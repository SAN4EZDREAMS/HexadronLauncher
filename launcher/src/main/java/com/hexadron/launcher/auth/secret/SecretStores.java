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
     * Picks a store for this machine, without doing the work yet.
     *
     * <p>Deliberately lazy, and the reason is measurable. Choosing a store means
     * asking each candidate whether it works, and on Windows that answer costs
     * two {@code powershell.exe} launches - a full DPAPI protect/unprotect round
     * trip, because anything cheaper would pass on a machine where PowerShell
     * exists but the assembly will not load. A cold PowerShell start is the
     * slowest thing the launcher does that is not a download.
     *
     * <p>It used to happen in the {@code LauncherService} constructor, on the
     * interface thread, before the window existed - so every start paid for it,
     * including the majority of starts that never touch a credential at all. An
     * offline account has no secret to read and no secret to write.
     *
     * <p>So the probe now happens on first use and not before. A launcher opened
     * to play an offline profile never runs it; one opened to sign in to
     * Microsoft pays it once, inside a flow that is already talking to a server.
     *
     * @param preferFile force the file store, for a user who does not want the
     *                   launcher touching their keychain at all
     */
    public static SecretStore forHost(GameDirs dirs, boolean preferFile) {
        Path secrets = dirs.root().resolve("secrets");
        if (preferFile) {
            // Nothing to probe: the user already said which store they want.
            return new EncryptedFileSecretStore(secrets);
        }
        return new LazySecretStore(secrets);
    }

    /** Does the probing that {@link #forHost} defers. */
    private static SecretStore resolve(Path secrets) {
        SecretStore file = new EncryptedFileSecretStore(secrets);
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
     * Resolves the real store on first use.
     *
     * <p>Every method delegates, so from the outside this is indistinguishable
     * from the store it stands in for - except in when it costs anything.
     *
     * <p>{@link #warmUp()} exists for the one caller that knows a credential is
     * about to be needed and would rather pay on a background thread than in the
     * middle of a sign-in.
     */
    static final class LazySecretStore implements SecretStore {

        private final Path secrets;
        private volatile SecretStore delegate;

        LazySecretStore(Path secrets) {
            this.secrets = secrets;
        }

        private SecretStore delegate() {
            SecretStore current = delegate;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                if (delegate == null) {
                    delegate = resolve(secrets);
                }
                return delegate;
            }
        }

        /** Resolves now. Safe to call from anywhere, including twice. */
        void warmUp() {
            delegate();
        }

        /** Whether the choice has already been made. */
        boolean isResolved() {
            return delegate != null;
        }

        @Override
        public String id() {
            return delegate().id();
        }

        @Override
        public String displayName() {
            return delegate().displayName();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean isOsProtected() {
            return delegate().isOsProtected();
        }

        @Override
        public void store(String key, String value) throws IOException {
            delegate().store(key, value);
        }

        @Override
        public Optional<String> load(String key) throws IOException {
            return delegate().load(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate().delete(key);
        }
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
