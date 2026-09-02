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

package com.hexadron.launcher.ui;

import com.hexadron.launcher.install.loader.LoaderType;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The small mark that says which loader a profile uses.
 *
 * <h2>Drawn, not shipped</h2>
 *
 * <p>Fabric, Quilt, Forge and NeoForge each have their own logo, and none of
 * them is this project's to redistribute - they are the projects' trade marks,
 * under their own licences. So these are original marks: a tile per loader, in
 * that loader's own colours, carrying a plain geometric device. They are meant
 * to be told apart at sixteen pixels, which is the only job they have.
 *
 * <p>Anybody who does have the right to use the real logos can drop them in
 * without touching this class. A PNG at {@code /ui/loader/<loader id>.png} in
 * the launcher's resources replaces the drawn mark for that loader, and
 * scaling is left off so pixel art stays pixel art.
 *
 * <h2>Nodes, not images</h2>
 *
 * <p>Each mark is a live group of shapes rather than an {@link Image}, and that
 * is deliberate: taking a picture of a drawing means {@code Canvas.snapshot},
 * and a canvas that has never been in a scene has never been rendered, so what
 * comes back is blank. {@link Brand} carries the scar from learning that. A
 * node is put in the scene and therefore drawn, at whatever size and display
 * scale it ends up at.
 */
public final class LoaderIcon {

    /** Where an override lives, with {@code %s} for the loader id. */
    private static final String OVERRIDE = "/ui/loader/%s.png";

    /** One entry per loader; absent means "checked, nothing there". */
    private static final Map<String, Image> OVERRIDES = new HashMap<>();

    private LoaderIcon() {
    }

    /**
     * The mark for a loader at the given edge length, in pixels.
     *
     * <p>A fresh node every call. JavaFX nodes belong to one parent, so a shared
     * instance would vanish from the list the moment the grid used it.
     */
    public static javafx.scene.Node node(LoaderType loader, double size) {
        LoaderType type = loader == null ? LoaderType.VANILLA : loader;
        Image override = override(type);
        if (override != null) {
            ImageView view = new ImageView(override);
            view.setFitWidth(size);
            view.setFitHeight(size);
            view.setPreserveRatio(true);
            // Pixel art enlarged with smoothing turns to mush; the marks these
            // replace are most likely to be 16 or 32 pixels square.
            view.setSmooth(false);
            StackPane holder = new StackPane(view);
            fix(holder, size);
            return holder;
        }
        return drawn(type, size);
    }

    private static synchronized Image override(LoaderType loader) {
        if (OVERRIDES.containsKey(loader.id())) {
            return OVERRIDES.get(loader.id());
        }
        Image image = null;
        try (InputStream in = LoaderIcon.class.getResourceAsStream(OVERRIDE.formatted(loader.id()))) {
            if (in != null) {
                Image loaded = new Image(in);
                // A failed decode gives an Image that reports the error instead
                // of throwing, and putting one on screen is how a mark ends up
                // as an empty square nobody can explain.
                image = (loaded.isError() || loaded.getWidth() <= 0) ? null : loaded;
            }
        } catch (Exception ignored) {
            image = null;
        }
        OVERRIDES.put(loader.id(), image);
        return image;
    }

    // ---------------------------------------------------------------- drawing

    /**
     * All coordinates below are in a sixteen-unit square, the size Minecraft's
     * own item textures use, and are scaled to the requested size on the way
     * out. Writing them in pixels would mean a second set of numbers for the
     * list rows and the grid cells.
     */
    private static final double UNITS = 16;

    private static javafx.scene.Node drawn(LoaderType loader, double size) {
        Pane pane = new Pane();
        fix(pane, size);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);

