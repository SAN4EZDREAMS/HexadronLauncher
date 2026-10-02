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

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The twelve colours a theme is made of.
 *
 * <p>Each slot is one of the named colours at the top of {@code hexadron.css},
 * and the stylesheet refers to nothing else: every surface, line and word in
 * the launcher is one of these or is mixed from one of these. So a palette is
 * the whole of what a colour theme has to say, and twelve swatches in the
 * settings window are the whole of what somebody has to set to make one.
 *
 * <p>Colours are kept as {@code #rrggbb} in lower case. Anything else is
 * refused where it comes in ({@link #parse}), because a colour that reaches the
 * stylesheet as text is a colour that could also carry a semicolon.
 */
public final class Palette {

    /** The slots, in the order the settings window shows them. */
    public enum Slot {
        BACKGROUND("background", "-fx-base-0"),
        PANEL("panel", "-fx-base-1"),
        CONTROL("control", "-fx-base-2"),
        BORDER("border", "-fx-base-3"),
        TEXT("text", "-fx-text-0"),
        TEXT_MUTED("textMuted", "-fx-text-1"),
        ACCENT("accent", "-fx-accent-0"),
        ACCENT_HOVER("accentHover", "-fx-accent-1"),
        DANGER("danger", "-fx-danger-0"),
        WARNING("warning", "-fx-warning-0"),
        MODPACK("modpack", "-fx-modpack-0"),
        DATAPACK("datapack", "-fx-datapack-0");

        private final String key;
        private final String token;

        Slot(String key, String token) {
            this.key = key;
            this.token = token;
        }

        /** The name in {@code launcher.json} and in a theme file. */
        public String key() {
            return key;
        }

        /** The looked-up colour in the stylesheet. */
        public String token() {
            return token;
        }

        /** The translation key of the slot's name. */
        public String labelKey() {
            return "appearance.color." + key;
        }

        public static Slot byKey(String key) {
            for (Slot slot : values()) {
                if (slot.key.equals(key)) {
                    return slot;
                }
            }
            return null;
        }
    }

    private final EnumMap<Slot, String> colors;

    private Palette(EnumMap<Slot, String> colors) {
        this.colors = colors;
    }

    /**
     * A palette from twelve colours in slot order.
     *
     * @throws IllegalArgumentException when one is not {@code #rrggbb}
     */
    public static Palette of(String... hex) {
        if (hex.length != Slot.values().length) {
            throw new IllegalArgumentException("a palette has " + Slot.values().length + " colours");
        }
        EnumMap<Slot, String> map = new EnumMap<>(Slot.class);
        for (Slot slot : Slot.values()) {
            String value = parse(hex[slot.ordinal()]);
            if (value == null) {
                throw new IllegalArgumentException("not a colour: " + hex[slot.ordinal()]);
            }
            map.put(slot, value);
        }
        return new Palette(map);
    }

    /** {@code #rrggbb} in lower case, or null for anything else. {@code rrggbb} and {@code #rgb} are accepted. */
    public static String parse(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (!text.startsWith("#")) {
            text = "#" + text;
        }
        if (text.matches("#[0-9a-f]{3}")) {
            text = "#" + text.charAt(1) + text.charAt(1) + text.charAt(2) + text.charAt(2)
                    + text.charAt(3) + text.charAt(3);
        }
        return text.matches("#[0-9a-f]{6}") ? text : null;
    }

    public String get(Slot slot) {
        return colors.get(slot);
    }

    /** This palette with one colour changed; an unusable colour changes nothing. */
    public Palette with(Slot slot, String hex) {
        String value = parse(hex);
        if (value == null || value.equals(colors.get(slot))) {
            return this;
        }
        EnumMap<Slot, String> copy = new EnumMap<>(colors);
        copy.put(slot, value);
        return new Palette(copy);
    }

    /** This palette with every colour in {@code overrides} put over it. */
    public Palette with(Map<Slot, String> overrides) {
        Palette result = this;
        for (Map.Entry<Slot, String> entry : overrides.entrySet()) {
            result = result.with(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * True when the window colour is light. Decides which way the colours
     * that are mixed from the palette are mixed: towards white on a dark
     * window, towards black on a light one.
     */
    public boolean isLight() {
        return luminance(get(Slot.BACKGROUND)) > 0.45;
    }

    // ---------------------------------------------------------------- colour arithmetic

    /** Red, green and blue of {@code #rrggbb}, 0-255. */
    static int[] rgb(String hex) {
        int value = Integer.parseInt(hex.substring(1), 16);
        return new int[]{(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff};
    }

    static String hex(int r, int g, int b) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", clamp(r), clamp(g), clamp(b));
    }

    /** {@code #rrggbbaa}, which JavaFX's stylesheet accepts as a colour with opacity. */
    static String withAlpha(String hex, double alpha) {
        int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return hex + String.format(Locale.ROOT, "%02x", a);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** Relative luminance, as WCAG defines it. */
    public static double luminance(String hex) {
        int[] c = rgb(hex);
        double[] linear = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = c[i] / 255.0;
            linear[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }

    /** WCAG contrast ratio between two colours, 1 to 21. */
    public static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** {@code a} moved {@code amount} (0-1) of the way to {@code b}. */
    static String mix(String a, String b, double amount) {
        int[] x = rgb(a);
        int[] y = rgb(b);
        return hex((int) Math.round(x[0] + (y[0] - x[0]) * amount),
                (int) Math.round(x[1] + (y[1] - x[1]) * amount),
                (int) Math.round(x[2] + (y[2] - x[2]) * amount));
    }

    /**
     * {@code color}, moved towards white or black - whichever is further from
     * {@code background} - until it reads as text on that background (4.5:1),
     * or as far as it goes.
     */
    static String readableOn(String color, String background) {
        String target = luminance(background) > 0.45 ? "#000000" : "#ffffff";
        for (int step = 0; step <= 20; step++) {
            String candidate = mix(color, target, step * 0.05);
            if (contrast(candidate, background) >= 4.5) {
                return candidate;
            }
        }
        return target;
    }

    /** White, or a near-black, whichever is easier to read on a fill of {@code color}. */
    static String inkOn(String color) {
        String dark = "#1b1206";
        return contrast("#ffffff", color) >= 3.0 || contrast("#ffffff", color) >= contrast(dark, color)
                ? "#ffffff" : dark;
    }

    // ---------------------------------------------------------------- json

    public Json toJson() {
        Json json = Json.object();
        for (Slot slot : Slot.values()) {
            json.put(slot.key(), colors.get(slot));
        }
        return json;
    }

    /** The colours a json object names, over {@code base}; unknown keys and bad colours are skipped. */
    public static Palette fromJson(Json json, Palette base) {
        Palette result = base;
        if (json == null || !json.isObject()) {
            return result;
        }
        for (Slot slot : Slot.values()) {
            String value = parse(json.get(slot.key()).asString(null));
            if (value != null) {
                result = result.with(slot, value);
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Palette palette && colors.equals(palette.colors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(colors);
    }

    @Override
    public String toString() {
        return colors.toString();
    }
}
