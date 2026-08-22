package com.hexadron.launcher.net;

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.util.Redactor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Shared HTTP client with retry and a launcher-identifying User-Agent.
 *
 * <p>Mojang, Modrinth and CurseForge all rate-limit and all expect a
 * descriptive User-Agent; Modrinth's API terms ask for a contactable one
 * explicitly. Retries cover the 5xx/429 and transient-IO cases only - a 404 is
 * an answer, not a failure to retry.
 */
public final class Http {

    public static final String USER_AGENT = "HexadronLauncher/0.2.0 (+https://github.com/hexadron/HexadronLauncher)";

    private static final int MAX_ATTEMPTS = 4;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Headers that belong to a host rather than to a call site.
     *
     * <p>This exists because of one platform. CurseForge has always required its
     * API key on {@code api.curseforge.com}, and since July 2026 it requires the
     * same key on the content hosts that serve the actual mod files. Those files
     * are fetched by the generic downloader, which knows nothing about CurseForge
     * and should not have to - so the key is attached here, by host, once, and
     * every path that goes through this class is covered: metadata, file
     * downloads, and anything added later.
     *
     * <p>Registered as a supplier rather than a value so that a key entered in
     * the settings while the launcher is running takes effect immediately.
     */
    private record HostHeaders(Predicate<String> matches, Supplier<Map<String, String>> headers) {
    }

    private static final List<HostHeaders> HOST_HEADERS = new CopyOnWriteArrayList<>();

    /**
     * The client used for every credential-bearing request.
     *
     * <p>It differs from {@link #CLIENT} in one deliberate way: it does not
     * follow redirects. A redirect on the authentication path is not a routine
     * event - it is either a misconfiguration or a redirect to a host the
     * launcher did not choose, and following it would forward an
     * {@code Authorization} header or a form body containing a refresh token to
     * that host. Refusing is the correct response to both.
     *
     * <p>TLS is the JDK default, which means certificate and hostname
     * verification against the platform trust store, TLS 1.2 minimum and TLS 1.3
     * preferred. The launcher deliberately installs no custom
     * {@code SSLContext}, no custom {@code TrustManager} and no hostname-verifier
     * override anywhere - the commonest way a desktop client ends up accepting a
     * proxy's certificate is a developer switching one of those off during
     * debugging and shipping it.
     */
    private static final HttpClient AUTH_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private Http() {
    }

    public static HttpClient client() {
        return CLIENT;
    }

    /**
     * Attaches headers to every request whose host {@code matches}.
     *
     * <p>Scoped by host on purpose: a credential must reach the one service it
     * belongs to and no other. An explicit header passed to a call always wins,
     * so a caller can still override.
     */
    public static void registerHostHeaders(Predicate<String> matches,
                                           Supplier<Map<String, String>> headers) {
        HOST_HEADERS.add(new HostHeaders(matches, headers));
    }

    /** Drops every host header rule. For the self-check only. */
    public static void clearHostHeaders() {
        HOST_HEADERS.clear();
    }

