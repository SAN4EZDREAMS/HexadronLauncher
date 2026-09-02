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

import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What a published build is made of, file by file.
 *
 * <h2>What this is for</h2>
 *
 * <p>An update is a hundred and something megabytes, and almost all of it is a
 * Java runtime that has not changed since the last build. A manifest is what
 * makes it possible to notice that: it lists every file in the published image
 * with its length and its SHA-256, grouped into parts that are published as
 * separate archives. A launcher can then check which of those files it already
 * has, take them from its own folder, and download only the parts it is missing.
 *
 * <h2>Why it makes the update safer rather than less safe</h2>
 *
 * <p>Three things, and they are the whole reason this is worth writing:
 *
 * <ul>
 *   <li>A file is reused from the current installation <em>only</em> when its
 *       SHA-256 is the one the manifest names. A file that something on the
 *       machine has altered does not match, so it is not reused - it is
 *       downloaded. Reuse cannot carry anything into the new build that the
 *       published manifest does not vouch for.</li>
 *   <li>Every file in the assembled image is checked against the manifest before
 *       anything is replaced, whether it was reused or downloaded.</li>
 *   <li>The full archive is checked too, when a manifest exists for it. Until
 *       this, the only thing verified about a downloaded update was its
 *       length.</li>
 * </ul>
 *
 * <p>What it does not do is prove who wrote the build. The manifest is published
 * beside the archives and fetched over the same HTTPS connection from the same
 * release, so it is exactly as trustworthy as the archive was before - no more,
 * and no less. Proving authorship needs a signature and a key that is not in
 * this repository, and pretending otherwise would be worse than not doing it.
 *
 * <h2>Directories, links and the executable bit</h2>
 *
 * <p>Only files and symbolic links are listed; folders are made as needed, so an
 * empty folder in an image would not survive. jpackage does not produce any, and
 * an image is not a place to keep one. Links are recorded as their target and
 * remade rather than followed - a macOS runtime is full of them. The executable
 * bit is recorded because a Linux image whose launcher has lost it is an update
 * that ends with nothing starting.
 */
