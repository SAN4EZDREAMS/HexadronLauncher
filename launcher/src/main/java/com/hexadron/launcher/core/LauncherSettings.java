package com.hexadron.launcher.core;

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.launch.JavaRuntimes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
     * Interface language as an ISO 639-1 code.
     *
     * <p>Empty means "follow the operating system", which is the default: a
     * first run should already be in the user's language where one is shipped,
     * without them having to find a setting first.
     */
    private String language = "";

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
        minimiseToTrayWhilePlaying = json.get("minimiseToTrayWhilePlaying")
                .asBool(minimiseToTrayWhilePlaying);
        downloadConcurrency = json.get("downloadConcurrency").asInt(downloadConcurrency);
        showAllVersions = json.get("showAllVersions").asBool(showAllVersions);
        splashMinimumMillis = json.get("splashMinimumMillis").asInt(splashMinimumMillis);
        javaDownloadPolicy = JavaRuntimes.DownloadPolicy
                .parse(json.get("javaDownloadPolicy").asString(javaDownloadPolicy)).stored();
        language = json.get("language").asString(language);
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
                .put("minimiseToTrayWhilePlaying", minimiseToTrayWhilePlaying)
                .put("downloadConcurrency", downloadConcurrency)
                .put("showAllVersions", showAllVersions)
                .put("splashMinimumMillis", splashMinimumMillis)
                .put("javaDownloadPolicy", javaDownloadPolicy)
                .put("language", language)
                .write(file);
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
