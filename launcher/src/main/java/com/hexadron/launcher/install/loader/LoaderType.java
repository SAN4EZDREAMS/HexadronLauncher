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

package com.hexadron.launcher.install.loader;

/** The mod loaders the launcher knows about. */
public enum LoaderType {

    /** No loader - plain Minecraft. */
    VANILLA("vanilla", "Vanilla", false),

    FABRIC("fabric", "Fabric", true),
    QUILT("quilt", "Quilt", true),
    FORGE("forge", "Forge", true),
    NEOFORGE("neoforge", "NeoForge", true);

    private final String id;
    private final String displayName;
    private final boolean modded;

    LoaderType(String id, String displayName, boolean modded) {
        this.id = id;
        this.displayName = displayName;
        this.modded = modded;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isModded() {
        return modded;
    }

    /**
     * The loader identifier used by mod distribution platforms.
     * Modrinth and CurseForge both key mod files by this string.
     */
    public String platformId() {
        return id;
    }

    public static LoaderType fromId(String id) {
        if (id == null || id.isBlank()) {
            return VANILLA;
        }
        for (LoaderType type : values()) {
            if (type.id.equalsIgnoreCase(id.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown loader: " + id);
    }
}
