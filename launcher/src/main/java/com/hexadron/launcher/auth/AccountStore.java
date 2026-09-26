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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Accounts whose credentials have not reached the credential store yet.
     *
     * <p>Kept so that {@link #saveSelection()} can rewrite {@code accounts.json}
     * without a round trip to the keychain for every account. On Windows each
     * store is a PowerShell process, and the selection is saved every time the
     * account box changes - five signed-in accounts would be five processes per
     * click. The credentials only change on sign-in and refresh, and those are
     * the calls that put an account in here.
     */
    private final Set<String> unsavedSecrets = new HashSet<>();

    /**
     * Accounts listed in accounts.json whose credentials have not been read yet,
     * by id, with the metadata they were listed with.
     *
     * <p>Reading them is a PowerShell launch per account on Windows (DPAPI),
     * about a second in all, and nothing on the way to the window needs them.
     * So {@link #load()} reads names only; {@link #loadSecrets()} reads the
     * rest, on the warm-up thread after the window is up, or at once for a
     * caller that needs a token before that. An account in here is never
     * written back to the credential store: it has nothing in memory to write.
     */
    private final Map<String, Json> pendingSecrets = new LinkedHashMap<>();

    /**
     * Accounts whose credentials could not be read (a locked keychain, a
     * helper that timed out), with the metadata they were listed with.
     *
     * <p>Treated like {@link #pendingSecrets} when writing: an account in here
     * has no tokens in memory, and saving it as if it had none would delete
     * the credentials that are still safely stored. Read again when a caller
     * actually needs them ({@link #withSecrets}).
     */
    private final Map<String, Json> unreadableSecrets = new LinkedHashMap<>();

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
        unsavedSecrets.clear();
        pendingSecrets.clear();
        unreadableSecrets.clear();
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
                    account = Account.fromMetadataJson(entry, Json.object());
                    if (!account.isOffline()) {
                        pendingSecrets.put(account.id(), entry);
                    }
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
        writeFile(true);
    }

    /**
     * Reads the credentials that {@link #load()} left for later. Safe to call
     * from any thread and more than once; the first call does the work and the
     * others wait for it or return at once.
     */
    public synchronized void loadSecrets() {
        if (pendingSecrets.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Json> pending : new ArrayList<>(pendingSecrets.entrySet())) {
            String id = pending.getKey();
            // Replaced since load() - a sign-in or a refresh brought newer
            // credentials than the ones on disk, and those win.
            if (accounts.containsKey(id)) {
                Json secret = readSecret(pending.getValue());
                if (secret == null) {
                    unreadableSecrets.put(id, pending.getValue());
                } else {
                    try {
                        Account full = Account.fromMetadataJson(pending.getValue(), secret);
                        registerSecrets(full);
                        accounts.put(id, full);
                    } catch (RuntimeException e) {
                        unreadableSecrets.put(id, pending.getValue());
                        System.err.println("could not read stored credentials for an account: "
                                + Redactor.scrub(String.valueOf(e.getMessage())));
                    }
                }
            }
            pendingSecrets.remove(id);
        }
    }

    /**
     * The account as stored here, with its credentials.
     *
     * <p>For anything about to use a token. The instance a list or a combo box
     * holds may be the one made before the credentials were read.
     */
    public synchronized Account withSecrets(Account account) {
        if (account == null) {
            return null;
        }
        // One more try for credentials that could not be read before: the
        // keychain may have been unlocked since.
        Json unreadable = unreadableSecrets.remove(account.id());
        if (unreadable != null) {
            pendingSecrets.put(account.id(), unreadable);
        }
        loadSecrets();
        return accounts.getOrDefault(account.id(), account);
    }

    /**
     * Writes which account is selected, and the list it is selected from.
     *
     * <p>The same file as {@link #save()}, but credentials are only written for
     * accounts that have new ones. This is what the account box calls on every
     * change, so the launcher opens on the account it was closed on.
     */
    public synchronized void saveSelection() throws IOException {
        writeFile(false);
    }

    private void writeFile(boolean allSecrets) throws IOException {
        Json array = Json.array();
        for (Account account : accounts.values()) {
            Json metadata = account.toMetadataJson();
            if (!account.isOffline()) {
                Json listed = pendingSecrets.containsKey(account.id())
                        ? pendingSecrets.get(account.id())
                        : unreadableSecrets.get(account.id());
                boolean credentialsInMemory = listed == null;
                if (credentialsInMemory && (allSecrets || unsavedSecrets.contains(account.id()))) {
                    writeSecret(account);
                }
                // Not read (yet, or at all): keep the key it was listed with, so
                // the stored credentials stay where they are.
                String key = credentialsInMemory
                        ? secretKey(account)
                        : listed.get("secretKey").asString(secretKey(account));
                metadata.put("secretKey", key);
            }
            array.add(metadata);
        }
        unsavedSecrets.clear();

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
        pendingSecrets.remove(account.id());
        unreadableSecrets.remove(account.id());
        registerSecrets(account);
        accounts.put(account.id(), account);
        unsavedSecrets.add(account.id());
        if (selectedId == null) {
            selectedId = account.id();
        }
    }

    /** Replaces an account in place, keeping selection - used after a token refresh. */
    public synchronized void update(Account account) {
        pendingSecrets.remove(account.id());
        unreadableSecrets.remove(account.id());
        Account previous = accounts.get(account.id());
        if (previous != null) {
            Redactor.forget(previous.accessToken());
            Redactor.forget(previous.refreshToken());
        }
        registerSecrets(account);
        accounts.put(account.id(), account);
        unsavedSecrets.add(account.id());
    }

    /**
     * Removes an account and its credentials.
     *
     * <p>The credential store entry is deleted first: an account that vanished
     * from the list while its refresh token stayed in the keychain would be a
     * token nothing can ever revoke from inside the launcher.
     */
    public synchronized void remove(Account account) throws IOException {
        Json listed = pendingSecrets.remove(account.id());
        Json unread = unreadableSecrets.remove(account.id());
        if (listed == null) {
            listed = unread;
        }
        accounts.remove(account.id());
        unsavedSecrets.remove(account.id());
        Redactor.forget(account.accessToken());
        Redactor.forget(account.refreshToken());
        if (!account.isOffline()) {
            secrets.delete(listed == null ? secretKey(account)
                    : listed.get("secretKey").asString(secretKey(account)));
        }
        if (account.id().equals(selectedId)) {
            selectedId = accounts.keySet().stream().findFirst().orElse(null);
        }
    }

    /**
     * Makes an account the one the launcher opens with.
     *
     * @return true when this changed the selection, which is when it is worth
     *         {@link #saveSelection() saving}
     */
    public synchronized boolean select(Account account) {
        if (account == null || !accounts.containsKey(account.id())
                || account.id().equals(selectedId)) {
            return false;
        }
        selectedId = account.id();
        return true;
    }

    /**
     * True when a Microsoft account is signed in here with its credentials.
     *
     * <p>{@link MicrosoftAuth} refuses an account that does not own the game,
     * both at sign-in and at every refresh, so such an account is proof of
     * ownership on this machine. An entry whose credentials are missing from
     * the credential store does not count: it could be a line typed into
     * {@code accounts.json}.
     */
    public synchronized boolean hasLicensedAccount() {
        // The answer depends on the credentials being there.
        loadSecrets();
        return accounts.values().stream().anyMatch(account ->
                account.type() == Account.AccountType.MICROSOFT && !account.needsSignIn());
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
            // A locked keychain or a helper that timed out. Null, not "empty":
            // an empty answer would later be saved as "no credentials" and
            // delete the ones still in the store.
            System.err.println("could not read stored credentials for an account: "
                    + Redactor.scrub(String.valueOf(e.getMessage())));
            return null;
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
