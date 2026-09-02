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

package com.hexadron.launcher.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Describes a built image so that the next update can skip most of it.
 *
 * <h2>Run by the build, not by the launcher</h2>
 *
 * <p>The release workflow runs this from the image it has just built, using that
 * image's own runtime. It writes two things into an output folder: the manifest
 * that {@link DeltaUpdate} reads, and one list of file names per part, which the
 * workflow hands to {@code tar} or {@code 7z} to make the part archives.
 *
 * <p>The packing itself is left to those two on purpose. They are what already
 * makes the full archive in that workflow, they keep symbolic links and the
 * executable bit on every system, and a second archive writer written here would
 * be a second thing that can be wrong about a macOS bundle.
 *
 * <h2>Why the parts are these parts</h2>
 *
 * <p>They are split by how often they change, which is the only split that
 * saves anything:
 *
 * <ul>
 *   <li><b>runtime</b> - the bundled Java. Changes when the JDK does, a few
 *       times a year. The largest part by far.</li>
 *   <li><b>libs</b> - JavaFX and anything else the launcher did not write.
 *       Changes when a dependency is bumped.</li>
 *   <li><b>app</b> - the launcher's own jar and its configuration. Changes every
 *       single build, and is a couple of megabytes.</li>
 *   <li><b>base</b> - the executable, the icon, whatever else is at the root.</li>
 * </ul>
 *
 * <p>A nightly therefore normally downloads <em>app</em> and <em>base</em>, and
 * takes the other two off the disk.
 */
public final class ManifestTool {

    /**
     * Where each system keeps the runtime and the jars inside its image.
     *
     * <p>Kept as data rather than as three branches of an if, because it is the
     * same table as {@link UpdateInstall}'s and the two have to agree.
     */
    private static final Map<String, String[]> LAYOUT = Map.of(
            // label -> { runtime prefix, app prefix }
            "windows", new String[]{"runtime/", "app/"},
            "linux", new String[]{"lib/runtime/", "lib/app/"},
            "macos", new String[]{"Contents/runtime/", "Contents/app/"});

    /**
     * What counts as the launcher's own, inside the app folder.
     *
     * <p>Everything else there is a dependency. Prefixes rather than exact names
     * because the version is in the file name, and the version is what changes.
     */
    private static final List<String> OURS = List.of("launcher-", "hexadron");

    private ManifestTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("usage: ManifestTool <image> <os-label> <version> <out-dir>"
                    + " [full-archive] [full-archive-name]");
            System.exit(2);
            return;
        }
        Path image = Path.of(args[0]).toAbsolutePath().normalize();
        String label = args[1].toLowerCase(Locale.ROOT);
        String version = args[2];
        Path out = Path.of(args[3]).toAbsolutePath().normalize();

        String[] layout = LAYOUT.get(label);
        if (layout == null) {
            System.err.println("unknown system: " + label);
            System.exit(2);
            return;
        }
        if (!Files.isDirectory(image)) {
            System.err.println("no image at " + image);
            System.exit(2);
            return;
        }
        Files.createDirectories(out);

        Map<String, String> assets = new LinkedHashMap<>();
        for (String part : List.of("runtime", "libs", "app", "base")) {
            assets.put(part, partAsset(label, part));
        }

        ImageManifest manifest = ImageManifest.scan(image, label, version, assets,
                path -> partOf(path, layout));

        if (args.length >= 6) {
            manifest = manifest.withArchive(args[5], Path.of(args[4]));
        }

        Path manifestFile = out.resolve("HexadronLauncher-" + label + ImageManifest.SUFFIX);
        manifest.toJson().write(manifestFile);
        System.out.println("wrote " + manifestFile.getFileName() + ", "
                + manifest.files().size() + " files");

        for (String part : assets.keySet()) {
            List<String> paths = new ArrayList<>();
            for (ImageManifest.Entry entry : manifest.filesOf(part)) {
                paths.add(entry.path());
            }
            // Named after the file it is to become, so that the workflow packs
            // it without knowing anything about how parts are named. One place
            // decides that, and it is this one.
            Path list = out.resolve(assets.get(part) + ".list");
            Files.writeString(list, String.join("\n", paths) + (paths.isEmpty() ? "" : "\n"),
                    StandardCharsets.UTF_8);
            System.out.println(part + ": " + paths.size() + " files, "
                    + manifest.unpackedSizeOf(part) / (1024 * 1024) + " MB");
        }
    }

    /**
     * What each system is called in a <em>part's</em> name.
     *
     * <p>Deliberately not "windows", "linux" or "macos". Launchers that are
     * already installed choose the build to download by its name, and the
     * versions before parts existed did it by looking for a name with their
     * system in it - so a part called {@code HexadronLauncher-windows-app.zip}
     * is picked up by them as the whole build, and the update dies with "the
     * downloaded archive holds no application image". It cannot be fixed in
     * those launchers, because they are already on people's machines. It can be
     * fixed here, once, by never publishing a part under a name their rule can
     * match.
     */
    private static final Map<String, String> PART_TOKEN = Map.of(
            "windows", "win",
            "linux", "lnx",
            "macos", "darwin");

    /** The published name of a part's archive, per system. */
    public static String partAsset(String label, String part) {
        String extension = "windows".equals(label) ? ".zip" : ".tar.gz";
        String token = PART_TOKEN.getOrDefault(label, label);
        return "HexadronLauncher-parts-" + token + "-" + part + extension;
    }

    /**
     * Which part a file belongs to.
     *
     * <p>By where it is in the image and, inside the app folder, by whether the
     * name is one of ours. A dependency that were misfiled as ours would still
     * update correctly - it would just be downloaded more often than it needs to
     * be, which is the right way round for a rule based on a name.
     */
    public static String partOf(String path, String[] layout) {
        String runtime = layout[0];
        String app = layout[1];
        if (path.startsWith(runtime)) {
            return "runtime";
        }
        if (path.startsWith(app)) {
            String name = path.substring(app.length()).toLowerCase(Locale.ROOT);
            for (String ours : OURS) {
                if (name.startsWith(ours)) {
                    return "app";
                }
            }
            return name.endsWith(".jar") ? "libs" : "app";
        }
        return "base";
    }
}
