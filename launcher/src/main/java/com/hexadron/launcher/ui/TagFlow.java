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

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.mods.ModCategory;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * One line of category chips, with the ones that do not fit behind a count.
 *
 * <h2>Why this is not just a box of labels</h2>
 *
 * <p>Because a mod is filed under as many categories as its author chose - seven
 * is common - and a row in a list is one line wide. A plain box lays them all
 * out and lets the cell clip whatever runs past the edge, which is the worst of
 * the three possible answers: the reader cannot tell a mod with three categories
 * from one with seven, and there is nothing on screen to suggest they are
 * missing. The other two answers are to wrap onto more lines, which makes rows
 * different heights again, or to say how many are hidden and offer them. This is
 * the third.
 *
 * <h2>How many fit</h2>
 *
 * <p>Worked out at layout, from the width the row actually got, because that is
 * the only moment it is known. The count on the overflow chip changes its own
 * width - "+2" is narrower than "+12" - so the fit is tried from the outside in
 * until one holds, which for a handful of chips is a handful of comparisons and
 * is done once per resize rather than once per frame.
 *
 * <h2>Reused, never rebuilt</h2>
 *
 * <p>The chips are kept and re-pointed. A fresh label is a node created during
 * the list's own layout - after CSS has run for that frame - so it is measured
 * once without its padding and font and corrected afterwards, which is a visible
 * flicker on every row as the selection moves. A slot already showing the right
 * category is not touched at all.
 */
final class TagFlow extends Pane {

    /** The space between two chips. */
    private static final double GAP = 4;

    private final double size;

    private final List<Label> chips = new ArrayList<>();
    /** What each chip is currently showing, so an unchanged one is left alone. */
    private final List<ModCategory> chipShown = new ArrayList<>();

    private final Label more = new Label();
    private final Tooltip rest = new Tooltip();
    private final VBox restPanel = new VBox(GAP);

    private List<ModCategory> categories = List.of();
    private Categories art;

    /** The hidden set the panel behind the count was last built for. */
    private List<ModCategory> panelShows = List.of();

    TagFlow(double height, double iconSize) {
        this.size = iconSize;
        setMinHeight(height);
        setPrefHeight(height);
        setMaxHeight(height);
        setMinWidth(0);

        more.getStyleClass().addAll("mod-tag", "mod-tag-more");
        more.setManaged(false);
        more.setVisible(false);
        getChildren().add(more);

        // One category to a line, and each line only as wide as its own words.
        // The panel this replaces wrapped at a fixed three hundred points, and a
        // wrap length is a width a box keeps whether or not anything in it needs
        // it: hiding one category behind the count opened a panel wide enough
        // for seven of them with a single chip in the corner. A column of rows
        // has no width of its own - it is as wide as its widest chip - so the
        // panel is the size of what is in it however much that is.
        restPanel.setAlignment(Pos.CENTER_LEFT);
        restPanel.setFillWidth(false);
        restPanel.getStyleClass().add("tag-popup");

        rest.setGraphic(restPanel);
        rest.getStyleClass().add("tag-tooltip");
        // Quick to appear and quick to go, and no time limit in between: this is
        // a list being read, not a hint being waited for.
        rest.setShowDelay(Duration.millis(120));
        rest.setHideDelay(Duration.millis(80));
        rest.setShowDuration(Duration.INDEFINITE);
        more.setTooltip(rest);
    }

    /**
     * Points the line at a mod's categories.
     *
     * @param shown the categories, already in the order they should appear
     * @param art   the names and drawings to use
     */
    void show(List<ModCategory> shown, Categories art) {
        this.art = art;
        this.categories = shown;

        while (chips.size() < shown.size()) {
            Label chip = new Label();
            chip.getStyleClass().add("mod-tag");
            chip.setGraphicTextGap(GAP);
            chip.setManaged(false);
            chips.add(chip);
            chipShown.add(null);
            getChildren().add(chips.size() - 1, chip);
        }
        for (int i = 0; i < chips.size(); i++) {
            ModCategory wanted = i < shown.size() ? shown.get(i) : null;
            if (chipShown.get(i) == wanted) {
                continue;
            }
            chipShown.set(i, wanted);
            Label chip = chips.get(i);
            if (wanted == null) {
                chip.setVisible(false);
                continue;
            }
            chip.setText(Categories.name(wanted));
            chip.setGraphic(art.icon(wanted, size));
        }
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        int count = categories.size();

        // Rounded up, always. A chip given a fraction of a point less than the
        // width it asked for does not lose a fraction of a letter: it drops the
        // last two and puts an ellipsis where they were.
        double[] widths = new double[count];
        double total = 0;
        for (int i = 0; i < count; i++) {
            widths[i] = Math.ceil(chips.get(i).prefWidth(-1));
            total += widths[i] + (i > 0 ? GAP : 0);
        }

        int visible = count;
        if (total > width) {
            // From the outside in: one fewer chip each time, until what is left
            // plus the count that replaces the rest fits the width there is.
            for (visible = count - 1; visible > 0; visible--) {
                more.setText(countText(count - visible));
                double needed = 0;
                for (int i = 0; i < visible; i++) {
                    needed += widths[i] + (i > 0 ? GAP : 0);
                }
                needed += GAP + Math.ceil(more.prefWidth(-1));
                if (needed <= width) {
                    break;
                }
            }
        }

        double x = 0;
        for (int i = 0; i < chips.size(); i++) {
            Label chip = chips.get(i);
            boolean on = i < visible;
            chip.setVisible(on);
            if (on) {
                chip.resizeRelocate(x, 0, widths[i], height);
                x += widths[i] + GAP;
            }
        }

        boolean overflowing = visible < count;
        more.setVisible(overflowing);
        if (!overflowing) {
            return;
        }
        more.setText(countText(count - visible));
        more.resizeRelocate(x, 0, Math.ceil(more.prefWidth(-1)), height);
        fillPanel(categories.subList(visible, count));
    }

    private static String countText(int hidden) {
        return "+" + hidden;
    }

    /**
     * Fills the panel behind the count.
     *
     * <p>Only when the hidden set has changed. Rebuilding it every time the row
     * is laid out would be a handful of nodes created during a layout pass, for
     * a panel nobody is looking at.
     */
    private void fillPanel(List<ModCategory> hidden) {
        if (hidden.equals(panelShows)) {
            return;
        }
        panelShows = List.copyOf(hidden);
        restPanel.getChildren().clear();
        for (ModCategory category : hidden) {
            Label chip = new Label(Categories.name(category));
            chip.getStyleClass().add("mod-tag");
            chip.setGraphicTextGap(GAP);
            chip.setGraphic(art.icon(category, size));
            restPanel.getChildren().add(chip);
        }
        more.setAccessibleText(I18n.t("mods.category.more", hidden.size()));
    }
}
