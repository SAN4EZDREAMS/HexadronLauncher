package com.hexadron.launcher.net;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.util.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parallel, verifying, resumable-by-skip downloader.
 *
 * <p>Behaviour that matters for correctness:
 * <ul>
 *   <li>A file whose SHA-1 already matches is skipped, so re-running an install
 *       is cheap and interrupted installs recover.</li>
 *   <li>Every download lands in a sibling {@code .part} file and is verified
 *       before being moved into place, so a killed launcher can never leave a
 *       truncated jar that later passes an existence check.</li>
 *   <li>Failures are collected rather than thrown immediately, so one dead
 *       mirror does not abort a 600-file install before the report is useful.</li>
 * </ul>
 */
public final class Downloader {

    /** Concurrency cap. Mojang's CDN is happy here; going much higher mostly adds timeouts. */
    private static final int DEFAULT_CONCURRENCY = 12;

    private final int concurrency;

    public Downloader() {
        this(DEFAULT_CONCURRENCY);
    }

    public Downloader(int concurrency) {
        this.concurrency = Math.max(1, concurrency);
    }

    /** A task that could not be fetched from any of its URLs. */
    public record Failure(DownloadTask task, Exception cause) {
        @Override
        public String toString() {
            return task.description() + " <- " + task.urls() + " : " + cause;
        }
    }

    public static final class DownloadFailedException extends IOException {
        private final transient List<Failure> failures;

        DownloadFailedException(List<Failure> failures) {
            super(buildMessage(failures));
            this.failures = failures;
        }

        public List<Failure> failures() {
            return failures;
        }

        private static String buildMessage(List<Failure> failures) {
            StringBuilder sb = new StringBuilder(failures.size() + " download(s) failed:");
            int shown = 0;
            for (Failure f : failures) {
                if (shown++ == 10) {
                    sb.append("\n  ... and ").append(failures.size() - 10).append(" more");
                    break;
                }
                sb.append("\n  ").append(f);
            }
            return sb.toString();
        }
    }

    /**
     * Runs every task, then throws if any failed.
     *
     * @throws DownloadFailedException with the full failure list
     */
    public void run(List<DownloadTask> tasks, Progress progress) throws IOException, InterruptedException {
        List<Failure> failures = runCollecting(tasks, progress);
        if (!failures.isEmpty()) {
            throw new DownloadFailedException(failures);
        }
    }

    /** Runs every task and returns the failures instead of throwing. */
    public List<Failure> runCollecting(List<DownloadTask> tasks, Progress progress)
            throws InterruptedException {
        if (tasks.isEmpty()) {
            return List.of();
        }

        long totalBytes = tasks.stream().mapToLong(DownloadTask::sizeForProgress).sum();
        AtomicLong doneBytes = new AtomicLong();
        AtomicInteger doneItems = new AtomicInteger();
        List<Failure> failures = Collections.synchronizedList(new ArrayList<>());

        progress.items(0, tasks.size());
        progress.bytes(0, totalBytes);

        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "hexadron-download");
            t.setDaemon(true);
            return t;
        })) {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (DownloadTask task : tasks) {
                futures.add(pool.submit(() -> {
                    if (progress.isCancelled()) {
                        return;
                    }
                    try {
                        fetch(task);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add(new Failure(task, e));
                    } catch (Exception e) {
                        failures.add(new Failure(task, e));
                    } finally {
                        doneBytes.addAndGet(task.sizeForProgress());
                        int done = doneItems.incrementAndGet();
                        progress.items(done, tasks.size());
                        progress.bytes(doneBytes.get(), totalBytes);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    // Individual failures are already recorded above; this only fires
                    // for an unexpected error escaping the finally block.
                    failures.add(new Failure(tasks.get(0), new IOException(e.getCause())));
                }
            }
        }
        return List.copyOf(failures);
    }

    /**
     * Fetches one task, trying each URL in turn.
     * Returns immediately if the destination already matches the expected hash.
     */
    public void fetch(DownloadTask task) throws IOException, InterruptedException {
        Path destination = task.destination();

        if (isAlreadyValid(task)) {
            return;
        }

        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        IOException lastFailure = null;
        for (String url : task.urls()) {
            Path temp = destination.resolveSibling(destination.getFileName() + ".part");
            try {
                try (InputStream in = Http.openStream(url);
                     OutputStream out = Files.newOutputStream(temp)) {
                    in.transferTo(out);
                }

                if (task.sha1() != null) {
                    String actual = Hashes.sha1(temp);
                    if (!actual.equalsIgnoreCase(task.sha1())) {
                        Files.deleteIfExists(temp);
                        lastFailure = new IOException("checksum mismatch for " + task.description()
                                + " from " + url + ": expected " + task.sha1() + ", got " + actual);
                        continue;
                    }
                }

                moveIntoPlace(temp, destination);
                if (task.executable()) {
                    makeExecutable(destination);
                }
                return;
            } catch (IOException e) {
                lastFailure = e;
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
        throw lastFailure != null
                ? lastFailure
                : new IOException("no usable URL for " + task.description());
    }

    private boolean isAlreadyValid(DownloadTask task) {
        Path destination = task.destination();
        if (!Files.isRegularFile(destination)) {
            return false;
        }
        if (task.sha1() != null) {
            return Hashes.matchesSha1(destination, task.sha1());
        }
        // No checksum published. Fall back to size when we have one, otherwise
        // trust existence - re-downloading unhashed artifacts on every launch
        // would make startup unusable.
        if (task.size() > 0) {
            try {
                return Files.size(destination) == task.size();
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private static void moveIntoPlace(Path temp, Path destination) throws IOException {
        try {
            Files.move(temp, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void makeExecutable(Path path) {
        try {
            var perms = Files.getPosixFilePermissions(path);
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows has no POSIX permissions and needs none.
        }
    }
}
