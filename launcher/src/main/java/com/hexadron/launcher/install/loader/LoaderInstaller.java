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
