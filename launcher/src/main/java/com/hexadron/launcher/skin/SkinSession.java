package com.hexadron.launcher.skin;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;

import java.io.IOException;
import java.util.List;

/**
 * The skin arrangement for one run of the game.
 *
 * <p>Everything the launch path has to know about skins is behind this: what to
 * add to the command line, and when to let go. A profile with nothing to show
 * produces {@link #none()}, whose argument list is empty and whose close does
 * nothing - so a launch that wants no skins is exactly the launch it was before
 * any of this existed, down to the command line.
 *
 * <h2>Failure is not fatal</h2>
 *
 * <p>Nothing here can stop a game from starting. The agent cannot be fetched,
 * the loopback port cannot be bound, the key cannot be written: each is logged
 * and the launch continues without skins. Somebody pressing Play wants to play,
 * and a launcher that refuses because a cosmetic service did not come up has
 * misjudged what it was asked to do.
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
     * Opens whatever this account's skin settings ask for.
     *
     * @return a session that is always safe to use and always safe to close
     */
    public static SkinSession open(Account account, SkinStore store, GameDirs dirs,
                                   Progress progress) {
        if (account == null || store == null) {
            return none();
        }
        SkinProfile profile = store.of(account.id());
        if (!profile.needsService()) {
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
                    account.uuid(), account.username(), profile, store,
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
