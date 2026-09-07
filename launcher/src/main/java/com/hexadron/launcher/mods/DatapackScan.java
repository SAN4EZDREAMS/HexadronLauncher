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

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Everything in one world's {@code datapacks} folder, as one list.
 *
 * <p>The same shape as {@link ModScan}, for the same reasons: the folder is the
 * authority, a pack the launcher did not download is listed rather than hidden,
 * and a pack renamed out of the game's way is listed as switched off rather than
 * as absent. What differs is three things.
 *
 * <h2>A data pack is a zip or a folder</h2>
 *
 * <p>Minecraft accepts both, and players unzip packs to edit them. Both are
 * listed. Only the zip can be switched off, because that is done by renaming the
 * file so the game no longer sees a {@code .zip} - a folder renamed the same way
 * still has a {@code pack.mcmeta} inside it and is still loaded, so offering the
 * button there would be offering something that does not work.
 *
 * <h2>There is no version to check</h2>
 *
 * <p>A mod jar says which Minecraft versions it needs, so the launcher can say
 * in advance that it will not load. A data pack says {@code pack_format}, a
 * single number, and turning that into "works on 1.21.4" needs a table of every
 * release which changes with every release. The launcher does not keep one and
 * therefore does not claim to know: the number is shown as the pack states it,
 * and the verdict is {@link VersionRanges.Verdict#UNKNOWN}. Minecraft itself
 * says "incompatible" next to the pack when it opens the world, which is the
 * answer from the only thing that has the table.
 *
 * <h2>Its record lives in the world</h2>
 *
 * <p>Beside the packs, under {@link #LOCK_FILE}, so a world copied to another
 * instance carries the knowledge of what its packs are with it.
 */
public final class DatapackScan {

    /** Which of a world's data packs the launcher downloaded. */
    public static final String LOCK_FILE = ".hexadron-datapacks.json";

    /** The suffix that makes the game stop seeing a pack. */
    public static final String DISABLED_SUFFIX = ".disabled";

    /** A pack's own description of itself. */
    public static final String META_FILE = "pack.mcmeta";

    /** A pack's own picture, which Minecraft shows beside it. */
    public static final String ICON_FILE = "pack.png";

    private DatapackScan() {
    }

    /** The record for one world. */
    public static ModLibrary libraryOf(Path datapacksDir) {
        return ModLibrary.read(datapacksDir, LOCK_FILE);
    }

    /**
     * Reads a world's data pack folder.
     *
     * <p>Never throws. A world with no {@code datapacks} folder yet is an empty
     * list, which is the truth - the folder is created when something is put in
     * it, and creating one to read it would be writing into the player's world
     * because a window was opened.
     */
    public static List<ModEntry> scan(Path datapacksDir) {
        if (datapacksDir == null || !Files.isDirectory(datapacksDir)) {
            return List.of();
        }

        Map<String, Path> files = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(datapacksDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") || !isDatapackFile(entry)) {
                    continue;
                }
                files.put(enabledName(name), entry);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }

        ModLibrary library = libraryOf(datapacksDir);
        List<ModEntry> entries = new ArrayList<>();

        for (InstalledMod pack : library.all()) {
            Path file = files.remove(pack.file().fileName());
            if (file == null) {
                continue;
            }
            entries.add(entryFor(pack, file));
        }
        for (Path file : files.values()) {
            entries.add(externalEntry(file));
        }

        entries.sort(Comparator
                .comparing((ModEntry entry) -> !entry.enabled())
                .thenComparing(ModEntry::title, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    private static ModEntry entryFor(InstalledMod pack, Path file) {
        String name = file.getFileName().toString();
        Meta meta = metaOf(file);
        return new ModEntry(
                pack.key(),
                pack.title(),
                meta.format(),
                meta.description(),
                List.of(),
                name,
                file,
                pack.origin(),
                pack.packId(),
                pack.iconUrl(),
                pack.pageUrl(),
                meta.iconPath(),
                isEnabled(name),
                null,
                VersionRanges.Verdict.UNKNOWN,
                pack.categories());
    }

    private static ModEntry externalEntry(Path file) {
        String name = file.getFileName().toString();
        Meta meta = metaOf(file);
        return new ModEntry(
                ModEntry.FILE_KEY_PREFIX + name,
                ModInstaller.readableNameFrom(enabledName(name)),
                meta.format(),
                meta.description(),
                List.of(),
                name,
                file,
                ModOrigin.EXTERNAL,
                null,
                null,
                null,
                meta.iconPath(),
                isEnabled(name),
                null,
                VersionRanges.Verdict.UNKNOWN,
                List.of());
    }

    // ---------------------------------------------------------------- actions

    /**
     * Switches a pack on or off by renaming it.
     *
     * <p>Only a zip, and {@link #isTogglable} is the question to ask first. A
     * folder cannot be switched off this way and the interface must not offer to.
     *
     * @return the new path, or the current one when nothing was needed
     */
    public static Path setEnabled(Path datapacksDir, ModEntry entry, boolean enabled)
            throws IOException {

        Path current = entry.path();
        if (!Files.isRegularFile(current) || entry.enabled() == enabled) {
            return current;
        }
        String name = current.getFileName().toString();
        Path target = datapacksDir.resolve(
                enabled ? enabledName(name) : enabledName(name) + DISABLED_SUFFIX);
        if (Files.exists(target)) {
            throw new IOException("there is already a file called " + target.getFileName());
        }
        return Files.move(current, target);
    }

    /** True when this row's on/off button can do anything. */
    public static boolean isTogglable(ModEntry entry) {
        return entry != null && Files.isRegularFile(entry.path());
    }

    /**
     * Gets rid of a pack the launcher did not install.
     *
     * <p>Same rule as {@link ModScan#discard}: to the recycle bin where the
     * desktop has one, and otherwise into a {@code .removed} folder beside the
     * packs. A pack the player made or edited themselves is the last thing in the
     * program that should be deleted outright.
     */
    public static void discard(Path datapacksDir, ModEntry entry, Progress progress)
            throws IOException {

        Path file = entry.path();
        if (!Files.exists(file)) {
            return;
        }
        if (moveToTrash(file)) {
            progress.log("Moved %s to the recycle bin", entry.fileName());
            return;
        }
        Path graveyard = datapacksDir.resolve(ModScan.DISCARD_DIR);
        Files.createDirectories(graveyard);
        Path target = free(graveyard.resolve(entry.fileName()));
        if (Files.isDirectory(file)) {
            Files.move(file, target);
        } else {
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
        }
        progress.log("Moved %s to %s", entry.fileName(), ModScan.DISCARD_DIR);
    }

    /**
     * Copies pack files the player chose into a world's folder.
     *
     * <p>Copies rather than moves, refuses to overwrite, and records nothing -
     * the same three rules as importing mods, and for the same reasons.
     */
    public static ModScan.Imported importPacks(Path datapacksDir, List<Path> files,
                                               Progress progress) throws IOException {

        Files.createDirectories(datapacksDir);
        List<String> imported = new ArrayList<>();
        List<ModScan.Skip> skipped = new ArrayList<>();
        int done = 0;

        for (Path source : files) {
            String name = source.getFileName().toString();
            progress.items(done++, files.size());

            if (!Files.isRegularFile(source)) {
                skipped.add(new ModScan.Skip(name, ModScan.Reason.NOT_A_FILE, null));
                continue;
            }
            if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                skipped.add(new ModScan.Skip(name, ModScan.Reason.NOT_A_JAR, null));
                continue;
            }
            Path target = datapacksDir.resolve(enabledName(name));
            if (Files.exists(target)) {
                skipped.add(new ModScan.Skip(name, ModScan.Reason.ALREADY_THERE, null));
                continue;
            }
            // A zip with a pack.mcmeta in it. Unlike a mod jar - where a missing
            // descriptor is a dialect this reader does not know and the file is
            // still a mod - pack.mcmeta is not optional: Minecraft refuses a pack
            // without one, so a zip without one is not a data pack.
            if (!hasMeta(source)) {
                skipped.add(new ModScan.Skip(name, ModScan.Reason.NOT_AN_ARCHIVE, null));
                continue;
            }
            try {
                Files.copy(source, target);
                imported.add(target.getFileName().toString());
                progress.log("Imported %s", target.getFileName());
            } catch (IOException e) {
                skipped.add(new ModScan.Skip(name, ModScan.Reason.FAILED,
                        e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        progress.items(files.size(), files.size());
        return new ModScan.Imported(imported, skipped);
    }

    // ---------------------------------------------------------------- names

    /** True for something in a datapacks folder that the game might load. */
    public static boolean isDatapackFile(Path entry) {
        if (Files.isDirectory(entry)) {
            return true;
        }
        return Files.isRegularFile(entry)
                && enabledName(entry.getFileName().toString())
                        .toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    public static boolean isEnabled(String fileName) {
        return !fileName.toLowerCase(Locale.ROOT).endsWith(DISABLED_SUFFIX);
    }

    public static String enabledName(String fileName) {
        return isEnabled(fileName)
                ? fileName
                : fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length());
    }

    // ---------------------------------------------------------------- reading

    /**
     * What a pack says about itself.
     *
     * @param format      {@code pack_format} as a line, or null
     * @param description one line, or null
     * @param iconPath    where the pack's picture is inside its zip, or null -
     *                    always null for a folder, which is not an archive to
     *                    read an entry out of
     */
    private record Meta(String format, String description, String iconPath) {

        static final Meta NONE = new Meta(null, null, null);
    }

    private static Meta metaOf(Path file) {
        if (Files.isDirectory(file)) {
            Path meta = file.resolve(META_FILE);
            if (!Files.isRegularFile(meta)) {
                return Meta.NONE;
            }
            try {
                return read(Json.parse(Files.readString(meta, StandardCharsets.UTF_8)), null);
            } catch (IOException | RuntimeException e) {
                return Meta.NONE;
            }
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(META_FILE);
            if (entry == null) {
                return Meta.NONE;
            }
            String icon = zip.getEntry(ICON_FILE) == null ? null : ICON_FILE;
            try (InputStream in = zip.getInputStream(entry)) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return read(Json.parse(text), icon);
            }
        } catch (IOException | RuntimeException e) {
            return Meta.NONE;
        }
    }

    private static Meta read(Json root, String iconPath) {
        Json pack = root.get("pack");
        int format = pack.get("pack_format").asInt(-1);
        return new Meta(format < 0 ? null : "pack format " + format,
                describe(pack.get("description")), iconPath);
    }

    /**
     * A pack's description, which may not be a string.
     *
     * <p>Minecraft accepts a raw JSON text component here, so a pack's
     * description is sometimes {@code "Fancy pack"}, sometimes
     * {@code {"text":"Fancy pack","color":"gold"}}, and sometimes a list of
     * those. All three say the same sentence, and the row wants the sentence.
     */
    private static String describe(Json description) {
        if (description.isString()) {
            String text = description.asString("");
            return text.isBlank() ? null : text;
        }
        if (description.isObject()) {
            String text = description.get("text").asString("");
            return text.isBlank() ? null : text;
        }
        if (description.isArray()) {
            StringBuilder line = new StringBuilder();
            for (Json part : description.elements()) {
                if (part.isString()) {
                    line.append(part.asString(""));
                } else if (part.isObject()) {
                    line.append(part.get("text").asString(""));
                }
            }
            String text = line.toString().trim();
            return text.isBlank() ? null : text;
        }
        return null;
    }

    private static boolean hasMeta(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry(META_FILE) != null) {
                return true;
            }
            // A pack zipped with its own folder inside it: "MyPack/pack.mcmeta".
            // Minecraft refuses that shape, and so does this - but the reason it
            // is looked for is so the refusal is about the right thing.
            return zip.stream().anyMatch(entry -> entry.getName().endsWith("/" + META_FILE));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean moveToTrash(Path file) {
        try {
            java.awt.Desktop desktop = java.awt.Desktop.isDesktopSupported()
                    ? java.awt.Desktop.getDesktop() : null;
            return desktop != null
                    && desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)
                    && desktop.moveToTrash(file.toFile());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Path free(Path wanted) {
        Path candidate = wanted;
        for (int suffix = 2; Files.exists(candidate) && suffix < 1000; suffix++) {
            candidate = wanted.resolveSibling(wanted.getFileName() + "." + suffix);
        }
        return candidate;
    }

    /** The record entry for one installed pack, when there is one. */
    public static Optional<InstalledMod> recorded(Path datapacksDir, String key) {
        return libraryOf(datapacksDir).get(key);
    }
}
