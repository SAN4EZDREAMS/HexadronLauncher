package com.hexadron.launcher.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * The one-shot loopback listener that receives the OAuth authorization code.
 *
 * <p>RFC 8252 §7.3 defines this pattern for native apps: the app opens the
 * user's real browser, the authorization server redirects to
 * {@code http://127.0.0.1:<port>/}, and the app reads the code off its own
 * socket. It is the pattern that lets a desktop launcher use the system browser
 * instead of an embedded web view - which matters because §8.12 forbids the
 * embedded view outright, and because a user typing their Microsoft password
 * into a window the launcher drew has no way to check who drew it.
 *
 * <p>Choices here that are security-relevant rather than incidental:
 *
 * <ul>
 *   <li><b>Bound to the loopback address, never {@code 0.0.0.0}.</b> The socket
 *       must not be reachable from the network, only from this machine.</li>
 *   <li><b>Ephemeral port.</b> Port 0 lets the kernel pick a free one. A fixed
 *       port collides when two launchers or two users run at once, and a
 *       collision is worse than an inconvenience: whoever already holds the
 *       port receives the code.</li>
 *   <li><b>{@code state} is checked before the code is accepted.</b> Without
 *       it, anything on the machine can POST or GET a code of its choosing to
 *       the listener and have the launcher exchange it - authorization code
 *       injection, the attack RFC 9700 keeps {@code state} for.</li>
 *   <li><b>The server stops after the first valid response.</b> It exists for
 *       one redirect and no longer.</li>
 *   <li><b>The browser is answered with a plain page, and the query string is
 *       never echoed back into it.</b> Reflecting the parameters would put the
 *       code into a page and, worse, into any XSS an attacker could induce.</li>
 * </ul>
 */
public final class LoopbackRedirectServer implements AutoCloseable {

    /** Path the redirect lands on. Kept short - Azure matches it exactly. */
    public static final String CALLBACK_PATH = "/";

    private final HttpServer server;
    private final Pkce pkce;
    private final CompletableFuture<String> code = new CompletableFuture<>();

    private LoopbackRedirectServer(HttpServer server, Pkce pkce) {
        this.server = server;
        this.pkce = pkce;
    }

    /** Binds a free loopback port and starts listening. */
    public static LoopbackRedirectServer start(Pkce pkce) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        LoopbackRedirectServer listener = new LoopbackRedirectServer(server, pkce);
        server.createContext(CALLBACK_PATH, listener::handle);
        server.setExecutor(null);
        server.start();
        return listener;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * The redirect URI to send to Microsoft.
     *
     * <p>{@code 127.0.0.1} rather than {@code localhost}, on Microsoft's own
     * advice: {@code localhost} resolves through the hosts file and the name
     * service, so a renamed interface or an edited hosts file can send the
     * redirect somewhere else entirely.
     */
    public String redirectUri() {
        return "http://127.0.0.1:" + port() + CALLBACK_PATH;
    }

    /**
     * Blocks until the browser comes back with a code.
     *
     * @throws MicrosoftAuth.AuthException when the user denied consent, the
     *                                     state did not match, or nothing
     *                                     arrived within {@code timeoutSeconds}
     */
    public String awaitCode(int timeoutSeconds) throws IOException, InterruptedException {
        return awaitCode(timeoutSeconds, () -> false);
    }

    /** How often the cancellation flag is looked at while waiting. */
    private static final long POLL_MILLIS = 200;

    /**
     * Blocks until the browser comes back with a code, the wait is cancelled, or
     * the timeout runs out.
     *
     * <p>Cancellation is polled rather than awaited, and that is the whole point.
     * Closing the browser tab sends nothing - there is no signal and there cannot
     * be one. Without this the launcher sat through the entire timeout on the
     * commonest way for a sign-in to end, a user changing their mind, with a
     * button that had nothing to do and no way to say so.
     *
     * @param cancelled checked every {@code 200} ms
     * @throws MicrosoftAuth.AuthException when consent was denied, the state did
     *                                     not match, the wait was cancelled, or
     *                                     nothing arrived in time
     */
    public String awaitCode(int timeoutSeconds, BooleanSupplier cancelled)
            throws IOException, InterruptedException {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            if (cancelled.getAsBoolean()) {
                cancel();
            }
            try {
                return code.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (System.nanoTime() >= deadline) {
                    throw new MicrosoftAuth.AuthException("sign-in was not completed within "
                            + timeoutSeconds + " seconds; start again");
                }
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new MicrosoftAuth.AuthException("sign-in failed", cause);
            }
        }
    }

    /** Aborts the wait, e.g. because the user pressed Cancel. */
    public void cancel() {
        code.completeExceptionally(new MicrosoftAuth.AuthException("sign-in cancelled"));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- handler

    private void handle(HttpExchange exchange) {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

            String error = query.get("error");
            if (error != null) {
                // error_description comes from Microsoft and is safe to show, but it
                // is not put into the HTML page - only into the exception.
                String description = query.getOrDefault("error_description", error);
                respond(exchange, 400, "Sign-in was not completed",
                        "You can close this tab and return to the launcher.");
                code.completeExceptionally(new MicrosoftAuth.AuthException(
                        "Microsoft refused the sign-in: " + description));
                return;
            }

            String returnedState = query.get("state");
            String authorizationCode = query.get("code");

            if (authorizationCode == null) {
                // A stray request - a browser prefetch, a probe. Not the redirect.
                respond(exchange, 404, "Nothing here", "This page is not part of the sign-in.");
                return;
            }

            if (!pkce.matchesState(returnedState)) {
                respond(exchange, 400, "Sign-in rejected",
                        "The response did not match this sign-in attempt. Start again in the launcher.");
                code.completeExceptionally(new MicrosoftAuth.AuthException(
                        "the sign-in response did not match this attempt (state mismatch) - "
                                + "it was not started by this launcher and has been discarded"));
                return;
            }

            respond(exchange, 200, "Signed in",
                    "You can close this tab and return to the launcher.");
            code.complete(authorizationCode);
        } catch (IOException e) {
            code.completeExceptionally(e);
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String heading, String body)
            throws IOException {
        String html = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <title>Hexadron Launcher</title>
                <style>
                  body { font-family: system-ui, sans-serif; background:#16181d; color:#e6e6e6;
                         display:flex; align-items:center; justify-content:center; height:100vh; margin:0 }
                  main { text-align:center; max-width:32rem; padding:2rem }
                  h1 { font-size:1.4rem; font-weight:600; margin:0 0 .5rem }
                  p { color:#a9b0bb; margin:0 }
                </style></head>
                <body><main><h1>%s</h1><p>%s</p></main></body></html>
                """.formatted(escape(heading), escape(body));

        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        // The browser must not keep this page, and must not send a referrer
        // carrying the code in the query string to anywhere else.
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            values.putIfAbsent(name, value);
        }
        return values;
    }
}
