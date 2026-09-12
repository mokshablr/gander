#!/usr/bin/env python3
"""Composite the drawn layer with the recorded takes and encode the two deliverables.

    python3 compose.py --standin     # prove the pipeline against stand-in takes
    python3 compose.py --takes DIR   # the real thing, from a directory of four clips

**Three layers per footage frame, and no blend modes anywhere.**

    1. the drawn plate, opaque, with a dark rounded seat where the take goes
    2. the take, masked to that same rounded rect, its opacity following the dissolve
    3. an `over` plate, transparent, for the one thing that sits above a take

Order matters and the first attempt got it backwards. Punching a transparent hole in the
plate and sliding footage underneath looks like the obvious design and fails twice: Chrome
composites the page onto an opaque body background so the hole comes back solid, and a
dissolving plate carries `opacity < 1`, which creates a stacking context and confines
`mix-blend-mode: destination-out` to that plate instead of erasing the frame beneath it. The
take would have snapped in at full opacity inside a soft cut. Putting the take on top
instead makes the cross-fade fall out of its own opacity for free.

`over` is almost always empty. B3's magnified fragment is the only element in the film that
has to be drawn above a take, and it comes from `layers.py`'s manifest as HTML.

**The fragment's content is a still, not per-frame footage.** It appears only after B3's
scroll has settled, so the real pipeline lifts one frame out of `b3_appinfo` at the settle
moment, crops the two proof rows, and points `layers.APPINFO` at it. That is literally what
the film does: a magnified fragment of exactly that row lifts out of the same
capture". `--extract-proof` does it.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import shutil
import subprocess
import sys
import tempfile

import audit
import layers
import style as S
from layers import frame, frame_div
from timeline import DISSOLVE_IN, FPS, LIFT_TAIL_FADE, Timeline, frames

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
HERE = pathlib.Path(__file__).parent
OUT = HERE / "out"
CLIPS = ("a3_open", "b2_walkout", "b3_appinfo", "c2_night")
SEAT_RADIUS = 28


def _run(cmd: list[str]) -> subprocess.CompletedProcess:
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"failed: {' '.join(cmd[:6])}...\n{r.stderr[-2000:]}")
    return r


def check_takes(d: pathlib.Path) -> dict[str, pathlib.Path]:
    """Every clip present, at the right duration, at the device's aspect."""
    tl = Timeline()
    found = {}
    problems = []
    for clip in CLIPS:
        p = d / f"{clip}.mp4"
        if not p.exists():
            problems.append(f"{clip}.mp4 is missing")
            continue
        beat = next(b for b in tl.beats if b.footage == clip)
        r = _run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
                  "-show_entries", "stream=nb_read_frames,width,height",
                  "-of", "csv=p=0:s=,", str(p)]).stdout.strip().split(",")
        w, h, n = int(r[0]), int(r[1]), int(r[2])
        want = round(beat.dur * FPS)
        if abs(n - want) > 1:
            problems.append(f"{clip}: {n} frames, its beat needs {want} "
                            f"({beat.dur:.3f}s at {FPS} fps)")
        if abs(w / h - layers.DEV_W / layers.DEV_H) > 0.02:
            problems.append(f"{clip}: {w}x{h} does not match the seat's "
                            f"{layers.DEV_W}x{layers.DEV_H} aspect")
        found[clip] = p
    if problems:
        sys.exit("takes are not usable:\n  " + "\n  ".join(problems))
    return found

