# Java and mod loaders

This page explains how the launcher finds, selects and downloads Java, how the loader choice limits the Minecraft version list, and how Forge and NeoForge are installed.

## Java

### Which version a profile needs

The launcher reads `javaVersion.majorVersion` from Mojang's version manifest. If there is no `javaVersion` block, the version needs Java 8. A loader manifest inherits the value from its vanilla parent. The runtime is selected for each launch, so profiles that need different Java versions can run on the same computer.

The required version is stored in the profile as `javaMajor` (in `profiles.json`) when the version is installed.

### Where it looks

`JavaLocator.discover()` searches in this order:

1. `java/` in the data folder (runtimes the launcher downloaded).
2. `JAVA_HOME`.
3. The JVM that runs the launcher, if it has `bin/java`.
4. Each directory on `PATH`. This finds runtimes from winget, scoop, Homebrew, apt and sdkman.
5. Common install folders for each OS, for example Program Files vendor folders, `/Library/Java/JavaVirtualMachines`, `/usr/lib/jvm`, `~/.jdks`, `~/.sdkman` and `~/.gradle/jdks`.
6. The official launcher's `.minecraft/runtime` folder.
7. On Windows, the vendor keys in the registry.

The search does not scan the whole disk. The version comes from the runtime's `release` file, or from `java -version` (15-second timeout). **Detect** in the main window writes the list to the log.

### How it selects a runtime

`JavaRuntimes.resolve` makes the decision for launching and for the Forge/NeoForge installers:

1. **Profile setting.** The **Java** field in the profile editor (`javaPath`): a `java` executable or a Java home. If it does not work or is too old, the launcher logs a warning and ignores it.
2. **Exact match.** An installed runtime with the required major version.
3. **Download.** If there is no exact match, the launcher offers a download, even when a newer runtime is installed. Old modded versions often crash on a newer JVM.
4. **Fallback.** Otherwise, the lowest installed version that is newer than required, with a warning in the log.
5. If nothing is new enough, the launch stops and the message lists the runtimes found.

### Before a modpack or build installs

When you install a modpack or import a `.hexbuild`, `LauncherService.settleJava` checks the required Java before it downloads any mods. So the download question comes first, not after the pack is downloaded. If you decline, or Eclipse Adoptium cannot be reached, the pack still installs and the launcher asks again when you click Play. A profile with its own Java path skips the question.

### Downloading a runtime

The launcher downloads **Eclipse Temurin** from the Eclipse Adoptium API. It does not use Mojang's runtime downloads, because that service is undocumented and has no licence for redistribution. Temurin is OpenJDK under GPLv2 with the Classpath Exception.

`javaDownloadPolicy` in `launcher.json` (**Settings > Java > When Java is missing**) controls this:

| Value | Label | Behaviour |
|---|---|---|
| `ask` (default) | Ask each time | Shows a dialog with the release and size |
| `always` | Download it | Downloads without asking |
| `never` | Never download it | No download. The error says which Java to install. |

In the dialog, **Download Java {n}** downloads once, and the checkbox **Download future Java versions automatically, without asking** also sets `always`. **Not now** skips this time. **Never ask again** sets `never`.

Command-line mode cannot show the dialog, so `ask` means no download there. Use the `java <major>` command instead (see [configuration.md](configuration.md)).

`JavaProvisioner` prefers a JRE to a JDK, and on `aarch64` also tries `x64` builds. It checks the SHA-256 that Adoptium publishes, unpacks to a temporary folder, and checks that the new `java` reports the expected version. Then it moves the runtime to `java/temurin-<major>-<os>-<arch>/` and writes a `.hexadron-runtime.json` marker. The archive's `legal/` folder is kept. If an old copy is in use, nothing changes and you are told to close Minecraft. Nothing outside the data folder changes.

### Removing unused runtimes

One downloaded runtime serves every profile that needs that major version. When you delete a profile, the launcher deletes each downloaded runtime that no remaining profile needs (`LauncherService.javaMajorsInUse`). If it cannot tell which Java a remaining profile needs, it deletes nothing. It deletes only folders with the marker, never Java that you installed yourself. The storage window can also remove unused runtimes (see [builds-and-storage.md](builds-and-storage.md)).

### Packaged clients

The `appImage` task omits `--strip-native-commands` from jpackage's jlink options, so the bundled Java 25 runtime keeps `bin/java` and can launch versions that need Java 25. See [building.md](building.md).

The launch wrapper (`launcher/src/wrapper/`) runs inside the game's JVM, so it is compiled for Java 8 (`options.release = 8`).

## Version and loader compatibility

When you select a loader, the Minecraft version list shows only versions that the loader supports:

