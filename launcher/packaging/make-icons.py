#!/usr/bin/env python3
"""
Regenerates icon.ico, icon.icns and icon.png.

The icons are committed rather than built, because building them needs Python
and Pillow and the build must not - but they are generated rather than drawn by
hand, so that they stay identical to the mark the interface draws at runtime in
ui/Brand.java. If that drawing changes, change the constants here to match and
run this once.

    pip install pillow
    python3 make-icons.py [path/to/a/bold/sans.ttf]

Rendered at four times the target and downsampled, because the corner curve and
the stem of the letter are what fall apart first at sixteen pixels.
"""
import io
import pathlib
import struct
import sys

from PIL import Image, ImageDraw, ImageFont

# Matching ui/Brand.java: -fx-accent-0, a 28% corner radius, a 62% cap height.
ACCENT = (0x2D, 0x7D, 0x46, 255)
WHITE = (255, 255, 255, 255)
CORNER_RADIUS = 0.28
CAP_HEIGHT = 0.62
LETTER = "H"

SUPERSAMPLE = 4

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "C:/Windows/Fonts/segoeuib.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
]

HERE = pathlib.Path(__file__).resolve().parent

# An .icns is a flat container of typed entries, and every modern type is a PNG.
# Written by hand because iconutil only exists on macOS.
ICNS_TYPES = [
    (b"icp4", 16), (b"icp5", 32), (b"ic11", 32), (b"ic12", 64),
    (b"ic07", 128), (b"ic13", 256), (b"ic08", 256),
    (b"ic14", 512), (b"ic09", 512), (b"ic10", 1024),
]


def find_font():
    if len(sys.argv) > 1:
        return sys.argv[1]
    for candidate in FONT_CANDIDATES:
        if pathlib.Path(candidate).is_file():
            return candidate
    raise SystemExit("no bold sans-serif font found; pass one as an argument")


def render(size, font_path):
    scale = size * SUPERSAMPLE
    image = Image.new("RGBA", (scale, scale), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle([0, 0, scale - 1, scale - 1],
                           radius=scale * CORNER_RADIUS, fill=ACCENT)

    font = ImageFont.truetype(font_path, int(scale * CAP_HEIGHT))
    box = draw.textbbox((0, 0), LETTER, font=font)
    width, height = box[2] - box[0], box[3] - box[1]
    # Placed from the measured bounds rather than by an anchor, for the same
    # reason Brand.java does it: anchors leave the letter a pixel or two off
    # centre, and that is only ever noticed after it has shipped.
    draw.text(((scale - width) / 2 - box[0], (scale - height) / 2 - box[1]),
              LETTER, font=font, fill=WHITE)
    return image.resize((size, size), Image.LANCZOS)


def main():
    font_path = find_font()
    sizes = [16, 24, 32, 48, 64, 128, 256, 512, 1024]
    icons = {size: render(size, font_path) for size in sizes}

    icons[256].save(HERE / "icon.ico", format="ICO",
                    sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
    icons[512].save(HERE / "icon.png", format="PNG")

    entries = b""
    for tag, size in ICNS_TYPES:
        buffer = io.BytesIO()
        icons[size].save(buffer, format="PNG")
        data = buffer.getvalue()
        entries += tag + struct.pack(">I", len(data) + 8) + data
    (HERE / "icon.icns").write_bytes(b"icns" + struct.pack(">I", len(entries) + 8) + entries)

    print("wrote icon.ico, icon.png and icon.icns using", font_path)


if __name__ == "__main__":
    main()
