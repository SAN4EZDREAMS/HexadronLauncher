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

import com.hexadron.launcher.mods.SvgPaths;

import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * Turns a read drawing into something on screen.
 *
 * <p>The reading is in {@link SvgPaths}, which needs no display and is checked
 * without one. This half is a few lines of JavaFX and three decisions: the
 * drawing is scaled by the grid it was actually drawn on, it is painted the way
 * its markup asks to be painted, and its colour is left to the stylesheet, so an
 * icon in a list follows the text beside it into whichever theme is on.
 */
final class SvgIcon {

    private SvgIcon() {
    }

    /**
     * A drawing of the given size.
     *
     * @return null when there is nothing to draw, which the caller shows as a
     *         name on its own
     */
    static Region draw(SvgPaths.Drawing drawing, double size) {
        if (drawing == null || drawing.isEmpty()) {
            return null;
        }

        // Scaled by the drawing's own grid, not by a constant.
        //
        // Almost every icon in this set is on a twenty-four unit grid and a
        // constant of twenty-four was right for it. Modrinth's "potato" shader
        // category is on a 512 unit one, and a constant drew it twenty-one times
        // too big: a drawing the width of the whole filter panel, laid over the
        // rows above its own. See SvgPaths.Drawing.
        double scale = size / drawing.extent();

        Group group = new Group();
        for (String data : drawing.paths()) {
            SVGPath path = new SVGPath();
            path.setContent(data);
            // Solid or outlined, as the markup asks. The two are different
            // stylesheet entries rather than colours set here, so both still
            // follow the theme.
            path.getStyleClass().add(drawing.filled() ? "svg-icon-filled" : "svg-icon");
            path.setStrokeLineCap(StrokeLineCap.ROUND);
            path.setStrokeLineJoin(StrokeLineJoin.ROUND);
            // In the grid's units, like everything else in the drawing, so it is
            // scaled along with it and comes out the same weight at any size.
            path.setStrokeWidth(drawing.stroked() ? drawing.strokeWidth() : 0);
            group.getChildren().add(path);
        }
        group.setScaleX(scale);
        group.setScaleY(scale);

        // The drawing is put in a box of the size it was asked for, and the box
        // is what the layout sees.
        //
        // A group reports the bounds of what is in it, and those bounds move: a
        // stroked shape is wider than the same shape unstroked, and the colour
        // that makes it stroked arrives from the stylesheet - so the icon grows
        // by a couple of points the moment the stylesheet is applied, after the
        // label beside it has already been given its width. The label keeps the
        // width it was measured at, the icon in it is now wider than it was, and
        // the text loses the difference: the name is cut short by exactly as much
        // as a stroke is wide.
        //
        // A box of a fixed size is the same size before and after any of that.
        StackPane holder = new StackPane(group);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        // It is a picture next to a name, not something to press.
        holder.setMouseTransparent(true);
        // And it cannot leave its box, whatever it is a drawing of.
        //
        // Mouse-transparent keeps a drawing from being clicked; it does not keep
        // one from being asked whether a point is inside it. A parent answers
        // that question by asking every child, transparent or not, so a drawing
        // hanging out of its box makes the tick box holding it answer "yes" for
        // points over its neighbours - and the neighbours, laid out earlier,
        // never get the click. Two shader categories could not be ticked at all
        // for this reason. A clip is what makes the box's answer the box's size.
        holder.setClip(new Rectangle(size, size));
        return holder;
    }
}
