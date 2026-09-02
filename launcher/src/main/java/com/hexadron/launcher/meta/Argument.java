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

package com.hexadron.launcher.meta;

import com.hexadron.launcher.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One entry of a version JSON {@code arguments.game} or {@code arguments.jvm} array.
 *
 * <p>An entry is either a bare string, or an object with {@code rules} and a
 * {@code value} that is itself a string or an array of strings. Rule-gated
 * entries are how demo mode, custom resolution and quick-play are expressed.
 */
public record Argument(List<String> values, List<Rule> rules) {

    public Argument {
        values = List.copyOf(values);
        rules = List.copyOf(rules);
    }

    public static Argument of(String... values) {
        return new Argument(List.of(values), List.of());
    }

    public static Argument parse(Json json) {
        if (json.isString()) {
            return new Argument(List.of(json.asString()), List.of());
        }
        if (!json.isObject()) {
            // Numbers and booleans do not appear in practice, but never drop data silently.
            return new Argument(List.of(json.toString()), List.of());
        }

        List<String> values = new ArrayList<>();
        Json value = json.get("value");
        if (value.isString()) {
            values.add(value.asString());
        } else if (value.isArray()) {
            for (Json element : value.elements()) {
                String s = element.asString(null);
                if (s != null) {
                    values.add(s);
                }
            }
        }
        return new Argument(values, Rule.parseList(json.get("rules")));
    }

    public static List<Argument> parseList(Json arrayJson) {
        if (!arrayJson.isArray()) {
            return List.of();
        }
        List<Argument> arguments = new ArrayList<>(arrayJson.size());
        for (Json element : arrayJson.elements()) {
            arguments.add(parse(element));
        }
        return List.copyOf(arguments);
    }

    /** Splits a legacy {@code minecraftArguments} string into unconditional arguments. */
    public static List<Argument> parseLegacy(String minecraftArguments) {
        if (minecraftArguments == null || minecraftArguments.isBlank()) {
            return List.of();
        }
        List<Argument> arguments = new ArrayList<>();
        for (String token : minecraftArguments.trim().split("\\s+")) {
            arguments.add(new Argument(List.of(token), List.of()));
        }
        return List.copyOf(arguments);
    }

    public boolean appliesTo(Map<String, Boolean> activeFeatures) {
        return Rule.allows(rules, activeFeatures);
    }

    /** Appends this entry's values to {@code out} when its rules permit. */
    public void collectInto(List<String> out, Map<String, Boolean> activeFeatures) {
        if (appliesTo(activeFeatures)) {
            out.addAll(values);
        }
    }
}
