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
import com.hexadron.launcher.mods.InstalledMod;
import com.hexadron.launcher.mods.ModEntry;
import com.hexadron.launcher.mods.ModOrigin;
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.mods.ModScan;
import com.hexadron.launcher.mods.PackInstaller;
import com.hexadron.launcher.mods.PackScan;
import com.hexadron.launcher.mods.ShaderLoaders;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.util.List;
import java.util.Locale;

/**
 * Resource packs, or shaders: whichever kind this instance of the section was
 * built for.
 *
 * <h2>Why one class for two sections</h2>
 *
 * <p>Because they are the same section. Both kinds live in one folder of the
 * instance, one file per pack; both are a zip or an unpacked folder; both are
 * switched off by renaming; both are searched, filtered, installed, imported and
 * removed the same way. What differs is three strings and one warning, and a
 * second copy of six hundred lines to hold those is a second copy that drifts.
 *
 * <p>Every sentence therefore comes from a key built from the kind - see
 * {@link #key} - rather than from a switch at each use. A third kind of pack
 * folder would be a {@link ContentKind} value, a reader, and a block of
 * translations; not another panel.
 *
 * <h2>The one thing that is not shared</h2>
 *
 * <p>A shader pack needs a program to load it: Iris, OptiFine or Canvas, none of
 * which is Minecraft and all of which are mods. An instance without one has a
 * {@code shaderpacks} folder the game never opens, so the panel says so and
 * offers the search that fixes it. It does not install anything by itself - that
 * is a jar in the player's mods folder, and it is their choice which one - and
 * it does not refuse the install either: putting the pack there and telling them
 * what is missing is more useful than a greyed-out button.
 */
final class PackSection extends ContentSection {

    private final ContentKind kind;
    private final PackScan scan;
    private final CataloguePane catalogue;

    private final ListView<ModEntry> installedList = new ListView<>();
    private final Label installedEmpty = new Label();
    private final Label installedCount = new Label();
    private final TextField installedSearch = new TextField();
    private final Button importButton = new Button();
    private final Label kindNote = new Label();

    /** For shaders: what loads them here, or that nothing does. */
    private final Label loaderNote = new Label();
    private final Button loaderAction = new Button();
    private final HBox loaderRow = new HBox(8, loaderNote, loaderAction);

    private final Tab browseTab = new Tab();
    private final Tab installedTab = new Tab();
    private final TabPane tabs = new TabPane(browseTab, installedTab);

    private final VBox pane = new VBox(10);

    private List<ModEntry> packsAll = List.of();
    private List<ShaderLoaders.ShaderLoader> loaders = List.of();

    PackSection(Host host, ContentKind kind) {
        super(host);
        this.kind = kind;
        this.scan = PackScan.of(kind);
        this.catalogue = new CataloguePane(host, kind, new CataloguePane.Actions() {
            /**
             * Whether this instance already has it.
             *
             * <p>Answered from the folder as it was read rather than from the
             * record beside it. The record is what the launcher meant to do; the
             * folder is what is there, and a button is owed the second.
             */
            @Override
            public boolean isInstalled(ModProvider.SearchResult hit) {
                String key = InstalledMod.keyOf(hit.source(), hit.projectId());
                return packsAll.stream().anyMatch(pack -> key.equals(pack.key()));
            }

            @Override
            public void install(ModProvider.SearchResult hit) {
                installPack(hit);
            }

            /**
             * Nothing blocks an install here, and for shaders that is deliberate.
             *
             * <p>A missing shader loader is worth saying and is not worth
             * refusing over: the pack is a file in a folder, it will be loaded
             * the moment Iris is installed, and browsing shaders to decide
             * whether to install Iris at all is a reasonable thing to do. The
             * warning is a row of its own rather than a greyed button.
             */
            @Override
            public String blockedReason() {
                return null;
            }
        });
        build();
    }

