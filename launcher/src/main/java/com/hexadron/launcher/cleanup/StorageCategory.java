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

import java.util.Locale;

/**
 * What the launcher's space is spent on, in the order the chart draws it.
 *
 * <p>Eight at most, and the last is the neutral one: a chart with a ninth hue
 * is a chart whose colours stop meaning anything.
 */
public enum StorageCategory {

    /** The instances: worlds, mods, configs. The player's own. */
    INSTANCES,

    /** Installed Minecraft and loader versions, and the natives extracted for them. */
    VERSIONS,

    /** The shared library store every version draws from. */
    LIBRARIES,

    /** Sounds, languages and textures shared between versions. */
    ASSETS,

    /** Java runtimes the launcher downloaded. */
    JAVA,

    /** Downloads kept to avoid downloading them again. */
    CACHE,

    /** The launcher's own logs. */
    LOGS,

    /** Settings, accounts, icons, skins and anything else in the data folder. */
    OTHER;

    /** The translation key of the category's name. */
    public String key() {
        return "cleanup.category." + name().toLowerCase(Locale.ROOT);
    }
}
