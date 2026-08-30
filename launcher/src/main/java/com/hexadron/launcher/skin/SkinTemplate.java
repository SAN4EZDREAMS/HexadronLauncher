package com.hexadron.launcher.skin;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import javax.imageio.ImageIO;

/**
 * Blank sheets to draw on, and a map of what goes where.
 *
 * <h2>Two files, because they answer two questions</h2>
 *
 * <p>The template is the canvas: the right size, the base areas filled with a
 * neutral colour and everything else left transparent. It is what an editor
 * should be opened on. The neutral fill is not decoration - a base layer with
 * holes in it renders as a see-through limb, and a first attempt at a skin is
 * exactly where that happens.
 *
 * <p>The guide is the reference: the same sheet enlarged, with every rectangle
 * outlined and named. Nobody can look at a bare 64 by 64 square and know that
 * the strip at (44, 20) is the front of the right sleeve.
 *
 * <h2>Generated, not shipped</h2>
 *
 * <p>Both are drawn from {@link SkinLayout} - the same rectangles the model is
 * built from. A picture checked in as a file would be a second copy of the
 * layout, free to disagree with the first one, and the disagreement would show
 * up as somebody's careful drawing landing on the wrong limb.
 */
public final class SkinTemplate {

    /** How much the guide is enlarged. A four-pixel side becomes wide enough to label. */
    private static final int GUIDE_SCALE = 12;

    /** The colour a blank base area is filled with: obviously unfinished, and opaque. */
    private static final Color BLANK = new Color(0xB4, 0xB4, 0xB4);

    private SkinTemplate() {
    }

    /**
     * Writes the pair into a folder.
     *
     * @param names  turns a layout name - a part name, or a side - into words
     *               for the guide's labels
     * @return the files written, in the order they were written
     */
    public static List<Path> write(Path folder, boolean cape, boolean slim,
                                   BiFunction<String, String, String> names) throws IOException {
        Files.createDirectories(folder);
        List<Path> written = new ArrayList<>(2);

        Path template = unique(folder, cape ? "cape-template" : "skin-template");
        ImageIO.write(cape ? capeTemplate() : skinTemplate(slim), "png", template.toFile());
        written.add(template);

        Path guide = unique(folder, cape ? "cape-guide" : "skin-guide");
        ImageIO.write(cape ? capeGuide(names) : skinGuide(slim, names), "png", guide.toFile());
        written.add(guide);

        return List.copyOf(written);
    }

    /**
     * A name nothing is already using.
     *
     * <p>Writing over a file somebody has spent an evening drawing on, because
     * they pressed the button again to look at the guide, is not a mistake worth
     * being able to make.
     */
    private static Path unique(Path folder, String stem) {
        Path candidate = folder.resolve(stem + ".png");
        for (int n = 2; Files.exists(candidate) && n < 1000; n++) {
            candidate = folder.resolve(stem + "-" + n + ".png");
        }
        return candidate;
    }

    // ------------------------------------------------------------------ canvases

    private static BufferedImage skinTemplate(boolean slim) {
        BufferedImage image = blank(SkinLayout.SHEET, SkinLayout.SHEET);
        Graphics2D g = image.createGraphics();
        g.setColor(BLANK);
        for (SkinLayout.Part part : SkinLayout.player(slim)) {
            if (part.overlay()) {
                // Left transparent on purpose: the second layer is meant to be
                // mostly nothing, and filling it would put a solid box round the
                // head of every skin drawn from this.
                continue;
            }
            for (SkinLayout.Rect rect : sides(part.faces())) {
                g.fillRect(rect.u0(), rect.v0(), rect.width(), rect.height());
            }
        }
        g.dispose();
        return image;
    }

    private static BufferedImage capeTemplate() {
        BufferedImage image = blank(64, 32);
        Graphics2D g = image.createGraphics();
        g.setColor(BLANK);
        for (SkinLayout.Rect rect : sides(SkinLayout.cape().faces())) {
            g.fillRect(rect.u0(), rect.v0(), rect.width(), rect.height());
        }
        g.dispose();
        return image;
    }

    // -------------------------------------------------------------------- guides

    private static BufferedImage skinGuide(boolean slim, BiFunction<String, String, String> names) {
        return guide(SkinLayout.SHEET, SkinLayout.SHEET, SkinLayout.player(slim), names);
    }

