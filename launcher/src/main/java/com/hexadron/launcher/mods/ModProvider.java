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

package com.hexadron.launcher.mods;

import com.hexadron.launcher.install.loader.LoaderType;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** A source of downloadable mods. */
public interface ModProvider {

    enum Source {
        MODRINTH("Modrinth"),
        CURSEFORGE("CurseForge");

        private final String displayName;

        Source(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** A search result, before a specific file has been chosen. */
    record SearchResult(String projectId, String slug, String title, String description,
                        String author, long downloads, String iconUrl, String pageUrl,
                        List<ModCategory> categories, Source source) {

        public SearchResult {
            categories = List.copyOf(categories);
        }

        /** What is worth keeping about this project once it has been installed. */
        public ProjectCard card() {
            return new ProjectCard(source, projectId, slug, title, iconUrl, pageUrl, categories);
        }
    }

    /**
     * A project's identity, kept so an installed mod can still be recognised.
     *
     * <p>The installed list used to hold a name and a file name, because that
     * was all installing needed. Both of the things a user does with a row -
     * recognise the mod by its logo, and go and read about it - need what the
     * platform already returned in the search result and the launcher then threw
     * away. Asking again later is not a substitute: it makes an offline launcher
     * show a list of grey squares.
     *
     * @param iconUrl    the project's own logo, or null when it publishes none
     * @param pageUrl    the project's page on the platform it came from
     * @param categories what the project is filed under, so an installed list can
     *                   say what a mod is for without asking anybody
     */
    record ProjectCard(Source source, String projectId, String slug, String title,
                       String iconUrl, String pageUrl, List<ModCategory> categories) {

        public ProjectCard {
            categories = List.copyOf(categories);
        }

        /** A card for a project nothing is known about beyond its name. */
        public ProjectCard(Source source, String projectId, String slug, String title,
                           String iconUrl, String pageUrl) {
            this(source, projectId, slug, title, iconUrl, pageUrl, List.of());
        }
    }

    Source source();

    /** True when this provider is configured and usable (CurseForge needs an API key). */
    boolean isAvailable();

    /**
     * One page of results, plus how many there are in total.
     *
     * <p>{@code total} is what makes a page honest. Without it the browser can
     * only ever say "here are 40", which reads as "there are 40" - and looks
     * identical for a Minecraft version with four thousand mods and one with
     * fifty.
     *
     * @param total       matches on the platform, or -1 when it does not report one
     * @param offset      index this page started at
     * @param unavailable platforms that were searched and did not answer, one
     *                    line each, ready to show. A platform failing must not
     *                    empty the browser - but it must not be silent either.
     *                    Results that are missing because a key is wrong or a
     *                    service is down look exactly like results that do not
     *                    exist, and the user has no way to tell which they are
     *                    unless told
     */
    record SearchPage(List<SearchResult> results, int total, int offset, List<String> unavailable) {

        public SearchPage {
            results = List.copyOf(results);
            unavailable = List.copyOf(unavailable);
        }

        /** A page every searched platform answered. */
        public SearchPage(List<SearchResult> results, int total, int offset) {
            this(results, total, offset, List.of());
        }

        public static SearchPage empty() {
            return new SearchPage(List.of(), 0, 0);
        }

        /** True when the platform says there is more after this page. */
        public boolean hasMore() {
            return total < 0 ? !results.isEmpty() : offset + results.size() < total;
        }

        /** True when part of what was asked for is missing rather than absent. */
        public boolean isPartial() {
            return !unavailable.isEmpty();
        }
    }

    /**
     * Searches for mods compatible with a Minecraft version and loader.
     *
     * @param categories the categories the results must all be in, or empty for
     *                   no restriction. Several narrow rather than widen, which
     *                   is what the platform's own filter does and therefore what
     *                   a player who has used it expects
     * @param limit      page size
     * @param offset     how many matches to skip, for paging
     */
    SearchPage search(String query, String minecraftVersion, LoaderType loader,
                      ModSort sort, List<ModCategory> categories, int limit, int offset)
            throws IOException, InterruptedException;

    /** First page, relevance-ordered, for callers that do not page or sort. */
    default List<SearchResult> search(String query, String minecraftVersion, LoaderType loader, int limit)
            throws IOException, InterruptedException {
        return search(query, minecraftVersion, loader, ModSort.RELEVANCE, List.of(), limit, 0)
                .results();
    }

    /**
     * The newest file of {@code projectId} compatible with the given version and
     * loader, or empty when the project has none.
     */
    Optional<ModFile> resolveLatest(String projectId, String minecraftVersion, LoaderType loader)
            throws IOException, InterruptedException;

    /**
     * The human-readable name of a project.
     *
     * <p>Needed for dependencies. A mod the user chose arrives with the name they
     * clicked; a mod pulled in behind it arrives as nothing but an id, and
     * "eXts2L7r" in an installed list is indistinguishable from something that
     * has no business being there. One request per dependency is a fair price for
     * a list a user can read.
     *
     * @return empty when the platform does not answer, which is not an error -
     *         the caller falls back to the file name
     */
    default Optional<String> projectName(String projectId) throws IOException, InterruptedException {
        return project(projectId).map(ProjectCard::title);
    }

    /**
     * Everything worth keeping about a project, in one request.
     *
     * <p>The name alone is not enough any more: a dependency arrives as an id,
     * and a row for it needs the same logo and the same link as a mod the user
     * picked themselves. One request answers all three, so this replaces the
     * name lookup rather than joining it.
     *
     * @return empty when the platform does not answer or has no such project,
     *         which is not an error - the caller falls back to the file name
     */
    default Optional<ProjectCard> project(String projectId) throws IOException, InterruptedException {
        return Optional.empty();
    }
}
