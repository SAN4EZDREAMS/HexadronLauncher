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

package com.hexadron.launcher.ui;

import javafx.scene.Group;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

/**
 * The launcher's icons, as paths rather than as pictures.
 *
 * <h2>Why drawn and not shipped</h2>
 *
 * <p>An icon file has a size, and a window that can be opened on a 100% display
 * and a 200% one needs two of them, or one that is soft on the first. A path
 * has no size: it is the same shape at any scale, it takes its colour from the
 * stylesheet like every other piece of text, and it changes with the theme
 * without a second file existing.
 *
 * <p>They are drawn on a 24 by 24 grid, the size every icon set uses, so a
 * shape lifted from one lands in the right place here.
 */
final class Glyphs {

    /** The grid every path below is drawn on. */
    private static final double GRID = 24;

    private Glyphs() {
    }

    /**
     * A cog.
     *
     * <p>Eight teeth and a hole, which is what a settings icon has been since
     * before any of this - the one symbol in a toolbar nobody has to be taught.
     * The two subpaths are wound so the even-odd rule punches the centre out
     * rather than filling it.
     */
    static Group settings() {
        SVGPath cog = new SVGPath();
        cog.setFillRule(FillRule.EVEN_ODD);
        cog.setContent(
                "M13.6 2h-3.2l-.5 2.4a7.9 7.9 0 0 0-1.9 1.1L5.7 4.7 3.4 7.2l1.6 1.9"
                        + "a7.9 7.9 0 0 0-.5 2.2H2v3.4h2.5c.1.8.3 1.5.6 2.2l-1.6 1.9 2.3 2.5"
                        + " 2.3-.8c.6.5 1.2.8 1.9 1.1l.5 2.4h3.2l.5-2.4c.7-.3 1.3-.6 1.9-1.1"
                        + "l2.3.8 2.3-2.5-1.6-1.9c.3-.7.5-1.4.6-2.2H22v-3.4h-2.5"
                        + "a7.9 7.9 0 0 0-.5-2.2l1.6-1.9-2.3-2.5-2.3.8a7.9 7.9 0 0 0-1.9-1.1z"
                        + "M12 8.4a3.6 3.6 0 1 1 0 7.2 3.6 3.6 0 0 1 0-7.2z");
        return sized(cog, 16);
    }

    /**
     * A question mark in a ring.
     *
     * <p>Chosen over an {@code i} because the two mean different things in a
     * toolbar and this is the one that means "what is this". Drawn as a ring
     * rather than a filled disc so it sits at the same visual weight as the cog
     * beside it - a solid circle next to an outlined shape reads as the louder
     * of the two, and neither of these is the important button on that bar.
     */
    static Group about() {
        SVGPath mark = new SVGPath();
        mark.setFillRule(FillRule.EVEN_ODD);
        mark.setContent(
                // The ring: an outer circle and an inner one, wound so the
                // even-odd rule leaves the band between them.
                "M12 1.6a10.4 10.4 0 1 1 0 20.8 10.4 10.4 0 0 1 0-20.8z"
                        + "M12 3.6a8.4 8.4 0 1 0 0 16.8 8.4 8.4 0 0 0 0-16.8z"
                        // The hook, written as explicit segments rather than the
                        // shorthand a shorter path would use: a mark this small
                        // shows every join, and one that does not close cleanly
                        // reads as a rendering fault rather than as a glyph.
                        + "M12 6.0 c -2.15 0 -3.85 1.6 -3.95 3.75 h 2.05 "
                        + "c 0.1 -1.05 0.9 -1.85 1.9 -1.85 c 1.0 0 1.8 0.75 1.8 1.7 "
                        + "c 0 0.7 -0.4 1.2 -1.2 1.8 c -1.05 0.8 -1.5 1.55 -1.45 2.75 "
                        + "v 0.45 h 2.0 v -0.4 c 0 -0.7 0.3 -1.1 1.1 -1.7 "
                        + "c 1.15 -0.85 1.65 -1.65 1.65 -2.85 c 0 -2.05 -1.65 -3.65 -3.9 -3.65 z"
                        + "M12 15.2 a 1.3 1.3 0 1 0 0 2.6 a 1.3 1.3 0 0 0 0 -2.6 z");
        return sized(mark, 16);
    }

