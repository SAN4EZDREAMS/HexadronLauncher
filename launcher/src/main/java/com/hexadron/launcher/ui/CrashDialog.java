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

import com.hexadron.launcher.crash.CrashAnalyzer;
import com.hexadron.launcher.crash.CrashEvidence;
import com.hexadron.launcher.crash.CrashFix;
import com.hexadron.launcher.crash.CrashFixes;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.mods.ModEntry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * What the player sees after a game stops with an error: the cause in plain
 * words, and the fixes the launcher can apply with one click.
 *
 * <p>Not modal. The player may want to open the mods window, read the crash
 * report or look something up before choosing a fix, and a dialog that holds
 * the launcher hostage while they do is one they close unread.
 *
 * <p>When no rule matched, the window says so plainly and points at the crash
 * report, the game logs and the bug report window. "Unknown error" with
 * nothing to do next is exactly the answer this window exists to replace.
 */
final class CrashDialog {

    /** What the dialog asks the main window to do. */
    interface Actions {
        /** Applies a fix off the interface thread; calls back on the interface thread. */
        void applyFix(CrashFixes.Prepared fix, Runnable onDone, Consumer<String> onFailure);

        void playAgain();

        void reportBug();

        /** Opens the search for the mod that causes the crash. */
        void findProblemMod();
    }

    private static final double WIDTH = 600;
    private static final double MAX_HEIGHT = 540;

    private final int exitCode;
    private final List<CrashAnalyzer.Diagnosis> diagnoses;
    private final Map<CrashAnalyzer.Diagnosis, List<CrashFixes.Prepared>> fixes;
    private final CrashEvidence evidence;
    private final Path gameDir;
    private final Actions actions;

    CrashDialog(int exitCode, List<CrashAnalyzer.Diagnosis> diagnoses,
                Map<CrashAnalyzer.Diagnosis, List<CrashFixes.Prepared>> fixes,
                CrashEvidence evidence, Path gameDir, Actions actions) {
        this.exitCode = exitCode;
        this.diagnoses = List.copyOf(diagnoses);
        this.fixes = Map.copyOf(fixes);
        this.evidence = evidence;
        this.gameDir = gameDir;
        this.actions = actions;
    }

