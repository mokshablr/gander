# Store art build sources

The generators behind the 1.14 Play assets: seven phone frames, four tablet frames and
the feature graphic. Kept for the same reason as the rest of `scripts/screenshots/`,
that rebuilding them from nothing costs the better part of a day and the listing wants
re-shooting every time the UI changes.

Only the sources are here. The working data they read and write is not committed and
lives under `docs/screenshots/v1.14/`, which is gitignored: `raw/` and
`../v1.14-tab/raw/` hold the device captures, `pages/` the cropped document content,
`out/` the rendered assets. The shipped results are committed, in
`fastlane/metadata/android/en-US/images/`.

Three things to sort out before rendering from a fresh clone:

- `RAW` at the top of `pano.py` and `tabpano.py` is an **absolute path** into
  `docs/screenshots/v1.14/raw` and `v1.14-tab/raw`. Repoint both if the tree has moved.
- **`pages/` is not committed**, being 5.2 MB of cropped captures. `fg2.py` and `fg7.py`
  read it from beside this file, so recreate it as `store-art/pages/` with the five
  `magick` crops under "Crop each page to the document's own top edge" below before
  rendering the feature graphic.
- **`final_c.py` will refuse to render until `raw/d-pdf-night.png` exists.** Panel 5 was
  repointed at it for 1.15; see "Panel 5 is the night-mode slot" below. The capture was
  taken on 2026-09-08 and `raw/` is gitignored, so a fresh clone must re-shoot it.

## Panel 5 is the night-mode slot, and 1.14 could not fill it

Panel 5 has always been the "Reads at 2am." frame. Through 1.14 it was shot on a
**Markdown file** and claimed only that the app follows the system dark mode, which every
Material app does. It was Markdown because it had to be: `raw/d-pdf.png` was captured for
that same shoot and never used, being the dark app wrapped around a glaring white PDF
page, which would have refuted the caption printed above it.

1.15 turned the paper over, so the slot now carries the claim nothing else in the category
makes, and the caption reads *"PDF paper goes black, text goes white, and photographs stay
as printed."*

**Shoot a page that carries a photograph.** A text-only page renders as black paper and
white text, which is legible and says nothing a reader could not have guessed. The
photograph left as printed beside inverted text is the half that is actually rare, it is
what the caption is selling, and at Play's 166x296 browse size it is also the only bright
thing in an otherwise black frame. Without it panel 5 is the flattest tile in the set,
which is what the shipped 1.14 Markdown shot was.

The document is `scripts/screenshots/make_survey.py`, written for this: a Willowmere
condition survey with one photographic plate. Viewer screens need no debug-overlay dance
(see below), so the capture is just: push the sample, open it, night mode on from the
overflow, `screencap`.

**THE TRAP, and it nearly shipped a screenshot that disproved its own caption.**
`looksLikePaper()` in `pdf.html` turns an image over with the text when its mean
saturation is under `PROBE_SATURATION` (0.15) **and** more than `PROBE_PAPER` (0.25) of it
is near white. That is a scanned page's signature, and it is also a **snowy scene's**. The
sample world's only photograph is a winter cityscape, and the obvious crop of it measures
**0.090 saturation, 0.361 pale**: night mode inverts it, producing a negative photograph
directly beneath "photographs stay as printed". `make_survey.py` picks a crop measuring
0.119 / 0.086 and re-measures on every run, refusing to build if an edit walks it back over
the line. **Any future re-shoot must check the same thing.** It is the NeRF failure case
from the 2026-09-05 night-mode work, met again in a different place.

The rendered sub-caption measures 754px against the 928px `.head` box, 81%, so there is
room if the wording changes. The widest headline in the set is panel 1 at 797px.

## What renders what

