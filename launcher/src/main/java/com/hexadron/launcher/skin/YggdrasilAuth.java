/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

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

        Session session = new Session(root,
                response.get("clientToken").asString(fallbackClientToken),
                response.get("accessToken").asString(""),
                undash(id),
                name);
        return register(session);
    }

    /**
     * What a profile is wearing, as the service reports it.
     *
     * @param slim true when the service says the arms are 3 pixels wide
     */
    public record Textures(String skinUrl, String capeUrl, boolean slim) {

        public static final Textures NONE = new Textures(null, null, false);

        /** True when the service knows the profile but has nothing on it. */
        public boolean isEmpty() {
            return skinUrl == null && capeUrl == null;
        }
    }

    /**
     * Reads what a profile wears, from the same endpoint the game will use.
     *
     * <h2>Why ask the service rather than draw the files on this PC</h2>
     *
     * <p>In remote mode the pictures on this machine are not what appears in
     * game - the service's are. A preview built from local files would show one
     * thing and the game another, and the first place that difference turns up
     * is a player wondering why their skin did not change.
     *
     * <p>An answer with no textures is a real answer, not a failure: it means
     * an account that exists at the service and has not uploaded anything.
     * That is the case the game draws its own default for, and so does the
     * window here.
     */
    public static Textures textures(String root, UUID uuid)
            throws IOException, InterruptedException {

        String undashed = uuid.toString().replace("-", "");
        String body = Http.getString(normalise(root)
                + "/sessionserver/session/minecraft/profile/" + undashed);
        if (body == null || body.isBlank()) {
            // 204: the service has never heard of this profile.
            return Textures.NONE;
        }

        Json profile = Json.parse(body);
        String encoded = null;
        for (Json property : profile.get("properties").elements()) {
            if ("textures".equals(property.get("name").asString(null))) {
                encoded = property.get("value").asString(null);
                break;
            }
        }
        if (encoded == null) {
            return Textures.NONE;
        }

        // The signature beside this value is what a *server* checks. Here it is
        // deliberately not checked: this is a picture for a preview window, the
        // connection it arrived over is the service's own, and a launcher that
        // refused to draw a preview over a signature question would be
        // theatre - the game does its own checking, with the service's key.
        Json textures = Json.parse(new String(
                java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
                .get("textures");

        Json skin = textures.get("SKIN");
        return new Textures(
                skin.get("url").asString(null),
                textures.get("CAPE").get("url").asString(null),
                "slim".equalsIgnoreCase(
                        skin.get("metadata").get("model").asString("")));
    }

    /**
     * Tells the redactor these are secrets, so they never reach a log.
     *
     * <p>The redactor's shape patterns describe what Microsoft and Xbox issue -
     * JWTs, {@code M.C5_...}, {@code XBL3.0 x=...}. A third-party service hands
     * out a plain random string, which no shape can recognise, so it has to be
     * registered by the code that receives it. Called wherever a session
     * enters the process: signing in, renewing, and reading one back off disk.
     */
    public static Session register(Session session) {
        if (session != null) {
            com.hexadron.launcher.util.Redactor.register(session.accessToken());
            com.hexadron.launcher.util.Redactor.register(session.clientToken());
        }
        return session;
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
