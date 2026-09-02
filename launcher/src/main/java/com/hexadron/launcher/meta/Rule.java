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

package com.hexadron.launcher.meta;

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.util.Platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A conditional rule from a version JSON.
 *
 * <p>Rules gate both libraries (which natives apply to this host) and arguments
 * (demo mode, custom resolution, quick play). The evaluation order defined by
 * Mojang is: start disallowed, walk the rules in order, and every rule whose
 * condition matches overwrites the verdict. The last matching rule wins.
 *
 * <p>An empty or absent rule list means unconditionally allowed.
 */
public record Rule(boolean allow, OsCondition os, Map<String, Boolean> features) {

    /**
     * @param name    Mojang OS name: windows / osx / linux
     * @param version regular expression matched against {@code os.version}
     * @param arch    Mojang arch name: x86 / x64 / arm64 / arm32
     */
    public record OsCondition(String name, String version, String arch) {

        boolean matches() {
            if (name != null && !name.equals(Platform.osName())) {
                return false;
            }
            if (arch != null && !arch.equals(Platform.arch())) {
                return false;
            }
            if (version != null) {
                try {
                    if (!Pattern.compile(version).matcher(Platform.osVersion()).find()) {
                        return false;
                    }
                } catch (PatternSyntaxException e) {
                    // A malformed pattern in upstream metadata must not break the
                    // install; treat it as non-matching rather than fatal.
                    return false;
                }
            }
            return true;
        }
    }

    /** Feature flags the launcher can turn on for argument rules. */
    public static final class Features {
        public static final String DEMO_USER = "is_demo_user";
        public static final String CUSTOM_RESOLUTION = "has_custom_resolution";
        public static final String QUICK_PLAYS_SUPPORT = "has_quick_plays_support";
        public static final String QUICK_PLAY_SINGLEPLAYER = "is_quick_play_singleplayer";
        public static final String QUICK_PLAY_MULTIPLAYER = "is_quick_play_multiplayer";
        public static final String QUICK_PLAY_REALMS = "is_quick_play_realms";

        private Features() {
        }
    }

    public static List<Rule> parseList(Json rulesJson) {
        if (!rulesJson.isArray()) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>(rulesJson.size());
        for (Json entry : rulesJson.elements()) {
            rules.add(parse(entry));
        }
        return List.copyOf(rules);
    }

    public static Rule parse(Json ruleJson) {
        boolean allow = "allow".equals(ruleJson.str("action", "allow"));

        OsCondition os = null;
        Json osJson = ruleJson.get("os");
        if (osJson.isObject()) {
            os = new OsCondition(
                    osJson.get("name").asString(null),
                    osJson.get("version").asString(null),
                    osJson.get("arch").asString(null));
        }

        Map<String, Boolean> features = new LinkedHashMap<>();
        Json featuresJson = ruleJson.get("features");
        if (featuresJson.isObject()) {
            featuresJson.fields().forEach((key, value) -> features.put(key, value.asBool(false)));
        }

        return new Rule(allow, os, Map.copyOf(features));
    }

    /** True when this rule's condition holds for the current host and feature set. */
    public boolean matches(Map<String, Boolean> activeFeatures) {
        if (os != null && !os.matches()) {
            return false;
        }
        for (Map.Entry<String, Boolean> required : features.entrySet()) {
            boolean active = activeFeatures.getOrDefault(required.getKey(), Boolean.FALSE);
            if (active != required.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates a rule list.
     *
     * @param rules          the list; empty or {@code null} means allowed
     * @param activeFeatures features currently switched on by the launcher
     */
    public static boolean allows(List<Rule> rules, Map<String, Boolean> activeFeatures) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Rule rule : rules) {
            if (rule.matches(activeFeatures)) {
                allowed = rule.allow();
            }
        }
        return allowed;
    }

    /** Evaluates with no features enabled - the correct default for libraries. */
    public static boolean allows(List<Rule> rules) {
        return allows(rules, Map.of());
    }
}