    /**
     * Four tiles in a square.
     *
     * <p>The inventory, drawn as what it is: cells of equal size in a grid. Four
     * rather than nine, because at sixteen pixels nine tiles are nine grey specks
     * with a pixel between them and the shape stops reading as anything.
     *
     * <p>The corners are rounded by the same amount as the profile tiles they
     * stand for, which is what makes this an icon of that grid rather than a
     * generic four-square.
     */
    static Group grid() {
        SVGPath tiles = new SVGPath();
        tiles.setFillRule(FillRule.EVEN_ODD);
        tiles.setContent(
                // Written out in full - absolute segments, spaces between every
                // number - because the compact form these are usually exported
                // in leans on a parser being generous, and a path that is read
                // wrongly is not an error anywhere, only a wrong shape.
                "M 4 3 H 10.2 A 1 1 0 0 1 11.2 4 V 10.2 A 1 1 0 0 1 10.2 11.2 H 4 "
                        + "A 1 1 0 0 1 3 10.2 V 4 A 1 1 0 0 1 4 3 Z "
                        + "M 13.8 3 H 20 A 1 1 0 0 1 21 4 V 10.2 A 1 1 0 0 1 20 11.2 H 13.8 "
                        + "A 1 1 0 0 1 12.8 10.2 V 4 A 1 1 0 0 1 13.8 3 Z "
                        + "M 4 12.8 H 10.2 A 1 1 0 0 1 11.2 13.8 V 20 A 1 1 0 0 1 10.2 21 H 4 "
                        + "A 1 1 0 0 1 3 20 V 13.8 A 1 1 0 0 1 4 12.8 Z "
                        + "M 13.8 12.8 H 20 A 1 1 0 0 1 21 13.8 V 20 A 1 1 0 0 1 20 21 H 13.8 "
                        + "A 1 1 0 0 1 12.8 20 V 13.8 A 1 1 0 0 1 13.8 12.8 Z");
        return sized(tiles, 16);
    }

    /**
     * Three rows, each a marker and a line.
     *
     * <p>The list, and the pair reads as a pair: this one is wide and thin where
     * the grid is square, which is the difference somebody sees before they have
     * looked at either. The markers are square rather than round because the
     * rows they stand for carry an instance's icon, not a bullet.
     */
    static Group list() {
        SVGPath rows = new SVGPath();
        rows.setFillRule(FillRule.EVEN_ODD);
        rows.setContent(
                // Three markers down the left, three bars beside them, each pair
                // sharing a centre line: 6.6, 12 and 17.4 on the 24 grid.
                "M 3.6 4.4 H 6.8 A 0.6 0.6 0 0 1 7.4 5 V 8.2 A 0.6 0.6 0 0 1 6.8 8.8 H 3.6 "
                        + "A 0.6 0.6 0 0 1 3 8.2 V 5 A 0.6 0.6 0 0 1 3.6 4.4 Z "
                        + "M 10.3 5.7 H 20.1 A 0.9 0.9 0 0 1 20.1 7.5 H 10.3 "
                        + "A 0.9 0.9 0 0 1 10.3 5.7 Z "
                        + "M 3.6 9.8 H 6.8 A 0.6 0.6 0 0 1 7.4 10.4 V 13.6 "
                        + "A 0.6 0.6 0 0 1 6.8 14.2 H 3.6 A 0.6 0.6 0 0 1 3 13.6 V 10.4 "
                        + "A 0.6 0.6 0 0 1 3.6 9.8 Z "
                        + "M 10.3 11.1 H 20.1 A 0.9 0.9 0 0 1 20.1 12.9 H 10.3 "
                        + "A 0.9 0.9 0 0 1 10.3 11.1 Z "
                        + "M 3.6 15.2 H 6.8 A 0.6 0.6 0 0 1 7.4 15.8 V 19 "
                        + "A 0.6 0.6 0 0 1 6.8 19.6 H 3.6 A 0.6 0.6 0 0 1 3 19 V 15.8 "
                        + "A 0.6 0.6 0 0 1 3.6 15.2 Z "
                        + "M 10.3 16.5 H 20.1 A 0.9 0.9 0 0 1 20.1 18.3 H 10.3 "
                        + "A 0.9 0.9 0 0 1 10.3 16.5 Z");
        return sized(rows, 16);
    }

