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

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.net.DownloadTask;
import com.hexadron.launcher.net.Downloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Installs and removes the packs that live in a folder of the instance's own:
 * resource packs and shader packs.
 *
 * <h2>Why this is not {@link ModInstaller}</h2>
 *
 * <p>A mod is resolved against a mod loader, lands in {@code mods}, and its
 * dependency graph is other mods in that same folder. Neither of those is true
 * here. A resource pack is loaded by the game and a shader pack by Iris or
 * OptiFine, so the loader a file is asked for is not the profile's; and what a
 * pack requires is usually another pack of the same kind, which belongs in the
 * same folder rather than in {@code mods}.
 *
 * <h2>What it does about requirements</h2>
 *
 * <p>Modrinth lets a version name required dependencies, and for these two kinds
 * they come in two shapes. A dependency that is <em>the same kind</em> - a base
 * pack an add-on is painted on top of, a shader's companion pack - is fetched
 * into the same folder and recorded as a dependency, so removing it warns and
 * removing the pack that needed it takes it along.
 *
 * <p>A dependency that is <em>not</em> the same kind is nearly always the program
 * that loads the pack: a shader naming Iris, a resource pack naming a mod that
 * adds the models it retextures. Those are not installed from here. They go in
 * {@code mods}, they are chosen against the profile's own loader, and a launcher
 * that quietly put a jar in the mods folder because somebody clicked a shader
 * would be installing something they never asked for. They are named in the
 * result instead, and the panel says so.
 *
 * <h2>What is refused</h2>
 *
 * <p>A file that is not this kind of pack. The platform can answer a request for
 * a resource pack with the project's mod build, and writing that into
 * {@code resourcepacks} produces a file nothing loads, a list that does not show
 * it, and a record that says it is installed - a launcher contradicting itself
 * in three places at once. The same mistake was found and fixed for data packs;
 * this is the same guard.
 */
public final class PackInstaller {

    /** How far a chain of same-kind requirements is followed. */
    private static final int MAX_DEPTH = 3;

    private final ContentKind kind;
    private final PackScan scan;
    private final Downloader downloader;
    private final Map<ModProvider.Source, ModProvider> providers = new LinkedHashMap<>();

    public PackInstaller(ContentKind kind, Downloader downloader, ModProvider... providers) {
        this.kind = kind;
        this.scan = PackScan.of(kind);
        this.downloader = downloader;
        for (ModProvider provider : providers) {
            this.providers.put(provider.source(), provider);
        }
    }

    public ContentKind kind() {
        return kind;
    }

    /**
     * What came of an install.
     *
     * @param installed    the pack itself, plus any same-kind pack it required
     * @param requirements things it needs that are not this kind and were
     *                     therefore not installed - the shader loader, a mod -
     *                     as lines ready to show
     * @param manual       what could not be fetched at all: an author who has
     *                     turned off third-party downloads on CurseForge, or a
     *                     required pack with no build for this version
     */
    public record Result(List<ModFile> installed, List<String> requirements, List<String> manual) {

        public Result {
            installed = List.copyOf(installed);
            requirements = List.copyOf(requirements);
            manual = List.copyOf(manual);
        }

        public boolean isClean() {
            return requirements.isEmpty() && manual.isEmpty();
        }

        /** Every line worth putting in front of the user, in one list. */
        public List<String> notes() {
            List<String> lines = new ArrayList<>(requirements);
            lines.addAll(manual);
            return List.copyOf(lines);
        }
    }

    /**
     * Installs one pack, and any pack of the same kind it requires.
     *
     * @param minecraftVersion the instance's version. Used for resource packs,
     *                         which state a pack format per era; ignored for
     *                         shaders, which do not - see
     *                         {@link ContentKind#SHADER}
     * @param loaderTags       the loaders to ask a file for: the shader loaders
     *                         this instance actually has, or empty to let the
     *                         kind decide. See {@link ShaderLoaders}
     * @param packsDir         the instance's folder for this kind, created if it
     *                         is not there yet
     */
    public Result install(ModProvider.ProjectCard chosen, String minecraftVersion,
                          List<String> loaderTags, Path packsDir, Progress progress)
            throws IOException, InterruptedException {

        ModProvider provider = providers.get(chosen.source());
        if (provider == null || !provider.isAvailable()) {
            throw new IOException(chosen.source().displayName() + " is not configured");
        }

        Files.createDirectories(packsDir);
        ModLibrary library = scan.libraryOf(packsDir);

        List<String> requirements = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        List<ModFile> installed = new ArrayList<>();

        Set<String> visited = new LinkedHashSet<>();
        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(chosen, chosen.projectId(), 0));

