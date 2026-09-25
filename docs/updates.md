# Updates

How the launcher updates itself: settings, channels, the update window, delta (parts) updates, the VirusTotal label, and how the installed folder is replaced. For what is and is not verified about a download, see [SECURITY.md](../SECURITY.md).

## Settings

Settings, Downloads:

| Control | `launcher.json` key | Default | Effect |
|---|---|---|---|
| Look for launcher updates at start-up | `checkForUpdates` | `true` | One request during the start-up screen (step `updates`). Off: the step is skipped. |
| Update channel | `updateChannel` | `release` | `release` or `nightly`. Any other value is read as `release`. |
| Check for updates | - | - | Asks now on the selected channel. Shows "This is the newest version", the update window, or "Could not check: ...". |

At start-up a failed check (no connection, GitHub down, rate limit) shows nothing. Every check result goes to `logs/launcher.log` with its channel.

## Channels

Both channels read the releases of `SAN4EZDREAMS/HexadronLauncher` through the GitHub API, without authentication (GitHub allows 60 such requests per hour per IP address).

| Channel | Request | Offers |
|---|---|---|
| Release | `/repos/SAN4EZDREAMS/HexadronLauncher/releases/latest` | GitHub's latest release (not a draft, not a pre-release). A 404 means no offer. |
| Nightly | `/repos/SAN4EZDREAMS/HexadronLauncher/releases?per_page=20` | The highest version among the 20 newest releases, pre-releases included, drafts skipped. |

Nightly builds are pre-releases from the branch and are sometimes broken. After you move from Nightly to Release, you get no offer until a release is newer than your nightly build.

## Versions

The version comes from the release tag, not the title. It is compared as a version:

- A leading `v` and build metadata after `+` are ignored.
- Numbers are compared as numbers; a missing number is 0.
- A suffix after `-` makes a pre-release, which is older than the same numbers without it.
- Suffix parts that are numbers are compared as numbers.

So `0.9.10` > `0.9.9`, `1.0.0` > `1.0.0-nightly.7`, and `nightly.10` > `nightly.9`. If either version cannot be read, nothing is offered. The running version is `Implementation-Version` in the launcher jar ([building.md](building.md)).

## Release files

A release is offered only if it has the full build for this system under an exact name (case ignored). If the chosen release has none, nothing is offered.

| System | Name |
|---|---|
| Windows | `HexadronLauncher-windows.zip` |
| Linux | `HexadronLauncher-linux.tar.gz` (also `.tgz`, `.zip`) |
| macOS | `HexadronLauncher-macos.tar.gz` (also `.tgz`, `.zip`, and the older `mac` and `osx` names) |

