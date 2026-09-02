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

import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Modrinth (api.modrinth.com/v2).
 *
 * <p>No API key is required for reads. Modrinth's terms ask for a descriptive,
 * contactable User-Agent, which {@link Http} sends on every request.
 */
public final class ModrinthProvider implements ModProvider {

    private static final String API = "https://api.modrinth.com/v2";

    @Override
    public Source source() {
        return Source.MODRINTH;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public SearchPage search(String query, String minecraftVersion, LoaderType loader,
                             ModSort sort, List<ModCategory> categories, int limit, int offset)
            throws IOException, InterruptedException {

        // Modrinth facets are an array of OR-groups that are ANDed together.
        List<String> facetGroups = new ArrayList<>();
        facetGroups.add("[\"project_type:mod\"]");
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            facetGroups.add("[\"versions:" + minecraftVersion + "\"]");
        }
        if (loader != null && loader.isModded()) {
            facetGroups.add("[\"categories:" + loader.platformId() + "\"]");
        }
        // One group each, so they are ANDed: two ticked categories mean "both",
        // which is how the platform's own filter behaves. A single group would
        // mean "either" and would widen the search that the player just narrowed.
        for (ModCategory category : categories) {
            facetGroups.add("[\"categories:" + category.id() + "\"]");
        }
        String facets = "[" + String.join(",", facetGroups) + "]";

        String url = API + "/search"
                + "?query=" + encode(query == null ? "" : query)
                + "&limit=" + Math.max(1, Math.min(limit, 100))
                + "&offset=" + Math.max(0, offset)
                + "&index=" + (sort == null ? ModSort.RELEVANCE : sort).modrinthIndex()
                + "&facets=" + encode(facets);

        Json response = Http.getJson(url);
        List<SearchResult> results = new ArrayList<>();
        for (Json hit : response.get("hits").elements()) {
            String slug = hit.get("slug").asString("");
            results.add(new SearchResult(
                    hit.get("project_id").asString(""),
                    slug,
                    hit.get("title").asString(""),
                    hit.get("description").asString(""),
                    hit.get("author").asString(""),
                    hit.get("downloads").asLong(0),
                    hit.get("icon_url").asString(null),
                    pageUrl(slug),
                    categoriesOf(hit.get("categories")),
                    Source.MODRINTH));
        }
        // total_hits counts every match for these facets, not just this page.
        return new SearchPage(results, response.get("total_hits").asInt(-1), Math.max(0, offset));
    }

    @Override
    public Optional<ModFile> resolveLatest(String projectId, String minecraftVersion, LoaderType loader)
            throws IOException, InterruptedException {

        StringBuilder url = new StringBuilder(API)
                .append("/project/").append(encode(projectId)).append("/version");
        List<String> params = new ArrayList<>();
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            params.add("game_versions=" + encode("[\"" + minecraftVersion + "\"]"));
        }
        if (loader != null && loader.isModded()) {
            params.add("loaders=" + encode("[\"" + loader.platformId() + "\"]"));
        }
        if (!params.isEmpty()) {
            url.append('?').append(String.join("&", params));
        }

        Json versions;
        try {
            versions = Http.getJson(url.toString());
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }

