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

package com.hexadron.launcher.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A decoder for lossless WebP, so that mod logos can be shown.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Modrinth serves most project icons as WebP - the URL in its own API ends
 * in {@code _96.webp} - and neither JavaFX nor {@code javax.imageio} can decode
 * that format. The whole catalogue therefore drew a coloured letter where a logo
 * should be, and the handful that did work were the older projects whose icon is
 * still a PNG. There is no format to ask for instead: the file on the CDN is a
 * WebP and that is the only thing at that address.
 *
 * <h2>Why only the lossless half</h2>
 *
 * <p>WebP is two formats behind one extension. Lossy WebP is a VP8 key frame -
 * an entropy coder, an inverse DCT, sixteen intra prediction modes and a loop
 * filter, which is a video codec and is not something this project should carry
 * for a 96-pixel logo. Lossless WebP is a palette-and-prediction image format of
 * the same order of complexity as PNG.
 *
 * <p>Every icon Modrinth publishes is the lossless one: of the 68 logos this
 * launcher had cached when the decoder was written, 15 were PNG and 53 were
 * WebP, and all 53 carried the {@code VP8L} tag. So the lossy half is not
 * implemented and, on the one file in a thousand that needs it, {@link #decode}
 * returns empty and the caller draws its lettered tile - which is what every
 * WebP logo did before this class existed.
 *
 * <h2>What it is checked against</h2>
 *
 * <p>The specification is Google's "WebP Lossless Bitstream Specification", and
 * the reference is libwebp's own decoder: several details here - which pixel the
 * top-right predictor reads at the end of a row, the byte-wise delta coding of a
 * colour map - are things a reader of the prose would get subtly wrong and only
 * find out on a picture that comes out looking almost right. The self-check
 * decodes real Modrinth icons and compares them pixel for pixel against output
 * produced by libwebp.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It reads one still image. Animation, ICC profiles, EXIF and XMP chunks are
 * skipped rather than parsed, and an extended file ({@code VP8X}) is only read
 * when the image inside it is lossless. Nothing here allocates on a size it has
 * not checked, because these bytes come off the internet.
 */
public final class Webp {

    /**
     * Largest image this will decode, in pixels.
     *
     * <p>A logo is 96 by 96. The limit is here because the width and height come
     * out of the first four bytes of an untrusted file, and a decoder that
     * believes them allocates whatever it is told to.
     */
    private static final int MAX_PIXELS = 4096 * 4096;

    /** The signature byte that opens a lossless stream. */
    private static final int VP8L_MAGIC = 0x2f;

    private static final int MAX_CODE_LENGTH = 15;
    private static final int LITERAL_CODES = 256;
    private static final int LENGTH_CODES = 24;
    private static final int DISTANCE_CODES = 40;
    private static final int CODE_LENGTH_CODES = 19;

    private static final int PREDICTOR_TRANSFORM = 0;
    private static final int CROSS_COLOR_TRANSFORM = 1;
    private static final int SUBTRACT_GREEN_TRANSFORM = 2;
    private static final int COLOR_INDEXING_TRANSFORM = 3;

    /** The order the 19 code-length codes are written in. */
    private static final int[] CODE_LENGTH_ORDER = {
        17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    /**
     * The short-distance plane codes.
     *
     * <p>The first 120 distance codes do not mean "this many pixels back" but
     * "this far back and this far up", so that a match against the row above
     * costs a small number rather than one the size of the image. Each entry
     * packs a y offset in the high nibble and an x offset, biased by 8, in the
     * low one.
     */
    private static final int[] CODE_TO_PLANE = {
        0x18, 0x07, 0x17, 0x19, 0x28, 0x06, 0x27, 0x29, 0x16, 0x1a,
        0x26, 0x2a, 0x38, 0x05, 0x37, 0x39, 0x15, 0x1b, 0x36, 0x3a,
        0x25, 0x2b, 0x48, 0x04, 0x47, 0x49, 0x14, 0x1c, 0x35, 0x3b,
        0x46, 0x4a, 0x24, 0x2c, 0x58, 0x45, 0x4b, 0x34, 0x3c, 0x03,
        0x57, 0x59, 0x13, 0x1d, 0x56, 0x5a, 0x23, 0x2d, 0x44, 0x4c,
        0x55, 0x5b, 0x33, 0x3d, 0x68, 0x02, 0x67, 0x69, 0x12, 0x1e,
        0x66, 0x6a, 0x22, 0x2e, 0x54, 0x5c, 0x43, 0x4d, 0x65, 0x6b,
        0x32, 0x3e, 0x78, 0x01, 0x77, 0x79, 0x53, 0x5d, 0x11, 0x1f,
        0x64, 0x6c, 0x42, 0x4e, 0x76, 0x7a, 0x21, 0x2f, 0x75, 0x7b,
        0x31, 0x3f, 0x63, 0x6d, 0x52, 0x5e, 0x00, 0x74, 0x7c, 0x41,
        0x4f, 0x10, 0x20, 0x62, 0x6e, 0x30, 0x73, 0x7d, 0x51, 0x5f,
        0x40, 0x72, 0x7e, 0x61, 0x6f, 0x50, 0x71, 0x7f, 0x60, 0x70
    };

    /** The multiplier the colour cache hashes with. */
    private static final int CACHE_HASH = 0x1e35a7bd;

    /**
     * A decoded picture: straight ARGB, one int per pixel, top row first.
     *
     * <p>The array is handed over rather than copied. It is freshly allocated by
     * the decode that produced it and nothing else keeps a reference, so a copy
     * would be two hundred kilobytes of work per logo to protect against a
     * sharing that does not happen.
     */
    public record Bitmap(int width, int height, int[] argb) {
    }

    private Webp() {
    }

    /** True when these bytes open like a WebP file, whichever kind it is. */
    public static boolean isWebp(byte[] bytes) {
        return bytes != null && bytes.length >= 16
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    /**
     * Decodes a lossless WebP.
     *
     * @return empty when this is not a WebP at all, when it is the lossy kind,
     *         or when the file is damaged. None of those is an error worth
     *         raising at a caller that is drawing a list: they all mean "no
     *         picture", and the caller already has something to draw instead
     */
    public static Optional<Bitmap> decode(byte[] bytes) {
        if (!isWebp(bytes)) {
            return Optional.empty();
        }
        try {
            int offset = findLosslessChunk(bytes);
            if (offset < 0) {
                return Optional.empty();
            }
            return Optional.of(new Decoder(bytes, offset).decode());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Finds the start of the {@code VP8L} payload.
     *
     * <p>Walks the RIFF chunks rather than assuming the image is the first one:
     * a file written with an alpha hint, an ICC profile or EXIF data opens with
     * {@code VP8X} and puts the image after them.
     *
     * @return the index of the payload's first byte, or -1 when there is no
     *         lossless image in this file
     */
    private static int findLosslessChunk(byte[] bytes) {
        int at = 12;
        while (at + 8 <= bytes.length) {
            int size = readLe32(bytes, at + 4);
            if (size < 0 || at + 8 + size > bytes.length) {
                return -1;
            }
            if (bytes[at] == 'V' && bytes[at + 1] == 'P' && bytes[at + 2] == '8'
                    && bytes[at + 3] == 'L') {
                return at + 8;
            }
            // Chunks are padded to an even length, and the pad byte is not
            // counted in the size.
            at += 8 + size + (size & 1);
        }
        return -1;
    }

    private static int readLe32(byte[] bytes, int at) {
        return (bytes[at] & 0xff)
                | ((bytes[at + 1] & 0xff) << 8)
                | ((bytes[at + 2] & 0xff) << 16)
                | ((bytes[at + 3] & 0xff) << 24);
    }

    /** Raised inside the decoder and turned into {@link Optional#empty()} at the door. */
    private static final class Malformed extends RuntimeException {
        Malformed(String message) {
            super(message, null, false, false);
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * The bit reader.
     *
     * <p>Bits come out of each byte starting at the least significant end, and
     * bytes in file order - the opposite convention to the Huffman codes read
     * through it, which are written most significant bit first. Both are what
     * the format says; the two are not a mistake.
     */
    private static final class Bits {

        private final byte[] data;
        private final int end;
        private int position;

        Bits(byte[] data, int start) {
            this.data = data;
            this.end = data.length << 3;
            this.position = start << 3;
        }

        int bit() {
            if (position >= end) {
                throw new Malformed("the stream ended in the middle of a symbol");
            }
            int value = (data[position >>> 3] >>> (position & 7)) & 1;
            position++;
            return value;
        }

        int read(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                value |= bit() << i;
            }
            return value;
        }
    }

    /**
     * One Huffman code, as a tree walked a bit at a time.
     *
     * <p>A lookup table would be faster and is what libwebp uses. A tree is a
     * few hundred nanoseconds slower per symbol on a picture this size and is
     * the version whose correctness can be read off the page, which for a
     * decoder fed by the internet is the trade worth making.
     */
    private static final class Huffman {

        private final int[] children;
        private final int[] symbols;
        private int nodes = 1;

        /** Set when the code has exactly one symbol, which then costs no bits at all. */
        private int only = -1;

        Huffman(int[] lengths) {
            int used = 0;
            int last = -1;
            int longest = 0;
            for (int symbol = 0; symbol < lengths.length; symbol++) {
                int length = lengths[symbol];
                if (length == 0) {
                    continue;
                }
                if (length > MAX_CODE_LENGTH) {
                    throw new Malformed("a Huffman code is longer than the format allows");
                }
                used++;
                last = symbol;
                longest = Math.max(longest, length);
            }
            if (used == 0) {
                throw new Malformed("a Huffman code with no symbols");
            }
            // One symbol is a code with nothing to distinguish, so it is read
            // without consuming anything. Building a one-branch tree instead
            // would demand a bit that the encoder never wrote.
            if (used == 1) {
                this.children = new int[0];
                this.symbols = new int[0];
                this.only = last;
                return;
            }

            this.children = new int[2 * (2 * used)];
            this.symbols = new int[2 * used];
            java.util.Arrays.fill(children, -1);
            java.util.Arrays.fill(symbols, -1);

            // Canonical assignment: symbols of the same length take consecutive
            // codes, in symbol order, shortest lengths first.
            int[] countPerLength = new int[longest + 1];
            for (int length : lengths) {
                if (length > 0) {
                    countPerLength[length]++;
                }
            }
            int[] nextCode = new int[longest + 2];
            int code = 0;
            for (int length = 1; length <= longest; length++) {
                code = (code + countPerLength[length - 1]) << 1;
                nextCode[length] = code;
            }
            for (int symbol = 0; symbol < lengths.length; symbol++) {
                int length = lengths[symbol];
                if (length > 0) {
                    insert(nextCode[length]++, length, symbol);
                }
            }
        }

        private void insert(int code, int length, int symbol) {
            int node = 0;
            for (int bit = length - 1; bit >= 0; bit--) {
                int slot = 2 * node + ((code >>> bit) & 1);
                if (children[slot] < 0) {
                    if (nodes >= symbols.length) {
                        throw new Malformed("a Huffman code that does not fit its own lengths");
                    }
                    children[slot] = nodes++;
                }
                node = children[slot];
            }
            if (symbols[node] >= 0) {
                throw new Malformed("two symbols share one Huffman code");
            }
            symbols[node] = symbol;
        }

        int read(Bits bits) {
            if (only >= 0) {
                return only;
            }
            int node = 0;
            while (symbols[node] < 0) {
                node = children[2 * node + bits.bit()];
                if (node < 0) {
                    throw new Malformed("a Huffman code that is not in the tree");
                }
            }
            return symbols[node];
        }
    }

    /** The five codes one region of the image is written with. */
    private record Group(Huffman green, Huffman red, Huffman blue, Huffman alpha, Huffman distance) {
    }

    /** One transform, and whatever it needs to be undone. */
    private static final class Transform {
        int type;
        int bits;
        /** The image's width before this transform narrowed it - so, after undoing it. */
        int width;
        int height;
        int[] data;
    }

    // ---------------------------------------------------------------- decoder

    private static final class Decoder {

        private final Bits bits;

        Decoder(byte[] data, int offset) {
            if (offset >= data.length || (data[offset] & 0xff) != VP8L_MAGIC) {
                throw new Malformed("this is not a lossless WebP stream");
            }
            this.bits = new Bits(data, offset + 1);
        }

        Bitmap decode() {
            int width = bits.read(14) + 1;
            int height = bits.read(14) + 1;
            bits.read(1);            // alpha_is_used: a hint, and not one to trust
            int version = bits.read(3);
            if (version != 0) {
                throw new Malformed("an unknown lossless version: " + version);
            }
            checkSize(width, height);
            return new Bitmap(width, height, stream(width, height, true));
        }

        private static void checkSize(int width, int height) {
            if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                throw new Malformed("an image of an unreasonable size: " + width + "x" + height);
            }
        }

        /**
         * Reads one image: its transforms, then its pixels, then undoes them.
         *
         * <p>The same routine reads the picture and the small images that
         * describe how to read it - the predictor map, the colour map, the map
         * of which Huffman codes apply where. Those are stored as pictures
         * because they compress like pictures, and they are read by this method
         * with {@code topLevel} false, which is what forbids them from carrying
         * transforms or maps of their own and stops the recursion.
         */
        private int[] stream(int width, int height, boolean topLevel) {
            List<Transform> transforms = new ArrayList<>();
            int codedWidth = width;
            if (topLevel) {
                boolean[] seen = new boolean[4];
                while (bits.bit() != 0) {
                    Transform transform = new Transform();
                    transform.type = bits.read(2);
                    if (seen[transform.type]) {
                        throw new Malformed("the same transform twice");
                    }
                    seen[transform.type] = true;
                    transform.width = codedWidth;
                    transform.height = height;
                    codedWidth = readTransform(transform, codedWidth, height);
                    transforms.add(transform);
                }
            }

            int[] pixels = pixels(codedWidth, height, topLevel);

            // In reverse: the last transform written is the outermost, so it is
            // the first that has to come off.
            for (int i = transforms.size() - 1; i >= 0; i--) {
                Transform transform = transforms.get(i);
                pixels = inverse(transform, pixels, codedWidth);
                codedWidth = transform.width;
            }
            return pixels;
        }

        /**
         * Reads one transform's parameters and data.
         *
         * @return the width the image is coded at from here on, which only the
         *         colour-indexing transform changes - it packs several pixels
         *         into one when the palette is small enough
         */
        private int readTransform(Transform transform, int width, int height) {
            switch (transform.type) {
                case PREDICTOR_TRANSFORM, CROSS_COLOR_TRANSFORM -> {
                    transform.bits = bits.read(3) + 2;
                    transform.data = stream(subSample(width, transform.bits),
                            subSample(height, transform.bits), false);
                    return width;
                }
                case COLOR_INDEXING_TRANSFORM -> {
                    int colours = bits.read(8) + 1;
                    transform.bits = colours > 16 ? 0 : colours > 4 ? 1 : colours > 2 ? 2 : 3;
                    transform.data = expandColourMap(stream(colours, 1, false), colours,
                            transform.bits);
                    return subSample(width, transform.bits);
                }
                case SUBTRACT_GREEN_TRANSFORM -> {
                    return width;
                }
                default -> throw new Malformed("an unknown transform");
            }
        }

        /**
         * The palette, delta-decoded and padded out to a whole power of two.
         *
         * <p>Two things happen here that reading the prose does not suggest.
         * Each entry is stored as a per-byte difference from the one before, so
         * a palette of similar colours costs almost nothing; and the table is
         * then padded with black up to the full range the index can address, so
         * that a damaged file naming a colour that was never written finds a
         * colour there rather than the end of an array.
         */
        private static int[] expandColourMap(int[] coded, int colours, int packBits) {
            if (coded.length < colours) {
                throw new Malformed("a colour map shorter than it claims");
            }
            int[] map = new int[1 << (8 >> packBits)];
            if (colours > map.length) {
                throw new Malformed("a colour map larger than its index");
            }
            map[0] = coded[0];
            for (int i = 1; i < colours; i++) {
                map[i] = addPixels(coded[i], map[i - 1]);
            }
            return map;
        }

        /**
         * Reads the pixels themselves.
         *
         * <p>Three things can come out of the green code, and which one it is
         * decides what is read next: a value below 256 is a green channel and
         * three more codes follow it; the next 24 are the length of a run to be
         * copied from somewhere earlier in the image; anything above that is an
         * index into the cache of recently used colours. That is the whole of
         * the format's compression - a palette, a back-reference and a cache -
         * and everything else in this class is undoing what was done to the
         * pixels before they were written.
         */
        private int[] pixels(int width, int height, boolean topLevel) {
            checkSize(width, height);

            int cacheBits = 0;
            if (bits.bit() != 0) {
                cacheBits = bits.read(4);
                if (cacheBits < 1 || cacheBits > 11) {
                    throw new Malformed("a colour cache of an impossible size");
                }
            }

            int[] groupImage = null;
            int groupBits = 0;
            int groupWidth = 0;
            int groups = 1;
            if (topLevel && bits.bit() != 0) {
                groupBits = bits.read(3) + 2;
                groupWidth = subSample(width, groupBits);
                groupImage = stream(groupWidth, subSample(height, groupBits), false);
                // The index is written into the red and green bytes of a
                // picture, which is why a file with many regions still costs
                // almost nothing to describe.
                int highest = 0;
                for (int i = 0; i < groupImage.length; i++) {
                    int group = (groupImage[i] >>> 8) & 0xffff;
                    groupImage[i] = group;
                    highest = Math.max(highest, group);
                }
                groups = highest + 1;
            }

            Group[] codes = new Group[groups];
            for (int i = 0; i < groups; i++) {
                codes[i] = readGroup(cacheBits);
            }

            int[] cache = cacheBits > 0 ? new int[1 << cacheBits] : null;
            int cacheShift = 32 - cacheBits;
            int[] argb = new int[width * height];
            int cached = 0;
            int position = 0;
            int column = 0;
            int row = 0;
            int cacheLimit = LITERAL_CODES + LENGTH_CODES + (cacheBits > 0 ? 1 << cacheBits : 0);

            while (position < argb.length) {
                Group group = codes[groupImage == null
                        ? 0
                        : groupImage[groupWidth * (row >>> groupBits) + (column >>> groupBits)]];

                int code = group.green().read(bits);
                if (code < LITERAL_CODES) {
                    int red = group.red().read(bits);
                    int blue = group.blue().read(bits);
                    int alpha = group.alpha().read(bits);
                    argb[position] = (alpha << 24) | (red << 16) | (code << 8) | blue;
                    position++;
                    if (++column >= width) {
                        column = 0;
                        row++;
                    }
                } else if (code < LITERAL_CODES + LENGTH_CODES) {
                    int length = prefix(code - LITERAL_CODES);
                    int distance = planeDistance(width, prefix(group.distance().read(bits)));
                    if (distance > position || length > argb.length - position) {
                        throw new Malformed("a back reference that points outside the image");
                    }
                    for (int i = 0; i < length; i++) {
                        argb[position + i] = argb[position + i - distance];
                    }
                    position += length;
                    column += length;
                    while (column >= width) {
                        column -= width;
                        row++;
                    }
                } else if (code < cacheLimit) {
                    // Everything produced since the last look-up has to be in
                    // the cache before this index means anything, because the
                    // encoder filled it as it went.
                    while (cached < position) {
                        cache[(argb[cached] * CACHE_HASH) >>> cacheShift] = argb[cached];
                        cached++;
                    }
                    argb[position] = cache[code - LITERAL_CODES - LENGTH_CODES];
                    position++;
                    if (++column >= width) {
                        column = 0;
                        row++;
                    }
                } else {
                    throw new Malformed("a symbol outside the alphabet");
                }

                if (cache != null) {
                    while (cached < position) {
                        cache[(argb[cached] * CACHE_HASH) >>> cacheShift] = argb[cached];
                        cached++;
                    }
                }
            }
            return argb;
        }

        /** The five codes of one region, in the order they are written. */
        private Group readGroup(int cacheBits) {
            int greenAlphabet = LITERAL_CODES + LENGTH_CODES
                    + (cacheBits > 0 ? 1 << cacheBits : 0);
            return new Group(
                    readCode(greenAlphabet),
                    readCode(LITERAL_CODES),
                    readCode(LITERAL_CODES),
                    readCode(LITERAL_CODES),
                    readCode(DISTANCE_CODES));
        }

        /**
         * One Huffman code.
         *
         * <p>Either spelled out - one or two symbols, for a channel that barely
         * varies - or written as a list of code lengths which is itself Huffman
         * coded, with runs of repeats. An icon with a flat background hits the
         * first case constantly.
         */
        private Huffman readCode(int alphabet) {
            int[] lengths = new int[alphabet];
            if (bits.bit() != 0) {
                int count = bits.read(1) + 1;
                int wide = bits.read(1);
                int symbol = bits.read(wide == 0 ? 1 : 8);
                if (symbol >= alphabet) {
                    throw new Malformed("a symbol outside the alphabet");
                }
                lengths[symbol] = 1;
                if (count == 2) {
                    symbol = bits.read(8);
                    if (symbol >= alphabet) {
                        throw new Malformed("a symbol outside the alphabet");
                    }
                    lengths[symbol] = 1;
                }
                return new Huffman(lengths);
            }

            int[] metaLengths = new int[CODE_LENGTH_CODES];
            int written = bits.read(4) + 4;
            for (int i = 0; i < written; i++) {
                metaLengths[CODE_LENGTH_ORDER[i]] = bits.read(3);
            }
            Huffman meta = new Huffman(metaLengths);

            // An encoder that knows the tail of the alphabet is unused says so
            // here, and stops early rather than writing a run of zeros.
            int remaining = alphabet;
            if (bits.bit() != 0) {
                int width = 2 + 2 * bits.read(3);
                remaining = 2 + bits.read(width);
            }

            int previous = 8;
            int symbol = 0;
            while (symbol < alphabet) {
                if (remaining-- == 0) {
                    break;
                }
                int length = meta.read(bits);
                if (length < 16) {
                    lengths[symbol++] = length;
                    if (length != 0) {
                        previous = length;
                    }
                    continue;
                }
                int repeat;
                int value;
                switch (length) {
                    case 16 -> {
                        repeat = bits.read(2) + 3;
                        value = previous;
                    }
                    case 17 -> {
                        repeat = bits.read(3) + 3;
                        value = 0;
                    }
                    default -> {
                        repeat = bits.read(7) + 11;
                        value = 0;
                    }
                }
                if (symbol + repeat > alphabet) {
                    throw new Malformed("a run of code lengths past the end of the alphabet");
                }
                while (repeat-- > 0) {
                    lengths[symbol++] = value;
                }
            }
            return new Huffman(lengths);
        }

        // ------------------------------------------------------------ inverse

        /**
         * A length or a distance, from its symbol.
         *
         * <p>The first four symbols are themselves; after that each pair
         * doubles the range and the remainder follows as extra bits. The same
         * trick DEFLATE uses, and what lets one small alphabet address a run
         * anywhere in the image.
         */
        private int prefix(int symbol) {
            if (symbol < 4) {
                return symbol + 1;
            }
            int extra = (symbol - 2) >>> 1;
            int offset = (2 + (symbol & 1)) << extra;
            return offset + bits.read(extra) + 1;
        }

        private int[] inverse(Transform transform, int[] pixels, int width) {
            return switch (transform.type) {
                case SUBTRACT_GREEN_TRANSFORM -> addGreen(pixels);
                case PREDICTOR_TRANSFORM -> unpredict(transform, pixels, width);
                case CROSS_COLOR_TRANSFORM -> uncross(transform, pixels, width);
                case COLOR_INDEXING_TRANSFORM -> unindex(transform, pixels, width);
                default -> throw new Malformed("an unknown transform");
            };
        }

        /**
         * Puts back the green that was taken out of red and blue.
         *
         * <p>The cheapest transform in the format and the one almost every
         * picture uses: in most images red, green and blue move together, so
         * storing red and blue as their distance from green leaves two channels
         * of small numbers instead of three of large ones.
         */
        private static int[] addGreen(int[] pixels) {
            for (int i = 0; i < pixels.length; i++) {
                int argb = pixels[i];
                int green = (argb >>> 8) & 0xff;
                int red = ((argb >>> 16) + green) & 0xff;
                int blue = (argb + green) & 0xff;
                pixels[i] = (argb & 0xff00ff00) | (red << 16) | blue;
            }
            return pixels;
        }

        /**
         * Undoes the spatial prediction.
         *
         * <p>The image is divided into tiles, each tile names one of fourteen
         * ways to guess a pixel from its neighbours, and what was stored is the
         * difference between the guess and the truth. Undoing it means making
         * the same guess from pixels that have already been restored, which is
         * why this runs strictly in reading order and reads from its own output.
         */
        private static int[] unpredict(Transform transform, int[] pixels, int width) {
            int height = transform.height;
            int[] out = new int[pixels.length];

            // The very first pixel has nothing to its left or above it, so it is
            // predicted from opaque black; the rest of the first row from the
            // pixel to the left; the first of every later row from the pixel
            // above.
            out[0] = addPixels(pixels[0], 0xff000000);
            for (int x = 1; x < width; x++) {
                out[x] = addPixels(pixels[x], out[x - 1]);
            }

            int tilesPerRow = subSample(width, transform.bits);

            for (int y = 1; y < height; y++) {
                int rowStart = y * width;
                int tileRow = (y >>> transform.bits) * tilesPerRow;
                out[rowStart] = addPixels(pixels[rowStart], out[rowStart - width]);
                for (int x = 1; x < width; x++) {
                    int at = rowStart + x;
                    int mode = (transform.data[tileRow + (x >>> transform.bits)] >>> 8) & 0xf;
                    out[at] = addPixels(pixels[at], predict(mode, out, at, width));
                }
            }
            return out;
        }

        /**
         * One predictor.
         *
         * <p>{@code above + 1} at the end of a row reads the first pixel of the
         * row being written rather than anything above it. That is not a bug
         * being reproduced: the rows are one continuous array in the format's
         * own definition, the encoder predicted from exactly that pixel, and a
         * decoder that "fixed" it would produce a picture with a wrong column
         * down its right edge.
         */
        private static int predict(int mode, int[] out, int at, int width) {
            int left = out[at - 1];
            int above = at - width;
            return switch (mode) {
                case 0 -> 0xff000000;
                case 1 -> left;
                case 2 -> out[above];
                case 3 -> out[above + 1];
                case 4 -> out[above - 1];
                case 5 -> average(average(left, out[above + 1]), out[above]);
                case 6 -> average(left, out[above - 1]);
                case 7 -> average(left, out[above]);
                case 8 -> average(out[above - 1], out[above]);
                case 9 -> average(out[above], out[above + 1]);
                case 10 -> average(average(left, out[above - 1]),
                        average(out[above], out[above + 1]));
                case 11 -> select(out[above], left, out[above - 1]);
                case 12 -> clampedAddSubtract(left, out[above], out[above - 1]);
                case 13 -> clampedAddSubtractHalf(left, out[above], out[above - 1]);
                default -> throw new Malformed("an unknown predictor: " + mode);
            };
        }

        /**
         * Undoes the cross-colour transform.
         *
         * <p>What is left after green has been subtracted still correlates:
         * red carries some of green, and blue carries some of both. Each tile
         * stores three signed multipliers saying how much, and this adds it
         * back.
         */
        private static int[] uncross(Transform transform, int[] pixels, int width) {
            int height = transform.height;
            int tilesPerRow = subSample(width, transform.bits);

            for (int y = 0; y < height; y++) {
                int rowStart = y * width;
                int tileRow = (y >>> transform.bits) * tilesPerRow;
                for (int x = 0; x < width; x++) {
                    int code = transform.data[tileRow + (x >>> transform.bits)];
                    int greenToRed = (byte) code;
                    int greenToBlue = (byte) (code >>> 8);
                    int redToBlue = (byte) (code >>> 16);

                    int argb = pixels[rowStart + x];
                    int green = (byte) (argb >>> 8);
                    int red = (((argb >>> 16) & 0xff) + ((greenToRed * green) >> 5)) & 0xff;
                    int blue = (argb & 0xff) + ((greenToBlue * green) >> 5)
                            + ((redToBlue * (byte) red) >> 5);
                    pixels[rowStart + x] = (argb & 0xff00ff00) | (red << 16) | (blue & 0xff);
                }
            }
            return pixels;
        }

        /**
         * Replaces palette indices with colours.
         *
         * <p>When the palette has sixteen colours or fewer the encoder also
         * packs two, four or eight pixels into each stored one, so the image
         * being read is narrower than the image being produced. That is the only
         * transform that changes the width, and it is why the width has to be
         * carried through the inverse pass rather than assumed constant.
         */
        private static int[] unindex(Transform transform, int[] pixels, int codedWidth) {
            int width = transform.width;
            int height = transform.height;
            int[] out = new int[width * height];
            int perPixel = 8 >> transform.bits;

            if (perPixel == 8) {
                for (int i = 0; i < out.length; i++) {
                    out[i] = transform.data[(pixels[i] >>> 8) & 0xff];
                }
                return out;
            }

            int perByte = 1 << transform.bits;
            int countMask = perByte - 1;
            int valueMask = (1 << perPixel) - 1;
            for (int y = 0; y < height; y++) {
                int packed = 0;
                int source = y * codedWidth;
                for (int x = 0; x < width; x++) {
                    if ((x & countMask) == 0) {
                        packed = (pixels[source++] >>> 8) & 0xff;
                    }
                    out[y * width + x] = transform.data[packed & valueMask];
                    packed >>>= perPixel;
                }
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** How many tiles of {@code 1 << bits} pixels it takes to cover this many. */
    private static int subSample(int size, int bits) {
        return (size + (1 << bits) - 1) >>> bits;
    }

    /** Adds two pixels channel by channel, each wrapping at 256. */
    private static int addPixels(int a, int b) {
        int alphaGreen = (a & 0xff00ff00) + (b & 0xff00ff00);
        int redBlue = (a & 0x00ff00ff) + (b & 0x00ff00ff);
        return (alphaGreen & 0xff00ff00) | (redBlue & 0x00ff00ff);
    }

    /** The mean of two pixels, channel by channel, without overflowing. */
    private static int average(int a, int b) {
        return (((a ^ b) & 0xfefefefe) >>> 1) + (a & b);
    }

    /**
     * Chooses whichever of left and above the gradient points at.
     *
     * <p>The same rule as PNG's Paeth predictor: it compares how far each
     * candidate is from what the three known neighbours predict, and takes the
     * nearer.
     */
    private static int select(int above, int left, int aboveLeft) {
        int difference = gradient(above >>> 24, left >>> 24, aboveLeft >>> 24)
                + gradient((above >> 16) & 0xff, (left >> 16) & 0xff, (aboveLeft >> 16) & 0xff)
                + gradient((above >> 8) & 0xff, (left >> 8) & 0xff, (aboveLeft >> 8) & 0xff)
                + gradient(above & 0xff, left & 0xff, aboveLeft & 0xff);
        return difference <= 0 ? above : left;
    }

    private static int gradient(int a, int b, int c) {
        return Math.abs(b - c) - Math.abs(a - c);
    }

    private static int clampedAddSubtract(int left, int above, int aboveLeft) {
        int alpha = clamp((left >>> 24) + (above >>> 24) - (aboveLeft >>> 24));
        int red = clamp(((left >> 16) & 0xff) + ((above >> 16) & 0xff) - ((aboveLeft >> 16) & 0xff));
        int green = clamp(((left >> 8) & 0xff) + ((above >> 8) & 0xff) - ((aboveLeft >> 8) & 0xff));
        int blue = clamp((left & 0xff) + (above & 0xff) - (aboveLeft & 0xff));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int clampedAddSubtractHalf(int left, int above, int aboveLeft) {
        int mean = average(left, above);
        int alpha = half(mean >>> 24, aboveLeft >>> 24);
        int red = half((mean >> 16) & 0xff, (aboveLeft >> 16) & 0xff);
        int green = half((mean >> 8) & 0xff, (aboveLeft >> 8) & 0xff);
        int blue = half(mean & 0xff, aboveLeft & 0xff);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int half(int a, int b) {
        return clamp(a + (a - b) / 2);
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /** Turns a plane code into a distance in pixels. */
    private static int planeDistance(int width, int code) {
        if (code > CODE_TO_PLANE.length) {
            return code - CODE_TO_PLANE.length;
        }
        int packed = CODE_TO_PLANE[code - 1];
        int distance = (packed >>> 4) * width + (8 - (packed & 0xf));
        return Math.max(distance, 1);
    }
}
