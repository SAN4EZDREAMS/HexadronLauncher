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
import java.util.List;

/**
 * What one cleanup deletes.
 *
 * @param trees     folders and files deleted whole
 * @param files     single files deleted one by one, with any folder they leave
 *                  empty under {@code filesRoot}
 * @param filesRoot where emptied folders stop being removed; null when
 *                  {@code files} is empty
 * @param javaMajors Java runtimes the launcher downloaded, uninstalled through
 *                  the launcher's own uninstall so its records stay right
 * @param size      what it frees, as measured when it was planned
 */
public record CleanupAction(List<Path> trees, List<Path> files, Path filesRoot,
                            List<Integer> javaMajors, long size) {

    public CleanupAction {
        trees = List.copyOf(trees);
        files = List.copyOf(files);
        javaMajors = List.copyOf(javaMajors);
    }

    /** One folder or file, whole. */
    public static CleanupAction tree(Path path, long size) {
        return new CleanupAction(List.of(path), List.of(), null, List.of(), size);
    }

    /** Several of them. */
    public static CleanupAction trees(List<Path> paths, long size) {
        return new CleanupAction(paths, List.of(), null, List.of(), size);
    }

    /** Loose files under one folder. */
    public static CleanupAction files(List<Path> paths, Path root, long size) {
        return new CleanupAction(List.of(), paths, root, List.of(), size);
    }

    /** Java runtimes, by major version. */
    public static CleanupAction java(List<Integer> majors, List<Path> homes, long size) {
        return new CleanupAction(homes, List.of(), null, majors, size);
    }

    /** How many separate things it touches, for the progress count. */
    public int steps() {
        return trees.size() + (files.isEmpty() ? 0 : 1);
    }

    /** Several actions as one list, in order. */
    static List<CleanupAction> list(List<CleanupAction> actions) {
        return new ArrayList<>(actions);
    }
}
