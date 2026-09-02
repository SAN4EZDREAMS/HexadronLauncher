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

package com.hexadron.launcher.install.loader;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.VersionInstaller;

import java.io.IOException;
import java.util.List;

/**
 * Installs a mod loader on top of a Minecraft version.
 *
 * <p>Every implementation writes a manifest into
 * {@code versions/<versionId>/<versionId>.json} that declares
 * {@code inheritsFrom: <minecraftVersion>}, then returns that id. From that
 * point the generic {@link VersionInstaller} handles libraries, natives and
 * assets identically for every loader.
 */
public interface LoaderInstaller {

    LoaderType type();

    /**
     * The Minecraft versions this loader actually has builds for.
     *
     * @param versions the supported ids
     * @param complete true when the list is the whole truth. False means the
     *                 loader publishes no usable version index and what is here
     *                 was derived from build numbers, so a version missing from
     *                 it is "not known to be supported", not "known to be
     *                 unsupported" - and must therefore not be hidden
     */
    record SupportedVersions(List<String> versions, boolean complete) {

        public SupportedVersions {
            versions = List.copyOf(versions);
        }

        /** Nothing could be determined; every Minecraft version stays on offer. */
        public static SupportedVersions unknown() {
            return new SupportedVersions(List.of(), false);
        }

        public boolean isUsableAsFilter() {
            return complete && !versions.isEmpty();
        }

        public boolean supports(String minecraftVersion) {
            return versions.contains(minecraftVersion);
        }
    }

    /**
     * Which Minecraft versions this loader can be installed on.
     *
     * <p>Default: unknown. An installer that cannot answer honestly says so
     * rather than returning a guess the version picker would then enforce.
     */
    default SupportedVersions supportedMinecraftVersions() throws IOException, InterruptedException {
        return SupportedVersions.unknown();
    }

    /** Loader builds available for {@code minecraftVersion}, newest first. */
    List<LoaderVersion> availableVersions(String minecraftVersion) throws IOException, InterruptedException;

    /** The build a user should get when they do not choose one, or null when none exists. */
    default LoaderVersion recommendedVersion(String minecraftVersion) throws IOException, InterruptedException {
        List<LoaderVersion> versions = availableVersions(minecraftVersion);
        return versions.stream().filter(LoaderVersion::stable).findFirst()
                .orElse(versions.isEmpty() ? null : versions.get(0));
    }

    /**
     * Writes the loader's version manifest to disk.
     *
     * @return the installed version id, to be passed to
     *         {@link VersionInstaller#install}
     */
    String install(String minecraftVersion, LoaderVersion loaderVersion,
                   VersionInstaller installer, Progress progress) throws IOException, InterruptedException;

    /** Thrown when a loader has no build for the requested Minecraft version. */
    class UnsupportedVersionException extends IOException {
        public UnsupportedVersionException(LoaderType type, String minecraftVersion) {
            super(type.displayName() + " has no build for Minecraft " + minecraftVersion);
        }
    }
}
