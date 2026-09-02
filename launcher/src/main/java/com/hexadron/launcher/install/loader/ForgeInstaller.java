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
import com.hexadron.launcher.install.loader.forge.ForgeStyleInstaller;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minecraft Forge.
 *
 * <p>Version discovery reads Forge's maven metadata, which is authoritative and
 * needs no mapping table: a Forge build id is literally
 * {@code <minecraftVersion>-<forgeVersion>}.
 *
 * <p>Installation is carried out by {@link ForgeStyleInstaller}, which runs the
 * processor chain the installer jar declares. Forge ships its changes to the
 * game as a binary diff against the vanilla client jar, and a patched Minecraft
 * jar may not be redistributed, so that patch has to be applied on the user's
 * own machine. That is why this is not a single download the way Fabric is.
 */
public final class ForgeInstaller implements LoaderInstaller {

    private static final String MAVEN_ROOT = "https://maven.minecraftforge.net/";

    /**
     * The other Forge host.
     *
     * <p>Not interchangeable with the maven one: {@code promotions_slim.json}
     * exists only here, and the same path on {@code maven.minecraftforge.net}
     * answers 404. Installer jars are on both, so it doubles as a mirror.
     */
    private static final String FILES_ROOT = "https://files.minecraftforge.net/";

    private static final String METADATA_URL =
            MAVEN_ROOT + "net/minecraftforge/forge/maven-metadata.xml";
    private static final String PROMOTIONS_URL =
            FILES_ROOT + "net/minecraftforge/forge/promotions_slim.json";

    /**
     * Builds that cannot be installed by anyone, ours or otherwise.
     *
     * <p>Every one of these ships an installer whose own metadata is malformed -
     * {@code 14.23.5.2851} writes {@code "data": []} where the format requires a
     * map, the rest are truncated archives. Offering them means offering a
     * failure, so they are left out of the picker instead. Every other launcher
     * that installs Forge keeps the same list.
     */
    private static final Set<String> BROKEN_BUILDS = Set.of(
            "1.12.2-14.23.5.2851",
            "1.6.1-8.9.0.749",
            "1.6.1-8.9.0.751",
            "1.6.4-9.11.1.960",
            "1.6.4-9.11.1.961",
            "1.6.4-9.11.1.963",
            "1.6.4-9.11.1.964");

    private final ForgeStyleInstaller engine = new ForgeStyleInstaller(LoaderType.FORGE);

    @Override
    public LoaderType type() {
        return LoaderType.FORGE;
    }

    /**
     * Minecraft versions Forge has builds for.
     *
     * <p>Authoritative, and it needs no mapping table: the set of supported
     * Minecraft versions is the set of distinct build-id prefixes.
     */
    @Override
    public SupportedVersions supportedMinecraftVersions() throws IOException, InterruptedException {
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        for (String build : MavenVersionList.fetchNewestFirst(METADATA_URL)) {
            if (BROKEN_BUILDS.contains(build)) {
                continue;
            }
            String minecraftVersion = minecraftVersionOf(build);
            if (minecraftVersion != null) {
                versions.add(minecraftVersion);
            }
        }
        return new SupportedVersions(List.copyOf(versions), true);
    }

    /** The Minecraft version a Forge build id targets, or null if it is malformed. */
    public static String minecraftVersionOf(String build) {
        int dash = build.indexOf('-');
        if (dash <= 0 || dash == build.length() - 1) {
            return null;
        }
        return build.substring(0, dash);
    }

    /** True when this build is known to be uninstallable. */
    public static boolean isBroken(String build) {
        return BROKEN_BUILDS.contains(build);
    }

    /**
     * Forge build ids are {@code <minecraftVersion>-<forgeVersion>}, so the
     * builds for a Minecraft version are exactly those with that prefix.
     */
    @Override
    public List<LoaderVersion> availableVersions(String minecraftVersion)
            throws IOException, InterruptedException {

        String prefix = minecraftVersion + "-";
        String recommended = recommendedBuild(minecraftVersion);

        List<LoaderVersion> versions = new ArrayList<>();
        for (String build : MavenVersionList.fetchNewestFirst(METADATA_URL)) {
            if (!build.startsWith(prefix) || BROKEN_BUILDS.contains(build)) {
                continue;
            }
            String forgeVersion = build.substring(prefix.length());
            versions.add(new LoaderVersion(
                    LoaderType.FORGE,
                    forgeVersion,
                    forgeVersion.equals(recommended),
                    minecraftVersion + "-forge-" + forgeVersion));
        }

        if (versions.isEmpty()) {
            throw new UnsupportedVersionException(LoaderType.FORGE, minecraftVersion);
        }
        return List.copyOf(versions);
    }

    /**
     * The build Forge marks "recommended" for this Minecraft version, or null.
     *
     * <p>Not every Minecraft version has a recommended build - several have only
     * a {@code -latest} entry - so the pair must not be assumed.
     */
    private String recommendedBuild(String minecraftVersion) {
        try {
            Json promotions = Http.getJson(PROMOTIONS_URL).get("promos");
            String recommended = promotions.get(minecraftVersion + "-recommended").asString(null);
            return recommended != null
                    ? recommended
                    : promotions.get(minecraftVersion + "-latest").asString(null);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** Installer jar URL on Forge's maven. */
    public static String installerUrl(String minecraftVersion, String forgeVersion) {
        return MAVEN_ROOT + installerPath(minecraftVersion + "-" + forgeVersion);
    }

    /** Both hosts that serve the installer, so one being down is not an outage. */
    public static List<String> installerUrls(String build) {
        String path = installerPath(build);
        return List.of(MAVEN_ROOT + path, FILES_ROOT + "maven/" + path);
    }

    private static String installerPath(String build) {
        return "net/minecraftforge/forge/" + build + "/forge-" + build + "-installer.jar";
    }

    @Override
    public String install(String minecraftVersion, LoaderVersion loaderVersion,
                          VersionInstaller installer, Progress progress)
            throws IOException, InterruptedException {

        String build = minecraftVersion + "-" + loaderVersion.version();
        if (BROKEN_BUILDS.contains(build)) {
            throw new IOException("Forge " + build + " cannot be installed by any launcher: "
                    + "its own installer metadata is malformed. Choose another build.");
        }
        return engine.install(minecraftVersion, loaderVersion, installerUrls(build),
                installer, progress);
    }
}