    /**
     * A beetle: body, head, two antennae, six legs, and markings punched out.
     *
     * <p>An insect rather than an exclamation mark or a crossed-out circle. The
     * other two mean "something is wrong right now" and would read, sitting in
     * a toolbar beside the cog, as a warning about the launcher's own state -
     * which is not what this button is. A bug is the one shape that means
     * "report a fault" without meaning "there is a fault".
     *
     * <p>Wound rather than filled with the even-odd rule, unlike every other
     * glyph here. This one is a silhouette assembled from overlapping pieces -
     * the head sits on the body, the legs run under it - and even-odd would
     * punch a hole out of every overlap. So the outline pieces are all wound
     * one way and the markings the other, and {@link FillRule#NON_ZERO} fills
     * the union and empties the markings.
     */
    static Group bug() {
        SVGPath beetle = new SVGPath();
        beetle.setFillRule(FillRule.NON_ZERO);
        beetle.setContent(
                // Body: an ellipse about (12, 14.5), drawn clockwise.
                "M 12 7.6 A 5.9 6.9 0 0 1 12 21.4 A 5.9 6.9 0 0 1 12 7.6 Z "
                        // Head, overlapping the body so the two read as one
                        // silhouette rather than as two shapes that touch.
                        + "M 12 2.6 A 2.8 2.8 0 0 1 12 8.2 A 2.8 2.8 0 0 1 12 2.6 Z "
                        // Antennae.
                        + "M 7.4 1.5 L 10.0 3.7 L 9.2 4.7 L 6.6 2.5 Z "
                        + "M 17.4 2.5 L 14.8 4.7 L 14.0 3.7 L 16.6 1.5 Z "
                        // Six legs, each rooted just inside the body edge and
                        // reaching the frame. Thicker than they look they need
                        // to be: at sixteen pixels a bar under a unit wide is a
                        // row of grey specks with gaps in it.
                        + "M 2.8 7.6 L 8.0 10.3 L 7.4 11.5 L 2.2 8.8 Z "
                        + "M 2.0 13.9 L 7.8 13.9 L 7.8 15.2 L 2.0 15.2 Z "
                        + "M 2.2 20.4 L 7.4 17.7 L 8.0 18.9 L 2.8 21.6 Z "
                        + "M 21.2 8.8 L 16.0 11.5 L 15.4 10.3 L 20.6 7.6 Z "
                        + "M 22.0 15.2 L 16.2 15.2 L 16.2 13.9 L 22.0 13.9 Z "
                        + "M 21.8 21.6 L 16.0 18.9 L 16.6 17.7 L 21.2 20.4 Z "
                        // The seam and two spots, wound the other way so they
                        // come out of the body rather than adding to it. Two
                        // rather than the four this was drawn with first: four
                        // survive the large size and turn the small one to mush.
                        + "M 11.35 9.4 L 11.35 19.8 L 12.65 19.8 L 12.65 9.4 Z "
                        + "M 9.2 12.0 A 1.35 1.35 0 0 0 9.2 14.7 A 1.35 1.35 0 0 0 9.2 12.0 Z "
                        + "M 14.8 12.0 A 1.35 1.35 0 0 0 14.8 14.7 A 1.35 1.35 0 0 0 14.8 12.0 Z");
        return sized(beetle, 16);
    }

    /**
     * A hexagonal ring: one module.
     *
     * <p>The content window's list of kinds needs three shapes that are told
     * apart at sixteen pixels, and told apart by outline rather than by detail -
     * anything finer than a stroke is mush at that size. A hexagon, a crate and a
     * page are three silhouettes with nothing in common, which is the whole
     * requirement.
     *
     * <p>Drawn as a ring: two hexagons wound the same way, with the even-odd rule
     * punching the smaller one out. Filled, it would be a blob.
     */
    static Group module() {
        SVGPath hexagon = new SVGPath();
        hexagon.setFillRule(FillRule.EVEN_ODD);
        hexagon.setContent(
                "M12 2 L20.7 7 L20.7 17 L12 22 L3.3 17 L3.3 7 Z "
                        + "M12 6.5 L16.8 9.25 L16.8 14.75 L12 17.5 L7.2 14.75 L7.2 9.25 Z");
        return sized(hexagon, 16);
    }

