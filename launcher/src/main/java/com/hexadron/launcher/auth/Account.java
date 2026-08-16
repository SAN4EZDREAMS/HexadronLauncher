package com.hexadron.launcher.auth;

import com.hexadron.launcher.json.Json;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A player identity the launcher can start the game with.
 *
 * @param type            offline or Microsoft
 * @param username        in-game name
 * @param uuid            player UUID
 * @param accessToken     Minecraft services token; {@code "0"} for offline play
 * @param refreshToken    Microsoft refresh token, null for offline accounts
 * @param expiresAt       epoch millis at which {@code accessToken} stops working
 * @param xuid            Xbox user id, passed to the game as {@code --xuid}
 */
public record Account(AccountType type, String username, UUID uuid, String accessToken,
                      String refreshToken, long expiresAt, String xuid) {

    public enum AccountType {
        /** No authentication. Cannot join premium servers and has no skin. */
        OFFLINE("legacy"),
        /** Microsoft account, authenticated through Xbox Live. */
        MICROSOFT("msa");

        private final String userType;

        AccountType(String userType) {
            this.userType = userType;
        }

        /** Value passed to the game as {@code --userType}. */
        public String userType() {
            return userType;
        }
    }

    /** Stable key for the account store. */
    public String id() {
        return type.name().toLowerCase(java.util.Locale.ROOT) + ":" + uuid;
    }

    public boolean isOffline() {
        return type == AccountType.OFFLINE;
    }

    /**
     * True when a Microsoft token needs refreshing. A two-minute margin keeps a
     * launch from starting with a token that expires mid-handshake.
     */
    public boolean needsRefresh() {
        if (type == AccountType.OFFLINE) {
            return false;
        }
        return System.currentTimeMillis() > expiresAt - 120_000L;
    }

    /**
     * Builds an offline account.
     *
     * <p>The UUID is derived exactly as a Minecraft server in offline mode
     * derives it - {@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)} -
     * so worlds and player data stay attached to the same name across
     * launchers, and across a later switch to a real server.
     */
    public static Account offline(String username) {
        String name = username == null || username.isBlank() ? "Player" : username.trim();
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new Account(AccountType.OFFLINE, name, uuid, "0", null, Long.MAX_VALUE, "0");
    }

    public Json toJson() {
        Json json = Json.object()
                .put("type", type.name())
                .put("username", username)
                .put("uuid", uuid.toString())
                .put("expiresAt", expiresAt);
        if (accessToken != null) {
            json.put("accessToken", accessToken);
        }
        if (refreshToken != null) {
            json.put("refreshToken", refreshToken);
        }
        if (xuid != null) {
            json.put("xuid", xuid);
        }
        return json;
    }

    public static Account fromJson(Json json) {
        AccountType type = AccountType.valueOf(json.get("type").asString(AccountType.OFFLINE.name()));
        String username = json.get("username").asString("Player");
        String rawUuid = json.get("uuid").asString(null);
        UUID uuid = rawUuid != null ? UUID.fromString(rawUuid) : Account.offline(username).uuid();
        return new Account(
                type,
                username,
                uuid,
                json.get("accessToken").asString("0"),
                json.get("refreshToken").asString(null),
                json.get("expiresAt").asLong(0),
                json.get("xuid").asString("0"));
    }

    /** Parses the undashed UUID form the Minecraft profile API returns. */
    public static UUID parseUndashedUuid(String undashed) {
        if (undashed.length() == 36) {
            return UUID.fromString(undashed);
        }
        if (undashed.length() != 32) {
            throw new IllegalArgumentException("not a Minecraft profile UUID: " + undashed);
        }
        String dashed = undashed.substring(0, 8) + "-"
                + undashed.substring(8, 12) + "-"
                + undashed.substring(12, 16) + "-"
                + undashed.substring(16, 20) + "-"
                + undashed.substring(20);
        return UUID.fromString(dashed);
    }

    @Override
    public String toString() {
        return username + " (" + type.name().toLowerCase(java.util.Locale.ROOT) + ")";
    }
}
