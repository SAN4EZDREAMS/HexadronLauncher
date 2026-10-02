# Crash analysis

What the launcher does when the game stops with an error: which files it reads, how it finds the cause, which fixes it offers, and how new rules reach launchers that are already installed.

## When the crash window opens

After the game process ends, the launcher opens the crash window when all of these are true:

| Condition | Why |
|---|---|
| You did not stop the game with **Stop** or from the tray icon, unless it had been silent for 45 seconds first | On Windows a stopped game also ends with a non-zero exit code. That is not a crash. A silent game that you stopped is probably frozen, and the window explains that |
| The exit code is not 0, the game wrote a crash report during this run, or it logged a `FATAL` line (not Forge 1.12's "Suppressed additional N model loading errors", which stops nothing) | Minecraft can write a crash report and still exit with code 0. Forge for 1.12 shows its own error screen (duplicate or missing mods) and exits with code 0 when you close it |
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
| `threads` | `hexadron-threads.txt` in the game folder, written by the thread-dump agent (see below) | The first 2 MB |

Lines longer than 4000 characters are cut. The analysis reads only these files. It sends nothing anywhere.

## Crash types

The built-in rule file is `launcher/src/main/resources/crash/rules.json`. It knows these causes. Every text is in all 16 languages of the launcher.

| Cause | Loaders | One-click fixes |
|---|---|---|
| Java too old (class file version, Fabric's `(java)` requirement) | all | **Use Java N**: sets this profile's Java to version N; downloads it when needed |
| Java too new (`Unsupported class file major version`; LaunchWrapper's `cannot be cast to class java.net.URLClassLoader` for 1.12 and older on Java 9+) | all | **Let the launcher choose Java** (only when a Java path or version was set by hand) |
| Java refuses a start option (`Unrecognized VM option`, `Unrecognized option:`) | all | **Remove** *option* **from the Java arguments** (only when the profile's own arguments have it; a `--option value` pair goes whole), **Let the launcher choose Java** |
| Game heap full (`OutOfMemoryError: Java heap space`) | all | **Raise memory to N GB** |
| No free system memory (page file, `insufficient memory for the Java Runtime`) | all | **Lower memory to N GB** |
| Java could not reserve the heap (32-bit Java, too large a limit) | all | **Let the launcher choose Java**, **Lower memory** |
| Required mod missing | Fabric, Forge (1.12 and 1.13+), NeoForge | Shown together in the **Required mods are missing** block (see below): **Install all required mods**, **Switch off the mods that need them**, a web search for each mod not found |
| Mod needs another version of a mod | Fabric, Forge, NeoForge | **Switch off** the mod. When the other "mod" is the loader itself (`forge`, `neoforge`, `fabricloader`): **Update to** *loader version* first |
| Mod for another Minecraft version | Fabric, Forge, NeoForge | **Replace with** the build of the same project for this version and loader (see below), **Switch off** the mod |
| Mod built for another game or loader version: the mods.toml names a mod, but no class has this loader's `@Mod` (`The Mod File X has mods that were not found`, for example a NeoForge 1.20.4 jar in a NeoForge 1.20.1 profile); a NeoForge entrypoint class that is not in the file; a mixin config the jar declares but does not have | Forge 1.17+, NeoForge | **Replace with** the right build, **Switch off** the file |
| Mod needs a newer loader (`needs language provider javafml:47`, `lowcodefml`, `minecraft`; a mixin config that asks for a newer Mixin behaviour) | Forge 1.17+, NeoForge | **Update to** *loader version*, **Switch off** the file |
| Mod needs a language library (`needs language provider kotlinforforge:4`) | Forge 1.17+, NeoForge | **Install** the library (Kotlin for Forge and the others in the library list), **Switch off** the file |
| A file in `mods` is not a mod for this loader: a Bukkit, Spigot or Paper plugin, an incompatible OptiFine, `is not a valid mod file`, an unknown FML mod type, `Missing License Information` | Forge 1.17+, NeoForge | **Switch off** the file |
| Mods bundle versions of one library that do not fit together (`requested conflicting versions of`, `no jar was provided which matched the range`) | Forge 1.17+, NeoForge | none: update the mods named, or switch one off |
| A mod needs a feature this game or computer does not have (`is missing a feature it requires to run`) | Forge 1.20+, NeoForge | **Switch off** the mod |
| Same mod installed twice | Forge (1.12 and 1.13+), NeoForge | **Keep the newest**: switches off the copies with a lower version in their jar; the file date decides only when the versions are equal or cannot be read |
| Two mods marked incompatible | Fabric, NeoForge | **Switch off** either mod |
| Mixin error | all | **Switch off** the mod (found by mod id, or by the mixin config file inside its jar) |
| Mod crashed during loading | Fabric, Forge, NeoForge | **Switch off** the mod |
| Damaged mod jar | Fabric, NeoForge | **Switch off** the file |
| Mod for another loader (also `is for an older version of Forge`/`NeoForge`, `is a LiteLoader mod`) | Forge 1.17+, NeoForge; Forge 1.12 for a Cleanroom build (its `@Mod` annotation requires `cleanroom`) that throws on plain Forge | **Replace with** the build for this loader, **Switch off** the file |
| Graphics driver without the OpenGL the game needs | all | none: update the driver |
| Crash inside the graphics driver (`hs_err` file) | all | none: update the driver |
| Crash inside an overlay program (`hs_err` file: RivaTuner/Afterburner, Discord, Steam, OBS, ShadowPlay, Overwolf, Nahimic, Bandicam, Fraps, Mumble, XSplit) | all | none: close the program or switch off its overlay |
| Damaged configuration file (`Failed loading config file X.toml of type ... for modid ...`, NightConfig) | Forge 1.13+, NeoForge | **Reset** *file*: renames it to `.broken` in `config/`, `defaultconfigs/` and each world's `serverconfig/`; the mod writes a new one |
| Disk full (`There is not enough space on the disk`, `No space left on device`) | all | none: the text points at **Storage and cleanup** |
| World open in another game (`session.lock: already locked`) | all | none: close the other game or server |
| Shader pack does not compile (Iris or Oculus `ShaderCompileException`, `ProgramLinkException`) | all | **Switch off shaders** (*pack*): `enableShaders=false` in `config/iris.properties` or `config/oculus.properties`, `shaderPack=OFF` in `optionsshaders.txt` for OptiFine. The pack stays in the folder |
| Game or loader files missing or damaged | all | **Check the game files**: verifies every file and downloads the broken ones again |

Some causes come from the launcher's own code, not from a rule. Their texts are in the same rule file (`modCode`, `frozen`, `frozenMod`, `missingLibrary`, `libraryOff`, `missingClass`); a downloaded rule file without them is refused.

| Cause | How it is found | Fix |
|---|---|---|
| A mod's code threw the exception | The stack trace of the crash report (or of the report the game printed, or of the exception LaunchWrapper reported under `[LaunchWrapper]: Unable to launch` - on Forge 1.12 the `main` thread then ends with `FMLSecurityManager$ExitTrappedException`, which names no mod - or of `Exception in thread "main"`) is read from the root cause outwards. Frames of the JDK, the game, the loaders and common libraries are skipped. The first class that a jar in the `mods` folder contains (looked up as `com/example/Foo.class` inside the jar) names the mod | **Switch off** that jar |
| The game froze in a mod | As below, and the thread dump names a mod: its deadlocked threads, render thread or main thread were running a class from that mod's jar | **Switch off** that jar |
| A class is missing (`NoClassDefFoundError`, `ClassNotFoundException` in the crash's own exception) | `crash/Linkage.java` looks the class up in every jar. Only a switched-off jar has it: that mod is off. No jar has it: the `libraries` list of the rule file names the library by the package. A game class (`net.minecraft.`): the mod that asked is for another Minecraft version. A loader class: the mod is for another loader. Otherwise: the mod that asked needs something nobody has. The log is not searched: mods catch these exceptions on purpose while they look for optional companions | **Switch on** *library*; **Install** *library*; **Switch off** the mod that asked |
| A method is missing (`NoSuchMethodError`) | The class the method was looked for in names the other side: the game (another Minecraft version), the loader (too old), or another mod (the two do not match) | **Update to** *loader version*; **Switch off** the mod that asked |
| The game stopped responding | The game ended with an error, wrote no crash report, no other cause was found, and it printed nothing for 45 seconds or more before it ended - typically a frozen start that was stopped | none: switch off the mods added last |

The window shows at most four causes, most specific first. Mods are named by the name in their jar, not by their id.

A Forge 1.12 crash report whose mod table has no mod past state `L` (none was constructed), with a stack through the game loop, is the loader's error screen too, even when no rule names its cause. The mod in the stack is not reported then either.

A cause that stops the loader before the mods start (a mod installed twice, a missing or wrong dependency, a mod for another Minecraft version or loader, a damaged jar, a broken installation) explains what crashes after it. Forge for 1.12 draws its error screen, and a mod hooked into the game loop runs there with none of its own start-up done and throws. That mod is not reported as a cause, and `logs/launcher.log` says so (`reported as a consequence, not a cause`). Rules mark these causes with `stopsLoading`.

When every cause has a recommended fix, the window has **Fix and start the game**: it applies them in order, each only once, and starts the game. A fix is recommended when it is the only one, or when it is first and repairs rather than removes (install, switch on, update the loader, reset a file, remove an option, Java, memory, keep the newest copy, check the game files). The fixes you applied one by one already are skipped. When a cause has no fix, or a choice between two things to switch off (two incompatible mods, for example), you choose, and the button is not shown.

### Fixes that need the network

### Required mods are missing

When one or more causes are a missing mod, the window shows them in one block above the other causes, each missing mod once, with the mods that need it. The causes of that kind get no card of their own.

| State of a missing mod | What the block shows |
|---|---|
| Modrinth or CurseForge has a file for this Minecraft version and loader | **Found on Modrinth** (or CurseForge): installs with its own required dependencies |
| A switched-off copy is in the `mods` folder | It will be switched on |
| Nothing found | **Not found for Minecraft** *version* · *loader*, and **Search the web:** *Minecraft version loader name* - a link that opens a Google search with these words in the browser |
| Installing it failed | The reason, and the same search link |

**Install all required mods (N)** installs or switches on every mod that was found, one after another in one task. A failure does not stop the others. When all are there, **Start the game again** becomes the main button.

**Switch off the mods that need them** is shown while at least one mod is not found or did not install. It reads the `mods` folder again and asks first. The question lists:

- the mods that need the missing mods;
- the mods that need those, at any depth: without them these do not start either, so they go too;
- the libraries that nothing left switched on needs any more, each with a tick box. A box is ticked when the mod is plainly a library (the rule file lists it, or its platform files it under libraries). A content mod that only an add-on needed is not ticked.

Switching off renames the jars to `.disabled`. Nothing is deleted.

NeoForge for Minecraft 1.20.1 is the Forge of 1.20.1 under a new name and loads Forge mods. For that version the launcher accepts `neoforge` and `forge` files: in Modrinth searches and file lookups, and in CurseForge file lookups (a CurseForge search takes one loader and stays `neoforge`). From 1.20.2 on, only `neoforge`.

**Install** and **Update to** are looked up while the crash is analysed, at most 8 seconds each, and are offered only when they can work: a Modrinth project with a file for this Minecraft version and loader, or a loader build newer than the profile's. With no connection there is no button, not a button that fails.

**Install** looks the mod id up in the rule file's `libraries` list first (`fabric` is Fabric API, `cloth-config2` is Cloth Config), then as a Modrinth slug. When Modrinth has no file for this version and loader and a CurseForge API key is set, CurseForge is asked next, by the library's own CurseForge slug (`redstoneflux` is `redstone-flux`) and then by the same slugs; most Forge 1.12 libraries are only there. A file whose author allows it only from the CurseForge website is not downloaded, and the fix says so. It installs with the mod's own required dependencies, and keeps the download only when a jar in it carries the mod id that was asked for; otherwise the jars are switched off again and the fix says it did not work. **Update to** takes Forge's newest build (Forge marks only its recommended build as stable, and that is often older than a mod asks for) and the newest stable build of the other loaders.

**Replace with** asks Modrinth which project the jar belongs to (by its SHA-1), then asks for that project's newest file for this Minecraft version, one loader tag at a time, and never the same file again. On NeoForge 1.20.1 it asks for `forge` first: Modrinth lists some NeoForge builds for later versions under 1.20.1 too, and those are exactly the files that fail there. A jar that Modrinth does not know is looked up on CurseForge by its fingerprint when a key is set. The new file goes in the way mod updates are installed: the old jar is set aside in `mods/.removed/` and the update can be rolled back; the dependencies the new build needs come with it.

The Forge and NeoForge mod-loading errors are matched in the game log, in the game output and in the crash report (`Failure message: Mod x requires y ...`), so a crash is explained even when only the report is left. A file named in a long path is named whole: the file name is taken from the full path before the value is shortened for the window.

These fixes are added by the launcher to the causes (`CrashFixes.withDerived`), not written in the rule file. A launcher refuses a whole rule file that names a fix kind it does not know, so a rule that used the new kinds would stop every older launcher from taking any rule update.

## Checks before the launch

**Play** checks the `mods` folder before the game starts, so the most common causes are fixed without a crash first.

| Check | Buttons |
|---|---|
| A mod is switched on in two or more files (the same mod id) | **Keep the newest and play** (the default): switches off every copy but the newest version, by the version in the jar, by the file date when the versions are equal or cannot be read. **Launch anyway**, **Cancel** |
| Mods need mods that are not there: a required dependency of a switched-on mod that no switched-on jar provides. A jar provides its own id, every `mcmod.info` entry and `[[mods]]` entry, Fabric and Quilt `provides`, and the ids of the jars nested in it (`META-INF/jars/`, `META-INF/jarjar/`) - one module of Fabric API is Fabric API. Forge 1.12 mods also require what their `@Mod` annotation names (`required-after:cofhcore`), read from the constant in the class file by its tag and length. A requirement on another loader (`cleanroom`) is not a mod to install: the mod is for another loader. The game, Java and the loaders are not mods. Checked in the background after **Play**, because the first read of a large 1.12 pack takes a few seconds | **Install and play** (the default): switches on a jar that is only switched off, installs the rest from Modrinth as the crash fix does. What cannot be installed would stop the game every time, so the launcher then asks again: **Switch off and play** for the mods that need it, **Launch anyway**, **Cancel**. **Launch anyway**, **Cancel** |
| A mod is for another mod loader: its jar has descriptors, and none is for this profile's loader (`fabric.mod.json` in a Forge profile; `mods.toml` only, in a NeoForge 1.20.5+ profile). Quilt loads Fabric mods; Sinytra Connector lets Forge and NeoForge load them; Kilt lets Fabric load Forge mods. A jar without descriptors is not judged | **Switch off and play** (the default), **Launch anyway**, **Cancel** |
| Mods known not to work together (the rule file's `conflicts`: OptiFine with Sodium, Embeddium, Rubidium, Magnesium, Iris, Oculus, Nvidium, Canvas or Indium). OptiFine declares no id and is found by its classes | **Switch off** *one side* **and play** for either side, **Launch anyway**, **Cancel**. Mods that need the side switched off go with it |
| A mod is for another Minecraft version | See [mods.md](mods.md) |

A launch started by the problem-mod search skips these checks: the search sets the mods itself.

What the player answers to each of these questions is written to `logs/launcher.log` (`Before launch: <question>: <answer>`).

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
- `conflicts` (optional): mods that do not work together, as an `id`, the ids on one side (`mods`) and on the other (`with`). `optifine` stands for OptiFine. Older launchers ignore the list.
- `libraries` (optional): library mods the launcher can name and install. Each has a Modrinth `slug`, an optional `curseforge` slug when it differs, a `name`, the mod `ids` other mods ask for it by, the `packages` its classes are in (each ends with a dot), and optional `loaders` (`fabric`, `quilt`, `forge`, `neoforge`). Older launchers ignore the list.
- `stopsLoading` (`true` or `false`, default `false`): the cause stops the loader before the mods start, so the stack trace of a mod that crashes after it is not reported. Older launchers ignore the field.
- `fixes` are only the kinds in `crash/CrashFix.java`: `disableMod`, `disableFile`, `disableMixinOwner`, `disableDuplicates`, `java`, `automaticJava`, `raiseMemory`, `lowerMemory`, `reinstall`, and since rule version 4 `installMod`, `enableFile`, `updateLoader`, `resetConfig`, `removeJvmArgument`. Use the new five in the published file only when no launcher older than them needs updates any more (see above). A rule cannot run a command or open a link.

The whole file is refused when a rule names an unknown fix, a fix parameter it does not take, a text with a value that the rule does not provide, a text without English, a condition without `contains`, a bad regular expression, or a schema other than 1.

The self-check (`SelfCheck`, section "Crash analysis") runs every rule against real loader and JVM output and checks every text in every language.

## Finding the problem mod

When no rule names the cause, or the problem is not a crash at all (wrong textures, a freeze, a missing feature), the launcher can find the mod by halving the mod set. Start it with **Find the problem mod** in the crash window or in the right-click menu of a profile.

1. The launcher records which jars in the `mods` folder are switched on. Jars that were off stay off and take no part.
2. It switches on half of them, together with every mod they require, and switches the rest off by adding `.disabled` to their names. The requirements come from each jar's descriptor, and for Forge 1.12 mods from their `@Mod` annotation too: without it the descriptors name the requirements of 3 mods in 80, and most launches would stop on a missing library.
3. You press **Start the game**. A search started from the crash window (or within 30 minutes of a crash in that profile) looks for that crash: `.hexadron-bisect-problem.json` holds its cause (or the root exception and first mod class of its stack). A crash is recognised by itself and counts as the problem when it is that crash. Another crash is not an answer: the window says which crash it was and asks **Did the problem occur?** A search started without a crash counts every crash, except one where the loader refused the mod set (a mod missing, installed twice, for another loader), which the halving so often causes - that one is asked about too. A harmless `FATAL` line is no crash - unless the crash analysis says a mod needed another one that this step switched off (a missing dependency, or a class only a switched-off jar has). That is the search's own doing: the launcher learns the requirement (`.hexadron-bisect-learned.json`), sets up the same step again with both on, and says so. A mod that needs a mod that is not in the folder at all cannot start in any step: the search keeps it off from then on (`off` in the same file), says so, and runs the step again. A mod whose missing class names no mod that asked for it is kept on whenever anything is. Otherwise play until you know, close the game and answer **Did the problem occur?** Only a launch started from this window reports to it: when you close the window while the game runs, it opens again when the game ends. A game started with **Play** opens the normal crash window, even when the search window was opened for that profile before.
4. The half that shows the problem is halved again. About log2(n) launches find one mod among n: 6 for 46 mods, 8 for 200.
5. A mod found that needs other mods never ran without them, so a library it needs is as likely the cause. Those are searched next, without the mod found; when none of them shows the problem, the mod found is the answer. A library found this way is checked the same way.
6. When each half works on its own, two mods conflict. The launcher then keeps one half on and halves the other to find the first mod, then finds its partner the same way.
7. The window names the mod (or the pair) and the mods it needs. **Keep it switched off** restores everything else; **Restore all mods** puts back the set from step 1.

The search is saved in `.hexadron-bisect.json` in the profile's game folder after every step. Closing the window or the launcher loses nothing: the next **Play** in that profile opens the search again, so you never play a half-switched-off mod set without knowing it. **Stop and restore** ends the search at any step. No file is moved out of the `mods` folder or deleted.

## Stopping a frozen game

The **Stop** button and **Stop** in the tray menu end the game and any process it started (a wrapper command, for example). A game that has not ended 10 seconds later is ended forcibly. Use them instead of Task Manager: there, the launcher and the game are both Java, and ending the wrong one closes the launcher. A game stopped this way does not open the crash window.

## Thread dumps of a frozen game

The launch wrapper jar is also a Java agent (`com.hexadron.wrapper.ThreadDumpAgent`). Every game starts with `-javaagent:<wrapper jar>=<game folder>`. The agent changes no class and sends nothing: once a second it looks for `hexadron-threads.request` in the game folder.

When the game has printed nothing for 30 seconds, the launcher creates that file and writes a line in its log. Within a second the agent writes `hexadron-threads.txt`: deadlocked threads first, then the render and main threads, then all the others, each with its full stack and locks. When the game is then stopped or ends, the crash analysis reads the first three threads and names the mod whose class they were running.

Send `hexadron-threads.txt` with a bug report about a frozen game.

## A launcher that stops responding

A background thread checks every 2 seconds that the launcher window still answers. When it has not answered for 8 seconds, `logs/launcher.log` gets the stack of the interface thread, and one more line when it answers again. Send that part of the log with a bug report.

Game output reaches the log panel in batches, one update for however many lines arrived. When more than 5000 lines wait, the oldest are left out of the panel with one line that says how many; `launcher.log` still has all of them.

## Command line

`play <profile>` in command-line mode prints the causes in English after a crash.
