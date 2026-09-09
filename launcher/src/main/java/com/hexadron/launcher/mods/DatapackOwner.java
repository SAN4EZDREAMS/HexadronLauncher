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

/**
 * The data pack a mod in the mods folder was installed for.
 *
 * <h2>Why a mod points at a data pack at all</h2>
 *
 * <p>Half of Modrinth's data packs are published twice: the plain pack, which
 * vanilla Minecraft reads out of a world folder, and the same pack for a mod
 * loader, whose version names a mod as a required dependency. Installing the
 * second flavour therefore puts a jar in {@code mods/} that the player never
 * chose and cannot place - it is not something they installed, it is not a
 * dependency of another mod, and without this record the row for it would say
 * "installed by you" about a file they have never heard of.
 *
 * <h2>Why all three fields</h2>
 *
 * <p>Because a data pack does not live where mods live. It is in one world's
 * folder, and the mod is in the instance's, so the link has to name the world
 * as well as the pack - and the row that shows it is drawn from the mods folder
 * alone, on every repaint, with no connection. The title is stored rather than
 * looked up for that reason: reading a lock file out of every world of the
 * instance to label one badge is a folder walk per frame.
 *
 * @param world the world folder the pack is in, as
 *              {@link WorldSaves.World#folder()} names it
 * @param key   the pack's key in that world's record, as
 *              {@link InstalledMod#key()} builds one
 * @param title what to call the pack in a row the player reads
 */
public record DatapackOwner(String world, String key, String title) {

    /** What to call it, falling back to the key when no name was recorded. */
    public String label() {
        return title == null || title.isBlank() ? key : title;
    }

    public Json toJson() {
        return Json.object()
                .put("world", world == null ? "" : world)
                .put("key", key == null ? "" : key)
                .put("title", title == null ? "" : title);
    }

    /**
     * Reads one, or null when there is none to read.
     *
     * <p>A record with no key names no pack and is therefore not one: an entry
     * written by a newer build, or edited by hand, must not leave a row with a
     * badge that opens an empty panel.
     */
    public static DatapackOwner fromJson(Json json) {
        if (json == null) {
            return null;
        }
        String key = json.get("key").asString(null);
        if (key == null || key.isBlank()) {
            return null;
        }
        return new DatapackOwner(json.get("world").asString(null), key,
                json.get("title").asString(null));
    }
}
