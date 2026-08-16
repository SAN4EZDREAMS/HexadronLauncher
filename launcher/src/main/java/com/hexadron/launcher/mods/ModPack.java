package com.hexadron.launcher.mods;

import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A named set of mods to install together, loaded from JSON rather than
 * hardcoded so the list can be edited, shipped or replaced without a rebuild.
 *
 * <p>Bundled packs live in {@code /packs/*.json} on the classpath; user packs
 * can be dropped into {@code <root>/packs/}.
 */
public record ModPack(String id, String name, String description, List<Entry> entries) {

    /**
     * @param provider  which platform to resolve against
     * @param projectId project id or slug on that platform
     * @param versionId a pinned file id, or null to take the newest compatible build
     * @param optional  when true, a resolution failure is reported and skipped
     *                  instead of failing the install
     */
    public record Entry(ModProvider.Source provider, String projectId, String versionId,
                        String label, boolean optional) {
    }

    public ModPack {
        entries = List.copyOf(entries);
    }

    public static ModPack parse(Json json) {
        List<Entry> entries = new ArrayList<>();
        for (Json entry : json.get("mods").elements()) {
            String projectId = entry.get("projectId").asString(null);
            if (projectId == null) {
                continue;
            }
            entries.add(new Entry(
                    ModProvider.Source.valueOf(entry.get("provider").asString("MODRINTH")),
                    projectId,
                    entry.get("versionId").asString(null),
                    entry.get("label").asString(projectId),
                    entry.get("optional").asBool(false)));
        }
        return new ModPack(
                json.get("id").asString("pack"),
                json.get("name").asString("Mod pack"),
                json.get("description").asString(""),
                entries);
    }

    /** Loads a pack bundled in the launcher jar. */
    public static ModPack fromClasspath(String resourcePath) throws IOException {
        try (InputStream in = ModPack.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("bundled pack not found on the classpath: " + resourcePath);
            }
            return parse(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    public static ModPack fromFile(Path path) throws IOException {
        return parse(Json.read(path));
    }

    /** The optimisation set this project is built around. */
    public static ModPack hexadronOptimise() throws IOException {
        return fromClasspath("/packs/hexadron-optimise.json");
    }
}
