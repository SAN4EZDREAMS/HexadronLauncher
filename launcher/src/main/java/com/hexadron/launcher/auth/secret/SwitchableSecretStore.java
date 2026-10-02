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

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;

/**
 * A credential store that can be swapped for another while the launcher runs,
 * taking the credentials with it.
 *
 * <p>Everything that holds the launcher's store - the account list, the
 * CurseForge key, the proxy password - holds this one, so a switch made in the
 * settings window reaches all of them at once. Before, the choice was read at
 * start-up only, and the next start opened a store that had none of the
 * credentials in it: every Microsoft account signed out, the CurseForge key
 * and the proxy password gone, with nothing to say why.
 */
public final class SwitchableSecretStore implements SecretStore {

    private volatile SecretStore delegate;

    public SwitchableSecretStore(SecretStore initial) {
        this.delegate = initial;
    }

    /**
     * Copies these keys from the store in use to the next one, then uses it.
     *
     * <p>All or nothing: a key that cannot be read or written stops the switch
     * before it happens, and the store in use stays the one in use. Copies
     * already made in the next store are harmless there. The old copies are
     * left where they were: the encrypted file can be the fallback half of the
     * system store, so clearing "the old store" could clear the new one.
     *
     * @return how many credentials were copied
     */
    public synchronized int switchTo(SecretStore next, Collection<String> keys) throws IOException {
        SecretStore current = delegate;
        int copied = 0;
        for (String key : keys) {
            Optional<String> value = current.load(key);
            if (value.isPresent()) {
                next.store(key, value.get());
                copied++;
            }
        }
        delegate = next;
        return copied;
    }

    /** The store this one passes everything to now. */
    public SecretStore current() {
        return delegate;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String displayName() {
        return delegate.displayName();
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public boolean isOsProtected() {
        return delegate.isOsProtected();
    }

    @Override
    public void store(String key, String value) throws IOException {
        delegate.store(key, value);
    }

    @Override
    public Optional<String> load(String key) throws IOException {
        return delegate.load(key);
    }

    @Override
    public void delete(String key) throws IOException {
        delegate.delete(key);
    }
}
