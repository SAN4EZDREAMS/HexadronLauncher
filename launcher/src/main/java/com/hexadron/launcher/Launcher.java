package com.hexadron.launcher;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Launcher extends Application {
	@Override
	public void start(Stage stage) {
		// Це перший рядок коду лаунчера. Якщо бачиш це вікно —
		// весь toolchain (JavaFX + Gradle + твоя машина) працює правильно.
		Label label = new Label("HexadronLauncher");
		StackPane root = new StackPane(label);

		stage.setScene(new Scene(root, 400, 300));
		stage.setTitle("HexadronLauncher");
		stage.show();
	}

	public static void main(String[] args) {
		launch(args);
	}
}
