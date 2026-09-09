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

package com.hexadron.launcher.mods;

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

/**
 * Which modpacks an instance has, and what each of them put there.
 *
 * <p>The mods folder has {@link ModLibrary}; this is the same idea one level up,
 * and it lives in the instance folder rather than in {@code mods} because a pack
 * writes into several folders and belongs to none of them.
 *
 * <p>Read, changed and written as a whole. The file is a handful of entries even
 * on an instance somebody has rebuilt a dozen times, and a partial write of the
 * one record that says which files may be deleted is not a failure mode worth
 * having.
 */
public final class ModpackLibrary {

    /** The file, in the instance folder. */
    public static final String LOCK_FILE = ".hexadron-modpacks.json";

    private final Path file;
    private final Map<String, InstalledModpack> packs = new LinkedHashMap<>();

    private ModpackLibrary(Path file) {
        this.file = file;
    }

    /**
     * Reads the record for an instance.
     *
     * <p>Never throws. A record that cannot be read is an empty one, which lists
     * no packs and therefore offers to delete nothing - the safe way round for a
     * file whose contents are a delete list.
     */
    public static ModpackLibrary read(Path gameDirectory) {
        ModpackLibrary library = new ModpackLibrary(gameDirectory.resolve(LOCK_FILE));
        if (!Files.isRegularFile(library.file)) {
            return library;
        }
        try {
            Json root = Json.read(library.file);
            for (Json entry : root.get("modpacks").elements()) {
                InstalledModpack pack = InstalledModpack.fromJson(entry);
                if (!pack.id().isBlank()) {
                    library.packs.put(pack.id(), pack);
                }
            }
        } catch (IOException | RuntimeException e) {
            library.packs.clear();
        }
        return library;
    }

    /** Newest first: the one just installed is the one being looked for. */
    public List<InstalledModpack> all() {
        List<InstalledModpack> sorted = new ArrayList<>(packs.values());
        sorted.sort(Comparator.comparingLong(InstalledModpack::installedAt).reversed());
        return List.copyOf(sorted);
    }

    public boolean isEmpty() {
        return packs.isEmpty();
    }

    public int size() {
        return packs.size();
    }

    public boolean contains(String id) {
        return packs.containsKey(id);
    }

    public boolean contains(ModProvider.Source source, String projectId) {
        return packs.containsKey(InstalledModpack.idOf(source, projectId));
    }

    public Optional<InstalledModpack> get(String id) {
        return Optional.ofNullable(packs.get(id));
    }

    public void put(InstalledModpack pack) {
        packs.put(pack.id(), pack);
    }

    public void forget(String id) {
        packs.remove(id);
    }

    /**
     * Writes the record, or deletes it once there is nothing to record.
     *
     * <p>An empty file left behind in an instance folder is one more dotfile for
     * somebody to wonder about.
     */
    public void write() throws IOException {
        if (packs.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Json array = Json.array();
        all().forEach(pack -> array.add(pack.toJson()));
        Files.createDirectories(file.getParent());
        Json.object().put("modpacks", array).write(file);
    }
}