    /** The translation key for one of this kind's own sentences. */
    private String key(String suffix) {
        return "packs." + kind.name().toLowerCase(Locale.ROOT) + "." + suffix;
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        kindNote.getStyleClass().add("muted");
        kindNote.setWrapText(true);

        // "muted" at all times; the warning look is toggled in applyLoaderRow,
        // because the row says two different things and only one of them is a
        // warning.
        loaderNote.getStyleClass().add("muted");
        loaderNote.setWrapText(true);
        loaderNote.setMinWidth(0);
        HBox.setHgrow(loaderNote, Priority.ALWAYS);
        loaderAction.setOnAction(event -> host.searchInMods(ShaderLoaders.SEARCH_TERM));
        loaderRow.setAlignment(Pos.CENTER_LEFT);

        installedList.setCellFactory(view -> new PackCell());
        installedList.setPlaceholder(installedEmpty);
        VBox.setVgrow(installedList, Priority.ALWAYS);
        acceptDroppedPacks(installedList);

        HBox.setHgrow(installedSearch, Priority.ALWAYS);
        installedSearch.textProperty().addListener(
                (observable, previous, value) -> applyInstalledFilter());
        importButton.setOnAction(event -> importPacks());

        HBox installedControls = new HBox(8, installedSearch, importButton);
        installedControls.setAlignment(Pos.CENTER_LEFT);

        installedCount.getStyleClass().add("muted");

        VBox installedPane = new VBox(10, installedControls, installedCount, installedList);
        installedPane.getStyleClass().add("browse-pane");

        VBox browsePane = new VBox(10, kindNote, catalogue.node());
        VBox.setVgrow(catalogue.node(), Priority.ALWAYS);
        browsePane.getStyleClass().add("kind-pane");

        browseTab.setClosable(false);
        installedTab.setClosable(false);
        browseTab.setContent(browsePane);
        installedTab.setContent(installedPane);
        tabs.getStyleClass().add("detail");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        pane.getChildren().setAll(loaderRow, tabs);
        pane.getStyleClass().add("kind-pane");
        applyTexts();
    }

    @Override
    Node node() {
        return pane;
    }

    @Override
    String title() {
        return I18n.t(kind.key());
    }

    @Override
    void applyTexts() {
        browseTab.setText(I18n.t("mods.tab.browse"));
        installedTab.setText(I18n.t("mods.tab.installed", packsAll.size()));
        installedSearch.setPromptText(I18n.t(key("installed.search")));
        importButton.setText(I18n.t("mods.import"));
        kindNote.setText(I18n.t(key("note")));
        installedEmpty.setText(I18n.t(key("installed.empty")));
        loaderAction.setText(I18n.t("packs.shader.noLoader.action"));
        applyLoaderRow();
        catalogue.applyTexts();
    }

    /**
     * The line above the tabs, for shaders only.
     *
     * <p>Two states, and the useful one is not the warning: an instance that has
     * Iris says so, because "loaded by Iris" is the answer to the question
     * somebody is asking when they wonder whether any of this will work.
     */
    private void applyLoaderRow() {
        boolean shown = kind == ContentKind.SHADER;
        boolean missing = shown && loaders.isEmpty();
        loaderNote.setText(!shown ? ""
                : missing ? I18n.t("packs.shader.noLoader")
                        : I18n.t("packs.shader.loader", ShaderLoaders.describe(loaders)));
        ContentRow.styleClass(loaderNote, "dialog-warning", missing);
        loaderAction.setVisible(missing);
        loaderAction.setManaged(missing);
        loaderAction.setDisable(host.isBusy());
        loaderRow.setVisible(shown);
        loaderRow.setManaged(shown);
    }

    @Override
    void refresh() {
        packsAll = host.service().packsIn(host.profile(), kind);
        if (kind == ContentKind.SHADER) {
            // The mods folder, not this one: Iris and OptiFine are mods. Read
            // with the pack list because installing one changes the answer, and
            // the answer decides which build of a pack is asked for.
            loaders = host.service().shaderLoadersIn(host.profile());
        }
        installedTab.setText(I18n.t("mods.tab.installed", packsAll.size()));
        importButton.setDisable(host.isBusy());
        applyLoaderRow();
        applyInstalledFilter();
    }

    @Override
    void onShown() {
        refresh();
        catalogue.search();
    }

    @Override
    void onProfileChanged() {
        // A resource pack catalogue is narrowed to the profile's Minecraft
        // version, so what is on screen was chosen against a version the profile
        // no longer has. A shader catalogue is not narrowed by version - see
        // ContentKind.SHADER - but its loader row is read from a mods folder a
        // pack install has just rewritten.
        catalogue.applyTexts();
        refresh();
        catalogue.search();
    }

    @Override
    void onBusyChanged() {
        importButton.setDisable(host.isBusy());
        loaderAction.setDisable(host.isBusy());
        catalogue.refreshRows();
        installedList.refresh();
    }

