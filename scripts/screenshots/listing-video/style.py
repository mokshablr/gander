#!/usr/bin/env python3
"""Palette, type scale and geometry. The film's half of the store design system.

Taken from `../store-art/pano.py` and `../store-art/fg7.py` rather than reinvented, so the
video and the seven shipped Play screenshots are provably the same system. Nothing here is
a preference; every value has a source, named beside it.

**The hero sets at 152 px, not the 300 px first specified, and that was a measured
correction rather than a taste one.** Do not confuse it with `HERO_STACK`, which is 168: that
is the pitch between stacked hero lines, derived further down from the ink this size actually
makes. The 300 figure came from scaling `pano.py`'s 168 px hero from a 1080-wide portrait
frame to a 1920-wide one. It does not survive landscape:
"everything." sets 1419 px at 300 px, which leaves 381 px of a 1920 frame for the card row
and makes `fg7.py`'s composition impossible. The deeper reason is that a 1920x1080 frame
shown at Play's carousel width is 169 px tall where a 1080x1920 screenshot is 533 px, so a
landscape film has a third of the vertical room and cannot carry the portrait type scale.

The right precedent is the feature graphic, which is landscape and which Play already shows
at browse size: `fg7.py` sets its hero at 96 px on a 1024 canvas, about 9.4% of the frame
width, and 152 px on 1920 is 7.9% of it, in the same band, which makes A1 an ordinary beat
rather than a special case. **The binding constraint is width, measured not guessed:**
"everything." is the film's widest hero word, and a beat that keeps the card row has only
780 px to give it, because `fg7.py`'s leftmost card sits at x=480 on its 1024 canvas, which
is x=900 here. Sizes above this print the hero on top of the cards. Every other size below is a ratio against the hero, sourced in the
comment beside it. **Re-run `measure.py` after touching any of this**: it measures the real
strings in Jost against each beat's real room, which is the only check that catches this.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Canvas and geometry
# ---------------------------------------------------------------------------

W, H = 1920, 1080
M = 120           # left margin; fg7.py's 64-on-1024, same proportion
GRID = 8          # every coordinate is a multiple of this, per fg7.py

# How wide a type block may be when it owns the whole frame. **The only room constant here,
# on purpose.** A beat that shares the frame with the card row or a device has its room
# computed by `layers.py` from where those things actually sit, and `measure.py` asks
# `layers.py` rather than restating it. Two more constants used to live here, both 780, both
# read by nothing: restating a number that layout already knows is how two lines came to
# overflow silently when the card row moved.
ROOM_FULL = W - M * 2        # 1680: type owns the frame

# ---------------------------------------------------------------------------
# Palette. pano.py's custom properties, plus fg7.py's dim and paper.
# Four caption colours and no fifth. A fifth would need a reason, and there isn't one.
# ---------------------------------------------------------------------------

INK = "#F8EFE0"
HOT = "#E2795F"
MUTED = "#CFC5B2"
DIM = "#6F6758"
PAPER = "#EFE9DD"

# The nine file-kind badges, verbatim from WELCOME_BADGES in MainActivity.kt. These are the
# app's own colours and the film uses no others for a chip.
BADGES = [
    ("PDF", "#B3261E"), ("DOC", "#1565C0"), ("XLS", "#2E7D32"),
    ("PPT", "#B25000"), ("IMG", "#7B1FA2"), ("VID", "#AD1457"),
    ("AUD", "#00838F"), ("MD",  "#455A64"), ("TXT", "#616161"),
]

# pano.py's canvas background. The two radial pools are given in percentages of the frame
# rather than the pixel sizes pano uses, because pano's are measured against a 7560 px
# canvas and would read as pinpricks on a single 1920 frame. The linear stop list and every
# colour are unchanged.
GROUND = (
    "radial-gradient(200% 110% at 12% -10%, rgba(226,121,95,.10), transparent 62%),"
    "radial-gradient(220% 130% at 78% 118%, rgba(120,150,210,.07), transparent 62%),"
    "linear-gradient(97deg,#1A140D 0%,#131009 30%,#191309 58%,#120E08 82%,#0E0B06 100%)"
)

# pano.py's vignette, so the frame does not read as a flat swatch.
VIGNETTE = ("linear-gradient(to bottom, rgba(0,0,0,.34) 0%, transparent 16%,"
            " transparent 76%, rgba(0,0,0,.42) 100%)")

# ---------------------------------------------------------------------------
# Type scale
# ---------------------------------------------------------------------------

FAMILY = "Jost,sans-serif"
GOOGLE_FONTS = ("https://fonts.googleapis.com/css2?"
                "family=Jost:wght@400;500;600;700&display=block")


class Role:
    """One type style: size in px, weight, tracking in em, line height."""

    def __init__(self, px: int, weight: int, track: float, lh: float, colour: str):
        self.px, self.weight, self.track, self.lh, self.colour = px, weight, track, lh, colour

    def css(self) -> str:
        return (f"font-size:{self.px}px;font-weight:{self.weight};"
                f"letter-spacing:{self.track}em;line-height:{self.lh};color:{self.colour}")


# pano.py: h1 is 168/700/-.045em/.92 and .kick is 44/500/1.3 with a 34 px top margin. The
# film's hero is the same 168, so pano's ratios carry over unscaled; the two roles that come
# from fg7 instead are marked, and they are scaled by 1920/1024 from that file's 1024 canvas.
ROLES = {
    "hero":      Role(152, 700, -0.045, 0.92, INK),
    # B1 and C3's opening line. **Not `statement` at 88/500, which is what it was until
    # 2026-09-12 and which is fg7's *kicker* style doing a headline's job.** Standing alone
    # on an empty frame a medium-weight geometric face at 88 px reads as under-set, and it
    # left 773 px of the frame empty to its right. At 120/700 it measures 1469 px, fills the
    # frame the way every other beat does, and sits in the same family as the hero without
    # competing with it.
    "lead":      Role(120, 700, -0.025, 1.15, INK),
    "statement": Role(88,  500, -0.020, 1.20, INK),   # fg7's kicker, scaled
    "sub":       Role(56,  500,  0.000, 1.35, MUTED),
    "kicker":    Role(46,  500,  0.000, 1.30, MUTED),  # pano's .kick ratio
    "small":     Role(44,  500,  0.015, 1.50, MUTED),
    "caption":   Role(32,  500,  0.015, 1.45, MUTED),  # fg7's .sub, scaled
    "tiny":      Role(25,  500,  0.050, 1.55, DIM),    # fg7's .fmt, scaled
}

# Gap under a hero block before its kicker. pano.py uses 34 px against a 168 px hero; this
# is the same ratio taken to the grid.
HERO_TO_KICKER = 40

# Baseline-to-baseline for stacked hero lines. **Not `hero.px * hero.lh`.**
#
# pano.py sets line-height .92, and copying that gave a 140 px advance against a measured
# ink height of 157 px at 152 px type, so stacked lines overlapped by 17 px. C4 showed it
# plainly: the descenders of "Opens everything." ran into the ascenders of "Takes nothing.".
# pano gets away with .92 because its heroes are two short words whose descenders and
# ascenders happen not to line up horizontally, which is luck rather than design, and this
# film sets whole sentences.
#
# 168 px is the measured 157 plus 11 of clearance. Measure again with measure.py if the
# hero size ever changes; ink is 1.033 em for this face at this weight.
HERO_STACK = 168

# ---------------------------------------------------------------------------
# The proof, and the one legibility rule that outranks composition
# ---------------------------------------------------------------------------
#
# "No permissions requested" is not a caption in this film. It is the evidence, and it is
# the only thing on screen that a competitor with nineteen permissions cannot also show. If
# it does not read at the size the film is actually watched, the film has no argument.
#
# **So this constraint wins over composition.** If a real capture comes back smaller than
# expected, or a beat gets crowded, the fragment grows and the layout gives way, not the
# other way round. `check_proof()` enforces it and `render.py` refuses to render below it.
#
# The measurement, taken off docs/press/assets/gander-permissions.png at threshold 205
# because both proof rows are deliberately greyed out and a normal ink threshold misses
# them entirely:
#
#     "Permissions"              y 718-743   h 26px   x  45-240
#     "No permissions requested" y 761-782   h 22px   x  44-328
#
# The crop takes both rows with padding. Taking the Notifications row above them as well,
# which the first pass did, costs a third of the magnification to show a line that proves
# nothing.
# **Re-derived 2026-09-12 from the real device**, a 3240 px render captured by
# `shoot.py --proof`, cropped to the two Permissions rows using their exact bounds from the
# view tree rather than a pixel threshold. The crop is already the proof block alone, so
# PROOF_CROP covers the whole file.
PROOF_SRC_W = 1455
PROOF_CROP = (0, 0, 1455, 471)
PROOF_GLYPH_H = 104                  # "No permissions requested" glyph height, source px

# What Play's carousel is about, and the size every legibility judgement is made at.
# store-art/README.md checks the feature graphic at 240px for the same reason.
CAROUSEL_W = 300

# Minimum rendered height of that phrase, in pixels, with the frame shown at CAROUSEL_W.
# Below about 8px a lowercase run stops resolving into words on a phone; 11 leaves margin
# for the viewer's own downscaling and for a capture with less detail than the stand-in.
# The first pass measured 3.1px, which is why this constant exists.
PROOF_MIN_PX = 11.0


def proof_px_at_carousel(frag_w: float) -> float:
    """Rendered height of "No permissions requested" when the frame is shown at CAROUSEL_W.

    `frag_w` is the fragment's width in frame pixels.
    """
    scale_in_frame = frag_w / PROOF_CROP[2]
    return PROOF_GLYPH_H * scale_in_frame * (CAROUSEL_W / W)


def min_frag_w() -> float:
    """The narrowest fragment that still meets PROOF_MIN_PX."""
    return PROOF_MIN_PX / (CAROUSEL_W / W) / PROOF_GLYPH_H * PROOF_CROP[2]


# ---------------------------------------------------------------------------
# Motion grammar.
# ---------------------------------------------------------------------------

RISE_PX = 24          # rule 1: arrivals rise 24 px
FRAG_SCALE = 2.6      # rule 7: B3 lifts the App info row to this magnification
FRAG_ROT = 1.6        # degrees, matching the store screenshots' fragment tilt

# fg7.py's elevation language: one soft layered shadow and no outline. A border and a heavy
# shadow on the same element is what made the earlier cards look pasted on.
SHADOW_CARD = ("0 1px 2px rgba(0,0,0,.30),0 10px 20px rgba(0,0,0,.30),"
               "0 30px 60px rgba(0,0,0,.26)")

# pano.py's .frag, which is the one element allowed an outline, because it has to read as a
# cutout rather than as an object.
SHADOW_FRAG = ("0 2px 4px rgba(0,0,0,.6), 0 22px 44px rgba(0,0,0,.58),"
               " 0 60px 120px rgba(0,0,0,.55)")
FRAG_CHROME = (f"border-radius:26px;background:#191410;"
               f"outline:1px solid rgba(248,239,224,.16);outline-offset:-1px;"
               f"box-shadow:{SHADOW_FRAG}")


def snap(x: float) -> int:
    """Nearest multiple of GRID. fg7.py's rule, applied to computed positions too."""
    return int(round(x / GRID) * GRID)


def head(extra_css: str = "", alpha: bool = False) -> str:
    """The shared document head. Every render loads Jost the same way store-art does.

    `alpha` drops the body background. **It is not optional for a transparent render and it
    has caught the same pipeline out twice.** Chrome's `--default-background-color=00000000`
    makes the viewport transparent but does nothing about an opaque `body{background}`, so a
    plate meant to carry alpha comes back solid black and paints over whatever it is
    composited onto. It defeated the first attempt at punching a hole through the drawn
    layer, and then defeated the over-plate in compose.py in exactly the same way.
    """
    bg = "" if alpha else "background:#000;"
    return ('<!doctype html><html><head><meta charset="utf-8">'
            f'<link href="{GOOGLE_FONTS}" rel="stylesheet">'
            f'<style>*{{box-sizing:border-box;margin:0}}'
            f'body{{margin:0;{bg}font-family:{FAMILY}}}'
            f'{extra_css}</style></head><body>')
