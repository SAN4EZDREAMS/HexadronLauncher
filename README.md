# HexadronLauncher

A Minecraft launcher and an umbrella performance mod, in one repository.

- `launcher/` - the launcher.
- `mod/` - Hexadron Optimise, a Fabric mod that groups a performance set.

## What the launcher does today

| Area | State |
|---|---|
| Minecraft versions | Every version in Mojang's `version_manifest_v2` - releases, snapshots, old_beta, old_alpha |
| Loaders | Fabric, Quilt, Forge and NeoForge all install and launch. The version picker offers only versions the chosen loader has builds for |
| Accounts | Offline accounts work and can be removed. Microsoft sign-in is implemented and needs an approved Azure client ID |
| Profiles | Each profile has its own game folder, Minecraft version, loader, memory limit, JVM arguments and Java path |
| Mods | A browser window per instance: search, sort, install and remove, filtered to that instance's version and loader. Modrinth needs no key; CurseForge needs one, and says so when it has none. Required dependencies resolve automatically |
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
| `microsoftSignInMethod` | `browser` (authorization code + PKCE, the default) or `deviceCode` |
| `secureLaunchHandshake` | Send the session token to the game over standard input instead of on the command line. `true` by default |
| `useFileCredentialStore` | Keep credentials in the launcher's own encrypted file instead of the operating system credential store. `false` by default, and a downgrade |
| `curseForgeApiKey` | CurseForge API key. Empty by default. Modrinth needs no key |
| `showAllVersions` | Show snapshots and old versions in the version list |
| `downloadConcurrency` | Number of files to download at the same time |
| `keepOpenWhilePlaying` | Keep the launcher window open while the game runs |

### Microsoft sign-in

Mojang requires each launcher to use its own Azure application, and Mojang must
approve that application. Do these steps in order:

1. Register an application in the Azure portal, in the **consumers** tenant.
   Add the platform **Mobile and desktop applications** and the redirect URI
   `http://127.0.0.1`. Leave **Allow public client flows** off unless you also
   want the device-code fallback; the launcher needs no client secret.
2. Apply to Mojang for approval of the application ID at
   <https://aka.ms/mce-reviewappid>.
3. Put the application ID into `launcher.json` as `microsoftClientId`.

The launcher signs in with the OAuth 2.0 authorization code grant and PKCE, in
the user's own browser, over a loopback redirect - what RFC 8252 prescribes for
a native application. It never receives the user's Microsoft password, never
draws a login form and never loads an embedded web view. The device code grant
is kept as a fallback for a machine with no browser.

Until the application is approved, `login_with_xbox` returns HTTP 403 and the
launcher reports exactly that.

Credentials are never written to `accounts.json`. See [SECURITY.md](SECURITY.md)
for what protects them, and for the limits of that protection.

Offline accounts need none of this. An offline account uses the same UUID that a
Minecraft server calculates in offline mode, so worlds keep the same player data.

### CurseForge

CurseForge requires an API key for every request, and since July 2026 for the
file downloads as well - its content hosts answer `401` without one. Modrinth
requires none.

**Where the key comes from.** In this order, first non-empty wins:

1. `curseForgeApiKey` in `launcher.json` - a user's own key, and it always wins;
2. the `CURSEFORGE_API_KEY` environment variable;
3. whatever the build put into the launcher jar's manifest.

With none of the three, `CurseForgeProvider.isAvailable()` is false, the mod
browser says so in one line and offers a field to paste a key into, and searches
run against Modrinth alone. That is a working launcher with one platform, not a
broken one.

**The key is not in this repository, and it is not in any fork.** CurseForge
issues one key per application and its terms say it is "non-transferable and may
not be shared with any third party". So the release build reads
`CURSEFORGE_API_KEY` from the environment - on CI, from a repository secret -
and writes it into the jar manifest, where `BuildConfig` finds it. GitHub does
not give repository secrets to builds of forks or to pull requests from them, so
those builds get an empty attribute, compile, run, and simply have no CurseForge
in them. Nothing has to be edited and no build fails.

