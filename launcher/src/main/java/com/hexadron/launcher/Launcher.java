package com.hexadron.launcher;

import fr.flowarg.flowupdater.FlowUpdater;
import fr.flowarg.flowupdater.versions.VanillaVersion;
import fr.flowarg.flowupdater.versions.fabric.FabricVersion;
import fr.flowarg.flowupdater.versions.fabric.FabricVersionBuilder;
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
		statusLabel = new Label("Завантажую Minecraft 26.2 + Fabric...");
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

			// Без .withFabricVersion(...) — бібліотека сама візьме найсвіжіший
			// доступний Fabric Loader, той самий принцип, що і з fabric.mod.json раніше
			FabricVersion fabricVersion = new FabricVersionBuilder()
					.build();

			FlowUpdater updater = new FlowUpdater.FlowUpdaterBuilder()
					.withVanillaVersion(version)
					.withModLoaderVersion(fabricVersion)
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

