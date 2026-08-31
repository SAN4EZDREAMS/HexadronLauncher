package com.hexadron.launcher.ui;

import com.hexadron.launcher.mods.LocalModInfo;
import com.hexadron.launcher.mods.ModEntry;
import com.hexadron.launcher.net.Http;
import com.hexadron.launcher.util.Hashes;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The little picture beside a mod.
 *
 * <h2>Why bother</h2>
 *
 * <p>A list of mods is a list of names of things the user chose on a website
 * where each of them had a logo. Sodium's leaf, JEI's cake, Iris's eye - that is
 * how they are recognised, and a column of identical grey rows is a list that
 * has to be read word by word to find one entry.
 *
 * <h2>Where a picture comes from, in order</h2>
 *
 * <ol>
 *   <li>the platform's logo, recorded when the mod was installed or looked up;</li>
 *   <li>the icon inside the jar, which every Fabric mod and most others ship
 *       precisely so that a launcher can show one;</li>
 *   <li>a tile with the mod's first letter, coloured from its name - which is
 *       not a picture of the mod, and is a stable, distinguishable mark rather
 *       than another grey square.</li>
 * </ol>
 *
 * <p>The third one is drawn immediately and the others replace it when they
 * arrive, so a row never appears empty and the list never waits on a network.
 *
 * <h2>Loading, and what it must not do</h2>
 *
 * <p>Nothing is decoded on the interface thread and nothing is fetched twice.
 * Downloads go through {@link Http}, not through JavaFX's own URL loading, so
 * that a user behind a proxy sees logos too. They land in the cache folder under
 * a name taken from the URL's digest, which makes the second start of the
 * launcher offline-clean: the pictures are already there.
 *
 * <p>List cells are recycled while a load is in flight, so every load carries
 * the row it was started for and a result whose row has since been given to
 * another mod is discarded. Without that, scrolling a long list quickly leaves
 * logos sitting beside the wrong names.
 */
public final class ModIcons {

    /** How large a cached picture is decoded, in pixels. Twice the drawn size, for scaled displays. */
    private static final int DECODE_SIZE = 96;