    /** Rebuilds the catalogue's category menu, after fresh drawings arrived. */
    void refreshCategoryArt() {
        catalogue.refreshCategoryArt();
        installedList.refresh();
    }

    /**
     * Narrows the installed list.
     *
     * <p>By name and by file name, which are the two things a player knows a
     * pack by - and they are often not the same word.
     */
    private void applyInstalledFilter() {
        String query = installedSearch.getText() == null
                ? "" : installedSearch.getText().trim().toLowerCase(Locale.ROOT);
        List<ModEntry> shown = packsAll.stream()
                .filter(pack -> query.isEmpty()
                        || pack.title().toLowerCase(Locale.ROOT).contains(query)
                        || pack.fileName().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        installedList.setItems(FXCollections.observableArrayList(shown));

        boolean narrowed = shown.size() != packsAll.size();
        installedCount.setText(narrowed
                ? I18n.t("mods.installed.shown", shown.size(), packsAll.size())
                : "");
        installedCount.setVisible(narrowed);
        installedCount.setManaged(narrowed);
    }

    // ---------------------------------------------------------------- actions

    private void installPack(ModProvider.SearchResult hit) {
        ModProvider.ProjectCard card = hit.card();
        host.mutate(I18n.t("mods.task.install", hit.title()), () -> {
            PackInstaller.Result result =
                    host.service().installPackFile(host.profile(), kind, card, host.progress());
            Platform.runLater(() -> {
                refresh();
                catalogue.refreshRows();
                host.progress().done(I18n.t(key("installed"), result.installed().size()));
                if (!result.isClean()) {
                    // Named rather than counted. What is usually in here is the
                    // program that loads the pack, and a player who is not told
                    // its name has a pack that does nothing and no way to find
                    // out why.
                    host.warn(I18n.t("packs.requirements.header"),
                            String.join("\n", result.notes()));
                }
            });
        });
    }

    private void removePack(ModEntry pack) {
        String body = pack.isManaged()
                ? I18n.t(key("remove.body"), pack.title())
                : I18n.t(key("remove.body.external"), pack.title(), pack.fileName());

        Label text = new Label(body);
        text.setWrapText(true);
        text.setMinWidth(0);

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(host.stage());
        Theme.apply(confirm.getDialogPane());
        confirm.setTitle(I18n.t(key("remove.header")));
        confirm.setHeaderText(I18n.t(key("remove.header")));
        confirm.getDialogPane().setContent(text);
        confirm.getDialogPane().setPrefWidth(560);

        boolean yes = confirm.showAndWait()
                .filter(button -> button.getButtonData().isDefaultButton()).isPresent();
        if (!yes) {
            return;
        }
        host.mutate(I18n.t("mods.task.remove", pack.title()), () -> {
            if (pack.isManaged()) {
                host.service().removePackFile(host.profile(), kind, pack.key(), host.progress());
            } else {
                host.service().discardExternalPack(host.profile(), kind, pack, host.progress());
            }
            Platform.runLater(() -> {
                refresh();
                catalogue.refreshRows();
                host.progress().done(I18n.t(pack.isManaged() ? "mods.removed" : "mods.discarded",
                        pack.title()));
            });
        });
    }

    private void togglePack(ModEntry pack) {
        boolean enable = !pack.enabled();
        host.mutate(I18n.t(enable ? "mods.task.enable" : "mods.task.disable", pack.title()), () -> {
            host.service().setPackEnabled(host.profile(), kind, pack, enable);
            Platform.runLater(() -> {
                refresh();
                host.progress().done(I18n.t(
                        enable ? "mods.enabled" : "mods.disabled", pack.title()));
            });
        });
    }

    private void importPacks() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t(key("import.title")));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t(key("import.filter")), kind.chooserPatterns()));
        List<java.io.File> chosen = chooser.showOpenMultipleDialog(host.stage());
        if (chosen == null || chosen.isEmpty()) {
            return;
        }
        importFiles(chosen.stream().map(java.io.File::toPath).toList());
    }

    private void importFiles(List<java.nio.file.Path> files) {
        if (files.isEmpty()) {
            return;
        }
        host.mutate(I18n.t("mods.task.import", files.size()), () -> {
            ModScan.Imported result =
                    host.service().importPackFiles(host.profile(), kind, files, host.progress());
            Platform.runLater(() -> {
                refresh();
                host.progress().done(I18n.t(key("imported"), result.imported().size()));
                if (!result.skipped().isEmpty()) {
                    // Named rather than counted: "three of your eleven files
                    // were skipped" without saying which three is a message that
                    // has to be worked out with a file manager.
                    host.warn(I18n.t("mods.import.skipped.header"),
                            result.skipped().stream().map(this::reasonFor)
                                    .collect(java.util.stream.Collectors.joining("\n")));
                }
            });
        });
    }

    /**
     * One refused file, as a line somebody can act on.
     *
     * <p>The reasons are the folder reader's; the sentences are this window's,
     * because this is where the language is known. Two of them are worded per
     * kind: what makes a zip a resource pack is a {@code pack.mcmeta} inside it,
     * and what makes one a shader pack is a {@code shaders} folder - so "not a
     * pack" is a different sentence in each section.
     */
    private String reasonFor(ModScan.Skip skip) {
        return switch (skip.reason()) {
            case NOT_A_FILE -> I18n.t("mods.import.skipped.notFile", skip.file());
            case NOT_A_JAR -> I18n.t("packs.import.skipped.notZip", skip.file());
            case ALREADY_THERE -> I18n.t("mods.import.skipped.already", skip.file());
            case NOT_AN_ARCHIVE -> I18n.t(key("import.skipped.notPack"), skip.file());
            case FAILED -> I18n.t("mods.import.skipped.failed", skip.file(),
                    skip.detail() == null ? "" : skip.detail());
        };
    }

    private void acceptDroppedPacks(Node target) {
        target.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles() && !host.isBusy()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });
        target.setOnDragDropped(event -> {
            boolean handled = event.getDragboard().hasFiles();
            if (handled) {
                importFiles(event.getDragboard().getFiles().stream()
                        .map(java.io.File::toPath).toList());
            }
            event.setDropCompleted(handled);
            event.consume();
        });
    }

    /** One pack in the folder: what it is, and what may be done to it. */
    private final class PackCell extends ContentRow<ModEntry> {

        private final Label badge = new Label();
        private final Button toggle = new Button();
        private final Button remove = new Button();
        private final Tooltip toggleTip = new Tooltip();
        private final Tooltip removeTip = new Tooltip();

        PackCell() {
            super(host::categories, catalogue::highlighted);
            badge.getStyleClass().add("badge");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            remove.getStyleClass().add("danger");
            actions.getChildren().addAll(badge, toggle, remove);
        }

        @Override
        protected void updateItem(ModEntry pack, boolean empty) {
            super.updateItem(pack, empty);
            if (empty || pack == null) {
                clearRow();
                return;
            }
            icon.show(pack);
            line(name, pack.title());
            // The file name is on the line either way. For a pack the launcher
            // installed it answers "which of these is that"; for one the player
            // made it is often all there is to go on.
            line(meta, pack.version() == null
                    ? pack.fileName()
                    : pack.version() + "  ·  " + pack.fileName());
            line(description, pack.description());
            tags(pack.categories());
            link(pack.pageUrl(), () -> {
                if (SystemBrowser.open(pack.pageUrl())) {
                    host.progress().done(I18n.t("mods.details.opened", pack.title()));
                    return;
                }
                host.warn(I18n.t("mods.details"), I18n.t("mods.details.failed", pack.pageUrl()));
            });

            badge.setText(ModLabels.badge(pack));
            styleClass(badge, "badge-off", !pack.enabled());
            styleClass(badge, "badge-pack", pack.enabled() && pack.isManaged());

            // A folder cannot be switched off by renaming it - the game reads
            // what is inside, not what it is called - so the button says so
            // rather than doing nothing.
            boolean togglable = PackScan.isTogglable(pack);
            toggle.setText(I18n.t(pack.enabled() ? "mods.disable" : "mods.enable"));
            toggle.setDisable(host.isBusy() || !togglable);
            tooltip(toggle, toggleTip, togglable ? null : I18n.t("packs.folder.noToggle"));
            toggle.setOnAction(event -> togglePack(pack));

            // A pack a modpack brought with it goes out with that modpack. Same
            // rule as the mods list, and for the same reason: a set that was
            // tested together stops being that set as soon as one file is
            // pulled out of it.
            boolean owned = pack.origin() == ModOrigin.PACK;
            remove.setText(I18n.t("mods.remove"));
            remove.setDisable(host.isBusy() || owned);
            tooltip(remove, removeTip, owned ? I18n.t("packs.pack.locked") : null);
            remove.setOnAction(event -> removePack(pack));
            showRow();
        }
    }
}
