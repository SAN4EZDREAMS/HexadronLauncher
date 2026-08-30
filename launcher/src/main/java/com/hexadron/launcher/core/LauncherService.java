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
import com.hexadron.launcher.skin.SkinCredentials;
import com.hexadron.launcher.skin.SkinProfile;
import com.hexadron.launcher.skin.SkinSession;
import com.hexadron.launcher.skin.SkinStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
    private final SkinCredentials skinCredentials;
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
     * Named stages of start-up, in the order they run.
     *
     * <p>Reported as identifiers rather than sentences: this class has no
     * interface layer and no translations, and the splash screen turns each of
     * these into a line in the user's own language. Anything not listed here is
     * fast enough not to be worth a line.
     */
    public static final List<String> STARTUP_STEPS =
            List.of("settings", "dataFolder", "profiles", "credentials", "accounts", "platforms");

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
        this.skinStore = new SkinStore(this.dirs).load();
        this.skinCredentials = new SkinCredentials(secretStore);

        // Before anything is fetched. On a network that needs a proxy, a single
        // request sent direct is a twenty-second wait for a failure.
        applyProxy();
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
            try {
                javaLocator.discover();
            } catch (RuntimeException ignored) {
                // A warm-up that fails costs nothing; the real call will report.
            }
        }, "hexadron-warmup");
        warm.setDaemon(true);
        warm.setPriority(Thread.MIN_PRIORITY);
        warm.start();
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
        settings.curseForgeApiKey(key);
        settings.save();
        curseForge.apiKey(settings.curseForgeApiKey());
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
        return modInstaller.installPack(pack, profile.minecraftVersion(), profile.loader(),
                profiles.modsDirectory(profile), progress);
    }

    /** What the launcher installed into this profile, and why. */
    public com.hexadron.launcher.mods.ModLibrary installedMods(Profile profile) {
        return com.hexadron.launcher.mods.ModLibrary
                .read(profiles.modsDirectory(profile))
                .pruneMissingFiles();
    }

    /** Searches the mod platforms for builds matching this profile. */
    public ModProvider.SearchPage searchMods(
            Profile profile, String query, com.hexadron.launcher.mods.ModSort sort,
            ModProvider.Source only, int limitPerProvider, int offset)
            throws IOException, InterruptedException {

        requireModdedLoader(profile);
        return modInstaller.search(query, profile.minecraftVersion(), profile.loader(),
                sort, limitPerProvider, offset, only);
    }

    /** Installs one mod, with its required dependencies, into a profile. */
    public ModInstaller.Result installMod(Profile profile,
                                          com.hexadron.launcher.mods.ModProvider.Source source,
                                          String projectId, String title, Progress progress)
            throws IOException, InterruptedException {

        requireModdedLoader(profile);
        return modInstaller.installMod(source, projectId, title, profile.minecraftVersion(),
                profile.loader(), profiles.modsDirectory(profile), progress);
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
            return new ModInstaller.PackAvailability(false, List.of());
        }
        return modInstaller.checkPack(pack, profile.minecraftVersion(), profile.loader());
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

    /** The skins and capes on this machine, and which account wears which. */
    public SkinStore skins() {
        return skinStore;
    }

    /** Saved sign-ins at third-party skin services, one per account. */
    public SkinCredentials skinCredentials() {
        return skinCredentials;
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

        Account player = ensureFresh(account, progress);

        // Settled here, before anything is built from the account: a remote
        // skin service is played as the account on that service, and that
        // decides the name written into the world, the token that has to be
        // kept off the command line, and the UUID every other player resolves.
        // The settings themselves stay keyed to the account that was selected.
        SkinProfile skin = skinStore.of(account.id());
        player = SkinSession.identity(player, skin, skinCredentials, progress);

        VersionJson version = installProfile(profile, progress);

        Path gameDir = profiles.gameDirectory(profile);
        AssetIndex index = AssetIndex.parse(version.assetsId(),
                com.hexadron.launcher.json.Json.read(dirs.assetIndexFile(version.assetsId())));
        Path assetsDir = versionInstaller.assets().assetsDirFor(index);

        int requiredJava = version.requiredJavaMajor();
        JavaLocator.JavaRuntime java =
                javaRuntimes.resolve(profile.javaPath(), requiredJava, false, progress);
        progress.log("Using %s (this version requires Java %d)", java, requiredJava);

        // Null when the setting is off or the wrapper jar is missing from this
        // build; the builder then falls back to the ordinary command line rather
        // than refusing to launch.
        Path wrapperJar = settings.secureLaunchHandshake()
                ? LaunchWrapperJar.ensureExtracted(dirs)
                : null;
        if (settings.secureLaunchHandshake() && wrapperJar == null && !player.isOffline()) {
            progress.log("Launch wrapper unavailable - the session token will be on the command line "
                    + "for this launch. Do not share JVM crash logs from this session.");
        }

        // Started before the command is built, because the command has to carry
        // the address it listens on. Closed by the exit handler below, so the
        // socket lives exactly as long as the game does.
        SkinSession skins = SkinSession.open(skin, player, skinStore, dirs, progress);

        LaunchCommandBuilder.LaunchCommand command = commandBuilder.build(
                version, profile, player, gameDir, assetsDir, java, wrapperJar, skins.arguments());

        progress.log("Command: %s", command.toLoggableString(player.accessToken()));

        profile.markPlayed();
        profiles.save();

        try {
            return gameLauncher.start(command, onOutput, exit -> {
                skins.close();
                onExit.accept(exit);
            }, progress);
        } catch (IOException | RuntimeException e) {
            // The game never started, so nothing will ever call the exit handler
            // that would have closed it.
            skins.close();
            throw e;
        }
    }
}
