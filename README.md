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
| Mods | A browser window per instance: search, sort, filter by category, install and remove, filtered to that instance's version and loader. Modrinth needs no key; CurseForge needs one, and says so when it has none. Required dependencies resolve automatically, and the launcher asks before you switch off or delete something other mods depend on |
| Updating itself | Checks the project's own releases at start-up, on the Release or the Nightly channel, and offers the new version with its notes. Downloads, unpacks and replaces the installed folder, then starts again |
| Java | The launcher finds the installed runtimes - PATH, the registry, the vendor folders, the official launcher's own downloads - and picks the one the version asks for. If the machine has none, it offers to download an Eclipse Temurin JRE |
| Assets | Modern, `virtual` (1.6) and `map_to_resources` (pre-1.6) layouts |
| Languages | English, Ukrainian, Russian, Polish, German. The picker changes the window immediately, without a restart |
| Interface | Searchable instance list, read-only instance summary, one Play button. Instances are edited in a dialog with Save and Cancel |
| While playing | The launcher hides to the notification area and returns by itself when the game closes |
| Start-up | A splash screen appears first and the work happens behind it, on a background thread. Each stage is listed with the time it took, and the same list goes into the log |

## Build

You need JDK 25.

```
./gradlew :launcher:build          # build the launcher
./gradlew :launcher:selfCheck      # verify the launch core, no network needed
./gradlew :launcher:licenseHeaders # verify the licence header in every source file
./gradlew :launcher:run            # start the window
./gradlew :mod:build               # build the mod
```

