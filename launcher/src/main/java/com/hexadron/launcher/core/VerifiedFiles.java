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

package com.hexadron.launcher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A record of which files have already been checked against their published
 * hash, so that a launch does not check them all again.
 *
 * <h2>The problem it solves</h2>
 *
 * <p>Every install pass - and a launch is an install pass, because that is what
 * makes the launcher able to repair itself - walks the whole file list and skips
 * anything whose SHA-1 already matches. Skipping is the right behaviour; the
 * cost is finding out. A modern Minecraft version is about five thousand asset
 * objects and a few hundred jars, and hashing them means opening and reading
 * every one, roughly two thirds of a gigabyte, before the game process is even
 * started. On Windows, where each of those opens also goes through the
 * real-time scanner, that is the better part of a minute of somebody staring at
 * a tray icon.
 *
 * <p>And it is repeated work in the exact sense: the answer was computed last
 * launch, and the file has not been touched since.
 *
 * <h2>What is remembered, and why that is honest</h2>
 *
 * <p>For each file: its size, its modification time, and the hash it was
 * verified against. A later run may skip the hash only when all three still
 * hold - the file is the same length, was not written since, and is being
 * checked against the same hash as before. Anything else - a different length, a
 * newer timestamp, a version whose manifest now publishes a different hash, no
 * record at all - falls through to reading the file, exactly as before.
 *
 * <p>So what is given up is precisely this: an attacker who can write into the
 * launcher's own data directory, and who also restores the size and the
 * modification time of what they replaced, and who also edits this ledger.
 * Anyone who can do the first can already drop a jar into an instance's
 * {@code mods} folder, which the game loads without anybody hashing anything.
 * The ledger does not stand between an attacker and the game; it stands between
 * the user and a minute of waiting.
 *
 * <p>What it does still catch is everything that actually goes wrong in
 * practice: a half-written file from a launcher killed mid-download, a truncated
 * jar, an edit by hand, bit rot on a failing disk, a file replaced by a
 * different one of another size. All of those change the size or the timestamp.
 *
 * <p>And it is only ever an optimisation of a check, never a substitute for one.
 * A file is recorded here only after it has been hashed and matched, or right
 * after it was downloaded and verified. Nothing enters this ledger unverified.
 *
 * <h2>Repair ignores it</h2>
 *
 * <p>{@link #DISABLED} answers "not verified" to everything and remembers
 * nothing. That is what the Install / repair button uses, because a user who
 * presses it is saying they do not trust what is on disk - and a repair that
 * trusted a ledger written by the run that produced the bad state would be
 * theatre.
 */
public final class VerifiedFiles {

    /**
     * A ledger that knows nothing and learns nothing.
     *
     * <p>For repair, and for the self-check and any other caller with no data
     * directory to keep one in.
     */
    public static final VerifiedFiles DISABLED = new VerifiedFiles(null);

    /**
     * Beyond this many records the ledger is pruned on save to what this run
     * actually used.
     *
     * <p>Entries are never dropped as files go: a deleted version leaves its
     * assets behind in the shared store, and pruning by checking what still
     * exists would mean stat-ing everything, which is the cost being avoided.
     * So the ledger is left to grow and trimmed when it gets silly. Fifty
     * thousand records is a few megabytes and covers a dozen versions.
     */
    private static final int PRUNE_ABOVE = 50_000;

    private static final String HEADER = "hexadron-verified-1";

    /** Key: path, relative to the data root where possible. */
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Keys touched this run, so a prune keeps what is in use. */
    private final Set<String> used = ConcurrentHashMap.newKeySet();

    private final Path file;
    private final Path root;

    private volatile boolean dirty;

    private VerifiedFiles(GameDirs dirs) {
        this.file = dirs == null ? null : dirs.cache().resolve("verified.index");
        this.root = dirs == null ? null : dirs.root();
    }

    /** Reads the ledger for these directories. A missing or damaged one is empty. */
    public static VerifiedFiles load(GameDirs dirs) {
        VerifiedFiles ledger = new VerifiedFiles(dirs);
        ledger.read();
        return ledger;
    }

    private record Entry(long size, long modified, String sha1) {
    }

    /**
     * Whether this file was already verified against this hash and has not been
     * touched since.
     *
     * @param attributes the file's attributes as just read by the caller - passed
     *                   in rather than read again, because the caller needs them
     *                   anyway and this runs once per file
     */
    public boolean isVerified(Path path, String sha1, BasicFileAttributes attributes) {
        if (file == null || sha1 == null || attributes == null) {
            return false;
        }
        String key = key(path);
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        boolean same = entry.size() == attributes.size()
                && entry.modified() == attributes.lastModifiedTime().toMillis()
                && entry.sha1().equalsIgnoreCase(sha1);
        if (same) {
            used.add(key);
        }
        return same;
    }

    /** Records a file that has just been hashed and matched, or just downloaded. */
    public void record(Path path, String sha1, BasicFileAttributes attributes) {
        if (file == null || sha1 == null || attributes == null) {
            return;
        }
        String key = key(path);
        entries.put(key, new Entry(attributes.size(),
                attributes.lastModifiedTime().toMillis(), sha1.toLowerCase(Locale.ROOT)));
        used.add(key);
        dirty = true;
    }

    /** Reads the attributes {@link #isVerified} and {@link #record} want, or null. */
    public static BasicFileAttributes attributesOf(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.isRegularFile() ? attributes : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** How many files are on record. */
    public int size() {
        return entries.size();
    }

    /**
     * Writes the ledger out, if anything was added.
     *
     * <p>Never throws. A ledger that cannot be written costs the next launch the
     * time this one saved, which is not a reason to fail an install that
     * otherwise succeeded.
     */
    public void save() {
        if (file == null || !dirty) {
            return;
        }
        Map<String, Entry> writing = entries;
        if (writing.size() > PRUNE_ABOVE) {
            Map<String, Entry> kept = new ConcurrentHashMap<>();
            for (String key : used) {
                Entry entry = entries.get(key);
                if (entry != null) {
                    kept.put(key, entry);
                }
            }
            writing = kept;
        }

        List<String> lines = new ArrayList<>(writing.size() + 1);
        lines.add(HEADER);
        writing.forEach((key, entry) -> lines.add(
                entry.sha1() + '\t' + entry.size() + '\t' + entry.modified() + '\t' + key));

        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".part");
            Files.write(temp, lines, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException ignored) {
            // Next launch re-hashes. Not worth reporting.
        }
    }

    private void read() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
                // A ledger from a future or a past format. Starting empty costs
                // one slow launch; guessing at what it means could cost a
                // corrupt file being trusted.
                return;
            }
            Set<String> seen = new HashSet<>();
            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split("\t", 4);
                if (parts.length != 4 || !seen.add(parts[3])) {
                    continue;
                }
                try {
                    entries.put(parts[3], new Entry(
                            Long.parseLong(parts[1]), Long.parseLong(parts[2]), parts[0]));
                } catch (NumberFormatException ignored) {
                    // One unreadable line does not invalidate the rest.
                }
            }
        } catch (IOException ignored) {
            // Same as a missing ledger.
        }
    }

    /**
     * The key for a path: relative to the data root when it is inside it.
     *
     * <p>Relative so that moving or renaming the data directory - or having it
     * on a drive that mounts under a different letter - does not silently
     * invalidate every record in it.
     */
    private String key(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (root != null) {
            Path base = root.toAbsolutePath().normalize();
            if (absolute.startsWith(base)) {
                return base.relativize(absolute).toString().replace('\\', '/');
            }
        }
        return absolute.toString().replace('\\', '/');
    }
}
