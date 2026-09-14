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
 * @param gameVersions the Minecraft versions the platform publishes this file
 *                     for. Empty when nothing was said about them, which is not
 *                     the same as "none" - see {@link #supports}
 */
public record ModFile(String projectId, String projectSlug, String versionId, String displayName,
                      String fileName, String url, String sha1, long size,
                      List<String> dependencies, ModProvider.Source source,
                      List<String> gameVersions) {

    public ModFile {
        dependencies = List.copyOf(dependencies);
        gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
    }

    /**
     * The form for callers that are rebuilding a file rather than reading one
     * from a platform - a mirrored download, a name parsed out of a pack.
     *
     * <p>Those have no version list of their own to pass, and inventing one
     * would be worse than having none: {@link #supports} treats an empty list as
     * "not known", and that is exactly what it is.
     */
    public ModFile(String projectId, String projectSlug, String versionId, String displayName,
                   String fileName, String url, String sha1, long size,
                   List<String> dependencies, ModProvider.Source source) {
        this(projectId, projectSlug, versionId, displayName, fileName, url, sha1, size,
                dependencies, source, List.of());
    }

    public boolean isDownloadable() {
        return url != null && !url.isBlank();
    }

    /**
     * Whether this file is published for {@code minecraftVersion}.
     *
     * <p>True when the platform listed no versions at all. An unknown answer
     * must not block an install: the launcher already refuses a lookup that
     * cannot be narrowed to a version, and a second guess on top of that would
     * only turn a working install into a false refusal. What this catches is the
     * case where the platform <em>did</em> say, and said something else - a file
     * pinned by a pack, or one carried over from a profile on another version.
     *
     * <p>CurseForge lists loader names in the same array as the versions, which
     * costs nothing here: a containment test cannot be confused by an extra
     * entry that is not a version.
     */
    public boolean supports(String minecraftVersion) {
        if (gameVersions.isEmpty() || minecraftVersion == null || minecraftVersion.isBlank()) {
            return true;
        }
        return gameVersions.contains(minecraftVersion);
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
        // Written only when there is something to write, so a lock file from a
        // build that predates this field stays byte-identical after a rewrite.
        if (!gameVersions.isEmpty()) {
            Json versions = Json.array();
            gameVersions.forEach(versions::add);
            json.put("gameVersions", versions);
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
        List<String> gameVersions = new java.util.ArrayList<>();
        for (Json version : json.get("gameVersions").elements()) {
            String value = version.asString(null);
            if (value != null) {
                gameVersions.add(value);
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
                ModProvider.Source.valueOf(json.get("source").asString("MODRINTH")),
                gameVersions);
    }
}
