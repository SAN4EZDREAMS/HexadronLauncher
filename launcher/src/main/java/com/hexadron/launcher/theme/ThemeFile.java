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

package com.hexadron.launcher.theme;

import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * A theme as one file, to keep or to give to somebody: {@code .hxtheme}.
 *
 * <p>JSON, with the background picture inside it rather than beside it, so a
 * theme that is passed on arrives whole. The picture's path in the data folder
 * is not written: it means nothing on another computer, and reading one back
 * would let a file choose where on disk the launcher looks.
 */
public final class ThemeFile {

    private ThemeFile() {
    }

    public static final String EXTENSION = "hxtheme";
    static final String FORMAT = "hexadron-theme";

    /** A theme file is the picture and a page of settings; larger is not one. */
    static final long MAX_BYTES = BackdropImage.MAX_BYTES * 4 / 3 + 64 * 1024;

    public static void write(Appearance appearance, Path dataRoot, Path target) throws IOException {
        Json settings = appearance.toJson();
        settings.remove("background");
        Json json = Json.object().put("format", FORMAT).put("version", 1).put("appearance", settings);
        if (appearance.hasBackground()) {
            Path picture = dataRoot.resolve(appearance.background());
            if (Files.isRegularFile(picture)) {
                String name = picture.getFileName().toString();
                json.put("background", Json.object()
                        .put("type", name.substring(name.lastIndexOf('.') + 1))
                        .put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(picture))));
            }
        }
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(partial, json.toPrettyString(), StandardCharsets.UTF_8);
        Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Reads a theme file, copying its picture into the data folder.
     *
     * @throws IOException when it is not a theme file, or its picture is not a picture
     */
    public static Appearance read(Path source, Path dataRoot) throws IOException {
        if (Files.size(source) > MAX_BYTES) {
            throw new IOException("too large for a theme file");
        }
        Json json;
        try {
            json = Json.parse(Files.readString(source, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("not a theme file: " + e.getMessage());
        }
        if (!FORMAT.equals(json.get("format").asString(""))) {
            throw new IOException("not a theme file");
        }
        Json settings = json.get("appearance");
        if (settings.isObject()) {
            settings.remove("background");
        }
        Appearance appearance = Appearance.fromJson(settings);
        Json picture = json.get("background");
        if (picture.isObject()) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(picture.get("data").asString(""));
            } catch (IllegalArgumentException e) {
                throw new IOException("the picture in the theme file is damaged");
            }
            appearance = appearance.withBackground(
                    BackdropImage.importBytes(bytes, picture.get("type").asString(""), dataRoot));
        }
        return appearance;
    }
}
