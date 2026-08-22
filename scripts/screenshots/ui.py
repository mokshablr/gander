#!/usr/bin/env python3
"""Tiny adb/uiautomator driver used to pose Gander for screenshots."""
import os
import re
import shutil
import subprocess
import sys
import time
import defusedxml.ElementTree as ET


def _adb():
    """adb from ANDROID_HOME, then PATH, then the macOS default install."""
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if root:
            candidate = os.path.join(root, "platform-tools", "adb")
            if os.path.exists(candidate):
                return candidate
    found = shutil.which("adb")
    if found:
        return found
    fallback = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
    if os.path.exists(fallback):
        return fallback
    raise SystemExit("adb not found. Set ANDROID_HOME or put adb on PATH.")


ADB = _adb()

# The debug build, because seeding a realistic recents list needs `run-as`, which
# only works on a debuggable package. It is the same code and the same look as
# release; only the applicationId suffix differs.
PKG = "com.arjun.gander.debug"

# Captures land where render-screenshot-captions.sh expects to find them.
VERSION = os.environ.get("GANDER_SHOT_VERSION", "v1.9")
_REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RAW = os.path.join(_REPO, "docs", "screenshots", VERSION, "raw")
os.makedirs(RAW, exist_ok=True)


def sh(*args, **kw):
    return subprocess.run([ADB] + list(args), capture_output=True, text=True, **kw)


def shell(cmd):
    return sh("shell", cmd).stdout


def dump():
    """Return the parsed uiautomator hierarchy."""
    for _ in range(4):
        sh("shell", "uiautomator dump /data/local/tmp/win.xml")
        raw = sh("exec-out", "cat /data/local/tmp/win.xml").stdout
        if raw and "<hierarchy" in raw:
            try:
                return ET.fromstring(raw)
            except ET.ParseError:
                pass
        time.sleep(1)
    raise RuntimeError("could not dump ui")


def _center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(pred, root=None):
    root = root if root is not None else dump()
    for n in root.iter("node"):
        if pred(n):
            return n
    return None


def by_text(text, exact=False, root=None, min_y=None, cls=None):
    def p(n):
        t = n.get("text", "") or ""
        d = n.get("content-desc", "") or ""
        if cls and cls not in n.get("class", ""):
            return False
        if min_y is not None and _center(n.get("bounds"))[1] < min_y:
            return False
        if exact:
            return t == text or d == text
        return text.lower() in t.lower() or text.lower() in d.lower()
    return find(p, root)


def tap_text(text, exact=False, wait=1.6, tries=3, min_y=None, cls=None):
    for i in range(tries):
        n = by_text(text, exact, min_y=min_y, cls=cls)
        if n is not None:
            x, y = _center(n.get("bounds"))
            sh("shell", f"input tap {x} {y}")
            time.sleep(wait)
            return True
        time.sleep(1.2)
    return False


def tap(x, y, wait=1.2):
    sh("shell", f"input tap {x} {y}")
    time.sleep(wait)


def swipe(x1, y1, x2, y2, ms=350, wait=1.0):
    sh("shell", f"input swipe {x1} {y1} {x2} {y2} {ms}")
    time.sleep(wait)


def back(wait=1.2):
    sh("shell", "input keyevent KEYCODE_BACK")
    time.sleep(wait)


def cap(name):
    path = os.path.join(RAW, name if name.endswith(".png") else name + ".png")
    with open(path, "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"],
                               capture_output=True).stdout)
    return path


def texts():
    """All visible text, for debugging what is on screen."""
    out = []
    for n in dump().iter("node"):
        t = (n.get("text") or "").strip()
        d = (n.get("content-desc") or "").strip()
        if t or d:
            out.append(f"{t or '(-)'} | {d or '(-)'} | {n.get('bounds')} | {n.get('class','')}")
    return out


def launch():
    sh("shell", f"am start -n {PKG}/com.arjun.gander.MainActivity")
    time.sleep(2.2)


def demo_mode():
    cmds = [
        "settings put global sysui_demo_allowed 1",
        "am broadcast -a com.android.systemui.demo -e command enter",
        "am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0930",
        "am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false",
        "am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true",
        "am broadcast -a com.android.systemui.demo -e command network -e mobile hide",
        "am broadcast -a com.android.systemui.demo -e command notifications -e visible false",
        "am broadcast -a com.android.systemui.demo -e command status -e volume hide -e bluetooth hide"
        " -e alarm hide -e location hide -e sync hide -e tty hide -e eri hide -e mute hide -e speakerphone hide",
    ]
    for c in cmds:
        sh("shell", c)
    time.sleep(0.6)


def demo_exit():
    sh("shell", "am broadcast -a com.android.systemui.demo -e command exit")


if __name__ == "__main__":
    if sys.argv[1:] and sys.argv[1] == "texts":
        print("\n".join(texts()))
    elif sys.argv[1:] and sys.argv[1] == "cap":
        print(cap(sys.argv[2]))
    elif sys.argv[1:] and sys.argv[1] == "demo":
        demo_mode()
