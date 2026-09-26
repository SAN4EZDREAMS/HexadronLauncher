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

import com.hexadron.launcher.mods.ModDependents;
import com.hexadron.launcher.mods.ModEntry;
import com.hexadron.launcher.mods.ModScan;
import com.hexadron.launcher.profile.Profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * Turns a proposed {@link CrashFix} into something concrete for one profile,
 * and carries out the parts that touch the mods folder.
 *
 * <p>A fix from a rule is a guess made from text: "switch off sodium". Here it
 * meets the files. A fix that would change nothing - the mod is not in the
 * folder, it is already off, the memory is already at the limit - is not
 * offered at all, because a button that does nothing teaches the player to
 * ignore the rest of the window.
 */
public final class CrashFixes {

    /** Memory is set in these steps, as in the profile editor. */
    static final int MEMORY_STEP = 512;
    /** Never more than this, however much the computer has; a larger heap makes pauses longer. */
    static final int MEMORY_CEILING = 16384;
    /** Left to the operating system and everything else. */
    static final int MEMORY_RESERVE = 2048;
    static final int MIN_JAVA = 8;
    static final int MAX_JAVA = 99;

    /**
     * A fix checked against a profile.
     *
     * @param fix        the fix as the rule proposed it
     * @param subject    what the button names: a mod's name, a Java version, a memory size
     * @param targets    the mod files it switches off
     * @param dependents mods that need a target and are switched off with it
     * @param number     the Java version or the new memory limit in MB; 0 otherwise
     */
    public record Prepared(CrashFix fix, String subject, List<ModEntry> targets,
                           List<ModEntry> dependents, int number) {
        public Prepared {
            targets = List.copyOf(targets);
            dependents = List.copyOf(dependents);
        }
    }

    private CrashFixes() {
    }

    /**
     * Checks a fix against a profile and its mods.
     *
     * @param mods           every jar in the profile's mods folder, on and off
     * @param physicalMemory installed memory in MB, or a negative number when unknown
     */
    public static Optional<Prepared> prepare(CrashFix fix, Profile profile, List<ModEntry> mods,
                                             long physicalMemory) {
        return switch (fix.kind()) {
            case DISABLE_MOD -> switchOff(fix, mods, byModId(mods, fix.value()));
            case DISABLE_FILE -> switchOff(fix, mods, byFileName(mods, fix.value()));
            case DISABLE_MIXIN_OWNER -> switchOff(fix, mods, byMixinConfig(mods, fix.value()));
            case DISABLE_DUPLICATES -> duplicates(fix, mods);
            case JAVA -> {
                Integer major = parseMajor(fix.value());
                yield major == null ? Optional.empty()
                        : Optional.of(new Prepared(fix, String.valueOf(major), List.of(), List.of(), major));
            }
            case AUTOMATIC_JAVA -> profile.javaPath() == null ? Optional.empty()
                    : Optional.of(new Prepared(fix, "", List.of(), List.of(), 0));
            case RAISE_MEMORY -> {
                int target = raisedMemory(profile.memoryMegabytes(), physicalMemory);
                yield target <= profile.memoryMegabytes() ? Optional.empty()
                        : Optional.of(new Prepared(fix, gigabytes(target), List.of(), List.of(), target));
            }
            case LOWER_MEMORY -> {
                int target = Profile.defaultMemoryMegabytes();
                yield target >= profile.memoryMegabytes() ? Optional.empty()
                        : Optional.of(new Prepared(fix, gigabytes(target), List.of(), List.of(), target));
            }
            case REINSTALL -> Optional.of(new Prepared(fix, "", List.of(), List.of(), 0));
        };
    }

    /**
     * The next memory limit up: half as much again, at least one more gigabyte,
     * and never past three quarters of the computer's memory or
     * {@link #MEMORY_CEILING}.
     */
    public static int raisedMemory(int current, long physicalMegabytes) {
        long ceiling = physicalMegabytes > 0
                ? Math.min(MEMORY_CEILING, Math.min(physicalMegabytes * 3 / 4,
                        physicalMegabytes - MEMORY_RESERVE))
                : 8192;
        ceiling = ceiling / MEMORY_STEP * MEMORY_STEP;
        long wanted = Math.max(current + 1024L, current * 3L / 2);
        wanted = (wanted + MEMORY_STEP - 1) / MEMORY_STEP * MEMORY_STEP;
        return (int) Math.max(current, Math.min(wanted, ceiling));
    }

