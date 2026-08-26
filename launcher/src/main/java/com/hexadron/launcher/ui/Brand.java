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

/**
 * The launcher's mark and colours, drawn with JavaFX.
 *
 * <p>The icon used to be drawn with AWT, into a {@code BufferedImage}, and that
 * was one of the two expensive things about starting up. Touching
 * {@code Graphics2D} initialises Java2D, and asking it for {@code Font.SANS_SERIF}
 * metrics makes the platform font manager enumerate every installed font. On a
 * machine with a large font collection that is not a small pause, and it
 * happened before the window appeared, to produce one 64-pixel image.
 *
 * <p>JavaFX is already loaded by the time anything here is called and has its
 * own text and shape rendering, so the same drawing now costs a canvas and no
 * second toolkit.
 *
 * <p>A {@link Canvas} rather than laid-out nodes on purpose: a canvas renders
 * into its own buffer immediately and can be snapshotted without ever being
 * attached to a scene, which is exactly the situation here.
 *
 * <p>The tray icon still uses AWT, because {@code SystemTray} is an AWT API and
 * JavaFX has no equivalent - but that runs when the game starts, not when the
 * launcher does.
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

    private Brand() {
    }

    /** The window icon. */
    public static Image windowIcon() {
        return icon(64);
    }

    /**
     * The mark at an arbitrary size: a rounded accent square with a white H.
     *
     * <p>Drawn rather than loaded, for the same reason the tray icon is: one
     * bitmap is either blurry or the wrong size at some display scale, and a
     * drawing is sharp at all of them without shipping several files.
     */
    public static Image icon(int size) {
        Canvas canvas = new Canvas(size, size);
        GraphicsContext g = canvas.getGraphicsContext2D();

        double radius = size * 0.28;
        g.setFill(ACCENT);
        g.fillRoundRect(0, 0, size, size, radius, radius);

        Font font = Font.font(FONT_FAMILY, FontWeight.BOLD, size * 0.62);
        Text measure = new Text("H");
        measure.setFont(font);
        var bounds = measure.getLayoutBounds();

        g.setFill(Color.WHITE);
        g.setFont(font);
        // Placed from the measured bounds rather than by a text alignment mode,
        // because the two disagree by a pixel or two at small sizes and an icon
        // with an off-centre letter is the kind of thing that is only ever
        // noticed once it has shipped.
        g.fillText("H", (size - bounds.getWidth()) / 2, (size - bounds.getHeight()) / 2 - bounds.getMinY());

        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, new WritableImage(size, size));
    }
}
