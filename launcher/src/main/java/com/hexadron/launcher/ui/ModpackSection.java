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
 * <h2>The catalogue can be filtered to this profile, and is by default</h2>
 *
 * <p>Unlike mods, where the narrowing is not a question: a pack names its own
 * version and loader rather than needing them, so both answers are real. Shown
 * unnarrowed, the list is every pack there is - which is how somebody finds the
 * pack they will switch to next. Narrowed, it is the packs that fit the profile
 * they are browsing from, which is what somebody adding a pack to a profile they
 * already play is looking for, and it hides the several thousand that would
 * replace its version.
 *
 * <p>So there is a tick box, and it starts ticked. Not because the narrow list is
 * the more useful one in the abstract, but because the wide one cannot be read
 * without knowing what each row would do to this profile - and the box is the
 * one control that says out loud that a pack has a version and a loader of its
 * own.
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

    /**
     * Brings one installed pack into view, and selects it.
     *
     * <p>What a mod row's modpack badge leads to. A launcher that answered
     * "which pack is this jar from" with a name and nothing else would leave the
     * reader to find that name in a list themselves; this is the same move the
     * dependency panel makes for a mod, one level up.
     *
     * @param packId {@link InstalledModpack#id()} of the pack to show
     */
    void reveal(String packId) {
        if (installed.isEmpty()) {
            refresh();
        }
        tabs.getSelectionModel().select(installedTab);
        List<InstalledModpack> shown = installedList.getItems();
        for (int index = 0; index < shown.size(); index++) {
            if (shown.get(index).id().equals(packId)) {
                installedList.getSelectionModel().clearAndSelect(index);
                // One row above the target, so it does not land against the top
                // edge with no context above it.
                installedList.scrollTo(Math.max(0, index - 1));
                installedList.requestFocus();
                return;
            }
        }
    }

    @Override
    void onProfileChanged() {
        // The tooltip names the profile's version and loader, and the catalogue
        // may be narrowed to them. Both were just replaced.
        catalogue.applyTexts();
        catalogue.search();
    }

    /** Rebuilds the catalogue's category menu, after fresh drawings arrived. */
    void refreshCategoryArt() {
        catalogue.refreshCategoryArt();
        installedList.refresh();
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
     * Asks which profile, before anything is downloaded.
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

        // What the pack is, before what to do with it. A modpack is a
        // pre-arranged Minecraft - a version, a loader and a set of mods chosen
        // together - and the one thing a reader has to know before answering is
        // that laying it over a profile they already play is the answer that
        // breaks things.
        Label warning = new Label(I18n.t("modpacks.target.warning"));
        warning.setWrapText(true);
        warning.setMinWidth(0);
        warning.getStyleClass().add("dialog-warning");

        VBox content = new VBox(10, body, warning);
        content.setMinWidth(0);

        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.initOwner(host.stage());
        Theme.apply(ask.getDialogPane());
        ask.setTitle(I18n.t("modpacks.target.header"));
        ask.setHeaderText(I18n.t("modpacks.target.header"));
        ask.getDialogPane().setContent(content);
        ask.getDialogPane().setPrefWidth(620);
        ask.getButtonTypes().setAll(newProfile, thisProfile, cancel);

        // The recommended answer is the one Enter chooses, and the destructive
        // one is not. A dialog whose default button is the answer its own
        // warning argues against is a dialog that recommends one thing and does
        // another.
        Button newButton = (Button) ask.getDialogPane().lookupButton(newProfile);
        newButton.setDefaultButton(true);
        newButton.getStyleClass().add("primary");
        ((Button) ask.getDialogPane().lookupButton(thisProfile)).setDefaultButton(false);

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

        if (target == Target.NEW_PROFILE) {
            adoptPackIcon(profile, card);
        }

        Platform.runLater(() -> {
            host.contentChanged();
            // The profile is not what it was: into this one, its version and its
            // loader are the pack's now; into a new one, there is a profile the
            // launcher's list has never seen. Either way the panel and the list
            // are describing something out of date until they are told.
            host.profileChanged();
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

    /**
     * Gives a profile made for a pack the pack's own logo.
     *
     * <h2>Why a picture and not only a name</h2>
     *
     * <p>The profile is already named after the pack. In a grid of thirty
     * profiles the name is the small print and the picture is what the eye finds
     * - and without this every pack-made profile wears the mark of its loader,
     * so four packs on Fabric are four identical squares that have to be read
     * one by one. The pack's logo is the picture the player already recognises
     * from the page they installed it from.
     *
     * <p>Quietly, and never at the cost of the install. It runs on the install's
     * own thread after the files are in place, so a logo that will not download
     * or will not decode leaves a profile with its loader's mark - which is what
     * every profile had until now - rather than an install that reports a
     * failure for a picture.
     *
     * @param card the platform's record of the pack, or null for a pack file the
     *             user opened themselves - which carries no logo to fetch
     */
    private void adoptPackIcon(Profile profile, ModProvider.ProjectCard card) {
        if (card == null || card.iconUrl() == null || card.iconUrl().isBlank()) {
            return;
        }
        try {
            byte[] logo = host.service().fetchIcon(card.iconUrl());
            String name = ProfileIcons.storeFetched(logo, host.service().dirs());
            profile.customIcon(name);
            host.service().profiles().save();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // A profile with the loader's mark on it, which is what it would
            // have had anyway. Logged rather than raised: the pack is installed.
            host.progress().log("The pack's logo could not be used as the profile picture: %s",
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
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
            super(host::categories, catalogue::highlighted);
            // Purple, and the same purple a mod row's modpack badge is drawn in.
            // The two say the same word about the same thing from two lists, and
            // a colour that changed between them would read as two meanings.
            badge.getStyleClass().addAll("badge", "badge-modpack");
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
