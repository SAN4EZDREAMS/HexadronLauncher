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

import com.hexadron.launcher.core.LauncherSettings;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.profile.ProfileLayout;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The one place a group is set up, for both creating and editing.
 *
 * <h2>Why a colour picker at all</h2>
 *
 * <p>In the grid a group has no heading - a cell has a fixed place and there is
 * no room for one - so its colour is the only thing that says which band is
 * which. That makes the colour a property of the group in the same way its name
 * is, and a property somebody has to live with is a property they should be able
 * to choose. It was assigned and unchangeable, so two groups whose colours read
 * alike on a particular screen stayed that way.
 *
 * <h2>A palette, and then anything</h2>
 *
 * <p>The fixed palette is the fast path: sixteen colours that are known to work
 * as a band behind the cells and as a plate with white text on it, so the usual
 * case is one click. Below it, under a rule of its own, is the shelf of colours
 * this user mixed and the button that mixes another - because a fixed palette is
 * a guess about what somebody's arrangement means, and a launcher has no
 * business telling a user that their eight servers may not each have the colour
 * they think of them in.
 *
 * <p>The two are separated rather than run together. Sixteen squares followed by
 * more squares is one long row in which nothing says where the launcher's
 * suggestions stop and the user's own colours start - and only the second half
 * can be taken away again, so the boundary has to be visible before anybody
 * right-clicks looking for it.
 *
 * <p>What is mixed there is kept - in {@link LauncherSettings}, not on the group
 * - and offered on every later group as an extra swatch. A colour that has to be
 * mixed again each time it is wanted is a colour that gets used once.
 *
 * <h2>Save writes, Cancel writes nothing</h2>
 *
 * <p>The same rule as the instance editor. The dialog hands back what was chosen
 * and touches no group state itself, so a cancelled edit cannot have
 * half-applied a rename.
 *
 * <p>The shelf of mixed colours is the one exception, and deliberately: it is
 * not part of the group being edited. Mixing a colour, deciding it is wrong for
 * this group and pressing Cancel should still leave the colour on the shelf,
 * because the work of mixing it was done either way.
 */
public final class GroupDialog {

    /** Size of a swatch, in pixels. */
    private static final double SWATCH = 26;

    /** What the dialog came back with. */
    public record Choice(String name, String color) {
    }

    /** Where mixed colours are kept between dialogs. Null means they are not. */
    private final LauncherSettings settings;

    /** Writes {@link #settings} to disk. Null means the caller does not care. */
    private final Runnable persist;

    /**
     * How many swatches fit on a row, and so how wide the rows are.
     *
     * <p>Fixed rather than left to the pane, because a {@link FlowPane} works out
     * its preferred height from {@code prefWrapLength} and then lays out at
     * whatever width it is actually given. When the two differ the dialog is
     * sized for one number of rows and drawn with another, which is how the
     * buttons ended up under the bottom edge.
     */
    private static final int PER_ROW = 11;

    private static final double GAP = 6;
    private static final double ROW_WIDTH = SWATCH * PER_ROW + GAP * (PER_ROW - 1);

    private final TextField nameField = new TextField();

    /** The fixed palette. */
    private final FlowPane fixed = new FlowPane(GAP, GAP);

    /** The colours this user mixed, and the button that mixes another. */
    private final FlowPane mine = new FlowPane(GAP, GAP);

    /**
     * The dialog itself, so a row of swatches appearing or disappearing can grow
     * the window with it. It is not resizable, and a fixed-size window does not
     * re-fit itself around content that changed after it opened.
     */
    private Dialog<ButtonType> dialog;

    /** Owner of the colour chooser this dialog opens. */
    private Window owner;

    private String chosen;

    /**
     * The group's own colour when it is neither in the palette nor on the shelf.
     *
     * <p>Kept as a swatch of its own so that an arrangement written by an older
     * version, or edited by hand, does not lose its colour the moment somebody
     * opens this dialog to change the name.
     */
    private String inherited;

    /** A dialog with no memory: the palette only, nothing kept between openings. */
    public GroupDialog() {
        this(null, null);
    }

