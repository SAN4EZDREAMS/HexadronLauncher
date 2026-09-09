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

package com.hexadron.launcher.launch;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.util.Archives;
import com.hexadron.launcher.util.Hashes;
import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads a Java runtime when the machine has none that will do.
 *
 * <h2>Why Eclipse Temurin, and why not Mojang's runtimes</h2>
 *
 * <p>Mojang publishes the runtimes its own launcher uses, and they are the
 * obvious thing to reach for. This class deliberately does not use them. That
 * endpoint is part of the official launcher's private plumbing: it is
 * undocumented, it carries no licence granting anyone else the right to
 * redistribute what it serves, and a third-party launcher pulling from it is
 * relying on a service that was never offered to it. None of that is a good
 * foundation for something handed to other people.
 *
 * <p>Eclipse Temurin has no such problem. The binaries are OpenJDK under the
 * GNU General Public License version 2 with the Classpath Exception, which
 * permits redistribution, and Eclipse Adoptium publishes a documented, public
 * download API for exactly this purpose. Downloading a JRE from Adoptium is a
 * transaction between the user's machine and the Eclipse Foundation that has
 * nothing to do with Minecraft, so no permission from Mojang or Microsoft is
 * needed or implied.
 *
 * <p>The licence text ships inside every Temurin archive, under {@code legal/},
 * and is preserved on disk rather than discarded, which is what the GPL asks of
 * anyone passing the binaries on.
 *
 * @see <a href="https://api.adoptium.net/q/swagger-ui/">The Adoptium API</a>
 */
public final class JavaProvisioner {

    /** Shown wherever the launcher has to name where a runtime came from. */
    public static final String VENDOR = "Eclipse Temurin";

    private static final String API_BASE = "https://api.adoptium.net/v3/assets/latest/";

    /** Marker written beside a downloaded runtime, recording what it is. */
    private static final String MARKER = ".hexadron-runtime.json";

    /** Matches the folder names {@link #component} produces. */
    private static final Pattern COMPONENT = Pattern.compile("^temurin-(\\d+)-[^-]+-.+$");

    /**
     * One monitor per major version.
     *
     * <p>Two things can want the same runtime at the same moment - a Forge
     * installer's processor chain and a launch that is waiting on it, or two
     * profiles started in quick succession - and before this they shared a
     * staging directory whose first act was to delete itself. Whichever arrived
     * second wiped what the first had half-unpacked, and the failure looked like
     * a corrupt download. Serialised per major version, so two different
     * runtimes still install in parallel.
     */
    private static final Map<Integer, Object> LOCKS = new ConcurrentHashMap<>();

    /** One candidate build, as the Adoptium API describes it. */
    public record Candidate(int major, String releaseName, String url, long size,
                            String sha256, String archiveName, String imageType,
                            String os, String architecture) {

        /** Download size in whole megabytes, for the wording of the prompt. */
        public long megabytes() {
            return Math.max(1, Math.round(size / 1_048_576.0));
        }

        @Override
        public String toString() {
            return VENDOR + " " + releaseName + " (" + imageType.toUpperCase(Locale.ROOT)
                    + ", " + os + "/" + architecture + ", " + megabytes() + " MB)";
        }
    }

    private final GameDirs dirs;
    private final JavaLocator locator;

    public JavaProvisioner(GameDirs dirs, JavaLocator locator) {
        this.dirs = dirs;
        this.locator = locator;
    }

    /** The folder a runtime of this major version is installed into. */
    public String component(int major) {
        return "temurin-" + major + "-" + adoptiumOs() + "-" + adoptiumArch();
    }

    /**
     * A runtime this class installed earlier, if it is still there and still
     * reports the version it was fetched for.
     */
    public Optional<JavaLocator.JavaRuntime> installed(int major) {
        Path home = dirs.javaRuntime(component(major));
        for (Path candidate : List.of(home, home.resolve("Contents").resolve("Home"))) {
            Path executable = candidate.resolve("bin").resolve(Platform.javaConsoleExecutableName());
            if (!Files.isRegularFile(executable)) {
                continue;
            }
            JavaLocator.JavaRuntime runtime = locator.probe(executable, "downloaded by the launcher");
            if (runtime != null && runtime.majorVersion() == major) {
                return Optional.of(runtime);
            }
        }
        return Optional.empty();
    }

