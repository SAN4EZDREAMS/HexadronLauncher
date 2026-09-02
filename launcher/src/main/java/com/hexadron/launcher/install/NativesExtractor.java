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

package com.hexadron.launcher.install;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.meta.Library;

import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks legacy native containers into a per-version natives directory.
 *
 * <p>Only versions up to roughly 1.18 need this: they declare a {@code natives}
 * block and expect the launcher to have unzipped {@code .dll}/{@code .so}/
 * {@code .dylib} files into the directory named by {@code ${natives_directory}}.
 * From 1.19 onwards LWJGL 3.3 ships natives as ordinary classpath jars and
 * extracts them itself, so this class simply finds nothing to do.
 */
public final class NativesExtractor {

    private NativesExtractor() {
    }

    /**
     * Extracts every applicable native container into {@code targetDir}.
     *
     * @param libraries  the resolved library list
     * @param libraryOf  resolves a library to its on-disk native jar
     * @param targetDir  {@code natives/<versionId>}
     */
    public static void extractAll(List<Library> libraries,
                                  java.util.function.Function<Library, Path> libraryOf,
                                  Path targetDir,
                                  Progress progress) throws IOException {
        List<Library> containers = libraries.stream()
                .filter(Library::appliesToThisHost)
                .filter(Library::isLegacyNativeContainer)
                .filter(lib -> lib.nativeArtifact() != null)
                .toList();

        if (containers.isEmpty()) {
            // Still make the directory: -Djava.library.path must point somewhere real.
            Files.createDirectories(targetDir);
            return;
        }

        // What this directory would contain if it were extracted now. Compared
        // against what it was extracted from last time, because this runs before
        // every launch and the answer is almost always "the same jars".
        // Two halves, and both have to still hold. The jars say "nothing has
        // been re-downloaded"; the contents say "and nothing has been added to
        // or altered inside this directory since". The second half is the one
        // that matters: this directory is on -Djava.library.path, so a file
        // planted in it is native code the game loads. Wiping and re-extracting
        // every launch used to destroy anything planted here as a side effect,
        // and skipping that without checking would have quietly given the
        // side effect away.
        String wantedSources = signature(containers, libraryOf);
        Path stamp = targetDir.resolve(STAMP);
        String previous = readStamp(stamp);
        if (previous != null) {
            int split = previous.indexOf(CONTENTS_MARK);
            if (split >= 0
                    && wantedSources.equals(previous.substring(0, split))
                    && contents(targetDir).equals(
                            previous.substring(split + CONTENTS_MARK.length()))) {
                return;
            }
        }

        progress.stage("Extracting native libraries");
        // Start from a clean directory so a version switch cannot leave a stale
        // native of the wrong architecture behind.
        deleteRecursively(targetDir);
        Files.createDirectories(targetDir);

        int done = 0;
        for (Library library : containers) {
            Path jar = libraryOf.apply(library);
            if (jar == null || !Files.isRegularFile(jar)) {
                progress.log("native container missing, skipping: " + library.name());
                continue;
            }
            extract(jar, targetDir, library.extractExcludes());
            progress.items(++done, containers.size());
        }
        writeStamp(stamp, wantedSources + CONTENTS_MARK + contents(targetDir));
    }

    /** Name of the file recording what this directory was extracted from. */
    private static final String STAMP = ".hexadron-natives";

    /** Separates the two halves of the stamp: the sources, then the result. */
    private static final String CONTENTS_MARK = "\n== contents ==\n";

    /**
     * Forgets that this directory was ever extracted, so the next pass unpacks
     * it again from scratch.
     *
     * <p>For repair. The stamp says "these jars, this size, this timestamp",
     * which is a statement about the jars and not about the files that came out
     * of them - so a user who has deleted or damaged something inside the
     * natives directory needs a way to say so.
     */
    public static void forget(Path targetDir) {
        try {
            Files.deleteIfExists(targetDir.resolve(STAMP));
        } catch (IOException ignored) {
            // Then it re-extracts next time the jars change, as before.
        }
    }

