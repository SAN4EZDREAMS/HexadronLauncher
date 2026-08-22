package com.hexadron.launcher.install.loader;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.VersionInstaller;
import com.hexadron.launcher.install.loader.forge.ForgeStyleInstaller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NeoForge.
 *
 * <p>NeoForge inherited Forge's installer format, so {@link ForgeStyleInstaller}
 * handles the install unchanged. What differs is version discovery, and it
 * differs in two awkward ways.
 *
 * <p><b>The build number does not repeat the Minecraft version.</b> It encodes
 * it, and the encoding changed when Minecraft moved to calendar versioning:
 * <ul>
 *   <li>Before that, a build was {@code major.minor.build} and the Minecraft
 *       version was {@code 1.major.minor} - {@code 21.1.248} targets
 *       {@code 1.21.1}, and {@code 21.0.167} targets {@code 1.21}, because a
 *       missing patch is written as {@code 0}.</li>
 *   <li>Since Minecraft {@code 26.x} a build is {@code major.minor.patch.build}
 *       and the first three parts <em>are</em> the Minecraft version, still with
 *       a missing patch written as {@code 0} - {@code 26.1.2.97} targets
 *       {@code 26.1.2}, and {@code 26.1.0.5-beta} targets {@code 26.1}.</li>
 * </ul>
 *
 * <p><b>There are two artifacts.</b> Everything from {@code 1.20.2} onwards is
 * published as {@code net.neoforged:neoforge}. Minecraft {@code 1.20.1} - the
 * version NeoForge started on, and still a popular one - lives in a frozen
 * {@code net.neoforged:forge} artifact whose version numbers are shaped like
 * Forge's. Both are read, or 1.20.1 would appear to have no NeoForge at all.
 */
public final class NeoForgeInstaller implements LoaderInstaller {

    private static final String MAVEN_ROOT = "https://maven.neoforged.net/releases/";

    private static final String METADATA_URL =
            MAVEN_ROOT + "net/neoforged/neoforge/maven-metadata.xml";

    /** Frozen since 2024, and the only place NeoForge for Minecraft 1.20.1 exists. */
    private static final String LEGACY_METADATA_URL =
            MAVEN_ROOT + "net/neoforged/forge/maven-metadata.xml";

