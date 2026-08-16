package com.hexadron.launcher.profile;

import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One installable, launchable configuration - what other launchers call an
 * instance.
 *
 * <p>Each profile owns a game directory under {@code instances/<id>}, so its
 * mods, configs, resource packs and worlds are isolated. Shared, content-
 * addressed data (libraries, assets, client jars) stays in the common store.
 */
public final class Profile {

    private final String id;
    private String name;
    private String minecraftVersion;
    private LoaderType loader;
    private String loaderVersion;
    /** Resolved version id to launch, e.g. {@code fabric-loader-0.19.3-26.2}. Set at install time. */
    private String versionId;
    private int memoryMegabytes;
    private List<String> extraJvmArguments;
    private List<String> extraGameArguments;
    private String javaPath;
    private Integer windowWidth;
    private Integer windowHeight;
    private boolean demo;
    private long lastPlayed;
    private String icon;

    private Profile(String id) {
        this.id = id;
        this.name = "New profile";
        this.minecraftVersion = "";
        this.loader = LoaderType.VANILLA;
        this.loaderVersion = null;
        this.versionId = null;
        this.memoryMegabytes = defaultMemoryMegabytes();
        this.extraJvmArguments = new ArrayList<>();
        this.extraGameArguments = new ArrayList<>();
        this.javaPath = null;
        this.demo = false;
        this.lastPlayed = 0;
        this.icon = "grass";
    }

    public static Profile create(String name, String minecraftVersion, LoaderType loader) {
        Profile profile = new Profile(slug(name));
        profile.name = name;
        profile.minecraftVersion = minecraftVersion;
        profile.loader = loader;
        return profile;
    }

    /**
     * Half of physical RAM, clamped to 2-8 GB.
     *
     * <p>Modded Minecraft is far more sensitive to a too-large heap than most
     * Java software: a big heap makes each garbage collection pause longer, and
     * those pauses are what players perceive as stutter. 4 GB is comfortable for
     * a heavy modpack; handing the JVM 16 GB usually makes frame times worse,
     * not better.
     */
    public static int defaultMemoryMegabytes() {
        long physicalBytes = physicalMemoryBytes();
        if (physicalBytes <= 0) {
            return 4096;
        }
        long half = physicalBytes / 2 / (1024 * 1024);
        return (int) Math.max(2048, Math.min(8192, half));
    }

