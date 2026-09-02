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

package com.hexadron.launcher.meta;

import com.hexadron.launcher.json.Json;

/**
 * A downloadable file described by version metadata.
 *
 * @param path repository-relative path under {@code libraries/}, may be null for
 *             non-library artifacts such as the client jar
 * @param url  absolute download URL, or null when the artifact is produced
 *             locally (Forge install processors emit some libraries)
 * @param sha1 expected digest, or null when the source publishes none
 * @param size expected size in bytes, or -1 when unknown
 */
public record Artifact(String path, String url, String sha1, long size) {

    public static Artifact parse(Json json) {
        if (!json.isObject()) {
            return null;
        }
        return new Artifact(
                json.get("path").asString(null),
                json.get("url").asString(null),
                json.get("sha1").asString(null),
                json.get("size").asLong(-1));
    }

    public boolean hasUrl() {
        return url != null && !url.isBlank();
    }
}
