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

import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.mods.ModEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * A crash because code was not where a mod expected it: a class that no jar
 * has ({@code NoClassDefFoundError}, {@code ClassNotFoundException}), or a
 * method that is not in the class that does exist ({@code NoSuchMethodError}).
 *
 * <p>The stack of such a crash names the mod that asked, and the stack
 * attribution would offer to switch that mod off. Usually the better answer is
 * the other side: the library it asked for is switched off, or not installed,
 * or has another version. The missing class is looked up in every jar of the
 * folder, the way {@link StackAttribution} looks up the classes of a stack.
 *
 * <p>Only the exception of the crash itself is read - the crash report, or the
 * report or {@code main} exception the game printed. A log is full of
 * {@code ClassNotFoundException}s that mods catch on purpose while they look
 * for optional companions, and none of those is a cause.
 */
public final class Linkage {

    /** What went missing. */
    public enum Kind { CLASS, METHOD }

    /** The class that was missing, or the class a missing method was looked for in. */
    public record Finding(Kind kind, String className, String line) {
    }

    /**
     * A diagnosis, and the mod whose code asked for what was missing.
     *
     * @param asker the mod the stack names, or null when it names none
     */
    public record Explained(CrashAnalyzer.Diagnosis diagnosis, ModEntry asker) {
    }

    static final Pattern MISSING_CLASS = Pattern.compile(
            "java\\.lang\\.(?:NoClassDefFoundError|ClassNotFoundException): (?!Could not initialize)"
                    + "([A-Za-z_$][\\w$]*(?:[./][A-Za-z_$][\\w$]*)+)");
    static final Pattern MISSING_METHOD = Pattern.compile(
            "java\\.lang\\.NoSuchMethodError: '?(?:[\\w$.\\[\\]<>]+ )?"
                    + "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)\\.[\\w$<>]+\\(");

    /** How far into a printed exception the causes are looked for. */
    static final int MAX_LINES = 400;

    private Linkage() {
    }

    /** The deepest missing class or method of the crash's exception, if it is one. */
    public static Optional<Finding> find(CrashEvidence evidence) {
        List<String> block = exceptionOf(evidence);
        Finding found = null;
        // The deepest cause is printed last, and it is the one that says what went wrong.
        for (String line : block) {
            Matcher method = MISSING_METHOD.matcher(line);
            if (method.find()) {
                found = new Finding(Kind.METHOD, method.group(1), line.trim());
                continue;
            }
            Matcher missing = MISSING_CLASS.matcher(line);
            if (missing.find()) {
                String name = missing.group(1).replace('/', '.');
                found = new Finding(Kind.CLASS, name, line.trim());
            }
        }
        if (found == null || isJdkClass(found.className())) {
            // A JDK class that is not there is a Java version question, which
            // the Java rules answer.
            return Optional.empty();
        }
        return Optional.of(found);
    }

