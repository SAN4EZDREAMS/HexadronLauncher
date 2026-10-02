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

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * One main action, and the related ones behind an arrow beside it.
 *
 * <p>Built from two buttons and an {@link ActionMenu} rather than JavaFX's
 * SplitMenuButton. That control's menu opens at whatever width its longest
 * item needs, with no way to ask for another, so under a full-width button it
 * hung off the left edge like a tooltip that had lost its owner. Here the menu
 * is exactly as wide as the pair of buttons and opens a few pixels under them,
 * so it reads as the rest of the same control.
 */
final class SplitButton extends HBox {

    private static final PseudoClass SHOWING = PseudoClass.getPseudoClass("showing");

    private final Button main = new Button();
    private final Button arrow = new Button();
    private final ActionMenu menu = new ActionMenu();

    SplitButton(Node glyph) {
        getStyleClass().add("split-action");
        setAlignment(Pos.CENTER_LEFT);
        setFillHeight(true);

        main.getStyleClass().add("split-main");
        main.setGraphic(glyph);
        main.setMaxWidth(Double.MAX_VALUE);
        main.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(main, Priority.ALWAYS);

        arrow.getStyleClass().add("split-arrow");
        arrow.setGraphic(Glyphs.chevronDown());
        arrow.setMaxHeight(Double.MAX_VALUE);
        arrow.setMinWidth(Region.USE_PREF_SIZE);
        arrow.setOnAction(event -> menu.toggle(this, true, false));
        menu.setOnShowing(showing -> arrow.pseudoClassStateChanged(SHOWING, showing));

        getChildren().setAll(main, arrow);
    }

    /** The main action: what a click on the word does. */
    void setOnAction(Runnable action) {
        main.setOnAction(event -> action.run());
    }

    void setText(String text) {
        main.setText(text);
    }

    /** What the arrow is called, for its tooltip and for a screen reader. */
    void setArrowName(String name) {
        arrow.setTooltip(new Tooltip(name));
        arrow.setAccessibleText(name);
    }

    /** Adds an entry to the menu; its words are set later, with the rest of the window's. */
    ActionMenu.Item add(Node glyph, Runnable action) {
        return menu.add(glyph, action);
    }

    /** Adds an entry under a rule: one of another kind than the ones above it. */
    ActionMenu.Item addSeparated(Node glyph, Runnable action) {
        return menu.addSeparated(glyph, action);
    }
}
