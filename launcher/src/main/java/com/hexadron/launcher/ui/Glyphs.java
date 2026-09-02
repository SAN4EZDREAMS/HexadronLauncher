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
     * Scales a path to a height in pixels and wraps it so layout can measure it.
     *
     * <p>Wrapped in a Group because a scaled node still reports its unscaled
     * bounds to a layout that asks - a button would reserve 24 pixels for a
     * 16-pixel icon and sit off-centre. A Group reports what it actually
     * occupies.
     */
    private static Group sized(SVGPath path, double pixels) {
        path.getStyleClass().add("glyph");
        double scale = pixels / GRID;
        path.setScaleX(scale);
        path.setScaleY(scale);
        return new Group(path);
    }
}
