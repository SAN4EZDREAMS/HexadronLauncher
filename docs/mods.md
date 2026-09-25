# Mods

This page covers the content window and how to search, install, switch off and remove mods. It also covers dependency warnings, mods left behind by a version change, the Hexadron Optimise set and the Mod Menu mod count.

Modpacks, data packs, resource packs and shaders use the same window. See [packs.md](packs.md).

## The content window

Select an instance and click **Content...**. The window is not modal. Each instance has one content window; if it is open, **Content...** brings it to the front. When you save changes in the instance dialog, the instance's content window closes, because its searches were made for the old version and loader.

```
+--------------------------------------------------------------+
| My world  26.2 · Fabric       [ Install Hexadron Optimise ]  |
+----+---------------------------------------------------------+
| M  | [ Browse ] [ Installed (5) ]                            |
| P  | [ search ... ] [Most popular] [Category] [All sources]  |
| D  | [] Sodium                                               |
| R  |    Modrinth · jellysquid3 · 1.2M downloads  [ Install ] |
| S  |    A modern rendering engine ...                        |
+----+---------------------------------------------------------+
| Showing 40 of 3812                                           |
| [=================== progress ===================]           |
+--------------------------------------------------------------+
```

The rail on the left has one row for each kind: Mods, Modpacks, Data packs, Resource packs, Shaders. It is 52 pixels wide and shows icons only. It opens to show the names when the pointer is on it or a row has keyboard focus. The open rail is drawn over the panel, so the lists do not move. Its width follows the longest translated name. The filled row is the section that is showing. All five rows are always present; a section that the instance cannot use says why.

The status line and progress bar belong to the window, not to a section. Only one change (install, removal, switch) runs at a time. A second one does not start, and the status line shows `Busy - "<action>" ignored`. Searches are never blocked.

## Browse

The **Browse** tab searches Modrinth and CurseForge for mods that match the instance's Minecraft version and loader. An instance with no loader cannot use mods; see [java-and-loaders.md](java-and-loaders.md).

| Control | Values |
|---|---|
| Sort | **Best match**, **Most downloaded**, **Most popular** (default), **Recently updated**, **Newest** |
| **Category** | Tick boxes, see below |
| Source | **All sources**, **Modrinth**, **CurseForge** |
| **Show more** | Next page |

Each search asks each platform for 40 results. The status line shows the count and the platforms' total, for example "Showing 40 of 3812". If one platform fails, the other's results are still shown and the status line adds "not searched:" with the platform and reason. Search errors never open a dialog.

On a Quilt instance, Modrinth is searched for `quilt` or `fabric` mods, and CurseForge for Fabric mods.

If the build has no CurseForge key, a note says so and **Add a key** opens a dialog for one. See [configuration.md](configuration.md).

### Categories

The **Category** menu lists the 19 Modrinth mod categories in two columns, sorted by translated name. The menu stays open while you tick boxes. **Clear all** removes the ticks. A result must have all ticked categories.

The list is fixed in `ModCategory` so that it can be translated and works offline. For CurseForge, `CurseForgeCategories` maps each category to CurseForge's own by meaning. If a ticked category has no CurseForge equivalent, CurseForge is not searched and the status line says "no equivalent here for" and names it.

Category icons come from Modrinth and are kept in `cache/mod-categories.json` in the data folder. They are fetched again after 30 days, or when a category has none.

### Result rows and installing

A row shows the logo, name, platform, author, downloads, description and categories. Ticked categories come first. Categories that do not fit are behind a `+N` mark; its tooltip lists them. **More about this mod** opens the project page in your browser.

**Install** does this:

- Downloads the newest build for the instance's version and loader, and its required dependencies, up to 6 levels deep. Dependencies already in the instance are not changed.
- Records the chosen mod as `MANUAL` and each dependency as `DEPENDENCY`.
- If a CurseForge author blocks third-party downloads, looks for a file with the same SHA-1 on Modrinth. If there is none, you must download it manually.
- Lists skipped mods and manual downloads in the dialog "Some mods need attention".

## The Installed tab

The **Installed** tab lists every `.jar` and `.jar.disabled` file in the instance's `mods` folder, including jars you added yourself. Files whose names start with `.` are ignored. The folder is the source of truth: a recorded mod whose file is gone is not listed. Switched-off mods are listed last.

Each row shows:

- **Picture**: the Modrinth or CurseForge logo, else the icon inside the jar, else a coloured tile with the first letter.
- **Name, version, authors, description**: from the jar's descriptor (`fabric.mod.json`, `quilt.mod.json`, `META-INF/neoforge.mods.toml`, `META-INF/mods.toml`, `mcmod.info`, then `META-INF/MANIFEST.MF`). This works offline.
- **File name**, **categories**, a **badge**, the **on/off switch** and **Remove**.
- **More about this mod**: the recorded project page, else the homepage in the jar. Only `http` and `https` links are shown.

