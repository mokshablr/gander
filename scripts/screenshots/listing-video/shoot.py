#!/usr/bin/env python3
"""Capture the film's takes off a real device over adb.

    python3 shoot.py --check           # device, build, screenrecord behaviour
    python3 shoot.py --proof           # the high-resolution App info still
    python3 shoot.py --take a3_open    # one take
    python3 shoot.py --all             # all four

**`--proof` is the one that cannot be done the obvious way.** The proof fragment is the most
important element in the film and `audit.py` shows it blown up 3.66x from the press stand-in.
A native 1080 capture only reaches 2.44x, because the proof block is about 46% of the screen
width and B3 shows it 1200 px wide, so the source needs to be roughly 2,600 px across. No
phone screen is.

So this raises `wm size` and `wm density` **together** before capturing. Together is the
whole trick: size alone re-lays the screen out as a tablet, and size with a matching density
keeps every dp identical while Android renders it at more pixels. Text is drawn as vectors,
so the result is genuinely sharp rather than interpolated. It is the same supersampling
`store-art/README.md` uses for the feature graphic, "rendered at 4x, Lanczos down", pointed
at a device instead of a Chrome page.

It is a `screencap`, not a video frame: PNG rather than H.264, and the fragment needs a still
anyway because it only appears once B3's scroll has settled. Taking it separately, on the
same screen in the same session, changes nothing about what it says.

**The device is always restored**, including if this crashes: `wm size reset` and
`wm density reset` run in a finally block. A phone left at 3x density is unusable.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
import time

import audit
import layers
from timeline import Timeline

ADB = pathlib.Path.home() / "Library/Android/sdk/platform-tools/adb"
PKG = "com.arjun.gander"
OUT = pathlib.Path(__file__).parent / "out" / "takes"

# How much more resolution to render the proof at. 3x on a 1080-wide phone gives a 3240 px
# screen and a proof block around 1,478 px, which B3 then shows at 1200: downscaled, which is
# the point. audit.py prints the width actually needed.
PROOF_MULTIPLIER = 3


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run([str(ADB), *args], capture_output=True, text=True)
    if check and r.returncode:
        sys.exit(f"adb {' '.join(args)} failed:\n{r.stderr.strip()}")
    return r.stdout.strip()


def one_device() -> str:
    if not ADB.exists():
        sys.exit(f"adb not found at {ADB}")
    lines = [l for l in adb("devices").splitlines()[1:] if l.strip()]
    ready = [l.split()[0] for l in lines if l.split()[1:2] == ["device"]]
    if not ready:
        sys.exit("No device. Plug the phone in, enable USB debugging, unlock the screen,\n"
                 "and accept the debugging prompt. `adb devices` should list it as `device`.")
    if len(ready) > 1:
        sys.exit(f"More than one device: {ready}. Unplug the others.")
    return ready[0]


def screen_size() -> tuple[int, int]:
    m = re.search(r"Physical size: (\d+)x(\d+)", adb("shell", "wm", "size"))
    if not m:
        sys.exit("could not read the screen size")
    return int(m.group(1)), int(m.group(2))


def screen_density() -> int:
    m = re.search(r"Physical density: (\d+)", adb("shell", "wm", "density"))
    if not m:
        sys.exit("could not read the screen density")
    return int(m.group(1))


def check() -> int:
    dev = one_device()
    w, h = screen_size()
    dens = screen_density()
    print(f"device      {dev}")
    print(f"screen      {w}x{h} at density {dens}")

    installed = adb("shell", "pm", "list", "packages", PKG)
    dbg = adb("shell", "pm", "list", "packages", f"{PKG}.debug")
    print(f"release     {'installed' if PKG in installed.replace(PKG+'.debug','') else 'NOT INSTALLED'}")
    if dbg:
        print("            ** a .debug build is also installed. Never shoot on it: it "
              "carries a\n            wireframe icon and the name 'Gander debug'. **")

    seat_ok = w >= layers.DEV_W and h >= layers.DEV_H
    print(f"seat        {layers.DEV_W}x{layers.DEV_H}; capture is "
          f"{'larger, will downscale' if seat_ok else 'SMALLER, would upscale'}")

    need = audit.needed_source_width()
    got = w * PROOF_MULTIPLIER
    print(f"proof       needs about {need}px wide; {PROOF_MULTIPLIER}x render gives {got}px "
          f"-> {'ok' if got >= need else 'STILL SHORT, raise PROOF_MULTIPLIER'}")

    print("\nscreenrecord across a ViewerActivity launch:")
    print("  Not yet tested. This has gone silent on the emulator when ViewerActivity is\n"
          "  created mid-clip, and A3 and C2 both do that. Run --take a3_open and look at\n"
          "  the result before trusting a long take.")
    return 0


def proof_bounds() -> tuple[int, int, int, int] | None:
    """Exact pixel bounds of the Permissions label and its value, from the view tree.

    Returns (x, y, w, h) covering both rows, or None if they are not on screen. The value
    row's wording differs by OEM and Android version, so it is matched by prefix rather than
    by exact string, and the label is found by its own text rather than by position.
    """
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    adb("shell", "rm", "/sdcard/ui.xml", check=False)

    def boxes(pred):
        out = []
        for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
                             xml):
            t = m.group(1)
            if pred(t):
                out.append((t, *(int(m.group(i)) for i in range(2, 6))))
        return out

    label = boxes(lambda t: t.strip().lower() == "permissions")
    value = boxes(lambda t: t.strip().lower().startswith("no permissions"))
    if not label or not value:
        print(f"  could not find the Permissions rows in the view tree "
              f"(label={len(label)}, value={len(value)})")
        return None
    _, lx1, ly1, lx2, ly2 = label[0]
    tv, vx1, vy1, vx2, vy2 = value[0]
    print(f'  view tree: "Permissions" and "{tv}"')
    x1, y1 = min(lx1, vx1), min(ly1, vy1)
    x2, y2 = max(lx2, vx2), max(ly2, vy2)
    return x1, y1, x2 - x1, y2 - y1


def capture_proof() -> int:
    """The App info screen, rendered at PROOF_MULTIPLIER and captured as a still."""
    one_device()
    w, h = screen_size()
    dens = screen_density()
    big_w, big_h = w * PROOF_MULTIPLIER, h * PROOF_MULTIPLIER
    big_d = dens * PROOF_MULTIPLIER
    OUT.mkdir(parents=True, exist_ok=True)
    dst = OUT / "proof-hires.png"

    # **Light theme, forced**, and on purpose: the swing
    # from the film's warm dark to Android's grey is the signal that the viewer is now
    # looking at something Gander does not control. On a phone in dark mode the App info
    # screen is near-black and blends straight into the film's own ground, losing that.
    was_night = "yes" in adb("shell", "cmd", "uimode", "night").lower()
    print(f"raising the render to {big_w}x{big_h} at density {big_d} "
          f"({PROOF_MULTIPLIER}x), same dp layout"
          + (", and forcing light theme" if was_night else ""))
    try:
        if was_night:
            adb("shell", "cmd", "uimode", "night", "no")
            time.sleep(1.5)
        # Size and density together. Size alone re-lays the screen out as a tablet.
        adb("shell", "wm", "size", f"{big_w}x{big_h}")
        adb("shell", "wm", "density", str(big_d))
        time.sleep(2.0)
        adb("shell", "am", "start", "-a", "android.settings.APPLICATION_DETAILS_SETTINGS",
            "-d", f"package:{PKG}")
        time.sleep(2.5)
        adb("shell", "screencap", "-p", "/sdcard/proof-hires.png")
        adb("pull", "/sdcard/proof-hires.png", str(dst))
        adb("shell", "rm", "/sdcard/proof-hires.png")
        # **Ask the view tree where the rows are, do not guess from pixels.** A threshold
        # heuristic picked the Archive / Uninstall / Force stop row instead, because "a pale
        # label with a pale value under it" describes half this screen. uiautomator gives
        # exact bounds in the same coordinate space as the capture.
        rect = proof_bounds()
    finally:
        # Always, including on a crash. A phone left at 3x density is unusable, and a phone
        # silently switched out of the owner's dark mode is rude.
        adb("shell", "wm", "size", "reset", check=False)
        adb("shell", "wm", "density", "reset", check=False)
        if was_night:
            adb("shell", "cmd", "uimode", "night", "yes", check=False)
        print("device restored: size, density"
              + (", and night mode" if was_night else ""))

    from PIL import Image
    got = Image.open(dst).size
    need = audit.needed_source_width()
    print(f"\nwrote {dst}  {got[0]}x{got[1]}")
    if rect:
        x, y, cw, ch = rect
        pad = round(got[0] * 0.022)
        x, y = max(0, x - pad), max(0, y - pad)
        cw, ch = min(got[0] - x, cw + pad * 2), min(got[1] - y, ch + pad * 2)
        crop = OUT / "proof.png"
        subprocess.run(["magick", str(dst), "-crop", f"{cw}x{ch}+{x}+{y}", "+repage",
                        str(crop)], check=True, capture_output=True)
        print(f"wrote {crop}  {cw}x{ch}  (from the view tree, not from a threshold)")
        print("\nPaste into style.py:")
        print(f"    PROOF_SRC_W = {got[0]}")
        print(f"    PROOF_CROP = ({x}, {y}, {cw}, {ch})")
    if got[0] < need:
        print(f"  ** {got[0]}px wide, and the proof wants about {need}px. Raise "
              f"PROOF_MULTIPLIER and retake. **")
    else:
        print(f"  {got[0]}px against the {need}px the proof needs: enough to downscale.")
    print("\nNext: compose.py --extract-proof to re-derive the crop, then point "
          "layers.APPINFO at it,\nthen measure.py and audit.py.")
    return 0


# ---------------------------------------------------------------------------
# Takes
# ---------------------------------------------------------------------------
#
# **Record long, trim later.** screenrecord writes variable-frame-rate video: it emits a
# frame only when the screen changes, so a static hold produces nothing and a real take came
# back at about 8 fps. Timing an action to land on an exact frame live is therefore hopeless.
# Every take is recorded with generous head and tail, normalised to constant 60 fps, and
# trimmed to its beat's exact frame count afterwards, where a bad in-point costs a re-trim
# rather than a re-shoot.
#
# B2 and B3 are captured as ONE take and split at trim time. That they are unbroken is the
# film's argument; timeline.py asserts the B2-to-B3 boundary stays a hard cut.

# **Kept outside git, in dist/, and that is deliberate.** The three screenrecord captures
# cannot be regenerated without the phone in a specific state: light theme, night mode off,
# and Gander's recents holding the six sample documents. Getting them took five failed takes.
#
# But committing them would break this repo's own settled convention, which is generators in
# and captures out: `.gitignore` already excludes `docs/screenshots/v1.14/` and
# `docs/screenshots/demo-build/` for exactly this reason, and no .mp4 had ever been committed
# here. `dist/` is already ignored and already holds the per-release artifacts that matter,
# which is the same shape of problem. See dist/listing-video-footage/README.md.
RAW = pathlib.Path(__file__).resolve().parents[3] / "dist" / "listing-video-footage"

# The document every take opens. It carries the photographic plate C2's night mode needs, and
# `make_survey.py` picks a crop that night mode will not invert. Matched by text rather than
# by row position: recents reorder every time anything is opened, so a coordinate that works
# once is wrong the next run.
DOC = "Willowmere Phase 3"


class light_theme:
    """Force the device into light mode for the duration, then put it back.

    **Every take is shot light, and that is a decision, not a default.** Gander's theme
    follows the system and Android has no per-app dark mode, so within one unbroken take the
    app and Android's own App info screen cannot differ. B2 and B3 are one take by design.

    Light wins on the only thing that decides it: contrast on the proof. "No permissions
    requested" is a deliberately greyed row, and in dark mode it is mid-grey on near-black,
    sitting on the film's warm near-black ground. That is the most important text in the
    film. Light makes it dark-on-light and it lifts off the ground.

    It is also more consistent than it first appears: the feature graphic's document cards,
    which A1 and A2 reuse, are already cropped from light-theme captures. The Play
    screenshots are dark, but that is a different surface.
    """

    def __enter__(self):
        self.was_night = "yes" in adb("shell", "cmd", "uimode", "night").lower()
        if self.was_night:
            print("    forcing light theme")
            adb("shell", "cmd", "uimode", "night", "no")
            time.sleep(2.0)
        return self

    def __exit__(self, *exc):
        if self.was_night:
            adb("shell", "cmd", "uimode", "night", "yes", check=False)
            print("    night mode restored")
        return False


def _rec_start(name: str) -> None:
    adb("shell", f"rm -f /sdcard/{name}.mp4", check=False)
    subprocess.Popen([str(ADB), "shell", "screenrecord", "--bit-rate", "16000000",
                      "--time-limit", "60", f"/sdcard/{name}.mp4"])
    time.sleep(1.5)   # screenrecord takes a moment to actually begin


def _rec_stop(name: str) -> pathlib.Path:
    adb("shell", "pkill -INT screenrecord", check=False)
    time.sleep(2.5)   # it needs to finalise the container
    RAW.mkdir(parents=True, exist_ok=True)
    dst = RAW / f"{name}.mp4"
    adb("pull", f"/sdcard/{name}.mp4", str(dst))
    adb("shell", f"rm -f /sdcard/{name}.mp4", check=False)
    return dst


def find_text(pattern: str) -> tuple[int, int] | None:
    """Centre of the first view whose text or content-desc matches, or None.

    Positions on a real launcher and in an OEM's popup menus are not guessable, and a wrong
    tap in the middle of a take is a re-shoot. Ask the view tree.
    """
    # uiautomator refuses to dump while the window is in flux, which during a shoot means
    # mid-animation. A single failure used to abort the whole run.
    xml = ""
    for attempt in range(4):
        adb("shell", "rm", "-f", "/sdcard/ui.xml", check=False)
        out = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        if "dumped to" in out:
            xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
            if xml.strip().startswith("<"):
                break
        time.sleep(1.2)
    adb("shell", "rm", "-f", "/sdcard/ui.xml", check=False)
    if not xml:
        return None
    m = re.search(
        rf'(?:text|content-desc)="([^"]*{pattern}[^"]*)"[^>]*'
        rf'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml, re.I)
    if not m:
        return None
    x1, y1, x2, y2 = (int(m.group(i)) for i in range(2, 6))
    return (x1 + x2) // 2, (y1 + y2) // 2


# **Every coordinate is resolved before the recording starts, never during it.**
# `find_text` runs a uiautomator dump, which takes seconds. Doing that mid-take left the
# overflow menu hanging open for about seven of c2's nine seconds and the usable window was
# 0.8 s against a 3.2 s beat. So each take does a silent dry run first, learns where its
# targets are, resets, and only then records and replays known taps.


def long_press(x: int, y: int, ms: int = 700) -> None:
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), str(ms))


def _home() -> None:
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1.2)


def open_doc() -> tuple[int, int]:
    """Find the survey's row in recents. Fails loudly rather than tapping the wrong one."""
    row = find_text(DOC)
    if row is None:
        sys.exit(f"'{DOC}' is not in Gander's recents. Open it once through the file "
                 "picker first; an adb-opened file never enters Recents.")
    return row


