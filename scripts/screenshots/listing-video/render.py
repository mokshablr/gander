#!/usr/bin/env python3
"""Render the drawn layer: stills for review, or the full frame sequence.

Same pattern as `../store-art/pano.py`, rotated. That file renders one 7560x1920 canvas and
slices it into seven Play frames with `magick`; this renders a horizontal filmstrip of N
film frames in one Chrome pass and slices it the same way, because launching Chrome 1,680
times to get 1,680 frames would take half an hour and gain nothing.

    python3 render.py --stills            # one frame per beat, plus a contact sheet
    python3 render.py --at 10.4 13.0      # specific times, for checking a cue
    python3 render.py --all               # every frame of the film
    python3 render.py --lift              # only the 12-second clip's frames
    python3 render.py --video             # the 28 s master and the 12.25 s lift, as MP4

Output goes to `out/`, which is gitignored for the same reason `store-art`'s renders are:
it is regenerable and large.

**The font check is not optional.** `store-art/README.md` records that a page which fails to
load Jost falls back silently and measures about 20% short, and a filmstrip that renders in
the fallback looks plausible until it is beside the real screenshots. Every run verifies the
font before it renders anything, and refuses rather than producing a strip that has to be
thrown away later.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import shutil
import subprocess
import sys
import tempfile

import style as S
from layers import check_proof, frame_div
from timeline import DISSOLVE_IN, LIFT_TAIL_FADE
from timeline import FPS, Timeline, frames

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
OUT = pathlib.Path(__file__).parent / "out"

# Frames per Chrome pass. The strip is FRAMES_PER_STRIP * 1920 px wide, and pano.py already
# proves 7560 px renders correctly, so 8 frames at 15,360 px is the most this should be
# pushed without checking. Chrome's own limit is a texture-size one and it fails by
# truncating rather than by erroring, which is why this is conservative.
FRAMES_PER_STRIP = 8


def _chrome(src: pathlib.Path, out: pathlib.Path, w: int, h: int,
            alpha: bool = False) -> None:
    args = [CHROME, "--headless", "--disable-gpu", "--hide-scrollbars",
            "--force-device-scale-factor=1", "--virtual-time-budget=30000"]
    if alpha:
        # Without this Chrome composites onto opaque white and the punched hole comes back
        # as a white rectangle rather than as transparency, which looks like a rendering bug
        # rather than like the feature it is.
        args.append("--default-background-color=00000000")
    args += [f"--window-size={w},{h}", f"--screenshot={out}", f"file://{src}"]
    subprocess.run(args, check=True, capture_output=True)


def verify_font(work: pathlib.Path) -> None:
    """Refuse to render if Jost did not load.

    Sets the same string twice, once in Jost and once in the fallback stack, and compares
    ink widths. Identical means the webfont never arrived and every frame would be set in
    the system sans, which looks fine alone and wrong beside the store screenshots.
    """
    probe = "everything."
    widths = []
    for family in (S.FAMILY, "sans-serif"):
        src = work / f"probe-{family.split(',')[0]}.html"
        png = work / f"probe-{family.split(',')[0]}.png"
        src.write_text(
            '<!doctype html><meta charset="utf-8">'
            f'<link href="{S.GOOGLE_FONTS}" rel="stylesheet">'
            f'<body style="margin:0;background:#000;width:2000px;height:300px">'
            f'<span style="font-family:{family};font-size:168px;font-weight:700;'
            f'letter-spacing:-.045em;color:#fff;line-height:300px">{probe}</span>'
        )
        _chrome(src, png, 2000, 300)
        r = subprocess.run(["magick", str(png), "-fuzz", "12%", "-trim",
                            "-format", "%w", "info:"],
                           check=True, capture_output=True, text=True)
        widths.append(int(r.stdout.strip() or 0))
    if widths[0] == widths[1]:
        sys.exit(
            f"Jost did not load: '{probe}' measures {widths[0]}px in both Jost and the\n"
            "fallback. Every frame would be set in the system sans. Check the network,\n"
            "then re-run. See store-art/README.md on silent font fallback."
        )
    print(f"font ok: Jost {widths[0]}px vs fallback {widths[1]}px")


def render_times(tl: Timeline, times: list[float], names: list[str],
                 work: pathlib.Path, outdir: pathlib.Path,
                 clear: bool = True, alpha: bool = False) -> list[pathlib.Path]:
    """Render an arbitrary list of times, in strips, and slice them.

    The output directory is emptied first. Frame names carry their timestamp, so a retime
    renames every file and a stale frame from the previous timing would otherwise sit
    alongside the new ones looking exactly as legitimate. That is not hypothetical: it is
    how the first pass at this beat was reviewed twice and judged unchanged.
    """
    if clear and outdir.exists():
        for old in outdir.glob("*.png"):
            old.unlink()
    outdir.mkdir(parents=True, exist_ok=True)
    written: list[pathlib.Path] = []
    for chunk_start in range(0, len(times), FRAMES_PER_STRIP):
        chunk = times[chunk_start:chunk_start + FRAMES_PER_STRIP]
        strip_w = S.W * len(chunk)
        html = S.head() + "".join(
            frame_div(tl, t, left=i * S.W, placeholder=not alpha)
            for i, t in enumerate(chunk)
        ) + "</body></html>"
        src = work / f"strip-{chunk_start}.html"
        png = work / f"strip-{chunk_start}.png"
        src.write_text(html)
        _chrome(src, png, strip_w, S.H, alpha=alpha)
        for i, _t in enumerate(chunk):
            dst = outdir / f"{names[chunk_start + i]}.png"
            subprocess.run(
                ["magick", str(png), "-crop", f"{S.W}x{S.H}+{i * S.W}+0", "+repage",
                 str(dst)], check=True, capture_output=True)
            written.append(dst)
        print(f"  {chunk_start + len(chunk)}/{len(times)}")
    return written


def render_video(tl: Timeline, work: pathlib.Path) -> None:
    """The 28 s master and the 12.25 s lift, with placeholder footage.

    **Timing is the one thing stills cannot show**, and the beat sheet is almost entirely
    timing: whether a 120 ms stagger reads as two beats, whether B2's 2.8 s feels like a real
    interaction or like an edit, whether B4's 2.6 s hold is a rest or dead air. So this
    exists before the shoot, not after it.

    Only distinct frames are rendered. The drawn layer is deduplicated by the HTML it
    generates, which is exact rather than approximate: identical markup renders identically.
    About four fifths of the film is a held frame, so this turns a 1,680-frame render into a
    395-frame one, and the sequence is rebuilt from symlinks rather than from 1.7 GB of
    duplicated PNGs.
    """
    seq = OUT / "seq"
    uniq = OUT / "uniq"
    # The sequence is rebuilt every run; the rendered frames are not. **Cached frames are
    # named by the hash of the HTML that produced them, never by frame index.** Keying on
    # the index looks right and is wrong: editing a beat changes what frame 42 contains
    # without changing that it is frame 42, so the cache serves the old picture and the edit
    # silently does not appear. That is the same failure as the stale stills, which is why
    # render_times clears its directory, and it is worth being paranoid about twice.
    # Content addressing makes a changed frame a new file and orphans the old one.
    if seq.exists():
        shutil.rmtree(seq)
    seq.mkdir(parents=True)
    uniq.mkdir(parents=True, exist_ok=True)

    # Which frames are distinct, and which earlier frame each duplicate repeats.
    owner: list[str] = []
    html_for: dict[str, str] = {}
    for i in range(tl.total_frames):
        html = frame_div(tl, i / FPS)
        h = hashlib.sha1(html.encode()).hexdigest()[:16]
        html_for.setdefault(h, html)
        owner.append(h)
    distinct = sorted(set(owner))
    print(f"{tl.total_frames} frames, {len(distinct)} distinct "
          f"({100 * len(distinct) / tl.total_frames:.0f}%)")

    todo = [h for h in distinct if not (uniq / f"{h}.png").exists()]
    stale = {q.stem for q in uniq.glob("*.png")} - set(distinct)
    for name in stale:
        (uniq / f"{name}.png").unlink()
    if stale:
        print(f"dropped {len(stale)} frame(s) whose content is no longer in the film")
    if todo:
        print(f"rendering {len(todo)} ({len(distinct) - len(todo)} already on disk)")
        # Render by content, so a frame is drawn once no matter how many times it appears.
        first_t = {}
        for i, h in enumerate(owner):
            first_t.setdefault(h, i / FPS)
        render_times(tl, [first_t[h] for h in todo], todo, work, uniq, clear=False)
    else:
        print(f"all {len(distinct)} frames already rendered")

    for i, h in enumerate(owner):
        (seq / f"{i:05d}.png").symlink_to(uniq / f"{h}.png")
    print(f"sequence: {len(owner)} symlinks over {len(distinct)} frames")

    # Names carry the duration and the duration is derived, never typed. They were
    # hardcoded "28s" and "12s", which survived the retime to 30 s and would have put a file
    # called gander-listing-28s.mp4 in front of someone as a 30-second film.
    lift_lo, lift_hi = frames(tl.lift[0]), frames(tl.lift[1])
    lift_secs = (lift_hi - lift_lo) / FPS + LIFT_TAIL_FADE
    for old in OUT.glob("gander-*.mp4"):
        old.unlink()
    for label, lo, hi, fade in (
            (f"gander-listing-{tl.duration:g}s", 0, tl.total_frames, 0.0),
            (f"gander-permissions-{lift_secs:g}s", lift_lo, lift_hi, LIFT_TAIL_FADE)):
        out = OUT / f"{label}.mp4"
        n = hi - lo
        # -frames:v caps the OUTPUT, so it has to account for the frames tpad appends.
        # Leaving it at n truncates the fade back off again and silently delivers 12.000 s,
        # which is exactly what the first two encodes did.
        n_out = n + round(fade * FPS)
        vf = ["format=yuv420p"]
        if fade:
            # The fade is **appended**, not cut out of the content. The clip promises
            # frames [lift) *plus* a tail fade, so the clip is 12.25 s of which the first 12
            # are the film's own frames. Fading the last 0.25 s of the 720 instead would
            # deliver 12.00 s and quietly dim the end of B4's payoff card, which is the frame
            # tweet 1 autoplays to. tpad clones the final frame, which is already static, so
            # nothing is invented.
            vf.insert(0, f"tpad=stop_mode=clone:stop_duration={fade:.3f}")
            vf.insert(1, f"fade=t=out:st={n / FPS:.3f}:d={fade:.3f}")
        subprocess.run(
            ["ffmpeg", "-y", "-framerate", str(FPS), "-start_number", str(lo),
             "-i", str(seq / "%05d.png"), "-frames:v", str(n_out),
             "-vf", ",".join(vf), "-fps_mode", "cfr", "-r", str(FPS),
             "-c:v", "libx264", "-preset", "slow",
             "-crf", "16", "-movflags", "+faststart", "-an", str(out)],
            check=True, capture_output=True)
        print(f"  {out.name}  {n_out} frames, {n_out / FPS:.3f}s, "
              f"{out.stat().st_size / 1e6:.1f} MB")


def contact_sheet(pngs: list[pathlib.Path], out: pathlib.Path, cols: int = 3) -> None:
    """One sheet of every still, for looking at the whole film at once."""
    subprocess.run(
        ["magick", "montage", *[str(p) for p in pngs], "-tile", f"{cols}x",
         "-geometry", "600x338+8+8", "-background", "#0E0B06", str(out)],
        check=True, capture_output=True)
    print(f"contact sheet: {out}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stills", action="store_true",
                    help="one representative frame per beat, plus a contact sheet")
    ap.add_argument("--at", nargs="+", type=float, metavar="T",
                    help="render these exact times")
    ap.add_argument("--all", action="store_true", help="every frame of the film")
    ap.add_argument("--lift", action="store_true", help="only the 12-second clip")
    ap.add_argument("--video", action="store_true",
                    help="encode the 28 s master and the 12.25 s lift as MP4")
    ap.add_argument("--alpha", action="store_true",
                    help="render plates with the footage rectangle punched transparent, "
                         "for compositing real takes underneath")
    ap.add_argument("--carousel", action="store_true",
                    help="also write every still at Play's carousel width, for the "
                         "legibility check that store-art/README.md does at 240px")
    args = ap.parse_args()

    if not pathlib.Path(CHROME).exists():
        sys.exit(f"Chrome not found at {CHROME}")
    if not shutil.which("magick"):
        sys.exit("ImageMagick 'magick' not on PATH")

    tl = Timeline()
    OUT.mkdir(exist_ok=True)

    with tempfile.TemporaryDirectory() as td:
        work = pathlib.Path(td)
        problems = check_proof()
        if problems:
            sys.exit("The proof would not read at carousel width:\n  "
                     + "\n  ".join(problems))
        print(f"proof ok: {S.proof_px_at_carousel(1120):.1f}px at "
              f"{S.CAROUSEL_W}px display (floor {S.PROOF_MIN_PX})")
        verify_font(work)

        if args.at:
            times = args.at
            names = [f"t{t:07.3f}".replace(".", "_") for t in times]
            print(f"rendering {len(times)} frame(s)"
                  + (" with the footage rect punched transparent" if args.alpha else ""))
            render_times(tl, times, names, work, OUT / "at", alpha=args.alpha)
            return 0

        if args.video:
            if not shutil.which("ffmpeg"):
                sys.exit("ffmpeg not on PATH")
            render_video(tl, work)
            print(f"\nout: {OUT}")
            return 0

        if args.all or args.lift:
            lo, hi = tl.lift if args.lift else (0.0, tl.duration)
            idx = range(frames(lo), frames(hi))
            times = [i / FPS for i in idx]
            names = [f"{i:05d}" for i in idx]
            label = "lift" if args.lift else "film"
            print(f"rendering {len(times)} frames of the {label} "
                  f"({lo:.3f} to {hi:.3f}s)")
            render_times(tl, times, names, work, OUT / label)
            return 0

        # Default: stills. One frame per beat, taken once everything in it has settled, so
        # each still is the composition rather than a moment mid-arrival.
        times, names = [], []
        for b in tl.beats:
            # Past the beat's own dissolve-in as well as its cues, or the still shows the
            # previous beat crossing over this one and is not a picture of either.
            settled = max(tl.still_from(b.tag), b.start + DISSOLVE_IN.get(b.tag, 0) / FPS)
            t = min(settled + 0.05, b.end - 1.0 / FPS)
            times.append(t)
            names.append(f"{b.tag}-{t:06.3f}".replace(".", "_"))
        print(f"rendering {len(times)} stills, one per beat")
        pngs = render_times(tl, times, names, work, OUT / "stills")
        contact_sheet(pngs, OUT / "contact.png")
        if args.carousel:
            small = OUT / "carousel"
            small.mkdir(exist_ok=True)
            for old in small.glob("*.png"):
                old.unlink()
            for png in pngs:
                subprocess.run(["magick", str(png), "-filter", "Lanczos",
                                "-resize", f"{S.CAROUSEL_W}x", str(small / png.name)],
                               check=True, capture_output=True)
            contact_sheet(sorted(small.glob("*.png")), OUT / "contact-carousel.png")
            print(f"carousel stills: {small}")

    print(f"\nout: {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
