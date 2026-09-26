# Security

How to report a vulnerability, and how HexadronLauncher handles secrets and runs code it did not write. Each section says what a measure protects against and what it does not.

## Reporting a vulnerability

Report a suspected vulnerability privately through GitHub: <https://github.com/SAN4EZDREAMS/HexadronLauncher/security/advisories/new>. Do not open a public issue for it.

Include the launcher version (Settings, Downloads, "You are running ..."), your operating system and the steps to reproduce. Read `logs/launcher.log` before you attach it: the launcher removes known token formats (section 4), but it cannot know every secret.

## Supported versions

The project is in beta. The version in `launcher/build.gradle` is `0.9.8`.

| Build | Tag | Supported |
|---|---|---|
| Newest release | `v<version>`, for example `v0.9.8` | Yes |
| Newest nightly | `v<version>-nightly.<N>` | Yes |
| Older builds | - | No. Update to the newest build. |

The release workflow keeps only the five newest nightly builds. The launcher updates itself from these releases ([docs/updates.md](docs/updates.md)).

## Principles

1. **State the limit.** A desktop launcher cannot protect a user from code that already runs as that user. Where a measure does not help, this file says so.
2. **Do not depend on the user being careful.** Minecraft sessions are usually lost through files, logs and process arguments, not broken cryptography. The measures concentrate on those.

---

## 1. Microsoft sign-in

The default is the authorization code grant with PKCE, in the system browser, with a loopback redirect, as RFC 8252 (OAuth 2.0 for Native Apps) specifies.

| Property | Implementation |
|---|---|
| User agent | The user's own browser, never an embedded web view (RFC 8252, section 8.12). |
| Redirect | `http://127.0.0.1:<port>/` on the loopback interface. The IP literal, so a hosts-file entry cannot redirect it. The operating system chooses the port for each sign-in. |
| PKCE | `S256` only, 32-byte random verifier. |
| CSRF | 32-byte random `state`, compared in constant time before the code is accepted. A mismatch is discarded and reported. |
| Client secret | None (public client). |
| Scope | `XboxLive.signin offline_access` only. |
| Account picker | Always `prompt=select_account`. |
| Listener | Ends when a valid response arrives, the user cancels, or after 5 minutes. |
| Response page | Static, no query values in it. `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'`. |

**Device code** is a fallback for machines without a usable browser (Settings, Accounts, Microsoft sign-in, "With a code"; key `microsoftSignInMethod`). It is not the default, because the code the user types is not bound to the application that asked for it. This makes it a known phishing method.

**Transport.** Authentication requests use a separate client (`Http.authPostForm`, `authPostJson`, `authGetJson`, `authSend`) that refuses non-HTTPS URLs on every call, does not follow redirects, and does not retry (a repeated refresh grant can invalidate the account). All HTTP uses the JDK's default TLS. The code has no custom `SSLContext`, `TrustManager` or hostname verifier.

---

## 2. Credentials at rest

`accounts.json` has **no credentials**: only user name, UUID, XUID, token expiry and the selected account. It is safe to attach to a bug report.

The Microsoft refresh token and the Minecraft access token go to the operating system's credential store. So does the proxy password.

| Platform | Store | Key held by |
|---|---|---|
| Windows | DPAPI, `CurrentUser` scope, through `powershell.exe`, plus entropy in `secrets/dpapi.entropy` | Windows, from the user's logon |
| macOS | Keychain, through `security` | macOS, login password |
| Linux | Secret Service (GNOME Keyring, KWallet and others), through `secret-tool` | The desktop keyring |
| Fallback | AES-256-GCM in `secrets/secrets.json`, key in `secrets/secrets.key` | The launcher |

The store is chosen when a credential is first needed. The availability test differs:

- Windows: a full DPAPI protect and unprotect round trip.
- Linux: a real `secret-tool lookup`. No D-Bus or a locked wallet fails it.
- macOS: only a check that `security` exists. A locked keychain passes it.

If the store fails during an operation, that operation uses the fallback file and the launcher stops reporting the credential as protected by the operating system. To choose the fallback yourself: Settings, Accounts, "Keep credentials in the launcher own encrypted file" (`useFileCredentialStore`, off by default).

**The fallback is not a security boundary.** Its key is in the same folder as the data. It only keeps tokens out of plain text in screenshots and simple searches, and GCM makes a changed file fail to decrypt.

Credential files are owner-only: mode `600` where POSIX permissions exist, otherwise (Windows) an explicit owner-only ACL without inherited entries. They are written to a temporary file and moved into place atomically.

**Secrets never go to a helper process as an argument**, because other processes can read arguments. They go over standard input. The DPAPI script is passed with `-EncodedCommand` and contains no secret.

**Migration.** If `accounts.json` from an older version contains tokens, the launcher moves them to the credential store at start-up and rewrites the file without them.

---

## 3. The session token at launch

