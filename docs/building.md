# Building

This page covers the build commands and Gradle tasks, the licence-header check, the CI workflows, the packaged clients and release files, the icons, and the version numbers.

## Requirements

- JDK 25. Both modules use a Java 25 toolchain. `settings.gradle` applies the foojay toolchain resolver, so Gradle can download a matching JDK if none is installed.
- The Gradle wrapper (Gradle 9.5.1). Use `./gradlew`.

Gradle reads `org.gradle.*` keys only from the root `gradle.properties`, so do not put them in `mod/gradle.properties`. The configuration cache is off, because IntelliJ IDEA and Fabric Loom do not fully support it.

`gradle/wrapper/gradle-wrapper.properties` sets `networkTimeout=60000` and `retries=3` for the Gradle download. `./gradlew wrapper` rewrites the file and removes them. Put them back after a Gradle update.

## Commands

```
./gradlew :launcher:build          # compile, run the checks, make the jar and distributions
./gradlew :launcher:selfCheck      # check the launch core; no network, no display
./gradlew :launcher:licenseHeaders # check the licence header in every source file
./gradlew :launcher:run            # start the launcher window
./gradlew :launcher:cli --args="versions"   # command-line mode
./gradlew :launcher:appImage       # packaged client for this operating system
./gradlew :mod:build               # build the Hexadron Optimise mod
./gradlew :mod:runClient -Phexadron.devMods=true
```

Add `--configure-on-demand` to build one module without the other. Without it Gradle configures all projects, so an error in `mod/build.gradle` also fails `:launcher:build`. CI uses this flag on every Gradle call.

`-Phexadron.devMods=true` adds Sodium, Lithium, FerriteCore, Krypton, EntityCulling, Iris and Mod Menu from the Modrinth Maven to `runClient`. It is off by default, so `:mod:build` does not need the Modrinth API. The command-line mode is described in [configuration.md](configuration.md).

## Gradle tasks

| Task (`:launcher`) | What it does |
|---|---|
| `licenseHeaders` | Checks the licence header in every `.java`, `.css` and `.properties` file under `launcher/src` and `mod/src`. Never up to date |
| `selfCheck` | Runs `com.hexadron.launcher.SelfCheck`. `check` depends on it |
| `cli` | Runs `com.hexadron.launcher.cli.HexadronCli` |
| `run` | Starts `com.hexadron.launcher.Main` (from the `application` plugin) |
| `generateVersionResource` | Writes `com/hexadron/launcher/version.properties` with the build version. `processResources` includes it |
| `wrapperJar` | Builds `hexadron-launchwrapper.jar` from `src/wrapper/java` for Java 8. It goes into the launcher jar under `wrapper/` |
| `jpackageInput`, `cleanAppImage` | Collect the runtime jars; delete the old image. `appImage` depends on both |
| `appImage` | Runs `jpackage --type app-image` into `launcher/build/jpackage` |

The mod uses Fabric Loom, which provides `build` and `runClient`. It builds `hexadron-optimise-<version>.jar` for Java 17. See [architecture.md](architecture.md).

## Licence headers

Every `.java`, `.css` and `.properties` file must start with the text in `tools/license-header.txt`. `licenseHeaders` compares the first 40 lines of each file with it, after it removes comment characters (`/*`, `*`, `#`) and blank lines. The error lists each file that fails.

In `:launcher`, `compileJava`, `compileWrapperJava` and `check` depend on `licenseHeaders`, so every launcher build runs it. `:mod:build` does not run it.

```
python3 tools/stamp-license-headers.py          # add missing headers, replace old ones
python3 tools/stamp-license-headers.py --check  # report only; exit code 1 on failures
```

The script finds an old header by its `SPDX-License-Identifier: LicenseRef-Hexadron-NC` line and replaces it. To change the wording, edit `tools/license-header.txt` and run the script. Keep the file in `tools/`: GitHub reads any `LICENSE*` file in the root as a licence. Terms: [LICENSE.md](../LICENSE.md).

## Packaged clients

`appImage` builds a folder with the launcher, JavaFX and a Java 25 runtime. It starts on a computer with no Java installed.

