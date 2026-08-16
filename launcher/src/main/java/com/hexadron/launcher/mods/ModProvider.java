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
     * One page of results, plus how many there are in total.
     *
     * <p>{@code total} is what makes a page honest. Without it the browser can
     * only ever say "here are 40", which reads as "there are 40" - and looks
     * identical for a Minecraft version with four thousand mods and one with
     * fifty.
     *
     * @param total    matches on the platform, or -1 when it does not report one
     * @param offset   index this page started at
     */
    record SearchPage(List<SearchResult> results, int total, int offset) {

        public SearchPage {
            results = List.copyOf(results);
        }

        public static SearchPage empty() {
            return new SearchPage(List.of(), 0, 0);
        }

        /** True when the platform says there is more after this page. */
        public boolean hasMore() {
            return total < 0 ? !results.isEmpty() : offset + results.size() < total;
        }
    }

    /**
     * Searches for mods compatible with a Minecraft version and loader.
     *
     * @param limit  page size
     * @param offset how many matches to skip, for paging
     */
    SearchPage search(String query, String minecraftVersion, LoaderType loader,
                      ModSort sort, int limit, int offset) throws IOException, InterruptedException;

    /** First page, relevance-ordered, for callers that do not page or sort. */
    default List<SearchResult> search(String query, String minecraftVersion, LoaderType loader, int limit)
            throws IOException, InterruptedException {
        return search(query, minecraftVersion, loader, ModSort.RELEVANCE, limit, 0).results();
    }

    /**
     * The newest file of {@code projectId} compatible with the given version and
     * loader, or empty when the project has none.
     */
    Optional<ModFile> resolveLatest(String projectId, String minecraftVersion, LoaderType loader)
            throws IOException, InterruptedException;
}
