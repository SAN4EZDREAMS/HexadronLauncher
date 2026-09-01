package com.hexadron.launcher.mods;

import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The little picture beside each category name.
 *
 * <h2>Fetched rather than drawn</h2>
 *
 * <p>Nineteen icons is more than this project should be drawing by hand, and a
 * hand-drawn set would drift from the one the player already knows from the
 * website. Modrinth publishes its own alongside the category list, as line
 * drawings on the same twenty-four unit grid the rest of this launcher's icons
 * use, so they are taken from there and drawn as paths - which means they take
 * the theme's colour like a piece of text and are sharp at any scale.
 *
 * <h2>Asked for once, then kept</h2>
 *
 * <p>They change about as often as the category list does, which is to say
 * almost never, so this is one request a month at most and the answer lives in
 * the data folder. That is what lets a launcher started with no connection draw
 * its own filter: the names come from {@link ModCategory}, the pictures from
 * here, and a category whose picture has not arrived yet is a category with a
 * name.
 */
public final class CategoryArt {

    public static final String FILE = "mod-categories.json";
    private static final int FORMAT_VERSION = 1;

    /** How long a kept set is used before the platform is asked again. */
    private static final long REFRESH_AFTER = java.time.Duration.ofDays(30).toMillis();

    private final Path file;
    private final Map<String, String> drawings = new LinkedHashMap<>();
    private long fetched;

    private CategoryArt(Path file) {
        this.file = file;
    }

    /** Reads what was kept. A missing or unreadable file yields an empty set. */
    public static CategoryArt read(Path cacheDir) {
        CategoryArt art = new CategoryArt(cacheDir.resolve(FILE));
        if (!Files.isRegularFile(art.file)) {
            return art;
        }
        try {
            Json root = Json.read(art.file);
            art.fetched = root.get("fetched").asLong(0);
            root.get("icons").fields().forEach((id, value) -> {
                String svg = value.asString(null);
                if (svg != null && !svg.isBlank()) {
                    art.drawings.put(id, svg);
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // A category with no picture is a category with a name.
        }
        return art;
    }

    /** The drawing for a category, as the markup it was published as. */
    public Optional<String> of(ModCategory category) {
        return Optional.ofNullable(drawings.get(category.id()));
    }

    public boolean isEmpty() {
        return drawings.isEmpty();
    }

    /** True when this has never been fetched, or was fetched long enough ago. */
    public boolean isStale() {
        return drawings.isEmpty() || System.currentTimeMillis() - fetched > REFRESH_AFTER;
    }

    /**
     * Asks the platform for the drawings and keeps them.
     *
     * <p>Only the ones this launcher has a name for: the same endpoint carries
     * the categories of resource packs, plugins and servers, and a set that
     * grows with all of them is a file that grows for no reason.
     *
     * @return true when anything changed and was written
     */
    public boolean refresh(ModrinthProvider modrinth) throws IOException, InterruptedException {
        Map<String, String> published = modrinth.categoryArt();
        Map<String, String> wanted = new LinkedHashMap<>();
        for (ModCategory category : ModCategory.values()) {
            String svg = published.get(category.id());
            if (svg != null && !svg.isBlank()) {
                wanted.put(category.id(), svg);
            }
        }
        if (wanted.isEmpty()) {
            return false;
        }
        boolean changed = !wanted.equals(drawings);
        drawings.clear();
        drawings.putAll(wanted);
        fetched = System.currentTimeMillis();
        write();
        return changed;
    }

    private void write() throws IOException {
        Files.createDirectories(file.getParent());
        Json icons = Json.object();
        drawings.forEach(icons::put);
        Json.object()
                .put("version", FORMAT_VERSION)
                .put("fetched", fetched)
                .put("icons", icons)
                .write(file);
    }
}
