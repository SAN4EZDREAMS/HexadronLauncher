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
| Mods | Modrinth works with no key. CurseForge works with an API key. Required dependencies resolve automatically |
| Java | The launcher finds the installed runtimes and selects one that the version requires |
| Assets | Modern, `virtual` (1.6) and `map_to_resources` (pre-1.6) layouts |

## Build

You need JDK 25.

```
./gradlew :launcher:build      # build the launcher
./gradlew :launcher:selfCheck  # verify the launch core, no network needed
./gradlew :launcher:run        # start the window
./gradlew :mod:build           # build the mod
```

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
`launcher/src/main/resources/packs/hexadron-optimise.json`. The Modrinth project
ids there match the `modImplementation` lines in `mod/build.gradle`.

The mods are not bundled into the mod jar with `include`. Bundling other
people's mods is a licensing decision, not a build setting. The launcher
downloads them from Modrinth instead.

## Architecture

The launcher core has no third-party dependencies. It uses only the JDK.

```
json/     small strict JSON tree and parser
util/     platform detection, hashes, maven coordinates
net/      HTTP client with retry, parallel verifying downloader
meta/     version manifest, version JSON, rules, libraries, assets
install/  version installer, asset installer, native extraction, loaders
auth/     accounts, offline UUIDs, Microsoft device code flow
profile/  profiles and their isolated game folders
mods/     Modrinth and CurseForge providers, pack installer
launch/   Java locator, command builder, process control
core/     settings and the application service
ui/       JavaFX window - contains no launch logic
cli/      headless entry point
```

`SelfCheck` verifies the metadata layer with 147 assertions. It needs no network,
no display and no test framework.

## Not done yet

- Forge and NeoForge installation. Both use an installer jar with processors
  that must run locally to patch the client jar.
- Download of a Java runtime when the machine has none.
- Import and export of Modrinth `.mrpack` and CurseForge modpack files.
- Storage of refresh tokens in the operating system credential store. The
  launcher currently writes `accounts.json` with owner-only permissions.

## License

CC0.
