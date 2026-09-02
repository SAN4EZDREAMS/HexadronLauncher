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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry of the available {@link LoaderInstaller} implementations. */
public final class Loaders {

    private static final Map<LoaderType, LoaderInstaller> INSTALLERS = build();

    private Loaders() {
    }

    private static Map<LoaderType, LoaderInstaller> build() {
        Map<LoaderType, LoaderInstaller> map = new LinkedHashMap<>();
        map.put(LoaderType.FABRIC, FabricLikeInstaller.fabric());
        map.put(LoaderType.QUILT, FabricLikeInstaller.quilt());
        map.put(LoaderType.NEOFORGE, new NeoForgeInstaller());
        map.put(LoaderType.FORGE, new ForgeInstaller());
        return Map.copyOf(map);
    }

    /** @throws IllegalArgumentException for {@link LoaderType#VANILLA}, which needs no installer */
    public static LoaderInstaller installerFor(LoaderType type) {
        LoaderInstaller installer = INSTALLERS.get(type);
        if (installer == null) {
            throw new IllegalArgumentException("no installer for loader " + type);
        }
        return installer;
    }

    /**
     * Every loader the interface offers.
     *
     * <p>There used to be a second, shorter list here of the loaders that could
     * actually be installed, because Forge and NeoForge could only list their
     * builds. They install now, so the two lists were the same list and one of
     * them had to go.
     */
    public static List<LoaderType> allLoaders() {
        return List.of(LoaderType.VANILLA, LoaderType.FABRIC, LoaderType.QUILT,
                LoaderType.NEOFORGE, LoaderType.FORGE);
    }
}
