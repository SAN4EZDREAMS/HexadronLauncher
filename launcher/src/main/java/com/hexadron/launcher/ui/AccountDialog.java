package com.hexadron.launcher.ui;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.skin.MinecraftSkinApi;
import com.hexadron.launcher.skin.SkinProfile;
import com.hexadron.launcher.skin.SkinStore;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The account editor: what this player looks like.
 *
 * <h2>Two accounts, two different dialogs behind one</h2>
 *
 * <p>An offline account owns its skin here - the file is copied into the
 * launcher and served to the game by the launcher, and the dialog is the only
 * place it exists. A Microsoft account owns nothing here: its skin lives at
 * Mojang, this window uploads to it and reads back from it, and closing the
 * window without pressing anything changes nothing anywhere.
 *
 * <p>So the same window shows different things, and says which is which. The
 * one line that matters to a user is the one about who else can see the skin,
 * and it is on screen rather than in a manual: for Microsoft accounts,
 * everybody; for offline accounts, you, and other players only on a server that
 * uses the same skin service.
 */
public final class AccountDialog {

    /** Edge length of the head preview, in pixels. */
    private static final double HEAD = 72;

    /** What the dialog came back with, or empty when it was cancelled. */
    public record Result(SkinProfile skin) {
    }

    private final SkinStore store;
    private final Account account;

    private SkinProfile profile;

    private final StackPane skinPreview = new StackPane();
    private final StackPane capePreview = new StackPane();
    private final Label sourceNote = new Label();
    private final TextField serviceField = new TextField();
    private final ComboBox<SkinProfile.Model> modelBox = new ComboBox<>();
    private final ComboBox<MinecraftSkinApi.Cape> capeBox = new ComboBox<>();
    private final Label status = new Label();

    private final RadioButton localSource = new RadioButton();
    private final RadioButton remoteSource = new RadioButton();

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
        dialog.getDialogPane().setPrefWidth(560);
        Theme.apply(dialog.getDialogPane());

