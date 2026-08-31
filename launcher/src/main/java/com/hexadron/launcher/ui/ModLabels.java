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
     * <p>Switched off comes first and replaces the origin. Where a mod came from
     * is a detail; whether the game is loading it is the thing the user is
     * looking at the list to find out.
     */
    public static String badge(ModEntry mod) {
        if (!mod.enabled()) {
            return I18n.t("mods.origin.disabled");
        }
        return switch (mod.origin()) {
            case PACK -> I18n.t("mods.origin.pack");
            case DEPENDENCY -> I18n.t("mods.origin.dependency");
            case MANUAL -> I18n.t("mods.origin.manual");
            case EXTERNAL -> I18n.t("mods.origin.external");
        };
    }
}
