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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One thing the launcher can change to get past a crash.
 *
 * <p>The kinds are fixed in code. A rule file names a kind and fills in its
 * parameters from the crash output; it cannot add a kind. The launcher then
 * checks the filled-in fix against the instance (see
 * {@code LauncherService.prepareCrashFix}) and offers it only when it would
 * change something there.
 *
 * @param kind  what to do
 * @param value the parameter: a mod id, a file name, a Java version, or empty
 */
public record CrashFix(Kind kind, String value) {

    public enum Kind {
        /** Rename a jar in the mods folder to {@code .disabled}, found by mod id. */
        DISABLE_MOD("disableMod", List.of("mod")),
        /** The same, found by file name. */
        DISABLE_FILE("disableFile", List.of("file")),
        /** The same, found by the mixin config file the jar carries. */
        DISABLE_MIXIN_OWNER("disableMixinOwner", List.of("config")),
        /** Keep the newest of several jars with the same mod id, switch off the rest. */
        DISABLE_DUPLICATES("disableDuplicates", List.of("mod")),
        /** Start the instance with at least this Java version. */
        JAVA("java", List.of("major")),
        /** Forget the Java chosen by hand and let the launcher choose. */
        AUTOMATIC_JAVA("automaticJava", List.of()),
        /** Give the game more memory. */
        RAISE_MEMORY("raiseMemory", List.of()),
        /** Give the game less memory, back to the default for this computer. */
        LOWER_MEMORY("lowerMemory", List.of()),
        /** Check every game and loader file and download the broken ones again. */
        REINSTALL("reinstall", List.of());

        private final String key;
        private final List<String> params;

        Kind(String key, List<String> params) {
            this.key = key;
            this.params = params;
        }

        /** The name used in the rule file. */
        public String key() {
            return key;
        }

        /** The parameter names this kind takes; at most one. */
        public List<String> params() {
            return params;
        }

        /** The kind with this rule-file name, or null. */
        public static Kind parse(String key) {
            for (Kind kind : values()) {
                if (kind.key.equals(key)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public CrashFix {
        Objects.requireNonNull(kind, "kind");
        value = value == null ? "" : value;
    }

    /** Fills a rule's template with the values of one match; null when a value is missing. */
    static CrashFix from(CrashRules.FixTemplate template, Map<String, String> values) {
        if (template.kind().params().isEmpty()) {
            return new CrashFix(template.kind(), "");
        }
        String name = template.kind().params().get(0);
        String filled = CrashRules.fill(template.params().getOrDefault(name, ""), values);
        if (filled.isBlank() || !CrashRules.placeholders(filled).isEmpty()) {
            return null;
        }
        return new CrashFix(template.kind(), filled.trim());
    }
}
