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
                Platform.runLater(() -> failed(e));
                return;
            }
            Platform.runLater(() -> open(stage, service));
        }, "hexadron-startup");
        startup.setDaemon(true);
        startup.start();
    }

    private void reportStep(String step) {
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
        stage.getIcons().add(Brand.windowIcon());
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
    }
}
