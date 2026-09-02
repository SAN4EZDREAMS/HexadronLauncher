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
import java.util.List;
import java.util.Locale;

/**
 * Does this mod's declared Minecraft requirement admit this Minecraft version?
 *
 * <h2>Why the launcher asks this at all</h2>
 *
 * <p>Because it is the one question that turns a wall of loader output into a
 * sentence. A profile whose Minecraft version was changed after its mods were
 * installed keeps every jar it had, and the launcher used to have no opinion
 * about that: it started the game, the game refused, and the player was handed
 * forty lines of "requires any version between 26.2 (inclusive) and 26.3-
 * (exclusive) of 'Minecraft', but only the wrong version is present: 1.20.1".
 * Every one of those lines was knowable before the game started, from files
 * already on disk.
 *
 * <h2>Where the answer comes from</h2>
 *
 * <p>From the mod itself. Every loader requires a mod to declare which Minecraft
 * versions it works with, because the loader has to read that to decide whether
 * to load it. So this asks exactly what the loader will ask, off the same file,
 * and gets the same answer without starting a game.
 *
 * <h2>Two dialects</h2>
 *
 * <p>Fabric and Quilt write npm-style ranges - {@code ~26.2},
 * {@code >=1.20.1 <1.21}, {@code 1.20.x}. Forge and NeoForge write Maven
 * ranges - {@code [1.20.1,1.21)}. Both are read here, because a launcher that
 * warned about Fabric mods and stayed quiet about Forge ones would be worse than
 * one that never warned at all.
 *
 * <h2>The rule that governs everything here</h2>
 *
 * <p><b>Silence unless certain.</b> A false "this mod is for another version" on
 * a pack that works is worse than no warning: it teaches the player to click
 * past the warning, and then the real one goes past too. So anything this cannot
 * parse - an unusual range, a Minecraft snapshot like {@code 23w31a}, a version
 * shaped like nothing else - comes back {@link Verdict#UNKNOWN} and the
 * launcher says nothing.
 */
public final class VersionRanges {

    /** What can be said about a mod and a Minecraft version. */
    public enum Verdict {

        /** The mod's own declaration admits this version. */
        MATCHES,

        /** It does not, and the loader will refuse to load the mod. */
        DOES_NOT_MATCH,

        /** Nothing could be established, and nothing will be said. */
        UNKNOWN
    }

    private VersionRanges() {
    }

    /**
     * Checks a Fabric or Quilt requirement.
     *
     * @param requirements the alternatives a mod declared. A mod may give
     *                     several, and it is satisfied by any one of them
     * @param version      the Minecraft version the profile is set to
     */
    public static Verdict fabric(List<String> requirements, String version) {
        if (requirements == null || requirements.isEmpty()) {
            return Verdict.UNKNOWN;
        }
        Version target = Version.parse(version);
        if (target == null) {
            return Verdict.UNKNOWN;
        }
        boolean understood = true;
        for (String requirement : requirements) {
            Verdict verdict = fabricOne(requirement, target);
            if (verdict == Verdict.MATCHES) {
                return Verdict.MATCHES;
            }
            // One unreadable alternative is enough to make the whole answer
            // unsafe: it might have been the one that admits this version.
            understood &= verdict == Verdict.DOES_NOT_MATCH;
        }
        return understood ? Verdict.DOES_NOT_MATCH : Verdict.UNKNOWN;
    }

    /**
     * One requirement, which may itself be several conditions.
     *
     * <p>Space-separated conditions are all required; {@code ||} separates
     * alternatives. Both appear in mods published today.
     */
    private static Verdict fabricOne(String requirement, Version target) {
        if (requirement == null || requirement.isBlank()) {
            return Verdict.UNKNOWN;
        }
        String trimmed = requirement.trim();
        if (trimmed.contains("||")) {
            boolean understood = true;
            for (String alternative : trimmed.split("\\|\\|")) {
                Verdict verdict = fabricOne(alternative, target);
                if (verdict == Verdict.MATCHES) {
                    return Verdict.MATCHES;
                }
                understood &= verdict == Verdict.DOES_NOT_MATCH;
            }
            return understood ? Verdict.DOES_NOT_MATCH : Verdict.UNKNOWN;
        }

        boolean matches = true;
        for (String condition : trimmed.split("\\s+")) {
            Verdict verdict = condition(condition, target);
            if (verdict == Verdict.UNKNOWN) {
                return Verdict.UNKNOWN;
            }
            matches &= verdict == Verdict.MATCHES;
        }
        return matches ? Verdict.MATCHES : Verdict.DOES_NOT_MATCH;
    }