def render_plates(tl: Timeline, work: pathlib.Path) -> tuple[pathlib.Path, dict]:
    """Every distinct drawn frame, plus the transparent `over` plates that need one.

    Deduplicated by the HTML that produced it, which is exact: identical markup renders
    identically. Frames are named by that hash and cached, so re-encoding costs seconds.
    """
    uniq = OUT / "uniq-c"
    uniq.mkdir(parents=True, exist_ok=True)

    # **Two caches, keyed independently.** Keying the over-plate by the *plate's* hash looks
    # like a harmless shortcut and is a real bug: B2 and B3 draw byte-identical plates, both
    # being ground plus the empty seat, because B3's fragment lives in the over layer. They
    # therefore collided, one over-plate survived for both, and B3's fragment was painted
    # across every frame of B2.
    # Hashes first, HTML later. Holding every distinct frame's markup costs tens of
    # megabytes for no reason: a dissolve frame carries two full plates, and the machine this
    # runs on has had the job killed for memory once already. The markup for the handful that
    # actually need rendering is cheap to rebuild on demand.
    owner: list[tuple[str, str | None]] = []
    first_t: dict[str, float] = {}
    over_first_t: dict[str, float] = {}
    for i in range(tl.total_frames):
        t = i / FPS
        pk = hashlib.sha1(frame_div(tl, t, placeholder=False).encode()).hexdigest()[:16]
        first_t.setdefault(pk, t)
        _, manifest = frame(tl, t, placeholder=False)
        oh = manifest[0][5] if manifest and manifest[0][5] else ""
        ok = None
        if oh:
            ok = hashlib.sha1(oh.encode()).hexdigest()[:16]
            over_first_t.setdefault(ok, t)
        owner.append((pk, ok))

    distinct = sorted(first_t)
    over_keys = sorted(over_first_t)
    todo = [h for h in distinct if not (uniq / f"plate-{h}.png").exists()]
    live = {f"plate-{h}" for h in distinct} | {f"over-{h}" for h in over_keys}
    for q in uniq.glob("*.png"):
        if q.stem not in live:
            q.unlink()
    print(f"{tl.total_frames} frames, {len(distinct)} plates ({len(todo)} to render), "
          f"{len(over_keys)} over-plates")

    # Four, not eight. Chrome holds the whole strip as a bitmap, so eight 1920-wide frames
    # is a 15,360 x 1080 surface and about 66 MB of peak; four halves it. This job has been
    # killed for memory on this machine once already.
    PER = 4
    for k in range(0, len(todo), PER):
        chunk = todo[k:k + PER]
        page = S.head() + "".join(
            f'<div style="position:absolute;left:{j * S.W}px;top:0;width:{S.W}px;'
            f'height:{S.H}px;overflow:hidden">'
            f'{frame_div(tl, first_t[h], placeholder=False)}</div>'
            for j, h in enumerate(chunk)) + "</body></html>"
        src, png = work / f"p{k}.html", work / f"p{k}.png"
        src.write_text(page)
        _run([CHROME, "--headless", "--disable-gpu", "--hide-scrollbars",
              "--force-device-scale-factor=1", "--virtual-time-budget=30000",
              f"--window-size={S.W * len(chunk)},{S.H}", f"--screenshot={png}",
              f"file://{src}"])
        for j, h in enumerate(chunk):
            _run(["magick", str(png), "-crop", f"{S.W}x{S.H}+{j * S.W}+0", "+repage",
                  str(uniq / f"plate-{h}.png")])
        print(f"  {min(k + PER, len(todo))}/{len(todo)}")

    # The `over` plates, transparent, only for the frames that carry one.
    need_over = [h for h in over_keys if not (uniq / f"over-{h}.png").exists()]
    for k in range(0, len(need_over), PER):
        chunk = need_over[k:k + PER]
        # alpha=True: without it the body background makes this plate opaque and it paints
        # over the whole frame. See style.head.
        page = S.head(alpha=True) + "".join(
            f'<div style="position:absolute;left:{j * S.W}px;top:0;width:{S.W}px;'
            f'height:{S.H}px;overflow:hidden">'
            f'{frame(tl, over_first_t[h], placeholder=False)[1][0][5]}'
            f'<div style="position:absolute;inset:0;pointer-events:none;'
            f'background:{S.VIGNETTE}"></div></div>'
            for j, h in enumerate(chunk)) + "</body></html>"
        src, png = work / f"o{k}.html", work / f"o{k}.png"
        src.write_text(page)
        _run([CHROME, "--headless", "--disable-gpu", "--hide-scrollbars",
              "--force-device-scale-factor=1", "--virtual-time-budget=30000",
              "--default-background-color=00000000",
              f"--window-size={S.W * len(chunk)},{S.H}", f"--screenshot={png}",
              f"file://{src}"])
        for j, h in enumerate(chunk):
            _run(["magick", str(png), "-crop", f"{S.W}x{S.H}+{j * S.W}+0", "+repage",
                  str(uniq / f"over-{h}.png")])
    if need_over:
        print(f"  rendered {len(need_over)} over-plates")
    return uniq, {"owner": owner}


