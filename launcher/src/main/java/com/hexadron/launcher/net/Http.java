package com.hexadron.launcher.net;

import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private Http() {
    }

    public static HttpClient client() {
        return CLIENT;
    }

    /** A non-2xx response that should surface to the caller rather than be retried. */
    public static final class HttpStatusException extends IOException {
        private final int statusCode;
        private final String uri;
        private final String body;

        public HttpStatusException(int statusCode, String uri, String body) {
            super("HTTP " + statusCode + " for " + uri
                    + (body == null || body.isBlank() ? "" : ": " + truncate(body)));
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT);
        headers.forEach(builder::header);
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
