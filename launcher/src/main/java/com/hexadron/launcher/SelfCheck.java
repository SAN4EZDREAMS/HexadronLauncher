/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher;

import com.hexadron.launcher.about.Credits;
import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.LauncherLog;
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
import com.hexadron.launcher.mods.ModPack;
import com.hexadron.launcher.mods.ModVersions;
import com.hexadron.launcher.mods.LocalModInfo;
import com.hexadron.launcher.mods.ModCategory;
import com.hexadron.launcher.mods.ModDependents;
import com.hexadron.launcher.update.AppVersion;
import com.hexadron.launcher.update.ReleaseFeed;
import com.hexadron.launcher.update.UpdateChannel;
import com.hexadron.launcher.update.UpdateInstall;
import com.hexadron.launcher.update.Updates;
import com.hexadron.launcher.mods.ModEntry;
import com.hexadron.launcher.mods.SvgPaths;
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.mods.ModScan;
import com.hexadron.launcher.mods.VersionRanges;
import com.hexadron.launcher.mods.ModrinthProvider;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.net.ProxyChoice;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileLayout;
import com.hexadron.launcher.skin.LocalSkinService;
import com.hexadron.launcher.skin.PngSize;
import com.hexadron.launcher.skin.SkinLayout;
import com.hexadron.launcher.skin.DefaultSkin;
import com.hexadron.launcher.skin.SkinCredentials;
import com.hexadron.launcher.skin.SkinProfile;
import com.hexadron.launcher.skin.SkinSheets;
import com.hexadron.launcher.skin.SkinSession;
import com.hexadron.launcher.skin.SkinStore;
import com.hexadron.launcher.skin.SkinTemplate;
import com.hexadron.launcher.skin.YggdrasilAuth;
import com.hexadron.launcher.util.Archives;
import com.hexadron.launcher.util.Hashes;
import com.hexadron.launcher.util.Arguments;
import com.hexadron.launcher.util.MavenCoordinate;
import com.hexadron.launcher.util.Platform;
import com.hexadron.launcher.util.Redactor;
import com.hexadron.launcher.util.Webp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        jarDescriptors();
        webpDecoding();
        modsFolderScan();
        modVersionRanges();
        modVersionMismatch();
        modImport();
        modDependencies();
        launcherUpdates();
        modCategories();
        categoryOrder();
        loaderCompatibility();
        forgeInstallerProfiles();
        forgeTokenLanguage();
        curseForgeKeyHandling();
        searchPaging();
        profileArrangement();
        profileIconValues();
        offlineRelaunch();
        unreachableHosts();
        proxyRouting();
        modCompatibility();
        stylesheet();
        launcherLog();
        about();
        verificationLedger();
        nativesReuse();
        skins();
        skinLayout();
        skinTemplates();
        skinSheets();
        skinService();
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
    /**
     * A conversion specifier of the kind {@code String.format} understands.
     *
     * <p>Deliberately narrow: {@code %} on its own is a per cent sign and turns
     * up in ordinary sentences, so only a {@code %} followed by an argument
     * index or one of the specifiers actually used in this codebase counts.
     */
    private static final java.util.regex.Pattern PRINTF =
            java.util.regex.Pattern.compile("%(\\d+\\$)?[sdfn]");

    private static void translations() {
        section("Translations");

        Map<String, String> reference = I18n.bundle(Language.DEFAULT);
        check("the reference bundle is not empty", !reference.isEmpty());

        // The buttons on a dialog are written by JavaFX from its own bundle, so
        // a dialog whose every sentence was translated still said OK and Cancel
        // in English. They are overwritten from these keys, and a missing one
        // would put the English back without anything failing.
        for (String key : new String[]{"dialog.ok", "dialog.cancel", "dialog.yes",
                "dialog.no", "dialog.close", "dialog.apply", "dialog.finish",
                "dialog.next", "dialog.previous"}) {
            check("the standard dialog buttons are the launcher's own words: " + key,
                    reference.containsKey(key));
        }

        // A file refused by the importer carries a reason, not a sentence, so
        // that the sentence can be in the language the window is in. Every
        // reason therefore needs one.
        Map<ModScan.Reason, String> refusals = Map.of(
                ModScan.Reason.NOT_A_FILE, "mods.import.skipped.notFile",
                ModScan.Reason.NOT_A_JAR, "mods.import.skipped.notJar",
                ModScan.Reason.ALREADY_THERE, "mods.import.skipped.already",
                ModScan.Reason.NOT_AN_ARCHIVE, "mods.import.skipped.notArchive",
                ModScan.Reason.FAILED, "mods.import.skipped.failed");
        for (ModScan.Reason reason : ModScan.Reason.values()) {
            check("a refused import can be explained: " + reason,
                    refusals.containsKey(reason)
                            && reference.containsKey(refusals.get(reason)));
        }

        check("a mod nothing needs any more can say so",
                reference.containsKey("mods.dependents.none"));
        check("the logo cache setting is named and explained",
                reference.containsKey("settings.modIconCache")
                        && reference.containsKey("settings.modIconCache.note"));

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

            // Bundle values go through MessageFormat and never through
            // String.format, so a printf placeholder in one is not a
            // placeholder - it is the literal text "%s", shown to the user
            // exactly as written. Consistent across every language, which is
            // why the check above waves it through, and wrong in every one.
            List<String> printf = new ArrayList<>();
            for (Map.Entry<String, String> entry : bundle.entrySet()) {
                if (PRINTF.matcher(entry.getValue()).find()) {
                    printf.add(entry.getKey());
                }
            }
            check(code + ": no printf placeholder where MessageFormat is used " + printf,
                    printf.isEmpty());
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
                "account.skin.resized",
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
                "account.service.signin", "account.service.signin.again",
                "account.service.signout", "account.service.signedin",
                "account.service.signedout", "account.service.elsewhere",
                "account.service.noskin", "account.service.fileunused",
                "signin.title", "signin.service", "signin.user", "signin.user.hint",
                "signin.password", "signin.button", "signin.busy", "signin.incomplete",
                "signin.failed", "signin.address.bad", "signin.nostore", "signin.notsaved",
                "account.preview.empty", "account.preview.left", "account.preview.right", "account.preview.in", "account.preview.out", "account.drop.rejected",
                "template.part.head",
                "template.part.hat",
                "template.part.body",
                "template.part.jacket",
                "template.part.rightArm",
                "template.part.rightSleeve",
                "template.part.leftArm",
                "template.part.leftSleeve",
                "template.part.rightLeg",
                "template.part.rightTrouser",
                "template.part.leftLeg",
                "template.part.leftTrouser",
                "template.part.cape",
                "template.side.top",
                "template.side.bottom",
                "template.side.right",
                "template.side.front",
                "template.side.left",
                "template.side.back",
                "account.template",
                "account.template.note",
                "account.template.written",
                "editor.icon", "editor.icon.note", "icon.title", "icon.choose",
                "icon.clear", "icon.filter", "icon.failed", "icon.set",
                "grid.addColumn", "grid.removeColumn", "grid.addRow", "grid.removeRow",
                "grid.noRoom", "grid.atMaximum", "settings.open", "settings.title",
                "settings.tab.interface", "settings.tab.game", "settings.tab.java",
                "settings.tab.downloads", "settings.tab.mods",
                "settings.tab.accounts", "settings.tab.data",
                // Self-updating: the window that offers it, the two channels,
                // and every line the update itself can end on.
                "splash.step.updates",
                "update.available.title", "update.available.header",
                "update.available.versions", "update.available.size",
                "update.available.notes", "update.available.notes.empty",
                "update.action.update", "update.action.later", "update.action.page",
                "update.manual.notImage", "update.manual.readOnly",
                "update.stage.download", "update.stage.unpack", "update.stage.apply",
                "update.failed", "update.check.action", "update.check.checking",
                "update.check.upToDate", "update.check.failed",
                "settings.update", "settings.update.note", "settings.update.channel",
                "settings.update.channel.release", "settings.update.channel.release.note",
                "settings.update.channel.nightly", "settings.update.channel.nightly.note",
                "settings.update.current",
                "settings.grid.columns", "settings.grid.rows", "settings.grid.note",
                "settings.grid.refusedHeader", "settings.grid.refusedColumns",
                "settings.grid.refusedRows", "settings.splash", "settings.splash.note",
                "settings.keepOpen", "settings.tray", "settings.tray.note",
                "settings.verify", "settings.verify.note",
                "settings.java", "settings.java.ask", "settings.java.always",
                "settings.java.never", "settings.java.note", "settings.concurrency",
                "settings.concurrency.note", "settings.curseforge",
                "settings.proxy", "settings.proxy.system", "settings.proxy.direct",
                "settings.proxy.manual", "settings.proxy.note", "settings.proxy.host",
                "settings.proxy.port", "settings.proxy.user", "settings.proxy.password",
                "settings.proxy.optional", "settings.proxy.privacy", "settings.proxy.test",
                "settings.proxy.testing", "settings.proxy.ok", "settings.proxy.failed",
                "settings.proxy.incomplete", "settings.proxy.notsaved",
                "settings.curseforge.prompt", "settings.signIn", "settings.signIn.browser",
                "settings.signIn.deviceCode", "settings.signIn.note",
                "settings.handshake", "settings.handshake.note", "settings.fileStore",
                "settings.fileStore.note", "settings.dataFolder", "settings.logs", "settings.logs.note", "log.gameLog",
                "dialog.close", "about.open", "about.title", "about.version",
                "about.what", "about.repository", "about.author", "about.builtOn",
                "about.licence",
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

    // ------------------------------------------------------- mod dependencies

    /**
     * Which mods need which, and what the launcher says before one goes away.
     *
     * <p>The graph is read from the jars, so this builds real ones: a library,
     * two mods that need it, a mod that needs one of those, and a switched-off
     * mod that needs the library too. Every value that decides whether a warning
     * appears is checked here, because the warning itself cannot be: it is a
     * dialog, and this runs with no screen.
     */
    private static void modDependencies() {
        section("Mod dependencies");

        Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("hexadron-deps-check");

            writeJar(dir.resolve("fabric-api.jar"), Map.of("fabric.mod.json",
                    "{\"id\":\"fabric-api\",\"version\":\"0.100.0\",\"name\":\"Fabric API\"}"));
            writeJar(dir.resolve("sodium.jar"), Map.of("fabric.mod.json", """
                    {"id":"sodium","version":"0.6.13","name":"Sodium",
                     "depends":{"minecraft":">=1.20","java":">=21",
                                "fabricloader":">=0.15","fabric-api":"*"}}"""));
            writeJar(dir.resolve("iris.jar"), Map.of("fabric.mod.json", """
                    {"id":"iris","version":"1.7","name":"Iris",
                     "depends":{"minecraft":">=1.20","sodium":"*"}}"""));
            writeJar(dir.resolve("indium.jar.disabled"), Map.of("fabric.mod.json", """
                    {"id":"indium","version":"1.0","name":"Indium",
                     "depends":{"fabric-api":"*"}}"""));

            LocalModInfo sodiumInfo = LocalModInfo.read(dir.resolve("sodium.jar")).orElseThrow();
            check("a Fabric mod's requirements are read",
                    sodiumInfo.depends().contains("fabric-api"));
            check("the loader and the game are not mods in the folder",
                    !sodiumInfo.depends().contains("minecraft")
                            && !sodiumInfo.depends().contains("fabricloader")
                            && !sodiumInfo.depends().contains("java"));

            List<ModEntry> mods = ModScan.scan(dir);
            ModDependents dependents = ModDependents.of(mods);

            ModEntry api = byTitle(mods, "Fabric API");
            ModEntry sodium = byTitle(mods, "Sodium");
            ModEntry iris = byTitle(mods, "Iris");

            check("a library knows what needs it", dependents.isNeeded(api));
            check("and says which", dependents.of(api).stream()
                    .anyMatch(mod -> "Sodium".equals(mod.title())));
            check("a mod that is needed by one thing is still needed",
                    dependents.isNeeded(sodium)
                            && dependents.of(sodium).size() == 1
                            && "Iris".equals(dependents.of(sodium).get(0).title()));
            check("nothing needs the mod at the end of the chain",
                    !dependents.isNeeded(iris));

            // A jar renamed to .disabled is not loaded, so it cannot break, and
            // a warning that it will is a warning about nothing.
            check("a switched-off mod does not hold anything back",
                    dependents.of(api).stream()
                            .noneMatch(mod -> "Indium".equals(mod.title())));

            check("a folder with one mod in it has no graph",
                    ModDependents.of(List.of(sodium)).size() == 0);
            check("neither has an empty one", ModDependents.of(List.of()).size() == 0);

            // Forge and NeoForge write "required" two different ways, and reading
            // one of them only reports half of what a folder actually needs.
            Path forge = dir.resolve("forgey.jar");
            writeJar(forge, Map.of("META-INF/mods.toml", String.join("\n",
                    "modLoader=\"javafml\"",
                    "[[mods]]",
                    "modId=\"forgey\"",
                    "displayName=\"Forgey\"",
                    "[[dependencies.forgey]]",
                    "modId=\"forge\"",
                    "mandatory=true",
                    "[[dependencies.forgey]]",
                    "modId=\"jei\"",
                    "mandatory=true",
                    "[[dependencies.forgey]]",
                    "modId=\"curios\"",
                    "mandatory=false")));
            LocalModInfo forgeInfo = LocalModInfo.read(forge).orElseThrow();
            check("a mandatory Forge dependency is a requirement",
                    forgeInfo.depends().contains("jei"));
            check("an optional one is not",
                    !forgeInfo.depends().contains("curios"));
            check("the loader is not one either",
                    !forgeInfo.depends().contains("forge"));

            Path neo = dir.resolve("neo.jar");
            writeJar(neo, Map.of("META-INF/neoforge.mods.toml", String.join("\n",
                    "modLoader=\"javafml\"",
                    "[[mods]]",
                    "modId=\"neo\"",
                    "displayName=\"Neo\"",
                    "[[dependencies.neo]]",
                    "modId=\"jade\"",
                    "type=\"required\"",
                    "[[dependencies.neo]]",
                    "modId=\"emi\"",
                    "type=\"optional\"")));
            LocalModInfo neoInfo = LocalModInfo.read(neo).orElseThrow();
            check("NeoForge spells required its own way",
                    neoInfo.depends().equals(List.of("jade")));

            Path quilt = dir.resolve("quilted.jar");
            writeJar(quilt, Map.of("quilt.mod.json", """
                    {"quilt_loader":{"id":"quilted","version":"1.0",
                       "depends":[{"id":"minecraft","versions":"1.20.1"},
                                  {"id":"qsl"},
                                  {"id":"extra","optional":true}]}}"""));
            LocalModInfo quiltInfo = LocalModInfo.read(quilt).orElseThrow();
            check("a Quilt requirement is read",
                    quiltInfo.depends().equals(List.of("qsl")));

            Path legacy = dir.resolve("legacy.jar");
            writeJar(legacy, Map.of("mcmod.info", """
                    [{"modid":"legacy","name":"Legacy","version":"1.0",
                      "requiredMods":["jei@[4.0,)","codechickenlib"]}]"""));
            LocalModInfo legacyInfo = LocalModInfo.read(legacy).orElseThrow();
            check("a version range is not part of a mod's name",
                    legacyInfo.depends().equals(List.of("jei", "codechickenlib")));

            // The dialog offers to stop appearing, so the answer has to outlive
            // the session that gave it - and has to be findable again, which is
            // what the switch in the settings window is for.
            GameDirs dirs = new GameDirs(dir.resolve("data"));
            com.hexadron.launcher.core.LauncherSettings settings =
                    new com.hexadron.launcher.core.LauncherSettings(dirs);
            check("the warning is on to begin with", settings.warnAboutDependents());

            // The logo cache size. Bounded on both sides, and the bounds are
            // applied to what a hand-edited file says as well as to what the
            // settings window sends: a nought in that file would otherwise mean
            // fetching every picture again on every scroll.
            check("the logo cache starts at thirty-two megabytes",
                    settings.modIconCacheMegabytes() == 32);
            check("and that is what it is in bytes",
                    settings.modIconCacheBytes() == 32L * 1024 * 1024);
            settings.modIconCacheMegabytes(256);
            settings.save();
            check("a chosen size survives a restart",
                    new com.hexadron.launcher.core.LauncherSettings(dirs)
                            .load().modIconCacheMegabytes() == 256);
            settings.modIconCacheMegabytes(0);
            check("nought is lifted to the smallest cache worth keeping",
                    settings.modIconCacheMegabytes()
                            == com.hexadron.launcher.core.LauncherSettings.MOD_ICON_CACHE_MIN);
            settings.modIconCacheMegabytes(1_000_000);
            check("and a typo is held to the ceiling",
                    settings.modIconCacheMegabytes()
                            == com.hexadron.launcher.core.LauncherSettings.MOD_ICON_CACHE_MAX);
            settings.modIconCacheMegabytes(32);
            settings.save();
            settings.warnAboutDependents(false);
            settings.save();
            check("and 'do not show this again' survives a restart",
                    !new com.hexadron.launcher.core.LauncherSettings(dirs)
                            .load().warnAboutDependents());
            settings.warnAboutDependents(true);
            settings.save();
            check("and can be turned back on",
                    new com.hexadron.launcher.core.LauncherSettings(dirs)
                            .load().warnAboutDependents());

        } catch (IOException e) {
            check("mod dependencies could be read: " + e, false);
        } finally {
            deleteRecursively(dir);
        }
    }


    // ---------------------------------------------------------- self-updating

    /**
     * The launcher replacing itself: which build is newer, which file is for
     * this machine, and where the installed one lives.
     *
     * <p>Every part of that except the swap itself is checked here, because
     * every part of it is a decision that can be wrong silently. A version
     * comparison that reads 0.9.10 as older than 0.9.9 offers a downgrade; an
     * asset rule that picks the jar for a Windows user replaces a working client
     * with something that cannot start; a layout rule that misses the install
     * root would have the updater write a new launcher into the wrong folder.
     *
     * <p>The swap is not checked here, and cannot honestly be: it happens in a
     * second process, after this one has exited, on three operating systems that
     * treat open files differently. What is checked is everything it is given.
     */
    private static void launcherUpdates() {
        section("Launcher updates");

        // ------------------------------------------------------------ versions
        check("a newer patch is newer", newer("0.9.5", "0.9.4.5"));
        check("ten is after nine, which a string comparison gets backwards",
                newer("0.9.10", "0.9.9"));
        check("a leading v is not part of the number",
                AppVersion.of("v1.0.0").orElseThrow()
                        .equals(AppVersion.of("1.0.0").orElseThrow()));
        check("a missing number is a zero",
                AppVersion.of("1.2").orElseThrow().equals(AppVersion.of("1.2.0").orElseThrow()));
        check("build metadata takes no part in it",
                AppVersion.of("1.0.0+abc123").orElseThrow()
                        .equals(AppVersion.of("1.0.0").orElseThrow()));

        // The rule that makes a nightly channel work at all.
        check("a release is newer than its own pre-releases",
                newer("1.0.0", "1.0.0-nightly.20260902"));
        check("a later nightly is newer than an earlier one",
                newer("1.0.0-nightly.20260902", "1.0.0-nightly.20260901"));
        check("nightly numbers are compared as numbers",
                newer("1.0.0-nightly.10", "1.0.0-nightly.9"));
        check("and a nightly of a newer version wins over the older release",
                newer("1.1.0-nightly.1", "1.0.0"));
        check("nothing is newer than itself", !newer("1.0.0", "1.0.0"));
        check("a version that is not a version is refused",
                AppVersion.of("beta").isEmpty() && AppVersion.of("").isEmpty()
                        && AppVersion.of(null).isEmpty());

        // ------------------------------------------------------------ channels
        check("the nightly channel is stored and read back",
                UpdateChannel.parse(UpdateChannel.NIGHTLY.stored()) == UpdateChannel.NIGHTLY);
        check("anything unreadable means the safe channel",
                UpdateChannel.parse("something else") == UpdateChannel.RELEASE
                        && UpdateChannel.parse(null) == UpdateChannel.RELEASE);
        check("only nightly takes pre-releases",
                UpdateChannel.NIGHTLY.acceptsPrereleases()
                        && !UpdateChannel.RELEASE.acceptsPrereleases());

        // ------------------------------------------------------------ releases
        Json feed = Json.parse("""
                [
                  {"tag_name":"v1.1.0","name":"draft","draft":true,"prerelease":false,
                   "assets":[]},
                  {"tag_name":"v1.0.1-nightly.7","name":"nightly","draft":false,
                   "prerelease":true,"body":"- a change\\n- another",
                   "html_url":"https://example.invalid/n","published_at":"2026-09-02T00:00:00Z",
                   "assets":[{"name":"HexadronLauncher-windows.zip","size":123,
                              "browser_download_url":"https://example.invalid/w.zip"}]},
                  {"tag_name":"v1.0.0","name":"release","draft":false,"prerelease":false,
                   "body":"first","html_url":"https://example.invalid/r",
                   "published_at":"2026-09-01T00:00:00Z",
                   "assets":[
                     {"name":"launcher-1.0.0.jar","size":9,
                      "browser_download_url":"https://example.invalid/j.jar"},
                     {"name":"HexadronLauncher-windows.zip","size":10,
                      "browser_download_url":"https://example.invalid/w.zip"},
                     {"name":"HexadronLauncher-linux.tar.gz","size":11,
                      "browser_download_url":"https://example.invalid/l.tgz"},
                     {"name":"HexadronLauncher-macos.tar.gz","size":12,
                      "browser_download_url":"https://example.invalid/m.tgz"}]}
                ]""");
        List<ReleaseFeed.Release> releases = ReleaseFeed.parseAll(feed);
        check("every release in the list is read", releases.size() == 3);

        ReleaseFeed.Release stable = ReleaseFeed.newest(releases, UpdateChannel.RELEASE).orElseThrow();
        check("the release channel skips drafts and pre-releases",
                "v1.0.0".equals(stable.tag()));
        ReleaseFeed.Release nightly = ReleaseFeed.newest(releases, UpdateChannel.NIGHTLY).orElseThrow();
        check("the nightly channel takes the newest of them",
                "v1.0.1-nightly.7".equals(nightly.tag()));
        check("a draft is not published to either",
                releases.stream().anyMatch(ReleaseFeed.Release::draft)
                        && !"v1.1.0".equals(nightly.tag()));

        // The asset rule. Each system gets its own build and never the jar.
        check("Windows gets the zip",
                stable.assetFor(Platform.OsFamily.WINDOWS).orElseThrow().name()
                        .equals("HexadronLauncher-windows.zip"));
        check("Linux gets its tarball",
                stable.assetFor(Platform.OsFamily.LINUX).orElseThrow().name()
                        .equals("HexadronLauncher-linux.tar.gz"));
        check("macOS gets its own",
                stable.assetFor(Platform.OsFamily.OSX).orElseThrow().name()
                        .equals("HexadronLauncher-macos.tar.gz"));
        check("and nobody is handed the bare jar",
                !ReleaseFeed.matches("launcher-1.0.0.jar", Platform.OsFamily.WINDOWS)
                        && !ReleaseFeed.matches("launcher-1.0.0.jar", Platform.OsFamily.LINUX)
                        && !ReleaseFeed.matches("launcher-1.0.0.jar", Platform.OsFamily.OSX));
        check("a release with no build for this system is not an update",
                Updates.compare("0.1.0", releases.get(0), Platform.OsFamily.WINDOWS).isEmpty());

        // ------------------------------------------------------------ the offer
        check("an older launcher is offered the release",
                Updates.compare("0.9.4.5", stable, Platform.OsFamily.WINDOWS).isPresent());
        check("the same version is offered nothing",
                Updates.compare("1.0.0", stable, Platform.OsFamily.WINDOWS).isEmpty());
        check("and a newer one is offered nothing either",
                Updates.compare("1.2.0", stable, Platform.OsFamily.WINDOWS).isEmpty());
        Updates.Available offer =
                Updates.compare("0.9.4.5", stable, Platform.OsFamily.LINUX).orElseThrow();
        check("the offer carries both versions and the file",
                "0.9.4.5".equals(offer.from().text())
                        && "v1.0.0".equals(offer.to().text())
                        && offer.size() == 11
                        && offer.notes().contains("first"));

        // ------------------------------------------------------------- layouts
        Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("hexadron-update-check");
            checkLayout(dir, Platform.OsFamily.WINDOWS, "HexadronLauncher",
                    "app", "runtime/bin/java.exe", "HexadronLauncher.exe");
            checkLayout(dir, Platform.OsFamily.LINUX, "linux-image",
                    "lib/app", "lib/runtime/bin/java", "bin/HexadronLauncher");
            checkLayout(dir, Platform.OsFamily.OSX, "HexadronLauncher.app",
                    "Contents/app", "Contents/runtime/Contents/Home/bin/java",
                    "Contents/MacOS/HexadronLauncher");

            // A jar that is not inside an image at all: a development run, and
            // there is nothing there to replace.
            Path loose = dir.resolve("loose");
            java.nio.file.Files.createDirectories(loose);
            Path looseJar = loose.resolve("launcher.jar");
            writeJar(looseJar, Map.of("a.txt", "b"));
            check("a launcher that is not installed has nothing to update",
                    UpdateInstall.detect(looseJar, Platform.OsFamily.WINDOWS).isEmpty());

            // The archive shapes the workflow actually produces: the image at the
            // top, and the image one level down beside a note.
            Path nested = dir.resolve("unpacked");
            java.nio.file.Files.createDirectories(nested);
            java.nio.file.Files.writeString(nested.resolve("HOW-TO-RUN.txt"), "run me");
            image(nested.resolve("HexadronLauncher"), Platform.OsFamily.WINDOWS,
                    "app", "runtime/bin/java.exe", "HexadronLauncher.exe");
            check("the image is found inside an unpacked archive",
                    UpdateInstall.imageIn(nested, Platform.OsFamily.WINDOWS)
                            .map(path -> path.getFileName().toString())
                            .orElse("").equals("HexadronLauncher"));

            // The jar the updater is started from has to be the one with the
            // updater in it, not the first jar in the folder.
            Path image = dir.resolve("HexadronLauncher");
            Path appDir = image.resolve("app");
            writeJar(appDir.resolve("javafx-controls-25.jar"), Map.of("javafx/Thing.class", "x"));
            writeJar(appDir.resolve("launcher-1.0.0.jar"),
                    Map.of("com/hexadron/launcher/update/Updater.class", "x"));
            check("the launcher jar is picked out of the folder by what is in it",
                    Updates.launcherJarIn(new UpdateInstall(image, Platform.OsFamily.WINDOWS))
                            .map(path -> path.getFileName().toString())
                            .orElse("").equals("launcher-1.0.0.jar"));

            // Copying an image must keep what makes it runnable.
            Path source = dir.resolve("tree");
            java.nio.file.Files.createDirectories(source.resolve("bin"));
            Path binary = source.resolve("bin/HexadronLauncher");
            java.nio.file.Files.writeString(binary, "#!/bin/sh\nexit 0\n");
            boolean executableSet = false;
            try {
                java.nio.file.Files.setPosixFilePermissions(binary,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                executableSet = true;
            } catch (UnsupportedOperationException ignored) {
                // Windows, where the bit does not exist and nothing can lose it.
            }
            java.nio.file.Files.createSymbolicLink(source.resolve("current"),
                    java.nio.file.Path.of("bin"));
            Path copy = dir.resolve("tree-copy");
            Updates.copyTree(source, copy);
            check("a copied image keeps its files",
                    java.nio.file.Files.isRegularFile(copy.resolve("bin/HexadronLauncher")));
            check("a copied image keeps its links",
                    java.nio.file.Files.isSymbolicLink(copy.resolve("current")));
            check("and the launcher stays executable",
                    !executableSet || java.nio.file.Files.isExecutable(
                            copy.resolve("bin/HexadronLauncher")));

        } catch (IOException e) {
            check("update layouts could be read: " + e, false);
        } finally {
            deleteRecursively(dir);
        }

        // ------------------------------------------------------------ settings
        Path settingsDir = null;
        try {
            settingsDir = java.nio.file.Files.createTempDirectory("hexadron-update-settings");
            GameDirs dirs = new GameDirs(settingsDir);
            com.hexadron.launcher.core.LauncherSettings settings =
                    new com.hexadron.launcher.core.LauncherSettings(dirs);
            check("the check is on by default", settings.checkForUpdates());
            check("and the channel is the stable one",
                    settings.updateChannel() == UpdateChannel.RELEASE);
            settings.checkForUpdates(false).updateChannel(UpdateChannel.NIGHTLY);
            settings.save();
            com.hexadron.launcher.core.LauncherSettings reread =
                    new com.hexadron.launcher.core.LauncherSettings(dirs).load();
            check("both survive a restart",
                    !reread.checkForUpdates() && reread.updateChannel() == UpdateChannel.NIGHTLY);
        } catch (IOException e) {
            check("update settings could be written: " + e, false);
        } finally {
            deleteRecursively(settingsDir);
        }
    }

    /** True when the first version is newer than the second. */
    private static boolean newer(String first, String second) {
        return AppVersion.of(first).orElseThrow().isNewerThan(AppVersion.of(second).orElseThrow());
    }

    /** Builds an application image of one platform's shape and reads it back. */
    private static void checkLayout(Path dir, Platform.OsFamily os, String name,
                                    String appDir, String runtime, String executable)
            throws IOException {

        Path root = image(dir.resolve(name), os, appDir, runtime, executable);
        Path jar = root.resolve(appDir).resolve("launcher.jar");
        writeJar(jar, Map.of("a.txt", "b"));

        UpdateInstall install = UpdateInstall.detect(jar, os).orElseThrow(
                () -> new IOException("the " + os + " image was not recognised"));
        check(os + ": the installed folder is found", install.root().equals(root));
        check(os + ": its jars are where they should be",
                install.appDirectory().equals(root.resolve(appDir)));
        check(os + ": the bundled runtime is found",
                install.javaExecutable().equals(root.resolve(runtime)));
        check(os + ": the launcher to start again is found",
                install.launcherExecutable().equals(root.resolve(executable)));
        check(os + ": the work folder is beside the install, not inside it",
                Updates.workDirectory(install).equals(dir.resolve(Updates.WORK_DIR)));
    }

    /** Writes the bare bones of an application image. */
    private static Path image(Path root, Platform.OsFamily os, String appDir,
                              String runtime, String executable) throws IOException {
        java.nio.file.Files.createDirectories(root.resolve(appDir));
        java.nio.file.Files.createDirectories(root.resolve(runtime).getParent());
        java.nio.file.Files.writeString(root.resolve(runtime), "java");
        java.nio.file.Files.createDirectories(root.resolve(executable).getParent());
        java.nio.file.Files.writeString(root.resolve(executable), "launcher");
        return root;
    }

    // ---------------------------------------------------------------- categories

    /**
     * The categories a mod is filed under, and the drawings beside their names.
     *
     * <p>Two things are checked here. That the loaders are kept out of the
     * category list - Modrinth stores {@code fabric} in the same field it stores
     * {@code magic}, and a filter built from the raw list offers to narrow a
     * Fabric search to Forge mods. And that the little drawings survive being
     * read out of the markup they are published as, which is untrusted text
     * turned into shapes and therefore worth checking without a display.
     */

    private static void modCategories() {
        section("Mod categories");

        check("a category is recognised by its identifier",
                ModCategory.byId("worldgen").orElseThrow() == ModCategory.WORLDGEN);
        check("a hyphenated identifier is recognised",
                ModCategory.byId("game-mechanics").orElseThrow() == ModCategory.GAME_MECHANICS);
        check("case and spacing do not matter",
                ModCategory.byId("  Magic ").orElseThrow() == ModCategory.MAGIC);
        check("a loader is not a category", ModCategory.byId("fabric").isEmpty());
        check("neither is anything else", ModCategory.byId("client").isEmpty());
        check("nor nothing at all", ModCategory.byId(null).isEmpty());

        // The list a project publishes carries its loaders in with its
        // categories, and the same category twice when both fields are read.
        List<ModCategory> parsed = ModCategory.parse(
                List.of("fabric", "magic", "quilt", "storage", "magic", "neoforge"));
        check("the loaders are dropped", parsed.size() == 2);
        check("the categories are kept in order",
                parsed.equals(List.of(ModCategory.MAGIC, ModCategory.STORAGE)));
        check("an empty list stays empty", ModCategory.parse(List.of()).isEmpty());
        check("identifiers come back out as they went in",
                ModCategory.idsOf(parsed).equals(List.of("magic", "storage")));

        // The full list, not the three an author features on the platform's own
        // card. Voxy WorldGen is filed under six things and used to say three,
        // which is a shorter answer than the truth with nothing to show it was
        // short. The loaders come in the same field and still have to go.
        List<ModCategory> voxy = ModCategory.parse(List.of("fabric", "game-mechanics",
                "management", "neoforge", "optimization", "storage", "utility", "worldgen"));
        check("every category a project is filed under is kept", voxy.size() == 6);
        check("and the loaders in the same field are not",
                !voxy.contains(ModCategory.LIBRARY) && voxy.contains(ModCategory.WORLDGEN)
                        && voxy.contains(ModCategory.STORAGE));

        // What is being filtered on comes first, and nothing else moves.
        List<ModCategory> row = List.of(ModCategory.GAME_MECHANICS, ModCategory.MANAGEMENT,
                ModCategory.OPTIMIZATION, ModCategory.STORAGE);
        check("the chosen categories come first",
                ModCategory.chosenFirst(row,
                        Set.of(ModCategory.STORAGE, ModCategory.MANAGEMENT))
                        .equals(List.of(ModCategory.MANAGEMENT, ModCategory.STORAGE,
                                ModCategory.GAME_MECHANICS, ModCategory.OPTIMIZATION)));
        check("the rest keep the order they arrived in",
                ModCategory.chosenFirst(row, Set.of(ModCategory.OPTIMIZATION))
                        .equals(List.of(ModCategory.OPTIMIZATION, ModCategory.GAME_MECHANICS,
                                ModCategory.MANAGEMENT, ModCategory.STORAGE)));
        check("nothing moves when nothing is chosen",
                ModCategory.chosenFirst(row, Set.of()).equals(row));
        check("nor when the chosen ones are not on this row",
                ModCategory.chosenFirst(row, Set.of(ModCategory.MAGIC)).equals(row));
        check("a row keeps every category it had",
                ModCategory.chosenFirst(row, Set.of(ModCategory.STORAGE)).size() == row.size());
        check("one category is left alone",
                ModCategory.chosenFirst(List.of(ModCategory.MAGIC), Set.of(ModCategory.MAGIC))
                        .equals(List.of(ModCategory.MAGIC)));

        // --- the drawings
        check("nothing is read out of nothing", SvgPaths.read(null).isEmpty());
        check("nor out of an empty string", SvgPaths.read("  ").isEmpty());
        check("nor out of markup with no shapes in it",
                SvgPaths.read("<svg viewBox=\"0 0 24 24\"><title>x</title></svg>").isEmpty());

        // A real one, exactly as the platform publishes it.
        List<String> adventure = SvgPaths.read(
                "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\""
                        + " stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">"
                        + "<circle cx=\"12\" cy=\"12\" r=\"10\"/>"
                        + "<polygon points=\"16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88"
                        + " 16.24 7.76\"/></svg>");
        check("a published drawing yields one path per shape", adventure.size() == 2);
        check("a circle becomes two arcs",
                adventure.get(0).startsWith("M 2.0 12.0") && countOf(adventure.get(0), " a ") == 2);
        check("a circle is closed", adventure.get(0).endsWith("Z"));
        check("a polygon becomes a closed run of points",
                adventure.get(1).startsWith("M 16.24 7.76") && adventure.get(1).endsWith(" Z"));
        check("a polygon keeps every point", countOf(adventure.get(1), " L ") == 4);

        check("a path is taken as it is",
                SvgPaths.read("<path d=\"M9.09 9a3 3 0 0 1 5.83 1\"/>")
                        .equals(List.of("M9.09 9a3 3 0 0 1 5.83 1")));
        check("a closing tag is not a second shape",
                SvgPaths.read("<path d=\"M1 1 L2 2\"></path>").size() == 1);
        check("a line becomes a move and a line",
                SvgPaths.read("<line x1=\"12\" y1=\"17\" x2=\"12.01\" y2=\"17\"/>")
                        .equals(List.of("M 12.0 17.0 L 12.01 17.0")));
        check("a polyline is not closed",
                !SvgPaths.read("<polyline points=\"1,1 2,2 3,3\"/>").get(0).endsWith("Z"));
        check("commas and spaces are both separators",
                SvgPaths.read("<polyline points=\"1,1 2,2\"/>")
                        .equals(SvgPaths.read("<polyline points=\"1 1 2 2\"/>")));
        check("an odd number of points is refused",
                SvgPaths.read("<polyline points=\"1 1 2\"/>").isEmpty());

        List<String> square = SvgPaths.read("<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\"/>");
        check("a plain rectangle is four sides", square.size() == 1
                && square.get(0).equals("M 3.0 3.0 h 18.0 v 18.0 h -18.0 Z"));
        List<String> rounded =
                SvgPaths.read("<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\"/>");
        check("a rounded rectangle has four corners",
                countOf(rounded.get(0), " a ") == 4 && rounded.get(0).endsWith("Z"));

        check("an ellipse uses both radii",
                SvgPaths.read("<ellipse cx=\"12\" cy=\"12\" rx=\"10\" ry=\"4\"/>")
                        .get(0).contains(" a 10.0 4.0 "));
        check("a shape with no size is left out",
                SvgPaths.read("<circle cx=\"12\" cy=\"12\" r=\"0\"/>").isEmpty());
        check("an element this does not draw is left out",
                SvgPaths.read("<text x=\"1\" y=\"1\">hello</text>").isEmpty());
        check("the shapes it does draw survive one it does not",
                SvgPaths.read("<g><text>x</text><line x1=\"1\" y1=\"1\" x2=\"2\" y2=\"2\"/></g>")
                        .size() == 1);

        // Untrusted text: whatever comes back, nothing may throw at a caller
        // that is drawing a list.
        int survived = 0;
        String[] rubbish = {"<", "<path", "<path d=", "<path d=\"", "<circle cx=\"a\" r=\"b\"/>",
            "<rect width=\"-5\" height=\"3\"/>", "<polygon points=\"\"/>", "<<<>>>",
            "<path d=\"\"/>", "<line/>"};
        for (String text : rubbish) {
            try {
                SvgPaths.read(text);
                survived++;
            } catch (RuntimeException e) {
                // Counted by not counting.
            }
        }
        check("no malformed markup throws", survived == rubbish.length);
    }

    /**
     * The order the categories are offered in, in each language.
     *
     * <p>A list of names is sorted with the alphabet of whoever is reading it,
     * and the obvious tool gets that wrong in three of the five languages this
     * launcher speaks. {@code String.CASE_INSENSITIVE_ORDER} compares code
     * points after lowering the case, and Ukrainian і and ї live at U+0456 and
     * U+0457 - above the whole of а-я - so they came out after the last word in
     * the menu instead of between "Економіка" and "Керування". Polish ą, ć, ł
     * and German ä, ö, ü are outside their alphabet's block for the same reason.
     *
     * <p>Checked against the real translation files rather than made-up strings,
     * because what is being checked is the order of the actual menu.
     */
    private static void categoryOrder() {
        section("Category order");

        check("English is in English order",
                orderIn(Language.ENGLISH).startsWith("Adventure, Cursed, Decoration, Economy"));

        // The one the bug was found in. І and Ї belong after Е and before К.
        String ukrainian = orderIn(byCode("uk"));
        check("Ukrainian starts at Б", ukrainian.startsWith("Бібліотека, Взаємодія"));
        check("Ukrainian puts І and Ї in the alphabet, not after it",
                ukrainian.contains("Економіка, Ігрові механіки, Їжа, Керування"));
        check("Ukrainian ends at Ч", ukrainian.endsWith("Технології, Чаклунство"));
        check("nothing is lost or repeated by the sort",
                ukrainian.split(", ").length == ModCategory.values().length);

        // Polish: ł sorts inside L, and ś inside S.
        String polish = orderIn(byCode("pl"));
        check("Polish puts ł where l is", polish.contains("Minigra, Narzędzia"));
        check("Polish sorts ś as s", polish.contains("Generowanie świata, Interakcja"));

        // German: ü sorts as u, so Ausrüstung stays under A.
        String german = orderIn(byCode("de"));
        check("German starts at A", german.startsWith("Abenteuer, Ausrüstung, Bibliothek"));
        check("German ends at W", german.endsWith("Werkzeuge, Wirtschaft"));

        // Russian has no letters outside its block, so it is the control: if
        // this one were ever wrong, the collator itself would be the problem.
        String russian = orderIn(byCode("ru"));
        check("Russian is in Russian order", russian.startsWith("Библиотека, Взаимодействие"));

        for (Language language : Language.all()) {
            check("every category is offered in " + language.code(),
                    ModCategory.inReadingOrder(language.locale(),
                            category -> I18n.bundle(language).get(category.key()))
                            .size() == ModCategory.values().length);
        }
    }

    /** The category names of one language, in the order the menu would offer them. */
    private static String orderIn(Language language) {
        Map<String, String> bundle = I18n.bundle(language);
        List<String> names = new ArrayList<>();
        for (ModCategory category : ModCategory.inReadingOrder(language.locale(),
                category -> bundle.getOrDefault(category.key(), category.id()))) {
            names.add(bundle.getOrDefault(category.key(), category.id()));
        }
        return String.join(", ", names);
    }

    private static Language byCode(String code) {
        return Language.byCode(code).orElseThrow();
    }

    /** How many times one string occurs in another. */
    private static int countOf(String text, String part) {
        int count = 0;
        int at = text.indexOf(part);
        while (at >= 0) {
            count++;
            at = text.indexOf(part, at + part.length());
        }
        return count;
    }

    // ---------------------------------------------------------------- importing

    /**
     * Bringing a folder of downloaded jars into an instance.
     *
     * <p>What has to hold: the originals stay where they were, nothing already
     * in the instance is overwritten, and everything refused is refused by name.
     * A silent skip in a batch of twenty is a mod the player will spend an
     * evening looking for.
     */
    private static void modImport() {
        section("Importing mods");

        Path source = null;
        Path target = null;
        try {
            source = java.nio.file.Files.createTempDirectory("hexadron-import-source");
            target = java.nio.file.Files.createTempDirectory("hexadron-import-target");

            Path good = source.resolve("goodmod-1.0.jar");
            writeJar(good, Map.of("fabric.mod.json",
                    "{\"id\":\"good\",\"version\":\"1.0\",\"name\":\"Good Mod\"}"));
            Path second = source.resolve("another-2.0.jar");
            writeJar(second, Map.of("fabric.mod.json",
                    "{\"id\":\"another\",\"version\":\"2.0\",\"name\":\"Another Mod\"}"));
            Path notAJar = source.resolve("readme.txt");
            java.nio.file.Files.writeString(notAJar, "not a mod");
            Path notAZip = source.resolve("broken.jar");
            java.nio.file.Files.writeString(notAZip, "this is not an archive");
            Path noMod = source.resolve("library.jar");
            writeJar(noMod, Map.of("some/class/File.txt", "no descriptor here"));

            List<Path> all = List.of(good, second, notAJar, notAZip, noMod);
            ModScan.Imported first = ModScan.importJars(target, all, QUIET);

            check("the readable mods are imported", first.imported().size() == 3);
            check("both land in the folder",
                    java.nio.file.Files.isRegularFile(target.resolve("goodmod-1.0.jar"))
                            && java.nio.file.Files.isRegularFile(target.resolve("another-2.0.jar")));
            check("two files are refused", first.skipped().size() == 2);
            check("a refusal names the file",
                    first.skipped().stream().anyMatch(skip -> skip.file().equals("readme.txt")));
            check("something that is not a jar says so",
                    first.skipped().stream().anyMatch(skip ->
                            skip.reason() == ModScan.Reason.NOT_A_JAR));
            check("a jar that is not an archive is refused",
                    first.skipped().stream().anyMatch(skip ->
                            skip.file().equals("broken.jar")
                                    && skip.reason() == ModScan.Reason.NOT_AN_ARCHIVE));

            // A jar whose descriptor this launcher cannot read is still a jar,
            // and the same file dragged into the folder by hand is listed
            // without complaint. Refusing it here made the button disagree with
            // the file manager about the same file.
            check("a jar with no descriptor is still imported",
                    java.nio.file.Files.isRegularFile(target.resolve("library.jar")));
            check("and a refusal carries a reason rather than an English sentence",
                    first.skipped().stream().allMatch(skip -> skip.reason() != null));

            // Copied, not moved. The files are the player's, sitting where they
            // downloaded them, and emptying that folder is not the launcher's
            // decision to take.
            check("the originals are left alone", java.nio.file.Files.isRegularFile(good)
                    && java.nio.file.Files.isRegularFile(second));

            // Importing the same batch again must not quietly replace what is
            // already installed: a version and a config folder can be behind it.
            java.nio.file.Files.writeString(target.resolve("goodmod-1.0.jar.marker"), "x");
            long before = java.nio.file.Files.size(target.resolve("goodmod-1.0.jar"));
            ModScan.Imported again = ModScan.importJars(target, all, QUIET);
            check("a second import adds nothing", again.imported().isEmpty());
            check("a file already there is refused by name",
                    again.skipped().stream().anyMatch(skip ->
                            skip.file().equals("goodmod-1.0.jar")
                                    && skip.reason() == ModScan.Reason.ALREADY_THERE));
            check("and is not overwritten",
                    java.nio.file.Files.size(target.resolve("goodmod-1.0.jar")) == before);

            // An imported jar is the player's own, and is listed as such: the
            // launcher did not download it and has no record of where it came
            // from, so nothing may treat it as its own to replace.
            List<ModEntry> mods = ModScan.scan(target);
            ModEntry imported = byTitle(mods, "Good Mod");
            check("an imported mod is listed", imported != null);
            check("an imported mod is the user's own",
                    imported.origin() == ModOrigin.EXTERNAL);
            check("an imported mod is removable", imported.isRemovable());

            check("importing nothing is not an error",
                    ModScan.importJars(target, List.of(), QUIET).imported().isEmpty());

        } catch (IOException e) {
            check("mods could be imported: " + e, false);
        } finally {
            deleteRecursively(source);
            deleteRecursively(target);
        }
    }

    // ---------------------------------------------------------------- version ranges

    /**
     * Whether a mod's declared Minecraft requirement admits a version.
     *
     * <p>The strings below are not invented. Every one of them was taken from a
     * real crash report: a profile whose Minecraft version was changed from 26.2
     * to 1.20.1 after its mods were installed, which started the game, was
     * refused by the loader, and produced forty lines naming each mod and the
     * version it actually wanted. All of that was readable from the jars before
     * anything started, and this is the check that reads it.
     *
     * <p>The second half matters as much as the first. A false "this mod is for
     * another version" on a pack that works teaches the player to click past the
     * warning, and then the true one goes past too - so anything that cannot be
     * read with confidence has to come back UNKNOWN rather than guessed at.
     */
    private static void modVersionRanges() {
        section("Mod version ranges");

        // --- what the loader refused, checked against the version it refused it on
        String on = "1.20.1";
        check("~26.2 excludes 1.20.1", refuses("~26.2", on));
        check("~26.2- excludes 1.20.1", refuses("~26.2-", on));
        check(">=26.1 <27 excludes 1.20.1", refuses(">=26.1 <27", on));
        check(">=26.2 excludes 1.20.1", refuses(">=26.2", on));
        check(">=26.2- excludes 1.20.1", refuses(">=26.2-", on));
        check(">=26.2 <26.3-alpha.3 excludes 1.20.1", refuses(">=26.2 <26.3-alpha.3", on));
        check(">=26.2 <26.3 excludes 1.20.1", refuses(">=26.2 <26.3", on));
        check(">=26.1-rc.2 excludes 1.20.1", refuses(">=26.1-rc.2", on));
        check(">=1.20.5-beta.1 excludes 1.20.1", refuses(">=1.20.5-beta.1", on));

        // --- what a real 1.20.1 pack declares, which must never be flagged
        check("an exact version admits itself", admits("1.20.1", on));
        check("~1.20.1 admits 1.20.1", admits("~1.20.1", on));
        check("~1.20 admits 1.20.1", admits("~1.20", on));
        check("a two-sided range admits what is inside it", admits(">=1.20.1 <1.21", on));
        check("1.20.x admits 1.20.1", admits("1.20.x", on));
        check("1.20.* admits 1.20.1", admits("1.20.*", on));
        check("^1.20 admits 1.20.1", admits("^1.20", on));
        check("a star admits anything", admits("*", on));
        // The distinction a reader of fabric.mod.json gets backwards: a list of
        // ranges is satisfied by ANY of them, while ranges written inside one
        // string all have to hold. ">=1.20.1 <1.21" is a window; the array
        // [">=1.20.1", "<1.21"] is every version there has ever been.
        check("a list of ranges is satisfied by any one of them",
                VersionRanges.fabric(List.of(">=1.21", "1.20.1"), on)
                        == VersionRanges.Verdict.MATCHES);
        check("ranges inside one string all have to hold",
                refuses(">=1.20.1 <1.21", "1.21"));
        check("a list of the same two admits what neither window would",
                VersionRanges.fabric(List.of(">=1.20.1", "<1.21"), "26.2")
                        == VersionRanges.Verdict.MATCHES);

        // --- the boundaries themselves
        check("~1.20.1 stops before 1.21", refuses("~1.20.1", "1.21"));
        check("~1.20.1 does not reach back", refuses("~1.20.1", "1.20"));
        check("^1.20 reaches 1.21", admits("^1.20", "1.21"));
        check("^1.20 stops before 2", refuses("^1.20", "2.0"));
        check("1.20.x stops before 1.21", refuses("1.20.x", "1.21"));
        check("a missing component counts as zero", admits(">=1.20", "1.20"));

        // --- pre-releases, which are older than the release they lead to
        check("a release candidate is below its release", refuses(">=26.2", "26.2-rc.1"));
        check("a bare dash admits the pre-releases", admits(">=26.2-", "26.2-rc.1"));
        check("a release is above its own candidates", admits(">=26.2-rc.2", "26.2"));
        check("build metadata does not change the order", admits("=1.20.1", "1.20.1+build.7"));

        // --- silence unless certain
        check("a snapshot version is not judged",
                VersionRanges.fabric(List.of("~1.20"), "23w31a")
                        == VersionRanges.Verdict.UNKNOWN);
        check("an unreadable range is not judged",
                VersionRanges.fabric(List.of("somewhere around 1.20"), on)
                        == VersionRanges.Verdict.UNKNOWN);
        check("one unreadable alternative makes the whole answer unsafe",
                VersionRanges.fabric(List.of(">=26.2", "whatever"), on)
                        == VersionRanges.Verdict.UNKNOWN);
        check("no declaration is not a refusal",
                VersionRanges.fabric(List.of(), on) == VersionRanges.Verdict.UNKNOWN);
        check("no version is not a refusal",
                VersionRanges.fabric(List.of("~1.20"), null) == VersionRanges.Verdict.UNKNOWN);

        // --- Maven ranges, which is how Forge and NeoForge write the same thing
        check("a Maven range admits its lower bound",
                VersionRanges.maven("[1.20.1,1.21)", on) == VersionRanges.Verdict.MATCHES);
        check("a Maven range excludes its open upper bound",
                VersionRanges.maven("[1.20.1,1.21)", "1.21") == VersionRanges.Verdict.DOES_NOT_MATCH);
        check("a Maven range includes a closed upper bound",
                VersionRanges.maven("[1.20,1.20.1]", on) == VersionRanges.Verdict.MATCHES);
        check("a Maven range excludes an open lower bound",
                VersionRanges.maven("(1.20.1,1.21)", on) == VersionRanges.Verdict.DOES_NOT_MATCH);
        check("an unbounded Maven range admits everything above it",
                VersionRanges.maven("[1.20,)", "1.21") == VersionRanges.Verdict.MATCHES);
        check("a bare Maven version means that or newer",
                VersionRanges.maven("1.20.1", "1.19.2") == VersionRanges.Verdict.DOES_NOT_MATCH);
        check("an unreadable Maven range is not judged",
                VersionRanges.maven("[not,a,range]", on) == VersionRanges.Verdict.UNKNOWN);
    }

    private static boolean refuses(String range, String version) {
        return VersionRanges.fabric(List.of(range), version) == VersionRanges.Verdict.DOES_NOT_MATCH;
    }

    private static boolean admits(String range, String version) {
        return VersionRanges.fabric(List.of(range), version) == VersionRanges.Verdict.MATCHES;
    }

    // ---------------------------------------------------------------- version mismatch

    /**
     * The whole path, from a jar in a folder to a launch that is stopped.
     *
     * <p>The case this exists for: an instance is set up on one Minecraft
     * version, mods are installed into it, and the version is then changed. The
     * jars stay - they are in the player's folder, and nothing in the launcher
     * deletes what it was not asked to - so the folder now holds mods for a
     * version this instance is no longer on. This is the check that the launcher
     * notices before the loader does.
     */
    private static void modVersionMismatch() {
        section("Mods against the instance version");

        Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("hexadron-mismatch-check");

            writeJar(dir.resolve("sodium-fabric-0.9.1+mc26.2.jar"), Map.of("fabric.mod.json", """
                    {"id":"sodium","version":"0.9.1+mc26.2","name":"Sodium",
                     "depends":{"minecraft":"~26.2","fabricloader":">=0.15"}}"""));
            writeJar(dir.resolve("create-fabric-6.0.8.jar"), Map.of("fabric.mod.json", """
                    {"id":"create","version":"6.0.8","name":"Create",
                     "depends":{"minecraft":">=1.20.1 <1.21"}}"""));
            writeJar(dir.resolve("mysterymod-1.0.jar"), Map.of("fabric.mod.json",
                    "{\"id\":\"mystery\",\"version\":\"1.0\",\"name\":\"Mystery\"}"));
            writeJar(dir.resolve("switched-off-2.0.jar.disabled"), Map.of("fabric.mod.json", """
                    {"id":"switched","version":"2.0","name":"Switched Off",
                     "depends":{"minecraft":"~26.2"}}"""));
            writeJar(dir.resolve("forgey-1.0.jar"), Map.of("META-INF/mods.toml", String.join("\n",
                    "modLoader=\"javafml\"",
                    "[[mods]]",
                    "modId=\"forgey\"",
                    "version=\"1.0\"",
                    "displayName=\"Forgey\"",
                    "[[dependencies.forgey]]",
                    "modId=\"forge\"",
                    "versionRange=\"[47,)\"",
                    "[[dependencies.forgey]]",
                    "modId=\"minecraft\"",
                    "versionRange=\"[1.20.1,1.21)\"")));

            List<ModEntry> onOldVersion = ModScan.scan(dir, "1.20.1");
            check("a mod for another version is spotted",
                    byTitle(onOldVersion, "Sodium").isWrongVersion());
            check("its requirement is kept for the message",
                    "~26.2".equals(byTitle(onOldVersion, "Sodium").requires()));
            check("a mod for this version is left alone",
                    !byTitle(onOldVersion, "Create").isWrongVersion());
            check("a mod that declares nothing is not accused",
                    !byTitle(onOldVersion, "Mystery").isWrongVersion());
            check("a Forge range is read too",
                    !byTitle(onOldVersion, "Forgey").isWrongVersion());
            // A switched-off mod is not going to be loaded either way, so
            // reporting it as a problem would be reporting the fix.
            check("a switched-off mod is not a problem",
                    !byTitle(onOldVersion, "Switched Off").isWrongVersion());
            check("only the offending mod is listed",
                    ModScan.wrongVersion(onOldVersion).size() == 1);

            List<ModEntry> onItsOwnVersion = ModScan.scan(dir, "26.2");
            check("on its own version the same mod is fine",
                    !byTitle(onItsOwnVersion, "Sodium").isWrongVersion());
            check("and the ones for the old version are now the problem",
                    byTitle(onItsOwnVersion, "Create").isWrongVersion());
            check("a Forge range is judged on both sides",
                    byTitle(onItsOwnVersion, "Forgey").isWrongVersion());

            check("without a version nothing is judged at all",
                    ModScan.wrongVersion(ModScan.scan(dir)).isEmpty());
            check("nor on a version that cannot be ordered",
                    ModScan.wrongVersion(ModScan.scan(dir, "23w31a")).isEmpty());

        } catch (IOException e) {
            check("the mods folder could be judged: " + e, false);
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------------------------------------------------------------- webp

    /**
     * The lossless WebP decoder.
     *
     * <p>The pictures below are real WebP files, kept as text so the check needs
     * no network and no test resources. Each one was produced by libwebp and its
     * expected pixels read back with libwebp, so a pass here means this decoder
     * agrees with the reference implementation exactly rather than merely
     * producing something that looks like a picture. Between them they cover
     * every transform the format has, all fourteen predictors, the colour cache
     * and the back-references.
     *
     * <p>The last one is lossy, and the check on it is that nothing is returned:
     * that half of the format is a video codec and is deliberately absent, so it
     * has to fail in the way the caller can handle rather than in some other
     * way.
     */
    private static void webpDecoding() {
        section("WebP");

        // 16x16, four colours - the colour indexing transform, two bits a pixel.
        byte[] palette = java.util.Base64.getDecoder().decode(
                "UklGRkgAAABXRUJQVlA4TDsAAAAvD8ADEB8gECBY8f9oQyBAkOA/lkAgCWp/tQUkhOeyXIwdeJUg"
                + "zDZ6HefUxnkAY4jofwn2KvWrWPcqAgA=");
        // 24x12 - subtract green, spatial prediction and the cross colour
        // transform, on an image whose channels all move independently.
        byte[] gradient = java.util.Base64.getDecoder().decode(
                "UklGRkIAAABXRUJQVlA4TDYAAAAvF8ACEAGBbLLn751CRP8zv4uI/odBcSQZ0CIF+cd7e9tREAsm"
                + "8ydLqW/4JwBijXYjOcRH3wk=");
        // One pixel: the smallest picture the format can express, and the case
        // where a row has no pixel to its left and none above it.
        byte[] single = java.util.Base64.getDecoder().decode(
                "UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAEAdQ5Db0oGOBiOh/AAA=");
        // The lossy kind, which this decoder does not read.
        byte[] lossy = java.util.Base64.getDecoder().decode(
                "UklGRi4AAABXRUJQVlA4ICIAAABwAQCdASoIAAgAAUAmJZQCdAFAAAD+/DeBV/fU6D4r4AAA");

        check("a WebP file is recognised", Webp.isWebp(palette));
        check("a PNG is not a WebP", !Webp.isWebp(new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'}));
        check("a short file is not a WebP", !Webp.isWebp(new byte[]{'R', 'I', 'F', 'F'}));
        check("null is not a WebP", !Webp.isWebp(null));

        Webp.Bitmap indexed = Webp.decode(palette).orElseThrow();
        check("a palette image has the right size",
                indexed.width() == 16 && indexed.height() == 16);
        check("a palette image decodes exactly as libwebp does",
                java.util.Arrays.hashCode(indexed.argb()) == 1508739073);
        check("the first palette pixel is right", indexed.argb()[0] == 0xffff0000);
        check("the end of the first row is right", indexed.argb()[15] == 0xff112233);
        check("the start of the last row is right", indexed.argb()[240] == 0xff00ff00);
        check("partial transparency survives a palette",
                java.util.Arrays.stream(indexed.argb()).anyMatch(p -> (p >>> 24) == 0x80));

        Webp.Bitmap predicted = Webp.decode(gradient).orElseThrow();
        check("a predicted image has the right size",
                predicted.width() == 24 && predicted.height() == 12);
        check("a predicted image decodes exactly as libwebp does",
                java.util.Arrays.hashCode(predicted.argb()) == -1181448447);
        check("the first predicted pixel is right", predicted.argb()[0] == 0xff0000c8);
        check("the last predicted pixel is right",
                predicted.argb()[predicted.argb().length - 1] == 0xa3e6217b);

        Webp.Bitmap one = Webp.decode(single).orElseThrow();
        check("a one pixel image has the right size", one.width() == 1 && one.height() == 1);
        check("a one pixel image decodes exactly as libwebp does",
                one.argb()[0] == 0x630dc807);

        check("a lossy WebP is refused rather than guessed at", Webp.decode(lossy).isEmpty());
        check("empty bytes decode to nothing", Webp.decode(new byte[0]).isEmpty());
        check("a truncated WebP decodes to nothing",
                Webp.decode(java.util.Arrays.copyOf(palette, 40)).isEmpty());

        // The header carries the size, so a file claiming to be enormous is a
        // file asking the decoder to allocate on its say-so.
        byte[] huge = palette.clone();
        java.util.Arrays.fill(huge, 21, 25, (byte) 0xff);
        check("an impossible size is refused rather than allocated",
                Webp.decode(huge).isEmpty());

        // Every byte of a real file, one at a time. Nothing here may throw at
        // the caller: these bytes come off the internet, and the one thing a
        // list being drawn cannot survive is an exception out of a logo.
        int survived = 0;
        for (int i = 0; i < gradient.length; i++) {
            byte[] damaged = gradient.clone();
            damaged[i] = (byte) ~damaged[i];
            try {
                Webp.decode(damaged);
                survived++;
            } catch (RuntimeException e) {
                // Counted by not counting.
            }
        }
        check("no damaged file throws", survived == gradient.length);
    }

    // ---------------------------------------------------------------- jar descriptors

    /** A progress sink for the checks: they assert on results, not on narration. */
    private static final Progress QUIET = new Progress() {
        @Override
        public void stage(String name) {
        }

        @Override
        public void bytes(long completed, long total) {
        }

        @Override
        public void items(int completed, int total) {
        }

        @Override
        public void log(String message) {
        }
    };

    /**
     * Reading a mod jar's own description of itself.
     *
     * <p>This is what turns a jar a player dropped into the folder into a row
     * they can read. Every loader's descriptor format is covered, because "the
     * launcher shows my Forge mods but not my Fabric ones" is the shape this
     * fails in.
     */
    private static void jarDescriptors() {
        section("Jar descriptors");

        Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("hexadron-jars-check");

            Path fabric = dir.resolve("some-mod-1.2.3.jar");
            writeJar(fabric, Map.of("fabric.mod.json", """
                    {"schemaVersion":1,"id":"sodium","version":"0.6.13",
                     "name":"Sodium","description":"A  modern\\n rendering engine.",
                     "authors":["jellysquid3",{"name":"IMS"}],
                     "contact":{"issues":"https://example.invalid/issues",
                                "homepage":"https://modrinth.com/mod/sodium"},
                     "icon":{"32":"assets/small.png","256":"assets/big.png"}}""",
                    "assets/big.png", "not really a png"));

            LocalModInfo info = LocalModInfo.read(fabric).orElseThrow();
            check("a Fabric name is read", "Sodium".equals(info.name()));
            check("a Fabric version is read", "0.6.13".equals(info.version()));
            check("a description is collapsed to one line",
                    "A modern rendering engine.".equals(info.description()));
            check("both author shapes are read",
                    info.authors().equals(List.of("jellysquid3", "IMS")));
            check("the homepage wins over the issue tracker",
                    "https://modrinth.com/mod/sodium".equals(info.homepage()));
            check("the largest icon is chosen", "assets/big.png".equals(info.iconPath()));
            check("the icon is readable", LocalModInfo.readIcon(fabric, info.iconPath())
                    .map(bytes -> bytes.length > 0).orElse(false));
            check("a missing icon path is not an error",
                    LocalModInfo.readIcon(fabric, "assets/nothing.png").isEmpty());

            // The one link that must never be opened. A descriptor is a file the
            // launcher did not write, and this string ends up behind a button
            // that hands it to the operating system.
            Path hostile = dir.resolve("hostile-1.0.jar");
            writeJar(hostile, Map.of("fabric.mod.json", """
                    {"id":"hostile","version":"1","name":"Hostile",
                     "contact":{"homepage":"file:///etc/passwd"}}"""));
            check("a non-web link is refused",
                    LocalModInfo.read(hostile).orElseThrow().homepage() == null);

            Path forge = dir.resolve("forge-mod-2.0.jar");
            writeJar(forge, Map.of("META-INF/mods.toml", String.join("\n",
                    "modLoader=\"javafml\" # a comment",
                    "loaderVersion=\"[47,)\"",
                    "issueTrackerURL=\"https://example.invalid/bugs\"",
                    "[[mods]]",
                    "modId=\"jei\"",
                    "version=\"${file.jarVersion}\"",
                    "displayName=\"Just Enough Items\"",
                    "authors=\"mezz\"",
                    "logoFile=\"jei_logo.png\"",
                    "description='''",
                    "JEI shows recipes.",
                    "'''",
                    "[[dependencies.jei]]",
                    "modId=\"forge\""),
                    "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\nImplementation-Version: 15.2.0.27\n\n"));

            LocalModInfo forgeInfo = LocalModInfo.read(forge).orElseThrow();
            check("a Forge name is read", "Just Enough Items".equals(forgeInfo.name()));
            check("a jarVersion placeholder is filled from the manifest",
                    "15.2.0.27".equals(forgeInfo.version()));
            check("a block description is read",
                    "JEI shows recipes.".equals(forgeInfo.description()));
            check("a Forge author list is split", forgeInfo.authors().equals(List.of("mezz")));
            check("the issue tracker is used when there is no site",
                    "https://example.invalid/bugs".equals(forgeInfo.homepage()));
            check("the logo inside the jar is found",
                    "jei_logo.png".equals(forgeInfo.iconPath()));
            check("a dependency table is not read as the mod",
                    "jei".equals(forgeInfo.modId()));

            Path quilt = dir.resolve("quilted-3.jar");
            writeJar(quilt, Map.of("quilt.mod.json", """
                    {"quilt_loader":{"id":"qsl","version":"7.0.0","metadata":{
                       "name":"Quilt Standard Libraries","description":"Libraries.",
                       "contributors":{"Quilt Team":"Owner"},
                       "contact":{"homepage":"https://quiltmc.org"}}}}"""));
            LocalModInfo quiltInfo = LocalModInfo.read(quilt).orElseThrow();
            check("a Quilt name is read",
                    "Quilt Standard Libraries".equals(quiltInfo.name()));
            check("a Quilt version is read from the loader block",
                    "7.0.0".equals(quiltInfo.version()));
            check("Quilt contributors are read from an object",
                    quiltInfo.authors().equals(List.of("Quilt Team")));

            Path legacy = dir.resolve("oldmod-1.7.10.jar");
            writeJar(legacy, Map.of("mcmod.info", """
                    [{"modid":"oldmod","name":"Old Mod","version":"1.4",
                      "description":"From before.","authorList":["Somebody"],
                      "url":"https://example.invalid/old"}]"""));
            LocalModInfo legacyInfo = LocalModInfo.read(legacy).orElseThrow();
            check("an mcmod.info name is read", "Old Mod".equals(legacyInfo.name()));
            check("an mcmod.info author list is read",
                    legacyInfo.authors().equals(List.of("Somebody")));

            Path bare = dir.resolve("library-1.0.jar");
            writeJar(bare, Map.of("META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\nImplementation-Title: Some Library\n"
                            + "Implementation-Version: 4.5\n\nName: other/\n"
                            + "Implementation-Title: Not This\n"));
            LocalModInfo bareInfo = LocalModInfo.read(bare).orElseThrow();
            check("a manifest is the last resort", "Some Library".equals(bareInfo.name()));
            check("only the manifest's main section is read",
                    !"Not This".equals(bareInfo.name()));

            Path junk = dir.resolve("not-a-jar.jar");
            java.nio.file.Files.writeString(junk, "this is not a zip");
            check("a file that is not an archive is not an error",
                    LocalModInfo.read(junk).isEmpty());

            check("a name falls back to the file name",
                    "Some Mod".equals(new LocalModInfo(null, null, null, null,
                            List.of(), null, null, null, List.of(), List.of())
                            .displayName("some-mod-1.2.3.jar")));

        } catch (IOException e) {
            check("jar descriptors could be read: " + e, false);
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------------------------------------------------------------- folder scan

    /**
     * The mods folder as the user sees it: what the launcher installed and what
     * the player put there, in one list.
     *
     * <p>The rule that must not break runs in both directions - a file the
     * launcher recorded is never listed as the player's, and a file the launcher
     * did not record is never treated as the launcher's to delete.
     */
    private static void modsFolderScan() {
        section("Mods folder scan");

        Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("hexadron-scan-check");

            writeJar(dir.resolve("sodium.jar"), Map.of("fabric.mod.json",
                    "{\"id\":\"sodium\",\"version\":\"0.6.13\",\"name\":\"Sodium\"}"));
            writeJar(dir.resolve("mine-1.0.jar"), Map.of("fabric.mod.json",
                    "{\"id\":\"mine\",\"version\":\"1.0\",\"name\":\"My Own Mod\"}"));
            writeJar(dir.resolve("switched-2.0.jar.disabled"), Map.of("fabric.mod.json",
                    "{\"id\":\"switched\",\"version\":\"2.0\",\"name\":\"Switched Off\"}"));
            java.nio.file.Files.writeString(dir.resolve("notes.txt"), "not a mod");

            ModLibrary library = ModLibrary.read(dir);
            library.put(new InstalledMod("Sodium",
                    new ModFile("AANobbMI", "sodium", "v1", "0.6.13", "sodium.jar",
                            "https://example.invalid/sodium.jar", null, 1, List.of(),
                            ModProvider.Source.MODRINTH),
                    ModOrigin.PACK, "hexadron-optimise",
                    "https://example.invalid/icon.png", "https://modrinth.com/mod/sodium"));
            library.write();

            check("the logo and page survive a save",
                    ModLibrary.read(dir).get("MODRINTH:AANobbMI").orElseThrow()
                            .pageUrl() != null);

            List<ModEntry> mods = ModScan.scan(dir);
            check("every jar is listed", mods.size() == 3);
            check("a text file is not a mod",
                    mods.stream().noneMatch(mod -> mod.fileName().endsWith(".txt")));
            check("the lock file is not a mod",
                    mods.stream().noneMatch(mod -> mod.fileName().startsWith(".")));

            ModEntry sodium = byTitle(mods, "Sodium");
            check("a recorded mod keeps its origin", sodium.origin() == ModOrigin.PACK);
            check("a recorded mod is managed", sodium.isManaged());
            check("a pack mod is still not removable alone", !sodium.isRemovable());
            check("the stored logo reaches the row", sodium.iconUrl() != null);
            check("the stored page reaches the row", sodium.hasPage());
            check("the jar is read for a managed mod too", "0.6.13".equals(sodium.version()));

            ModEntry mine = byTitle(mods, "My Own Mod");
            check("a jar nobody recorded is the user's", mine.origin() == ModOrigin.EXTERNAL);
            check("the user's own mod is not managed", !mine.isManaged());
            check("the user's own mod can be removed", mine.isRemovable());
            check("the user's own mod is described by its jar", "1.0".equals(mine.version()));
            check("a file key cannot collide with a lock key",
                    mine.key().startsWith(ModEntry.FILE_KEY_PREFIX));

            ModEntry off = byTitle(mods, "Switched Off");
            check("a .disabled jar is listed", off != null);
            check("a .disabled jar is not enabled", off != null && !off.enabled());
            check("switched-off mods sort last", mods.get(mods.size() - 1).equals(off));
            check("the jar name drops the suffix", "switched-2.0.jar".equals(off.jarName()));

            // Switching a managed mod off must not turn it into a stranger, or
            // the next pack removal would leave the jar behind for ever.
            ModScan.setEnabled(dir, sodium, false);
            List<ModEntry> afterDisable = ModScan.scan(dir);
            ModEntry disabledSodium = byTitle(afterDisable, "Sodium");
            check("a switched-off managed mod is still managed", disabledSodium.isManaged());
            check("a switched-off managed mod is still pack-owned",
                    disabledSodium.origin() == ModOrigin.PACK);
            check("a switched-off managed mod is not counted as loaded",
                    !disabledSodium.enabled());
            check("the folder still holds three jars", afterDisable.size() == 3);

            ModScan.setEnabled(dir, disabledSodium, true);
            check("switching back on restores the name",
                    java.nio.file.Files.isRegularFile(dir.resolve("sodium.jar")));

            ModEntry stranger = byTitle(ModScan.scan(dir), "My Own Mod");
            ModScan.discard(dir, stranger, QUIET);
            check("a discarded file leaves the mods folder",
                    !java.nio.file.Files.isRegularFile(dir.resolve("mine-1.0.jar")));
            check("the row goes with it", ModScan.scan(dir).size() == 2);

            check("a missing folder scans to nothing",
                    ModScan.scan(dir.resolve("nowhere")).isEmpty());
            check("a disabled name round-trips",
                    "a.jar".equals(ModScan.enabledName("a.jar" + ModScan.DISABLED_SUFFIX)));
            check("a jar is recognised through the suffix",
                    ModScan.isJar("a.jar" + ModScan.DISABLED_SUFFIX));
            check("a text file is not a jar", !ModScan.isJar("notes.txt"));

            check("a Modrinth page is built from the slug",
                    "https://modrinth.com/mod/sodium".equals(ModrinthProvider.pageUrl("sodium")));
            check("no slug means no page", ModrinthProvider.pageUrl("") == null);

        } catch (IOException e) {
            check("the mods folder could be scanned: " + e, false);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static ModEntry byTitle(List<ModEntry> mods, String title) {
        return mods.stream().filter(mod -> title.equals(mod.title())).findFirst().orElse(null);
    }

    /** Builds a jar with the given entries, so the readers can be checked offline. */
    private static void writeJar(Path path, Map<String, String> entries) throws IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                java.nio.file.Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static ModProvider.SearchResult hit(String id) {
        return new ModProvider.SearchResult(id, id, id, "", "", 0, null,
                ModrinthProvider.pageUrl(id), List.of(), ModProvider.Source.MODRINTH);
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
    /**
     * Telling "the host never answered" apart from "the request went wrong".
     *
     * <p>This is the distinction the whole offline path turns on, and it was
     * wrong for the commonest case on a filtered network. A firewall that drops
     * packets produces a connect timeout, not a refusal - so the launcher
     * retried it four times, waited eighty seconds, and then reported "HTTP
     * connect timed out": no host, no cause, nothing to act on.
     */
    private static void unreachableHosts() {
        section("Unreachable hosts");

        check("a name that does not resolve is unreachable",
                Http.isUnreachable(new java.net.UnknownHostException("piston-meta.mojang.com")));
        check("a refused connection is unreachable",
                Http.isUnreachable(new java.net.ConnectException("Connection refused")));
        check("no route is unreachable",
                Http.isUnreachable(new java.net.NoRouteToHostException()));

        // The one that was missing. A dropped connection - a firewall, a
        // filtering antivirus, a blocked host - looks like this and nothing else.
        check("a connection that timed out is unreachable",
                Http.isUnreachable(new java.net.http.HttpConnectTimeoutException("HTTP connect timed out")));
        check("and it stays unreachable when it is wrapped",
                Http.isUnreachable(new IOException("wrapped",
                        new java.net.http.HttpConnectTimeoutException("HTTP connect timed out"))));

        // Deliberately not: the host answered and then stalled. That is
        // transient, and giving up on it would turn a slow mirror into a failed
        // install.
        check("a response that timed out is not unreachable",
                !Http.isUnreachable(new java.net.http.HttpTimeoutException("request timed out")));
        check("a checksum mismatch is not unreachable",
                !Http.isUnreachable(new IOException("checksum mismatch")));
        check("an HTTP status is not unreachable",
                !Http.isUnreachable(new Http.HttpStatusException(503, "https://example.org", "")));

        // A circular cause chain must not spin. Java forbids an exception being
        // its own cause, but not two of them pointing at each other.
        IOException first = new IOException("first");
        IOException second = new IOException("second", first);
        first.initCause(second);
        check("a circular cause chain terminates", !Http.isUnreachable(first));
        check("and is still read as far as it goes",
                Http.isUnreachable(new IOException("outer",
                        new IOException("middle", new java.net.ConnectException("refused")))));
    }

    /**
     * The route the launcher takes, and what it refuses to become.
     *
     * <p>A proxy is the answer to a blocked network that keeps Mojang as the
     * source of the files. A mirror is not: Mojang's usage guidelines forbid
     * redistributing game files, and a mirror that also served the version
     * manifest would be supplying both the downloads and the hashes they are
     * checked against. What is asserted here is the small part that can be:
     * the settings survive a save, an unusable one is recognised as unusable,
     * and nothing about the route can put a password in the settings file.
     */
    private static void proxyRouting() {
        section("Proxy routing");

        check("the default route is whatever this computer is set up for",
                ProxyChoice.system().mode() == ProxyChoice.Mode.SYSTEM);
        check("an unknown stored value falls back to that",
                ProxyChoice.Mode.parse("nonsense") == ProxyChoice.Mode.SYSTEM);
        check("and a missing one does too", ProxyChoice.Mode.parse(null) == ProxyChoice.Mode.SYSTEM);
        check("direct survives a save and a load",
                ProxyChoice.Mode.parse(ProxyChoice.Mode.DIRECT.stored()) == ProxyChoice.Mode.DIRECT);
        check("so does manual",
                ProxyChoice.Mode.parse(ProxyChoice.Mode.MANUAL.stored()) == ProxyChoice.Mode.MANUAL);

        ProxyChoice manual = ProxyChoice.system()
                .withMode(ProxyChoice.Mode.MANUAL)
                .withHost("127.0.0.1")
                .withPort(8080);
        check("a manual proxy with an address is usable", manual.isUsable());
        check("without an address it is not", !manual.withHost("  ").isUsable());
        check("nor with a port outside the range", !manual.withPort(70000).isUsable());
        check("the other modes need no address",
                ProxyChoice.system().isUsable()
                        && ProxyChoice.system().withMode(ProxyChoice.Mode.DIRECT).isUsable());

        check("no user means nothing to authenticate with", !manual.wantsAuthentication());
        check("a user means there is", manual.withUser("someone").wantsAuthentication());
        // Only for a proxy this launcher was told to use. A system route is
        // whatever the machine already does, and answering a challenge from it
        // with a password typed here would send it somewhere unasked.
        check("a system route never authenticates",
                !ProxyChoice.system().withUser("someone").wantsAuthentication());

        java.nio.file.Path work = null;
        try {
            work = java.nio.file.Files.createTempDirectory("hexadron-proxy-check");
            GameDirs dirs = new GameDirs(work);
            dirs.createBaseDirectories();

            com.hexadron.launcher.core.LauncherSettings settings =
                    new com.hexadron.launcher.core.LauncherSettings(dirs);
            settings.proxy(manual.withUser("someone"));
            settings.save();

            String written = java.nio.file.Files.readString(dirs.settingsFile());
            check("the route is written to the settings file", written.contains("\"proxy\""));
            // The one thing that must never be in this file. It is synced,
            // backed up and pasted into bug reports.
            check("and the password is not, because it is never held there",
                    !written.toLowerCase(java.util.Locale.ROOT).contains("password"));

            com.hexadron.launcher.core.LauncherSettings reread =
                    new com.hexadron.launcher.core.LauncherSettings(dirs).load();
            check("and it reads back as it was written",
                    reread.proxy().mode() == ProxyChoice.Mode.MANUAL
                            && reread.proxy().host().equals("127.0.0.1")
                            && reread.proxy().port() == 8080
                            && reread.proxy().user().equals("someone"));
        } catch (IOException e) {
            check("the route is written to the settings file", false);
        } finally {
            if (work != null) {
                deleteRecursively(work);
            }
        }
    }

    /**
     * A compatibility mod that must not always be installed.
     *
     * <p>Indium exists to give Sodium the Fabric Rendering API. Sodium 0.6 has
     * that built in and is <em>incompatible</em> with Indium, so the same set of
     * mods needs it on one Minecraft version and is broken by it on another.
     * Every version string below is a real Modrinth {@code version_number} or
     * file name, because the parsing is the whole of the decision.
     */
    private static void modCompatibility() {
        section("Mod compatibility conditions");

        // --- reading a mod's own version out of what Modrinth publishes -----
        // The Minecraft version comes first in these strings, so the naive
        // "first number in the string" answers 1.20.1 and every comparison
        // after it is meaningless.
        check("a Modrinth version number gives the mod's version, not Minecraft's",
                "0.5.13".equals(ModVersions.of("mc1.20.1-0.5.13-fabric", null)));
        check("and does when the mod version is higher than the game's",
                "0.8.13".equals(ModVersions.of("mc1.21.1-0.8.13-fabric", null)));
        check("a beta tag does not become the version",
                "0.8.13".equals(ModVersions.of("mc1.21.1-0.8.13-beta.2-fabric", null)));
        check("a plain version number is itself",
                "0.5.11".equals(ModVersions.of("mc1.20.1-0.5.11", null)));
        check("the jar name answers when the published name does not",
                "0.6.13".equals(ModVersions.of(null, "sodium-fabric-0.6.13+mc1.21.1.jar")));
        check("and when the game version comes first in the jar name",
                "0.5.13".equals(ModVersions.of("", "sodium-fabric-mc1.20.1-0.5.13.jar")));
        check("a string with no version in it reads as nothing, not as a guess",
                ModVersions.of("latest", "sodium.jar") == null);

        // --- comparing --------------------------------------------------
        check("0.5.13 is below 0.6.0", ModVersions.isBelow("0.5.13", "0.6.0"));
        check("0.6.0 is not below itself", !ModVersions.isBelow("0.6.0", "0.6.0"));
        check("0.8.13 is not below 0.6.0", !ModVersions.isBelow("0.8.13", "0.6.0"));
        // The range these comparisons actually live in, and the one string
        // comparison gets backwards.
        check("0.10.0 is above 0.9.9", !ModVersions.isBelow("0.10.0", "0.9.9"));
        check("0.9.9 is below 0.10.0", ModVersions.isBelow("0.9.9", "0.10.0"));
        check("a shorter version is padded, not truncated",
                ModVersions.isBelow("0.6", "0.6.1") && !ModVersions.isBelow("0.6.0", "0.6"));
        check("an unknown version is never below anything",
                !ModVersions.isBelow(null, "0.6.0"));

        // --- the pack as it ships -------------------------------------------
        try {
            ModPack pack = ModPack.hexadronOptimise();

            ModPack.Entry indium = null;
            ModPack.Entry sodium = null;
            for (ModPack.Entry entry : pack.entries()) {
                if (entry.label().equals("Indium")) {
                    indium = entry;
                }
                if (entry.label().equals("Sodium")) {
                    sodium = entry;
                }
            }

            check("the set contains Sodium", sodium != null);
            check("and Indium, which it did not before", indium != null);
            check("Indium is conditional rather than always installed",
                    indium != null && indium.isConditional());
            check("it is optional, so a version with no build for it is a note not a failure",
                    indium != null && indium.optional());
            check("the condition names Sodium",
                    indium != null && sodium != null
                            && indium.onlyWith().projectId().equals(sodium.projectId()));
            check("and the boundary is the release that made it unnecessary",
                    indium != null && indium.onlyWith().versionBelow().equals("0.6.0"));

            // What the condition decides, on the two builds that actually ship.
            check("so on Minecraft 1.20.1, where Sodium is 0.5.13, Indium goes in",
                    ModVersions.isBelow(
                            ModVersions.of("mc1.20.1-0.5.13-fabric", null),
                            indium.onlyWith().versionBelow()));
            check("and on 1.21.1, where Sodium is 0.8.13, it stays out",
                    !ModVersions.isBelow(
                            ModVersions.of("mc1.21.1-0.8.13-fabric", null),
                            indium.onlyWith().versionBelow()));

            // A conditional entry cannot make the set look uninstallable before
            // anything has been resolved.
            for (ModPack.Entry entry : pack.entries()) {
                if (entry.isConditional()) {
                    check("a conditional entry is optional by construction", entry.optional());
                }
            }
        } catch (IOException e) {
            check("the set contains Sodium", false);
        }
    }

    /**
     * The stylesheet, as far as it can be checked without a screen.
     *
     * <p>JavaFX does not fail on a stylesheet it cannot parse - it logs a
     * warning nobody reads and draws the control from modena instead, which on
     * a dark panel means light-grey text on light-grey. That is how the radio
     * buttons in the account window ended up unreadable: they had no rule at
     * all, and nothing said so. Braces and the classes the interface actually
     * asks for are cheap to check here and expensive to notice on screen.
     */
    private static void stylesheet() {
        section("Stylesheet");

        String css;
        try (java.io.InputStream in =
                     SelfCheck.class.getResourceAsStream("/ui/hexadron.css")) {
            if (in == null) {
                check("the stylesheet is on the classpath", false);
                return;
            }
            css = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            check("the stylesheet is on the classpath", false);
            return;
        }
        check("the stylesheet is on the classpath", true);

        int depth = 0;
        boolean balanced = true;
        for (int i = 0; i < css.length() && balanced; i++) {
            char c = css.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                balanced = depth >= 0;
            }
        }
        check("its braces balance", balanced && depth == 0);

        // Every class the code adds by name. A rule that is not here is a
        // control drawn by modena on a dark panel.
        for (String styleClass : new String[]{
                "root", "label", "muted", "button", "text-field", "radio-button",
                "check-box", "combo-box", "section-title", "form-label", "detail-title",
                "detail-icon", "skin-viewer", "viewer-button", "swatch", "swatch-add",
                "chooser-field", "chooser-hue", "chooser-thumb", "chooser-preview",
                "scroll-pane", "scroll-bar", "profile-scroll", "inv-scroll"}) {
            check("." + styleClass + " is styled", css.contains("." + styleClass));
        }

        // The scroller added to the account window is not the only one: the
        // profile list and the inventory view were drawing modena's light bar
        // down the side of a dark panel for want of these.
        check("scrollbar thumbs are styled, not left to modena",
                css.contains(".scroll-bar > .thumb"));
        check("and the viewport behind them is transparent",
                css.contains(".scroll-pane > .viewport"));

        // A tick box drawn by modena on a dark panel is a light box with a dark
        // mark in it, and one filled with the colour of the panel it sits on is
        // no box at all. Both halves have to be named.
        check("the tick box itself is drawn, not only its label",
                css.contains(".check-box > .box"));
        check("and the mark in it is painted, not left in modena's ink",
                css.contains(".check-box:selected > .box > .mark"));
        check("an empty box is not filled with a surface colour",
                !ruleOf(css, ".check-box > .box").contains("-fx-background-color: -fx-base-2")
                        && !ruleOf(css, ".check-box > .box")
                        .contains("-fx-background-color: -fx-base-1"));

        // The row a mark sits on is filled with -fx-base-3 when it is selected.
        // A mark filled with the same colour disappears the moment its mod is
        // clicked, which is the moment it is being read.
        check("a category mark is not filled with the selected-row colour",
                !ruleOf(css, ".mod-tag").contains("-fx-background-color: -fx-base-3"));
        check("and it is outlined, so it is a mark on any row",
                ruleOf(css, ".mod-tag").contains("-fx-border-width"));

        // The category panel is one menu item holding nineteen rows: without
        // this the menu's highlight covers all nineteen at once.
        check("the category panel turns off the menu's own highlight",
                css.contains(".category-item:focused"));
        check("a mod other mods need is marked in its own colour",
                css.contains(".badge-dependency") && css.contains("-fx-warning-0"));
        check("a badge keeps its edges on a selected row",
                ruleOf(css, ".badge").contains("-fx-border-width")
                        && !ruleOf(css, ".badge").contains("-fx-background-color: -fx-base-3"));
        check("the panel that lists them is styled, not left to modena",
                css.contains(".hover-panel") && css.contains(".hover-link"));
        check("the update window is styled too",
                css.contains(".update-pane") && css.contains(".update-notes")
                        && css.contains(".update-blocked"));
        check("and its rows highlight themselves",
                css.contains(".category-list > .check-box:hover"));
    }

    /** The body of the first rule with this selector, for asking what is in it. */
    private static String ruleOf(String css, String selector) {
        int at = css.indexOf(selector + " {");
        if (at < 0) {
            at = css.indexOf(selector + ",");
        }
        if (at < 0) {
            return "";
        }
        int open = css.indexOf('{', at);
        int close = css.indexOf('}', open);
        return open < 0 || close < 0 ? "" : css.substring(open, close);
    }

    /**
     * The launcher's own log.
     *
     * <p>Written for one purpose: to be attached to a bug report. That makes
     * two things load-bearing - that credentials never reach it, and that the
     * last line before a crash is on disk rather than in a buffer. Both are
     * checked here against a real file.
     */
    private static void launcherLog() {
        section("Launcher log");

        java.nio.file.Path work = null;
        try {
            work = java.nio.file.Files.createTempDirectory("hexadron-log-check");
            GameDirs dirs = new GameDirs(work);

            java.nio.file.Path file = LauncherLog.open(dirs);
            check("a log is opened in the logs folder",
                    file != null && file.startsWith(dirs.logs()));

            LauncherLog.info("hello");
            // Flushed per line, because the line worth reading is the one
            // written just before the process stopped existing.
            check("a line is on disk before anything is closed",
                    java.nio.file.Files.readString(file).contains("hello"));

            // The reason this file can be handed to somebody. Redactor is what
            // does it; this asserts that the log actually goes through it.
            String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27u";
            LauncherLog.info("Command: --accessToken " + jwt + " --uuid 1234");
            String written = java.nio.file.Files.readString(file);
            check("a session token does not reach the file", !written.contains(jwt));

            // The shape patterns describe what Microsoft and Xbox issue. A
            // third-party skin service issues a plain random string, which no
            // shape recognises - so the word in front of it has to be enough.
            String opaque = "b7f3a19c4e6d48a2b0c15d7e9f3a2b4c";
            LauncherLog.info("Command: --accessToken " + opaque + " --uuid 1234");
            LauncherLog.info("saved {\"accessToken\":\"" + opaque + "\",\"name\":\"Player\"}");
            written = java.nio.file.Files.readString(file);
            check("and neither does an opaque one, named on a command line or in JSON",
                    !written.contains(opaque));
            check("while what is around it survives",
                    written.contains("--uuid 1234") && written.contains("Player"));

            LauncherLog.error("boom", new IllegalStateException("the cause"));
            written = java.nio.file.Files.readString(file);
            check("an error carries its cause, not just its headline",
                    written.contains("IllegalStateException") && written.contains("the cause"));
            check("and the stack trace with it",
                    written.contains("com.hexadron.launcher.SelfCheck"));

            // Everything the panel is told goes to the file, so the two cannot
            // disagree about what happened.
            java.util.List<String> seen = new java.util.ArrayList<>();
            Progress tee = LauncherLog.tee(new Progress() {
                @Override
                public void stage(String name) {
                    seen.add("stage:" + name);
                }

                @Override
                public void bytes(long completed, long total) {
                }

                @Override
                public void items(int completed, int total) {
                }

                @Override
                public void log(String message) {
                    seen.add("log:" + message);
                }
            });
            tee.stage("Installing");
            tee.log("a line");
            written = java.nio.file.Files.readString(file);
            check("a teed progress still reaches whatever it wrapped",
                    seen.equals(java.util.List.of("stage:Installing", "log:a line")));
            check("and is written down as well",
                    written.contains("== Installing") && written.contains("a line"));

            LauncherLog.close();

            // The run before the one that went wrong is often the one that
            // explains it, so it is kept rather than overwritten.
            LauncherLog.open(dirs);
            LauncherLog.info("second run");
            LauncherLog.close();
            check("the previous run is kept beside the current one",
                    java.nio.file.Files.readString(dirs.logs().resolve("launcher-1.log"))
                            .contains("hello"));
            check("and the current one is the current one",
                    java.nio.file.Files.readString(dirs.logs().resolve("launcher.log"))
                            .contains("second run")
                            && !java.nio.file.Files.readString(dirs.logs().resolve("launcher.log"))
                            .contains("hello"));

        } catch (IOException e) {
            check("a log is opened in the logs folder", false);
        } finally {
            LauncherLog.close();
            deleteRecursively(work);
        }
    }

    /**
     * The about window's list of what this launcher stands on.
     *
     * <p>Two things are checked and they are different in kind. That the list
     * is complete and internally consistent, because attribution that is wrong
     * is worse than none. And that nothing in it can be turned into a way to
     * run something: the file is a resource on a disk the user can write to,
     * every entry becomes a link the browser is handed at a click, and a
     * {@code file:} entry in a credits screen would be a very quiet way in.
     */
    private static void about() {
        section("About window");

        Credits credits;
        try {
            credits = Credits.load();
        } catch (IOException e) {
            check("the credits list ships with the launcher", false);
            return;
        }
        check("the credits list ships with the launcher", true);

        check("it names the author", credits.author().name().equals("OLEKSII RADCHUK"));

        // The licence sentence in that window is the attribution the licence
        // itself points at, so it is checked rather than trusted. It said "CC0:
        // no rights reserved, no attribution required" for one release after the
        // terms had changed - a window telling users the opposite of the file
        // beside it, in five languages, and nothing said so.
        for (Language language : Language.all()) {
            String sentence = I18n.bundle(language).getOrDefault("about.licence", "");
            String code = language.code();
            check(code + ": the About window states the licence",
                    sentence.contains("LICENSE.md"));
            check(code + ": and does not claim the project is public domain",
                    !sentence.contains("CC0") && !sentence.toLowerCase(java.util.Locale.ROOT)
                            .contains("public domain"));
        }
        check("with somewhere to find them", credits.author().links().size() >= 3);
        check("and it points at the project's own repository",
                credits.repository() != null
                        && credits.repository().contains("github.com")
                        && credits.repository().endsWith("HexadronLauncher"));

        List<Credits.Entry> entries = credits.allEntries();
        check("there is something to credit", entries.size() >= 15);
        check("in groups", credits.groups().size() >= 4);

        // The one that must be there. Its jar is downloaded and run inside the
        // game, which is a dependency in the strongest sense there is, and its
        // licence is the reason the exception it grants matters.
        Credits.Entry agent = entries.stream()
                .filter(entry -> entry.name().equals("authlib-injector"))
                .findFirst().orElse(null);
        check("authlib-injector is credited, because its jar is run inside the game",
                agent != null);
        check("and the terms it is used under are named",
                agent != null && agent.licence() != null
                        && agent.licence().contains("AGPL"));

        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<String> urls = new java.util.HashSet<>();
        boolean uniqueNames = true;
        boolean uniqueUrls = true;
        boolean allHttps = true;
        boolean allNamed = true;
        for (Credits.Entry entry : entries) {
            uniqueNames &= names.add(entry.name());
            uniqueUrls &= urls.add(entry.url());
            allHttps &= entry.url().startsWith("https://");
            allNamed &= !entry.name().isBlank();
        }
        check("nothing is credited twice", uniqueNames && uniqueUrls);
        check("every entry has a name", allNamed);
        check("and every link is https", allHttps);

        for (Credits.Link link : credits.author().links()) {
            allHttps &= link.url().startsWith("https://");
        }
        check("including the author's own", allHttps);

        // The gate, not the call site. Anything that is not https is dropped
        // when the file is read, so nothing downstream can hold one.
        check("a file: link is refused", Credits.safe("file:///etc/passwd") == null);
        check("a javascript: link is refused",
                Credits.safe("javascript:alert(1)") == null);
        check("plain http is refused", Credits.safe("http://example.org") == null);
        check("an https link is kept",
                "https://example.org".equals(Credits.safe(" https://example.org ")));

        Credits planted = Credits.parse(com.hexadron.launcher.json.Json.parse("""
                {"author":{"name":"x","links":[{"name":"bad","url":"file:///x"}]},
                 "repository":"http://example.org",
                 "groups":[{"heading":"h","entries":[
                   {"name":"bad","url":"javascript:alert(1)"},
                   {"name":"good","url":"https://example.org"}]}]}
                """));
        check("an unsafe entry does not survive being read",
                planted.allEntries().size() == 1
                        && planted.allEntries().get(0).name().equals("good"));
        check("nor an unsafe author link", planted.author().links().isEmpty());
        check("nor an unsafe repository", planted.repository() == null);

        // Every heading is an i18n key, so a missing one shows as !about.x! in
        // the window rather than as nothing at all.
        for (Credits.Group group : credits.groups()) {
            check(group.heading() + " is a translated heading",
                    !I18n.t(group.heading()).startsWith("!"));
        }
    }

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
            java.nio.file.Files.write(wrong, png(100, 100));
            java.nio.file.Path large = work.resolve("large.png");
            java.nio.file.Files.write(large, png(128, 128));
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
            check("a picture whose size is not a skin sheet is refused", refused);

            // The layout is a map, and a sheet at twice the resolution is the
            // same map drawn finer. Refusing it would be refusing a better
            // version of an accepted file.
            check("a high-resolution skin is accepted", store.store(large, false) != null);

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

            // The overlay layers are drawn into the base texture rather than
            // as a second shell, and the code that does it walks the list
            // pairing each overlay with the part before it. If that order ever
            // stops holding, a sleeve gets composited onto a trouser leg.
            boolean paired = true;
            for (int i = 0; i < parts.size(); i++) {
                SkinLayout.Part part = parts.get(i);
                if (!part.overlay()) {
                    continue;
                }
                SkinLayout.Part under = i == 0 ? null : parts.get(i - 1);
                paired &= under != null && !under.overlay()
                        && under.width() == part.width()
                        && under.height() == part.height()
                        && under.depth() == part.depth()
                        && under.x() == part.x() && under.y() == part.y();
            }
            check(what + ": every overlay follows the part it covers, at the same size",
                    paired);

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

    /**
     * The sheets handed out to draw on.
     *
     * <p>Both are generated from the same rectangles the model is built from,
     * so the thing worth checking is not that they are drawn - it is that the
     * canvas is the size an editor expects, that the areas which have to be
     * opaque are, and that pressing the button twice does not write over an
     * evening's work.
     */
    /**
     * Signing in to a third-party skin service.
     *
     * <p>Nothing here touches the network. What is checked is the part that
     * goes wrong silently: an address that is a slash away from every endpoint
     * 404ing, a UUID in the form the game will not take, a saved sign-in that
     * belongs to a service other than the one now configured - each of which
     * ends as "I pasted the address and nothing happened".
     */
    private static void skinService() {
        section("Skin service sign-in");

        check("a trailing slash is trimmed off the address",
                YggdrasilAuth.normalise("https://littleskin.cn/api/yggdrasil/")
                        .equals("https://littleskin.cn/api/yggdrasil"));
        check("and so is more than one",
                YggdrasilAuth.normalise(" https://example.org/api//  ")
                        .equals("https://example.org/api"));

        check("an address without a scheme is refused",
                YggdrasilAuth.reasonToRefuse("littleskin.cn/api/yggdrasil") != null);
        // The password is in that request body. There is no version of sending
        // it in the clear that this launcher offers.
        check("so is a plain http one",
                YggdrasilAuth.reasonToRefuse("http://littleskin.cn/api/yggdrasil") != null);
        check("an empty address is refused",
                YggdrasilAuth.reasonToRefuse("   ") != null);
        check("an https address is accepted",
                YggdrasilAuth.reasonToRefuse("https://littleskin.cn/api/yggdrasil") == null);

        try {
            java.util.UUID dashed = YggdrasilAuth.undash("069a79f444e94726a5befca90e38aaf5");
            check("an undashed profile id becomes a UUID",
                    dashed.toString().equals("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
            check("an already-dashed one survives the trip",
                    YggdrasilAuth.undash("069a79f4-44e9-4726-a5be-fca90e38aaf5").equals(dashed));
        } catch (IOException e) {
            check("an undashed profile id becomes a UUID", false);
        }

        boolean refusedShortId = false;
        try {
            YggdrasilAuth.undash("nope");
        } catch (IOException expected) {
            refusedShortId = true;
        }
        check("an unreadable profile id is refused rather than guessed", refusedShortId);

        YggdrasilAuth.Session session = new YggdrasilAuth.Session(
                "https://littleskin.cn/api/yggdrasil", "client-token", "access-token",
                java.util.UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch");

        YggdrasilAuth.Session read = YggdrasilAuth.Session.fromJson(
                com.hexadron.launcher.json.Json.parse(session.toJson().toString()));
        check("a saved sign-in reads back as it was written",
                read != null && read.equals(session));
        check("a damaged one reads back as nothing at all",
                YggdrasilAuth.Session.fromJson(
                        com.hexadron.launcher.json.Json.parse("{\"root\":\"x\"}")) == null);

        // The address field can be edited after signing in, and a token issued
        // by one service means nothing at another.
        check("a sign-in knows the service it was issued by",
                session.isFor("https://littleskin.cn/api/yggdrasil/"));
        check("and knows when it is for a different one",
                !session.isFor("https://ely.by/api/yggdrasil"));

        check("a refusal is reported in the service's own words",
                YggdrasilAuth.describe(new com.hexadron.launcher.net.Http.HttpStatusException(
                        403, "https://example.org/authserver/authenticate",
                        "{\"error\":\"ForbiddenOperationException\","
                                + "\"errorMessage\":\"Invalid credentials.\"}"))
                        .equals("Invalid credentials."));
        check("an address that is not a service at all says so",
                YggdrasilAuth.describe(new com.hexadron.launcher.net.Http.HttpStatusException(
                        404, "https://example.org/authserver/authenticate", "<html>Not Found</html>"))
                        .contains("not a skin service"));

        // --- the launch identity ------------------------------------------
        Account offline = Account.offline("Player");
        Progress quiet = Progress.NOOP;

        check("a local profile is played as the account that was selected",
                SkinSession.identity(offline, SkinProfile.empty(), null, quiet) == offline);

        SkinProfile remote = SkinProfile.empty()
                .withSource(SkinProfile.Source.REMOTE)
                .withService("https://littleskin.cn/api/yggdrasil");
        check("a remote profile with no store behind it is too",
                SkinSession.identity(offline, remote, null, quiet) == offline);
        check("a remote profile with no sign-in saved is too",
                SkinSession.identity(offline, remote, new SkinCredentials(null), quiet) == offline);
        check("and a remote profile with no address is too",
                SkinSession.identity(offline, SkinProfile.empty()
                                .withSource(SkinProfile.Source.REMOTE),
                        new SkinCredentials(null), quiet) == offline);

        // A remote service is worth attaching with no local pictures at all;
        // a local one is not, because there would be nothing to serve.
        check("a remote profile asks for the service to be attached",
                remote.needsService());
        check("an empty local profile does not",
                !SkinProfile.empty().needsService());

        // --- one sign-in per service ---------------------------------------
        // Pointing the address at another service is switching to a different
        // account somewhere else, not signing out of the first.
        String littleskin = SkinCredentials.key("offline:x", "https://littleskin.cn/api/yggdrasil");
        String elyby = SkinCredentials.key("offline:x", "https://ely.by/api/authlib-injector");
        check("two services are two separate sign-ins", !littleskin.equals(elyby));
        check("and the same service is the same one, slash or no slash",
                littleskin.equals(
                        SkinCredentials.key("offline:x", "https://littleskin.cn/api/yggdrasil/")));
        check("two accounts at one service are separate too",
                !littleskin.equals(
                        SkinCredentials.key("offline:y", "https://littleskin.cn/api/yggdrasil")));

        // --- the stand-in figure -------------------------------------------
        try {
            byte[] bytes = DefaultSkin.png();
            int[] shape = PngSize.read(bytes);
            check("the stand-in skin is a 64 by 64 sheet",
                    shape != null && shape[0] == 64 && shape[1] == 64);

            java.awt.image.BufferedImage sheet = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(bytes));

            // A hole in a base layer renders as a see-through limb, which looks
            // like a broken texture rather than a plain figure.
            SkinLayout.Rect chest = named(SkinLayout.player(false), "body").faces().front();
            boolean opaque = true;
            for (int x = chest.u0(); x < chest.u1(); x++) {
                for (int y = chest.v0(); y < chest.v1(); y++) {
                    opaque &= (sheet.getRGB(x, y) >>> 24) == 0xFF;
                }
            }
            check("and every base area on it is opaque", opaque);

            SkinLayout.Rect face = named(SkinLayout.player(false), "head").faces().front();
            check("the head has a front that is not one flat colour",
                    sheet.getRGB(face.u0() + 2, face.v0() + 4)
                            != sheet.getRGB(face.u0() + 2, face.v0() + 7));
        } catch (IOException e) {
            check("the stand-in skin is a 64 by 64 sheet", false);
        }
    }

    /**
     * The size Minecraft will actually accept.
     *
     * <p>This is the check that was missing, and its absence cost a working
     * feature. The launcher accepted a 512x512 skin, stored it, served it,
     * signed it - and the client answered, in its own log and nowhere else:
     *
     * <pre>Discarding incorrectly sized (512x512) skin texture</pre>
     *
     * <p>{@code SkinTextureDownloader} takes 64x64 and the pre-1.8 64x32, and
     * nothing else. Every number below is that rule.
     */
    private static void skinSheets() {
        section("Skin sheet sizes");

        java.nio.file.Path work = null;
        try {
            work = java.nio.file.Files.createTempDirectory("hexadron-sheet-check");

            // --- what needs doing, and what does not ------------------------
            check("a 64x64 sheet is already what the game takes",
                    !SkinSheets.needsResizing(64, 64, false));
            check("so is the sheet from before 1.8",
                    !SkinSheets.needsResizing(64, 32, false));
            check("a 512x512 skin does not fit and has to be resized",
                    SkinSheets.needsResizing(512, 512, false));
            check("nor does 128x128", SkinSheets.needsResizing(128, 128, false));
            check("nor a high-resolution legacy sheet",
                    SkinSheets.needsResizing(256, 128, false));
            check("a cape from before 1.8 is reshaped",
                    SkinSheets.needsResizing(22, 17, true));
            check("a 64x32 cape is not", !SkinSheets.needsResizing(64, 32, true));
            check("a size that fits no rule is left alone",
                    !SkinSheets.needsResizing(100, 100, false));

            // --- a sheet that needs nothing comes back untouched -------------
            java.nio.file.Path plain = work.resolve("plain.png");
            java.awt.image.BufferedImage small = coloured(64, 64);
            javax.imageio.ImageIO.write(small, "png", plain.toFile());
            byte[] before = java.nio.file.Files.readAllBytes(plain);
            check("and is served byte for byte, not re-encoded",
                    java.util.Arrays.equals(before, SkinSheets.forGame(plain, false)));

            // --- the case from the bug report -------------------------------
            java.nio.file.Path huge = work.resolve("huge.png");
            javax.imageio.ImageIO.write(magnify(small, 8), "png", huge.toFile());
            byte[] served = SkinSheets.forGame(huge, false);
            int[] size = PngSize.read(served);
            check("a 512x512 skin is served to the game as 64x64",
                    size != null && size[0] == 64 && size[1] == 64);

            // The property that matters: a high-resolution skin is nearly
            // always a 64x64 one that was enlarged, so shrinking it again has
            // to give back exactly what it started as - not something close.
            java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(served));
            boolean identical = true;
            for (int x = 0; x < 64 && identical; x++) {
                for (int y = 0; y < 64 && identical; y++) {
                    identical = back.getRGB(x, y) == small.getRGB(x, y);
                }
            }
            check("and an enlarged 64x64 shrinks back to exactly itself", identical);

            // --- a legacy sheet keeps its shape ------------------------------
            java.nio.file.Path legacy = work.resolve("legacy.png");
            javax.imageio.ImageIO.write(magnify(coloured(64, 32), 4), "png", legacy.toFile());
            int[] legacySize = PngSize.read(SkinSheets.forGame(legacy, false));
            check("a 256x128 sheet becomes 64x32, not 64x64",
                    legacySize != null && legacySize[0] == 64 && legacySize[1] == 32);

            // --- the old cape shape -----------------------------------------
            java.nio.file.Path oldCape = work.resolve("cape.png");
            javax.imageio.ImageIO.write(coloured(22, 17), "png", oldCape.toFile());
            int[] capeSize = PngSize.read(SkinSheets.forGame(oldCape, true));
            check("a 22x17 cape is put back on a 64x32 sheet",
                    capeSize != null && capeSize[0] == 64 && capeSize[1] == 32);

            // --- transparency is not dragged towards black -------------------
            // Half of every block transparent, half solid red. Premultiplied,
            // the colour that survives is red; averaged raw it comes out dark.
            java.awt.image.BufferedImage patchy =
                    new java.awt.image.BufferedImage(128, 128,
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < 128; x++) {
                for (int y = 0; y < 128; y++) {
                    patchy.setRGB(x, y, x % 2 == 0 ? 0x00000000 : 0xFFFF0000);
                }
            }
            java.nio.file.Path fringe = work.resolve("fringe.png");
            javax.imageio.ImageIO.write(patchy, "png", fringe.toFile());
            java.awt.image.BufferedImage shrunk = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(SkinSheets.forGame(fringe, false)));
            int pixel = shrunk.getRGB(10, 10);
            check("a half-transparent block keeps its colour rather than going dark",
                    (pixel & 0x00FFFFFF) == 0x00FF0000);
            check("and keeps half its opacity", Math.abs(((pixel >>> 24) & 0xFF) - 128) <= 1);

        } catch (IOException e) {
            check("a 512x512 skin is served to the game as 64x64", false);
        } finally {
            deleteRecursively(work);
        }
    }

    /** A deterministic pattern, so a shrink can be compared against it. */
    private static java.awt.image.BufferedImage coloured(int width, int height) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, 0xFF000000 | (x * 4 % 256) << 16 | (y * 4 % 256) << 8 | 0x40);
            }
        }
        return image;
    }

    /** Nearest-neighbour enlargement - what a skin site's "HD" version is. */
    private static java.awt.image.BufferedImage magnify(
            java.awt.image.BufferedImage source, int factor) {
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                source.getWidth() * factor, source.getHeight() * factor,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < out.getWidth(); x++) {
            for (int y = 0; y < out.getHeight(); y++) {
                out.setRGB(x, y, source.getRGB(x / factor, y / factor));
            }
        }
        return out;
    }

    private static void skinTemplates() {
        section("Skin templates");

        java.nio.file.Path work = null;
        try {
            work = java.nio.file.Files.createTempDirectory("hexadron-template-check");

            List<java.nio.file.Path> skin = SkinTemplate.write(work, false, false,
                    (kind, name) -> kind + "." + name);
            check("a skin gives a canvas and a guide", skin.size() == 2);

            java.awt.image.BufferedImage canvas = javax.imageio.ImageIO.read(skin.get(0).toFile());
            check("the canvas is a 64 by 64 sheet",
                    canvas.getWidth() == 64 && canvas.getHeight() == 64);

            // The front of the body has to be opaque: a base layer with holes in
            // it renders as a see-through torso, and this is the file somebody's
            // first skin is drawn on top of.
            SkinLayout.Part body = named(SkinLayout.player(false), "body");
            SkinLayout.Rect front = body.faces().front();
            check("its base areas are filled in, and opaque",
                    (canvas.getRGB(front.u0() + 1, front.v0() + 1) >>> 24) == 0xFF);

            // The second layer is meant to be mostly nothing. Filling it would
            // put a solid box round the head of every skin drawn from this.
            SkinLayout.Rect hat = named(SkinLayout.player(false), "hat").faces().front();
            check("and its overlay areas are left clear",
                    (canvas.getRGB(hat.u0() + 1, hat.v0() + 1) >>> 24) == 0x00);

            check("nothing outside the layout is filled",
                    (canvas.getRGB(63, 0) >>> 24) == 0x00);

            java.awt.image.BufferedImage guide = javax.imageio.ImageIO.read(skin.get(1).toFile());
            check("the guide is the same sheet, enlarged",
                    guide.getWidth() == guide.getHeight()
                            && guide.getWidth() > canvas.getWidth() * 4);

            List<java.nio.file.Path> cape = SkinTemplate.write(work, true, false,
                    (kind, name) -> kind + "." + name);
            java.awt.image.BufferedImage capeCanvas =
                    javax.imageio.ImageIO.read(cape.get(0).toFile());
            check("a cape canvas is twice as wide as it is tall",
                    capeCanvas.getWidth() == 64 && capeCanvas.getHeight() == 32);

            List<java.nio.file.Path> again = SkinTemplate.write(work, false, false,
                    (kind, name) -> kind + "." + name);
            check("writing twice does not write over the first pair",
                    !again.get(0).equals(skin.get(0)) && !again.get(1).equals(skin.get(1)));
            check("and the first pair is still there",
                    java.nio.file.Files.isRegularFile(skin.get(0))
                            && java.nio.file.Files.isRegularFile(skin.get(1)));
        } catch (IOException e) {
            check("the skin template check can run: " + e.getMessage(), false);
        } finally {
            if (work != null) {
                deleteRecursively(work);
            }
        }
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
