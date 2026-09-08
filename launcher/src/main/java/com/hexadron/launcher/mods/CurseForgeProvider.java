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

import com.hexadron.launcher.BuildConfig;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.util.Redactor;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * CurseForge (api.curseforge.com/v1).
 *
 * <p>Three constraints, all imposed by CurseForge rather than by this code.
 *
 * <p><b>A key is required for every request.</b> It is a <em>Core API key</em>
 * from {@code console.curseforge.com}, and CurseForge's other key page - the
 * author site's "API tokens" - issues something else entirely that this service
 * refuses with a {@code 403}. Which is a mistake worth naming rather than
 * letting a user debug: see {@link KeyShape}.
 *
 * <p>Where the key comes from, in order, and the first non-empty one wins:
 * <ol>
 *   <li>the launcher settings, so a user can always use their own key;</li>
 *   <li>the {@code CURSEFORGE_API_KEY} environment variable;</li>
 *   <li>whatever the build put in - see {@link BuildConfig}, which explains why
 *       the key is not in the repository.</li>
 * </ol>
 * With none of those the provider reports itself unavailable and the interface
 * leaves CurseForge out. That is a working launcher without one platform, not a
 * broken one: Modrinth needs no key at all.
 *
 * <p><b>The key is needed for the downloads too, not only the search.</b> Since
 * July 2026 CurseForge's content hosts reject unauthenticated requests with
 * {@code 401}. The key is therefore registered against those hosts in
 * {@link Http}, so the generic downloader sends it without knowing what
 * CurseForge is.
 *
 * <p><b>Authors can forbid third-party downloads.</b> For those projects the API
 * returns a file with no {@code downloadUrl}. That is a licence decision and not
 * an error, so this provider hands the file back without a URL and lets
 * {@link ModInstaller} decide what to do - which is to look for the identical
 * file on Modrinth, and otherwise to say so and skip it. It is never worked
 * around.
 */
public final class CurseForgeProvider implements ModProvider {

    private static final String API = "https://api.curseforge.com/v1";
    private static final String API_KEY_HEADER = "x-api-key";

    /**
     * Where a key that works here is created.
     *
     * <p>Written down because it is the answer to the one question this class
     * cannot work around, and because there are two CurseForge key pages and
     * only one of them issues a key for this API. See {@link KeyShape}.
     */
    public static final String CONSOLE_URL = "https://console.curseforge.com/";

    /** The other page: where the token that does <em>not</em> work comes from. */
    public static final String AUTHOR_TOKENS_URL =
            "https://legacy.curseforge.com/account/api-tokens";

    /**
     * A Core API key, as the console issues them: a bcrypt-shaped string.
     *
     * <p>{@code $2a$10$} and then fifty-odd characters. Matched loosely - the
     * cost version and the salt are not this launcher's business - because the
     * point is to tell it apart from the other kind of key, not to validate it.
     */
    private static final Pattern CORE_KEY_SHAPE =
            Pattern.compile("^\\$2[abxy]?\\$\\d{2}\\$\\S{20,}$");

