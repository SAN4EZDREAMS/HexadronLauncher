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
