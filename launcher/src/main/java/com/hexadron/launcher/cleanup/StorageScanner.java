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

package com.hexadron.launcher.cleanup;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.launch.JavaProvisioner;
import com.hexadron.launcher.meta.AssetIndex;
import com.hexadron.launcher.meta.VersionJson;
import com.hexadron.launcher.meta.VersionResolver;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileStore;
import com.hexadron.launcher.util.MavenCoordinate;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the data folder and works out what in it is still in use.
 *
 * <h2>What "in use" means</h2>
 *
 * <p>Worked out from the profiles outwards, never guessed from names or dates:
 *
 * <ul>
 *   <li>a <b>version</b> is in use when a profile runs it, or when a version a
 *       profile runs inherits from it;</li>
 *   <li>a <b>library</b> is in use when one of those versions names it - as its
 *       jar, as one of its natives, or on its command line;</li>
 *   <li>an <b>asset</b> is in use when the asset index of one of those versions
 *       lists its hash;</li>
 *   <li>a <b>Java runtime</b> is in use when a profile asks for its major
 *       version.</li>
 * </ul>
 *
 * <p>Whenever the answer cannot be worked out, nothing is offered. A profile
 * whose version file will not read might name any library at all, so while one
 * exists no library is called unused; an installed version whose asset index is
 * missing might need any asset, so then no asset is. The safe mode is only safe
 * if it is allowed to say "I don't know".
 *
 * <p>Forge and NeoForge install more than their version file names: the
 * installer writes patched game jars and mappings into the library store and
 * the game finds them there at run time. While either is installed, their
 * library groups are never called unused.
 *
 * <p>Nothing inside an instance folder is ever a candidate. Those are the
 * player's worlds, mods and settings, and the advanced mode is where they can
 * be chosen by hand.
 */
public final class StorageScanner {

    /**
     * What the scan reads.
     *
     * @param javaInUse   Java majors the profiles ask for, or null when that cannot
     *                    be worked out - then no runtime is offered
     * @param currentLog  the log the launcher is writing, which is never offered
     */
    public record Inputs(GameDirs dirs, List<Profile> profiles, Function<Profile, Path> gameDirectory,
                         VersionResolver resolver, JavaProvisioner java, Set<Integer> javaInUse,
                         Path currentLog) {
    }

    /** A partial download younger than this may still be arriving. */
    static final long PARTIAL_AGE_MILLIS = 15L * 60 * 1000;

    /** A folder with more entries than this is shown as one row, not listed. */
    private static final int EXPAND_LIMIT = 400;

    /** Library groups a Forge-style installer writes into without naming them. */
    private static final List<String> FORGE_GROUPS = List.of(
            "net/minecraftforge/", "net/neoforged/", "net/minecraft/", "de/oceanlabs/", "cpw/mods/");

    /** Folders of the data root this scan knows by name. */
    private static final Set<String> KNOWN = Set.of("instances", "versions", "libraries", "assets",
            "java", "natives", "cache", "logs");

    private static final Pattern LIBRARY_ARGUMENT =
            Pattern.compile("\\$\\{library_directory}[/\\\\]([^;:${}\\s\"]+)");

    private final Inputs in;
    private final GameDirs dirs;
    private final Path root;

    private StorageScanner(Inputs in) {
        this.in = in;
        this.dirs = in.dirs();
        this.root = dirs.root().toAbsolutePath().normalize();
    }

    /** Scans the data folder. Reads only; nothing is written or deleted. */
    public static StorageReport scan(Inputs in, Progress progress) throws InterruptedException {
        return new StorageScanner(in).run(progress);
    }

    /**
     * The paths the cleaner refuses to touch, and refuses to delete a folder
     * holding: settings, accounts, credentials, the launch wrapper and the log
     * being written.
     */
    public static Set<Path> protectedPaths(GameDirs dirs, Path currentLog) {
        Path root = dirs.root().toAbsolutePath().normalize();
        Set<Path> paths = new LinkedHashSet<>();
        for (String name : List.of("launcher.json", "accounts.json", "profiles.json",
                "secrets", "wrapper")) {
            paths.add(root.resolve(name));
        }
        paths.add(dirs.skinsFile().toAbsolutePath().normalize());
        if (currentLog != null) {
            paths.add(currentLog.toAbsolutePath().normalize());
        }
        return paths;
    }

    // ---------------------------------------------------------------- the run