    /**
     * Two threads.
     *
     * <p>Enough that a screenful of rows fills quickly, few enough that scrolling
     * a folder of two hundred mods cannot open two hundred connections. The
     * queue is unbounded and the work is small, so the visible rows are served
     * within a moment either way.
     */
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "hexadron-mod-icons");
        thread.setDaemon(true);
        return thread;
    });

    /** Decoded pictures, by source. Bounded, because a mods folder is not. */
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 512;

    /** Where fetched pictures are kept between runs. Null until the launcher says. */
    private static volatile Path cacheDir;

    private ModIcons() {
    }

    /**
     * Where to keep fetched logos.
     *
     * <p>Set once at start-up. Until it is, pictures are still fetched and shown,
     * they are simply fetched again next time - which is the right behaviour for
     * a launcher that has not worked out where its data folder is yet.
     */
    public static void cacheDirectory(Path directory) {
        cacheDir = directory;
    }

    /**
     * The picture for one mod, ready to be put in a row.
     *
     * <p>Created once per list cell and re-pointed at whatever mod that cell is
     * currently showing, which is how JavaFX list cells are meant to be used and
     * is why {@link Tile#show} exists instead of a factory method per row.
     */
    public static final class Tile extends StackPane {

        private final double size;
        private final Label letter = new Label();
        private final ImageView image = new ImageView();

        /** The mod this tile is currently for. A load for anything else is stale. */
        private String token;

        public Tile(double size) {
            this.size = size;
            setMinSize(size, size);
            setPrefSize(size, size);
            setMaxSize(size, size);
            getStyleClass().add("mod-icon");

            letter.getStyleClass().add("mod-icon-letter");
            letter.setStyle("-fx-font-size: " + Math.round(size * 0.45) + "px;");

            image.setFitWidth(size);
            image.setFitHeight(size);
            image.setPreserveRatio(true);
            image.setSmooth(true);
            image.setVisible(false);

            getChildren().addAll(letter, image);
        }

        /** Shows the logo for a mod already in the folder. */
        public void show(ModEntry entry) {
            if (entry == null) {
                showLetter("?");
                return;
            }
            showLetter(entry.title());
            String key = entry.key();
            token = key;
            if (entry.iconUrl() != null && !entry.iconUrl().isBlank()) {
                loadRemote(entry.iconUrl(), key);
                return;
            }
            if (entry.iconJarPath() != null) {
                loadFromJar(entry.path(), entry.iconJarPath(), key);
            }
        }

        /** Shows the logo for a search result, which has a URL and nothing else. */
        public void show(String iconUrl, String title) {
            showLetter(title);
            String key = iconUrl == null ? title : iconUrl;
            token = key;
            if (iconUrl != null && !iconUrl.isBlank()) {
                loadRemote(iconUrl, key);
            }
        }

        private void showLetter(String title) {
            image.setVisible(false);
            image.setImage(null);
            String text = title == null || title.isBlank()
                    ? "?"
                    : title.strip().substring(0, 1).toUpperCase(Locale.ROOT);
            letter.setText(text);
            setStyle("-fx-background-color: " + colourFor(title) + ";");
        }

        private void loadRemote(String url, String key) {
            Image cached = CACHE.get(url);
            if (cached != null) {
                apply(cached, key);
                return;
            }
            LOADER.execute(() -> {
                Optional<Image> loaded = fetch(url);
                loaded.ifPresent(picture -> {
                    remember(url, picture);
                    Platform.runLater(() -> apply(picture, key));
                });
            });
        }

        private void loadFromJar(Path jar, String iconPath, String key) {
            String cacheKey = jar + "!" + iconPath;
            Image cached = CACHE.get(cacheKey);
            if (cached != null) {
                apply(cached, key);
                return;
            }
            LOADER.execute(() -> LocalModInfo.readIcon(jar, iconPath)
                    .flatMap(ModIcons::decode)
                    .ifPresent(picture -> {
                        remember(cacheKey, picture);
                        Platform.runLater(() -> apply(picture, key));
                    }));
        }

        /** Puts a picture in place, unless this cell has moved on to another mod. */
        private void apply(Image picture, String key) {
            if (!key.equals(token)) {
                return;
            }
            image.setImage(picture);
            image.setVisible(true);
        }
    }

    // ---------------------------------------------------------------- fetching

    /** A picture from the web, through the disk cache. */
    private static Optional<Image> fetch(String url) {
        Path file = cacheFileFor(url);
        if (file != null && Files.isRegularFile(file)) {
            try {
                Optional<Image> cached = decode(Files.readAllBytes(file));
                if (cached.isPresent()) {
                    return cached;
                }
            } catch (IOException ignored) {
                // A half-written cache file is replaced by fetching again.
            }
        }
        byte[] bytes;
        try {
            bytes = Http.getBytes(Http.requireHttps(url));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            // A logo that will not load is not worth a message. The lettered
            // tile is already on screen and says the same thing the name does.
            return Optional.empty();
        }
        store(file, bytes);
        return decode(bytes);
    }

    private static Optional<Image> decode(byte[] bytes) {
        try {
            Image image = new Image(new ByteArrayInputStream(bytes),
                    DECODE_SIZE, DECODE_SIZE, true, true);
            return image.isError() || image.getWidth() <= 0 ? Optional.empty() : Optional.of(image);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static void store(Path file, byte[] bytes) {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            // Written beside and moved into place, so that a launcher closed
            // mid-download does not leave a truncated picture behind to be read
            // as a real one on the next start.
            Path temporary = file.resolveSibling(file.getFileName() + ".part");
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // The picture is already in hand; failing to keep it is not a failure.
        }
    }

    /**
     * The cache file for a URL.
     *
     * <p>Named from the digest of the URL rather than from anything in it. The
     * URL comes from a third party and its last segment is not a name this code
     * gets to trust with a file path.
     */
    private static Path cacheFileFor(String url) {
        Path directory = cacheDir;
        if (directory == null) {
            return null;
        }
        return directory.resolve(Hashes.sha1(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void remember(String key, Image picture) {
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(key, picture);
    }

    /**
     * A colour for the lettered tile, from the name.
     *
     * <p>Fixed saturation and lightness so every tile sits at the same weight
     * against the background in both themes; only the hue moves, and it moves
     * with the name, so a mod keeps its colour between runs.
     */
    private static String colourFor(String title) {
        int hash = title == null ? 0 : title.toLowerCase(Locale.ROOT).hashCode();
        double hue = Math.floorMod(hash, 360);
        Color colour = Color.hsb(hue, 0.42, 0.55);
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                Math.round(colour.getRed() * 255),
                Math.round(colour.getGreen() * 255),
                Math.round(colour.getBlue() * 255));
    }
}
