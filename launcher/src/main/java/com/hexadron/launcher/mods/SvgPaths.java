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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line drawings, read out of the markup they were published as.
 *
 * <h2>What this is for</h2>
 *
 * <p>Modrinth publishes a small drawing beside each of its category names, and
 * they are the ones a player already recognises from the website. They arrive as
 * markup rather than as pictures, which is the good case: a drawing has no size,
 * so it is sharp on a 100% display and a 200% one alike, and it can take the
 * theme's colour the way a piece of text does instead of needing a light copy
 * and a dark one.
 *
 * <h2>Why this is not an SVG reader</h2>
 *
 * <p>It reads one shape of file: a set of line drawings on a 24 unit grid, drawn
 * with a stroke and no fill - which is what these are, and what every icon set
 * of this kind is. Six element types cover all of them, and five of the six are
 * a rectangle, a line or a run of points, each of which is two lines of
 * arithmetic to write as a path. What it does not do is transforms, gradients,
 * groups, styles, text or anything else a drawing program emits: those are not
 * in these files, and a half-implemented version of them would draw something
 * subtly wrong rather than nothing.
 *
 * <p>Anything it does not understand is left out. A category whose drawing comes
 * back empty is a category with a name, which is what it was before there were
 * drawings at all.
 *
 * <p>It lives here rather than beside the code that draws it so that it can be
 * checked without a display, which for a converter of untrusted text is the
 * whole difference between "it looked right on my machine" and knowing.
 */
public final class SvgPaths {

    /** The grid these are drawn on. Everything below is in its units. */
    public static final double GRID = 24;

    /** Deliberately narrow: these files have no quoting tricks in them. */
    private static final Pattern ELEMENT =
            Pattern.compile("<\\s*(path|circle|ellipse|rect|line|polyline|polygon)\\b([^>]*)>",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE =
            Pattern.compile("([\\w-]+)\\s*=\\s*\"([^\"]*)\"");

    private SvgPaths() {
    }

    /**
     * Reads the markup into path data.
     *
     * <p>Separated from anything that draws it because a drawing node can only
     * be in one place at a time, so every row that shows a category needs its
     * own - while the reading behind them is the same answer every time and is
     * done once, here.
     *
     * @return one entry per shape, in the order they were written; empty when
     *         there was nothing readable
     */
    public static List<String> read(String markup) {
        if (markup == null || markup.isBlank()) {
            return List.of();
        }
        return List.copyOf(pathsOf(markup));
    }

    /** Every element of the markup, as path data. */
    private static List<String> pathsOf(String markup) {
        List<String> paths = new ArrayList<>();
        Matcher elements = ELEMENT.matcher(markup);
        while (elements.find()) {
            String name = elements.group(1).toLowerCase(Locale.ROOT);
            java.util.Map<String, String> attributes = attributesOf(elements.group(2));
            String data = switch (name) {
                case "path" -> attributes.get("d");
                case "circle" -> ellipse(number(attributes, "cx"), number(attributes, "cy"),
                        number(attributes, "r"), number(attributes, "r"));
                case "ellipse" -> ellipse(number(attributes, "cx"), number(attributes, "cy"),
                        number(attributes, "rx"), number(attributes, "ry"));
                case "rect" -> rectangle(attributes);
                case "line" -> line(attributes);
                case "polyline" -> points(attributes.get("points"), false);
                case "polygon" -> points(attributes.get("points"), true);
                default -> null;
            };
            if (data != null && !data.isBlank()) {
                paths.add(data);
            }
        }
        return paths;
    }

    private static java.util.Map<String, String> attributesOf(String text) {
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(text);
        while (matcher.find()) {
            attributes.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
        }
        return attributes;
    }

    private static double number(java.util.Map<String, String> attributes, String name) {
        String value = attributes.get(name);
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A circle or an ellipse, as two half-turns.
     *
     * <p>One arc cannot draw a closed ellipse - the start and the end would be
     * the same point and the renderer has no way to tell which way round to go -
     * so it is written as two.
     */
    private static String ellipse(double cx, double cy, double rx, double ry) {
        if (rx <= 0 || ry <= 0) {
            return null;
        }
        return "M " + (cx - rx) + " " + cy
                + " a " + rx + " " + ry + " 0 1 0 " + (2 * rx) + " 0"
                + " a " + rx + " " + ry + " 0 1 0 " + (-2 * rx) + " 0 Z";
    }

    private static String rectangle(java.util.Map<String, String> attributes) {
        double x = number(attributes, "x");
        double y = number(attributes, "y");
        double width = number(attributes, "width");
        double height = number(attributes, "height");
        if (width <= 0 || height <= 0) {
            return null;
        }
        double radius = Math.min(Math.max(number(attributes, "rx"), number(attributes, "ry")),
                Math.min(width, height) / 2);
        if (radius <= 0) {
            return "M " + x + " " + y + " h " + width + " v " + height + " h " + (-width) + " Z";
        }
        return "M " + (x + radius) + " " + y
                + " h " + (width - 2 * radius)
                + " a " + radius + " " + radius + " 0 0 1 " + radius + " " + radius
                + " v " + (height - 2 * radius)
                + " a " + radius + " " + radius + " 0 0 1 " + (-radius) + " " + radius
                + " h " + (-(width - 2 * radius))
                + " a " + radius + " " + radius + " 0 0 1 " + (-radius) + " " + (-radius)
                + " v " + (-(height - 2 * radius))
                + " a " + radius + " " + radius + " 0 0 1 " + radius + " " + (-radius) + " Z";
    }

    private static String line(java.util.Map<String, String> attributes) {
        return "M " + number(attributes, "x1") + " " + number(attributes, "y1")
                + " L " + number(attributes, "x2") + " " + number(attributes, "y2");
    }

    /**
     * A run of points, open or closed.
     *
     * <p>The separators are whatever the writer felt like: commas, spaces,
     * newlines, or several of each. Everything that is not a number is a
     * separator, which is the only reading of this that survives real files.
     */
    private static String points(String value, boolean closed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] numbers = value.trim().split("[\\s,]+");
        if (numbers.length < 4 || numbers.length % 2 != 0) {
            return null;
        }
        StringBuilder data = new StringBuilder();
        for (int i = 0; i + 1 < numbers.length; i += 2) {
            data.append(i == 0 ? "M " : " L ").append(numbers[i]).append(' ').append(numbers[i + 1]);
        }
        return closed ? data.append(" Z").toString() : data.toString();
    }
}
