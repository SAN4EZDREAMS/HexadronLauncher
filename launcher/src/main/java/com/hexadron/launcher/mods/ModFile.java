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

import java.util.List;

/**
 * A concrete downloadable mod file, resolved for one Minecraft version and one
 * loader.
 *
 * @param projectId    the provider's project identifier
 * @param projectSlug  human-readable identifier, used for logs and the lock file
 * @param versionId    the provider's identifier for this specific file
 * @param displayName  version name shown to the user
 * @param fileName     the name to write into the mods directory
 * @param url          direct download URL, or null when the provider forbids
 *                     third-party downloads for this project
 * @param sha1         digest, when published
 * @param size         bytes, or -1
 * @param dependencies required project ids that must also be installed
 * @param source       which provider produced this
 */
public record ModFile(String projectId, String projectSlug, String versionId, String displayName,
                      String fileName, String url, String sha1, long size,
                      List<String> dependencies, ModProvider.Source source) {

    public ModFile {
        dependencies = List.copyOf(dependencies);
    }

    public boolean isDownloadable() {
        return url != null && !url.isBlank();
    }

    public Json toJson() {
        Json deps = Json.array();
        dependencies.forEach(deps::add);
        Json json = Json.object()
                .put("source", source.name())
                .put("projectId", projectId)
                .put("versionId", versionId)
                .put("fileName", fileName)
                .put("displayName", displayName)
                .put("size", size)
                .put("dependencies", deps);
        if (projectSlug != null) {
            json.put("projectSlug", projectSlug);
        }
        if (url != null) {
            json.put("url", url);
        }
        if (sha1 != null) {
            json.put("sha1", sha1);
        }
        return json;
    }

    public static ModFile fromJson(Json json) {
        List<String> dependencies = new java.util.ArrayList<>();
        for (Json dep : json.get("dependencies").elements()) {
            String value = dep.asString(null);
            if (value != null) {
                dependencies.add(value);
            }
        }
        return new ModFile(
                json.get("projectId").asString(""),
                json.get("projectSlug").asString(null),
                json.get("versionId").asString(""),
                json.get("displayName").asString(""),
                json.get("fileName").asString(""),
                json.get("url").asString(null),
                json.get("sha1").asString(null),
                json.get("size").asLong(-1),
                dependencies,
                ModProvider.Source.valueOf(json.get("source").asString("MODRINTH")));
    }
}
