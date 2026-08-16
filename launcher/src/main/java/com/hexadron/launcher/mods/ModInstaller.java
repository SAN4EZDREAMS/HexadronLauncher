package com.hexadron.launcher.mods;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.DownloadTask;
import com.hexadron.launcher.net.Downloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves and installs mods into a profile's {@code mods} directory.
 *
 * <p>Keeps a lock file ({@code mods/.hexadron-mods.json}) recording which file
 * came from which project, so mods can be updated and removed without guessing
 * from filenames, and so files the user dropped in by hand are never touched.
 */
public final class ModInstaller {

    private static final String LOCK_FILE = ".hexadron-mods.json";
    private static final int MAX_DEPENDENCY_DEPTH = 6;

    private final Map<ModProvider.Source, ModProvider> providers = new LinkedHashMap<>();
    private final Downloader downloader;

    public ModInstaller(Downloader downloader, ModProvider... providers) {
        this.downloader = downloader;
        for (ModProvider provider : providers) {
            this.providers.put(provider.source(), provider);
        }
    }

    /** Outcome of an install run. */
    public record Result(List<ModFile> installed, List<String> skipped, List<String> manualDownloads) {
        public boolean isClean() {
            return skipped.isEmpty() && manualDownloads.isEmpty();
        }
    }

    /**
     * Installs a pack into {@code modsDir}, resolving required dependencies
     * transitively.
     */
    public Result installPack(ModPack pack, String minecraftVersion, LoaderType loader,
                              Path modsDir, Progress progress) throws IOException, InterruptedException {

        progress.stage("Resolving " + pack.name());
        Files.createDirectories(modsDir);

        Map<String, ModFile> resolved = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();

        Deque<Pending> queue = new ArrayDeque<>();
        for (ModPack.Entry entry : pack.entries()) {
            queue.add(new Pending(entry.provider(), entry.projectId(), entry.versionId(),
                    entry.label(), entry.optional(), 0));
        }

        while (!queue.isEmpty()) {
            Pending pending = queue.poll();
            String key = pending.provider + ":" + pending.projectId;
            if (!visited.add(key)) {
                continue;
            }
            if (pending.depth > MAX_DEPENDENCY_DEPTH) {
                skipped.add(pending.label + " (dependency chain deeper than " + MAX_DEPENDENCY_DEPTH + ")");
                continue;
            }

            ModProvider provider = providers.get(pending.provider);
            if (provider == null || !provider.isAvailable()) {
                String reason = provider == null
                        ? "provider not configured"
                        : provider.source().displayName() + " is not configured (missing API key)";
                if (pending.optional) {
                    skipped.add(pending.label + " (" + reason + ")");
                    continue;
                }
                throw new IOException("cannot install " + pending.label + ": " + reason);
            }

            Optional<ModFile> file;
            try {
                file = resolve(provider, pending, minecraftVersion, loader);
            } catch (IOException e) {
                if (pending.optional) {
                    skipped.add(pending.label + " (" + e.getMessage() + ")");
                    continue;
                }
                throw e;
            }

            if (file.isEmpty()) {
                String reason = "no build for Minecraft " + minecraftVersion
                        + " on " + loader.displayName();
                if (pending.optional) {
                    skipped.add(pending.label + " (" + reason + ")");
                    continue;
                }
                throw new IOException(pending.label + ": " + reason);
            }

            ModFile modFile = file.get();
            if (!modFile.isDownloadable()) {
                // CurseForge authors can forbid third-party downloads. Respect it.
                manual.add(pending.label + " - " + modFile.fileName()
                        + " (the author has disabled third-party downloads; get it from "
                        + provider.source().displayName() + " manually)");
                continue;
            }

            resolved.put(key, modFile);
            progress.log("Resolved %s -> %s", pending.label, modFile.fileName());

            for (String dependency : modFile.dependencies()) {
                queue.add(new Pending(pending.provider, dependency, null,
                        dependency, false, pending.depth + 1));
            }
        }

        // Remove previously installed files that are no longer part of the set,
        // leaving anything the user added by hand untouched.
        Map<String, ModFile> previous = readLock(modsDir);
        for (Map.Entry<String, ModFile> old : previous.entrySet()) {
            if (resolved.containsKey(old.getKey())) {
                continue;
            }
            Path stale = modsDir.resolve(old.getValue().fileName());
            if (Files.deleteIfExists(stale)) {
                progress.log("Removed %s (no longer in the set)", old.getValue().fileName());
            }
        }

        List<DownloadTask> tasks = new ArrayList<>();
        for (ModFile modFile : resolved.values()) {
            tasks.add(DownloadTask.of(modFile.url(), modsDir.resolve(modFile.fileName()),
                    modFile.sha1(), modFile.size(), modFile.fileName()));
        }

        progress.stage("Downloading " + tasks.size() + " mod(s)");
        downloader.run(tasks, progress);

        writeLock(modsDir, resolved);

        for (String note : skipped) {
            progress.log("Skipped: %s", note);
        }
        for (String note : manual) {
            progress.log("Manual download required: %s", note);
        }

        return new Result(List.copyOf(resolved.values()), List.copyOf(skipped), List.copyOf(manual));
    }

    private Optional<ModFile> resolve(ModProvider provider, Pending pending,
                                      String minecraftVersion, LoaderType loader)
            throws IOException, InterruptedException {

        if (pending.versionId != null && provider instanceof ModrinthProvider modrinth) {
            return modrinth.resolveVersion(pending.projectId, pending.versionId);
        }
        return provider.resolveLatest(pending.projectId, minecraftVersion, loader);
    }

    // ---------------------------------------------------------------- lock file

    private Map<String, ModFile> readLock(Path modsDir) {
        Path lock = modsDir.resolve(LOCK_FILE);
        if (!Files.isRegularFile(lock)) {
            return Map.of();
        }
        try {
            Map<String, ModFile> map = new LinkedHashMap<>();
            Json root = Json.read(lock);
            root.get("mods").fields().forEach((key, value) -> {
                try {
                    map.put(key, ModFile.fromJson(value));
                } catch (RuntimeException ignored) {
                    // A corrupt entry just means that file is treated as user-managed.
                }
            });
            return map;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private void writeLock(Path modsDir, Map<String, ModFile> resolved) throws IOException {
        Json mods = Json.object();
        resolved.forEach((key, file) -> mods.put(key, file.toJson()));
        Json.object()
                .put("version", 1)
                .put("mods", mods)
                .write(modsDir.resolve(LOCK_FILE));
    }

    private record Pending(ModProvider.Source provider, String projectId, String versionId,
                           String label, boolean optional, int depth) {
    }
}
