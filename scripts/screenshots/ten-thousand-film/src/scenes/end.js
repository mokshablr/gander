// 0:17.5 to 0:22.5. The end card. The morning opens out from where the goose stood as the 1,
// and the pieces of the app's icon come together round it: three file cards and the goose
// looking over them, at the icon's own coordinates, as at the end of the brand film. Under
// it the name, the line the app goes by and where to find it, set as that film sets them.
(function () {
  const F = window.F, C = F.C, E = F.E;

  F.scenes.push({
    // It holds for a second after the narrator has said its last line.
    id: "end", t0: 17.5, t1: 22.5, z: 1,
    build(root) {
      const T = 17.5, k = F.TALL ? 7 : 6.3, CX = F.W / 2, CY = F.TALL ? 640 : 412;
      const ROWS = F.TALL ? [1190, 1304, 1440] : [942, 1046, 1160]; // wordmark, tagline, address
      const P = (x, y) => [CX + (x - 54) * k, CY + (y - 54) * k]; // launcher-icon units to the frame
      const clipId = F.id("morning");
      const iris = F.el("circle", { cx: 175, cy: 800 + F.DROP, r: 0 }, F.el("clipPath", { id: clipId }, root));
      const card = F.g(root, { "clip-path": `url(#${clipId})` });
      F.ground(card, C.cream);
      const tile = F.g(card);
      F.el("path", { d: F.rr(72 * k, 72 * k, 17 * k), fill: "rgba(26,34,44,0.10)", transform: "translate(10 14)" }, tile);
      F.wob(F.el("path", { d: F.rr(72 * k, 72 * k, 17 * k), fill: "#EFE7D6" }, tile), { amp: 2.2, wl: 140, step: 6 });
      const goose = F.goose(card, { u: k });
      const cards = [
        { col: "#1565C0", to: [43, 61, -16] }, { col: "#2E7D32", to: [67.5, 61, 14] }, { col: "#D32F2F", to: [54, 54, -2] },
      ].map((c, i) => Object.assign(c, { n: F.fileCard(card, { w: 26 * k, badge: " ", color: c.col, lines: [0.62, 0.46], shadow: i === 2 }) }));
      const word = F.headline(card, { x: CX, y: ROWS[0], size: 196, weight: 700, ls: "-0.035em", anchor: "middle", lines: [{ text: "Gander", fill: C.ink }] });
      const tag = F.text(card, "", { x: CX, y: ROWS[1], size: 60, weight: 500, fill: C.ink, anchor: "middle" });
      [["Take a gander at "], ["any", 1], [" file."]].forEach(([s, red]) => {
        const sp = F.el("tspan", red ? { fill: C.red, "font-style": "italic", "font-weight": 600 } : null, tag);
        sp.textContent = s;
      });
      tag.setAttribute("xml:space", "preserve");
      const url = F.text(card, "arjun.maniyani.com/gander", { x: CX, y: ROWS[2], size: 38, weight: 500, fill: C.inkSoft, anchor: "middle", ls: "0.05em" });
      F.cue(T, "end"); F.cue(T + 0.2, "tile"); [0, 1, 2].forEach((i) => F.cue(T + 0.55 + i * 0.1, "fan", { i }));
      F.cue(T + 1.0, "gooseUp", { dur: 0.8 }); // The score has three notes for the name, a quarter of a second apart. The name comes in
      // on the first, the line under it on the second and the address on the third.
      F.cue(T + 1.5, "wordmark"); F.cue(T + 1.75, "tagline"); F.cue(T + 2.0, "url"); F.cue(T + 3.5, "nod");
      const home = P(57.85, 37.4), base = P(57.85, 66);
      const at = (ay, o) => Object.assign({ bx: base[0], by: base[1], ax: home[0], ay }, o);
      const POSE = [
        [T + 1.0, at(P(0, 60)[1])], [T + 1.8, at(home[1]), E.spring(0.55, 10)], [T + 3.5, at(home[1])],
        [T + 3.7, at(home[1] + 14, { tilt: -9 }), E.soft], [T + 4.05, at(home[1]), E.out],
      ];
      return (lt, t) => {
        iris.setAttribute("r", (F.tw(t, T, 0.6, E.io) * 2600).toFixed(1));
        F.T(tile, CX, CY, 0, F.tw(t, T + 0.2, 0.7, E.spring(0.55, 10)));
        cards.forEach((c, i) => {
          const a = F.tw(t, T + 0.55 + i * 0.1, 0.75, E.spring(0.6, 10)), from = P(54, 60), to = P(c.to[0], c.to[1]);
          F.T(c.n.root, F.lerp(from[0], to[0], a), F.lerp(from[1] + 40, to[1], a), c.to[2] * a, F.lerp(0.5, 1, a));
          F.op(c.n.root, F.tw(t, T + 0.55 + i * 0.1, 0.15, E.lin));
        });
        const pose = F.poseKeys(t, POSE);
        pose.blink = F.blinks(t, [T + 2.6, T + 3.8]);
        goose.pose(pose);
        F.show(goose.root, t > T + 1.0);
        word.update(F.prog(t, T + 1.5, T + 2.3));
        [[tag, T + 1.75], [url, T + 2.0]].forEach(([n, s]) => {
          const a = F.tw(t, s, 0.6, E.out);
          n.setAttribute("transform", `translate(0 ${((1 - a) * 26).toFixed(2)})`);
          F.op(n, a);
        });
      };
    },
  });
})();
