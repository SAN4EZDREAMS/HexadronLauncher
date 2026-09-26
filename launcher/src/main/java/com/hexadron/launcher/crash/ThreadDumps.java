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

package com.hexadron.launcher.crash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The launcher's side of the thread-dump agent ({@code com.hexadron.wrapper.ThreadDumpAgent},
 * in the launch wrapper jar).
 *
 * <p>The agent runs inside the game and watches for {@link #REQUEST} in the
 * game folder; it answers with {@link #DUMP}. The names are repeated here
 * because the launcher does not load the wrapper's classes, and the self-check
 * compares them with the jar.
 */
public final class ThreadDumps {

    public static final String REQUEST = "hexadron-threads.request";
    public static final String DUMP = "hexadron-threads.txt";

    /** Silence after which the launcher asks for the game's threads. */
    public static final long ASK_AFTER_MILLIS = 30_000;

    private ThreadDumps() {
    }

    /** Asks a running game for its threads. The answer arrives within about a second. */
    public static void request(Path gameDir) throws IOException {
        Files.writeString(gameDir.resolve(REQUEST), "");
    }

    /** Removes an unanswered request, so the next game does not answer it at start. */
    public static void clear(Path gameDir) {
        try {
            Files.deleteIfExists(gameDir.resolve(REQUEST));
        } catch (IOException ignored) {
            // A stale request costs one dump at the next start; not worth a failure.
        }
    }
}
