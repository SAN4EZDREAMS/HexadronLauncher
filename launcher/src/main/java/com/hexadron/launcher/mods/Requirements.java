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

package com.hexadron.launcher.mods;

import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.json.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * The mods that other mods in a folder require and that are not there, found
 * before the game starts.
 *
 * <p>What a jar provides is more than its own id: a {@code mcmod.info} can
 * list several mods, a {@code mods.toml} several {@code [[mods]]}, a Fabric
 * mod names aliases in {@code provides}, and most of all a jar carries other
 * jars inside it ({@code META-INF/jars/} for Fabric and Quilt,
 * {@code META-INF/jarjar/} for Forge and NeoForge) - Fabric API is a hundred
 * modules, and a mod that needs one of them needs nothing else. What a mod
 * requires is its descriptor's required dependencies, and for a Forge 1.12
 * mod the requirements in its {@code @Mod} annotation.
 */
public final class Requirements {

    /**
     * A requirement nobody in the folder meets.
     *
     * @param mod         the mod that needs it
     * @param dependency  the mod id it needs, lower case
     * @param switchedOff a switched-off jar that would meet it, or null
     */
    public record Missing(ModEntry mod, String dependency, ModEntry switchedOff) {
    }

    /** Ids the game, Java or the loader provide; never a jar in the mods folder. */
    static final Set<String> BUILT_IN = Set.of("minecraft", "java", "fabricloader", "fabric-loader",
            "quilt_loader", "forge", "neoforge", "fml", "javafml", "lowcodefml", "mcp", "mixinextras", "*");

    /** Largest nested jar that is opened; a module is far smaller. */
    static final int MAX_NESTED_BYTES = 32 * 1024 * 1024;

    private static final Pattern TOML_MOD_ID = Pattern.compile("^\\s*modId\\s*=\\s*\"([^\"]{1,64})\"");
    private static final Map<String, Set<String>> PROVIDED = new ConcurrentHashMap<>();

    private Requirements() {
    }

    /**
     * The requirements of the switched-on mods that no switched-on jar meets.
     * Mods the loader will not load are left out: {@link LoaderCheck} speaks
     * for them, and their requirements are not this loader's.
     */
    public static List<Missing> missing(List<ModEntry> mods, LoaderType loader, String minecraftVersion) {
        List<Missing> missing = new ArrayList<>();
        if (loader == null || loader == LoaderType.VANILLA) {
            return missing;
        }
        List<ModEntry> wrongLoader = LoaderCheck.wrongLoader(mods, loader, minecraftVersion);
        Set<String> present = new LinkedHashSet<>();
        Map<String, ModEntry> off = new LinkedHashMap<>();
        for (ModEntry mod : mods) {
            Set<String> ids = provided(mod.path(), ModScan.descriptorOf(mod.path()).modId());
            if (mod.enabled()) {
                present.addAll(ids);
            } else if (mod.verdict() != VersionRanges.Verdict.DOES_NOT_MATCH) {
                // A switched-off copy for another Minecraft version is not
                // offered back: it was switched off for that, most likely, and
                // switching it on crashes the game the other way. The
                // requirement is then installed for this version instead.
                ids.forEach(id -> off.putIfAbsent(id, mod));
            }
        }
        Set<String> reported = new LinkedHashSet<>();
        for (ModEntry mod : mods) {
            // A mod for another Minecraft version will not load whatever it
            // is given, so what it needs is not worth installing: the warning
            // about the mod itself is the one that matters.
            if (!mod.enabled() || wrongLoader.contains(mod) || mod.isWrongVersion()) {
                continue;
            }
            for (String need : required(mod)) {
                // Another loader is not a mod to install: LoaderCheck speaks for those mods.
                if (BUILT_IN.contains(need) || LegacyDependencies.OTHER_LOADERS.contains(need)
                        || present.contains(need) || !reported.add(mod.fileName() + "|" + need)) {
                    continue;
                }
                missing.add(new Missing(mod, need, off.get(need)));
            }
        }
        return missing;
    }