    /**
     * Asks Adoptium what it has for this machine.
     *
     * <p>A JRE is preferred over a JDK: it is roughly half the size and Minecraft
     * needs nothing a JDK adds. The fallbacks below exist because coverage is
     * not uniform - there is no Temurin 8 for Apple Silicon, and Windows on ARM
     * only appears from 21 onwards - and on those combinations an x64 build run
     * through the platform's own translation layer is the working answer rather
     * than no answer.
     */
    public Optional<Candidate> find(int major) throws IOException, InterruptedException {
        for (String[] attempt : attempts()) {
            Optional<Candidate> candidate = query(major, attempt[0], attempt[1]);
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    /** (architecture, image type) pairs to try, in order. */
    private List<String[]> attempts() {
        Set<String> architectures = new LinkedHashSet<>();
        architectures.add(adoptiumArch());
        if (adoptiumArch().equals("aarch64")) {
            // Apple's Rosetta and Windows on ARM both run x64 binaries.
            architectures.add("x64");
        }
        List<String[]> attempts = new ArrayList<>();
        for (String architecture : architectures) {
            attempts.add(new String[]{architecture, "jre"});
        }
        for (String architecture : architectures) {
            attempts.add(new String[]{architecture, "jdk"});
        }
        return attempts;
    }

    private Optional<Candidate> query(int major, String architecture, String imageType)
            throws IOException, InterruptedException {

        String url = API_BASE + major + "/hotspot"
                + "?architecture=" + architecture
                + "&image_type=" + imageType
                + "&os=" + adoptiumOs()
                + "&vendor=eclipse"
                + "&project=jdk"
                + "&heap_size=normal";

        Json response;
        try {
            response = Http.getJson(url);
        } catch (Http.HttpStatusException e) {
            // 404 is the API's way of saying "no such release line". Anything
            // else is a real fault and belongs to the caller.
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        if (!response.isArray()) {
            return Optional.empty();
        }

        for (Json asset : response.elements()) {
            Json binary = asset.get("binary");
            Json pkg = binary.get("package");
            String link = pkg.get("link").asString("");
            String checksum = pkg.get("checksum").asString("");
            String name = pkg.get("name").asString("");
            long size = pkg.get("size").asLong(-1);
            if (link.isBlank() || checksum.isBlank() || name.isBlank()) {
                continue;
            }
            // Without a published checksum there is nothing to verify the
            // download against, and an unverified runtime is not worth having.
            if (!link.startsWith("https://")) {
                continue;
            }
            return Optional.of(new Candidate(
                    major,
                    asset.get("release_name").asString("jdk-" + major),
                    link,
                    size,
                    checksum.toLowerCase(Locale.ROOT),
                    name,
                    imageType,
                    adoptiumOs(),
                    architecture));
        }
        return Optional.empty();
    }

    /**
     * Downloads and unpacks a runtime, and returns it.
     *
     * <p>Everything lands in a scratch directory first and is moved into place
     * only once it has been verified and shown to start. A launcher that leaves
     * half a runtime behind after a dropped connection is a launcher that fails
     * the same way on every later attempt, with a directory that exists and does
     * not work.
     */
    public JavaLocator.JavaRuntime install(Candidate candidate, Progress progress)
            throws IOException, InterruptedException {

        Object lock = LOCKS.computeIfAbsent(candidate.major(), major -> new Object());
        synchronized (lock) {
            // Re-checked inside the lock. Whoever was ahead in the queue may have
            // just finished fetching exactly this, and downloading it a second
            // time to overwrite it with itself is 180 MB of nothing.
            Optional<JavaLocator.JavaRuntime> already = installed(candidate.major());
            if (already.isPresent()) {
                progress.log("Java %d was already fetched: %s",
                        candidate.major(), already.get());
                return already.get();
            }
            return installExclusively(candidate, progress);
        }
    }

    /** The body of {@link #install}, with the per-version lock already held. */
    private JavaLocator.JavaRuntime installExclusively(Candidate candidate, Progress progress)
            throws IOException, InterruptedException {

        Path home = dirs.javaRuntime(component(candidate.major()));
        // Unique per attempt rather than a fixed ".incomplete": a leftover from
        // a run that was killed must not be mistaken for this run's work, and
        // two attempts must not share one directory.
        Path staging = home.resolveSibling(home.getFileName() + ".incomplete-"
                + ProcessHandle.current().pid() + "-" + System.nanoTime());
        Path archive = dirs.cache().resolve("java").resolve(candidate.archiveName());

        Files.createDirectories(archive.getParent());
        sweepLeftovers(home);

        progress.stage("Downloading Java " + candidate.major());
        progress.log("Source: %s", candidate.url());
        progress.log("%s, %d MB, SHA-256 %s", candidate, candidate.megabytes(), candidate.sha256());

        download(candidate, archive, progress);

        progress.stage("Unpacking Java " + candidate.major());
        try {
            Archives.extract(archive, staging, 1);

            JavaLocator.clearProbeCache();
            verify(staging, candidate, progress);

            swapIntoPlace(staging, home, candidate.major());

            writeMarker(home, candidate);
            JavaLocator.clearProbeCache();

            return installed(candidate.major()).orElseThrow(() -> new IOException(
                    "the runtime was unpacked into " + home + " but cannot be started from there"));
        } finally {
            Archives.deleteWhatCan(staging);
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // The archive is in the cache and its job is done. A file the
                // system will not release yet is not worth failing a finished
                // install over.
            }
        }
    }

    /**
     * Puts {@code staging} where {@code home} is, without a moment in which
     * neither exists.
     *
     * <p>This used to delete {@code home} and then move. On Windows that is not
     * one step: if anything still holds a handle inside the old tree - and the
     * game running on that very runtime does - the delete half-succeeds and the
     * move then fails onto a directory that exists and no longer works. Every
     * later attempt found that directory, and the launcher reported a corrupt
     * runtime it had corrupted itself. Moving the old tree aside either succeeds
     * completely or changes nothing, and says which.
     */
    private void swapIntoPlace(Path staging, Path home, int major) throws IOException {
        Files.createDirectories(home.getParent());
        Path retired = null;
        if (Files.exists(home)) {
            retired = home.resolveSibling(home.getFileName() + ".replaced-" + System.nanoTime());
            try {
                Files.move(home, retired);
            } catch (IOException e) {
                throw new IOException("Java " + major + " is already unpacked at " + home
                        + " and it cannot be replaced while something is using it. Close "
                        + "Minecraft, then try again. Nothing was changed.", e);
            }
        }
        try {
            Files.move(staging, home);
        } catch (IOException e) {
            if (retired != null) {
                // Put back what was working before, so a failed replacement
                // leaves the machine no worse than it started.
                try {
                    Files.move(retired, home);
                } catch (IOException ignored) {
                    throw new IOException("the new Java " + major + " could not be moved into "
                            + home + ", and the runtime that was there is now at " + retired
                            + ". Rename it back, or delete both and let the launcher fetch "
                            + "another.", e);
                }
            }
            throw e;
        }
        if (retired != null) {
            Archives.deleteWhatCan(retired);
        }
    }

    /**
     * Removes the debris of earlier attempts beside {@code home}.
     *
     * <p>Best effort, and never the runtime itself. A killed download leaves a
     * partial staging tree that nothing will ever look at again; left alone, one
     * per attempt accumulates at 180 MB each.
     */
    private void sweepLeftovers(Path home) {
        Path parent = home.getParent();
        String prefix = home.getFileName().toString();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        try (var entries = Files.list(parent)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix + ".incomplete-")
                                || name.startsWith(prefix + ".replaced-");
                    })
                    .forEach(Archives::deleteWhatCan);
        } catch (IOException | RuntimeException ignored) {
            // Nothing here is required for the install to succeed.
        }
    }

    /**
     * Streams the archive to disk, hashing as it goes.
     *
     * <p>Hashing during the transfer rather than by re-reading the file
     * afterwards halves the disk work on what is the largest single download the
     * launcher ever makes, and lets the byte counter be honest about a file
     * whose size the API already told us.
     */
    private void download(Candidate candidate, Path archive, Progress progress)
            throws IOException, InterruptedException {

        Path temp = archive.resolveSibling(archive.getFileName() + ".part");
        Files.deleteIfExists(temp);

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("this JVM has no SHA-256 implementation", e);
        }

        long total = Math.max(candidate.size(), 0);
        long done = 0;
        try (InputStream in = Http.openStream(candidate.url());
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[131072];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (progress.isCancelled()) {
                    throw new InterruptedException("cancelled while downloading Java");
                }
                out.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                done += count;
                progress.bytes(done, total);
                // The progress bar is driven by item counts, not byte counts, and
                // this is a single item. Counting megabytes as items gives the
                // one download in the launcher that is big enough to need a bar
                // an actual bar instead of an indeterminate sweep.
                if (total > 0) {
                    progress.items((int) (done / 1_048_576L), (int) (total / 1_048_576L));
                }
            }
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        String actual = Hashes.normalise(hex(digest.digest()));
        if (!actual.equalsIgnoreCase(Hashes.normalise(candidate.sha256()))) {
            Files.deleteIfExists(temp);
            throw new IOException("the downloaded Java archive does not match the checksum "
                    + "Adoptium published for it (expected " + candidate.sha256() + ", got " + actual
                    + "). Nothing was installed.");
        }
        Files.move(temp, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Confirms the unpacked tree actually starts and reports the right version. */
    private JavaLocator.JavaRuntime verify(Path staging, Candidate candidate, Progress progress)
            throws IOException {

        for (Path base : List.of(staging, staging.resolve("Contents").resolve("Home"))) {
            Path executable = base.resolve("bin").resolve(Platform.javaConsoleExecutableName());
            if (!Files.isRegularFile(executable)) {
                continue;
            }
            JavaLocator.JavaRuntime runtime = locator.probe(executable, "downloaded by the launcher");
            if (runtime == null) {
                throw new IOException("the unpacked runtime at " + base + " does not report a version");
            }
            if (runtime.majorVersion() != candidate.major()) {
                throw new IOException("expected Java " + candidate.major() + " but the download "
                        + "unpacked to Java " + runtime.majorVersion());
            }
            progress.log("Installed %s", runtime);
            return runtime;
        }
        throw new IOException("the archive from Adoptium contained no bin/"
                + Platform.javaConsoleExecutableName());
    }

    /**
     * Records what this runtime is and where it came from.
     *
     * <p>Read by nothing: it is there so that a user who finds a 180 MB folder
     * inside the launcher's data directory can tell what put it there, what it
     * is licensed under, and that deleting it is safe.
     */
    private void writeMarker(Path home, Candidate candidate) throws IOException {
        Json.object()
                .put("vendor", VENDOR)
                .put("release", candidate.releaseName())
                .put("majorVersion", candidate.major())
                .put("imageType", candidate.imageType())
                .put("platform", candidate.os() + "/" + candidate.architecture())
                .put("downloadedFrom", candidate.url())
                .put("sha256", candidate.sha256())
                .put("license", "GPLv2 with Classpath Exception - see the legal/ folder beside this file")
                .put("note", "Downloaded by HexadronLauncher because no suitable Java was installed. "
                        + "Safe to delete; it will be fetched again if it is needed.")
                .write(home.resolve(MARKER));
    }

    // ------------------------------------------------------------ inventory

    /**
     * The major versions this class has installed on this machine.
     *
     * <p>Read from the folder names, and only for folders that carry the marker.
     * The marker is what distinguishes a runtime the launcher fetched - and may
     * therefore delete again - from anything else a user has put in that folder.
     */
    public List<Integer> installedMajors() {
        Path root = dirs.javaRuntimes();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Integer> majors = new ArrayList<>();
        try (var entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                Matcher matcher = COMPONENT.matcher(directory.getFileName().toString());
                if (!matcher.matches() || !isLauncherManaged(directory)) {
                    continue;
                }
                try {
                    majors.add(Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    // Not one of ours after all.
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return List.copyOf(majors);
        }
        majors.sort(null);
        return List.copyOf(majors);
    }

    /** Whether this directory is a runtime the launcher downloaded. */
    public boolean isLauncherManaged(Path runtimeHome) {
        return runtimeHome != null && Files.isRegularFile(runtimeHome.resolve(MARKER));
    }

    /** Where a runtime of this major version lives, whether or not it is there. */
    public Path home(int major) {
        return dirs.javaRuntime(component(major));
    }

    /**
     * Deletes a runtime this class installed.
     *
     * <p>Refuses anything without the marker, so a path that has been
     * hand-edited, or a folder a user pointed the setting at, is never removed
     * by a routine that thinks it owns everything under {@code java/}.
     *
     * @return the paths that could not be deleted; empty when the runtime is gone
     */
    public List<Path> uninstall(int major) throws IOException {
        Path home = home(major);
        if (!Files.exists(home)) {
            return List.of();
        }
        if (!isLauncherManaged(home)) {
            throw new IOException("refusing to delete " + home + ": it carries no "
                    + MARKER + ", so the launcher did not put it there");
        }
        List<Path> undeleted = Archives.deleteWhatCan(home);
        JavaLocator.clearProbeCache();
        return undeleted;
    }

    // ------------------------------------------------------------- platform

    /** The {@code os} value the Adoptium API uses for this machine. */
    public static String adoptiumOs() {
        return switch (Platform.os()) {
            case WINDOWS -> "windows";
            case OSX -> "mac";
            case LINUX -> "linux";
        };
    }

    /** The {@code architecture} value the Adoptium API uses for this machine. */
    public static String adoptiumArch() {
        return switch (Platform.arch()) {
            case "arm64" -> "aarch64";
            case "arm32" -> "arm";
            case "x86" -> "x32";
            default -> "x64";
        };
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** Where a downloaded runtime's licence files end up. Shown in the log. */
    public Path licenseDirectory(int major) {
        return dirs.javaRuntime(component(major)).resolve("legal");
    }

    /** Human-readable note naming the vendor and licence. Used by the prompt. */
    public static String attribution() {
        return VENDOR + " (OpenJDK), GPLv2 with Classpath Exception, from Eclipse Adoptium";
    }
}