    /**
     * An Upload API token, as the author site issues them: 32 hex characters,
     * or the same thing written as a UUID.
     */
    private static final Pattern UPLOAD_TOKEN_SHAPE = Pattern.compile(
            "^[0-9a-fA-F]{32}$"
                    + "|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** CurseForge's game id for Minecraft. */
    private static final int GAME_MINECRAFT = 432;

    /**
     * The key the host-header rule reads.
     *
     * <p>The launcher has one CurseForge provider, and the rule in {@link Http}
     * is registered once and outlives any single provider instance, so the key it
     * sends is kept here rather than captured. Setting a key in the settings then
     * takes effect on the next request, with no restart and no re-registration.
     */
    private static final AtomicReference<String> ACTIVE_KEY = new AtomicReference<>("");
    private static final AtomicBoolean HEADER_RULE_REGISTERED = new AtomicBoolean();

    /**
     * Which of CurseForge's two kinds of key this looks like.
     *
     * <h2>Why this is worth detecting</h2>
     *
     * <p>CurseForge has two API key pages and they are not interchangeable, and
     * nothing on either page says so. The one a search engine finds first -
     * {@link #AUTHOR_TOKENS_URL} - issues an <b>Upload API token</b>: a
     * 32-character string, sent as {@code X-Api-Token}, whose purpose is
     * uploading files to projects you own and reading the list of game versions.
     * It is not a credential for {@code api.curseforge.com} and that service
     * rejects it with {@code 403} on every request.
     *
     * <p>The one a launcher needs is a <b>Core API key</b> from
     * {@link #CONSOLE_URL}, sent as {@code x-api-key}. The two look nothing
     * alike, which is what makes this checkable: a 403 with a 32-hex key in the
     * settings has one overwhelmingly likely cause, and saying so turns an
     * afternoon of guessing into one sentence.
     *
     * <p>A shape is a guess and is never a reason to refuse a key. CurseForge
     * may change either format, and a launcher that rejected the new one would
     * be broken by a change it could simply have passed on.
     */
    public enum KeyShape {

        /** Looks like a Core API key: the right sort for this API. */
        CORE,

        /** Looks like an Upload API token: the wrong sort, and a common mistake. */
        UPLOAD_TOKEN,

        /** Neither shape. No opinion - it is sent and the service decides. */
        UNKNOWN
    }

    /** Which kind of key this looks like. Reads the string, sends nothing. */
    public static KeyShape shapeOf(String key) {
        if (key == null) {
            return KeyShape.UNKNOWN;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return KeyShape.UNKNOWN;
        }
        if (CORE_KEY_SHAPE.matcher(trimmed).matches()) {
            return KeyShape.CORE;
        }
        if (UPLOAD_TOKEN_SHAPE.matcher(trimmed).matches()) {
            return KeyShape.UPLOAD_TOKEN;
        }
        return KeyShape.UNKNOWN;
    }

    /** The shape of the key this provider is using. */
    public KeyShape keyShape() {
        return shapeOf(apiKey);
    }

    /**
     * Why CurseForge is likely to have refused the key in use.
     *
     * <p>Appended to a 401 or a 403, which on its own is a number the reader
     * cannot act on. Static because the message is wanted from
     * {@link ModInstaller#reasonFor}, which is handed an exception rather than a
     * provider - and there is only ever one key in use.
     */
    public static String explainRejection() {
        return switch (shapeOf(ACTIVE_KEY.get())) {
            case UPLOAD_TOKEN -> "that key is 32 hex characters, which is the shape of an"
                    + " Upload API token from " + AUTHOR_TOKENS_URL + ". That token uploads"
                    + " files to projects you own; it is not a credential for"
                    + " api.curseforge.com. Create a Core API key at " + CONSOLE_URL
                    + " and paste that one instead";
            case CORE -> "the key has the shape of a Core API key, so it is the right sort and"
                    + " was still refused: it may have been revoked, or the account it belongs"
                    + " to may not be approved for the API yet. Create a new one at "
                    + CONSOLE_URL;
            case UNKNOWN -> "a key for this API is created at " + CONSOLE_URL + ". A token from "
                    + AUTHOR_TOKENS_URL + " is a different thing and is not accepted here";
        };
    }

    /**
     * One request to the API, with the key attached and a refusal explained.
     *
     * <p>Every path into this class goes through here so that a 401 or a 403
     * carries the sentence that names the two key pages, once. Before this the
     * message a user got depended on which button they had pressed: a search
     * arrived with the explanation, and installing a mod arrived as
     * {@code HTTP 403 for https://api.curseforge.com/v1/mods/1234/files?...} -
     * the same fault, reported as a URL they never typed.
     */
    private Json get(String url) throws IOException, InterruptedException {
        try {
            return Http.getJson(url, headers());
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() != 401 && e.statusCode() != 403) {
                throw e;
            }
            throw new KeyRejectedException(e.statusCode(), e);
        }
    }

    /**
     * CurseForge refused the key.
     *
     * <p>A subclass rather than a message, so that the status code survives for
     * the callers that ask about it - {@link #resolveFile} treats a 404 as "no
     * such project" and must not treat this the same way - and so the sentence
     * is written once.
     */
    public static final class KeyRejectedException extends IOException {

        private final int statusCode;

