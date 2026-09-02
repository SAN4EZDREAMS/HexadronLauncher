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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A Yggdrasil service for one player, on the loopback interface, for the length
 * of one game session.
 *
 * <h2>What it is for</h2>
 *
 * <p>An offline account has no profile anywhere, so it has no textures, so the
 * game draws Steve. The client asks a session service for the textures of every
 * profile it has to render, including its own; point it at this and its own
 * question gets an answer. Nothing is signed up for, nothing is uploaded, and
 * it works with the network cable out.
 *
 * <p>The client is pointed here by authlib-injector, which replaces the
 * authentication library's endpoints with a base URL. See
 * {@link AuthlibInjector}.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It answers for exactly one profile - the account being launched. Every
 * other lookup gets {@code 204 No Content}, which is what Mojang's service
 * returns for a profile with no textures and what the client already knows how
 * to handle: the other player is drawn with the default skin. Pretending to
 * know about profiles this launcher has never heard of would mean answering
 * questions about other people with made-up data.
 *
 * <p>It also does not authenticate anybody. {@code /authserver/*} accepts what
 * it is given and hands back the same token, because there is no account here
 * to get wrong: this is a texture service wearing the shape of a Yggdrasil one,
 * and the shape is what authlib-injector requires.
 *
 * <h2>Security</h2>
 *
 * <ul>
 *   <li><b>Bound to the loopback address on an ephemeral port.</b> Nothing off
 *       this machine can reach it, and two launchers do not collide.</li>
 *   <li><b>It serves two files.</b> Texture requests are matched against the
 *       hashes of the skin and the cape this session was started with; a path
 *       is never taken from the request. There is no traversal to attempt.</li>
 *   <li><b>The signing key is generated once and kept in the data folder.</b>
 *       It signs texture metadata, which is all Yggdrasil signatures are for.
 *       It is not a credential and grants nothing.</li>
 *   <li><b>It stops when the game does.</b> {@link #close()} is called from the
 *       session's exit handler.</li>
 * </ul>
 */
public final class LocalSkinService implements AutoCloseable {

    /** How the service names itself in the client's log. */
    private static final String NAME = "Hexadron local skins";

    private final HttpServer server;
    private final String root;
    private final Json metadata;

    /** Texture hash to the file it names. Two entries at most. */
    private final Map<String, byte[]> textures;

    private LocalSkinService(HttpServer server, String root, Json metadata,
                             Map<String, byte[]> textures) {
        this.server = server;
        this.root = root;
        this.metadata = metadata;
        this.textures = textures;
    }

    /** The base URL to hand to authlib-injector. */
    public String root() {
        return root;
    }

    /**
     * The service's own description, as authlib-injector would fetch it.
     *
     * <p>Handed over on the command line instead, so the client has it before
     * it makes a single request. That removes a round trip from start-up and,
     * more usefully, removes a way for the game to fail to start because a
     * loopback request lost a race with the JVM.
     */
    public String prefetchedMetadata() {
        return Base64.getEncoder().encodeToString(
                metadata.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Starts the service for one account.
     *
     * @param keyFile where the signing key lives, generated on first use
     */
    public static LocalSkinService start(UUID uuid, String username, SkinProfile profile,
                                         SkinStore store, Path keyFile) throws IOException {

        KeyPair keys = keys(keyFile);

        // The bytes to serve, not the files. Minecraft takes a 64x64 sheet and
        // the pre-1.8 64x32, and discards everything else with one line in its
        // own log - so a high-resolution skin arrived, verified, and vanished.
        // The file on disk stays as the user chose it; this is what goes down
        // the wire.
        Map<String, byte[]> textures = new HashMap<>();
        byte[] skin = sheet(store.file(profile.skin()), false);
        byte[] cape = sheet(store.file(profile.cape()), true);

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String root = "http://127.0.0.1:" + server.getAddress().getPort();

        Json textureSet = Json.object();
        if (skin != null) {
            // Of the bytes served rather than of the file: they can differ, and
            // a name that does not describe what is behind it is a bug waiting
            // for a cache to find it.
            String hash = hashOf(skin);
            textures.put(hash, skin);
            Json entry = Json.object().put("url", root + "/textures/" + hash);
            if (profile.model() == SkinProfile.Model.SLIM) {
                // The only metadata the format carries, and the difference
                // between a skin rendering as Alex and as Steve.
                entry.put("metadata", Json.object().put("model", "slim"));
            }
            textureSet.put("SKIN", entry);
        }
        if (cape != null) {
            String hash = hashOf(cape);
            textures.put(hash, cape);
            textureSet.put("CAPE", Json.object().put("url", root + "/textures/" + hash));
        }

        Json profileJson = profileJson(uuid, username, textureSet, keys.getPrivate());
        Json metadata = Json.object()
                .put("meta", Json.object()
                        .put("serverName", NAME)
                        .put("implementationName", "hexadron-local")
                        .put("implementationVersion", "1"))
                .put("skinDomains", Json.array().add("127.0.0.1").add("localhost"))
                .put("signaturePublickey", pem(keys));

        LocalSkinService service = new LocalSkinService(server, root, metadata, textures);
        server.createContext("/", service::handle);
        server.setExecutor(null);
        service.profile = profileJson;
        service.uuid = undashed(uuid);
        service.username = username;
        server.start();
        return service;
    }

    private volatile Json profile;
    private volatile String uuid;
    private volatile String username;

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                respond(exchange, 200, "application/json", metadata.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/textures/")) {
                byte[] texture = textures.get(path.substring("/textures/".length()));
                if (texture == null) {
                    respond(exchange, 404, "text/plain", new byte[0]);
                    return;
                }
                respond(exchange, 200, "image/png", texture);
                return;
            }
            if (path.startsWith("/sessionserver/session/minecraft/profile/")) {
                String asked = path.substring(path.lastIndexOf('/') + 1).replace("-", "")
                        .toLowerCase(Locale.ROOT);
                // 204 for anybody else, which is what a profile with no textures
                // looks like. The client draws them with the default skin.
                if (!asked.equalsIgnoreCase(uuid)) {
                    respond(exchange, 204, "application/json", new byte[0]);
                    return;
                }
                respond(exchange, 200, "application/json",
                        profile.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/sessionserver/session/minecraft/hasJoined")) {
                // Asked by a server in online mode. There is no server here that
                // could have called join, so nobody has joined.
                respond(exchange, 204, "application/json", new byte[0]);
                return;
            }
            if (path.equals("/sessionserver/session/minecraft/join")) {
                respond(exchange, 204, "application/json", new byte[0]);
                return;
            }
            if (path.equals("/api/profiles/minecraft")) {
                // Bulk name lookup. Answers for this one player and nobody else.
                byte[] body = exchange.getRequestBody().readAllBytes();
                Json names = safeParse(new String(body, StandardCharsets.UTF_8));
                Json result = Json.array();
                for (Json name : names.elements()) {
                    if (username.equalsIgnoreCase(name.asString(""))) {
                        result.add(Json.object().put("id", uuid).put("name", username));
                    }
                }
                respond(exchange, 200, "application/json",
                        result.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/authserver/")) {
                // Shape, not substance: there is no account here to authenticate.
                respond(exchange, 200, "application/json",
                        Json.object().toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 404, "text/plain", new byte[0]);
        } catch (RuntimeException e) {
            // A handler that throws leaves the client waiting on a socket that
            // never answers, which shows up as the game hanging on start-up.
            respond(exchange, 500, "text/plain", new byte[0]);
        }
    }

    private static Json safeParse(String text) {
        try {
            return Json.parse(text);
        } catch (RuntimeException e) {
            return Json.array();
        }
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        if (status == 204 || body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * The profile document, with the textures property signed.
     *
     * <p>Signed because the client may ask for signed data, and an unsigned
     * answer to that question is discarded rather than used. The signature is
     * over the base64 value, which is what Yggdrasil signs.
     */
    private static Json profileJson(UUID uuid, String username, Json textures, PrivateKey key) {
        Json payload = Json.object()
                .put("timestamp", System.currentTimeMillis())
                .put("profileId", undashed(uuid))
                .put("profileName", username)
                .put("textures", textures);
        String value = Base64.getEncoder().encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8));

        Json property = Json.object().put("name", "textures").put("value", value);
        String signature = sign(value, key);
        if (signature != null) {
            property.put("signature", signature);
        }
        return Json.object()
                .put("id", undashed(uuid))
                .put("name", username)
                .put("properties", Json.array().add(property));
    }

    private static String sign(String value, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(key);
            signature.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            // Unsigned still works for a client that does not ask for signatures.
            return null;
        }
    }

    /**
     * The signing key, generated on first use and kept afterwards.
     *
     * <p>Kept rather than generated per session because the public half is
     * published in the service description, and a key that changed every launch
     * would invalidate anything the client had cached from the last one.
     */
    private static KeyPair keys(Path keyFile) throws IOException {
        try {
            if (Files.isRegularFile(keyFile)) {
                Json stored = Json.read(keyFile);
                byte[] priv = Base64.getDecoder().decode(stored.get("private").asString(""));
                byte[] pub = Base64.getDecoder().decode(stored.get("public").asString(""));
                KeyFactory factory = KeyFactory.getInstance("RSA");
                return new KeyPair(
                        factory.generatePublic(new X509EncodedKeySpec(pub)),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(priv)));
            }
        } catch (Exception ignored) {
            // Unreadable or from another version: a new one costs nothing.
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(4096);
            KeyPair pair = generator.generateKeyPair();
            Files.createDirectories(keyFile.getParent());
            Json.object()
                    .put("private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()))
                    .put("public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                    .write(keyFile);
            return pair;
        } catch (Exception e) {
            throw new IOException("could not create the skin signing key: " + e.getMessage(), e);
        }
    }

    private static String pem(KeyPair keys) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keys.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    /**
     * The sheet to serve for one file, or null when there is no file.
     *
     * <p>Never fails a launch: a picture that cannot be normalised is served as
     * it is, and the worst that happens is what happened before this existed.
     */
    private static byte[] sheet(Path file, boolean cape) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return SkinSheets.forGame(file, cape);
        } catch (IOException | RuntimeException e) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    private static String hashOf(byte[] texture) {
        // Named by content, as Mojang's texture URLs are - and by the content
        // actually served, which is not always the content of the file.
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest(texture)) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