def take_a3() -> pathlib.Path:
    """Gander's home, tap the survey in recents, the PDF renders, one short scroll."""
    _open_survey()                      # settles night mode, then back to the list
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)
    row = open_doc()
    print(f"    '{DOC}' row at {row}")
    # Head and tail both generous: the beat is 2.4 s and trim.py needs room either side of
    # the tap to place it. A take that is only just longer than its beat cannot be trimmed.
    _rec_start("a3")
    time.sleep(1.4)
    adb("shell", "input", "tap", str(row[0]), str(row[1]))
    time.sleep(3.0)
    adb("shell", "input", "swipe", "540", "1700", "540", "1100", "700")
    time.sleep(3.0)
    return _rec_stop("a3")


def take_b23() -> pathlib.Path:
    """One unbroken take: out of Gander, long-press the icon, App info, scroll to Permissions.

    **Never split this at capture time.** B2 and B3 are one take and the fact that it is
    unbroken is what the film is arguing.

    The long-press matters and is not a detail: it is the beat that shows a viewer *how* to
    reach the screen themselves, which is the whole point of a film whose line is "check its
    permissions page". Opening Settings by intent would reach the same screen and skip the
    route.
    """
    # --- dry run: learn where everything is, with nothing recording ---
    adb("shell", "am", "force-stop", PKG)
    _home()
    icon = find_text("Gander")
    if icon is None:
        sys.exit("Gander's icon is not on the current home page. Swipe to the page that has "
                 "it and re-run; the take has to start from a home screen that shows it.")
    long_press(*icon)
    time.sleep(1.5)
    info = find_text("App info")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.0)
    if info is None:
        sys.exit("'App info' is not in the launcher's long-press menu on this device.")
    print(f"    icon at {icon}, App info at {info}")

    # --- the take, replaying known taps ---
    #
    # **Tight, because the beat is 2.8 s and the interaction is real.** The first version
    # waited 4.7 s in deliberate sleeps between the home gesture and App info, so B3 began
    # while the launcher's popup was still up and Android's screen only arrived near its end.
    # Speeding the footage afterwards would fix the symptom and break the film: motion rule 5
    # is that recorded footage is never ramped, because the whole argument is that nothing
    # was edited. So the capture is tightened instead, to roughly what the interaction
    # actually takes.
    _open_survey()
    _rec_start("b23")
    time.sleep(0.6)
    _home()
    time.sleep(0.5)
    long_press(*icon)
    time.sleep(0.7)
    adb("shell", "input", "tap", str(info[0]), str(info[1]))
    time.sleep(1.4)
    adb("shell", "input", "swipe", "540", "1700", "540", "1150", "700")
    # **B3 is four seconds of Android's own screen**, and the fragment lifts 1.4 s into it.
    # The first version left 2.1 s of App info after the scroll, so B3 opened on the
    # launcher's popup and the proof was magnified out of a screen that had not arrived.
    time.sleep(5.5)
    return _rec_stop("b23")


