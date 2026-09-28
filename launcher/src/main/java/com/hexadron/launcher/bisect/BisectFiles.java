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

package com.hexadron.launcher.bisect;

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.mods.ModEntry;
import com.hexadron.launcher.mods.ModScan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The part of a search that touches files: switching mods on and off by
 * renaming them, reading the dependency graph from the jars, and saving the
 * state beside the game.
 *
 * <p>A mod is switched off the way the mods window does it, by adding
 * {@code .disabled} to its file name. Nothing is moved out of the folder and
 * nothing is deleted, so whatever happens to the launcher in the middle of a
 * search, every mod is still in the mods folder under a name the player and
 * every other launcher recognise.
 */
public final class BisectFiles {

    /** Where a running search is saved, in the profile's game folder. */
    public static final String STATE_FILE = ".hexadron-bisect.json";

    private BisectFiles() {
    }

    /** The file names of the jars that are switched on, without {@code .disabled}. */
    public static List<String> enabledJars(Path modsDir) throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return names;
        }
        try (var stream = Files.newDirectoryStream(modsDir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && ModScan.isJar(name)
                        && ModScan.isEnabled(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Switches every mod of the search on or off to match {@code on}. Mods that
     * were not part of the search are left as they are.
     *
     * @return the mods that are no longer in the folder under either name
     */
    public static List<String> apply(Path modsDir, Collection<String> original, Set<String> on)
            throws IOException {
        List<String> missing = new ArrayList<>();
        for (String name : original) {
            Path enabled = modsDir.resolve(name);
            Path disabled = modsDir.resolve(name + ModScan.DISABLED_SUFFIX);
            boolean isOn = Files.isRegularFile(enabled, LinkOption.NOFOLLOW_LINKS);
            boolean isOff = Files.isRegularFile(disabled, LinkOption.NOFOLLOW_LINKS);
            if (!isOn && !isOff) {
                missing.add(name);
                continue;
            }
            boolean want = on.contains(name);
            if (want && !isOn) {
                Files.move(disabled, enabled, StandardCopyOption.ATOMIC_MOVE);
            } else if (!want && isOn && !isOff) {
                Files.move(enabled, disabled, StandardCopyOption.ATOMIC_MOVE);
            }
        }
        return missing;
    }

    /**
     * Which mod of the search needs which, from the requirements each jar
     * declares. Only mods of the search count; a requirement on the game or the
     * loader is not a file here.
     */
    public static Bisect.Graph graph(List<ModEntry> mods, Collection<String> original) {
        Map<String, String> byId = new LinkedHashMap<>();
        Map<String, List<String>> declared = new LinkedHashMap<>();
        for (ModEntry mod : mods) {
            String name = ModScan.enabledName(mod.fileName());
            if (!original.contains(name)) {
                continue;
            }
            var info = ModScan.descriptorOf(mod.path());
            for (String id : com.hexadron.launcher.mods.LegacyDependencies.provides(mod.path(), info.modId())) {
                byId.putIfAbsent(id, name);
            }
            List<String> needs = new ArrayList<>(info.depends());
            // Forge 1.12 mods name what they need in their @Mod annotation, not
            // in mcmod.info. Without these, half of a search's launches stop on
            // a missing library and count as the problem.
            if (com.hexadron.launcher.mods.LegacyDependencies.isLegacyForge(mod.path())) {
                needs.addAll(com.hexadron.launcher.mods.LegacyDependencies.of(mod.path()));
            }
            declared.put(name, needs);
        }
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        declared.forEach((name, ids) -> {
            Set<String> needs = new LinkedHashSet<>();
            for (String id : ids) {
                String provider = byId.get(id.trim().toLowerCase(Locale.ROOT));
                if (provider != null && !provider.equals(name)) {
                    needs.add(provider);
                }
            }
            deps.put(name, needs);
        });
        return new Bisect.Graph(deps);
    }

    /** The saved search of a profile, or empty; a file that does not read is ignored. */
    public static Optional<Bisect.State> load(Path gameDir) {
        Path file = gameDir.resolve(STATE_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Bisect.fromJson(Json.read(file)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public static void save(Path gameDir, Bisect.State state, String profileId) throws IOException {
        Files.createDirectories(gameDir);
        Path temp = gameDir.resolve(STATE_FILE + ".part");
        Bisect.toJson(state, profileId).write(temp);
        Files.move(temp, gameDir.resolve(STATE_FILE), StandardCopyOption.REPLACE_EXISTING);
    }

    public static void delete(Path gameDir) throws IOException {
        Files.deleteIfExists(gameDir.resolve(STATE_FILE));
        Files.deleteIfExists(gameDir.resolve(LEARNED_FILE));
        Files.deleteIfExists(gameDir.resolve(PROBLEM_FILE));
    }

    /**
     * The crash a search looks for, when it was started after one. A launch
     * that stops with another crash is not an answer to the search.
     */
    public static final String PROBLEM_FILE = ".hexadron-bisect-problem.json";

    public static void saveProblem(Path gameDir, com.hexadron.launcher.crash.CrashSignature signature)
            throws IOException {
        Json root = Json.object();
        root.put("key", signature.key());
        root.put("label", signature.label());
        Path temp = gameDir.resolve(PROBLEM_FILE + ".part");
        root.write(temp);
        Files.move(temp, gameDir.resolve(PROBLEM_FILE), StandardCopyOption.REPLACE_EXISTING);
    }

    /** The crash the search looks for; empty when it was not started after one. */
    public static Optional<com.hexadron.launcher.crash.CrashSignature> problem(Path gameDir) {
        Path file = gameDir.resolve(PROBLEM_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            Json root = Json.read(file);
            var signature = new com.hexadron.launcher.crash.CrashSignature(root.get("key").asString(""),
                    root.get("label").asString(""));
            return signature.known() ? Optional.of(signature) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Requirements a search found out by itself: a launch stopped because a
     * mod needed another that the search had switched off. Kept beside the
     * state, so an interrupted search keeps what it learned.
     */
    public static final String LEARNED_FILE = ".hexadron-bisect-learned.json";

    /**
     * Mods a search found that cannot start at all: they need a mod that is
     * not in the folder. Every launch with one of them stops before the game,
     * so the search keeps them off; they cannot be the answer either way.
     */
    public static Set<String> learnedOff(Path gameDir) {
        Set<String> off = new LinkedHashSet<>();
        Path file = gameDir.resolve(LEARNED_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return off;
        }
        try {
            for (Json name : Json.read(file).get("off").elements()) {
                String value = name.asString("");
                if (!value.isBlank() && !value.contains("/") && !value.contains("\\")) {
                    off.add(value);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Teaches nothing.
        }
        return off;
    }

    /**
     * Adds mods to the ones the search keeps off.
     *
     * @return true when any of them is new
     */
    public static boolean learnOff(Path gameDir, Collection<String> files) throws IOException {
        Set<String> off = learnedOff(gameDir);
        if (!off.addAll(files)) {
            return false;
        }
        writeLearned(gameDir, learned(gameDir), off);
        return true;
    }

    /** What a search learned, file to the files it needs; empty when nothing. */
    public static Map<String, Set<String>> learned(Path gameDir) {
        Path file = gameDir.resolve(LEARNED_FILE);
        Map<String, Set<String>> learned = new LinkedHashMap<>();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return learned;
        }
        try {
            Json root = Json.read(file);
            for (Map.Entry<String, Json> entry : root.get("needs").fields().entrySet()) {
                Set<String> needs = new LinkedHashSet<>();
                for (Json name : entry.getValue().elements()) {
                    String value = name.asString("");
                    if (!value.isBlank()) {
                        needs.add(value);
                    }
                }
                learned.put(entry.getKey(), needs);
            }
        } catch (IOException | RuntimeException e) {
            // A file that does not read teaches nothing; the search goes on without it.
        }
        return learned;
    }

    /**
     * Adds requirements to what the search learned.
     *
     * @return true when any of them is new
     */
    public static boolean learn(Path gameDir, Map<String, Set<String>> more) throws IOException {
        Map<String, Set<String>> learned = learned(gameDir);
        boolean added = false;
        for (Map.Entry<String, Set<String>> entry : more.entrySet()) {
            for (String need : entry.getValue()) {
                if (!need.equals(entry.getKey())) {
                    added |= learned.computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>()).add(need);
                }
            }
        }
        if (!added) {
            return false;
        }
        writeLearned(gameDir, learned, learnedOff(gameDir));
        return true;
    }

    private static void writeLearned(Path gameDir, Map<String, Set<String>> learned, Set<String> off)
            throws IOException {
        Json needs = Json.object();
        learned.forEach((file, files) -> {
            Json list = Json.array();
            files.forEach(name -> list.add(Json.of(name)));
            needs.put(file, list);
        });
        Json root = Json.object();
        root.put("needs", needs);
        Json offList = Json.array();
        off.forEach(name -> offList.add(Json.of(name)));
        root.put("off", offList);
        Path temp = gameDir.resolve(LEARNED_FILE + ".part");
        root.write(temp);
        Files.move(temp, gameDir.resolve(LEARNED_FILE), StandardCopyOption.REPLACE_EXISTING);
    }
}
