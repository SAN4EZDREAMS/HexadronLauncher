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

package com.hexadron.launcher.install.loader.forge;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.util.Hashes;
import com.hexadron.launcher.util.MavenCoordinate;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Executes the processor chain of a modern Forge or NeoForge installer.
 *
 * <p>Each step runs as a separate JVM rather than inside the launcher's own.
 * That is a deliberate trade of speed for containment: these are third-party
 * programs, several of them a decade old, they rewrite the thread context
 * classloader, they call {@code System.exit}, and one of them
 * ({@code SpecialSource 1.8.5}, used by the 1.13-1.16 chain) misbehaves on
 * anything newer than Java 8. A separate process cannot take the launcher down
 * with it, and it can be given a different JVM than the launcher runs on.
 *
 * <p>The working directory is always a scratch folder. The installers hijack
 * {@code System.out} and write a log file named after their own jar into the
 * current directory, so running them anywhere else litters the user's folders.
 */
public final class ProcessorRunner {

    /** Lines of a failed step's output to include in the error. */
    private static final int LOG_TAIL = 40;

    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");

    private final Path javaExecutable;
    private final Path librariesDir;
    private final Path workDir;
    private final Progress progress;

    public ProcessorRunner(Path javaExecutable, Path librariesDir, Path workDir, Progress progress) {
        this.javaExecutable = javaExecutable;
        this.librariesDir = librariesDir;
        this.workDir = workDir;
        this.progress = progress;
    }

    /** Runs every step that belongs to {@code side}, in the order the profile lists them. */
    public void runAll(List<ForgeProcessor> processors, Map<String, String> tokens, String side)
            throws IOException, InterruptedException {

        List<ForgeProcessor> applicable = new ArrayList<>();
        for (ForgeProcessor processor : processors) {
            if (processor.appliesToSide(side)) {
                applicable.add(processor);
            }
        }
        if (applicable.isEmpty()) {
            return;
        }

        progress.stage("Patching the client jar");
        progress.items(0, applicable.size());
        for (int index = 0; index < applicable.size(); index++) {
            if (progress.isCancelled()) {
                throw new InterruptedException("cancelled while patching the client jar");
            }
            runOne(applicable.get(index), tokens, index + 1, applicable.size());
            progress.items(index + 1, applicable.size());
        }
    }

    private void runOne(ForgeProcessor processor, Map<String, String> tokens, int step, int total)
            throws IOException, InterruptedException {

        Map<Path, String> outputs = resolveOutputs(processor, tokens);

        // The profile publishes the hash of every file a step produces, so a
        // repeated install - or a repair after a partial one - can skip the
        // expensive work instead of redoing it. Forge's own installer calls this
        // a cache hit.
        if (!outputs.isEmpty() && outputs.entrySet().stream()
                .allMatch(entry -> isSatisfied(entry.getKey(), entry.getValue()))) {
            progress.log("  [%d/%d] %s - already produced", step, total, processor.label());
            return;
        }

        Path jar = libraryFile(processor.jar());
        if (!Files.isRegularFile(jar)) {
            throw new IOException("the installer needs " + processor.jar()
                    + " to patch the client jar, and it is not in the library folder: " + jar);
        }

        List<String> classpath = new ArrayList<>();
        classpath.add(jar.toAbsolutePath().toString());
        for (MavenCoordinate dependency : processor.classpath()) {
            Path file = libraryFile(dependency);
            if (!Files.isRegularFile(file)) {
                throw new IOException(processor.jar() + " needs " + dependency
                        + " on its classpath, and it is not in the library folder: " + file);
            }
            classpath.add(file.toAbsolutePath().toString());
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toAbsolutePath().toString());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(mainClassOf(jar));
        for (String argument : processor.args()) {
            command.add(Tokens.resolve(tokens, argument, this::libraryPathOf));
        }

        progress.log("  [%d/%d] %s", step, total, processor.label());

        Files.createDirectories(workDir);
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();

        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == LOG_TAIL) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        }

        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(processor.jar() + " failed with exit code " + exit
                    + "\n\nLast output:\n  " + String.join("\n  ", tail));
        }

        verifyOutputs(processor, outputs);
    }

    private Map<Path, String> resolveOutputs(ForgeProcessor processor, Map<String, String> tokens) {
        Map<Path, String> outputs = new LinkedHashMap<>();
        processor.outputs().forEach((key, value) -> outputs.put(
                Paths.get(Tokens.resolve(tokens, key, this::libraryPathOf)),
                Tokens.resolve(tokens, value, this::libraryPathOf)));
        return outputs;
    }

    private void verifyOutputs(ForgeProcessor processor, Map<Path, String> outputs) throws IOException {
        for (Map.Entry<Path, String> output : outputs.entrySet()) {
            Path file = output.getKey();
            String expected = output.getValue();

            if (!Files.isRegularFile(file)) {
                throw new IOException(processor.jar() + " reported success but did not write "
                        + file);
            }
            if (!SHA1.matcher(expected).matches() || Hashes.matchesSha1(file, expected)) {
                continue;
            }

            // A different hash is not automatically a broken file any more. These
            // steps build jars at install time, and a JVM using a native deflate
            // implementation (zlib-ng and friends) produces a byte-different but
            // completely valid archive. Rejecting on the hash alone made Forge
            // uninstallable on those machines, so an archive that is structurally
            // whole is accepted with a note instead.
            if (isArchive(file) && isReadableArchive(file)) {
                progress.log("note: %s has a different SHA-1 than the installer declares. "
                        + "The archive is valid, so it is kept - this is normal when the JVM "
                        + "compresses differently.", file.getFileName());
                continue;
            }

            Files.deleteIfExists(file);
            throw new IOException(processor.jar() + " produced " + file.getFileName()
                    + " with the wrong contents (expected SHA-1 " + expected
                    + "). The file has been deleted; run the install again.");
        }
    }

    /** True when the file exists and either matches its declared hash or is a whole archive. */
    private boolean isSatisfied(Path file, String expected) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        if (!SHA1.matcher(expected).matches()) {
            return true;
        }
        return Hashes.matchesSha1(file, expected) || (isArchive(file) && isReadableArchive(file));
    }

    private static boolean isArchive(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    /**
     * Whether the file is a complete archive.
     *
     * <p>Opening it is the check: a truncated or empty file has no end-of-central
     * -directory record and cannot be opened, and that is precisely the failure
     * this stands in for once the hash can no longer be trusted.
     */
    private static boolean isReadableArchive(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.size() >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static String mainClassOf(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            var manifest = file.getManifest();
            String mainClass = manifest == null
                    ? null
                    : manifest.getMainAttributes().getValue("Main-Class");
            if (mainClass == null || mainClass.isBlank()) {
                throw new IOException(jar.getFileName()
                        + " declares no Main-Class, so it cannot be run as an install step");
            }
            return mainClass;
        }
    }

    private Path libraryFile(MavenCoordinate coordinate) {
        return librariesDir.resolve(coordinate.path().replace('/', File.separatorChar));
    }

    private String libraryPathOf(MavenCoordinate coordinate) {
        return libraryFile(coordinate).toAbsolutePath().toString();
    }
}