Logos are downloaded through the launcher's HTTP client (so its proxy setting applies) and kept in `cache/mod-icons`. The **Mod picture cache, MB** setting (`modIconCacheMegabytes`, default 32, range 8-1024) limits that folder; the least recently used logos are deleted first. JavaFX cannot read WebP, so `util/Webp` decodes lossless WebP. A lossy WebP logo shows the letter tile.

Each time the window opens, the launcher asks Modrinth for the logo, page and categories of launcher-installed Modrinth mods whose records lack them. Values already recorded are not changed.

### Badges

| Badge | Colour | Meaning |
|---|---|---|
| switched off | Outline only | Renamed to `.disabled`; the loader ignores it. Takes priority over all others. |
| for another version | Red, bold | Declares a different Minecraft version. See [below](#mods-left-behind-by-a-version-change). |
| modpack | Violet | Installed by a modpack ([packs.md](packs.md)). |
| Hexadron Optimise | Green | Part of the [Hexadron Optimise](#hexadron-optimise) set. |
| dependency | Amber, bold | Installed because another mod required it. |
| data pack | Blue | Installed because a data pack needs it ([packs.md](packs.md)). |
| installed by you | Grey | Chosen in Browse. |
| user's own mod | Grey | Not installed by the launcher. |

Hovering a badge opens a panel when there is more to say:

- **Needed by these mods**: the switched-on mods that require this one. Click a name to go to its row.
- A dependency that nothing needs now: "Installed as another mod's requirement. Nothing in this instance needs it now."
- A modpack or data pack mod: the pack's name. Click it to go to the pack in its section.

### The on/off switch

Rows in the mods, data packs, resource packs and shaders lists have an on/off switch (`ui/OnOffSwitch.java`) that shows the current state:

| State | Knob | Track | Word |
|---|---|---|---|
| On | Right | Dark green | **On** |
| Off | Left | Dark red | **Off** |

A click, or Space or Enter, asks for the change. The switch moves only when the list shows the new state, so a cancelled or failed change never looks done. The knob slides for 150 ms when the same item changes; a reused list cell jumps with no animation. Both states have the same width. Screen readers see a toggle button whose text is the action ("Switch off" or "Switch on"). The switch is disabled while a change runs. CSS: `.on-off-switch`, with the `:on` pseudo-class.

For a mod, off renames `name.jar` to `name.jar.disabled`, and on renames it back. If the target name already exists, the switch fails with an error.

### Remove

Removal always asks first.

- A launcher-installed mod is deleted (also if switched off). You can install it again.
- A jar the launcher did not install goes to the recycle bin, or to `mods/.removed` if the desktop has none. The launcher cannot download it again, so it does not delete it.
- **Remove** is disabled for Hexadron Optimise mods, modpack mods and data pack mods. They are removed with their set or pack.

### Import

**Import…** copies `.jar` files into the `mods` folder. You can select several files or drop them onto the list. The originals stay where they are. Existing files are never overwritten. Imported jars are not recorded; they show as "user's own mod". The only content check is that the file is a valid zip archive.

Refused files are listed by name in "Some files were not imported", with the reason: not a file, not a jar, already in this instance, not an archive (an unfinished download), or the copy error.

### Search, filter and Identify

The search field matches the mod name, the file name and the authors. The filter shows **All mods**, **Installed by the launcher**, **Your own mods**, **Switched off** or **For another version**. A narrowed list shows "Showing N of M".

**Identify** appears when there are jars the launcher did not install and has not asked about. It sends the SHA-1 of each to Modrinth. Known files then show their project name, logo, page and categories. Nothing is sent unless you click the button. Answers, including "not known", are stored in `mods/.hexadron-external.json` by file name and size, so a file is asked about again only if its size changes. Identified jars stay "user's own mod".

The instance summary in the main window shows the same list with badges and no buttons. See [interface.md](interface.md).

## Dependency warnings

Each jar declares the mods it needs in its descriptor. The launcher reads these declarations from the switched-on jars in the folder, including your own jars. Switched-off mods are ignored, because they are not loaded.

When you switch off or remove a mod that other switched-on mods need, the dialog "Other mods need this one" names up to 8 of them, then "... and N more". Switching a mod on never asks.

**Do not show this again** turns the warning off, whichever button you click. To turn it on again, tick **Warn before breaking a mod's dependency** on the **Mods** tab in Settings (`warnAboutDependents` in `launcher.json`, default `true`).

## Mods left behind by a version change

Changing an instance's Minecraft version does not move or delete its mods. Jars built for the old version then stop the loader from starting.

The launcher checks the Minecraft range each jar declares, with `mods/VersionRanges`:

| Loaders | Format | Examples |
|---|---|---|
| Fabric, Quilt | npm-style | `~26.2`, `>=1.20.1 <1.21`, `1.20.x` |
| Forge, NeoForge | Maven | `[1.20.1,1.21)` |

It warns only when it is sure. A range it cannot parse, a snapshot such as `23w31a`, a mod with no range and a switched-off mod give no result. False warnings would teach users to ignore real ones.

A mod that fails the check:

- shows the red **for another version** badge; the tooltip gives the version it needs;
- is counted after you save a version or loader change: "The mods in this instance are for the old version";
- stops **Play** with "Some mods are for another Minecraft version", which names up to 8 mods. **Launch anyway** starts the game, because a mod's declared range can be wrong.

The profile records its previous Minecraft version (`previousMinecraftVersion`). If some mods fail for the current version and none fail for the previous one, the **Play** dialog also offers **Go back to** *version*. This sets the version back, replaces each launcher-installed mod with the newest build for that version (or switches it off if there is none; leaves it if the platform cannot be reached), then switches off any mod that still fails. The game does not start; click **Play** again.

## Hexadron Optimise

Hexadron Optimise is a set of performance mods defined in `launcher/src/main/resources/packs/hexadron-optimise.json`. All entries are Modrinth projects with no pinned build, so the newest compatible build is installed. The mod of the same name is described in [architecture.md](architecture.md).

**Install Hexadron Optimise** is in the header while the Mods section shows. When the set cannot be installed, the button is disabled and a line under it says why:

| Condition | Message |
|---|---|
| Check running | "Checking which of Hexadron Optimise this profile can run…" |
| No loader | "This profile has no mod loader. Set it to Fabric, Quilt or NeoForge to install Hexadron Optimise." |
| Loader not in the set's `loaders` | "Hexadron Optimise is not published for *loader*. It covers: *loaders*." |
| A required mod has no build | "Hexadron Optimise has no build for Minecraft *version* on *loader*. No build yet for: *mods*." |

The first three need no network request. The last asks the platform about each required entry; optional and conditional entries are not checked.

| Mod | Fabric | Quilt | NeoForge | Notes |
|---|---|---|---|---|
| Fabric API | yes | - | - | |
| Quilted Fabric API | - | yes | - | Replaces Fabric API on Quilt |
| Sodium | yes | yes | yes | |
| Indium | yes | yes | - | Optional; only if the resolved Sodium is older than 0.6.0 |
| Lithium | yes | yes | yes | |
| FerriteCore | yes | yes | yes | |
| Krypton | yes | yes | - | |
| EntityCulling | yes | yes | yes | |
| Iris Shaders | yes | yes | yes | |
| Mod Menu | yes | yes | - | Optional |

Forge is not supported, because most of the set has no Forge build. An optional or conditional entry that is left out does not fail the install; it is listed in "Some mods need attention" with the reason.

When the set is installed, the button becomes **Remove Hexadron Optimise**. It stays enabled even if the set can no longer be installed, for example after a loader change. The set is installed and removed as a whole.

`mods/.hexadron-mods.json` records the origin of each launcher-installed mod: `PACK`, `MANUAL`, `DEPENDENCY` or `DATAPACK`. Because of this record:

- an install of the set removes only files the set owned before and no longer includes;
- a set mod that you installed yourself earlier stays `MANUAL` and is not removed with the set;
- jars you copied in are not in the record, and change only when you use their own row.

## The Mod Menu mod count

Mod Menu's title screen count includes libraries, hidden mods and modules nested in other mods (Fabric API has about fifty). When a mod or set install includes a file whose name starts with `modmenu`, the launcher sets these keys to `false` in `config/modmenu.json` in the instance's game directory:

- `count_libraries`
- `count_hidden_mods`
- `count_children`

The count then shows the mods you installed. Mod Menu's list and mod loading do not change. A key is written only if the file does not have it yet, so your own choices in Mod Menu are kept. Other keys are not changed.

## Files

| Path | Content |
|---|---|
| `<instance>/mods/.hexadron-mods.json` | Launcher-installed mods and their origin |
| `<instance>/mods/.hexadron-external.json` | **Identify** answers |
| `<instance>/mods/.removed/` | Your own jars removed without a recycle bin |
| `<instance>/config/modmenu.json` | Mod Menu count options |
| `<data folder>/cache/mod-categories.json` | Category icons |
| `<data folder>/cache/mod-icons/` | Mod logos |

Main classes: `ui/ContentBrowserWindow` (window, rail, mods section), `mods/ModScan` (folder, import, switch), `mods/ModInstaller` (search, install, sets), `mods/ContentKind` (per-kind platform constants). See [architecture.md](architecture.md).
