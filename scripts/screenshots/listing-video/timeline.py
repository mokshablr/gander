#!/usr/bin/env python3
"""The beat sheet as data. The single source of truth for every time in the film.

`BEATS` below carries each beat's duration and computes its start. `_SPEC_BEATS` transcribes
the absolute starts as well. They are two views of the same timing, so changing one duration
moves every later start and the two disagree: `selftest()` fails loudly, and it is much
cheaper to find out here than after rendering 1,800 frames.

**Every cue is an offset from its own beat's start, never an absolute time.** That is the
whole design of this module. Changing one duration slides everything downstream and no cue
has to be touched, so retiming the film is a one-line change rather than a rewrite. Nothing
anywhere else in the pipeline may hardcode a time; ask this module.

The lift window is derived the same way, from the B block's own boundaries, so the
standalone 12-second clip stays correct across a retime without anyone remembering to
update it.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# ---------------------------------------------------------------------------
# Canvas.
# ---------------------------------------------------------------------------

FPS = 60
W, H = 1920, 1080

# Left margin. fg7.py uses 64 on a 1024 canvas; this is the same proportion, and the film
# inherits fg7's composition rather than inventing a second one.
M = 120

# Every coordinate in the film is a multiple of this, following fg7.py's own rule.
GRID = 8


def frames(seconds: float) -> int:
    """Seconds to a whole frame count, rounded to even frames.

    Durations are kept to even frames so a cue never lands on a half-frame boundary at
    60 fps and then rounds differently on two sides of an edit.
    """
    return int(round(seconds * FPS / 2.0)) * 2


# ---------------------------------------------------------------------------
# Easing
# ---------------------------------------------------------------------------

def _clamp01(x: float) -> float:
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


def linear(t: float) -> float:
    return _clamp01(t)


def ease_out(t: float) -> float:
    """Cubic ease-out. The film's default arrival, per motion rule 1."""
    t = _clamp01(t)
    return 1.0 - (1.0 - t) ** 3


def ease_in_out(t: float) -> float:
    """Cubic ease-in-out. Used only where something travels rather than arrives."""
    t = _clamp01(t)
    return 4.0 * t ** 3 if t < 0.5 else 1.0 - ((-2.0 * t + 2.0) ** 3) / 2.0


EASINGS = {"linear": linear, "out": ease_out, "inout": ease_in_out}


# ---------------------------------------------------------------------------
# Cues and beats
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class Cue:
    """One timed change inside a beat.

    `at` and `dur` are seconds relative to the beat's start. `stagger` repeats the cue
    across `count` items, each starting `stagger` seconds after the last, which is how the
    chip and card rows arrive without needing a cue each.
    """
    at: float
    dur: float = 0.0
    ease: str = "out"
    count: int = 1
    stagger: float = 0.0

    def span(self) -> float:
        """How long from the cue's start until the last item has finished."""
        return (self.count - 1) * self.stagger + self.dur


@dataclass
class Beat:
    tag: str
    name: str
    dur: float
    # Whether this beat composites recorded footage. The caption layer draws nothing over
    # a footage beat except what is listed in its own cues.
    footage: str | None = None
    cues: dict[str, Cue] = field(default_factory=dict)
    start: float = 0.0  # filled in by Timeline

    @property
    def end(self) -> float:
        return self.start + self.dur


# The standard arrival, motion rule 1: opacity 0 to 1 over 200 ms with a 24 px rise.
RISE_PX = 24.0
RISE_S = 0.200


def arrive(at: float, count: int = 1, stagger: float = 0.0) -> Cue:
    return Cue(at=at, dur=RISE_S, ease="out", count=count, stagger=stagger)


# ---------------------------------------------------------------------------
# The beat sheet. Run this module to print it.
# ---------------------------------------------------------------------------

