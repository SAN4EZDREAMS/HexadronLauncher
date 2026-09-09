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

import java.util.List;

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

    /**
     * Every platform loader tag whose files this loader can actually run,
     * most specific first.
     *
     * <p>Asking a platform for one tag is asking the wrong question for Quilt.
     * Quilt Loader runs Fabric mods unchanged - that is its stated compatibility
     * promise and the reason almost nobody publishes a separate Quilt build - so
     * a search filtered to {@code quilt} alone returns a near-empty catalogue and
     * makes the launcher look as though Quilt has no mods. The one thing Quilt
     * does <em>not</em> take from Fabric is Fabric API itself, which is replaced
     * by Quilted Fabric API; that substitution belongs in the pack file, not
     * here, because it is a fact about one project rather than about the loader.
     *
     * <p>Forge and NeoForge are deliberately not made compatible with each
     * other. NeoForge forked Forge at 1.20.1 and the two have diverged: a Forge
     * jar on modern NeoForge is a crash, not a fallback.
     *
     * @return an empty list for {@link #VANILLA}, which loads nothing
     */
    public List<String> platformIds() {
        return switch (this) {
            case VANILLA -> List.of();
            case FABRIC -> List.of("fabric");
            case QUILT -> List.of("quilt", "fabric");
            case FORGE -> List.of("forge");
            case NEOFORGE -> List.of("neoforge");
        };
    }

    /**
     * The single tag to send when a platform will only accept one.
     *
     * <p>CurseForge's {@code modLoaderType} is one number, so Quilt has to
     * choose, and it chooses {@code fabric}: that is where the files a Quilt
     * profile can run actually are. A Quilt-only mod on CurseForge is rare
     * enough that missing it costs less than missing everything else.
     *
     * @return null for {@link #VANILLA}, which is no filter at all
     */
    public String searchPlatformId() {
        List<String> ids = platformIds();
        if (ids.isEmpty()) {
            return null;
        }
        return this == QUILT ? "fabric" : ids.get(0);
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
