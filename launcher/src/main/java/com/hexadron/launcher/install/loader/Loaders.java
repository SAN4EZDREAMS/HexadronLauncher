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

    /** Loaders that can install and launch today. */
    public static List<LoaderType> workingLoaders() {
        return List.of(LoaderType.VANILLA, LoaderType.FABRIC, LoaderType.QUILT);
    }

    /** Every loader the UI offers, including those whose install is still pending. */
    public static List<LoaderType> allLoaders() {
        return List.of(LoaderType.VANILLA, LoaderType.FABRIC, LoaderType.QUILT,
                LoaderType.NEOFORGE, LoaderType.FORGE);
    }
}
