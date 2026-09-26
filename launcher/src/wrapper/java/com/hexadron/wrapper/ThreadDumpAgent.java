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

package com.hexadron.wrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes the game's threads to a file when the launcher asks for them.
 *
 * <h2>Why</h2>
 *
 * <p>A frozen game leaves nothing behind: the log stops, the player ends the
 * process, and "what was it doing" has no answer. The JDK tools that answer it
 * (jstack, jcmd) are not in a runtime, and a game window has no console to press
 * Ctrl+Break in. So the launcher starts every game with this agent. It does
 * nothing but look, once a second, for a request file in the game folder. When
 * the launcher sees the game go silent it creates that file, and the agent
 * answers with every thread's stack and any deadlock.
 *
 * <h2>What it does not do</h2>
 *
 * <p>No class is changed - there is no {@code ClassFileTransformer} - and
 * nothing is sent anywhere. It reads the threads of the JVM it runs in and
 * writes one text file next to the game's own logs. Compiled for Java 8, like
 * {@link GameLaunchWrapper}, because it runs inside every game JVM the launcher
 * starts.
 */
public final class ThreadDumpAgent {

    /** Created by the launcher to ask for a dump. */
    public static final String REQUEST = "hexadron-threads.request";
    /** The dump. */
    public static final String DUMP = "hexadron-threads.txt";

    private static final String[] FIRST = {"Render thread", "Client thread", "main", "Server thread"};

    private ThreadDumpAgent() {
    }

    /** Called by the JVM for {@code -javaagent:<jar>=<game folder>}. */
    public static void premain(String args) {
        final File dir = new File(args == null || args.isEmpty() ? "." : args);
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                File request = new File(dir, REQUEST);
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (request.isFile() && request.delete()) {
                        write(new File(dir, DUMP));
                    }
                }
            }
        }, "hexadron-thread-dump");
        watcher.setDaemon(true);
        watcher.setPriority(Thread.MIN_PRIORITY);
        watcher.start();
    }

    static void write(File target) {
        File temp = new File(target.getPath() + ".part");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            out.write(dump());
        } catch (IOException | RuntimeException e) {
            temp.delete();
            return;
        }
        target.delete();
        if (!temp.renameTo(target)) {
            temp.delete();
        }
    }

    /**
     * Every thread: the deadlocked ones first, then the game's main threads,
     * then the rest. The launcher reads them in that order.
     */
    static String dump() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long[] deadlocked = bean.isSynchronizerUsageSupported()
                ? bean.findDeadlockedThreads() : bean.findMonitorDeadlockedThreads();
        Set<Long> stuck = new HashSet<Long>();
        if (deadlocked != null) {
            for (long id : deadlocked) {
                stuck.add(id);
            }
        }
        ThreadInfo[] threads = bean.dumpAllThreads(bean.isObjectMonitorUsageSupported(),
                bean.isSynchronizerUsageSupported());
        List<ThreadInfo> ordered = new ArrayList<ThreadInfo>();
        for (ThreadInfo info : threads) {
            if (info != null && stuck.contains(info.getThreadId())) {
                ordered.add(info);
            }
        }
        for (String name : FIRST) {
            for (ThreadInfo info : threads) {
                if (info != null && name.equals(info.getThreadName()) && !ordered.contains(info)) {
                    ordered.add(info);
                }
            }
        }
        for (ThreadInfo info : threads) {
            if (info != null && !ordered.contains(info)
                    && !"hexadron-thread-dump".equals(info.getThreadName())) {
                ordered.add(info);
            }
        }

        StringBuilder text = new StringBuilder();
        text.append("Hexadron thread dump, ").append(new Date()).append('\n');
        text.append("Deadlocked threads: ").append(stuck.isEmpty() ? "none" : String.valueOf(stuck.size()))
                .append("\n\n");
        for (ThreadInfo info : ordered) {
            text.append('"').append(info.getThreadName()).append('"')
                    .append(" #").append(info.getThreadId())
                    .append(' ').append(info.getThreadState());
            if (stuck.contains(info.getThreadId())) {
                text.append(" DEADLOCKED");
            }
            if (info.getLockName() != null) {
                text.append(" on ").append(info.getLockName());
                if (info.getLockOwnerName() != null) {
                    text.append(" owned by \"").append(info.getLockOwnerName()).append('"');
                }
            }
            text.append('\n');
            StackTraceElement[] frames = info.getStackTrace();
            MonitorInfo[] monitors = info.getLockedMonitors();
            for (int i = 0; i < frames.length; i++) {
                text.append("\tat ").append(frames[i]).append('\n');
                for (MonitorInfo monitor : monitors) {
                    if (monitor.getLockedStackDepth() == i) {
                        text.append("\t- locked ").append(monitor).append('\n');
                    }
                }
            }
            LockInfo[] synchronizers = info.getLockedSynchronizers();
            if (synchronizers.length > 0) {
                text.append("\tLocked synchronizers: ").append(Arrays.toString(synchronizers)).append('\n');
            }
            text.append('\n');
        }
        return text.toString();
    }
}
