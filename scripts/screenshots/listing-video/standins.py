#!/usr/bin/env python3
"""Build stand-in takes from captures already on disk, so the compositor can be finished
and proved before anyone picks up a phone.

**These are not the film's footage and must never be.** They exist to exercise the real
geometry, the real durations and the real hand-off between `layers.py`'s manifest and
`compose.py`, so that when the four real takes arrive the only thing that changes is the
input files. `compose.py` refuses to build a deliverable from them.

They are made from `docs/screenshots/v1.14/raw/`, which holds genuine 1080x1920 device
captures, plus the press kit's own App info screenshot. Panning and cross-fading between
real screens is honest about proportions in a way a synthetic test pattern would not be,
and it costs no emulator boot: an emulator is not evidence about rendering, the Gander AVD
has its GPU disabled and ANRs at startup unless booted with `-gpu host`, and
`adb shell screenrecord` has gone silent there when `ViewerActivity` is created mid-clip,
which two of the four takes do.

    python3 standins.py

Writes `out/standin/<clip>.mp4` at 1080x1920, 60 fps, each the exact duration its beat
needs, read from `timeline.py` rather than typed here.
"""

from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys

from timeline import FPS, Timeline

HERE = pathlib.Path(__file__).parent
REPO = HERE.resolve().parents[2]
RAW = REPO / "docs/screenshots/v1.14/raw"
PRESS = REPO / "docs/press/assets/gander-permissions.png"
OUT = HERE / "out" / "standin"

W, H = 1080, 1920

# (clip, beat, [(source, seconds)...], description)
# Sources are real captures. Where a take needs a screen we have never captured, the nearest
# honest thing is used and the gap is named, because a stand-in that pretends to be complete
# is worse than one that is visibly partial.
TAKES = [
    ("a3_open", "A3", [("d-home.png", 0.45), ("d-pdf.png", 0.55)],
     "home, then the PDF open. The tap itself is not in any capture."),
    ("b2_walkout", "B2", [("d-home.png", 0.35), ("d-home.png", 0.30), ("__appinfo__", 0.35)],
     "home, then App info. The launcher and its long-press menu have never been captured; "
     "this is the one take the stand-in is weakest at and the real shoot must carry."),
    ("b3_appinfo", "B3", [("__appinfo__", 1.0)],
     "the App info screen, scrolled to Permissions."),
    ("c2_night", "C2", [("d-pdf.png", 0.45), ("d-pdf-night.png", 0.55)],
     "the PDF by day, then night mode on."),
]


def _src(name: str, work: pathlib.Path) -> pathlib.Path:
    """A 1080x1920 still for a source name, letterboxed if it is not that shape."""
    if name == "__appinfo__":
        # 720x813 press capture, scaled to the device's width and padded to its height on
        # the screen's own background so it reads as a phone screen rather than a crop.
        out = work / "appinfo.png"
        if not out.exists():
            subprocess.run(
                ["magick", str(PRESS), "-resize", f"{W}x", "-background", "#EEF0F4",
                 "-gravity", "north", "-extent", f"{W}x{H}", str(out)],
                check=True, capture_output=True)
        return out
    p = RAW / name
    if not p.exists():
        sys.exit(f"missing capture: {p}\nThese live in a gitignored tree; see "
                 f"scripts/screenshots/store-art/README.md")
    return p


def build(tl: Timeline) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    work = OUT / "_work"
    work.mkdir(exist_ok=True)

    for clip, tag, parts, note in TAKES:
        beat = tl[tag]
        total = beat.dur
        dst = OUT / f"{clip}.mp4"
        segs = []
        for i, (name, frac) in enumerate(parts):
            src = _src(name, work)
            secs = total * frac
            seg = work / f"{clip}-{i}.mp4"
            # A slow push, so a held still reads as footage rather than as a freeze. Real
            # takes will not need this; it is here so timing judgements made against the
            # stand-in are not made against a motionless frame.
            subprocess.run(
                ["ffmpeg", "-y", "-loop", "1", "-i", str(src), "-t", f"{secs:.4f}",
                 "-vf", (f"scale={int(W * 1.06)}:-1,"
                         f"zoompan=z='min(zoom+0.0005,1.06)':d={max(1, int(secs * FPS))}"
                         f":x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s={W}x{H}:fps={FPS},"
                         f"format=yuv420p"),
                 "-r", str(FPS), "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                 str(seg)], check=True, capture_output=True)
            segs.append(seg)

        lst = work / f"{clip}.txt"
        lst.write_text("".join(f"file '{s.resolve()}'\n" for s in segs))
        subprocess.run(
            ["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(lst),
             "-t", f"{total:.4f}", "-r", str(FPS), "-c:v", "libx264", "-preset", "veryfast",
             "-crf", "18", "-pix_fmt", "yuv420p", str(dst)],
            check=True, capture_output=True)

        n = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
             "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(dst)],
            check=True, capture_output=True, text=True).stdout.strip()
        want = round(total * FPS)
        flag = "" if abs(int(n) - want) <= 1 else f"  ** wanted {want} **"
        print(f"  {clip:<12} {tag}  {total:.3f}s  {n} frames{flag}")
        print(f"               {note}")

    (OUT / "STANDIN").write_text(
        "Stand-in takes, built by standins.py from captures already on disk.\n"
        "NOT the film's footage. compose.py refuses to build a deliverable from these.\n")
    shutil.rmtree(work, ignore_errors=True)
    print(f"\nout: {OUT}")


if __name__ == "__main__":
    if not shutil.which("ffmpeg") or not shutil.which("magick"):
        sys.exit("needs ffmpeg and ImageMagick on PATH")
    build(Timeline())
