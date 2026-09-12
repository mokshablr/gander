#!/usr/bin/env python3
"""Every image in the film, source pixels against displayed pixels.

    python3 audit.py            # report
    python3 audit.py --gate     # exit non-zero if anything upscales past the limit

**Nothing in the final cut may be upscaled.** `compose.py --takes` runs this as a gate
before it will build a deliverable, so a soft asset cannot reach a render by being
overlooked.

The one that matters is the proof fragment, and it is the reason this file exists. It is the
single most important element in the film and it was being blown up **3.66x** from the press
kit's 720 px stand-in. A native 1080 capture only gets that to 2.44x, so it is not a problem
the shoot fixes by itself: the proof block is about 46% of the screen's width, so displaying
it 1200 px wide needs a source roughly 2,600 px across, and no phone screen is that wide.

**The fix is to make Android render it wider.** `wm size` and `wm density` raised together
keep the same dp layout while rendering it at more pixels, and Android draws text as vectors,
so the result is genuinely sharp rather than interpolated. It is the same supersampling
`store-art/README.md` uses for the feature graphic, "rendered at 4x, Lanczos down", applied
to a device instead of a Chrome page. `shoot.py --proof` does it and restores the device
afterwards.

That capture is a still, which is what the fragment needs: it appears only after B3's scroll
has settled, so one frame carries it. Taking it separately, at higher render resolution, in
the same session and on the same screen, changes nothing about what it says. The words are
still Android's own and still unedited.
"""

from __future__ import annotations

import sys

from PIL import Image

import layers
import style as S

# fg7.py's window-onto-a-page scale, mirrored from layers.card().
ZOOM = 1.75

# How much upscaling is tolerable before it is visible on a 1080p frame. Anything above 1.0
# is interpolation; a little is survivable on photographic content and none of it is on type.
LIMIT = 1.0


def rows() -> list[tuple[str, str, int, float, bool]]:
    """(name, source, shown, scale, is_type) for every image the film draws."""
    out = []
    lk = Image.open(layers.LOCKUP).size
    for beat, w in (("A1", layers.A1_LOCK_W), ("B1", 280), ("C4", 300)):
        out.append((f"lockup.png ({beat})", f"{lk[0]}x{lk[1]}", w, w / lk[0], True))

    for x, cy, w, src, fmt, rot, b in layers.A1_CARDS:
        p = layers.PAGES / src
        if not p.exists():
            out.append((f"pages/{src} ({fmt})", "MISSING", 0, 0.0, False))
            continue
        sz = Image.open(p).size
        shown = round(w * ZOOM)
        out.append((f"pages/{src} ({fmt} card)", f"{sz[0]}x{sz[1]}", shown, shown / sz[0],
                    False))

    # **Measure the file, do not trust the constant.** `layers.py` renders whatever sits at
    # APPINFO at `PROOF_SRC_W * k` px wide, so the real scale is that against the image's own
    # width. Dividing `w` by `PROOF_CROP`'s width instead reports 0.82 whatever the source is,
    # which made this gate blind to the one case it is here for: proof.png missing, and the
    # 720 px press-kit stand-in silently rendered at 1200.
    crop_w = S.PROOF_CROP[2]
    src_w = Image.open(layers.APPINFO).size[0]
    for beat, w in (("B3", layers.FRAG_W_B3), ("B4", layers.FRAG_W_B4),
                    ("C1", layers.FRAG_W_C1), ("C3", layers.FRAG_W_C3)):
        shown_w = S.PROOF_SRC_W * (w / crop_w)     # what the <img> is actually set to
        out.append((f"proof crop ({beat})", f"{src_w}px source",
                    round(shown_w), shown_w / src_w, True))

    cw, ch = layers.CAPTURE
    out.append((f"take into the seat", f"{cw}x{ch}", layers.DEV_W,
                layers.DEV_W / cw, False))
    return out


def needed_source_width() -> int:
    """How wide the App info capture has to be for the proof never to be upscaled.

    This is a property of the layout alone, so it must not depend on whatever image happens
    to be at APPINFO. It used to divide by the current source's width, which meant that with
    the 720 px stand-in in place it answered "you need 594 px" while that same stand-in was
    being upscaled 1.67x: the number went down exactly when the problem appeared.
    """
    return round(S.PROOF_SRC_W * (layers.FRAG_W_B3 / S.PROOF_CROP[2]))


def main() -> int:
    gate = "--gate" in sys.argv
    bad = []
    print(f"{'asset':<36}{'source':>14}{'shown':>8}{'scale':>8}  verdict")
    print("-" * 88)
    for name, src, shown, sc, is_type in rows():
        if src == "MISSING":
            print(f"{name:<36}{'MISSING':>14}{'':>8}{'':>8}  cannot audit")
            bad.append(name)
            continue
        if sc <= LIMIT:
            v = "ok"
        else:
            v = f"UPSCALED {sc:.2f}x" + (" on TYPE" if is_type else "")
            bad.append(name)
        print(f"{name:<36}{src:>14}{shown:>8}{sc:>8.2f}  {v}")

    need = needed_source_width()
    print(f"\nFor the proof never to be upscaled, the App info capture has to be about "
          f"{need} px wide.")
    print("No phone screen is. Raise `wm size` and `wm density` together so Android renders "
          "the\nsame dp layout at more pixels, then `screencap`. See shoot.py --proof.")

    if bad:
        print(f"\n{len(bad)} asset(s) would be upscaled in the final cut:")
        for b in bad:
            print(f"  {b}")
        if gate:
            print("\nRefusing to build a deliverable. Fix the source, not the layout.")
            return 1
    else:
        print("\nNothing is upscaled.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
