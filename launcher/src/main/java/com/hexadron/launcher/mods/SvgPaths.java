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

    /**
     * The grid to assume when the markup names none.
     *
     * <p>A drawing with no {@code viewBox} is one whose author took the default,
     * and for this kind of icon set the default is twenty-four. It is a fallback
     * and nothing more: the grid a drawing is actually on is read from the
     * drawing, because they are not all on the same one - see {@link #of}.
     */
    public static final double GRID = 24;

    /**
     * A drawing, with the two things a caller needs besides its shapes.
     *
     * <h2>The grid, because it is not always twenty-four</h2>
     *
     * <p>This used to hand back shapes alone and the code that drew them scaled
     * by a constant twenty-four. That is right for almost every icon in the set
     * and wrong for the ones that are not: Modrinth's {@code potato} shader
     * category is published on a 512 grid, so a constant of twenty-four drew it
     * twenty-one times too big - a drawing the width of the whole filter panel,
     * over the rows above it.
     *
     * <p>That is not only ugly. A drawing that spills out of its box widens the
     * box's parent as far as anything asking "is this point inside you" is
     * concerned, and a tick box the width of the panel answers yes for the rows
     * above it - so two categories could not be ticked at all. The size an icon
     * is drawn at has to come from the drawing.
     *
     * <h2>Filled or stroked, because it is not always stroked</h2>
     *
     * <p>Most of the set is a line drawing: no fill, a stroke of two units. The
     * 512-grid one is the other kind - a solid shape with {@code fill} and no
     * meaningful stroke - and drawing it stroked-and-hollow like the rest turns
     * a potato into a wire outline of a potato. What the markup asks for is
     * carried here and honoured.
     *
     * @param paths       one entry per shape, in the order they were written
     * @param minX        left edge of the grid the shapes are placed on
     * @param minY        top edge of it
     * @param width       its width, always above zero
     * @param height      its height, always above zero
     * @param filled      true when the shapes are solid
     * @param stroked     true when the shapes are outlined
     * @param strokeWidth the outline's width, in the grid's own units
     */
    public record Drawing(List<String> paths, double minX, double minY,
                          double width, double height,
                          boolean filled, boolean stroked, double strokeWidth) {

        public Drawing {
            paths = List.copyOf(paths);
        }

        /** Nothing readable was in the markup. */
        public static Drawing empty() {
            return new Drawing(List.of(), 0, 0, GRID, GRID, false, true, 2);
        }

        public boolean isEmpty() {
            return paths.isEmpty();
        }

        /** The longer side of the grid: what a square box has to fit. */
        public double extent() {
            return Math.max(width, height);
        }
    }

    /** Deliberately narrow: these files have no quoting tricks in them. */
    private static final Pattern ELEMENT =
            // [^<>] rather than [^>]: an element that never closes then fails
            // at the next "<" instead of rescanning to the end for every start.
            Pattern.compile("<\\s*(path|circle|ellipse|rect|line|polyline|polygon)\\b([^<>]*)>",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE =
            Pattern.compile("([\\w-]+)\\s*=\\s*\"([^\"]*)\"");

    /** The opening tag, which is where the grid and the paint are written. */
    private static final Pattern ROOT =
            Pattern.compile("<\\s*svg\\b([^>]*)>", Pattern.CASE_INSENSITIVE);

    /** Everything that is not a number is a separator. Same rule as points. */
    private static final Pattern SEPARATOR = Pattern.compile("[\\s,]+");

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
        return of(markup).paths();
    }

    /**
     * Reads the markup into a drawing: its shapes, the grid they are on, and
     * whether they are filled, stroked or both.
     *
     * <p>The whole of what a caller needs to put this on screen at a size of its
     * choosing. See {@link Drawing} for why the last two are not assumed.
     *
     * @return an empty drawing when there was nothing readable; never null
     */
    public static Drawing of(String markup) {
        if (markup == null || markup.isBlank()) {
            return Drawing.empty();
        }
        List<String> paths = pathsOf(markup);
        if (paths.isEmpty()) {
            return Drawing.empty();
        }
        java.util.Map<String, String> root = rootAttributes(markup);
        double[] box = viewBox(root.get("viewbox"));

        // What the markup asks to be painted with. A drawing that says neither
        // is the shape the rest of this set is - an outline, two units wide -
        // because that is what it was drawn as before any of this was read, and
        // a silent change of appearance is not an improvement.
        boolean declared = root.get("fill") != null || root.get("stroke") != null;
        boolean filled = paint(root.get("fill")) != null;
        boolean stroked = paint(root.get("stroke")) != null;
        // A drawing that asks for neither would be invisible, and a drawing that
        // asks for nothing at all is the outline the rest of this set is.
        if (!filled && !stroked) {
            stroked = true;
        }
        double strokeWidth = strokeWidth(root.get("stroke-width"), declared);

        return new Drawing(paths, box[0], box[1], box[2], box[3], filled, stroked, strokeWidth);
    }

    /** The attributes of the opening tag, or none when there is no such tag. */
    private static java.util.Map<String, String> rootAttributes(String markup) {
        Matcher matcher = ROOT.matcher(markup);
        return matcher.find() ? attributesOf(matcher.group(1)) : java.util.Map.of();
    }

    /**
     * The grid, as {@code minX minY width height}.
     *
     * <p>Falls back to the twenty-four unit square for anything unreadable, and
     * for a box with no area - a drawing scaled by zero is a drawing that is not
     * there, which is worse than one drawn on the wrong grid.
     */
    private static double[] viewBox(String value) {
        double[] fallback = {0, 0, GRID, GRID};
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String[] parts = SEPARATOR.split(value.trim());
        if (parts.length != 4) {
            return fallback;
        }
        double[] box = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                box[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        if (!(box[2] > 0) || !(box[3] > 0)) {
            return fallback;
        }
        return box;
    }

    /** A colour, or null for "none" and for an attribute that is not there. */
    private static String paint(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.equalsIgnoreCase("none") ? null : trimmed;
    }

    /**
     * The outline's width in grid units.
     *
     * <p>Unwritten means one, which is what SVG itself means by it - not the two
     * this used to hard-code. Two is kept as the answer for markup that names no
     * paint at all, which is how this reads a fragment rather than a whole file;
     * those were drawn two units wide before any of this was read, and a silent
     * change of appearance is not an improvement.
     */
    private static double strokeWidth(String value, boolean declared) {
        double fallback = declared ? 1 : 2;
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            double width = Double.parseDouble(value.trim());
            return width > 0 ? width : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
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
