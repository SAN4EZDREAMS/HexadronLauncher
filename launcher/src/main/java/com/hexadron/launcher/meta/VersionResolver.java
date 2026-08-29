package com.hexadron.launcher.meta;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads installed version manifests from disk and flattens {@code inheritsFrom}
 * chains into a single launchable {@link VersionJson}.
 *
 * <p>Chains are usually two links (fabric-loader-x-26.2 inherits 26.2) but Forge
 * installs can be longer, and a malformed pair could form a cycle - which is
 * detected here rather than as a StackOverflowError at launch time.
 */
public final class VersionResolver {

    private static final int MAX_CHAIN_LENGTH = 16;

    private final GameDirs dirs;

    public VersionResolver(GameDirs dirs) {
        this.dirs = dirs;
    }

    /** Reads one version manifest from {@code versions/<id>/<id>.json} without resolving inheritance. */
    public VersionJson load(String versionId) throws IOException {
        Path path = dirs.versionJson(versionId);
        if (!Files.isRegularFile(path)) {
            throw new IOException("version '" + versionId + "' is not installed (expected " + path + ")");
        }
        try {
            return VersionJson.parse(Json.read(path));
        } catch (RuntimeException e) {
            throw new IOException("version manifest for '" + versionId + "' is malformed: " + e.getMessage(), e);
        }
    }

    /**
     * Loads a version and flattens its whole inheritance chain.
     *
     * @throws IOException if a link is missing, or the chain loops or is absurdly long
     */
    public VersionJson resolve(String versionId) throws IOException {
        List<VersionJson> chain = chain(versionId);
        VersionJson resolved = chain.get(0);
        for (int i = 1; i < chain.size(); i++) {
            resolved = VersionJson.merge(resolved, chain.get(i));
        }
        return resolved;
    }

    /** The chain from the requested version up to the root, child first. */
    public List<VersionJson> chain(String versionId) throws IOException {
        List<VersionJson> chain = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();

        String current = versionId;
        while (current != null) {
            if (!visited.add(current)) {
                throw new IOException("inheritsFrom cycle detected: " + visited + " -> " + current);
            }
            if (chain.size() >= MAX_CHAIN_LENGTH) {
                throw new IOException("inheritsFrom chain longer than " + MAX_CHAIN_LENGTH + ": " + visited);
            }
            VersionJson version = load(current);
            chain.add(version);
            current = version.hasParent() ? version.inheritsFrom() : null;
        }
        return chain;
    }

    /** Ids of every version manifest present on disk. */
    public List<String> installedVersions() throws IOException {
        Path versions = dirs.versions();
        if (!Files.isDirectory(versions)) {
            return List.of();
        }
        try (var stream = Files.list(versions)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(id -> Files.isRegularFile(dirs.versionJson(id)))
                    .sorted()
                    .toList();
        }
    }

    public boolean isInstalled(String versionId) {
        return Files.isRegularFile(dirs.versionJson(versionId));
    }

    /**
     * Whether this version and everything it inherits from are on disk and
     * readable.
     *
     * <p>The difference from {@link #isInstalled} is the chain.
     * {@code fabric-loader-0.19.3-26.2} being present says nothing about 26.2
     * being present, and a launch needs both - so "installed" for the purpose of
     * deciding whether anything has to be fetched is this, not that.
     *
     * <p>Answers with a boolean rather than throwing because the caller is asking
     * a question, not attempting the work: a missing link here means "go and
     * install it", and the install path reports its own failures far better than
     * an exception raised while deciding whether to take it.
     */
    public boolean isFullyInstalled(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return false;
        }
        try {
            chain(versionId);
            return true;
        } catch (IOException e) {
            // Missing, malformed, cyclic or too long - all of which mean the
            // install path has to run.
            return false;
        }
    }
}
