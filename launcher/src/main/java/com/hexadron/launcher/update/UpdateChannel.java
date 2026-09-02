package com.hexadron.launcher.update;

import java.util.Locale;

/**
 * Which builds the launcher offers to update to.
 *
 * <h2>Two channels, one repository</h2>
 *
 * <p>Both read the same list of releases; they differ in what they are willing
 * to take from it. Release takes only what was published as a finished release,
 * which is what somebody who plays on this launcher wants. Nightly takes
 * whatever is newest, including the pre-releases built from the branch, which is
 * what somebody who is testing it wants - and which will, from time to time, be
 * broken.
 *
 * <p>Nightly is not a separate line of development. A nightly build is the same
 * project a few commits further on, so a nightly user who moves back to Release
 * simply stops being offered anything until a release passes the build they are
 * on. That is why the channel is a filter here rather than two update feeds.
 */
public enum UpdateChannel {

    /** Published releases only: finished, tagged, and not marked pre-release. */
    RELEASE("release"),

    /** Whatever is newest, pre-releases included. */
    NIGHTLY("nightly");

    private final String stored;

    UpdateChannel(String stored) {
        this.stored = stored;
    }

    /** The value written to the settings file. */
    public String stored() {
        return stored;
    }

    /** The translation key for the channel's name. */
    public String key() {
        return "settings.update.channel." + stored;
    }

    /** The translation key for the sentence under it. */
    public String noteKey() {
        return "settings.update.channel." + stored + ".note";
    }

    /**
     * Reads a stored value.
     *
     * <p>Anything unrecognised is {@link #RELEASE}: an unreadable settings file
     * must not be able to put somebody on test builds without them choosing to.
     */
    public static UpdateChannel parse(String value) {
        if (value == null) {
            return RELEASE;
        }
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        for (UpdateChannel channel : values()) {
            if (channel.stored.equals(wanted)) {
                return channel;
            }
        }
        return RELEASE;
    }

    /** True when this channel will take a build marked as a pre-release. */
    public boolean acceptsPrereleases() {
        return this == NIGHTLY;
    }
}
