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

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.launch.JavaProvisioner;
import com.hexadron.launcher.util.TreeDeleter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deletes what the storage window chose.
 *
 * <p>The window decides what; this refuses what must never go, whatever the
 * window asked for. Every path is checked here again, at the last moment:
 *
 * <ul>
 *   <li>it must be inside the data folder, and not the data folder itself;</li>
 *   <li>it must not be one of the launcher's own settings, accounts,
 *       credentials or the log it is writing - see
 *       {@link StorageScanner#protectedPaths};</li>
 *   <li>it must not be a folder that holds one of those.</li>
 * </ul>
 *
 * <p>Links are deleted as links, never followed - that is {@link TreeDeleter}'s
 * rule. A Java runtime goes through the launcher's own uninstall, which checks
 * the marker file that says the launcher put it there.
 */
public final class StorageCleaner {

    private StorageCleaner() {
    }

    /**
     * What came of it.
     *
     * @param deleted         what was removed, files and folders together
     * @param failed          what was left: refused, or locked by another program
     * @param profilesRemoved profiles taken out of the list with their folders
     */
    public record Result(int deleted, List<Path> failed, int profilesRemoved) {

        public Result {
            failed = List.copyOf(failed);
        }

        public Result(int deleted, List<Path> failed) {
            this(deleted, failed, 0);
        }

        public boolean isComplete() {
            return failed.isEmpty();
        }
    }

    /**
     * Carries out the actions, in order.
     *
     * <p>Reports {@code "delete:<name>"} as the stage for each step, so the window
     * can say what is going in the player's language, and file counts as items.
     */
    public static Result clean(GameDirs dirs, Path currentLog, JavaProvisioner java,
                               List<CleanupAction> actions, Progress progress)
            throws InterruptedException {

        Path root = dirs.root().toAbsolutePath().normalize();
        Set<Path> guarded = StorageScanner.protectedPaths(dirs, currentLog);
        List<Path> failed = new ArrayList<>();
        int deleted = 0;

        for (CleanupAction action : actions) {
            Set<Path> javaHomes = new HashSet<>();
            for (int major : action.javaMajors()) {
                Path home = java.home(major).toAbsolutePath().normalize();
                javaHomes.add(home);
                if (!isAllowed(root, guarded, home)) {
                    failed.add(home);
                    continue;
                }
                progress.stage("delete:Java " + major);
                try {
                    List<Path> left = java.uninstall(major);
                    failed.addAll(left);
                    if (left.isEmpty()) {
                        deleted++;
                    }
                } catch (IOException e) {
                    failed.add(home);
                }
            }

            for (Path tree : action.trees()) {
                Path target = tree.toAbsolutePath().normalize();
                if (javaHomes.contains(target)) {
                    continue;
                }
                if (!isAllowed(root, guarded, target)) {
                    failed.add(target);
                    continue;
                }
                progress.stage("delete:" + target.getFileName());
                TreeDeleter.Outcome outcome = TreeDeleter.deleteTree(target, progress, null);
                deleted += outcome.deleted();
                failed.addAll(outcome.failed());
            }

            if (!action.files().isEmpty() && action.filesRoot() != null) {
                Path filesRoot = action.filesRoot().toAbsolutePath().normalize();
                List<Path> allowed = new ArrayList<>();
                for (Path file : action.files()) {
                    Path target = file.toAbsolutePath().normalize();
                    if (target.startsWith(filesRoot) && isAllowed(root, guarded, target)) {
                        allowed.add(target);
                    } else {
                        failed.add(target);
                    }
                }
                if (!allowed.isEmpty() && isAllowed(root, guarded, filesRoot)) {
                    progress.stage("delete:" + filesRoot.getFileName());
                    TreeDeleter.Outcome outcome = TreeDeleter.deleteFiles(allowed, filesRoot, progress);
                    deleted += outcome.deleted();
                    failed.addAll(outcome.failed());
                }
            }
        }
        return new Result(deleted, failed);
    }

    /** The last check before a delete. Public so the self-check can put the refusals to it. */
    public static boolean isAllowed(Path root, Set<Path> guarded, Path target) {
        Path normalised = target.toAbsolutePath().normalize();
        if (!normalised.startsWith(root) || normalised.equals(root)) {
            return false;
        }
        for (Path protectedPath : guarded) {
            // Neither the file itself, nor anything in a protected folder, nor
            // a folder that has a protected file in it.
            if (normalised.startsWith(protectedPath) || protectedPath.startsWith(normalised)) {
                return false;
            }
        }
        return true;
    }
}
