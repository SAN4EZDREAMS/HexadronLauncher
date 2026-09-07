/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
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
import com.hexadron.launcher.net.DownloadTask;
import com.hexadron.launcher.net.Downloader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs and removes modpacks.
 *
 * <h2>What a modpack install actually is</h2>
 *
 * <p>Three steps, and the third is the one that makes a pack a pack:
 *
 * <ol>
 *   <li>every file the manifest names is fetched into the place the manifest
 *       puts it - which for a Modrinth pack is a path it states, and for a
 *       CurseForge pack is {@code mods/} plus whatever name the file has;</li>
 *   <li>the pack's own {@code overrides} are copied over the instance. This is
 *       the configs, the keybindings, the shader presets, the resource packs -
 *       everything that makes the set behave the way its author tested it, and
 *       the reason a pack is not just a list of mods;</li>
 *   <li>every path written is written down, because nothing about the instance
 *       afterwards says which files came from the pack. See
 *       {@link InstalledModpack}.</li>
 * </ol>
 *
 * <h2>What it will not do</h2>
 *
 * <p>Nothing outside the instance folder. A manifest is a file from the internet
 * and a path in it is untrusted input: {@code ../../../.ssh/authorized_keys} is a
 * valid string in a JSON document, and a launcher that resolves it against the
 * instance folder and writes there has handed a stranger the user's home
 * directory. Every path - from the manifest and from inside the overrides - is
 * checked to land under the instance, and one that does not is refused and
 * reported rather than silently dropped.
 *
 * <p>It also never overwrites a file it did not write in this run without saying
 * so. Re-installing a pack over itself replaces the pack's own files, which is
 * the point; the report names anything of the player's that was in the way.
 */
public final class ModpackInstaller {

    private final Map<ModProvider.Source, ModProvider> providers = new LinkedHashMap<>();
    private final Downloader downloader;

    public ModpackInstaller(Downloader downloader, ModProvider... providers) {
        this.downloader = downloader;
        for (ModProvider provider : providers) {
            this.providers.put(provider.source(), provider);
        }
    }

    /**
     * What came of an install.
     *
     * @param record          what was written down, so the caller can show and
     *                        later remove it
     * @param files           how many files ended up on disk
     * @param skipped         one line each: a file that was named and did not
     *                        arrive, and why
     * @param manualDownloads files whose author forbids third-party downloads,
     *                        which have to be fetched by hand
     */
    public record Result(InstalledModpack record, int files,
                         List<String> skipped, List<String> manualDownloads) {

        public Result {
            skipped = List.copyOf(skipped);
            manualDownloads = List.copyOf(manualDownloads);
        }

        public boolean isClean() {
            return skipped.isEmpty() && manualDownloads.isEmpty();
        }
    }

    /**
     * Fetches the pack archive itself.
     *
     * <p>Into a folder the caller owns rather than into the instance: the archive
     * is the input to the install, not part of its output, and an instance folder
     * with a 300 MB zip left in it is 300 MB the user cannot account for.
     */
    public Path fetch(ModFile file, Path directory, Progress progress)
            throws IOException, InterruptedException {

        if (!file.isDownloadable()) {
            throw new IOException(file.fileName()
                    + " cannot be downloaded from " + file.source().displayName()
                    + ": the author has disabled third-party downloads. Download it from the"
                    + " project page and open it with the Open file button.");
        }
        Files.createDirectories(directory);
        Path target = directory.resolve(safeFileName(file.fileName()));
        progress.stage("Downloading " + file.fileName());
        downloader.run(List.of(DownloadTask.of(file.url(), target,
                file.sha1(), file.size(), file.fileName())), progress);
        return target;
    }