    /**
     * Explains a missing class or method, with the fixes that follow from it.
     *
     * @param loader the profile's loader; a library that serves another one is not offered
     */
    public static Optional<Explained> explain(CrashEvidence evidence, List<ModEntry> mods, CrashRules rules,
                                              String language, LoaderType loader) {
        Optional<Finding> finding = find(evidence);
        if (finding.isEmpty()) {
            return Optional.empty();
        }
        Finding f = finding.get();
        String loaderKey = loader == null ? "" : loader.name().toLowerCase(Locale.ROOT);
        ModEntry asker = StackAttribution.blame(evidence, mods).map(StackAttribution.Blame::mod).orElse(null);
        CrashRules.Source source = evidence.crashReport().isPresent()
                ? CrashRules.Source.CRASH : CrashRules.Source.OUTPUT;
        String cls = f.className();

        Optional<CrashAnalyzer.Diagnosis> diagnosis = Optional.empty();
        if (f.kind() == Kind.CLASS) {
            if (owner(cls, mods, true) != null) {
                // The class is there. It failed to load for another reason,
                // which the stack says more about than this does.
                return Optional.empty();
            }
            ModEntry off = owner(cls, mods, false);
            Optional<CrashRules.Library> library = rules.libraryForClass(cls, loaderKey);
            if (off != null) {
                diagnosis = CrashAnalyzer.describe(rules, language, "library-off", 12, CrashRules.TEXT_LIBRARY_OFF,
                        Map.of("library", nameOf(off), "class", cls),
                        List.of(new CrashFix(CrashFix.Kind.ENABLE_FILE, off.fileName())), source, f.line());
            } else if (isGameClass(cls)) {
                diagnosis = asker == null ? Optional.empty()
                        : CrashAnalyzer.describe(rules, language, "missing-game-class", 12, "wrongMinecraft",
                                Map.of("mod", nameOf(asker)), disable(asker), source, f.line());
            } else if (library.isPresent()) {
                List<CrashFix> fixes = new ArrayList<>();
                fixes.add(new CrashFix(CrashFix.Kind.INSTALL_MOD, library.get().ids().get(0)));
                fixes.addAll(disable(asker));
                diagnosis = CrashAnalyzer.describe(rules, language, "missing-library", 12,
                        CrashRules.TEXT_MISSING_LIBRARY,
                        Map.of("library", library.get().name(), "class", cls), fixes, source, f.line());
            } else if (isLoaderClass(cls)) {
                diagnosis = asker == null ? Optional.empty()
                        : CrashAnalyzer.describe(rules, language, "missing-loader-class", 12, "wrongLoader",
                                Map.of("file", asker.fileName()), disable(asker), source, f.line());
            } else if (asker != null) {
                diagnosis = CrashAnalyzer.describe(rules, language, "missing-class", 12, CrashRules.TEXT_MISSING_CLASS,
                        Map.of("mod", nameOf(asker), "class", cls), disable(asker), source, f.line());
            }
        } else if (asker != null) {
            if (isGameClass(cls)) {
                diagnosis = CrashAnalyzer.describe(rules, language, "method-game", 12, "wrongMinecraft",
                        Map.of("mod", nameOf(asker)), disable(asker), source, f.line());
            } else if (isLoaderClass(cls) && loader != null && loader != LoaderType.VANILLA) {
                List<CrashFix> fixes = new ArrayList<>();
                fixes.add(new CrashFix(CrashFix.Kind.UPDATE_LOADER, ""));
                fixes.addAll(disable(asker));
                diagnosis = CrashAnalyzer.describe(rules, language, "method-loader", 12, "depVersion",
                        Map.of("mod", nameOf(asker), "dep", loader.displayName()), fixes, source, f.line());
            } else {
                ModEntry owner = owner(cls, mods, true);
                if (owner != null && !owner.fileName().equals(asker.fileName())) {
                    diagnosis = CrashAnalyzer.describe(rules, language, "method-mod", 12, "depVersion",
                            Map.of("mod", nameOf(asker), "dep", nameOf(owner)), disable(asker), source, f.line());
                }
            }
        }
        return diagnosis.map(d -> new Explained(d, asker));
    }

    static boolean isJdkClass(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.sun.");
    }

    static boolean isGameClass(String name) {
        return (name.startsWith("net.minecraft.") && !name.startsWith("net.minecraft.launchwrapper."))
                || name.startsWith("com.mojang.blaze3d.") || name.startsWith("com.mojang.math.");
    }

    static boolean isLoaderClass(String name) {
        return name.startsWith("net.minecraftforge.") || name.startsWith("net.neoforged.")
                || name.startsWith("net.fabricmc.loader.") || name.startsWith("org.quiltmc.loader.")
                || name.startsWith("cpw.mods.") || name.startsWith("net.minecraft.launchwrapper.");
    }

    private static List<CrashFix> disable(ModEntry mod) {
        return mod == null ? List.of() : List.of(new CrashFix(CrashFix.Kind.DISABLE_FILE, mod.fileName()));
    }

    private static String nameOf(ModEntry mod) {
        return mod.title() == null || mod.title().isBlank() ? mod.fileName() : mod.title();
    }

    /** The jar, switched on or off as asked, that has this class. */
    static ModEntry owner(String className, List<ModEntry> mods, boolean enabled) {
        String entry = className.replace('.', '/') + ".class";
        for (ModEntry mod : mods) {
            if (mod.enabled() != enabled) {
                continue;
            }
            try (ZipFile jar = new ZipFile(mod.path().toFile())) {
                if (jar.getEntry(entry) != null) {
                    return mod;
                }
            } catch (IOException | RuntimeException e) {
                // Not a readable jar; it has nothing.
            }
        }
        return null;
    }

    /**
     * The lines of the crash's own exception: the crash report from its
     * description on, or the last report or {@code main} exception printed.
     */
    static List<String> exceptionOf(CrashEvidence evidence) {
        List<String> report = evidence.lines(CrashRules.Source.CRASH);
        if (!report.isEmpty()) {
            return slice(report, indexOf(report, "Description:", 0));
        }
        for (CrashRules.Source source : List.of(CrashRules.Source.OUTPUT, CrashRules.Source.LOG)) {
            List<String> lines = evidence.lines(source);
            int printed = lastIndexOf(lines, "---- Minecraft Crash Report ----");
            if (printed >= 0) {
                return slice(lines, indexOf(lines, "Description:", printed));
            }
            int main = lastIndexOf(lines, "Exception in thread \"main\"");
            if (main >= 0) {
                return slice(lines, main);
            }
        }
        return List.of();
    }

    private static List<String> slice(List<String> lines, int from) {
        if (from < 0) {
            return List.of();
        }
        List<String> block = new ArrayList<>();
        for (int i = from; i < lines.size() && i < from + MAX_LINES; i++) {
            String line = lines.get(i);
            if (line.contains("A detailed walkthrough of the error")) {
                break;
            }
            block.add(line);
        }
        return block;
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
