package com.hexadron.launcher.ui;

import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.mods.ModEntry;
import com.hexadron.launcher.mods.ModInstaller;
import com.hexadron.launcher.mods.ModLibrary;
import com.hexadron.launcher.mods.ModOrigin;
import com.hexadron.launcher.mods.ModPack;
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.mods.ModSort;
import com.hexadron.launcher.profile.Profile;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;
import java.util.Locale;

/**
 * The mod browser: one window per profile, opened from the main window.
 *
 * <p>It is a separate stage rather than a panel or a modal dialog on purpose.
 * Choosing mods is a long, exploratory task - search, read, install, look at
 * what is already there, search again - and it happens against one fixed pair of
 * Minecraft version and loader. A separate window can be left open beside the
 * launcher and closed whenever; a modal one would block the launcher for as long
 * as the user was browsing.
 *
 * <p>Everything shown is already filtered by the profile's version and loader,
 * so a result in the list is a build that will actually load. That is the whole
 * value of browsing from inside a launcher rather than on a website.
 */
public final class ModBrowserWindow {

    /** Results asked of each platform per search. Enough to scroll, small enough to stay quick. */
    private static final int PAGE_SIZE = 40;

    private final LauncherService service;
    private final Stage owner;
    private final Profile profile;
    private final Runnable onChanged;

    private final Stage stage = new Stage();

    private final Label titleLabel = new Label();
    private final Label subtitleLabel = new Label();
    private final Button packButton = new Button();
    private final Label packNote = new Label();

    private final TextField searchField = new TextField();
    private final ComboBox<ModSort> sortBox = new ComboBox<>();
    private final ComboBox<SourceChoice> sourceBox = new ComboBox<>();
    private final Button searchButton = new Button();

    /**
     * Says out loud when CurseForge is not being searched.
     *
     * <p>Without it the browser quietly returns Modrinth results only, and a user
     * looking for a mod that is on CurseForge alone concludes it does not exist
     * for their version. A launcher searching one platform has to say it is
     * searching one platform - and then offer the one action that fixes it.
     */
    private final Label curseForgeNote = new Label();
    private final Button curseForgeKeyButton = new Button();
    private final HBox curseForgeRow = new HBox(8, curseForgeNote, curseForgeKeyButton);

    private final javafx.collections.ObservableList<ModProvider.SearchResult> results =
            FXCollections.observableArrayList();
    private final ListView<ModProvider.SearchResult> resultList = new ListView<>(results);
    private final Label browseEmpty = new Label();
    private final Button moreButton = new Button();

    private final ListView<ModEntry> installedList = new ListView<>();
    private final Label installedEmpty = new Label();

    /**
     * Asks Modrinth what the jars the launcher did not install actually are.
     *
     * <p>A button rather than something the window does when it opens. The
     * question is asked by sending a digest of every unrecognised file in the
     * player's mods folder to a third party, and a launcher does not do that
     * because a window was opened.
     */
    private final Button identifyButton = new Button();
    private final Label identifyNote = new Label();

    private final Tab browseTab = new Tab();
    private final Tab installedTab = new Tab();
    private final TabPane tabs = new TabPane(browseTab, installedTab);

    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final BrowserProgress progress;

    /** Where the next page starts, and how many matches the platforms report. */
    private int nextOffset;
    private int totalMatches = -1;

    private ModLibrary library;
    private ModPack pack;
    private boolean packAvailable;
    private volatile boolean busy;

    public ModBrowserWindow(LauncherService service, Stage owner, Profile profile, Runnable onChanged) {
        this.service = service;
        this.owner = owner;
        this.profile = profile;
        this.onChanged = onChanged;
        this.progress = new BrowserProgress(statusLabel, progressBar);
    }

    /**
     * True once the stage has been configured and given a scene.
     *
     * <p>{@code initOwner} and {@code initModality} may only be called before a
     * stage is shown for the first time, and calling them again afterwards
     * throws {@code IllegalStateException: Cannot set owner once stage has been
     * set visible}. A closed stage still counts as having been shown, so
     * {@code isShowing()} is not the right question to ask - this flag is.
     * Closing the window and pressing Mods again used to take exactly that path.
     */
    private boolean built;

