package com.hexadron.launcher.skin;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.json.Json;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.util.Hashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The agent that points the game's authentication library somewhere else.
 *
 * <h2>Why an agent at all</h2>
 *
 * <p>The endpoints Minecraft talks to for profiles and textures are compiled
 * into its authentication library as constants. There is no setting, and the
 * system properties that once worked were removed. authlib-injector is the
 * established answer: a Java agent that rewrites those constants as the classes
 * load, so the client asks a chosen service instead of Mojang's. It is what
 * every third-party skin system for offline play is built on, which is also why
 * a server configured for one of those systems and a client configured for the
 * same one see each other's skins.
 *
 * <h2>Obtaining it</h2>
 *
 * <p>Downloaded once, over HTTPS, and checked against the SHA-256 the project
 * publishes beside it. Being honest about what that check is worth: the hash
 * and the jar come from the same publisher, so it catches a corrupted or
 * truncated download and a tampered mirror, and it does not catch a compromised
 * publisher. It is the same trust as any other artifact the launcher fetches by
 * URL, and it is why the agent is attached only when a profile actually asks
 * for a skin service - a launch that wants no skins loads no agent.
 *
 * <p>The jar can also simply be placed in the folder by hand, which is checked
 * first. A user who would rather review it themselves and drop it in never has
 * to let the launcher fetch anything.
 */
public final class AuthlibInjector {

    /** Where the project publishes its current build and that build's hash. */
    private static final String LATEST = "https://authlib-injector.yushi.moe/artifact/latest.json";

    private AuthlibInjector() {
    }

    /** The jar, whether or not it has been fetched yet. */
    public static Path jar(GameDirs dirs) {
        return dirs.agents().resolve("authlib-injector.jar");
    }

    /** True when the agent is on disk and does not need fetching. */
    public static boolean isPresent(GameDirs dirs) {
        Path jar = jar(dirs);
        try {
            return Files.isRegularFile(jar) && Files.size(jar) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Makes sure the agent is on disk, fetching it if it is not.
     *
     * @throws IOException when it is absent and cannot be fetched. The message
     *                     is shown to the user, and says that skins are the only
     *                     thing that will not work
     */
    public static Path ensure(GameDirs dirs, Progress progress)
            throws IOException, InterruptedException {

        Path jar = jar(dirs);
        if (isPresent(dirs)) {
            return jar;
        }

        progress.stage("Fetching the skin agent");
        Json latest = Http.getJson(LATEST);
        String url = latest.get("download_url").asString(null);
        String sha256 = latest.get("checksums").get("sha256").asString(null);
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("the skin agent index gave no usable download address");
        }

        byte[] bytes = Http.getBytes(url);
        if (sha256 != null && !sha256.isBlank()) {
            String actual = Hashes.sha256(bytes);
            if (!actual.equalsIgnoreCase(sha256)) {
                throw new IOException("the skin agent did not match its published checksum"
                        + " - refusing to use it");
            }
        }

        Files.createDirectories(jar.getParent());
        // Written beside and moved into place, so a launch that races an
        // interrupted download cannot attach half a jar.
        Path temp = jar.resolveSibling(jar.getFileName() + ".part");
        Files.write(temp, bytes);
        Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
        progress.log("Skin agent installed (%d KB)", bytes.length / 1024);
        return jar;
    }

    /**
     * The JVM arguments that attach the agent to a service.
     *
     * @param apiRoot    base URL of the Yggdrasil service
     * @param prefetched the service description, base64, or null to let the
     *                   agent fetch it. Handing it over removes a request from
     *                   start-up, and removes a way for the game to fail to
     *                   start because that request did not answer
     */
    public static List<String> arguments(Path jar, String apiRoot, String prefetched) {
        List<String> arguments = new ArrayList<>(3);
        arguments.add("-javaagent:" + jar.toAbsolutePath() + "=" + apiRoot);
        if (prefetched != null && !prefetched.isBlank()) {
            arguments.add("-Dauthlibinjector.yggdrasil.prefetched=" + prefetched);
        }
        // The service name is otherwise printed over the server list, which for
        // a service running on this machine is noise.
        arguments.add("-Dauthlibinjector.noShowServerName");
        return List.copyOf(arguments);
    }
}
