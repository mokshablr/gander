#!/usr/bin/env python3
"""This film's soundtrack: score, sound design and narration, written to picture.

    python3 audio/score.py     # out/soundtrack.wav, out/soundtrack-no-voice.wav and the stems

The instruments, the mix and the master are the brand film's (../brand-film/audio/score.py),
and so is the rule: nothing here knows a time. Every sound hangs on a cue from out/cues.json,
which the film writes from the constants that move the picture (`render.mjs --cues`).

What is written to this picture. The six pieces of the number land as six notes going up,
and the same six come back when the whole number does. Each scene starts on a cue and takes
one chord. A zero is a note when the goose comes through it. Before the honk everything
stops, the honk has the bar to itself, and it comes back twice, smaller, from the right and
then the left, which is where the empty zeros are. 120 to the minute, F major, the three
notes under "Take a gander at the zeros." are the three under the wordmark, where each one
brings in a line of the end card: the name, the line under it, the address. The narrator says
that line over the last chord, which is held under her and comes back up when she has done.
"""
import importlib.util, json, pathlib
import numpy as np
from scipy.io import wavfile
from scipy.ndimage import uniform_filter1d

HERE = pathlib.Path(__file__).resolve().parent.parent
OUT = HERE / "out"
spec = importlib.util.spec_from_file_location("band", HERE.parent / "brand-film" / "audio" / "score.py")
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
SR, N, T, hz, rng = b.SR, b.N, b.T, b.hz, b.rng
MARGIN = 11.0                                    # dB the voice is kept over everything else where it speaks over the last chord
RIFF = ["F4", "A4", "C5", "F5", "A5", "C6"]      # the 1, the four zeros and the plus
ZERO = ["A4", "C5", "D5", "F5", "A5"]            # what each zero, and then the plus, sounds like


