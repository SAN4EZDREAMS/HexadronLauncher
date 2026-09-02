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

import com.hexadron.launcher.core.LauncherLog;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.update.UpdateInstall;
import com.hexadron.launcher.update.Updates;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The window that offers a newer launcher, and then installs it.
 *
 * <h2>Why a window and not a notification</h2>
 *
 * <p>Because what is being proposed is that this program replace itself, and the
 * only way to decide is to read what changed. So the notes are the body of the
 * window rather than a link to them, and the two answers are at the bottom,
 * where a decision belongs.
 *
 * <h2>Why the same window does the work</h2>
 *
 * <p>An update that answers "yes" by closing the window and doing something
 * elsewhere takes away the one thing the user was looking at. Here the buttons
 * become a progress bar in place: it fills while the build downloads, says what
 * it is doing while it unpacks, and the launcher closes when there is nothing
 * left to say. The next thing on screen is the new version.
 *
 * <h2>When it can only point at the door</h2>
 *
 * <p>A launcher started from an IDE or from {@code java -jar} has no installed
 * folder to replace, and one installed somewhere the user cannot write to must
 * not be half-replaced. Both are said plainly, and the window then offers the
 * release page instead of a button that would fail.
 */
final class UpdateDialog {

    /** How wide the window is: wide enough for a paragraph of notes to read well. */
    private static final double WIDTH = 640;

    /** How tall the notes may grow before they scroll rather than push the buttons off. */
    private static final double NOTES_HEIGHT = 240;

    /** How often the progress line is rewritten, in milliseconds. */
    private static final long PROGRESS_EVERY = 120;

    private final Stage stage = new Stage();
    private final Updates.Available update;

    /** Where this launcher is installed, or empty when it is not installed at all. */
    private final Optional<UpdateInstall> install;

    private final Label status = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private final VBox progressBox = new VBox(6, status, bar);

    private final Button installButton = new Button();
    private final Button laterButton = new Button();
    private final Button pageButton = new Button();

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean working;
    private volatile long lastReport;

    private UpdateDialog(Updates.Available update, Optional<UpdateInstall> install) {
        this.update = update;
        this.install = install;
    }

    /** Offers this update, over the given window. */
    static void show(Window owner, Updates.Available update) {
        new UpdateDialog(update, UpdateInstall.detect()).open(owner);
    }