    /** What a mod cannot start without, lower case. */
    public static List<String> required(ModEntry mod) {
        Set<String> needs = new LinkedHashSet<>();
        for (String id : ModScan.descriptorOf(mod.path()).depends()) {
            needs.add(id.trim().toLowerCase(Locale.ROOT));
        }
        if (LegacyDependencies.isLegacyForge(mod.path())) {
            needs.addAll(LegacyDependencies.of(mod.path()));
        }
        return List.copyOf(needs);
    }

    /** Every mod id a jar provides, its nested jars included; read once per version of the file. */
    public static Set<String> provided(Path jar, String mainId) {
        String key;
        try {
            key = jar + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException e) {
            return mainId == null ? Set.of() : Set.of(mainId.trim().toLowerCase(Locale.ROOT));
        }
        if (PROVIDED.size() > 4096) {
            PROVIDED.clear();
        }
        return PROVIDED.computeIfAbsent(key, ignored -> read(jar, mainId));
    }

    private static Set<String> read(Path jar, String mainId) {
        Set<String> ids = new LinkedHashSet<>(LegacyDependencies.provides(jar, mainId));
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            describe(entry(zip, "fabric.mod.json"), entry(zip, "quilt.mod.json"),
                    entry(zip, "META-INF/mods.toml"), entry(zip, "META-INF/neoforge.mods.toml"), ids);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if ((name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))
                        && name.endsWith(".jar") && entry.getSize() <= MAX_NESTED_BYTES) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        nested(in.readNBytes(MAX_NESTED_BYTES + 1), ids);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // A jar that does not read provides its own id at most.
        }
        return Set.copyOf(ids);
    }

    /** The ids of a jar inside a jar, one level down. */
    private static void nested(byte[] bytes, Set<String> ids) throws IOException {
        if (bytes.length > MAX_NESTED_BYTES) {
            return;
        }
        String fabric = null;
        String quilt = null;
        String toml = null;
        String neoToml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                switch (entry.getName()) {
                    case "fabric.mod.json" -> fabric = text(zip);
                    case "quilt.mod.json" -> quilt = text(zip);
                    case "META-INF/mods.toml" -> toml = text(zip);
                    case "META-INF/neoforge.mods.toml" -> neoToml = text(zip);
                    default -> {
                    }
                }
            }
        }
        describe(fabric, quilt, toml, neoToml, ids);
    }

    private static void describe(String fabric, String quilt, String toml, String neoToml, Set<String> ids) {
        if (fabric != null) {
            try {
                Json root = Json.parseLenient(fabric);
                add(ids, root.get("id").asString(null));
                for (Json alias : root.get("provides").elements()) {
                    add(ids, alias.asString(null));
                }
            } catch (RuntimeException e) {
                // A descriptor that does not parse provides nothing.
            }
        }
        if (quilt != null) {
            try {
                Json loader = Json.parseLenient(quilt).get("quilt_loader");
                add(ids, loader.get("id").asString(null));
                for (Json alias : loader.get("provides").elements()) {
                    add(ids, alias.isString() ? alias.asString(null) : alias.get("id").asString(null));
                }
            } catch (RuntimeException e) {
                // As above.
            }
        }
        for (String file : new String[]{toml, neoToml}) {
            if (file == null) {
                continue;
            }
            boolean inMods = false;
            for (String line : file.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    inMods = trimmed.equals("[[mods]]");
                    continue;
                }
                Matcher matcher = TOML_MOD_ID.matcher(line);
                if (inMods && matcher.find()) {
                    add(ids, matcher.group(1));
                }
            }
        }
    }

    private static void add(Set<String> ids, String id) {
        if (id != null && !id.isBlank()) {
            ids.add(id.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static String entry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > 1024 * 1024) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    private static String text(ZipInputStream zip) throws IOException {
        return new String(zip.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
    }
}