def build(cues, duration, lines):
    every = lambda name: [c for c in cues if c["name"] == name]
    at = lambda name, i=0: every(name)[i]["t"]
    mus, mus_wet, sfx, sfx_wet = (b.Bus(duration + 3) for _ in range(4))
    h = lambda: rng.uniform(-0.004, 0.004)  # nobody plays exactly on the grid

    def bar(t0, t1, chord, level, soft=1.0):
        """From one cue to the next at 120. level: 0 harmony, 1 marimba and shaker, 2 the kit."""
        beats = max(1, int(round((t1 - t0) / 0.5)))
        root, hold, arp = b.CH[chord]
        held = b.pad(hold, beats * 0.5, attack=0.25)
        mus.add(held, t0, 0.105 * soft)
        mus_wet.add(held, t0, 0.05 * soft)
        mus.add(b.bass(hz(root), 0.6), t0, 0.21 * soft)
        if level >= 1:
            for i, (k, v) in enumerate(zip([0, 2, 1, 2, 3, 2, 1, 2], [1, .55, .75, .55, .9, .55, .75, .5])):
                if i < beats * 2:
                    m = b.mallet(hz(arp[k]))
                    mus.add(m, t0 + i * 0.25 + h(), 0.17 * v * soft, -0.25 + 0.12 * k)
                    mus_wet.add(m, t0 + i * 0.25, 0.05 * v * soft)
                    mus.add(b.shaker(1.0 if i % 2 == 0 else 0.55), t0 + i * 0.25 + h(), 0.11 * soft, 0.45)
            for n in hold[1:]:
                mus.add(b.keys(hz(n) * 2, 0.8), t0 + h(), 0.05 * soft, rng.uniform(-0.3, 0.3))
        if level >= 2:
            for beat in range(beats):
                if beat % 2 == 0:
                    mus.add(b.kick(), t0 + beat * 0.5, 0.34 * soft)
                else:
                    s = b.snap()
                    mus.add(s, t0 + beat * 0.5 + h(), 0.26 * soft, 0.15)
                    mus_wet.add(s, t0 + beat * 0.5, 0.06 * soft)
            for beat in (1.5, 2.5):
                if beat < beats:
                    mus.add(b.bass(hz(root), 0.2), t0 + beat * 0.5, 0.17 * soft)

    def ding(note, t, gain=0.12, pan=0.0, wet=0.08):
        mus.add(b.bell(hz(note)), t, gain, pan)
        mus_wet.add(b.bell(hz(note)), t, wet, pan)

    def tune(notes, t0, step, gain=0.2, pan=0.0):
        for i, n in enumerate(notes):
            mus.add(b.mallet(hz(n), bright=1.2), t0 + i * step, gain, pan)
            mus_wet.add(b.mallet(hz(n)), t0 + i * step, gain * 0.35, pan)

    def riff(times, gain):
        """The number, as notes: one for each piece of it, left to right."""
        for i, t in enumerate(times):
            pan = -0.5 + i * 0.2
            sfx.add(b.bloop(hz(RIFF[i]), 0.2, 1.4, 0.05), t, gain * 0.8, pan)
            mus.add(b.mallet(hz(RIFF[i]), bright=1.3), t, gain, pan)
            mus_wet.add(b.mallet(hz(RIFF[i])), t, gain * 0.4, pan)

    # ---- the sun comes up on the number ------------------------------------------------------
    rise = every("sunrise")[0]
    swing, travel, inhale, honk = at("swing"), every("travel"), at("inhale"), at("honk")
    mus.add(b.pad(["F2", "C3", "F3", "A3", "C4", "F4"], swing - rise["t"], attack=rise["dur"]), rise["t"], 0.2)
    mus_wet.add(b.pad(["F3", "A3", "C4", "F4"], swing - rise["t"], attack=rise["dur"]), rise["t"], 0.1)
    sfx.add(b.whoosh(rise["dur"] + 0.2, 300, 4200, 0, 0, 0.8, 2.0), rise["t"], 0.2)
    morning = b.birds(5.0) * np.minimum(1, T(5.0) / 0.8) * np.minimum(1, (5.0 - T(5.0)) / 1.5)
    sfx.add(morning, rise["t"] + 0.3, 0.05, 0.3)
    riff([c["t"] for c in every("land")], 0.2)
    mus.add(b.bass(hz("F2"), 0.8), every("land")[0]["t"], 0.3)
    ding("A6", at("twinkle"), 0.07, 0.5)                      # the plus turns over
    for c, n in zip(every("hop"), ["A5", "C6", "D6", "F6"]):  # and the zeros hop, one after another
        mus.add(b.mallet(hz(n), bright=1.2), c["t"], 0.11, -0.3 + 0.2 * c["i"])
        sfx.add(b.bloop(hz(n), 0.1, 1.2, 0.03), c["t"], 0.1, -0.3 + 0.2 * c["i"])
    sfx.add(b.rim(), at("blink"), 0.1, -0.5)
    sfx.add(b.flick(), at("turn"), 0.2, -0.5)
    for i, n in enumerate(["F5", "A5"]):  # two beats of the scale under the turn, so the bar is not empty
        mus.add(b.mallet(hz(n)), at("turn") + i * 0.25, 0.12, -0.4)

    # ---- the view swings round, and the goose leans in -------------------------------------------
    sw = every("swing")[0]
    sfx.add(b.whoosh(sw["dur"], 500, 1900, -0.6, 0.6, 0.5), sw["t"], 0.24)
    bar(swing, travel[0]["t"], "Bb", 1)
    tune(["F5", "A5", "C6"], at("sub"), 0.25, 0.15, 0.2)  # the three notes that end the film
    sfx.add(b.whoosh(0.4, 1400, 350, -0.3, -0.7, 0.3), at("leave"), 0.22)

    # ---- the zeros, one at a time ----------------------------------------------------------------
    chords = ["F", "Dm7", "Csus", "F", "Bb"]
    stops = [travel[1]["t"], travel[2]["t"], inhale, travel[4]["t"], at("pullback")]
    for c, chord, stop in zip(travel, chords, stops):
        bar(c["t"], stop, chord, 2)
        sfx.add(b.whoosh(c["dur"] + 0.1, 380, 2300, -0.5 if c["i"] else 0, 0.5 if c["i"] else 0, 0.6), c["t"], 0.24)
    for c in every("pop"):
        note = hz(ZERO[c["i"]])
        sfx.add(b.bloop(note, 0.24, 1.8, 0.07), c["t"], 0.34, 0.1)
        sfx_wet.add(b.bloop(note, 0.24, 1.8, 0.07), c["t"], 0.1, 0.1)
        sfx.add(b.thud(120), c["t"], 0.3)
        mus.add(b.mallet(note * 2, bright=1.3), c["t"], 0.16, 0.2)
    for c in every("label"):
        sfx.add(b.keyclick(), c["t"], 0.16, -0.4)
    ding("C6", at("label", 3), 0.13, -0.2)   # free
    ding("E6", at("label", 3) + 0.12, 0.09, 0.2)
    ding("D6", at("label", 4), 0.12, 0.0)    # and something more
    for c in every("duck"):
        sfx.add(b.bloop(hz(ZERO[c["i"]]), 0.2, 0.55, 0.06), c["t"] + 0.05, 0.2, 0.1)
        sfx.add(b.flick(), c["t"], 0.12, 0.1)

    # ---- the third zero: a breath, a honk, and the honk again from each side ----------------------
    sfx.add(b.whoosh(honk - inhale - 0.08, 500, 3800, -0.1, -0.1, 0.92, 1.0), inhale, 0.4)
    sfx.add(b.honk(), honk, 0.66, -0.05)
    sfx_wet.add(b.honk(), honk, 0.22, -0.05)
    mus.add(b.kick(), honk, 0.6)
    mus.add(b.bass(hz("F2"), 0.9), honk, 0.34)
    for i in range(8):
        sfx.add(b.flick(), honk + 0.06 + i * 0.045 + rng.uniform(0, 0.02), 0.13 * (1 - i / 10), rng.uniform(0.1, 0.8))
    for c, pan, gain in zip(every("echo"), (0.8, -0.8), (0.3, 0.15)):
        far = b.bp(b.honk(0.97), 500, 2400)   # further off: thinner, quieter, and mostly room
        sfx.add(far, c["t"], gain * 0.5, pan)
        sfx_wet.add(far, c["t"], gain * 0.45, pan)
    sfx.add(b.whoosh(0.3, 1500, 400, 0, 0, 0.3), at("hush"), 0.12)

    # ---- the plus ---------------------------------------------------------------------------------
    peck = at("peck")
    sfx.add(b.click(3200, 0.004), peck, 0.4, 0.3)
    sfx.add(b.bloop(hz("D6"), 0.16, 1.3, 0.04), peck, 0.26, 0.3)
    spin = b.sweep(rng.standard_normal(N(0.55)), 900, 3200, 1.2) * np.hanning(N(0.55)) * (0.6 + 0.4 * np.sin(2 * np.pi * 14 * T(0.55)))
    sfx.add(spin, peck + 0.03, 0.2, 0.3)
    ding("A6", peck + 0.45, 0.08, 0.3)

    # ---- back out to the whole number, and the icon --------------------------------------------------
    back, number, end = every("pullback")[0], at("number"), at("end")
    bar(back["t"], number, "C", 1)
    sfx.add(b.whoosh(back["dur"], 2400, 320, 0.3, -0.3, 0.35), back["t"], 0.24)
    sfx.add(b.riser(back["dur"]), back["t"], 0.07)
    sfx.add(b.bloop(hz("F4"), 0.28, 0.55, 0.09), at("return"), 0.24, -0.6)
    bar(number, end, "F", 2)
    mus.add(b.pad(["F2", "C3", "F3", "A3", "C4", "F4"], end - number, attack=0.04), number, 0.2)
    riff([number + i * 0.07 for i in range(6)], 0.2)
    ding("F6", number + 0.42, 0.1)
    sfx.add(b.birds(5.0, seed=5) * np.minimum(1, T(5.0) / 1.0) * np.minimum(1, (5.0 - T(5.0)) / 1.5), number, 0.045, -0.3)

    word = at("wordmark")
    sfx.add(b.whoosh(0.6, 400, 2600, -0.4, 0.2, 0.6), end, 0.2)
    bar(end, end + 1.0, "Bb", 2)
    bar(end + 1.0, word, "C", 2)
    sfx.add(b.bloop(hz("F4"), 0.25, 0.55, 0.08), at("tile"), 0.3)
    for c in every("fan"):
        sfx.add(b.flick(), c["t"], 0.22, -0.3 + 0.3 * c["i"])
    for i, n in enumerate(["F3", "A3", "C4"]):
        mus.add(b.mallet(hz(n) * 2), at("gooseUp") + i * 0.2, 0.13)
    tune(["F5", "A5", "C6"], word, 0.25, 0.26)  # the three notes from the start
    ding("F6", word + 0.5, 0.12)
    sfx.add(b.keyclick(), at("tagline"), 0.1, -0.2)  # these two fall on the second and third notes
    sfx.add(b.keyclick(), at("url"), 0.12, 0.2)
    # The last chord. Where the narrator speaks over it, it is held until she has finished and
    # everything has had time to step forward again, so that it is still there to ring out.
    # It is kept apart from the rest of the music, because under a voice it steps back further.
    fin = word + 1.0
    spoken = [ln["at"] + ln["dur"] for ln in lines if ln["at"] + ln["dur"] > fin]
    hold = max(1.3, max(spoken) - fin + 0.35) if spoken else 1.3
    last, last_wet = b.Bus(duration + 3), b.Bus(duration + 3)
    last.add(b.pad(["F2", "C3", "F3", "A3", "C4", "F4"], hold, attack=0.03, release=0.9), fin, 0.28)
    last_wet.add(b.pad(b.CH["F"][1], hold - 0.3, attack=0.03), fin, 0.14)
    last.add(b.kick(), fin, 0.5)
    last.add(b.bass(hz("F2"), 1.2), fin, 0.34)
    last.add(b.bell(hz("C6")), fin, 0.12)
    last_wet.add(b.bell(hz("C6")), fin, 0.08)
    tune(["F6"], at("nod") + 0.2, 0.25, 0.13)

    hall, room = b.impulse(2.1, 0.02, 5200, 11), b.impulse(0.7, 0.008, 6500, 12)
    eq = lambda x: x - 0.3 * b.lp(x, 120) + 0.9 * b.hp(x, 3500)
    music = eq(mus.a + b.reverb(mus_wet.a, hall) * 0.9)
    chord = eq(last.a + b.reverb(last_wet.a, hall) * 0.9)
    # Before the honk everything stops, and that includes whatever the last bar left ringing.
    held = np.ones(len(music))
    held[N(inhale):N(honk)] = np.clip(1 - np.arange(N(honk) - N(inhale)) / N(0.12), 0, 1)
    music = music * held[:, None]
    sound = sfx.a + b.reverb(sfx_wet.a, room) * 0.8

    # ---- the narration, as the brand film has it ------------------------------------------------------
    vo = b.Bus(duration + 3)
    for ln in lines:
        rate, y = wavfile.read(OUT / "voice" / f"{ln['id']}.wav")
        y = y.astype(np.float64) / (2 ** 31 if y.dtype == np.int32 else 2 ** 15 if y.dtype == np.int16 else 1)
        y = b.hp(b.signal.resample_poly(y, SR, rate), 85)
        y = y / (np.sqrt(np.mean(y ** 2)) + 1e-9) * 0.2
        y = y + 0.22 * b.bp(y, 2500, 6000)
        vo.add(y, ln["at"])
    voice = vo.a + b.reverb(vo.a, room) * 0.06
    return music, chord, sound, voice, fin


