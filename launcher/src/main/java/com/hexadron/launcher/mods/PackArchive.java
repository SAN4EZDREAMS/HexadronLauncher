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

import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A modpack file, read.
 *
 * <h2>Two formats, one answer</h2>
 *
 * <p>A modpack is not a mod. It is a statement about a whole instance - this
 * Minecraft version, this loader at this version, these files, and these
 * configs on top - and both platforms express it as a zip with a manifest
 * inside. The manifests have nothing in common but their purpose:
 *
 * <ul>
 *   <li>Modrinth's {@code .mrpack} holds {@code modrinth.index.json}, in which
 *       every file is a path plus a list of URLs plus a SHA-1, and the loader is
 *       a dependency such as {@code fabric-loader: 0.16.9}. Nothing has to be
 *       looked up: the pack carries the addresses;</li>
 *   <li>CurseForge's zip holds {@code manifest.json}, in which every file is a
 *       pair of numbers - project id and file id - and the loader is a string
 *       such as {@code fabric-0.16.9}. Nothing can be downloaded until each pair
 *       has been resolved through the API, which needs a key.</li>
 * </ul>
 *
 * <p>So this class ends the difference. It reads either manifest and produces one
 * record, in which a file is either an address ({@link Download}) or a pair to
 * resolve ({@link ProjectFile}), and the version and loader have already been
 * turned into the launcher's own {@link LoaderType} and a version string. What
 * installs it does not have to know which platform it came from.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>No downloading and no extraction. This is a reader: it opens the archive,
 * reads one entry out of it and closes it. Deciding what to do with the answer -
 * which is where a user gets asked whether to make a new instance - belongs to
 * the caller, and a reader that started downloading two hundred files would have
 * taken that decision on their behalf.
 *
 * @param format          which manifest it turned out to be
 * @param name            the pack's own name, for the instance it becomes
 * @param version         the pack's version, or null
 * @param author          who published it, or null
 * @param summary         one line, or null
 * @param minecraftVersion the Minecraft version the pack is for
 * @param loader          the loader it needs
 * @param loaderVersion   that loader's version, or null when the pack does not
 *                        pin one and the newest will do
 * @param downloads       files the pack gives addresses for
 * @param projectFiles    files the pack names as ids, to be resolved
 * @param overrides       folders inside the archive whose contents are copied
 *                        over the instance, in the order they must be applied
 * @param archive         the file this was read from
 */
