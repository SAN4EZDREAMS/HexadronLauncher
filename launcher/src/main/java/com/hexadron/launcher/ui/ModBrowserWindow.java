package com.hexadron.launcher.ui;

import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.mods.ModCategory;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
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

    /**
     * The category filter.
     *
     * <p>A menu of tick boxes rather than a list that picks one, because a mod
     * is filed under several and a player narrowing a search usually means more
     * than one thing at once - "adventure and magic", not "adventure, and now
     * start again with magic". The menu stays open while they are ticked, so
     * choosing four is four clicks rather than four round trips.
     */
    private final MenuButton categoryBox = new MenuButton();
    private final java.util.Set<ModCategory> chosenCategories =
            java.util.EnumSet.noneOf(ModCategory.class);

    /** The boxes themselves, so "clear all" can untick them without rebuilding the menu. */
    private final java.util.Map<ModCategory, CheckBox> categoryBoxes =
            new java.util.EnumMap<>(ModCategory.class);
    private final Button clearCategories = new Button();

    /** Names, and the drawings that go beside them. Rebuilt when the drawings arrive. */
    private Categories categories;
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

    /**
     * Getting a pile of jars into an instance in one go.
     *
     * <p>A player who has just downloaded eleven mods from a browser has them in
     * one folder, and the only way in used to be a file manager and a path they
     * had to be told. The launcher knows the path.
     */
    private final Button importButton = new Button();

    private final TextField installedSearch = new TextField();
    private final ComboBox<ModFilter> installedFilter = new ComboBox<>();
    private final Label installedCount = new Label();

    /**
     * Every mod in the folder, before the search box and the filter have had
     * their say. Kept so that typing narrows a list that is already in hand
     * rather than re-reading the folder on every keystroke.
     */
    private java.util.List<ModEntry> installedAll = java.util.List.of();

    /**
     * Which of those mods are needed by which others, read from the jars.
     *
     * <p>Rebuilt with the list, because switching one mod off changes the answer
     * for every other one: a mod that is not loaded cannot need anything.
     */
    private com.hexadron.launcher.mods.ModDependents dependents =
            com.hexadron.launcher.mods.ModDependents.NONE;

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
        loadCategoryDataAsync();
        runSearch();

        stage.show();
        stage.toFront();
    }

    /** One-time stage setup. Everything here is illegal after the first show. */
    private void buildStage() {
        categories = new Categories(service.categoryArt());
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
        installedSearch.setPromptText(I18n.t("mods.installed.search"));
        importButton.setText(I18n.t("mods.import"));
        // The filter names are drawn by its converter, which reads I18n each
        // time; nudging the box is what makes it redraw after a language change.
        ModFilter chosenFilter = installedFilter.getValue();
        installedFilter.setValue(null);
        installedFilter.setValue(chosenFilter);
        buildCategoryMenu();
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

        categoryBox.setPrefWidth(170);
        buildCategoryMenu();

        searchButton.setOnAction(event -> runSearch());

        HBox controls = new HBox(8, searchField, sortBox, categoryBox, sourceBox, searchButton);
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

    /**
     * Fills the category menu.
     *
     * <p>Rebuilt rather than updated when the language changes or the drawings
     * arrive, because both change every item in it and a menu of nineteen is
     * cheaper to build than to reconcile.
     */
    private void buildCategoryMenu() {
        categoryBoxes.clear();

        // Every category on screen at once, in two columns.
        //
        // The panel this replaces was one column in a scroller, and a scroller
        // is a thing that has to be discovered: nine of the nineteen were below
        // the edge with nothing but a thin bar to say so, and somebody looking
        // for "Технології" saw a list that stopped at "Оптимізація". Two columns
        // is the shape that fits the whole list in a panel shorter than the
        // window it drops out of, so the list is read rather than scrolled.
        //
        // Down the first column, then the second, because a list in reading
        // order is read down, not across.
        java.util.List<ModCategory> ordered = Categories.inReadingOrder();
        int rows = (ordered.size() + 1) / 2;

        GridPane list = new GridPane();
        list.getStyleClass().add("category-list");
        list.setHgap(14);
        list.setVgap(2);
        int placed = 0;
        for (ModCategory category : ordered) {
            CheckBox box = new CheckBox(Categories.name(category));
            box.setSelected(chosenCategories.contains(category));
            box.setGraphic(categories.icon(category, 14));
            box.setMaxWidth(Double.MAX_VALUE);
            // As wide as its name, never narrower. A row that may stretch to
            // fill its column may also be squeezed into it, and a squeezed name
            // is not a name with less space around it: it is a name with its
            // last two letters replaced by an ellipsis. The column widens to the
            // longest name instead.
            box.setMinWidth(Region.USE_PREF_SIZE);
            box.setOnAction(event -> {
                if (box.isSelected()) {
                    chosenCategories.add(category);
                } else {
                    chosenCategories.remove(category);
                }
                updateCategoryLabel();
                runSearch();
            });
            categoryBoxes.put(category, box);
            list.add(box, placed / rows, placed % rows);
            placed++;
        }

        clearCategories.setText(I18n.t("mods.category.clear"));
        clearCategories.setMaxWidth(Double.MAX_VALUE);
        clearCategories.getStyleClass().add("category-clear");
        clearCategories.setOnAction(event -> {
            if (chosenCategories.isEmpty()) {
                return;
            }
            chosenCategories.clear();
            // The boxes are unticked rather than the panel rebuilt: this runs
            // from inside the popup that holds them, and replacing what a menu
            // is showing while it delivers an event to it is not a thing to do
            // for the sake of saving a loop.
            categoryBoxes.values().forEach(box -> box.setSelected(false));
            updateCategoryLabel();
            runSearch();
        });

        VBox panel = new VBox(6, clearCategories, list);
        panel.getStyleClass().add("category-panel");

        CustomMenuItem item = new CustomMenuItem(panel);
        // One item holding the whole panel means the menu's own highlight is the
        // whole panel: the pointer anywhere inside lit all nineteen rows at
        // once. The stylesheet turns that highlight off for this item, and each
        // row lights itself instead.
        item.getStyleClass().add("category-item");
        // The popup stays up while boxes are ticked: choosing four categories
        // should be four clicks, not four times opening the same menu.
        item.setHideOnClick(false);
        categoryBox.getItems().setAll(item);
        updateCategoryLabel();
    }

    private void updateCategoryLabel() {
        categoryBox.setText(chosenCategories.isEmpty()
                ? I18n.t("mods.category.any")
                : I18n.t("mods.category.some", chosenCategories.size()));
        clearCategories.setDisable(chosenCategories.isEmpty());
    }

    /** The chosen categories, in the platform's own order rather than the menu's. */
    private java.util.List<ModCategory> categoriesForSearch() {
        java.util.List<ModCategory> chosen = new java.util.ArrayList<>();
        for (ModCategory category : ModCategory.values()) {
            if (chosenCategories.contains(category)) {
                chosen.add(category);
            }
        }
        return chosen;
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
        acceptDroppedJars(installedList);

        installedSearch.setPromptText(I18n.t("mods.installed.search"));
        HBox.setHgrow(installedSearch, Priority.ALWAYS);
        // On every keystroke, over a list already in memory: the folder is not
        // re-read, so there is nothing here worth waiting for a pause to do.
        installedSearch.textProperty().addListener(
                (observable, previous, value) -> applyInstalledFilter());

        installedFilter.setItems(FXCollections.observableArrayList(ModFilter.values()));
        installedFilter.setValue(ModFilter.ALL);
        installedFilter.setPrefWidth(190);
        installedFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ModFilter value) {
                return value == null ? "" : I18n.t(value.key());
            }

            @Override
            public ModFilter fromString(String text) {
                return null;
            }
        });
        installedFilter.valueProperty().addListener(
                (observable, previous, value) -> applyInstalledFilter());

        importButton.setOnAction(event -> importMods());

        HBox controls = new HBox(8, installedSearch, installedFilter, importButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        installedCount.getStyleClass().add("muted");

        identifyNote.getStyleClass().add("muted");
        identifyNote.setWrapText(true);
        HBox.setHgrow(identifyNote, Priority.ALWAYS);
        identifyButton.setOnAction(event -> identifyExternal());
        HBox identifyRow = new HBox(8, identifyNote, identifyButton);
        identifyRow.setAlignment(Pos.CENTER_LEFT);
        identifyRow.setVisible(false);
        identifyRow.setManaged(false);
        this.identifyRow = identifyRow;

        VBox pane = new VBox(10, controls, identifyRow, installedCount, installedList);
        pane.getStyleClass().add("browse-pane");
        return pane;
    }

    /** Holds the identify button, so it can be hidden when there is nothing to ask about. */
    private HBox identifyRow;

    /** What the installed list is narrowed to. */
    private enum ModFilter {

        ALL("mods.filter.all"),
        MANAGED("mods.filter.managed"),
        EXTERNAL("mods.filter.external"),
        DISABLED("mods.filter.disabled"),
        WRONG_VERSION("mods.filter.wrongVersion");

        private final String key;

        ModFilter(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }

        boolean accepts(ModEntry mod) {
            return switch (this) {
                case ALL -> true;
                case MANAGED -> mod.isManaged();
                case EXTERNAL -> !mod.isManaged();
                case DISABLED -> !mod.enabled();
                case WRONG_VERSION -> mod.isWrongVersion();
            };
        }
    }

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

        /** The height of the category line, empty or not. See {@link #tags}. */
        private static final double TAG_HEIGHT = 18;

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

        /**
         * What the mod is for, as the platform files it.
         *
         * <p>Its own line, and always the same height whether or not there is
         * anything on it. A row that grew a line when a mod happened to have
         * categories would put the list back to the ladder of uneven heights it
         * was before.
         */
        protected final TagFlow tags = new TagFlow(TAG_HEIGHT, 11);

        private final VBox text = new VBox(1, name, meta, description, tags, page);
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

        /**
         * Writes one line, and keeps its space whether or not there is one.
         *
         * <p>Hidden rather than unmanaged, and that is the whole of the "the
         * list goes uneven" bug. A row whose mod published no description, or no
         * page, used to be one line shorter than the row above it, so a list of
         * forty mods was a ladder of four different row heights that changed
         * again as logos arrived. Every row now reserves the same four lines and
         * leaves the ones it has nothing for blank.
         */
        protected static void line(Label label, String value) {
            boolean present = value != null && !value.isBlank();
            label.setText(present ? value : "");
            label.setVisible(present);
        }

        /**
         * Writes the category line.
         *
         * <p>All of them, in the order the reader is most likely to be looking
         * for. Whatever they ticked in the filter comes first: somebody who has
         * narrowed a search to two categories is scanning for those two, and
         * finding them behind a count that has to be hovered would answer the
         * question they asked with an extra step.
         */
        protected void tags(java.util.List<ModCategory> shown) {
            tags.show(ModCategory.chosenFirst(shown, chosenCategories), categories);
        }

        /** Points the link at a page, or leaves its line blank. */
        protected void link(String url, Runnable action) {
            boolean present = SystemBrowser.isWebPage(url);
            page.setText(present ? I18n.t("mods.details") : "");
            page.setVisible(present);
            page.setOnAction(event -> action.run());
        }

        /**
         * Adds or removes a style class, and only when it is not already right.
         *
         * <p>The unconditional {@code removeAll} then {@code add} that used to be
         * here is what made the badge and the buttons jump for one frame every
         * time the selection moved. {@link javafx.scene.control.ListCell#updateItem}
         * is called from the list's own layout pass - after CSS has been applied
         * for that frame - so a style class changed there is not resolved until
         * the next one. The row is therefore measured once with the old padding,
         * border and weight, drawn in the wrong place, and corrected a frame
         * later. {@code .badge-off} adds a one-pixel border and
         * {@code .badge-wrong} makes the text bold, so "the old values" really
         * are a different width.
         *
         * <p>Checking first makes the common case - scrolling or clicking
         * through rows whose badges are the same kind - touch nothing at all,
         * and there is nothing to resolve late.
         */
        protected static void styleClass(javafx.scene.Node node, String name, boolean wanted) {
            if (node.getStyleClass().contains(name) == wanted) {
                return;
            }
            if (wanted) {
                node.getStyleClass().add(name);
            } else {
                node.getStyleClass().remove(name);
            }
        }

        /**
         * Points a control at its tooltip, or takes it away.
         *
         * <p>One tooltip per cell, reused. A fresh one on every update is a node
         * and a listener allocated for every row the eye passes over, thrown
         * away unread.
         */
        protected static void tooltip(javafx.scene.control.Control control,
                                      javafx.scene.control.Tooltip tip, String text) {
            if (text == null) {
                if (control.getTooltip() != null) {
                    control.setTooltip(null);
                }
                return;
            }
            if (!text.equals(tip.getText())) {
                tip.setText(text);
            }
            if (control.getTooltip() != tip) {
                control.setTooltip(tip);
            }
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
            tags(hit.categories());
            // The catalogue needs the link at least as much as the installed
            // list does: this is where the user is deciding whether they want
            // the mod at all, and that decision is made on the mod's own page.
            link(hit.pageUrl(), () -> openPage(hit.title(), hit.pageUrl()));

            boolean installed = library != null && library.contains(hit.source(), hit.projectId());
            action.setText(I18n.t(installed ? "mods.installed" : "mods.install"));
            action.setDisable(installed || busy);
            // ".primary" carries a font size and a padding, so churning it on
            // every row is a button that changes width a frame late.
            styleClass(action, "primary", !installed);
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

        /** One each, reused, rather than a new node per row the eye passes over. */
        private final javafx.scene.control.Tooltip badgeTip = new javafx.scene.control.Tooltip();
        private final javafx.scene.control.Tooltip removeTip = new javafx.scene.control.Tooltip();

        /** The names of the mods that need this one, shown while the badge is hovered. */
        private final HoverPanel needed = new HoverPanel();
        private java.util.List<ModEntry> neededShows = java.util.List.of();

        InstalledCell() {
            badge.getStyleClass().add("badge");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            // Set once, on a badge that is reused. A panel with nothing in it
            // opens nothing, so a mod nothing depends on simply never shows one.
            needed.watch(badge);
            // Set once. Remove is the destructive button in every row this cell
            // will ever show, so saying so again on each of them is churn with
            // no answer that can change.
            remove.getStyleClass().add("danger");
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
            tags(mod.categories());
            link(mod.pageUrl(), () -> openPage(mod.title(), mod.pageUrl()));

            badge.setText(ModLabels.badge(mod));
            styleClass(badge, "badge-off", !mod.enabled());
            styleClass(badge, "badge-wrong", mod.enabled() && mod.isWrongVersion());
            styleClass(badge, "badge-pack",
                    mod.enabled() && !mod.isWrongVersion() && mod.origin() == ModOrigin.PACK);
            styleClass(badge, "badge-dependency", mod.enabled() && !mod.isWrongVersion()
                    && mod.origin() == ModOrigin.DEPENDENCY);

            // What would break if this one went away. Rebuilt only when the
            // answer differs from the row this cell drew last.
            java.util.List<ModEntry> needs = dependents.of(mod);
            styleClass(badge, "badge-linked", !needs.isEmpty());
            if (!needs.equals(neededShows)) {
                neededShows = needs;
                fillNeeded(needs);
            }

            // One thing at a time under the pointer: a row whose badge already
            // opens a list of names does not also get a hint over the top of it.
            tooltip(badge, badgeTip, needs.isEmpty()
                    && mod.isWrongVersion() && mod.requires() != null
                    ? I18n.t("mods.wrongVersion.tooltip", mod.requires(),
                            profile.minecraftVersion())
                    : null);

            toggle.setText(I18n.t(mod.enabled() ? "mods.disable" : "mods.enable"));
            toggle.setDisable(busy);
            toggle.setOnAction(event -> toggleMod(mod));

            remove.setText(I18n.t("mods.remove"));
            // A pack goes out whole, through its own button in the header.
            remove.setDisable(!mod.isRemovable() || busy);
            tooltip(remove, removeTip,
                    mod.isRemovable() ? null : I18n.t("mods.remove.packLocked"));
            remove.setOnAction(event -> removeMod(mod));
            showRow();
        }

        /**
         * Fills the panel behind the badge with the mods that need this one.
         *
         * <p>Names that can be pressed, not a sentence listing them. The reader
         * hovering a dependency is asking "what is this here for", and the
         * useful next step is the mod that put it there - so each name takes
         * them to that row, the way an anchor on a page does.
         */
        private void fillNeeded(java.util.List<ModEntry> needs) {
            needed.content().clear();
            if (needs.isEmpty()) {
                needed.hide();
                return;
            }
            Label title = new Label(I18n.t("mods.dependents.title"));
            title.getStyleClass().add("hover-title");
            needed.content().add(title);
            for (ModEntry dependent : needs) {
                Hyperlink link = new Hyperlink(dependent.title());
                link.getStyleClass().add("hover-link");
                link.setOnAction(event -> {
                    needed.hide();
                    jumpToMod(dependent);
                });
                needed.content().add(link);
            }
            Label hint = new Label(I18n.t("mods.dependents.hint"));
            hint.getStyleClass().add("muted");
            needed.content().add(hint);
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
        java.util.List<ModCategory> chosen = categoriesForSearch();
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
                    service.searchMods(profile, query, sort, chosen, only, PAGE_SIZE, offset);
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

        // One dialog, not two. A mod that other mods need is still a mod being
        // removed, and asking twice for one button is how a warning becomes
        // something to click past.
        java.util.List<ModEntry> needs = warnedDependents(mod);
        if (!needs.isEmpty()) {
            body = dependentsBody(mod, needs, true) + "\n\n" + body;
        }
        if (!confirmTakingAway(needs.isEmpty()
                        ? I18n.t("mods.remove.header")
                        : I18n.t("mods.dependents.header"),
                body, !needs.isEmpty())) {
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
        // Only on the way off. Switching a mod on cannot leave anything without
        // what it needs, so there is nothing to ask about.
        if (!enable) {
            java.util.List<ModEntry> needs = warnedDependents(mod);
            if (!needs.isEmpty() && !confirmTakingAway(I18n.t("mods.dependents.header"),
                    dependentsBody(mod, needs, false) + "\n\n"
                            + I18n.t("mods.dependents.confirm.disable"), true)) {
                return;
            }
        }
        mutate(I18n.t(enable ? "mods.task.enable" : "mods.task.disable", mod.title()), () -> {
            service.setModEnabled(profile, mod, enable);
            Platform.runLater(() -> {
                refreshInstalled();
                progress.done(I18n.t(enable ? "mods.enabled" : "mods.disabled", mod.title()));
            });
        });
    }

    /** How many dependent mods are named in a warning before it says "and more". */
    private static final int DEPENDENTS_LISTED = 8;

    /**
     * The mods that need this one, or nothing at all when the warning is off.
     *
     * <p>The setting is read here rather than at each call site so that "do not
     * show this again" means one thing in both places it can be ticked.
     */
    private java.util.List<ModEntry> warnedDependents(ModEntry mod) {
        return service.settings().warnAboutDependents()
                ? dependents.of(mod)
                : java.util.List.of();
    }

    /** What is about to be left without something it needs, by name. */
    private String dependentsBody(ModEntry mod, java.util.List<ModEntry> needs, boolean removing) {
        StringBuilder text = new StringBuilder(I18n.t(
                removing ? "mods.dependents.remove" : "mods.dependents.disable", mod.title()));
        needs.stream().limit(DEPENDENTS_LISTED)
                .forEach(dependent -> text.append("\n   \u2022 ").append(dependent.title()));
        if (needs.size() > DEPENDENTS_LISTED) {
            text.append("\n   ").append(
                    I18n.t("mods.dependents.more", needs.size() - DEPENDENTS_LISTED));
        }
        return text.toString();
    }

    /**
     * Asks the question, with the box that stops it being asked again.
     *
     * <p>The box is honoured whichever way the question is answered, because
     * that is what it says: somebody who ticks it and then presses No has said
     * "stop asking", not "stop asking if I agree". Where to switch it back on is
     * written under it, since a dialog that can turn itself off for good has to
     * say where it went.
     *
     * @return true when the action should go ahead
     */
    private boolean confirmTakingAway(String header, String body, boolean offerToHide) {
        Label text = new Label(body);
        text.setWrapText(true);

        CheckBox hide = new CheckBox(I18n.t("mods.dependents.hide"));
        Label hideNote = new Label(I18n.t("mods.dependents.hideNote"));
        hideNote.getStyleClass().add("muted");
        hideNote.setWrapText(true);

        VBox content = offerToHide
                ? new VBox(10, text, hide, hideNote)
                : new VBox(10, text);
        content.setMinWidth(0);

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        Theme.apply(confirm.getDialogPane());
        confirm.setHeaderText(header);
        confirm.getDialogPane().setContent(content);
        confirm.getDialogPane().setPrefWidth(560);

        boolean yes = confirm.showAndWait()
                .filter(button -> button.getButtonData().isDefaultButton()).isPresent();
        if (offerToHide && hide.isSelected()) {
            service.settings().warnAboutDependents(false);
            try {
                service.settings().save();
            } catch (java.io.IOException e) {
                progress.failed(I18n.t("log.settingsSaveFailed", e.getMessage()));
            }
        }
        return yes;
    }

    /**
     * Goes to a mod in the installed list and selects it.
     *
     * <p>The list is widened first when the mod is not in the current view.
     * Jumping to a row that a search box is hiding does nothing visible at all,
     * which reads as a link that is broken rather than as a filter that is on.
     */
    private void jumpToMod(ModEntry target) {
        tabs.getSelectionModel().select(installedTab);
        boolean visible = installedList.getItems().stream()
                .anyMatch(mod -> mod.key().equals(target.key()));
        if (!visible) {
            installedSearch.clear();
            installedFilter.setValue(ModFilter.ALL);
            applyInstalledFilter();
        }
        java.util.List<ModEntry> shown = installedList.getItems();
        for (int index = 0; index < shown.size(); index++) {
            if (shown.get(index).key().equals(target.key())) {
                installedList.getSelectionModel().clearAndSelect(index);
                // One row above the target, so it does not land against the top
                // edge with no context above it.
                installedList.scrollTo(Math.max(0, index - 1));
                installedList.requestFocus();
                return;
            }
        }
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
        installedAll = service.modsIn(profile);
        dependents = service.modDependents(installedAll);
        installedTab.setText(I18n.t("mods.tab.installed", installedAll.size()));
        importButton.setText(I18n.t("mods.import"));
        installedSearch.setPromptText(I18n.t("mods.installed.search"));
        applyInstalledFilter();
        updateIdentifyRow(installedAll);
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
     * Fetches the category drawings, and fills in what old lock files never
     * recorded about the mods already installed.
     *
     * <p>Both quietly. A failure costs nineteen little pictures and a line of
     * categories, the names beside them are already on screen, and neither is
     * worth a message or a place on the status line - which belongs to whatever
     * the user actually asked for.
     */
    private void loadCategoryDataAsync() {
        Thread thread = new Thread(() -> {
            try {
                if (service.refreshCategoryArt()) {
                    Platform.runLater(() -> {
                        categories = new Categories(service.categoryArt());
                        buildCategoryMenu();
                        resultList.refresh();
                        installedList.refresh();
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Nineteen drawings. Not worth a word.
            }
            try {
                // Everything installed before the lock file had a place for
                // categories has none, and would sit for ever with an empty line
                // where its categories go. One request fills in the lot.
                if (service.describeInstalledMods(profile)) {
                    Platform.runLater(this::refreshInstalled);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // The rows keep the little they had. Not worth a word either.
            }
        }, "hexadron-categories");
        thread.setDaemon(true);
        thread.start();
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

    /**
     * Narrows the installed list to what was asked for.
     *
     * <p>The search matches the name, the file name and the authors, because
     * those are the three things a player knows a mod by and they are rarely the
     * same word - "sodium", "sodium-fabric-0.5.13.jar" and "jellysquid3" are one
     * mod, and any of them is a reasonable thing to type.
     */
    private void applyInstalledFilter() {
        ModFilter filter = installedFilter.getValue() == null
                ? ModFilter.ALL : installedFilter.getValue();
        String query = installedSearch.getText() == null
                ? "" : installedSearch.getText().trim().toLowerCase(Locale.ROOT);

        java.util.List<ModEntry> shown = installedAll.stream()
                .filter(filter::accepts)
                .filter(mod -> matches(mod, query))
                .toList();
        installedList.setItems(FXCollections.observableArrayList(shown));

        boolean narrowed = shown.size() != installedAll.size();
        installedEmpty.setText(installedAll.isEmpty()
                ? I18n.t("mods.installed.empty")
                : I18n.t("mods.installed.noMatch"));
        installedCount.setText(narrowed
                ? I18n.t("mods.installed.shown", shown.size(), installedAll.size())
                : "");
        installedCount.setVisible(narrowed);
        installedCount.setManaged(narrowed);
    }

    private static boolean matches(ModEntry mod, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (mod.title().toLowerCase(Locale.ROOT).contains(query)
                || mod.fileName().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return mod.authors().stream()
                .anyMatch(author -> author.toLowerCase(Locale.ROOT).contains(query));
    }

    /**
     * Asks for jar files and copies them in.
     *
     * <p>Several at once, because that is how they arrive: a player who has just
     * been through a mod site has a folder of them, not one.
     */
    private void importMods() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("mods.import.title"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(I18n.t("mods.import.filter"), "*.jar"));
        java.util.List<java.io.File> chosen = chooser.showOpenMultipleDialog(stage);
        if (chosen == null || chosen.isEmpty()) {
            return;
        }
        importFiles(chosen.stream().map(java.io.File::toPath).toList());
    }

    /**
     * Lets jars be dropped onto the installed list.
     *
     * <p>The same action as the button, reached the way a player would try it
     * first. Only files are accepted, and only as a copy - a drop that moved the
     * originals out of the folder they were downloaded to would be a surprise.
     */
    private void acceptDroppedJars(javafx.scene.Node target) {
        target.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles() && !busy) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        target.setOnDragDropped(event -> {
            Dragboard board = event.getDragboard();
            boolean handled = board.hasFiles();
            if (handled) {
                importFiles(board.getFiles().stream().map(java.io.File::toPath).toList());
            }
            event.setDropCompleted(handled);
            event.consume();
        });
    }

    private void importFiles(java.util.List<java.nio.file.Path> files) {
        if (files.isEmpty()) {
            return;
        }
        mutate(I18n.t("mods.task.import", files.size()), () -> {
            com.hexadron.launcher.mods.ModScan.Imported result =
                    service.importMods(profile, files, progress);
            Platform.runLater(() -> {
                refreshInstalled();
                progress.done(I18n.t("mods.imported", result.imported().size()));
                // Named rather than counted. "Three of your eleven files were
                // skipped" without saying which three is a message that has to
                // be worked out with a file manager.
                if (!result.skipped().isEmpty()) {
                    warn(I18n.t("mods.import.skipped.header"),
                            String.join("\n", result.skipped()));
                }
            });
        });
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
        importButton.setDisable(value);
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
