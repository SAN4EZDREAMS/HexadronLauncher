package com.hexadron.launcher.auth;

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Microsoft account sign-in, using the OAuth 2.0 device authorization grant.
 *
 * <p>The chain is fixed by Mojang and every step is mandatory:
 * <ol>
 *   <li>Microsoft identity platform - device code, then poll for an MSA token</li>
 *   <li>Xbox Live user authenticate - MSA token to an XBL token plus a user hash</li>
 *   <li>XSTS authorize - XBL token to a token scoped to Minecraft services</li>
 *   <li>{@code login_with_xbox} - XSTS token to a Minecraft access token</li>
 *   <li>entitlement check, then profile lookup for name and UUID</li>
 * </ol>
 *
 * <p>Device code flow is used rather than an embedded browser deliberately: it
 * needs no redirect URI, no embedded web view, and no client secret, so the
 * launcher never handles the user's Microsoft password.
 *
 * <p><b>Prerequisite.</b> {@code clientId} must be an Azure application
 * registration that Mojang has approved for Minecraft authentication. Without
 * approval, step 4 returns HTTP 403 no matter how correct the rest is. Apply
 * through Mojang's help centre; until then use an offline account.
 */
public final class MicrosoftAuth {

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

    /** Minimum scope: Xbox sign-in plus a refresh token so the user signs in once. */
    private static final String SCOPE = "XboxLive.signin offline_access";

    private final String clientId;

