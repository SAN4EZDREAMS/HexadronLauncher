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

package com.hexadron.launcher.ui;

import com.hexadron.launcher.mods.ModEntry;
import com.hexadron.launcher.i18n.I18n;

/**
 * The words a mod row uses.
 *
 * <p>Its own class because two windows draw the same row - the mod browser and
 * the instance summary - and a badge that said "installed by you" in one of them
 * and "user's own mod" in the other would be describing two different things
 * with the same list.
 */
public final class ModLabels {

    private ModLabels() {
    }

    /**
     * What a row says about where its mod came from.
     *
     * <p>Switched off comes first and replaces the origin, and a mod for another
     * Minecraft version comes next. Where a mod came from is a detail; whether
     * the game is going to load it is the thing the user is looking at the list
     * to find out.
     */
    public static String badge(ModEntry mod) {
        if (!mod.enabled()) {
            return I18n.t("mods.origin.disabled");
        }
        // Ahead of where it came from: a mod the game is going to refuse is not
        // usefully described by who installed it.
        if (mod.isWrongVersion()) {
            return I18n.t("mods.origin.wrongVersion");
        }
        return switch (mod.origin()) {
            case PACK -> I18n.t("mods.origin.pack");
            case DEPENDENCY -> I18n.t("mods.origin.dependency");
            case MANUAL -> I18n.t("mods.origin.manual");
            case EXTERNAL -> I18n.t("mods.origin.external");
        };
    }
}
