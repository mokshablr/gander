#!/usr/bin/env python3
"""Measure every line the film sets, in Jost, at the size it will actually render at.

`store-art/README.md`: "Measure the type, do not estimate it. Poppins is wider than it
looks... and the pages were placed on top of it twice." The same trap is worse here,
because a landscape film's type shares the frame with a card row or a device rather than
with a static margin, so a line that is 15% wide lands on top of the picture.

It carries that README's other warning too, which is the one that silently ruins a run:
**a throwaway page falls back to a system font and reports a width about 20% short.** Every
string is therefore rendered twice, once in Jost and once in the fallback stack, and the
run fails if too many rows match. Identical widths mean Jost never loaded.

    python3 measure.py                    # every string, against its own beat's room
    python3 measure.py --verbose          # show the ones that fit as well
    python3 measure.py --proof CAPTURE    # re-derive the proof crop from a real take

Strings come from words.py and sizes from style.py, so this measures what will be rendered
rather than a copy of it, which is the other half of the README's warning.
"""

from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile

import layers
import style
import words

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ROW_H = 300
CANVAS_W = 3600

# How much width a beat's type actually has. **Asked of layers.py, never restated here.**
# This file used to carry its own copy of the geometry and assume every block began at the
# left margin. Two lines overflowed silently when the card row moved, and later C3's first
# line ran off the right edge of the frame while this reported it fitting, because C3's type
# did not start at the margin at all. Ask the layout.
def room_for(beat: str, role: str = "") -> tuple[int, str]:
    x, w = layers.type_room(beat, role)
    where = "full width" if w >= style.ROOM_FULL else f"x={x}, {w}px to the next element"
    return w, where


