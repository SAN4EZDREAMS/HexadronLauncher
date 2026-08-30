package com.hexadron.launcher.skin;

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Signing in to a skin service.
 *
 * <h2>Why this is needed at all</h2>
 *
 * <p>Pointing the game at a service is half of it. The other half is being
 * somebody that service has heard of: it answers questions about profiles, and
 * an offline account's profile - a UUID derived from a name, on this machine,
 * a minute ago - is not one of them. Without this the agent is attached, every
 * lookup returns "no such profile", and nothing appears, which is exactly what
 * it looks like when a feature is not finished.
 *
 * <p>So a remote service means a real account on that service, and the game is
 * launched as that account: its UUID, its name, its token. Which is also what
 * makes the skin visible to other players - on a server set up for the same
 * service, that UUID is who you are to everybody.
 *
 * <h2>The protocol</h2>
 *
 * <p>Yggdrasil, the scheme Mojang used before Microsoft accounts and which
 * every third-party skin service implements. Three calls matter: exchange a
 * password for a token, check whether a token is still good, and renew one that
 * is not. It is a small and very old API, and that is why services as different
 * as LittleSkin, Ely.by and a self-hosted Blessing Skin all speak it.
 *
 * <h2>What is kept</h2>
 *
 * <p>Not the password. It is exchanged once, for a token pair, and forgotten;
 * the pair goes to the same credential store the Microsoft tokens use, which on
 * Windows means the operating system holds the key. A token can be revoked from
 * the service's own site, which a password shared with a launcher cannot.
 */
public final class YggdrasilAuth {

    private YggdrasilAuth() {
    }

    /** A signed-in identity at one service. */
    public record Session(String root, String clientToken, String accessToken,
                          UUID uuid, String name) {

        public Json toJson() {
            return Json.object()
                    .put("root", root)
                    .put("clientToken", clientToken)
                    .put("accessToken", accessToken)
                    .put("uuid", uuid.toString())
                    .put("name", name);
        }

        public static Session fromJson(Json json) {
            try {
                return new Session(
                        json.get("root").asString(""),
                        json.get("clientToken").asString(""),
                        json.get("accessToken").asString(""),
                        UUID.fromString(json.get("uuid").asString("")),
                        json.get("name").asString(""));
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** True when this session belongs to the service currently configured. */
        public boolean isFor(String service) {
            return root.equals(normalise(service));
        }
    }

    /**
     * Trims a service address to the form the calls are built on.
     *
     * <p>A trailing slash turns every endpoint into a double slash, which some
     * services route and some answer 404 to - a failure that looks like a wrong
     * password.
     */
    public static String normalise(String root) {
        String trimmed = root == null ? "" : root.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Checks that an address is one this launcher will talk to.
     *
     * @return null when it is usable, otherwise why it is not
     */
    public static String reasonToRefuse(String root) {
        String address = normalise(root);
        if (address.isEmpty()) {
            return "no address";
        }
        if (!address.toLowerCase(Locale.ROOT).startsWith("https://")) {
            // The password goes over this connection. There is no version of
            // sending it in the clear that is worth supporting.
            return "the address has to start with https://";
        }
        return null;
    }

    /**
     * Exchanges a password for a token pair.
     *
     * @param clientToken this installation's own identifier, kept across
     *                    sign-ins so that renewing a token does not invalidate
     *                    the same account's session elsewhere
     */
    public static Session authenticate(String root, String user, String password,
                                       String clientToken) throws IOException, InterruptedException {

        String address = normalise(root);
        Json response = Http.authPostJson(address + "/authserver/authenticate",
                Json.object()
                        .put("agent", Json.object().put("name", "Minecraft").put("version", 1))
                        .put("username", user)
                        .put("password", password)
                        .put("clientToken", clientToken)
                        .put("requestUser", false),
                Map.of());

        return session(address, response, clientToken);
    }

    /** Renews a token pair. */
    public static Session refresh(Session session) throws IOException, InterruptedException {
        Json response = Http.authPostJson(session.root() + "/authserver/refresh",
                Json.object()
                        .put("accessToken", session.accessToken())
                        .put("clientToken", session.clientToken()),
                Map.of());
        return session(session.root(), response, session.clientToken());
    }

    /**
     * Whether a token is still good.
     *
     * <p>Answered with 204 and nothing else when it is, and 403 when it is not,
     * so the status is the answer and there is no body to read.
     */
    public static boolean validate(Session session) throws InterruptedException {
        try {
            Http.authSend("POST", session.root() + "/authserver/validate",
                    Json.object()
                            .put("accessToken", session.accessToken())
                            .put("clientToken", session.clientToken())
                            .toString().getBytes(StandardCharsets.UTF_8),
                    Map.of("Content-Type", "application/json"));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Session session(String root, Json response, String fallbackClientToken)
            throws IOException {

        Json profile = response.get("selectedProfile");
        if (!profile.exists()) {
            // Some services hand back several characters and select none. The
            // first is a better answer than a failure, and the name is shown, so
            // a wrong guess is visible rather than silent.
            profile = response.get("availableProfiles").get(0);
        }
        String id = profile.get("id").asString(null);
        String name = profile.get("name").asString(null);
        if (id == null || name == null) {
            throw new IOException("the service signed in but returned no character to play as");
        }

        return new Session(root,
                response.get("clientToken").asString(fallbackClientToken),
                response.get("accessToken").asString(""),
                undash(id),
                name);
    }

    /** Yggdrasil writes UUIDs without dashes; the game wants them with. */
    public static UUID undash(String id) throws IOException {
        try {
            return com.hexadron.launcher.auth.Account.parseUndashedUuid(id.replace("-", ""));
        } catch (RuntimeException e) {
            throw new IOException("the service returned an unreadable profile id");
        }
    }

    /**
     * What went wrong, in the words the service used.
     *
     * <p>Yggdrasil answers a refusal with a JSON body carrying an
     * {@code errorMessage}, and that message is the useful half - "Invalid
     * credentials. Invalid username or password." tells somebody what to do
     * next, where "HTTP 403" does not. The status line is kept only when there
     * is no message to find, which is what a wrong address looks like: a 404
     * from a web server that has never heard of Yggdrasil.
     */
    public static String describe(Exception failure) {
        if (failure instanceof Http.HttpStatusException status) {
            String message = null;
            try {
                message = Json.parse(status.body()).get("errorMessage").asString(null);
            } catch (RuntimeException ignored) {
                // Not JSON at all: a proxy page, or a site that is not a service.
                // Which is itself worth reporting, below.
            }
            if (message != null && !message.isBlank()) {
                return message;
            }
            if (status.statusCode() == 404) {
                return "the address answered, but it is not a skin service (HTTP 404)."
                        + " Check that it is the service's API root.";
            }
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.toString() : message;
    }
}
