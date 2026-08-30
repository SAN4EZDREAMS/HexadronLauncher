package com.hexadron.launcher.net;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.core.VerifiedFiles;
import com.hexadron.launcher.util.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
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

    /**
     * Attempts per mirror before moving to the next one.
     *
     * <p>{@link Http#openStream} already retries the connection and the status.
     * This covers what it cannot: a transfer that breaks partway, which on a
     * shaky connection is the ordinary failure and used to fail the whole
     * install on the first occurrence.
     */
    private static final int ATTEMPTS_PER_URL = 3;

    private final int concurrency;

    /**
     * What has already been checked, so a file is not read to be told what it
     * was told last time.
     *
     * <p>Defaults to the ledger that knows nothing, which is exactly the old
     * behaviour: every file with a published hash gets hashed. The application
     * hands it a real one; a repair hands it {@link VerifiedFiles#DISABLED}
     * again so that the whole install is read from disk.
     */
    private volatile VerifiedFiles verified = VerifiedFiles.DISABLED;

    /**
     * Whether the ledger may be believed, as opposed to only written to.
     *
     * <p>Two different things, and keeping them apart is what makes "verify
     * everything" affordable to turn on and off. With this false every file with
     * a published hash is read and hashed - the ledger answers nothing - but
     * what matched is still recorded. So a user who tries the setting and turns
     * it off again does not pay for a cold ledger afterwards, and a user who
     * leaves it on is not quietly building a record nobody reads.
     *
     * <p>Never a shortcut: false can only ever mean more work, never less.
     */
    private volatile boolean trustLedger = true;

    public Downloader() {
        this(DEFAULT_CONCURRENCY);
    }

    public Downloader(int concurrency) {
        this.concurrency = Math.max(1, concurrency);
    }

    /** @see #verified */
    public Downloader verified(VerifiedFiles ledger) {
        this.verified = ledger == null ? VerifiedFiles.DISABLED : ledger;
        return this;
    }

    /** @see #verified */
    public VerifiedFiles verified() {
        return verified;
    }

    /** @see #trustLedger */
    public Downloader trustLedger(boolean value) {
        this.trustLedger = value;
        return this;
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
        if (failures.isEmpty()) {
            return;
        }
        for (Failure failure : failures) {
            if (failure.cause() instanceof Http.OfflineException offline) {
                // Thrown as itself rather than folded into a list of file
                // failures. There is one fault here and it is not the files:
                // callers that can carry on without the network - a launch of
                // something already installed - recognise this type, and the
                // user gets a sentence about their connection rather than a
                // catalogue of jars.
                throw offline;
            }
        }
        throw new DownloadFailedException(failures);
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

        // Set by the first task to find the host unreachable. Everything still
        // queued gives up immediately rather than waiting out its own timeout:
        // on a blocked network an asset index is eight thousand tasks, and
        // eight thousand twenty-second waits is forty minutes of a progress bar
        // that was never going to finish.
        java.util.concurrent.atomic.AtomicReference<Http.OfflineException> offline =
                new java.util.concurrent.atomic.AtomicReference<>();

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
                    if (progress.isCancelled() || offline.get() != null) {
                        return;
                    }
                    try {
                        fetch(task);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add(new Failure(task, e));
                    } catch (Http.OfflineException e) {
                        offline.compareAndSet(null, e);
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

            // A transfer that dies after the stream was handed over cannot be
            // retried inside Http - the stream belongs to this method by then -
            // so the second half of the retry policy lives here. A half-written
            // .part is deleted before each new attempt, so a resumed attempt
            // never appends to the last one's leftovers.
            for (int attempt = 1; attempt <= ATTEMPTS_PER_URL; attempt++) {
                try {
                    try (InputStream in = Http.openStream(url);
                         OutputStream out = Files.newOutputStream(temp)) {
                        in.transferTo(out);
                    }

                    if (task.sha1() != null) {
                        String actual = Hashes.sha1(temp);
                        if (!actual.equalsIgnoreCase(task.sha1())) {
                            Files.deleteIfExists(temp);
                            // Not retried: a mirror serving the wrong bytes will
                            // serve them again. The next URL might not.
                            lastFailure = new IOException("checksum mismatch for "
                                    + task.description() + " from " + url
                                    + ": expected " + task.sha1() + ", got " + actual);
                            break;
                        }
                    }

                    moveIntoPlace(temp, destination);
                    if (task.executable()) {
                        makeExecutable(destination);
                    }
                    // Just written and just verified, so the next launch has no
                    // reason to read it again.
                    verified.record(destination, task.sha1(),
                            VerifiedFiles.attributesOf(destination));
                    return;

                } catch (Http.OfflineException e) {
                    // The host was never reached. Neither another attempt nor
                    // another mirror on the same dead network will change that,
                    // and eight thousand assets each waiting out a timeout is
                    // how a launcher appears to hang instead of reporting.
                    discard(temp);
                    throw e;
                } catch (IOException e) {
                    lastFailure = e;
                    discard(temp);
                    if (attempt < ATTEMPTS_PER_URL) {
                        Thread.sleep(400L * (1L << (attempt - 1)));
                    }
                }
            }
        }
        throw lastFailure != null
                ? lastFailure
                : new IOException("no usable URL for " + task.description());
    }

    /**
     * Whether the file is already what the task would download.
     *
     * <p>One {@code stat} up front, and the ledger consulted before the file is
     * read: on a launch where nothing has changed this is the whole of the work,
     * and the several thousand file reads it replaces are the difference between
     * a game that starts and a launcher that appears to have hung.
     *
     * <p>A file the ledger does not vouch for is hashed exactly as before, and
     * recorded once it matches - so the first launch after this change is as
     * slow as every launch was, and the second is not.
     */
    private static void discard(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // Best effort: the next attempt truncates it anyway.
        }
    }

    private boolean isAlreadyValid(DownloadTask task) {
        Path destination = task.destination();
        BasicFileAttributes attributes = VerifiedFiles.attributesOf(destination);
        if (attributes == null) {
            return false;
        }
        if (task.sha1() != null) {
            if (trustLedger && verified.isVerified(destination, task.sha1(), attributes)) {
                return true;
            }
            if (!Hashes.matchesSha1(destination, task.sha1())) {
                return false;
            }
            // Recorded only if the file did not change while it was being read.
            // The attributes above were taken before the hash, so writing them
            // down together with a hash computed after it would put a record in
            // the ledger describing a state that was never actually verified -
            // and the whole value of the ledger is that everything in it was.
            BasicFileAttributes after = VerifiedFiles.attributesOf(destination);
            if (after != null
                    && after.size() == attributes.size()
                    && after.lastModifiedTime().equals(attributes.lastModifiedTime())) {
                verified.record(destination, task.sha1(), after);
            }
            return true;
        }
        // No checksum published. Fall back to size when we have one, otherwise
        // trust existence - re-downloading unhashed artifacts on every launch
        // would make startup unusable.
        if (task.size() > 0) {
            return attributes.size() == task.size();
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
