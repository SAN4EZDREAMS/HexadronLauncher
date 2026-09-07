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

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.mods.ModCategory;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The parts every row in the content window is built from, and the sizing rules
 * that keep it in one piece.
 *
 * <h2>Why this is shared</h2>
 *
 * <p>A search hit, an installed mod, a modpack and a data pack are the same
 * object to the person reading the list - a thing, with a logo, a name, a line
 * about it and something to press - so they are laid out by the same code. When a
 * hit and an installed mod were two hand-built rows they drifted: only one of
 * them had the link to the project's page, and only one of them survived a long
 * name. Four kinds of row would drift four ways.
 *
 * <p>It is a top-level class rather than an inner one for the same reason. The
 * sections are separate classes, and a base that lived inside one of them would
 * be the one section's row that the others copied.
 *
 * <h2>The sizing rules, which are the whole point</h2>
 *
 * <p>A row is a horizontal box in a list cell, and a list cell clips. So every
 * part of it has to say explicitly whether it may grow, whether it may shrink,
 * and what it does when the text is longer than the space:
 *
 * <ul>
 *   <li>the text column is the only part that grows, and it is allowed to shrink
 *       to nothing ({@code setMinWidth(0)}). Without that, a long description
 *       sets a minimum width for the whole row, the buttons are pushed past the
 *       right edge of the cell, and they are simply not there any more - which is
 *       exactly what a mod with a long name or a long summary did;</li>
 *   <li>every line of that column is a single line that ends in an ellipsis
 *       rather than wrapping. A wrapped label's height depends on its width,
 *       which in a cell that is itself being measured is a layout that argues
 *       with itself;</li>
 *   <li>the badge and the buttons never shrink ({@code USE_PREF_SIZE} as a
 *       minimum). They are the part of the row that has to be reachable, so they
 *       are the part that keeps its size and the text gives way instead;</li>
 *   <li>the cell asks for no width of its own, so the list never grows a
 *       horizontal scroll bar to fit its longest row.</li>
 * </ul>
 */
abstract class ContentRow<T> extends ListCell<T> {

    /** The height of the category line, empty or not. See {@link #tags}. */
    private static final double TAG_HEIGHT = 18;

    protected final ModIcons.Tile icon = new ModIcons.Tile(40);
    protected final Label name = new Label();
    protected final Label meta = new Label();
    protected final Label description = new Label();

    /**
     * The link to the project's page.
     *
     * <p>Under the text and set small, rather than out beside the buttons. It
     * opens something outside the launcher, so it is the one thing in the row
     * that must not be hit by accident on the way to Remove - and a quiet line of
     * small text under a description is read as a link and not as a target.
     */
    protected final Hyperlink page = new Hyperlink();

    /**
     * What the thing is for, as the platform files it.
     *
     * <p>Its own line, and always the same height whether or not there is
     * anything on it. A row that grew a line when a mod happened to have
     * categories would put the list back to the ladder of uneven heights it was
     * before.
     */
    protected final TagFlow tags = new TagFlow(TAG_HEIGHT, 11);

    /** Everything to the right of the text: filled by the subclass, never shrunk. */
    protected final HBox actions = new HBox(6);

    private final VBox text = new VBox(1, name, meta, description, tags, page);
    private final HBox row;

    /**
     * Where the drawings and the ticked filter come from.
     *
     * <p>Suppliers rather than values: the drawings arrive from Modrinth a moment
     * after the window opens and the ticked categories change as the user clicks,
     * and a cell built once at the start of a list must see both.
     */
    private final Supplier<Categories> art;
    private final Supplier<Set<ModCategory>> highlighted;

    ContentRow(Supplier<Categories> art, Supplier<Set<ModCategory>> highlighted) {
        this.art = art;
        this.highlighted = highlighted;

        name.getStyleClass().add("instance-name");
        meta.getStyleClass().add("instance-subtitle");
        description.getStyleClass().add("instance-subtitle");
        page.getStyleClass().add("mod-link");

        // Filling the column is what makes the ellipsis appear: a label only
        // shortens its text when something has told it how wide it is.
        for (Label label : new Label[]{name, meta, description}) {
            label.setWrapText(false);
            label.setMaxWidth(Double.MAX_VALUE);
            label.setMinWidth(0);
        }
        // The link is the exception, and deliberately so. Stretched to the column
        // it would be a full-width click target sitting directly above Remove;
        // left at its own width it is only clickable where the words are.
        page.setMaxWidth(Region.USE_PREF_SIZE);
        page.setAlignment(Pos.CENTER_LEFT);

        text.setFillWidth(true);
        text.setMinWidth(0);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);

