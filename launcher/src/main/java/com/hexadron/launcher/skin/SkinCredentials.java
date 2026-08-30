package com.hexadron.launcher.skin;

import com.hexadron.launcher.auth.secret.SecretStore;
import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a skin service sign-in is kept between runs.
 *
 * <h2>The same place the Microsoft tokens go</h2>
 *
 * <p>This is the account credential store, keyed per account. On Windows that
 * means DPAPI holds the key, on macOS the keychain, on a Linux desktop the
 * session keyring; where none of those can be reached it is the launcher's own
 * encrypted file, which says out loud that it is the weaker option. Nothing
 * about a third-party skin token deserves a lesser home than a Mojang one: it
 * is a live credential for an account somebody owns.
 *
 * <h2>Not the password</h2>
 *
 * <p>A password is typed once and exchanged for a token pair, and then it is
 * gone - it is never written anywhere by this launcher. What is kept is the
 * pair, which the service's own website can revoke, and which is useless for
 * changing the password or the e-mail on that account.
 *
 * <h2>Losing it is not an error</h2>
 *
 * <p>{@link #load} answers "no session" when the store cannot be read, rather
 * than failing. The cost of that is one sign-in; the cost of the alternative is
 * a launcher that will not start the game because a keyring is locked.
 */
public final class SkinCredentials {

    /** Key prefix in the credential store, followed by the account id. */
    private static final String PREFIX = "skin-service:";

    private final SecretStore store;

    public SkinCredentials(SecretStore store) {
        this.store = store;
    }

    /** The saved sign-in for this account, if there is a readable one. */
    public Optional<YggdrasilAuth.Session> load(String accountId) {
        if (store == null || accountId == null) {
            return Optional.empty();
        }
        try {
            return store.load(PREFIX + accountId)
                    .map(text -> YggdrasilAuth.Session.fromJson(Json.parse(text)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Writes a sign-in, replacing any earlier one for the same account. */
    public void save(String accountId, YggdrasilAuth.Session session) throws IOException {
        if (store == null) {
            throw new IOException("no credential store is available on this machine");
        }
        store.store(PREFIX + accountId, session.toJson().toString());
    }

    /** Forgets the sign-in for this account. Never throws. */
    public void forget(String accountId) {
        if (store == null || accountId == null) {
            return;
        }
        try {
            store.delete(PREFIX + accountId);
        } catch (IOException | RuntimeException ignored) {
            // Nothing useful to do: the caller is signing out, and a token left
            // in the store is inert once the service has been told to forget it.
        }
    }

    /**
     * A fresh identifier for this installation at one service.
     *
     * <p>Yggdrasil ties a token to a client token, and renewing with a
     * different one invalidates the session everywhere else the same account is
     * signed in. Generating it once per account and keeping it with the token
     * is what stops this launcher from logging the user out of their phone.
     */
    public static String newClientToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
