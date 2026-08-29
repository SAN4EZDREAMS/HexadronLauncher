package com.hexadron.launcher.skin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The width and height in a PNG's header, read without decoding the image.
 *
 * <h2>Why not an image library</h2>
 *
 * <p>Two reasons, and the second is the real one. The first is cost: this
 * answers a question about eight bytes, and decoding a picture to ask it means
 * allocating the whole raster. The second is that this is the check that
 * decides whether a file is allowed into the skin store, and it runs on files
 * the user picked - so the less of the file is parsed before the size is known,
 * the smaller the surface. A decoder is a large program; a header read is
 * twenty-four bytes and a comparison.
 *
 * <p>The signature is checked too, so a JPEG renamed to {@code .png} is
 * rejected here rather than becoming a skin that renders as noise.
 */
public final class PngSize {

    /** The eight bytes every PNG starts with. */
    private static final byte[] SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngSize() {
    }

    /**
     * @return {@code {width, height}}, or null when the file is not a PNG
     */
    public static int[] read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(24);
            return read(header);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @param header the first 24 bytes of the file
     * @return {@code {width, height}}, or null when these are not a PNG header
     */
    public static int[] read(byte[] header) {
        if (header == null || header.length < 24) {
            return null;
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (header[i] != SIGNATURE[i]) {
                return null;
            }
        }
        // The first chunk of a PNG must be IHDR, and its data begins with the
        // two dimensions as big-endian 32-bit integers.
        if (header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
            return null;
        }
        int width = int32(header, 16);
        int height = int32(header, 20);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[]{width, height};
    }

    private static int int32(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 24)
                | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8)
                | (bytes[at + 3] & 0xFF);
    }
}
