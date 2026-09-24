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
    public List<Path> removeWithFiles(Profile profile) throws IOException {
        Path directory;
        synchronized (this) {
            directory = gameDirectory(profile);
            remove(profile);
        }
        try {
            return deleteInstanceFolder(directory, com.hexadron.launcher.core.Progress.NOOP, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("deleting " + directory + " was interrupted", e);
        }
    }

    /** Where folders wait to be deleted. Under the instances folder, so on the same drive. */
    public static final String DELETING_DIR = ".deleting";

    /**
     * Deletes an instance folder, reporting as it goes.
     *
     * <p>Not synchronized, deliberately. The old version held this store's lock
     * for the whole deletion, and the window asks this store for the profile
     * list on every repaint - so the launcher froze for as long as the files
     * took, even with the deletion on a background thread.
     *
     * <p>The folder is first renamed into {@link #DELETING_DIR}. A rename on the
     * same drive is instant, so the instance is gone from where it was at once;
     * what is left is emptying a folder nothing refers to, and if the launcher
     * is closed half-way, {@link #purgeLeftovers} finishes it on the next start.
     * When the rename is refused - on Windows, a file in the folder is open - the
     * files are deleted where they are, and whatever is locked is reported.
     *
     * @param counted told how many files there are before the first is deleted;
     *                may be null
     * @return the paths that could not be deleted, empty when the folder is gone
     */
    public List<Path> deleteInstanceFolder(Path directory, com.hexadron.launcher.core.Progress progress,
                                           java.util.function.IntConsumer counted)
            throws IOException, InterruptedException {
        Path target = checkedInstanceFolder(directory);
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Path doomed = moveAside(target);
        List<Path> failed = com.hexadron.launcher.util.TreeDeleter
                .deleteTree(doomed, progress, counted).failed();
        if (!doomed.equals(target)) {
            try {
                Files.deleteIfExists(doomed.getParent());
            } catch (IOException e) {
                // Another deletion is still using it, or something is left in it.
            }
        }
        return failed;
    }

    /**
     * Finishes deletions an earlier run did not get to the end of.
     *
     * <p>Called once in the background at start-up. Never throws.
     */
    public void purgeLeftovers() {
        Path deleting = dirs.instances().resolve(DELETING_DIR);
        if (!Files.isDirectory(deleting, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            com.hexadron.launcher.util.TreeDeleter.deleteTree(deleting,
                    com.hexadron.launcher.core.Progress.NOOP, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path moveAside(Path target) {
        Path deleting = dirs.instances().resolve(DELETING_DIR);
        try {
            Files.createDirectories(deleting);
            Path aside = deleting.resolve(target.getFileName() + "-" + System.nanoTime());
            Files.move(target, aside, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return aside;
        } catch (IOException | RuntimeException e) {
            return target;
        }
    }

    /** The same refusal as before: nothing outside the instances folder is ever deleted. */
    private Path checkedInstanceFolder(Path root) throws IOException {
        Path instances = dirs.instances().toAbsolutePath().normalize();
        Path target = root.toAbsolutePath().normalize();
        if (!target.startsWith(instances) || target.equals(instances)
                || target.equals(instances.resolve(DELETING_DIR))) {
            throw new IOException("refusing to delete " + target
                    + ": it is not an instance folder under " + instances);
        }
        return target;
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

    /**
     * The folder inside a profile where one kind of content lives.
     *
     * <p>{@code mods}, {@code resourcepacks}, {@code shaderpacks} - the names
     * are Minecraft's own and belong to the kind, not to this class, which is
     * why they come from {@link com.hexadron.launcher.mods.ContentKind}.
     *
     * @throws IllegalArgumentException for a kind with no single folder: a
     *                                 modpack is unpacked across the whole
     *                                 instance, and a data pack goes into one
     *                                 world rather than into the instance
     */
    public Path contentDirectory(Profile profile,
                                 com.hexadron.launcher.mods.ContentKind kind) {
        if (kind == null || !kind.hasInstanceFolder()) {
            throw new IllegalArgumentException(kind + " has no folder of its own in an instance");
        }
        return gameDirectory(profile).resolve(kind.instanceFolder());
    }
}
