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

import com.hexadron.launcher.mods.ModEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Finds the mod whose code threw the exception that ended the game.
 *
 * <p>The rules recognise messages. Most crashes have none: a mod dereferences
 * null, and the crash report holds an exception and a stack. The stack names
 * classes, and every class lives in exactly one jar. So the frames are read
 * from the root cause outwards, the game's, the loaders' and the JDK's own
 * classes are passed over, and the first class that a jar in the mods folder
 * contains names the mod.
 *
 * <p>Matched by the class file inside the jar, not by guessing from package
 * names: {@code com.replaymod.replay.InputReplayTimer} is looked up as
 * {@code com/replaymod/replay/InputReplayTimer.class}. A class the launcher
 * cannot find in any jar names nobody.
 */
public final class StackAttribution {

    /** How many classes are looked up; the answer is in the first few frames. */
    static final int MAX_CLASSES = 20;

    /**
     * Packages that belong to the JDK, the game, the loaders and the libraries
     * every modded game carries. A frame in them is where the error passed
     * through, not where it came from.
     */
    static final List<String> NOT_A_MOD = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "net.minecraft.", "com.mojang.", "com.google.", "org.apache.", "org.slf4j.",
            "io.netty.", "it.unimi.", "org.lwjgl.", "org.joml.", "oshi.",
            "net.minecraftforge.", "net.neoforged.", "cpw.mods.", "net.fabricmc.", "org.quiltmc.",
            "org.spongepowered.", "com.llamalad7.mixinextras.", "org.objectweb.asm.",
            "gg.essential.loader.", "kotlin.", "kotlinx.", "scala.", "org.jetbrains.",
            "zone.rong.mixinbooter.", "com.electronwill.nightconfig.", "org.openjdk.");

    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+(?:\\S*/)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)\\.[\\w$<>]+\\(");
    private static final Pattern EXCEPTION = Pattern.compile(
            "^(?:Caused by: |Exception in thread \"[^\"]*\" )?((?:[a-z_$][\\w$]*\\.)+([A-Z][\\w$]*(?:Exception|Error|Throwable)))\\b");

    /**
     * The mod the stack points at.
     *
     * @param mod       the jar
     * @param className the first class of it found in the stack
     * @param error     the simple name of the root exception, e.g. {@code NullPointerException}
     */
    public record Blame(ModEntry mod, String className, String error) {
    }

    /** The exception and the classes to look up, most likely first. */
    record Stack(String error, List<String> classes) {
    }

    private StackAttribution() {
    }

    /** How many threads of a dump are read: the deadlocked ones and the game's main threads come first. */
    static final int THREADS_READ = 3;

    /** Finds the mod, or empty when the stack names no class in any jar. */
    public static Optional<Blame> blame(CrashEvidence evidence, List<ModEntry> mods) {
        return find(stackOf(evidence), mods);
    }

    /**
     * Finds the mod a frozen game was running, from the thread dump the
     * launcher's agent wrote. The agent puts deadlocked threads first, then the
     * render and main threads, so the first few threads are the ones read.
     */
    public static Optional<Blame> blameThreads(CrashEvidence evidence, List<ModEntry> mods) {
        List<String> dump = evidence.lines(CrashRules.Source.THREADS);
        if (dump.isEmpty()) {
            return Optional.empty();
        }
        Set<String> classes = new LinkedHashSet<>();
        int threads = 0;
        for (String line : dump) {
            if (line.startsWith("\"")) {
                if (++threads > THREADS_READ) {
                    break;
                }
                continue;
            }
            if (threads == 0) {
                continue;
            }
            Matcher frame = FRAME.matcher(line);
            if (frame.find() && isModClass(frame.group(1)) && classes.size() < MAX_CLASSES) {
                String name = frame.group(1);
                int inner = name.indexOf('$');
                classes.add(inner > 0 ? name.substring(0, inner) : name);
            }
        }
        return find(Optional.of(new Stack("freeze", List.copyOf(classes))), mods);
    }

    private static Optional<Blame> find(Optional<Stack> stack, List<ModEntry> mods) {
        if (stack.isEmpty() || stack.get().classes().isEmpty()) {
            return Optional.empty();
        }
        List<String> classes = stack.get().classes();
        int best = Integer.MAX_VALUE;
        ModEntry owner = null;
        for (ModEntry mod : mods) {
            if (!mod.enabled()) {
                continue;
            }
            try (ZipFile jar = new ZipFile(mod.path().toFile())) {
                for (int i = 0; i < Math.min(best, classes.size()); i++) {
                    if (jar.getEntry(classes.get(i).replace('.', '/') + ".class") != null) {
                        best = i;
                        owner = mod;
                        break;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Not a readable jar; it owns nothing.
            }
            if (best == 0) {
                break;
            }
        }
        return owner == null ? Optional.empty()
                : Optional.of(new Blame(owner, classes.get(best), stack.get().error()));
    }

    /**
     * The stack of the crash: from the crash report, or, when there is none,
     * from the copy of it the game prints, or from an exception that ended the
     * main thread.
     */
    static Optional<Stack> stackOf(CrashEvidence evidence) {
        List<String> report = evidence.lines(CrashRules.Source.CRASH);
        if (!report.isEmpty()) {
            int start = indexOf(report, "Description:", 0);
            return start < 0 ? Optional.empty() : Optional.of(parse(report, start + 1));
        }
        for (CrashRules.Source source : List.of(CrashRules.Source.OUTPUT, CrashRules.Source.LOG)) {
            List<String> lines = evidence.lines(source);
            int printed = lastIndexOf(lines, "---- Minecraft Crash Report ----");
            if (printed >= 0) {
                int start = indexOf(lines, "Description:", printed);
                if (start >= 0) {
                    return Optional.of(parse(lines, start + 1));
                }
            }
            int main = lastIndexOf(lines, "Exception in thread \"main\"");
            if (main >= 0) {
                return Optional.of(parse(lines, main));
            }
        }
        return Optional.empty();
    }

    /**
     * Reads one exception with its causes, starting at {@code from}, and stops
     * at the first line that is none of those.
     */
    static Stack parse(List<String> lines, int from) {
        List<List<String>> blocks = new ArrayList<>();
        String error = null;
        List<String> current = null;
        for (int i = from; i < lines.size() && i < from + 2000; i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                // A blank line after the frames ends the stack; one before
                // them only separates it from the description.
                if (current != null && !current.isEmpty()) {
                    break;
                }
                continue;
            }
            if (trimmed.startsWith("A detailed walkthrough") || trimmed.startsWith("-- ")) {
                break;
            }
            Matcher exception = EXCEPTION.matcher(trimmed);
            if (exception.find() && !trimmed.startsWith("at ")) {
                current = new ArrayList<>();
                blocks.add(current);
                // The deepest cause is the one that says what went wrong.
                error = exception.group(2);
                continue;
            }
            Matcher frame = FRAME.matcher(line);
            if (frame.find()) {
                if (current == null) {
                    current = new ArrayList<>();
                    blocks.add(current);
                }
                current.add(frame.group(1));
            }
        }
        Set<String> classes = new LinkedHashSet<>();
        for (int b = blocks.size() - 1; b >= 0 && classes.size() < MAX_CLASSES; b--) {
            for (String name : blocks.get(b)) {
                if (isModClass(name)) {
                    int inner = name.indexOf('$');
                    classes.add(inner > 0 ? name.substring(0, inner) : name);
                    if (classes.size() >= MAX_CLASSES) {
                        break;
                    }
                }
            }
        }
        return new Stack(error == null ? "Exception" : error, List.copyOf(classes));
    }

    static boolean isModClass(String className) {
        for (String prefix : NOT_A_MOD) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return className.indexOf('.') > 0;
    }

    private static int indexOf(List<String> lines, String needle, int from) {
        for (int i = Math.max(0, from); i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(List<String> lines, String needle) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
