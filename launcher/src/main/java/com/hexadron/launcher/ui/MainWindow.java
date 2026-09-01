package com.hexadron.launcher.ui;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.install.loader.LoaderVersion;
import com.hexadron.launcher.launch.GameLauncher;
import com.hexadron.launcher.launch.JavaProvisioner;
import com.hexadron.launcher.launch.JavaRuntimes;
import com.hexadron.launcher.meta.VersionManifest;
import com.hexadron.launcher.mods.ModEntry;
import com.hexadron.launcher.mods.ModOrigin;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileLayout;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
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
public final class MainWindow implements ProfileHost {

    private final LauncherService service;
    private final Stage stage;
    private final TrayIntegration tray;

    /**
     * The profiles, the search, and the selection - once, for both interfaces.
     *
     * <p>This is the whole of the synchronisation between the list and the
     * inventory grid: there is only one of each of these, both views read them,
     * and neither keeps a copy. A rename, a reorder, a new group or a click
     * changes what is here, and both views are rebuilt from it - so there is no
     * state in one view that the other could be out of step with.
     */
    private final List<Profile> profiles = new ArrayList<>();

    /** The search text, lower case and trimmed. Empty means no filter. */
    private String filter = "";

    private Profile selectedProfile;

    private final ProfileListView listView;
    private final InventoryView inventoryView;

    /**
     * The switchable area: everything above the footer.
     *
     * <p>The grid is a sibling of the whole upper block rather than a
     * replacement for the middle of it, because what it has to do is cover the
     * header, the instance list and the summary together. The footer stays: the
     * account and the Play button belong to neither view, and a grid you have to
     * leave in order to press Play would be a worse grid.
     */
    private final StackPane content = new StackPane();
    private final VBox inventoryPanel = new VBox();

    /** True while the cover animation runs, so a second click cannot interrupt it. */
    private boolean switching;

    private final TextField searchField = new TextField();
    private final ComboBox<Account> accountBox = new ComboBox<>();

    /**
     * The profile's picture, beside its name.
     *
     * <p>The same picture the list and the grid show - the chosen one, or the
     * loader's mark - and drawn larger here because this is the one place with
     * room for it. The list has thirty rows to fit; this panel describes one
     * instance, and the picture is the fastest part of it to read.
     */
    private final StackPane detailIcon = new StackPane();

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
    private final ListView<ModEntry> modsList = new ListView<>();
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
    private final Button editAccountButton = new Button();

    private final Button settingsButton = new Button();
    private final Button gridSettingsButton = new Button();
    private final Button aboutButton = new Button();
    private final Button gridAboutButton = new Button();
    private final Button modeButton = new Button();
    private final Button gridModeButton = new Button();
    private final Button newGroupButton = new Button();
    private final Button sortButton = new Button();
    private final Button gridNewButton = new Button();
    private final Button gridNewGroupButton = new Button();
    private final Button gridSortButton = new Button();
    private final TextField gridSearchField = new TextField();
    private final Label gridTitle = new Label();
    private final Label gridHint = new Label();

    /**
     * A refusal, said over the bottom of whichever view is showing.
     *
     * <p>It was a label in the grid's toolbar, sharing a row with five buttons,
     * which meant the one message worth reading was the one thing on screen with
     * no room to be read - it arrived ellipsised. A panel over the content area
     * has the width for a whole sentence, wraps, and is in front of the thing the
     * message is about rather than off in a corner.
     *
     * <p>Not a dialog: these come from clicking a small button on the grid's
     * edge, and a modal in front of that is a modal in the way of the next click.
     */
    private final HBox toast = new HBox(10);
    private final Label toastText = new Label();
    private javafx.animation.PauseTransition toastTimer;

    private final Label brandLabel = new Label();
    private final Label instancesTitle = new Label();
    private final Label accountTitle = new Label();

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
        // Mod logos are fetched once and kept in the data folder, so the second
        // start of the launcher draws the list without a connection. How much of
        // it they may fill is the user's to decide; the folder is swept to that
        // size as soon as it is known.
        ModIcons.cacheBudget(service.settings().modIconCacheBytes());
        ModIcons.cacheDirectory(service.dirs().cache().resolve("mod-icons"));
        this.progress = new UiProgress(stageLabel, progressBar, logArea);
        // After the fields above: both views are handed this window as their
        // host and read everything through it, so nothing they read may still be
        // uninitialised when they are built.
        this.listView = new ProfileListView(this);
        this.inventoryView = new InventoryView(this);
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

        BorderPane upper = new BorderPane();
        upper.setTop(buildHeader());
        upper.setLeft(buildSidebar());
        upper.setCenter(buildDetail());

        inventoryPanel.getStyleClass().add("inventory-panel");
        inventoryPanel.getChildren().setAll(buildInventoryBar(), inventoryView.node());
        VBox.setVgrow(inventoryView.node(), Priority.ALWAYS);
        inventoryPanel.setVisible(false);
        inventoryPanel.setManaged(false);

        content.getChildren().setAll(upper, inventoryPanel, buildToast());
        // Clipped, because the grid is slid in from above its own top edge, and
        // an unclipped child in JavaFX paints outside its parent quite happily -
        // which during the animation means over the title bar.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(content.widthProperty());
        clip.heightProperty().bind(content.heightProperty());
        content.setClip(clip);

        BorderPane root = new BorderPane();
        root.setCenter(content);
        root.setBottom(buildFooter());

        Scene scene = new Scene(root, 1180, 760);
        Theme.apply(scene);
        // A floor rather than a preference: below this the toolbars cannot show
        // their own labels, and the grid starts scrolling sideways at nine
        // columns. Both are worse than a window that refuses to get smaller.
        stage.setMinWidth(1000);
        stage.setMinHeight(640);

