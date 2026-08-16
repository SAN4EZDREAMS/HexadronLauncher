package com.hexadron.launcher.ui;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.LauncherService;
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
    private final Slider memorySlider = new Slider(1024, 16384, 4096);
    private final Label memoryLabel = new Label();
    private final TextField javaPathField = new TextField();
    private final CheckBox showAllVersions = new CheckBox("Show snapshots and old versions");

    private final Label stageLabel = new Label("Ready");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TextArea logArea = new TextArea();
    private final UiProgress progress;

    private final Button playButton = new Button("Play");
    private final Button installButton = new Button("Install / repair");
    private final Button modsButton = new Button("Install Hexadron Optimise");

    private GameLauncher.GameSession session;
    private volatile boolean busy;

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

        Button add = new Button("New");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(event -> createProfile());

        Button remove = new Button("Remove");
        remove.setMaxWidth(Double.MAX_VALUE);
        remove.setOnAction(event -> removeSelectedProfile());

        HBox buttons = new HBox(6, add, remove);
        HBox.setHgrow(add, Priority.ALWAYS);
        HBox.setHgrow(remove, Priority.ALWAYS);

        VBox pane = new VBox(8, new Label("Profiles"), profileList, buttons);
        pane.setPadding(new Insets(10));
        VBox.setVgrow(profileList, Priority.ALWAYS);
        return pane;
    }

    private VBox buildEditorPane() {
        nameField.setPromptText("Profile name");
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
            memoryLabel.setText(megabytes + " MB");
            withSelected(profile -> profile.memoryMegabytes(megabytes));
        });

        javaPathField.setPromptText("Java executable (leave empty to detect automatically)");
        javaPathField.textProperty().addListener((observable, previous, value) ->
                withSelected(profile -> profile.javaPath(value)));

        Button detectJava = new Button("Detect");
        detectJava.setOnAction(event -> showDetectedJava());

        installButton.setOnAction(event -> runInBackground("Install", () -> {
            Profile profile = requireSelected();
            service.installProfile(profile, progress);
            progress.log("Installed %s", profile.effectiveVersionId());
        }));

        modsButton.setOnAction(event -> runInBackground("Install mods", () -> {
            Profile profile = requireSelected();
            ModInstaller.Result result = service.installPack(profile, ModPack.hexadronOptimise(), progress);
            progress.log("Installed %d mod(s) into %s",
                    result.installed().size(), service.profiles().modsDirectory(profile));
            if (!result.isClean()) {
                Platform.runLater(() -> showWarning("Some mods need attention",
                        String.join("\n",
                                java.util.stream.Stream.concat(
                                        result.skipped().stream(),
                                        result.manualDownloads().stream()).toList())));
            }
        }));

        VBox pane = new VBox(10,
                labelled("Name", nameField),
                labelled("Minecraft version", minecraftVersionBox),
                showAllVersions,
                labelled("Mod loader", loaderBox),
                labelled("Loader version", loaderVersionBox),
                labelled("Memory", new HBox(10, memorySlider, memoryLabel)),
                labelled("Java", new HBox(6, javaPathField, detectJava)),
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

        Button addAccount = new Button("Add offline account");
        addAccount.setOnAction(event -> addOfflineAccount());

        Button signIn = new Button("Sign in with Microsoft");
        signIn.setOnAction(event -> signInWithMicrosoft());

        Button openFolder = new Button("Open game folder");
        openFolder.setOnAction(event -> openGameFolder());

        accountBox.setPrefWidth(220);

        HBox controls = new HBox(8, new Label("Account:"), accountBox, addAccount, signIn,
                openFolder, spacer(), playButton);
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

    private static VBox labelled(String text, javafx.scene.Node control) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(4, label, control);
        return box;
    }

    private static javafx.scene.Node spacer() {
        javafx.scene.layout.Region region = new javafx.scene.layout.Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    // ---------------------------------------------------------------- actions

    private void createProfile() {
        TextInputDialog dialog = new TextInputDialog("New profile");
        dialog.setTitle("New profile");
        dialog.setHeaderText("Name this profile");
        Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        String version = minecraftVersionBox.getValue();
        if (version == null) {
            showWarning("No version list yet",
                    "The Minecraft version list has not loaded. Check the log and try again.");
            return;
        }
        try {
            Profile profile = service.profiles().add(
                    Profile.create(name.get().trim(), version, LoaderType.FABRIC));
            service.profiles().save();
            refreshProfiles();
            profileList.getSelectionModel().select(profile);
        } catch (IOException e) {
            showError("Could not create the profile", e);
        }
    }

    private void removeSelectedProfile() {
        Profile profile = profileList.getSelectionModel().getSelectedItem();
        if (profile == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove profile \"" + profile.name() + "\"?\n\n"
                        + "Its game folder, including worlds, is left on disk at\n"
                        + service.profiles().gameDirectory(profile));
        confirm.setHeaderText("Remove profile");
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
            showWarning("No account", "Add an offline account or sign in with Microsoft first.");
            return;
        }
        runInBackground("Play", () -> {
            Profile profile = requireSelected();
            session = service.launch(profile, account, progress,
                    progress::log,
                    exitCode -> {
                        progress.log(GameLauncher.describeExit(exitCode));
                        Platform.runLater(() -> {
                            playButton.setText("Play");
                            setBusy(false);
                        });
                    });
            Platform.runLater(() -> {
                playButton.setText("Stop");
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
        TextInputDialog dialog = new TextInputDialog("Player");
        dialog.setTitle("Offline account");
        dialog.setHeaderText("In-game name");
        dialog.setContentText("This account cannot join servers that check ownership.");
        dialog.showAndWait().ifPresent(name -> {
            Account account = Account.offline(name);
            service.accounts().add(account);
            try {
                service.accounts().save();
            } catch (IOException e) {
                showError("Could not save the account", e);
                return;
            }
            refreshAccounts();
            accountBox.getSelectionModel().select(account);
        });
    }

    private void signInWithMicrosoft() {
        if (!service.settings().hasMicrosoftClientId()) {
            showWarning("Microsoft sign-in is not configured", """
                    Microsoft sign-in needs an Azure application (client) ID that Mojang has \
                    approved for Minecraft authentication.

                    Register an application in the Azure portal, apply to Mojang for approval, \
                    then put the client ID into launcher.json as "microsoftClientId".

                    Offline accounts work without any of this.""");
            return;
        }
        runInBackground("Microsoft sign-in", () -> {
            var auth = new com.hexadron.launcher.auth.MicrosoftAuth(service.settings().microsoftClientId());
            var prompt = auth.requestDeviceCode();
            progress.log("Open %s and enter the code %s",
                    prompt.verificationUri(), prompt.userCode());
            Platform.runLater(() -> showInfo("Sign in with Microsoft",
                    "Open\n" + prompt.verificationUri()
                            + "\n\nand enter this code:\n\n" + prompt.userCode()
                            + "\n\nThis window can stay open; the launcher is waiting."));

            Account account = auth.completeDeviceCodeFlow(prompt,
                    remaining -> progress.stage("Waiting for sign-in (" + remaining + "s left)"),
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
        runInBackground("Detect Java", () -> {
            var runtimes = service.javaLocator().discover();
            progress.log("Detected %d Java runtime(s)", runtimes.size());
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
                progress.log("Game folder: %s", folder);
            }
        } catch (IOException | UnsupportedOperationException e) {
            progress.log("Game folder: %s (could not open a file manager: %s)", folder, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- data loading

    private void loadMinecraftVersionsAsync() {
        runInBackground("Loading version list", () -> {
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
            progress.log("Loaded %d Minecraft versions", ids.size());
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
        runInBackground("Loading " + loader.displayName() + " builds", () -> {
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
        memoryLabel.setText(profile.memoryMegabytes() + " MB");
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
            throw new IOException("select a profile first");
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
            progress.log("Busy - '%s' ignored", name);
            return;
        }
        setBusy(true);
        progress.reset();
        Thread thread = new Thread(() -> {
            try {
                task.run();
                if (clearBusyOnSuccess) {
                    Platform.runLater(() -> {
                        stageLabel.setText("Ready");
                        progressBar.setProgress(1);
                        setBusy(false);
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Platform.runLater(() -> setBusy(false));
            } catch (Exception e) {
                progress.log("FAILED: %s", e.getMessage() == null ? e.toString() : e.getMessage());
                Platform.runLater(() -> {
                    stageLabel.setText(name + " failed");
                    progressBar.setProgress(0);
                    setBusy(false);
                    showError(name + " failed", e);
                });
            }
        }, "hexadron-" + name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
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
            progress.log("Could not save profiles: %s", e.getMessage());
        }
    }

    private void saveSettingsQuietly() {
        try {
            service.settings().save();
        } catch (IOException e) {
            progress.log("Could not save settings: %s", e.getMessage());
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
