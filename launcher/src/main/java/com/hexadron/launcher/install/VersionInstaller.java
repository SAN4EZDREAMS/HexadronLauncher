package com.hexadron.launcher.install;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.meta.Artifact;
import com.hexadron.launcher.meta.Library;
import com.hexadron.launcher.meta.VersionJson;
import com.hexadron.launcher.meta.VersionManifest;
import com.hexadron.launcher.meta.VersionResolver;
import com.hexadron.launcher.net.DownloadTask;
import com.hexadron.launcher.net.Downloader;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.util.Hashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Installs everything a version needs to run: its manifest chain, the client
 * jar, every applicable library and native, and the asset set.
 *
 * <p>Fully incremental. Re-running against a complete install verifies hashes
 * and downloads nothing, which is also the repair path when a user reports a
 * corrupt jar.
 */
public final class VersionInstaller {

    /**
     * Fallback maven mirrors tried when a library's primary URL fails.
     * Forge's own maven is the usual outage; Central carries most of the same
     * third-party artifacts.
     */
    private static final List<String> LIBRARY_MIRRORS = List.of(
            "https://libraries.minecraft.net/",
            "https://maven.minecraftforge.net/",
            "https://maven.neoforged.net/releases/",
            "https://maven.fabricmc.net/",
            "https://repo1.maven.org/maven2/");

    private final GameDirs dirs;
    private final Downloader downloader;
    private final VersionResolver resolver;
    private final AssetInstaller assetInstaller;

    public VersionInstaller(GameDirs dirs, Downloader downloader) {
        this.dirs = dirs;
        this.downloader = downloader;
        this.resolver = new VersionResolver(dirs);
        this.assetInstaller = new AssetInstaller(dirs, downloader);
    }

    public VersionResolver resolver() {
        return resolver;
    }

    public AssetInstaller assets() {
        return assetInstaller;
    }

    /**
     * Downloads a vanilla version manifest into {@code versions/<id>/<id>.json}
     * if it is not already there, verifying the SHA-1 Mojang publishes for it.
     */
    public void ensureVanillaVersionJson(String versionId, VersionManifest manifest, Progress progress)
            throws IOException, InterruptedException {
        Path target = dirs.versionJson(versionId);
        VersionManifest.Entry entry = manifest.find(versionId)
                .orElseThrow(() -> new IOException("unknown Minecraft version: " + versionId));

        if (Files.isRegularFile(target) && Hashes.matchesSha1(target, entry.sha1())) {
            return;
        }
        progress.log("Fetching version manifest for " + versionId);
        downloader.fetch(DownloadTask.of(entry.url(), target, entry.sha1(), -1, versionId + ".json"));
    }

    /**
     * Writes a loader-produced manifest (Fabric, Quilt, Forge, NeoForge) to disk
     * in the standard location so the resolver can pick it up.
     */
    public void writeVersionJson(String versionId, Json versionJson) throws IOException {
        versionJson.write(dirs.versionJson(versionId));
    }

    /**
     * Full install of {@code versionId}, following its {@code inheritsFrom}
     * chain and fetching any missing vanilla link from the manifest.
     *
     * @param gameDir profile game directory, used only by legacy asset layouts
     * @return the flattened manifest, ready for the launch builder
     */
    public VersionJson install(String versionId, Path gameDir, Progress progress)
            throws IOException, InterruptedException {

        progress.stage("Resolving " + versionId);
        VersionManifest manifest = null;

        // Walk the chain, pulling any missing vanilla manifest as we go.
        Set<String> visited = new LinkedHashSet<>();
        String current = versionId;
        while (current != null) {
            if (!visited.add(current)) {
                throw new IOException("inheritsFrom cycle at " + current);
            }
            if (!resolver.isInstalled(current)) {
                if (manifest == null) {
                    manifest = VersionManifest.fetch(dirs);
                }
                ensureVanillaVersionJson(current, manifest, progress);
            }
            VersionJson link = resolver.load(current);
            current = link.hasParent() ? link.inheritsFrom() : null;
        }

        VersionJson version = resolver.resolve(versionId);

        List<DownloadTask> tasks = new ArrayList<>();
        collectClientJar(version, tasks);
        collectLibraries(version, tasks, progress);

        progress.stage("Downloading client and libraries");
        downloader.run(tasks, progress);

        NativesExtractor.extractAll(
                version.libraries(),
                lib -> {
                    Artifact nativeArtifact = lib.nativeArtifact();
                    return nativeArtifact == null ? null
                            : dirs.library(nativeArtifact.path() != null
                                    ? nativeArtifact.path()
                                    : lib.coordinate().withClassifier(lib.nativeClassifierForThisHost()).path());
                },
                dirs.natives(version.id()),
                progress);

        assetInstaller.install(version, gameDir, progress);

        progress.stage("Install complete");
        return version;
    }

