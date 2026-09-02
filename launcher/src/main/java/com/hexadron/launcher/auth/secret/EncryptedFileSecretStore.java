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

package com.hexadron.launcher.auth.secret;

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.util.FilePermissions;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The fallback: AES-256-GCM with a key file next to the ciphertext.
 *
 * <p><b>Read this before assuming it protects anything.</b> The key is on the
 * same disk, in the same folder, readable by the same account. Anyone who can
 * read {@code secrets.json} can read {@code secrets.key}, and the encryption
 * buys nothing against them. Calling this "encrypted storage" without saying so
 * would be the false-comfort claim the previous plaintext implementation was
 * right to refuse to make.
 *
 * <p>What it does buy, honestly:
 * <ul>
 *   <li>A credential no longer appears in plaintext in a screen share, a
 *       support screenshot, a synced cloud folder listing, or a naive
 *       grep-for-tokens sweep by a stealer that only knows the shapes of
 *       {@code accounts.json} files.</li>
 *   <li>Tampering is detected: GCM authenticates, so a modified file fails
 *       rather than silently loading a substituted token.</li>
 *   <li>Each entry has its own nonce, so the same token stored twice does not
 *       produce the same bytes.</li>
 * </ul>
 *
 * <p>This store is used only when {@link DpapiSecretStore},
 * {@link KeychainSecretStore} and {@link SecretServiceStore} are all
 * unavailable, and the launcher tells the user which one it ended up on.
 */
public final class EncryptedFileSecretStore implements SecretStore {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path directory;
    private final Path keyFile;
    private final Path dataFile;
    private final SecureRandom random = new SecureRandom();

    public EncryptedFileSecretStore(Path secretsDirectory) {
        this.directory = secretsDirectory;
        this.keyFile = secretsDirectory.resolve("secrets.key");
        this.dataFile = secretsDirectory.resolve("secrets.json");
    }

    @Override
    public String id() {
        return "file";
    }

    @Override
    public String displayName() {
        return "Encrypted file (no OS credential store available)";
    }

    @Override
    public boolean isOsProtected() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        try {
            Cipher.getInstance(TRANSFORMATION);
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override
    public synchronized void store(String key, String value) throws IOException {
        Map<String, String> entries = readAll();
        entries.put(key, encrypt(value));
        writeAll(entries);
    }

    @Override
    public synchronized Optional<String> load(String key) throws IOException {
        String encrypted = readAll().get(key);
        if (encrypted == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(decrypt(encrypted));
        } catch (GeneralSecurityException e) {
            // A key file replaced or a truncated write. Treat as "sign in again"
            // rather than as a hard failure that leaves the launcher unusable.
            throw new IOException("stored credential could not be decrypted; sign in again", e);
        }
    }

    @Override
    public synchronized void delete(String key) throws IOException {
        Map<String, String> entries = readAll();
        if (entries.remove(key) != null) {
            writeAll(entries);
        }
    }

    // ---------------------------------------------------------------- internals

    private Map<String, String> readAll() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        if (!Files.isRegularFile(dataFile)) {
            return entries;
        }
        Json root = Json.read(dataFile);
        root.fields().forEach((name, value) -> entries.put(name, value.asString("")));
        return entries;
    }

    private void writeAll(Map<String, String> entries) throws IOException {
        Json root = Json.object();
        entries.forEach(root::put);
        FilePermissions.writeRestricted(dataFile, root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String encrypt(String plaintext) throws IOException {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IOException("could not encrypt the credential", e);
        }
    }

    private String decrypt(String encoded) throws IOException, GeneralSecurityException {
        byte[] combined = Base64.getDecoder().decode(encoded);
        if (combined.length <= NONCE_BYTES) {
            throw new GeneralSecurityException("stored credential is truncated");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(combined, 0, nonce, 0, NONCE_BYTES);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
        byte[] plaintext = cipher.doFinal(combined, NONCE_BYTES, combined.length - NONCE_BYTES);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private synchronized SecretKey key() throws IOException {
        if (Files.isRegularFile(keyFile)) {
            byte[] raw = Base64.getDecoder().decode(
                    Files.readString(keyFile, StandardCharsets.US_ASCII).strip());
            if (raw.length == KEY_BYTES) {
                return new SecretKeySpec(raw, "AES");
            }
        }
        byte[] raw = new byte[KEY_BYTES];
        random.nextBytes(raw);
        FilePermissions.createRestrictedDirectory(directory);
        FilePermissions.writeRestricted(keyFile,
                Base64.getEncoder().encodeToString(raw).getBytes(StandardCharsets.US_ASCII));
        return new SecretKeySpec(raw, "AES");
    }
}
