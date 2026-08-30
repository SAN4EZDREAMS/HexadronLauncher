package com.hexadron.launcher;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.core.VerifiedFiles;
import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.install.NativesExtractor;
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
import com.hexadron.launcher.launch.JavaProvisioner;
import com.hexadron.launcher.launch.JavaRuntimes;
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
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileLayout;
import com.hexadron.launcher.skin.LocalSkinService;
import com.hexadron.launcher.skin.PngSize;
import com.hexadron.launcher.skin.SkinLayout;
import com.hexadron.launcher.skin.SkinProfile;
import com.hexadron.launcher.skin.SkinSession;
import com.hexadron.launcher.skin.SkinStore;
import com.hexadron.launcher.util.Archives;
import com.hexadron.launcher.util.Hashes;
import com.hexadron.launcher.util.Arguments;
import com.hexadron.launcher.util.MavenCoordinate;
import com.hexadron.launcher.util.Platform;
import com.hexadron.launcher.util.Redactor;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        wrapperCommand();
        assetIndexParsing();
        accounts();
        securityHardening();
        javaVersionParsing();
        javaRuntimeSelection();
        archiveExtraction();
        applicationIcons();
        versionManifestParsing();
        playerNamesAndArguments();
        modOwnership();
        loaderCompatibility();
        forgeInstallerProfiles();
        forgeTokenLanguage();
        curseForgeKeyHandling();
        searchPaging();
        profileArrangement();
        profileIconValues();
        offlineRelaunch();
        verificationLedger();
        nativesReuse();
        skins();
        skinLayout();
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

    // ---------------------------------------------------------------- wrapper

    /**
     * The wrapper command: the launcher's one honest sandboxing offer.
     *
     * <p>Two properties are checked here, and both are the kind that only a test
     * notices. First, an absent wrapper must leave the command byte-identical to
     * what it was before the feature existed - a launch path that changes shape
     * for everyone in order to serve the few who set a wrapper is a regression
     * dressed as a feature. Second, the wrapper must come first and the java
     * executable immediately after it, because that ordering is the whole
     * mechanism: bwrap, firejail, prime-run, gamemoderun and mangohud all work
     * by being the parent process of the JVM.
     */
    private static void wrapperCommand() {
        section("Wrapper command");

        Path root = Paths.get(System.getProperty("java.io.tmpdir"), "hexadron-selfcheck");
        GameDirs dirs = new GameDirs(root);
        LaunchCommandBuilder builder = new LaunchCommandBuilder(dirs);

        VersionJson version = VersionJson.parse(Json.parse("""
                {
                  "id": "1.20.1",
                  "mainClass": "net.minecraft.client.main.Main",
                  "assets": "5",
                  "minecraftArguments": "--username ${auth_player_name}",
                  "downloads": {"client": {"url": "https://example/c.jar", "sha1": "aa", "size": 1}},
                  "libraries": []
                }"""));

        Account steve = Account.offline("Steve");
        JavaLocator.JavaRuntime java = new JavaLocator.JavaRuntime(
                Paths.get("/usr/bin/java"), 21, "selfcheck");
        Path gameDir = root.resolve("instances/test");
        Path assets = dirs.assets();

        Profile plain = Profile.create("Plain", "1.20.1", LoaderType.VANILLA);
        List<String> without =
                builder.build(version, plain, steve, gameDir, assets, java, null).command();

        check("with no wrapper the java executable is still first",
                without.get(0).equals(java.executable().toString()));

        // Set, then cleared. A field that only ever holds "" and a field that
        // holds null must produce the same command, or clearing the box in the
        // dialog would leave a wrapper behind.
        Profile cleared = Profile.create("Cleared", "1.20.1", LoaderType.VANILLA)
                .wrapperCommand("bwrap --unshare-net")
                .wrapperCommand("   ");
        check("a blank wrapper is stored as absent", cleared.wrapperCommand() == null);
        check("clearing the wrapper restores the original command",
                builder.build(version, cleared, steve, gameDir, assets, java, null).command()
                        .equals(without));

        Profile wrapped = Profile.create("Wrapped", "1.20.1", LoaderType.VANILLA)
                .wrapperCommand("bwrap --unshare-net --die-with-parent");
        List<String> with =
                builder.build(version, wrapped, steve, gameDir, assets, java, null).command();

        check("the wrapper program is the process that starts", with.get(0).equals("bwrap"));
        check("the wrapper's own arguments follow it in order",
                with.subList(0, 3).equals(List.of("bwrap", "--unshare-net", "--die-with-parent")));
        check("the java executable comes straight after the wrapper",
                with.get(3).equals(java.executable().toString()));
        check("nothing else about the launch changed",
                with.subList(3, with.size()).equals(without));

        // Quoting, because a bwrap bind mount is the first thing anybody types
        // here and Windows paths have spaces in them.
        Profile quoted = Profile.create("Quoted", "1.20.1", LoaderType.VANILLA)
                .wrapperCommand("firejail \"--whitelist=C:\\Program Files\\Minecraft\"");
        check("a quoted wrapper argument stays one argument",
                builder.build(version, quoted, steve, gameDir, assets, java, null).command()
                        .subList(0, 2)
                        .equals(List.of("firejail", "--whitelist=C:\\Program Files\\Minecraft")));

        // Persistence. The whole point of the field is that it survives a
        // restart, and it is written by hand rather than through a picker, so a
        // silent loss on save would look like the launcher ignoring the setting.
        Profile reloaded = Profile.fromJson(wrapped.toJson());
        check("the wrapper survives a save and reload",
                "bwrap --unshare-net --die-with-parent".equals(reloaded.wrapperCommand()));
        check("a profile written without a wrapper reads back as absent",
                Profile.fromJson(plain.toJson()).wrapperCommand() == null);
        check("no wrapper key is written when there is no wrapper",
                !plain.toJson().toString().contains("wrapperCommand"));

        check("the split used by the launch is the same one the dialog uses",
                Arguments.split(wrapped.wrapperCommand())
                        .equals(List.of("bwrap", "--unshare-net", "--die-with-parent")));

        // The one failure a wrapper actually causes, and the one the user has no
        // way to guess. Exit 92 is the launch handshake, the handshake is stdin,
        // and stdin is exactly what a wrapper can drop.
        String withWrapper = com.hexadron.launcher.launch.GameLauncher
                .describeExit(92, "bwrap --unshare-all");
        check("a handshake failure under a wrapper names the wrapper",
                withWrapper.contains("bwrap --unshare-all"));
        check("a handshake failure under a wrapper says what to try",
                withWrapper.contains("standard input") && withWrapper.contains("Clear"));
        check("a handshake failure with no wrapper does not invent one",
                !com.hexadron.launcher.launch.GameLauncher.describeExit(92, null)
                        .contains("wrapper"));
        check("a blank wrapper is not blamed either",
                com.hexadron.launcher.launch.GameLauncher.describeExit(92, "  ")
                        .equals(com.hexadron.launcher.launch.GameLauncher.describeExit(92)));
        // Only exit 92. A wrapper set on a profile that crashed for an unrelated
        // reason must not turn every crash report into a wrapper report.
        check("an ordinary crash is not blamed on the wrapper",
                !com.hexadron.launcher.launch.GameLauncher.describeExit(1, "bwrap")
                        .contains("bwrap"));
        check("a clean exit is not blamed on the wrapper",
                com.hexadron.launcher.launch.GameLauncher.describeExit(0, "bwrap")
                        .equals(com.hexadron.launcher.launch.GameLauncher.describeExit(0)));
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

    /**
     * The rule that decides which installed runtime a version runs on.
     *
     * <p>Worth a test of its own because it is the difference between a 1.19
     * Forge profile starting on the Java 17 it was built against and starting on
     * whatever newest JVM the machine happens to carry.
     */
    private static void javaRuntimeSelection() {
        section("Java runtime selection");

        java.nio.file.Path anywhere = java.nio.file.Paths.get("java");
        List<JavaLocator.JavaRuntime> runtimes = List.of(
                new JavaLocator.JavaRuntime(anywhere, 8, "test"),
                new JavaLocator.JavaRuntime(anywhere, 25, "test"),
                new JavaLocator.JavaRuntime(anywhere, 17, "test"),
                new JavaLocator.JavaRuntime(anywhere, 21, "test"));

        check("exact major wins over a newer one",
                JavaLocator.choose(runtimes, 17).orElseThrow().majorVersion() == 17);
        check("lowest satisfying wins when there is no exact match",
                JavaLocator.choose(runtimes, 18).orElseThrow().majorVersion() == 21);
        check("nothing old enough is refused",
                JavaLocator.choose(List.of(new JavaLocator.JavaRuntime(anywhere, 8, "test")), 17)
                        .isEmpty());
        check("an exact match is noticed", JavaLocator.hasExactly(runtimes, 25));
        check("a missing major is not invented", !JavaLocator.hasExactly(runtimes, 11));
        check("the Java 8 case reports what it found",
                JavaLocator.describeMissing(17, List.of(
                        new JavaLocator.JavaRuntime(anywhere, 8, "C:\\Program Files (x86)\\Java")))
                        .contains("Java 8 at"));
        check("the empty case says so",
                JavaLocator.describeMissing(25, List.of())
                        .contains("No Java installation was detected at all"));

        check("policy words map to always",
                JavaRuntimes.DownloadPolicy.parse("always") == JavaRuntimes.DownloadPolicy.ALWAYS);
        check("policy words map to never",
                JavaRuntimes.DownloadPolicy.parse("never") == JavaRuntimes.DownloadPolicy.NEVER);
        check("an unknown policy falls back to asking",
                JavaRuntimes.DownloadPolicy.parse("banana") == JavaRuntimes.DownloadPolicy.ASK);
        check("a null policy falls back to asking",
                JavaRuntimes.DownloadPolicy.parse(null) == JavaRuntimes.DownloadPolicy.ASK);
        check("policy survives a round trip through settings",
                JavaRuntimes.DownloadPolicy.parse(
                        JavaRuntimes.DownloadPolicy.NEVER.stored()) == JavaRuntimes.DownloadPolicy.NEVER);

        check("this host maps to an Adoptium os",
                List.of("windows", "mac", "linux").contains(JavaProvisioner.adoptiumOs()));
        check("this host maps to an Adoptium architecture",
                List.of("x64", "x32", "aarch64", "arm").contains(JavaProvisioner.adoptiumArch()));
    }

    /**
     * Unpacking a runtime archive.
     *
     * <p>Two properties matter and neither is visible from the outside until it
     * has already gone wrong: the wrapper directory has to be stripped, so that
     * bin/java lands where the locator looks for it, and an entry that points
     * outside the target has to be refused rather than written.
     */
    private static void archiveExtraction() {
        section("Archive extraction");

        java.nio.file.Path work = null;
        try {
            work = java.nio.file.Files.createTempDirectory("hexadron-selfcheck");

            java.nio.file.Path zip = work.resolve("runtime.zip");
            try (var out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
                out.putNextEntry(new java.util.zip.ZipEntry("jdk-17.0.1+1-jre/bin/java"));
                out.write("binary".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
                out.putNextEntry(new java.util.zip.ZipEntry("jdk-17.0.1+1-jre/release"));
                out.write("JAVA_VERSION=\"17.0.1\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }

            java.nio.file.Path target = work.resolve("out");
            Archives.extract(zip, target, 1);
            check("the wrapper directory is stripped",
                    java.nio.file.Files.isRegularFile(target.resolve("bin").resolve("java")));
            check("files beside it come across",
                    java.nio.file.Files.isRegularFile(target.resolve("release")));

            java.nio.file.Path escaping = work.resolve("escaping.zip");
            try (var out = new java.util.zip.ZipOutputStream(
                    java.nio.file.Files.newOutputStream(escaping))) {
                out.putNextEntry(new java.util.zip.ZipEntry("wrapper/../../escaped.txt"));
                out.write("no".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            java.nio.file.Path guarded = work.resolve("guarded");
            boolean refused = false;
            try {
                Archives.extract(escaping, guarded, 1);
            } catch (IOException expected) {
                refused = true;
            }
            check("an entry pointing outside the target is refused", refused);
            check("and nothing was written outside it",
                    !java.nio.file.Files.exists(work.resolve("escaped.txt")));
        } catch (IOException e) {
            check("archive extraction ran: " + e.getMessage(), false);
        } finally {
            if (work != null) {
                try {
                    Archives.deleteRecursively(work);
                } catch (IOException ignored) {
                    // A leftover temp directory is not a failed check.
                }
            }
        }
    }

    /**
     * The window icon resources.
     *
     * <p>Here because the failure is silent. A missing icon resource is not an
     * error at runtime - the window simply gets the platform's generic
     * application icon, which is what happened once already and was noticed by
     * eye rather than by anything in the build.
     *
     * <p>The PNG header is parsed rather than the file merely being opened,
     * because a resource that is present and truncated fails exactly the same
     * way as one that is absent. Done by hand rather than through
     * {@code javafx.scene.image.Image}: this runs headless, and nothing here
     * should need a graphics toolkit.
     */
    private static void applicationIcons() {
        section("Application icons");

        for (int size : new int[]{16, 24, 32, 48, 64, 128}) {
            String path = "/ui/icon/icon-" + size + ".png";
            byte[] header = readHeader(path, 24);
            if (header == null) {
                check("window icon " + size + " ships in the jar", false);
                continue;
            }
            boolean isPng = header.length == 24
                    && (header[0] & 0xFF) == 0x89
                    && header[1] == 'P' && header[2] == 'N' && header[3] == 'G';
            check("window icon " + size + " is a PNG", isPng);
            if (!isPng) {
                continue;
            }
            // IHDR is always the first chunk: width and height are big-endian
            // 32-bit values at offsets 16 and 20.
            int width = readInt(header, 16);
            int height = readInt(header, 20);
            check("window icon " + size + " is " + size + "x" + size,
                    width == size && height == size);
        }
    }

    private static byte[] readHeader(String resource, int count) {
        try (java.io.InputStream in = SelfCheck.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            byte[] header = in.readNBytes(count);
            return header.length == count ? header : new byte[0];
        } catch (IOException e) {
            return null;
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
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

    // ---------------------------------------------------------------- arrangement

    /**
     * The one arrangement both profile interfaces draw.
     *
     * <p>Checked here because it is the only place the two views can disagree.
     * They hold no order of their own - a drag in either calls the methods below
     * and both are rebuilt from the result - so if the model keeps its
     * invariants, the list and the grid cannot get out of step, and if it does
     * not, they will and nothing on screen will say why.
     *
     * <p>The invariant that matters: every profile appears exactly once, whether
     * it is loose or in a group, however it got there.
     */
    private static void profileArrangement() {
        section("Profile arrangement");

        List<Profile> profiles = new ArrayList<>();
        for (String name : List.of("Zeta", "alpha", "Mid", "beta")) {
            profiles.add(Profile.create(name, "26.2", LoaderType.FABRIC));
        }
        Map<String, String> id = new LinkedHashMap<>();
        profiles.forEach(profile -> id.put(profile.name(), profile.id()));

        ProfileLayout layout = new ProfileLayout();
        check("the grid starts nine wide", layout.columns() == 9);
        check("the grid starts three deep", layout.rows() == 3);
        check("capacity is columns times rows", layout.capacity() == 27);
        check("an empty arrangement reports empty", layout.isEmpty());

        check("reconcile seeds from the profiles", layout.reconcile(profiles));
        List<String> seeded = new ArrayList<>();
        for (String profileId : layout.sequence()) {
            profiles.stream().filter(profile -> profile.id().equals(profileId))
                    .findFirst().ifPresent(profile -> seeded.add(profile.name()));
        }
        check("the seeded order is alphabetical " + seeded,
                seeded.equals(List.of("alpha", "beta", "Mid", "Zeta")));
        check("seeding fills the first cells with no holes",
                layout.cellOf(id.get("alpha")).orElseThrow()[1] == 0
                        && layout.cellOf(id.get("Zeta")).orElseThrow()[1] == 3);
        check("every profile has a cell", layout.occupied() == 4);
        check("nothing is in a group yet", layout.groups().isEmpty());

        // -------------------------------------------------- absolute cells
        int[] before = layout.cellOf(id.get("beta")).orElseThrow();
        check("a free cell is taken exactly", layout.placeAt(id.get("Zeta"), 2, 7));
        check("the profile is in the cell it was dropped on",
                cellUnchanged(layout, id.get("Zeta"), new int[]{2, 7}));
        check("nothing else moved", cellUnchanged(layout, id.get("beta"), before));
        check("a cell outside the grid is refused", !layout.placeAt(id.get("Zeta"), 9, 9));
        check("the refusal left it where it was",
                cellUnchanged(layout, id.get("Zeta"), new int[]{2, 7}));

        int[] alphaCell = layout.cellOf(id.get("alpha")).orElseThrow();
        layout.placeAt(id.get("alpha"), 2, 7);
        check("dropping on an occupied cell swaps the two",
                cellUnchanged(layout, id.get("alpha"), new int[]{2, 7})
                        && cellUnchanged(layout, id.get("Zeta"), alphaCell));
        check("a swap loses nothing", layout.occupied() == 4);
        layout.placeAt(id.get("alpha"), alphaCell[0], alphaCell[1]);

        // -------------------------------------------------- gaps are nothing
        ProfileLayout gaps = new ProfileLayout();
        gaps.reconcile(profiles);
        gaps.placeAt(id.get("alpha"), 0, 0);
        gaps.placeAt(id.get("beta"), 0, 2);
        gaps.placeAt(id.get("Mid"), 0, 6);
        gaps.placeAt(id.get("Zeta"), 1, 4);
        check("a hole between two profiles is not a row",
                gaps.sequence().equals(List.of(id.get("alpha"), id.get("beta"),
                        id.get("Mid"), id.get("Zeta"))));
        check("the list has one row per placed profile", gaps.listRows().size() == 4);

        gaps.moveProfileBeside(id.get("Zeta"), id.get("alpha"), false);
        check("a list reorder changes the order",
                gaps.sequence().get(0).equals(id.get("Zeta")));
        check("a list reorder keeps the same cells occupied",
                cellUnchanged(gaps, id.get("Zeta"), new int[]{0, 0})
                        && cellUnchanged(gaps, id.get("alpha"), new int[]{0, 2})
                        && cellUnchanged(gaps, id.get("Mid"), new int[]{1, 4}));
        check("a list reorder loses nothing", gaps.occupied() == 4);

        // -------------------------------------------------- a group takes rows
        //
        // The property the previous attempt got wrong: a group has to own
        // something for collapsing it to mean anything.
        ProfileLayout rowsLayout = new ProfileLayout();
        rowsLayout.reconcile(profiles);
        ProfileLayout.Group group = rowsLayout.createGroup("Modded");
        check("a new group owns a row", rowsLayout.rowsOf(group.id()).size() == 1);
        int groupRow = rowsLayout.rowsOf(group.id()).get(0);
        check("the row it took was empty", rowsLayout.membersOf(group.id()).isEmpty());
        check("making a group displaced nobody", rowsLayout.occupied() == 4);
        check("the row knows its group",
                rowsLayout.rowGroup(groupRow).orElseThrow().id().equals(group.id()));

        // Membership is the row, so joining is a move into one of its cells.
        check("joining moves the profile into the group",
                rowsLayout.join(id.get("Mid"), group.id()));
        check("and it is now in the group",
                rowsLayout.groupOf(id.get("Mid")).orElseThrow().id().equals(group.id()));
        check("its cell is in one of the group rows",
                rowsLayout.rowsOf(group.id())
                        .contains(rowsLayout.cellOf(id.get("Mid")).orElseThrow()[0]));
        check("the group lists it",
                rowsLayout.membersOf(group.id()).equals(List.of(id.get("Mid"))));
        check("a profile in an ungrouped row is in no group",
                rowsLayout.groupOf(id.get("beta")).isEmpty());

        // Dropping it into an ungrouped cell is how it leaves.
        check("leaving is a move too", rowsLayout.join(id.get("Mid"), null));
        check("and it is out of the group", rowsLayout.groupOf(id.get("Mid")).isEmpty());
        check("the group is empty again", rowsLayout.membersOf(group.id()).isEmpty());
        check("but still owns its row", rowsLayout.rowsOf(group.id()).size() == 1);
        rowsLayout.join(id.get("Mid"), group.id());
        rowsLayout.join(id.get("Zeta"), group.id());
        check("two profiles in the group", rowsLayout.membersOf(group.id()).size() == 2);

        // -------------------------------------------------- collapsing
        List<ProfileLayout.ListRow> open = rowsLayout.listRows();
        check("the list shows a header for the group",
                open.stream().filter(ProfileLayout.ListRow::isGroup).count() == 1);
        check("the header knows how many it holds", open.stream()
                .filter(ProfileLayout.ListRow::isGroup).findFirst().orElseThrow()
                .memberCount() == 2);
        check("members are drawn nested",
                open.stream().filter(row -> !row.isGroup() && row.isNested()).count() == 2);
        check("every profile appears once",
                open.stream().filter(row -> !row.isGroup()).count() == 4);

        rowsLayout.setCollapsed(group.id(), true);
        List<ProfileLayout.ListRow> folded = rowsLayout.listRows();
        check("collapsing hides the members in the list",
                folded.stream().filter(row -> !row.isGroup()).count() == 2);
        check("the header is still there",
                folded.stream().filter(ProfileLayout.ListRow::isGroup).count() == 1);
        check("collapsing moves nothing", rowsLayout.occupied() == 4);

        // The grid folds with it, which is what a group owning rows is for.
        List<ProfileLayout.Band> bands = rowsLayout.bands();
        ProfileLayout.Band groupBand = bands.stream()
                .filter(band -> band.group() != null).findFirst().orElseThrow();
        check("the grid folds the band too", groupBand.isCollapsed());
        check("the folded band knows its rows", !groupBand.rows().isEmpty());
        check("the folded band knows its members", groupBand.memberCount() == 2);
        rowsLayout.setCollapsed(group.id(), false);
        check("bands cover every row",
                rowsLayout.bands().stream().mapToInt(band -> band.rows().size()).sum()
                        == rowsLayout.rows());
        check("a band never mixes groups", bandsAreUniform(rowsLayout));

        // -------------------------------------------------- rows of a group
        check("a group can be given another row", rowsLayout.addRowToGroup(group.id()));
        check("it now owns two", rowsLayout.rowsOf(group.id()).size() == 2);
        check("its rows are next to each other",
                rowsLayout.rowsOf(group.id()).get(1)
                        - rowsLayout.rowsOf(group.id()).get(0) == 1);
        check("adding a row kept everybody", rowsLayout.occupied() == 4);
        check("and kept the members in the group",
                rowsLayout.membersOf(group.id()).size() == 2);
        check("a group can give a row back", rowsLayout.removeRowFromGroup(group.id()));
        check("it owns one again", rowsLayout.rowsOf(group.id()).size() == 1);
        check("the last row of a group will not go",
                !rowsLayout.removeRowFromGroup(group.id()));
        check("nothing was lost by the refusal", rowsLayout.occupied() == 4);

        // -------------------------------------------------- removing rows
        //
        // Removing a row is a change to the table. Deleting a group is a change
        // to the arrangement, and one must not happen as a side effect of the
        // other.
        ProfileLayout table = new ProfileLayout();
        table.reconcile(profiles);
        ProfileLayout.Group only = table.createGroup("Only");
        // The first free row, not a new one at the bottom: making a group should
        // not push the grid taller when there is already an empty row in it.
        check("a new group takes the first free row",
                table.rowsOf(only.id()).equals(List.of(1)));
        check("an empty ungrouped last row goes without argument", table.removeRow());
        check("the grid is two deep", table.rows() == 2);
        check("the group's row is now the last one",
                table.rowsOf(only.id()).get(0) == table.rows() - 1);
        check("removing it is refused", !table.removeRow());
        check("the group survived the refusal", table.group(only.id()).isPresent());
        check("it still owns its row", table.rowsOf(only.id()).size() == 1);
        check("and the grid is unchanged", table.rows() == 2);
        check("adding a row to the group first", table.addRowToGroup(only.id()));
        check("now the last row can go", table.removeRow());
        check("and the group is still here", table.group(only.id()).isPresent());
        check("with one row", table.rowsOf(only.id()).size() == 1);

        int rowsBefore = table.rows();
        table.removeGroup(only.id());
        check("deleting a group keeps every profile", table.occupied() == 4);
        check("deleting a group keeps its rows", table.rows() == rowsBefore);
        check("and nothing is in a group", table.groups().isEmpty());

        // -------------------------------------------------- removing columns
        ProfileLayout narrow = new ProfileLayout();
        narrow.reconcile(profiles);
        ProfileLayout.Group kept = narrow.createGroup("Kept");
        narrow.join(id.get("Mid"), kept.id());
        int[] pinned = narrow.cellOf(id.get("beta")).orElseThrow();
        narrow.placeAt(id.get("Zeta"), narrow.rowsOf(kept.id()).get(0), narrow.columns() - 1);
        check("a column can go", narrow.removeColumn());
        check("and took its occupant with it", narrow.occupied() == 4);
        check("the displaced profile stayed in its group",
                narrow.groupOf(id.get("Zeta")).orElseThrow().id().equals(kept.id()));
        check("everything else stayed put", cellUnchanged(narrow, id.get("beta"), pinned));
        check("every cell is inside the grid", allInside(narrow));

        // A resize may never move a profile out of its group. When the group
        // has no free cell for the one being displaced, the column stays.
        ProfileLayout strict = new ProfileLayout();
        List<Profile> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(Profile.create("strict" + i, "26.2", LoaderType.VANILLA));
        }
        strict.reconcile(eleven);
        ProfileLayout.Group packed = strict.createGroup("Packed");
        for (int i = 0; i < 9; i++) {
            strict.join(eleven.get(i).id(), packed.id());
        }
        check("the group is exactly full",
                strict.membersOf(packed.id()).size() == strict.columns()
                        && strict.rowsOf(packed.id()).size() == 1);
        String edgeProfile = strict.at(strict.rowsOf(packed.id()).get(0),
                strict.columns() - 1).orElseThrow();
        check("a full group refuses to lose a column", !strict.removeColumn());
        check("the refusal moved nobody",
                strict.groupOf(edgeProfile).orElseThrow().id().equals(packed.id())
                        && strict.cellOf(edgeProfile).orElseThrow()[1]
                                == strict.columns() - 1);
        check("nobody was pushed out of the group",
                strict.membersOf(packed.id()).size() == 9);
        check("giving the group a row makes room", strict.addRowToGroup(packed.id()));
        check("and then the column can go", strict.removeColumn());
        check("the displaced profile is still in its group",
                strict.groupOf(edgeProfile).orElseThrow().id().equals(packed.id()));
        check("and everybody is still placed", strict.occupied() == 11);
        check("inside the grid", allInside(strict));

        ProfileLayout full = new ProfileLayout();
        List<Profile> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(Profile.create("full" + i, "26.2", LoaderType.VANILLA));
        }
        full.reconcile(nine);
        check("a grid can be narrowed to exactly its contents", full.rows(1));
        check("its capacity is nine", full.capacity() == 9);
        check("a full grid refuses to lose a column", !full.removeColumn());
        check("the refusal changed nothing", full.columns() == 9 && full.occupied() == 9);
        check("adding a row first makes room", full.addRow());
        check("and then the column can go", full.removeColumn());
        check("still nine profiles", full.occupied() == 9);
        check("all of them inside the grid", allInside(full));
        check("no two share a cell", noSharedCells(full));

        // -------------------------------------------------- the table's own minus
        //
        // It takes the last row that is empty and in no group, wherever that is.
        // A group at the bottom must not make the button refuse while an empty
        // row above it is doing nothing.
        ProfileLayout bottom = new ProfileLayout();
        bottom.reconcile(profiles);
        ProfileLayout.Group tail = bottom.createGroup("Tail");
        check("the empty row below the group goes first", bottom.removeLastEmptyRow());
        check("the group is now the last row",
                bottom.rowsOf(tail.id()).get(0) == bottom.rows() - 1);
        check("an empty row above the group", bottom.insertRowAt(1, null));
        check("the button skips the group and takes that one",
                bottom.removeLastEmptyRow());
        check("the group survived", bottom.group(tail.id()).isPresent());
        check("and is still the last row",
                bottom.rowsOf(tail.id()).get(0) == bottom.rows() - 1);
        check("nothing was moved", bottom.occupied() == 4);
        check("with no empty ungrouped row left it refuses",
                !bottom.removeLastEmptyRow());
        check("and the grid is unchanged", bottom.rows() == 2);
        check("the group is untouched by the refusal",
                bottom.group(tail.id()).isPresent()
                        && bottom.rowsOf(tail.id()).size() == 1);

        // -------------------------------------------------- moving a group
        ProfileLayout moved = new ProfileLayout();
        moved.reconcile(profiles);
        ProfileLayout.Group block = moved.createGroup("Block");
        moved.join(id.get("Zeta"), block.id());
        moved.addRowToGroup(block.id());
        List<Integer> was = moved.rowsOf(block.id());
        moved.moveGroupBeside(block.id(), id.get("alpha"), false);
        List<Integer> now = moved.rowsOf(block.id());
        check("the group moved " + was + " -> " + now, !was.equals(now));
        check("its rows are still together and in order",
                now.size() == 2 && now.get(1) - now.get(0) == 1);
        check("its members came with it",
                moved.groupOf(id.get("Zeta")).orElseThrow().id().equals(block.id()));
        check("moving a group lost nobody", moved.occupied() == 4);
        check("every cell still inside", allInside(moved));

        // -------------------------------------------------- a group on one row
        //
        // "New group here" points at a row rather than taking the first free one,
        // and that row may already have instances in it. Both answers to that
        // have to work, and neither may lose anybody.
        ProfileLayout here = new ProfileLayout();
        here.reconcile(profiles);
        check("the row that was pointed at is not empty", here.occupantsInRow(0) == 4);

        ProfileLayout takes = ProfileLayout.fromJson(Json.parse(here.toJson().toString()));
        takes.reconcile(profiles);
        ProfileLayout.Group taken = takes.claimRow(0, "Takes them", true);
        check("a group can take the row it was asked for", taken != null);
        check("it owns that row", takes.rowsOf(taken.id()).equals(List.of(0)));
        check("and the instances that were in it are its members",
                takes.membersOf(taken.id()).size() == 4);
        check("nobody moved", takes.occupantsInRow(0) == 4);

        ProfileLayout evicts = ProfileLayout.fromJson(Json.parse(here.toJson().toString()));
        evicts.reconcile(profiles);
        ProfileLayout.Group emptied = evicts.claimRow(0, "Moves them out", false);
        check("or the row can be cleared for it", emptied != null);
        check("the group starts empty", evicts.membersOf(emptied.id()).isEmpty());
        check("and everybody is still placed", evicts.occupied() == 4);
        check("outside every group",
                evicts.sequence().stream().allMatch(entry -> evicts.groupOf(entry).isEmpty()));
        check("inside the grid", allInside(evicts));
        check("no two in one cell", noSharedCells(evicts));

        // Nowhere to put them means another row, not a refusal and not a group
        // that swallowed them anyway.
        ProfileLayout cramped = new ProfileLayout();
        List<Profile> twentyseven = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            twentyseven.add(Profile.create("cramped" + i, "26.2", LoaderType.VANILLA));
        }
        cramped.reconcile(twentyseven);
        check("the grid is exactly full", cramped.freeCells() == 0);
        int wasRows = cramped.rows();
        ProfileLayout.Group grew = cramped.claimRow(0, "Needs room", false);
        check("the grid grew to make room", grew != null && cramped.rows() > wasRows);
        check("everybody is still placed", cramped.occupied() == 27);
        check("and none of them landed in the new group",
                cramped.membersOf(grew.id()).isEmpty());
        check("all inside the grid", allInside(cramped));
        check("no two in one cell", noSharedCells(cramped));

        ProfileLayout twice = new ProfileLayout();
        twice.reconcile(profiles);
        ProfileLayout.Group first = twice.claimRow(1, "First", true);
        check("a row can be claimed", first != null);
        check("but not twice", twice.claimRow(1, "Second", true) == null);
        check("and the first group is untouched",
                twice.rowsOf(first.id()).equals(List.of(1)) && twice.groups().size() == 1);

        // -------------------------------------------------- dragging a band
        ProfileLayout dragged = new ProfileLayout();
        dragged.reconcile(profiles);
        ProfileLayout.Group band = dragged.createGroup("Band");
        dragged.join(id.get("Zeta"), band.id());
        dragged.addRowToGroup(band.id());
        List<Integer> startedAt = dragged.rowsOf(band.id());
        check("the band can be moved above the first row",
                dragged.moveBandBeside(band.id(), 0, false));
        check("it is now at the top", dragged.rowsOf(band.id()).get(0) == 0);
        check("its rows stayed together",
                dragged.rowsOf(band.id()).get(1) - dragged.rowsOf(band.id()).get(0) == 1);
        check("its member came with it",
                dragged.groupOf(id.get("Zeta")).orElseThrow().id().equals(band.id()));
        check("nobody was lost " + startedAt, dragged.occupied() == 4);
        check("a band cannot be dropped on itself",
                !dragged.moveBandBeside(band.id(), dragged.rowsOf(band.id()).get(0), true));
        check("moving the top band up again does nothing",
                !dragged.moveGroupBy(band.id(), true));
        check("but it can go down", dragged.moveGroupBy(band.id(), false));
        check("its rows are still together",
                dragged.rowsOf(band.id()).get(1) - dragged.rowsOf(band.id()).get(0) == 1);
        check("and it is no longer at the top", dragged.rowsOf(band.id()).get(0) > 0);
        check("still four profiles", dragged.occupied() == 4);
        check("all inside", allInside(dragged));

        // -------------------------------------------------- between empty rows
        //
        // A run of empty rows used to be one band, so a dropped group could only
        // go above all of them or below all of them - there was no way to leave
        // one sitting between two empty rows. Each row in no group is its own
        // band now, and that is the property to hold on to.
        ProfileLayout between = new ProfileLayout();
        between.reconcile(profiles);
        between.addRow();
        check("four rows, the first one occupied", between.rows() == 4
                && between.occupantsInRow(0) == 4);
        ProfileLayout.Group loose = between.claimRow(3, "Loose", true);
        check("a group at the bottom", loose != null
                && between.rowsOf(loose.id()).equals(List.of(3)));
        check("rows one and two are empty and in no group",
                between.occupantsInRow(1) == 0 && between.occupantsInRow(2) == 0
                        && between.rowGroup(1).isEmpty() && between.rowGroup(2).isEmpty());

        long single = between.bands().stream()
                .filter(strip -> strip.group() == null)
                .filter(strip -> strip.rows().size() == 1)
                .count();
        long ungrouped = between.bands().stream()
                .filter(strip -> strip.group() == null).count();
        check("every row in no group is a band of its own " + single + "/" + ungrouped,
                single == ungrouped && ungrouped == 3);

        check("the group can be dropped above the second empty row",
                between.moveBandBeside(loose.id(), 2, false));
        check("and it lands between the two empty rows",
                between.rowsOf(loose.id()).equals(List.of(2)));
        check("with an empty row above it",
                between.rowGroup(1).isEmpty() && between.occupantsInRow(1) == 0);
        check("and an empty row below it",
                between.rowGroup(3).isEmpty() && between.occupantsInRow(3) == 0);
        check("the occupied row is untouched", between.occupantsInRow(0) == 4);
        check("nobody was lost", between.occupied() == 4);
        check("all inside the grid", allInside(between));

        check("and it can be dropped below that row again",
                between.moveBandBeside(loose.id(), 3, true));
        check("landing at the bottom", between.rowsOf(loose.id()).equals(List.of(3)));
        check("still four rows", between.rows() == 4);

        // A group's own rows stay one band, because a group moves and folds whole.
        ProfileLayout whole = new ProfileLayout();
        whole.reconcile(profiles);
        ProfileLayout.Group deep = whole.createGroup("Deep");
        whole.addRowToGroup(deep.id());
        check("the group owns two rows", whole.rowsOf(deep.id()).size() == 2);
        long groupBands = whole.bands().stream()
                .filter(strip -> strip.group() != null).count();
        check("and they are still one band " + groupBands, groupBands == 1);
        check("bands still cover every row",
                whole.bands().stream().mapToInt(strip -> strip.rows().size()).sum()
                        == whole.rows());

        // -------------------------------------------------- groups never nest
        //
        // Dropping a group on a row that is inside another group is not a way to
        // put one group into another - there is no such thing. Whatever row is
        // aimed at, the target's whole block is stepped over, and the two groups
        // come out one after the other rather than interleaved.
        ProfileLayout nested = new ProfileLayout();
        nested.reconcile(profiles);
        ProfileLayout.Group outer = nested.createGroup("Outer");
        nested.join(id.get("Zeta"), outer.id());
        ProfileLayout.Group inner = nested.createGroup("Inner");
        nested.join(id.get("Mid"), inner.id());
        nested.addRowToGroup(inner.id());
        check("two groups, one of them two rows deep",
                nested.rowsOf(outer.id()).size() == 1
                        && nested.rowsOf(inner.id()).size() == 2);
        check("neither is interleaved to begin with", groupRowsAreContiguous(nested));

        int deepInside = nested.rowsOf(inner.id()).get(1);
        check("a group can be dropped on a row inside another",
                nested.moveBandBeside(outer.id(), deepInside, false));
        check("but it does not end up inside it", groupRowsAreContiguous(nested));
        check("its member came with it",
                nested.groupOf(id.get("Zeta")).orElseThrow().id().equals(outer.id()));
        check("and the other group kept its own",
                nested.groupOf(id.get("Mid")).orElseThrow().id().equals(inner.id()));
        check("nobody was lost", nested.occupied() == 4);
        check("everything is inside the grid", allInside(nested));

        int deepInsideAfter = nested.rowsOf(inner.id()).get(0);
        check("the same from the other side",
                nested.moveBandBeside(outer.id(), deepInsideAfter, true));
        check("still not interleaved", groupRowsAreContiguous(nested));
        check("a group cannot be dropped on a row of its own",
                !nested.moveBandBeside(outer.id(), nested.rowsOf(outer.id()).get(0), true));
        check("nothing changed by the refusal", groupRowsAreContiguous(nested)
                && nested.occupied() == 4);

        // -------------------------------------------------- sorting
        ProfileLayout tidy = new ProfileLayout();
        tidy.reconcile(profiles);
        ProfileLayout.Group sorted = tidy.createGroup("Sorted");
        tidy.join(id.get("Zeta"), sorted.id());
        tidy.join(id.get("alpha"), sorted.id());
        tidy.sortByName(profiles);
        check("sorting orders a group by name", tidy.membersOf(sorted.id())
                .equals(List.of(id.get("alpha"), id.get("Zeta"))));
        check("sorting leaves the groups where they are",
                tidy.rowsOf(sorted.id()).size() == 1);
        check("sorting keeps everybody in their group",
                tidy.groupOf(id.get("alpha")).orElseThrow().id().equals(sorted.id())
                        && tidy.groupOf(id.get("beta")).isEmpty());

        // -------------------------------------------------- round trip
        rowsLayout.mode(ProfileLayout.Mode.INVENTORY);
        rowsLayout.addColumn();
        ProfileLayout reread = ProfileLayout.fromJson(
                Json.parse(rowsLayout.toJson().toString()));
        reread.reconcile(profiles);
        check("the chosen interface survives a save",
                reread.mode() == ProfileLayout.Mode.INVENTORY);
        check("the grid size survives a save",
                reread.columns() == rowsLayout.columns() && reread.rows() == rowsLayout.rows());
        check("every cell survives a save", sameCells(rowsLayout, reread, id.values()));
        check("the group rows survive a save",
                reread.rowsOf(group.id()).equals(rowsLayout.rowsOf(group.id())));
        check("membership survives a save",
                reread.membersOf(group.id()).equals(rowsLayout.membersOf(group.id())));

        // -------------------------------------------------- the first format
        ProfileLayout old = ProfileLayout.fromJson(Json.parse(
                "{\"mode\":\"inventory\",\"entries\":["
                + "{\"type\":\"profile\",\"id\":\"" + id.get("Mid") + "\"},"
                + "{\"type\":\"group\",\"id\":\"g1\",\"name\":\"Old set\","
                + "\"collapsed\":true,\"members\":[\"" + id.get("Zeta") + "\","
                + "\"" + id.get("alpha") + "\"]},"
                + "{\"type\":\"profile\",\"id\":\"" + id.get("beta") + "\"}]}"));
        old.reconcile(profiles);
        check("the first format keeps its order", old.sequence().equals(List.of(
                id.get("Mid"), id.get("Zeta"), id.get("alpha"), id.get("beta"))));
        check("the first format keeps its group", old.groups().size() == 1);
        check("with its name", old.group("g1").orElseThrow().name().equals("Old set"));
        check("its members are in its rows",
                old.membersOf("g1").equals(List.of(id.get("Zeta"), id.get("alpha"))));
        check("the group owns a row", !old.rowsOf("g1").isEmpty());
        check("its collapsed state is carried over",
                old.group("g1").orElseThrow().collapsed());
        check("the interface choice is carried over",
                old.mode() == ProfileLayout.Mode.INVENTORY);
        check("nobody outside the group ended up in it",
                old.groupOf(id.get("Mid")).isEmpty() && old.groupOf(id.get("beta")).isEmpty());

        // -------------------------------------------------- the second format
        //
        // The version that kept membership on the profile rather than on the
        // row. Its cells cannot be honoured - a group has to own whole rows now -
        // so the order and the grouping are read and laid out again.
        ProfileLayout second = ProfileLayout.fromJson(Json.parse(
                "{\"mode\":\"list\",\"columns\":9,\"rows\":3,"
                + "\"groups\":[{\"id\":\"g2\",\"name\":\"Second\",\"color\":\"#3d6ea5\"}],"
                + "\"cells\":[{\"id\":\"" + id.get("Mid") + "\",\"row\":0,\"column\":0},"
                + "{\"id\":\"" + id.get("Zeta") + "\",\"row\":0,\"column\":1,\"group\":\"g2\"},"
                + "{\"id\":\"" + id.get("beta") + "\",\"row\":1,\"column\":3}]}"));
        second.reconcile(profiles);
        check("the second format keeps its group", second.group("g2").isPresent());
        check("its member is in the group's rows",
                second.groupOf(id.get("Zeta")).orElseThrow().id().equals("g2"));
        check("the group owns whole rows now", !second.rowsOf("g2").isEmpty());
        check("the others are not in it",
                second.groupOf(id.get("Mid")).isEmpty()
                        && second.groupOf(id.get("beta")).isEmpty());
        check("every profile has a cell", second.occupied() == 4);
        check("no two share a cell", noSharedCells(second));

        // -------------------------------------------------- hostile input
        ProfileLayout brokenLayout = ProfileLayout.fromJson(Json.parse(
                "{\"mode\":\"nonsense\",\"columns\":9999,\"rows\":-4,"
                + "\"groups\":[{\"id\":\"g1\"},{\"id\":\"g1\"}],"
                + "\"rowGroups\":[{\"row\":0,\"group\":\"g1\"},"
                + "{\"row\":900,\"group\":\"g1\"},{\"row\":1,\"group\":\"missing\"}],"
                + "\"cells\":[{\"id\":\"ghost\",\"row\":0,\"column\":0},"
                + "{\"id\":\"" + id.get("Mid") + "\",\"row\":0,\"column\":0},"
                + "{\"id\":\"" + id.get("Zeta") + "\",\"row\":700,\"column\":3},"
                + "{\"id\":\"" + id.get("Zeta") + "\",\"row\":1,\"column\":1}]}"));
        brokenLayout.reconcile(profiles);
        check("an unknown interface name falls back to the list",
                brokenLayout.mode() == ProfileLayout.Mode.LIST);
        check("an absurd column count is clamped",
                brokenLayout.columns() <= ProfileLayout.MAX_COLUMNS
                        && brokenLayout.columns() >= ProfileLayout.MIN_COLUMNS);
        check("an absurd row count is clamped",
                brokenLayout.rows() >= ProfileLayout.MIN_ROWS);
        check("a duplicated group id is dropped", brokenLayout.groups().size() == 1);
        check("a row assigned to a group that does not exist is dropped",
                brokenLayout.rowGroup(1).isEmpty());
        check("ids of profiles that no longer exist are dropped",
                new java.util.HashSet<>(brokenLayout.sequence())
                        .equals(new java.util.HashSet<>(id.values())));
        check("every profile ended up with a cell", brokenLayout.occupied() == 4);
        check("no two profiles share a cell", noSharedCells(brokenLayout));
        check("every cell is inside the grid", allInside(brokenLayout));
        check("every group still owns a row", brokenLayout.groups().stream()
                .allMatch(entry -> !brokenLayout.rowsOf(entry.id()).isEmpty()));

        // A profile with nowhere to be is the one case the grid grows by itself.
        ProfileLayout tight = new ProfileLayout();
        List<Profile> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(Profile.create("many" + (100 + i), "26.2", LoaderType.VANILLA));
        }
        tight.reconcile(many);
        check("a grid too small for the profiles grows", tight.capacity() >= 30);
        check("all thirty are placed", tight.occupied() == 30);
        check("all thirty are inside the grid", allInside(tight));
        check("no two of them share a cell", noSharedCells(tight));

        // A new profile is never dropped into somebody's group by the launcher.
        ProfileLayout newcomer = new ProfileLayout();
        newcomer.reconcile(profiles);
        ProfileLayout.Group theirs = newcomer.createGroup("Theirs");
        newcomer.join(id.get("Mid"), theirs.id());
        List<Profile> plusOne = new ArrayList<>(profiles);
        Profile fresh = Profile.create("Newcomer", "26.2", LoaderType.VANILLA);
        plusOne.add(fresh);
        newcomer.reconcile(plusOne);
        check("a new profile lands outside every group",
                newcomer.groupOf(fresh.id()).isEmpty());
        check("and the group is untouched",
                newcomer.membersOf(theirs.id()).equals(List.of(id.get("Mid"))));
    }

    private static boolean cellUnchanged(ProfileLayout layout, String profileId, int[] expected) {
        int[] cell = layout.cellOf(profileId).orElse(null);
        return cell != null && cell[0] == expected[0] && cell[1] == expected[1];
    }

    private static boolean allInside(ProfileLayout layout) {
        for (String id : layout.sequence()) {
            int[] cell = layout.cellOf(id).orElseThrow();
            if (cell[0] < 0 || cell[1] < 0 || cell[0] >= layout.rows()
                    || cell[1] >= layout.columns()) {
                return false;
            }
        }
        return true;
    }

    private static boolean noSharedCells(ProfileLayout layout) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id : layout.sequence()) {
            int[] cell = layout.cellOf(id).orElseThrow();
            if (!seen.add(cell[0] + ":" + cell[1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every group's rows sit next to each other.
     *
     * <p>The property behind "groups do not nest": if one group's rows were ever
     * split by another's, the two would be interleaved on screen and there would
     * be no honest way to draw either of them as a band.
     */
    private static boolean groupRowsAreContiguous(ProfileLayout layout) {
        for (ProfileLayout.Group group : layout.groups()) {
            List<Integer> owned = layout.rowsOf(group.id());
            for (int i = 1; i < owned.size(); i++) {
                if (owned.get(i) - owned.get(i - 1) != 1) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Every row of a band belongs to the band's group, and to no other. */
    private static boolean bandsAreUniform(ProfileLayout layout) {
        for (ProfileLayout.Band band : layout.bands()) {
            for (int row : band.rows()) {
                var owner = layout.rowGroup(row);
                if (band.group() == null) {
                    if (owner.isPresent()) {
                        return false;
                    }
                } else if (owner.isEmpty() || !owner.get().id().equals(band.group().id())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean sameCells(ProfileLayout one, ProfileLayout other,
                                     java.util.Collection<String> ids) {
        for (String id : ids) {
            int[] a = one.cellOf(id).orElse(null);
            int[] b = other.cellOf(id).orElse(null);
            if (a == null || b == null || a[0] != b[0] || a[1] != b[1]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The icon fields on a profile.
     *
     * <p>{@code customIcon} is a file name that is resolved inside the
     * launcher's icons folder, and it comes out of a file the user can edit -
     * so the check that matters is that it cannot be turned into a path
     * pointing anywhere else.
     */
    private static void profileIconValues() {
        section("Profile icons");

        Profile profile = Profile.create("Test", "26.2", LoaderType.QUILT);
        check("a new profile follows its loader", profile.iconFollowsLoader());
        check("a new profile has no chosen picture", !profile.hasCustomIcon());

        profile.customIcon("../../../etc/passwd");
        check("a path escape is refused, not trimmed", profile.customIcon() == null);
        profile.customIcon("C:\\Windows\\explorer.exe");
        check("a Windows path is refused", profile.customIcon() == null);
        profile.customIcon("0a1b2c3d4e5f6071.png");
        check("a plain file name is kept",
                "0a1b2c3d4e5f6071.png".equals(profile.customIcon()));

        Json saved = profile.toJson();
        Profile reread = Profile.fromJson(Json.parse(saved.toString()));
        check("the chosen picture survives a save",
                "0a1b2c3d4e5f6071.png".equals(reread.customIcon()));

        // The field used to hold "grass", which named a picture the launcher
        // never had. It has to read as "follow the loader" rather than as a
        // missing file.
        Profile old = Profile.fromJson(Json.parse(
                "{\"id\":\"old-1\",\"name\":\"Old\",\"icon\":\"grass\"}"));
        check("an old icon value reads as follow-the-loader", old.iconFollowsLoader());
        check("an old profile has no chosen picture", !old.hasCustomIcon());
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

        // Bundles agreeing with each other does not mean the interface can find
        // what it asks for: a key absent from every bundle is consistent and
        // still renders as "!action.cancel!" on a button. These are the keys
        // added or renamed most recently, checked by name for exactly that.
        List<String> mustResolve = List.of(
                "action.cancel", "action.signIn", "action.signIn.cancel",
                "status.playing", "status.gameClosed", "log.signInCancelled",
                "profiles.remove.body", "profiles.remove.keepFiles",
                "profiles.remove.deleteFiles", "profiles.remove.deleted",
                "profiles.remove.deleteFailed",
                "mods.curseforge.disabled", "mods.curseforge.setKey",
                "mods.curseforge.key.header", "mods.curseforge.key.body",
                "mods.curseforge.key.saved", "mods.searchPartial",
                "editor.wrapper", "editor.wrapper.prompt", "editor.wrapper.note",
                "ui.mode.grid", "ui.mode.toGrid", "ui.mode.toList", "inventory.hint",
                "profiles.sort", "groups.new", "groups.new.title", "groups.new.header",
                "groups.new.body", "groups.new.default", "groups.remove", "groups.remove.header",
                "groups.remove.body", "groups.collapse", "groups.expand",
                "groups.settings", "groups.settings.title", "groups.name",
                "groups.name.prompt", "groups.color", "groups.color.custom",
                "groups.color.mixed", "groups.color.forget", "groups.color.mine",
                "color.title", "color.current", "color.new",
                "color.hex", "color.rgb",
                "groups.empty.hint",
                "groups.count", "groups.moveTo", "groups.none",
                "action.editAccount",
                "account.edit.title",
                "account.edit.failed",
                "account.kind.offline",
                "account.kind.microsoft",
                "account.skin",
                "account.cape",
                "account.source",
                "account.skin.choose",
                "account.skin.clear",
                "account.skin.none",
                "account.skin.upload",
                "account.skin.uploaded",
                "account.skin.premium.note",
                "account.model.classic",
                "account.model.slim",
                "account.cape.apply",
                "account.cape.applied",
                "account.cape.none",
                "account.cape.note",
                "account.source.local",
                "account.source.local.note",
                "account.source.remote",
                "account.source.remote.note",
                "account.busy",
                "account.preview.empty", "account.preview.left", "account.preview.right", "account.preview.in", "account.preview.out",
                "editor.icon", "editor.icon.note", "icon.title", "icon.choose",
                "icon.clear", "icon.filter", "icon.failed", "icon.set",
                "grid.addColumn", "grid.removeColumn", "grid.addRow", "grid.removeRow",
                "grid.noRoom", "grid.atMaximum", "settings.open", "settings.title",
                "settings.tab.interface", "settings.tab.game", "settings.tab.java",
                "settings.tab.network", "settings.tab.accounts", "settings.tab.data",
                "settings.grid.columns", "settings.grid.rows", "settings.grid.note",
                "settings.grid.refusedHeader", "settings.grid.refusedColumns",
                "settings.grid.refusedRows", "settings.splash", "settings.splash.note",
                "settings.keepOpen", "settings.tray", "settings.tray.note",
                "settings.verify", "settings.verify.note",
                "settings.java", "settings.java.ask", "settings.java.always",
                "settings.java.never", "settings.java.note", "settings.concurrency",
                "settings.concurrency.note", "settings.curseforge",
                "settings.curseforge.prompt", "settings.signIn", "settings.signIn.browser",
                "settings.signIn.deviceCode", "settings.signIn.note",
                "settings.handshake", "settings.handshake.note", "settings.fileStore",
                "settings.fileStore.note", "settings.dataFolder",
                "settings.dataFolder.note", "groups.addRow", "groups.removeRow",
                "groups.folded", "grid.lastGroupRow", "grid.noEmptyRow",
                "grid.noRoomInGroup", "toast.dismiss", "groups.plate.hint",
                "groups.moveUp", "groups.moveDown", "groups.moveFailed",
                "grid.newGroupHere", "grid.rowInGroup",
                "grid.newGroupHere.occupants.header",
                "grid.newGroupHere.occupants.body", "grid.newGroupHere.take",
                "grid.newGroupHere.move", "grid.newGroupHere.failed");
        for (Language language : Language.all()) {
            I18n.use(language);
            List<String> unresolved = mustResolve.stream()
                    .filter(key -> I18n.t(key).startsWith("!"))
                    .toList();
            check(language.code() + ": every key the interface asks for resolves " + unresolved,
                    unresolved.isEmpty());
        }
        I18n.use(Language.DEFAULT);

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

        // Naming a dependency. The reported symptom was an installed list showing
        // "eXts2L7r", which is a project id and tells the user nothing. The
        // platform is asked for the real name first; this is the fallback for
        // when it will not answer.
        check("a version is stripped off a jar name",
                "Placeholder Api".equals(ModInstaller.readableNameFrom(
                        "placeholder-api-3.1.0-beta.1+26.2.jar")));
        check("a simple name survives",
                "Sodium".equals(ModInstaller.readableNameFrom("sodium-0.6.13.jar")));
        check("underscores read as spaces",
                "Ferrite Core".equals(ModInstaller.readableNameFrom("ferrite_core-8.0.0.jar")));
        check("a name with no version is left alone",
                "Somemod".equals(ModInstaller.readableNameFrom("somemod.jar")));
        check("a digit inside a word is not a version boundary",
                "Log4j Fix".equals(ModInstaller.readableNameFrom("log4j-fix-1.0.jar")));
        check("a blank name does not produce a blank label",
                !ModInstaller.readableNameFrom("").isBlank());
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

    /**
     * Launching something already installed must not need the network.
     *
     * <p>The property under test is the one that decides it: a version counts as
     * installed only when its whole {@code inheritsFrom} chain is on disk. A
     * loader manifest without its Minecraft version is half an install, and
     * treating it as a whole one would skip the fetch that is genuinely needed
     * and fail later, inside the launch, with something far less obvious.
     *
     * <p>This is the check that would have caught the release where every launch
     * asked meta.fabricmc.net which loader builds exist - a question already
     * answered, in {@code profiles.json}, when the instance was installed.
     */
    private static void offlineRelaunch() {
        section("Offline relaunch");

        java.nio.file.Path work = null;
        try {
            work = java.nio.file.Files.createTempDirectory("hexadron-offline-check");
            GameDirs dirs = new GameDirs(work);
            com.hexadron.launcher.meta.VersionResolver resolver =
                    new com.hexadron.launcher.meta.VersionResolver(dirs);

            writeVersionJson(dirs, "26.2", """
                    {"id": "26.2", "mainClass": "net.minecraft.client.main.Main"}
                    """);
            writeVersionJson(dirs, "fabric-loader-0.19.3-26.2", """
                    {"id": "fabric-loader-0.19.3-26.2",
                     "inheritsFrom": "26.2",
                     "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient"}
                    """);

            check("a loader manifest with its parent on disk needs no network",
                    resolver.isFullyInstalled("fabric-loader-0.19.3-26.2"));
            check("so does the plain Minecraft version it inherits from",
                    resolver.isFullyInstalled("26.2"));

            check("a version that was never installed does not",
                    !resolver.isFullyInstalled("fabric-loader-0.19.3-26.1"));
            check("nor does an unset version id",
                    !resolver.isFullyInstalled(null));
            check("nor does a blank one",
                    !resolver.isFullyInstalled("   "));

            // The half install: the loader wrote its manifest, then the vanilla
            // fetch failed. isInstalled says yes to the child, which is exactly
            // the trap.
            java.nio.file.Files.delete(dirs.versionJson("26.2"));
            check("a loader manifest whose parent is missing is not installed",
                    !resolver.isFullyInstalled("fabric-loader-0.19.3-26.2"));
            check("even though the manifest itself is still there",
                    resolver.isInstalled("fabric-loader-0.19.3-26.2"));

            // A pair that inherits from each other. Nothing writes this, but a
            // hand-edited versions folder can, and the answer has to be "install
            // it again" rather than a hang.
            writeVersionJson(dirs, "loop-a", """
                    {"id": "loop-a", "inheritsFrom": "loop-b", "mainClass": "M"}
                    """);
            writeVersionJson(dirs, "loop-b", """
                    {"id": "loop-b", "inheritsFrom": "loop-a", "mainClass": "M"}
                    """);
            check("a cycle is not installed rather than a hang",
                    !resolver.isFullyInstalled("loop-a"));

            writeVersionJson(dirs, "broken", "{ not json");
            check("a malformed manifest is not installed",
                    !resolver.isFullyInstalled("broken"));
        } catch (IOException e) {
            check("the offline relaunch check can run: " + e.getMessage(), false);
        } finally {
            if (work != null) {
                deleteRecursively(work);
            }
        }
    }

    private static void writeVersionJson(GameDirs dirs, String versionId, String json)
            throws IOException {
        java.nio.file.Path file = dirs.versionJson(versionId);
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, json);
    }

    /**
     * Not re-reading a gigabyte of files that were already checked.
     *
     * <p>The ledger is a performance change with a security argument attached,
     * which is exactly the kind that has to be pinned down by tests rather than
     * by a comment. The claim is: it may skip a hash only when the file is the
     * same length, was not written since, and is being checked against the same
     * hash as last time. Each of those three is one check below.
     */
    private static void verificationLedger() {
        section("Verification ledger");

        java.nio.file.Path work = null;
        try {
            work = java.nio.file.Files.createTempDirectory("hexadron-ledger-check");
            GameDirs dirs = new GameDirs(work);
            dirs.createBaseDirectories();

            java.nio.file.Path file = work.resolve("libraries").resolve("thing.jar");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, "the original contents");
            String hash = Hashes.sha1(file);

            VerifiedFiles ledger = VerifiedFiles.load(dirs);
            check("an unknown file is not verified",
                    !ledger.isVerified(file, hash, VerifiedFiles.attributesOf(file)));

            ledger.record(file, hash, VerifiedFiles.attributesOf(file));
            check("a recorded, untouched file is verified",
                    ledger.isVerified(file, hash, VerifiedFiles.attributesOf(file)));
            check("but not against a different hash",
                    !ledger.isVerified(file, "0000000000000000000000000000000000000000",
                            VerifiedFiles.attributesOf(file)));

            // Rewritten with content of a different length: the case of a
            // truncated or half-written file.
            java.nio.file.Files.writeString(file, "shorter");
            check("a file of a different length is not verified",
                    !ledger.isVerified(file, hash, VerifiedFiles.attributesOf(file)));

            // Rewritten with content of exactly the same length. Only the
            // timestamp gives it away, which is why the timestamp is recorded.
            java.nio.file.Files.writeString(file, "the original contents");
            java.nio.file.Files.setLastModifiedTime(file,
                    java.nio.file.attribute.FileTime.fromMillis(
                            java.nio.file.Files.getLastModifiedTime(file).toMillis() + 5000));
            check("a file written since is not verified, even at the same length",
                    !ledger.isVerified(file, hash, VerifiedFiles.attributesOf(file)));

            check("a file that has gone has no attributes to check",
                    VerifiedFiles.attributesOf(work.resolve("absent.jar")) == null);
            check("and a directory is not a file",
                    VerifiedFiles.attributesOf(work) == null);

            // Survives a restart: the whole point is that Monday's work counts
            // on Tuesday.
            java.nio.file.Files.writeString(file, "final contents");
            String finalHash = Hashes.sha1(file);
            ledger.record(file, finalHash, VerifiedFiles.attributesOf(file));
            ledger.save();

            VerifiedFiles reopened = VerifiedFiles.load(dirs);
            check("the ledger survives being reloaded",
                    reopened.isVerified(file, finalHash, VerifiedFiles.attributesOf(file)));

            // Nothing may enter the ledger that was not hashed and matched
            // first. This is the invariant the whole argument rests on: the
            // ledger is an optimisation of a check, never a substitute for one.
            java.nio.file.Path never = work.resolve("libraries").resolve("never-checked.jar");
            java.nio.file.Files.writeString(never, "downloaded but never hashed");
            ledger.record(never, null, VerifiedFiles.attributesOf(never));
            check("a file with no hash is not recorded",
                    !ledger.isVerified(never, Hashes.sha1(never),
                            VerifiedFiles.attributesOf(never)));

            // The setting that turns the ledger off. Default and persistence
            // both matter: a security setting that silently forgets it was
            // switched on is worse than not having one.
            com.hexadron.launcher.core.LauncherSettings settings =
                    new com.hexadron.launcher.core.LauncherSettings(dirs);
            check("full verification is off by default", !settings.verifyEveryLaunch());
            settings.verifyEveryLaunch(true);
            settings.save();
            check("and survives being written and read back",
                    new com.hexadron.launcher.core.LauncherSettings(dirs)
                            .load().verifyEveryLaunch());
            settings.verifyEveryLaunch(false);
            settings.save();
            check("and can be turned off again",
                    !new com.hexadron.launcher.core.LauncherSettings(dirs)
                            .load().verifyEveryLaunch());

            check("the disabled ledger verifies nothing",
                    !VerifiedFiles.DISABLED.isVerified(file, finalHash,
                            VerifiedFiles.attributesOf(file)));
            VerifiedFiles.DISABLED.record(file, finalHash, VerifiedFiles.attributesOf(file));
            check("and learns nothing when told",
                    !VerifiedFiles.DISABLED.isVerified(file, finalHash,
                            VerifiedFiles.attributesOf(file)));
        } catch (IOException e) {
            check("the verification ledger check can run: " + e.getMessage(), false);
        } finally {
            if (work != null) {
                deleteRecursively(work);
            }
        }
    }

    /**
     * Not unpacking the same native jars before every launch.
     *
     * <p>The directory was wiped and re-extracted every time, which for a
     * version with thirty megabytes of natives is thousands of small writes
     * between pressing Play and the game starting. It is now skipped when the
     * jars it came from are unchanged - and repair can still force it, which is
     * the half that is easy to leave out.
     */
    private static void nativesReuse() {
        section("Natives reuse");

        java.nio.file.Path work = null;
        try {
            work = java.nio.file.Files.createTempDirectory("hexadron-natives-check");

            java.nio.file.Path jar = work.resolve("lwjgl-natives.jar");
            try (var out = new java.util.zip.ZipOutputStream(
                    java.nio.file.Files.newOutputStream(jar))) {
                out.putNextEntry(new java.util.zip.ZipEntry("native/lwjgl.dll"));
                out.write("from the jar".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }

            Library library = Library.parse(Json.parse("""
                    {"name": "org.lwjgl:lwjgl-platform:2.9.4",
                     "natives": {"windows": "natives-windows", "linux": "natives-linux",
                                 "osx": "natives-osx"},
                     "downloads": {"classifiers": {
                       "natives-windows": {"url": "https://example/w.jar", "sha1": "aa", "size": 1},
                       "natives-linux": {"url": "https://example/l.jar", "sha1": "bb", "size": 1},
                       "natives-osx": {"url": "https://example/o.jar", "sha1": "cc", "size": 1}}}}
                    """));
            check("the test library is a legacy native container",
                    library.isLegacyNativeContainer() && library.nativeArtifact() != null);

            java.nio.file.Path target = work.resolve("natives");
            Progress quiet = Progress.NOOP;

            // How many times the jars were looked up. Unpacking consults them
            // once to describe them and once to read them; skipping consults
            // them only to describe them. Counting is the probe because the
            // obvious one - editing an extracted file and seeing whether the
            // edit survives - now measures the opposite thing: an edited native
            // is meant to be detected and replaced. See below.
            java.util.concurrent.atomic.AtomicInteger consulted =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Function<Library, java.nio.file.Path> counting = lib -> {
                consulted.incrementAndGet();
                return jar;
            };

            NativesExtractor.extractAll(List.of(library), counting, target, quiet);
            int whenUnpacking = consulted.getAndSet(0);

            java.nio.file.Path extracted = target.resolve("lwjgl.dll");
            check("the native is unpacked the first time",
                    java.nio.file.Files.isRegularFile(extracted));

            NativesExtractor.extractAll(List.of(library), counting, target, quiet);
            check("an unchanged, untouched directory is not unpacked again",
                    consulted.get() < whenUnpacking);
            check("and what was unpacked is still there",
                    "from the jar".equals(java.nio.file.Files.readString(extracted)));

            java.nio.file.Files.writeString(extracted, "replaced by hand");
            NativesExtractor.forget(target);
            NativesExtractor.extractAll(List.of(library), lib -> jar, target, quiet);
            check("repair unpacks it again",
                    "from the jar".equals(java.nio.file.Files.readString(extracted)));

            // A different jar in the same place.
            try (var out = new java.util.zip.ZipOutputStream(
                    java.nio.file.Files.newOutputStream(jar))) {
                out.putNextEntry(new java.util.zip.ZipEntry("native/lwjgl.dll"));
                out.write("from a newer jar entirely".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            NativesExtractor.extractAll(List.of(library), lib -> jar, target, quiet);
            check("a changed jar is unpacked again",
                    "from a newer jar entirely".equals(java.nio.file.Files.readString(extracted)));

            // The security half. This directory is handed to the game as
            // -Djava.library.path, so everything in it is native code that gets
            // loaded. Wiping it before every launch used to destroy anything
            // planted here; skipping that is only safe because the stamp
            // describes the contents as well as the jars.
            java.nio.file.Files.writeString(extracted, "tampered with, same length");
            NativesExtractor.extractAll(List.of(library), lib -> jar, target, quiet);
            check("an edited native is detected and replaced",
                    "from a newer jar entirely".equals(java.nio.file.Files.readString(extracted)));

            java.nio.file.Path planted = target.resolve("evil.dll");
            java.nio.file.Files.writeString(planted, "not from any jar");
            NativesExtractor.extractAll(List.of(library), lib -> jar, target, quiet);
            check("a file planted beside the natives is removed",
                    !java.nio.file.Files.isRegularFile(planted));

            java.nio.file.Files.delete(extracted);
            NativesExtractor.extractAll(List.of(library), lib -> jar, target, quiet);
            check("a deleted native is put back",
                    java.nio.file.Files.isRegularFile(extracted));
        } catch (IOException e) {
            check("the natives reuse check can run: " + e.getMessage(), false);
        } finally {
            if (work != null) {
                deleteRecursively(work);
            }
        }
    }

    /**
     * Skins: what is accepted, what is served, and to whom.
     *
     * <p>The interesting part is not that a skin can be chosen - it is that the
     * local service answers for exactly one profile and returns "no textures"
     * for everybody else. A service that invented an answer for another player
     * would be putting made-up data where the game expects a fact about a real
     * person, and it would do it silently.
     */
    private static void skins() {
        section("Skins");

        java.nio.file.Path work = null;
        LocalSkinService service = null;
        try {
            work = java.nio.file.Files.createTempDirectory("hexadron-skin-check");
            GameDirs dirs = new GameDirs(work);
            dirs.createBaseDirectories();

            // --- what counts as a picture -------------------------------------
            check("a non-PNG has no readable size",
                    PngSize.read("this is not a png at all!!".getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)) == null);
            check("a truncated header has none either",
                    PngSize.read(new byte[]{(byte) 0x89, 'P', 'N', 'G'}) == null);

            java.nio.file.Path skin = work.resolve("skin.png");
            java.nio.file.Files.write(skin, png(64, 64));
            java.nio.file.Path old = work.resolve("old.png");
            java.nio.file.Files.write(old, png(64, 32));
            java.nio.file.Path wrong = work.resolve("wrong.png");
            java.nio.file.Files.write(wrong, png(128, 128));
            java.nio.file.Path cape = work.resolve("cape.png");
            java.nio.file.Files.write(cape, png(64, 32));

            int[] size = PngSize.read(skin);
            check("a PNG header gives its size",
                    size != null && size[0] == 64 && size[1] == 64);

            SkinStore store = new SkinStore(dirs);
            String skinName = store.store(skin, false);
            check("a 64x64 skin is accepted", skinName != null);
            check("so is the 64x32 sheet from before 1.8", store.store(old, false) != null);
            check("and it lands in the skins folder, named by content",
                    store.file(skinName) != null
                            && store.file(skinName).startsWith(dirs.skins()));

            boolean refused = false;
            try {
                store.store(wrong, false);
            } catch (IOException expected) {
                refused = true;
            }
            check("a picture of the wrong size is refused", refused);

            refused = false;
            try {
                store.store(skin, true);
            } catch (IOException expected) {
                refused = true;
            }
            check("a 64x64 file is refused as a cape", refused);

            String capeName = store.store(cape, true);
            check("a 64x32 cape is accepted", capeName != null);

            // A name from a hand-edited skins.json cannot walk out of the folder.
            check("a stored name is resolved inside the skins folder only",
                    store.file("../../../etc/passwd") == null);

            // --- who wears what, across a restart ------------------------------
            SkinProfile worn = SkinProfile.empty()
                    .withSkin(skinName).withCape(capeName)
                    .withModel(SkinProfile.Model.SLIM);
            store.put("offline:1234", worn);
            store.save();

            SkinProfile reloaded = new SkinStore(dirs).load().of("offline:1234");
            check("what an account wears survives a restart",
                    skinName.equals(reloaded.skin())
                            && capeName.equals(reloaded.cape())
                            && reloaded.model() == SkinProfile.Model.SLIM);
            check("an account with no skin wears nothing",
                    new SkinStore(dirs).load().of("offline:nobody").isEmpty());

            check("a profile with nothing to show needs no service",
                    !SkinProfile.empty().needsService());
            check("one with a skin does", worn.needsService());
            check("and a remote service does even with no local pictures",
                    SkinProfile.empty().withSource(SkinProfile.Source.REMOTE)
                            .withService("https://example/api").needsService());

            // --- what the local service actually answers -----------------------
            java.util.UUID uuid = java.util.UUID.nameUUIDFromBytes(
                    "OfflinePlayer:Tester".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            service = LocalSkinService.start(uuid, "Tester", worn, store,
                    work.resolve("key.json"));

            String root = service.root();
            check("the service listens on the loopback interface only",
                    root.startsWith("http://127.0.0.1:"));

            Json described = Json.parse(new String(java.util.Base64.getDecoder()
                    .decode(service.prefetchedMetadata()),
                    java.nio.charset.StandardCharsets.UTF_8));
            check("it publishes a signing key",
                    described.get("signaturePublickey").asString("")
                            .contains("BEGIN PUBLIC KEY"));
            check("and allows textures only from this machine",
                    described.get("skinDomains").size() == 2);

            String undashed = uuid.toString().replace("-", "");
            Json profile = Json.parse(get(root + "/sessionserver/session/minecraft/profile/" + undashed));
            check("it answers for the account it was started for",
                    undashed.equals(profile.get("id").asString("")));

            Json property = profile.get("properties").get(0);
            check("with a signed textures property",
                    "textures".equals(property.get("name").asString(""))
                            && !property.get("signature").asString("").isBlank());

            Json textures = Json.parse(new String(java.util.Base64.getDecoder()
                    .decode(property.get("value").asString("")),
                    java.nio.charset.StandardCharsets.UTF_8)).get("textures");
            check("carrying the skin", textures.get("SKIN").get("url").asString("").startsWith(root));
            check("the arm width", "slim".equals(
                    textures.get("SKIN").get("metadata").get("model").asString("")));
            check("and the cape", textures.get("CAPE").get("url").asString("").startsWith(root));

            // The whole point of the design, in one check.
            check("and nothing at all about any other player",
                    status(root + "/sessionserver/session/minecraft/profile/"
                            + java.util.UUID.randomUUID().toString().replace("-", "")) == 204);

            byte[] served = bytes(textures.get("SKIN").get("url").asString(""));
            check("the texture it points at is the file that was chosen",
                    java.util.Arrays.equals(served,
                            java.nio.file.Files.readAllBytes(store.file(skinName))));
            check("and a texture it never published is not served",
                    status(root + "/textures/" + "0".repeat(64)) == 404);

            check("a launch with no skin attaches no agent",
                    !SkinSession.none().isActive()
                            && SkinSession.none().arguments().isEmpty());
        } catch (IOException e) {
            check("the skin check can run: " + e.getMessage(), false);
        } finally {
            if (service != null) {
                service.close();
            }
            if (work != null) {
                deleteRecursively(work);
            }
        }
    }

    /** A PNG header of the given size, with no image data. Enough to be measured. */
    private static byte[] png(int width, int height) {
        byte[] bytes = new byte[24];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        System.arraycopy(signature, 0, bytes, 0, 8);
        bytes[11] = 13;
        bytes[12] = 'I';
        bytes[13] = 'H';
        bytes[14] = 'D';
        bytes[15] = 'R';
        putInt(bytes, 16, width);
        putInt(bytes, 20, height);
        return bytes;
    }

    private static void putInt(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }

    private static String get(String url) throws IOException {
        return new String(bytes(url), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String url) throws IOException {
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        try (java.io.InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        } finally {
            connection.disconnect();
        }
    }

    private static int status(String url) throws IOException {
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Where every rectangle of a skin sheet goes on the figure.
     *
     * <p>This is the part of the 3D preview that cannot be reviewed by looking
     * at it. A wrong rectangle does not crash and does not look broken - it puts
     * a hand on a shin, or the back of a head on the front, and in a model that
     * is slowly turning nobody catches it. As numbers it is trivial: no two
     * parts may claim the same pixel, and every side must be the size of the
     * face it covers.
     */
    private static void skinLayout() {
        section("Skin layout");

        for (boolean slim : new boolean[]{false, true}) {
            String what = slim ? "slim" : "classic";
            List<SkinLayout.Part> parts = SkinLayout.player(slim);

            check(what + ": the figure is head, body, two arms, two legs, and their overlays",
                    parts.size() == 12);

            // Every part claims its own pixels. One shifted block and two parts
            // wear the same rectangle - which is the whole failure mode.
            boolean[][] claimed = new boolean[SkinLayout.SHEET][SkinLayout.SHEET];
            boolean overlap = false;
            boolean outside = false;
            boolean wrongSize = false;

            for (SkinLayout.Part part : parts) {
                for (SkinLayout.Rect rect : sides(part.faces())) {
                    if (rect.u0() < 0 || rect.v0() < 0
                            || rect.u1() > SkinLayout.SHEET || rect.v1() > SkinLayout.SHEET) {
                        outside = true;
                        continue;
                    }
                    for (int v = rect.v0(); v < rect.v1(); v++) {
                        for (int u = rect.u0(); u < rect.u1(); u++) {
                            if (claimed[v][u]) {
                                overlap = true;
                            }
                            claimed[v][u] = true;
                        }
                    }
                }

                int w = (int) part.width();
                int h = (int) part.height();
                int d = (int) part.depth();
                SkinLayout.Faces faces = part.faces();
                wrongSize |= faces.front().width() != w || faces.front().height() != h;
                wrongSize |= faces.back().width() != w || faces.back().height() != h;
                wrongSize |= faces.left().width() != d || faces.left().height() != h;
                wrongSize |= faces.right().width() != d || faces.right().height() != h;
                wrongSize |= faces.top().width() != w || faces.top().height() != d;
                wrongSize |= faces.bottom().width() != w || faces.bottom().height() != d;
            }

            check(what + ": every rectangle is on the sheet", !outside);
            check(what + ": no two parts claim the same pixel", !overlap);
            check(what + ": every side is the size of the face it covers", !wrongSize);

            SkinLayout.Part rightArm = named(parts, "rightArm");
            SkinLayout.Part leftArm = named(parts, "leftArm");
            check(what + ": the arms are " + (slim ? 3 : 4) + " pixels wide",
                    rightArm.width() == (slim ? 3 : 4) && leftArm.width() == rightArm.width());
            check(what + ": and they are different rectangles",
                    !rightArm.faces().front().equals(leftArm.faces().front()));

            // Facing the camera, a right hand is on the viewer's left.
            check(what + ": the right arm is on the viewer's left",
                    rightArm.x() < 0 && leftArm.x() > 0);
            check(what + ": and the legs are the same way round",
                    named(parts, "rightLeg").x() < 0 && named(parts, "leftLeg").x() > 0);

            // Y runs down, so a smaller number is higher up.
            check(what + ": the head is above the body, and the body above the legs",
                    named(parts, "head").y() < named(parts, "body").y()
                            && named(parts, "body").y() < named(parts, "leftLeg").y());

            check(what + ": the hat sits on the head",
                    named(parts, "hat").x() == named(parts, "head").x()
                            && named(parts, "hat").y() == named(parts, "head").y());
            check(what + ": and comes from the other half of the sheet",
                    named(parts, "hat").faces().front().u0()
                            - named(parts, "head").faces().front().u0() == 32);
        }

        SkinLayout.Part cape = SkinLayout.cape();
        check("the cape is ten by sixteen, one thick",
                cape.width() == 10 && cape.height() == 16 && cape.depth() == 1);
        check("and hangs off the back",
                cape.z() > 0);
        // The side a bystander sees is the one pointing away from the player.
        check("its outward side carries the outer rectangle",
                cape.faces().back().u0() == 1 && cape.faces().front().u0() == 12);

        boolean capeOnSheet = true;
        for (SkinLayout.Rect rect : sides(cape.faces())) {
            capeOnSheet &= rect.u1() <= 22 && rect.v1() <= 17;
        }
        check("and every rectangle fits the smallest cape sheet there is", capeOnSheet);
    }

    private static SkinLayout.Part named(List<SkinLayout.Part> parts, String name) {
        return parts.stream().filter(part -> part.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<SkinLayout.Rect> sides(SkinLayout.Faces faces) {
        return List.of(faces.top(), faces.bottom(), faces.right(),
                faces.front(), faces.left(), faces.back());
    }

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
