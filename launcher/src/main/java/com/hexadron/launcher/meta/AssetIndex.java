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

package com.hexadron.launcher.meta;

import com.hexadron.launcher.json.Json;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A parsed asset index: every sound, language file and texture the version needs.
 *
 * <p>Two legacy flags change where the files must end up:
 * <ul>
 *   <li>{@code virtual} (Minecraft 1.6): the game reads a materialised tree at
 *       {@code assets/virtual/<index>/<name>} instead of the hashed store.</li>
 *   <li>{@code map_to_resources} (pre-1.6): the game reads
 *       {@code <gameDir>/resources/<name>}.</li>
 * </ul>
 * Both are ignored by modern versions but must be honoured to claim "all versions".
 */
public record AssetIndex(String id, Map<String, AssetObject> objects, boolean virtual, boolean mapToResources) {

    /** One asset: content-addressed by SHA-1. */
    public record AssetObject(String hash, long size) {

        /** Path within the object store: {@code <first two hex chars>/<hash>}. */
        public String storePath() {
            String h = hash.toLowerCase(Locale.ROOT);
            return h.substring(0, 2) + "/" + h;
        }

        public String url() {
            return "https://resources.download.minecraft.net/" + storePath();
        }
    }

    public AssetIndex {
        objects = Map.copyOf(objects);
    }

    public static AssetIndex parse(String id, Json json) {
        Map<String, AssetObject> objects = new LinkedHashMap<>();
        Json objectsJson = json.get("objects");
        if (objectsJson.isObject()) {
            objectsJson.fields().forEach((name, entry) -> {
                String hash = entry.get("hash").asString(null);
                if (hash != null && hash.length() >= 2) {
                    objects.put(name, new AssetObject(hash, entry.get("size").asLong(-1)));
                }
            });
        }
        return new AssetIndex(
                id,
                objects,
                json.get("virtual").asBool(false),
                json.get("map_to_resources").asBool(false));
    }

    public int size() {
        return objects.size();
    }

    public long totalBytes() {
        return objects.values().stream().mapToLong(o -> Math.max(o.size(), 0)).sum();
    }

    /** True when the assets must additionally be materialised under a readable name. */
    public boolean needsMaterialisation() {
        return virtual || mapToResources;
    }
}
