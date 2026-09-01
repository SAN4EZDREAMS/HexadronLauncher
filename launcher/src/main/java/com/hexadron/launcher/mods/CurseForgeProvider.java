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

/**
 * CurseForge (api.curseforge.com/v1).
 *
 * <p>Three constraints, all imposed by CurseForge rather than by this code.
 *
 * <p><b>A key is required for every request.</b> Where it comes from, in order,
 * and the first non-empty one wins:
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

    /** CurseForge's game id for Minecraft. */
    private static final int GAME_MINECRAFT = 432;
    /** CurseForge's class id for the "Mods" category. */
    private static final int CLASS_MODS = 6;

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

    /** CurseForge's numeric mod loader ids. */
    private static Integer loaderTypeId(LoaderType loader) {
        if (loader == null) {
            return null;
        }
        return switch (loader) {
            case FORGE -> 1;
            case FABRIC -> 4;
            case QUILT -> 5;
            case NEOFORGE -> 6;
            case VANILLA -> null;
        };
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
    public SearchPage search(String query, String minecraftVersion, LoaderType loader,
                             ModSort sort, List<ModCategory> categories, int limit, int offset)
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
                .append("&classId=").append(CLASS_MODS)
                .append("&pageSize=").append(Math.max(1, Math.min(limit, 50)))
                .append("&index=").append(Math.max(0, offset))
                .append("&sortField=").append((sort == null ? ModSort.RELEVANCE : sort).curseForgeSortField())
                .append("&sortOrder=desc");

        if (query != null && !query.isBlank()) {
            url.append("&searchFilter=").append(encode(query));
        }
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            url.append("&gameVersion=").append(encode(minecraftVersion));
        }
        Integer loaderId = loaderTypeId(loader);
        if (loaderId != null) {
            url.append("&modLoaderType=").append(loaderId);
        }

        Json response = Http.getJson(url.toString(), headers());
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
            Json mod = Http.getJson(API + "/mods/" + encode(projectId), headers()).get("data");
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
    public Optional<ModFile> resolveLatest(String projectId, String minecraftVersion, LoaderType loader)
            throws IOException, InterruptedException {

        StringBuilder url = new StringBuilder(API + "/mods/").append(encode(projectId)).append("/files")
                .append("?pageSize=50");
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            url.append("&gameVersion=").append(encode(minecraftVersion));
        }
        Integer loaderId = loaderTypeId(loader);
        if (loaderId != null) {
            url.append("&modLoaderType=").append(loaderId);
        }

        Json response;
        try {
            response = Http.getJson(url.toString(), headers());
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

        List<String> dependencies = new ArrayList<>();
        for (Json dependency : chosen.get("dependencies").elements()) {
            // relationType 3 = required dependency.
            if (dependency.get("relationType").asInt(0) == 3) {
                dependencies.add(String.valueOf(dependency.get("modId").asLong(0)));
            }
        }

        return Optional.of(new ModFile(
                projectId,
                null,
                String.valueOf(chosen.get("id").asLong(0)),
                chosen.get("displayName").asString(""),
                chosen.get("fileName").asString(""),
                chosen.get("downloadUrl").asString(null),
                sha1Of(chosen),
                chosen.get("fileLength").asLong(-1),
                dependencies,
                Source.CURSEFORGE));
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
