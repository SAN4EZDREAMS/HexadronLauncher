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
     * How much every box is pulled in, per side.
     *
     * <p>A hundredth of a pixel, and it exists to stop parts that touch from
     * fighting. An arm sits at exactly the body's edge, a leg at exactly the
     * other leg's, the head at exactly the top of the body - so those pairs of
     * faces land on the same plane, at the same depth, and which one gets drawn
     * comes down to rounding. That is the streaking down the shoulders and
     * between the legs. Two hundredths of a unit apart is invisible at any size
     * this is drawn, and it is the whole of the fix.
     */
    private static final double INSET = 0.01;

    /**
     * How large a texture is enlarged to before it is used.
     *
     * <p>Materials are sampled smoothly and there is no switch for that, so a
     * 64-pixel sheet stretched over a head arrives as a blur. Enlarging it first
     * with whole-pixel copies means the smoothing has nothing left to blur:
     * every source pixel is already a solid block.
     *
     * <p>A target rather than a factor, because skins are not all 64 pixels
     * wide. Multiplying a 512-pixel one by sixteen would allocate a hundred and
     * thirty megabytes to solve a problem it does not have.
     */
    private static final int MAGNIFIED = 1024;

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

        Image sheet = skin == null ? null : magnify(flatten(widen(skin), slim));
        PhongMaterial material = material(sheet);

        // The rectangles are written in the units of a 64-wide sheet, and they
        // stay in those units however many pixels the sheet actually has. A
        // high-resolution skin is the same map at a finer grain, not a different
        // map - so the divisor is the map's size, never the image's.
        for (SkinLayout.Part part : SkinLayout.player(slim)) {
            if (part.overlay()) {
                // Already drawn - into the texture, not into the scene. See
                // flatten.
                continue;
            }
            group.getChildren().add(view(part, material,
                    SkinLayout.SHEET, SkinLayout.SHEET, -INSET));
        }

        if (cape != null) {
            double[] map = capeMap(cape);
            MeshView view = view(SkinLayout.cape(), material(magnify(cape)),
                    map[0], map[1], -INSET);
            // Hung from the shoulders and tipped away from the back. Away: a
            // negative angle here swings the hem forwards, through the legs.
            view.getTransforms().add(new Rotate(8, 0, -8, 0, Rotate.X_AXIS));
            group.getChildren().add(view);
        }
        return group;
    }

    /**
     * The size of the map a cape sheet is drawn on.
     *
     * <p>Two shapes exist. The one every cape has used since 1.6 is twice as
     * wide as it is tall, and its rectangles are written against 64 by 32
     * whatever its pixel count. The other is the 22 by 17 sheet from before
     * that, whose rectangles are the same numbers against a much smaller map -
     * which is why the map cannot simply be assumed.
     */
    private static double[] capeMap(Image cape) {
        if (cape.getWidth() == 22 && cape.getHeight() == 17) {
            return new double[]{22, 17};
        }
        return new double[]{64, 32};
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
                                 double mapWidth, double mapHeight, double inflate) {
        MeshView view = new MeshView(mesh(part, mapWidth, mapHeight, inflate));
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
     * Draws the overlay layers into the base ones.
     *
     * <p>A skin has a second layer - hat, jacket, sleeves, trousers - which the
     * game draws as a slightly larger shell around each part, with the parts of
     * it that are transparent left out. That shell is the obvious thing to build
     * here too, and it is what produced the mess this replaces: a scene graph
     * writes depth for transparent pixels as readily as for solid ones, so every
     * see-through part of the hat punched a hole through the head behind it, and
     * the two surfaces flickered wherever they nearly touched.
     *
     * <p>So the two layers are combined before anything is drawn: each overlay
     * pixel is composited over the base pixel underneath it, once, in the
     * texture. What reaches the scene is one opaque box per body part - nothing
     * transparent, nothing overlapping, nothing to sort. The cost is that the
     * layer no longer stands off the body, which at the size this is drawn is
     * not visible; what is visible is that it is now right.
     *
     * <p>The base is forced opaque afterwards. A base layer is meant to be, some
     * skins are careless about it, and a see-through torso on a solid box shows
     * the inside of the far side of the box.
     */
    static Image flatten(Image sheet, boolean slim) {
        PixelReader in = sheet.getPixelReader();
        if (in == null) {
            return sheet;
        }
        int size = (int) sheet.getWidth();
        double scale = size / (double) SkinLayout.SHEET;

        WritableImage flat = new WritableImage(size, (int) sheet.getHeight());
        PixelWriter out = flat.getPixelWriter();
        for (int y = 0; y < (int) sheet.getHeight(); y++) {
            for (int x = 0; x < size; x++) {
                out.setArgb(x, y, in.getArgb(x, y));
            }
        }

        SkinLayout.Part base = null;
        for (SkinLayout.Part part : SkinLayout.player(slim)) {
            if (!part.overlay()) {
                base = part;
                continue;
            }
            if (base == null) {
                continue;
            }
            List<SkinLayout.Rect> under = sides(base.faces());
            List<SkinLayout.Rect> over = sides(part.faces());
            for (int i = 0; i < under.size(); i++) {
                blend(flat, out, scale, over.get(i), under.get(i));
            }
        }
        return flat;
    }

    private static List<SkinLayout.Rect> sides(SkinLayout.Faces faces) {
        return List.of(faces.top(), faces.bottom(), faces.right(),
                faces.front(), faces.left(), faces.back());
    }

    /** One overlay rectangle over the base rectangle it covers. */
    private static void blend(Image sheet, PixelWriter out, double scale,
                              SkinLayout.Rect over, SkinLayout.Rect under) {
        PixelReader in = sheet.getPixelReader();
        int width = (int) (under.width() * scale);
        int height = (int) (under.height() * scale);
        int ox = (int) (over.u0() * scale);
        int oy = (int) (over.v0() * scale);
        int ux = (int) (under.u0() * scale);
        int uy = (int) (under.v0() * scale);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int top = in.getArgb(ox + x, oy + y);
                int bottom = in.getArgb(ux + x, uy + y);
                out.setArgb(ux + x, uy + y, 0xFF000000 | over(top, bottom));
            }
        }
    }

    /** Straight source-over, which is what the game does with the two layers. */
    private static int over(int top, int bottom) {
        int alpha = (top >>> 24) & 0xFF;
        if (alpha == 0) {
            return bottom;
        }
        if (alpha == 0xFF) {
            return top;
        }
        int r = mix((top >> 16) & 0xFF, (bottom >> 16) & 0xFF, alpha);
        int g = mix((top >> 8) & 0xFF, (bottom >> 8) & 0xFF, alpha);
        int b = mix(top & 0xFF, bottom & 0xFF, alpha);
        return (r << 16) | (g << 8) | b;
    }

    private static int mix(int top, int bottom, int alpha) {
        return (top * alpha + bottom * (255 - alpha)) / 255;
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

    /** @see #MAGNIFIED */
    private static Image magnify(Image sheet) {
        PixelReader in = sheet.getPixelReader();
        if (in == null) {
            return sheet;
        }
        int width = (int) sheet.getWidth();
        int height = (int) sheet.getHeight();
        int factor = Math.max(1, MAGNIFIED / Math.max(1, width));
        if (factor == 1) {
            return sheet;
        }
        WritableImage big = new WritableImage(width * factor, height * factor);
        PixelWriter out = big.getPixelWriter();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = in.getArgb(x, y);
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        out.setArgb(x * factor + dx, y * factor + dy, argb);
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
