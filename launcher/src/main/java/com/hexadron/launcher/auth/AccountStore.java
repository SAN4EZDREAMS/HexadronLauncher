package com.hexadron.launcher.auth;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persists accounts to {@code accounts.json}.
 *
 * <p><b>On token storage.</b> Refresh tokens are written in plain text, with the
 * file restricted to the owner (mode 600 on POSIX). They are not encrypted, and
 * this class does not pretend otherwise: a desktop launcher with no OS keychain
 * integration has nowhere to keep a key that an attacker with read access to the
 * user's home directory could not also read, so "encryption" there would be
 * obfuscation with a false sense of safety. Anyone who can read this file could
 * equally read a key stored beside it.
 *
 * <p>Wiring this to the platform credential stores - DPAPI on Windows, Keychain
 * on macOS, Secret Service on Linux - is the honest fix and is tracked as future
 * work. Until then, a user who does not want a refresh token on disk can decline
 * to save the account and sign in each session.
 */
public final class AccountStore {

    private final Path file;
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private String selectedId;

    public AccountStore(GameDirs dirs) {
        this.file = dirs.accountsFile();
    }

    public synchronized AccountStore load() throws IOException {
        accounts.clear();
        selectedId = null;
        if (!Files.isRegularFile(file)) {
            return this;
        }
        Json root = Json.read(file);
        for (Json entry : root.get("accounts").elements()) {
            try {
                Account account = Account.fromJson(entry);
                accounts.put(account.id(), account);
            } catch (RuntimeException e) {
                // One corrupt entry must not lock the user out of every account.
                System.err.println("skipping unreadable account entry: " + e.getMessage());
            }
        }
        selectedId = root.get("selected").asString(null);
        if (selectedId != null && !accounts.containsKey(selectedId)) {
            selectedId = null;
        }
        return this;
    }

    public synchronized void save() throws IOException {
        Json array = Json.array();
        accounts.values().forEach(account -> array.add(account.toJson()));

        Json root = Json.object().put("accounts", array);
        if (selectedId != null) {
            root.put("selected", selectedId);
        }
        root.write(file);
        restrictPermissions(file);
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
        accounts.put(account.id(), account);
        if (selectedId == null) {
            selectedId = account.id();
        }
    }

    /** Replaces an account in place, keeping selection - used after a token refresh. */
    public synchronized void update(Account account) {
        accounts.put(account.id(), account);
    }

    public synchronized void remove(Account account) {
        accounts.remove(account.id());
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

    /** Owner-read/write only, where the filesystem supports it. */
    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some network filesystems: nothing portable to do here.
        }
    }
}
