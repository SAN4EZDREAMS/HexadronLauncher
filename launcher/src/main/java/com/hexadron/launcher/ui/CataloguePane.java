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

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.mods.ContentKind;
import com.hexadron.launcher.mods.ModCategory;
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.mods.ModSort;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Set;

/**
 * A searchable catalogue of one kind of thing.
 *
 * <h2>Why it is one class and not one per section</h2>
 *
 * <p>Searching a platform is the same job whatever is being searched for: a box
 * to type in, an ordering, a platform filter, a page of rows, a total, and a
 * button for the next page. All of it was written once for mods, and the parts
 * that would have had to be written again for modpacks and for data packs are
 * exactly the parts that were worth getting right - the honest total ("showing 40
 * of 3812" rather than a silent 40), the line that says which platform did not
 * answer, and the notice that CurseForge is off with the one action that fixes
 * it.
 *
 * <p>What differs per kind is not the catalogue. It is what a row's button does,
 * and whether it can be pressed at all - a data pack needs a world to go into -
 * so that is what {@link Actions} is for and the only thing a section has to
 * supply.
 *
 * <p>The category filter is here rather than per section for the same reason.
 * Every kind has one - a different list each, which {@link ModCategory} knows -
 * so what used to be "the mods panel has a filter and the others do not" is now
 * one {@link CategoryFilter} told which kind it is narrowing.
 *
 * <p>The mods section is still not built on this: it has the pack button, the
 * identify pass and the installed-mods filter, none of which mean anything for a
 * modpack or a data pack. It shares the filter widget rather than the pane.
 */
final class CataloguePane {

    /** Results asked of each platform per search. Enough to scroll, small enough to stay quick. */
    private static final int PAGE_SIZE = 40;

    /** What the section decides about each row. */
    interface Actions {

        /** True when this instance already has it, which greys the button. */
        boolean isInstalled(ModProvider.SearchResult hit);

        /** The button was pressed. */
        void install(ModProvider.SearchResult hit);

        /**
         * Why nothing here can be installed right now, or null when it can.
         *
         * <p>Shown as a line above the list, and it also greys every button. The
         * data pack section has one answer for it - there is no world to install
         * into - and a row of live buttons that all fail is worse than a row of
         * grey ones with the reason written above them.
         */
        default String blockedReason() {
            return null;
        }
    }

    private final ContentSection.Host host;
    private final ContentKind kind;
    private final Actions hooks;

    private final TextField searchField = new TextField();
    private final ComboBox<ModSort> sortBox = new ComboBox<>();
    private final ComboBox<SourceChoice> sourceBox = new ComboBox<>();
    private final Button searchButton = new Button();

    /**
     * What the thing is for, as this platform files this kind.
     *
     * <p>Null only for a kind the platform files under nothing at all, which is
     * a case that does not exist today and is cheaper to allow for than to
     * assume away: a filter offering an empty menu is a control that cannot do
     * anything.
     */
    private final CategoryFilter categoryFilter;

    /**
     * The one narrowing question this kind puts to the user.
     *
     * <p>One box, not a box per kind, because each kind has exactly one such
     * question and it is a different question - which is why the label, the
     * sentence under the pointer and the starting state all come from
     * {@link ContentKind.Narrowing} rather than being written here. A modpack
     * asks "only the packs that fit this profile", ticked to begin with; a data
     * pack asks "without mods", unticked, because for a data pack the narrower
     * list is the default and the box widens it.
     *
     * <p>Null for a mod, which is always narrowed to the instance and therefore
     * has nothing to ask.
     */
    private final CheckBox narrowingBox;

    /** Says what the box means, in the one sentence that is worth its space. */
    private final Tooltip narrowingTip = new Tooltip();

    /**
     * Whether the box was last drawn stuck on.
     *
     * <p>Held so that leaving that state can put the box back where it started.
     * A data pack box is forced on for an instance with no loader, and forced is
     * not chosen: somebody who then sets the instance to Fabric has said nothing
     * about wanting the wider list, and leaving the box ticked would answer a
     * question they were never asked.
     */
    private boolean lockedOn;

    /**
     * Says out loud when CurseForge is not being searched.
     *
     * <p>Without it the catalogue quietly returns Modrinth results only, and a
     * user looking for something that is on CurseForge alone concludes it does
     * not exist. A launcher searching one platform has to say it is searching one
     * platform - and then offer the one action that fixes it.
     */
    private final Label curseForgeNote = new Label();
    private final Button curseForgeKeyButton = new Button();
    private final HBox curseForgeRow = new HBox(8, curseForgeNote, curseForgeKeyButton);

