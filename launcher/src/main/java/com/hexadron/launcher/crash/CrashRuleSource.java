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

package com.hexadron.launcher.crash;

import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.update.ReleaseFeed;
import com.hexadron.launcher.update.UpdateChannel;
import com.hexadron.launcher.update.UpdateSignature;
import com.hexadron.launcher.util.FilePermissions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the rule file: the one built in, or a newer signed one published
 * with a release.
 *
 * <h2>Why the newer file must be signed</h2>
 *
 * <p>Rules decide which mod the launcher offers to switch off and what the
 * player is told to do. A file that anyone on the network path could replace
 * would be a way to tell every player with a crash to download something. So
 * a published file is used only with a valid Ed25519 signature by the update
 * key ({@link UpdateSignature}), the same key that signs update manifests, and
 * only when its version is higher than the built-in one. Anything else - no
 * signature, a bad one, an older version, a file that does not parse - leaves
 * the built-in rules in charge, and says so in the log.
 *
 * <p>The signature is checked every time the cached file is read, not only
 * when it is downloaded, so a file changed on disk afterwards is refused too.
 */
public final class CrashRuleSource {

    /** The name of the rule file among a release's assets. */
    public static final String ASSET = "hexadron-crash-rules.json";

    /** How often the launcher asks for a newer file. */
    static final Duration CHECK_INTERVAL = Duration.ofHours(24);

    private final Path dir;
    private volatile CrashRules current;
    private volatile String origin = "built-in";

    /** @param dir where the downloaded file and its signature are kept */
    public CrashRuleSource(Path dir) {
        this.dir = dir;
    }

    /** The rules in use; the built-in ones until a newer signed file has been read. */
    public CrashRules current() {
        CrashRules rules = current;
        if (rules == null) {
            synchronized (this) {
                if (current == null) {
                    current = load();
                }
                rules = current;
            }
        }
        return rules;
    }

    /** "built-in", or "downloaded, version N", for the log. */
    public String origin() {
        current();
        return origin;
    }

    private CrashRules load() {
        CrashRules bundled = CrashRules.bundled();
        byte[] json = readQuietly(dir.resolve(ASSET));
        byte[] signature = readQuietly(dir.resolve(ASSET + UpdateSignature.SUFFIX));
        CrashRules chosen = choose(bundled, json, signature, null);
        origin = chosen == bundled ? "built-in, version " + bundled.version()
                : "downloaded, version " + chosen.version();
        return chosen;
    }

    /**
     * The newer of the built-in rules and a published file, if the file is
     * signed, readable and newer.
     *
     * @param keys the keys to check against; null for the keys of this build
     */
    public static CrashRules choose(CrashRules bundled, byte[] json, byte[] signature,
                                    List<String> keys) {
        if (json == null || signature == null || json.length > CrashRules.MAX_FILE_BYTES) {
            return bundled;
        }
        boolean signed = keys == null
                ? UpdateSignature.isConfigured() && UpdateSignature.verify(json, signature)
                : UpdateSignature.verify(json, signature, keys);
        if (!signed) {
            return bundled;
        }
        try {
            CrashRules published = CrashRules.parse(new String(json, StandardCharsets.UTF_8));
            if (!published.texts().keySet().containsAll(CrashRules.CODE_TEXTS)) {
                return bundled;
            }
            return published.version() > bundled.version() ? published : bundled;
        } catch (RuntimeException e) {
            return bundled;
        }
    }

    /**
     * Asks the latest release for a newer rule file, at most once a day.
     *
     * @return a line for the log, or empty when nothing was asked
     */
    public Optional<String> refresh(ReleaseFeed feed, UpdateChannel channel)
            throws InterruptedException {
        Path stamp = dir.resolve("checked");
        try {
            if (Files.isRegularFile(stamp)) {
                long last = Files.getLastModifiedTime(stamp).toMillis();
                if (System.currentTimeMillis() - last < CHECK_INTERVAL.toMillis()) {
                    return Optional.empty();
                }
            }
            FilePermissions.createRestrictedDirectory(dir);
            Files.writeString(stamp, "");

            Optional<ReleaseFeed.Release> release = feed.latest(channel);
            if (release.isEmpty()) {
                return Optional.of("Crash rules: no release to check");
            }
            ReleaseFeed.Asset rules = null;
            ReleaseFeed.Asset signature = null;
            for (ReleaseFeed.Asset asset : release.get().assets()) {
                if (asset.name().equals(ASSET)) {
                    rules = asset;
                } else if (asset.name().equals(ASSET + UpdateSignature.SUFFIX)) {
                    signature = asset;
                }
            }
            if (rules == null || signature == null) {
                return Optional.of("Crash rules: " + release.get().tag() + " publishes none");
            }
            byte[] json = download(rules.url(), CrashRules.MAX_FILE_BYTES);
            byte[] sig = download(signature.url(), 4096);
            CrashRules before = current();
            CrashRules chosen = choose(before, json, sig, null);
            if (chosen == before) {
                return Optional.of("Crash rules: kept " + origin()
                        + " (the published file is not newer, or not signed)");
            }
            Path temp = dir.resolve(ASSET + ".part");
            Path tempSig = dir.resolve(ASSET + UpdateSignature.SUFFIX + ".part");
            Files.write(temp, json);
            Files.write(tempSig, sig);
            Files.move(tempSig, dir.resolve(ASSET + UpdateSignature.SUFFIX),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, dir.resolve(ASSET), StandardCopyOption.REPLACE_EXISTING);
            synchronized (this) {
                current = chosen;
                origin = "downloaded, version " + chosen.version();
            }
            return Optional.of("Crash rules: now using " + origin);
        } catch (IOException e) {
            return Optional.of("Crash rules: could not check for a newer file: " + e.getMessage());
        }
    }

    private static byte[] download(String url, int limit) throws IOException, InterruptedException {
        try (InputStream in = Http.openStream(Http.requireHttps(url))) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("file is larger than " + limit + " bytes");
            }
            return bytes;
        }
    }

    private static byte[] readQuietly(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