def measure_proof(path: pathlib.Path) -> int:
    """Re-derive PROOF_CROP and PROOF_GLYPH_H from a capture of Android's App info screen.

    **Run this against the real take.** Everything in style.py about the proof was measured
    off the press kit's 720x813 stand-in, and a different device, Android version or font
    scale puts that phrase at a different size. The stand-in is likely to be the pessimistic
    case, since a full-resolution device capture carries more detail than a downscaled press
    asset, but "likely" is not a basis for the one thing the film has to get right.

    Both proof rows are deliberately greyed out, so this thresholds pale rather than dark: a
    normal ink threshold misses them completely, which is how the first pass measured the
    Notifications row instead and lost a third of the magnification to it.
    """
    from PIL import Image
    im = Image.open(path).convert("L")
    w, h = im.size
    px = im.load()
    THR = 205
    rows = [(y, sum(1 for x in range(w) if px[x, y] < THR)) for y in range(h // 2, h)]
    lines, start, prev = [], None, None
    for y, n in rows:
        if n > 2 and start is None:
            start = y
        if n <= 2 and start is not None:
            lines.append((start, prev))
            start = None
        if n > 2:
            prev = y
    if start is not None:
        lines.append((start, prev))

    print(f"{path.name}: {w}x{h}, threshold {THR}")
    found = []
    for a, b in lines:
        xs = [x for y in range(a, b + 1) for x in range(w) if px[x, y] < THR]
        found.append((a, b, min(xs), max(xs)))
        print(f"  y {a:>4}-{b:<4} h={b - a + 1:>3}px   "
              f"x {min(xs):>4}-{max(xs):<4} w={max(xs) - min(xs) + 1:>4}px")
    if len(found) < 2:
        print("\nCould not find two proof rows. Check the crop covers "
              "'Permissions' and 'No permissions requested'.")
        return 1

    lab, phrase = found[-2], found[-1]
    pad_x, pad_y = 20, 18
    cx = max(0, min(lab[2], phrase[2]) - pad_x)
    cy = max(0, lab[0] - pad_y)
    cw = max(lab[3], phrase[3]) - cx + pad_x
    ch = phrase[1] - cy + pad_y
    glyph = phrase[1] - phrase[0] + 1
    print(f"\nPaste into style.py:")
    print(f"    PROOF_SRC_W = {w}")
    print(f"    PROOF_CROP = ({cx}, {cy}, {cw}, {ch})")
    print(f"    PROOF_GLYPH_H = {glyph}")
    need = style.PROOF_MIN_PX / (style.CAROUSEL_W / style.W) / glyph * cw
    print(f"\nWith those, the narrowest fragment meeting the "
          f"{style.PROOF_MIN_PX}px floor is {need:.0f}px "
          f"(layers.py currently uses {layers.FRAG_W_B4} at B4).")
    if need > layers.FRAG_W_B4:
        print("  ** That is WIDER than the current B4 fragment. Widen it, and let the "
              "layout give way. **")
    return 0


def build_page(family: str, cases) -> str:
    rows = []
    for i, (_, role, text) in enumerate(cases):
        r = style.ROLES[role]
        rows.append(
            f'<div class="r" style="top:{i * ROW_H}px">'
            f'<span style="font-size:{r.px}px;font-weight:{r.weight};'
            f'letter-spacing:{r.track}em">{text}</span></div>'
        )
    return (
        '<!doctype html><html><head><meta charset="utf-8">'
        f'<link href="{style.GOOGLE_FONTS}" rel="stylesheet">'
        f'<style>*{{box-sizing:border-box;margin:0}}'
        f'body{{background:#000;width:{CANVAS_W}px;'
        f'height:{len(cases) * ROW_H}px;font-family:{family}}}'
        f'.r{{position:absolute;left:0;width:{CANVAS_W}px;height:{ROW_H}px;'
        f'color:#fff;white-space:nowrap;line-height:{ROW_H}px}}'
        '</style></head><body>' + "".join(rows) + '</body></html>'
    )


def shoot(html: str, out: pathlib.Path, work: pathlib.Path, rows: int) -> None:
    src = work / f"{out.stem}.html"
    src.write_text(html)
    subprocess.run(
        [CHROME, "--headless", "--disable-gpu", "--hide-scrollbars",
         "--force-device-scale-factor=1", "--virtual-time-budget=30000",
         f"--window-size={CANVAS_W},{rows * ROW_H}",
         f"--screenshot={out}", f"file://{src}"],
        check=True, capture_output=True,
    )


def row_widths(png: pathlib.Path, rows: int) -> list[int]:
    out = []
    for i in range(rows):
        r = subprocess.run(
            ["magick", str(png), "-crop", f"{CANVAS_W}x{ROW_H}+0+{i * ROW_H}", "+repage",
             "-fuzz", "12%", "-trim", "-format", "%w", "info:"],
            check=True, capture_output=True, text=True,
        )
        out.append(int(r.stdout.strip() or 0))
    return out


def main() -> int:
    if "--proof" in sys.argv:
        i = sys.argv.index("--proof")
        if i + 1 >= len(sys.argv):
            sys.exit("--proof needs a path to a capture of Android's App info screen")
        return measure_proof(pathlib.Path(sys.argv[i + 1]))

    verbose = "--verbose" in sys.argv
    if not pathlib.Path(CHROME).exists():
        sys.exit(f"Chrome not found at {CHROME}")
    if not shutil.which("magick"):
        sys.exit("ImageMagick 'magick' not on PATH")

    cases = words.all_strings()
    with tempfile.TemporaryDirectory() as td:
        work = pathlib.Path(td)
        jost_png, fall_png = work / "jost.png", work / "fallback.png"
        shoot(build_page(style.FAMILY, cases), jost_png, work, len(cases))
        shoot(build_page("sans-serif", cases), fall_png, work, len(cases))
        jost = row_widths(jost_png, len(cases))
        fall = row_widths(fall_png, len(cases))

    identical = sum(1 for a, b in zip(jost, fall) if a == b)
    if identical > len(cases) // 3:
        print(f"FAIL: {identical}/{len(cases)} rows match the fallback exactly.")
        print("Jost did not load. Every width is wrong. Do not use this run.")
        return 1

    over = []
    print(f"{'beat':<5} {'role':<10} {'px':>4} {'width':>6} {'room':>6}  text")
    print("-" * 96)
    for (beat, role, text), w in zip(cases, jost):
        room, why = room_for(beat, role)
        fits = w <= room
        if not fits:
            over.append((beat, role, text, w, room, why))
        if verbose or not fits:
            mark = "" if fits else f"  OVER by {w - room} ({why})"
            plain = text.replace("&middot;", "·")
            print(f"{beat:<5} {role:<10} {style.ROLES[role].px:>4} {w:>6} {room:>6}  "
                  f"{plain[:44]}{mark}")

    print()
    print(f"Jost loaded: {len(cases) - identical}/{len(cases)} rows differ from the fallback.")
    if over:
        print(f"\n{len(over)} line(s) do not fit. Either the size or the copy has to give.")
        return 1
    print(f"All {len(cases)} lines fit their beat's room.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
