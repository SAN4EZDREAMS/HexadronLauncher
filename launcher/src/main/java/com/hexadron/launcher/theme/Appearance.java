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

import com.hexadron.launcher.json.Json;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * How the launcher looks: a colour theme with any colours changed on top of
 * it, a background picture, and the fonts.
 *
 * <p>Immutable, so the settings window can hold the one it opened with and
 * put it back on Cancel, whatever was previewed in between. Every value is
 * held to its range on the way in, so a hand-edited {@code launcher.json}
 * cannot ask for a font at a tenth of its size or a picture dimmed past black.
 */
public final class Appearance {

    /** How the background picture fills a window. */
    public enum Fit {
        /** Fills the window, cut at the edges if the shapes differ. */
        COVER("cover"),
        /** Whole picture, with bands of the window colour where the shapes differ. */
        CONTAIN("contain"),
        /** Stretched to the window, out of proportion. */
        STRETCH("stretch"),
        /** At its own size, in the middle. */
        CENTER("center"),
        /** Repeated. */
        TILE("tile");

        private final String id;

        Fit(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String labelKey() {
            return "appearance.background.fit." + id;
        }

        public static Fit byId(String id) {
            for (Fit fit : values()) {
                if (fit.id.equalsIgnoreCase(id == null ? "" : id.trim())) {
                    return fit;
                }
            }
            return COVER;
        }
    }

    public static final int DIM_MAX = 90;
    public static final int BLUR_MAX = 40;
    public static final int PANEL_OPACITY_MIN = 30;
    public static final int FONT_SCALE_MIN = 80;
    public static final int FONT_SCALE_MAX = 150;
    public static final int FONT_SCALE_STEP = 5;

    /** What a first run looks like: the launcher's own theme, no picture, the default fonts. */
    public static final Appearance DEFAULT = new Appearance(ThemePreset.HEXADRON,
            new EnumMap<>(Palette.Slot.class), "", Fit.COVER, 35, 0, 85, "", "", 100, true);

    private final ThemePreset preset;
    private final EnumMap<Palette.Slot, String> overrides;
    private final String background;
    private final Fit fit;
    private final int dim;
    private final int blur;
    private final int panelOpacity;
    private final String font;
    private final String monoFont;
    private final int fontScale;
    private final boolean pattern;

    private Appearance(ThemePreset preset, EnumMap<Palette.Slot, String> overrides, String background,
                       Fit fit, int dim, int blur, int panelOpacity, String font, String monoFont,
                       int fontScale, boolean pattern) {
        this.preset = preset == null ? ThemePreset.HEXADRON : preset;
        // Only colours that differ from the preset are kept: a theme that was
        // changed back by hand is the theme again, not a copy of it.
        EnumMap<Palette.Slot, String> kept = new EnumMap<>(Palette.Slot.class);
        overrides.forEach((slot, value) -> {
            String colour = Palette.parse(value);
            if (slot != null && colour != null && !colour.equals(this.preset.palette().get(slot))) {
                kept.put(slot, colour);
            }
        });
        this.overrides = kept;
        this.background = safeBackground(background);
        this.fit = fit == null ? Fit.COVER : fit;
        this.dim = Math.max(0, Math.min(DIM_MAX, dim));
        this.blur = Math.max(0, Math.min(BLUR_MAX, blur));
        this.panelOpacity = Math.max(PANEL_OPACITY_MIN, Math.min(100, panelOpacity));
        this.font = fontName(font);
        this.monoFont = fontName(monoFont);
        int scale = Math.round(fontScale / (float) FONT_SCALE_STEP) * FONT_SCALE_STEP;
        this.fontScale = Math.max(FONT_SCALE_MIN, Math.min(FONT_SCALE_MAX, scale));
        this.pattern = pattern;
    }

    /**
     * A font family name as the stylesheet can take it: no quotes, no
     * backslashes and nothing that ends a declaration, at most 100 characters.
     */
    public static String fontName(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("[\"'\\\\;{}<>\\p{Cntrl}]", "").trim();
        return clean.length() > 100 ? clean.substring(0, 100).trim() : clean;
    }

    // ---------------------------------------------------------------- reading

    public ThemePreset preset() {
        return preset;
    }

    /** The colours changed on top of the preset. */
    public Map<Palette.Slot, String> overrides() {
        return Collections.unmodifiableMap(overrides);
    }

    /** True when at least one colour differs from the preset. */
    public boolean isCustomised() {
        return !overrides.isEmpty();
    }

    /** The colours in effect: the preset's, with the changed ones over them. */
    public Palette palette() {
        return preset.palette().with(overrides);
    }

    /** The background picture, relative to the data folder; empty for none. */
    public String background() {
        return background;
    }

    public boolean hasBackground() {
        return !background.isEmpty();
    }

    public Fit fit() {
        return fit;
    }

    /** How far the picture is faded into the window colour, 0-90 %. */
    public int dim() {
        return dim;
    }

    /** Blur radius of the picture in pixels, 0-40. */
    public int blur() {
        return blur;
    }

    /** How opaque panels are over the picture, 30-100 %. */
    public int panelOpacity() {
        return panelOpacity;
    }

    /** The interface font family; empty for the launcher's own choice. */
    public String font() {
        return font;
    }

    /** The font for logs and other fixed-width text; empty for the launcher's own choice. */
    public String monoFont() {
        return monoFont;
    }

    /** True unless the player turned the theme's pattern off. */
    public boolean pattern() {
        return pattern;
    }

    /** True when the theme's pattern is drawn: the theme has one, it is on, and no picture replaces it. */
    public boolean showsPattern() {
        return pattern && preset.hasPattern() && !hasBackground();
    }