        // The endpoint returns newest first. Prefer a release over a beta when
        // both are present for this version.
        Json chosen = null;
        for (Json version : versions.elements()) {
            if (chosen == null) {
                chosen = version;
            }
            if ("release".equals(version.get("version_type").asString(""))) {
                chosen = version;
                break;
            }
        }
        if (chosen == null) {
            return Optional.empty();
        }
        return Optional.of(toModFile(projectId, chosen));
    }

    @Override
    public Optional<ProjectCard> project(String projectId) throws IOException, InterruptedException {
        try {
            Json project = Http.getJson(API + "/project/" + encode(projectId));
            String title = project.get("title").asString(null);
            if (title == null || title.isBlank()) {
                return Optional.empty();
            }
            // The slug is what the website is addressed by. The id works too and
            // is what a dependency arrives as, so it is the fallback rather than
            // a reason to have no link.
            String slug = project.get("slug").asString(projectId);
            return Optional.of(new ProjectCard(Source.MODRINTH,
                    project.get("id").asString(projectId), slug, title,
                    project.get("icon_url").asString(null), pageUrl(slug),
                    categoriesOf(project.get("categories"))));
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * A project's categories, the loaders left out.
     *
     * <p>The full list, not {@code display_categories}. That field is the three
     * the author chose to feature on the site's own card, and reading it left
     * rows saying a mod was three things when the platform had it filed under
     * seven - a shorter answer than the truth with nothing to show it was short.
     *
     * <p>It goes through {@link ModCategory}, which is what keeps {@code fabric}
     * and {@code neoforge} - stored in the very same field - out of a list of
     * what a mod is <em>for</em>.
     */
    private static List<ModCategory> categoriesOf(Json categories) {
        List<String> ids = new ArrayList<>();
        for (Json entry : categories.elements()) {
            String id = entry.asString(null);
            if (id != null) {
                ids.add(id);
            }
        }
        return ModCategory.parse(ids);
    }

    /**
     * The line drawings the platform publishes beside its category names.
     *
     * <p>The same endpoint carries the categories of resource packs, plugins and
     * servers, so only the ones filed under a mod are kept.
     *
     * @return category identifier to the markup of its drawing
     */
    public java.util.Map<String, String> categoryArt() throws IOException, InterruptedException {
        java.util.Map<String, String> art = new java.util.LinkedHashMap<>();
        for (Json tag : Http.getJson(API + "/tag/category").elements()) {
            if (!"mod".equals(tag.get("project_type").asString(""))) {
                continue;
            }
            String name = tag.get("name").asString(null);
            String icon = tag.get("icon").asString(null);
            if (name != null && icon != null && !icon.isBlank()) {
                art.putIfAbsent(name, icon);
            }
        }
        return art;
    }

    /**
     * The page a user reads about this mod on.
     *
     * <p>Built rather than fetched. Modrinth's search returns no link, the shape
     * {@code modrinth.com/mod/<slug>} is what the site itself publishes, and one
     * extra request per row to be told that is not a trade worth making.
     */
    public static String pageUrl(String slug) {
        return slug == null || slug.isBlank()
                ? null
                : "https://modrinth.com/mod/" + encode(slug);
    }

    /** Resolves one exact version id, used when a pack pins a build. */
    public Optional<ModFile> resolveVersion(String projectId, String versionId)
            throws IOException, InterruptedException {
        try {
            Json version = Http.getJson(API + "/version/" + encode(versionId));
            return Optional.of(toModFile(projectId, version));
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * The Modrinth file with this SHA-1, if Modrinth has one.
     *
     * <p>This is how a mod whose author has turned off third-party downloads on
     * CurseForge can still be installed: many of those mods are published on
     * Modrinth as well, by the same author, and a matching SHA-1 means it is
     * byte for byte the same jar. Nothing is circumvented - the file is taken
     * from a place the author did allow.
     *
     * @param sha1 the digest to look for; the hash is the whole point, so a
     *             mismatching file is never returned
     */
    public Optional<ModFile> resolveByHash(String sha1) throws IOException, InterruptedException {
        if (sha1 == null || sha1.isBlank()) {
            return Optional.empty();
        }
        String wanted = sha1.trim().toLowerCase(Locale.ROOT);
        Json version;
        try {
            version = Http.getJson(API + "/version_file/" + encode(wanted) + "?algorithm=sha1");
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }

        for (Json file : version.get("files").elements()) {
            if (wanted.equalsIgnoreCase(file.get("hashes").get("sha1").asString(""))) {
                return Optional.of(toModFile(
                        version.get("project_id").asString(""), version, file));
            }
        }
        return Optional.empty();
    }

    /**
     * Which Modrinth project each of these files belongs to, in one request.
     *
     * <p>Used to work out what the jars a player copied into the folder by hand
     * actually are. One request rather than one per file: a mods folder is
     * routinely eighty jars, and eighty round trips is a button that appears to
     * have hung.
     *
     * @param sha1s digests of the files to ask about
     * @return digest to project id, containing only the ones Modrinth knows
     */
    public java.util.Map<String, String> projectsByHash(java.util.Collection<String> sha1s)
            throws IOException, InterruptedException {

        java.util.Map<String, String> found = new java.util.LinkedHashMap<>();
        if (sha1s.isEmpty()) {
            return found;
        }
        Json hashes = Json.array();
        sha1s.forEach(hash -> hashes.add(hash.toLowerCase(Locale.ROOT)));
        Json body = Json.object().put("hashes", hashes).put("algorithm", "sha1");

        Json response = Http.postJson(API + "/version_files", body,
                java.util.Map.of("Accept", "application/json"));
        response.fields().forEach((hash, version) -> {
            String projectId = version.get("project_id").asString(null);
            if (projectId != null && !projectId.isBlank()) {
                found.put(hash.toLowerCase(Locale.ROOT), projectId);
            }
        });
        return found;
    }

    /** Several projects in one request, for the same reason as {@link #projectsByHash}. */
    public List<ProjectCard> projects(java.util.Collection<String> projectIds)
            throws IOException, InterruptedException {

        List<ProjectCard> cards = new ArrayList<>();
        if (projectIds.isEmpty()) {
            return cards;
        }
        StringBuilder ids = new StringBuilder("[");
        for (String id : projectIds) {
            if (ids.length() > 1) {
                ids.append(',');
            }
            ids.append('"').append(id).append('"');
        }
        ids.append(']');

        Json response = Http.getJson(API + "/projects?ids=" + encode(ids.toString()));
        for (Json project : response.elements()) {
            String id = project.get("id").asString(null);
            if (id == null) {
                continue;
            }
            String slug = project.get("slug").asString(id);
            cards.add(new ProjectCard(Source.MODRINTH, id, slug,
                    project.get("title").asString(slug),
                    project.get("icon_url").asString(null), pageUrl(slug),
                    categoriesOf(project.get("categories"))));
        }
        return cards;
    }

    private ModFile toModFile(String projectId, Json version) {
        // A version can carry several files; the primary one is the mod jar,
        // the rest are sources/javadoc/extras that must not go into mods/.
        Json chosenFile = null;
        for (Json file : version.get("files").elements()) {
            if (chosenFile == null) {
                chosenFile = file;
            }
            if (file.get("primary").asBool(false)) {
                chosenFile = file;
                break;
            }
        }
        if (chosenFile == null) {
            throw new IllegalStateException("Modrinth version " + version.get("id").asString("?")
                    + " has no files");
        }
        return toModFile(projectId, version, chosenFile);
    }

    private ModFile toModFile(String projectId, Json version, Json chosenFile) {
        List<String> dependencies = new ArrayList<>();
        for (Json dependency : version.get("dependencies").elements()) {
            if (!"required".equals(dependency.get("dependency_type").asString(""))) {
                continue;
            }
            String dependencyProject = dependency.get("project_id").asString(null);
            if (dependencyProject != null) {
                dependencies.add(dependencyProject);
            }
        }

        return new ModFile(
                version.get("project_id").asString(projectId),
                null,
                version.get("id").asString(""),
                version.get("name").asString(version.get("version_number").asString("")),
                chosenFile.get("filename").asString(""),
                chosenFile.get("url").asString(null),
                chosenFile.get("hashes").get("sha1").asString(null),
                chosenFile.get("size").asLong(-1),
                dependencies,
                Source.MODRINTH);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
