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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything in a profile's mods folder, as one list.
 *
 * <h2>What this fixes</h2>
 *
 * <p>The launcher used to show the lock file. That is the record of what it
 * downloaded, so a jar the player copied in themselves - from a browser, from a
 * friend, from a previous instance - was not in it and did not appear anywhere.
 * From inside the launcher the mod did not exist: it could not be seen, named,
 * read about, switched off or removed, while the game loaded it and sometimes
 * crashed because of it.
 *
 * <p>So the folder is read as well, and anything in it that the lock file does
 * not claim is listed as {@link ModOrigin#EXTERNAL} with whatever the jar says
 * about itself. Reading the folder changes nothing in it. The rule that protects
 * a player's own files - if the launcher did not record it, the launcher does
 * not touch it - is untouched: these entries are shown, and are removed only
 * when the user presses the button on that row.
 *
 * <h2>Disabled mods</h2>
 *
 * <p>A jar renamed to end in {@code .disabled} is the convention every launcher
 * uses for "keep this but do not load it", and the loader ignores it. Both are
 * listed, because a mod that is present and switched off is a different thing
 * from a mod that is not there - and the launcher is the obvious place to switch
 * it back on.
 */
public final class ModScan {

    /** The suffix that marks a jar the loader will ignore. */
    public static final String DISABLED_SUFFIX = ".disabled";

    /** Where files go when the recycle bin is not available. */
    public static final String DISCARD_DIR = ".removed";

    /**
     * Descriptors already read, keyed by path, size and modification time.
     *
     * <p>The list is redrawn on every install, removal and window opening, and
     * re-reading eighty archives each time is work with a known answer. Any of
     * the three parts of the key changing means the file did, and the entry is
     * simply replaced.
     */
    private static final Map<String, LocalModInfo> DESCRIPTORS = new ConcurrentHashMap<>();

    /** A file that has no descriptor at all, so the miss is cached as well. */
    private static final LocalModInfo NONE =
            new LocalModInfo(null, null, null, null, List.of(), null, null, null);

    private ModScan() {
    }

    /**
     * Reads the folder.
     *
     * <p>Never throws: this is called to draw a list, and a mods folder that
     * cannot be read is an empty list plus a game that will say so.
     */
    public static List<ModEntry> scan(Path modsDir) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return List.of();
        }

        // Every jar in the folder, indexed by the name it would have if enabled,
        // so that a mod and its switched-off self are the same entry.
        Map<String, Path> files = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                // The lock file and this launcher's own bookkeeping are not mods,
                // and neither is anything else a folder collects.
                if (name.startsWith(".") || !isJar(name) || !Files.isRegularFile(file)) {
                    continue;
                }
                files.put(enabledName(name), file);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }

        ModLibrary library = ModLibrary.read(modsDir);
        ExternalModIndex index = ExternalModIndex.read(modsDir);
        List<ModEntry> entries = new ArrayList<>();

        for (InstalledMod mod : library.all()) {
            Path file = files.remove(mod.file().fileName());
            // Recorded but gone from the folder. The folder is the authority:
            // a player who deleted a jar in Explorer is not shown a mod that is
            // not there.
            if (file == null) {
                continue;
            }
            entries.add(entryFor(mod, file));
        }
        for (Path file : files.values()) {
            entries.add(externalEntry(file, index));
        }

        entries.sort(Comparator
                // Switched-off mods sink: they are not loaded, so they are not
                // part of what this instance currently is.
                .comparing((ModEntry entry) -> !entry.enabled())
                .thenComparing(ModEntry::title, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    /** A row for a mod the launcher downloaded and has a record of. */
    private static ModEntry entryFor(InstalledMod mod, Path file) {
        String name = file.getFileName().toString();
        LocalModInfo info = descriptorOf(file);
        return new ModEntry(
                mod.key(),
                mod.title(),
                info.version(),
                info.description(),
                info.authors(),
                name,
                file,
                mod.origin(),
                mod.packId(),
                mod.iconUrl(),
                // The platform's page is the one to offer. The jar's own link is
                // the fallback for entries written before the page was recorded.
                mod.pageUrl() != null ? mod.pageUrl() : info.homepage(),
                info.iconPath(),
                isEnabled(name));
    }

    /** A row for a jar the launcher did not put there. */
    private static ModEntry externalEntry(Path file, ExternalModIndex index) {
        String name = file.getFileName().toString();
        LocalModInfo info = descriptorOf(file);
        Optional<ModProvider.ProjectCard> known = index.get(name, sizeOf(file));
        return new ModEntry(
                ModEntry.FILE_KEY_PREFIX + name,
                known.map(ModProvider.ProjectCard::title).orElseGet(() -> info.displayName(name)),
                info.version(),
                info.description(),
                info.authors(),
                name,
                file,
                ModOrigin.EXTERNAL,
                null,
                known.map(ModProvider.ProjectCard::iconUrl).orElse(null),
                known.map(ModProvider.ProjectCard::pageUrl).orElse(info.homepage()),
                info.iconPath(),
                isEnabled(name));
    }

    // ---------------------------------------------------------------- actions

    /**
     * Switches one mod on or off by renaming its file.
     *
     * <p>Renaming rather than moving: the file stays in the folder the user put
     * it in, under a name every other launcher and every guide on the subject
     * agrees on, so switching a mod off here and looking for it in Explorer
     * afterwards finds it where it was.
     *
     * @return the file's new path, or its current one when nothing was needed
     */
    public static Path setEnabled(Path modsDir, ModEntry entry, boolean enabled) throws IOException {
        Path current = entry.path();
        if (!Files.isRegularFile(current) || entry.enabled() == enabled) {
            return current;
        }
        String name = current.getFileName().toString();
        Path target = modsDir.resolve(enabled ? enabledName(name) : enabledName(name) + DISABLED_SUFFIX);
        if (Files.exists(target)) {
            throw new IOException("there is already a file called " + target.getFileName());
        }
        return Files.move(current, target);
    }

    /**
     * Gets rid of a file the launcher did not install.
     *
     * <p>To the recycle bin where the desktop has one, and otherwise into a
     * {@code .removed} folder beside the mods. Not deleted, in either case, and
     * this is the difference that matters: a mod the launcher downloaded can be
     * downloaded again from the record it kept, and one the player dragged in
     * cannot - the launcher does not know what it was or where it came from. The
     * one irreversible deletion in the program should not be the one performed
     * on the files it knows least about.
     */
    public static void discard(Path modsDir, ModEntry entry, Progress progress) throws IOException {
        Path file = entry.path();
        if (!Files.exists(file)) {
            return;
        }
        if (moveToTrash(file)) {
            progress.log("Moved %s to the recycle bin", entry.fileName());
        } else {
            Path graveyard = modsDir.resolve(DISCARD_DIR);
            Files.createDirectories(graveyard);
            Path target = free(graveyard.resolve(entry.fileName()));
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            progress.log("Moved %s to %s", entry.fileName(), DISCARD_DIR);
        }
        DESCRIPTORS.keySet().removeIf(key -> key.startsWith(file.toString() + "|"));
    }

    /**
     * Hands the file to the desktop's recycle bin.
     *
     * @return false when this desktop has none, which is normal on a bare Linux
     *         session and is the reason there is a fallback at all
     */
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

    /** The first name in this folder that is not taken. */
    private static Path free(Path wanted) {
        Path candidate = wanted;
        for (int suffix = 2; Files.exists(candidate) && suffix < 1000; suffix++) {
            candidate = wanted.resolveSibling(wanted.getFileName() + "." + suffix);
        }
        return candidate;
    }

    // ---------------------------------------------------------------- names

    /** True for a jar, switched on or off. */
    public static boolean isJar(String fileName) {
        return enabledName(fileName).toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /** True unless the file has been renamed out of the loader's way. */
    public static boolean isEnabled(String fileName) {
        return !fileName.toLowerCase(Locale.ROOT).endsWith(DISABLED_SUFFIX);
    }

    /** The name this file would have if it were switched on. */
    public static String enabledName(String fileName) {
        return isEnabled(fileName)
                ? fileName
                : fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length());
    }

    // ---------------------------------------------------------------- reading

    /** The jar's own description of itself, read once per version of the file. */
    private static LocalModInfo descriptorOf(Path file) {
        // Every entry is one small record and the key is unique per version of
        // a file, so the map only grows when files change. Clearing it wholesale
        // at a generous ceiling costs one re-read of the visible rows and cannot
        // turn a long-running launcher into a leak.
        if (DESCRIPTORS.size() > 2048) {
            DESCRIPTORS.clear();
        }
        String key;
        try {
            key = file.toString() + "|" + Files.size(file) + "|"
                    + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return NONE;
        }
        return DESCRIPTORS.computeIfAbsent(key, ignored -> LocalModInfo.read(file).orElse(NONE));
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }
}
