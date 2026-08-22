package com.hexadron.launcher;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.install.loader.ForgeInstaller;
import com.hexadron.launcher.install.loader.LoaderInstaller;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.install.loader.NeoForgeInstaller;
import com.hexadron.launcher.install.loader.forge.ForgeProcessor;
import com.hexadron.launcher.install.loader.forge.InstallProfile;
import com.hexadron.launcher.install.loader.forge.Tokens;
import com.hexadron.launcher.i18n.Language;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.json.JsonException;
import com.hexadron.launcher.launch.JavaLocator;
import com.hexadron.launcher.launch.LaunchCommandBuilder;
import com.hexadron.launcher.meta.Argument;
import com.hexadron.launcher.meta.AssetIndex;
import com.hexadron.launcher.meta.Library;
import com.hexadron.launcher.meta.Rule;
import com.hexadron.launcher.meta.VersionJson;
import com.hexadron.launcher.meta.VersionManifest;
import com.hexadron.launcher.mods.CurseForgeProvider;
import com.hexadron.launcher.mods.InstalledMod;
import com.hexadron.launcher.mods.ModFile;
import com.hexadron.launcher.mods.ModInstaller;
import com.hexadron.launcher.mods.ModLibrary;
import com.hexadron.launcher.mods.ModOrigin;
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.util.MavenCoordinate;
import com.hexadron.launcher.util.Platform;
import com.hexadron.launcher.util.Redactor;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dependency-free assertions over the metadata layer.
 *
 * <p>Runnable with {@code ./gradlew :launcher:selfCheck} or directly as a main
 * class. Deliberately uses no test framework: the whole point of the
 * dependency-free core is that its correctness can be checked anywhere, with
 * nothing but a JDK and no network.
 *
 * <p>Everything here is pure parsing and assembly logic - the part where a
 * silent mistake produces a classpath that boots into a confusing crash rather
 * than an obvious error.
 */
