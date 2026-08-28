package com.hexadron.launcher.ui;

import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.profile.ProfileLayout;

import java.util.List;

/**
 * What a profile view is allowed to ask the launcher window for.
 *
 * <h2>Why the views share one of these</h2>
 *
 * <p>There are two interfaces onto the same profiles - a list and a grid of
 * inventory cells - and the requirement they exist under is that they are one
 * thing shown twice. The way that is guaranteed here is that neither view owns
 * any state: no copy of the profiles, no order of its own, no idea of which one
 * is selected. Each is a function of {@link #profiles()}, {@link #layout()} and
 * {@link #selected()}, and every change either of them makes goes back through
 * this interface to the one place that holds those.
 *
 * <p>So the two cannot drift. A drag in the grid calls
 * {@link #layoutChanged()}, which saves and rebuilds both; a rename in the
 * dialog does the same. There is no synchronising step between the views
 * because there is nothing in either of them to synchronise.
 */
public interface ProfileHost {

    LauncherService service();

    /** The shared arrangement: groups, order, and which interface is showing. */
    ProfileLayout layout();

    /** Every profile, in the arranged order. */
    List<Profile> profiles();

    /** The current search text, lower case and trimmed; empty for "no filter". */
    String filter();

    /** True when this profile passes the current search. */
    boolean matchesFilter(Profile profile);

    Profile selected();

    /** Makes a profile the selected one, in both views and in the summary panel. */
    void select(Profile profile);

    // ------------------------------------------------------------ profile actions

    /** Starts the game, as the Play button does. */
    void play(Profile profile);

    void edit(Profile profile);

    void remove(Profile profile);

    void install(Profile profile);

    void openMods(Profile profile);

    void openFolder(Profile profile);

    /** Asks for a picture and puts it on the profile. */
    void chooseIcon(Profile profile);

    /** Drops a chosen picture, so the loader mark comes back. */
    void clearIcon(Profile profile);

    // ------------------------------------------------------------ arrangement

    /**
     * Saves the arrangement and rebuilds both views from it.
     *
     * <p>Called after every drag, every group change and every collapse. Saving
     * on each one rather than at shutdown is deliberate: an arrangement the user
     * spent a minute on must not be lost because the launcher was closed from
     * the task manager, or because the game took it down with it.
     */
    void layoutChanged();

    /** Asks for a name, makes a group, and puts {@code profile} in it when given one. */
    void createGroup(Profile profile);

    /** Opens the editor for a brand new instance. */
    void createProfile();

    /**
     * Makes a group that takes one particular row of the grid.
     *
     * <p>Separate from {@link #createGroup} because a row that was pointed at may
     * already have instances in it, and the two answers to that - the group takes
     * them, or they move out of its way - are the user's to give. The host asks;
     * the layout does as it is told.
     */
    void createGroupInRow(int row);

    /**
     * Says something the user needs to read but must not be stopped by.
     *
     * <p>Used for a refusal with a reason - "there is nowhere to move these
     * profiles to, widen the grid first". A dialog for that would be a dialog in
     * the way of a gesture somebody is in the middle of repeating, so it goes on
     * screen beside the grid and fades.
     */
    void hint(String message);

    /** Opens the settings window. The same one from either interface. */
    void openSettings();

    /** Opens the group's own settings: its name and its colour. */
    void editGroup(ProfileLayout.Group group);

    void removeGroup(ProfileLayout.Group group);
}