        while (!queue.isEmpty()) {
            Pending pending = queue.poll();
            String key = InstalledMod.keyOf(chosen.source(), pending.projectId());
            if (!visited.add(key)) {
                continue;
            }
            if (pending.depth() > MAX_DEPTH) {
                requirements.add(pending.label() + " (the chain of required packs is too deep)");
                continue;
            }

            // A dependency arrives as an id and nothing else. The card is what
            // puts a name, a logo and a link on its row instead of "eXts2L7r",
            // and it is also how the note below can name the thing the player
            // has to go and install. One request per dependency, which is the
            // same trade the mod installer makes and for the same reason.
            Pending named = pending.card() != null
                    ? pending
                    : pending.with(cardFor(provider, pending.projectId()));

            Optional<ModFile> found = provider.resolveFile(
                    kind, named.projectId(), minecraftVersion, LoaderType.VANILLA, loaderTags);
            if (found.isEmpty()) {
                if (named.depth() == 0) {
                    throw new IOException(missingBuild(named.label(), minecraftVersion));
                }
                // Asked for as this kind and there is none. Overwhelmingly this
                // is the program that loads the pack - a shader naming Iris -
                // rather than a pack that is missing a build, so the note sends
                // the reader to the section that can install it instead of
                // reporting a gap in a catalogue.
                requirements.add(otherKindNote(named.label()));
                continue;
            }

            ModFile file = found.get();
            if (!kind.matches(file.fileName())) {
                String note = named.label() + " - " + file.fileName() + " is not "
                        + describeKind() + ". This project publishes this version in another"
                        + " form; install that one from the section it belongs to";
                if (named.depth() == 0) {
                    throw new IOException(note);
                }
                requirements.add(note);
                continue;
            }
            if (!file.isDownloadable()) {
                Optional<ModFile> mirrored = mirrorOnModrinth(file);
                if (mirrored.isEmpty()) {
                    manual.add(named.label() + " - " + file.fileName()
                            + " (the author has disabled third-party downloads)");
                    if (named.depth() == 0) {
                        return new Result(installed, requirements, manual);
                    }
                    continue;
                }
                file = mirrored.get();
                progress.log("%s is taken from Modrinth: the identical file, where it may be"
                        + " downloaded", file.fileName());
            }

            Path target = packsDir.resolve(file.fileName());
            progress.stage("Downloading " + file.fileName());
            downloader.run(List.of(DownloadTask.of(file.url(), target,
                    file.sha1(), file.size(), file.fileName())), progress);

            // Whatever this project left here last time, when it is not the file
            // just downloaded: a newer version of the same pack, whose old zip
            // would otherwise reappear in the list as one the player had put
            // there themselves.
            String installedName = file.fileName();
            library.get(key)
                    .map(previous -> previous.file().fileName())
                    .filter(name -> !name.equals(installedName))
                    .ifPresent(name -> replace(packsDir, name, progress));

            ModOrigin origin = named.depth() == 0 ? ModOrigin.MANUAL : ModOrigin.DEPENDENCY;
            library.put(named.card() != null
                    ? InstalledMod.of(named.card(), file, origin, null)
                    : new InstalledMod(ModInstaller.readableNameFrom(file.fileName()),
                            file, origin, null));
            installed.add(file);

            for (String dependency : file.dependencies()) {
                if (visited.contains(InstalledMod.keyOf(chosen.source(), dependency))) {
                    continue;
                }
                queue.add(new Pending(null, dependency, named.depth() + 1));
            }
        }

