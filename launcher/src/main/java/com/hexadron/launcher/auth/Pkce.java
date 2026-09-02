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

package com.hexadron.launcher.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Proof Key for Code Exchange (RFC 7636) and the CSRF {@code state} value.
 *
 * <p><b>What PKCE is for here.</b> The launcher is a public client: its client
 * ID ships inside a jar that anyone can open, so there is no client secret and
 * the authorization code is the only thing standing between a bystander and the
 * account. On a desktop the code comes back over a loopback HTTP redirect, and
 * every other process on the machine can bind a loopback port or watch the
 * browser's history. PKCE makes an intercepted code useless: the token endpoint
 * only accepts it together with the verifier, which never leaves this process.
 *
 * <p>RFC 8252 §8.1 states the requirement plainly - public native app clients
 * MUST implement PKCE. Of the launchers surveyed while writing this, only Prism
 * does, and only because Qt does it for it; ATLauncher and HeliosLauncher send
 * neither a challenge nor a state.
 *
 * <p>Only {@code S256} is produced. The {@code plain} method exists in the RFC
 * for devices that cannot compute SHA-256, which is not a category any machine
 * running Minecraft belongs to.
 */
public record Pkce(String verifier, String challenge, String state) {

    /** RFC 7636 §4.1 allows 43-128 characters; 32 random bytes gives 43. */
    private static final int VERIFIER_BYTES = 32;
    private static final int STATE_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    public static final String CHALLENGE_METHOD = "S256";

    /** Generates a fresh verifier, its S256 challenge, and a state value. */
    public static Pkce generate() {
        String verifier = randomUrlSafe(VERIFIER_BYTES);
        return new Pkce(verifier, challengeFor(verifier), randomUrlSafe(STATE_BYTES));
    }

    /** {@code BASE64URL(SHA256(ASCII(verifier)))}, per RFC 7636 §4.2. */
    public static String challengeFor(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return URL_ENCODER.encodeToString(raw);
    }

    /**
     * Constant-time comparison for the returned {@code state}.
     *
     * <p>A length-dependent early exit would leak how much of a guessed state is
     * correct. The window is small and the attack is impractical over a loopback
     * socket, but a comparison that is right by construction costs nothing.
     */
    public boolean matchesState(String returned) {
        if (returned == null) {
            return false;
        }
        return MessageDigest.isEqual(
                state.getBytes(StandardCharsets.US_ASCII),
                returned.getBytes(StandardCharsets.US_ASCII));
    }

    /** Never let a verifier reach a log through a stack trace or a debug print. */
    @Override
    public String toString() {
        return "Pkce[verifier=<redacted>, challenge=<redacted>, state=<redacted>]";
    }
}
