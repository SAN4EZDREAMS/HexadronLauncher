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

package com.hexadron.launcher.core;

import com.hexadron.launcher.util.Redactor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * What the launcher did, written down.
 *
 * <h2>Why it exists</h2>
 *
 * <p>A skin that did not appear was diagnosed from Minecraft's own log, in one
 * line, in a file the launcher does not write and the user had no reason to
 * open. The launcher itself kept everything in a text area that goes away with
 * the window, so the only way to find out what it had done was to be watching
 * at the time. That is not a state to ask somebody to reproduce a bug from.
 *
 * <p>So: one file per run, in a folder the settings window can open, with the
 * previous few runs kept beside it. When something goes wrong the answer is
 * "send me logs/launcher.log" rather than a screenshot of a scrolled-away
 * panel.
 *
 * <h2>It is written to be shared</h2>
 *
 * <p>Every line goes through {@link Redactor} on the way out. A launcher log
 * carries access tokens, refresh tokens and API keys through it as a matter of
 * course, and a log people are asked to attach to a bug report is the single
 * likeliest way for one of those to end up in public. Scrubbing at this point
 * rather than at each call site is deliberate: a redaction that has to be
 * remembered is a redaction that will eventually be forgotten.
 *
 * <p>Flushed after every line, which costs a little and buys the thing a log is
 * for: the last line before a crash is the one worth reading, and a buffer
 * loses exactly that one.
 *
 * <h2>It never throws</h2>
 *
 * <p>Nothing here can fail a launch. A folder that cannot be created, a disk
 * that is full, a file locked by something else - each leaves the log silently
 * off. Refusing to start the game because the diary could not be written would
 * be a worse fault than the one being recorded.
 */
public final class LauncherLog {

    /** How many previous runs are kept beside the current one. */
    private static final int KEPT_RUNS = 5;

    /**
     * Beyond this the current file stops growing.
     *
     * <p>A launch that loops on a failing download can produce a very great
     * many lines, and a log that fills a disk is a bug of its own. The cap is
     * generous enough that a whole ordinary session is far below it.
     */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    private static final Object LOCK = new Object();

    private static Writer writer;
    private static Path file;
    private static long written;
    private static boolean capped;

    private LauncherLog() {
    }

    /**
     * Starts a log for this run, rotating the previous ones.
     *
     * <p>Safe to call more than once; the second call does nothing.
     *
     * @return the file being written, or null when logging could not start
     */
    public static Path open(GameDirs dirs) {
        synchronized (LOCK) {
            if (writer != null) {
                return file;
            }
            try {
                Path folder = dirs.logs();
                Files.createDirectories(folder);
                rotate(folder);

                file = folder.resolve("launcher.log");
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                written = 0;
                capped = false;
            } catch (IOException | RuntimeException e) {
                writer = null;
                file = null;
            }
        }
        return file;
    }

    /** The file being written, or null when logging is off. */
    public static Path file() {
        synchronized (LOCK) {
            return file;
        }
    }

    /**
     * The header: what this build is, and what it is running on.
     *
     * <p>First questions on every report, and the three that are most often
     * answered wrongly from memory.
     */
    public static void header(String version, GameDirs dirs) {
        write("INFO", "Hexadron Launcher " + version);
        write("INFO", "Java " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        write("INFO", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch")
                + ", locale " + Locale.getDefault());
        write("INFO", "Data folder: " + dirs.root());
    }

    public static void info(String message) {
        write("INFO", message);
    }

    public static void info(String format, Object... args) {
        write("INFO", format(format, args));
    }

    public static void warn(String format, Object... args) {
        write("WARN", format(format, args));
    }

    /**
     * An error, with the whole cause chain.
     *
     * <p>The stack trace and not just the message: the message says what failed
     * and the trace says where, and a report with only the first is a report
     * that needs a second round of questions.
     */
    public static void error(String message, Throwable failure) {
        write("ERROR", message);
        if (failure == null) {
            return;
        }
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        for (String line : trace.toString().split("\\R")) {
            write("ERROR", "  " + line);
        }
    }

    /**
     * Wraps a {@link Progress} so everything it is told is also written down.
     *
     * <p>The counters are not: a download posts thousands of them, they mean
     * nothing after the fact, and they would bury the lines that do.
     */
    public static Progress tee(Progress inner) {
        return new Progress() {
            @Override
            public void stage(String name) {
                write("INFO", "== " + name);
                inner.stage(name);
            }

            @Override
            public void bytes(long completed, long total) {
                inner.bytes(completed, total);
            }

            @Override
            public void items(int completed, int total) {
                inner.items(completed, total);
            }

            @Override
            public void log(String message) {
                write("INFO", message);
                inner.log(message);
            }

            @Override
            public boolean isCancelled() {
                return inner.isCancelled();
            }
        };
    }

    /** Catches what nothing else did, so a crash leaves a trace behind it. */
    public static void catchUncaught() {
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, failure) -> error("Uncaught in thread " + thread.getName(), failure));
    }

    /** Closes the file. The launcher works the same without it. */
    public static void close() {
        synchronized (LOCK) {
            if (writer == null) {
                return;
            }
            try {
                writer.close();
            } catch (IOException ignored) {
                // Nothing left to report it to.
            }
            writer = null;
        }
    }

    private static String format(String format, Object... args) {
        if (args == null || args.length == 0) {
            return format;
        }
        try {
            return String.format(format, args);
        } catch (RuntimeException e) {
            // A message with a stray % in it is still a message worth keeping.
            return format;
        }
    }

    private static void write(String level, String message) {
        synchronized (LOCK) {
            if (writer == null || capped) {
                return;
            }
            String line = LocalDateTime.now().format(TIME)
                    + " [" + Thread.currentThread().getName() + "] "
                    + level + "  " + Redactor.scrub(message == null ? "null" : message)
                    + System.lineSeparator();
            try {
                writer.write(line);
                writer.flush();
                written += line.length();
                if (written > MAX_BYTES) {
                    writer.write("--- log stopped: it passed " + (MAX_BYTES / (1024 * 1024))
                            + " MB ---" + System.lineSeparator());
                    writer.flush();
                    capped = true;
                }
            } catch (IOException e) {
                // A disk that will not take this line will not take a complaint
                // about it either.
                writer = null;
            }
        }
    }

    /**
     * Moves the previous runs down one, dropping the oldest.
     *
     * <p>Kept as a small fixed set rather than one file per day: a user asked
     * for "the log" should find one obvious file, and the run before the one
     * that went wrong is often the one that explains it.
     */
    private static void rotate(Path folder) {
        Path oldest = folder.resolve("launcher-" + KEPT_RUNS + ".log");
        try {
            Files.deleteIfExists(oldest);
        } catch (IOException ignored) {
            // Then it is overwritten below, or it is not. Either is survivable.
        }
        for (int i = KEPT_RUNS - 1; i >= 1; i--) {
            move(folder.resolve("launcher-" + i + ".log"),
                    folder.resolve("launcher-" + (i + 1) + ".log"));
        }
        move(folder.resolve("launcher.log"), folder.resolve("launcher-1.log"));
    }

    private static void move(Path from, Path to) {
        try {
            if (Files.isRegularFile(from)) {
                Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // A file held open by something else stays where it is.
        }
    }
}
