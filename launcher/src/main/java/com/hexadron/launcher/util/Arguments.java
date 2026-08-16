package com.hexadron.launcher.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a typed argument line into the list a {@code ProcessBuilder} needs.
 *
 * <p>Lives outside the UI so it can be checked without a display: the launcher
 * passes whatever comes out of here straight to the JVM, and a split that loses
 * a quoted path produces a launch failure that is hard to read back to the
 * field that caused it.
 */
public final class Arguments {

    /** Splits on whitespace, keeping double-quoted groups together. */
    public static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : text.trim().toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return List.copyOf(parts);
    }

    /** The inverse, for showing a stored list back in a text field. */
    public static String join(List<String> arguments) {
        StringBuilder text = new StringBuilder();
        for (String argument : arguments) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            if (argument.indexOf(' ') >= 0) {
                text.append('"').append(argument).append('"');
            } else {
                text.append(argument);
            }
        }
        return text.toString();
    }

    private Arguments() {
    }
}