| Script | Output | Notes |
| --- | --- | --- |
| `final_c.py` + `pano.py` + `drift.py` | `../out/c-0..6.png` | **Concept C**, the chosen set. Seven 1080x1920 phone frames, sliced from one 7560x1920 canvas. |
| `final_tab2.py` + `tabpano.py` + `drift.py` | `../out/tablet/tabp-0..3.png` | **The tablet set.** Four 2560x1600 landscape frames, sliced from one 10240x1600 canvas. |
| `final_tab.py` + `tab.py` | — | First tablet pass: four independent stages. Superseded, kept for reference. |
| `fg7.py` + `lockup.png` + `pages/` | `../out/featureGraphic.jpg` | **The feature graphic.** 1024x500 JPEG q94, 4:4:4. Four rendered pages, stacked. Flat ground, no gradients/blur/text-shadow. Rendered at 4x, Lanczos down, grain added last. |
| `fg2.py` | — | Same composition, 6 gradients / 17 blurs / 2 text-shadows / an outline on top of three shadows. Superseded by `fg7.py`. |
| `fg6.py` | — | Flat colour, one hairline, 8px grid. Passes the craft rules; lost the product. Reference. |
| `fg5.py` | — | Two sharp page columns. Reference. |
| `fg4.py` | — | Type over defocused paper. Reference. |
| `fg3.py` | — | Nine format tiles full width. Legible at 240px but reproduces screenshot 1's hero. Reference. |
| `fg.html` | — | First attempt: wordmark, small tagline, abstract tiles. Reference. |
| `compose.py` | — | Concept A (warm paper). Its final stage, `final.py`, was not kept. Reference. |
| `compose_b.py`, `final_b.py` | — | Concept B (dark, no panorama). Kept for reference. |

Render with headless Chrome, then slice:

```sh
python3 final_c.py                      # writes c.html
"…/Google Chrome" --headless --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=1 --virtual-time-budget=25000 \
  --window-size=7560,1920 --screenshot=c-canvas.png "file://$PWD/c.html"
for i in 0 1 2 3 4 5 6; do
  magick c-canvas.png -crop 1080x1920+$((i*1080))+0 +repage c-$i.png
done
```

Tablet frames render the same way, at `--window-size=10240,1600` from `tab2.html`,
cropped at `+$((i*2560))+0`.

The feature graphic renders at **4x and downsamples with Lanczos**. 2x was not enough:
Chrome's own downscale of the page images is poor, and letting the final resize do the
averaging is visibly cleaner on the document text.

```sh
python3 fg7.py                          # writes fg7.html
"…/Google Chrome" --headless --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=4 --virtual-time-budget=30000 \
  --window-size=1024,500 --screenshot=fg-4x.png "file://$PWD/fg7.html"
magick fg-4x.png -filter Lanczos -resize 1024x500 -background black \
  -alpha remove -alpha off -depth 8 flat.png
magick flat.png -attenuate 0.16 +noise Gaussian -depth 8 \
  -quality 94 -sampling-factor 1x1 -strip ../out/featureGraphic.jpg
```

Check any new feature graphic at **240px wide** before accepting it - narrower than any
surface Play renders it on. It decided this asset after three false starts: at 240 nothing
survives but the headline, so the headline has to carry the whole message and everything
else is atmosphere. Real pages are grey noise there; the nine format tiles do survive, but
they reproduce screenshot 1's hero directly above them and read as a formats badge row
once the phone is taken out from under them.

**Grain goes on last, and it has to be added, not blended.** The ground is one flat colour,
so without it the background measures `stddev=0` - a literally dead field, which is what
"boring" meant. It cannot go in the HTML: anything drawn before the 4x downscale is averaged
away. And `-compose Overlay` with a noise layer is near-useless here, because Overlay
compresses toward the existing value and the ground sits at 18/255 - it measured
`stddev=0.31`, invisible. `-attenuate 0.16 +noise Gaussian` on the final 1024x500 gives
`stddev=3.2`, which reads as texture at full size and disappears by 240px.

**The output is JPEG, not PNG.** Grain destroys PNG compression - 271kB flat, 912kB grained,
uncomfortably close to the 1MB the feature graphic slot has historically allowed. JPEG q94 at
4:4:4 is 213kB and indistinguishable from the PNG at 3x magnification on the terracotta
edges, which is the only place ringing would show. Keep `-sampling-factor 1x1`; chroma
subsampling is what would wreck those edges.