def composite(tl: Timeline, takes: dict, uniq: pathlib.Path, meta: dict,
              work: pathlib.Path, scale: float = 1.0) -> pathlib.Path:
    """One PNG per frame: plate, then the take on top, then the over plate.

    **Done in-process with Pillow, not by shelling out to ImageMagick per frame.** The first
    version spawned one `magick` per footage frame, 744 processes, which was both the whole
    runtime and most of the memory pressure: the job was killed three times. Pillow does the
    same three operations without a fork.

    Order matters and the obvious arrangement is wrong. Punching a transparent hole in the
    plate and sliding footage underneath fails twice: Chrome composites the page onto an
    opaque body background so the hole comes back solid, and a dissolving plate carries
    `opacity < 1`, which creates a stacking context and confines `destination-out` to that
    plate. Putting the take on top makes the cross-fade fall out of its own opacity.

    `scale` shrinks the written frame and is for proving the pipeline only; the deliverable
    path refuses it.
    """
    from PIL import Image, ImageDraw

    seq = OUT / "seq-c"
    if seq.exists():
        shutil.rmtree(seq)
    seq.mkdir(parents=True)

    # The seat's rounded-rect alpha, built once.
    mask = Image.new("L", (layers.DEV_W, layers.DEV_H), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, layers.DEV_W - 1, layers.DEV_H - 1], radius=SEAT_RADIUS, fill=255)

    # Every take decoded once, to stills. Pulling frames with `ffmpeg select=eq(n,X)`
    # re-decodes from frame zero each time: quadratic in the take's length for linear work.
    stills = {}
    for clip, src in takes.items():
        d = work / clip
        d.mkdir()
        _run(["ffmpeg", "-y", "-i", str(src), "-vf", f"scale={layers.DEV_W}:{layers.DEV_H}",
              "-start_number", "0", str(d / "%05d.png")])
        stills[clip] = d
        print(f"  decoded {clip}: {len(list(d.glob('*.png')))} frames")

    # **The seat's position comes from the manifest, per frame, not from layers.DEV_X.** The
    # device is centred while it is alone in the frame and slides right as the proof lifts out
    # of it, so a constant would paste every take in the wrong place for most of the lift.
    # This is what the manifest was for.
    take_frame = {}
    for b in tl.beats:
        if b.footage:
            for i in range(frames(b.start), frames(b.end)):
                _, mf = frame(tl, i / FPS, placeholder=False)
                take_frame[i] = (b.footage, i - frames(b.start), b, mf[0][1], mf[0][2])

    ow, oh = round(S.W * scale), round(S.H * scale)
    over_cache: dict[str, object] = {}
    for i, (pk, ok) in enumerate(meta["owner"]):
        plate_path = uniq / f"plate-{pk}.png"
        dst = seq / f"{i:05d}.png"
        if i not in take_frame:
            if scale == 1.0:
                dst.symlink_to(plate_path)
            else:
                Image.open(plate_path).convert("RGB").resize((ow, oh),
                    Image.LANCZOS).save(dst)
            continue

        clip, fi, beat, sx, sy = take_frame[i]
        still_path = stills[clip] / f"{fi:05d}.png"
        if not still_path.exists():
            sys.exit(f"{clip} has no frame {fi}; check_takes should have caught this")

        frame_img = Image.open(plate_path).convert("RGB")
        take = Image.open(still_path).convert("RGB")
        # Opacity follows the dissolve into this beat, so the take crosses in with the plate
        # rather than snapping to full inside a soft cut.
        n = DISSOLVE_IN.get(beat.tag, 0)
        op = min(1.0, (i - frames(beat.start) + 1) / n) if n else 1.0
        m = mask if op >= 1.0 else mask.point(lambda v, o=op: int(v * o))
        frame_img.paste(take, (sx, sy), m)

        if ok:
            if ok not in over_cache:
                over_cache.clear()      # one at a time; these are 8 MB each
                over_cache[ok] = Image.open(uniq / f"over-{ok}.png").convert("RGBA")
            ov = over_cache[ok]
            frame_img.paste(ov, (0, 0), ov)

        if scale != 1.0:
            frame_img = frame_img.resize((ow, oh), Image.LANCZOS)
        frame_img.save(dst, compress_level=1)
        if i % 200 == 0:
            print(f"  composited {i}/{tl.total_frames}", flush=True)
    return seq


