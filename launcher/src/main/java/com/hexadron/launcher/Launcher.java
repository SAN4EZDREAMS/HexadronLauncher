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

package com.hexadron.launcher;

import com.hexadron.launcher.cli.HexadronCli;
import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.i18n.Language;
import com.hexadron.launcher.ui.Brand;
import com.hexadron.launcher.ui.MainWindow;
import com.hexadron.launcher.ui.SplashScreen;
import com.hexadron.launcher.ui.Theme;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

/**
 * Application entry point.
 *
 * <p>With no arguments it opens the window. With arguments it hands off to
 * {@link HexadronCli}, so one artifact serves both the desktop and headless use.
 *
 * <h2>What start-up does, and in which order</h2>
 *
 * <p>The rule here is that nothing slow happens on the JavaFX application
 * thread. That thread draws the interface; every millisecond spent on it is a
 * millisecond with nothing on screen. So {@code start} does the least it can -
 * pick a language, put the splash up - and hands the rest to a worker.
 *
 * <p>Reading settings, profiles and accounts then happens off the interface
 * thread, reporting each stage to {@link SplashScreen}. Only the two things that
 * genuinely must be on the interface thread come back to it: building the window
 * and showing it.
 *
 * <p>This was not always so. The previous arrangement built the whole service
 * inside {@code start}, and the credential store alone cost two
 * {@code powershell.exe} launches there - see {@code SecretStores}. Between the
 * double-click and the first pixel there was nothing at all.
 */
public final class Launcher extends Application {

    private MainWindow window;
    private SplashScreen splash;

    /**
     * A newer launcher, found while the start-up screen was up.
     *
     * <p>Offered after the window is on screen rather than during the splash: a
     * question about replacing the program belongs over the program, and the
     * splash is a progress report that a dialog must not be modal to.
     */
    private com.hexadron.launcher.update.Updates.Available pendingUpdate;

    public static void main(String[] args) {
        if (args.length > 0) {
            HexadronCli.main(args);
            return;
        }
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // The language is picked before anything can fail, so that even the
        // startup-failure dialog is readable. With no stored preference this
        // follows the operating system.
        I18n.use(Language.resolve(""));

        // Before anything that can fail. A startup failure is exactly the case
        // where there is no window to report it in, and until this existed the
        // only record of one was whatever the user managed to screenshot.
        try {
            com.hexadron.launcher.core.GameDirs dirs =
                    com.hexadron.launcher.core.GameDirs.defaultDirs();
            com.hexadron.launcher.core.LauncherLog.open(dirs);
            com.hexadron.launcher.core.LauncherLog.header(BuildConfig.version(), dirs);
        } catch (Throwable ignored) {
            // No log, then. Not a reason to refuse to start.
        }
        com.hexadron.launcher.core.LauncherLog.catchUncaught();

        // While the splash is the only window on screen, an implicit exit would
        // end the application in the gap between closing it and showing the
        // main window. Switched back on once the window is up.
        Platform.setImplicitExit(false);

        if (!SplashScreen.isDisabled()) {
            splash = new SplashScreen(I18n.t("splash.version", BuildConfig.version()),
                    LauncherService.STARTUP_STEPS.size() + 1);
            splash.show();
        }

        Thread startup = new Thread(() -> {
            LauncherService service;
            try {
                service = LauncherService.createDefault(this::reportStep);
            } catch (Throwable e) {
                // Throwable, not Exception. Implicit exit is off while the splash
                // is the only window, so an Error escaping here would leave a
                // spinning splash and no way out of it - which is a worse
                // failure than the one that caused it.
                com.hexadron.launcher.core.LauncherLog.error("Startup failed", e);
                Platform.runLater(() -> failed(e));
                return;
            }
            // After the service, because it is the settings that say whether to
            // look at all and on which channel; before the window, because the
            // answer is wanted the moment the window is up.
            pendingUpdate = lookForUpdate(service);
            Platform.runLater(() -> open(stage, service));
        }, "hexadron-startup");
        startup.setDaemon(true);
        startup.start();
    }