Minecraft takes its token as `--accessToken <token>`. Other processes can read process arguments (`ps`, `/proc/<pid>/cmdline`, `Get-CimInstance Win32_Process`), and the JVM copies them into `hs_err_pid*.log` when it crashes. So the launcher:

1. Puts the placeholder `%%HEXADRON_ACCESS_TOKEN%%` in the arguments.
2. Starts `com.hexadron.wrapper.GameLaunchWrapper` (one class, own jar, compiled for Java 8) on the game's class path.
3. Writes the real token to the child's standard input and closes the stream.
4. The wrapper replaces the placeholder in memory and calls the game's main class by reflection in the same JVM.

If the wrapper jar is missing, the token falls back to the command line and the log says so. Settings, Accounts, "Hand the session token over standard input" (`secureLaunchHandshake`, on by default) turns it off; do this only if a mod loader fails with it. A profile's wrapper command must pass standard input through, or the game exits with code 92.

---

## 4. Logs

Two layers remove secrets from output:

- **Registered secrets.** Microsoft, Xbox, XSTS, Minecraft tokens, the device code and the CurseForge key are registered with `util/Redactor.java` when received. Each exact value becomes `<redacted>`. Values under 12 characters are not registered.
- **Token shapes.** Unregistered tokens are found by shape: JWTs, `M.C5_...`/`M.R3_...`, `XBL3.0 x=...;...`, `token:...:`, OAuth codes and tokens in URLs and JSON, and values after `--accessToken`, `--session`, `"accessToken"` and `"clientToken"`.

Both apply where text leaves the launcher: `logs/launcher.log`, the log pane, console output, game output, helper error output and the printed launch command.

For Microsoft OAuth errors only `error` and `error_description` are read. For other endpoints, an HTTP error puts the response body in the exception message only after scrubbing and cutting it to 500 characters.

---

## 5. What the game process can reach

**The game receives the Minecraft access token.** It needs it to join servers. The token is not in the process table or crash log, but it is in the JVM's memory, and a mod can read it.

**The game never receives the Microsoft refresh token.** `LaunchCommandBuilder` puts one secret in the handshake: the access token. Only the launcher uses the refresh token.

| Stolen | Gives access to | Valid for |
|---|---|---|
| Minecraft access token | The Minecraft profile (play, change skin or cape) | Up to 24 hours |
| Microsoft refresh token | The Microsoft account | Until revoked |

A mod can take the first but not the second. This limits the damage; it does not prevent it.

---

## 6. Removing an account

Removing an account deletes the entry and its stored credentials. The launcher then says that this does **not** withdraw its access to the Microsoft account, and links to <https://account.live.com/consent/Manage>.

---

## 7. What these measures do not stop

- **A malicious mod, for the account.** A mod can read the token from the running JVM. A credential store does not help, and a sandbox does not either, because the token is inside the process. JEP 486 permanently disabled the `SecurityManager` in Java 24, so Java has no in-process alternative.

  A sandbox does protect the rest of the machine. Mod malware such as fractureiser (2023) stole browser data and other files outside the game. Each profile has a wrapper command, empty by default, that can run the game in `bwrap` or `firejail` ([docs/flatpak-and-sandboxing.md](docs/flatpak-and-sandboxing.md)). The token goes over standard input, which a wrapper passes through, so a sandbox does not put it back in the arguments.
- **An infostealer that runs as the user.** It can ask the operating system for the same secret. The store does stop simple file theft: searches for launcher files, synced folders, restored backups, other users of the same PC.
- **Sign-in on a phishing page.** The system browser shows Microsoft's address and certificate, and the user's password manager works. That is the most a launcher can do.

---

## 8. The CurseForge API key

The key identifies the application to CurseForge. It gives no access to a player's account.

- It is not in the repository. The release build reads `CURSEFORGE_API_KEY` from the environment (a repository secret on CI) into the jar manifest attribute `Hexadron-CurseForge-Api-Key`. Forks and local builds get an empty value and no CurseForge support ([docs/building.md](docs/building.md)).
- A key the user enters replaces the built-in one. It is kept in the credential store (DPAPI, Keychain, Secret Service or the encrypted file), not in `launcher.json`; a plain-text key from an older version is moved there on the next start.
- Both are registered with `Redactor`.
- `Http` sends the key only to `api.curseforge.com`, `forgecdn.net` and hosts ending in `.forgecdn.net`, and only over HTTPS. The match is on a dot boundary, so `evil-forgecdn.net` gets nothing. Requests that carry the key follow redirects themselves: the key is attached again only when the next host is one of those, and a redirect to plain http is refused.
- The game and the Forge installer processors are started without `CURSEFORGE_API_KEY` in their environment.

A key shipped in a client is not secret from its user. The build setup keeps it out of version control and forks, and lets the project replace it in one place.

---

## 9. Forge and NeoForge installer processors

