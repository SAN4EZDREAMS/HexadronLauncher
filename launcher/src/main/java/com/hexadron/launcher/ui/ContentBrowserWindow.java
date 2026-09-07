/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

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
 * The content window: one per profile, opened from the main window.
 *
 * <h2>One window, several kinds of thing</h2>
 *
 * <p>It began as a mod browser and is now the window for everything a player
 * installs into an instance - mods, modpacks, data packs - because they are the
 * same task performed against the same instance: search a platform, read about
 * something, install it, look at what is already there, search again. Three
 * windows for that would be three status lines, three ways for two installs to
 * write to one folder at once, and three places to fix the next thing that is
 * wrong with a row.
 *
 * <p>So the kinds are a list down the left and the panel on the right belongs to
 * whichever is chosen. A list rather than a second row of tabs: the tabs inside a
 * section are already {@code Browse} and {@code Installed}, and putting kinds on
 * a second row above them makes two rows of tabs that look the same and mean
 * different things. The list also has room for the kinds that are not here yet.
 *
 * <p>It is a separate stage rather than a panel or a modal dialog on purpose.
 * Choosing mods is a long, exploratory task, and a separate window can be left
 * open beside the launcher and closed whenever; a modal one would block the
 * launcher for as long as the user was browsing.
 *
 * <h2>What is shared, and why it is shared here</h2>
 *
 * <p>The status line, the progress bar, the rule that only one install runs at a
 * time, and the alert dialogs. All four belong to the window rather than to a
 * section, and {@link ContentSection.Host} is how a section reaches them - see
 * that interface for what each one is for.
 */
public final class ContentBrowserWindow implements ContentSection.Host {

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

    /**
     * The kinds of thing this window covers, in the order they are offered.
     *
     * <p>Mods first because it is what almost every visit is for. The rest in the
     * order of how much of an instance they decide: a modpack decides all of it,
     * a data pack decides one world.
     */
    private enum Section {
        MODS("mods.kind.mod"),
        MODPACKS("mods.kind.modpack"),
        DATAPACKS("mods.kind.datapack");

        private final String key;

        Section(String key) {
            this.key = key;
        }

        String title() {
            return I18n.t(key);
        }

        javafx.scene.Node glyph() {
            return switch (this) {
                case MODS -> Glyphs.module();
                case MODPACKS -> Glyphs.crate();
                case DATAPACKS -> Glyphs.page();
            };
        }
    }

    private final ListView<Section> sectionList =
            new ListView<>(FXCollections.observableArrayList(Section.values()));

    /** The panel for each kind. Mods is this class's own; the rest are their own. */
    private final javafx.scene.layout.StackPane sectionPane = new javafx.scene.layout.StackPane();
    private ModpackSection modpacks;
    private DatapackSection datapacks;
    private javafx.scene.Node modsPane;

    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final BrowserProgress progress;

    /** Where the next page starts, and how many matches the platforms report. */
    private int nextOffset;
    private int totalMatches = -1;

    private ModLibrary library;
    private ModPack pack;
    private boolean packAvailable;

    /**
     * Why the pack cannot be installed here, or null when it can.
     *
     * <p>Held rather than derived because the button is drawn again on every
     * refresh - after an install, after a filter - and the reason must not be
     * lost between the check that found it and the next repaint.
     */
    private String packBlockedReason;
    private volatile boolean busy;

    public ContentBrowserWindow(LauncherService service, Stage owner, Profile profile, Runnable onChanged) {
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
        modpacks.refresh();
        datapacks.refresh();
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

        modpacks = new ModpackSection(this);
        datapacks = new DatapackSection(this);
        modsPane = buildTabs();

        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setLeft(buildSidebar());
        root.setCenter(buildSectionPane());
        root.setBottom(buildFooter());

        Scene scene = new Scene(root, 1080, 720);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(540);
    }

