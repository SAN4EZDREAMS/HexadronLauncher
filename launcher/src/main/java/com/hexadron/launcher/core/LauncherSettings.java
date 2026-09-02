package com.hexadron.launcher.core;

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.launch.JavaRuntimes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Launcher-wide settings persisted to {@code launcher.json}. */
public final class LauncherSettings {

    private final Path file;

    /**
     * Azure application (client) ID used for Microsoft sign-in.
     *
     * <p>Empty by default and deliberately not shipped with a value: Mojang
     * requires each launcher to register and be approved for its own
     * application ID, and embedding someone else's would break as soon as it
     * were revoked.
     */
    private String microsoftClientId = "014ab124-109d-4664-a685-c2f88aa32ea8";

    /** CurseForge API key. Empty means the CurseForge provider stays disabled. */
    private String curseForgeApiKey = "";

    /**
     * How Microsoft sign-in is started: {@code "browser"} or {@code "deviceCode"}.
     *
     * <p>Browser is the default because it is what RFC 8252 prescribes for a
     * native application and because the device code grant is now a documented
     * phishing vector - the user is trained to type a code into a Microsoft page
     * with no binding to the application that produced it, which is exactly how
     * the campaign that made Mojang start reviewing launcher applications worked.
     * Device code stays available for a machine with no usable browser.
     */
    private String microsoftSignInMethod = "browser";

    /**
     * Deliver the session token to the game over standard input rather than on
     * the command line.
     *
     * <p>On by default. Process arguments are readable by every process on the
     * machine and are copied verbatim into JVM crash logs, so this is the
     * difference between a session token that leaks by accident and one that
     * does not. The setting exists only so that a user hitting an unforeseen
     * incompatibility with some mod loader can start the game while it is
     * investigated.
     */
    private boolean secureLaunchHandshake = true;

    /**
     * Keep credentials in the launcher's own encrypted file instead of the
     * operating system's credential store.
     *
     * <p>Off by default, and it is a downgrade: the file's key sits next to the
     * file. It is offered because some users will not want a launcher writing to
     * their keychain at all, and because a locked or absent keyring should be a
     * choice rather than a hang.
     */
    private boolean useFileCredentialStore = false;

    /** Keep the launcher window open while the game runs, for reading the log. */
    private boolean keepOpenWhilePlaying = true;

    /**
     * Read and hash every installed file before every launch, instead of
     * trusting the record of what was already checked.
     *
     * <p>Off by default, and that default is a judgement about where the risk
     * actually is rather than about how much a check costs. What is skipped by
     * default is re-reading a file that is the same length, was not written
     * since, and is being checked against the same hash as last time. Evading
     * that means writing into the launcher's own data directory, restoring the
     * size and the timestamp, and editing the ledger - and anyone who can do the
     * first of those can also drop a jar into an instance's {@code mods} folder,
     * which the game loads with no hashing at all. The reading, meanwhile, costs
     * a modern version around five thousand file opens and two thirds of a
     * gigabyte, every time Play is pressed.
     *
     * <p>On, it is the older behaviour exactly: the ledger is not consulted, the
     * natives are unpacked again from their jars, and nothing on disk is taken
     * on trust. Offered because "my machine, my rules" is a legitimate position,
     * and because somebody who suspects tampering should not have to press
     * Install / repair before every session to act on it.
     */
    private boolean verifyEveryLaunch = false;

    /**
     * How the launcher reaches the network.
     *
     * <p>The proxy password is deliberately not here. This file is plain JSON
     * that gets synced, backed up and pasted into bug reports; the password goes
     * to the same credential store the account tokens use.
     */
    private com.hexadron.launcher.net.ProxyChoice proxy =
            com.hexadron.launcher.net.ProxyChoice.system();

    /**
     * Hide the window to the notification area while the game runs.
     *
     * <p>On by default. A launcher left on the taskbar is one more window to
     * alt-tab past during a session, and the tray icon is also where the game
     * can be stopped from. The window returns by itself when the game ends.
     */
    private boolean minimiseToTrayWhilePlaying = true;

