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

package com.hexadron.launcher.skin;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;

import java.io.IOException;
import java.util.List;

/**
 * The skin arrangement for one run of the game.
 *
 * <p>Everything the launch path has to know about skins is behind this: who the
 * game is started as, what to add to the command line, and when to let go. A
 * profile with nothing to show produces {@link #none()}, whose argument list is
 * empty and whose close does nothing - so a launch that wants no skins is
 * exactly the launch it was before any of this existed, down to the command
 * line.
 *
 * <h2>Two halves, and why they are separate calls</h2>
 *
 * <p>{@link #identity} answers "who is playing", and {@link #open} answers
 * "where does the game look for textures". They are apart because the first
 * changes the account the rest of the launch is built from - the token that
 * has to be kept off the command line, the name written into the world - and
 * that has to be settled before the command is assembled, while the second
 * opens a socket that must not be left listening if anything after it fails.
 *
 * <h2>Failure is not fatal</h2>
 *
 * <p>Nothing here can stop a game from starting. The agent cannot be fetched,
 * the loopback port cannot be bound, the service will not renew a token: each
 * is logged and the launch continues without skins, as the account that was
 * already selected. Somebody pressing Play wants to play, and a launcher that
 * refuses because a cosmetic service did not come up has misjudged what it was
 * asked to do.
 */
public final class SkinSession implements AutoCloseable {

    private static final SkinSession NONE = new SkinSession(null, List.of());

    private final LocalSkinService service;
    private final List<String> arguments;

    private SkinSession(LocalSkinService service, List<String> arguments) {
        this.service = service;
        this.arguments = arguments;
    }

    /** No skin service: nothing added to the command, nothing to close. */
    public static SkinSession none() {
        return NONE;
    }

    /** JVM arguments the launch has to carry. Empty when there are none. */
    public List<String> arguments() {
        return arguments;
    }

    /** True when a skin service was actually attached. */
    public boolean isActive() {
        return !arguments.isEmpty();
    }

    /**
     * Who the game should be started as.
     *
     * <h2>Why the identity changes at all</h2>
     *
     * <p>A skin service is asked about a player by UUID, and answers about the
     * accounts it knows. An offline account's UUID is derived from a name on
     * this machine and has never been near the service, so pointing the game at
     * one while playing as that account produces a launch where every lookup
     * correctly answers "no such profile" and nothing appears - which is
     * indistinguishable, from the outside, from a broken feature.
     *
     * <p>So a remote service means playing as the account on that service: its
     * UUID, its name, its token. That is also the thing that makes the skin
     * visible to other players - on a server pointed at the same service, that
     * UUID is who you are to everybody on it.
     *
     * @return the account to launch as, which is the one passed in whenever
     *         there is no remote service or no usable sign-in for it
     */
    public static Account identity(Account account, SkinProfile profile,
                                   SkinCredentials credentials, Progress progress) {

        if (account == null || profile == null || credentials == null
                || profile.source() != SkinProfile.Source.REMOTE
                || profile.service().isBlank()) {
            return account;
        }

        YggdrasilAuth.Session saved =
                credentials.load(account.id(), profile.service()).orElse(null);
        if (saved == null) {
            // Worth saying plainly. The alternative is a launch that looks
            // successful and a skin that never appears, with nothing in the log
            // pointing at the reason.
            progress.log("Skins: not signed in to %s, so the game starts as %s and that service"
                            + " has never heard of this profile. Sign in from the account window.",
                    profile.service(), account.username());
            return account;
        }

        try {
            YggdrasilAuth.Session live = saved;
            if (!YggdrasilAuth.validate(saved)) {
                live = YggdrasilAuth.refresh(saved);
                credentials.save(account.id(), live);
            }
            progress.log("Skins: playing as %s on %s", live.name(), live.root());
            return new Account(Account.AccountType.MICROSOFT, live.name(), live.uuid(),
                    live.accessToken(), null, Long.MAX_VALUE, "0");

        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            progress.log("Skins: %s would not renew the sign-in (%s). The game starts as %s,"
                            + " without skins from that service.",
                    profile.service(), YggdrasilAuth.describe(e), account.username());
            return account;
        }
    }

    /**
     * Opens whatever this profile asks for.
     *
     * @param profile the account's skin settings, read before the identity was
     *                resolved - the settings belong to the account that was
     *                selected, not to the one the game ends up being launched as
     * @param player  the account the game is actually started as, which for a
     *                local service is whose UUID the loopback service answers
     *                about
     * @return a session that is always safe to use and always safe to close
     */
    public static SkinSession open(SkinProfile profile, Account player, SkinStore store,
                                   GameDirs dirs, Progress progress) {
        if (profile == null || player == null || store == null || !profile.needsService()) {
            return none();
        }

        try {
            java.nio.file.Path jar = AuthlibInjector.ensure(dirs, progress);

            if (profile.source() == SkinProfile.Source.REMOTE) {
                // Nothing runs here: the service is somebody else's, and the
                // agent talks to it directly. This is the arrangement in which
                // other players see the skin, because their client resolves it
                // at the same address.
                progress.log("Skins: using %s", profile.service());
                return new SkinSession(null,
                        AuthlibInjector.arguments(jar, profile.service(), null));
            }

            LocalSkinService local = LocalSkinService.start(
                    player.uuid(), player.username(), profile, store,
                    dirs.skins().resolve("signing-key.json"));
            progress.log("Skins: serving this account's skin from %s", local.root());
            return new SkinSession(local,
                    AuthlibInjector.arguments(jar, local.root(), local.prefetchedMetadata()));

        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            progress.log("Skins are not available this launch (%s). The game starts without them.",
                    e.getMessage());
            return none();
        }
    }

    @Override
    public void close() {
        if (service != null) {
            service.close();
        }
    }
}
