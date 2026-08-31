package com.hexadron.launcher.mods;

import java.nio.file.Path;
import java.util.List;

/**
 * One row of a profile's mod list, whoever put the file there.
 *
 * <h2>Why this is not {@link InstalledMod}</h2>
 *
 * <p>{@link InstalledMod} is a lock-file record: it exists because the launcher
 * downloaded something and has to remember what and why. Half of what is in a
 * mods folder has no such record - a player drags jars in from a browser, from a
 * friend, from an old instance - and to that half the lock file is not merely
 * empty but deliberately empty, because the rule that stops a pack install from
 * deleting a player's own mods is exactly "if it is not in the file, it is not
 * ours to touch".
 *
 * <p>So the list the user reads and the record the installer keeps are two
 * different things, and this is the first. It is assembled by {@link ModScan}
 * from three sources - the lock file, the folder itself, and each jar's own
 * descriptor - and is read-only: acting on a row goes back through the service,
 * which knows which of those sources is allowed to change.
 *
 * @param key         a stable identity for the row: the lock key for a managed
 *                    mod, {@code file:<name>} for one the launcher did not
 *                    install
 * @param title       what to call it
 * @param version     the mod's own version, read from the jar, or null
 * @param description one line at most, or null
 * @param authors     as the jar publishes them, possibly empty
 * @param fileName    the name on disk, {@code .disabled} suffix included
 * @param path        the file itself
 * @param origin      who put it there
 * @param packId      the pack that owns it, for {@link ModOrigin#PACK}
 * @param iconUrl     the project's logo on Modrinth or CurseForge, or null
 * @param pageUrl     the project's page, or null when there is nothing to open
 * @param iconJarPath the mod's own icon inside the jar, used when there is no
 *                    {@code iconUrl} - which is the normal case for a file the
 *                    launcher did not download
 * @param enabled     false for a jar renamed to {@code .disabled}, which the
 *                    loader ignores and the launcher therefore must not present
 *                    as installed
 */
public record ModEntry(String key, String title, String version, String description,
                       List<String> authors, String fileName, Path path,
                       ModOrigin origin, String packId,
                       String iconUrl, String pageUrl, String iconJarPath,
                       boolean enabled) {

    /** The prefix that keeps a file-based key from ever colliding with a lock key. */
    public static final String FILE_KEY_PREFIX = "file:";

    public ModEntry {
        authors = List.copyOf(authors);
    }

    /** True when the launcher downloaded this and has a record of it. */
    public boolean isManaged() {
        return origin != ModOrigin.EXTERNAL;
    }

    /** True when this row's Remove button should work. */
    public boolean isRemovable() {
        return origin.isRemovableAlone();
    }

    /** True when there is a page worth offering to open. */
    public boolean hasPage() {
        return pageUrl != null && !pageUrl.isBlank();
    }

    /** The authors as one line, or null when the jar names none. */
    public String authorLine() {
        return authors.isEmpty() ? null : String.join(", ", authors);
    }

    /** The file name with any {@code .disabled} suffix taken off. */
    public String jarName() {
        return ModScan.enabledName(fileName);
    }
}
