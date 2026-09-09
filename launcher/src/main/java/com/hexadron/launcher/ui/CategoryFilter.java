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
import com.hexadron.launcher.mods.ContentKind;
import com.hexadron.launcher.mods.ModCategory;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * "What is it for": the category filter, for one kind of thing.
 *
 * <h2>Why it is a class of its own</h2>
 *
 * <p>It was written inside the mods panel, because for a while mods were the
 * only thing with categories. Modpacks and data packs have them too - a
 * different list each, which is the whole reason {@link ModCategory} now knows
 * which kind it files - and a second copy of this menu would have been a second
 * place for the two-column layout, the "stay open while boxes are ticked" rule
 * and the language rebuild to be got right. Three copies would have been three.
 *
 * <p>So one widget, told which kind it is filtering and what to do when the
 * ticks change. It holds the ticked set itself, because the rows of a list ask
 * for it on every repaint to put the ticked categories first.
 *
 * <h2>A menu of tick boxes, not a list that picks one</h2>
 *
 * <p>A thing is filed under several categories at once, and somebody narrowing a
 * search usually means more than one of them - "adventure and magic", not
 * "adventure, and now start again with magic". The menu also stays open while
 * they are ticked, so choosing four is four clicks rather than four round trips.
 */
final class CategoryFilter {

    private final ContentKind kind;

    /**
     * Where the drawings come from.
     *
     * <p>A supplier rather than a value: they arrive from Modrinth a moment
     * after the window opens, and the menu is built before that.
     */
    private final Supplier<Categories> art;

    /** What to do once the ticks have changed - a fresh search, always. */
    private final Runnable onChanged;

    private final MenuButton button = new MenuButton();
    private final Button clear = new Button();

    private final Set<ModCategory> chosen = EnumSet.noneOf(ModCategory.class);

    /** The boxes themselves, so "clear all" can untick them without rebuilding. */
    private final Map<ModCategory, CheckBox> boxes = new EnumMap<>(ModCategory.class);

    CategoryFilter(ContentKind kind, Supplier<Categories> art, Runnable onChanged) {
        this.kind = kind;
        this.art = art;
        this.onChanged = onChanged;
        button.setPrefWidth(170);
        build();
    }

    MenuButton node() {
        return button;
    }

    /**
     * The ticked categories, as a live view.
     *
     * <p>Handed to the rows as something to read on every repaint, so it is the
     * set itself rather than a copy.
     */
    Set<ModCategory> chosen() {
        return chosen;
    }

    /** The ticked categories in the platform's own order rather than the menu's. */
    List<ModCategory> forSearch() {
        List<ModCategory> ordered = new ArrayList<>();
        for (ModCategory category : ModCategory.values()) {
            if (chosen.contains(category)) {
                ordered.add(category);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * Fills the menu.
     *
     * <p>Rebuilt rather than updated when the language changes or the drawings
     * arrive, because both change every item in it and a menu of this size is
     * cheaper to build than to reconcile. The ticks survive it: they live in
     * {@link #chosen}, not in the boxes.
     */
    void build() {
        boxes.clear();

        // Every category on screen at once, in two columns.
        //
        // The panel this replaces was one column in a scroller, and a scroller
        // is a thing that has to be discovered: half the list was below the edge
        // with nothing but a thin bar to say so, and somebody looking for
        // "Технології" saw a list that stopped at "Оптимізація". Two columns is
        // the shape that fits the whole list in a panel shorter than the window
        // it drops out of, so the list is read rather than scrolled.
        //
        // Down the first column, then the second, because a list in reading
        // order is read down, not across.
        List<ModCategory> ordered = Categories.inReadingOrder(kind);
        int rows = (ordered.size() + 1) / 2;

        GridPane list = new GridPane();
        list.getStyleClass().add("category-list");
        list.setHgap(14);
        list.setVgap(2);
        int placed = 0;
        for (ModCategory category : ordered) {
            CheckBox box = new CheckBox(Categories.name(category));
            box.setSelected(chosen.contains(category));
            box.setGraphic(art.get().icon(category, 14));
            box.setMaxWidth(Double.MAX_VALUE);
            // As wide as its name, never narrower. A row that may stretch to
            // fill its column may also be squeezed into it, and a squeezed name
            // is not a name with less space around it: it is a name with its
            // last two letters replaced by an ellipsis. The column widens to the
            // longest name instead.
            box.setMinWidth(Region.USE_PREF_SIZE);
            box.setOnAction(event -> {
                if (box.isSelected()) {
                    chosen.add(category);
                } else {
                    chosen.remove(category);
                }
                updateLabel();
                onChanged.run();
            });
            boxes.put(category, box);
            list.add(box, placed / rows, placed % rows);
            placed++;
        }

        clear.setText(I18n.t("mods.category.clear"));
        clear.setMaxWidth(Double.MAX_VALUE);
        clear.getStyleClass().add("category-clear");
        clear.setOnAction(event -> {
            if (chosen.isEmpty()) {
                return;
            }
            chosen.clear();
            // The boxes are unticked rather than the panel rebuilt: this runs
            // from inside the popup that holds them, and replacing what a menu
            // is showing while it delivers an event to it is not a thing to do
            // for the sake of saving a loop.
            boxes.values().forEach(box -> box.setSelected(false));
            updateLabel();
            onChanged.run();
        });

        VBox panel = new VBox(6, clear, list);
        panel.getStyleClass().add("category-panel");

        CustomMenuItem item = new CustomMenuItem(panel);
        // One item holding the whole panel means the menu's own highlight is the
        // whole panel: the pointer anywhere inside lit every row at once. The
        // stylesheet turns that highlight off for this item, and each row lights
        // itself instead.
        item.getStyleClass().add("category-item");
        // The popup stays up while boxes are ticked: choosing four categories
        // should be four clicks, not four times opening the same menu.
        item.setHideOnClick(false);
        button.getItems().setAll(item);
        updateLabel();
    }

    private void updateLabel() {
        button.setText(chosen.isEmpty()
                ? I18n.t("mods.category.any")
                : I18n.t("mods.category.some", chosen.size()));
        clear.setDisable(chosen.isEmpty());
    }
}