The release workflow ([building.md](building.md)) also publishes a manifest and four parts per system (see [Delta updates](#delta-updates)). Part names use `win`, `lnx` and `darwin`. Older launchers pick "a name that contains `windows` and ends in `.zip`", so a part named with `windows` would break their updates. Do not rename these files.

## The update window

The window shows the old and new version, the download size, the release tag, the VirusTotal label (if any), and the release notes under "What changed" as plain text. The workflow writes the notes as the commits since the previous release.

| Button | Action |
|---|---|
| Release page | Opens the release in the browser. |
| Not now | Closes the window. |
| Update | Downloads and installs. The buttons become a progress bar and a status line. |

Closing the window during the download cancels it. After a failure the status line shows "The update failed: ..." and you can try again.

The Update button is disabled, with a reason, in these cases:

| Case | What to do |
|---|---|
| Not a packaged client (development run, or `java -jar` outside an application image) | Download from the release page. |
| No write permission to the installed folder or its parent (for example Program Files, `/opt`) | Move the client to a writable folder, or update by hand. |
| Flatpak | The button becomes "Download .flatpak" and opens `HexadronLauncher-linux.flatpak` (or the release page) in the browser. Install it with your software centre or `flatpak install`. Flathub installs update through Flathub. See [flatpak-and-sandboxing.md](flatpak-and-sandboxing.md). |

## Installing an update

When you click Update, the launcher:

1. Creates `.hexadron-update` next to the installed folder, on the same file system.
2. Gets the new build from parts (see [Delta updates](#delta-updates)) or as the full archive. For the full archive it checks the length against the length GitHub published (a short file is deleted), and the SHA-256 against the manifest if the manifest names this archive. It unpacks with `util/Archives.java`, which keeps symbolic links and the executable bit.
3. Checks that the result is an application image: the jar folder and bundled `java` are where jpackage puts them.
4. Starts `com.hexadron.launcher.update.Updater` with the new build's runtime and jar, writes the updater's process ID to `.hexadron-update/handoff`, and exits. Updater output goes to `.hexadron-update/update.log`.

The updater:

1. Waits up to 60 seconds for the launcher to exit.
2. Moves the installed folder to `<folder>.old-<time>` (up to 20 attempts, 250 ms apart).
3. Copies the new build into place, keeping links and permissions.
4. Deletes the downloaded files in `.hexadron-update`, except `update.log` and `handoff`.
5. Starts the new launcher (`/usr/bin/open -n <bundle>` on macOS).
6. Deletes the old folder. If something holds it (Explorer, an antivirus), the next start deletes it.

If step 2, 3 or 4 fails, the updater deletes the half-copied folder, moves the old one back and starts it, so you keep your version. If the old folder cannot be moved back, `update.log` names it; rename it to the original name by hand.

### Cleanup on the next start

The updater runs from `.hexadron-update`, so it cannot delete it. The start-up step `updateCleanup` removes `.hexadron-update` (including `update.log`) and every `<folder>.old-*` next to the installation. It runs even when the update check is off.

- It runs on a background thread.
- If `handoff` is under 10 minutes old and that process still runs, it waits up to 45 seconds for it.
- It deletes what it can and records what it cannot. It clears the read-only attribute and tries again, because jpackage marks some files read-only and Windows cannot delete them.
- If something is left, it tries four more times, with pauses of 2, 8, 30 and 120 seconds, then waits for the next start.
- Each result goes to `logs/launcher.log`.

## Delta updates

The Java runtime and JavaFX are most of an image and change rarely. The release workflow runs `com.hexadron.launcher.update.ManifestTool` on each image. It writes `HexadronLauncher-<windows|linux|macos>.manifest.json` (format `1`) with every file's path, part, size and SHA-256, link targets, the executable bit, and the full archive's name, size and SHA-256. It also writes one file list per part, which the workflow packs as `HexadronLauncher-parts-<win|lnx|darwin>-<part>.zip` (Windows, `7z`) or `.tar.gz` (Linux, macOS, `tar`).

| Part | Contents |
|---|---|
| `runtime` | The bundled Java runtime. |
| `libs` | Jars in the app folder that are not the launcher's own (JavaFX and other dependencies). |
| `app` | The launcher's jars (names starting with `launcher-` or `hexadron`) and non-jar files in the app folder. |
| `base` | Everything else: executable, icon, root files. |

The launcher:

1. Reads the manifest for this system. No manifest, an unreadable one, a wrong system or another format: full archive.
2. Reuses a part only if every file in it matches the installed folder by size and SHA-256 (links by target).
3. Takes the full archive if the parts to download are 75% or more of the unpacked build.
4. Downloads the missing parts, unpacks them into `.hexadron-update/assembled`, and copies the rest from the installed folder.
5. Checks every file against the manifest (presence, size, SHA-256, link target) and restores the executable bit where recorded.

Any failure is logged and the full archive is downloaded instead. The manifest is refused if a path is absolute or contains `..`, `\` or `:`. A nightly build normally downloads only `app` and `base`.

## VirusTotal

After it publishes a release, the release workflow calls the `virustotal` workflow. This needs the `VT_API_KEY` repository secret; without it there is no scan and the notes do not mention one.

- The notes are published with a `verdict=pending` block.
- `.github/scripts/virustotal_scan.py` downloads the release files (not `.json`, `.txt`, `.md`, `.sha256`), looks each up by SHA-256, uploads unknown files, and waits up to 45 minutes for results. Files it cannot check before the daily API limit are `unchecked`.
- It replaces the block between `<!-- virustotal:start ... -->` and `<!-- virustotal:end -->` at the end of the notes. The first comment carries `verdict`, `found`, `checked` and `total`.
- A file is `danger` with 3 or more "malicious" results (`VT_DANGER_THRESHOLD`), `warning` with any "malicious" or "suspicious", otherwise `clean`. The release gets the worst verdict.
- `danger` or `error` fails the job. The release stays published.

To scan an older release: Actions, virustotal, Run workflow, enter the tag.

The update window cuts the block out of the notes and shows one coloured label. Click it to open the release page.

| Verdict | Label |
|---|---|
| `clean` | VirusTotal: clean - N of M files checked, nothing found |
| `warning` | VirusTotal: N detections - probably a false alarm, the reports are on the release page |
| `danger` | VirusTotal: N detections - read the reports on the release page before you update |
| `pending` | VirusTotal: the check is still running |
| `unchecked`, `error` | VirusTotal: N of M files checked - the reports are on the release page |

No block, or an unknown verdict: no label. A `danger` label does not block the update.
