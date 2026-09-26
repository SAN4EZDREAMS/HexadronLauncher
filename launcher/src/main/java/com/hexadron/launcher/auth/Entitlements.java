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

package com.hexadron.launcher.auth;

import com.hexadron.launcher.json.Json;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * What {@code /entitlements/mcstore} says an account has.
 *
 * <p>The old check was "is the list empty". That is too coarse: a Bedrock
 * Edition licence, Minecraft Dungeons or Legends each put items in the list,
 * so an account with no Java Edition at all passed the ownership check and then
 * failed one step later with a message about a missing username. The item names
 * are what tell the cases apart.
 *
 * <p>Item names, as documented on the Minecraft Wiki ("Microsoft
 * authentication"):
 * <ul>
 *   <li>{@code product_minecraft}, {@code game_minecraft} - Java Edition</li>
 *   <li>{@code product_game_pass_pc}, {@code product_game_pass_ultimate} -
 *       access through PC Game Pass or Game Pass Ultimate</li>
 *   <li>{@code product_minecraft_bedrock}, {@code game_minecraft_bedrock},
 *       {@code product_dungeons}, {@code product_legends} and similar -
 *       other Minecraft games, which do not start Java Edition</li>
 * </ul>
 *
 * <p>Only the names are read. Each item also carries a signed JWT, and that is
 * never kept, logged or compared.
 *
 * @param names     every item name in the response, sorted, lower case
 * @param java      Java Edition is listed
 * @param gamePass  a Game Pass product is listed
 * @param otherOnly the list is not empty but holds neither of the above
 */
public record Entitlements(Set<String> names, boolean java, boolean gamePass, boolean otherOnly) {

    private static final Set<String> JAVA = Set.of("product_minecraft", "game_minecraft");
    private static final Set<String> GAME_PASS =
            Set.of("product_game_pass_pc", "product_game_pass_ultimate");

    /** A name is logged only if it looks like one; anything else is dropped. */
    private static final String NAME_PATTERN = "[a-z0-9_]{1,64}";

    public Entitlements {
        names = Collections.unmodifiableSet(new TreeSet<>(names));
    }

    /** Reads the {@code items[].name} values of an entitlements response. */
    public static Entitlements from(Json response) {
        Set<String> names = new TreeSet<>();
        Json items = response == null ? Json.MISSING : response.get("items");
        if (items.isArray()) {
            for (Json item : items.elements()) {
                String name = item.get("name").asString("");
                String normalised = name.trim().toLowerCase(Locale.ROOT);
                if (normalised.matches(NAME_PATTERN)) {
                    names.add(normalised);
                }
            }
        }
        return of(names);
    }

    /** Classifies a set of item names. */
    public static Entitlements of(Set<String> names) {
        boolean java = names.stream().anyMatch(JAVA::contains);
        boolean gamePass = names.stream().anyMatch(GAME_PASS::contains);
        return new Entitlements(names, java, gamePass, !names.isEmpty() && !java && !gamePass);
    }

    /** Java Edition can be played on this account right now: bought, or through Game Pass. */
    public boolean grantsJava() {
        return java || gamePass;
    }

    /** The names for the log, or "none". The names are fixed product IDs, not secrets. */
    public String describe() {
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
