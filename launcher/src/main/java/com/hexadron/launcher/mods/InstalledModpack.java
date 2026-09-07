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

import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.json.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * A modpack that was installed into an instance, and every file it wrote.
 *
 * <h2>Why the file list is stored and not derived</h2>
 *
 * <p>A mod is one jar in one folder, so removing it needs only its name. A
 * modpack writes across a whole instance - jars into {@code mods}, a resource
 * pack into {@code resourcepacks}, a keybinding file into {@code config}, a
 * shader preset, sometimes a world - and after the first launch the game has
 * written into the same folders. Nothing about the instance afterwards says
 * which of those files came out of the pack.
 *
 * <p>Deriving it later is not possible and guessing it is worse: "delete
 * {@code config}" takes the player's own settings with it, and "delete
 * everything in {@code mods}" takes the three mods they added on top. So the
 * paths are written down at install time, and removal deletes exactly them. It
 * is the same rule the mod lock file follows - if the launcher did not record
 * it, the launcher does not touch it - applied to a thing that spans folders.
 *
 * @param id               how this pack is addressed: {@code <SOURCE>:<projectId>}
 *                         for one from a platform, {@code file:<name>} for a pack
 *                         file the user opened themselves
 * @param name             the pack's own name
 * @param version          the pack's version, or null
 * @param author           who published it, or null
 * @param source           the platform, or null for a local file
 * @param projectId        the platform's id, or null for a local file
 * @param iconUrl          the pack's logo, or null
 * @param pageUrl          the pack's page, or null
 * @param minecraftVersion the version the pack was installed for
 * @param loader           the loader it needs
 * @param loaderVersion    that loader's version, or null
 * @param paths            every file the install wrote, relative to the instance
 *                         folder, {@code /}-separated
 * @param installedAt      epoch milliseconds
 */
public record InstalledModpack(String id, String name, String version, String author,
                               ModProvider.Source source, String projectId,
                               String iconUrl, String pageUrl,
                               String minecraftVersion, LoaderType loader, String loaderVersion,
                               List<String> paths, long installedAt) {

    /** The prefix that keeps a pack opened from disk from colliding with a platform id. */
    public static final String FILE_ID_PREFIX = "file:";

    public InstalledModpack {
        paths = List.copyOf(paths);
    }

    /** How a pack from a platform is addressed. */
    public static String idOf(ModProvider.Source source, String projectId) {
        return source.name() + ":" + projectId;
    }

    /** How a pack file the user opened is addressed. */
    public static String idOf(String fileName) {
        return FILE_ID_PREFIX + fileName;
    }

    /** True when there is a page worth offering to open. */
    public boolean hasPage() {
        return pageUrl != null && !pageUrl.isBlank();
    }

    /** What the row's second line says: version, then platform. */
    public String subtitle() {
        StringBuilder line = new StringBuilder();
        if (version != null && !version.isBlank()) {
            line.append(version);
        }
        String platform = source == null ? null : source.displayName();
        if (platform != null) {
            if (line.length() > 0) {
                line.append("  ·  ");
            }
            line.append(platform);
        }
        return line.toString();
    }

    public Json toJson() {
        Json array = Json.array();
        paths.forEach(array::add);
        Json json = Json.object()
                .put("id", id)
                .put("name", name == null ? "" : name)
                .put("minecraftVersion", minecraftVersion == null ? "" : minecraftVersion)
                .put("loader", loader == null ? LoaderType.VANILLA.id() : loader.id())
                .put("installedAt", installedAt)
                .put("paths", array);
        if (version != null) {
            json.put("version", version);
        }
        if (author != null) {
            json.put("author", author);
        }
        if (source != null) {
            json.put("source", source.name());
        }
        if (projectId != null) {
            json.put("projectId", projectId);
        }
        if (iconUrl != null) {
            json.put("iconUrl", iconUrl);
        }
        if (pageUrl != null) {
            json.put("pageUrl", pageUrl);
        }
        if (loaderVersion != null) {
            json.put("loaderVersion", loaderVersion);
        }
        return json;
    }

    public static InstalledModpack fromJson(Json json) {
        List<String> paths = new ArrayList<>();
        for (Json path : json.get("paths").elements()) {
            String value = path.asString(null);
            if (value != null && !value.isBlank()) {
                paths.add(value);
            }
        }
        ModProvider.Source source = null;
        String sourceName = json.get("source").asString(null);
        if (sourceName != null) {
            try {
                source = ModProvider.Source.valueOf(sourceName);
            } catch (IllegalArgumentException e) {
                // A platform this build does not know about. The pack is still
                // listed and still removable; only its logo and link are lost.
                source = null;
            }
        }
        LoaderType loader;
        try {
            loader = LoaderType.fromId(json.get("loader").asString(null));
        } catch (IllegalArgumentException e) {
            loader = LoaderType.VANILLA;
        }
        return new InstalledModpack(
                json.get("id").asString(""),
                json.get("name").asString(""),
                json.get("version").asString(null),
                json.get("author").asString(null),
                source,
                json.get("projectId").asString(null),
                json.get("iconUrl").asString(null),
                json.get("pageUrl").asString(null),
                json.get("minecraftVersion").asString(null),
                loader,
                json.get("loaderVersion").asString(null),
                paths,
                json.get("installedAt").asLong(0));
    }
}
