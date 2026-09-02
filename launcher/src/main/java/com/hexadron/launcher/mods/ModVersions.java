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

package com.hexadron.launcher.mods;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading a mod's own version out of what the platform publishes.
 *
 * <h2>Why this is needed</h2>
 *
 * <p>Some mods stop being compatible with each other at a known version, and
 * the only way to decide before downloading is to look at the version that is
 * about to be downloaded. The case this was written for is Indium: it exists to
 * give Sodium the Fabric Rendering API, Sodium 0.6 has that built in, and
 * Indium is <em>incompatible</em> with 0.6 and newer. So the same set of mods
 * needs Indium on one Minecraft version and must not have it on another, and
 * neither the Minecraft version nor the mod list can answer that on its own.
 *
 * <h2>Why the Minecraft version is not the answer</h2>
 *
 * <p>It looks like one, and it is wrong. Sodium is backported: Minecraft 1.20.1
 * gets 0.5.13 and needs Indium, while 1.21.1 - which Indium still publishes
 * builds for - gets 0.8.13 and must not have it. A rule written in Minecraft
 * versions would install a mod that breaks the game, and would need editing
 * every time a backport appeared.
 *
 * <h2>The parsing, and its limits</h2>
 *
 * <p>Modrinth version numbers carry the Minecraft version in with the mod's
 * own - {@code mc1.20.1-0.5.13-fabric}, {@code sodium-fabric-0.6.13+mc1.21.1}.
 * Taking the first number in the string would return the Minecraft version, so
 * every {@code mc}-prefixed number is removed first and the first number left
 * is the mod's.
 *
 * <p>It can still fail on a version number shaped like nothing else, and it
 * says so by returning null rather than guessing. The caller decides what an
 * unknown version means; for Indium it means "do not install", because a
 * missing compatibility mod is a warning screen and a wrong one is a crash.
 */
public final class ModVersions {

    /** {@code mc1.20.1}, {@code +mc1.21.1} - the Minecraft version, not the mod's. */
    private static final Pattern MINECRAFT_TAG =
            Pattern.compile("\\+?\\bmc\\d+(?:\\.\\d+)+", Pattern.CASE_INSENSITIVE);

    /** The first {@code 1.2} or {@code 1.2.3} left once those are gone. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)+");

    private ModVersions() {
    }

    /**
     * The mod's own version.
     *
     * @param published what the platform calls the build - Modrinth's
     *                  {@code version_number}, or the name shown for it
     * @param fileName  the jar's name, used when the published one yields
     *                  nothing: {@code sodium-fabric-0.5.13+mc1.20.1.jar} still
     *                  carries the answer
     * @return the version, or null when neither string contains one
     */
    public static String of(String published, String fileName) {
        String found = extract(published);
        return found != null ? found : extract(fileName);
    }

    private static String extract(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = MINECRAFT_TAG.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
        Matcher matcher = NUMBER.matcher(cleaned);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * Whether {@code version} is older than {@code threshold}.
     *
     * <p>Compared part by part as numbers, so 0.10 is above 0.9 - which string
     * comparison gets backwards, and which is exactly the range these
     * comparisons live in.
     *
     * @return false when either is null or unparseable: an unknown version is
     *         never treated as older, so a caller using this to decide whether
     *         to add a compatibility mod does not add one on a guess
     */
    public static boolean isBelow(String version, String threshold) {
        if (version == null || threshold == null) {
            return false;
        }
        String[] left = version.split("\\.");
        String[] right = threshold.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long a = part(left, i);
            long b = part(right, i);
            if (a != b) {
                return a < b;
            }
        }
        return false;
    }

    private static long part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Long.parseLong(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
