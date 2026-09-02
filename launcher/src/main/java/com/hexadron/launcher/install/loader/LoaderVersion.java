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

package com.hexadron.launcher.install.loader;

/**
 * One selectable loader build for a given Minecraft version.
 *
 * @param type          which loader
 * @param version       the loader's own version, e.g. {@code 0.19.3}
 * @param stable        whether upstream marks this build as stable/recommended
 * @param versionId     the id the installed manifest will carry, e.g.
 *                      {@code fabric-loader-0.19.3-26.2}
 */
public record LoaderVersion(LoaderType type, String version, boolean stable, String versionId) {

    @Override
    public String toString() {
        return type.displayName() + " " + version + (stable ? "" : " (unstable)");
    }
}
