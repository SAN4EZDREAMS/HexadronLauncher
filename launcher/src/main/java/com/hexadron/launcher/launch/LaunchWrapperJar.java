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

package com.hexadron.launcher.launch;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.util.FilePermissions;
import com.hexadron.launcher.util.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Puts {@code com.hexadron.wrapper.GameLaunchWrapper} on disk so it can be added
 * to the game's classpath.
 *
 * <p>The wrapper is built as its own jar by a separate Gradle source set and
 * embedded in the launcher's resources. It is not simply left inside the
 * launcher jar and that jar added to the game's classpath, for two reasons:
 * the launcher jar also carries {@code lang/*.properties} and {@code ui/*.css}
 * at paths a mod could plausibly use, and a classpath entry containing the whole
 * launcher gives a hostile mod the launcher's own classes to work with. The
 * wrapper jar contains one class and nothing else.
 *
 * <p>Extraction is content-addressed: if the file on disk already hashes to the
 * same value as the embedded copy, it is left alone, so a launch does not
 * rewrite it every time and a partially written file from an interrupted launch
 * is replaced rather than trusted.
 */
public final class LaunchWrapperJar {

    private static final String RESOURCE = "/wrapper/hexadron-launchwrapper.jar";

    private LaunchWrapperJar() {
    }

    /**
     * Returns the wrapper jar on disk, extracting it if needed.
     *
     * <p>The file name carries the hash. A game still running from an older
     * launcher keeps its jar open, and on Windows an open file cannot be
     * replaced; with one name per content the new jar never has to overwrite
     * the old one.
     *
     * @return the path, or null only when this build carries no wrapper jar
     * @throws IOException when the jar is in the build but could not be put on
     *                     disk. The caller must not fall back to the command
     *                     line for an account with a real session token.
     */
    public static Path ensureExtracted(GameDirs dirs) throws IOException {
        byte[] bytes;
        try (InputStream embedded = LaunchWrapperJar.class.getResourceAsStream(RESOURCE)) {
            if (embedded == null) {
                return null;
            }
            bytes = embedded.readAllBytes();
        }
        if (bytes.length == 0) {
            return null;
        }
        Path directory = FilePermissions.createRestrictedDirectory(dirs.root().resolve("wrapper"));
        String expected = Hashes.sha256(bytes);
        Path jar = directory.resolve("hexadron-launchwrapper-" + expected.substring(0, 16) + ".jar");

        if (Files.isRegularFile(jar) && expected.equals(Hashes.sha256(Files.readAllBytes(jar)))) {
            return jar;
        }
        // Owner-only, written to a temporary file and moved into place.
        FilePermissions.writeRestricted(jar, bytes);
        if (!expected.equals(Hashes.sha256(Files.readAllBytes(jar)))) {
            throw new IOException("the launch wrapper on disk does not match the one in the launcher");
        }
        return jar;
    }
}