        applyTexts();
        refreshProfiles();
        refreshAccounts();
        showProfile(selectedProfile);
        // Reopen in the interface the launcher was closed in, and without the
        // animation: an animation on start-up is a launcher that looks slower
        // than it is.
        if (layout().mode() == ProfileLayout.Mode.INVENTORY) {
            setMode(ProfileLayout.Mode.INVENTORY, false);
        }
        return scene;
    }

    // ---------------------------------------------------------------- header

    private HBox buildHeader() {
        Label mark = new Label("H");
        mark.getStyleClass().add("brand-mark");
        brandLabel.getStyleClass().add("brand");

        searchField.setPrefWidth(260);
        searchField.textProperty().addListener((observable, previous, value) -> applyFilter(value));

        modeButton.setOnAction(event -> toggleMode());
        settingsButton.setOnAction(event -> openSettings());
        asIcon(settingsButton, Glyphs.settings(), "settings.open");
        aboutButton.setOnAction(event -> openAbout());
        asIcon(aboutButton, Glyphs.about(), "about.open");

        // No language box here. The setting lives in the settings window, and a
        // setting with two homes is a setting that disagrees with itself; the
        // two buttons left are the two that are pressed often enough to earn the
        // far end of the bar.
        HBox header = new HBox(10, mark, brandLabel, searchField, spacer(),
                modeButton, aboutButton, settingsButton);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        keepLabels(header);
        // The search field is what gives way when the window is narrow. A
        // button whose text has been replaced by an ellipsis is a button nobody
        // can read, and the field is still usable at half the width.
        searchField.setMinWidth(90);
        return header;
    }

    // ---------------------------------------------------------------- sidebar

    private VBox buildSidebar() {
        Region list = listView.node();
        list.setPrefWidth(300);
        list.setMinWidth(240);

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

        // Grouping and sorting are arrangement, not instance settings, so they
        // sit with the list rather than in the instance editor.
        newGroupButton.setMaxWidth(Double.MAX_VALUE);
        newGroupButton.setOnAction(event -> createGroup(null));
        sortButton.setMaxWidth(Double.MAX_VALUE);
        sortButton.setOnAction(event -> sortAlphabetically());
        HBox arrange = new HBox(6, newGroupButton, sortButton);
        HBox.setHgrow(newGroupButton, Priority.ALWAYS);
        HBox.setHgrow(sortButton, Priority.ALWAYS);

        instancesTitle.getStyleClass().add("section-title");

        VBox pane = new VBox(8, instancesTitle, list, buttons, arrange);
        pane.getStyleClass().add("sidebar");
        VBox.setVgrow(list, Priority.ALWAYS);
        return pane;
    }

    // ---------------------------------------------------------------- inventory

    /**
     * The bar above the grid.
     *
     * <p>The grid covers the header, so it has to carry the header's own
     * controls again - and the search field is bound to the one it covered
     * rather than being a second search. Typing in either is typing in both,
     * which is the same principle as the arrangement: one piece of state, two
     * places it can be seen.
     */
    private HBox buildInventoryBar() {
        Label mark = new Label("H");
        mark.getStyleClass().add("brand-mark");
        gridTitle.getStyleClass().add("brand");

        gridSearchField.setPrefWidth(240);
        gridSearchField.textProperty().bindBidirectional(searchField.textProperty());

        gridNewButton.setOnAction(event -> createProfile());
        gridNewGroupButton.setOnAction(event -> createGroup(null));
        gridSortButton.setOnAction(event -> sortAlphabetically());
        gridModeButton.setOnAction(event -> toggleMode());
        gridSettingsButton.setOnAction(event -> openSettings());
        asIcon(gridSettingsButton, Glyphs.settings(), "settings.open");
        gridAboutButton.setOnAction(event -> openAbout());
        asIcon(gridAboutButton, Glyphs.about(), "about.open");

        gridHint.getStyleClass().add("muted");

        HBox bar = new HBox(10, mark, gridTitle, gridSearchField, gridNewButton,
                gridNewGroupButton, gridSortButton, spacer(), gridHint,
                gridModeButton, gridAboutButton, gridSettingsButton);
        bar.getStyleClass().addAll("header", "inventory-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        keepLabels(bar);
        gridSearchField.setMinWidth(90);
        // The hint gives way before anything else: it is the one thing here that
        // is only ever read once.
        gridHint.setMinWidth(0);
        gridHint.setMaxWidth(320);
        return bar;
    }

    private HBox buildToast() {
        toastText.setWrapText(true);
        toastText.setMaxWidth(620);
        toastText.setMinHeight(Region.USE_PREF_SIZE);
        toastText.getStyleClass().add("toast-text");

        Region marker = new Region();
        marker.getStyleClass().add("toast-marker");

        Label close = new Label("×");
        close.getStyleClass().add("toast-close");
        javafx.scene.control.Tooltip.install(close,
                new javafx.scene.control.Tooltip(I18n.t("toast.dismiss")));

        toast.getChildren().setAll(marker, toastText, close);
        toast.getStyleClass().add("toast");
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setMaxWidth(Region.USE_PREF_SIZE);
        toast.setMaxHeight(Region.USE_PREF_SIZE);
        toast.setVisible(false);
        toast.setManaged(false);
        toast.setOnMouseClicked(event -> dismissToast());
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 24, 22, 24));
        return toast;
    }

    private void toggleMode() {
        setMode(layout().mode().other(), true);
    }

    /**
     * Shows one of the two interfaces.
     *
     * <p>The grid slides down over the upper block and fades in as it goes,
     * because the two interfaces are the same instances and a cut between them
     * reads as a different screen. 260 ms: long enough to be seen as a
     * movement, short enough that somebody switching back and forth is not
     * waiting for it.
     *
     * <p>The chosen mode is saved straight away, so the launcher reopens in the
     * interface it was left in.
     */
    private void setMode(ProfileLayout.Mode mode, boolean animate) {
        if (switching) {
            return;
        }
        layout().mode(mode);
        saveProfilesQuietly();
        applyModeTexts();

        if (mode == ProfileLayout.Mode.INVENTORY) {
            inventoryView.rebuild();
            inventoryPanel.setManaged(true);
            inventoryPanel.setVisible(true);
            if (!animate) {
                inventoryPanel.setTranslateY(0);
                inventoryPanel.setOpacity(1);
                return;
            }
            slide(-coverHeight(), 0, () -> { });
        } else {
            if (!animate) {
                hideInventoryPanel();
                return;
            }
            slide(0, -coverHeight(), this::hideInventoryPanel);
        }
    }

    private void slide(double from, double to, Runnable done) {
        switching = true;
        inventoryPanel.setTranslateY(from);

        TranslateTransition move = new TranslateTransition(Duration.millis(260), inventoryPanel);
        move.setFromY(from);
        move.setToY(to);
        move.setInterpolator(Interpolator.EASE_BOTH);

        FadeTransition fade = new FadeTransition(Duration.millis(220), inventoryPanel);
        fade.setFromValue(to == 0 ? 0.2 : 1);
        fade.setToValue(to == 0 ? 1 : 0);

        ParallelTransition together = new ParallelTransition(move, fade);
        together.setOnFinished(event -> {
            switching = false;
            done.run();
        });
        together.play();
    }

    private void hideInventoryPanel() {
        inventoryPanel.setVisible(false);
        inventoryPanel.setManaged(false);
        inventoryPanel.setTranslateY(0);
        inventoryPanel.setOpacity(1);
    }

    /** How far the grid has to travel to be off the top. Falls back before first layout. */
    private double coverHeight() {
        double height = content.getHeight();
        return height > 0 ? height : 640;
    }

    private void sortAlphabetically() {
        layout().sortByName(profiles);
        layoutChanged();
    }

    /**
     * Opens the settings window and applies what came back.
     *
     * <p>Three of the answers need something doing beyond being written to
     * launcher.json: a language change has to re-read every visible string, a
     * grid change has to redraw both views, and a refused grid change has to be
     * said out loud - a spinner that silently springs back looks like a bug in
     * the spinner.
     */
    private void openSettingsWindow() {
        SettingsDialog dialog = new SettingsDialog(service.settings(), layout(), service.dirs(),
                service.secretStore());
        dialog.show(stage).ifPresent(result -> {
            saveSettingsQuietly();
            // Applied here rather than only at the next start: the settings
            // window is where somebody fixes a route that is not working, and
            // "restart the launcher" is not an answer to that.
            service.applyProxy();
            // Same reasoning: somebody who has just made the logo cache smaller
            // wants that disk space back now, not at the next start.
            ModIcons.cacheBudget(service.settings().modIconCacheBytes());
            if (result.gridChanged()) {
                saveProfilesQuietly();
            }
            if (result.languageChanged()) {
                applyTexts();
            }
            rebuildViews();
            showProfile(shown);
            if (!result.refused().isEmpty()) {
                showWarning(I18n.t("settings.grid.refusedHeader"),
                        String.join("\n\n", result.refused()));
            }
        });
    }

    /**
     * Puts a message where the grid is, for a few seconds.
     *
     * <p>Beside the thing it is about, and not modal: the refusals this carries
     * come from clicking a small button on the grid's edge, and a dialog in front
     * of that is a dialog in the way of the next click. It also goes to the log,
     * which is where anybody asking why will be sent.
     */
    /**
     * Shows a message over the bottom of the content area for a few seconds.
     *
     * <p>Nine seconds, because these sentences say what to do about the refusal
     * as well as what it was, and five is not long enough to read one and act on
     * it. A click dismisses it sooner, and the log keeps it afterwards.
     */
    @Override
    public void hint(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        toastText.setText(message);
        progress.log(message);

        toast.setManaged(true);
        toast.setVisible(true);
        FadeTransition in = new FadeTransition(Duration.millis(160), toast);
        in.setFromValue(toast.getOpacity() < 1 ? 0 : 1);
        in.setToValue(1);
        in.play();

        if (toastTimer != null) {
            toastTimer.stop();
        }
        toastTimer = new javafx.animation.PauseTransition(Duration.seconds(9));
        toastTimer.setOnFinished(event -> dismissToast());
        toastTimer.play();
    }

    private void dismissToast() {
        if (toastTimer != null) {
            toastTimer.stop();
            toastTimer = null;
        }
        if (!toast.isVisible()) {
            return;
        }
        FadeTransition out = new FadeTransition(Duration.millis(160), toast);
        out.setFromValue(toast.getOpacity());
        out.setToValue(0);
        out.setOnFinished(event -> {
            toast.setVisible(false);
            toast.setManaged(false);
        });
        out.play();
    }

    // ---------------------------------------------------------------- detail

    /** Edge length of the picture beside the detail title, in pixels. */
    private static final double DETAIL_ICON_SIZE = 52;

    private VBox buildDetail() {
        detailName.getStyleClass().add("detail-title");
        detailSubtitle.getStyleClass().add("detail-subtitle");
        detailIcon.getStyleClass().add("detail-icon");

        installButton.setOnAction(event -> installSelected());

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

        // The two lines are stacked and the picture stands beside both of them,
        // rather than beside the name alone: the version under the name belongs
        // to the same instance, and a picture centred on one line of a two-line
        // heading sits visibly high.
        VBox titles = new VBox(2, detailName, detailSubtitle);
        titles.setAlignment(Pos.CENTER_LEFT);
        HBox heading = new HBox(14, detailIcon, titles);
        heading.setAlignment(Pos.CENTER_LEFT);

        VBox pane = new VBox(14, heading, summary, actions, modsBox);
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

    /**
     * One line per mod: its logo, what it is, and where it came from.
     *
     * <p>Read-only, unlike the same row in the mod browser. This list is part of
     * the panel that describes an instance, and a Remove button next to a
     * single-click list on the launcher's front page is a mod deleted by
     * accident. The browser is one button away and is where mods are changed.
     */
    private static final class ModCell extends ListCell<ModEntry> {
        private final ModIcons.Tile icon = new ModIcons.Tile(24);
        private final Label name = new Label();
        private final Label version = new Label();
        private final Label badge = new Label();
        private final HBox box = new HBox(10, icon, name, version, badge);

        ModCell() {
            name.getStyleClass().add("summary-value");
            version.getStyleClass().add("instance-subtitle");
            badge.getStyleClass().add("badge");
            box.setAlignment(Pos.CENTER_LEFT);

            // The name is the only part allowed to grow, and the only part
            // allowed to shrink: a mod called "Adventure Craft: Reforged
            // Edition" must lose its own tail rather than push the badge out of
            // a cell that clips.
            name.setMaxWidth(Double.MAX_VALUE);
            name.setMinWidth(0);
            HBox.setHgrow(name, Priority.ALWAYS);
            version.setMinWidth(Region.USE_PREF_SIZE);
            badge.setMinWidth(Region.USE_PREF_SIZE);
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(ModEntry mod, boolean empty) {
            super.updateItem(mod, empty);
            if (empty || mod == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            icon.show(mod);
            name.setText(mod.title());
            version.setText(mod.version() == null ? "" : mod.version());
            badge.setText(ModLabels.badge(mod));
            // Only when it actually differs. A style class changed from inside a
            // list cell's update is resolved a frame late - the cell is updated
            // during the list's layout, after CSS has run - so touching one for
            // no reason is a badge that is drawn at the wrong width for a frame
            // and corrected afterwards.
            setBadgeClass(badge, "badge-off", !mod.enabled());
            setBadgeClass(badge, "badge-wrong", mod.enabled() && mod.isWrongVersion());
            setBadgeClass(badge, "badge-pack", mod.enabled() && !mod.isWrongVersion()
                    && mod.origin() == ModOrigin.PACK);
            setBadgeClass(badge, "badge-dependency", mod.enabled() && !mod.isWrongVersion()
                    && mod.origin() == ModOrigin.DEPENDENCY);
            setGraphic(box);
        }
    }

    /**
     * The Install / repair button.
     *
     * <p>Asks for a full verification, unlike a launch: the launcher normally
     * trusts its record of which files it has already checked, and this button
     * exists for the case where that record is exactly what is in doubt.
     */
    private void installSelected() {
        runInBackground(I18n.t("task.install"), () -> {
            Profile profile = requireSelected();
            service.installProfile(profile, progress, true);
            progress.log(I18n.t("log.installed", profile.effectiveVersionId()));
            Platform.runLater(() -> {
                showProfile(profile);
                // The list and the grid show the installed state in the row
                // subtitle, so a finished install has to redraw them.
                rebuildViews();
            });
        });
    }

    /**
     * Opens the account editor for whichever account is selected.
     *
     * <p>The skin is saved here rather than inside the dialog: the dialog hands
     * back what was chosen and touches no state, the same rule as the instance
     * and group editors, so a cancelled edit cannot leave half a change behind.
     * What the dialog does do by itself is talk to Mojang, and only when a
     * button in it is pressed - those are writes to an account elsewhere, and
     * Cancel could not undo them anyway.
     */
    private void editSelectedAccount() {
        Account account = accountBox.getValue();
        if (account == null) {
            return;
        }
        new AccountDialog(account, service.skins(), service.skinCredentials()).show(stage).ifPresent(result -> {
            service.skins().put(account.id(), result.skin());
            try {
                service.skins().save();
            } catch (IOException e) {
                showError(I18n.t("account.edit.failed"), e);
            }
        });
    }

    private void openAbout() {
        new AboutDialog().show(stage);
    }

    private void openModBrowser() {
        Profile profile = selectedProfile;
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

    /** Adds or removes a style class, and only when it is not already right. */
    private static void setBadgeClass(Label badge, String name, boolean wanted) {
        if (badge.getStyleClass().contains(name) == wanted) {
            return;
        }
        if (wanted) {
            badge.getStyleClass().add(name);
        } else {
            badge.getStyleClass().remove(name);
        }
    }

    /**
     * Re-reads the mods folder for the summary list.
     *
     * <p>More than the lock file, now that the list includes what the player put
     * there themselves: the folder is listed and each jar's descriptor is read.
     * {@code ModScan} keeps those descriptors, keyed by size and modification
     * time, so redrawing the list after an install re-reads only what changed.
     */
    private void refreshModsList(Profile profile) {
        if (profile == null) {
            modsList.setItems(FXCollections.observableArrayList());
            modsTitle.setText(I18n.t("instance.mods", 0));
            return;
        }
        java.util.List<ModEntry> installed = service.modsIn(profile);
        modsList.setItems(FXCollections.observableArrayList(installed));
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
        editAccountButton.setOnAction(event -> editSelectedAccount());
        removeAccountButton.getStyleClass().add("danger");
        removeAccountButton.setOnAction(event -> removeSelectedAccount());
        accountBox.setPrefWidth(230);
        accountBox.valueProperty().addListener((observable, previous, value) ->
                removeAccountButton.setDisable(value == null));
        accountBox.valueProperty().addListener((observable, previous, value) ->
                editAccountButton.setDisable(value == null));
        editAccountButton.setDisable(accountBox.getValue() == null);

        HBox controls = new HBox(8, accountTitle, accountBox, addAccountButton, signInButton,
                editAccountButton, removeAccountButton, spacer(), playButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        stageLabel.getStyleClass().add("muted");

        logPane.setContent(logArea);
        logPane.setExpanded(false);
        logPane.setAnimated(false);

        VBox footer = new VBox(8, controls, stageLabel, progressBar, logPane);
        footer.getStyleClass().add("footer");
        return footer;
    }

    /**
     * Stops a toolbar from shrinking its buttons below their own text.
     *
     * <p>An HBox shrinks its children when the window is narrower than their
     * preferred widths, and a Button that has been shrunk shows an ellipsis - so
     * at the default window size on a scaled display the bar read "Створ...",
     * "Нова гр...", "За алфаві...". Fixing each button's minimum at its
     * preferred width moves the shrinking onto the fields and the spacer, which
     * can afford it.
     */
    private static void keepLabels(HBox bar) {
        for (javafx.scene.Node node : bar.getChildren()) {
            if (node instanceof Button button) {
                button.setMinWidth(Region.USE_PREF_SIZE);
            } else if (node instanceof Label label && !label.getStyleClass().contains("muted")) {
                label.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
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
        editAccountButton.setText(I18n.t("action.editAccount"));
        playButton.setText(I18n.t(playing ? "action.stop" : "action.play"));

        newGroupButton.setText(I18n.t("groups.new"));
        sortButton.setText(I18n.t("profiles.sort"));
        gridNewButton.setText(I18n.t("profiles.new"));
        gridNewGroupButton.setText(I18n.t("groups.new"));
        gridSortButton.setText(I18n.t("profiles.sort"));
        // No text on these: they are shapes, and the word lives in the tooltip -
        // which still has to follow a language change. Each remembers its own
        // key, so this loop does not have to know what any of them is.
        for (javafx.scene.control.Button button : new javafx.scene.control.Button[]{
                settingsButton, gridSettingsButton, aboutButton, gridAboutButton}) {
            Object key = button.getProperties().get("hexadron.name");
            if (key == null) {
                continue;
            }
            String name = I18n.t(key.toString());
            if (button.getTooltip() != null) {
                button.getTooltip().setText(name);
            }
            button.setAccessibleText(name);
        }
        gridTitle.setText(I18n.t("ui.mode.grid"));
        gridHint.setText(I18n.t("inventory.hint"));
        gridSearchField.setPromptText(I18n.t("search.prompt"));
        applyModeTexts();

        accountTitle.setText(I18n.t("label.account"));
        logPane.setText(I18n.t("log.title"));

        if (!busy) {
            stageLabel.setText(I18n.t("status.ready"));
        }
    }

    /**
     * The two switch buttons.
     *
     * <p>Each says where it goes, not where it is. "Inventory" on a button in
     * the list is ambiguous - it could as easily be a label for the view you are
     * already in - and a button that has to be tried to find out what it does is
     * a button that gets tried once and then avoided.
     */
    /**
     * Turns a settings button into a cog.
     *
     * <p>The word was the wrong shape for the place it sits: a bar of two
     * buttons at the far right, next to a search field, where every other
     * launcher and every browser puts a cog. A word there is wider than it
     * needs to be, it grows in translation - German and Ukrainian both run
     * longer - and it competes with the one control on the bar that has
     * something to say.
     *
     * <p>The name is not lost, only moved: it is the tooltip, and it is what a
     * screen reader is given. An icon nobody can name is worse than a word
     * nobody looks at.
     */
    private static void asIcon(javafx.scene.control.Button button,
                               javafx.scene.Node glyph, String key) {
        button.setText(null);
        button.setGraphic(glyph);
        button.getStyleClass().add("icon-button");
        button.getProperties().put("hexadron.name", key);
        String name = I18n.t(key);
        button.setTooltip(new javafx.scene.control.Tooltip(name));
        button.setAccessibleText(name);
    }

    private void applyModeTexts() {
        modeButton.setText(I18n.t("ui.mode.toGrid"));
        gridModeButton.setText(I18n.t("ui.mode.toList"));
    }

    // ---------------------------------------------------------------- actions

    private void applyFilter(String query) {
        filter = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        rebuildViews();
    }

    /** Redraws both interfaces from the shared arrangement. Tens of nodes, no network. */
    private void rebuildViews() {
        listView.rebuild();
        inventoryView.rebuild();
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
                service.settings().showAllVersions(),
                service.dirs());
    }

    @Override
    public void createProfile() {
        ProfileDialog dialog = newDialog();
        dialog.show(stage, null).ifPresent(profile -> {
            try {
                service.profiles().add(profile);
                service.profiles().save();
                rememberVersionPreference(dialog);
                refreshProfiles();
                select(profile);
            } catch (IOException e) {
                showError(I18n.t("profiles.create.failed"), e);
            }
        });
    }

    private void editSelectedProfile() {
        Profile profile = selectedProfile;
        if (profile == null) {
            return;
        }
        // Read before the dialog, because the dialog edits the profile in place
        // and there is nothing to compare against afterwards.
        String wasVersion = profile.minecraftVersion();
        LoaderType wasLoader = profile.loader();

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
            select(edited);
            reportModsLeftBehind(edited, wasVersion, wasLoader);
        });
    }

    private void rememberVersionPreference(ProfileDialog dialog) {
        if (dialog.showsAllVersions() != service.settings().showAllVersions()) {
            service.settings().showAllVersions(dialog.showsAllVersions());
            saveSettingsQuietly();
        }
    }

    private void removeSelectedProfile() {
        Profile profile = selectedProfile;
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
        confirm.setTitle(I18n.t("profiles.remove.header"));
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
        if (!confirmWrongVersionMods()) {
            return;
        }
        runInBackground(I18n.t("task.play"), () -> {
            Profile profile = requireSelected();
            // Where the game writes its own log. That file answered the last
            // three questions about this launcher, and nothing pointed at it.
            progress.log(I18n.t("log.gameLog",
                    service.profiles().gameDirectory(profile).resolve("logs")));
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
        confirm.setTitle(I18n.t("account.remove.header"));
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
     * Says so when changing the version has stranded the mods already installed.
     *
     * <p>This is where the mistake is actually made, and it is a reasonable
     * thing to do: an instance is set up, mods are installed, and then the
     * Minecraft version is changed - to try an older modpack, or because the
     * version was wrong to begin with. Nothing moves the jars, because they are
     * in the player's own folder and the launcher does not delete what it was
     * not asked to. So the folder quietly stops matching the instance, and until
     * this existed the first anyone heard of it was the loader refusing to start
     * and printing a page about it.
     *
     * <p>It reports rather than offers to fix. Which mods should go is not
     * obvious - some of them the player will want to replace with builds for the
     * new version, and a launcher that deleted them would have thrown away the
     * list of what to replace.
     */
    private void reportModsLeftBehind(Profile profile, String wasVersion, LoaderType wasLoader) {
        if (profile.minecraftVersion().equals(wasVersion) && profile.loader() == wasLoader) {
            return;
        }
        java.util.List<ModEntry> wrong;
        try {
            wrong = service.wrongVersionMods(profile);
        } catch (RuntimeException e) {
            return;
        }
        if (wrong.isEmpty()) {
            return;
        }
        showWarning(I18n.t("mods.leftBehind.header"),
                I18n.t("mods.leftBehind.body", wrong.size(), wasVersion,
                        profile.minecraftVersion()));
    }

    /**
     * Stops a launch that is already known to fail, and says why.
     *
     * <p>The one case this exists for: a profile's Minecraft version is changed
     * after its mods were installed. Nothing removes the old jars - they are in
     * the player's own folder and the launcher does not delete what it was not
     * asked to - so the folder now holds mods built for a version this profile
     * is no longer on. The loader refuses to start and prints forty lines about
     * it, which is the first the player hears of it.
     *
     * <p>Every one of those forty lines was knowable beforehand, out of files
     * already on disk: each jar names the Minecraft versions it needs, because
     * the loader has to read that to load it. So the launcher reads the same
     * thing and asks first.
     *
     * <p>It asks rather than refuses. A version range can be wrong in the mod's
     * own metadata, and a player who knows their pack works is not to be argued
     * with by a launcher.
     *
     * @return true when the launch should go ahead
     */
    private boolean confirmWrongVersionMods() {
        Profile profile = selectedProfile;
        if (profile == null) {
            return true;
        }
        java.util.List<ModEntry> wrong;
        try {
            wrong = service.wrongVersionMods(profile);
        } catch (RuntimeException e) {
            // A folder that cannot be read is not a reason to block a launch.
            return true;
        }
        if (wrong.isEmpty()) {
            return true;
        }

        StringBuilder detail = new StringBuilder();
        int listed = Math.min(wrong.size(), WRONG_VERSION_LISTED);
        for (int i = 0; i < listed; i++) {
            ModEntry mod = wrong.get(i);
            detail.append("\n  · ").append(mod.title());
            if (mod.requires() != null) {
                detail.append(I18n.t("mods.wrongVersion.needs", mod.requires()));
            }
        }
        if (wrong.size() > listed) {
            detail.append("\n  ").append(I18n.t("mods.wrongVersion.more", wrong.size() - listed));
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.t("mods.wrongVersion.body", wrong.size(),
                        profile.minecraftVersion(), detail.toString()));
        alert.initOwner(stage);
        Theme.apply(alert.getDialogPane());
        alert.setTitle(I18n.t("mods.wrongVersion.header"));
        alert.setHeaderText(I18n.t("mods.wrongVersion.header"));
        alert.getDialogPane().setPrefWidth(620);
        // The default is to stop. The player pressed Play expecting a game, and
        // the likely answer to "some of your mods are for another version" is
        // "then do not start".
        javafx.scene.control.ButtonType launch =
                new javafx.scene.control.ButtonType(I18n.t("mods.wrongVersion.launch"),
                        javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancel =
                new javafx.scene.control.ButtonType(I18n.t("action.cancel"),
                        javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, launch);
        return alert.showAndWait().filter(launch::equals).isPresent();
    }

    /** How many offending mods the warning names before it starts counting. */
    private static final int WRONG_VERSION_LISTED = 8;

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
        Profile profile = selectedProfile;
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

    /**
     * Re-reads the profiles and redraws both interfaces.
     *
     * <p>In arranged order, not by recency. Recency is the right default for a
     * launcher that arranges nothing, and exactly wrong once the user has put
     * the list in an order by hand: playing one instance would move it, and the
     * list they arranged would rearrange itself underneath them.
     */
    private void refreshProfiles() {
        String previousId = selectedProfile == null ? null : selectedProfile.id();
        profiles.clear();
        profiles.addAll(service.profiles().arranged());

        selectedProfile = null;
        if (previousId != null) {
            for (Profile profile : profiles) {
                if (profile.id().equals(previousId)) {
                    selectedProfile = profile;
                    break;
                }
            }
        }
        if (selectedProfile == null) {
            selectedProfile = service.profiles().selected()
                    .filter(profiles::contains)
                    .orElse(profiles.isEmpty() ? null : profiles.get(0));
        }
        if (selectedProfile != null) {
            service.profiles().select(selectedProfile);
        }
        rebuildViews();
        showProfile(selectedProfile);
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

        updateDetailIcon(profile);

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

    /**
     * Puts the selected profile's picture beside its name.
     *
     * <p>Hidden rather than replaced by a placeholder when nothing is selected:
     * the panel then reads "no instance selected", and a mark next to that
     * sentence would be a mark for an instance that does not exist.
     */
    private void updateDetailIcon(Profile profile) {
        if (profile == null) {
            detailIcon.getChildren().clear();
            detailIcon.setVisible(false);
            detailIcon.setManaged(false);
            return;
        }
        detailIcon.setVisible(true);
        detailIcon.setManaged(true);
        detailIcon.getChildren().setAll(
                ProfileIcons.node(profile, service.dirs(), DETAIL_ICON_SIZE));
    }

    private Profile requireSelected() throws IOException {
        Profile profile = selectedProfile;
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
        boolean hasProfile = selectedProfile != null;
        playButton.setDisable(value || !hasProfile);
        installButton.setDisable(value || !hasProfile);
        // The browser runs its own downloads in its own window, so it stays
        // reachable while the launcher is busy with something else.
        modsButton.setDisable(!hasProfile);
    }

    /**
     * Records how long each stage of start-up took.
     *
     * <p>In the log pane rather than on screen: nobody wants a timing report in
     * their way every time they open the launcher, and the one time the numbers
     * matter - somebody saying it takes too long to start - the log is what they
     * are asked to send.
     */
    public void logStartup(String summary) {
        progress.log(I18n.t("log.startup", summary));
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
        com.hexadron.launcher.core.LauncherLog.warn("%s: %s", header, message);
        alert(Alert.AlertType.WARNING, header, message, 520);
    }

    private void showError(String header, Throwable error) {
        // Written down before it is shown. The dialog carries one sentence; the
        // file carries the cause chain, which is the half that says where.
        com.hexadron.launcher.core.LauncherLog.error(header, error);
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

    // ------------------------------------------------------------ ProfileHost
    //
    // What the two views are allowed to ask for, and the only way they change
    // anything. Every method here either reads the shared state above or writes
    // it and calls rebuildViews(), which is why the list and the grid cannot
    // disagree: there is one arrangement, one selection and one search, and both
    // views are a drawing of them.

    @Override
    public LauncherService service() {
        return service;
    }

    @Override
    public ProfileLayout layout() {
        return service.profiles().layout();
    }

    @Override
    public List<Profile> profiles() {
        return List.copyOf(profiles);
    }

    @Override
    public String filter() {
        return filter;
    }

    @Override
    public boolean matchesFilter(Profile profile) {
        if (filter.isEmpty()) {
            return true;
        }
        if (profile == null) {
            return false;
        }
        return profile.name().toLowerCase(Locale.ROOT).contains(filter)
                || profile.minecraftVersion().toLowerCase(Locale.ROOT).contains(filter)
                || profile.loader().displayName().toLowerCase(Locale.ROOT).contains(filter);
    }

    @Override
    public Profile selected() {
        return selectedProfile;
    }

    /**
     * Selects a profile in both views and in the summary panel.
     *
     * <p>Moves a style class rather than rebuilding, and that is not an
     * optimisation. Selection happens on mouse-pressed, which is the same press
     * a drag begins with - rebuilding here would replace the node the drag was
     * about to start from, and dragging would silently stop working.
     */
    @Override
    public void select(Profile profile) {
        if (profile == null) {
            return;
        }
        selectedProfile = profile;
        service.profiles().select(profile);
        listView.applySelection();
        inventoryView.applySelection();
        showProfile(profile);
    }

    @Override
    public void play(Profile profile) {
        select(profile);
        play();
    }

    @Override
    public void edit(Profile profile) {
        select(profile);
        editSelectedProfile();
    }

    @Override
    public void remove(Profile profile) {
        select(profile);
        removeSelectedProfile();
    }

    @Override
    public void install(Profile profile) {
        select(profile);
        installSelected();
    }

    @Override
    public void openMods(Profile profile) {
        select(profile);
        openModBrowser();
    }

    @Override
    public void openFolder(Profile profile) {
        select(profile);
        openGameFolder();
    }

    /**
     * Puts a picture of the user's choosing on a profile.
     *
     * <p>The file is copied into the launcher folder by {@link ProfileIcons},
     * so the icon survives the original being moved or deleted, and nothing in
     * profiles.json is ever a path the launcher opens.
     */
    @Override
    public void chooseIcon(Profile profile) {
        if (profile == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("icon.title"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("icon.filter"), ProfileIcons.chooserPatterns()));
        java.io.File chosen = chooser.showOpenDialog(stage);
        if (chosen == null) {
            return;
        }
        try {
            profile.customIcon(ProfileIcons.store(chosen.toPath(), service.dirs()));
            saveProfilesQuietly();
            rebuildViews();
            updateDetailIcon(shown);
            progress.log(I18n.t("icon.set", profile.name()));
        } catch (IOException e) {
            showError(I18n.t("icon.failed"), e);
        }
    }

    @Override
    public void clearIcon(Profile profile) {
        if (profile == null || !profile.hasCustomIcon()) {
            return;
        }
        // The file is left in the icons folder on purpose: another profile may
        // be using it - the store names files by content, so identical pictures
        // are one file - and an icon is a few kilobytes.
        profile.customIcon(null);
        saveProfilesQuietly();
        rebuildViews();
        updateDetailIcon(shown);
    }

    /**
     * A group dialog that remembers the colours mixed in it.
     *
     * <p>Handed the settings and a way to write them, because a mixed colour is
     * launcher-wide rather than part of the group it was mixed for - and it is
     * saved the moment it is mixed, so cancelling the dialog does not throw the
     * work away.
     */
    private GroupDialog groupDialog() {
        return new GroupDialog(service.settings(), this::saveSettingsQuietly);
    }

    @Override
    public void openSettings() {
        openSettingsWindow();
    }

    @Override
    public void layoutChanged() {
        saveProfilesQuietly();
        rebuildViews();
    }

    /**
     * Makes a group, asking for its name and its colour first.
     *
     * <p>The colour is asked for at the same moment as the name because in the
     * grid it is the only thing that says which band is which, so it is as much
     * a part of what the group is. The default offered is a palette colour no
     * other group is using.
     */
    /**
     * Makes a group, asking for its name and its colour first.
     *
     * <p>The colour is asked for at the same moment as the name because in the
     * grid it is the only thing that says which band is which, so it is as much
     * a part of what the group is. The colour offered is the one the layout would
     * have picked anyway - the first in the palette no other group is using - so
     * the dialog shows what would happen and lets it be changed.
     *
     * <p>Nothing is created until Save. Making the group first so the dialog
     * could read its colour would mean a cancel had to unmake it, and a cancel
     * that has to undo something is a cancel that can leave something behind.
     */
    @Override
    public void createGroup(Profile profile) {
        groupDialog().show(stage, I18n.t("groups.new.default"),
                layout().nextPaletteColor()).ifPresent(choice -> {
            ProfileLayout.Group made = layout().createGroup(choice.name());
            made.color(choice.color());
            if (profile != null) {
                // Membership is the row, so this moves the profile into one of
                // the group's cells.
                layout().join(profile.id(), made.id());
            }
            layoutChanged();
        });
    }

    /**
     * Makes a group that takes the row the user pointed at.
     *
     * <p>Two questions, in the order they matter: what the group is called, and -
     * only when that row already has instances in it - whether the group takes
     * them or they move out of its way. Guessing either one is worse than asking:
     * a group that swallowed three unrelated instances and a group that scattered
     * them are both wrong half the time.
     */
    @Override
    public void createGroupInRow(int row) {
        ProfileLayout layout = layout();
        if (layout.rowGroup(row).isPresent()) {
            hint(I18n.t("grid.rowInGroup"));
            return;
        }

        // The colour offered is the one the layout would pick anyway, so the
        // dialog shows what would happen and lets it be changed.
        String suggested = layout.nextPaletteColor();
        groupDialog().show(stage, I18n.t("groups.new.default"), suggested).ifPresent(choice -> {
            boolean keepOccupants = true;
            int occupants = layout.occupantsInRow(row);
            // Asked before the group exists, so a cancel leaves nothing behind.
            if (occupants > 0) {
                ButtonType take = new ButtonType(
                        I18n.t("grid.newGroupHere.take"), ButtonBar.ButtonData.OTHER);
                ButtonType moveOut = new ButtonType(
                        I18n.t("grid.newGroupHere.move"), ButtonBar.ButtonData.OTHER);
                ButtonType cancel = new ButtonType(
                        I18n.t("action.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
                Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                        I18n.t("grid.newGroupHere.occupants.body", occupants),
                        take, moveOut, cancel);
                ask.initOwner(stage);
                Theme.apply(ask.getDialogPane());
                ask.setHeaderText(I18n.t("grid.newGroupHere.occupants.header"));
                ask.setTitle(I18n.t("grid.newGroupHere.occupants.header"));
                ask.getDialogPane().setPrefWidth(620);
                var answer = ask.showAndWait();
                if (answer.isEmpty() || answer.get() == cancel) {
                    return;
                }
                keepOccupants = answer.get() == take;
            }
            ProfileLayout.Group made = layout.claimRow(row, choice.name(), keepOccupants);
            if (made == null) {
                hint(I18n.t("grid.newGroupHere.failed"));
                return;
            }
            made.color(choice.color());
            layoutChanged();
        });
    }

    @Override
    public void editGroup(ProfileLayout.Group group) {
        if (group == null) {
            return;
        }
        groupDialog().show(stage, group.name(), group.color()).ifPresent(choice -> {
            layout().renameGroup(group.id(), choice.name());
            group.color(choice.color());
            layoutChanged();
        });
    }

    /**
     * Deletes a group and keeps the instances that were in it.
     *
     * <p>The confirmation says so, because "delete group" over a set of
     * instances reads as though it deletes them. It does not, and nothing here
     * touches a game folder - {@link #removeSelectedProfile()} is the only place
     * that can.
     */
    @Override
    public void removeGroup(ProfileLayout.Group group) {
        if (group == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.t("groups.remove.body", group.name(),
                        layout().membersOf(group.id()).size()));
        confirm.initOwner(stage);
        Theme.apply(confirm.getDialogPane());
        confirm.setHeaderText(I18n.t("groups.remove.header"));
        confirm.setTitle(I18n.t("groups.remove.header"));
        confirm.getDialogPane().setPrefWidth(560);
        if (confirm.showAndWait()
                .filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            return;
        }
        layout().removeGroup(group.id());
        layoutChanged();
    }

    /** Called when the window closes: drop the tray icon and stop the game if wanted. */
    public void shutdown() {
        tray.dispose();
        if (session != null && session.isRunning() && !service.settings().keepOpenWhilePlaying()) {
            session.terminate();
        }
    }
}