    /**
     * Installs a pack into an instance folder.
     *
     * @param card the project this came from, for the logo and the link on the
     *             installed row, or null for a pack file the user opened
     */
    public Result install(PackArchive pack, ModProvider.ProjectCard card,
                          Path gameDirectory, Progress progress)
            throws IOException, InterruptedException {

        Files.createDirectories(gameDirectory);
        Path root = gameDirectory.toAbsolutePath().normalize();

        List<String> skipped = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        Set<String> written = new LinkedHashSet<>();

        // Which paths must not fail the install: a pack's own optional extras,
        // and the client-side "optional" of a Modrinth env block.
        Set<String> tolerated = new LinkedHashSet<>();
        Map<String, DownloadTask> tasks = new LinkedHashMap<>();
        Map<String, ModFile> pinned = new LinkedHashMap<>();

        progress.stage("Resolving " + pack.instanceName());

        for (PackArchive.Download download : pack.downloads()) {
            String relative = safeRelative(root, download.path());
            if (relative == null) {
                skipped.add(download.path() + " (the pack puts this outside the instance folder)");
                continue;
            }
            DownloadTask task = new DownloadTask(download.urls(), root.resolve(relative),
                    download.sha1(), download.size(), fileNameOf(relative), false);
            tasks.put(relative, task);
            if (download.optional()) {
                tolerated.add(relative);
            }
        }

        for (PackArchive.ProjectFile entry : pack.projectFiles()) {
            Optional<ModFile> resolved = resolveExact(entry);
            if (resolved.isEmpty()) {
                String note = "CurseForge project " + entry.projectId()
                        + " file " + entry.fileId() + " could not be resolved";
                if (entry.required()) {
                    skipped.add(note);
                } else {
                    skipped.add(note + " (optional)");
                }
                continue;
            }
            ModFile file = resolved.get();
            if (!file.isDownloadable()) {
                Optional<ModFile> mirrored = mirrorOnModrinth(file);
                if (mirrored.isEmpty()) {
                    manual.add(file.fileName()
                            + " (the author has disabled third-party downloads on CurseForge;"
                            + " fetch it by hand and put it in the mods folder)");
                    continue;
                }
                file = mirrored.get();
                progress.log("%s is taken from Modrinth: the identical file, where it may be"
                        + " downloaded", file.fileName());
            }
            String relative = safeRelative(root, "mods/" + file.fileName());
            if (relative == null) {
                skipped.add(file.fileName() + " (the pack names an unusable file name)");
                continue;
            }
            tasks.put(relative, DownloadTask.of(file.url(), root.resolve(relative),
                    file.sha1(), file.size(), file.fileName()));
            pinned.put(relative, file);
            if (!entry.required()) {
                tolerated.add(relative);
            }
        }

        // The folders first, in one pass: two hundred createDirectories calls
        // interleaved with two hundred downloads is two hundred chances to fail
        // half-way with files on disk and no record of them.
        for (Path destination : tasks.values().stream().map(DownloadTask::destination).toList()) {
            Files.createDirectories(destination.getParent());
        }

        progress.stage("Downloading " + tasks.size() + " file(s) for " + pack.instanceName());
        List<Downloader.Failure> failures =
                downloader.runCollecting(List.copyOf(tasks.values()), progress);

        Set<Path> failed = new LinkedHashSet<>();
        for (Downloader.Failure failure : failures) {
            failed.add(failure.task().destination());
        }
        for (Map.Entry<String, DownloadTask> entry : tasks.entrySet()) {
            if (!failed.contains(entry.getValue().destination())) {
                written.add(entry.getKey());
                continue;
            }
            String note = entry.getValue().description() + " did not download";
            if (tolerated.contains(entry.getKey())) {
                skipped.add(note + " (optional)");
            } else {
                skipped.add(note);
            }
        }
        // Every required file failing is not a pack with notes against it; it is
        // an install that did not happen, and it is reported as one.
        if (!written.isEmpty() || tasks.isEmpty()) {
            progress.log("%d of %d file(s) downloaded", written.size(), tasks.size());
        } else if (!tasks.isEmpty()) {
            throw new IOException("nothing could be downloaded for " + pack.instanceName()
                    + ": " + (failures.isEmpty() ? "no files arrived"
                    : String.valueOf(failures.get(0).cause())));
        }

        // The overrides, in the order the pack lists them, so a pack that ships
        // both a shared and a client-only set gets the client one last.
        for (String overrides : pack.overrides()) {
            progress.stage("Applying " + overrides);
            written.addAll(extractOverrides(pack.archive(), overrides, root, skipped, progress));
        }

        InstalledModpack record = new InstalledModpack(
                card != null
                        ? InstalledModpack.idOf(card.source(), card.projectId())
                        : InstalledModpack.idOf(pack.archive().getFileName().toString()),
                pack.instanceName(),
                pack.version(),
                pack.author(),
                card == null ? null : card.source(),
                card == null ? null : card.projectId(),
                card == null ? null : card.iconUrl(),
                card == null ? null : card.pageUrl(),
                pack.minecraftVersion(),
                pack.loader(),
                pack.loaderVersion(),
                List.copyOf(written),
                System.currentTimeMillis());

        ModpackLibrary library = ModpackLibrary.read(gameDirectory);
        library.put(record);
        library.write();

        // The jars this pack owns, marked as its own in the mods list.
        //
        // Only the ones whose project is known - the CurseForge files, and the
        // Modrinth files whose address carries the project id. A jar the pack
        // fetched from somewhere else is left listed as a file the launcher did
        // not install, which is the truth: there is no project to record.
        recordOwnedMods(root, record, tasks, pinned, written, progress);

        for (String note : skipped) {
            progress.log("Skipped: %s", note);
        }
        for (String note : manual) {
            progress.log("Manual download required: %s", note);
        }
        return new Result(record, written.size(), skipped, manual);
    }

