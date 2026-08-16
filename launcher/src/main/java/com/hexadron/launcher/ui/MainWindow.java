package com.hexadron.launcher.ui;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.i18n.Language;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.install.loader.LoaderVersion;
import com.hexadron.launcher.launch.GameLauncher;
import com.hexadron.launcher.meta.VersionManifest;
import com.hexadron.launcher.mods.ModInstaller;
import com.hexadron.launcher.mods.ModPack;
import com.hexadron.launcher.profile.Profile;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

/**
 * The launcher window.
 *
 * <p>Layout follows what every current launcher settled on: a header, a
 * searchable instance list on the left, a read-only summary of the selected
 * instance in the middle, and one persistent footer holding the account and the
 * Play button. Nothing in the middle panel is an input. Instances are changed
 * through {@link ProfileDialog}, which is the pattern Prism Launcher and
 * MultiMC use - an edit with a Save and a Cancel, instead of fields that write
 * to disk as they are typed.
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
    private final TrayIntegration tray;

    /** Every profile, and the search-filtered view the list actually shows. */
    private final ObservableList<Profile> allProfiles = FXCollections.observableArrayList();
    private final FilteredList<Profile> visibleProfiles = new FilteredList<>(allProfiles, profile -> true);
    private final ListView<Profile> profileList = new ListView<>(visibleProfiles);

    private final TextField searchField = new TextField();
    private final ComboBox<Account> accountBox = new ComboBox<>();
    private final ComboBox<Language> languageBox = new ComboBox<>();

    private final Label detailName = new Label();
    private final Label detailSubtitle = new Label();
    private final Label summaryVersionValue = new Label();
    private final Label summaryLoaderValue = new Label();
    private final Label summaryMemoryValue = new Label();
    private final Label summaryJavaValue = new Label();
    private final Label summaryFolderValue = new Label();
    private final Label summaryPlayedValue = new Label();

    private final Label summaryVersionTitle = new Label();
    private final Label summaryLoaderTitle = new Label();
    private final Label summaryMemoryTitle = new Label();
    private final Label summaryJavaTitle = new Label();
    private final Label summaryFolderTitle = new Label();
    private final Label summaryPlayedTitle = new Label();

    private final Label stageLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TextArea logArea = new TextArea();
    private final TitledPane logPane = new TitledPane();
    private final UiProgress progress;

    private final Button playButton = new Button();
    private final Button newButton = new Button();
    private final Button editButton = new Button();
    private final Button removeButton = new Button();
    private final Button installButton = new Button();
    private final Button modsButton = new Button();
    private final Button openFolderButton = new Button();
    private final Button detectJavaButton = new Button();
    private final Button addAccountButton = new Button();
    private final Button signInButton = new Button();

    private final Label brandLabel = new Label();
    private final Label instancesTitle = new Label();
    private final Label accountTitle = new Label();
    private final Label languageTitle = new Label();

    private GameLauncher.GameSession session;
    private volatile boolean busy;
    private boolean playing;

    /** Cached so a language switch can re-render the summary without touching disk. */
    private Profile shown;

    public MainWindow(LauncherService service, Stage stage) {
        this.service = service;
        this.stage = stage;
        this.tray = new TrayIntegration(stage);
        this.progress = new UiProgress(stageLabel, progressBar, logArea);
    }

    public Scene build() {
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setFont(Font.font("Monospaced", 11));

        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setLeft(buildSidebar());
        root.setCenter(buildDetail());
        root.setBottom(buildFooter());

        Scene scene = new Scene(root, 1120, 740);
        Theme.apply(scene);

        applyTexts();
        refreshProfiles();
        refreshAccounts();
        showProfile(profileList.getSelectionModel().getSelectedItem());
        return scene;
    }

    // ---------------------------------------------------------------- header

    private HBox buildHeader() {
        Label mark = new Label("H");
        mark.getStyleClass().add("brand-mark");
        brandLabel.getStyleClass().add("brand");

        searchField.setPrefWidth(260);
        searchField.textProperty().addListener((observable, previous, value) -> applyFilter(value));

        languageBox.setItems(FXCollections.observableArrayList(Language.all()));
        languageBox.setValue(I18n.current());
        languageBox.setPrefWidth(150);
        languageBox.valueProperty().addListener((observable, previous, value) -> {
            if (value == null || value == I18n.current()) {
                return;
            }
            I18n.use(value);
            service.settings().language(value.code());
            saveSettingsQuietly();
            applyTexts();
            showProfile(shown);
        });

        HBox header = new HBox(10, mark, brandLabel, searchField, spacer(), languageTitle, languageBox);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    // ---------------------------------------------------------------- sidebar

    private VBox buildSidebar() {
        profileList.setPrefWidth(280);
        profileList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        profileList.setCellFactory(view -> new InstanceCell());
        profileList.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> showProfile(selected));
        // Double-click opens the editor, matching the list behaviour people expect.
        profileList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && profileList.getSelectionModel().getSelectedItem() != null) {
                editSelectedProfile();
            }
        });

        newButton.setMaxWidth(Double.MAX_VALUE);
        newButton.setOnAction(event -> createProfile());
        editButton.setMaxWidth(Double.MAX_VALUE);
        editButton.setOnAction(event -> editSelectedProfile());
        removeButton.setMaxWidth(Double.MAX_VALUE);
        removeButton.getStyleClass().add("danger");
        removeButton.setOnAction(event -> removeSelectedProfile());

        HBox buttons = new HBox(6, newButton, editButton, removeButton);
        HBox.setHgrow(newButton, Priority.ALWAYS);
        HBox.setHgrow(editButton, Priority.ALWAYS);
        HBox.setHgrow(removeButton, Priority.ALWAYS);

        instancesTitle.getStyleClass().add("section-title");

        VBox pane = new VBox(8, instancesTitle, profileList, buttons);
        pane.getStyleClass().add("sidebar");
        VBox.setVgrow(profileList, Priority.ALWAYS);
        return pane;
    }

    /** Name on top, version and loader underneath - enough to tell instances apart at a glance. */
    private static final class InstanceCell extends ListCell<Profile> {
        private final Label name = new Label();
        private final Label subtitle = new Label();
        private final VBox box = new VBox(2, name, subtitle);

        InstanceCell() {
            name.getStyleClass().add("instance-name");
            subtitle.getStyleClass().add("instance-subtitle");
        }

        @Override
        protected void updateItem(Profile profile, boolean empty) {
            super.updateItem(profile, empty);
            if (empty || profile == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            name.setText(profile.name());
            subtitle.setText(profile.minecraftVersion()
                    + (profile.loader() == LoaderType.VANILLA
                            ? ""
                            : "  ·  " + profile.loader().displayName()));
            setGraphic(box);
        }
    }

    // ---------------------------------------------------------------- detail

    private VBox buildDetail() {
        detailName.getStyleClass().add("detail-title");
        detailSubtitle.getStyleClass().add("detail-subtitle");

        installButton.setOnAction(event -> runInBackground(I18n.t("task.install"), () -> {
            Profile profile = requireSelected();
            service.installProfile(profile, progress);
            progress.log(I18n.t("log.installed", profile.effectiveVersionId()));
            Platform.runLater(() -> showProfile(profile));
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

        openFolderButton.setOnAction(event -> openGameFolder());
        detectJavaButton.setOnAction(event -> showDetectedJava());

        GridPane summary = new GridPane();
        summary.getStyleClass().add("summary");
        summary.setHgap(24);
        summary.setVgap(8);
        int row = 0;
        summary.addRow(row++, styled(summaryVersionTitle), styled(summaryVersionValue));
        summary.addRow(row++, styled(summaryLoaderTitle), styled(summaryLoaderValue));
        summary.addRow(row++, styled(summaryMemoryTitle), styled(summaryMemoryValue));
        summary.addRow(row++, styled(summaryJavaTitle), styled(summaryJavaValue));
        summary.addRow(row++, styled(summaryPlayedTitle), styled(summaryPlayedValue));
        summary.addRow(row, styled(summaryFolderTitle), styled(summaryFolderValue));

        HBox actions = new HBox(8, editButtonProxy(), installButton, modsButton,
                openFolderButton, detectJavaButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox pane = new VBox(14, detailName, detailSubtitle, summary, actions);
        pane.getStyleClass().add("detail");
        return pane;
    }

    /**
     * The detail panel needs an Edit button too, but a JavaFX node lives in one
     * place only, so this is a second button wired to the same action rather
     * than the sidebar's button moved.
     */
    private Button detailEdit;

    private Button editButtonProxy() {
        detailEdit = new Button();
        detailEdit.setOnAction(event -> editSelectedProfile());
        return detailEdit;
    }

    private static Label styled(Label label) {
        if (label.getStyleClass().contains("form-label") || label.getStyleClass().contains("summary-value")) {
            return label;
        }
        label.getStyleClass().add("summary-value");
        return label;
    }

    // ---------------------------------------------------------------- footer

    private VBox buildFooter() {
        progressBar.setMaxWidth(Double.MAX_VALUE);
        logArea.setPrefRowCount(10);

        playButton.setDefaultButton(true);
        playButton.getStyleClass().add("primary");
        playButton.setOnAction(event -> play());

        addAccountButton.setOnAction(event -> addOfflineAccount());
        signInButton.setOnAction(event -> signInWithMicrosoft());
        accountBox.setPrefWidth(230);

        HBox controls = new HBox(8, accountTitle, accountBox, addAccountButton, signInButton,
                spacer(), playButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        stageLabel.getStyleClass().add("muted");

        logPane.setContent(logArea);
        logPane.setExpanded(false);
        logPane.setAnimated(false);

        VBox footer = new VBox(8, controls, stageLabel, progressBar, logPane);
        footer.getStyleClass().add("footer");
        return footer;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    // ---------------------------------------------------------------- texts

    /**
     * Writes every visible string from the active language.
     *
     * <p>Called once when the window is built, and again after each language
     * change. Only static text is touched; the instance summary is re-rendered
     * separately from the selected profile.
     */
    private void applyTexts() {
        stage.setTitle(I18n.t("app.title"));
        brandLabel.setText(I18n.t("app.title"));
        searchField.setPromptText(I18n.t("search.prompt"));

        instancesTitle.setText(I18n.t("profiles.header"));
        newButton.setText(I18n.t("profiles.new"));
        editButton.setText(I18n.t("action.edit"));
        removeButton.setText(I18n.t("profiles.remove"));
        if (detailEdit != null) {
            detailEdit.setText(I18n.t("action.edit"));
        }

        summaryVersionTitle.setText(I18n.t("instance.summary.version"));
        summaryLoaderTitle.setText(I18n.t("instance.summary.loader"));
        summaryMemoryTitle.setText(I18n.t("instance.summary.memory"));
        summaryJavaTitle.setText(I18n.t("instance.summary.java"));
        summaryPlayedTitle.setText(I18n.t("instance.summary.lastPlayed"));
        summaryFolderTitle.setText(I18n.t("instance.summary.folder"));
        for (Label title : List.of(summaryVersionTitle, summaryLoaderTitle, summaryMemoryTitle,
                summaryJavaTitle, summaryPlayedTitle, summaryFolderTitle)) {
            if (!title.getStyleClass().contains("form-label")) {
                title.getStyleClass().add("form-label");
            }
        }

        installButton.setText(I18n.t("action.install"));
        modsButton.setText(I18n.t("action.mods"));
        openFolderButton.setText(I18n.t("action.openFolder"));
        detectJavaButton.setText(I18n.t("editor.java.detect"));
        addAccountButton.setText(I18n.t("action.addOffline"));
        signInButton.setText(I18n.t("action.signIn"));
        playButton.setText(I18n.t(playing ? "action.stop" : "action.play"));

        accountTitle.setText(I18n.t("label.account"));
        languageTitle.setText(I18n.t("label.language"));
        logPane.setText(I18n.t("log.title"));

        if (!busy) {
            stageLabel.setText(I18n.t("status.ready"));
        }
    }

    // ---------------------------------------------------------------- actions

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        visibleProfiles.setPredicate(profile -> needle.isEmpty()
                || profile.name().toLowerCase(Locale.ROOT).contains(needle)
                || profile.minecraftVersion().toLowerCase(Locale.ROOT).contains(needle)
                || profile.loader().displayName().toLowerCase(Locale.ROOT).contains(needle));
    }

    private ProfileDialog newDialog() {
        return new ProfileDialog(
                includeAll -> {
                    VersionManifest manifest = service.minecraftVersions();
                    return (includeAll ? manifest.versions() : manifest.releases())
                            .stream().map(VersionManifest.Entry::id).toList();
                },
                (loader, minecraftVersion) -> service.loaderVersions(loader, minecraftVersion)
                        .stream().map(LoaderVersion::version).toList(),
                service.settings().showAllVersions());
    }

    private void createProfile() {
        ProfileDialog dialog = newDialog();
        dialog.show(stage, null).ifPresent(profile -> {
            try {
                service.profiles().add(profile);
                service.profiles().save();
                rememberVersionPreference(dialog);
                refreshProfiles();
                profileList.getSelectionModel().select(profile);
            } catch (IOException e) {
                showError(I18n.t("profiles.create.failed"), e);
            }
        });
    }

    private void editSelectedProfile() {
        Profile profile = profileList.getSelectionModel().getSelectedItem();
        if (profile == null) {
            return;
        }
        ProfileDialog dialog = newDialog();
        dialog.show(stage, profile).ifPresent(edited -> {
            saveProfilesQuietly();
            rememberVersionPreference(dialog);
            refreshProfiles();
            profileList.getSelectionModel().select(edited);
            showProfile(edited);
        });
    }

    private void rememberVersionPreference(ProfileDialog dialog) {
        if (dialog.showsAllVersions() != service.settings().showAllVersions()) {
            service.settings().showAllVersions(dialog.showsAllVersions());
            saveSettingsQuietly();
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
        confirm.initOwner(stage);
        Theme.apply(confirm.getDialogPane());
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
        // The same rule the game enforces, checked here so the message names the
        // account rather than arriving as a lost connection inside the world.
        if (account.isOffline() && !Account.isValidUsername(account.username())) {
            showWarning(I18n.t("account.invalid.header"),
                    I18n.t("account.invalid.body", account.username()));
            return;
        }
        runInBackground(I18n.t("task.play"), () -> {
            Profile profile = requireSelected();
            session = service.launch(profile, account, progress,
                    progress::log,
                    exitCode -> {
                        progress.log(GameLauncher.describeExit(exitCode));
                        // The launcher comes back by itself: leaving it in the
                        // notification area after the game has closed is a window
                        // the player has to go and find.
                        tray.restore();
                        Platform.runLater(() -> {
                            playing = false;
                            playButton.setText(I18n.t("action.play"));
                            setBusy(false);
                            showProfile(shown);
                        });
                    });
            Platform.runLater(() -> {
                playing = true;
                playButton.setText(I18n.t("action.stop"));
                // The game is running; the launcher itself is free again.
                setBusy(false);
                playButton.setDisable(false);
                goToTray();
            });
        }, false);
    }

    /** Hides to the notification area, or minimises where there is no tray. */
    private void goToTray() {
        if (!service.settings().minimiseToTrayWhilePlaying()) {
            if (!service.settings().keepOpenWhilePlaying()) {
                stage.setIconified(true);
            }
            return;
        }
        boolean hidden = tray.hide(
                I18n.t("tray.tooltip"),
                I18n.t("tray.show"),
                I18n.t("tray.stop"),
                () -> {
                    if (session != null && session.isRunning()) {
                        session.terminate();
                    }
                });
        if (hidden) {
            progress.log(I18n.t("log.toTray"));
        } else {
            stage.setIconified(true);
        }
    }

    private void addOfflineAccount() {
        TextInputDialog dialog = new TextInputDialog(I18n.t("account.offline.default"));
        dialog.initOwner(stage);
        Theme.apply(dialog.getDialogPane());
        dialog.setTitle(I18n.t("account.offline.title"));
        dialog.setHeaderText(I18n.t("account.offline.header"));
        dialog.setContentText(I18n.t("account.offline.body"));
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name == null ? "" : name.trim();
            if (!Account.isValidUsername(trimmed)) {
                showWarning(I18n.t("account.invalid.header"),
                        I18n.t("account.invalid.body", trimmed));
                return;
            }
            Account account = Account.offline(trimmed);
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
            Platform.runLater(() -> logPane.setExpanded(true));
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

    // ---------------------------------------------------------------- data

    private void refreshProfiles() {
        Profile previous = profileList.getSelectionModel().getSelectedItem();
        allProfiles.setAll(service.profiles().byRecency());
        applyFilter(searchField.getText());
        if (previous != null && visibleProfiles.contains(previous)) {
            profileList.getSelectionModel().select(previous);
        } else {
            service.profiles().selected()
                    .filter(visibleProfiles::contains)
                    .ifPresent(profileList.getSelectionModel()::select);
        }
        if (profileList.getSelectionModel().getSelectedItem() == null && !visibleProfiles.isEmpty()) {
            profileList.getSelectionModel().selectFirst();
        }
    }

    private void refreshAccounts() {
        accountBox.setItems(FXCollections.observableArrayList(service.accounts().all()));
        service.accounts().selected().ifPresent(accountBox.getSelectionModel()::select);
    }

    /** Renders the selected instance. Read-only: every value here is changed in the dialog. */
    private void showProfile(Profile profile) {
        shown = profile;
        boolean present = profile != null;

        editButton.setDisable(!present);
        if (detailEdit != null) {
            detailEdit.setDisable(!present);
        }
        removeButton.setDisable(!present);
        installButton.setDisable(!present || busy);
        modsButton.setDisable(!present || busy);
        playButton.setDisable(!present || busy);

        if (!present) {
            detailName.setText(I18n.t("instance.none.title"));
            detailSubtitle.setText(I18n.t("instance.none.body"));
            for (Label value : List.of(summaryVersionValue, summaryLoaderValue, summaryMemoryValue,
                    summaryJavaValue, summaryPlayedValue, summaryFolderValue)) {
                value.setText("-");
            }
            return;
        }

        service.profiles().select(profile);
        detailName.setText(profile.name());
        detailSubtitle.setText(profile.versionId() == null
                ? I18n.t("instance.summary.notInstalled")
                : profile.effectiveVersionId());

        summaryVersionValue.setText(profile.minecraftVersion());
        summaryLoaderValue.setText(profile.loader() == LoaderType.VANILLA
                ? profile.loader().displayName()
                : profile.loader().displayName()
                        + (profile.loaderVersion() == null
                                ? "  ·  " + I18n.t("dialog.loaderVersion.auto")
                                : "  ·  " + profile.loaderVersion()));
        summaryMemoryValue.setText(I18n.t("unit.megabytes", String.valueOf(profile.memoryMegabytes())));
        summaryJavaValue.setText(profile.javaPath() == null
                ? I18n.t("instance.summary.java.auto")
                : profile.javaPath());
        summaryPlayedValue.setText(profile.lastPlayed() <= 0
                ? I18n.t("instance.summary.never")
                : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                        .withLocale(I18n.current().locale())
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(profile.lastPlayed())));
        summaryFolderValue.setText(service.profiles().gameDirectory(profile).toString());
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
                    logPane.setExpanded(true);
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
        boolean hasProfile = profileList.getSelectionModel().getSelectedItem() != null;
        playButton.setDisable(value || !hasProfile);
        installButton.setDisable(value || !hasProfile);
        modsButton.setDisable(value || !hasProfile);
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
        alert(Alert.AlertType.INFORMATION, header, message, 520);
    }

    private void showWarning(String header, String message) {
        alert(Alert.AlertType.WARNING, header, message, 520);
    }

    private void showError(String header, Throwable error) {
        alert(Alert.AlertType.ERROR, header,
                error.getMessage() == null ? error.toString() : error.getMessage(), 600);
    }

    private void alert(Alert.AlertType type, String header, String message, int width) {
        Alert alert = new Alert(type, message);
        alert.initOwner(stage);
        Theme.apply(alert.getDialogPane());
        alert.setHeaderText(header);
        alert.setTitle(header);
        alert.getDialogPane().setPrefWidth(width);
        alert.showAndWait();
    }

    /** Called when the window closes: drop the tray icon and stop the game if wanted. */
    public void shutdown() {
        tray.dispose();
        if (session != null && session.isRunning() && !service.settings().keepOpenWhilePlaying()) {
            session.terminate();
        }
    }
}
