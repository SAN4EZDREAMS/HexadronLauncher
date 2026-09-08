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

    /**
     * What to call when a profile itself changed, or a new one exists.
     *
     * <p>Separate from {@link #onChanged} because the two are different jobs at
     * the other end: one re-counts the mods in the panel, the other re-reads the
     * profiles and redraws the list, the grid and the panel. Doing the second on
     * every mod switched off would rebuild both interfaces for a number that did
     * not change; doing the first after a modpack install left the launcher
     * showing the version the profile used to be on.
     */
    private final Runnable onProfileChanged;

    private final Stage stage = new Stage();

    private final Label titleLabel = new Label();
    private final Label subtitleLabel = new Label();
    private final Button packButton = new Button();
    private final Label packNote = new Label();

    private final TextField searchField = new TextField();
    private final ComboBox<ModSort> sortBox = new ComboBox<>();

    /**
     * The category filter for mods.
     *
     * <p>The widget is shared with the other two sections' catalogues - each is
     * told which kind it narrows, because the platform files each kind under its
     * own list - so what it is and why it is a menu of tick boxes rather than a
     * list that picks one lives in {@link CategoryFilter}.
     *
     * <p>Built in {@link #buildBrowsePane()} rather than here: it draws a
     * picture beside every name, and the pictures are read from disk in
     * {@link #buildStage()}.
     */
    private CategoryFilter categoryFilter;

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

    /**
     * The modpacks this instance has, by the id a mod row records.
     *
     * <p>Read with the mods list, because it is what a row needs to tell the two
     * meanings of {@link ModOrigin#PACK} apart: a jar out of the launcher's own
     * set, and a jar out of a modpack somebody installed. Both are "a pack owns
     * this", and only one of them is Hexadron Optimise.
     */
    private java.util.Map<String, com.hexadron.launcher.mods.InstalledModpack> modpacksById =
            java.util.Map.of();

    private final Tab browseTab = new Tab();
    private final Tab installedTab = new Tab();
    private final TabPane tabs = new TabPane(browseTab, installedTab);

    /**
     * The kinds of thing this window covers, in the order they are offered.
     *
     * <p>Mods first because it is what almost every visit is for. Then the
     * modpack, which decides a whole instance. Then the three that change one
     * part of one: what the world runs on, what it looks like, and how it is
     * lit. Data packs before the two look-and-feel kinds because a data pack
     * changes the game rather than the picture of it.
     */
    private enum Section {
        MODS("mods.kind.mod"),
        MODPACKS("mods.kind.modpack"),
        DATAPACKS("mods.kind.datapack"),
        RESOURCEPACKS("mods.kind.resourcepack"),
        SHADERS("mods.kind.shader");

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
                case RESOURCEPACKS -> Glyphs.palette();
                case SHADERS -> Glyphs.sun();
            };
        }
    }

    /**
     * The width of the rail with nothing but its icons on it.
     *
     * <p>Wide enough for a sixteen-pixel glyph with the same padding a button
     * has, and no wider: this is the state it is in almost all the time, and
     * every pixel of it is taken from the lists.
     */
    private static final double RAIL_WIDTH = 52;

    /**
     * The rail down the left, and the row for each kind.
     *
     * <p>Buttons in a box rather than a {@link ListView}, and that is not a
     * stylistic preference. The rail has to be exactly as wide as its widest
     * <em>translated</em> name when it opens - "Data packs", "Датапаки" and
     * "Datenpakete" are three different widths - and a box of buttons computes
     * that for itself from the text in them, while a list view reports a width of
     * its own that has nothing to do with its rows. Three rows is also not a list
     * worth a list's machinery: no scrolling, no selection model, no cell reuse.
     */
    private final VBox rail = new VBox(4);
    private final java.util.Map<Section, javafx.scene.control.Button> sectionRows =
            new java.util.EnumMap<>(Section.class);

    /** Which kind is showing. Held because the rail draws the answer on every row. */
    private Section current = Section.MODS;

    /** The panel for each kind. Mods is this class's own; the rest are their own. */
    private final javafx.scene.layout.StackPane sectionPane = new javafx.scene.layout.StackPane();
    private ModpackSection modpacks;
    private DatapackSection datapacks;
    private PackSection resourcePacks;
    private PackSection shaders;
    private javafx.scene.Node modsPane;

    /**
     * Every section but the mods one, in the order the rail offers them.
     *
     * <p>Built once and then iterated, because almost everything this window
     * does to a section it does to all of them: re-read the folder, re-read the
     * strings, tell them an install started, tell them the profile changed. A
     * list is one line per such job; five named fields is five lines each, and
     * the section somebody forgets to add is the one that shows stale rows.
     */
    private java.util.List<ContentSection> sections = java.util.List.of();

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

    public ContentBrowserWindow(LauncherService service, Stage owner, Profile profile,
                                Runnable onChanged, Runnable onProfileChanged) {
        this.service = service;
        this.owner = owner;
        this.profile = profile;
        this.onChanged = onChanged;
        this.onProfileChanged = onProfileChanged;
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
        sections.forEach(ContentSection::refresh);
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
        resourcePacks = new PackSection(this, com.hexadron.launcher.mods.ContentKind.RESOURCEPACK);
        shaders = new PackSection(this, com.hexadron.launcher.mods.ContentKind.SHADER);
        sections = java.util.List.of(modpacks, datapacks, resourcePacks, shaders);
        modsPane = buildTabs();

        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildBody());
        root.setBottom(buildFooter());

        Scene scene = new Scene(root, 1080, 720);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(540);
    }

    /**
     * The rail and the panels, with the rail over the panels rather than beside
     * them.
     *
     * <h2>Why it is an overlay</h2>
     *
     * <p>The rail is a column of three icons that grows into a column of three
     * names when the pointer is on it. If it took part in the layout, opening it
     * would push every list in the window a hundred pixels to the right and
     * closing it would pull them back - so a pointer crossing the left edge on
     * its way to a Remove button would make the row it was aiming at move. The
     * panels therefore keep a fixed {@link #RAIL_WIDTH} of space on their left
     * for ever, and the open rail is drawn over the top of them. Nothing reflows,
     * and what the rail covers while it is open is the part of the window the
     * user is not reading.
     *
     * <p>It is inside the centre rather than the whole window's left so that the
     * header and the status line still run the full width. The instance's name
     * belongs to the window, not to the panel next to the rail.
     */
    private javafx.scene.layout.StackPane buildBody() {
        sectionPane.getChildren().setAll(modsPane, modpacks.node(), datapacks.node(),
                resourcePacks.node(), shaders.node());
        sectionPane.setPadding(new javafx.geometry.Insets(0, 0, 0, RAIL_WIDTH));

        javafx.scene.layout.StackPane body =
                new javafx.scene.layout.StackPane(sectionPane, buildRail());
        javafx.scene.layout.StackPane.setAlignment(rail, Pos.TOP_LEFT);
        showSection(Section.MODS);
        return body;
    }

    /**
     * The rail: one row per kind, icons only until the pointer arrives.
     *
     * <p>Every kind is always on it, including the ones this profile cannot use.
     * A row that disappeared on a profile with no loader would leave the user
     * looking for it; a row that is there and says why is a row that answers the
     * question. What each section does about not being usable is its own - the
     * mods one has always said so in place of its results.
     *
     * <p>Each row keeps a tooltip whether the rail is open or shut. Shut, it is
     * the only way to find out what a glyph means without opening the rail;
     * open, it costs nothing and is not in the way.
     */
    private VBox buildRail() {
        for (Section section : Section.values()) {
            javafx.scene.control.Button row = new javafx.scene.control.Button();
            row.setGraphic(section.glyph());
            row.setGraphicTextGap(10);
            row.setMaxWidth(Double.MAX_VALUE);
            // Never narrower than its own contents: a row squeezed into the rail
            // is not a name with less space around it, it is a name with its last
            // two letters replaced by an ellipsis.
            row.setMinWidth(Region.USE_PREF_SIZE);
            row.getStyleClass().add("kind-row");
            row.setTooltip(new javafx.scene.control.Tooltip());
            row.setOnAction(event -> showSection(section));
            // Reached by keyboard as well as by pointer. Tabbing onto a row of
            // icons with no names is a rail that only works for a mouse.
            //
            // Keyboard focus, not focus. This is the whole of the "the rail
            // stays open after I have chosen a section" bug: clicking a row
            // gives it the focus, so the pointer leaving found a row still
            // focused and kept the rail open until something else was clicked -
            // which is the one thing a pointer that has left is not going to do.
            // isFocusVisible() is true only while the focus arrived by keyboard
            // and should therefore be shown, which is exactly the case the rail
            // has to stay open for.
            //
            // Losing focus is not the same question as gaining it. Tabbing from
            // one row to the next takes focus off the first, and shutting the
            // rail on that would shut it under the row that just took focus - so
            // the way out asks whether anything is still holding it open.
            row.focusedProperty().addListener((observable, previous, focused) ->
                    setRailOpen(rail.isHover() || anyRowFocused()));
            row.focusVisibleProperty().addListener((observable, previous, visible) -> {
                if (visible) {
                    pointerOnRail = false;
                }
                setRailOpen(visible || rail.isHover() || anyRowFocused());
            });
            // And the belt to that pair of braces. A row clicked while the
            // keyboard was already on the rail keeps the focus it had, so the
            // property above never changes and nothing tells the rail that the
            // pointer has taken over. A press is that signal, and it is read as
            // a filter so a row that consumes the click cannot hide it.
            row.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                    event -> pointerOnRail = true);
            sectionRows.put(section, row);
        }

        sectionTitle.getStyleClass().add("section-title");
        rail.getChildren().setAll(sectionTitle);
        rail.getChildren().addAll(sectionRows.values());
        // Not ".sidebar" as well: that class carries a padding of its own, and
        // two rules setting the same property is a width that depends on which
        // of them the stylesheet happens to list second. The rail's shut width
        // is 52 and its padding is part of that arithmetic, so it owns it.
        rail.getStyleClass().add("kind-rail");
        rail.setFillWidth(true);
        // Full height, and only as wide as its state asks for. Without the max
        // height a box aligned to the top of a stack pane is as tall as its
        // three rows, and the panel's own background shows under it.
        rail.setMaxHeight(Double.MAX_VALUE);
        rail.setOnMouseEntered(event -> setRailOpen(true));
        rail.setOnMouseExited(event -> setRailOpen(anyRowFocused()));
        setRailOpen(false);
        return rail;
    }

    /**
     * True while the <em>keyboard</em> is on one of the rail's rows.
     *
     * <p>Not {@code isFocused}: a row clicked with the mouse is focused too, and
     * an open rail that waits for that focus to go away waits for a click
     * somewhere else. {@code isFocusVisible} is the platform's own answer to
     * "should this look focused", which is false for a mouse press and true for
     * keyboard traversal - the only case the rail must stay open for once the
     * pointer has gone.
     */
    private boolean anyRowFocused() {
        return !pointerOnRail
                && sectionRows.values().stream().anyMatch(javafx.scene.Node::isFocusVisible);
    }

    /**
     * True once the pointer has pressed a row, until the keyboard takes the
     * rail back.
     *
     * <p>The rail is opened by the pointer and by the keyboard, and only one of
     * them can be asked to close it: a pointer that has left the window is not
     * going to press anything else, so a rail held open by a focus the pointer
     * put there stays open for ever.
     */
    private boolean pointerOnRail;

    /** The name of each kind, beside the rail's icons. */
    private final Label sectionTitle = new Label();

    /**
     * Whether the rail is showing its names, or null before it has been set.
     *
     * <p>A {@code Boolean} rather than a {@code boolean} so that the first call
     * is never mistaken for a repeat. The pointer entering and leaving asks this
     * far more often than the answer changes, and setting three content displays
     * and three widths to the values they already hold is a layout pass for
     * nothing.
     */
    private Boolean railOpen;

    /**
     * Opens or shuts the rail.
     *
     * <p>Opening is a change of what each row displays, not a change of width
     * that has to be worked out: switching a row from {@code GRAPHIC_ONLY} to
     * {@code LEFT} makes it ask for the space its name needs, and the box asks
     * for the widest of the three. That is what makes it right in five languages
     * without anything measuring a string.
     *
     * <p>Shut, the width is pinned to {@link #RAIL_WIDTH} instead, because a
     * column of three icons would otherwise be as wide as the widest icon plus
     * padding - which is right by accident on one theme and wrong on the next.
     *
     * <p>No animation. The panels do not move either way, so there is nothing for
     * the eye to follow from one place to another; a width that slides would only
     * put the names behind a wait.
     */
    private void setRailOpen(boolean open) {
        if (Boolean.valueOf(open).equals(railOpen)) {
            return;
        }
        railOpen = open;

        sectionTitle.setVisible(open);
        sectionTitle.setManaged(open);
        for (javafx.scene.control.Button row : sectionRows.values()) {
            row.setContentDisplay(open
                    ? javafx.scene.control.ContentDisplay.LEFT
                    : javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            // Centred while it is a lone icon, so the column of three reads as a
            // column; left with the text, so the names line up under each other.
            row.setAlignment(open ? Pos.CENTER_LEFT : Pos.CENTER);
        }
        // Only the preferred width changes; the other two follow it for ever.
        //
        // This is the part that has to be right rather than plausible. A stack
        // pane stretches a child to fill itself unless the child's *maximum*
        // says otherwise, and USE_COMPUTED_SIZE as a maximum computes to
        // "unbounded" for a box - so a rail set that way opens across the whole
        // window instead of to the width of its widest name. USE_PREF_SIZE is
        // the one that means "no wider than you asked for".
        rail.setPrefWidth(open ? Region.USE_COMPUTED_SIZE : RAIL_WIDTH);
        rail.setMinWidth(Region.USE_PREF_SIZE);
        rail.setMaxWidth(Region.USE_PREF_SIZE);
        // The shadow is what says the open rail is over the panel rather than
        // part of it. Shut, it is flush with the edge and has nothing to cast on.
        ContentRow.styleClass(rail, "kind-rail-open", open);
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
        // Pressing the row that is already showing must not start its search
        // again. The rail is three buttons and the one under the pointer is the
        // one most likely to be pressed twice.
        boolean changed = chosen != current;
        current = chosen;
        sectionRows.forEach((kind, row) ->
                ContentRow.styleClass(row, "kind-row-on", kind == chosen));
        javafx.scene.Node[] panes = {modsPane, modpacks.node(), datapacks.node(),
                resourcePacks.node(), shaders.node()};
        Section[] order = {Section.MODS, Section.MODPACKS, Section.DATAPACKS,
                Section.RESOURCEPACKS, Section.SHADERS};
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

        if (!built || !changed) {
            return;
        }
        switch (chosen) {
            case MODPACKS -> modpacks.onShown();
            case DATAPACKS -> datapacks.onShown();
            case RESOURCEPACKS -> resourcePacks.onShown();
            case SHADERS -> shaders.onShown();
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
        // The rows carry their names as text rather than drawing them, so a
        // language change is written here - and the rail's open width follows,
        // because it is that text the box measures.
        sectionRows.forEach((kind, row) -> {
            row.setText(kind.title());
            row.getTooltip().setText(kind.title());
        });
        sections.forEach(ContentSection::applyTexts);
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
        // Rebuilt rather than relabelled: every name in the menu changes with
        // the language. The ticks survive - the filter holds them, not its boxes.
        if (categoryFilter != null) {
            categoryFilter.build();
        }
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

        categoryFilter = new CategoryFilter(
                com.hexadron.launcher.mods.ContentKind.MOD, this::categories, this::runSearch);

        searchButton.setOnAction(event -> runSearch());

        HBox controls = new HBox(8, searchField, sortBox, categoryFilter.node(),
                sourceBox, searchButton);
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

        /** What the badge says when it is hovered: the pack, the dependents, or both. */
        private final HoverPanel needed = new HoverPanel();
        private java.util.List<ModEntry> neededShows = java.util.List.of();
        private boolean neededExplains;

        /** The modpack the panel was last built for, so an unchanged row is left alone. */
        private String neededPack;

        /** The data pack it was last built for, for the same reason. */
        private String neededDatapack;

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

            // Which pack owns it, when one does and it is a modpack rather than
            // the launcher's own set. This is what stops a jar out of a
            // downloaded pack from claiming to be Hexadron Optimise.
            com.hexadron.launcher.mods.InstalledModpack owner = modpackOf(mod);

            // And which data pack needed it, for the jars a data pack brought
            // with it. The same question one folder over: a data pack lives in a
            // world, so the answer is not in this list and the row has to carry
            // it.
            com.hexadron.launcher.mods.DatapackOwner datapack = datapackOf(mod);

            badge.setText(ModLabels.badge(mod, owner != null));
            boolean live = mod.enabled() && !mod.isWrongVersion();
            styleClass(badge, "badge-off", !mod.enabled());
            styleClass(badge, "badge-wrong", mod.enabled() && mod.isWrongVersion());
            styleClass(badge, "badge-modpack", live && owner != null);
            styleClass(badge, "badge-pack",
                    live && owner == null && mod.origin() == ModOrigin.PACK);
            styleClass(badge, "badge-dependency", live && mod.origin() == ModOrigin.DEPENDENCY);
            styleClass(badge, "badge-datapack", live && mod.origin() == ModOrigin.DATAPACK);

            // What would break if this one went away. Rebuilt only when the
            // answer differs from the row this cell drew last.
            java.util.List<ModEntry> needs = dependents.of(mod);
            String packId = owner == null ? null : owner.id();
            // A mod the launcher installed because something else asked for it
            // is worth hovering even when the answer is "nothing, any more":
            // that is the difference between a badge that says nothing and a
            // badge that says this one can go. A mod out of a modpack always is:
            // "which pack is this" is the question the badge raises.
            String datapackKey = datapack == null ? null : datapack.key();
            boolean explains = !needs.isEmpty()
                    || mod.origin() == ModOrigin.DEPENDENCY
                    || owner != null
                    || datapack != null;
            styleClass(badge, "badge-linked", explains);
            if (!needs.equals(neededShows) || explains != neededExplains
                    || !java.util.Objects.equals(packId, neededPack)
                    || !java.util.Objects.equals(datapackKey, neededDatapack)) {
                neededShows = needs;
                neededExplains = explains;
                neededPack = packId;
                neededDatapack = datapackKey;
                fillNeeded(owner, datapack, needs, explains);
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
            // A pack goes out whole, through its own button in the header; a
            // data pack's mod goes out with the data pack, which is a button in
            // another section - so the two locked rows say different things.
            remove.setDisable(!mod.isRemovable() || busy);
            tooltip(remove, removeTip, mod.isRemovable() ? null
                    : I18n.t(mod.origin() == ModOrigin.DATAPACK
                            ? "mods.remove.datapackLocked" : "mods.remove.packLocked"));
            remove.setOnAction(event -> removeMod(mod));
            showRow();
        }

        /**
         * Fills the panel behind the badge: which pack this came from, and what
         * needs it.
         *
         * <p>Names that can be pressed, not a sentence listing them. The reader
         * hovering a dependency is asking "what is this here for", and the
         * useful next step is the mod that put it there - so each name takes
         * them to that row, the way an anchor on a page does. A mod out of a
         * modpack is the same question one level up, and the answer is the same
         * shape: the pack's name, and pressing it goes to the pack.
         */
        private void fillNeeded(com.hexadron.launcher.mods.InstalledModpack owner,
                                com.hexadron.launcher.mods.DatapackOwner datapack,
                                java.util.List<ModEntry> needs, boolean explains) {
            needed.content().clear();
            if (!explains) {
                needed.hide();
                return;
            }
            if (datapack != null) {
                Label title = new Label(I18n.t("mods.datapack.title"));
                title.getStyleClass().add("hover-title");
                needed.content().add(title);

                Hyperlink link = new Hyperlink(datapack.label());
                link.getStyleClass().add("hover-link");
                link.setOnAction(event -> {
                    needed.hide();
                    jumpToDatapack(datapack);
                });
                needed.content().add(link);

                Label hint = new Label(I18n.t("mods.datapack.hint"));
                hint.setWrapText(true);
                hint.setMaxWidth(280);
                hint.getStyleClass().add("muted");
                needed.content().add(hint);
            }
            if (owner != null) {
                Label title = new Label(I18n.t("mods.modpack.title"));
                title.getStyleClass().add("hover-title");
                needed.content().add(title);

                Hyperlink link = new Hyperlink(nameOf(owner));
                link.getStyleClass().add("hover-link");
                link.setOnAction(event -> {
                    needed.hide();
                    jumpToModpack(owner);
                });
                needed.content().add(link);

                Label hint = new Label(I18n.t("mods.modpack.hint"));
                hint.setWrapText(true);
                hint.setMaxWidth(280);
                hint.getStyleClass().add("muted");
                needed.content().add(hint);
            }
            if (needs.isEmpty()) {
                if (owner == null && datapack == null) {
                    // Installed as somebody else's requirement, and nothing that
                    // is in the folder now asks for it. Said plainly, because the
                    // badge on its own reads as "something needs this" and the
                    // hover showing nothing reads as a launcher that failed to
                    // answer.
                    Label alone = new Label(I18n.t("mods.dependents.none"));
                    alone.setWrapText(true);
                    alone.setMaxWidth(280);
                    alone.getStyleClass().add("muted");
                    needed.content().add(alone);
                }
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
        java.util.List<ModCategory> chosen = categoryFilter.forSearch();
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
    /**
     * The modpack that owns this mod, or null.
     *
     * <p>Null for a mod nothing owns, for one out of the launcher's own set -
     * whose {@code packId} is that set's id and is in no modpack record - and
     * for one whose pack has since been removed. All three are the same answer
     * to the row: there is no pack to send the reader to.
     */
    private com.hexadron.launcher.mods.InstalledModpack modpackOf(ModEntry mod) {
        if (mod.origin() != ModOrigin.PACK || mod.packId() == null) {
            return null;
        }
        return modpacksById.get(mod.packId());
    }

    /**
     * Which data pack a mod was installed for, when one was.
     *
     * <p>Out of the lock file rather than out of the row, because that is where
     * it is written: the world and the pack's key are recorded against the mod
     * at install time precisely so that this question can be answered from the
     * instance's own folder, with no connection and without reading a lock file
     * out of every world the instance has.
     */
    private com.hexadron.launcher.mods.DatapackOwner datapackOf(ModEntry mod) {
        if (mod.origin() != ModOrigin.DATAPACK || library == null) {
            return null;
        }
        return library.get(mod.key())
                .map(com.hexadron.launcher.mods.InstalledMod::datapack)
                .orElse(null);
    }

    /** What to call a pack in a link. Its own name, or its id when it has none. */
    private static String nameOf(com.hexadron.launcher.mods.InstalledModpack pack) {
        return pack.name() == null || pack.name().isBlank() ? pack.id() : pack.name();
    }

    /**
     * Goes from a mod to the pack it came from.
     *
     * <p>The same move the dependency panel makes between two mods, one level
     * up: the section changes, the Installed tab is chosen, and the pack's row
     * is selected - so the answer to "which pack is this jar from" is the row
     * itself rather than a name to go and look for.
     */
    private void jumpToModpack(com.hexadron.launcher.mods.InstalledModpack pack) {
        showSection(Section.MODPACKS);
        modpacks.reveal(pack.id());
    }

    /**
     * Goes from a mod to the data pack that needed it.
     *
     * <p>The same move as {@link #jumpToModpack}, one section over, and it
     * carries a world as well as a key: a data pack's row does not exist until
     * its world is the one on screen.
     */
    private void jumpToDatapack(com.hexadron.launcher.mods.DatapackOwner owner) {
        showSection(Section.DATAPACKS);
        datapacks.reveal(owner.world(), owner.key());
    }

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
        // Read here rather than asked for per row: a row is drawn on every
        // repaint and every scroll, and the answer is a file in the instance
        // folder. Read with the list it describes, so the two never disagree.
        java.util.Map<String, com.hexadron.launcher.mods.InstalledModpack> packs =
                new java.util.LinkedHashMap<>();
        service.modpacksIn(profile).forEach(pack -> packs.put(pack.id(), pack));
        modpacksById = java.util.Map.copyOf(packs);
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
                        categoryFilter.build();
                        // The other sections draw the same pictures in their
                        // own menus and rows, and they arrived for all of them.
                        modpacks.refreshCategoryArt();
                        datapacks.refreshCategoryArt();
                        resourcePacks.refreshCategoryArt();
                        shaders.refreshCategoryArt();
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
        sections.forEach(ContentSection::onBusyChanged);
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
     * <p>The mods panel's own, and only its rows read it now: each catalogue has
     * a filter of its own, because each kind is filed under its own list, and a
     * modpack row putting a mod category first would be answering a question
     * nobody asked in that list. Still on the interface because a section that
     * shows mod rows - and a future one might - needs somewhere to get it.
     */
    @Override
    public java.util.Set<ModCategory> highlightedCategories() {
        return categoryFilter == null ? java.util.Set.of() : categoryFilter.chosen();
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
        sections.forEach(ContentSection::refresh);
    }

    /**
     * Takes the user to the mods panel with a search already run.
     *
     * <p>Used by the shaders panel, whose answer to "nothing here will load" is
     * a mod. Typed into the field rather than searched behind the scenes, so
     * that what happened is visible and the word can be changed.
     */
    @Override
    public void searchInMods(String query) {
        showSection(Section.MODS);
        tabs.getSelectionModel().select(browseTab);
        searchField.setText(query == null ? "" : query);
        searchField.requestFocus();
        searchField.end();
        runSearch();
    }

    /**
     * A profile changed under us, or a new one was made.
     *
     * <p>Three things follow, and the first two are this window's own. Its
     * header names the profile's Minecraft version and loader, and a pack
     * installed into this profile has just changed both. Its modpack catalogue
     * can be narrowed to that same pair, so the list on screen was chosen
     * against the version the profile used to be on - and the tooltip that says
     * which pair still named it. Then the launcher is told, because its list,
     * its grid and its panel all describe profiles.
     */
    @Override
    public void profileChanged() {
        String line = profile.minecraftVersion() + "  ·  " + profile.loader().displayName();
        // Whether this window's own profile changed, or somebody else's did.
        // Installing a pack into a new profile is the second: the launcher has a
        // profile to draw and this window has nothing to redo, and a search
        // restarted for it would be a request for an answer already on screen.
        boolean here = !line.equals(subtitleLabel.getText());
        subtitleLabel.setText(line);
        titleLabel.setText(profile.name());
        if (here) {
            // Every list in this window was chosen against the pair that has
            // just been replaced: the mod catalogue asked the platforms for that
            // version and that loader, and the pack button asked whether the
            // launcher's own set can be installed on it.
            runSearch();
            loadPackStateAsync();
            // The other sections asked their platforms about the same pair.
            sections.forEach(ContentSection::onProfileChanged);
        }
        if (onProfileChanged != null) {
            onProfileChanged.run();
        }
    }
}