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
    private String selectedId;

    public ProfileStore(GameDirs dirs) {
        this.dirs = dirs;
        this.file = dirs.profilesFile();
    }

    public synchronized ProfileStore load() throws IOException {
        profiles.clear();
        selectedId = null;
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
        return this;
    }

    public synchronized void save() throws IOException {
        Json array = Json.array();
        profiles.values().forEach(profile -> array.add(profile.toJson()));
        Json root = Json.object().put("profiles", array);
        if (selectedId != null) {
            root.put("selected", selectedId);
        }
        root.write(file);
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
        return profile;
    }

    public synchronized void remove(Profile profile) {
        profiles.remove(profile.id());
        if (profile.id().equals(selectedId)) {
            selectedId = profiles.keySet().stream().findFirst().orElse(null);
        }
        // The instance directory is deliberately left on disk: it holds the
        // user's worlds. Deleting saved games as a side effect of removing a
        // list entry is not a recoverable mistake.
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
