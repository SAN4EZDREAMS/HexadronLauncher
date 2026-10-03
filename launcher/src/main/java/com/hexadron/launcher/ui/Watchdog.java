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

import javafx.application.Platform;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Writes down what the launcher's own threads are doing when it stops.
 *
 * <p>A launcher that has stopped cannot say why: its log is one of the things
 * that stop. So this thread does not use the log. Every few seconds it asks
 * the interface thread to answer, and it looks at how long any thread has been
 * trying to write a log line. When the interface has not answered for
 * {@value #INTERFACE_STALL_MILLIS} ms, or a log line has taken
 * {@value #LOG_STALL_MILLIS} ms, it writes every thread's stack and locks to
 * {@code logs/launcher-threads-<time>.txt} directly - once per stall - and
 * that file is what the next bug report needs.
 *
 * <p>It changes nothing and stops nothing. A stall it reports may yet pass.
 */
public final class Watchdog {

    static final long INTERFACE_STALL_MILLIS = 15_000;
    static final long LOG_STALL_MILLIS = 10_000;
    private static final long PERIOD_MILLIS = 3_000;
    private static final int KEPT_DUMPS = 5;
    static final String PREFIX = "launcher-threads-";

    private static volatile boolean started;

    private Watchdog() {
    }

    public static void start(Path logsDir) {
        if (started || logsDir == null) {
            return;
        }
        started = true;
        Thread thread = new Thread(() -> watch(logsDir), "hexadron-watchdog");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    private static void watch(Path logsDir) {
        java.util.concurrent.atomic.AtomicLong answered =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        java.util.concurrent.atomic.AtomicBoolean asked = new java.util.concurrent.atomic.AtomicBoolean();
        long askedAt = 0;
        boolean reported = false;
        while (true) {
            try {
                Thread.sleep(PERIOD_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            if (asked.compareAndSet(false, true)) {
                askedAt = now;
                try {
                    Platform.runLater(() -> {
                        answered.set(System.currentTimeMillis());
                        asked.set(false);
                    });
                } catch (IllegalStateException e) {
                    // The toolkit has gone: the launcher is closing.
                    return;
                }
            }
            long interfaceStall = asked.get() ? now - askedAt : 0;
            long logStall = com.hexadron.launcher.core.LauncherLog.longestWriteMillis();
            boolean stalled = interfaceStall >= INTERFACE_STALL_MILLIS || logStall >= LOG_STALL_MILLIS;
            if (stalled && !reported) {
                reported = true;
                Path written = dump(logsDir, interfaceStall, logStall);
                if (written != null) {
                    // Told to the log from a thread of its own: if the log is
                    // what stopped, this thread must not stop with it.
                    String name = written.getFileName().toString();
                    Thread note = new Thread(() -> com.hexadron.launcher.core.LauncherLog.warn(
                            "Watchdog: the launcher stopped answering; its threads are in logs/%s", name),
                            "hexadron-watchdog-note");
                    note.setDaemon(true);
                    note.start();
                }
            } else if (!stalled) {
                reported = false;
            }
        }
    }

    /** Every thread's stack and locks, in a file of its own. Null when it could not be written. */
    public static Path dump(Path logsDir, long interfaceStall, long logStall) {
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            StringBuilder out = new StringBuilder(64 * 1024);
            out.append("Hexadron launcher thread dump, ").append(LocalDateTime.now()).append('\n');
            out.append("Interface thread not answering for ").append(interfaceStall / 1000).append(" s; ")
                    .append("a log line waiting for ").append(logStall / 1000).append(" s\n");
            long[] deadlocked = bean.findDeadlockedThreads();
            out.append("Deadlocked threads: ").append(deadlocked == null ? "none" : deadlocked.length).append("\n\n");
            ThreadInfo[] threads = bean.dumpAllThreads(bean.isObjectMonitorUsageSupported(),
                    bean.isSynchronizerUsageSupported());
            for (ThreadInfo info : threads) {
                describe(info, out);
            }
            Files.createDirectories(logsDir);
            prune(logsDir);
            Path file = logsDir.resolve(PREFIX + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)) + ".txt");
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
            return file;
        } catch (Throwable e) {
            return null;
        }
    }

    /** One thread in full: ThreadInfo.toString stops after eight frames, and the ninth is often the answer. */
    static void describe(ThreadInfo info, StringBuilder out) {
        out.append('"').append(info.getThreadName()).append("\" #").append(info.getThreadId())
                .append(' ').append(info.getThreadState());
        if (info.getLockName() != null) {
            out.append(" on ").append(info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            out.append(" owned by \"").append(info.getLockOwnerName()).append("\" #").append(info.getLockOwnerId());
        }
        out.append('\n');
        StackTraceElement[] stack = info.getStackTrace();
        MonitorInfo[] monitors = info.getLockedMonitors();
        for (int i = 0; i < stack.length; i++) {
            out.append("\tat ").append(stack[i]).append('\n');
            for (MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == i) {
                    out.append("\t- locked ").append(monitor).append('\n');
                }
            }
        }
        LockInfo[] synchronizers = info.getLockedSynchronizers();
        if (synchronizers.length > 0) {
            out.append("\tLocked synchronizers:\n");
            for (LockInfo lock : synchronizers) {
                out.append("\t- ").append(lock).append('\n');
            }
        }
        out.append('\n');
    }

    private static void prune(Path logsDir) {
        try (var files = Files.list(logsDir)) {
            var dumps = files.filter(f -> f.getFileName().toString().startsWith(PREFIX))
                    .sorted(java.util.Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .toList();
            for (int i = KEPT_DUMPS - 1; i < dumps.size(); i++) {
                Files.deleteIfExists(dumps.get(i));
            }
        } catch (Exception ignored) {
            // A few old files more is no fault.
        }
    }
}
