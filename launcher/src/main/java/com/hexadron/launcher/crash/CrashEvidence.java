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

package com.hexadron.launcher.crash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a game left behind when it stopped: its output, its log, its crash
 * report and the JVM's fatal error file.
 *
 * <p>Only files written during this run count. An instance keeps every crash
 * report it ever produced, and reading last week's would explain last week's
 * crash. A file counts when it was modified after the launch started, less a
 * small allowance for file systems that store times coarsely.
 *
 * <p>Every file is read up to a limit. The parts that name a cause are at the
 * top of a crash report and of an {@code hs_err} file, and at the end of a log,
 * so those are the parts kept.
 *
 * @param exitCode the game's exit code
 * @param lines    the text of each source, split into lines
 * @param files    the file each source was read from, where there was one
 */
public record CrashEvidence(int exitCode, Map<CrashRules.Source, List<String>> lines,
                            Map<CrashRules.Source, Path> files) {

    static final int MAX_LINE = 4000;
    static final int MAX_CRASH_REPORT = 1024 * 1024;
    static final int MAX_HS_ERR = 1024 * 1024;
    static final int MAX_LOG_TAIL = 4 * 1024 * 1024;
    static final int MAX_THREAD_DUMP = 2 * 1024 * 1024;
    /** Allowance for file systems with two-second timestamps (FAT, some network shares). */
    static final long CLOCK_SLACK_MILLIS = 3000;

    public CrashEvidence {
        Map<CrashRules.Source, List<String>> copy = new EnumMap<>(CrashRules.Source.class);
        lines.forEach((source, text) -> copy.put(source, List.copyOf(text)));
        lines = Collections.unmodifiableMap(copy);
        Map<CrashRules.Source, Path> paths = new EnumMap<>(CrashRules.Source.class);
        paths.putAll(files);
        files = Collections.unmodifiableMap(paths);
    }

    /** Evidence from text alone, for tests and for callers that have it already. */
    public static CrashEvidence of(int exitCode, Map<CrashRules.Source, List<String>> lines) {
        return new CrashEvidence(exitCode, lines, Map.of());
    }

    public List<String> lines(CrashRules.Source source) {
        return lines.getOrDefault(source, List.of());
    }

    /** The crash report of this run, if the game wrote one. */
    public Optional<Path> crashReport() {
        return Optional.ofNullable(files.get(CrashRules.Source.CRASH));
    }

    /** True when there is anything to read at all. */
    public boolean isEmpty() {
        return lines.values().stream().allMatch(List::isEmpty);
    }

    /**
     * Gathers what one run left in its game directory.
     *
     * @param gameDir     the instance's game directory
     * @param startedAt   when the launch began, in epoch milliseconds
     * @param output      the lines the launcher read from the game, already capped
     * @param exitCode    the exit code
     */
    public static CrashEvidence collect(Path gameDir, long startedAt, List<String> output,
                                        int exitCode) {
        Map<CrashRules.Source, List<String>> lines = new EnumMap<>(CrashRules.Source.class);
        Map<CrashRules.Source, Path> files = new EnumMap<>(CrashRules.Source.class);
        lines.put(CrashRules.Source.OUTPUT, clip(output == null ? List.of() : output));

        long since = startedAt - CLOCK_SLACK_MILLIS;
        newest(gameDir.resolve("crash-reports"), "crash-", ".txt", since).ifPresent(file -> {
            files.put(CrashRules.Source.CRASH, file);
            lines.put(CrashRules.Source.CRASH, split(readHead(file, MAX_CRASH_REPORT)));
        });
        // The JVM writes it into its working directory, which is the game directory.
        newest(gameDir, "hs_err_pid", ".log", since).ifPresent(file -> {
            files.put(CrashRules.Source.HS_ERR, file);
            lines.put(CrashRules.Source.HS_ERR, split(readHead(file, MAX_HS_ERR)));
        });
        Path threads = gameDir.resolve(ThreadDumps.DUMP);
        if (isRecentFile(threads, since)) {
            files.put(CrashRules.Source.THREADS, threads);
            lines.put(CrashRules.Source.THREADS, split(readHead(threads, MAX_THREAD_DUMP)));
        }
        Path log = gameDir.resolve("logs").resolve("latest.log");
        if (isRecentFile(log, since)) {
            files.put(CrashRules.Source.LOG, log);
            lines.put(CrashRules.Source.LOG, split(readTail(log, MAX_LOG_TAIL)));
        }
        return new CrashEvidence(exitCode, lines, files);
    }

    /**
     * True when the game logged a fatal error. Some loaders stop on one without
     * a crash report and still exit with code 0 once the player closes their
     * error screen - Forge for 1.12 on duplicate mods, for one.
     */
    public static boolean hasFatalLine(List<String> output) {
        for (String line : output) {
            if (line != null && line.contains("/FATAL]")) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the game wrote a crash report during this run. Minecraft can
     * write one and still exit with code 0, so the exit code alone is not
     * enough to decide there was no crash.
     */
    public static boolean hasCrashReportSince(Path gameDir, long startedAt) {
        return newest(gameDir.resolve("crash-reports"), "crash-", ".txt",
                startedAt - CLOCK_SLACK_MILLIS).isPresent();
    }

    private static Optional<Path> newest(Path dir, String prefix, String suffix, long since) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(suffix)) {
                    continue;
                }
                long time = modified(file);
                if (time >= since && time > bestTime && isRegular(file)) {
                    best = file;
                    bestTime = time;
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(best);
    }

    private static boolean isRecentFile(Path file, long since) {
        return isRegular(file) && modified(file) >= since;
    }

    private static boolean isRegular(Path file) {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    static String readHead(Path file, int limit) {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return new String(in.readNBytes(limit), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    static String readTail(Path file, int limit) {
        try (SeekableByteChannel channel = Files.newByteChannel(file, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            long start = Math.max(0, size - limit);
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(limit, size));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // keep reading until the buffer is full or the file ends
            }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            if (start > 0) {
                // The first line is cut; it would only mislead.
                int newline = text.indexOf('\n');
                text = newline >= 0 ? text.substring(newline + 1) : "";
            }
            return text;
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    static List<String> split(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : text.split("\r?\n", -1)) {
            result.add(line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line);
        }
        return result;
    }

    private static List<String> clip(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line != null) {
                result.add(line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line);
            }
        }
        return result;
    }
}