def level(a, where):
    return 20 * np.log10(np.sqrt(np.mean(a[where] ** 2)) + 1e-12)


def mix(music, chord, sound, voice, fin):
    """Everything that is not the voice, stepped back while she speaks.

    The music gives way by 12 dB and the effects by 6, as in the brand film. The last chord is
    the loudest thing in the score and she speaks right over it, so it gives way by as much
    more as it takes to leave her MARGIN dB clear of everything else while she does.
    """
    talking = uniform_filter1d(np.abs(voice).max(1), N(0.05)) > 0.004
    over = talking & (np.arange(len(voice)) / SR >= fin)
    rest = music * b.duck(voice, 12.0) + sound * b.duck(voice, 6.0)
    for more in np.arange(0, 12.5, 0.5):
        bed = rest + chord * b.duck(voice, 12.0 + more)
        if not over.any() or level(voice, over) - level(bed, over) >= MARGIN:
            break
    return bed, talking, over, float(more)


def main():
    film = json.loads((OUT / "cues.json").read_text())
    lines = json.loads((OUT / "voice" / "lines.json").read_text())["lines"]
    music, chord, sound, voice, fin = build(film["cues"], film["duration"], lines)

    bed, talking, over, more = mix(music, chord, sound, voice, fin)
    report = {
        "with voice": b.master(bed + voice, OUT / "soundtrack.wav", film["duration"]),
        "no voice": b.master(music + chord + sound, OUT / "soundtrack-no-voice.wav", film["duration"]),
    }
    for name, stem in (("music", music + chord), ("sfx", sound), ("voice", voice)):
        wavfile.write(OUT / f"stem-{name}.wav", SR, stem[: N(film["duration"])].astype(np.float32))
    report["voice over bed, dB"] = round(level(voice, talking) - level(bed, talking), 1)
    if over.any():
        report["voice over the last chord, dB"] = round(level(voice, over) - level(bed, over), 1)
        report["the last chord gives way by a further, dB"] = more
    (OUT / "soundtrack-report.json").write_text(json.dumps(report, indent=1) + "\n")
    for k, v in report.items():
        print(f"{k}: {v}")


if __name__ == "__main__":
    main()
