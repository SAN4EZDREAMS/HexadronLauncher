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

import com.hexadron.launcher.util.Redactor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared plumbing for the stores that talk to an operating-system helper
 * ({@code security} on macOS, {@code secret-tool} on Linux, {@code powershell}
 * on Windows).
 *
 * <p><b>Secrets go in on standard input, never in the argument list.</b> Process
 * arguments are world-readable on all three platforms - {@code ps},
 * {@code /proc/<pid>/cmdline}, {@code Get-CimInstance Win32_Process} - so
 * {@code security add-generic-password -w <password>} would hand the credential
 * to every other process on the machine for as long as the helper runs. Writing
 * it to the helper's stdin keeps it in a pipe that only the two processes share.
 */
abstract class ProcessSecretStore implements SecretStore {

    /** Long enough for a keychain unlock prompt, short enough not to hang a launch. */
    private static final int TIMEOUT_SECONDS = 30;

    /** Result of running a helper: exit status plus its captured output. */
    record Result(int exitCode, String stdout, String stderr) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * Runs {@code command}, writing {@code stdin} to it and capturing its output.
     *
     * @param stdin bytes for the helper's standard input, or null for none
     */
    static Result run(List<String> command, byte[] stdin) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process = builder.start();

        try (OutputStream out = process.getOutputStream()) {
            if (stdin != null) {
                out.write(stdin);
                out.flush();
            }
        } catch (IOException closedEarly) {
            // A helper that read what it needed and exited closes the pipe. Not an error.
        }

        String stdout;
        String stderr;
        try (InputStream outStream = process.getInputStream();
             InputStream errStream = process.getErrorStream()) {
            stdout = new String(outStream.readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        int exit;
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.get(0) + " did not finish within "
                        + TIMEOUT_SECONDS + " seconds");
            }
            exit = process.exitValue();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + command.get(0), e);
        }
        return new Result(exit, stdout, Redactor.scrub(stderr));
    }

    /** True when {@code executable} exists on PATH and answers a harmless probe. */
    static boolean probe(List<String> command) {
        try {
            return run(command, null).exitCode() != 127;
        } catch (IOException e) {
            return false;
        }
    }
}
