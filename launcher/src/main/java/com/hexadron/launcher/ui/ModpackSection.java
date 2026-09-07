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
import com.hexadron.launcher.mods.InstalledModpack;
import com.hexadron.launcher.mods.ModFile;
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.mods.ModpackInstaller;
import com.hexadron.launcher.mods.PackArchive;
import com.hexadron.launcher.profile.Profile;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Modpacks: the catalogue, and the ones this instance has.
 *
 * <h2>A modpack is not a big mod</h2>
 *
 * <p>Everything else in this window goes into an instance. A modpack
 * <em>is</em> one: it states a Minecraft version, a loader at a pinned version,
 * a set of files and a folder of configuration to lay over the top. Installing
 * one is therefore not an addition, it is a decision about an instance, and there
 * are only two honest answers to "which instance" - a new one shaped by the pack,
 * or this one, changed to match it. So the window asks, every time, before
 * anything is downloaded.
 *
 * <p>It asks rather than choosing because both are ordinary things to want.
 * Making a new instance is what somebody trying a pack out wants, and it is the
 * safe answer: nothing they have is touched. Installing into this one is what
 * somebody rebuilding an instance they already have accounts, worlds and settings
 * in wants - and doing that behind their back would take a version and a loader
 * off them without asking.
 *
 * <h2>The catalogue is not filtered to this instance</h2>
 *
 * <p>Unlike mods. A pack names its own version and loader, so narrowing the list
 * to the instance's would hide every pack the user might install next - which is
 * all of them, since a pack that already matched would be one there was no reason
 * to install. The line above the list says so, because a list that is not
 * filtered in a window where everything else is needs to say which it is.
 */
final class ModpackSection extends ContentSection {

    private final CataloguePane catalogue;

    private final ListView<InstalledModpack> installedList = new ListView<>();
    private final Label installedEmpty = new Label();
    private final Label kindNote = new Label();
    private final Button openFileButton = new Button();

    private final Tab browseTab = new Tab();
    private final Tab installedTab = new Tab();
    private final TabPane tabs = new TabPane(browseTab, installedTab);

    private List<InstalledModpack> installed = List.of();

