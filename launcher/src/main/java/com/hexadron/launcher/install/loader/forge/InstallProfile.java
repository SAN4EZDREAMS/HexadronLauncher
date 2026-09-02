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

package com.hexadron.launcher.install.loader.forge;

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.util.MavenCoordinate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code install_profile.json} that every Forge and NeoForge installer jar
 * carries.
 *
 * <p>Two shapes exist, and a launcher must recognise both from the file itself
 * rather than from the Minecraft version, because the boundary moved several
 * times:
 *
 * <ul>
 *   <li><b>Legacy</b> - keys {@code install} and {@code versionInfo}. Used up to
 *       roughly Minecraft 1.12.2. {@code versionInfo} <em>is</em> a complete
 *       version manifest, so installation is "extract one jar, write that
 *       object out". There are no processors.</li>
 *   <li><b>Modern</b> - key {@code spec}. The version manifest is a separate
 *       entry in the jar, and the client jar has to be patched locally first by
 *       a chain of {@link ForgeProcessor}s.</li>
 * </ul>
 *
 * <p>The detection order is the same one Forge's own installer uses: the
 * presence of {@code spec} decides. Some 1.16 builds still write
 * {@code "spec": 0} while being modern in every other respect, so the spec
 * number must not be used to pick the era.
 */
public final class InstallProfile {

    public enum Era {
        /** {@code install} + {@code versionInfo}, no processors. */
        LEGACY,
        /** {@code spec}, separate version manifest, processor chain. */
        MODERN
    }

    /**
     * One entry of the {@code data} block: the same token has a different value
     * for a client install and a server install.
     */
    public record DataEntry(String client, String server) {

        public String forSide(String side) {
            return "server".equals(side) ? server : client;
        }
    }

    private final Era era;
    private final int spec;
    private final String minecraftVersion;
    private final String versionId;
    private final MavenCoordinate mainJar;
    private final String legacyJarEntry;
    private final String versionJsonEntry;
    private final Json legacyVersionInfo;
    private final List<Json> libraries;
    private final List<ForgeProcessor> processors;
    private final Map<String, DataEntry> data;

    private InstallProfile(Era era, int spec, String minecraftVersion, String versionId,
                           MavenCoordinate mainJar, String legacyJarEntry, String versionJsonEntry,
                           Json legacyVersionInfo, List<Json> libraries,
                           List<ForgeProcessor> processors, Map<String, DataEntry> data) {
        this.era = era;
        this.spec = spec;
        this.minecraftVersion = minecraftVersion;
        this.versionId = versionId;
        this.mainJar = mainJar;
        this.legacyJarEntry = legacyJarEntry;
        this.versionJsonEntry = versionJsonEntry;
        this.legacyVersionInfo = legacyVersionInfo;
        this.libraries = List.copyOf(libraries);
        this.processors = List.copyOf(processors);
        this.data = Map.copyOf(data);
    }

    /**
     * @throws IllegalArgumentException when the document is neither shape, which
     *         is the only honest answer: guessing produces an install that fails
     *         later, inside the game, for no visible reason
     */
    public static InstallProfile parse(Json json) {
        if (json.has("spec")) {
            return parseModern(json);
        }
        if (json.has("install") && json.has("versionInfo")) {
            return parseLegacy(json);
        }
        throw new IllegalArgumentException(
                "install_profile.json has neither 'spec' nor 'install'+'versionInfo', "
                + "so its format cannot be determined");
    }

    private static InstallProfile parseModern(Json json) {
        String path = json.get("path").asString(null);
        return new InstallProfile(
                Era.MODERN,
                json.get("spec").asInt(0),
                json.get("minecraft").asString(null),
                json.get("version").asString(null),
                path == null || path.isBlank() ? null : MavenCoordinate.parse(path),
                null,
                json.get("json").asString("/version.json"),
                null,
                json.get("libraries").elements(),
                parseProcessors(json.get("processors")),
                parseData(json.get("data")));
    }

    private static InstallProfile parseLegacy(Json json) {
        Json install = json.get("install");
        Json versionInfo = json.get("versionInfo");
        String path = install.get("path").asString(null);
        return new InstallProfile(
                Era.LEGACY,
                0,
                install.get("minecraft").asString(null),
                versionInfo.get("id").asString(install.get("target").asString(null)),
                path == null || path.isBlank() ? null : MavenCoordinate.parse(path),
                install.get("filePath").asString(null),
                null,
                versionInfo,
                versionInfo.get("libraries").elements(),
                List.of(),
                Map.of());
    }

    private static List<ForgeProcessor> parseProcessors(Json array) {
        List<ForgeProcessor> processors = new ArrayList<>();
        for (Json entry : array.elements()) {
            processors.add(ForgeProcessor.parse(entry));
        }
        return processors;
    }

    /**
     * Reads the {@code data} block.
     *
     * <p>Tolerates a non-object, because at least one shipped build is wrong:
     * Forge {@code 1.12.2-14.23.5.2851} writes {@code "data": []} where the
     * format requires a map. An empty map is the correct reading of "no data" and
     * keeps the rest of the profile usable.
     */
    private static Map<String, DataEntry> parseData(Json object) {
        Map<String, DataEntry> data = new LinkedHashMap<>();
        if (!object.isObject()) {
            return data;
        }
        object.fields().forEach((key, value) -> data.put(key, new DataEntry(
                value.get("client").asString(null),
                value.get("server").asString(null))));
        return data;
    }

    // ---------------------------------------------------------------- accessors

    public Era era() {
        return era;
    }

    /** Declared spec number. 0 for legacy profiles and for early modern ones. */
    public int spec() {
        return spec;
    }

    /** The vanilla version this build patches, as the installer states it. */
    public String minecraftVersion() {
        return minecraftVersion;
    }

    /** The version id the installer wants to create, or null when it names none. */
    public String versionId() {
        return versionId;
    }

    /** Maven coordinate of the loader's own jar, or null. */
    public MavenCoordinate mainJar() {
        return mainJar;
    }

    /**
     * Legacy only: the entry inside the installer jar holding the universal jar.
     * Blank in a few 1.11 builds, which is why it is checked rather than assumed.
     */
    public String legacyJarEntry() {
        return legacyJarEntry;
    }

    /** Modern only: the entry inside the installer jar holding the version manifest. */
    public String versionJsonEntry() {
        return versionJsonEntry;
    }

    /** Legacy only: the embedded version manifest. */
    public Json legacyVersionInfo() {
        return legacyVersionInfo;
    }

    /**
     * Library entries.
     *
     * <p>Modern: the extra libraries the <em>processors</em> need on their
     * classpath - not the game's libraries, which live in the separate version
     * manifest. Legacy: the game's libraries, because there is no separate
     * manifest to hold them.
     */
    public List<Json> libraries() {
        return libraries;
    }

    public List<ForgeProcessor> processors() {
        return processors;
    }

    public Map<String, DataEntry> data() {
        return data;
    }
}
