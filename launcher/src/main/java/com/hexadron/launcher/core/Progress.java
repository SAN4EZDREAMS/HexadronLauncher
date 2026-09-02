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

package com.hexadron.launcher.core;

/**
 * Progress and log sink for long-running operations (install, download, launch).
 *
 * <p>Implementations are called from worker threads. UI implementations must
 * marshal onto their own toolkit thread; {@code Progress} makes no promises
 * about which thread calls it.
 */
public interface Progress {

    /** Discards everything. Useful for tests and headless operation. */
    Progress NOOP = new Progress() {
        @Override
        public void stage(String name) {
        }

        @Override
        public void bytes(long completed, long total) {
        }

        @Override
        public void items(int completed, int total) {
        }

        @Override
        public void log(String message) {
        }
    };

    /** A named phase began, e.g. "Downloading libraries". */
    void stage(String name);

    /** Byte counters for the current stage. {@code total} may be 0 when unknown. */
    void bytes(long completed, long total);

    /** File/item counters for the current stage. */
    void items(int completed, int total);

    /** A human-readable line for the launcher log view. */
    void log(String message);

    /**
     * Formats and logs a line, with credentials removed.
     *
     * <p>The scrub happens here rather than in each implementation because this
     * is the single point every log line in the launcher passes through, and a
     * redaction that has to be remembered at each call site is a redaction that
     * will eventually be forgotten at one of them.
     */
    default void log(String format, Object... args) {
        log(com.hexadron.launcher.util.Redactor.scrub(String.format(format, args)));
    }

    /** Cooperative cancellation. Long loops poll this between units of work. */
    default boolean isCancelled() {
        return false;
    }

    /** Writes to stdout. Convenient for CLI runs and integration testing. */
    static Progress console() {
        return new Progress() {
            @Override
            public void stage(String name) {
                System.out.println("[stage] " + name);
            }

            @Override
            public void bytes(long completed, long total) {
            }

            @Override
            public void items(int completed, int total) {
            }

            @Override
            public void log(String message) {
                System.out.println("[log] " + com.hexadron.launcher.util.Redactor.scrub(message));
            }
        };
    }
}
