package com.hexadron.launcher.core;

import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Canonical on-disk layout.
 *
 * <p>Deliberately mirrors the official launcher's shared-store layout so that
 * artifacts are downloaded once and reused by every profile:
 *
 * <pre>
 * &lt;root&gt;/
 *   launcher.json            launcher settings
 *   accounts.json            saved accounts (owner-readable only; see AccountStore)
 *   profiles.json            profile/instance definitions
 *   versions/&lt;id&gt;/&lt;id&gt;.json  one version manifest per installed version
 *   versions/&lt;id&gt;/&lt;id&gt;.jar   client jar
 *   libraries/&lt;maven path&gt;   shared library store
 *   assets/indexes/&lt;id&gt;.json
 *   assets/objects/&lt;xx&gt;/&lt;hash&gt;
 *   assets/virtual/&lt;id&gt;/     materialised assets for pre-1.7 versions
 *   natives/&lt;id&gt;/            extracted native libraries per version
 *   java/&lt;component&gt;/        launcher-managed Java runtimes
 *   instances/&lt;profile&gt;/     per-profile game directory (mods, saves, config)
 *   cache/                   metadata cache
 * </pre>
 *
 * <p>Each profile gets its own game directory under {@code instances/} so that
 * a Fabric 26.2 profile and a NeoForge 26.1 profile cannot corrupt each other's
 * mods, configs or worlds.
 */
public final class GameDirs {

    private final Path root;

    public GameDirs(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Default root, following each platform's convention for application data. */
    public static GameDirs defaultDirs() {
        return new GameDirs(defaultRoot());
    }

    public static Path defaultRoot() {
        String override = System.getProperty("hexadron.root");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String home = System.getProperty("user.home", ".");
        return switch (Platform.os()) {
            case WINDOWS -> {
                String appData = System.getenv("APPDATA");
                Path base = (appData == null || appData.isBlank()) ? Paths.get(home) : Paths.get(appData);
                yield base.resolve(".hexadronlauncher");
            }
            case OSX -> Paths.get(home, "Library", "Application Support", "hexadronlauncher");
            case LINUX -> {
                String xdg = System.getenv("XDG_DATA_HOME");
                Path base = (xdg == null || xdg.isBlank()) ? Paths.get(home, ".local", "share") : Paths.get(xdg);
                yield base.resolve("hexadronlauncher");
            }
        };
    }

    public Path root() {
        return root;
    }

    public Path settingsFile() {
        return root.resolve("launcher.json");
    }

    public Path accountsFile() {
        return root.resolve("accounts.json");
    }

    public Path profilesFile() {
        return root.resolve("profiles.json");
    }

    public Path versions() {
        return root.resolve("versions");
    }

    public Path versionDir(String versionId) {
        return versions().resolve(versionId);
    }

    public Path versionJson(String versionId) {
        return versionDir(versionId).resolve(versionId + ".json");
    }

    public Path versionJar(String versionId) {
        return versionDir(versionId).resolve(versionId + ".jar");
    }

    public Path libraries() {
        return root.resolve("libraries");
    }

    /** Absolute path of a library given its repository-relative path. */
    public Path library(String relativePath) {
        return libraries().resolve(relativePath.replace('/', java.io.File.separatorChar));
    }

    public Path assets() {
        return root.resolve("assets");
    }

    public Path assetIndexes() {
        return assets().resolve("indexes");
    }

    public Path assetIndexFile(String indexId) {
        return assetIndexes().resolve(indexId + ".json");
    }

    public Path assetObjects() {
        return assets().resolve("objects");
    }

    /** Object store path for a content hash: {@code objects/<first two hex chars>/<hash>}. */
    public Path assetObject(String hash) {
        String normalised = hash.toLowerCase(Locale.ROOT);
        return assetObjects().resolve(normalised.substring(0, 2)).resolve(normalised);
    }

    /** Materialised asset tree used by pre-1.7 versions ("virtual" asset indexes). */
    public Path virtualAssets(String indexId) {
        return assets().resolve("virtual").resolve(indexId);
    }

    public Path natives(String versionId) {
        return root.resolve("natives").resolve(versionId);
    }

    public Path javaRuntimes() {
        return root.resolve("java");
    }

    public Path javaRuntime(String component) {
        return javaRuntimes().resolve(component);
    }

    public Path instances() {
        return root.resolve("instances");
    }

    public Path instance(String profileId) {
        return instances().resolve(profileId);
    }

    public Path cache() {
        return root.resolve("cache");
    }

    public Path logs() {
        return root.resolve("logs");
    }

    /** Creates the directories that must exist before anything else runs. */
    public GameDirs createBaseDirectories() throws IOException {
        for (Path p : new Path[]{root, versions(), libraries(), assetIndexes(), assetObjects(),
                instances(), cache(), logs(), javaRuntimes()}) {
            Files.createDirectories(p);
        }
        return this;
    }
}
