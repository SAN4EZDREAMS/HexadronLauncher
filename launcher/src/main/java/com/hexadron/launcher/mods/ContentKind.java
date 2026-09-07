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

import java.util.List;
import java.util.Locale;

/**
 * A kind of thing a player installs into an instance.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>The browser used to be a mod browser, and every question it asked a
 * platform had "mod" written into it: one facet on Modrinth, one class id on
 * CurseForge, one folder on disk, one file extension. Those four constants are
 * the entire difference between searching for mods and searching for modpacks or
 * data packs - the paging, the sorting, the two source filters, the rows, the
 * failure messages and the installed list are the same work either way. So they
 * are collected here rather than copied per section, and adding a kind is this
 * one file plus a folder reader, not a second browser that drifts from the first.
 *
 * <h2>The three that are not the same</h2>
 *
 * <p><b>Filtering.</b> A mod is only worth listing if it has a build for this
 * instance's Minecraft version and loader, and that filter is the whole reason to
 * browse from inside a launcher. A modpack is the opposite: it <em>states</em> a
 * version and a loader, so narrowing the catalogue by the instance's own would
 * hide every pack the player might want to install next. A data pack is narrowed
 * by version always, and by loader by default - see {@link Narrowing} for why a
 * thing vanilla Minecraft loads has anything to do with a loader at all.
 *
 * <p><b>Where it goes.</b> Mods go in one folder per instance. Data packs go in
 * one folder per <em>world</em>, which is why that section asks which world
 * first. A modpack goes nowhere in particular: it is a version, a loader and a
 * list of files, so it is installed by being unpacked across a whole instance.
 *
 * <p><b>What the file is.</b> Used for the import dialog's filter and for
 * refusing a file that is obviously not this kind of thing.
 */
public enum ContentKind {

    /**
     * A mod: one jar in {@code mods/}, needing a loader.
     *
     * <p>Modrinth files these under {@code project_type:mod}; CurseForge's class
     * 6 is its "Mods" section.
     */
    MOD("mod", 6, "mods.kind.mod", "mod", true, true, Narrowing.NONE, List.of(".jar")),

    /**
     * A modpack: a version, a loader and a list of files, in one archive.
     *
     * <p>Not filtered by the instance's version or loader, because a pack names
     * its own and the point of the list is to find one to install.
     */
    MODPACK("modpack", 4471, "mods.kind.modpack", "modpack", false, false,
            Narrowing.ONLY_FOR_PROFILE, List.of(".mrpack", ".zip")),

    /**
     * A data pack: a zip in one world's {@code datapacks/} folder.
     *
     * <p>Filtered by Minecraft version always - a data pack declares which ones
     * it is written for and the game refuses the wrong pack format.
     *
     * <p>And by loader by default, which needs saying because vanilla Minecraft
     * is what loads a data pack. Half the catalogue is published twice over: the
     * plain pack, and the same pack as a mod loader loads it, which arrives with
     * a mod that puts it in place. Modrinth's own download asks which of the two
     * you want, and "just the data pack, no mods" is one of the answers. So the
     * catalogue is narrowed to the flavour this instance can actually load, and
     * {@link Narrowing#WITHOUT_MODS} is the box that asks for the other one.
     */
    DATAPACK("datapack", 6945, "mods.kind.datapack", "datapack", true, false,
            Narrowing.WITHOUT_MODS, List.of(".zip"));

    /**
     * The one narrowing question a kind puts to the user, if it has one.
     *
     * <h2>Why one question and not two flags</h2>
     *
     * <p>Every kind has exactly one thing worth asking about, and it is a
     * different thing per kind - so it is one answer travelling through the
     * search, read by whichever kind asked. A second boolean beside it would be
     * a parameter that is meaningless for two kinds out of three, and every
     * layer between the tick box and the request would have to carry both.
     *
     * <p>Each value also owns its own words and its own default, because those
     * are part of the question rather than of the panel that draws it.
     */
    public enum Narrowing {

        /** Nothing to ask. A mod is narrowed to the instance, always. */
        NONE(null, null, false),

        /**
         * "Show only modpacks that fit this profile", ticked to begin with.
         *
         * <p>A pack states its own version and loader, so the unnarrowed
         * catalogue is thousands of packs almost none of which can be installed
         * without replacing what the instance is.
         */
        ONLY_FOR_PROFILE("mods.onlyForProfile", "mods.onlyForProfile.tip", true),

