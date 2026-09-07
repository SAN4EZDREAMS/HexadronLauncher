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

package com.hexadron.launcher.ui;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.profile.Profile;
import com.hexadron.launcher.util.Hashes;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The picture shown for a profile, in both interfaces.
 *
 * <p>Two sources, in order: a picture the user chose, or otherwise the mark of
 * the loader the profile uses. Nothing else, and no third state - a profile
 * always has something to show, which is what lets the grid be a grid of icons
 * rather than a grid of icons and gaps.
 *
 * <h2>Chosen pictures are copied in</h2>
 *
 * <p>{@link #store} copies the file into {@code <root>/icons} under a name taken
 * from its own content, and the profile records only that name. Three things
 * follow, and each of them was a reason:
 *
 * <ul>
 *   <li>the icon survives the original being renamed, moved to the bin, or
 *       being on a memory stick that is not plugged in;</li>
 *   <li>two profiles given the same picture share one file rather than two
 *       copies, because the same bytes hash to the same name;</li>
 *   <li>nothing in {@code profiles.json} is ever opened as a path. A bare file
 *       name resolved inside one folder cannot be edited into
 *       {@code C:\Windows\...} or into somebody else's home directory.</li>
 * </ul>
 *
 * <h2>Any picture, at the right size</h2>
 *
 * <p>PNG, JPEG, GIF and BMP are accepted, transparency is kept, and an animated
 * GIF animates: the image is created from the file's URL and handed to an
 * {@link ImageView} that is only told how large to be. Nothing is resampled on
 * the way in, so a 512-pixel logo and a 16-pixel pixel-art tile both end up
 * drawn at the size the interface asked for, in proportion, with the spare
 * space transparent rather than stretched.
 */
public final class ProfileIcons {

    /** Extensions {@link #store} accepts, lower case, with the dot. */
    public static final List<String> EXTENSIONS =
            List.of(".png", ".jpg", ".jpeg", ".gif", ".bmp");

    /**
     * Largest file accepted, in bytes.
     *
     * <p>Eight megabytes is far more than an icon needs and still small enough
     * that a mistake - a photograph, a video frame sequence saved as a GIF -
     * cannot fill the data folder or hold up the interface while it decodes.
     */
    public static final long MAXIMUM_BYTES = 8L * 1024 * 1024;

    /** Decoded pictures, keyed by file and modification time. */
    private static final Map<String, Image> CACHE = new HashMap<>();

    private ProfileIcons() {
    }

    /**
     * The icon for a profile at the given edge length, in pixels.
     *
     * <p>Falls back to the loader mark when the chosen picture has gone missing
     * or will not decode. A profile whose icon file was deleted by hand is a
     * profile with the wrong picture, not a profile that cannot be shown.
     */
    public static Node node(Profile profile, GameDirs dirs, double size) {
        if (profile == null) {
            return LoaderIcon.node(LoaderType.VANILLA, size);
        }
        if (profile.hasCustomIcon() && dirs != null) {
            Image image = load(dirs.icons().resolve(profile.customIcon()));
            if (image != null) {
                return view(image, size);
            }
        }
        return LoaderIcon.node(iconLoader(profile), size);
    }

    /**
     * Which loader mark a profile shows: the one it runs, or the one it pins.
     *
     * <p>Pinning exists because a profile is not always what its loader says it
     * is - a Fabric instance that is really a server's modpack is easier to find
     * in a grid of thirty if it does not look like every other Fabric instance.
     */
    public static LoaderType iconLoader(Profile profile) {
        if (profile == null || profile.iconFollowsLoader()) {
            return profile == null ? LoaderType.VANILLA : profile.loader();
        }
        try {
            return LoaderType.fromId(profile.icon());
        } catch (IllegalArgumentException e) {
            // A hand-edited icon value. The loader it actually uses is a better
            // answer than a question mark.
            return profile.loader();
        }
    }

    /** An {@link ImageView} sized to fit, in proportion, without resampling on load. */
    public static Node view(Image image, double size) {
        ImageView view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        // Off for the same reason as in LoaderIcon: most instance icons people
        // choose are pixel art, and smoothing a 16-pixel tile up to 48 blurs it.
        view.setSmooth(false);
        StackPane holder = new StackPane(view);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        return holder;
    }

    /** A decoded picture from the icons folder, or null when there is none to show. */
    public static Image load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        String key;
        try {
            key = file.toAbsolutePath() + "@" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            key = file.toAbsolutePath().toString();
        }
        synchronized (CACHE) {
            if (CACHE.containsKey(key)) {
                return CACHE.get(key);
            }
        }
        Image image = null;
        try {
            // From the URL rather than from a stream, and with no requested
            // width or height: both of those turn an animated GIF into its
            // first frame, and the point of accepting GIF is that it moves.
            Image loaded = new Image(file.toUri().toString(), false);
            image = (loaded.isError() || loaded.getWidth() <= 0) ? null : loaded;
        } catch (Exception ignored) {
            image = null;
        }
        synchronized (CACHE) {
            CACHE.put(key, image);
        }
        return image;
    }

    /**
     * Copies a chosen picture into the launcher's icons folder.
     *
     * @return the file name to store on the profile
     * @throws IOException when the file is not a picture this launcher accepts,
     *                     is too large, or cannot be copied. The message is
     *                     shown to the user, so it says which of the three.
     */
    public static String store(Path source, GameDirs dirs) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("no such file: " + source);
        }
        String extension = extensionOf(source.getFileName().toString());
        if (extension == null) {
            throw new IOException("not a picture this launcher reads: "
                    + source.getFileName() + " (accepted: " + String.join(", ", EXTENSIONS) + ")");
        }
        long bytes = Files.size(source);
        if (bytes > MAXIMUM_BYTES) {
            throw new IOException("the picture is " + (bytes / (1024 * 1024))
                    + " MB; the limit is " + (MAXIMUM_BYTES / (1024 * 1024)) + " MB");
        }

        // Decoded before it is copied, not after. A file named .png that is not
        // a PNG must fail here, while the user is still looking at the file
        // chooser, rather than become a profile with an empty square on it.
        Image probe = new Image(source.toUri().toString(), false);
        if (probe.isError() || probe.getWidth() <= 0) {
            throw new IOException("the file could not be read as a picture: "
                    + source.getFileName());
        }

        Files.createDirectories(dirs.icons());
        String name = Hashes.sha1(source).substring(0, 16) + extension;
        Path target = dirs.icons().resolve(name);
        if (!Files.exists(target)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return name;
    }

    /**
     * Keeps a picture that arrived over the network, and returns its file name.
     *
     * <p>{@link #store} is for a file the user chose in a file chooser: it trusts
     * the name, because the user typed the path and can see what they picked.
     * This is for a logo fetched from a platform, where there is no name worth
     * trusting - a Modrinth logo address ends in {@code .png} whatever the bytes
     * behind it are - so the format is read out of the first few bytes instead.
     *
     * <p>WebP is converted rather than refused. Modrinth serves a good part of
     * its logos as WebP, JavaFX cannot read one, and a profile whose picture
     * silently failed to arrive is the bug this method exists to avoid; the
     * launcher already has a lossless WebP decoder for the mod rows, so the
     * bitmap it produces is written out as a PNG. The lossy kind cannot be
     * decoded and is refused like anything else unreadable.
     *
     * @param bytes what the platform sent
     * @param dirs  where the icons folder is
     * @return the file name to store on the profile
     * @throws IOException when the bytes are too large, are not a picture this
     *                     launcher can read, or cannot be written
     */
    public static String storeFetched(byte[] bytes, GameDirs dirs) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("the logo came back empty");
        }
        if (bytes.length > MAXIMUM_BYTES) {
            throw new IOException("the picture is " + (bytes.length / (1024 * 1024))
                    + " MB; the limit is " + (MAXIMUM_BYTES / (1024 * 1024)) + " MB");
        }

        byte[] picture = bytes;
        String extension = extensionOfBytes(picture);
        if (extension == null && com.hexadron.launcher.util.Webp.isWebp(picture)) {
            picture = pngFromWebp(picture);
            extension = ".png";
        }
        if (extension == null) {
            throw new IOException("the logo is not a picture this launcher reads");
        }

        // Decoded before it is kept, not after, exactly as for a chosen file: a
        // profile with an empty square on it is worse than a profile that kept
        // the loader's mark.
        Image probe = new Image(new ByteArrayInputStream(picture));
        if (probe.isError() || probe.getWidth() <= 0) {
            throw new IOException("the logo could not be read as a picture");
        }

        Files.createDirectories(dirs.icons());
        // Named from the content, like a chosen file, so two profiles made from
        // the same pack share one file rather than two copies of it.
        String name = Hashes.sha1(picture).substring(0, 16) + extension;
        Path target = dirs.icons().resolve(name);
        if (!Files.exists(target)) {
            Path temporary = target.resolveSibling(name + ".part");
            Files.write(temporary, picture);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return name;
    }

    /**
     * The format these bytes are in, as one of {@link #EXTENSIONS}, or null.
     *
     * <p>By signature, not by name. Every one of these is fixed by the format's
     * own specification and is the first thing in the file.
     */
    private static String extensionOfBytes(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') {
            return ".png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return ".jpg";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return ".gif";
        }
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
            return ".bmp";
        }
        return null;
    }

    /**
     * A lossless WebP as PNG bytes.
     *
     * <p>Through {@code java.awt} and {@code ImageIO}, which are in the JDK: the
     * launcher's own decoder produces straight ARGB, and writing a PNG is the
     * one step it does not do. Nothing on screen comes from this - it is a file
     * being written - so the toolkit it uses is not the interface's.
     */
    private static byte[] pngFromWebp(byte[] bytes) throws IOException {
        com.hexadron.launcher.util.Webp.Bitmap bitmap =
                com.hexadron.launcher.util.Webp.decode(bytes)
                        .orElseThrow(() -> new IOException(
                                "the logo is a WebP this launcher cannot decode"));
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                bitmap.width(), bitmap.height(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, bitmap.width(), bitmap.height(), bitmap.argb(), 0, bitmap.width());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(image, "png", out)) {
            throw new IOException("this Java has no PNG writer");
        }
        return out.toByteArray();
    }

    /** The accepted extension of a file name, lower case and with the dot, or null. */
    public static String extensionOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return extension;
            }
        }
        return null;
    }

    /** The glob patterns for a file chooser, e.g. {@code *.png}. */
    public static List<String> chooserPatterns() {
        return EXTENSIONS.stream().map(extension -> "*" + extension).toList();
    }

    /** A mark for something that is not a profile - used by the group rail. */
    public static Node letter(String text, double size, Color background) {
        return LoaderIcon.letter(text, size, background);
    }
}
