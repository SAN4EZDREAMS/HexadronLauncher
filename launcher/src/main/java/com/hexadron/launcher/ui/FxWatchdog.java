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
import javafx.application.Platform;

import java.util.Map;

/**
 * Writes down what the interface thread is doing when it stops answering.
 *
 * <p>A window that stops responding leaves nothing behind: the log shows the
 * last thing that worked, the player ends the process, and the question "what
 * was it doing" has no answer. This thread asks the interface thread for a
 * heartbeat every two seconds. When none has come for
 * {@link #BLOCKED_MILLIS}, it writes that thread's stack to
 * {@code launcher.log} - once per stall - and a second line when the window
 * answers again.
 *
 * <p>It reads, it never interrupts. A stack trace in the log costs nothing when
 * the window is fine and is the whole diagnosis when it is not.
 */
public final class FxWatchdog {

    static final long BEAT_MILLIS = 2000;
    static final long BLOCKED_MILLIS = 8000;
    private static final String FX_THREAD = "JavaFX Application Thread";

    private static volatile long lastBeat = System.currentTimeMillis();
    private static volatile boolean started;

    private FxWatchdog() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread watchdog = new Thread(FxWatchdog::watch, "hexadron-fx-watchdog");
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.MIN_PRIORITY);
        watchdog.start();
    }

    private static void watch() {
        long reportedStall = 0;
        while (true) {
            try {
                Thread.sleep(BEAT_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            Platform.runLater(() -> lastBeat = System.currentTimeMillis());
            long silent = System.currentTimeMillis() - lastBeat;
            if (silent >= BLOCKED_MILLIS && reportedStall == 0) {
                reportedStall = lastBeat;
                LauncherLog.info("Interface thread has not answered for " + silent / 1000
                        + " s. It is doing:" + stackOf(FX_THREAD));
            } else if (silent < BLOCKED_MILLIS && reportedStall != 0) {
                LauncherLog.info("Interface thread answers again after "
                        + (lastBeat - reportedStall) / 1000 + " s");
                reportedStall = 0;
            }
        }
    }

    /** The stack of the named thread, one frame per line, at most 40 frames. */
    static String stackOf(String threadName) {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            if (entry.getKey().getName().equals(threadName)) {
                StringBuilder out = new StringBuilder();
                StackTraceElement[] frames = entry.getValue();
                for (int i = 0; i < Math.min(40, frames.length); i++) {
                    out.append(System.lineSeparator()).append("    at ").append(frames[i]);
                }
                return out.toString();
            }
        }
        return " (thread not found)";
    }
}
