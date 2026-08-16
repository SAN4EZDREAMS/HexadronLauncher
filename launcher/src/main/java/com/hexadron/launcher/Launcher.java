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

		String mainClass = fabricJson.get("mainClass").getAsString();

		List<String> classpath = new ArrayList<>();
		classpath.add(gameDir.resolve("client.jar").toString());

		// Спершу ванільні бібліотеки (успадковані через inheritsFrom) —
		// саме тут живуть LWJGL, Netty тощо, і саме тут трапляються
		// ОС-специфічні rules, яких у fabric-json немає
		Path vanillaJsonPath = gameDir.resolve("26.2.json");
		if (Files.exists(vanillaJsonPath)) {
			JsonObject vanillaJson = gson.fromJson(Files.readString(vanillaJsonPath), JsonObject.class);
			addLibraries(vanillaJson.getAsJsonArray("libraries"), gameDir, classpath);
		} else {
			System.out.println("УВАГА: не знайшов 26.2.json поруч — класпас буде неповний.");
		}

		addLibraries(fabricJson.getAsJsonArray("libraries"), gameDir, classpath);

		// JVM-аргументи з fabric json (той самий -DFabricMcEmu=..., без якого Fabric не стартує)
		List<String> jvmArgs = new ArrayList<>();
		if (fabricJson.has("arguments") && fabricJson.getAsJsonObject("arguments").has("jvm")) {
			for (JsonElement el : fabricJson.getAsJsonObject("arguments").getAsJsonArray("jvm")) {
				jvmArgs.add(el.getAsString().trim());
			}
		}
		jvmArgs.add("-Djava.library.path=" + gameDir.resolve("natives"));

		// assetIndex беремо з ванільного 26.2.json — у fabric json його нема
		String assetIndex = "";
		if (Files.exists(vanillaJsonPath)) {
			JsonObject vanillaJson = gson.fromJson(Files.readString(vanillaJsonPath), JsonObject.class);
			assetIndex = vanillaJson.getAsJsonObject("assetIndex").get("id").getAsString();
		}

		// Мінімальний набір game-аргументів для локального офлайн-тесту (не справжній акаунт) —
		// повний список правил з 26.2.json (демо-режим, роздільна здатність тощо) поки не парсимо,
		// це свідоме спрощення тільки для першої перевірки запуску
		List<String> gameArgs = List.of(
				"--username", "HexadronTester",
				"--version", "26.2",
				"--gameDir", gameDir.toString(),
				"--assetsDir", gameDir.resolve("assets").toString(),
				"--assetIndex", assetIndex,
				"--uuid", "00000000-0000-0000-0000-000000000000",
				"--accessToken", "0",
				"--userType", "legacy",
				"--versionType", "release"
		);

		System.out.println("=== mainClass ===");
		System.out.println(mainClass);
		System.out.println("=== JVM-аргументи ===");
		jvmArgs.forEach(System.out::println);
		System.out.println("=== Game-аргументи ===");
		System.out.println(String.join(" ", gameArgs));
		System.out.println("=== Класпас (" + classpath.size() + " записів) ===");
		classpath.forEach(System.out::println);
	}

	private void addLibraries(JsonArray libraries, Path gameDir, List<String> classpath) {
		if (libraries == null) return;
		for (JsonElement el : libraries) {
			JsonObject lib = el.getAsJsonObject();

			if (!passesOsRules(lib)) continue;

			String relativePath;
			if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
				// Ванільні бібліотеки часто дають готовий шлях напряму —
				// надійніше, ніж вираховувати його самим з "name"
				relativePath = lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString();
			} else {
				String name = lib.get("name").getAsString();
				String[] parts = name.split(":");
				relativePath = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + ".jar";
			}
			classpath.add(gameDir.resolve("libraries").resolve(relativePath).toString());
		}
	}

	private boolean passesOsRules(JsonObject lib) {
		if (!lib.has("rules")) return true;

		String currentOs = System.getProperty("os.name").toLowerCase().contains("win") ? "windows"
				: System.getProperty("os.name").toLowerCase().contains("mac") ? "osx" : "linux";

		boolean allowed = false;
		for (JsonElement ruleEl : lib.getAsJsonArray("rules")) {
			JsonObject rule = ruleEl.getAsJsonObject();
			boolean isAllow = rule.get("action").getAsString().equals("allow");
			if (!rule.has("os")) {
				allowed = isAllow;
				continue;
			}
			String osName = rule.getAsJsonObject("os").has("name") ? rule.getAsJsonObject("os").get("name").getAsString() : null;
			if (osName == null || osName.equals(currentOs)) {
				allowed = isAllow;
			}
		}
		return allowed;
	}

	public static void main(String[] args) {
		launch(args);
	}
}

