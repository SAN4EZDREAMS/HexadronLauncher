package com.hexadron.launcher.install;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.meta.Library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
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
