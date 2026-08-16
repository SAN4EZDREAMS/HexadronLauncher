package com.hexadron.launcher;

import com.hexadron.launcher.cli.HexadronCli;
import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.i18n.Language;
import com.hexadron.launcher.ui.MainWindow;

import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

/**
 * Application entry point.
 *
 * <p>With no arguments it opens the window. With arguments it hands off to
 * {@link HexadronCli}, so one artifact serves both the desktop and headless use.
 */
public final class Launcher extends Application {

    private MainWindow window;

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

        LauncherService service;
        try {
            service = LauncherService.createDefault();
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    I18n.t("startup.failed.body",
                            e.getMessage() == null ? e.toString() : e.getMessage()));
            alert.setHeaderText(I18n.t("startup.failed.header"));
            alert.showAndWait();
            javafx.application.Platform.exit();
            return;
        }

        I18n.use(Language.resolve(service.settings().language()));

        window = new MainWindow(service, stage);
        // The title is set by the window, from the active language.
        stage.getIcons().add(com.hexadron.launcher.ui.TrayIntegration.windowIcon());
        stage.setScene(window.build());
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.show();
    }

    @Override
    public void stop() {
        if (window != null) {
            window.shutdown();
        }
    }
}
