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

import java.util.List;

/**
 * One thing the safe mode offers to delete, and why.
 *
 * <p>Only ever made for files the launcher can show it does not use: a version
 * no profile runs, a library no installed version names, a download already
 * unpacked. Never for anything inside an instance folder.
 *
 * @param id         stable within one scan, for the interface's selection
 * @param titleKey   what it is
 * @param reasonKey  why it is safe to delete
 * @param reasonArgs what the reason names
 * @param details    the items inside it, largest first, for the list under the card
 */
public record CleanupCandidate(String id, StorageCategory category, String titleKey,
                               String reasonKey, Object[] reasonArgs, long size, long files,
                               List<Detail> details, CleanupAction action) {

    /** One line of the list under a card. */
    public record Detail(String name, long size) {
    }

    public CleanupCandidate {
        reasonArgs = reasonArgs == null ? new Object[0] : reasonArgs.clone();
        details = List.copyOf(details);
    }

    /** The description shown on hover: the same key as the title, with {@code .tip}. */
    public String tipKey() {
        return titleKey + ".tip";
    }

    @Override
    public Object[] reasonArgs() {
        return reasonArgs.clone();
    }
}
