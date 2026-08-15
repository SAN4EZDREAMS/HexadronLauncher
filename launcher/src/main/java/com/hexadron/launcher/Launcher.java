package com.hexadron.launcher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

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

			// Тимчасовий шлях, поки нема нормального публічного релізу мода —
			// беремо свіжозібраний jar прямо з сусіднього підпроєкту в цьому ж репо.
			// Це працює тільки в нас, для розробки; для реальних користувачів
			// пізніше знадобиться хостити jar десь публічно (напр. GitHub Releases)
			// і качати його через FlowUpdater.Mod, як і решту модів.
			Path modJar = Paths.get("..", "mod", "build", "libs", "hexadron-optimise-1.0.0.jar");
			Path modsDir = gameDir.resolve("mods");
			Files.createDirectories(modsDir);
			Files.copy(modJar, modsDir.resolve("hexadron-optimise-1.0.0.jar"), StandardCopyOption.REPLACE_EXISTING);

			Platform.runLater(() -> statusLabel.setText("Готово! Minecraft 26.2 + Fabric + наш мод у " + gameDir));

			// Наступний крок: розпарсити fabric-loader json і зібрати класпас.
			// Поки що НЕ запускаємо гру — тільки друкуємо результат в консоль,
			// щоб перевірити, що парсинг правильний, перш ніж ризикувати запуском.
			buildAndPrintClasspath(gameDir);
		} catch (Exception e) {
			e.printStackTrace();
			Platform.runLater(() -> statusLabel.setText("Помилка: " + e.getMessage()));
		}
	}

	private void buildAndPrintClasspath(Path gameDir) throws IOException {
		// FlowUpdater кладе fabric-loader json прямо в корінь gameDir
		// (не в стандартну структуру versions/, як офіційний лаунчер)
		Path fabricJsonPath = null;
		try (var files = Files.list(gameDir)) {
			fabricJsonPath = files
					.filter(p -> p.getFileName().toString().startsWith("fabric-loader-") && p.getFileName().toString().endsWith(".json"))
					.findFirst()
					.orElse(null);
		}

		if (fabricJsonPath == null) {
			System.out.println("Не знайшов fabric-loader json у " + gameDir + " — щось не так із завантаженням.");
			return;
		}

		Gson gson = new Gson();
		JsonObject fabricJson = gson.fromJson(Files.readString(fabricJsonPath), JsonObject.class);

		String mainClass = fabricJson.get("mainClass").getAsJsonObject().get("client").getAsString();

		List<String> classpath = new ArrayList<>();
		classpath.add(gameDir.resolve("client.jar").toString());

		JsonArray libraries = fabricJson.getAsJsonArray("libraries");
		for (JsonElement el : libraries) {
			String name = el.getAsJsonObject().get("name").getAsString();
			// "group:artifact:version" -> group/із/крапками/як/слеші/artifact/version/artifact-version.jar
			String[] parts = name.split(":");
			String path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + ".jar";
			classpath.add(gameDir.resolve("libraries").resolve(path).toString());
		}

		System.out.println("=== Знайдений mainClass ===");
		System.out.println(mainClass);
		System.out.println("=== Класпас (" + classpath.size() + " записів) ===");
		classpath.forEach(System.out::println);
	}

	public static void main(String[] args) {
		launch(args);
	}
}

