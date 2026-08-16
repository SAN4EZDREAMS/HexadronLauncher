package com.hexadron.launcher.install.loader;

/** The mod loaders the launcher knows about. */
public enum LoaderType {

    /** No loader - plain Minecraft. */
    VANILLA("vanilla", "Vanilla", false),

    FABRIC("fabric", "Fabric", true),
    QUILT("quilt", "Quilt", true),
    FORGE("forge", "Forge", true),
    NEOFORGE("neoforge", "NeoForge", true);

    private final String id;
    private final String displayName;
    private final boolean modded;

    LoaderType(String id, String displayName, boolean modded) {
        this.id = id;
        this.displayName = displayName;
        this.modded = modded;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isModded() {
        return modded;
    }

    /**
     * The loader identifier used by mod distribution platforms.
     * Modrinth and CurseForge both key mod files by this string.
     */
    public String platformId() {
        return id;
    }

    public static LoaderType fromId(String id) {
        if (id == null || id.isBlank()) {
            return VANILLA;
        }
        for (LoaderType type : values()) {
            if (type.id.equalsIgnoreCase(id.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown loader: " + id);
    }
}
