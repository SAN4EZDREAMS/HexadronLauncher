/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.core;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.auth.AccountStore;
import com.hexadron.launcher.auth.MicrosoftAuth;
import com.hexadron.launcher.auth.secret.SecretStore;
import com.hexadron.launcher.auth.secret.SecretStores;
import com.hexadron.launcher.install.VersionInstaller;
import com.hexadron.launcher.install.loader.LoaderInstaller;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.install.loader.LoaderVersion;
import com.hexadron.launcher.install.loader.Loaders;
import com.hexadron.launcher.launch.GameLauncher;
import com.hexadron.launcher.launch.JavaLocator;
import com.hexadron.launcher.launch.JavaRuntimes;
import com.hexadron.launcher.launch.LaunchCommandBuilder;
import com.hexadron.launcher.launch.LaunchWrapperJar;
import com.hexadron.launcher.meta.AssetIndex;
import com.hexadron.launcher.meta.VersionJson;
import com.hexadron.launcher.meta.VersionManifest;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.mods.CurseForgeProvider;
import com.hexadron.launcher.mods.ModInstaller;
import com.hexadron.launcher.mods.ModPack;
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.mods.ModrinthProvider;
import com.hexadron.launcher.net.Downloader;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileStore;
import com.hexadron.launcher.skin.SkinStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The launcher's application layer: everything the UI needs, with no UI
 * dependencies, so the whole flow is drivable headlessly and testable.
 */
public final class LauncherService {

    private final GameDirs dirs;
    private final LauncherSettings settings;
    private final Downloader downloader;

    /**
     * Which files have already been checked against their hash.
     *
     * <p>Owned here because it outlives any one install: the point is that the
     * work done on Monday's launch is still done on Tuesday's.
     */
    private final VerifiedFiles verified;

    /** Skins and capes, and which account wears which. */
    private final SkinStore skinStore;
    private final VersionInstaller versionInstaller;
    private final ProfileStore profiles;
    private final AccountStore accounts;
    private final SecretStore secretStore;
    private final JavaLocator javaLocator;
    private final JavaRuntimes javaRuntimes;
    private final LaunchCommandBuilder commandBuilder;
    private final GameLauncher gameLauncher = new GameLauncher();
    private final ModrinthProvider modrinth = new ModrinthProvider();
    private final CurseForgeProvider curseForge;
    private final ModInstaller modInstaller;

    /**
     * The two installers that are not the mod installer.
     *
     * <p>Separate classes rather than more methods on {@link ModInstaller},
     * because what they do differs in the parts that matter: a modpack is a
     * version, a loader and a set of files written across a whole instance, and a
     * data pack goes into one world's folder and has no dependencies to follow.
     * Only the search is genuinely shared, and that is where they meet - in
     * {@link com.hexadron.launcher.mods.ContentKind}.
     */
    private final com.hexadron.launcher.mods.ModpackInstaller modpackInstaller;
    private final com.hexadron.launcher.mods.DatapackInstaller datapackInstaller;
    private final com.hexadron.launcher.mods.PackInstaller resourcePackInstaller;
    private final com.hexadron.launcher.mods.PackInstaller shaderInstaller;
    /** The crash rules: built in, or a newer signed file from a release. */
    private final com.hexadron.launcher.crash.CrashRuleSource crashRules;

    /**
     * Named stages of start-up that this class runs, in the order they run.
     *
     * <p>Reported as identifiers rather than sentences: this class has no
     * interface layer and no translations, and the splash screen turns each of
     * these into a line in the user's own language.
     *
     * <p>Every piece of work the constructor does is inside one of these stages.
     * A new piece of start-up work gets its own stage here, a
     * {@code splash.step.<name>} line in every language, and a call to the
     * step consumer where it begins. The self-check fails when a stage has no
     * line in the reference language.
     */
    public static final List<String> STARTUP_STEPS = List.of(
            "settings", "dataFolder", "verifiedFiles", "profiles", "credentials",
            "accounts", "skins", "network", "javaRuntimes", "platforms");

    /**
     * Named stages of start-up that the application runs around this class, in
     * the order they run: clearing the leftovers of an old update, the update
     * check, the language, and the window.
     *
     * <p>Kept here, next to {@link #STARTUP_STEPS}, so that the splash screen
     * and the self-check read one complete list. The {@code updates} stage does
     * not run when the update check is switched off in the settings.
     */
    public static final List<String> LAUNCHER_STEPS =
            List.of("updateCleanup", "updates", "language", "interface");

    /** Every stage the splash screen can show, in the order they run. */
    public static final List<String> ALL_STARTUP_STEPS;

    static {
        java.util.List<String> all = new java.util.ArrayList<>(STARTUP_STEPS);
        all.addAll(LAUNCHER_STEPS);
        ALL_STARTUP_STEPS = List.copyOf(all);
    }

    public LauncherService(GameDirs dirs, LauncherSettings settings) throws IOException {
        this(dirs, settings, step -> { });
    }

    /**
     * @param step called with each of {@link #STARTUP_STEPS} as it begins. Runs
     *             on the calling thread, which is not the interface thread, so
     *             implementations marshal for themselves.
     */
    public LauncherService(GameDirs dirs, LauncherSettings settings, Consumer<String> step)
            throws IOException {
        step.accept("dataFolder");
        this.dirs = dirs.createBaseDirectories();
        this.settings = settings;
        this.downloader = new Downloader(settings.downloadConcurrency());
        // Read once, at start-up, off the launch path: it is one file, and
        // reading it while the user waits for the game would be the wrong place
        // to spend the time it exists to save.
        step.accept("verifiedFiles");
        this.verified = VerifiedFiles.load(this.dirs);
        this.downloader.verified(verified);
        this.versionInstaller = new VersionInstaller(dirs, downloader);
        step.accept("profiles");
        this.profiles = new ProfileStore(dirs).load();
        // Deliberately not probed here: SecretStores.forHost hands back a store
        // that decides what it is on first use. Probing costs two PowerShell
        // launches on Windows, and most starts never read a credential at all.
        step.accept("credentials");
        this.secretStore = SecretStores.forHost(this.dirs, settings.useFileCredentialStore());
        step.accept("accounts");
        this.accounts = new AccountStore(this.dirs, secretStore).load();
        step.accept("skins");
        this.skinStore = new SkinStore(this.dirs).load();

        // Before anything is fetched. On a network that needs a proxy, a single
        // request sent direct is a twenty-second wait for a failure. A stage of
        // its own, because a proxy with a password reads it from the credential
        // store, and on Windows that can be the slowest step of start-up.
        step.accept("network");
        applyProxy();
        step.accept("javaRuntimes");
        this.javaLocator = new JavaLocator(dirs);
        // One resolver, shared by launching and by the loader installers, so a
        // profile can never install against one Java and start on another.
        this.javaRuntimes = new JavaRuntimes(this.dirs, javaLocator,
                settings::javaDownloadPolicy,
                policy -> {
                    settings.javaDownloadPolicy(policy);
                    try {
                        settings.save();
                    } catch (IOException e) {
                        // The runtime is still installed and still used; only the
                        // "do not ask again" part is lost, so this is not fatal.
                        System.err.println("could not store the Java download setting: "
                                + e.getMessage());
                    }
                });
        this.versionInstaller.javaRuntimes(javaRuntimes);
        this.commandBuilder = new LaunchCommandBuilder(dirs);
        step.accept("platforms");
        this.curseForge = CurseForgeProvider.fromEnvironment(settings.curseForgeApiKey());
        this.modInstaller = new ModInstaller(downloader, modrinth, curseForge);
        this.modpackInstaller =
                new com.hexadron.launcher.mods.ModpackInstaller(downloader, modrinth, curseForge);
        this.datapackInstaller = new com.hexadron.launcher.mods.DatapackInstaller(
                downloader, modInstaller, modrinth, curseForge);
        // One per kind, because the folder and the record file are the kind's.
        this.resourcePackInstaller = new com.hexadron.launcher.mods.PackInstaller(
                com.hexadron.launcher.mods.ContentKind.RESOURCEPACK,
                downloader, modrinth, curseForge);
        this.shaderInstaller = new com.hexadron.launcher.mods.PackInstaller(
                com.hexadron.launcher.mods.ContentKind.SHADER,
                downloader, modrinth, curseForge);
        this.crashRules = new com.hexadron.launcher.crash.CrashRuleSource(
                dirs.cache().resolve("crash-rules"));
    }

    /** Builds a service rooted at the default location. */
    public static LauncherService createDefault() throws IOException {
        return createDefault(step -> { });
    }

    /** As {@link #createDefault()}, reporting each stage to {@code step}. */
    public static LauncherService createDefault(Consumer<String> step) throws IOException {
        GameDirs dirs = GameDirs.defaultDirs();
        step.accept("settings");
        LauncherSettings settings = new LauncherSettings(dirs).load();
        return new LauncherService(dirs, settings, step);
    }

    /**
     * Does work now that would otherwise be done on the first click.
     *
     * <p>Called after the window is on screen, on a background thread, so it
     * costs the user nothing they can see. Detecting Java is the one worth
     * warming: it reads the registry and probes every runtime it finds, and
     * without this the bill arrives on the first press of Play.
     */
    public void warmUpInBackground() {
        Thread warm = new Thread(() -> {
            // Credentials first: they are what the Play button needs, and
            // reading them is the slow part of a start on Windows.
            accounts.loadSecrets();
            loadCurseForgeKey();
            try {
                javaLocator.discover();
            } catch (RuntimeException ignored) {
                // A warm-up that fails costs nothing; the real call will report.
            }
            // A profile deleted while the launcher was closing leaves its files
            // in the instances folder's .deleting. Finished here, where nobody
            // is waiting for it.
            profiles.purgeLeftovers();
            refreshCrashRules();
        }, "hexadron-warmup");
        warm.setDaemon(true);
        warm.setPriority(Thread.MIN_PRIORITY);
        warm.start();
    }

    /**
     * Asks the latest release for newer crash rules, at most once a day.
     *
     * <p>Under the same switch as the update check: a player who told the
     * launcher not to ask GitHub about updates did not mean "except for this".
     */
    private void refreshCrashRules() {
        if (!settings.checkForUpdates()) {
            return;
        }
        try {
            crashRules.refresh(new com.hexadron.launcher.update.ReleaseFeed(), settings.updateChannel())
                    .ifPresent(LauncherLog::info);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LauncherLog.info("Crash rules: check failed: " + e);
        }
    }

    // ---------------------------------------------------------------- crashes

    public com.hexadron.launcher.crash.CrashRuleSource crashRules() {
        return crashRules;
    }

    /** Every jar in a profile's mods folder, on and off; empty for a profile with none. */
    private java.util.List<com.hexadron.launcher.mods.ModEntry> modsOf(Profile profile) {
        Path modsDir = profiles.modsDirectory(profile);
        if (!java.nio.file.Files.isDirectory(modsDir)) {
            return java.util.List.of();
        }
        return com.hexadron.launcher.mods.ModScan.scan(modsDir, profile.minecraftVersion());
    }

    /**
     * Explains why a game stopped, naming mods by the names in their jars.
     *
     * @param language two-letter code of the language to explain it in
     */
    public java.util.List<com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis> analyzeCrash(
            Profile profile, com.hexadron.launcher.crash.CrashEvidence evidence, String language) {
        return analyzeCrash(profile, evidence, language, 0);
    }

    /** Silence before the end, past which a game with no other explanation is called frozen. */
    public static final long FROZEN_AFTER_MILLIS = 45_000;

    /**
     * Explains why a game stopped: the rules first, then the mod a stack trace
     * points at, then - when nothing else explains it - a game that went silent
     * long before it was ended.
     *
     * @param quietMillis how long the game printed nothing before it ended
     */
    public java.util.List<com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis> analyzeCrash(
            Profile profile, com.hexadron.launcher.crash.CrashEvidence evidence, String language,
            long quietMillis) {
        return analyzeCrash(modsOf(profile), crashRules.current(), profile.loader(), evidence, language, quietMillis);
    }

    /**
     * Explains a crash from the mods of a folder and a rule file, with no
     * profile: the same answer {@link #analyzeCrash(Profile, com.hexadron.launcher.crash.CrashEvidence, String, long)}
     * gives, for the self-check and for replaying real crashes.
     *
     * @param loader the profile's loader; null when unknown
     */
    public static java.util.List<com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis> analyzeCrash(
            java.util.List<com.hexadron.launcher.mods.ModEntry> mods, com.hexadron.launcher.crash.CrashRules rules,
            LoaderType loader, com.hexadron.launcher.crash.CrashEvidence evidence, String language,
            long quietMillis) {
        String loaderKey = loader == null ? "" : loader.name().toLowerCase(java.util.Locale.ROOT);
        java.util.List<com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis> found = new java.util.ArrayList<>(
                com.hexadron.launcher.crash.CrashAnalyzer.analyze(evidence, rules, language, id -> {
                    // A mod that is not in the folder is named by the library
                    // list when it knows it: "HBM's Nuclear Tech Mod", not "hbm".
                    String name = com.hexadron.launcher.crash.CrashFixes.displayName(mods, id);
                    return name == null || name.equals(id)
                            ? rules.libraryForMod(id, loaderKey).map(com.hexadron.launcher.crash.CrashRules.Library::name)
                                    .orElse(id)
                            : name;
                }));

        // A cause that stopped the loader explains what crashed after it. Forge
        // 1.12 with a mod installed twice draws its error screen, a mod hooked
        // into the game loop runs there with none of its own start-up done, and
        // throws - and naming that mod would offer to switch off the wrong one.
        //
        // The crash report says so even when no rule knows the cause: no mod
        // got past loading, and the game crashed in its own loop.
        boolean ruled = com.hexadron.launcher.crash.CrashAnalyzer.loaderStopped(found, rules);
        boolean early = !ruled && evidence.loaderStoppedEarly();
        boolean loaderStopped = ruled || early;
        if (loaderStopped) {
            com.hexadron.launcher.crash.StackAttribution.blame(evidence, mods).ifPresent(blame ->
                    LauncherLog.info("Crash analysis: " + blame.mod().fileName() + " threw after the loader"
                            + " had stopped" + (early ? " (no mod was started)" : "")
                            + "; reported as a consequence, not a cause"));
        }
        // A class or method that is not where a mod looked for it: the answer
        // is usually on the other side - a library switched off, not installed,
        // or of another version - so it goes before the mod the stack names.
        boolean linked = false;
        if (!loaderStopped && found.size() < com.hexadron.launcher.crash.CrashAnalyzer.MAX_DIAGNOSES) {
            java.util.Optional<com.hexadron.launcher.crash.Linkage.Explained> explained =
                    com.hexadron.launcher.crash.Linkage.explain(evidence, mods, rules, language, loader);
            if (explained.isPresent()) {
                linked = true;
                com.hexadron.launcher.mods.ModEntry asker = explained.get().asker();
                String askerId = asker == null ? null
                        : com.hexadron.launcher.mods.ModScan.descriptorOf(asker.path()).modId();
                // "Mod X failed to start" says less than "X needs GeckoLib", and
                // its fix - switch X off - would undo the better one.
                if (askerId != null) {
                    found.removeIf(d -> "modStartup".equals(d.textId()) && askerId.equals(d.values().get("mod")));
                }
                found.add(explained.get().diagnosis());
            }
        }
        if (!loaderStopped && !linked && found.size() < com.hexadron.launcher.crash.CrashAnalyzer.MAX_DIAGNOSES) {
            com.hexadron.launcher.crash.StackAttribution.blame(evidence, mods).ifPresent(blame -> {
                com.hexadron.launcher.mods.ModEntry mod = blame.mod();
                String name = mod.title() == null || mod.title().isBlank() ? mod.fileName() : mod.title();
                String id = com.hexadron.launcher.mods.ModScan.descriptorOf(mod.path()).modId();
                // A rule that already named this mod said more than a stack can.
                boolean named = found.stream().anyMatch(d -> d.values().containsValue(name)
                        || (id != null && d.values().containsValue(id))
                        || d.values().containsValue(mod.fileName()));
                if (named) {
                    return;
                }
                com.hexadron.launcher.crash.CrashRules.Source source = evidence.crashReport().isPresent()
                        ? com.hexadron.launcher.crash.CrashRules.Source.CRASH
                        : com.hexadron.launcher.crash.CrashRules.Source.OUTPUT;
                java.util.List<com.hexadron.launcher.crash.CrashFix> off = java.util.List.of(
                        new com.hexadron.launcher.crash.CrashFix(
                                com.hexadron.launcher.crash.CrashFix.Kind.DISABLE_FILE, mod.fileName()));
                // A Forge 1.12 build for Cleanroom throws from its own loading
                // plugin on plain Forge. "Its code crashed" is true, but "it is
                // for another loader" is the answer.
                if (loader == LoaderType.FORGE
                        && com.hexadron.launcher.mods.LegacyDependencies.isLegacyForge(mod.path())
                        && com.hexadron.launcher.mods.LegacyDependencies.requiresOtherLoader(mod.path())) {
                    java.util.Optional<com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis> other =
                            com.hexadron.launcher.crash.CrashAnalyzer.describe(rules, language, "other-loader-mod", 40,
                                    "wrongLoader", java.util.Map.of("file", mod.fileName()), off, source,
                                    blame.className());
                    if (other.isPresent()) {
                        found.add(other.get());
                        return;
                    }
                }
                com.hexadron.launcher.crash.CrashAnalyzer.describe(rules, language, "stack-trace", 10,
                        com.hexadron.launcher.crash.CrashRules.TEXT_MOD_CODE,
                        java.util.Map.of("mod", name, "error", blame.error()), off, source,
                        blame.className()).ifPresent(found::add);
            });
        }

        if (found.isEmpty() && evidence.exitCode() != 0 && evidence.crashReport().isEmpty()
                && quietMillis >= FROZEN_AFTER_MILLIS) {
            java.util.List<String> output = evidence.lines(com.hexadron.launcher.crash.CrashRules.Source.OUTPUT);
            String last = "";
            for (int i = output.size() - 1; i >= 0 && last.isBlank(); i--) {
                last = output.get(i);
            }
            String seconds = String.valueOf(quietMillis / 1000);
            String lastLine = last;
            java.util.Optional<com.hexadron.launcher.crash.StackAttribution.Blame> stuck =
                    com.hexadron.launcher.crash.StackAttribution.blameThreads(evidence, mods);
            if (stuck.isPresent()) {
                com.hexadron.launcher.mods.ModEntry mod = stuck.get().mod();
                String name = mod.title() == null || mod.title().isBlank() ? mod.fileName() : mod.title();
                com.hexadron.launcher.crash.CrashAnalyzer.describe(rules, language, "frozen-mod", 8,
                        com.hexadron.launcher.crash.CrashRules.TEXT_FROZEN_MOD,
                        java.util.Map.of("seconds", seconds, "mod", name),
                        java.util.List.of(new com.hexadron.launcher.crash.CrashFix(
                                com.hexadron.launcher.crash.CrashFix.Kind.DISABLE_FILE, mod.fileName())),
                        com.hexadron.launcher.crash.CrashRules.Source.THREADS, stuck.get().className())
                        .ifPresent(found::add);
            } else {
                com.hexadron.launcher.crash.CrashAnalyzer.describe(rules, language, "frozen", 5,
                        com.hexadron.launcher.crash.CrashRules.TEXT_FROZEN,
                        java.util.Map.of("seconds", seconds), java.util.List.of(),
                        com.hexadron.launcher.crash.CrashRules.Source.OUTPUT, lastLine).ifPresent(found::add);
            }
        }
        return found.stream()
                .map(d -> com.hexadron.launcher.crash.CrashFixes.withDerived(d, loader))
                .toList();
    }

