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

package com.hexadron.launcher.auth;

import com.hexadron.launcher.auth.secret.SecretStore;
import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.util.FilePermissions;
import com.hexadron.launcher.util.Redactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists accounts: metadata to {@code accounts.json}, credentials to the
 * operating system's credential store.
 *
 * <h2>What changed, and why</h2>
 *
 * <p>This class used to write refresh tokens in plain text and argue that
 * encryption without an OS keychain is obfuscation. The first half of that
 * argument was right and the conclusion was wrong: the answer is not to encrypt
 * badly, it is to use the keychain. Every launcher surveyed - Prism, MultiMC,
 * ATLauncher, Modrinth App, GDLauncher, Helios - still writes tokens in the
 * clear, and infostealers are built specifically to collect those files. Prism's
 * own issue tracker states it plainly: "Any data grabber may simply steal the
 * token."
 *
 * <p>So the split is:
 *
 * <ul>
 *   <li><b>{@code accounts.json}</b> - username, UUID, XUID, token expiry,
 *       which account is selected. No credentials. Safe to read, copy or attach
 *       to a bug report.</li>
 *   <li><b>{@link SecretStore}</b> - the Microsoft refresh token and the
 *       Minecraft access token, under DPAPI on Windows, the Keychain on macOS or
 *       the Secret Service on Linux. Where none of those work, an encrypted file
 *       that says what it is and is not.</li>
 * </ul>
 *
 * <p><b>The limit, stated once.</b> None of this stops code running as the user.
 * A malicious mod can read the token out of the running game, and the launcher
 * itself can always ask the keychain for what it put there. What it does stop is
 * a file grab - a stealer sweeping for {@code accounts.json}, a synced folder, a
 * backup restored under another account, a second user on a family PC.
 *
 * <p><b>Migration.</b> A file written by an older version still has tokens in
 * it. On first load those are moved into the credential store and the file is
 * rewritten without them. The old file is not left behind.
 */
public final class AccountStore {

    private final Path file;
    private final SecretStore secrets;
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private String selectedId;
    private boolean migratedFromPlaintext;

    public AccountStore(GameDirs dirs, SecretStore secrets) {
        this.file = dirs.accountsFile();
        this.secrets = secrets;
    }

    /** Where the credentials for this run are being kept. Shown in the interface. */
    public SecretStore secretStore() {
        return secrets;
    }

    /** True when this load converted a plaintext file from an older version. */
    public boolean migratedFromPlaintext() {
        return migratedFromPlaintext;
    }

    public synchronized AccountStore load() throws IOException {
        accounts.clear();
        selectedId = null;
        migratedFromPlaintext = false;
        if (!Files.isRegularFile(file)) {
            return this;
        }
        Json root = Json.read(file);
        boolean legacy = false;

        for (Json entry : root.get("accounts").elements()) {
            try {
                Account account;
                if (entry.has("accessToken") || entry.has("refreshToken")) {
                    // Written by a version that kept credentials in this file.
                    account = Account.fromLegacyJson(entry);
                    legacy = true;
                } else {
                    account = Account.fromMetadataJson(entry, readSecret(entry));
                }
                registerSecrets(account);
                accounts.put(account.id(), account);
            } catch (RuntimeException e) {
                // One corrupt entry must not lock the user out of every account.
                System.err.println("skipping unreadable account entry: " + Redactor.scrub(e.getMessage()));
            }
        }
        selectedId = root.get("selected").asString(null);
        if (selectedId != null && !accounts.containsKey(selectedId)) {
            selectedId = null;
        }

        if (legacy) {
            // Rewrites accounts.json without the tokens and puts them in the
            // credential store. Done here rather than lazily so that a user who
            // upgrades and never signs in again still gets the file cleaned.
            save();
            migratedFromPlaintext = true;
        }
        return this;
    }