    private StorageReport run(Progress progress) throws InterruptedException {
        progress.stage("scan:REFERENCES");
        References refs = references();
        Set<Path> protectedPaths = protectedPaths(dirs, in.currentLog());

        StorageNode tree = new StorageNode(root.getFileName() == null ? root.toString()
                : root.getFileName().toString(), StorageCategory.OTHER, null, false,
                "cleanup.desc.dataRoot");

        progress.stage("scan:" + StorageCategory.INSTANCES.name());
        addIfAny(tree, instances());
        checkInterrupt();
        progress.stage("scan:" + StorageCategory.VERSIONS.name());
        addIfAny(tree, versions(refs));
        checkInterrupt();
        progress.stage("scan:" + StorageCategory.LIBRARIES.name());
        addIfAny(tree, libraries());
        checkInterrupt();
        progress.stage("scan:" + StorageCategory.ASSETS.name());
        addIfAny(tree, assets(refs));
        checkInterrupt();
        progress.stage("scan:" + StorageCategory.JAVA.name());
        addIfAny(tree, java());
        progress.stage("scan:" + StorageCategory.CACHE.name());
        addIfAny(tree, cache());
        progress.stage("scan:" + StorageCategory.LOGS.name());
        addIfAny(tree, logs());
        progress.stage("scan:" + StorageCategory.OTHER.name());
        addIfAny(tree, other(refs, protectedPaths));
        tree.total();
        checkInterrupt();

        progress.stage("scan:CANDIDATES");
        List<String> notes = new ArrayList<>();
        List<CleanupCandidate> candidates = candidates(refs, notes);
        candidates.sort(Comparator.comparingLong(CleanupCandidate::size).reversed());

        long usable = -1;
        long disk = -1;
        try {
            FileStore store = Files.getFileStore(root);
            usable = store.getUsableSpace();
            disk = store.getTotalSpace();
        } catch (IOException | RuntimeException ignored) {
            // A drive that will not say is a tile that says "unknown".
        }
        return new StorageReport(root, tree, candidates, usable, disk, refs.shared,
                refs.unresolved, notes);
    }

    private static void addIfAny(StorageNode parent, StorageNode child) {
        if (child.hasChildren()) {
            parent.add(child);
        }
    }