    /**
     * A crate: a set that travels as one thing.
     *
     * <p>Assembled from bars rather than punched out of a rectangle, and wound
     * one way with {@link FillRule#NON_ZERO}, so the band across the middle joins
     * the frame instead of cutting a hole in it - which is what even-odd would do
     * where the two overlap.
     */
    static Group crate() {
        SVGPath crate = new SVGPath();
        crate.setFillRule(FillRule.NON_ZERO);
        crate.setContent(
                // The frame, as four bars.
                "M3 4.5 H21 V6.7 H3 Z "
                        + "M3 17.3 H21 V19.5 H3 Z "
                        + "M3 4.5 H5.2 V19.5 H3 Z "
                        + "M18.8 4.5 H21 V19.5 H18.8 Z "
                        // The band, and the latch on it.
                        + "M3 10.4 H21 V12.6 H3 Z "
                        + "M10.3 12.6 H13.7 V16 H10.3 Z");
        return sized(crate, 16);
    }

    /**
     * A page with writing on it: data.
     *
     * <p>Three lines, the last one short, because that is what makes a rectangle
     * with bars in it read as a page of text rather than as a list. Wound one way
     * for the same reason as the crate.
     */
    static Group page() {
        SVGPath page = new SVGPath();
        page.setFillRule(FillRule.NON_ZERO);
        page.setContent(
                "M5 2 H19 V4.1 H5 Z "
                        + "M5 19.9 H19 V22 H5 Z "
                        + "M5 2 H7.1 V22 H5 Z "
                        + "M16.9 2 H19 V22 H16.9 Z "
                        + "M8.7 6.6 H15.3 V8.4 H8.7 Z "
                        + "M8.7 11.1 H15.3 V12.9 H8.7 Z "
                        + "M8.7 15.6 H12.8 V17.4 H8.7 Z");
        return sized(page, 16);
    }

    /**
     * Resource packs: a stack of tiles, which is what a texture pack is.
     *
     * <p>Drawn as three offset squares rather than a picture frame, because the
     * rail is sixteen pixels and a frame at that size is a rectangle with a
     * smaller rectangle in it - indistinguishable from the modpack crate.
     */
    static Group palette() {
        SVGPath tiles = new SVGPath();
        tiles.setFillRule(FillRule.NON_ZERO);
        tiles.setContent(
                "M3 3 H10.4 V10.4 H3 Z "
                        + "M13.6 3 H21 V10.4 H13.6 Z "
                        + "M3 13.6 H10.4 V21 H3 Z "
                        + "M13.6 13.6 H21 V16.3 H13.6 Z "
                        + "M13.6 18.3 H21 V21 H13.6 Z");
        return sized(tiles, 16);
    }

    /**
     * Shaders: a sun over a horizon.
     *
     * <p>What a shader pack changes is the light, and the light is the one thing
     * a sixteen-pixel glyph can say about it.
     */
    static Group sun() {
        SVGPath sun = new SVGPath();
        sun.setFillRule(FillRule.NON_ZERO);
        sun.setContent(
                "M11.1 2 H12.9 V5.2 H11.1 Z "
                        + "M17.9 4.8 L19.2 6.1 L16.9 8.4 L15.6 7.1 Z "
                        + "M4.8 6.1 L6.1 4.8 L8.4 7.1 L7.1 8.4 Z "
                        + "M12 8 A4 4 0 1 1 11.99 8 Z "
                        + "M2 15.1 H22 V16.9 H2 Z "
                        + "M5 19.1 H19 V20.9 H5 Z");
        return sized(sun, 16);
    }

