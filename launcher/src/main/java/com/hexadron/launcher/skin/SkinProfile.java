/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
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

import java.util.Locale;

/**
 * What one account wears, and where the game is told to look for it.
 *
 * <h2>Two sources, because they answer different questions</h2>
 *
 * <p>{@link Source#LOCAL} serves the pictures from this machine, through a
 * skin service the launcher runs on the loopback interface for the length of
 * the session. It needs no account anywhere, works with no internet, and the
 * player sees their own skin in single player and on every server. What it
 * cannot do is show that skin to anybody else: another player's client asks
 * <em>their</em> session service for your textures, and their session service
 * has never heard of this machine.
 *
 * <p>{@link Source#REMOTE} points the same mechanism at a Yggdrasil service on
 * the network - LittleSkin, Ely.by, a self-hosted Blessing Skin. That costs an
 * account on that service and a working connection, and buys the thing the
 * local source cannot: on a server whose own launcher-side configuration points
 * at the same service, every player's client resolves every other player's
 * textures there, so the skins are mutual.
 *
 * <p>Neither makes a skin appear on a plain offline-mode server whose operator
 * has done nothing. That is not a limitation of this launcher: on such a server
 * nobody's profile carries textures, because the server is the thing that puts
 * them there.
 */
public record SkinProfile(Source source, String skin, String cape, Model model, String service) {

    /** Where the game is pointed for textures. */
    public enum Source {
        /** The launcher's own service, on the loopback interface. */
        LOCAL,
        /** A Yggdrasil service on the network. */
        REMOTE;

        public static Source parse(String value) {
            return value != null && value.equalsIgnoreCase("remote") ? REMOTE : LOCAL;
        }

        public String stored() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Which arm width the skin is drawn with.
     *
     * <p>Part of the texture metadata rather than of the picture: the same
     * 64x64 file renders as Steve or as Alex depending on this, and getting it
     * wrong is the classic "my arms look wrong" report.
     */
    public enum Model {
        CLASSIC("classic"),
        SLIM("slim");

        private final String id;

        Model(String id) {
            this.id = id;
        }

        /** The value Mojang's texture metadata and the skin upload API use. */
        public String id() {
            return id;
        }

        public static Model parse(String value) {
            return value != null && value.equalsIgnoreCase("slim") ? SLIM : CLASSIC;
        }
    }

    /** An account that wears nothing yet. */
    public static SkinProfile empty() {
        return new SkinProfile(Source.LOCAL, null, null, Model.CLASSIC, "");
    }

    public boolean hasSkin() {
        return skin != null && !skin.isBlank();
    }

    public boolean hasCape() {
        return cape != null && !cape.isBlank();
    }

    /** True when there is anything at all to serve. */
    public boolean isEmpty() {
        return !hasSkin() && !hasCape();
    }

    /**
     * True when this profile needs the game to be launched with the skin
     * service attached.
     *
     * <p>A remote service is worth attaching even with no local pictures: the
     * pictures live on the service, not here.
     */
    public boolean needsService() {
        return source == Source.REMOTE ? !service().isBlank() : !isEmpty();
    }

    public SkinProfile withSkin(String value) {
        return new SkinProfile(source, value, cape, model, service);
    }

    public SkinProfile withCape(String value) {
        return new SkinProfile(source, skin, value, model, service);
    }

    public SkinProfile withModel(Model value) {
        return new SkinProfile(source, skin, cape, value, service);
    }

    public SkinProfile withSource(Source value) {
        return new SkinProfile(value, skin, cape, model, service);
    }

    public SkinProfile withService(String value) {
        return new SkinProfile(source, skin, cape, model, value == null ? "" : value.trim());
    }

    public Json toJson() {
        Json json = Json.object()
                .put("source", source.stored())
                .put("model", model.id());
        if (hasSkin()) {
            json.put("skin", skin);
        }
        if (hasCape()) {
            json.put("cape", cape);
        }
        if (!service.isBlank()) {
            json.put("service", service);
        }
        return json;
    }

    public static SkinProfile fromJson(Json json) {
        return new SkinProfile(
                Source.parse(json.get("source").asString(null)),
                json.get("skin").asString(null),
                json.get("cape").asString(null),
                Model.parse(json.get("model").asString(null)),
                json.get("service").asString(""));
    }
}
