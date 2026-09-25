# Build files and storage cleanup

This page covers `.hexbuild` build files (export and import of a profile) and the **Storage and cleanup** window.

## Build files

A build file packs one profile so that another copy of the launcher can make the same instance.

- Export with **Export build...** in the profile's right-click menu, or **Export** in the sidebar.
- Import with **Import** in the sidebar, or drop a `.hexbuild` file anywhere on the main window. Of several dropped builds, only the first is imported.
- An import always makes a new profile. It is refused while the launcher is busy.

### Format

A build is a zip with the extension `.hexbuild` (`share/BuildFormat.java`):

| Entry | Content |
|---|---|
| `hexadron-build.json` | Manifest: format version, the launcher that made it, profile settings, every file that can be downloaded again (path, HTTPS URL, SHA-1, size, and the launcher's record of it), files that can only be carried, and the modpack records |
| `files/<path>` | Files carried inside the build, at their path in the instance |
| `icon/<name>` | The profile's own picture, up to 4 MB |

The profile settings are: name, Minecraft version, loader and loader version, memory, Java major version, window size (when set), demo mode, icon, and extra JVM and game arguments.

**Never exported:** accounts, the Java path and the wrapper command. Record files (`.hexadron-*`) are not carried; the import writes new ones from the manifest. A build with a newer format version is refused.

### Export

The export dialog has these options:

| Option | Default | What goes in |
|---|---|---|
| Mods | On | `mods` |
| Resource packs | On | `resourcepacks` |
| Shaders | On | `shaderpacks` |
| Mod and game settings | On | `config`, `defaultconfigs`, `kubejs`, `scripts`, `options.txt`, `optionsof.txt`, `optionsshaders.txt`, `servers.dat` |
| Worlds, with their data packs | Off (disabled if there are no worlds) | `saves/<world>`, without `session.lock` |

Each file in a content folder is sorted like this:

1. A file that the launcher downloaded, and whose SHA-1 still matches its record, is named by its URL.
2. For any other file, Modrinth is asked whether it publishes that SHA-1. A match is named by the Modrinth URL.
3. Anything left is a custom file (a jar you built, a pack from another site, an unpacked folder). The launcher asks: "The build contains personal custom mods with no information about them. Export them too?" If you answer No, the manifest lists them as missing.

### Import

The import dialog shows what the build contains and lets you change the profile name. **Use the launch arguments from the build** is off by default, because a JVM argument can load code or run a command. If the build carries custom files, the launcher asks whether to import them.

Checks on import (`share/BuildImport.java`):

- Every path must land inside the new instance (`ModpackInstaller.safeRelative`, see [packs.md](packs.md)).
- A download entry must sit directly in `mods`, `resourcepacks`, `shaderpacks` or `saves/<world>/datapacks`, and have an HTTPS URL and a SHA-1. The URL must be on `cdn.modrinth.com`, a `*.forgecdn.net` host, `github.com`, `raw.githubusercontent.com` or `gitlab.com`. Other entries are refused and reported.
- Every download must match its SHA-1.

Modpack records are restored with only the paths that arrived.

## Storage and cleanup

The broom button in the header opens **Storage and cleanup**. It measures the data folder and shows four tiles (**Launcher data**, **Safe to free**, **Free on the drive**, **Largest category**), a ring chart by category, and the 7 largest items. Every tile, row and slice has a tooltip. There are two modes: **Safe** (the default) and **Advanced**.

### Safe mode

Safe mode works out what the profiles use and offers only what they do not use. It never offers anything inside an instance folder.

| Suggestion | Condition |
|---|---|
| Unused Minecraft and loader versions, and their natives | No profile runs the version and no used version inherits from it |
| Libraries no version needs | No installed version names the file. While a Forge-style version is installed, `net/minecraftforge`, `net/neoforged`, `net/minecraft`, `de/oceanlabs` and `cpw/mods` are kept |
| Unused game assets | No installed version's asset index lists them |
| Unused Java runtimes | Downloaded by the launcher (it has the launcher's marker file) and no profile needs that major version |
| Cache | `cache/modpacks`, `cache/loaders`, `cache/java` and `cache/mod-icons` |
| Unfinished downloads | `.part` and similar temporary files older than 15 minutes |
| Launcher logs of earlier runs | `launcher-<n>.log`, except the current log |
| Unused profile icons | No profile uses the picture |
| Leftovers of deleted profiles | `instances/.deleting` |

Safe mode holds back when it cannot be sure:

| Condition | Not offered |
|---|---|
| The data folder has `launcher_profiles.json` (it is shared with the official launcher) | Versions, natives, libraries, assets |
| The version of a profile cannot be read | Libraries, assets |
| An installed version has no asset index on disk | Assets |
| A profile has no recorded Java version | Java runtimes |

### Advanced mode

Advanced mode shows the whole data folder as a tree, down to worlds and mod jars, with a size bar on each row. A folder with more than 400 entries is shown as one row.

- Nothing can be ticked until you tick the box under the red warning. The confirm dialog has a second box.
- Deletion is permanent. It does not use the recycle bin.
- Ticking a whole instance folder removes its profile too, the same as the **Remove** button.

### Protected files and checks

These files cannot be ticked, and `StorageCleaner` refuses them again at delete time: `launcher.json`, `accounts.json`, `profiles.json`, `secrets`, `wrapper`, the skins index and the log being written. An `agents` folder left by an earlier version is not used any more and can be deleted. A folder that holds one of them is refused too.

Cleanup does not start while the game runs or the launcher is busy. It keeps the launcher busy while it deletes.

After a cleanup, the remaining profiles are checked against the disk. A profile whose version was deleted shows as not installed, and one whose custom picture was deleted goes back to its normal icon. Content windows of removed profiles close.
