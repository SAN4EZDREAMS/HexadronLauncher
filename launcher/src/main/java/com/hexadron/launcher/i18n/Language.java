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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A language the launcher ships strings for.
 *
 * <p>The display name is written in the language itself, not translated into
 * the current one: a user who has the launcher in a language they cannot read
 * still has to be able to find their own entry in the list.
 */
public enum Language {

    ENGLISH("en", "English"),
    UKRAINIAN("uk", "Українська"),
    RUSSIAN("ru", "Русский"),
    POLISH("pl", "Polski"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    // Brazilian Portuguese, under the bare code: byCode drops the region, so
    // pt-BR and pt-PT both land here, and Brazil is most of the players.
    PORTUGUESE("pt", "Português (Brasil)"),
    TURKISH("tr", "Türkçe"),
    INDONESIAN("id", "Bahasa Indonesia"),
    VIETNAMESE("vi", "Tiếng Việt"),
    HINDI("hi", "हिन्दी"),
    // Simplified, under the bare code for the same reason: zh-CN and zh-TW both
    // resolve to "zh", and there is one Chinese file.
    CHINESE("zh", "简体中文"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어");

    /** The language used when nothing else matches, and the fallback for missing keys. */
    public static final Language DEFAULT = ENGLISH;

    private final String code;
    private final String displayName;

    Language(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** ISO 639-1 code, and the name of the properties file under {@code /lang}. */
    public String code() {
        return code;
    }

    /** The language's own name, for the picker. */
    public String displayName() {
        return displayName;
    }

    public Locale locale() {
        return Locale.of(code);
    }

    public static List<Language> all() {
        return List.of(values());
    }

    /** Looks a language up by code. Case and region suffix are ignored. */
    public static Optional<Language> byCode(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String wanted = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = wanted.indexOf('-');
        if (dash > 0) {
            wanted = wanted.substring(0, dash);
        }
        for (Language language : values()) {
            if (language.code.equals(wanted)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the language to start in.
     *
     * @param saved the code stored in {@code launcher.json}; blank means "follow
     *              the operating system", which is the default so that a first
     *              run is already in the user's language where we have one
     */
    public static Language resolve(String saved) {
        return byCode(saved)
                .or(() -> byCode(Locale.getDefault().getLanguage()))
                .orElse(DEFAULT);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