def encode(tl: Timeline, seq: pathlib.Path, suffix: str) -> None:
    lo, hi = frames(tl.lift[0]), frames(tl.lift[1])
    lift_secs = (hi - lo) / FPS + LIFT_TAIL_FADE
    for old in OUT.glob(f"gander-*{suffix}.mp4"):
        old.unlink()
    for label, a, b, fade in (
            (f"gander-listing-{tl.duration:g}s{suffix}", 0, tl.total_frames, 0.0),
            (f"gander-permissions-{lift_secs:g}s{suffix}", lo, hi, LIFT_TAIL_FADE)):
        n = b - a
        n_out = n + round(fade * FPS)
        vf = ["format=yuv420p"]
        if fade:
            vf = [f"tpad=stop_mode=clone:stop_duration={fade:.3f}",
                  f"fade=t=out:st={n / FPS:.3f}:d={fade:.3f}"] + vf
        out = OUT / f"{label}.mp4"
        _run(["ffmpeg", "-y", "-framerate", str(FPS), "-start_number", str(a),
              "-i", str(seq / "%05d.png"), "-frames:v", str(n_out), "-vf", ",".join(vf),
              "-fps_mode", "cfr", "-r", str(FPS), "-c:v", "libx264", "-preset", "slow",
              "-crf", "16", "-movflags", "+faststart", "-an", str(out)])
        print(f"  {out.name}  {n_out} frames, {n_out / FPS:.3f}s, "
              f"{out.stat().st_size / 1e6:.1f} MB")