        if (dialog.showAndWait().filter(button -> button == save).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Result(collect()));
    }

    private GridPane build(Window owner) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form");
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(18, 18, 8, 18));

        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(120);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);

        int row = 0;

        Label name = new Label(account.username());
        name.getStyleClass().add("detail-title");
        Label kind = new Label(I18n.t(account.isOffline()
                ? "account.kind.offline" : "account.kind.microsoft"));
        kind.getStyleClass().add("muted");
        grid.addRow(row++, formLabel(I18n.t("label.account")), new VBox(2, name, kind));

        // ------------------------------------------------------------ skin
        for (StackPane preview : List.of(skinPreview, capePreview)) {
            preview.getStyleClass().add("detail-icon");
            preview.setMinSize(HEAD, HEAD);
            preview.setPrefSize(HEAD, HEAD);
            preview.setMaxSize(HEAD, HEAD);
        }

        Button chooseSkin = new Button(I18n.t("account.skin.choose"));
        chooseSkin.setOnAction(event -> pick(owner, false));
        Button clearSkin = new Button(I18n.t("account.skin.clear"));
        clearSkin.setOnAction(event -> {
            profile = profile.withSkin(null);
            refresh();
        });

        modelBox.getItems().setAll(SkinProfile.Model.values());
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
            if (value != null) {
                profile = profile.withModel(value);
            }
        });

        HBox skinRow = new HBox(10, skinPreview,
                new VBox(6, new HBox(6, chooseSkin, clearSkin), modelBox));
        skinRow.setAlignment(Pos.CENTER_LEFT);
        grid.addRow(row++, formLabel(I18n.t("account.skin")), skinRow);

        // ------------------------------------------------------------ cape
        if (account.isOffline()) {
            Button chooseCape = new Button(I18n.t("account.skin.choose"));
            chooseCape.setOnAction(event -> pick(owner, true));
            Button clearCape = new Button(I18n.t("account.skin.clear"));
            clearCape.setOnAction(event -> {
                profile = profile.withCape(null);
                refresh();
            });
            HBox capeRow = new HBox(10, capePreview, new HBox(6, chooseCape, clearCape));
            capeRow.setAlignment(Pos.CENTER_LEFT);
            grid.addRow(row++, formLabel(I18n.t("account.cape")), capeRow);
        } else {
            capeBox.setMaxWidth(Double.MAX_VALUE);
            Button apply = new Button(I18n.t("account.cape.apply"));
            apply.setOnAction(event -> applyCape());
            HBox capeRow = new HBox(6, capeBox, apply);
            HBox.setHgrow(capeBox, Priority.ALWAYS);
            capeRow.setAlignment(Pos.CENTER_LEFT);
            grid.addRow(row++, formLabel(I18n.t("account.cape")), capeRow);
            grid.addRow(row++, new Label(), note("account.cape.note"));
        }

        // ------------------------------------------------------------ source
        if (account.isOffline()) {
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
            sourceNote.setMaxWidth(380);
            sourceNote.setMinHeight(Region.USE_PREF_SIZE);

            VBox sourceBox = new VBox(8, localSource, remoteSource, serviceField, sourceNote);
            grid.addRow(row++, formLabel(I18n.t("account.source")), sourceBox);
        } else {
            grid.addRow(row++, new Label(), note("account.skin.premium.note"));

            Button upload = new Button(I18n.t("account.skin.upload"));
            upload.setOnAction(event -> uploadSkin());
            grid.addRow(row++, new Label(), upload);
        }

        status.getStyleClass().add("muted");
        status.setWrapText(true);
        status.setMaxWidth(380);
        status.setMinHeight(Region.USE_PREF_SIZE);
        grid.addRow(row, new Label(), status);

        refresh();
        if (!account.isOffline()) {
            loadPremiumProfile();
        }
        return grid;
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
     * Uploads to Mojang, immediately, rather than on Save.
     *
     * <p>Because it is not this dialog's state being edited: it is an account
     * at Mojang. A button that silently deferred a network write until Save
     * would leave the user unsure whether it had happened, and Cancel unable to
     * undo it either way.
     */
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

    private void loadPremiumProfile() {
        run(() -> {
            MinecraftSkinApi.Profile fetched = MinecraftSkinApi.read(account);
            javafx.application.Platform.runLater(() -> {
                capeBox.getItems().setAll(fetched.capes());
                capeBox.getItems().add(0, new MinecraftSkinApi.Cape("", I18n.t("account.cape.none"), false));
                fetched.capes().stream().filter(MinecraftSkinApi.Cape::active).findFirst()
                        .ifPresentOrElse(capeBox::setValue, () -> capeBox.getSelectionModel().selectFirst());
                modelBox.setValue(fetched.model());
            });
            return "";
        });
    }

    /** Anything that talks to Mojang, off the interface thread. */
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
        serviceField.setText(profile.service());
        show(skinPreview, store.file(profile.skin()), true);
        show(capePreview, store.file(profile.cape()), false);
        refreshSource();
    }

    private void refreshSource() {
        boolean remote = remoteSource.isSelected();
        serviceField.setDisable(!remote);
        sourceNote.setText(I18n.t(remote ? "account.source.remote.note" : "account.source.local.note"));
    }

    /**
     * The preview.
     *
     * <p>For a skin it is the head: the 8x8 face at (8,8) with the hat layer
     * over it, scaled up with no smoothing. That is the part of a skin somebody
     * recognises, and showing the whole sheet - which is mostly blank and
     * upside-down limbs - identifies nothing.
     */
    private static void show(StackPane target, Path file, boolean head) {
        target.getChildren().clear();
        if (file == null) {
            return;
        }
        Image image = new Image(file.toUri().toString(), false);
        if (image.isError() || image.getWidth() <= 0) {
            return;
        }
        ImageView view = new ImageView(image);
        view.setSmooth(false);
        if (head && image.getWidth() >= 64) {
            double scale = image.getWidth() / 64.0;
            view.setViewport(new javafx.geometry.Rectangle2D(
                    8 * scale, 8 * scale, 8 * scale, 8 * scale));
            view.setFitWidth(HEAD - 12);
            view.setFitHeight(HEAD - 12);
        } else {
            view.setFitWidth(HEAD - 12);
            view.setFitHeight(HEAD - 12);
            view.setPreserveRatio(true);
        }
        target.getChildren().add(view);
    }

    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }

    private static Label note(String key) {
        Label label = new Label(I18n.t(key));
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        label.setMaxWidth(380);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }
}
