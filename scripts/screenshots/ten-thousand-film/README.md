# Ten thousand

A film of 22.5 seconds for the day Gander passed 10,000 downloads on Google Play, 1080x1350
at 60 fps with a 1080x1920 cut. Like the brand film next door, nothing in it is recorded: every
frame is drawn by the page in this directory, and it is that film's engine that draws it.

The idea is in the number. The 1 in 10,000+ is the goose, and it goes along the four zeros
putting its head through each one to show there is nothing in it: no ads, no account, no
internet permission, no price. The plus is that it is open source.

## Run it

It borrows `../brand-film/` for the engine, the typeface and the narrator, so set that up
first (its README has the two `curl` lines for Jost and the two for the voice model).

```sh
open film.html                                   # watch it: space plays, arrows step a frame
node ../brand-film/render.mjs --film film.html --size 1080x1350 --check     # before any real render
node ../brand-film/render.mjs --film film.html --size 1080x1350 --scale 2 --uhd --out out/ten-thousand-1080x1350.mp4
node ../brand-film/render.mjs --film film.html --tall --scale 2 --uhd --out out/ten-thousand-1080x1920.mp4
node ../brand-film/render.mjs --film film.html --size 1080x1350 --stills 1,10.1,16.5
node ../brand-film/render.mjs --film film.html --size 1080x1350 --sheet 0,21,22

open 'film.html?tall'                            # the tall cut, live
```

## How it is put together

| | |
| --- | --- |
| `film.html` | The page. It loads the brand film's `core.js`, `style.js`, `parts.js`, `goose.js` and `main.js`. |
| `src/stage.js` | The frame: 4:5, and how that picture sits on the tall one. |
| `src/props.js` | The numerals as cut paper: a zero with a real hole in it, a comma, a plus. |
| `src/world.js` | The set. Sky, sun, hills, ground and the number, drawn from a view and a pose. |
| `src/scenes/film.js` | 0:00 The number, the four zeros and the plus. All the timings are at the top. |
| `src/scenes/end.js` | 0:17.5 The end card, at the icon's own coordinates. |
| `audio/` | The narration, the score and the checks: this film's script run through the brand film's code. |

**One set, many views.** A view says how big each piece of the number is and where it stands.
Face on they are all one size in a row. From one end they shrink away from us and never
overlap, which is what keeps the number readable. Close up, one zero fills the frame. The
film is a list of views and the times to be at each, and every piece is moved between them,
so there is no camera to keep in step with anything.

**The goose goes through a zero** by being drawn between two copies of it: the whole zero
underneath, and the lower part of the rim once more on top. Its neck goes behind the rim and
its beak comes out over it.

**The tall cut** is the same film on 1080x1920. The 4:5 picture is set down whole with more
sky above and more ground below. Close up, where there is the height for it, the zeros are
drawn larger and the frame cuts off more of their neighbours.

## Sound

```sh
node ../brand-film/render.mjs --film film.html --size 1080x1350 --cues   # out/cues.json
.venv/bin/python audio/voice.py --phonemes   # read these before making the voice
.venv/bin/python audio/voice.py --check
python3 audio/score.py                       # out/soundtrack.wav and the stems
.venv/bin/python audio/check.py
python3 ../brand-film/audio/octaves.py out/soundtrack.wav
../brand-film/audio/mux.sh out/ten-thousand-1080x1350.mp4 out/soundtrack.wav out/ten-thousand-1080x1350-sound.mp4
```

The venv is the brand film's recipe plus scipy: `python3 -m venv .venv && .venv/bin/pip
install kokoro-onnx soundfile faster-whisper scipy`. `models` is a link to `../brand-film/models`.

It is written to picture, like the brand film's. The six pieces of the number land as six
notes going up, and the same six come back when the whole number does. Each zero is a note
when the goose comes through it. Before the honk everything stops; the honk has the bar to
itself and comes back twice, smaller, from the right and then the left, which is where the
empty zeros are. The three notes under "Take a gander at the zeros." are the three under the
wordmark, and on the end card each one brings in a line: the name, the line under it, the
address. The narrator says the name and then the line, as she does at the end of the brand
film, over the last chord, which is held under her and comes back up when she has finished.

The recogniser in `check.py` writes numbers as figures, so it reports "ten thousand", "eight"
and "eleven" as not heard when it has heard them and written 10,000, 8 and 11. Read what it
heard before believing that line.

## What it claims

A film cannot be edited once it is posted, so every word in it was checked on the day it
was made, 25 November 2026:

- **10,000+ downloads on Google Play**, dated on screen as November 2026. It is the only
  count on screen. This film has a number in it, where the brand film has none, because it
  is a dated milestone and cannot go stale.
- **No ads, no account, no internet permission.** **Free.** **Open source, MIT licensed.**
- In the narration only: **eight weeks on Google Play** (since 29 September 2026) and
  **eleven updates** (2.1 to 2.11).

The end card carries the app's own line, "Take a gander at any file.", as the brand film's
does. No size and no version on screen. If one of these stops being true the film is not wrong,
because it is dated, but it should not be posted again as if it were new.
