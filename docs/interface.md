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

- The splash stays up for at least `splashMinimumMillis` (default 1000). Change it on the General tab of the settings window (0-15 seconds) or in `launcher.json`. Zero removes the minimum.
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
| Instances [sort] | [icon] My world |
| | Modded set 2.. | Minecraft 26.2 |
| | [F] My world | Loader Fabric 0.19.3 |
| | [F] Sky | Memory 4096 MB |
| [V] 1.8 | Java Detected automatically |
| | Last played 16 Aug 2026, 14:47 |
| | Folder ...\instances\1-027f96 |
| | [Edit][Content...][Install / repair][...] |
| [ + New      | v ] | Mods (5) |
+---------------------+------------------------------------------+
| Account: [ v ] [Manage accounts] [Add Microsoft account]  |
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

**Sidebar.** Only actions on the list itself. The sort icon next to the heading sorts A-Z. Under the list is one button, **New**: a click creates an instance. Its arrow opens a menu as wide as the button, with a picture and a one-line description for each entry: New instance..., Import build... (`.hexbuild` files, see [builds-and-storage.md](builds-and-storage.md)) and, under a rule, New group. Actions on one instance are in the detail panel and in the instance menu.

**Detail panel.** A read-only summary of the selected instance and its mods. The Java line has the link **Find Java on this computer**: it searches for Java runtimes and lists them in the Log panel, see [java-and-loaders.md](java-and-loaders.md). The Folder line has the link **Open**. Under the summary is one row of buttons, each with a picture: Edit, Content..., Install / repair, and **More** (three dots). More opens a menu with Export build..., Open game folder and, under a rule and in red, Remove instance....

| Button | Action |
|---|---|
| Edit | Opens the instance editor |
| Install / repair | Downloads and checks the game files, loader and libraries |
| Content... | Opens the content window, see [mods.md](mods.md) |
| More > Export build... | Writes the instance to a `.hexbuild` file, see [builds-and-storage.md](builds-and-storage.md) |
| More > Open game folder | Opens the instance folder (also the **Open** link on the Folder line) |
| More > Remove instance... | Removes the instance, with or without its files |

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

The General tab of the settings window has the same two numbers. They are stored in `profiles.json`, not `launcher.json`. Lowering the rows there removes rows from the bottom and moves their instances within their group. When a column or row cannot be removed, the size stops at the last value reached and a warning says why.

## Settings window

The cog button in either view opens the settings window. Save writes to `launcher.json` (grid size to `profiles.json`); Cancel writes nothing. The proxy, the game's SOCKS proxy, the memory for new instances, the number of simultaneous downloads, the credential store and the mod picture cache size apply at once, without a restart. Settings that few people need are in a folded **Advanced** block at the end of their tab.

