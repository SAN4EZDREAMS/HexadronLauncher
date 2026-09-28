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
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The newer builds of a profile's mods, each with a box to untick, and the
 * way back from the last update.
 *
 * <p>Every box starts ticked: the player asked to see updates, and the likely
 * answer is all of them. The window says where the old files go, because the
 * question a careful player has before pressing the button is whether it can
 * be undone.
 */
final class UpdatesDialog {

    /** What the dialog asks the main window to do; both run off the interface thread there. */
    interface Actions {
        void apply(List<ModUpdates.Update> chosen);

        void rollBack();
    }

    private static final double WIDTH = 600;

    private final String minecraftVersion;
    private final ModUpdates.Check check;
    private final boolean canRollBack;
    private final Actions actions;

    UpdatesDialog(String minecraftVersion, ModUpdates.Check check, boolean canRollBack, Actions actions) {
        this.minecraftVersion = minecraftVersion;
        this.check = check;
        this.canRollBack = canRollBack;
        this.actions = actions;
    }

    void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("mods.updates.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(16, 20, 8, 20));
        root.setPrefWidth(WIDTH);
        Label heading = new Label(check.updates().isEmpty()
                ? I18n.t("mods.updates.upToDate", check.checked())
                : I18n.t("mods.updates.header", check.updates().size(), minecraftVersion));
        heading.getStyleClass().add("section-title");
        heading.setWrapText(true);
        root.getChildren().add(heading);

        Map<CheckBox, ModUpdates.Update> boxes = new LinkedHashMap<>();
        VBox rows = new VBox(6);
        com.hexadron.launcher.mods.ContentKind group = null;
        for (ModUpdates.Update update : check.updates().stream()
                .sorted(java.util.Comparator.comparing(ModUpdates.Update::kind)).toList()) {
            if (update.kind() != group && check.updates().stream().anyMatch(other -> other.kind() != update.kind())) {
                // A heading per kind, when there is more than one.
                Label kind = new Label(I18n.t(update.kind().key()));
                kind.getStyleClass().add("form-label");
                rows.getChildren().add(kind);
            }
            group = update.kind();
            // A pack's "version" is its pack format, a number nobody knows it by; its file name says more.
            String from = update.kind() != com.hexadron.launcher.mods.ContentKind.MOD
                    || update.current().version() == null || update.current().version().isBlank()
                    ? update.current().fileName() : update.current().version();
            CheckBox box = new CheckBox(I18n.t("mods.updates.row", update.title(), from,
                    update.next().displayName()));
            box.setSelected(true);
            box.setWrapText(true);
            box.setMaxWidth(WIDTH - 60);
            rows.getChildren().add(box);
            if (!update.dependencies().isEmpty()) {
                rows.getChildren().add(muted(I18n.t("mods.updates.needs", update.dependencies().size())));
            }
            boxes.put(box, update);
        }
        if (!boxes.isEmpty()) {
            ScrollPane scroll = new ScrollPane(rows);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(Math.min(360, 28.0 * boxes.size() + 8));
            scroll.getStyleClass().add("edge-to-edge");
            root.getChildren().add(scroll);
            root.getChildren().add(muted(I18n.t("mods.updates.note")));
        }
        if (check.unknown() > 0) {
            root.getChildren().add(muted(I18n.t("mods.updates.unknown", check.unknown())));
        }

        ButtonType apply = new ButtonType(I18n.t("mods.updates.apply"), ButtonBar.ButtonData.OK_DONE);
        ButtonType rollBack = new ButtonType(I18n.t("mods.updates.rollback"), ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType(I18n.t("dialog.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        if (!boxes.isEmpty()) {
            dialog.getDialogPane().getButtonTypes().add(apply);
        }
        if (canRollBack) {
            dialog.getDialogPane().getButtonTypes().add(rollBack);
        }
        dialog.getDialogPane().getButtonTypes().add(close);
        dialog.getDialogPane().setContent(root);
        Theme.apply(dialog.getDialogPane());
        if (!boxes.isEmpty()) {
            dialog.getDialogPane().lookupButton(apply).getStyleClass().add("primary");
        }

        dialog.setOnHidden(event -> {
            if (apply.equals(dialog.getResult())) {
                List<ModUpdates.Update> chosen = new ArrayList<>();
                boxes.forEach((box, update) -> {
                    if (box.isSelected()) {
                        chosen.add(update);
                    }
                });
                if (!chosen.isEmpty()) {
                    actions.apply(chosen);
                }
            } else if (rollBack.equals(dialog.getResult())) {
                actions.rollBack();
            }
        });
        dialog.show();
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(WIDTH - 40);
        label.getStyleClass().add("muted");
        return label;
    }
}
