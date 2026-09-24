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
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

/**
 * Progress for the content window's own status line.
 *
 * <p>Separate from the launcher's log pane: a download started in that window
 * belongs on that window, not appended to a log the user cannot see from it.
 *
 * <p>Its own class rather than an inner one because every section shares the one
 * status line. A section that had its own would give an install a place to
 * report to that the user is not looking at, and two of them could report at
 * once - which is also why the window lets only one mutating task run.
 *
 * <p>Every method marshals onto the interface thread. {@link Progress} promises
 * nothing about which thread calls it, and the two methods below that read as
 * "this is finished" are called from both.
 */
final class BrowserProgress implements Progress {

    private final Label label;
    private final ProgressBar bar;

    BrowserProgress(Label label, ProgressBar bar) {
        this.label = label;
        this.bar = bar;
    }

    @Override
    public void stage(String name) {
        Platform.runLater(() -> {
            label.setText(name);
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        });
    }

    @Override
    public void bytes(long completed, long total) {
    }

    /**
     * The bar at most this often.
     *
     * <p>A removal reports once per file, from several threads, and a few
     * thousand {@code runLater} calls queued at once kept the window from
     * repainting until they were through - the freeze the bar was meant to
     * explain.
     */
    private static final long MIN_UPDATE_INTERVAL_MILLIS = 60;

    private final java.util.concurrent.atomic.AtomicLong lastUpdate =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public void items(int completed, int total) {
        if (total <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastUpdate.get();
        // The last one always goes through, so the bar cannot stop at 99%.
        if (completed < total
                && (now - last < MIN_UPDATE_INTERVAL_MILLIS || !lastUpdate.compareAndSet(last, now))) {
            return;
        }
        double fraction = (double) completed / total;
        Platform.runLater(() -> bar.setProgress(fraction));
    }

    @Override
    public void log(String message) {
        Platform.runLater(() -> label.setText(message));
    }

    /** Done, and the bar full. */
    void done(String message) {
        Platform.runLater(() -> {
            label.setText(message);
            bar.setProgress(1);
        });
    }

    /** Not done, and the bar empty rather than left where it stopped. */
    void failed(String message) {
        Platform.runLater(() -> {
            label.setText(message);
            bar.setProgress(0);
        });
    }
}