        switch (loader) {
            case VANILLA -> vanilla(pane, size);
            case FABRIC -> fabric(pane, size);
            case QUILT -> quilt(pane, size);
            case FORGE -> anvil(pane, size, Color.web("#39404a"), Color.web("#cfd6e0"), false);
            case NEOFORGE -> anvil(pane, size, Color.web("#9c5418"), Color.web("#f6e6d2"), true);
        }
        return pane;
    }

    /** A grass block seen flat: green over earth. The mark for "no loader". */
    private static void vanilla(Pane pane, double size) {
        tile(pane, size, Color.web("#6b4a2f"));
        pane.getChildren().add(rect(size, 1, 1, 14, 6, Color.web("#5d9c46")));
        pane.getChildren().add(rect(size, 1, 6.4, 14, 1.2, Color.web("#4a7c38")));
        // Two clods in the earth, so the lower half is not a flat brown field.
        pane.getChildren().add(rect(size, 3.5, 9, 3, 2, Color.web("#7d5837")));
        pane.getChildren().add(rect(size, 9, 11, 3.5, 2, Color.web("#5b3f28")));
        bevel(pane, size);
    }

    /**
     * Two threads over two threads, interlaced.
     *
     * <p>The interlacing is z-order, not geometry: horizontal, vertical,
     * horizontal, vertical, each drawn over the last, which is exactly what a
     * weave looks like from a distance and costs four rectangles.
     */
    private static void fabric(Pane pane, double size) {
        tile(pane, size, Color.web("#7c5a30"));
        pane.getChildren().add(rect(size, 0.8, 3.2, 14.4, 3, Color.web("#dcc08a")));
        pane.getChildren().add(rect(size, 3.2, 0.8, 3, 14.4, Color.web("#c2a068")));
        pane.getChildren().add(rect(size, 0.8, 9.8, 14.4, 3, Color.web("#dcc08a")));
        pane.getChildren().add(rect(size, 9.8, 0.8, 3, 14.4, Color.web("#c2a068")));
        bevel(pane, size);
    }

    /** Four patches and a seam between them. */
    private static void quilt(Pane pane, double size) {
        tile(pane, size, Color.web("#2b2733"));
        pane.getChildren().add(patch(size, 1.2, 1.2, Color.web("#c060cf")));
        pane.getChildren().add(patch(size, 8.4, 1.2, Color.web("#8b53c4")));
        pane.getChildren().add(patch(size, 1.2, 8.4, Color.web("#8b53c4")));
        pane.getChildren().add(patch(size, 8.4, 8.4, Color.web("#d78fdd")));
        bevel(pane, size);
    }

    private static Rectangle patch(double size, double x, double y, Color fill) {
        Rectangle patch = rect(size, x, y, 6.4, 6.4, fill);
        patch.setArcWidth(scale(size, 2));
        patch.setArcHeight(scale(size, 2));
        return patch;
    }

    /**
     * An anvil: what a forge is for, and a shape that survives being sixteen
     * pixels wide. Forge and NeoForge share it and differ by colour, which is
     * also how their real logos differ at a glance.
     *
     * @param spark adds the struck-metal spark that tells the second one apart
     *              from the first even in greyscale
     */
    private static void anvil(Pane pane, double size, Color background, Color metal, boolean spark) {
        tile(pane, size, background);
        pane.getChildren().add(rect(size, 2, 4, 12, 2.4, metal));
        pane.getChildren().add(rect(size, 5.6, 6.4, 4.8, 3.6, metal.darker()));
        pane.getChildren().add(rect(size, 3.2, 10, 9.6, 2.4, metal));
        if (spark) {
            Polygon star = new Polygon();
            double unit = scale(size, 1);
            double cx = scale(size, 12.4);
            double cy = scale(size, 2.6);
            star.getPoints().addAll(
                    cx, cy - 2.1 * unit,
                    cx + 0.7 * unit, cy - 0.7 * unit,
                    cx + 2.1 * unit, cy,
                    cx + 0.7 * unit, cy + 0.7 * unit,
                    cx, cy + 2.1 * unit,
                    cx - 0.7 * unit, cy + 0.7 * unit,
                    cx - 2.1 * unit, cy,
                    cx - 0.7 * unit, cy - 0.7 * unit);
            star.setFill(Color.web("#ffd07a"));
            pane.getChildren().add(star);
        }
        bevel(pane, size);
    }

    /** An unknown loader id, so the row still shows something with a name in it. */
    public static javafx.scene.Node letter(String text, double size, Color background) {
        Pane pane = new Pane();
        fix(pane, size);
        tile(pane, size, background);
        Text glyph = new Text(text == null || text.isBlank()
                ? "?" : text.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        glyph.setFill(Color.WHITE);
        glyph.setFont(Font.font(Brand.FONT_FAMILY, FontWeight.BOLD, size * 0.6));
        glyph.setLayoutX((size - glyph.getLayoutBounds().getWidth()) / 2);
        glyph.setLayoutY(size / 2 + glyph.getLayoutBounds().getHeight() * 0.34);
        pane.getChildren().add(glyph);
        bevel(pane, size);
        return pane;
    }

    private static void tile(Pane pane, double size, Color fill) {
        Rectangle base = rect(size, 0, 0, UNITS, UNITS, fill);
        base.setArcWidth(scale(size, 3));
        base.setArcHeight(scale(size, 3));
        pane.getChildren().add(base);
    }

    /**
     * The one-pixel highlight and shadow that make a flat tile read as a block.
     *
     * <p>Drawn last, so it sits over whatever the mark put inside it, and kept
     * to a hairline: at sixteen pixels a two-pixel bevel is an eighth of the
     * icon.
     */
    private static void bevel(Pane pane, double size) {
        Rectangle edge = rect(size, 0, 0, UNITS, UNITS, Color.TRANSPARENT);
        edge.setArcWidth(scale(size, 3));
        edge.setArcHeight(scale(size, 3));
        edge.setStroke(Color.rgb(255, 255, 255, 0.16));
        edge.setStrokeWidth(Math.max(1, size / 24));
        pane.getChildren().add(edge);
    }

    private static Rectangle rect(double size, double x, double y,
                                 double width, double height, Color fill) {
        Rectangle rectangle = new Rectangle(
                scale(size, x), scale(size, y), scale(size, width), scale(size, height));
        rectangle.setFill(fill);
        return rectangle;
    }

    private static double scale(double size, double units) {
        return size * units / UNITS;
    }

    private static void fix(javafx.scene.layout.Region region, double size) {
        region.setMinSize(size, size);
        region.setPrefSize(size, size);
        region.setMaxSize(size, size);
    }
}