**1024x500 is a hard Play requirement** - there is no larger option for a feature graphic,
so perceived sharpness has to come from what is inside those pixels. Two things control it:

- **`ZOOM` in `fg7.py`.** A whole 1080px-wide page shrunk into a 280px card puts 14px body
  text at 3.6px, below the resolution floor - it smears however it is rendered. `ZOOM=1.75`
  shows a window onto each document instead, putting the same text at ~6.5px, where it
  reads. This is the single biggest lever on how sharp the asset looks.
- **Supersample factor.** 4x, not 2x.

`PAN=0` aligns each window to its own document's left margin; panning right cut the row
labels off the spreadsheet.

**Seven versions exist and all are kept.** The shipped one is `fg7.py`: `fg2.py`'s
composition rebuilt without the soft effects. Verify a change by counting them in the
generated HTML rather than by looking, because looking is what missed them for five passes:

```sh
for f in fg2 fg7; do
  printf '%-5s gradients=%s blurs=%s text-shadow=%s outline=%s\n' "$f" \
    "$(grep -o 'gradient' $f.html | wc -l)" "$(grep -o 'blur(' $f.html | wc -l)" \
    "$(grep -o 'text-shadow' $f.html | wc -l)" "$(grep -o 'outline:' $f.html | wc -l)"
done
# fg2  gradients=6 blurs=17 text-shadow=2 outline=1
# fg7  gradients=0 blurs=0  text-shadow=0 outline=0
```

**Measure the type, do not estimate it.** Poppins is wider than it looks: "Opens
everything." at 72px runs to x=713, not the ~595 a per-character estimate gives, and the
pages were placed on top of it twice. Render the type alone and trim it:

```sh
sed 's#</style>#.card{display:none}.chip{display:none}.lock{display:none}</style>#' fg2.html > t.html
"…/Google Chrome" --headless --disable-gpu --hide-scrollbars --window-size=1024,500 \
  --virtual-time-budget=20000 --screenshot=t.png "file://$PWD/t.html"
magick t.png -crop 1024x80+0+218 +repage -fuzz 12% -trim -format '%w %[fx:page.x+w]\n' info:
```

A throwaway measuring page will silently fall back to a system font and report a width
~20% short. Measure the real file with its elements hidden, not a copy of the markup.

**Crop each page to the document's own top edge, not to a fixed offset.** `+0+300` clears
the app bar on all four captures but leaves a strip of the viewer's dark backdrop above the
content - about 6 rows on the PDF, 10 on the slide. Invisible when the whole page is shrunk
to fit; at `ZOOM=1.75` the slide's strip sits directly above its navy header and reads as a
second toolbar. The offsets that are actually right:

```sh
magick raw/docx.png  -crop 1080x1620+0+300 +repage build/pages/doc.png
magick raw/xlsx.png  -crop 1080x1620+0+300 +repage build/pages/xls.png
magick raw/photo.png -crop 1080x1620+0+300 +repage build/pages/img.png
magick raw/pdf.png   -crop 1080x1612+0+308 +repage build/pages/pdf.png   # page edge
magick raw/pptx.png  -crop 1080x1610+0+310 +repage build/pages/ppt.png   # slide edge
```

Check a re-crop by reading the first row, not by looking: it should be the document's own
colour, never the viewer's `rgb(52,48,41)` backdrop.

```sh
for f in xls ppt img pdf; do magick pages/$f.png -crop 1080x1+0+0 +repage \
  -resize 1x1! -format "$f %[pixel:p{0,0}]\n" info:; done
```

`pages/` is shared with `fg2.py` - the rendered documents, cropped from the
**light-theme** captures in `../raw` with `-crop 1080x1620+0+300`, which drops the status
bar and app bar. Committed because the `d-` captures in `../raw` are dark-theme and would
disappear against the ground.

