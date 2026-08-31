package com.hexadron.launcher.mods;

/**
 * How a mod came to be in a profile's {@code mods} folder.
 *
 * <p>This is what decides whether the user may remove a file one at a time. A
 * pack is a set that was chosen and tested together: letting a single mod be
 * pulled out of it leaves something that is no longer the pack but still claims
 * to be, and the first symptom is a crash the user cannot connect to the
 * deletion. A pack goes in and comes out whole.
 */
public enum ModOrigin {

    /** Installed as part of a named pack. Removed only by removing that pack. */
    PACK,

    /** Chosen by the user in the mod browser. Removable on its own. */
    MANUAL,

    /** Pulled in because something else required it. Removable, with a warning. */
    DEPENDENCY,

    /**
     * Put there by the user, outside the launcher.
     *
     * <p>Never written to the lock file - the lock file is the record of what
     * the launcher downloaded, and the whole point of this value is that nothing
     * downloaded it. It exists so that a jar found in the folder can be shown in
     * the same list as the rest instead of being invisible, which is what a
     * player who copies a mod in and then cannot see it in the launcher
     * reasonably reads as the launcher not having noticed.
     *
     * <p>Removable, and the only origin whose removal cannot be undone by
     * installing it again: the launcher has no record of where it came from.
     */
    EXTERNAL;

    /** True when this entry may be removed by itself. */
    public boolean isRemovableAlone() {
        return this != PACK;
    }

    public static ModOrigin parse(String value) {
        if (value == null) {
            return MANUAL;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return MANUAL;
        }
    }
}
