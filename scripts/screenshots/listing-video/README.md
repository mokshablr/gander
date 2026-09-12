# Play listing video: the caption layer

`timeline.py` owns every time in the film. Run it to print the beat sheet.

This renders the video in the Play listing's preview slot: a 30.000 s film at 1920x1080, and
a 12.25 s clip lifted whole out of its middle. Both are **silent by design**, because Play
autoplays the preview muted, so the type has to carry the whole argument on its own.

Only the code is here. The film's direction and the copy rationale behind it are editorial
rather than technical, and are not versioned with the tooling.

**The drawn layer is built before the shoot, not after it.** Every frame renders with a
labelled hole where each recorded take belongs, so the composition can be judged and
corrected while a change still costs a re-render rather than a re-shoot, and the shoot then
gets an exact rectangle instead of a description. That order is why this directory is shaped
the way it is, and it is worth keeping if the film is ever recut.

**The recorded takes are not in git.** They are screen recordings from a specific phone in a
specific state, they are large, and they cannot be regenerated without that phone.
`shoot.py` writes them outside the repository and `trim.py` cuts them; `standins.py` fills
the same rectangles with placeholders so the cut is watchable without them.

## Run it

```sh
python3 timeline.py              # print the beat sheet, and self-check the timing
python3 measure.py               # every line measured in Jost against its beat's room
python3 render.py --stills       # one frame per beat + out/contact.png
python3 render.py --at 10.4 13.0 # exact times, for checking a cue
python3 render.py --lift         # the 12-second clip's 720 frames
python3 render.py --all          # all 1,800
python3 render.py --video        # both MP4s, drawn layer only, with placeholder footage
python3 compose.py --takes out/takes   # the real composite: both silent masters
python3 audit.py                 # refuses to build if anything is upscaled
```

`out/` is gitignored, same as store-art's renders: regenerable and large.

## The files

`timeline.py`, `words.py`, `style.py`, `layers.py` and `render.py` are the core five, and the
split between them is the point. The rest handle the shoot and the composite.

| File | Owns |
| --- | --- |
| `timeline.py` | **Every time.** Beats, cues, easing, the lift window. Nothing else may hardcode a time. |
| `words.py` | **Every word on screen.** Most are quotations from `../store-art/final_c.py`; the source is named beside each. |
| `style.py` | **Every colour, size and coordinate.** Taken from `../store-art/pano.py` and `fg7.py`. |
| `layers.py` | Draws one frame, and emits the footage manifest. |
| `render.py` | Chrome, filmstrips, slicing, the font check, the proof gate, the encode. |
| `measure.py` | A bench tool. Measures type by importing the modules above, so it measures what will actually render. |
| `shoot.py` | Drives the phone over adb. Device and build checks, the takes, the high resolution proof capture. |
| `trim.py` | Cuts the raw recordings down to the four takes. screenrecord is variable frame rate; this is where that is dealt with. |
| `compose.py` | Places footage into the manifest rectangles and encodes. **The real deliverables come from here**, not from `render.py --video`. |
| `audit.py` | Refuses to build if any asset is shown larger than its source. |
| `standins.py` | Placeholder footage, so the drawn layer could be watched before the shoot. |

That split is the point: retiming the film is one number in `timeline.py`, changing a line is
one string in `words.py`, and neither touches layout.

## What building it proved wrong

Three things, all found by rendering rather than by reading.

**The 300 px hero does not survive landscape.** It came from scaling `pano.py`'s 168 px hero
from a 1080-wide portrait frame to a 1920-wide one. Measured, "everything." sets 1419 px at
300 px, which leaves 381 px of the frame for the card row and makes `fg7.py`'s composition
impossible. The real constraint is that a 1920x1080 frame at Play's carousel width is 169 px
tall where a 1080x1920 screenshot is 533 px, so a landscape film has a third of the vertical
room. **The hero is 152 px**, measured to clear the leftmost card at x=900 with headroom.

