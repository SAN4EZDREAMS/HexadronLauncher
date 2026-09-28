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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
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

/**
 * The mods a Forge 1.12 (and older) mod requires, read from its {@code @Mod}
 * annotation.
 *
 * <p>Those mods declare what they need in code, not in {@code mcmod.info}:
 * {@code @Mod(dependencies = "required-after:cofhcore@[4.6,);after:jei")}.
 * Forge refuses to start when one is missing, so a search that switches half
 * the mods off has to keep these together - and the descriptor names them for
 * three mods in eighty. The string is a constant in the class file, so it is
 * found by its text, with no bytecode parsing: an annotation value is stored
 * as one UTF-8 constant, and the byte after it is the tag of the next one,
 * which is never a printable character.
 *
 * <p>The start of the constant is not found the same way. Before its text
 * stand the tag {@code 1} and a two-byte length, and the low byte of that
 * length is printable for every string of 32 to 126 characters - which is
 * most dependency strings. Read as text it joins the string
 * ({@code 'required-after:baubles}), and the first requirement, usually the
 * only one, was lost. So the start is taken where the tag and the length
 * agree with the text that follows.
 */
public final class LegacyDependencies {

    /** Only classes up to this size are read; a mod class is far smaller. */
    static final int MAX_CLASS_BYTES = 512 * 1024;
    /** And at most this many classes of one jar. */
    static final int MAX_CLASSES = 20_000;

    private static final byte[][] MARKERS = {
            "required-after:".getBytes(StandardCharsets.US_ASCII),
            "required-before:".getBytes(StandardCharsets.US_ASCII),
            "required:".getBytes(StandardCharsets.US_ASCII),
    };
    private static final byte[] MOD_ANNOTATION =
            "Lnet/minecraftforge/fml/common/Mod;".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern ITEM = Pattern.compile(
            "^\\s*required(?:-after|-before|-client|-server)?:\\s*([A-Za-z0-9_.\\-]+)");

    /** Ids that are the loader or the game, not a mod in the folder. */
    static final Set<String> NOT_MODS = Set.of("forge", "minecraft", "fml", "mcp", "*");

    /**
     * Ids that name another mod loader, not a mod. A Forge 1.12 mod that
     * requires one of them is a build for that loader: Distant Horizons ships a
     * build with {@code required-after:cleanroom}, and plain Forge stops on it
     * before the first mod is constructed.
     */
    public static final Set<String> OTHER_LOADERS = Set.of("cleanroom");

    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    private LegacyDependencies() {
    }

    /** The mod ids a jar requires through its annotations, lower case; read once per version of the file. */
    public static List<String> of(Path jar) {
        String key;
        try {
            key = jar + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException e) {
            return List.of();
        }
        if (CACHE.size() > 4096) {
            CACHE.clear();
        }
        return CACHE.computeIfAbsent(key, ignored -> read(jar));
    }

    static List<String> read(Path jar) {
        Set<String> found = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int classes = 0;
            while (entries.hasMoreElements() && classes < MAX_CLASSES) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")
                        || entry.getSize() > MAX_CLASS_BYTES) {
                    continue;
                }
                classes++;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readNBytes(MAX_CLASS_BYTES + 1);
                }
                if (bytes.length > MAX_CLASS_BYTES || indexOf(bytes, MOD_ANNOTATION, 0) < 0) {
                    continue;
                }
                for (byte[] marker : MARKERS) {
                    for (int at = indexOf(bytes, marker, 0); at >= 0; at = indexOf(bytes, marker, at + 1)) {
                        found.addAll(parse(constantAt(bytes, at)));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not a readable jar; it declares nothing.
        }
        return List.copyOf(found);
    }

    /**
     * True for a jar that can only be a Forge 1.12 (or older) mod: none of the
     * newer descriptors. Those declare their requirements in the descriptor,
     * and reading every class of them would cost seconds for nothing.
     */
    public static boolean isLegacyForge(Path jar) {
        Set<LoaderCheck.Descriptor> found = LoaderCheck.descriptors(jar);
        return !found.contains(LoaderCheck.Descriptor.FABRIC) && !found.contains(LoaderCheck.Descriptor.QUILT)
                && !found.contains(LoaderCheck.Descriptor.NEOFORGE) && !found.contains(LoaderCheck.Descriptor.FORGE_TOML);
    }

    private static final Pattern MCMOD_ID = Pattern.compile("\"modid\"\\s*:\\s*\"([^\"]{1,64})\"");

    /**
     * Every mod id a jar provides: its main id, and for {@code mcmod.info}
     * every entry - one jar often carries a mod and its API or core.
     */
    public static List<String> provides(Path jar, String mainId) {
        Set<String> ids = new LinkedHashSet<>();
        if (mainId != null && !mainId.isBlank()) {
            ids.add(mainId.trim().toLowerCase(Locale.ROOT));
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry info = zip.getEntry("mcmod.info");
            if (info != null && info.getSize() < 256 * 1024) {
                try (InputStream in = zip.getInputStream(info)) {
                    Matcher matcher = MCMOD_ID.matcher(new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        ids.add(matcher.group(1).trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Nothing more than the main id.
        }
        return List.copyOf(ids);
    }

    /** The required ids of one dependency string: {@code required-after:a@[1,);after:b} is {@code a}. */
    public static List<String> parse(String dependencies) {
        List<String> ids = new ArrayList<>();
        for (String item : dependencies.split(";")) {
            Matcher matcher = ITEM.matcher(item);
            if (matcher.find()) {
                String id = matcher.group(1).toLowerCase(Locale.ROOT);
                int at = id.indexOf('@');
                id = at >= 0 ? id.substring(0, at) : id;
                if (!id.isEmpty() && !NOT_MODS.contains(id) && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** True when a Forge 1.12 mod requires another loader than Forge, such as Cleanroom. */
    public static boolean requiresOtherLoader(Path jar) {
        for (String id : of(jar)) {
            if (OTHER_LOADERS.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The UTF-8 constant the marker is part of.
     *
     * <p>The end is the first byte that is not printable: the tag of the next
     * constant. The start is where a tag {@code 1} and a two-byte length stand
     * just before the text and the length reaches exactly that end. Without
     * such a place (a string not in a constant pool), the printable run is
     * taken whole, as before.
     */
    static String constantAt(byte[] bytes, int at) {
        int run = at;
        while (run > 0 && printable(bytes[run - 1])) {
            run--;
        }
        int end = at;
        while (end < bytes.length && printable(bytes[end]) && end - run < 4096) {
            end++;
        }
        for (int start = run; start <= at; start++) {
            if (start >= 3 && bytes[start - 3] == 1
                    && (((bytes[start - 2] & 0xff) << 8) | (bytes[start - 1] & 0xff)) == end - start) {
                return new String(bytes, start, end - start, StandardCharsets.US_ASCII);
            }
        }
        return new String(bytes, run, end - run, StandardCharsets.US_ASCII);
    }

    private static boolean printable(byte b) {
        return b >= 0x20 && b < 0x7f;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