    /** Why nothing can be installed, when that is the case. */
    private final Label blockedNote = new Label();

    private final ObservableList<ModProvider.SearchResult> results =
            FXCollections.observableArrayList();
    private final ListView<ModProvider.SearchResult> resultList = new ListView<>(results);
    private final Label empty = new Label();
    private final Button moreButton = new Button();

    private final VBox pane;

    /** Where the next page starts, and how many matches the platforms report. */
    private int nextOffset;
    private int totalMatches = -1;

    CataloguePane(ContentSection.Host host, ContentKind kind, Actions actions) {
        this.host = host;
        this.kind = kind;
        this.hooks = actions;
        this.categoryFilter = kind.hasCategories()
                ? new CategoryFilter(kind, host::categories, this::search)
                : null;
        this.narrowingBox = kind.narrowing().isOffered() ? new CheckBox() : null;
        this.pane = build();
    }

    VBox node() {
        return pane;
    }

    private VBox build() {
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setOnAction(event -> search());

        sortBox.setItems(FXCollections.observableArrayList(ModSort.values()));
        sortBox.setValue(ModSort.POPULAR);
        sortBox.setPrefWidth(190);
        sortBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ModSort value) {
                return value == null ? "" : I18n.t(value.key());
            }

            @Override
            public ModSort fromString(String text) {
                return null;
            }
        });
        sortBox.valueProperty().addListener((observable, previous, value) -> search());

        sourceBox.setItems(FXCollections.observableArrayList(SourceChoice.values()));
        sourceBox.setValue(SourceChoice.ALL);
        sourceBox.setPrefWidth(150);
        sourceBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(SourceChoice value) {
                return value == null ? "" : value.label();
            }

            @Override
            public SourceChoice fromString(String text) {
                return null;
            }
        });
        sourceBox.valueProperty().addListener((observable, previous, value) -> search());

        searchButton.setOnAction(event -> search());

        HBox controls = new HBox(8, searchField, sortBox);
        if (categoryFilter != null) {
            controls.getChildren().add(categoryFilter.node());
        }
        controls.getChildren().addAll(sourceBox, searchButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        if (narrowingBox != null) {
            // Set before the listener is attached, so that a box which starts
            // out ticked - or is forced ticked because this instance has no
            // loader - does not fire a search from inside the constructor of a
            // window that has not been shown yet.
            lockedOn = isLockedOn();
            narrowingBox.setSelected(kind.narrowing().isChosenByDefault() || lockedOn);
            narrowingBox.setWrapText(true);
            narrowingBox.setTooltip(narrowingTip);
            // Straight into a fresh search rather than filtering the page in
            // hand. The narrowing is the platform's own - the request carries
            // the version and the loader - so a page fetched without it does not
            // contain the answer to the same question with it: it contains forty
            // of the wrong rows and a total that counts them.
            narrowingBox.selectedProperty().addListener(
                    (observable, previous, value) -> search());
        }

        curseForgeNote.getStyleClass().add("muted");
        curseForgeNote.setWrapText(true);
        HBox.setHgrow(curseForgeNote, Priority.ALWAYS);
        curseForgeKeyButton.setOnAction(event -> promptForCurseForgeKey());
        curseForgeRow.setAlignment(Pos.CENTER_LEFT);

        blockedNote.getStyleClass().add("muted");
        blockedNote.setWrapText(true);
        blockedNote.setVisible(false);
        blockedNote.setManaged(false);

        resultList.setCellFactory(view -> new HitCell());
        resultList.setPlaceholder(empty);
        VBox.setVgrow(resultList, Priority.ALWAYS);

        // Paging rather than a bigger page. A single huge request is slower to
        // first result and still has a ceiling; this one has none the user meets.
        moreButton.setMaxWidth(Double.MAX_VALUE);
        moreButton.setVisible(false);
        moreButton.setManaged(false);
        moreButton.setOnAction(event -> loadPage(false));

        VBox box = narrowingBox == null
                ? new VBox(10, controls, curseForgeRow, blockedNote, resultList, moreButton)
                : new VBox(10, controls, narrowingBox, curseForgeRow, blockedNote,
                        resultList, moreButton);
        box.getStyleClass().add("browse-pane");
        applyTexts();
        return box;
    }

    /** Re-reads every string, and the CurseForge state with them. */
    void applyTexts() {
        searchField.setPromptText(I18n.t("mods.search.prompt." + kind.name().toLowerCase(
                java.util.Locale.ROOT)));
        searchButton.setText(I18n.t("mods.search"));
        moreButton.setText(I18n.t("mods.more"));
        // The converters read I18n each time they are asked; nudging a box is
        // what makes it redraw after a language change.
        ModSort sort = sortBox.getValue();
        sortBox.setValue(null);
        sortBox.setValue(sort);
        SourceChoice source = sourceBox.getValue();
        sourceBox.setValue(null);
        sourceBox.setValue(source);
        if (narrowingBox != null) {
            narrowingBox.setText(I18n.t(kind.narrowing().key()));
            boolean locked = isLockedOn();
            // The profile's own pair is in the sentence, because "fits this
            // profile" is only checkable by somebody who knows what the profile
            // is set to - and that is exactly what a pack install changes.
            narrowingTip.setText(locked
                    ? I18n.t("datapacks.withoutMods.vanilla")
                    : I18n.t(kind.narrowing().tipKey(),
                            host.profile().minecraftVersion(),
                            host.profile().loader().displayName()));
            narrowingBox.setDisable(locked);
            // Forced on rather than merely greyed: an instance with no loader
            // can load nothing but a plain data pack, so the state the box is
            // stuck in has to be the state the search actually runs in. Set only
            // when it differs, because setting it fires a fresh search - which
            // is the right thing when a profile has just become vanilla, and
            // pointless churn otherwise.
            if (locked && !narrowingBox.isSelected()) {
                narrowingBox.setSelected(true);
            } else if (!locked && lockedOn) {
                // Out of the stuck state: back to the default rather than
                // wherever being stuck left it.
                narrowingBox.setSelected(kind.narrowing().isChosenByDefault());
            }
            lockedOn = locked;
        }
        // Rebuilt rather than relabelled: every name in it changes, and the
        // drawings beside them may have arrived since it was last built. The
        // ticks are kept - the filter holds them, not its boxes.
        if (categoryFilter != null) {
            categoryFilter.build();
        }
        refreshCurseForgeState();
        refreshBlocked();
    }

    /**
     * True when this kind's box cannot be unticked on this instance.
     *
     * <p>One case: data packs on an instance with no mod loader. The box means
     * "without mods", and without a loader there is nothing else on offer - a
     * list narrowed to a loader the instance has not got is an empty list, and
     * an empty list reads as "nothing has been published for your version".
     */
    private boolean isLockedOn() {
        return kind.narrowing() == ContentKind.Narrowing.WITHOUT_MODS
                && !host.profile().loader().isModded();
    }

    /**
     * The user's answer to this kind's narrowing question.
     *
     * <p>Read by the section as well as by the search: for data packs it decides
     * what an install downloads, not only what the list shows.
     */
    boolean narrowingChosen() {
        return narrowingBox == null
                ? kind.narrowing().isChosenByDefault()
                : narrowingBox.isSelected();
    }

    /** Starts a new search from the first page. */
    void search() {
        loadPage(true);
    }

    /** Repaints the rows: an install changes what their buttons say. */
    void refreshRows() {
        refreshBlocked();
        resultList.refresh();
    }

    /**
     * The categories ticked here, so a row can put those first.
     *
     * <p>This pane's own, not the window's. Somebody who has narrowed a modpack
     * search to "quests" is scanning modpack rows for "quests", and the ticks in
     * the mods panel are a different question they asked earlier.
     */
    Set<ModCategory> highlighted() {
        return categoryFilter == null ? Set.of() : categoryFilter.chosen();
    }

    /** Rebuilds the category menu, after fresh drawings arrived. */
    void refreshCategoryArt() {
        if (categoryFilter != null) {
            categoryFilter.build();
        }
    }

    /** Writes the "nothing can be installed" line, and greys the buttons with it. */
    private void refreshBlocked() {
        String reason = hooks.blockedReason();
        blockedNote.setText(reason == null ? "" : reason);
        blockedNote.setVisible(reason != null);
        blockedNote.setManaged(reason != null);
    }

    private void loadPage(boolean fresh) {
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        ModSort sort = sortBox.getValue() == null ? ModSort.POPULAR : sortBox.getValue();
        ModProvider.Source only = sourceBox.getValue() == null ? null : sourceBox.getValue().source();
        List<ModCategory> categoriesChosen =
                categoryFilter == null ? List.of() : categoryFilter.forSearch();
        boolean chosen = narrowingChosen();
        int offset = fresh ? 0 : nextOffset;

        if (fresh) {
            empty.setText(I18n.t("mods.searching"));
        }
        // The placeholder after an empty answer depends on the box: "nothing
        // matches" is a different sentence from "nothing that fits this profile
        // matches", and the second one names the thing to tick or untick.
        String nothing = kind.narrowing().emptyKey(chosen);
        moreButton.setDisable(true);

        // A search never pops a dialog. It is the one action the user repeats
        // constantly, and a modal error for a dropped connection would be in the
        // way of the retry.
        host.run(I18n.t("mods.task.search"), empty::setText, () -> {
            ModProvider.SearchPage page = host.service().searchContent(
                    kind, host.profile(), query, sort, categoriesChosen, chosen, only,
                    PAGE_SIZE, offset);
            Platform.runLater(() -> {
                if (fresh) {
                    results.setAll(page.results());
                } else {
                    results.addAll(page.results());
                }
                nextOffset = offset + PAGE_SIZE;
                totalMatches = page.total();
                empty.setText(I18n.t(nothing));

                boolean more = page.hasMore() && !page.results().isEmpty();
                moreButton.setVisible(more);
                moreButton.setManaged(more);
                moreButton.setDisable(false);
                moreButton.setText(I18n.t("mods.more"));

                String found = totalMatches >= 0
                        ? I18n.t("mods.foundOf", results.size(), totalMatches)
                        : I18n.t("mods.found", results.size());
                if (page.isPartial()) {
                    // A shorter list with no explanation reads as "that does not
                    // exist for this version". Say which platform is missing.
                    host.progress().failed(found + "  ·  "
                            + I18n.t("mods.searchPartial", String.join("; ", page.unavailable())));
                } else {
                    host.progress().done(found);
                }
            });
        });
    }

    /** Shows or hides the CurseForge notice, depending on whether it has a key. */
    private void refreshCurseForgeState() {
        boolean available = host.service().curseForge().isAvailable();
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
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(
                host.service().settings().curseForgeApiKey());
        dialog.initOwner(host.stage());
        Theme.apply(dialog.getDialogPane());
        dialog.setTitle(I18n.t("mods.curseforge.key.header"));
        dialog.setHeaderText(I18n.t("mods.curseforge.key.header"));
        dialog.setContentText(I18n.t("mods.curseforge.key.body"));
        dialog.getDialogPane().setPrefWidth(620);
        dialog.getEditor().setPrefColumnCount(48);

        dialog.showAndWait().ifPresent(value -> {
            try {
                host.service().curseForgeApiKey(value);
            } catch (java.io.IOException e) {
                host.warn(I18n.t("mods.curseforge.key.header"),
                        e.getMessage() == null ? e.toString() : e.getMessage());
                return;
            }
            refreshCurseForgeState();
            if (host.service().curseForge().isAvailable()) {
                host.progress().done(I18n.t("mods.curseforge.key.saved"));
                search();
            }
        });
    }

    /** A search hit: name, author and downloads, description, page, and one action. */
    private final class HitCell extends ContentRow<ModProvider.SearchResult> {

        private final Button action = new Button();

        HitCell() {
            super(host::categories, CataloguePane.this::highlighted);
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
            // The catalogue needs the link at least as much as the installed list
            // does: this is where the user is deciding whether they want the
            // thing at all, and that decision is made on its own page.
            link(hit.pageUrl(), () -> openPage(hit.title(), hit.pageUrl()));

            boolean installed = hooks.isInstalled(hit);
            action.setText(I18n.t(installed ? "mods.installed" : "mods.install"));
            action.setDisable(installed || host.isBusy() || hooks.blockedReason() != null);
            // ".primary" carries a font size and a padding, so churning it on
            // every row is a button that changes width a frame late.
            styleClass(action, "primary", !installed);
            action.setOnAction(event -> hooks.install(hit));
            showRow();
        }
    }

    /** Opens a project's page in the user's own browser. */
    private void openPage(String title, String url) {
        if (SystemBrowser.open(url)) {
            host.progress().done(I18n.t("mods.details.opened", title));
            return;
        }
        // The address is shown rather than swallowed: on a session with no
        // desktop integration, copying it is the whole of the workaround.
        host.warn(I18n.t("mods.details"), I18n.t("mods.details.failed", url));
    }
}
