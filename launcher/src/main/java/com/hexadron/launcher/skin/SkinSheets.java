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

package com.hexadron.launcher.skin;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Turning a picture into the sheet Minecraft will actually accept.
 *
 * <h2>The rule this exists for</h2>
 *
 * <p>Minecraft's own words, from {@code SkinTextureDownloader}:
 *
 * <pre>Discarding incorrectly sized (512x512) skin texture from ...</pre>
 *
 * <p>The client takes a skin sheet at 64x64, or at the pre-1.8 64x32 which it
 * converts itself, and <em>nothing else</em>. A high-resolution skin - which
 * every skin site offers, and which people upscale to draw on - is thrown away
 * with that one line in a log nobody opens, and the player gets the default
 * skin. Everything else works: the service answers, the file is fetched, the
 * signature checks out. The picture is simply the wrong size.
 *
 * <p>So the file the launcher serves to the game is normalised here. The file
 * on disk is left alone, because it is the one the preview draws and the one
 * the user chose; only what goes down the wire is resized.
 *
 * <h2>How it is resized</h2>
 *
 * <p>Averaged over whole blocks, in premultiplied alpha. A high-resolution skin
 * is almost always a 64x64 layout at 2x, 4x or 8x - either upscaled for
 * drawing, or drawn at that scale with the same block boundaries - so every
 * pixel of a block has the same colour and the average is exactly the pixel a
 * 64x64 version would have had. Where it is genuinely detailed art the average
 * is the honest answer, and a fringe of half-transparent pixels around a hat
 * edge is what premultiplying prevents: without it a transparent neighbour
 * drags the colour towards black.
 *
 * <p>Nothing is guessed. A sheet that is already the right size is returned
 * byte for byte, so the common case is not re-encoded, and a size that fits no
 * rule is passed through untouched rather than mangled into something the game
 * will reject differently.
 */
public final class SkinSheets {

    /** The width every sheet the game accepts has. */
    private static final int WIDTH = 64;

    private SkinSheets() {
    }

    /**
     * The bytes to hand the game.
     *
     * @param cape true for a cape sheet, which has its own shapes and its own
     *             pre-1.8 form
     * @return the file's own bytes when it is already a size the game takes,
     *         otherwise a re-encoded PNG
     */
    public static byte[] forGame(Path file, boolean cape) throws IOException {
        byte[] original = Files.readAllBytes(file);
        int[] size = PngSize.read(original);
        if (size == null) {
            return original;
        }

        int width = size[0];
        int height = size[1];

        // Already what the client wants. Returned untouched: re-encoding a file
        // that needs nothing done to it is a way to introduce a difference for
        // no reason.
        if (width == WIDTH && (height == WIDTH || height == WIDTH / 2)) {
            return original;
        }

        BufferedImage image = read(original);
        if (image == null) {
            return original;
        }

        if (cape && width == 22 && height == 17) {
            // The shape capes had before 1.8. The layout is identical, just
            // cropped, so it becomes a 64x32 sheet by being put back in the
            // corner it was cut from.
            return encode(onto(image, WIDTH, WIDTH / 2));
        }

        if (width % WIDTH != 0) {
            return original;
        }
        int factor = width / WIDTH;
        if (height != width && height * 2 != width) {
            return original;
        }

        return encode(shrink(image, factor));
    }

    /** True when {@link #forGame} would change this file. */
    public static boolean needsResizing(int width, int height, boolean cape) {
        if (width == WIDTH && (height == WIDTH || height == WIDTH / 2)) {
            return false;
        }
        if (cape && width == 22 && height == 17) {
            return true;
        }
        return width % WIDTH == 0 && width > WIDTH && (height == width || height * 2 == width);
    }

    /**
     * Averages each block down to one pixel, in premultiplied alpha.
     *
     * <p>Premultiplied because a skin sheet is mostly transparent: averaging
     * raw colour across a transparent neighbour pulls the result towards
     * whatever that neighbour's unused colour bytes happen to be, which is
     * usually black, and the visible edge of a hat picks up a dark rim.
     */
    private static BufferedImage shrink(BufferedImage source, int factor) {
        int width = source.getWidth() / factor;
        int height = source.getHeight() / factor;
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int pixels = factor * factor;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                long alpha = 0;
                long red = 0;
                long green = 0;
                long blue = 0;
                for (int dx = 0; dx < factor; dx++) {
                    for (int dy = 0; dy < factor; dy++) {
                        int argb = source.getRGB(x * factor + dx, y * factor + dy);
                        long a = (argb >>> 24) & 0xFF;
                        alpha += a;
                        red += ((argb >> 16) & 0xFF) * a;
                        green += ((argb >> 8) & 0xFF) * a;
                        blue += (argb & 0xFF) * a;
                    }
                }

                int argb;
                if (alpha == 0) {
                    argb = 0;
                } else {
                    argb = (int) ((alpha + pixels / 2) / pixels) << 24
                            | (int) ((red + alpha / 2) / alpha) << 16
                            | (int) ((green + alpha / 2) / alpha) << 8
                            | (int) ((blue + alpha / 2) / alpha);
                }
                out.setRGB(x, y, argb);
            }
        }
        return out;
    }

    /** The image in the top-left corner of a transparent sheet of this size. */
    private static BufferedImage onto(BufferedImage source, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < Math.min(width, source.getWidth()); x++) {
            for (int y = 0; y < Math.min(height, source.getHeight()); y++) {
                out.setRGB(x, y, source.getRGB(x, y));
            }
        }
        return out;
    }

    private static BufferedImage read(byte[] bytes) {
        try {
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