    void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(javafx.stage.Modality.NONE);
        dialog.setTitle(I18n.t("crash.title"));
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        ButtonType again = new ButtonType(I18n.t("crash.playAgain"), ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType(I18n.t("dialog.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(again, close);

        VBox content = build(dialog, again);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("edge-to-edge");
        // As tall as the causes need and no taller, up to a limit; past it, scroll.
        // A fixed height left half the window empty for one cause.
        content.heightProperty().addListener((observable, before, height) -> {
            scroll.setPrefViewportHeight(Math.min(MAX_HEIGHT, height.doubleValue()));
            Window window = dialog.getDialogPane().getScene() == null ? null
                    : dialog.getDialogPane().getScene().getWindow();
            if (window != null) {
                window.sizeToScene();
            }
        });
        dialog.getDialogPane().setContent(scroll);
        Theme.apply(dialog.getDialogPane());

        dialog.setOnHidden(event -> {
            if (again.equals(dialog.getResult())) {
                actions.playAgain();
            }
        });
        dialog.show();
    }

    private VBox build(Dialog<ButtonType> dialog, ButtonType again) {
        VBox root = new VBox(14);
        root.setPadding(new Insets(18, 22, 10, 22));
        root.setPrefWidth(WIDTH);

        Label heading = new Label(I18n.t(diagnoses.isEmpty()
                ? "crash.heading.unknown" : "crash.title"));
        heading.getStyleClass().add("section-title");
        Label exit = muted(I18n.t("crash.exit", String.valueOf(exitCode)));
        root.getChildren().addAll(heading, exit);

        if (diagnoses.isEmpty()) {
            root.getChildren().add(wrapped(I18n.t("crash.unknown.body")));
        }
        for (CrashAnalyzer.Diagnosis diagnosis : diagnoses) {
            root.getChildren().addAll(new Separator(), card(diagnosis, dialog, again));
        }

        root.getChildren().addAll(new Separator(), links());
        return root;
    }

    private VBox card(CrashAnalyzer.Diagnosis diagnosis, Dialog<ButtonType> dialog, ButtonType again) {
        Label title = new Label(diagnosis.title());
        title.getStyleClass().add("section-title");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);
        title.setMaxWidth(WIDTH - 48);

        VBox card = new VBox(6, title, wrapped(diagnosis.cause()), wrapped(diagnosis.advice()));
        if (diagnosis.line() != null && !diagnosis.line().isBlank()) {
            Label found = muted(I18n.t("crash.evidence", diagnosis.line()));
            found.setStyle("-fx-font-family: monospace; -fx-font-size: 0.85em;");
            card.getChildren().add(found);
        }

        List<CrashFixes.Prepared> offered = fixes.getOrDefault(diagnosis, List.of());
        if (!offered.isEmpty()) {
            FlowPane buttons = new FlowPane(8, 8);
            buttons.setPadding(new Insets(4, 0, 0, 0));
            Label status = muted("");
            status.setVisible(false);
            status.setManaged(false);
            for (CrashFixes.Prepared fix : offered) {
                buttons.getChildren().add(fixButton(fix, status, dialog, again));
            }
            card.getChildren().addAll(buttons, status);
        }
        return card;
    }

    private Button fixButton(CrashFixes.Prepared fix, Label status, Dialog<ButtonType> dialog,
                             ButtonType again) {
        Button button = new Button(label(fix));
        button.setOnAction(event -> {
            if (!fix.dependents().isEmpty() && !confirmDependents(fix, dialog.getOwner())) {
                return;
            }
            button.setDisable(true);
            actions.applyFix(fix, () -> {
                button.setText("✓ " + label(fix));
                show(status, I18n.t("crash.fix.done"));
                // The next step is plainly to try again; make it the obvious button.
                dialog.getDialogPane().lookupButton(again).getStyleClass().add("primary");
            }, failure -> {
                button.setDisable(false);
                show(status, I18n.t("crash.fix.failed", failure));
            });
        });
        return button;
    }

    private boolean confirmDependents(CrashFixes.Prepared fix, Window owner) {
        String names = fix.dependents().stream().map(CrashDialog::nameOf)
                .collect(Collectors.joining(", "));
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, I18n.t("crash.fix.also", names),
                ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setHeaderText(label(fix));
        alert.setTitle(I18n.t("crash.title"));
        Theme.apply(alert.getDialogPane());
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    /** The words on a fix button. */
    static String label(CrashFixes.Prepared fix) {
        CrashFix.Kind kind = fix.fix().kind();
        return switch (kind) {
            case DISABLE_MOD, DISABLE_FILE, DISABLE_MIXIN_OWNER ->
                    I18n.t("crash.fix.disableMod", fix.subject());
            case DISABLE_DUPLICATES -> I18n.t("crash.fix.disableDuplicates", fix.subject());
            case JAVA -> I18n.t("crash.fix.java", fix.subject());
            case AUTOMATIC_JAVA -> I18n.t("crash.fix.automaticJava");
            case RAISE_MEMORY -> I18n.t("crash.fix.raiseMemory", fix.subject());
            case LOWER_MEMORY -> I18n.t("crash.fix.lowerMemory", fix.subject());
            case REINSTALL -> I18n.t("crash.fix.reinstall");
        };
    }

    private HBox links() {
        HBox row = new HBox(14);
        row.setAlignment(Pos.CENTER_LEFT);
        evidence.crashReport().ifPresent(report -> {
            Hyperlink open = link(I18n.t("crash.openReport"));
            open.setOnAction(event -> SystemFiles.reveal(report));
            row.getChildren().add(open);
        });
        Hyperlink logs = link(I18n.t("crash.openLogs"));
        logs.setOnAction(event -> SystemFiles.openFolder(gameDir.resolve("logs")));
        Hyperlink report = link(I18n.t("crash.report"));
        report.setOnAction(event -> actions.reportBug());
        Hyperlink bisect = link(I18n.t("bisect.action"));
        bisect.setOnAction(event -> actions.findProblemMod());
        row.getChildren().addAll(bisect, logs, report);
        return row;
    }

    private static String nameOf(ModEntry entry) {
        return entry.title() == null || entry.title().isBlank() ? entry.fileName() : entry.title();
    }

    private static void show(Label label, String text) {
        label.setText(text);
        label.setVisible(true);
        label.setManaged(true);
    }

    private static Hyperlink link(String text) {
        Hyperlink link = new Hyperlink(text);
        link.getStyleClass().add("about-link");
        return link;
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(WIDTH - 48);
        return label;
    }

    private static Label muted(String text) {
        Label label = wrapped(text);
        label.getStyleClass().add("muted");
        return label;
    }
}
