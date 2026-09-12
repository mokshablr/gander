#!/usr/bin/env python3
"""The drawn layer: one 1920x1080 frame of the film, as HTML, for any time t.

Everything the film draws lives here. Nothing here knows what time it is on its own; it
asks `timeline.py`. Nothing here contains a user-visible string; it asks `words.py`.
Nothing here picks a colour or a size; it asks `style.py`. That split is what lets the
28-to-30-second question, an A/B on the line, and a palette change each be a one-file edit.

**Footage is not composited here.** Beats A3, B2, B3 and C2 are recorded, and this module
draws their ground, their card chrome and their captions, then leaves a labelled hole where
the capture goes and records that hole's rectangle in the frame manifest. That is what lets
the whole film be laid out, reviewed and corrected before anyone picks up a phone, which is
the entire reason the caption layer was built first.

Rendered by `render.py`. Run this file directly for a one-frame dump to stdout.
"""

from __future__ import annotations

import inspect
import pathlib

import style as S
import words as T
from timeline import DISSOLVE_IN, FPS, RISE_PX, Timeline

REPO = pathlib.Path(__file__).resolve().parents[3]
PAGES = REPO / "scripts/screenshots/store-art/pages"
LOCKUP = REPO / "scripts/screenshots/store-art/lockup.png"

# The capture Android's App info screen was shot on, used as the B3 fragment's stand-in so
# the composition can be judged before the real take exists. 720x813, cropped to the
# Permissions row. This is a real screenshot of the real screen, so the placeholder is
# honest about proportions even though the final frames come from the new shoot.
# The proof still. `shoot.py --proof` writes it here: the App info screen rendered at 3x by
# raising wm size and density together, captured with screencap, and cropped to the two
# Permissions rows using their exact bounds from the view tree. 1455 px wide against the
# 1200 px B3 shows it at, so it downscales. The press kit's 720 px capture, which stood in
# until the shoot, left the film's most important element blown up 3.66x.
APPINFO = pathlib.Path(__file__).parent / "proof.png"
if not APPINFO.exists():
    APPINFO = REPO / "docs/press/assets/gander-permissions.png"


# ---------------------------------------------------------------------------
# Primitives
# ---------------------------------------------------------------------------

def _px(v: float) -> str:
    """Render a number the way CSS wants it, without a trailing .0 on whole pixels."""
    return f"{v:.2f}".rstrip("0").rstrip(".")


def rise(p: float) -> str:
    """The standard arrival, motion rule 1: opacity in, 24 px up, and then it stops."""
    return f"opacity:{_px(p)};transform:translateY({_px((1.0 - p) * RISE_PX)}px)"


def lockup_centred(y: int, w: int, p: float = 1.0) -> str:
    """The lockup, centred across the frame. B1 only; see its docstring."""
    return (f'<div style="position:absolute;left:0;top:{y}px;width:{S.W}px;'
            f'text-align:center;{rise(p)}">'
            f'<img src="{LOCKUP}" style="width:{w}px;display:inline-block"></div>')


def text(role: str, lines, x: int, y: int, p: float = 1.0,
         hot_from: int | None = None, centre: bool = False) -> str:
    """A type block. `lines` is a string or a tuple of them, set as one element.

    `hot_from` sets that line index and everything after it in coral, which is how a hero
    pair carries its second line and how the signature carries its second half.
    """
    if isinstance(lines, str):
        lines = (lines,)
    r = S.ROLES[role]
    body = []
    for i, line in enumerate(lines):
        colour = f"color:{S.HOT}" if hot_from is not None and i >= hot_from else ""
        body.append(f'<div style="{colour}">{line}</div>')
    box = f'left:0;width:{S.W}px;text-align:center' if centre else f'left:{x}px'
    return (f'<div style="position:absolute;{box};top:{y}px;white-space:nowrap;'
            f'{r.css()};{rise(p)}">' + "".join(body) + "</div>")


def split_hot(line: str, hot: str) -> str:
    """Set the tail of a line in coral, inline.

    The signature needs "Opens everything." with only "everything." in coral, which is a
    different shape from a hero pair: there the coral half is its own line and can arrive on
    its own cue, here it is part of one line and must not. Two ways of doing the same thing
    would drift, so this is the only place a line is split.
    """
    if not line.endswith(hot):
        raise ValueError(f"{hot!r} is not the tail of {line!r}")
    return f'{line[: -len(hot)]}<span style="color:{S.HOT}">{hot}</span>'