`RAW` at the top of `pano.py` / `tabpano.py` points at `../raw` and `../../v1.14-tab/raw`.

`tabpano.py` repoints `drift.CW` / `drift.H` at the tablet canvas rather than forking
`drift.py`, so both sets keep one implementation of the tile, card and sweep bands.
Bands are drawn on the canvas and cross the seams - that is what stops four frames
sharing one background - but each panel's caption and device live inside a clipped
`.pan` div, because a slate crossing a seam puts half a tablet in the next Play frame.

**`pano.py` now does the same repoint, and for a while it did not.** `drift.py` defaults
to `CW,H = 6480,1920`, the six-frame canvas it was written against. The seventh phone
frame was added later behind an `os.path.exists` check and nobody told `drift`, so all
three bands stopped dead at x=6480, which is exactly frame 7's left edge: the shipped
`phoneScreenshots/7.png` has no ribbon, no procession and no cards behind its device.
`pano.py` sets `drift.CW, drift.H` right after computing `CW = W*N`, and the generators
read those at call time, so mutating the module before they run is enough.

**Widening the canvas thins the bands unless `n` goes up with it.** Every generator
spreads exactly `n` items across the full width, so at seven frames the procession went
from a tile every 368px to one every 425px: filling frame 7 by making all seven
backgrounds 15% sparser. `final_c.py` wraps the counts in `dens()`, which scales them by
`CW / 6480`, giving 20 -> 23, 13 -> 15 and 9 -> 10 and holding every spacing within 2.4%
of the six-frame design. Add an eighth frame and it self-corrects.

**Do not import `pano` and `tabpano` into one process.** Both mutate the same module
globals, so the last import wins and the loser renders its bands at the other set's
width, silently. No script does this today; the two sets are separate runs.

## Things that will bite you

**Shoot with the debug resource overlay removed.** `app/src/debug/res/` replaces the
launcher icon with a wireframe grid and the app name with "Gander debug", and
`scripts/screenshots/README.md` tells you to install the debug build without
mentioning it. Move that directory aside, `assembleDebug`, install, capture, move it
back — and use **absolute paths** in the restore, because the capture step `cd`s into
`scripts/screenshots` and a relative `mv` in an EXIT trap fails silently there.

Only screens showing the icon or app name are affected: welcome, home, and the About
dialog's toolbar. Document viewers show a filename and are fine.

**Recents can be populated through the SAF picker**, so `run-as` — the whole reason
the tooling README says to use the debug build — is not actually needed for a reshoot.

**The tablet AVD needs `-gpu host`.** With `-gpu swiftshader_indirect` at 2560x1600,
Android's own SystemUI ANRs and covers the screen; three capture runs produced nothing
but "System UI isn't responding". Gander itself was fine throughout.

**WebView text selection cannot be driven by synthetic input.** Long-press via
`input swipe` and via `input motionevent DOWN/UP` both fail, so a PDF
text-selection shot has to be taken by hand. Same class of limit as the pinch-zoom
note already in the tooling README.

**Force-stop DocumentsUI before a folder grant.** The SAF tree-grant flow desyncs
otherwise, and one run captured DocumentsUI's own permission dialog — "Allow Gander
to access files in Documents?" — which reads as Gander asking for permissions.

**Clear junk out of `/sdcard/Documents` first.** Leftover test files (`big-clip*.mp4`,
`archive.zip`, `save-me.pdf`) made the folder listing look like a dump rather than the
curated sample world the tooling README asks for.

## Two things deliberately not in the pictures

**No download size.** Commit `5b9fb92` took it out of a caption because "One 8 MB app"
stayed on the live listing four releases after it stopped being true. A number baked
into a PNG costs a re-render and a Play re-upload to fix.

**No version number.** The About dialog prints it, so `final_c.py` covers that line
with a rectangle of the dialog's own flat surface colour — `VERSION_PATCH`, declared in
the layout rather than painted into the capture, so the raw file stays pristine and a
reshoot keeps the fix.
