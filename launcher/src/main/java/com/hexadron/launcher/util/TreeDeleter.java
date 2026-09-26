/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.util;

import com.hexadron.launcher.core.Progress;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Deletes many files quickly, and says how far it has got.
 *
 * <h2>Why not one walk, one file at a time</h2>
 *
 * <p>An instance with a modpack in it is tens of thousands of files, and on
 * Windows each delete is a round trip through the file system filter drivers -
 * the antivirus first among them. One at a time, that is minutes of a launcher
 * that looks hung. The deletes do not depend on each other, so they are spread
 * over a few threads; the directories go afterwards, deepest first, once they
 * are empty.
 *
 * <p>The files are counted before any is deleted, so the bar can move from 0
 * to 100 rather than spin.
 *
 * <h2>What it never does</h2>
 *
 * <p>Follow a link. A symbolic link, and on Windows a junction, is deleted as
 * the link it is, and what it points to is not touched: a mods folder linked to
 * a shared one elsewhere must not take the shared one with it.
 */
public final class TreeDeleter {

    /** Below this many files the threads cost more than they save. */
    private static final int PARALLEL_FROM = 64;

    /** Files handed to a thread at once. */
    private static final int CHUNK = 128;

    private TreeDeleter() {
    }

    /**
     * What was deleted and what was left.
     *
     * @param deleted how many files and folders were removed
     * @param failed  what could not be removed - on Windows, almost always a
     *                file another program still has open
     */
    public record Outcome(int deleted, List<Path> failed) {

        public Outcome {
            failed = List.copyOf(failed);
        }

        public boolean isComplete() {
            return failed.isEmpty();
        }
    }

    /**
     * Deletes a folder and everything in it.
     *
     * @param counted told how many files there are, once they have been
     *                counted and before the first is deleted; may be null
     */
    public static Outcome deleteTree(Path root, Progress progress, IntConsumer counted)
            throws InterruptedException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return new Outcome(0, List.of());
        }
        // The start folder too. A folder moved to another drive with a
        // symbolic link or an NTFS junction reads as a directory; walking it
        // would empty the folder it points at, not remove the link.
        try {
            BasicFileAttributes top = Files.readAttributes(root, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (top.isSymbolicLink() || top.isOther()) {
                try {
                    Files.delete(root);
                    if (counted != null) {
                        counted.accept(1);
                    }
                    return new Outcome(1, List.of());
                } catch (IOException e) {
                    return new Outcome(0, List.of(root));
                }
            }
        } catch (IOException e) {
            return new Outcome(0, List.of(root));
        }
        List<Path> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        List<Path> failed = Collections.synchronizedList(new ArrayList<>());
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    // A junction reads as a directory that is also "other".
                    // Its contents belong to the folder it points at.
                    if (!directory.equals(root)
                            && (attributes.isSymbolicLink() || attributes.isOther())) {
                        files.add(directory);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    directories.add(directory);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    // A broken link cannot be read and can still be deleted.
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            failed.add(root);
            return new Outcome(0, failed);
        }
        if (counted != null) {
            counted.accept(files.size());
        }

        int total = files.size() + directories.size();
        AtomicInteger done = new AtomicInteger();
        progress.items(0, total);
        int deleted = deleteAll(files, failed, done, total, progress);

        // Deepest first: a walk lists a folder before what is in it, so the
        // list read backwards has every folder after its contents.
        for (int i = directories.size() - 1; i >= 0; i--) {
            if (delete(directories.get(i), failed)) {
                deleted++;
            }
            progress.items(done.incrementAndGet(), total);
        }
        progress.items(total, total);
        return new Outcome(deleted, failed);
    }

    /**
     * Deletes the given files, then any folder they leave empty.
     *
     * <p>For a removal that knows exactly what it wrote - a modpack - and must
     * not take anything else with it. A folder is removed only when it is
     * empty, so {@code config} holding one of the pack's files and one of the
     * player's is a folder that stays.
     *
     * @param stopAt folders at or above this one are never removed
     */
    public static Outcome deleteFiles(Collection<Path> paths, Path stopAt, Progress progress)
            throws InterruptedException {
        List<Path> files = new ArrayList<>(new LinkedHashSet<>(paths));
        List<Path> failed = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger();
        progress.items(0, files.size());
        int deleted = deleteAll(files, failed, done, files.size(), progress);

        Path root = stopAt.toAbsolutePath().normalize();
        Set<Path> parents = new LinkedHashSet<>();
        for (Path file : files) {
            Path parent = file.toAbsolutePath().normalize().getParent();
            while (parent != null && !parent.equals(root) && parent.startsWith(root)
                    && parents.add(parent)) {
                parent = parent.getParent();
            }
        }
        List<Path> ordered = new ArrayList<>(parents);
        ordered.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path directory : ordered) {
            try {
                Files.deleteIfExists(directory);
            } catch (DirectoryNotEmptyException e) {
                // Something of the player's is still in it. That is the point.
            } catch (IOException | RuntimeException e) {
                // Not worth a line: the files in it are what the report is about.
            }
        }
        progress.items(files.size(), files.size());
        return new Outcome(deleted, failed);
    }

    // ---------------------------------------------------------------- pieces

    private static int deleteAll(List<Path> files, List<Path> failed, AtomicInteger done,
                                 int total, Progress progress) throws InterruptedException {
        if (files.size() < PARALLEL_FROM) {
            int deleted = 0;
            for (Path file : files) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("deletion interrupted");
                }
                if (delete(file, failed)) {
                    deleted++;
                }
                progress.items(done.incrementAndGet(), total);
            }
            return deleted;
        }

        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "hexadron-delete-worker");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger deleted = new AtomicInteger();
        try {
            List<Future<?>> chunks = new ArrayList<>();
            for (int from = 0; from < files.size(); from += CHUNK) {
                List<Path> chunk = files.subList(from, Math.min(from + CHUNK, files.size()));
                chunks.add(pool.submit(() -> {
                    for (Path file : chunk) {
                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        if (delete(file, failed)) {
                            deleted.incrementAndGet();
                        }
                        progress.items(done.incrementAndGet(), total);
                    }
                }));
            }
            for (Future<?> chunk : chunks) {
                try {
                    chunk.get();
                } catch (ExecutionException e) {
                    // delete() catches everything it can meet; a failure here
                    // is one it could not, and the files stay listed as failed
                    // by the check below.
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            throw e;
        } finally {
            pool.shutdown();
        }
        return deleted.get();
    }

    /** One entry, with a second try for a read-only file. True when it was there and is gone. */
    private static boolean delete(Path path, List<Path> failed) {
        try {
            return Files.deleteIfExists(path);
        } catch (DirectoryNotEmptyException e) {
            failed.add(path);
            return false;
        } catch (IOException | RuntimeException first) {
            // A lock or a read-only attribute. Clearing the attribute tells them apart.
        }
        Archives.makeWritable(path);
        try {
            return Files.deleteIfExists(path);
        } catch (IOException | RuntimeException second) {
            failed.add(path);
            return false;
        }
    }
}
