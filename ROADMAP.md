# HexadronLauncher roadmap

Last review: 2026-09-24.

This plan compares HexadronLauncher with 17 other Minecraft Java launchers. It
lists what the launcher does not have yet, and what no checked launcher has.
Each item gives the problem, the plan, the condition for "done", and the code
to start from. The **Sources** section at the end gives the evidence.

"Not found in any checked launcher" means exactly that: the review did not find
it. It is not a guarantee that nobody has it.

## Legend

| Mark | Meaning |
|---|---|
| **P0** | Blocker. Some players cannot use the launcher without it |
| **P1** | High value. Other launchers have it, or users often ask for it |
| **P2** | Medium value |
| **P3** | Low value, or optional |
| **S / M / L** | Size estimate: days / one to two weeks / more |
| **Unique** | Not found in any checked launcher |

## Summary

| # | Item | Priority | Size | Unique |
|---|---|---|---|---|
| 0.1 | Approved Microsoft sign-in | P0 | S (waiting time is long) | |
| 1.1 | Crash analysis with fixes | P1 | M | |
| 1.2 | Find the bad mod automatically (bisect) | P1 | M | Unique |
| 1.3 | Mod update check and bulk update | P1 | M | |
| 1.4 | Move an instance to another Minecraft version | P1 | M | Unique (full form) |
| 2.1 | Modpack update that keeps player configs | P1 | L | Unique |
| 2.2 | Instance snapshots and rollback | P1 | M | Unique |
| 2.3 | World backups | P1 | S | |
| 2.4 | Export as `.mrpack` and CurseForge zip | P1 | M | |
| 2.5 | Import from other launchers | P2 | M | |
| 2.6 | Help with blocked CurseForge mods | P2 | M | |
| 3.1 | Play with friends over the internet (P2P) | P2 | L | |
| 3.2 | Desktop shortcuts to a world or a server | P2 | S | |
| 3.3 | Screenshot gallery | P3 | S | |
| 3.4 | Shared files between instances (hard links) | P2 | M | |
| 3.5 | Low free memory warning | P3 | S | |
| 4.1 | Safety check of local mods | P2 | M | |
| 4.2 | Lint the workflows in CI | P3 | S | |

## What the launcher already does well

Keep these. They are rare or not found in other launchers:

