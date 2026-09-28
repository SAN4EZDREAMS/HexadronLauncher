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
import com.hexadron.launcher.mods.ModScan;
import com.hexadron.launcher.mods.Requirements;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Mods in one folder that the rule file says do not work together, found
 * before the launch: OptiFine with Sodium or Iris, for one. Neither is broken
 * alone, so the crash analysis would name one of them at random and the
 * problem-mod search would spend launches finding a pair that is known.
 */
public final class Conflicts {

    /** The id OptiFine is known by here; it declares none. */
    public static final String OPTIFINE = "optifine";

    /**
     * One conflict in the folder.
     *
     * @param first  the switched-on mods of the rule's {@code mods} side
     * @param second those of its {@code with} side
     */
    public record Found(String id, List<ModEntry> first, List<ModEntry> second) {
        public Found {
            first = List.copyOf(first);
            second = List.copyOf(second);
        }
    }

    /** Classes only OptiFine's jar has, in the builds for Forge, for Fabric and standalone. */
    static final List<String> OPTIFINE_CLASSES = List.of(
            "optifine/OptiFineClassTransformer.class", "optifine/OptiFineTweaker.class",
            "optifine/OptiFineForgeTweaker.class", "optifine/Installer.class", "net/optifine/Config.class");

    private Conflicts() {
    }

    /** True for an OptiFine jar, found by the classes in it. */
    public static boolean isOptiFine(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String name : OPTIFINE_CLASSES) {
                if (zip.getEntry(name) != null) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not a readable jar.
        }
        return false;
    }

    /** The ids a jar answers to for a conflict: what it provides, and {@code optifine} for OptiFine. */
    public static Set<String> idsOf(ModEntry mod) {
        Set<String> ids = new LinkedHashSet<>(Requirements.provided(mod.path(),
                ModScan.descriptorOf(mod.path()).modId()));
        if (isOptiFine(mod.path())) {
            ids.add(OPTIFINE);
        }
        return ids;
    }

    /** The known conflicts among the switched-on mods. */
    public static List<Found> find(List<ModEntry> mods, List<CrashRules.Conflict> known) {
        List<Found> found = new ArrayList<>();
        if (known.isEmpty()) {
            return found;
        }
        List<ModEntry> on = mods.stream().filter(ModEntry::enabled).toList();
        List<Set<String>> ids = on.stream().map(Conflicts::idsOf).toList();
        for (CrashRules.Conflict conflict : known) {
            List<ModEntry> first = new ArrayList<>();
            List<ModEntry> second = new ArrayList<>();
            for (int i = 0; i < on.size(); i++) {
                Set<String> mine = ids.get(i);
                if (conflict.mods().stream().anyMatch(mine::contains)) {
                    first.add(on.get(i));
                } else if (conflict.with().stream().anyMatch(mine::contains)) {
                    second.add(on.get(i));
                }
            }
            if (!first.isEmpty() && !second.isEmpty()) {
                found.add(new Found(conflict.id(), first, second));
            }
        }
        return found;
    }
}