    /** Text size in percent of the launcher's own, 80-150 in steps of 5. */
    public int fontScale() {
        return fontScale;
    }

    // ---------------------------------------------------------------- changing

    /** Another theme, with the colour changes dropped: they belonged to the old one. */
    public Appearance withPreset(ThemePreset value) {
        return new Appearance(value, new EnumMap<>(Palette.Slot.class), background, fit, dim, blur,
                panelOpacity, font, monoFont, fontScale, pattern);
    }

    public Appearance withColor(Palette.Slot slot, String hex) {
        EnumMap<Palette.Slot, String> copy = new EnumMap<>(overrides);
        String colour = Palette.parse(hex);
        if (colour == null) {
            copy.remove(slot);
        } else {
            copy.put(slot, colour);
        }
        return new Appearance(preset, copy, background, fit, dim, blur, panelOpacity, font, monoFont, fontScale, pattern);
    }

    /** The theme's own colours again. */
    public Appearance withoutColorChanges() {
        return new Appearance(preset, new EnumMap<>(Palette.Slot.class), background, fit, dim, blur,
                panelOpacity, font, monoFont, fontScale, pattern);
    }

    public Appearance withBackground(String value) {
        return new Appearance(preset, overrides, value, fit, dim, blur, panelOpacity, font, monoFont, fontScale, pattern);
    }

    public Appearance withFit(Fit value) {
        return new Appearance(preset, overrides, background, value, dim, blur, panelOpacity, font, monoFont, fontScale, pattern);
    }

    public Appearance withDim(int value) {
        return new Appearance(preset, overrides, background, fit, value, blur, panelOpacity, font, monoFont, fontScale, pattern);
    }

    public Appearance withBlur(int value) {
        return new Appearance(preset, overrides, background, fit, dim, value, panelOpacity, font, monoFont, fontScale, pattern);
    }

    public Appearance withPanelOpacity(int value) {
        return new Appearance(preset, overrides, background, fit, dim, blur, value, font, monoFont, fontScale, pattern);
    }

    public Appearance withFont(String value) {
        return new Appearance(preset, overrides, background, fit, dim, blur, panelOpacity, value, monoFont, fontScale, pattern);
    }

    public Appearance withMonoFont(String value) {
        return new Appearance(preset, overrides, background, fit, dim, blur, panelOpacity, font, value, fontScale, pattern);
    }

    /** Whether the theme's own pattern is drawn when there is no picture. */
    public Appearance withPattern(boolean value) {
        return new Appearance(preset, overrides, background, fit, dim, blur, panelOpacity, font, monoFont,
                fontScale, value);
    }

    public Appearance withFontScale(int value) {
        return new Appearance(preset, overrides, background, fit, dim, blur, panelOpacity, font, monoFont, value, pattern);
    }

    // ---------------------------------------------------------------- json

    /** The {@code appearance} object of {@code launcher.json}. */
    public Json toJson() {
        Json colours = Json.object();
        overrides.forEach((slot, value) -> colours.put(slot.key(), value));
        return Json.object()
                .put("theme", preset.id())
                .put("colors", colours)
                .put("background", background)
                .put("backgroundFit", fit.id())
                .put("backgroundDim", dim)
                .put("backgroundBlur", blur)
                .put("panelOpacity", panelOpacity)
                .put("font", font)
                .put("monoFont", monoFont)
                .put("fontScale", fontScale)
                .put("themePattern", pattern);
    }

    /** Reads what {@link #toJson} wrote; anything missing or unusable is the default. */
    public static Appearance fromJson(Json json) {
        if (json == null || !json.isObject()) {
            return DEFAULT;
        }
        EnumMap<Palette.Slot, String> colours = new EnumMap<>(Palette.Slot.class);
        Json saved = json.get("colors");
        if (saved.isObject()) {
            saved.fields().forEach((key, value) -> {
                Palette.Slot slot = Palette.Slot.byKey(key);
                String colour = Palette.parse(value.asString(null));
                if (slot != null && colour != null) {
                    colours.put(slot, colour);
                }
            });
        }
        return new Appearance(ThemePreset.byId(json.get("theme").asString("")), colours,
                json.get("background").asString(""),
                Fit.byId(json.get("backgroundFit").asString("")),
                json.get("backgroundDim").asInt(DEFAULT.dim),
                json.get("backgroundBlur").asInt(DEFAULT.blur),
                json.get("panelOpacity").asInt(DEFAULT.panelOpacity),
                json.get("font").asString(""),
                json.get("monoFont").asString(""),
                json.get("fontScale").asInt(100),
                json.get("themePattern").asBool(true));
    }

    /**
     * Only a file the launcher put into its own {@code backgrounds} folder:
     * the path is joined to the data folder, and a saved {@code ../} or an
     * absolute path would point it anywhere on the disk.
     */
    static String safeBackground(String value) {
        if (value == null) {
            return "";
        }
        String path = value.trim().replace('\\', '/');
        return path.matches("backgrounds/[0-9a-f]{16}\\.(png|jpg|jpeg|gif|bmp)") ? path : "";
    }

    /** The picture types the launcher can read and copy in. */
    public static boolean isPictureName(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".bmp");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Appearance that && preset == that.preset && overrides.equals(that.overrides)
                && background.equals(that.background) && fit == that.fit && dim == that.dim
                && blur == that.blur && panelOpacity == that.panelOpacity && font.equals(that.font)
                && monoFont.equals(that.monoFont) && fontScale == that.fontScale
                && pattern == that.pattern;
    }

    @Override
    public int hashCode() {
        return Objects.hash(preset, overrides, background, fit, dim, blur, panelOpacity, font, monoFont, fontScale,
                pattern);
    }
}