    /** A plus: makes something new. Drawn small, beside a word that says what. */
    static Group plus() {
        SVGPath plus = new SVGPath();
        plus.setFillRule(FillRule.NON_ZERO);
        plus.setContent(
                "M 10.6 4 H 13.4 V 10.6 H 20 V 13.4 H 13.4 V 20 H 10.6 V 13.4 "
                        + "H 4 V 10.6 H 10.6 Z");
        return sized(plus, 12);
    }

    /** A pencil: change this. */
    static Group pencil() {
        SVGPath pencil = new SVGPath();
        pencil.setFillRule(FillRule.NON_ZERO);
        pencil.setContent("M 14.6 4.6 L 19.4 9.4 L 9 19.8 L 3.8 20.2 L 4.2 15 Z "
                + "M 16 3.2 L 17.4 1.8 A 1.4 1.4 0 0 1 19.4 1.8 L 22.2 4.6 A 1.4 1.4 0 0 1 22.2 6.6 L 20.8 8 Z");
        return sized(pencil, 14);
    }

    /** An arrow going round: check everything again and put back what is broken. */
    static Group repair() {
        SVGPath arrows = new SVGPath();
        arrows.setFillRule(FillRule.NON_ZERO);
        arrows.setContent("M 12 4 A 8 8 0 1 0 20 12 H 17.6 A 5.6 5.6 0 1 1 12 6.4 Z "
                + "M 12 1.2 L 16.6 5.2 L 12 9.2 Z");
        return sized(arrows, 14);
    }

    /** Three dots in a row: more of the same kind, behind this. */
    static Group dots() {
        SVGPath dots = new SVGPath();
        dots.setFillRule(FillRule.NON_ZERO);
        dots.setContent("M 5 10 A 2 2 0 1 1 5 14 A 2 2 0 1 1 5 10 Z "
                + "M 12 10 A 2 2 0 1 1 12 14 A 2 2 0 1 1 12 10 Z "
                + "M 19 10 A 2 2 0 1 1 19 14 A 2 2 0 1 1 19 10 Z");
        return sized(dots, 16);
    }

    /** An arrow out of a tray: something written out to a file. */
    static Group exportArrow() {
        SVGPath arrow = new SVGPath();
        arrow.setFillRule(FillRule.NON_ZERO);
        arrow.setContent(
                "M 10.9 16.5 H 13.1 V 7.3 L 16.2 10.4 L 17.8 8.8 L 12 3 L 6.2 8.8 L 7.8 10.4 L 10.9 7.3 Z "
                        + "M 3 14 H 5.2 V 18.8 H 18.8 V 14 H 21 V 21 H 3 Z");
        return sized(arrow, 16);
    }

    /** A bin: gone from the list. */
    static Group trash() {
        SVGPath bin = new SVGPath();
        bin.setFillRule(FillRule.NON_ZERO);
        bin.setContent("M 9 3 H 15 V 4.5 H 20 V 6.7 H 4 V 4.5 H 9 Z "
                + "M 5.5 8 H 18.5 L 17.4 20 A 1.5 1.5 0 0 1 15.9 21.4 H 8.1 A 1.5 1.5 0 0 1 6.6 20 Z");
        return sized(bin, 16);
    }

    /** A chevron pointing down: there is more behind this. Turned over while it is open. */
    static Group chevronDown() {
        SVGPath chevron = new SVGPath();
        chevron.setFillRule(FillRule.NON_ZERO);
        chevron.setContent("M 5.3 8.3 L 12 15 L 18.7 8.3 L 20.4 10 L 12 18.4 L 3.6 10 Z");
        return sized(chevron, 12);
    }

    /** An arrow into a tray: something brought in from a file. */
    static Group importArrow() {
        SVGPath arrow = new SVGPath();
        arrow.setFillRule(FillRule.NON_ZERO);
        arrow.setContent(
                "M 10.9 3 H 13.1 V 12.2 L 16.2 9.1 L 17.8 10.7 L 12 16.5 L 6.2 10.7 L 7.8 9.1 L 10.9 12.2 Z "
                        + "M 3 14 H 5.2 V 18.8 H 18.8 V 14 H 21 V 21 H 3 Z");
        return sized(arrow, 16);
    }

