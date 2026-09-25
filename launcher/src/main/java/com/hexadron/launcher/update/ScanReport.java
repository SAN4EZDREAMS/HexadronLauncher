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

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The VirusTotal result that the release workflow writes into a release's notes.
 *
 * <h2>Where it comes from</h2>
 *
 * <p>{@code .github/scripts/virustotal_scan.py} appends a block to the notes,
 * between two HTML comments. On the release page GitHub hides the comments and
 * shows badges and a folded table. The first comment also carries the result
 * as attributes:
 *
 * <pre>{@code
 * <!-- virustotal:start verdict=clean found=0 checked=16 total=16 -->
 * ... badges, table ...
 * <!-- virustotal:end -->
 * }</pre>
 *
 * <p>Those attributes are a contract with the script. The update window shows
 * the notes as plain text, where badge links and a table are noise, so it cuts
 * the block out ({@link #without}) and draws one coloured label from the
 * attributes instead ({@link #in}).
 *
 * <h2>The older format</h2>
 *
 * <p>Releases scanned before the badges carry a plain-text section that starts
 * with the line {@link #LEGACY_HEAD}, preceded by a {@code ---} line, and runs to
 * the end of the notes. It is cut as well, so every release reads the same.
 */
public record ScanReport(Verdict verdict, int found, int checked, int total) {

    /** What the scan concluded, worst first in the order the script ranks them. */
    public enum Verdict {
        CLEAN, WARNING, DANGER, PENDING, UNCHECKED, ERROR
    }

    static final String MARK_START = "<!-- virustotal:start";
    static final String MARK_END = "<!-- virustotal:end -->";
    // The Ukrainian "VirusTotal check" - the heading the script wrote before the badges.
    static final String LEGACY_HEAD = "\u041f\u0435\u0440\u0435\u0432\u0456\u0440\u043a\u0430 VirusTotal";

    private static final Pattern ATTRIBUTES = Pattern.compile(
            Pattern.quote(MARK_START) + "([^>]*?)-->");
    private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)=([\\w-]+)");

    // The block, with the blank lines before it. A block with no end - a run
    // that was cut off - is taken to the end of the notes.
    private static final Pattern BLOCK = Pattern.compile(
            "(?:\\r?\\n)*" + Pattern.quote(MARK_START) + "[\\s\\S]*?(?:"
                    + Pattern.quote(MARK_END) + "|\\z)[ \\t]*");

    // Only from the start of a line: the list of commits above it may use the
    // same words in the middle of one, and cutting the notes there is wrong.
    private static final Pattern LEGACY = Pattern.compile(
            "(?:\\r?\\n)*(?:^---[ \\t]*\\r?\\n(?:[ \\t]*\\r?\\n)*)?^"
                    + Pattern.quote(LEGACY_HEAD) + "[\\s\\S]*\\z",
            Pattern.MULTILINE);

    /** The notes without the scan block, in either format. Never null. */
    public static String without(String notes) {
        if (notes == null) {
            return "";
        }
        String kept = BLOCK.matcher(notes).replaceAll("");
        return LEGACY.matcher(kept).replaceAll("").strip();
    }

    /**
     * The result the notes carry, or empty when they carry none, or one this
     * launcher does not understand. A missing number reads as zero; an unknown
     * verdict is not guessed at.
     */
    public static Optional<ScanReport> in(String notes) {
        if (notes == null) {
            return Optional.empty();
        }
        Matcher head = ATTRIBUTES.matcher(notes);
        if (!head.find()) {
            return Optional.empty();
        }
        Verdict verdict = null;
        int found = 0;
        int checked = 0;
        int total = 0;
        Matcher pair = ATTRIBUTE.matcher(head.group(1));
        while (pair.find()) {
            String value = pair.group(2);
            switch (pair.group(1)) {
                case "verdict" -> verdict = verdictOf(value);
                case "found" -> found = number(value);
                case "checked" -> checked = number(value);
                case "total" -> total = number(value);
                default -> {
                    // A newer script may add attributes. They are not an error.
                }
            }
        }
        return verdict == null
                ? Optional.empty()
                : Optional.of(new ScanReport(verdict, found, checked, total));
    }

    private static Verdict verdictOf(String code) {
        try {
            return Verdict.valueOf(code.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static int number(String text) {
        try {
            return Math.max(0, Integer.parseInt(text));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
