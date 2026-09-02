/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.mods;

import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the launcher put into one profile's {@code mods} folder, and why.
 *
 * <p>Backed by {@code mods/.hexadron-mods.json}. The point of the file is that
 * the launcher can tell its own downloads apart from jars the user dropped in
 * by hand: anything not listed here is never deleted, moved or reported as
 * managed. Deleting a file a player copied in themselves is not recoverable
 * from inside the launcher.
 *
 * <p>Version 1 of the file had no {@code origin} field because the pack
 * installer was its only writer. Reading one therefore marks every entry as
 * pack-owned - that is what those entries actually were, and guessing
 * "user-installed" instead would let the next click delete a mod out of the
 * middle of the optimisation set.
 */
public final class ModLibrary {

    public static final String LOCK_FILE = ".hexadron-mods.json";
    /**
     * Version 3 added the project's logo and page to each entry, and version 4
     * its categories. Both are readable by an older build - the extra fields are
     * simply ignored - so the number records when they appeared rather than
     * gating anything.
     */
    private static final int FORMAT_VERSION = 4;

    /** The pack that wrote every version-1 lock file. */
    private static final String LEGACY_PACK_ID = "hexadron-optimise";

    private final Path modsDir;
    private final Map<String, InstalledMod> mods = new LinkedHashMap<>();

    private ModLibrary(Path modsDir) {
        this.modsDir = modsDir;
    }

    /** Reads the lock file. A missing or unreadable one yields an empty library. */
    public static ModLibrary read(Path modsDir) {
        ModLibrary library = new ModLibrary(modsDir);
        Path lock = modsDir.resolve(LOCK_FILE);
        if (!Files.isRegularFile(lock)) {
            return library;
        }
        try {
            Json root = Json.read(lock);
            boolean legacy = root.get("version").asInt(FORMAT_VERSION) < 2;
            root.get("mods").fields().forEach((key, value) -> {
                try {
                    library.mods.put(key, InstalledMod.fromJson(value, legacy, LEGACY_PACK_ID));
                } catch (RuntimeException ignored) {
                    // A corrupt entry means that file is treated as user-managed,
                    // which is the safe direction: it will not be deleted.
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // Same reasoning: an unreadable lock must not block installing mods.
        }
        return library;
    }

    public Path modsDirectory() {
        return modsDir;
    }

    /** Every managed mod, in insertion order. */
    public List<InstalledMod> all() {
        return List.copyOf(mods.values());
    }

    public boolean isEmpty() {
        return mods.isEmpty();
    }

    public int size() {
        return mods.size();
    }

    public boolean contains(String key) {
        return mods.containsKey(key);
    }

    public boolean contains(ModProvider.Source source, String projectId) {
        return mods.containsKey(InstalledMod.keyOf(source, projectId));
    }

    public Optional<InstalledMod> get(String key) {
        return Optional.ofNullable(mods.get(key));
    }

    public void put(InstalledMod mod) {
        mods.put(mod.key(), mod);
    }

    public void forget(String key) {
        mods.remove(key);
    }

    /** Entries owned by a pack. */
    public List<InstalledMod> ofPack(String packId) {
        List<InstalledMod> owned = new ArrayList<>();
        mods.values().stream().filter(mod -> mod.belongsTo(packId)).forEach(owned::add);
        return List.copyOf(owned);
    }

    public boolean isPackInstalled(String packId) {
        return mods.values().stream().anyMatch(mod -> mod.belongsTo(packId));
    }

    /** Titles for the profile summary, in a stable order. */
    public List<String> titles() {
        return mods.values().stream().map(InstalledMod::title).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /**
     * Drops entries whose jar is no longer on disk.
     *
     * <p>A player who deletes a jar in Explorer expects the launcher to agree
     * with the folder rather than with its own bookkeeping.
     */
    public ModLibrary pruneMissingFiles() {
        mods.entrySet().removeIf(entry ->
                !Files.isRegularFile(modsDir.resolve(entry.getValue().file().fileName())));
        return this;
    }

    public void write() throws IOException {
        Files.createDirectories(modsDir);
        Json entries = Json.object();
        mods.forEach((key, mod) -> entries.put(key, mod.toJson()));
        Json.object()
                .put("version", FORMAT_VERSION)
                .put("mods", entries)
                .write(modsDir.resolve(LOCK_FILE));
    }
}
