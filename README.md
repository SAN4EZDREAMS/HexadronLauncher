# HexadronLauncher

A Minecraft launcher for Windows, macOS and Linux, and a Fabric mod that names a
performance mod set. The project is in beta (version 0.9.8).

- `launcher/` - the launcher (Java 25, JavaFX).
- `mod/` - Hexadron Optimise, a Fabric mod that lists the performance set the
  launcher installs.

## Features

| Area | What it does |
|---|---|
| Minecraft versions | Every version in Mojang's `version_manifest_v2`: releases, snapshots, old beta and old alpha. Modern, `virtual` (1.6) and `map_to_resources` (before 1.6) asset layouts |
| Mod loaders | Fabric, Quilt, Forge and NeoForge install and launch. The version list shows only versions that the chosen loader has builds for |
| Profiles | Each profile has its own game folder, Minecraft version, loader, memory limit, JVM arguments, Java path and wrapper command. List view and grid view, with groups and custom icons |
| Accounts | Microsoft sign-in with system credential storage. Credentials go to the system credential store, or to an encrypted file when there is none. Offline accounts for single player and LAN, only while a Microsoft account that owns the game is signed in |
| Skins | Skin and cape window: choose a PNG, 3D preview, templates, upload to Mojang for Microsoft accounts |
| Mods | Search Modrinth and CurseForge, filter by category, install with required dependencies, switch mods on and off, remove. Works with jars you add yourself. CurseForge needs an API key |
| Modpacks | Modrinth `.mrpack` and CurseForge modpack zips, from the catalogue or from a file. A pack becomes a new profile or replaces the current one. Removing a pack deletes only the files it wrote |
| Data packs | Per world. Install, switch on and off, remove, import a zip. Mods that a data pack needs are installed with it |
| Resource packs and shaders | Search, install, switch on and off, remove, import. For shaders the launcher detects Iris, OptiFine or Canvas and installs a matching build |
| Build files | A profile exports to one `.hexbuild` file (version, loader, settings, mods, packs, configs, and worlds if you choose) and imports as a new profile |
| Storage | A storage window shows what uses disk space. Safe mode deletes only files that no profile uses; Advanced mode lets you select anything, after a warning |
| Java | Finds installed runtimes and selects the version that Minecraft asks for. If none fits, it offers to download an Eclipse Temurin runtime |
| Updates | Checks the project's GitHub releases at start-up, on the Release or Nightly channel. Downloads only the changed parts when it can, then replaces the installed folder |
| Languages | English, Ukrainian, Russian, Polish, German, Spanish, French, Italian, Portuguese (Brazil), Turkish, Indonesian, Vietnamese, Hindi, Chinese (Simplified), Japanese, Korean. The language changes without a restart |
| While playing | The launcher hides to the notification area and comes back when the game closes |

## Install

Download a client from the [Releases](https://github.com/SAN4EZDREAMS/HexadronLauncher/releases)
page. Each client includes its own Java runtime, so you do not need Java installed.

| System | File | Start |
|---|---|---|
| Windows | `HexadronLauncher-windows.zip` | Unpack it and run `HexadronLauncher\HexadronLauncher.exe` |
| Linux | `HexadronLauncher-linux.tar.gz` | Unpack it and run `HexadronLauncher/bin/HexadronLauncher` |
| Linux (Flatpak) | `HexadronLauncher-linux.flatpak` | `flatpak install --user HexadronLauncher-linux.flatpak` |
| macOS | `HexadronLauncher-macos.tar.gz` | Unpack it and open `HexadronLauncher.app` |

The clients are not signed. Windows SmartScreen and macOS Gatekeeper can show a
warning the first time you start them.

Nightly builds are published as pre-releases. They get the newest changes and
are sometimes broken.

## Build

You need JDK 25 (Gradle can download it for you).

```
./gradlew :launcher:run            # start the launcher
./gradlew :launcher:build          # compile, check and build the jar
./gradlew :launcher:selfCheck      # check the launch core, no network needed
./gradlew :launcher:appImage       # packaged client for this system
./gradlew :mod:build               # build the mod
```

Add `--configure-on-demand` to build one module without configuring the other.
Every source file must keep its licence header; the build fails if one is
missing or changed. Details are in [docs/building.md](docs/building.md).

## Documentation

| Page | Content |
|---|---|
| [Interface](docs/interface.md) | Main window, list and grid views, groups, settings window, start-up screen |
| [Configuration](docs/configuration.md) | Data folder, launcher.json, Microsoft sign-in, CurseForge key, languages, command-line mode |
| [Skins and capes](docs/skins.md) | The skin window, 3D preview, skin and cape management |
| [Java and mod loaders](docs/java-and-loaders.md) | Java detection and download, loader compatibility, Forge and NeoForge installation |
| [Mods](docs/mods.md) | Content window, mod search, the Installed tab, dependencies, Hexadron Optimise |
| [Modpacks and packs](docs/packs.md) | Modpacks, data packs, resource packs, shaders |
| [Build files and storage](docs/builds-and-storage.md) | `.hexbuild` export and import, storage cleanup |
| [Updates](docs/updates.md) | Self-update, channels, delta updates, VirusTotal results |
| [Flatpak and sandboxing](docs/flatpak-and-sandboxing.md) | Flatpak permissions and build, running the game in a sandbox |
| [Building](docs/building.md) | Gradle tasks, CI workflows, packaged clients, icons, versions |
| [Architecture](docs/architecture.md) | Code layout, design rules, the `mod/` module |
| [Roadmap](ROADMAP.md) | Planned work |
| [Security](SECURITY.md) | How to report a vulnerability, and what the launcher protects |

## License

Source-available and noncommercial. You may use, copy, change and share the
launcher for free, and publish changed versions under the same terms. You may
not sell it or charge for access to it, and you may not remove the authorship
and licence notices. This is not an OSI-approved open-source licence. The full
terms are in [LICENSE.md](LICENSE.md).

Minecraft, the mod loaders, the mods and the Java runtimes that the launcher
downloads have their own licences; see LICENSE.md, section 8.

HexadronLauncher is not affiliated with Mojang Studios or Microsoft.

**NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
