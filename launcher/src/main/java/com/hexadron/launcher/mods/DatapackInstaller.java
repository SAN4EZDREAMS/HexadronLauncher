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
 * <p>A loader can still be <em>used</em>, and that is the part that is easy to
 * get wrong. Modrinth publishes much of its data pack catalogue twice: the plain
 * pack, and the same pack for a mod loader, whose version names as a required
 * dependency the mod that puts the pack in place. Which of the two is installed
 * is the user's answer to "without mods", and it decides both the file that is
 * downloaded and whether anything goes into {@code mods/} at all - see
 * {@link ContentKind.Narrowing#WITHOUT_MODS}.
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
     * @param loader           the instance's loader, used to pick which flavour
     *                         of the pack to take when {@code withoutMods} is
     *                         false
     * @param withoutMods      the user's answer to "without mods": true takes
     *                         the plain pack and touches nothing outside the
     *                         world, false takes the flavour this loader loads
     *                         and installs the mod that version requires
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

        // Vanilla stands for "as a plain data pack" here rather than for an
        // instance with no loader: it is what the request asks the platform for
        // when the answer to "without mods" is yes, whatever the instance runs.
        LoaderType wanted = withoutMods || loader == null || !loader.isModded()
                ? LoaderType.VANILLA : loader;

        Optional<ModFile> found = provider.resolveFile(
                ContentKind.DATAPACK, chosen.projectId(), minecraftVersion, wanted);
        if (found.isEmpty()) {
            throw new IOException(chosen.title() + ": no build for Minecraft " + minecraftVersion
                    + (wanted.isModded()
                            ? " on " + wanted.displayName()
                                    + " - try it without mods instead"
                            : " as a plain data pack"));
        }

        ModFile file = found.get();
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
        library.put(InstalledMod.of(chosen, file, ModOrigin.MANUAL, null));
        library.write();

        // The pack is in the world before anything is asked about mods. If the
        // mod cannot be fetched the player has a pack that will not load and a
        // line saying why, which is recoverable; the other order would leave a
        // jar in the folder for a pack that is not there.
        List<ModFile> needed = List.of();
        if (!withoutMods && wanted.isModded() && !file.dependencies().isEmpty()
                && mods != null && modsDir != null) {

            DatapackOwner owner = new DatapackOwner(world,
                    InstalledMod.keyOf(chosen.source(), chosen.projectId()), chosen.title());
            ModInstaller.Result result = mods.installRequirements(chosen.source(),
                    file.dependencies(), minecraftVersion, wanted, modsDir, owner, progress);
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