def hero_pair(ink: str, hot: str, x: int, y: int, p_ink: float, p_hot: float) -> str:
    """Two stacked hero words, the second in coral, arriving 120 ms apart.

    Set as two elements rather than one block precisely so they can arrive separately.
    Motion rule 2: that stagger is the only one in the film's type, and it is what makes a
    hero pair read as two beats rather than one slab.
    """
    r = S.ROLES["hero"]
    return (
        f'<div style="position:absolute;left:{x}px;top:{y}px;white-space:nowrap;'
        f'{r.css()};{rise(p_ink)}">{ink}</div>'
        f'<div style="position:absolute;left:{x}px;top:{y + S.HERO_STACK}px;'
        f'white-space:nowrap;{r.css()};color:{S.HOT};{rise(p_hot)}">{hot}</div>'
    )


def chip(label: str, colour: str, x: int, y: int, size: int, p: float = 1.0,
         rot: float = 0.0, z: int = 20) -> str:
    """One format badge, in the app's own colour.

    fg7.py's chip: a rounded square with the three-letter kind in white at 700. Its radius
    is 0.235 of its size and its type 0.34 of it, both kept here so a chip in the film and a
    chip on the feature graphic are the same object.
    """
    r = f"rotate({_px(rot)}deg) " if rot else ""
    return (f'<div style="position:absolute;left:{x}px;top:{y}px;width:{size}px;'
            f'height:{size}px;background:{colour};border-radius:{size * 0.235:.0f}px;'
            f'display:flex;align-items:center;justify-content:center;color:#fff;'
            f'font-weight:700;letter-spacing:.02em;font-size:{size * 0.34:.0f}px;'
            f'box-shadow:{S.SHADOW_CARD};z-index:{z};opacity:{_px(p)};'
            f'transform:{r}translateY({_px((1.0 - p) * RISE_PX)}px)">{label}</div>')


def card(x: int, cy: int, w: int, src: str | None, rot: float, bright: float,
         p: float = 1.0, z: int = 2) -> str:
    """A document card: a window onto a page, not a whole page shrunk into a thumbnail.

    fg7.py's ZOOM lesson, and it is the single biggest lever on whether this reads as
    documents or as grey noise. A whole 1080 px page inside a 315 px card puts 14 px body
    text at 4 px, under the resolution floor, where it smears however it is rendered. The
    image is therefore set 1.75x the card's width, showing a window onto the document.

    `src` of None draws blank paper. Nothing uses it today: the rank-two cards it was added
    for were cut, for the reasons recorded above A2_CHIP. It is kept because a beat added
    later may want paper without a document on it, and because inventing it again would be
    the moment someone reaches for a PDF crop to sit behind a VID chip instead.
    """
    h = round(w * 1.5)
    top = cy - h // 2
    inner = (f'<img src="{PAGES}/{src}" style="position:absolute;left:0;top:0;'
             f'width:{w * 1.75:.0f}px;display:block">') if src else ""
    return (f'<div style="position:absolute;left:{x}px;top:{top}px;width:{w}px;'
            f'height:{h}px;z-index:{z};overflow:hidden;border-radius:8px;'
            f'background:{S.PAPER};box-shadow:{S.SHADOW_CARD};'
            f'transform:rotate({_px(rot)}deg);filter:brightness({_px(bright)});'
            f'opacity:{_px(p)}">{inner}</div>')


def rule(x: int, y: int, w: int, p: float = 1.0) -> str:
    """The hairline. Draws left to right rather than fading, so it reads as a stroke."""
    return (f'<div style="position:absolute;left:{x}px;top:{y}px;width:{w * p:.0f}px;'
            f'height:1px;background:rgba(248,239,224,.15)"></div>')


def lockup(x: int, y: int, w: int, p: float = 1.0) -> str:
    return (f'<img src="{LOCKUP}" style="position:absolute;left:{x}px;top:{y}px;'
            f'width:{w}px;display:block;{rise(p)}">')


def frag(x: int, y: int, w: int, scale: float = 1.0, p: float = 1.0,
         rot: float = 0.0, placeholder: bool = True) -> str:
    """The proof, lifted out of the capture and floated in front of the device.

    pano.py's `.frag`: the one element in the film allowed an outline, because it has to
    read as a cutout rather than as an object. In the finished film its content is a crop of
    the live B3 frame; until the shoot exists it stands in with the press kit's own capture
    of the same screen, which is the real screen at the real proportions.

    **`w` is governed by legibility, not by composition.** `style.PROOF_MIN_PX` is the rule
    and `check_proof()` enforces it: this thing has to be readable when the frame is shown at
    Play's carousel width, because it is the film's entire argument, and a layout that
    squeezes it is a layout that has to give way. See the comment above `PROOF_CROP`.
    """
    cx, cy, cw, ch = S.PROOF_CROP
    k = w / cw                      # source-to-frame scale
    h = round(ch * k)
    # **Always drawn.** This used to be gated on `placeholder`, which meant the real render
    # produced an empty dark card: the film's entire proof, missing, in the one beat it
    # exists for. `placeholder` distinguishes a labelled footage hole from a seat; it was
    # never meant to decide whether the proof has content.
    img = ""
    if APPINFO.exists():
        img = (f'<img src="{APPINFO}" style="position:absolute;'
               f'left:{-cx * k:.1f}px;top:{-cy * k:.1f}px;'
               f'width:{S.PROOF_SRC_W * k:.1f}px;display:block">')
    return (f'<div style="position:absolute;left:{x}px;top:{y}px;width:{w}px;height:{h}px;'
            f'overflow:hidden;{S.FRAG_CHROME};'
            f'transform:rotate({_px(rot)}deg) scale({_px(scale)});'
            f'opacity:{_px(p)}">{img}</div>')