    /**
     * The mods that are in this profile's folder more than once, switched on:
     * each mod id with its copies. Every loader refuses to start with such a
     * folder, so the question is put before the launch rather than after the crash.
     */
    public java.util.Map<String, java.util.List<com.hexadron.launcher.mods.ModEntry>> duplicateMods(
            Profile profile) {
        return com.hexadron.launcher.crash.CrashFixes.duplicateGroups(modsOf(profile));
    }

    /** Mods switched on in this profile that the rule file says do not work together. */
    public java.util.List<com.hexadron.launcher.crash.Conflicts.Found> modConflicts(Profile profile) {
        return com.hexadron.launcher.crash.Conflicts.find(modsOf(profile), crashRules.current().conflicts());
    }

    /**
     * Switches off these mods, and the mods that need them.
     *
     * @return the files switched off
     */
    public java.util.List<String> switchOffMods(Profile profile, java.util.List<com.hexadron.launcher.mods.ModEntry> off)
            throws IOException {
        java.util.List<com.hexadron.launcher.mods.ModEntry> mods = modsOf(profile);
        com.hexadron.launcher.mods.ModDependents dependents = com.hexadron.launcher.mods.ModDependents.of(mods);
        java.util.LinkedHashMap<String, com.hexadron.launcher.mods.ModEntry> all = new java.util.LinkedHashMap<>();
        for (com.hexadron.launcher.mods.ModEntry mod : off) {
            all.putIfAbsent(mod.key(), mod);
            for (com.hexadron.launcher.mods.ModEntry dependent : dependents.of(mod)) {
                if (dependent.enabled()) {
                    all.putIfAbsent(dependent.key(), dependent);
                }
            }
        }
        java.util.List<String> done = com.hexadron.launcher.crash.CrashFixes.applySwitchOff(profiles.modsDirectory(profile),
                new com.hexadron.launcher.crash.CrashFixes.Prepared(new com.hexadron.launcher.crash.CrashFix(
                        com.hexadron.launcher.crash.CrashFix.Kind.DISABLE_FILE, ""), "",
                        java.util.List.copyOf(all.values()), java.util.List.of(), 0));
        LauncherLog.info("Before launch in %s: switched off %s", profile.name(), String.join(", ", done));
        return done;
    }

    /** The mods switched on in this profile that its loader will not load. */
    public java.util.List<com.hexadron.launcher.mods.ModEntry> wrongLoaderMods(Profile profile) {
        return com.hexadron.launcher.mods.LoaderCheck.wrongLoader(modsOf(profile), profile.loader(),
                profile.minecraftVersion());
    }

    /**
     * Switches off the mods this profile's loader will not load.
     *
     * @return the files switched off
     */
    public java.util.List<String> switchOffWrongLoaderMods(Profile profile) throws IOException {
        java.nio.file.Path modsDir = profiles.modsDirectory(profile);
        java.util.List<String> off = new java.util.ArrayList<>();
        for (com.hexadron.launcher.mods.ModEntry mod : wrongLoaderMods(profile)) {
            com.hexadron.launcher.mods.ModScan.setEnabled(modsDir, mod, false);
            off.add(mod.fileName());
        }
        if (!off.isEmpty()) {
            LauncherLog.info("Mods for another loader in %s: switched off %s", profile.name(), String.join(", ", off));
        }
        return off;
    }

    /**
     * Keeps the newest copy of every mod that is in the folder twice and
     * switches the others off.
     *
     * @return the files switched off
     */
    public java.util.List<String> keepNewestCopies(Profile profile) throws IOException {
        java.util.List<com.hexadron.launcher.mods.ModEntry> mods = modsOf(profile);
        java.util.List<String> off = new java.util.ArrayList<>();
        for (String id : com.hexadron.launcher.crash.CrashFixes.duplicateGroups(mods).keySet()) {
            java.util.Optional<com.hexadron.launcher.crash.CrashFixes.Prepared> prepared =
                    com.hexadron.launcher.crash.CrashFixes.prepare(new com.hexadron.launcher.crash.CrashFix(
                            com.hexadron.launcher.crash.CrashFix.Kind.DISABLE_DUPLICATES, id), profile, mods, -1);
            if (prepared.isPresent()) {
                off.addAll(com.hexadron.launcher.crash.CrashFixes.applySwitchOff(
                        profiles.modsDirectory(profile), prepared.get()));
            }
        }
        if (!off.isEmpty()) {
            LauncherLog.info("Duplicate mods in %s: switched off %s", profile.name(), String.join(", ", off));
        }
        return off;
    }

    /** The fixes of one diagnosis that would change something in this profile. */
    public java.util.List<com.hexadron.launcher.crash.CrashFixes.Prepared> prepareCrashFixes(
            Profile profile, com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis diagnosis) {
        java.util.List<com.hexadron.launcher.mods.ModEntry> mods = modsOf(profile);
        long physical = Profile.physicalMemoryBytes();
        long physicalMegabytes = physical > 0 ? physical / (1024 * 1024) : -1;
        java.util.List<com.hexadron.launcher.crash.CrashFixes.Prepared> prepared = new java.util.ArrayList<>();
        java.util.Set<String> same = new java.util.HashSet<>();
        Path gameDir = profiles.gameDirectory(profile);
        for (com.hexadron.launcher.crash.CrashFix fix : diagnosis.fixes()) {
            java.util.Optional<com.hexadron.launcher.crash.CrashFixes.Prepared> ready =
                    com.hexadron.launcher.crash.CrashFixes.prepare(fix, profile, mods, physicalMegabytes, gameDir);
            if (ready.isPresent() && ready.get().fix().kind() == com.hexadron.launcher.crash.CrashFix.Kind.INSTALL_MOD) {
                com.hexadron.launcher.crash.CrashFixes.Prepared offline = ready.get();
                ready = withinLookupTime(() -> resolveInstall(profile, offline, mods));
            } else if (ready.isPresent()
                    && ready.get().fix().kind() == com.hexadron.launcher.crash.CrashFix.Kind.UPDATE_LOADER) {
                com.hexadron.launcher.crash.CrashFixes.Prepared offline = ready.get();
                ready = withinLookupTime(() -> resolveLoaderUpdate(profile, offline));
            } else if (ready.isPresent()
                    && ready.get().fix().kind() == com.hexadron.launcher.crash.CrashFix.Kind.REPLACE_BUILD) {
                com.hexadron.launcher.crash.CrashFixes.Prepared offline = ready.get();
                ready = withinLookupTime(() -> {
                    try {
                        return replacementFor(profile, offline.targets().get(0)).map(update -> offline.resolved(
                                update.title() + " " + update.next().displayName(), update.next().versionId()));
                    } catch (IOException e) {
                        return java.util.Optional.empty();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return java.util.Optional.empty();
                    }
                });
            } else if (ready.isPresent()
                    && ready.get().fix().kind() == com.hexadron.launcher.crash.CrashFix.Kind.UPDATE_MOD) {
                com.hexadron.launcher.crash.CrashFixes.Prepared offline = ready.get();
                ready = withinLookupTime(() -> {
                    try {
                        return updateFor(profile, offline.fix().value()).map(update -> offline.resolved(
                                update.title() + " " + update.next().displayName(), update.next().versionId()));
                    } catch (IOException e) {
                        return java.util.Optional.empty();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return java.util.Optional.empty();
                    }
                });
            }
            if (ready.isPresent() && same.add(ready.get().sameAs())) {
                prepared.add(ready.get());
            }
        }
        return prepared;
    }

    /**
     * The missing mods of a crash, from all its causes, with the fix found for
     * each: what the crash window's one "install them all" button installs.
     */
    public com.hexadron.launcher.crash.MissingMods missingMods(Profile profile,
            java.util.List<com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis> diagnoses,
            java.util.Map<com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis,
                    java.util.List<com.hexadron.launcher.crash.CrashFixes.Prepared>> fixes) {
        java.util.List<com.hexadron.launcher.mods.ModEntry> mods = modsOf(profile);
        String loaderKey = profile.loader().name().toLowerCase(java.util.Locale.ROOT);
        com.hexadron.launcher.crash.CrashRules rules = crashRules.current();
        return com.hexadron.launcher.crash.MissingMods.of(diagnoses, fixes, id -> {
            String name = com.hexadron.launcher.crash.CrashFixes.displayName(mods, id);
            return name == null || name.equals(id)
                    ? rules.libraryForMod(id, loaderKey).map(com.hexadron.launcher.crash.CrashRules.Library::name)
                            .orElse(id)
                    : name;
        }, profile.minecraftVersion(), profile.loader().displayName());
    }

    /**
     * What to switch off when these mods need something that cannot be had:
     * them, everything that needs them, and the libraries only they needed.
     * Read from the folder as it is now, so installs made a moment ago count.
     */
    public java.util.Optional<com.hexadron.launcher.crash.CrashFixes.SwitchOffPlan> planCrashSwitchOff(
            Profile profile, java.util.Collection<String> modIds) {
        String loaderKey = profile.loader().name().toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> known = new java.util.HashSet<>();
        for (com.hexadron.launcher.crash.CrashRules.Library library : crashRules.current().libraries()) {
            if (library.serves(loaderKey)) {
                known.addAll(library.ids());
            }
        }
        return com.hexadron.launcher.crash.CrashFixes.planSwitchOff(modIds, modsOf(profile), known::contains);
    }

    /** How long the crash window waits for a fix that has to ask the network. */
    static final long FIX_LOOKUP_SECONDS = 8;

