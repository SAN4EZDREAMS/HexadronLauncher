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

package com.hexadron.launcher.update;

import com.hexadron.launcher.util.Archives;
import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The half of an update that cannot run inside the launcher.
 *
 * <h2>Why a second process</h2>
 *
 * <p>Because the folder being replaced is the folder the launcher is running
 * from. On Windows an open file cannot be deleted or renamed at all, and on the
 * other two a program that deletes its own runtime mid-sentence is asking for
 * the failure it gets. So the launcher downloads and unpacks, starts this from
 * the <em>new</em> build's runtime, and exits. This waits for it to be gone and
 * then does the swap.
 *
 * <h2>What it does, in order</h2>
 *
 * <ol>
 *   <li>Waits for the launcher's process to end.</li>
 *   <li>Moves the installed folder aside rather than deleting it. That is the
 *       whole of the safety here: until the new one is in place, the old one is
 *       one rename away.</li>
 *   <li>Copies the new build into place. A copy and not a move, because this
 *       process is running out of the new build - its own jar and its own
 *       runtime are in there, and a folder cannot be moved out from under a
 *       running program on Windows either.</li>
 *   <li>Deletes the old folder, and starts the launcher again.</li>
 * </ol>
 *
 * <p>If anything in the middle fails, the folder that was moved aside is put
 * back and that launcher is started. A failed update leaves the user with the
 * version they had, which is the only acceptable outcome for a program that
 * replaces itself.
 *
 * <p>What it deliberately does not do is delete the folder it is running from.
 * That is left to the launcher's next start; see {@link Updates#cleanUp}.
 */
public final class Updater {

    /** How long to wait for the launcher to close before giving up on it. */
    private static final Duration EXIT_WAIT = Duration.ofSeconds(60);

    /** How many times a move is retried, and how long between the tries. */
    private static final int MOVE_ATTEMPTS = 20;
    private static final long MOVE_PAUSE_MILLIS = 250;

    private Updater() {
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            log("usage: Updater <new-image> <installed-folder> <launcher-pid> [work-folder]");
            System.exit(2);
            return;
        }
        Path staged = Path.of(args[0]);
        Path target = Path.of(args[1]);
        long pid = parsePid(args[2]);
        // Optional, so that an updater from this build still runs when started
        // by an older launcher that did not pass it.
        Path workDir = args.length > 3 ? Path.of(args[3]) : null;

        log("update: " + staged + " -> " + target + ", waiting for process " + pid);
        waitForExit(pid);

