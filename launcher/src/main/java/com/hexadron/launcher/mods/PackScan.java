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

import java.io.IOException;
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
import java.util.zip.ZipFile;

/**
 * One instance folder of packs, as one list.
 *
 * <p>{@code resourcepacks} and {@code shaderpacks}: the two kinds that live in a
 * folder of the instance's own, one file per pack, and are read by something
 * other than a mod loader. The same shape as {@link ModScan} and
 * {@link DatapackScan}, and for the same three reasons - the folder is the
 * authority, a pack the launcher did not download is listed rather than hidden,
 * and a pack renamed out of the way is listed as switched off rather than as
 * absent.
 *
 * <h2>Why one class for two kinds and not two</h2>
 *
 * <p>Because everything that differs between them is a constant, and there are
 * only three: the folder, the record file beside the packs, and the test for
 * whether a zip is that kind of pack at all. Everything else - the disabled
 * suffix, the pairing of records against files, the sort, the import rules, the
 * recycle bin - is identical work, and a second copy of it is a second place for
 * the two to drift apart.
 *
 * <h2>A pack is a zip or a folder</h2>
 *
 * <p>Minecraft accepts both for resource packs and Iris accepts both for
 * shaders, and players unzip packs to edit them. Both are listed. Only the zip
 * can be switched off, because that is done by renaming the file so the game
 * stops seeing a {@code .zip} - a folder renamed the same way still has its
 * contents and is still loaded, so offering the button there would be offering
 * something that does not work.
 *
 * <h2>There is no version verdict</h2>
 *
 * <p>A resource pack states {@code pack_format}, one number, and turning that
 * into "works on 1.21.4" needs a table of every release. A shader pack states
 * nothing at all: it is written against Iris or OptiFine rather than against a
 * Minecraft version. So the number is shown as the pack states it where there is
 * one, the verdict is {@link VersionRanges.Verdict#UNKNOWN} either way, and the
 * launcher does not claim to know what it does not.
 */
public final class PackScan {

    /** The suffix that makes the game stop seeing a pack. */
    public static final String DISABLED_SUFFIX = ".disabled";

    /** The folder a shader pack's programs live in, and what identifies one. */
    private static final String SHADERS_DIR = "shaders/";

    private final ContentKind kind;

    private PackScan(ContentKind kind) {
        this.kind = kind;
    }

    /**
     * A reader for one kind.
     *
     * @throws IllegalArgumentException for a kind that has no instance folder -
     *                                  a modpack is unpacked across the whole
     *                                  instance and a data pack goes into one
     *                                  world, so neither is a folder this reads
     */
    public static PackScan of(ContentKind kind) {
        if (kind == null || !kind.hasInstanceFolder() || kind == ContentKind.MOD) {
            throw new IllegalArgumentException(kind + " is not a pack folder this reads");
        }
        return new PackScan(kind);
    }

    public ContentKind kind() {
        return kind;
    }

    /** The record of what the launcher downloaded into this folder. */
    public ModLibrary libraryOf(Path packsDir) {
        return ModLibrary.read(packsDir, kind.lockFile());
    }

