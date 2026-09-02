/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.ui;

import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * Puts the launcher in the notification area while the game runs.
 *
 * <p>Why the window is hidden rather than minimised: a minimised launcher is
 * still a taskbar entry the player alt-tabs through while playing. Hiding it
 * leaves one entry - the game - and the launcher comes back by itself when the
 * game ends, so nothing has to be found and restored by hand.
 *
 * <p>JavaFX has no tray API, so this uses AWT's {@link SystemTray}. That brings
 * two rules, and breaking either one is what usually makes tray code flaky:
 *
 * <ul>
 *   <li>AWT calls belong on the AWT event thread, JavaFX calls on the JavaFX
 *       application thread. Every method here hops to the right one.</li>
 *   <li>Hiding the only window would normally end a JavaFX application. While
 *       the icon is up, implicit exit is switched off and switched back on when
 *       the window returns - otherwise the launcher would quit the moment it
 *       went to the tray.</li>
 * </ul>
 *
 * <p>Where there is no tray - a headless session, or a desktop without a
 * notification area - {@link #isSupported()} is false and the caller falls back
 * to minimising. Nothing here fails loudly for the want of a tray.
 */
public final class TrayIntegration {

    private final Stage stage;

    /** Non-null exactly while the launcher is hidden in the tray. */
    private volatile TrayIcon icon;

    public TrayIntegration(Stage stage) {
        this.stage = stage;
    }

    public static boolean isSupported() {
        try {
            return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        } catch (Throwable e) {
            // A missing or broken AWT tray must not stop the launcher.
            return false;
        }
    }

    public boolean isHidden() {
        return icon != null;
    }

    /**
     * Hides the window and shows a tray icon.
     *
     * @param tooltip  hover text, e.g. "Minecraft is running"
     * @param showText menu entry that brings the launcher back
     * @param stopText menu entry that stops the game
     * @param onStop   invoked on the JavaFX thread when the player chooses stop
     * @return false when there is no tray; the window is then left untouched
     */
    public boolean hide(String tooltip, String showText, String stopText, Runnable onStop) {
        if (!isSupported() || isHidden()) {
            return isHidden();
        }
        Platform.setImplicitExit(false);

        java.awt.EventQueue.invokeLater(() -> {
            try {
                SystemTray tray = SystemTray.getSystemTray();
                TrayIcon trayIcon = new TrayIcon(iconImage(tray), tooltip);
                trayIcon.setImageAutoSize(true);

                PopupMenu menu = new PopupMenu();
                MenuItem show = new MenuItem(showText);
                show.addActionListener(event -> restore());
                MenuItem stop = new MenuItem(stopText);
                stop.addActionListener(event -> Platform.runLater(onStop));
                menu.add(show);
                menu.add(stop);
                trayIcon.setPopupMenu(menu);

                // Double-click is what people try first; the menu is the discoverable path.
                trayIcon.addActionListener(event -> restore());

                tray.add(trayIcon);
                icon = trayIcon;
                Platform.runLater(stage::hide);
            } catch (AWTException | RuntimeException e) {
                // The tray refused the icon. Leave the window where it is rather
                // than hiding a window that nothing could bring back.
                Platform.setImplicitExit(true);
                icon = null;
            }
        });
        return true;
    }

    /** Brings the window back and removes the icon. Safe to call when not hidden. */
    public void restore() {
        TrayIcon current = icon;
        icon = null;
        if (current != null) {
            java.awt.EventQueue.invokeLater(() -> {
                try {
                    SystemTray.getSystemTray().remove(current);
                } catch (RuntimeException ignored) {
                    // Already gone; nothing to undo.
                }
            });
        }
        Platform.runLater(() -> {
            Platform.setImplicitExit(true);
            if (!stage.isShowing()) {
                stage.show();
            }
            stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
        });
    }

    /** Removes the icon without touching the window. For shutdown. */
    public void dispose() {
        TrayIcon current = icon;
        icon = null;
        if (current == null) {
            return;
        }
        java.awt.EventQueue.invokeLater(() -> {
            try {
                SystemTray.getSystemTray().remove(current);
            } catch (RuntimeException ignored) {
                // Nothing to remove.
            }
        });
    }

    /** Shows a balloon message, when the platform has one. */
    public void notify(String caption, String text) {
        TrayIcon current = icon;
        if (current == null) {
            return;
        }
        java.awt.EventQueue.invokeLater(() -> {
            try {
                current.displayMessage(caption, text, TrayIcon.MessageType.INFO);
            } catch (RuntimeException ignored) {
                // Balloons are optional on some desktops.
            }
        });
    }

    /**
     * Draws the icon instead of shipping a PNG.
     *
     * <p>Tray icon sizes differ per platform and per display scale; a single
     * bitmap is either blurry or wrong somewhere. Drawing it at the size the
     * tray asks for is both sharp everywhere and one less binary asset.
     */
    private static java.awt.Image iconImage(SystemTray tray) {
        int size = Math.max(16, (int) tray.getTrayIconSize().getWidth());
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(new Color(0x2D, 0x7D, 0x46));
            int radius = Math.max(4, size / 4);
            g.fillRoundRect(0, 0, size, size, radius, radius);

            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(9, (int) (size * 0.68))));
            var metrics = g.getFontMetrics();
            String letter = "H";
            int x = (size - metrics.stringWidth(letter)) / 2;
            int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(letter, x, y);
        } finally {
            g.dispose();
        }
        return image;
    }

    // The window icon used to be drawn here too, with AWT, and it is now in
    // Brand: doing it with Graphics2D pulled Java2D and the platform font
    // manager into start-up for the sake of one 64-pixel image. The tray icon
    // above stays with AWT because SystemTray is an AWT API and JavaFX has no
    // equivalent - but that code runs when the game starts, not when the
    // launcher does.
}