        Path aside = target.resolveSibling(target.getFileName() + Updates.OLD_SUFFIX
                + System.currentTimeMillis());
        boolean movedAside = false;
        try {
            if (Files.exists(target)) {
                moveWithRetries(target, aside);
                movedAside = true;
                log("the installed folder was moved to " + aside.getFileName());
            }
            Updates.copyTree(staged, target);
            log("the new build is in place");

            // The downloaded archive is half of what is left lying about and
            // nothing holds it - it was read once and closed. Deleted here
            // rather than left for the next start, because this is the last
            // moment at which its folder is certainly known.
            dropDownloads(workDir);

            // Started before the old folder is removed, not after. The update is
            // finished the moment the new build is in place; making the user
            // watch an empty screen while a folder that no longer matters is
            // deleted - for up to five seconds, if something is holding it -
            // would be charging them for housekeeping.
            start(target);
            if (movedAside) {
                deleteQuietly(aside);
            }
            log("done");
        } catch (Exception failure) {
            log("the update failed: " + failure);
            rollBack(target, aside, movedAside);
            System.exit(1);
        }
    }

    /**
     * Deletes the downloaded archives, and only those.
     *
     * <p>The files directly inside the work folder are what was fetched: the
     * archive, or the parts a delta update fetched. Once the new build is in
     * place none of them is of any further use, and together they are the
     * largest single thing an update leaves behind - a hundred and fifty
     * megabytes of it.
     *
     * <p>Named by what is kept rather than by what goes: the update log, which
     * is the only account of this run, and the handoff note, which the next
     * launcher reads to know when this process has finished. The unpacked tree
     * is not touched either - this process is running out of it.
     */
    private static void dropDownloads(Path workDir) {
        if (workDir == null || !Files.isDirectory(workDir)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.list(workDir)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> !"update.log".equals(file.getFileName().toString()))
                    .filter(file -> !Updates.HANDOFF_FILE.equals(file.getFileName().toString()))
                    .forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            log("could not delete " + file.getFileName() + ": " + e);
                        }
                    });
        } catch (IOException | RuntimeException e) {
            log("the downloads could not be cleared: " + e);
        }
    }

    /**
     * Puts back what was there.
     *
     * <p>Whatever was half-copied into place is removed first: an image missing
     * the files that come after the failure will not start, and leaving it there
     * would hide the working copy behind it.
     */
    private static void rollBack(Path target, Path aside, boolean movedAside) {
        if (!movedAside) {
            start(target);
            return;
        }
        try {
            if (Files.exists(target)) {
                Archives.deleteRecursively(target);
            }
            moveWithRetries(aside, target);
            log("the previous version was put back");
        } catch (Exception e) {
            // Both folders exist and neither is where it should be. Said out
            // loud in the log, with the name of the folder to rename by hand,
            // because there is nothing else left to try.
            log("could not put the previous version back: " + e);
            log("the previous version is in " + aside + " - rename it to "
                    + target.getFileName() + " to restore it");
            return;
        }
        start(target);
    }

    private static void waitForExit(long pid) {
        Optional<ProcessHandle> handle = pid > 0 ? ProcessHandle.of(pid) : Optional.empty();
        if (handle.isEmpty()) {
            // Already gone, or never there. Either way there is nothing to wait
            // for - and the retries around the move cover the rest.
            return;
        }
        try {
            handle.get().onExit().get(EXIT_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log("the launcher did not close within " + EXIT_WAIT.toSeconds() + "s: " + e);
        }
    }

    /**
     * Moves a folder, waiting out whatever is still holding it.
     *
     * <p>A process that has just exited has not necessarily released its files
     * yet - antivirus software on Windows is a common reason, and the launcher's
     * own shutdown another. Twenty tries a quarter of a second apart is five
     * seconds of patience, which costs nothing and turns a race into a wait.
     */
    private static void moveWithRetries(Path source, Path destination) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < MOVE_ATTEMPTS; attempt++) {
            try {
                Files.move(source, destination);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(MOVE_PAUSE_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last == null ? new IOException("could not move " + source) : last;
    }

    /**
     * Removes the folder that was moved aside, waiting out what is holding it.
     *
     * <p>The same patience as the move, for the same reason: an antivirus reads
     * a folder that has just been renamed, and a folder being read cannot be
     * removed on Windows. What that patience will not outlast is somebody
     * looking at the folder in Explorer, and there is no answer to that from
     * here - so a folder that will not go is left, and the launcher's next start
     * clears it ({@link Updates#cleanUp}). The update itself is already done;
     * this is housekeeping.
     */
    private static void deleteQuietly(Path path) {
        for (int attempt = 0; attempt < MOVE_ATTEMPTS; attempt++) {
            if (!Files.exists(path)) {
                return;
            }
            // Every pass removes what it can and names what it could not, so a
            // folder with one busy file in it still loses the rest, and the
            // line below says which file to blame.
            List<Path> left = Archives.deleteWhatCan(path);
            if (left.isEmpty()) {
                return;
            }
            if (attempt == MOVE_ATTEMPTS - 1) {
                log("the old folder could not be deleted, " + left.size()
                        + " path(s) are held, first is " + left.get(0));
                log("it will be removed the next time the launcher starts");
                return;
            }
            try {
                Thread.sleep(MOVE_PAUSE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Starts the launcher that is now in the folder. */
    private static void start(Path root) {
        List<String> command = new UpdateInstall(root, Platform.os()).relaunchCommand();
        try {
            log("starting " + String.join(" ", command));
            new ProcessBuilder(command).directory(root.toFile()).start();
        } catch (IOException e) {
            log("the launcher could not be started: " + e);
        }
    }

    private static long parsePid(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The log for an update.
     *
     * <p>Standard output, which the launcher redirected into a file beside the
     * download before it exited. By the time any of this runs there is no window
     * to report anything in, and this file is the only account of what happened
     * between one launcher closing and the next one opening.
     */
    private static void log(String message) {
        System.out.println("[" + java.time.LocalDateTime.now() + "] " + message);
        System.out.flush();
    }
}
