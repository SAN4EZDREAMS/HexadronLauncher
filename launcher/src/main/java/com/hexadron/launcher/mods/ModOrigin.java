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

/**
 * How a mod came to be in a profile's {@code mods} folder.
 *
 * <p>This is what decides whether the user may remove a file one at a time. A
 * pack is a set that was chosen and tested together: letting a single mod be
 * pulled out of it leaves something that is no longer the pack but still claims
 * to be, and the first symptom is a crash the user cannot connect to the
 * deletion. A pack goes in and comes out whole.
 */
public enum ModOrigin {

    /** Installed as part of a named pack. Removed only by removing that pack. */
    PACK,

    /** Chosen by the user in the mod browser. Removable on its own. */
    MANUAL,

    /** Pulled in because something else required it. Removable, with a warning. */
    DEPENDENCY,

    /**
     * Installed because a data pack needs it. Removed with that data pack.
     *
     * <p>Not {@link #DEPENDENCY}, although it arrives the same way. A dependency
     * is required by another mod, which is in the same folder and in the same
     * list, so "what needs this" is answerable from the list itself and the
     * player can weigh removing it. This one is required by a zip in one world's
     * folder that the mods list knows nothing about, and taking it away leaves a
     * data pack the player installed silently not loading.
     *
     * <p>So it follows the {@link #PACK} rule rather than the dependency one: it
     * goes out when the thing that brought it goes out. The row says which pack,
     * and pressing that takes the player to it - which is where the button that
     * removes both of them is.
     */
    DATAPACK,

    /**
     * Put there by the user, outside the launcher.
     *
     * <p>Never written to the lock file - the lock file is the record of what
     * the launcher downloaded, and the whole point of this value is that nothing
     * downloaded it. It exists so that a jar found in the folder can be shown in
     * the same list as the rest instead of being invisible, which is what a
     * player who copies a mod in and then cannot see it in the launcher
     * reasonably reads as the launcher not having noticed.
     *
     * <p>Removable, and the only origin whose removal cannot be undone by
     * installing it again: the launcher has no record of where it came from.
     */
    EXTERNAL;

    /** True when this entry may be removed by itself. */
    public boolean isRemovableAlone() {
        return this != PACK && this != DATAPACK;
    }

    public static ModOrigin parse(String value) {
        if (value == null) {
            return MANUAL;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return MANUAL;
        }
    }
}
