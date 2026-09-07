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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The worlds in an instance.
 *
 * <h2>Why the data pack section needs this at all</h2>
 *
 * <p>Every other kind of thing a player installs belongs to the instance: one
 * mods folder, one resourcepacks folder, one shaderpacks folder. A data pack does
 * not. Minecraft loads data packs from {@code saves/&lt;world&gt;/datapacks},
 * per world, and there is no instance-wide folder to put one in - not because
 * nobody thought of it, but because a data pack changes recipes, loot and world
 * generation, which are properties of a world and not of a game.
 *
 * <p>Launchers that pretend otherwise keep a folder of their own and copy from it
 * into worlds, and then the two disagree: a pack removed from a world is still
 * "installed", a pack added to the folder after a world was made is not in it,
 * and the list is a list of intentions rather than of facts. So this window asks
 * which world first and lists that world's folder. What it shows is what the game
 * will load.
 *
 * <h2>The name</h2>
 *
 * <p>The folder name, not the name in {@code level.dat}. Minecraft names the
 * folder after the world when it creates it, so they are the same for almost
 * every world; and {@code level.dat} is gzipped NBT, which would mean a binary
 * format reader in the launcher to improve a label in the one case where a player
 * has renamed a world since. The folder name is also what they will look for on
 * disk.
 */
public final class WorldSaves {

    /** The folder Minecraft keeps worlds in, inside an instance. */
    public static final String SAVES_DIR = "saves";

    /** The folder Minecraft loads a world's data packs from. */
    public static final String DATAPACKS_DIR = "datapacks";

    private WorldSaves() {
    }

    /**
     * One world.
     *
     * @param folder     the folder name, which is what it is called
     * @param path       the world folder itself
     * @param lastPlayed modification time of {@code level.dat}, or 0 - used to
     *                   put the world the player is actually in at the top
     */
    public record World(String folder, Path path, long lastPlayed) {

        /** Where this world's data packs go. Not created by reading. */
        public Path datapacks() {
            return path.resolve(DATAPACKS_DIR);
        }

        /** How many data pack files are in it now. */
        public int datapackCount() {
            Path directory = datapacks();
            if (!Files.isDirectory(directory)) {
                return 0;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                int count = 0;
                for (Path entry : stream) {
                    if (DatapackScan.isDatapackFile(entry)) {
                        count++;
                    }
                }
                return count;
            } catch (IOException | RuntimeException e) {
                return 0;
            }
        }

        @Override
        public String toString() {
            return folder;
        }
    }

    /**
     * Every world in an instance, most recently played first.
     *
     * <p>Never throws: this draws a picker, and an instance that has never been
     * launched has no {@code saves} folder at all, which is an empty list and a
     * sentence saying so rather than an error.
     *
     * <p>A folder counts as a world when it has a {@code level.dat}. That is the
     * file Minecraft itself looks for, so anything else in {@code saves} - a
     * backup zip, a half-extracted download, {@code .DS_Store} - is left out
     * exactly as the game leaves it out.
     */
    public static List<World> of(Path gameDirectory) {
        Path saves = gameDirectory.resolve(SAVES_DIR);
        if (!Files.isDirectory(saves)) {
            return List.of();
        }
        List<World> worlds = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(saves)) {
            for (Path folder : stream) {
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                Path level = folder.resolve("level.dat");
                if (!Files.isRegularFile(level)) {
                    continue;
                }
                long played;
                try {
                    played = Files.getLastModifiedTime(level).toMillis();
                } catch (IOException e) {
                    played = 0;
                }
                worlds.add(new World(folder.getFileName().toString(), folder, played));
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        worlds.sort(Comparator.comparingLong(World::lastPlayed).reversed()
                .thenComparing(World::folder, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(worlds);
    }
}