BEATS: list[Beat] = [
    Beat("A1", "The cover, alive", 2.000, cues={
        # The first 12 frames are a still composition, because this is the frame Play sits
        # behind the play button. The drift starts only after it.
        "drift": Cue(at=0.200, dur=1.600, ease="inout"),
    }),
    Beat("A2", "Nine, not four", 2.600, cues={
        "hero_ink":   arrive(0.150),
        "hero_hot":   arrive(0.270),   # the 120 ms stagger, motion rule 2
        "kicker":     arrive(0.450),
        # The nine badges, after the claim rather than under it: the hero says what it
        # opens and the chips enumerate it. C1 brings this same row back.
        "chips":      arrive(0.700, count=9, stagger=0.054),
    }),
    Beat("A3", "It just opens", 2.400, footage="a3_open"),

    # ---- the lift opens here -------------------------------------------------
    Beat("B1", "The instruction", 1.200, cues={
        # Lockup and line arrive together. The beat is too short to spend 120 ms on a
        # stagger, so rule 2 does not apply here and that is deliberate.
        "lockup": arrive(0.100),
        "line":   arrive(0.100),
    }),
    Beat("B2", "Leaving the app", 2.800, footage="b2_walkout"),
    Beat("B3", "Android says it", 4.000, footage="b3_appinfo", cues={
        # The scroll is recorded, so this is when it has settled, not something we drive.
        "settled":  Cue(at=1.200, dur=0.0),
        # The device moves aside just before the proof lifts, so the fragment fills the space
        # it vacated rather than landing on top of a frame that had no room for it. Starting
        # 100 ms early is what makes the two read as one movement instead of two.
        "slide":    Cue(at=1.300, dur=0.500, ease="inout"),
        "fragment": Cue(at=1.400, dur=0.240, ease="out"),
    }),
    Beat("B4", "The payoff", 4.000, cues={
        # B4 had a "ground" cue here describing an 8-frame ground dissolve. It was never
        # read by layers.py: dead config, left from
        # the first commit. DISSOLVE_IN now crosses the whole plate at this boundary, which
        # is what that cue was trying to say, so it is gone rather than wired up.
        "frag_travel": Cue(at=0.133, dur=0.400, ease="inout"),
        "hero_ink":   arrive(0.900),
        "hero_hot":   arrive(1.020),
        "kicker":     arrive(1.200),
    }),
    # ---- the lift closes at the end of B4 ------------------------------------

    Beat("C1", "The cost that was not paid", 2.400, cues={
        "chips": arrive(0.100, count=9, stagger=0.054),
        "line":  arrive(0.800),
    }),
    Beat("C2", "Night", 3.200, footage="c2_night", cues={
        "kicker": arrive(0.800),
    }),
    Beat("C3", "Why you would switch", 2.400, cues={
        "fragment": arrive(0.200),
        "line1":    arrive(0.500),
        "line2":    arrive(0.700),
    }),
    Beat("C4", "The signature", 3.000, cues={
        "lockup": arrive(0.100),
        "line1":  arrive(0.350),
        "line2":  arrive(0.600),
        "rule":   Cue(at=0.800, dur=0.300, ease="inout"),
        "small":  arrive(1.250),
    }),
]

# How many frames each beat dissolves IN from the one before it. Zero is a hard cut.
#
# Motion rule 6 used to make every transition hard. Watched end to end that read as choppy,
# so the drawn boundaries now cross. **Three boundaries stay hard and each for its own
# reason, none of them stylistic:**
#
#   A3 -> B1   the lift's in-point. The standalone clip has to start clean, and a dissolve
#              carries the previous beat's frames across the boundary into it.
#   B2 -> B3   **there is no cut here at all.** B2 and B3 are one unbroken recorded take,
#              and the fact that it is unbroken is the argument. Dissolving would fabricate
#              a transition where the film's whole claim is that none exists.
#   B4 -> C1   the picture is continuous already: C1 holds B4's card and adds to it. It is
#              also the lift's out-point, which has to be a static frame.
#
# A1 -> A2 gets the longest because the picture behind it does not change at all, only the
# type, and a fast swap on an identical picture reads as a glitch rather than as a cut.
DISSOLVE_IN = {
    "A2": 16,   # same picture, type only
    "A3": 10,
    "B1": 0,    # lift in-point
    "B2": 10,
    "B3": 0,    # one unbroken take with B2
    "B4": 10,
    "C1": 0,    # continuous picture, lift out-point
    "C2": 10,
    "C3": 10,
    "C4": 10,
}