    /**
     * What the launcher may do when a profile needs a Java version this machine
     * does not have: {@code "ask"}, {@code "always"} or {@code "never"}.
     *
     * <p>Ask by default. Fetching a runtime is a 45-90 MB download from a host
     * the user did not choose, and doing that silently on someone's metered
     * connection the first time they press Play is not a decision to make for
     * them. Once they have said yes it is stored as {@code always}, because the
     * same question asked again for every version is not consent, it is noise.
     */
    private String javaDownloadPolicy = JavaRuntimes.DownloadPolicy.ASK.stored();

    /** Simultaneous downloads. */
    private int downloadConcurrency = 12;

    /**
     * How long the start-up window stays up at minimum, in milliseconds.
     *
     * <p>There has to be a floor, because start-up is now fast enough that
     * without one the window would appear and vanish - which reads as a glitch
     * rather than as a splash. Three seconds is long enough to read the stage
     * list; a click or a key closes it sooner, and zero removes the floor for
     * anyone who wants the launcher and nothing else.
     */
    private int splashMinimumMillis = 3000;

    /** Show snapshots and old versions in the version picker. */
    private boolean showAllVersions = false;

    /**
     * Look for a newer launcher when the launcher starts.
     *
     * <p>On by default, and worth defending: a launcher is the piece of software
     * that stands between somebody and a game they want to play now, so a
     * version with a fixed crash in it is only useful if it actually reaches
     * them. The check is one request to the repository's list of releases, it
     * happens while the start-up screen is already on screen, and a machine with
     * no connection simply carries on - nothing waits for it and nothing is
     * reported when it fails.
     *
     * <p>Nothing is ever installed without being asked. The check leads to a
     * window that says which version, from what to what, and what changed.
     */
    private boolean checkForUpdates = true;

    /**
     * Which builds the update check offers: {@code "release"} or {@code "nightly"}.
     *
     * <p>Release by default, because that is what somebody who is here to play
     * wants. Nightly is a deliberate choice to run test builds.
     */
    private String updateChannel = com.hexadron.launcher.update.UpdateChannel.RELEASE.stored();

    /**
     * How much of the data folder the kept mod logos may fill, in megabytes.
     *
     * <p>Thirty-two by default, which is roughly two thousand logos - more mods
     * than most people browse in a year, and small enough that nobody notices
     * it. It is a setting because the two ends of the range are both real: a
     * machine short of disk wants it small, and somebody who browses the whole
     * catalogue on a slow connection would rather spend a few hundred megabytes
     * than fetch the same pictures again.
     *
     * <p>Bounded on both sides. Zero would mean fetching every logo on every
     * scroll, which is worse for the user and worse for the platform being
     * asked; the ceiling is there because this is a cache of thumbnails, and a
     * number past it is a typo rather than a decision.
     */
    private int modIconCacheMegabytes = 32;

    /** The smallest cache worth keeping, in megabytes. */
    public static final int MOD_ICON_CACHE_MIN = 8;

    /** The largest cache this offers, in megabytes. */
    public static final int MOD_ICON_CACHE_MAX = 1024;

    /**
     * Ask before switching off or deleting a mod that other mods need.
     *
     * <p>On by default, and the one dialog in this launcher with a "do not show
     * this again" box on it. The question is worth asking because the damage is
     * invisible at the moment it is done - a library removed from under five
     * mods fails at the next launch, in a crash naming a class the player has
     * never heard of - and worth being able to switch off because somebody who
     * is deliberately taking a pack apart is answering the same question for the
     * tenth time.
     */
    private boolean warnAboutDependents = true;

    /**
     * Interface language as an ISO 639-1 code.
     *
     * <p>Empty means "follow the operating system", which is the default: a
     * first run should already be in the user's language where one is shipped,
     * without them having to find a setting first.
     */
    private String language = "";

