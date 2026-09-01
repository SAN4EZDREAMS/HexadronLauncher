package com.hexadron.launcher.mods;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.util.Hashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the launcher has been told about the jars it did not install.
 *
 * <h2>The problem</h2>
 *
 * <p>A jar a player copied in describes itself well enough to be named and
 * summarised - {@link LocalModInfo} reads that straight out of the file - but it
 * carries no logo the user would recognise from the website and no link back to
 * where it came from. Both exist; they are on Modrinth, and Modrinth can be
 * asked "which version has this SHA-1", which is an exact answer or none.
 *
 * <h2>Why it is a button and not a refresh</h2>
 *
 * <p>Asking that question means sending a digest of every file in the player's
 * mods folder to a third party. That is a reasonable thing to do when the player
 * asks for it and an unreasonable thing to do because they opened a window, so
 * the lookup is one explicit action and its answers are kept here rather than
 * asked again. A launcher started with no connection shows exactly what it
 * showed before, from this file.
 *
 * <h2>What it is keyed by, and why not the hash</h2>
 *
 * <p>By file name, with the size recorded beside it. Keying by digest would be
 * tidier and would survive a rename, and it would also mean hashing every jar in
 * the folder every time the list is drawn - hundreds of megabytes of reading to
 * populate a list. The name and size are free, they come from the directory
 * listing that has to happen anyway, and the cost of being wrong is that a
 * renamed or replaced file loses its logo until the button is pressed again.
 *
 * <p>Entries that were asked about and not found are recorded too. Without that
 * the button re-asks about the same unknown jars every time it is pressed, and
 * a folder of mods from a site Modrinth does not mirror never stops costing a
 * request.
 */
public final class ExternalModIndex {

    public static final String INDEX_FILE = ".hexadron-external.json";
    private static final int FORMAT_VERSION = 1;

    /** Digests asked about in one request. Modrinth's own limit is well above this. */
    private static final int BATCH = 100;

    private final Path modsDir;
    private final Map<String, Entry> byFileName = new LinkedHashMap<>();

    /**
     * @param size  the file's size when it was identified; a different size means
     *              a different file wearing the same name
     * @param card  what Modrinth said it is, or null for "asked, not known"
     */
    private record Entry(long size, ModProvider.ProjectCard card) {
    }

    private ExternalModIndex(Path modsDir) {
        this.modsDir = modsDir;
    }

    /** Reads the index. A missing or unreadable one is simply empty. */
    public static ExternalModIndex read(Path modsDir) {
        ExternalModIndex index = new ExternalModIndex(modsDir);
        Path file = modsDir.resolve(INDEX_FILE);
        if (!Files.isRegularFile(file)) {
            return index;
        }
        try {
            Json root = Json.read(file);
            root.get("files").fields().forEach((fileName, value) -> {
                long size = value.get("size").asLong(-1);
                String projectId = value.get("projectId").asString(null);
                if (projectId == null) {
                    index.byFileName.put(fileName, new Entry(size, null));
                    return;
                }
                List<String> categoryIds = new ArrayList<>();
                for (Json category : value.get("categories").elements()) {
                    String id = category.asString(null);
                    if (id != null) {
                        categoryIds.add(id);
                    }
                }
                index.byFileName.put(fileName, new Entry(size, new ModProvider.ProjectCard(
                        ModProvider.Source.valueOf(value.get("source").asString("MODRINTH")),
                        projectId,
                        value.get("slug").asString(null),
                        value.get("title").asString(""),
                        value.get("iconUrl").asString(null),
                        value.get("pageUrl").asString(null),
                        ModCategory.parse(categoryIds))));
            });
        } catch (IOException | RuntimeException ignored) {
            // An unreadable index costs the user a logo, not a mod.
        }
        return index;
    }

    /**
     * What is known about a file, if anything.
     *
     * @param size the file's current size, checked against what was recorded
     */
    public Optional<ModProvider.ProjectCard> get(String fileName, long size) {
        Entry entry = byFileName.get(fileName);
        return entry == null || entry.size() != size || entry.card() == null
                ? Optional.empty()
                : Optional.of(entry.card());
    }

    /** True when this exact file has already been asked about, found or not. */
    public boolean isChecked(String fileName, long size) {
        Entry entry = byFileName.get(fileName);
        return entry != null && entry.size() == size;
    }

    /** True when nothing in the folder is still worth asking about. */
    public static boolean isComplete(List<ModEntry> mods, ExternalModIndex index) {
        return unidentified(mods, index).isEmpty();
    }