Two well-known launchers commit their key in plain text instead and have been
formally challenged over it. A proxy holding the key server-side is not the
answer either: the same terms forbid reaching the API through a proxy and
forbid caching its responses, so that trades one breach for two, and adds a
server you have to pay for.

**What this does not claim.** A manifest attribute is not a secret from the
person running the launcher. No client-side key can be, whatever is done to it,
and obfuscating one only hides that fact. What the arrangement actually achieves
is narrower and worth having: the key is out of version control, out of every
fork, and replaceable in one place.

`-Dhexadron.curseforge.apikey=...` overrides the built-in key, which is how a
developer runs against their own without touching a build file.

**Mods whose authors disabled third-party downloads.** For those the API returns
a file with no download URL. That is a licence decision and it is respected -
nothing is circumvented. What the launcher does instead is ask Modrinth whether
it has a file with the same SHA-1. A hit is the same bytes by definition,
published by the same author in a place they did allow, so the download comes
from there and the digest still verifies it. No hit, and the mod is named,
skipped, and left for you to fetch by hand.

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

## Version and loader compatibility

Choosing a loader narrows the Minecraft version list to versions that loader
can actually run. Before this, every version Mojang ever published sat next to
every loader, so Minecraft 1.0 with Fabric selected looked like a valid choice
and only failed at install time.

The filter is built from each project's own data, never from a rule of thumb:

| Loader | Source of truth | Filters? |
|---|---|---|
| Fabric, Quilt | `/versions/game` on their meta APIs. No intermediary mappings means the loader cannot run at all | yes |
| Forge | maven metadata: a build id **is** `<minecraftVersion>-<forgeVersion>` | yes |
| NeoForge | build numbers: `21.1.66` means Minecraft 1.21.1, `26.1.2.97` means Minecraft 26.1.2 | **no** |

NeoForge is still deliberately excluded, even though both of its encodings are
now implemented. The scheme has already changed once - Minecraft's move to
calendar versioning turned three-part build numbers into four-part ones - and
the two possible mistakes do not cost the same. A list that is too long offers a
version whose build list then comes back empty, which says so plainly and by
name. A list that is too short hides a version that does work, and gives the
user nothing to read. So the derived list is used to sort and to suggest, never
to hide.

## Installing Forge and NeoForge

Fabric and Quilt publish a finished launcher profile, so installing them is one
download of one JSON file. Forge cannot work that way, and the reason is not
laziness on their part: Forge ships its changes to the game as a **binary diff
against the vanilla client jar**, because nobody may redistribute a patched
Minecraft jar. The diff has to be applied on the user's own machine.

That is what the installer jar's `install_profile.json` describes: a chain of
*processors*, each a separate Java program, that together turn the vanilla jar
into the one Forge launches. `install/loader/forge/` implements it.

| File | Job |
|---|---|
| `InstallProfile` | reads `install_profile.json`, both formats |
| `ForgeProcessor` | one step: its jar, its classpath, its arguments, its expected outputs |
| `Tokens` | the substitution language the arguments are written in |
| `ProcessorRunner` | runs the steps and verifies what they produced |
| `ForgeStyleInstaller` | the whole install, for Forge and NeoForge alike |

NeoForge forked Forge's installer and kept the format, so one engine covers
both. Three eras of the format are in use and all three are supported; which
one applies is read from the profile, never guessed from the Minecraft version,
because the boundary has moved:

- **up to 1.12.2** - keys `install` and `versionInfo`. The loader is a plain jar
  inside the installer and there is no patching at all.
- **1.13 to 1.20** - a long chain: read the mappings, split the jar, remap it,
  apply the diff.
- **current** - one step, because both projects moved the heavy work into their
  own build.

Four decisions in there are worth stating, because each replaces something that
looks simpler and is wrong:

