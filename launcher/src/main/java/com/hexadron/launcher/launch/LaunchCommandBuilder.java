package com.hexadron.launcher.launch;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.meta.Argument;
import com.hexadron.launcher.meta.Artifact;
import com.hexadron.launcher.meta.Library;
import com.hexadron.launcher.meta.Rule;
import com.hexadron.launcher.meta.VersionJson;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.util.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a resolved version, a profile and an account into the exact command
 * line to execute.
 *
 * <p>All the version-specific knowledge lives in the metadata, so this class
 * only has to: evaluate rules, assemble the classpath, and substitute the
 * {@code ${...}} placeholders the metadata uses.
 */
public final class LaunchCommandBuilder {

    public static final String LAUNCHER_NAME = "HexadronLauncher";
    public static final String LAUNCHER_VERSION = "0.2.0";

    private final GameDirs dirs;

    public LaunchCommandBuilder(GameDirs dirs) {
        this.dirs = dirs;
    }

    /** The command plus the context needed to run and debug it. */
    public record LaunchCommand(List<String> command, Path workingDirectory,
                                Path javaExecutable, List<Path> classpath, String mainClass) {

        /** Command with the access token masked, safe to write to a log. */
        public String toLoggableString(String accessToken) {
            String joined = String.join(" ", command);
            if (accessToken != null && !accessToken.isBlank() && !accessToken.equals("0")) {
                joined = joined.replace(accessToken, "<access token redacted>");
            }
            return joined;
        }
    }

    /**
     * @param version   the flattened version manifest
     * @param profile   the profile being launched
     * @param account   the signed-in (or offline) player
     * @param gameDir   the profile's isolated game directory
     * @param assetsDir directory to pass as {@code --assetsDir}
     * @param java      the runtime chosen for this launch
     */
    public LaunchCommand build(VersionJson version, Profile profile, Account account,
                               Path gameDir, Path assetsDir, JavaLocator.JavaRuntime java) {

        Map<String, Boolean> features = features(profile);
        List<Path> classpath = buildClasspath(version);
        Map<String, String> placeholders = placeholders(version, profile, account, gameDir, assetsDir, classpath);

        List<String> command = new ArrayList<>();
        command.add(java.executable().toString());

        // Heap settings come before the metadata's JVM arguments so a profile
        // override in extraJvmArguments can still win by appearing later.
        command.add("-Xmx" + profile.memoryMegabytes() + "M");
        command.add("-Xms" + Math.min(profile.memoryMegabytes(), 512) + "M");

        // LWJGL 3 on macOS must own the first thread; modern version JSONs say so
        // themselves through a rule, older ones do not.
        if (Platform.isMac()) {
            command.add("-XstartOnFirstThread");
        }

        List<String> jvmArguments = new ArrayList<>();
        for (Argument argument : version.jvmArguments()) {
            argument.collectInto(jvmArguments, features);
        }
        jvmArguments.forEach(argument -> command.add(substitute(argument, placeholders)));

        // Log configuration Mojang ships with the version, when present.
        String loggingArgument = loggingArgument(version);
        if (loggingArgument != null) {
            command.add(loggingArgument);
        }

        command.addAll(profile.extraJvmArguments());

        String mainClass = version.mainClass();
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalStateException("version " + version.id() + " declares no mainClass");
        }
        command.add(mainClass);

        List<String> gameArguments = new ArrayList<>();
        for (Argument argument : version.gameArguments()) {
            argument.collectInto(gameArguments, features);
        }
        gameArguments.forEach(argument -> command.add(substitute(argument, placeholders)));

        command.addAll(profile.extraGameArguments());

