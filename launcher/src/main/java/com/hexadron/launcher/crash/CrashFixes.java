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
import com.hexadron.launcher.mods.VersionRanges;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.profile.Profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
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
     * @param files      the files it renames that are not mods: configuration files
     * @param reference  what the service resolved: a Modrinth project id, a loader version; empty otherwise
     */
    public record Prepared(CrashFix fix, String subject, List<ModEntry> targets,
                           List<ModEntry> dependents, int number, List<Path> files, String reference) {
        public Prepared {
            targets = List.copyOf(targets);
            dependents = List.copyOf(dependents);
            files = List.copyOf(files);
            reference = reference == null ? "" : reference;
        }

        public Prepared(CrashFix fix, String subject, List<ModEntry> targets,
                        List<ModEntry> dependents, int number) {
            this(fix, subject, targets, dependents, number, List.of(), "");
        }

        /** The same fix with what the service found out: a name to show and what to act on. */
        public Prepared resolved(String newSubject, String newReference) {
            return new Prepared(fix, newSubject, targets, dependents, number, files, newReference);
        }

        /** Two fixes that would do the same thing: one is enough. */
        public String sameAs() {
            return fix.kind() + "|" + (reference.isEmpty() ? fix.value().toLowerCase(Locale.ROOT) : reference)
                    + "|" + targets.stream().map(ModEntry::fileName).toList();
        }
    }

    /** Fixes that repair rather than take something away: offered first, and applied by one click. */
    static final Set<CrashFix.Kind> REPAIRS = EnumSet.of(CrashFix.Kind.INSTALL_MOD, CrashFix.Kind.ENABLE_FILE,
            CrashFix.Kind.UPDATE_LOADER, CrashFix.Kind.RESET_CONFIG, CrashFix.Kind.REMOVE_JVM_ARGUMENT,
            CrashFix.Kind.JAVA, CrashFix.Kind.AUTOMATIC_JAVA, CrashFix.Kind.DISABLE_DUPLICATES,
            CrashFix.Kind.REINSTALL, CrashFix.Kind.RAISE_MEMORY, CrashFix.Kind.LOWER_MEMORY);

    /**
     * The fix to apply without asking the player to choose: the only one
     * offered, or the first when it repairs. Two ways to switch a mod off
     * (two incompatible mods) is a choice, and there is no recommendation.
     */
    public static Optional<Prepared> recommended(List<Prepared> offered) {
        if (offered.isEmpty()) {
            return Optional.empty();
        }
        if (offered.size() == 1 || REPAIRS.contains(offered.get(0).fix().kind())) {
            return Optional.of(offered.get(0));
        }
        return Optional.empty();
    }

    /** The mod ids that name a loader, not a mod. */
    public static boolean isLoaderId(String id) {
        return loaderOf(id) != null;
    }

    /** The loader a dependency id names, or null: {@code forge}, {@code neoforge}, {@code fabricloader}. */
    public static LoaderType loaderOf(String id) {
        if (id == null) {
            return null;
        }
        return switch (id.trim().toLowerCase(Locale.ROOT)) {
            case "forge" -> LoaderType.FORGE;
            case "neoforge" -> LoaderType.NEOFORGE;
            case "fabricloader", "fabric-loader" -> LoaderType.FABRIC;
            case "quilt_loader" -> LoaderType.QUILT;
            default -> null;
        };
    }

    /**
     * The fixes the launcher adds to a cause by itself, before the rule's own:
     * the ones that need more than a rule file can say. A missing mod is
     * installed rather than its dependent switched off; a loader that is too
     * old is updated; a damaged configuration file is reset; an option Java
     * refuses is taken out.
     *
     * <p>Kept out of the rule file on purpose. A launcher refuses a whole rule
     * file that names a fix it does not know, so a rule that used these kinds
     * would stop every older launcher from taking any rule update at all.
     */
    public static CrashAnalyzer.Diagnosis withDerived(CrashAnalyzer.Diagnosis diagnosis, LoaderType loader) {
        List<CrashFix> extra = new ArrayList<>();
        Map<String, String> values = diagnosis.values();
        String dep = values.get("dep");
        switch (diagnosis.textId()) {
            case "missingDep" -> {
                if (dep != null && !isLoaderId(dep) && !"minecraft".equalsIgnoreCase(dep)
                        && !"java".equalsIgnoreCase(dep)) {
                    extra.add(new CrashFix(CrashFix.Kind.INSTALL_MOD, dep));
                }
            }
            case "depVersion" -> {
                LoaderType named = loaderOf(dep);
                if (named != null && (named == loader || (named == LoaderType.FABRIC && loader == LoaderType.QUILT))) {
                    extra.add(new CrashFix(CrashFix.Kind.UPDATE_LOADER, ""));
                }
            }
            case "brokenConfig" -> {
                if (values.get("config") != null) {
                    extra.add(new CrashFix(CrashFix.Kind.RESET_CONFIG, values.get("config")));
                }
            }
            case "jvmOptions" -> {
                if (values.get("option") != null) {
                    extra.add(new CrashFix(CrashFix.Kind.REMOVE_JVM_ARGUMENT, values.get("option")));
                }
                extra.add(new CrashFix(CrashFix.Kind.AUTOMATIC_JAVA, ""));
            }
            default -> {
            }
        }
        if (extra.isEmpty()) {
            return diagnosis;
        }
        List<CrashFix> fixes = new ArrayList<>(extra);
        for (CrashFix fix : diagnosis.fixes()) {
            if (!fixes.contains(fix)) {
                fixes.add(fix);
            }
        }
        return new CrashAnalyzer.Diagnosis(diagnosis.ruleId(), diagnosis.textId(), diagnosis.priority(),
                diagnosis.title(), diagnosis.cause(), diagnosis.advice(), fixes, values,
                diagnosis.source(), diagnosis.line());
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
        return prepare(fix, profile, mods, physicalMemory, null);
    }

    /**
     * @param gameDir the profile's game folder, for fixes that touch files
     *                outside the mods folder; null when there is none
     */
    public static Optional<Prepared> prepare(CrashFix fix, Profile profile, List<ModEntry> mods,
                                             long physicalMemory, Path gameDir) {
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
            case AUTOMATIC_JAVA -> profile == null || (profile.javaPath() == null && profile.javaMajor() == null)
                    ? Optional.empty()
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
            case INSTALL_MOD -> installOrSwitchOn(fix, mods);
            case ENABLE_FILE -> switchOn(fix, byFileName(mods, fix.value()));
            case UPDATE_LOADER -> profile == null || profile.loader() == null || profile.loader() == LoaderType.VANILLA
                    ? Optional.empty()
                    : Optional.of(new Prepared(fix, profile.loader().displayName(), List.of(), List.of(), 0));
            case RESET_CONFIG -> configFiles(fix, gameDir);
            case REMOVE_JVM_ARGUMENT -> jvmArguments(fix, profile);
        };
    }

    /**
     * A missing mod: switched on again when the folder has it switched off,
     * otherwise left for the service to look up. Nothing when it is on already.
     */
    private static Optional<Prepared> installOrSwitchOn(CrashFix fix, List<ModEntry> mods) {
        List<ModEntry> copies = byModId(mods, fix.value());
        if (copies.stream().anyMatch(ModEntry::enabled)) {
            return Optional.empty();
        }
        if (!copies.isEmpty()) {
            ModEntry keep = newestCopy(copies);
            return switchOn(new CrashFix(CrashFix.Kind.ENABLE_FILE, keep.fileName()), List.of(keep));
        }
        return Optional.of(new Prepared(fix, fix.value(), List.of(), List.of(), 0));
    }

    private static Optional<Prepared> switchOn(CrashFix fix, List<ModEntry> candidates) {
        List<ModEntry> off = candidates.stream().filter(entry -> !entry.enabled()).toList();
        if (off.isEmpty()) {
            return Optional.empty();
        }
        ModEntry first = off.get(0);
        return Optional.of(new Prepared(fix, nameOf(first), List.of(first), List.of(), 0));
    }

    /** Configuration file names a rule may name: a plain file name, of a kind mods write. */
    private static final java.util.regex.Pattern CONFIG_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.\\-]{1,100}\\.(?:toml|json|json5|cfg|properties|txt|yml|yaml)");

    /**
     * Where a configuration file of that name lives: {@code config/}, the
     * defaults Forge copies into new worlds, and each world's own copy.
     */
    private static Optional<Prepared> configFiles(CrashFix fix, Path gameDir) {
        if (gameDir == null || !CONFIG_NAME.matcher(fix.value()).matches() || fix.value().startsWith(".")) {
            return Optional.empty();
        }
        List<Path> found = new ArrayList<>();
        for (Path dir : List.of(gameDir.resolve("config"), gameDir.resolve("defaultconfigs"))) {
            Path file = dir.resolve(fix.value());
            if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                found.add(file);
            }
        }
        Path saves = gameDir.resolve("saves");
        if (Files.isDirectory(saves)) {
            try (java.util.stream.Stream<Path> worlds = Files.list(saves)) {
                worlds.limit(500).map(world -> world.resolve("serverconfig").resolve(fix.value()))
                        .filter(file -> Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .forEach(found::add);
            } catch (IOException | RuntimeException e) {
                // The worlds are an extra place to look, not the first one.
            }
        }
        return found.isEmpty() ? Optional.empty()
                : Optional.of(new Prepared(fix, fix.value(), List.of(), List.of(), 0, found, ""));
    }

    /**
     * Renames each damaged configuration file to {@code <name>.broken}, with a
     * number when that is taken. Nothing is deleted: the old file stays next
     * to the new one the mod writes, for anyone who wants their settings back.
     *
     * @return the new names
     */
    public static List<String> applyResetConfig(Prepared prepared) throws IOException {
        List<String> done = new ArrayList<>();
        for (Path file : prepared.files()) {
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path target = file.resolveSibling(file.getFileName() + ".broken");
            for (int n = 2; Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS) && n < 1000; n++) {
                target = file.resolveSibling(file.getFileName() + ".broken-" + n);
            }
            Files.move(file, target);
            done.add(target.getFileName().toString());
        }
        return done;
    }

    /** The profile's own Java arguments that carry the refused option. */
    private static Optional<Prepared> jvmArguments(CrashFix fix, Profile profile) {
        if (profile == null) {
            return Optional.empty();
        }
        List<String> matching = matchingArguments(profile.extraJvmArguments(), fix.value());
        return matching.isEmpty() ? Optional.empty()
                : Optional.of(new Prepared(fix, String.join(" ", matching), List.of(), List.of(), 0));
    }

    /**
     * The arguments an option names: the one that carries it, and for a
     * {@code --option value} pair the value after it too, so no half of a
     * pair is left behind to be refused next.
     */
    public static List<String> matchingArguments(List<String> arguments, String option) {
        List<String> matching = new ArrayList<>();
        if (arguments == null || option == null || option.trim().length() < 3) {
            return matching;
        }
        String wanted = option.trim();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument == null || !(argument.equals(wanted) || argument.contains(wanted))) {
                continue;
            }
            matching.add(argument);
            if (argument.startsWith("--") && !argument.contains("=") && i + 1 < arguments.size()
                    && !arguments.get(i + 1).startsWith("-")) {
                matching.add(arguments.get(++i));
            }
        }
        return matching;
    }

    /** The arguments with those of an option taken out. */
    public static List<String> withoutArguments(List<String> arguments, String option) {
        List<String> remove = matchingArguments(arguments, option);
        List<String> kept = new ArrayList<>();
        int skip = 0;
        for (String argument : arguments) {
            if (skip < remove.size() && argument.equals(remove.get(skip))) {
                skip++;
                continue;
            }
            kept.add(argument);
        }
        return kept;
    }

    /** Switches the targets back on. */
    public static List<String> applySwitchOn(Path modsDir, Prepared prepared) throws IOException {
        List<String> done = new ArrayList<>();
        for (ModEntry entry : prepared.targets()) {
            if (!entry.enabled() && Files.isRegularFile(entry.path())) {
                ModScan.setEnabled(modsDir, entry, true);
                done.add(ModScan.enabledName(entry.fileName()));
            }
        }
        return done;
    }

    private static String nameOf(ModEntry entry) {
        return entry.title() == null || entry.title().isBlank() ? entry.fileName() : entry.title();
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

    public static List<ModEntry> byModId(List<ModEntry> mods, String modId) {
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

    /**
     * The mods that are switched on in more than one file, by mod id, in the
     * order of the folder. Jars whose descriptor names no id take no part: two
     * of them are not the same mod for any loader.
     */
    public static Map<String, List<ModEntry>> duplicateGroups(List<ModEntry> mods) {
        Map<String, List<ModEntry>> byId = new LinkedHashMap<>();
        for (ModEntry entry : mods) {
            if (!entry.enabled()) {
                continue;
            }
            String id = ModScan.descriptorOf(entry.path()).modId();
            if (id == null || id.isBlank()) {
                continue;
            }
            byId.computeIfAbsent(id.trim().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(entry);
        }
        byId.values().removeIf(copies -> copies.size() < 2);
        return byId;
    }

    /** The copy of a mod that {@code disableDuplicates} keeps: the newest version. */
    public static ModEntry newestCopy(List<ModEntry> copies) {
        ModEntry newest = copies.get(0);
        for (ModEntry copy : copies.subList(1, copies.size())) {
            if (isNewer(copy, newest)) {
                newest = copy;
            }
        }
        return newest;
    }

    /**
     * Keeps the newest copy of a mod and switches off the others.
     *
     * <p>Newest by the version in the jar. The file date decides only between
     * copies whose versions are equal or unreadable: it records when a file was
     * downloaded or copied, not how new the mod is, and by date alone
     * Controlling 3.0.12.2 copied in after 3.0.12.4 was the one kept.
     */
    private static Optional<Prepared> duplicates(CrashFix fix, List<ModEntry> mods) {
        List<ModEntry> copies = new ArrayList<>(byModId(mods, fix.value()).stream()
                .filter(ModEntry::enabled).toList());
        if (copies.size() < 2) {
            return Optional.empty();
        }
        ModEntry keep = newestCopy(copies);
        List<ModEntry> older = copies.stream().filter(copy -> copy != keep).toList();
        String subject = keep.title() == null || keep.title().isBlank() ? fix.value() : keep.title();
        return Optional.of(new Prepared(fix, subject, older, List.of(), 0));
    }

    private static boolean isNewer(ModEntry candidate, ModEntry than) {
        int byVersion = VersionRanges.compare(candidate.version(), than.version());
        return byVersion != 0 ? byVersion > 0 : modified(candidate) > modified(than);
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
