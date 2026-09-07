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
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.mods.ModSort;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;

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
 * <p>The mods section is not built on this. It has a category filter, which is
 * Modrinth's nineteen and applies to nothing else, and folding that in would mean
 * a class carrying a filter that three quarters of its users must remember to
 * switch off.
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

        HBox controls = new HBox(8, searchField, sortBox, sourceBox, searchButton);
        controls.setAlignment(Pos.CENTER_LEFT);

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

        VBox box = new VBox(10, controls, curseForgeRow, blockedNote, resultList, moreButton);
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
        refreshCurseForgeState();
        refreshBlocked();
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
        int offset = fresh ? 0 : nextOffset;

        if (fresh) {
            empty.setText(I18n.t("mods.searching"));
        }
        moreButton.setDisable(true);

        // A search never pops a dialog. It is the one action the user repeats
        // constantly, and a modal error for a dropped connection would be in the
        // way of the retry.
        host.run(I18n.t("mods.task.search"), empty::setText, () -> {
            ModProvider.SearchPage page = host.service().searchContent(
                    kind, host.profile(), query, sort, List.of(), only, PAGE_SIZE, offset);
            Platform.runLater(() -> {
                if (fresh) {
                    results.setAll(page.results());
                } else {
                    results.addAll(page.results());
                }
                nextOffset = offset + PAGE_SIZE;
                totalMatches = page.total();
                empty.setText(I18n.t("mods.noResults"));

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
            super(host::categories, host::highlightedCategories);
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
