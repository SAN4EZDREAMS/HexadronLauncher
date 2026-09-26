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

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.json.JsonException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The rule file that turns crash output into a cause and a fix.
 *
 * <h2>Why a data file and not code</h2>
 *
 * <p>Crash messages change with every loader release, and a new crash type is
 * found long before the next launcher release. Rules are data so that a newer
 * file can be published beside a release and picked up by launchers already
 * installed (see {@link CrashRuleSource}). The bundled copy is the floor: a
 * launcher that never goes online still knows every rule it shipped with.
 *
 * <h2>What a rule can do</h2>
 *
 * <p>A rule matches lines, names values from them and points at a text and at
 * fixes. It cannot run anything. Every fix is one of the fixed kinds in
 * {@link CrashFix.Kind}, and the launcher checks each one against the
 * instance before it is offered: a mod to switch off must be a file in that
 * instance's mods folder, a Java version must be a plausible number. A rule
 * file from the network is also refused unless it is signed with the update
 * key.
 *
 * <h2>Format</h2>
 *
 * <pre>{@code
 * {
 *   "schema": 1,
 *   "version": 3,
 *   "texts": {
 *     "missingDep": {
 *       "en": {"title": "...", "cause": "Mod {mod} needs {dep}.", "fix": "..."},
 *       "uk": {...}
 *     }
 *   },
 *   "rules": [
 *     {
 *       "id": "fabric-missing-dependency",
 *       "priority": 80,
 *       "text": "missingDep",
 *       "match": [
 *         {"contains": ["which is missing!"],
 *          "regex": "Mod '[^']*' \\((?<mod>[a-z0-9_.-]+)\\) \\S+ requires .+? of (?<dep>[a-z0-9_.-]+), which is missing!",
 *          "in": ["output", "log", "crash"], "lines": 1, "repeat": true}
 *       ],
 *       "exit": [1],
 *       "values": {"java": "classfile(cf)"},
 *       "fixes": [{"type": "disableMod", "mod": "{mod}"}]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Every condition in {@code match} has to hold. {@code contains} is a list
 * of plain substrings, any one of which lets a line through to the regular
 * expression - it keeps the expensive part off the thousands of lines that
 * cannot match. Values come from named groups; {@code values} derives more of
 * them ({@code classfile(g)} turns a class file version into a Java version,
 * {@code file(g)} keeps the file name of a path, {@code stem(g)} turns a
 * mixin config name into the mod name it usually carries, {@code int(g)} and
 * {@code lower(g)} do what they say).
 */
public final class CrashRules {

    /** The one format this code reads. A file for a later format is left alone. */
    public static final int SCHEMA = 1;

    /** The text the launcher uses when a stack trace names a mod (see {@link StackAttribution}). */
    public static final String TEXT_MOD_CODE = "modCode";
    /** The text the launcher uses when the game went silent before it was ended. */
    public static final String TEXT_FROZEN = "frozen";
    /**
     * Texts that code refers to rather than rules. A rule file without them is
     * refused as a download, because the launcher could not explain those cases.
     */
    /** The text the launcher uses when the threads of a silent game point at a mod. */
    public static final String TEXT_FROZEN_MOD = "frozenMod";
    public static final List<String> CODE_TEXTS = List.of(TEXT_MOD_CODE, TEXT_FROZEN, TEXT_FROZEN_MOD);

    /** Where the copy built into the launcher lives. */
    public static final String BUNDLED_RESOURCE = "/crash/rules.json";

    /** Larger than any real rule file by far; a bigger one is refused unread. */
    public static final int MAX_FILE_BYTES = 1024 * 1024;

    static final int MAX_RULES = 300;
    static final int MAX_REGEX_LENGTH = 600;
    static final int MAX_TEXT_LENGTH = 1200;
    static final int MAX_JOINED_LINES = 5;

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]{0,31})}");
    private static final Pattern DERIVED = Pattern.compile("(classfile|file|int|lower|stem)\\(([a-zA-Z][a-zA-Z0-9]{0,31})\\)");
    private static final Pattern GROUP_NAME = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");

    /** Where a condition looks. */
    public enum Source {
        /** What the game printed while it ran, as the launcher read it. */
        OUTPUT("output"),
        /** {@code logs/latest.log}. */
        LOG("log"),
        /** The crash report Minecraft wrote for this run. */
        CRASH("crash"),
        /** The JVM's own fatal error file, {@code hs_err_pid*.log}. */
        HS_ERR("hserr"),
        /** The game's threads, written by the launcher's agent when the game went silent. */
        THREADS("threads");

        private final String key;

        Source(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        static Source parse(String value) {
            for (Source source : values()) {
                if (source.key.equals(value)) {
                    return source;
                }
            }
            throw new JsonException("unknown source: " + value);
        }
    }

    /** One line (or run of lines) a rule needs to see. */
    public record Condition(List<String> contains, Pattern regex, Set<Source> in,
                            int lines, boolean repeat) {

        public Condition {
            contains = List.copyOf(contains);
            in = Collections.unmodifiableSet(new LinkedHashSet<>(in));
        }

        /** True when the first line could start a match. Cheap; no regex. */
        boolean admits(String line) {
            for (String needle : contains) {
                if (line.contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The named groups of a match in {@code text}, or null when there is none.
         * A condition without a regular expression matches on {@code contains}
         * alone and names nothing.
         */
        Map<String, String> match(String text) {
            if (regex == null) {
                return Map.of();
            }
            Matcher matcher = regex.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            Map<String, String> groups = new LinkedHashMap<>();
            for (String name : groupNames(regex.pattern())) {
                String value = matcher.group(name);
                if (value != null) {
                    groups.put(name, value);
                }
            }
            return groups;
        }
    }

    /** One fix a rule proposes, with its parameters still as templates. */
    public record FixTemplate(CrashFix.Kind kind, Map<String, String> params) {
        public FixTemplate {
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
    }

    /** A rule, read and checked. */
    public record Rule(String id, int priority, String textId, List<Condition> conditions,
                       Set<Integer> exitCodes, Map<String, String> derived,
                       List<FixTemplate> fixes) {

        public Rule {
            conditions = List.copyOf(conditions);
            exitCodes = Set.copyOf(exitCodes);
            derived = Collections.unmodifiableMap(new LinkedHashMap<>(derived));
            fixes = List.copyOf(fixes);
        }
    }

    /** The words for one cause, in one language. */
    public record Text(String title, String cause, String fix) {
    }

    private final int version;
    private final List<Rule> rules;
    private final Map<String, Map<String, Text>> texts;

    private CrashRules(int version, List<Rule> rules, Map<String, Map<String, Text>> texts) {
        this.version = version;
        this.rules = List.copyOf(rules);
        this.texts = texts;
    }

    /** An empty set, for when even the bundled file cannot be read. */
    public static CrashRules empty() {
        return new CrashRules(0, List.of(), Map.of());
    }

    public int version() {
        return version;
    }

    /** Highest priority first; the order the analyzer tries them in. */
    public List<Rule> rules() {
        return rules;
    }

    /** Every text id and the languages it has, for the self-check. */
    public Map<String, Map<String, Text>> texts() {
        return texts;
    }

    /**
     * The words for a text in a language, falling back to English. Null only
     * when the text does not exist, which {@link #parse} does not allow.
     */
    public Text text(String textId, String language) {
        Map<String, Text> byLanguage = texts.get(textId);
        if (byLanguage == null) {
            return null;
        }
        Text text = byLanguage.get(language);
        return text != null ? text : byLanguage.get("en");
    }

    /** The copy built into the launcher. Never throws: a broken resource is an empty set. */
    public static CrashRules bundled() {
        try (InputStream in = CrashRules.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                return empty();
            }
            byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                return empty();
            }
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    /**
     * Reads and checks a rule file.
     *
     * <p>Strict on purpose. A rule that names a fix this build does not know, a
     * text with no English, or a placeholder no rule can fill makes the whole
     * file unusable, not just the rule: a half-read file is a file nobody
     * tested.
     *
     * @throws JsonException when anything is wrong, with a message saying what
     */
    public static CrashRules parse(String json) {
        if (json == null || json.length() > MAX_FILE_BYTES) {
            throw new JsonException("rule file is missing or too large");
        }
        Json root;
        try {
            root = Json.parse(json);
        } catch (RuntimeException e) {
            throw new JsonException("rule file is not JSON: " + e.getMessage());
        }
        if (!root.isObject()) {
            throw new JsonException("rule file is not an object");
        }
        int schema = root.get("schema").asInt(-1);
        if (schema != SCHEMA) {
            throw new JsonException("unsupported rule schema " + schema);
        }
        int version = root.get("version").asInt(-1);
        if (version < 1) {
            throw new JsonException("rule file has no version");
        }

        Map<String, Map<String, Text>> texts = parseTexts(root.get("texts"));

        Json list = root.get("rules");
        if (!list.isArray() || list.size() > MAX_RULES) {
            throw new JsonException("rules must be an array of at most " + MAX_RULES);
        }
        List<Rule> rules = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Json item : list.elements()) {
            Rule rule = parseRule(item, texts);
            if (!ids.add(rule.id())) {
                throw new JsonException("duplicate rule id " + rule.id());
            }
            rules.add(rule);
        }
        // Stable: rules of equal priority keep the order the file gives them.
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        return new CrashRules(version, sorted, texts);
    }

    private static Map<String, Map<String, Text>> parseTexts(Json node) {
        if (!node.isObject()) {
            throw new JsonException("texts must be an object");
        }
        Map<String, Map<String, Text>> texts = new LinkedHashMap<>();
        for (Map.Entry<String, Json> entry : node.fields().entrySet()) {
            String id = entry.getKey();
            if (!ID.matcher(id.toLowerCase(Locale.ROOT)).matches()) {
                throw new JsonException("bad text id " + id);
            }
            if (!entry.getValue().isObject()) {
                throw new JsonException("text " + id + " must be an object");
            }
            Map<String, Text> byLanguage = new LinkedHashMap<>();
            for (Map.Entry<String, Json> language : entry.getValue().fields().entrySet()) {
                if (!language.getKey().matches("[a-z]{2}")) {
                    throw new JsonException("bad language " + language.getKey() + " in " + id);
                }
                Json words = language.getValue();
                Text text = new Text(
                        requireText(words, "title", id),
                        requireText(words, "cause", id),
                        requireText(words, "fix", id));
                byLanguage.put(language.getKey(), text);
            }
            if (!byLanguage.containsKey("en")) {
                throw new JsonException("text " + id + " has no English");
            }
            texts.put(id, Collections.unmodifiableMap(byLanguage));
        }
        return Collections.unmodifiableMap(texts);
    }

    private static String requireText(Json words, String key, String id) {
        String value = words.get(key).asString(null);
        if (value == null || value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new JsonException("text " + id + " needs a " + key);
        }
        return value;
    }

    private static Rule parseRule(Json item, Map<String, Map<String, Text>> texts) {
        if (!item.isObject()) {
            throw new JsonException("a rule must be an object");
        }
        String id = item.get("id").asString("");
        if (!ID.matcher(id).matches()) {
            throw new JsonException("bad rule id '" + id + "'");
        }
        int priority = item.get("priority").asInt(50);
        String textId = item.get("text").asString("");
        if (!texts.containsKey(textId)) {
            throw new JsonException("rule " + id + " names unknown text " + textId);
        }

        List<Condition> conditions = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        Json match = item.get("match");
        if (match.isArray()) {
            for (Json node : match.elements()) {
                Condition condition = parseCondition(node, id);
                conditions.add(condition);
                if (condition.regex() != null) {
                    names.addAll(groupNames(condition.regex().pattern()));
                }
            }
        } else if (match.exists()) {
            throw new JsonException("rule " + id + ": match must be an array");
        }

        Set<Integer> exitCodes = new LinkedHashSet<>();
        Json exit = item.get("exit");
        if (exit.isArray()) {
            for (Json code : exit.elements()) {
                if (!code.isNumber()) {
                    throw new JsonException("rule " + id + ": exit codes are numbers");
                }
                exitCodes.add(code.asInt(0));
            }
        }
        if (conditions.isEmpty() && exitCodes.isEmpty()) {
            throw new JsonException("rule " + id + " matches nothing");
        }

        Map<String, String> derived = new LinkedHashMap<>();
        Json values = item.get("values");
        if (values.isObject()) {
            for (Map.Entry<String, Json> entry : values.fields().entrySet()) {
                String expression = entry.getValue().asString("");
                Matcher matcher = DERIVED.matcher(expression);
                if (!entry.getKey().matches("[a-zA-Z][a-zA-Z0-9]{0,31}") || !matcher.matches()
                        || !names.contains(matcher.group(2))) {
                    throw new JsonException("rule " + id + ": bad value " + entry.getKey());
                }
                derived.put(entry.getKey(), expression);
                names.add(entry.getKey());
            }
        }

        List<FixTemplate> fixes = new ArrayList<>();
        Json fixList = item.get("fixes");
        if (fixList.isArray()) {
            for (Json node : fixList.elements()) {
                CrashFix.Kind kind = CrashFix.Kind.parse(node.get("type").asString(""));
                if (kind == null) {
                    throw new JsonException("rule " + id + ": unknown fix " + node.get("type"));
                }
                Map<String, String> params = new LinkedHashMap<>();
                for (Map.Entry<String, Json> entry : node.fields().entrySet()) {
                    if (entry.getKey().equals("type")) {
                        continue;
                    }
                    if (!kind.params().contains(entry.getKey())) {
                        throw new JsonException("rule " + id + ": fix " + kind.key()
                                + " takes no " + entry.getKey());
                    }
                    String template = entry.getValue().asString("");
                    requireKnown(template, names, "rule " + id + " fix " + kind.key());
                    params.put(entry.getKey(), template);
                }
                if (!kind.params().isEmpty() && params.isEmpty()) {
                    throw new JsonException("rule " + id + ": fix " + kind.key() + " needs "
                            + kind.params());
                }
                fixes.add(new FixTemplate(kind, params));
            }
        }

        // Every placeholder in every language has to be something this rule
        // can fill. Checked per rule, because two rules can share a text.
        for (Text text : texts.get(textId).values()) {
            for (String part : new String[]{text.title(), text.cause(), text.fix()}) {
                requireKnown(part, names, "rule " + id + " text " + textId);
            }
        }

        return new Rule(id, priority, textId, conditions, exitCodes, derived, fixes);
    }

    private static void requireKnown(String template, Set<String> names, String where) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (!names.contains(matcher.group(1))) {
                throw new JsonException(where + " uses {" + matcher.group(1)
                        + "}, which nothing in the rule provides");
            }
        }
    }

    private static Condition parseCondition(Json node, String id) {
        if (!node.isObject()) {
            throw new JsonException("rule " + id + ": a condition must be an object");
        }
        List<String> contains = new ArrayList<>();
        Json list = node.get("contains");
        if (list.isString()) {
            contains.add(list.asString());
        } else if (list.isArray()) {
            for (Json value : list.elements()) {
                String needle = value.asString("");
                if (needle.isEmpty()) {
                    throw new JsonException("rule " + id + ": empty contains");
                }
                contains.add(needle);
            }
        }
        if (contains.isEmpty()) {
            // Required. It is what keeps a rule cheap, and a rule with a regular
            // expression alone would run it on every line of a large log.
            throw new JsonException("rule " + id + ": a condition needs contains");
        }

        Pattern regex = null;
        String expression = node.get("regex").asString(null);
        if (expression != null) {
            if (expression.length() > MAX_REGEX_LENGTH) {
                throw new JsonException("rule " + id + ": regex too long");
            }
            try {
                regex = Pattern.compile(expression);
            } catch (PatternSyntaxException e) {
                throw new JsonException("rule " + id + ": bad regex: " + e.getDescription());
            }
        }

        Set<Source> in = new LinkedHashSet<>();
        Json sources = node.get("in");
        if (sources.isArray()) {
            for (Json source : sources.elements()) {
                in.add(Source.parse(source.asString("")));
            }
        }
        if (in.isEmpty()) {
            in.add(Source.OUTPUT);
            in.add(Source.LOG);
            in.add(Source.CRASH);
        }

        int lines = node.get("lines").asInt(1);
        if (lines < 1 || lines > MAX_JOINED_LINES) {
            throw new JsonException("rule " + id + ": lines must be 1 to " + MAX_JOINED_LINES);
        }
        return new Condition(contains, regex, in, lines, node.get("repeat").asBool(false));
    }

    /** The named groups of a pattern, in order. */
    static List<String> groupNames(String pattern) {
        List<String> names = new ArrayList<>();
        Matcher matcher = GROUP_NAME.matcher(pattern);
        while (matcher.find()) {
            if (!names.contains(matcher.group(1))) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /** Derives a value; null when its input is absent or does not fit. */
    static String derive(String expression, Map<String, String> values) {
        Matcher matcher = DERIVED.matcher(expression);
        if (!matcher.matches()) {
            return null;
        }
        String input = values.get(matcher.group(2));
        if (input == null) {
            return null;
        }
        return switch (matcher.group(1)) {
            case "classfile" -> {
                // Class file 52 is Java 8; each release since adds one.
                Integer number = parseInt(input);
                yield number == null || number < 45 ? null : String.valueOf(number - 44);
            }
            case "int" -> {
                Integer number = parseInt(input);
                yield number == null ? null : String.valueOf(number);
            }
            case "file" -> {
                String trimmed = input.trim();
                int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
                yield slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
            }
            case "lower" -> input.toLowerCase(Locale.ROOT);
            case "stem" -> {
                // "sodium.mixins.json" -> "sodium": the usual way a mixin
                // config is named after the mod that carries it.
                String name = input.trim();
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                name = slash >= 0 ? name.substring(slash + 1) : name;
                name = name.replaceFirst("(?i)\\.json$", "")
                        .replaceFirst("(?i)[._-]?mixins?$", "")
                        .replaceFirst("(?i)^mixins?[._-]", "");
                yield name.isBlank() ? null : name;
            }
            default -> null;
        };
    }

    private static Integer parseInt(String value) {
        try {
            String digits = value.trim();
            int dot = digits.indexOf('.');
            if (dot > 0) {
                digits = digits.substring(0, dot);
            }
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Fills {name} placeholders. A name with no value is left as it is. */
    public static String fill(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The placeholders a template uses. */
    public static Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