- **Each step runs as a separate JVM.** These are third-party programs, some a
  decade old; they rewrite the thread context classloader and they call
  `System.exit`. A separate process cannot take the launcher down with it, and
  it can be given a different JVM - which matters, because the remapper used by
  the 1.13-1.16 chain behaves differently on anything newer than Java 8. The
  JVM is chosen from what the *version manifest* asks for, not from what the
  launcher happens to run on.
- **A step whose outputs already exist and match is skipped.** The profile
  publishes a SHA-1 for every file a step produces, so a repeat install or a
  repair costs almost nothing.
- **A wrong hash is not automatically a wrong file.** These jars are built at
  install time, and a JVM using a native compression library produces a
  byte-different but perfectly valid archive. Rejecting on the hash alone made
  Forge uninstallable on those machines. A mismatch that is still a whole
  archive is kept, with a note; anything else is deleted and reported.
- **The version manifest is written last.** A half-finished install that leaves
  no manifest cannot be launched by mistake. One that leaves the manifest and no
  patched jar boots into a crash the user cannot read.

### What has actually been launched

The self-check covers the parsing and the merge rules with no network. It cannot
cover an install, so these four were run end to end - installed, launched, world
generated, player joined, exited cleanly. They are one per era of the format
rather than four of the same thing, which is what makes the set worth keeping.

| Loader | Minecraft | What it exercises |
|---|---|---|
| Forge 14.23.5.2859 | 1.12.2 | no processors at all; LaunchWrapper and `--tweakClass` instead of the module system; Java 8 |
| Forge 47.4.10 | 1.20.1 | the long chain - mappings, jar splitting, remapping, binary patch - and `BootstrapLauncher` |
| NeoForge 1.20.1-47.1.106 | 1.20.1 | the frozen `net.neoforged:forge` artifact, whose versions are shaped like Forge's |
| NeoForge 26.1.2.97 | 26.1.2 | one processor; the calendar version scheme; Java 25 |

Two real faults came out of that, and neither was in the installer:

- **The game jar was missing from Forge's `ignoreList`.** Fixed in
  `LaunchCommandBuilder.repairIgnoreList`, described there.
- **Pre-1.13 game arguments were appended instead of replaced.** Fixed in
  `VersionJson.merge`, described there. This one had been in the metadata layer
  since before Forge existed here, waiting for the first version that uses the
  old `minecraftArguments` form.

A harmless one worth knowing: Minecraft 1.12.2 logs
`Couldn't load Narrator library ... SAPIWrapper_x64.dll` at startup. That is
vanilla 1.12.2's own bug - its JNA looks for the file under a path Mojang did
not ship it at - and it happens in the official launcher too.

A handful of Forge and NeoForge builds ship a broken installer or are listed in
the repository without existing - `1.12.2-14.23.5.2851` writes `"data": []`
where the format requires a map, `47.1.82` is listed without its version prefix.
Those are left out of the picker by name. Offering them means offering a
failure.

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

Results are paged. The status line shows the platform's own total - "showing 40
of 3812" - and **Show more** fetches the next page. The first version of this
window asked for 40 results and showed 40 for every Minecraft version and every
loader, which read as "there are 40 mods" and made a version with four thousand
mods look identical to one with fifty.

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
install/loader/forge/
          the Forge and NeoForge installer: install_profile.json, the token
          language, the processor runner
auth/     accounts, offline UUIDs, Microsoft sign-in (PKCE + device code),
          credential stores (DPAPI / Keychain / Secret Service / encrypted file)
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
splitting, mod ownership, loader/version compatibility, search paging, the
language files, the Forge installer profile formats and their token language,
the CurseForge key chain and where that key is allowed to be sent, and the
authentication hardening - PKCE against RFC 7636's own test vector, state
validation, log redaction, the credential split - with 380 assertions. It needs
no network, no display and no test framework.

## Not done yet

- Download of a Java runtime when the machine has none.
- Import and export of Modrinth `.mrpack` and CurseForge modpack files.
- Sandboxing the game process. Storing credentials outside the game's reach
  does not stop a malicious mod reading the live session out of the running
  JVM; only isolating the process does.

## License

CC0.
