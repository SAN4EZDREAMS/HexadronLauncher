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
 * player read "Чаклунство" rather than {@code magic}; the picture comes from
 * Modrinth, which is what makes it the one they already recognise from the
 * website. Either half can be missing - a category with no picture yet is a
 * category with a name - and neither needs a connection once it has been seen
 * once.
 */
final class Categories {

    /** Path data, read once per drawing rather than once per row that shows it. */
    private final Map<ModCategory, List<String>> drawings = new EnumMap<>(ModCategory.class);

    Categories(CategoryArt art) {
        for (ModCategory category : ModCategory.values()) {
            art.of(category).ifPresent(markup -> {
                List<String> paths = SvgPaths.read(markup);
                if (!paths.isEmpty()) {
                    drawings.put(category, paths);
                }
            });
        }
    }

    /** What a player calls this category. */
    static String name(ModCategory category) {
        return I18n.t(category.key());
    }

    /**
     * The categories in the order they should be offered.
     *
     * <p>By the name the player reads, not by the identifier underneath it: a
     * list sorted by {@code game-mechanics} and {@code worldgen} is not sorted
     * at all to somebody reading "Ігрові механіки" and "Генерація світу". The
     * ordering itself lives in {@link ModCategory}, where it can be checked
     * without a display.
     */
    static List<ModCategory> inReadingOrder() {
        return ModCategory.inReadingOrder(I18n.current().locale(), Categories::name);
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
