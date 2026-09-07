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

package com.hexadron.launcher.ui;

import com.hexadron.launcher.i18n.I18n;
import com.hexadron.launcher.mods.ModProvider;

/**
 * The platform filter, including "every platform".
 *
 * <p>Its own type because every catalogue in the window offers it and the label
 * for "all" is a translation rather than a platform name - which is the one thing
 * a plain {@link ModProvider.Source} cannot express.
 */
enum SourceChoice {

    ALL(null),
    MODRINTH(ModProvider.Source.MODRINTH),
    CURSEFORGE(ModProvider.Source.CURSEFORGE);

    private final ModProvider.Source source;

    SourceChoice(ModProvider.Source source) {
        this.source = source;
    }

    /** Null for "every platform", which is what the search layer expects. */
    ModProvider.Source source() {
        return source;
    }

    String label() {
        return source == null ? I18n.t("mods.source.all") : source.displayName();
    }
}
