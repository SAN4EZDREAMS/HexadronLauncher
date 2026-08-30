package com.hexadron.launcher.skin;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each part of a player model is on the skin sheet, and where it sits on
 * the body.
 *
 * <h2>Plain data, on purpose</h2>
 *
 * <p>Nothing here touches a graphics library. A skin sheet is a fixed
 * arrangement of rectangles that has not changed since 2014, and getting one of
 * them wrong shows up as a hand drawn on a shin - which is a hard thing to
 * notice in a spinning model and a trivial thing to assert about numbers. So
 * the arrangement is data, checked by the self-check, and the part that needs a
 * graphics library only reads it.
 *
 * <h2>The coordinate system</h2>
 *
 * <p>Model units are skin pixels: the head is eight of them. The origin is the
 * middle of the figure, X runs to the viewer's right, Y runs <em>down</em> - as
 * it does everywhere in a scene graph - and the figure faces -Z, towards the
 * camera. So the player's own right arm is at negative X, which is the viewer's
 * left, which is where a right hand appears when somebody faces you.
 */
public final class SkinLayout {

    /** A rectangle on the sheet, in pixels, top-left to bottom-right. */
    public record Rect(int u0, int v0, int u1, int v1) {

        public int width() {
            return u1 - u0;
        }

        public int height() {
            return v1 - v0;
        }

        /** The same rectangle moved, for the overlay copy of a part. */
        public Rect shifted(int dx, int dy) {
            return new Rect(u0 + dx, v0 + dy, u1 + dx, v1 + dy);
        }
    }

    /**
     * The six sides of a cuboid.
     *
     * <p>Named for where they face, not for what is drawn on them: {@code front}
     * is the -Z side, which is the one pointing at the camera.
     */
    public record Faces(Rect top, Rect bottom, Rect right, Rect front, Rect left, Rect back) {

        public Faces shifted(int dx, int dy) {
            return new Faces(top.shifted(dx, dy), bottom.shifted(dx, dy), right.shifted(dx, dy),
                    front.shifted(dx, dy), left.shifted(dx, dy), back.shifted(dx, dy));
        }
    }

    /** One cuboid of the figure. */
    public record Part(String name,
                       double width, double height, double depth,
                       double x, double y, double z,
                       Faces faces, boolean overlay) {
    }

    private SkinLayout() {
    }

    /** The sheet every part is measured against. */
    public static final int SHEET = 64;

    /**
     * The parts of a player model.
     *
     * @param slim three-pixel arms rather than four. The difference is the arm
     *             boxes and the width of four of their rectangles; everything
     *             else is identical, which is why it is a flag and not a second
     *             layout
     */
    public static List<Part> player(boolean slim) {
        List<Part> parts = new ArrayList<>(12);

        // Head, 8x8x8, on top. Its overlay - the hat layer - is the one every
        // skin uses, so it is worth having even when the others are empty.
        Faces head = box(0, 0, 8, 8, 8);
        parts.add(new Part("head", 8, 8, 8, 0, -12, 0, head, false));
        parts.add(new Part("hat", 8, 8, 8, 0, -12, 0, head.shifted(32, 0), true));

        // Body, 8x12x4.
        Faces body = box(16, 16, 8, 12, 4);
        parts.add(new Part("body", 8, 12, 4, 0, -2, 0, body, false));
        parts.add(new Part("jacket", 8, 12, 4, 0, -2, 0, body.shifted(0, 16), true));

        int arm = slim ? 3 : 4;
        double armX = 4.0 / 2 + arm / 2.0;

        // The player's right arm, on the viewer's left.
        Faces rightArm = box(40, 16, arm, 12, 4);
        parts.add(new Part("rightArm", arm, 12, 4, -armX, -2, 0, rightArm, false));
        parts.add(new Part("rightSleeve", arm, 12, 4, -armX, -2, 0, rightArm.shifted(0, 16), true));

        // The left arm has its own rectangles, which is why a 64x32 sheet - which
        // has none - has to be widened before it can be drawn. See SkinModel.
        Faces leftArm = box(32, 48, arm, 12, 4);
        parts.add(new Part("leftArm", arm, 12, 4, armX, -2, 0, leftArm, false));
        parts.add(new Part("leftSleeve", arm, 12, 4, armX, -2, 0, leftArm.shifted(16, 0), true));

        Faces rightLeg = box(0, 16, 4, 12, 4);
        parts.add(new Part("rightLeg", 4, 12, 4, -2, 10, 0, rightLeg, false));
        parts.add(new Part("rightTrouser", 4, 12, 4, -2, 10, 0, rightLeg.shifted(0, 16), true));

        Faces leftLeg = box(16, 48, 4, 12, 4);
        parts.add(new Part("leftLeg", 4, 12, 4, 2, 10, 0, leftLeg, false));
        parts.add(new Part("leftTrouser", 4, 12, 4, 2, 10, 0, leftLeg.shifted(-16, 0), true));

        return List.copyOf(parts);
    }

    /**
     * The cape: ten wide, sixteen tall, one thick, hung off the back.
     *
     * <p>Its outward side - the one anybody looking at the player sees - is the
     * +Z one, so the sheet's outer rectangle goes on {@code back} and the inner
     * one on {@code front}. A cape mapped the obvious way round is a cape whose
     * pattern is only visible from inside the player.
     */
    public static Part cape() {
        Faces faces = new Faces(
                new Rect(1, 0, 11, 1),     // top
                new Rect(11, 0, 21, 1),    // bottom
                new Rect(11, 1, 12, 17),   // right edge
                new Rect(12, 1, 22, 17),   // towards the player: the inside
                new Rect(0, 1, 1, 17),     // left edge
                new Rect(1, 1, 11, 17));   // away from the player: the outside
        return new Part("cape", 10, 16, 1, 0, 0, 2.5, faces, false);
    }

    /**
     * The six rectangles of a part whose block starts at {@code u,v}.
     *
     * <p>The arrangement is always the same: a row of two on top - the lid and
     * the base, each as wide as the part and as tall as it is deep - and under
     * it a row of four sides, in the order right, front, left, back.
     */
    private static Faces box(int u, int v, int width, int height, int depth) {
        return new Faces(
                new Rect(u + depth, v, u + depth + width, v + depth),
                new Rect(u + depth + width, v, u + depth + width * 2, v + depth),
                new Rect(u, v + depth, u + depth, v + depth + height),
                new Rect(u + depth, v + depth, u + depth + width, v + depth + height),
                new Rect(u + depth + width, v + depth, u + depth + width + depth, v + depth + height),
                new Rect(u + depth + width + depth, v + depth,
                        u + depth + width * 2 + depth, v + depth + height));
    }
}
