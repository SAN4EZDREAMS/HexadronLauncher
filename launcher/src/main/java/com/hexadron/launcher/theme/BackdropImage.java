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

package com.hexadron.launcher.theme;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The background picture: copied into the data folder, and prepared for
 * drawing.
 *
 * <p>Blurring and fading are done here, once, into a file, rather than by the
 * stylesheet. A stylesheet cannot blur a background at all, and an effect on
 * the window root would blur the window's contents along with it. The
 * prepared file is named after everything that went into it, so moving a
 * slider back to where it was finds the earlier result instead of making it
 * again.
 */
public final class BackdropImage {

    private BackdropImage() {
    }

    /** Larger pictures are refused: nobody's background needs to be a 25 MB file. */
    public static final long MAX_BYTES = 25L * 1024 * 1024;

    /** Prepared pictures are no wider or taller than this. */
    static final int MAX_SIDE = 2560;

    /** Where copied pictures live, inside the data folder. */
    public static final String FOLDER = "backgrounds";

    /**
     * Copies a picture into {@code <data>/backgrounds}, named by its content.
     *
     * @return the path to store in the settings, relative to the data folder
     * @throws IOException when it is too large, unreadable, or not a picture
     */
    public static String importPicture(Path source, Path dataRoot) throws IOException {
        String name = source.getFileName().toString();
        if (!Appearance.isPictureName(name)) {
            throw new IOException("not a PNG, JPEG, GIF or BMP picture: " + name);
        }
        long size = Files.size(source);
        if (size > MAX_BYTES) {
            throw new IOException("larger than 25 MB: " + name);
        }
        byte[] bytes = Files.readAllBytes(source);
        return importBytes(bytes, extension(name), dataRoot);
    }

    /** The same, for a picture that came out of a theme file. */
    public static String importBytes(byte[] bytes, String extension, Path dataRoot) throws IOException {
        if (bytes.length > MAX_BYTES) {
            throw new IOException("larger than 25 MB");
        }
        String ext = extension.toLowerCase(Locale.ROOT).replace("jpeg", "jpg");
        if (!Appearance.isPictureName("x." + ext)) {
            throw new IOException("not a PNG, JPEG, GIF or BMP picture");
        }
        if (read(bytes) == null) {
            throw new IOException("the file is not a picture this launcher can read");
        }
        String relative = FOLDER + "/" + sha1(bytes).substring(0, 16) + "." + ext;
        Path target = dataRoot.resolve(relative);
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            Files.write(partial, bytes);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return relative;
    }

