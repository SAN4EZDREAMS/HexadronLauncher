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

import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.util.Archives;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Building the new launcher out of the parts of the old one.
 *
 * <h2>What is actually being saved</h2>
 *
 * <p>A published image is the launcher, JavaFX and a whole Java runtime. Between
 * two nightly builds the launcher's own jar changes and the other two do not, so
 * downloading the image whole means fetching the same runtime again every night.
 * The build is published in parts, with a manifest that says which file is in
 * which part and what each file's SHA-256 is; this fetches the parts whose files
 * the installation does not already have, and takes the rest off the disk.
 *
 * <h2>The rules that keep it honest</h2>
 *
 * <ol>
 *   <li>A local file is reused only when its hash is the one the manifest names.
 *       Same name, same length, different content - downloaded.</li>
 *   <li>A part is fetched whole or not at all. Half a part is not a thing this
 *       can reason about.</li>
 *   <li>The assembled image is verified against the manifest, in full, before it
 *       is handed to the updater. Anything wrong here is a failure, and a
 *       failure falls back to downloading the whole archive.</li>
 *   <li>If what would have to be fetched is most of the build anyway, the whole
 *       archive is taken instead: one request beats five.</li>
 * </ol>
 *
 * <p>None of this is a trust decision. The manifest comes from the same release,
 * over the same HTTPS, as the archive it replaces - and every file that ends up
 * in the new image has been checked against it, which is more than the plain
 * archive path ever did.
 */
public final class DeltaUpdate {

    /**
     * When the delta stops being worth it.
     *
     * <p>Below this share of the whole build, fetching parts saves real time.
     * Above it, the parts add requests, a manifest and an assembly step to save
     * a fraction of a download that was going to happen anyway.
     */
    private static final double WORTH_IT = 0.75;

    /** Where the new image is put together, beside the download. */
    public static final String ASSEMBLY_DIR = "assembled";

    private DeltaUpdate() {
    }

    /**
     * What an update would cost with the parts this machine already has.
     *
     * @param fetch what has to be downloaded, in the manifest's order
     * @param reuse what can be taken from the installation
     */
    public record Plan(List<String> fetch, List<ImageManifest.Entry> reuse,
                       long unpackedFetched, long unpackedReused) {

        public Plan {
            fetch = List.copyOf(fetch);
            reuse = List.copyOf(reuse);
        }

        /** Nothing to download at all: every part is already on the disk. */
        public boolean isComplete() {
            return fetch.isEmpty();
        }

        /** The share of the build that would have to come over the network. */
        public double fetchedShare() {
            long total = unpackedFetched + unpackedReused;
            return total <= 0 ? 1 : (double) unpackedFetched / total;
        }
    }

    /**
     * Works out what can be taken from the installation and what cannot.
     *
     * <p>Per part, and by hash. Reading the current installation costs a few
     * seconds of disk - which is what the manifest is for, and cheaper than the
     * download it avoids by a factor of about a hundred.
     */
    public static Plan plan(ImageManifest manifest, Path currentRoot, Progress progress) {
        List<String> fetch = new ArrayList<>();
        List<ImageManifest.Entry> reuse = new ArrayList<>();
        long fetched = 0;
        long reused = 0;

        for (String part : manifest.partNames()) {
            List<ImageManifest.Entry> entries = manifest.filesOf(part);
            boolean whole = !entries.isEmpty();
            for (ImageManifest.Entry entry : entries) {
                if (progress != null && progress.isCancelled()) {
                    whole = false;
                    break;
                }
                if (!alreadyHere(entry, currentRoot)) {
                    whole = false;
                    break;
                }
            }
            if (whole) {
                reuse.addAll(entries);
                reused += manifest.unpackedSizeOf(part);
            } else {
                fetch.add(part);
                fetched += manifest.unpackedSizeOf(part);
            }
        }
        return new Plan(fetch, reuse, fetched, reused);
    }

