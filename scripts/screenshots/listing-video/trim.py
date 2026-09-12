#!/usr/bin/env python3
"""Normalise raw screenrecord takes to constant 60 fps and cut each beat's exact length.

    python3 trim.py --list                 # what is in out/raw, and where it can be cut
    python3 trim.py --auto                 # cut every take from its detected in-point
    python3 trim.py --cut a3_open 1.85     # cut one take from an explicit in-point

**screenrecord writes variable-frame-rate video.** It emits a frame only when the screen
changes, so a static hold produces nothing at all: a real 17.9 s take came back with 140
frames, about 8 fps. Nothing downstream can use that, because every beat needs an exact
count at 60. `fps=60` resamples it, duplicating frames across the holds, which is correct:
the screen genuinely was not changing.

**In-points are found, not timed.** Trying to make an action land on an exact frame during
capture is hopeless against a variable frame rate, so takes are recorded with generous head
and tail and the interesting moment is located afterwards, by looking for the first frame
that differs materially from the opening one. A bad in-point then costs a re-trim rather
than a re-shoot.

B2 and B3 come from a single recording, `b23`, and are split here. That they were never cut
apart is the film's argument; timeline.py asserts that boundary stays a hard cut.
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys

from timeline import FPS, Timeline

# Asked of shoot.py, not restated. Two files each holding their own idea of where the raw
# takes live is how --auto reported "skipped, no a3.mp4" while a3.mp4 sat on disk.
import shoot

HERE = pathlib.Path(__file__).parent
RAW = shoot.RAW
TAKES = shoot.OUT

# Which raw recording each take comes from, and where in it. b23 holds two beats end to end.
SOURCE = {"a3_open": ("a3", 0), "b2_walkout": ("b23", 0), "b3_appinfo": ("b23", 1),
          "c2_night": ("c2", 0)}

# Where each beat begins in its recording, in seconds. **Measured by looking, not detected.**
# Scene detection finds the largest change, which is the event itself, so every take came
# back trimmed to start exactly when the interesting thing had already happened: c2 began on
# the night-mode flip with 0.3 s left.
IN_POINT = {
    "a3_open":    2.40,   # 0.6 s of recents, then the tap and the render
    "b2_walkout": 1.90,   # a moment on the document, the home gesture at 2.2
    "b3_appinfo": 4.70,   # the popup, then App info from 5.6
    "c2_night":   1.80,   # day page, the overflow, the flip at 3.8, then night
}
# The capture's own resolution. **Never cropped to the seat's shape**: the seat's aspect is
# derived from this, not the other way round. Cropping to a 1080x1920 target took 246 px off
# the top and bottom of every take and cut the status and navigation bars away.
import layers as _layers

DEV_W, DEV_H = _layers.CAPTURE


def _run(cmd: list[str]) -> str:
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"failed: {' '.join(cmd[:5])}...\n{r.stderr[-1500:]}")
    return r.stdout.strip()


def normalise(src: pathlib.Path, dst: pathlib.Path) -> pathlib.Path:
    """Variable rate to constant 60 fps, cropped to the device's own aspect."""
    _run(["ffmpeg", "-y", "-i", str(src),
          "-vf", f"fps={FPS},scale={DEV_W}:{DEV_H}",
          "-r", str(FPS), "-c:v", "libx264", "-preset", "veryfast", "-crf", "17",
          "-pix_fmt", "yuv420p", str(dst)])
    return dst

def cut(take: str, start: float, tl: Timeline) -> pathlib.Path:
    beat = next(b for b in tl.beats if b.footage == take)
    n = round(beat.dur * FPS)
    raw_name, index = SOURCE[take]
    src = RAW / f"{raw_name}.mp4"
    if not src.exists():
        sys.exit(f"no raw take at {src}. Run shoot.py --take {raw_name}.")
    norm = RAW / f"{raw_name}-60.mp4"
    if not norm.exists():
        print(f"  normalising {src.name} to constant {FPS} fps")
        normalise(src, norm)
    TAKES.mkdir(parents=True, exist_ok=True)
    dst = TAKES / f"{take}.mp4"
    # **The tail is padded by cloning the final frame, and that is honest.** screenrecord
    # emits a frame only when the screen changes, so a settled App info screen or a finished
    # night-mode flip writes nothing at all and the recording simply ends: waiting longer at
    # capture time produces no more footage. The held frames a constant-rate camera would
    # have recorded are exactly what `tpad` reconstructs. Nothing is invented; the screen
    # genuinely was not changing.
    _run(["ffmpeg", "-y", "-ss", f"{start:.4f}", "-i", str(norm),
          "-vf", f"tpad=stop_mode=clone:stop_duration={beat.dur + 1:.3f}",
          "-frames:v", str(n), "-r", str(FPS), "-fps_mode", "cfr",
          "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p",
          str(dst)])
    got = _run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
                "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(dst)])
    flag = "" if abs(int(got) - n) <= 1 else f"  ** wanted {n}; the raw take is too short **"
    print(f"  {take:<12} from {raw_name} at {start:6.3f}s  {got} frames "
          f"({beat.dur:.3f}s){flag}")
    return dst


def main() -> int:
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--list", action="store_true")
    g.add_argument("--auto", action="store_true")
    g.add_argument("--cut", nargs=2, metavar=("TAKE", "SECONDS"))
    args = ap.parse_args()
    tl = Timeline()

    if args.list:
        for raw in sorted(RAW.glob("*.mp4")):
            if raw.stem.endswith("-60"):
                continue
            d = _run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                      "-of", "csv=p=0", str(raw)])
            print(f"  {raw.name:<12} {float(d):6.2f}s raw")
        print("\n  beats needing takes:")
        for b in tl.beats:
            if b.footage:
                print(f"    {b.footage:<12} {b.tag}  {b.dur:.3f}s  "
                      f"{round(b.dur * FPS)} frames  from '{SOURCE[b.footage][0]}'")
        return 0

    if args.cut:
        cut(args.cut[0], float(args.cut[1]), tl)
        return 0

    for take in SOURCE:
        raw_name, _index = SOURCE[take]
        src = RAW / f"{raw_name}.mp4"
        if not src.exists():
            print(f"  {take:<12} skipped, no {src.name}")
            continue
        norm = RAW / f"{raw_name}-60.mp4"
        if not norm.exists():
            normalise(src, norm)
        cut(take, IN_POINT[take], tl)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
