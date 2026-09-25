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

package com.hexadron.launcher.share;

import com.hexadron.launcher.mods.ContentKind;

import java.util.List;
import java.util.Locale;

/**
 * The shape of a build file: one profile, packed so that another copy of the
 * launcher can make the same instance.
 *
 * <h2>What is in one</h2>
 *
 * <p>A zip with a {@code .hexbuild} extension:
 *
 * <pre>
 * hexadron-build.json   the manifest: Minecraft version, loader, profile
 *                       settings, and every file that can be downloaded again,
 *                       with its address and SHA-1
 * files/&lt;path&gt;          files carried inside the build, at the path they take
 *                       in the instance: configs, worlds, and - only when the
 *                       player said yes - their own mods and packs
 * icon/&lt;name&gt;           the profile's own picture, when it has one
 * </pre>
 *
 * <p>A file that can be downloaded is named, not carried. That keeps a build of
 * two hundred mods at a few hundred kilobytes, and it means the importing
 * launcher gets each file from the place its author published it, checked
 * against the hash this launcher had.
 *
 * <p>A file nothing is known about - a jar built by the player, a resource pack
 * from a forum, a data pack in a world - can only travel inside the build. Those
 * are the "custom" files, and they are carried only when asked for, because a
 * build is something people share and a player's own work is theirs to decide
 * about.
 *
 * <h2>What is never in one</h2>
 *
 * <p>No account, no session, no Java path, no wrapper command. A Java path is a
 * path on one machine. A wrapper command is a program the launcher runs, and a
 * file from somebody else that sets one is a file that runs a program.
 */
public final class BuildFormat {

    /** The file extension, with its dot. */
    public static final String EXTENSION = ".hexbuild";

    /** The manifest's name inside the archive. */
    public static final String MANIFEST = "hexadron-build.json";

    /** Where carried files sit in the archive. */
    public static final String FILES = "files/";

    /** Where the profile picture sits in the archive. */
    public static final String ICON = "icon/";

    /** The value of the manifest's {@code format} member. */
    public static final String FORMAT_ID = "hexadron-build";

    /** The newest manifest version this build reads and the one it writes. */
    public static final int FORMAT_VERSION = 1;

    /** A profile picture larger than this is not carried. */
    public static final long ICON_LIMIT = 4L * 1024 * 1024;

    /**
     * What "settings" means: the mods' own configuration and the game's.
     *
     * <p>The same set every modpack format ships as its overrides. {@code
     * options.txt} carries key bindings, video settings and the list of enabled
     * resource packs, which is why a build without it opens with every pack
     * switched off.
     */
    public static final List<String> SETTINGS = List.of(
            "config", "defaultconfigs", "kubejs", "scripts",
            "options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat");

    /** The content kinds that have one folder in the instance. */
    public static final List<ContentKind> FOLDER_KINDS =
            List.of(ContentKind.MOD, ContentKind.RESOURCEPACK, ContentKind.SHADER);

    /** Files a world holds that must not be copied into another one. */
    static final List<String> WORLD_SKIPPED = List.of("session.lock");

    private BuildFormat() {
    }

    /** True when {@code path} is {@code prefix} or lies under it. Both {@code /}-separated. */
    public static boolean within(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * True for the launcher's own record files.
     *
     * <p>Not carried: the importing launcher writes its own from the manifest,
     * and a record copied over the top of that one would claim files the
     * import did not put there.
     */
    public static boolean isBookkeeping(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).startsWith(".hexadron-");
    }

    /**
     * True when {@code folder} is where {@code kind} keeps its files.
     *
     * <p>Mods, resource packs and shaders have one folder each. Data packs have
     * one per world, {@code saves/<world>/datapacks}.
     */
    public static boolean isFolderOf(ContentKind kind, String folder) {
        if (kind == null || folder == null) {
            return false;
        }
        if (kind == ContentKind.DATAPACK) {
            String[] parts = folder.split("/");
            return parts.length == 3 && parts[0].equals("saves")
                    && !parts[1].isBlank() && parts[2].equals("datapacks");
        }
        return kind.hasInstanceFolder() && kind.instanceFolder().equals(folder);
    }

    /**
     * True for an address the importer will download from.
     *
     * <p>A build file is made by another player and carries its own SHA-1, so
     * the digest proves only that the download is the file the build meant.
     * The addresses are therefore held to the hosts the launcher itself
     * records them from: Modrinth's and CurseForge's file hosts, plus the code
     * hosts a {@code .mrpack} may use. Anything else is refused by name.
     */
    public static boolean isFetchable(String url) {
        if (com.hexadron.launcher.mods.PackArchive.isAllowedDownload(url)) {
            return true;
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && host != null
                    && host.toLowerCase(Locale.ROOT).endsWith(".forgecdn.net");
        } catch (java.net.URISyntaxException | NullPointerException e) {
            return false;
        }
    }

    /** The last segment of a {@code /}-separated path. */
    static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