public record ImageManifest(String version, String os, Archive archive,
                            Map<String, String> parts, List<Entry> files) {

    /** The format's own version, so an older launcher can refuse a newer shape. */
    public static final int FORMAT = 1;

    /** The suffix every manifest is published under. */
    public static final String SUFFIX = ".manifest.json";

    public ImageManifest {
        parts = Map.copyOf(parts);
        files = List.copyOf(files);
    }

    /** The whole build in one file, for when no part of it can be reused. */
    public record Archive(String asset, long size, String sha256) {
    }

    /**
     * One file in the image.
     *
     * @param path   relative to the image root, always with {@code /}
     * @param part   which published archive carries it
     * @param link   the target when this is a symbolic link, otherwise null
     * @param exec   whether the file is executable, which only Unix records
     */
    public record Entry(String path, String part, long size, String sha256,
                        String link, boolean exec) {

        public boolean isLink() {
            return link != null;
        }
    }

    /** The parts this manifest names, in the order they were written. */
    public List<String> partNames() {
        return List.copyOf(parts.keySet());
    }

    /** The published file that carries a part. */
    public Optional<String> assetOf(String part) {
        return Optional.ofNullable(parts.get(part));
    }

    /** Everything that comes out of one part. */
    public List<Entry> filesOf(String part) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : files) {
            if (entry.part().equals(part)) {
                found.add(entry);
            }
        }
        return List.copyOf(found);
    }

    /** How much a part weighs unpacked, for deciding whether it is worth it. */
    public long unpackedSizeOf(String part) {
        long total = 0;
        for (Entry entry : filesOf(part)) {
            total += entry.size();
        }
        return total;
    }

    // ------------------------------------------------------------------ json

    public Json toJson() {
        Json root = Json.object();
        root.put("manifest", FORMAT);
        root.put("version", version);
        root.put("os", os);
        if (archive != null) {
            Json full = Json.object();
            full.put("asset", archive.asset());
            full.put("size", archive.size());
            full.put("sha256", archive.sha256());
            root.put("archive", full);
        }
        Json partsJson = Json.object();
        parts.forEach(partsJson::put);
        root.put("parts", partsJson);

        Json list = Json.array();
        for (Entry entry : files) {
            Json item = Json.object();
            item.put("path", entry.path());
            item.put("part", entry.part());
            if (entry.isLink()) {
                item.put("link", entry.link());
            } else {
                item.put("size", entry.size());
                item.put("sha256", entry.sha256());
                if (entry.exec()) {
                    item.put("exec", true);
                }
            }
            list.add(item);
        }
        root.put("files", list);
        return root;
    }

    /**
     * Reads a manifest.
     *
     * <p>Strict, and deliberately so. Everything this describes ends up being
     * copied over a working installation, so a manifest that is missing a field,
     * names a part that was not published, or was written in a format this
     * launcher does not know is not something to interpret generously - it is a
     * reason to fall back to downloading the whole archive, which always works.
     */
    public static ImageManifest parse(Json json) throws IOException {
        if (json == null || !json.isObject()) {
            throw new IOException("the manifest is not an object");
        }
        long format = json.get("manifest").asLong(0);
        if (format != FORMAT) {
            throw new IOException("the manifest is in format " + format
                    + ", and this launcher reads " + FORMAT);
        }
        String version = json.get("version").asString("");
        String os = json.get("os").asString("");
        if (version.isBlank() || os.isBlank()) {
            throw new IOException("the manifest says neither what it is nor what for");
        }

        Archive archive = null;
        Json full = json.get("archive");
        if (full.isObject()) {
            archive = new Archive(full.get("asset").asString(""),
                    full.get("size").asLong(0),
                    full.get("sha256").asString(""));
        }

        Map<String, String> parts = new LinkedHashMap<>();
        for (Map.Entry<String, Json> part : json.get("parts").fields().entrySet()) {
            String asset = part.getValue().asString("");
            if (asset.isBlank()) {
                throw new IOException("the part " + part.getKey() + " names no file");
            }
            parts.put(part.getKey(), asset);
        }
        if (parts.isEmpty()) {
            throw new IOException("the manifest names no parts");
        }

        List<Entry> files = new ArrayList<>();
        for (Json item : json.get("files").elements()) {
            String path = item.get("path").asString("");
            String part = item.get("part").asString("");
            if (path.isBlank() || !parts.containsKey(part)) {
                throw new IOException("a file in the manifest has no path or no part: " + path);
            }
            if (!isSafe(path)) {
                // A path that climbs out of the image is how an archive is made
                // to write into somebody's home folder, and a manifest is read
                // before anything is unpacked.
                throw new IOException("a file in the manifest leaves the image: " + path);
            }
            String link = item.get("link").asString(null);
            if (link != null) {
                files.add(new Entry(path, part, 0, null, link, false));
                continue;
            }
            String sha = item.get("sha256").asString("");
            if (sha.length() != 64) {
                throw new IOException("the file " + path + " has no usable hash");
            }
            files.add(new Entry(path, part, item.get("size").asLong(-1),
                    sha.toLowerCase(Locale.ROOT), null, item.get("exec").asBool(false)));
        }
        if (files.isEmpty()) {
            throw new IOException("the manifest lists no files");
        }
        return new ImageManifest(version, os, archive, parts, files);
    }

    /** True when a manifest path stays inside the image it describes. */
    public static boolean isSafe(String path) {
        if (path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("\\") || path.contains(":")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ scan

    /** Decides which part a file belongs to, by its path inside the image. */
    public interface Parts {
        String of(String path);
    }

    /**
     * Reads an image off the disk and describes it.
     *
     * <p>The same code that the launcher reads a manifest with is the code that
     * writes one, which is the only way to be sure the two agree about what a
     * path, a link or an executable bit is.
     */
    public static ImageManifest scan(Path root, String os, String version,
                                     Map<String, String> partAssets, Parts parts)
            throws IOException {

        List<Entry> files = new ArrayList<>();
        // Without FOLLOW_LINKS, which is the point: a macOS runtime's links are
        // recorded as links rather than walked into and written down twice.
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> found = walk.sorted().toList();
            for (Path path : found) {
                if (path.equals(root)) {
                    continue;
                }
                boolean link = Files.isSymbolicLink(path);
                if (!link && Files.isDirectory(path)) {
                    continue;
                }
                String relative = slashes(root.relativize(path));
                String part = parts.of(relative);
                if (part == null) {
                    throw new IOException("no part was named for " + relative);
                }
                if (!partAssets.containsKey(part)) {
                    throw new IOException("the part " + part + " has no published file");
                }
                if (link) {
                    files.add(new Entry(relative, part, 0, null,
                            slashes(Files.readSymbolicLink(path)), false));
                    continue;
                }
                files.add(new Entry(relative, part,
                        Files.size(path), sha256(path), null, isExecutable(path)));
            }
        }
        return new ImageManifest(version, os, null, partAssets, files);
    }

    /** The same manifest with the full archive's own hash added to it. */
    public ImageManifest withArchive(String asset, Path file) throws IOException {
        return new ImageManifest(version, os,
                new Archive(asset, Files.size(file), sha256(file)), parts, files);
    }

    // ----------------------------------------------------------------- files

    /** A file's SHA-256, in lower-case hexadecimal. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Required of every Java runtime since there have been any.
            throw new IllegalStateException("this runtime has no SHA-256", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >> 4) & 0xF, 16));
            text.append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }

    /** Whether a file carries the executable bit, on systems that have one. */
    public static boolean isExecutable(Path file) {
        try {
            if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                return false;
            }
            return Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                    .contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /** Gives a file the executable bit, where that means anything. */
    public static void makeExecutable(Path file) {
        try {
            if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                return;
            }
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                    new java.util.HashSet<>(Files.getPosixFilePermissions(file));
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (IOException | RuntimeException ignored) {
            // A file that cannot be made executable is caught by the check that
            // follows the assembly, where it can be reported as what it is.
        }
    }

    /** A path as the manifest writes it: relative, with forward slashes. */
    public static String slashes(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/').replace('\\', '/');
    }
}
