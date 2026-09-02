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

package com.hexadron.wrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Starts Minecraft with the session token delivered over standard input instead
 * of on the command line.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>Minecraft takes its session token as {@code --accessToken <token>}. Process
 * arguments are readable by every process on the machine - {@code ps} and
 * {@code /proc/<pid>/cmdline} on Linux, {@code ps} on macOS,
 * {@code Get-CimInstance Win32_Process} or any WMI query on Windows - so for as
 * long as the game runs, anything running as the user, and on some systems any
 * user at all, can read a live Minecraft session token straight out of the
 * process table. Worse in practice: the JVM writes the full argument list into
 * {@code hs_err_pid*.log} when it crashes, which is exactly the file players
 * upload to support channels. Modrinth's own help pages warn never to share an
 * unedited JVM crash log for this reason, and the common log-paste sites do not
 * strip it.
 *
 * <h2>How it works</h2>
 *
 * <p>The launcher replaces each secret in the argument list with a placeholder,
 * starts this class instead of Minecraft's main class, and then writes the real
 * values to the child's standard input - a pipe that only the two processes
 * share. This class substitutes them back in memory and calls the real main
 * method by reflection, in the same JVM, on the same classpath. Minecraft sees
 * exactly the arguments it expects and cannot tell the difference.
 *
 * <p>Prism Launcher and MultiMC use the same technique, and are the only two
 * launchers of the ones surveyed that keep the token out of the process table.
 *
 * <h2>Protocol</h2>
 *
 * <p>One instruction per line, UTF-8, on standard input:
 * <pre>
 * secret &lt;placeholder&gt; &lt;base64 of the value&gt;
 * launch
 * </pre>
 * Base64 because a token is opaque and must survive a newline-delimited channel
 * unchanged. Anything before {@code launch} that is not understood is ignored,
 * so the protocol can grow without breaking an older wrapper.
 *
 * <h2>Deliberate constraints</h2>
 *
 * <ul>
 *   <li>No dependencies, not even on the rest of the launcher. This class is
 *       built into its own jar and put on the game's classpath; anything else it
 *       pulled in would be a class the game could collide with.</li>
 *   <li>It does not log the values it receives, ever, not even truncated.</li>
 *   <li>It exits with a distinguishable status if the launcher never sends
 *       {@code launch}, so a broken handshake looks like a broken handshake and
 *       not like a Minecraft crash.</li>
 * </ul>
 *
 * <p>Usage: {@code java ... com.hexadron.wrapper.GameLaunchWrapper <realMainClass> <args...>}
 */
public final class GameLaunchWrapper {

    /** Exit status used when the launcher did not complete the handshake. */
    private static final int EXIT_NO_HANDSHAKE = 92;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("[hexadron] no main class given");
            System.exit(EXIT_NO_HANDSHAKE);
            return;
        }

        String mainClassName = args[0];
        String[] gameArguments = new String[args.length - 1];
        System.arraycopy(args, 1, gameArguments, 0, gameArguments.length);

        Map<String, String> secrets = readSecrets();
        if (secrets == null) {
            System.err.println("[hexadron] the launcher did not send the launch parameters");
            System.exit(EXIT_NO_HANDSHAKE);
            return;
        }

        for (int i = 0; i < gameArguments.length; i++) {
            gameArguments[i] = substitute(gameArguments[i], secrets);
        }
        // The map is the last copy the wrapper controls; drop it before handing
        // control to code that could dump the heap.
        secrets.clear();

        Class<?> mainClass = Class.forName(mainClassName, false,
                Thread.currentThread().getContextClassLoader());
        Method main = mainClass.getMethod("main", String[].class);
        main.invoke(null, (Object) gameArguments);
    }

    /** Reads instructions until {@code launch}. Returns null if input ended first. */
    private static Map<String, String> readSecrets() throws IOException {
        Map<String, String> secrets = new HashMap<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.equals("launch")) {
                return secrets;
            }
            if (line.startsWith("secret ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length == 3) {
                    secrets.put(parts[1],
                            new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8));
                }
            }
            // Unknown instructions are ignored on purpose - forward compatibility.
        }
        return null;
    }

    private static String substitute(String argument, Map<String, String> secrets) {
        if (argument == null || argument.indexOf('%') < 0) {
            return argument;
        }
        String result = argument;
        for (Map.Entry<String, String> entry : secrets.entrySet()) {
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private GameLaunchWrapper() {
    }
}
