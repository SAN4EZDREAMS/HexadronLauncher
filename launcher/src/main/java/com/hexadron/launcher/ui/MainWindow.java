package com.hexadron.launcher.ui;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.i18n.Language;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.install.loader.LoaderVersion;
import com.hexadron.launcher.launch.GameLauncher;
import com.hexadron.launcher.launch.JavaProvisioner;
import com.hexadron.launcher.launch.JavaRuntimes;
import com.hexadron.launcher.meta.VersionManifest;
import com.hexadron.launcher.mods.InstalledMod;
import com.hexadron.launcher.mods.ModLibrary;
import com.hexadron.launcher.mods.ModOrigin;
import com.hexadron.launcher.profile.Profile;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
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
import java.nio.file.Path;
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

    /** What this profile actually loads. Read-only; the browser is where it changes. */
    private final Label modsTitle = new Label();
    private final ListView<InstalledMod> modsList = new ListView<>();
    private final Label modsEmpty = new Label();

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
    private final Button removeAccountButton = new Button();

    private final Label brandLabel = new Label();
    private final Label instancesTitle = new Label();
    private final Label accountTitle = new Label();
    private final Label languageTitle = new Label();

    private GameLauncher.GameSession session;
    private volatile boolean busy;
    private boolean playing;

    /** True while a Microsoft sign-in is waiting for the browser. */
    private volatile boolean signingIn;

    /** Cached so a language switch can re-render the summary without touching disk. */
    private Profile shown;

    /** One browser window per profile, reused so a second click focuses it. */
    private final java.util.Map<String, ModBrowserWindow> browsers = new java.util.HashMap<>();

    public MainWindow(LauncherService service, Stage stage) {
        this.service = service;
        this.stage = stage;
        this.tray = new TrayIntegration(stage);
        this.progress = new UiProgress(stageLabel, progressBar, logArea);
        service.javaRuntimes().consent(this::askAboutJavaDownload);
    }

    /**
     * Asks whether the launcher may download a Java runtime.
     *
     * <p>Called from the worker thread, in the middle of a launch, and has to
     * come back with an answer - so it hops to the interface thread, waits, and
     * hands the result back. A latch rather than {@code showAndWait}, because
     * that method throws when it is called from anywhere but the FX thread.
     *
     * <p>Three answers rather than two. "Never ask again" exists because a user
     * who has their own reason to manage Java themselves should be able to say
     * so once, and get a message telling them what to install instead of the
     * same dialog before every launch.
     */
    private boolean askAboutJavaDownload(JavaProvisioner.Candidate candidate) {
        if (Platform.isFxApplicationThread()) {
            // Not expected - launches run on a worker - but blocking the FX
            // thread on itself would deadlock, so answer without asking.
            return false;
        }

        java.util.concurrent.CountDownLatch answered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean allowed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        Platform.runLater(() -> {
            try {
                ButtonType download = new ButtonType(I18n.t("java.download.confirm"),
                        ButtonBar.ButtonData.OK_DONE);
                ButtonType notNow = new ButtonType(I18n.t("java.download.notNow"),
                        ButtonBar.ButtonData.CANCEL_CLOSE);
                ButtonType never = new ButtonType(I18n.t("java.download.never"),
                        ButtonBar.ButtonData.NO);

                Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                        I18n.t("java.download.message",
                                candidate.major(),
                                candidate.releaseName(),
                                candidate.megabytes(),
                                JavaProvisioner.attribution()),
                        download, notNow, never);
                alert.initOwner(stage);
                Theme.apply(alert.getDialogPane());
                alert.setTitle(I18n.t("java.download.title", candidate.major()));
                alert.setHeaderText(I18n.t("java.download.header", candidate.major()));
                alert.getDialogPane().setPrefWidth(620);

                ButtonType chosen = alert.showAndWait().orElse(notNow);
                allowed.set(chosen == download);
                if (chosen == never) {
                    service.settings().javaDownloadPolicy(JavaRuntimes.DownloadPolicy.NEVER);
                    saveSettingsQuietly();
                }
            } finally {
                answered.countDown();
            }
        });

        try {
            answered.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return allowed.get();
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

        // Installing mods moved into its own window. It needs a search, a sort,
        // an installed list and a per-mod action - none of which fits beside an
        // instance summary, and all of which is a task of its own.
        modsButton.setOnAction(event -> openModBrowser());

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

        modsTitle.getStyleClass().add("section-title");
        modsList.setCellFactory(view -> new ModCell());
        modsList.setPlaceholder(modsEmpty);
        modsList.setPrefHeight(150);
        modsList.setFocusTraversable(false);
        VBox modsBox = new VBox(6, modsTitle, modsList);
        VBox.setVgrow(modsList, Priority.ALWAYS);

        VBox pane = new VBox(14, detailName, detailSubtitle, summary, actions, modsBox);
        pane.getStyleClass().add("detail");
        VBox.setVgrow(modsBox, Priority.ALWAYS);
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

    /** One line per mod: what it is, and whether the pack owns it. */
    private static final class ModCell extends ListCell<InstalledMod> {
        private final Label name = new Label();
        private final Label badge = new Label();
        private final HBox box = new HBox(10, name, badge);

        ModCell() {
            name.getStyleClass().add("summary-value");
            badge.getStyleClass().add("badge");
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(InstalledMod mod, boolean empty) {
            super.updateItem(mod, empty);
            if (empty || mod == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            name.setText(mod.title());
            badge.setText(switch (mod.origin()) {
                case PACK -> I18n.t("mods.origin.pack");
                case DEPENDENCY -> I18n.t("mods.origin.dependency");
                case MANUAL -> I18n.t("mods.origin.manual");
            });
            badge.getStyleClass().removeAll("badge-pack");
            if (mod.origin() == ModOrigin.PACK) {
                badge.getStyleClass().add("badge-pack");
            }
            setGraphic(box);
        }
    }

    private void openModBrowser() {
        Profile profile = profileList.getSelectionModel().getSelectedItem();
        if (profile == null) {
            return;
        }
        if (profile.loader() == LoaderType.VANILLA) {
            showWarning(I18n.t("mods.vanilla.header"), I18n.t("mods.vanilla"));
            return;
        }
        browsers.computeIfAbsent(profile.id(),
                        id -> new ModBrowserWindow(service, stage, profile, () -> refreshModsList(profile)))
                .show();
    }

    /** Re-reads the lock file for the summary list. Cheap: one small JSON file. */
    private void refreshModsList(Profile profile) {
        if (profile == null) {
            modsList.setItems(FXCollections.observableArrayList());
            modsTitle.setText(I18n.t("instance.mods", 0));
            return;
        }
        ModLibrary installed = service.installedMods(profile);
        modsList.setItems(FXCollections.observableArrayList(installed.all()));
        modsTitle.setText(I18n.t("instance.mods", installed.size()));
        modsEmpty.setText(I18n.t("instance.mods.empty"));
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
        removeAccountButton.getStyleClass().add("danger");
        removeAccountButton.setOnAction(event -> removeSelectedAccount());
        accountBox.setPrefWidth(230);
        accountBox.valueProperty().addListener((observable, previous, value) ->
                removeAccountButton.setDisable(value == null));

        HBox controls = new HBox(8, accountTitle, accountBox, addAccountButton, signInButton,
                removeAccountButton, spacer(), playButton);
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
        modsEmpty.setText(I18n.t("instance.mods.empty"));
        openFolderButton.setText(I18n.t("action.openFolder"));
        detectJavaButton.setText(I18n.t("editor.java.detect"));
        addAccountButton.setText(I18n.t("action.addOffline"));
        signInButton.setText(I18n.t(signingIn ? "action.signIn.cancel" : "action.signIn"));
        removeAccountButton.setText(I18n.t("action.removeAccount"));
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
                service::loaderSupport,
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
            // The browser is built around one version and one loader, and it
            // asked the platforms about them when it opened. An edit can change
            // both, so the window is discarded rather than left showing answers
            // to a question that is no longer being asked.
            closeBrowser(profile.id());
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
        // Three answers, not two. "Remove" on its own had to mean one of them,
        // and whichever it meant was wrong half the time: a player clearing out
        // an old instance wants the disk space back, a player who misclicked
        // must not lose a world to it. Asking costs one extra button.
        ButtonType keepFiles = new ButtonType(
                I18n.t("profiles.remove.keepFiles"), ButtonBar.ButtonData.OTHER);
        ButtonType deleteFiles = new ButtonType(
                I18n.t("profiles.remove.deleteFiles"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(
                I18n.t("action.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.t("profiles.remove.body", profile.name(),
                        service.profiles().gameDirectory(profile)),
                keepFiles, deleteFiles, cancel);
        confirm.initOwner(stage);
        Theme.apply(confirm.getDialogPane());
        confirm.setHeaderText(I18n.t("profiles.remove.header"));
        confirm.getDialogPane().setPrefWidth(640);

        var answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() == cancel) {
            return;
        }

        closeBrowser(profile.id());
        if (answer.get() == deleteFiles) {
            try {
                List<Path> undeleted = service.profiles().removeWithFiles(profile);
                if (undeleted.isEmpty()) {
                    progress.log(I18n.t("profiles.remove.deleted", profile.name()));
                } else {
                    // Named, not swallowed. A folder still sitting there after
                    // "delete files" needs a reason, and on Windows the reason is
                    // almost always a file the game has not released yet.
                    progress.log(I18n.t("profiles.remove.deleteFailed", undeleted.size()));
                    undeleted.stream().limit(10).forEach(path -> progress.log("  " + path));
                }
            } catch (IOException e) {
                showError(I18n.t("profiles.remove.header"), e);
            }
        } else {
            service.profiles().remove(profile);
        }
        saveProfilesQuietly();
        refreshProfiles();
    }

    private void closeBrowser(String profileId) {
        ModBrowserWindow browser = browsers.remove(profileId);
        if (browser != null) {
            browser.close();
        }
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
                        progress.log(GameLauncher.describeExit(
                                exitCode, profile.wrapperCommand()));
                        // The status line has to be closed off here. This task
                        // runs with clearBusyOnSuccess off - the "success" of a
                        // launch is a game still running - so nothing else ever
                        // takes the bar out of the indeterminate state that
                        // stage() put it in.
                        progress.finish(I18n.t("status.gameClosed"));
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
            progress.finish(I18n.t("status.playing"));
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

    /**
     * Removes the selected account.
     *
     * <p>Worth a confirmation for two different reasons, so the text names both:
     * a Microsoft account has to be signed in again afterwards, and an offline
     * account's identity is derived from its name - recreating it with the same
     * spelling restores the same worlds, with a different spelling does not.
     */
    private void removeSelectedAccount() {
        Account account = accountBox.getValue();
        if (account == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.t(account.isOffline() ? "account.remove.offlineBody" : "account.remove.msaBody",
                        account.username()));
        confirm.initOwner(stage);
        Theme.apply(confirm.getDialogPane());
        confirm.setHeaderText(I18n.t("account.remove.header"));
        if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            return;
        }
        try {
            // Removes the stored credentials as well as the list entry. Signing
            // out of the launcher is not the same as revoking the launcher's
            // access to the Microsoft account, and the message below says so.
            service.signOut(account);
        } catch (IOException e) {
            showError(I18n.t("account.remove.failed"), e);
        }
        refreshAccounts();
        progress.log(I18n.t("account.removed", account.username()));
        if (!account.isOffline()) {
            showInfo(I18n.t("account.revoke.header"),
                    I18n.t("account.revoke.body",
                            com.hexadron.launcher.auth.MicrosoftAuth.CONSENT_MANAGEMENT_URL));
        }
    }

    /**
     * Starts a Microsoft sign-in, or cancels the one already waiting.
     *
     * <p>The second behaviour is the important one. A sign-in waits for the
     * browser to come back, and closing the browser tab sends nothing - so the
     * launcher waited out the whole timeout while every other action refused to
     * start, and pressing the button again silently did nothing because the
     * launcher was busy with the sign-in the user had just abandoned. Now the
     * button says Cancel for as long as it is one.
     */
    private void signInWithMicrosoft() {
        if (signingIn) {
            progress.cancel();
            progress.log(I18n.t("log.signInCancelled"));
            return;
        }
        if (!service.settings().hasMicrosoftClientId()) {
            showWarning(I18n.t("ms.notConfigured.header"), I18n.t("ms.notConfigured.body"));
            return;
        }
        signingIn = true;
        signInButton.setText(I18n.t("action.signIn.cancel"));
        runInBackground(I18n.t("task.signIn"), () -> {
            try {
                Account account = service.signInWithMicrosoft(
                        MainWindow::openInSystemBrowser,
                        prompt -> {
                            progress.log(I18n.t("log.signInPrompt",
                                    prompt.verificationUri(), prompt.userCode()));
                            Platform.runLater(() -> showInfo(I18n.t("ms.signIn.header"),
                                    I18n.t("ms.signIn.body",
                                            prompt.verificationUri(), prompt.userCode())));
                        },
                        progress);
                Platform.runLater(() -> {
                    refreshAccounts();
                    accountBox.getSelectionModel().select(account);
                });
            } catch (IOException e) {
                // A cancellation is not a fault, and must not arrive as an error
                // dialog about something the user just chose to do.
                if (progress.isCancelled()) {
                    progress.finish(I18n.t("log.signInCancelled"));
                    return;
                }
                throw e;
            } finally {
                Platform.runLater(this::endSignIn);
            }
        });
    }

    private void endSignIn() {
        signingIn = false;
        signInButton.setText(I18n.t("action.signIn"));
    }

    /**
     * Opens the Microsoft sign-in page in the user's own browser.
     *
     * <p>The system browser, never an embedded web view. RFC 8252 §8.12 forbids
     * the embedded view for native apps, and the reason a user can act on is
     * this: in their own browser they see Microsoft's address bar and
     * certificate, and their password manager, passkey and two-factor prompts
     * work exactly as they always do. In a window the launcher drew, none of
     * that is true and none of it can be checked.
     */
    private static void openInSystemBrowser(java.net.URI uri) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(uri);
                return;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Some Linux sessions have no AWT Desktop integration. Fall through.
        }
        try {
            String[] command;
            if (com.hexadron.launcher.util.Platform.isWindows()) {
                command = new String[]{"rundll32", "url.dll,FileProtocolHandler", uri.toString()};
            } else if (com.hexadron.launcher.util.Platform.isMac()) {
                command = new String[]{"open", uri.toString()};
            } else {
                command = new String[]{"xdg-open", uri.toString()};
            }
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            // The authorization URL carries a PKCE challenge, not the verifier,
            // so it is not a credential and printing it is a usable last resort.
            System.out.println("Open this URL to sign in: " + uri);
        }
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
        removeAccountButton.setDisable(accountBox.getValue() == null);
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
        modsButton.setDisable(!present);
        playButton.setDisable(!present || busy);

        if (!present) {
            detailName.setText(I18n.t("instance.none.title"));
            detailSubtitle.setText(I18n.t("instance.none.body"));
            for (Label value : List.of(summaryVersionValue, summaryLoaderValue, summaryMemoryValue,
                    summaryJavaValue, summaryPlayedValue, summaryFolderValue)) {
                value.setText("-");
            }
            refreshModsList(null);
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
        refreshModsList(profile);
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
        // The browser runs its own downloads in its own window, so it stays
        // reachable while the launcher is busy with something else.
        modsButton.setDisable(!hasProfile);
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