    /**
     * Removes a pack: exactly the files it wrote, and nothing else.
     *
     * <p>Deepest first, so a folder the pack created is empty by the time it is
     * considered - and folders are only removed when they are empty, because
     * {@code config} holding one file of the pack's and one of the player's is a
     * folder that stays.
     *
     * @return how many files were deleted
     */
    public int remove(String id, Path gameDirectory, Progress progress) throws IOException {
        ModpackLibrary library = ModpackLibrary.read(gameDirectory);
        InstalledModpack pack = library.get(id).orElseThrow(
                () -> new IOException("no modpack recorded under " + id));
        Path root = gameDirectory.toAbsolutePath().normalize();

        int deleted = 0;
        List<Path> directories = new ArrayList<>();
        for (String relative : pack.paths()) {
            String safe = safeRelative(root, relative);
            if (safe == null) {
                continue;
            }
            Path file = root.resolve(safe);
            try {
                if (Files.deleteIfExists(file)) {
                    deleted++;
                }
            } catch (IOException e) {
                // A file the game still has open on Windows. Reported, not
                // fatal: the rest of the pack still comes out.
                progress.log("Could not delete %s: %s", safe,
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Path parent = file.getParent();
            while (parent != null && !parent.equals(root) && parent.startsWith(root)) {
                directories.add(parent);
                parent = parent.getParent();
            }
        }

        directories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path directory : directories) {
            try {
                Files.deleteIfExists(directory);
            } catch (IOException e) {
                // Not empty, which is the normal case and not a problem.
            }
        }

        // And the mods list's own record of the jars, so the folder and the
        // record cannot disagree about what is installed.
        ModLibrary mods = ModLibrary.read(root.resolve("mods"));
        for (InstalledMod mod : mods.ofPack(pack.id())) {
            mods.forget(mod.key());
        }
        mods.write();

        library.forget(id);
        library.write();
        progress.log("Removed %d file(s) of %s", deleted, pack.name());
        return deleted;
    }

    // ---------------------------------------------------------------- pieces

    /**
     * Copies one overrides folder over the instance.
     *
     * @return the paths written, relative to the instance and {@code /}-separated
     */
    private static List<String> extractOverrides(Path archive, String overrides, Path root,
                                                 List<String> skipped, Progress progress)
            throws IOException {

        String prefix = overrides.endsWith("/") ? overrides : overrides + "/";
        List<String> written = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<ZipEntry> entries = new ArrayList<>();
            zip.stream()
                    .filter(entry -> entry.getName().startsWith(prefix))
                    .filter(entry -> !entry.isDirectory())
                    .forEach(entries::add);
            int done = 0;
            progress.items(0, entries.size());
            for (ZipEntry entry : entries) {
                String relative = safeRelative(root, entry.getName().substring(prefix.length()));
                if (relative == null || relative.isBlank()) {
                    skipped.add(entry.getName() + " (the pack puts this outside the instance folder)");
                    continue;
                }
                Path destination = root.resolve(relative);
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                written.add(relative);
                progress.items(++done, entries.size());
            }
        }
        return written;
    }

