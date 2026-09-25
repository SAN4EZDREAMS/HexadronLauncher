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

import com.hexadron.launcher.json.Json;

/**
 * The skin picture kept for one Microsoft account, and the arm width it is
 * drawn with.
 *
 * <p>The picture is the file offered for upload to Mojang. The game never
 * reads it from here: a Microsoft account's skin and cape are kept by Mojang,
 * and the game fetches them from Mojang's own services. So nothing about a
 * launch depends on this record.
 *
 * <p>Earlier versions stored more fields in {@code skins.json}. Those fields
 * are ignored when the file is read, and are gone once it is saved again.
 */
public record SkinProfile(String skin, Model model) {

    /**
     * Which arm width the skin is drawn with.
     *
     * <p>Part of the texture metadata rather than of the picture: the same
     * 64x64 file renders as Steve or as Alex depending on this, and getting it
     * wrong is the classic "my arms look wrong" report.
     */
    public enum Model {
        CLASSIC("classic"),
        SLIM("slim");

        private final String id;

        Model(String id) {
            this.id = id;
        }

        /** The value Mojang's texture metadata and the skin upload API use. */
        public String id() {
            return id;
        }

        public static Model parse(String value) {
            return value != null && value.equalsIgnoreCase("slim") ? SLIM : CLASSIC;
        }
    }

    /** An account with no picture chosen yet. */
    public static SkinProfile empty() {
        return new SkinProfile(null, Model.CLASSIC);
    }

    public boolean hasSkin() {
        return skin != null && !skin.isBlank();
    }

    /** True when there is no picture to keep. */
    public boolean isEmpty() {
        return !hasSkin();
    }

    public SkinProfile withSkin(String value) {
        return new SkinProfile(value, model);
    }

    public SkinProfile withModel(Model value) {
        return new SkinProfile(skin, value);
    }

    public Json toJson() {
        Json json = Json.object().put("model", model.id());
        if (hasSkin()) {
            json.put("skin", skin);
        }
        return json;
    }

    public static SkinProfile fromJson(Json json) {
        return new SkinProfile(
                json.get("skin").asString(null),
                Model.parse(json.get("model").asString(null)));
    }
}
