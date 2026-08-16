package com.hexadron.launcher.mods;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.loader.LoaderType;
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
 * <p>Ownership is recorded in {@link ModLibrary}, and that record is what keeps
 * the two ways mods arrive here from destroying each other. A pack is installed
 * and removed whole; a mod picked in the browser is installed and removed on its
 * own. Both write into the same folder, so without the record refreshing the
 * pack would delete everything the user had chosen themselves.
 */
public final class ModInstaller {

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

    /** Whether a pack can be installed for a given version and loader at all. */
    public record PackAvailability(boolean available, List<String> missing) {
        public PackAvailability {
            missing = List.copyOf(missing);
        }
    }

    // ---------------------------------------------------------------- packs

    /**
     * Checks a pack against a version and loader without downloading anything.
     *
     * <p>The mod browser uses this to decide whether to offer the pack at all.
     * A button that always fails - because half the set has no build for the
     * chosen version - reads as a broken launcher rather than as an unsupported
     * version, so the button is simply absent instead.
     */
    public PackAvailability checkPack(ModPack pack, String minecraftVersion, LoaderType loader)
            throws InterruptedException {

        List<String> missing = new ArrayList<>();
        for (ModPack.Entry entry : pack.entries()) {
            if (entry.optional()) {
                continue;
            }
            ModProvider provider = providers.get(entry.provider());
            if (provider == null || !provider.isAvailable()) {
                missing.add(entry.label());
                continue;
            }
            try {
                if (provider.resolveLatest(entry.projectId(), minecraftVersion, loader).isEmpty()) {
                    missing.add(entry.label());
                }
            } catch (IOException e) {
                missing.add(entry.label());
            }
        }
        return new PackAvailability(missing.isEmpty(), missing);
    }

    /**
     * Installs a pack, keeping the user's own mods untouched.
     *
     * <p>Re-running it removes files that used to belong to this pack and no
     * longer do. It never removes anything the user installed themselves, and it
     * never converts a mod the user had already installed by hand into a
     * pack-owned one - removing the pack must not take that mod with it.
     */
    public Result installPack(ModPack pack, String minecraftVersion, LoaderType loader,
                              Path modsDir, Progress progress) throws IOException, InterruptedException {

        progress.stage("Resolving " + pack.name());
        Files.createDirectories(modsDir);

        Map<String, ModFile> resolved = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
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
            String key = InstalledMod.keyOf(pending.provider, pending.projectId);
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
            titles.put(key, pending.label);
            progress.log("Resolved %s -> %s", pending.label, modFile.fileName());

            for (String dependency : modFile.dependencies()) {
                queue.add(new Pending(pending.provider, dependency, null,
                        dependency, false, pending.depth + 1));
            }
        }

        ModLibrary library = ModLibrary.read(modsDir);

        // Files this pack used to own and no longer does. Manual entries are not
        // considered here at all - they are not the pack's to remove.
        for (InstalledMod previous : library.ofPack(pack.id())) {
            if (resolved.containsKey(previous.key())) {
                continue;
            }
            if (Files.deleteIfExists(modsDir.resolve(previous.file().fileName()))) {
                progress.log("Removed %s (no longer in the set)", previous.file().fileName());
            }
            library.forget(previous.key());
        }

        List<DownloadTask> tasks = new ArrayList<>();
        for (ModFile modFile : resolved.values()) {
            tasks.add(DownloadTask.of(modFile.url(), modsDir.resolve(modFile.fileName()),
                    modFile.sha1(), modFile.size(), modFile.fileName()));
        }

        progress.stage("Downloading " + tasks.size() + " mod(s)");
        downloader.run(tasks, progress);

        resolved.forEach((key, modFile) -> {
            // Already there by the user's own choice: leave it theirs.
            if (library.get(key).map(mod -> mod.origin() == ModOrigin.MANUAL).orElse(false)) {
                return;
            }
            library.put(new InstalledMod(titles.getOrDefault(key, modFile.fileName()),
                    modFile, ModOrigin.PACK, pack.id()));
        });
        library.write();

        for (String note : skipped) {
            progress.log("Skipped: %s", note);
        }
        for (String note : manual) {
            progress.log("Manual download required: %s", note);
        }

