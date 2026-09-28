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

import com.hexadron.launcher.install.loader.LoaderType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Which loader a jar is for, read from the descriptors it carries, and
 * whether this profile's loader loads it.
 *
 * <p>Judged by the descriptor files, not by the one {@link LocalModInfo}
 * reads first: a jar made for several loaders carries a descriptor for each,
 * and any one that fits is enough. A jar with none - a plain library, a Forge
 * 1.12 mod without {@code mcmod.info} - is not judged at all.
 */
public final class LoaderCheck {

    /** What a jar declares itself as. */
    public enum Descriptor { FABRIC, QUILT, NEOFORGE, FORGE_TOML, FORGE_LEGACY }

    /** Mods that let a loader load the jars of another: Sinytra Connector, Kilt. */
    static final Set<String> BRIDGES_TO_FABRIC = Set.of("connector");
    static final Set<String> BRIDGES_TO_FORGE = Set.of("kilt");

    private LoaderCheck() {
    }

    /** The descriptors in a jar; empty when it has none or cannot be read. */
    public static Set<Descriptor> descriptors(Path jar) {
        Set<Descriptor> found = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry("fabric.mod.json") != null) {
                found.add(Descriptor.FABRIC);
            }
            if (zip.getEntry("quilt.mod.json") != null) {
                found.add(Descriptor.QUILT);
            }
            if (zip.getEntry("META-INF/neoforge.mods.toml") != null) {
                found.add(Descriptor.NEOFORGE);
            }
            if (zip.getEntry("META-INF/mods.toml") != null) {
                found.add(Descriptor.FORGE_TOML);
            }
            if (zip.getEntry("mcmod.info") != null) {
                found.add(Descriptor.FORGE_LEGACY);
            }
        } catch (IOException | RuntimeException e) {
            // Not a readable jar; the damaged-jar rules speak for it.
        }
        return found;
    }

    /**
     * True when a loader loads a jar with these descriptors.
     *
     * @param minecraftVersion NeoForge reads {@code mods.toml} only before 1.20.5
     * @param toFabric         a bridge that loads Fabric jars is installed
     * @param toForge          a bridge that loads Forge jars is installed
     */
    public static boolean loads(LoaderType loader, String minecraftVersion, Set<Descriptor> found,
                                boolean toFabric, boolean toForge) {
        if (found.isEmpty() || loader == null || loader == LoaderType.VANILLA) {
            return true;
        }
        return switch (loader) {
            case FABRIC -> found.contains(Descriptor.FABRIC)
                    || (toForge && found.contains(Descriptor.FORGE_TOML));
            case QUILT -> found.contains(Descriptor.FABRIC) || found.contains(Descriptor.QUILT);
            case FORGE -> found.contains(Descriptor.FORGE_TOML) || found.contains(Descriptor.FORGE_LEGACY)
                    || (toFabric && found.contains(Descriptor.FABRIC));
            case NEOFORGE -> found.contains(Descriptor.NEOFORGE)
                    || (found.contains(Descriptor.FORGE_TOML) && readsModsToml(minecraftVersion))
                    || (toFabric && found.contains(Descriptor.FABRIC));
            default -> true;
        };
    }

    /** NeoForge renamed its descriptor for 1.20.5; a version that cannot be read is taken as newer. */
    static boolean readsModsToml(String minecraftVersion) {
        return VersionRanges.compare(minecraftVersion, "1.20.5") < 0;
    }

    /** The mods switched on in this folder that this loader will not load. */
    public static List<ModEntry> wrongLoader(List<ModEntry> mods, LoaderType loader, String minecraftVersion) {
        List<ModEntry> wrong = new ArrayList<>();
        if (loader == null || loader == LoaderType.VANILLA) {
            return wrong;
        }
        boolean toFabric = false;
        boolean toForge = false;
        for (ModEntry mod : mods) {
            String id = mod.enabled() ? ModScan.descriptorOf(mod.path()).modId() : null;
            toFabric |= id != null && BRIDGES_TO_FABRIC.contains(id);
            toForge |= id != null && BRIDGES_TO_FORGE.contains(id);
        }
        for (ModEntry mod : mods) {
            if (!mod.enabled()) {
                continue;
            }
            Set<Descriptor> found = descriptors(mod.path());
            if (!loads(loader, minecraftVersion, found, toFabric, toForge)) {
                wrong.add(mod);
            } else if (loader == LoaderType.FORGE
                    && (found.isEmpty() || found.contains(Descriptor.FORGE_LEGACY))
                    && LegacyDependencies.isLegacyForge(mod.path())
                    && LegacyDependencies.requiresOtherLoader(mod.path())) {
                // A Forge 1.12 descriptor, but a build for Cleanroom: it says
                // so in its @Mod annotation, and Forge stops on it before any
                // mod is constructed.
                wrong.add(mod);
            }
        }
        return wrong;
    }
}
