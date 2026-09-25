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

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.skin.MinecraftSkinApi;
import com.hexadron.launcher.skin.SkinProfile;
import com.hexadron.launcher.skin.SkinStore;
import com.hexadron.launcher.skin.SkinTemplate;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.control.Tooltip;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The skin and cape editor for a Microsoft account.
 *
 * <h2>The figure is the point</h2>
 *
 * <p>The left half is the player, turning. Everything on the right changes it
 * as it is pressed, so the answer to "what will this look like" is on screen
 * before anything is uploaded - which is the whole reason to have this window
 * rather than a file picker.
 *
 * <h2>Labels sit above what they label</h2>
 *
 * <p>A heading over its own group has one reading and needs no alignment to
 * work.
 *
 * <h2>Where the skin lives</h2>
 *
 * <p>A Microsoft account's skin and cape are kept by Mojang. The picture chosen
 * here is only the file offered for upload; the buttons that change the account
 * write to Mojang immediately rather than on Save, because Cancel could not undo
 * a network write and pretending otherwise would be worse than not offering it.
 *
 * <p>An offline account has no skin settings. It plays with the game's default
 * skin, and the launcher does not attach anything to the game to change that.
 */
public final class AccountDialog {

    /** What the dialog came back with, or empty when it was cancelled. */
    public record Result(SkinProfile skin) {
    }

    private final SkinStore store;
    private final Account account;

    private SkinProfile profile;

    private final SkinViewer viewer = new SkinViewer();
    private final ComboBox<SkinProfile.Model> modelBox = new ComboBox<>();
    private final ComboBox<MinecraftSkinApi.Cape> capeBox = new ComboBox<>();
    private final Label status = new Label();

    /** The skin held at Mojang. Drawn, never stored. */
    private Image remoteSkin;

    /**
     * @throws IllegalArgumentException for an offline account, which has no
     *                                  skin settings to edit
     */
    public AccountDialog(Account account, SkinStore store) {
        if (account.isOffline()) {
            throw new IllegalArgumentException("an offline account has no skin settings");
        }
        this.account = account;
        this.store = store;
        this.profile = store.of(account.id());
    }

    public Optional<Result> show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("account.edit.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(false);

        ButtonType save = new ButtonType(I18n.t("dialog.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(save, cancel);
        dialog.getDialogPane().setContent(build(owner));
        Theme.apply(dialog.getDialogPane());

        try {
            if (dialog.showAndWait().filter(button -> button == save).isEmpty()) {
                return Optional.empty();
            }
        } finally {
            // The figure turns on a frame timer, which would otherwise keep
            // running for the life of the launcher once this window has gone.
            viewer.stop();
        }
        return Optional.of(new Result(profile));
    }

    private HBox build(Window owner) {
        VBox form = new VBox(14);
        form.setPadding(new Insets(18, 18, 8, 18));
        form.setMinWidth(330);
        form.setPrefWidth(340);

        Label name = new Label(account.username());
        name.getStyleClass().add("detail-title");
        Label kind = new Label(I18n.t("account.kind.microsoft"));
        kind.getStyleClass().add("muted");
        form.getChildren().add(new VBox(2, name, kind));

        form.getChildren().addAll(new Separator(), skinSection(owner));
        form.getChildren().addAll(new Separator(), capeSection());

        status.getStyleClass().add("muted");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        form.getChildren().add(status);

        // In a scroller of a fixed height, so the window and the figure beside
        // it do not jump when a status line wraps to more lines.
        javafx.scene.control.ScrollPane scroller = new javafx.scene.control.ScrollPane(form);
        scroller.getStyleClass().add("form-scroll");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPrefViewportHeight(viewer.getPrefHeight());
        scroller.setMinHeight(viewer.getPrefHeight());
        scroller.setPrefHeight(viewer.getPrefHeight());
        scroller.setMaxHeight(viewer.getPrefHeight());

        HBox root = new HBox(scroller);
        root.getChildren().add(0, viewer);
        HBox.setHgrow(scroller, Priority.ALWAYS);

        viewer.onFileDropped(this::dropped);

        refresh();
        loadPremiumProfile();
        return root;
    }

    // ------------------------------------------------------------------ sections

    private VBox skinSection(Window owner) {
        Button choose = new Button(I18n.t("account.skin.choose"));
        choose.setOnAction(event -> pick(owner));
        Button clear = new Button(I18n.t("account.skin.clear"));
        clear.setOnAction(event -> {
            profile = profile.withSkin(null);
            refresh();
        });

        modelBox.getItems().setAll(SkinProfile.Model.values());
        modelBox.setMaxWidth(Double.MAX_VALUE);
        modelBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(SkinProfile.Model model) {
                return model == null ? "" : I18n.t(model == SkinProfile.Model.SLIM
                        ? "account.model.slim" : "account.model.classic");
            }

            @Override
            public SkinProfile.Model fromString(String text) {
                return SkinProfile.Model.CLASSIC;
            }
        });
        modelBox.valueProperty().addListener((observable, previous, value) -> {
            if (value != null && value != profile.model()) {
                profile = profile.withModel(value);
                refresh();
            }
        });

        Button upload = new Button(I18n.t("account.skin.upload"));
        upload.setOnAction(event -> uploadSkin());

        return new VBox(8, title("account.skin"),
                new HBox(6, choose, clear, template(owner)), modelBox,
                upload, note("account.skin.premium.note"));
    }

    private VBox capeSection() {
        capeBox.setMaxWidth(Double.MAX_VALUE);
        Button apply = new Button(I18n.t("account.cape.apply"));
        apply.setOnAction(event -> applyCape());
        HBox row = new HBox(6, capeBox, apply);
        HBox.setHgrow(capeBox, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, title("account.cape"), row, note("account.cape.note"));
    }

