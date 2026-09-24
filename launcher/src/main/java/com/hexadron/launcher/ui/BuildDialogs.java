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
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.share.BuildExport;
import com.hexadron.launcher.share.BuildImport;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * The three questions an export or an import asks.
 *
 * <p>Each hands back what was chosen and touches no state, the same rule as the
 * instance and group editors: a cancelled dialog cannot leave half a change
 * behind.
 */
final class BuildDialogs {

    /** How many custom files the question names before it says "and N more". */
    private static final int LISTED = 15;

    private BuildDialogs() {
    }

    /**
     * What goes into an export.
     *
     * @param worlds how many worlds the instance has; the box is off when none
     */
    static Optional<BuildExport.Options> exportOptions(Window owner, Profile profile, int worlds) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("build.export.title"));
        dialog.setHeaderText(I18n.t("build.export.header", profile.name()));

        BuildExport.Options defaults = BuildExport.Options.defaults();
        CheckBox mods = box("build.export.mods", defaults.mods());
        CheckBox resourcePacks = box("build.export.resourcePacks", defaults.resourcePacks());
        CheckBox shaders = box("build.export.shaders", defaults.shaders());
        CheckBox settings = box("build.export.settings", defaults.settings());
        CheckBox worldBox = new CheckBox(worlds > 0
                ? I18n.t("build.export.worlds", worlds)
                : I18n.t("build.export.worlds.none"));
        worldBox.setSelected(defaults.worlds() && worlds > 0);
        worldBox.setDisable(worlds == 0);

        Label note = new Label(I18n.t("build.export.note"));
        note.setWrapText(true);
        note.getStyleClass().add("muted");

        VBox content = new VBox(10, mods, resourcePacks, shaders, settings, worldBox, note);
        content.setPadding(new Insets(16, 18, 8, 18));

        ButtonType next = new ButtonType(I18n.t("dialog.next"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(next, cancel);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(560);
        Theme.apply(dialog.getDialogPane());

        if (dialog.showAndWait().filter(button -> button == next).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BuildExport.Options(mods.isSelected(), resourcePacks.isSelected(),
                shaders.isSelected(), settings.isSelected(),
                worldBox.isSelected() && !worldBox.isDisabled()));
    }

    /**
     * The question about the player's own files.
     *
     * @param titles    what to call each one
     * @param bytes     their total size, or a negative number when unknown
     * @param exporting true for the export wording, false for the import one
     * @return true for yes, false for no, empty for cancel
     */
    static Optional<Boolean> askAboutCustom(Window owner, List<String> titles, long bytes,
                                            boolean exporting) {
        StringBuilder list = new StringBuilder();
        titles.stream().limit(LISTED).forEach(title -> list.append("  • ").append(title).append('\n'));
        if (titles.size() > LISTED) {
            list.append("  ").append(I18n.t("build.custom.more", titles.size() - LISTED)).append('\n');
        }

        ButtonType yes = new ButtonType(I18n.t("dialog.yes"), ButtonBar.ButtonData.YES);
        ButtonType no = new ButtonType(I18n.t("dialog.no"), ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.t(exporting ? "build.custom.export.body" : "build.custom.import.body",
                        titles.size(), bytes < 0 ? "?" : size(bytes), list.toString().stripTrailing()),
                yes, no, cancel);
        alert.initOwner(owner);
        Theme.apply(alert.getDialogPane());
        alert.setTitle(I18n.t("build.custom.title"));
        alert.setHeaderText(I18n.t(exporting ? "build.custom.export.header" : "build.custom.import.header"));
        alert.getDialogPane().setPrefWidth(620);

        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isEmpty() || answer.get() == cancel) {
            return Optional.empty();
        }
        return Optional.of(answer.get() == yes);
    }

    /**
     * What an import makes.
     *
     * @param name          the name the build suggests, already made unique
     * @param withArguments whether the build's launch arguments are used
     */
    record ImportChoice(String name, boolean withArguments) {
    }

    static Optional<ImportChoice> importOptions(Window owner, BuildImport build) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("build.import.title"));
        dialog.setHeaderText(I18n.t("build.import.header", build.archive().getFileName().toString()));

        TextField name = new TextField(build.name());
        Label nameLabel = new Label(I18n.t("build.import.name"));
        nameLabel.getStyleClass().add("form-label");

        Label summary = new Label(I18n.t("build.import.summary",
                build.minecraftVersion(),
                build.loader().displayName()
                        + (build.loaderVersion() == null ? "" : " " + build.loaderVersion()),
                build.remoteCount(), build.extrasCount() + build.bundledCustom().size()));
        summary.setWrapText(true);

        VBox content = new VBox(10, nameLabel, name, summary);
        if (build.modpackCount() > 0) {
            content.getChildren().add(new Label(I18n.t("build.import.modpacks", build.modpackCount())));
        }
        if (build.generator() != null) {
            Label from = new Label(I18n.t("build.import.from", build.generator()));
            from.getStyleClass().add("muted");
            content.getChildren().add(from);
        }

        CheckBox arguments = new CheckBox(I18n.t("build.import.arguments"));
        if (build.hasArguments()) {
            List<String> all = new java.util.ArrayList<>(build.jvmArguments());
            all.addAll(build.gameArguments());
            Label warning = new Label(I18n.t("build.import.arguments.tip", String.join(" ", all)));
            warning.setWrapText(true);
            warning.getStyleClass().add("muted");
            // Off until the player has read what they are.
            arguments.setSelected(false);
            content.getChildren().addAll(arguments, warning);
        }
        content.setPadding(new Insets(16, 18, 8, 18));

        ButtonType create = new ButtonType(I18n.t("build.import.create"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(create, cancel);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(580);
        Theme.apply(dialog.getDialogPane());
        dialog.getDialogPane().lookupButton(create).disableProperty().bind(
                name.textProperty().isEmpty());

        javafx.application.Platform.runLater(() -> {
            name.requestFocus();
            name.selectAll();
        });

        if (dialog.showAndWait().filter(button -> button == create).isEmpty()) {
            return Optional.empty();
        }
        String typed = name.getText() == null ? "" : name.getText().trim();
        return Optional.of(new ImportChoice(typed.isBlank() ? build.name() : typed,
                build.hasArguments() && arguments.isSelected()));
    }

    /** A size in the unit that makes it short. */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static CheckBox box(String key, boolean selected) {
        CheckBox box = new CheckBox(I18n.t(key));
        box.setSelected(selected);
        return box;
    }
}
