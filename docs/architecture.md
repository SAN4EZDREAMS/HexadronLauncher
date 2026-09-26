# Architecture

This page describes how the code is laid out, the rules the code follows, and the `mod/` module that builds the Hexadron Optimise mod. For build commands and Gradle tasks, see [building.md](building.md).

## Modules

`settings.gradle` includes two Gradle projects:

| Project | Output |
|---|---|
| `launcher` | The launcher: one jar for the desktop window and the command-line mode, plus the launch wrapper jar |
| `mod` | `hexadron-optimise`, a Fabric mod that names the performance mod set |

Other folders:

| Path | Content |
|---|---|
| `tools/` | `license-header.txt` (the header text) and `stamp-license-headers.py` (adds it to files) |
| `.github/workflows/` | `build-launcher.yml`, `build-mod.yml`, `release-launcher.yml`, `virustotal.yml` |
| `.github/scripts/virustotal_scan.py` | Writes the VirusTotal result into the release notes |

## Launcher packages

All launcher code is in `launcher/src/main/java/com/hexadron/launcher/`.

Top-level classes:

| Class | Purpose |
|---|---|
| `Main` | Entry point for packaged builds and the distribution zip. With arguments it calls `HexadronCli`; with none it starts the JavaFX application. It does not extend `Application`, so the JDK starts it when JavaFX is on the class path |
| `Launcher` | The JavaFX `Application`: language, splash screen, start-up steps, main window |
| `BuildConfig` | Values put into the jar manifest at build time: the CurseForge API key and the version |
| `SelfCheck` | The self-check (see [Design rules](#design-rules)) |

Packages:

| Package | Content |
|---|---|
| `about/` | `Credits`: the About window's credit list, read from `about/credits.json` |
| `auth/` | Accounts and `accounts.json`, Microsoft sign-in (browser with PKCE and a loopback redirect server, or device code) |
| `auth/secret/` | Credential stores: Windows DPAPI, macOS Keychain, freedesktop Secret Service, and an AES-256-GCM encrypted file as the fallback. `SecretStores` picks the best available |
| `cleanup/` | The storage window's model: scans the data folder, works out what is still in use, and deletes only what passes a last safety check (`StorageCleaner`) |
| `cli/` | `HexadronCli`, the command-line mode |
| `crash/` | Crash analysis: what a run left behind (`CrashEvidence`), the rule file (`CrashRules`, built-in copy in `crash/rules.json`), matching (`CrashAnalyzer`), fix kinds and their checks (`CrashFix`, `CrashFixes`), and signed rule updates (`CrashRuleSource`). See [crashes.md](crashes.md) |
| `core/` | `LauncherService` (the application layer for the UI and the CLI), `LauncherSettings` (`launcher.json`), `GameDirs` (data folder layout), `LauncherLog`, `VerifiedFiles` (the record of files already checked against their hash), `Progress` |
| `i18n/` | `I18n` (the string table) and `Language`; strings are in `lang/<code>.properties` |
| `install/` | `VersionInstaller`, `AssetInstaller`, `NativesExtractor` |
| `install/loader/` | Loader installers: Fabric and Quilt (`FabricLikeInstaller`), Forge, NeoForge; loader version lists, including `maven-metadata.xml` |
| `install/loader/forge/` | The Forge and NeoForge installer engine: `install_profile.json` (both formats), the token language (`Tokens`), and `ProcessorRunner`, which runs each processor in a separate JVM |
| `json/` | `Json`: a small, strict JSON tree, parser and writer |
| `launch/` | Java detection (`JavaLocator`), Java download from Adoptium (`JavaProvisioner`), runtime selection (`JavaRuntimes`), the command line (`LaunchCommandBuilder`), the game process (`GameLauncher`), and `LaunchWrapperJar`, which extracts the launch wrapper |
| `meta/` | Mojang metadata: version manifest, version JSON, `inheritsFrom` resolution, rules, arguments, libraries, asset index |
| `mods/` | Modrinth and CurseForge providers; `ContentKind` (mods, resource packs, shaders); mod, modpack, data pack and pack installers; both modpack formats (`PackArchive`); the mod lock file (`ModLibrary`); jar and pack descriptors; version ranges; the dependency graph (`ModDependents`); categories; the world list (`WorldSaves`) |
| `net/` | `Http` (shared client with retry and User-Agent), `Downloader` (parallel, verifies hashes), `ProxyChoice` |
| `profile/` | `Profile` (one instance and its game folder under `instances/`), `ProfileStore`, `ProfileLayout` (groups and order, shared by the list and grid views) |
| `share/` | `.hexbuild` build files: `BuildFormat`, `BuildExport`, `BuildImport` |
| `skin/` | Skins and capes: Mojang Profile API for Microsoft accounts, local sheet layouts, templates, and the 3D model for the viewer (`SkinModel`) |
| `ui/` | JavaFX windows and dialogs, the content window and its sections, list and grid views, theme, tray. No launch logic |
| `update/` | Self-update: release feed, channels, version comparison, image manifests and delta updates, the VirusTotal report parser, and `Updater`, the second process that replaces the installed folder |
| `util/` | Platform detection, hashes, Maven coordinates, archive extraction, argument splitting, file permissions, log redaction (`Redactor`), fast tree deletion, a lossless WebP decoder |

Resources in `launcher/src/main/resources/`:

| Path | Content |
|---|---|
| `lang/` | 16 language files; `en.properties` is the English text |
| `ui/` | `hexadron.css` and the application icons |
| `packs/hexadron-optimise.json` | The Hexadron Optimise mod set |
| `about/credits.json` | The credit list |
| `crash/rules.json` | The built-in crash rules and their texts in 16 languages |
| `wrapper/` | Not in the source tree. `processResources` copies the built `wrapperJar` here |

### The launch wrapper

`launcher/src/wrapper/java/com/hexadron/wrapper/GameLaunchWrapper.java` is a separate source set. It is built into `hexadron-launchwrapper.jar` by the `wrapperJar` task and embedded in the launcher's resources. `LaunchWrapperJar` extracts it to the data folder and extracts it again when its hash changes.

The wrapper runs inside the game's JVM. The launcher replaces secrets in the game arguments with placeholders and sends the real values on the child's standard input. The wrapper puts them back and calls the game's main class by reflection. This keeps the session token out of the process table and out of `hs_err_pid*.log`. See [SECURITY.md](../SECURITY.md).

The wrapper is compiled with `options.release = 8`, because Minecraft up to 1.16 runs on Java 8. Do not raise it above the oldest Java the launcher still starts the game on.

## Design rules

These rules hold in the code. Keep them when you change it.

- **No third-party libraries in the launcher.** The `dependencies` block in `launcher/build.gradle` is empty. The core uses only the JDK (`java.net.http` for HTTP). JavaFX comes from the `org.openjfx.javafxplugin` plugin (module `javafx.controls`) and is used by `ui/`, `Launcher`, `Main` and `skin/SkinModel` only.
- **JSON goes through `json/Json.java`.** Every JSON file and API response is read as a tree and navigated by key. There is no data binding and no other JSON library.
- **Third-party programs run outside the launcher process.** Java runtimes and loader installers (the Forge processors run in their own JVM) are managed independently and never run unsandboxed in the launcher's own JVM.
- **`LauncherService` is the application layer.** It has no UI dependencies. The window and `HexadronCli` call the same methods, so every flow can run headless.
- **Long work runs off the JavaFX thread.** The UI starts worker threads (for example `MainWindow.runInBackground`) and returns results with `Platform.runLater`. `Progress` implementations are called from worker threads; `UiProgress` moves the updates onto the JavaFX thread and limits how often it does so.
- **Credentials stay out of logs and command lines.** Tokens are registered with `Redactor` when the launcher gets them. Every log output (`UiProgress`, `LauncherLog`, the console `Progress`, the game output reader in `GameLauncher`) removes them with `Redactor.scrub`. Credential stores that call an operating-system helper (`security`, `secret-tool`, `powershell`) send secrets on standard input (`ProcessSecretStore`). The game gets its token through the launch wrapper.
- **A self-check, not a test framework.** `SelfCheck` is a plain `main` class with `check(...)` assertions. It needs no network, no display and no test library. `./gradlew :launcher:selfCheck` runs it, and `check` depends on it. At commit 9923b33 it runs 69 groups and reports `2067 checks passed`. Add a check when you add parsing or decision logic.
- **Every source file has the licence header.** The `licenseHeaders` task checks all `.java`, `.css` and `.properties` files under `launcher/src` and `mod/src` against `tools/license-header.txt`. `compileJava` and `compileWrapperJava` depend on it, so a missing header stops the build. To add headers, run `python3 tools/stamp-license-headers.py`.

## The `mod/` module

`mod/` builds `hexadron-optimise`, a Fabric mod with no game code. It contains a logger, the mod id and two empty entrypoints (`HexadronOptimise`, `HexadronOptimiseClient`). Its purpose is to name the performance mod set.

Files:

| File | Content |
|---|---|
| `mod/build.gradle` | Loom build with split `main` and `client` source sets; the optional dev-mods set |
| `mod/gradle.properties` | Project properties only (see below). `org.gradle.*` settings are in the root `gradle.properties`, because Gradle ignores them in a subproject |
| `src/main/resources/fabric.mod.json` | Mod metadata |
| `src/main/resources/hexadron-optimise.mixins.json`, `src/client/resources/hexadron-optimise.client.mixins.json` | Mixin configs with empty mixin lists |
| `src/main/java/com/hexadron/optimise/HexadronOptimise.java` | Main entrypoint; logs one line |
| `src/client/java/com/hexadron/optimise/client/HexadronOptimiseClient.java` | Client entrypoint; empty |

`mod/gradle.properties`:

| Key | Value |
|---|---|
| `minecraft_version` | `26.2` |
| `loader_version` | `0.19.3` |
| `loom_version` | `1.17-SNAPSHOT` |
| `fabric_api_version` | `0.157.0+26.2` |
| `version` | `1.0.0` |
| `group` | `com.hexadron.optimise` |

### Why the jar works on every version

The jar has no reference to a Minecraft class, so a Minecraft update cannot break it. Keep it that way:

- `fabric.mod.json` has no `minecraft` bound. It depends only on `"fabricloader": ">=0.14.0"`.
- The code is compiled with `options.release = 17`, so it loads on Java 17 and later. The toolchain is still Java 25, because Loom must read Minecraft 26.2 classes in the development environment.
- The mixin configs have no `compatibilityLevel`.
- The seven performance mods are under `recommends`, not `depends`: Sodium, Lithium, FerriteCore, Krypton, EntityCulling, Iris and Mod Menu. With `depends`, the game does not start when one of them has no build for the Minecraft version.

### The mod set and the dev mods

The launcher does not read the mod's metadata. It installs the set from `launcher/src/main/resources/packs/hexadron-optimise.json`. That file is packed into the launcher jar, so a change to it needs a new launcher build but no code change. The command-line command `mods <profile> <pack.json>` installs a set from a file in the same format without a rebuild. For what the set contains and how it is installed, see [mods.md](mods.md).

The launcher does not install the `hexadron-optimise` jar itself. To copy a local build into a profile, use the `addjar` command. The `cli` task runs in the `launcher/` folder, so give the jar path relative to it, or as an absolute path:

./gradlew :mod:build
./gradlew :launcher:cli --args="addjar <profile-id> ../mod/build/libs/hexadron-optimise-1.0.0.jar"

`addjar` does not write the jar into the mod lock file, so a later pack install does not delete it.

`mod/build.gradle` has a second, fixed list for the development environment. It adds the seven performance mods as `runtimeOnly` dependencies from the Modrinth Maven repository, pinned to specific builds. It is off by default, so `:mod:build` does not need the Modrinth API:

./gradlew :mod:runClient -Phexadron.devMods=true

The mods are not bundled into the jar with `include`, because that would redistribute other authors' mods.

### Loom and Minecraft 26.1+

Minecraft 26.1 is the first version without obfuscation. The `net.fabricmc.fabric-loom` plugin does no remapping for it, so the `mod*` configurations (`modImplementation`, `modCompileOnly`, ...) and `remapJar` do not exist. Use `implementation`, `compileOnly`, `runtimeOnly` and `jar`. The `mod*` names are only in `net.fabricmc.fabric-loom-remap`, which is for 1.21.11 and older.