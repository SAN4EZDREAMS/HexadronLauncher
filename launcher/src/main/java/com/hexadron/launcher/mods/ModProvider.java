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
                        String author, long downloads, String iconUrl, Source source) {
    }

    Source source();

    /** True when this provider is configured and usable (CurseForge needs an API key). */
    boolean isAvailable();

    /**
     * Searches for mods compatible with a Minecraft version and loader.
     *
     * @param limit maximum results
     */
    List<SearchResult> search(String query, String minecraftVersion, LoaderType loader,
                              ModSort sort, int limit) throws IOException, InterruptedException;

    /** Relevance-ordered search, for callers that do not offer a sort. */
    default List<SearchResult> search(String query, String minecraftVersion, LoaderType loader, int limit)
            throws IOException, InterruptedException {
        return search(query, minecraftVersion, loader, ModSort.RELEVANCE, limit);
    }

    /**
     * The newest file of {@code projectId} compatible with the given version and
     * loader, or empty when the project has none.
     */
    Optional<ModFile> resolveLatest(String projectId, String minecraftVersion, LoaderType loader)
            throws IOException, InterruptedException;
}
