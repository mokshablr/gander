#!/usr/bin/env python3
"""The brand film's two checks, asked of this film's soundtrack.

    .venv/bin/python audio/check.py

Is there a sound where the film says something happens, and can the narration still be
understood through the finished mix. What they can and cannot tell you is written in
../brand-film/audio/check.py. Neither says it sounds good.
"""
import importlib.util, pathlib

HERE = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("checks", HERE.parent / "brand-film" / "audio" / "check.py")
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)
checks.HERE, checks.OUT = HERE, HERE / "out"
# The cues that ought to be heard starting.
checks.SHARP = ["land", "pop", "honk", "echo", "peck", "number", "tile", "fan", "wordmark", "tagline", "url"]

if __name__ == "__main__":
    checks.main()
