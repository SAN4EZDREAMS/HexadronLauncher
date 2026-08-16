package com.hexadron.launcher.mods;

import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CurseForge (api.curseforge.com/v1).
 *
 * <p>Two constraints, both imposed by CurseForge rather than by this code:
 * <ul>
 *   <li>An API key is mandatory for every request. Register at the CurseForge
 *       developer console; the launcher reads it from the {@code CURSEFORGE_API_KEY}
 *       environment variable or from launcher settings.</li>
 *   <li>Authors can opt out of third-party distribution. For those projects the
 *       API returns a file with a null {@code downloadUrl}. That is a licence
 *       decision, not an error, so this provider surfaces the file with no URL
 *       and the installer reports it as "must be downloaded manually" rather
 *       than trying to work around it.</li>
 * </ul>
 */
public final class CurseForgeProvider implements ModProvider {

    private static final String API = "https://api.curseforge.com/v1";

    /** CurseForge's game id for Minecraft. */
    private static final int GAME_MINECRAFT = 432;
    /** CurseForge's class id for the "Mods" category. */
    private static final int CLASS_MODS = 6;

    private final String apiKey;

    public CurseForgeProvider(String apiKey) {
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey.trim();
    }

    /** Reads the key from launcher settings, falling back to the environment. */
    public static CurseForgeProvider fromEnvironment(String configuredKey) {
        String key = (configuredKey == null || configuredKey.isBlank())
                ? System.getenv("CURSEFORGE_API_KEY")
                : configuredKey;
        return new CurseForgeProvider(key);
    }

    @Override
    public Source source() {
        return Source.CURSEFORGE;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null;
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

    private Map<String, String> headers() {
        requireKey();
        return Map.of("x-api-key", apiKey, "Accept", "application/json");
    }

    private void requireKey() {
        if (apiKey == null) {
            throw new IllegalStateException("""
                    CurseForge needs an API key.

                    Create one in the CurseForge developer console, then set it in launcher \
                    settings or in the CURSEFORGE_API_KEY environment variable. Modrinth works \
                    without a key.""");
        }
    }

    @Override
    public SearchPage search(String query, String minecraftVersion, LoaderType loader,
                             ModSort sort, int limit, int offset)
            throws IOException, InterruptedException {

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
            results.add(new SearchResult(
                    String.valueOf(mod.get("id").asLong(0)),
                    mod.get("slug").asString(""),
                    mod.get("name").asString(""),
                    mod.get("summary").asString(""),
                    mod.get("authors").get(0).get("name").asString(""),
                    mod.get("downloadCount").asLong(0),
                    mod.get("logo").get("thumbnailUrl").asString(null),
                    Source.CURSEFORGE));
        }
        return new SearchPage(results,
                response.get("pagination").get("totalCount").asInt(-1),
                Math.max(0, offset));
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