    public synchronized void save() throws IOException {
        Json array = Json.array();
        for (Account account : accounts.values()) {
            Json metadata = account.toMetadataJson();
            if (!account.isOffline()) {
                writeSecret(account);
                metadata.put("secretKey", secretKey(account));
            }
            array.add(metadata);
        }

        Json root = Json.object().put("accounts", array);
        if (selectedId != null) {
            root.put("selected", selectedId);
        }
        // accounts.json no longer holds credentials, but it still identifies
        // which Microsoft accounts a machine has been signed into, so it keeps
        // owner-only permissions - on Windows too, which the old POSIX-only
        // implementation silently skipped.
        FilePermissions.writeRestricted(file, root.toPrettyString().getBytes(StandardCharsets.UTF_8));
    }

    public synchronized List<Account> all() {
        return List.copyOf(new ArrayList<>(accounts.values()));
    }

    public synchronized Optional<Account> selected() {
        if (selectedId == null) {
            return accounts.values().stream().findFirst();
        }
        return Optional.ofNullable(accounts.get(selectedId));
    }

    public synchronized void add(Account account) {
        registerSecrets(account);
        accounts.put(account.id(), account);
        if (selectedId == null) {
            selectedId = account.id();
        }
    }

    /** Replaces an account in place, keeping selection - used after a token refresh. */
    public synchronized void update(Account account) {
        Account previous = accounts.get(account.id());
        if (previous != null) {
            Redactor.forget(previous.accessToken());
            Redactor.forget(previous.refreshToken());
        }
        registerSecrets(account);
        accounts.put(account.id(), account);
    }

    /**
     * Removes an account and its credentials.
     *
     * <p>The credential store entry is deleted first: an account that vanished
     * from the list while its refresh token stayed in the keychain would be a
     * token nothing can ever revoke from inside the launcher.
     */
    public synchronized void remove(Account account) throws IOException {
        accounts.remove(account.id());
        Redactor.forget(account.accessToken());
        Redactor.forget(account.refreshToken());
        if (!account.isOffline()) {
            secrets.delete(secretKey(account));
        }
        if (account.id().equals(selectedId)) {
            selectedId = accounts.keySet().stream().findFirst().orElse(null);
        }
    }

    public synchronized void select(Account account) {
        if (accounts.containsKey(account.id())) {
            selectedId = account.id();
        }
    }

    public synchronized boolean isEmpty() {
        return accounts.isEmpty();
    }

    // ---------------------------------------------------------------- secrets

    /**
     * The credential store key for an account.
     *
     * <p>The account id, not the username: a username can be changed on
     * mojang.com, and a key that moves would orphan the stored token.
     */
    private static String secretKey(Account account) {
        return "account/" + account.id();
    }

    private Json readSecret(Json metadata) {
        String key = metadata.get("secretKey").asString(null);
        if (key == null) {
            // Metadata written before secretKey existed, or an offline account.
            String uuid = metadata.get("uuid").asString(null);
            String type = metadata.get("type").asString("OFFLINE");
            if (uuid == null || type.equals("OFFLINE")) {
                return Json.object();
            }
            key = "account/" + type.toLowerCase(java.util.Locale.ROOT) + ":" + uuid;
        }
        try {
            return secrets.load(key).map(Json::parse).orElseGet(Json::object);
        } catch (IOException | RuntimeException e) {
            // A locked keychain or a rotated key. The account stays in the list
            // with no refresh token, which the interface shows as "sign in again"
            // rather than silently dropping it.
            System.err.println("could not read stored credentials for an account: "
                    + Redactor.scrub(String.valueOf(e.getMessage())));
            return Json.object();
        }
    }

    private void writeSecret(Account account) throws IOException {
        Json blob = account.toSecretJson();
        if (blob.size() == 0) {
            secrets.delete(secretKey(account));
            return;
        }
        secrets.store(secretKey(account), blob.toString());
    }

    private static void registerSecrets(Account account) {
        Redactor.register(account.accessToken());
        Redactor.register(account.refreshToken());
    }
}