        library.write();
        return new Result(installed, requirements, manual);
    }

    /**
     * What the platform publishes about a project, or null.
     *
     * <p>Never fatal. A platform that will not answer costs a row labelled from
     * its file name, which is what every row looked like before project cards
     * existed - not an install that fails.
     */
    private static ModProvider.ProjectCard cardFor(ModProvider provider, String projectId)
            throws InterruptedException {
        try {
            return provider.project(projectId).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** One project waiting to be installed, and what to call it in a message. */
    private record Pending(ModProvider.ProjectCard card, String projectId, int depth) {

        String label() {
            return card != null ? card.title() : projectId;
        }

        Pending with(ModProvider.ProjectCard found) {
            return new Pending(found, projectId, depth);
        }
    }

    /**
     * Removes one pack the launcher installed, and the packs it brought with it
     * that nothing else needs.
     *
     * <p>Deleted rather than sent to the recycle bin, because the record says
     * where it came from and it can be installed again - the same distinction
     * {@link ModScan#discard} draws for the packs the launcher did not download.
     *
     * @return how many files were deleted
     */
    public int remove(String key, Path packsDir, Progress progress) throws IOException {
        ModLibrary library = scan.libraryOf(packsDir);
        InstalledMod pack = library.get(key).orElseThrow(
                () -> new IOException("no " + describeKind() + " recorded under " + key));
        if (pack.origin() == ModOrigin.PACK) {
            throw new IOException(pack.title() + " came with a modpack, and a modpack goes out"
                    + " whole rather than a file at a time");
        }

        int deleted = delete(packsDir, pack.file().fileName(), progress) ? 1 : 0;
        library.forget(key);

        // The packs it required, when nothing else requires them. A dependency
        // left behind is a pack nobody can account for; one still needed by a
        // second pack is one that has to stay.
        for (InstalledMod dependency : List.copyOf(library.all())) {
            if (dependency.origin() != ModOrigin.DEPENDENCY || neededByAnother(library, dependency)) {
                continue;
            }
            if (delete(packsDir, dependency.file().fileName(), progress)) {
                deleted++;
            }
            library.forget(dependency.key());
        }

        library.write();
        return deleted;
    }

    /**
     * True when some other pack in the folder names this one as a requirement.
     *
     * <p>Read from what was recorded at install time rather than asked of the
     * platform again: a removal has to work offline, and the answer cannot
     * depend on a service being up.
     */
    private static boolean neededByAnother(ModLibrary library, InstalledMod dependency) {
        for (InstalledMod other : library.all()) {
            if (other.key().equals(dependency.key())) {
                continue;
            }
            if (other.file().dependencies().contains(dependency.file().projectId())) {
                return true;
            }
        }
        return false;
    }

    private boolean delete(Path packsDir, String fileName, Progress progress) throws IOException {
        // Both names: the player may have switched the pack off since it was
        // installed, and a rename is all that is.
        if (Files.deleteIfExists(packsDir.resolve(fileName))
                || Files.deleteIfExists(packsDir.resolve(fileName + PackScan.DISABLED_SUFFIX))) {
            progress.log("Removed %s", fileName);
            return true;
        }
        return false;
    }

    /** Throws away a file this installer wrote here before. */
    private void replace(Path packsDir, String fileName, Progress progress) {
        try {
            if (delete(packsDir, fileName, progress)) {
                progress.log("Replaced %s", fileName);
            }
        } catch (IOException | RuntimeException e) {
            // One stale file in the folder. The install itself is fine, and the
            // list will show the leftover as a pack the player put there.
            progress.log("Could not remove the previous %s: %s", fileName, e);
        }
    }

    /**
     * A requirement that is not this kind of thing.
     *
     * <p>Which for these two kinds means, almost always, the program that loads
     * the pack. It is a mod: it belongs in the mods folder, it is chosen against
     * the profile's loader, and installing it from here because somebody clicked
     * a shader would be putting a jar in their instance that they did not ask
     * for. So it is named and pointed at.
     */
    private String otherKindNote(String label) {
        return kind == ContentKind.SHADER
                ? label + " is needed to load this pack, and it is a mod rather than a shader"
                        + " pack - install it from Mods"
                : label + " is needed by this pack and is not a resource pack - install it"
                        + " from the section it belongs to";
    }

    private String missingBuild(String label, String minecraftVersion) {
        if (kind == ContentKind.SHADER) {
            return label + ": no shader build this instance can load. A shader pack is"
                    + " published for Iris, OptiFine or Canvas, and this project has none for"
                    + " the one installed here";
        }
        return label + ": no " + describeKind() + " build for Minecraft " + minecraftVersion;
    }

    private String describeKind() {
        return kind == ContentKind.SHADER ? "a shader pack" : "a resource pack";
    }

    private Optional<ModFile> mirrorOnModrinth(ModFile file) throws InterruptedException {
        if (file.source() == ModProvider.Source.MODRINTH
                || file.sha1() == null || file.sha1().isBlank()) {
            return Optional.empty();
        }
        if (!(providers.get(ModProvider.Source.MODRINTH) instanceof ModrinthProvider modrinth)
                || !modrinth.isAvailable()) {
            return Optional.empty();
        }
        try {
            return modrinth.resolveByHash(file.sha1())
                    .map(mirror -> new ModFile(file.projectId(), file.projectSlug(),
                            file.versionId(), file.displayName(), file.fileName(),
                            mirror.url(), file.sha1(), file.size(), file.dependencies(),
                            file.source()))
                    .filter(ModFile::isDownloadable);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
