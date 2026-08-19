package com.hexadron.launcher.auth.secret;

import java.io.IOException;
import java.util.Optional;

/**
 * Where a credential lives when the launcher is not running.
 *
 * <p>Implementations are ordered by how much they actually protect:
 *
 * <ol>
 *   <li>{@link DpapiSecretStore}, {@link KeychainSecretStore},
 *       {@link SecretServiceStore} - the operating system holds the key. The
 *       stored bytes are useless on another machine or to another account.</li>
 *   <li>{@link EncryptedFileSecretStore} - the launcher holds the key, next to
 *       the data. This raises the bar against a careless backup or a screen
 *       share; it does not stop anyone who can read the folder. It says so.</li>
 * </ol>
 *
 * <p><b>What none of them stop.</b> Code running as the user - a malicious mod,
 * an infostealer already on the machine - can ask the same operating system for
 * the same secret, and the game is handed a live token at launch regardless.
 * That is the honest limit of at-rest protection on a desktop, and it is why
 * this is one of several measures rather than the measure.
 */
public interface SecretStore {

    /** Short identifier for logs and the settings screen, e.g. {@code "dpapi"}. */
    String id();

    /** Human-readable name for the settings screen. */
    String displayName();

    /**
     * True when this store can be used on this machine right now.
     *
     * <p>Implementations must actually verify - a round trip through the real
     * backend - rather than guess from the operating system name. A Linux
     * desktop with no session keyring and a macOS machine with a locked
     * keychain both look available and are not.
     */
    boolean isAvailable();

    /** True when the operating system, rather than the launcher, holds the key. */
    boolean isOsProtected();

    void store(String key, String value) throws IOException;

    Optional<String> load(String key) throws IOException;

    void delete(String key) throws IOException;
}
