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
 * <h2>One sign-in per service, not per account</h2>
 *
 * <p>The address field can be pointed at LittleSkin today and a self-hosted
 * Blessing Skin tomorrow, and each of those is a different account somewhere
 * else. Keying only by account would mean the second sign-in quietly replaced
 * the first, and switching back would look like being logged out. So the
 * service is part of the key, and every service the user has signed in to keeps
 * its own token pair.
 *
 * <h2>Losing it is not an error</h2>
 *
 * <p>{@link #load} answers "no session" when the store cannot be read, rather
 * than failing. The cost of that is one sign-in; the cost of the alternative is
 * a launcher that will not start the game because a keyring is locked.
 */
public final class SkinCredentials {

    /** Key prefix in the credential store, followed by account and service. */
    private static final String PREFIX = "skin-service:";

    private final SecretStore store;

    public SkinCredentials(SecretStore store) {
        this.store = store;
    }

    /**
     * The saved sign-in for this account at this service, if there is one.
     *
     * @param service the address as it is currently configured; a sign-in
     *                issued by any other service is not an answer to this
     */
    public Optional<YggdrasilAuth.Session> load(String accountId, String service) {
        String root = YggdrasilAuth.normalise(service);
        if (store == null || accountId == null || root.isEmpty()) {
            return Optional.empty();
        }

        Optional<YggdrasilAuth.Session> found = read(key(accountId, root));
        if (found.isEmpty()) {
            // Written by the first version of this, which kept one sign-in per
            // account. Moved rather than ignored: the alternative is telling
            // somebody who signed in yesterday to do it again for no reason
            // they can see.
            found = read(PREFIX + accountId).filter(session -> session.isFor(root));
            found.ifPresent(session -> {
                try {
                    save(accountId, session);
                    store.delete(PREFIX + accountId);
                } catch (IOException | RuntimeException ignored) {
                    // The old key still reads. Nothing is lost by leaving it.
                }
            });
        }
        return found.filter(session -> session.isFor(root));
    }

    /** Writes a sign-in, replacing any earlier one for the same service. */
    public void save(String accountId, YggdrasilAuth.Session session) throws IOException {
        if (store == null) {
            throw new IOException("no credential store is available on this machine");
        }
        store.store(key(accountId, session.root()), session.toJson().toString());
    }

    /** Forgets this account's sign-in at one service. Never throws. */
    public void forget(String accountId, String service) {
        String root = YggdrasilAuth.normalise(service);
        if (store == null || accountId == null || root.isEmpty()) {
            return;
        }
        try {
            store.delete(key(accountId, root));
            store.delete(PREFIX + accountId);
        } catch (IOException | RuntimeException ignored) {
            // Nothing useful to do: the caller is signing out, and a token left
            // in the store is inert once the service has been told to forget it.
        }
    }

    private Optional<YggdrasilAuth.Session> read(String key) {
        try {
            return store.load(key)
                    .map(text -> YggdrasilAuth.Session.fromJson(Json.parse(text)))
                    .filter(session -> session != null)
                    // A token read off disk is as much a secret as one just
                    // issued, and this is the moment it enters the process.
                    .map(YggdrasilAuth::register);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The store key one sign-in is written under. */
    public static String key(String accountId, String service) {
        return PREFIX + accountId + ":" + YggdrasilAuth.normalise(service);
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
