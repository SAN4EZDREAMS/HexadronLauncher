/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.update;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.util.Archives;
import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * Updating the launcher itself: what is available, fetching it, and handing over.
 *
 * <h2>The shape of the thing</h2>
 *
 * <p>An update replaces a folder that the running program is inside. No process
 * can do that to itself - on Windows it cannot even delete its own executable
 * while it runs - so the work is split in two. This class does everything that
 * can be done while the launcher is up: ask what exists, download it, unpack it
 * beside the installation. {@link Updater} does the rest, in a separate process
 * started from the <em>unpacked copy</em>, after this one has exited.
 *
 * <h2>Where the files go</h2>
 *
 * <p>Into {@code .hexadron-update} next to the installed folder, and not into
 * the system temporary directory. Two reasons, both practical: replacing the
 * installation is a move within one folder rather than a copy across
 * filesystems, and a machine where the installation cannot be written to is
 * found out before a hundred and fifty megabytes have been downloaded rather
 * than after.
 */
public final class Updates {

    /** The folder the download and the unpacked image live in, beside the install. */
    public static final String WORK_DIR = ".hexadron-update";

    private Updates() {
    }

    /**
     * A build that is newer than the one running.
     *
     * @param from    the version running now
     * @param release what was published
     * @param asset   the file for this machine
     */
    public record Available(AppVersion from, AppVersion to, ReleaseFeed.Release release,
                            ReleaseFeed.Asset asset) {

        /** The notes the author wrote, or an empty string. */
        public String notes() {
            return release.notes() == null ? "" : release.notes();
        }

        /** How much there is to download. */
        public long size() {
            return asset.size();
        }
    }

    /**
     * Asks the channel what it has, and answers whether it is newer.
     *
     * <p>Empty means "nothing to do", and that covers every ordinary case: the
     * newest build is the one running, the repository has published nothing on
     * this channel, or the release carries no file for this operating system.
     * Only a failure to ask at all is thrown, and the caller decides whether
     * that is worth a word - at start-up it is not.
     */
    public static Optional<Available> check(String currentVersion, UpdateChannel channel,
                                            ReleaseFeed feed, Platform.OsFamily os)
            throws IOException, InterruptedException {

        Optional<AppVersion> current = AppVersion.of(currentVersion);
        if (current.isEmpty()) {
            // A build whose own version cannot be read is not one to compare
            // anything against. Silence rather than a guess.
            return Optional.empty();
        }
        Optional<ReleaseFeed.Release> latest = feed.latest(channel);
        return latest.isEmpty() ? Optional.empty() : compare(currentVersion, latest.get(), os);
    }

    /**
     * Whether one published build is an update for this machine.
     *
     * <p>Separated from the request so that the decision - is it newer, is there
     * a file for this system - can be checked against releases written by hand,
     * with no network and no repository.
     */
    public static Optional<Available> compare(String currentVersion,
                                              ReleaseFeed.Release release,
                                              Platform.OsFamily os) {
        Optional<AppVersion> current = AppVersion.of(currentVersion);
        if (current.isEmpty() || release == null) {
            return Optional.empty();
        }
        Optional<AppVersion> published = release.version();
        if (published.isEmpty() || !published.get().isNewerThan(current.get())) {
            return Optional.empty();
        }
        return release.assetFor(os)
                .map(asset -> new Available(current.get(), published.get(), release, asset));
    }

    /** Where the download and the unpacked image go for this installation. */
    public static Path workDirectory(UpdateInstall install) {
        Path parent = install.root().getParent();
        return parent == null ? install.root().resolve(WORK_DIR) : parent.resolve(WORK_DIR);
    }

