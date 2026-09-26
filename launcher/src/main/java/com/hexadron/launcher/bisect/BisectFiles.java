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
            if (info.modId() != null && !info.modId().isBlank()) {
                byId.putIfAbsent(info.modId().trim().toLowerCase(Locale.ROOT), name);
            }
            declared.put(name, info.depends());
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
    }
}