    /** The rows this index has nothing to say about yet. */
    public static List<ModEntry> unidentified(List<ModEntry> mods, ExternalModIndex index) {
        List<ModEntry> pending = new ArrayList<>();
        for (ModEntry mod : mods) {
            if (mod.isManaged()) {
                continue;
            }
            long size = sizeOf(mod.path());
            if (size >= 0 && !index.isChecked(mod.fileName(), size)) {
                pending.add(mod);
            }
        }
        return List.copyOf(pending);
    }

    public void write() throws IOException {
        Files.createDirectories(modsDir);
        Json files = Json.object();
        byFileName.forEach((fileName, entry) -> {
            Json value = Json.object().put("size", entry.size());
            ModProvider.ProjectCard card = entry.card();
            if (card != null) {
                value.put("source", card.source().name()).put("projectId", card.projectId());
                putIfPresent(value, "slug", card.slug());
                putIfPresent(value, "title", card.title());
                putIfPresent(value, "iconUrl", card.iconUrl());
                putIfPresent(value, "pageUrl", card.pageUrl());
                if (!card.categories().isEmpty()) {
                    Json list = Json.array();
                    card.categories().forEach(category -> list.add(category.id()));
                    value.put("categories", list);
                }
            }
            files.put(fileName, value);
        });
        Json.object()
                .put("version", FORMAT_VERSION)
                .put("files", files)
                .write(modsDir.resolve(INDEX_FILE));
    }

    private static void putIfPresent(Json json, String key, String value) {
        if (value != null && !value.isBlank()) {
            json.put(key, value);
        }
    }

    // ---------------------------------------------------------------- lookup

    /**
     * Asks Modrinth to identify the jars the launcher did not install.
     *
     * <p>Two requests for the whole folder, not two per file: the digests go up
     * in one batch and the projects they map to come back in another. Files
     * already asked about are not asked about again, so pressing the button a
     * second time after adding one mod costs one round of requests about one
     * mod.
     *
     * @return how many files were newly recognised
     */
    public static int identify(Path modsDir, ModrinthProvider modrinth, Progress progress)
            throws IOException, InterruptedException {

        List<ModEntry> mods = ModScan.scan(modsDir);
        ExternalModIndex index = read(modsDir);
        List<ModEntry> pending = unidentified(mods, index);
        if (pending.isEmpty()) {
            return 0;
        }

        progress.stage("Reading " + pending.size() + " file(s)");
        Map<String, ModEntry> byHash = new LinkedHashMap<>();
        int hashed = 0;
        for (ModEntry mod : pending) {
            try {
                byHash.put(Hashes.sha1(mod.path()).toLowerCase(java.util.Locale.ROOT), mod);
            } catch (IOException e) {
                // A file that cannot be read is one the user will find out about
                // from the game. It is not a reason to abandon the other eighty.
                progress.log("Could not read %s", mod.fileName());
            }
            progress.items(++hashed, pending.size());
        }

        progress.stage("Asking Modrinth about " + byHash.size() + " file(s)");
        Map<String, String> projectByHash = new LinkedHashMap<>();
        List<String> hashes = new ArrayList<>(byHash.keySet());
        for (int from = 0; from < hashes.size(); from += BATCH) {
            projectByHash.putAll(modrinth.projectsByHash(
                    hashes.subList(from, Math.min(from + BATCH, hashes.size()))));
        }

        Set<String> projectIds = new LinkedHashSet<>(projectByHash.values());
        Map<String, ModProvider.ProjectCard> cards = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(projectIds);
        for (int from = 0; from < ids.size(); from += BATCH) {
            for (ModProvider.ProjectCard card
                    : modrinth.projects(ids.subList(from, Math.min(from + BATCH, ids.size())))) {
                cards.put(card.projectId(), card);
            }
        }

        int recognised = 0;
        for (Map.Entry<String, ModEntry> found : byHash.entrySet()) {
            ModEntry mod = found.getValue();
            long size = sizeOf(mod.path());
            ModProvider.ProjectCard card = cards.get(projectByHash.get(found.getKey()));
            // A null card is recorded too: "asked, and Modrinth does not have it"
            // is an answer, and re-asking it every time is not.
            index.byFileName.put(mod.fileName(), new Entry(size, card));
            if (card != null) {
                recognised++;
                progress.log("%s is %s", mod.fileName(), card.title());
            }
        }
        index.write();
        return recognised;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }
}