public record PackArchive(Format format, String name, String version, String author, String summary,
                          String minecraftVersion, LoaderType loader, String loaderVersion,
                          List<Download> downloads, List<ProjectFile> projectFiles,
                          List<String> overrides, Path archive) {

    /** Which manifest a pack file carries. */
    public enum Format {

        /** Modrinth: {@code modrinth.index.json}, files as URLs. */
        MRPACK("Modrinth"),

        /** CurseForge: {@code manifest.json}, files as project and file ids. */
        CURSEFORGE("CurseForge");

        private final String displayName;

        Format(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** The Modrinth manifest, by the name it has inside a {@code .mrpack}. */
    public static final String MRPACK_INDEX = "modrinth.index.json";

    /** The CurseForge manifest, by the name it has inside the zip. */
    public static final String CURSEFORGE_MANIFEST = "manifest.json";

    public PackArchive {
        downloads = List.copyOf(downloads);
        projectFiles = List.copyOf(projectFiles);
        overrides = List.copyOf(overrides);
    }

    /**
     * One file the pack gives an address for.
     *
     * @param path      where it goes, relative to the instance folder. Always
     *                  {@code /}-separated, as the manifest writes it
     * @param urls      every address the pack offers, in its own order
     * @param sha1      digest, when published
     * @param size      bytes, or -1
     * @param optional  true for a file the pack marks as not required, which is
     *                  installed anyway but must not fail the install
     */
    public record Download(String path, List<String> urls, String sha1, long size,
                           boolean optional) {

        public Download {
            urls = List.copyOf(urls);
        }
    }

    /**
     * One file the pack names as a pair of CurseForge ids.
     *
     * @param required false for a pack's own optional extras
     */
    public record ProjectFile(String projectId, String fileId, boolean required) {
    }

    /** How many files this pack installs, whichever way they are named. */
    public int fileCount() {
        return downloads.size() + projectFiles.size();
    }

    /** True when installing this needs a CurseForge key. */
    public boolean needsCurseForge() {
        return !projectFiles.isEmpty();
    }

    /** A name for the instance this pack becomes. */
    public String instanceName() {
        if (name == null || name.isBlank()) {
            String fileName = archive.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            return dot > 0 ? fileName.substring(0, dot) : fileName;
        }
        return name;
    }

    // ---------------------------------------------------------------- reading

    /**
     * Reads a pack file.
     *
     * <p>The format is decided by what is inside rather than by the extension. A
     * CurseForge pack is a plain {@code .zip} and so is a great many other
     * things, and a {@code .mrpack} renamed on the way through a browser is still
     * a Modrinth pack.
     *
     * @throws IOException when the file is not a zip, or is a zip with neither
     *                     manifest in it - which is the honest answer for the
     *                     resource pack somebody dropped on this window by
     *                     mistake
     */
    public static PackArchive read(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry modrinth = zip.getEntry(MRPACK_INDEX);
            if (modrinth != null) {
                return readModrinth(zip, modrinth, file);
            }
            ZipEntry curseForge = zip.getEntry(CURSEFORGE_MANIFEST);
            if (curseForge != null) {
                return readCurseForge(zip, curseForge, file);
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("not a modpack file: " + file.getFileName()
                    + " is not a zip archive", e);
        }
        throw new IOException("not a modpack file: " + file.getFileName()
                + " holds neither " + MRPACK_INDEX + " nor " + CURSEFORGE_MANIFEST);
    }

    /** True when this file is a pack, without complaining when it is not. */
    public static boolean looksLikePack(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.getEntry(MRPACK_INDEX) != null
                    || zip.getEntry(CURSEFORGE_MANIFEST) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static PackArchive readModrinth(ZipFile zip, ZipEntry entry, Path file)
            throws IOException {

        Json index = parse(zip, entry);
        Json dependencies = index.get("dependencies");
        String minecraft = dependencies.get("minecraft").asString(null);
        if (minecraft == null || minecraft.isBlank()) {
            throw new IOException(MRPACK_INDEX + " does not say which Minecraft version it is for");
        }

        LoaderType loader = LoaderType.VANILLA;
        String loaderVersion = null;
        // The keys are the loader's own name plus "-loader" for the two that
        // publish a loader separately from the mod system.
        for (String key : List.of("fabric-loader", "quilt-loader", "forge", "neoforge")) {
            String value = dependencies.get(key).asString(null);
            if (value == null || value.isBlank()) {
                continue;
            }
            loader = switch (key) {
                case "fabric-loader" -> LoaderType.FABRIC;
                case "quilt-loader" -> LoaderType.QUILT;
                case "forge" -> LoaderType.FORGE;
                default -> LoaderType.NEOFORGE;
            };
            loaderVersion = value.trim();
            break;
        }

        List<Download> downloads = new ArrayList<>();
        for (Json fileJson : index.get("files").elements()) {
            String path = fileJson.get("path").asString(null);
            if (path == null || path.isBlank()) {
                continue;
            }
            // A pack carries both sides of a multiplayer install. "unsupported"
            // on the client side means the file is for the server and installing
            // it is not an omission - it is the pack being followed.
            String clientSupport = fileJson.get("env").get("client").asString("required");
            if ("unsupported".equalsIgnoreCase(clientSupport)) {
                continue;
            }
            List<String> urls = new ArrayList<>();
            for (Json url : fileJson.get("downloads").elements()) {
                String value = url.asString(null);
                if (value != null && !value.isBlank()) {
                    urls.add(value.trim());
                }
            }
            if (urls.isEmpty()) {
                continue;
            }
            downloads.add(new Download(path, urls,
                    fileJson.get("hashes").get("sha1").asString(null),
                    fileJson.get("fileSize").asLong(-1),
                    "optional".equalsIgnoreCase(clientSupport)));
        }

        // client-overrides last: a pack that ships both means the client ones to
        // win, which is the whole reason it separates them.
        List<String> overrides = new ArrayList<>();
        if (hasDirectory(zip, "overrides/")) {
            overrides.add("overrides");
        }
        if (hasDirectory(zip, "client-overrides/")) {
            overrides.add("client-overrides");
        }

        return new PackArchive(Format.MRPACK,
                index.get("name").asString(null),
                index.get("versionId").asString(null),
                null,
                index.get("summary").asString(null),
                minecraft.trim(), loader, loaderVersion,
                downloads, List.of(), overrides, file);
    }

    private static PackArchive readCurseForge(ZipFile zip, ZipEntry entry, Path file)
            throws IOException {

        Json manifest = parse(zip, entry);
        Json minecraftJson = manifest.get("minecraft");
        String minecraft = minecraftJson.get("version").asString(null);
        if (minecraft == null || minecraft.isBlank()) {
            throw new IOException(CURSEFORGE_MANIFEST
                    + " does not say which Minecraft version it is for");
        }

        LoaderType loader = LoaderType.VANILLA;
        String loaderVersion = null;
        // A pack may list several loaders and mark one primary. The primary one
        // is the pack's own answer; the first is the fallback for the packs that
        // mark none.
        String chosen = null;
        for (Json modLoader : minecraftJson.get("modLoaders").elements()) {
            String id = modLoader.get("id").asString(null);
            if (id == null || id.isBlank()) {
                continue;
            }
            if (chosen == null || modLoader.get("primary").asBool(false)) {
                chosen = id.trim();
            }
            if (modLoader.get("primary").asBool(false)) {
                break;
            }
        }
        if (chosen != null) {
            // "fabric-0.16.9", "forge-47.2.0", "neoforge-21.0.100".
            int dash = chosen.indexOf('-');
            String name = dash < 0 ? chosen : chosen.substring(0, dash);
            loaderVersion = dash < 0 || dash + 1 >= chosen.length()
                    ? null : chosen.substring(dash + 1);
            loader = switch (name.toLowerCase(Locale.ROOT)) {
                case "fabric" -> LoaderType.FABRIC;
                case "quilt" -> LoaderType.QUILT;
                case "forge" -> LoaderType.FORGE;
                case "neoforge" -> LoaderType.NEOFORGE;
                default -> LoaderType.VANILLA;
            };
        }

        List<ProjectFile> files = new ArrayList<>();
        for (Json fileJson : manifest.get("files").elements()) {
            long projectId = fileJson.get("projectID").asLong(0);
            long fileId = fileJson.get("fileID").asLong(0);
            if (projectId == 0 || fileId == 0) {
                continue;
            }
            files.add(new ProjectFile(String.valueOf(projectId), String.valueOf(fileId),
                    fileJson.get("required").asBool(true)));
        }

        String overridesDir = manifest.get("overrides").asString("overrides");
        List<String> overrides = hasDirectory(zip, overridesDir + "/")
                ? List.of(overridesDir) : List.of();

        return new PackArchive(Format.CURSEFORGE,
                manifest.get("name").asString(null),
                manifest.get("version").asString(null),
                manifest.get("author").asString(null),
                null,
                minecraft.trim(), loader, loaderVersion,
                List.of(), files, overrides, file);
    }

    /**
     * Whether the archive has anything under this prefix.
     *
     * <p>Not {@code getEntry("overrides/")}: a zip is not obliged to carry
     * entries for its directories, and plenty written by build tools do not. The
     * question that matters is whether anything is filed under the name.
     */
    private static boolean hasDirectory(ZipFile zip, String prefix) {
        return zip.stream().anyMatch(entry -> entry.getName().startsWith(prefix)
                && !entry.getName().equals(prefix));
    }

    private static Json parse(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException(entry.getName() + " could not be read: " + e.getMessage(), e);
        }
    }
}
