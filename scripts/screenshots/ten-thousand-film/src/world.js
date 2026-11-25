// The world the film is set in, and the number standing in it: a pink sky, the sun coming up,
// far hills, open ground, and on the ground 10,000+ as cut paper. A goose goes where the 1
// goes, the four zeros are rings with holes it can put its head through, and there is a comma
// and a plus. A scene hands this a view (how big each piece is and where it stands) and a
// pose for the goose, and everything is drawn from those, so one set serves the number seen
// face on, seen from one end, and seen from as close as a single zero.
(function () {
  const F = window.F, C = F.C;
  const U0 = 10;                              // the unit the pieces are drawn at; a view scales them
  const S = 6.4 * U0, RX = 14.7 * U0, RY = 24.8 * U0; // a zero: how thick, and its outer radii
  const LEAN = [-1.2, 1.4, -0.8, 1.2];        // nothing stood on the ground by hand is quite upright
  F.SKY = F.tint(C.fmt.PDF, 0.26);
  // Where the pieces come along the row, in goose units from the 1: the zeros, comma and plus.
  F.ROW = { zeros: [21.3, 59.3, 91.3, 123.3], comma: 39.9, plus: 147.6 };

  F.world = (root) => {
    const world = F.stage(F.g(root));
    const edge = { fill: C.paper, stroke: C.ink, "vector-effect": "non-scaling-stroke" };
    const cut = (parent, d) => { const p = F.paper(parent, d, { fill: C.paper, stroke: C.ink, sw: 5.5 }); p.setAttribute("vector-effect", "non-scaling-stroke"); return p; };
    const zeroD = F.zeroD(RX, RY, S);

    // ---- sky and ground ------------------------------------------------------------------
    const sun = F.wob(F.el("path", { d: "M100,0 A100,100 0 1 1 -100,0 A100,100 0 1 1 100,0 Z", fill: C.coral }, world), { amp: 0.9, wl: 50, step: 2.4 });
    // The last cloud is for the tall frame, which has more sky to fill, and it is only there
    // while the whole number is in view: close up it would sit behind the goose's head.
    const clouds = [[250, 268, 120, 0.42, 3], [872, 176, 84, 0.4, 8], [1420, 240, 104, 0.4, 12], [-380, 150, 90, 0.42, 21], [742, 518, 66, 0.4, 12, 1]].map(([x, y, r, flat, seed, far]) => {
      const g = F.g(world), d = F.blobPath(F.blobRadii(seed, 9, 0.22), r, 0, 0, 1.9, flat);
      F.el("path", { d, fill: F.mixHex(F.SKY, C.fmt.PDF, 0.16), transform: "translate(8 10)" }, g);
      F.el("path", { d, fill: C.cream }, g);
      if (far && !F.TALL) F.show(g, false);
      return { g, x, y, far };
    });
    // A few birds, far off, each two strokes that flap.
    const flock = F.g(world, { fill: "none", stroke: C.ink, "stroke-width": 4, "stroke-linecap": "round", "stroke-linejoin": "round", opacity: 0.7 });
    const birds = [0, 1, 2].map(() => F.el("path", null, flock));
    const hills = [[-420, 38, 310, 1.6, 0.27, 14], [200, 34, 330, 1.7, 0.3, 5], [900, 40, 300, 1.5, 0.24, 9], [1720, 36, 320, 1.6, 0.26, 17]].map(([x, y, r, sx, sy, seed]) =>
      ({ n: F.el("path", { d: F.blobPath(F.blobRadii(seed, 9, 0.16), r, 0, 0, sx, sy), fill: F.mixHex(F.SKY, C.fmt.PDF, 0.3) }, world), x, y }));
    const ground = F.el("rect", { x: -20, width: F.W + 40, fill: C.creamDeep }, world);

    // ---- shadows on the ground, thrown towards us by the low sun ---------------------------
    const shade = F.g(world);
    const zShade = F.ROW.zeros.map(() => { const g = F.g(shade); F.paper(F.T(F.g(g), 0, -RY), zeroD, { fill: C.ink }); return g; });
    const ghostFall = F.g(shade), ghostSize = F.g(ghostFall), ghost = F.goose(ghostSize, { u: U0, fill: C.ink });
    ghost.root.querySelectorAll("ellipse, path").forEach((n) => n.setAttribute("fill", C.ink));

    // ---- the plus and the zeros, the furthest first ----------------------------------------
    const plus = F.g(world), plusTurn = F.g(plus), plusPath = cut(plusTurn, F.plusD(13 * U0, S * 0.8));
    const zeros = F.ROW.zeros.map(() => null);
    [3, 2, 1, 0].forEach((i) => { const g = F.g(world), tilt = F.g(g); zeros[i] = { g, tilt, p: cut(F.T(F.g(tilt), 0, -RY), zeroD) }; });

    // ---- the goose. When it has its head through a zero, the lower part of that zero is
    // drawn once more over its neck, so the neck goes behind the rim and the head comes out.
    // Before the goose, the shadow it throws on that rim: its own shape again, moved down
    // and to the right, and kept to where the zero is.
    const rims = zeros.map((z) => {
      z.p.id = F.id("rim");
      z.p.setAttribute("clip-rule", "evenodd");
      const id = F.id("onrim");
      return { id, use: F.el("use", { href: "#" + z.p.id }, F.el("clipPath", { id }, world)) };
    });
    const rimShade = F.g(world, { opacity: 0.16 }), rimSize = F.g(F.g(rimShade, { transform: "translate(10 12)" })), rimGoose = F.goose(rimSize, { u: U0, fill: C.ink });
    rimGoose.root.querySelectorAll("ellipse, path").forEach((n) => n.setAttribute("fill", C.ink));
    // With its head through a zero the goose stands right behind it, and none of it is let
    // show under the foot of the rim.
    const above = F.id("above"), aboveRect = F.el("rect", { x: -3000, y: -3000, width: 8000, height: 10 }, F.el("clipPath", { id: above }, world));
    const gooseBox = F.g(world), gooseSize = F.g(gooseBox), goose = F.goose(gooseSize, { u: U0 });
    const fronts = F.ROW.zeros.map(() => {
      const g = F.g(world), tilt = F.g(g), id = F.id("low");
      F.el("rect", { x: -RX - 80, y: -RY * 0.58, width: 2 * RX + 160, height: RY }, F.el("clipPath", { id }, tilt));
      return { g, tilt, p: cut(F.T(F.g(F.g(tilt, { "clip-path": `url(#${id})` })), 0, -RY), zeroD) };
    });
    // Close up, the ground this side of the zeros hides where the goose's neck comes from.
    const band = F.el("rect", { x: -20, width: F.W + 40, fill: C.creamDeep }, world);
    const comma = F.g(world), commaPath = cut(F.T(F.g(comma), 0, 0, 6), F.commaD(2.9 * U0));

    const place = (g, u, x, y) => g.setAttribute("transform", `translate(${x.toFixed(2)} ${y.toFixed(2)}) scale(${(u / U0).toFixed(4)})`);
    const weight = (u) => (5.5 * Math.min(1, 0.55 + 0.06 * u)).toFixed(2);
    const deep = F.H - F.DROP + 60;

    return {
      world, U0, RX, RY,
      // v: { z: [[unit, x, foot] x4], comma: [unit, x, y], plus: [unit, x, y], hy, sun: [x, y, r],
      // band, far, shade }. o: what the scene adds on top (which zero is being looked through,
      // how far each zero is rocked, how far round the plus has turned, where the clouds and
      // the birds are).
      draw(v, pose, gu, o = {}) {
        sun.setAttribute("transform", `translate(${v.sun[0].toFixed(2)} ${v.sun[1].toFixed(2)}) scale(${(v.sun[2] / 100).toFixed(4)})`);
        clouds.forEach((c, i) => {
          F.T(c.g, c.x + (o.drift || 0) * (i % 2 ? 0.8 : 1) + v.far * 0.5, c.y + (v.hy - 830) * 0.25);
          if (c.far) F.op(c.g, v.shade / 0.12);
        });
        hills.forEach((h) => F.T(h.n, h.x + v.far, v.hy + h.y));
        F.show(flock, Boolean(o.birds));
        if (o.birds) o.birds.forEach(([x, y, beat, size], i) => {
            const tip = -5 - 9 * Math.sin(beat), w = 15 * size;
            birds[i].setAttribute("d", `M${(x - w).toFixed(1)},${(y + tip * size).toFixed(1)} Q${(x - w * 0.45).toFixed(1)},${(y - 7 * size).toFixed(1)} ${x.toFixed(1)},${y.toFixed(1)} Q${(x + w * 0.45).toFixed(1)},${(y - 7 * size).toFixed(1)} ${(x + w).toFixed(1)},${(y + tip * size).toFixed(1)}`);
        });
        F.set(ground, { y: v.hy.toFixed(2), height: Math.max(0, deep - v.hy).toFixed(2) });
        F.set(band, { y: v.band.toFixed(2), height: Math.max(0, deep - v.band).toFixed(2) });

        F.op(shade, v.shade);
        v.z.forEach(([u, x, foot], i) => {
          const rock = LEAN[i] + ((o.rock && o.rock[i]) || 0), lift = (o.lift && o.lift[i]) || 0;
          zShade[i].setAttribute("transform", `translate(${x.toFixed(2)} ${foot.toFixed(2)}) skewX(-62) scale(${(u / U0).toFixed(4)} ${(-0.26 * u / U0).toFixed(4)})`);
          [zeros[i], fronts[i]].forEach((z) => {
            place(z.g, u, x, foot + lift);
            z.tilt.setAttribute("transform", `rotate(${rock.toFixed(3)})`);
            z.p.setAttribute("stroke-width", weight(u));
          });
          F.show(fronts[i].g, o.through === i);
          rims[i].use.setAttribute("transform", `translate(${x.toFixed(2)} ${(foot + lift).toFixed(2)}) scale(${(u / U0).toFixed(4)}) rotate(${rock.toFixed(3)}) translate(0 ${-RY})`);
        });
        place(plus, v.plus[0], v.plus[1], v.plus[2]);
        plusTurn.setAttribute("transform", `rotate(${(4 + (o.turn || 0)).toFixed(2)})`);
        plusPath.setAttribute("stroke-width", weight(v.plus[0]));
        place(comma, v.comma[0], v.comma[1], v.comma[2]);
        commaPath.setAttribute("stroke-width", weight(v.comma[0]));

        // The goose is one size on the page and another in the film: a pose is given in the
        // frame's own pixels and brought down to the size the rig was built at.
        const k = gu / U0, local = Object.assign({}, pose, { bx: pose.bx / k, by: pose.by / k, ax: pose.ax / k, ay: pose.ay / k, bend: (pose.bend || 0) / k });
        gooseSize.setAttribute("transform", `scale(${k.toFixed(4)})`);
        goose.pose(local);
        F.show(rimShade, o.through != null);
        if (o.through == null) gooseBox.removeAttribute("clip-path");
        if (o.through != null) {
          const [zu, , zfoot] = v.z[o.through];
          aboveRect.setAttribute("height", (3000 + zfoot + ((o.lift && o.lift[o.through]) || 0) - 0.95 * zu).toFixed(2));
          gooseBox.setAttribute("clip-path", `url(#${above})`);
          rimShade.setAttribute("clip-path", `url(#${rims[o.through].id})`);
          rimSize.setAttribute("transform", `scale(${k.toFixed(4)})`);
          rimGoose.pose(local);
        }
        const foot = o.foot ?? 1330;
        ghostFall.setAttribute("transform", `translate(${pose.bx.toFixed(2)} ${foot}) skewX(-62) scale(1 -0.15) translate(${(-pose.bx).toFixed(2)} ${-foot})`);
        ghostSize.setAttribute("transform", `scale(${k.toFixed(4)})`);
        ghost.pose(local);
        F.op(ghostFall, o.ghost ?? 1);
      },
      // Where the tip of the beak is, in the frame's pixels.
      beak(pose, gu) {
        const k = gu / U0, b = goose.beak(Object.assign({}, pose, { ax: pose.ax / k, ay: pose.ay / k }));
        return [b[0] * k, b[1] * k];
      },
    };
  };
})();