def extract_proof(d: pathlib.Path) -> int:
    """Lift the proof still out of the real b3 take, and re-derive its crop.

    The fragment's content is a still, not per-frame footage: it appears only once B3's
    scroll has settled, so one frame carries it. That is literally what the film
    describes, "a magnified fragment of exactly that row lifts out of the same capture".

    Both proof rows are greyed out, which is the point of the screen and also why this
    thresholds **pale** rather than dark. A normal ink threshold finds the Notifications row
    above them instead, which is the mistake that first rendered the phrase 3.1 px tall.
    """
    from PIL import Image
    tl = Timeline()
    # Prefer the dedicated high-resolution still. It is what shoot.py --proof captures and
    # the only source that does not leave the film's most important element upscaled: a
    # video frame of the same screen is 1080 px wide and B3 shows the crop at 1200.
    hires = d / "proof-hires.png"
    if hires.exists():
        still = hires
        print(f"using {hires.name}, the dedicated high-resolution capture")
    else:
        src = d / "b3_appinfo.mp4"
        if not src.exists():
            sys.exit(f"no proof-hires.png and no b3_appinfo.mp4 in {d}.\n"
                     "Run shoot.py --proof.")
        beat = tl["B3"]
        settled = beat.start + beat.cues["settled"].at
        fi = frames(settled) - frames(beat.start)
        still = d / "b3-settled.png"
        _run(["ffmpeg", "-y", "-i", str(src), "-vf", f"select=eq(n\\,{fi})",
              "-vframes", "1", str(still)])
        print(f"took frame {fi} of b3_appinfo at {settled:.3f}s, the settle cue")
        print("  ** that is a 1080-wide video frame; the proof will be upscaled. "
              "Prefer shoot.py --proof. **")

    im = Image.open(still).convert("L")
    w, h = im.size
    px = im.load()
    THR = 205
    step = max(1, w // 720)      # sample columns; a 3240 px capture does not need every one
    rows = [(y, sum(1 for x in range(0, w, step) if px[x, y] < THR))
            for y in range(h // 3, h)]
    bands, start, prev = [], None, None
    for y, n in rows:
        if n > 2 and start is None:
            start = y
        if n <= 2 and start is not None:
            bands.append((start, prev))
            start = None
        if n > 2:
            prev = y
    if start is not None:
        bands.append((start, prev))
    if len(bands) < 2:
        sys.exit("could not find two proof rows in the settled frame. Check the scroll "
                 "actually stopped on Permissions, and that the capture is not cropped.")

    # The two proof rows are the pair whose lower row is the widest run of pale text under
    # a short label. Find "Permissions" by its own text rather than by position: the rows
    # below it differ by device and by Android version.
    pairs = [(a, b) for a, b in zip(bands, bands[1:])
             if 0 < b[0] - a[1] < (h // 40)]
    if not pairs:
        sys.exit("could not pair a label with a value row")
    lab, phrase = pairs[0]
    for a, b in pairs:
        xs_b = [x for y in range(b[0], b[1] + 1, 2) for x in range(0, w, step)
                if px[x, y] < THR]
        if xs_b and (max(xs_b) - min(xs_b)) > 0:
            lab, phrase = a, b
            break
    pad_x, pad_y = round(w * 0.028), round(w * 0.025)
    xs = [x for y in range(lab[0], phrase[1] + 1, 2) for x in range(0, w, step)
          if px[x, y] < THR]
    cx = max(0, min(xs) - pad_x)
    cy = max(0, lab[0] - pad_y)
    cw, ch = max(xs) - cx + pad_x, phrase[1] - cy + pad_y
    glyph = phrase[1] - phrase[0] + 1
    out = d / "proof.png"
    _run(["magick", str(still), "-crop", f"{cw}x{ch}+{cx}+{cy}", "+repage", str(out)])

    need = S.PROOF_MIN_PX / (S.CAROUSEL_W / S.W) / glyph * cw
    print(f"\nwrote {out}")
    print("Paste into style.py:")
    print(f"    PROOF_SRC_W = {w}")
    print(f"    PROOF_CROP = ({cx}, {cy}, {cw}, {ch})")
    print(f"    PROOF_GLYPH_H = {glyph}")
    print(f"\nNarrowest fragment meeting the {S.PROOF_MIN_PX}px floor: {need:.0f}px "
          f"(layers.py uses {layers.FRAG_W_B4} at B4)")
    if need > layers.FRAG_W_B4:
        print("  ** WIDER than the current fragment. Widen it and let the layout give way, "
              "not the proof. **")
    print("Then point layers.APPINFO at proof.png and re-run measure.py.")
    return 0


def audit_gate() -> bool:
    """True if anything in the film would be upscaled. Printed in full, then refused."""
    bad = [n for n, src, shown, sc, _t in audit.rows()
           if src == "MISSING" or sc > audit.LIMIT]
    if not bad:
        print("audit ok: nothing in the film is upscaled")
        return False
    print("\nRefusing to build a deliverable. These would be upscaled:")
    for n, src, shown, sc, is_type in audit.rows():
        if src == "MISSING" or sc > audit.LIMIT:
            print(f"  {n:<34} {src:>14} shown at {shown} "
                  f"({sc:.2f}x{' on TYPE' if is_type else ''})")
    print(f"\nThe proof needs an App info capture about {audit.needed_source_width()}px "
          "wide.\nRun shoot.py --proof, then compose.py --extract-proof. "
          "Fix the source, not the layout.")
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--standin", action="store_true",
                   help="use out/standin, and mark the output as not a deliverable")
    g.add_argument("--takes", metavar="DIR", help="directory holding the four real takes")
    ap.add_argument("--scale", type=float, default=None, metavar="F",
                    help="shrink the output; proving the pipeline does not need full "
                         "resolution. Defaults to 0.5 for --standin and 1.0 for --takes.")
    ap.add_argument("--extract-proof", action="store_true",
                    help="lift the proof still out of the real b3 take and re-derive its "
                         "crop, instead of compositing")
    args = ap.parse_args()

    if args.extract_proof:
        if args.standin:
            sys.exit("--extract-proof needs the real b3 take, not a stand-in: the crop it "
                     "derives decides whether the film's one phrase is legible.")
        return extract_proof(pathlib.Path(args.takes))

    src = OUT / "standin" if args.standin else pathlib.Path(args.takes)
    if not src.exists():
        sys.exit(f"no takes at {src}" + ("  (run standins.py first)" if args.standin else ""))
    if not args.standin and (src / "STANDIN").exists():
        sys.exit(f"{src} is marked as stand-in footage. It is for proving the pipeline, "
                 "never for a deliverable. Use --standin, or point --takes at real ones.")

    tl = Timeline()
    takes = check_takes(src)
    print(f"takes ok: {', '.join(takes)}")
    with tempfile.TemporaryDirectory() as td:
        work = pathlib.Path(td)
        scale = args.scale if args.scale else (0.5 if args.standin else 1.0)
        if not args.standin:
            # A deliverable is 1920x1080 and nothing in it is upscaled. Both halves are
            # refused rather than warned about, because a warning in a long log is how a
            # soft render reaches a store listing.
            if scale != 1.0:
                sys.exit(f"--scale {scale:g} on real takes: the final cut is 1080p and is "
                         "not scaled. Drop --scale.")
            if audit_gate():
                return 1
        uniq, meta = render_plates(tl, work)
        seq = composite(tl, takes, uniq, meta, work, scale=scale)
        encode(tl, seq, "-standin" if args.standin else "")
        if scale != 1.0:
            print(f"  (rendered at {scale:g}x, {round(S.W * scale)}x{round(S.H * scale)})")
    if args.standin:
        print("\n** STAND-IN FOOTAGE. Proves the pipeline; not the film. **")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
