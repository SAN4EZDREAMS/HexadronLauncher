package com.hexadron.launcher.ui;

import com.hexadron.launcher.core.Progress;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;

/**
 * Bridges {@link Progress} onto the JavaFX application thread.
 *
 * <p>Updates are coalesced: a 600-file asset download would otherwise post
 * thousands of runnables and make the UI less responsive than the download.
 */
public final class UiProgress implements Progress {

    private static final long MIN_UPDATE_INTERVAL_MILLIS = 60;
    private static final int MAX_LOG_CHARACTERS = 400_000;

    private final Label stageLabel;
    private final ProgressBar progressBar;
    private final TextArea logArea;
    private final SimpleBooleanProperty cancelled = new SimpleBooleanProperty(false);

    private volatile long lastUpdate;

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
        Platform.runLater(() -> {
            if (logArea.getLength() > MAX_LOG_CHARACTERS) {
                logArea.deleteText(0, MAX_LOG_CHARACTERS / 2);
            }
            logArea.appendText(safe + System.lineSeparator());
        });
    }

    private boolean shouldUpdate() {
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
