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

import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Showing a file or a folder in the user's own file manager.
 *
 * <p>{@link SystemBrowser} opens web pages and refuses everything else, which
 * is the right rule for a string that came out of a mod jar. This is the other
 * half: paths the launcher itself computed, handed to the desktop so the user
 * can get at a file without being told where to look for it.
 *
 * <h2>Revealing, not opening</h2>
 *
 * <p>{@link #reveal} asks for the containing folder with the file selected in
 * it, and that is deliberately not {@code Desktop.open(file)}: opening
 * {@code launcher.log} hands it to whatever the system has registered for
 * {@code .log}, which is a text editor on one machine, nothing at all on the
 * next, and on a third an application the user has to dismiss before they can
 * do the thing they actually wanted - attach the file to a report. The folder
 * with the file highlighted is what "here it is" means.
 *
 * <p>Only Windows and macOS have a select-this-file command. Elsewhere the
 * folder is opened and the user reads the name off the row, which is one glance
 * more than the other two and still better than a path in a message box.
 *
 * <h2>Why AWT first and a command second</h2>
 *
 * <p>The same reason as in {@link SystemBrowser}: a bare Linux session
 * frequently has no {@code java.awt.Desktop} at all, and reporting "cannot open
 * folders" on a machine where {@code xdg-open} works is a fault of the launcher
 * rather than of the desktop.
 */
public final class SystemFiles {

    private SystemFiles() {
    }

    /**
     * Shows a file in the file manager, selected if the platform can do that.
     *
     * <p>Falls back to the containing folder when the file is not there - a log
     * that was rotated away between the window being drawn and the link being
     * clicked should still land the user in the right place.
     *
     * @return false when nothing could be opened, which the caller may want to
     *         say out loud rather than swallow
     */
    public static boolean reveal(Path file) {
        if (file == null) {
            return false;
        }
        Path absolute = file.toAbsolutePath();
        Path folder = absolute.getParent();

        if (Files.isRegularFile(absolute)) {
            try {
                if (Platform.isWindows()) {
                    // No shell in between: /select, takes the path as one
                    // argument, and a path with a space in it is exactly what a
                    // shell would split in half.
                    new ProcessBuilder("explorer.exe", "/select," + absolute).start();
                    return true;
                }
                if (Platform.isMac()) {
                    new ProcessBuilder("open", "-R", absolute.toString()).start();
                    return true;
                }
            } catch (IOException | SecurityException ignored) {
                // Fall through to opening the folder, which is the same answer
                // one detail less precise.
            }
        }
        return openFolder(folder != null ? folder : absolute);
    }

    /**
     * Opens a folder.
     *
     * @return false when the folder does not exist and could not be created, or
     *         when no way of opening one could be found
     */
    public static boolean openFolder(Path folder) {
        if (folder == null) {
            return false;
        }
        Path absolute = folder.toAbsolutePath();
        try {
            Files.createDirectories(absolute);
        } catch (IOException | SecurityException e) {
            // It may still exist and merely be unwritable, so this is not a
            // reason to stop - only a reason not to insist it was created.
            if (!Files.isDirectory(absolute)) {
                return false;
            }
        }

        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(absolute.toFile());
                return true;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // No desktop integration in this session. Try the command instead.
        }

        try {
            String[] command;
            if (Platform.isWindows()) {
                command = new String[]{"explorer.exe", absolute.toString()};
            } else if (Platform.isMac()) {
                command = new String[]{"open", absolute.toString()};
            } else {
                command = new String[]{"xdg-open", absolute.toString()};
            }
            new ProcessBuilder(command).start();
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /**
     * The newest launcher log in a folder.
     *
     * <p>By modification time rather than by name. The names are
     * {@code launcher.log} and {@code launcher-1.log} upwards, where the current
     * run is the unnumbered one and the numbers count backwards - a reading
     * nobody should be asked to do, and one that is wrong anyway on the run
     * where logging never started and the newest file on disk is
     * {@code launcher-1.log}.
     *
     * @return the newest file, or null when the folder holds none
     */
    public static Path newestLog(Path logsFolder) {
        if (logsFolder == null || !Files.isDirectory(logsFolder)) {
            return null;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(logsFolder)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString()
                                .toLowerCase(java.util.Locale.ROOT);
                        return name.startsWith("launcher") && name.endsWith(".log");
                    })
                    .max(java.util.Comparator.comparingLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (IOException e) {
                            return Long.MIN_VALUE;
                        }
                    }))
                    .orElse(null);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