    private static void checkInterrupt() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("scan interrupted");
        }
    }

    // ---------------------------------------------------------------- references

    /** What the profiles use, worked out once for both the tree and the candidates. */
    private static final class References {
        final Set<String> versions = new LinkedHashSet<>();
        final Map<String, List<String>> usedBy = new HashMap<>();
        final Set<String> libraries = new HashSet<>();
        final Set<String> assetIds = new HashSet<>();
        final Set<String> hashes = new HashSet<>();
        final Set<String> virtualIds = new HashSet<>();
        final Set<String> logConfigs = new HashSet<>();
        final Map<String, List<String>> iconUsers = new HashMap<>();
        boolean forgeLike;
        boolean unresolved;
        boolean assetsUnknown;
        boolean shared;
    }

    private References references() {
        References refs = new References();
        // The official launcher's own file. A data folder with it is a
        // .minecraft that other programs run versions from, and "no profile
        // here uses it" says nothing about theirs.
        refs.shared = Files.exists(root.resolve("launcher_profiles.json"));

        for (Profile profile : in.profiles()) {
            if (profile.hasCustomIcon()) {
                refs.iconUsers.computeIfAbsent(profile.customIcon(), key -> new ArrayList<>())
                        .add(profile.name());
            }
            String effective = profile.effectiveVersionId();
            if (effective == null || effective.isBlank()) {
                continue;
            }
            refs.versions.add(effective);
            refs.usedBy.computeIfAbsent(effective, key -> new ArrayList<>()).add(profile.name());
            String minecraft = profile.minecraftVersion();
            if (minecraft != null && !minecraft.isBlank()) {
                refs.versions.add(minecraft);
            }
            try {
                for (VersionJson version : in.resolver().chain(effective)) {
                    refs.versions.add(version.id());
                }
            } catch (IOException | RuntimeException e) {
                // A profile that says it is installed, and whose version will
                // not read, could be using anything.
                if (profile.versionId() != null || in.resolver().isInstalled(effective)) {
                    refs.unresolved = true;
                }
            }
        }

        for (String id : List.copyOf(refs.versions)) {
            if (!in.resolver().isInstalled(id)) {
                continue;
            }
            try {
                Json raw = Json.read(dirs.versionJson(id));
                collectLibraries(raw, refs.libraries);
                String main = raw.get("mainClass").asString("");
                String lower = id.toLowerCase(Locale.ROOT);
                if (lower.contains("forge") || main.contains("cpw.mods")
                        || main.contains("net.minecraftforge") || main.contains("neoforged")) {
                    refs.forgeLike = true;
                }
            } catch (IOException | RuntimeException e) {
                refs.unresolved = true;
                continue;
            }
            try {
                VersionJson resolved = in.resolver().resolve(id);
                String assets = resolved.assetsId();
                refs.assetIds.add(assets);
                Path index = dirs.assetIndexFile(assets);
                if (Files.isRegularFile(index)) {
                    AssetIndex parsed = AssetIndex.parse(assets, Json.read(index));
                    parsed.objects().values().forEach(object ->
                            refs.hashes.add(object.hash().toLowerCase(Locale.ROOT)));
                    if (parsed.virtual() || parsed.mapToResources()) {
                        refs.virtualIds.add(assets);
                    }
                } else {
                    refs.assetsUnknown = true;
                }
                Json logging = resolved.logging();
                if (logging != null) {
                    String config = logging.get("client").get("file").get("id").asString(null);
                    if (config != null) {
                        refs.logConfigs.add(config);
                    }
                }
            } catch (IOException | RuntimeException e) {
                refs.unresolved = true;
            }
        }
        return refs;
    }

    /**
     * Every library path a version file names, for every platform.
     *
     * <p>Wider than what this machine downloads, on purpose: a path named for
     * another operating system is not here anyway, and one read too narrowly is
     * a library deleted from under a version that uses it.
     */
    static void collectLibraries(Json raw, Set<String> into) {
        for (Json library : raw.get("libraries").elements()) {
            try {
                String name = library.get("name").asString(null);
                MavenCoordinate coordinate = name == null ? null : MavenCoordinate.parse(name);
                if (coordinate != null) {
                    into.add(coordinate.path());
                }
                Json downloads = library.get("downloads");
                String artifact = downloads.get("artifact").get("path").asString(null);
                if (artifact != null) {
                    into.add(artifact);
                }
                downloads.get("classifiers").fields().forEach((classifier, value) -> {
                    String path = value.get("path").asString(null);
                    if (path != null) {
                        into.add(path);
                    }
                });
                if (coordinate != null) {
                    library.get("natives").fields().forEach((os, value) -> {
                        String classifier = value.asString(null);
                        if (classifier == null) {
                            return;
                        }
                        for (String bits : List.of("32", "64")) {
                            into.add(coordinate.withClassifier(
                                    classifier.replace("${arch}", bits)).path());
                        }
                    });
                }
            } catch (RuntimeException ignored) {
                // An entry that cannot be read names nothing this can match.
            }
        }
        List<String> strings = new ArrayList<>();
        flatten(raw.get("arguments"), strings);
        String legacy = raw.get("minecraftArguments").asString(null);
        if (legacy != null) {
            strings.add(legacy);
        }
        for (String value : strings) {
            Matcher matcher = LIBRARY_ARGUMENT.matcher(value);
            while (matcher.find()) {
                into.add(matcher.group(1).replace('\\', '/'));
            }
        }
    }

    private static void flatten(Json json, List<String> into) {
        if (json.isString()) {
            into.add(json.asString(""));
        } else if (json.isArray()) {
            json.elements().forEach(element -> flatten(element, into));
        } else if (json.isObject()) {
            json.fields().values().forEach(value -> flatten(value, into));
        }
    }

    private boolean libraryInUse(References refs, String relative) {
        if (refs.libraries.contains(relative)) {
            return true;
        }
        if (refs.forgeLike) {
            for (String group : FORGE_GROUPS) {
                if (relative.startsWith(group)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the tree

    private StorageNode instances() {
        StorageNode category = heading(StorageCategory.INSTANCES, "cleanup.desc.instances");
        Map<String, Profile> byFolder = new HashMap<>();
        for (Profile profile : in.profiles()) {
            Path folder = in.gameDirectory().apply(profile);
            if (folder != null && folder.getFileName() != null) {
                byFolder.put(folder.getFileName().toString(), profile);
            }
        }
        for (Path entry : list(dirs.instances())) {
            String name = entry.getFileName().toString();
            boolean directory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
            if (name.equals(ProfileStore.DELETING_DIR)) {
                category.add(measure(new StorageNode(name, StorageCategory.INSTANCES, entry, true,
                        "cleanup.desc.deleting")));
            } else if (directory && byFolder.containsKey(name)) {
                Profile profile = byFolder.get(name);
                StorageNode node = category.add(new StorageNode(profile.name(),
                        StorageCategory.INSTANCES, entry, true, "cleanup.desc.profile", profile.name())
                        .note("cleanup.note.folder", name).profile(profile.id()));
                expandInstance(node, entry);
            } else if (directory) {
                StorageNode node = category.add(new StorageNode(name, StorageCategory.INSTANCES,
                        entry, true, "cleanup.desc.orphanInstance").note("cleanup.note.noProfile"));
                expandInstance(node, entry);
            } else {
                category.add(measure(new StorageNode(name, StorageCategory.INSTANCES, entry, true,
                        "cleanup.desc.unknown")));
            }
        }
        return category;
    }

    private void expandInstance(StorageNode node, Path folder) {
        for (Path entry : list(folder)) {
            String name = entry.getFileName().toString();
            boolean directory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
            StorageNode part = node.add(new StorageNode(name, StorageCategory.INSTANCES, entry, true,
                    partKey(name, directory)));
            List<Path> inside = directory && expands(name) ? list(entry) : List.of();
            if (inside.isEmpty() || inside.size() > EXPAND_LIMIT) {
                measure(part);
                continue;
            }
            for (Path child : inside) {
                String childName = child.getFileName().toString();
                boolean childDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                part.add(measure(new StorageNode(childName, StorageCategory.INSTANCES, child, true,
                        childKey(name, childName, childDirectory))));
            }
        }
    }

    private static boolean expands(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "saves", "mods", "resourcepacks", "shaderpacks", "config", "screenshots",
                    "logs", "crash-reports", "schematics", "defaultconfigs" -> true;
            default -> false;
        };
    }

    /** The description of something directly in an instance folder. */
    static String partKey(String name, boolean directory) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".hexadron-")) {
            return "cleanup.desc.bookkeeping";
        }
        return switch (lower) {
            case "saves" -> "cleanup.desc.saves";
            case "mods" -> "cleanup.desc.mods";
            case "config" -> "cleanup.desc.config";
            case "defaultconfigs" -> "cleanup.desc.defaultconfigs";
            case "resourcepacks" -> "cleanup.desc.resourcepacks";
            case "shaderpacks" -> "cleanup.desc.shaderpacks";
            case "screenshots" -> "cleanup.desc.screenshots";
            case "logs" -> "cleanup.desc.gameLogs";
            case "crash-reports" -> "cleanup.desc.crashReports";
            case "options.txt", "optionsof.txt", "optionsshaders.txt" -> "cleanup.desc.options";
            case "servers.dat", "servers.dat_old" -> "cleanup.desc.servers";
            case "kubejs", "scripts" -> "cleanup.desc.scripts";
            case "schematics" -> "cleanup.desc.schematics";
            case ".fabric", ".mixin.out", ".cache" -> "cleanup.desc.loaderCache";
            case "resources", "assets" -> "cleanup.desc.legacyResources";
            case "usercache.json", "usernamecache.json" -> "cleanup.desc.userCache";
            default -> directory ? "cleanup.desc.instanceFolder" : "cleanup.desc.instanceFile";
        };
    }

    /** The description of something one level further in. */
    static String childKey(String parent, String name, boolean directory) {
        return switch (parent.toLowerCase(Locale.ROOT)) {
            case "saves" -> directory ? "cleanup.desc.world" : "cleanup.desc.instanceFile";
            case "mods" -> name.equals(".removed") ? "cleanup.desc.removedBin"
                    : name.startsWith(".hexadron-") ? "cleanup.desc.bookkeeping"
                    : directory ? "cleanup.desc.instanceFolder" : "cleanup.desc.modFile";
            case "resourcepacks", "shaderpacks" -> name.startsWith(".hexadron-")
                    ? "cleanup.desc.bookkeeping" : "cleanup.desc.packFile";
            case "config", "defaultconfigs" -> "cleanup.desc.configEntry";
            case "screenshots" -> "cleanup.desc.screenshot";
            case "logs" -> "cleanup.desc.gameLogFile";
            case "crash-reports" -> "cleanup.desc.crashReport";
            case "schematics" -> "cleanup.desc.schematicFile";
            default -> directory ? "cleanup.desc.instanceFolder" : "cleanup.desc.instanceFile";
        };
    }

    private StorageNode versions(References refs) {
        StorageNode category = heading(StorageCategory.VERSIONS, "cleanup.desc.versions");
        for (Path entry : list(dirs.versions())) {
            if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String id = entry.getFileName().toString();
            category.add(measure(noteUse(new StorageNode(id, StorageCategory.VERSIONS, entry, true,
                    "cleanup.desc.version", id), refs, id)));
        }
        Path natives = root.resolve("natives");
        if (Files.isDirectory(natives)) {
            StorageNode group = category.add(new StorageNode("natives", StorageCategory.VERSIONS,
                    natives, true, "cleanup.desc.natives"));
            for (Path entry : list(natives)) {
                String id = entry.getFileName().toString();
                group.add(measure(noteUse(new StorageNode(id, StorageCategory.VERSIONS, entry, true,
                        "cleanup.desc.nativesEntry", id), refs, id)));
            }
            if (!group.hasChildren()) {
                measure(group);
            }
        }
        return category;
    }

    private static StorageNode noteUse(StorageNode node, References refs, String id) {
        List<String> users = refs.usedBy.get(id);
        if (users != null && !users.isEmpty()) {
            return node.note("cleanup.note.usedBy", String.join(", ", users));
        }
        if (refs.versions.contains(id)) {
            return node.note("cleanup.note.parent");
        }
        return node.note("cleanup.note.unused");
    }

    private StorageNode libraries() {
        StorageNode category = heading(StorageCategory.LIBRARIES, "cleanup.desc.libraries");
        for (Path first : list(dirs.libraries())) {
            String firstName = first.getFileName().toString();
            if (!Files.isDirectory(first, LinkOption.NOFOLLOW_LINKS)) {
                category.add(measure(new StorageNode(firstName, StorageCategory.LIBRARIES, first, true,
                        "cleanup.desc.libraryGroup", firstName)));
                continue;
            }
            for (Path second : list(first)) {
                String name = firstName + "/" + second.getFileName();
                category.add(measure(new StorageNode(name, StorageCategory.LIBRARIES, second, true,
                        "cleanup.desc.libraryGroup", name.replace('/', '.'))));
            }
        }
        return category;
    }

    private StorageNode assets(References refs) {
        StorageNode category = heading(StorageCategory.ASSETS, "cleanup.desc.assets");
        for (Path entry : list(dirs.assets())) {
            String name = entry.getFileName().toString();
            switch (name) {
                case "objects" -> category.add(measure(new StorageNode(name, StorageCategory.ASSETS,
                        entry, true, "cleanup.desc.assetObjects")));
                case "indexes", "virtual", "log_configs" -> {
                    StorageNode part = category.add(new StorageNode(name, StorageCategory.ASSETS,
                            entry, true, "cleanup.desc.asset." + name));
                    for (Path child : list(entry)) {
                        String childName = child.getFileName().toString();
                        String id = childName.endsWith(".json")
                                ? childName.substring(0, childName.length() - 5) : childName;
                        boolean used = switch (name) {
                            case "indexes" -> refs.assetIds.contains(id);
                            case "virtual" -> refs.virtualIds.contains(id);
                            default -> refs.logConfigs.contains(childName);
                        };
                        part.add(measure(new StorageNode(childName, StorageCategory.ASSETS, child, true,
                                "cleanup.desc.asset." + name + ".entry", id)
                                .note(used ? "cleanup.note.inUse" : "cleanup.note.unused")));
                    }
                    if (!part.hasChildren()) {
                        measure(part);
                    }
                }
                default -> category.add(measure(new StorageNode(name, StorageCategory.ASSETS, entry,
                        true, "cleanup.desc.assetOther")));
            }
        }
        return category;
    }

    private StorageNode java() {
        StorageNode category = heading(StorageCategory.JAVA, "cleanup.desc.java");
        Map<Path, Integer> managed = new HashMap<>();
        for (int major : in.java().installedMajors()) {
            managed.put(in.java().home(major).toAbsolutePath().normalize(), major);
        }
        for (Path entry : list(dirs.javaRuntimes())) {
            String name = entry.getFileName().toString();
            Integer major = managed.get(entry.toAbsolutePath().normalize());
            StorageNode node = major == null
                    ? new StorageNode(name, StorageCategory.JAVA, entry, true, "cleanup.desc.javaOther")
                    : new StorageNode(name, StorageCategory.JAVA, entry, true,
                    "cleanup.desc.javaRuntime", major);
            if (major != null) {
                if (in.javaInUse() == null) {
                    node.note("cleanup.note.javaUnknown");
                } else {
                    node.note(in.javaInUse().contains(major) ? "cleanup.note.inUse" : "cleanup.note.unused");
                }
            }
            category.add(measure(node));
        }
        return category;
    }

    private StorageNode cache() {
        StorageNode category = heading(StorageCategory.CACHE, "cleanup.desc.cache");
        for (Path entry : list(dirs.cache())) {
            String name = entry.getFileName().toString();
            String key = switch (name) {
                case "modpacks" -> "cleanup.desc.cacheModpacks";
                case "loaders" -> "cleanup.desc.cacheLoaders";
                case "java" -> "cleanup.desc.cacheJava";
                case "mod-icons" -> "cleanup.desc.cacheModIcons";
                case "verified.index" -> "cleanup.desc.cacheVerified";
                case "version_manifest_v2.json" -> "cleanup.desc.cacheManifest";
                default -> "cleanup.desc.cacheOther";
            };
            category.add(measure(new StorageNode(name, StorageCategory.CACHE, entry, true, key)));
        }
        return category;
    }

    private StorageNode logs() {
        StorageNode category = heading(StorageCategory.LOGS, "cleanup.desc.logs");
        Path current = in.currentLog() == null ? null : in.currentLog().toAbsolutePath().normalize();
        for (Path entry : list(dirs.logs())) {
            boolean writing = entry.toAbsolutePath().normalize().equals(current);
            category.add(measure(new StorageNode(entry.getFileName().toString(), StorageCategory.LOGS,
                    entry, !writing, writing ? "cleanup.desc.launcherLogCurrent" : "cleanup.desc.launcherLog")
                    .note(writing ? "cleanup.note.protected" : null)));
        }
        return category;
    }

    private StorageNode other(References refs, Set<Path> protectedPaths) {
        StorageNode category = heading(StorageCategory.OTHER, "cleanup.desc.other");
        for (Path entry : list(root)) {
            String name = entry.getFileName().toString();
            if (KNOWN.contains(name)) {
                continue;
            }
            Path normalised = entry.toAbsolutePath().normalize();
            boolean isProtected = protectedPaths.contains(normalised);
            if (name.equals("icons")) {
                StorageNode icons = category.add(new StorageNode(name, StorageCategory.OTHER, entry, true,
                        "cleanup.desc.icons"));
                for (Path icon : list(entry)) {
                    String file = icon.getFileName().toString();
                    List<String> users = refs.iconUsers.get(file);
                    icons.add(measure(new StorageNode(file, StorageCategory.OTHER, icon, true,
                            "cleanup.desc.iconFile").note(users == null ? "cleanup.note.unused"
                            : "cleanup.note.usedBy", users == null ? null : String.join(", ", users))));
                }
                if (!icons.hasChildren()) {
                    measure(icons);
                }
            } else if (name.equals("skins")) {
                StorageNode skins = category.add(new StorageNode(name, StorageCategory.OTHER, entry, false,
                        "cleanup.desc.skins"));
                for (Path skin : list(entry)) {
                    boolean locked = protectedPaths.contains(skin.toAbsolutePath().normalize());
                    skins.add(measure(new StorageNode(skin.getFileName().toString(), StorageCategory.OTHER,
                            skin, !locked, locked ? "cleanup.desc.skinsIndex" : "cleanup.desc.skinFile")
                            .note(locked ? "cleanup.note.protected" : null)));
                }
                if (!skins.hasChildren()) {
                    measure(skins);
                }
            } else {
                String key = switch (name) {
                    case "launcher.json" -> "cleanup.desc.settingsFile";
                    case "accounts.json" -> "cleanup.desc.accountsFile";
                    case "profiles.json" -> "cleanup.desc.profilesFile";
                    case "secrets" -> "cleanup.desc.secrets";
                    case "wrapper" -> "cleanup.desc.wrapper";
                    case "agents" -> "cleanup.desc.agents";
                    default -> "cleanup.desc.unknown";
                };
                category.add(measure(new StorageNode(name, StorageCategory.OTHER, entry, !isProtected, key)
                        .note(isProtected ? "cleanup.note.protected" : null)));
            }
        }
        return category;
    }

    private static StorageNode heading(StorageCategory category, String key) {
        return new StorageNode(category.key(), category, null, false, key);
    }

    // ---------------------------------------------------------------- candidates

    private List<CleanupCandidate> candidates(References refs, List<String> notes) {
        List<CleanupCandidate> candidates = new ArrayList<>();
        if (refs.shared) {
            notes.add("cleanup.notice.shared");
        }
        if (refs.unresolved) {
            notes.add("cleanup.notice.unresolved");
        }
        if (refs.assetsUnknown && !refs.unresolved) {
            notes.add("cleanup.notice.assetsUnknown");
        }
        if (in.javaInUse() == null) {
            notes.add("cleanup.notice.javaUnknown");
        }
        long now = System.currentTimeMillis();

        // ------------------------------------------------ leftovers of deleted profiles
        Path deleting = dirs.instances().resolve(ProfileStore.DELETING_DIR);
        if (Files.isDirectory(deleting, LinkOption.NOFOLLOW_LINKS)) {
            long[] size = measure(deleting);
            candidates.add(new CleanupCandidate("deleting", StorageCategory.INSTANCES,
                    "cleanup.safe.deleting", "cleanup.reason.deleting", new Object[0], size[0], size[1],
                    List.of(new CleanupCandidate.Detail(ProfileStore.DELETING_DIR, size[0])),
                    CleanupAction.tree(deleting, size[0])));
        }

        // ------------------------------------------------ partial downloads
        List<Path> partial = new ArrayList<>();
        Path instancesRoot = dirs.instances().toAbsolutePath().normalize();
        walkFiles(root, instancesRoot, (file, attributes) -> {
            String name = file.getFileName().toString();
            boolean temporary = name.endsWith(".part")
                    || (name.startsWith(".hexadron-") && name.endsWith(".tmp"))
                    || name.equals("hexadron-launchwrapper.jar.tmp");
            if (temporary && now - attributes.lastModifiedTime().toMillis() > PARTIAL_AGE_MILLIS) {
                partial.add(file);
            }
        });
        Set<Path> partialSet = new HashSet<>(partial);
        addFiles(candidates, "partial", StorageCategory.CACHE, "cleanup.safe.partial",
                "cleanup.reason.partial", partial, null);

        if (!refs.shared) {
            // ------------------------------------------------ versions
            List<Path> unusedVersions = new ArrayList<>();
            for (Path entry : list(dirs.versions())) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                        && !refs.versions.contains(entry.getFileName().toString())) {
                    unusedVersions.add(entry);
                }
            }
            addTrees(candidates, "versions", StorageCategory.VERSIONS, "cleanup.safe.versions",
                    "cleanup.reason.versions", unusedVersions);

            List<Path> unusedNatives = new ArrayList<>();
            for (Path entry : list(root.resolve("natives"))) {
                if (!refs.versions.contains(entry.getFileName().toString())) {
                    unusedNatives.add(entry);
                }
            }
            addTrees(candidates, "natives", StorageCategory.VERSIONS, "cleanup.safe.natives",
                    "cleanup.reason.natives", unusedNatives);
        }

        if (!refs.shared && !refs.unresolved) {
            // ------------------------------------------------ libraries
            Path libraries = dirs.libraries().toAbsolutePath().normalize();
            List<Path> orphans = new ArrayList<>();
            walkFiles(libraries, null, (file, attributes) -> {
                if (partialSet.contains(file)) {
                    return;
                }
                String relative = libraries.relativize(file).toString().replace('\\', '/');
                if (!libraryInUse(refs, relative)) {
                    orphans.add(file);
                }
            });
            addFiles(candidates, "libraries", StorageCategory.LIBRARIES, "cleanup.safe.libraries",
                    "cleanup.reason.libraries", orphans, libraries);

            // ------------------------------------------------ assets
            if (!refs.assetsUnknown) {
                Path assets = dirs.assets().toAbsolutePath().normalize();
                List<Path> files = new ArrayList<>();
                List<CleanupCandidate.Detail> details = new ArrayList<>();
                long[] objects = {0, 0};
                walkFiles(assets.resolve("objects"), null, (file, attributes) -> {
                    String hash = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!partialSet.contains(file) && !refs.hashes.contains(hash)) {
                        files.add(file);
                        objects[0] += attributes.size();
                        objects[1]++;
                    }
                });
                if (objects[1] > 0) {
                    details.add(new CleanupCandidate.Detail("objects (" + objects[1] + ")", objects[0]));
                }
                List<Path> trees = new ArrayList<>();
                for (Path index : list(assets.resolve("indexes"))) {
                    String name = index.getFileName().toString();
                    String id = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
                    if (!refs.assetIds.contains(id)) {
                        trees.add(index);
                    }
                }
                for (Path virtual : list(assets.resolve("virtual"))) {
                    if (!refs.virtualIds.contains(virtual.getFileName().toString())) {
                        trees.add(virtual);
                    }
                }
                for (Path config : list(assets.resolve("log_configs"))) {
                    if (!refs.logConfigs.contains(config.getFileName().toString())) {
                        trees.add(config);
                    }
                }
                long size = objects[0];
                long count = objects[1];
                for (Path tree : trees) {
                    long[] measured = measure(tree);
                    size += measured[0];
                    count += measured[1];
                    details.add(new CleanupCandidate.Detail(assets.relativize(tree).toString()
                            .replace('\\', '/'), measured[0]));
                }
                if (count > 0) {
                    details.sort(Comparator.comparingLong(CleanupCandidate.Detail::size).reversed());
                    candidates.add(new CleanupCandidate("assets", StorageCategory.ASSETS,
                            "cleanup.safe.assets", "cleanup.reason.assets", new Object[]{count},
                            size, count, details,
                            new CleanupAction(trees, files, assets, List.of(), size)));
                }
            }
        }

        // ------------------------------------------------ Java
        if (in.javaInUse() != null) {
            List<Integer> majors = new ArrayList<>();
            List<Path> homes = new ArrayList<>();
            List<CleanupCandidate.Detail> details = new ArrayList<>();
            long size = 0;
            long count = 0;
            for (int major : in.java().installedMajors()) {
                if (in.javaInUse().contains(major)) {
                    continue;
                }
                Path home = in.java().home(major);
                long[] measured = measure(home);
                majors.add(major);
                homes.add(home);
                size += measured[0];
                count += measured[1];
                details.add(new CleanupCandidate.Detail("Java " + major, measured[0]));
            }
            if (!majors.isEmpty()) {
                candidates.add(new CleanupCandidate("java", StorageCategory.JAVA, "cleanup.safe.java",
                        "cleanup.reason.java", new Object[0], size, count, details,
                        CleanupAction.java(majors, homes, size)));
            }
        }

        // ------------------------------------------------ cache
        for (String part : List.of("modpacks", "loaders", "java", "mod-icons")) {
            Path folder = dirs.cache().resolve(part);
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            List<Path> entries = list(folder);
            String id = "cache-" + part;
            addTrees(candidates, id, StorageCategory.CACHE, "cleanup.safe." + id,
                    "cleanup.reason." + id, entries);
        }

        // ------------------------------------------------ old logs
        Path current = in.currentLog() == null ? null : in.currentLog().toAbsolutePath().normalize();
        List<Path> oldLogs = new ArrayList<>();
        for (Path log : list(dirs.logs())) {
            String name = log.getFileName().toString();
            if (name.matches("launcher-\\d+\\.log") && !log.toAbsolutePath().normalize().equals(current)) {
                oldLogs.add(log);
            }
        }
        addTrees(candidates, "logs", StorageCategory.LOGS, "cleanup.safe.logs", "cleanup.reason.logs",
                oldLogs);

        // ------------------------------------------------ icons
        List<Path> unusedIcons = new ArrayList<>();
        for (Path icon : list(dirs.icons())) {
            if (Files.isRegularFile(icon, LinkOption.NOFOLLOW_LINKS)
                    && !partialSet.contains(icon)
                    && !refs.iconUsers.containsKey(icon.getFileName().toString())) {
                unusedIcons.add(icon);
            }
        }
        addTrees(candidates, "icons", StorageCategory.OTHER, "cleanup.safe.icons",
                "cleanup.reason.icons", unusedIcons);
        return candidates;
    }

    private void addTrees(List<CleanupCandidate> into, String id, StorageCategory category,
                          String titleKey, String reasonKey, List<Path> trees) {
        if (trees.isEmpty()) {
            return;
        }
        List<CleanupCandidate.Detail> details = new ArrayList<>();
        long size = 0;
        long count = 0;
        for (Path tree : trees) {
            long[] measured = measure(tree);
            size += measured[0];
            count += Math.max(1, measured[1]);
            details.add(new CleanupCandidate.Detail(tree.getFileName().toString(), measured[0]));
        }
        details.sort(Comparator.comparingLong(CleanupCandidate.Detail::size).reversed());
        into.add(new CleanupCandidate(id, category, titleKey, reasonKey, new Object[]{trees.size()},
                size, count, details, CleanupAction.trees(trees, size)));
    }

    /**
     * Loose files as one candidate, with a detail line per group.
     *
     * @param root where the files are grouped from and where emptied folders
     *             stop; null for files scattered over the data folder, which
     *             are then deleted as single entries
     */
    private void addFiles(List<CleanupCandidate> into, String id, StorageCategory category,
                          String titleKey, String reasonKey, List<Path> files, Path root) {
        if (files.isEmpty()) {
            return;
        }
        Map<String, long[]> groups = new LinkedHashMap<>();
        long size = 0;
        for (Path file : files) {
            long bytes;
            try {
                bytes = Files.size(file);
            } catch (IOException e) {
                bytes = 0;
            }
            size += bytes;
            String group = groupOf(root == null ? this.root : root, file, root == null ? 1 : 2);
            long[] totals = groups.computeIfAbsent(group, key -> new long[2]);
            totals[0] += bytes;
            totals[1]++;
        }
        List<CleanupCandidate.Detail> details = new ArrayList<>();
        groups.forEach((group, totals) -> details.add(new CleanupCandidate.Detail(
                group + " (" + totals[1] + ")", totals[0])));
        details.sort(Comparator.comparingLong(CleanupCandidate.Detail::size).reversed());
        CleanupAction action = root == null
                ? CleanupAction.trees(files, size)
                : CleanupAction.files(files, root, size);
        into.add(new CleanupCandidate(id, category, titleKey, reasonKey, new Object[]{files.size()},
                size, files.size(), details, action));
    }

    private static String groupOf(Path root, Path file, int segments) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        int count = Math.min(segments, Math.max(1, relative.getNameCount() - 1));
        return relative.subpath(0, count).toString().replace('\\', '/');
    }

    // ---------------------------------------------------------------- disk

    private interface FileSink {
        void accept(Path file, BasicFileAttributes attributes);
    }

    /** Every regular file under {@code start}, never through a link, skipping {@code skip}. */
    private static void walkFiles(Path start, Path skip, FileSink sink) {
        if (start == null || !Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(start.toAbsolutePath().normalize(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (skip != null && directory.equals(skip)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        sink.accept(file, attributes);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // What could be read has been read.
        }
    }

    private static StorageNode measure(StorageNode node) {
        long[] measured = measure(node.path());
        return node.measured(measured[0], measured[1]);
    }

    /** Bytes and file count under a path, never following a link. */
    static long[] measure(Path path) {
        long[] totals = {0, 0};
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return totals;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        totals[0] += attributes.size();
                    }
                    totals[1]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // A partial count is still the right order of magnitude.
        }
        return totals;
    }

    /** The entries of a folder, sorted by name. Empty when it is not there. */
    static List<Path> list(Path directory) {
        if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            stream.forEach(entries::add);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        entries.sort(Comparator.comparing(path -> path.getFileName().toString(),
                String.CASE_INSENSITIVE_ORDER));
        return entries;
    }
}
