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

import com.hexadron.launcher.core.Progress;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges {@link Progress} onto the JavaFX application thread.
 *
 * <p>Updates are coalesced: a 600-file asset download would otherwise post
 * thousands of runnables and make the UI less responsive than the download.
 */
public final class UiProgress implements Progress {

    private static final long MIN_UPDATE_INTERVAL_MILLIS = 60;
    private static final int MAX_LOG_CHARACTERS = 400_000;
    /**
     * Lines waiting for the panel. Past this the oldest are dropped from the
     * panel - never from the log file - with one line saying how many.
     */
    static final int MAX_PENDING_LINES = 5000;

    private final Label stageLabel;
    private final ProgressBar progressBar;
    private final TextArea logArea;
    private final SimpleBooleanProperty cancelled = new SimpleBooleanProperty(false);

    private volatile long lastUpdate;

    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicBoolean flushQueued = new AtomicBoolean();

    public UiProgress(Label stageLabel, ProgressBar progressBar, TextArea logArea) {
        this.stageLabel = stageLabel;
        this.progressBar = progressBar;
        this.logArea = logArea;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void reset() {
        cancelled.set(false);
        Platform.runLater(() -> progressBar.setProgress(0));
    }

    /**
     * Ends the current activity: a fixed message, and a bar that stops moving.
     *
     * <p>This exists because {@link #stage} switches the bar to indeterminate and
     * something has to switch it back. Without it, any long task that finishes
     * outside the ordinary success path - the game process exiting, most
     * obviously - leaves the launcher animating "Starting Minecraft" for ever,
     * which reads as a launcher that has lost track of the game it started.
     *
     * <p>Deliberately silent in the log: the caller has already said what
     * happened there, and saying it twice is how a log stops being readable.
     */
    public void finish(String message) {
        Platform.runLater(() -> {
            stageLabel.setText(message);
            progressBar.setProgress(1);
        });
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void stage(String name) {
        Platform.runLater(() -> {
            stageLabel.setText(name);
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        });
        log("== " + name);
    }

    @Override
    public void bytes(long completed, long total) {
        // Item counts drive the bar; byte counts only enrich the stage label.
        if (total <= 0 || !shouldUpdate()) {
            return;
        }
        String text = String.format("%.1f / %.1f MB", completed / 1048576.0, total / 1048576.0);
        Platform.runLater(() -> stageLabel.setText(stripSuffix(stageLabel.getText()) + "  (" + text + ")"));
    }

    @Override
    public void items(int completed, int total) {
        if (total <= 0) {
            return;
        }
        double fraction = (double) completed / total;
        // Always post the final update so the bar cannot stick at 99%.
        if (completed < total && !shouldUpdate()) {
            return;
        }
        Platform.runLater(() -> progressBar.setProgress(fraction));
    }

    /**
     * Everything the panel is told is also written to the launcher's log file.
     *
     * <p>Here rather than around this class, so that the file and the panel say
     * exactly the same thing. A log that is a subset of what was on screen is a
     * log somebody has to be told to disbelieve.
     */
    private static void record(String message) {
        com.hexadron.launcher.core.LauncherLog.info(message);
    }

    /**
     * Appends a line to the log pane, with credentials removed.
     *
     * <p>The scrub happens at this sink and not only at the call sites, because
     * this pane is what a user selects, copies and pastes into a support thread.
     * Anything that reaches it - a line from a mod, an exception from a library,
     * something the launcher did not write itself - passes through here first,
     * and this is the one place the guarantee can be made for all of it.
     */
    @Override
    public void log(String message) {
        String safe = com.hexadron.launcher.util.Redactor.scrub(message);
        record(safe);
        pending.add(safe);
        if (pendingCount.incrementAndGet() > MAX_PENDING_LINES && pending.poll() != null) {
            pendingCount.decrementAndGet();
            dropped.incrementAndGet();
        }
        // One task on the interface thread for however many lines arrive before
        // it runs. It used to be one task per line: a modpack printing a few
        // thousand lines a second queued a few thousand appends to a text area
        // of 400 000 characters, each copying all of it, and the window stopped
        // answering while the game started.
        if (flushQueued.compareAndSet(false, true)) {
            Platform.runLater(this::flush);
        }
    }

    private void flush() {
        flushQueued.set(false);
        StringBuilder chunk = new StringBuilder();
        int skipped = dropped.getAndSet(0);
        if (skipped > 0) {
            chunk.append(com.hexadron.launcher.i18n.I18n.t("log.linesSkipped", skipped))
                    .append(System.lineSeparator());
        }
        String line;
        while ((line = pending.poll()) != null) {
            pendingCount.decrementAndGet();
            chunk.append(line).append(System.lineSeparator());
        }
        if (chunk.isEmpty()) {
            return;
        }
        String text = chunk.length() > MAX_LOG_CHARACTERS / 2
                ? chunk.substring(chunk.length() - MAX_LOG_CHARACTERS / 2)
                : chunk.toString();
        if (logArea.getLength() + text.length() > MAX_LOG_CHARACTERS) {
            logArea.deleteText(0, Math.min(logArea.getLength(),
                    logArea.getLength() + text.length() - MAX_LOG_CHARACTERS / 2));
        }
        logArea.appendText(text);
    }

    private synchronized boolean shouldUpdate() {
        long now = System.currentTimeMillis();
        if (now - lastUpdate < MIN_UPDATE_INTERVAL_MILLIS) {
            return false;
        }
        lastUpdate = now;
        return true;
    }

    private static String stripSuffix(String text) {
        int index = text.indexOf("  (");
        return index < 0 ? text : text.substring(0, index);
    }
}
