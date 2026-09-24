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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one scan found.
 *
 * @param root          the tree: one child per category that has anything in it
 * @param candidates    what the safe mode offers, largest first
 * @param usableBytes   free space on the drive the data folder is on, or -1
 * @param diskBytes     that drive's size, or -1
 * @param sharedStore   true when the data folder is also another launcher's -
 *                      versions, libraries and assets are then left alone
 * @param unresolved    true when a profile's version could not be read, so
 *                      which libraries and assets are in use cannot be known
 *                      and none is offered
 * @param notes         translation keys of anything the safe mode held back on
 */
public record StorageReport(Path dataRoot, StorageNode root, List<CleanupCandidate> candidates,
                            long usableBytes, long diskBytes, boolean sharedStore,
                            boolean unresolved, List<String> notes) {

    public StorageReport {
        candidates = List.copyOf(candidates);
        notes = List.copyOf(notes);
    }

    public long totalBytes() {
        return root.size();
    }

    public long totalFiles() {
        return root.files();
    }

    /** What the safe mode could free if everything it offers were ticked. */
    public long reclaimableBytes() {
        return candidates.stream().mapToLong(CleanupCandidate::size).sum();
    }

    /** Bytes per category, zero for the ones that are empty. */
    public Map<StorageCategory, Long> byCategory() {
        Map<StorageCategory, Long> sizes = new EnumMap<>(StorageCategory.class);
        for (StorageCategory category : StorageCategory.values()) {
            sizes.put(category, 0L);
        }
        for (StorageNode node : root.children()) {
            sizes.merge(node.category(), node.size(), Long::sum);
        }
        return sizes;
    }
}
