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

package com.hexadron.launcher.about;

import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who and what this launcher is built on.
 *
 * <h2>A resource, not a list in code</h2>
 *
 * <p>An attribution list is the part of a program that goes out of date first:
 * a project moves, a licence changes, something new is used. Keeping it in a
 * file beside the mod pack - which is a resource for the same reason - means a
 * correction is an edit rather than a release.
 *
 * <h2>Only https</h2>
 *
 * <p>Every link here is opened in the user's browser at a click, and the file
 * is on disk in a folder the user can write to. A {@code file:} or
 * {@code javascript:} entry would turn a credits screen into a way to run
 * something, so anything that is not {@code https://} is dropped when the file
 * is read rather than checked at the moment of clicking - one gate, at the
 * boundary, rather than a check every call site has to remember.
 */
public record Credits(Author author, String repository, List<Group> groups) {

    /** A person, and where to find them. */
    public record Author(String name, List<Link> links) {
    }

    /** A named destination. */
    public record Link(String name, String url) {
    }

    /**
     * One credited project.
     *
     * @param role    what this launcher took from it, or null
     * @param licence the terms it is used under, or null when they are not
     *                something this launcher has to honour
     */
    public record Entry(String name, String url, String role, String licence) {
    }

    /** @param heading an i18n key, so the headings follow the language */
    public record Group(String heading, List<Entry> entries) {
    }

    public Credits {
        groups = List.copyOf(groups);
    }

    /** Reads the list shipped with the launcher. */
    public static Credits load() throws IOException {
        try (InputStream in = Credits.class.getResourceAsStream("/about/credits.json")) {
            if (in == null) {
                throw new IOException("the credits list is missing from this build");
            }
            return parse(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    public static Credits parse(Json json) {
        Json authorJson = json.get("author");
        List<Link> links = new ArrayList<>();
        for (Json link : authorJson.get("links").elements()) {
            String url = safe(link.get("url").asString(null));
            if (url != null) {
                links.add(new Link(link.get("name").asString(url), url));
            }
        }

        List<Group> groups = new ArrayList<>();
        for (Json group : json.get("groups").elements()) {
            List<Entry> entries = new ArrayList<>();
            for (Json entry : group.get("entries").elements()) {
                String url = safe(entry.get("url").asString(null));
                String name = entry.get("name").asString(null);
                if (url == null || name == null) {
                    continue;
                }
                entries.add(new Entry(name, url,
                        entry.get("role").asString(null),
                        entry.get("licence").asString(null)));
            }
            if (!entries.isEmpty()) {
                groups.add(new Group(group.get("heading").asString(""), List.copyOf(entries)));
            }
        }

        return new Credits(
                new Author(authorJson.get("name").asString("?"), List.copyOf(links)),
                safe(json.get("repository").asString(null)),
                groups);
    }

    /**
     * A link this launcher is willing to open, or null.
     *
     * <p>The single gate. Nothing downstream re-checks, because nothing
     * downstream can hold a link that did not come through here.
     */
    public static String safe(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        return trimmed.toLowerCase(Locale.ROOT).startsWith("https://") ? trimmed : null;
    }

    /** Every entry, flattened. For the self-check. */
    public List<Entry> allEntries() {
        List<Entry> all = new ArrayList<>();
        groups.forEach(group -> all.addAll(group.entries()));
        return all;
    }
}
