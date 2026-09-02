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

import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.util.Duration;

/**
 * A small panel that appears under something while the pointer is on it.
 *
 * <h2>Why not a tooltip</h2>
 *
 * <p>Because what goes in this one is a list of names to press. A tooltip is a
 * thing that is read and then gets out of the way: it belongs to the node it
 * hangs off, and the pointer leaving that node takes it away - which is exactly
 * the movement somebody makes when they reach for something inside it. A panel
 * that has to be reached into has to survive being reached into.
 *
 * <p>So it is a popup that watches two things instead of one. Leaving the badge
 * starts a short count; entering the panel stops it. The gap is what a hand
 * crossing four points of empty space needs, and is short enough that a panel
 * left behind never sits on screen after the pointer has gone elsewhere.
 *
 * <h2>One per row, not one per showing</h2>
 *
 * <p>Held by the cell that owns it and refilled as the row it draws changes,
 * for the same reason the cells themselves are reused: a popup, a layout and a
 * handful of links built for every row the eye passes over is work thrown away
 * unread.
 */
final class HoverPanel {

    /** How long a panel stays after the pointer leaves, so it can be reached. */
    private static final Duration LINGER = Duration.millis(220);

    /** The gap between the thing hovered and the panel under it. */
    private static final double DROP = 4;

    private final Popup popup = new Popup();
    private final VBox box = new VBox(4);
    private final PauseTransition closing = new PauseTransition(LINGER);

    HoverPanel() {
        box.getStyleClass().add("hover-panel");
        popup.getContent().add(box);
        // Clicking elsewhere closes it, and it never takes the keyboard from
        // the window it hangs over.
        popup.setAutoHide(true);
        popup.setAutoFix(true);
        popup.setHideOnEscape(true);

        closing.setOnFinished(event -> popup.hide());
        box.setOnMouseEntered(event -> closing.stop());
        box.setOnMouseExited(event -> closing.playFromStart());
    }

    /** What the panel shows. Filled by whoever owns it, before it is opened. */
    javafx.collections.ObservableList<Node> content() {
        return box.getChildren();
    }

    /**
     * Makes hovering this node open the panel.
     *
     * <p>Set once, on a node that is reused. An empty panel opens nothing, so a
     * row with nothing to say simply never shows one.
     */
    void watch(Node node) {
        node.setOnMouseEntered(event -> show(node));
        node.setOnMouseExited(event -> closing.playFromStart());
    }

    private void show(Node node) {
        closing.stop();
        if (box.getChildren().isEmpty() || node.getScene() == null) {
            return;
        }
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        // A popup is its own window with its own scene, and a scene with no
        // stylesheet is drawn by modena: light grey text on a light grey panel,
        // in the middle of a dark launcher.
        if (popup.getScene() != null) {
            popup.getScene().getStylesheets().setAll(node.getScene().getStylesheets());
        }
        popup.show(node, bounds.getMinX(), bounds.getMaxY() + DROP);
    }

    /** Closes it now, without waiting out the count. */
    void hide() {
        closing.stop();
        popup.hide();
    }
}