    public MicrosoftAuth(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("an Azure application client ID is required");
        }
        this.clientId = clientId;
    }

    /** What the user must be shown to complete sign-in on another device. */
    public record DeviceCodePrompt(String userCode, String verificationUri, String message,
                                   String deviceCode, int intervalSeconds, int expiresInSeconds) {
    }

    /** Raised when the chain fails in a way the user can act on. */
    public static final class AuthException extends IOException {
        public AuthException(String message) {
            super(message);
        }

        public AuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ---------------------------------------------------------------- step 1

    /** Requests a device code. The user then enters {@code userCode} at {@code verificationUri}. */
    public DeviceCodePrompt requestDeviceCode() throws IOException, InterruptedException {
        Json response = Http.postForm(DEVICE_CODE_URL,
                Map.of("client_id", clientId, "scope", SCOPE),
                Map.of());

        String deviceCode = response.get("device_code").asString(null);
        if (deviceCode == null) {
            throw new AuthException("Microsoft did not return a device code: " + response);
        }
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

            HttpResponse<String> response = Http.postFormRaw(TOKEN_URL,
                    Map.of("client_id", clientId,
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                            "device_code", prompt.deviceCode()),
                    Map.of());

            Json body = Json.parse(response.body());
            if (response.statusCode() / 100 == 2) {
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
                default -> throw new AuthException("Microsoft sign-in failed: "
                        + body.get("error_description").asString(error.isEmpty() ? response.body() : error));
            }
        }
        throw new AuthException("the sign-in code expired, start again");
    }

    // ---------------------------------------------------------------- refresh

    /** Exchanges a stored refresh token for a fresh Minecraft session. */
    public Account refresh(Account account, Progress progress) throws IOException, InterruptedException {
        if (account.refreshToken() == null) {
            throw new AuthException("account " + account.username() + " has no refresh token, sign in again");
        }
        Json response = Http.postForm(TOKEN_URL,
                Map.of("client_id", clientId,
                        "grant_type", "refresh_token",
                        "refresh_token", account.refreshToken(),
                        "scope", SCOPE),
                Map.of());

        String accessToken = response.get("access_token").asString(null);
        if (accessToken == null) {
            throw new AuthException("Microsoft refused the refresh token: "
                    + response.get("error_description").asString(response.toString()));
        }
        return fromMicrosoftToken(accessToken,
                response.get("refresh_token").asString(account.refreshToken()),
                progress);
    }

    // ---------------------------------------------------------------- steps 2-5

    /** Runs Xbox Live, XSTS, Minecraft login, entitlement and profile in order. */
    public Account fromMicrosoftToken(String microsoftAccessToken, String refreshToken, Progress progress)
            throws IOException, InterruptedException {

        if (microsoftAccessToken == null) {
            throw new AuthException("no Microsoft access token to exchange");
        }

        progress.stage("Authenticating with Xbox Live");
        Json xblRequest = Json.object()
                .put("Properties", Json.object()
                        .put("AuthMethod", "RPS")
                        .put("SiteName", "user.auth.xboxlive.com")
                        .put("RpsTicket", "d=" + microsoftAccessToken))
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT");

        Json xbl = Http.postJson(XBL_AUTH_URL, xblRequest, Map.of());
        String xblToken = xbl.get("Token").asString(null);
        String userHash = xbl.get("DisplayClaims").get("xui").get(0).get("uhs").asString(null);
        if (xblToken == null || userHash == null) {
            throw new AuthException("Xbox Live did not return a token: " + xbl);
        }

        progress.stage("Authorising with XSTS");
        Json xstsRequest = Json.object()
                .put("Properties", Json.object()
                        .put("SandboxId", "RETAIL")
                        .put("UserTokens", Json.array().add(Json.of(xblToken))))
                .put("RelyingParty", "rp://api.minecraftservices.com/")
                .put("TokenType", "JWT");

        Json xsts;
        try {
            xsts = Http.postJson(XSTS_AUTH_URL, xstsRequest, Map.of());
        } catch (Http.HttpStatusException e) {
            throw new AuthException(describeXstsFailure(e), e);
        }
        String xstsToken = xsts.get("Token").asString(null);
        if (xstsToken == null) {
            throw new AuthException("XSTS did not return a token: " + xsts);
        }

        progress.stage("Signing in to Minecraft services");
        Json mcLogin;
        try {
            mcLogin = Http.postJson(MC_LOGIN_URL,
                    Json.object().put("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken),
                    Map.of());
        } catch (Http.HttpStatusException e) {
            if (e.statusCode() == 403) {
                throw new AuthException("""
                        Minecraft services rejected this application (HTTP 403).

                        This almost always means the Azure application ID in use has not been \
                        approved by Mojang for Minecraft authentication. The OAuth chain itself \
                        succeeded up to this point.""", e);
            }
            throw new AuthException("Minecraft services rejected the sign-in: " + e.getMessage(), e);
        }

        String mcAccessToken = mcLogin.get("access_token").asString(null);
        if (mcAccessToken == null) {
            throw new AuthException("Minecraft services returned no access token: " + mcLogin);
        }
        long expiresAt = System.currentTimeMillis() + mcLogin.get("expires_in").asLong(86_400) * 1000L;

        progress.stage("Checking game ownership");
        Json entitlements = Http.getJson(MC_ENTITLEMENTS_URL,
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
            profile = Http.getJson(MC_PROFILE_URL, Map.of("Authorization", "Bearer " + mcAccessToken));
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
            throw new AuthException("Minecraft profile response was incomplete: " + profile);
        }

        UUID uuid = Account.parseUndashedUuid(rawUuid);
        String xuid = xsts.get("DisplayClaims").get("xui").get(0).get("xid").asString("0");

        progress.log("Signed in as %s", name);
        return new Account(Account.AccountType.MICROSOFT, name, uuid, mcAccessToken,
                refreshToken, expiresAt, xuid);
    }

    /** Turns XSTS's numeric XErr codes into something a user can act on. */
    private static String describeXstsFailure(Http.HttpStatusException e) {
        String body = e.body();
        long xErr;
        try {
            xErr = Json.parse(body).get("XErr").asLong(0);
        } catch (RuntimeException parseFailure) {
            return "XSTS authorisation failed: " + e.getMessage();
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
        return "XSTS authorisation failed (XErr " + xErr + "): " + e.getMessage();
    }
}
