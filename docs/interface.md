# Interface

This page describes the launcher window: the start-up screen, the main window, the list and grid views, the settings window, instance icons, the bug report window, and what happens while the game runs.

## Start-up

The first thing `Launcher.start` does is show the splash screen. The rest of start-up runs on a background thread (`hexadron-startup`).

The splash adds a line for each stage when it starts. When the stage ends, the line turns grey and shows its time in milliseconds. The bar counts stages. Each stage is written to the launcher log as `Startup: <stage>`, and when the window opens a summary line with all the times goes to the Log panel and the log file.

The stages run in this order. `LauncherService.ALL_STARTUP_STEPS` is the full list: `STARTUP_STEPS` run in `LauncherService.createDefault` and its constructor, `LAUNCHER_STEPS` run in `Launcher`.

| Stage | Splash text | What runs |
|---|---|---|
| `settings` | Reading settings | Reads `launcher.json` |
| `dataFolder` | Preparing the data folder | Creates the base folders and the download manager |
| `verifiedFiles` | Reading the list of verified files | Loads the list of files already checked against their hash |
| `profiles` | Loading profiles | Loads `profiles.json` |
| `credentials` | Choosing the credential store | Selects the credential store. The system store is probed on first use, not here |
| `accounts` | Loading accounts | Loads the accounts |
| `skins` | Loading skins | Loads the saved skins |
| `network` | Applying the network settings | Applies the proxy. A proxy password is read from the credential store |
| `javaRuntimes` | Preparing the Java runtimes | Creates the Java locator and resolver. Detection runs later |
| `platforms` | Connecting the mod platforms | Sets up CurseForge and the mod, modpack, data pack, resource pack and shader installers |
| `updateCleanup` | Clearing the leftovers of the previous update | Starts removing files left by a previous self-update, on its own thread |
| `updates` | Checking for updates | Asks the release feed for a newer launcher, see [updates.md](updates.md) |
| `language` | Applying the language | Applies the language from the settings |
| `interface` | Building the interface | Builds the main window |

When the update check is off, `updates` does not run: the bar counts one stage fewer and the summary names it as skipped. A newer build found by the check is offered after the main window is on screen.

`SelfCheck` fails when a stage has no `splash.step.<stage>` text in the reference language, is listed twice, or is reported out of the `STARTUP_STEPS` order.

**Closing the splash:**

- The splash stays up for at least `splashMinimumMillis` (default 3000). Change it on the Interface tab of the settings window (0-15 seconds) or in `launcher.json`. Zero removes the minimum.
- After 1.1 seconds the splash shows "Click or press a key to continue". A click or key press cancels the minimum wait. It does not stop start-up: if stages are still running, the splash closes when they end.
- The main window is built while the splash is up and shown only after the splash has faded out (260 ms) and closed. The splash keeps keyboard focus until then.
- Implicit exit is off until the main window is shown, so JavaFX does not exit in the moment when no window is open.
- After the window is shown, a low-priority thread (`hexadron-warmup`) detects Java runtimes and finishes deleting instance folders left in `.deleting`.
- `-Dhexadron.nosplash=true` skips the splash. The stages are still logged.
- If start-up fails, the splash closes, a "Startup failed" dialog shows the cause, and the launcher exits.

## The main window

+----------------------------------------------------------------+
| H HexadronLauncher [Search instances] [grid][broom][bug][?][cog] |
+---------------------+------------------------------------------+
| Instances | [icon] My world |
| | Modded set 2.. | Minecraft 26.2 |
| | [F] My world | Loader Fabric 0.19.3 |
| | [F] Sky | Memory 4096 MB |
| [V] 1.8 | Java Detected automatically |
| | Last played 16 Aug 2026, 14:47 |
| | Folder ...\instances\1-027f96 |
| [New][Edit][Remove] | [Edit][Install / repair][Content...] |
| [New group][Sort A-Z] [Open game folder][Detect] |
| [Import][Export] | Mods (5) |
+---------------------+------------------------------------------+
| Account: [ v ] [Manage accounts] [Sign in with Microsoft] |
| [Skin and cape...] [Remove] [ Play ] |
| Ready |
| [==============================================] |
| > Log |
+----------------------------------------------------------------+

**Header.** The search field filters both views by instance name, Minecraft version or loader. The icon buttons have their names in tooltips:

| Button | Opens |
|---|---|
| Inventory view / List view | Switches the view |
| Storage and cleanup | The storage window, see [builds-and-storage.md](builds-and-storage.md) |
| Report a bug | The bug report window |
| About this launcher | Version, author, the projects the launcher is built on, the licence |
| Settings | The settings window |

