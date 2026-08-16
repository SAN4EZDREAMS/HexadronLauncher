package com.hexadron.launcher.core;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.auth.AccountStore;
import com.hexadron.launcher.auth.MicrosoftAuth;
import com.hexadron.launcher.install.VersionInstaller;
import com.hexadron.launcher.install.loader.LoaderInstaller;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.install.loader.LoaderVersion;
import com.hexadron.launcher.install.loader.Loaders;
import com.hexadron.launcher.launch.GameLauncher;
import com.hexadron.launcher.launch.JavaLocator;
import com.hexadron.launcher.launch.LaunchCommandBuilder;
import com.hexadron.launcher.meta.AssetIndex;
import com.hexadron.launcher.meta.VersionJson;
import com.hexadron.launcher.meta.VersionManifest;
import com.hexadron.launcher.mods.CurseForgeProvider;
import com.hexadron.launcher.mods.ModInstaller;
import com.hexadron.launcher.mods.ModPack;
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.mods.ModrinthProvider;
import com.hexadron.launcher.net.Downloader;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileStore;

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
    private final VersionInstaller versionInstaller;
    private final ProfileStore profiles;
    private final AccountStore accounts;
    private final JavaLocator javaLocator;
    private final LaunchCommandBuilder commandBuilder;
    private final GameLauncher gameLauncher = new GameLauncher();
    private final ModrinthProvider modrinth = new ModrinthProvider();
    private final CurseForgeProvider curseForge;
    private final ModInstaller modInstaller;

    public LauncherService(GameDirs dirs, LauncherSettings settings) throws IOException {
        this.dirs = dirs.createBaseDirectories();
        this.settings = settings;
        this.downloader = new Downloader(settings.downloadConcurrency());
        this.versionInstaller = new VersionInstaller(dirs, downloader);
        this.profiles = new ProfileStore(dirs).load();
        this.accounts = new AccountStore(dirs).load();
        this.javaLocator = new JavaLocator(dirs);
        this.commandBuilder = new LaunchCommandBuilder(dirs);
        this.curseForge = CurseForgeProvider.fromEnvironment(settings.curseForgeApiKey());
        this.modInstaller = new ModInstaller(downloader, modrinth, curseForge);
    }

    /** Builds a service rooted at the default location. */
    public static LauncherService createDefault() throws IOException {
        GameDirs dirs = GameDirs.defaultDirs();
        LauncherSettings settings = new LauncherSettings(dirs).load();
        return new LauncherService(dirs, settings);
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

    public JavaLocator javaLocator() {
        return javaLocator;
    }

    public VersionInstaller versionInstaller() {
        return versionInstaller;
    }

    public List<ModProvider> modProviders() {
        return List.of(modrinth, curseForge);
    }

    // ---------------------------------------------------------------- versions

    public VersionManifest minecraftVersions() throws IOException, InterruptedException {
        return VersionManifest.fetch(dirs);
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
     */
    public VersionJson installProfile(Profile profile, Progress progress)
            throws IOException, InterruptedException {

        if (profile.minecraftVersion() == null || profile.minecraftVersion().isBlank()) {
            throw new IOException("profile '" + profile.name() + "' has no Minecraft version set");
        }

        Path gameDir = profiles.gameDirectory(profile);
        String versionId = profile.minecraftVersion();

        if (profile.loader() != LoaderType.VANILLA) {
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

        VersionJson version = versionInstaller.install(versionId, gameDir, progress);
        profile.versionId(versionId);
        profiles.save();
        return version;
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
    public List<com.hexadron.launcher.mods.ModProvider.SearchResult> searchMods(
            Profile profile, String query, com.hexadron.launcher.mods.ModSort sort,
            com.hexadron.launcher.mods.ModProvider.Source only, int limitPerProvider)
            throws IOException, InterruptedException {

        requireModdedLoader(profile);
        return modInstaller.search(query, profile.minecraftVersion(), profile.loader(),
                sort, limitPerProvider, only);
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
        VersionJson version = installProfile(profile, progress);

        Path gameDir = profiles.gameDirectory(profile);
        AssetIndex index = AssetIndex.parse(version.assetsId(),
                com.hexadron.launcher.json.Json.read(dirs.assetIndexFile(version.assetsId())));
        Path assetsDir = versionInstaller.assets().assetsDirFor(index);

        int requiredJava = version.requiredJavaMajor();
        JavaLocator.JavaRuntime java = javaLocator.locate(profile.javaPath(), requiredJava);
        progress.log("Using %s (this version requires Java %d)", java, requiredJava);

        LaunchCommandBuilder.LaunchCommand command =
                commandBuilder.build(version, profile, player, gameDir, assetsDir, java);

        progress.log("Command: %s", command.toLoggableString(player.accessToken()));

        profile.markPlayed();
        profiles.save();

        return gameLauncher.start(command, onOutput, onExit, progress);
    }
}
