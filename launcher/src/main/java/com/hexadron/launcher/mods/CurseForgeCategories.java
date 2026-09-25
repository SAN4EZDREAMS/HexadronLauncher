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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What CurseForge files a project under, said in this launcher's own words.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>The two platforms describe the same mods with two different vocabularies.
 * Modrinth has {@code technology}; CurseForge has seven separate technology
 * categories. Modrinth has {@code worldgen}; CurseForge splits it into biomes,
 * dimensions, structures and ores. Modrinth calls a helper library
 * {@code library}, CurseForge calls it {@code library-api}.
 *
 * <p>Until this file existed, a CurseForge project's categories were run through
 * {@link ModCategory#byId} - which recognises Modrinth's identifiers and nothing
 * else - so a CurseForge row showed whichever handful of its categories happened
 * to be spelled the same, and nothing at all for the rest. A mod filed under
 * "Technology: Processing" and "World Gen: Ores and Resources" came back with no
 * marks on its row, while an identical Modrinth listing showed two.
 *
 * <p>Worse than the blank, that accident was not kind-aware. CurseForge files
 * data packs under {@code fantasy}, and {@code fantasy} is a Modrinth identifier
 * - for a shader. A data pack was being marked with a category the platform does
 * not offer for data packs, which is a mark that cannot be filtered on and does
 * not mean what it says.
 *
 * <h2>By meaning, per kind</h2>
 *
 * <p>So the pairing is written down: one table per {@link ContentKind}, because
 * both platforms keep a separate list per kind and the same word is not the same
 * category across them - {@code magic} is a mod category, a modpack category and
 * a data pack category, and {@code vanilla} means a resource pack's style in one
 * list and a shader's in another.
 *
 * <p>Several CurseForge categories can land on one of ours, which is the usual
 * case rather than the exception: the seven technology categories are all
 * {@link ModCategory#TECHNOLOGY}. One can land on two, where CurseForge's is the
 * broader word - "Energy/Fluid/Item Transport" is technology and transport at
 * once. And a good many are left unpaired on purpose, listed below with the
 * reason, because a mark that means something the author did not say is worse
 * than no mark.
 *
 * <h2>Both directions</h2>
 *
 * <p>Read forwards ({@link #of}) it turns a project's CurseForge categories into
 * the marks on its row. Read backwards ({@link #slugsFor}) it turns the ticked
 * boxes in the filter into something CurseForge can be asked for, which is what
 * lets the category filter narrow a CurseForge search at all rather than
 * refusing the search outright.
 */
public final class CurseForgeCategories {

    /**
     * CurseForge's own slugs, by kind, and what each of them is here.
     *
     * <p>Slugs rather than the numeric ids the API filters by, because a slug is
     * the platform's stable name for a category and a number is not readable by
     * anybody checking this table. The numbers are looked up at runtime from the
     * platform's own category listing - see
     * {@link CurseForgeProvider#categoryIdsFor} - so this file never has to hold
     * one, and never goes wrong if CurseForge renumbers.
     */
    private static final Map<ContentKind, Map<String, List<ModCategory>>> BY_KIND =
            new EnumMap<>(ContentKind.class);

    static {
        // ------------------------------------------------------------- mods
        //
        // Left unpaired, and why: education, mc-creator, mc-miscellaneous and
        // modjam-2025 describe who made a mod or a jam it was made for, not what
        // it is for; horror and skyblock have no mod-side equivalent on
        // Modrinth; and the whole "Addons" group - create, kubejs,
        // applied-energistics-2, blood-magic, twilight-forest and the rest -
        // names the mod a mod extends, which is a different question from what
        // it does and one Modrinth answers with a dependency rather than a
        // category.
        Map<String, List<ModCategory>> mods = new LinkedHashMap<>();
        put(mods, "adventure-rpg", ModCategory.ADVENTURE);
        put(mods, "library-api", ModCategory.LIBRARY);
        put(mods, "armor-weapons-tools", ModCategory.EQUIPMENT);
        put(mods, "bug-fixes", ModCategory.UTILITY);
        put(mods, "cosmetic", ModCategory.DECORATION);
        put(mods, "creativemode", ModCategory.UTILITY);
        put(mods, "mc-food", ModCategory.FOOD);
        put(mods, "magic", ModCategory.MAGIC);
        put(mods, "map-information", ModCategory.UTILITY);
        put(mods, "performance", ModCategory.OPTIMIZATION);
        put(mods, "redstone", ModCategory.TECHNOLOGY);
        put(mods, "server-utility", ModCategory.MANAGEMENT);
        put(mods, "storage", ModCategory.STORAGE);
        put(mods, "technology-automation", ModCategory.TECHNOLOGY);
        put(mods, "technology-energy", ModCategory.TECHNOLOGY);
        // The one CurseForge category that is honestly two of ours: moving items
        // and fluids about is technology, and moving them about is transport.
        put(mods, "technology-item-fluid-energy-transport",
                ModCategory.TECHNOLOGY, ModCategory.TRANSPORTATION);
        put(mods, "technology-farming", ModCategory.TECHNOLOGY);
        put(mods, "technology-genetics", ModCategory.TECHNOLOGY);
        put(mods, "technology-player-transport", ModCategory.TRANSPORTATION);
        put(mods, "technology-processing", ModCategory.TECHNOLOGY);
        put(mods, "twitch-integration", ModCategory.SOCIAL);
        put(mods, "utility-qol", ModCategory.UTILITY);
        put(mods, "world-biomes", ModCategory.WORLDGEN);
        put(mods, "world-dimensions", ModCategory.WORLDGEN);
        put(mods, "world-mobs", ModCategory.MOBS);
        put(mods, "world-ores-resources", ModCategory.WORLDGEN);
        put(mods, "world-structures", ModCategory.WORLDGEN);
        BY_KIND.put(ContentKind.MOD, Map.copyOf(mods));

        // --------------------------------------------------------- modpacks
        //
        // Left unpaired: ftb-official-pack and rlcraft name a publisher and a
        // pack rather than a kind of pack; horror has no equivalent; and
        // "Vanilla+" is a promise about how far a pack strays, which Modrinth
        // makes with vanilla-like - a category it offers for resource packs and
        // shaders, and not for packs.
        Map<String, List<ModCategory>> modpacks = new LinkedHashMap<>();
        put(modpacks, "adventure-and-rpg", ModCategory.ADVENTURE);
        put(modpacks, "exploration", ModCategory.ADVENTURE);
        put(modpacks, "map-based", ModCategory.ADVENTURE);
        put(modpacks, "combat-pvp", ModCategory.COMBAT);
        put(modpacks, "expert", ModCategory.CHALLENGING);
        put(modpacks, "hardcore", ModCategory.CHALLENGING);
        put(modpacks, "skyblock", ModCategory.CHALLENGING);
        put(modpacks, "extra-large", ModCategory.KITCHEN_SINK);
        put(modpacks, "small-light", ModCategory.LIGHTWEIGHT);
        put(modpacks, "magic", ModCategory.MAGIC);
        put(modpacks, "mini-game", ModCategory.MULTIPLAYER);
        put(modpacks, "multiplayer", ModCategory.MULTIPLAYER);
        put(modpacks, "quests", ModCategory.QUESTS);
        put(modpacks, "tech", ModCategory.TECHNOLOGY);
        put(modpacks, "sci-fi", ModCategory.TECHNOLOGY);
        BY_KIND.put(ContentKind.MODPACK, Map.copyOf(modpacks));

        // -------------------------------------------------------- datapacks
        //
        // Left unpaired: miscellaneous and modjam-2025 say nothing about what a
        // pack does; mod-support says which mods it needs; and CurseForge's
        // data pack "fantasy" is not Modrinth's - theirs is a shader's look, and
        // marking a data pack with it was the clearest thing wrong with reading
        // these by identifier alone.
        Map<String, List<ModCategory>> datapacks = new LinkedHashMap<>();
        put(datapacks, "adventure", ModCategory.ADVENTURE);
        put(datapacks, "library", ModCategory.LIBRARY);
        put(datapacks, "magic", ModCategory.MAGIC);
        put(datapacks, "tech", ModCategory.TECHNOLOGY);
        put(datapacks, "utility", ModCategory.UTILITY);
        BY_KIND.put(ContentKind.DATAPACK, Map.copyOf(datapacks));

        // ---------------------------------------------------- resource packs
        //
        // The resolutions pair exactly, once the spelled-out names are read:
        // CurseForge writes "sixteen-x" where Modrinth writes "16x". Left
        // unpaired: animated, data-packs, miscellaneous and modjam-2025.
        Map<String, List<ModCategory>> resourcePacks = new LinkedHashMap<>();
        put(resourcePacks, "sixteen-x", ModCategory.RESOLUTION_16X);
        put(resourcePacks, "thirty-two-x", ModCategory.RESOLUTION_32X);
        put(resourcePacks, "sixty-four-x", ModCategory.RESOLUTION_64X);
        put(resourcePacks, "one-twenty-eight-x", ModCategory.RESOLUTION_128X);
        put(resourcePacks, "two-fifty-six-x", ModCategory.RESOLUTION_256X);
        put(resourcePacks, "five-twelve-x-and-beyond", ModCategory.RESOLUTION_512X_PLUS);
        put(resourcePacks, "font-packs", ModCategory.FONTS);
        put(resourcePacks, "mod-support", ModCategory.MODDED);
        put(resourcePacks, "photo-realistic", ModCategory.REALISTIC);
        put(resourcePacks, "traditional", ModCategory.VANILLA_LIKE);
        // Three ways of saying "it has a setting": Modrinth's word for all of
        // them is themed.
        put(resourcePacks, "medieval", ModCategory.THEMED);
        put(resourcePacks, "modern", ModCategory.THEMED);
        put(resourcePacks, "steampunk", ModCategory.THEMED);
        BY_KIND.put(ContentKind.RESOURCEPACK, Map.copyOf(resourcePacks));

        // --------------------------------------------------------- shaders
        //
        // CurseForge offers three, and all three pair. Everything else the
        // filter offers for shaders - the effects, and how much of a machine a
        // pack asks for - is Modrinth's alone, which is why a search narrowed to
        // those cannot be put to CurseForge at all. See
        // CurseForgeProvider.UnsupportedCategoriesException.
        Map<String, List<ModCategory>> shaders = new LinkedHashMap<>();
        put(shaders, "fantasy", ModCategory.FANTASY);
        put(shaders, "realistic", ModCategory.REALISTIC);
        put(shaders, "vanilla", ModCategory.VANILLA_LIKE);
        BY_KIND.put(ContentKind.SHADER, Map.copyOf(shaders));
    }

    private static void put(Map<String, List<ModCategory>> table,
                            String slug, ModCategory... categories) {
        table.put(slug, List.of(categories));
    }

    private CurseForgeCategories() {
    }

    /**
     * What this launcher calls the categories a CurseForge project is filed
     * under.
     *
     * <p>In the order the platform listed them, without repeats - two CurseForge
     * technology categories on one mod are one mark, not two - and without
     * anything unpaired.
     *
     * @param kind  which of the platform's lists these slugs are from
     * @param slugs the project's own {@code categories[].slug} values
     */
    public static List<ModCategory> of(ContentKind kind, Collection<String> slugs) {
        Map<String, List<ModCategory>> table = tableFor(kind);
        if (table.isEmpty() || slugs == null || slugs.isEmpty()) {
            return List.of();
        }
        Set<ModCategory> found = new LinkedHashSet<>();
        for (String slug : slugs) {
            if (slug == null) {
                continue;
            }
            List<ModCategory> paired = table.get(slug.trim().toLowerCase(Locale.ROOT));
            if (paired != null) {
                found.addAll(paired);
            }
        }
        return List.copyOf(found);
    }

    /**
     * The CurseForge categories that mean this one, for a search.
     *
     * <p>More than one where CurseForge splits what Modrinth joins: asking it for
     * {@link ModCategory#WORLDGEN} means asking it for biomes, dimensions,
     * structures and ores. Empty when the platform has no way of saying it.
     */
    public static List<String> slugsFor(ContentKind kind, ModCategory category) {
        List<String> slugs = new ArrayList<>();
        tableFor(kind).forEach((slug, paired) -> {
            if (paired.contains(category)) {
                slugs.add(slug);
            }
        });
        return List.copyOf(slugs);
    }

    /** True when a search narrowed to this category can be put to CurseForge. */
    public static boolean canExpress(ContentKind kind, ModCategory category) {
        return !slugsFor(kind, category).isEmpty();
    }

    /**
     * The chosen categories this platform has no words for.
     *
     * <p>Named rather than counted, because "the categories you chose do not
     * exist here" is a different message from "Low impact does not
     * exist here", and only the second one tells somebody which box to untick.
     */
    public static List<ModCategory> unexpressible(ContentKind kind,
                                                  Collection<ModCategory> chosen) {
        List<ModCategory> missing = new ArrayList<>();
        for (ModCategory category : chosen) {
            if (!canExpress(kind, category)) {
                missing.add(category);
            }
        }
        return List.copyOf(missing);
    }

    private static Map<String, List<ModCategory>> tableFor(ContentKind kind) {
        return kind == null ? Map.of() : BY_KIND.getOrDefault(kind, Map.of());
    }

    /** Every slug this launcher pairs for a kind. Used by the tests and the cache. */
    public static Set<String> slugsFor(ContentKind kind) {
        return tableFor(kind).keySet();
    }
}