| Loader | Source | Filters the list |
|---|---|---|
| Fabric, Quilt | `/versions/game` on their meta APIs (versions with intermediary mappings) | Yes |
| Forge | Forge maven metadata. A build id is `<minecraftVersion>-<forgeVersion>`. | Yes |
| NeoForge | Derived from build numbers | No |

NeoForge build numbers encode the Minecraft version: `21.1.66` is 1.21.1, and `26.1.2.97` is 26.1.2. Minecraft 1.20.1 builds are in the separate `net.neoforged:forge` artifact, with versions such as `1.20.1-47.1.106`. The launcher reads both artifacts. It does not hide versions for NeoForge, because the numbering scheme has changed before. If a version has no NeoForge build, the build list says so.

If you do not choose a build, the launcher uses Forge's recommended build (else its latest), the newest NeoForge build without `beta` or `alpha`, or the first stable Fabric or Quilt build.

Some published builds cannot be installed, so the picker leaves them out: Forge `1.12.2-14.23.5.2851` (its installer has `"data": []`), six Forge 1.6.x builds, and NeoForge `47.1.82` and `1.20.1-47.1.7` (listed, but no file exists).

## Installing Forge and NeoForge

Fabric and Quilt publish a complete launcher profile, so the install is one JSON download. Forge ships its changes as a binary diff against the vanilla client jar, because a patched Minecraft jar may not be redistributed. The diff is applied on the user's computer.

The installer's `install_profile.json` lists *processors*: separate Java programs that turn the vanilla jar into the jar Forge launches. NeoForge uses the same format, so `install/loader/forge/` handles both:

| Class | Job |
|---|---|
| `InstallProfile` | Reads `install_profile.json` |
| `ForgeProcessor` | One step: jar, classpath, arguments, expected outputs |
| `Tokens` | The argument syntax: `{KEY}`, `'literal'`, `[maven:coordinate]` |
| `ProcessorRunner` | Runs the steps and checks their outputs |
| `ForgeStyleInstaller` | The complete install |

The format comes from the keys in the file, not from the Minecraft version:

- **Legacy** (`install` and `versionInfo`, up to about 1.12.2): no processors. The loader jar is copied out of the installer and `versionInfo` becomes the version manifest.
- **Modern** (`spec`): a processor chain patches the client jar. For 1.13 to 1.20 the chain is long (mappings, jar split, remap, patch). Current builds have a short chain.

A modern install copies the installer's embedded `maven/` files into the library folder, downloads the processor libraries, and runs the processors on a copy of the vanilla jar.

Processor rules:

- **Each step runs in its own JVM** (`-Xmx1536M`, temporary working folder). These programs call `System.exit` and change the classloader, so they cannot run inside the launcher. The JVM is the exact major that the vanilla manifest names. The 1.13-1.16 remapper behaves differently on Java versions newer than 8.
- **A step whose outputs already have the expected SHA-1 is skipped**, so a reinstall or repair is fast.
- **A different hash on a valid archive is accepted.** A JVM with a different compression library makes a different but valid jar. A `.jar` or `.zip` that opens is kept, with a note in the log. Other mismatches delete the file and stop the install.
- **A failed step** reports its exit code and the last 40 lines of its output.
- **The version manifest is written last**, so an interrupted install cannot be launched.

### Launch fixes for Forge

- **`ignoreList`** (`LaunchCommandBuilder.repairIgnoreList`). From 1.17, Forge's `BootstrapLauncher` makes each classpath jar a module unless `-DignoreList=` names it. Forge expects the game jar under the loader's version id. This launcher shares one `versions/<mc>/<mc>.jar`, so it adds that file name to the list. Without this, the launch fails with `ResolutionException: Module minecraft contains package net.minecraft.server`.
- **Pre-1.13 arguments** (`VersionJson.merge`). A loader's `minecraftArguments` string replaces the parent's. If it were appended, LaunchWrapper would stop with `Found multiple arguments for option gameDir`.

### Tested builds

`./gradlew :launcher:selfCheck` tests parsing and merge rules, but not a real install. These builds were installed and launched end to end:

| Loader | Minecraft | Tests |
|---|---|---|
| Forge 14.23.5.2859 | 1.12.2 | Legacy format, LaunchWrapper and `--tweakClass`, Java 8 |
| Forge 47.4.10 | 1.20.1 | Long processor chain, `BootstrapLauncher` |
| NeoForge 1.20.1-47.1.106 | 1.20.1 | The `net.neoforged:forge` artifact |
| NeoForge 26.1.2.97 | 26.1.2 | One processor, calendar versions, Java 25 |
