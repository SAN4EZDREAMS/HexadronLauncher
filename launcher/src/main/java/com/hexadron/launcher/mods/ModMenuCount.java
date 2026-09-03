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

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Makes Mod Menu count mods the way a player counts them.
 *
 * <h2>The number in the corner</h2>
 *
 * <p>Install eight mods and the title screen says sixty-eight. Nothing is
 * wrong with the folder - the eight jars are the eight jars - and nothing in
 * this launcher wrote that number. It is Mod Menu's, and Mod Menu counts
 * something else: every mod the loader has, which includes the roughly
 * fifty modules Fabric API is made of and every library jar-in-jarred inside
 * somebody else's mod. Its three counting options all default to on:
 *
 * <ul>
 *   <li>{@code count_libraries} - jars filed as libraries rather than mods;</li>
 *   <li>{@code count_hidden_mods} - the ones that ask not to be listed;</li>
 *   <li>{@code count_children} - the modules nested inside another mod, which
 *       is where Fabric API's fifty come from.</li>
 * </ul>
 *
 * <p>Turning the three off makes the number mean "mods I installed", which is
 * the only reading a player has for it. Nothing else changes: Mod Menu's list
 * still shows everything, and every mod still loads. It is a display setting.
 *
 * <h2>Why the launcher writes it</h2>
 *
 * <p>Because the launcher is what installed Fabric API, and the inflated number
 * is a direct consequence of that. Leaving it is leaving the player to discover
 * a setting they have no reason to look for, in a mod they did not ask for, to
 * explain a number they will reasonably read as a broken install.
 *
 * <h2>What it will not do</h2>
 *
 * <p>It writes a key only when the file does not already have one. A player who
 * has been into Mod Menu's settings and turned counting back on has said what
 * they want, and an installer that overrules that on every run is a bug of a
 * worse kind than the one it is fixing. Everything else in the file is read,
 * kept and written back untouched.
 */
public final class ModMenuCount {

    /** Mod Menu reads {@code <game dir>/config/modmenu.json}. */
    private static final String CONFIG_FILE = "modmenu.json";

    /** The three keys, in Mod Menu's own spelling: field names, lower-cased. */
    private static final List<String> COUNTING_KEYS =
            List.of("count_libraries", "count_hidden_mods", "count_children");

    private ModMenuCount() {
    }

    /**
     * True when one of these files is Mod Menu.
     *
     * <p>By file name, because that is what is known here for certain. A project
     * id would tie this class to one platform's identifier for a mod that is on
     * several, and the file Mod Menu publishes has been named
     * {@code modmenu-<version>.jar} throughout.
     */
    public static boolean isPresentAmong(Iterable<ModFile> files) {
        for (ModFile file : files) {
            String name = file.fileName();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith("modmenu")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the three counting keys into a profile's Mod Menu config.
     *
     * <p>Never throws: a mod count is not worth failing an install over, and a
     * config file that cannot be read is a file that belongs to the player.
     *
     * @param gameDirectory the profile's game directory, the parent of {@code config}
     * @return true when the file was changed
     */
    public static boolean applyTo(Path gameDirectory, Progress progress) {
        Path config = gameDirectory.resolve("config").resolve(CONFIG_FILE);
        try {
            Json settings = Files.isRegularFile(config) ? Json.read(config) : Json.object();
            if (!settings.isObject()) {
                // Something else is at that path. Not ours to replace.
                return false;
            }

            List<String> changed = new java.util.ArrayList<>();
            for (String key : COUNTING_KEYS) {
                if (settings.get(key).exists()) {
                    continue;
                }
                settings.put(key, false);
                changed.add(key);
            }
            if (changed.isEmpty()) {
                return false;
            }
            settings.write(config);
            progress.log("Set Mod Menu to count installed mods only (%s), so the title screen"
                    + " shows the mods you chose rather than every nested library",
                    String.join(", ", changed));
            return true;
        } catch (IOException | RuntimeException e) {
            progress.log("Could not adjust Mod Menu's mod count: %s", e.toString());
            return false;
        }
    }
}