# Steps in a hole's progress bar. Quantised rather than continuous on purpose: the drawn
# layer is deduplicated by frame before rendering, and a smooth bar would make every frame of
# a footage beat distinct, turning a 349-frame render into a 1,100-frame one to animate
# something that is a placeholder anyway.
HOLE_STEPS = 12


def hole(x: int, y: int, w: int, h: int, clip: str, note: str = "",
         p: float = 0.0, alpha: bool = False) -> str:
    """Where a recorded take goes. Drawn as a card so the composition is judgeable now.

    This is the whole point of building the caption layer first: every frame can be laid
    out, measured and corrected before a phone is picked up, and the shoot then has an exact
    rectangle to fill rather than a description.

    `p` is how far through its own beat the take is, drawn as a stepped bar. Without it a
    preview render holds a motionless box for 2.8 seconds and the beat reads as dead air
    rather than as footage, which makes the one thing a preview is for, judging timing,
    impossible on exactly the beats that carry the argument.
    """
    if alpha:
        # The seat the recorded take sits in. Just the card's shadow and a dark well, no
        # label and no progress bar.
        #
        # **It is not transparent, and it deliberately is not.** The first attempt punched a
        # hole with `mix-blend-mode: destination-out` so footage could show through from
        # underneath, and that was wrong twice over: Chrome composites the page onto an
        # opaque body background so the hole came back solid, and more fundamentally a
        # dissolving plate carries `opacity < 1`, which creates a stacking context and
        # confines the blend to that plate rather than erasing the frame beneath it. The
        # footage would have appeared instantly inside a soft cut.
        #
        # So the take goes **on top** of this plate rather than behind it, masked to the same
        # rounded rect, with its opacity following the dissolve. No blend modes anywhere, one
        # render path, and the cross-fade falls out for free. `compose.py` does the placing;
        # this only has to look right underneath.
        return (f'<div style="position:absolute;left:{x}px;top:{y}px;width:{w}px;'
                f'height:{h}px;border-radius:28px;background:#12100B;'
                f'box-shadow:{S.SHADOW_CARD}"></div>')

    step = round(p * HOLE_STEPS) / HOLE_STEPS
    bar = (f'<div style="position:absolute;left:12%;bottom:10%;width:76%;height:6px;'
           f'background:rgba(226,121,95,.18);border-radius:3px">'
           f'<div style="width:{step * 100:.2f}%;height:100%;background:rgba(226,121,95,.55);'
           f'border-radius:3px"></div></div>')
    return (f'<div style="position:absolute;left:{x}px;top:{y}px;width:{w}px;height:{h}px;'
            f'border-radius:28px;background:#12100B;box-shadow:{S.SHADOW_CARD};'
            f'outline:2px dashed rgba(226,121,95,.45);outline-offset:-2px;'
            f'display:flex;flex-direction:column;align-items:center;'
            f'justify-content:center;color:{S.DIM};font-size:34px;font-weight:500;'
            f'letter-spacing:.04em;text-align:center;line-height:1.6">'
            f'<div>{clip}</div><div style="font-size:24px">{w}x{h}</div>'
            f'<div style="font-size:24px;max-width:80%">{note}</div>{bar}</div>')


# ---------------------------------------------------------------------------
# Layout. Every number here is on the 8 px grid, per fg7.py.
# ---------------------------------------------------------------------------

# A1 reproduces fg7's composition. Its stack is fg7's own, scaled from a 500-tall canvas to
# 1080, and its cards are fg7's CARDS with x and width scaled from 1024 to 1920.
A1_STACK = {"lock": 208, "kicker": 400, "hero": 520, "rule": 792, "sub": 848, "fmt": 920}
A1_LOCK_W, A1_RULE_W = 300, 672

