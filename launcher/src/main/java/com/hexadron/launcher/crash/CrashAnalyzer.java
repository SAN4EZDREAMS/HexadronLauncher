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

import com.hexadron.launcher.util.Redactor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Matches crash evidence against the rules.
 *
 * <p>Pure: no files, no network, no interface. The same evidence and rules
 * always give the same answer, which is what lets the self-check run it on
 * real loader output.
 */
public final class CrashAnalyzer {

    /** How many causes one crash is explained with. More is a list nobody reads. */
    public static final int MAX_DIAGNOSES = 4;

    /** How many separate matches one repeating rule may report (five missing dependencies, say). */
    static final int MAX_REPEATS = 5;

    /** The values that hold a mod id, which the player knows by a name instead. */
    static final List<String> NAMED = List.of("mod", "other", "dep");

    /** Longest value put into a sentence. Mod ids and file names are far shorter. */
    static final int MAX_VALUE = 100;

    /**
     * One explained cause.
     *
     * @param ruleId   the rule that matched
     * @param textId   the text it uses; two rules with the same text are one cause
     * @param title    a few words for the heading
     * @param cause    what happened, in plain language
     * @param advice   what the player can do, in plain language
     * @param fixes    what the launcher can do; checked against the instance later
     * @param values   the values the rule read from the output
     * @param source   where the first condition matched
     * @param line     the line it matched, for "what we found"
     */
    public record Diagnosis(String ruleId, String textId, int priority, String title,
                            String cause, String advice, List<CrashFix> fixes,
                            Map<String, String> values, CrashRules.Source source, String line) {

        public Diagnosis {
            fixes = List.copyOf(fixes);
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    private record Match(Map<String, String> groups, CrashRules.Source source, String line) {
    }

    private CrashAnalyzer() {
    }

    /**
     * Explains a crash.
     *
     * @param language two-letter code of the language to explain it in
     * @return the causes found, most specific first; empty when no rule matched
     */
    public static List<Diagnosis> analyze(CrashEvidence evidence, CrashRules rules, String language) {
        return analyze(evidence, rules, language, UnaryOperator.identity());
    }

    /**
     * Explains a crash, naming mods the way the player knows them.
     *
     * @param names turns a mod id into the name the player sees; the sentences
     *              use it, the fixes keep the id
     */
    public static List<Diagnosis> analyze(CrashEvidence evidence, CrashRules rules, String language,
                                          UnaryOperator<String> names) {
        List<Diagnosis> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CrashRules.Rule rule : rules.rules()) {
            if (found.size() >= MAX_DIAGNOSES) {
                break;
            }
            if (!rule.exitCodes().isEmpty() && !rule.exitCodes().contains(evidence.exitCode())) {
                continue;
            }
            for (Diagnosis diagnosis : apply(rule, evidence, rules, language, names)) {
                // The same cause read twice - once from the output, once from
                // the log that holds the same lines - is one cause.
                String key = diagnosis.textId() + "\n" + diagnosis.cause();
                if (seen.add(key) && found.size() < MAX_DIAGNOSES) {
                    found.add(diagnosis);
                }
            }
        }
        return List.copyOf(found);
    }

    private static List<Diagnosis> apply(CrashRules.Rule rule, CrashEvidence evidence,
                                         CrashRules rules, String language,
                                         UnaryOperator<String> names) {
        List<Map<String, String>> matches = new ArrayList<>();
        CrashRules.Source firstSource = null;
        String firstLine = "";

        if (rule.conditions().isEmpty()) {
            matches.add(Map.of());
        } else {
            CrashRules.Condition first = rule.conditions().get(0);
            List<Match> heads = find(first, evidence, first.repeat() ? MAX_REPEATS : 1);
            if (heads.isEmpty()) {
                return List.of();
            }
            firstSource = heads.get(0).source();
            firstLine = heads.get(0).line();
            Map<String, String> rest = new LinkedHashMap<>();
            for (int i = 1; i < rule.conditions().size(); i++) {
                List<Match> more = find(rule.conditions().get(i), evidence, 1);
                if (more.isEmpty()) {
                    return List.of();
                }
                rest.putAll(more.get(0).groups());
            }
            for (Match head : heads) {
                Map<String, String> values = new LinkedHashMap<>(rest);
                values.putAll(head.groups());
                matches.add(values);
            }
        }

        List<Diagnosis> result = new ArrayList<>();
        for (Map<String, String> raw : matches) {
            Map<String, String> values = new LinkedHashMap<>();
            raw.forEach((name, value) -> {
                String clean = clean(value);
                if (!clean.isEmpty()) {
                    values.put(name, clean);
                }
            });
            rule.derived().forEach((name, expression) -> {
                String value = CrashRules.derive(expression, values);
                if (value != null) {
                    values.put(name, clean(value));
                }
            });

            Map<String, String> shown = new LinkedHashMap<>(values);
            for (String key : NAMED) {
                String id = shown.get(key);
                if (id != null) {
                    String name = names.apply(id);
                    shown.put(key, name == null || name.isBlank() ? id : clean(name));
                }
            }
            CrashRules.Text text = rules.text(rule.textId(), language);
            String title = CrashRules.fill(text.title(), shown);
            String cause = CrashRules.fill(text.cause(), shown);
            String advice = CrashRules.fill(text.fix(), shown);
            // A sentence with a hole in it explains nothing. A regex group that
            // did not take part in the match leaves one; the match is dropped.
            if (!CrashRules.placeholders(title + cause + advice).isEmpty()) {
                continue;
            }
            List<CrashFix> fixes = new ArrayList<>();
            for (CrashRules.FixTemplate template : rule.fixes()) {
                CrashFix fix = CrashFix.from(template, values);
                if (fix != null && !fixes.contains(fix)) {
                    fixes.add(fix);
                }
            }
            result.add(new Diagnosis(rule.id(), rule.textId(), rule.priority(), title, cause,
                    advice, fixes, values, firstSource, clean(firstLine, 300)));
        }
        return result;
    }

    /**
     * A diagnosis the launcher found by itself rather than through a rule: a
     * stack trace that names a mod, a game that went silent. Worded from a
     * text in the rule file, so it is in the same languages as every other.
     *
     * @return empty when the rule file has no such text or a value is missing
     */
    public static java.util.Optional<Diagnosis> describe(CrashRules rules, String language, String ruleId,
                                                         int priority, String textId,
                                                         Map<String, String> values, List<CrashFix> fixes,
                                                         CrashRules.Source source, String line) {
        CrashRules.Text text = rules.text(textId, language);
        if (text == null) {
            return java.util.Optional.empty();
        }
        Map<String, String> clean = new LinkedHashMap<>();
        values.forEach((key, value) -> clean.put(key, clean(value)));
        String title = CrashRules.fill(text.title(), clean);
        String cause = CrashRules.fill(text.cause(), clean);
        String advice = CrashRules.fill(text.fix(), clean);
        if (!CrashRules.placeholders(title + cause + advice).isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Diagnosis(ruleId, textId, priority, title, cause, advice,
                fixes, clean, source, clean(line, 300)));
    }

    private static List<Match> find(CrashRules.Condition condition, CrashEvidence evidence, int limit) {
        List<Match> found = new ArrayList<>();
        Set<Map<String, String>> distinct = new LinkedHashSet<>();
        for (CrashRules.Source source : condition.in()) {
            List<String> lines = evidence.lines(source);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!condition.admits(line)) {
                    continue;
                }
                String text = line;
                if (condition.lines() > 1) {
                    StringBuilder joined = new StringBuilder(line);
                    for (int j = 1; j < condition.lines() && i + j < lines.size(); j++) {
                        joined.append('\n').append(lines.get(i + j));
                    }
                    text = joined.toString();
                }
                Map<String, String> groups = condition.match(text);
                if (groups != null && distinct.add(groups)) {
                    found.add(new Match(groups, source, line));
                    if (found.size() >= limit) {
                        return found;
                    }
                }
            }
        }
        return found;
    }

    static String clean(String value) {
        return clean(value, MAX_VALUE);
    }

    /**
     * Makes a value from game output fit to show: no colour codes, no control
     * characters, nothing a token could hide in, and short.
     */
    static String clean(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("§.", "")
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .trim();
        text = Redactor.scrub(text);
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }
}
