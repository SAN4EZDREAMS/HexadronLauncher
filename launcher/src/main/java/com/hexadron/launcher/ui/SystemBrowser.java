package com.hexadron.launcher.ui;

import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Opening a web page in the user's own browser.
 *
 * <p>Two ways, because one of them is not always there: AWT's desktop
 * integration first, and a platform command after it. A bare Linux session
 * frequently has no {@code java.awt.Desktop} at all, and reporting "cannot open
 * links" on a machine where {@code xdg-open} works perfectly is a fault of the
 * launcher rather than of the desktop.
 *
 * <h2>Why the scheme is checked here</h2>
 *
 * <p>Some of the links this opens come out of a mod jar - a {@code homepage}
 * field in a file the launcher did not write and the user did not read.
 * Everything downstream of this class hands its argument to the operating
 * system and asks it to do whatever that string means, and on every desktop
 * there are strings that mean more than "show a page". So only {@code http} and
 * {@code https} get that far; anything else is refused here, where the reason is
 * visible, rather than filtered by whichever component happens to be last.
 */
public final class SystemBrowser {

    private SystemBrowser() {
    }

    /** True when this string is a web page and may be opened. */
    public static boolean isWebPage(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Opens a page.
     *
     * @return false when the string is not a web page, or no way of opening one
     *         could be found - the caller decides whether that is worth saying
     */
    public static boolean open(String url) {
        if (!isWebPage(url)) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(uri);
                return true;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // No desktop integration in this session. Try the command instead.
        }
        try {
            String[] command;
            if (Platform.isWindows()) {
                // Through rundll32 rather than "cmd /c start", which would give
                // the URL to a shell that treats & as a command separator.
                command = new String[]{"rundll32", "url.dll,FileProtocolHandler", uri.toString()};
            } else if (Platform.isMac()) {
                command = new String[]{"open", uri.toString()};
            } else {
                command = new String[]{"xdg-open", uri.toString()};
            }
            new ProcessBuilder(command).start();
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
