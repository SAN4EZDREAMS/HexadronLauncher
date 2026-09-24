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

import com.hexadron.launcher.cleanup.CleanupAction;
import com.hexadron.launcher.cleanup.CleanupCandidate;
import com.hexadron.launcher.cleanup.StorageCategory;
import com.hexadron.launcher.cleanup.StorageCleaner;
import com.hexadron.launcher.cleanup.StorageNode;
import com.hexadron.launcher.cleanup.StorageReport;
import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.i18n.I18n;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The storage window: what the launcher keeps on this computer, and what of it
 * can go.
 *
 * <h2>Two modes, and why the line between them is where it is</h2>
 *
 * <p><b>Safe</b> is the default and the one most people should ever see. It
 * offers only what {@link com.hexadron.launcher.cleanup.StorageScanner} can
 * show the launcher does not use - versions no profile runs, libraries and
 * assets no installed version names, downloads already unpacked - and it never
 * offers anything inside an instance folder. Whatever it deletes, the launcher
 * would download again if it were ever needed.
 *
 * <p><b>Advanced</b> is the whole data folder as a tree, every row with its size
 * and a tick box. It says in red what it can break, and stays switched off
 * until the player says they understood. Worlds, mods and configs are in it,
 * because sometimes that is what somebody needs to free - and that is exactly
 * why it asks twice.
 *
 * <p>Every row, card and slice explains itself on hover: what the folder is,
 * what uses it, and what deleting it would cost.
 */
final class CleanupWindow {

    /** What the launcher window does for this one. */
    interface Host {

        /** Null when a cleanup may start, or the sentence that says why not. */
        String blockedReason();

        /** A cleanup is starting: the launcher must not start the game or install. */
        void cleaningStarted();

        /** It is over; the lists may have changed under the launcher. */
        void cleaningFinished();
    }

    /**
     * One hue per category, in a fixed order, validated for the dark panel.
     *
     * <p>Seven categorical steps and a neutral grey for the eighth, "other",
     * which is not a thing of its own but what is left.
     */
    private static final Color[] PALETTE = {
            Color.web("#3987e5"), Color.web("#d95926"), Color.web("#199e70"),
            Color.web("#c98500"), Color.web("#d55181"), Color.web("#008300"),
            Color.web("#9085e9")};
    private static final Color NEUTRAL = Color.web("#6b7280");

    /** Rows in "largest" and detail lines under a card. */
    private static final int TOP = 7;
    private static final int DETAILS = 40;

    private final Stage stage = new Stage();
    private final LauncherService service;
    private final Host host;

    private StorageReport report;
    private boolean advanced;
    private boolean working;

    // ---------------------------------------------------------------- header
    private final ToggleButton safeToggle = new ToggleButton();
    private final ToggleButton advancedToggle = new ToggleButton();
    private final Button rescanButton = new Button();
    private final Label totalValue = new Label("-");
    private final Label totalCaption = new Label();
    private final Label reclaimValue = new Label("-");
    private final Label reclaimCaption = new Label();
    private final Label freeValue = new Label("-");
    private final Label freeCaption = new Label();
    private final ProgressBar freeBar = new ProgressBar(0);
    private final Label largestValue = new Label("-");
    private final Label largestCaption = new Label();

    // ---------------------------------------------------------------- left
    private final DonutChart donut = new DonutChart();
    private final VBox legend = new VBox(4);
    private final VBox topList = new VBox(6);
    private final Map<StorageCategory, HBox> legendRows = new EnumMap<>(StorageCategory.class);

    // ---------------------------------------------------------------- safe
    private final VBox safeView = new VBox(10);
    private final VBox cards = new VBox(8);
    private final VBox notices = new VBox(6);
    private final ScrollPane cardScroll = new ScrollPane(cards);
    private final Set<String> chosenCandidates = new LinkedHashSet<>();
    private final Map<String, Node> cardById = new java.util.HashMap<>();

    // ---------------------------------------------------------------- advanced
    private final VBox advancedView = new VBox(10);
    private final CheckBox understood = new CheckBox();
    private final TextField filter = new TextField();
    private final ListView<StorageNode> tree = new ListView<>();
    private final ObservableList<StorageNode> visible = FXCollections.observableArrayList();
    private final Set<StorageNode> expanded = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<StorageNode> chosenLeaves = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<StorageNode, Counts> counts = new IdentityHashMap<>();
    private final Label treeSummary = new Label();

    // ---------------------------------------------------------------- footer
    private final Label status = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button cleanButton = new Button();

    /** Per node: how many leaves under it can be ticked, how many are, and what they weigh. */
    private static final class Counts {
        int selectable;
        int locked;
        int chosen;
        long chosenBytes;
    }

