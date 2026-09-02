/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.install.loader.forge;

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.util.MavenCoordinate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One step of a modern Forge or NeoForge install: a separate Java program that
 * has to be executed locally to turn the vanilla client jar into the patched one
 * the loader launches.
 *
 * @param sides     which installs this step belongs to - {@code client},
 *                  {@code server} or {@code extract}. Empty means every side,
 *                  which is the format's default and not the same as "none"
 * @param jar       the program to run
 * @param classpath what has to be on its classpath besides itself
 * @param args      its arguments, still holding unresolved tokens
 * @param outputs   files this step must produce. The key names the file and the
 *                  value is its expected SHA-1, both written in the same token
 *                  language as {@code args}. This is what makes a re-run cheap:
 *                  a step whose outputs are already correct is skipped
 */
public record ForgeProcessor(List<String> sides, MavenCoordinate jar,
                             List<MavenCoordinate> classpath, List<String> args,
                             Map<String, String> outputs) {

    public ForgeProcessor {
        sides = List.copyOf(sides);
        classpath = List.copyOf(classpath);
        args = List.copyOf(args);
        outputs = Map.copyOf(outputs);
    }

    public static ForgeProcessor parse(Json json) {
        String jar = json.get("jar").asString(null);
        if (jar == null || jar.isBlank()) {
            throw new IllegalArgumentException("a processor entry has no 'jar': " + json);
        }

        List<String> sides = new ArrayList<>();
        for (Json side : json.get("sides").elements()) {
            String value = side.asString(null);
            if (value != null) {
                sides.add(value);
            }
        }

        List<MavenCoordinate> classpath = new ArrayList<>();
        for (Json entry : json.get("classpath").elements()) {
            String value = entry.asString(null);
            if (value != null && !value.isBlank()) {
                classpath.add(MavenCoordinate.parse(value));
            }
        }

        List<String> args = new ArrayList<>();
        for (Json entry : json.get("args").elements()) {
            String value = entry.asString(null);
            if (value != null) {
                args.add(value);
            }
        }

        Map<String, String> outputs = new LinkedHashMap<>();
        Json outputsJson = json.get("outputs");
        if (outputsJson.isObject()) {
            outputsJson.fields().forEach((key, value) -> {
                String expected = value.asString(null);
                if (expected != null) {
                    outputs.put(key, expected);
                }
            });
        }

        return new ForgeProcessor(sides, MavenCoordinate.parse(jar), classpath, args, outputs);
    }

    /**
     * Whether this step runs for {@code side}.
     *
     * <p>An absent {@code sides} array means all sides. Getting this backwards is
     * the difference between a patched client jar and a silent no-op that only
     * shows up as a crash on the first launch.
     */
    public boolean appliesToSide(String side) {
        return sides.isEmpty() || sides.contains(side);
    }

    /** Short label for progress and logs. */
    public String label() {
        return jar.groupArtifact();
    }
}
