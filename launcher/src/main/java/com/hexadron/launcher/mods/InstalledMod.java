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
 * @param title  the project name as the user saw it when installing
 * @param file   the downloaded file
 * @param origin who put it there
 * @param packId the pack that owns it, when {@code origin} is {@link ModOrigin#PACK}
 */
public record InstalledMod(String title, ModFile file, ModOrigin origin, String packId) {

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
        String packId = json.get("packId").asString(legacy ? legacyPackId : null);
        String title = json.get("title").asString(null);
        if (title == null || title.isBlank()) {
            title = file.projectSlug() != null ? file.projectSlug() : file.fileName();
        }
        return new InstalledMod(title, file, origin, origin == ModOrigin.PACK ? packId : null);
    }
}