    /**
     * @param settings where mixed colours are remembered, or null for none
     * @param persist  called after the shelf changes, to write the settings out
     */
    public GroupDialog(LauncherSettings settings, Runnable persist) {
        this.settings = settings;
        this.persist = persist;
    }

    /**
     * Opens the dialog.
     *
     * @param name  the name to start from, or null when creating
     * @param color the colour to start from; when it is neither in the palette
     *              nor on the shelf it is offered as an extra swatch
     * @return the name and colour when Save was pressed, empty when cancelled
     */
    public Optional<Choice> show(Window owner, String name, String color) {
        this.owner = owner;
        dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("groups.settings.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(false);

        ButtonType save = new ButtonType(I18n.t("dialog.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(save, cancel);
        dialog.getDialogPane().setContent(buildForm(name, color));
        dialog.getDialogPane().setPrefWidth(520);
        Theme.apply(dialog.getDialogPane());

        // The name is what somebody came here to type, in the usual case.
        javafx.application.Platform.runLater(() -> {
            nameField.requestFocus();
            nameField.selectAll();
        });

        if (dialog.showAndWait().filter(button -> button == save).isEmpty()) {
            return Optional.empty();
        }
        String typed = nameField.getText() == null ? "" : nameField.getText().trim();
        if (typed.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Choice(typed, chosen));
    }

    private GridPane buildForm(String name, String color) {
        chosen = color != null ? color.toLowerCase(Locale.ROOT)
                : ProfileLayout.palette().get(0);
        inherited = offered().contains(chosen) ? null : chosen;

        nameField.setText(name == null ? I18n.t("groups.new.default") : name);
        nameField.setPromptText(I18n.t("groups.name.prompt"));

        for (FlowPane pane : List.of(fixed, mine)) {
            pane.setAlignment(Pos.CENTER_LEFT);
            // Wraps rather than scrolls: every colour has to be visible at once
            // for this to be a palette instead of a list to be paged through.
            pane.setPrefWrapLength(ROW_WIDTH);
            pane.setMaxWidth(ROW_WIDTH);
        }
        rebuildSwatches();

        Separator rule = new Separator();
        rule.setMaxWidth(ROW_WIDTH);

        Label mineTitle = new Label(I18n.t("groups.color.mine"));
        mineTitle.getStyleClass().add("muted");

        VBox colors = new VBox(10, fixed, rule, mineTitle, mine);
        colors.setMaxWidth(ROW_WIDTH);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form");
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(18, 18, 8, 18));

        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(110);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);

        grid.addRow(0, formLabel(I18n.t("groups.name")), nameField);
        grid.addRow(1, formLabel(I18n.t("groups.color")), colors);
        return grid;
    }

    /** Every colour on offer, in the order it is shown, without the mixer. */
    private List<String> offered() {
        List<String> all = new ArrayList<>(ProfileLayout.palette());
        all.addAll(customColors());
        return all;
    }

    private List<String> customColors() {
        return settings == null ? List.of() : settings.customGroupColors();
    }

    /**
     * Redraws both rows.
     *
     * <p>Wholesale rather than by touching the one swatch that changed: there are
     * under forty children, and every way of doing it in place has to keep the
     * selection ring, the shelf and the order in step by hand.
     */
    private void rebuildSwatches() {
        fixed.getChildren().clear();
        if (inherited != null) {
            fixed.getChildren().add(swatch(inherited, false));
        }
        for (String candidate : ProfileLayout.palette()) {
            fixed.getChildren().add(swatch(candidate, false));
        }

        mine.getChildren().clear();
        for (String candidate : customColors()) {
            mine.getChildren().add(swatch(candidate, true));
        }
        mine.getChildren().add(mixer());

        refit();
    }

    /**
     * Grows the window around the swatches after they change.
     *
     * <p>Deferred by one pulse: the pane being measured has only just been handed
     * its children, and the size that matters is the one after the next layout
     * rather than the one still on the pane now.
     */
    private void refit() {
        if (dialog == null || dialog.getDialogPane().getScene() == null) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            Window window = dialog.getDialogPane().getScene().getWindow();
            if (window != null) {
                window.sizeToScene();
            }
        });
    }

    /**
     * One colour.
     *
     * <p>The chosen one is marked with a style class rather than a border colour
     * written into the same inline style as the fill: the fill has to be inline
     * because it is data, and an inline border would then be unable to change on
     * selection without rewriting the fill with it.
     *
     * @param mixed whether this one came off the shelf, and so can be forgotten
     */
    private Region swatch(String color, boolean mixed) {
        Region region = new Region();
        region.getStyleClass().add("swatch");
        region.setStyle("-fx-background-color: " + color + ";");
        region.setMinSize(SWATCH, SWATCH);
        region.setPrefSize(SWATCH, SWATCH);
        region.setMaxSize(SWATCH, SWATCH);
        Tooltip.install(region, new Tooltip(mixed
                ? color + "  ·  " + I18n.t("groups.color.mixed") : color));
        if (color.equalsIgnoreCase(chosen)) {
            region.getStyleClass().add("swatch-chosen");
        }
        region.setOnMouseClicked(event -> {
            chosen = color;
            rebuildSwatches();
            event.consume();
        });
        if (mixed) {
            // Only mixed colours can be taken off: the palette is the thing that
            // is always there, and a user who removed half of it would have a
            // dialog that no longer matches the one in the next screenshot.
            MenuItem forget = new MenuItem(I18n.t("groups.color.forget"));
            forget.setOnAction(event -> forget(color));
            ContextMenu menu = new ContextMenu(forget);
            region.setOnContextMenuRequested(event -> {
                ProfileMenu.show(menu, region, event.getScreenX(), event.getScreenY());
                event.consume();
            });
        }
        return region;
    }

    private void forget(String color) {
        if (settings == null || !settings.removeCustomGroupColor(color)) {
            return;
        }
        // A group painted with it keeps it, so the colour that is still selected
        // has to stay on screen - as the inherited swatch, where it was before
        // it was put on the shelf.
        if (color.equalsIgnoreCase(chosen)) {
            inherited = chosen;
        }
        save();
        rebuildSwatches();
    }

    /**
     * The "+" at the end of the shelf: opens the launcher's colour chooser.
     *
     * <p>The same square as a swatch, dashed and empty, so it reads as one more
     * place a colour can come from rather than as a control of another kind
     * dropped at the end of the row.
     */
    private Region mixer() {
        Label plus = new Label("+");
        plus.getStyleClass().add("swatch-add-mark");

        StackPane button = new StackPane(plus);
        button.getStyleClass().addAll("swatch", "swatch-add");
        button.setMinSize(SWATCH, SWATCH);
        button.setPrefSize(SWATCH, SWATCH);
        button.setMaxSize(SWATCH, SWATCH);
        Tooltip.install(button, new Tooltip(I18n.t("groups.color.custom")));
        button.setOnMouseClicked(event -> {
            new ColorChooserDialog().show(window(), chosen).ifPresent(this::mixed);
            event.consume();
        });
        return button;
    }

    /** The window the chooser belongs in front of: this dialog, or its owner. */
    private Window window() {
        if (dialog != null && dialog.getDialogPane().getScene() != null
                && dialog.getDialogPane().getScene().getWindow() != null) {
            return dialog.getDialogPane().getScene().getWindow();
        }
        return owner;
    }

    /**
     * Takes a colour from the chooser: selects it, and puts it on the shelf.
     *
     * <p>Only what OK was pressed on gets this far. The platform picker this
     * replaced fired on every change inside itself, so a minute of moving one
     * slider filled the shelf with near-identical colours and pushed out
     * everything that had been mixed on purpose.
     */
    private void mixed(String hex) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) {
            return;
        }
        chosen = hex;
        if (settings != null && settings.addCustomGroupColor(hex)) {
            save();
            // On the shelf now, so it no longer needs the inherited slot - and
            // would otherwise be drawn twice.
            if (hex.equals(inherited)) {
                inherited = null;
            }
        } else if (!offered().contains(hex)) {
            inherited = hex;
        }
        rebuildSwatches();
    }

    private void save() {
        if (persist != null) {
            persist.run();
        }
    }

    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }
}