**The five extra cards in A2 were built and cut.** Real page crops exist for five document
kinds and have never been captured for VID, AUD, MD or TXT, so four of the nine were blank
paper and read as grey slabs, like a loading state. Putting a PDF crop behind a VID chip
instead would have been a small lie in a film whose whole argument is that it does not tell
them. The breadth claim moved to the nine chips, which are the app's own badges in the app's
own colours and survive being shown small in a way a list of words at 44 px does not. A2 and
C1 now share one `chip_row`, so the row in the payoff is the same object as the row in the
setup rather than a lookalike.

**C4's coral needed a different mechanism.** A hero pair puts its coral half on its own line,
where it gets its own cue. The signature needs coral on the tail of a single line, which the
line-level colouring could not do. `split_hot` is the only place a line is split, so the two
do not drift.

## The proof outranks the composition

`style.PROOF_MIN_PX` is the floor for how tall "No permissions requested" renders when the
frame is shown at Play's carousel width, `layers.check_proof()` enforces it, and `render.py`
refuses to render below it. **If a beat gets crowded, the fragment grows and the layout gives
way.** The first build measured 3.1 px, which is why the gate exists.

```sh
python3 render.py --stills --carousel          # every beat at 300px + a contact sheet
python3 measure.py --proof <capture.png>       # re-derive the crop from a real take
```

Run the second one **against the real take, not the stand-in.** Everything about the proof in
`style.py` was measured off the press kit's 720x813 capture, and a different device, Android
version or font scale moves it. The tool prints the crop, the glyph height and the narrowest
fragment that still clears the floor, and says so plainly if that is wider than the layout
currently allows.

B3, B4 and C1 are held to the floor. C3 is exempt on purpose: eight seconds of the phrase have
already gone by, so it asks for recognition rather than reading.

## Watching it

`render.py --video` writes `out/gander-listing-30s.mp4` and
`out/gander-permissions-12.25s.mp4`, with placeholder footage in the four recorded beats.
**Both names are derived from the timeline, never typed**: they were hardcoded "28s" and
"12s", survived the retime to 30 s, and would have handed someone a file called
`gander-listing-28s.mp4` containing a thirty-second film.

**This exists before the shoot, not after it, because timing is the one thing stills cannot
show** and the beat sheet is almost entirely timing: whether the 120 ms stagger reads as two
beats or as one slab, whether B2's 2.8 s feels like a real interaction or like an edit,
whether B4's 2.6 s hold is a rest or dead air. Those are answerable now, and changing them
now costs a re-render rather than a re-shoot.

Three things about how it is built:

- **Only distinct frames are rendered.** The drawn layer is deduplicated by the HTML it
  generates, which is exact rather than approximate, since identical markup renders
  identically. About three quarters of the film is a held frame, so 1,800 frames cost 433
  renders, and the sequence is rebuilt from symlinks rather than gigabytes of duplicate PNGs.
- **Rendered frames are cached in `out/uniq/`, named by the hash of the HTML that produced
  them, never by frame index.** Keying on the index looks right and is wrong: editing a beat
  changes what frame 42 contains without changing that it is frame 42, so the cache serves
  the old picture and the edit silently does not appear. That is the same failure as the
  stale stills. Content addressing makes a changed frame a new file and orphans the old one,
  which is dropped on the next run.
- **The footage holes carry a stepped progress bar.** Without it a preview holds a motionless
  box for 2.8 seconds and the beat reads as dead air rather than as footage, which defeats
  the point. It is quantised to 12 steps so it does not make every frame of a footage beat
  distinct and undo the deduplication.

**Boundaries cross, except three.** `timeline.DISSOLVE_IN` holds how many frames each beat
dissolves in over, 10 as standard and 16 for A1 into A2. Each side of a dissolve is a
complete plate, ground and vignette included, so it is a true cross rather than one beat's
content fading over the other's background. **A3 into B1, B2 into B3, and B4 into C1 stay
hard**, and `timeline.selftest()` asserts that those three and only those three are zero.
B2 into B3 is the one that matters most: there is no cut there at all, the two are a single
unbroken take, and dissolving would fabricate a transition exactly where the film's claim is
that none exists.

