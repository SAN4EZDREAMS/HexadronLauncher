package com.hexadron.launcher.install.loader;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.VersionInstaller;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft Forge.
 *
 * <p>Version discovery is implemented here. Installation is not yet: unlike
 * Fabric and Quilt, Forge does not publish a ready launcher profile. Its
 * installer jar carries an {@code install_profile.json} describing a chain of
 * <em>processors</em> - separate Java programs that must be executed locally to
 * deobfuscate and patch the client jar before the game can start. That
 * subsystem is the next milestone.
 */
public final class ForgeInstaller implements LoaderInstaller {

    private static final String MAVEN_ROOT = "https://maven.minecraftforge.net/";
    private static final String METADATA_URL =
            MAVEN_ROOT + "net/minecraftforge/forge/maven-metadata.xml";
    private static final String PROMOTIONS_URL =
            MAVEN_ROOT + "net/minecraftforge/forge/promotions_slim.json";

    @Override
    public LoaderType type() {
        return LoaderType.FORGE;
    }

    /**
     * Minecraft versions Forge has builds for.
     *
     * <p>Authoritative, and it needs no mapping table: a Forge build id is
     * literally {@code <minecraftVersion>-<forgeVersion>}, so the set of
     * supported Minecraft versions is the set of distinct prefixes.
     */
    @Override
    public SupportedVersions supportedMinecraftVersions() throws IOException, InterruptedException {
        java.util.LinkedHashSet<String> versions = new java.util.LinkedHashSet<>();
        for (String build : MavenVersionList.fetchNewestFirst(METADATA_URL)) {
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
            if (!build.startsWith(prefix)) {
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

    /** The build Forge marks "recommended" for this Minecraft version, or null. */
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

    /** Installer jar URL for a build, kept here so the processor work has a starting point. */
    public static String installerUrl(String minecraftVersion, String forgeVersion) {
        String build = minecraftVersion + "-" + forgeVersion;
        return MAVEN_ROOT + "net/minecraftforge/forge/" + build + "/forge-" + build + "-installer.jar";
    }

    @Override
    public String install(String minecraftVersion, LoaderVersion loaderVersion,
                          VersionInstaller installer, Progress progress) throws IOException {
        throw new IOException("""
                Forge installation is not implemented yet.

                Forge ships an installer jar whose install_profile.json defines processors \
                that must be executed locally to patch the client jar. That runner is the \
                next milestone. Fabric and Quilt install and launch today.

                Installer for this build: %s"""
                .formatted(installerUrl(minecraftVersion, loaderVersion.version())));
    }
}
