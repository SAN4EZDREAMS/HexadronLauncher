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
import com.hexadron.launcher.mods.DatapackInstaller;
import com.hexadron.launcher.mods.DatapackScan;
import com.hexadron.launcher.mods.ModEntry;
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.mods.ModScan;
import com.hexadron.launcher.mods.WorldSaves;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
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
import javafx.util.StringConverter;

import java.util.List;
import java.util.Locale;

/**
 * Data packs, one world at a time.
 *
 * <h2>Why there is a world picker and not a folder</h2>
 *
 * <p>Minecraft loads data packs from {@code saves/&lt;world&gt;/datapacks}. There
 * is no instance-wide folder, and that is not an oversight: a data pack changes
 * recipes, loot tables and world generation, which belong to a world rather than
 * to a game. A launcher that kept a folder of its own and copied out of it would
 * be showing a list of intentions - a pack removed from the world still
 * "installed", a pack added after the world was made not in it - so this section
 * lists one world's folder and nothing else. What it shows is what the game will
 * load.
 *
 * <p>The consequence is honest and worth stating plainly: an instance that has
 * never been launched has no worlds, and there is nowhere to install a data pack
 * to. The section says so and greys the buttons rather than offering an install
 * that would have to invent a destination.
 *
 * <h2>No loader needed</h2>
 *
 * <p>This is the one section that works on a plain instance. Data packs are
 * loaded by vanilla Minecraft, so nothing here asks about Fabric or Forge.
 */
final class DatapackSection extends ContentSection {

    private final CataloguePane catalogue;

    private final ComboBox<WorldSaves.World> worldBox = new ComboBox<>();
    private final Label worldLabel = new Label();
    private final Label worldNote = new Label();
    private final Button reloadWorlds = new Button();

    private final ListView<ModEntry> installedList = new ListView<>();
    private final Label installedEmpty = new Label();
    private final Label installedCount = new Label();
    private final TextField installedSearch = new TextField();
    private final Button importButton = new Button();
    private final Label kindNote = new Label();

    private final Tab browseTab = new Tab();
    private final Tab installedTab = new Tab();
    private final TabPane tabs = new TabPane(browseTab, installedTab);

    private final VBox pane = new VBox(10);

    private List<WorldSaves.World> worlds = List.of();
    private List<ModEntry> packsAll = List.of();

