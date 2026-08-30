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
     * @param onlyWith  a condition on another mod in the same set, or null for
     *                  an entry that is always installed
     */
    public record Entry(ModProvider.Source provider, String projectId, String versionId,
                        String label, boolean optional, Condition onlyWith) {

        public boolean isConditional() {
            return onlyWith != null;
        }
    }

    /**
     * "Install this only alongside an older build of that."
     *
     * <h2>What it is for</h2>
     *
     * <p>A compatibility mod that stops being a fix and becomes a fault. Indium
     * is the case in hand: it gives Sodium the Fabric Rendering API, Sodium 0.6
     * has that built in, and Indium is incompatible with 0.6 and above. So the
     * same set needs it on Minecraft 1.20.1, where Sodium is 0.5.13, and must
     * not have it on 1.21.1, where Sodium is 0.8.13 - even though Indium
     * publishes builds for both.
     *
     * <p>Expressed against the companion's <em>version</em> rather than the
     * Minecraft version because Sodium is backported: which Sodium a Minecraft
     * version gets is not something a pack file can know in advance, and a rule
     * written the other way would install a mod that breaks the game the first
     * time a backport appeared.
     *
     * @param provider     platform the companion is resolved on
     * @param projectId    the companion, e.g. Sodium
     * @param versionBelow install this entry only when the companion resolves
     *                     to a version older than this
     */
    public record Condition(ModProvider.Source provider, String projectId, String versionBelow) {
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
            ModProvider.Source provider =
                    ModProvider.Source.valueOf(entry.get("provider").asString("MODRINTH"));

            Json condition = entry.get("onlyWith");
            Condition onlyWith = null;
            String companion = condition.get("projectId").asString(null);
            String below = condition.get("versionBelow").asString(null);
            if (companion != null && below != null) {
                onlyWith = new Condition(
                        ModProvider.Source.valueOf(
                                condition.get("provider").asString(provider.name())),
                        companion, below);
            }

            entries.add(new Entry(
                    provider,
                    projectId,
                    entry.get("versionId").asString(null),
                    entry.get("label").asString(projectId),
                    entry.get("optional").asBool(false),
                    onlyWith));
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