# The lift is the span of these beats, derived rather than written down, so a retime
# cannot leave the standalone clip pointing at the wrong frames.
LIFT_BEATS = ("B1", "B2", "B3", "B4")

# Appended to the standalone export so the clip ends on a fade rather than being severed.
# The film itself does not use it; the lift's out-point inside the film is a static frame.
LIFT_TAIL_FADE = 0.250


class Timeline:
    def __init__(self, beats: list[Beat] | None = None):
        self.beats = beats if beats is not None else BEATS
        t = 0.0
        for b in self.beats:
            b.start = t
            t += b.dur
        self.duration = t
        self._by_tag = {b.tag: b for b in self.beats}

    # -- lookup ------------------------------------------------------------

    def __getitem__(self, tag: str) -> Beat:
        return self._by_tag[tag]

    def beat_at(self, t: float) -> Beat:
        """The beat containing absolute time `t`. The final frame belongs to the last beat."""
        for b in self.beats:
            if b.start <= t < b.end:
                return b
        return self.beats[-1]

    @property
    def lift(self) -> tuple[float, float]:
        first, last = self._by_tag[LIFT_BEATS[0]], self._by_tag[LIFT_BEATS[-1]]
        return (first.start, last.end)

    @property
    def total_frames(self) -> int:
        return frames(self.duration)

    # -- cue evaluation ----------------------------------------------------

    def p(self, t: float, ref: str, index: int = 0) -> float:
        """Eased progress 0 to 1 of one cue at absolute time `t`.

        `ref` is "BEAT.cue". `index` picks an item out of a staggered cue. Returns 0.0
        before the cue starts and 1.0 after it finishes, so a caller can multiply by it
        without checking bounds. A zero-length cue is a switch: 0.0 before, 1.0 from its
        moment onward.
        """
        tag, _, name = ref.partition(".")
        beat = self._by_tag[tag]
        cue = beat.cues[name]
        start = beat.start + cue.at + index * cue.stagger
        if t < start:
            return 0.0
        if cue.dur <= 0.0:
            return 1.0
        return EASINGS[cue.ease]((t - start) / cue.dur)

    def settled(self, t: float, ref: str) -> bool:
        """True once every item of a cue has finished moving."""
        tag, _, name = ref.partition(".")
        beat = self._by_tag[tag]
        cue = beat.cues[name]
        return t >= beat.start + cue.at + cue.span()

    def still_from(self, tag: str) -> float:
        """Absolute time from which a beat is a completely static frame.

        A beat's static tail is where nothing is still moving, and it is computed rather
        than asserted, so a retime cannot silently remove the still tail the lift's
        out-point depends on.
        """
        beat = self._by_tag[tag]
        if not beat.cues:
            return beat.start
        return beat.start + max(c.at + c.span() for c in beat.cues.values())


# ---------------------------------------------------------------------------
# Self-test, against the absolute times transcribed below
# ---------------------------------------------------------------------------

# (tag, start, duration). **The spec the beat list above is checked against, and a second
# representation of it on purpose.** `BEATS` carries durations and computes each start;
# this carries the absolute starts as well, so changing one duration up there shifts every
# later start and disagrees with this. Two copies of the same numbers would be pointless;
# two different views of them is a real check. Mutation-checked: editing one duration in
# either place fails the self-test.
_SPEC_BEATS = [
    ("A1",  0.000, 2.000), ("A2",  2.000, 2.600), ("A3",  4.600, 2.400),
    ("B1",  7.000, 1.200), ("B2",  8.200, 2.800), ("B3", 11.000, 4.000),
    ("B4", 15.000, 4.000),
    ("C1", 19.000, 2.400), ("C2", 21.400, 3.200), ("C3", 24.600, 2.400),
    ("C4", 27.000, 3.000),
]

# A few absolute cue times, spot-checked here because they are the ones a reader would
# notice being wrong. These are derived quantities rather than part of the beat spec.
_SPEC_CUES = [
    ("A2.hero_ink", 2.150), ("A2.hero_hot", 2.270), ("A2.kicker", 2.450),
    ("A2.chips",    2.700),
    ("B1.lockup",   7.100),
    ("B3.fragment", 12.400),
    ("B4.hero_ink", 15.900), ("B4.hero_hot", 16.020), ("B4.kicker", 16.200),
    ("C1.line",     19.800),
    ("C2.kicker",   22.200),
    ("C3.line1",    25.100), ("C3.line2", 25.300),
    ("C4.line1",    27.350), ("C4.rule",  27.800), ("C4.small", 28.250),
]


