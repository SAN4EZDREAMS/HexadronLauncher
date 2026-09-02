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
import com.hexadron.launcher.util.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
     * @return the path, or null when the launcher was built without the wrapper
     *         jar - in which case the caller falls back to passing the token on
     *         the command line rather than refusing to start the game
     */
    public static Path ensureExtracted(GameDirs dirs) {
        try (InputStream embedded = LaunchWrapperJar.class.getResourceAsStream(RESOURCE)) {
            if (embedded == null) {
                return null;
            }
            byte[] bytes = embedded.readAllBytes();
            if (bytes.length == 0) {
                return null;
            }
            Path directory = dirs.root().resolve("wrapper");
            Files.createDirectories(directory);
            Path jar = directory.resolve("hexadron-launchwrapper.jar");

            String expected = Hashes.sha256(bytes);
            if (Files.isRegularFile(jar) && expected.equals(Hashes.sha256(Files.readAllBytes(jar)))) {
                return jar;
            }
            Path temporary = directory.resolve("hexadron-launchwrapper.jar.tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING);
            return jar;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
