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

package com.hexadron.launcher.json;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strict, dependency-free JSON tree.
 *
 * <p>Why not Gson: every JSON this launcher touches (piston-meta, Fabric meta,
 * Modrinth, CurseForge, our own profile store) is read as a tree and navigated
 * by key, never bound to POJOs. A tree API removes a dependency from the
 * download path and lets the whole metadata layer be unit tested with no
 * network and no external artifacts.
 *
 * <p>Navigation never throws on a missing key: {@link #get(String)} returns the
 * {@link #MISSING} sentinel, so {@code json.get("a").get("b").asString("")} is
 * safe on arbitrary input. Only {@code asX()} without a default is strict.
 */
public final class Json {

    /** Sentinel returned for absent keys / out-of-range indices. Distinct from JSON null. */
    public static final Json MISSING = new Json(Kind.MISSING, null);
    public static final Json NULL = new Json(Kind.NULL, null);
    public static final Json TRUE = new Json(Kind.BOOL, Boolean.TRUE);
    public static final Json FALSE = new Json(Kind.BOOL, Boolean.FALSE);

    private enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOL, NULL, MISSING }

    private final Kind kind;
    private final Object value;

    private Json(Kind kind, Object value) {
        this.kind = kind;
        this.value = value;
    }

    // ---------------------------------------------------------------- factories

    public static Json object() {
        return new Json(Kind.OBJECT, new LinkedHashMap<String, Json>());
    }

    public static Json array() {
        return new Json(Kind.ARRAY, new ArrayList<Json>());
    }

    public static Json of(String s) {
        return s == null ? NULL : new Json(Kind.STRING, s);
    }

    public static Json of(long n) {
        return new Json(Kind.NUMBER, n);
    }

    public static Json of(double n) {
        return new Json(Kind.NUMBER, n);
    }

    public static Json of(boolean b) {
        return b ? TRUE : FALSE;
    }

    // ---------------------------------------------------------------- parsing

    public static Json parse(String text) {
        return new Parser(text).parseDocument();
    }

    public static Json parse(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return parse(sb.toString());
    }

    public static Json read(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public void write(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, toPrettyString(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- kind tests

    public boolean isObject()  { return kind == Kind.OBJECT; }
    public boolean isArray()   { return kind == Kind.ARRAY; }
    public boolean isString()  { return kind == Kind.STRING; }
    public boolean isNumber()  { return kind == Kind.NUMBER; }
    public boolean isBool()    { return kind == Kind.BOOL; }
    public boolean isNull()    { return kind == Kind.NULL; }
    public boolean isMissing() { return kind == Kind.MISSING; }

    /** True when the value is absent or JSON null - the usual "nothing here" test. */
    public boolean isAbsent()  { return kind == Kind.MISSING || kind == Kind.NULL; }

    /** True when this is a present, non-null value. */
    public boolean exists()    { return kind != Kind.MISSING && kind != Kind.NULL; }

    // ---------------------------------------------------------------- navigation

    @SuppressWarnings("unchecked")
    private Map<String, Json> map() {
        return (Map<String, Json>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Json> list() {
        return (List<Json>) value;
    }

    /** Object member lookup. Returns {@link #MISSING} if this is not an object or the key is absent. */
    public Json get(String key) {
        if (kind != Kind.OBJECT) {
            return MISSING;
        }
        Json found = map().get(key);
        return found == null ? MISSING : found;
    }

    /** Array element lookup. Returns {@link #MISSING} if this is not an array or the index is out of range. */
    public Json get(int index) {
        if (kind != Kind.ARRAY) {
            return MISSING;
        }
        List<Json> l = list();
        return index < 0 || index >= l.size() ? MISSING : l.get(index);
    }

    public boolean has(String key) {
        return kind == Kind.OBJECT && map().containsKey(key);
    }

    /** Element count for arrays and objects; 0 for scalars. */
    public int size() {
        if (kind == Kind.ARRAY) {
            return list().size();
        }
        if (kind == Kind.OBJECT) {
            return map().size();
        }
        return 0;
    }

    /** Elements of an array. Empty (never null) for any other kind, so it is always safe to iterate. */
    public List<Json> elements() {
        return kind == Kind.ARRAY ? Collections.unmodifiableList(list()) : List.of();
    }

    /** Members of an object in insertion order. Empty (never null) for any other kind. */
    public Map<String, Json> fields() {
        return kind == Kind.OBJECT ? Collections.unmodifiableMap(map()) : Map.of();
    }

    // ---------------------------------------------------------------- mutation

    public Json put(String key, Json child) {
        requireKind(Kind.OBJECT, "put");
        map().put(key, child == null ? NULL : child);
        return this;
    }

    public Json put(String key, String v)  { return put(key, of(v)); }
    public Json put(String key, long v)    { return put(key, of(v)); }
    public Json put(String key, double v)  { return put(key, of(v)); }
    public Json put(String key, boolean v) { return put(key, of(v)); }

    public Json remove(String key) {
        requireKind(Kind.OBJECT, "remove");
        map().remove(key);
        return this;
    }

    public Json add(Json child) {
        requireKind(Kind.ARRAY, "add");
        list().add(child == null ? NULL : child);
        return this;
    }

    public Json add(String v) { return add(of(v)); }

    private void requireKind(Kind expected, String op) {
        if (kind != expected) {
            throw new IllegalStateException("cannot " + op + " on JSON " + kind);
        }
    }

    // ---------------------------------------------------------------- accessors

    /** @throws JsonException if this is not a string. */
    public String asString() {
        if (kind != Kind.STRING) {
            throw new JsonException("expected string, got " + kind);
        }
        return (String) value;
    }

    public String asString(String fallback) {
        return kind == Kind.STRING ? (String) value : fallback;
    }

    public long asLong(long fallback) {
        if (kind == Kind.NUMBER) {
            return ((Number) value).longValue();
        }
        if (kind == Kind.STRING) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public int asInt(int fallback) {
        return (int) asLong(fallback);
    }

    public double asDouble(double fallback) {
        return kind == Kind.NUMBER ? ((Number) value).doubleValue() : fallback;
    }

    public boolean asBool(boolean fallback) {
        return kind == Kind.BOOL ? (Boolean) value : fallback;
    }

    /** Convenience: string value of a member, or the fallback. */
    public String str(String key, String fallback) {
        return get(key).asString(fallback);
    }

    // ---------------------------------------------------------------- output

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, -1, 0);
        return sb.toString();
    }

    public String toPrettyString() {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, 2, 0);
        return sb.toString();
    }

    private void writeTo(StringBuilder sb, int indent, int depth) {
        switch (kind) {
            case MISSING, NULL -> sb.append("null");
            case BOOL -> sb.append(((Boolean) value) ? "true" : "false");
            case NUMBER -> sb.append(numberToString((Number) value));
            case STRING -> escape(sb, (String) value);
            case ARRAY -> {
                List<Json> l = list();
                if (l.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    newlineIndent(sb, indent, depth + 1);
                    l.get(i).writeTo(sb, indent, depth + 1);
                }
                newlineIndent(sb, indent, depth);
                sb.append(']');
            }
            case OBJECT -> {
                Map<String, Json> m = map();
                if (m.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Json> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    newlineIndent(sb, indent, depth + 1);
                    escape(sb, e.getKey());
                    sb.append(':');
                    if (indent >= 0) {
                        sb.append(' ');
                    }
                    e.getValue().writeTo(sb, indent, depth + 1);
                }
                newlineIndent(sb, indent, depth);
                sb.append('}');
            }
        }
    }

    private static void newlineIndent(StringBuilder sb, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        sb.append('\n');
        sb.append(" ".repeat(indent * depth));
    }

    private static String numberToString(Number n) {
        if (n instanceof Long || n instanceof Integer) {
            return n.toString();
        }
        double d = n.doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return n.toString();
    }

    private static void escape(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- parser

    /** Recursive-descent parser. Strict: rejects trailing content, trailing commas and unquoted keys. */
    private static final class Parser {
        private static final int MAX_DEPTH = 200;

        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
            // Tolerate a UTF-8 BOM: some mirrors of version JSON carry one.
            this.pos = (!src.isEmpty() && src.charAt(0) == '\uFEFF') ? 1 : 0;
        }

        Json parseDocument() {
            skipWhitespace();
            Json result = parseValue(0);
            skipWhitespace();
            if (pos < src.length()) {
                throw error("trailing content after JSON document");
            }
            return result;
        }

        private Json parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting deeper than " + MAX_DEPTH);
            }
            if (pos >= src.length()) {
                throw error("unexpected end of input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> of(parseString());
                case 't' -> { expectLiteral("true"); yield TRUE; }
                case 'f' -> { expectLiteral("false"); yield FALSE; }
                case 'n' -> { expectLiteral("null"); yield NULL; }
                default -> parseNumber();
            };
        }

        private Json parseObject(int depth) {
            pos++; // '{'
            Json obj = object();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return obj;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected '\"' to start an object key");
                }
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw error("expected ':' after object key '" + key + "'");
                }
                pos++;
                skipWhitespace();
                obj.map().put(key, parseValue(depth + 1));
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return obj;
                }
                throw error("expected ',' or '}' in object");
            }
        }

        private Json parseArray(int depth) {
            pos++; // '['
            Json arr = array();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return arr;
            }
            while (true) {
                skipWhitespace();
                arr.list().add(parseValue(depth + 1));
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return arr;
                }
                throw error("expected ',' or ']' in array");
            }
        }

        private String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw error("unterminated string");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw error("unescaped control character in string");
                    }
                    sb.append(c);
                    continue;
                }
                if (pos >= src.length()) {
                    throw error("unterminated escape sequence");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'u'  -> {
                        if (pos + 4 > src.length()) {
                            throw error("truncated \\u escape");
                        }
                        String hex = src.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("invalid \\u escape '" + hex + "'");
                        }
                        pos += 4;
                    }
                    default -> throw error("invalid escape '\\" + esc + "'");
                }
            }
        }

        private Json parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < src.length() && isDigit(src.charAt(pos))) {
                pos++;
            }
            boolean floating = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                floating = true;
                pos++;
                while (pos < src.length() && isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                floating = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < src.length() && isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            String text = src.substring(start, pos);
            if (text.isEmpty() || text.equals("-")) {
                throw error("invalid number");
            }
            if (!floating) {
                try {
                    return of(Long.parseLong(text));
                } catch (NumberFormatException ignored) {
                    // falls through to double for values beyond long range
                }
            }
            try {
                return of(Double.parseDouble(text));
            } catch (NumberFormatException e) {
                throw error("invalid number '" + text + "'");
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private void expectLiteral(String literal) {
            if (!src.startsWith(literal, pos)) {
                throw error("expected '" + literal + "'");
            }
            pos += literal.length();
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private JsonException error(String message) {
            int line = 1;
            int col = 1;
            for (int i = 0; i < Math.min(pos, src.length()); i++) {
                if (src.charAt(i) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new JsonException(message + " (line " + line + ", column " + col + ")");
        }
    }
}