        /**
         * "Show data packs without mods", unticked to begin with.
         *
         * <p>The inverse shape of the one above, and deliberately so: the
         * default is the narrower list, and the box widens it. Unticked, the
         * catalogue is the packs this instance's loader can load - which is the
         * flavour that may bring a mod with it. Ticked, the loader stops
         * mattering and only the Minecraft version does, so what is listed is
         * data packs and nothing else.
         */
        WITHOUT_MODS("datapacks.withoutMods", "datapacks.withoutMods.tip", false);

        private final String key;
        private final String tipKey;
        private final boolean chosenByDefault;

        Narrowing(String key, String tipKey, boolean chosenByDefault) {
            this.key = key;
            this.tipKey = tipKey;
            this.chosenByDefault = chosenByDefault;
        }

        /** True when there is a box to draw. */
        public boolean isOffered() {
            return this != NONE;
        }

        /** The translation key for the box's label. Null for {@link #NONE}. */
        public String key() {
            return key;
        }

        /** The translation key for the sentence that says what it does. */
        public String tipKey() {
            return tipKey;
        }

        /** How the box starts out. */
        public boolean isChosenByDefault() {
            return chosenByDefault;
        }

        /**
         * What an empty answer says.
         *
         * <p>It has to name the box whenever the box is what emptied the list,
         * or "nothing matches" reads as "no such thing exists for your version".
         */
        public String emptyKey(boolean chosen) {
            return switch (this) {
                case NONE -> "mods.noResults";
                case ONLY_FOR_PROFILE -> chosen ? "mods.noResults.forProfile" : "mods.noResults";
                case WITHOUT_MODS -> chosen ? "mods.noResults" : "datapacks.noResults.forProfile";
            };
        }
    }

    private final String modrinthProjectType;
    private final int curseForgeClassId;
    private final String key;
    private final String modrinthPagePath;
    private final boolean filteredByVersion;
    private final boolean filteredByLoader;
    private final Narrowing narrowing;
    private final List<String> extensions;

    ContentKind(String modrinthProjectType, int curseForgeClassId, String key,
                String modrinthPagePath, boolean filteredByVersion, boolean filteredByLoader,
                Narrowing narrowing, List<String> extensions) {
        this.modrinthProjectType = modrinthProjectType;
        this.curseForgeClassId = curseForgeClassId;
        this.key = key;
        this.modrinthPagePath = modrinthPagePath;
        this.filteredByVersion = filteredByVersion;
        this.filteredByLoader = filteredByLoader;
        this.narrowing = narrowing;
        this.extensions = List.copyOf(extensions);
    }

    /** Translation key for the name of this kind. */
    public String key() {
        return key;
    }

    /**
     * Modrinth's {@code project_type} facet value.
     *
     * <p>See {@link #modrinthTypeFacets()} for why this is not used on its own
     * for data packs.
     */
    public String modrinthProjectType() {
        return modrinthProjectType;
    }

    /**
     * The {@code project_type} values a search of this kind should accept.
     *
     * <p>An OR group, because Modrinth's own history is in this field. Data packs
     * were introduced as a loader on top of {@code mod} before they became a
     * project type of their own, so a project published before the change is
     * {@code project_type:mod} with the {@code datapack} loader and one published
     * after is {@code project_type:datapack}. Asking for one of the two returns
     * half the catalogue and no hint that the other half exists; asking for
     * either, and then narrowing by the loader, returns the same list the website
     * shows.
     */
    public List<String> modrinthTypeFacets() {
        return this == DATAPACK ? List.of("mod", "datapack") : List.of(modrinthProjectType);
    }

    /**
     * The Modrinth loader tag that identifies this kind, or null when the kind is
     * not a loader on that platform.
     *
     * <p>Only data packs have one. It is what separates a data pack from a mod in
     * the half of the catalogue where both are {@code project_type:mod}.
     */
    public String modrinthLoaderTag() {
        return this == DATAPACK ? "datapack" : null;
    }

