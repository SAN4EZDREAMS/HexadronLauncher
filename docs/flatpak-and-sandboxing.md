# Flatpak and sandboxing

This page covers the Linux Flatpak build and the per-profile wrapper command, which can run the game in a sandbox.

## Flatpak

`HexadronLauncher-linux.flatpak` contains the same application image as `HexadronLauncher-linux.tar.gz`, on the GNOME 50 runtime (`org.gnome.Platform`, for GTK 3). The application id is `io.github.san4ezdreams.HexadronLauncher`.

```
flatpak install --user HexadronLauncher-linux.flatpak
flatpak run io.github.san4ezdreams.HexadronLauncher
```

You can also open the file in GNOME Software or KDE Discover. The first install downloads the runtime from Flathub.

### Permissions

From `finish-args` in `launcher/packaging/flatpak/io.github.san4ezdreams.HexadronLauncher.yml`:

| Permission | Used for |
|---|---|
| `--share=network` | Downloads, Modrinth, CurseForge, Microsoft sign-in, multiplayer |
| `--share=ipc`, `--socket=x11` | JavaFX and LWJGL draw through X11 (XWayland on Wayland) |
| `--socket=pulseaudio` | Sound, and the microphone for voice-chat mods |
| `--device=all` | The GPU, and `/dev/input` for controller mods |
| `--filesystem=xdg-download` | Import from and export to Downloads |
| `xdg-run/app/com.discordapp.Discord:create`, `xdg-run/discord-ipc-0` | Discord Rich Presence mods (Flatpak or native Discord) |
| `/sys/kernel/mm/hugepages:ro`, `/sys/kernel/mm/transparent_hugepage:ro` | Lets the JVM detect large pages |

The launcher, the game and its mods share one sandbox. It sees the launcher's data folder and Downloads, and no other part of your home folder.

### Differences from the tar.gz build

- **Data folder:** `~/.var/app/io.github.san4ezdreams.HexadronLauncher/data/hexadronlauncher` (the launcher uses `$XDG_DATA_HOME/hexadronlauncher`, and Flatpak sets `XDG_DATA_HOME`). To move a native install, close the launcher and copy `~/.local/share/hexadronlauncher` there. `flatpak uninstall --delete-data` removes it. See [configuration.md](configuration.md).
- **Updates:** `/app` is read-only, so the launcher does not replace itself. The update window has a **Download .flatpak** button that opens the release's `.flatpak` (or the release page) in your browser. Install the new file over the old one; your data stays. See [updates.md](updates.md).
- **Other folders:** `flatpak override --user --filesystem=~/Games io.github.san4ezdreams.HexadronLauncher`, or Flatseal.
- **Java:** host Java is not visible. The launcher uses its bundled Java 25 or downloads the Java a version needs. See [java-and-loaders.md](java-and-loaders.md).
- **Wrapper command:** runs inside the sandbox, so host programs such as `bwrap`, `gamemoderun` or `mangohud` are not available.
- **Credentials:** the launcher tries the Secret Service through `secret-tool`. If that fails, it uses its encrypted file store in `secrets/`. The manifest gives no access to the host keyring, so after a move you may have to sign in to Microsoft accounts again.

### Build locally

Needs `flatpak` and `flatpak-builder`. The first build downloads the GNOME runtime and SDK (about 1 GB).

```
./gradlew --configure-on-demand :launcher:appImage
tar czf HexadronLauncher-linux.tar.gz -C launcher/build/jpackage .
launcher/packaging/flatpak/build-flatpak.sh HexadronLauncher-linux.tar.gz <version> [out-dir]
```

The script writes `<version>` and the date into the AppStream metadata, runs `desktop-file-validate` and `appstreamcli validate` when installed, and writes `HexadronLauncher-linux.flatpak` to `out-dir` (default: current folder).

CI runs the same script in a `flatpak` job:

