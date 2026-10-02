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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the stylesheet for an {@link Appearance}.
 *
 * <p>Nothing is drawn differently by code: the launcher's own stylesheet is
 * taken as it is, and the theme is expressed as text around it.
 *
 * <ol>
 *   <li>Every {@code -fx-font-size} given in pixels is multiplied by the text
 *       size. The stylesheet sizes all its text in pixels, so this one pass
 *       scales every heading, note and badge together, and nothing in it has to
 *       know there is such a setting.</li>
 *   <li>On a light palette, {@code hexadron-light.css} goes after it: the few
 *       rules that mix a colour towards black, mixed towards white.</li>
 *   <li>A {@code .root} block goes last. It defines the twelve colours and the
 *       ones mixed from them, the fonts, and modena's own base colours, so the
 *       parts of a control the stylesheet never mentions follow the theme
 *       too.</li>
 *   <li>With a background picture, a {@code .root.hx-backdrop} block puts it on
 *       every window root marked with that class, and makes the panels inside
 *       translucent so it shows through. Popup menus and tooltips have roots of
 *       their own without the class, so they stay solid and readable.</li>
 * </ol>
 *
 * <p>Plain text in, plain text out, so all of it can be checked without a
 * screen.
 */
public final class ThemeCss {

    private ThemeCss() {
    }

    /** The class {@code Theme} puts on window roots that may carry the picture. */
    public static final String BACKDROP_CLASS = "hx-backdrop";

    private static final Pattern FONT_SIZE =
            Pattern.compile("(-fx-font-size\\s*:\\s*)([0-9]+(?:\\.[0-9]+)?)px");

    private static final Pattern MONO_FAMILY =
            Pattern.compile("-fx-font-family\\s*:\\s*\"Consolas\"[^;]*;");

    /** The panel rules that become translucent over a picture. */
    static final String[] PANELS = {
            ".header", ".sidebar", ".footer", ".summary", ".detail-icon", ".kind-rail",
            ".skin-viewer", ".titled-pane > .title", ".titled-pane > *.content",
            ".dialog-pane .header-panel", ".dialog-warning", ".update-notes",
            ".update-scroll > .viewport", ".update-blocked", ".about-logo", ".category-clear",
            ".cleanup-tile", ".cleanup-panel", ".cleanup-details", ".cleanup-tree",
            ".text-area .content"};

    /** Containers painted in the window colour, which a picture has to show through. */
    static final String[] WINDOW_FILLS = {
            ".tab-pane .tab-header-background", ".update-pane", ".cleanup-root"};

    /**
     * Panes that cover the whole window over other content - the inventory
     * grid lies on top of the list view - and so carry the picture themselves:
     * made transparent, they would show what they are there to hide.
     */
    static final String[] PICTURE_HOLDERS = {".inventory-panel"};

    /** In a dialog, the parts over the picture that hold text. */
    static final String[] DIALOG_PARTS = {".dialog-pane > .content", ".dialog-pane > .button-bar"};

    /**
     * The whole stylesheet.
     *
     * @param base          {@code hexadron.css}
     * @param light         {@code hexadron-light.css}
     * @param appearance    what to draw
     * @param backgroundUrl the prepared picture as a URL, or null for none
     */
    public static String build(String base, String light, Appearance appearance, String backgroundUrl) {
        Palette palette = appearance.palette();
        StringBuilder css = new StringBuilder(base.length() + light.length() + 4096);
        css.append(base);
        if (palette.isLight()) {
            css.append("\n\n").append(light);
        }
        String sheet = scaleFonts(css.toString(), appearance.fontScale());
        if (!appearance.monoFont().isEmpty()) {
            sheet = withMonoFont(sheet, appearance.monoFont());
        }
        StringBuilder out = new StringBuilder(sheet);
        out.append("\n\n/* ---- theme: ").append(appearance.preset().id())
                .append(appearance.isCustomised() ? " (changed)" : "").append(" ---- */\n");
        out.append(rootBlock(appearance));
        if (backgroundUrl != null && !backgroundUrl.isEmpty()) {
            out.append(backdropBlock(appearance, backgroundUrl));
        }
        return out.toString();
    }

