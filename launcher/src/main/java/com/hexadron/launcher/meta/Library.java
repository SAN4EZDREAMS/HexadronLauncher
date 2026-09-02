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
import com.hexadron.launcher.util.MavenCoordinate;
import com.hexadron.launcher.util.Platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry of a version JSON {@code libraries} array.
 *
 * <p>Three dialects must be understood, because a modded install merges all
 * three into one classpath:
 *
 * <ol>
 *   <li><b>Vanilla</b>: {@code downloads.artifact} carries path, url, sha1 and
 *       size; native libraries additionally use {@code natives} +
 *       {@code downloads.classifiers}. Modern versions (1.19+) instead publish
 *       natives as ordinary per-OS library entries gated by {@code rules}.</li>
 *   <li><b>Fabric / Quilt</b>: only {@code name} plus a {@code url} that is a
 *       <em>maven repository root</em>, not a file URL. The path is derived from
 *       the coordinate. Checksums are usually absent.</li>
 *   <li><b>Forge / NeoForge</b>: a mix of the two, plus entries with no source
 *       at all because the installer's processors generate the file locally.</li>
 * </ol>
 */
public final class Library {

    private final MavenCoordinate coordinate;
    private final String rawName;
    private final Artifact artifact;
    private final Map<String, Artifact> classifiers;
    private final Map<String, String> nativeClassifiers;
    private final List<Rule> rules;
    private final List<String> extractExcludes;
    private final String mavenRepositoryRoot;

    private Library(MavenCoordinate coordinate, String rawName, Artifact artifact,
                    Map<String, Artifact> classifiers, Map<String, String> nativeClassifiers,
                    List<Rule> rules, List<String> extractExcludes, String mavenRepositoryRoot) {
        this.coordinate = coordinate;
        this.rawName = rawName;
        this.artifact = artifact;
        this.classifiers = classifiers;
        this.nativeClassifiers = nativeClassifiers;
        this.rules = rules;
        this.extractExcludes = extractExcludes;
        this.mavenRepositoryRoot = mavenRepositoryRoot;
    }

    public static Library parse(Json json) {
        String name = json.get("name").asString(null);
        if (name == null) {
            throw new IllegalArgumentException("library entry without a name: " + json);
        }
        MavenCoordinate coordinate = MavenCoordinate.parse(name);

        Json downloads = json.get("downloads");
        Artifact artifact = Artifact.parse(downloads.get("artifact"));

        Map<String, Artifact> classifiers = new LinkedHashMap<>();
        Json classifiersJson = downloads.get("classifiers");
        if (classifiersJson.isObject()) {
            classifiersJson.fields().forEach((key, value) -> {
                Artifact parsed = Artifact.parse(value);
                if (parsed != null) {
                    classifiers.put(key, parsed);
                }
            });
        }

        Map<String, String> natives = new LinkedHashMap<>();
        Json nativesJson = json.get("natives");
        if (nativesJson.isObject()) {
            nativesJson.fields().forEach((os, value) -> {
                String classifier = value.asString(null);
                if (classifier != null) {
                    natives.put(os, classifier);
                }
            });
        }

        List<String> excludes = new ArrayList<>();
        Json extractJson = json.get("extract");
        if (extractJson.isObject()) {
            for (Json entry : extractJson.get("exclude").elements()) {
                String value = entry.asString(null);
                if (value != null) {
                    excludes.add(value);
                }
            }
        }

        // A top-level "url" in Fabric/Quilt/Forge metadata is a repository root.
        String repoRoot = json.get("url").asString(null);
        if (repoRoot != null && !repoRoot.isBlank() && !repoRoot.endsWith("/")) {
            repoRoot = repoRoot + "/";
        }

        // Fabric-style entries sometimes carry a flat sha1/size beside the name.
        if (artifact == null && repoRoot != null) {
            artifact = new Artifact(
                    coordinate.path(),
                    repoRoot + coordinate.path(),
                    json.get("sha1").asString(null),
                    json.get("size").asLong(-1));
        }

        return new Library(coordinate, name, artifact,
                Map.copyOf(classifiers), Map.copyOf(natives),
                Rule.parseList(json.get("rules")), List.copyOf(excludes), repoRoot);
    }

    // ---------------------------------------------------------------- accessors

    public MavenCoordinate coordinate() {
        return coordinate;
    }

    public String name() {
        return rawName;
    }

    public List<Rule> rules() {
        return rules;
    }

    public List<String> extractExcludes() {
        return extractExcludes;
    }

    public String mavenRepositoryRoot() {
        return mavenRepositoryRoot;
    }

    /** True when this library's rules permit it on the current host. */
    public boolean appliesToThisHost() {
        return Rule.allows(rules);
    }

    /**
     * True when this entry is a legacy native container - one that must be
     * unpacked into the natives directory rather than placed on the classpath.
     * Modern versions ship natives as plain classpath jars with OS rules and
     * return false here.
     */
    public boolean isLegacyNativeContainer() {
        return !nativeClassifiers.isEmpty();
    }

    /**
     * The classifier for this host's natives, with {@code ${arch}} substituted.
     * Null when this library has no native for this OS.
     */
    public String nativeClassifierForThisHost() {
        String classifier = nativeClassifiers.get(Platform.osName());
        if (classifier == null) {
            return null;
        }
        return classifier.replace("${arch}", Platform.archBits());
    }

    /**
     * The artifact that belongs on the classpath, or null when this entry only
     * contributes natives or is generated locally.
     */
    public Artifact classpathArtifact() {
        if (isLegacyNativeContainer()) {
            return null;
        }
        return artifact;
    }

    /** The native container to unpack for this host, or null when there is none. */
    public Artifact nativeArtifact() {
        String classifier = nativeClassifierForThisHost();
        if (classifier == null) {
            return null;
        }
        Artifact fromClassifiers = classifiers.get(classifier);
        if (fromClassifiers != null) {
            return fromClassifiers;
        }
        // No downloads block: derive from the coordinate plus the repo root.
        MavenCoordinate nativeCoordinate = coordinate.withClassifier(classifier);
        String path = nativeCoordinate.path();
        String url = mavenRepositoryRoot != null ? mavenRepositoryRoot + path : null;
        return new Artifact(path, url, null, -1);
    }

    /**
     * Repository-relative path of the classpath artifact, derived from the
     * coordinate when metadata omits it.
     */
    public String classpathPath() {
        Artifact a = classpathArtifact();
        if (a != null && a.path() != null) {
            return a.path();
        }
        return coordinate.path();
    }

    /** Identity used to deduplicate a merged library list. */
    public String dedupeKey() {
        return coordinate.dedupeKey();
    }

    @Override
    public String toString() {
        return rawName;
    }
}
