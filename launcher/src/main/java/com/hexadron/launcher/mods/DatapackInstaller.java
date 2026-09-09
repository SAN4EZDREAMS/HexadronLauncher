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
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.net.DownloadTask;
import com.hexadron.launcher.net.Downloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Installs and removes data packs, in one world at a time.
 *
 * <h2>Why this is not {@link ModInstaller}</h2>
 *
 * <p>Two differences, and both are about what a data pack is.
 *
 * <p><b>No dependency walk.</b> A mod declares the mods it needs and the
 * installer follows them, because a missing dependency is a crash. A data pack
 * declares nothing of the kind: the format has no dependency field, packs that
 * need another say so in their description, and the game loads what is in the
 * folder in the order the player chooses. There is no graph to walk, and
 * inventing one would mean guessing.
 *
 * <p><b>No loader is needed.</b> A data pack is loaded by vanilla Minecraft, so
 * a profile with no mod loader can install one - the one thing in this window
 * that works on a plain instance.
 *
 * <h2>What goes into the world, and what cannot</h2>
 *
 * <p>Modrinth publishes much of its data pack catalogue twice, as two separate
 * versions of one project: the data pack, whose file is a {@code .zip}, and the
 * <em>mod build</em> of the same content, whose file is a {@code .jar}. Dungeons
 * and Taverns is the usual example - {@code 5.3.2 (Datapack)} is a zip and
 * {@code 5.3.2+mod (Fabric)} is a jar carrying the same data pack, applied to
 * every world by the loader instead of being copied into one.
 *
 * <p>Only the first of those is a data pack. Minecraft reads a zip or a folder
 * out of {@code saves/&lt;world&gt;/datapacks} and ignores everything else, so a
 * jar downloaded into that folder is a file nothing loads and nothing lists -
 * which is exactly what it looked like: the catalogue said "installed", the
 * world's own list stayed empty, and its pack count did not move. So the file
 * asked for here is always the data pack, whatever the instance runs, and a
 * project with no data pack build for this version is refused with the reason
 * rather than half-installed.
 *
 * <p>Installing both builds would be worse than either: the same pack would be
 * loaded twice, once from the world and once by the loader.
 *
 * <h2>What the loader is for, then</h2>
 *
 * <p>The mods the pack itself requires. Some data packs need one - a loader for
 * global packs, a library - and those are named as required dependencies of the
 * data pack version. They go into the instance's {@code mods} folder, recorded
 * against this pack, and "without mods" is the answer that says not to fetch
 * them. See {@link ContentKind.Choice#WITHOUT_MODS}.
 */
public final class DatapackInstaller {

    private final Map<ModProvider.Source, ModProvider> providers = new LinkedHashMap<>();
    private final Downloader downloader;

    /**
     * Where the mods a pack needs are installed.
     *
     * <p>Not this class's own job: a jar in {@code mods/} is recorded in the
     * instance's lock file, resolved against the instance's loader and may pull
     * dependencies of its own, and all of that is {@link ModInstaller}. This one
     * hands over the project ids the pack's version named and the pack they
     * belong to.
     */
    private final ModInstaller mods;

    public DatapackInstaller(Downloader downloader, ModInstaller mods,
                             ModProvider... providers) {
        this.downloader = downloader;
        this.mods = mods;
        for (ModProvider provider : providers) {
            this.providers.put(provider.source(), provider);
        }
    }

    /**
     * What came of an install.
     *
     * @param installed       the pack files that went into the world
     * @param mods            the jars the pack needed, which went into the
     *                        instance's mods folder rather than into the world
     * @param manualDownloads what could not be fetched and is worth a sentence:
     *                        an author who has turned off third-party downloads
     *                        on CurseForge, or a required mod with no build for
     *                        this instance
     */
    public record Result(List<ModFile> installed, List<ModFile> mods,
                         List<String> manualDownloads) {

        public Result {
            installed = List.copyOf(installed);
            mods = List.copyOf(mods);
            manualDownloads = List.copyOf(manualDownloads);
        }

        /** A pack that needed nothing else. */
        public Result(List<ModFile> installed, List<String> manualDownloads) {
            this(installed, List.of(), manualDownloads);
        }

        public boolean isClean() {
            return manualDownloads.isEmpty();
        }
    }

    /**
     * Installs one data pack into one world, and whatever it needs to be loaded.
     *
     * @param minecraftVersion the instance's version, used to pick the build -
     *                         a data pack does declare which versions it is for
     * @param loader           the instance's loader, for the mods the pack
     *                         requires. The pack file itself never depends on it
     * @param withoutMods      the user's answer to "without mods": true installs
     *                         the pack and touches nothing outside the world,
     *                         false also fetches the mods the pack names as
     *                         requirements
     * @param datapacksDir     that world's {@code datapacks} folder, created if
     *                         it is not there yet
     * @param modsDir          the instance's mods folder, for the pack's own
     *                         requirements
     * @param world            which world this is, recorded against those mods
     *                         so that removing the pack can find them again
     */
    public Result install(ModProvider.ProjectCard chosen, String minecraftVersion,
                          LoaderType loader, boolean withoutMods,
                          Path datapacksDir, Path modsDir, String world, Progress progress)
            throws IOException, InterruptedException {

        ModProvider provider = providers.get(chosen.source());
        if (provider == null || !provider.isAvailable()) {
            throw new IOException(chosen.source().displayName() + " is not configured");
        }

        // Vanilla stands for "as a data pack" rather than for an instance with
        // no loader. It is what this asks for every time, on every instance:
        // the world folder can load a data pack and nothing else, so the mod
        // build of a project is not a thing this installer may hand it.
        Optional<ModFile> found = provider.resolveFile(
                ContentKind.DATAPACK, chosen.projectId(), minecraftVersion, LoaderType.VANILLA);
        if (found.isEmpty()) {
            throw new IOException(chosen.title() + ": no data pack build for Minecraft "
                    + minecraftVersion + " - this project may publish this version as a mod"
                    + " only, and a mod is installed from Mods rather than into a world");
        }

        ModFile file = found.get();
        // The platform answered with something that is not a data pack. Refused
        // rather than written into the world: the game would not load it, the
        // world's list would not show it, and the record would still say the
        // pack was installed - which is a launcher contradicting itself in three
        // places at once.
        if (!ContentKind.DATAPACK.matches(file.fileName())) {
            throw new IOException(chosen.title() + " - " + file.fileName()
                    + " is not a data pack. Minecraft loads a zip or a folder out of a world's"
                    + " datapacks folder, and this is the project's mod build; install it from"
                    + " Mods instead");
        }
        List<String> manual = new ArrayList<>();
        if (!file.isDownloadable()) {
            Optional<ModFile> mirrored = mirrorOnModrinth(file);
            if (mirrored.isEmpty()) {
                manual.add(chosen.title() + " - " + file.fileName()
                        + " (the author has disabled third-party downloads)");
                return new Result(List.of(), manual);
            }
            file = mirrored.get();
            progress.log("%s is taken from Modrinth: the identical file, where it may be"
                    + " downloaded", file.fileName());
        }

        Files.createDirectories(datapacksDir);
        Path target = datapacksDir.resolve(file.fileName());
        progress.stage("Downloading " + file.fileName());
        downloader.run(List.of(DownloadTask.of(file.url(), target,
                file.sha1(), file.size(), file.fileName())), progress);

        ModLibrary library = DatapackScan.libraryOf(datapacksDir);
        // Whatever this project left in the folder last time, when it is not the
        // file just downloaded. Two cases, and both used to leave a file behind:
        // a newer version of the same pack, whose old zip then reappeared in the
        // list as one the player had put there themselves; and a jar written
        // here by a build that asked the platform for the wrong thing.
        String installedName = file.fileName();
        library.get(InstalledMod.keyOf(chosen.source(), chosen.projectId()))
                .map(previous -> previous.file().fileName())
                .filter(name -> !name.equals(installedName))
                .ifPresent(name -> discard(datapacksDir, name, progress));
        library.put(InstalledMod.of(chosen, file, ModOrigin.MANUAL, null));
        library.write();

        // The pack is in the world before anything is asked about mods. If a
        // required mod cannot be fetched the player has a pack that will not
        // work and a line saying why, which is recoverable; the other order
        // would leave a jar in the folder for a pack that is not there.
        List<ModFile> needed = List.of();
        if (!withoutMods && loader != null && loader.isModded()
                && !file.dependencies().isEmpty() && mods != null && modsDir != null) {

            DatapackOwner owner = new DatapackOwner(world,
                    InstalledMod.keyOf(chosen.source(), chosen.projectId()), chosen.title());
            ModInstaller.Result result = mods.installRequirements(chosen.source(),
                    file.dependencies(), minecraftVersion, loader, modsDir, owner, progress);
            needed = result.installed();
            manual = new ArrayList<>(manual);
            manual.addAll(result.manualDownloads());
            manual.addAll(result.skipped());
        }

        return new Result(List.of(file), needed, manual);
    }

    /**
     * Removes one data pack the launcher installed.
     *
     * <p>Deleted rather than sent to the recycle bin, because the record says
     * where it came from and it can be installed again - the same distinction
     * {@link ModScan#discard} draws for the packs the launcher did not download.
     */
    public int remove(String key, Path datapacksDir, Path modsDir, String world,
                      Progress progress) throws IOException {

        ModLibrary library = DatapackScan.libraryOf(datapacksDir);
        InstalledMod pack = library.get(key).orElseThrow(
                () -> new IOException("no data pack recorded under " + key));
        String name = pack.file().fileName();
        if (Files.deleteIfExists(datapacksDir.resolve(name))
                || Files.deleteIfExists(datapacksDir.resolve(name + DatapackScan.DISABLED_SUFFIX))) {
            progress.log("Removed %s", name);
        }
        library.forget(key);
        library.write();

        // And the jar it needed, which is in the instance's folder rather than
        // in the world's. Left behind it would be a mod nobody can account for:
        // the pack that explains it is gone, and its row would have said so.
        return mods == null || modsDir == null
                ? 0 : mods.removeDatapackMods(world, key, modsDir, progress);
    }

    /**
     * Throws away a file this installer wrote here before.
     *
     * <p>Deleted rather than sent to the recycle bin, for the same reason
     * {@link #remove} deletes: the record says where it came from, so it can be
     * fetched again. Both names, because the player may have switched it off.
     */
    private static void discard(Path datapacksDir, String fileName, Progress progress) {
        try {
            if (Files.deleteIfExists(datapacksDir.resolve(fileName))
                    || Files.deleteIfExists(datapacksDir.resolve(
                            fileName + DatapackScan.DISABLED_SUFFIX))) {
                progress.log("Replaced %s", fileName);
            }
        } catch (IOException | RuntimeException e) {
            // One stale file in a world folder. The install itself is fine, and
            // the list will show the leftover as a pack the player put there.
            progress.log("Could not remove the previous %s: %s", fileName, e);
        }
    }

    private Optional<ModFile> mirrorOnModrinth(ModFile file) throws InterruptedException {
        if (file.source() == ModProvider.Source.MODRINTH
                || file.sha1() == null || file.sha1().isBlank()) {
            return Optional.empty();
        }
        if (!(providers.get(ModProvider.Source.MODRINTH) instanceof ModrinthProvider modrinth)
                || !modrinth.isAvailable()) {
            return Optional.empty();
        }
        try {
            return modrinth.resolveByHash(file.sha1())
                    .map(mirror -> new ModFile(file.projectId(), file.projectSlug(),
                            file.versionId(), file.displayName(), file.fileName(),
                            mirror.url(), file.sha1(), file.size(), file.dependencies(),
                            file.source()))
                    .filter(ModFile::isDownloadable);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