**Sidebar.** The instance list and three rows of buttons: New / Edit / Remove, New group / Sort A-Z, Import / Export (`.hexbuild` files, see [builds-and-storage.md](builds-and-storage.md)).

**Detail panel.** A read-only summary of the selected instance and its mods, and these buttons:

| Button | Action |
|---|---|
| Edit | Opens the instance editor |
| Install / repair | Downloads and checks the game files, loader and libraries |
| Content... | Opens the content window, see [mods.md](mods.md) |
| Open game folder | Opens the instance folder |
| Detect | Searches for Java runtimes and lists them in the Log panel, see [java-and-loaders.md](java-and-loaders.md) |

To change an instance, use Edit or the right-click menu. The instance editor has Name, Icon, Minecraft version, Mod loader, Loader version, Memory, Java, Extra JVM arguments and Wrapper command. Save writes the changes; Cancel writes nothing.

**Footer.** Account selector and account buttons, Play (Stop while the game runs), status line, progress bar, and the collapsible Log panel. The footer is the same in both views. The selected account is saved when it changes.

**Instance menu.** Right-click an instance in either view: Play, Edit, Install / repair, Content..., Open game folder, Export build..., Choose picture..., Use the loader mark, Move to group, Remove. Double-click an instance to play it.

## List and grid views

The header button switches between two views of the same instances:

- **List** - rows with icons; groups are bands.
- **Inventory** - a grid of cells with icon and name, nine across by default. It slides down over the upper part of the window in 260 ms. Its bar repeats the header buttons and adds New, Import, New group and Sort A-Z. Its search field shares its text with the header search.

The last view is saved in `profiles.json` (`layout.mode`), and the launcher opens in it.

### One arrangement

Both views draw one `ProfileLayout`, stored under `layout` in `profiles.json`. The views change it only through `ProfileHost`, so a change in one view is already in the other. It has two parts:

- each instance has one **cell** (row and column);
- each **row** can belong to one named group.

An instance is in a group when its row is. Nothing else records membership. The list reads the cells row by row and skips empty ones, so two instances with a free cell between them are consecutive rows in the list. On a first start, instances are placed in name order.

### Moving instances

In the **grid**, a drop on a free cell puts the instance there, and a drop on an occupied cell swaps the two. No other instance moves.

In the **list**, dragging a row changes which instance is in which occupied cell; the empty cells stay. A row dropped among a group's members joins that group, and a row dropped among ungrouped rows leaves its group. Dragging a group header moves the whole group.

**Sort A-Z** sorts by name inside each group and inside the ungrouped rows. Groups do not move.

### Groups

Groups are one level deep and own whole rows. In the list a group is a tinted band with a coloured bar, its name and its instance count. In the grid its rows are tinted and a coloured plate on the left carries the name.

| Create with | Row it takes |
|---|---|
| New group button | The first empty row with no group, or a new row |
| Move to group > New group (instance menu) | The same, and the instance moves into it |
| New group in this row (right-click an empty cell of an ungrouped row) | That row. If the row has instances, the launcher asks whether to put them in the group or move them to free ungrouped cells (adding rows if needed) |

The group dialog asks for a name and a colour. The default colour is the first of the 16 palette colours that no other group uses. "Mix a colour…" opens a colour chooser; mixed colours are saved in `launcher.json` (`customGroupColors`, newest first, at most 16) and offered for later groups. Right-click a mixed colour to forget it.

- **Join or leave:** drag the instance into or out of the group's cells, or use Move to group (a full group gets another row) and Move to group > No group.
- **Fold:** click the grid plate; in the list, click `-`/`+` on the header or double-click it (only when the group has instances). The fold state is shared by both views.
- **Move:** drag the plate or header, or use Move the group up / down. A group lands above or below another group as a whole, never inside it. Each ungrouped row is a separate drop position.
- **Group menu** (right-click plate or header): Collapse/Expand, Group settings..., Move the group up, Move the group down, Delete group. In the grid it also has Add a row to the group and Remove a row from the group.
- **Delete:** asks first. The rows stop belonging to the group; no instance moves and no game folder changes.

### Grid size

The grid starts at 9 columns by 3 rows. Limits are 2-24 columns and 1-60 rows. It never resizes by itself, except when a new instance has no free cell at all: then it gets a row. A new instance takes the first free ungrouped cell, or a grouped cell when no ungrouped cell is free.