    /**
     * Fetches the published file, reporting bytes as they arrive.
     *
     * <p>Written to its own name inside the work folder and checked against the
     * length the platform published. A short file is a cut connection, and
     * unpacking one produces a folder that is missing whatever came after the
     * cut - which would then be moved over a working installation.
     */
    public static Path download(Available update, Path workDir, Progress progress)
            throws IOException, InterruptedException {

        Files.createDirectories(workDir);
        Path file = workDir.resolve(update.asset().name());
        Files.deleteIfExists(file);

        long expected = update.asset().size();
        long done = 0;
        try (InputStream in = Http.openStream(update.asset().url());
             OutputStream out = Files.newOutputStream(file)) {

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (progress.isCancelled()) {
                    throw new InterruptedIOException("the update was cancelled");
                }
                out.write(buffer, 0, read);
                done += read;
                progress.bytes(done, expected);
            }
        }
        if (expected > 0 && done != expected) {
            Files.deleteIfExists(file);
            throw new IOException("the download stopped early: " + done + " of " + expected
                    + " bytes");
        }
        return file;
    }

    /**
     * Unpacks the downloaded archive and finds the application image in it.
     *
     * <p>Through the launcher's own archive reader, which keeps symbolic links
     * and the executable bit - both of which an update must not lose. A macOS
     * bundle is full of links inside its runtime, and a Linux image whose
     * launcher is no longer executable is an update that ends with nothing
     * starting.
     */
    public static Path unpack(Path archive, Path workDir, Platform.OsFamily os) throws IOException {
        Path unpacked = workDir.resolve("unpacked");
        if (Files.exists(unpacked)) {
            Archives.deleteRecursively(unpacked);
        }
        Archives.extract(archive, unpacked, 0);
        return UpdateInstall.imageIn(unpacked, os).orElseThrow(() -> new IOException(
                "the downloaded archive holds no application image for " + os));
    }

    /**
     * Starts the process that does the replacing, and returns.
     *
     * <p>The caller's next act is to exit: the updater is waiting for exactly
     * that before it touches anything.
     *
     * <p>It is started from the <em>new</em> build's runtime and jar, in the
     * work folder. Running it from the installed copy would mean a process
     * holding open the very files it has to replace, which on Windows is not a
     * race to be won but a rule: an open file cannot be deleted or renamed.
     */
    public static void handOver(Path stagedImage, UpdateInstall install, Path workDir)
            throws IOException {

        UpdateInstall staged = new UpdateInstall(stagedImage, install.os());
        Path java = staged.javaExecutable();
        if (!Files.isRegularFile(java)) {
            throw new IOException("the downloaded build has no runtime at " + java);
        }
        Path jar = launcherJarIn(staged).orElseThrow(() -> new IOException(
                "the downloaded build has no launcher jar in " + staged.appDirectory()));

        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(jar.toString());
        command.add(Updater.class.getName());
        command.add(stagedImage.toString());
        command.add(install.root().toString());
        command.add(String.valueOf(ProcessHandle.current().pid()));

        // The log is the only thing left behind if this goes wrong after the
        // window has closed, so it is written where the next start can find it.
        Path log = workDir.resolve("update.log");
        new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
    }

    /**
     * The jar in an image that carries this class.
     *
     * <p>Found rather than named. The launcher jar's name has the version in it,
     * and the folder beside it holds JavaFX's jars as well - and the updater
     * needs exactly the one that has the code it is about to run.
     */
    public static Optional<Path> launcherJarIn(UpdateInstall image) {
        String entry = Updater.class.getName().replace('.', '/') + ".class";
        for (String candidate : image.appJars()) {
            Path jar = Path.of(candidate);
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                if (zip.getEntry(entry) != null) {
                    return Optional.of(jar);
                }
            } catch (IOException | RuntimeException ignored) {
                // Not a readable jar, so not the one.
            }
        }
        return Optional.empty();
    }

    /**
     * Clears what an update left behind.
     *
     * <p>Called at start-up, because the updater cannot delete the folder it is
     * itself running from - its own copy of the runtime and the jar are in it.
     * By the time the launcher is up again, that process has ended and the
     * folder is nobody's.
     */
    public static void cleanUp(UpdateInstall install) {
        Path workDir = workDirectory(install);
        if (!Files.isDirectory(workDir)) {
            return;
        }
        try {
            Archives.deleteRecursively(workDir);
        } catch (IOException | RuntimeException ignored) {
            // Still in use, or not ours to delete. It is a cache folder beside
            // the installation; the next start tries again.
        }
    }

    /**
     * Moves a folder, falling back to a copy when it cannot be moved.
     *
     * <p>Used by the updater. Kept here so that the same rules - links kept,
     * permissions kept - are written once.
     */
    public static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException ignored) {
            // Different filesystems, or a platform that will not move a folder
            // with anything open under it. Copy instead.
        }
        copyTree(source, target);
    }

    /**
     * Copies a folder as it is: links as links, permissions as they were.
     *
     * <p>{@code Files.walk} plus a plain copy would follow every symbolic link
     * and turn a macOS bundle's runtime into several copies of itself, and would
     * drop the executable bit from the launcher binary on Linux.
     */
    public static void copyTree(Path source, Path target) throws IOException {
        java.nio.file.Files.walkFileTree(source, java.util.Set.of(), Integer.MAX_VALUE,
                new java.nio.file.SimpleFileVisitor<Path>() {

            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Path destination = target.resolve(source.relativize(file).toString());
                Files.createDirectories(destination.getParent());
                if (attributes.isSymbolicLink()) {
                    Files.createSymbolicLink(destination, Files.readSymbolicLink(file));
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