    /**
     * The picture as it is drawn: scaled down to {@link #MAX_SIDE}, laid on the
     * window colour (for pictures with transparency), blurred and faded.
     *
     * @return the prepared file in {@code cacheDir}; made only if it is not there yet
     */
    public static Path prepare(Path picture, Path cacheDir, String windowColor, int dim, int blur)
            throws IOException {
        String colour = Palette.parse(windowColor) == null ? "#000000" : Palette.parse(windowColor);
        String base = picture.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path target = cacheDir.resolve("bg-" + base + "-" + colour.substring(1) + "-d" + dim + "-b" + blur + ".jpg");
        if (Files.isRegularFile(target)) {
            return target;
        }
        BufferedImage image = read(Files.readAllBytes(picture));
        if (image == null) {
            throw new IOException("the background picture cannot be read: " + picture.getFileName());
        }
        BufferedImage flat = flatten(image, colour);
        if (blur > 0) {
            flat = blur(flat, blur);
        }
        if (dim > 0) {
            fade(flat, colour, dim / 100.0);
        }
        Files.createDirectories(cacheDir);
        Path partial = cacheDir.resolve(target.getFileName() + ".part");
        if (!javax.imageio.ImageIO.write(flat, "jpg", partial.toFile())) {
            throw new IOException("no JPEG writer");
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * A theme's pattern tile as it is drawn: laid on the window colour and
     * faded, at its own size. Written as PNG, not JPEG: the tiles have hard
     * edges, and JPEG would ring around every one of them.
     *
     * @param tile the PNG from the jar
     * @param id   the theme, for the file name
     * @return the prepared file in {@code cacheDir}; made only if it is not there yet
     */
    public static Path prepareTile(byte[] tile, String id, Path cacheDir, String windowColor, int dim)
            throws IOException {
        String colour = Palette.parse(windowColor) == null ? "#000000" : Palette.parse(windowColor);
        String safeId = id.replaceAll("[^a-z0-9]", "");
        Path target = cacheDir.resolve("bg-tile-" + safeId + "-" + sha1(tile).substring(0, 8) + "-"
                + colour.substring(1) + "-d" + dim + ".png");
        if (Files.isRegularFile(target)) {
            return target;
        }
        BufferedImage image = read(tile);
        if (image == null) {
            throw new IOException("the pattern of theme " + safeId + " cannot be read");
        }
        BufferedImage flat = flatten(image, colour);
        if (dim > 0) {
            fade(flat, colour, dim / 100.0);
        }
        Files.createDirectories(cacheDir);
        Path partial = cacheDir.resolve(target.getFileName() + ".part");
        if (!javax.imageio.ImageIO.write(flat, "png", partial.toFile())) {
            throw new IOException("no PNG writer");
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static BufferedImage read(byte[] bytes) throws IOException {
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            return javax.imageio.ImageIO.read(in);
        }
    }

    /** Opaque RGB, no larger than {@link #MAX_SIDE}, over the window colour. */
    static BufferedImage flatten(BufferedImage image, String colour) {
        double scale = Math.min(1.0, MAX_SIDE / (double) Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            int[] c = Palette.rgb(colour);
            g.setColor(new java.awt.Color(c[0], c[1], c[2]));
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Three box blurs, which together are close to a Gaussian of that radius.
     * Done on a picture a quarter of the size when the radius is large: a
     * blurred picture has no detail for the full size to keep, and it is
     * sixteen times less work.
     */
    static BufferedImage blur(BufferedImage image, int radius) {
        int factor = radius >= 12 ? 4 : radius >= 6 ? 2 : 1;
        BufferedImage work = image;
        if (factor > 1) {
            work = resize(image, Math.max(1, image.getWidth() / factor), Math.max(1, image.getHeight() / factor));
        }
        int r = Math.max(1, Math.round(radius / (float) factor / 1.7f));
        int w = work.getWidth();
        int h = work.getHeight();
        int[] pixels = work.getRGB(0, 0, w, h, null, 0, w);
        int[] buffer = new int[pixels.length];
        for (int pass = 0; pass < 3; pass++) {
            boxHorizontal(pixels, buffer, w, h, r);
            boxVertical(buffer, pixels, w, h, r);
        }
        BufferedImage blurred = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        blurred.setRGB(0, 0, w, h, pixels, 0, w);
        return factor > 1 ? resize(blurred, image.getWidth(), image.getHeight()) : blurred;
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void boxHorizontal(int[] in, int[] out, int w, int h, int r) {
        int span = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + Math.max(0, Math.min(w - 1, i))];
                sr += (p >> 16) & 0xff;
                sg += (p >> 8) & 0xff;
                sb += p & 0xff;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = (sr / span) << 16 | (sg / span) << 8 | (sb / span);
                int add = in[row + Math.min(w - 1, x + r + 1)];
                int remove = in[row + Math.max(0, x - r)];
                sr += ((add >> 16) & 0xff) - ((remove >> 16) & 0xff);
                sg += ((add >> 8) & 0xff) - ((remove >> 8) & 0xff);
                sb += (add & 0xff) - (remove & 0xff);
            }
        }
    }

    private static void boxVertical(int[] in, int[] out, int w, int h, int r) {
        int span = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[Math.max(0, Math.min(h - 1, i)) * w + x];
                sr += (p >> 16) & 0xff;
                sg += (p >> 8) & 0xff;
                sb += p & 0xff;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = (sr / span) << 16 | (sg / span) << 8 | (sb / span);
                int add = in[Math.min(h - 1, y + r + 1) * w + x];
                int remove = in[Math.max(0, y - r) * w + x];
                sr += ((add >> 16) & 0xff) - ((remove >> 16) & 0xff);
                sg += ((add >> 8) & 0xff) - ((remove >> 8) & 0xff);
                sb += (add & 0xff) - (remove & 0xff);
            }
        }
    }

    /** Every pixel moved {@code amount} of the way to the window colour. */
    static void fade(BufferedImage image, String colour, double amount) {
        int[] c = Palette.rgb(colour);
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (int) Math.round(((p >> 16) & 0xff) + (c[0] - ((p >> 16) & 0xff)) * amount);
            int g = (int) Math.round(((p >> 8) & 0xff) + (c[1] - ((p >> 8) & 0xff)) * amount);
            int b = (int) Math.round((p & 0xff) + (c[2] - (p & 0xff)) * amount);
            pixels[i] = r << 16 | g << 8 | b;
        }
        image.setRGB(0, 0, w, h, pixels, 0, w);
    }

    /** Deletes prepared pictures other than {@code keep}. Errors are ignored: it is a cache. */
    public static void prune(Path cacheDir, Path keep) {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        try (var files = Files.list(cacheDir)) {
            files.filter(file -> file.getFileName().toString().startsWith("bg-"))
                    .filter(file -> keep == null || !file.equals(keep))
                    .forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException ignored) {
                            // In use, or gone already.
                        }
                    });
        } catch (IOException ignored) {
            // Nothing to tidy.
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
