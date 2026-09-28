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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What one crash was, reduced to a key that the same crash gives again.
 *
 * <p>The problem-mod search needs it. A search started from a crash is looking
 * for that crash, and every launch of it that stops with another one - a
 * library the step switched off, a mod that cannot start at all - is not an
 * answer to the question. Counted as "the problem", those launches made the
 * search name a mod that had nothing to do with the crash.
 *
 * <p>The key is the cause the analysis found (its text and the mod, file or
 * library it names), or, when no cause was found, the root exception of the
 * crash's stack and the first class of a mod in it. The same crash in a
 * smaller mod set gives the same key: the names come from the jars, not from
 * the set.
 *
 * @param key   empty when nothing identifies the crash
 * @param label a few words for the log and the search window
 */
public record CrashSignature(String key, String label) {

    /** Values that name what a cause is about, most telling first. */
    static final List<String> SUBJECTS = List.of("mod", "file", "library", "dep", "class");

    public CrashSignature {
        key = key == null ? "" : key;
        label = label == null ? "" : label;
    }

    /** Nothing identifies it. */
    public static CrashSignature unknown() {
        return new CrashSignature("", "");
    }

    public boolean known() {
        return !key.isEmpty();
    }

    /** The signature of a crash: its first cause, or its stack when no cause was found. */
    public static CrashSignature of(List<CrashAnalyzer.Diagnosis> diagnoses, CrashEvidence evidence) {
        if (!diagnoses.isEmpty()) {
            CrashAnalyzer.Diagnosis first = diagnoses.get(0);
            return new CrashSignature(keyOf(first), first.title());
        }
        return stackKey(evidence).orElse(unknown());
    }

    /** Every key a crash can be recognised by: each cause, and its stack. */
    public static Set<String> keys(List<CrashAnalyzer.Diagnosis> diagnoses, CrashEvidence evidence) {
        Set<String> keys = new LinkedHashSet<>();
        for (CrashAnalyzer.Diagnosis diagnosis : diagnoses) {
            keys.add(keyOf(diagnosis));
        }
        stackKey(evidence).ifPresent(signature -> keys.add(signature.key()));
        return keys;
    }

    /** True when this crash is the one the signature was taken from. */
    public boolean matches(List<CrashAnalyzer.Diagnosis> diagnoses, CrashEvidence evidence) {
        return known() && keys(diagnoses, evidence).contains(key);
    }

    static String keyOf(CrashAnalyzer.Diagnosis diagnosis) {
        String subject = "";
        for (String name : SUBJECTS) {
            String value = diagnosis.values().get(name);
            if (value != null && !value.isBlank()) {
                subject = value.trim().toLowerCase(java.util.Locale.ROOT);
                break;
            }
        }
        return "cause|" + diagnosis.textId() + "|" + subject;
    }

    static Optional<CrashSignature> stackKey(CrashEvidence evidence) {
        return StackAttribution.stackOf(evidence)
                .filter(stack -> !stack.classes().isEmpty())
                .map(stack -> new CrashSignature("stack|" + stack.error() + "|" + stack.classes().get(0),
                        stack.error() + " (" + stack.classes().get(0) + ")"));
    }
}
