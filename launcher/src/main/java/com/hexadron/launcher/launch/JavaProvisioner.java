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
import java.util.Optional;
import java.util.Set;

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

        Path home = dirs.javaRuntime(component(candidate.major()));
        Path staging = home.resolveSibling(home.getFileName() + ".incomplete");
        Path archive = dirs.cache().resolve("java").resolve(candidate.archiveName());

        Archives.deleteRecursively(staging);
        Files.createDirectories(archive.getParent());

        progress.stage("Downloading Java " + candidate.major());
        progress.log("Source: %s", candidate.url());
        progress.log("%s, %d MB, SHA-256 %s", candidate, candidate.megabytes(), candidate.sha256());

        download(candidate, archive, progress);

        progress.stage("Unpacking Java " + candidate.major());
        try {
            Archives.extract(archive, staging, 1);

            JavaLocator.clearProbeCache();
            JavaLocator.JavaRuntime runtime = verify(staging, candidate, progress);

            Archives.deleteRecursively(home);
            Files.createDirectories(home.getParent());
            Files.move(staging, home);

            writeMarker(home, candidate);
            JavaLocator.clearProbeCache();

            return installed(candidate.major()).orElseThrow(() -> new IOException(
                    "the runtime was unpacked into " + home + " but cannot be started from there"));
        } finally {
            Archives.deleteRecursively(staging);
            Files.deleteIfExists(archive);
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
