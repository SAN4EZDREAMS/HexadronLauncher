# HexadronLauncher

A Minecraft launcher and an umbrella performance mod, in one repository.

- `launcher/` - the launcher.
- `mod/` - Hexadron Optimise, a Fabric mod that groups a performance set.

## What the launcher does today

| Area | State |
|---|---|
| Minecraft versions | Every version in Mojang's `version_manifest_v2` - releases, snapshots, old_beta, old_alpha |
| Loaders | Fabric and Quilt install and launch. Forge and NeoForge list their builds but do not install yet |
| Accounts | Offline accounts work. Microsoft sign-in is implemented and needs an approved Azure client ID |
| Profiles | Each profile has its own game folder, Minecraft version, loader, memory limit, JVM arguments and Java path |
| Mods | A browser window per instance: search, sort, install and remove, filtered to that instance's version and loader. Modrinth needs no key; CurseForge needs one. Required dependencies resolve automatically |
| Java | The launcher finds the installed runtimes and selects one that the version requires |
| Assets | Modern, `virtual` (1.6) and `map_to_resources` (pre-1.6) layouts |
| Languages | English, Ukrainian, Russian, Polish, German. The picker changes the window immediately, without a restart |
| Interface | Searchable instance list, read-only instance summary, one Play button. Instances are edited in a dialog with Save and Cancel |
| While playing | The launcher hides to the notification area and returns by itself when the game closes |

## Build

You need JDK 25.

```
./gradlew :launcher:build      # build the launcher
./gradlew :launcher:selfCheck  # verify the launch core, no network needed
./gradlew :launcher:run        # start the window
./gradlew :mod:build           # build the mod
```

Add `--configure-on-demand` to keep one subproject out of the other's way:
without it Gradle configures every project, so an error in `mod/build.gradle`
also fails `:launcher:build`. Both CI workflows pass the flag.

To start `runClient` with the whole performance set loaded:

```
./gradlew :mod:runClient -Phexadron.devMods=true
```

That set is off by default, so a build of our own code does not depend on the
Modrinth API being up or on seven third-party builds still existing.

## Headless use

The launcher has a command-line mode. Use it to test an install without a display.

```
./gradlew :launcher:cli --args="versions"
./gradlew :launcher:cli --args="create Hexadron 26.2 fabric"
./gradlew :launcher:cli --args="offline Steve"
./gradlew :launcher:cli --args="install <profile-id>"
./gradlew :launcher:cli --args="mods <profile-id>"
./gradlew :launcher:cli --args="play <profile-id> Steve"
```

## Data folder

The launcher keeps all data in one folder:

- Windows: `%APPDATA%\.hexadronlauncher`
- macOS: `~/Library/Application Support/hexadronlauncher`
- Linux: `~/.local/share/hexadronlauncher`

Libraries, assets and client jars are shared between profiles. Each profile has
its own folder under `instances/`, so mods and worlds stay separate.

## Configuration

`launcher.json` in the data folder holds the launcher settings.

| Key | Purpose |
|---|---|
| `language` | Interface language: `en`, `uk`, `ru`, `pl`, `de`. Empty follows the operating system |
| `minimiseToTrayWhilePlaying` | Hide the window to the notification area while the game runs. `true` by default |
| `microsoftClientId` | Azure application ID for Microsoft sign-in. Empty by default |
| `curseForgeApiKey` | CurseForge API key. Empty by default. Modrinth needs no key |
| `showAllVersions` | Show snapshots and old versions in the version list |
| `downloadConcurrency` | Number of files to download at the same time |
| `keepOpenWhilePlaying` | Keep the launcher window open while the game runs |

### Microsoft sign-in

Mojang requires each launcher to use its own Azure application, and Mojang must
approve that application. Do these steps in order:

1. Register an application in the Azure portal. Select the public client flow.
2. Apply to Mojang for approval of the application ID.
3. Put the application ID into `launcher.json` as `microsoftClientId`.

The launcher uses the OAuth device code flow. It never receives the user's
Microsoft password. Until the application is approved, `login_with_xbox` returns
HTTP 403 and the launcher reports this.

