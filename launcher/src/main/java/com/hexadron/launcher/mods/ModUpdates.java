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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Newer builds of the mods in a folder, and a way back from installing them.
 *
 * <p>Every switched-on jar is asked about, not only those the launcher
 * installed: a jar is identified by its content - its SHA-1 on Modrinth, its
 * fingerprint on CurseForge - so a mod the player dragged in is updated the
 * same way. Mods that belong to an installed modpack are left out; the pack
 * decides their versions, and one updated on its own is how a tested pack
 * stops being that pack.
 *
 * <p>An update sets the old jar aside in {@code mods/.removed/} and writes
 * what it did to {@link #JOURNAL} in the game folder. {@code rollBack} puts
 * the last update back exactly: the old jars in place, the new ones set aside,
 * the launcher's record of each mod as it was.
 */
public final class ModUpdates {

    /** The record of the last update, in the profile's game folder. */
    public static final String JOURNAL = ".hexadron-mod-updates.json";

    /**
     * A newer build of one mod.
     *
     * @param current      the jar in the folder now
     * @param title        the name to show
     * @param next         the build to install
     * @param dependencies projects the new build requires that the folder does not have
     */
    public record Update(ModEntry current, String title, ModFile next, List<String> dependencies) {
        public Update {
            dependencies = List.copyOf(dependencies);
        }

        public ModProvider.Source source() {
            return next.source();
        }
    }

    /**
     * What a check found.
     *
     * @param updates the mods with a newer build
     * @param checked the jars that were asked about
     * @param unknown the jars neither platform knows: built by hand, or from elsewhere
     * @param notes   what could not be asked, for the log: a platform that did not answer
     */
    public record Check(List<Update> updates, int checked, int unknown, List<String> notes) {
        public Check {
            updates = List.copyOf(updates);
            notes = List.copyOf(notes);
        }
    }

    /** One replaced jar in the journal. */
    public record Change(String oldFile, String asideFile, String newFile, Json oldRecord) {
    }

    private ModUpdates() {
    }

    // ------------------------------------------------------------ CurseForge fingerprint

    /**
     * CurseForge's fingerprint of a file: MurmurHash2, seed 1, over the bytes
     * with every tab, line feed, carriage return and space left out.
     */
    public static long curseForgeFingerprint(byte[] data) {
        byte[] kept = new byte[data.length];
        int length = 0;
        for (byte b : data) {
            if (b != 9 && b != 10 && b != 13 && b != 32) {
                kept[length++] = b;
            }
        }
        final int m = 0x5bd1e995;
        final int r = 24;
        int h = 1 ^ length;
        int i = 0;
        while (length - i >= 4) {
            int k = (kept[i] & 0xff) | (kept[i + 1] & 0xff) << 8 | (kept[i + 2] & 0xff) << 16
                    | (kept[i + 3] & 0xff) << 24;
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
            i += 4;
        }
        switch (length - i) {
            case 3:
                h ^= (kept[i + 2] & 0xff) << 16;
                // fall through
            case 2:
                h ^= (kept[i + 1] & 0xff) << 8;
                // fall through
            case 1:
                h ^= kept[i] & 0xff;
                h *= m;
                break;
            default:
                break;
        }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h & 0xffffffffL;
    }

    // ------------------------------------------------------------ files

    /**
     * Moves a jar into {@code mods/.removed/}, under a free name.
     *
     * @return the name it has there
     */
    public static String setAside(Path modsDir, String fileName) throws IOException {
        Path source = modsDir.resolve(fileName);
        Path graveyard = modsDir.resolve(ModScan.DISCARD_DIR);
        Files.createDirectories(graveyard);
        Path target = graveyard.resolve(fileName);
        for (int n = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS) && n < 1000; n++) {
            target = graveyard.resolve(fileName + "." + n);
        }
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        return target.getFileName().toString();
    }

    /** The record of the last update; empty when there is none or it does not read. */
    public static List<Change> journal(Path gameDir) {
        List<Change> changes = new ArrayList<>();
        Path file = gameDir.resolve(JOURNAL);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return changes;
        }
        try {
            for (Json change : Json.read(file).get("changes").elements()) {
                String oldFile = safe(change.get("old").asString(""));
                String aside = safe(change.get("aside").asString(""));
                String newFile = safe(change.get("new").asString(""));
                if (oldFile != null && aside != null && newFile != null) {
                    changes.add(new Change(oldFile, aside, newFile,
                            change.get("record").isObject() ? change.get("record") : null));
                }
            }
        } catch (IOException | RuntimeException e) {
            changes.clear();
        }
        return changes;
    }

    public static void writeJournal(Path gameDir, List<Change> changes) throws IOException {
        Json list = Json.array();
        for (Change change : changes) {
            Json entry = Json.object().put("old", change.oldFile()).put("aside", change.asideFile())
                    .put("new", change.newFile());
            if (change.oldRecord() != null) {
                entry.put("record", change.oldRecord());
            }
            list.add(entry);
        }
        Json root = Json.object().put("at", System.currentTimeMillis());
        root.put("changes", list);
        Path temp = gameDir.resolve(JOURNAL + ".part");
        root.write(temp);
        Files.move(temp, gameDir.resolve(JOURNAL), StandardCopyOption.REPLACE_EXISTING);
    }

    public static void forgetJournal(Path gameDir) throws IOException {
        Files.deleteIfExists(gameDir.resolve(JOURNAL));
    }

    /**
     * Undoes the last update: each new jar is set aside and the old one comes
     * back under its old name.
     *
     * @return the records to put back into the launcher's list of mods, by the
     *         file each belongs to; a jar the launcher never recorded has none
     */
    public static List<Change> rollBack(Path gameDir, Path modsDir) throws IOException {
        List<Change> changes = journal(gameDir);
        List<Change> done = new ArrayList<>();
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change change = changes.get(i);
            Path aside = modsDir.resolve(ModScan.DISCARD_DIR).resolve(change.asideFile());
            if (!Files.isRegularFile(aside, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isRegularFile(modsDir.resolve(change.newFile()), LinkOption.NOFOLLOW_LINKS)
                    && !change.newFile().equals(change.oldFile())) {
                setAside(modsDir, change.newFile());
            } else if (change.newFile().equals(change.oldFile())) {
                // The new build has the old one's name; it is replaced in place.
                Files.deleteIfExists(modsDir.resolve(change.newFile()));
            }
            Path target = modsDir.resolve(change.oldFile());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Files.move(aside, target, StandardCopyOption.ATOMIC_MOVE);
            done.add(change);
        }
        forgetJournal(gameDir);
        return done;
    }

    /** The record the launcher keeps for a file, if it keeps one. */
    public static Optional<InstalledMod> recordOf(ModLibrary library, String fileName) {
        return library.all().stream().filter(mod -> mod.file().fileName().equals(fileName)).findFirst();
    }

    /** A plain file name, nothing that walks out of the folder. */
    private static String safe(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.equals("..")) {
            return null;
        }
        return name;
    }
}