| Control | Position | Effect |
|---|---|---|
| Columns `+` / `-` | Strip above the grid, right | `-` removes the last column and moves its instances to free cells of the same group (or ungrouped cells). If there is no room, nothing changes |
| Rows `+` / `-` | Strip below the grid, left | `+` adds a row at the bottom. `-` removes the last row that is empty and in no group, wherever it is. It never moves an instance |
| Group `+` / `-` | Right end of the band, in the group colour | `+` adds a row under the group. `-` removes the group's last row and moves its instances into the group's other rows. A group's only row stays |

The strips are faint until the pointer is over the grid. A refusal shows as a message over the bottom of the view for nine seconds (click to close) and is written to the log.

The Interface tab of the settings window has the same two numbers. They are stored in `profiles.json`, not `launcher.json`. Lowering the rows there removes rows from the bottom and moves their instances within their group. When a column or row cannot be removed, the size stops at the last value reached and a warning says why.

## Settings window

The cog button in either view opens the settings window. Save writes to `launcher.json` (grid size to `profiles.json`); Cancel writes nothing. The proxy and the mod picture cache size apply at once, without a restart.

| Tab | Settings |
|---|---|
| Interface | Language; Grid columns; Grid rows; Start-up window, seconds |
| Game | Keep the launcher open while the game runs; Hide to the notification area while the game runs; Show snapshots and old versions; Check every file before each launch |
| Java | When Java is missing: Ask each time / Download it / Never download it |
| Downloads | Simultaneous downloads (1-32); Look for launcher updates at start-up; Update channel (Release / Nightly); Check for updates; Route (This computer's settings / Straight out, no proxy / A proxy I type in) with Address, Port, User, Password and Test the connection |
| Mods | Warn before breaking a mod's dependency; Mod picture cache, MB; CurseForge API key, with a button that opens console.curseforge.com |
| Accounts | Microsoft sign-in (In your own browser / With a code); Hand the session token over standard input; Keep credentials in the launcher own encrypted file |
| Data folder | Paths of the data folder and the launcher log folder, each with a button that opens it |

- Test the connection applies the route on screen and fetches the Mojang version manifest. Cancel restores the previous route.
- A manual proxy with no address or port is not saved; the window says so after Save.
- The proxy password goes to the credential store, not `launcher.json`, and is never shown again. Leave the field untouched to keep it; empty it to delete it.
- The Azure client id (`microsoftClientId`) is only in `launcher.json`, for forks.

Keys and defaults are in [configuration.md](configuration.md).

## Instance icons

An instance shows its loader mark (Vanilla, Fabric, Quilt, Forge or NeoForge) unless it has a picture. Set one from the Icon row of the editor or Choose picture... in the menu; Use the loader mark removes it.

- PNG, JPEG, GIF or BMP, up to 8 MB. Transparency is kept and animated GIFs animate. The picture is scaled to fit in proportion; 128 × 128 is best.
- The file is copied to `icons/` in the data folder as the first 16 hex characters of its SHA-1 plus the extension. Instances with the same picture share one file. `profiles.json` stores only the file name, so moving or deleting the original has no effect. Removing an icon does not delete the file.
- A modpack install sets the pack's logo as the picture, and a `.hexbuild` import sets the picture stored in the build. WebP logos are converted to PNG.

The loader marks are drawn in code (`LoaderIcon`); the projects' own logos are their trade marks. A PNG at `/ui/loader/<loader id>.png` in the launcher resources (`vanilla`, `fabric`, `quilt`, `forge`, `neoforge`) replaces the drawn mark and is scaled without smoothing.

## Reporting a bug

The bug button opens the Report a bug window. It asks for a screenshot or short video and a short description of what the bug is, how it happened, and how to reproduce it.

It also names the newest launcher log: the `launcher*.log` file in the log folder with the latest modification time (usually `launcher.log`; after a bad run it can be `launcher-1.log`). Click the name to open the folder with the file selected (`explorer.exe /select,` on Windows, `open -R` on macOS, the folder only elsewhere). With no log yet, the window shows the log folder path.

**Send a report** opens `https://github.com/SAN4EZDREAMS/HexadronLauncher/issues/new` in the system browser. The address is also shown as a link. The launcher uploads nothing; the user attaches the files to the issue. **Close** closes the window.

## While the game runs

After Play, two Game tab settings decide what the window does:

| `minimiseToTrayWhilePlaying` | `keepOpenWhilePlaying` | Window |
|---|---|---|
| `true` (default) | any | Hidden to the notification area; minimised where there is none |
| `false` | `true` (default) | Stays on screen |
| `false` | `false` | Minimised |

The tray icon menu has Show launcher and Stop Minecraft; activating the icon also shows the launcher. When the game exits, the tray icon goes away and the window comes back to the front.

Closing the launcher window while the game runs stops the game when `keepOpenWhilePlaying` is `false`. When it is `true`, the game continues.