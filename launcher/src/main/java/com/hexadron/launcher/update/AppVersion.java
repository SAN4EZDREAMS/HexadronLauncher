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

package com.hexadron.launcher.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The launcher's own version, and which of two of them is newer.
 *
 * <h2>Why this is not a string comparison</h2>
 *
 * <p>Because "0.9.10" is newer than "0.9.9" and shorter in the alphabet, and
 * because a nightly build has to be comparable with the release it was built
 * from. The numbers are compared as numbers, and what follows a hyphen is
 * compared the way semantic versioning compares a pre-release: a build with one
 * is <em>older</em> than the same numbers without one, which is what makes
 * {@code 0.9.5-nightly.20260902} older than {@code 0.9.5} and newer than
 * {@code 0.9.5-nightly.20260901}.
 *
 * <h2>Silence unless certain</h2>
 *
 * <p>A version this cannot read yields {@link Optional#empty()}, and nothing is
 * offered. The alternative - guessing - ends with a launcher that offers to
 * "update" somebody to the build they are already running, or worse, to an older
 * one, and there is no version of that failure a user can diagnose.
 */
public final class AppVersion implements Comparable<AppVersion> {

    private final List<Long> numbers;

    /** Dot-separated identifiers after the hyphen, empty for a finished release. */
    private final List<String> pre;

    private final String text;

    private AppVersion(List<Long> numbers, List<String> pre, String text) {
        this.numbers = List.copyOf(numbers);
        this.pre = List.copyOf(pre);
        this.text = text;
    }

    /**
     * Reads a version.
     *
     * <p>Accepts what this project's tags and manifests actually carry: an
     * optional leading {@code v}, two to four numbers, an optional
     * {@code -pre.release} part, and build metadata after {@code +} which is
     * ignored because semantic versioning says it takes no part in precedence.
     *
     * @return empty when there is no number in it at all, which is not a version
     *         this can reason about
     */
    public static Optional<AppVersion> of(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String text = value.trim();
        String working = text;
        if (working.startsWith("v") || working.startsWith("V")) {
            working = working.substring(1);
        }
        int plus = working.indexOf('+');
        if (plus >= 0) {
            working = working.substring(0, plus);
        }
        int hyphen = working.indexOf('-');
        String core = hyphen < 0 ? working : working.substring(0, hyphen);
        String tail = hyphen < 0 ? "" : working.substring(hyphen + 1);

        List<Long> numbers = new ArrayList<>();
        for (String part : core.split("\\.")) {
            String digits = part.trim();
            if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            try {
                numbers.add(Long.parseLong(digits));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        if (numbers.isEmpty()) {
            return Optional.empty();
        }

        List<String> pre = new ArrayList<>();
        if (!tail.isBlank()) {
            for (String part : tail.split("\\.")) {
                if (!part.isBlank()) {
                    pre.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Optional.of(new AppVersion(numbers, pre, text));
    }

    /** The version as it was written, for showing to somebody. */
    public String text() {
        return text;
    }

    /** True when this is a pre-release: a nightly, a beta, a release candidate. */
    public boolean isPrerelease() {
        return !pre.isEmpty();
    }

    public boolean isNewerThan(AppVersion other) {
        return other != null && compareTo(other) > 0;
    }

    @Override
    public int compareTo(AppVersion other) {
        int length = Math.max(numbers.size(), other.numbers.size());
        for (int i = 0; i < length; i++) {
            // A missing number is a zero: 1.2 and 1.2.0 are the same version.
            long mine = i < numbers.size() ? numbers.get(i) : 0;
            long theirs = i < other.numbers.size() ? other.numbers.get(i) : 0;
            if (mine != theirs) {
                return Long.compare(mine, theirs);
            }
        }
        if (pre.isEmpty() || other.pre.isEmpty()) {
            // Neither, both, or one of them: a finished release wins over a
            // pre-release of the same numbers, and two releases are equal.
            return Integer.compare(other.pre.isEmpty() ? 0 : 1, pre.isEmpty() ? 0 : 1);
        }
        int identifiers = Math.max(pre.size(), other.pre.size());
        for (int i = 0; i < identifiers; i++) {
            if (i >= pre.size()) {
                return -1;
            }
            if (i >= other.pre.size()) {
                return 1;
            }
            int order = compareIdentifier(pre.get(i), other.pre.get(i));
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }

    /**
     * One pre-release identifier against another.
     *
     * <p>Numbers as numbers, so {@code nightly.9} is older than
     * {@code nightly.10}; a number is older than a word, which is the rule
     * semantic versioning gives and the only part of this that is arbitrary.
     */
    private static int compareIdentifier(String mine, String theirs) {
        boolean mineNumeric = isNumeric(mine);
        boolean theirsNumeric = isNumeric(theirs);
        if (mineNumeric && theirsNumeric) {
            try {
                return Long.compare(Long.parseLong(mine), Long.parseLong(theirs));
            } catch (NumberFormatException e) {
                // Longer than a long: compare as text, which for two strings of
                // digits of the same length is the same answer.
                return mine.length() != theirs.length()
                        ? Integer.compare(mine.length(), theirs.length())
                        : mine.compareTo(theirs);
            }
        }
        if (mineNumeric != theirsNumeric) {
            return mineNumeric ? -1 : 1;
        }
        return mine.compareTo(theirs);
    }

    private static boolean isNumeric(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AppVersion version && compareTo(version) == 0;
    }

    @Override
    public int hashCode() {
        return numbers.hashCode() * 31 + pre.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
