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

import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where this launcher is installed, and what its parts are called there.
 *
 * <h2>Why an update needs to know this at all</h2>
 *
 * <p>What is published is not a jar - it is an application image built by
 * {@code jpackage}: the launcher, JavaFX and a Java runtime in one folder that
 * runs on a machine with no Java. Updating it means replacing that folder. So
 * the update has to find it, and the three operating systems put its parts in
 * three different places:
 *
 * <pre>
 *   Windows  root/HexadronLauncher.exe   root/app/*.jar   root/runtime/bin/java.exe
 *   Linux    root/bin/HexadronLauncher   root/lib/app/*.jar   root/lib/runtime/bin/java
 *   macOS    root.app/Contents/MacOS/... root.app/Contents/app/*.jar
 *                                        root.app/Contents/runtime/Contents/Home/bin/java
 * </pre>
 *
 * <h2>And when it is not installed at all</h2>
 *
 * <p>A launcher started from a class directory, from {@code java -jar}, or out
 * of an IDE has no image to replace. {@link #detect()} then finds nothing, and
 * the interface offers the release page instead of an update. That is the
 * honest answer: the developer running it that way updates it with git.
 */
public record UpdateInstall(Path root, Platform.OsFamily os) {

    /** The application's name, and therefore its folder's and its executable's. */
    public static final String APP_NAME = "HexadronLauncher";

    /**
     * Where this launcher is running from, when it is a packaged image.
     *
     * @return empty for a development run, which is not an error
     */
    public static Optional<UpdateInstall> detect() {
        return currentJar().flatMap(jar -> detect(jar, Platform.os()));
    }

    /**
     * The same, from a given jar, so that the layout rules can be checked
     * against folders built for the purpose rather than only against whatever
     * machine the checks happen to run on.
     */
    public static Optional<UpdateInstall> detect(Path jar, Platform.OsFamily os) {
        if (jar == null) {
            return Optional.empty();
        }
        Path directory = jar.getParent();
        // Windows puts the jar two levels below the root, Linux and macOS three.
        // Both are tried on every system rather than only the one that matches:
        // it costs two calls to Files.isDirectory and it means an image that was
        // laid out by a future jpackage is still found.
        for (int level = 0; level < 4 && directory != null; level++) {
            if (looksLikeImage(directory, os)) {
                return Optional.of(new UpdateInstall(directory, os));
            }
            directory = directory.getParent();
        }
        return Optional.empty();
    }

    /** True when this folder is an application image for that system. */
    public static boolean looksLikeImage(Path root, Platform.OsFamily os) {
        if (root == null || !Files.isDirectory(root)) {
            return false;
        }
        UpdateInstall candidate = new UpdateInstall(root, os);
        return Files.isDirectory(candidate.appDirectory())
                && Files.isRegularFile(candidate.javaExecutable());
    }

    /**
     * The image inside an unpacked archive.
     *
     * <p>The archives are not all the same shape: the Windows one is the folder
     * itself plus a note beside it, and the other two are a tar of the folder.
     * So the image is looked for rather than assumed - here, and one level down.
     */
    public static Optional<Path> imageIn(Path unpacked, Platform.OsFamily os) {
        if (looksLikeImage(unpacked, os)) {
            return Optional.of(unpacked);
        }
        try (Stream<Path> children = Files.list(unpacked)) {
            List<Path> directories = children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path child : directories) {
                if (looksLikeImage(child, os)) {
                    return Optional.of(child);
                }
            }
            // One more level: a tar made with "-C build/jpackage ." unpacks as
            // ./HexadronLauncher/..., and some tools keep the leading dot as a
            // directory of its own.
            for (Path child : directories) {
                Optional<Path> deeper = imageIn(child, os);
                if (deeper.isPresent()) {
                    return deeper;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // An unreadable staging folder is a failed update, reported by the
            // caller as one.
        }
        return Optional.empty();
    }

    /** Where the launcher's own jars live inside the image. */
    public Path appDirectory() {
        return switch (os) {
            case WINDOWS -> root.resolve("app");
            case LINUX -> root.resolve("lib").resolve("app");
            case OSX -> root.resolve("Contents").resolve("app");
        };
    }

    /** The bundled runtime's {@code java}, which is what runs the updater. */
    public Path javaExecutable() {
        String name = os == Platform.OsFamily.WINDOWS ? "java.exe" : "java";
        return switch (os) {
            case WINDOWS -> root.resolve("runtime").resolve("bin").resolve(name);
            case LINUX -> root.resolve("lib").resolve("runtime").resolve("bin").resolve(name);
            case OSX -> root.resolve("Contents").resolve("runtime").resolve("Contents")
                    .resolve("Home").resolve("bin").resolve(name);
        };
    }

    /** The file a user double-clicks. */
    public Path launcherExecutable() {
        Path expected = switch (os) {
            case WINDOWS -> root.resolve(APP_NAME + ".exe");
            case LINUX -> root.resolve("bin").resolve(APP_NAME);
            case OSX -> root.resolve("Contents").resolve("MacOS").resolve(APP_NAME);
        };
        if (Files.isRegularFile(expected)) {
            return expected;
        }
        // Renamed by whoever unpacked it, which people do. The folder holds one
        // executable; find it rather than refuse to start the update.
        Path directory = expected.getParent();
        if (directory != null) {
            try (Stream<Path> files = Files.list(directory)) {
                Optional<Path> found = files
                        .filter(Files::isRegularFile)
                        .filter(file -> os != Platform.OsFamily.WINDOWS
                                || file.getFileName().toString().toLowerCase(
                                        java.util.Locale.ROOT).endsWith(".exe"))
                        .filter(file -> os == Platform.OsFamily.WINDOWS
                                || Files.isExecutable(file))
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (IOException | RuntimeException ignored) {
                // Fall through to the expected name and let the start fail
                // where it can be reported.
            }
        }
        return expected;
    }

    /**
     * How to start this launcher again after it has been replaced.
     *
     * <p>macOS goes through {@code open} rather than running the executable
     * inside the bundle directly. A bundle started by its inner binary is not
     * registered with the window server as the application it belongs to: it
     * gets no dock icon of its own and cannot be brought to the front.
     */
    public List<String> relaunchCommand() {
        if (os == Platform.OsFamily.OSX) {
            return List.of("/usr/bin/open", "-n", root.toString());
        }
        return List.of(launcherExecutable().toString());
    }

    /**
     * Whether this image can be replaced in place.
     *
     * <p>The folder itself and the folder above it: replacing an image means
     * writing beside it and then moving it, so both have to allow it. An install
     * under Program Files or /opt will not, and the honest answer there is to
     * send the user to the download page rather than to fail halfway through.
     */
    public boolean isWritable() {
        Path parent = root.getParent();
        return Files.isWritable(root) && parent != null && Files.isWritable(parent);
    }

    /** Every jar in the image, for the class path the updater is started with. */
    public List<String> appJars() {
        List<String> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(appDirectory())) {
            files.filter(file -> file.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(file -> jars.add(file.toString()));
        } catch (IOException | RuntimeException ignored) {
            // An image with no readable app folder is not one this can update.
        }
        return List.copyOf(jars);
    }

    /** The jar this class was loaded from, when it was loaded from one. */
    static Optional<Path> currentJar() {
        try {
            java.security.CodeSource source =
                    UpdateInstall.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return Optional.empty();
            }
            URL location = source.getLocation();
            if (location == null || !"file".equals(location.getProtocol())) {
                return Optional.empty();
            }
            Path path = Path.of(location.toURI());
            // A class directory is a development run, and there is nothing to
            // update in one.
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