- `build-launcher.yml`: artifact `hexadron-launcher-flatpak`, version `<base>-ci.<run>`, on pushes and pull requests that change launcher or Gradle files.
- `release-launcher.yml`: artifact `client-flatpak`, attached to each release, nightly builds included. `publish` needs only the three archives, so a failed Flatpak build does not stop the release.

### Flathub manifest

`launcher/packaging/flatpak/flathub/io.github.san4ezdreams.HexadronLauncher.yml` is a template for a Flathub submission. It downloads `HexadronLauncher-linux.tar.gz` from release `v<version>` by URL and SHA-256. Before submission, replace `@VERSION@` and `@SHA256@`, and put the `.desktop` file, start script, three icons and filled-in metainfo next to it. Its `x-checker-data` follows the latest non-prerelease GitHub release.

## Sandboxing

A sandbox (`bwrap`, `firejail`, Flatpak) protects your other files from a malicious mod. It does not protect the Minecraft account: the session token is in the game's own JVM memory, and mods run in that JVM. Mod malware such as fractureiser (2023) stole browser cookies, Discord tokens and crypto wallets from files. Java cannot block this in-process, because JEP 486 disabled the `SecurityManager` in Java 24.

Namespaces add no per-frame cost. The risk is breakage: a sandbox without `/dev` can leave the game without a GPU or gamepads. That is why the Flatpak keeps `--device=all` and X11.

### The wrapper command

The profile dialog has a **Wrapper command** field below **Extra JVM arguments**. The launch becomes:

```
<wrapper> <java> <jvm args> <main class> <game args>
```

Use it for `gamemoderun`, `prime-run`, `mangohud`, `strace -f -o trace.log` or a sandbox.

- Empty by default; empty leaves the command unchanged.
- One line. Split on spaces; double quotes group an argument. There is no shell, so `~` and `$HOME` are not expanded.
- Not included in `.hexbuild` exports.
- **Keep stdin open.** With **Hand the session token over standard input** on (the default), Microsoft accounts get the token over stdin. A wrapper that closes stdin breaks their launch. `bwrap` and `firejail` pass stdin through.
- **Keep the network.** `--unshare-net` blocks Mojang's session server and all servers.

If the launch fails with exit code 92 and the profile has a wrapper, the message names the wrapper and tells you to clear the field and try again.

### Linux example

Put a long sandbox command in a script and enter its absolute path in the field. The launcher passes the Java command as arguments; `exec` keeps stdin. Not tested by the project.

```sh
#!/bin/sh
D="${XDG_DATA_HOME:-$HOME/.local/share}/hexadronlauncher"
I="$D/instances/<instance-folder>"
exec bwrap --die-with-parent --unshare-pid --new-session \
  --ro-bind /usr /usr --ro-bind /etc /etc \
  --symlink usr/lib /lib --symlink usr/lib64 /lib64 --symlink usr/bin /bin \
  --proc /proc --dev-bind /dev /dev --ro-bind /sys /sys --tmpfs /tmp \
  --bind "$I" "$I" \
  --ro-bind "$D/assets" "$D/assets" \
  --ro-bind "$D/libraries" "$D/libraries" \
  --ro-bind "$D/versions" "$D/versions" \
  --ro-bind-try "$D/natives" "$D/natives" \
  --ro-bind-try "$D/java" "$D/java" \
  --ro-bind-try "$D/wrapper" "$D/wrapper" \
  "$@"
```

`wrapper/` holds the jar that receives the session token. Bind the folder of any Java outside `/usr`.

### Windows and macOS

On Windows, [Sandboxie-Plus](https://sandboxie-plus.com) works as a wrapper:

```
"C:\Program Files\Sandboxie-Plus\Start.exe" /box:minecraft /wait
```

Without `/wait`, `Start.exe` exits at once and the launcher thinks the game has closed. Stdin pass-through is not tested; if it fails, Microsoft accounts fail with exit code 92.

On macOS there is no supported isolation wrapper (`sandbox-exec` is deprecated). Use the field for tools only.
