/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

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
            // A conditional entry is not part of what makes the set installable:
            // whether it belongs at all depends on the build another entry
            // resolves to, which is not known until the install runs.
            if (entry.optional() || entry.isConditional()) {
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
        Map<String, ModProvider.ProjectCard> cards = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();

        Deque<Pending> queue = new ArrayDeque<>();
        List<ModPack.Entry> conditional = new ArrayList<>();
        for (ModPack.Entry entry : pack.entries()) {
            if (entry.isConditional()) {
                // Held back: the condition is about what another entry resolves
                // to, so it cannot be answered until that one has.
                conditional.add(entry);
                continue;
            }
            queue.add(new Pending(entry.provider(), entry.projectId(), entry.versionId(),
                    entry.label(), entry.optional(), 0));
        }

        boolean conditionsAnswered = false;
        while (true) {
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
                Optional<ModFile> mirrored = mirrorOnModrinth(modFile);
                if (mirrored.isEmpty()) {
                    manual.add(pending.label + " - " + modFile.fileName()
                            + " (the author has disabled third-party downloads; get it from "
                            + provider.source().displayName() + " manually)");
                    continue;
                }
                modFile = mirrored.get();
                progress.log("%s cannot be downloaded from %s; the identical file is on "
                                + "Modrinth and is taken from there", pending.label,
                        provider.source().displayName());
            }

            ModProvider.ProjectCard card = cardFor(provider, pending, modFile);
            String label = card.title();
            resolved.put(key, modFile);
            cards.put(key, card);
            progress.log("Resolved %s -> %s", label, modFile.fileName());
            if (pending.requiredBy() != null) {
                progress.log("  %s is required by %s", label, pending.requiredBy());
            }

            for (String dependency : modFile.dependencies()) {
                queue.add(new Pending(pending.provider, dependency, null,
                        dependency, false, pending.depth + 1, pending.label));
            }
        }

        if (conditionsAnswered) {
            break;
        }
        conditionsAnswered = true;
        for (ModPack.Entry entry : conditional) {
            String verdict = conditionVerdict(entry, resolved);
            if (verdict != null) {
                skipped.add(entry.label() + " (" + verdict + ")");
                continue;
            }
            queue.add(new Pending(entry.provider(), entry.projectId(), entry.versionId(),
                    entry.label(), true, 0));
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
            library.put(InstalledMod.of(cardOrFileName(cards, key, modFile),
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

    /**
     * Whether a conditional entry belongs in this install.
     *
     * @return null when it does, otherwise why it does not - in words that go
     *         into the log, because "a mod in the set was not installed" is
     *         only useful with the reason attached
     */
    private static String conditionVerdict(ModPack.Entry entry, Map<String, ModFile> resolved) {
        ModPack.Condition condition = entry.onlyWith();
        ModFile companion = resolved.get(
                InstalledMod.keyOf(condition.provider(), condition.projectId()));
        if (companion == null) {
            return "there is no " + condition.projectId() + " in this set to go with";
        }

        String version = ModVersions.of(companion.displayName(), companion.fileName());
        if (version == null) {
            // Deliberately the cautious way round. This mod exists to patch a
            // gap in an older companion and conflicts with the newer one, so a
            // wrong "yes" is a game that will not start, while a wrong "no" is
            // at worst the warning screen it was meant to remove.
            return "the version of " + companion.fileName() + " could not be read,"
                    + " so it was left out rather than guessed at";
        }
        if (!ModVersions.isBelow(version, condition.versionBelow())) {
            return companion.fileName() + " is " + version + ", which is "
                    + condition.versionBelow() + " or newer and does not need it";
        }
        return null;
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
    public Result installMod(ModProvider.ProjectCard chosen,
                             String minecraftVersion, LoaderType loader,
                             Path modsDir, Progress progress) throws IOException, InterruptedException {

        ModProvider.Source source = chosen.source();
        ModProvider provider = providers.get(source);
        if (provider == null || !provider.isAvailable()) {
            throw new IOException(source.displayName() + " is not configured");
        }
        Files.createDirectories(modsDir);
        ModLibrary library = ModLibrary.read(modsDir);

        Map<String, ModFile> resolved = new LinkedHashMap<>();
        Map<String, ModOrigin> origins = new LinkedHashMap<>();
        Map<String, ModProvider.ProjectCard> cards = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();

        // The mod the user clicked is already described by the search result they
        // clicked it in. Asking the platform to describe it again would be a
        // request for something the caller has in its hand.
        cards.put(InstalledMod.keyOf(source, chosen.projectId()), chosen);

        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(source, chosen.projectId(), null, chosen.title(), false, 0));

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
                Optional<ModFile> mirrored = mirrorOnModrinth(modFile);
                if (mirrored.isEmpty()) {
                    manual.add(pending.label + " - " + modFile.fileName()
                            + " (the author has disabled third-party downloads)");
                    continue;
                }
                modFile = mirrored.get();
                progress.log("%s cannot be downloaded from %s; the identical file is on "
                                + "Modrinth and is taken from there", pending.label,
                        source.displayName());
            }

            ModProvider.ProjectCard card = cards.get(key);
            if (card == null) {
                card = cardFor(provider, pending, modFile);
                cards.put(key, card);
            }
            resolved.put(key, modFile);
            origins.put(key, pending.depth == 0 ? ModOrigin.MANUAL : ModOrigin.DEPENDENCY);
            if (pending.requiredBy() != null) {
                progress.log("%s is required by %s", card.title(), pending.requiredBy());
            }

            for (String dependency : modFile.dependencies()) {
                queue.add(new Pending(pending.provider, dependency, null,
                        dependency, false, pending.depth + 1, pending.label));
            }
        }

        List<DownloadTask> tasks = new ArrayList<>();
        for (ModFile modFile : resolved.values()) {
            tasks.add(DownloadTask.of(modFile.url(), modsDir.resolve(modFile.fileName()),
                    modFile.sha1(), modFile.size(), modFile.fileName()));
        }
        progress.stage("Downloading " + tasks.size() + " mod(s)");
        downloader.run(tasks, progress);

        resolved.forEach((key, modFile) -> library.put(InstalledMod.of(
                cardOrFileName(cards, key, modFile),
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
        // Both names, because the user may have switched the mod off since it
        // was installed, and a rename is all that is.
        if (Files.deleteIfExists(modsDir.resolve(mod.file().fileName()))
                || Files.deleteIfExists(modsDir.resolve(
                        mod.file().fileName() + ModScan.DISABLED_SUFFIX))) {
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
                                         List<ModCategory> categories,
                                         int limitPerProvider, int offset,
                                         ModProvider.Source only)
            throws IOException, InterruptedException {

        List<ModProvider.SearchResult> results = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
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
                        query, minecraftVersion, loader, sort, categories,
                        limitPerProvider, offset);
                results.addAll(page.results());
                if (page.total() >= 0) {
                    total += page.total();
                    totalKnown = true;
                }
            } catch (IOException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
                // Named, not swallowed. A platform that was asked and did not
                // answer is the one case indistinguishable from "there is nothing
                // there": the user sees a shorter list and no reason for it. A
                // wrong API key looks exactly like a mod that does not exist for
                // their version.
                unavailable.add(provider.source().displayName() + ": " + reasonFor(e));
            }
        }
        if (results.isEmpty() && firstFailure != null) {
            // Nothing to show, so this one is raised rather than reported beside
            // results. It still gets the same sentence: the raw exception message
            // is "HTTP 403 for https://api.curseforge.com/v1/mods/search?gameId=
            // 432&classId=6&pageSize=40&index=0&..." followed by the platform's
            // own wording, which is a URL the user did not type and cannot act
            // on. The original is kept as the cause, so a log still has it.
            throw new IOException(String.join("; ", unavailable), firstFailure);
        }
        return new ModProvider.SearchPage(
                results, totalKnown ? total : -1, offset, unavailable);
    }

    /**
     * A short reason fit for one line of interface, not a stack trace.
     *
     * <p>The HTTP code alone is not an explanation. A user reading "403" against
     * CurseForge has no way to know that it means their key, and the platform's
     * own body text - "Forbidden: API Key missing or invalid" - arrives buried
     * behind the full request URL.
     */
    public static String reasonFor(IOException failure) {
        if (failure instanceof CurseForgeProvider.UnsupportedCategoriesException) {
            return "the chosen categories are Modrinth's own and have no equivalent here";
        }
        if (failure instanceof com.hexadron.launcher.net.Http.HttpStatusException status) {
            return switch (status.statusCode()) {
                case 401, 403 -> "HTTP " + status.statusCode() + " - the API key was refused";
                case 429 -> "HTTP 429 - too many requests, try again shortly";
                case 404 -> "HTTP 404 - the platform has no such endpoint any more";
                default -> "HTTP " + status.statusCode();
            };
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.toString() : message;
    }

    /**
     * What to record about this mod, so the installed list can show it.
     *
     * <p>A mod the user chose already has the name they clicked. A dependency
     * arrives with nothing but a project id, and an installed list reading
     * "eXts2L7r" is indistinguishable from something that has no business being
     * there - which is exactly how it was reported. So the platform is asked,
     * and if it will not answer, the file name is used: "Placeholder Api" from
     * placeholder-api-3.1.0-beta.1+26.2.jar is not perfect, and it is still an
     * answer.
     *
     * <p>The same request now also returns the project's logo and page, which is
     * why it is made for pack entries too - those already had a name and needed
     * nothing else. One small request per mod, once, at install time, is what
     * buys a list that can be recognised at a glance and read about afterwards
     * without a connection.
     */
    private ModProvider.ProjectCard cardFor(ModProvider provider, Pending pending, ModFile file)
            throws InterruptedException {

        ModProvider.ProjectCard published = null;
        try {
            published = provider.project(pending.projectId()).orElse(null);
        } catch (IOException | RuntimeException e) {
            // One undescribed mod is not worth failing an install over. The row
            // then shows a readable name and no logo, which is what every row
            // looked like before there were logos.
        }
        // A name the caller supplied wins over the platform's. For a pack entry
        // that is the label the pack author chose, and for a mod the user picked
        // it is the name they read before clicking; the platform's own title is
        // for the case where all this arrived as a bare project id.
        String title = pending.projectId().equals(pending.label())
                ? (published != null ? published.title() : readableNameFrom(file.fileName()))
                : pending.label();
        if (published == null) {
            return new ModProvider.ProjectCard(provider.source(), pending.projectId(),
                    null, title, null, null);
        }
        return new ModProvider.ProjectCard(published.source(), published.projectId(),
                published.slug(), title, published.iconUrl(), published.pageUrl());
    }

    /** The card for a key, or a bare one built from the file name. */
    private static ModProvider.ProjectCard cardOrFileName(
            Map<String, ModProvider.ProjectCard> cards, String key, ModFile file) {

        ModProvider.ProjectCard card = cards.get(key);
        return card != null ? card : new ModProvider.ProjectCard(
                file.source(), file.projectId(), file.projectSlug(),
                readableNameFrom(file.fileName()), null, null);
    }

    /**
     * A readable name from a jar file name: everything before the version.
     *
     * <p>The split is at the first hyphen followed by a digit, which is where
     * every mod jar naming convention in use puts the boundary.
     */
    public static String readableNameFrom(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unknown mod";
        }
        String base = fileName.endsWith(".jar")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^(.+?)-\\d").matcher(base);
        String stem = matcher.find() ? matcher.group(1) : base;
        stem = stem.replace('_', ' ').replace('-', ' ').trim();
        if (stem.isEmpty()) {
            return base;
        }
        StringBuilder out = new StringBuilder(stem.length());
        for (String word : stem.split("\\s+")) {
            out.append(Character.toUpperCase(word.charAt(0)))
               .append(word.substring(1))
               .append(' ');
        }
        return out.toString().trim();
    }

    /**
     * The same jar, published where it may be downloaded from.
     *
     * <p>A CurseForge author can switch off third-party downloads, and the API
     * then returns the file with no URL. Most of those mods are also on Modrinth,
     * published by the same author, and Modrinth can be asked "which version has
     * this SHA-1". A hit is the same bytes by definition, so the file is fetched
     * from there and the digest still verifies it.
     *
     * <p>The returned entry keeps the original project's identity and only
     * borrows the URL. Rewriting the identity would change the key the mod is
     * recorded under, and a pack would then stop recognising its own files.
     *
     * <p>No hash, no attempt: this must never turn into "find something with a
     * similar name".
     */
    private Optional<ModFile> mirrorOnModrinth(ModFile file) throws InterruptedException {
        if (file.source() == ModProvider.Source.MODRINTH || file.sha1() == null) {
            return Optional.empty();
        }
        if (!(providers.get(ModProvider.Source.MODRINTH) instanceof ModrinthProvider modrinth)
                || !modrinth.isAvailable()) {
            return Optional.empty();
        }
        try {
            return modrinth.resolveByHash(file.sha1())
                    .map(found -> new ModFile(
                            file.projectId(),
                            file.projectSlug(),
                            file.versionId(),
                            file.displayName(),
                            file.fileName(),
                            found.url(),
                            file.sha1(),
                            file.size(),
                            file.dependencies(),
                            file.source()))
                    .filter(ModFile::isDownloadable);
        } catch (IOException e) {
            // Modrinth being unreachable is not this mod's problem to report;
            // the caller falls back to telling the user to fetch it by hand.
            return Optional.empty();
        }
    }

    private Optional<ModFile> resolve(ModProvider provider, Pending pending,
                                      String minecraftVersion, LoaderType loader)
            throws IOException, InterruptedException {

        if (pending.versionId != null && provider instanceof ModrinthProvider modrinth) {
            return modrinth.resolveVersion(pending.projectId, pending.versionId);
        }
        return provider.resolveLatest(pending.projectId, minecraftVersion, loader);
    }

    /**
     * @param label      what to call this in the interface. For a dependency it
     *                   starts out as the raw project id and is replaced by the
     *                   real name once the platform has been asked
     * @param requiredBy the mod that pulled this one in, or null for a mod the
     *                   user or a pack asked for directly
     */
    private record Pending(ModProvider.Source provider, String projectId, String versionId,
                           String label, boolean optional, int depth, String requiredBy) {

        Pending(ModProvider.Source provider, String projectId, String versionId,
                String label, boolean optional, int depth) {
            this(provider, projectId, versionId, label, optional, depth, null);
        }
    }
}
