package com.hexadron.launcher.auth;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.util.Redactor;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Microsoft account sign-in.
 *
 * <p>The chain is fixed by Mojang and every step is mandatory:
 * <ol>
 *   <li>Microsoft identity platform - an MSA access token plus a refresh token</li>
 *   <li>Xbox Live user authenticate - MSA token to an XBL token plus a user hash</li>
 *   <li>XSTS authorize - XBL token to a token scoped to Minecraft services</li>
 *   <li>{@code login_with_xbox} - XSTS token to a Minecraft access token</li>
 *   <li>entitlement check, then profile lookup for name and UUID</li>
 * </ol>
 *
 * <h2>Two ways to do step 1, and why the order matters</h2>
 *
 * <p><b>Authorization code with PKCE, through the system browser</b>
 * ({@link #signInWithBrowser}) is the default. This is what RFC 8252 - "OAuth
 * 2.0 for Native Apps" - prescribes for a desktop application: the real browser,
 * a loopback redirect, and a PKCE challenge so that an intercepted authorization
 * code cannot be redeemed. The user sees Microsoft's own address bar and
 * Microsoft's own certificate, and any password manager or passkey they already
 * trust works. The launcher never sees the password, never draws the login form,
 * and never loads a web view.
 *
 * <p><b>The device code grant</b> ({@link #requestDeviceCode} /
 * {@link #completeDeviceCodeFlow}) is kept, but as a fallback for a machine with
 * no usable browser and for sign-in from a phone. It is not the default any
 * more, and that is a deliberate reversal. Device code was designed for
 * televisions and printers; on a machine that has a browser it strictly weakens
 * the flow, because the user is trained to type a code they were given into a
 * Microsoft page without any binding between the code and the application that
 * produced it. That is the mechanism the "Gas Auth" campaign used against
 * Minecraft players - malicious applications spoofing launcher names to harvest
 * Xbox Live consent - and it is why Mojang introduced manual review of new
 * Azure applications in June 2023 in the first place. Microsoft's own security
 * guidance now treats device code as a phishing vector.
 *
 * <h2>What this class will not do</h2>
 *
 * <ul>
 *   <li>No embedded web view. RFC 8252 §8.12: native apps MUST NOT use embedded
 *       user-agents. A window the launcher draws is a window the launcher can
 *       read the keystrokes of, and the user has no way to tell one from the
 *       real thing.</li>
 *   <li>No client secret. A secret shipped in a jar is not a secret; the
 *       application is registered as a public client and PKCE takes its place.</li>
 *   <li>No response body is ever concatenated into an exception message. The
 *       Xbox and Minecraft endpoints echo tokens back inside their error and
 *       success payloads, and exception messages end up in the log pane, in
 *       stack traces, and in whatever the user pastes into a support channel.</li>
 * </ul>
 *
 * <p><b>Prerequisite.</b> {@code clientId} must be an Azure application
 * registration that Mojang has approved for Minecraft authentication. Without
 * approval, step 4 returns HTTP 403 no matter how correct the rest is. Apply at
 * {@code https://aka.ms/mce-reviewappid}; until then use an offline account.
 */
public final class MicrosoftAuth {

    private static final String AUTHORIZE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_ENTITLEMENTS_URL =
            "https://api.minecraftservices.com/entitlements/mcstore";
    private static final String MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";

    /** Where a user revokes this launcher's access to their Microsoft account. */
    public static final String CONSENT_MANAGEMENT_URL = "https://account.live.com/consent/Manage";

    /**
     * Minimum scope: Xbox sign-in plus a refresh token so the user signs in once.
     *
     * <p>Nothing else is requested. {@code profile}, {@code openid} and
     * {@code email} would all be granted without complaint and none of them are
     * needed to start Minecraft, so asking for them would be collecting consent
     * for data the launcher has no use for.
     */
    private static final String SCOPE = "XboxLive.signin offline_access";

    /** How long the loopback listener waits for the user to finish in the browser. */
    private static final int BROWSER_TIMEOUT_SECONDS = 300;

    private final String clientId;

    public MicrosoftAuth(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("an Azure application client ID is required");
        }
        this.clientId = clientId.trim();
    }

    /** Raised when the chain fails in a way the user can act on. */
    public static final class AuthException extends IOException {
        public AuthException(String message) {
            super(Redactor.scrub(message));
        }

        public AuthException(String message, Throwable cause) {
            super(Redactor.scrub(message), cause);
        }
    }

    // ------------------------------------------------ browser flow (preferred)

    /**
     * Signs in through the system browser using authorization code + PKCE.
     *
     * @param openBrowser hands the authorization URL to the platform. The caller
     *                    owns this because the launcher core must not depend on
     *                    AWT or JavaFX; the UI passes a Desktop.browse call and
     *                    the CLI prints the URL.
     */
    public Account signInWithBrowser(Consumer<URI> openBrowser, Progress progress)
            throws IOException, InterruptedException {

        Pkce pkce = Pkce.generate();
        try (LoopbackRedirectServer listener = LoopbackRedirectServer.start(pkce)) {
            String redirectUri = listener.redirectUri();
            URI authorizationUri = URI.create(buildAuthorizeUrl(pkce, redirectUri));

            progress.stage("Waiting for sign-in in your browser");
            progress.log("Opened Microsoft sign-in on 127.0.0.1:%d", listener.port());
            openBrowser.accept(authorizationUri);

            // The cancellation flag is handed down rather than checked after the
            // fact: checking it afterwards is checking it once the wait is
            // already over, which is exactly too late to be useful.
            String code = listener.awaitCode(BROWSER_TIMEOUT_SECONDS, progress::isCancelled);
            if (progress.isCancelled()) {
                throw new AuthException("sign-in cancelled");
            }

            progress.stage("Exchanging the authorization code");
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", clientId);
            form.put("grant_type", "authorization_code");
            form.put("code", code);
            form.put("redirect_uri", redirectUri);
            form.put("scope", SCOPE);
            form.put("code_verifier", pkce.verifier());

            HttpResponse<String> response = Http.authPostForm(TOKEN_URL, form, Map.of());
            Json body = Json.parse(response.body());
            if (response.statusCode() / 100 != 2) {
                throw new AuthException("Microsoft rejected the sign-in: " + errorOf(body));
            }
            return fromMicrosoftToken(
                    body.get("access_token").asString(null),
                    body.get("refresh_token").asString(null),
                    progress);
        }
    }

    /**
     * The authorization URL.
     *
     * <p>Public so the self-check can assert on its parameters without a
     * network call: "is PKCE actually being sent" is exactly the kind of thing
     * that silently regresses and is only noticed when it is too late to matter.
     */
    public String buildAuthorizeUrl(Pkce pkce, String redirectUri) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", clientId);
        query.put("response_type", "code");
        query.put("redirect_uri", redirectUri);
        query.put("response_mode", "query");
        query.put("scope", SCOPE);
        query.put("state", pkce.state());
        query.put("code_challenge", pkce.challenge());
        query.put("code_challenge_method", Pkce.CHALLENGE_METHOD);
        // Always show the account chooser. Silent reuse of whatever account the
        // browser happens to be signed into is how a shared machine ends up with
        // someone else's Minecraft account saved in the launcher.
        query.put("prompt", "select_account");

        StringBuilder url = new StringBuilder(AUTHORIZE_URL).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) {
                url.append('&');
            }
            first = false;
            url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    // ------------------------------------------------ device code (fallback)

    /** What the user must be shown to complete sign-in on another device. */
    public record DeviceCodePrompt(String userCode, String verificationUri, String message,
                                   String deviceCode, int intervalSeconds, int expiresInSeconds) {
        /** The device code is a credential; keep it out of logs and dialogs. */
        @Override
        public String toString() {
            return "DeviceCodePrompt[userCode=" + userCode + ", uri=" + verificationUri
                    + ", deviceCode=<redacted>]";
        }
    }

    /** Requests a device code. The user then enters {@code userCode} at {@code verificationUri}. */
    public DeviceCodePrompt requestDeviceCode() throws IOException, InterruptedException {
        HttpResponse<String> raw = Http.authPostForm(DEVICE_CODE_URL,
                Map.of("client_id", clientId, "scope", SCOPE), Map.of());
        Json response = Json.parse(raw.body());

        String deviceCode = response.get("device_code").asString(null);
        if (deviceCode == null) {
            throw new AuthException("Microsoft did not return a device code: " + errorOf(response));
        }
        Redactor.register(deviceCode);
        return new DeviceCodePrompt(
                response.get("user_code").asString(""),
                response.get("verification_uri").asString("https://microsoft.com/link"),
                response.get("message").asString(""),
                deviceCode,
                response.get("interval").asInt(5),
                response.get("expires_in").asInt(900));
    }

    /**
     * Polls until the user completes sign-in, then runs the rest of the chain.
     *
     * @param onPoll called before each poll so the UI can show a countdown
     */
    public Account completeDeviceCodeFlow(DeviceCodePrompt prompt, Consumer<Integer> onPoll,
                                          Progress progress) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + prompt.expiresInSeconds() * 1000L;
        int intervalSeconds = prompt.intervalSeconds();

        while (System.currentTimeMillis() < deadline) {
            if (progress.isCancelled()) {
                throw new AuthException("sign-in cancelled");
            }
            int remaining = (int) ((deadline - System.currentTimeMillis()) / 1000L);
            onPoll.accept(remaining);

            Thread.sleep(intervalSeconds * 1000L);

            HttpResponse<String> response = Http.authPostForm(TOKEN_URL,
                    Map.of("client_id", clientId,
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                            "device_code", prompt.deviceCode()),
                    Map.of());

            Json body = Json.parse(response.body());
            if (response.statusCode() / 100 == 2) {
                Redactor.forget(prompt.deviceCode());
                return fromMicrosoftToken(
                        body.get("access_token").asString(null),
                        body.get("refresh_token").asString(null),
                        progress);
            }

            // A pending sign-in is reported as HTTP 400 with an error code; only
            // authorization_pending and slow_down mean "keep waiting".
            String error = body.get("error").asString("");
            switch (error) {
                case "authorization_pending" -> {
                    // keep polling
                }
                case "slow_down" -> intervalSeconds += 5;
                case "expired_token" -> throw new AuthException("the sign-in code expired, start again");
                case "authorization_declined" -> throw new AuthException("sign-in was declined");
                default -> throw new AuthException("Microsoft sign-in failed: " + errorOf(body));
            }
        }
        throw new AuthException("the sign-in code expired, start again");
    }

    // ---------------------------------------------------------------- refresh

    /** Exchanges a stored refresh token for a fresh Minecraft session. */
    public Account refresh(Account account, Progress progress) throws IOException, InterruptedException {
        String refreshToken = account.refreshToken();
        if (refreshToken == null) {
            throw new AuthException("account " + account.username()
                    + " has no stored refresh token, sign in again");
        }
        HttpResponse<String> raw = Http.authPostForm(TOKEN_URL,
                Map.of("client_id", clientId,
                        "grant_type", "refresh_token",
                        "refresh_token", refreshToken,
                        "scope", SCOPE),
                Map.of());
        Json response = Json.parse(raw.body());

        String accessToken = response.get("access_token").asString(null);
        if (accessToken == null) {
            throw new AuthException("Microsoft refused the refresh token: " + errorOf(response)
                    + " - sign in again");
        }
        // Microsoft rotates the refresh token on most grants. The old one stops
        // working, so it is unregistered from the redactor to keep that set small.
        String rotated = response.get("refresh_token").asString(refreshToken);
        if (!rotated.equals(refreshToken)) {
            Redactor.forget(refreshToken);
        }
        return fromMicrosoftToken(accessToken, rotated, progress);
    }

    // ---------------------------------------------------------------- steps 2-5

    /** Runs Xbox Live, XSTS, Minecraft login, entitlement and profile in order. */
    public Account fromMicrosoftToken(String microsoftAccessToken, String refreshToken, Progress progress)
            throws IOException, InterruptedException {

        if (microsoftAccessToken == null) {
            throw new AuthException("Microsoft returned no access token to exchange");
        }
        Redactor.register(microsoftAccessToken);
        Redactor.register(refreshToken);

        progress.stage("Authenticating with Xbox Live");
        Json xblRequest = Json.object()
                .put("Properties", Json.object()
                        .put("AuthMethod", "RPS")
                        .put("SiteName", "user.auth.xboxlive.com")
                        .put("RpsTicket", "d=" + microsoftAccessToken))
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT");

        Json xbl = Http.authPostJson(XBL_AUTH_URL, xblRequest, Map.of());
        String xblToken = xbl.get("Token").asString(null);
        String userHash = xbl.get("DisplayClaims").get("xui").get(0).get("uhs").asString(null);
        if (xblToken == null || userHash == null) {
            throw new AuthException("Xbox Live did not return a usable token");
        }
        Redactor.register(xblToken);

        progress.stage("Authorising with XSTS");
        Json xstsRequest = Json.object()
                .put("Properties", Json.object()
                        .put("SandboxId", "RETAIL")
                        .put("UserTokens", Json.array().add(Json.of(xblToken))))
                .put("RelyingParty", "rp://api.minecraftservices.com/")
                .put("TokenType", "JWT");

        Json xsts;
        try {
            xsts = Http.authPostJson(XSTS_AUTH_URL, xstsRequest, Map.of());
        } catch (Http.HttpStatusException e) {
            throw new AuthException(describeXstsFailure(e), e);
        }
        String xstsToken = xsts.get("Token").asString(null);
        if (xstsToken == null) {
            throw new AuthException("XSTS did not return a usable token");
        }
        Redactor.register(xstsToken);

        progress.stage("Signing in to Minecraft services");
        Json mcLogin;
        try {
            mcLogin = Http.authPostJson(MC_LOGIN_URL,
                    Json.object().put("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken),
                    Map.of());
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() == 403) {
                throw new AuthException("""
                        Minecraft services rejected this application (HTTP 403).

                        This almost always means the Azure application ID in use has not been \
                        approved by Mojang for Minecraft authentication. The OAuth chain itself \
                        succeeded up to this point. Apply at https://aka.ms/mce-reviewappid.""", e);
            }
            throw new AuthException("Minecraft services rejected the sign-in (HTTP "
                    + e.statusCode() + ")", e);
        }

        String mcAccessToken = mcLogin.get("access_token").asString(null);
        if (mcAccessToken == null) {
            throw new AuthException("Minecraft services returned no access token");
        }
        Redactor.register(mcAccessToken);
        long expiresAt = System.currentTimeMillis() + mcLogin.get("expires_in").asLong(86_400) * 1000L;

        progress.stage("Checking game ownership");
        Json entitlements = Http.authGetJson(MC_ENTITLEMENTS_URL,
                Map.of("Authorization", "Bearer " + mcAccessToken));
        if (entitlements.get("items").size() == 0) {
            throw new AuthException("""
                    This Microsoft account does not own Minecraft: Java Edition.

                    Game Pass accounts must launch the game once from the official launcher \
                    before third-party sign-in works.""");
        }

        progress.stage("Fetching profile");
        Json profile;
        try {
            profile = Http.authGetJson(MC_PROFILE_URL, Map.of("Authorization", "Bearer " + mcAccessToken));
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() == 404) {
                throw new AuthException("This account owns the game but has no Minecraft profile yet. "
                        + "Create a username in the official launcher first.", e);
            }
            throw e;
        }

        String name = profile.get("name").asString(null);
        String rawUuid = profile.get("id").asString(null);
        if (name == null || rawUuid == null) {
            throw new AuthException("the Minecraft profile response was incomplete");
        }

        UUID uuid = Account.parseUndashedUuid(rawUuid);
        String xuid = xsts.get("DisplayClaims").get("xui").get(0).get("xid").asString("0");

        progress.log("Signed in as %s", name);
        return new Account(Account.AccountType.MICROSOFT, name, uuid, mcAccessToken,
                refreshToken, expiresAt, xuid);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The safe part of an OAuth error response.
     *
     * <p>Only {@code error} and {@code error_description} are read. Dumping the
     * whole body would be simpler and would, on the token endpoint, print a
     * refresh token the moment anything unexpected happened.
     */
    private static String errorOf(Json body) {
        String code = body.get("error").asString("");
        String description = body.get("error_description").asString("");
        if (!description.isBlank()) {
            // Microsoft's descriptions carry a correlation ID and a timestamp,
            // which is what a support request actually needs.
            return Redactor.scrub(description);
        }
        return code.isBlank() ? "no error detail was returned" : code;
    }

    /** Turns XSTS's numeric XErr codes into something a user can act on. */
    private static String describeXstsFailure(Http.HttpStatusException e) {
        long xErr;
        try {
            xErr = Json.parse(e.body()).get("XErr").asLong(0);
        } catch (RuntimeException parseFailure) {
            return "XSTS authorisation failed (HTTP " + e.statusCode() + ")";
        }
        // XErr values exceed the int range, so these must stay long comparisons.
        if (xErr == 2148916233L) {
            return "This Microsoft account has no Xbox profile. Create one at xbox.com, then try again.";
        }
        if (xErr == 2148916238L) {
            return "This account is registered as a child and must be added to a Microsoft Family group first.";
        }
        if (xErr == 2148916234L) {
            return "This account must accept the Xbox Live terms of service first.";
        }
        if (xErr == 2148916235L) {
            return "Xbox Live is not available in this account's country or region.";
        }
        if (xErr == 2148916236L || xErr == 2148916237L) {
            return "This account requires additional verification (adult verification / proof of age).";
        }
        if (xErr == 2148916227L) {
            return "This Xbox account has been permanently banned for a terms of service violation.";
        }
        return "XSTS authorisation failed (XErr " + xErr + ")";
    }
}
