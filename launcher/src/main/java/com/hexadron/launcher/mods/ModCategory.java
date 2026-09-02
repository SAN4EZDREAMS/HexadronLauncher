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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a mod is for: the categories Modrinth files its projects under.
 *
 * <h2>Why a fixed list rather than whatever the platform returns</h2>
 *
 * <p>Three reasons, and each of them is a thing that goes wrong if the list is
 * simply whatever came back from the last request.
 *
 * <p>The first is that the platform's list is not only categories. Modrinth
 * stores the mod loaders in the same field a category lives in - {@code fabric}
 * and {@code forge} sit beside {@code magic} and {@code storage} - so a filter
 * built from the raw list offers to narrow a Fabric instance's search to Forge
 * mods. Naming the categories here is what keeps that out.
 *
 * <p>The second is that these are shown to the player in their own language, and
 * a name that arrives from a request cannot be translated. Each value here has a
 * translation key, and the list the player reads is sorted in the alphabet they
 * read it in.
 *
 * <p>The third is that a launcher opened without a connection still has to draw
 * its own filter. The names are here; only the little pictures come from the
 * network, and a category with no picture yet is a category with a name.
 *
 * <p>A category Modrinth adds later is one line in this file. That is the whole
 * cost of the choice.
 */
public enum ModCategory {

    ADVENTURE("adventure"),
    CURSED("cursed"),
    DECORATION("decoration"),
    ECONOMY("economy"),
    EQUIPMENT("equipment"),
    FOOD("food"),
    GAME_MECHANICS("game-mechanics"),
    LIBRARY("library"),
    MAGIC("magic"),
    MANAGEMENT("management"),
    MINIGAME("minigame"),
    MOBS("mobs"),
    OPTIMIZATION("optimization"),
    SOCIAL("social"),
    STORAGE("storage"),
    TECHNOLOGY("technology"),
    TRANSPORTATION("transportation"),
    UTILITY("utility"),
    WORLDGEN("worldgen");

    private final String id;

    ModCategory(String id) {
        this.id = id;
    }

    /** What the platform calls it, and what goes into a search. */
    public String id() {
        return id;
    }

    /** The translation key for the name a player reads. */
    public String key() {
        return "mods.category." + id;
    }

    /**
     * The category with this identifier, if it is one.
     *
     * <p>Empty for anything else, which is what filters the loaders out of a
     * project's category list and, incidentally, lets a CurseForge project keep
     * whichever of its own categories happen to be named the same.
     */
    public static Optional<ModCategory> byId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        for (ModCategory category : values()) {
            if (category.id.equals(wanted)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }

    /**
     * The categories in a list of platform identifiers, in order, without
     * repeats and without anything that is not a category.
     */
    public static List<ModCategory> parse(Collection<String> ids) {
        Set<ModCategory> found = new LinkedHashSet<>();
        for (String id : ids) {
            byId(id).ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    /**
     * The categories in the order somebody reading that language expects.
     *
     * <p>Sorted with the language's own collator, and that is not a detail. The
     * obvious tool, {@code String.CASE_INSENSITIVE_ORDER}, compares code points
     * after lowering the case - and Ukrainian і and ї sit at U+0456 and U+0457,
     * above the whole of а-я, so a list sorted with it puts them after the last
     * word rather than between "Економіка" and "Керування" where the alphabet
     * puts them. Ukrainian is not a special case: Polish ą, ć and ł and German
     * ä, ö and ü are all outside the block their alphabet places them in, and
     * every one of them would be wrong the same way. A collator is the thing
     * that knows where a letter belongs in the alphabet somebody actually reads.
     *
     * @param locale the language the names are in
     * @param nameOf what each category is called in it
     */
    public static List<ModCategory> inReadingOrder(
            java.util.Locale locale, java.util.function.Function<ModCategory, String> nameOf) {

        java.text.Collator collator = java.text.Collator.getInstance(locale);
        // Case is not a distinction worth making in a list of names, and an
        // accent is: "Ó" belongs beside "O", not at the end of the alphabet.
        collator.setStrength(java.text.Collator.SECONDARY);
        List<ModCategory> ordered = new ArrayList<>(List.of(values()));
        ordered.sort(java.util.Comparator.comparing(nameOf, collator));
        return List.copyOf(ordered);
    }

    /**
     * A mod's categories, with the ones being filtered on at the front.
     *
     * <p>Somebody who has narrowed a search to two categories is scanning the
     * rows for those two. Leaving them wherever the platform happened to put
     * them - possibly behind a count that has to be hovered - answers the
     * question they asked with an extra step.
     *
     * <p>The mod's own order otherwise, on both sides of the split, so a row
     * does not reshuffle itself as boxes are ticked; it only moves the chosen
     * ones forward.
     */
    public static List<ModCategory> chosenFirst(List<ModCategory> shown,
                                                Set<ModCategory> chosen) {
        if (chosen.isEmpty() || shown.size() < 2) {
            return shown;
        }
        List<ModCategory> ordered = new ArrayList<>(shown.size());
        shown.stream().filter(chosen::contains).forEach(ordered::add);
        shown.stream().filter(category -> !chosen.contains(category)).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    /** The identifiers of these categories, for a request. */
    public static List<String> idsOf(Collection<ModCategory> categories) {
        List<String> ids = new ArrayList<>();
        categories.forEach(category -> ids.add(category.id()));
        return List.copyOf(ids);
    }
}
