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

package com.hexadron.launcher;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.jar.Manifest;

/**
 * Values that are put into a build rather than written in the source.
 *
 * <p>Right now there is one: the CurseForge API key.
 *
 * <p><b>Why it is not in the repository.</b> CurseForge issues one key per
 * application and its terms say the key is "non-transferable and may not be
 * shared with any third party". A key committed to a public repository is shared
 * with everyone who clones it, and a key in a public repository is also a key
 * that gets scraped and revoked. Two well-known launchers do commit theirs in
 * plain text and have been formally challenged over it; that is not a pattern
 * worth copying.
 *
 * <p><b>How the key gets in.</b> The release build reads {@code CURSEFORGE_API_KEY}
 * from the environment and writes it into the launcher jar's manifest, as
 * {@value #CURSEFORGE_API_KEY_ATTRIBUTE}. On CI that value comes from a
 * repository secret, which GitHub does not expose to builds of forks or to pull
 * requests from them. So:
 *
 * <ul>
 *   <li>an official release has a working CurseForge tab;</li>
 *   <li>a fork, a pull request or anyone's local {@code ./gradlew build} produces
 *       a launcher with an empty key, which compiles and runs and simply has no
 *       CurseForge in it;</li>
 *   <li>nothing in the source tree ever holds the key.</li>
 * </ul>
 *
 * <p><b>What this does not claim.</b> A manifest attribute is not a secret from
 * the person running the launcher - anyone can open the jar and read it. No
 * client-side key can be, whatever is done to it, and obfuscating one only
 * hides that fact. What this arrangement actually achieves is narrower and
 * worth having: the key is out of version control, out of every fork, and
 * replaceable in one place. A user who would rather use their own key can set
 * one in the launcher settings, and that always wins.
 *
 * <p>{@code -Dhexadron.curseforge.apikey=...} on the command line overrides the
 * manifest, which is how a developer runs against their own key without editing
 * a build file.
 */
public final class BuildConfig {

    /** System property that overrides the built-in key. */
    public static final String CURSEFORGE_API_KEY_PROPERTY = "hexadron.curseforge.apikey";

    /** Manifest attribute the build writes the key into. */
    public static final String CURSEFORGE_API_KEY_ATTRIBUTE = "Hexadron-CurseForge-Api-Key";

    /**
     * What the version is when the manifest cannot say.
     *
     * <p>Which is every development run: a build from a class directory has no
     * manifest to read. Kept in step with {@code version} in
     * launcher/build.gradle - it is the same number, and a mismatch shows up as
     * a splash screen claiming the wrong version in the IDE and the right one in
     * a release.
     */
    private static final String FALLBACK_VERSION = "0.9.5";

    private static final String CURSEFORGE_API_KEY = readCurseForgeApiKey();

    private static final String VERSION = readVersion();

    private BuildConfig() {
    }

    /** The built-in CurseForge key, or an empty string when this build has none. */
    public static String curseForgeApiKey() {
        return CURSEFORGE_API_KEY;
    }

    public static boolean hasCurseForgeApiKey() {
        return !CURSEFORGE_API_KEY.isEmpty();
    }

    /** The version this build reports, e.g. for the splash screen. */
    public static String version() {
        return VERSION;
    }

    private static String readVersion() {
        String fromManifest = manifestAttribute("Implementation-Version");
        return fromManifest.isEmpty() ? FALLBACK_VERSION : fromManifest;
    }

    private static String readCurseForgeApiKey() {
        String property = System.getProperty(CURSEFORGE_API_KEY_PROPERTY);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        return manifestAttribute(CURSEFORGE_API_KEY_ATTRIBUTE);
    }

    /**
     * Reads one attribute from the manifest of the jar this class came from.
     *
     * <p>Deliberately not {@code getClass().getClassLoader().getResource(
     * "META-INF/MANIFEST.MF")}: that returns the first manifest on the whole
     * classpath, which during development is some dependency's. Resolving it
     * relative to this class's own location is what makes the answer belong to
     * this jar.
     */
    private static String manifestAttribute(String name) {
        try {
            URL self = BuildConfig.class.getResource("BuildConfig.class");
            if (self == null || !"jar".equals(self.getProtocol())) {
                // Running from a class directory: there is no manifest, which is
                // the normal state of a development build.
                return "";
            }
            String location = self.toString();
            int separator = location.indexOf("!/");
            if (separator < 0) {
                return "";
            }
            URL manifestUrl = URI.create(location.substring(0, separator + 2)
                    + "META-INF/MANIFEST.MF").toURL();
            try (InputStream in = manifestUrl.openStream()) {
                String value = new Manifest(in).getMainAttributes().getValue(name);
                return value == null ? "" : value.trim();
            }
        } catch (Exception e) {
            // A build with no readable manifest is a build with no key. That is a
            // working launcher without CurseForge, not a failure to report.
            return "";
        }
    }
}
