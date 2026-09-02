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

package com.hexadron.launcher.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Translated strings.
 *
 * <p>Deliberately not {@link java.util.ResourceBundle}: bundles resolve against
 * the JVM default locale and silently fall back through a chain that is awkward
 * to reason about, while the launcher needs one explicit switch that also has to
 * work at runtime, without a restart. This class holds exactly one active map
 * and falls back to English per key, so a half-finished translation degrades to
 * English words rather than to {@code missing_key}.
 *
 * <p>Files live at {@code /lang/<code>.properties} and are read as UTF-8.
 */
public final class I18n {

    private static final String PATH = "/lang/%s.properties";

    private static final Map<String, String> FALLBACK = load(Language.DEFAULT);

    private static volatile Language current = Language.DEFAULT;
    private static volatile Map<String, String> strings = FALLBACK;

    /** Switches the active language. Takes effect for every later {@link #t} call. */
    public static void use(Language language) {
        Language target = language == null ? Language.DEFAULT : language;
        strings = target == Language.DEFAULT ? FALLBACK : load(target);
        current = target;
    }

    public static Language current() {
        return current;
    }

    /**
     * Returns the translation for {@code key}.
     *
     * <p>Arguments are substituted with {@link MessageFormat}, but only when
     * some are supplied - that keeps a translated string containing a literal
     * brace from being reinterpreted as a format pattern.
     */
    public static String t(String key, Object... args) {
        String pattern = strings.get(key);
        if (pattern == null) {
            pattern = FALLBACK.get(key);
        }
        if (pattern == null) {
            // Visible rather than silent: a missing key is a bug in the bundle,
            // and SelfCheck fails the build on one.
            return "!" + key + "!";
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        return new MessageFormat(pattern, current.locale()).format(args);
    }

    /** The raw map for a language. Used by SelfCheck to compare key sets. */
    public static Map<String, String> bundle(Language language) {
        return language == Language.DEFAULT ? FALLBACK : load(language);
    }

    private static Map<String, String> load(Language language) {
        String resource = PATH.formatted(language.code());
        try (InputStream in = I18n.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UncheckedIOException(
                        new IOException("missing language file " + resource));
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> map = new LinkedHashMap<>();
            properties.forEach((key, value) -> map.put(key.toString(), value.toString()));
            return Collections.unmodifiableMap(map);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private I18n() {
    }
}
