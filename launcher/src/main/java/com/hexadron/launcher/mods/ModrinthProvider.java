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
                             ModSort sort, int limit, int offset)
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
            results.add(new SearchResult(
                    hit.get("project_id").asString(""),
                    hit.get("slug").asString(""),
                    hit.get("title").asString(""),
                    hit.get("description").asString(""),
                    hit.get("author").asString(""),
                    hit.get("downloads").asLong(0),
                    hit.get("icon_url").asString(null),
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
