package com.hexadron.launcher.install.loader;

import com.hexadron.launcher.net.Http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code maven-metadata.xml} version lists.
 *
 * <p>Forge and NeoForge publish their build lists only as maven metadata, so
 * this pulls the {@code <version>} elements out directly. A full XML parser is
 * unnecessary and would be one more thing to keep safe against entity
 * expansion; the document shape here is fixed and trivial.
 */
public final class MavenVersionList {

    private static final Pattern VERSION_ELEMENT =
            Pattern.compile("<version>\\s*([^<\\s][^<]*?)\\s*</version>");

    private MavenVersionList() {
    }

    /**
     * @param metadataUrl absolute URL of a {@code maven-metadata.xml}
     * @return every published version, in the order maven lists them (oldest first)
     */
    public static List<String> fetch(String metadataUrl) throws IOException, InterruptedException {
        String xml = Http.getString(metadataUrl);
        List<String> versions = new ArrayList<>();
        Matcher matcher = VERSION_ELEMENT.matcher(xml);
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        if (versions.isEmpty()) {
            throw new IOException("no <version> entries found in " + metadataUrl);
        }
        return List.copyOf(versions);
    }

    /** Same as {@link #fetch} but newest first, which is what a version picker wants. */
    public static List<String> fetchNewestFirst(String metadataUrl) throws IOException, InterruptedException {
        List<String> versions = new ArrayList<>(fetch(metadataUrl));
        java.util.Collections.reverse(versions);
        return List.copyOf(versions);
    }
}
