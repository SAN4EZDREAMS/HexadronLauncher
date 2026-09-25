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

package com.hexadron.launcher.skin;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.util.Hashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The skin pictures on this machine, and which account each one is kept for.
 *
 * <h2>Pictures are copied in, and named by their content</h2>
 *
 * <p>The same rule as profile icons, for the same three reasons: the skin
 * survives the file it came from being renamed or deleted, two accounts given
 * the same picture share one copy, and nothing in {@code skins.json} is ever a
 * path the launcher opens - a bare file name resolved inside one folder cannot
 * be edited into {@code C:\Windows\...}.
 *
 * <h2>What counts as a skin</h2>
 *
 * <p>A skin is 64x64, or 64x32 for the format used before 1.8. Anything else is
 * refused here, while the user is still looking at the file chooser - rather
 * than at upload, where Mojang refuses it with a less useful message.
 */
public final class SkinStore {

    /** Largest picture accepted, in bytes. A 64x64 PNG is under two kilobytes. */
    public static final long MAXIMUM_BYTES = 2L * 1024 * 1024;

    private final GameDirs dirs;

    /** Account id to what that account wears. */
    private final Map<String, SkinProfile> profiles = new LinkedHashMap<>();

    public SkinStore(GameDirs dirs) {
        this.dirs = dirs;
    }

    /** What this account wears. Never null; an unknown account wears nothing. */
    public synchronized SkinProfile of(String accountId) {
        return profiles.getOrDefault(accountId, SkinProfile.empty());
    }

    public synchronized void put(String accountId, SkinProfile profile) {
        if (accountId == null) {
            return;
        }
        if (profile == null || profile.isEmpty()) {
            profiles.remove(accountId);
            return;
        }
        profiles.put(accountId, profile);
    }

    /** Forgets an account's skin. The picture files are left where they are. */
    public synchronized void remove(String accountId) {
        profiles.remove(accountId);
    }

    /** The file a stored name resolves to, or null when there is no such name. */
    public Path file(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        // Resolved as a bare name inside one directory, never as a path. A value
        // hand-edited into skins.json cannot walk out of here.
        Path file = dirs.skins().resolve(Path.of(name).getFileName().toString());
        return Files.isRegularFile(file) ? file : null;
    }

    /**
     * Copies a chosen skin picture into the store.
     *
     * @return the file name to record on the profile
     * @throws IOException when the file is not a PNG of an accepted size. The
     *                     message is shown to the user, so it says which.
     */
    public String store(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("no such file: " + source);
        }
        long bytes = Files.size(source);
        if (bytes > MAXIMUM_BYTES) {
            throw new IOException("the picture is " + (bytes / 1024) + " KB; the limit is "
                    + (MAXIMUM_BYTES / 1024) + " KB");
        }

        // Read as a picture before it is copied, not after. A .png that is not a
        // PNG has to fail while the file chooser is still open.
        int[] size = PngSize.read(source);
        if (size == null) {
            throw new IOException("the file could not be read as a PNG: " + source.getFileName());
        }
        if (!accepted(size[0], size[1])) {
            throw new IOException("a skin is 64x64 or 64x32, or a multiple of either"
                    + "; this file is " + size[0] + "x" + size[1]);
        }

        Files.createDirectories(dirs.skins());
        String name = "skin-" + Hashes.sha1(source).substring(0, 16) + ".png";
        Path target = dirs.skins().resolve(name);
        if (!Files.exists(target)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return name;
    }

    /**
     * Whether a picture of this size can be worn.
     *
     * <p>Sizes are checked as multiples rather than as exact numbers. The
     * layout of a skin is a map, and a sheet at twice or four times the
     * resolution is the same map drawn finer - which is what a high-resolution
     * skin is, and refusing one because it is not literally 64 pixels wide
     * would be refusing a better version of an accepted file.
     *
     * <p>The two shapes that matter are the square sheet used since 1.8 and the
     * half-height one from before it.
     */
    private static boolean accepted(int width, int height) {
        return multipleOf64(width) && (height == width || height * 2 == width);
    }

    private static boolean multipleOf64(int width) {
        return width >= 64 && width % 64 == 0;
    }

    public synchronized SkinStore load() throws IOException {
        profiles.clear();
        Path file = dirs.skinsFile();
        if (!Files.isRegularFile(file)) {
            return this;
        }
        Json json = Json.read(file);
        json.get("accounts").fields().forEach((accountId, entry) ->
                profiles.put(accountId, SkinProfile.fromJson(entry)));
        return this;
    }

    public synchronized void save() throws IOException {
        Json accounts = Json.object();
        profiles.forEach((accountId, profile) -> accounts.put(accountId, profile.toJson()));
        Files.createDirectories(dirs.skins());
        Json.object().put("accounts", accounts).write(dirs.skinsFile());
    }
}
