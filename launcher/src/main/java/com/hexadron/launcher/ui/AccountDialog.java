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
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
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
 * The skin and cape editor.
 *
 * <h2>The figure is the point</h2>
 *
 * <p>The left half is the player, turning. Everything on the right changes it
 * as it is pressed, so the answer to "what will this look like" is on screen
 * before anything is saved - which is the whole reason to have this window
 * rather than a file picker.
 *
 * <p>The figure shows the files chosen here whichever service is selected. When
 * the skins come from a service on the network the file here is a preview
 * rather than the thing the game will fetch, and that is still worth drawing:
 * somebody choosing between two PNGs is choosing between two pictures, and the
 * question of who serves them is a different one, answered a few lines below.
 *
 * <h2>Labels sit above what they label</h2>
 *
 * <p>They used to be in a column down the left, level with the middle of each
 * row - which for a row two controls tall put the word "Skin" beside the gap
 * between them. A heading over its own group has one reading and needs no
 * alignment to work.
 *
 * <h2>Two accounts, one window</h2>
 *
 * <p>An offline account owns its skin here: the file is copied into the
 * launcher and served to the game by the launcher. A Microsoft account owns
 * nothing here - its skin lives at Mojang, and the buttons that touch it write
 * there immediately rather than on Save, because Cancel could not undo a
 * network write and pretending otherwise would be worse than not offering it.
 */
public final class AccountDialog {

    /** What the dialog came back with, or empty when it was cancelled. */
    public record Result(SkinProfile skin) {
    }

    private final SkinStore store;
    private final Account account;

    private SkinProfile profile;

    private final SkinViewer viewer = new SkinViewer();
    private final TextField serviceField = new TextField();
    private final ComboBox<SkinProfile.Model> modelBox = new ComboBox<>();
    private final ComboBox<MinecraftSkinApi.Cape> capeBox = new ComboBox<>();
    private final Label sourceNote = new Label();
    private final Label status = new Label();

    private final RadioButton localSource = new RadioButton();
    private final RadioButton remoteSource = new RadioButton();

    /** The skin held at Mojang, for a premium account. Drawn, never stored. */
    private Image remoteSkin;

    public AccountDialog(Account account, SkinStore store) {
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
        return Optional.of(new Result(collect()));
    }

    private HBox build(Window owner) {
        VBox form = new VBox(14);
        form.setPadding(new Insets(18, 18, 8, 18));
        form.setMinWidth(330);
        form.setPrefWidth(340);

        Label name = new Label(account.username());
        name.getStyleClass().add("detail-title");
        Label kind = new Label(I18n.t(account.isOffline()
                ? "account.kind.offline" : "account.kind.microsoft"));
        kind.getStyleClass().add("muted");
        form.getChildren().add(new VBox(2, name, kind));

        form.getChildren().addAll(new Separator(), skinSection(owner));
        form.getChildren().addAll(new Separator(), capeSection(owner));
        form.getChildren().addAll(new Separator(), sourceSection());

        status.getStyleClass().add("muted");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        form.getChildren().add(status);

        HBox root = new HBox(form);
        root.getChildren().add(0, viewer);
        VBox.setVgrow(viewer, Priority.ALWAYS);
        HBox.setHgrow(form, Priority.ALWAYS);
        viewer.setPrefHeight(430);

        viewer.onFileDropped(this::dropped);

        refresh();
        if (!account.isOffline()) {
            loadPremiumProfile();
        }
        return root;
    }

    // ------------------------------------------------------------------ sections

    private VBox skinSection(Window owner) {
        Button choose = new Button(I18n.t("account.skin.choose"));
        choose.setOnAction(event -> pick(owner, false));
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

        VBox box = new VBox(8, title("account.skin"),
                new HBox(6, choose, clear, template(owner, false)), modelBox);
        if (!account.isOffline()) {
            Button upload = new Button(I18n.t("account.skin.upload"));
            upload.setOnAction(event -> uploadSkin());
            box.getChildren().addAll(upload, note("account.skin.premium.note"));
        }
        return box;
    }

    private VBox capeSection(Window owner) {
        if (account.isOffline()) {
            Button choose = new Button(I18n.t("account.skin.choose"));
            choose.setOnAction(event -> pick(owner, true));
            Button clear = new Button(I18n.t("account.skin.clear"));
            clear.setOnAction(event -> {
                profile = profile.withCape(null);
                refresh();
            });
            return new VBox(8, title("account.cape"),
                    new HBox(6, choose, clear, template(owner, true)));
        }

        capeBox.setMaxWidth(Double.MAX_VALUE);
        Button apply = new Button(I18n.t("account.cape.apply"));
        apply.setOnAction(event -> applyCape());
        HBox row = new HBox(6, capeBox, apply);
        HBox.setHgrow(capeBox, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, title("account.cape"), row, note("account.cape.note"));
    }

