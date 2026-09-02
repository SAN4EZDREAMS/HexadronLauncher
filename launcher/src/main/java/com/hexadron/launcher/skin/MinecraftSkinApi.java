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

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skins and capes for a Microsoft account, through Mojang's own profile API.
 *
 * <h2>Why this is the easy half</h2>
 *
 * <p>For a licensed account there is nothing to work around. The skin is
 * uploaded to Mojang, Mojang signs it into the profile, and every server hands
 * that profile to every client. The skin is visible to everybody, everywhere,
 * with no agent, no service and no cooperation from the server - which is
 * exactly what an offline account cannot have, and worth saying plainly so the
 * difference is not mistaken for a fault in the launcher.
 *
 * <h2>Capes are chosen, not uploaded</h2>
 *
 * <p>A cape belongs to an account because it was granted to it. The API can
 * make one of the granted capes active, or none of them, and that is the whole
 * of what is possible; there is no endpoint for a cape of one's own, and a
 * launcher that offered a file picker for it would be promising something
 * Mojang does not implement.
 *
 * <h2>The token</h2>
 *
 * <p>Every call carries the account's Minecraft token, the same one the launch
 * uses. It is sent over HTTPS to Mojang and to nowhere else, it is never
 * written to the log - {@code Redactor} runs over error bodies - and these
 * calls happen only when the user presses a button in the account editor.
 */
public final class MinecraftSkinApi {

    private static final String PROFILE = "https://api.minecraftservices.com/minecraft/profile";

    private MinecraftSkinApi() {
    }

    /** One cape the account owns. */
    public record Cape(String id, String name, boolean active) {
        @Override
        public String toString() {
            return name == null || name.isBlank() ? id : name;
        }
    }

    /** What Mojang currently holds for this account. */
    public record Profile(String skinUrl, SkinProfile.Model model, List<Cape> capes) {
    }

    /** Reads the account's current skin and the capes it owns. */
    public static Profile read(Account account) throws IOException, InterruptedException {
        Json json = Http.authGetJson(PROFILE, bearer(account));

        String skinUrl = null;
        SkinProfile.Model model = SkinProfile.Model.CLASSIC;
        for (Json skin : json.get("skins").elements()) {
            if (!"ACTIVE".equalsIgnoreCase(skin.get("state").asString(""))) {
                continue;
            }
            skinUrl = skin.get("url").asString(null);
            model = SkinProfile.Model.parse(skin.get("variant").asString("classic"));
        }

        List<Cape> capes = new ArrayList<>();
        for (Json cape : json.get("capes").elements()) {
            capes.add(new Cape(
                    cape.get("id").asString(""),
                    cape.get("alias").asString(cape.get("id").asString("")),
                    "ACTIVE".equalsIgnoreCase(cape.get("state").asString(""))));
        }
        return new Profile(skinUrl, model, List.copyOf(capes));
    }

    /**
     * Uploads a skin.
     *
     * <p>Multipart, because that is what the endpoint takes. The boundary is
     * random rather than fixed so that a file whose bytes happen to contain a
     * guessable boundary cannot truncate the request - PNG is binary, and a
     * fixed boundary in a binary body is a real collision rather than a
     * theoretical one.
     */
    public static void uploadSkin(Account account, Path png, SkinProfile.Model model)
            throws IOException, InterruptedException {

        int[] size = PngSize.read(png);
        if (size == null || size[0] != 64 || (size[1] != 64 && size[1] != 32)) {
            throw new IOException("a skin is a 64x64 or 64x32 PNG; Mojang will refuse anything else");
        }

        String boundary = "hexadron" + java.util.UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, model.id(), png);

        Http.authSend("POST", PROFILE + "/skins", body, Map.of(
                "Authorization", "Bearer " + account.accessToken(),
                "Content-Type", "multipart/form-data; boundary=" + boundary));
    }

    /** Makes one of the account's capes active. */
    public static void wearCape(Account account, String capeId) throws IOException, InterruptedException {
        Http.authSend("PUT", PROFILE + "/capes/active",
                Json.object().put("capeId", capeId).toString().getBytes(StandardCharsets.UTF_8),
                Map.of("Authorization", "Bearer " + account.accessToken(),
                        "Content-Type", "application/json"));
    }

    /** Takes the cape off. */
    public static void removeCape(Account account) throws IOException, InterruptedException {
        Http.authSend("DELETE", PROFILE + "/capes/active", null,
                Map.of("Authorization", "Bearer " + account.accessToken()));
    }

    private static byte[] multipart(String boundary, String variant, Path png) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"variant\"\r\n\r\n");
        write(out, variant + "\r\n");
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n");
        write(out, "Content-Type: image/png\r\n\r\n");
        out.write(Files.readAllBytes(png));
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> bearer(Account account) {
        return Map.of("Authorization", "Bearer " + account.accessToken());
    }
}