    /**
     * Switches off the targets and their dependents.
     *
     * @return the file names switched off, in order
     */
    public static List<String> applySwitchOff(Path modsDir, Prepared prepared) throws IOException {
        List<String> done = new ArrayList<>();
        List<ModEntry> all = new ArrayList<>(prepared.targets());
        all.addAll(prepared.dependents());
        for (ModEntry entry : all) {
            if (entry.enabled() && Files.isRegularFile(entry.path())) {
                ModScan.setEnabled(modsDir, entry, false);
                done.add(entry.fileName());
            }
        }
        return done;
    }

    /** A display name for a mod id, from the jar that carries it; the id itself otherwise. */
    public static String displayName(List<ModEntry> mods, String modId) {
        for (ModEntry entry : byModId(mods, modId)) {
            if (entry.title() != null && !entry.title().isBlank()) {
                return entry.title();
            }
        }
        return modId;
    }

    // ------------------------------------------------------------------ finding

    static List<ModEntry> byModId(List<ModEntry> mods, String modId) {
        List<ModEntry> found = new ArrayList<>();
        if (modId == null || modId.isBlank()) {
            return found;
        }
        String wanted = modId.trim().toLowerCase(Locale.ROOT);
        for (ModEntry entry : mods) {
            String id = ModScan.descriptorOf(entry.path()).modId();
            if (id != null && id.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                found.add(entry);
            }
        }
        return found;
    }

    static List<ModEntry> byFileName(List<ModEntry> mods, String fileName) {
        List<ModEntry> found = new ArrayList<>();
        if (fileName == null || fileName.isBlank()) {
            return found;
        }
        String wanted = ModScan.enabledName(fileName.trim());
        for (ModEntry entry : mods) {
            if (ModScan.enabledName(entry.fileName()).equalsIgnoreCase(wanted)) {
                found.add(entry);
            }
        }
        return found;
    }

    /**
     * The jars that carry a mixin config file. Matched on the name alone, at
     * the root of the jar, which is where every loader looks for it.
     */
    static List<ModEntry> byMixinConfig(List<ModEntry> mods, String config) {
        List<ModEntry> found = new ArrayList<>();
        if (config == null || !config.matches("[A-Za-z0-9_.\\-]{1,100}\\.json")) {
            return found;
        }
        for (ModEntry entry : mods) {
            if (!entry.enabled()) {
                continue;
            }
            try (ZipFile zip = new ZipFile(entry.path().toFile())) {
                if (zip.getEntry(config) != null) {
                    found.add(entry);
                }
            } catch (IOException | RuntimeException e) {
                // Not a readable jar; it cannot be the owner.
            }
        }
        return found;
    }

    private static Optional<Prepared> switchOff(CrashFix fix, List<ModEntry> mods,
                                                List<ModEntry> candidates) {
        List<ModEntry> targets = candidates.stream().filter(ModEntry::enabled).toList();
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        ModDependents dependents = ModDependents.of(mods);
        Map<String, ModEntry> also = new LinkedHashMap<>();
        for (ModEntry target : targets) {
            for (ModEntry dependent : dependents.of(target)) {
                if (dependent.enabled() && targets.stream().noneMatch(t -> t.key().equals(dependent.key()))) {
                    also.putIfAbsent(dependent.key(), dependent);
                }
            }
        }
        String subject = targets.get(0).title() == null || targets.get(0).title().isBlank()
                ? targets.get(0).fileName() : targets.get(0).title();
        return Optional.of(new Prepared(fix, subject, targets, List.copyOf(also.values()), 0));
    }

    /** Keeps the newest file of a mod and switches off the other copies. */
    private static Optional<Prepared> duplicates(CrashFix fix, List<ModEntry> mods) {
        List<ModEntry> copies = new ArrayList<>(byModId(mods, fix.value()).stream()
                .filter(ModEntry::enabled).toList());
        if (copies.size() < 2) {
            return Optional.empty();
        }
        copies.sort(Comparator.comparingLong(CrashFixes::modified).reversed());
        List<ModEntry> older = copies.subList(1, copies.size());
        String subject = copies.get(0).title() == null || copies.get(0).title().isBlank()
                ? fix.value() : copies.get(0).title();
        return Optional.of(new Prepared(fix, subject, older, List.of(), 0));
    }

    private static long modified(ModEntry entry) {
        try {
            return Files.getLastModifiedTime(entry.path()).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    static Integer parseMajor(String value) {
        try {
            int major = Integer.parseInt(value.trim());
            return major >= MIN_JAVA && major <= MAX_JAVA ? major : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String gigabytes(int megabytes) {
        double gb = megabytes / 1024.0;
        return gb == Math.rint(gb) ? String.valueOf((int) gb) : String.format(Locale.ROOT, "%.1f", gb);
    }
}