    /** Opens the window, or focuses it if it is already up. */
    public void show() {
        if (!built) {
            buildStage();
            built = true;
        }
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
        // Re-read on every opening rather than only on the first. The folder can
        // have changed while the window was closed, and the launcher's language
        // can have changed with it.
        applyTexts();
        refreshInstalled();
        loadPackStateAsync();
        nextOffset = 0;
        totalMatches = -1;
        runSearch();

        stage.show();
        stage.toFront();
    }

    /** One-time stage setup. Everything here is illegal after the first show. */
    private void buildStage() {
        stage.initOwner(owner);
        // Not modal: the launcher stays usable while mods are being chosen.
        stage.initModality(Modality.NONE);
        stage.getIcons().addAll(owner.getIcons());

        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildTabs());
        root.setBottom(buildFooter());

        Scene scene = new Scene(root, 980, 700);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(520);
    }

    /**
     * Writes the strings that are not rebuilt with the list cells.
     *
     * <p>Also re-reads the profile: it is the same mutable object the instance
     * dialog edits, so a version or loader changed while this window was closed
     * is already visible here and must be shown, not remembered.
     */
    private void applyTexts() {
        stage.setTitle(I18n.t("mods.title", profile.name()));
        titleLabel.setText(profile.name());
        subtitleLabel.setText(profile.minecraftVersion() + "  ·  " + profile.loader().displayName());
        searchField.setPromptText(I18n.t("mods.search.prompt"));
        searchButton.setText(I18n.t("mods.search"));
        browseTab.setText(I18n.t("mods.tab.browse"));
        installedEmpty.setText(I18n.t("mods.installed.empty"));
        refreshCurseForgeState();
    }

    /** Closes the window if it is open. */
    public void close() {
        if (stage.isShowing()) {
            stage.close();
        }
    }

    // ---------------------------------------------------------------- header

    private VBox buildHeader() {
        titleLabel.getStyleClass().add("detail-title");
        titleLabel.setText(profile.name());

        subtitleLabel.getStyleClass().add("detail-subtitle");
        subtitleLabel.setText(profile.minecraftVersion() + "  ·  " + profile.loader().displayName());

        // Hidden, not merely disabled, until the check says the set exists for
        // this version: an always-failing button is worse than no button.
        packButton.getStyleClass().add("primary");
        packButton.setVisible(false);
        packButton.setManaged(false);
        packButton.setOnAction(event -> togglePack());

        packNote.getStyleClass().add("muted");
        packNote.setVisible(false);
        packNote.setManaged(false);

        VBox text = new VBox(2, titleLabel, subtitleLabel);
        VBox packBox = new VBox(4, packButton, packNote);
        packBox.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(12, text, spacer(), packBox);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        return new VBox(header);
    }

    // ---------------------------------------------------------------- tabs

    private TabPane buildTabs() {
        browseTab.setClosable(false);
        installedTab.setClosable(false);
        browseTab.setContent(buildBrowsePane());
        installedTab.setContent(buildInstalledPane());
        tabs.getStyleClass().add("detail");
        return tabs;
    }

    private VBox buildBrowsePane() {
        searchField.setPromptText(I18n.t("mods.search.prompt"));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setOnAction(event -> runSearch());

        sortBox.setItems(FXCollections.observableArrayList(ModSort.values()));
        sortBox.setValue(ModSort.POPULAR);
        sortBox.setPrefWidth(190);
        sortBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ModSort value) {
                return value == null ? "" : I18n.t(value.key());
            }

            @Override
            public ModSort fromString(String text) {
                return null;
            }
        });
        sortBox.valueProperty().addListener((observable, previous, value) -> runSearch());

        sourceBox.setItems(FXCollections.observableArrayList(SourceChoice.values()));
        sourceBox.setValue(SourceChoice.ALL);
        sourceBox.setPrefWidth(150);
        sourceBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(SourceChoice value) {
                return value == null ? "" : value.label();
            }

            @Override
            public SourceChoice fromString(String text) {
                return null;
            }
        });
        sourceBox.valueProperty().addListener((observable, previous, value) -> runSearch());

        searchButton.setOnAction(event -> runSearch());

        HBox controls = new HBox(8, searchField, sortBox, sourceBox, searchButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        curseForgeNote.getStyleClass().add("muted");
        curseForgeNote.setWrapText(true);
        HBox.setHgrow(curseForgeNote, Priority.ALWAYS);
        curseForgeKeyButton.setOnAction(event -> promptForCurseForgeKey());
        curseForgeRow.setAlignment(Pos.CENTER_LEFT);
        refreshCurseForgeState();

        resultList.setCellFactory(view -> new ResultCell());
        resultList.setPlaceholder(browseEmpty);
        VBox.setVgrow(resultList, Priority.ALWAYS);

        // Paging rather than a bigger page. A single huge request is slower to
        // first result and still has a ceiling; this one has none the user meets.
        moreButton.setMaxWidth(Double.MAX_VALUE);
        moreButton.setVisible(false);
        moreButton.setManaged(false);
        moreButton.setOnAction(event -> loadPage(false));

        VBox pane = new VBox(10, controls, curseForgeRow, resultList, moreButton);
        pane.getStyleClass().add("browse-pane");
        return pane;
    }

    /** Shows or hides the CurseForge notice, depending on whether it has a key. */
    private void refreshCurseForgeState() {
        boolean available = service.curseForge().isAvailable();
        curseForgeNote.setText(available ? "" : I18n.t("mods.curseforge.disabled"));
        curseForgeKeyButton.setText(I18n.t("mods.curseforge.setKey"));
        curseForgeRow.setVisible(!available);
        curseForgeRow.setManaged(!available);
    }

    /**
     * Asks for a CurseForge key and puts it to use.
     *
     * <p>Plain text rather than a masked field on purpose: this is not a
     * password, it identifies an application rather than a person, and a key
     * pasted into a field nobody can read is a key nobody can check for a
     * trailing space.
     */
    private void promptForCurseForgeKey() {
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog(service.settings().curseForgeApiKey());
        dialog.initOwner(stage);
        Theme.apply(dialog.getDialogPane());
        dialog.setTitle(I18n.t("mods.curseforge.key.header"));
        dialog.setHeaderText(I18n.t("mods.curseforge.key.header"));
        dialog.setContentText(I18n.t("mods.curseforge.key.body"));
        dialog.getDialogPane().setPrefWidth(620);
        dialog.getEditor().setPrefColumnCount(48);

        dialog.showAndWait().ifPresent(value -> {
            try {
                service.curseForgeApiKey(value);
            } catch (java.io.IOException e) {
                warn(I18n.t("mods.curseforge.key.header"),
                        e.getMessage() == null ? e.toString() : e.getMessage());
                return;
            }
            refreshCurseForgeState();
            if (service.curseForge().isAvailable()) {
                progress.done(I18n.t("mods.curseforge.key.saved"));
                runSearch();
            }
        });
    }

    private VBox buildInstalledPane() {
        installedList.setCellFactory(view -> new InstalledCell());
        installedList.setPlaceholder(installedEmpty);
        VBox.setVgrow(installedList, Priority.ALWAYS);

        identifyNote.getStyleClass().add("muted");
        identifyNote.setWrapText(true);
        HBox.setHgrow(identifyNote, Priority.ALWAYS);
        identifyButton.setOnAction(event -> identifyExternal());
        HBox identifyRow = new HBox(8, identifyNote, identifyButton);
        identifyRow.setAlignment(Pos.CENTER_LEFT);
        identifyRow.setVisible(false);
        identifyRow.setManaged(false);
        this.identifyRow = identifyRow;

        VBox pane = new VBox(10, identifyRow, installedList);
        pane.getStyleClass().add("browse-pane");
        return pane;
    }

    /** Holds the identify button, so it can be hidden when there is nothing to ask about. */
    private HBox identifyRow;

    private VBox buildFooter() {
        progressBar.setMaxWidth(Double.MAX_VALUE);
        statusLabel.getStyleClass().add("muted");
        VBox footer = new VBox(6, statusLabel, progressBar);
        footer.getStyleClass().add("footer");
        return footer;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    // ---------------------------------------------------------------- cells

    /**
     * The parts every mod row is built from, and the sizing rules that keep it
     * in one piece.
     *
     * <h2>Why this is shared</h2>
     *
     * <p>A search hit and an installed mod are the same object to the person
     * reading the list - a mod, with a logo, a name, a line about it and
     * something to press - so they are laid out by the same code. When they were
     * two hand-built rows they drifted: only one of them had the link to the
     * mod's page, and only one of them survived a long name.
     *
     * <h2>The sizing rules, which are the whole point</h2>
     *
     * <p>A row is a horizontal box in a list cell, and a list cell clips. So
     * every part of it has to say explicitly whether it may grow, whether it may
     * shrink, and what it does when the text is longer than the space:
     *
     * <ul>
     *   <li>the text column is the only part that grows, and it is allowed to
     *       shrink to nothing ({@code setMinWidth(0)}). Without that, a long
     *       description sets a minimum width for the whole row, the buttons are
     *       pushed past the right edge of the cell, and they are simply not
     *       there any more - which is exactly what a mod with a long name or a
     *       long summary did;</li>
     *   <li>every line of that column is a single line that ends in an ellipsis
     *       rather than wrapping. A wrapped label's height depends on its width,
     *       which in a cell that is itself being measured is a layout that
     *       argues with itself;</li>
     *   <li>the badge and the buttons never shrink
     *       ({@code USE_PREF_SIZE} as a minimum). They are the part of the row
     *       that has to be reachable, so they are the part that keeps its size
     *       and the text gives way instead;</li>
     *   <li>the cell asks for no width of its own, so the list never grows a
     *       horizontal scroll bar to fit its longest row.</li>
     * </ul>
     */
    private abstract class ModRow<T> extends ListCell<T> {

        protected final ModIcons.Tile icon = new ModIcons.Tile(40);
        protected final Label name = new Label();
        protected final Label meta = new Label();
        protected final Label description = new Label();

        /**
         * The link to the mod's page.
         *
         * <p>Under the text and set small, rather than out beside the buttons.
         * It opens something outside the launcher, so it is the one thing in the
         * row that must not be hit by accident on the way to Remove - and a
         * quiet line of small text under a description is read as a link and
         * not as a target.
         */
        protected final Hyperlink page = new Hyperlink();

        private final VBox text = new VBox(1, name, meta, description, page);
        private final HBox row;

        /** Everything to the right of the text: set by the subclass, never shrunk. */
        protected final HBox actions = new HBox(6);

        ModRow() {
            name.getStyleClass().add("instance-name");
            meta.getStyleClass().add("instance-subtitle");
            description.getStyleClass().add("instance-subtitle");
            page.getStyleClass().add("mod-link");

            // Filling the column is what makes the ellipsis appear: a label only
            // shortens its text when something has told it how wide it is.
            for (Label label : new Label[]{name, meta, description}) {
                label.setWrapText(false);
                label.setMaxWidth(Double.MAX_VALUE);
                label.setMinWidth(0);
            }
            // The link is the exception, and deliberately so. Stretched to the
            // column it would be a full-width click target sitting directly
            // above Remove; left at its own width it is only clickable where the
            // words are.
            page.setMaxWidth(Region.USE_PREF_SIZE);
            page.setAlignment(Pos.CENTER_LEFT);

            text.setFillWidth(true);
            text.setMinWidth(0);
            text.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(text, Priority.ALWAYS);

            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.setMinWidth(Region.USE_PREF_SIZE);

            row = new HBox(12, icon, text, actions);
            row.setAlignment(Pos.CENTER_LEFT);

            // No preferred width of its own: the cell is as wide as the list,
            // and anything longer is the text column's problem to ellipsise.
            setPrefWidth(0);
        }

        /** Shows or hides a line, so an absent one takes no height. */
        protected static void line(Label label, String value) {
            boolean present = value != null && !value.isBlank();
            label.setText(present ? value : "");
            label.setVisible(present);
            label.setManaged(present);
        }

        /** Points the link at a page, or takes it out of the row entirely. */
        protected void link(String url, Runnable action) {
            boolean present = SystemBrowser.isWebPage(url);
            page.setText(present ? I18n.t("mods.details") : "");
            page.setVisible(present);
            page.setManaged(present);
            page.setOnAction(event -> action.run());
        }

        /** Puts the assembled row on screen. Called at the end of every update. */
        protected void showRow() {
            setGraphic(row);
        }

        protected void clearRow() {
            setGraphic(null);
            setText(null);
        }
    }

    /** A search hit: name, author and downloads, description, page, and one action. */
    private final class ResultCell extends ModRow<ModProvider.SearchResult> {

        private final Button action = new Button();

        ResultCell() {
            actions.getChildren().add(action);
        }

        @Override
        protected void updateItem(ModProvider.SearchResult hit, boolean empty) {
            super.updateItem(hit, empty);
            if (empty || hit == null) {
                clearRow();
                return;
            }
            icon.show(hit.iconUrl(), hit.title());
            line(name, hit.title());
            line(meta, hit.source().displayName()
                    + (hit.author() == null || hit.author().isBlank() ? "" : "  ·  " + hit.author())
                    + "  ·  " + I18n.t("mods.downloads", formatCount(hit.downloads())));
            line(description, hit.description());
            // The catalogue needs the link at least as much as the installed
            // list does: this is where the user is deciding whether they want
            // the mod at all, and that decision is made on the mod's own page.
            link(hit.pageUrl(), () -> openPage(hit.title(), hit.pageUrl()));

            boolean installed = library != null && library.contains(hit.source(), hit.projectId());
            action.setText(I18n.t(installed ? "mods.installed" : "mods.install"));
            action.setDisable(installed || busy);
            action.getStyleClass().removeAll("primary");
            if (!installed) {
                action.getStyleClass().add("primary");
            }
            action.setOnAction(event -> installMod(hit));
            showRow();
        }
    }

    /**
     * One mod in the folder: what it is, where it came from, and what may be
     * done to it.
     *
     * <p>The row is the same shape whether the launcher downloaded the mod or
     * the player dropped it in, because to the person reading it they are the
     * same thing - a mod that is installed. What differs is the badge, and which
     * of the buttons are live.
     */
    private final class InstalledCell extends ModRow<ModEntry> {

        private final Label badge = new Label();
        private final Button toggle = new Button();
        private final Button remove = new Button();

        InstalledCell() {
            badge.getStyleClass().add("badge");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            actions.getChildren().addAll(badge, toggle, remove);
        }

        @Override
        protected void updateItem(ModEntry mod, boolean empty) {
            super.updateItem(mod, empty);
            if (empty || mod == null) {
                clearRow();
                return;
            }
            icon.show(mod);
            line(name, mod.version() == null ? mod.title() : mod.title() + "  " + mod.version());

            // The file name is on the line either way. For a mod the launcher
            // installed it answers "which of these jars is that"; for one the
            // player dropped in it is often all there is to go on, and it is
            // what they will look for in the folder.
            String author = mod.authorLine();
            line(meta, author == null ? mod.fileName() : author + "  ·  " + mod.fileName());
            line(description, mod.description());
            link(mod.pageUrl(), () -> openPage(mod.title(), mod.pageUrl()));

            badge.setText(ModLabels.badge(mod));
            badge.getStyleClass().removeAll("badge-pack", "badge-off");
            if (!mod.enabled()) {
                badge.getStyleClass().add("badge-off");
            } else if (mod.origin() == ModOrigin.PACK) {
                badge.getStyleClass().add("badge-pack");
            }

            toggle.setText(I18n.t(mod.enabled() ? "mods.disable" : "mods.enable"));
            toggle.setDisable(busy);
            toggle.setOnAction(event -> toggleMod(mod));

            remove.setText(I18n.t("mods.remove"));
            remove.getStyleClass().removeAll("danger");
            remove.getStyleClass().add("danger");
            // A pack goes out whole, through its own button in the header.
            remove.setDisable(!mod.isRemovable() || busy);
            remove.setTooltip(mod.isRemovable()
                    ? null
                    : new javafx.scene.control.Tooltip(I18n.t("mods.remove.packLocked")));
            remove.setOnAction(event -> removeMod(mod));
            showRow();
        }
    }


    // ---------------------------------------------------------------- actions

    /** Starts a new search from the first page. */
    private void runSearch() {
        loadPage(true);
    }

    /**
     * Fetches one page.
     *
     * <p>The page size is a request size, not a result cap. Before paging, the
     * browser asked for 40 and showed 40 whatever the version, which made a
     * Minecraft version with four thousand mods look exactly like one with
     * fifty. Now the platform's own total is shown, and the rest is one click
     * away.
     */
    private void loadPage(boolean fresh) {
        if (profile.loader() == LoaderType.VANILLA) {
            browseEmpty.setText(I18n.t("mods.vanilla"));
            results.clear();
            return;
        }
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        ModSort sort = sortBox.getValue() == null ? ModSort.POPULAR : sortBox.getValue();
        ModProvider.Source only = sourceBox.getValue() == null ? null : sourceBox.getValue().source();
        int offset = fresh ? 0 : nextOffset;

        if (fresh) {
            browseEmpty.setText(I18n.t("mods.searching"));
        }
        moreButton.setDisable(true);

        // A search never pops a dialog. It is the one action the user repeats
        // constantly, and a modal error for a dropped connection would be in the
        // way of the retry.
        run(I18n.t("mods.task.search"), false, () -> {
            ModProvider.SearchPage page =
                    service.searchMods(profile, query, sort, only, PAGE_SIZE, offset);
            Platform.runLater(() -> {
                if (fresh) {
                    results.setAll(page.results());
                } else {
                    results.addAll(page.results());
                }
                nextOffset = offset + PAGE_SIZE;
                totalMatches = page.total();
                browseEmpty.setText(I18n.t("mods.noResults"));

                boolean more = page.hasMore() && !page.results().isEmpty();
                moreButton.setVisible(more);
                moreButton.setManaged(more);
                moreButton.setDisable(false);
                moreButton.setText(I18n.t("mods.more"));

                String found = totalMatches >= 0
                        ? I18n.t("mods.foundOf", results.size(), totalMatches)
                        : I18n.t("mods.found", results.size());
                if (page.isPartial()) {
                    // A shorter list with no explanation reads as "that mod does
                    // not exist for this version". Say which platform is missing.
                    progress.failed(found + "  ·  "
                            + I18n.t("mods.searchPartial", String.join("; ", page.unavailable())));
                } else {
                    progress.done(found);
                }
            });
        });
    }

    private void installMod(ModProvider.SearchResult hit) {
        mutate(I18n.t("mods.task.install", hit.title()), () -> {
            ModInstaller.Result result = service.installMod(profile, hit.card(), progress);
            Platform.runLater(() -> {
                refreshInstalled();
                progress.done(I18n.t("mods.installedCount", result.installed().size()));
                if (!result.isClean()) {
                    warn(I18n.t("mods.attention.header"), String.join("\n",
                            java.util.stream.Stream.concat(result.skipped().stream(),
                                    result.manualDownloads().stream()).toList()));
                }
            });
        });
    }

    /**
     * Removes one mod.
     *
     * <p>Two different things behind one button, and the dialog says which. A
     * mod the launcher downloaded is deleted and can be installed again from the
     * record it keeps. A file the player put there is sent to the recycle bin,
     * because the launcher has no idea what it was or where it came from and
     * therefore no way to get it back.
     */
    private void removeMod(ModEntry mod) {
        String body = mod.isManaged()
                ? I18n.t("mods.remove.body", mod.title())
                : I18n.t("mods.remove.body.external", mod.title(), mod.fileName());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, body);
        confirm.initOwner(stage);
        Theme.apply(confirm.getDialogPane());
        confirm.setHeaderText(I18n.t("mods.remove.header"));
        confirm.getDialogPane().setPrefWidth(520);
        if (confirm.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isEmpty()) {
            return;
        }
        mutate(I18n.t("mods.task.remove", mod.title()), () -> {
            if (mod.isManaged()) {
                service.removeMod(profile, mod.key(), progress);
            } else {
                service.discardExternalMod(profile, mod, progress);
            }
            Platform.runLater(() -> {
                refreshInstalled();
                progress.done(I18n.t(mod.isManaged() ? "mods.removed" : "mods.discarded", mod.title()));
            });
        });
    }

    /**
     * Switches a mod on or off.
     *
     * <p>By renaming the file, which is what the loader looks at, so the answer
     * is the same one the game will give. Kept rather than removed: a mod
     * switched off to test a crash is meant to come back.
     */
    private void toggleMod(ModEntry mod) {
        boolean enable = !mod.enabled();
        mutate(I18n.t(enable ? "mods.task.enable" : "mods.task.disable", mod.title()), () -> {
            service.setModEnabled(profile, mod, enable);
            Platform.runLater(() -> {
                refreshInstalled();
                progress.done(I18n.t(enable ? "mods.enabled" : "mods.disabled", mod.title()));
            });
        });
    }

    /** Opens a mod's page in the user's own browser. */
    private void openPage(String title, String url) {
        if (SystemBrowser.open(url)) {
            progress.done(I18n.t("mods.details.opened", title));
            return;
        }
        // The address is shown rather than swallowed: on a session with no
        // desktop integration, copying it is the whole of the workaround.
        warn(I18n.t("mods.details"), I18n.t("mods.details.failed", url));
    }

    /**
     * Asks Modrinth to put names and logos to the jars the player added.
     *
     * <p>Nothing in the folder is changed by this and nothing becomes
     * launcher-managed: the answers are written to a separate index beside the
     * lock file, and a mod that is recognised is still the player's to keep or
     * remove.
     */
    private void identifyExternal() {
        mutate(I18n.t("mods.task.identify"), () -> {
            int recognised = service.identifyExternalMods(profile, progress);
            Platform.runLater(() -> {
                refreshInstalled();
                progress.done(I18n.t("mods.identified", recognised));
            });
        });
    }

    private void togglePack() {
        if (pack == null) {
            return;
        }
        boolean installed = library != null && library.isPackInstalled(pack.id());
        if (installed) {
            mutate(I18n.t("mods.task.packRemove"), () -> {
                int removed = service.removePack(profile, pack.id(), progress);
                Platform.runLater(() -> {
                    refreshInstalled();
                    progress.done(I18n.t("mods.pack.removed", removed));
                });
            });
            return;
        }
        mutate(I18n.t("mods.task.packInstall"), () -> {
            ModInstaller.Result result =
                    service.installPack(profile, pack, progress);
            Platform.runLater(() -> {
                refreshInstalled();
                progress.done(I18n.t("mods.installedCount", result.installed().size()));
                if (!result.isClean()) {
                    warn(I18n.t("mods.attention.header"), String.join("\n",
                            java.util.stream.Stream.concat(result.skipped().stream(),
                                    result.manualDownloads().stream()).toList()));
                }
            });
        });
    }

    // ---------------------------------------------------------------- state

    private void refreshInstalled() {
        // Two reads, and they answer different questions. The library says which
        // projects are installed, which is what greys out an Install button in
        // the browse tab; the scan says what is actually in the folder, which is
        // what the installed tab lists - and the two differ by exactly the mods
        // the launcher did not put there.
        library = service.installedMods(profile);
        java.util.List<ModEntry> mods = service.modsIn(profile);
        installedList.setItems(FXCollections.observableArrayList(mods));
        installedEmpty.setText(I18n.t("mods.installed.empty"));
        installedTab.setText(I18n.t("mods.tab.installed", mods.size()));
        updateIdentifyRow(mods);
        browseTab.setText(I18n.t("mods.tab.browse"));
        searchButton.setText(I18n.t("mods.search"));
        updatePackButton();
        // Repaint the browse list: an install changes the state of its rows.
        resultList.refresh();
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /**
     * Decides whether the pack is offered, and as install or as removal.
     *
     * <p>Runs once per window: it costs one lookup per pack entry, and the
     * answer cannot change while the window is open because the version and
     * loader it depends on belong to the profile, which is edited elsewhere.
     */
    private void loadPackStateAsync() {
        // The answer belongs to one version and one loader. Both can have changed
        // while the window was closed, so the previous answer is discarded rather
        // than shown until a new one arrives.
        pack = null;
        packAvailable = false;
        packButton.setVisible(false);
        packButton.setManaged(false);
        packNote.setVisible(false);
        packNote.setManaged(false);

        if (profile.loader() == LoaderType.VANILLA) {
            return;
        }
        run(I18n.t("mods.task.packCheck"), false, () -> {
            ModPack loaded = ModPack.hexadronOptimise();
            ModInstaller.PackAvailability availability = service.packAvailability(profile, loaded);
            Platform.runLater(() -> {
                pack = loaded;
                packAvailable = availability.available();
                if (!packAvailable) {
                    packNote.setText(I18n.t("mods.pack.unavailable",
                            profile.minecraftVersion(), profile.loader().displayName()));
                    packNote.setVisible(true);
                    packNote.setManaged(true);
                }
                updatePackButton();
                progress.done(I18n.t("status.ready"));
            });
        });
    }

    private void updatePackButton() {
        if (pack == null || !packAvailable) {
            packButton.setVisible(false);
            packButton.setManaged(false);
            return;
        }
        boolean installed = library != null && library.isPackInstalled(pack.id());
        packButton.setText(I18n.t(installed ? "mods.pack.remove" : "mods.pack.install"));
        packButton.getStyleClass().removeAll("primary", "danger");
        packButton.getStyleClass().add(installed ? "danger" : "primary");
        packButton.setDisable(busy);
        packButton.setVisible(true);
        packButton.setManaged(true);
    }

    // ---------------------------------------------------------------- plumbing

    @FunctionalInterface
    private interface Task {
        void run() throws Exception;
    }

    /**
     * Runs a mutating action - install, remove - one at a time.
     *
     * <p>Two of these at once would interleave writes to the same lock file and
     * the same folder, and the loser would leave a jar on disk that nothing
     * records. Searching is not affected: it changes nothing, so it is never
     * blocked by a download and never blocks one.
     */
    private void mutate(String name, Task task) {
        if (busy) {
            progress.log(I18n.t("status.busy", name));
            return;
        }
        setBusy(true);
        run(name, true, () -> {
            try {
                task.run();
            } finally {
                Platform.runLater(() -> setBusy(false));
            }
        });
    }

    private void run(String name, boolean alerting, Task task) {
        progress.stage(name);
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                String detail = e.getMessage() == null ? e.toString() : e.getMessage();
                Platform.runLater(() -> {
                    progress.failed(detail);
                    if (alerting) {
                        warn(I18n.t("status.failed", name), detail);
                    } else {
                        browseEmpty.setText(detail);
                    }
                });
            }
        }, "hexadron-mods");
        thread.setDaemon(true);
        thread.start();
    }

    /** Offers the lookup only while there is something in the folder to look up. */
    private void updateIdentifyRow(java.util.List<ModEntry> mods) {
        int pending = service.unidentifiedModCount(profile, mods);
        identifyButton.setText(I18n.t("mods.identify"));
        identifyButton.setDisable(busy);
        identifyNote.setText(I18n.t("mods.identify.note", pending));
        identifyRow.setVisible(pending > 0);
        identifyRow.setManaged(pending > 0);
    }

    private void setBusy(boolean value) {
        busy = value;
        packButton.setDisable(value || pack == null);
        identifyButton.setDisable(value);
        resultList.refresh();
        installedList.refresh();
    }

    private void warn(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.initOwner(stage);
        Theme.apply(alert.getDialogPane());
        alert.setHeaderText(header);
        alert.setTitle(header);
        alert.getDialogPane().setPrefWidth(560);
        alert.showAndWait();
    }

    /** 1234567 -> "1.2M". Exact counts are noise at this scale. */
    private static String formatCount(long value) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        }
        return Long.toString(value);
    }

    /** The source filter, including "every platform". */
    private enum SourceChoice {
        ALL(null),
        MODRINTH(ModProvider.Source.MODRINTH),
        CURSEFORGE(ModProvider.Source.CURSEFORGE);

        private final ModProvider.Source source;

        SourceChoice(ModProvider.Source source) {
            this.source = source;
        }

        ModProvider.Source source() {
            return source;
        }

        String label() {
            return source == null ? I18n.t("mods.source.all") : source.displayName();
        }
    }

    /**
     * Progress for this window's own status line.
     *
     * <p>Separate from the launcher's log pane: a download started here belongs
     * on this window, not appended to a log the user cannot see from it.
     */
    private static final class BrowserProgress implements com.hexadron.launcher.core.Progress {
        private final Label label;
        private final ProgressBar bar;

        BrowserProgress(Label label, ProgressBar bar) {
            this.label = label;
            this.bar = bar;
        }

        @Override
        public void stage(String name) {
            Platform.runLater(() -> {
                label.setText(name);
                bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            });
        }

        @Override
        public void bytes(long completed, long total) {
        }

        @Override
        public void items(int completed, int total) {
            if (total <= 0) {
                return;
            }
            double fraction = (double) completed / total;
            Platform.runLater(() -> bar.setProgress(fraction));
        }

        @Override
        public void log(String message) {
            Platform.runLater(() -> label.setText(message));
        }

        void done(String message) {
            label.setText(message);
            bar.setProgress(1);
        }

        void failed(String message) {
            label.setText(message);
            bar.setProgress(0);
        }
    }
}