    /** A folder: a group, which holds instances the way a folder holds files. */
    static Group folder() {
        SVGPath folder = new SVGPath();
        folder.setFillRule(FillRule.NON_ZERO);
        folder.setContent(
                "M 3 6 A 1.5 1.5 0 0 1 4.5 4.5 H 9.5 L 11.5 6.5 H 19.5 A 1.5 1.5 0 0 1 21 8 V 18 "
                        + "A 1.5 1.5 0 0 1 19.5 19.5 H 4.5 A 1.5 1.5 0 0 1 3 18 Z");
        return sized(folder, 16);
    }

    /**
     * Three bars that shorten downwards, and an arrow pointing down beside them.
     *
     * <p>Sort, in the shape every file manager uses for it. Bars and not letters:
     * "A-Z" is a Latin alphabet, and the list it sorts may be in any script.
     */
    static Group sort() {
        SVGPath sort = new SVGPath();
        sort.setFillRule(FillRule.NON_ZERO);
        sort.setContent(
                "M 3 5 H 13 V 7 H 3 Z "
                        + "M 3 11 H 10.5 V 13 H 3 Z "
                        + "M 3 17 H 8 V 19 H 3 Z "
                        + "M 16.9 4 H 19.1 V 15.4 H 21.6 L 18 20.6 L 14.4 15.4 H 16.9 Z");
        return sized(sort, 16);
    }

    /** A broom: the storage window, where the launcher is swept out. */
    static Group broom() {
        SVGPath broom = new SVGPath();
        broom.setFillRule(FillRule.NON_ZERO);
        broom.setContent(
                "M18.6 2.2 L21.8 5.4 L14.9 12.3 L11.7 9.1 Z "
                        + "M10.5 10.3 L13.7 13.5 L12.6 14.6 C11.8 15.4 11.7 16.9 12.3 18.9 L13.1 21.6 "
                        + "C9.3 22.3 5.2 21.2 2.4 18.6 L5.6 17.3 L3.3 15.9 "
                        + "C4.2 13.9 5.9 12.6 8.1 12.1 Z");
        return sized(broom, 16);
    }

    /**
     * Scales a path to a height in pixels and wraps it so layout can measure it.
     *
     * <p>Wrapped in a Group because a scaled node still reports its unscaled
     * bounds to a layout that asks - a button would reserve 24 pixels for a
     * 16-pixel icon and sit off-centre. A Group reports what it actually
     * occupies.
     */
    /**
     * A shield with a tick in it: a Microsoft account, the one that owns the
     * game and can join servers that check. Not Microsoft's own logo - that is
     * their trade mark - but the mark for "verified", which is what the
     * difference between the two kinds of account comes down to.
     */
    static Group licensedAccount() {
        SVGPath shield = new SVGPath();
        shield.setFillRule(FillRule.EVEN_ODD);
        shield.setContent("M12 2 L20 5 V11 C20 16.2 16.6 20.4 12 22 C7.4 20.4 4 16.2 4 11 V5 Z "
                + "M10.6 16 L6.8 12.2 L8.4 10.6 L10.6 12.8 L15.6 7.8 L17.2 9.4 Z");
        shield.getStyleClass().add("account-glyph-licensed");
        return sized(shield, 14);
    }

    /**
     * A plain figure: an offline account, a name and nothing else - no
     * licence behind it and nothing a server can check.
     */
    static Group offlineAccount() {
        SVGPath person = new SVGPath();
        person.setFillRule(FillRule.NON_ZERO);
        person.setContent("M12 3 A4.2 4.2 0 1 1 12 11.4 A4.2 4.2 0 1 1 12 3 Z "
                + "M3.8 21 C3.8 16.4 7.4 13.4 12 13.4 C16.6 13.4 20.2 16.4 20.2 21 Z");
        person.getStyleClass().add("account-glyph-offline");
        return sized(person, 14);
    }

    private static Group sized(SVGPath path, double pixels) {
        path.getStyleClass().add("glyph");
        double scale = pixels / GRID;
        path.setScaleX(scale);
        path.setScaleY(scale);
        return new Group(path);
    }
}