`compileJava` depends on `licenseHeaders`, so every one of those already runs it.
A file whose licence notice is missing or altered fails the build by name; see
[License](#license).

Add `--configure-on-demand` to keep one subproject out of the other's way:
without it Gradle configures every project, so an error in `mod/build.gradle`
also fails `:launcher:build`. Both CI workflows pass the flag.

To start `runClient` with the whole performance set loaded:

```
./gradlew :mod:runClient -Phexadron.devMods=true
```

That set is off by default, so a build of our own code does not depend on the
Modrinth API being up or on seven third-party builds still existing.

## Ready-made clients

```
./gradlew :launcher:appImage    # a runnable folder for this operating system
```

`jpackage` puts the launcher, JavaFX and a Java runtime into one folder under
`launcher/build/jpackage`. It starts on a machine with no Java installed.

CI builds all three. The `package` job runs only after `build` has passed -
there is no point packaging something that does not compile - and it runs on
three separate runners because **jpackage only builds for the system it runs
on**: it embeds that platform's Java runtime, so a Windows client cannot be
produced on Linux even in principle. `fail-fast: false`, so a broken macOS
build does not throw away the Windows and Linux ones that already worked.

| Artifact | What downloading it gives you | Needs Java installed? |
|---|---|---|
| `hexadron-launcher-windows` | a zip holding the `HexadronLauncher` folder | no |
| `hexadron-launcher-linux` | a zip holding `HexadronLauncher-linux.tar.gz` | no |
| `hexadron-launcher-macos` | a zip holding `HexadronLauncher-macos.tar.gz` | no |
| `hexadron-launcher-jar` | the launcher jar and the start-script zip | **yes**, JDK 25 |

Those are the per-push artifacts, which expire. A **release** carries the same
three clients under fixed names - `HexadronLauncher-windows.zip`,
`HexadronLauncher-linux.tar.gz`, `HexadronLauncher-macos.tar.gz` - and those
names are a contract: the launcher's own updater looks for the file for its
system by name. `.github/workflows/release-launcher.yml` publishes them, on a
`v*` tag for the Release channel and on a schedule or a manual run for Nightly.
See [Updating itself](#updating-itself).

Windows is not packed twice and the other two are, and that is deliberate.
`actions/upload-artifact` always hands a download over as a zip - it is not
configurable - so anything the workflow archives itself the user unpacks twice.
On Windows nothing is lost by skipping our own archive, so the workflow uploads
the image folder as it is and GitHub's zip is the only one.

On Linux and macOS the `tar.gz` has to stay: GitHub's zip carries neither the
execute bit nor symbolic links, and the image needs both - the launcher binary
must remain executable, and a macOS `.app` is full of symlinks into its embedded
runtime. An image archived without them unpacks into something that will not
start, and it fails at the user's end rather than in CI. One extra unpack is the
cheaper of the two problems.

## Icons

There are two sets and they are not interchangeable.

`launcher/packaging/icon.ico`, `.icns` and `.png` are what `jpackage` stamps into
the executable - the icon in Explorer, the Dock, the file manager. One container
format per platform, because each wants its own.

`launcher/src/main/resources/ui/icon/icon-*.png` are what the running program
hands to every window: the title bar, the taskbar, alt-tab. The two are
unrelated, which is worth knowing because getting the first one right does
nothing for the second - and for one build that was exactly the state of things:
a correct icon on the file, and Windows' generic white window frame in the title
bar.

The cause was that the window icon was being *drawn*, with `Canvas.snapshot`, and
a canvas that has never belonged to a scene has never been through a render pass.
What came back was an empty image, which is not an error - so nothing complained,
and the platform quietly fell back to its own icon. They are loaded from
resources now, and `SelfCheck` parses each PNG header, because a missing or
truncated icon resource fails silently by its nature and is otherwise caught only
by somebody looking at a title bar.

There is one image per size rather than one image resized. A window icon list
holding a single 64-pixel entry makes the platform downscale to 16 for the title
bar, and the crossbar of an H does not survive that. Each size is rendered from
four times its own dimensions instead.

All of them are committed rather than built - generating them needs Python and
Pillow, and the build must not - but they are generated rather than drawn:
`launcher/packaging/make-icons.py` writes every file from the same mark, corner
radius and cap height that `ui/Brand.java` uses at runtime. Change the drawing
and run that script; do not edit the images.

The last one is not a client. It is kept because someone who already has a JDK
has no use for another 80 MB of embedded runtime, and because it is what a Linux
user packaging this themselves would start from.

Packaging runs on every push, on any branch. It costs about three minutes of
runner time across three machines, which is worth it while the clients are the
thing being tested. When that stops being true, the workflow carries the `if:`
that skips packaging on working branches - commented, with the reason next to it.

One thing worth knowing before relying on that gate: the **Re-run** button keeps
the original event, so a re-run of a push is still a push and is not a
`workflow_dispatch`. Only **Run workflow** is.

Two choices in there worth stating.

**Portable archives, not installers.** No `.msi`, no `.dmg`, no `.deb`. An
unsigned installer is worse than none: Windows SmartScreen warns on an unsigned
`.msi`, and macOS refuses an unsigned `.dmg` with "the app is damaged and can't
be opened" - which reads like a corrupt download rather than a missing
signature. Fixing that needs a Windows code-signing certificate and an Apple
Developer membership. Until those exist, an archive the user unpacks is the
honest format.

**tar.gz on Linux and macOS, zip on Windows.** Archiving happens in the workflow
with the platform's own tool rather than through a Gradle `Zip` task, because
Gradle's archive tasks follow symlinks and drop the executable bit. Both matter:
the launcher binary has to stay executable, and a macOS `.app` is full of
symlinks into its embedded runtime. An archive built the other way unpacks into
something that will not start - and it fails at the user's end, not in CI.

**jpackage only takes numbers.** `--app-version` accepts digits and dots and
nothing else, so a nightly build's version - `0.2.0-nightly.41` - cannot go into
a bundle at all. The numeric core goes there and the full version stays where the
launcher reads it: `Implementation-Version` in the jar's manifest, which is what
`BuildConfig.version()` returns, what the User-Agent carries, and what the update
check compares against the tag of a release. `-PlauncherVersion=` sets it; CI
passes the version it is publishing.

**The macOS bundle reports 1.0.0.** Apple requires `CFBundleVersion` to start
above zero, so jpackage refuses a `0.x` app-version outright - "The first number
in an app-version cannot be zero or negative". A pre-1.0 product cannot be
expressed in a macOS bundle at all. Windows and Linux accept `0.2.0` and get it;
only macOS gets the stand-in, and the build logs the substitution rather than
making it quietly. The jar inside the image is still `launcher-0.2.0.jar`, and
the launcher still sends `0.2.0` in its User-Agent.

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

## Start-up

The window used to appear a second or two after the double-click, with nothing
on screen in between. Two things were responsible, and both are fixed rather
than hidden behind the splash.

**The credential store was probed on every start.** Choosing where to keep
credentials means asking each candidate store whether it works, and on Windows
that answer costs two `powershell.exe` launches - a full DPAPI
protect/unprotect round trip, because anything cheaper passes on machines where
the call would actually fail. A cold PowerShell start is the slowest thing the
launcher does that is not a download, and it happened on the interface thread,
before the window existed, on every start - including the majority that never
touch a credential at all. An offline account has no secret to read.

It is now decided on first use. A launcher opened to play an offline profile
never runs it.

**The window icon was drawn with AWT.** Touching `Graphics2D` initialises Java2D,
and asking it for `Font.SANS_SERIF` metrics makes the platform font manager
enumerate every installed font - to produce one 64-pixel image, before the
window appeared. It is drawn with JavaFX now, which is already loaded. The tray
icon still uses AWT because `SystemTray` is an AWT API and JavaFX has no
equivalent, but that runs when the game starts, not when the launcher does.

What is left is genuinely unavoidable - the JavaFX toolkit coming up, and
reading three small files - so the rest is honesty about it:

- The splash appears as the first thing `Launcher.start` does.
- Settings, profiles and accounts are read on a background thread, so the
  interface thread is free to draw.
- Each stage is listed as it runs and stamped with the time it took when it
  ends. These are real measurements of real steps, not a scripted animation.
- The window is shown before the splash fades, never after: closing the last
  window is what ends a JavaFX application, and a moment with no window at all
  is a moment for that to happen.
- Detecting Java runtimes is warmed up **after** the window is up, in the
  background. It reads the registry and probes everything it finds, so it would
  be the slowest stage of all - and doing it here keeps that cost off both
  start-up and the first press of Play.

`-Dhexadron.nosplash=true` skips the splash. The timings still go into the log.

The splash stays up for three seconds at minimum, and there has to be some
floor: start-up is now fast enough that without one the window would appear and
vanish, which reads as a glitch rather than as a splash. A click or any key
closes it at once - a minimum display time is a promise that the user gets to
read the thing, not a licence to hold their launcher hostage - and
`splashMinimumMillis` in `launcher.json` moves or removes it.

The window itself is built while the splash is still up and shown only once it
has gone. That is what lets a key press dismiss it: a window shown behind the
splash would have taken keyboard focus off it.

## Data folder

The launcher keeps all data in one folder:

- Windows: `%APPDATA%\.hexadronlauncher`
- macOS: `~/Library/Application Support/hexadronlauncher`
- Linux: `~/.local/share/hexadronlauncher`

Libraries, assets and client jars are shared between profiles. Each profile has
its own folder under `instances/`, so mods and worlds stay separate. Inside a
profile's `mods/` folder the launcher keeps two files of its own:
`.hexadron-mods.json`, the record of what it downloaded, and
`.hexadron-external.json`, what Modrinth said about the jars it did not. Mod
logos are cached under `cache/mod-icons`, keyed by the digest of their address,
and that folder is bounded: thirty-two megabytes by default, least recently used
thrown away. The size is a setting - Downloads and mods, 8 MB to 1 GB - because
both ends of that range are real: a machine short of disk wants it small, and
somebody browsing the whole catalogue on a slow line would rather spend a few
hundred megabytes than fetch the same pictures twice. A second bound on the
number of files follows the first, since tens of thousands of tiny logos fit
inside any size budget and slow every sweep after. Lowering the setting sweeps
the folder at once rather than at the next start. It had no bound at
all to begin with, which is a browser scrolled through a few thousand results
turning into gigabytes of pictures of leaves in a folder nobody ever opens.
Nothing kept there cannot be fetched again, which is what lets the sweep be as
blunt as it is. Pictures chosen as instance icons are copied into `icons/`, named
after their own content.

`profiles.json` holds the profiles and, next to them, how they are arranged: the
size of the grid, the cell each profile is in, the groups and which rows belong to
them, and which of the two views was last used. One file, so there is no way to end up with
an arrangement referring to profiles that a separately restored file no longer
has. An arrangement written by either earlier version - the one that had no grid at
all, and the one that kept group membership on the instance instead of the row -
is read and laid out again rather than discarded.

Removing a profile asks what to do with that folder, and the two answers are
both right some of the time: someone clearing out an old instance wants the
twenty gigabytes back, and someone who misclicked must not lose a world to it.
There is no safe default, so there is no default. Deletion is best-effort and
reports what it could not remove - on Windows a file the game still holds open
cannot be deleted at all, and a folder left one locked shader cache short of
empty needs to say so rather than look like a failure.

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
| `splashMinimumMillis` | How long the start-up window stays up at minimum, in milliseconds. `3000` by default; `0` removes the floor. A click or a key closes it sooner |
| `javaDownloadPolicy` | What to do when no installed Java fits: `ask` (the default), `always` or `never` |
| `keepOpenWhilePlaying` | Keep the launcher window open while the game runs |
| `verifyEveryLaunch` | Re-read and re-hash every file before each launch instead of trusting the ledger. `false` by default |
| `proxy` | How the launcher reaches the network: `mode` (`system`, `direct`, `manual`), `host`, `port`, `user`. The password is not here - it goes to the credential store |
| `warnAboutDependents` | Ask before switching off or deleting a mod that other installed mods need. `true` by default |
| `modIconCacheMegabytes` | How much of the data folder the kept mod logos may fill. `32` by default, 8 to 1024 |
| `checkForUpdates` | Look for a newer launcher at start-up. `true` by default |
| `updateChannel` | Which builds that check offers: `release` (the default) or `nightly` |
| `customGroupColors` | Colours mixed by hand in the group editor, newest first |

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
| H  HexadronLauncher [ search ] [Inventory][Settings] Lang:[v] |
+---------------------+----------------------------------------+
| Instances           |  Instance name                          |
| - Modded set    (2) |  fabric-loader-0.19.3-26.2              |
|   [F] My world      |  +----------------------------------+   |
|       26.2 · Fabric |  | Minecraft   26.2                 |   |
|                     |  | Loader      Fabric · 0.19.3      |   |
|                     |  | Memory      4096 MB              |   |
|                     |  | Java        Detected automatically|  |
|                     |  | Last played 16 Aug 2026, 14:47   |   |
|                     |  | Folder      ...\instances\1-027f96|  |
|                     |  +----------------------------------+   |
| [New][Edit][Remove] |  [Edit] [Install] [Mods...] [Folder]    |
| [New group][Sort]   |                                         |
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
reached by the Edit button or by right-clicking the instance, and that dialog has
a Save and a Cancel. The previous window edited the selected profile's fields in
place, which meant a mistyped name was written to disk before it could be
noticed and there was no point at which the values could be checked together.
Prism Launcher and MultiMC edit instances the same way.

The instance list is searchable by name, Minecraft version or loader. The Play
button never moves: it sits in the footer next to the account, so the one action
the launcher exists for is always in the same place.

### Two views of the same instances

There are two interfaces onto the profiles, and a button in the header switches
between them:

- **the list** - rows, grouped and indented, each row carrying the mark of its
  mod loader;
- **the inventory** - nine cells across with a thick bevelled border, each cell
  holding an icon with the instance name under it, as the game's own inventory
  is laid out.

```
+---------------------------------------------------------------+
| H  Inventory  [ search ] [New][New group][Sort]    [List][Set] |
+---------------------------------------------------------------+
|   | +-----+ +-----+ +-----+ +-----+ +-----+ +-----+ +-----+ |+||
|   | | [F] | | [Q] | |     | | [N] | |     | | [V] | |     | |-||
|   | | Sky | | Pack| |     | | Neo | |     | | 1.8 | |     | |  ||
|   | +-----+ +-----+ +-----+ +-----+ +-----+ +-----+ +-----+ |  ||
| M | +-----+ +-----+ +-----+ +-----+ +-----+ +-----+ +-----+ |  ||
| o | | [F] | | [F] | |     | |     | |     | |     | |     | |  ||
| d | +-----+ +-----+ +-----+ +-----+ +-----+ +-----+ +-----+ |  ||
|   |                    [ + ]  [ - ]                          ||
+---------------------------------------------------------------+
| Account: [ v ] [Add offline] [Sign in]              [ PLAY ]  |
+---------------------------------------------------------------+
```

The second row is a group. The coloured plate down its left carries the group's
name written along it - `Mod` here - and clicking the plate folds the band into a
single strip that still says which group it is. The `+` and `-` on the right add
and remove a column; the pair underneath add and remove a row.

They are the same instances, not two lists kept in step. Neither view holds an
order, a selection or a search of its own: both read one arrangement, and every
drag, group, rename and click writes to it and redraws both. So a reorder made
in the grid is already true in the list before it is switched to, and there is no
synchronising step that could be missed. `ProfileLayout` is that arrangement and
`ProfileHost` is the only way either view can change it.

The arrangement has exactly two parts, and that is what makes one of it: every
instance sits in one **cell** of the grid, and every **row** of the grid may
belong to a named group. An instance is in a group when its row is - nothing else
records membership, so "which group is this in" and "where is this" are the same
question and cannot give different answers.

The grid draws the cells. The list walks the same cells in reading order and
leaves the empty ones out, so a gap in the grid is nothing in the list - two
instances with a free cell between them are two consecutive rows.

Cells are absolute. A drop on a free cell puts the instance in that cell and
leaves it there; a drop on an occupied one exchanges the two. Nothing moves that
was not dragged. The first version wrapped instances into as many rows as they
needed, which made a free cell mean "the end" - so dragging an instance onto the
empty cells at the end of the top row sent it to the bottom of the grid, and
looked like a drag that had failed.

A group can be left anywhere, including between two empty rows. Each row that
belongs to no group is a band of its own, so every one of them is a separate
place to drop into; a group's own rows stay one band, because a group is moved and
folded whole. Merged, a run of empty rows offered a dropped group only two
positions - above all of them or below all of them.

Groups do not nest, and the drop line says so. A group dragged over a row that
happens to be inside another group is shown landing above or below that whole
band, never between two of its members - which is what actually happens, since
the target's block is stepped over rather than split. The line used to go on the
row under the pointer and promised a nesting that was never going to occur.

Dragging in the *list* is a different question with a different answer: the list
has no cells, so a row dragged to a new position reorders which instance sits in
which of the already-occupied cells. The gaps stay where they were and only the
contents change. Dragging a group header moves all its members together. Where a
row lands also decides its group - dropped among a group's members it joins them,
dropped among the loose rows it leaves - because that is what the row will look
like once it lands.

Grouping is one level deep, and a group owns rows. Making one takes the first
free row of the grid, or adds a row when there is none, so it never displaces
anybody and it starts with room in it. Dragging an instance into one of its cells
is how that instance joins; dragging it out is how it leaves.

In both views a group is a band. In the list it is a tinted panel with a coloured
rail down the left, holding the header and its members; in the grid it is the same
tint across its rows, the cells themselves included, with a coloured plate down
the left carrying its name along it. In the list the heading carries no colour
chip and no grey - the rail and the tint already say the colour, and everything
written on the band is white at one opacity or another, so the only colour in the
block is the group's. The list used to indent its members and nothing more, which left the question
the grid answers at a glance unanswered - where a group ends, and which of two
adjacent groups a row belongs to.
The plate is also the band's handle: clicking it folds the band into one strip
and folds the list with it - which is what owning rows buys, and why the control
did nothing in the version where membership was a property of the instance
instead - and dragging it moves the whole group above or below another band. The
same two moves are on the group's menu, because a drag needs both ends on screen
at once and is not available from a keyboard at all.

A group can also be started on one particular row, from the right-click menu of
any empty cell in it. If that row already has instances, the launcher asks which
of the two things it should do rather than guessing: the group takes them, or
they move out of its way into free cells that belong to no group - and the grid
gets another row if there is nowhere to put them. A row is used to make that room
and never a column, because a new row belongs to no group and every one of its
cells is somewhere those instances may go, while a new column puts one cell
inside each existing group as well.

A group has its own `+` and `-` at the right end of its band, in the group's own
colour, and the same two items on its right-click menu. They are separate from
the grid's edge strips because "one more row in this group" is a different thing
from "one more row in the table", and they are coloured because with three groups
on screen a plain plus does not say which group it belongs to.

Its name and its colour are both set from its own settings, and both are offered
when it is created. The colour matters more than it looks: in the grid a group has
no heading, so the colour is the only thing that says which band is which. A new
group is given the first palette colour no other group is using, and the palette
is fixed - every colour in it stays legible as a band behind the cells and as a
plate with white text on it, which an arbitrary colour would not.

The fold control appears in the list only once a group has something in it. A
group with no members reads identically folded and unfolded there, so a `-` that
visibly did nothing was a control reporting itself broken. The grid keeps its own,
because there a band has rows to close over even when it is empty.

Deleting a group deletes and moves nothing: its rows simply stop belonging to
it.

The same moves are on the right-click menu as well, because a drag needs both
ends visible at once and a keyboard has no drag.

The grid covers the whole upper block when it opens, sliding down over it in
about a quarter of a second - the two views are the same instances, and a hard
cut between them reads as a different screen. The footer stays: the account and
the Play button belong to neither view. Which view was last used is remembered in
`profiles.json`, so the launcher reopens as it was left.

### The size of the grid

The grid is a fixed field of rows and columns, nine by three to begin with, and
it never reflows - so its size is something to set rather than something that
happens. The controls are outside the table, on the axis each one changes: a strip above
it at the right-hand end for columns, one below it at the left for rows, each with
a `+` and a `-`, faint until the pointer is in the grid. The column pair used to
be a column down the right-hand side, vertically centred - which put it level
with whichever band happened to be beside it, next to that group's own two
buttons, so the pair that changes the whole table looked like it belonged to one
group. The same two numbers are in the settings
window for anybody who would rather type them.

Removing a **column** takes a cell off every row, so it moves the instances that
were in it - and only ever into a free cell of the same group. A change to the
shape of the table is not allowed to change what anything belongs to, so when the
group has no free cell the column stays and the launcher says why. That is
stricter than finding the instance a cell somewhere else, and it has to be: the
looser version quietly moved an instance out of its group, which looked like the
group had lost it.

Removing a **row** moves nothing at all. The `-` under the grid takes away the
last row that is empty and belongs to no group, wherever in the grid that row is -
so a group sitting at the bottom does not make the button refuse while an empty
row above it is doing nothing. It refuses only when every row is either in a group
or has instances in it, and then the two things to do about that - empty a row, or
take a row off a group - are both deliberate acts with their own controls.

A refusal appears as a panel over the bottom of the view, with room for the whole
sentence, and goes away by itself or when clicked. It is also written to the log.
It is not a dialog: these come from clicking a small button on the grid's edge,
and a modal in front of that is a modal in the way of the next click.

The one thing that grows by itself is a grid with no room for a *new* instance:
it gets another row, because an instance that exists and cannot be seen cannot be
launched either.

### Settings

One button, in both views, opens the settings window. Everything the launcher
can be told is there, on seven tabs - interface, game, Java, downloads, mods,
accounts, and the data folder - including the things that previously existed only
in `launcher.json` or as a "never ask again" button inside a prompt.

Downloads and mods used to be one tab, and two subjects in one tab is one subject
too many: how many files to fetch at once and which route to take belong beside
the update channel, not beside an API key for a mod site. **Downloads** now holds
the concurrency, the update check, the channel, a Check for updates button and
the whole proxy block; **Mods** holds the CurseForge key, the dependency warning
and the size of the logo cache.

Save writes and Cancel writes nothing, the same rule as the instance editor: half
of these cannot be undone by typing them back.

The Azure client id is deliberately not among them. It identifies the launcher to
Microsoft rather than the user to the launcher - every copy of a build signs in as
the same registered application - so a field inviting somebody to change it has no
use except to break their sign-in. It stays in `launcher.json` for whoever forks
the project.

The two grid numbers are on the Interface tab but are not stored with the
settings - they live with the cells they describe, because narrowing the grid has
to find somewhere for the instances in the removed column to go, and can fail. So
the window asks and reports a refusal rather than writing a number the cells
would then contradict.

### Instance icons

Every instance shows the mark of its mod loader - Vanilla, Fabric, Quilt, Forge
or NeoForge - and any instance can be given a picture instead, from the Icon row
of the editor or from the right-click menu. PNG, JPEG, GIF and BMP are accepted,
transparency is kept, and an animated GIF animates. The picture is scaled to fit
in proportion, so any size works.

The loader marks are drawn by the launcher, in `LoaderIcon`. They are not the
projects' real logos: those are Fabric's, Quilt's, Forge's and NeoForge's own
trade marks and are not this project's to redistribute. Anybody who does have
the right to use them can drop a PNG at `/ui/loader/<loader id>.png` into the
launcher's resources and it replaces the drawn mark, with scaling left off so
pixel art stays pixel art.

A chosen picture is copied into `icons/` in the data folder and the profile
records only the file name. So the icon survives the original being renamed,
deleted or left on a memory stick; two instances given the same picture share one
file, because the file is named after its own content; and nothing in
`profiles.json` is ever opened as a path, which a hand-edited absolute path would
be.

## Java

Every Minecraft version names the Java it wants. Mojang's version JSON carries a
`javaVersion` block - 25 for 26.2, 21 from 1.20.5, 17 from 1.17, and 8 for
everything older - and the launcher resolves a runtime per launch rather than
per install, because one launcher holding a 1.7.10 profile and a 26.2 profile
needs two different runtimes on the same machine.

### Finding what is already there

Searched, in this order:

1. Runtimes the launcher downloaded itself, under `java/` in the data folder.
2. `JAVA_HOME`.
3. The JVM the launcher is running on.
4. Every directory on `PATH` - which is how a runtime installed by winget,
   scoop, Homebrew, apt or sdkman is found without knowing where each of those
   puts things.
5. The conventional install folders for each platform, plus `~/.jdks`,
   `~/.sdkman` and `~/.gradle/jdks`.
6. `.minecraft/runtime` - the runtimes the **official** launcher downloads.
   Often the only Java on a player's machine, and there is no reason to make
   them fetch a second copy of something already on their disk.
7. The Windows registry keys the vendors write, which is the only way to find an
   installation someone put in a folder of their own choosing.

Of everything that satisfies the requirement, an exact match on the major
version wins, and after that the lowest version that still qualifies. Both rules
point the same way: run each version on the runtime its own era was built
against.

### Downloading one

When nothing fits, the launcher offers to fetch a JRE. Three answers: download,
not now, or never ask again - and "download" is remembered, so the question is
asked once rather than once per version. `javaDownloadPolicy` in `launcher.json`
is the same switch.

The runtime comes from **Eclipse Temurin**, through Eclipse Adoptium's public
download API, and lands in the launcher's own data folder. Nothing outside that
folder is touched, no system Java is installed or replaced, and the runtime is
used by this launcher only.

Mojang publishes runtimes too, and they are the obvious thing to reach for. This
launcher deliberately does not use them. That endpoint is part of the official
launcher's private plumbing: undocumented, carrying no licence that grants
anyone else the right to redistribute what it serves, and relying on a service
that was never offered to third parties. Temurin has none of those problems -
OpenJDK under GPLv2 with the Classpath Exception, which permits redistribution,
published through an API meant to be called. Fetching a JRE from Adoptium is a
transaction between the user's machine and the Eclipse Foundation, and needs no
permission from Mojang or Microsoft because it involves neither.

The download is checked against the SHA-256 Adoptium publishes before anything is
unpacked, it is unpacked into a scratch directory and moved into place only after
it has been shown to start and to report the version it was fetched for, and the
licence files that ship inside the archive are kept rather than discarded.

The Forge and NeoForge installers get the same treatment, with one difference:
there the exact major version is insisted on rather than merely preferred. Those
processors are third-party programs built against one Java generation - see
`ProcessorRunner` - and "new enough" is not the same property.

### The packaged clients

The `appImage` build passes `--jlink-options` without `--strip-native-commands`.
jpackage strips them by default, which deletes every executable from the
embedded runtime, `bin/java` included. The launcher still starts, because its
native launcher loads `libjvm` directly - but the bundle then carries a Java 25
runtime that no child process can be started from, and the launcher correctly
reported "no Java installation was detected at all" while sitting on top of one.
Keeping the commands costs about ten megabytes and makes 26.2 launch out of the
box with no download at all.

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
|  [ search ...... ] [ Most popular v ] [ Category v ] [ All v ]|
| []  Sodium                 Modrinth · 40.1M downloads         |
|     A modern rendering engine ...                 [ Install ] |
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

**Category** is a menu of tick boxes rather than a list that picks one, because
a mod is filed under several and somebody narrowing a search usually means more
than one thing at once - "adventure and magic", not "adventure, and now start
again with magic". The menu stays open while they are ticked, so choosing four
categories is four clicks rather than four times opening the same menu, and
**Clear all** empties it in one. All nineteen are on screen at once, in two
columns: they used to be one scrolling column, and nine of them below the edge
with nothing but a thin bar to say so is a list that stops at "Optimisation" for
anybody who does not think to scroll. Several categories narrow rather than widen,
which is what the platform's own filter does and therefore what somebody who has
used it expects.

The nineteen categories are named in `ModCategory` rather than taken from
whatever the last request returned, and each of the three reasons is a thing that
breaks otherwise. Modrinth stores the loaders in the same field a category lives
in - `fabric` sits beside `magic` - so a filter built from the raw list offers to
narrow a Fabric instance's search to Forge mods. The names are shown in the
player's own language, and a name that arrives from a request cannot be
translated. And a launcher opened with no connection still has to draw its own
filter. A category Modrinth adds later is one line in that file; that is the
whole cost of the choice.

The little drawing beside each name comes from Modrinth, so it is the one the
player already recognises from the website. It arrives as markup rather than as a
picture, which is the good case: `SvgPaths` reads it into path data and it is
drawn as shapes, sharp at any scale, taking the theme's colour like a piece of
text instead of needing a light copy and a dark one. That reader is deliberately
not an SVG reader - six element types cover every icon of this kind, and anything
else is left out, because a half-implemented transform draws something subtly
wrong rather than nothing. The drawings are asked for once a month and kept in
`cache/mod-categories.json`; a category whose drawing has not arrived is a
category with a name.

The same categories are on every row in both lists, as the marks the platform's
own listing uses, so a mod recognised on the website is recognised here. For an
installed mod they come out of the lock file, which is why the row can say what a
mod is for with no connection and no lookup.

All of them, not the first three. A row is one line wide and a mod is filed under
as many categories as its author chose, so what does not fit goes behind a small
`+N` at the end of the line, and hovering that opens the rest. The ones ticked in
the filter come first: somebody who has narrowed a search to two categories is
scanning the rows for those two, and finding them behind a count that has to be
hovered answers the question they asked with an extra step.

Category filtering is Modrinth's. CurseForge files its projects under a different
set of its own, and only a handful of names coincide, so a search with categories
chosen says out loud that CurseForge was not searched rather than guessing a
mapping and quietly returning the wrong mods. Whichever of a CurseForge project's
own categories this launcher has a name for are still shown on its row.

Results are paged. The status line shows the platform's own total - "showing 40
of 3812" - and **Show more** fetches the next page. The first version of this
window asked for 40 results and showed 40 for every Minecraft version and every
loader, which read as "there are 40 mods" and made a version with four thousand
mods look identical to one with fifty.

### The Installed tab

The **Installed** tab lists the mods folder, not the lock file - which is a
different list, and the difference is every jar the player dragged in
themselves. Those used to be invisible from inside the launcher: the game loaded
them and sometimes crashed on them, and the launcher showed nothing, because the
only thing it read was its own record of what it had downloaded.

```
+--------------------------------------------------------------+
| [ Browse ] [ Installed (7) ]                                  |
|  3 file(s) here were not installed by the launcher [Identify] |
|                                                               |
|  [ search ............ ] [ All mods v ]        [ Import... ]  |
|                                                               |
| []  Sodium  0.6.13                    Hexadron Optimise       |
|     jellysquid3 · sodium-fabric-0.6.13.jar                    |
|     A modern rendering engine.  [More about this mod][Off][X] |
|                                                               |
| []  Distant Horizons  2.3.2          user's own mod           |
|     James Seibel · DistantHorizons-2.3.2.jar                  |
|     Level of detail rendering.  [More about this mod][Off][X] |
+--------------------------------------------------------------+
```

Every row is the same shape whichever way the mod arrived, because to the person
reading it they are the same thing - a mod that is installed. What differs is
the badge and which buttons are live.

- **The picture.** For a mod the launcher installed it is the project's logo
  from Modrinth or CurseForge, recorded at install time and cached in the data
  folder, so the list draws itself offline. For a jar the player added it is the
  icon inside the jar - every Fabric mod and most others ship one precisely so a
  launcher can show it. Failing both, a tile with the mod's first letter,
  coloured from its name: not a picture of the mod, but a mark that can be told
  apart from the one above it.

  Those logos are mostly WebP, and JavaFX cannot read WebP. Neither can
  `javax.imageio`: the JDK ships PNG, JPEG, GIF, BMP and TIFF and nothing else.
  Modrinth's own API gives an address ending in `_96.webp` for most projects, and
  there is no other format to ask for - the file at that address is a WebP. So
  the catalogue drew a column of letters with the occasional logo, and the ones
  that worked were the projects old enough to still have a PNG.

  `com.hexadron.launcher.util.Webp` reads it. Only the lossless half: WebP is two
  formats behind one extension, and the lossy one is a VP8 key frame - an entropy
  coder, an inverse DCT, sixteen intra prediction modes and a loop filter, which
  is a video codec and not something this project should carry for a 96-pixel
  logo. Every icon Modrinth publishes is the lossless one; of the 68 logos this
  launcher had cached when the decoder was written, 15 were PNG and 53 were WebP,
  and all 53 were `VP8L`. A lossy file is refused and the lettered tile stands,
  which is what every WebP logo did before the decoder existed.

  The self-check decodes real files and compares them against libwebp's own
  output, pixel for pixel, rather than checking that something picture-shaped
  came out. Several details in that format - which pixel the top-right predictor
  reads at the end of a row, the byte-wise delta coding of a colour map - are
  ones a reader of the prose gets subtly wrong and only discovers on an image
  that comes out looking almost right.
- **Name, version, author, description.** Read out of the jar's own descriptor -
  `fabric.mod.json`, `quilt.mod.json`, `META-INF/mods.toml`,
  `META-INF/neoforge.mods.toml`, `mcmod.info`, or the manifest as a last resort.
  This is the same file the loader reads to load the mod, so it is available for
  every mod, offline, with no lookup.
- **More about this mod** opens the project page in the user's own browser. For
  an installed mod that is the recorded Modrinth or CurseForge page; otherwise
  it is the homepage the jar itself publishes. The button is hidden rather than
  disabled when there is no page, and only `http` and `https` links are ever
  opened - the string comes out of an archive the launcher did not write.
- **Switch off** renames the jar to `.disabled`, which is what every launcher
  and every guide on the subject means by it and what the loader looks at. The
  mod stays in the folder and stays in the list, marked switched off, because a
  mod turned off to test a crash is meant to come back.
- **Remove** deletes a mod the launcher installed, and sends a jar the player
  added to the recycle bin instead. That difference is the point: the launcher
  can fetch its own downloads again from the record it kept, and it has no idea
  what the other file was or where it came from. The one irreversible deletion
  in the program should not be the one performed on the files it knows least
  about. Where the desktop has no recycle bin, the file goes to `mods/.removed`.

**Import…** copies jars the player already has into the instance, several at a
time, and a drop onto the list does the same - which is how they arrive: someone
who has just been through a mod site has a folder of them, not one. Copied, not
moved: the files are theirs, sitting where they downloaded them, and emptying
that folder is not the launcher's decision to take. Nothing already in the
instance is ever overwritten, because behind an existing jar there is a version
and a config folder, and a silent replacement is how a working instance becomes a
broken one with nothing to point at. Everything refused - not a jar, not an
archive, a name already taken - is refused by name, since a silent skip in a
batch of twenty is a mod somebody spends an evening looking for.

The test is that the file is an archive, and nothing more. It used to be that the
launcher could read a mod descriptor out of it, which refused real mods: a
descriptor can be written in a dialect this reader does not parse, and a library
jar has none at all - and the same file dragged into the folder by hand was
listed without complaint. The button and the file manager disagreed about one
file, and the button was the one that was wrong. What is still refused is a file
that is not an archive: a half-finished download, or something renamed to `.jar`.
The loader will not read that one either.

**Which mods need this one.** A library nobody installs for its own sake -
Fabric API, Architectury, a mod's own core - is in the folder because five other
mods put it there, and taking it out does not fail at the point of taking it out.
It fails at the next launch, in a crash report naming a class the player has
never heard of, and the last thing they did was remove something else.

So the launcher reads the graph the loader is about to read - every jar declares
what it cannot start without, in its own loader's dialect - and says the same
thing first, by name: *these five mods need this one*. Switching such a mod off
or deleting it asks, lists what would be left without it, and can be told not to
ask again; the switch to bring the question back is Settings, under Mods. The
badge on those rows is amber, and hovering it opens the list of the mods that
need this one - each of them a link that jumps to that row in the list.

It is read from the jars rather than from the lock file, and only for mods that
are switched on. The lock file knows what the launcher installed as a dependency
once; it knows nothing about the jar the player dropped in yesterday that now
needs it. And a mod that is switched off is not loaded, so it cannot break -
warning that it will is warning about something that is not going to happen. A
mod that was installed as a dependency and that nothing needs any more says so
when hovered, which is the launcher saying it can go.

**Search and filter** narrow the list. The search matches the name, the file name
and the authors, because those are the three things a player knows a mod by and
they are rarely the same word - "sodium", "sodium-fabric-0.5.13.jar" and
"jellysquid3" are one mod, and any of them is a reasonable thing to type. The
filter is all mods, the launcher's own, the player's own, switched off, or for
another version.

**Identify** appears only while there are unrecognised jars. It sends a digest
of each of them to Modrinth and asks which project has that exact file, which is
how a jar the player dragged in gets its real name, its logo and its link. It is
a button and not something the window does on opening, because reporting the
contents of somebody's mods folder to a third party is a reasonable thing to do
when they ask for it and an unreasonable thing to do because a window was
opened. Answers are kept in `mods/.hexadron-external.json` - including "asked,
and Modrinth does not have it" - so nothing is asked twice and a launcher
started offline shows what it showed before.

The instance summary in the main window shows the same list, without the
buttons: a Remove button beside a single-click list on the front page is a mod
deleted by accident, and the browser is one button away.

### Mods left behind by a change of version

An instance's Minecraft version can be changed after its mods are installed, and
there are good reasons to do it - an older modpack, or a version that was wrong
to begin with. Nothing moves the jars when it happens: they are in the player's
own folder, and the launcher does not delete what it was not asked to. So the
folder quietly stops matching the instance.

Until this existed, the first anyone heard of that was Fabric refusing to start
and printing a page of it - forty lines of "requires any version between 26.2
(inclusive) and 26.3- (exclusive) of 'Minecraft', but only the wrong version is
present: 1.20.1", one per mod, after the game had already been launched.

Every one of those lines was readable beforehand. A mod has to declare which
Minecraft versions it works with, because the loader reads that declaration to
decide whether to load it, so `com.hexadron.launcher.mods.VersionRanges` reads
the same thing off the same files and answers the same question with nothing
running. Fabric and Quilt write npm-style ranges (`~26.2`, `>=1.20.1 <1.21`,
`1.20.x`); Forge and NeoForge write Maven ranges (`[1.20.1,1.21)`). Both are
read, because a launcher that warned about Fabric mods and stayed quiet about
Forge ones would be worse than one that never warned.

Three things follow from it:

- a mod that will not load is badged **for another version** in red, in both mod
  lists, with what it actually wants in its tooltip;
- changing an instance's version says how many mods have just been stranded;
- **Play** stops and names them, with the choice of launching anyway. It asks
  rather than refuses: a range can be wrong in a mod's own metadata, and a player
  who knows their pack works is not to be argued with by a launcher.

The rule underneath all of it is **silence unless certain**. A false "this mod is
for another version" on a pack that works teaches the player to click past the
warning, and then the true one goes past too. So a range that cannot be parsed, a
Minecraft snapshot like `23w31a`, a mod that declares nothing, a jar that is
switched off - all of them produce no opinion at all. The self-check holds this
from both sides: the ranges from a real crash report are all refused, and the
ranges a working 1.20.1 pack declares are all admitted.

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
file at all: they are listed, and they are removed only when the button on their
own row is pressed. Nothing else in the launcher moves or deletes them.

## Updating itself

The launcher checks its own repository for a newer build while the start-up
screen is up, and offers what it finds in a window: from this version to that
one, the notes that were written for the release, and two answers.

**Two channels, one list of releases.** *Release* takes only what was published
as a finished release - what somebody who is here to play wants. *Nightly* takes
whatever is newest, pre-releases included, which is what somebody testing it
wants and which will from time to time be broken. Both read the same list and
differ only in what they are willing to take from it, so a nightly user who
switches back to Release is simply offered nothing until a release passes the
build they are on. Release is the default.

Versions are compared as versions rather than as strings, which is the whole
reason a nightly channel can work at all: `0.9.10` is newer than `0.9.9`,
`1.0.0` is newer than `1.0.0-nightly.7`, and `nightly.10` is newer than
`nightly.9`. A version that cannot be read produces no offer - the rule is the
same one the mod-version warnings follow, silence unless certain.

**What actually happens when you say yes.** The file for this operating system is
downloaded into `.hexadron-update` beside the installed folder - beside it, so
that replacing the folder is a move within one filesystem, and so that a machine
where the folder cannot be written to is found out before a hundred and fifty
megabytes have been fetched rather than after. It is checked against the length
the platform published, unpacked with the launcher's own archive reader - which
keeps symbolic links and the executable bit, both of which an image needs to
start - and then a second process takes over.

That second process is not an implementation detail. The folder being replaced is
the folder the launcher is running from, and on Windows an open file cannot be
deleted or renamed at all. So the launcher hands over to `Updater`, started from
the *new* build's own runtime and jar, and exits. The updater waits for it to be
gone, moves the installed folder aside rather than deleting it, copies the new
one into place, deletes the old one and starts the launcher again. If anything in
the middle fails, the folder that was moved aside is put back and *that* launcher
is started: a failed update leaves you with the version you had, which is the
only acceptable outcome for a program that replaces itself. What it deliberately
does not do is delete the folder it is itself running from; the next start does
that.

**When it cannot.** A launcher started from an IDE or with `java -jar` has no
installed folder to replace, and one installed somewhere the user cannot write to
must not be half-replaced. Both are said plainly in the window, which then offers
the release page instead of a button that would fail.

Everything about it is a setting - Settings, Downloads: whether to check at all,
which channel, and a Check for updates button for asking now. With no connection
nothing happens and nothing is reported: the launcher you already have still
works, and a dialog about a failed update check is in the way of the game.

See [SECURITY.md](SECURITY.md) section 10 for what is and is not verified about a
downloaded build.

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
          ownership records, jar descriptors, categories, the dependency graph
update/   the launcher's own releases: channels, version comparison, download,
          and the second process that replaces the installed folder
skin/     skins and capes: the viewer, the sheet layouts, the service
about/    the credits shown in the About window
launch/   Java locator, command builder, process control
core/     settings and the application service
ui/       JavaFX window, mod browser, instance dialog, theme, tray -
          no launch logic
cli/      headless entry point
```

`SelfCheck` verifies the metadata layer, the player-name rule, JVM-argument
splitting, mod ownership, loader/version compatibility, search paging, the
language files, the Forge installer profile formats and their token language,
the CurseForge key chain and where that key is allowed to be sent, the update
machinery - version ordering, channel filtering, which published file belongs to
which operating system, and the three application-image layouts - and the
authentication hardening: PKCE against RFC 7636's own test vector, state
validation, log redaction, the credential split. 1213 assertions, and it needs no
network, no display and no test framework.

## Sandboxing, and what it is actually for

An earlier version of this file said that storing credentials outside the game's
reach does not stop a malicious mod reading the live session out of the running
JVM, and that "only isolating the process does". The second half of that was
wrong, and it is worth being exact about why.

A sandbox is enforced by the kernel, at the boundary between the process and
everything outside it - files, sockets, other processes. The session token is
not outside the process. It is in the game's own heap, put there by the launcher
because the game needs it to talk to Mojang. A mod runs inside that same JVM,
which means it reaches the token by reading its own memory. There is no boundary
to cross, so there is nothing for a sandbox to arbitrate. The author of Prism
Launcher's own sandboxing proposal says the same thing in the pull request:
isolation does not protect the account.

What a sandbox does protect is everything the mod is not supposed to touch. That
is not a small category, and it is where the real incidents happened:

- **fractureiser** (2023) - spread through mods on CurseForge and Modrinth,
  and stole browser cookies, Discord tokens and cryptocurrency wallets.
- **"Windows Borderless"** and the **Stargazers** campaigns - the same shape,
  through the same channels.

None of these read the Minecraft token out of the JVM. All of them read files
that had nothing to do with Minecraft. A sandbox stops that, and Java cannot:
`SecurityManager` was the JVM-level answer, and JEP 486 removed it permanently
in Java 24. There is no in-process option left, which is the honest reason the
boundary has to be the kernel's.

The cost is not performance. Linux namespaces are a permission check at setup,
not a layer the game runs through - frame times are unaffected. The cost is
things that stop working: the NVIDIA proprietary driver wants device nodes a
strict sandbox removes, and controllers need an input-device portal that does
not exist yet, so a locked-down profile can boot into software rendering or a
dead gamepad. Prism Launcher's Flatpak is the one meaningful deployment of this
in the ecosystem, and it grants `--device=all` and `--socket=x11` for exactly
those reasons.

So the launcher does not choose for you. It gives you the field.

### The wrapper command

Each profile has a **wrapper command**, in the instance dialog under the JVM
arguments. Whatever is in it runs first, and the game's JVM becomes its child:

```
<wrapper> <java> <jvm args> <main class> <game args>
```

Empty by default, and it must stay that way: a sandbox switched on for everyone
breaks somebody's GPU driver, controller or Discord integration the first time
they press Play, and they have no way to know what changed.

A conservative Linux starting point - the game keeps its own instance folder and
the assets it needs, and loses the rest of `$HOME`:

```
bwrap --die-with-parent --unshare-pid --new-session
      --ro-bind /usr /usr --ro-bind /etc /etc
      --symlink usr/lib /lib --symlink usr/lib64 /lib64 --symlink usr/bin /bin
      --proc /proc --dev-bind /dev /dev --tmpfs /tmp
      --bind ~/.hexadron/instances/<id> ~/.hexadron/instances/<id>
      --ro-bind ~/.hexadron/assets ~/.hexadron/assets
      --ro-bind ~/.hexadron/libraries ~/.hexadron/libraries
      --ro-bind ~/.hexadron/versions ~/.hexadron/versions
```

Two constraints, both of which the launcher's self-check enforces:

- **Standard input must survive.** The session token is handed to the game over
  stdin rather than on the command line, so a wrapper that closes stdin breaks
  every online account. `bwrap` and `firejail` both pass it through; a wrapper
  of your own that redirects from `/dev/null` will not.
- **The network must stay.** `--unshare-net` produces a launcher that starts a
  game that cannot reach a server, including Mojang's session server. Use it
  only for a deliberately offline instance.

If the handshake does break, the launcher names the wrapper in the exit message
rather than blaming the handshake in the abstract - that path is covered by the
self-check, because it is the one failure this feature can introduce.

The same field takes the things people more commonly want it for -
`gamemoderun`, `prime-run`, `mangohud`, `strace -f -o trace.log` - which is why
it is one text box and not a sandbox checkbox.

### On Windows and macOS

`bwrap` and `firejail` are Linux programs, so the example above is Linux-only.
The field itself is not.

On **Windows** the equivalent is [Sandboxie-Plus](https://sandboxie-plus.com),
which runs a program in a named box from the command line:

```
"C:\Program Files\Sandboxie-Plus\Start.exe" /box:minecraft /wait
```

`/wait` is not optional here. Without it `Start.exe` returns as soon as it has
handed the program over, and the launcher reads that as the game having exited -
so the status line goes back to idle while Minecraft is still running.

**This has not been tested against the launch handshake.** The session token
travels to the game over standard input, and whether `Start.exe` passes stdin
through to the sandboxed process is not something this project has verified. If
it does not, the game exits with code 92 and the launcher now says so by name:
it reports the wrapper, says the likely cause is stdin, and tells you to clear
the field and try again. That is a known failure with a clear message rather
than a mystery, which is the most this can honestly claim until somebody runs it.

On **macOS** there is no comparable wrapper program. `sandbox-exec` exists, is
undocumented, and has been marked deprecated by Apple for years; the supported
mechanism is an entitlement applied to a signed application, which is not
something a launcher can put around a JVM it did not sign. So on macOS this
field is for `mangohud`-style tools, not for isolation.


## Not done yet

- Import and export of Modrinth `.mrpack` and CurseForge modpack files.
- A sandbox the launcher turns on by itself. What exists instead is the
  wrapper command below, and the reason is in the next section: a sandbox
  cannot do the thing this line used to claim it did.

## License

**Noncommercial, source-available, attribution fixed.** The full terms are in
[LICENSE.md](LICENSE.md); this is the summary.

You may use the launcher for free, for anything, copy it, give it away, read and
change the source, publish your changed version under these same terms, and send
changes back. You may not sell it or charge for access to it; you may not remove,
rename or obscure the authorship and the licence notices; and you may not publish
a build made to damage, spy on, or sabotage the people who run it.

That is deliberately not an OSI-approved open-source licence - the Open Source
Definition does not allow a licence to forbid selling, and this one does. MIT,
Apache-2.0 and the GPL all permit commercial use, GPL included: copyleft
restricts closing the source, not selling it. None of them can express "free for
everybody, not for sale". [PolyForm Noncommercial
1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/) is the standard
licence for this intent and the terms here are close to it in substance.

**Every source file carries the notice**, and the build enforces it. The text
lives once, in [LICENSE-HEADER.txt](LICENSE-HEADER.txt);
`tools/stamp-license-headers.py` puts it into every `.java`, `.css` and
`.properties` file in `launcher/src` and `mod/src`, and the Gradle task
`licenseHeaders` fails the build when one of them is missing it or carries an
altered one - naming the files. `compileJava` depends on that task, so nothing
compiles, packages or runs while a notice is missing, and both workflows run it
as their first step. Removing the authorship "by accident, along with something
else" is therefore not a quiet operation: it is a red build with the file name in
it.

To change the wording, edit `LICENSE-HEADER.txt` and run the script; that is one
edit and one command rather than a hundred and fifty edits and a broken build.

What the launcher downloads is not covered by any of this - Minecraft itself, the
mod loaders, the mods, the Temurin runtimes and JavaFX each carry their own
terms. LICENSE.md section 8 lists them.