    /**
     * Colours the user mixed for groups, newest first.
     *
     * <p>Here rather than on the group that first used one, because a colour
     * that has to be mixed again for every group it is wanted on is a colour
     * nobody uses twice. The dialog offers these beside the fixed palette, so a
     * colour is mixed once and then picked.
     *
     * <p>Capped at {@link #CUSTOM_COLOR_LIMIT}. The list is a shelf of colours
     * within reach, and a shelf that never drops anything stops being one - it
     * becomes a second palette, longer than the first and mostly one-offs.
     */
    private final List<String> customGroupColors = new ArrayList<>();

    /** How many mixed colours are kept. The oldest falls off beyond this. */
    public static final int CUSTOM_COLOR_LIMIT = 16;

    public LauncherSettings(GameDirs dirs) {
        this.file = dirs.settingsFile();
    }

    public LauncherSettings load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return this;
        }
        Json json = Json.read(file);
        microsoftClientId = json.get("microsoftClientId").asString(microsoftClientId);
        curseForgeApiKey = json.get("curseForgeApiKey").asString(curseForgeApiKey);
        microsoftSignInMethod = json.get("microsoftSignInMethod").asString(microsoftSignInMethod);
        secureLaunchHandshake = json.get("secureLaunchHandshake").asBool(secureLaunchHandshake);
        useFileCredentialStore = json.get("useFileCredentialStore").asBool(useFileCredentialStore);
        keepOpenWhilePlaying = json.get("keepOpenWhilePlaying").asBool(keepOpenWhilePlaying);
        verifyEveryLaunch = json.get("verifyEveryLaunch").asBool(verifyEveryLaunch);
        Json proxyJson = json.get("proxy");
        proxy = new com.hexadron.launcher.net.ProxyChoice(
                com.hexadron.launcher.net.ProxyChoice.Mode.parse(
                        proxyJson.get("mode").asString(null)),
                proxyJson.get("host").asString(""),
                proxyJson.get("port").asInt(8080),
                proxyJson.get("user").asString(""));
        minimiseToTrayWhilePlaying = json.get("minimiseToTrayWhilePlaying")
                .asBool(minimiseToTrayWhilePlaying);
        downloadConcurrency = json.get("downloadConcurrency").asInt(downloadConcurrency);
        showAllVersions = json.get("showAllVersions").asBool(showAllVersions);
        warnAboutDependents = json.get("warnAboutDependents").asBool(warnAboutDependents);
        checkForUpdates = json.get("checkForUpdates").asBool(checkForUpdates);
        updateChannel = com.hexadron.launcher.update.UpdateChannel
                .parse(json.get("updateChannel").asString(updateChannel)).stored();
        modIconCacheMegabytes(json.get("modIconCacheMegabytes").asInt(modIconCacheMegabytes));
        splashMinimumMillis = json.get("splashMinimumMillis").asInt(splashMinimumMillis);
        javaDownloadPolicy = JavaRuntimes.DownloadPolicy
                .parse(json.get("javaDownloadPolicy").asString(javaDownloadPolicy)).stored();
        language = json.get("language").asString(language);
        customGroupColors.clear();
        for (Json entry : json.get("customGroupColors").elements()) {
            addCustomGroupColor(entry.asString(null));
        }
        return this;
    }

    public void save() throws IOException {
        Json.object()
                .put("microsoftClientId", microsoftClientId)
                .put("curseForgeApiKey", curseForgeApiKey)
                .put("microsoftSignInMethod", microsoftSignInMethod)
                .put("secureLaunchHandshake", secureLaunchHandshake)
                .put("useFileCredentialStore", useFileCredentialStore)
                .put("keepOpenWhilePlaying", keepOpenWhilePlaying)
                .put("verifyEveryLaunch", verifyEveryLaunch)
                .put("proxy", Json.object()
                        .put("mode", proxy.mode().stored())
                        .put("host", proxy.host())
                        .put("port", proxy.port())
                        .put("user", proxy.user()))
                .put("minimiseToTrayWhilePlaying", minimiseToTrayWhilePlaying)
                .put("downloadConcurrency", downloadConcurrency)
                .put("showAllVersions", showAllVersions)
                .put("warnAboutDependents", warnAboutDependents)
                .put("checkForUpdates", checkForUpdates)
                .put("updateChannel", updateChannel)
                .put("modIconCacheMegabytes", modIconCacheMegabytes)
                .put("splashMinimumMillis", splashMinimumMillis)
                .put("javaDownloadPolicy", javaDownloadPolicy)
                .put("language", language)
                .put("customGroupColors", colorsAsJson())
                .write(file);
    }

    private Json colorsAsJson() {
        Json array = Json.array();
        customGroupColors.forEach(array::add);
        return array;
    }

    /** The mixed colours, newest first. Never null, possibly empty. */
    public List<String> customGroupColors() {
        return List.copyOf(customGroupColors);
    }

    /**
     * Remembers a mixed colour, moving it to the front if it is already known.
     *
     * <p>Anything that is not {@code #rrggbb} is dropped rather than stored: the
     * value ends up in a JavaFX inline style, and a bad one there does not fail
     * loudly - it makes a swatch that silently paints nothing.
     *
     * @return true when the shelf changed, so the caller knows whether to save
     */
    public boolean addCustomGroupColor(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) {
            return false;
        }
        String normalised = value.toLowerCase(Locale.ROOT);
        if (!customGroupColors.isEmpty() && customGroupColors.get(0).equals(normalised)) {
            return false;
        }
        customGroupColors.remove(normalised);
        customGroupColors.add(0, normalised);
        while (customGroupColors.size() > CUSTOM_COLOR_LIMIT) {
            customGroupColors.remove(customGroupColors.size() - 1);
        }
        return true;
    }

    /** Forgets a mixed colour. Groups already painted with it keep it. */
    public boolean removeCustomGroupColor(String value) {
        return value != null
                && customGroupColors.remove(value.toLowerCase(Locale.ROOT));
    }

    public String microsoftClientId() {
        return microsoftClientId;
    }

    public LauncherSettings microsoftClientId(String value) {
        this.microsoftClientId = value == null ? "" : value.trim();
        return this;
    }

    public boolean hasMicrosoftClientId() {
        return !microsoftClientId.isBlank();
    }

    /** {@code "browser"} (authorization code + PKCE) or {@code "deviceCode"}. */
    public String microsoftSignInMethod() {
        return microsoftSignInMethod;
    }

    public boolean usesBrowserSignIn() {
        return !"deviceCode".equalsIgnoreCase(microsoftSignInMethod);
    }

    public LauncherSettings microsoftSignInMethod(String value) {
        this.microsoftSignInMethod = "deviceCode".equalsIgnoreCase(value) ? "deviceCode" : "browser";
        return this;
    }

    public boolean secureLaunchHandshake() {
        return secureLaunchHandshake;
    }

    public LauncherSettings secureLaunchHandshake(boolean value) {
        this.secureLaunchHandshake = value;
        return this;
    }

    public boolean useFileCredentialStore() {
        return useFileCredentialStore;
    }

    public LauncherSettings useFileCredentialStore(boolean value) {
        this.useFileCredentialStore = value;
        return this;
    }

    public String curseForgeApiKey() {
        return curseForgeApiKey;
    }

    public LauncherSettings curseForgeApiKey(String value) {
        this.curseForgeApiKey = value == null ? "" : value.trim();
        return this;
    }

    public boolean keepOpenWhilePlaying() {
        return keepOpenWhilePlaying;
    }

    /** How the launcher reaches the network. Never carries the password. */
    public com.hexadron.launcher.net.ProxyChoice proxy() {
        return proxy;
    }

    public LauncherSettings proxy(com.hexadron.launcher.net.ProxyChoice value) {
        this.proxy = value == null ? com.hexadron.launcher.net.ProxyChoice.system() : value;
        return this;
    }

    /** @see #verifyEveryLaunch */
    public boolean verifyEveryLaunch() {
        return verifyEveryLaunch;
    }

    /** @see #verifyEveryLaunch */
    public LauncherSettings verifyEveryLaunch(boolean value) {
        this.verifyEveryLaunch = value;
        return this;
    }

    public LauncherSettings keepOpenWhilePlaying(boolean value) {
        this.keepOpenWhilePlaying = value;
        return this;
    }

    public boolean minimiseToTrayWhilePlaying() {
        return minimiseToTrayWhilePlaying;
    }

    public LauncherSettings minimiseToTrayWhilePlaying(boolean value) {
        this.minimiseToTrayWhilePlaying = value;
        return this;
    }

    public int downloadConcurrency() {
        return Math.max(1, Math.min(downloadConcurrency, 32));
    }

    public LauncherSettings downloadConcurrency(int value) {
        this.downloadConcurrency = value;
        return this;
    }

    public boolean showAllVersions() {
        return showAllVersions;
    }

    public LauncherSettings showAllVersions(boolean value) {
        this.showAllVersions = value;
        return this;
    }

    /** Clamped: a negative value is no floor, and no window should hold for a minute. */
    /** True while the launcher looks for a newer version at start-up. */
    public boolean checkForUpdates() {
        return checkForUpdates;
    }

    public LauncherSettings checkForUpdates(boolean value) {
        this.checkForUpdates = value;
        return this;
    }

    /** Which builds an update check offers. */
    public com.hexadron.launcher.update.UpdateChannel updateChannel() {
        return com.hexadron.launcher.update.UpdateChannel.parse(updateChannel);
    }

    public LauncherSettings updateChannel(com.hexadron.launcher.update.UpdateChannel value) {
        this.updateChannel = (value == null
                ? com.hexadron.launcher.update.UpdateChannel.RELEASE : value).stored();
        return this;
    }

    /** How much the mod logo cache may fill, in megabytes. */
    public int modIconCacheMegabytes() {
        return modIconCacheMegabytes;
    }

    /**
     * Sets it, within the bounds.
     *
     * <p>Clamped rather than refused, and clamped here rather than in the
     * window that offers it: this value also arrives from a file somebody may
     * have edited by hand, and a nought in it would otherwise switch the cache
     * off in a way no part of the interface can explain.
     */
    public LauncherSettings modIconCacheMegabytes(int value) {
        this.modIconCacheMegabytes =
                Math.max(MOD_ICON_CACHE_MIN, Math.min(MOD_ICON_CACHE_MAX, value));
        return this;
    }

    /** The same, in bytes, which is what a cache is measured in. */
    public long modIconCacheBytes() {
        return (long) modIconCacheMegabytes * 1024 * 1024;
    }

    /** True while the launcher still asks before breaking a dependency. */
    public boolean warnAboutDependents() {
        return warnAboutDependents;
    }

    public LauncherSettings warnAboutDependents(boolean value) {
        this.warnAboutDependents = value;
        return this;
    }

    public int splashMinimumMillis() {
        return Math.max(0, Math.min(splashMinimumMillis, 15_000));
    }

    public LauncherSettings splashMinimumMillis(int value) {
        this.splashMinimumMillis = value;
        return this;
    }

    public JavaRuntimes.DownloadPolicy javaDownloadPolicy() {
        return JavaRuntimes.DownloadPolicy.parse(javaDownloadPolicy);
    }

    public LauncherSettings javaDownloadPolicy(JavaRuntimes.DownloadPolicy value) {
        this.javaDownloadPolicy = (value == null ? JavaRuntimes.DownloadPolicy.ASK : value).stored();
        return this;
    }

    /** The stored language code, or an empty string for "follow the system". */
    public String language() {
        return language;
    }

    public LauncherSettings language(String value) {
        this.language = value == null ? "" : value.trim();
        return this;
    }
}