def selftest() -> None:
    tl = Timeline()
    eps = 1e-9

    expected = _SPEC_BEATS
    source = "the transcript in this file"
    for beat, (tag, start, dur) in zip(tl.beats, expected):
        assert beat.tag == tag, f"{beat.tag} != {tag}"
        assert abs(beat.start - start) < eps, f"{tag} starts {beat.start}, {source} says {start}"
        assert abs(beat.dur - dur) < eps, f"{tag} runs {beat.dur}, {source} says {dur}"

    assert abs(tl.duration - 30.0) < eps, f"film is {tl.duration}s, direction says 30.000"
    assert tl.lift == (7.0, 19.0), f"lift is {tl.lift}, direction says (7.0, 19.0)"
    assert abs((tl.lift[1] - tl.lift[0]) - 12.0) < eps, "the lift is not 12 seconds"

    for ref, absolute in _SPEC_CUES:
        tag, _, name = ref.partition(".")
        beat, cue = tl[tag], tl[tag].cues[ref.split(".")[1]]
        got = beat.start + cue.at
        assert abs(got - absolute) < eps, f"{ref} fires at {got:.3f}, direction says {absolute:.3f}"

    # Contiguity: no gaps and no overlaps.
    for a, b in zip(tl.beats, tl.beats[1:]):
        assert abs(a.end - b.start) < eps, f"gap or overlap between {a.tag} and {b.tag}"

    # The lift's out-point has to be a static frame, or the standalone clip is severed
    # mid-move. The clip needs 0.4 s of it; anything under that is a real defect.
    tail = tl["B4"].end - tl.still_from("B4")
    assert tail >= 0.4 - eps, f"B4's static tail is only {tail:.3f}s; the lift needs 0.4"

    # Same for the film's own last frame, which is what a paused or looping video sits on.
    hold = tl["C4"].end - tl.still_from("C4")
    assert hold >= 1.0 - eps, f"C4 holds for only {hold:.3f}s"

    # Every cue must finish inside its own beat, or it bleeds across a hard cut.
    for b in tl.beats:
        for name, c in b.cues.items():
            assert c.at + c.span() <= b.dur + eps, \
                f"{b.tag}.{name} runs {c.at + c.span():.3f}s into a {b.dur:.3f}s beat"

    # A dissolve reads the outgoing beat's last frame, so it must be shorter than the beat
    # it dissolves into, or the overlap runs past that beat's own cues.
    for b in tl.beats:
        n = DISSOLVE_IN.get(b.tag, 0)
        assert n / FPS < b.dur, f"{b.tag} dissolves for {n} frames into a {b.dur}s beat"
    hard = [t for t, n in DISSOLVE_IN.items() if n == 0]
    assert set(hard) == {"B1", "B3", "C1"}, f"hard cuts are {hard}, expected B1, B3, C1"

    print(f"checked against {source}")
    print(f"timeline ok: {len(tl.beats)} beats, {tl.duration:.3f}s, "
          f"{tl.total_frames} frames at {FPS} fps")
    print(f"lift: {tl.lift[0]:.3f} to {tl.lift[1]:.3f} "
          f"({tl.lift[1] - tl.lift[0]:.3f}s, frames {frames(tl.lift[0])} to {frames(tl.lift[1])})")
    print(f"B4 static tail {tail:.3f}s | C4 hold {hold:.3f}s")


if __name__ == "__main__":
    selftest()
    tl = Timeline()
    print()
    for b in tl.beats:
        mark = "  <-- lift" if b.tag in LIFT_BEATS else ""
        foot = f"  [{b.footage}]" if b.footage else ""
        print(f"  {b.tag}  {b.start:6.3f} + {b.dur:.3f} -> {b.end:6.3f}  "
              f"{b.name}{foot}{mark}")