- **Mods for another version, found before launch.** `VersionRanges` reads the
  Fabric, Quilt, Forge and NeoForge ranges and warns before **Play**.
  Modrinth App users still ask for this (modrinth/code#5474).
- **Storage window** with Safe and Advanced modes.
- **Delta self-update** by manifest and SHA-256. Only SKLauncher has a
  similar feature.
- **VirusTotal report** in every release and pre-release.
- **Dependency guard**: the launcher asks before you disable or remove a mod
  that other mods need.
- **Shader loader detection** (Iris, OptiFine, Canvas).
- **Bug report window** that names the newest log file.

---

## Phase 0: blocker

### 0.1 Approved Microsoft sign-in (P0)

**Problem.** Microsoft sign-in is in the code, but it needs an Azure client ID
that Mojang approves (see README, "Microsoft sign-in"). Until Mojang approves
it, owners of the game cannot sign in. Every large launcher has this.

**Plan.**
1. Register the Azure application in the consumers tenant (README, steps 1-3).
2. Send the application to Mojang for approval.
3. Put the approved ID into the release build the same way as
   `CURSEFORGE_API_KEY`: from a repository secret, not from the source.

**Done when.** A release build signs in a real Microsoft account and starts
the game online.

**Start from.** `auth/MicrosoftAuth.java`, `microsoftClientId` in
`launcher.json`.

---

## Phase 1: crashes and mod updates

### 1.1 Crash analysis with fixes (P1)

**Problem.** Most support questions are about crashes. The Prism maintainers
say this in PrismLauncher#2777 (high priority, still open). HMCL and PCL CE
have crash analysis. PCL CE still shows "Unknown" for some mod conflicts
(Meloong-Git/PCL#7166).

**Plan.**
1. After the game stops with an error, read `crash-reports/`, `logs/latest.log`
   and `hs_err_pid*.log`.
2. Match them against a rule file. Each rule has a pattern, a plain-language
   cause and a fix.
3. Ship the rule file as a **signed JSON asset in the GitHub releases**, in the
   same way as the update manifest. The launcher can then get new rules
   without a new launcher release. PrismLauncher#2777 asks for this.
4. Give one-click fixes where they are safe: disable mod X, choose Java N,
   raise the memory limit.
5. When no rule matches, offer **1.2** (bisect) and the bug report window.

**Done when.** The launcher explains the ten most common crash types in plain
language (wrong Java, not enough memory, missing dependency, mod for another
version, duplicate mod, mixin conflict, missing graphics driver features, and
so on), in all five languages.

**Start from.** `launch/GameLauncher.java` (exit code), `core/LauncherLog.java`,
`ui/ReportBugDialog.java`, `update/ImageManifest.java` (signed download
pattern).

### 1.2 Find the bad mod automatically: bisect (P1, Unique)

**Problem.** With 100 or more mods, the player cannot find the mod that crashes
the game. People disable mods by hand, one at a time.

**Plan.**
1. The player selects **Find the problem mod**.
2. The launcher takes a snapshot of the mod set (see **2.2**).
3. It disables half of the mods. Required dependencies stay with the mods that
   need them (use `ModDependents`).
4. It starts the game and waits for a crash, or asks: "Did the problem occur?"
5. It keeps the half that fails and repeats. For 200 mods, about 8 launches
   are enough.
6. At the end, it names the mod, or the pair of mods, and restores the
   snapshot.

**Done when.** On a test pack with one known bad mod, bisect finds that mod,
and the mod set is the same as before the test.

**Start from.** `mods/ModDependents.java`, `mods/ModScan.java`,
`launch/GameLauncher.java`.

### 1.3 Mod update check and bulk update (P1)

**Problem.** Prism, Modrinth App and CurseForge show available updates. This
launcher records the Modrinth or CurseForge origin of each mod, but it does not
check for updates.

**Plan.**
1. Modrinth: send the SHA-1 hashes to `POST /v2/version_files/update` with the
   loader and game version of the instance. One request covers all mods.
2. CurseForge: match files by fingerprint with `POST /v1/fingerprints`.
3. Show an **Update** badge per mod and an **Update all** button.
4. Take a snapshot before the update (**2.2**).
5. Apply the same check to resource packs and shaders.

**Done when.** An instance with old mods shows the correct number of updates,
and **Update all** installs them with their dependencies.

**Start from.** `mods/ModrinthProvider.java`, `mods/CurseForgeProvider.java`,
`mods/ModOrigin.java`, `mods/ModInstaller.java`.

### 1.4 Move an instance to another Minecraft version (P1, Unique in full form)

**Problem.** When the player changes the Minecraft version, the launcher warns
about mods for another version. It does not find the correct versions of those
mods. Modrinth App only asks the player to change mod versions
(modrinth/code#228). The request for more (modrinth/code#5474) is still open.

**Plan.**
1. Before the change, show a table: mod, current version, version for the
   target Minecraft version, or **not available**.
2. Use the same API calls as **1.3**, with the target game version.
3. On confirm: take a snapshot, change the version, replace the mods, and
   disable the mods that have no version for the target.

**Done when.** An instance moves from one Minecraft version to another, and
the report of mods for another version (from `VersionRanges`) is empty after
the move.

**Start from.** `mods/VersionRanges.java`, `ui/ProfileDialog.java`.

---

## Phase 2: modpacks and data safety

### 2.1 Modpack update that keeps player configs (P1, Unique)

**Problem.** A modpack update can overwrite the player's changes to configs,
key bindings and `options.txt`. This is a frequent complaint:
PrismLauncher#748 (open), #2918, #1501. No checked launcher solves it.

**Plan.**
1. The launcher already records every file that a modpack writes. Also store
   the hash of each file as the pack installed it.
2. On update, compare three versions of each file: the old pack, the new pack,
   and the file on disk now.
   - The player did not change the file: take the new version.
   - The pack did not change the file: keep the player's version.
   - Both changed the file: merge `options.txt` by key and simple
     `.properties` / `.toml` / `.json` configs by key. For other files, show
     both versions and let the player choose.
3. Always keep the player's key bindings, FOV, sound volumes and language.

**Done when.** After a pack update, the player's key bindings and changed
configs are the same as before, and the new pack files are in place.

**Start from.** `mods/ModpackInstaller.java`, `mods/InstalledModpack.java`,
`mods/PackMeta.java`.

### 2.2 Instance snapshots and rollback (P1, Unique)

**Problem.** An install, an update or a pack change can break an instance.
The player has no simple way to go back.

**Plan.**
1. Before each change (install, update, pack update, version change, bisect),
   save a snapshot: a list of files with hashes, and hard links to them.
   Hard links use almost no disk space.
2. **History** in the instance window: date, what changed, **Restore**.
3. Keep the last N snapshots. Show their size in the storage window.

**Done when.** After a bad update, **Restore** returns the instance to a state
that starts, and the storage window shows the space the snapshots use.

**Start from.** `profile/ProfileLayout.java`, `cleanup/StorageScanner.java`,
`util/Hashes.java`.

### 2.3 World backups (P1)

**Problem.** ATLauncher and Nitrolaunch have world backups. A corrupt or
griefed world is lost without one.

**Plan.**
1. **Back up** and **Restore** per world, as a zip in the data folder.
2. Optional automatic backup when the game closes, and keep the last N.
3. Do not back up while the game runs (the files are open).

**Done when.** A backup restores a world that starts and has the same
content.

**Start from.** `mods/WorldSaves.java`, `util/Archives.java`.

### 2.4 Export as `.mrpack` and CurseForge zip (P1)

**Problem.** This is in README, "Not done yet". Prism, Modrinth App and
CurseForge export these formats. Without it, a player cannot publish a pack
from this launcher.

**Plan.** Use the file sort that `.hexbuild` already does (platform files and
the player's own files). Write `modrinth.index.json` or `manifest.json` from
it.

**Done when.** An exported pack imports into Prism and into Modrinth App and
starts.

**Start from.** `share/BuildExport.java`, `share/BuildFormat.java`.

### 2.5 Import from other launchers (P2)

**Problem.** Players who want to move from Prism, MultiMC, CurseForge or
Modrinth App must build their instances again. Prism, ATLauncher, GDLauncher
and Nitrolaunch can import from other launchers.

**Plan.** Find the other launchers' instance folders. Read `instance.cfg` and
`mmc-pack.json` (Prism, MultiMC) and `minecraftinstance.json` (CurseForge).
Make a profile, copy or hard-link the game folder, and keep the mod origins.

**Done when.** A Prism instance and a CurseForge instance import and start.

**Start from.** `share/BuildImport.java`, `profile/ProfileStore.java`.

### 2.6 Help with blocked CurseForge mods (P2)

**Problem.** Some mod authors do not allow downloads from third-party apps.
Today the launcher names these mods and skips them. README, "Not done yet",
already describes the plan.

**Plan.** Open each blocked project's page. Watch the Downloads folder with a
`WatchService`. Match a new file by name and SHA-1, then move it into the
instance.

**Done when.** A pack with blocked mods installs completely after the player
clicks the download buttons on each page.

**Start from.** `mods/ModpackInstaller.java`, `ui/SystemBrowser.java`.

---

## Phase 3: multiplayer and convenience

### 3.1 Play with friends over the internet: P2P (P2)

**Problem.** Players want to play together without a server or port
forwarding. HMCL and PCL CE use Terracotta (built on EasyTier), and players of
the two launchers can join each other. XMCL has its own P2P mode. With P2P,
the players connect directly, so the launcher does not need
its own relay server to keep online.

**Plan.**
1. Check the Terracotta licence before any work.
2. If it is compatible, use the Terracotta protocol, so players of this
   launcher can also join HMCL and PCL CE players.
3. The host opens the world to LAN in the game and gets a room code in the
   launcher. The guest enters the code.

**Done when.** Two computers on different networks play together with a room
code.

### 3.2 Desktop shortcuts to a world or a server (P2)

**Problem.** The official launcher and Prism 10 can start the game directly
into a world or a server. Minecraft 1.20 and later accepts
`--quickPlaySingleplayer <world>` and `--quickPlayMultiplayer <host:port>`.
The launcher already substitutes `${quickPlayPath}`, but it does not use these
options.

**Plan.** Add **Play this world** / **Join this server** to the world list and
to `servers.dat`. Add **Create desktop shortcut** (`.lnk` on Windows,
`.desktop` on Linux, `.command` on macOS). The shortcut calls the launcher CLI.

**Done when.** A desktop shortcut starts the game and loads the chosen world.

**Start from.** `launch/LaunchCommandBuilder.java`, `cli/HexadronCli.java`.

### 3.3 Screenshot gallery (P3)

Show `screenshots/` per instance: thumbnails, open, copy to clipboard, delete,
open folder. SJMCL and Freesm have a similar feature.

### 3.4 Shared files between instances: hard links (P2)

**Problem.** The same mod or resource pack is often stored in many instances.
XMCL links such files instead of copying them.

**Plan.** Store each downloaded file once, by hash, in the data folder. Put a
hard link into each instance. The storage window shows the saved space. If the
file system does not support hard links, copy the file.

**Done when.** Two instances with the same 100 mods use the space of one set.

**Start from.** `net/Downloader.java`, `cleanup/StorageScanner.java`.

### 3.5 Low free memory warning (P3)

Before launch, compare the instance memory limit with the free memory of the
computer. Warn if the free memory is lower. Prism 11 has this.

---

## Phase 4: safety and CI

### 4.1 Safety check of local mods (P2)

**Problem.** In June 2023, the "fractureiser" malware spread through mods on
CurseForge and Bukkit. A player can also add jars from unknown sites.

**Plan.**
1. Hash every jar in the instance.
2. Check the hashes against a list of known bad hashes. Ship the list in the
   same way as the crash rules (**1.1**).
3. Optional: look up unknown hashes on VirusTotal with the player's own free
   API key. A hash lookup does not upload the file. The free API allows 4
   requests per minute and 500 per day, so cache the results.

**Done when.** A test jar on the bad-hash list is flagged before launch.

**Start from.** `util/Hashes.java`, `mods/ModScan.java`,
`.github/scripts/virustotal_scan.py` (API calls and rate limits).

### 4.2 Lint the workflows in CI (P3)

Add a small workflow that runs `actionlint` when `.github/**` changes. Today
changes to the workflows start no workflow, because the build workflows use
`paths` filters for code only.

---

## Sources

Launchers checked (September 2026): Prism Launcher, MultiMC, Modrinth App,
CurseForge App, ATLauncher, GDLauncher Carbon, FTB App, X Minecraft Launcher,
HMCL, PCL CE, SJMCL, SKLauncher, TLauncher, Lunar Client / Badlion,
Dawn (was Feather Client), official launcher, Nitrolaunch.

- Crash support load and updatable crash rules:
  https://github.com/PrismLauncher/PrismLauncher/issues/2777
- Crash analysis misses a mod conflict:
  https://github.com/Meloong-Git/PCL/issues/7166
- Modpack update overwrites configs:
  https://github.com/PrismLauncher/PrismLauncher/issues/748 ,
  https://github.com/PrismLauncher/PrismLauncher/issues/2918 ,
  https://github.com/PrismLauncher/PrismLauncher/issues/1501
- Incompatible mods after a version change:
  https://github.com/modrinth/code/issues/5474 ,
  https://github.com/modrinth/code/issues/228
- Terracotta P2P in HMCL: https://github.com/HMCL-dev/HMCL/pull/4215 ,
  https://github.com/burningtnt/Terracotta
- XMCL (P2P, hard links): https://github.com/Voxelum/x-minecraft-launcher
- Prism releases (Quick Play shortcuts in 10, low memory warning in 11):
  https://github.com/PrismLauncher/PrismLauncher/releases
- SKLauncher (delta updates): https://skmedix.pl/
- ATLauncher (world backups): https://github.com/ATLauncher/ATLauncher
- Nitrolaunch (backups, migration): https://github.com/Nitrolaunch/nitrolaunch
- SJMCL: https://github.com/UNIkeEN/SJMCL
- Freesm: https://github.com/FreesmTeam/FreesmLauncher
- VirusTotal public API limits:
  https://docs.virustotal.com/reference/public-vs-premium-api
