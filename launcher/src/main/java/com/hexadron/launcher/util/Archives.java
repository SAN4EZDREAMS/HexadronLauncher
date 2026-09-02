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

package com.hexadron.launcher.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Archive unpacking for whole directory trees.
 *
 * <p>{@link com.hexadron.launcher.install.NativesExtractor} flattens a jar into
 * one folder and is right to; this does the opposite job - reproducing a tree
 * exactly, with its file modes and its symbolic links - which is what a Java
 * runtime is. On macOS and Linux a runtime that lost its symlinks or its execute
 * bits does not start.
 *
 * <p>Tar is read here rather than with a library because the launcher core has
 * no third-party dependencies (see launcher/build.gradle) and because the subset
 * that matters - ustar and the two GNU extensions Temurin's tarballs use - is
 * small enough to be read in full on one screen.
 */
public final class Archives {

    private static final int TAR_BLOCK = 512;

    private Archives() {
    }

    /**
     * Unpacks an archive into {@code targetDir}, dropping {@code stripLeading}
     * leading path components from every entry.
     *
     * <p>Distribution archives wrap everything in one versioned folder
     * ({@code jdk-17.0.20.1+1-jre/...}); stripping it puts {@code bin} and
     * {@code lib} directly where they are expected, so nothing downstream has to
     * guess the folder's name.
     */
    public static void extract(Path archive, Path targetDir, int stripLeading) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            extractZip(archive, targetDir, stripLeading);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archive, targetDir, stripLeading);
        } else {
            throw new IOException("unsupported archive format: " + archive.getFileName());
        }
    }

    // ------------------------------------------------------------------- zip

    private static void extractZip(Path archive, Path targetDir, int stripLeading) throws IOException {
        Path root = prepare(targetDir);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String relative = strip(entry.getName(), stripLeading);
                if (relative == null) {
                    continue;
                }
                Path destination = resolveSafely(root, relative);
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                // A zip written on Windows carries no modes. Executability is
                // decided by location instead: everything in bin has to run.
                if (isInBinDirectory(relative)) {
                    addExecuteBit(destination);
                }
            }
        }
    }

    // ------------------------------------------------------------------- tar

    private static void extractTarGz(Path archive, Path targetDir, int stripLeading) throws IOException {
        Path root = prepare(targetDir);
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive), 65536)) {
            byte[] header = new byte[TAR_BLOCK];
            String pendingLongName = null;

            while (true) {
                if (!readFully(in, header)) {
                    break;
                }
                if (isAllZero(header)) {
                    break; // End-of-archive marker.
                }

                String name = pendingLongName != null ? pendingLongName : field(header, 0, 100);
                pendingLongName = null;
                String prefix = field(header, 345, 155);
                if (!prefix.isEmpty() && !name.startsWith(prefix)) {
                    name = prefix + "/" + name;
                }
                long size = octal(header, 124, 12);
                int mode = (int) octal(header, 100, 8);
                char type = (char) (header[156] & 0xFF);
                String linkTarget = field(header, 157, 100);

                // GNU long name: the next block holds the real name.
                if (type == 'L') {
                    pendingLongName = readString(in, size);
                    skipPadding(in, size);
                    continue;
                }
                // pax and GNU extended headers: not needed for these archives.
                if (type == 'x' || type == 'g' || type == 'K') {
                    skip(in, size);
                    skipPadding(in, size);
                    continue;
                }

                String relative = strip(name, stripLeading);
                if (relative == null) {
                    skip(in, size);
                    skipPadding(in, size);
                    continue;
                }
                Path destination = resolveSafely(root, relative);

                switch (type) {
                    case '5' -> Files.createDirectories(destination);
                    case '1', '2' -> {
                        Files.createDirectories(destination.getParent());
                        link(destination, linkTarget, type == '2', root);
                    }
                    case '0', '\0' -> {
                        Files.createDirectories(destination.getParent());
                        copy(in, destination, size);
                        applyMode(destination, mode);
                    }
                    default -> skip(in, size); // Character/block devices, fifos: not ours.
                }
                skipPadding(in, size);
            }
        }
    }

    /**
     * Recreates a link from the archive.
     *
     * <p>A symlink is written as a symlink where the platform allows it. Windows
     * needs Developer Mode or elevation for that, so a failure falls back to
     * copying the target - which is what the file system can express there, and
     * is why this is not treated as a fatal error.
     */
    private static void link(Path destination, String target, boolean symbolic, Path root)
            throws IOException {
        Files.deleteIfExists(destination);
        if (symbolic) {
            try {
                Files.createSymbolicLink(destination, destination.getParent()
                        .getFileSystem().getPath(target));
                return;
            } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
                // Fall through to the copy below.
            }
        }
        Path source = symbolic
                ? destination.getParent().resolve(target).normalize()
                : resolveSafely(root, target);
        if (Files.isRegularFile(source)) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------- utilities

    private static Path prepare(Path targetDir) throws IOException {
        Path root = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root;
    }

    /**
     * Resolves an entry name inside the target directory, and refuses anything
     * that would land outside it.
     *
     * <p>An archive is untrusted input even when it came from a host we chose:
     * an entry named {@code ../../../autorun} is the whole of the "zip slip"
     * class of bugs, and the check belongs at the point of resolution rather
     * than in a caller that might forget.
     */
    private static Path resolveSafely(Path root, String relative) throws IOException {
        Path destination = root.resolve(relative).normalize();
        if (!destination.startsWith(root)) {
            throw new IOException("refusing to unpack outside the target directory: " + relative);
        }
        return destination;
    }

    /** Drops leading path components; null when the entry is the stripped folder itself. */
    private static String strip(String name, int stripLeading) {
        String normalised = name.replace('\\', '/');
        while (normalised.startsWith("/")) {
            normalised = normalised.substring(1);
        }
        for (int i = 0; i < stripLeading; i++) {
            int slash = normalised.indexOf('/');
            if (slash < 0) {
                return null;
            }
            normalised = normalised.substring(slash + 1);
        }
        while (normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return normalised.isEmpty() ? null : normalised;
    }

    private static boolean isInBinDirectory(String relative) {
        return relative.equals("bin") || relative.startsWith("bin/")
                || relative.contains("/bin/");
    }

    private static void applyMode(Path file, int mode) {
        if ((mode & 0111) != 0) {
            addExecuteBit(file);
        }
    }

    private static void addExecuteBit(Path file) {
        try {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(file));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows has no POSIX permissions and needs none.
        }
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int count = in.read(buffer, read, buffer.length - read);
            if (count < 0) {
                return read != 0 && fail("truncated archive");
            }
            read += count;
        }
        return true;
    }

    private static boolean fail(String message) throws IOException {
        throw new IOException(message);
    }

    private static void copy(InputStream in, Path destination, long size) throws IOException {
        try (var out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            long remaining = size;
            while (remaining > 0) {
                int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    throw new IOException("truncated archive while writing " + destination.getFileName());
                }
                out.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private static String readString(InputStream in, long size) throws IOException {
        byte[] data = new byte[(int) size];
        if (!readFully(in, data)) {
            throw new IOException("truncated archive header");
        }
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) {
            end--;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    private static void skip(InputStream in, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }
                remaining--;
                continue;
            }
            remaining -= skipped;
        }
    }

    /** Tar pads every entry to a 512-byte boundary. */
    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK;
        skip(in, padding);
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] header, int offset, int length) {
        String value = field(header, offset, length).trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isAllZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** Removes a directory tree. Used to clear a half-written runtime. */
    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // A broken symlink cannot be read but can still be deleted.
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
