package com.hexadron.launcher.net;

import java.util.Locale;

/**
 * How the launcher reaches the network.
 *
 * <h2>Why this exists, and what it is not</h2>
 *
 * <p>On some networks Mojang's hosts simply do not answer - a corporate
 * firewall, a filtering antivirus, a provider blocking a range. The files are
 * fine and the launcher is fine; the route is not. A proxy gives the launcher
 * another route to <em>Mojang's own servers</em>.
 *
 * <p>It is deliberately not a mirror. A mirror would mean fetching Minecraft
 * from somebody who is not Mojang, and two things are wrong with that. The
 * first is licensing: Mojang's usage guidelines say "do not redistribute our
 * games or any alterations of our games or game files", so every such mirror is
 * hosting those files without permission, and a launcher that shipped a list of
 * them would be pointing its users at exactly that. The second is trust: the
 * SHA-1 of every file the launcher downloads comes from the version manifest,
 * so a mirror that also served the manifest would be supplying both the files
 * and the numbers they are checked against - which is not a check at all.
 *
 * <p>A proxy has neither problem. The bytes still come from Mojang, still over
 * TLS the proxy cannot read, still checked against hashes Mojang published.
 *
 * <h2>HTTP proxies only</h2>
 *
 * <p>Java's HTTP client speaks to HTTP proxies and not to SOCKS ones. Rather
 * than offer a SOCKS option that silently does nothing, there is no SOCKS
 * option. Most tools that provide a SOCKS port provide an HTTP port beside it.
 */
public record ProxyChoice(Mode mode, String host, int port, String user) {

    public enum Mode {
        /**
         * Whatever this computer is set up to use - the operating system's own
         * proxy settings, and the {@code -Dhttp.proxyHost} family if the
         * launcher was started with them.
         *
         * <p>The default, because it is what every browser on the machine does,
         * and because a user behind a proxy has already told their computer
         * about it once.
         */
        SYSTEM,
        /** Straight out, ignoring anything the system says. */
        DIRECT,
        /** The address typed into the settings. */
        MANUAL;

        public static Mode parse(String value) {
            if (value == null) {
                return SYSTEM;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "direct" -> DIRECT;
                case "manual" -> MANUAL;
                default -> SYSTEM;
            };
        }

        public String stored() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static ProxyChoice system() {
        return new ProxyChoice(Mode.SYSTEM, "", 8080, "");
    }

    /** True when the mode is MANUAL and there is somewhere to send traffic. */
    public boolean isUsable() {
        return mode != Mode.MANUAL || (!host.isBlank() && port > 0 && port <= 65535);
    }

    public boolean wantsAuthentication() {
        return mode == Mode.MANUAL && !user.isBlank();
    }

    public ProxyChoice withMode(Mode value) {
        return new ProxyChoice(value, host, port, user);
    }

    public ProxyChoice withHost(String value) {
        return new ProxyChoice(mode, value == null ? "" : value.trim(), port, user);
    }

    public ProxyChoice withPort(int value) {
        return new ProxyChoice(mode, host, value, user);
    }

    public ProxyChoice withUser(String value) {
        return new ProxyChoice(mode, host, port, value == null ? "" : value.trim());
    }

    @Override
    public String toString() {
        return switch (mode) {
            case SYSTEM -> "system proxy settings";
            case DIRECT -> "no proxy";
            case MANUAL -> (user.isBlank() ? "" : user + "@") + host + ":" + port;
        };
    }
}