    /**
     * The list of kinds down the left.
     *
     * <p>Every kind is always on it, including the ones this profile cannot use.
     * A row that disappeared on a profile with no loader would leave the user
     * looking for it; a row that is there and says why is a row that answers the
     * question. What each section does about not being usable is its own - the
     * mods one has always said so in place of its results.
     */
    private VBox buildSidebar() {
        sectionList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Section section, boolean empty) {
                super.updateItem(section, empty);
                if (empty || section == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(section.title());
                setGraphic(section.glyph());
            }
        });
        sectionList.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, value) -> showSection(value));
        sectionList.getSelectionModel().select(Section.MODS);
        sectionList.setPrefWidth(196);
        sectionList.setMinWidth(150);
        sectionList.getStyleClass().add("kind-list");

        sectionTitle.getStyleClass().add("section-title");
        VBox sidebar = new VBox(8, sectionTitle, sectionList);
        VBox.setVgrow(sectionList, Priority.ALWAYS);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private final Label sectionTitle = new Label();

    private javafx.scene.layout.StackPane buildSectionPane() {
        sectionPane.getChildren().setAll(modsPane, modpacks.node(), datapacks.node());
        showSection(Section.MODS);
        return sectionPane;
    }

    /**
     * Brings one section's panel forward.
     *
     * <p>Managed as well as hidden. A hidden panel that is still managed is still
     * measured, and three tab panes taking part in one layout is three times the
     * work on every resize for two panels nobody can see.
     */
    private void showSection(Section section) {
        Section chosen = section == null ? Section.MODS : section;
        javafx.scene.Node[] panes = {modsPane, modpacks.node(), datapacks.node()};
        Section[] order = {Section.MODS, Section.MODPACKS, Section.DATAPACKS};
        for (int index = 0; index < panes.length; index++) {
            boolean visible = order[index] == chosen;
            panes[index].setVisible(visible);
            panes[index].setManaged(visible);
        }
        // The pack button belongs to the mods section: it installs a set of mods.
        boolean mods = chosen == Section.MODS;
        packButton.setVisible(mods);
        packButton.setManaged(mods);
        packNote.setVisible(mods && packBlockedReason != null);
        packNote.setManaged(mods && packBlockedReason != null);

        if (!built) {
            return;
        }
        switch (chosen) {
            case MODPACKS -> modpacks.onShown();
            case DATAPACKS -> datapacks.onShown();
            case MODS -> { }
        }
    }

    /**
     * Writes the strings that are not rebuilt with the list cells.
     *
     * <p>Also re-reads the profile: it is the same mutable object the instance
     * dialog edits, so a version or loader changed while this window was closed
     * is already visible here and must be shown, not remembered.
     */
    private void applyTexts() {
        stage.setTitle(I18n.t("content.title", profile.name()));
        sectionTitle.setText(I18n.t("content.sections"));
        // The cells draw their own names from I18n, so refreshing the list is
        // what makes a language change reach them.
        sectionList.refresh();
        modpacks.applyTexts();
        datapacks.applyTexts();
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

        packButton.getStyleClass().add("primary");
        // Disabled rather than absent, from the first frame. A control that
        // appears once the answer arrives makes the panel jump; one that is
        // simply missing on Forge tells the user nothing about why.
        packButton.setDisable(true);
        packButton.setOnAction(event -> togglePack());

        packNote.getStyleClass().add("muted");
        packNote.setWrapText(true);
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

    // The row itself is ContentRow: the same layout and the same sizing rules
    // for every list in this window, in every section. What is left here is the
    // two cells that are the mods section's own.

    /** A search hit: name, author and downloads, description, page, and one action. */
    private final class ResultCell extends ContentRow<ModProvider.SearchResult> {

        private final Button action = new Button();

        ResultCell() {
            super(ContentBrowserWindow.this::categories,
                    ContentBrowserWindow.this::highlightedCategories);
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
    private final class InstalledCell extends ContentRow<ModEntry> {

        private final Label badge = new Label();
        private final Button toggle = new Button();
        private final Button remove = new Button();

        /** One each, reused, rather than a new node per row the eye passes over. */
        private final javafx.scene.control.Tooltip badgeTip = new javafx.scene.control.Tooltip();
        private final javafx.scene.control.Tooltip removeTip = new javafx.scene.control.Tooltip();

        /** The names of the mods that need this one, shown while the badge is hovered. */
        private final HoverPanel needed = new HoverPanel();
        private java.util.List<ModEntry> neededShows = java.util.List.of();
        private boolean neededExplains;

        InstalledCell() {
            super(ContentBrowserWindow.this::categories,
                    ContentBrowserWindow.this::highlightedCategories);
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
            // A mod the launcher installed because something else asked for it
            // is worth hovering even when the answer is "nothing, any more":
            // that is the difference between a badge that says nothing and a
            // badge that says this one can go.
            boolean explains = !needs.isEmpty() || mod.origin() == ModOrigin.DEPENDENCY;
            styleClass(badge, "badge-linked", explains);
            if (!needs.equals(neededShows) || explains != neededExplains) {
                neededShows = needs;
                neededExplains = explains;
                fillNeeded(needs, explains);
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
        private void fillNeeded(java.util.List<ModEntry> needs, boolean explains) {
            needed.content().clear();
            if (!explains) {
                needed.hide();
                return;
            }
            if (needs.isEmpty()) {
                // Installed as somebody else's requirement, and nothing that is
                // in the folder now asks for it. Said plainly, because the badge
                // on its own reads as "something needs this" and the hover
                // showing nothing reads as a launcher that failed to answer.
                Label alone = new Label(I18n.t("mods.dependents.none"));
                alone.setWrapText(true);
                alone.setMaxWidth(280);
                alone.getStyleClass().add("muted");
                needed.content().add(alone);
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
        run(I18n.t("mods.task.search"), browseEmpty::setText, () -> {
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
        confirm.setTitle(header);
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
        // The button is greyed in this state, so this is the second lock rather
        // than the first - and the one that holds if a keyboard ever reaches it.
        if (packBlockedReason != null && !installed) {
            return;
        }
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
        packBlockedReason = I18n.t("mods.pack.checking");
        updatePackButton();

        // Vanilla is answered here rather than on the network. It is not that
        // the set has no build for this profile; it is that a profile with no
        // loader cannot load a mod at all, and one lookup would be one too many.
        if (profile.loader() == LoaderType.VANILLA) {
            packBlockedReason = I18n.t("mods.pack.noLoader");
            updatePackButton();
            return;
        }
        run(I18n.t("mods.task.packCheck"), browseEmpty::setText, () -> {
            ModPack loaded = ModPack.hexadronOptimise();
            ModInstaller.PackAvailability availability = service.packAvailability(profile, loaded);
            Platform.runLater(() -> {
                pack = loaded;
                packAvailable = availability.available();
                packBlockedReason = packAvailable ? null : reasonFor(loaded, availability);
                updatePackButton();
                progress.done(I18n.t("status.ready"));
            });
        });
    }

    /**
     * The sentence under a disabled button.
     *
     * <p>Two different facts, and a user can act on only one of them. A loader
     * the set was never written for will not change tomorrow and the answer is
     * to use a different loader; a Minecraft version the mods have not been
     * built for yet is a wait, and naming the ones that are missing is what
     * makes it a wait rather than a mystery.
     */
    private String reasonFor(ModPack loaded, ModInstaller.PackAvailability availability) {
        if (!availability.loaderSupported()) {
            return I18n.t("mods.pack.wrongLoader",
                    profile.loader().displayName(), String.join(", ", loaded.loaders()));
        }
        String note = I18n.t("mods.pack.unavailable",
                profile.minecraftVersion(), profile.loader().displayName());
        if (availability.missing().isEmpty()) {
            return note;
        }
        return note + " " + I18n.t("mods.pack.missing",
                String.join(", ", availability.missing()));
    }

    /**
     * Draws the pack button, and the sentence under it when it is off.
     *
     * <p>The button is always there. It used to disappear on a profile the set
     * has nothing for, which is the one case where the user most needs to be
     * told something: a control that is absent is indistinguishable from a
     * launcher that forgot to draw it, and the user's next move is to look for
     * it rather than to change the profile. So it stays, greyed, with the reason
     * beside it - and greyed is also what makes it unclickable, which is the
     * point: nothing here can install a set that will not run.
     *
     * <p>The one exception is a set that is already installed. Removing it must
     * stay possible even when it has become uninstallable - the mods are in the
     * folder either way, and a profile switched to a loader the set does not
     * cover would otherwise have no way to take them out again.
     */
    private void updatePackButton() {
        boolean installed = pack != null && library != null && library.isPackInstalled(pack.id());
        boolean blocked = packBlockedReason != null;

        packButton.setText(I18n.t(installed ? "mods.pack.remove" : "mods.pack.install"));
        packButton.getStyleClass().removeAll("primary", "danger");
        packButton.getStyleClass().add(installed ? "danger" : "primary");
        packButton.setDisable(busy || (blocked && !installed));
        packButton.setVisible(true);
        packButton.setManaged(true);

        packNote.setText(blocked ? packBlockedReason : "");
        packNote.setVisible(blocked);
        packNote.setManaged(blocked);
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Runs a mutating action - install, remove - one at a time.
     *
     * <p>Two of these at once would interleave writes to the same lock file and
     * the same folder, and the loser would leave a jar on disk that nothing
     * records. Searching is not affected: it changes nothing, so it is never
     * blocked by a download and never blocks one.
     */
    @Override
    public void mutate(String name, Task task) {
        if (busy) {
            progress.log(I18n.t("status.busy", name));
            return;
        }
        setBusy(true);
        run(name, null, () -> {
            try {
                task.run();
            } finally {
                Platform.runLater(() -> setBusy(false));
            }
        });
    }

    @Override
    public void run(String name, java.util.function.Consumer<String> onFailure, Task task) {
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
                    if (onFailure == null) {
                        warn(I18n.t("status.failed", name), detail);
                    } else {
                        onFailure.accept(detail);
                    }
                });
            }
        }, "hexadron-content");
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
                            result.skipped().stream().map(ContentBrowserWindow::reasonFor)
                                    .collect(java.util.stream.Collectors.joining("\n")));
                }
            });
        });
    }

    /**
     * One refused file, as a line somebody can act on.
     *
     * <p>Written here rather than where the refusal happened, because this is
     * where the language of the window is known. The folder reader has no
     * business holding sentences in five languages.
     */
    private static String reasonFor(com.hexadron.launcher.mods.ModScan.Skip skip) {
        return switch (skip.reason()) {
            case NOT_A_FILE -> I18n.t("mods.import.skipped.notFile", skip.file());
            case NOT_A_JAR -> I18n.t("mods.import.skipped.notJar", skip.file());
            case ALREADY_THERE -> I18n.t("mods.import.skipped.already", skip.file());
            case NOT_AN_ARCHIVE -> I18n.t("mods.import.skipped.notArchive", skip.file());
            case FAILED -> I18n.t("mods.import.skipped.failed", skip.file(),
                    skip.detail() == null ? "" : skip.detail());
        };
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
        modpacks.onBusyChanged();
        datapacks.onBusyChanged();
        // One place decides whether this button is clickable, and it is the one
        // that also knows the set is already installed and must stay removable.
        updatePackButton();
        identifyButton.setDisable(value);
        importButton.setDisable(value);
        resultList.refresh();
        installedList.refresh();
    }

    private void warnDialog(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.initOwner(stage);
        Theme.apply(alert.getDialogPane());
        alert.setHeaderText(header);
        alert.setTitle(header);
        alert.getDialogPane().setPrefWidth(560);
        alert.showAndWait();
    }

    // ---------------------------------------------------------------- the host

    // What the sections are lent. The window owns all of it, which is the point:
    // one status line, one progress bar, one rule about installs running one at a
    // time, and one place that raises a dialog.

    @Override
    public LauncherService service() {
        return service;
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public Profile profile() {
        return profile;
    }

    @Override
    public BrowserProgress progress() {
        return progress;
    }

    @Override
    public Categories categories() {
        return categories;
    }

    /**
     * The categories ticked in the mods filter.
     *
     * <p>Shared with the other sections on purpose. Nothing else offers the
     * filter - it is Modrinth's nineteen and it files mods - but a row anywhere
     * in the window puts a ticked category first, and a reader who has narrowed a
     * mod search to two things is scanning for those two wherever they appear.
     */
    @Override
    public java.util.Set<ModCategory> highlightedCategories() {
        return chosenCategories;
    }

    @Override
    public boolean isBusy() {
        return busy;
    }

    @Override
    public void warn(String header, String message) {
        warnDialog(header, message);
    }

    /**
     * Something in this instance changed.
     *
     * <p>Every section that changes anything calls it, and it refreshes all of
     * them rather than only the caller: installing a modpack writes jars into the
     * mods folder, so the mods list is stale the moment a pack lands.
     */
    @Override
    public void contentChanged() {
        refreshInstalled();
        modpacks.refresh();
        datapacks.refresh();
    }
}