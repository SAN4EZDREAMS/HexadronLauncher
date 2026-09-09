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
import java.util.Optional;

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
 * by version and never by loader, because vanilla Minecraft is what loads one -
 * every data pack for the right version works on every instance.
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
    MOD("mod", 6, "mods.kind.mod", "mod", true, true, Choice.NONE, List.of(".jar"),
            "mods", null),

    /**
     * A modpack: a version, a loader and a list of files, in one archive.
     *
     * <p>Not filtered by the instance's version or loader, because a pack names
     * its own and the point of the list is to find one to install.
     */
    MODPACK("modpack", 4471, "mods.kind.modpack", "modpack", false, false,
            Choice.ONLY_FOR_PROFILE, List.of(".mrpack", ".zip"), null, null),

    /**
     * A data pack: a zip in one world's {@code datapacks/} folder.
     *
     * <p>Filtered by Minecraft version always - a data pack declares which ones
     * it is written for and the game refuses the wrong pack format - and never
     * by loader. Vanilla Minecraft is what loads a data pack, so every pack for
     * the right version works on every instance, and narrowing by the loader
     * would hide packs that are perfectly installable.
     *
     * <p>It is worth saying what the loader tags on these projects are, since
     * they are there and are not this. Much of the catalogue is published twice
     * over: the data pack, and a mod build of the same content that a loader
     * applies to every world. A project carries the tags of both, so filtering
     * by a loader would leave only the packs that have that second build -
     * a smaller list, chosen by something the world folder does not care about.
     * What goes into a world is the data pack, always.
     */
    DATAPACK("datapack", 6945, "mods.kind.datapack", "datapack", true, false,
            Choice.WITHOUT_MODS, List.of(".zip"), null, "datapack"),

    /**
     * A resource pack: a zip in the instance's {@code resourcepacks} folder.
     *
     * <p>Filtered by Minecraft version and never by loader. Vanilla Minecraft
     * loads resource packs, so a loader narrows nothing - and the version
     * genuinely matters here, because a pack states a {@code pack_format} and the
     * game refuses one from the wrong era rather than making do.
     *
     * <p>Modrinth files the whole catalogue under
     * {@code project_type:resourcepack} - there is no second spelling to OR
     * against, unlike data packs - and tags every one of them with the loader
     * {@code minecraft}, which is that platform's way of writing "loaded by the
     * game itself".
     */
    RESOURCEPACK("resourcepack", 12, "mods.kind.resourcepack", "resourcepack", true, false,
            Choice.NONE, List.of(".zip"), "resourcepacks", "minecraft"),

    /**
     * A shader pack: a zip in the instance's {@code shaderpacks} folder.
     *
     * <h2>Why this one is not narrowed by version</h2>
     *
     * <p>Every other kind here is. A shader pack is GLSL loaded by Iris, OptiFine
     * or Canvas, and what it is written against is that program's pipeline rather
     * than a Minecraft release - which is why one pack runs for years across a
     * dozen versions, and why its author lists whichever versions they happened
     * to test. Narrowing the catalogue to the instance's exact version therefore
     * hides packs that work, and on a version published last month it hides
     * nearly all of them. The kinds where a wrong build is a crash are narrowed;
     * this is not one of them.
     *
     * <p>The loader is not the profile's either. A shader is loaded by
     * {@code iris}, {@code optifine} or {@code canvas} - not by Fabric or Forge -
     * so the tag a <em>file</em> is asked for comes from what the instance
     * actually has installed, which no enum can know: see {@link ShaderLoaders}.
     * The catalogue is not narrowed by it, because most packs publish for two of
     * the three and somebody still deciding which loader to install wants to see
     * what they would get.
     */
    SHADER("shader", 6552, "mods.kind.shader", "shader", false, false,
            Choice.NONE, List.of(".zip"), "shaderpacks", null);

    /**
     * The one thing a kind asks the user, if it has one.
     *
     * <h2>Why one question and not two flags</h2>
     *
     * <p>Every kind has exactly one thing worth a tick box, and it is a
     * different thing per kind - so it is one box, described by the kind, and
     * one answer read by whoever asked. A second boolean beside it would be a
     * control that is meaningless for two kinds out of three, and every layer
     * between the box and whatever reads it would have to carry both.
     *
     * <p>Each value owns its own words and its own default, because those are
     * part of the question rather than of the panel that draws it - and it says
     * whether the answer belongs to the search or to the install, which is the
     * distinction that keeps a box from re-running a request it cannot change.
     */
    public enum Choice {

        /** Nothing to ask. A mod is narrowed to the instance, always. */
        NONE(null, null, false, false),

        /**
         * "Show only modpacks that fit this profile", ticked to begin with.
         *
         * <p>A pack states its own version and loader, so the unnarrowed
         * catalogue is thousands of packs almost none of which can be installed
         * without replacing what the instance is.
         *
         * <p>A search question: the narrowing is the platform's own, so changing
         * the answer means asking again.
         */
        ONLY_FOR_PROFILE("mods.onlyForProfile", "mods.onlyForProfile.tip", true, true),

        /**
         * "Install data packs without mods", unticked to begin with.
         *
         * <p>An install question, and the only one of these that is not about
         * the list. Some data packs name a mod as a requirement - a loader for
         * global packs, a library - and unticked the launcher fetches it into
         * the instance's mods folder; ticked, it installs the pack and touches
         * nothing outside the world. The pack that lands in the world is the
         * same file either way.
         *
         * <p>Unticked to begin with because a pack that names a requirement does
         * not work without it, and a player who asked for the pack asked for it
         * working. The box is for somebody who wants their mods folder left
         * alone and will accept a pack that does less.
         *
         * <p>It narrows nothing, so ticking it does not re-run the search. This
         * was tried the other way round - the box widened a catalogue that had
         * been narrowed to the instance's loader by default - and the default
         * was simply wrong: a data pack is loaded by vanilla Minecraft, so
         * filtering by loader hid packs that install and work.
         */
        WITHOUT_MODS("datapacks.withoutMods", "datapacks.withoutMods.tip", false, false);

        private final String key;
        private final String tipKey;
        private final boolean chosenByDefault;
        private final boolean affectsSearch;

        Choice(String key, String tipKey, boolean chosenByDefault, boolean affectsSearch) {
            this.key = key;
            this.tipKey = tipKey;
            this.chosenByDefault = chosenByDefault;
            this.affectsSearch = affectsSearch;
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
         * True when the answer belongs in the request to the platform.
         *
         * <p>False for a box that only decides what an install does. Such a box
         * must not re-run the search: the page in hand is already the answer,
         * and asking again for it would be a spinner and a scroll position lost
         * for nothing.
         */
        public boolean affectsSearch() {
            return affectsSearch;
        }

        /**
         * What an empty answer says.
         *
         * <p>It has to name the box whenever the box is what emptied the list,
         * or "nothing matches" reads as "no such thing exists for your version".
         */
        public String emptyKey(boolean chosen) {
            return switch (this) {
                case ONLY_FOR_PROFILE -> chosen ? "mods.noResults.forProfile" : "mods.noResults";
                // A box that narrows nothing cannot be what emptied the list, so
                // naming it would send the reader to a control that will not
                // help.
                case NONE, WITHOUT_MODS -> "mods.noResults";
            };
        }
    }

    private final String modrinthProjectType;
    private final int curseForgeClassId;
    private final String key;
    private final String modrinthPagePath;
    private final boolean filteredByVersion;
    private final boolean filteredByLoader;
    private final Choice choice;
    private final List<String> extensions;
    private final String instanceFolder;
    private final String modrinthLoaderTag;

    ContentKind(String modrinthProjectType, int curseForgeClassId, String key,
                String modrinthPagePath, boolean filteredByVersion, boolean filteredByLoader,
                Choice choice, List<String> extensions,
                String instanceFolder, String modrinthLoaderTag) {
        this.modrinthProjectType = modrinthProjectType;
        this.curseForgeClassId = curseForgeClassId;
        this.key = key;
        this.modrinthPagePath = modrinthPagePath;
        this.filteredByVersion = filteredByVersion;
        this.filteredByLoader = filteredByLoader;
        this.choice = choice;
        this.extensions = List.copyOf(extensions);
        this.instanceFolder = instanceFolder;
        this.modrinthLoaderTag = modrinthLoaderTag;
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
     * The folder inside an instance where this kind lives, or null.
     *
     * <p>Null for the two kinds that have no single one: a modpack is unpacked
     * across the whole instance, and a data pack goes into one <em>world</em>
     * rather than into the instance - which is why that section has a world
     * picker and these do not.
     */
    public String instanceFolder() {
        return instanceFolder;
    }

    /** True when this kind lives in one folder per instance. */
    public boolean hasInstanceFolder() {
        return instanceFolder != null;
    }

    /**
     * The record file this kind keeps beside its files.
     *
     * <p>One per folder, never shared. A folder holding two kinds could
     * otherwise have one record claiming the other's files, and the rule that
     * protects a player's own files is exactly "if it is not in this record, it
     * is not ours to touch".
     */
    public String lockFile() {
        return switch (this) {
            case MOD -> ModLibrary.LOCK_FILE;
            case DATAPACK -> DatapackScan.LOCK_FILE;
            case RESOURCEPACK -> ".hexadron-resourcepacks.json";
            case SHADER -> ".hexadron-shaderpacks.json";
            case MODPACK -> ModpackLibrary.LOCK_FILE;
        };
    }

    /**
     * The Modrinth loader tag that identifies this kind, or null when it has no
     * single one.
     *
     * <p>Modrinth keeps "what loads this" in the same field as the mod loaders,
     * so a kind loaded by something other than Fabric or Forge has a tag of its
     * own there: {@code datapack} for a data pack, {@code minecraft} for a
     * resource pack. For a data pack the tag is load-bearing - it is what
     * separates a pack from a mod in the half of the catalogue where both are
     * {@code project_type:mod}.
     *
     * <p>Null for mods and modpacks, whose loader is the profile's, and for
     * shaders, which have three of them - {@code iris}, {@code optifine},
     * {@code canvas} - and no way to choose without looking at what the instance
     * has installed. See {@link ShaderLoaders}.
     */
    public String modrinthLoaderTag() {
        return modrinthLoaderTag;
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

    /**
     * The kind a CurseForge {@code classId} names, if this launcher offers it.
     *
     * <p>The way back from a number the platform put in a response. A project
     * fetched by id says which class it is in and nothing else about its kind,
     * and the kind is what decides which of CurseForge's category lists its
     * categories are from - see {@link CurseForgeCategories}.
     */
    public static Optional<ContentKind> byCurseForgeClassId(int classId) {
        for (ContentKind kind : values()) {
            if (kind.curseForgeClassId == classId) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
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

    /** The one thing this kind asks the user, or {@link Choice#NONE}. */
    public Choice choice() {
        return choice;
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
     * <p>A data pack is narrowed by version and never by loader, and its own
     * question is not about the list at all - see {@link Choice#WITHOUT_MODS} -
     * so this stays a question about modpacks rather than becoming a flag three
     * kinds share.
     */
    public boolean isNarrowableToProfile() {
        return choice == Choice.ONLY_FOR_PROFILE;
    }

    /**
     * Whether a search should be narrowed to the profile's Minecraft version.
     *
     * @param onlyForProfile the user's answer, for the one kind whose box
     *                       narrows the search - see
     *                       {@link Choice#ONLY_FOR_PROFILE}. Ignored for the
     *                       kinds where it is not a question the search asks
     */
    public boolean narrowsByVersion(boolean onlyForProfile) {
        return filteredByVersion || (choice == Choice.ONLY_FOR_PROFILE && onlyForProfile);
    }

    /** The same question for the loader. See {@link #narrowsByVersion}. */
    public boolean narrowsByLoader(boolean onlyForProfile) {
        return filteredByLoader || (choice == Choice.ONLY_FOR_PROFILE && onlyForProfile);
    }

    /**
     * True when this kind cannot be installed at all without a mod loader.
     *
     * <p>Mods only, and it is deliberately not the same question as
     * {@link #isFilteredByLoader()}. A resource pack and a data pack are loaded
     * by the game. A shader is loaded by Iris, OptiFine or Canvas - which is a
     * mod, and therefore something the section reports as missing and offers to
     * fetch, rather than a reason to refuse the search: browsing shaders to
     * decide whether to install Iris at all is a reasonable thing to do.
     */
    public boolean needsLoader() {
        return this == MOD;
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
     * <p>True for every kind now that each is offered its own list. It was once
     * true for mods alone, because {@link ModCategory} held Modrinth's mod
     * categories and nothing else, and offering those against a modpack returned
     * a confidently empty list.
     */
    public boolean hasCategories() {
        return !categories().isEmpty();
    }
}
