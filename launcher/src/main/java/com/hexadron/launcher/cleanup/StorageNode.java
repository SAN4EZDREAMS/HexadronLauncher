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

package com.hexadron.launcher.cleanup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One thing on disk, as the storage window shows it.
 *
 * <p>A node is either a folder or file ({@link #path()} set) or a heading that
 * groups some ({@link #path()} null - the eight categories). Sizes are summed
 * from the children when there are any, and measured from the disk when not.
 *
 * <p>Descriptions are translation keys, not sentences. This package has no
 * interface layer, the same rule as the rest of the core; the window turns the
 * key into the player's language.
 */
public final class StorageNode {

    private final String name;
    private final StorageCategory category;
    private final Path path;
    private final boolean deletable;
    private final String descriptionKey;
    private final Object[] descriptionArgs;
    private final List<StorageNode> children = new ArrayList<>();
    private StorageNode parent;
    private String noteKey;
    private Object[] noteArgs = new Object[0];
    private long size;
    private long files;
    private String profileId;

    StorageNode(String name, StorageCategory category, Path path, boolean deletable,
                String descriptionKey, Object... descriptionArgs) {
        this.name = name;
        this.category = category;
        this.path = path;
        this.deletable = deletable && path != null;
        this.descriptionKey = descriptionKey;
        this.descriptionArgs = descriptionArgs == null ? new Object[0] : descriptionArgs;
    }

    // ---------------------------------------------------------------- building

    StorageNode add(StorageNode child) {
        child.parent = this;
        children.add(child);
        return child;
    }

    StorageNode measured(long bytes, long fileCount) {
        this.size = bytes;
        this.files = fileCount;
        return this;
    }

    StorageNode note(String key, Object... args) {
        this.noteKey = key;
        this.noteArgs = args == null ? new Object[0] : args;
        return this;
    }

    StorageNode profile(String id) {
        this.profileId = id;
        return this;
    }

    /** Sums the children into this node, deepest first, and sorts them largest first. */
    StorageNode total() {
        if (children.isEmpty()) {
            return this;
        }
        long bytes = 0;
        long count = 0;
        for (StorageNode child : children) {
            child.total();
            bytes += child.size;
            count += child.files;
        }
        size = bytes;
        files = count;
        children.sort((a, b) -> Long.compare(b.size, a.size));
        return this;
    }

    // ---------------------------------------------------------------- reading

    /** What to call it: a folder name, a profile's name, a version id. */
    public String name() {
        return name;
    }

    public StorageCategory category() {
        return category;
    }

    /** Where it is, or null for a heading. */
    public Path path() {
        return path;
    }

    /**
     * True when this can be deleted as it stands.
     *
     * <p>False for the launcher's own settings, accounts and credentials, and
     * for the log it is writing: those are not storage, and a checkbox beside
     * them would be an offer to break the launcher.
     */
    public boolean isDeletable() {
        return deletable;
    }

    public String descriptionKey() {
        return descriptionKey;
    }

    public Object[] descriptionArgs() {
        return descriptionArgs.clone();
    }

    /** A second line - "used by 2 profiles", "not used" - or null. */
    public String noteKey() {
        return noteKey;
    }

    public Object[] noteArgs() {
        return noteArgs.clone();
    }

    public long size() {
        return size;
    }

    /**
     * The profile this is the instance folder of, or null.
     *
     * <p>Set only on the folder itself. Deleting it whole removes the profile as
     * well; deleting something inside it leaves the profile where it is.
     */
    public String profileId() {
        return profileId;
    }

    public long files() {
        return files;
    }

    public List<StorageNode> children() {
        return Collections.unmodifiableList(children);
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public StorageNode parent() {
        return parent;
    }

    /** How far below the root: the categories are 0. */
    public int depth() {
        int depth = -1;
        for (StorageNode node = parent; node != null; node = node.parent) {
            depth++;
        }
        return Math.max(0, depth);
    }

    @Override
    public String toString() {
        return name + " (" + size + " B)";
    }
}