    ModpackSection(Host host) {
        super(host);
        this.catalogue = new CataloguePane(host, ContentKind.MODPACK, new CataloguePane.Actions() {
            @Override
            public boolean isInstalled(ModProvider.SearchResult hit) {
                return installed.stream().anyMatch(pack ->
                        pack.source() == hit.source()
                                && hit.projectId().equals(pack.projectId()));
            }

            @Override
            public void install(ModProvider.SearchResult hit) {
                installFromPlatform(hit);
            }
        });
        build();
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        kindNote.getStyleClass().add("muted");
        kindNote.setWrapText(true);

        installedList.setCellFactory(view -> new PackCell());
        installedList.setPlaceholder(installedEmpty);
        VBox.setVgrow(installedList, Priority.ALWAYS);
        acceptDroppedPacks(installedList);

        openFileButton.setOnAction(event -> installFromFile());

        HBox controls = new HBox(8, spacer(), openFileButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        VBox installedPane = new VBox(10, controls, installedList);
        installedPane.getStyleClass().add("browse-pane");

        VBox browsePane = new VBox(10, kindNote, catalogue.node());
        VBox.setVgrow(catalogue.node(), Priority.ALWAYS);
        browsePane.getStyleClass().add("kind-pane");

        browseTab.setClosable(false);
        installedTab.setClosable(false);
        browseTab.setContent(browsePane);
        installedTab.setContent(installedPane);
        tabs.getStyleClass().add("detail");
        applyTexts();
    }

    @Override
    Node node() {
        return tabs;
    }

    @Override
    String title() {
        return I18n.t("mods.kind.modpack");
    }

    @Override
    void applyTexts() {
        browseTab.setText(I18n.t("mods.tab.browse"));
        installedTab.setText(I18n.t("modpacks.tab.installed", installed.size()));
        installedEmpty.setText(I18n.t("modpacks.installed.empty"));
        kindNote.setText(I18n.t("modpacks.note"));
        openFileButton.setText(I18n.t("modpacks.openFile"));
        catalogue.applyTexts();
    }

    @Override
    void refresh() {
        installed = host.service().modpacksIn(host.profile());
        installedList.setItems(FXCollections.observableArrayList(installed));
        installedTab.setText(I18n.t("modpacks.tab.installed", installed.size()));
        installedEmpty.setText(I18n.t("modpacks.installed.empty"));
        catalogue.refreshRows();
    }

    @Override
    void onShown() {
        if (catalogue.node().getScene() != null && installed.isEmpty()) {
            // Nothing yet, so the catalogue is the useful tab. Not forced on a
            // later visit: somebody who left this window on Installed meant to.
            tabs.getSelectionModel().select(browseTab);
        }
        catalogue.search();
    }

    @Override
    void onBusyChanged() {
        openFileButton.setDisable(host.isBusy());
        catalogue.refreshRows();
        installedList.refresh();
    }

    // ---------------------------------------------------------------- install

    /** Where a pack is about to be installed. */
    private enum Target {
        /** A new instance, shaped by the pack. Nothing the user has is touched. */
        NEW_PROFILE,
        /** This instance, changed to match the pack. */
        THIS_PROFILE
    }

    /**
     * Asks which instance, before anything is downloaded.
     *
     * <p>Before, and not after, because a pack is hundreds of megabytes and a
     * question asked at the end of a ten-minute download is a question asked when
     * the answer can no longer be "neither".
     *
     * @return empty when the user cancelled
     */
    private Optional<Target> askTarget(String packName) {
        ButtonType newProfile = new ButtonType(
                I18n.t("modpacks.target.new"), ButtonBar.ButtonData.OK_DONE);
        ButtonType thisProfile = new ButtonType(
                I18n.t("modpacks.target.this"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(
                I18n.t("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Label body = new Label(I18n.t("modpacks.target.body", packName, host.profile().name()));
        body.setWrapText(true);
        body.setMinWidth(0);

        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.initOwner(host.stage());
        Theme.apply(ask.getDialogPane());
        ask.setTitle(I18n.t("modpacks.target.header"));
        ask.setHeaderText(I18n.t("modpacks.target.header"));
        ask.getDialogPane().setContent(body);
        ask.getDialogPane().setPrefWidth(600);
        ask.getButtonTypes().setAll(newProfile, thisProfile, cancel);

        Optional<ButtonType> chosen = ask.showAndWait();
        if (chosen.isEmpty() || chosen.get() == cancel) {
            return Optional.empty();
        }
        return Optional.of(chosen.get() == thisProfile ? Target.THIS_PROFILE : Target.NEW_PROFILE);
    }

    private void installFromPlatform(ModProvider.SearchResult hit) {
        Optional<Target> target = askTarget(hit.title());
        if (target.isEmpty()) {
            return;
        }
        ModProvider.ProjectCard card = hit.card();
        host.mutate(I18n.t("mods.task.install", hit.title()), () -> {
            ModFile file = host.service().resolveModpack(card).orElseThrow(
                    () -> new java.io.IOException(I18n.t("modpacks.noFile", hit.title())));
            Path archive = host.service().fetchModpack(file, host.progress());
            PackArchive pack = host.service().readModpack(archive);
            apply(pack, card, target.get());
        });
    }

    /**
     * Installs a pack file the user opened themselves.
     *
     * <p>The way in for a pack that is not on either platform, for one whose
     * author has switched off third-party downloads, and for one somebody built.
     * The file is read before anything is asked, so the question names the pack
     * rather than the file.
     */
    private void installFromFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("modpacks.openFile.title"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("modpacks.openFile.filter"),
                ContentKind.MODPACK.chooserPatterns()));
        java.io.File chosen = chooser.showOpenDialog(host.stage());
        if (chosen == null) {
            return;
        }
        installFile(chosen.toPath());
    }

    private void installFile(Path file) {
        PackArchive pack;
        try {
            pack = host.service().readModpack(file);
        } catch (java.io.IOException e) {
            host.warn(I18n.t("modpacks.openFile.title"),
                    e.getMessage() == null ? e.toString() : e.getMessage());
            return;
        }
        Optional<Target> target = askTarget(pack.instanceName());
        if (target.isEmpty()) {
            return;
        }
        host.mutate(I18n.t("mods.task.install", pack.instanceName()),
                () -> apply(pack, null, target.get()));
    }

    /** The half of an install that is the same however the pack arrived. */
    private void apply(PackArchive pack, ModProvider.ProjectCard card, Target target)
            throws Exception {

        Profile profile = target == Target.NEW_PROFILE
                ? host.service().createProfileForModpack(pack)
                : host.profile();

        ModpackInstaller.Result result =
                host.service().installModpack(profile, pack, card, host.progress());

        Platform.runLater(() -> {
            host.contentChanged();
            host.progress().done(target == Target.NEW_PROFILE
                    ? I18n.t("modpacks.installed.new", profile.name(), result.files())
                    : I18n.t("modpacks.installed.here", result.files()));
            if (!result.isClean()) {
                host.warn(I18n.t("mods.attention.header"), String.join("\n",
                        java.util.stream.Stream.concat(result.skipped().stream(),
                                result.manualDownloads().stream()).toList()));
            }
        });
    }

    /** Lets a pack file be dropped onto the installed list. */
    private void acceptDroppedPacks(Node target) {
        target.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles() && !host.isBusy()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });
        target.setOnDragDropped(event -> {
            List<java.io.File> files = event.getDragboard().getFiles();
            boolean handled = files != null && !files.isEmpty();
            if (handled) {
                // One at a time. Two packs into one instance is not a thing that
                // can be done - the second would overwrite the first's version -
                // so the first file is taken and the rest are ignored rather than
                // installed one over another.
                installFile(files.get(0).toPath());
            }
            event.setDropCompleted(handled);
            event.consume();
        });
    }

    private void removePack(InstalledModpack pack) {
        Label body = new Label(I18n.t("modpacks.remove.body", pack.name(), pack.paths().size()));
        body.setWrapText(true);
        body.setMinWidth(0);

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(host.stage());
        Theme.apply(confirm.getDialogPane());
        confirm.setTitle(I18n.t("modpacks.remove.header"));
        confirm.setHeaderText(I18n.t("modpacks.remove.header"));
        confirm.getDialogPane().setContent(body);
        confirm.getDialogPane().setPrefWidth(560);

        boolean yes = confirm.showAndWait()
                .filter(button -> button.getButtonData().isDefaultButton()).isPresent();
        if (!yes) {
            return;
        }
        host.mutate(I18n.t("mods.task.remove", pack.name()), () -> {
            int removed = host.service().removeModpack(host.profile(), pack.id(), host.progress());
            Platform.runLater(() -> {
                host.contentChanged();
                host.progress().done(I18n.t("modpacks.removed", pack.name(), removed));
            });
        });
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /** One installed pack: what it is, what it is for, and one button. */
    private final class PackCell extends ContentRow<InstalledModpack> {

        private final Label badge = new Label();
        private final Button remove = new Button();

        PackCell() {
            super(host::categories, host::highlightedCategories);
            badge.getStyleClass().addAll("badge", "badge-pack");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            remove.getStyleClass().add("danger");
            actions.getChildren().addAll(badge, remove);
        }

        @Override
        protected void updateItem(InstalledModpack pack, boolean empty) {
            super.updateItem(pack, empty);
            if (empty || pack == null) {
                clearRow();
                return;
            }
            icon.show(pack.iconUrl(), pack.name());
            line(name, pack.name());
            line(meta, pack.subtitle());
            // What the pack made this instance, in the pack's own terms. It is
            // the thing a player checks a pack row for: which version am I on,
            // and on what.
            line(description, I18n.t("modpacks.builtFor",
                    pack.minecraftVersion() == null ? "?" : pack.minecraftVersion(),
                    pack.loader() == null ? "?" : pack.loader().displayName())
                    + "  ·  " + I18n.t("modpacks.fileCount", pack.paths().size()));
            tags(List.of());
            link(pack.pageUrl(), () -> {
                if (SystemBrowser.open(pack.pageUrl())) {
                    host.progress().done(I18n.t("mods.details.opened", pack.name()));
                    return;
                }
                host.warn(I18n.t("mods.details"), I18n.t("mods.details.failed", pack.pageUrl()));
            });

            badge.setText(I18n.t("modpacks.badge"));
            remove.setText(I18n.t("mods.remove"));
            remove.setDisable(host.isBusy());
            remove.setOnAction(event -> removePack(pack));
            showRow();
        }
    }
}
