# Crash analysis

What the launcher does when the game stops with an error: which files it reads, how it finds the cause, which fixes it offers, and how new rules reach launchers that are already installed.

## When the crash window opens

After the game process ends, the launcher opens the crash window when all of these are true:

| Condition | Why |
|---|---|
| You did not stop the game with **Stop** or from the tray icon | On Windows a stopped game also ends with a non-zero exit code. That is not a crash |
| The exit code is not 0, or the game wrote a crash report during this run | Minecraft can write a crash report and still exit with code 0 |
| The exit code is not 92 | Exit 92 is the launcher's own launch handshake. The log line above it explains that case |

The window is not modal. You can open the mods window or the crash report while it is open.

## What it reads

Only files written during this run count. A file counts when it was changed after the launch started (with 3 seconds of tolerance for file systems that store times coarsely).

| Source | File | Part read |
|---|---|---|
| `output` | What the game printed, as the launcher read it | The last 4000 lines |
| `log` | `logs/latest.log` | The last 4 MB |
| `crash` | The newest `crash-reports/crash-*.txt` | The first 1 MB |
| `hserr` | The newest `hs_err_pid*.log` in the game folder (the JVM's fatal error file) | The first 1 MB |

Lines longer than 4000 characters are cut. The analysis reads only these files. It sends nothing anywhere.

## Crash types

The built-in rule file is `launcher/src/main/resources/crash/rules.json`. It knows these causes. Every text is in all 16 languages of the launcher.

| Cause | Loaders | One-click fixes |
|---|---|---|
| Java too old (class file version, Fabric's `(java)` requirement) | all | **Use Java N**: sets this profile's Java to version N; downloads it when needed |
| Java too new (`Unsupported class file major version`) | all | **Let the launcher choose Java** (only when a Java path was set by hand) |
| Game heap full (`OutOfMemoryError: Java heap space`) | all | **Raise memory to N GB** |
| No free system memory (page file, `insufficient memory for the Java Runtime`) | all | **Lower memory to N GB** |
| Java could not reserve the heap (32-bit Java, too large a limit) | all | **Let the launcher choose Java**, **Lower memory** |
| Required mod missing | Fabric, Forge, NeoForge | **Switch off** the mod that needs it |
| Mod needs another version of a mod | Fabric, Forge, NeoForge | **Switch off** the mod |
| Mod for another Minecraft version | Fabric, Forge, NeoForge | **Switch off** the mod |
| Same mod installed twice | Forge, NeoForge | **Keep the newest**: switches off the older files |
| Two mods marked incompatible | Fabric, NeoForge | **Switch off** either mod |
| Mixin error | all | **Switch off** the mod (found by mod id, or by the mixin config file inside its jar) |
| Mod crashed during loading | Fabric, Forge, NeoForge | **Switch off** the mod |
| Damaged mod jar | Fabric, NeoForge | **Switch off** the file |
| Mod for another loader | NeoForge | **Switch off** the file |
| Graphics driver without the OpenGL the game needs | all | none: update the driver |
| Crash inside the graphics driver (`hs_err` file) | all | none: update the driver |
| Game or loader files missing or damaged | all | **Check the game files**: verifies every file and downloads the broken ones again |

Two causes come from the launcher's own code, not from a rule. Their texts are in the same rule file (`modCode`, `frozen`); a downloaded rule file without them is refused.

| Cause | How it is found | Fix |
|---|---|---|
| A mod's code threw the exception | The stack trace of the crash report (or of the report the game printed, or of `Exception in thread "main"`) is read from the root cause outwards. Frames of the JDK, the game, the loaders and common libraries are skipped. The first class that a jar in the `mods` folder contains (looked up as `com/example/Foo.class` inside the jar) names the mod | **Switch off** that jar |
| The game stopped responding | The game ended with an error, wrote no crash report, no other cause was found, and it printed nothing for 45 seconds or more before it ended - typically a frozen start that was ended in Task Manager | none: switch off the mods added last |

The window shows at most four causes, most specific first. Mods are named by the name in their jar, not by their id.

A fix is offered only when it would change something in this profile. **Switch off** needs the mod in this profile's `mods` folder, switched on. **Raise memory** needs room: the new limit is half as much again (at least 1 GB more), in 512 MB steps, and never more than three quarters of the computer's memory or 16 GB.

Switching off a mod renames its jar to `.disabled`, as the mods window does. When other mods need it, the window lists them and switches them off too, after you confirm. You can switch everything back on in the mods window.

When no rule matches, the window says so and links the crash report, the game logs and the bug report window.

## Rule updates

A newer rule file is published with each release as `hexadron-crash-rules.json`, with its signature `hexadron-crash-rules.json.sig`. Once a day, in the background after start-up, the launcher asks the newest release on its update channel for this file.

| Check | Result when it fails |
|---|---|
| **Look for launcher updates at start-up** is on | Nothing is asked |
| The file has an Ed25519 signature by the update key (the key that signs update manifests, `update/UpdateSignature.java`) | The built-in rules stay in use |
| Its `version` is higher than the built-in file's | The built-in rules stay in use |
| It parses without one error (see below) | The built-in rules stay in use |

The downloaded file is kept in `cache/crash-rules/` and its signature is checked again every time it is read. `logs/launcher.log` says which rules each analysis used (`built-in, version N` or `downloaded, version N`).

The release workflow copies `crash/rules.json` into the release and signs it in the same step as the manifests (`.github/workflows/release-launcher.yml`). To publish new rules, raise `version` in `rules.json` and make a release.

## Rule file format

The format is documented in `crash/CrashRules.java`. In short:

- `texts`: for each cause, `title`, `cause` and `fix` per language. English is required. `{name}` inserts a value.
- `rules`: each has an `id`, a `priority`, a `text`, and a list of `match` conditions that all have to hold.
- A condition has `contains` (required: plain strings, any one lets a line through), an optional `regex` whose named groups become values, `in` (sources), `lines` (1 to 5 lines joined, for messages that continue on the next line) and `repeat` (report every distinct match, up to 5).
- `values` derives more values: `classfile(g)` (class file version to Java version), `file(g)`, `stem(g)` (mixin config name to mod name), `int(g)`, `lower(g)`.
- `fixes` are only the kinds in `crash/CrashFix.java`: `disableMod`, `disableFile`, `disableMixinOwner`, `disableDuplicates`, `java`, `automaticJava`, `raiseMemory`, `lowerMemory`, `reinstall`. A rule cannot run a command or open a link.

The whole file is refused when a rule names an unknown fix, a fix parameter it does not take, a text with a value that the rule does not provide, a text without English, a condition without `contains`, a bad regular expression, or a schema other than 1.

The self-check (`SelfCheck`, section "Crash analysis") runs every rule against real loader and JVM output and checks every text in every language.

## Stopping a frozen game

The **Stop** button and **Stop** in the tray menu end the game and any process it started (a wrapper command, for example). A game that has not ended 10 seconds later is ended forcibly. Use them instead of Task Manager: there, the launcher and the game are both Java, and ending the wrong one closes the launcher. A game stopped this way does not open the crash window.

## A launcher that stops responding

A background thread checks every 2 seconds that the launcher window still answers. When it has not answered for 8 seconds, `logs/launcher.log` gets the stack of the interface thread, and one more line when it answers again. Send that part of the log with a bug report.

Game output reaches the log panel in batches, one update for however many lines arrived. When more than 5000 lines wait, the oldest are left out of the panel with one line that says how many; `launcher.log` still has all of them.

## Command line

`play <profile>` in command-line mode prints the causes in English after a crash.