        return new LaunchCommand(List.copyOf(command), gameDir, java.executable(),
                classpath, mainClass);
    }

    // ---------------------------------------------------------------- classpath

    /**
     * Libraries first, client jar last.
     *
     * <p>The client jar must come last: a mod loader deliberately ships patched
     * copies of some vanilla classes, and the first match on the classpath wins.
     * Deduplication keeps the first occurrence of each group:artifact for the
     * same reason - {@code VersionJson.merge} has already put the loader's
     * overrides ahead of vanilla's.
     */
    public List<Path> buildClasspath(VersionJson version) {
        List<Path> classpath = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Library library : version.libraries()) {
            if (!library.appliesToThisHost()) {
                continue;
            }
            Artifact artifact = library.classpathArtifact();
            if (artifact == null) {
                continue;
            }
            if (!seen.add(library.dedupeKey())) {
                continue;
            }
            String path = artifact.path() != null ? artifact.path() : library.coordinate().path();
            classpath.add(dirs.library(path));
        }

        classpath.add(dirs.versionJar(version.jarVersionId()));
        return List.copyOf(classpath);
    }

    // ---------------------------------------------------------------- placeholders

    private Map<String, Boolean> features(Profile profile) {
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put(Rule.Features.DEMO_USER, profile.demo());
        features.put(Rule.Features.CUSTOM_RESOLUTION, profile.hasCustomResolution());
        // Quick play is not wired to the UI yet; declaring it false keeps the
        // rule-gated arguments out rather than emitting them with empty values.
        features.put(Rule.Features.QUICK_PLAYS_SUPPORT, false);
        features.put(Rule.Features.QUICK_PLAY_SINGLEPLAYER, false);
        features.put(Rule.Features.QUICK_PLAY_MULTIPLAYER, false);
        features.put(Rule.Features.QUICK_PLAY_REALMS, false);
        return features;
    }

    private Map<String, String> placeholders(VersionJson version, Profile profile, Account account,
                                             Path gameDir, Path assetsDir, List<Path> classpath) {
        String separator = Platform.classpathSeparator();
        String joinedClasspath = String.join(separator,
                classpath.stream().map(Path::toString).toList());

        Map<String, String> map = new LinkedHashMap<>();

        // Game
        map.put("auth_player_name", account.username());
        map.put("version_name", version.id());
        map.put("game_directory", gameDir.toString());
        map.put("assets_root", assetsDir.toString());
        map.put("game_assets", assetsDir.toString());          // legacy spelling
        map.put("assets_index_name", version.assetsId());
        map.put("auth_uuid", account.uuid().toString());
        map.put("auth_access_token", account.accessToken());
        map.put("auth_session", "token:" + account.accessToken() + ":" + account.uuid());  // legacy
        map.put("auth_xuid", account.xuid() == null ? "0" : account.xuid());
        map.put("clientid", "");
        map.put("user_type", account.type().userType());
        map.put("version_type", version.type() == null ? "release" : version.type());
        map.put("user_properties", "{}");
        map.put("resolution_width", profile.windowWidth() == null ? "" : profile.windowWidth().toString());
        map.put("resolution_height", profile.windowHeight() == null ? "" : profile.windowHeight().toString());

        // JVM
        map.put("natives_directory", dirs.natives(version.id()).toString());
        map.put("launcher_name", LAUNCHER_NAME);
        map.put("launcher_version", LAUNCHER_VERSION);
        map.put("classpath", joinedClasspath);
        map.put("classpath_separator", separator);
        map.put("library_directory", dirs.libraries().toString());
        map.put("primary_jar", dirs.versionJar(version.jarVersionId()).toString());

        return map;
    }

    /** Replaces every {@code ${key}} occurrence; unknown keys are left untouched. */
    public static String substitute(String template, Map<String, String> placeholders) {
        if (template.indexOf('$') < 0) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("${", i);
            if (start < 0) {
                out.append(template, i, template.length());
                break;
            }
            int end = template.indexOf('}', start + 2);
            if (end < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, start);
            String key = template.substring(start + 2, end);
            String value = placeholders.get(key);
            // An unknown placeholder is left verbatim so it shows up in the log
            // instead of silently becoming an empty argument.
            out.append(value != null ? value : template.substring(start, end + 1));
            i = end + 1;
        }
        return out.toString();
    }

    /**
     * The {@code -Dlog4j.configurationFile=...} argument, when the version ships
     * a client logging config and the file is already on disk.
     */
    private String loggingArgument(VersionJson version) {
        var client = version.logging().get("client");
        if (!client.isObject()) {
            return null;
        }
        String argument = client.get("argument").asString(null);
        String id = client.get("file").get("id").asString(null);
        if (argument == null || id == null) {
            return null;
        }
        Path configFile = dirs.assets().resolve("log_configs").resolve(id);
        if (!java.nio.file.Files.isRegularFile(configFile)) {
            return null;
        }
        return argument.replace("${path}", configFile.toString());
    }

    /** URL of the logging config for a version, for the installer to fetch. */
    public static String loggingConfigUrl(VersionJson version) {
        return version.logging().get("client").get("file").get("url").asString(null);
    }

    /** Included so the launcher identifies itself consistently everywhere. */
    public static String userAgent() {
        return Http.USER_AGENT;
    }
}