# How far each card drifts during A1, by depth. **Unified direction, not a spread.** The
# first version moved the two rear cards left and the two front cards right, which fanned
# the row open over 0.8 s and read as the pages coming apart rather than as the poster
# breathing. Same magnitude the other way is a parallax: everything moves together, the
# front card furthest, which is what depth looks like when a camera moves at all.
#
# **Raised 2026-09-12, and motion rule 3 was raised with it.** At 15 px the front card moved
# 2.3 px at Play's carousel width across the whole 0.8 s and the rear card moved 0.8 px, so
# the opening second was a still on the surface the film is mostly watched on. The old cap of
# 40 px over 2 s is 20 px/s, which is 3 px/s of movement anyone actually sees once a 1920
# frame is shown at 300. These run to 50 px/s at the front, putting it at about 6 px of travel
# at carousel width: visible as motion without becoming a move, and every still frame is still
# a valid composition, which is what the rule protects.
A1_DRIFT_PX = (14, 22, 30, 40)


def a1_card_x(i: int, p: float) -> int:
    """Where card `i` sits at drift progress `p`. **The only definition of this.**

    A2 asks for p=1.0 rather than restating the end position, which is what makes the cut
    between the two beats continuous. It was not: A1 ended with XLS at 884 and PPT at 1124,
    A2 restated both as x+16, and the two cards jumped 32 px right in a single frame at
    t=1.000. The comment in _beat_A2 claimed they were continuous the whole time.
    """
    return round(A1_CARDS[i][0] + A1_DRIFT_PX[i] * p)
A1_CARDS = [
    # x,    cy,   w,   src,       fmt,   rot,  bright
    (900,  691, 315, "xls.png", "XLS", -3.0, 0.66),
    (1140, 657, 375, "ppt.png", "PPT", -1.0, 0.78),
    (1380, 622, 435, "img.png", "IMG",  1.0, 0.90),
    (1620, 536, 525, "pdf.png", "PDF",  3.0, 1.00),
]

# A2 keeps those four cards exactly where A1 left them, so the two beats are continuous and
# nothing jumps across the cut. It adds no cards at all.
#
# **Five more were meant to fan in from behind to make nine. That was built and cut.**
# Two reasons, both found by rendering it. Real page crops exist for five document kinds and
# have never been captured for VID, AUD, MD or TXT, so four of the nine were blank paper, and
# at the brightness a rear card wants they read as grey slabs, like a loading state rather
# than like documents. Putting a PDF crop behind a VID chip instead would fill the slot with
# a small lie, in a film whose entire argument is that it does not tell them. And the row had
# nowhere to go: the hero needs the left 840 px and fg7's leftmost card already sits at
# x=900, so nine cards would have been 20 px slivers at carousel width.
#
# The breadth claim moves to the chips, which is where it belongs: they are the app's own
# nine badges in the app's own colours, they are the same row C1 brings back, and a row of
# colour survives being shown small in a way a list of words at 44 px does not.
A2_CHIP, A2_CHIP_GAP = 64, 10
# The device, treated as a card rather than as a phone. "Screen only, at fg7's card
# elevation": one layered shadow, no outline, so the recorded beats and the
# document cards are one object family.
#
# **The seat's aspect is the phone's, and it was not.** DEV_W was derived from an assumed
# 1080x1920 capture, giving 528x944, and the real device is 1080x2412. trim.py dutifully
# forced every take into that shape by cropping 246 px off the top and 246 off the bottom,
# which cut away the status bar and the navigation bar and read, correctly, as wrong.
#
# 1080x2412 is 0.4478. At 944 tall the seat is 424 wide, which is 0.4492: a third of a
# percent out, invisible, and it keeps the whole screen in frame. **A capture at a different
# aspect must change these numbers, not be cropped to fit them.** check_takes in compose.py
# refuses a take whose aspect does not match.
CAPTURE = (1080, 2412)
DEV_H = 944
DEV_W = round(DEV_H * CAPTURE[0] / CAPTURE[1] / S.GRID) * S.GRID   # 424
DEV_Y = (S.H - DEV_H) // 2

# **The device is centred while it is the only thing in the frame, and slides right as the
# proof lifts out of it.**
#
# It used to be pinned right for the whole film, which left B2 with about 1,150 px of dead
# frame beside a phone, justified only by type that does not arrive until B4. Centring it
# balances the beat on its own terms, and the slide gives the fragment a reason to appear
# where it does: the device moves aside and the proof fills the space it vacated.
#
# This is a deliberate exception to motion rule 4, which says the device never moves because
# a still phone with a moving screen reads as a recording and a moving one reads as a render.
# One motivated reframe is not drift. It happens once, it is 500 ms, and it is the moment the
# lift stops being centred and resolves into the film's left-type/right-picture grammar.
DEV_X_CENTRE = S.snap((S.W - DEV_W) / 2)          # 748
DEV_X_RIGHT = 1400                                # clear of the fragment's resting edge
DEV_X = DEV_X_RIGHT                               # the resting position, for static callers