- `jpackage` builds only for the operating system it runs on.
- The icon is `launcher/packaging/icon.ico` (Windows), `icon.icns` (macOS) or `icon.png` (Linux). The task fails if it is missing.
- `--jlink-options` keeps `bin/java` in the embedded runtime, so the launcher can start Minecraft and the Forge installers with it. Do not add `--strip-native-commands`.
- No Gradle task archives the image. Gradle's `Zip` and `Tar` tasks follow symbolic links and drop the executable bit, and the image does not start without them. CI uses `tar` (Linux, macOS) and `7z` (Windows).

The clients are portable archives, not installers. Without a code-signing certificate, Windows SmartScreen warns about an `.msi` and macOS refuses a `.dmg` as "damaged".

## CI workflows

The workflows are in `.github/workflows/`. They use Microsoft's JDK 25 build and validate the Gradle wrapper first.

| Workflow | Trigger | Jobs |
|---|---|---|
| `build-launcher.yml` | push or pull request that changes `launcher/**` or the Gradle files; manual | `build`, `package`, `flatpak` |
| `build-mod.yml` | push or pull request that changes `mod/**` or the Gradle files; manual | `build` |
| `release-launcher.yml` | tag `v*`; manual (`nightly` or `release`); daily at 03:00 UTC | `plan`, `build`, `flatpak`, `publish`, `virustotal` |
| `virustotal.yml` | called by `release-launcher.yml`; manual with a tag | `scan` |

### build-launcher

- `build` (Ubuntu): `licenseHeaders`, `selfCheck`, then `assemble`.
- `package` (after `build`): `licenseHeaders` and `appImage` on `windows-2025`, `ubuntu-24.04` and `macos-15`, with `fail-fast: false`.
- `flatpak` (after all three `package` jobs pass): wraps the Linux client.

| Artifact | Contents | Kept |
|---|---|---|
| `hexadron-launcher-jar` | `launcher/build/libs/*.jar` and the `distZip` start-script zip. Needs Java 25 | 14 days |
| `hexadron-launcher-windows` | the `HexadronLauncher` folder and `HOW-TO-RUN.txt` | 30 days |
| `hexadron-launcher-linux`, `-macos` | `HexadronLauncher-<os>.tar.gz` | 30 days |
| `hexadron-launcher-flatpak` | `HexadronLauncher-linux.flatpak` | 30 days |

GitHub delivers every artifact as a zip. The Linux and macOS artifacts contain a `tar.gz`, because GitHub's zip loses the executable bit and symbolic links. Unpack both levels.

The jar artifact is not a client. The launcher jar has no `Main-Class`, so use the start scripts in the zip. The JavaFX plugin adds JavaFX jars for the build platform, and CI builds this artifact on Linux.

The workflow has a commented-out `if:` that limits `package` to manual runs, tags and the default branch. If you enable it, note that **Re-run** keeps the original `push` event. Only **Run workflow** is a `workflow_dispatch`.

### build-mod

One job: `:mod:build`. Artifact `hexadron-optimise-jar` (`mod/build/libs/*.jar`), with the repository's default retention.

### release-launcher

`plan` sets the tag and version. `<base>` is the default version in `launcher/build.gradle`.

| Trigger | Tag and version | Prerelease |
|---|---|---|
| tag `v*` | the tag; version is the tag without `v` | yes if the tag contains `-` |
| manual, `release` | `v<base>` | no |
| manual, `nightly`, or schedule | `v<base>-nightly.<run number>` | yes |

A scheduled run stops if there were no commits in the last 24 hours.

`build` runs on the same three runners: `licenseHeaders`, `selfCheck` (Linux only) and `appImage` with `-PlauncherVersion=<version>`. It packs the full archive and runs `com.hexadron.launcher.update.ManifestTool` for the delta-update files. `flatpak` wraps the Linux image.

`publish` needs the three full archives; a failed Flatpak does not stop it. It deletes a release with the same tag if there is one, creates the release with the commits since the previous release as notes, and uploads all `HexadronLauncher-*` files. After a prerelease it deletes all prereleases except the newest five. `virustotal` then scans the files; see [updates.md](updates.md).