    /**
     * A description of the containers a natives directory came from.
     *
     * <p>One line per jar: its coordinate, the length of the file on disk and
     * when it was last written. Enough that swapping a native jar for another
     * build, or a library re-download replacing one, is noticed - and cheap,
     * because it is a {@code stat} per jar rather than a read.
     *
     * <p>Not the published hash, deliberately: these are the same jars the
     * downloader has just verified by hash on the way here, so hashing them a
     * second time to decide whether to unzip them would put back the cost this
     * is removing.
     */
    private static String signature(List<Library> containers,
                                    java.util.function.Function<Library, Path> libraryOf) {
        StringBuilder sb = new StringBuilder(containers.size() * 64);
        sb.append(Platform.osName()).append('/').append(Platform.arch()).append('\n');
        for (Library library : containers) {
            Path jar = libraryOf.apply(library);
            sb.append(library.name()).append('\t');
            try {
                var attributes = Files.readAttributes(jar, BasicFileAttributes.class);
                sb.append(attributes.size()).append('\t')
                        .append(attributes.lastModifiedTime().toMillis());
            } catch (IOException | NullPointerException e) {
                // Missing or unreadable: whatever is in the directory did not come
                // from this, so make sure the comparison fails.
                sb.append("missing");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * A description of what is in the natives directory right now.
     *
     * <p>Every file, sorted, with its length and when it was written - and the
     * stamp itself left out, since it is written after the rest. Anything added,
     * removed, replaced or edited changes this string, and the directory is then
     * wiped and unpacked again, which is exactly what used to happen
     * unconditionally.
     *
     * <p>A few dozen {@code stat} calls. That is what makes it affordable to do
     * before every launch, where reading each file back would not be.
     */
    private static String contents(Path targetDir) {
        List<String> lines = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(targetDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.equals(STAMP)) {
                    continue;
                }
                try {
                    BasicFileAttributes attributes =
                            Files.readAttributes(entry, BasicFileAttributes.class);
                    lines.add(name + '\t'
                            + (attributes.isDirectory() ? "dir" : attributes.size()) + '\t'
                            + attributes.lastModifiedTime().toMillis());
                } catch (IOException e) {
                    lines.add(name + "\tunreadable");
                }
            }
        } catch (IOException e) {
            // No directory, or it cannot be listed: whatever the stamp says, it
            // does not describe this. Returning something no signature equals
            // forces the extraction.
            return "unreadable";
        }
        java.util.Collections.sort(lines);
        return String.join("\n", lines);
    }

    private static String readStamp(Path stamp) {
        try {
            return Files.readString(stamp, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeStamp(Path stamp, String signature) {
        try {
            Files.writeString(stamp, signature, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Without it the next launch extracts again, which is what used to
            // happen every time. Not worth failing an install over.
        }
    }

    /** Extracts one native jar, honouring the library's exclude list. */
    public static void extract(Path jar, Path targetDir, List<String> excludes) throws IOException {
        Path normalisedTarget = targetDir.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (isExcluded(name, excludes)) {
                    continue;
                }

                // Flatten: the game looks for natives directly in the directory,
                // not in the jar's internal folder structure.
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (fileName.isEmpty()) {
                    continue;
                }
                Path destination = normalisedTarget.resolve(fileName).normalize();
                if (!destination.startsWith(normalisedTarget)) {
                    throw new IOException("refusing to extract outside the natives directory: " + name);
                }

                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                makeExecutableIfNeeded(destination);
            }
        }
    }

    private static boolean isExcluded(String entryName, List<String> excludes) {
        // META-INF is never a native and signing files break some loaders.
        if (entryName.startsWith("META-INF/") || entryName.equals("META-INF")) {
            return true;
        }
        for (String exclude : excludes) {
            if (entryName.startsWith(exclude)) {
                return true;
            }
        }
        String lower = entryName.toLowerCase(Locale.ROOT);
        // Sources and javadoc jars occasionally get published as native classifiers.
        return lower.endsWith(".java") || lower.endsWith(".git") || lower.endsWith(".sha1");
    }

    private static void makeExecutableIfNeeded(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".so") && !name.endsWith(".dylib") && !name.endsWith(".jnilib")) {
            return;
        }
        try {
            var perms = Files.getPosixFilePermissions(path);
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: nothing to do.
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
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
