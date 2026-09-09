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

import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The little picture beside each category name.
 *
 * <h2>Fetched rather than drawn</h2>
 *
 * <p>Twenty-five icons is more than this project should be drawing by hand, and
 * a hand-drawn set would drift from the one the player already knows from the
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

    /**
     * Version 2 records which categories were asked about, not only the ones
     * that came back. See {@link #asked}.
     */
    private static final int FORMAT_VERSION = 2;

    /** How long a kept set is used before the platform is asked again. */
    private static final long REFRESH_AFTER = java.time.Duration.ofDays(30).toMillis();

    private final Path file;
    private final Map<String, String> drawings = new LinkedHashMap<>();

    /**
     * The categories the last fetch asked the platform about.
     *
     * <p>This is the difference between a cache and a cache that notices. The
     * kept file used to hold the drawings alone, and {@link #isStale()} asked
     * one question of it - is it empty, or a month old. Neither is true of a
     * file written last week, so a category added to {@link ModCategory} since
     * then had no drawing and no way of ever getting one: the launcher was
     * holding a complete-looking answer to a question that had changed. That is
     * exactly what the six modpack-only categories looked like - names in the
     * filter with a blank where the picture goes, for up to a month.
     *
     * <p>Asked rather than answered, because the two are not the same list. A
     * category the platform publishes no drawing for is one this file cannot
     * hold, and treating its absence as "not fetched yet" would re-ask on every
     * opening of the browser for ever. Recording what was asked separates "we
     * have not looked" from "we looked, and there is nothing".
     */
    private final Set<String> asked = new LinkedHashSet<>();

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
            for (Json id : root.get("asked").elements()) {
                String value = id.asString(null);
                if (value != null && !value.isBlank()) {
                    art.asked.add(value);
                }
            }
            // A version-1 file recorded no such list. The drawings it does hold
            // are the honest floor - they were certainly asked about - so an
            // enum that has not grown since is not re-fetched, and one that has
            // is, which is the whole point.
            if (art.asked.isEmpty()) {
                art.asked.addAll(art.drawings.keySet());
            }
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

    /**
     * True when the platform should be asked.
     *
     * <p>Three reasons, and the middle one is the one that was missing: nothing
     * has ever been fetched; a category this launcher now offers has never been
     * asked about; or what is here has been here a month.
     */
    public boolean isStale() {
        return drawings.isEmpty()
                || !asked.containsAll(known())
                || System.currentTimeMillis() - fetched > REFRESH_AFTER;
    }

    /** True when some category this launcher offers has no drawing. */
    public boolean isIncomplete() {
        for (ModCategory category : ModCategory.values()) {
            if (!drawings.containsKey(category.id())) {
                return true;
            }
        }
        return false;
    }

    /** Every category identifier this launcher has a name for. */
    private static Set<String> known() {
        Set<String> ids = new LinkedHashSet<>();
        for (ModCategory category : ModCategory.values()) {
            ids.add(category.id());
        }
        return ids;
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
            // An answer with nothing in it is a request that failed in a way
            // that returned 200. The kept set is left alone, and stays stale.
            return false;
        }
        boolean changed = !wanted.equals(drawings);
        drawings.clear();
        drawings.putAll(wanted);
        // Every category was asked about, including the ones the platform
        // published no drawing for. Written whether or not anything changed:
        // this is the record that stops the next opening asking again.
        asked.clear();
        asked.addAll(known());
        fetched = System.currentTimeMillis();
        write();
        return changed;
    }

    private void write() throws IOException {
        Files.createDirectories(file.getParent());
        Json icons = Json.object();
        drawings.forEach(icons::put);
        Json list = Json.array();
        asked.forEach(list::add);
        Json.object()
                .put("version", FORMAT_VERSION)
                .put("fetched", fetched)
                .put("asked", list)
                .put("icons", icons)
                .write(file);
    }
}