    // ---------------------------------------------------------------- task building

    private void collectClientJar(VersionJson version, List<DownloadTask> tasks) throws IOException {
        Artifact client = version.clientDownload();
        Path jar = dirs.versionJar(version.jarVersionId());

        if (client == null || !client.hasUrl()) {
            if (!Files.isRegularFile(jar)) {
                throw new IOException("version " + version.id()
                        + " publishes no client download and " + jar + " is absent");
            }
            return;
        }
        tasks.add(DownloadTask.of(client.url(), jar, client.sha1(), client.size(),
                version.jarVersionId() + ".jar"));
    }

    private void collectLibraries(VersionJson version, List<DownloadTask> tasks, Progress progress) {
        for (Library library : version.libraries()) {
            if (!library.appliesToThisHost()) {
                continue;
            }

            Artifact classpathArtifact = library.classpathArtifact();
            if (classpathArtifact != null) {
                String path = classpathArtifact.path() != null
                        ? classpathArtifact.path()
                        : library.coordinate().path();
                addLibraryTask(tasks, path, classpathArtifact, library, progress);
            }

            Artifact nativeArtifact = library.nativeArtifact();
            if (nativeArtifact != null) {
                String classifier = library.nativeClassifierForThisHost();
                String path = nativeArtifact.path() != null
                        ? nativeArtifact.path()
                        : library.coordinate().withClassifier(classifier).path();
                addLibraryTask(tasks, path, nativeArtifact, library, progress);
            }
        }
    }

    private void addLibraryTask(List<DownloadTask> tasks, String relativePath, Artifact artifact,
                                Library library, Progress progress) {
        Path destination = dirs.library(relativePath);

        List<String> urls = new ArrayList<>();
        if (artifact.hasUrl()) {
            urls.add(artifact.url());
        }
        for (String mirror : LIBRARY_MIRRORS) {
            String candidate = mirror + relativePath;
            if (!urls.contains(candidate)) {
                urls.add(candidate);
            }
        }

        if (urls.isEmpty()) {
            // No source at all. Legitimate for Forge libraries produced by the
            // installer's processors, which must already be on disk by now.
            if (!Files.isRegularFile(destination)) {
                progress.log("no download source for " + library.name() + " and it is not on disk");
            }
            return;
        }

        tasks.add(new DownloadTask(urls, destination, artifact.sha1(), artifact.size(),
                library.name(), false));
    }

    /** Best-effort probe used by the UI to show whether a version is ready to play. */
    public boolean isFullyInstalled(String versionId) {
        try {
            VersionJson version = resolver.resolve(versionId);
            if (!Files.isRegularFile(dirs.versionJar(version.jarVersionId()))) {
                return false;
            }
            for (Library library : version.libraries()) {
                if (!library.appliesToThisHost()) {
                    continue;
                }
                Artifact artifact = library.classpathArtifact();
                if (artifact == null) {
                    continue;
                }
                String path = artifact.path() != null ? artifact.path() : library.coordinate().path();
                if (!Files.isRegularFile(dirs.library(path))) {
                    return false;
                }
            }
            return Files.isRegularFile(dirs.assetIndexFile(version.assetsId()));
        } catch (IOException e) {
            return false;
        }
    }

    /** Exposed so loader installers can reuse the shared HTTP stack. */
    public static String fetchString(String url) throws IOException, InterruptedException {
        return Http.getString(url);
    }
}