def dev_x(tl: Timeline, t: float) -> int:
    """Where the device sits at time `t`. Centred until B3's slide, then right.

    A3 and B2 are centred because nothing else is in the frame. C2 is not: it carries its
    kicker bottom-left and has always been composed against it.
    """
    beat = tl.beat_at(t)
    if beat.tag == "C2":
        return DEV_X_RIGHT
    if beat.tag != "B3":
        return DEV_X_CENTRE
    p = tl.p(t, "B3.slide")
    return round(DEV_X_CENTRE + (DEV_X_RIGHT - DEV_X_CENTRE) * p)

# Type column for every beat that sets type beside the device or the card row.
COL_X = S.M

# Fragment widths. The floor is style.min_frag_w(), currently 1050 px, and the three beats
# where the proof is being made sit above it. C3 is deliberately below: by then the viewer
# has had the phrase in front of them for eight seconds across B3 and B4, so that beat asks
# for recognition rather than reading, and the line beside it states the meaning in the
# film's own type. check_proof() knows the difference and only holds the first three to the
# floor.
FRAG_W_B3 = 1200        # the reveal; the largest, because it is the first read
FRAG_W_B4 = 1120        # the lift's end card, which is what tweet 1 autoplays to
FRAG_W_C1 = 1120
FRAG_W_C3 = 480         # recall, not proof; sits right of the type like B4 and C1


def type_room(beat: str, role: str = "") -> tuple[int, int]:
    """(x, width) available to a type block, by beat and by the role set in it.

    **measure.py reads this rather than restating it.** That file used to carry its own copy
    of the geometry and assume every block began at the left margin, which is how C3's first
    line ran off the right edge of the frame while the measurement reported it fitting.

    It is per-role and not just per-beat because a beat's right-hand element does not span
    the whole frame height. C1 holds its fragment between y=392 and y=733 and sets its
    closing line at y=924, underneath it, with the full width available; constraining that
    line to the fragment's column reports a 91 px overflow that does not exist.
    """
    below_the_fragment = {("C1", "small")}
    if (beat, role) in below_the_fragment:
        return COL_X, S.ROOM_FULL
    if beat in ("A1", "A2"):
        return COL_X, A1_CARDS[0][0] - COL_X          # to the leftmost card
    if beat in ("A3", "B2", "B3", "C2"):
        return COL_X, DEV_X - COL_X                    # to the device
    if beat in ("B4", "C1"):
        return COL_X, 744 - COL_X                      # to the resting fragment
    if beat == "C3":
        return COL_X, 1320 - COL_X
    return COL_X, S.ROOM_FULL


def chip_row(tl: Timeline, t: float, ref: str, x: int, y: int) -> list[str]:
    """The nine, in one flat row, in the app's own colours.

    Shared by A2 and C1 so the row in the payoff is the same object as the row in the setup
    rather than a lookalike. That identity is the whole point: A2 says what it opens, B says
    what it takes, and C1 puts the two in one frame.
    """
    step = A2_CHIP + A2_CHIP_GAP
    return [chip(label, colour, x + i * step, y, A2_CHIP, p=tl.p(t, ref, index=i))
            for i, (label, colour) in enumerate(S.BADGES)]


def _a1_type(alpha: float = 1.0) -> str:
    """A1's type block: the feature graphic's own stack.

    Shared with A2's dissolve source. Wrapped in one opacity layer rather than fading each
    element, so the block goes as a unit.
    """
    parts = [lockup(COL_X, A1_STACK["lock"], A1_LOCK_W),
             text("statement", T.A1_KICKER, COL_X, A1_STACK["kicker"]),
             text("hero", T.A1_HERO, COL_X, A1_STACK["hero"], hot_from=0),
             rule(COL_X, A1_STACK["rule"], A1_RULE_W),
             text("caption", T.A1_SUB, COL_X, A1_STACK["sub"]),
             text("tiny", T.A1_FMT, COL_X, A1_STACK["fmt"])]
    body = "".join(parts)
    if alpha >= 1.0:
        return body
    return f'<div style="opacity:{_px(alpha)}">{body}</div>'