    private VBox sourceSection() {
        if (!account.isOffline()) {
            // A licensed account has one source and no choice about it, so there
            // is nothing here to ask.
            return new VBox();
        }
        ToggleGroup group = new ToggleGroup();
        localSource.setToggleGroup(group);
        remoteSource.setToggleGroup(group);
        localSource.setText(I18n.t("account.source.local"));
        remoteSource.setText(I18n.t("account.source.remote"));
        group.selectedToggleProperty().addListener((observable, previous, value) -> refreshSource());

        serviceField.setPromptText("https://littleskin.cn/api/yggdrasil");
        serviceField.setMaxWidth(Double.MAX_VALUE);

        sourceNote.getStyleClass().add("muted");
        sourceNote.setWrapText(true);
        sourceNote.setMinHeight(Region.USE_PREF_SIZE);

        return new VBox(8, title("account.source"), localSource, remoteSource,
                serviceField, sourceNote);
    }

    // ------------------------------------------------------------------ actions

    private void pick(Window owner, boolean cape) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t(cape ? "account.cape" : "account.skin"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG", List.of("*.png")));
        java.io.File chosen = chooser.showOpenDialog(owner);
        if (chosen == null) {
            return;
        }
        try {
            String stored = store.store(chosen.toPath(), cape);
            profile = cape ? profile.withCape(stored) : profile.withSkin(stored);
            status.setText("");
            refresh();
        } catch (IOException e) {
            status.setText(e.getMessage());
        }
    }

    /**
     * The button that hands somebody a sheet to draw on.
     *
     * <p>Asked for a folder rather than a file name, because it writes two: the
     * canvas and the map of what goes where. Naming them is this window's job -
     * they are a pair, and a pair the user has to name twice is a pair that ends
     * up with one of them called "skin2".
     */
    private Button template(Window owner, boolean cape) {
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
                List<Path> written = SkinTemplate.write(folder.toPath(), cape,
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

    /**
     * A file dropped on the figure.
     *
     * <p>Which of the two it is, decided from the picture rather than asked:
     * only a cape can be 22x17, only a skin can be 64x64, and the one shape both
     * share - 64x32 - goes to whichever slot is still empty, skin first. Getting
     * that wrong costs one press of the other Choose button, and stopping to ask
     * every time would cost more.
     */
    private void dropped(Path file) {
        int[] size = com.hexadron.launcher.skin.PngSize.read(file);
        if (size == null) {
            status.setText(I18n.t("account.drop.rejected"));
            return;
        }
        boolean cape = size[0] == 22
                || (size[0] == 64 && size[1] == 32 && profile.hasSkin() && !profile.hasCape());
        try {
            String stored = store.store(file, cape);
            profile = cape ? profile.withCape(stored) : profile.withSkin(stored);
            status.setText("");
            refresh();
        } catch (IOException e) {
            status.setText(e.getMessage());
        }
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

    private SkinProfile collect() {
        SkinProfile result = profile;
        if (account.isOffline()) {
            result = result
                    .withSource(remoteSource.isSelected()
                            ? SkinProfile.Source.REMOTE : SkinProfile.Source.LOCAL)
                    .withService(serviceField.getText());
        }
        return result;
    }

    private void refresh() {
        modelBox.setValue(profile.model());
        localSource.setSelected(profile.source() == SkinProfile.Source.LOCAL);
        remoteSource.setSelected(profile.source() == SkinProfile.Source.REMOTE);
        if (serviceField.getText() == null || serviceField.getText().isBlank()) {
            serviceField.setText(profile.service());
        }
        refreshSource();

        // Deliberately independent of which service was chosen: this is a
        // picture of the files in front of the user.
        Image skin = load(store.file(profile.skin()));
        if (skin == null) {
            skin = remoteSkin;
        }
        viewer.show(skin, load(store.file(profile.cape())),
                profile.model() == SkinProfile.Model.SLIM);
    }

    private void refreshSource() {
        boolean remote = remoteSource.isSelected();
        serviceField.setDisable(!remote);
        sourceNote.setText(I18n.t(remote
                ? "account.source.remote.note" : "account.source.local.note"));
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
