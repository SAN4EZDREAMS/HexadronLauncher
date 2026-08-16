package com.hexadron.launcher.install.loader;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.VersionInstaller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NeoForge.
 *
 * <p>Version discovery is implemented here. Installation is not yet, for the
 * same reason as {@link ForgeInstaller}: NeoForge inherited Forge's
 * installer-with-processors design and publishes no ready launcher profile.
 *
 * <p>NeoForge build numbers do not repeat the Minecraft version. For the
 * {@code 1.x.y} era the convention is {@code x.y.z} - NeoForge {@code 21.1.66}
 * targets Minecraft {@code 1.21.1}. That mapping is applied when the requested
 * Minecraft version matches the old {@code 1.x[.y]} shape. Minecraft's 2026
 * switch to calendar versioning ({@code 26.2}) has no documented NeoForge
 * mapping that this code can rely on, so for those versions every published
 * build is offered rather than guessing a filter and silently hiding the
 * correct one.
 */
public final class NeoForgeInstaller implements LoaderInstaller {

    private static final String MAVEN_ROOT = "https://maven.neoforged.net/releases/";
    private static final String METADATA_URL =
            MAVEN_ROOT + "net/neoforged/neoforge/maven-metadata.xml";

    private static final Pattern LEGACY_MC_VERSION = Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?$");

    @Override
    public LoaderType type() {
        return LoaderType.NEOFORGE;
    }

    @Override
    public List<LoaderVersion> availableVersions(String minecraftVersion)
            throws IOException, InterruptedException {

        String prefix = legacyPrefixFor(minecraftVersion);
        List<String> builds = MavenVersionList.fetchNewestFirst(METADATA_URL);

        List<LoaderVersion> versions = new ArrayList<>();
        for (String build : builds) {
            if (prefix != null && !build.startsWith(prefix)) {
                continue;
            }
            versions.add(new LoaderVersion(
                    LoaderType.NEOFORGE,
                    build,
                    !build.contains("beta") && !build.contains("alpha"),
                    "neoforge-" + build));
        }

        if (versions.isEmpty()) {
            throw new UnsupportedVersionException(LoaderType.NEOFORGE, minecraftVersion);
        }
        return List.copyOf(versions);
    }

    /**
     * The build-number prefix for a {@code 1.x.y} Minecraft version, or null when
     * the version does not follow that scheme and no filter can be justified.
     */
    static String legacyPrefixFor(String minecraftVersion) {
        Matcher matcher = LEGACY_MC_VERSION.matcher(minecraftVersion.trim());
        if (!matcher.matches()) {
            return null;
        }
        String minor = matcher.group(1);
        String patch = matcher.group(2) == null ? "0" : matcher.group(2);
        return minor + "." + patch + ".";
    }

    /** Installer jar URL for a build. */
    public static String installerUrl(String neoForgeVersion) {
        return MAVEN_ROOT + "net/neoforged/neoforge/" + neoForgeVersion
                + "/neoforge-" + neoForgeVersion + "-installer.jar";
    }

    @Override
    public String install(String minecraftVersion, LoaderVersion loaderVersion,
                          VersionInstaller installer, Progress progress) throws IOException {
        throw new IOException("""
                NeoForge installation is not implemented yet.

                NeoForge uses Forge's installer format: install_profile.json defines \
                processors that must be executed locally to patch the client jar. That \
                runner is the next milestone. Fabric and Quilt install and launch today.

                Installer for this build: %s"""
                .formatted(installerUrl(loaderVersion.version())));
    }
}
