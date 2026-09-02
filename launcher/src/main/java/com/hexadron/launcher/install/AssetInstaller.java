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

package com.hexadron.launcher.install;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.meta.AssetIndex;
import com.hexadron.launcher.meta.VersionJson;
import com.hexadron.launcher.net.DownloadTask;
import com.hexadron.launcher.net.Downloader;
import com.hexadron.launcher.util.Hashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Downloads the asset index and every object it references. */
public final class AssetInstaller {

    private final GameDirs dirs;
    private final Downloader downloader;

    public AssetInstaller(GameDirs dirs, Downloader downloader) {
        this.dirs = dirs;
        this.downloader = downloader;
    }

    /**
     * Ensures every asset for {@code version} is present.
     *
     * @param gameDir the profile's game directory, needed only for pre-1.6
     *                versions that read assets from {@code <gameDir>/resources}
     * @return the parsed index, so the launcher can decide what to pass as
     *         {@code --assetsDir}
     */
    public AssetIndex install(VersionJson version, Path gameDir, Progress progress)
            throws IOException, InterruptedException {

        VersionJson.AssetIndexInfo info = version.assetIndex();
        String indexId = version.assetsId();

        Path indexFile = dirs.assetIndexFile(indexId);
        if (info != null && info.url() != null) {
            progress.stage("Downloading asset index");
            downloader.fetch(DownloadTask.of(info.url(), indexFile, info.sha1(), info.size(),
                    "asset index " + indexId));
        } else if (!Files.isRegularFile(indexFile)) {
            throw new IOException("version " + version.id()
                    + " declares no assetIndex and none is cached for '" + indexId + "'");
        }

        AssetIndex index = AssetIndex.parse(indexId, Json.read(indexFile));

        List<DownloadTask> tasks = new ArrayList<>(index.size());
        for (Map.Entry<String, AssetIndex.AssetObject> entry : index.objects().entrySet()) {
            AssetIndex.AssetObject object = entry.getValue();
            Path destination = dirs.assetObject(object.hash());
            tasks.add(DownloadTask.of(object.url(), destination, object.hash(), object.size(), entry.getKey()));
        }

        progress.stage("Downloading assets (" + index.size() + " files)");
        downloader.run(tasks, progress);

        if (index.needsMaterialisation()) {
            materialise(index, gameDir, progress);
        }
        return index;
    }

    /**
     * Copies the hashed store into a readable tree for versions that predate the
     * object store: 1.6 reads {@code assets/virtual/<index>}, and anything older
     * reads {@code <gameDir>/resources}.
     */
    private void materialise(AssetIndex index, Path gameDir, Progress progress) throws IOException {
        Path target = index.mapToResources()
                ? gameDir.resolve("resources")
                : dirs.virtualAssets(index.id());

        progress.stage("Materialising legacy assets");
        Files.createDirectories(target);

        int done = 0;
        for (Map.Entry<String, AssetIndex.AssetObject> entry : index.objects().entrySet()) {
            Path source = dirs.assetObject(entry.getValue().hash());
            Path destination = target.resolve(entry.getKey().replace('/', java.io.File.separatorChar));

            if (!Files.isRegularFile(source)) {
                continue;
            }
            // Skip when already materialised and identical, so relaunching is fast.
            if (Files.isRegularFile(destination)
                    && Hashes.matchesSha1(destination, entry.getValue().hash())) {
                continue;
            }
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            progress.items(++done, index.size());
        }
    }

    /**
     * The directory to pass as {@code --assetsDir}: the virtual tree for 1.6,
     * otherwise the shared hashed store.
     */
    public Path assetsDirFor(AssetIndex index) {
        return index.virtual() ? dirs.virtualAssets(index.id()) : dirs.assets();
    }
}
