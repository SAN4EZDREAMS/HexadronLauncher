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
import java.util.Set;
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
     * Four threads.
     *
     * <p>Enough that a screenful of search results fills at once, few enough
     * that scrolling a folder of two hundred mods cannot open two hundred
     * connections. Two was not enough and it showed: a page of forty results
     * queued forty fetches behind two workers, the queue is served oldest
     * first, and by the time a row's turn came the user had scrolled and the
     * result was thrown away - so the list filled in slowly and unevenly, which
     * reads as "some logos are missing". The other half of that fix is that a
     * task now checks it is still wanted before it opens a connection.
     */
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "hexadron-mod-icons");
        thread.setDaemon(true);
        return thread;
    });

    /** Decoded pictures, by source. Bounded, because a mods folder is not. */
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 512;

    /**
     * Addresses that did not yield a picture.
     *
     * <p>Remembered so that a project whose logo has been deleted, or a host
     * that is refusing, is asked once rather than on every scroll for the rest
     * of the session. It costs one row its logo; the alternative costs the
     * launcher a connection attempt every time that row is drawn.
     */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

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

        /**
         * The mod this tile is currently for. A load for anything else is stale.
         *
         * <p>Read by the loader threads and written by the interface thread, so
         * it is volatile: a worker that reads a stale value does needless work
         * at best and paints the wrong logo at worst.
         */
        private volatile String token;

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
                reset("?", null);
                return;
            }
            if (!reset(entry.title(), entry.key())) {
                return;
            }
            if (entry.iconUrl() != null && !entry.iconUrl().isBlank()) {
                loadRemote(entry.iconUrl(), entry.key());
                return;
            }
            if (entry.iconJarPath() != null) {
                loadFromJar(entry.path(), entry.iconJarPath(), entry.key());
            }
        }

        /** Shows the logo for a search result, which has a URL and nothing else. */
        public void show(String iconUrl, String title) {
            String key = iconUrl == null || iconUrl.isBlank() ? "title:" + title : iconUrl;
            if (!reset(title, key)) {
                return;
            }
            if (iconUrl != null && !iconUrl.isBlank()) {
                loadRemote(iconUrl, key);
            }
        }

        /**
         * Points the tile at a mod, and says whether anything needs loading.
         *
         * <p>The guard is the important half. A list cell is told to update far
         * more often than its contents change - on selection, on a repaint, and
         * on every {@code refresh()} the window makes after an install - and the
         * first version cleared the picture and queued another fetch each time.
         * A single install therefore blanked every logo in the catalogue and
         * re-fetched the lot, which is what "the pictures disappear" was.
         *
         * @return false when this tile is already showing that mod, and the
         *         caller should leave it alone
         */
        private boolean reset(String title, String key) {
            if (key != null && key.equals(token)) {
                return false;
            }
            token = key;
            image.setVisible(false);
            image.setImage(null);
            String text = title == null || title.isBlank()
                    ? "?"
                    : title.strip().substring(0, 1).toUpperCase(Locale.ROOT);
            letter.setText(text);
            letter.setVisible(true);
            setStyle("-fx-background-color: " + colourFor(title) + ";");
            return true;
        }

        private void loadRemote(String url, String key) {
            Image cached = CACHE.get(url);
            if (cached != null) {
                apply(cached, key);
                return;
            }
            if (FAILED.contains(url)) {
                return;
            }
            LOADER.execute(() -> {
                // Checked here rather than only at the end: by the time a worker
                // reaches a queued row the user may have scrolled past it, and
                // opening a connection for a row nobody is looking at is what
                // keeps the visible ones waiting.
                if (!key.equals(token)) {
                    return;
                }
                Optional<Image> loaded = fetch(url);
                if (loaded.isEmpty()) {
                    FAILED.add(url);
                    return;
                }
                remember(url, loaded.get());
                Platform.runLater(() -> apply(loaded.get(), key));
            });
        }

        private void loadFromJar(Path jar, String iconPath, String key) {
            String cacheKey = jar + "!" + iconPath;
            Image cached = CACHE.get(cacheKey);
            if (cached != null) {
                apply(cached, key);
                return;
            }
            LOADER.execute(() -> {
                if (!key.equals(token)) {
                    return;
                }
                LocalModInfo.readIcon(jar, iconPath)
                        .flatMap(ModIcons::decode)
                        .ifPresent(picture -> {
                            remember(cacheKey, picture);
                            Platform.runLater(() -> apply(picture, key));
                        });
            });
        }

        /** Puts a picture in place, unless this cell has moved on to another mod. */
        private void apply(Image picture, String key) {
            if (!key.equals(token)) {
                return;
            }
            image.setImage(picture);
            image.setVisible(true);
            // Out of the way rather than merely behind: a transparent logo would
            // otherwise be read on top of a letter.
            letter.setVisible(false);
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
