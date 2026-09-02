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

import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The launcher's own releases, read from the repository they are published in.
 *
 * <h2>Why the platform's own list and not a file on a server</h2>
 *
 * <p>Because the releases already exist there. A launcher that reads a
 * hand-written {@code latest.json} needs somebody to remember to update it, and
 * the first time they forget the update either stops or points at a build that
 * was never uploaded. The list of releases is written by the thing that builds
 * them, carries the notes that were written for the humans reading them, and
 * says for itself which builds are finished and which are tests.
 *
 * <h2>What is asked for</h2>
 *
 * <p>One request, unauthenticated, to the public API. That is rate-limited per
 * address rather than per user, at sixty requests an hour - and this asks once
 * at start-up, or when somebody presses the button, so a launcher that is opened
 * ten times a day uses a sixth of it.
 */
public final class ReleaseFeed {

    /** Where this launcher's own builds are published. */
    public static final String DEFAULT_REPOSITORY = "SAN4EZDREAMS/HexadronLauncher";

    private static final String API = "https://api.github.com/repos/";

    /**
     * How many releases are read on the nightly channel.
     *
     * <p>Only the newest is used. The rest are read because "newest" is a
     * question about the whole list: drafts are skipped, and a release with no
     * build for this operating system is not an update for this machine.
     */
    private static final int LIST_SIZE = 20;

    private final String repository;

    public ReleaseFeed() {
        this(DEFAULT_REPOSITORY);
    }

    public ReleaseFeed(String repository) {
        this.repository = repository;
    }

    /** One downloadable file attached to a release. */
    public record Asset(String name, String url, long size) {
    }

    /**
     * One published build.
     *
     * @param notes what was written about it, as the markdown the author typed
     */
    public record Release(String tag, String name, String notes, boolean prerelease,
                          boolean draft, String publishedAt, String pageUrl, List<Asset> assets) {

        public Release {
            assets = List.copyOf(assets);
        }

        /**
         * The version this release carries.
         *
         * <p>From the tag, because that is the value the build was stamped with.
         * The release's title is written by a person and says things like
         * "Hexadron Launcher Beta 0.9.4.5", which is not a version number.
         */
        public Optional<AppVersion> version() {
            return AppVersion.of(tag);
        }

        /** The file for this machine, when the release has one. */
        public Optional<Asset> assetFor(Platform.OsFamily os) {
            for (Asset asset : assets) {
                if (matches(asset.name(), os)) {
                    return Optional.of(asset);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Whether a published file is <em>the whole build</em> for an operating
     * system.
     *
     * <p>Matched on the name, which is the only thing the platform can be asked
     * about a file it is simply storing - and matched on the whole name rather
     * than on pieces of it. That is not fussiness, it is a repaired bug: while
     * this asked for "a name with windows in it that ends in .zip", the day a
     * release also carried {@code HexadronLauncher-windows-app.zip} beside
     * {@code HexadronLauncher-windows.zip} the first one won, and the update
     * failed with "the downloaded archive holds no application image" - because
     * it was not one. A rule that can be satisfied by a file that is only part
     * of the build has no business choosing which file is the build.
     *
     * <p>The name is therefore the published name exactly: the application's
     * name, the system, and the extension for that system. Nothing else in a
     * release can collide with that, whatever is added to one later.
     */
    public static boolean matches(String assetName, Platform.OsFamily os) {
        if (assetName == null) {
            return false;
        }
        String name = assetName.toLowerCase(Locale.ROOT);
        for (String alias : aliases(os)) {
            for (String extension : extensions(os)) {
                if (name.equals("hexadronlauncher-" + alias + extension)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What a system has been called in this project's published file names. */
    private static List<String> aliases(Platform.OsFamily os) {
        return switch (os) {
            case WINDOWS -> List.of("windows");
            case LINUX -> List.of("linux");
            // "mac" and "osx" are not used now and were accepted before; a
            // release made under the old names still updates.
            case OSX -> List.of("macos", "mac", "osx");
        };
    }

    private static List<String> extensions(Platform.OsFamily os) {
        return os == Platform.OsFamily.WINDOWS
                ? List.of(".zip")
                : List.of(".tar.gz", ".tgz", ".zip");
    }

    /**
     * The newest build this channel will take.
     *
     * @return empty when the repository has published nothing this channel
     *         accepts, which is a normal answer and not a failure
     */
    public Optional<Release> latest(UpdateChannel channel) throws IOException, InterruptedException {
        if (channel == UpdateChannel.RELEASE) {
            // The platform's own idea of "latest", which is defined as the most
            // recent release that is neither a draft nor a pre-release - exactly
            // this channel's rule, answered without reading the whole list.
            try {
                return Optional.of(parse(Http.getJson(API + repository + "/releases/latest", headers())));
            } catch (Http.HttpStatusException status) {
                // 404 is "this repository has never published a finished
                // release", which is a state a new project is in for a while.
                if (status.statusCode() == 404) {
                    return Optional.empty();
                }
                throw status;
            }
        }
        Json list = Http.getJson(API + repository + "/releases?per_page=" + LIST_SIZE, headers());
        return newest(parseAll(list), channel);
    }

    /**
     * The one to offer out of a list.
     *
     * <p>The list arrives newest first, but "newest" there is by publication
     * date, and a release published later can carry an older version - a fix
     * backported to the previous line, say. The version is what decides.
     */
    public static Optional<Release> newest(List<Release> releases, UpdateChannel channel) {
        Release best = null;
        AppVersion bestVersion = null;
        for (Release release : releases) {
            if (release.draft() || (release.prerelease() && !channel.acceptsPrereleases())) {
                continue;
            }
            Optional<AppVersion> version = release.version();
            if (version.isEmpty()) {
                continue;
            }
            if (bestVersion == null || version.get().isNewerThan(bestVersion)) {
                best = release;
                bestVersion = version.get();
            }
        }
        return Optional.ofNullable(best);
    }

    public static List<Release> parseAll(Json list) {
        List<Release> releases = new ArrayList<>();
        for (Json entry : list.elements()) {
            releases.add(parse(entry));
        }
        return List.copyOf(releases);
    }

    public static Release parse(Json json) {
        List<Asset> assets = new ArrayList<>();
        for (Json asset : json.get("assets").elements()) {
            String name = asset.get("name").asString(null);
            String url = asset.get("browser_download_url").asString(null);
            if (name != null && url != null) {
                assets.add(new Asset(name, url, asset.get("size").asLong(0)));
            }
        }
        return new Release(
                json.get("tag_name").asString(""),
                json.get("name").asString(""),
                json.get("body").asString(""),
                json.get("prerelease").asBool(false),
                json.get("draft").asBool(false),
                json.get("published_at").asString(""),
                json.get("html_url").asString(""),
                assets);
    }

    private static Map<String, String> headers() {
        return Map.of(
                "Accept", "application/vnd.github+json",
                // Pinned, because the unversioned API is whatever it is today.
                "X-GitHub-Api-Version", "2022-11-28");
    }
}
