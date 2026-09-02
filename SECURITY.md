# Security

This document describes how HexadronLauncher handles secrets and how it runs
code it did not write: Microsoft account credentials in sections 1 to 6, the
CurseForge API key in section 7, the Forge installer's processors in section 8,
what the self-check covers in section 9, and the launcher replacing itself in
section 10. For each one it says what the measure does protect against and what
it does not. It is written to be read by a reviewer as well as by a user.

Two rules govern everything below.

1. **State the limit.** A desktop launcher cannot defend a user against code
   already running as that user. Where a measure does not help, this document
   says so instead of implying otherwise.
2. **Do not rely on the user being careful.** Every documented loss of a
   Minecraft session in recent years came from a file or a log, not from broken
   cryptography. The defences are therefore concentrated on files, logs and
   process arguments.

---

## 1. The sign-in flow

**Authorization code grant with PKCE, in the system browser, over a loopback
redirect.** This is what RFC 8252 ("OAuth 2.0 for Native Apps") prescribes for a
desktop application, and RFC 9700 confirms for all clients.

| Property | Implementation |
|---|---|
| User agent | The user's own browser. Never an embedded web view - RFC 8252 §8.12 forbids it, and a window the launcher drew is a window the launcher could read keystrokes from |
| Redirect | `http://127.0.0.1:<ephemeral port>/`, bound to the loopback interface only. The IP literal rather than `localhost`, so a hosts-file entry or a renamed interface cannot redirect it |
| Port | Chosen by the kernel per sign-in. A fixed port would collide between two launchers or two users, and whoever already held it would receive the code |
| PKCE | `S256` only. RFC 8252 §8.1 requires PKCE for public native clients. Verified in `SelfCheck` against RFC 7636's own test vector |
| CSRF | A 256-bit `state`, compared in constant time before the code is accepted. A response that does not match is discarded and reported |
| Client secret | None. The application is registered as a public client; a secret shipped in a jar is not a secret |
| Scope | `XboxLive.signin offline_access` and nothing else. `openid`, `profile` and `email` would all be granted and none is needed to start Minecraft |
| Account picker | `prompt=select_account` always. Silent reuse of whatever account the browser is signed into is how a shared machine acquires someone else's account |
| Listener lifetime | One request. The server stops as soon as a valid response arrives, or after five minutes |
| Response page | Static, no query parameters echoed, `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, restrictive CSP |

**Device code is a fallback, not the default.** It is kept for machines with no
usable browser and for signing in from a phone. It was demoted deliberately: the
device code grant was designed for televisions and printers, it creates no
binding between the code the user types and the application that produced it,
and that is precisely the mechanism the "Gas Auth" phishing campaign used against
Minecraft players - malicious applications spoofing launcher names, including
MultiMC's, to harvest Xbox Live consent. That campaign is why Mojang introduced
manual review of new Azure applications in June 2023. Microsoft's own security
guidance now treats device code as a phishing vector.

### Transport

All authentication requests go through a dedicated HTTP client that

- **refuses any URL that is not HTTPS** - checked per call, not assumed from a
  constant;
- **does not follow redirects.** A redirect on the authentication path would
  forward an `Authorization` header or a refresh-token form body to a host the
  launcher did not choose;
- **is not retried.** Replaying a token exchange is not idempotent, and retrying
  a refresh grant against a server that rotates refresh tokens can invalidate
  the account;
- **uses the JDK's default TLS.** The launcher installs no custom `SSLContext`,
  no custom `TrustManager` and no hostname-verifier override anywhere in the
  codebase. The commonest way a desktop client ends up trusting a proxy's
  certificate is a developer disabling one of those while debugging and shipping
  it.

---

## 2. Credentials at rest

`accounts.json` contains **no credentials**: only the username, UUID, XUID, token
expiry and which account is selected. It is safe to copy, sync or attach to a bug
report.

The Microsoft refresh token and the Minecraft access token go to the operating
system's credential store:

| Platform | Store | Key held by |
|---|---|---|
| Windows | DPAPI, `CurrentUser` scope, with per-installation entropy | Windows, derived from the user's logon credentials |
| macOS | Keychain, via the `security` tool | macOS, unlocked with the login password |
| Linux | Secret Service (GNOME Keyring, KWallet, …) via `secret-tool` | The desktop keyring |
| Fallback | AES-256-GCM file, key file beside it | The launcher - see below |

Availability is established by a real round trip through the backend, not by
reading `os.name`: a Linux session without D-Bus and a Mac with a locked keychain
both look available and are not. If the chosen store fails mid-session, the
launcher falls back for that operation and stops claiming a protection that is no
longer in force.

**About the fallback.** Its key sits in the same folder as its ciphertext.
Anyone who can read one can read the other, and against them the encryption buys
nothing. It is not described as a security boundary. What it honestly buys: the
token no longer appears in plaintext in a screen share, a support screenshot, a
synced-folder preview or a naive grep-for-tokens sweep, and GCM makes tampering
fail loudly rather than silently substituting a token.

Every credential-bearing file is written owner-only, **including on Windows**,
where an explicit non-inherited ACL replaces the inherited one. The previous
implementation called `setPosixFilePermissions` and silently did nothing on the
platform most players use. Files are written to a restricted temporary file
first and moved into place atomically, so there is no window in which a
half-written or unprotected file exists.

**Secrets are never passed to a helper process as an argument.** Process
arguments are world-readable on all three platforms, so `security
add-generic-password -w <password>` would hand the credential to every process on
the machine. All helper input goes over stdin.

### Migration

A file written by an earlier version still has tokens in it. On first load they
are moved into the credential store and `accounts.json` is rewritten without
them, on start-up rather than lazily, so a user who upgrades and never signs in
again still gets the file cleaned.

---

## 3. The session token at launch

Minecraft takes its session token as `--accessToken <token>`. Process arguments
are readable by every process on the machine (`ps`, `/proc/<pid>/cmdline`,
`Get-CimInstance Win32_Process`), and the JVM copies the full argument list into
`hs_err_pid*.log` when it crashes - the file players routinely upload to support
channels, and which the common log-paste sites do not redact.

HexadronLauncher does not put the token on the command line. Instead:

1. The launcher substitutes a placeholder into the argument list.
2. It starts `com.hexadron.wrapper.GameLaunchWrapper` - one class, in its own
   jar, on the game's classpath.
3. It writes the real value to the child's **standard input**, a pipe only the
   two processes share, then closes the stream so nothing inside the game
   inherits a channel back to the launcher.
4. The wrapper substitutes the value in memory and invokes the game's real main
   method by reflection, in the same JVM. Minecraft sees exactly the arguments
   it expects.

Prism Launcher and MultiMC use the same technique and are the only other
launchers surveyed that keep the token out of the process table.

The wrapper is compiled for Java 8, because Minecraft versions up to 1.16 run on
Java 8. It is skipped for offline accounts, whose "token" is the literal `0`,
and the launcher falls back to the ordinary command line - with a warning in the
log - rather than refusing to start if the wrapper jar is missing.

---

## 4. Logs

Two independent layers, because either alone fails:

- **Registered secrets.** Every token is registered the moment it is created and
  replaced by exact match wherever it appears.
- **Shape patterns.** A token from a response the launcher did not expect was
  never registered, so exact matching cannot catch it. JWTs, `M.C5_…`/`M.R3_…`
  Microsoft tokens, `XBL3.0 x=…;…` identity headers, legacy `token:…:uuid`
  session arguments and OAuth codes in URLs are matched by shape.

Redaction is applied at the sinks - the log pane, the console `Progress`, the
game's own output stream - and not only at call sites, because a redaction that
must be remembered at each call site will eventually be forgotten at one of them.

**No response body is ever concatenated into an exception message.** The Xbox and
Minecraft endpoints echo tokens inside both error and success payloads; only
`error` and `error_description` are read. This is the class of bug that produced
CVE-2025-54120 in another launcher (credentials written to a debug log,
CVSS 9.3).

---

## 4a. What the game process can and cannot reach

This is the part that decides how bad a malicious mod actually is, so it is
stated as two separate facts.

**The game receives the Minecraft access token.** It has to: the game presents
it to Mojang's session server to join anything. It reaches the game over
standard input, not in `argv`, so it is not in the process table and not in
`hs_err_pid*.log`, but it is in the JVM's heap the moment the game reads it. A
mod can read it. Nothing in this launcher or any other changes that.

**The game never receives the Microsoft refresh token.** It is not passed as an
argument, it is not part of the launch handshake, and it never leaves the
credential store for any purpose other than a token refresh performed by the
launcher itself against Microsoft. `LaunchCommandBuilder` puts exactly one
secret into the handshake map, and it is the access token.

The gap between those two is the whole point of the credential split:

| Stolen | Reaches | Lasts |
|---|---|---|
| Minecraft access token | the Minecraft profile - play as the account, change skin or cape | up to 24 hours, and only until the token is refreshed |
| Microsoft refresh token | the Microsoft account - mail, other services, long-lived re-auth | until revoked |

A mod can take the first. It cannot take the second, because the second is
never in the room. That is a bound on the damage rather than a prevention of it,
and a bound is what is actually available here.


---

## 5. Sign-out and revocation

Removing an account deletes both the list entry and the stored credentials. The
launcher then says, explicitly, that this does **not** withdraw its access to the
Microsoft account, and points at <https://account.live.com/consent/Manage>.
After a suspected compromise those are very different actions, and treating
"remove" as if it meant "revoke" would be the more dangerous of the two to get
wrong.

---

## 6. What none of this stops

- **A malicious mod, as far as the account goes.** The game is handed a live
  token at launch by necessity. A mod can read it out of the running JVM - this
  is how the real-world Minecraft token stealers work, not by reading
  `accounts.json`. A credential store does not help here, and claiming
  otherwise would be dishonest.

  Nor does a sandbox, and an earlier version of this file said it did. A
  sandbox is a kernel-enforced boundary around a process; the token is inside
  the process, in the JVM's own heap, and a mod reading it is reading its own
  memory. No boundary is crossed, so there is nothing to arbitrate. Java's
  in-process answer is also gone for good: `SecurityManager` was removed
  permanently by JEP 486 in Java 24.

  What a sandbox does stop is the part the real incidents actually used -
  fractureiser (2023) took browser cookies, Discord tokens and cryptocurrency
  wallets; "Windows Borderless" and the Stargazers campaigns did the same. None
  of them touched the Minecraft token. All of them read files outside the game.
  So the wrapper command exists (README, "Sandboxing, and what it is actually
  for"), off by default, and it defends the machine from the mod rather than the
  account from the mod. The launch path is built so a sandbox can work at all:
  the token travels over standard input, which a wrapper passes through, and not
  in argv, which a namespaced process would still expose in its own `/proc`.
- **An infostealer already running as the user.** It can ask the same operating
  system for the same secret. What the credential store does stop is the file
  grab: a stealer sweeping for known launcher JSON files, a synced folder, a
  backup restored under another account, a second user on a family PC.
- **A user who signs in to a phishing page.** The system browser makes this
  visible - Microsoft's address bar, Microsoft's certificate, the user's own
  password manager - which is the strongest thing a launcher can do about it.

---

## 7. The CurseForge API key

This one is not a user credential, and it is written down here so that nobody
mistakes it for one. It identifies the *application* to CurseForge. Losing it
costs the project its API access; it gives nobody access to a player's account.

The key is never in this repository. The release build reads
`CURSEFORGE_API_KEY` from the environment - on CI, from a repository secret -
and writes it into the launcher jar's manifest, where `BuildConfig` reads it
back. GitHub does not hand repository secrets to builds of forks or to pull
requests from them, so every such build gets an empty attribute and simply has
no CurseForge in it. A user's own key, pasted into the settings, always wins
over the built-in one.

The key is registered with `Redactor` the moment it is read, so it cannot appear
in a log line, an error body or a pasted stack trace.

`Http` attaches it by host, and only to `api.curseforge.com` and the hosts that
end in `.forgecdn.net`. Host matching is on a dot boundary, so a look-alike
domain such as `evil-forgecdn.net` receives nothing. Both content hosts are
covered: sending the key to only one of them is a real bug in at least one other
launcher, and it surfaces as files failing to download from what looks like a
dead mirror.

**What this does not claim.** A manifest attribute is not a secret from the
person running the launcher, and no key shipped to a client ever can be.
Obfuscating it would only hide that fact from us. What the arrangement achieves
is that the key is out of version control, out of every fork, and replaceable in
one place.

---

## 8. Running the Forge installer's processors

Installing Forge or NeoForge means executing third-party programs on the user's
machine - that is what the installer's processor chain is, and there is no way
to install Forge without it. Four things narrow what that means:

- Each step runs as a **separate process**, never inside the launcher's JVM. It
  cannot reach the launcher's memory, and it cannot take the launcher down by
  calling `System.exit` - which several of these tools do.
- It runs with its **working directory in a scratch folder** under `cache/`,
  which is deleted afterwards. These installers hijack `System.out` and write a
  log file named after their own jar into the current directory.
- Every step's program and every entry of its classpath is a **maven artifact
  named by the installer profile**, downloaded through the same verifying
  downloader as everything else, and a step is refused outright if one of them
  is missing rather than run with a shorter classpath.
- Every file a step produces is **checked against the SHA-1 the profile
  publishes**. A mismatch that is still a structurally whole archive is kept
  with a note, because these jars are built at install time and a JVM using a
  native compression library produces valid but byte-different output. Anything
  else is deleted and the install stops.

The trust boundary is honest and worth naming: whoever controls the installer
jar controls what runs. That jar is fetched over HTTPS from the loader project's
own maven, and it is the same jar the user would download and double-click.

---

## 9. Verification

`./gradlew :launcher:selfCheck` runs 1213 assertions with no network and no
display, including where the CurseForge key may be sent, what the update check
will and will not accept as a newer build, and the authentication hardening:

- PKCE `S256` against RFC 7636's own test vector, verifier length and character
  set, and that two verifiers differ;
- that the authorization request carries `code_challenge_method=S256`, a
  `state`, `response_type=code`, no client secret, no verifier, a loopback IP
  redirect and no scope beyond `XboxLive.signin`;
- that a wrong or missing `state` is refused;
- that account metadata cannot carry a token and that metadata plus secrets
  reconstructs the account;
- that registered secrets and unregistered token shapes are both removed from
  log lines, and that ordinary text is left alone;
- that the launch placeholder is distinctive and is not itself token-shaped;
- that with no CurseForge key nothing is added to a CurseForge request, that a
  key set at runtime reaches the API host and both content hosts, that Modrinth
  and look-alike domains receive nothing, and that the key is masked in a log
  line.

---

## 10. Updating the launcher

The launcher replaces itself from the project's own releases (README, "Updating
itself"). That is code arriving on the user's machine and being run, so it is
written down here in the same terms as everything else: what is checked, and what
is not.

**What happens.** One request to `api.github.com` over HTTPS asks what the chosen
channel has published. Nothing is downloaded on the strength of that answer
alone: a window says which version, from what to what, and what changed, and
waits. Only then is the file for this operating system fetched - over HTTPS, from
the address the release publishes - unpacked beside the installed folder, and
swapped in by a second process that moves the old folder aside first and puts it
back if anything fails.

| Checked | How |
|---|---|
| The transport | HTTPS to `api.github.com` and to the release's own download host, through the JDK's default TLS. No custom trust manager anywhere in this codebase |
| That it is the right file | The asset is matched by name to this operating system, and a release with no build for it produces no offer at all |
| That it arrived whole | The number of bytes received is compared with the length the release publishes; a short file is deleted rather than unpacked |
| That the archive is an application image | The unpacked folder must carry the runtime and the jars in the layout jpackage produces, or the update stops before anything is replaced |
| That the swap can be undone | The installed folder is moved aside, not deleted, until the new one is in place |

**What is not checked, and this is the important half.**

- **There is no signature.** The build is not code-signed and the download is not
  verified against a key this project controls. What authenticates it is the
  transport and the repository: whoever can publish a release in
  `SAN4EZDREAMS/HexadronLauncher` can publish a build the launcher will install.
  A compromised maintainer account is therefore a compromised launcher, and no
  amount of hashing inside this repository would change that - a hash published
  next to the file it describes is signed by nobody.
- **Neither Windows SmartScreen nor macOS Gatekeeper vouches for it.** The
  clients are unsigned archives; see the README on why an unsigned installer is
  worse than none. A user who wants a second opinion has the release page and the
  build log that produced the file.
- **The update is only as trustworthy as the channel.** Nightly builds are
  published from a branch without review. That is what the channel means, it is
  not the default, and switching to it is a deliberate act in the settings.

**What it does not need.** No elevation, ever: the update writes only to the
folder the launcher is installed in and to `.hexadron-update` beside it. An
installation the user cannot write to is refused with an explanation rather than
asking for a password, and the launcher never starts an installer or a helper
with rights of its own.

**The check can be switched off** in Settings, under Downloads. With it off the
launcher makes no request of its own at start-up.

---

## Reporting

Report a suspected vulnerability privately through the repository's security
advisory page rather than in a public issue.
