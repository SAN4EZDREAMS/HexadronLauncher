#!/usr/bin/env python3
"""Puts the project's licence header at the top of every source file.

The header itself lives in LICENSE-HEADER.txt, once, and both this script and
the Gradle task that enforces it read that file - so there is one text, and a
change to it is a change everywhere rather than a change in one place and a
build failure in another.

Run it from the repository root:

    python3 tools/stamp-license-headers.py            # stamp what is missing
    python3 tools/stamp-license-headers.py --check    # report, change nothing

It is safe to run twice: a file that already carries the current header is left
exactly as it was, and a file carrying an older version of it has that block
replaced rather than a second one added.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
HEADER_FILE = ROOT / "LICENSE-HEADER.txt"

# Where source lives. Everything else - build output, the Gradle wrapper, the
# icons - is either generated or not ours to stamp.
# "src" is here so that the same script works from inside the launcher module
# as well as from the repository root; a root that does not exist is skipped.
SOURCE_ROOTS = ["launcher/src", "mod/src", "src"]

# One entry per file type this project has and one comment syntax each. A file
# type that is not here is not stamped: JSON has no comments at all, and a
# header inside an icon is a corrupt icon.
STYLES = {
    ".java": ("/*", " * ", " */"),
    ".css": ("/*", " * ", " */"),
    ".properties": (None, "# ", None),
}

# The line that identifies a header of ours, whatever its wording. Used to
# replace an outdated block instead of stacking a second one on top of it.
MARKER = "Copyright (c) 2026 SAN4EZDREAMS"


def header_lines():
    return HEADER_FILE.read_text(encoding="utf-8").rstrip("\n").split("\n")


def rendered(style):
    """The header as it appears in a file of this type."""
    opening, prefix, closing = style
    out = []
    if opening:
        out.append(opening)
    for line in header_lines():
        out.append((prefix + line).rstrip() if line else prefix.rstrip())
    if closing:
        out.append(closing)
    return "\n".join(out) + "\n"


def existing_block(text, style):
    """The header already at the top of this file, or None."""
    opening, _, closing = style
    if opening:
        if not text.startswith(opening):
            return None
        end = text.find(closing.strip(), len(opening))
        if end < 0:
            return None
        end = text.index("\n", end) + 1 if "\n" in text[end:] else len(text)
        block = text[:end]
        return block if MARKER in block else None

    # Line comments: the run of "#" lines at the top of the file.
    lines = text.split("\n")
    taken = 0
    while taken < len(lines) and lines[taken].startswith("#"):
        taken += 1
    if taken == 0:
        return None
    block = "\n".join(lines[:taken]) + "\n"
    return block if MARKER in block else None


def stamp(path, check_only):
    """Returns True when the file already carried the current header."""
    style = STYLES[path.suffix]
    text = path.read_text(encoding="utf-8")
    wanted = rendered(style)

    current = existing_block(text, style)
    if current is not None and current.strip() == wanted.strip():
        return True
    if check_only:
        return False

    rest = text[len(current):] if current is not None else text
    rest = rest.lstrip("\n")
    path.write_text(wanted + "\n" + rest, encoding="utf-8")
    return False


def main():
    check_only = "--check" in sys.argv
    if not HEADER_FILE.is_file():
        print(f"missing {HEADER_FILE}", file=sys.stderr)
        return 2

    stamped = []
    for root in SOURCE_ROOTS:
        base = ROOT / root
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*")):
            if path.is_file() and path.suffix in STYLES:
                if not stamp(path, check_only):
                    stamped.append(path.relative_to(ROOT))

    if check_only:
        for path in stamped:
            print(f"missing or altered header: {path}")
        print(f"{len(stamped)} file(s) without the current header")
        return 1 if stamped else 0

    for path in stamped:
        print(f"stamped {path}")
    print(f"{len(stamped)} file(s) stamped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
