package com.hexadron.launcher.core;

import com.hexadron.launcher.json.Json;

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
    private String microsoftClientId = "";

    /** CurseForge API key. Empty means the CurseForge provider stays disabled. */
    private String curseForgeApiKey = "";

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

    /** Simultaneous downloads. */
    private int downloadConcurrency = 12;

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
        keepOpenWhilePlaying = json.get("keepOpenWhilePlaying").asBool(keepOpenWhilePlaying);
        minimiseToTrayWhilePlaying = json.get("minimiseToTrayWhilePlaying")
                .asBool(minimiseToTrayWhilePlaying);
        downloadConcurrency = json.get("downloadConcurrency").asInt(downloadConcurrency);
        showAllVersions = json.get("showAllVersions").asBool(showAllVersions);
        language = json.get("language").asString(language);
        return this;
    }

    public void save() throws IOException {
        Json.object()
                .put("microsoftClientId", microsoftClientId)
                .put("curseForgeApiKey", curseForgeApiKey)
                .put("keepOpenWhilePlaying", keepOpenWhilePlaying)
                .put("minimiseToTrayWhilePlaying", minimiseToTrayWhilePlaying)
                .put("downloadConcurrency", downloadConcurrency)
                .put("showAllVersions", showAllVersions)
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

    /** The stored language code, or an empty string for "follow the system". */
    public String language() {
        return language;
    }

    public LauncherSettings language(String value) {
        this.language = value == null ? "" : value.trim();
        return this;
    }
}
