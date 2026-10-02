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

package com.hexadron.launcher.theme;

/**
 * The colour themes that come with the launcher.
 *
 * <p>Each is a whole {@link Palette}, so picking one is one click, and each is
 * a starting point: the colour swatches in the settings window change single
 * colours on top of the one picked, and "back to the theme" removes those
 * changes again.
 *
 * <p>The first is the launcher's own look, unchanged; the others keep its
 * meanings - green for the one action that matters, amber for "something
 * depends on this", red for "this is broken" - and change the surfaces around
 * them. The two light ones keep the status colours dark enough to read on
 * white.
 */
public enum ThemePreset {

    /** The launcher's own dark look. Its colours are the ones written in hexadron.css. */
    HEXADRON("hexadron", Palette.of(
            "#14161a", "#1b1e24", "#23272f", "#2f343d",
            "#e6e8ec", "#aab3c2",
            "#2d7d46", "#359152", "#b3403a", "#d8a13c", "#7a5cc4", "#2e83b8")),

    /** Light grey window, white panels. */
    LIGHT("light", Palette.of(
            "#eef0f3", "#ffffff", "#e4e7ec", "#cdd3db",
            "#1b1e24", "#4b5563",
            "#2d7d46", "#26703c", "#b73a32", "#b7791f", "#6b4fb8", "#2373a6")),

    /** True black, for OLED screens and dark rooms. */
    OLED("oled", Palette.of(
            "#000000", "#0a0b0d", "#16181c", "#272a30",
            "#e6e8ec", "#a3abb8",
            "#2d7d46", "#359152", "#b3403a", "#d8a13c", "#7a5cc4", "#2e83b8")),

    /** Dark blue, with a blue accent. */
    MIDNIGHT("midnight", Palette.of(
            "#0f1420", "#151c2b", "#1d2638", "#2b3650",
            "#e5e9f2", "#a7b1c6",
            "#2f6fd6", "#4a83e0", "#c0453f", "#d8a13c", "#8a63d2", "#2a9d8f")),

    /** Dark red-brown, with an orange accent. */
    NETHER("nether", Palette.of(
            "#170f0f", "#201514", "#2b1c1a", "#3e2825",
            "#f1e6e3", "#c4aca6",
            "#c2410c", "#d9541a", "#d23b3b", "#e0a83c", "#9b5cc4", "#3a8fb8")),

    /** Dark violet, with a purple accent. */
    END("end", Palette.of(
            "#13101a", "#1a1624", "#241e31", "#352c46",
            "#ece7f5", "#b4a9c9",
            "#7c4ddb", "#9066e3", "#c4434a", "#d8a13c", "#c45c9b", "#2e9ab8")),

    /** Warm light, like birch planks. */
    BIRCH("birch", Palette.of(
            "#f3ede3", "#fffaf2", "#e9e0d2", "#d6c9b5",
            "#2a241c", "#655a4b",
            "#3d7a3a", "#336a31", "#b0402f", "#a86b12", "#7451a8", "#2c6f96"));

    private final String id;
    private final Palette palette;

    ThemePreset(String id, Palette palette) {
        this.id = id;
        this.palette = palette;
    }

    /** The name in {@code launcher.json}. */
    public String id() {
        return id;
    }

    public Palette palette() {
        return palette;
    }

    /** The translation key of the theme's name. */
    public String labelKey() {
        return "appearance.preset." + id;
    }

    /** The preset with this id, or the launcher's own one. */
    public static ThemePreset byId(String id) {
        for (ThemePreset preset : values()) {
            if (preset.id.equals(id)) {
                return preset;
            }
        }
        return HEXADRON;
    }
}
