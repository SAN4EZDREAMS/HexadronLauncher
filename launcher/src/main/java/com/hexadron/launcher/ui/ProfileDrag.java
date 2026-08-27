package com.hexadron.launcher.ui;

import javafx.scene.input.Dragboard;

/**
 * The dragboard contract the two profile views share.
 *
 * <p>Small, and deliberately in one place. Both the list and the inventory grid
 * are drag sources and drop targets, and a profile picked up in one is dropped
 * in the other often enough - the views are switched mid-arrangement - that two
 * private copies of these prefixes would be a bug waiting for the day somebody
 * renamed one of them.
 *
 * <p>A prefix rather than a custom {@code DataFormat} because the payload also
 * has to be recognisable as not ours: a file dragged in from the desktop, or
 * text from another application, both arrive on the same dragboard, and
 * {@link #key} is the one check that says whether a drop belongs to the
 * launcher at all.
 */
final class ProfileDrag {

    static final String PROFILE = "hexadron-profile:";
    static final String GROUP = "hexadron-group:";

    private ProfileDrag() {
    }

    static String profile(String profileId) {
        return PROFILE + profileId;
    }

    static String group(String groupId) {
        return GROUP + groupId;
    }

    static boolean isProfile(String payload) {
        return payload != null && payload.startsWith(PROFILE);
    }

    static boolean isGroup(String payload) {
        return payload != null && payload.startsWith(GROUP);
    }

    /** The launcher's own payload, or null when the dragboard holds somebody else's. */
    static String key(Dragboard board) {
        if (board == null || !board.hasString()) {
            return null;
        }
        String value = board.getString();
        return (isProfile(value) || isGroup(value)) ? value : null;
    }

    /** The id inside a payload. */
    static String id(String payload) {
        if (isProfile(payload)) {
            return payload.substring(PROFILE.length());
        }
        if (isGroup(payload)) {
            return payload.substring(GROUP.length());
        }
        return payload;
    }
}