    private static BufferedImage capeGuide(BiFunction<String, String, String> names) {
        return guide(64, 32, List.of(SkinLayout.cape()), names);
    }

    private static BufferedImage guide(int width, int height, List<SkinLayout.Part> parts,
                                       BiFunction<String, String, String> names) {

        BufferedImage image = new BufferedImage(width * GUIDE_SCALE, height * GUIDE_SCALE,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(0x14, 0x16, 0x1A));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());

        // One line per sheet pixel. The guide is read while counting squares in
        // an editor, so the squares have to be there to count.
        g.setColor(new Color(0xFF, 0xFF, 0xFF, 18));
        for (int x = 0; x <= width; x++) {
            g.drawLine(x * GUIDE_SCALE, 0, x * GUIDE_SCALE, image.getHeight());
        }
        for (int y = 0; y <= height; y++) {
            g.drawLine(0, y * GUIDE_SCALE, image.getWidth(), y * GUIDE_SCALE);
        }

        String[] sideKeys = {"top", "bottom", "right", "front", "left", "back"};
        int hue = 0;
        for (SkinLayout.Part part : parts) {
            Color colour = colour(hue++, part.overlay());
            List<SkinLayout.Rect> rects = sides(part.faces());
            for (int i = 0; i < rects.size(); i++) {
                SkinLayout.Rect rect = rects.get(i);
                int x = rect.u0() * GUIDE_SCALE;
                int y = rect.v0() * GUIDE_SCALE;
                int w = rect.width() * GUIDE_SCALE;
                int h = rect.height() * GUIDE_SCALE;

                g.setColor(colour);
                g.fillRect(x, y, w, h);
                g.setStroke(new BasicStroke(part.overlay() ? 1f : 2f));
                g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 230));
                g.drawRect(x, y, w, h);

                label(g, x, y, w, h,
                        names.apply("part", part.name()),
                        names.apply("side", sideKeys[i]),
                        rect.width() + "×" + rect.height());
            }
        }
        g.dispose();
        return image;
    }

    /**
     * Three lines in a box, dropping the ones that do not fit.
     *
     * <p>A four-by-four top face is forty-eight pixels square in the guide,
     * which holds one short word. The part name is the one worth keeping when
     * only one fits: which limb it is matters more than which way it faces,
     * because the sides are in the same order on every limb.
     */
    private static void label(Graphics2D g, int x, int y, int w, int h,
                              String part, String side, String size) {
        g.setColor(Color.WHITE);
        int fontSize = Math.max(8, Math.min(13, w / 6));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize));
        int line = g.getFontMetrics().getHeight();

        List<String> lines = new ArrayList<>(3);
        lines.add(part);
        if (h >= line * 2 + 6) {
            lines.add(side);
        }
        if (h >= line * 3 + 6) {
            lines.add(size);
        }

        int top = y + (h - line * lines.size()) / 2 + g.getFontMetrics().getAscent();
        for (int i = 0; i < lines.size(); i++) {
            String text = clip(g, lines.get(i), w - 4);
            int width = g.getFontMetrics().stringWidth(text);
            g.drawString(text, x + (w - width) / 2, top + i * line);
        }
    }

    private static String clip(Graphics2D g, String text, int width) {
        if (g.getFontMetrics().stringWidth(text) <= width) {
            return text;
        }
        for (int n = text.length() - 1; n > 0; n--) {
            String shorter = text.substring(0, n);
            if (g.getFontMetrics().stringWidth(shorter) <= width) {
                return shorter;
            }
        }
        return "";
    }

    /**
     * A colour per part, spread round the wheel.
     *
     * <p>Generated rather than listed so that the guide does not need a second
     * table to be kept in step with the layout. Overlays come out fainter, which
     * is the one distinction that has to survive: they are the layer that is
     * usually left empty.
     */
    private static Color colour(int index, boolean overlay) {
        float hue = (index * 0.137f) % 1f;
        Color base = Color.getHSBColor(hue, overlay ? 0.35f : 0.55f, overlay ? 0.55f : 0.75f);
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), overlay ? 70 : 120);
    }

    private static BufferedImage blank(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private static List<SkinLayout.Rect> sides(SkinLayout.Faces faces) {
        return List.of(faces.top(), faces.bottom(), faces.right(),
                faces.front(), faces.left(), faces.back());
    }
}
