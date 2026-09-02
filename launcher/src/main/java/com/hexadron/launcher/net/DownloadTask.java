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

package com.hexadron.launcher.net;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One file to fetch.
 *
 * @param urls        candidate sources, tried in order. More than one matters for
 *                    libraries that exist on several maven mirrors (Forge in
 *                    particular publishes to both its own maven and Maven Central).
 * @param destination final path on disk
 * @param sha1        expected SHA-1, or {@code null}/blank when the source
 *                    publishes no checksum
 * @param size        expected size in bytes, or {@code -1} when unknown; used
 *                    only for progress estimation, never for validation
 * @param description short label for logs and progress
 * @param executable  whether to set the POSIX execute bit after writing (java
 *                    binaries inside a downloaded runtime)
 */
public record DownloadTask(List<String> urls, Path destination, String sha1, long size,
                           String description, boolean executable) {

    public DownloadTask {
        Objects.requireNonNull(urls, "urls");
        Objects.requireNonNull(destination, "destination");
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("a download task needs at least one URL: " + destination);
        }
        urls = List.copyOf(urls);
        sha1 = (sha1 == null || sha1.isBlank()) ? null : sha1.trim().toLowerCase(java.util.Locale.ROOT);
        description = (description == null || description.isBlank())
                ? destination.getFileName().toString()
                : description;
    }

    public static DownloadTask of(String url, Path destination, String sha1, long size, String description) {
        return new DownloadTask(List.of(url), destination, sha1, size, description, false);
    }

    public static DownloadTask of(String url, Path destination, String description) {
        return new DownloadTask(List.of(url), destination, null, -1, description, false);
    }

    public DownloadTask withExtraUrl(String url) {
        if (urls.contains(url)) {
            return this;
        }
        return new DownloadTask(
                java.util.stream.Stream.concat(urls.stream(), java.util.stream.Stream.of(url)).toList(),
                destination, sha1, size, description, executable);
    }

    public DownloadTask asExecutable() {
        return new DownloadTask(urls, destination, sha1, size, description, true);
    }

    /** Size for progress purposes; unknown sizes are counted as zero. */
    public long sizeForProgress() {
        return Math.max(size, 0);
    }
}