def _a1_cards(p: float) -> list[str]:
    """The four cards at drift progress `p`, with their chips."""
    out = []
    for i, (_x, cy, w, src, fmt, rot, bright) in enumerate(A1_CARDS):
        x = a1_card_x(i, p)
        out.append(card(x, cy, w, src, rot, bright + 0.04 * p * (1 if i < 2 else 0),
                        z=2 + i * 2))
        cw = round(w * 0.17 / 2) * 2
        out.append(chip(fmt, dict(S.BADGES)[fmt], round(x - cw * 0.38),
                        cy + round(w * 1.5) // 2 - cw, cw, z=3 + i * 2, rot=rot))
    return out


def _beat_A1(tl: Timeline, t: float) -> tuple[str, list]:
    p = tl.p(t, "A1.drift")
    return _a1_type() + "".join(_a1_cards(p)), []


def _beat_A2(tl: Timeline, t: float) -> tuple[str, list]:
    # p=1.0, not a restated end position. See a1_card_x.
    parts = _a1_cards(1.0)
    # A1's type is not drawn here. The frame-level cross-dissolve in frame_div handles the
    # hand-over, which is the same mechanism every other boundary uses rather than a second
    # one just for this beat. Because A1 and A2 share their cards exactly, dissolving the
    # whole frame dissolves only what actually differs, which is the type.
    r = S.ROLES["hero"]
    hero_y = 176
    kick_y = hero_y + S.HERO_STACK * 2 + S.HERO_TO_KICKER
    kr = S.ROLES["small"]
    chips_y = round(kick_y + kr.px * kr.lh * 2 + 64)
    parts.append(hero_pair(T.A2_HERO[0], T.A2_HERO[1], COL_X, hero_y,
                           tl.p(t, "A2.hero_ink"), tl.p(t, "A2.hero_hot")))
    # Set in `small` rather than `kicker`: at 46 px this line is 781 px and fg7's leftmost
    # card is at x=900, which leaves 780. Measured, not guessed. See measure.py.
    parts.append(text("small", T.A2_KICKER, COL_X, kick_y, tl.p(t, "A2.kicker")))
    parts.extend(chip_row(tl, t, "A2.chips", COL_X, chips_y))
    return "".join(parts), []


def _beat_A3(tl: Timeline, t: float, placeholder: bool = True) -> tuple[str, list]:
    b = tl["A3"]
    return (hole(dev_x(tl, t), DEV_Y, DEV_W, DEV_H, "a3_open",
                 "tap the first recent, PDF renders, one short scroll",
                 p=(t - b.start) / b.dur, alpha=not placeholder),
            [("a3_open", dev_x(tl, t), DEV_Y, DEV_W, DEV_H, "")])


def _beat_B1(tl: Timeline, t: float) -> tuple[str, list]:
    """The instruction, alone on the ground, and the one centred block in the film.

    Centred, unlike every other beat, because it is a title card: the lockup and one line on
    an empty frame, opening the lift. The film's grammar is type left and picture right, and
    the frame resolves into it at B3's slide, when the proof appears. Left-aligned this beat
    put a line at x=120 with 450 px of dead frame beside it and nothing to justify it.
    """
    p = tl.p(t, "B1.lockup")
    parts = [lockup_centred(408, 280, p),
             text("lead", T.B1_LINE, 0, 560, tl.p(t, "B1.line"), centre=True)]
    return "".join(parts), []


def _beat_B2(tl: Timeline, t: float, placeholder: bool = True) -> tuple[str, list]:
    b = tl["B2"]
    return (hole(dev_x(tl, t), DEV_Y, DEV_W, DEV_H, "b2_walkout",
                 "one unbroken take: home, long-press icon, App info",
                 p=(t - b.start) / b.dur, alpha=not placeholder),
            [("b2_walkout", dev_x(tl, t), DEV_Y, DEV_W, DEV_H, "")])


def _beat_B3(tl: Timeline, t: float, placeholder: bool = True) -> tuple[str, list]:
    b = tl["B3"]
    parts = [hole(dev_x(tl, t), DEV_Y, DEV_W, DEV_H, "b3_appinfo",
                  "same take continues, scroll settles on Permissions",
                  p=(t - b.start) / b.dur, alpha=not placeholder)]
    over = []
    p = tl.p(t, "B3.fragment")
    if p > 0:
        # Grows from the row's own place on the device to its resting size, so the eye can
        # follow where it came from. Motion rule 7: never a push into the phone.
        w = FRAG_W_B3
        x0, y0 = dev_x(tl, t) + 40, DEV_Y + 520
        x1, y1 = 160, 520
        # Into `over`, not `parts`: this is the one element in the film that has to be drawn
        # above the recorded take, and compose.py lays it over the footage.
        over.append(frag(round(x0 + (x1 - x0) * p), round(y0 + (y1 - y0) * p), w,
                         scale=1.0 + (S.FRAG_SCALE / 2.6 - 1.0) * p, p=p,
                         rot=S.FRAG_ROT * p, placeholder=placeholder))
    return "".join(parts), [("b3_appinfo", dev_x(tl, t), DEV_Y, DEV_W, DEV_H,
                             "".join(over))]


def _beat_B4(tl: Timeline, t: float, placeholder: bool = True) -> tuple[str, list]:
    p_travel = tl.p(t, "B4.frag_travel")
    x = round(160 + (744 - 160) * p_travel)
    y = round(520 + (392 - 520) * p_travel)
    hero_y = 260
    r = S.ROLES["hero"]
    kick_y = hero_y + S.HERO_STACK * 2 + S.HERO_TO_KICKER
    parts = [frag(x, y, FRAG_W_B4, rot=S.FRAG_ROT, placeholder=placeholder),
             hero_pair(T.B4_HERO[0], T.B4_HERO[1], COL_X, hero_y,
                       tl.p(t, "B4.hero_ink"), tl.p(t, "B4.hero_hot")),
             text("kicker", T.B4_KICKER, COL_X, kick_y, tl.p(t, "B4.kicker"))]
    return "".join(parts), []


def _beat_C1(tl: Timeline, t: float, placeholder: bool = True) -> tuple[str, list]:
    r = S.ROLES["hero"]
    hero_y = 220   # 40 px higher than B4: this beat stacks chips and a line under the kicker
    kick_y = hero_y + S.HERO_STACK * 2 + S.HERO_TO_KICKER
    kr = S.ROLES["kicker"]
    chips_y = round(kick_y + kr.px * kr.lh * 2 + 88)
    parts = [frag(744, 392, FRAG_W_C1, rot=S.FRAG_ROT, placeholder=placeholder),
             hero_pair(T.B4_HERO[0], T.B4_HERO[1], COL_X, hero_y, 1.0, 1.0),
             text("kicker", T.B4_KICKER, COL_X, kick_y)]
    parts.extend(chip_row(tl, t, "C1.chips", COL_X, chips_y))
    parts.append(text("small", T.C1_LINE, COL_X, chips_y + A2_CHIP + 56,
                      tl.p(t, "C1.line")))
    return "".join(parts), []


def _beat_C2(tl: Timeline, t: float, placeholder: bool = True) -> tuple[str, list]:
    kick_y = S.H - 260
    b = tl["C2"]
    parts = [hole(dev_x(tl, t), DEV_Y, DEV_W, DEV_H, "c2_night",
                  "day page with the plate, night mode on from the overflow",
                  p=(t - b.start) / b.dur, alpha=not placeholder),
             text("kicker", T.C2_KICKER, COL_X, kick_y, tl.p(t, "C2.kicker"))]
    return "".join(parts), [("c2_night", dev_x(tl, t), DEV_Y, DEV_W, DEV_H, "")]


def _beat_C3(tl: Timeline, t: float, placeholder: bool = True) -> tuple[str, list]:
    # The proof returns at its own size, not magnified: this beat is the derivation, and a
    # second 2.6x blow-up would compete with the line rather than support it.
    #
    # **Type left, fragment right, as in B4 and C1.** An earlier layout put the fragment on the
    # left here, reading cause then effect, which is a nice idea and does not survive
    # contact: at 120 px the first line is 1116 px and had only 860 px beside a left-hand
    # fragment, so it ran off the right edge of the frame. Flipping it costs the reading
    # order and buys one `lead` size across the film and a line that fits.
    parts = [text("lead", T.C3_LINE1, COL_X, 400, tl.p(t, "C3.line1")),
             text("sub", T.C3_LINE2, COL_X, 560, tl.p(t, "C3.line2")),
             frag(1320, 440, FRAG_W_C3, p=tl.p(t, "C3.fragment"), rot=S.FRAG_ROT,
                  placeholder=placeholder)]
    return "".join(parts), []


def _beat_C4(tl: Timeline, t: float) -> tuple[str, list]:
    r = S.ROLES["hero"]
    y = 340
    line_h = S.HERO_STACK
    parts = [lockup(COL_X, y - 200, 300, tl.p(t, "C4.lockup"))]
    for i, (line, hot) in enumerate(zip(T.C4_HERO, T.C4_HOT)):
        parts.append(text("hero", split_hot(line, hot), COL_X, y + i * line_h,
                          tl.p(t, f"C4.line{i + 1}")))
    parts.append(rule(COL_X, y + line_h * 2 + 56, 672, tl.p(t, "C4.rule")))
    # Credential then pointer, on two lines and at two sizes. They were one line joined by
    # fg7's coral middot, which is that file's pattern for a run of *equal* clauses
    # ("Offline viewer / no permissions / no trackers"). These two are not equal: one is a
    # claim and the other is where to go and check it, so the middot made the URL read as a
    # third item in a list and put an orange dot in the middle of the quietest line on the
    # card. Both arrive on the same cue, because they are one block.
    credit_y = y + line_h * 2 + 96
    small_lh = round(S.ROLES["small"].px * S.ROLES["small"].lh)
    parts.append(text("small", T.C4_SMALL, COL_X, credit_y, tl.p(t, "C4.small")))
    parts.append(text("caption", T.C4_URL, COL_X, credit_y + small_lh,
                      tl.p(t, "C4.small")))
    return "".join(parts), []


_BUILDERS = {
    "A1": _beat_A1, "A2": _beat_A2, "A3": _beat_A3,
    "B1": _beat_B1, "B2": _beat_B2, "B3": _beat_B3, "B4": _beat_B4,
    "C1": _beat_C1, "C2": _beat_C2, "C3": _beat_C3, "C4": _beat_C4,
}


def frame(tl: Timeline, t: float, placeholder: bool = True) -> tuple[str, list]:
    """One frame's drawn content, plus its footage manifest.

    The manifest is a list of `(clip, x, y, w, h, over)` telling the compositor exactly where
    each recorded take belongs in this frame, and what has to be drawn *on top of* it. It is
    emitted even in placeholder mode, so the shoot and the composite both have a rectangle
    rather than a description.

    `over` is HTML, almost always empty. B3's magnified fragment is the only thing in the
    film that sits above a recorded take, and `compose.py` renders it as its own transparent
    plate and lays it over the footage.
    """
    beat = tl.beat_at(t)
    builder = _BUILDERS[beat.tag]
    # Dispatch on the signature rather than by catching TypeError: a bare except here
    # swallows a real TypeError raised inside the builder and retries it, which turns a
    # one-line bug into a confusing double traceback.
    if "placeholder" in inspect.signature(builder).parameters:
        body, manifest = builder(tl, t, placeholder)   # type: ignore[call-arg]
    else:
        body, manifest = builder(tl, t)                # type: ignore[call-arg]
    return body, manifest


def _plate(body: str, opacity: float = 1.0) -> str:
    """One full-frame layer: ground, content, vignette. The unit a dissolve crosses."""
    op = "" if opacity >= 1.0 else f"opacity:{_px(opacity)};"
    return (f'<div style="position:absolute;inset:0;overflow:hidden;{op}'
            f'background:{S.GROUND}">{body}'
            f'<div style="position:absolute;inset:0;pointer-events:none;'
            f'background:{S.VIGNETTE}"></div></div>')


def frame_div(tl: Timeline, t: float, left: int = 0, placeholder: bool = True) -> str:
    """A frame as a positioned div, for stacking into a filmstrip.

    Within a beat's dissolve-in window the outgoing beat's final frame is drawn underneath
    and this one is crossed over it. Each side is a complete plate, ground and vignette
    included, so it is a true cross-dissolve rather than one beat's content fading over the
    other's background.

    `timeline.DISSOLVE_IN` says which boundaries cross and which stay hard, and the three
    hard ones are load-bearing rather than stylistic: see the comment on that table.
    """
    beat = tl.beat_at(t)
    body, _ = frame(tl, t, placeholder)
    n = DISSOLVE_IN.get(beat.tag, 0)
    inner = _plate(body)
    if n:
        elapsed = t - beat.start
        window = n / FPS
        if elapsed < window:
            i = tl.beats.index(beat)
            if i > 0:
                prev = tl.beats[i - 1]
                # The outgoing beat at its own last frame, held under the incoming one.
                out_body, _ = frame(tl, prev.end - 1.0 / FPS, placeholder)
                inner = _plate(out_body) + _plate(body, elapsed / window)
    return (f'<div style="position:absolute;left:{left}px;top:0;width:{S.W}px;'
            f'height:{S.H}px;overflow:hidden;isolation:isolate;background:{S.GROUND}">'
            f'{inner}</div>')


def check_proof() -> list[str]:
    """Fail if the proof would not read at Play's carousel width.

    The beats where the argument is actually being made are held to style.PROOF_MIN_PX. C3
    is exempt by design, not by oversight: see the note above FRAG_W_B3.
    """
    problems = []
    floor = S.min_frag_w()
    for name, w in (("B3", FRAG_W_B3), ("B4", FRAG_W_B4), ("C1", FRAG_W_C1)):
        got = S.proof_px_at_carousel(w)
        if got < S.PROOF_MIN_PX:
            problems.append(
                f"{name}: the proof renders {got:.1f}px at {S.CAROUSEL_W}px display, "
                f"under the {S.PROOF_MIN_PX}px floor. Widen the fragment to at least "
                f"{floor:.0f}px and let the layout give way, not the proof.")
    return problems


if __name__ == "__main__":
    import sys
    tl = Timeline()
    t = float(sys.argv[1]) if len(sys.argv) > 1 else 0.0
    print(S.head() + frame_div(tl, t) + "</body></html>")