        KeyRejectedException(int statusCode, Throwable cause) {
            super("CurseForge refused the API key (HTTP " + statusCode + "). "
                    + explainRejection(), cause);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    /** What one probe request found out about the key. */
    public record KeyCheck(boolean ok, String message) {
    }

    /**
     * Asks CurseForge whether it accepts the key, now.
     *
     * <p>One small request, made because somebody just pasted a key. Without it
     * the answer arrives as an empty catalogue on the next search, which looks
     * like a platform with nothing on it for this version rather than like a
     * key that was refused - and that is exactly how a wrong key gets mistaken
     * for a broken launcher.
     *
     * <p>Never throws. A refusal and an unreachable service are both answers,
     * and both belong on the status line rather than in a stack trace.
     */
    public KeyCheck verify() throws InterruptedException {
        if (apiKey == null) {
            return new KeyCheck(false, "no key is set, so CurseForge is switched off");
        }
        try {
            Json game = get(API + "/games/" + GAME_MINECRAFT).get("data");
            if (game.get("id").asLong(0) != GAME_MINECRAFT) {
                return new KeyCheck(false, "CurseForge answered, and not with Minecraft");
            }
            return new KeyCheck(true, "the key was accepted");
        } catch (KeyRejectedException e) {
            return new KeyCheck(false, e.getMessage());
        } catch (Http.HttpStatusException e) {
            return new KeyCheck(false, "HTTP " + e.statusCode() + " from CurseForge");
        } catch (IOException e) {
            return new KeyCheck(false,
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Where the key in use came from. Shown in diagnostics, never the key itself. */
    public enum KeySource {
        SETTINGS("launcher settings"),
        ENVIRONMENT("the CURSEFORGE_API_KEY environment variable"),
        BUILD("this build"),
        NONE("nowhere - CurseForge is off");

        private final String description;

        KeySource(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private volatile String apiKey;
    private volatile KeySource keySource;

    public CurseForgeProvider(String apiKey) {
        registerHeaderRule();
        apply(apiKey, apiKey == null || apiKey.isBlank() ? KeySource.NONE : KeySource.SETTINGS);
    }

    /**
     * Builds a provider from the settings, falling back to the environment and
     * then to the built-in key.
     */
    public static CurseForgeProvider fromEnvironment(String configuredKey) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            return new CurseForgeProvider(configuredKey);
        }
        CurseForgeProvider provider = new CurseForgeProvider(null);
        String environment = System.getenv("CURSEFORGE_API_KEY");
        if (environment != null && !environment.isBlank()) {
            provider.apply(environment, KeySource.ENVIRONMENT);
        } else if (BuildConfig.hasCurseForgeApiKey()) {
            provider.apply(BuildConfig.curseForgeApiKey(), KeySource.BUILD);
        }
        return provider;
    }

    /**
     * Replaces the key at runtime, for when the user pastes one in.
     *
     * <p>An empty value returns the provider to "no key", which switches
     * CurseForge back off rather than leaving it failing every request.
     */
    public void apiKey(String value) {
        apply(value, value == null || value.isBlank() ? KeySource.NONE : KeySource.SETTINGS);
    }

    private void apply(String value, KeySource source) {
        String trimmed = value == null ? "" : value.trim();
        this.apiKey = trimmed.isEmpty() ? null : trimmed;
        this.keySource = trimmed.isEmpty() ? KeySource.NONE : source;
        ACTIVE_KEY.set(trimmed);
        if (!trimmed.isEmpty()) {
            // So that the key can never appear in a log line, an error body or a
            // pasted stack trace.
            Redactor.register(trimmed);
        }
    }

    /**
     * Teaches {@link Http} to send the key to CurseForge's own hosts and nowhere
     * else.
     *
     * <p>Both content hosts are listed. Sending the key only to
     * {@code edge.forgecdn.net} is a real bug in at least one other launcher:
     * files served from {@code mediafilez.forgecdn.net} then fail with 401 and
     * the failure looks like a dead mirror.
     */
    private static void registerHeaderRule() {
        if (!HEADER_RULE_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Http.registerHostHeaders(CurseForgeProvider::isCurseForgeHost, () -> {
            String key = ACTIVE_KEY.get();
            return key.isEmpty() ? Map.of() : Map.of(API_KEY_HEADER, key);
        });
    }

    /** True for CurseForge's API host and for every host that serves its files. */
    public static boolean isCurseForgeHost(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("api.curseforge.com")
                || lower.equals("forgecdn.net")
                || lower.endsWith(".forgecdn.net");
    }

    @Override
    public Source source() {
        return Source.CURSEFORGE;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null;
    }

    /** Where the key in use came from. Never returns the key. */
    public KeySource keySource() {
        return keySource;
    }

    /** CurseForge's numeric mod loader ids, by the platform tag name. */
    private static Integer loaderTypeId(String platformId) {
        if (platformId == null) {
            return null;
        }
        return switch (platformId) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> null;
        };
    }

    /**
     * The one id to filter a search by.
     *
     * <p>{@code modLoaderType} takes a single number, so a loader that can run
     * more than one kind of file has to pick; {@link LoaderType#searchPlatformId()}
     * is where that choice is made and explained.
     */
    private static Integer searchLoaderTypeId(LoaderType loader) {
        return loader == null ? null : loaderTypeId(loader.searchPlatformId());
    }

    /**
     * Headers for an API call.
     *
     * <p>The key is not here. It is attached by host in {@link Http}, which is
     * the only place that knows it, so that the file downloads get it too and so
     * that there is exactly one code path that can send it.
     */
    private Map<String, String> headers() {
        requireKey();
        return Map.of("Accept", "application/json");
    }

    private void requireKey() {
        if (apiKey == null) {
            throw new IllegalStateException("""
                    CurseForge needs an API key.

                    Create one in the CurseForge developer console, then paste it into \
                    launcher settings, or set the CURSEFORGE_API_KEY environment variable. \
                    Modrinth works without a key.""");
        }
    }

    @Override
    public SearchPage search(ContentKind kind, String query, String minecraftVersion,
                             LoaderType loader, ModSort sort, List<ModCategory> categories,
                             boolean onlyForProfile, int limit, int offset)
            throws IOException, InterruptedException {

        // The categories are Modrinth's, and CurseForge files its projects under
        // a different set of its own. Guessing a mapping would quietly return
        // the wrong mods; saying so is the honest answer, and the browser has a
        // line for exactly this.
        if (!categories.isEmpty()) {
            throw new UnsupportedCategoriesException();
        }

        StringBuilder url = new StringBuilder(API + "/mods/search")
                .append("?gameId=").append(GAME_MINECRAFT)
                .append("&classId=").append(kind.curseForgeClassId())
                .append("&pageSize=").append(Math.max(1, Math.min(limit, 50)))
                .append("&index=").append(Math.max(0, offset))
                .append("&sortField=").append((sort == null ? ModSort.RELEVANCE : sort).curseForgeSortField())
                .append("&sortOrder=desc");

        if (query != null && !query.isBlank()) {
            url.append("&searchFilter=").append(encode(query));
        }
        if (kind.narrowsByVersion(onlyForProfile)
                && minecraftVersion != null && !minecraftVersion.isBlank()) {
            url.append("&gameVersion=").append(encode(minecraftVersion));
        }
        Integer loaderId = kind.narrowsByLoader(onlyForProfile)
                ? searchLoaderTypeId(loader) : null;
        if (loaderId != null) {
            url.append("&modLoaderType=").append(loaderId);
        }

        Json response = get(url.toString());
        List<SearchResult> results = new ArrayList<>();
        for (Json mod : response.get("data").elements()) {
            String slug = mod.get("slug").asString("");
            results.add(new SearchResult(
                    String.valueOf(mod.get("id").asLong(0)),
                    slug,
                    mod.get("name").asString(""),
                    mod.get("summary").asString(""),
                    mod.get("authors").get(0).get("name").asString(""),
                    mod.get("downloadCount").asLong(0),
                    mod.get("logo").get("thumbnailUrl").asString(null),
                    pageUrl(mod, slug),
                    categoriesOf(mod),
                    Source.CURSEFORGE));
        }
        return new SearchPage(results,
                response.get("pagination").get("totalCount").asInt(-1),
                Math.max(0, offset));
    }

    @Override
    public Optional<ProjectCard> project(String projectId) throws IOException, InterruptedException {
        try {
            Json mod = get(API + "/mods/" + encode(projectId)).get("data");
            String name = mod.get("name").asString(null);
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            String slug = mod.get("slug").asString("");
            return Optional.of(new ProjectCard(Source.CURSEFORGE,
                    String.valueOf(mod.get("id").asLong(0)), slug, name,
                    mod.get("logo").get("thumbnailUrl").asString(null),
                    pageUrl(mod, slug)));
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Whichever of a CurseForge project's own categories this launcher has a
     * name for.
     *
     * <p>The two platforms file mods under different sets, and only a handful of
     * names coincide - magic, technology, food, storage, mobs. Those are shown;
     * the rest are left off rather than translated by guesswork into something
     * the project's author did not say.
     */
    private static List<ModCategory> categoriesOf(Json mod) {
        List<String> ids = new ArrayList<>();
        for (Json category : mod.get("categories").elements()) {
            String slug = category.get("slug").asString(null);
            if (slug != null) {
                ids.add(slug);
            }
        }
        return ModCategory.parse(ids);
    }

    /** Raised when a search asks for categories this platform cannot express. */
    public static final class UnsupportedCategoriesException extends IOException {

        UnsupportedCategoriesException() {
            super("categories are Modrinth's and do not map onto CurseForge's own");
        }
    }

    /**
     * The project's page on curseforge.com.
     *
     * <p>Taken from {@code links.websiteUrl} where the platform supplies it,
     * because a CurseForge project is not always under {@code /mc-mods}: the
     * same API returns modpacks, worlds and resource packs, each under its own
     * path. The built URL is the fallback for the case where that field is
     * absent, and it is right for the class this provider asks for.
     */
    private static String pageUrl(Json mod, String slug) {
        String published = mod.get("links").get("websiteUrl").asString(null);
        if (published != null && !published.isBlank()) {
            return published.trim();
        }
        return slug == null || slug.isBlank()
                ? null
                : "https://www.curseforge.com/minecraft/mc-mods/" + encode(slug);
    }

    @Override
    public Optional<ModFile> resolveFile(ContentKind kind, String projectId,
                                         String minecraftVersion, LoaderType loader)
            throws IOException, InterruptedException {

        String version = kind.isFilteredByVersion() ? minecraftVersion : null;

        // Every tag this loader can actually run, most specific first. On Quilt
        // that is the Quilt build when the author published one and the Fabric
        // build otherwise - which is the file Quilt Loader will load either way.
        List<String> platformIds = kind.isFilteredByLoader() && loader != null
                ? loader.platformIds() : List.of();
        if (platformIds.isEmpty()) {
            return resolveLatestFor(projectId, version, null);
        }
        for (String platformId : platformIds) {
            Optional<ModFile> found =
                    resolveLatestFor(projectId, version, loaderTypeId(platformId));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * One exact file, by its own id.
     *
     * <p>What a CurseForge modpack's manifest is written in: a list of project
     * and file ids, with no versions and no names. Nothing else can answer it -
     * "the newest file" is not what the pack pinned, and installing that instead
     * is how a pack that was tested together stops being the pack.
     */
    public Optional<ModFile> resolveExact(String projectId, String fileId)
            throws IOException, InterruptedException {
        try {
            Json file = get(API + "/mods/" + encode(projectId)
                    + "/files/" + encode(fileId)).get("data");
            if (file.get("id").asLong(0) == 0) {
                return Optional.empty();
            }
            return Optional.of(toModFile(projectId, file));
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** One query, against one of CurseForge's numeric loader ids. */
    private Optional<ModFile> resolveLatestFor(String projectId, String minecraftVersion,
                                               Integer loaderId)
            throws IOException, InterruptedException {

        StringBuilder url = new StringBuilder(API + "/mods/").append(encode(projectId)).append("/files")
                .append("?pageSize=50");
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            url.append("&gameVersion=").append(encode(minecraftVersion));
        }
        if (loaderId != null) {
            url.append("&modLoaderType=").append(loaderId);
        }

        Json response;
        try {
            response = get(url.toString());
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }

        Json chosen = null;
        for (Json file : response.get("data").elements()) {
            if (chosen == null) {
                chosen = file;
            }
            // releaseType 1 = release, 2 = beta, 3 = alpha.
            if (file.get("releaseType").asInt(3) == 1) {
                chosen = file;
                break;
            }
        }
        if (chosen == null) {
            return Optional.empty();
        }
        return Optional.of(toModFile(projectId, chosen));
    }

    /** One of CurseForge's file objects, as the launcher's own record of it. */
    private static ModFile toModFile(String projectId, Json file) {
        List<String> dependencies = new ArrayList<>();
        for (Json dependency : file.get("dependencies").elements()) {
            // relationType 3 = required dependency.
            if (dependency.get("relationType").asInt(0) == 3) {
                dependencies.add(String.valueOf(dependency.get("modId").asLong(0)));
            }
        }

        return new ModFile(
                projectId,
                null,
                String.valueOf(file.get("id").asLong(0)),
                file.get("displayName").asString(""),
                file.get("fileName").asString(""),
                file.get("downloadUrl").asString(null),
                sha1Of(file),
                file.get("fileLength").asLong(-1),
                dependencies,
                Source.CURSEFORGE);
    }

    /** CurseForge reports hashes as a list with algo 1 = SHA-1, 2 = MD5. */
    private static String sha1Of(Json file) {
        for (Json hash : file.get("hashes").elements()) {
            if (hash.get("algo").asInt(0) == 1) {
                return hash.get("value").asString(null);
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