        row = new HBox(12, icon, text, actions);
        row.setAlignment(Pos.CENTER_LEFT);

        // No preferred width of its own: the cell is as wide as the list, and
        // anything longer is the text column's problem to ellipsise.
        setPrefWidth(0);
    }

    /**
     * Writes one line, and keeps its space whether or not there is one.
     *
     * <p>Hidden rather than unmanaged, and that is the whole of the "the list
     * goes uneven" bug. A row whose project published no description, or no page,
     * used to be one line shorter than the row above it, so a list of forty was a
     * ladder of four different row heights that changed again as logos arrived.
     * Every row now reserves the same lines and leaves the ones it has nothing
     * for blank.
     */
    protected static void line(Label label, String value) {
        boolean present = value != null && !value.isBlank();
        label.setText(present ? value : "");
        label.setVisible(present);
    }

    /**
     * Writes the category line.
     *
     * <p>All of them, in the order the reader is most likely to be looking for.
     * Whatever they ticked in the filter comes first: somebody who has narrowed a
     * search to two categories is scanning for those two, and finding them behind
     * a count that has to be hovered would answer the question they asked with an
     * extra step.
     */
    protected void tags(List<ModCategory> shown) {
        tags.show(ModCategory.chosenFirst(shown, highlighted.get()), art.get());
    }

    /** Points the link at a page, or leaves its line blank. */
    protected void link(String url, Runnable action) {
        boolean present = SystemBrowser.isWebPage(url);
        page.setText(present ? I18n.t("mods.details") : "");
        page.setVisible(present);
        page.setOnAction(event -> action.run());
    }

    /**
     * Adds or removes a style class, and only when it is not already right.
     *
     * <p>The unconditional {@code removeAll} then {@code add} that used to be
     * here is what made the badge and the buttons jump for one frame every time
     * the selection moved. {@link ListCell#updateItem} is called from the list's
     * own layout pass - after CSS has been applied for that frame - so a style
     * class changed there is not resolved until the next one. The row is
     * therefore measured once with the old padding, border and weight, drawn in
     * the wrong place, and corrected a frame later. {@code .badge-off} adds a
     * one-pixel border and {@code .badge-wrong} makes the text bold, so "the old
     * values" really are a different width.
     *
     * <p>Checking first makes the common case - scrolling or clicking through
     * rows whose badges are the same kind - touch nothing at all, and there is
     * nothing to resolve late.
     */
    protected static void styleClass(Node node, String name, boolean wanted) {
        if (node.getStyleClass().contains(name) == wanted) {
            return;
        }
        if (wanted) {
            node.getStyleClass().add(name);
        } else {
            node.getStyleClass().remove(name);
        }
    }

    /**
     * Points a control at its tooltip, or takes it away.
     *
     * <p>One tooltip per cell, reused. A fresh one on every update is a node and
     * a listener allocated for every row the eye passes over, thrown away unread.
     */
    protected static void tooltip(Control control, Tooltip tip, String text) {
        if (text == null) {
            if (control.getTooltip() != null) {
                control.setTooltip(null);
            }
            return;
        }
        if (!text.equals(tip.getText())) {
            tip.setText(text);
        }
        if (control.getTooltip() != tip) {
            control.setTooltip(tip);
        }
    }

    /** Puts the assembled row on screen. Called at the end of every update. */
    protected void showRow() {
        setGraphic(row);
    }

    protected void clearRow() {
        setGraphic(null);
        setText(null);
    }

    /** 1234567 -> "1.2M". Exact counts are noise at this scale. */
    static String formatCount(long value) {
        if (value >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.1fk", value / 1_000.0);
        }
        return Long.toString(value);
    }
}