    /**
     * Reads the folder.
     *
     * <p>Never throws. An instance with no such folder yet is an empty list,
     * which is the truth: the folder appears when something is put in it, and
     * creating one to read it would be writing into the player's instance
     * because a window was opened.
     */
    public List<ModEntry> scan(Path packsDir) {
        if (packsDir == null || !Files.isDirectory(packsDir)) {
            return List.of();
        }

        Map<String, Path> files = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packsDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") || !isPackFile(entry)) {
                    continue;
                }
                files.put(enabledName(name), entry);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }

        ModLibrary library = libraryOf(packsDir);
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

    private ModEntry entryFor(InstalledMod pack, Path file) {
        String name = file.getFileName().toString();
        PackMeta meta = metaOf(file);
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

    private ModEntry externalEntry(Path file) {
        String name = file.getFileName().toString();
        PackMeta meta = metaOf(file);
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

    /**
     * What this pack says about itself.
     *
     * <p>A resource pack carries the same {@code pack.mcmeta} a data pack does,
     * so it is read the same way. A shader pack carries no manifest of any kind -
     * it is a folder of GLSL with a {@code shaders} directory in it - so there is
     * nothing to read, and inventing a description from the file name is
     * {@link ModInstaller#readableNameFrom}'s job rather than this one's.
     */
    private PackMeta metaOf(Path file) {
        return kind == ContentKind.RESOURCEPACK ? PackMeta.of(file) : PackMeta.NONE;
    }

    // ---------------------------------------------------------------- actions

    /**
     * Switches a pack on or off by renaming it.
     *
     * <p>Only a zip; {@link #isTogglable} is the question to ask first.
     *
     * @return the new path, or the current one when nothing was needed
     */
    public Path setEnabled(Path packsDir, ModEntry entry, boolean enabled) throws IOException {
        Path current = entry.path();
        if (!Files.isRegularFile(current) || entry.enabled() == enabled) {
            return current;
        }
        String name = current.getFileName().toString();
        Path target = packsDir.resolve(
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
     * packs. A pack the player made or edited themselves is the last thing in
     * the program that should be deleted outright.
     */
    public void discard(Path packsDir, ModEntry entry, Progress progress) throws IOException {
        Path file = entry.path();
        if (!Files.exists(file)) {
            return;
        }
        if (moveToTrash(file)) {
            progress.log("Moved %s to the recycle bin", entry.fileName());
            return;
        }
        Path graveyard = packsDir.resolve(ModScan.DISCARD_DIR);
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
     * Copies pack files the player chose into the folder.
     *
     * <p>Copies rather than moves, refuses to overwrite, and records nothing -
     * the same three rules as importing mods, and for the same reasons.
     */
    public ModScan.Imported importPacks(Path packsDir, List<Path> files, Progress progress)
            throws IOException {

        Files.createDirectories(packsDir);
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
            if (!kind.matches(name)) {
                skipped.add(new ModScan.Skip(name, ModScan.Reason.NOT_A_JAR, null));
                continue;
            }
            Path target = packsDir.resolve(enabledName(name));
            if (Files.exists(target)) {
                skipped.add(new ModScan.Skip(name, ModScan.Reason.ALREADY_THERE, null));
                continue;
            }
            if (!looksLikePack(source)) {
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

    /** True for something in the folder that the game or Iris might load. */
    public boolean isPackFile(Path entry) {
        if (Files.isDirectory(entry)) {
            return true;
        }
        return Files.isRegularFile(entry) && kind.matches(enabledName(entry.getFileName().toString()));
    }

    /**
     * True when this zip really is this kind of pack.
     *
     * <p>A resource pack is a zip with a {@code pack.mcmeta} in it: Minecraft
     * refuses one without, so a zip without one is not a resource pack however it
     * is named. A shader pack is a zip with a {@code shaders} folder in it, which
     * is what Iris and OptiFine look for - and the reason to check is that
     * "shaders.zip" is also what a browser calls a resource pack that happens to
     * contain core shaders.
     */
    public boolean looksLikePack(Path file) {
        if (Files.isDirectory(file)) {
            return kind == ContentKind.RESOURCEPACK
                    ? Files.isRegularFile(file.resolve(PackMeta.META_FILE))
                    : Files.isDirectory(file.resolve("shaders"));
        }
        if (kind == ContentKind.RESOURCEPACK) {
            return PackMeta.isPackArchive(file);
        }
        return hasShadersFolder(file);
    }

    /** True when this zip carries a {@code shaders} folder, nested or not. */
    private static boolean hasShadersFolder(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.stream().anyMatch(entry -> {
                String name = entry.getName().replace('\\', '/');
                // At the root, or one folder down - a pack zipped with its own
                // folder inside it, which Iris accepts.
                return name.startsWith(SHADERS_DIR)
                        || name.matches("^[^/]+/" + SHADERS_DIR + ".*");
            });
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static boolean isEnabled(String fileName) {
        return !fileName.toLowerCase(Locale.ROOT).endsWith(DISABLED_SUFFIX);
    }

    public static String enabledName(String fileName) {
        return isEnabled(fileName)
                ? fileName
                : fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length());
    }

    /** The record entry for one installed pack, when there is one. */
    public Optional<InstalledMod> recorded(Path packsDir, String key) {
        return libraryOf(packsDir).get(key);
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
}