Offline accounts need none of this. An offline account uses the same UUID that a
Minecraft server calculates in offline mode, so worlds keep the same player data.

### CurseForge

CurseForge requires an API key for every request. Some authors disable
third-party downloads. For those mods the API returns no download URL. The
launcher reports the mod and asks you to download it by hand. It does not try to
bypass the restriction.

## The mod

`mod/` builds `hexadron-optimise`. It does not implement optimisations itself. It
declares a dependency on a set of proven performance mods, and the launcher
installs that set.

The set is defined in one place that is easy to edit:
`launcher/src/main/resources/packs/hexadron-optimise.json`. That file is the
authoritative list; the launcher reads it at runtime, so changing the set needs
no rebuild. `mod/build.gradle` repeats the same ids only for the development
environment, behind `-Phexadron.devMods=true`.

Minecraft 26.1 is the first unobfuscated version. From it on the
`net.fabricmc.fabric-loom` plugin does no remapping, so `modImplementation`,
`modCompileOnly` and `remapJar` no longer exist - `implementation`,
`compileOnly` and `jar` replace them. The `mod*` names survive only in
`net.fabricmc.fabric-loom-remap`, which targets 1.21.11 and older.

The mods are not bundled into the mod jar with `include`. Bundling other
people's mods is a licensing decision, not a build setting. The launcher
downloads them from Modrinth instead.

## The window

```
+--------------------------------------------------------------+
| H  HexadronLauncher   [ search ]              Language: [ v ] |
+---------------------+----------------------------------------+
| Instances           |  Instance name                          |
|  My world           |  fabric-loader-0.19.3-26.2              |
|  26.2 · Fabric      |  +----------------------------------+   |
|  ...                |  | Minecraft   26.2                 |   |
|                     |  | Loader      Fabric · 0.19.3      |   |
|                     |  | Memory      4096 MB              |   |
|                     |  | Java        Detected automatically|  |
|                     |  | Last played 16 Aug 2026, 14:47   |   |
|                     |  | Folder      ...\instances\1-027f96|  |
|                     |  +----------------------------------+   |
| [New][Edit][Remove] |  [Edit] [Install] [Mods...] [Folder]    |
|                     |  Mods (5)                               |
|                     |   Sodium        [Hexadron Optimise]     |
|                     |   Jade          [your choice]           |
+---------------------+----------------------------------------+
| Account: [ v ] [Add offline] [Sign in]              [ PLAY ]  |
| Ready                                                         |
| [=========================================================]   |
| > Log                                                         |
+--------------------------------------------------------------+
```

Nothing in the middle panel is an input. An instance is changed in a dialog
reached by the Edit button or by double-clicking the list entry, and that dialog
has a Save and a Cancel. The previous window edited the selected profile's
fields in place, which meant a mistyped name was written to disk before it could
be noticed and there was no point at which the values could be checked together.
Prism Launcher and MultiMC edit instances the same way.

The instance list is searchable by name, Minecraft version or loader. The Play
button never moves: it sits in the footer next to the account, so the one action
the launcher exists for is always in the same place.

## The mod browser

`Mods...` opens a window of its own for the selected instance. It is not a
dialog: choosing mods is a long, back-and-forth task, and a modal window would
hold the launcher hostage for as long as it took.

```
+--------------------------------------------------------------+
| My world                          [ Install Hexadron Optimise]|
| 26.2 · Fabric                                                 |
+--------------------------------------------------------------+
| [ Browse ] [ Installed (5) ]                                  |
|  [ search .......... ] [ Most popular v ] [ All sources v ]   |
|  Sodium                    Modrinth · 40.1M downloads         |
|  A modern rendering engine ...                    [ Install ] |
|  ...                                                          |
+--------------------------------------------------------------+
| Searching...                                                  |
+--------------------------------------------------------------+
```

Every result is already filtered to the instance's Minecraft version and
loader, so anything listed is a build that will actually load. That is the
point of browsing from inside a launcher rather than on a website. Sorting is
by best match, downloads, popularity, last update or newest; the source filter
picks one platform or both.

