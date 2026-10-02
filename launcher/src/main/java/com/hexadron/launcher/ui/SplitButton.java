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

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * One main action, and the related ones behind an arrow beside it.
 *
 * <p>Built from two buttons and a menu rather than JavaFX's SplitMenuButton.
 * That control's menu opens at whatever width its longest item needs, with no
 * way to ask for another, so under a full-width button it hung off the left
 * edge like a tooltip that had lost its owner. Here the menu is exactly as
 * wide as the pair of buttons and opens a few pixels under them, so it reads
 * as the rest of the same control.
 *
 * <p>Each item has a picture, a name and one line on what it is for: three
 * ways a new entry can arrive in a list are not obvious from three words.
 */
final class SplitButton extends HBox {

    /** Between the bottom of the buttons and the top of the menu. */
    private static final double GAP = 4;
    /** A first guess at what the menu adds around an item; measured on the first opening. */
    private static final double GUESSED_INSETS = 30;
    private static final PseudoClass SHOWING = PseudoClass.getPseudoClass("showing");

    private final Button main = new Button();
    private final Button arrow = new Button();
    private final ContextMenu menu = new ContextMenu();
    private final List<Region> contents = new ArrayList<>();
    private double insets = GUESSED_INSETS;
    private boolean measured;
    /** When the menu last closed; a click on the arrow that closed it is not one that opens it. */
    private long hiddenAt;

    /** One entry of the menu. */
    static final class Item {
        private final Label title = new Label();
        private final Label hint = new Label();

        void setText(String name, String what) {
            title.setText(name);
            hint.setText(what);
            hint.setVisible(what != null && !what.isBlank());
            hint.setManaged(hint.isVisible());
        }
    }

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
        arrow.setOnAction(event -> toggle());

        menu.getStyleClass().add("create-menu");
        menu.setOnShowing(event -> arrow.pseudoClassStateChanged(SHOWING, true));
        menu.setOnHidden(event -> {
            arrow.pseudoClassStateChanged(SHOWING, false);
            hiddenAt = System.nanoTime();
        });
        menu.setOnShown(event -> fitWidth());

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
    Item add(Node glyph, Runnable action) {
        Item item = new Item();
        item.title.getStyleClass().add("create-menu-title");
        item.hint.getStyleClass().add("create-menu-hint");
        item.hint.setWrapText(true);

        StackPane picture = new StackPane(glyph);
        picture.getStyleClass().add("create-menu-glyph");
        picture.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox words = new VBox(1, item.title, item.hint);
        words.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(words, Priority.ALWAYS);
        HBox content = new HBox(10, picture, words);
        content.setAlignment(Pos.CENTER_LEFT);
        contents.add(content);

        CustomMenuItem entry = new CustomMenuItem(content, true);
        entry.setOnAction(event -> action.run());
        menu.getItems().add(entry);
        return item;
    }

    /** Adds an entry under a rule: one of another kind than the ones above it. */
    Item addSeparated(Node glyph, Runnable action) {
        menu.getItems().add(new SeparatorMenuItem());
        return add(glyph, action);
    }

    private void toggle() {
        if (menu.isShowing()) {
            menu.hide();
            return;
        }
        // The press on the arrow closed the open menu on its way in (a menu
        // closes on any press outside it); the click that follows must not
        // open it again.
        if (System.nanoTime() - hiddenAt < 250_000_000L) {
            return;
        }
        open();
    }

    private void open() {
        sizeContents();
        menu.show(this, Side.BOTTOM, 0, GAP);
    }

    /** Makes every entry as wide as the buttons, less what the menu draws around it. */
    private void sizeContents() {
        double width = Math.max(120, getWidth() - insets);
        for (Region content : contents) {
            content.setMinWidth(width);
            content.setPrefWidth(width);
            content.setMaxWidth(width);
        }
    }

    /**
     * Measures, on the first opening, what the menu adds around an entry, and
     * opens it again at the right width when the guess was off. The padding
     * comes from the stylesheet and the theme, so it is read, not assumed.
     */
    private void fitWidth() {
        if (measured || menu.getSkin() == null || !(menu.getSkin().getNode() instanceof Region root)) {
            return;
        }
        measured = true;
        double off = root.getWidth() - getWidth();
        if (Math.abs(off) < 0.5) {
            return;
        }
        insets += off;
        menu.hide();
        Platform.runLater(this::open);
    }
}