Installing Forge or NeoForge runs the third-party programs in the installer's processor chain. The launcher limits this:

- Each step is a **separate JVM process**. It cannot read the launcher's memory, and its `System.exit` does not stop the launcher.
- Its working directory is a **scratch folder** under `cache/loaders/`, deleted afterwards.
- Its jar and class path are **maven artifacts named by the installer profile**. If one is missing, the step is refused.
- Each output is **checked against the SHA-1 in the profile**. A mismatched file that is a complete, readable archive is kept with a log note (a JVM with another compression library writes valid but different bytes). Any other mismatch deletes the file and stops the install.

Whoever controls the installer jar controls what runs. The launcher downloads it over HTTPS from `maven.minecraftforge.net` or `maven.neoforged.net`, the same jar a user would run by hand.

**Modpack files.** A `.mrpack` names each file by URL and SHA-1, and both come from the pack itself. So the SHA-1 proves only that the download is the file the pack meant. The launcher therefore downloads `.mrpack` files only over HTTPS from the hosts in Modrinth's format specification (`cdn.modrinth.com`, `github.com`, `raw.githubusercontent.com`, `gitlab.com`), and skips a file with no SHA-1. Paths that leave the instance folder are refused.

---

## 10. Updating the launcher

The launcher replaces itself with builds from the project's GitHub releases ([docs/updates.md](docs/updates.md)). One HTTPS request to `api.github.com` asks what the channel has. Nothing is downloaded until the user clicks Update. A second process then moves the old folder aside, copies the new build in, and puts the old folder back if a step fails.

| Checked | How |
|---|---|
| Transport | HTTPS, JDK default TLS. The download URL comes from the GitHub API. Redirects are followed, never from HTTPS to HTTP. |
| Right file | The full archive has an exact name for each system. No such file, no offer. |
| Complete download | Length compared with the length GitHub published. A short file is deleted. |
| Manifest author | Ed25519 signature of the manifest, checked against the public key built into the launcher (`update/UpdateSignature.java`). The manifest must be for the version and system on offer. With a key built in, an update without a valid signature is refused. See [docs/updates.md](docs/updates.md#update-signatures). |
| Full archive content | SHA-256 compared with the release manifest, when the manifest names this archive. With a key built in, the manifest must name it. |
| Delta update content | Every file of the assembled image, reused or downloaded, must match the manifest (size, SHA-256, link target). Any mismatch falls back to the full archive. |
| Paths | Manifest paths that leave the image are refused. The archive reader refuses entries that resolve outside the target folder, symbolic links that lead outside it (absolute targets, and relative targets that climb out, also through linked folders), and writes through a link to outside. |
| Application image | Runtime and jar folder must be where jpackage puts them, or nothing is replaced. |
| Undo | The old folder is moved aside, not deleted, until the new build is in place. |

Crash rules ([docs/crashes.md](docs/crashes.md#rule-updates)) come from the same releases, once a day and only when the update check is on. A downloaded rule file is used only with a valid Ed25519 signature by the update key and a higher version than the built-in file; the signature is checked again each time the file is read. A rule can only choose among fixed fix kinds (switch off a mod in the profile's mods folder, choose Java, change the memory limit, check the game files). It cannot run a command or open a link.

**Not checked:**

- **No code signing of the executables.** The update manifest is signed (see above), but the executables are not signed by Microsoft or Apple. The manifest signature protects against a replaced release only when `PUBLIC_KEYS` has a key and the private key is kept out of the repository. While `PUBLIC_KEYS` is empty, anyone who can publish a release in `SAN4EZDREAMS/HexadronLauncher` can publish a build the launcher will install.
- **No SmartScreen or Gatekeeper approval.** The clients are unsigned archives. For a second opinion, use the release page, the VirusTotal result and the build log.
- **VirusTotal does not block anything.** The result is written into the release notes after publication and shown in the update window. A `danger` result does not block the update or remove the release.
- **Nightly builds are not reviewed.** They are published from the branch. Nightly is not the default; you must select it.

**No elevation.** The update writes only to the installed folder, the `<folder>.old-<time>` copy next to it, and `.hexadron-update`. If the installation is not writable, the launcher says so and does not ask for a password. A Flatpak install does not replace itself; Flatpak updates it.

To switch the check off: Settings, Downloads, "Look for launcher updates at start-up". The launcher then makes no update request at start-up.

---

## 11. Self-check

`./gradlew :launcher:selfCheck` runs without network or display. It covers, among other things: PKCE against the RFC 7636 test vector; the authorization request parameters (`S256`, `state`, `response_type=code`, loopback IP redirect, no client secret, no verifier, no `openid` or `email` scope); refusal of a wrong or missing `state`; that account metadata cannot carry a token; log redaction of registered and unregistered tokens; where the CurseForge key is sent; which releases count as newer; parsing of the VirusTotal block; delta update planning and assembly; and refusal of unsafe manifest paths.