**`timeline.selftest()` checks the timing against a second view of it.** `BEATS` carries each
beat's duration and computes its start; `_SPEC_BEATS` transcribes the absolute starts as well.
Change one duration and every later start moves, so the two disagree and the self-test fails.
Two identical copies would prove nothing; two different views of the same numbers is a real
check. Mutation-checked in both directions, per the repo's rule about never trusting a test
you have not seen break.

**The lift's tail fade is appended, not cut out of the content.** The clip is 12.25 s of which
the first 12.000 are the film's own frames; `tpad` clones the final frame, which is already
static, and the fade runs over the clone. Fading the last 0.25 s of the 720 instead delivers
12.00 s and quietly dims the end of B4's payoff card, which is the frame tweet 1 autoplays to.
That is what the first encode did.

**Do not compare the two MP4s frame for frame.** They are separately encoded H.264 from the
same PNGs, so the same frame differs between them at the pixel level and always will. Check
the in-point against `out/seq/`, which is what both encodes read.

## Things that will bite you

**Jost is not a system font here.** It loads from Google Fonts at render time, exactly as
`store-art` does, and `store-art/README.md` records that a page which fails to load it falls
back silently and measures about 20% short. `render.py` and `measure.py` both verify the font
before doing anything, by setting the same string in Jost and in the fallback and comparing
ink widths. **Never remove that check**: a filmstrip rendered in the system sans looks
entirely plausible until it is beside the real screenshots.

**Measure type, never estimate it.** `measure.py` reads its strings from `words.py` and its
sizes from `style.py`, so it measures what will actually render rather than a copy of the
markup, which is the second half of the same README warning. Re-run it after any change to
copy, type scale, or the card and device positions.

**`measure.py` derives each beat's room from `layers.py`.** It used to restate the numbers and
two lines overflowed silently when the card row moved. Ask the layout, do not copy it.

**`render.py` empties its output directory first.** Frame names carry their timestamp, so a
retime renames every file and a stale frame from the previous timing sits alongside the new
ones looking exactly as legitimate. This is not hypothetical; it is how A2 was reviewed twice
and judged unchanged after it had in fact been fixed.

**`pages/` is not committed.** `layers.py` reads the four document crops from
`../store-art/pages/`, which is 5.2 MB and gitignored. Rebuild it with the five `magick` crops
in `store-art/README.md` before rendering A1 or A2, and check the result with that README's
own first-row test: the top row of each crop must be the document's colour, never the
viewer's `rgb(52,48,41)` backdrop.

**The B3 fragment is the real capture, and it is committed.** `proof.png` is a 1455 px wide
`screencap` of Android's own App info screen, taken at raised `wm size` and `wm density` so
that B3, which shows it at 1200 px, downscales rather than magnifies. `layers.py` falls back
to the press kit's 720 px capture if it is missing, and `audit.py` then refuses the build:
720 px shown at 1200 is a 1.67x upscale of the film's most important element, which is
exactly the failure the resolution gate exists to catch.

## The film is silent, on purpose

Play **autoplays the preview muted**, for up to 30 seconds, off the feature graphic as its
poster frame. So the cut has to carry its whole argument with the sound off and finish inside
30 seconds, which is why it is 30.000 s long and why the type does all the work. Beds were
tried and none of them fitted the picture.

Two things learned that are worth keeping if sound is ever revisited:

- **A licensed track can break a hard Play requirement.** Play says "Disable ads for your
  video to be shown on Google Play", and then: "If your video uses copyrighted content,
  turning off monetization for your video may not be enough to prevent ads." A library
  licence protects you legally and does nothing about a YouTube Content ID claim, and the
  claim is what puts the ads there.
- **Measure a bed per octave, not per FFT bin.** One candidate measured a 1,087 Hz spectral
  centroid and read as bright; per octave it was 79% below 125 Hz and would have been close
  to inaudible on a phone speaker. Magnitude-per-bin flatters wide high bands into looking
  like content.
