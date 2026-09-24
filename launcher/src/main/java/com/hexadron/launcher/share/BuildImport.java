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
import com.hexadron.launcher.mods.InstalledMod;
import com.hexadron.launcher.mods.InstalledModpack;
import com.hexadron.launcher.mods.ModLibrary;
import com.hexadron.launcher.mods.ModpackInstaller;
import com.hexadron.launcher.mods.ModpackLibrary;
import com.hexadron.launcher.net.DownloadTask;
import com.hexadron.launcher.net.Downloader;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.util.Archives;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a build file and makes an instance out of it. See {@link BuildFormat}.
 *
 * <p>Everything in the file is untrusted input. A build is something people pass
 * around, and a path in its manifest or its archive is a string somebody else
 * wrote: every one goes through {@link ModpackInstaller#safeRelative}, the same
 * gate a modpack goes through, and is refused if it would land outside the new
 * instance. Downloads are HTTPS only and each is checked against the SHA-1 the
 * exporting launcher recorded, so a manifest can name where a file comes from
 * but not what it is.
 */
public final class BuildImport {

    /** A file to download. */
    record Remote(String path, ContentKind kind, String folder, Json lock,
                  List<String> urls, String sha1, long size) {
    }

    /**
     * A file nothing was known about when the build was made.
     *
     * @param included whether the exporting player put it in the build
     */
    public record Custom(String path, String kind, String title, long size, boolean included) {
    }

    /**
     * What came of an import.
     *
     * @param downloaded files fetched from their platforms
     * @param copied     files taken out of the build itself
     * @param failed     one line each: a file that should have arrived and did not
     * @param skipped    one line each: a file left out, and why
     */
    public record Result(int downloaded, int copied, List<String> failed, List<String> skipped) {

        public Result {
            failed = List.copyOf(failed);
            skipped = List.copyOf(skipped);
        }

        public boolean isClean() {
            return failed.isEmpty() && skipped.isEmpty();
        }
    }

    private final Path archive;
    private final Json profile;
    private final String generator;
    private final List<Remote> remote;
    private final List<Custom> custom;
    private final List<Json> modpacks;
    private final String iconEntry;
    private final int extras;
    private final List<String> refused;

    private BuildImport(Path archive, Json profile, String generator, List<Remote> remote,
                        List<Custom> custom, List<Json> modpacks, String iconEntry, int extras,
                        List<String> refused) {
        this.archive = archive;
        this.profile = profile;
        this.generator = generator;
        this.remote = List.copyOf(remote);
        this.custom = List.copyOf(custom);
        this.modpacks = List.copyOf(modpacks);
        this.iconEntry = iconEntry;
        this.extras = extras;
        this.refused = List.copyOf(refused);
    }

    // ---------------------------------------------------------------- reading

    /** True when the file has a build manifest in it. Never throws. */
    public static boolean looksLikeBuild(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile(), Archives.legacyEntryNames())) {
            return zip.getEntry(BuildFormat.MANIFEST) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Reads a build without installing anything.
     *
     * @throws IOException when the file is not a build, is from a newer launcher,
     *                     or names no Minecraft version. The message is shown to
     *                     the player.
     */
    public static BuildImport read(Path archive) throws IOException {
        Json manifest;
        List<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), Archives.legacyEntryNames())) {
            ZipEntry entry = zip.getEntry(BuildFormat.MANIFEST);
            if (entry == null) {
                throw new IOException(archive.getFileName()
                        + " is not a Hexadron build: it has no " + BuildFormat.MANIFEST);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                manifest = Json.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            zip.stream().filter(e -> !e.isDirectory()).forEach(e -> entries.add(e.getName()));
        } catch (java.util.zip.ZipException e) {
            throw new IOException(archive.getFileName() + " is not a readable archive: "
                    + BuildExport.message(e), e);
        } catch (RuntimeException e) {
            throw new IOException(archive.getFileName() + " has a manifest that cannot be read: "
                    + BuildExport.message(e), e);
        }

        if (!BuildFormat.FORMAT_ID.equals(manifest.get("format").asString(null))) {
            throw new IOException(archive.getFileName() + " is not a Hexadron build");
        }
        int version = manifest.get("formatVersion").asInt(-1);
        if (version < 1) {
            throw new IOException(archive.getFileName() + " has no format version");
        }
        if (version > BuildFormat.FORMAT_VERSION) {
            throw new IOException(archive.getFileName() + " was made by a newer Hexadron Launcher"
                    + " (format " + version + "); update the launcher to open it");
        }
        Json profile = manifest.get("profile");
        String minecraft = profile.get("minecraftVersion").asString("").trim();
        if (minecraft.isBlank()) {
            throw new IOException(archive.getFileName() + " names no Minecraft version");
        }

        List<String> refused = new ArrayList<>();
        List<Remote> remote = new ArrayList<>();
        for (Json file : manifest.get("files").elements()) {
            String path = file.get("path").asString("");
            ContentKind kind = kindOf(file.get("kind").asString(null));
            String folder = file.get("folder").asString("");
            List<String> urls = new ArrayList<>();
            for (Json url : file.get("urls").elements()) {
                String value = url.asString(null);
                if (BuildFormat.isFetchable(value)) {
                    urls.add(value);
                }
            }
            String sha1 = file.get("sha1").asString(null);
            // Each of these makes the entry one the importer cannot place or
            // cannot check, so it is refused by name rather than half-used.
            if (kind == null || !BuildFormat.isFolderOf(kind, folder)
                    || !path.equals(folder + "/" + BuildFormat.fileNameOf(path))
                    || urls.isEmpty() || sha1 == null || !sha1.matches("[0-9a-fA-F]{40}")) {
                refused.add(path.isBlank() ? "(a file with no path)" : path);
                continue;
            }
            Json lock = file.get("lock");
            remote.add(new Remote(path, kind, folder, lock.isObject() ? lock : null, urls,
                    sha1, file.get("size").asLong(-1)));
        }

        Set<String> names = new LinkedHashSet<>(entries);
        List<Custom> custom = new ArrayList<>();
        for (Json entry : manifest.get("custom").elements()) {
            String path = entry.get("path").asString("");
            if (path.isBlank()) {
                continue;
            }
            String inside = BuildFormat.FILES + path;
            boolean present = names.stream().anyMatch(name ->
                    name.equals(inside) || name.startsWith(inside + "/"));
            custom.add(new Custom(path, entry.get("kind").asString(""),
                    entry.get("title").asString(BuildFormat.fileNameOf(path)),
                    entry.get("size").asLong(-1),
                    present));
        }

        int extras = 0;
        for (String name : entries) {
            if (!name.startsWith(BuildFormat.FILES)) {
                continue;
            }
            String path = name.substring(BuildFormat.FILES.length());
            if (custom.stream().noneMatch(entry -> BuildFormat.within(path, entry.path()))) {
                extras++;
            }
        }

        List<Json> modpacks = new ArrayList<>(manifest.get("modpacks").elements());
        String icon = manifest.get("icon").asString(null);
        if (icon != null && (!icon.startsWith(BuildFormat.ICON) || !names.contains(icon))) {
            icon = null;
        }
        return new BuildImport(archive, profile, manifest.get("generator").asString(null),
                remote, custom, modpacks, icon, extras, refused);
    }

    private static ContentKind kindOf(String name) {
        if (name == null) {
            return null;
        }
        try {
            ContentKind kind = ContentKind.valueOf(name);
            return kind == ContentKind.MODPACK ? null : kind;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- what it says

    public Path archive() {
        return archive;
    }

    public String name() {
        String name = profile.get("name").asString("").trim();
        return name.isBlank() ? "Imported build" : name;
    }

    public String minecraftVersion() {
        return profile.get("minecraftVersion").asString("").trim();
    }

    public LoaderType loader() {
        return LoaderType.fromId(profile.get("loader").asString("vanilla"));
    }

    public String loaderVersion() {
        String value = profile.get("loaderVersion").asString(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Which launcher made it, or null. */
    public String generator() {
        return generator;
    }

    /** How many files will be downloaded. */
    public int remoteCount() {
        return remote.size();
    }

    /** How many configuration and world files the build carries. */
    public int extrasCount() {
        return extras;
    }

    public int modpackCount() {
        return modpacks.size();
    }

    /** Custom files the build carries - the ones the player is asked about. */
    public List<Custom> bundledCustom() {
        return custom.stream().filter(Custom::included).toList();
    }

    /** Custom files the exporting player chose to leave out. Named in the report. */
    public List<Custom> missingCustom() {
        return custom.stream().filter(entry -> !entry.included()).toList();
    }

    /** Manifest entries that could not be used, by path. */
    public List<String> refused() {
        return refused;
    }

    public List<String> jvmArguments() {
        return strings(profile.get("extraJvmArguments"));
    }

    public List<String> gameArguments() {
        return strings(profile.get("extraGameArguments"));
    }

    /**
     * True when the build sets launch arguments.
     *
     * <p>Asked about separately, because a JVM argument can do more than tune
     * memory: {@code -javaagent} loads code into the game and
     * {@code -XX:OnOutOfMemoryError} runs a command. From a build the player made
     * themselves that is fine; from a stranger's it is worth a look first.
     */
    public boolean hasArguments() {
        return !jvmArguments().isEmpty() || !gameArguments().isEmpty();
    }

    private static List<String> strings(Json array) {
        List<String> values = new ArrayList<>();
        for (Json value : array.elements()) {
            String text = value.asString(null);
            if (text != null && !text.isBlank()) {
                values.add(text);
            }
        }
        return Collections.unmodifiableList(values);
    }

    // ---------------------------------------------------------------- making it

    /**
     * Puts the build's settings on a profile.
     *
     * @param withArguments whether the JVM and game arguments are taken too
     */
    public void applySettings(Profile target, boolean withArguments) {
        int memory = profile.get("memoryMegabytes").asInt(-1);
        if (memory > 0) {
            target.memoryMegabytes(memory);
        }
        int javaMajor = profile.get("javaMajor").asInt(-1);
        if (javaMajor > 0) {
            target.javaMajor(javaMajor);
        }
        int width = profile.get("windowWidth").asInt(-1);
        int height = profile.get("windowHeight").asInt(-1);
        if (width > 0 && height > 0) {
            target.windowSize(width, height);
        }
        target.demo(profile.get("demo").asBool(false));
        target.icon(profile.get("icon").asString(Profile.ICON_AUTO));
        if (withArguments) {
            target.extraJvmArguments(jvmArguments());
            target.extraGameArguments(gameArguments());
        }
    }

    /** The profile picture, or null when the build has none. */
    public byte[] icon() throws IOException {
        if (iconEntry == null) {
            return null;
        }
        try (ZipFile zip = new ZipFile(archive.toFile(), Archives.legacyEntryNames())) {
            ZipEntry entry = zip.getEntry(iconEntry);
            if (entry == null || entry.getSize() > BuildFormat.ICON_LIMIT) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes((int) BuildFormat.ICON_LIMIT + 1);
                return bytes.length > BuildFormat.ICON_LIMIT ? null : bytes;
            }
        }
    }

    /**
     * Fills an instance folder from the build.
     *
     * @param gameDirectory the new instance's folder
     * @param includeCustom whether the player's own files are taken out of the build
     */
    public Result install(Path gameDirectory, boolean includeCustom, Downloader downloader,
                          Progress progress) throws IOException, InterruptedException {

        Files.createDirectories(gameDirectory);
        Path root = gameDirectory.toAbsolutePath().normalize();
        List<String> failed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        refused.forEach(path -> skipped.add(path + " (the build does not say where to get it safely)"));

        // ------------------------------------------------ downloads
        Map<String, DownloadTask> tasks = new LinkedHashMap<>();
        Map<String, Remote> byPath = new LinkedHashMap<>();
        for (Remote entry : remote) {
            String relative = ModpackInstaller.safeRelative(root, entry.path());
            if (relative == null) {
                skipped.add(entry.path() + " (the build puts this outside the instance folder)");
                continue;
            }
            Path destination = root.resolve(relative);
            tasks.put(relative, new DownloadTask(entry.urls(), destination, entry.sha1(),
                    entry.size(), BuildFormat.fileNameOf(relative), false));
            byPath.put(relative, entry);
        }
        for (DownloadTask task : tasks.values()) {
            Files.createDirectories(task.destination().getParent());
        }
        progress.stage("Downloading " + tasks.size() + " file(s)");
        List<Downloader.Failure> failures = tasks.isEmpty()
                ? List.of()
                : downloader.runCollecting(List.copyOf(tasks.values()), progress);
        Set<Path> didNotArrive = new LinkedHashSet<>();
        for (Downloader.Failure failure : failures) {
            didNotArrive.add(failure.task().destination());
            failed.add(failure.task().description() + " did not download: "
                    + BuildExport.message(failure.cause()));
        }

        // Each folder's record of what the launcher put there, so the rows for
        // these files say where they came from and can be updated and removed
        // the way they could in the instance they were exported from.
        Map<String, ModLibrary> libraries = new LinkedHashMap<>();
        int downloaded = 0;
        for (Map.Entry<String, DownloadTask> entry : tasks.entrySet()) {
            if (didNotArrive.contains(entry.getValue().destination())) {
                continue;
            }
            downloaded++;
            Remote source = byPath.get(entry.getKey());
            if (source.lock() == null) {
                continue;
            }
            try {
                InstalledMod record = InstalledMod.fromJson(source.lock(), false, null);
                String name = BuildFormat.fileNameOf(entry.getKey());
                // The record must describe the file it sits beside, or the list
                // would show a row for a file that is not there and leave the
                // real one unclaimed.
                if (!name.equals(record.file().fileName())
                        && !name.equals(record.file().fileName() + ".disabled")) {
                    continue;
                }
                libraries.computeIfAbsent(source.folder(),
                        folder -> ModLibrary.read(root.resolve(folder), source.kind().lockFile()))
                        .put(record);
            } catch (RuntimeException e) {
                // An entry this build cannot read leaves the file listed as one
                // the launcher did not install - the safe reading.
            }
        }
        for (Map.Entry<String, ModLibrary> library : libraries.entrySet()) {
            try {
                library.getValue().write();
            } catch (IOException e) {
                progress.log("The record for %s could not be written: %s", library.getKey(),
                        BuildExport.message(e));
            }
        }

        // ------------------------------------------------ carried files
        int copied = 0;
        progress.stage("Unpacking " + archive.getFileName());
        try (ZipFile zip = new ZipFile(archive.toFile(), Archives.legacyEntryNames())) {
            List<? extends ZipEntry> carried = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().startsWith(BuildFormat.FILES))
                    .toList();
            int done = 0;
            for (ZipEntry entry : carried) {
                progress.items(done++, carried.size());
                String path = entry.getName().substring(BuildFormat.FILES.length());
                boolean isCustom = custom.stream().anyMatch(c -> BuildFormat.within(path, c.path()));
                if (isCustom && !includeCustom) {
                    continue;
                }
                if (BuildFormat.isBookkeeping(BuildFormat.fileNameOf(path))) {
                    continue;
                }
                String relative = ModpackInstaller.safeRelative(root, path);
                if (relative == null) {
                    skipped.add(path + " (the build puts this outside the instance folder)");
                    continue;
                }
                Path destination = root.resolve(relative);
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                copied++;
            }
            progress.items(carried.size(), carried.size());
        }
        if (!includeCustom) {
            bundledCustom().forEach(entry -> skipped.add(entry.title() + " (" + entry.path()
                    + "): left out, as chosen"));
        }
        missingCustom().forEach(entry -> skipped.add(entry.title() + " (" + entry.path()
                + "): not in the build - the player who made it left it out"));

        // ------------------------------------------------ modpacks
        // Their records, so a pack's files stay grouped under it and removing
        // the pack still takes out exactly what it put there. Only the paths
        // that arrived: a record naming files that are not here would offer to
        // delete something else later.
        if (!modpacks.isEmpty()) {
            ModpackLibrary library = ModpackLibrary.read(root);
            for (Json json : modpacks) {
                try {
                    InstalledModpack pack = InstalledModpack.fromJson(json);
                    if (pack.id().isBlank()) {
                        continue;
                    }
                    List<String> present = new ArrayList<>();
                    for (String path : pack.paths()) {
                        String relative = ModpackInstaller.safeRelative(root, path);
                        if (relative != null && Files.exists(root.resolve(relative))) {
                            present.add(relative);
                        }
                    }
                    library.put(new InstalledModpack(pack.id(), pack.name(), pack.version(),
                            pack.author(), pack.source(), pack.projectId(), pack.iconUrl(),
                            pack.pageUrl(), pack.minecraftVersion(), pack.loader(),
                            pack.loaderVersion(), present, pack.installedAt()));
                } catch (RuntimeException e) {
                    progress.log("A modpack record in the build could not be read");
                }
            }
            library.write();
        }

        progress.log("%d file(s) downloaded, %d taken from the build", downloaded, copied);
        return new Result(downloaded, copied, failed, skipped);
    }
}
