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

package com.hexadron.launcher.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials from anything that is about to be shown, logged or
 * written to a file.
 *
 * <p><b>Why this exists.</b> Every publicly documented loss of a Minecraft
 * session in the last three years came out of a log file, not out of broken
 * cryptography: CVE-2025-54120 was a launcher writing login credentials to its
 * own debug log, and the standing advice for JVM crash logs is "never share an
 * unedited one, it contains your access token". A token that never reaches a
 * log cannot be leaked by a user pasting that log into a support channel.
 *
 * <p>Two layers, because either one alone fails:
 * <ul>
 *   <li><b>Registered secrets.</b> Anything the launcher knows to be a secret
 *       is registered here the moment it is created, and is then replaced by
 *       exact match wherever it appears.</li>
 *   <li><b>Shape patterns.</b> A token that arrives in a response the launcher
 *       did not expect was never registered, so exact matching cannot catch it.
 *       The patterns below match the shapes Microsoft, Xbox and Mojang actually
 *       issue, so an unexpected token is still masked.</li>
 * </ul>
 *
 * <p>This class is deliberately conservative about short strings: registering a
 * three-character value would turn every log line into asterisks. Values below
 * {@link #MIN_SECRET_LENGTH} are ignored.
 */
public final class Redactor {

    /** Shorter values are too likely to occur as ordinary text to mask safely. */
    public static final int MIN_SECRET_LENGTH = 12;

    private static final String MASK = "<redacted>";

    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    /**
     * Shapes that are a credential no matter where they came from.
     *
     * <ul>
     *   <li>JWT - three base64url segments. Xbox Live, XSTS and Minecraft
     *       services tokens are all JWTs.</li>
     *   <li>{@code M.C5_...} / {@code M.R3_...} - Microsoft account access and
     *       refresh tokens.</li>
     *   <li>{@code XBL3.0 x=<hash>;<token>} - the Minecraft services identity
     *       header.</li>
     *   <li>{@code token:<token>:<uuid>} - the legacy {@code --session}
     *       argument.</li>
     *   <li>An OAuth authorization code or PKCE verifier appearing in a URL.</li>
     * </ul>
     */
    private static final Pattern[] SHAPES = {
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            Pattern.compile("M\\.[A-Za-z0-9]{1,4}_[A-Za-z0-9._-]{20,}"),
            Pattern.compile("XBL3\\.0\\s+x=[^;\\s]+;[A-Za-z0-9._-]{20,}"),
            Pattern.compile("token:[A-Za-z0-9._-]{20,}:"),
            Pattern.compile("(?<=[?&])(code|code_verifier|refresh_token|access_token|id_token|device_code)="
                    + "[^&\\s\"']{8,}"),
            Pattern.compile("(?<=\")(refresh_token|access_token|id_token|device_code|Token)"
                    + "(?=\"\\s*:\\s*\")[^\"]*\"[^\"]{20,}\""),

            // An opaque token behind a name that says what it is. The shapes
            // above all describe what Microsoft and Xbox issue; a third-party
            // Yggdrasil service issues a plain random string, which looks like
            // nothing in particular and would go into a log untouched. What
            // gives it away is not the value but the word in front of it.
            Pattern.compile("(?<=--accessToken )\\S{16,}"),
            Pattern.compile("(?<=--session )\\S{16,}"),
            Pattern.compile("(?<=\"accessToken\"\\s{0,3}:\\s{0,3}\")[^\"]{16,}"),
            Pattern.compile("(?<=\"clientToken\"\\s{0,3}:\\s{0,3}\")[^\"]{16,}"),
    };

    private Redactor() {
    }

    /**
     * Marks a value as a secret. Safe to call repeatedly with the same value.
     *
     * @return the value unchanged, so this can wrap an assignment
     */
    public static String register(String secret) {
        if (secret != null && secret.length() >= MIN_SECRET_LENGTH) {
            REGISTERED.add(secret);
        }
        return secret;
    }

    /** Stops masking a value - used when a token is replaced by a newer one. */
    public static void forget(String secret) {
        if (secret != null) {
            REGISTERED.remove(secret);
        }
    }

    /** Drops every registered secret. Used on sign-out. */
    public static void clear() {
        REGISTERED.clear();
    }

    /** Number of currently registered secrets. For the self-check only. */
    public static int registeredCount() {
        return REGISTERED.size();
    }

    /**
     * Returns {@code text} with every registered secret and every
     * credential-shaped substring replaced by {@value #MASK}.
     */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String secret : REGISTERED) {
            if (out.contains(secret)) {
                out = out.replace(secret, MASK);
            }
        }
        for (Pattern shape : SHAPES) {
            Matcher matcher = shape.matcher(out);
            if (matcher.find()) {
                out = matcher.replaceAll(MASK);
            }
        }
        return out;
    }

    /**
     * Shows enough of a value to tell two of them apart, and no more.
     * Used where a log line has to identify which token it is talking about.
     */
    public static String fingerprint(String secret) {
        if (secret == null || secret.isBlank()) {
            return "<none>";
        }
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
        byte[] hash = digest.digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(8);
        for (int i = 0; i < 4; i++) {
            hex.append(String.format("%02x", hash[i]));
        }
        return "sha256:" + hex;
    }
}
