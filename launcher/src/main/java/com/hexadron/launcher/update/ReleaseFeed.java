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
     * Whether a published file is the build for an operating system.
     *
     * <p>Matched on the name, which is the only thing the platform can be asked
     * about a file it is simply storing. Both halves are required - the system
     * and the format - because a release also carries the jar and the script
     * distribution, and handing a Windows user {@code launcher-0.9.5.jar}
     * because it was the first file in the list would be an "update" that
     * replaces a working launcher with something that cannot start.
     */
    public static boolean matches(String assetName, Platform.OsFamily os) {
        if (assetName == null) {
            return false;
        }
        String name = assetName.toLowerCase(Locale.ROOT);
        return switch (os) {
            case WINDOWS -> name.contains("windows") && name.endsWith(".zip");
            case LINUX -> name.contains("linux") && (name.endsWith(".tar.gz") || name.endsWith(".tgz"));
            case OSX -> (name.contains("macos") || name.contains("mac-") || name.contains("osx"))
                    && (name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".zip"));
        };
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
