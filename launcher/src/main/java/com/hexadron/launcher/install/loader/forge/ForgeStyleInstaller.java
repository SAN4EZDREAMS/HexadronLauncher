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

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.VersionInstaller;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.install.loader.LoaderVersion;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.launch.JavaLocator;
import com.hexadron.launcher.net.DownloadTask;
import com.hexadron.launcher.util.MavenCoordinate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Installs Forge and NeoForge.
 *
 * <p>Both are handled here because they are the same installer: NeoForge forked
 * Forge's and kept the format, so a single engine that reads
 * {@code install_profile.json} covers both, and any divergence shows up as a
 * different profile rather than as a different code path.
 *
 * <p><b>Why this is not "fetch JSON, write JSON" like Fabric.</b> Fabric and
 * Quilt publish a finished launcher profile, so installing them is one download.
 * Forge does not, and cannot: it ships its changes to the game as a binary diff
 * against the vanilla client jar. Nobody may redistribute a patched Minecraft
 * jar, so the patch has to be applied on the user's own machine. That is what
 * the processor chain in {@link ProcessorRunner} does, and it is the whole reason
 * Forge support is more than a URL.
 *
 * <p>Three eras, all still in use:
 * <ul>
 *   <li>up to 1.12.2 - the loader is a plain jar inside the installer, no
 *       patching at all;</li>
 *   <li>1.13 to 1.20 - a long chain: read the mappings, split the jar, remap it,
 *       then apply the diff;</li>
 *   <li>current - one step, because both projects moved the heavy work into
 *       their own build.</li>
 * </ul>
 * None of that is decided here. The profile in the installer says which steps to
 * run, and this class runs exactly those.
 */
public final class ForgeStyleInstaller {

    private static final String SIDE_CLIENT = "client";

    private final LoaderType type;

    public ForgeStyleInstaller(LoaderType type) {
        this.type = type;
    }

