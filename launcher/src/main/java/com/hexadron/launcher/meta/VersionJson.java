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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed Minecraft version manifest - either a vanilla one from piston-meta
 * or a mod loader one that declares {@code inheritsFrom}.
 *
 * <p>Instances are immutable. {@link #merge(VersionJson, VersionJson)} produces
 * the flattened form used for launching.
 */
public final class VersionJson {

    /** {@code assetIndex} block: which asset index this version uses. */
    public record AssetIndexInfo(String id, String url, String sha1, long size, long totalSize) {
        public static AssetIndexInfo parse(Json json) {
            if (!json.isObject()) {
                return null;
            }
            return new AssetIndexInfo(
                    json.get("id").asString(null),
                    json.get("url").asString(null),
                    json.get("sha1").asString(null),
                    json.get("size").asLong(-1),
                    json.get("totalSize").asLong(-1));
        }
    }

    /** {@code javaVersion} block: which Java runtime Mojang ships for this version. */
    public record JavaVersionInfo(String component, int majorVersion) {
        public static JavaVersionInfo parse(Json json) {
            if (!json.isObject()) {
                return null;
            }
            return new JavaVersionInfo(
                    json.get("component").asString("jre-legacy"),
                    json.get("majorVersion").asInt(8));
        }
    }

    private final String id;
    private final String inheritsFrom;
    private final String jarVersionId;
    private final String mainClass;
    private final String type;
    private final String releaseTime;
    private final String assetsId;
    private final AssetIndexInfo assetIndex;
    private final JavaVersionInfo javaVersion;
    private final Map<String, Artifact> downloads;
    private final List<Library> libraries;

    /**
     * Game arguments from {@code minecraftArguments}, the pre-1.13 form.
     *
     * <p>Kept apart from the modern ones because the two fields merge by
     * <em>opposite</em> rules, and mixing them up produces every argument twice.
     * See {@link #merge}.
     */
    private final List<Argument> legacyGameArguments;

    /** Game arguments from {@code arguments.game}, the 1.13-and-later form. */
    private final List<Argument> modernGameArguments;

    private final List<Argument> gameArguments;
    private final List<Argument> jvmArguments;
    private final Json logging;
    private final int complianceLevel;
    private final Json raw;

    private VersionJson(String id, String inheritsFrom, String jarVersionId, String mainClass, String type, String releaseTime,
                        String assetsId, AssetIndexInfo assetIndex, JavaVersionInfo javaVersion,
                        Map<String, Artifact> downloads, List<Library> libraries,
                        List<Argument> legacyGameArguments, List<Argument> modernGameArguments,
                        List<Argument> jvmArguments,
                        Json logging, int complianceLevel, Json raw) {
        this.id = id;
        this.inheritsFrom = inheritsFrom;
        this.jarVersionId = jarVersionId;
        this.mainClass = mainClass;
        this.type = type;
        this.releaseTime = releaseTime;
        this.assetsId = assetsId;
        this.assetIndex = assetIndex;
        this.javaVersion = javaVersion;
        this.downloads = downloads;
        this.libraries = libraries;
        this.legacyGameArguments = List.copyOf(legacyGameArguments);
        this.modernGameArguments = List.copyOf(modernGameArguments);
        // The order matters: the legacy string is the whole vanilla argument
        // line, so it comes first and anything the modern block adds follows it.
        List<Argument> allGame = new ArrayList<>(this.legacyGameArguments);
        allGame.addAll(this.modernGameArguments);
        this.gameArguments = List.copyOf(allGame);
        this.jvmArguments = jvmArguments;
        this.logging = logging;
        this.complianceLevel = complianceLevel;
        this.raw = raw;
    }

    /**
     * Default JVM arguments for versions predating the {@code arguments} block
     * (Minecraft 1.12.2 and older). Without these, legacy versions launch with
     * no native path and no classpath.
     */
    private static final List<Argument> LEGACY_JVM_ARGUMENTS = List.of(
            Argument.of("-Djava.library.path=${natives_directory}"),
            Argument.of("-Dminecraft.launcher.brand=${launcher_name}"),
            Argument.of("-Dminecraft.launcher.version=${launcher_version}"),
            Argument.of("-cp", "${classpath}"));

    public static VersionJson parse(Json json) {
        String id = json.get("id").asString(null);
        if (id == null) {
            throw new IllegalArgumentException("version JSON has no \"id\"");
        }

        Map<String, Artifact> downloads = new LinkedHashMap<>();
        Json downloadsJson = json.get("downloads");
        if (downloadsJson.isObject()) {
            downloadsJson.fields().forEach((key, value) -> {
                Artifact parsed = Artifact.parse(value);
                if (parsed != null) {
                    downloads.put(key, parsed);
                }
            });
        }

        List<Library> libraries = new ArrayList<>();
        for (Json entry : json.get("libraries").elements()) {
            libraries.add(Library.parse(entry));
        }

        List<Argument> legacyGameArguments;
        List<Argument> modernGameArguments;
        List<Argument> jvmArguments;
        Json argumentsJson = json.get("arguments");
        if (argumentsJson.isObject()) {
            legacyGameArguments = List.of();
            modernGameArguments = Argument.parseList(argumentsJson.get("game"));
            jvmArguments = Argument.parseList(argumentsJson.get("jvm"));
        } else {
            legacyGameArguments = Argument.parseLegacy(json.get("minecraftArguments").asString(null));
            modernGameArguments = List.of();
            jvmArguments = List.of();
        }

        return new VersionJson(
                id,
                json.get("inheritsFrom").asString(null),
                json.get("jar").asString(null),
                json.get("mainClass").asString(null),
                json.get("type").asString("release"),
                json.get("releaseTime").asString(null),
                json.get("assets").asString(null),
                AssetIndexInfo.parse(json.get("assetIndex")),
                JavaVersionInfo.parse(json.get("javaVersion")),
                Map.copyOf(downloads),
                List.copyOf(libraries),
                legacyGameArguments,
                modernGameArguments,
                jvmArguments,
                json.get("logging"),
                json.get("complianceLevel").asInt(0),
                json);
    }

    // ---------------------------------------------------------------- accessors

    public String id() {
        return id;
    }

    public String inheritsFrom() {
        return inheritsFrom;
    }

    public boolean hasParent() {
        return inheritsFrom != null && !inheritsFrom.isBlank();
    }

    /**
     * The version id whose client jar this version runs against.
     *
     * <p>An explicit {@code "jar"} field wins (some loader and custom manifests
     * set it); otherwise the id of whichever manifest in the chain actually
     * published a client download, which {@link #merge} propagates.
     */
    public String jarVersionId() {
        return jarVersionId != null ? jarVersionId : id;
    }

    public boolean hasExplicitJar() {
        return jarVersionId != null;
    }

    public String mainClass() {
        return mainClass;
    }

    public String type() {
        return type;
    }

    public String releaseTime() {
        return releaseTime;
    }

    /** Asset index id; falls back to the {@code assetIndex.id} then to "legacy". */
    public String assetsId() {
        if (assetsId != null) {
            return assetsId;
        }
        if (assetIndex != null && assetIndex.id() != null) {
            return assetIndex.id();
        }
        return "legacy";
    }

    public AssetIndexInfo assetIndex() {
        return assetIndex;
    }

    public JavaVersionInfo javaVersion() {
        return javaVersion;
    }

    /** Required Java major version; 8 when the metadata predates the field. */
    public int requiredJavaMajor() {
        return javaVersion == null ? 8 : javaVersion.majorVersion();
    }

    public Map<String, Artifact> downloads() {
        return downloads;
    }

    public Artifact clientDownload() {
        return downloads.get("client");
    }

    public List<Library> libraries() {
        return libraries;
    }

    public List<Argument> gameArguments() {
        return gameArguments;
    }

    /** JVM arguments, substituting the legacy defaults when the version has none. */
    public List<Argument> jvmArguments() {
        return jvmArguments.isEmpty() ? LEGACY_JVM_ARGUMENTS : jvmArguments;
    }

    public Json logging() {
        return logging;
    }

    public int complianceLevel() {
        return complianceLevel;
    }

    public Json raw() {
        return raw;
    }

    // ---------------------------------------------------------------- merging

    /**
     * Flattens a child (mod loader) manifest onto its parent (vanilla).
     *
     * <p>Rules, matching the official launcher and every established third-party one:
     * <ul>
     *   <li>Scalars come from the child when present, otherwise the parent.
     *       {@code mainClass} in particular is how Fabric and Forge take over
     *       the boot sequence.</li>
     *   <li>Libraries: <b>child first</b>, then parent, deduplicated by
     *       group:artifact:classifier keeping the first occurrence. This is what
     *       lets Fabric override the ASM and Guava versions vanilla ships;
     *       reversing the order produces a boot classpath that silently uses the
     *       wrong ASM and fails deep inside mixin.</li>
     *   <li>Arguments: it depends on which of the two forms they are written in,
     *       and the two rules are opposites.
     *       <ul>
     *         <li><b>{@code arguments.game} and {@code arguments.jvm}</b> (1.13
     *             and later) are arrays, and the child's are <b>appended</b> to
     *             the parent's. The child adds {@code -DFabricMcEmu=...} and
     *             friends on top of the vanilla set.</li>
     *         <li><b>{@code minecraftArguments}</b> (pre-1.13) is a single
     *             string, and the child's <b>replaces</b> the parent's. A loader
     *             writing this field writes the whole line, vanilla arguments
     *             included, with its own {@code --tweakClass} added. Appending it
     *             hands the game every argument twice, and LaunchWrapper stops
     *             with {@code MultipleArgumentsForOptionException: Found multiple
     *             arguments for option gameDir}.</li>
     *       </ul></li>
     * </ul>
     *
     * @param child  the manifest that declared {@code inheritsFrom}
     * @param parent the manifest it inherits from
     */
    public static VersionJson merge(VersionJson child, VersionJson parent) {
        List<Library> mergedLibraries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Library library : child.libraries) {
            if (seen.add(library.dedupeKey())) {
                mergedLibraries.add(library);
            }
        }
        for (Library library : parent.libraries) {
            if (seen.add(library.dedupeKey())) {
                mergedLibraries.add(library);
            }
        }

        // Replace, not append: see the note on this method.
        List<Argument> mergedLegacyGame = child.legacyGameArguments.isEmpty()
                ? parent.legacyGameArguments
                : child.legacyGameArguments;

        List<Argument> mergedModernGame = new ArrayList<>(parent.modernGameArguments);
        mergedModernGame.addAll(child.modernGameArguments);

        List<Argument> mergedJvm = new ArrayList<>(
                parent.jvmArguments.isEmpty() ? LEGACY_JVM_ARGUMENTS : parent.jvmArguments);
        mergedJvm.addAll(child.jvmArguments);

        Map<String, Artifact> mergedDownloads = new LinkedHashMap<>(parent.downloads);
        mergedDownloads.putAll(child.downloads);

        // The client jar comes from whichever manifest published a client
        // download - normally the vanilla parent. A loader manifest that sets
        // "jar" explicitly still wins.
        String mergedJar = child.jarVersionId != null
                ? child.jarVersionId
                : (parent.jarVersionId != null ? parent.jarVersionId
                        : (parent.downloads.containsKey("client") ? parent.id : child.id));

        return new VersionJson(
                child.id,
                null, // fully resolved
                mergedJar,
                child.mainClass != null ? child.mainClass : parent.mainClass,
                child.type != null ? child.type : parent.type,
                child.releaseTime != null ? child.releaseTime : parent.releaseTime,
                child.assetsId != null ? child.assetsId : parent.assetsId,
                child.assetIndex != null ? child.assetIndex : parent.assetIndex,
                child.javaVersion != null ? child.javaVersion : parent.javaVersion,
                Map.copyOf(mergedDownloads),
                List.copyOf(mergedLibraries),
                List.copyOf(mergedLegacyGame),
                List.copyOf(mergedModernGame),
                List.copyOf(mergedJvm),
                child.logging.exists() ? child.logging : parent.logging,
                Math.max(child.complianceLevel, parent.complianceLevel),
                child.raw);
    }

    @Override
    public String toString() {
        return "VersionJson[" + id + (hasParent() ? " inherits " + inheritsFrom : "")
                + ", " + libraries.size() + " libraries]";
    }
}
