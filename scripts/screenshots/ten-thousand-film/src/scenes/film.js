// 0:00 to 0:17.5. The number face on at sunrise, with a goose for its 1. The view swings
// round to one end and the goose leans in to the first zero. Then each zero in turn, close:
// it puts its head through and there is nothing in it. At the third it honks to make sure,
// and only the honk comes back. Then the plus, and back out to the whole number.
(function () {
  const F = window.F, C = F.C, E = F.E;

  // ---- when things happen. The film runs at 120 to the minute and its scenes change on
  // the beat, so the score can be written to it. Each line of words comes up a little before
  // the goose does, which gives it longer on screen for anybody watching with the sound off.
  const T = {
    twinkle: 0.85, blink: 1.15, turn: 1.5, hop: 1.62, swing: 2.0, sub: 2.1, leave: 3.8,
    in: [4.0, 6.0, 8.0, 11.5], pop: [4.72, 6.62, 8.52, 11.97], label: [4.62, 6.52, 8.5, 11.9], duck: [5.8, 7.75, 11.4, 12.9],
    inhale: 9.5, honk: 10.0, echo: [10.5, 11.0], hush: 11.2,
    plusIn: 13.0, plusPop: 13.45, peck: 13.8, plusLabel: 13.3, plusDuck: 14.9,
    out: 15.0, back: 15.6, number: 16.0, end: 17.5,
  };
  F.T10K = T;

  // ---- views ---------------------------------------------------------------------------
  // Close up, the tall frame has the room to draw a zero larger, so it does, and the frame
  // cuts off more of the zeros on either side. k is how much larger.
  const AT = F.TALL ? 520 : 500, EDGE = F.TALL ? 985 : 905, CLOSE = F.TALL ? 17.6 : 14.8, k = CLOSE / 14.8, MID = EDGE - 24.8 * CLOSE;
  // Seen whole, the number stands lower in the tall frame, which has more sky to put over it,
  // and the sun behind it is bigger. V is how much lower, and UNDER is the foot of the frame.
  const V = F.TALL ? 120 : 0, HY = 830 + V, UNDER = F.H - F.DROP;
  const FLAT = { z: [307.9, 516.9, 692.9, 868.9].map((x) => [5.5, x, 940 + V]), comma: [5.5, 410.2, 926 + V], plus: [5.5, 994.7, 754 + V], hy: HY, sun: [628, 850 + V, F.TALL ? 455 : 340], band: UNDER + 70, far: 0, shade: 0.12 };
  const DEPTH = { z: [[7.6, 341.5, 1060], [6.6, 595, 1029.7], [5.7, 790, 1002.5], [5.0, 960.5, 981.3]].map(([u, x, y]) => [u, x, y + V]), comma: [7.2, 481, 1046 + V], plus: [4.6, 1034, 742 + V], hy: HY, sun: [780, 850 + V, F.TALL ? 430 : 330], band: UNDER + 70, far: -70, shade: 0.12 };
  // Close on whatever is rc units along the row. The sun is too far off to move as we do.
  const close = (rc) => ({
    z: F.ROW.zeros.map((r) => [CLOSE, AT + (r - rc) * CLOSE, EDGE]), comma: [CLOSE, AT + (F.ROW.comma - rc) * CLOSE, EDGE - 6.4 * CLOSE],
    plus: [CLOSE, AT + (F.ROW.plus - rc) * CLOSE, EDGE - 33.8 * CLOSE], hy: EDGE - 26, sun: [AT + 30 * k, MID - 88 * k, 352 * k], band: EDGE, far: -150 - rc * 1.5, shade: 0,
  });
  const Z = F.ROW.zeros.map(close), ZP = close(139);
  const LAST = Object.assign({}, FLAT, { sun: [628, 700 + V, FLAT.sun[2]] }); // by the end the sun is up
  const VIEWS = [
    [0, FLAT], [T.swing, FLAT], [3.0, DEPTH], [T.in[0], DEPTH], [4.7, Z[0]], [T.in[1], Z[0]], [6.6, Z[1]], [T.in[2], Z[1]], [8.5, Z[2]],
    [T.in[3], Z[2]], [T.in[3] + 0.45, Z[3]], [T.plusIn, Z[3]], [T.plusIn + 0.45, ZP], [T.out, ZP], [T.number, LAST], [T.end, LAST],
  ];
  const mix = (a, b, p) => {
    const l = (x, y) => F.lerp(x, y, p), v3 = (x, y) => x.map((q, i) => l(q, y[i]));
    return { z: a.z.map((q, i) => v3(q, b.z[i])), comma: v3(a.comma, b.comma), plus: v3(a.plus, b.plus), hy: l(a.hy, b.hy), sun: v3(a.sun, b.sun), band: l(a.band, b.band), far: l(a.far, b.far), shade: l(a.shade, b.shade) };
  };
  const viewAt = (t) => {
    for (let i = 1; i < VIEWS.length; i++) if (t <= VIEWS[i][0]) return mix(VIEWS[i - 1][1], VIEWS[i][1], E.io(F.prog(t, VIEWS[i - 1][0], VIEWS[i][0])));
    return VIEWS[VIEWS.length - 1][1];
  };

  // ---- the goose -----------------------------------------------------------------------
  const ONE = { bx: 175, by: 1000 + V, ax: 175, ay: 1000 + V - 34.5 * 8.2, tilt: 3, flip: 0 };
  // Leaning in, it is nearer to us than the number is, and its neck comes up from under the frame.
  const LEANING = { bx: 64, by: UNDER + 90, ax: 150, ay: 950 + V, tilt: -4, flip: 1, brow: 0.3 };
  const GONE = { bx: -60, by: UNDER + 330, ax: -50, ay: UNDER + 250, tilt: -20, flip: 1 };
  const m = (p, o) => Object.assign({}, p, o);
  // Through a zero from behind, facing right or left, and down out of sight below it.
  const thru = (right) => ({ bx: AT + (right ? -6 : 6) * k, by: EDGE + 80 * k, ax: AT + (right ? -38 : 38) * k, ay: MID + 36 * k, tilt: 7, flip: right ? 1 : 0, bend: -10 * k });
  const down = (right) => ({ bx: AT + (right ? -6 : 6) * k, by: EDGE + 470 * k, ax: AT + (right ? -20 : 20) * k, ay: EDGE + 300 * k, flip: right ? 1 : 0 });
  const INHALE = { bx: AT + 2 * k, by: EDGE + 86 * k, ax: AT - 64 * k, ay: MID + 26 * k, tilt: 13, flip: 1, bend: -24 * k, brow: 0.55 };
  const HONK = { bx: AT + 4 * k, by: EDGE + 90 * k, ax: AT - 50 * k, ay: MID + 66 * k, tilt: 19, flip: 1, bend: -14 * k, open: 1, brow: 0.9 };
  // Beside the plus, which is 8.6 units past where the last view is centred.
  const PX = AT + 8.6 * CLOSE, PY = EDGE - 33.8 * CLOSE;
  const PECK = { bx: PX + 17.3 * CLOSE, by: EDGE + 80 * k, ax: PX + 18.8 * CLOSE, ay: PY + 7.9 * CLOSE, tilt: 2, flip: 0 }, PDOWN = { bx: PX + 17.3 * CLOSE, by: EDGE + 470 * k, ax: PX + 18.4 * CLOSE, ay: EDGE + 300 * k, flip: 0 };
  const spring = E.spring(0.55, 10);
  const look = (i, right, a, b) => [
    [T.pop[i] - 0.17, down(right)], [T.pop[i] + 0.23, thru(right), spring], [a, m(thru(right), { tilt: -5 }), E.soft], [b, m(thru(right), { tilt: 9 }), E.soft],
    [T.duck[i], thru(right), E.soft], [T.duck[i] + 0.25, down(right), E.in],
  ];
  const POSE = [
    [0, m(ONE, { ay: ONE.ay + 46, tilt: -3 })], [0.95, ONE, E.spring(0.5, 11)], [1.12, m(ONE, { tilt: 8 }), E.soft], [T.turn, ONE, E.soft], [T.turn + 0.35, m(ONE, { flip: 1, tilt: 0 })], [T.swing, m(ONE, { flip: 1, tilt: 0 })],
    [3.0, LEANING], [3.35, m(LEANING, { ay: LEANING.ay + 14, tilt: -9 }), E.soft], [3.7, LEANING, E.soft], [T.leave, LEANING], [4.3, GONE, E.in],
    ...look(0, true, 5.2, 5.5), ...look(1, false, 7.1, 7.42),
    [T.pop[2] - 0.17, down(true)], [T.pop[2] + 0.23, thru(true), spring], [9.0, m(thru(true), { tilt: -5 }), E.soft], [9.3, m(thru(true), { tilt: 8 }), E.soft], [T.inhale, thru(true), E.soft], [T.honk - 0.1, INHALE, E.soft], [T.honk, INHALE], [T.honk + 0.09, HONK, E.back(2.2)],
    [T.hush - 0.1, HONK], [T.hush + 0.12, m(thru(true), { brow: 0 }), E.soft], [T.duck[2], thru(true)], [T.duck[2] + 0.25, down(true), E.in],
    [T.pop[3] - 0.17, down(false)], [T.pop[3] + 0.23, thru(false), spring], [T.pop[3] + 0.55, m(thru(false), { tilt: 12 }), E.soft], [T.duck[3], thru(false), E.soft], [T.duck[3] + 0.25, down(false), E.in],
    [T.plusPop - 0.15, PDOWN], [T.plusPop + 0.22, PECK, spring], [T.peck, PECK], [T.peck + 0.08, m(PECK, { ax: PX + 17.3 * CLOSE, ay: PY + 8.5 * CLOSE, tilt: -13 }), E.in], [T.peck + 0.26, PECK, E.out],
    [T.plusDuck, PECK], [T.plusDuck + 0.25, PDOWN, E.in],
    [T.back, m(ONE, { bx: -170, ax: -190, tilt: -8 })], [T.back + 0.62, ONE, E.spring(0.7, 8)], [T.end, ONE],
  ];
  // How big the goose is. It changes size only while it is out of sight.
  const SIZE = [[0, 8.2], [T.swing, 8.2], [3.0, 12], [4.3, 12], [4.31, CLOSE], [T.plusDuck + 0.3, CLOSE], [T.plusDuck + 0.31, 8.2]];
  const HIDDEN = [[4.3, T.pop[0] - 0.17], [T.duck[0] + 0.25, T.pop[1] - 0.17], [T.duck[1] + 0.25, T.pop[2] - 0.17], [T.duck[2] + 0.25, T.pop[3] - 0.17], [T.duck[3] + 0.25, T.plusPop - 0.15], [T.plusDuck + 0.25, T.back]];
  const BLINKS = [T.blink, 3.42, 5.62, 7.6, T.hush + 0.16, T.pop[3] + 0.75, T.plusDuck - 0.25, T.number + 0.9];
  // A knock that dies away: what a zero does when something comes through it or honks by it.
  const ring = (t, t0, amp, rate = 24, fade = 6) => (t < t0 ? 0 : amp * Math.exp(-fade * (t - t0)) * Math.sin(rate * (t - t0)));

  F.scenes.push({
    // It runs on under the end card for as long as that takes to open over it.
    id: "number", t0: 0, t1: T.end + 0.7,
    build(root) {
      F.ground(root, F.SKY);
      const W = F.world(root), type = F.g(W.world);

      // ---- the words ---------------------------------------------------------------------
      const kicker = F.kicker(type, "On Google Play · November 2026", { x: 70, y: 118, size: 32 });
      const word = F.g(type);
      F.text(word, "downloads.", { x: 0, y: 0, size: 140, weight: 600, fill: C.red, ls: "-0.025em" });
      const sub = F.text(type, "", { x: 70, y: 196, size: 46, weight: 500, fill: C.inkSoft });
      [["Take a gander at the "], ["zeros", 1], ["."]].forEach(([s, stress]) => {
        const sp = F.el("tspan", stress ? { fill: C.ink, "font-style": "italic", "font-weight": 600 } : null, sub);
        sp.textContent = s;
      });
      sub.setAttribute("xml:space", "preserve");
      // Close up, the milestone stays on as a small line over each thing the zeros say.
      const small = F.kicker(type, "10,000+ downloads on Google Play · November 2026", { x: 66, y: EDGE + 54, size: 24 });
      const block = (lines, size = 124, drop = 0) => F.headline(type, { x: 62, y: EDGE + 196 + drop, size, lh: size * 1.02, lines: lines.map(([text, red]) => ({ text, fill: red ? C.red : C.ink })) });
      const LABELS = [
        [block([["No"], ["ads.", 1]]), T.label[0], T.duck[0] + 0.1],
        [block([["No"], ["account.", 1]]), T.label[1], T.duck[1] + 0.1],
        [block([["No internet"], ["permission.", 1]]), T.label[2], T.duck[2] - 0.05],
        [block([["Free.", 1]], 150, 22), T.label[3], T.duck[3] + 0.05],
        [block([["Plus:"], ["open source.", 1]]), T.plusLabel, T.plusDuck],
      ];
      const mit = F.text(type, "MIT licensed.", { x: 66, y: EDGE + 196 + 126 + 68, size: 46, weight: 500, fill: C.inkSoft });

      // The honk, and what is left of it each time it comes back: once out of the hole of
      // the zero to the right, and fainter out of the one to the left.
      const letter = (str, x, y, size, turn) => { const g = F.g(type), tx = F.text(g, str, { size, weight: 800, fill: C.ink, anchor: "middle", ls: "0.01em", italic: true }); return { g, x, y, turn }; };
      const honk = letter("HONK!", F.TALL ? 792 : 772, EDGE - 727 * k, F.TALL ? 132 : 126, -9);
      const echoes = F.TALL ? [letter("honk", 1012, EDGE - 548, 44, -7), letter("honk", 50, EDGE - 520, 30, 6)] : [letter("honk", 976, EDGE - 533, 58, -7), letter("honk", 74, EDGE - 485, 36, 6)];
      const lines = F.g(type, { stroke: C.ink, "stroke-width": 7, "stroke-linecap": "round" });
      const beak = W.beak(HONK, CLOSE);
      const rays = [[-34, 92], [-12, 120], [12, 104], [36, 126], [58, 88]].map(([deg, len]) => ({ n: F.el("line", null, lines), a: ((deg - 19) * Math.PI) / 180, len }));

      // ---- cues, for the soundtrack ------------------------------------------------------
      F.cue(0, "sunrise", { dur: 1.3 });
      [0, 1, 2, 3, 4, 5].forEach((i) => F.cue(0.12 + i * 0.07, "land", { i }));
      F.cue(T.twinkle, "twinkle"); [0, 1, 2, 3].forEach((i) => F.cue(T.hop + i * 0.085, "hop", { i }));
      F.cue(T.blink, "blink"); F.cue(T.turn, "turn"); F.cue(T.swing, "swing", { dur: 1.0 }); F.cue(T.sub, "sub"); F.cue(T.leave, "leave");
      T.in.forEach((t, i) => { F.cue(t, "travel", { i, dur: i ? 0.5 : 0.7 }); F.cue(T.pop[i], "pop", { i }); F.cue(T.label[i], "label", { i }); F.cue(T.duck[i], "duck", { i }); });
      F.cue(T.inhale, "inhale", { dur: T.honk - T.inhale }); F.cue(T.honk, "honk"); T.echo.forEach((t, i) => F.cue(t, "echo", { i })); F.cue(T.hush, "hush");
      F.cue(T.plusIn, "travel", { i: 4, dur: 0.45 }); F.cue(T.plusPop, "pop", { i: 4 }); F.cue(T.plusLabel, "label", { i: 4 }); F.cue(T.peck + 0.08, "peck"); F.cue(T.plusDuck, "duck", { i: 4 });
      F.cue(T.out, "pullback", { dur: T.number - T.out }); F.cue(T.back, "return"); F.cue(T.number, "number");

      return (lt, t) => {
        const v = viewAt(t);
        v.sun[1] += (1 - E.soft(F.prog(t, 0, T.swing))) * 150; // the sun comes up as the film starts
        // Birds cross the sky at the start, and again when the whole number comes back.
        const pass = t < 8 ? t - 0.1 : t - (T.number - 0.5), wing = (i) => [-70 + pass * 300 - [0, 46, 38][i], 352 + [0, 26, -30][i] - 26 * Math.sin(pass * 0.9 + i), t * 13 + i * 1.3, [1, 0.85, 0.92][i]];
        const i3 = [0, 1, 2, 3];
        const pose = F.poseKeys(t, POSE);
        pose.blink = Math.max(pose.blink || 0, F.blinks(t, BLINKS));
        if (t > T.honk && t < T.hush) pose.tilt += Math.sin((t - T.honk) * 70) * 1.4 * Math.exp(-3 * (t - T.honk));
        pose.alpha = HIDDEN.some(([a, b]) => t >= a && t < b) ? 0 : 1;
        const through = [0, 1, 2, 3].find((i) => t >= T.pop[i] - 0.17 && t < T.duck[i] + 0.25);
        W.draw(v, pose, F.keys(t, SIZE), {
          through, foot: 1000 + V, drift: t * 7,
          // The goose throws a shadow only while it stands in the row as the 1.
          ghost: t < T.out ? 1 - F.prog(t, T.swing, T.swing + 0.3) : F.prog(t, T.back + 0.1, T.back + 0.45), birds: pass > 0 && pass < 4.4 ? [0, 1, 2].map(wing) : null,
          // Each zero lands at the start, rocks when the goose comes through it, and the two
          // on either side of the honk rock with that.
          // and hops, one after another, when the goose turns to look at them.
          lift: i3.map((i) => -(1 - E.spring(0.42, 13)(F.prog(t, 0.05 + i * 0.07, 0.85 + i * 0.07))) * 54 - Math.sin(Math.PI * F.prog(t, T.hop + i * 0.085, T.hop + i * 0.085 + 0.3)) * 17),
          rock: i3.map((i) => ring(t, T.pop[i] + 0.02, 3.2) + (i === 1 || i === 3 ? ring(t, T.honk + 0.05, i === 1 ? -3 : 3, 30, 4) : 0) + (i === 2 ? ring(t, T.honk + 0.03, 1.6, 40, 5) : 0)),
          turn: (E.spring(0.5, 9)(F.prog(t, T.twinkle, T.twinkle + 0.8)) + E.spring(0.5, 9)(F.prog(t, T.peck + 0.08, T.peck + 0.9))) * 90,
        });

        // The honk shakes the whole picture, once, and less each time round.
        W.world.setAttribute("transform", `translate(${ring(t, T.honk, 9, 58, 7).toFixed(2)} ${(F.DROP + ring(t, T.honk + 0.01, 6, 47, 7)).toFixed(2)})`);

        // The words.
        const wide = 1 - F.tw(t, T.in[0] - 0.12, 0.25, E.in) + F.tw(t, T.number - 0.25, 0.4, E.out);
        F.op(kicker, wide);
        const dp = E.io(F.prog(t, T.swing, 3.0)) * (t < T.out ? 1 : 0), wx = F.lerp(62, 560, dp), wy = F.lerp(1152 + V + (F.TALL ? 34 : 0), 1250 + V, dp), ws = F.lerp(F.TALL ? 1.186 : 1, 0.714, dp);
        const rise = t < T.out ? 0 : (1 - F.tw(t, T.number - 0.2, 0.5, E.out)) * 40;
        word.setAttribute("transform", `translate(${wx.toFixed(2)} ${(wy + F.tw(t, T.in[0] - 0.1, 0.3, E.in) * (t < T.out ? 60 : 0) + rise).toFixed(2)}) scale(${ws.toFixed(4)})`);
        F.op(word, t < T.out ? 1 - F.tw(t, T.in[0] - 0.1, 0.25, E.in) : F.tw(t, T.number - 0.2, 0.4, E.out));
        const sa = F.tw(t, T.sub, 0.45, E.out), sb = F.tw(t, T.leave + 0.12, 0.25, E.in);
        sub.setAttribute("transform", `translate(0 ${((1 - sa) * 22).toFixed(2)})`);
        F.op(sub, sa * (1 - sb));
        F.op(small, F.tw(t, T.label[0] - 0.1, 0.3, E.out) * (1 - F.tw(t, T.plusDuck, 0.25, E.in)));
        LABELS.forEach(([b, a, z]) => { b.update(F.prog(t, a, a + 0.45), F.prog(t, z, z + 0.28)); F.show(b.root, t > a - 0.05 && t < z + 0.4); });
        F.op(mit, F.tw(t, T.plusLabel + 0.2, 0.3, E.out) * (1 - F.tw(t, T.plusDuck, 0.25, E.in)));

        // The honk.
        const hp = E.spring(0.5, 12)(F.prog(t, T.honk + 0.02, T.honk + 0.5)), hz = 1 - F.tw(t, T.hush + 0.02, 0.22, E.in);
        F.T(honk.g, honk.x, honk.y, honk.turn + ring(t, T.honk, 3.5, 44, 5), 0.5 + 0.5 * hp);
        F.op(honk.g, (t >= T.honk ? 1 : 0) * hz);
        echoes.forEach((e, i) => {
          const a = F.tw(t, T.echo[i], 0.16, E.out);
          F.T(e.g, e.x, e.y, e.turn, 0.8 + 0.2 * a);
          F.op(e.g, a * [0.55, 0.32][i] * (1 - F.tw(t, T.hush + 0.1 + i * 0.12, 0.25, E.in)));
        });
        const lp = F.tw(t, T.honk + 0.02, 0.22, E.out), lz = 1 - F.tw(t, T.honk + 0.5, 0.35, E.in);
        F.op(lines, (t >= T.honk ? 1 : 0) * lz);
        rays.forEach(({ n, a, len }) => {
          const r0 = (46 + 26 * (1 - lz)) * k, r1 = r0 + len * lp * k;
          F.set(n, { x1: beak[0] + Math.cos(a) * r0, y1: beak[1] + Math.sin(a) * r0, x2: beak[0] + Math.cos(a) * r1, y2: beak[1] + Math.sin(a) * r1 });
        });
      };
    },
  });
})();
