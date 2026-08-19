package com.hexadron.launcher.auth.secret;

import com.hexadron.launcher.core.GameDirs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Chooses where credentials are kept, best available first.
 *
 * <p>The order is not a preference, it is a security ranking: a store where the
 * operating system holds the key always beats one where the launcher does. The
 * chosen store is reported to the user, because "your refresh token is in the
 * Windows credential system" and "your refresh token is in a file next to its
 * own key" are different promises and should not look the same in the interface.
 */
public final class SecretStores {

    private SecretStores() {
    }

    /**
     * Picks a store for this machine.
     *
     * @param preferFile force the file store, for a user who does not want the
     *                   launcher touching their keychain at all
     */
    public static SecretStore forHost(GameDirs dirs, boolean preferFile) {
        Path secrets = dirs.root().resolve("secrets");
        SecretStore file = new EncryptedFileSecretStore(secrets);
        if (preferFile) {
            return file;
        }
        List<SecretStore> candidates = List.of(
                new DpapiSecretStore(secrets),
                new KeychainSecretStore(),
                new SecretServiceStore());
        for (SecretStore candidate : candidates) {
            if (candidate.isAvailable()) {
                return new FallbackSecretStore(candidate, file);
            }
        }
        return file;
    }

    /**
     * Uses the operating system store, and falls back to the file store for a
     * single operation that fails.
     *
     * <p>A keychain can be available at start-up and locked a minute later, and
     * a Linux session can lose its D-Bus connection. Without this, that turns
     * into "the launcher cannot save your account and will not tell you why".
     * With it, the credential still lands somewhere, and {@link #isOsProtected()}
     * stops claiming a protection that is no longer in force.
     */
    static final class FallbackSecretStore implements SecretStore {

        private final SecretStore primary;
        private final SecretStore secondary;
        private volatile boolean primaryFailed;

        FallbackSecretStore(SecretStore primary, SecretStore secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public String id() {
            return primaryFailed ? secondary.id() : primary.id();
        }

        @Override
        public String displayName() {
            return primaryFailed
                    ? secondary.displayName() + " (fell back from " + primary.displayName() + ")"
                    : primary.displayName();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean isOsProtected() {
            return !primaryFailed && primary.isOsProtected();
        }

        @Override
        public void store(String key, String value) throws IOException {
            try {
                primary.store(key, value);
                secondary.delete(key);
            } catch (IOException e) {
                primaryFailed = true;
                secondary.store(key, value);
            }
        }

        @Override
        public Optional<String> load(String key) throws IOException {
            try {
                Optional<String> value = primary.load(key);
                if (value.isPresent()) {
                    return value;
                }
            } catch (IOException e) {
                primaryFailed = true;
            }
            return secondary.load(key);
        }

        @Override
        public void delete(String key) throws IOException {
            IOException failure = null;
            try {
                primary.delete(key);
            } catch (IOException e) {
                failure = e;
            }
            try {
                secondary.delete(key);
            } catch (IOException e) {
                failure = e;
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
