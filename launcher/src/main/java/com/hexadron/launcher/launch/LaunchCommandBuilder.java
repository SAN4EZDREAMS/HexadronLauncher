/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

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
import com.hexadron.launcher.util.Arguments;
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

    /**
     * The placeholder that stands in for the Minecraft session token in the
     * argument list when the secure launch path is used.
     *
     * <p>Chosen to be something no Minecraft argument, mod or path could
     * legitimately contain, so a failed handshake produces an obvious
     * "invalid session" from the game rather than a subtle corruption.
     */
    public static final String ACCESS_TOKEN_PLACEHOLDER = "%%HEXADRON_ACCESS_TOKEN%%";

    /** Main class of the wrapper that reads the token from standard input. */
    public static final String WRAPPER_MAIN_CLASS = "com.hexadron.wrapper.GameLaunchWrapper";

    /**
     * The command plus the context needed to run and debug it.
     *
     * @param secrets    placeholder to real value. Empty when the token is on the
     *                   command line; one entry when the wrapper is in use.
     *                   {@link GameLauncher} writes these to the child's standard
     *                   input and nothing else ever touches them.
     * @param realMainClass the game's own main class, which the wrapper invokes.
     *                   Equal to {@code mainClass} when the wrapper is not used.
     */
    public record LaunchCommand(List<String> command, Path workingDirectory,
                                Path javaExecutable, List<Path> classpath, String mainClass,
                                Map<String, String> secrets, String realMainClass) {

        /** True when the session token is delivered over standard input. */
        public boolean usesSecureHandshake() {
            return !secrets.isEmpty();
        }

        /**
         * Command safe to write to a log.
         *
         * <p>With the wrapper in use there is nothing to mask, because the token
         * was never in the list. The replacement is kept for the fallback path,
         * and {@link com.hexadron.launcher.util.Redactor} runs over the result as
         * a second line of defence for anything else that slipped in.
         */
        public String toLoggableString(String accessToken) {
            String joined = String.join(" ", command);
            if (accessToken != null && !accessToken.isBlank() && !accessToken.equals("0")) {
                joined = joined.replace(accessToken, "<access token redacted>");
            }
            return com.hexadron.launcher.util.Redactor.scrub(joined);
        }
    }

    /**
     * @param version    the flattened version manifest
     * @param profile    the profile being launched
     * @param account    the signed-in (or offline) player
     * @param gameDir    the profile's isolated game directory
     * @param assetsDir  directory to pass as {@code --assetsDir}
     * @param java       the runtime chosen for this launch
     * @param wrapperJar the launch wrapper jar, or null to put the session token
     *                   on the command line the way every other launcher does
     */
    public LaunchCommand build(VersionJson version, Profile profile, Account account,
                               Path gameDir, Path assetsDir, JavaLocator.JavaRuntime java,
                               Path wrapperJar) {
        return build(version, profile, account, gameDir, assetsDir, java, wrapperJar, List.of());
    }

    /**
     * @param agentArguments JVM arguments that must come before the version's
     *                       own - the skin service's {@code -javaagent} and the
     *                       properties that configure it. Separate from
     *                       {@code profile.extraJvmArguments()} because those are
     *                       the user's and go last, where they can override
     *                       anything; an agent that arrives after the class it
     *                       has to transform has arrived too late
     */
    public LaunchCommand build(VersionJson version, Profile profile, Account account,
                               Path gameDir, Path assetsDir, JavaLocator.JavaRuntime java,
                               Path wrapperJar, List<String> agentArguments) {

        // The wrapper is pointless for an offline account, whose "token" is the
        // literal string "0", and it must not be used when the jar is missing.
        boolean secure = wrapperJar != null && !account.isOffline()
                && account.accessToken() != null && !account.accessToken().equals("0");

        Map<String, Boolean> features = features(profile);
        List<Path> classpath = new ArrayList<>();
        if (secure) {
            classpath.add(wrapperJar);
        }
        classpath.addAll(buildClasspath(version));
        classpath = List.copyOf(classpath);

        Map<String, String> placeholders =
                placeholders(version, profile, account, gameDir, assetsDir, classpath, secure);

        List<String> command = new ArrayList<>();

        // The wrapper goes first, so the launch becomes
        //   <wrapper> <java> <java args...>
        // and the wrapper is the parent of the JVM. That is what makes bwrap,
        // firejail, prime-run, gamemoderun and mangohud work.
        //
        // Two things this must not break, and does not:
        //  - standard input. The session token is handed to the game over stdin,
        //    and a wrapper that closed it would break every online account.
        //    bwrap and firejail both pass stdin through.
        //  - the argument list. Everything after the wrapper is unchanged, so a
        //    wrapper that is simply absent leaves the command identical to what
        //    it was before this feature existed.
        command.addAll(Arguments.split(profile.wrapperCommand()));
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

        // Before the version's own arguments and before the classpath: an agent
        // transforms classes as they load, so it has to be attached while the
        // authentication library is still unloaded.
        command.addAll(agentArguments);

        List<String> jvmArguments = new ArrayList<>();
        for (Argument argument : version.jvmArguments()) {
            argument.collectInto(jvmArguments, features);
        }
        String gameJarName = dirs.versionJar(version.jarVersionId()).getFileName().toString();
        jvmArguments.forEach(argument -> command.add(
                repairIgnoreList(substitute(argument, placeholders), gameJarName)));

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
        if (secure) {
            // The wrapper is started instead, and is told which class to hand
            // control to once it has received the session token over stdin.
            command.add(WRAPPER_MAIN_CLASS);
            command.add(mainClass);
        } else {
            command.add(mainClass);
        }

        List<String> gameArguments = new ArrayList<>();
        for (Argument argument : version.gameArguments()) {
            argument.collectInto(gameArguments, features);
        }
        gameArguments.forEach(argument -> command.add(substitute(argument, placeholders)));

        command.addAll(profile.extraGameArguments());

        Map<String, String> secrets = secure
                ? Map.of(ACCESS_TOKEN_PLACEHOLDER, account.accessToken())
                : Map.of();

        return new LaunchCommand(List.copyOf(command), gameDir, java.executable(),
                classpath, secure ? WRAPPER_MAIN_CLASS : mainClass, secrets, mainClass);
    }

    // ------------------------------------------------------------- ignoreList

    /** The property modern Forge and NeoForge use to keep jars out of the module graph. */
    private static final String IGNORE_LIST_PREFIX = "-DignoreList=";

    /**
     * Adds the game jar's own file name to Forge's {@code ignoreList}.
     *
     * <p><b>What this fixes.</b> From Minecraft 1.17 on, Forge boots through
     * {@code BootstrapLauncher}, which turns every classpath entry into a Java
     * module unless its file name matches an entry of {@code -DignoreList=}. The
     * patched game classes arrive separately, as the module named
     * {@code minecraft}, so the plain game jar on the classpath has to be
     * excluded - two modules cannot both own {@code net.minecraft.server}.
     *
     * <p>Forge writes {@code ${version_name}.jar} into that list, which assumes
     * the launcher stores the game jar under the <em>loader's</em> version id:
     * {@code versions/1.20.1-forge-47.4.10/1.20.1-forge-47.4.10.jar}, a copy of
     * the vanilla jar. This launcher does not copy it. Every profile shares one
     * {@code versions/1.20.1/1.20.1.jar}, which is why installing four Forge
     * builds costs four small manifests rather than four 25 MB jars.
     *
     * <p>So the assumption is false here, the name in the list never matches, and
     * the jar becomes an automatic module called {@code _1._20._1} that collides
     * with {@code minecraft}:
     *
     * <pre>
     * java.lang.module.ResolutionException: Module minecraft contains package
     * net.minecraft.server, module _1._20._1 exports package
     * net.minecraft.server to minecraft
     * </pre>
     *
     * <p>The fix is to name the jar this launcher actually puts there. Appending
     * rather than replacing: {@code ${version_name}.jar} stays correct for anyone
     * whose data folder was written by another launcher, and the list is matched
     * by prefix, so an extra entry can only ever exclude the file it names.
     */
    public static String repairIgnoreList(String argument, String gameJarName) {
        if (!argument.startsWith(IGNORE_LIST_PREFIX)
                || gameJarName == null || gameJarName.isBlank()) {
            return argument;
        }
        String entries = argument.substring(IGNORE_LIST_PREFIX.length());
        for (String entry : entries.split(",")) {
            if (entry.trim().equals(gameJarName)) {
                return argument;
            }
        }
        return argument + "," + gameJarName;
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
                                             Path gameDir, Path assetsDir, List<Path> classpath,
                                             boolean secure) {
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

        // With the secure path, what goes into the argument list is a placeholder;
        // the real token is written to the child's stdin by GameLauncher and
        // substituted inside the game's own JVM. This is the difference between a
        // session token that any process on the machine can read out of the
        // process table, and one that never leaves a pipe.
        String tokenForArguments = secure ? ACCESS_TOKEN_PLACEHOLDER : account.accessToken();
        map.put("auth_access_token", tokenForArguments);
        map.put("auth_session", "token:" + tokenForArguments + ":" + account.uuid());  // legacy
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