    /**
     * Whether the installation already holds exactly this file.
     *
     * <p>The length first, because it is free and answers most of the question,
     * and then the hash, which is the only part that counts. Any failure to read
     * the file is a "no": an unreadable file is one to download, not one to
     * guess about.
     */
    static boolean alreadyHere(ImageManifest.Entry entry, Path root) {
        Path local = root.resolve(entry.path().replace('/', java.io.File.separatorChar));
        try {
            if (entry.isLink()) {
                return Files.isSymbolicLink(local)
                        && ImageManifest.slashes(Files.readSymbolicLink(local))
                                .equals(entry.link());
            }
            if (!Files.isRegularFile(local, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (entry.size() >= 0 && Files.size(local) != entry.size()) {
                return false;
            }
            return ImageManifest.sha256(local).equals(entry.sha256());
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Builds the new image, fetching what is missing.
     *
     * @param assets  where a part's published file can be found, by asset name
     * @return the assembled image, ready for the updater
     */
    public static Path assemble(ImageManifest manifest, Plan plan, Path currentRoot,
                                Path workDir, Assets assets, Progress progress)
            throws IOException, InterruptedException {

        Path assembly = workDir.resolve(ASSEMBLY_DIR);
        if (Files.exists(assembly)) {
            Archives.deleteRecursively(assembly);
        }
        Files.createDirectories(assembly);

        // Downloaded first. The copying is the cheap half, and a machine that is
        // going to fail this update because a part cannot be fetched should find
        // that out before it has written a hundred megabytes of copies.
        Set<String> fetched = new LinkedHashSet<>(plan.fetch());
        int done = 0;
        for (String part : plan.fetch()) {
            String asset = manifest.assetOf(part).orElseThrow(
                    () -> new IOException("the manifest names no file for the part"));
            Path archive = assets.fetch(asset, workDir, progress);
            Archives.extract(archive, assembly, 0);
            Files.deleteIfExists(archive);
            progress.items(++done, plan.fetch().size());
        }

        progress.stage("assemble");
        for (ImageManifest.Entry entry : manifest.files()) {
            if (fetched.contains(entry.part())) {
                continue;
            }
            copyLocal(entry, currentRoot, assembly);
        }

        progress.stage("verify");
        verify(manifest, assembly);
        return assembly;
    }

    /** Takes one file out of the current installation. */
    private static void copyLocal(ImageManifest.Entry entry, Path currentRoot, Path assembly)
            throws IOException {

        Path from = currentRoot.resolve(entry.path().replace('/', java.io.File.separatorChar));
        Path to = assembly.resolve(entry.path().replace('/', java.io.File.separatorChar));
        Files.createDirectories(to.getParent());
        if (entry.isLink()) {
            // Deleted first, because a part that carried this path already -
            // which is what a mismatched or swapped archive looks like - would
            // otherwise fail here with nothing but a file name. It should fail
            // at the verification below, where the message says what is wrong.
            Files.deleteIfExists(to);
            Files.createSymbolicLink(to, Path.of(entry.link().replace('/',
                    java.io.File.separatorChar)));
            return;
        }
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    /**
     * Checks an assembled image against the manifest, in full.
     *
     * <p>Every file, every hash, before anything is replaced. This is the step
     * that makes the rest of it safe to do at all, so it is not sampled and not
     * skipped for large files: hashing a hundred megabytes takes about as long
     * as writing them did.
     */
    public static void verify(ImageManifest manifest, Path root) throws IOException {
        for (ImageManifest.Entry entry : manifest.files()) {
            Path file = root.resolve(entry.path().replace('/', java.io.File.separatorChar));
            if (entry.isLink()) {
                if (!Files.isSymbolicLink(file)
                        || !ImageManifest.slashes(Files.readSymbolicLink(file))
                                .equals(entry.link())) {
                    throw new IOException("the link " + entry.path() + " is not what was published");
                }
                continue;
            }
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("the assembled build is missing " + entry.path());
            }
            if (entry.size() >= 0 && Files.size(file) != entry.size()) {
                throw new IOException("the file " + entry.path() + " is the wrong length");
            }
            if (!ImageManifest.sha256(file).equals(entry.sha256())) {
                throw new IOException("the file " + entry.path() + " is not what was published");
            }
            if (entry.exec() && !ImageManifest.isExecutable(file)) {
                // An archive unpacked by something that does not carry the bit,
                // or a copy onto a filesystem that does not keep it. Put it back
                // rather than fail: the file itself is the right one, and it has
                // just been proved so.
                ImageManifest.makeExecutable(file);
            }
        }
    }

    /** Where a published file comes from. Separated so that it can be faked. */
    public interface Assets {

        /**
         * Fetches one published file into the work folder.
         *
         * @return the file on the disk
         */
        Path fetch(String assetName, Path workDir, Progress progress)
                throws IOException, InterruptedException;
    }

    /** True when a plan is worth carrying out rather than taking the archive. */
    public static boolean worthwhile(Plan plan) {
        return plan.fetchedShare() < WORTH_IT;
    }
}