    /**
     * The host headers that would be sent to one URI.
     *
     * <p>Public because it is the only way to assert the property that matters:
     * that a credential registered for one service reaches that service's hosts
     * and no others. The self-check does exactly that.
     */
    public static Map<String, String> hostHeadersFor(URI uri) {
        String host = uri.getHost();
        if (host == null || HOST_HEADERS.isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (HostHeaders entry : HOST_HEADERS) {
            if (entry.matches().test(host)) {
                headers.putAll(entry.headers().get());
            }
        }
        return headers;
    }

    /**
     * Rejects anything that is not HTTPS.
     *
     * <p>Called on every authentication endpoint. Mojang's version metadata
     * still contains a handful of {@code http://} library URLs, so this cannot
     * be a blanket rule for downloads - those are covered by the SHA-1 in the
     * manifest instead - but a token must never travel in clear text, and a
     * mistyped constant is exactly how that happens.
     */
    public static String requireHttps(String url) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("refusing to send credentials over a non-HTTPS URL: " + uri.getHost());
        }
        return url;
    }

    /** A non-2xx response that should surface to the caller rather than be retried. */
    public static final class HttpStatusException extends IOException {
        private final int statusCode;
        private final String uri;
        private final String body;

        public HttpStatusException(int statusCode, String uri, String body) {
            // The body is scrubbed before it reaches the message. Error responses
            // from the Xbox and Minecraft endpoints routinely echo back tokens,
            // and this message ends up in the launcher log and in stack traces.
            super("HTTP " + statusCode + " for " + uri
                    + (body == null || body.isBlank() ? "" : ": " + truncate(Redactor.scrub(body))));
            this.statusCode = statusCode;
            this.uri = uri;
            this.body = body;
        }

        public int statusCode() {
            return statusCode;
        }

        public String uri() {
            return uri;
        }

        public String body() {
            return body == null ? "" : body;
        }

        private static String truncate(String s) {
            return s.length() <= 500 ? s : s.substring(0, 500) + "...";
        }
    }

    // ---------------------------------------------------------------- GET

    public static String getString(String url) throws IOException, InterruptedException {
        return getString(url, Map.of());
    }

    public static String getString(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpResponse<String> response = send(
                requestBuilder(url, headers).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.body();
    }

    public static Json getJson(String url) throws IOException, InterruptedException {
        return getJson(url, Map.of());
    }

    public static Json getJson(String url, Map<String, String> headers) throws IOException, InterruptedException {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putIfAbsent("Accept", "application/json");
        return Json.parse(getString(url, merged));
    }

    public static byte[] getBytes(String url) throws IOException, InterruptedException {
        return send(requestBuilder(url, Map.of()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    /**
     * Streams a response body. The caller owns the stream and must close it.
     * Used by the downloader so large jars never sit in the heap.
     */
    public static InputStream openStream(String url) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = CLIENT.send(
                requestBuilder(url, Map.of()).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new HttpStatusException(response.statusCode(), url, "");
        }
        return response.body();
    }

    // ---------------------------------------------------------------- POST

    public static Json postJson(String url, Json body, Map<String, String> headers)
            throws IOException, InterruptedException {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putIfAbsent("Content-Type", "application/json");
        merged.putIfAbsent("Accept", "application/json");
        HttpResponse<String> response = send(
                requestBuilder(url, merged)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Json.parse(response.body());
    }

    public static Json postForm(String url, Map<String, String> form, Map<String, String> headers)
            throws IOException, InterruptedException {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
        merged.putIfAbsent("Accept", "application/json");
        HttpResponse<String> response = send(
                requestBuilder(url, merged)
                        .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Json.parse(response.body());
    }

    /**
     * POST that returns the raw response even on a non-2xx status.
     * The OAuth device-code endpoint signals "still pending" with HTTP 400 and a
     * JSON error body, so that case must not be thrown away.
     */
    public static HttpResponse<String> postFormRaw(String url, Map<String, String> form, Map<String, String> headers)
            throws IOException, InterruptedException {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
        merged.putIfAbsent("Accept", "application/json");
        return CLIENT.send(
                requestBuilder(url, merged)
                        .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------- authentication requests

    /**
     * POST a form to an authentication endpoint.
     *
     * <p>HTTPS is enforced, redirects are refused, and the request is not
     * retried: replaying a token exchange or a device-code poll is not
     * idempotent, and a retry of a refresh-token grant against a server that
     * rotates refresh tokens can invalidate the account.
     */
    public static HttpResponse<String> authPostForm(String url, Map<String, String> form,
                                                    Map<String, String> headers)
            throws IOException, InterruptedException {
        requireHttps(url);
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
        merged.putIfAbsent("Accept", "application/json");
        return AUTH_CLIENT.send(
                requestBuilder(url, merged)
                        .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** POST JSON to an authentication endpoint. Throws on any non-2xx status. */
    public static Json authPostJson(String url, Json body, Map<String, String> headers)
            throws IOException, InterruptedException {
        requireHttps(url);
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putIfAbsent("Content-Type", "application/json");
        merged.putIfAbsent("Accept", "application/json");
        HttpResponse<String> response = AUTH_CLIENT.send(
                requestBuilder(url, merged)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new HttpStatusException(response.statusCode(), url, response.body());
        }
        return Json.parse(response.body());
    }

    /** GET from an authentication endpoint, with a bearer token. */
    public static Json authGetJson(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        requireHttps(url);
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putIfAbsent("Accept", "application/json");
        HttpResponse<String> response = AUTH_CLIENT.send(
                requestBuilder(url, merged).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new HttpStatusException(response.statusCode(), url, response.body());
        }
        return Json.parse(response.body());
    }

    public static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- plumbing

    private static HttpRequest.Builder requestBuilder(String url, Map<String, String> headers) {
        URI uri = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT);
        headers.forEach(builder::header);
        // Added second, and only where the caller said nothing: HttpRequest.header
        // appends rather than replaces, and a request carrying the same
        // credential header twice is rejected by some services.
        hostHeadersFor(uri).forEach((name, value) -> {
            if (!headers.containsKey(name)) {
                builder.header(name, value);
            }
        });
        return builder;
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<T> response = CLIENT.send(request, handler);
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return response;
                }
                String body = response.body() instanceof String s ? s : "";
                if (isRetryableStatus(status) && attempt < MAX_ATTEMPTS) {
                    sleepBackoff(attempt, response);
                    continue;
                }
                throw new HttpStatusException(status, request.uri().toString(), body);
            } catch (HttpStatusException e) {
                throw e;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                sleepBackoff(attempt, null);
            }
        }
        throw new IOException("request to " + request.uri() + " failed after " + MAX_ATTEMPTS + " attempts",
                lastFailure);
    }

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static void sleepBackoff(int attempt, HttpResponse<?> response) throws InterruptedException {
        long millis = 400L * (1L << (attempt - 1));
        if (response != null) {
            // Honour Retry-After when the server sends one (Modrinth and CurseForge do).
            long retryAfter = response.headers().firstValue("Retry-After")
                    .map(v -> {
                        try {
                            return Long.parseLong(v.trim()) * 1000L;
                        } catch (NumberFormatException e) {
                            return 0L;
                        }
                    }).orElse(0L);
            millis = Math.max(millis, retryAfter);
        }
        Thread.sleep(Math.min(millis, 30_000L));
    }
}
