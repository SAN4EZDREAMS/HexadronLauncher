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
    DEPENDENCY;

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