    /** Every pixel font size, multiplied by {@code percent} and rounded to half a pixel. */
    public static String scaleFonts(String css, int percent) {
        if (percent == 100) {
            return css;
        }
        Matcher matcher = FONT_SIZE.matcher(css);
        StringBuilder out = new StringBuilder(css.length() + 256);
        while (matcher.find()) {
            double size = Double.parseDouble(matcher.group(2)) * percent / 100.0;
            double rounded = Math.round(size * 2) / 2.0;
            String text = rounded == Math.floor(rounded)
                    ? String.valueOf((long) rounded)
                    : String.format(Locale.ROOT, "%.1f", rounded);
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + text + "px"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The fixed-width font of logs and code, put in front of the launcher's own choice. */
    public static String withMonoFont(String css, String family) {
        String name = Appearance.fontName(family);
        if (name.isEmpty()) {
            return css;
        }
        return MONO_FAMILY.matcher(css).replaceAll(Matcher.quoteReplacement(
                "-fx-font-family: \"" + name + "\", \"Consolas\", \"Menlo\", monospace;"));
    }

    /** The {@code .root} block: the colours, the colours mixed from them, and the font. */
    static String rootBlock(Appearance appearance) {
        StringBuilder out = new StringBuilder(".root {\n");
        tokens(appearance.palette()).forEach((name, value) ->
                out.append("    ").append(name).append(": ").append(value).append(";\n"));
        if (!appearance.font().isEmpty()) {
            out.append("    -fx-font-family: \"").append(appearance.font())
                    .append("\", \"Segoe UI\", \"Inter\", \"Noto Sans\", sans-serif;\n");
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * Every looked-up colour the stylesheet uses, by name.
     *
     * <p>The palette's own twelve, then the ones mixed from them. The launcher's
     * own theme keeps the exact values hexadron.css has always had, so choosing
     * it changes nothing on screen; any other palette has them worked out.
     */
    public static Map<String, String> tokens(Palette palette) {
        Map<String, String> tokens = new LinkedHashMap<>();
        for (Palette.Slot slot : Palette.Slot.values()) {
            tokens.put(slot.token(), palette.get(slot));
        }
        String background = palette.get(Palette.Slot.BACKGROUND);
        String panel = palette.get(Palette.Slot.PANEL);
        String control = palette.get(Palette.Slot.CONTROL);
        String accent = palette.get(Palette.Slot.ACCENT);
        String danger = palette.get(Palette.Slot.DANGER);
        String warning = palette.get(Palette.Slot.WARNING);
        tokens.put("-fx-group-tint", control);

        boolean own = palette.equals(ThemePreset.HEXADRON.palette());
        tokens.put("-fx-on-accent", Palette.inkOn(accent));
        tokens.put("-fx-on-danger", Palette.inkOn(danger));
        tokens.put("-fx-on-warning", Palette.inkOn(warning));
        tokens.put("-fx-on-modpack", Palette.inkOn(palette.get(Palette.Slot.MODPACK)));
        tokens.put("-fx-on-datapack", Palette.inkOn(palette.get(Palette.Slot.DATAPACK)));

        String ink = palette.isLight() ? "#000000" : "#ffffff";
        tokens.put("-fx-ink", palette.isLight() ? "#1b1e24" : "#ffffff");
        for (int alpha : new int[]{6, 9, 12, 35, 42, 45, 55, 80}) {
            double opacity = alpha / 100.0;
            tokens.put(String.format(Locale.ROOT, "-fx-ink-%02d", alpha), Palette.withAlpha(ink, opacity));
        }
        tokens.put("-fx-scrim-72", Palette.withAlpha(background, 0.72));
        tokens.put("-fx-scrim-84", Palette.withAlpha(background, 0.84));
        if (own) {
            tokens.put("-fx-success-text", "#4cc07a");
            tokens.put("-fx-danger-text", "#ff8a80");
            tokens.put("-fx-warning-text", "#d8a13c");
            tokens.put("-fx-toast-mark", "#e0b25a");
        } else {
            tokens.put("-fx-success-text", Palette.readableOn(accent, panel));
            tokens.put("-fx-danger-text", Palette.readableOn(danger, panel));
            tokens.put("-fx-warning-text", Palette.readableOn(warning, panel));
            tokens.put("-fx-toast-mark", palette.isLight()
                    ? Palette.mix(warning, "#000000", 0.08) : Palette.mix(warning, "#ffffff", 0.12));
        }
        tokens.put("-fx-warning-wash", Palette.withAlpha(warning, palette.isLight() ? 0.16 : 0.12));
        tokens.put("-fx-danger-wash", Palette.withAlpha(danger, palette.isLight() ? 0.12 : 0.16));

        // Modena's own: the parts of a control hexadron.css never names - a
        // spinner's arrows, a list inside a popup, the focus ring - are mixed
        // by modena from these, and from its light grey when they are not set.
        tokens.put("-fx-base", control);
        tokens.put("-fx-background", background);
        tokens.put("-fx-control-inner-background", control);
        tokens.put("-fx-accent", accent);
        tokens.put("-fx-focus-color", accent);
        tokens.put("-fx-faint-focus-color", Palette.withAlpha(accent, 0.13));
        return tokens;
    }

    /** The picture on window roots, and the panels over it made translucent. */
    static String backdropBlock(Appearance appearance, String backgroundUrl) {
        Palette palette = appearance.palette();
        String url = backgroundUrl.replace("\"", "%22").replace("\\", "/");
        StringBuilder out = new StringBuilder();
        out.append("\n.root.").append(BACKDROP_CLASS).append(",\n").append(join(PICTURE_HOLDERS, BACKDROP_CLASS))
                .append(" {\n")
                .append("    -fx-background-color: ").append(palette.get(Palette.Slot.BACKGROUND)).append(";\n")
                .append("    -fx-background-image: url(\"").append(url).append("\");\n")
                .append("    -fx-background-position: center center;\n");
        switch (appearance.fit()) {
            case COVER -> out.append("    -fx-background-repeat: no-repeat;\n    -fx-background-size: cover;\n");
            case CONTAIN -> out.append("    -fx-background-repeat: no-repeat;\n    -fx-background-size: contain;\n");
            case STRETCH -> out.append("    -fx-background-repeat: no-repeat;\n    -fx-background-size: 100% 100%;\n");
            case CENTER -> out.append("    -fx-background-repeat: no-repeat;\n    -fx-background-size: auto;\n");
            case TILE -> out.append("    -fx-background-repeat: repeat;\n    -fx-background-size: auto;\n")
                    .append("    -fx-background-position: left top;\n");
        }
        out.append("}\n\n");

        out.append(join(WINDOW_FILLS, BACKDROP_CLASS)).append(" {\n    -fx-background-color: transparent;\n}\n\n");
        String panel = Palette.withAlpha(palette.get(Palette.Slot.PANEL), appearance.panelOpacity() / 100.0);
        out.append(join(PANELS, BACKDROP_CLASS)).append(" {\n    -fx-background-color: ").append(panel)
                .append(";\n}\n\n");
        // A dialog is mostly sentences; they sit on the panel colour, not on
        // the picture, as they do on the main window's panels.
        for (int i = 0; i < DIALOG_PARTS.length; i++) {
            out.append(i > 0 ? ",\n" : "").append(".root.").append(BACKDROP_CLASS).append(DIALOG_PARTS[i]);
        }
        out.append(" {\n    -fx-background-color: ").append(panel).append(";\n}\n");
        return out.toString();
    }

    private static String join(String[] selectors, String scope) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < selectors.length; i++) {
            if (i > 0) {
                out.append(",\n");
            }
            out.append('.').append(scope).append(' ').append(selectors[i]);
        }
        return out.toString();
    }
}
