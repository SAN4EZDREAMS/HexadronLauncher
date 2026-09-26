# HexadronLauncher roadmap

Last review: 2026-09-26, against commit fcfe2e8 (Beta 0.9.9.5). Items marked **Done** are kept for reference.

This plan lists what the launcher does not do yet. It is based on a comparison with 17 other Minecraft Java launchers (see [Sources](#sources)). Each item gives the problem, the plan, the condition for "done", and the code to start from. Paths under "Start from" are relative to `launcher/src/main/java/com/hexadron/launcher/` unless they start with `.github/`.

"Unique" means the review did not find the feature in any checked launcher. It is not a guarantee that no launcher has it.

## Legend

| Mark | Meaning |
|---|---|
| **P0** | Blocker. Some players cannot use the launcher without it |
| **P1** | High value. Other launchers have it, or users often ask for it |
| **P2** | Medium value |
| **P3** | Low value, or optional |
| **S / M / L** | Size estimate: days / one to two weeks / more |

## Summary

| # | Item | Priority | Size | Unique |
|---|---|---|---|---|
| 0.1 | Approved Microsoft sign-in | P0 | **Done** | |
| 1.1 | Crash analysis with fixes | P1 | **Done** | |
| 1.2 | Find the bad mod automatically (bisect) | P1 | M | Unique |
| 1.3 | Mod update check and bulk update | P1 | M | |
| 1.4 | Move mods with a version change in the instance dialog | P1 | S | Unique (full form) |
| 2.1 | Modpack update that keeps player configs | P1 | L | Unique |
| 2.2 | Instance snapshots and rollback | P1 | M | Unique |
| 2.3 | World backups | P1 | S | |
| 2.4 | Export as `.mrpack` and CurseForge zip | P1 | M | |
| 2.5 | Import from other launchers | P2 | M | |
| 2.6 | Help with blocked CurseForge mods | P2 | M | |
| 3.1 | Play with friends over the internet (P2P) | P2 | L | |
| 3.2 | Quick Play and desktop shortcuts | P2 | S | |
| 3.3 | Screenshot gallery | P3 | S | |
| 3.4 | Shared files between instances (hard links) | P2 | M | |
| 3.5 | Low free memory warning | P3 | S | |
| 4.1 | Safety check of local mods | P2 | M | |
| 4.2 | Lint the workflows in CI | P3 | S | |
| 4.3 | Sandbox turned on by the launcher | P3 | ? | |

## Features to keep

These are rare in other launchers. Do not remove them while you work on the items below.

- Mods for another Minecraft version are found before launch (`mods/VersionRanges.java`, Fabric/Quilt and Forge/NeoForge ranges). **Play** warns and offers **Launch anyway**. See [docs/mods.md](docs/mods.md).
- The storage window with **Safe** and **Advanced** modes. See [docs/builds-and-storage.md](docs/builds-and-storage.md).
- Delta self-update from a per-file manifest with SHA-256 hashes, and a VirusTotal report in the release notes. See [docs/updates.md](docs/updates.md).
- The dependency guard: the launcher asks before you switch off or remove a mod that other mods need.
- Shader loader detection (Iris, OptiFine, Canvas).
- The bug report window names the newest launcher log.
- Crash analysis with one-click fixes and signed rule updates (`crash/`, [docs/crashes.md](docs/crashes.md)).

---

## Phase 0: blocker

### 0.1 Approved Microsoft sign-in (P0) - Done

Mojang approved the application ID in `core/LauncherSettings.java` (`microsoftClientId`). A release build signs in a real Microsoft account (bought or through PC Game Pass) and plays on official servers. When the account cannot play, `auth/MicrosoftAuth.java` names the reason from the entitlements: no licence, Game Pass ended, only other Minecraft games, no profile name yet (see [docs/configuration.md](docs/configuration.md)).

---

## Phase 1: crashes and mod updates

### 1.1 Crash analysis with fixes (P1) - Done

Built as planned; see [docs/crashes.md](docs/crashes.md).

- After a crash the launcher reads the game output, `logs/latest.log`, the crash report and `hs_err_pid*.log` of that run (`crash/CrashEvidence.java`).
- `crash/rules.json` holds 32 rules for 17 causes, and 2 texts for the causes found by code, in all 16 languages: Java too old or too new, heap full, no system memory, heap not reserved, missing dependency, wrong dependency version, mod for another Minecraft version or loader, duplicate mod, incompatible mods, mixin error, mod crash during loading, damaged jar, missing OpenGL support, graphics driver crash, broken installation. Fabric, Forge (1.13 to 1.20.1 format) and NeoForge messages.
- One-click fixes, checked against the profile before they are offered: switch off a mod (with the mods that need it), keep the newest of duplicate jars, use Java N, let the launcher choose Java, raise or lower memory, check the game files (`crash/CrashFixes.java`).
- Rule updates: the release workflow publishes `hexadron-crash-rules.json` with an Ed25519 signature by the update key. A launcher uses it only when the signature is valid and its version is higher (`crash/CrashRuleSource.java`). The manifest is signed now too, so the note about hash-only checks no longer applies.
- A stack trace names the mod whose class threw the exception, found by the class file inside the jar (`crash/StackAttribution.java`). A game that went silent for 45 seconds before it was ended is reported as frozen.
- When no rule matches, the window links the crash report, the game logs and the bug report window. **Find the problem mod** joins them with **1.2**.

### 1.2 Find the bad mod automatically: bisect (P1, Unique)

**Problem.** With 100 or more mods, the player cannot find the mod that crashes the game. People switch off mods by hand, one at a time.

**Plan.**
1. The player selects **Find the problem mod**.
2. The launcher takes a snapshot of the mod set (see **2.2**).
3. It switches off half of the mods. Required dependencies stay with the mods that need them (`ModDependents`).
4. It starts the game and waits for a crash, or asks: "Did the problem occur?"
5. It keeps the half that fails and repeats. For 200 mods, about 8 launches are enough.
6. At the end, it names the mod, or the pair of mods, and restores the snapshot.
7. Offer it in the crash window (`ui/CrashDialog.java`) when no rule matches, and after a crash fix that did not help.

**Done when.** On a test pack with one known bad mod, bisect finds that mod, and the mod set is the same as before the test.

**Start from.** `mods/ModDependents.java`, `mods/ModScan.java`, `launch/GameLauncher.java`, `crash/CrashEvidence.java` (tells a crash from a clean exit).

### 1.3 Mod update check and bulk update (P1)

**Problem.** Prism, Modrinth App and CurseForge show available updates. This launcher records the platform and project of each mod it installs (`mods/ModLibrary.java`, `.hexadron-mods.json`), but it does not check for updates.

**Plan.**
1. Modrinth: send the SHA-1 hashes to `POST /v2/version_files/update` with the loader and game version of the instance. One request covers all mods. `ModrinthProvider` already calls `POST /v2/version_files` for hash lookups.
2. CurseForge: match files by fingerprint with `POST /v1/fingerprints`.
3. Show an **Update** badge per mod and an **Update all** button.
4. Take a snapshot before the update (**2.2**).
5. Apply the same check to resource packs and shaders.

**Done when.** An instance with old mods shows the correct number of updates, and **Update all** installs them with their dependencies.

**Start from.** `mods/ModrinthProvider.java`, `mods/CurseForgeProvider.java`, `mods/ModInstaller.java` (`migrateMods` already replaces each launcher-installed mod with the newest build for a version).

### 1.4 Move mods with a version change in the instance dialog (P1, Unique in full form)

**Problem.** The move itself exists: `LauncherService.moveToVersion` replaces each mod the launcher installed with a build for the new version, and switches off mods with no build. Hand-added jars that declare another version are switched off too. It is available as the command-line command `move <profile> <mcVersion>` and as the **Go back to {version}** button in the wrong-version warning. When the player changes the version in the instance dialog, the launcher only sets the version and reports the mods left behind. Modrinth App only asks the player to change mod versions (modrinth/code#228); the request for more (modrinth/code#5474) is still open.

**Plan.**
1. Before the change, show a table: mod, current version, version for the target Minecraft version, or **not available**.
2. On confirm: take a snapshot (**2.2**), then call `moveToVersion`.
3. Show the result (updated, switched off, kept) in the dialog.

**Done when.** An instance moves from one Minecraft version to another from the instance dialog, and the report of mods for another version (from `VersionRanges`) is empty after the move, apart from mods with no build for the target.

**Start from.** `core/LauncherService.java` (`moveToVersion`), `mods/ModInstaller.java` (`migrateMods`), `ui/ProfileDialog.java`, `ui/MainWindow.java` (`goBackToVersion`).

---

## Phase 2: modpacks and data safety

### 2.1 Modpack update that keeps player configs (P1, Unique)

**Problem.** A modpack update can overwrite the player's changes to configs, key bindings and `options.txt`. This is a frequent complaint: PrismLauncher#748 (open), #2918, #1501. No checked launcher solves it.

**Plan.**
1. The launcher already records the path of every file a modpack writes (`mods/InstalledModpack.java`). Also store the hash of each file as the pack installed it.
2. On update, compare three versions of each file: the old pack, the new pack, and the file on disk now.
   - The player did not change the file: take the new version.
   - The pack did not change the file: keep the player's version.
   - Both changed the file: merge `options.txt` by key and simple `.properties` / `.toml` / `.json` configs by key. For other files, show both versions and let the player choose.
3. Always keep the player's key bindings, FOV, sound volumes and language.

**Done when.** After a pack update, the player's key bindings and changed configs are the same as before, and the new pack files are in place.

**Start from.** `mods/ModpackInstaller.java`, `mods/InstalledModpack.java`, `mods/PackMeta.java`.

### 2.2 Instance snapshots and rollback (P1, Unique)

**Problem.** An install, an update or a pack change can break an instance. The player has no simple way to go back.

**Plan.**
1. Before each change (install, update, pack update, version change, bisect), save a snapshot: a list of files with hashes, and hard links to them. Hard links use almost no disk space.
2. Add **History** to the instance window: date, what changed, **Restore**.
3. Keep the last N snapshots. Show their size in the storage window.

**Done when.** After a bad update, **Restore** returns the instance to a state that starts, and the storage window shows the space the snapshots use.

**Start from.** `share/BuildExport.java` (already sorts an instance's files), `cleanup/StorageScanner.java`, `util/Hashes.java`.

### 2.3 World backups (P1)

**Problem.** ATLauncher and Nitrolaunch have world backups. A corrupt or griefed world is lost without one.

**Plan.**
1. **Back up** and **Restore** per world, as a zip in the data folder.
2. Optional automatic backup when the game closes; keep the last N.
3. Do not back up while the game runs (the files are open).

**Done when.** A backup restores a world that starts and has the same content.

**Start from.** `mods/WorldSaves.java`, `util/Archives.java`.

### 2.4 Export as `.mrpack` and CurseForge zip (P1)

**Problem.** Prism, Modrinth App and CurseForge export these formats. Without it, a player cannot publish a pack from this launcher. The launcher's own `.hexbuild` export already sorts an instance's files into files that can be downloaded again and the player's own files (see [docs/builds-and-storage.md](docs/builds-and-storage.md)).

**Plan.** Use the same sort to write `modrinth.index.json` or `manifest.json`. `mods/PackArchive.java` already reads both formats.

**Done when.** An exported pack imports into Prism and into Modrinth App and starts.

**Start from.** `share/BuildExport.java`, `share/BuildFormat.java`, `mods/PackArchive.java`.

### 2.5 Import from other launchers (P2)

**Problem.** Players who move from Prism, MultiMC, CurseForge or Modrinth App must build their instances again. Prism, ATLauncher, GDLauncher and Nitrolaunch can import from other launchers.

**Plan.** Find the other launchers' instance folders. Read `instance.cfg` and `mmc-pack.json` (Prism, MultiMC) and `minecraftinstance.json` (CurseForge). Make a profile, copy or hard-link the game folder, and keep the mod origins.

**Done when.** A Prism instance and a CurseForge instance import and start.

**Start from.** `share/BuildImport.java`, `profile/ProfileStore.java`.

### 2.6 Help with blocked CurseForge mods (P2)

**Problem.** Some mod authors do not allow downloads from third-party apps. The launcher first looks for the same file on Modrinth by SHA-1. If it is not there, the launcher names the mod and skips it, and the player must download it by hand. See [docs/packs.md](docs/packs.md).

**Plan.**
1. Open each blocked project's page in the system browser.
2. Watch the player's Downloads folder with a `WatchService`.
3. Match an arriving file by name and SHA-1 against what the pack asked for, and move it into the instance.

**Done when.** A pack with blocked mods installs completely after the player clicks the download button on each page.

**Start from.** `mods/ModpackInstaller.java` (`manualDownloads`), `ui/SystemBrowser.java`.

---

## Phase 3: multiplayer and convenience

### 3.1 Play with friends over the internet: P2P (P2)

**Problem.** Players want to play together without a server or port forwarding. HMCL and PCL CE use Terracotta (built on EasyTier), and players of the two launchers can join each other. XMCL has its own P2P mode. With P2P the players connect directly, so the launcher does not need its own relay server.

**Plan.**
1. Check the Terracotta licence before any work.
2. If it is compatible, use the Terracotta protocol, so players of this launcher can also join HMCL and PCL CE players.
3. The host opens the world to LAN in the game and gets a room code in the launcher. The guest enters the code.

**Done when.** Two computers on different networks play together with a room code.

### 3.2 Quick Play and desktop shortcuts (P2)

**Problem.** The official launcher and Prism 10 can start the game directly into a world or a server. Minecraft 1.20 and later accepts `--quickPlaySingleplayer <world>` and `--quickPlayMultiplayer <host:port>`. The launcher reads the quick-play rule features but sets all of them to false (`launch/LaunchCommandBuilder.java`), and it does not substitute `${quickPlayPath}` or the other quick-play placeholders.

**Plan.**
1. Set the quick-play features and placeholders when a world or server is chosen.
2. Add **Play this world** to the world list and **Join this server** for entries in `servers.dat`.
3. Add **Create desktop shortcut** (`.lnk` on Windows, `.desktop` on Linux, `.command` on macOS). The shortcut calls the launcher's command-line mode.

**Done when.** A desktop shortcut starts the game and loads the chosen world.

**Start from.** `launch/LaunchCommandBuilder.java`, `meta/Rule.java` (`Features`), `mods/WorldSaves.java`, `cli/HexadronCli.java`.

### 3.3 Screenshot gallery (P3)

Show `screenshots/` per instance: thumbnails, open, copy to clipboard, delete, open folder. SJMCL and Freesm have a similar feature.

### 3.4 Shared files between instances: hard links (P2)

**Problem.** The same mod or resource pack is often stored in many instances. XMCL links such files instead of copying them.

**Plan.** Store each downloaded file once, by hash, in the data folder. Put a hard link into each instance. The storage window shows the saved space. If the file system does not support hard links, copy the file.

**Done when.** Two instances with the same 100 mods use the space of one set.

**Start from.** `net/Downloader.java`, `cleanup/StorageScanner.java`.

### 3.5 Low free memory warning (P3)

Before launch, compare the instance memory limit with the free memory of the computer. Warn if the free memory is lower. Prism 11 has this. `profile/Profile.java` already reads the total memory for the default memory limit.

---

## Phase 4: safety and CI

### 4.1 Safety check of local mods (P2)

**Problem.** In June 2023 the "fractureiser" malware spread through mods on CurseForge and Bukkit. A player can also add jars from unknown sites.

**Plan.**
1. Hash every jar in the instance.
2. Check the hashes against a list of known bad hashes. Ship the list in the same way as the crash rules (**1.1**).
3. Optional: look up unknown hashes on VirusTotal with the player's own free API key. A hash lookup does not upload the file. The free API allows 4 requests per minute and 500 per day, so cache the results.

**Done when.** A test jar on the bad-hash list is flagged before launch.

**Start from.** `util/Hashes.java`, `mods/ModScan.java`, `.github/scripts/virustotal_scan.py` (API calls and rate limits).

### 4.2 Lint the workflows in CI (P3)

Add a small workflow that runs `actionlint` when `.github/**` changes. Today `build-launcher.yml` and `build-mod.yml` run only on changes to code, Gradle files and their own workflow file. A change to `release-launcher.yml`, `virustotal.yml` or `.github/scripts/` starts no check.

### 4.3 Sandbox turned on by the launcher (P3)

**Problem.** The launcher does not isolate the game by itself. What exists is the per-profile wrapper command (for example `bwrap` or `firejail`) and the Flatpak build. See [docs/flatpak-and-sandboxing.md](docs/flatpak-and-sandboxing.md).

A sandbox does not protect the session token, because the token is in the game's own memory and mods run in the same JVM. It protects the player's other files from a malicious mod. A strict sandbox can break the NVIDIA proprietary driver and controllers.

**Plan.** No design yet. Decide first whether a launcher-provided default is worth the breakage it can cause.

**Done when.** A decision is recorded here, and, if yes, a profile can turn on the sandbox without a hand-written wrapper command and still render with the GPU.

**Start from.** `profile/Profile.java` (`wrapperCommand`), `launch/LaunchCommandBuilder.java`.

---

## Sources

Launchers checked (September 2026): Prism Launcher, MultiMC, Modrinth App, CurseForge App, ATLauncher, GDLauncher Carbon, FTB App, X Minecraft Launcher, HMCL, PCL CE, SJMCL, SKLauncher, TLauncher, Lunar Client / Badlion, Dawn (was Feather Client), official launcher, Nitrolaunch.

- Crash support load and updatable crash rules: https://github.com/PrismLauncher/PrismLauncher/issues/2777
- Crash analysis misses a mod conflict: https://github.com/Meloong-Git/PCL/issues/7166
- Modpack update overwrites configs: https://github.com/PrismLauncher/PrismLauncher/issues/748 , https://github.com/PrismLauncher/PrismLauncher/issues/2918 , https://github.com/PrismLauncher/PrismLauncher/issues/1501
- Incompatible mods after a version change: https://github.com/modrinth/code/issues/5474 , https://github.com/modrinth/code/issues/228
- Terracotta P2P in HMCL: https://github.com/HMCL-dev/HMCL/pull/4215 , https://github.com/burningtnt/Terracotta
- XMCL (P2P, hard links): https://github.com/Voxelum/x-minecraft-launcher
- Prism releases (Quick Play shortcuts in 10, low memory warning in 11): https://github.com/PrismLauncher/PrismLauncher/releases
- SKLauncher (delta updates): https://skmedix.pl/
- ATLauncher (world backups): https://github.com/ATLauncher/ATLauncher
- Nitrolaunch (backups, migration): https://github.com/Nitrolaunch/nitrolaunch
- SJMCL: https://github.com/UNIkeEN/SJMCL
- Freesm: https://github.com/FreesmTeam/FreesmLauncher
- VirusTotal public API limits: https://docs.virustotal.com/reference/public-vs-premium-api
