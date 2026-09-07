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

import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.mods.ModCategory;
import com.hexadron.launcher.profile.Profile;

import javafx.scene.Node;
import javafx.stage.Stage;

import java.util.Set;
import java.util.function.Consumer;

/**
 * One panel of the content window.
 *
 * <h2>What a section is, and what it is not</h2>
 *
 * <p>A section owns a kind of thing: what its catalogue looks like, what its
 * installed list looks like, and what the buttons on a row do. It does not own a
 * window, a status line, a thread, or the rule that only one install runs at a
 * time - all four are shared, all four are the window's, and a section that had
 * its own would be a second place for a download to report to and a second way
 * for two writes to the same folder to interleave.
 *
 * <p>So a section is handed a {@link Host}: the service, the stage to own dialogs
 * with, the one status line, and the two ways to run work. Everything it needs
 * and nothing it could use to fight another section.
 */
abstract class ContentSection {

    /** What the window lends its sections. */
    interface Host {

        /** A unit of work that may fail. */
        @FunctionalInterface
        interface Task {
            void run() throws Exception;
        }

        LauncherService service();

        /** The window, for owning dialogs and file choosers. */
        Stage stage();

        /**
         * The profile this window is for.
         *
         * <p>The same mutable object the instance dialog edits, so a version or
         * loader changed elsewhere is already visible here.
         */
        Profile profile();

        /** The one status line and progress bar, shared by every section. */
        BrowserProgress progress();

        /** Category names and the drawings beside them. */
        Categories categories();

        /** Which categories are ticked, so a row can put those first. */
        Set<ModCategory> highlightedCategories();

        /** True while a mutating task is running anywhere in this window. */
        boolean isBusy();

        /**
         * Runs something that changes nothing.
         *
         * <p>A search, a folder read, a lookup. Never blocked by an install, and
         * never blocking one.
         *
         * @param onFailure given the failure as one line, for a section that has
         *                  somewhere to put it - a list's placeholder, usually.
         *                  Null raises a dialog instead, which is right for
         *                  something the user pressed once and wrong for a search
         *                  they are repeating
         */
        void run(String name, Consumer<String> onFailure, Task task);

        /**
         * Runs something that changes the instance - install, remove, switch off.
         *
         * <p>One at a time across the whole window: two of these at once would
         * interleave writes to the same folders and the same record files, and
         * the loser would leave a file on disk that nothing records.
         */
        void mutate(String name, Task task);

        void warn(String header, String message);

        /**
         * Says that this instance's contents changed.
         *
         * <p>The window passes it on to the launcher, whose instance panel counts
         * mods, and to the other sections - installing a modpack changes what the
         * mods list holds.
         */
        void contentChanged();

        /**
         * Says that a profile itself changed, or that there is a new one.
         *
         * <h2>Why this is not {@link #contentChanged()}</h2>
         *
         * <p>Because they are different facts with different readers. Contents
         * are what is inside a profile - the mods list, the pack list, the count
         * on the launcher's panel. This one is the profile: its Minecraft
         * version, its loader, its picture, and whether it exists at all.
         *
         * <p>Installing a modpack is the one action in this window that does the
         * second. Into this profile, it takes the version and the loader off it
         * and puts the pack's on; into a new one, it creates a profile that the
         * launcher's list has never heard of. Reporting either as "the contents
         * changed" is what left the launcher showing the old version beside a
         * profile that no longer had it, and a new profile that appeared only
         * after something else happened to redraw the list.
         */
        void profileChanged();
    }

    protected final Host host;

    ContentSection(Host host) {
        this.host = host;
    }

    /** The panel itself, built once. */
    abstract Node node();

    /** Re-reads every string, after the language changed or the window reopened. */
    abstract void applyTexts();

    /** Re-reads the folder and redraws the lists. */
    abstract void refresh();

    /** What the sidebar calls this. */
    abstract String title();

    /** Called when the sidebar switches to this section. */
    void onShown() {
    }

    /** Called when a mutating task starts or ends anywhere in the window. */
    void onBusyChanged() {
    }

    /**
     * Called when this window's profile changed its Minecraft version or loader.
     *
     * <p>Which is to say: when every answer this section has on screen was
     * chosen against a pair that is no longer the profile's. A catalogue narrowed
     * to the version is showing the wrong catalogue, and a list of worlds belongs
     * to a profile that has just been rebuilt around a pack. Only a modpack
     * install does this, and it is the section that does it - which is why this
     * comes back through the window rather than being handled where it happens:
     * the other sections have to hear about it too.
     */
    void onProfileChanged() {
    }
}
