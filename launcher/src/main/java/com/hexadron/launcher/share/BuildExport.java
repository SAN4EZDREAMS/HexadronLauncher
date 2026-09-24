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

package com.hexadron.launcher.share;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.mods.ContentKind;
import com.hexadron.launcher.mods.DatapackScan;
import com.hexadron.launcher.mods.InstalledMod;
import com.hexadron.launcher.mods.InstalledModpack;
import com.hexadron.launcher.mods.ModEntry;
import com.hexadron.launcher.mods.ModFile;
import com.hexadron.launcher.mods.ModLibrary;
import com.hexadron.launcher.mods.ModScan;
import com.hexadron.launcher.mods.ModpackLibrary;
import com.hexadron.launcher.mods.ModrinthProvider;
import com.hexadron.launcher.mods.PackScan;
import com.hexadron.launcher.mods.WorldSaves;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.util.Hashes;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a profile out as a build file. See {@link BuildFormat} for the shape.
 *
 * <p>Two steps, because there is a question between them. {@link #plan} reads the
 * instance and sorts every file into "can be downloaded again" and "can only be
 * carried"; the interface then asks whether the second kind goes in; {@link
 * #write} writes the archive. Nothing is written by the first step, so a player
 * who cancels at the question has lost nothing.
 */
public final class BuildExport {

    /** How many hashes go to Modrinth in one request. */
    private static final int BATCH = 100;

    private BuildExport() {
    }

    /**
     * What goes in.
     *
     * @param mods          the mods folder
     * @param resourcePacks the resource packs folder
     * @param shaders       the shader packs folder
     * @param settings      the configuration of the mods and the game - see
     *                      {@link BuildFormat#SETTINGS}
     * @param worlds        the worlds, with the data packs in them. Off by default:
     *                      a world is often the largest thing in an instance and
     *                      the most personal
     */
    public record Options(boolean mods, boolean resourcePacks, boolean shaders,
                          boolean settings, boolean worlds) {

        public static Options defaults() {
            return new Options(true, true, true, true, false);
        }

        boolean includes(ContentKind kind) {
            return switch (kind) {
                case MOD -> mods;
                case RESOURCEPACK -> resourcePacks;
                case SHADER -> shaders;
                case DATAPACK -> worlds;
                case MODPACK -> false;
            };
        }
    }

    /** A file the build names by its address. */
    record Remote(String path, ContentKind kind, String folder, Json lock,
                  String url, String sha1, long size) {

        Json toJson() {
            Json json = Json.object()
                    .put("path", path)
                    .put("kind", kind.name())
                    .put("folder", folder)
                    .put("sha1", sha1)
                    .put("size", size)
                    .put("urls", Json.array().add(url));
            if (lock != null) {
                json.put("lock", lock);
            }
            return json;
        }
    }

    /**
     * A file or folder that nothing is known about, so it can only be carried.
     *
     * @param path      where it is in the instance, {@code /}-separated
     * @param kind      what it is
     * @param title     the name to show the player
     * @param size      its size in bytes; a folder's is the sum of its files
     * @param directory true for an unpacked pack
     */
    public record Custom(String path, ContentKind kind, String title, long size,
                         boolean directory) {

        Json toJson(boolean included) {
            return Json.object()
                    .put("path", path)
                    .put("kind", kind.name())
                    .put("title", title)
                    .put("size", size)
                    .put("directory", directory)
                    .put("included", included);
        }
    }

    /** A file that has to be asked about before it is sorted. */
    private record Candidate(String path, ContentKind kind, String folder, Json lock,
                             String sha1, long size, String title) {
    }

    /** What {@link #plan} found. Handed back to {@link #write} unchanged. */
    public static final class Plan {

        private final Profile profile;
        private final Path root;
        private final Path icon;
        private final Options options;
        private final List<Remote> remote;
        private final List<Custom> custom;
        private final List<String> extras;
        private final List<InstalledModpack> modpacks;
        private final List<String> notes;

        private Plan(Profile profile, Path root, Path icon, Options options, List<Remote> remote,
                     List<Custom> custom, List<String> extras,
                     List<InstalledModpack> modpacks, List<String> notes) {
            this.profile = profile;
            this.root = root;
            this.icon = icon;
            this.options = options;
            this.remote = List.copyOf(remote);
            this.custom = List.copyOf(custom);
            this.extras = List.copyOf(extras);
            this.modpacks = List.copyOf(modpacks);
            this.notes = List.copyOf(notes);
        }

        public Profile profile() {
            return profile;
        }

        /** How many files the build names by address. */
        public int remoteCount() {
            return remote.size();
        }

        /** Files and folders that can only be carried. */
        public List<Custom> custom() {
            return custom;
        }

        public boolean hasCustom() {
            return !custom.isEmpty();
        }

        public long customBytes() {
            return custom.stream().mapToLong(Custom::size).sum();
        }

        /** How many configuration and world files are carried. */
        public int extrasCount() {
            return extras.size();
        }

        public int modpackCount() {
            return modpacks.size();
        }

        /** Things worth a line in the log: a file that could not be read, a platform that did not answer. */
        public List<String> notes() {
            return notes;
        }

        /** The name offered for the file: the profile's, made safe for any file system. */
        public String suggestedFileName() {
            String base = profile.name() == null ? "" : profile.name().trim();
            base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").replaceAll("\\s+", " ").trim();
            if (base.isBlank() || base.chars().allMatch(c -> c == '.')) {
                base = "build";
            }
            return base + BuildFormat.EXTENSION;
        }
    }

    // ---------------------------------------------------------------- planning

    /**
     * Reads an instance and sorts its files.
     *
     * @param profile       the profile being exported
     * @param gameDirectory its instance folder
     * @param icon          its own picture, or null
     * @param modrinth      asked about files the launcher did not download, or
     *                      null to skip asking - they are then all custom
     */
    public static Plan plan(Profile profile, Path gameDirectory, Path icon, Options options,
                            ModrinthProvider modrinth, Progress progress)
            throws InterruptedException {

        Path root = gameDirectory.toAbsolutePath().normalize();
        List<Remote> remote = new ArrayList<>();
        List<Custom> custom = new ArrayList<>();
        List<String> extras = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Candidate> unknown = new LinkedHashMap<>();

        progress.stage("Reading " + profile.name());
        for (ContentKind kind : BuildFormat.FOLDER_KINDS) {
            if (!options.includes(kind)) {
                continue;
            }
            Path folder = root.resolve(kind.instanceFolder());
            List<ModEntry> entries = kind == ContentKind.MOD
                    ? ModScan.scan(folder)
                    : PackScan.of(kind).scan(folder);
            collect(kind, root, kind.instanceFolder(), entries, remote, custom, unknown,
                    notes, progress);
        }

        if (options.worlds()) {
            for (WorldSaves.World world : WorldSaves.of(root)) {
                String folder = WorldSaves.SAVES_DIR + "/" + world.folder() + "/"
                        + WorldSaves.DATAPACKS_DIR;
                collect(ContentKind.DATAPACK, root, folder, DatapackScan.scan(world.datapacks()),
                        remote, custom, unknown, notes, progress);
                // Everything else in the world, except the data packs just
                // sorted and the lock the game holds while the world is open.
                walk(root, world.path(), extras, notes, path ->
                        BuildFormat.within(path, folder)
                                || BuildFormat.WORLD_SKIPPED.contains(BuildFormat.fileNameOf(path)));
            }
        }

        if (options.settings()) {
            for (String name : BuildFormat.SETTINGS) {
                walk(root, root.resolve(name), extras, notes, path -> false);
            }
        }

        lookUp(unknown, modrinth, remote, notes, progress);
        for (Candidate candidate : unknown.values()) {
            custom.add(new Custom(candidate.path(), candidate.kind(), candidate.title(),
                    candidate.size(), false));
        }

        List<InstalledModpack> modpacks = ModpackLibrary.read(root).all();
        Path picture = icon != null && Files.isRegularFile(icon) && sizeOf(icon) <= BuildFormat.ICON_LIMIT
                ? icon : null;
        return new Plan(profile, root, picture, options, remote, custom, extras, modpacks, notes);
    }

    /** Sorts one folder's files into named, custom, and to-be-asked-about. */
    private static void collect(ContentKind kind, Path root, String folder, List<ModEntry> entries,
                                List<Remote> remote, List<Custom> custom,
                                Map<String, Candidate> unknown, List<String> notes,
                                Progress progress) throws InterruptedException {
        if (entries.isEmpty()) {
            return;
        }
        ModLibrary library = ModLibrary.read(root.resolve(folder), kind.lockFile());
        progress.stage("Reading " + folder);
        int done = 0;
        for (ModEntry entry : entries) {
            if (Thread.currentThread().isInterrupted() || progress.isCancelled()) {
                throw new InterruptedException("export cancelled");
            }
            progress.items(done++, entries.size());
            Path file = entry.path();
            String path = folder + "/" + file.getFileName().toString();
            if (Files.isDirectory(file)) {
                // An unpacked pack. No platform publishes a folder.
                custom.add(new Custom(path, kind, entry.title(), sizeOfTree(file), true));
                continue;
            }
            String sha1;
            long size;
            try {
                sha1 = Hashes.sha1(file).toLowerCase(Locale.ROOT);
                size = Files.size(file);
            } catch (IOException e) {
                notes.add(path + " could not be read: " + message(e));
                continue;
            }

            Json lock = null;
            Optional<InstalledMod> recorded = entry.isManaged()
                    ? library.get(entry.key()) : Optional.empty();
            if (recorded.isPresent()) {
                ModFile recordedFile = recorded.get().file();
                // The record is trusted only while the file still is the one it
                // describes. A jar swapped for another under the same name is
                // the other jar.
                boolean same = recordedFile.sha1() == null
                        || recordedFile.sha1().equalsIgnoreCase(sha1);
                if (same) {
                    lock = recorded.get().toJson();
                    if (BuildFormat.isFetchable(recordedFile.url())) {
                        remote.add(new Remote(path, kind, folder, lock, recordedFile.url(), sha1, size));
                        continue;
                    }
                }
            }
            unknown.put(path, new Candidate(path, kind, folder, lock, sha1, size, entry.title()));
        }
        progress.items(entries.size(), entries.size());
    }

    /**
     * Asks Modrinth which of the unknown files it publishes.
     *
     * <p>A failure here is not a failed export: the files it would have named are
     * carried or left out as custom ones instead, and the log says why.
     */
    private static void lookUp(Map<String, Candidate> unknown, ModrinthProvider modrinth,
                               List<Remote> remote, List<String> notes, Progress progress)
            throws InterruptedException {
        if (unknown.isEmpty() || modrinth == null) {
            return;
        }
        Set<String> hashes = new LinkedHashSet<>();
        unknown.values().forEach(candidate -> hashes.add(candidate.sha1()));
        List<String> all = new ArrayList<>(hashes);
        Map<String, ModFile> found = new LinkedHashMap<>();
        progress.stage("Asking Modrinth about " + all.size() + " file(s)");
        try {
            for (int from = 0; from < all.size(); from += BATCH) {
                found.putAll(modrinth.filesByHash(all.subList(from, Math.min(from + BATCH, all.size()))));
            }
        } catch (IOException | RuntimeException e) {
            notes.add("Modrinth could not be asked about the files the launcher did not download: "
                    + message(e));
            return;
        }
        var iterator = unknown.values().iterator();
        while (iterator.hasNext()) {
            Candidate candidate = iterator.next();
            ModFile match = found.get(candidate.sha1());
            if (match == null || !BuildFormat.isFetchable(match.url())) {
                continue;
            }
            remote.add(new Remote(candidate.path(), candidate.kind(), candidate.folder(),
                    candidate.lock(), match.url(), candidate.sha1(), candidate.size()));
            iterator.remove();
        }
    }

    /** Adds every file under {@code start} to {@code into}, relative to {@code root}. */
    private static void walk(Path root, Path start, List<String> into, List<String> notes,
                             java.util.function.Predicate<String> skip) {
        if (!Files.exists(start)) {
            return;
        }
        try (Stream<Path> files = Files.walk(start)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String relative = relative(root, file);
                if (relative != null && !skip.test(relative)
                        && !BuildFormat.isBookkeeping(file.getFileName().toString())) {
                    into.add(relative);
                }
            });
        } catch (IOException | RuntimeException e) {
            notes.add(relative(root, start) + " could not be read: " + message(e));
        }
    }

    // ---------------------------------------------------------------- writing

    /**
     * Writes the build.
     *
     * <p>To a temporary file beside the target first, moved into place at the
     * end: a failure half-way leaves no half a build under the name the player
     * chose, where it would be mistaken for a whole one.
     *
     * @param includeCustom whether the custom files go in
     * @param generator     the launcher's name and version, recorded in the manifest
     * @return how many files were written into the archive, not counting the manifest
     */
    public static int write(Plan plan, boolean includeCustom, Path target, String generator,
                            Progress progress) throws IOException, InterruptedException {

        Path absolute = target.toAbsolutePath();
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".part");
        int written = 0;
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary));
                 ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {

                zip.putNextEntry(new ZipEntry(BuildFormat.MANIFEST));
                zip.write(manifest(plan, includeCustom, generator).toPrettyString()
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();

                List<String> carried = new ArrayList<>(plan.extras);
                if (includeCustom) {
                    for (Custom entry : plan.custom) {
                        if (entry.directory()) {
                            walk(plan.root, plan.root.resolve(entry.path()), carried,
                                    new ArrayList<>(), path -> false);
                        } else {
                            carried.add(entry.path());
                        }
                    }
                }

                progress.stage("Writing " + absolute.getFileName());
                int done = 0;
                for (String path : carried) {
                    if (Thread.currentThread().isInterrupted() || progress.isCancelled()) {
                        throw new InterruptedException("export cancelled");
                    }
                    progress.items(done++, carried.size());
                    Path file = plan.root.resolve(path);
                    try {
                        copyInto(zip, BuildFormat.FILES + path, file);
                        written++;
                    } catch (IOException e) {
                        // A config the game has open on Windows. One file is not
                        // worth the build; the log names it.
                        progress.log("Not included, could not be read: %s (%s)", path, message(e));
                    }
                }
                progress.items(carried.size(), carried.size());

                if (plan.icon != null) {
                    copyInto(zip, BuildFormat.ICON + plan.icon.getFileName(), plan.icon);
                }
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return written;
    }

    /** The manifest, as {@link BuildImport} reads it. */
    static Json manifest(Plan plan, boolean includeCustom, String generator) {
        Json files = Json.array();
        plan.remote.forEach(entry -> files.add(entry.toJson()));
        Json custom = Json.array();
        plan.custom.forEach(entry -> custom.add(entry.toJson(includeCustom)));
        Json modpacks = Json.array();
        plan.modpacks.forEach(pack -> modpacks.add(pack.toJson()));

        Json manifest = Json.object()
                .put("format", BuildFormat.FORMAT_ID)
                .put("formatVersion", BuildFormat.FORMAT_VERSION)
                .put("generator", generator == null ? "Hexadron Launcher" : generator)
                .put("exportedAt", Instant.now().toString())
                .put("profile", profileJson(plan.profile))
                .put("contents", Json.object()
                        .put("mods", plan.options.mods())
                        .put("resourcePacks", plan.options.resourcePacks())
                        .put("shaders", plan.options.shaders())
                        .put("settings", plan.options.settings())
                        .put("worlds", plan.options.worlds()))
                .put("files", files)
                .put("custom", custom)
                .put("modpacks", modpacks);
        if (plan.icon != null) {
            manifest.put("icon", BuildFormat.ICON + plan.icon.getFileName());
        }
        return manifest;
    }

    /**
     * The profile's settings that mean the same thing on another machine.
     *
     * <p>Left out: the id (the importer makes its own), the installed version id
     * (the importer installs), the Java path and the wrapper command - see
     * {@link BuildFormat} for why.
     */
    static Json profileJson(Profile profile) {
        Json jvm = Json.array();
        profile.extraJvmArguments().forEach(jvm::add);
        Json game = Json.array();
        profile.extraGameArguments().forEach(game::add);
        Json json = Json.object()
                .put("name", profile.name())
                .put("minecraftVersion", profile.minecraftVersion())
                .put("loader", (profile.loader() == null ? LoaderType.VANILLA : profile.loader()).id())
                .put("memoryMegabytes", profile.memoryMegabytes())
                .put("demo", profile.demo())
                .put("icon", profile.icon())
                .put("extraJvmArguments", jvm)
                .put("extraGameArguments", game);
        if (profile.loaderVersion() != null) {
            json.put("loaderVersion", profile.loaderVersion());
        }
        if (profile.javaMajor() != null) {
            json.put("javaMajor", profile.javaMajor());
        }
        if (profile.hasCustomResolution()) {
            json.put("windowWidth", profile.windowWidth());
            json.put("windowHeight", profile.windowHeight());
        }
        return json;
    }

    // ---------------------------------------------------------------- pieces

    private static void copyInto(ZipOutputStream zip, String name, Path file) throws IOException {
        // Read before the entry is opened: a file that cannot be read must not
        // leave an empty entry behind under its name.
        try (var in = Files.newInputStream(file)) {
            zip.putNextEntry(new ZipEntry(name));
            in.transferTo(zip);
            zip.closeEntry();
        }
    }

    private static String relative(Path root, Path file) {
        Path normalised = file.toAbsolutePath().normalize();
        if (!normalised.startsWith(root) || normalised.equals(root)) {
            return null;
        }
        return root.relativize(normalised).toString().replace('\\', '/');
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long sizeOfTree(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