    /**
     * Runs a network lookup for a fix, and gives up on it after
     * {@link #FIX_LOOKUP_SECONDS}: the crash window is not held back for a
     * button, and a slow network loses that button, not the window.
     */
    private java.util.Optional<com.hexadron.launcher.crash.CrashFixes.Prepared> withinLookupTime(
            java.util.function.Supplier<java.util.Optional<com.hexadron.launcher.crash.CrashFixes.Prepared>> lookup) {
        java.util.concurrent.CompletableFuture<java.util.Optional<com.hexadron.launcher.crash.CrashFixes.Prepared>> future =
                java.util.concurrent.CompletableFuture.supplyAsync(lookup);
        try {
            return future.get(FIX_LOOKUP_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            LauncherLog.info("Crash fix: the lookup took longer than " + FIX_LOOKUP_SECONDS + " s; not offered");
            return java.util.Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        } catch (java.util.concurrent.ExecutionException e) {
            LauncherLog.info("Crash fix: the lookup failed: " + e.getCause());
            return java.util.Optional.empty();
        }
    }

    /**
     * Finds the mod a missing-mod fix would install: switched on again when a
     * copy under another of its ids is in the folder, otherwise a Modrinth
     * project with a file for this profile's version and loader. Asked here,
     * on the thread that analyses the crash, so a button is shown only when it
     * can work; with no connection there is no button.
     */
    private java.util.Optional<com.hexadron.launcher.crash.CrashFixes.Prepared> resolveInstall(
            Profile profile, com.hexadron.launcher.crash.CrashFixes.Prepared offline,
            java.util.List<com.hexadron.launcher.mods.ModEntry> mods) {
        String id = offline.fix().value();
        String loaderKey = profile.loader().name().toLowerCase(java.util.Locale.ROOT);
        java.util.Optional<com.hexadron.launcher.crash.CrashRules.Library> library =
                crashRules.current().libraryForMod(id, loaderKey);
        java.util.List<String> ids = new java.util.ArrayList<>(java.util.List.of(id));
        library.ifPresent(found -> ids.addAll(found.ids()));
        if (hasEnabledMod(mods, ids)) {
            return java.util.Optional.empty();
        }
        for (String alias : ids) {
            java.util.List<com.hexadron.launcher.mods.ModEntry> copies =
                    com.hexadron.launcher.crash.CrashFixes.byModId(mods, alias);
            if (!copies.isEmpty()) {
                return com.hexadron.launcher.crash.CrashFixes.prepare(new com.hexadron.launcher.crash.CrashFix(
                        com.hexadron.launcher.crash.CrashFix.Kind.ENABLE_FILE,
                        com.hexadron.launcher.crash.CrashFixes.newestCopy(copies).fileName()), profile, mods, -1);
            }
        }
        java.util.LinkedHashSet<String> slugs = new java.util.LinkedHashSet<>();
        library.ifPresent(found -> slugs.add(found.slug()));
        if ((profile.loader() == LoaderType.FABRIC || profile.loader() == LoaderType.QUILT)
                && isFabricApiModule(id.toLowerCase(java.util.Locale.ROOT))) {
            // One module of Fabric API; the whole of it is what gets installed.
            slugs.add("fabric-api");
        }
        slugs.add(id.toLowerCase(java.util.Locale.ROOT));
        slugs.add(id.toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        java.util.Optional<com.hexadron.launcher.crash.CrashFixes.Prepared> found =
                lookUp(modrinth, slugs, profile, offline, "");
        if (found.isPresent() || !curseForge.isAvailable()) {
            return found;
        }
        // Most libraries of Forge 1.12 were never published on Modrinth.
        java.util.LinkedHashSet<String> curseForgeSlugs = new java.util.LinkedHashSet<>();
        library.ifPresent(known -> curseForgeSlugs.add(known.curseForgeSlug()));
        curseForgeSlugs.addAll(slugs);
        return lookUp(curseForge, curseForgeSlugs, profile, offline, CURSEFORGE_REFERENCE);
    }

    /** Marks a missing-mod fix whose project is on CurseForge rather than Modrinth. */
    static final String CURSEFORGE_REFERENCE = "curseforge:";

    /**
     * The first of these slugs that is a project with a file for this profile,
     * on one platform. A connection that fails ends the lookup on that platform.
     */
    private java.util.Optional<com.hexadron.launcher.crash.CrashFixes.Prepared> lookUp(
            ModProvider provider, java.util.Collection<String> slugs, Profile profile,
            com.hexadron.launcher.crash.CrashFixes.Prepared offline, String prefix) {
        for (String slug : slugs) {
            if (!slug.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
                continue;
            }
            try {
                java.util.Optional<ModProvider.ProjectCard> card = provider == curseForge
                        ? curseForge.projectBySlug(slug) : provider.project(slug);
                if (card.isPresent() && provider.resolveFile(com.hexadron.launcher.mods.ContentKind.MOD,
                        card.get().projectId(), profile.minecraftVersion(), profile.loader()).isPresent()) {
                    String title = prefix.isEmpty() ? card.get().title() : card.get().title() + " (CurseForge)";
                    return java.util.Optional.of(offline.resolved(title, prefix + card.get().projectId()));
                }
            } catch (IOException e) {
                LauncherLog.info("Crash fix: could not look up " + slug + " on "
                        + provider.source().displayName() + ": " + e.getMessage());
                return java.util.Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return java.util.Optional.empty();
            } catch (RuntimeException e) {
                LauncherLog.info("Crash fix: could not look up " + slug + " on "
                        + provider.source().displayName() + ": " + e);
            }
        }
        return java.util.Optional.empty();
    }

    /** The newest build of this profile's loader, when it is newer than the one it has. */
    private java.util.Optional<com.hexadron.launcher.crash.CrashFixes.Prepared> resolveLoaderUpdate(
            Profile profile, com.hexadron.launcher.crash.CrashFixes.Prepared offline) {
        try {
            java.util.List<LoaderVersion> versions = loaderVersions(profile.loader(), profile.minecraftVersion());
            if (versions.isEmpty()) {
                return java.util.Optional.empty();
            }
            // Forge marks only its recommended build stable, which is often
            // older than a mod asks for; the newest build is the answer there.
            LoaderVersion target = profile.loader() == LoaderType.FORGE ? versions.get(0)
                    : versions.stream().filter(LoaderVersion::stable).findFirst().orElse(versions.get(0));
            String current = profile.loaderVersion();
            if (current != null && (target.version().equals(current)
                    || com.hexadron.launcher.mods.VersionRanges.compare(target.version(), current) < 0)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(offline.resolved(
                    profile.loader().displayName() + " " + target.version(), target.version()));
        } catch (IOException e) {
            LauncherLog.info("Crash fix: could not list " + profile.loader().displayName() + " builds: " + e.getMessage());
            return java.util.Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        }
    }

    /**
     * Carries out a prepared crash fix and saves the profile.
     *
     * @return what was done, for the log
     */
    public String applyCrashFix(Profile profile, com.hexadron.launcher.crash.CrashFixes.Prepared prepared,
                                Progress progress) throws IOException, InterruptedException {
        com.hexadron.launcher.crash.CrashFix fix = prepared.fix();
        String done;
        switch (fix.kind()) {
            case DISABLE_MOD, DISABLE_FILE, DISABLE_MIXIN_OWNER, DISABLE_DUPLICATES -> {
                java.util.List<String> off = com.hexadron.launcher.crash.CrashFixes.applySwitchOff(
                        profiles.modsDirectory(profile), prepared);
                done = "Crash fix: switched off " + String.join(", ", off);
            }
            case JAVA -> {
                int major = prepared.number();
                JavaLocator.JavaRuntime runtime = javaRuntimes.ensure(major, progress)
                        .orElseThrow(() -> new IOException("Java " + major
                                + " is not installed and could not be downloaded"));
                profile.javaPath(runtime.executable().toString());
                profiles.save();
                done = "Crash fix: this profile now uses " + runtime;
            }
            case AUTOMATIC_JAVA -> {
                profile.javaPath(null);
                profile.javaMajor(null);
                profiles.save();
                done = "Crash fix: the launcher chooses Java for this profile again";
            }
            case RAISE_MEMORY, LOWER_MEMORY -> {
                int before = profile.memoryMegabytes();
                profile.memoryMegabytes(prepared.number());
                profiles.save();
                done = "Crash fix: memory limit " + before + " MB -> " + prepared.number() + " MB";
            }
            case REINSTALL -> {
                installProfile(profile, progress, true);
                done = "Crash fix: game files checked and downloaded again";
            }
            case ENABLE_FILE -> {
                java.util.List<String> on = com.hexadron.launcher.crash.CrashFixes.applySwitchOn(
                        profiles.modsDirectory(profile), prepared);
                done = "Crash fix: switched on " + String.join(", ", on);
            }
            case INSTALL_MOD -> done = installMissingMod(profile, prepared, progress);
            case UPDATE_LOADER -> {
                String before = profile.loaderVersion();
                profile.loaderVersion(prepared.reference());
                profiles.save();
                installProfile(profile, progress);
                done = "Crash fix: " + profile.loader().displayName() + " " + before + " -> " + prepared.reference();
            }
            case RESET_CONFIG -> {
                java.util.List<String> renamed = com.hexadron.launcher.crash.CrashFixes.applyResetConfig(prepared);
                done = "Crash fix: damaged configuration set aside as " + String.join(", ", renamed);
            }
            case UPDATE_MOD -> {
                com.hexadron.launcher.mods.ModUpdates.Update update = updateFor(profile, fix.value())
                        .orElseThrow(() -> new IOException("no newer build of " + prepared.subject() + " was found"));
                done = "Crash fix: " + applyModUpdates(profile, java.util.List.of(update), progress);
            }
            case REPLACE_BUILD -> {
                com.hexadron.launcher.mods.ModUpdates.Update update = replacementFor(profile, prepared.targets().get(0))
                        .orElseThrow(() -> new IOException("no build of " + prepared.subject()
                                + " for this Minecraft version and loader was found"));
                done = "Crash fix: replaced " + prepared.targets().get(0).fileName() + ": "
                        + applyModUpdates(profile, java.util.List.of(update), progress);
            }
            case DISABLE_SHADERS -> {
                java.util.List<String> changed = com.hexadron.launcher.crash.CrashFixes.applyDisableShaders(prepared);
                done = "Crash fix: shaders switched off (" + prepared.subject() + ") in " + String.join(", ", changed);
            }
            case REMOVE_JVM_ARGUMENT -> {
                java.util.List<String> before = profile.extraJvmArguments();
                profile.extraJvmArguments(com.hexadron.launcher.crash.CrashFixes.withoutArguments(before, fix.value()));
                profiles.save();
                done = "Crash fix: removed " + prepared.subject() + " from the Java arguments";
            }
            default -> throw new IllegalStateException(fix.kind().toString());
        }
        // Not written to the log here: every caller hands the line to its
        // progress, which writes it, and the log had each fix twice.
        return done;
    }

    /**
     * Installs the mod a crash said was missing, with what it needs in turn,
     * and keeps it only when a jar in the download carries the mod id that
     * was asked for. A Modrinth project found by that id could be something
     * else of the same name; a jar that is not the missing mod is switched off
     * again, and the fix reports that it did not work.
     */
    private String installMissingMod(Profile profile, com.hexadron.launcher.crash.CrashFixes.Prepared prepared,
                                     Progress progress) throws IOException, InterruptedException {
        String id = prepared.fix().value();
        String loaderKey = profile.loader().name().toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> ids = new java.util.ArrayList<>(java.util.List.of(id));
        crashRules.current().libraryForMod(id, loaderKey).ifPresent(found -> ids.addAll(found.ids()));
        if (hasEnabledMod(modsOf(profile), ids)) {
            // Applied twice - two causes that needed the same library.
            return "Crash fix: " + prepared.subject() + " is installed already";
        }
        boolean fromCurseForge = prepared.reference().startsWith(CURSEFORGE_REFERENCE);
        ModProvider.ProjectCard card = (fromCurseForge
                ? curseForge.project(prepared.reference().substring(CURSEFORGE_REFERENCE.length()))
                : modrinth.project(prepared.reference()))
                .orElseThrow(() -> new IOException(prepared.subject() + " is no longer on "
                        + (fromCurseForge ? "CurseForge" : "Modrinth")));
        ModInstaller.Result result = installMod(profile, card, progress);
        if (!result.manualDownloads().isEmpty() && !hasEnabledMod(modsOf(profile), ids)) {
            // The author allows the file only from the CurseForge website.
            throw new IOException(card.title() + " can be downloaded only from its CurseForge page: "
                    + String.join(", ", result.manualDownloads()));
        }
        if (!hasEnabledMod(modsOf(profile), ids)) {
            java.nio.file.Path modsDir = profiles.modsDirectory(profile);
            for (com.hexadron.launcher.mods.ModEntry entry : modsOf(profile)) {
                boolean fromThis = result.installed().stream()
                        .anyMatch(file -> file.fileName().equals(entry.fileName()));
                if (fromThis && entry.enabled()) {
                    com.hexadron.launcher.mods.ModScan.setEnabled(modsDir, entry, false);
                }
            }
            throw new IOException(card.title() + " was downloaded, but no jar in it is the mod " + id
                    + "; it was switched off again");
        }
        return "Crash fix: installed " + result.installed().stream()
                .map(com.hexadron.launcher.mods.ModFile::fileName).toList();
    }

    /**
     * True when a switched-on jar provides one of these ids: as its own id, an
     * alias, another mod in the same jar, or a jar nested inside it.
     */
    private static boolean hasEnabledMod(java.util.List<com.hexadron.launcher.mods.ModEntry> mods,
                                         java.util.List<String> ids) {
        for (com.hexadron.launcher.mods.ModEntry mod : mods) {
            if (!mod.enabled()) {
                continue;
            }
            java.util.Set<String> provided = com.hexadron.launcher.mods.Requirements.provided(mod.path(),
                    com.hexadron.launcher.mods.ModScan.descriptorOf(mod.path()).modId());
            for (String id : ids) {
                if (provided.contains(id.trim().toLowerCase(java.util.Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A Fabric API module id: {@code fabric-networking-api-v1}, {@code fabric-api-base}. */
    static boolean isFabricApiModule(String id) {
        return id != null && id.startsWith("fabric-") && !id.equals("fabric-loader")
                && (id.matches("fabric-.+-v\\d+") || id.equals("fabric-api-base") || id.endsWith("-api"));
    }

    /** The mods switched on in this profile need these, and nothing switched on provides them. */
    public java.util.List<com.hexadron.launcher.mods.Requirements.Missing> missingRequirements(Profile profile) {
        return com.hexadron.launcher.mods.Requirements.missing(modsOf(profile), profile.loader(),
                profile.minecraftVersion());
    }

    /**
     * Supplies one missing requirement before a launch: switches the jar on
     * that has it, or installs it from Modrinth the way the crash fix does.
     *
     * @return what was done, or null when something done before already supplied it
     * @throws IOException when it could be neither switched on nor installed
     */
    public String supplyRequirement(Profile profile, com.hexadron.launcher.mods.Requirements.Missing missing,
                                    Progress progress) throws IOException, InterruptedException {
        java.util.List<com.hexadron.launcher.mods.ModEntry> mods = modsOf(profile);
        if (hasEnabledMod(mods, java.util.List.of(missing.dependency()))) {
            return null;
        }
        if (missing.switchedOff() != null && java.nio.file.Files.isRegularFile(missing.switchedOff().path())) {
            com.hexadron.launcher.mods.ModScan.setEnabled(profiles.modsDirectory(profile), missing.switchedOff(), true);
            String done = "Before launch: switched on " + com.hexadron.launcher.mods.ModScan.enabledName(
                    missing.switchedOff().fileName()) + " for " + missing.mod().fileName();
            LauncherLog.info(done);
            return done;
        }
        com.hexadron.launcher.crash.CrashFixes.Prepared offline = new com.hexadron.launcher.crash.CrashFixes.Prepared(
                new com.hexadron.launcher.crash.CrashFix(com.hexadron.launcher.crash.CrashFix.Kind.INSTALL_MOD,
                        missing.dependency()), missing.dependency(), java.util.List.of(), java.util.List.of(), 0);
        com.hexadron.launcher.crash.CrashFixes.Prepared found = resolveInstall(profile, offline, mods)
                .orElseThrow(() -> new IOException(missing.dependency() + " was not found on Modrinth"
                        + (curseForge.isAvailable() ? " or CurseForge" : " (CurseForge needs an API key in the settings)")
                        + " for Minecraft " + profile.minecraftVersion() + " and " + profile.loader().displayName()));
        return found.fix().kind() == com.hexadron.launcher.crash.CrashFix.Kind.INSTALL_MOD
                ? installMissingMod(profile, found, progress) : applyCrashFix(profile, found, progress);
    }

    // ---------------------------------------------------------------- updates of mods and packs

    /** How many CurseForge lookups run at once; each is one small request. */
    static final int UPDATE_LOOKUPS = 6;

    /** The kinds whose files are updated: the ones kept in the instance's own folders. */
    static final java.util.List<com.hexadron.launcher.mods.ContentKind> UPDATED_KINDS = java.util.List.of(
            com.hexadron.launcher.mods.ContentKind.MOD, com.hexadron.launcher.mods.ContentKind.RESOURCEPACK,
            com.hexadron.launcher.mods.ContentKind.SHADER);

    /** The folder of one kind in a profile. */
    private Path folderOf(Profile profile, com.hexadron.launcher.mods.ContentKind kind) {
        return kind == com.hexadron.launcher.mods.ContentKind.MOD ? profiles.modsDirectory(profile)
                : profiles.gameDirectory(profile).resolve(kind.instanceFolder());
    }

    /** The switched-on files of one kind that can be asked about: files, not unpacked folders. */
    private java.util.List<com.hexadron.launcher.mods.ModEntry> updatable(Profile profile,
                                                                         com.hexadron.launcher.mods.ContentKind kind) {
        java.util.List<com.hexadron.launcher.mods.ModEntry> entries = kind == com.hexadron.launcher.mods.ContentKind.MOD
                ? modsOf(profile)
                : com.hexadron.launcher.mods.PackScan.of(kind).scan(folderOf(profile, kind));
        return entries.stream().filter(com.hexadron.launcher.mods.ModEntry::enabled)
                .filter(entry -> java.nio.file.Files.isRegularFile(entry.path())).toList();
    }

    /**
     * Newer builds of a profile's mods, resource packs and shader packs, for
     * its Minecraft version and loader: two Modrinth requests per kind for
     * the whole folder, then CurseForge for the files Modrinth does not know
     * (with an API key). Files that belong to an installed modpack are left to
     * the pack.
     */
    public com.hexadron.launcher.mods.ModUpdates.Check checkModUpdates(Profile profile)
            throws IOException, InterruptedException {
        java.util.List<String> notes = new java.util.ArrayList<>();
        java.util.List<com.hexadron.launcher.mods.ModUpdates.Update> updates = new java.util.ArrayList<>();
        int checked = 0;
        int unknown = 0;
        for (com.hexadron.launcher.mods.ContentKind kind : UPDATED_KINDS) {
            if (kind == com.hexadron.launcher.mods.ContentKind.MOD
                    && (profile.loader() == null || !profile.loader().isModded())) {
                continue;
            }
            com.hexadron.launcher.mods.ModUpdates.Check one = checkUpdates(profile, kind, updatable(profile, kind), notes);
            updates.addAll(one.updates());
            checked += one.checked();
            unknown += one.unknown();
        }
        LauncherLog.info("Updates for %s: %d of %d files, %d unknown%s", profile.name(), updates.size(),
                checked, unknown, notes.isEmpty() ? "" : "; " + notes);
        return new com.hexadron.launcher.mods.ModUpdates.Check(updates, checked, unknown, notes);
    }

    /**
     * What each file is on Modrinth or CurseForge, and its newest build for
     * one Minecraft version and this profile's loader.
     *
     * @param currentId the version (Modrinth) or file id (CurseForge) the file is
     * @param newest    the newest build for that Minecraft version, or null when there is none
     * @param missing   projects the newest build requires that the folder does not have
     */
    record Identified(String currentId, com.hexadron.launcher.mods.ModFile newest, java.util.List<String> missing) {
        /** True when the newest build is another file than the one in the folder. */
        boolean differs() {
            return newest != null && !newest.versionId().equals(currentId);
        }
    }

    /**
     * Identifies files by their content: SHA-1 on Modrinth (two requests for
     * all of them), then the CurseForge fingerprint of the rest. Files in the
     * answer are known; the others are on neither platform. Files that belong
     * to an installed modpack are left out.
     */
    private java.util.Map<com.hexadron.launcher.mods.ModEntry, Identified> identify(Profile profile,
            com.hexadron.launcher.mods.ContentKind kind, java.util.List<com.hexadron.launcher.mods.ModEntry> candidates,
            String minecraftVersion, java.util.List<String> notes, java.util.List<com.hexadron.launcher.mods.ModEntry> asked)
            throws IOException, InterruptedException {
        java.util.Set<String> modpacks = new java.util.HashSet<>();
        modpacksIn(profile).forEach(pack -> modpacks.add(pack.id()));
        java.util.Map<String, com.hexadron.launcher.mods.ModEntry> bySha1 = new java.util.LinkedHashMap<>();
        for (com.hexadron.launcher.mods.ModEntry mod : candidates) {
            if (mod.origin() == com.hexadron.launcher.mods.ModOrigin.PACK && mod.packId() != null
                    && modpacks.contains(mod.packId())) {
                continue;
            }
            try {
                bySha1.putIfAbsent(com.hexadron.launcher.util.Hashes.sha1(mod.path()).toLowerCase(java.util.Locale.ROOT), mod);
            } catch (IOException e) {
                notes.add(mod.fileName() + " could not be read: " + e.getMessage());
            }
        }
        asked.addAll(bySha1.values());
        java.util.Map<com.hexadron.launcher.mods.ModEntry, Identified> found = new java.util.LinkedHashMap<>();
        if (bySha1.isEmpty()) {
            return found;
        }
        boolean isMod = kind == com.hexadron.launcher.mods.ContentKind.MOD;
        java.util.List<com.hexadron.launcher.mods.InstalledMod> recorded =
                com.hexadron.launcher.mods.ModLibrary.read(profiles.modsDirectory(profile)).all();

        java.util.Map<String, com.hexadron.launcher.mods.ModrinthProvider.Latest> onModrinth = java.util.Map.of();
        try {
            onModrinth = modrinth.latestByHash(kind, bySha1.keySet(), minecraftVersion, profile.loader());
        } catch (IOException e) {
            notes.add("Modrinth could not be asked: " + e.getMessage());
        }
        java.util.Set<String> haveModrinth = new java.util.HashSet<>();
        onModrinth.values().forEach(latest -> haveModrinth.add(latest.projectId()));
        recorded.stream().filter(mod -> mod.file().source() == ModProvider.Source.MODRINTH)
                .forEach(mod -> haveModrinth.add(mod.file().projectId()));
        for (java.util.Map.Entry<String, com.hexadron.launcher.mods.ModrinthProvider.Latest> entry : onModrinth.entrySet()) {
            com.hexadron.launcher.mods.ModrinthProvider.Latest latest = entry.getValue();
            java.util.List<String> missing = !isMod || latest.newest() == null ? java.util.List.of()
                    : latest.newest().dependencies().stream().filter(id -> !haveModrinth.contains(id)).toList();
            found.put(bySha1.get(entry.getKey()), new Identified(latest.currentVersionId(), latest.newest(), missing));
        }

        java.util.List<com.hexadron.launcher.mods.ModEntry> rest = bySha1.values().stream()
                .filter(mod -> !found.containsKey(mod)).toList();
        if (rest.isEmpty() || !curseForge.isAvailable()) {
            return found;
        }
        java.util.Map<Long, com.hexadron.launcher.mods.ModEntry> byFingerprint = new java.util.LinkedHashMap<>();
        for (com.hexadron.launcher.mods.ModEntry mod : rest) {
            try {
                byFingerprint.putIfAbsent(com.hexadron.launcher.mods.ModUpdates.curseForgeFingerprint(
                        java.nio.file.Files.readAllBytes(mod.path())), mod);
            } catch (IOException | OutOfMemoryError e) {
                notes.add(mod.fileName() + " could not be read for CurseForge");
            }
        }
        try {
            java.util.Map<Long, long[]> matches = curseForge.matchFingerprints(byFingerprint.keySet());
            java.util.Set<String> haveCurseForge = new java.util.HashSet<>();
            matches.values().forEach(match -> haveCurseForge.add(String.valueOf(match[0])));
            recorded.stream().filter(mod -> mod.file().source() == ModProvider.Source.CURSEFORGE)
                    .forEach(mod -> haveCurseForge.add(mod.file().projectId()));
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(UPDATE_LOOKUPS,
                    runnable -> {
                        Thread thread = new Thread(runnable, "hexadron-updates");
                        thread.setDaemon(true);
                        return thread;
                    });
            try {
                java.util.Map<com.hexadron.launcher.mods.ModEntry,
                        java.util.concurrent.Future<java.util.Optional<com.hexadron.launcher.mods.ModFile>>> lookups =
                        new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<Long, long[]> match : matches.entrySet()) {
                    long modId = match.getValue()[0];
                    lookups.put(byFingerprint.get(match.getKey()), pool.submit(() -> curseForge.resolveFile(kind,
                            String.valueOf(modId), minecraftVersion, profile.loader())));
                }
                for (java.util.Map.Entry<Long, long[]> match : matches.entrySet()) {
                    com.hexadron.launcher.mods.ModEntry mod = byFingerprint.get(match.getKey());
                    try {
                        com.hexadron.launcher.mods.ModFile newest = lookups.get(mod).get().orElse(null);
                        java.util.List<String> missing = !isMod || newest == null ? java.util.List.of()
                                : newest.dependencies().stream().filter(id -> !haveCurseForge.contains(id)).toList();
                        found.put(mod, new Identified(String.valueOf(match.getValue()[1]), newest, missing));
                    } catch (java.util.concurrent.ExecutionException e) {
                        notes.add("CurseForge: " + e.getCause().getMessage());
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        } catch (IOException e) {
            notes.add("CurseForge could not be asked: " + e.getMessage());
        }
        return found;
    }

    private com.hexadron.launcher.mods.ModUpdates.Check checkUpdates(Profile profile,
            com.hexadron.launcher.mods.ContentKind kind, java.util.List<com.hexadron.launcher.mods.ModEntry> candidates,
            java.util.List<String> notes) throws IOException, InterruptedException {
        java.util.List<com.hexadron.launcher.mods.ModEntry> asked = new java.util.ArrayList<>();
        java.util.Map<com.hexadron.launcher.mods.ModEntry, Identified> found =
                identify(profile, kind, candidates, profile.minecraftVersion(), notes, asked);
        java.util.List<com.hexadron.launcher.mods.ModUpdates.Update> updates = new java.util.ArrayList<>();
        found.forEach((mod, identified) -> {
            if (!identified.differs() || !identified.newest().isDownloadable()) {
                return;
            }
            // CurseForge numbers files in the order they were uploaded; its newest
            // release can be older than a beta the player chose.
            if (identified.newest().source() == ModProvider.Source.CURSEFORGE
                    && parseLong(identified.newest().versionId()) <= parseLong(identified.currentId())) {
                return;
            }
            updates.add(new com.hexadron.launcher.mods.ModUpdates.Update(kind, mod, titleOf(mod), identified.newest(),
                    identified.missing()));
        });
        updates.sort(java.util.Comparator.comparing(update -> update.title().toLowerCase(java.util.Locale.ROOT)));
        return new com.hexadron.launcher.mods.ModUpdates.Check(updates, asked.size(), asked.size() - found.size(),
                java.util.List.of());
    }

    /**
     * What moving this profile to another Minecraft version would do to each
     * switched-on mod, asked before the move: a build for that version to
     * replace it, the same file (it serves both), no build (it is switched
     * off), or not known (a jar on neither platform, judged by the versions
     * it declares).
     */
    public java.util.List<com.hexadron.launcher.mods.ModUpdates.MoveRow> planVersionMove(Profile profile,
            String minecraftVersion) throws IOException, InterruptedException {
        java.util.List<com.hexadron.launcher.mods.ModEntry> candidates = modsOf(profile).stream()
                .filter(com.hexadron.launcher.mods.ModEntry::enabled).toList();
        java.util.List<String> notes = new java.util.ArrayList<>();
        java.util.List<com.hexadron.launcher.mods.ModEntry> asked = new java.util.ArrayList<>();
        java.util.Map<com.hexadron.launcher.mods.ModEntry, Identified> found = identify(profile,
                com.hexadron.launcher.mods.ContentKind.MOD, candidates, minecraftVersion, notes, asked);
        java.util.Set<String> declaredWrong = new java.util.HashSet<>();
        modsBrokenBy(profile, minecraftVersion).forEach(mod -> declaredWrong.add(mod.fileName()));
        java.util.List<com.hexadron.launcher.mods.ModUpdates.MoveRow> rows = new java.util.ArrayList<>();
        for (com.hexadron.launcher.mods.ModEntry mod : candidates) {
            Identified identified = found.get(mod);
            com.hexadron.launcher.mods.ModUpdates.MoveAction action;
            if (identified == null) {
                action = declaredWrong.contains(mod.fileName()) ? com.hexadron.launcher.mods.ModUpdates.MoveAction.SWITCH_OFF
                        : com.hexadron.launcher.mods.ModUpdates.MoveAction.UNKNOWN;
            } else if (identified.newest() == null) {
                action = com.hexadron.launcher.mods.ModUpdates.MoveAction.SWITCH_OFF;
            } else if (!identified.differs()) {
                action = com.hexadron.launcher.mods.ModUpdates.MoveAction.KEEP;
            } else if (!identified.newest().isDownloadable()) {
                // The author allows the build only from the website: the old file stays, and is named.
                action = declaredWrong.contains(mod.fileName()) ? com.hexadron.launcher.mods.ModUpdates.MoveAction.SWITCH_OFF
                        : com.hexadron.launcher.mods.ModUpdates.MoveAction.UNKNOWN;
            } else {
                action = com.hexadron.launcher.mods.ModUpdates.MoveAction.REPLACE;
            }
            rows.add(new com.hexadron.launcher.mods.ModUpdates.MoveRow(mod, titleOf(mod), action,
                    action == com.hexadron.launcher.mods.ModUpdates.MoveAction.REPLACE ? identified.newest() : null,
                    action == com.hexadron.launcher.mods.ModUpdates.MoveAction.REPLACE ? identified.missing()
                            : java.util.List.of()));
        }
        notes.forEach(note -> LauncherLog.info("Version move plan: " + note));
        return rows;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String titleOf(com.hexadron.launcher.mods.ModEntry mod) {
        return mod.title() == null || mod.title().isBlank() ? mod.fileName() : mod.title();
    }

    /**
     * Installs newer builds. Every file is downloaded before any is replaced,
     * so a failed download leaves the folders as they were. Each old file is
     * set aside in {@code .removed/} of its folder, the launcher's record moves
     * to the new build, the game's settings follow a pack whose file name
     * changed, and the change is written down for {@link #rollBackModUpdates}.
     * Mods the new builds require are installed after.
     *
     * @return what was done, for the log
     */
    public String applyModUpdates(Profile profile, java.util.List<com.hexadron.launcher.mods.ModUpdates.Update> updates,
                                  Progress progress) throws IOException, InterruptedException {
        return applyUpdates(profile, updates, progress, true);
    }

    /**
     * @param journal false for a version move: undoing it file by file would put
     *                builds for the old version into a profile on the new one
     */
    private String applyUpdates(Profile profile, java.util.List<com.hexadron.launcher.mods.ModUpdates.Update> updates,
                                Progress progress, boolean journal) throws IOException, InterruptedException {
        if (updates.isEmpty()) {
            return "Nothing to update";
        }
        Path gameDir = profiles.gameDirectory(profile);
        java.util.List<com.hexadron.launcher.net.DownloadTask> tasks = new java.util.ArrayList<>();
        for (com.hexadron.launcher.mods.ModUpdates.Update update : updates) {
            tasks.add(com.hexadron.launcher.net.DownloadTask.of(update.next().url(),
                    folderOf(profile, update.kind()).resolve(update.next().fileName() + ".hexadron-update"),
                    update.next().sha1(), update.next().size(), update.title()));
        }
        try {
            downloader.run(tasks, progress);
        } catch (IOException | InterruptedException e) {
            for (com.hexadron.launcher.net.DownloadTask task : tasks) {
                java.nio.file.Files.deleteIfExists(task.destination());
            }
            throw e;
        }

        java.util.List<String> done = new java.util.ArrayList<>();
        for (com.hexadron.launcher.mods.ContentKind kind : UPDATED_KINDS) {
            Path folder = folderOf(profile, kind);
            com.hexadron.launcher.mods.ModLibrary library = com.hexadron.launcher.mods.ModLibrary.read(folder, kind.lockFile());
            java.util.List<com.hexadron.launcher.mods.ModUpdates.Change> changes = new java.util.ArrayList<>();
            for (int i = 0; i < updates.size(); i++) {
                com.hexadron.launcher.mods.ModUpdates.Update update = updates.get(i);
                if (update.kind() != kind) {
                    continue;
                }
                String oldName = update.current().fileName();
                String newName = update.next().fileName();
                java.util.Optional<com.hexadron.launcher.mods.InstalledMod> record =
                        com.hexadron.launcher.mods.ModUpdates.recordOf(library, oldName);
                String aside = com.hexadron.launcher.mods.ModUpdates.setAside(folder, oldName);
                if (!newName.equals(oldName) && java.nio.file.Files.exists(folder.resolve(newName))) {
                    // Another file already has the new build's name: the same thing, set aside too.
                    com.hexadron.launcher.mods.ModUpdates.setAside(folder, newName);
                }
                java.nio.file.Files.move(tasks.get(i).destination(), folder.resolve(newName),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                record.ifPresent(old -> library.forget(old.key()));
                com.hexadron.launcher.mods.InstalledMod kept = record.orElse(null);
                library.put(kept == null
                        ? new com.hexadron.launcher.mods.InstalledMod(update.title(), update.next(),
                                com.hexadron.launcher.mods.ModOrigin.MANUAL, null)
                        : new com.hexadron.launcher.mods.InstalledMod(kept.title(), update.next(), kept.origin(),
                                kept.packId(), kept.iconUrl(), kept.pageUrl(), kept.categories(), kept.datapack()));
                com.hexadron.launcher.mods.ModUpdates.renameInSettings(gameDir, kind, oldName, newName);
                changes.add(new com.hexadron.launcher.mods.ModUpdates.Change(oldName, aside, newName,
                        record.map(com.hexadron.launcher.mods.InstalledMod::toJson).orElse(null)));
                done.add(update.title() + " -> " + update.next().displayName());
            }
            if (!changes.isEmpty()) {
                library.write();
                if (journal) {
                    com.hexadron.launcher.mods.ModUpdates.writeJournal(gameDir,
                            com.hexadron.launcher.mods.ModUpdates.journalName(kind), changes);
                }
            }
        }

        java.util.LinkedHashSet<String> requirements = new java.util.LinkedHashSet<>();
        for (com.hexadron.launcher.mods.ModUpdates.Update update : updates) {
            for (String projectId : update.dependencies()) {
                requirements.add(update.source().name() + "|" + projectId);
            }
        }
        for (String requirement : requirements) {
            ModProvider.Source source = ModProvider.Source.valueOf(requirement.substring(0, requirement.indexOf('|')));
            String projectId = requirement.substring(requirement.indexOf('|') + 1);
            ModProvider provider = source == ModProvider.Source.CURSEFORGE ? curseForge : modrinth;
            try {
                java.util.Optional<ModProvider.ProjectCard> card = provider.project(projectId);
                if (card.isPresent() && !com.hexadron.launcher.mods.ModLibrary.read(profiles.modsDirectory(profile))
                        .contains(source, projectId)) {
                    installMod(profile, card.get(), progress);
                    done.add("+ " + card.get().title());
                }
            } catch (IOException e) {
                progress.log("A requirement of the update could not be installed: %s (%s)", projectId, e.getMessage());
            }
        }
        // The caller's progress writes it to the log; written here too, every
        // update stood in the log twice.
        return "Updated: " + String.join(", ", done);
    }

    /** True when the last update of mods, resource packs or shader packs of this profile can be undone. */
    public boolean canRollBackModUpdates(Profile profile) {
        Path gameDir = profiles.gameDirectory(profile);
        return UPDATED_KINDS.stream().anyMatch(kind -> !com.hexadron.launcher.mods.ModUpdates.journal(gameDir,
                com.hexadron.launcher.mods.ModUpdates.journalName(kind)).isEmpty());
    }

    /**
     * Undoes the last update of each kind: the old files come back, the new
     * ones are set aside, the launcher's records and the game's settings are
     * what they were. Mods the update installed as new requirements stay.
     */
    public String rollBackModUpdates(Profile profile) throws IOException {
        Path gameDir = profiles.gameDirectory(profile);
        java.util.List<String> restored = new java.util.ArrayList<>();
        for (com.hexadron.launcher.mods.ContentKind kind : UPDATED_KINDS) {
            Path folder = folderOf(profile, kind);
            java.util.List<com.hexadron.launcher.mods.ModUpdates.Change> undone = com.hexadron.launcher.mods.ModUpdates
                    .rollBack(gameDir, folder, com.hexadron.launcher.mods.ModUpdates.journalName(kind));
            if (undone.isEmpty()) {
                continue;
            }
            com.hexadron.launcher.mods.ModLibrary library = com.hexadron.launcher.mods.ModLibrary.read(folder, kind.lockFile());
            for (com.hexadron.launcher.mods.ModUpdates.Change change : undone) {
                com.hexadron.launcher.mods.ModUpdates.recordOf(library, change.newFile())
                        .ifPresent(record -> library.forget(record.key()));
                if (change.oldRecord() != null) {
                    library.put(com.hexadron.launcher.mods.InstalledMod.fromJson(change.oldRecord(), false, null));
                }
                com.hexadron.launcher.mods.ModUpdates.renameInSettings(gameDir, kind, change.newFile(), change.oldFile());
                restored.add(change.oldFile());
            }
            library.write();
        }
        String summary = "Update undone: " + restored;
        LauncherLog.info(summary);
        return summary;
    }

    /**
     * The newer build of the one mod that provides this id, for a crash fix:
     * a mod that needs another version of another mod.
     */
    /**
     * The build of this jar's project for the profile's Minecraft version and
     * loader, when the platform that knows the file has one that is not this
     * file. It is asked by the file itself (its hash on Modrinth, its
     * fingerprint on CurseForge), so a jar for 1.20.4 finds its 1.20.1 build,
     * and a Fabric jar in a Forge profile finds the Forge one.
     */
    java.util.Optional<com.hexadron.launcher.mods.ModUpdates.Update> replacementFor(Profile profile,
            com.hexadron.launcher.mods.ModEntry jar) throws IOException, InterruptedException {
        if (jar == null || !jar.enabled() || !java.nio.file.Files.isRegularFile(jar.path())) {
            return java.util.Optional.empty();
        }
        String sha1 = com.hexadron.launcher.util.Hashes.sha1(jar.path()).toLowerCase(java.util.Locale.ROOT);
        String projectId = modrinth.projectsByHash(java.util.List.of(sha1)).get(sha1);
        if (projectId != null) {
            // One loader tag at a time, in the order this loader prefers them,
            // and never the file that just failed. Asking for all tags at once
            // returns the newest file under any of them, and platforms list
            // builds under versions they do not run on: ImmediatelyFast's
            // NeoForge 1.20.4 build is filed under 1.20.1 too, and on NeoForge
            // 1.20.1 - the Forge of 1.20.1 renamed - it is exactly the file
            // that does not load. There the Forge build is asked for first.
            java.util.List<String> tags = new java.util.ArrayList<>(
                    profile.loader().platformIds(profile.minecraftVersion()));
            if (profile.loader() == LoaderType.NEOFORGE && tags.contains("forge")) {
                tags.remove("forge");
                tags.add(0, "forge");
            }
            for (String tag : tags) {
                java.util.Optional<com.hexadron.launcher.mods.ModFile> next = modrinth.resolveFile(
                        com.hexadron.launcher.mods.ContentKind.MOD, projectId, profile.minecraftVersion(),
                        profile.loader(), java.util.List.of(tag));
                if (next.isPresent() && next.get().isDownloadable()
                        && !sha1.equalsIgnoreCase(String.valueOf(next.get().sha1()))) {
                    java.util.Set<String> present = new java.util.HashSet<>();
                    java.util.List<String> hashes = new java.util.ArrayList<>();
                    for (com.hexadron.launcher.mods.ModEntry mod : modsOf(profile)) {
                        if (mod.enabled()) {
                            try {
                                hashes.add(com.hexadron.launcher.util.Hashes.sha1(mod.path()).toLowerCase(java.util.Locale.ROOT));
                            } catch (IOException e) {
                                // An unreadable jar provides nothing to count.
                            }
                        }
                    }
                    present.addAll(modrinth.projectsByHash(hashes).values());
                    java.util.List<String> missing = next.get().dependencies().stream()
                            .filter(id -> !present.contains(id)).toList();
                    return java.util.Optional.of(new com.hexadron.launcher.mods.ModUpdates.Update(
                            com.hexadron.launcher.mods.ContentKind.MOD, jar, titleOf(jar), next.get(), missing));
                }
            }
            return java.util.Optional.empty();
        }
        // Not on Modrinth: CurseForge knows it by its fingerprint, when a key is set.
        return checkUpdates(profile, com.hexadron.launcher.mods.ContentKind.MOD, java.util.List.of(jar),
                new java.util.ArrayList<>()).updates().stream().findFirst();
    }

    java.util.Optional<com.hexadron.launcher.mods.ModUpdates.Update> updateFor(Profile profile, String modId)
            throws IOException, InterruptedException {
        java.util.List<com.hexadron.launcher.mods.ModEntry> candidates = new java.util.ArrayList<>();
        for (com.hexadron.launcher.mods.ModEntry mod : modsOf(profile)) {
            if (mod.enabled() && com.hexadron.launcher.mods.Requirements.provided(mod.path(),
                    com.hexadron.launcher.mods.ModScan.descriptorOf(mod.path()).modId())
                    .contains(modId.trim().toLowerCase(java.util.Locale.ROOT))) {
                candidates.add(mod);
            }
        }
        if (candidates.size() != 1) {
            return java.util.Optional.empty();
        }
        return checkUpdates(profile, com.hexadron.launcher.mods.ContentKind.MOD, candidates, new java.util.ArrayList<>())
                .updates().stream().findFirst();
    }

    // ---------------------------------------------------------------- problem-mod search

    /** The search saved in a profile, if one is running. */
    public java.util.Optional<com.hexadron.launcher.bisect.Bisect.State> bisectState(Profile profile) {
        return com.hexadron.launcher.bisect.BisectFiles.load(profiles.gameDirectory(profile));
    }

    /** The dependency graph of a search, read from the jars in the mods folder. */
    public com.hexadron.launcher.bisect.Bisect.Graph bisectGraph(Profile profile,
                                                                 com.hexadron.launcher.bisect.Bisect.State state) {
        return com.hexadron.launcher.bisect.BisectFiles.graph(modsOf(profile), state.original())
                .plus(com.hexadron.launcher.bisect.BisectFiles.learned(profiles.gameDirectory(profile)));
    }

    /**
     * Learns from a crash in a launch of the search. When the game stopped
     * because a mod needs another one that this step switched off, the search
     * did that, not the problem: the two are kept together from now on, the
     * step is set up again, and the crash does not count.
     *
     * <p>A mod whose need names no mod that asked for it (a library a
     * mixin loader or a tweaker needed) is kept on whenever anything is.
     *
     * @return true when something was learned and the same step runs again
     */
    public boolean bisectLearn(Profile profile, com.hexadron.launcher.crash.CrashEvidence evidence) throws IOException {
        return learnFromCrash(profile, evidence).learned();
    }

    /**
     * What a crash of the search taught it.
     *
     * @param learned     true when the step runs again
     * @param cannotStart the mods found to need a mod that is not installed
     */
    private record Learned(boolean learned, java.util.Set<String> cannotStart) {
    }

    private Learned learnFromCrash(Profile profile, com.hexadron.launcher.crash.CrashEvidence evidence)
            throws IOException {
        java.util.Optional<com.hexadron.launcher.bisect.Bisect.State> saved = bisectState(profile);
        if (saved.isEmpty() || saved.get().isDone()) {
            return new Learned(false, java.util.Set.of());
        }
        com.hexadron.launcher.bisect.Bisect.State state = saved.get();
        java.util.List<com.hexadron.launcher.mods.ModEntry> mods = modsOf(profile);
        java.util.Set<String> on = bisectOn(profile, state);
        Path gameDir = profiles.gameDirectory(profile);
        java.util.Set<String> keptOff = com.hexadron.launcher.bisect.BisectFiles.learnedOff(gameDir);
        java.util.Set<String> cannotStart = new java.util.LinkedHashSet<>();
        java.util.Map<String, String> byId = new java.util.HashMap<>();
        for (com.hexadron.launcher.mods.ModEntry mod : mods) {
            String name = com.hexadron.launcher.mods.ModScan.enabledName(mod.fileName());
            if (state.original().contains(name)) {
                for (String id : com.hexadron.launcher.mods.LegacyDependencies.provides(mod.path(),
                        com.hexadron.launcher.mods.ModScan.descriptorOf(mod.path()).modId())) {
                    byId.putIfAbsent(id, name);
                }
            }
        }
        java.util.Map<String, java.util.Set<String>> more = new java.util.LinkedHashMap<>();
        for (com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis diagnosis : analyzeCrash(profile, evidence, "en", 0)) {
            String need = null;
            String asker = null;
            if ("missingDep".equals(diagnosis.textId())) {
                need = byId.get(lower(diagnosis.values().get("dep")));
                asker = byId.get(lower(diagnosis.values().get("mod")));
                // What it needs is not in the folder at all (or is a mod the
                // search keeps off for that reason): this mod stops every
                // launch it is part of, whatever the step. It stays off.
                if (asker != null && on.contains(asker) && (need == null || keptOff.contains(need))) {
                    cannotStart.add(asker);
                    continue;
                }
            } else if ("library-off".equals(diagnosis.ruleId())) {
                need = diagnosis.fixes().stream()
                        .filter(fix -> fix.kind() == com.hexadron.launcher.crash.CrashFix.Kind.ENABLE_FILE)
                        .map(fix -> com.hexadron.launcher.mods.ModScan.enabledName(fix.value()))
                        .findFirst().orElse(null);
                asker = diagnosis.values().get("askerFile");
            }
            if (need == null || !state.original().contains(need) || on.contains(need)) {
                continue;
            }
            if (asker != null && state.original().contains(asker)) {
                more.computeIfAbsent(asker, key -> new java.util.LinkedHashSet<>()).add(need);
            } else {
                for (String file : state.original()) {
                    more.computeIfAbsent(file, key -> new java.util.LinkedHashSet<>()).add(need);
                }
            }
        }
        boolean needs = !more.isEmpty() && com.hexadron.launcher.bisect.BisectFiles.learn(gameDir, more);
        boolean broken = !cannotStart.isEmpty() && com.hexadron.launcher.bisect.BisectFiles.learnOff(gameDir, cannotStart);
        if (!needs && !broken) {
            return new Learned(false, java.util.Set.of());
        }
        applyBisect(profile, state);
        if (needs) {
            LauncherLog.info("Problem-mod search: learned that %s need %s; step %d runs again",
                    more.size() > 3 ? more.size() + " mods" : more.keySet(),
                    more.values().stream().flatMap(java.util.Set::stream).distinct().toList(), state.step());
        }
        if (broken) {
            LauncherLog.info("Problem-mod search: learned that %s cannot start (a mod they need is not installed);"
                    + " kept off, step %d runs again", cannotStart, state.step());
        }
        return new Learned(true, broken ? cannotStart : java.util.Set.of());
    }

    /** How a launch of the search ended. */
    public enum BisectVerdict {
        /** The problem the search looks for. */
        PROBLEM,
        /** A crash the step itself caused; the step runs again. */
        LEARNED,
        /** Another crash: the player is asked whether the problem occurred. */
        OTHER
    }

    /**
     * @param verdict what the launch counts as
     * @param detail  for {@code OTHER}, the crash it ended with, in a few words;
     *                for {@code LEARNED}, the mods found unable to start, or empty
     */
    public record BisectOutcome(BisectVerdict verdict, String detail) {
    }

    /**
     * Decides what a crash in a launch of the search counts as.
     *
     * <p>A search started after a crash looks for that crash: the same one is
     * the problem, another one is not an answer. One started without a crash
     * cannot compare, so a crash counts - except one where the loader refused
     * the mod set (a mod missing, installed twice or for another loader),
     * which the halving itself so often causes.
     *
     * @param language the language the detail is given in
     */
    public BisectOutcome bisectJudge(Profile profile, com.hexadron.launcher.crash.CrashEvidence evidence,
                                     String language) {
        java.util.List<com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis> found;
        try {
            found = analyzeCrash(profile, evidence, language, 0);
        } catch (RuntimeException e) {
            LauncherLog.error("Problem-mod search: could not analyse the crash", e);
            found = java.util.List.of();
        }
        Path gameDir = profiles.gameDirectory(profile);
        java.util.Optional<com.hexadron.launcher.crash.CrashSignature> sought =
                com.hexadron.launcher.bisect.BisectFiles.problem(gameDir);
        if (sought.isPresent() && sought.get().matches(found, evidence)) {
            return new BisectOutcome(BisectVerdict.PROBLEM, "");
        }
        try {
            Learned learned = learnFromCrash(profile, evidence);
            if (learned.learned()) {
                return new BisectOutcome(BisectVerdict.LEARNED, learned.cannotStart().stream()
                        .map(file -> modFileTitle(profile, file)).collect(java.util.stream.Collectors.joining(", ")));
            }
        } catch (IOException | RuntimeException e) {
            LauncherLog.error("Problem-mod search: could not learn from the crash", e);
        }
        String detail = found.isEmpty()
                ? com.hexadron.launcher.crash.CrashSignature.of(found, evidence).label()
                : found.get(0).title() + ": " + found.get(0).cause();
        if (detail.isBlank()) {
            detail = "exit code " + evidence.exitCode();
        }
        if (sought.isPresent()) {
            LauncherLog.info("Problem-mod search: another crash than the one searched for (%s): %s",
                    sought.get().label(), com.hexadron.launcher.crash.CrashSignature.of(found, evidence).key());
            return new BisectOutcome(BisectVerdict.OTHER, detail);
        }
        if (com.hexadron.launcher.crash.CrashAnalyzer.loaderStopped(found, crashRules.current())
                || evidence.loaderStoppedEarly()) {
            LauncherLog.info("Problem-mod search: the loader refused this mod set: %s",
                    com.hexadron.launcher.crash.CrashSignature.of(found, evidence).key());
            return new BisectOutcome(BisectVerdict.OTHER, detail);
        }
        return new BisectOutcome(BisectVerdict.PROBLEM, "");
    }

    /** The last crash explained in each profile, for a search started after it. */
    private final java.util.Map<String, RememberedCrash> lastCrash = new java.util.concurrent.ConcurrentHashMap<>();

    private record RememberedCrash(com.hexadron.launcher.crash.CrashSignature signature, long at) {
    }

    /** How long after a crash a search started in the same profile is taken to be about it. */
    static final long CRASH_REMEMBERED_MILLIS = 30 * 60 * 1000L;

    /**
     * Remembers what a crash was, so that a problem-mod search started after
     * it looks for that crash and no other.
     */
    public void rememberCrash(Profile profile, com.hexadron.launcher.crash.CrashEvidence evidence,
                              java.util.List<com.hexadron.launcher.crash.CrashAnalyzer.Diagnosis> diagnoses) {
        com.hexadron.launcher.crash.CrashSignature signature =
                com.hexadron.launcher.crash.CrashSignature.of(diagnoses, evidence);
        if (signature.known()) {
            lastCrash.put(profile.id(), new RememberedCrash(signature, System.currentTimeMillis()));
        } else {
            lastCrash.remove(profile.id());
        }
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Starts a search: records the mods that are on, switches on the first half
     * and saves the state.
     *
     * @throws IllegalArgumentException when fewer than two mods are on
     */
    public com.hexadron.launcher.bisect.Bisect.State bisectStart(Profile profile) throws IOException {
        Path modsDir = profiles.modsDirectory(profile);
        com.hexadron.launcher.bisect.Bisect.State state = com.hexadron.launcher.bisect.Bisect.start(
                com.hexadron.launcher.bisect.BisectFiles.enabledJars(modsDir));
        // What an earlier search learned belongs to that search.
        com.hexadron.launcher.bisect.BisectFiles.delete(profiles.gameDirectory(profile));
        // Saved before anything is renamed: if the launcher stops between the
        // two, the saved list is what puts the folder back.
        com.hexadron.launcher.bisect.BisectFiles.save(profiles.gameDirectory(profile), state, profile.id());
        RememberedCrash crash = lastCrash.get(profile.id());
        boolean afterCrash = crash != null && System.currentTimeMillis() - crash.at() < CRASH_REMEMBERED_MILLIS;
        if (afterCrash) {
            com.hexadron.launcher.bisect.BisectFiles.saveProblem(profiles.gameDirectory(profile), crash.signature());
        }
        applyBisect(profile, state);
        LauncherLog.info("Problem-mod search started in %s with %d mods%s", profile.name(), state.original().size(),
                afterCrash ? ", looking for: " + crash.signature().key() : "");
        return state;
    }

    /** Records whether the problem occurred and moves the search on. */
    public com.hexadron.launcher.bisect.Bisect.State bisectAnswer(Profile profile, boolean problem)
            throws IOException {
        com.hexadron.launcher.bisect.Bisect.State state = bisectState(profile)
                .orElseThrow(() -> new IOException("no search is running in this profile"));
        com.hexadron.launcher.bisect.Bisect.State next = com.hexadron.launcher.bisect.Bisect.next(state, problem,
                bisectGraph(profile, state));
        com.hexadron.launcher.bisect.BisectFiles.save(profiles.gameDirectory(profile), next, profile.id());
        LauncherLog.info("Problem-mod search: step %d %s", state.step(), problem ? "showed the problem" : "was clean");
        if (next.isDone()) {
            LauncherLog.info("Problem-mod search result: %s", String.join(" + ", next.result()));
        } else {
            applyBisect(profile, next);
        }
        return next;
    }

    private void applyBisect(Profile profile, com.hexadron.launcher.bisect.Bisect.State state) throws IOException {
        java.util.Set<String> on = bisectOn(profile, state);
        java.util.List<String> missing = com.hexadron.launcher.bisect.BisectFiles.apply(
                profiles.modsDirectory(profile), state.original(), on);
        java.util.Set<String> off = com.hexadron.launcher.bisect.BisectFiles.learnedOff(profiles.gameDirectory(profile));
        LauncherLog.info("Problem-mod search: step %d, %d of %d mods on%s%s", state.step(), on.size(),
                state.original().size(), missing.isEmpty() ? "" : ", missing: " + missing,
                off.isEmpty() ? "" : ", kept off (cannot start): " + off);
    }

    /**
     * The mods a launch of the search has on: the step's set, less the mods
     * the search found cannot start at all.
     */
    public java.util.Set<String> bisectOn(Profile profile, com.hexadron.launcher.bisect.Bisect.State state) {
        java.util.Set<String> on = new java.util.LinkedHashSet<>(
                com.hexadron.launcher.bisect.Bisect.enabledFor(state, bisectGraph(profile, state)));
        if (!state.isDone()) {
            on.removeAll(com.hexadron.launcher.bisect.BisectFiles.learnedOff(profiles.gameDirectory(profile)));
        }
        return on;
    }

    /**
     * Ends a search: every mod that was on is switched on again, except the
     * ones the player chose to keep off, and the saved state is removed.
     */
    public void bisectFinish(Profile profile, java.util.Collection<String> keepOff) throws IOException {
        java.util.Optional<com.hexadron.launcher.bisect.Bisect.State> state = bisectState(profile);
        if (state.isPresent()) {
            java.util.Set<String> on = new java.util.LinkedHashSet<>(state.get().original());
            on.removeAll(keepOff);
            com.hexadron.launcher.bisect.BisectFiles.apply(profiles.modsDirectory(profile),
                    state.get().original(), on);
        }
        com.hexadron.launcher.bisect.BisectFiles.delete(profiles.gameDirectory(profile));
        LauncherLog.info("Problem-mod search ended in %s%s", profile.name(),
                keepOff.isEmpty() ? ", all mods restored" : ", kept off: " + keepOff);
    }

    /** The name a mod file is shown by: the title in its jar, or the file name. */
    public String modFileTitle(Profile profile, String fileName) {
        for (com.hexadron.launcher.mods.ModEntry entry : modsOf(profile)) {
            if (com.hexadron.launcher.mods.ModScan.enabledName(entry.fileName()).equals(fileName)) {
                return entry.title() == null || entry.title().isBlank() ? fileName : entry.title();
            }
        }
        return fileName;
    }

    // ---------------------------------------------------------------- accessors

    public GameDirs dirs() {
        return dirs;
    }

    public LauncherSettings settings() {
        return settings;
    }

    public ProfileStore profiles() {
        return profiles;
    }

    public AccountStore accounts() {
        return accounts;
    }

    /** Where credentials are being kept on this machine. Shown in the interface. */
    public SecretStore secretStore() {
        return secretStore;
    }

    public JavaLocator javaLocator() {
        return javaLocator;
    }

    /** Java runtime policy: detection, and downloading one when there is none. */
    public JavaRuntimes javaRuntimes() {
        return javaRuntimes;
    }

    public VersionInstaller versionInstaller() {
        return versionInstaller;
    }

    public List<ModProvider> modProviders() {
        return List.of(modrinth, curseForge);
    }

    /** The CurseForge provider, so the interface can show whether it has a key. */
    public CurseForgeProvider curseForge() {
        return curseForge;
    }

    /**
     * Stores a CurseForge key and puts it to use at once.
     *
     * <p>No restart: the provider and the HTTP layer both read the current key,
     * so the next search already uses it. An empty value switches CurseForge back
     * off, which is the honest state for "no key" and better than a platform that
     * is listed and fails every request.
     */
    public void curseForgeApiKey(String key) throws IOException {
        // A key chosen now replaces any old plain-text one still waiting to be
        // moved, so the warm-up cannot put the old key back over this one.
        settings.takePlaintextCurseForgeKey();
        settings.curseForgeApiKey(key);
        String value = settings.curseForgeApiKey();
        if (value.isEmpty()) {
            secretStore.delete(CURSEFORGE_KEY);
        } else {
            secretStore.store(CURSEFORGE_KEY, value);
        }
        settings.curseForgeKeyStored(!value.isEmpty());
        settings.save();
        curseForge.apiKey(value);
    }

    /** Where the CurseForge key lives in the credential store. */
    public static final String CURSEFORGE_KEY = "curseforge:apiKey";

    /**
     * Moves a plain-text key out of launcher.json, or reads the stored one.
     *
     * <p>Off the start-up path: reading the credential store can mean two
     * PowerShell launches on Windows. Until it finishes, CurseForge runs on
     * the environment or build key, if there is one.
     */
    void loadCurseForgeKey() {
        try {
            String plaintext = settings.plaintextCurseForgeKey();
            if (plaintext != null) {
                // Stored first, dropped from launcher.json second: if the store
                // fails the key stays where it was instead of being lost.
                secretStore.store(CURSEFORGE_KEY, plaintext);
                settings.takePlaintextCurseForgeKey();
                settings.curseForgeKeyStored(true);
                settings.save();
                LauncherLog.info("CurseForge key moved from launcher.json to the credential store");
                return;
            }
            if (settings.curseForgeKeyStored()) {
                secretStore.load(CURSEFORGE_KEY).filter(key -> !key.isBlank()).ifPresent(key -> {
                    settings.curseForgeApiKey(key);
                    curseForge.apiKey(key);
                });
            }
        } catch (IOException | RuntimeException e) {
            LauncherLog.warn("Could not read the CurseForge key from the credential store: %s",
                    com.hexadron.launcher.util.Redactor.scrub(String.valueOf(e.getMessage())));
        }
    }

    // ---------------------------------------------------------------- versions

    public VersionManifest minecraftVersions() throws IOException, InterruptedException {
        return VersionManifest.fetch(dirs);
    }

    /**
     * Which Minecraft versions a loader has builds for.
     *
     * <p>Used to keep the version picker honest: offering Minecraft 1.0 with
     * Fabric selected is offering a combination that cannot be installed, and
     * the user only finds out after choosing it.
     */
    public LoaderInstaller.SupportedVersions loaderSupport(LoaderType loader)
            throws IOException, InterruptedException {
        if (loader == null || loader == LoaderType.VANILLA) {
            return LoaderInstaller.SupportedVersions.unknown();
        }
        return Loaders.installerFor(loader).supportedMinecraftVersions();
    }

    public List<LoaderVersion> loaderVersions(LoaderType loader, String minecraftVersion)
            throws IOException, InterruptedException {
        if (loader == LoaderType.VANILLA) {
            return List.of();
        }
        return Loaders.installerFor(loader).availableVersions(minecraftVersion);
    }

    // ---------------------------------------------------------------- install

    /**
     * Installs everything a profile needs: the loader manifest if any, then the
     * client jar, libraries, natives and assets.
     *
     * <p>Safe to re-run: it verifies and repairs rather than redownloading.
     *
     * <p>And safe to re-run with no internet, which is the harder half. Every
     * step here already does nothing when what it would fetch is on disk with
     * the right hash - except the loader lookup, which used to ask
     * {@code meta.fabricmc.net} which builds exist before every single launch,
     * only to re-approve a version that had been pinned and installed days
     * earlier. That turned "start the instance I installed yesterday" into a
     * request that fails four times and then refuses to start the game. See
     * {@link #installedVersionId}.
     */
    public VersionJson installProfile(Profile profile, Progress progress)
            throws IOException, InterruptedException {
        return installProfile(profile, progress, false);
    }

    /**
     * @param verifyEverything read and hash every file rather than trusting the
     *                         record of what was checked before. What the
     *                         Install / repair button passes: somebody pressing
     *                         it is saying they do not trust what is on disk, and
     *                         the one job it has is to find out. The
     *                         {@code verifyEveryLaunch} setting adds the same
     *                         thing to every launch
     */
    public VersionJson installProfile(Profile profile, Progress progress, boolean verifyEverything)
            throws IOException, InterruptedException {
        boolean full = verifyEverything || settings.verifyEveryLaunch();
        // The ledger stays attached either way, and only stops being believed.
        // Writing to it costs nothing on a pass that is reading every file
        // anyway, and it means turning the setting back off does not buy one
        // more slow launch on top of the ones it already cost.
        downloader.trustLedger(!full);
        try {
            return install(profile, progress, full);
        } catch (Http.OfflineException e) {
            // Something genuinely had to be fetched and could not be. Said in
            // terms of what the user can do about it, because "request to
            // https://... failed after 4 attempts" reads as a fault in the
            // launcher rather than as "you are offline, and this instance is not
            // finished installing".
            throw new IOException("'" + profile.name() + "' is not fully installed yet, and "
                    + e.getMessage() + ".\n\nIt needs that host once, to finish installing;"
                    + " after that it starts without a connection. If other sites work on this"
                    + " computer, try opening https://" + e.host() + " in a browser - if that"
                    + " also fails, the block is on the network rather than in the launcher.", e);
        } finally {
            // Written even when the install failed: the files that were checked
            // before it failed were still checked, and the next attempt should
            // not repeat that part.
            verified.save();
            downloader.trustLedger(true);
        }
    }

    private VersionJson install(Profile profile, Progress progress, boolean verifyEverything)
            throws IOException, InterruptedException {

        if (profile.minecraftVersion() == null || profile.minecraftVersion().isBlank()) {
            throw new IOException("profile '" + profile.name() + "' has no Minecraft version set");
        }

        Path gameDir = profiles.gameDirectory(profile);
        String versionId = profile.minecraftVersion();

        String installed = installedVersionId(profile);
        if (installed != null) {
            versionId = installed;
            progress.log("Using the installed %s", versionId);
        } else if (profile.loader() != LoaderType.VANILLA) {
            LoaderInstaller installer = Loaders.installerFor(profile.loader());

            LoaderVersion loaderVersion;
            if (profile.loaderVersion() != null && !profile.loaderVersion().isBlank()) {
                String wanted = profile.loaderVersion();
                loaderVersion = installer.availableVersions(profile.minecraftVersion()).stream()
                        .filter(candidate -> candidate.version().equals(wanted))
                        .findFirst()
                        .orElseThrow(() -> new IOException(profile.loader().displayName() + " "
                                + wanted + " is not available for Minecraft " + profile.minecraftVersion()));
            } else {
                loaderVersion = installer.recommendedVersion(profile.minecraftVersion());
                if (loaderVersion == null) {
                    throw new LoaderInstaller.UnsupportedVersionException(
                            profile.loader(), profile.minecraftVersion());
                }
                profile.loaderVersion(loaderVersion.version());
            }

            versionId = installer.install(profile.minecraftVersion(), loaderVersion,
                    versionInstaller, progress);
        }

        if (verifyEverything) {
            // The natives directory is unpacked from jars rather than downloaded,
            // so nothing about it is on record in the ledger. Repair has to say
            // so separately.
            com.hexadron.launcher.install.NativesExtractor.forget(dirs.natives(versionId));
        }

        VersionJson version = versionInstaller.install(versionId, gameDir, progress);
        profile.versionId(versionId);
        // Noted here because this is the first point at which it is known for
        // certain, and because the profile has to carry it for its own removal
        // to be able to tell whether the runtime is still wanted.
        profile.javaMajor(version.requiredJavaMajor());
        profiles.save();
        return version;
    }

    /**
     * The manifest this profile can launch from without asking anybody, or null
     * when the loader still has to be installed.
     *
     * <p>Rests on one guarantee, which is what makes it safe to trust a recorded
     * id rather than re-derive it: {@link Profile} clears {@code versionId} in
     * the setters for the Minecraft version, the loader and the loader version.
     * So a non-null one is not "some id from the past" - it is the id of a
     * manifest installed for exactly the three values the profile holds now.
     * Change any of them and this returns null on the next launch, and the
     * loader is fetched again.
     *
     * <p>Vanilla profiles pass through here too: their id is the Minecraft
     * version, and having it and its chain on disk says the same thing.
     *
     * <p>What this deliberately does not check is the client jar, the libraries
     * or the assets. Those are verified by hash a few lines later, in
     * {@link #install}, which repairs what is missing and downloads nothing when
     * nothing is. The question here is narrower: whether a network round trip is
     * needed to find out which manifest to launch.
     */
    private String installedVersionId(Profile profile) {
        String recorded = profile.versionId();
        if (recorded == null || !versionInstaller.resolver().isFullyInstalled(recorded)) {
            return null;
        }
        return recorded;
    }

    /** Installs a mod pack into a profile's mods directory. */
    public ModInstaller.Result installPack(Profile profile, ModPack pack, Progress progress)
            throws IOException, InterruptedException {

        if (profile.loader() == LoaderType.VANILLA) {
            throw new IOException("mods need a loader - set this profile to Fabric, Quilt, "
                    + "Forge or NeoForge first");
        }
        ModInstaller.Result result = modInstaller.installPack(pack, profile.minecraftVersion(),
                profile.loader(), profiles.modsDirectory(profile), progress);
        settleModCount(profile, result, progress);
        return result;
    }

    /**
     * Makes the number on the title screen mean what a player reads it to mean.
     *
     * <p>Only when Mod Menu was one of the files, because Mod Menu is what draws
     * that number, and only for keys the config does not already have - see
     * {@link com.hexadron.launcher.mods.ModMenuCount}.
     */
    private void settleModCount(Profile profile, ModInstaller.Result result, Progress progress) {
        if (com.hexadron.launcher.mods.ModMenuCount.isPresentAmong(result.installed())) {
            com.hexadron.launcher.mods.ModMenuCount.applyTo(
                    profiles.gameDirectory(profile), progress);
        }
    }

    /** What the launcher installed into this profile, and why. */
    public com.hexadron.launcher.mods.ModLibrary installedMods(Profile profile) {
        return com.hexadron.launcher.mods.ModLibrary
                .read(profiles.modsDirectory(profile))
                .pruneMissingFiles();
    }

    /**
     * The line drawings that go beside the category names.
     *
     * <p>Read from the data folder, so the filter draws itself with no
     * connection. {@link #refreshCategoryArt} is what fills it in, and it is
     * called once, in the background, when the mod browser is first opened.
     */
    public com.hexadron.launcher.mods.CategoryArt categoryArt() {
        if (categoryArt == null) {
            categoryArt = com.hexadron.launcher.mods.CategoryArt.read(dirs.cache());
        }
        return categoryArt;
    }

    /**
     * Asks Modrinth for the category drawings, at most once a month.
     *
     * @return true when something changed and the interface should redraw
     */
    public boolean refreshCategoryArt() throws IOException, InterruptedException {
        com.hexadron.launcher.mods.CategoryArt art = categoryArt();
        return art.isStale() && art.refresh(modrinth);
    }

    private com.hexadron.launcher.mods.CategoryArt categoryArt;

    /**
     * Fetches a project's logo, for a profile that is about to wear it.
     *
     * <p>Through {@link com.hexadron.launcher.net.Http} rather than by handing a
     * URL to the interface, for the same reason every other picture in this
     * launcher is: a user behind a proxy has one configured here and nowhere
     * else. HTTPS is required, because this is bytes off the internet that end
     * up in the data folder.
     *
     * <p>Nothing is decoded here. What a picture is and where it may be written
     * belongs to the interface layer, which is the half of this launcher that
     * knows how to read one.
     *
     * @param url the platform's logo address
     * @return the bytes, never null
     */
    public byte[] fetchIcon(String url) throws IOException, InterruptedException {
        return com.hexadron.launcher.net.Http.getBytes(
                com.hexadron.launcher.net.Http.requireHttps(url));
    }

    /**
     * Fills in what the launcher never recorded about the mods it installed.
     *
     * <p>Each version of the lock file learned to keep a little more - the
     * project's logo, then its page, then its categories - and every entry
     * written before it learned is missing that field for ever. A mod installed
     * last month therefore sits with a lettered tile, a dead link and an empty
     * category line, while the one installed today has all three.
     *
     * <p>One request fixes the lot: these are the launcher's own downloads and it
     * knows exactly which projects they are, so it can ask about all of them at
     * once. Nothing already recorded is overwritten - what is on disk was true
     * when it was written and is not this method's to second-guess.
     *
     * <p>Modrinth only, because a bulk lookup is what makes this one request
     * rather than one per mod. A CurseForge entry keeps what it has until it is
     * installed again.
     *
     * @return true when anything was filled in and the interface should redraw
     */
    public boolean describeInstalledMods(Profile profile) throws IOException, InterruptedException {
        com.hexadron.launcher.mods.ModLibrary library =
                com.hexadron.launcher.mods.ModLibrary.read(profiles.modsDirectory(profile));

        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (com.hexadron.launcher.mods.InstalledMod mod : library.all()) {
            boolean complete = !mod.categories().isEmpty()
                    && mod.iconUrl() != null && mod.pageUrl() != null;
            if (!complete && mod.file().source() == ModProvider.Source.MODRINTH
                    && !unknown.contains(mod.file().projectId())) {
                unknown.add(mod.file().projectId());
            }
        }
        if (unknown.isEmpty()) {
            return false;
        }

        // In batches, because a folder can hold hundreds and a URL cannot.
        java.util.Map<String, ModProvider.ProjectCard> published = new java.util.HashMap<>();
        for (int from = 0; from < unknown.size(); from += 100) {
            java.util.List<String> batch =
                    unknown.subList(from, Math.min(from + 100, unknown.size()));
            for (ModProvider.ProjectCard card : modrinth.projects(batch)) {
                published.put(card.projectId(), card);
            }
        }

        boolean changed = false;
        for (com.hexadron.launcher.mods.InstalledMod mod : library.all()) {
            ModProvider.ProjectCard card = published.get(mod.file().projectId());
            if (card == null) {
                continue;
            }
            String icon = mod.iconUrl() != null ? mod.iconUrl() : card.iconUrl();
            String page = mod.pageUrl() != null ? mod.pageUrl() : card.pageUrl();
            java.util.List<com.hexadron.launcher.mods.ModCategory> categories =
                    mod.categories().isEmpty() ? card.categories() : mod.categories();
            if (java.util.Objects.equals(icon, mod.iconUrl())
                    && java.util.Objects.equals(page, mod.pageUrl())
                    && categories.equals(mod.categories())) {
                continue;
            }
            library.put(new com.hexadron.launcher.mods.InstalledMod(mod.title(), mod.file(),
                    mod.origin(), mod.packId(), icon, page, categories));
            changed = true;
        }
        if (changed) {
            library.write();
        }
        return changed;
    }

    /** Searches the mod platforms for builds matching this profile. */
    public ModProvider.SearchPage searchMods(
            Profile profile, String query, com.hexadron.launcher.mods.ModSort sort,
            java.util.List<com.hexadron.launcher.mods.ModCategory> categories,
            ModProvider.Source only, int limitPerProvider, int offset)
            throws IOException, InterruptedException {

        return searchContent(com.hexadron.launcher.mods.ContentKind.MOD, profile, query, sort,
                categories, false, only, limitPerProvider, offset);
    }

    /**
     * Searches the platforms for one kind of thing.
     *
     * <p>The loader is required for the kinds that need one and not for the rest,
     * which is the whole reason the check is here rather than in every caller: a
     * data pack is loaded by vanilla Minecraft, and a modpack brings its own
     * loader with it, so refusing either on a profile with no loader would be
     * refusing something that works.
     */
    public ModProvider.SearchPage searchContent(
            com.hexadron.launcher.mods.ContentKind kind,
            Profile profile, String query, com.hexadron.launcher.mods.ModSort sort,
            java.util.List<com.hexadron.launcher.mods.ModCategory> categories,
            boolean onlyForProfile,
            ModProvider.Source only, int limitPerProvider, int offset)
            throws IOException, InterruptedException {

        if (kind.needsLoader()) {
            requireModdedLoader(profile);
        }
        return modInstaller.search(kind, query, profile.minecraftVersion(), profile.loader(),
                sort, categories, onlyForProfile, limitPerProvider, offset, only);
    }

    /** Installs one mod, with its required dependencies, into a profile. */
    public ModInstaller.Result installMod(Profile profile, ModProvider.ProjectCard chosen,
                                          Progress progress)
            throws IOException, InterruptedException {

        requireModdedLoader(profile);
        ModInstaller.Result result = modInstaller.installMod(chosen, profile.minecraftVersion(),
                profile.loader(), profiles.modsDirectory(profile), progress);
        settleModCount(profile, result, progress);
        return result;
    }

    /**
     * Everything in a profile's mods folder, whether the launcher put it there
     * or the player did.
     */
    public java.util.List<com.hexadron.launcher.mods.ModEntry> modsIn(Profile profile) {
        // Judged against this profile's Minecraft version, so a jar left behind
        // by a change of version is a row that says so rather than a crash.
        return modsIn(profile, profile.minecraftVersion());
    }

    /**
     * The same list, judged against a Minecraft version the profile is not on.
     *
     * <p>Asked before the change rather than after it. Moving a profile to
     * another Minecraft version is the one edit that can invalidate the whole
     * mods folder at once, and it is carried out silently: the dialog closes, the
     * client is reinstalled, and the damage appears at the next launch as a wall
     * of loader errors. With this the question can be put while the answer still
     * costs nothing - "twelve of these forty publish no build for 1.21.1" - and
     * the user decides knowing it.
     *
     * <p>Also what makes a way back offerable. The launcher records the version a
     * profile came from; this says whether going back would actually help, so the
     * offer is only made when it would.
     */
    public java.util.List<com.hexadron.launcher.mods.ModEntry> modsIn(
            Profile profile, String minecraftVersion) {
        return com.hexadron.launcher.mods.ModScan.scan(
                profiles.modsDirectory(profile), minecraftVersion);
    }

    /**
     * The mods that would stop loading if this profile moved to
     * {@code minecraftVersion}, in the order they are listed.
     *
     * <p>Only jars that rule the version out themselves. One that names no
     * versions is not counted: the launcher does not know, and reporting a guess
     * as a casualty would talk people out of changes that would have worked.
     */
    public java.util.List<com.hexadron.launcher.mods.ModEntry> modsBrokenBy(
            Profile profile, String minecraftVersion) {
        return com.hexadron.launcher.mods.ModScan.wrongVersion(
                modsIn(profile, minecraftVersion));
    }

    /**
     * The version to offer going back to, or empty when there is nothing worth
     * offering.
     *
     * <p>Three conditions, and all have to hold. There has to be a recorded
     * previous version - the launcher does not infer one from the mods' own
     * ranges, which would at best name a range and at worst name a version the
     * profile was never on. Something has to be broken now. And going back has
     * to fix it without breaking anything else.
     *
     * <p>So a folder whose mods are wrong for both versions produces no offer,
     * which is right: going back would swap one failure for another, and the
     * user would have been told it was a fix.
     */
    public java.util.Optional<String> versionToGoBackTo(Profile profile) {
        String previous = profile.previousMinecraftVersion();
        if (previous == null || previous.isBlank()
                || previous.equals(profile.minecraftVersion())) {
            return java.util.Optional.empty();
        }
        if (modsBrokenBy(profile, profile.minecraftVersion()).isEmpty()) {
            return java.util.Optional.empty();
        }
        if (!modsBrokenBy(profile, previous).isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(previous);
    }

    /**
     * Moves a profile to another Minecraft version, taking its mods with it.
     *
     * <p>Two halves, and only one of them can be done exactly. Mods the launcher
     * installed are looked up again for the new version and replaced - that is
     * {@link ModInstaller#migrateMods}, and it works from project ids. Jars the
     * player put in the folder themselves have no project to look up, so they are
     * judged the only way left: on what the jar itself says it accepts. One that
     * rules the new version out is switched off; one that says nothing is left
     * alone, because a guess that switches off a working mod is worse than a mod
     * that turns out not to load.
     *
     * <p>The version is written to the profile first. Everything after it reads
     * the profile's new version, and a half-moved profile - new mods, old version
     * - is the state that is hardest to explain afterwards.
     *
     * @return what happened to each mod, for the caller to show
     */
    public ModInstaller.Migration moveToVersion(Profile profile, String minecraftVersion,
                                                Progress progress)
            throws IOException, InterruptedException {
        return moveToVersion(profile, minecraftVersion, planVersionMove(profile, minecraftVersion), progress);
    }

    /**
     * Moves a profile to another Minecraft version as a plan from
     * {@link #planVersionMove} says: each mod with a build for the new version
     * is replaced (the old jar set aside in {@code mods/.removed/}, and what the
     * new build requires installed), each without one is switched off, the rest
     * is left alone. Every jar is identified by its content, so one the player
     * added by hand is moved as well when a platform knows it.
     */
    public ModInstaller.Migration moveToVersion(Profile profile, String minecraftVersion,
                                                java.util.List<com.hexadron.launcher.mods.ModUpdates.MoveRow> plan,
                                                Progress progress) throws IOException, InterruptedException {
        profile.minecraftVersion(minecraftVersion);
        profiles.save();

        java.util.List<String> updated = new java.util.ArrayList<>();
        java.util.List<String> switchedOff = new java.util.ArrayList<>();
        java.util.List<String> kept = new java.util.ArrayList<>();
        java.util.List<com.hexadron.launcher.mods.ModUpdates.Update> replace = new java.util.ArrayList<>();
        for (com.hexadron.launcher.mods.ModUpdates.MoveRow row : plan) {
            switch (row.action()) {
                case REPLACE -> {
                    replace.add(new com.hexadron.launcher.mods.ModUpdates.Update(
                            com.hexadron.launcher.mods.ContentKind.MOD, row.mod(), row.title(), row.next(),
                            row.dependencies()));
                    updated.add(row.title() + " -> " + row.next().displayName());
                }
                case SWITCH_OFF -> {
                    try {
                        setModEnabled(profile, row.mod(), false);
                        switchedOff.add(row.title() + " - no build for Minecraft " + minecraftVersion);
                    } catch (IOException e) {
                        progress.log("%s could not be switched off: %s", row.mod().fileName(), e.getMessage());
                    }
                }
                case KEEP -> kept.add(row.title());
                case UNKNOWN -> kept.add(row.title() + " (on neither Modrinth nor CurseForge; left as it is)");
            }
        }
        if (!replace.isEmpty()) {
            applyUpdates(profile, replace, progress, false);
        }
        LauncherLog.info("Moved %s to Minecraft %s: %d replaced, %d switched off, %d kept", profile.name(),
                minecraftVersion, updated.size(), switchedOff.size(), kept.size());
        return new ModInstaller.Migration(updated, switchedOff, kept);
    }

    /**
     * Which of these mods are needed by which others.
     *
     * <p>Read from the jars already scanned, so it is a map built in memory
     * rather than a folder read again. Asked before switching a mod off or
     * deleting it: a library taken out from under five mods does not fail then,
     * it fails at the next launch.
     */
    public com.hexadron.launcher.mods.ModDependents modDependents(
            java.util.List<com.hexadron.launcher.mods.ModEntry> mods) {
        return com.hexadron.launcher.mods.ModDependents.of(mods);
    }

    /**
     * The mods in a profile that will not load, because they say so themselves.
     *
     * <p>Asked before a launch. Everything here comes from files already on
     * disk, so it costs nothing and works with no connection - and it is the
     * same question the loader is about to ask, which is why the answer can be
     * trusted enough to stop a launch on.
     */
    public java.util.List<com.hexadron.launcher.mods.ModEntry> wrongVersionMods(Profile profile) {
        return com.hexadron.launcher.mods.ModScan.wrongVersion(modsIn(profile));
    }

    /** How many of the offending mods to name before counting the rest. */
    private static final int WRONG_VERSION_NAMES_SHOWN = 5;

    /**
     * What to tell the user about mods that cannot load, and what to do next.
     *
     * <p>Names the files rather than the projects: the fix is carried out in the
     * mods folder, and the file name is what identifies a jar there.
     */
    private String wrongVersionMessage(
            Profile profile, java.util.List<com.hexadron.launcher.mods.ModEntry> wrongVersion) {

        boolean one = wrongVersion.size() == 1;
        StringBuilder message = new StringBuilder();
        message.append(wrongVersion.size())
                .append(one ? " mod in this profile is" : " mods in this profile are")
                .append(" not for Minecraft ").append(profile.minecraftVersion())
                .append(", and the game will not start while ")
                .append(one ? "it is" : "they are")
                .append(" switched on:");

        int named = 0;
        for (com.hexadron.launcher.mods.ModEntry entry : wrongVersion) {
            if (named == WRONG_VERSION_NAMES_SHOWN) {
                message.append("\n  and ").append(wrongVersion.size() - named).append(" more");
                break;
            }
            message.append("\n  ").append(entry.fileName());
            if (entry.requires() != null && !entry.requires().isBlank()) {
                message.append(" (needs ").append(entry.requires()).append(')');
            }
            named++;
        }

        message.append(one
                        ? "\nSwitch it off, or replace it with a build for Minecraft "
                        : "\nSwitch them off, or replace them with builds for Minecraft ")
                .append(profile.minecraftVersion()).append('.');

        // The way back, when there is one that works. Named only after it has
        // been checked against the mods themselves, so this is never the advice
        // that trades one broken version for another.
        versionToGoBackTo(profile).ifPresent(previous -> message
                .append("\nThis profile was on Minecraft ").append(previous)
                .append(" before, and every one of these loads there.")
                .append(" Setting it back to ").append(previous)
                .append(" is the other way out."));

        return message.toString();
    }

    /**
     * Turns one file in the mods folder on or off by renaming it.
     *
     * @return where the file ended up
     */
    public Path setModEnabled(Profile profile, com.hexadron.launcher.mods.ModEntry entry,
                              boolean enabled) throws IOException {
        return com.hexadron.launcher.mods.ModScan.setEnabled(
                profiles.modsDirectory(profile), entry, enabled);
    }

    /**
     * Copies jars the player chose into a profile's mods folder.
     *
     * <p>Not routed through the mod installer: nothing here is resolved, no
     * version is checked against a platform and no lock entry is written,
     * because none of that is known about a file that arrived from a browser.
     * They land as what they are - the player's own mods.
     */
    public com.hexadron.launcher.mods.ModScan.Imported importMods(
            Profile profile, java.util.List<Path> files, Progress progress) throws IOException {

        return com.hexadron.launcher.mods.ModScan.importJars(
                profiles.modsDirectory(profile), files, progress);
    }

    /** Sends a file the launcher did not install to the recycle bin. */
    public void discardExternalMod(Profile profile, com.hexadron.launcher.mods.ModEntry entry,
                                   Progress progress) throws IOException {
        com.hexadron.launcher.mods.ModScan.discard(
                profiles.modsDirectory(profile), entry, progress);
    }

    /**
     * How many jars in this profile's folder the launcher still has no name for.
     *
     * <p>What the "identify" button is offered on the strength of. Cheap: it
     * reads one small index file and compares names and sizes against a list the
     * caller already has.
     */
    public int unidentifiedModCount(Profile profile,
                                    java.util.List<com.hexadron.launcher.mods.ModEntry> mods) {
        return com.hexadron.launcher.mods.ExternalModIndex.unidentified(mods,
                com.hexadron.launcher.mods.ExternalModIndex.read(
                        profiles.modsDirectory(profile))).size();
    }

    /**
     * Asks Modrinth what the unrecognised jars in a profile actually are.
     *
     * <p>By hash, and only when the user presses the button: the launcher does
     * not report the contents of a player's mods folder to anybody on its own.
     *
     * @return how many were recognised
     */
    public int identifyExternalMods(Profile profile, Progress progress)
            throws IOException, InterruptedException {

        return com.hexadron.launcher.mods.ExternalModIndex.identify(
                profiles.modsDirectory(profile), modrinth, progress);
    }

    /** Removes one mod the user installed. Pack-owned mods are refused here. */
    public void removeMod(Profile profile, String key, Progress progress) throws IOException {
        modInstaller.removeMod(key, profiles.modsDirectory(profile), progress);
    }

    /** Removes every mod a pack owns. */
    public int removePack(Profile profile, String packId, Progress progress) throws IOException {
        return modInstaller.removePack(packId, profiles.modsDirectory(profile), progress);
    }

    /** Whether a pack has a build for every required entry on this profile. */
    public ModInstaller.PackAvailability packAvailability(Profile profile, ModPack pack)
            throws InterruptedException {
        if (profile.loader() == LoaderType.VANILLA) {
            return ModInstaller.PackAvailability.unsupportedLoader();
        }
        return modInstaller.checkPack(pack, profile.minecraftVersion(), profile.loader());
    }

    // ---------------------------------------------------------------- modpacks

    /**
     * The pack archive published for a project, ready to download.
     *
     * <p>Not filtered by this profile's version or loader: a pack states its own,
     * and asking for "the build for Fabric 26.2" would come back empty for every
     * pack that is not exactly that - which is every pack.
     */
    public java.util.Optional<com.hexadron.launcher.mods.ModFile> resolveModpack(
            ModProvider.ProjectCard card) throws IOException, InterruptedException {

        for (ModProvider provider : modProviders()) {
            if (provider.source() != card.source() || !provider.isAvailable()) {
                continue;
            }
            return provider.resolveFile(com.hexadron.launcher.mods.ContentKind.MODPACK,
                    card.projectId(), profiles.selected().map(Profile::minecraftVersion).orElse(null),
                    LoaderType.VANILLA);
        }
        throw new IOException(card.source().displayName() + " is not configured");
    }

    /**
     * Downloads a pack archive into the cache.
     *
     * <p>The cache and not an instance: the archive is the input to an install
     * rather than part of its result, and a 300 MB zip left in an instance folder
     * is 300 MB the user cannot account for. It stays in the cache so that
     * re-installing the same pack does not fetch it twice.
     */
    public Path fetchModpack(com.hexadron.launcher.mods.ModFile file, Progress progress)
            throws IOException, InterruptedException {
        return modpackInstaller.fetch(file, dirs.cache().resolve("modpacks"), progress);
    }

    /** Reads a pack file - either format - without installing anything. */
    public com.hexadron.launcher.mods.PackArchive readModpack(Path archive) throws IOException {
        return com.hexadron.launcher.mods.PackArchive.read(archive);
    }

    /**
     * A new profile shaped by a pack: its name, its Minecraft version, its
     * loader and the loader version it pins.
     *
     * <p>Created and saved before anything is downloaded, so that a pack whose
     * install fails half-way leaves an instance the user can see, retry into, or
     * delete - rather than a folder under an id that nothing in the launcher
     * mentions.
     */
    public Profile createProfileForModpack(com.hexadron.launcher.mods.PackArchive pack)
            throws IOException {

        String name = freeProfileName(pack.instanceName());
        Profile profile = Profile.create(name, pack.minecraftVersion(), pack.loader());
        if (pack.loaderVersion() != null && !pack.loaderVersion().isBlank()) {
            profile.loaderVersion(pack.loaderVersion().trim());
        }
        profiles.add(profile);
        profiles.save();
        return profile;
    }

    /**
     * A name no existing profile has.
     *
     * <p>Profile ids are made from the name, and two instances of the same
     * modpack is an ordinary thing to want - one to play and one to test a mod
     * in. Without this the second one would be filed under the first one's id and
     * would replace it in the list while sharing its folder.
     */
    private String freeProfileName(String wanted) {
        String base = wanted == null || wanted.isBlank() ? "Modpack" : wanted.trim();
        java.util.Set<String> taken = new java.util.HashSet<>();
        profiles.all().forEach(profile -> taken.add(profile.name().toLowerCase(java.util.Locale.ROOT)));
        if (!taken.contains(base.toLowerCase(java.util.Locale.ROOT))) {
            return base;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + " (" + suffix + ")";
            if (!taken.contains(candidate.toLowerCase(java.util.Locale.ROOT))) {
                return candidate;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

    /**
     * Installs a pack into a profile.
     *
     * <p>The profile's version and loader are set to the pack's. That is not a
     * courtesy: a pack's mods are built for one pair, and installing them into an
     * instance set to another produces a folder of jars that cannot load and a
     * crash the user has no way to connect to this button.
     */
    public com.hexadron.launcher.mods.ModpackInstaller.Result installModpack(
            Profile profile, com.hexadron.launcher.mods.PackArchive pack,
            ModProvider.ProjectCard card, Progress progress)
            throws IOException, InterruptedException {

        profile.minecraftVersion(pack.minecraftVersion());
        profile.loader(pack.loader());
        profile.loaderVersion(pack.loaderVersion() == null || pack.loaderVersion().isBlank()
                ? null : pack.loaderVersion().trim());
        profiles.save();

        // Before the mods, not after. See settleJava: this is the point at which
        // the pack's Minecraft version is known and nothing large has been
        // fetched yet, which makes it the only place the question can be asked
        // early enough to be worth asking.
        settleJava(profile, pack.minecraftVersion(), progress);

        // Nothing is done to the pack's own configuration here, deliberately. A
        // pack ships its overrides because its author decided what they should
        // say, and a launcher that edited one of those files after unpacking it
        // would be changing a set the player asked for by name.
        return modpackInstaller.install(pack, card, profiles.gameDirectory(profile), progress);
    }

    // ---------------------------------------------------------------- storage

    /**
     * Reads the data folder for the storage window: what is there, what uses it,
     * and what the safe mode may offer. Reads only.
     */
    public com.hexadron.launcher.cleanup.StorageReport scanStorage(Progress progress)
            throws InterruptedException {
        return com.hexadron.launcher.cleanup.StorageScanner.scan(
                new com.hexadron.launcher.cleanup.StorageScanner.Inputs(dirs, profiles.all(),
                        profiles::gameDirectory, versionInstaller.resolver(),
                        javaRuntimes.provisioner(), javaMajorsInUse(), LauncherLog.file()),
                progress);
    }

    /**
     * Deletes what the storage window chose, and brings the profile list up to
     * date with it.
     *
     * <p>Three steps. A profile chosen whole is removed the way the Remove
     * button removes one - out of the list, its folder with it, its Java given
     * back if nothing else wants it - because a folder deleted from under a
     * profile left the profile in the list, pointing at nothing. Then the rest
     * goes through {@link com.hexadron.launcher.cleanup.StorageCleaner}. Then
     * every remaining profile is checked against what is left on disk, so one
     * whose version was deleted says "not installed" rather than naming a
     * version that is gone.
     */
    public com.hexadron.launcher.cleanup.StorageCleaner.Result cleanStorage(
            List<com.hexadron.launcher.cleanup.CleanupAction> actions, Progress progress)
            throws InterruptedException {

        List<Path> failed = new java.util.ArrayList<>();
        List<com.hexadron.launcher.cleanup.CleanupAction> rest = new java.util.ArrayList<>();
        int removed = 0;
        int deleted = 0;
        for (com.hexadron.launcher.cleanup.CleanupAction action : actions) {
            Set<Path> handled = new java.util.HashSet<>();
            for (String id : action.profileIds()) {
                java.util.Optional<Profile> found = profiles.byId(id);
                if (found.isEmpty()) {
                    continue;
                }
                Profile profile = found.get();
                Path folder = profiles.gameDirectory(profile).toAbsolutePath().normalize();
                progress.stage("delete:" + profile.name());
                try {
                    failed.addAll(deleteProfile(profile, true, progress));
                    handled.add(folder);
                    removed++;
                } catch (IOException e) {
                    failed.add(folder);
                    handled.add(folder);
                }
            }
            List<Path> trees = action.trees().stream()
                    .filter(path -> !handled.contains(path.toAbsolutePath().normalize()))
                    .toList();
            if (!trees.isEmpty() || !action.files().isEmpty() || !action.javaMajors().isEmpty()) {
                rest.add(new com.hexadron.launcher.cleanup.CleanupAction(trees, action.files(),
                        action.filesRoot(), action.javaMajors(), action.size()));
            }
        }

        com.hexadron.launcher.cleanup.StorageCleaner.Result result =
                com.hexadron.launcher.cleanup.StorageCleaner.clean(dirs, LauncherLog.file(),
                        javaRuntimes.provisioner(), rest, progress);
        failed.addAll(result.failed());
        deleted += result.deleted();

        if (profiles.reconcileWithDisk(versionInstaller.resolver()::isFullyInstalled)) {
            try {
                profiles.save();
            } catch (IOException e) {
                progress.log("The profile list could not be saved: %s",
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        return new com.hexadron.launcher.cleanup.StorageCleaner.Result(deleted + removed, failed, removed);
    }

    // ---------------------------------------------------------------- builds

    /**
     * Reads a profile for export and sorts its files.
     *
     * <p>The first half of an export: nothing is written. What it returns says
     * which files are the player's own, so the interface can ask about them
     * before {@link #writeBuild} puts anything on disk.
     */
    public com.hexadron.launcher.share.BuildExport.Plan planBuild(
            Profile profile, com.hexadron.launcher.share.BuildExport.Options options,
            Progress progress) throws InterruptedException {

        Path icon = profile.hasCustomIcon() ? dirs.icons().resolve(profile.customIcon()) : null;
        return com.hexadron.launcher.share.BuildExport.plan(profile,
                profiles.gameDirectory(profile), icon, options,
                modrinth, progress);
    }

    /** The second half of an export. */
    public int writeBuild(com.hexadron.launcher.share.BuildExport.Plan plan,
                          boolean includeCustom, Path target, Progress progress)
            throws IOException, InterruptedException {
        return com.hexadron.launcher.share.BuildExport.write(plan, includeCustom, target,
                "Hexadron Launcher " + com.hexadron.launcher.BuildConfig.version(), progress);
    }

    /** Reads a build file without installing anything. */
    public com.hexadron.launcher.share.BuildImport readBuild(Path archive) throws IOException {
        return com.hexadron.launcher.share.BuildImport.read(archive);
    }

    /** A profile made from a build, and what came of filling it. */
    public record ImportedBuild(Profile profile,
                                com.hexadron.launcher.share.BuildImport.Result result) {
    }

    /**
     * Makes a new profile out of a build.
     *
     * <p>Always a new one. Importing over an existing instance would mean
     * deciding, file by file, whose copy wins - and the loser would be a world
     * or a config the player had not backed up. A new profile has nothing to
     * lose.
     *
     * <p>Created and saved before anything is downloaded, for the same reason as
     * {@link #createProfileForModpack}: an import that fails half-way leaves an
     * instance the player can see, retry into, or delete.
     *
     * @param name          what to call it; made unique if it is taken
     * @param includeCustom whether the player's own files are taken out of the build
     * @param withArguments whether the build's JVM and game arguments are used
     */
    public ImportedBuild importBuild(com.hexadron.launcher.share.BuildImport build, String name,
                                     boolean includeCustom, boolean withArguments,
                                     Progress progress)
            throws IOException, InterruptedException {

        Profile profile = Profile.create(freeProfileName(name == null || name.isBlank()
                ? build.name() : name), build.minecraftVersion(), build.loader());
        if (build.loaderVersion() != null) {
            profile.loaderVersion(build.loaderVersion());
        }
        build.applySettings(profile, withArguments);
        profiles.add(profile);
        profiles.save();

        // Before the files, as for a modpack: the version is known now and
        // nothing large has been fetched yet.
        settleJava(profile, build.minecraftVersion(), progress);

        com.hexadron.launcher.share.BuildImport.Result result =
                build.install(profiles.gameDirectory(profile), includeCustom, downloader, progress);
        profiles.save();
        return new ImportedBuild(profile, result);
    }

    /**
     * The major Java version a Minecraft version asks for.
     *
     * <p>Fetches the vanilla manifest if it is not on disk - a few kilobytes -
     * because the number has to be known before a pack is installed and a
     * profile that has never been launched has nothing local to read. Loader
     * manifests inherit the block, so the vanilla version is the right thing to
     * ask.
     */
    public int requiredJavaFor(String minecraftVersion, Progress progress)
            throws IOException, InterruptedException {

        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new IOException("no Minecraft version to look up a Java requirement for");
        }
        if (!versionInstaller.resolver().isInstalled(minecraftVersion)) {
            versionInstaller.ensureVanillaVersionJson(
                    minecraftVersion, minecraftVersions(), progress);
        }
        return versionInstaller.resolver().resolve(minecraftVersion).requiredJavaMajor();
    }

    /**
     * Settles the Java question for a profile before anything large is
     * downloaded for it.
     *
     * <p>The whole point of the timing. A pack's Java requirement used to
     * surface at the end of the chain - install the pack, wait for four hundred
     * mods, press Play, and only then be told the machine has no Java 8 - and by
     * then the person has spent twenty minutes on something that was never going
     * to start. Asked here, the answer costs one small manifest fetch and
     * arrives before the mods do.
     *
     * <p>Reports rather than throws. A declined download, an unreachable
     * Adoptium or a version whose manifest cannot be read are all reasons to
     * carry on installing: the pack itself is fine, and {@link #launch} asks the
     * same question again with the same dialog when the time comes.
     *
     * @return the major version the profile needs, or 0 when it could not be
     *         determined
     */
    public int settleJava(Profile profile, String minecraftVersion, Progress progress)
            throws InterruptedException {

        int required;
        try {
            required = requiredJavaFor(minecraftVersion, progress);
        } catch (IOException e) {
            progress.log("Could not work out which Java Minecraft %s needs (%s). It will be "
                    + "settled when the game is started.", minecraftVersion, e.getMessage());
            return 0;
        }

        profile.javaMajor(required);
        try {
            profiles.save();
        } catch (IOException e) {
            progress.log("Could not store this profile's Java version: %s", e.getMessage());
        }

        if (profile.javaPath() != null) {
            // The profile names its own runtime. Asking about a download would
            // be asking about something this profile will not use.
            progress.log("This profile is pinned to the Java at %s; Minecraft %s asks for "
                    + "Java %d.", profile.javaPath(), minecraftVersion, required);
            return required;
        }

        progress.log("Minecraft %s needs Java %d.", minecraftVersion, required);
        javaRuntimes.ensure(required, progress).ifPresentOrElse(
                runtime -> progress.log("Java %d is ready: %s", required, runtime),
                () -> progress.log("Java %d is not installed. The pack will install, and the "
                        + "launcher will ask again when the game is started - but it will not "
                        + "start until Java %d is there.", required, required));
        return required;
    }

    /**
     * Removes a profile, and with it any runtime the launcher downloaded that
     * nothing else asks for.
     *
     * <p>Runtimes are shared by major version, so one Java 21 serves every
     * profile that wants Java 21 and the question at removal time is never
     * "which runtime was this profile's" but "is anything still asking for this
     * one". {@link #javaMajorsInUse} answers it, and refuses to answer when it
     * cannot answer completely.
     *
     * @param deleteFiles whether the profile's game folder goes too
     * @return the paths that could not be deleted, empty when everything went
     */
    public List<Path> deleteProfile(Profile profile, boolean deleteFiles, Progress progress)
            throws IOException, InterruptedException {
        return deleteProfile(profile, deleteFiles, progress, new DeletionSteps() {
        });
    }

    /**
     * What a profile deletion tells the interface on the way.
     *
     * <p>Both on the deleting thread.
     */
    public interface DeletionSteps {

        /** The profile is out of the list and the list is saved. The files may still be there. */
        default void removed() {
        }

        /** The files have been counted and the first is about to go. */
        default void counted(int files) {
        }
    }

    /**
     * Removes a profile, and its files when asked.
     *
     * <p>The list first, then the files. The profile leaves the list the moment
     * the player confirms - that part is one small file - and the folder, which
     * can be tens of thousands of files, is emptied after, with {@code steps}
     * told how many so the bar can say so.
     */
    public List<Path> deleteProfile(Profile profile, boolean deleteFiles, Progress progress,
                                    DeletionSteps steps)
            throws IOException, InterruptedException {

        Path directory = profiles.gameDirectory(profile);
        profiles.remove(profile);
        profiles.save();
        steps.removed();

        List<Path> undeleted = deleteFiles
                ? profiles.deleteInstanceFolder(directory, progress, steps::counted)
                : List.of();

        Set<Integer> stillWanted = javaMajorsInUse();
        if (stillWanted == null) {
            progress.log("Leaving the downloaded Java runtimes alone: at least one remaining "
                    + "profile has no recorded Java version, so it cannot be said which "
                    + "runtimes are still needed.");
            return undeleted;
        }
        javaRuntimes.prune(stillWanted, progress);
        return undeleted;
    }

    /**
     * The major Java versions the remaining profiles need.
     *
     * <p>Null - not an empty set - when any profile's requirement is unknown.
     * The difference matters: an empty set means "nothing needs anything, delete
     * it all", and returning that because one profile had not been launched yet
     * would delete a runtime it is about to need. Unknown is a reason to do
     * nothing, and the caller treats it as one.
     */
    public Set<Integer> javaMajorsInUse() {
        Set<Integer> majors = new LinkedHashSet<>();
        for (Profile profile : profiles.all()) {
            Integer recorded = profile.javaMajor();
            if (recorded != null) {
                majors.add(recorded);
                continue;
            }
            // Not recorded, so try to derive it from what is already on disk.
            // No network here: this runs while a dialog is closing.
            String versionId = profile.effectiveVersionId();
            try {
                majors.add(versionInstaller.resolver().resolve(versionId).requiredJavaMajor());
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return majors;
    }

    /** The modpacks installed in a profile, newest first. */
    public java.util.List<com.hexadron.launcher.mods.InstalledModpack> modpacksIn(Profile profile) {
        return com.hexadron.launcher.mods.ModpackLibrary
                .read(profiles.gameDirectory(profile)).all();
    }

    /** True when this project is already installed in this profile. */
    public boolean hasModpack(Profile profile, ModProvider.Source source, String projectId) {
        return com.hexadron.launcher.mods.ModpackLibrary
                .read(profiles.gameDirectory(profile)).contains(source, projectId);
    }

    /** Removes a modpack: exactly the files it wrote, and nothing else. */
    public int removeModpack(Profile profile, String id, Progress progress) throws IOException {
        return modpackInstaller.remove(id, profiles.gameDirectory(profile), progress);
    }

    // ------------------------------------------------- resource packs, shaders

    /**
     * Everything in a profile's folder for one kind, whoever put it there.
     *
     * <p>{@link ContentKind#RESOURCEPACK} and {@link ContentKind#SHADER}. Mods
     * have {@link #modsIn} and data packs {@link #datapacksIn}, because those
     * two answer a different question - one is judged against the profile's
     * Minecraft version, the other belongs to a world.
     */
    public java.util.List<com.hexadron.launcher.mods.ModEntry> packsIn(
            Profile profile, com.hexadron.launcher.mods.ContentKind kind) {
        return com.hexadron.launcher.mods.PackScan.of(kind)
                .scan(profiles.contentDirectory(profile, kind));
    }

    /**
     * Which programs in this instance can load a shader pack.
     *
     * <p>Read from the mods folder, because that is where they are: Iris,
     * OptiFine and Canvas are mods. Answered before a shader is installed - to
     * pick the build this instance can actually use - and shown in the panel,
     * because a shaderpacks folder on an instance with none of them is a folder
     * the game never opens.
     */
    public java.util.List<com.hexadron.launcher.mods.ShaderLoaders.ShaderLoader>
            shaderLoadersIn(Profile profile) {
        return com.hexadron.launcher.mods.ShaderLoaders.detect(modsIn(profile));
    }

    /**
     * Installs one resource pack or shader pack, and any pack of the same kind
     * it requires.
     *
     * <p>The shader loaders installed here are passed for a shader, because a
     * shader project publishes a version per program that loads it and the one
     * to ask for is the one the instance has. Nothing is passed for a resource
     * pack: the game loads those.
     */
    public com.hexadron.launcher.mods.PackInstaller.Result installPackFile(
            Profile profile, com.hexadron.launcher.mods.ContentKind kind,
            ModProvider.ProjectCard chosen, Progress progress)
            throws IOException, InterruptedException {

        java.util.List<String> loaderTags =
                kind == com.hexadron.launcher.mods.ContentKind.SHADER
                        ? com.hexadron.launcher.mods.ShaderLoaders.tagsOf(shaderLoadersIn(profile))
                        : java.util.List.of();
        return installerFor(kind).install(chosen, profile.minecraftVersion(), loaderTags,
                profiles.contentDirectory(profile, kind), progress);
    }

    /**
     * Removes one pack the launcher installed, and the packs it brought with it
     * that nothing else needs.
     *
     * @return how many files were deleted
     */
    public int removePackFile(Profile profile, com.hexadron.launcher.mods.ContentKind kind,
                              String key, Progress progress) throws IOException {
        return installerFor(kind).remove(key, profiles.contentDirectory(profile, kind), progress);
    }

    /** Sends a pack the launcher did not install to the recycle bin. */
    public void discardExternalPack(Profile profile, com.hexadron.launcher.mods.ContentKind kind,
                                    com.hexadron.launcher.mods.ModEntry entry, Progress progress)
            throws IOException {
        com.hexadron.launcher.mods.PackScan.of(kind)
                .discard(profiles.contentDirectory(profile, kind), entry, progress);
    }

    /** Turns one pack on or off by renaming it. */
    public Path setPackEnabled(Profile profile, com.hexadron.launcher.mods.ContentKind kind,
                               com.hexadron.launcher.mods.ModEntry entry, boolean enabled)
            throws IOException {
        return com.hexadron.launcher.mods.PackScan.of(kind)
                .setEnabled(profiles.contentDirectory(profile, kind), entry, enabled);
    }

    /** Copies pack files the player chose into a profile's folder for that kind. */
    public com.hexadron.launcher.mods.ModScan.Imported importPackFiles(
            Profile profile, com.hexadron.launcher.mods.ContentKind kind,
            java.util.List<Path> files, Progress progress) throws IOException {
        return com.hexadron.launcher.mods.PackScan.of(kind)
                .importPacks(profiles.contentDirectory(profile, kind), files, progress);
    }

    private com.hexadron.launcher.mods.PackInstaller installerFor(
            com.hexadron.launcher.mods.ContentKind kind) {
        return switch (kind) {
            case RESOURCEPACK -> resourcePackInstaller;
            case SHADER -> shaderInstaller;
            default -> throw new IllegalArgumentException(
                    kind + " is not installed into a folder of the instance's own");
        };
    }

    // ---------------------------------------------------------------- data packs

    /** The worlds in a profile, most recently played first. */
    public java.util.List<com.hexadron.launcher.mods.WorldSaves.World> worldsIn(Profile profile) {
        return com.hexadron.launcher.mods.WorldSaves.of(profiles.gameDirectory(profile));
    }

    /** Everything in one world's data pack folder, whoever put it there. */
    public java.util.List<com.hexadron.launcher.mods.ModEntry> datapacksIn(
            com.hexadron.launcher.mods.WorldSaves.World world) {
        return com.hexadron.launcher.mods.DatapackScan.scan(world.datapacks());
    }

    /**
     * Installs one data pack into one world.
     *
     * <p>The profile's loader is passed rather than assumed, because a data pack
     * taken in its loader flavour brings a mod with it and that mod goes into
     * this profile's mods folder. {@code withoutMods} is the user's answer to
     * the box that decides which flavour is asked for.
     */
    public com.hexadron.launcher.mods.DatapackInstaller.Result installDatapack(
            Profile profile, com.hexadron.launcher.mods.WorldSaves.World world,
            ModProvider.ProjectCard chosen, boolean withoutMods, Progress progress)
            throws IOException, InterruptedException {

        return datapackInstaller.install(chosen, profile.minecraftVersion(), profile.loader(),
                withoutMods, world.datapacks(), profiles.modsDirectory(profile),
                world.folder(), progress);
    }

    /**
     * Removes one data pack the launcher installed, and the mods it needed.
     *
     * @return how many mods went with it
     */
    public int removeDatapack(Profile profile,
                              com.hexadron.launcher.mods.WorldSaves.World world,
                              String key, Progress progress) throws IOException {
        return datapackInstaller.remove(key, world.datapacks(),
                profiles.modsDirectory(profile), world.folder(), progress);
    }

    /** Sends a data pack the launcher did not install to the recycle bin. */
    public void discardExternalDatapack(com.hexadron.launcher.mods.WorldSaves.World world,
                                        com.hexadron.launcher.mods.ModEntry entry,
                                        Progress progress) throws IOException {
        com.hexadron.launcher.mods.DatapackScan.discard(world.datapacks(), entry, progress);
    }

    /** Turns one data pack on or off by renaming it. */
    public Path setDatapackEnabled(com.hexadron.launcher.mods.WorldSaves.World world,
                                   com.hexadron.launcher.mods.ModEntry entry, boolean enabled)
            throws IOException {
        return com.hexadron.launcher.mods.DatapackScan.setEnabled(
                world.datapacks(), entry, enabled);
    }

    /** Copies data pack zips the player chose into one world's folder. */
    public com.hexadron.launcher.mods.ModScan.Imported importDatapacks(
            com.hexadron.launcher.mods.WorldSaves.World world,
            java.util.List<Path> files, Progress progress) throws IOException {
        return com.hexadron.launcher.mods.DatapackScan.importPacks(
                world.datapacks(), files, progress);
    }

    private void requireModdedLoader(Profile profile) throws IOException {
        if (profile.loader() == LoaderType.VANILLA) {
            throw new IOException("mods need a loader - set this profile to Fabric, Quilt, "
                    + "Forge or NeoForge first");
        }
    }

    /**
     * Copies a locally built mod jar into a profile's mods folder.
     *
     * <p>This is how {@code mod/build/libs/hexadron-optimise-*.jar} reaches a
     * test profile during development, without publishing it anywhere. The copy
     * is not recorded in the mod lock file, so a later pack install will not
     * delete it.
     */
    public Path installLocalMod(Profile profile, Path jar) throws IOException {
        if (!java.nio.file.Files.isRegularFile(jar)) {
            throw new IOException("no jar at " + jar.toAbsolutePath());
        }
        Path modsDir = profiles.modsDirectory(profile);
        java.nio.file.Files.createDirectories(modsDir);
        Path destination = modsDir.resolve(jar.getFileName().toString());
        java.nio.file.Files.copy(jar, destination,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return destination;
    }

    // ---------------------------------------------------------------- accounts

    /**
     * Signs in to a Microsoft account, using whichever flow the settings select.
     *
     * <p>Lives here rather than in the window so that the CLI, the self-check and
     * any future headless mode all go through the same code path. A second
     * implementation of an authentication flow is a second place for it to be
     * wrong.
     *
     * @param openBrowser  hands the authorization URL to the platform
     * @param onDeviceCode shown to the user when the device-code fallback is in use
     */
    public Account signInWithMicrosoft(java.util.function.Consumer<java.net.URI> openBrowser,
                                       Consumer<MicrosoftAuth.DeviceCodePrompt> onDeviceCode,
                                       Progress progress) throws IOException, InterruptedException {
        if (!settings.hasMicrosoftClientId()) {
            throw new IOException("no Azure application ID is configured for Microsoft sign-in");
        }
        MicrosoftAuth auth = new MicrosoftAuth(settings.microsoftClientId());

        Account account;
        if (settings.usesBrowserSignIn()) {
            account = auth.signInWithBrowser(openBrowser, progress);
        } else {
            MicrosoftAuth.DeviceCodePrompt prompt = auth.requestDeviceCode();
            onDeviceCode.accept(prompt);
            account = auth.completeDeviceCodeFlow(prompt,
                    remaining -> progress.stage("Waiting for sign-in (" + remaining + "s left)"),
                    progress);
        }
        accounts.add(account);
        accounts.save();
        return account;
    }

    /**
     * Removes an account and its stored credentials.
     *
     * <p>Deleting the local copy does not revoke Microsoft's side of the grant -
     * only the user can do that, at {@link MicrosoftAuth#CONSENT_MANAGEMENT_URL}.
     * The interface says so rather than implying that "remove" is the same as
     * "revoke", because after a suspected compromise those are very different
     * actions.
     */
    public void signOut(Account account) throws IOException {
        accounts.remove(account);
        accounts.save();
    }

    /**
     * Why an offline account was refused. Shown to the user as it is, so it
     * says what to do.
     */
    public static final String OFFLINE_NEEDS_LICENCE =
            "Offline play needs a Microsoft account that owns Minecraft: Java Edition. "
                    + "Sign in with Microsoft first, then add the offline account.";

    /**
     * Adds an offline account.
     *
     * <p>Only when a Microsoft account that owns the game is signed in here.
     * {@link MicrosoftAuth} checks ownership before it hands back an account,
     * so every stored Microsoft account with its credentials has passed that
     * check. Offline play is for single player and LAN games on a copy the
     * player owns - not a way to play without buying the game.
     *
     * @throws IllegalStateException    when no such Microsoft account is here;
     *                                  the message is {@link #OFFLINE_NEEDS_LICENCE}
     * @throws IllegalArgumentException when Minecraft would reject the name
     */
    public Account addOfflineAccount(String username) throws IOException {
        if (!accounts.hasLicensedAccount()) {
            throw new IllegalStateException(OFFLINE_NEEDS_LICENCE);
        }
        Account account = Account.offline(username);
        accounts.add(account);
        accounts.save();
        return account;
    }

    /** The skin pictures kept for Microsoft accounts, for upload to Mojang. */
    public SkinStore skins() {
        return skinStore;
    }

    /** Where the proxy password lives, if there is one. */
    public static final String PROXY_PASSWORD_KEY = "proxy:password";

    /**
     * Routes the network layer according to the settings.
     *
     * <p>Called at startup and again whenever the settings window is saved, so
     * a proxy typed in takes effect without a restart.
     */
    public void applyProxy() {
        String password = null;
        if (settings.proxy().wantsAuthentication()) {
            try {
                password = secretStore.load(PROXY_PASSWORD_KEY).orElse(null);
            } catch (IOException e) {
                // A locked keyring costs the proxy password, not the launch.
                // The proxy will answer 407 and that is a readable failure.
                password = null;
            }
        }
        com.hexadron.launcher.net.Http.useProxy(settings.proxy(), password);
    }

    /** Refreshes a Microsoft account's token if it is close to expiry. */
    public Account ensureFresh(Account account, Progress progress) throws IOException, InterruptedException {
        // The instance the interface holds can predate the credentials being
        // read (AccountStore.loadSecrets); this one has them.
        account = accounts.withSecrets(account);
        if (!account.needsRefresh()) {
            return account;
        }
        if (!settings.hasMicrosoftClientId()) {
            throw new IOException("this Microsoft account's session expired, but no Azure "
                    + "application ID is configured to refresh it");
        }
        progress.stage("Refreshing Microsoft session");
        Account refreshed = new MicrosoftAuth(settings.microsoftClientId()).refresh(account, progress);
        accounts.update(refreshed);
        accounts.save();
        return refreshed;
    }

    // ---------------------------------------------------------------- launch

    /**
     * Installs if needed, then starts the game.
     *
     * @param onOutput receives every line the game prints
     * @param onExit   receives the exit code when the game ends
     */
    public GameLauncher.GameSession launch(Profile profile, Account account, Progress progress,
                                           Consumer<String> onOutput, IntConsumer onExit)
            throws IOException, InterruptedException {
        return launch(profile, account, progress, onOutput, onExit, false);
    }

    /**
     * The same, for a caller that has already put the question to the user.
     *
     * <p>Mods that name a Minecraft version this profile is not on will not
     * load, and the launch stops rather than spending two minutes to arrive at a
     * loader error. That is right when there is nobody to ask - a scripted run,
     * the command line - and wrong as a rule: a version range is written by a
     * mod author and can be out of date, and a player who knows their pack works
     * is not to be argued with. So an interface that has asked and been told to
     * go ahead says so here, and is believed.
     *
     * @param modsAlreadyConfirmed true when the caller has shown the user which
     *                             mods will not load and been told to start anyway
     */
    public GameLauncher.GameSession launch(Profile profile, Account account, Progress progress,
                                           Consumer<String> onOutput, IntConsumer onExit,
                                           boolean modsAlreadyConfirmed)
            throws IOException, InterruptedException {

        // Checked before anything is downloaded. An unusable name otherwise
        // surfaces minutes later, inside the game, as the player being dropped
        // from their own single-player world with "Invalid characters in
        // username" - a message that looks like a multiplayer fault.
        if (account.isOffline() && !Account.isValidUsername(account.username())) {
            throw new IOException("Minecraft will not accept the player name \""
                    + account.username() + "\". A name is 3 to 16 characters and uses only "
                    + "Latin letters, digits and underscore. Add an offline account with a "
                    + "valid name and select it.");
        }

        // Offline play is for people who own the game. Checked here as well as
        // when the account is added, because accounts.json can be edited by hand
        // and a Microsoft account can be removed after an offline one was made.
        if (account.isOffline() && !accounts.hasLicensedAccount()) {
            throw new IOException(OFFLINE_NEEDS_LICENCE);
        }

        Account player = ensureFresh(account, progress);

        VersionJson version = installProfile(profile, progress);

        // Every jar in the folder names the Minecraft versions it accepts, so
        // this is decided from files already on disk, in the same terms the
        // loader is about to decide it in. Left unasked, the answer arrives as
        // the game exiting with code 1 and a page of resolution errors naming
        // every mod in the set - which reads as "the launcher is broken" and
        // takes an evening to trace back to one jar for the wrong version.
        if (!modsAlreadyConfirmed) {
            java.util.List<com.hexadron.launcher.mods.ModEntry> wrongVersion =
                    wrongVersionMods(profile);
            if (!wrongVersion.isEmpty()) {
                throw new IOException(wrongVersionMessage(profile, wrongVersion));
            }
        }

        Path gameDir = profiles.gameDirectory(profile);
        AssetIndex index = AssetIndex.parse(version.assetsId(),
                com.hexadron.launcher.json.Json.read(dirs.assetIndexFile(version.assetsId())));
        Path assetsDir = versionInstaller.assets().assetsDirFor(index);

        int requiredJava = version.requiredJavaMajor();
        // exactWanted, and this is the change that stops "the modpack will not
        // start". It used to be false, which meant "anything at least this new",
        // and satisfies() is a >= test - so a machine holding only Java 21 ran a
        // 1.12.2 pack on Java 21, crashed inside Forge's own bootstrap, and
        // never downloaded the Java 8 it needed. Nothing else would have fetched
        // it either: Forge for 1.12.2 has no processors, so the installer path
        // that does insist on the exact major is never reached for exactly the
        // packs that need it most. Mojang names one major per version and the
        // loaders compile against that one; a newer JVM is a different
        // environment, not a better one.
        JavaLocator.JavaRuntime java =
                javaRuntimes.resolve(profile.javaPath(), requiredJava, true, progress);
        progress.log("Using %s (this version requires Java %d)", java, requiredJava);

        // With the handshake on, a Microsoft session never goes onto the command
        // line: that is readable by every process on the machine and is copied
        // into hs_err_pid*.log. If the wrapper cannot be prepared the launch
        // stops instead of quietly falling back.
        Path wrapperJar = null;
        if (settings.secureLaunchHandshake()) {
            try {
                wrapperJar = LaunchWrapperJar.ensureExtracted(dirs);
            } catch (IOException e) {
                if (!player.isOffline()) {
                    throw new IOException("The launch wrapper could not be prepared ("
                            + e.getMessage() + "). The game was not started, so the session "
                            + "token did not go onto the command line. Close any running game "
                            + "and press Play again.", e);
                }
            }
            if (wrapperJar == null && !player.isOffline()) {
                throw new IOException("This build of the launcher has no launch wrapper, so the "
                        + "session token could only be passed on the command line. The game was "
                        + "not started.");
            }
        }

        // The same jar is the thread-dump agent, and that part is wanted for
        // every account: it is how a frozen game can still say what it was
        // doing. A jar that cannot be put on disk only costs that.
        Path agentJar = wrapperJar;
        if (agentJar == null) {
            try {
                agentJar = LaunchWrapperJar.ensureExtracted(dirs);
            } catch (IOException e) {
                progress.log("Thread-dump agent unavailable: %s", e.getMessage());
            }
        }
        com.hexadron.launcher.crash.ThreadDumps.clear(gameDir);

        LaunchCommandBuilder.LaunchCommand command = commandBuilder.build(
                version, profile, player, gameDir, assetsDir, java, wrapperJar, agentJar);

        progress.log("Command: %s", command.toLoggableString(player.accessToken()));

        profile.markPlayed();
        profiles.save();

        return gameLauncher.start(command, onOutput, onExit, progress);
    }
}