public final class SelfCheck {

    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        jsonParsing();
        jsonOutput();
        mavenCoordinates();
        ruleEvaluation();
        libraryDialects();
        versionMerge();
        legacyArguments();
        placeholderSubstitution();
        classpathAssembly();
        assetIndexParsing();
        accounts();
        securityHardening();
        javaVersionParsing();
        versionManifestParsing();
        playerNamesAndArguments();
        modOwnership();
        loaderCompatibility();
        forgeInstallerProfiles();
        forgeTokenLanguage();
        curseForgeKeyHandling();
        searchPaging();
        translations();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("SelfCheck: " + checks + " checks passed.");
            return;
        }
        System.out.println("SelfCheck: " + failures.size() + " of " + checks + " checks FAILED:");
        failures.forEach(failure -> System.out.println("  - " + failure));
        System.exit(1);
    }

    // ---------------------------------------------------------------- json

    private static void jsonParsing() {
        section("JSON parsing");

        Json root = Json.parse("""
                {
                  "id": "26.2",
                  "count": 42,
                  "ratio": 1.5,
                  "big": 9007199254740993,
                  "ok": true,
                  "nothing": null,
                  "list": [1, "two", {"three": 3}, []],
                  "escaped": "line\\nbreak \\"quoted\\" back\\\\slash \\u0041"
                }""");

        check("string member", "26.2".equals(root.get("id").asString()));
        check("int member", root.get("count").asInt(0) == 42);
        check("double member", root.get("ratio").asDouble(0) == 1.5);
        check("long precision preserved", root.get("big").asLong(0) == 9007199254740993L);
        check("bool member", root.get("ok").asBool(false));
        check("null is absent", root.get("nothing").isAbsent());
        check("null is not missing", !root.get("nothing").isMissing());
        check("array size", root.get("list").size() == 4);
        check("nested object in array", root.get("list").get(2).get("three").asInt(0) == 3);
        check("empty nested array", root.get("list").get(3).size() == 0);
        check("escapes decoded",
                "line\nbreak \"quoted\" back\\slash A".equals(root.get("escaped").asString()));

        // Missing-key navigation must never throw, at any depth.
        check("missing key is MISSING", root.get("nope").isMissing());
        check("deep missing navigation safe",
                "fallback".equals(root.get("a").get("b").get("c").asString("fallback")));
        check("index on non-array is missing", root.get("id").get(0).isMissing());
        check("key on non-object is missing", root.get("list").get("id").isMissing());

        checkThrows("trailing content rejected", () -> Json.parse("{} {}"));
        checkThrows("trailing comma rejected", () -> Json.parse("[1,2,]"));
        checkThrows("unquoted key rejected", () -> Json.parse("{a:1}"));
        checkThrows("unterminated string rejected", () -> Json.parse("{\"a\":\"b"));
        checkThrows("bare control char rejected", () -> Json.parse("{\"a\":\"b\nc\"}"));
        checkThrows("bad escape rejected", () -> Json.parse("{\"a\":\"\\x\"}"));
        checkThrows("empty input rejected", () -> Json.parse(""));

        check("BOM tolerated", Json.parse("\uFEFF{\"a\":1}").get("a").asInt(0) == 1);

        // Error messages must locate the problem.
        try {
            Json.parse("{\n  \"a\": 1,\n  \"b\": }\n}");
            check("malformed value reports position", false);
        } catch (JsonException e) {
            check("malformed value reports position", e.getMessage().contains("line 3"));
        }
    }

    private static void jsonOutput() {
        section("JSON output");

        Json built = Json.object()
                .put("name", "Hexadron")
                .put("count", 3L)
                .put("nested", Json.object().put("flag", true))
                .put("list", Json.array().add("a").add("b"));

        String compact = built.toString();
        check("compact output has no spaces", !compact.contains(": "));
        Json reparsed = Json.parse(compact);
        check("round trip preserves string", "Hexadron".equals(reparsed.get("name").asString()));
        check("round trip preserves nested bool", reparsed.get("nested").get("flag").asBool(false));
        check("round trip preserves array", reparsed.get("list").size() == 2);

        String pretty = built.toPrettyString();
        check("pretty output is indented", pretty.contains("\n  \"name\""));
        check("pretty output re-parses",
                "Hexadron".equals(Json.parse(pretty).get("name").asString()));

        // Round-tripping the awkward characters is what protects profile names.
        String awkward = "tab\there \"quotes\" \\ back \u0007 bell";
        String encoded = Json.object().put("v", awkward).toString();
        check("awkward string round trips", awkward.equals(Json.parse(encoded).get("v").asString()));

        check("whole numbers print without a decimal point",
                Json.of(5.0).toString().equals("5"));
    }

    // ---------------------------------------------------------------- maven

    private static void mavenCoordinates() {
        section("Maven coordinates");

        MavenCoordinate plain = MavenCoordinate.parse("org.ow2.asm:asm:9.7");
        check("plain path", "org/ow2/asm/asm/9.7/asm-9.7.jar".equals(plain.path()));
        check("plain groupArtifact", "org.ow2.asm:asm".equals(plain.groupArtifact()));

        MavenCoordinate classified = MavenCoordinate.parse("org.lwjgl:lwjgl:3.3.3:natives-windows");
        check("classifier path",
                "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar".equals(classified.path()));
        check("classifier dedupe key",
                "org.lwjgl:lwjgl:natives-windows".equals(classified.dedupeKey()));
        check("classifier differs from plain dedupe key",
                !classified.dedupeKey().equals(MavenCoordinate.parse("org.lwjgl:lwjgl:3.3.3").dedupeKey()));

        MavenCoordinate extended = MavenCoordinate.parse("de.oceanlabs.mcp:mcp_config:1.20.1@zip");
        check("extension parsed", "zip".equals(extended.extension()));
        check("extension path",
                "de/oceanlabs/mcp/mcp_config/1.20.1/mcp_config-1.20.1.zip".equals(extended.path()));

        MavenCoordinate both = MavenCoordinate.parse("net.minecraftforge:forge:1.20.1-47.2.0:universal@jar");
        check("classifier plus extension",
                "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar"
                        .equals(both.path()));

        checkThrows("too few parts rejected", () -> MavenCoordinate.parse("group:artifact"));
    }

    // ---------------------------------------------------------------- rules

    private static void ruleEvaluation() {
        section("Rule evaluation");

        check("no rules means allowed", Rule.allows(List.of()));

        String thisOs = Platform.osName();
        String otherOs = thisOs.equals("windows") ? "linux" : "windows";

        List<Rule> allowThisOs = Rule.parseList(Json.parse(
                "[{\"action\":\"allow\",\"os\":{\"name\":\"" + thisOs + "\"}}]"));
        check("allow for this OS", Rule.allows(allowThisOs));

        List<Rule> allowOtherOs = Rule.parseList(Json.parse(
                "[{\"action\":\"allow\",\"os\":{\"name\":\"" + otherOs + "\"}}]"));
        check("allow for another OS excludes us", !Rule.allows(allowOtherOs));

        // The real shape: allow everywhere, then disallow one OS. Last match wins.
        List<Rule> allowThenDeny = Rule.parseList(Json.parse(
                "[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"" + thisOs + "\"}}]"));
        check("later disallow overrides earlier allow", !Rule.allows(allowThenDeny));

        List<Rule> allowThenDenyOther = Rule.parseList(Json.parse(
                "[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"" + otherOs + "\"}}]"));
        check("disallow for another OS leaves us allowed", Rule.allows(allowThenDenyOther));

        List<Rule> demoOnly = Rule.parseList(Json.parse(
                "[{\"action\":\"allow\",\"features\":{\"is_demo_user\":true}}]"));
        check("feature off excludes", !Rule.allows(demoOnly, Map.of()));
        check("feature on includes", Rule.allows(demoOnly, Map.of("is_demo_user", true)));
        check("feature explicitly false excludes",
                !Rule.allows(demoOnly, Map.of("is_demo_user", false)));

        List<Rule> archGated = Rule.parseList(Json.parse(
                "[{\"action\":\"allow\",\"os\":{\"name\":\"" + thisOs + "\",\"arch\":\"x86\"}}]"));
        check("arch narrows the match",
                Rule.allows(archGated) == Platform.arch().equals("x86"));

        List<Rule> malformedPattern = Rule.parseList(Json.parse(
                "[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"" + thisOs
                        + "\",\"version\":\"^[unclosed\"}}]"));
        check("malformed version regex does not throw", Rule.allows(malformedPattern));
    }

    // ---------------------------------------------------------------- libraries

    private static void libraryDialects() {
        section("Library dialects");

        // Vanilla: full downloads block.
        Library vanilla = Library.parse(Json.parse("""
                {
                  "name": "com.google.guava:guava:32.1.2-jre",
                  "downloads": {
                    "artifact": {
                      "path": "com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar",
                      "sha1": "aaaa",
                      "size": 123,
                      "url": "https://libraries.minecraft.net/com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar"
                    }
                  }
                }"""));
        check("vanilla artifact url", vanilla.classpathArtifact().hasUrl());
        check("vanilla artifact path",
                "com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar".equals(vanilla.classpathPath()));
        check("vanilla is not a native container", !vanilla.isLegacyNativeContainer());

        // Fabric: name plus a maven repository ROOT, not a file URL.
        Library fabric = Library.parse(Json.parse("""
                {
                  "name": "net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7",
                  "url": "https://maven.fabricmc.net/"
                }"""));
        check("fabric root becomes a full artifact url",
                ("https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.15.2+mixin.0.8.7/"
                        + "sponge-mixin-0.15.2+mixin.0.8.7.jar")
                        .equals(fabric.classpathArtifact().url()));
        check("fabric path derived from coordinate",
                fabric.classpathPath().startsWith("net/fabricmc/sponge-mixin/"));
        check("fabric entry has no checksum", fabric.classpathArtifact().sha1() == null);

        // Fabric root without a trailing slash must still produce a valid URL.
        Library noSlash = Library.parse(Json.parse("""
                {"name": "org.ow2.asm:asm:9.7", "url": "https://maven.fabricmc.net"}"""));
        check("missing trailing slash is repaired",
                "https://maven.fabricmc.net/org/ow2/asm/asm/9.7/asm-9.7.jar"
                        .equals(noSlash.classpathArtifact().url()));

        // Legacy native container with ${arch} interpolation.
        String thisOs = Platform.osName();
        Library legacyNative = Library.parse(Json.parse("""
                {
                  "name": "net.java.jinput:jinput-platform:2.0.5",
                  "natives": {"%s": "natives-%s-${arch}"},
                  "downloads": {
                    "classifiers": {
                      "natives-%s-%s": {
                        "path": "net/java/jinput/jinput-platform/2.0.5/jinput-platform-2.0.5-natives.jar",
                        "url": "https://libraries.minecraft.net/x.jar",
                        "sha1": "bbbb", "size": 1
                      }
                    }
                  },
                  "extract": {"exclude": ["META-INF/"]}
                }""".formatted(thisOs, thisOs, thisOs, Platform.archBits())));

        check("legacy native container detected", legacyNative.isLegacyNativeContainer());
        check("arch placeholder substituted",
                ("natives-" + thisOs + "-" + Platform.archBits())
                        .equals(legacyNative.nativeClassifierForThisHost()));
        check("native artifact resolved from classifiers", legacyNative.nativeArtifact() != null);
        check("native container is kept off the classpath", legacyNative.classpathArtifact() == null);
        check("extract excludes parsed", legacyNative.extractExcludes().contains("META-INF/"));

        // Modern natives: an ordinary rule-gated classpath jar, no natives block.
        Library modernNative = Library.parse(Json.parse("""
                {
                  "name": "org.lwjgl:lwjgl:3.3.3:natives-%s",
                  "rules": [{"action": "allow", "os": {"name": "%s"}}],
                  "downloads": {"artifact": {
                    "path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-%s.jar",
                    "url": "https://libraries.minecraft.net/y.jar", "sha1": "cccc", "size": 2}}
                }""".formatted(thisOs, thisOs, thisOs)));
        check("modern native is a classpath entry", modernNative.classpathArtifact() != null);
        check("modern native is not a legacy container", !modernNative.isLegacyNativeContainer());
        check("modern native applies to this host", modernNative.appliesToThisHost());

        checkThrows("library without a name is rejected",
                () -> Library.parse(Json.parse("{\"downloads\":{}}")));
    }

    // ---------------------------------------------------------------- merge

    private static void versionMerge() {
        section("Version inheritance and merge");

        VersionJson vanilla = VersionJson.parse(Json.parse("""
                {
                  "id": "26.2",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "assets": "26",
                  "assetIndex": {"id": "26", "url": "https://example/26.json", "sha1": "aa", "size": 1, "totalSize": 2},
                  "javaVersion": {"component": "java-runtime-delta", "majorVersion": 25},
                  "downloads": {"client": {"url": "https://example/client.jar", "sha1": "bb", "size": 3}},
                  "libraries": [
                    {"name": "org.ow2.asm:asm:9.5",
                     "downloads": {"artifact": {"path": "org/ow2/asm/asm/9.5/asm-9.5.jar",
                                                "url": "https://libraries.minecraft.net/asm-9.5.jar",
                                                "sha1": "cc", "size": 4}}},
                    {"name": "com.google.guava:guava:32.1.2-jre",
                     "downloads": {"artifact": {"path": "g.jar", "url": "https://libraries.minecraft.net/g.jar",
                                                "sha1": "dd", "size": 5}}}
                  ],
                  "arguments": {
                    "game": ["--username", "${auth_player_name}"],
                    "jvm": ["-Djava.library.path=${natives_directory}", "-cp", "${classpath}"]
                  }
                }"""));

        VersionJson fabric = VersionJson.parse(Json.parse("""
                {
                  "id": "fabric-loader-0.19.3-26.2",
                  "inheritsFrom": "26.2",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "libraries": [
                    {"name": "org.ow2.asm:asm:9.7", "url": "https://maven.fabricmc.net/"},
                    {"name": "net.fabricmc:fabric-loader:0.19.3", "url": "https://maven.fabricmc.net/"}
                  ],
                  "arguments": {"jvm": ["-DFabricMcEmu=net.minecraft.client.main.Main"]}
                }"""));

        check("child declares a parent", fabric.hasParent());
        check("parent name recorded", "26.2".equals(fabric.inheritsFrom()));

        VersionJson merged = VersionJson.merge(fabric, vanilla);

        check("merged id is the child's", "fabric-loader-0.19.3-26.2".equals(merged.id()));
        check("merged has no parent left", !merged.hasParent());
        check("loader mainClass wins",
                "net.fabricmc.loader.impl.launch.knot.KnotClient".equals(merged.mainClass()));
        check("assets inherited from parent", "26".equals(merged.assetsId()));
        check("assetIndex inherited", merged.assetIndex() != null);
        check("javaVersion inherited", merged.requiredJavaMajor() == 25);
        check("client download inherited", merged.clientDownload() != null);
        check("jar id points at the vanilla version", "26.2".equals(merged.jarVersionId()));

        // The critical one: the loader's ASM must shadow vanilla's.
        List<Library> libraries = merged.libraries();
        check("libraries merged and deduplicated", libraries.size() == 3);
        Library firstAsm = libraries.stream()
                .filter(library -> library.coordinate().groupArtifact().equals("org.ow2.asm:asm"))
                .findFirst().orElseThrow();
        check("loader ASM version wins over vanilla's",
                "9.7".equals(firstAsm.coordinate().version()));
        check("loader libraries come first",
                libraries.get(0).coordinate().groupArtifact().equals("org.ow2.asm:asm"));
        check("vanilla-only library survives",
                libraries.stream().anyMatch(library ->
                        library.coordinate().groupArtifact().equals("com.google.guava:guava")));

        List<String> jvm = new ArrayList<>();
        merged.jvmArguments().forEach(argument -> argument.collectInto(jvm, Map.of()));
        check("parent jvm args come first", jvm.get(0).startsWith("-Djava.library.path="));
        check("child jvm args appended",
                jvm.get(jvm.size() - 1).equals("-DFabricMcEmu=net.minecraft.client.main.Main"));

        List<String> game = new ArrayList<>();
        merged.gameArguments().forEach(argument -> argument.collectInto(game, Map.of()));
        check("parent game args preserved", game.contains("--username"));

        // The pre-1.13 form merges the other way round, and this is the case that
        // caught it: Forge for 1.12.2 writes minecraftArguments containing the
        // whole vanilla line plus its own --tweakClass. Appending that to the
        // parent's copy of the same line gives every argument twice, and
        // LaunchWrapper refuses to start with "Found multiple arguments for
        // option gameDir".
        VersionJson legacyVanilla = VersionJson.parse(Json.parse("""
                {
                  "id": "1.12.2",
                  "mainClass": "net.minecraft.client.main.Main",
                  "minecraftArguments": "--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${assets_root}",
                  "libraries": []
                }"""));
        VersionJson legacyForge = VersionJson.parse(Json.parse("""
                {
                  "id": "1.12.2-forge-14.23.5.2859",
                  "inheritsFrom": "1.12.2",
                  "mainClass": "net.minecraft.launchwrapper.Launch",
                  "minecraftArguments": "--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${assets_root} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker --versionType Forge",
                  "libraries": []
                }"""));

        List<String> legacyGame = new ArrayList<>();
        VersionJson.merge(legacyForge, legacyVanilla).gameArguments()
                .forEach(argument -> argument.collectInto(legacyGame, Map.of()));

        check("a legacy argument line is not doubled",
                legacyGame.stream().filter("--gameDir"::equals).count() == 1);
        check("the loader's legacy line is the one used",
                legacyGame.contains("--tweakClass"));
        check("the vanilla arguments are still there",
                legacyGame.contains("--username") && legacyGame.contains("--assetsDir"));
        check("nothing else crept in", legacyGame.size() == 12);

        // A parent with the old form and a child with the new one has to keep
        // both, or the vanilla arguments vanish.
        VersionJson modernChildOnLegacyParent = VersionJson.parse(Json.parse("""
                {
                  "id": "mixed",
                  "inheritsFrom": "1.12.2",
                  "arguments": {"game": ["--tweakClass", "example.Tweaker"]},
                  "libraries": []
                }"""));
        List<String> mixedGame = new ArrayList<>();
        VersionJson.merge(modernChildOnLegacyParent, legacyVanilla).gameArguments()
                .forEach(argument -> argument.collectInto(mixedGame, Map.of()));
        check("a legacy parent keeps its arguments under a modern child",
                mixedGame.contains("--username") && mixedGame.contains("example.Tweaker"));
    }

    // ---------------------------------------------------------------- legacy

    private static void legacyArguments() {
        section("Legacy argument handling");

        VersionJson legacy = VersionJson.parse(Json.parse("""
                {
                  "id": "1.12.2",
                  "mainClass": "net.minecraft.client.main.Main",
                  "assets": "1.12",
                  "minecraftArguments": "--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory}",
                  "libraries": []
                }"""));

        List<String> game = new ArrayList<>();
        legacy.gameArguments().forEach(argument -> argument.collectInto(game, Map.of()));
        check("legacy string split into tokens", game.size() == 6);
        check("legacy first token", "--username".equals(game.get(0)));
        check("legacy placeholder token preserved", "${auth_player_name}".equals(game.get(1)));

        List<String> jvm = new ArrayList<>();
        legacy.jvmArguments().forEach(argument -> argument.collectInto(jvm, Map.of()));
        check("legacy versions get default jvm args", jvm.contains("-cp"));
        check("legacy defaults include the native path",
                jvm.stream().anyMatch(argument -> argument.contains("${natives_directory}")));
        check("legacy default java major is 8", legacy.requiredJavaMajor() == 8);

        // Rule-gated argument objects.
        List<Argument> conditional = Argument.parseList(Json.parse("""
                [
                  "--always",
                  {"rules": [{"action": "allow", "features": {"has_custom_resolution": true}}],
                   "value": ["--width", "${resolution_width}"]}
                ]"""));
        List<String> without = new ArrayList<>();
        conditional.forEach(argument -> argument.collectInto(without, Map.of()));
        check("gated argument omitted when feature off", without.size() == 1);

        List<String> with = new ArrayList<>();
        conditional.forEach(argument ->
                argument.collectInto(with, Map.of("has_custom_resolution", true)));
        check("gated argument included when feature on", with.size() == 3);
        check("gated argument keeps its order", "--width".equals(with.get(1)));
    }

    // ---------------------------------------------------------------- placeholders

    private static void placeholderSubstitution() {
        section("Placeholder substitution");

        Map<String, String> values = Map.of(
                "auth_player_name", "Steve",
                "classpath", "a.jar:b.jar",
                "empty", "");

        check("simple substitution",
                "Steve".equals(LaunchCommandBuilder.substitute("${auth_player_name}", values)));
        check("embedded substitution",
                "-Duser=Steve".equals(LaunchCommandBuilder.substitute("-Duser=${auth_player_name}", values)));
        check("multiple substitutions",
                "Steve/a.jar:b.jar".equals(
                        LaunchCommandBuilder.substitute("${auth_player_name}/${classpath}", values)));
        check("no placeholder is untouched",
                "--demo".equals(LaunchCommandBuilder.substitute("--demo", values)));
        check("empty value substitutes to empty",
                "".equals(LaunchCommandBuilder.substitute("${empty}", values)));

        // An unknown key must stay visible rather than silently becoming "".
        check("unknown placeholder left verbatim",
                "${quickPlayPath}".equals(LaunchCommandBuilder.substitute("${quickPlayPath}", values)));
        check("unterminated placeholder left verbatim",
                "${broken".equals(LaunchCommandBuilder.substitute("${broken", values)));
        check("lone dollar left verbatim",
                "$notaplaceholder".equals(
                        LaunchCommandBuilder.substitute("$notaplaceholder", values)));

        // Forge's ignoreList. It names the game jar as ${version_name}.jar,
        // which assumes a copy of the vanilla jar under the loader's own version
        // folder. This launcher shares one jar between profiles, so the name
        // never matches, the jar becomes an automatic module, and Forge dies with
        // "Module minecraft contains package net.minecraft.server, module
        // _1._20._1 exports package net.minecraft.server to minecraft".
        String forgeIgnoreList = "-DignoreList=bootstraplauncher,securejarhandler,asm,"
                + "client-extra,fmlcore,forge-,1.20.1-forge-47.4.10.jar";

        check("the real game jar name is added to the ignore list",
                LaunchCommandBuilder.repairIgnoreList(forgeIgnoreList, "1.20.1.jar")
                        .endsWith(",1.20.1.jar"));
        check("the entries already there are kept",
                LaunchCommandBuilder.repairIgnoreList(forgeIgnoreList, "1.20.1.jar")
                        .contains("1.20.1-forge-47.4.10.jar"));
        // Idempotent, or a data folder written by another launcher - where the
        // name does already match - would grow a duplicate entry every launch.
        check("a name already listed is not added twice",
                LaunchCommandBuilder.repairIgnoreList(forgeIgnoreList, "1.20.1-forge-47.4.10.jar")
                        .equals(forgeIgnoreList));
        check("any other argument is untouched",
                "-Xmx4096M".equals(
                        LaunchCommandBuilder.repairIgnoreList("-Xmx4096M", "1.20.1.jar")));
        check("an argument that merely mentions the property is untouched",
                "-Dsomething=-DignoreList=x".equals(LaunchCommandBuilder.repairIgnoreList(
                        "-Dsomething=-DignoreList=x", "1.20.1.jar")));
        check("no jar name means no change",
                forgeIgnoreList.equals(
                        LaunchCommandBuilder.repairIgnoreList(forgeIgnoreList, "")));
    }

    // ---------------------------------------------------------------- classpath

    private static void classpathAssembly() {
        section("Classpath assembly");

        Path root = Paths.get(System.getProperty("java.io.tmpdir"), "hexadron-selfcheck");
        GameDirs dirs = new GameDirs(root);
        LaunchCommandBuilder builder = new LaunchCommandBuilder(dirs);

        String thisOs = Platform.osName();
        String otherOs = thisOs.equals("windows") ? "linux" : "windows";

        VersionJson version = VersionJson.parse(Json.parse("""
                {
                  "id": "test",
                  "mainClass": "Main",
                  "downloads": {"client": {"url": "https://example/c.jar", "sha1": "aa", "size": 1}},
                  "libraries": [
                    {"name": "org.ow2.asm:asm:9.7", "url": "https://maven.fabricmc.net/"},
                    {"name": "org.ow2.asm:asm:9.5", "url": "https://libraries.minecraft.net/"},
                    {"name": "only.on:other:1.0",
                     "rules": [{"action": "allow", "os": {"name": "%s"}}],
                     "url": "https://libraries.minecraft.net/"},
                    {"name": "only.on:this:1.0",
                     "rules": [{"action": "allow", "os": {"name": "%s"}}],
                     "url": "https://libraries.minecraft.net/"}
                  ]
                }""".formatted(otherOs, thisOs)));

        List<Path> classpath = builder.buildClasspath(version);
        List<String> asStrings = classpath.stream().map(Path::toString).toList();

        check("client jar is last",
                asStrings.get(asStrings.size() - 1).endsWith("test.jar"));
        check("duplicate group:artifact collapsed to one entry",
                asStrings.stream().filter(entry -> entry.contains("ow2")).count() == 1);
        check("first (loader) version of the duplicate wins",
                asStrings.stream().anyMatch(entry -> entry.contains("9.7")));
        check("shadowed vanilla version excluded",
                asStrings.stream().noneMatch(entry -> entry.contains("9.5")));
        check("other-OS library excluded",
                asStrings.stream().noneMatch(entry -> entry.contains("other")));
        check("this-OS library included",
                asStrings.stream().anyMatch(entry -> entry.contains("this")));
        check("every entry is under the libraries store or versions store",
                asStrings.stream().allMatch(entry ->
                        entry.startsWith(dirs.libraries().toString())
                                || entry.startsWith(dirs.versions().toString())));
    }

    // ---------------------------------------------------------------- assets

    private static void assetIndexParsing() {
        section("Asset index");

        AssetIndex index = AssetIndex.parse("26", Json.parse("""
                {
                  "objects": {
                    "minecraft/sounds/step.ogg": {"hash": "abcdef0123456789abcdef0123456789abcdef01", "size": 100},
                    "minecraft/lang/en_us.json": {"hash": "0123456789abcdef0123456789abcdef01234567", "size": 200}
                  }
                }"""));

        check("object count", index.size() == 2);
        check("total bytes", index.totalBytes() == 300);
        check("modern index is not virtual", !index.virtual());
        check("modern index needs no materialisation", !index.needsMaterialisation());

        AssetIndex.AssetObject object = index.objects().get("minecraft/sounds/step.ogg");
        check("store path uses the first two hex characters",
                "ab/abcdef0123456789abcdef0123456789abcdef01".equals(object.storePath()));
        check("download url built from the store path",
                object.url().endsWith("/ab/abcdef0123456789abcdef0123456789abcdef01"));

        AssetIndex virtual = AssetIndex.parse("legacy", Json.parse("""
                {"virtual": true, "objects": {"a": {"hash": "ffeeddccbbaa99887766554433221100ffeeddcc", "size": 1}}}"""));
        check("virtual flag parsed", virtual.virtual());
        check("virtual index needs materialisation", virtual.needsMaterialisation());

        AssetIndex preOneSix = AssetIndex.parse("pre-1.6", Json.parse("""
                {"map_to_resources": true, "objects": {}}"""));
        check("map_to_resources parsed", preOneSix.mapToResources());
        check("map_to_resources needs materialisation", preOneSix.needsMaterialisation());
    }

    // ---------------------------------------------------------------- accounts

    private static void accounts() {
        section("Accounts");

        Account steve = Account.offline("Steve");
        Account steveAgain = Account.offline("Steve");
        check("offline uuid is deterministic", steve.uuid().equals(steveAgain.uuid()));
        check("offline uuid matches the server-side derivation",
                steve.uuid().equals(UUID.nameUUIDFromBytes(
                        "OfflinePlayer:Steve".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        check("different names give different uuids",
                !steve.uuid().equals(Account.offline("Alex").uuid()));
        check("offline never needs a refresh", !steve.needsRefresh());
        check("offline user type is legacy", "legacy".equals(steve.type().userType()));
        // A blank name used to become "Player" silently. It is refused now:
        // quietly renaming a player also quietly moves their offline UUID, and
        // with it their inventory and position in every existing world.
        checkThrows("blank name is refused", () -> Account.offline("  "));

        Json serialised = steve.toMetadataJson();
        Account restored = Account.fromMetadataJson(serialised, Json.object());
        check("account round trips", restored.uuid().equals(steve.uuid()));
        check("account name round trips", restored.username().equals(steve.username()));

        // The point of the metadata/secret split: accounts.json must not be able
        // to carry a credential, even by accident.
        Account signedIn = new Account(Account.AccountType.MICROSOFT, "Notch",
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                "mc-access-token-value", "ms-refresh-token-value",
                System.currentTimeMillis() + 60_000L, "2535");
        String metadataText = signedIn.toMetadataJson().toString();
        check("account metadata carries no access token",
                !metadataText.contains("mc-access-token-value"));
        check("account metadata carries no refresh token",
                !metadataText.contains("ms-refresh-token-value"));
        String secretText = signedIn.toSecretJson().toString();
        check("the secret blob carries both tokens",
                secretText.contains("mc-access-token-value")
                        && secretText.contains("ms-refresh-token-value"));
        Account reloaded = Account.fromMetadataJson(signedIn.toMetadataJson(), signedIn.toSecretJson());
        check("metadata plus secrets reconstructs the account",
                "mc-access-token-value".equals(reloaded.accessToken())
                        && "ms-refresh-token-value".equals(reloaded.refreshToken()));
        check("an account with no stored secret asks for a new sign-in",
                Account.fromMetadataJson(signedIn.toMetadataJson(), Json.object()).needsSignIn());

        UUID parsed = Account.parseUndashedUuid("069a79f444e94726a5befca90e38aaf5");
        check("undashed uuid parsed",
                "069a79f4-44e9-4726-a5be-fca90e38aaf5".equals(parsed.toString()));
        check("dashed uuid also accepted",
                Account.parseUndashedUuid("069a79f4-44e9-4726-a5be-fca90e38aaf5").equals(parsed));
        checkThrows("malformed uuid rejected", () -> Account.parseUndashedUuid("nope"));

        Account expired = new Account(Account.AccountType.MICROSOFT, "X", steve.uuid(),
                "t", "r", System.currentTimeMillis() - 1000, "0");
        check("expired microsoft token needs refresh", expired.needsRefresh());
        Account fresh = new Account(Account.AccountType.MICROSOFT, "X", steve.uuid(),
                "t", "r", System.currentTimeMillis() + 3_600_000, "0");
        check("fresh microsoft token does not", !fresh.needsRefresh());
        check("microsoft user type is msa", "msa".equals(fresh.type().userType()));
    }

    // ---------------------------------------------------------------- java

    private static void javaVersionParsing() {
        section("Java version parsing");

        check("modern version", Integer.valueOf(25).equals(JavaLocator.parseMajor("25.0.1")));
        check("bare major", Integer.valueOf(21).equals(JavaLocator.parseMajor("21")));
        check("legacy 1.8 scheme", Integer.valueOf(8).equals(JavaLocator.parseMajor("1.8.0_402")));
        check("early access suffix", Integer.valueOf(26).equals(JavaLocator.parseMajor("26-ea")));
        check("blank returns null", JavaLocator.parseMajor("  ") == null);
        check("nonsense returns null", JavaLocator.parseMajor("abc") == null);
    }

    // ---------------------------------------------------------------- manifest

    private static void versionManifestParsing() {
        section("Version manifest");

        VersionManifest manifest = VersionManifest.parse(Json.parse("""
                {
                  "latest": {"release": "26.2", "snapshot": "26.3-snap1"},
                  "versions": [
                    {"id": "26.2", "type": "release", "url": "https://example/26.2.json",
                     "sha1": "aa", "releaseTime": "2026-06-16T00:00:00+00:00", "time": "x"},
                    {"id": "26.3-snap1", "type": "snapshot", "url": "https://example/s.json", "sha1": "bb"},
                    {"id": "b1.7.3", "type": "old_beta", "url": "https://example/b.json", "sha1": "cc"},
                    {"id": "broken", "type": "release"}
                  ]
                }"""));

        check("latest release", "26.2".equals(manifest.latestRelease()));
        check("latest snapshot", "26.3-snap1".equals(manifest.latestSnapshot()));
        check("entry without a url is skipped", manifest.versions().size() == 3);
        check("release channel filter", manifest.releases().size() == 1);
        check("snapshot channel filter",
                manifest.byChannel(VersionManifest.Channel.SNAPSHOT).size() == 1);
        check("old_beta channel recognised",
                manifest.byChannel(VersionManifest.Channel.OLD_BETA).size() == 1);
        check("lookup by id", manifest.find("26.2").isPresent());
        check("lookup of an unknown id is empty", manifest.find("nope").isEmpty());
        check("sha1 carried through", "aa".equals(manifest.find("26.2").orElseThrow().sha1()));
    }

    // ---------------------------------------------------------------- translations

    /**
     * Guards the language files.
     *
     * <p>A translation goes wrong quietly: a key is forgotten during a rename,
     * or a translator drops a {@code {0}} and the running launcher prints a
     * sentence with a hole in it. Both are caught here, before release, rather
     * than by a user who cannot read the fallback language.
     */
    private static void translations() {
        section("Translations");

        Map<String, String> reference = I18n.bundle(Language.DEFAULT);
        check("the reference bundle is not empty", !reference.isEmpty());

        for (Language language : Language.all()) {
            Map<String, String> bundle = I18n.bundle(language);
            String code = language.code();

            check(code + ": has every key", bundle.keySet().containsAll(reference.keySet()));
            check(code + ": has no extra key", reference.keySet().containsAll(bundle.keySet()));

            boolean blanks = bundle.values().stream().anyMatch(String::isBlank);
            check(code + ": no blank value", !blanks);

            boolean placeholdersMatch = reference.entrySet().stream()
                    .filter(entry -> bundle.containsKey(entry.getKey()))
                    .allMatch(entry -> placeholders(entry.getValue())
                            .equals(placeholders(bundle.get(entry.getKey()))));
            check(code + ": placeholders match the reference", placeholdersMatch);

            // Formatting each pattern for real catches what a textual check
            // cannot: in MessageFormat a lone apostrophe starts a quoted run and
            // silently swallows the placeholder after it. Ukrainian, Polish and
            // French all use apostrophes in ordinary words, so this is the
            // mistake a translator is most likely to make.
            List<String> swallowed = new ArrayList<>();
            for (Map.Entry<String, String> entry : bundle.entrySet()) {
                var used = placeholders(entry.getValue());
                if (used.isEmpty()) {
                    continue;
                }
                Object[] arguments = new Object[used.stream().mapToInt(Integer::parseInt).max()
                        .orElse(0) + 1];
                for (int i = 0; i < arguments.length; i++) {
                    arguments[i] = "<A" + i + ">";
                }
                String formatted = new java.text.MessageFormat(entry.getValue(), language.locale())
                        .format(arguments);
                for (String index : used) {
                    if (!formatted.contains("<A" + index + ">")) {
                        swallowed.add(entry.getKey());
                        break;
                    }
                }
            }
            check(code + ": every placeholder survives formatting " + swallowed, swallowed.isEmpty());
        }

        // The active language must survive a switch and a switch back.
        Language before = I18n.current();
        I18n.use(Language.UKRAINIAN);
        check("switching changes the active language", I18n.current() == Language.UKRAINIAN);
        check("a switched string is not the English one",
                !I18n.t("action.play").equals(reference.get("action.play")));
        I18n.use(Language.DEFAULT);
        check("switching back restores English",
                I18n.t("action.play").equals(reference.get("action.play")));
        check("arguments are substituted", I18n.t("log.installed", "26.2").contains("26.2"));
        check("an unknown key is visible, not silent", I18n.t("no.such.key").contains("no.such.key"));
        I18n.use(before);

        check("a language code resolves", Language.byCode("uk").orElseThrow() == Language.UKRAINIAN);
        check("a region suffix is ignored", Language.byCode("de-AT").orElseThrow() == Language.GERMAN);
        check("an unknown code is empty", Language.byCode("xx").isEmpty());
        check("a blank preference falls back", Language.resolve("  ") != null);
    }

    // ---------------------------------------------------------------- mod ownership

    /**
     * The rules that decide which jars the launcher may delete.
     *
     * <p>This is the only place in the launcher where a wrong answer destroys
     * something the user cannot get back from inside it: a mod they installed by
     * hand, or a world-critical mod pulled out of a set. Every branch is checked
     * here because none of them can be checked safely by trying it.
     */
    private static void modOwnership() {
        section("Mod ownership");

        check("a pack mod cannot be removed alone", !ModOrigin.PACK.isRemovableAlone());
        check("a chosen mod can", ModOrigin.MANUAL.isRemovableAlone());
        check("a dependency can", ModOrigin.DEPENDENCY.isRemovableAlone());
        check("an unknown origin reads as the user's",
                ModOrigin.parse("something-else") == ModOrigin.MANUAL);
        check("a null origin reads as the user's", ModOrigin.parse(null) == ModOrigin.MANUAL);

        check("the key is per provider and project",
                "MODRINTH:AANobbMI".equals(
                        InstalledMod.keyOf(ModProvider.Source.MODRINTH, "AANobbMI")));
        check("the same project on two platforms is two entries",
                !InstalledMod.keyOf(ModProvider.Source.MODRINTH, "x")
                        .equals(InstalledMod.keyOf(ModProvider.Source.CURSEFORGE, "x")));

        Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("hexadron-mods-check");

            // A version-1 lock had no origin field, and only the pack installer
            // ever wrote one. Reading it as "the user's" would let the next
            // click delete a mod out of the middle of the optimisation set.
            java.nio.file.Files.writeString(dir.resolve(ModLibrary.LOCK_FILE), """
                    {"version":1,"mods":{"MODRINTH:AANobbMI":{
                      "source":"MODRINTH","projectId":"AANobbMI","versionId":"v1",
                      "fileName":"sodium.jar","displayName":"Sodium 0.9.1","size":1,
                      "dependencies":[]}}}""");
            java.nio.file.Files.writeString(dir.resolve("sodium.jar"), "jar");

            ModLibrary legacy = ModLibrary.read(dir);
            check("a version-1 entry is read", legacy.size() == 1);
            InstalledMod migrated = legacy.get("MODRINTH:AANobbMI").orElseThrow();
            check("a version-1 entry is pack-owned", migrated.origin() == ModOrigin.PACK);
            check("a version-1 entry is attributed to the optimisation pack",
                    migrated.belongsTo("hexadron-optimise"));
            check("a migrated entry is protected", !migrated.origin().isRemovableAlone());
            check("the pack reads as installed", legacy.isPackInstalled("hexadron-optimise"));

            // Round-tripping must not lose ownership; if it did, one save would
            // quietly turn every pack mod into a removable one.
            legacy.put(new InstalledMod("Mine", new ModFile("P1", "mine", "v2", "Mine 1.0",
                    "mine.jar", "https://example/mine.jar", null, 2, List.of(),
                    ModProvider.Source.MODRINTH), ModOrigin.MANUAL, null));
            java.nio.file.Files.writeString(dir.resolve("mine.jar"), "jar");
            legacy.write();

            ModLibrary reread = ModLibrary.read(dir);
            check("both entries survive a save", reread.size() == 2);
            check("pack ownership survives a save",
                    reread.get("MODRINTH:AANobbMI").orElseThrow().origin() == ModOrigin.PACK);
            check("the user's mod stays the user's",
                    reread.get("MODRINTH:P1").orElseThrow().origin() == ModOrigin.MANUAL);
            check("the pack owns only its own entry", reread.ofPack("hexadron-optimise").size() == 1);
            check("titles are listed for the summary", reread.titles().size() == 2);

            // A jar deleted in a file manager must disappear from the list too,
            // rather than being reported as installed for ever.
            java.nio.file.Files.delete(dir.resolve("mine.jar"));
            ModLibrary pruned = ModLibrary.read(dir).pruneMissingFiles();
            check("an entry whose jar is gone is dropped", pruned.size() == 1);
            check("the remaining entry is the one still on disk",
                    pruned.contains(ModProvider.Source.MODRINTH, "AANobbMI"));
        } catch (IOException e) {
            check("mod library checks ran (" + e.getMessage() + ")", false);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    java.nio.file.Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing a check over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    // ---------------------------------------------------------------- loader compatibility

    /**
     * The rules that decide which Minecraft versions a loader is offered for.
     *
     * <p>An error here is not a crash, it is a version quietly missing from the
     * picker - the failure mode that is hardest to notice and hardest to report,
     * because the user cannot see what is not there.
     */
    private static void loaderCompatibility() {
        section("Loader compatibility");

        // A Forge build id carries its Minecraft version outright.
        check("forge build names its version",
                "1.20.1".equals(ForgeInstaller.minecraftVersionOf("1.20.1-47.3.0")));
        check("forge build with a suffix",
                "1.12.2".equals(ForgeInstaller.minecraftVersionOf("1.12.2-14.23.5.2860")));
        check("forge build without a dash is rejected",
                ForgeInstaller.minecraftVersionOf("47.3.0") == null);
        check("forge build ending in a dash is rejected",
                ForgeInstaller.minecraftVersionOf("1.20.1-") == null);

        check("a broken forge build is known to be broken",
                ForgeInstaller.isBroken("1.12.2-14.23.5.2851"));
        check("an ordinary forge build is not", !ForgeInstaller.isBroken("1.12.2-14.23.5.2860"));

        // NeoForge: the derivation must be the exact inverse of the filter, or
        // the picker offers a version whose builds it then hides. Both encodings
        // are checked, because both are in use - Minecraft's move to calendar
        // versioning changed the rule without retiring the old one.
        check("neoforge build maps to a patch version",
                "1.21.1".equals(NeoForgeInstaller.minecraftVersionOf("21.1.66")));
        check("neoforge build with patch 0 maps to the base version",
                "1.21".equals(NeoForgeInstaller.minecraftVersionOf("21.0.167")));
        check("neoforge build with a suffix still maps",
                "1.20.4".equals(NeoForgeInstaller.minecraftVersionOf("20.4.100-beta")));
        check("a four-part build maps to a calendar version",
                "26.1.2".equals(NeoForgeInstaller.minecraftVersionOf("26.1.2.97")));
        check("a four-part build with patch 0 drops the patch",
                "26.1".equals(NeoForgeInstaller.minecraftVersionOf("26.1.0.5-beta")));
        check("a build-metadata suffix is ignored",
                "26.1".equals(NeoForgeInstaller.minecraftVersionOf("26.1.0.0-alpha.1+snapshot-1")));
        check("the legacy artifact names its version outright",
                "1.20.1".equals(NeoForgeInstaller.minecraftVersionOf("1.20.1-47.1.106")));
        check("a snapshot build maps to nothing",
                NeoForgeInstaller.minecraftVersionOf("0.1.2.3") == null);
        check("nonsense maps to nothing", NeoForgeInstaller.minecraftVersionOf("nonsense") == null);
        check("a blank build maps to nothing", NeoForgeInstaller.minecraftVersionOf("  ") == null);

        for (String build : List.of("21.1.66", "21.0.167", "20.4.100", "26.1.2.97", "26.1.0.5")) {
            String derived = NeoForgeInstaller.minecraftVersionOf(build);
            String prefix = NeoForgeInstaller.prefixFor(derived);
            check("neoforge round trip for " + build,
                    prefix != null && build.startsWith(prefix));
        }
        check("a calendar Minecraft version now has a prefix",
                "26.1.2.".equals(NeoForgeInstaller.prefixFor("26.1.2")));
        check("a calendar version without a patch fills in zero",
                "26.1.0.".equals(NeoForgeInstaller.prefixFor("26.1")));
        // A two-part version below 26 is not calendar versioning, it is nothing
        // NeoForge ever published. Inventing a prefix for it would filter every
        // build away and report "no builds" for a version that has none anyway,
        // but by the wrong route.
        check("a pre-calendar two-part number has no prefix",
                NeoForgeInstaller.prefixFor("25.1") == null);
        check("nonsense has no prefix", NeoForgeInstaller.prefixFor("kittens") == null);

        // Every loader the interface offers must have an installer behind it.
        // Forge and NeoForge were offered without one for a while, and the only
        // symptom was an error at the end of choosing a profile.
        for (LoaderType loader : com.hexadron.launcher.install.loader.Loaders.allLoaders()) {
            if (loader == LoaderType.VANILLA) {
                continue;
            }
            boolean installed;
            try {
                installed = com.hexadron.launcher.install.loader.Loaders
                        .installerFor(loader).type() == loader;
            } catch (RuntimeException e) {
                installed = false;
            }
            check(loader.id() + " has an installer", installed);
        }

        // An incomplete list must never be used to hide a version.
        var complete = new LoaderInstaller.SupportedVersions(List.of("1.21.1", "26.2"), true);
        check("a complete list filters", complete.isUsableAsFilter());
        check("a supported version passes", complete.supports("26.2"));
        check("an unsupported version does not", !complete.supports("1.0"));

        var derived = new LoaderInstaller.SupportedVersions(List.of("1.21.1"), false);
        check("an incomplete list never filters", !derived.isUsableAsFilter());
        check("an empty complete list does not filter either",
                !new LoaderInstaller.SupportedVersions(List.of(), true).isUsableAsFilter());
        check("unknown filters nothing", !LoaderInstaller.SupportedVersions.unknown().isUsableAsFilter());
        check("vanilla is not a loader with builds", LoaderType.VANILLA.isModded() == false);
    }

    // ---------------------------------------------------------------- forge profiles

    /**
     * Reading {@code install_profile.json}.
     *
     * <p>Both shapes are checked from real documents, because the format is only
     * ever met at install time and the failure mode is silent: a profile read as
     * the wrong era produces an install that writes no patched jar and reports
     * success, and the user meets that as a crash on first launch with nothing to
     * connect it to.
     */
    private static void forgeInstallerProfiles() {
        section("Forge installer profiles");

        InstallProfile legacy = InstallProfile.parse(Json.parse("""
                {
                  "install": {
                    "profileName": "forge",
                    "target": "1.12.2-forge-14.23.5.2860",
                    "path": "net.minecraftforge:forge:1.12.2-14.23.5.2860",
                    "filePath": "forge-1.12.2-14.23.5.2860-universal.jar",
                    "minecraft": "1.12.2"
                  },
                  "versionInfo": {
                    "id": "1.12.2-forge-14.23.5.2860",
                    "inheritsFrom": "1.12.2",
                    "mainClass": "net.minecraft.launchwrapper.Launch",
                    "libraries": [
                      {"name": "net.minecraftforge:forge:1.12.2-14.23.5.2860",
                       "url": "https://files.minecraftforge.net/maven/"},
                      {"name": "net.minecraft:launchwrapper:1.12"}
                    ]
                  }
                }"""));

        check("legacy era detected", legacy.era() == InstallProfile.Era.LEGACY);
        check("legacy minecraft version", "1.12.2".equals(legacy.minecraftVersion()));
        check("legacy version id comes from versionInfo",
                "1.12.2-forge-14.23.5.2860".equals(legacy.versionId()));
        check("legacy main jar coordinate",
                "net.minecraftforge:forge:1.12.2-14.23.5.2860".equals(legacy.mainJar().toString()));
        check("legacy universal jar entry named",
                "forge-1.12.2-14.23.5.2860-universal.jar".equals(legacy.legacyJarEntry()));
        check("legacy libraries come from versionInfo", legacy.libraries().size() == 2);
        check("legacy has no processors", legacy.processors().isEmpty());
        check("legacy carries its version manifest", legacy.legacyVersionInfo().isObject());

        InstallProfile modern = InstallProfile.parse(Json.parse("""
                {
                  "spec": 1,
                  "profile": "forge",
                  "version": "1.20.1-forge-47.4.10",
                  "minecraft": "1.20.1",
                  "json": "/version.json",
                  "path": "net.minecraftforge:forge:1.20.1-47.4.10",
                  "libraries": [
                    {"name": "net.minecraftforge:binarypatcher:1.1.1",
                     "downloads": {"artifact": {
                       "path": "net/minecraftforge/binarypatcher/1.1.1/binarypatcher-1.1.1.jar",
                       "url": "https://maven.minecraftforge.net/net/minecraftforge/binarypatcher/1.1.1/binarypatcher-1.1.1.jar",
                       "sha1": "0000000000000000000000000000000000000000", "size": 1}}}
                  ],
                  "data": {
                    "BINPATCH": {"client": "/data/client.lzma", "server": "/data/server.lzma"},
                    "PATCHED": {"client": "[net.minecraftforge:forge:1.20.1-47.4.10:client]",
                                "server": "[net.minecraftforge:forge:1.20.1-47.4.10:server]"},
                    "PATCHED_SHA": {"client": "'4d8a9a63dc16a45d7fc5c54c627234f601d0cc17'",
                                    "server": "'0000000000000000000000000000000000000000'"}
                  },
                  "processors": [
                    {"sides": ["server"],
                     "jar": "net.minecraftforge:installertools:1.4.1",
                     "classpath": [],
                     "args": ["--task", "EXTRACT_FILES", "--archive", "{INSTALLER}"]},
                    {"jar": "net.minecraftforge:binarypatcher:1.1.1",
                     "classpath": ["net.minecraftforge:binarypatcher:1.1.1"],
                     "args": ["--clean", "{MINECRAFT_JAR}", "--output", "{PATCHED}",
                              "--apply", "{BINPATCH}"],
                     "outputs": {"{PATCHED}": "{PATCHED_SHA}"}}
                  ]
                }"""));

        check("modern era detected", modern.era() == InstallProfile.Era.MODERN);
        check("modern spec read", modern.spec() == 1);
        check("modern version manifest entry named",
                "/version.json".equals(modern.versionJsonEntry()));
        check("modern libraries are the processors' own", modern.libraries().size() == 1);
        check("both processors read", modern.processors().size() == 2);

        ForgeProcessor serverOnly = modern.processors().get(0);
        ForgeProcessor both = modern.processors().get(1);
        check("a server-only step is skipped on the client", !serverOnly.appliesToSide("client"));
        check("a server-only step runs on the server", serverOnly.appliesToSide("server"));
        // An absent sides array means every side. Reading it as "no side" is the
        // one mistake that silently installs nothing at all.
        check("a step with no sides runs on the client", both.appliesToSide("client"));
        check("a step with no sides runs on the server", both.appliesToSide("server"));
        check("classpath parsed", both.classpath().size() == 1);
        check("args kept in order", both.args().get(0).equals("--clean"));
        check("outputs parsed", both.outputs().get("{PATCHED}").equals("{PATCHED_SHA}"));

        check("a data entry can be a path inside the installer",
                "/data/client.lzma".equals(modern.data().get("BINPATCH").forSide("client")));
        check("a data entry differs per side",
                "/data/server.lzma".equals(modern.data().get("BINPATCH").forSide("server")));

        // Forge 1.12.2-14.23.5.2851 really ships this. An installer with one
        // malformed field must still be readable, or a whole build becomes
        // uninstallable over a field the client install never uses.
        InstallProfile wrongDataType = InstallProfile.parse(Json.parse("""
                {"spec": 0, "version": "x", "minecraft": "1.12.2", "data": [],
                 "libraries": [], "processors": []}"""));
        check("a data block of the wrong type reads as empty",
                wrongDataType.data().isEmpty());
        check("a profile with no path has no main jar", wrongDataType.mainJar() == null);

        checkThrows("a profile of neither shape is rejected",
                () -> InstallProfile.parse(Json.parse("{\"something\": 1}")));
        checkThrows("a processor with no jar is rejected",
                () -> InstallProfile.parse(Json.parse(
                        "{\"spec\":1,\"processors\":[{\"args\":[]}]}")));
    }

    // ---------------------------------------------------------------- forge tokens

    /**
     * The substitution language the processor arguments are written in.
     *
     * <p>Small, and every rule in it has a failure that looks like something
     * else. An unresolved token becomes a file named {@code {MAPPINGS}} and the
     * step reports success. A token that only works when it stands alone breaks
     * {@code {ROOT}/libraries/} without saying so.
     */
    private static void forgeTokenLanguage() {
        section("Forge token language");

        Map<String, String> tokens = Map.of(
                "ROOT", "/games/minecraft",
                "SIDE", "client",
                "PATCHED_SHA", "4d8a9a63dc16a45d7fc5c54c627234f601d0cc17");

        check("a token is replaced",
                "/games/minecraft".equals(Tokens.replaceTokens(tokens, "{ROOT}")));
        check("a token is replaced in the middle of a value",
                "/games/minecraft/libraries/".equals(
                        Tokens.replaceTokens(tokens, "{ROOT}/libraries/")));
        check("two tokens in one value",
                "client:/games/minecraft".equals(Tokens.replaceTokens(tokens, "{SIDE}:{ROOT}")));
        check("a value with no tokens is unchanged",
                "--task".equals(Tokens.replaceTokens(tokens, "--task")));
        check("a quoted literal loses its quotes",
                "MCP_DATA".equals(Tokens.replaceTokens(tokens, "'MCP_DATA'")));
        check("an escaped brace is literal",
                "{ROOT}".equals(Tokens.replaceTokens(tokens, "\\{ROOT}")));

        checkThrows("an unknown key is fatal",
                () -> Tokens.replaceTokens(tokens, "{NOT_A_TOKEN}"));
        checkThrows("an unterminated token is fatal",
                () -> Tokens.replaceTokens(tokens, "{ROOT"));
        checkThrows("an unterminated literal is fatal",
                () -> Tokens.replaceTokens(tokens, "'text"));
        checkThrows("a trailing escape is fatal",
                () -> Tokens.replaceTokens(tokens, "text\\"));

        java.util.function.Function<MavenCoordinate, String> library =
                coordinate -> "/libs/" + coordinate.path();

        check("a whole-value coordinate becomes a path",
                "/libs/org/ow2/asm/asm/9.7/asm-9.7.jar".equals(
                        Tokens.resolve(tokens, "[org.ow2.asm:asm:9.7]", library)));
        check("a coordinate with a classifier and extension becomes a path",
                "/libs/net/minecraft/client/1.20.1/client-1.20.1-mappings.txt".equals(
                        Tokens.resolve(tokens, "[net.minecraft:client:1.20.1:mappings@txt]", library)));
        check("an output hash resolves through its token",
                "4d8a9a63dc16a45d7fc5c54c627234f601d0cc17".equals(
                        Tokens.resolve(tokens, "{PATCHED_SHA}", library)));

        check("a data value that is a coordinate becomes a path",
                "/libs/net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-client.jar"
                        .equals(Tokens.resolveDataValue(
                                "[net.minecraftforge:forge:1.20.1-47.4.10:client]",
                                library, name -> "unused")));
        check("a data value that is a literal keeps its text",
                "20230612.114412".equals(Tokens.resolveDataValue(
                        "'20230612.114412'", library, name -> "unused")));
        // The third form, and the one that carries the binary patch itself.
        check("a data value that is neither is extracted from the installer",
                "/tmp/data/client.lzma".equals(Tokens.resolveDataValue(
                        "/data/client.lzma", library, name -> "/tmp" + name)));
    }

    // ---------------------------------------------------------------- curseforge key

    /**
     * Where the CurseForge key comes from, and where it is allowed to go.
     *
     * <p>Two properties are asserted, and they are the whole design:
     * <ul>
     *   <li>no key means the platform switches itself off, rather than staying
     *       listed and failing every request;</li>
     *   <li>the key is sent to CurseForge's own hosts - the API host and every
     *       host that serves its files - and to nothing else.</li>
     * </ul>
     * The second one is not obvious. CurseForge began requiring the key on its
     * content hosts in 2026, and a launcher that lists only one of those hosts
     * fails on the files served from the others, which reads as a dead mirror.
     */
    private static void curseForgeKeyHandling() {
        section("CurseForge key handling");

        CurseForgeProvider provider = new CurseForgeProvider(null);
        check("no key means unavailable", !provider.isAvailable());
        check("no key is reported as such",
                provider.keySource() == CurseForgeProvider.KeySource.NONE);
        check("a blank key is no key", !new CurseForgeProvider("   ").isAvailable());

        URI apiHost = URI.create("https://api.curseforge.com/v1/mods/search");
        URI edgeHost = URI.create("https://edge.forgecdn.net/files/1/2/mod.jar");
        URI mediaHost = URI.create("https://mediafilez.forgecdn.net/files/1/2/mod.jar");
        URI modrinthHost = URI.create("https://api.modrinth.com/v2/search");

        check("without a key nothing is added to a curseforge request",
                Http.hostHeadersFor(apiHost).isEmpty());

        provider.apiKey("selfcheck-not-a-real-key-000000");
        check("a key makes the provider available", provider.isAvailable());
        check("a key set at runtime is attributed to the settings",
                provider.keySource() == CurseForgeProvider.KeySource.SETTINGS);
        check("the api host gets the key",
                "selfcheck-not-a-real-key-000000"
                        .equals(Http.hostHeadersFor(apiHost).get("x-api-key")));
        check("the edge content host gets the key",
                Http.hostHeadersFor(edgeHost).containsKey("x-api-key"));
        check("the other content host gets it too",
                Http.hostHeadersFor(mediaHost).containsKey("x-api-key"));
        check("modrinth does not get it", Http.hostHeadersFor(modrinthHost).isEmpty());

        check("the api host is recognised",
                CurseForgeProvider.isCurseForgeHost("api.curseforge.com"));
        check("host matching ignores case",
                CurseForgeProvider.isCurseForgeHost("API.CurseForge.com"));
        check("every content subdomain is recognised",
                CurseForgeProvider.isCurseForgeHost("media.forgecdn.net"));
        // Suffix matching has to be on a dot boundary, or a host somebody else
        // registered receives the key.
        check("a look-alike host is not recognised",
                !CurseForgeProvider.isCurseForgeHost("evil-forgecdn.net"));
        check("a host that merely contains the name is not recognised",
                !CurseForgeProvider.isCurseForgeHost("api.curseforge.com.example.org"));
        check("modrinth is not a curseforge host",
                !CurseForgeProvider.isCurseForgeHost("api.modrinth.com"));
        check("a null host is not a curseforge host",
                !CurseForgeProvider.isCurseForgeHost(null));

        provider.apiKey("");
        check("clearing the key switches the platform off", !provider.isAvailable());
        check("clearing the key stops the header being sent",
                Http.hostHeadersFor(apiHost).isEmpty());

        // The key is a credential, and every credential the launcher holds is
        // masked before anything is logged.
        check("the key is masked in log output",
                !Redactor.scrub("x-api-key: selfcheck-not-a-real-key-000000")
                        .contains("selfcheck-not-a-real-key-000000"));

        check("a build with no key reports none",
                BuildConfig.hasCurseForgeApiKey() == !BuildConfig.curseForgeApiKey().isEmpty());
    }

    // ---------------------------------------------------------------- search paging

    /**
     * Paging arithmetic for the mod browser.
     *
     * <p>Before paging existed the browser asked for 40 results and showed 40,
     * for every Minecraft version and every loader, which read as "there are 40
     * mods". These checks cover the boundary where "show more" must and must not
     * appear.
     */
    private static void searchPaging() {
        section("Search paging");

        var first = new ModProvider.SearchPage(List.of(hit("a"), hit("b")), 10, 0);
        check("a first page of a larger set has more", first.hasMore());

        var last = new ModProvider.SearchPage(List.of(hit("a"), hit("b")), 10, 8);
        check("the last page has no more", !last.hasMore());

        var exact = new ModProvider.SearchPage(List.of(hit("a")), 1, 0);
        check("a single-result set has no more", !exact.hasMore());

        var unknownTotal = new ModProvider.SearchPage(List.of(hit("a")), -1, 0);
        check("an unreported total assumes more while results keep arriving",
                unknownTotal.hasMore());
        check("an unreported total stops at an empty page",
                !new ModProvider.SearchPage(List.of(), -1, 40).hasMore());
        check("an empty result set has no more", !ModProvider.SearchPage.empty().hasMore());
        check("the page remembers where it started", last.offset() == 8);

        // A platform that was asked and did not answer has to be visible. The
        // failure mode it prevents is silent: fewer results, no reason given, and
        // a user concluding the mod does not exist for their version when in fact
        // the key was refused.
        check("a page from every platform is not partial", !first.isPartial());
        var partial = new ModProvider.SearchPage(List.of(hit("a")), 1, 0,
                List.of("CurseForge: HTTP 403 - the API key was refused"));
        check("a page missing a platform is partial", partial.isPartial());
        check("the missing platform is named",
                partial.unavailable().get(0).startsWith("CurseForge"));
        check("results still come through", partial.results().size() == 1);

        // What a failed platform is allowed to say. The raw exception message
        // carries the whole request URL, which is not something a user typed or
        // can act on, and the HTTP code on its own explains nothing.
        String refused = ModInstaller.reasonFor(
                new Http.HttpStatusException(403,
                        "https://api.curseforge.com/v1/mods/search?gameId=432&classId=6",
                        "Forbidden: API Key missing or invalid"));
        check("403 is explained as the key", refused.contains("API key was refused"));
        check("the request url is not repeated at the user", !refused.contains("gameId"));
        check("429 is explained as rate limiting",
                ModInstaller.reasonFor(new Http.HttpStatusException(429, "https://x/y", ""))
                        .contains("too many requests"));
        check("an unmapped status still names its code",
                ModInstaller.reasonFor(new Http.HttpStatusException(500, "https://x/y", ""))
                        .equals("HTTP 500"));
        check("a plain failure keeps its own message",
                "connection reset".equals(
                        ModInstaller.reasonFor(new IOException("connection reset"))));
    }

    private static ModProvider.SearchResult hit(String id) {
        return new ModProvider.SearchResult(id, id, id, "", "", 0, null,
                ModProvider.Source.MODRINTH);
    }

    // ---------------------------------------------------------------- names and arguments

    /**
     * The player-name rule, and the argument splitter that feeds the JVM.
     *
     * <p>Both sit between a text field and something that fails far away from
     * it: a rejected name surfaces inside the game as a lost connection to the
     * player's own world, and a mis-split argument list surfaces as a JVM that
     * refuses to start.
     */
    private static void playerNamesAndArguments() {
        section("Player names");

        check("plain name", Account.isValidUsername("Steve"));
        check("digits and underscore", Account.isValidUsername("San4ez_2026"));
        check("shortest allowed", Account.isValidUsername("abc"));
        check("longest allowed", Account.isValidUsername("abcdefghijklmnop"));
        check("too short", !Account.isValidUsername("ab"));
        check("too long", !Account.isValidUsername("abcdefghijklmnopq"));
        check("Cyrillic rejected", !Account.isValidUsername("Гравець"));
        check("space rejected", !Account.isValidUsername("Some One"));
        check("dash rejected", !Account.isValidUsername("some-one"));
        check("empty rejected", !Account.isValidUsername(""));
        check("null rejected", !Account.isValidUsername(null));

        checkThrows("an offline account refuses a rejected name",
                () -> Account.offline("Гравець"));
        check("an offline account accepts a valid name",
                "Steve".equals(Account.offline("Steve").username()));
        check("the offline UUID matches the server's derivation",
                UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .equals(Account.offline("Steve").uuid()));
        // A stored account is read back even when its name is one the game
        // rejects, so the user can select a different one and delete it.
        check("a stored bad name still parses",
                "Гравець".equals(Account.fromLegacyJson(Json.parse("""
                        {"type":"OFFLINE","username":"Гравець",
                         "uuid":"00000000-0000-0000-0000-000000000001"}""")).username()));

        section("Argument splitting");

        check("empty is empty", com.hexadron.launcher.util.Arguments.split("  ").isEmpty());
        check("plain split", com.hexadron.launcher.util.Arguments.split("-Xss1M  -XX:+UseG1GC")
                .equals(List.of("-Xss1M", "-XX:+UseG1GC")));
        check("quoted group stays whole",
                com.hexadron.launcher.util.Arguments.split("-Dpath=\"C:\\Program Files\\x\" -Xmx2G")
                        .equals(List.of("-Dpath=C:\\Program Files\\x", "-Xmx2G")));
        check("join quotes what needs it",
                "-Xmx2G \"-Da=b c\"".equals(
                        com.hexadron.launcher.util.Arguments.join(List.of("-Xmx2G", "-Da=b c"))));
        check("split and join round-trip",
                com.hexadron.launcher.util.Arguments.split(
                        com.hexadron.launcher.util.Arguments.join(List.of("-Xmx2G", "-Da=b c")))
                        .equals(List.of("-Xmx2G", "-Da=b c")));
    }

    /** The set of {@code {0}}-style indices used by a MessageFormat pattern. */
    private static java.util.Set<String> placeholders(String pattern) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{(\\d+)").matcher(pattern);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    // ---------------------------------------------------------------- security

    /**
     * The authentication hardening, checked the same way as everything else:
     * offline, with no network and no display.
     *
     * <p>These are the assertions that would otherwise only be tested by a real
     * sign-in against Microsoft, which cannot run in CI. Each one corresponds to
     * a specific requirement rather than to a line of code - PKCE against RFC
     * 7636's own test vector, the state check against authorization code
     * injection, the redactor against the crash-log leak, the placeholder
     * against the process table.
     */
    private static void securityHardening() {
        section("Authentication hardening");

        // -- PKCE, against the test vector in RFC 7636 appendix B.
        check("PKCE S256 matches the RFC 7636 vector",
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".equals(
                        com.hexadron.launcher.auth.Pkce.challengeFor(
                                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")));

        var pkce = com.hexadron.launcher.auth.Pkce.generate();
        check("the verifier is at least the 43 characters RFC 7636 requires",
                pkce.verifier().length() >= 43 && pkce.verifier().length() <= 128);
        check("the verifier uses only unreserved characters",
                pkce.verifier().matches("[A-Za-z0-9._~-]+"));
        check("two verifiers differ",
                !pkce.verifier().equals(com.hexadron.launcher.auth.Pkce.generate().verifier()));
        check("the challenge is derived from the verifier",
                pkce.challenge().equals(
                        com.hexadron.launcher.auth.Pkce.challengeFor(pkce.verifier())));
        check("a matching state is accepted", pkce.matchesState(pkce.state()));
        check("a wrong state is refused", !pkce.matchesState("something-else"));
        check("a missing state is refused", !pkce.matchesState(null));
        check("the verifier never appears in toString",
                !pkce.toString().contains(pkce.verifier()));

        // -- The authorization request itself.
        var auth = new com.hexadron.launcher.auth.MicrosoftAuth(
                "00000000-1111-2222-3333-444444444444");
        String url = auth.buildAuthorizeUrl(pkce, "http://127.0.0.1:51234/");
        check("the authorization endpoint is HTTPS", url.startsWith("https://"));
        check("the authorization request carries an S256 challenge",
                url.contains("code_challenge_method=S256") && url.contains("code_challenge="));
        check("the authorization request carries a state", url.contains("state="));
        check("the authorization request asks for a code, not a token",
                url.contains("response_type=code"));
        check("the authorization request never carries a client secret",
                !url.contains("client_secret"));
        check("the verifier is never sent to the authorization endpoint",
                !url.contains(pkce.verifier()));
        check("the redirect is loopback by IP literal",
                url.contains("127.0.0.1") && !url.contains("localhost"));
        check("only the Xbox sign-in scope is requested",
                url.contains("XboxLive.signin") && !url.contains("openid") && !url.contains("email"));

        // -- Redaction. Registered values and unregistered token shapes both.
        String token = "M.C531_BAY.0.U.-Cq3zEXAMPLEtokenvaluethatislongenough";
        com.hexadron.launcher.util.Redactor.register(token);
        check("a registered secret is removed from a log line",
                !com.hexadron.launcher.util.Redactor
                        .scrub("--accessToken " + token).contains(token));
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1";
        check("an unregistered JWT is removed by shape",
                !com.hexadron.launcher.util.Redactor.scrub("Bearer " + jwt).contains(jwt));
        check("a legacy session argument is removed",
                !com.hexadron.launcher.util.Redactor
                        .scrub("--session token:" + jwt + ":uuid").contains(jwt));
        check("an authorization code in a URL is removed",
                !com.hexadron.launcher.util.Redactor
                        .scrub("GET /?code=M.C531_SN1.2.U.abcdefghijklmnop&state=x")
                        .contains("abcdefghijklmnop"));
        check("ordinary text is left alone",
                "installing 42 libraries".equals(
                        com.hexadron.launcher.util.Redactor.scrub("installing 42 libraries")));
        check("a short value is not registered as a secret",
                "ok".equals(com.hexadron.launcher.util.Redactor.scrub("ok")));
        // A value with no token shape: masked only while it is registered. This
        // is what proves the two layers are independent, and it is also why the
        // Microsoft-shaped token above stays masked after being forgotten - the
        // shape rule catches it regardless, which is the intended behaviour.
        String opaque = "plain-registered-value-0123456789";
        com.hexadron.launcher.util.Redactor.register(opaque);
        check("an opaque registered secret is masked",
                !com.hexadron.launcher.util.Redactor.scrub(opaque).contains(opaque));
        com.hexadron.launcher.util.Redactor.forget(opaque);
        check("a forgotten opaque secret stops being masked",
                com.hexadron.launcher.util.Redactor.scrub(opaque).equals(opaque));
        com.hexadron.launcher.util.Redactor.forget(token);
        check("a Microsoft-shaped token stays masked even after being forgotten",
                !com.hexadron.launcher.util.Redactor.scrub(token).contains(token));

        // -- The launch handshake. The placeholder must be something no path,
        // argument or mod identifier could collide with.
        String placeholder = LaunchCommandBuilder.ACCESS_TOKEN_PLACEHOLDER;
        check("the token placeholder is distinctive",
                placeholder.startsWith("%%") && placeholder.endsWith("%%")
                        && placeholder.contains("HEXADRON"));
        check("the placeholder is not a valid token shape",
                com.hexadron.launcher.util.Redactor.scrub(placeholder).equals(placeholder));
    }

    // ---------------------------------------------------------------- harness

    private static void section(String name) {
        System.out.println("-- " + name);
    }

    private static void check(String description, boolean condition) {
        checks++;
        if (!condition) {
            failures.add(description);
            System.out.println("   FAIL  " + description);
        }
    }

    private static void checkThrows(String description, Runnable action) {
        checks++;
        try {
            action.run();
            failures.add(description + " (expected an exception, none thrown)");
            System.out.println("   FAIL  " + description + " (no exception)");
        } catch (RuntimeException expected) {
            // correct
        }
    }

    private SelfCheck() {
    }
}