    private void open(Window owner) {
        Label header = new Label(I18n.t("update.available.header"));
        header.getStyleClass().add("detail-title");
        header.setWrapText(true);

        Label versions = new Label(I18n.t("update.available.versions",
                update.from().text(), update.to().text()));
        versions.getStyleClass().add("update-versions");

        Label meta = new Label(I18n.t("update.available.size", megabytes(update.size()))
                + "   ·   " + update.release().tag());
        meta.getStyleClass().add("muted");

        Label notesTitle = new Label(I18n.t("update.available.notes"));
        notesTitle.getStyleClass().add("section-title");

        // The notes are whatever was written on the release, as it was written.
        // Markdown is left as text on purpose: a launcher that renders it would
        // be a launcher with a markdown reader in it, and what is actually
        // published here is a list of lines starting with dashes.
        Label notes = new Label(update.notes().isBlank()
                ? I18n.t("update.available.notes.empty")
                : update.notes().strip());
        notes.setWrapText(true);
        VBox notesBox = new VBox(notes);
        notesBox.getStyleClass().add("update-notes");

        ScrollPane notesScroll = new ScrollPane(notesBox);
        notesScroll.setFitToWidth(true);
        notesScroll.setPrefViewportHeight(NOTES_HEIGHT);
        notesScroll.getStyleClass().add("update-scroll");
        VBox.setVgrow(notesScroll, Priority.ALWAYS);

        String blocked = blockedReason();
        Label blockedNote = new Label(blocked == null ? "" : blocked);
        blockedNote.setWrapText(true);
        blockedNote.getStyleClass().add("update-blocked");
        blockedNote.setVisible(blocked != null);
        blockedNote.setManaged(blocked != null);

        status.getStyleClass().add("muted");
        bar.setMaxWidth(Double.MAX_VALUE);
        progressBox.setVisible(false);
        progressBox.setManaged(false);

        installButton.setText(I18n.t("update.action.update"));
        installButton.getStyleClass().add("primary");
        installButton.setDisable(blocked != null);
        installButton.setOnAction(event -> begin());

        laterButton.setText(I18n.t("update.action.later"));
        laterButton.setOnAction(event -> stage.close());

        pageButton.setText(I18n.t("update.action.page"));
        pageButton.setOnAction(event -> SystemBrowser.open(update.release().pageUrl()));
        pageButton.setDisable(!SystemBrowser.isWebPage(update.release().pageUrl()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, pageButton, spacer, laterButton, installButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, header, versions, meta, notesTitle, notesScroll,
                blockedNote, progressBox, buttons);
        root.getStyleClass().add("update-pane");
        VBox.setVgrow(notesScroll, Priority.ALWAYS);

        Scene scene = new Scene(root, WIDTH, 520);
        Theme.apply(scene);

        stage.setScene(scene);
        stage.setTitle(I18n.t("update.available.title"));
        stage.getIcons().setAll(Brand.windowIcons());
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setMinWidth(520);
        stage.setMinHeight(420);
        // Closing the window during a download is an answer too: the request is
        // stopped rather than left running against a window that has gone.
        stage.setOnCloseRequest(event -> cancelled.set(true));
        stage.showAndWait();
    }

    /** Why this launcher cannot replace itself here, or null when it can. */
    private String blockedReason() {
        if (install.isEmpty()) {
            return I18n.t("update.manual.notImage");
        }
        if (!install.get().isWritable()) {
            return I18n.t("update.manual.readOnly", install.get().root());
        }
        return null;
    }

    private void begin() {
        working = true;
        cancelled.set(false);
        installButton.setDisable(true);
        laterButton.setDisable(true);
        progressBox.setVisible(true);
        progressBox.setManaged(true);
        bar.setProgress(0);
        status.setText(I18n.t("update.stage.download", megabytes(0), megabytes(update.size())));

        Thread worker = new Thread(this::run, "hexadron-update");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Downloads, unpacks, hands over, and closes the launcher.
     *
     * <p>The last step is not a tidy-up: the updater is already running and is
     * waiting for this process to end before it touches anything. Exiting is
     * how the update proceeds.
     */
    private void run() {
        UpdateInstall target = install.orElseThrow();
        Path workDir = Updates.workDirectory(target);
        try {
            Path archive = Updates.download(update, workDir, progress());
            Platform.runLater(() -> {
                bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                status.setText(I18n.t("update.stage.unpack"));
            });
            Path image = Updates.unpack(archive, workDir, target.os());

            Platform.runLater(() -> status.setText(I18n.t("update.stage.apply")));
            Updates.handOver(image, target, workDir);
            LauncherLog.info("Update: handed over to the updater, closing");

            Platform.runLater(() -> {
                Platform.exit();
                // Platform.exit ends the toolkit, not the process: a thread that
                // is still running would keep this one alive, and the updater is
                // waiting for it to end.
                System.exit(0);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Platform.runLater(this::reset);
        } catch (InterruptedIOException e) {
            Platform.runLater(this::reset);
        } catch (Exception e) {
            LauncherLog.error("Update failed", e);
            Platform.runLater(() -> failed(e));
        }
    }

    /** Back to the question, after a cancelled download. */
    private void reset() {
        working = false;
        progressBox.setVisible(false);
        progressBox.setManaged(false);
        installButton.setDisable(blockedReason() != null);
        laterButton.setDisable(false);
    }

    private void failed(Exception failure) {
        working = false;
        bar.setProgress(0);
        status.setText(I18n.t("update.failed",
                failure.getMessage() == null ? failure.toString() : failure.getMessage()));
        status.getStyleClass().add("update-failed");
        installButton.setDisable(false);
        laterButton.setDisable(false);
    }

    /**
     * Bytes into a line and a bar.
     *
     * <p>Rewritten a few times a second rather than on every block read. A
     * hundred and fifty megabytes arrive in a few thousand pieces, and a label
     * asked to redraw itself that many times is a window that stops answering.
     */
    private Progress progress() {
        return new Progress() {

            @Override
            public void stage(String name) {
                // The stages here are named by the caller, in the user's own
                // language. Nothing to add.
            }

            @Override
            public void bytes(long completed, long total) {
                long now = System.currentTimeMillis();
                if (now - lastReport < PROGRESS_EVERY && completed < total) {
                    return;
                }
                lastReport = now;
                double fraction = total > 0 ? (double) completed / total : -1;
                Platform.runLater(() -> {
                    bar.setProgress(fraction);
                    status.setText(I18n.t("update.stage.download",
                            megabytes(completed), megabytes(total)));
                });
            }

            @Override
            public void items(int completed, int total) {
            }

            @Override
            public void log(String message) {
                LauncherLog.info("Update: " + message);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    /** A byte count as the number of megabytes somebody would say out loud. */
    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0, bytes) / (1024.0 * 1024.0));
    }
}
