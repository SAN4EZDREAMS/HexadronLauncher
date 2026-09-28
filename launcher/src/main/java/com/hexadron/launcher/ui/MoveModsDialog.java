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
import com.hexadron.launcher.mods.ModUpdates;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * What a change of Minecraft version does to each mod, shown before it is
 * done: the build that replaces it, the same file, or nothing - and then the
 * choice to move the mods, go back to the old version, or leave the folder as
 * it is.
 */
final class MoveModsDialog {

    /** What the dialog asks the main window to do. */
    interface Actions {
        void move();

        void goBack();

        void leave();
    }

    private static final double WIDTH = 680;

    private final String fromVersion;
    private final String toVersion;
    private final List<ModUpdates.MoveRow> plan;
    private final Actions actions;

    MoveModsDialog(String fromVersion, String toVersion, List<ModUpdates.MoveRow> plan, Actions actions) {
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.plan = List.copyOf(plan);
        this.actions = actions;
    }

    void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("mods.move.title", toVersion));
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(16, 20, 8, 20));
        root.setPrefWidth(WIDTH);
        Label heading = new Label(I18n.t("mods.move.title", toVersion));
        heading.getStyleClass().add("section-title");
        root.getChildren().addAll(heading, wrapped(I18n.t("mods.move.body", fromVersion, toVersion), false));

        GridPane table = new GridPane();
        table.setHgap(16);
        table.setVgap(4);
        table.addRow(0, header(I18n.t("mods.move.colMod")), header(I18n.t("mods.move.colNow")),
                header(I18n.t("mods.move.colNext", toVersion)));
        int row = 1;
        for (ModUpdates.MoveRow move : plan) {
            String now = move.mod().version() == null || move.mod().version().isBlank()
                    ? move.mod().fileName() : move.mod().version();
            Label next = switch (move.action()) {
                case REPLACE -> new Label(move.next().displayName());
                case KEEP -> muted(I18n.t("mods.move.same"));
                case SWITCH_OFF -> styled(I18n.t("mods.move.none"), "badge-wrong");
                case UNKNOWN -> muted(I18n.t("mods.move.unknown"));
            };
            table.addRow(row++, new Label(move.title()), muted(now), next);
        }
        ScrollPane scroll = new ScrollPane(table);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(380, 24.0 * (plan.size() + 1) + 8));
        scroll.getStyleClass().add("edge-to-edge");
        root.getChildren().add(scroll);

        long replaced = plan.stream().filter(move -> move.action() == ModUpdates.MoveAction.REPLACE).count();
        long off = plan.stream().filter(move -> move.action() == ModUpdates.MoveAction.SWITCH_OFF).count();
        root.getChildren().add(wrapped(I18n.t("mods.move.summary", replaced, off,
                plan.size() - replaced - off), true));

        ButtonType move = new ButtonType(I18n.t("mods.move.apply"), ButtonBar.ButtonData.OK_DONE);
        ButtonType back = new ButtonType(I18n.t("mods.move.back", fromVersion), ButtonBar.ButtonData.OTHER);
        ButtonType leave = new ButtonType(I18n.t("mods.move.leave"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(move, back, leave);
        dialog.getDialogPane().setContent(root);
        Theme.apply(dialog.getDialogPane());
        dialog.getDialogPane().lookupButton(move).getStyleClass().add("primary");

        dialog.setOnHidden(event -> {
            if (move.equals(dialog.getResult())) {
                actions.move();
            } else if (back.equals(dialog.getResult())) {
                actions.goBack();
            } else {
                actions.leave();
            }
        });
        dialog.show();
    }

    private static Label header(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }

    private static Label styled(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().addAll("badge", style);
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    private static Label wrapped(String text, boolean muted) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(WIDTH - 40);
        if (muted) {
            label.getStyleClass().add("muted");
        }
        return label;
    }
}
