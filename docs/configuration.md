# Configuration

This page covers the data folder and its layout, the settings in `launcher.json`,
Microsoft sign-in, the CurseForge API key, languages and the
command-line mode.

## Data folder

The launcher keeps all its data in one folder:

| Platform | Location |
|---|---|
| Windows | `%APPDATA%\.hexadronlauncher` (the user's home folder if `APPDATA` is not set) |
| macOS | `~/Library/Application Support/hexadronlauncher` |
| Linux | `$XDG_DATA_HOME/hexadronlauncher`, or `~/.local/share/hexadronlauncher` when `XDG_DATA_HOME` is not set |
| Flatpak | `~/.var/app/io.github.san4ezdreams.HexadronLauncher/data/hexadronlauncher` |

To use a different folder, start the launcher with `-Dhexadron.root=<path>`.
The **Data folder** tab in the settings window shows the folder in use and opens it.

### Layout

| Path | Content |
|---|---|
| `launcher.json` | Launcher settings (see [Settings](#settings-launcherjson)) |
| `accounts.json` | Saved accounts: name, UUID, token expiry, selected account. No credentials |
| `profiles.json` | Profiles, plus their arrangement: view mode, grid size, the cell of each profile, groups and which rows belong to them |
| `versions/<id>/` | Version manifest `<id>.json` and client jar `<id>.jar` |
| `libraries/` | Shared library store |
| `assets/indexes/`, `assets/objects/` | Shared asset store. `assets/virtual/<id>/` holds unpacked assets for versions before 1.7 |
| `natives/<id>/` | Native libraries unpacked for each version |
| `java/<component>/` | Java runtimes that the launcher downloaded |
| `instances/<profile>/` | Game folder of each profile: mods, worlds, config |
| `icons/` | Pictures chosen as profile icons. Each copy is named after the first 16 characters of its SHA-1 |
| `skins/` | Chosen skins and capes, and `skins.json` (local skin cache and active selections) |
| `wrapper/` | The internal launch wrapper jar for secure session handshakes |
| `secrets/` | Credential files, when the credential store in use keeps files here (see [Credential storage](#credential-storage)) |
| `cache/` | Version manifest, `verified.index`, downloaded modpacks, loader installers, Java archives, mod logos (`mod-icons/`) |
| `logs/` | `launcher.log` for the current run, and `launcher-1.log` to `launcher-5.log` for the five runs before it |

Libraries, assets and client jars are shared by all profiles. Each profile has
its own game folder, so mods, configs and worlds stay separate.

### Record files in instance folders

The launcher records which files it wrote, so that it never touches files it did
not install. A file that is not in a record is treated as the user's own.

| File | Location | Records |
|---|---|---|
| `.hexadron-mods.json` | `mods/` | Mods the launcher downloaded |
| `.hexadron-external.json` | `mods/` | What Modrinth reported about jars the launcher did not download |
| `.hexadron-resourcepacks.json` | `resourcepacks/` | Resource packs the launcher downloaded |
| `.hexadron-shaderpacks.json` | `shaderpacks/` | Shader packs the launcher downloaded |
| `.hexadron-datapacks.json` | `saves/<world>/datapacks/` | Data packs the launcher downloaded. It stays with the world when you copy the world |
| `.hexadron-modpacks.json` | instance folder | Each installed modpack and every path it wrote. Removing a pack uses this list |

A runtime in `java/` has a `.hexadron-runtime.json` marker.

### Mod logo cache

`cache/mod-icons/` is limited by size (`modIconCacheMegabytes`, **Settings > Mods >
Mod picture cache, MB**) and by file count (the size limit divided by 13 KB, at
least 256 files). Over a limit, the launcher deletes the least recently used logos.
It checks at start-up, after every 64 new logos, and at once when you lower the
limit.

### Removing a profile

**Remove** asks what to do with the game folder: **Remove, keep files** or
**Remove and delete files**. Deletion continues past files it cannot delete (on
Windows, files that a program has open) and reports how many remain.

## Settings (`launcher.json`)

`launcher.json` in the data folder holds the launcher settings. Most of them are
also in the settings window. Edit the file only while the launcher is closed:
the launcher writes the whole file each time it saves settings.

A key that is missing, or has a value of the wrong JSON type, gets its default.

| Key | Type | Default | Values and limits | Settings window |
|---|---|---|---|---|
| `language` | string | `""` | A language code (see [Languages](#languages)). Empty follows the operating system | Interface |
| `splashMinimumMillis` | number | `3000` | Minimum time the start-up window stays open, in ms. Clamped to 0-15000. `0` removes the minimum. A click or a key closes the window sooner | Interface (in whole seconds, 0-15) |
| `keepOpenWhilePlaying` | boolean | `true` | Keep the launcher window open while the game runs | Game |
| `minimiseToTrayWhilePlaying` | boolean | `true` | Hide the window to the notification area while the game runs | Game |
| `showAllVersions` | boolean | `false` | Show snapshots and old versions in the version list | Game |
| `verifyEveryLaunch` | boolean | `false` | Read and hash every file before each launch, and unpack native libraries again. When `false`, a file is read again only if its size, time stamp or expected hash changed | Game |
| `javaDownloadPolicy` | string | `"ask"` | `ask`, `always` or `never`. Also accepts `auto`, `true`, `yes` (as `always`) and `off`, `false`, `no` (as `never`). Other values mean `ask` | Java |
| `downloadConcurrency` | number | `12` | Number of files downloaded at the same time. Clamped to 1-32. Used from the next start | Downloads |
| `checkForUpdates` | boolean | `true` | Look for a newer launcher at start-up | Downloads |
| `updateChannel` | string | `"release"` | `release` or `nightly`. Other values mean `release` | Downloads |
| `proxy` | object | `{"mode": "system", "host": "", "port": 8080, "user": ""}` | `mode`: `system`, `direct` or `manual` (other values mean `system`). `host`, `port` and `user` apply to `manual`. HTTP proxies only. The password is kept in the credential store, not in this file | Downloads |
| `warnAboutDependents` | boolean | `true` | Ask before you switch off or delete a mod that other installed mods need | Mods |
| `modIconCacheMegabytes` | number | `32` | Size limit of the mod logo cache in MB. Clamped to 8-1024 | Mods |
| `curseForgeApiKey` | string | `""` | CurseForge Core API key. Empty uses the next key source (see [CurseForge](#curseforge)) | Mods |
| `microsoftSignInMethod` | string | `"browser"` | `browser` (authorization code with PKCE) or `deviceCode`. Any value other than `deviceCode` means `browser` | Accounts |
| `secureLaunchHandshake` | boolean | `true` | Give the session token to the game through standard input, not on the command line. Turn it off only if a mod loader does not start with it | Accounts |
| `useFileCredentialStore` | boolean | `false` | Keep credentials in the launcher's encrypted file, not in the operating system store. This is less secure: the file's key is next to it. Used from the next start | Accounts |
| `microsoftClientId` | string | built-in ID | Azure application ID for Microsoft sign-in. An empty string turns Microsoft sign-in off | - |
| `customGroupColors` | array of strings | `[]` | Colours mixed in the group editor, newest first. `#rrggbb` only, stored in lower case, 16 at most | - |

For the effect of these settings, see:
[java-and-loaders.md](java-and-loaders.md) (`javaDownloadPolicy`),
[updates.md](updates.md) (`checkForUpdates`, `updateChannel`),
[mods.md](mods.md) (`warnAboutDependents`),
[interface.md](interface.md) (start-up window and window behaviour while the game runs),
[../SECURITY.md](../SECURITY.md) (`secureLaunchHandshake`, `useFileCredentialStore`, `verifyEveryLaunch`).

### System properties and environment variables

| Name | Kind | Effect |
|---|---|---|
| `hexadron.root` | system property | Use this folder as the data folder |
| `hexadron.curseforge.apikey` | system property | Replaces the CurseForge key built into the jar |
| `hexadron.nosplash` | system property | `true` skips the start-up window |
| `CURSEFORGE_API_KEY` | environment | CurseForge key when `curseForgeApiKey` is empty. At build time, the key written into the jar |
| `HEXADRON_DEBUG` | environment | Any value makes the command-line mode print stack traces |

## Microsoft sign-in

The launcher signs in with the OAuth 2.0 authorization code grant and PKCE, in the
user's own browser. Microsoft redirects to a one-time listener on
`http://127.0.0.1:<port>/` (a free port), which waits 300 seconds. The launcher
never sees the password and uses no embedded web view. It asks only for
`XboxLive.signin offline_access`.

The device code grant (**Settings > Accounts > Microsoft sign-in > With a code**)
is for a computer with no usable browser.

### Using your own Azure application

The launcher has a built-in application ID. Mojang requires each launcher to use
its own Azure application, and Mojang must approve it. To use your own, for
example in a fork:

1. Register an application in the Azure portal, in the **consumers** tenant.
   Add the platform **Mobile and desktop applications** with the redirect URI
   `http://127.0.0.1`. No client secret is necessary. Turn on
   **Allow public client flows** only if you also want the device code method.
2. Apply to Mojang for approval of the application ID at
   <https://aka.ms/mce-reviewappid>.
3. Set the application ID as `microsoftClientId` in `launcher.json`.

If Mojang has not approved the application, `login_with_xbox` returns HTTP 403.
The launcher then shows "Minecraft services rejected this application (HTTP 403)"
with the link above.

With `microsoftClientId` set to an empty string, **Sign in with Microsoft** shows
"Microsoft sign-in is not configured".

To revoke the launcher's access to a Microsoft account, use
<https://account.live.com/consent/Manage>. Removing the account in the launcher
deletes the local credentials only.

### Credential storage

Credentials are never written to `accounts.json`. The Microsoft refresh token
and the Minecraft access token go to the first store that works:

| Platform | Store |
|---|---|
| Windows | DPAPI (encrypted files in `secrets/`) |
| macOS | Keychain (through the `security` command) |
| Linux | Secret Service (through the `secret-tool` command) |
| Any, as fallback | Encrypted file `secrets/secrets.json`, key in `secrets/secrets.key` |

`useFileCredentialStore` forces the encrypted file. The launcher probes the
system stores only when it first needs a credential. The proxy password uses the
same store. See
[../SECURITY.md](../SECURITY.md) for what this protects against and what it does
not.

## CurseForge

CurseForge requires an API key for every API request. Its file hosts
(`*.forgecdn.net`) also refuse downloads without a key, with HTTP 401. The
launcher sends the key only to `api.curseforge.com` and `forgecdn.net` hosts.
Modrinth needs no key.

### Use the right kind of key

CurseForge has two key pages. Only one of them issues a key that works here.

| Page | Issues | Use |
|---|---|---|
| `console.curseforge.com` | **Core API key**: starts with `$2a$10$`, about 60 characters, sent as `x-api-key` | Reading the catalogue. This is the key the launcher needs |
| `legacy.curseforge.com/account/api-tokens` | **Upload API token**: 32 hex characters (or a UUID), sent as `X-Api-Token` | Uploading files to projects you own. `api.curseforge.com` refuses it with HTTP 403 on every request |

`CurseForgeProvider.shapeOf` looks at the string and classifies it as `CORE`,
`UPLOAD_TOKEN` or `UNKNOWN`. It sends nothing and it never refuses a key, because
CurseForge can change either format. The result is used in three places:

- **Settings > Mods > CurseForge API key** shows a warning under the field when
  the key looks like an upload token.
- When you save a key in the content window, the launcher warns about an upload
  token. For other keys it sends one request to `/v1/games/432` and shows the
  result at once.
- Every HTTP 401 or 403 from the API goes through `CurseForgeProvider.get`. The
  error message says which key page to use, for a search and for an install.

### Where the key comes from

The first non-empty value wins:

1. `curseForgeApiKey` in `launcher.json`.
2. The `CURSEFORGE_API_KEY` environment variable.
3. The key built into the jar: the `hexadron.curseforge.apikey` system property
   if it is set, otherwise the `Hexadron-CurseForge-Api-Key` attribute in the jar
   manifest.

With no key, `CurseForgeProvider.isAvailable()` is false. The mod browser shows
"CurseForge is off - this build has no API key, so only Modrinth is being
searched." with an **Add a key** button, and searches use Modrinth only.

### The key is not in the repository

CurseForge issues one key per application, and its terms do not allow you to
share it. The `jar` task in `launcher/build.gradle` writes `CURSEFORGE_API_KEY`
from the environment into the manifest attribute. On CI the value comes from a
repository secret. Forks and pull requests from forks do not get the secret, so
their builds have an empty attribute and run without CurseForge.

The key in the manifest is not secret from the person who runs the launcher. The
arrangement only keeps it out of version control and out of forks.

Builds run from class folders (the `:launcher:cli` and `:launcher:run` Gradle
tasks) have no manifest. Use `CURSEFORGE_API_KEY` or `curseForgeApiKey` there.

### Mods that do not allow third-party downloads

For these mods the API returns a file with no download URL. The launcher then
asks Modrinth for a file with the same SHA-1 and downloads that if it exists.
If not, it skips the mod and names it as a manual download.

## Languages

| Code | Language | Code | Language |
|---|---|---|---|
| `en` | English | `pt` | Português (Brasil) |
| `uk` | Українська | `tr` | Türkçe |
| `ru` | Русский | `id` | Bahasa Indonesia |
| `pl` | Polski | `vi` | Tiếng Việt |
| `de` | Deutsch | `hi` | हिन्दी |
| `es` | Español | `zh` | 简体中文 |
| `fr` | Français | `ja` | 日本語 |
| `it` | Italiano | `ko` | 한국어 |

The language is set in **Settings > Interface > Language**. The launcher ignores
case and region: `pt-PT` and `pt-BR` both give `pt`, `zh-TW` and `zh-CN` both
give `zh`. With `language` empty or unknown, the launcher uses the operating
system language if there is a file for it, otherwise English. A change applies
to the open windows when you save the settings.

Strings are in `launcher/src/main/resources/lang/<code>.properties`, read as
UTF-8. A key missing from a file falls back to English for that key.
`en.properties` is the reference. `SelfCheck` (part of `./gradlew check`) fails
when another file:

- is missing a key or has an extra key;
- has a blank value;
- uses different `{n}` placeholders from the English string;
- loses a placeholder in `MessageFormat`, for example after a single apostrophe.

The Spanish, French, Italian, Portuguese, Turkish, Indonesian, Vietnamese, Hindi,
Chinese, Japanese and Korean files are machine translations that native speakers
have not yet reviewed. Corrections are welcome.

To add a language:

1. Copy `en.properties` to `<code>.properties` and translate it.
2. Add one entry to `com.hexadron.launcher.i18n.Language`.

## Command-line mode

The launcher has a command-line mode that needs no display. It uses the same data
folder and settings as the window.

./gradlew :launcher:cli --args="versions"
./gradlew :launcher:cli --args="create Hexadron 26.2 fabric"
./gradlew :launcher:cli --args="install Hexadron"
./gradlew :launcher:cli --args="mods Hexadron"
./gradlew :launcher:cli --args="play Hexadron"

`com.hexadron.launcher.Main` starts the command-line mode when it gets any
argument, so a packaged build accepts the same commands. The Windows executable
is built without a console, so use the Gradle task there.

| Command | Does |
|---|---|
| `versions [--all]` | Latest release and snapshot, then the 40 newest releases, or all versions with `--all` |
| `loaders <loader> <mcVersion>` | Up to 30 builds of a loader for a Minecraft version |
| `java` | Detected Java runtimes and the `javaDownloadPolicy` value |
| `java <major>` | Downloads an Eclipse Temurin runtime of that major version, if none is installed |
| `profiles` | Profiles, most recently used first |
| `create <name> <mcVersion> [loader]` | Creates a profile. The loader defaults to `vanilla` |
| `install <profile>` | Downloads everything the profile needs |
| `check <profile> [mcVersion]` | Lists mods that would not load on the profile's version, or on `mcVersion`. Exit code 1 if any |
| `move <profile> <mcVersion>` | Changes the profile's version and updates, keeps or switches off its mods. Run `install` after it |
| `mods <profile> [pack.json]` | Installs a mod pack from a file, or the Hexadron Optimise set |
| `addjar <profile> <jar>` | Copies a local mod jar into the profile |
| `search <query> <mcVersion> <loader>` | Up to 10 results from each of Modrinth and CurseForge. A platform with no key is skipped |
| `accounts` | Saved accounts with their profile identifiers |
| `play <profile>` | Installs if needed, then launches with the active authenticated account |

Loaders: `vanilla`, `fabric`, `quilt`, `forge`, `neoforge`.
`<profile>` is a profile ID or a profile name.

Exit codes: `0` success, `1` error (or failed check, or the game exited with a
non-zero code), `2` unknown command or no arguments, `130` interrupted.
Microsoft sign-in requires a display and is performed via the graphical window.