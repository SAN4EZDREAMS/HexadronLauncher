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

package com.hexadron.launcher.install.loader;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.VersionInstaller;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Installer for Fabric and Quilt.
 *
 * <p>Both projects expose the same shaped meta API and, crucially, both will
 * hand back a complete, ready-to-write launcher profile:
 * {@code /versions/loader/<mc>/<loader>/profile/json}. That response already
 * declares {@code inheritsFrom}, the loader's {@code mainClass} and every extra
 * library, so installation is "fetch JSON, write JSON" with no processors and
 * no jar rewriting - which is why these two loaders work on every Minecraft
 * version they publish for, immediately.
 */
public final class FabricLikeInstaller implements LoaderInstaller {

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String QUILT_META = "https://meta.quiltmc.org/v3";

    private final LoaderType type;
    private final String metaRoot;

    private FabricLikeInstaller(LoaderType type, String metaRoot) {
        this.type = type;
        this.metaRoot = metaRoot;
    }

    public static FabricLikeInstaller fabric() {
        return new FabricLikeInstaller(LoaderType.FABRIC, FABRIC_META);
    }

    public static FabricLikeInstaller quilt() {
        return new FabricLikeInstaller(LoaderType.QUILT, QUILT_META);
    }

    @Override
    public LoaderType type() {
        return type;
    }

    /**
     * Minecraft versions this loader publishes intermediary mappings for.
     *
     * <p>Authoritative: without intermediary there is nothing for the loader to
     * map against, so a version absent from {@code /versions/game} genuinely
     * cannot run Fabric or Quilt. This is the list, not a heuristic.
     */
    @Override
    public SupportedVersions supportedMinecraftVersions() throws IOException, InterruptedException {
        Json response = Http.getJson(metaRoot + "/versions/game");
        List<String> versions = new ArrayList<>(response.size());
        for (Json entry : response.elements()) {
            String version = entry.get("version").asString(null);
            if (version != null) {
                versions.add(version);
            }
        }
        return new SupportedVersions(versions, true);
    }

    @Override
    public List<LoaderVersion> availableVersions(String minecraftVersion)
            throws IOException, InterruptedException {

        String url = metaRoot + "/versions/loader/" + encode(minecraftVersion);
        Json response;
        try {
            response = Http.getJson(url);
        } catch (Http.HttpStatusException e) {
            // Both metas answer 404 for a Minecraft version they do not support.
            if (e.statusCode() == 404) {
                throw new UnsupportedVersionException(type, minecraftVersion);
            }
            throw e;
        }

        List<LoaderVersion> versions = new ArrayList<>(response.size());
        for (Json entry : response.elements()) {
            Json loader = entry.get("loader");
            String version = loader.get("version").asString(null);
            if (version == null) {
                continue;
            }
            versions.add(new LoaderVersion(
                    type,
                    version,
                    loader.get("stable").asBool(false),
                    versionId(minecraftVersion, version)));
        }

        if (versions.isEmpty()) {
            throw new UnsupportedVersionException(type, minecraftVersion);
        }
        // The meta API already returns newest first; preserve that order.
        return List.copyOf(versions);
    }

    @Override
    public String install(String minecraftVersion, LoaderVersion loaderVersion,
                          VersionInstaller installer, Progress progress)
            throws IOException, InterruptedException {

        progress.stage("Installing " + type.displayName() + " " + loaderVersion.version());

        String url = metaRoot + "/versions/loader/"
                + encode(minecraftVersion) + "/"
                + encode(loaderVersion.version()) + "/profile/json";

        Json profile = Http.getJson(url);

        String id = profile.get("id").asString(null);
        if (id == null) {
            throw new IOException(type.displayName() + " meta returned a profile with no id: " + url);
        }
        String declaredParent = profile.get("inheritsFrom").asString(null);
        if (declaredParent != null && !declaredParent.equals(minecraftVersion)) {
            // Not fatal, but worth surfacing: it means the meta service resolved
            // a different base than the one requested.
            progress.log("note: %s profile inherits from %s, not %s",
                    type.displayName(), declaredParent, minecraftVersion);
        }

        installer.writeVersionJson(id, profile);
        progress.log("Wrote version manifest %s", id);
        return id;
    }

    /**
     * The id these metas assign, mirrored here so the UI can show it before the
     * profile is fetched. The authoritative value is always the {@code id} in
     * the downloaded profile, which {@link #install} uses.
     */
    private String versionId(String minecraftVersion, String loaderVersion) {
        String prefix = type == LoaderType.QUILT ? "quilt-loader-" : "fabric-loader-";
        return prefix + loaderVersion + "-" + minecraftVersion;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
