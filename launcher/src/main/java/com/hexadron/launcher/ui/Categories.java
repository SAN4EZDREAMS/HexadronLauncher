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

package com.hexadron.launcher.ui;

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.mods.CategoryArt;
import com.hexadron.launcher.mods.ModCategory;
import com.hexadron.launcher.mods.SvgPaths;

import javafx.scene.Node;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Category names and their little pictures, ready to put in a row.
 *
 * <p>Two things live here because they are two halves of one answer. The name
 * comes from the launcher's own translations, which is what lets a Ukrainian
 * player read the Ukrainian word for "Magic" rather than {@code magic}; the picture comes from
 * Modrinth, which is what makes it the one they already recognise from the
 * website. Either half can be missing - a category with no picture yet is a
 * category with a name - and neither needs a connection once it has been seen
 * once.
 */
final class Categories {

    /** Read once per drawing rather than once per row that shows it. */
    private final Map<ModCategory, SvgPaths.Drawing> drawings = new EnumMap<>(ModCategory.class);

    Categories(CategoryArt art) {
        for (ModCategory category : ModCategory.values()) {
            art.of(category).ifPresent(markup -> {
                SvgPaths.Drawing drawing = SvgPaths.of(markup);
                if (!drawing.isEmpty()) {
                    drawings.put(category, drawing);
                }
            });
        }
    }

    /** What a player calls this category. */
    static String name(ModCategory category) {
        return I18n.t(category.key());
    }

    /**
     * One kind's categories, in the order they should be offered.
     *
     * <p>By the name the player reads, not by the identifier underneath it: a
     * list sorted by {@code game-mechanics} and {@code worldgen} is not sorted
     * at all to somebody who reads them in Ukrainian as "Game mechanics" and
     * "World generation". The
     * ordering itself lives in {@link ModCategory}, where it can be checked
     * without a display.
     *
     * <p>Per kind, because the platform files each kind under its own list and a
     * modpack's is not a mod's - see {@link ModCategory#forKind}.
     */
    static List<ModCategory> inReadingOrder(com.hexadron.launcher.mods.ContentKind kind) {
        return ModCategory.inReadingOrder(kind, I18n.current().locale(), Categories::name);
    }

    /**
     * A fresh drawing for a category.
     *
     * @return null when none has been fetched, which the caller shows as a name
     *         on its own
     */
    Node icon(ModCategory category, double size) {
        return SvgIcon.draw(drawings.get(category), size);
    }

    /** True when no drawing has arrived for anything yet. */
    boolean isEmpty() {
        return drawings.isEmpty();
    }
}
