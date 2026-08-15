package com.hexadron.launcher;

import fr.flowarg.flowupdater.FlowUpdater;
import fr.flowarg.flowupdater.versions.VanillaVersion;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Launcher extends Application {
	private Label statusLabel;

	@Override
	public void start(Stage stage) {
		statusLabel = new Label("Завантажую Minecraft 26.2...");
		StackPane root = new StackPane(statusLabel);

		stage.setScene(new Scene(root, 400, 300));
		stage.setTitle("HexadronLauncher");
		stage.show();

		// Завантаження — важка мережева операція, тому в окремому потоці,
		// інакше вікно "замерзне" поки качається гра
		new Thread(this::downloadVanilla).start();
	}

	private void downloadVanilla() {
		try {
			Path gameDir = Paths.get(System.getProperty("user.home"), ".hexadronlauncher", "minecraft");

			VanillaVersion version = new VanillaVersion.VanillaVersionBuilder()
					.withName("26.2")
					.build();

			FlowUpdater updater = new FlowUpdater.FlowUpdaterBuilder()
					.withVanillaVersion(version)
					.build();

			updater.update(gameDir);

			Platform.runLater(() -> statusLabel.setText("Готово! Minecraft 26.2 у " + gameDir));
		} catch (Exception e) {
			e.printStackTrace();
			Platform.runLater(() -> statusLabel.setText("Помилка: " + e.getMessage()));
		}
	}

	public static void main(String[] args) {
		launch(args);
	}
}

