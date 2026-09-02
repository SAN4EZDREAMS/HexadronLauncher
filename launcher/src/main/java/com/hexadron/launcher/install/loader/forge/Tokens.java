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

import com.hexadron.launcher.util.MavenCoordinate;

import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The tiny substitution language a Forge installer profile is written in.
 *
 * <p>This is not a general template engine and must not be implemented as one.
 * Forge's installer uses a hand-written scanner, and three of its properties are
 * load-bearing:
 *
 * <ul>
 *   <li>Substitution is <b>in-line</b>: {@code "{ROOT}/libraries/"} is one
 *       argument, not a token that has to stand alone. A regex over whole
 *       arguments silently produces a literal {@code {ROOT}} on disk.</li>
 *   <li>An unknown key is <b>fatal</b>. Leaving it unresolved would pass a
 *       literal brace to a processor, which then writes a file called
 *       {@code {MAPPINGS}} and reports success.</li>
 *   <li>Token names are <b>upper case</b>: {@code SIDE}, {@code MINECRAFT_JAR},
 *       {@code MINECRAFT_VERSION}, {@code ROOT}, {@code INSTALLER},
 *       {@code LIBRARY_DIR}, plus every key of the profile's {@code data} block.
 *       {@code client} and {@code server} are not tokens - they are the two
 *       sides of each {@code data} entry.</li>
 * </ul>
 *
 * <p>Three forms appear:
 * <ul>
 *   <li>{@code {KEY}} - a token, replaced by its value.</li>
 *   <li>{@code 'text'} - a literal; the quotes are removed. This is how a SHA-1
 *       is written into the profile without it looking like a token.</li>
 *   <li>{@code [group:artifact:version[:classifier][@ext]]} - a maven
 *       coordinate, replaced by that artifact's absolute path in the local
 *       library folder. Recognised only when it is the whole value, which is
 *       what the installer itself does.</li>
 * </ul>
 */
public final class Tokens {

    private Tokens() {
    }

    /**
     * Resolves an argument, an output file name or an expected hash.
     *
     * @param mavenPath maps a coordinate to its absolute local path
     */
    public static String resolve(Map<String, String> tokens, String value,
                                 Function<MavenCoordinate, String> mavenPath) {
        if (isBracketed(value)) {
            return mavenPath.apply(MavenCoordinate.parse(inner(value)));
        }
        return replaceTokens(tokens, value);
    }

    /**
     * Resolves one side of one {@code data} entry into the string that the
     * processors will see.
     *
     * @param mavenPath maps a coordinate to its absolute local path
     * @param extract   copies an entry out of the installer jar and returns the
     *                  path it was written to. Used for {@code /data/client.lzma},
     *                  which is a path inside the jar rather than a coordinate or
     *                  a literal
     */
    public static String resolveDataValue(String raw, Function<MavenCoordinate, String> mavenPath,
                                          UnaryOperator<String> extract) {
        String value = raw.trim();
        if (isBracketed(value)) {
            return mavenPath.apply(MavenCoordinate.parse(inner(value)));
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return inner(value);
        }
        return extract.apply(value);
    }

    /**
     * The scanner itself.
     *
     * @throws IllegalArgumentException on an unterminated form or an unknown key
     */
    public static String replaceTokens(Map<String, String> tokens, String value) {
        StringBuilder out = new StringBuilder();

        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);

            if (current == '\\') {
                if (index == value.length() - 1) {
                    throw new IllegalArgumentException("illegal pattern, trailing escape: " + value);
                }
                out.append(value.charAt(++index));
                continue;
            }

            if (current != '{' && current != '\'') {
                out.append(current);
                continue;
            }

            char closing = current == '{' ? '}' : '\'';
            StringBuilder key = new StringBuilder();
            int scan = index + 1;
            boolean closed = false;
            for (; scan < value.length(); scan++) {
                char inside = value.charAt(scan);
                if (inside == '\\') {
                    if (scan == value.length() - 1) {
                        throw new IllegalArgumentException("illegal pattern, trailing escape: " + value);
                    }
                    key.append(value.charAt(++scan));
                    continue;
                }
                if (inside == closing) {
                    closed = true;
                    break;
                }
                key.append(inside);
            }
            if (!closed) {
                throw new IllegalArgumentException("illegal pattern, unterminated "
                        + current + " in: " + value);
            }
            index = scan;

            if (current == '\'') {
                out.append(key);
                continue;
            }
            String replacement = tokens.get(key.toString());
            if (replacement == null) {
                throw new IllegalArgumentException(
                        "illegal pattern: " + value + " - missing key: " + key);
            }
            out.append(replacement);
        }
        return out.toString();
    }

    private static boolean isBracketed(String value) {
        String trimmed = value.trim();
        return trimmed.length() >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    private static String inner(String value) {
        String trimmed = value.trim();
        return trimmed.substring(1, trimmed.length() - 1);
    }
}
