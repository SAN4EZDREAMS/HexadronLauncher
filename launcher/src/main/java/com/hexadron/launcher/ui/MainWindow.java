package com.hexadron.launcher.ui;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.i18n.Language;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.install.loader.LoaderVersion;
import com.hexadron.launcher.install.loader.Loaders;
import com.hexadron.launcher.launch.GameLauncher;
import com.hexadron.launcher.meta.VersionManifest;
import com.hexadron.launcher.mods.ModInstaller;
import com.hexadron.launcher.mods.ModPack;
import com.hexadron.launcher.profile.Profile;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The launcher window.
 *
 * <p>Contains no launch logic: every action delegates to {@link LauncherService}
 * on a background thread and reports through {@link UiProgress}. That split is
 * what lets the same flows run headlessly from the CLI.
 *
 * <p>No user-visible string is written inline. Every one comes from
 * {@link I18n}, and {@link #applyTexts()} re-reads all of them, so a language
 * change takes effect immediately instead of at the next start.
 */
public final class MainWindow {

    private final LauncherService service;
    private final Stage stage;

    private final ListView<Profile> profileList = new ListView<>();
    private final ComboBox<Account> accountBox = new ComboBox<>();
    private final TextField nameField = new TextField();
    private final ComboBox<String> minecraftVersionBox = new ComboBox<>();
    private final ComboBox<LoaderType> loaderBox = new ComboBox<>();
    private final ComboBox<String> loaderVersionBox = new ComboBox<>();
    private final ComboBox<Language> languageBox = new ComboBox<>();
    private final Slider memorySlider = new Slider(1024, 16384, 4096);
    private final Label memoryLabel = new Label();
    private final TextField javaPathField = new TextField();
    private final CheckBox showAllVersions = new CheckBox();

    private final Label stageLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TextArea logArea = new TextArea();
    private final UiProgress progress;

    private final Button playButton = new Button();
    private final Button installButton = new Button();
    private final Button modsButton = new Button();
    private final Button addProfileButton = new Button();
    private final Button removeProfileButton = new Button();
    private final Button detectJavaButton = new Button();
    private final Button addAccountButton = new Button();
    private final Button signInButton = new Button();
    private final Button openFolderButton = new Button();

    private final Label profilesTitle = new Label();
    private final Label nameTitle = new Label();
    private final Label versionTitle = new Label();
    private final Label loaderTitle = new Label();
    private final Label loaderVersionTitle = new Label();
    private final Label memoryTitle = new Label();
    private final Label javaTitle = new Label();
    private final Label accountTitle = new Label();
    private final Label languageTitle = new Label();

    private GameLauncher.GameSession session;
    private volatile boolean busy;
    private boolean playing;

    public MainWindow(LauncherService service, Stage stage) {
        this.service = service;
        this.stage = stage;
        this.progress = new UiProgress(stageLabel, progressBar, logArea);
    }

    public Scene build() {
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setFont(Font.font("Monospaced", 11));

        BorderPane root = new BorderPane();
        root.setLeft(buildProfilePane());
        root.setCenter(buildEditorPane());
        root.setBottom(buildStatusPane());

        applyTexts();
        refreshProfiles();
        refreshAccounts();
        loadMinecraftVersionsAsync();

        return new Scene(root, 1080, 720);
    }

    // ---------------------------------------------------------------- panes

    private VBox buildProfilePane() {
        profileList.setPrefWidth(260);
        profileList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        profileList.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> showProfile(selected));

        addProfileButton.setMaxWidth(Double.MAX_VALUE);
        addProfileButton.setOnAction(event -> createProfile());

        removeProfileButton.setMaxWidth(Double.MAX_VALUE);
        removeProfileButton.setOnAction(event -> removeSelectedProfile());

        HBox buttons = new HBox(6, addProfileButton, removeProfileButton);
        HBox.setHgrow(addProfileButton, Priority.ALWAYS);
        HBox.setHgrow(removeProfileButton, Priority.ALWAYS);

        profilesTitle.setStyle("-fx-font-weight: bold;");
        VBox pane = new VBox(8, profilesTitle, profileList, buttons);
        pane.setPadding(new Insets(10));
        VBox.setVgrow(profileList, Priority.ALWAYS);
        return pane;
    }

    private VBox buildEditorPane() {
        nameField.textProperty().addListener((observable, previous, value) ->
                withSelected(profile -> profile.name(value)));

        minecraftVersionBox.setMaxWidth(Double.MAX_VALUE);
        minecraftVersionBox.valueProperty().addListener((observable, previous, value) -> {
            if (value != null) {
                withSelected(profile -> profile.minecraftVersion(value));
                loadLoaderVersionsAsync();
            }
        });

        loaderBox.setItems(FXCollections.observableArrayList(Loaders.allLoaders()));
        loaderBox.setMaxWidth(Double.MAX_VALUE);
        loaderBox.valueProperty().addListener((observable, previous, value) -> {
            if (value != null) {
                withSelected(profile -> profile.loader(value));
                loadLoaderVersionsAsync();
            }
        });

        loaderVersionBox.setMaxWidth(Double.MAX_VALUE);
        loaderVersionBox.valueProperty().addListener((observable, previous, value) ->
                withSelected(profile -> profile.loaderVersion(value)));

        showAllVersions.setSelected(service.settings().showAllVersions());
        showAllVersions.setOnAction(event -> {
            service.settings().showAllVersions(showAllVersions.isSelected());
            saveSettingsQuietly();
            loadMinecraftVersionsAsync();
        });

        memorySlider.setMajorTickUnit(1024);
        memorySlider.setBlockIncrement(512);
        memorySlider.setShowTickMarks(true);
        memorySlider.valueProperty().addListener((observable, previous, value) -> {
            int megabytes = (int) (Math.round(value.doubleValue() / 256.0) * 256);
            memoryLabel.setText(I18n.t("unit.megabytes", String.valueOf(megabytes)));
            withSelected(profile -> profile.memoryMegabytes(megabytes));
        });

        javaPathField.textProperty().addListener((observable, previous, value) ->
                withSelected(profile -> profile.javaPath(value)));

        detectJavaButton.setOnAction(event -> showDetectedJava());

        installButton.setOnAction(event -> runInBackground(I18n.t("task.install"), () -> {
            Profile profile = requireSelected();
            service.installProfile(profile, progress);
            progress.log(I18n.t("log.installed", profile.effectiveVersionId()));
        }));

        modsButton.setOnAction(event -> runInBackground(I18n.t("task.mods"), () -> {
            Profile profile = requireSelected();
            ModInstaller.Result result = service.installPack(profile, ModPack.hexadronOptimise(), progress);
            progress.log(I18n.t("log.installedMods",
                    result.installed().size(), service.profiles().modsDirectory(profile)));
            if (!result.isClean()) {
                Platform.runLater(() -> showWarning(I18n.t("mods.attention.header"),
                        String.join("\n",
                                java.util.stream.Stream.concat(
                                        result.skipped().stream(),
                                        result.manualDownloads().stream()).toList())));
            }
        }));

        VBox pane = new VBox(10,
                labelled(nameTitle, nameField),
                labelled(versionTitle, minecraftVersionBox),
                showAllVersions,
                labelled(loaderTitle, loaderBox),
                labelled(loaderVersionTitle, loaderVersionBox),
                labelled(memoryTitle, new HBox(10, memorySlider, memoryLabel)),
                labelled(javaTitle, new HBox(6, javaPathField, detectJavaButton)),
                new Separator(),
                new HBox(8, installButton, modsButton));

        HBox.setHgrow(memorySlider, Priority.ALWAYS);
        HBox.setHgrow(javaPathField, Priority.ALWAYS);
        pane.setPadding(new Insets(10));
        return pane;
    }

    private BorderPane buildStatusPane() {
        progressBar.setMaxWidth(Double.MAX_VALUE);
        logArea.setPrefRowCount(14);

        playButton.setDefaultButton(true);
        playButton.setPrefWidth(140);
        playButton.setOnAction(event -> play());

        addAccountButton.setOnAction(event -> addOfflineAccount());
        signInButton.setOnAction(event -> signInWithMicrosoft());
        openFolderButton.setOnAction(event -> openGameFolder());

        accountBox.setPrefWidth(220);

        languageBox.setItems(FXCollections.observableArrayList(Language.all()));
        languageBox.setValue(I18n.current());
        languageBox.setPrefWidth(140);
        languageBox.valueProperty().addListener((observable, previous, value) -> {
            if (value == null || value == I18n.current()) {
                return;
            }
            I18n.use(value);
            service.settings().language(value.code());
            saveSettingsQuietly();
            applyTexts();
        });

        HBox controls = new HBox(8, accountTitle, accountBox, addAccountButton, signInButton,
                openFolderButton, spacer(), languageTitle, languageBox, playButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8, 10, 8, 10));

        VBox status = new VBox(4, stageLabel, progressBar);
        status.setPadding(new Insets(0, 10, 6, 10));

        SplitPane splitPane = new SplitPane(logArea);
        splitPane.setPrefHeight(240);

        BorderPane pane = new BorderPane();
        pane.setTop(new VBox(controls, status));
        pane.setCenter(splitPane);
        return pane;
    }

    /**
     * Writes every visible string from the active language.
     *
     * <p>Called once when the window is built, and again after each language
     * change. Only static text is touched: nothing here reads or writes profile
     * state, so switching language cannot disturb what the user is editing.
     */
    private void applyTexts() {
        stage.setTitle(I18n.t("app.title"));

        profilesTitle.setText(I18n.t("profiles.header"));
        addProfileButton.setText(I18n.t("profiles.new"));
        removeProfileButton.setText(I18n.t("profiles.remove"));

        nameTitle.setText(I18n.t("editor.name"));
        nameField.setPromptText(I18n.t("editor.name.prompt"));
        versionTitle.setText(I18n.t("editor.version"));
        showAllVersions.setText(I18n.t("editor.showAll"));
        loaderTitle.setText(I18n.t("editor.loader"));
        loaderVersionTitle.setText(I18n.t("editor.loaderVersion"));
        memoryTitle.setText(I18n.t("editor.memory"));
        memoryLabel.setText(I18n.t("unit.megabytes", String.valueOf((int) memorySlider.getValue())));
        javaTitle.setText(I18n.t("editor.java"));
        javaPathField.setPromptText(I18n.t("editor.java.prompt"));
        detectJavaButton.setText(I18n.t("editor.java.detect"));

        installButton.setText(I18n.t("action.install"));
        modsButton.setText(I18n.t("action.mods"));
        addAccountButton.setText(I18n.t("action.addOffline"));
        signInButton.setText(I18n.t("action.signIn"));
        openFolderButton.setText(I18n.t("action.openFolder"));
        playButton.setText(I18n.t(playing ? "action.stop" : "action.play"));

        accountTitle.setText(I18n.t("label.account"));
        languageTitle.setText(I18n.t("label.language"));

        if (!busy) {
            stageLabel.setText(I18n.t("status.ready"));
        }
    }

    private static VBox labelled(Label title, javafx.scene.Node control) {
        title.setStyle("-fx-font-weight: bold;");
        return new VBox(4, title, control);
    }

    private static javafx.scene.Node spacer() {
        javafx.scene.layout.Region region = new javafx.scene.layout.Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    // ---------------------------------------------------------------- actions

    private void createProfile() {
        TextInputDialog dialog = new TextInputDialog(I18n.t("profiles.new.default"));
        dialog.setTitle(I18n.t("profiles.new.title"));
        dialog.setHeaderText(I18n.t("profiles.new.header"));
        Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        String version = minecraftVersionBox.getValue();
        if (version == null) {
            showWarning(I18n.t("profiles.noVersions.header"), I18n.t("profiles.noVersions.body"));
            return;
        }
        try {
            Profile profile = service.profiles().add(
                    Profile.create(name.get().trim(), version, LoaderType.FABRIC));
            service.profiles().save();
            refreshProfiles();
            profileList.getSelectionModel().select(profile);
        } catch (IOException e) {
            showError(I18n.t("profiles.create.failed"), e);
        }
    }

    private void removeSelectedProfile() {
        Profile profile = profileList.getSelectionModel().getSelectedItem();
        if (profile == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.t("profiles.remove.body", profile.name(),
                        service.profiles().gameDirectory(profile)));
        confirm.setHeaderText(I18n.t("profiles.remove.header"));
        if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            return;
        }
        service.profiles().remove(profile);
        saveProfilesQuietly();
        refreshProfiles();
    }

    private void play() {
        if (session != null && session.isRunning()) {
            session.terminate();
            return;
        }
        Account account = accountBox.getValue();
        if (account == null) {
            showWarning(I18n.t("account.none.header"), I18n.t("account.none.body"));
            return;
        }
        runInBackground(I18n.t("task.play"), () -> {
            Profile profile = requireSelected();
            session = service.launch(profile, account, progress,
                    progress::log,
                    exitCode -> {
                        progress.log(GameLauncher.describeExit(exitCode));
                        Platform.runLater(() -> {
                            playing = false;
                            playButton.setText(I18n.t("action.play"));
                            setBusy(false);
                        });
                    });
            Platform.runLater(() -> {
                playing = true;
                playButton.setText(I18n.t("action.stop"));
                // The game is running; the launcher itself is free again.
                setBusy(false);
                playButton.setDisable(false);
                if (!service.settings().keepOpenWhilePlaying()) {
                    stage.setIconified(true);
                }
            });
        }, false);
    }

    private void addOfflineAccount() {
        TextInputDialog dialog = new TextInputDialog(I18n.t("account.offline.default"));
        dialog.setTitle(I18n.t("account.offline.title"));
        dialog.setHeaderText(I18n.t("account.offline.header"));
        dialog.setContentText(I18n.t("account.offline.body"));
        dialog.showAndWait().ifPresent(name -> {
            Account account = Account.offline(name);
            service.accounts().add(account);
            try {
                service.accounts().save();
            } catch (IOException e) {
                showError(I18n.t("account.save.failed"), e);
                return;
            }
            refreshAccounts();
            accountBox.getSelectionModel().select(account);
        });
    }

    private void signInWithMicrosoft() {
        if (!service.settings().hasMicrosoftClientId()) {
            showWarning(I18n.t("ms.notConfigured.header"), I18n.t("ms.notConfigured.body"));
            return;
        }
        runInBackground(I18n.t("task.signIn"), () -> {
            var auth = new com.hexadron.launcher.auth.MicrosoftAuth(service.settings().microsoftClientId());
            var prompt = auth.requestDeviceCode();
            progress.log(I18n.t("log.signInPrompt", prompt.verificationUri(), prompt.userCode()));
            Platform.runLater(() -> showInfo(I18n.t("ms.signIn.header"),
                    I18n.t("ms.signIn.body", prompt.verificationUri(), prompt.userCode())));

            Account account = auth.completeDeviceCodeFlow(prompt,
                    remaining -> progress.stage(I18n.t("ms.waiting", remaining)),
                    progress);
            service.accounts().add(account);
            service.accounts().save();
            Platform.runLater(() -> {
                refreshAccounts();
                accountBox.getSelectionModel().select(account);
            });
        });
    }

    private void showDetectedJava() {
        runInBackground(I18n.t("task.detectJava"), () -> {
            var runtimes = service.javaLocator().discover();
            progress.log(I18n.t("log.javaFound", runtimes.size()));
            runtimes.forEach(runtime -> progress.log("  %s", runtime));
        });
    }

    private void openGameFolder() {
        Profile profile = profileList.getSelectionModel().getSelectedItem();
        var folder = profile == null
                ? service.dirs().root()
                : service.profiles().gameDirectory(profile);
        try {
            java.nio.file.Files.createDirectories(folder);
            // Desktop is unavailable on some Linux sessions; report rather than fail silently.
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(folder.toFile());
            } else {
                progress.log(I18n.t("log.gameFolder", folder));
            }
        } catch (IOException | UnsupportedOperationException e) {
            progress.log(I18n.t("log.gameFolderFailed", folder, e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- data loading

    private void loadMinecraftVersionsAsync() {
        runInBackground(I18n.t("task.versions"), () -> {
            VersionManifest manifest = service.minecraftVersions();
            List<String> ids = (service.settings().showAllVersions()
                    ? manifest.versions()
                    : manifest.releases())
                    .stream().map(VersionManifest.Entry::id).toList();
            Platform.runLater(() -> {
                String previous = minecraftVersionBox.getValue();
                minecraftVersionBox.setItems(FXCollections.observableArrayList(ids));
                if (previous != null && ids.contains(previous)) {
                    minecraftVersionBox.setValue(previous);
                } else if (!ids.isEmpty()) {
                    minecraftVersionBox.setValue(
                            manifest.latestRelease() != null && ids.contains(manifest.latestRelease())
                                    ? manifest.latestRelease()
                                    : ids.get(0));
                }
            });
            progress.log(I18n.t("log.versionsLoaded", ids.size()));
        });
    }

    private void loadLoaderVersionsAsync() {
        LoaderType loader = loaderBox.getValue();
        String minecraftVersion = minecraftVersionBox.getValue();
        if (loader == null || minecraftVersion == null || loader == LoaderType.VANILLA) {
            Platform.runLater(() -> {
                loaderVersionBox.setItems(FXCollections.observableArrayList());
                loaderVersionBox.setDisable(loader == LoaderType.VANILLA);
            });
            return;
        }
        runInBackground(I18n.t("task.loaderVersions", loader.displayName()), () -> {
            List<LoaderVersion> versions = service.loaderVersions(loader, minecraftVersion);
            List<String> ids = versions.stream().map(LoaderVersion::version).toList();
            Platform.runLater(() -> {
                loaderVersionBox.setDisable(false);
                loaderVersionBox.setItems(FXCollections.observableArrayList(ids));
                versions.stream().filter(LoaderVersion::stable).findFirst()
                        .ifPresentOrElse(
                                stable -> loaderVersionBox.setValue(stable.version()),
                                () -> {
                                    if (!ids.isEmpty()) {
                                        loaderVersionBox.setValue(ids.get(0));
                                    }
                                });
            });
        });
    }

    private void refreshProfiles() {
        List<Profile> all = service.profiles().byRecency();
        profileList.setItems(FXCollections.observableArrayList(all));
        service.profiles().selected().ifPresent(profileList.getSelectionModel()::select);
        if (profileList.getSelectionModel().getSelectedItem() == null && !all.isEmpty()) {
            profileList.getSelectionModel().selectFirst();
        }
    }

    private void refreshAccounts() {
        accountBox.setItems(FXCollections.observableArrayList(service.accounts().all()));
        service.accounts().selected().ifPresent(accountBox.getSelectionModel()::select);
    }

    private void showProfile(Profile profile) {
        boolean present = profile != null;
        nameField.setDisable(!present);
        minecraftVersionBox.setDisable(!present);
        loaderBox.setDisable(!present);
        memorySlider.setDisable(!present);
        javaPathField.setDisable(!present);
        installButton.setDisable(!present);
        modsButton.setDisable(!present);
        playButton.setDisable(!present);
        if (!present) {
            return;
        }
        service.profiles().select(profile);
        nameField.setText(profile.name());
        minecraftVersionBox.setValue(profile.minecraftVersion());
        loaderBox.setValue(profile.loader());
        memorySlider.setValue(profile.memoryMegabytes());
        memoryLabel.setText(I18n.t("unit.megabytes", String.valueOf(profile.memoryMegabytes())));
        javaPathField.setText(profile.javaPath() == null ? "" : profile.javaPath());
    }

    private void withSelected(java.util.function.Consumer<Profile> action) {
        Profile profile = profileList.getSelectionModel().getSelectedItem();
        if (profile != null) {
            action.accept(profile);
            saveProfilesQuietly();
        }
    }

    private Profile requireSelected() throws IOException {
        Profile profile = profileList.getSelectionModel().getSelectedItem();
        if (profile == null) {
            throw new IOException(I18n.t("profiles.selectFirst"));
        }
        return profile;
    }

    // ---------------------------------------------------------------- plumbing

    /** Work that may throw, run off the UI thread. */
    @FunctionalInterface
    private interface BackgroundTask {
        void run() throws Exception;
    }

    private void runInBackground(String name, BackgroundTask task) {
        runInBackground(name, task, true);
    }

    private void runInBackground(String name, BackgroundTask task, boolean clearBusyOnSuccess) {
        if (busy) {
            progress.log(I18n.t("status.busy", name));
            return;
        }
        setBusy(true);
        progress.reset();
        Thread thread = new Thread(() -> {
            try {
                task.run();
                if (clearBusyOnSuccess) {
                    Platform.runLater(() -> {
                        stageLabel.setText(I18n.t("status.ready"));
                        progressBar.setProgress(1);
                        setBusy(false);
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Platform.runLater(() -> setBusy(false));
            } catch (Exception e) {
                progress.log(I18n.t("log.failed",
                        e.getMessage() == null ? e.toString() : e.getMessage()));
                Platform.runLater(() -> {
                    stageLabel.setText(I18n.t("status.failed", name));
                    progressBar.setProgress(0);
                    setBusy(false);
                    showError(I18n.t("status.failed", name), e);
                });
            }
        // A fixed thread name: deriving it from a translated task name produced
        // a different name per language, which is useless in a stack trace.
        }, "hexadron-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean value) {
        busy = value;
        playButton.setDisable(value);
        installButton.setDisable(value);
        modsButton.setDisable(value);
    }

    private void saveProfilesQuietly() {
        try {
            service.profiles().save();
        } catch (IOException e) {
            progress.log(I18n.t("log.profilesSaveFailed", e.getMessage()));
        }
    }

    private void saveSettingsQuietly() {
        try {
            service.settings().save();
        } catch (IOException e) {
            progress.log(I18n.t("log.settingsSaveFailed", e.getMessage()));
        }
    }

    private void showInfo(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setHeaderText(header);
        alert.setTitle(header);
        alert.getDialogPane().setPrefWidth(520);
        alert.showAndWait();
    }

    private void showWarning(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.setHeaderText(header);
        alert.setTitle(header);
        alert.getDialogPane().setPrefWidth(520);
        alert.showAndWait();
    }

    private void showError(String header, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR,
                error.getMessage() == null ? error.toString() : error.getMessage());
        alert.setHeaderText(header);
        alert.setTitle(header);
        alert.getDialogPane().setPrefWidth(600);
        alert.showAndWait();
    }

    /** Called when the window closes: stop the game if the user wants it stopped with the launcher. */
    public void shutdown() {
        if (session != null && session.isRunning() && !service.settings().keepOpenWhilePlaying()) {
            session.terminate();
        }
    }
}
