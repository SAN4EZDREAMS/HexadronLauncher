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
 * <p><b>No loader.</b> A data pack is loaded by vanilla Minecraft. A profile with
 * no mod loader can install one, which is the one thing in this window that works
 * on a plain instance - so nothing here asks about loaders.
 */
public final class DatapackInstaller {

    private final Map<ModProvider.Source, ModProvider> providers = new LinkedHashMap<>();
    private final Downloader downloader;

    public DatapackInstaller(Downloader downloader, ModProvider... providers) {
        this.downloader = downloader;
        for (ModProvider provider : providers) {
            this.providers.put(provider.source(), provider);
        }
    }

    /**
     * What came of an install.
     *
     * @param manualDownloads the one refusal worth a sentence: an author who has
     *                        turned off third-party downloads on CurseForge
     */
    public record Result(List<ModFile> installed, List<String> manualDownloads) {

        public Result {
            installed = List.copyOf(installed);
            manualDownloads = List.copyOf(manualDownloads);
        }

        public boolean isClean() {
            return manualDownloads.isEmpty();
        }
    }

    /**
     * Installs one data pack into one world.
     *
     * @param minecraftVersion the instance's version, used to pick the build -
     *                         a data pack does declare which versions it is for
     * @param datapacksDir     that world's {@code datapacks} folder, created if
     *                         it is not there yet
     */
    public Result install(ModProvider.ProjectCard chosen, String minecraftVersion,
                          Path datapacksDir, Progress progress)
            throws IOException, InterruptedException {

        ModProvider provider = providers.get(chosen.source());
        if (provider == null || !provider.isAvailable()) {
            throw new IOException(chosen.source().displayName() + " is not configured");
        }

        Optional<ModFile> found = provider.resolveFile(
                ContentKind.DATAPACK, chosen.projectId(), minecraftVersion, LoaderType.VANILLA);
        if (found.isEmpty()) {
            throw new IOException(chosen.title() + ": no build for Minecraft " + minecraftVersion);
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

        return new Result(List.of(file), manual);
    }

    /**
     * Removes one data pack the launcher installed.
     *
     * <p>Deleted rather than sent to the recycle bin, because the record says
     * where it came from and it can be installed again - the same distinction
     * {@link ModScan#discard} draws for the packs the launcher did not download.
     */
    public void remove(String key, Path datapacksDir, Progress progress) throws IOException {
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