    CleanupWindow(Stage owner, LauncherService service, Host host) {
        this.service = service;
        this.host = host;
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.getIcons().addAll(owner.getIcons());
        stage.setTitle(I18n.t("cleanup.title"));

        BorderPane root = new BorderPane();
        root.getStyleClass().add("cleanup-root");
        root.setTop(new VBox(buildHeader(), buildTiles()));
        root.setCenter(buildBody());
        root.setBottom(buildFooter());

        Scene scene = new Scene(root, 1200, 800);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(660);
        showMode();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    void show() {
        stage.show();
        stage.toFront();
        if (report == null && !working) {
            scan(null);
        }
    }

    void toFront() {
        stage.toFront();
        stage.requestFocus();
    }

    // ---------------------------------------------------------------- building

    private HBox buildHeader() {
        Label title = new Label(I18n.t("cleanup.title"));
        title.getStyleClass().add("cleanup-title");
        Label subtitle = new Label(I18n.t("cleanup.subtitle", service.dirs().root()));
        subtitle.getStyleClass().add("muted");
        VBox titles = new VBox(2, title, subtitle);

        ToggleGroup modes = new ToggleGroup();
        safeToggle.setToggleGroup(modes);
        advancedToggle.setToggleGroup(modes);
        safeToggle.setText(I18n.t("cleanup.mode.safe"));
        advancedToggle.setText(I18n.t("cleanup.mode.advanced"));
        advancedToggle.getStyleClass().add("cleanup-mode-advanced");
        safeToggle.setSelected(true);
        tip(safeToggle, I18n.t("cleanup.mode.safe.tip"));
        tip(advancedToggle, I18n.t("cleanup.mode.advanced.tip"));
        // A toggle group lets the selected button be clicked off, which here
        // would be "no mode". Clicking the selected one keeps it.
        modes.selectedToggleProperty().addListener((observable, previous, value) -> {
            if (value == null) {
                previous.setSelected(true);
                return;
            }
            advanced = value == advancedToggle;
            showMode();
        });
        HBox switcher = new HBox(0, safeToggle, advancedToggle);
        switcher.getStyleClass().add("cleanup-mode");

        rescanButton.setText(I18n.t("cleanup.rescan"));
        rescanButton.setOnAction(event -> scan(null));

        HBox header = new HBox(16, titles, spacer(), switcher, rescanButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().addAll("header", "cleanup-header");
        return header;
    }

    private HBox buildTiles() {
        freeBar.setMaxWidth(Double.MAX_VALUE);
        freeBar.getStyleClass().add("cleanup-free-bar");
        HBox tiles = new HBox(12,
                tile("cleanup.tile.total", totalValue, totalCaption, null),
                tile("cleanup.tile.reclaim", reclaimValue, reclaimCaption, null),
                tile("cleanup.tile.free", freeValue, freeCaption, freeBar),
                tile("cleanup.tile.largest", largestValue, largestCaption, null));
        reclaimValue.getStyleClass().add("cleanup-tile-good");
        tiles.setPadding(new Insets(14, 16, 4, 16));
        for (Node tile : tiles.getChildren()) {
            HBox.setHgrow(tile, Priority.ALWAYS);
        }
        return tiles;
    }

    private VBox tile(String key, Label value, Label caption, Node extra) {
        Label name = new Label(I18n.t(key));
        name.getStyleClass().add("cleanup-tile-name");
        value.getStyleClass().add("cleanup-tile-value");
        caption.getStyleClass().add("muted");
        VBox tile = new VBox(4, name, value, caption);
        if (extra != null) {
            tile.getChildren().add(extra);
        }
        tile.getStyleClass().add("cleanup-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        tip(tile, I18n.t(key + ".tip"));
        return tile;
    }

    private HBox buildBody() {
        Label usage = new Label(I18n.t("cleanup.chart.title"));
        usage.getStyleClass().add("cleanup-section-title");
        donut.onHover(key -> legendRows.forEach((category, row) ->
                row.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("lifted"),
                        category == key)));
        donut.onClick(key -> reveal((StorageCategory) key));

        Label largest = new Label(I18n.t("cleanup.largest.title"));
        largest.getStyleClass().add("cleanup-section-title");
        tip(largest, I18n.t("cleanup.largest.tip"));

        VBox left = new VBox(10, usage, donut, legend, new Region(), largest, topList);
        left.getStyleClass().add("cleanup-panel");
        left.setPrefWidth(360);
        left.setMinWidth(320);
        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setMinWidth(340);
        leftScroll.setPrefWidth(380);

        buildSafeView();
        buildAdvancedView();
        StackPane right = new StackPane(safeView, advancedView);
        right.getStyleClass().add("cleanup-panel");
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox body = new HBox(12, leftScroll, right);
        body.setPadding(new Insets(10, 16, 10, 16));
        return body;
    }

    private void buildSafeView() {
        Label title = new Label(I18n.t("cleanup.safe.title"));
        title.getStyleClass().add("cleanup-section-title");
        Label intro = new Label(I18n.t("cleanup.safe.intro"));
        intro.setWrapText(true);
        intro.setMinHeight(Region.USE_PREF_SIZE);
        intro.getStyleClass().add("cleanup-intro");

        Hyperlink all = new Hyperlink(I18n.t("cleanup.select.all"));
        all.setOnAction(event -> {
            if (report != null) {
                report.candidates().forEach(candidate -> chosenCandidates.add(candidate.id()));
                rebuildCards();
            }
        });
        Hyperlink none = new Hyperlink(I18n.t("cleanup.select.none"));
        none.setOnAction(event -> {
            chosenCandidates.clear();
            rebuildCards();
        });
        HBox selectors = new HBox(12, all, none);

        cards.setPadding(new Insets(2, 4, 8, 0));
        cardScroll.setFitToWidth(true);
        cardScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(cardScroll, Priority.ALWAYS);

        safeView.getChildren().setAll(title, intro, notices, selectors, cardScroll);
    }

    private void buildAdvancedView() {
        Label warningTitle = new Label(I18n.t("cleanup.advanced.warning.title"));
        warningTitle.getStyleClass().add("cleanup-danger-title");
        Label warning = new Label(I18n.t("cleanup.advanced.warning.body"));
        warning.setWrapText(true);
        warning.setMinHeight(Region.USE_PREF_SIZE);
        warning.getStyleClass().add("cleanup-danger-text");
        understood.setText(I18n.t("cleanup.advanced.understood"));
        understood.getStyleClass().add("cleanup-danger-check");
        understood.selectedProperty().addListener((observable, previous, value) -> {
            if (!value) {
                clearTreeSelection();
            }
            updateAdvancedEnabled();
            updateCleanButton();
        });
        VBox banner = new VBox(6, warningTitle, warning, understood);
        banner.getStyleClass().add("cleanup-danger");

        filter.setPromptText(I18n.t("cleanup.filter.prompt"));
        filter.textProperty().addListener((observable, previous, value) -> rebuildVisible());
        HBox.setHgrow(filter, Priority.ALWAYS);
        Button collapse = new Button(I18n.t("cleanup.collapse"));
        collapse.setOnAction(event -> {
            expanded.clear();
            rebuildVisible();
        });
        Button clear = new Button(I18n.t("cleanup.select.none"));
        clear.setOnAction(event -> clearTreeSelection());
        HBox toolbar = new HBox(8, filter, collapse, clear);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        treeSummary.getStyleClass().add("muted");
        tree.setItems(visible);
        tree.getStyleClass().add("cleanup-tree");
        tree.setCellFactory(view -> new NodeCell());
        tree.setPlaceholder(new Label(I18n.t("cleanup.tree.empty")));
        VBox.setVgrow(tree, Priority.ALWAYS);

        advancedView.getChildren().setAll(banner, toolbar, treeSummary, tree);
        updateAdvancedEnabled();
    }

    private HBox buildFooter() {
        status.getStyleClass().add("cleanup-status");
        progressBar.setPrefWidth(320);
        progressBar.setVisible(false);
        VBox left = new VBox(4, status, progressBar);
        cleanButton.getStyleClass().add("primary");
        cleanButton.setOnAction(event -> confirmAndClean());
        cleanButton.setDisable(true);
        HBox footer = new HBox(12, left, spacer(), cleanButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("footer");
        return footer;
    }

    // ---------------------------------------------------------------- modes

    private void showMode() {
        safeView.setVisible(!advanced);
        safeView.setManaged(!advanced);
        advancedView.setVisible(advanced);
        advancedView.setManaged(advanced);
        stage.getScene().getRoot().pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("advanced"), advanced);
        if (report != null) {
            rebuildLegend();
        }
        updateCleanButton();
    }

    private void updateAdvancedEnabled() {
        boolean on = understood.isSelected() && !working;
        tree.setDisable(!on);
        filter.setDisable(!on);
    }

    // ---------------------------------------------------------------- scanning

    /** Reads the data folder again. {@code after} runs on the interface thread once it is shown. */
    private void scan(Runnable after) {
        setWorking(true, I18n.t("cleanup.scanning"));
        Thread thread = new Thread(() -> {
            try {
                StorageReport scanned = service.scanStorage(new WindowProgress());
                Platform.runLater(() -> {
                    show(scanned);
                    setWorking(false, I18n.t("cleanup.scanned",
                            BuildDialogs.size(scanned.totalBytes()), scanned.totalFiles()));
                    if (after != null) {
                        after.run();
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Platform.runLater(() -> setWorking(false, ""));
            } catch (RuntimeException e) {
                com.hexadron.launcher.core.LauncherLog.error("Storage scan failed", e);
                Platform.runLater(() -> setWorking(false, I18n.t("cleanup.scanFailed",
                        e.getMessage() == null ? e.toString() : e.getMessage())));
            }
        }, "hexadron-storage-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(StorageReport scanned) {
        this.report = scanned;
        // A new scan is new rows: what was ticked referred to the old ones.
        Set<String> kept = new HashSet<>(chosenCandidates);
        chosenCandidates.clear();
        boolean first = kept.isEmpty();
        for (CleanupCandidate candidate : scanned.candidates()) {
            if (first || kept.contains(candidate.id())) {
                chosenCandidates.add(candidate.id());
            }
        }
        chosenLeaves.clear();
        expanded.clear();
        counts.clear();
        count(scanned.root());

        totalValue.setText(BuildDialogs.size(scanned.totalBytes()));
        totalCaption.setText(I18n.t("cleanup.tile.total.caption", scanned.totalFiles()));
        reclaimValue.setText(BuildDialogs.size(scanned.reclaimableBytes()));
        reclaimCaption.setText(I18n.t("cleanup.tile.reclaim.caption", scanned.candidates().size()));
        if (scanned.usableBytes() >= 0 && scanned.diskBytes() > 0) {
            freeValue.setText(BuildDialogs.size(scanned.usableBytes()));
            freeCaption.setText(I18n.t("cleanup.tile.free.caption", BuildDialogs.size(scanned.diskBytes())));
            freeBar.setProgress(1 - (double) scanned.usableBytes() / scanned.diskBytes());
        } else {
            freeValue.setText("?");
            freeCaption.setText(I18n.t("cleanup.tile.free.unknown"));
            freeBar.setProgress(0);
        }
        StorageNode biggest = scanned.root().children().stream()
                .max(Comparator.comparingLong(StorageNode::size)).orElse(null);
        largestValue.setText(biggest == null ? "-" : I18n.t(biggest.category().key()));
        largestCaption.setText(biggest == null ? "" : BuildDialogs.size(biggest.size())
                + "  ·  " + share(biggest.size(), scanned.totalBytes()));

        List<DonutChart.Slice> slices = new ArrayList<>();
        for (StorageNode category : scanned.root().children()) {
            slices.add(new DonutChart.Slice(I18n.t(category.category().key()), category.size(),
                    color(category.category()), category.category(), BuildDialogs.size(category.size())));
        }
        donut.setSlices(slices, BuildDialogs.size(scanned.totalBytes()), I18n.t("cleanup.chart.center"));
        rebuildLegend();
        rebuildTop();
        rebuildNotices();
        rebuildCards();
        rebuildVisible();
        updateCleanButton();
    }

    // ---------------------------------------------------------------- left panel

    private void rebuildLegend() {
        legend.getChildren().clear();
        legendRows.clear();
        if (report == null) {
            return;
        }
        Map<StorageCategory, Long> reclaim = new EnumMap<>(StorageCategory.class);
        report.candidates().forEach(candidate -> reclaim.merge(candidate.category(), candidate.size(), Long::sum));
        for (StorageNode category : report.root().children()) {
            StorageCategory key = category.category();
            Region swatch = swatch(color(key), 12);
            Label name = new Label(I18n.t(key.key()));
            name.getStyleClass().add("cleanup-legend-name");
            Label size = new Label(BuildDialogs.size(category.size()));
            size.getStyleClass().add("cleanup-size");
            Label percent = new Label(share(category.size(), report.totalBytes()));
            percent.getStyleClass().add("muted");
            percent.setMinWidth(40);
            percent.setAlignment(Pos.CENTER_RIGHT);
            HBox row = new HBox(8, swatch, name, spacer(), size, percent);
            long free = reclaim.getOrDefault(key, 0L);
            VBox cell = new VBox(1, row);
            if (!advanced && free > 0) {
                Label reclaimable = new Label(I18n.t("cleanup.legend.reclaim", BuildDialogs.size(free)));
                reclaimable.getStyleClass().add("cleanup-legend-reclaim");
                reclaimable.setPadding(new Insets(0, 0, 0, 20));
                cell.getChildren().add(reclaimable);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            HBox wrapper = new HBox(cell);
            HBox.setHgrow(cell, Priority.ALWAYS);
            wrapper.getStyleClass().add("cleanup-legend-row");
            wrapper.setOnMouseEntered(event -> donut.highlight(key));
            wrapper.setOnMouseExited(event -> donut.highlight(null));
            wrapper.setOnMouseClicked(event -> reveal(key));
            tip(wrapper, I18n.t(category.descriptionKey()));
            legendRows.put(key, wrapper);
            legend.getChildren().add(wrapper);
        }
    }

    private void rebuildTop() {
        topList.getChildren().clear();
        List<StorageNode> items = new ArrayList<>();
        for (StorageNode category : report.root().children()) {
            items.addAll(category.children());
        }
        items.sort(Comparator.comparingLong(StorageNode::size).reversed());
        long max = items.isEmpty() ? 1 : Math.max(1, items.get(0).size());
        for (StorageNode node : items.subList(0, Math.min(TOP, items.size()))) {
            Label name = new Label(node.name());
            name.getStyleClass().add("cleanup-top-name");
            name.setMaxWidth(210);
            Label size = new Label(BuildDialogs.size(node.size()));
            size.getStyleClass().add("cleanup-size");
            HBox line = new HBox(8, swatch(color(node.category()), 8), name, spacer(), size);
            line.setAlignment(Pos.CENTER_LEFT);
            Region fill = new Region();
            fill.getStyleClass().add("cleanup-bar-fill");
            fill.setStyle("-fx-background-color: " + web(color(node.category())) + ";");
            Region track = new Region();
            track.getStyleClass().add("cleanup-bar-track");
            StackPane bar = new StackPane(track, fill);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setMinHeight(6);
            bar.setMaxHeight(6);
            double fraction = (double) node.size() / max;
            fill.maxWidthProperty().bind(bar.widthProperty().multiply(Math.max(0.02, fraction)));
            track.setMaxWidth(Double.MAX_VALUE);
            VBox row = new VBox(4, line, bar);
            row.getStyleClass().add("cleanup-top-row");
            tip(row, describe(node));
            row.setOnMouseClicked(event -> {
                if (advanced) {
                    revealNode(node);
                }
            });
            topList.getChildren().add(row);
        }
    }

    private void rebuildNotices() {
        notices.getChildren().clear();
        for (String key : report.notes()) {
            Label notice = new Label(I18n.t(key));
            notice.setWrapText(true);
            notice.setMinHeight(Region.USE_PREF_SIZE);
            notice.getStyleClass().add("cleanup-notice");
            notices.getChildren().add(notice);
        }
    }

    /** From the chart or the legend: show that category in whichever mode is open. */
    private void reveal(StorageCategory category) {
        if (report == null || category == null) {
            return;
        }
        if (advanced) {
            report.root().children().stream().filter(node -> node.category() == category)
                    .findFirst().ifPresent(this::revealNode);
            return;
        }
        for (CleanupCandidate candidate : report.candidates()) {
            if (candidate.category() == category) {
                Node card = cardById.get(candidate.id());
                if (card != null) {
                    scrollTo(cardScroll, cards, card);
                    card.getStyleClass().add("cleanup-card-flash");
                    javafx.animation.PauseTransition pause =
                            new javafx.animation.PauseTransition(Duration.millis(900));
                    pause.setOnFinished(event -> card.getStyleClass().remove("cleanup-card-flash"));
                    pause.play();
                }
                return;
            }
        }
        status.setText(I18n.t("cleanup.nothingIn", I18n.t(category.key())));
    }

    // ---------------------------------------------------------------- safe mode

    private void rebuildCards() {
        cards.getChildren().clear();
        cardById.clear();
        if (report == null) {
            return;
        }
        if (report.candidates().isEmpty()) {
            Label clean = new Label(I18n.t("cleanup.safe.empty"));
            clean.getStyleClass().add("cleanup-empty");
            clean.setWrapText(true);
            cards.getChildren().add(clean);
            updateCleanButton();
            return;
        }
        for (CleanupCandidate candidate : report.candidates()) {
            Node card = card(candidate);
            cardById.put(candidate.id(), card);
            cards.getChildren().add(card);
        }
        updateCleanButton();
    }

    private Node card(CleanupCandidate candidate) {
        CheckBox box = new CheckBox();
        box.setSelected(chosenCandidates.contains(candidate.id()));
        box.selectedProperty().addListener((observable, previous, value) -> {
            if (value) {
                chosenCandidates.add(candidate.id());
            } else {
                chosenCandidates.remove(candidate.id());
            }
            updateCleanButton();
        });

        Label title = new Label(I18n.t(candidate.titleKey()));
        title.getStyleClass().add("cleanup-card-title");
        Label reason = new Label(I18n.t(candidate.reasonKey(), candidate.reasonArgs()));
        reason.setWrapText(true);
        reason.setMinHeight(Region.USE_PREF_SIZE);
        reason.getStyleClass().add("cleanup-card-reason");
        Label category = new Label(I18n.t(candidate.category().key()));
        category.getStyleClass().add("cleanup-chip");
        category.setStyle("-fx-border-color: " + web(color(candidate.category())) + ";");
        HBox titleLine = new HBox(8, title, category);
        titleLine.setAlignment(Pos.CENTER_LEFT);
        VBox texts = new VBox(3, titleLine, reason);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Label size = new Label(BuildDialogs.size(candidate.size()));
        size.getStyleClass().add("cleanup-card-size");
        Label files = new Label(I18n.t("cleanup.files", candidate.files()));
        files.getStyleClass().add("muted");
        VBox sizes = new VBox(2, size, files);
        sizes.setAlignment(Pos.CENTER_RIGHT);
        sizes.setMinWidth(Region.USE_PREF_SIZE);

        Region stripe = new Region();
        stripe.getStyleClass().add("cleanup-card-stripe");
        stripe.setStyle("-fx-background-color: " + web(color(candidate.category())) + ";");

        HBox top = new HBox(12, box, texts, sizes);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox details = new VBox(3);
        details.getStyleClass().add("cleanup-details");
        details.setVisible(false);
        details.setManaged(false);
        Hyperlink more = new Hyperlink(I18n.t("cleanup.details.show", candidate.details().size()));
        more.setOnAction(event -> {
            boolean open = !details.isVisible();
            if (open && details.getChildren().isEmpty()) {
                fillDetails(details, candidate);
            }
            details.setVisible(open);
            details.setManaged(open);
            more.setText(I18n.t(open ? "cleanup.details.hide" : "cleanup.details.show",
                    candidate.details().size()));
        });

        VBox body = new VBox(6, top, more, details);
        HBox.setHgrow(body, Priority.ALWAYS);
        HBox card = new HBox(12, stripe, body);
        card.getStyleClass().add("cleanup-card");
        tip(card, I18n.t(candidate.tipKey()));
        return card;
    }

    private static void fillDetails(VBox into, CleanupCandidate candidate) {
        List<CleanupCandidate.Detail> lines = candidate.details();
        for (CleanupCandidate.Detail detail : lines.subList(0, Math.min(DETAILS, lines.size()))) {
            Label name = new Label(detail.name());
            name.getStyleClass().add("cleanup-detail-name");
            Label size = new Label(BuildDialogs.size(detail.size()));
            size.getStyleClass().add("muted");
            HBox line = new HBox(8, name, spacer(), size);
            into.getChildren().add(line);
        }
        if (lines.size() > DETAILS) {
            Label rest = new Label(I18n.t("build.custom.more", lines.size() - DETAILS));
            rest.getStyleClass().add("muted");
            into.getChildren().add(rest);
        }
    }

    private List<CleanupCandidate> chosenCandidates() {
        List<CleanupCandidate> chosen = new ArrayList<>();
        if (report != null) {
            for (CleanupCandidate candidate : report.candidates()) {
                if (chosenCandidates.contains(candidate.id())) {
                    chosen.add(candidate);
                }
            }
        }
        return chosen;
    }

    // ---------------------------------------------------------------- advanced mode

    private Counts count(StorageNode node) {
        Counts totals = new Counts();
        if (!node.hasChildren()) {
            if (node.isDeletable()) {
                totals.selectable = 1;
            } else {
                totals.locked = 1;
            }
        } else {
            for (StorageNode child : node.children()) {
                Counts inner = count(child);
                totals.selectable += inner.selectable;
                totals.locked += inner.locked;
            }
        }
        counts.put(node, totals);
        return totals;
    }

    private void setChosen(StorageNode node, boolean value) {
        List<StorageNode> leaves = new ArrayList<>();
        collectLeaves(node, leaves);
        for (StorageNode leaf : leaves) {
            if (!leaf.isDeletable() || chosenLeaves.contains(leaf) == value) {
                continue;
            }
            if (value) {
                chosenLeaves.add(leaf);
            } else {
                chosenLeaves.remove(leaf);
            }
            for (StorageNode at = leaf; at != null; at = at.parent()) {
                Counts totals = counts.get(at);
                if (totals == null) {
                    continue;
                }
                totals.chosen += value ? 1 : -1;
                totals.chosenBytes += value ? leaf.size() : -leaf.size();
            }
        }
        tree.refresh();
        updateCleanButton();
    }

    private static void collectLeaves(StorageNode node, List<StorageNode> into) {
        if (!node.hasChildren()) {
            into.add(node);
            return;
        }
        node.children().forEach(child -> collectLeaves(child, into));
    }

    private void clearTreeSelection() {
        if (report == null) {
            return;
        }
        setChosen(report.root(), false);
    }

    /**
     * The fewest deletions that cover what is ticked.
     *
     * <p>A folder with every row under it ticked goes as one folder - faster, and
     * nothing is left behind that the tree did not list. A folder with a locked
     * row in it never goes whole, however it is ticked.
     */
    private void collect(StorageNode node, List<StorageNode> into) {
        Counts totals = counts.get(node);
        if (totals == null || totals.chosen == 0) {
            return;
        }
        if (!node.hasChildren()) {
            into.add(node);
            return;
        }
        if (node.isDeletable() && totals.chosen == totals.selectable && totals.locked == 0) {
            into.add(node);
            return;
        }
        node.children().forEach(child -> collect(child, into));
    }

    private void rebuildVisible() {
        visible.clear();
        if (report == null) {
            return;
        }
        String query = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        for (StorageNode category : report.root().children()) {
            addVisible(category, query);
        }
        treeSummary.setText(I18n.t("cleanup.tree.summary", visible.size()));
    }

    private void addVisible(StorageNode node, String query) {
        if (query.isEmpty()) {
            visible.add(node);
            if (expanded.contains(node)) {
                node.children().forEach(child -> addVisible(child, query));
            }
            return;
        }
        if (!matches(node, query)) {
            return;
        }
        visible.add(node);
        node.children().forEach(child -> addVisible(child, query));
    }

    private boolean matches(StorageNode node, String query) {
        if (label(node).toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (StorageNode child : node.children()) {
            if (matches(child, query)) {
                return true;
            }
        }
        return false;
    }

    private void toggleExpanded(StorageNode node) {
        if (!node.hasChildren()) {
            return;
        }
        if (!expanded.remove(node)) {
            expanded.add(node);
        }
        rebuildVisible();
    }

    private void revealNode(StorageNode node) {
        if (!understood.isSelected()) {
            status.setText(I18n.t("cleanup.advanced.firstUnderstand"));
            return;
        }
        filter.clear();
        for (StorageNode at = node.parent(); at != null; at = at.parent()) {
            expanded.add(at);
        }
        expanded.add(node);
        rebuildVisible();
        int index = visible.indexOf(node);
        if (index >= 0) {
            tree.scrollTo(Math.max(0, index - 2));
            tree.getSelectionModel().select(index);
        }
    }

    /** One row of the tree. */
    private final class NodeCell extends ListCell<StorageNode> {

        private final Region indent = new Region();
        private final Label arrow = new Label();
        private final CheckBox box = new CheckBox();
        private final Region dot = new Region();
        private final Label name = new Label();
        private final Label sub = new Label();
        private final Label locked = new Label();
        private final Region fill = new Region();
        private final StackPane bar;
        private final Label size = new Label();
        private final HBox row;
        private final Tooltip tooltip = new Tooltip();
        private boolean updating;

        NodeCell() {
            arrow.getStyleClass().add("cleanup-arrow");
            arrow.setMinWidth(16);
            arrow.setOnMouseClicked(event -> {
                if (getItem() != null) {
                    toggleExpanded(getItem());
                }
                event.consume();
            });
            box.setOnAction(event -> {
                if (!updating && getItem() != null) {
                    setChosen(getItem(), box.isSelected());
                }
            });
            dot.getStyleClass().add("cleanup-dot");
            name.getStyleClass().add("cleanup-row-name");
            sub.getStyleClass().add("cleanup-row-sub");
            locked.getStyleClass().add("cleanup-locked");
            locked.setText(I18n.t("cleanup.locked"));
            VBox texts = new VBox(1, name, sub);
            HBox.setHgrow(texts, Priority.ALWAYS);
            texts.setMinWidth(0);
            Region track = new Region();
            track.getStyleClass().add("cleanup-bar-track");
            fill.getStyleClass().add("cleanup-bar-fill");
            bar = new StackPane(track, fill);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setMinSize(90, 6);
            bar.setPrefSize(90, 6);
            bar.setMaxSize(90, 6);
            size.getStyleClass().add("cleanup-size");
            size.setMinWidth(78);
            size.setAlignment(Pos.CENTER_RIGHT);
            row = new HBox(8, indent, arrow, box, dot, texts, locked, bar, size);
            row.setAlignment(Pos.CENTER_LEFT);
            tooltip.getStyleClass().add("cleanup-tooltip");
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(440);
            tooltip.setShowDelay(Duration.millis(350));
            tooltip.setShowDuration(Duration.seconds(30));
            setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && getItem() != null) {
                    toggleExpanded(getItem());
                }
            });
        }

        @Override
        protected void updateItem(StorageNode node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            int depth = node.depth();
            indent.setMinWidth(depth * 18);
            indent.setPrefWidth(depth * 18);
            arrow.setText(node.hasChildren() ? (expanded.contains(node)
                    || !filter.getText().isBlank() ? "▾" : "▸") : "");

            Counts totals = counts.get(node);
            updating = true;
            boolean selectable = totals != null && totals.selectable > 0;
            box.setDisable(!selectable);
            box.setSelected(totals != null && totals.selectable > 0 && totals.chosen == totals.selectable);
            box.setIndeterminate(totals != null && totals.chosen > 0 && totals.chosen < totals.selectable);
            updating = false;
            locked.setVisible(!selectable);
            locked.setManaged(!selectable);

            dot.setStyle("-fx-background-color: " + web(color(node.category())) + ";");
            name.setText(label(node));
            name.getStyleClass().removeAll("cleanup-row-heading");
            if (depth == 0) {
                name.getStyleClass().add("cleanup-row-heading");
            }
            String note = node.noteKey() == null ? null : I18n.t(node.noteKey(), node.noteArgs());
            String chosenText = totals != null && totals.chosen > 0 && node.hasChildren()
                    ? I18n.t("cleanup.row.chosen", BuildDialogs.size(totals.chosenBytes)) : null;
            String line = note == null ? chosenText
                    : chosenText == null ? note : note + "  ·  " + chosenText;
            sub.setText(line == null ? "" : line);
            sub.setVisible(line != null);
            sub.setManaged(line != null);
            sub.getStyleClass().removeAll("cleanup-row-unused");
            if ("cleanup.note.unused".equals(node.noteKey()) || "cleanup.note.noProfile".equals(node.noteKey())) {
                sub.getStyleClass().add("cleanup-row-unused");
            }

            long reference = node.parent() == null || node.parent().size() <= 0
                    ? Math.max(1, report == null ? 1 : report.totalBytes()) : node.parent().size();
            if (depth == 0 && report != null) {
                reference = Math.max(1, report.totalBytes());
            }
            fill.setStyle("-fx-background-color: " + web(color(node.category())) + ";");
            fill.setMaxWidth(90 * Math.max(0.02, Math.min(1, (double) node.size() / reference)));
            size.setText(BuildDialogs.size(node.size()));

            tooltip.setText(describe(node));
            setTooltip(tooltip);
            setGraphic(row);
        }
    }

    // ---------------------------------------------------------------- cleaning

    private void updateCleanButton() {
        if (working || report == null) {
            cleanButton.setDisable(true);
            cleanButton.setText(I18n.t("cleanup.clean.nothing"));
            return;
        }
        long bytes;
        int items;
        if (advanced) {
            Counts totals = counts.get(report.root());
            items = totals == null ? 0 : totals.chosen;
            bytes = totals == null ? 0 : totals.chosenBytes;
            if (!understood.isSelected()) {
                items = 0;
            }
        } else {
            List<CleanupCandidate> chosen = chosenCandidates();
            items = chosen.size();
            bytes = chosen.stream().mapToLong(CleanupCandidate::size).sum();
        }
        cleanButton.setDisable(items == 0);
        cleanButton.getStyleClass().removeAll("cleanup-danger-button", "primary");
        cleanButton.getStyleClass().add(advanced ? "cleanup-danger-button" : "primary");
        cleanButton.setText(items == 0 ? I18n.t("cleanup.clean.nothing")
                : I18n.t(advanced ? "cleanup.clean.advanced" : "cleanup.clean.safe", BuildDialogs.size(bytes)));
    }

    private void confirmAndClean() {
        if (report == null || working) {
            return;
        }
        String blocked = host.blockedReason();
        if (blocked != null) {
            alert(Alert.AlertType.WARNING, I18n.t("cleanup.blocked.header"), blocked);
            return;
        }
        List<CleanupAction> actions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        long bytes = 0;
        boolean gameData = false;
        List<String> profiles = new ArrayList<>();
        if (advanced) {
            List<StorageNode> nodes = new ArrayList<>();
            collect(report.root(), nodes);
            for (StorageNode node : nodes) {
                // A whole profile folder is a whole profile: it leaves the list
                // too, rather than staying there pointing at nothing.
                if (node.profileId() != null) {
                    actions.add(CleanupAction.profile(node.profileId(), node.path(), node.size()));
                    profiles.add(label(node));
                } else {
                    actions.add(CleanupAction.tree(node.path(), node.size()));
                }
                names.add(label(node) + "  (" + BuildDialogs.size(node.size()) + ")");
                bytes += node.size();
                gameData |= node.category() == StorageCategory.INSTANCES;
            }
        } else {
            for (CleanupCandidate candidate : chosenCandidates()) {
                actions.add(candidate.action());
                names.add(I18n.t(candidate.titleKey()) + "  (" + BuildDialogs.size(candidate.size()) + ")");
                bytes += candidate.size();
            }
        }
        if (actions.isEmpty()) {
            return;
        }
        if (!confirm(names, bytes, gameData, profiles)) {
            return;
        }
        long before = report.totalBytes();
        host.cleaningStarted();
        setWorking(true, I18n.t("cleanup.cleaning"));
        Thread thread = new Thread(() -> {
            StorageCleaner.Result result = null;
            try {
                result = service.cleanStorage(actions, new WindowProgress());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                com.hexadron.launcher.core.LauncherLog.error("Storage cleanup failed", e);
            }
            StorageCleaner.Result done = result;
            Platform.runLater(() -> {
                host.cleaningFinished();
                setWorking(false, "");
                scan(() -> reportResult(done, before));
            });
        }, "hexadron-storage-clean");
        thread.setDaemon(true);
        thread.start();
    }

    private void reportResult(StorageCleaner.Result result, long before) {
        long freed = Math.max(0, before - (report == null ? before : report.totalBytes()));
        if (result == null) {
            status.setText(I18n.t("cleanup.failed"));
            alert(Alert.AlertType.ERROR, I18n.t("cleanup.failed"), I18n.t("cleanup.failed.body"));
            return;
        }
        String profiles = result.profilesRemoved() > 0
                ? "\n" + I18n.t("cleanup.done.profiles", result.profilesRemoved()) : "";
        status.setText(I18n.t("cleanup.done", BuildDialogs.size(freed)) + profiles.replace('\n', ' '));
        if (!result.isComplete()) {
            StringBuilder text = new StringBuilder(I18n.t("cleanup.partial.body",
                    BuildDialogs.size(freed), result.failed().size()));
            text.insert(0, profiles.isEmpty() ? "" : profiles.substring(1) + "\n\n");
            result.failed().stream().limit(12).forEach(path -> text.append("\n• ").append(path));
            if (result.failed().size() > 12) {
                text.append("\n").append(I18n.t("build.custom.more", result.failed().size() - 12));
            }
            alert(Alert.AlertType.WARNING, I18n.t("cleanup.partial.header"), text.toString());
        } else {
            alert(Alert.AlertType.INFORMATION, I18n.t("cleanup.done.header"),
                    I18n.t("cleanup.done.body", BuildDialogs.size(freed)) + profiles);
        }
    }

    /**
     * The last question.
     *
     * <p>In the advanced mode it cannot be answered with Enter: the button that
     * deletes stays off until the box under the list is ticked, because a
     * dialog that one key dismisses is a dialog that gets dismissed unread.
     */
    private boolean confirm(List<String> names, long bytes, boolean gameData, List<String> profiles) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(I18n.t("cleanup.confirm.title"));
        dialog.setHeaderText(I18n.t(advanced ? "cleanup.confirm.header.advanced" : "cleanup.confirm.header.safe",
                names.size(), BuildDialogs.size(bytes)));

        StringBuilder list = new StringBuilder();
        names.stream().limit(14).forEach(name -> list.append("• ").append(name).append('\n'));
        if (names.size() > 14) {
            list.append(I18n.t("build.custom.more", names.size() - 14));
        }
        Label listed = new Label(list.toString().stripTrailing());
        listed.setWrapText(true);
        Label body = new Label(I18n.t(advanced ? "cleanup.confirm.body.advanced" : "cleanup.confirm.body.safe"));
        body.setWrapText(true);
        VBox content = new VBox(10, body, listed);
        CheckBox sure = new CheckBox(I18n.t("cleanup.confirm.sure"));
        if (advanced) {
            if (gameData) {
                Label danger = new Label(I18n.t("cleanup.confirm.gameData"));
                danger.setWrapText(true);
                danger.getStyleClass().add("cleanup-danger-text");
                VBox box = new VBox(danger);
                box.getStyleClass().add("cleanup-danger");
                content.getChildren().add(0, box);
            }
            if (!profiles.isEmpty()) {
                Label removed = new Label(I18n.t("cleanup.confirm.profiles", String.join(", ", profiles)));
                removed.setWrapText(true);
                removed.setMinHeight(Region.USE_PREF_SIZE);
                removed.getStyleClass().add("cleanup-notice");
                content.getChildren().add(removed);
            }
            content.getChildren().add(sure);
        }
        content.setPadding(new Insets(12, 16, 6, 16));

        ButtonType delete = new ButtonType(I18n.t(advanced ? "cleanup.confirm.delete.advanced"
                : "cleanup.confirm.delete.safe"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(cancel, delete);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(620);
        Theme.apply(dialog.getDialogPane());
        Node deleteButton = dialog.getDialogPane().lookupButton(delete);
        deleteButton.getStyleClass().add(advanced ? "cleanup-danger-button" : "primary");
        if (advanced) {
            deleteButton.disableProperty().bind(sure.selectedProperty().not());
            ((Button) dialog.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        }
        return dialog.showAndWait().filter(button -> button == delete).isPresent();
    }

    // ---------------------------------------------------------------- plumbing

    private void setWorking(boolean value, String text) {
        working = value;
        progressBar.setVisible(value);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        rescanButton.setDisable(value);
        safeToggle.setDisable(value);
        advancedToggle.setDisable(value);
        cards.setDisable(value);
        understood.setDisable(value);
        updateAdvancedEnabled();
        if (text != null) {
            status.setText(text);
        }
        updateCleanButton();
    }

    /** Progress into this window's own bar, in the player's language. */
    private final class WindowProgress implements Progress {

        private final AtomicLong last = new AtomicLong();

        @Override
        public void stage(String name) {
            String text;
            if (name.startsWith("scan:")) {
                text = I18n.t("cleanup.scan." + name.substring(5).toLowerCase(Locale.ROOT));
            } else if (name.startsWith("delete:")) {
                text = I18n.t("cleanup.progress.deleting", name.substring(7));
            } else {
                text = name;
            }
            Platform.runLater(() -> {
                status.setText(text);
                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
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
            long now = System.currentTimeMillis();
            long previous = last.get();
            if (completed < total && (now - previous < 60 || !last.compareAndSet(previous, now))) {
                return;
            }
            double fraction = (double) completed / total;
            Platform.runLater(() -> progressBar.setProgress(fraction));
        }

        @Override
        public void log(String message) {
            com.hexadron.launcher.core.LauncherLog.info(message);
        }
    }

    /** What a row is called: the category's name for a heading, its own otherwise. */
    private static String label(StorageNode node) {
        return node.path() == null ? I18n.t(node.name()) : node.name();
    }

    /** The hover text: what it is, where it is, what it weighs, who uses it. */
    private String describe(StorageNode node) {
        StringBuilder text = new StringBuilder();
        text.append(label(node)).append("\n\n");
        text.append(I18n.t(node.descriptionKey(), node.descriptionArgs()));
        if (node.noteKey() != null) {
            text.append("\n\n").append(I18n.t(node.noteKey(), node.noteArgs()));
        }
        text.append("\n\n").append(I18n.t("cleanup.tip.size", BuildDialogs.size(node.size()), node.files()));
        if (node.path() != null) {
            Path root = service.dirs().root();
            Path shown = node.path().startsWith(root) ? root.relativize(node.path()) : node.path();
            text.append("\n").append(I18n.t("cleanup.tip.path", shown.toString().replace('\\', '/')));
        }
        if (!node.isDeletable() && node.path() != null && !node.hasChildren()) {
            text.append("\n\n").append(I18n.t("cleanup.tip.locked"));
        }
        return text.toString();
    }

    private static Color color(StorageCategory category) {
        int index = category.ordinal();
        return category == StorageCategory.OTHER || index >= PALETTE.length ? NEUTRAL : PALETTE[index];
    }

    private static String web(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255), (int) Math.round(color.getBlue() * 255));
    }

    private static String share(long part, long whole) {
        if (whole <= 0) {
            return "0%";
        }
        double percent = part * 100.0 / whole;
        return percent > 0 && percent < 1 ? "<1%" : Math.round(percent) + "%";
    }

    private static Region swatch(Color color, double size) {
        Region swatch = new Region();
        swatch.getStyleClass().add("cleanup-swatch");
        swatch.setMinSize(size, size);
        swatch.setMaxSize(size, size);
        swatch.setStyle("-fx-background-color: " + web(color) + ";");
        return swatch;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static void tip(Node node, String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.getStyleClass().add("cleanup-tooltip");
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        tooltip.setShowDelay(Duration.millis(350));
        tooltip.setShowDuration(Duration.seconds(30));
        Tooltip.install(node, tooltip);
    }

    private static void scrollTo(ScrollPane scroll, VBox content, Node child) {
        double contentHeight = content.getBoundsInLocal().getHeight();
        double viewport = scroll.getViewportBounds().getHeight();
        if (contentHeight <= viewport) {
            return;
        }
        double y = child.getBoundsInParent().getMinY();
        scroll.setVvalue(Math.max(0, Math.min(1, y / (contentHeight - viewport))));
    }

    private void alert(Alert.AlertType type, String header, String message) {
        Alert alert = new Alert(type, message);
        alert.initOwner(stage);
        Theme.apply(alert.getDialogPane());
        alert.setHeaderText(header);
        alert.setTitle(header);
        alert.getDialogPane().setPrefWidth(600);
        alert.showAndWait();
    }
}
