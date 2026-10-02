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
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The launcher's menu of actions: each entry a picture, a name and one muted
 * line on what it does, opened a few pixels under the button it belongs to.
 *
 * <p>A JavaFX ContextMenu underneath, so it takes the window's stylesheet and
 * keyboard handling, with the parts a plain one cannot do added: it can be
 * exactly as wide as its button, or line its right edge up with the button's,
 * and a click on the button that closes it does not open it again.
 */
final class ActionMenu {

    /** Between the bottom of the button and the top of the menu. */
    private static final double GAP = 4;
    /** A first guess at what the menu adds around an entry; measured on the first opening. */
    private static final double GUESSED_INSETS = 30;
    /** The narrowest a menu of natural width is drawn. */
    private static final double MIN_ENTRY_WIDTH = 210;

    /** One entry. */
    static final class Item {
        private final Label title = new Label();
        private final Label hint = new Label();
        private CustomMenuItem entry;

        void setText(String name, String what) {
            title.setText(name);
            hint.setText(what == null ? "" : what);
            hint.setVisible(what != null && !what.isBlank());
            hint.setManaged(hint.isVisible());
        }

        void setDisable(boolean disable) {
            entry.setDisable(disable);
        }
    }

    private final ContextMenu menu = new ContextMenu();
    private final List<Region> contents = new ArrayList<>();
    private double insets = GUESSED_INSETS;
    private boolean measured;
    private long hiddenAt;
    private Region anchor;
    private boolean matchWidth;
    private boolean alignRight;
    private Consumer<Boolean> onShowing = showing -> { };

    ActionMenu() {
        menu.getStyleClass().add("create-menu");
        menu.setOnShowing(event -> onShowing.accept(true));
        menu.setOnHidden(event -> {
            onShowing.accept(false);
            hiddenAt = System.nanoTime();
        });
        menu.setOnShown(event -> afterShown());
    }

    /** Told true when the menu opens and false when it closes, for the button's look. */
    void setOnShowing(Consumer<Boolean> listener) {
        onShowing = listener;
    }

    Item add(Node glyph, Runnable action) {
        Item item = new Item();
        item.title.getStyleClass().add("create-menu-title");
        item.hint.getStyleClass().add("create-menu-hint");
        item.hint.setWrapText(true);
        // A wrapped label asks for one line unless told it may have the height
        // its words need, and then cuts them short with an ellipsis.
        item.hint.setMinHeight(Region.USE_PREF_SIZE);
        item.title.setMinHeight(Region.USE_PREF_SIZE);

        StackPane picture = new StackPane(glyph);
        picture.getStyleClass().add("create-menu-glyph");
        picture.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox words = new VBox(1, item.title, item.hint);
        words.setAlignment(Pos.CENTER_LEFT);
        words.setMinWidth(0);
        HBox.setHgrow(words, Priority.ALWAYS);
        HBox content = new HBox(10, picture, words);
        content.setAlignment(Pos.CENTER_LEFT);
        contents.add(content);

        item.entry = new CustomMenuItem(content, true);
        item.entry.setOnAction(event -> action.run());
        menu.getItems().add(item.entry);
        return item;
    }

    /** Adds an entry under a rule: one of another kind than the ones above it. */
    Item addSeparated(Node glyph, Runnable action) {
        menu.getItems().add(new SeparatorMenuItem());
        return add(glyph, action);
    }

    /** Adds an entry that cannot be taken back, drawn in the colour that says so. */
    Item addDanger(Node glyph, Runnable action) {
        Item item = addSeparated(glyph, action);
        item.entry.getStyleClass().add("danger-item");
        return item;
    }

    boolean isShowing() {
        return menu.isShowing();
    }

    /**
     * Opens the menu under this button, or closes it when it is open.
     *
     * @param matchWidth true to make it exactly as wide as the button
     * @param alignRight true to line its right edge up with the button's
     */
    void toggle(Region anchor, boolean matchWidth, boolean alignRight) {
        if (menu.isShowing()) {
            menu.hide();
            return;
        }
        // The press on the button closed the open menu on its way in (a menu
        // closes on any press outside it); the click that follows must not
        // open it again.
        if (System.nanoTime() - hiddenAt < 250_000_000L) {
            return;
        }
        this.anchor = anchor;
        this.matchWidth = matchWidth;
        this.alignRight = alignRight;
        open();
    }

    private void open() {
        sizeContents();
        menu.show(anchor, Side.BOTTOM, 0, GAP);
    }

    private void sizeContents() {
        double width = matchWidth ? Math.max(120, anchor.getWidth() - insets) : -1;
        for (Region content : contents) {
            content.setMinWidth(width > 0 ? width : MIN_ENTRY_WIDTH);
            content.setPrefWidth(width > 0 ? width : Region.USE_COMPUTED_SIZE);
            content.setMaxWidth(width > 0 ? width : Double.MAX_VALUE);
        }
    }

    private void afterShown() {
        if (!(menu.getSkin() != null && menu.getSkin().getNode() instanceof Region root)) {
            return;
        }
        if (alignRight) {
            Bounds button = anchor.localToScreen(anchor.getBoundsInLocal());
            if (button != null) {
                menu.setAnchorX(button.getMaxX() - root.getWidth());
            }
        }
        // The padding comes from the stylesheet and the theme, so it is read,
        // not assumed: measured once, and the menu resized when the guess was
        // off. Later, not from inside this handler: hiding or resizing a popup
        // while it is still being shown throws inside JavaFX.
        if (matchWidth && !measured) {
            measured = true;
            double off = root.getWidth() - anchor.getWidth();
            if (Math.abs(off) >= 0.5) {
                insets += off;
                Platform.runLater(() -> {
                    sizeContents();
                    root.applyCss();
                    root.layout();
                    menu.sizeToScene();
                });
            }
        }
    }
}