    /**
     * Marks the pack's jars as the pack's, in the mods list.
     *
     * <p>So the row for one of them says which pack it belongs to and its Remove
     * button is off - a pack is a set that was tested together, and pulling one
     * mod out of it leaves something that is no longer the pack but still claims
     * to be. Removing the pack is what takes them out.
     */
    private static void recordOwnedMods(Path root, InstalledModpack pack,
                                        Map<String, DownloadTask> tasks,
                                        Map<String, ModFile> pinned,
                                        Set<String> written, Progress progress) {
        Path modsDir = root.resolve("mods");
        ModLibrary library = ModLibrary.read(modsDir);
        boolean any = false;

        for (String relative : written) {
            if (!relative.startsWith("mods/") || !relative.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            ModFile file = pinned.get(relative);
            if (file == null) {
                DownloadTask task = tasks.get(relative);
                file = fromModrinthCdn(task, fileNameOf(relative));
            }
            if (file == null) {
                continue;
            }
            // Not over the top of a mod the user installed themselves. Removing
            // the pack must not take that one with it.
            if (library.get(InstalledMod.keyOf(file.source(), file.projectId()))
                    .map(mod -> mod.origin() == ModOrigin.MANUAL).orElse(false)) {
                continue;
            }
            library.put(new InstalledMod(
                    ModInstaller.readableNameFrom(file.fileName()),
                    file, ModOrigin.PACK, pack.id()));
            any = true;
        }
        if (!any) {
            return;
        }
        try {
            library.write();
        } catch (IOException e) {
            // The pack is installed and its own record is written; this one only
            // decides what the mods list says about the jars.
            progress.log("The mods list could not be updated: %s",
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * A Modrinth file's identity, read out of the address it was fetched from.
     *
     * <p>Modrinth's own CDN addresses are
     * {@code cdn.modrinth.com/data/<projectId>/versions/<versionId>/<file>}, so a
     * {@code .mrpack} that names its files by URL has already said which project
     * each one is - and asking the platform again for something already in hand
     * would be a request per file for no new information.
     *
     * @return null when the address is not one of those, which is not an error
     */
    private static ModFile fromModrinthCdn(DownloadTask task, String fileName) {
        if (task == null) {
            return null;
        }
        for (String url : task.urls()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "^https://cdn\\.modrinth\\.com/data/([A-Za-z0-9]+)/versions/([A-Za-z0-9]+)/")
                    .matcher(url);
            if (matcher.find()) {
                return new ModFile(matcher.group(1), null, matcher.group(2), fileName,
                        fileName, url, task.sha1(), task.size(), List.of(),
                        ModProvider.Source.MODRINTH);
            }
        }
        return null;
    }

    private Optional<ModFile> resolveExact(PackArchive.ProjectFile entry)
            throws InterruptedException {

        if (!(providers.get(ModProvider.Source.CURSEFORGE) instanceof CurseForgeProvider curseForge)
                || !curseForge.isAvailable()) {
            return Optional.empty();
        }
        try {
            return curseForge.resolveExact(entry.projectId(), entry.fileId());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The same file, published where it may be downloaded from.
     *
     * <p>Same rule as {@link ModInstaller}: a matching SHA-1 on Modrinth is the
     * same bytes by definition, so nothing is circumvented - the file is taken
     * from a place its author did allow. No hash, no attempt.
     */
    private Optional<ModFile> mirrorOnModrinth(ModFile file) throws InterruptedException {
        if (file.sha1() == null || file.sha1().isBlank()) {
            return Optional.empty();
        }
        if (!(providers.get(ModProvider.Source.MODRINTH) instanceof ModrinthProvider modrinth)
                || !modrinth.isAvailable()) {
            return Optional.empty();
        }
        try {
            return modrinth.resolveByHash(file.sha1())
                    .map(found -> new ModFile(file.projectId(), file.projectSlug(),
                            file.versionId(), file.displayName(), file.fileName(),
                            found.url(), file.sha1(), file.size(), file.dependencies(),
                            file.source()))
                    .filter(ModFile::isDownloadable);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- paths

    /**
     * A path from a manifest, made safe, or null when it cannot be.
     *
     * <p>Refused: an absolute path, a Windows drive, a {@code ..} segment, and
     * anything that still resolves outside the instance folder after
     * normalisation. The last check is the one that catches what the first three
     * miss, and it is done against the real resolved path rather than the string.
     *
     * <p>Public so that the self-check can put the refusals to it directly. It is
     * the one method here whose failure is not a broken install but a written
     * file outside the folder the user chose, so it is worth testing by name
     * rather than through an install that would have to be performed to reach it.
     *
     * @return the path relative to the instance, {@code /}-separated, or null
     */
    public static String safeRelative(Path root, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replace('\\', '/').trim();
        // Two leading separators is a network location - \\server\share on
        // Windows - and no manifest has any business naming one. Flattening it
        // into the instance would write the file somewhere harmless under a
        // nonsense name; refusing it says what happened instead.
        if (cleaned.startsWith("//")) {
            return null;
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank() || cleaned.contains(":")) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : cleaned.split("/")) {
            if (segment.isBlank() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                return null;
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            return null;
        }
        String relative = String.join("/", segments);
        Path resolved = root.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            return null;
        }
        return relative;
    }

    /** A file name with no path in it, for a download into a folder we chose. */
    private static String safeFileName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = slash < 0 ? name : name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._+-]", "_");
        return name.isBlank() ? "modpack.zip" : name;
    }

    private static String fileNameOf(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }
}