    /** One comparison: an operator and a version, or a wildcard, or an exact version. */
    private static Verdict condition(String condition, Version target) {
        String text = condition.trim();
        if (text.isEmpty() || text.equals("*") || text.equalsIgnoreCase("any")) {
            return Verdict.MATCHES;
        }

        String operator = "=";
        for (String candidate : new String[]{">=", "<=", "==", "!=", ">", "<", "=", "~", "^"}) {
            if (text.startsWith(candidate)) {
                operator = candidate;
                text = text.substring(candidate.length()).trim();
                break;
            }
        }
        if (text.isEmpty()) {
            return Verdict.UNKNOWN;
        }

        // 1.20.x and 1.20.* mean the whole of 1.20, whatever operator was in
        // front of them, and a bound cannot be built from a wildcard.
        int wildcard = wildcardDepth(text);
        if (wildcard >= 0) {
            if (!operator.equals("=") && !operator.equals("==") && !operator.equals("~")) {
                return Verdict.UNKNOWN;
            }
            Version low = Version.parse(text.replaceAll("[.*xX]+$", ""));
            if (low == null) {
                return Verdict.UNKNOWN;
            }
            return between(target, low, low.nextAt(wildcard - 1));
        }

        Version bound = Version.parse(text);
        if (bound == null) {
            return Verdict.UNKNOWN;
        }
        return switch (operator) {
            case ">=" -> verdict(target.compareTo(bound) >= 0);
            case ">" -> verdict(target.compareTo(bound) > 0);
            case "<=" -> verdict(target.compareTo(bound) <= 0);
            case "<" -> verdict(target.compareTo(bound) < 0);
            case "!=" -> verdict(target.compareTo(bound) != 0);
            case "=", "==" -> verdict(target.compareTo(bound) == 0);
            // ~1.2.3 is everything up to the next minor; ~1.2 up to the next
            // minor as well; ~1 up to the next major.
            case "~" -> between(target, bound, bound.nextAt(Math.min(bound.depth(), 2) - 1));
            // ^1.2.3 is everything up to the next major.
            case "^" -> between(target, bound, bound.nextAt(0));
            default -> Verdict.UNKNOWN;
        };
    }

    /**
     * Checks a Forge or NeoForge requirement, written as a Maven range.
     *
     * <p>{@code [1.20.1,1.21)} and its variants: a square bracket includes the
     * bound, a round one excludes it, and a missing side is unbounded. A bare
     * version means "this or newer", which is Maven's rule and catches out
     * anyone reading it as an exact version.
     */
    public static Verdict maven(String range, String version) {
        if (range == null || range.isBlank()) {
            return Verdict.UNKNOWN;
        }
        Version target = Version.parse(version);
        if (target == null) {
            return Verdict.UNKNOWN;
        }
        String text = range.trim();
        if (!text.startsWith("[") && !text.startsWith("(")) {
            Version bound = Version.parse(text);
            return bound == null ? Verdict.UNKNOWN : verdict(target.compareTo(bound) >= 0);
        }
        // A range may be a comma-separated set of ranges; the version has to be
        // in one of them.
        boolean understood = true;
        for (String part : splitRanges(text)) {
            Verdict verdict = mavenOne(part, target);
            if (verdict == Verdict.MATCHES) {
                return Verdict.MATCHES;
            }
            understood &= verdict == Verdict.DOES_NOT_MATCH;
        }
        return understood ? Verdict.DOES_NOT_MATCH : Verdict.UNKNOWN;
    }

