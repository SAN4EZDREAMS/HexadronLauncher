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

package com.hexadron.launcher.profile;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persists profiles to {@code profiles.json} and owns their game directories. */
public final class ProfileStore {

    private final GameDirs dirs;
    private final Path file;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();

    /**
     * How the profiles are arranged, in the same file as the profiles.
     *
     * <p>The arrangement is the user's, so it belongs with the thing it
     * arranges: one file to copy to another machine, one file written atomically
     * when a drag ends, and no way to end up with groups referring to profiles
     * that a separately restored file no longer has.
     */
    private ProfileLayout layout = new ProfileLayout();
    private String selectedId;

    public ProfileStore(GameDirs dirs) {
        this.dirs = dirs;
        this.file = dirs.profilesFile();
    }

    public synchronized ProfileStore load() throws IOException {
        profiles.clear();
        selectedId = null;
        layout = new ProfileLayout();
        if (!Files.isRegularFile(file)) {
            return this;
        }
        Json root = Json.read(file);
        for (Json entry : root.get("profiles").elements()) {
            try {
                Profile profile = Profile.fromJson(entry);
                profiles.put(profile.id(), profile);
            } catch (RuntimeException e) {
                System.err.println("skipping unreadable profile entry: " + e.getMessage());
            }
        }
        selectedId = root.get("selected").asString(null);
        if (selectedId != null && !profiles.containsKey(selectedId)) {
            selectedId = null;
        }
        layout = ProfileLayout.fromJson(root.get("layout"));
        layout.reconcile(profiles.values());
        return this;
    }

    public synchronized void save() throws IOException {
        Json array = Json.array();
        profiles.values().forEach(profile -> array.add(profile.toJson()));
        Json root = Json.object().put("profiles", array);
        if (selectedId != null) {
            root.put("selected", selectedId);
        }
        root.put("layout", layout.toJson());
        root.write(file);
    }

    /** The shared arrangement: groups, order, and which interface is showing. */
    public synchronized ProfileLayout layout() {
        return layout;
    }

    /**
     * The profiles in the arranged order - what both interfaces draw.
     *
     * <p>Not {@link #byRecency()}. Recency is a useful default for a launcher
     * that arranges nothing, and it is exactly wrong once the user has put the
     * list in an order by hand: playing one instance would move it and reorder
     * the list underneath them.
     */
    public synchronized List<Profile> arranged() {
        layout.reconcile(profiles.values());
        List<Profile> ordered = new ArrayList<>();
        for (String id : layout.sequence()) {
            Profile profile = profiles.get(id);
            if (profile != null) {
                ordered.add(profile);
            }
        }
        return List.copyOf(ordered);
    }

    public synchronized List<Profile> all() {
        return List.copyOf(new ArrayList<>(profiles.values()));
    }

    /** Profiles ordered most recently played first - the useful order for a launcher's list. */
    public synchronized List<Profile> byRecency() {
        List<Profile> sorted = new ArrayList<>(profiles.values());
        sorted.sort(Comparator.comparingLong(Profile::lastPlayed).reversed()
                .thenComparing(Profile::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(sorted);
    }

    public synchronized Optional<Profile> byId(String id) {
        return Optional.ofNullable(profiles.get(id));
    }

    public synchronized Optional<Profile> selected() {
        if (selectedId != null) {
            return Optional.ofNullable(profiles.get(selectedId));
        }
        return byRecency().stream().findFirst();
    }

    public synchronized void select(Profile profile) {
        if (profiles.containsKey(profile.id())) {
            selectedId = profile.id();
        }
    }

    public synchronized Profile add(Profile profile) throws IOException {
        profiles.put(profile.id(), profile);
        if (selectedId == null) {
            selectedId = profile.id();
        }
        Files.createDirectories(gameDirectory(profile).resolve("mods"));
        layout.reconcile(profiles.values());
        return profile;
    }

    public synchronized void remove(Profile profile) {
        profiles.remove(profile.id());
        layout.reconcile(profiles.values());
        if (profile.id().equals(selectedId)) {
            selectedId = profiles.keySet().stream().findFirst().orElse(null);
        }
        // The instance directory is left on disk by this method: it holds the
        // user's worlds. Deleting saved games as a side effect of removing a
        // list entry is not a recoverable mistake, so it takes the separate,
        // explicit call below.
    }

    /**
     * Removes a profile and deletes its game folder.
     *
     * <p>Separate from {@link #remove} on purpose. Both are legitimate and
     * neither is a safe default for the other: a player who removes an old
     * instance usually wants the twenty gigabytes back, and a player who removes
     * one by accident must not lose a world to it. The interface asks which.
     *
     * <p>Deletion is deepest-first and best-effort. On Windows a file the game
     * still has open cannot be deleted at all, and a folder that is one locked
     * shader cache short of empty is a normal outcome rather than a failure to
     * hide - so what survived is returned and reported, instead of leaving the
     * user to wonder why the folder is still there.
     *
     * @return the paths that could not be deleted, empty when the folder is gone
     */
    public synchronized List<Path> removeWithFiles(Profile profile) throws IOException {
        Path directory = gameDirectory(profile);
        remove(profile);
        return deleteRecursively(directory);
    }

    /**
     * Deletes a directory tree, deepest entry first.
     *
     * <p>Refuses anything that is not inside the instances folder. Profile ids
     * are generated, but {@code profiles.json} is an editable file on disk, and
     * the one thing this method must never do is accept a hand-edited id that
     * resolves somewhere else.
     */
    private List<Path> deleteRecursively(Path root) throws IOException {
        Path instances = dirs.instances().toAbsolutePath().normalize();
        Path target = root.toAbsolutePath().normalize();
        if (!target.startsWith(instances) || target.equals(instances)) {
            throw new IOException("refusing to delete " + target
                    + ": it is not an instance folder under " + instances);
        }
        if (!Files.exists(target)) {
            return List.of();
        }

        List<Path> failed = new ArrayList<>();
        try (var entries = Files.walk(target)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    failed.add(path);
                }
            }
        }
        return List.copyOf(failed);
    }

    public synchronized boolean isEmpty() {
        return profiles.isEmpty();
    }

    /** The isolated game directory for a profile. */
    public Path gameDirectory(Profile profile) {
        return dirs.instance(profile.id());
    }

    public Path modsDirectory(Profile profile) {
        return gameDirectory(profile).resolve("mods");
    }
}
