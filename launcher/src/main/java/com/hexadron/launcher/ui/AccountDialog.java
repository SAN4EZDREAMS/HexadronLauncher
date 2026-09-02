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

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.skin.MinecraftSkinApi;
import com.hexadron.launcher.skin.SkinCredentials;
import com.hexadron.launcher.skin.SkinProfile;
import com.hexadron.launcher.skin.SkinStore;
import com.hexadron.launcher.skin.SkinTemplate;
import com.hexadron.launcher.skin.YggdrasilAuth;

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
    private final SkinCredentials credentials;
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

    private final Label signedIn = new Label();
    private final Button signIn = new Button();
    private final Button signOut = new Button();

    /** The saved sign-in for the service in the address field, if there is one. */
    private YggdrasilAuth.Session session;

    /**
     * What the service says this profile wears, and the figure built from it.
     *
     * <p>Kept apart from the local files because in remote mode these are what
     * the game will actually show, and the local ones are not.
     */
    private Image serviceSkin;
    private Image serviceCape;
    private boolean serviceSlim;
    private boolean serviceEmpty;

    /** Which service {@link #serviceSkin} was fetched from, so it is not reused. */
    private String servicedFrom = "";

    /** The skin held at Mojang, for a premium account. Drawn, never stored. */
    private Image remoteSkin;

    public AccountDialog(Account account, SkinStore store, SkinCredentials credentials) {
        this.account = account;
        this.store = store;
        this.credentials = credentials;
        this.profile = store.of(account.id());
        this.session = credentials == null
                ? null : credentials.load(account.id(), profile.service()).orElse(null);
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

        // In a scroller of a fixed height, and that height is the point.
        //
        // The form is not the same height in every state: the sign-in row and
        // the line under it exist only for a service, and the two notes wrap to
        // different numbers of lines. Left to size itself, the window - and the
        // figure beside it - jumped every time the radio button was clicked.
        // Fixed here, the window is the same window whatever is selected, and
        // the rare state that does not fit scrolls instead of resizing
        // everything around it.
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
        if (!account.isOffline()) {
            loadPremiumProfile();
        }
        loadServiceTextures();
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
        group.selectedToggleProperty().addListener((observable, previous, value) -> {
            // Written back rather than only read at Save: refresh() sets the
            // radio from the profile, so a profile that does not know what the
            // user just chose quietly undoes the choice on the next file pick.
            profile = profile.withSource(remoteSource.isSelected()
                    ? SkinProfile.Source.REMOTE : SkinProfile.Source.LOCAL);
            refreshSource();
            drawFigure();
        });

        serviceField.setPromptText("https://littleskin.cn/api/yggdrasil");
        serviceField.setMaxWidth(Double.MAX_VALUE);
        // Two listeners, on purpose. Every keystroke updates what the line under
        // the buttons says, which is cheap. Looking a saved sign-in up is not -
        // on Windows it is a process - so that waits until the address is
        // finished with: Enter, or the focus moving on.
        serviceField.textProperty().addListener((observable, previous, value) -> refreshSource());
        serviceField.setOnAction(event -> reloadSession());
        serviceField.focusedProperty().addListener((observable, had, has) -> {
            if (!has) {
                reloadSession();
            }
        });

        sourceNote.getStyleClass().add("muted");
        sourceNote.setWrapText(true);
        sourceNote.setMinHeight(Region.USE_PREF_SIZE);

        signIn.setText(I18n.t("account.service.signin"));
        signIn.setOnAction(event -> signIn());
        signOut.setText(I18n.t("account.service.signout"));
        signOut.setOnAction(event -> signOut());

        signedIn.getStyleClass().add("muted");
        signedIn.setWrapText(true);
        signedIn.setMinHeight(Region.USE_PREF_SIZE);

        HBox buttons = new HBox(6, signIn, signOut);
        buttons.setAlignment(Pos.CENTER_LEFT);

        return new VBox(8, title("account.source"), localSource, remoteSource,
                serviceField, buttons, signedIn, sourceNote);
    }

    // -------------------------------------------------------------- signing in

    /**
     * Signs in to the service in the address field.
     *
     * <p>Saved the moment it succeeds rather than on Save, for the same reason
     * the premium buttons write immediately: Cancel cannot un-issue a token, and
     * a window that quietly discarded one would leave the user signed in at the
     * service and signed out here.
     */
    private void signIn() {
        String address = serviceField.getText();
        String refusal = YggdrasilAuth.reasonToRefuse(address);
        if (refusal != null) {
            status.setText(I18n.t("signin.address.bad"));
            return;
        }
        if (credentials == null) {
            status.setText(I18n.t("signin.nostore"));
            return;
        }

        String clientToken = session == null ? null : session.clientToken();
        new YggdrasilSignInDialog(address, clientToken)
                .show(signIn.getScene() == null ? null : signIn.getScene().getWindow())
                .ifPresent(signedInAs -> {
                    try {
                        credentials.save(account.id(), signedInAs);
                        session = signedInAs;
                        profile = profile.withService(signedInAs.root());
                        serviceField.setText(signedInAs.root());
                        status.setText("");
                    } catch (IOException e) {
                        status.setText(I18n.t("signin.notsaved", e.getMessage()));
                    }
                    refreshSource();
                    loadServiceTextures();
                });
    }

    private void signOut() {
        if (credentials != null) {
            credentials.forget(account.id(), serviceField.getText());
        }
        session = null;
        forgetServiceTextures();
        status.setText("");
        refreshSource();
        drawFigure();
    }

    /**
     * Picks up the sign-in belonging to the address now in the field.
     *
     * <p>Sign-ins are kept per service, so pointing the field at another one is
     * not signing out - it is switching to a different account somewhere else,
     * which may well already be signed in.
     */
    private void reloadSession() {
        profile = profile.withService(serviceField.getText());
        YggdrasilAuth.Session found = credentials == null ? null
                : credentials.load(account.id(), serviceField.getText()).orElse(null);
        if (found == session) {
            return;
        }
        session = found;
        forgetServiceTextures();
        refreshSource();
        drawFigure();
        loadServiceTextures();
    }

    /**
     * Fetches what the service says this profile wears.
     *
     * <p>Run when the window opens and after every sign-in, because that is
     * when the answer can have changed. Not run on every keystroke in the
     * address field: a sign-in is what makes an address meaningful here, and
     * there is one for every address that has been signed in to.
     */
    private void loadServiceTextures() {
        if (session == null || !remoteSource.isSelected()
                || !session.isFor(serviceField.getText())) {
            return;
        }
        YggdrasilAuth.Session asked = session;
        if (asked.root().equals(servicedFrom)) {
            return;
        }

        run(() -> {
            YggdrasilAuth.Textures textures =
                    YggdrasilAuth.textures(asked.root(), asked.uuid());
            Image skin = textures.skinUrl() == null ? null : download(textures.skinUrl());
            Image cape = textures.capeUrl() == null ? null : download(textures.capeUrl());

            javafx.application.Platform.runLater(() -> {
                if (session != asked) {
                    // Signed in somewhere else while this was in flight.
                    return;
                }
                servicedFrom = asked.root();
                serviceSkin = skin;
                serviceCape = cape;
                serviceSlim = textures.slim();
                serviceEmpty = textures.isEmpty();
                refresh();
            });
            return "";
        });
    }

    private void forgetServiceTextures() {
        servicedFrom = "";
        serviceSkin = null;
        serviceCape = null;
        serviceEmpty = false;
    }

    /**
     * Fetched here rather than handed to the scene as a URL.
     *
     * <p>A lazily loading Image has no pixels yet when the figure is built from
     * it, and nothing rebuilds the figure when they arrive - which shows up as
     * a skin that appears only after the next click.
     */
    private static Image download(String url) throws IOException, InterruptedException {
        Image image = new Image(new java.io.ByteArrayInputStream(
                com.hexadron.launcher.net.Http.getBytes(url)));
        return image.isError() || image.getWidth() <= 0 ? null : image;
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
            status.setText(noteFor(chosen.toPath(), cape));
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
            status.setText(noteFor(file, cape));
            refresh();
        } catch (IOException e) {
            status.setText(e.getMessage());
        }
    }

    /**
     * What to say about a file that has just been taken.
     *
     * <p>Said at the moment of choosing, because the alternative is what
     * happened before: a high-resolution skin was accepted, stored, served,
     * verified - and thrown away by the game with one line in a log the user
     * has no reason to open, leaving the default skin and no explanation
     * anywhere.
     */
    private String noteFor(Path file, boolean cape) {
        if (usingService()) {
            return I18n.t("account.service.fileunused");
        }
        int[] size = com.hexadron.launcher.skin.PngSize.read(file);
        if (size != null
                && com.hexadron.launcher.skin.SkinSheets.needsResizing(size[0], size[1], cape)) {
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

        drawFigure();
    }

    /**
     * Builds the figure out of whatever will actually be worn in game.
     *
     * <h2>The order, and why it is this one</h2>
     *
     * <p>Signed in to a service: the service's textures, because those are the
     * ones the game fetches - and its default when the account there wears
     * nothing, because an empty box in that case reads as a failed sign-in.
     *
     * <p>Otherwise: the files chosen here, because those are what the launcher
     * itself will serve. A Microsoft account with no local file falls back to
     * the sheet held at Mojang, which is the same rule.
     *
     * <p>What is deliberately not done is showing a local file while a service
     * is signed in. It was, at first, on the reasoning that somebody choosing
     * between two PNGs wants to see them - but it means the window shows one
     * thing and the game another, and the first place that turns up is a player
     * asking why their skin did not change.
     */
    private void drawFigure() {
        if (usingService()) {
            if (serviceEmpty) {
                viewer.show(defaultSkin(), null, profile.model() == SkinProfile.Model.SLIM);
            } else {
                viewer.show(serviceSkin, serviceCape, serviceSlim);
            }
            return;
        }

        Image skin = load(store.file(profile.skin()));
        if (skin == null) {
            skin = remoteSkin;
        }
        viewer.show(skin, load(store.file(profile.cape())),
                profile.model() == SkinProfile.Model.SLIM);
    }

    /** True when the figure is showing a service's answer rather than a file. */
    private boolean usingService() {
        return session != null && remoteSource.isSelected()
                && session.root().equals(servicedFrom)
                && session.isFor(serviceField.getText());
    }

    /** The launcher's own stand-in. Not Mojang's default, and the note says so. */
    private static Image defaultSkin() {
        if (fallback == null) {
            fallback = new Image(new java.io.ByteArrayInputStream(
                    com.hexadron.launcher.skin.DefaultSkin.png()));
        }
        return fallback;
    }

    private static Image fallback;

    private void refreshSource() {
        boolean remote = remoteSource.isSelected();
        serviceField.setDisable(!remote);
        sourceNote.setText(I18n.t(remote
                ? "account.source.remote.note" : "account.source.local.note"));

        signIn.setDisable(!remote);
        signIn.setText(I18n.t(session == null
                ? "account.service.signin" : "account.service.signin.again"));
        signOut.setDisable(!remote || session == null);

        // The address in the field and the address the saved sign-in was issued
        // by can differ, because the field can be edited after signing in. Said
        // out loud, because otherwise the launch is the first place it shows -
        // as a skin that does not appear.
        if (!remote) {
            signedIn.setText("");
        } else if (session == null) {
            signedIn.setText(I18n.t("account.service.signedout"));
        } else if (!session.isFor(serviceField.getText())) {
            signedIn.setText(I18n.t("account.service.elsewhere", session.root()));
        } else if (serviceEmpty && session.root().equals(servicedFrom)) {
            // The sign-in worked and the account there is bare. Worth separating
            // from a failure, because on screen the two look the same: a figure
            // that is not the one the user picked.
            signedIn.setText(I18n.t("account.service.signedin", session.name())
                    + " " + I18n.t("account.service.noskin"));
        } else {
            signedIn.setText(I18n.t("account.service.signedin", session.name()));
        }
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
