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

package com.hexadron.launcher.mods;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which program in an instance can load a shader pack.
 *
 * <h2>Why this has to be asked at all</h2>
 *
 * <p>A shader pack is not loaded by Minecraft and not loaded by Fabric or Forge.
 * It is loaded by one of three mods - Iris, OptiFine or Canvas - and a
 * {@code shaderpacks} folder on an instance that has none of them is a folder
 * the game never opens. So a launcher that installs a shader pack without
 * checking has told the player "installed" about a file that will do nothing,
 * which is the same failure the data pack section was built to avoid.
 *
 * <p>It also decides <em>which file</em> to install. Modrinth publishes a shader
 * project's versions per loader - {@code iris}, {@code optifine},
 * {@code canvas} - and most packs publish for two of the three. Asking for
 * neither returns whichever was uploaded last, which may be for the program this
 * instance does not have.
 *
 * <h2>Why it is a name test confirmed by a mod id</h2>
 *
 * <p>The cheap signal is the file name, and it is nearly always right: these
 * four jars are called what they are. It is not enough on its own - "Canvas
 * Blocks" is a mod that is not Canvas - so anything the name catches is opened
 * and its own declared mod id is read, and only a jar that agrees counts.
 *
 * <p>The exception is OptiFine, which ships no {@code fabric.mod.json} and no
 * {@code mods.toml} at all: nothing can be read out of it, so for that one the
 * name is the answer. Being wrong there costs a warning that is not shown, not
 * a file written anywhere.
 */
public final class ShaderLoaders {

    /**
     * A program that loads shader packs, and how it is recognised.
     *
     * <p>{@code tag} is Modrinth's own loader name for the program, which is
     * what a request for a file has to carry. {@code modIds} are the ids the
     * jars declare - Iris on Forge is published as Oculus, a different mod with
     * the same pipeline, and a pack built for Iris is the pack it loads.
     */
    public enum ShaderLoader {

        IRIS("iris", "Iris", Set.of("iris", "oculus"), Set.of("iris", "oculus")),
        OPTIFINE("optifine", "OptiFine", Set.of("optifine"), Set.of("optifine")),
        CANVAS("canvas", "Canvas", Set.of("canvas"), Set.of("canvas"));

        private final String tag;
        private final String displayName;
        private final Set<String> modIds;
        private final Set<String> nameTokens;

        ShaderLoader(String tag, String displayName, Set<String> modIds, Set<String> nameTokens) {
            this.tag = tag;
            this.displayName = displayName;
            this.modIds = Set.copyOf(modIds);
            this.nameTokens = Set.copyOf(nameTokens);
        }

        /** Modrinth's loader name for this program. */
        public String tag() {
            return tag;
        }

        /** What to call it in a sentence a player reads. */
        public String displayName() {
            return displayName;
        }

        /** True when a jar declaring this mod id is this program. */
        public boolean claimsModId(String modId) {
            return modId != null && modIds.contains(modId.trim().toLowerCase(Locale.ROOT));
        }

        /** True when a file or project name looks like this program. */
        public boolean looksLikeName(String text) {
            if (text == null) {
                return false;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (String token : nameTokens) {
                if (lower.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        /** The one whose Modrinth tag this is, if it is one of ours. */
        public static Optional<ShaderLoader> byTag(String tag) {
            if (tag == null) {
                return Optional.empty();
            }
            String wanted = tag.trim().toLowerCase(Locale.ROOT);
            for (ShaderLoader loader : values()) {
                if (loader.tag.equals(wanted)) {
                    return Optional.of(loader);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What to type into the mods search to find one.
     *
     * <p>Iris rather than all three, because it is the one a player on Fabric or
     * NeoForge can install from either platform in one click - OptiFine is not
     * published on them at all, and Canvas is a renderer with consequences of
     * its own. The section offers the search rather than performing the install,
     * so the choice stays the player's.
     */
    public static final String SEARCH_TERM = "Iris";

    private ShaderLoaders() {
    }

    /**
     * The shader loaders installed and switched on in this mods folder.
     *
     * <p>Reads the list a folder scan already produced rather than the folder
     * again: this is asked every time the shaders panel is refreshed, and a
     * second walk of three hundred jars to answer it would be paid for on every
     * refresh.
     *
     * <p>Switched-off jars do not count. A mod renamed to {@code .disabled} is
     * not going to load a shader, and reporting it as present would leave the
     * player with a pack, no warning and nothing on screen.
     */
    public static List<ShaderLoader> detect(List<ModEntry> mods) {
        if (mods == null || mods.isEmpty()) {
            return List.of();
        }
        Set<ShaderLoader> found = new LinkedHashSet<>();
        for (ModEntry mod : mods) {
            if (!mod.enabled()) {
                continue;
            }
            for (ShaderLoader loader : ShaderLoader.values()) {
                if (found.contains(loader)) {
                    continue;
                }
                if (!loader.looksLikeName(mod.jarName()) && !loader.looksLikeName(mod.title())) {
                    continue;
                }
                if (confirms(loader, mod)) {
                    found.add(loader);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Whether the jar itself agrees that it is this program.
     *
     * <p>A jar that declares a mod id has to declare the right one. A jar that
     * declares nothing readable - OptiFine, and a jar packed in a dialect this
     * launcher does not parse - is taken at the word of its name, because the
     * alternative is refusing to notice the loader that is actually there.
     */
    private static boolean confirms(ShaderLoader loader, ModEntry mod) {
        Optional<LocalModInfo> info = LocalModInfo.read(mod.path());
        if (info.isEmpty() || info.get().modId() == null || info.get().modId().isBlank()) {
            return true;
        }
        return loader.claimsModId(info.get().modId());
    }

    /** The Modrinth loader tags to ask a shader project for. */
    public static List<String> tagsOf(List<ShaderLoader> loaders) {
        List<String> tags = new ArrayList<>();
        for (ShaderLoader loader : loaders) {
            tags.add(loader.tag());
        }
        return List.copyOf(tags);
    }

    /** The installed loaders as one line, for a sentence that names them. */
    public static String describe(List<ShaderLoader> loaders) {
        List<String> names = new ArrayList<>();
        for (ShaderLoader loader : loaders) {
            names.add(loader.displayName());
        }
        return String.join(", ", names);
    }
}