The **Installed** tab lists what the launcher put in the folder, with a badge
saying where each file came from. The instance summary in the main window shows
the same list, so the answer to "what does this profile actually run" is visible
without opening anything.

### Hexadron Optimise

The pack button lives in this window, not in the main one, and it appears only
when every mod in the set has a build for the chosen version and loader. A
button that always fails on an unsupported version reads as a broken launcher
rather than as an unsupported version, so on those versions there is no button -
only a line saying why.

Once the set is installed the same button becomes **Remove Hexadron Optimise**,
and the individual Remove buttons on those mods are disabled. A pack is a set
that was chosen and tested together: pulling one mod out leaves something that
is no longer the pack but still claims to be, and the first symptom is a crash
nobody connects to the deletion. It goes in whole and comes out whole.

Mods installed by hand are never touched by any of this. `mods/.hexadron-mods.json`
records who installed what - `PACK`, `MANUAL` or `DEPENDENCY` - so reinstalling
the pack cannot delete a mod the user chose, and removing the pack cannot take
one with it. Jars the user copied into the folder themselves are not in that
file at all, and are never deleted, moved or reported as managed.

## While the game runs

The launcher hides to the notification area, not to the taskbar. A minimised
launcher is still a window to alt-tab past during a session; a hidden one is
not. The tray icon's menu can show the launcher again or stop the game, and the
window comes back on its own the moment the game closes.

Where a system has no notification area - a headless session, some Linux
desktops - the launcher falls back to minimising. Set
`minimiseToTrayWhilePlaying` to `false` in `launcher.json` to keep the window
on screen instead.

## Offline accounts

An offline name must be what Minecraft itself accepts: 3 to 16 characters, and
only Latin letters, digits and underscore. The launcher refuses anything else
when the account is added and again before a launch.

This is not a preference. The integrated single-player server validates the
local player's name exactly as it validates a remote one, so a name such as
`Гравець` loads the world and then drops the player out of it with
`Invalid characters in username` - a message that reads like a multiplayer
fault and gives no hint that the account name caused it.

## Languages

Strings live in `launcher/src/main/resources/lang/<code>.properties`, read as
UTF-8. `en.properties` is the reference: `SelfCheck` fails the build when
another file is missing a key, carries an extra one, has a blank value, or has
lost a `{0}` placeholder that the English string uses. A missing key falls back
to English per key, so a half-finished translation shows English words rather
than a broken screen.

To add a language: copy `en.properties` to the new code, translate it, and add
one entry to `com.hexadron.launcher.i18n.Language`. Nothing else changes - the
window reads every string through `I18n` and rebuilds its text on the spot when
the picker changes.

## Architecture

The launcher core has no third-party dependencies. It uses only the JDK.

```
i18n/     languages and the string table
json/     small strict JSON tree and parser
util/     platform detection, hashes, maven coordinates
net/      HTTP client with retry, parallel verifying downloader
meta/     version manifest, version JSON, rules, libraries, assets
install/  version installer, asset installer, native extraction, loaders
auth/     accounts, offline UUIDs, Microsoft device code flow
profile/  profiles and their isolated game folders
mods/     Modrinth and CurseForge providers, pack and single-mod installer,
          ownership records
launch/   Java locator, command builder, process control
core/     settings and the application service
ui/       JavaFX window, mod browser, instance dialog, theme, tray -
          no launch logic
cli/      headless entry point
```

`SelfCheck` verifies the metadata layer, the player-name rule, JVM-argument
splitting, mod ownership and the language files with 221 assertions. It needs no
network, no display and no test framework.

## Not done yet

- Forge and NeoForge installation. Both use an installer jar with processors
  that must run locally to patch the client jar.
- Download of a Java runtime when the machine has none.
- Import and export of Modrinth `.mrpack` and CurseForge modpack files.
- Storage of refresh tokens in the operating system credential store. The
  launcher currently writes `accounts.json` with owner-only permissions.

## License

CC0.
