package com.hexadron.launcher.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** File and byte digests. Every downloaded artifact is verified through here. */
public final class Hashes {

    private Hashes() {
    }

    public static String sha1(Path file) throws IOException {
        return digest(file, "SHA-1");
    }

    public static String sha512(Path file) throws IOException {
        return digest(file, "SHA-512");
    }

    public static String md5(Path file) throws IOException {
        return digest(file, "MD5");
    }

    public static String sha1(byte[] data) {
        return HexFormat.of().formatHex(newDigest("SHA-1").digest(data));
    }

    public static String digest(Path file, String algorithm) throws IOException {
        MessageDigest md = newDigest(algorithm);
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * True when {@code file} exists and matches {@code expectedHex}.
     *
     * <p>A blank expected hash means "no hash published" - the file is then
     * accepted on existence alone, which is what the official launcher does for
     * artifacts that carry no checksum (some Fabric and Forge maven entries).
     */
    public static boolean matches(Path file, String expectedHex, String algorithm) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        if (expectedHex == null || expectedHex.isBlank()) {
            return true;
        }
        try {
            return digest(file, algorithm).equalsIgnoreCase(expectedHex.trim());
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean matchesSha1(Path file, String expectedHex) {
        return matches(file, expectedHex, "SHA-1");
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is required by the Java platform", e);
        }
    }

    /** Normalises a hex digest for comparison and storage. */
    public static String normalise(String hex) {
        return hex == null ? "" : hex.trim().toLowerCase(Locale.ROOT);
    }
}
