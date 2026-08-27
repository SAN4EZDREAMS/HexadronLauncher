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