        return new Result(List.copyOf(resolved.values()), List.copyOf(skipped), List.copyOf(manual));
    }

    /** Removes every file a pack owns, and only those. */
    public int removePack(String packId, Path modsDir, Progress progress) throws IOException {
        ModLibrary library = ModLibrary.read(modsDir);
        List<InstalledMod> owned = library.ofPack(packId);
        for (InstalledMod mod : owned) {
            if (Files.deleteIfExists(modsDir.resolve(mod.file().fileName()))) {
                progress.log("Removed %s", mod.file().fileName());
            }
            library.forget(mod.key());
        }
        library.write();
        return owned.size();
    }

    // ---------------------------------------------------------------- single mods

    /**
     * Installs one mod chosen in the browser, plus whatever it requires.
     *
     * <p>The mod itself is recorded as the user's; anything pulled in behind it
     * is recorded as a dependency, so the installed list can explain why a mod
     * nobody asked for is present.
     */
    public Result installMod(ModProvider.Source source, String projectId, String title,
                             String minecraftVersion, LoaderType loader,
                             Path modsDir, Progress progress) throws IOException, InterruptedException {

        ModProvider provider = providers.get(source);
        if (provider == null || !provider.isAvailable()) {
            throw new IOException(source.displayName() + " is not configured");
        }
        Files.createDirectories(modsDir);
        ModLibrary library = ModLibrary.read(modsDir);

        Map<String, ModFile> resolved = new LinkedHashMap<>();
        Map<String, ModOrigin> origins = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();

        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(source, projectId, null, title, false, 0));

        while (!queue.isEmpty()) {
            Pending pending = queue.poll();
            String key = InstalledMod.keyOf(pending.provider, pending.projectId);
            if (!visited.add(key)) {
                continue;
            }
            if (pending.depth > MAX_DEPENDENCY_DEPTH) {
                skipped.add(pending.label + " (dependency chain too deep)");
                continue;
            }
            // A dependency already present - from the pack, or from an earlier
            // install - is left exactly as it is, ownership included.
            if (pending.depth > 0 && library.contains(key)) {
                continue;
            }

            Optional<ModFile> file = provider.resolveLatest(pending.projectId, minecraftVersion, loader);
            if (file.isEmpty()) {
                String reason = "no build for Minecraft " + minecraftVersion
                        + " on " + loader.displayName();
                if (pending.depth == 0) {
                    throw new IOException(pending.label + ": " + reason);
                }
                skipped.add(pending.label + " (" + reason + ")");
                continue;
            }

            ModFile modFile = file.get();
            if (!modFile.isDownloadable()) {
                manual.add(pending.label + " - " + modFile.fileName()
                        + " (the author has disabled third-party downloads)");
                continue;
            }

            resolved.put(key, modFile);
            origins.put(key, pending.depth == 0 ? ModOrigin.MANUAL : ModOrigin.DEPENDENCY);
            titles.put(key, pending.label);

            for (String dependency : modFile.dependencies()) {
                queue.add(new Pending(pending.provider, dependency, null,
                        dependency, false, pending.depth + 1));
            }
        }

        List<DownloadTask> tasks = new ArrayList<>();
        for (ModFile modFile : resolved.values()) {
            tasks.add(DownloadTask.of(modFile.url(), modsDir.resolve(modFile.fileName()),
                    modFile.sha1(), modFile.size(), modFile.fileName()));
        }
        progress.stage("Downloading " + tasks.size() + " mod(s)");
        downloader.run(tasks, progress);

        resolved.forEach((key, modFile) -> library.put(new InstalledMod(
                titles.getOrDefault(key, modFile.fileName()),
                modFile,
                origins.getOrDefault(key, ModOrigin.MANUAL),
                null)));
        library.write();

        for (String note : skipped) {
            progress.log("Skipped: %s", note);
        }
        for (String note : manual) {
            progress.log("Manual download required: %s", note);
        }
        return new Result(List.copyOf(resolved.values()), List.copyOf(skipped), List.copyOf(manual));
    }

    /**
     * Removes one mod.
     *
     * @throws IOException when the entry belongs to a pack, which is removed whole
     */
    public void removeMod(String key, Path modsDir, Progress progress) throws IOException {
        ModLibrary library = ModLibrary.read(modsDir);
        InstalledMod mod = library.get(key).orElseThrow(
                () -> new IOException("no managed mod with key " + key));
        if (!mod.origin().isRemovableAlone()) {
            throw new IOException(mod.title() + " belongs to the " + mod.packId()
                    + " pack and is removed with it, not on its own");
        }
        if (Files.deleteIfExists(modsDir.resolve(mod.file().fileName()))) {
            progress.log("Removed %s", mod.file().fileName());
        }
        library.forget(key);
        library.write();
    }

    // ---------------------------------------------------------------- search

    /**
     * Searches the configured providers and concatenates one page from each.
     *
     * <p>The totals are summed so the browser can say how much it is not
     * showing. One platform being unreachable must not empty the browser, so a
     * failure is only raised when every provider failed and there is nothing to
     * show.
     */
    public ModProvider.SearchPage search(String query, String minecraftVersion,
                                         LoaderType loader, ModSort sort,
                                         int limitPerProvider, int offset,
                                         ModProvider.Source only)
            throws IOException, InterruptedException {

        List<ModProvider.SearchResult> results = new ArrayList<>();
        int total = 0;
        boolean totalKnown = false;
        IOException firstFailure = null;

        for (ModProvider provider : providers.values()) {
            if (only != null && provider.source() != only) {
                continue;
            }
            if (!provider.isAvailable()) {
                continue;
            }
            try {
                ModProvider.SearchPage page = provider.search(
                        query, minecraftVersion, loader, sort, limitPerProvider, offset);
                results.addAll(page.results());
                if (page.total() >= 0) {
                    total += page.total();
                    totalKnown = true;
                }
            } catch (IOException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (results.isEmpty() && firstFailure != null) {
            throw firstFailure;
        }
        return new ModProvider.SearchPage(results, totalKnown ? total : -1, offset);
    }

    private Optional<ModFile> resolve(ModProvider provider, Pending pending,
                                      String minecraftVersion, LoaderType loader)
            throws IOException, InterruptedException {

        if (pending.versionId != null && provider instanceof ModrinthProvider modrinth) {
            return modrinth.resolveVersion(pending.projectId, pending.versionId);
        }
        return provider.resolveLatest(pending.projectId, minecraftVersion, loader);
    }

    private record Pending(ModProvider.Source provider, String projectId, String versionId,
                           String label, boolean optional, int depth) {
    }
}
