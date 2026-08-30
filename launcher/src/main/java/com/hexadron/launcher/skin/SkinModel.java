package com.hexadron.launcher.skin;

import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

import java.util.List;

/**
 * The player figure, built out of the skin sheet.
 *
 * <p>Six-sided boxes with the sheet's rectangles pinned to their sides, exactly
 * as the game draws them - which is why the result looks like the player rather
 * than like an approximation of one. {@link SkinLayout} says which rectangle
 * goes where; this turns that into geometry.
 */
public final class SkinModel {

    /**
     * How far the overlay layers stand off the body, per side.
     *
     * <p>A quarter of a pixel, which is what the game uses. Too little and the
     * two surfaces fight over the same depth and flicker; too much and the hat
     * floats.
     */
    private static final double OVERLAY = 0.25;

    /**
     * How much the texture is enlarged before it is used.
     *
     * <p>Materials are sampled smoothly and there is no switch for that, so a
     * 64-pixel sheet stretched over a head arrives as a blur. Enlarging it first
     * with whole-pixel copies means the smoothing has nothing left to blur: every
     * source pixel is already a solid block. Sixteen is the smallest factor at
     * which the edges stop looking soft at the sizes this is drawn at.
     */
    private static final int MAGNIFY = 16;

    private SkinModel() {
    }

    /**
     * Builds the figure.
     *
     * @param skin  the skin sheet, or null for an untextured figure
     * @param cape  the cape sheet, or null for none
     * @param slim  three-pixel arms
     */
    public static Group build(Image skin, Image cape, boolean slim) {
        Group group = new Group();

        Image sheet = skin == null ? null : magnify(widen(skin));
        PhongMaterial material = material(sheet);

        for (SkinLayout.Part part : SkinLayout.player(slim)) {
            if (part.overlay() && sheet == null) {
                // Nothing to be transparent against: an untextured figure wearing
                // an untextured hat is a figure with a bigger head.
                continue;
            }
            group.getChildren().add(view(part, material, sheet, part.overlay() ? OVERLAY : 0));
        }

        if (cape != null) {
            MeshView view = view(SkinLayout.cape(), material(magnify(cape)), cape, 0);
            // Hung from the shoulders and tipped away from the back, which is
            // what stops it reading as a plank stuck to the spine.
            view.getTransforms().add(new Rotate(-8, 0, -8, 0, Rotate.X_AXIS));
            group.getChildren().add(view);
        }
        return group;
    }

    private static PhongMaterial material(Image sheet) {
        PhongMaterial material = new PhongMaterial();
        if (sheet == null) {
            material.setDiffuseColor(Color.web("#6f7684"));
        } else {
            material.setDiffuseMap(sheet);
            // White, so the texture is shown as it is rather than tinted.
            material.setDiffuseColor(Color.WHITE);
        }
        material.setSpecularColor(Color.TRANSPARENT);
        return material;
    }

    private static MeshView view(SkinLayout.Part part, PhongMaterial material,
                                 Image sheet, double inflate) {
        double sheetWidth = sheet == null ? SkinLayout.SHEET : sheet.getWidth() / MAGNIFY;
        double sheetHeight = sheet == null ? SkinLayout.SHEET : sheet.getHeight() / MAGNIFY;

        MeshView view = new MeshView(mesh(part, sheetWidth, sheetHeight, inflate));
        view.setMaterial(material);
        // Both sides of every face. The alternative is one wrong winding
        // somewhere making a limb invisible from one angle only, which is the
        // kind of fault that survives review and shows up in a screenshot.
        view.setCullFace(CullFace.NONE);
        view.setTranslateX(part.x());
        view.setTranslateY(part.y());
        view.setTranslateZ(part.z());
        return view;
    }

    /**
     * One box: eight corners, six sides, two triangles each.
     *
     * <p>Y is down, so the corners at {@code -h} are the top ones.
     */
    private static TriangleMesh mesh(SkinLayout.Part part, double sheetWidth, double sheetHeight,
                                     double inflate) {
        double w = part.width() / 2 + inflate;
        double h = part.height() / 2 + inflate;
        double d = part.depth() / 2 + inflate;

        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().addAll(
                (float) -w, (float) -h, (float) -d,   // 0 top    left  front
                (float) w, (float) -h, (float) -d,    // 1 top    right front
                (float) w, (float) h, (float) -d,     // 2 bottom right front
                (float) -w, (float) h, (float) -d,    // 3 bottom left  front
                (float) -w, (float) -h, (float) d,    // 4 top    left  back
                (float) w, (float) -h, (float) d,     // 5 top    right back
                (float) w, (float) h, (float) d,      // 6 bottom right back
                (float) -w, (float) h, (float) d);    // 7 bottom left  back

        SkinLayout.Faces faces = part.faces();
        // Each side names its corners clockwise from the top left as seen from
        // outside, so the rectangle lands on it the right way up.
        quad(mesh, faces.front(), sheetWidth, sheetHeight, 0, 1, 2, 3);
        quad(mesh, faces.back(), sheetWidth, sheetHeight, 5, 4, 7, 6);
        quad(mesh, faces.right(), sheetWidth, sheetHeight, 1, 5, 6, 2);
        quad(mesh, faces.left(), sheetWidth, sheetHeight, 4, 0, 3, 7);
        quad(mesh, faces.top(), sheetWidth, sheetHeight, 4, 5, 1, 0);
        quad(mesh, faces.bottom(), sheetWidth, sheetHeight, 3, 2, 6, 7);
        return mesh;
    }

