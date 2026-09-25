# Modpacks, data packs, resource packs and shaders

This page covers the Modpacks, Data packs, Resource packs and Shaders sections of the content window. The window itself and the Mods section are described in [mods.md](mods.md).

## Common behaviour

Data packs, resource packs and shaders work the same way:

- The folder on disk is the authority. Packs that the launcher did not download are listed too. A record file beside the packs lists the ones it did download.
- A pack can be a zip or an unpacked folder.
- The on/off switch renames a zip to `<name>.zip.disabled`. A folder cannot be switched off (the game still loads a renamed folder), so its switch is disabled.
- **Remove** deletes a pack that the launcher installed. Other packs go to the recycle bin, or to a `.removed` folder beside the packs if there is no recycle bin.
- **Import…** (or a drop on the list) copies zips into the folder. It never overwrites a file and records nothing.
- There is no version check. The row shows `pack format <n>` from `pack.mcmeta` when there is one.

| Kind | Folder | Record file | Catalogue filter | Import accepts a zip with |
|---|---|---|---|---|
| Data pack | `saves/<world>/datapacks` | `.hexadron-datapacks.json` | Minecraft version | `pack.mcmeta` |
| Resource pack | `resourcepacks` | `.hexadron-resourcepacks.json` | Minecraft version | `pack.mcmeta` |
| Shader pack | `shaderpacks` | `.hexadron-shaderpacks.json` | None | `shaders/`, at the root or one folder down |
| Modpack | whole instance | `.hexadron-modpacks.json` | Optional, see below | - |

## Modpacks

A modpack sets a Minecraft version, a loader version and a set of files for a whole instance. The launcher reads two formats (`PackArchive`). It detects the format from the file contents, not from the extension.

| Format | Manifest | Files | Loader |
|---|---|---|---|
| Modrinth `.mrpack` | `modrinth.index.json` | Path, download URLs and SHA-1 | Dependency, for example `fabric-loader: 0.16.9` |
| CurseForge `.zip` | `manifest.json` | Project ID and file ID, resolved through the CurseForge API | String, for example `fabric-0.16.9`. The entry marked `primary` wins, otherwise the first |

A CurseForge pack needs a CurseForge API key (see [configuration.md](configuration.md)). Without a key, its files cannot be resolved and are reported as skipped.

### Catalogue

The Browse tab has the check box **Show only modpacks that fit this profile**. It is ticked by default and limits the list to packs for the profile's Minecraft version and loader. Untick it to see all packs.

### Install

Before anything is downloaded, the launcher asks where the pack goes:

- **New profile** (the default button): a new profile with the pack's name, Minecraft version, loader and loader version. If the pack came from a platform, the profile gets the pack's logo as its picture. If the name is taken, the launcher adds a suffix such as ` (2)`.
- **This profile**: the profile's Minecraft version and loader change to the pack's, and the pack's files are written into it. The dialog warns that mods you installed yourself can stop working.

Then the launcher:

1. Downloads the pack archive to `cache/modpacks` (platform packs only).
2. Downloads the files the manifest names. A Modrinth file marked `client: unsupported` is skipped. An optional file is installed, but its failure does not stop the install.
3. For a CurseForge file whose author has disabled third-party downloads, it looks for a file with the same SHA-1 on Modrinth. If there is none, the file is listed for manual download.
4. Copies the pack's `overrides` over the instance (for a `.mrpack`, `client-overrides` after `overrides`). It does not change these files.
5. Records every path it wrote in `.hexadron-modpacks.json`.

If no file downloads, the install fails. Skipped files and manual downloads are shown in one message at the end.

### Path checks

A path in a manifest or in `overrides` is untrusted input. `ModpackInstaller.safeRelative` refuses:

- network paths (two leading slashes),
- any path with `:` (Windows drives, URLs),
- any `..` segment,
- any path that still resolves outside the instance folder after normalisation.

A single leading `/` is removed and the rest is treated as relative to the instance. Refused paths are reported.

### Files a modpack owns

Downloaded files in `mods`, `resourcepacks` and `shaderpacks` are marked as the pack's own in that folder's record file. This applies to CurseForge files and to Modrinth files with a `https://cdn.modrinth.com/data/<project>/versions/<version>/` URL. A file you had already installed yourself is not taken over. The Resource packs and Shaders sections also match files against the paths in the modpack record, so packs from `overrides` show as the modpack's too.

A pack-owned row has a modpack badge. Hover over it and click the modpack name to open it in the Modpacks section. The row's **Remove** button is disabled.

### Remove

**Remove** on a modpack row deletes the paths that the pack recorded, and nothing else. Folders are removed only when they are empty. The pack's entries are also removed from the record files of `mods`, `resourcepacks` and `shaderpacks`.

### Local files

**Open file...** installs a `.mrpack` or `.zip` from disk, for example a pack whose author has disabled third-party downloads. You can also drop a file on the Installed list. Only the first dropped file is used.

## Data packs

Minecraft loads data packs per world from `saves/<world>/datapacks`. The section has a **World** picker. It lists the worlds that have a `level.dat`, most recently played first, and selects the first one. **Reload** reads the worlds again.

- If the instance has no worlds, the section says so and install and import are disabled.
- Data packs need no mod loader, so this section works on a vanilla instance. The catalogue is never filtered by loader.

### What gets installed

The launcher always asks the platform for the data pack build (a `.zip`). Many projects also publish a mod build (a `.jar`) of the same content. A jar is refused, with a message to install it from Mods.

Some data pack versions name required mods. By default the launcher installs them into the instance's `mods` folder and marks them as belonging to that data pack and world. Removing the data pack also removes those mods. Tick **Install data packs without mods** to install only the pack. On an instance without a mod loader, this box is ticked and locked.

## Resource packs and shaders

Both sections use `ui/PackSection.java`, `mods/PackScan.java` and `mods/PackInstaller.java`. Vanilla Minecraft loads resource packs. The shader catalogue is not filtered by Minecraft version, because shader packs are written for a shader loader, not for a Minecraft release.

### Shader loaders

A shader pack needs Iris, OptiFine or Canvas, which are mods. `ShaderLoaders` finds them among the enabled jars in `mods`: the file name or title must contain `iris`, `oculus`, `optifine` or `canvas`, and the jar's mod ID must agree (`oculus` counts as Iris). A jar with no readable mod ID, such as OptiFine, is accepted by its name.

The section shows **Loaded by <loader>**. With no loader, it shows a warning and **Find Iris in Mods**, which runs that search in the Mods section. The launcher does not install the loader, and it does not block the shader install. The loaders it finds are passed as `loaderTags` to `ModProvider.resolveFile`, so Modrinth returns the build for your loader.

### Requirements

A pack version can name required dependencies:

- A dependency of the same kind (for example, a base resource pack) is installed into the same folder and recorded as a dependency. The chain is followed up to 3 levels. Removing the pack also removes such dependencies that no other pack needs.
- A dependency of another kind (usually the shader loader or a mod) is not installed. The launcher names it in a message and tells you where to install it.

### Game settings

The launcher manages only the folders. It does not write `options.txt` (which resource packs are on) or `config/iris.properties` (which shader is selected). Choose those in the game or in the shader loader's settings.