| Tab | Settings |
|---|---|
| General | Language (As the system, or one of the 16 languages); While the game runs (Hide to the notification area / Minimise the window / Keep the window as it is); Closing the launcher stops the game; Grid columns; Grid rows; Start-up window, seconds |
| Appearance | Theme (7 themes); Colours (12 colours over the theme, Back to the theme's colours); Background picture with Placement, Fade, Blur and Panel opacity; Interface font; Fixed-width font; Text size (80-150 %); Export theme, Import theme, Reset appearance. See [Appearance](#appearance) |
| Game | When Java is missing: Ask each time / Download it / Never download it; Memory for new instances, MB, with Automatic; Advanced: Check every file before each launch |
| Network | Simultaneous downloads (1-32); Look for launcher updates at start-up; Update channel (Release / Nightly); Check for updates; Proxy (This computer's settings / Straight out, no proxy / A proxy I type in) with Address, Port, User, Password and Test the connection; SOCKS proxy for the game with Port |
| Mods | Warn before breaking a mod's dependency; Look for mod updates; CurseForge API key, with a button that opens console.curseforge.com |
| Data and security | Paths of the data folder and the launcher log folder, each with a button that opens it; Mod picture cache, MB; Storage and cleanup (opens the storage window); Microsoft sign-in (In your own browser / With a code); Advanced: Hand the session token over standard input, Keep credentials in the launcher own encrypted file |

- Test the connection applies the route on screen and fetches the Mojang version manifest. Cancel restores the previous route.
- A manual proxy with no address or port is not saved; the window says so after Save. The same applies to a SOCKS host that is not a host name or an address.
- The SOCKS proxy for the game is given as Minecraft's own `--proxyHost` and `--proxyPort`, before the instance's own game arguments. Minecraft uses it for sign-in to servers, skins and Realms. The launcher does not use it (Java's HTTP client has no SOCKS support), and connections to game servers do not go through it.
- "Show snapshots and old versions" is only in the instance editor; the editor remembers it.
- A manual proxy also goes to the game: each game starts with `-Dhttp.proxyHost`, `-Dhttp.proxyPort`, `-Dhttps.proxyHost`, `-Dhttps.proxyPort` and `-Dhttp.nonProxyHosts=localhost|127.*|[::1]|10.*|192.168.*`, before the profile's own Java arguments (so a profile can override them). Libraries and mods that leave the proxy to Java follow them. Minecraft's own sign-in to servers, skins and Realms does not: the game connects there with an explicit "no proxy" unless it is given a SOCKS proxy. The user name and password are not passed: Java has no standard property for them, and a command line is readable by other programs.
- The proxy password goes to the credential store, not `launcher.json`, and is never shown again. Leave the field untouched to keep it; empty it to delete it.
- The Azure client id (`microsoftClientId`) is only in `launcher.json`, for forks.

Keys and defaults are in [configuration.md](configuration.md).

## Appearance

The Appearance tab of the settings window changes how every launcher window looks. Each change shows at once in all open windows. **Save** keeps the changes; **Cancel** (or closing the window) puts back the previous look.

**Theme.** A theme is a palette of 12 colours:

| Theme | `theme` | Window |
|---|---|---|
| Hexadron (default) | `hexadron` | Dark grey, green accent. The launcher's original look |
| Light | `light` | Light grey and white |
| OLED | `oled` | Black |
| Midnight | `midnight` | Dark blue, blue accent |
| Nether | `nether` | Dark red-brown, orange accent |
| End | `end` | Dark violet, purple accent |
| Birch | `birch` | Warm light |

**Colours.** Click a colour to change it with the colour chooser. A changed colour is marked with `*`. A change applies on top of the chosen theme; choosing another theme removes the changes, and **Back to the theme's colours** removes them too. When text on panels has a contrast below 4.5:1, a warning shows under the colours.

The launcher calculates the other colours from the 12: text on coloured buttons and badges (white or near-black, whichever reads better), status text (made readable on panels), and the colours of modena (the JavaFX default style) for parts that the launcher stylesheet does not name. When the window colour is light, the launcher also uses `hexadron-light.css`, which mixes the soft tints towards white instead of black.

**Background picture.** **Choose...** copies a PNG, JPEG, GIF or BMP file (25 MB at most) to `backgrounds/` in the data folder, named by the first 16 hex characters of its SHA-1. The picture shows in every launcher window, including dialogs. Menus and tooltips stay solid.

- **Placement**: Fill the window (cover), Show all of it (contain), Stretch, Centre, Tile.
- **Fade** (0-90 %): mixes the picture with the window colour.
- **Blur** (0-40 px).
- **Panel opacity** (30-100 %): how solid the panels are over the picture.

The launcher prepares the picture once for each fade, blur and window colour (scaled to 2560 pixels at most, saved as JPEG in `cache/theme/`). Prepared pictures that are not in use are deleted after Save and at start-up.

**Fonts.** **Interface font** and **Fixed-width font** list the fonts installed on the computer. The fixed-width font is used for logs and code. **Text size** changes all text in the launcher together. The main window and the settings window get wider when the text is larger, but not wider than the screen.

**Theme files.** **Export theme...** writes a `.hxtheme` file: JSON with the theme, colours, fonts and picture settings, and the picture itself (base64). The picture's path on the computer is not written. **Import theme...** reads such a file and copies its picture to the data folder. **Reset appearance** goes back to the Hexadron theme with no picture and the default fonts.

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

After Play, **While the game runs** on the General tab (`whilePlaying`) decides what the window does:

| Choice | `whilePlaying` | Window |
|---|---|---|
| Hide to the notification area (default) | `tray` | Hidden to the notification area; minimised where there is none |
| Minimise the window | `minimise` | Minimised |
| Keep the window as it is | `stay` | Stays on screen |

The tray icon menu has Show launcher and Stop Minecraft; activating the icon also shows the launcher. When the game exits, the tray icon goes away and the window comes back to the front.

Closing the launcher window while the game runs stops the game when **Closing the launcher stops the game** (`stopGameOnClose`) is on. When it is off (default), the game continues.