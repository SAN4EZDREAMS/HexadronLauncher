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

import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What a {@code pack.mcmeta} pack says about itself.
 *
 * <p>Two kinds of thing carry this file and Minecraft reads it the same way in
 * both: a data pack in a world's folder, and a resource pack in the instance's.
 * Both are a zip or a folder, both state a {@code pack_format} and a
 * description, and both may ship a {@code pack.png} that the game shows beside
 * them. So the reader is one reader, rather than one per folder that drifts from
 * the other.
 *
 * <p>Everything here is best-effort by design. A pack with no readable metadata
 * is still a pack the player put there, and the row for it says the file name
 * instead of a description - which is what the folder can honestly report.
 *
 * @param format      {@code pack_format} as a line to show, or null
 * @param description one line, or null
 * @param iconPath    where the pack's own picture is inside its zip, or null -
 *                    always null for a folder, which is not an archive to read
 *                    an entry out of
 */
public record PackMeta(String format, String description, String iconPath) {

    /** What could not be read. */
    public static final PackMeta NONE = new PackMeta(null, null, null);

    /** A pack's own description of itself. */
    public static final String META_FILE = "pack.mcmeta";

    /** A pack's own picture, which Minecraft shows beside it. */
    public static final String ICON_FILE = "pack.png";

    /** Reads the metadata of a zip or a folder. Never throws. */
    public static PackMeta of(Path file) {
        if (file == null) {
            return NONE;
        }
        if (Files.isDirectory(file)) {
            Path meta = file.resolve(META_FILE);
            if (!Files.isRegularFile(meta)) {
                return NONE;
            }
            try {
                return read(Json.parse(Files.readString(meta, StandardCharsets.UTF_8)), null);
            } catch (IOException | RuntimeException e) {
                return NONE;
            }
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(META_FILE);
            if (entry == null) {
                return NONE;
            }
            String icon = zip.getEntry(ICON_FILE) == null ? null : ICON_FILE;
            try (InputStream in = zip.getInputStream(entry)) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return read(Json.parse(text), icon);
            }
        } catch (IOException | RuntimeException e) {
            return NONE;
        }
    }

    private static PackMeta read(Json root, String iconPath) {
        Json pack = root.get("pack");
        int format = pack.get("pack_format").asInt(-1);
        return new PackMeta(format < 0 ? null : "pack format " + format,
                describe(pack.get("description")), iconPath);
    }

    /**
     * A pack's description, which may not be a string.
     *
     * <p>Minecraft accepts a raw JSON text component here, so a pack's
     * description is sometimes {@code "Fancy pack"}, sometimes
     * {@code {"text":"Fancy pack","color":"gold"}}, and sometimes a list of
     * those. All three say the same sentence, and the row wants the sentence.
     */
    private static String describe(Json description) {
        if (description.isString()) {
            String text = description.asString("");
            return text.isBlank() ? null : text;
        }
        if (description.isObject()) {
            String text = description.get("text").asString("");
            return text.isBlank() ? null : text;
        }
        if (description.isArray()) {
            StringBuilder line = new StringBuilder();
            for (Json part : description.elements()) {
                if (part.isString()) {
                    line.append(part.asString(""));
                } else if (part.isObject()) {
                    line.append(part.get("text").asString(""));
                }
            }
            String text = line.toString().trim();
            return text.isBlank() ? null : text;
        }
        return null;
    }

    /**
     * True when this zip is a pack rather than merely a zip.
     *
     * <p>Unlike a mod jar - where a missing descriptor is a dialect the reader
     * does not know and the file is still a mod - {@code pack.mcmeta} is not
     * optional: Minecraft refuses a pack without one, so a zip without one is
     * not a pack. Also true for a zip that has one nested one folder down, which
     * Minecraft itself refuses; the reason it is looked for is so that the
     * refusal can be about the right thing.
     */
    public static boolean isPackArchive(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry(META_FILE) != null) {
                return true;
            }
            return zip.stream().anyMatch(entry -> entry.getName().endsWith("/" + META_FILE));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