    /**
     * Asks the repository whether there is a newer launcher.
     *
     * <p>Every failure here is a shrug. A machine with no connection, a
     * repository that is down, a rate limit that has been reached - none of them
     * is something the person opening a launcher can act on, and none of them is
     * a reason to hold up the start or to put a dialog in front of it. What they
     * all have in common is that the launcher they already have still works.
     *
     * <p>The leftovers of a previous update are cleared here too, for the one
     * reason they cannot be cleared by the updater itself: that process is
     * running out of the folder it would be deleting.
     */
    private com.hexadron.launcher.update.Updates.Available lookForUpdate(LauncherService service) {
        try {
            com.hexadron.launcher.update.UpdateInstall.detect()
                    .ifPresent(com.hexadron.launcher.update.Updates::cleanUp);
        } catch (Throwable ignored) {
            // A folder that will not go is not a reason to fail a start-up.
        }
        if (!service.settings().checkForUpdates()) {
            return null;
        }
        reportStep("updates");
        try {
            return com.hexadron.launcher.update.Updates.check(
                    BuildConfig.version(),
                    service.settings().updateChannel(),
                    new com.hexadron.launcher.update.ReleaseFeed(),
                    com.hexadron.launcher.util.Platform.os()).orElse(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable e) {
            com.hexadron.launcher.core.LauncherLog.info("Update check skipped: " + e);
            return null;
        }
    }

    private void reportStep(String step) {
        com.hexadron.launcher.core.LauncherLog.info("Startup: " + step);
        if (splash != null) {
            splash.step(step);
        }
    }

    /**
     * Builds the window behind the splash and shows it once the splash has gone.
     *
     * <p>Built but not shown: the scene is assembled here, which is the
     * expensive part, and {@code show} happens in the callback. That ordering
     * costs nothing and buys two things. The splash keeps keyboard focus for as
     * long as it is up, so a key press can dismiss it - it could not if a window
     * behind it had taken focus. And the window arrives as one event rather than
     * appearing under a panel that then dissolves off it.
     *
     * <p>What makes it safe is the implicit exit switched off in {@code start}:
     * closing the last window is what ends a JavaFX application, and between the
     * splash closing and the window opening there is briefly no window at all.
     * It goes back on the moment the window is up.
     */
    private void open(Stage stage, LauncherService service) {
        I18n.use(Language.resolve(service.settings().language()));
        // Now that settings have been read, the splash can be told how long the
        // user wants to look at it. Until this point it has been using its own
        // default, because reading that setting is one of the stages it shows.
        if (splash != null) {
            splash.minimumVisible(service.settings().splashMinimumMillis());
        }
        reportStep("interface");

        window = new MainWindow(service, stage);
        // The title is set by the window, from the active language.
        stage.getIcons().setAll(Brand.windowIcons());
        stage.setScene(window.build());
        stage.setMinWidth(900);
        stage.setMinHeight(620);

        if (splash == null) {
            reveal(stage, service, null);
            return;
        }
        splash.done(() -> reveal(stage, service, splash.summary()));
    }

    private void reveal(Stage stage, LauncherService service, String startupSummary) {
        stage.show();
        stage.toFront();
        stage.requestFocus();
        Platform.setImplicitExit(true);

        if (startupSummary != null) {
            // Logged rather than shown: it answers "why is it slow to start",
            // and that question is asked with a copy of the log attached.
            window.logStartup(startupSummary);
        }
        // Last, and over the window rather than over the splash: what this asks
        // is whether to replace the program the user has just opened, and that
        // question is worth the window being there behind it.
        if (pendingUpdate != null) {
            window.offerUpdate(pendingUpdate);
        }

        // Detecting Java reads the registry and probes every runtime it finds.
        // Doing it now, in the background, keeps that cost off the first press
        // of Play - and off start-up, where it would be the slowest stage.
        service.warmUpInBackground();
    }

    private void failed(Throwable e) {
        if (splash != null) {
            splash.close();
        }
        Platform.setImplicitExit(true);
        Alert alert = new Alert(Alert.AlertType.ERROR,
                I18n.t("startup.failed.body",
                        e.getMessage() == null ? e.toString() : e.getMessage()));
        Theme.apply(alert.getDialogPane());
        alert.setHeaderText(I18n.t("startup.failed.header"));
        alert.showAndWait();
        Platform.exit();
    }

    @Override
    public void stop() {
        if (window != null) {
            window.shutdown();
        }
        com.hexadron.launcher.core.LauncherLog.info("Launcher closed");
        com.hexadron.launcher.core.LauncherLog.close();
    }
}
