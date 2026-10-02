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

package com.hexadron.launcher.ui;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Hands web addresses to the system browser. */
final class WebLinks {

    /** Where a player searches for a mod the launcher could not find. */
    static final String SEARCH = "https://www.google.com/search?q=";

    private WebLinks() {
    }

    /** The web search for these words. */
    static URI search(String query) {
        return URI.create(SEARCH + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    /**
     * Opens an https address in the system browser.
     *
     * <p>Only https: this is the one place that hands a string to the operating
     * system to open, and anything else there could be a program.
     */
    static void open(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(uri);
                return;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Some Linux sessions have no AWT Desktop integration. Fall through.
        }
        try {
            String[] command;
            if (com.hexadron.launcher.util.Platform.isWindows()) {
                command = new String[]{com.hexadron.launcher.util.Platform.systemTool("rundll32.exe"),
                        "url.dll,FileProtocolHandler", uri.toString()};
            } else if (com.hexadron.launcher.util.Platform.isMac()) {
                command = new String[]{com.hexadron.launcher.util.Platform.systemTool("open"), uri.toString()};
            } else {
                command = new String[]{com.hexadron.launcher.util.Platform.systemTool("xdg-open"), uri.toString()};
            }
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            com.hexadron.launcher.core.LauncherLog.warn("Could not open %s: %s", uri, e);
        }
    }
}