def page_is_dark() -> bool:
    """Is the open document's paper currently inverted?

    **Sampled from the paper, never from the middle of the page.** The first version read a
    single pixel at 58% of screen height, which on the survey lands inside the photographic
    plate: the one element night mode is specifically designed to leave as printed. It
    therefore reported "day" with night mode on, and the take recorded the flip backwards.

    Reads a band across the upper page instead, where the survey has title and body text on
    plain paper, and takes the median so a headline or a rule does not swing it.
    """
    adb("shell", "screencap", "-p", "/sdcard/pg.png")
    OUT.mkdir(parents=True, exist_ok=True)
    tmp = OUT / "_pg.png"
    adb("pull", "/sdcard/pg.png", str(tmp))
    adb("shell", "rm", "-f", "/sdcard/pg.png", check=False)
    from PIL import Image
    im = Image.open(tmp).convert("L")
    w, h = im.size
    px = im.load()
    vals = [px[x, y]
            for y in range(int(h * 0.28), int(h * 0.34), 3)
            for x in range(int(w * 0.15), int(w * 0.85), 7)]
    tmp.unlink(missing_ok=True)
    vals.sort()
    median = vals[len(vals) // 2]
    return median < 110


def _open_survey(day: bool = True) -> None:
    """From nothing to the survey open and rendered, in day mode unless told otherwise.

    **Night mode persists**, so a take shot after a night-mode take opens on a black page.
    A3 ends on a day page and B2 opens on one, so they have to agree or the cut between them
    shows the document changing colour for no reason.
    """
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2.2)
    adb("shell", "input", "tap", *[str(v) for v in open_doc()])
    time.sleep(4.0)
    if day and page_is_dark():
        more = find_text("More options") or (1027, 209)
        adb("shell", "input", "tap", str(more[0]), str(more[1]))
        time.sleep(1.5)
        night = find_text("Night mode")
        if night:
            adb("shell", "input", "tap", str(night[0]), str(night[1]))
            time.sleep(2.5)


