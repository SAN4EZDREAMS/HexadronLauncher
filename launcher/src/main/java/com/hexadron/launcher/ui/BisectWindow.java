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

import com.hexadron.launcher.bisect.Bisect;
import com.hexadron.launcher.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The window that runs a problem-mod search, from the first launch to the
 * answer.
 *
 * <p>It stays open while the game runs, because a search is a conversation
 * over several launches: switch half the mods off, start the game, say
 * whether the problem came back, repeat. A crash is recognised by itself and
 * counts as the problem; anything else - a texture that is wrong, a freeze you
 * stopped, a game that simply works - is the player's answer to give.
 *
 * <p>The state lives on disk, not here (see {@code bisect/BisectFiles}). Closing
 * the window loses nothing: the next Play in this profile offers to continue
 * or to put every mod back.
 */
final class BisectWindow {

    /** What the window asks the main window to do. Calls may block; they run off the interface thread. */
    interface Actions {
        Bisect.State current() throws Exception;

        Bisect.State start() throws Exception;

        Bisect.State answer(boolean problem) throws Exception;

        void finish(List<String> keepOff) throws Exception;

        Set<String> enabled(Bisect.State state) throws Exception;

        Set<String> needs(Bisect.State state, String file) throws Exception;

        String title(String file);

        /** Starts the game with the mods of the current step, on the interface thread. */
        void launch();

        int enabledNow() throws Exception;
    }

    private static final double WIDTH = 520;

    private final Stage stage = new Stage();
    private final VBox root = new VBox(12);
    private final String profileName;
    private final Actions actions;
    private boolean gameRunning;

    BisectWindow(Window owner, String profileName, Actions actions) {
        this.profileName = profileName;
        this.actions = actions;
        stage.initOwner(owner);
        stage.setTitle(I18n.t("bisect.title") + " - " + profileName);
        root.setPadding(new Insets(18, 22, 18, 22));
        root.setPrefWidth(WIDTH);
        root.getStyleClass().add("cleanup-root");
        Scene scene = new Scene(root);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setResizable(false);
    }

