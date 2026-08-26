package com.hexadron.launcher.ui;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's mark and colours.
 *
 * <h2>Why the window icon is loaded and not drawn</h2>
 *
 * <p>It was drawn, briefly, and the result was a title bar showing Windows'
 * generic application icon - the little white window frame it falls back to when
 * a window supplies none. The drawing came from {@link Canvas#snapshot}, and a
 * canvas that has never belonged to a scene has never been through a render
 * pass, so what came back was an empty image. An empty image is not an error, so
 * nothing complained; it simply was not an icon.
 *
 * <p>They are PNG resources now, at the sizes Windows actually asks for.
 * Loading them is not slower than drawing - decoding a 16-pixel PNG is nothing -
 * and it fixes a second thing at the same time: a window icon list with one
 * 64-pixel entry made the platform downscale to 16 for the title bar, and a
 * letter drawn at 64 and squeezed to 16 is mush. Each size is rendered
 * separately, from four times its own size, so the crossbar of the H is a whole
 * pixel where it has to be.
 *
 * <p>The files are generated, not hand-drawn: {@code launcher/packaging/
 * make-icons.py} writes them and the application icons from the same constants
 * that {@link #icon(int)} uses below, so the tab, the taskbar, the executable
 * and the splash cannot drift apart.
 *
 * <p>The tray icon is the one thing still drawn with AWT, because
 * {@code SystemTray} is an AWT API and JavaFX has no equivalent - but that runs
 * when the game starts, not when the launcher does.
 */
public final class Brand {

    /** The accent green, matching {@code -fx-accent-0} in the stylesheet. */
    public static final Color ACCENT = Color.web("#2d7d46");

    /** The lighter accent, matching {@code -fx-accent-1}. */
    public static final Color ACCENT_LIGHT = Color.web("#359152");

    /** Window background, matching {@code -fx-base-0}. */
    public static final Color SURFACE = Color.web("#14161a");

    /** Panel background, matching {@code -fx-base-1}. */
    public static final Color PANEL = Color.web("#1b1e24");

    /** Borders and dividers, matching {@code -fx-base-3}. */
    public static final Color LINE = Color.web("#2f343d");

    /** Primary text, matching {@code -fx-text-0}. */
    public static final Color TEXT = Color.web("#e6e8ec");

    /** Secondary text, matching {@code -fx-text-1}. */
    public static final Color MUTED = Color.web("#9aa2b1");

    /** The font stack the stylesheet uses, so drawn text matches laid-out text. */
    public static final String FONT_FAMILY = "Segoe UI";

    /** Corner radius as a fraction of the icon's size. */
    private static final double CORNER_RADIUS = 0.28;

    /** Cap height as a fraction of the icon's size. */
    private static final double CAP_HEIGHT = 0.62;

    /** Icon sizes shipped as resources, smallest first. */
    public static final List<Integer> ICON_SIZES = List.of(16, 24, 32, 48, 64, 128);

    /** Where those resources live, with {@code %d} for the size. */
    public static final String ICON_RESOURCE = "/ui/icon/icon-%d.png";

    private static List<Image> windowIcons;

    private Brand() {
    }

    /**
     * Every size of the window icon, smallest first.
     *
     * <p>Handed to {@code Stage.getIcons()} as a set rather than one image: the
     * platform picks the nearest size for each place it shows the icon - the
     * title bar, the taskbar, alt-tab - and given only one it downscales, badly.
     *
     * <p>Loaded once. A {@code Stage} keeps its own reference and the images are
     * immutable, so several windows can share them.
     */
    public static synchronized List<Image> windowIcons() {
        if (windowIcons != null) {
            return windowIcons;
        }
        List<Image> loaded = new ArrayList<>();
        for (int size : ICON_SIZES) {
            Image image = load(size);
            if (image != null) {
                loaded.add(image);
            }
        }
        if (loaded.isEmpty()) {
            // A build whose resources did not make it into the jar. Drawing is
            // not reliable enough to be the normal path, but a mark that might
            // be blank still beats the platform's generic one.
            loaded.add(icon(64));
        }
        windowIcons = List.copyOf(loaded);
        return windowIcons;
    }

    private static Image load(int size) {
        String path = ICON_RESOURCE.formatted(size);
        try (InputStream in = Brand.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            Image image = new Image(in);
            // A decode failure yields an Image that reports the error rather
            // than throwing, and adding one to a window is how a title bar ends
            // up with nothing in it.
            return (image.isError() || image.getWidth() <= 0) ? null : image;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Draws the mark at an arbitrary size.
     *
     * <p>Kept for sizes there is no resource for, and as the fallback above. Not
     * the normal path: see the class comment for what a snapshot of a detached
     * canvas is worth.
     */
    public static Image icon(int size) {
        Canvas canvas = new Canvas(size, size);
        GraphicsContext g = canvas.getGraphicsContext2D();

        double radius = size * CORNER_RADIUS;
        g.setFill(ACCENT);
        g.fillRoundRect(0, 0, size, size, radius, radius);

        Font font = Font.font(FONT_FAMILY, FontWeight.BOLD, size * CAP_HEIGHT);
        Text measure = new Text("H");
        measure.setFont(font);
        var bounds = measure.getLayoutBounds();

        g.setFill(Color.WHITE);
        g.setFont(font);
        // Placed from the measured bounds rather than by a text alignment mode,
        // because the two disagree by a pixel or two at small sizes and an icon
        // with an off-centre letter is the kind of thing that is only ever
        // noticed once it has shipped.
        g.fillText("H", (size - bounds.getWidth()) / 2,
                (size - bounds.getHeight()) / 2 - bounds.getMinY());

        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, new WritableImage(size, size));
    }
}
