package com.hexadron.launcher.util;

import java.util.Objects;

/**
 * A Maven coordinate as written in Minecraft version JSON library entries.
 *
 * <p>Format: {@code group:artifact:version[:classifier][@extension]}.
 * Vanilla entries are plain three-part coordinates; Forge and NeoForge make
 * heavy use of the classifier and the {@code @zip}/{@code @txt} extension
 * suffix in their installer profiles.
 */
public record MavenCoordinate(String group, String artifact, String version, String classifier, String extension) {

    public MavenCoordinate {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(version, "version");
        classifier = (classifier == null || classifier.isBlank()) ? null : classifier;
        extension = (extension == null || extension.isBlank()) ? "jar" : extension;
    }

    public static MavenCoordinate parse(String coordinate) {
        String remainder = coordinate;
        String extension = "jar";

        int at = remainder.lastIndexOf('@');
        // Guard against '@' appearing before the last ':' - not legal, but be defensive.
        if (at >= 0 && at > remainder.lastIndexOf(':')) {
            extension = remainder.substring(at + 1);
            remainder = remainder.substring(0, at);
        }

        String[] parts = remainder.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("not a maven coordinate: " + coordinate);
        }
        String classifier = parts.length >= 4 ? parts[3] : null;
        return new MavenCoordinate(parts[0], parts[1], parts[2], classifier, extension);
    }

    /** Repository-relative path, e.g. {@code org/ow2/asm/asm/9.7/asm-9.7.jar}. */
    public String path() {
        StringBuilder sb = new StringBuilder();
        sb.append(group.replace('.', '/')).append('/');
        sb.append(artifact).append('/');
        sb.append(version).append('/');
        sb.append(artifact).append('-').append(version);
        if (classifier != null) {
            sb.append('-').append(classifier);
        }
        sb.append('.').append(extension);
        return sb.toString();
    }

    /** {@code group:artifact} - the identity used to deduplicate the classpath. */
    public String groupArtifact() {
        return group + ":" + artifact;
    }

    /**
     * {@code group:artifact:classifier} - the identity used when deduplicating
     * must keep two different classifiers of the same artifact (Forge ships
     * both the plain and the {@code :universal} jar).
     */
    public String dedupeKey() {
        return classifier == null ? groupArtifact() : groupArtifact() + ":" + classifier;
    }

    public MavenCoordinate withClassifier(String newClassifier) {
        return new MavenCoordinate(group, artifact, version, newClassifier, extension);
    }

    public MavenCoordinate withExtension(String newExtension) {
        return new MavenCoordinate(group, artifact, version, classifier, newExtension);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(group).append(':').append(artifact).append(':').append(version);
        if (classifier != null) {
            sb.append(':').append(classifier);
        }
        if (!extension.equals("jar")) {
            sb.append('@').append(extension);
        }
        return sb.toString();
    }
}
