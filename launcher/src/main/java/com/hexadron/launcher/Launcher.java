package com.hexadron.launcher;

import com.hexadron.launcher.cli.HexadronCli;
import com.hexadron.launcher.core.LauncherService;
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
        LauncherService service;
        try {
            service = LauncherService.createDefault();
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "The launcher could not prepare its data directory.\n\n"
                            + (e.getMessage() == null ? e.toString() : e.getMessage()));
            alert.setHeaderText("Startup failed");
            alert.showAndWait();
            javafx.application.Platform.exit();
            return;
        }

        window = new MainWindow(service, stage);
        stage.setTitle("HexadronLauncher");
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
