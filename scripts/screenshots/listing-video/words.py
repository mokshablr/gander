#!/usr/bin/env python3
"""Every word that appears on screen, in one place.

Named `words` rather than `copy` because a module called copy.py on this path shadows
the standard library's copy module for every script in the directory, which is the kind of
breakage that shows up later and somewhere else.

The counterpart to `timeline.py`: that module owns every time, this one owns every word.
Nothing else in the pipeline contains a user-visible string, so a copy change is a one-line
edit here and a re-render, never a hunt through layout code.

**Most of these are quotations, not new writing.** The hero pairs and their kickers are
lifted verbatim from `../store-art/final_c.py`, which is what the seven shipped Play
screenshots actually say, so the film and the listing speak in the same words and the same
face. Where a line is quoted, the source is named beside it. Do not paraphrase a quoted
line: the point of it being identical is that a viewer who scrolls from the video to the
screenshots sees one voice rather than two.

`LINE_VARIANTS` holds two candidate lines for B1. That beat is a type-only card on flat
ground precisely so that swapping between them costs one render and no reshoot.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# A1. Quoting the feature graphic, so frame 1 is the poster Play plays from.
# Source: ../store-art/fg7.py
# ---------------------------------------------------------------------------

A1_KICKER = "Take a gander at"
A1_HERO = "any file."
A1_SUB = "Offline viewer &middot; no permissions &middot; no trackers"
A1_FMT = ("PDF &middot; Word &middot; Excel &middot; PowerPoint &middot; Photos",
          "Video &middot; Audio &middot; Markdown &middot; Code")

# ---------------------------------------------------------------------------
# A2. Verbatim from final_c.py panel 0, which is Play screenshot 1.
# ---------------------------------------------------------------------------

A2_HERO = ("Opens", "everything.")
A2_KICKER = ("PDF, Word, Excel, PowerPoint, photos,",
             "video, audio, Markdown and code.")

# ---------------------------------------------------------------------------
# B1. The line. The one sentence a viewer should be able to repeat.
# ---------------------------------------------------------------------------

B1_LINE = "Check its permissions page."

# Two candidate lines for B1, kept here so swapping one is a single string. Arm A is an
# instruction, arm B is a claim; they are different arguments rather than a wording tweak,
# which is why the beat is a type-only card on flat ground. Which one ships is not decided
# in this repository.
LINE_VARIANTS = {
    "a": ("Check its permissions page.", None),
    "b": ("It can't upload your files.", "There's no internet permission to do it with."),
}

# ---------------------------------------------------------------------------
# B3. Nothing. The only words in this beat are Android's own, inside the capture,
# and a caption over them would make them the film's. Left here as a deliberate
# empty so nobody adds one later thinking it was an oversight.
# ---------------------------------------------------------------------------

B3_WORDS = None

# ---------------------------------------------------------------------------
# B4. Verbatim from final_c.py panel 1, which is Play screenshot 2.
# ---------------------------------------------------------------------------

B4_HERO = ("Takes", "nothing.")
B4_KICKER = ("No permissions. No trackers.",
             "No internet access at all.")

# ---------------------------------------------------------------------------
# C1. Compressed from docs/index.html's own argument: "Not as a promise it makes,
# but as a capability it does not have."
# ---------------------------------------------------------------------------

C1_LINE = "Not a promise. A missing capability."

# ---------------------------------------------------------------------------
# C2. Verbatim from final_c.py panel 5, which is Play screenshot 6.
# ---------------------------------------------------------------------------

C2_KICKER = ("PDF paper goes black, text goes white,",
             "and photographs stay as printed.")

# ---------------------------------------------------------------------------
# C3. The first line is a structural claim rather than a promise: the app cannot
# add ads in a later update because it holds no INTERNET permission to load one
# through. That is checkable in the manifest, which is why it is worth a beat.
#
# The second line is set over two lines rather than one. At 56 px the single line
# measures 1322 px, which does not fit beside the returning proof fragment; the
# break also reads better, because it puts the mechanism on its own line.
# ---------------------------------------------------------------------------

C3_LINE1 = "It can't add ads later."
C3_LINE2 = ("There is no internet permission",
            "to load one through.")

# ---------------------------------------------------------------------------
# C4. The signature. Screenshot 1 and screenshot 2's captions welded, which is
# what the listing already says across its first two frames.
#
# No CTA: the Install button is already on the page. "Open source. MIT." rather
# than anything about price, because "free" is a banned word in Play metadata and
# the neighbouring phrasings sit close to it. No pricing claim of any kind here:
# it is the one thing on this card that could stop being true.
# ---------------------------------------------------------------------------

C4_HERO = ("Opens everything.", "Takes nothing.")

# The credential, then the pointer to it. **The URL is here and a CTA is not**, and the two
# are not the same kind of thing. On the Play listing the Install button sits a few
# centimetres below the video, so "Download now" names a button the viewer can already see,
# while a URL there is unclickable and redundant. Off the listing it inverts: the lift never
# reaches this beat, and on YouTube or a creator's re-post there is no button at all, so the
# URL is the only way anyone can act. Each is useful exactly where the other is not, and a
# CTA would also turn the film's last two seconds from evidence into advertisement.
#
# GitHub rather than the site, because it sits under "MIT" and is the proof of that claim.
# `url_source` in strings.xml and the Play description both use this form.
#
# Set on its own line and one size down, not joined to the credential by fg7's coral middot.
# That separator is for a run of equal clauses; a claim and a pointer to where you check it
# are not equal, and the dot read as a stray orange mark in the quietest line on the card.
C4_SMALL = "Open source. MIT."
C4_URL = "github.com/mokshablr/gander"

# Which half of each hero pair is set in coral. Everything before it is ink.
C4_HOT = ("everything.", "nothing.")


# ---------------------------------------------------------------------------
# Every string, flattened, for the measuring bench.
# ---------------------------------------------------------------------------

def all_strings() -> list[tuple[str, str, str]]:
    """(beat, role, text) for every line the film sets, for measure.py.

    `role` names the type style the line is set in, and layers.py reads the same names,
    so a line measured here is measured at the size it will actually be rendered at.
    """
    out: list[tuple[str, str, str]] = []

    def add(beat: str, role: str, text):
        if text is None:
            return
        if isinstance(text, tuple):
            for part in text:
                add(beat, role, part)
        else:
            out.append((beat, role, text))

    add("A1", "statement", A1_KICKER)
    add("A1", "hero", A1_HERO)
    add("A1", "caption", A1_SUB)
    add("A1", "tiny", A1_FMT)
    add("A2", "hero", A2_HERO)
    add("A2", "small", A2_KICKER)   # set in `small`: at 46 px it is 781 against 780
    add("B1", "lead", B1_LINE)
    add("B1", "lead", LINE_VARIANTS["b"][0])
    add("B1", "sub", LINE_VARIANTS["b"][1])
    add("B4", "hero", B4_HERO)
    add("B4", "kicker", B4_KICKER)
    add("C1", "small", C1_LINE)
    add("C2", "kicker", C2_KICKER)
    add("C3", "lead", C3_LINE1)
    add("C3", "sub", C3_LINE2)
    add("C4", "hero", C4_HERO)
    add("C4", "small", C4_SMALL)
    add("C4", "caption", C4_URL)
    return out


if __name__ == "__main__":
    for beat, role, text in all_strings():
        plain = text.replace("&middot;", ".")
        print(f"{beat:<4} {role:<10} {plain}")