    private static long physicalMemoryBytes() {
        try {
            var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            var method = bean.getClass().getMethod("getTotalMemorySize");
            method.setAccessible(true);
            return (long) method.invoke(bean);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private static String slug(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "profile";
        }
        // A short suffix keeps two profiles with the same display name apart
        // without exposing a full UUID in the path.
        return base + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    // ---------------------------------------------------------------- accessors

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Profile name(String value) {
        this.name = value;
        return this;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public Profile minecraftVersion(String value) {
        this.minecraftVersion = value;
        // The installed version id no longer matches; force a reinstall.
        this.versionId = null;
        return this;
    }

    public LoaderType loader() {
        return loader;
    }

    public Profile loader(LoaderType value) {
        this.loader = value;
        this.versionId = null;
        return this;
    }

    public String loaderVersion() {
        return loaderVersion;
    }

    public Profile loaderVersion(String value) {
        this.loaderVersion = value;
        this.versionId = null;
        return this;
    }

    public String versionId() {
        return versionId;
    }

    public Profile versionId(String value) {
        this.versionId = value;
        return this;
    }

    /** The version id to launch: the loader's when installed, otherwise plain Minecraft. */
    public String effectiveVersionId() {
        return versionId != null ? versionId : minecraftVersion;
    }

    public int memoryMegabytes() {
        return memoryMegabytes;
    }

    public Profile memoryMegabytes(int value) {
        this.memoryMegabytes = Math.max(512, value);
        return this;
    }

    public List<String> extraJvmArguments() {
        return extraJvmArguments;
    }

    public Profile extraJvmArguments(List<String> value) {
        this.extraJvmArguments = new ArrayList<>(value);
        return this;
    }

    public List<String> extraGameArguments() {
        return extraGameArguments;
    }

    public Profile extraGameArguments(List<String> value) {
        this.extraGameArguments = new ArrayList<>(value);
        return this;
    }

    public String javaPath() {
        return javaPath;
    }

    public Profile javaPath(String value) {
        this.javaPath = (value == null || value.isBlank()) ? null : value;
        return this;
    }

    public Integer windowWidth() {
        return windowWidth;
    }

    public Integer windowHeight() {
        return windowHeight;
    }

    public Profile windowSize(Integer width, Integer height) {
        this.windowWidth = width;
        this.windowHeight = height;
        return this;
    }

    public boolean hasCustomResolution() {
        return windowWidth != null && windowHeight != null;
    }

    public boolean demo() {
        return demo;
    }

    public Profile demo(boolean value) {
        this.demo = value;
        return this;
    }

    public long lastPlayed() {
        return lastPlayed;
    }

    public Profile markPlayed() {
        this.lastPlayed = System.currentTimeMillis();
        return this;
    }

    public String icon() {
        return icon;
    }

    public Profile icon(String value) {
        this.icon = value;
        return this;
    }

    // ---------------------------------------------------------------- persistence

    public Json toJson() {
        Json jvm = Json.array();
        extraJvmArguments.forEach(jvm::add);
        Json game = Json.array();
        extraGameArguments.forEach(game::add);

        Json json = Json.object()
                .put("id", id)
                .put("name", name)
                .put("minecraftVersion", minecraftVersion)
                .put("loader", loader.id())
                .put("memoryMegabytes", memoryMegabytes)
                .put("extraJvmArguments", jvm)
                .put("extraGameArguments", game)
                .put("demo", demo)
                .put("lastPlayed", lastPlayed)
                .put("icon", icon);

        if (loaderVersion != null) {
            json.put("loaderVersion", loaderVersion);
        }
        if (versionId != null) {
            json.put("versionId", versionId);
        }
        if (javaPath != null) {
            json.put("javaPath", javaPath);
        }
        if (windowWidth != null && windowHeight != null) {
            json.put("windowWidth", windowWidth);
            json.put("windowHeight", windowHeight);
        }
        return json;
    }

    public static Profile fromJson(Json json) {
        String id = json.get("id").asString(null);
        if (id == null) {
            throw new IllegalArgumentException("profile entry has no id");
        }
        Profile profile = new Profile(id);
        profile.name = json.get("name").asString(id);
        profile.minecraftVersion = json.get("minecraftVersion").asString("");
        profile.loader = LoaderType.fromId(json.get("loader").asString("vanilla"));
        profile.loaderVersion = json.get("loaderVersion").asString(null);
        profile.versionId = json.get("versionId").asString(null);
        profile.memoryMegabytes = json.get("memoryMegabytes").asInt(defaultMemoryMegabytes());
        profile.javaPath = json.get("javaPath").asString(null);
        profile.demo = json.get("demo").asBool(false);
        profile.lastPlayed = json.get("lastPlayed").asLong(0);
        profile.icon = json.get("icon").asString("grass");

        int width = json.get("windowWidth").asInt(-1);
        int height = json.get("windowHeight").asInt(-1);
        if (width > 0 && height > 0) {
            profile.windowWidth = width;
            profile.windowHeight = height;
        }

        for (Json argument : json.get("extraJvmArguments").elements()) {
            String value = argument.asString(null);
            if (value != null) {
                profile.extraJvmArguments.add(value);
            }
        }
        for (Json argument : json.get("extraGameArguments").elements()) {
            String value = argument.asString(null);
            if (value != null) {
                profile.extraGameArguments.add(value);
            }
        }
        return profile;
    }

    @Override
    public String toString() {
        return name + " [" + minecraftVersion
                + (loader == LoaderType.VANILLA ? "" : " / " + loader.displayName()) + "]";
    }
}