    /**
     * CurseForge's numeric {@code classId} for this kind.
     *
     * <p>CurseForge's classes for Minecraft: 6 Mods, 4471 Modpacks, 6945 Data
     * Packs. They are the platform's own and are not derivable from anything, so
     * they are written down here - one place to correct if CurseForge ever
     * renumbers a section.
     */
    public int curseForgeClassId() {
        return curseForgeClassId;
    }

    /** The path Modrinth publishes a project of this kind under. */
    public String modrinthPagePath() {
        return modrinthPagePath;
    }

    /**
     * True when a search of this kind should be narrowed to the instance's
     * Minecraft version.
     */
    public boolean isFilteredByVersion() {
        return filteredByVersion;
    }

    /**
     * True when a search of this kind should be narrowed to the instance's
     * loader.
     */
    public boolean isFilteredByLoader() {
        return filteredByLoader;
    }

    /** The one narrowing question this kind offers, or {@link Narrowing#NONE}. */
    public Narrowing narrowing() {
        return narrowing;
    }

    /**
     * True when a search of this kind can be narrowed to the profile's own
     * Minecraft version and loader <em>on request</em>.
     *
     * <h2>Why the question is per kind</h2>
     *
     * <p>A mod is always narrowed: a build for another version is not worth
     * listing, so there is nothing to ask.
     *
     * <p>A modpack is the one kind where narrowing to the instance is a real
     * question with two honest answers. A pack <em>states</em> a version and a
     * loader, so the catalogue is worth browsing unnarrowed - that is how a
     * player finds the pack they will switch to next - and it is also worth
     * narrowing, because somebody who wants a pack for the profile they already
     * have does not want to read about six hundred packs that would replace its
     * version.
     *
     * <p>A data pack's question is not this one and is the other way round -
     * see {@link Narrowing#WITHOUT_MODS} - which is why this stays a question
     * about modpacks rather than becoming a flag three kinds share.
     */
    public boolean isNarrowableToProfile() {
        return narrowing == Narrowing.ONLY_FOR_PROFILE;
    }

    /**
     * Whether a search should be narrowed to the profile's Minecraft version.
     *
     * @param chosen the user's answer to {@link #narrowing()}, where they were
     *               offered it. Ignored for a kind that offers nothing
     */
    public boolean narrowsByVersion(boolean chosen) {
        return filteredByVersion || (narrowing == Narrowing.ONLY_FOR_PROFILE && chosen);
    }

    /**
     * The same question for the loader.
     *
     * <p>Note the third clause, which is the data pack one and reads backwards
     * on purpose: {@link Narrowing#WITHOUT_MODS} unticked is the narrowed list.
     * The loader still only reaches a request when the instance has one - a
     * vanilla instance is offered plain data packs, which is all it can load.
     */
    public boolean narrowsByLoader(boolean chosen) {
        return filteredByLoader
                || (narrowing == Narrowing.ONLY_FOR_PROFILE && chosen)
                || (narrowing == Narrowing.WITHOUT_MODS && !chosen);
    }

    /** True when this kind cannot be installed without a mod loader. */
    public boolean needsLoader() {
        return filteredByLoader;
    }

    /** The file extensions a file of this kind is allowed to have. */
    public List<String> extensions() {
        return extensions;
    }

    /** {@code *.jar}-style patterns, for a file chooser. */
    public List<String> chooserPatterns() {
        return extensions.stream().map(extension -> "*" + extension).toList();
    }

    /**
     * True when this file name looks like this kind of thing.
     *
     * <p>By name only, and deliberately: the folder readers judge the contents
     * separately, and a name test is what a file chooser and an import can do
     * before opening anything.
     */
    public boolean matches(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The categories the platform files this kind under.
     *
     * <p>Not one list for the whole window. Mods have Modrinth's nineteen; a
     * modpack has ten, six of them its own; a data pack is filed as a mod and
     * carries the mod list. Asking for the wrong one is a filter that cannot
     * match, and a search that cannot match looks exactly like a version nothing
     * has been published for.
     */
    public List<ModCategory> categories() {
        return ModCategory.forKind(this);
    }

    /**
     * True when the category filter means anything for this kind.
     *
     * <p>True for all three now that each kind is offered its own list. It was
     * once true for mods alone, because {@link ModCategory} held Modrinth's mod
     * categories and nothing else, and offering those against a modpack returned
     * a confidently empty list.
     */
    public boolean hasCategories() {
        return !categories().isEmpty();
    }
}