    DatapackSection(Host host) {
        super(host);
        this.catalogue = new CataloguePane(host, ContentKind.DATAPACK, new CataloguePane.Actions() {
            @Override
            public boolean isInstalled(ModProvider.SearchResult hit) {
                WorldSaves.World world = worldBox.getValue();
                if (world == null) {
                    return false;
                }
                return DatapackScan.libraryOf(world.datapacks())
                        .contains(hit.source(), hit.projectId());
            }

            @Override
            public void install(ModProvider.SearchResult hit) {
                installPack(hit);
            }

            @Override
            public String blockedReason() {
                if (worlds.isEmpty()) {
                    return I18n.t("datapacks.noWorlds");
                }
                return worldBox.getValue() == null ? I18n.t("datapacks.pickWorld") : null;
            }
        });
        build();
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        worldLabel.getStyleClass().add("form-label");
        worldBox.setPrefWidth(280);
        worldBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(WorldSaves.World value) {
                return value == null ? "" : I18n.t("datapacks.world.line",
                        value.folder(), value.datapackCount());
            }

            @Override
            public WorldSaves.World fromString(String text) {
                return null;
            }
        });
        worldBox.valueProperty().addListener((observable, previous, value) -> onWorldChanged());

        reloadWorlds.setOnAction(event -> refresh());

        worldNote.getStyleClass().add("muted");
        worldNote.setWrapText(true);
        HBox.setHgrow(worldNote, Priority.ALWAYS);

        HBox worldRow = new HBox(8, worldLabel, worldBox, reloadWorlds, worldNote);
        worldRow.setAlignment(Pos.CENTER_LEFT);
        worldRow.getStyleClass().add("world-row");

        kindNote.getStyleClass().add("muted");
        kindNote.setWrapText(true);

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

        pane.getChildren().setAll(worldRow, tabs);
        pane.getStyleClass().add("kind-pane");
        applyTexts();
    }

    @Override
    Node node() {
        return pane;
    }

    @Override
    String title() {
        return I18n.t("mods.kind.datapack");
    }

    @Override
    void applyTexts() {
        worldLabel.setText(I18n.t("datapacks.world"));
        reloadWorlds.setText(I18n.t("datapacks.reload"));
        browseTab.setText(I18n.t("mods.tab.browse"));
        installedTab.setText(I18n.t("datapacks.tab.installed", packsAll.size()));
        installedSearch.setPromptText(I18n.t("datapacks.installed.search"));
        importButton.setText(I18n.t("mods.import"));
        kindNote.setText(I18n.t("datapacks.note"));
        installedEmpty.setText(worlds.isEmpty()
                ? I18n.t("datapacks.noWorlds")
                : I18n.t("datapacks.installed.empty"));
        // The converter reads I18n each time; nudging the box redraws it.
        WorldSaves.World chosen = worldBox.getValue();
        worldBox.setValue(null);
        worldBox.setValue(chosen);
        catalogue.applyTexts();
    }

    @Override
    void refresh() {
        WorldSaves.World previous = worldBox.getValue();
        worlds = host.service().worldsIn(host.profile());
        worldBox.setItems(FXCollections.observableArrayList(worlds));

        WorldSaves.World keep = null;
        if (previous != null) {
            for (WorldSaves.World world : worlds) {
                if (world.folder().equals(previous.folder())) {
                    keep = world;
                    break;
                }
            }
        }
        // The most recently played world, when there is nothing to keep. It is
        // the one the player is in, and therefore the one they mean.
        worldBox.setValue(keep != null ? keep : (worlds.isEmpty() ? null : worlds.get(0)));

        worldBox.setDisable(worlds.isEmpty());
        worldNote.setText(worlds.isEmpty() ? I18n.t("datapacks.noWorlds") : "");
        worldNote.setVisible(worlds.isEmpty());
        worldNote.setManaged(worlds.isEmpty());
        reloadWorlds.setDisable(host.isBusy());

        reloadPacks();
    }

    @Override
    void onShown() {
        refresh();
        catalogue.search();
    }

    @Override
    void onProfileChanged() {
        // Data packs are searched for the profile's Minecraft version and its
        // loader, and the worlds belong to a profile a pack has just rebuilt.
        // The catalogue's texts go first: the "without mods" box names the
        // profile's pair in its tooltip, and on an instance with no loader it is
        // the box's own state that changes rather than only its words.
        catalogue.applyTexts();
        refresh();
        catalogue.search();
    }

    /**
     * Goes to one data pack's row, choosing its world on the way.
     *
     * <p>How a mod that a data pack brought with it answers "which pack is
     * this". The world has to be chosen first because a data pack's row does not
     * exist until its world is the one being shown - which is the same reason
     * the section has a world picker at all.
     */
    void reveal(String world, String key) {
        if (worlds.isEmpty()) {
            refresh();
        }
        for (WorldSaves.World candidate : worlds) {
            if (candidate.folder().equals(world)) {
                // Setting it re-reads that world's folder, so the list below is
                // the one this pack is in by the time it is searched.
                worldBox.setValue(candidate);
                break;
            }
        }
        tabs.getSelectionModel().select(installedTab);
        List<ModEntry> shown = installedList.getItems();
        for (int index = 0; index < shown.size(); index++) {
            if (shown.get(index).key().equals(key)) {
                installedList.getSelectionModel().clearAndSelect(index);
                // One row above the target, so it does not land against the top
                // edge with no context above it.
                installedList.scrollTo(Math.max(0, index - 1));
                installedList.requestFocus();
                return;
            }
        }
    }

    /** Rebuilds the catalogue's category menu, after fresh drawings arrived. */
    void refreshCategoryArt() {
        catalogue.refreshCategoryArt();
        installedList.refresh();
    }

    @Override
    void onBusyChanged() {
        importButton.setDisable(host.isBusy() || worldBox.getValue() == null);
        reloadWorlds.setDisable(host.isBusy());
        catalogue.refreshRows();
        installedList.refresh();
    }

    private void onWorldChanged() {
        reloadPacks();
        catalogue.refreshRows();
    }

    /** Re-reads the chosen world's folder. */
    private void reloadPacks() {
        WorldSaves.World world = worldBox.getValue();
        packsAll = world == null ? List.of() : host.service().datapacksIn(world);
        installedTab.setText(I18n.t("datapacks.tab.installed", packsAll.size()));
        installedEmpty.setText(worlds.isEmpty()
                ? I18n.t("datapacks.noWorlds")
                : (world == null ? I18n.t("datapacks.pickWorld")
                        : I18n.t("datapacks.installed.empty")));
        importButton.setDisable(host.isBusy() || world == null);
        applyInstalledFilter();
    }

    /**
     * Narrows the installed list.
     *
     * <p>By name and by file name, which are the two things a player knows a data
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
        WorldSaves.World world = worldBox.getValue();
        if (world == null) {
            return;
        }
        ModProvider.ProjectCard card = hit.card();
        // Read on the interface thread, before the install starts: it is the
        // state of a tick box, and the box is the user's to change while a
        // download runs.
        boolean withoutMods = catalogue.narrowingChosen();
        host.mutate(I18n.t("mods.task.install", hit.title()), () -> {
            DatapackInstaller.Result result = host.service().installDatapack(
                    host.profile(), world, card, withoutMods, host.progress());
            Platform.runLater(() -> {
                refresh();
                catalogue.refreshRows();
                // The mods are named separately because they went somewhere
                // else: into this instance's mods folder, not into the world.
                // A player who asked for a data pack and got a mod is owed the
                // sentence that says so.
                host.progress().done(result.mods().isEmpty()
                        ? I18n.t("datapacks.installed",
                                result.installed().size(), world.folder())
                        : I18n.t("datapacks.installed.withMods",
                                result.installed().size(), world.folder(),
                                result.mods().size()));
                if (!result.isClean()) {
                    host.warn(I18n.t("mods.attention.header"),
                            String.join("\n", result.manualDownloads()));
                }
                // The mods list is a different section and has just gained a
                // row. Without this the jar appears only after the window is
                // reopened, which reads as the launcher having installed
                // something it did not mention.
                host.contentChanged();
            });
        });
    }

    private void removePack(ModEntry pack) {
        WorldSaves.World world = worldBox.getValue();
        if (world == null) {
            return;
        }
        String body = pack.isManaged()
                ? I18n.t("datapacks.remove.body", pack.title(), world.folder())
                : I18n.t("datapacks.remove.body.external", pack.title(), pack.fileName());

        Label text = new Label(body);
        text.setWrapText(true);
        text.setMinWidth(0);

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(host.stage());
        Theme.apply(confirm.getDialogPane());
        confirm.setTitle(I18n.t("datapacks.remove.header"));
        confirm.setHeaderText(I18n.t("datapacks.remove.header"));
        confirm.getDialogPane().setContent(text);
        confirm.getDialogPane().setPrefWidth(560);

        boolean yes = confirm.showAndWait()
                .filter(button -> button.getButtonData().isDefaultButton()).isPresent();
        if (!yes) {
            return;
        }
        host.mutate(I18n.t("mods.task.remove", pack.title()), () -> {
            int mods = 0;
            if (pack.isManaged()) {
                mods = host.service().removeDatapack(
                        host.profile(), world, pack.key(), host.progress());
            } else {
                host.service().discardExternalDatapack(world, pack, host.progress());
            }
            int removedMods = mods;
            Platform.runLater(() -> {
                refresh();
                catalogue.refreshRows();
                host.progress().done(removedMods > 0
                        ? I18n.t("datapacks.removed.withMods", pack.title(), removedMods)
                        : I18n.t(pack.isManaged() ? "mods.removed" : "mods.discarded",
                                pack.title()));
                if (removedMods > 0) {
                    host.contentChanged();
                }
            });
        });
    }

    private void togglePack(ModEntry pack) {
        WorldSaves.World world = worldBox.getValue();
        if (world == null) {
            return;
        }
        boolean enable = !pack.enabled();
        host.mutate(I18n.t(enable ? "mods.task.enable" : "mods.task.disable", pack.title()), () -> {
            host.service().setDatapackEnabled(world, pack, enable);
            Platform.runLater(() -> {
                refresh();
                host.progress().done(I18n.t(
                        enable ? "mods.enabled" : "datapacks.disabled", pack.title()));
            });
        });
    }

    private void importPacks() {
        WorldSaves.World world = worldBox.getValue();
        if (world == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("datapacks.import.title"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("datapacks.import.filter"), ContentKind.DATAPACK.chooserPatterns()));
        List<java.io.File> chosen = chooser.showOpenMultipleDialog(host.stage());
        if (chosen == null || chosen.isEmpty()) {
            return;
        }
        importFiles(chosen.stream().map(java.io.File::toPath).toList());
    }

    private void importFiles(List<java.nio.file.Path> files) {
        WorldSaves.World world = worldBox.getValue();
        if (world == null || files.isEmpty()) {
            return;
        }
        host.mutate(I18n.t("mods.task.import", files.size()), () -> {
            ModScan.Imported result = host.service().importDatapacks(world, files, host.progress());
            Platform.runLater(() -> {
                refresh();
                host.progress().done(I18n.t("datapacks.imported",
                        result.imported().size(), world.folder()));
                if (!result.skipped().isEmpty()) {
                    // Named rather than counted: "three of your eleven files were
                    // skipped" without saying which three is a message that has
                    // to be worked out with a file manager.
                    host.warn(I18n.t("mods.import.skipped.header"),
                            result.skipped().stream().map(DatapackSection::reasonFor)
                                    .collect(java.util.stream.Collectors.joining("\n")));
                }
            });
        });
    }

    /**
     * One refused file, as a line somebody can act on.
     *
     * <p>The reasons are the folder reader's; the sentences are this window's,
     * because this is where the language is known. Two of them are worded for
     * data packs: a zip is what a pack is, and a zip with no {@code pack.mcmeta}
     * in it is not a pack rather than being a broken download.
     */
    private static String reasonFor(ModScan.Skip skip) {
        return switch (skip.reason()) {
            case NOT_A_FILE -> I18n.t("mods.import.skipped.notFile", skip.file());
            case NOT_A_JAR -> I18n.t("datapacks.import.skipped.notZip", skip.file());
            case ALREADY_THERE -> I18n.t("datapacks.import.skipped.already", skip.file());
            case NOT_AN_ARCHIVE -> I18n.t("datapacks.import.skipped.notPack", skip.file());
            case FAILED -> I18n.t("mods.import.skipped.failed", skip.file(),
                    skip.detail() == null ? "" : skip.detail());
        };
    }

    private void acceptDroppedPacks(Node target) {
        target.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles() && !host.isBusy()
                    && worldBox.getValue() != null) {
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

    /** One data pack in a world's folder: what it is, and what may be done to it. */
    private final class PackCell extends ContentRow<ModEntry> {

        private final Label badge = new Label();
        private final Button toggle = new Button();
        private final Button remove = new Button();
        private final Tooltip toggleTip = new Tooltip();

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
            boolean togglable = DatapackScan.isTogglable(pack);
            toggle.setText(I18n.t(pack.enabled() ? "mods.disable" : "mods.enable"));
            toggle.setDisable(host.isBusy() || !togglable);
            tooltip(toggle, toggleTip, togglable ? null : I18n.t("datapacks.folder.noToggle"));
            toggle.setOnAction(event -> togglePack(pack));

            remove.setText(I18n.t("mods.remove"));
            remove.setDisable(host.isBusy());
            remove.setOnAction(event -> removePack(pack));
            showRow();
        }
    }
}