    // ------------------------------------------------------------------ actions

    private void pick(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("account.skin"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG", List.of("*.png")));
        java.io.File chosen = chooser.showOpenDialog(owner);
        if (chosen == null) {
            return;
        }
        take(chosen.toPath());
    }

    /**
     * The button that hands somebody a sheet to draw on.
     *
     * <p>Asked for a folder rather than a file name, because it writes two: the
     * canvas and the map of what goes where.
     */
    private Button template(Window owner) {
        Button button = new Button(I18n.t("account.template"));
        Tooltip.install(button, new Tooltip(I18n.t("account.template.note")));
        button.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(I18n.t("account.template"));
            java.io.File folder = chooser.showDialog(owner);
            if (folder == null) {
                return;
            }
            try {
                List<Path> written = SkinTemplate.write(folder.toPath(), false,
                        profile.model() == SkinProfile.Model.SLIM,
                        (kind, name) -> I18n.t("template." + kind + "." + name));
                status.setText(I18n.t("account.template.written",
                        written.stream().map(path -> path.getFileName().toString())
                                .collect(java.util.stream.Collectors.joining(", "))));
            } catch (IOException e) {
                status.setText(e.getMessage());
            }
        });
        return button;
    }

    /** A file dropped on the figure. */
    private void dropped(Path file) {
        if (com.hexadron.launcher.skin.PngSize.read(file) == null) {
            status.setText(I18n.t("account.drop.rejected"));
            return;
        }
        take(file);
    }

    private void take(Path file) {
        try {
            String stored = store.store(file);
            profile = profile.withSkin(stored);
            status.setText(noteFor(file));
            refresh();
        } catch (IOException e) {
            status.setText(e.getMessage());
        }
    }

    /**
     * What to say about a file that has just been taken.
     *
     * <p>Said at the moment of choosing: the game takes 64x64 and 64x32 only,
     * and a larger sheet is scaled down before upload.
     */
    private String noteFor(Path file) {
        int[] size = com.hexadron.launcher.skin.PngSize.read(file);
        if (size != null
                && com.hexadron.launcher.skin.SkinSheets.needsResizing(size[0], size[1], false)) {
            return I18n.t("account.skin.resized", size[0] + "x" + size[1]);
        }
        return "";
    }

    private void uploadSkin() {
        Path file = store.file(profile.skin());
        if (file == null) {
            status.setText(I18n.t("account.skin.none"));
            return;
        }
        status.setText(I18n.t("account.busy"));
        run(() -> {
            MinecraftSkinApi.uploadSkin(account, file, profile.model());
            return I18n.t("account.skin.uploaded");
        });
    }

    private void applyCape() {
        MinecraftSkinApi.Cape cape = capeBox.getValue();
        status.setText(I18n.t("account.busy"));
        run(() -> {
            if (cape == null || cape.id().isBlank()) {
                MinecraftSkinApi.removeCape(account);
            } else {
                MinecraftSkinApi.wearCape(account, cape.id());
            }
            return I18n.t("account.cape.applied");
        });
    }

    /**
     * Reads what Mojang holds, and draws it.
     *
     * <p>The sheet is fetched on the worker thread rather than handed to the
     * scene as a lazily loading image: a half-loaded sheet has no pixels to read
     * yet, and the figure would be built out of an empty texture and never
     * rebuilt.
     */
    private void loadPremiumProfile() {
        run(() -> {
            MinecraftSkinApi.Profile fetched = MinecraftSkinApi.read(account);
            Image sheet = fetched.skinUrl() == null ? null : new Image(fetched.skinUrl());
            javafx.application.Platform.runLater(() -> {
                capeBox.getItems().setAll(fetched.capes());
                capeBox.getItems().add(0,
                        new MinecraftSkinApi.Cape("", I18n.t("account.cape.none"), false));
                fetched.capes().stream().filter(MinecraftSkinApi.Cape::active).findFirst()
                        .ifPresentOrElse(capeBox::setValue,
                                () -> capeBox.getSelectionModel().selectFirst());
                profile = profile.withModel(fetched.model());
                if (sheet != null && !sheet.isError()) {
                    remoteSkin = sheet;
                }
                refresh();
            });
            return "";
        });
    }

    private void run(NetworkTask task) {
        Thread worker = new Thread(() -> {
            String message;
            try {
                message = task.run();
            } catch (Exception e) {
                message = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String shown = message;
            javafx.application.Platform.runLater(() -> {
                if (!shown.isBlank()) {
                    status.setText(shown);
                }
            });
        }, "account-dialog");
        worker.setDaemon(true);
        worker.start();
    }

    @FunctionalInterface
    private interface NetworkTask {
        String run() throws Exception;
    }

    // ------------------------------------------------------------------ views

    private void refresh() {
        modelBox.setValue(profile.model());
        drawFigure();
    }

    /**
     * The chosen file when there is one, because that is what an upload would
     * send; otherwise the sheet Mojang holds.
     */
    private void drawFigure() {
        Image skin = load(store.file(profile.skin()));
        if (skin == null) {
            skin = remoteSkin;
        }
        viewer.show(skin, null, profile.model() == SkinProfile.Model.SLIM);
    }

    private static Image load(Path file) {
        if (file == null) {
            return null;
        }
        Image image = new Image(file.toUri().toString(), false);
        return image.isError() || image.getWidth() <= 0 ? null : image;
    }

    private static Label title(String key) {
        Label label = new Label(I18n.t(key));
        label.getStyleClass().add("section-title");
        return label;
    }

    private static Label note(String key) {
        Label label = new Label(I18n.t(key));
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }
}
