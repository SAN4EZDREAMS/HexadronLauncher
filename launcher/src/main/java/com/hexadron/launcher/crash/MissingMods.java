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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Every mod a crash says is missing, gathered from all its causes into one list.
 *
 * <p>One crash often names several: each mod that needs a library is its own
 * line in the loader's error, and two mods can need the same one. The crash
 * window shows them together, with one button that installs all of them and,
 * for the ones nobody publishes for this profile, one that switches off what
 * needs them and a web search for each.
 *
 * @param needs            the missing mods, each once, in the order the causes named them
 * @param minecraftVersion the profile's Minecraft version, for the web search
 * @param loaderName       the profile's loader as people write it ("NeoForge"), for the web search
 */
public record MissingMods(List<Need> needs, String minecraftVersion, String loaderName) {

    public static final MissingMods NONE = new MissingMods(List.of(), "", "");

    public MissingMods {
        needs = List.copyOf(needs);
        minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
        loaderName = loaderName == null ? "" : loaderName;
    }

    /**
     * One missing mod.
     *
     * @param id          the mod id the loader asked for
     * @param name        what to call it: the name the library list or the platform gives it, or the id
     * @param neededBy    the ids of the mods that need it
     * @param neededNames the same mods by the names in their jars
     * @param supply      the fix that brings it - an install from a platform, or a
     *                    switched-off copy switched on; empty when none was found
     */
    public record Need(String id, String name, List<String> neededBy, List<String> neededNames,
                       Optional<CrashFixes.Prepared> supply) {
        public Need {
            neededBy = List.copyOf(neededBy);
            neededNames = List.copyOf(neededNames);
        }
    }

    public boolean isEmpty() {
        return needs.isEmpty();
    }

    /** The words to search the web with: version, loader and name, as a player would type them. */
    public String searchQuery(Need need) {
        StringBuilder query = new StringBuilder("Minecraft");
        if (!minecraftVersion.isBlank()) {
            query.append(' ').append(minecraftVersion);
        }
        if (!loaderName.isBlank()) {
            query.append(' ').append(loaderName);
        }
        return query.append(' ').append(need.name()).toString();
    }

    /**
     * Gathers the missing mods out of the causes and the fixes prepared for them.
     *
     * @param names the name of a mod id: the one in its jar, the library list's, or the id
     */
    public static MissingMods of(List<CrashAnalyzer.Diagnosis> diagnoses,
                                 Map<CrashAnalyzer.Diagnosis, List<CrashFixes.Prepared>> fixes,
                                 UnaryOperator<String> names, String minecraftVersion, String loaderName) {
        Map<String, String> firstId = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> askers = new LinkedHashMap<>();
        Map<String, CrashFixes.Prepared> supplies = new LinkedHashMap<>();
        for (CrashAnalyzer.Diagnosis diagnosis : diagnoses) {
            if (!isMissingMod(diagnosis)) {
                continue;
            }
            String dep = diagnosis.values().get("dep").trim();
            String key = dep.toLowerCase(Locale.ROOT);
            firstId.putIfAbsent(key, dep);
            LinkedHashSet<String> by = askers.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            String mod = diagnosis.values().get("mod");
            if (mod != null && !mod.isBlank()) {
                by.add(mod.trim());
            }
            if (!supplies.containsKey(key)) {
                fixes.getOrDefault(diagnosis, List.of()).stream()
                        .filter(fix -> fix.fix().kind() == CrashFix.Kind.INSTALL_MOD
                                || fix.fix().kind() == CrashFix.Kind.ENABLE_FILE)
                        .findFirst().ifPresent(fix -> supplies.put(key, fix));
            }
        }
        List<Need> needs = new ArrayList<>();
        firstId.forEach((key, id) -> {
            CrashFixes.Prepared supply = supplies.get(key);
            String name = names.apply(id);
            if ((name == null || name.equalsIgnoreCase(id)) && supply != null
                    && supply.fix().kind() == CrashFix.Kind.INSTALL_MOD) {
                // The platform's title of the project that would be installed.
                name = supply.subject().replace(" (CurseForge)", "");
            }
            List<String> by = List.copyOf(askers.get(key));
            needs.add(new Need(id, name == null || name.isBlank() ? id : name, by,
                    by.stream().map(names).toList(), Optional.ofNullable(supply)));
        });
        return new MissingMods(needs, minecraftVersion, loaderName);
    }

    /** A cause that is a missing mod, and not the game, Java or a loader. */
    public static boolean isMissingMod(CrashAnalyzer.Diagnosis diagnosis) {
        if (!"missingDep".equals(diagnosis.textId())) {
            return false;
        }
        String dep = diagnosis.values().get("dep");
        return dep != null && !dep.isBlank() && !CrashFixes.isLoaderId(dep)
                && !"minecraft".equalsIgnoreCase(dep.trim()) && !"java".equalsIgnoreCase(dep.trim());
    }
}
