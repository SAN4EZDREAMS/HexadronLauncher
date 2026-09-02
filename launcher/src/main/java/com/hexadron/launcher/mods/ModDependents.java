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

package com.hexadron.launcher.mods;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which mods in a folder need which other mods in it.
 *
 * <h2>What this is for</h2>
 *
 * <p>One question, asked at the two moments it matters: switching a mod off and
 * deleting it. A library nobody would install for its own sake - Fabric API,
 * Architectury, a mod's own core - is in the folder because five other mods put
 * it there, and taking it out does not fail at the point of taking it out. It
 * fails on the next launch, in a crash report naming a class the player has
 * never heard of, and the last thing they did was remove something else.
 *
 * <p>So the launcher reads the graph the loader is going to read and says the
 * same thing first, by name: these five mods need this one.
 *
 * <h2>Where the answer comes from</h2>
 *
 * <p>The jars, not the lock file. The lock file records that the launcher
 * installed something as a dependency, which is a fact about an install that
 * happened once; it does not know about the mods the player dropped in
 * afterwards, and it says nothing at all for a library the player installed by
 * hand and five mods later came to need. Each jar declares what it cannot start
 * without, in its own loader's descriptor, and that declaration is what the game
 * enforces.
 *
 * <h2>Enabled mods only</h2>
 *
 * <p>A mod that is switched off is not loaded, so it cannot break, and warning
 * that it will is warning about something that is not going to happen. It comes
 * back into the graph the moment it is switched back on - which is a scan away,
 * because this is rebuilt from the folder every time the list is.
 */
public final class ModDependents {

    /** Nothing depends on anything: the answer for an empty or unread folder. */
    public static final ModDependents NONE = new ModDependents(Map.of());

    /** Keyed by {@link ModEntry#key()} of the mod being depended on. */
    private final Map<String, List<ModEntry>> byKey;

    private ModDependents(Map<String, List<ModEntry>> byKey) {
        this.byKey = byKey;
    }

    /**
     * Reads the graph out of the jars behind these rows.
     *
     * <p>The descriptors are the ones the scan already read: this is a lookup in
     * the same cache, not eighty archives opened a second time.
     */
    public static ModDependents of(List<ModEntry> mods) {
        if (mods == null || mods.size() < 2) {
            return NONE;
        }

        // What each identifier in the folder is. First one wins: two jars
        // claiming the same identifier is a broken folder, and the loader will
        // pick one of them too.
        Map<String, ModEntry> providers = new LinkedHashMap<>();
        for (ModEntry mod : mods) {
            String id = ModScan.descriptorOf(mod.path()).modId();
            if (id != null && !id.isBlank()) {
                providers.putIfAbsent(id.trim().toLowerCase(Locale.ROOT), mod);
            }
        }
        if (providers.isEmpty()) {
            return NONE;
        }

        Map<String, List<ModEntry>> found = new LinkedHashMap<>();
        for (ModEntry mod : mods) {
            if (!mod.enabled()) {
                continue;
            }
            for (String id : ModScan.descriptorOf(mod.path()).depends()) {
                ModEntry provider = providers.get(id);
                // A mod that requires something not in the folder is a different
                // problem, and one the launcher cannot fix by keeping a file.
                if (provider == null || provider.key().equals(mod.key())) {
                    continue;
                }
                found.computeIfAbsent(provider.key(), ignored -> new ArrayList<>()).add(mod);
            }
        }
        if (found.isEmpty()) {
            return NONE;
        }

        Map<String, List<ModEntry>> sorted = new LinkedHashMap<>();
        found.forEach((key, dependents) -> {
            dependents.sort(Comparator.comparing(ModEntry::title, String.CASE_INSENSITIVE_ORDER));
            sorted.put(key, List.copyOf(dependents));
        });
        return new ModDependents(Map.copyOf(sorted));
    }

    /** The mods that need this one, by name, or an empty list. */
    public List<ModEntry> of(ModEntry mod) {
        if (mod == null) {
            return List.of();
        }
        return byKey.getOrDefault(mod.key(), List.of());
    }

    /** True when taking this mod out would leave something behind that needs it. */
    public boolean isNeeded(ModEntry mod) {
        return !of(mod).isEmpty();
    }

    /** How many mods are depended on by something. For a check, not for a user. */
    public int size() {
        return byKey.size();
    }
}
