package com.hexadron.launcher.mods;

import com.hexadron.launcher.json.Json;

/**
 * One mod file recorded in a profile's lock file.
 *
 * <p>{@code title} is stored rather than derived: {@link ModFile#displayName()}
 * is the name of a *version* ("0.9.1+mc26.2"), and the file name is whatever the
 * author chose to call the jar. Neither is the name the user picked the mod by,
 * and asking the API again just to label a list would make the installed view
 * depend on the network.
 *
 * <p>{@code iconUrl} and {@code pageUrl} are stored for the same reason and were
 * added later, when the installed list stopped being a list of names. The search
 * result the user clicked already carried both; throwing them away meant that
 * showing a logo, or opening the mod's page, could only be done by asking the
 * platform again - which is a list of grey squares and dead buttons on a
 * launcher started without a connection. Entries written before this are simply
 * missing them, and a row without a logo is drawn without one.
 *
 * @param title   the project name as the user saw it when installing
 * @param file    the downloaded file
 * @param origin  who put it there
 * @param packId  the pack that owns it, when {@code origin} is {@link ModOrigin#PACK}
 * @param iconUrl the project's logo on the platform, or null
 * @param pageUrl the project's page on the platform, or null
 */
public record InstalledMod(String title, ModFile file, ModOrigin origin, String packId,
                           String iconUrl, String pageUrl) {

    /** An entry with no artwork or link recorded, as version 2 of the lock file wrote them. */
    public InstalledMod(String title, ModFile file, ModOrigin origin, String packId) {
        this(title, file, origin, packId, null, null);
    }

    /** An entry labelled from what the platform published about the project. */
    public static InstalledMod of(ModProvider.ProjectCard card, ModFile file,
                                  ModOrigin origin, String packId) {
        return new InstalledMod(card.title(), file, origin, packId,
                card.iconUrl(), card.pageUrl());
    }

    /** The lock-file key: one entry per project per provider. */
    public static String keyOf(ModProvider.Source source, String projectId) {
        return source.name() + ":" + projectId;
    }

    public String key() {
        return keyOf(file.source(), file.projectId());
    }

    public boolean belongsTo(String pack) {
        return origin == ModOrigin.PACK && packId != null && packId.equals(pack);
    }

    public Json toJson() {
        Json json = file.toJson()
                .put("title", title)
                .put("origin", origin.name());
        if (packId != null) {
            json.put("packId", packId);
        }
        if (iconUrl != null) {
            json.put("iconUrl", iconUrl);
        }
        if (pageUrl != null) {
            json.put("pageUrl", pageUrl);
        }
        return json;
    }

    /**
     * Reads one entry.
     *
     * @param legacy true for a version-1 lock file, whose only writer was the
     *               pack installer - so every entry in one came from the pack,
     *               and must keep being protected as such after the upgrade
     */
    public static InstalledMod fromJson(Json json, boolean legacy, String legacyPackId) {
        ModFile file = ModFile.fromJson(json);
        ModOrigin origin = legacy ? ModOrigin.PACK : ModOrigin.parse(json.get("origin").asString(null));
        // EXTERNAL describes a file the launcher did not put there, so it is not
        // something a lock-file entry can be. Reading one back means the file was
        // edited or written by a newer build; MANUAL is the safe reading, because
        // it keeps the entry removable without making it pack-owned.
        if (origin == ModOrigin.EXTERNAL) {
            origin = ModOrigin.MANUAL;
        }
        String packId = json.get("packId").asString(legacy ? legacyPackId : null);
        String title = json.get("title").asString(null);
        if (title == null || title.isBlank()) {
            title = file.projectSlug() != null ? file.projectSlug() : file.fileName();
        }
        return new InstalledMod(title, file, origin,
                origin == ModOrigin.PACK ? packId : null,
                json.get("iconUrl").asString(null),
                json.get("pageUrl").asString(null));
    }
}
