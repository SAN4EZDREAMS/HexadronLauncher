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
import com.hexadron.launcher.crash.MissingMods;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.mods.ModEntry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.FlowPane;
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

        /** Applies every fix, in order, off the interface thread, then starts the game again. */
        void applyAllAndPlay(List<CrashFixes.Prepared> fixes);

        /**
         * Applies these fixes one after another off the interface thread, and
         * goes on past one that fails. On the interface thread it reports each
         * - with null, or the reason it failed - and then calls {@code done}.
         *
         * @return false when the launcher is busy and nothing was started
         */
        boolean applyEach(List<CrashFixes.Prepared> fixes,
                          java.util.function.BiConsumer<CrashFixes.Prepared, String> each, Runnable done);

        /** What to switch off when these mods need something that cannot be had; read now. */
        java.util.Optional<CrashFixes.SwitchOffPlan> planSwitchOff(java.util.Collection<String> modIds);
    }

    private static final double WIDTH = 600;
    private static final double MAX_HEIGHT = 540;

    private final int exitCode;
    private final List<CrashAnalyzer.Diagnosis> diagnoses;
    private final Map<CrashAnalyzer.Diagnosis, List<CrashFixes.Prepared>> fixes;
    /** Every missing mod of every cause, shown together rather than one card each. */
    private final MissingMods missing;
    private final CrashEvidence evidence;
    private final Path gameDir;
    private final Actions actions;
    /** Fixes the player has applied one by one already; the one-click button skips them. */
    private final java.util.Set<CrashFixes.Prepared> applied = new java.util.HashSet<>();

    CrashDialog(int exitCode, List<CrashAnalyzer.Diagnosis> diagnoses,
                Map<CrashAnalyzer.Diagnosis, List<CrashFixes.Prepared>> fixes,
                MissingMods missing, CrashEvidence evidence, Path gameDir, Actions actions) {
        this.exitCode = exitCode;
        this.diagnoses = List.copyOf(diagnoses);
        this.fixes = Map.copyOf(fixes);
        this.missing = missing == null ? MissingMods.NONE : missing;
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

        // One click for the whole answer when the answer is not in doubt: every
        // cause found has one fix, or a repair first among several. With a
        // choice to make - two mods that are incompatible, a cause with no fix
        // at all - the player chooses.
        List<CrashFixes.Prepared> oneClick = oneClick();
        ButtonType fixAll = oneClick.isEmpty() ? null
                : new ButtonType(I18n.t("crash.fixAll"), ButtonBar.ButtonData.OK_DONE);
        ButtonType again = new ButtonType(I18n.t("crash.playAgain"),
                fixAll == null ? ButtonBar.ButtonData.OK_DONE : ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType(I18n.t("dialog.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(again, close);
        if (fixAll != null) {
            dialog.getDialogPane().getButtonTypes().add(fixAll);
        }

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

        if (fixAll != null) {
            Button fixAllButton = (Button) dialog.getDialogPane().lookupButton(fixAll);
            fixAllButton.getStyleClass().add("primary");
            // Asked while the window is still open, so "no" leaves it there.
            fixAllButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                for (CrashFixes.Prepared fix : pending(oneClick)) {
                    if (!fix.dependents().isEmpty() && !confirmDependents(fix, dialog.getOwner())) {
                        event.consume();
                        return;
                    }
                }
            });
        }

        dialog.setOnHidden(event -> {
            if (again.equals(dialog.getResult())) {
                actions.playAgain();
            } else if (fixAll != null && fixAll.equals(dialog.getResult())) {
                List<CrashFixes.Prepared> left = pending(oneClick);
                if (left.isEmpty()) {
                    actions.playAgain();
                } else {
                    actions.applyAllAndPlay(left);
                }
            }
        });
        dialog.show();
    }

    /**
     * The recommended fix of every cause, each once, or nothing when any cause
     * has none - then one click would not be the whole answer.
     */
    private List<CrashFixes.Prepared> oneClick() {
        if (diagnoses.isEmpty()) {
            return List.of();
        }
        List<CrashFixes.Prepared> all = new java.util.ArrayList<>();
        java.util.Set<String> same = new java.util.HashSet<>();
        for (CrashAnalyzer.Diagnosis diagnosis : diagnoses) {
            java.util.Optional<CrashFixes.Prepared> recommended =
                    CrashFixes.recommended(fixes.getOrDefault(diagnosis, List.of()));
            if (recommended.isEmpty()) {
                return List.of();
            }
            if (same.add(recommended.get().sameAs())) {
                all.add(recommended.get());
            }
        }
        return all;
    }

    private List<CrashFixes.Prepared> pending(List<CrashFixes.Prepared> fixes) {
        return fixes.stream().filter(fix -> !applied.contains(fix)).toList();
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
        // The missing mods first, all of them in one place: they stop the loader
        // before anything else runs, so nothing below them matters until they
        // are there. Their own cards would say the same thing once per mod.
        if (!missing.isEmpty()) {
            root.getChildren().addAll(new Separator(), new MissingCard(dialog, again).node());
        }
        for (CrashAnalyzer.Diagnosis diagnosis : diagnoses) {
            if (!missing.isEmpty() && MissingMods.isMissingMod(diagnosis)) {
                continue;
            }
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
                applied.add(fix);
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
            case INSTALL_MOD -> I18n.t("crash.fix.installMod", fix.subject());
            case ENABLE_FILE -> I18n.t("crash.fix.enableFile", fix.subject());
            case UPDATE_LOADER -> I18n.t("crash.fix.updateLoader", fix.subject());
            case RESET_CONFIG -> I18n.t("crash.fix.resetConfig", fix.subject());
            case REMOVE_JVM_ARGUMENT -> I18n.t("crash.fix.removeJvmArgument", fix.subject());
            case DISABLE_SHADERS -> I18n.t("crash.fix.disableShaders", fix.subject());
            case UPDATE_MOD -> I18n.t("crash.fix.updateMod", fix.subject());
        };
    }

    private FlowPane links() {
        // A flow, not a row: four links in a row were each cut short to
        // "Open crash rep..." in the languages with longer words.
        FlowPane row = new FlowPane(14, 4);
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

    /** Where one missing mod stands in this window. */
    private enum State {
        /** A platform has it, or a switched-off copy is in the folder. */
        FOUND,
        /** No platform has it for this profile. */
        NOT_FOUND,
        INSTALLING,
        INSTALLED,
        /** Found, but installing it did not work. */
        FAILED,
        /** The mods that need it were switched off instead. */
        SWITCHED_OFF
    }

    /**
     * The missing mods of the crash, with one button that brings all of them,
     * and - for the ones that cannot be brought - one that switches off what
     * needs them, and a web search for each to find it by hand.
     */
    private final class MissingCard {
        private final Dialog<ButtonType> dialog;
        private final ButtonType again;
        private final Map<MissingMods.Need, State> states = new java.util.LinkedHashMap<>();
        private final Map<MissingMods.Need, String> failures = new java.util.HashMap<>();
        private final Map<MissingMods.Need, Label> stateLabels = new java.util.HashMap<>();
        private final Map<MissingMods.Need, Hyperlink> searches = new java.util.HashMap<>();
        private final Button installAll = new Button();
        private final Button switchOff = new Button();
        private final Label note = muted("");
        private final Label status = muted("");
        private boolean working;

        MissingCard(Dialog<ButtonType> dialog, ButtonType again) {
            this.dialog = dialog;
            this.again = again;
        }

        VBox node() {
            Label title = new Label(I18n.t("crash.missing.title"));
            title.getStyleClass().add("section-title");
            VBox card = new VBox(8, title, wrapped(I18n.t("crash.missing.body")));

            for (MissingMods.Need need : missing.needs()) {
                states.put(need, need.supply().isPresent() ? State.FOUND : State.NOT_FOUND);
                Label name = wrapped(need.name());
                name.setStyle("-fx-font-weight: bold;");
                Label neededBy = muted(I18n.t("crash.missing.neededBy", String.join(", ", need.neededNames())));
                Label state = muted("");
                String query = missing.searchQuery(need);
                Hyperlink search = link(I18n.t("crash.missing.search", query));
                search.setWrapText(true);
                search.setMaxWidth(WIDTH - 60);
                search.setOnAction(event -> WebLinks.open(WebLinks.search(query)));
                stateLabels.put(need, state);
                searches.put(need, search);
                VBox row = new VBox(1, name, neededBy, state, search);
                row.setPadding(new Insets(0, 0, 0, 10));
                card.getChildren().add(row);
            }

            installAll.getStyleClass().add("primary");
            installAll.setOnAction(event -> installAll());
            switchOff.getStyleClass().add("danger");
            switchOff.setOnAction(event -> switchOff());
            FlowPane buttons = new FlowPane(8, 8, installAll, switchOff);
            buttons.setPadding(new Insets(4, 0, 0, 0));
            status.setVisible(false);
            status.setManaged(false);
            card.getChildren().addAll(note, buttons, status);
            refresh();
            return card;
        }

        private void refresh() {
            for (MissingMods.Need need : missing.needs()) {
                State state = states.get(need);
                stateLabels.get(need).setText(switch (state) {
                    case FOUND -> need.supply().get().fix().kind() == CrashFix.Kind.ENABLE_FILE
                            ? I18n.t("crash.missing.offCopy")
                            : I18n.t("crash.missing.found", need.supply().get().reference().startsWith("curseforge:")
                                    ? "CurseForge" : "Modrinth");
                    case NOT_FOUND -> I18n.t("crash.missing.notFound",
                            (missing.minecraftVersion() + " · " + missing.loaderName()).trim());
                    case INSTALLING -> I18n.t("crash.missing.installing");
                    case INSTALLED -> "✓ " + I18n.t("crash.missing.installed");
                    case FAILED -> I18n.t("crash.missing.failed", failures.getOrDefault(need, ""));
                    case SWITCHED_OFF -> I18n.t("crash.missing.switchedOff");
                });
                boolean searchable = state == State.NOT_FOUND || state == State.FAILED;
                searches.get(need).setVisible(searchable);
                searches.get(need).setManaged(searchable);
            }
            long pending = missing.needs().stream().filter(need -> states.get(need) == State.FOUND).count();
            installAll.setText(I18n.t("crash.missing.installAll", String.valueOf(pending)));
            installAll.setVisible(pending > 0);
            installAll.setManaged(pending > 0);
            installAll.setDisable(working);

            List<MissingMods.Need> unresolved = unresolved();
            switchOff.setText(I18n.t("crash.missing.disable"));
            switchOff.setVisible(!unresolved.isEmpty());
            switchOff.setManaged(!unresolved.isEmpty());
            switchOff.setDisable(working);
            note.setText(I18n.t("crash.missing.unresolved",
                    unresolved.stream().map(MissingMods.Need::name).collect(Collectors.joining(", "))));
            note.setVisible(!unresolved.isEmpty());
            note.setManaged(!unresolved.isEmpty());
        }

        private List<MissingMods.Need> unresolved() {
            return missing.needs().stream()
                    .filter(need -> states.get(need) == State.NOT_FOUND || states.get(need) == State.FAILED)
                    .toList();
        }

        private void installAll() {
            Map<CrashFixes.Prepared, MissingMods.Need> byFix = new java.util.IdentityHashMap<>();
            List<CrashFixes.Prepared> supplies = new java.util.ArrayList<>();
            for (MissingMods.Need need : missing.needs()) {
                if (states.get(need) == State.FOUND) {
                    byFix.put(need.supply().get(), need);
                    supplies.add(need.supply().get());
                }
            }
            if (supplies.isEmpty()) {
                return;
            }
            boolean started = actions.applyEach(supplies, (fix, failure) -> {
                MissingMods.Need need = byFix.get(fix);
                if (failure == null) {
                    states.put(need, State.INSTALLED);
                    applied.add(fix);
                } else {
                    states.put(need, State.FAILED);
                    failures.put(need, failure);
                }
                refresh();
            }, () -> {
                working = false;
                if (unresolved().isEmpty()) {
                    say(I18n.t("crash.missing.allInstalled"));
                    makeAgainPrimary();
                } else {
                    say(I18n.t("crash.missing.someFailed"));
                }
                refresh();
            });
            if (!started) {
                say(I18n.t("status.busy", I18n.t("crash.fix.task")));
                return;
            }
            working = true;
            byFix.values().forEach(need -> states.put(need, State.INSTALLING));
            refresh();
        }

        private void switchOff() {
            List<MissingMods.Need> unresolved = unresolved();
            java.util.Set<String> askers = new java.util.LinkedHashSet<>();
            unresolved.forEach(need -> askers.addAll(need.neededBy()));
            java.util.Optional<CrashFixes.SwitchOffPlan> plan = actions.planSwitchOff(askers);
            if (plan.isEmpty()) {
                // Switched off already, in the mods window or by an earlier click.
                unresolved.forEach(need -> states.put(need, State.SWITCHED_OFF));
                say(I18n.t("crash.missing.nothingToDisable"));
                refresh();
                return;
            }
            java.util.Optional<List<ModEntry>> libraries = confirmSwitchOff(plan.get());
            if (libraries.isEmpty()) {
                return;
            }
            CrashFixes.Prepared fix = plan.get().prepared(libraries.get());
            List<String> names = new java.util.ArrayList<>(fix.targets().stream().map(CrashDialog::nameOf).toList());
            fix.dependents().stream().map(CrashDialog::nameOf).forEach(names::add);
            boolean started = actions.applyEach(List.of(fix), (done, failure) -> {
                if (failure == null) {
                    unresolved.forEach(need -> states.put(need, State.SWITCHED_OFF));
                    applied.add(done);
                    say(I18n.t("crash.missing.disabled", String.join(", ", names)));
                    makeAgainPrimary();
                } else {
                    say(I18n.t("crash.fix.failed", failure));
                }
            }, () -> {
                working = false;
                refresh();
            });
            if (!started) {
                say(I18n.t("status.busy", I18n.t("crash.fix.task")));
                return;
            }
            working = true;
            refresh();
        }

        /**
         * Asks before switching off, and lists everything that will go.
         *
         * <p>The mods that need the switched-off ones go without a choice: left
         * on, they stop the game the same way. The libraries left over each get
         * a tick box, ticked when the library plainly is one; a content mod that
         * only an add-on needed is the player's to keep.
         *
         * @return the libraries to switch off too; empty when the player said no
         */
        private java.util.Optional<List<ModEntry>> confirmSwitchOff(CrashFixes.SwitchOffPlan plan) {
            VBox content = new VBox(6);
            content.setPadding(new Insets(4, 0, 0, 0));
            content.getChildren().addAll(heading(I18n.t("crash.missing.confirm.askers")),
                    wrapped(bullets(plan.askers())));
            if (!plan.dependents().isEmpty()) {
                content.getChildren().addAll(heading(I18n.t("crash.missing.confirm.dependents")),
                        wrapped(bullets(plan.dependents())));
            }
            Map<CheckBox, ModEntry> boxes = new java.util.LinkedHashMap<>();
            if (!plan.libraries().isEmpty()) {
                content.getChildren().add(heading(I18n.t("crash.missing.confirm.libraries")));
                for (CrashFixes.SwitchOffPlan.Library library : plan.libraries()) {
                    CheckBox box = new CheckBox(nameOf(library.mod()));
                    box.setSelected(library.likely());
                    boxes.put(box, library.mod());
                    content.getChildren().add(box);
                }
            }
            content.getChildren().add(muted(I18n.t("crash.missing.confirm.note")));

            ButtonType ok = new ButtonType(I18n.t("crash.missing.confirm.ok"), ButtonBar.ButtonData.OK_DONE);
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", ok, ButtonType.CANCEL);
            alert.initOwner(dialog.getOwner());
            alert.setTitle(I18n.t("crash.title"));
            alert.setHeaderText(I18n.t("crash.missing.confirm.header"));
            alert.getDialogPane().setContent(content);
            Theme.apply(alert.getDialogPane());
            if (alert.showAndWait().filter(ok::equals).isEmpty()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(boxes.entrySet().stream()
                    .filter(entry -> entry.getKey().isSelected()).map(Map.Entry::getValue).toList());
        }

        private void makeAgainPrimary() {
            javafx.scene.Node button = dialog.getDialogPane().lookupButton(again);
            if (button != null && !button.getStyleClass().contains("primary")) {
                button.getStyleClass().add("primary");
            }
        }

        private void say(String text) {
            show(status, text);
        }
    }

    private static Label heading(String text) {
        Label label = wrapped(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static String bullets(List<ModEntry> mods) {
        return mods.stream().map(entry -> "• " + nameOf(entry)).collect(Collectors.joining("\n"));
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
