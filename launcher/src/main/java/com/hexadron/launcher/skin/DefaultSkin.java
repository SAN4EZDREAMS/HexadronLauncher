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

package com.hexadron.launcher.skin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The figure drawn when a service knows an account but it wears nothing.
 *
 * <h2>Why the launcher draws its own</h2>
 *
 * <p>An account that exists at a skin service and has never uploaded anything
 * is a normal state, and the preview window has to show something for it. An
 * empty box reads as a failure - "I signed in and nothing happened" - which is
 * exactly the wrong message for the case where everything worked.
 *
 * <p>This is the launcher's own drawing, not Mojang's. In game the default is
 * Mojang's, picked from the account's UUID, and the two do not look alike; the
 * window says so beside the figure rather than implying that this is what will
 * appear. What it is honestly for is answering the question the user is
 * actually asking at that moment, which is whether the sign-in took.
 *
 * <h2>Built rather than shipped</h2>
 *
 * <p>Flat rectangles on a 64x64 sheet, in the layout {@link SkinLayout}
 * already describes, so there is no image file to keep in step with the code
 * and nothing to go missing from a build. It costs a few milliseconds, once,
 * the first time a window needs it.
 */
public final class DefaultSkin {

    private static final int SHEET = 64;

    private static final Color SKIN = new Color(0x9B, 0x8E, 0x84);
    private static final Color SKIN_SHADE = new Color(0x86, 0x7A, 0x71);
    private static final Color SHIRT = new Color(0x4C, 0x6E, 0x8A);
    private static final Color SHIRT_SHADE = new Color(0x3E, 0x5B, 0x73);
    private static final Color TROUSERS = new Color(0x3A, 0x3F, 0x4A);
    private static final Color TROUSERS_SHADE = new Color(0x2F, 0x33, 0x3C);
    private static final Color SHOE = new Color(0x24, 0x26, 0x2C);
    private static final Color HAIR = new Color(0x39, 0x30, 0x2B);
    private static final Color EYE = new Color(0x1C, 0x20, 0x2A);

    /** Cached: the same sheet every time, and it is not large. */
    private static volatile byte[] png;

    private DefaultSkin() {
    }

    /** The sheet, as PNG bytes. */
    public static byte[] png() {
        byte[] cached = png;
        if (cached == null) {
            cached = encode(draw());
            png = cached;
        }
        return cached;
    }

    private static BufferedImage draw() {
        BufferedImage sheet = new BufferedImage(SHEET, SHEET, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        try {
            // Every base part is filled opaque. A hole in a base layer renders
            // as a see-through limb, which looks like a broken texture rather
            // than a plain figure.
            for (SkinLayout.Part part : SkinLayout.player(false)) {
                if (part.overlay()) {
                    continue;
                }
                fill(g, part, colourFor(part.name()), shadeFor(part.name()));
            }
            face(g);
        } finally {
            g.dispose();
        }
        return sheet;
    }

    /**
     * Fills one box: every side in the flat colour, the top and the bottom a
     * shade darker so the shape reads as solid rather than as a sticker.
     */
    private static void fill(Graphics2D g, SkinLayout.Part part, Color flat, Color shade) {
        SkinLayout.Faces faces = part.faces();
        for (SkinLayout.Rect side : new SkinLayout.Rect[]{
                faces.front(), faces.back(), faces.left(), faces.right()}) {
            paint(g, side, flat);
        }
        paint(g, faces.top(), shade);
        paint(g, faces.bottom(), shade);
    }

    private static void paint(Graphics2D g, SkinLayout.Rect rect, Color colour) {
        g.setColor(colour);
        g.fillRect(rect.u0(), rect.v0(), rect.width(), rect.height());
    }

    /** Two eyes and a hairline, so the head has a front. */
    private static void face(Graphics2D g) {
        SkinLayout.Rect front = named("head").faces().front();

        g.setColor(HAIR);
        g.fillRect(front.u0(), front.v0(), front.width(), 2);

        g.setColor(EYE);
        g.fillRect(front.u0() + 2, front.v0() + 4, 1, 1);
        g.fillRect(front.u0() + 5, front.v0() + 4, 1, 1);

        g.setColor(SKIN_SHADE);
        g.fillRect(front.u0() + 3, front.v0() + 6, 2, 1);
    }

    private static SkinLayout.Part named(String name) {
        for (SkinLayout.Part part : SkinLayout.player(false)) {
            if (part.name().equals(name)) {
                return part;
            }
        }
        throw new IllegalStateException("the player layout has no " + name);
    }

    private static Color colourFor(String part) {
        if (part.contains("Leg")) {
            return TROUSERS;
        }
        if (part.equals("body")) {
            return SHIRT;
        }
        if (part.contains("Arm")) {
            // Short sleeves: the arm box is skin, the shoulder end is shirt.
            return SKIN;
        }
        return SKIN;
    }

    private static Color shadeFor(String part) {
        if (part.contains("Leg")) {
            return part.startsWith("left") || part.startsWith("right") ? SHOE : TROUSERS_SHADE;
        }
        if (part.equals("body")) {
            return SHIRT_SHADE;
        }
        if (part.contains("Arm")) {
            return SHIRT_SHADE;
        }
        return SKIN_SHADE;
    }

    private static byte[] encode(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