    void show() {
        stage.show();
        stage.toFront();
        refresh();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    void close() {
        stage.close();
    }

    /** Called when a launch of this search ends. {@code crashed} is true when the launcher saw a crash. */
    void gameEnded(boolean crashed) {
        gameRunning = false;
        if (crashed) {
            work(() -> actions.answer(true), next -> {
                render(next);
                note(I18n.t("bisect.crashed"));
            });
        } else {
            ask();
        }
    }

    // ------------------------------------------------------------------ views

    private void refresh() {
        work(actions::current, state -> {
            if (state == null) {
                intro();
            } else {
                render(state);
            }
        });
    }

    private void intro() {
        work(actions::enabledNow, count -> {
            clear();
            heading(I18n.t("bisect.title"));
            if (count < 2) {
                text(I18n.t("bisect.tooFew"));
                buttons(button(I18n.t("dialog.close"), stage::close, false));
                return;
            }
            text(I18n.t("bisect.intro", Bisect.estimate(count), count));
            buttons(button(I18n.t("bisect.start"), () -> work(actions::start, this::render), true),
                    button(I18n.t("dialog.close"), stage::close, false));
        });
    }

    private void render(Bisect.State state) {
        if (state.isDone()) {
            result(state);
            return;
        }
        work(() -> actions.enabled(state), on -> {
            clear();
            heading(I18n.t("bisect.title"));
            muted(I18n.t("bisect.step", state.step(), Bisect.remaining(state), state.suspects().size()));
            if (state.mode() != Bisect.Mode.SINGLE) {
                text(I18n.t("bisect.pair"));
            }
            text(I18n.t("bisect.testing", on.size(), state.original().size()));
            if (gameRunning) {
                text(I18n.t("bisect.running"));
                // For a launch that never got going - the launcher refused, or
                // an install failed. A game that is running still reports back.
                buttons(button(I18n.t("dialog.cancel"), () -> {
                    gameRunning = false;
                    render(state);
                }, false));
                return;
            }
            buttons(button(I18n.t("bisect.play"), () -> {
                        gameRunning = true;
                        render(state);
                        actions.launch();
                    }, true),
                    button(I18n.t("bisect.cancel"), () -> finish(List.of()), false));
        });
    }

    private void ask() {
        work(actions::current, state -> {
            if (state == null) {
                intro();
                return;
            }
            clear();
            heading(I18n.t("bisect.title"));
            muted(I18n.t("bisect.step", state.step(), Bisect.remaining(state), state.suspects().size()));
            text(I18n.t("bisect.question"));
            buttons(button(I18n.t("bisect.yes"), () -> work(() -> actions.answer(true), this::render), true),
                    button(I18n.t("bisect.no"), () -> work(() -> actions.answer(false), this::render), false),
                    button(I18n.t("bisect.cancel"), () -> finish(List.of()), false));
        });
    }

    private void result(Bisect.State state) {
        List<String> found = state.result();
        work(() -> {
            List<String> needs = new ArrayList<>();
            for (String file : found) {
                for (String need : actions.needs(state, file)) {
                    if (!found.contains(need) && !needs.contains(need)) {
                        needs.add(need);
                    }
                }
            }
            return needs;
        }, needs -> {
            clear();
            heading(I18n.t("bisect.title"));
            if (found.size() == 1) {
                text(I18n.t("bisect.found.one", actions.title(found.get(0))));
            } else {
                text(I18n.t("bisect.found.pair", actions.title(found.get(0)), actions.title(found.get(1))));
            }
            if (!needs.isEmpty()) {
                muted(I18n.t("bisect.found.with",
                        needs.stream().map(actions::title).collect(Collectors.joining(", "))));
            }
            List<Button> choices = new ArrayList<>();
            for (String file : found) {
                choices.add(button(I18n.t("bisect.keepOff", actions.title(file)),
                        () -> finish(List.of(file)), choices.isEmpty()));
            }
            choices.add(button(I18n.t("bisect.restoreAll"), () -> finish(List.of()), false));
            buttons(choices.toArray(new Button[0]));
        });
    }

    private void finish(List<String> keepOff) {
        work(() -> {
            actions.finish(keepOff);
            return keepOff;
        }, kept -> {
            clear();
            heading(I18n.t("bisect.title"));
            text(kept.isEmpty() ? I18n.t("bisect.restored")
                    : I18n.t("bisect.keptOff", actions.title(kept.get(0))));
            buttons(button(I18n.t("dialog.close"), stage::close, true));
        });
    }

    // ------------------------------------------------------------------ plumbing

    private interface Work<T> {
        T run() throws Exception;
    }

    /** Runs {@code task} off the interface thread and hands the result back on it. */
    private <T> void work(Work<T> task, java.util.function.Consumer<T> then) {
        root.setDisable(true);
        Thread thread = new Thread(() -> {
            try {
                T value = task.run();
                Platform.runLater(() -> {
                    root.setDisable(false);
                    then.accept(value);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    root.setDisable(false);
                    note(I18n.t("bisect.failed", MainWindow.describe(e)));
                });
            }
        }, "hexadron-bisect");
        thread.setDaemon(true);
        thread.start();
    }

    private void clear() {
        root.getChildren().clear();
    }

    private void heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        root.getChildren().add(label);
    }

    private void text(String text) {
        root.getChildren().add(wrapped(text));
    }

    private void muted(String text) {
        Label label = wrapped(text);
        label.getStyleClass().add("muted");
        root.getChildren().add(label);
    }

    private void note(String text) {
        Label label = wrapped(text);
        label.getStyleClass().add("muted");
        root.getChildren().add(1, label);
        stage.sizeToScene();
    }

    private void buttons(Button... buttons) {
        FlowPane row = new FlowPane(8, 8, buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 0, 0, 0));
        root.getChildren().add(row);
        stage.sizeToScene();
    }

    private static Button button(String text, Runnable action, boolean primary) {
        Button button = new Button(text);
        if (primary) {
            button.getStyleClass().add("primary");
        }
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(WIDTH - 44);
        return label;
    }
}