    /**
     * Downloads the installer for this build and carries out the install it
     * describes.
     *
     * @param installerUrls candidate sources for the installer jar, tried in order
     * @return the version id that was written, for {@link VersionInstaller#install}
     */
    public String install(String minecraftVersion, LoaderVersion loaderVersion,
                          List<String> installerUrls, VersionInstaller installer, Progress progress)
            throws IOException, InterruptedException {

        GameDirs dirs = installer.dirs();
        String label = type.displayName() + " " + loaderVersion.version();
        progress.stage("Installing " + label);

        // The processors patch the vanilla client jar, so it has to be on disk
        // first. Ordinarily the generic installer fetches it after the loader
        // manifest is written, which is too late here.
        Path vanillaJar = installer.ensureClientJar(minecraftVersion, progress);

        Path installerJar = dirs.cache().resolve("loaders")
                .resolve(type.id() + "-" + loaderVersion.version() + "-installer.jar");
        Files.createDirectories(installerJar.getParent());
        progress.log("Fetching the %s installer", type.displayName());
        installer.downloader().fetch(new DownloadTask(installerUrls, installerJar, null, -1,
                installerJar.getFileName().toString(), false));

        Path workDir = dirs.cache().resolve("loaders")
                .resolve(type.id() + "-" + loaderVersion.version() + "-work");
        deleteRecursively(workDir);
        Files.createDirectories(workDir);

        try (FileSystem jar = FileSystems.newFileSystem(installerJar)) {
            Path profileEntry = jar.getPath("install_profile.json");
            if (!Files.isRegularFile(profileEntry)) {
                throw new IOException("the " + label
                        + " installer contains no install_profile.json, so it cannot be read. "
                        + "The download may be corrupt: " + installerJar);
            }

            InstallProfile profile;
            try {
                profile = InstallProfile.parse(
                        Json.parse(Files.readString(profileEntry, StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                throw new IOException("the " + label + " installer profile cannot be read: "
                        + e.getMessage(), e);
            }
            progress.log("Installer profile: %s format, spec %d", profile.era(), profile.spec());

            return profile.era() == InstallProfile.Era.LEGACY
                    ? installLegacy(profile, jar, minecraftVersion, installer, progress)
                    : installModern(profile, jar, installerJar, vanillaJar, minecraftVersion,
                            workDir, installer, progress);
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ---------------------------------------------------------------- legacy

    /**
     * The pre-1.13 path: one jar to extract and one object to write out.
     *
     * <p>The universal jar exists nowhere but inside the installer for many of
     * these builds, so extracting it is not a shortcut - it is the only source.
     */
    private String installLegacy(InstallProfile profile, FileSystem jar, String minecraftVersion,
                                 VersionInstaller installer, Progress progress) throws IOException {

        GameDirs dirs = installer.dirs();
        MavenCoordinate mainJar = profile.mainJar();
        String entryName = profile.legacyJarEntry();

        if (mainJar != null) {
            Path target = dirs.library(mainJar.path());
            Path source = null;
            if (entryName != null && !entryName.isBlank()) {
                Path candidate = jar.getPath(entryName);
                if (Files.isRegularFile(candidate)) {
                    source = candidate;
                }
            }
            if (source == null) {
                // Some builds leave filePath empty and carry the jar in the
                // installer's own maven tree instead.
                Path candidate = jar.getPath("maven", mainJar.path());
                if (Files.isRegularFile(candidate)) {
                    source = candidate;
                }
            }
            if (source != null) {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                progress.log("Extracted %s from the installer", mainJar);
            } else if (!Files.isRegularFile(target)) {
                progress.log("note: the installer does not carry %s; it will be looked for "
                        + "on the loader's maven instead", mainJar);
            }
        }

        Json versionInfo = profile.legacyVersionInfo();
        String id = versionInfo.get("id").asString(profile.versionId());
        if (id == null || id.isBlank()) {
            throw new IOException("the installer profile names no version id");
        }
        // The oldest builds omit inheritsFrom, which the resolver needs in order
        // to find the vanilla manifest this one extends.
        if (!versionInfo.has("inheritsFrom")) {
            versionInfo.put("inheritsFrom", minecraftVersion);
        }
        installer.writeVersionJson(id, versionInfo);
        progress.log("Wrote version manifest %s", id);
        return id;
    }

    // ---------------------------------------------------------------- modern

    private String installModern(InstallProfile profile, FileSystem jar, Path installerJar,
                                 Path vanillaJar, String minecraftVersion, Path workDir,
                                 VersionInstaller installer, Progress progress)
            throws IOException, InterruptedException {

        GameDirs dirs = installer.dirs();

        // 1. Artifacts the installer carries itself. Several of them - the server
        //    shim among them - are published on no repository at all, so this is
        //    a source and not a cache.
        extractEmbeddedMaven(jar, dirs, progress);

        // 2. The programs the processors are, plus what they need to run.
        if (!profile.libraries().isEmpty()) {
            progress.stage("Downloading the " + type.displayName() + " install tools");
            installer.downloadLibraries(profile.libraries(), progress);
        }

        // 3. The manifest the game is launched from.
        Json versionJson = readVersionJson(profile, jar);
        String id = versionJson.get("id").asString(profile.versionId());
        if (id == null || id.isBlank()) {
            throw new IOException("the installer's version manifest names no id");
        }
        if (!versionJson.has("inheritsFrom")) {
            versionJson.put("inheritsFrom", minecraftVersion);
        }

        // 4. The patching itself.
        if (!profile.processors().isEmpty()) {
            Path minecraftJar = workDir.resolve("minecraft.jar");
            // A copy, not the original: the processors write to what they are
            // given, and the version folder's jar is shared with vanilla profiles.
            Files.copy(vanillaJar, minecraftJar, StandardCopyOption.REPLACE_EXISTING);

            Map<String, String> tokens = buildTokens(
                    profile, dirs, jar, installerJar, minecraftJar, workDir, minecraftVersion);

            int requiredJava = requiredJavaFor(installer, minecraftVersion);
            // exactWanted, because this is the one place where a newer JVM is
            // not simply "new enough": these are third-party programs built
            // against one Java generation, and ProcessorRunner's own notes
            // record what happens when they meet a later one.
            JavaLocator.JavaRuntime java = installer.javaRuntimes() != null
                    ? installer.javaRuntimes().resolve(null, requiredJava, true, progress)
                    : new JavaLocator(dirs).locate(null, requiredJava);
            progress.log("Patching with %s", java);

            new ProcessorRunner(java.executable(), dirs.libraries(), workDir, progress)
                    .runAll(profile.processors(), tokens, SIDE_CLIENT);
        }

        // Written last on purpose. A half-finished install that leaves no version
        // manifest behind cannot be launched by mistake; one that leaves the
        // manifest and no patched jar boots into a crash the user cannot read.
        installer.writeVersionJson(id, versionJson);
        progress.log("Wrote version manifest %s", id);
        return id;
    }

    private Json readVersionJson(InstallProfile profile, FileSystem jar) throws IOException {
        String entryName = profile.versionJsonEntry();
        String normalised = entryName.startsWith("/") ? entryName.substring(1) : entryName;
        Path entry = jar.getPath(normalised);
        if (!Files.isRegularFile(entry)) {
            throw new IOException("the installer declares its version manifest at " + entryName
                    + ", and there is no such entry in the jar");
        }
        return Json.parse(Files.readString(entry, StandardCharsets.UTF_8));
    }

    private Map<String, String> buildTokens(InstallProfile profile, GameDirs dirs, FileSystem jar,
                                            Path installerJar, Path minecraftJar, Path workDir,
                                            String minecraftVersion) throws IOException {

        Map<String, String> tokens = new LinkedHashMap<>();

        for (Map.Entry<String, InstallProfile.DataEntry> entry : profile.data().entrySet()) {
            String raw = entry.getValue().forSide(SIDE_CLIENT);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            tokens.put(entry.getKey(), Tokens.resolveDataValue(
                    raw,
                    coordinate -> dirs.library(coordinate.path()).toAbsolutePath().toString(),
                    name -> extractToWorkDir(jar, name, workDir)));
        }

        // The six the installer injects itself. Upper case, and none of them
        // optional: a processor that asks for one it did not get fails loudly
        // rather than writing a file named after the token.
        tokens.put("SIDE", SIDE_CLIENT);
        tokens.put("MINECRAFT_JAR", minecraftJar.toAbsolutePath().toString());
        tokens.put("MINECRAFT_VERSION", profile.minecraftVersion() == null
                ? minecraftVersion
                : profile.minecraftVersion());
        tokens.put("ROOT", dirs.root().toAbsolutePath().toString());
        tokens.put("INSTALLER", installerJar.toAbsolutePath().toString());
        tokens.put("LIBRARY_DIR", dirs.libraries().toAbsolutePath().toString());
        return tokens;
    }

    /**
     * Copies one entry out of the installer jar into the scratch folder.
     *
     * <p>This is how {@code /data/client.lzma} - the binary diff itself - reaches
     * the patcher. It is neither a coordinate nor a literal, just a path inside
     * the jar, and it is the third and last form a {@code data} value can take.
     */
    private String extractToWorkDir(FileSystem jar, String entryName, Path workDir) {
        String normalised = entryName.startsWith("/") ? entryName.substring(1) : entryName;
        Path source = jar.getPath(normalised);
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("the installer profile refers to " + entryName
                    + ", and there is no such entry in the installer jar");
        }
        Path target = workDir.resolve("installer").resolve(normalised.replace('/', '_'));
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("cannot extract " + entryName + " from the installer", e);
        }
        return target.toAbsolutePath().toString();
    }

    /**
     * Copies the installer's own {@code maven/} tree into the library folder.
     *
     * <p>Walks the whole archive and selects entries by path prefix rather than
     * asking for {@code maven/} as a directory. A zip is a flat list of entries
     * and directory entries are optional: an archive written without them has a
     * {@code maven/net/...} entry and no {@code maven/} entry, and
     * {@code Files.isDirectory} on that path can answer false. Reading it as
     * "this installer bundles nothing" is silent and wrong - the artifacts it
     * would have skipped exist on no public repository, so the failure surfaces
     * much later as a missing library at launch.
     *
     * @return how many files were newly written
     */
    private int extractEmbeddedMaven(FileSystem jar, GameDirs dirs, Progress progress)
            throws IOException {

        int found = 0;
        int copied = 0;
        for (Path root : jar.getRootDirectories()) {
            try (Stream<Path> entries = Files.walk(root)) {
                for (Path source : entries.filter(Files::isRegularFile).toList()) {
                    String relative = underMavenTree(source);
                    if (relative == null) {
                        continue;
                    }
                    found++;
                    Path target = dirs.library(relative);
                    if (Files.isRegularFile(target)) {
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
        }

        if (found == 0) {
            progress.log("The installer bundles no artifacts of its own");
        } else if (copied == 0) {
            progress.log("All %d artifact(s) bundled in the installer are already in place", found);
        } else {
            progress.log("Extracted %d of %d artifact(s) bundled in the installer", copied, found);
        }
        return copied;
    }

    /**
     * The library-relative path of an entry inside the installer's maven tree,
     * or null when the entry is not in it.
     */
    private static String underMavenTree(Path entry) {
        String path = entry.toString().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path.startsWith("maven/") ? path.substring("maven/".length()) : null;
    }

    /**
     * The Java version to run the processors with.
     *
     * <p>Taken from the vanilla manifest rather than from the launcher's own JVM.
     * {@link JavaLocator} then picks the lowest installed runtime that satisfies
     * it, which is what keeps the 1.13-1.16 chain off a modern JVM - its remapper
     * is known to behave differently there.
     */
    private int requiredJavaFor(VersionInstaller installer, String minecraftVersion) {
        try {
            return installer.resolver().resolve(minecraftVersion).requiredJavaMajor();
        } catch (IOException e) {
            return 8;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
