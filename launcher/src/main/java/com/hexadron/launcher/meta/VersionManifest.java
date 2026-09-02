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

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mojang's list of every published Minecraft version.
 *
 * <p>Source of truth for "all versions": {@code version_manifest_v2.json} lists
 * every release, snapshot, old_beta and old_alpha ever published, each with the
 * URL and SHA-1 of its own version JSON.
 */
public record VersionManifest(String latestRelease, String latestSnapshot, List<Entry> versions) {

    public static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** Version channels as Mojang labels them. */
    public enum Channel {
        RELEASE("release"),
        SNAPSHOT("snapshot"),
        OLD_BETA("old_beta"),
        OLD_ALPHA("old_alpha"),
        UNKNOWN("");

        private final String id;

        Channel(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Channel fromId(String id) {
            for (Channel c : values()) {
                if (c.id.equals(id)) {
                    return c;
                }
            }
            return UNKNOWN;
        }
    }

    /**
     * @param sha1 digest of the version JSON at {@code url}; verifying it is what
     *             stops a poisoned mirror from injecting a rogue mainClass
     */
    public record Entry(String id, Channel channel, String url, String sha1,
                        String releaseTime, String time) {

        public boolean isRelease() {
            return channel == Channel.RELEASE;
        }
    }

    public VersionManifest {
        versions = List.copyOf(versions);
    }

    public static VersionManifest parse(Json json) {
        String latestRelease = json.get("latest").get("release").asString(null);
        String latestSnapshot = json.get("latest").get("snapshot").asString(null);

        List<Entry> versions = new ArrayList<>();
        for (Json entry : json.get("versions").elements()) {
            String id = entry.get("id").asString(null);
            String url = entry.get("url").asString(null);
            if (id == null || url == null) {
                continue;
            }
            versions.add(new Entry(
                    id,
                    Channel.fromId(entry.get("type").asString("")),
                    url,
                    entry.get("sha1").asString(null),
                    entry.get("releaseTime").asString(null),
                    entry.get("time").asString(null)));
        }
        return new VersionManifest(latestRelease, latestSnapshot, versions);
    }

    /**
     * Fetches the manifest, caching it under {@code cache/version_manifest_v2.json}.
     * Falls back to the cached copy when the network is unavailable, so the
     * launcher stays usable offline for already-installed versions.
     */
    public static VersionManifest fetch(GameDirs dirs) throws IOException, InterruptedException {
        Path cacheFile = dirs.cache().resolve("version_manifest_v2.json");
        try {
            String body = Http.getString(MANIFEST_URL);
            VersionManifest manifest = parse(Json.parse(body));
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, body);
            return manifest;
        } catch (IOException | RuntimeException e) {
            if (Files.isRegularFile(cacheFile)) {
                return parse(Json.read(cacheFile));
            }
            throw e;
        }
    }

    public Optional<Entry> find(String versionId) {
        return versions.stream().filter(v -> v.id().equals(versionId)).findFirst();
    }

    public List<Entry> byChannel(Channel channel) {
        return versions.stream().filter(v -> v.channel() == channel).toList();
    }

    public List<Entry> releases() {
        return byChannel(Channel.RELEASE);
    }
}