def take_c2() -> pathlib.Path:
    """The survey PDF by day, then night mode on from the overflow.

    **Both the coordinates and the starting state are established before recording**, and the
    document is re-opened from scratch afterwards rather than assumed to have survived. An
    earlier version backed out of the dry run with KEYCODE_BACK, which closed the viewer as
    well as the menu, so the take's taps landed on Gander's *home* overflow and it recorded
    the About dialog instead of night mode.
    """
    # --- dry run: coordinates, and the page in day mode ---
    _open_survey()
    more = find_text("More options") or (1027, 209)
    adb("shell", "input", "tap", str(more[0]), str(more[1]))
    time.sleep(1.8)
    night = find_text("Night mode")
    if night is None:
        sys.exit("'Night mode' is not in the overflow. Is a document actually open?")
    print(f"    overflow at {more}, Night mode at {night}")

    # Night mode persists between sessions, so a previous shoot can leave it on and this take
    # then records the flip backwards, day arriving instead of night. That is exactly what
    # happened once. Read the page rather than assuming.
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.2)
    if page_is_dark():
        print("    page is inverted; turning night mode off before the take")
        adb("shell", "input", "tap", str(more[0]), str(more[1]))
        time.sleep(1.5)
        adb("shell", "input", "tap", str(night[0]), str(night[1]))
        time.sleep(2.5)
        if page_is_dark():
            sys.exit("could not get the page into day mode before the take")

    # --- the take, from a known state ---
    _open_survey()
    if page_is_dark():
        sys.exit("the page is dark at the top of the take; night mode did not stay off")
    # 3.2 s beat: about 0.9 s of day page, the menu, the flip, then the rest on night. The
    # menu cannot linger, or the flip lands at the very end and the beat cuts before it.
    # 0.8 s was too fast: the overflow had not finished opening, the Night mode tap missed,
    # and the take came back with the page white throughout. The menu needs about 1.2 s.
    _rec_start("c2")
    time.sleep(1.0)
    adb("shell", "input", "tap", str(more[0]), str(more[1]))
    time.sleep(1.2)
    adb("shell", "input", "tap", str(night[0]), str(night[1]))
    time.sleep(4.0)
    if not page_is_dark():
        print("    ** the page is still light at the end of the take; night mode did not "
              "engage **")
    return _rec_stop("c2")


def main() -> int:
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--check", action="store_true", help="device, build and capability report")
    g.add_argument("--proof", action="store_true", help="the high-resolution App info still")
    g.add_argument("--take", metavar="CLIP", help="capture one take")
    g.add_argument("--all", action="store_true", help="capture all four takes")
    args = ap.parse_args()

    if args.check:
        return check()
    if args.proof:
        return capture_proof()

    one_device()
    tl = Timeline()
    jobs = {"a3_open": take_a3, "b23": take_b23, "c2_night": take_c2}
    todo = list(jobs) if args.all else [args.take]
    unknown = [t for t in todo if t not in jobs]
    if unknown:
        sys.exit(f"unknown take(s) {unknown}. Choices: {list(jobs)} "
                 "(b23 covers b2_walkout and b3_appinfo, which are one take)")
    with light_theme():
      for name in todo:
        print(f"\n=== {name} ===")
        p = jobs[name]()
        n = subprocess.run(
            [ "ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
              "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(p)],
            capture_output=True, text=True).stdout.strip()
        print(f"    {p}  {n} raw frames (variable rate; trim.py normalises and cuts)")
    print("\nNext: trim.py, which converts to constant 60 fps and cuts each beat's exact "
          "frame count.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