    private static final Pattern LEGACY_MC_VERSION = Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern CALENDAR_MC_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern BUILD =
            Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-+].*)?$");

    /**
     * Versions that are published but cannot be fetched.
     *
     * <p>{@code 47.1.82} is a genuine mistake in the legacy artifact's metadata -
     * it is listed without the {@code 1.20.1-} prefix every other entry has, and
     * nothing exists at the path that implies. {@code 1.20.1-47.1.7} is listed
     * and returns 404.
     */
    private static final Set<String> BROKEN_BUILDS = Set.of("47.1.82", "1.20.1-47.1.7");

    private final ForgeStyleInstaller engine = new ForgeStyleInstaller(LoaderType.NEOFORGE);

    @Override
    public LoaderType type() {
        return LoaderType.NEOFORGE;
    }

    /**
     * Minecraft versions derived from NeoForge build numbers.
     *
     * <p>Reported as incomplete on purpose. Both encodings above are documented
     * and implemented, but the scheme has already changed once, and the cost of
     * the two answers is not symmetric: an over-long list offers a version whose
     * build list then turns out to be empty, which says so plainly, while a
     * short list hides a version that does work and gives the user nothing to
     * read. So this is used to sort and to suggest, never to filter.
     */
    @Override
    public SupportedVersions supportedMinecraftVersions() throws IOException, InterruptedException {
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        for (String build : allBuilds()) {
            String minecraftVersion = minecraftVersionOf(build);
            if (minecraftVersion != null) {
                versions.add(minecraftVersion);
            }
        }
        return new SupportedVersions(List.copyOf(versions), false);
    }

    /**
     * The Minecraft version a NeoForge build targets, under either encoding.
     *
     * @return null when the build follows neither, which includes the snapshot
     *         builds numbered {@code 0.x}
     */
    public static String minecraftVersionOf(String build) {
        String value = build == null ? "" : build.trim();
        if (value.isEmpty()) {
            return null;
        }

        // The legacy artifact repeats the Minecraft version, Forge-style.
        if (value.startsWith("1.")) {
            int dash = value.indexOf('-');
            return dash > 0 ? value.substring(0, dash) : null;
        }

        Matcher matcher = BUILD.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        int major = Integer.parseInt(matcher.group(1));
        if (major == 0) {
            // Snapshot builds. They target a Minecraft snapshot, which the
            // release manifest does not list, so there is nothing to map to.
            return null;
        }
        if (major >= 26) {
            return "0".equals(matcher.group(3))
                    ? matcher.group(1) + "." + matcher.group(2)
                    : matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
        }
        return "0".equals(matcher.group(2))
                ? "1." + matcher.group(1)
                : "1." + matcher.group(1) + "." + matcher.group(2);
    }

    /**
     * The build-number prefix that selects the builds for a Minecraft version.
     *
     * <p>The exact inverse of {@link #minecraftVersionOf}. If the two ever
     * disagree the picker offers a version and then hides every build for it,
     * which is why the round trip is asserted in the self-check.
     *
     * @return null when the version follows no known scheme
     */
    public static String prefixFor(String minecraftVersion) {
        String value = minecraftVersion == null ? "" : minecraftVersion.trim();

        Matcher legacy = LEGACY_MC_VERSION.matcher(value);
        if (legacy.matches()) {
            String patch = legacy.group(2) == null ? "0" : legacy.group(2);
            return legacy.group(1) + "." + patch + ".";
        }

        Matcher calendar = CALENDAR_MC_VERSION.matcher(value);
        if (calendar.matches() && Integer.parseInt(calendar.group(1)) >= 26) {
            String patch = calendar.group(3) == null ? "0" : calendar.group(3);
            return calendar.group(1) + "." + calendar.group(2) + "." + patch + ".";
        }
        return null;
    }

    @Override
    public List<LoaderVersion> availableVersions(String minecraftVersion)
            throws IOException, InterruptedException {

        String prefix = prefixFor(minecraftVersion);

        List<LoaderVersion> versions = new ArrayList<>();
        for (String build : allBuilds()) {
            if (BROKEN_BUILDS.contains(build)) {
                continue;
            }
            // Two ways to belong to this Minecraft version: the legacy artifact
            // names it outright, the modern one encodes it in the prefix.
            boolean matches = minecraftVersion.equals(minecraftVersionOf(build))
                    || (prefix != null && build.startsWith(prefix));
            if (!matches) {
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
     * Every published build, newest first, from both artifacts.
     *
     * <p>The legacy list being unreachable is not fatal: it only carries 1.20.1,
     * and losing it must not take every other version with it.
     */
    private List<String> allBuilds() throws IOException, InterruptedException {
        List<String> builds = new ArrayList<>(MavenVersionList.fetchNewestFirst(METADATA_URL));
        try {
            builds.addAll(MavenVersionList.fetchNewestFirst(LEGACY_METADATA_URL));
        } catch (IOException | RuntimeException ignored) {
            // Frozen artifact, 1.20.1 only. The modern list still stands.
        }
        return builds;
    }

    /** The artifact a build belongs to decides its installer URL. */
    public static List<String> installerUrls(String neoForgeVersion) {
        if (neoForgeVersion.startsWith("1.")) {
            return List.of(MAVEN_ROOT + "net/neoforged/forge/" + neoForgeVersion
                    + "/forge-" + neoForgeVersion + "-installer.jar");
        }
        return List.of(MAVEN_ROOT + "net/neoforged/neoforge/" + neoForgeVersion
                + "/neoforge-" + neoForgeVersion + "-installer.jar");
    }

    /** Installer jar URL for a build. */
    public static String installerUrl(String neoForgeVersion) {
        return installerUrls(neoForgeVersion).get(0);
    }

    @Override
    public String install(String minecraftVersion, LoaderVersion loaderVersion,
                          VersionInstaller installer, Progress progress)
            throws IOException, InterruptedException {

        String build = loaderVersion.version();
        if (BROKEN_BUILDS.contains(build)) {
            throw new IOException("NeoForge " + build + " is listed in the repository metadata "
                    + "but no installer exists for it. Choose another build.");
        }
        return engine.install(minecraftVersion, loaderVersion, installerUrls(build),
                installer, progress);
    }
}
