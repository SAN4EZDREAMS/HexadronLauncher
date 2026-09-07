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
 * hide every pack the player might want to install next. A data pack has a
 * version and no loader - vanilla Minecraft loads it.
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
    MOD("mod", 6, "mods.kind.mod", "mod", true, true, List.of(".jar")),

    /**
     * A modpack: a version, a loader and a list of files, in one archive.
     *
     * <p>Not filtered by the instance's version or loader, because a pack names
     * its own and the point of the list is to find one to install.
     */
    MODPACK("modpack", 4471, "mods.kind.modpack", "modpack", false, false,
            List.of(".mrpack", ".zip")),

    /**
     * A data pack: a zip in one world's {@code datapacks/} folder.
     *
     * <p>Filtered by Minecraft version - a data pack declares which ones it is
     * written for and the game refuses the wrong pack format - and not by loader,
     * because vanilla Minecraft is what loads it.
     */
    DATAPACK("datapack", 6945, "mods.kind.datapack", "datapack", true, false,
            List.of(".zip"));

    private final String modrinthProjectType;
    private final int curseForgeClassId;
    private final String key;
    private final String modrinthPagePath;
    private final boolean filteredByVersion;
    private final boolean filteredByLoader;
    private final List<String> extensions;

    ContentKind(String modrinthProjectType, int curseForgeClassId, String key,
                String modrinthPagePath, boolean filteredByVersion, boolean filteredByLoader,
                List<String> extensions) {
        this.modrinthProjectType = modrinthProjectType;
        this.curseForgeClassId = curseForgeClassId;
        this.key = key;
        this.modrinthPagePath = modrinthPagePath;
        this.filteredByVersion = filteredByVersion;
        this.filteredByLoader = filteredByLoader;
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
     * True when the category filter means anything for this kind.
     *
     * <p>{@link ModCategory} is the nineteen Modrinth files <em>mods</em> under.
     * A modpack and a data pack are filed under different sets, and offering a
     * mod's categories against them would return a confidently empty list rather
     * than an honest one - so the filter is not offered there at all.
     */
    public boolean hasCategories() {
        return this == MOD;
    }
}