    private static List<String> splitRanges(String text) {
        List<String> parts = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '(') {
                start = i;
            } else if ((c == ']' || c == ')') && start >= 0) {
                parts.add(text.substring(start, i + 1));
                start = -1;
            }
        }
        return parts;
    }

    private static Verdict mavenOne(String range, Version target) {
        if (range.length() < 3) {
            return Verdict.UNKNOWN;
        }
        boolean lowInclusive = range.charAt(0) == '[';
        boolean highInclusive = range.charAt(range.length() - 1) == ']';
        String body = range.substring(1, range.length() - 1);
        int comma = body.indexOf(',');
        if (comma < 0) {
            // [1.20.1] - a single version.
            Version exact = Version.parse(body.trim());
            return exact == null ? Verdict.UNKNOWN : verdict(target.compareTo(exact) == 0);
        }
        String lowText = body.substring(0, comma).trim();
        String highText = body.substring(comma + 1).trim();

        if (!lowText.isEmpty()) {
            Version low = Version.parse(lowText);
            if (low == null) {
                return Verdict.UNKNOWN;
            }
            int comparison = target.compareTo(low);
            if (comparison < 0 || (comparison == 0 && !lowInclusive)) {
                return Verdict.DOES_NOT_MATCH;
            }
        }
        if (!highText.isEmpty()) {
            Version high = Version.parse(highText);
            if (high == null) {
                return Verdict.UNKNOWN;
            }
            int comparison = target.compareTo(high);
            if (comparison > 0 || (comparison == 0 && !highInclusive)) {
                return Verdict.DOES_NOT_MATCH;
            }
        }
        return Verdict.MATCHES;
    }

    // ---------------------------------------------------------------- helpers

    private static Verdict verdict(boolean matches) {
        return matches ? Verdict.MATCHES : Verdict.DOES_NOT_MATCH;
    }

    /** {@code low <= target < high}. */
    private static Verdict between(Version target, Version low, Version high) {
        return verdict(target.compareTo(low) >= 0 && target.compareTo(high) < 0);
    }

    /**
     * Which component a trailing wildcard sits at, or -1 when there is none.
     *
     * <p>{@code 1.20.x} answers 2, so the range runs to the next value of
     * component 1.
     */
    private static int wildcardDepth(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".x") && !lower.endsWith(".*")) {
            return -1;
        }
        int depth = 0;
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) == '.') {
                depth++;
            }
        }
        return depth;
    }

    /**
     * A version as the loaders read one.
     *
     * <p>Numeric components, then an optional pre-release tail. The tail is the
     * part people get wrong: {@code 26.2-rc.1} is <em>older</em> than
     * {@code 26.2}, not newer, and a comparison that ignores it puts every
     * release candidate on the wrong side of every bound written against it.
     */
    static final class Version implements Comparable<Version> {

        private final int[] parts;
        private final String preRelease;

        private Version(int[] parts, String preRelease) {
            this.parts = parts;
            this.preRelease = preRelease;
        }

        /** @return null when this is not a version this class can order */
        static Version parse(String text) {
            if (text == null) {
                return null;
            }
            String value = text.trim();
            if (value.isEmpty()) {
                return null;
            }
            // Build metadata says nothing about order.
            int plus = value.indexOf('+');
            if (plus >= 0) {
                value = value.substring(0, plus);
            }
            String preRelease = null;
            int dash = value.indexOf('-');
            if (dash >= 0) {
                // A bare trailing dash is how the loaders write "any
                // pre-release of this version", which sorts below the release.
                preRelease = value.substring(dash + 1);
                value = value.substring(0, dash);
            }
            if (value.isEmpty()) {
                return null;
            }
            String[] fields = value.split("\\.");
            int[] parts = new int[fields.length];
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].isEmpty() || !fields[i].chars().allMatch(Character::isDigit)) {
                    return null;
                }
                try {
                    parts[i] = Integer.parseInt(fields[i]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return new Version(parts, preRelease);
        }

        int depth() {
            return parts.length;
        }

        /**
         * The next version at one component, everything after it dropped.
         *
         * <p>{@code 1.20.1.nextAt(1)} is {@code 1.21}, which is the upper bound
         * of {@code ~1.20.1}. A component past the end of this version counts as
         * zero, so {@code 26.2.nextAt(2)} is {@code 26.2.1}.
         */
        Version nextAt(int index) {
            int[] next = new int[index + 1];
            for (int i = 0; i <= index; i++) {
                next[i] = i < parts.length ? parts[i] : 0;
            }
            next[index]++;
            return new Version(next, null);
        }

        @Override
        public int compareTo(Version other) {
            int length = Math.max(parts.length, other.parts.length);
            for (int i = 0; i < length; i++) {
                int mine = i < parts.length ? parts[i] : 0;
                int theirs = i < other.parts.length ? other.parts[i] : 0;
                if (mine != theirs) {
                    return Integer.compare(mine, theirs);
                }
            }
            if (preRelease == null && other.preRelease == null) {
                return 0;
            }
            // A release outranks any pre-release of the same numbers.
            if (preRelease == null) {
                return 1;
            }
            if (other.preRelease == null) {
                return -1;
            }
            return comparePreRelease(preRelease, other.preRelease);
        }

        /** Dot-separated, numbers below words, as semantic versioning defines it. */
        private static int comparePreRelease(String a, String b) {
            String[] mine = a.isEmpty() ? new String[0] : a.split("\\.");
            String[] theirs = b.isEmpty() ? new String[0] : b.split("\\.");
            for (int i = 0; i < Math.max(mine.length, theirs.length); i++) {
                if (i >= mine.length) {
                    return -1;
                }
                if (i >= theirs.length) {
                    return 1;
                }
                boolean mineNumeric = mine[i].chars().allMatch(Character::isDigit);
                boolean theirsNumeric = theirs[i].chars().allMatch(Character::isDigit);
                if (mineNumeric && theirsNumeric) {
                    int comparison = Integer.compare(
                            Integer.parseInt(mine[i]), Integer.parseInt(theirs[i]));
                    if (comparison != 0) {
                        return comparison;
                    }
                } else if (mineNumeric != theirsNumeric) {
                    return mineNumeric ? -1 : 1;
                } else {
                    int comparison = mine[i].compareTo(theirs[i]);
                    if (comparison != 0) {
                        return comparison;
                    }
                }
            }
            return 0;
        }
    }
}