    private static void quad(TriangleMesh mesh, SkinLayout.Rect rect,
                             double sheetWidth, double sheetHeight,
                             int topLeft, int topRight, int bottomRight, int bottomLeft) {

        // Pulled a hair inside the rectangle. Sampling exactly on the boundary
        // picks up the neighbouring part - which is how a sleeve ends up with a
        // one-pixel stripe of the trouser next to it on the sheet.
        double bleed = 0.01;
        float u0 = (float) ((rect.u0() + bleed) / sheetWidth);
        float v0 = (float) ((rect.v0() + bleed) / sheetHeight);
        float u1 = (float) ((rect.u1() - bleed) / sheetWidth);
        float v1 = (float) ((rect.v1() - bleed) / sheetHeight);

        int t = mesh.getTexCoords().size() / 2;
        mesh.getTexCoords().addAll(u0, v0, u1, v0, u1, v1, u0, v1);

        mesh.getFaces().addAll(
                topLeft, t, topRight, t + 1, bottomRight, t + 2,
                topLeft, t, bottomRight, t + 2, bottomLeft, t + 3);
    }

    /**
     * Turns a sheet from before 1.8 into a modern one.
     *
     * <p>The old sheet is half as tall and has no left arm or left leg: the game
     * drew both sides from the right-hand ones. The rectangles this launcher
     * uses are the modern ones, so rather than carry a second layout, the old
     * sheet is widened once - the right limbs are copied into the left slots,
     * mirrored.
     *
     * <p>Mirroring a whole limb block works because of how the block is laid
     * out. Its four sides run right, front, left, back; flipped, they run back,
     * left, front, right, each reversed - which is exactly the same limb seen
     * from the other side.
     */
    static Image widen(Image sheet) {
        if (sheet.getHeight() >= sheet.getWidth()) {
            return sheet;
        }
        int width = (int) sheet.getWidth();
        int height = (int) sheet.getHeight();
        double scale = width / 64.0;

        WritableImage widened = new WritableImage(width, width);
        PixelReader in = sheet.getPixelReader();
        PixelWriter out = widened.getPixelWriter();
        if (in == null) {
            return sheet;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setArgb(x, y, in.getArgb(x, y));
            }
        }
        mirror(in, out, scale, 0, 16, 16, 16, 16, 48);   // right leg -> left leg
        mirror(in, out, scale, 40, 16, 16, 16, 32, 48);  // right arm -> left arm
        return widened;
    }

    private static void mirror(PixelReader in, PixelWriter out, double scale,
                               int fromU, int fromV, int blockWidth, int blockHeight,
                               int toU, int toV) {
        int x0 = (int) (fromU * scale);
        int y0 = (int) (fromV * scale);
        int w = (int) (blockWidth * scale);
        int h = (int) (blockHeight * scale);
        int tx = (int) (toU * scale);
        int ty = (int) (toV * scale);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setArgb(tx + x, ty + y, in.getArgb(x0 + (w - 1 - x), y0 + y));
            }
        }
    }

    /** @see #MAGNIFY */
    private static Image magnify(Image sheet) {
        PixelReader in = sheet.getPixelReader();
        if (in == null) {
            return sheet;
        }
        int width = (int) sheet.getWidth();
        int height = (int) sheet.getHeight();
        WritableImage big = new WritableImage(width * MAGNIFY, height * MAGNIFY);
        PixelWriter out = big.getPixelWriter();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = in.getArgb(x, y);
                for (int dy = 0; dy < MAGNIFY; dy++) {
                    for (int dx = 0; dx < MAGNIFY; dx++) {
                        out.setArgb(x * MAGNIFY + dx, y * MAGNIFY + dy, argb);
                    }
                }
            }
        }
        return big;
    }

    /** The parts, for anything that wants to reason about the figure. */
    public static List<SkinLayout.Part> parts(boolean slim) {
        return SkinLayout.player(slim);
    }
}
