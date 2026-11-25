#!/usr/bin/env python3
"""The narration for this film, one WAV per line, by the brand film's narrator.

    .venv/bin/python audio/voice.py --phonemes   # read this first: how each line will be said
    .venv/bin/python audio/voice.py --check      # make the lines, and transcribe them back

Everything about how a line is made is in ../brand-film/audio/voice.py, which this runs with
its own script and its own film: the same voice, the same trimming, the same rule that a line
is pinned to a cue and never to a number. Read what that file says about phonemes before
changing a line here.
"""
import importlib.util, pathlib

HERE = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("narrator", HERE.parent / "brand-film" / "audio" / "voice.py")
narrator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(narrator)

narrator.HERE, narrator.OUT = HERE, HERE / "out" / "voice"
# (id, cue name, which occurrence of it, seconds after, the line, how to say it)
narrator.LINES = [
    ("news",     "sunrise",  0,  0.30, "Over ten thousand downloads on Google Play.", None),
    ("zeros",    "sub",      0,  0.95, "Take a gander at the zeros.", None),
    ("ads",      "label",    0,  0.33, "No ads.", None),
    ("account",  "label",    1,  0.33, "No account.", None),
    # Said as the view arrives, so that it is over before the goose breathes in.
    ("internet", "label",    2, -0.30, "No internet permission.", None),
    ("free",     "label",    3,  0.10, "And it's free.", None),
    ("open",     "label",    4,  0.20, "Plus, it's open source.", None),
    ("weeks",    "pullback", 0,  0.30, "Eight weeks on Google Play. Eleven updates.", None),
    ("name",     "wordmark", 0,  0.10, "Gander.", None),
    # The line is on screen from the second of the three notes, while the name is still being
    # said, so it is spoken later than it appears: a second after the name's cue, which is
    # where the brand film says it.
    ("tagline",  "tagline",  0,  0.75, "Take a gander at any file.", None),
]
narrator.SPEED = {}

if __name__ == "__main__":
    narrator.main()