| Release file | Purpose |
|---|---|
| `HexadronLauncher-windows.zip`, `-linux.tar.gz`, `-macos.tar.gz` | full clients |
| `HexadronLauncher-linux.flatpak` | Flatpak bundle, if its job passed |
| `HexadronLauncher-<windows\|linux\|macos>.manifest.json` | SHA-256 file list for delta updates |
| `HexadronLauncher-parts-<win\|lnx\|darwin>-<runtime\|libs\|app\|base>.<zip\|tar.gz>` | delta-update parts |

Do not rename these files. The updater finds its client by name (`ReleaseFeed.matches()`). The part names use `win`, `lnx` and `darwin` because older launchers take any file that contains their system name as the full client. Only `ManifestTool.partAsset()` builds part names.

### Secrets

- `CURSEFORGE_API_KEY`: goes into the jar manifest as `Hexadron-CurseForge-Api-Key`. Without it the build passes and CurseForge is off in that build. Forks and pull requests from forks get no secrets. The build log shows only whether a key was supplied.
- `VT_API_KEY`: without it there is no VirusTotal scan and no scan placeholder in the release notes.

## Flatpak

`build-launcher.yml` builds a Flatpak for each run and `release-launcher.yml` attaches it to each release. To build it locally:

```
./gradlew :launcher:appImage
tar czf HexadronLauncher-linux.tar.gz -C launcher/build/jpackage .
launcher/packaging/flatpak/build-flatpak.sh HexadronLauncher-linux.tar.gz 0.9.8
```

Details: [flatpak-and-sandboxing.md](flatpak-and-sandboxing.md).

## Icons

| Files | Used for |
|---|---|
| `launcher/packaging/icon.ico`, `icon.icns`, `icon.png` | the executable icon that `jpackage` adds |
| `launcher/src/main/resources/ui/icon/icon-{16,24,32,48,64,128}.png` | window icons (title bar, taskbar, alt-tab), loaded by `ui/Brand.windowIcons()` |

The two sets are separate. A correct executable icon does not fix the window icon.

Each window-icon size is its own image, rendered at four times its size. A single large image scaled down to 16 pixels makes the H unreadable. Do not draw window icons with `Canvas.snapshot`: a canvas that is not in a scene gives an empty image, and the platform shows its generic icon with no error. `Brand.icon(int)` draws the mark only as a fallback when no resource loads. `SelfCheck` reads the PNG header of each window icon and checks that it exists, is a PNG and has the correct size.

The icons are committed, so the build does not need Python. To change them, run the generator. Do not edit the images.

```
pip install pillow
python3 launcher/packaging/make-icons.py [path/to/bold-sans.ttf]
```

The script uses the same colour (`#2d7d46`), corner radius (28%) and cap height (62%) as `ui/Brand.java`. If you change one, change the other.

## Versions

| Where | Value |
|---|---|
| `launcher/build.gradle` | `0.9.8` by default; `-PlauncherVersion=<v>` overrides it. CI passes the version it publishes |
| jar manifest `Implementation-Version` | the full version, for example `0.9.8-nightly.41`. `BuildConfig.version()` reads it for the User-Agent, splash screen, About window and update check |
| `com/hexadron/launcher/version.properties` | the same version, written by the `generateVersionResource` task into the resources. `BuildConfig.version()` reads it when there is no jar manifest: with `run`, `cli`, `selfCheck` and in the IDE. With neither file nor manifest the version is `0.0.0-dev` |
| `jpackage --app-version` | the numeric part only, because `jpackage` accepts only digits and dots |
| macOS bundle | `1.0.0` when the version starts with `0.`, because Apple requires `CFBundleVersion` above zero. The build logs this |
| `mod/gradle.properties` | mod `1.0.0`, Minecraft `26.2`, Fabric Loader `0.19.3`, Fabric API `0.157.0+26.2`, Loom `1.17-SNAPSHOT` |

The jar in the image (`launcher-<version>.jar`) and the User-Agent carry the full version on all systems.
