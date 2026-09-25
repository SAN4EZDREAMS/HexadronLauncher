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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Proves that an update manifest was written by the author of this launcher.
 *
 * <h2>Why a signature, and why the hash alone was not enough</h2>
 *
 * <p>The manifest lists the SHA-256 of every file in a build and of the whole
 * archive. That catches a damaged or swapped download. It does not catch a
 * swapped <em>release</em>: the manifest is published in the same release as
 * the archive, so whoever can replace one can replace both. A stolen GitHub
 * token would be enough to hand every installed launcher a build of somebody
 * else's choosing.
 *
 * <p>A signature closes that. The private key never goes to GitHub as a file in
 * the repository; the release workflow reads it from an encrypted secret, signs
 * the manifest, and the launcher checks the result against the public key
 * below, which is compiled in. Replacing the release is then not enough - the
 * attacker also needs the key.
 *
 * <h2>The format</h2>
 *
 * <p>Ed25519 over the exact bytes of {@code HexadronLauncher-<system>.manifest.json},
 * published beside it as {@code HexadronLauncher-<system>.manifest.json.sig}: the
 * 64-byte signature, Base64, on one line. That is what
 * {@code openssl pkeyutl -sign -rawin} produces for an Ed25519 key, so the
 * workflow needs no Java to sign, and the JDK verifies it with no library.
 *
 * <h2>Rotating the key</h2>
 *
 * <p>{@link #PUBLIC_KEYS} is a list. Add the new key, release with the old one,
 * then sign with the new one and remove the old key in a later release. A
 * launcher only ever trusts the keys it was built with.
 */
public final class UpdateSignature {

    /** Published beside each manifest. */
    public static final String SUFFIX = ".sig";

    /**
     * The keys an update manifest may be signed with, as Base64 of the DER
     * {@code SubjectPublicKeyInfo} - the output of
     * {@code openssl pkey -in update-signing.pem -pubout -outform DER | base64 -w0}.
     *
     * <p>Empty until the author generates a key pair (see docs/updates.md).
     * While it is empty, {@link #isConfigured()} is false and updates are
     * checked by hash only, as before; the update window says so in its log.
     */
    static final List<String> PUBLIC_KEYS = List.of(
            "MCowBQYDK2VwAyEAdPaqOY00jq5d7Qz6kOunmfCzQn5pL1XpTPmSDyFQrus="
    );

    private UpdateSignature() {
    }

    /** True when this build carries at least one key, so signatures are required. */
    public static boolean isConfigured() {
        return !PUBLIC_KEYS.isEmpty();
    }

    /**
     * True when {@code signature} is a valid signature of {@code manifest} by any
     * key this build trusts.
     *
     * @param signature the published {@code .sig} file, Base64 text
     */
    public static boolean verify(byte[] manifest, byte[] signature) {
        return verify(manifest, signature, PUBLIC_KEYS);
    }

    /** The same, against the given keys. Separate so the self-check can use a test key. */
    public static boolean verify(byte[] manifest, byte[] signature, List<String> keys) {
        if (manifest == null || signature == null || keys.isEmpty()) {
            return false;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(
                    new String(signature, StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (raw.length != 64) {
            return false;
        }
        for (PublicKey key : keys(keys)) {
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                verifier.update(manifest);
                if (verifier.verify(raw)) {
                    return true;
                }
            } catch (GeneralSecurityException e) {
                // A key that cannot be used cannot vouch for anything; try the next.
            }
        }
        return false;
    }

    private static List<PublicKey> keys(List<String> encoded) {
        List<PublicKey> keys = new ArrayList<>(encoded.size());
        for (String value : encoded) {
            try {
                keys.add(KeyFactory.getInstance("Ed25519").generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(value.trim()))));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                // Skipped. A malformed constant is a build mistake, and the
                // self-check fails on it before any release is made.
            }
        }
        return keys;
    }

    /** True when every configured key can be read. Used by the self-check. */
    public static boolean keysAreReadable() {
        return keys(PUBLIC_KEYS).size() == PUBLIC_KEYS.size();
    }
}
