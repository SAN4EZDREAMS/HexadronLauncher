package com.hexadron.launcher.mods;

/**
 * How a mod search is ordered.
 *
 * <p>The two platforms name their orderings differently, so the mapping lives
 * with each provider rather than in the UI. Where a platform has no equivalent
 * the closest one is used - an approximate order is more useful than hiding the
 * option on one source and not the other.
 */
public enum ModSort {

    /** Best text match. Meaningless for an empty query, where it degrades to popularity. */
    RELEVANCE("mods.sort.relevance"),
    /** Most downloaded overall. */
    DOWNLOADS("mods.sort.downloads"),
    /** Most followed / most popular right now. */
    POPULAR("mods.sort.popular"),
    /** Most recently updated. */
    UPDATED("mods.sort.updated"),
    /** Most recently published. */
    NEWEST("mods.sort.newest");

    private final String key;

    ModSort(String key) {
        this.key = key;
    }

    /** Translation key for the picker. */
    public String key() {
        return key;
    }

    /** Modrinth's {@code index} parameter. These names are the API's own. */
    public String modrinthIndex() {
        return switch (this) {
            case RELEVANCE -> "relevance";
            case DOWNLOADS -> "downloads";
            case POPULAR -> "follows";
            case UPDATED -> "updated";
            case NEWEST -> "newest";
        };
    }

    /**
     * CurseForge's numeric {@code sortField}.
     *
     * <p>2 is popularity, 3 last-updated, 6 total downloads. CurseForge has no
     * relevance ordering, so a text search falls back to popularity - which is
     * also what its own site shows by default.
     */
    public int curseForgeSortField() {
        return switch (this) {
            case RELEVANCE, POPULAR -> 2;
            case DOWNLOADS -> 6;
            case UPDATED, NEWEST -> 3;
        };
    }
}
