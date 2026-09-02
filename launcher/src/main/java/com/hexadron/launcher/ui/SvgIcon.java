/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
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
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.List;

/**
 * Turns read path data into something on screen.
 *
 * <p>The reading is in {@link SvgPaths}, which needs no display and is checked
 * without one. This half is four lines of JavaFX and the two decisions that
 * matter: the shapes are stroked and not filled, which is what these drawings
 * are, and their colour is left to the stylesheet, so an icon in a list follows
 * the text beside it into whichever theme is on.
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
    static Region draw(List<String> paths, double size) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        Group group = new Group();
        for (String data : paths) {
            SVGPath path = new SVGPath();
            path.setContent(data);
            path.getStyleClass().add("svg-icon");
            path.setStrokeLineCap(StrokeLineCap.ROUND);
            path.setStrokeLineJoin(StrokeLineJoin.ROUND);
            path.setStrokeWidth(2);
            group.getChildren().add(path);
        }
        double scale = size / SvgPaths.GRID;
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
        // the text loses the difference: "Ігрові механіки" becomes "Ігрові
        // механі...", which is exactly as much as a stroke is wide.
        //
        // A box of a fixed size is the same size before and after any of that.
        StackPane holder = new StackPane(group);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        // It is a picture next to a name, not something to press or to clip.
        holder.setMouseTransparent(true);
        return holder;
    }
}
