// The goose. Its head is the launcher icon's own outline (ic_launcher_foreground.xml), moved
// so that the origin is the top of the neck and split at the beak so it can open. The neck
// is rebuilt every frame as a tapered ribbon along a curve from wherever the base is hidden
// to wherever the head has got to, so the only things a scene animates are a base, a head
// position and a handful of numbers.
(function () {
  const F = window.F, C = F.C;

  // Icon units. One unit is 1/6.3 of the neck's width.
  const HEAD =
    "M3.15,0 L3.15,-4.4 C3.15,-8.8 2.55,-11.8 1.05,-13.3 C-0.15,-14.5 -1.75,-15.15 -3.45,-15.1 " +
    "C-6.25,-15 -8.45,-13.1 -9.05,-10.4 L-8.95,-6.1 C-8.25,-4.3 -6.65,-3.1 -4.75,-2.8 " +
    "C-3.65,-2.6 -3.15,-1.7 -3.15,0 Z";
  const UPPER = "M-7.6,-10.9 L-9.05,-10.4 L-14.75,-8.6 C-15.2,-8.45 -15.38,-8.2 -15.27,-8 L-7.6,-8 Z";
  const LOWER = "M-7.6,-8 L-15.27,-8 C-15.32,-7.75 -15.1,-7.55 -14.75,-7.45 L-8.95,-6.1 L-7.6,-5.8 Z";
  const HINGE = [-8.7, -8];
  // The head seen from in front: as wide as the neck, and its sides are the back of the
  // profile head, mirrored, so that in profile it is wholly inside the head and never shows.
  const FRONT =
    "M3.15,0 L3.15,-4.4 C3.15,-8.8 2.55,-11.8 1.05,-13.3 C0.7,-13.62 0.35,-13.9 0,-13.9 " +
    "C-0.35,-13.9 -0.7,-13.62 -1.05,-13.3 C-2.55,-11.8 -3.15,-8.8 -3.15,-4.4 L-3.15,0 Z";

  const REST = {
    bx: 0, by: 400,   // where the neck comes from, in stage pixels
    ax: 0, ay: 0,     // where the top of the neck is
    tilt: 0,          // degrees; positive lifts the beak
    bend: 0,          // pushes the middle of the neck sideways, in pixels, toward the beak if positive
    rise: 0.38,       // how far the neck keeps going straight up out of its base, as a share of its length
    curl: 0.38,       // and how far out from the head it is already coming straight into it
    open: 0,          // 0..1 beak
    blink: 0,         // 0..1 eyelid
    brow: 0,          // 0..1 the scowl that goes with a honk
    flip: 0,          // 0 faces left like the icon, 1 faces right, and between them it is turning
    alpha: 1,
  };

  F.goose = (parent, o = {}) => {
    const u = o.u || 10;
    const root = F.g(parent);
    const neck = F.el("path", { fill: o.fill || C.goose }, root);
    const front = F.g(root);
    const frontShape = F.el("path", { d: FRONT, fill: o.fill || C.goose, transform: `scale(${u})` }, front);
    const head = F.g(root);
    const hs = F.g(head, { transform: `scale(${u})` });
    const mouth = F.el("path", { fill: C.coral }, hs);
    const lower = F.el("path", { d: LOWER, fill: o.fill || C.goose }, hs);
    const upper = F.el("path", { d: UPPER, fill: o.fill || C.goose }, hs);
    F.wob(F.el("path", { d: HEAD, fill: o.fill || C.goose }, hs), { amp: 0.13, wl: 7, step: 0.35 });
    F.el("ellipse", {
      cx: -4.95, cy: -6.5, rx: 3.5, ry: 2.4, fill: C.cheek, transform: "rotate(-22 -4.95 -6.5)",
    }, hs);
    const eye = F.el("ellipse", { cx: -5.75, cy: -11.2, rx: 1.12, ry: 1.12, fill: C.cream }, hs);
    // The scowl is a lid of head colour that comes down across the top of the eye at a slant.
    const brow = F.el("path", { d: "M-8.2,-13.6 L-3.6,-12.2 L-3.6,-14.6 L-8.2,-15 Z", fill: o.fill || C.goose }, hs);
    const extras = F.g(hs); // hats and the like, in icon units, head-relative

    const cubic = (p0, p1, p2, p3, s) => {
      const m = 1 - s;
      return [
        m * m * m * p0[0] + 3 * m * m * s * p1[0] + 3 * m * s * s * p2[0] + s * s * s * p3[0],
        m * m * m * p0[1] + 3 * m * m * s * p1[1] + 3 * m * s * s * p2[1] + s * s * s * p3[1],
      ];
    };

    const api = {
      root, extras, u,
      pose(p) {
        p = Object.assign({}, REST, p);
        // flip runs 0..1 and the head turns: the profile narrows to nothing at the halfway
        // point and opens out again facing the other way. It narrows over the front view,
        // though, not over nothing. A head on a neck is never narrower than the neck, and
        // without this the turn opened a notch of background between chin and neck and left
        // the head, at the midpoint, as a sliver on a flat-topped post. The neck is worked
        // out as if facing left and mirrored once the turn is past halfway, and the tilt it
        // arrives at goes through level at the midpoint, so that mirroring it moves nothing.
        const f = 1 - 2 * F.clamp(p.flip), sgn = f < 0 ? -1 : 1;
        const B = [(p.bx - p.ax) * sgn, p.by - p.ay];
        const th = (p.tilt * Math.abs(f) * Math.PI) / 180;
        const L = Math.hypot(B[0], B[1]);
        const down = [-Math.sin(th), Math.cos(th)]; // the head's own "down", after tilt
        const c2 = [down[0] * L * p.curl - p.bend * 0.6, down[1] * L * p.curl];
        const c1 = [B[0] - p.bend, B[1] - L * p.rise];
        const N = 26, left = [], right = [];
        for (let i = 0; i <= N; i++) {
          const s = i / N;
          const a = cubic(B, c1, c2, [0, 0], s), b = cubic(B, c1, c2, [0, 0], Math.min(1, s + 0.01));
          const a0 = cubic(B, c1, c2, [0, 0], Math.max(0, s - 0.01));
          let tx = b[0] - a0[0], ty = b[1] - a0[1];
          const tl = Math.hypot(tx, ty) || 1;
          tx /= tl; ty /= tl;
          // The neck's edge wobbles by how far along it a point is, not by where it is on
          // screen, so the wobble rides with the neck instead of swimming through it.
          const fade = Math.min(1, (1 - s) * 5);
          const hw = 3.15 * u * (1 + 0.32 * Math.pow(1 - s, 1.6));
          const wl = F.noise2(s * 4.2, 3.1, F.seed) * 0.14 * u * fade, wr = F.noise2(s * 4.2, 9.7, F.seed) * 0.14 * u * fade;
          left.push([(a[0] + ty * (hw + wl)) * sgn, a[1] - tx * (hw + wl)]);
          right.push([(a[0] - ty * (hw + wr)) * sgn, a[1] + tx * (hw + wr)]);
        }
        // The ribbon runs on a little way into the head, so that the join is an overlap and
        // never a seam, whatever the head is doing.
        const into = [-down[0] * 1.6 * u, -down[1] * 1.6 * u], hwTop = 3.15 * u;
        left.push([(into[0] - down[1] * hwTop) * sgn, into[1] + down[0] * hwTop]);
        right.push([(into[0] + down[1] * hwTop) * sgn, into[1] - down[0] * hwTop]);
        const pts = left.concat(right.reverse());
        neck.setAttribute("d", "M" + pts.map((q) => q[0].toFixed(2) + "," + q[1].toFixed(2)).join(" L") + " Z");

        F.T(root, p.ax, p.ay);
        const turning = Math.abs(f) < 0.985;
        F.show(front, turning);
        if (turning) {
          front.setAttribute("transform", `rotate(${(p.tilt * f).toFixed(3)})`);
          // The profile is a little taller than the front view, so the front view grows to
          // meet it as the turn comes edge on, and is its own height again where it hands over.
          frontShape.setAttribute("transform", `scale(${u} ${(u * (1 + 0.09 * (1 - Math.abs(f)))).toFixed(3)})`);
        }
        F.show(head, Math.abs(f) >= 0.08); // edge on, the profile is a sliver taller than the front view; leave it out
        const fx = Math.abs(f) < 0.04 ? 0.04 * sgn : f;
        head.setAttribute("transform", `scale(${fx.toFixed(4)} 1) rotate(${p.tilt.toFixed(3)})`);
        const up = -p.open * 17, lo = p.open * 27;
        upper.setAttribute("transform", `rotate(${up} ${HINGE[0]} ${HINGE[1]})`);
        lower.setAttribute("transform", `rotate(${lo} ${HINGE[0]} ${HINGE[1]})`);
        if (p.open > 0.01) {
          const rot = (deg, x, y) => {
            const r = (deg * Math.PI) / 180, dx = x - HINGE[0], dy = y - HINGE[1];
            return [HINGE[0] + dx * Math.cos(r) - dy * Math.sin(r), HINGE[1] + dx * Math.sin(r) + dy * Math.cos(r)];
          };
          const a = rot(up, -13.4, -8), b = rot(lo, -13.4, -8);
          mouth.setAttribute("d", `M${HINGE[0] + 1},${HINGE[1]} L${a[0]},${a[1]} L${b[0]},${b[1]} Z`);
          F.show(mouth, true);
        } else F.show(mouth, false);
        eye.setAttribute("ry", (1.12 * (1 - 0.9 * F.clamp(p.blink))).toFixed(3));
        brow.setAttribute("transform", `translate(0 ${(F.clamp(p.brow) * 2.5 - 0.4).toFixed(3)})`);
        F.op(root, p.alpha);
      },
      // Where the tip of the beak is on the stage for a given pose, for anything that has
      // to come out of it.
      beak(p) {
        p = Object.assign({}, REST, p);
        const f = 1 - 2 * F.clamp(p.flip), r = (p.tilt * Math.PI) / 180;
        const x = -15.27 * u, y = -8 * u;
        return [p.ax + (x * Math.cos(r) - y * Math.sin(r)) * f, p.ay + x * Math.sin(r) + y * Math.cos(r)];
      },
    };
    api.pose({});
    return api;
  };

  // Mix two poses field by field.
  F.mixPose = (a, b, k) => {
    const out = {};
    const keys = new Set(Object.keys(a).concat(Object.keys(b)));
    keys.forEach((key) => {
      out[key] = F.lerp(a[key] ?? REST[key], b[key] ?? REST[key], k);
    });
    return out;
  };
  // Pose keyframes: [[time, pose], [time, pose, ease], ...]. Always hands back a fresh
  // object with every field filled in: scenes add breathing and blinks to what they get, and
  // if that were ever a key itself, each frame drawn would bend the track for the next one,
  // and a frame would stop being a function of its time alone.
  F.poseKeys = (t, ks) => {
    if (t <= ks[0][0]) return Object.assign({}, REST, ks[0][1]);
    for (let i = 1; i < ks.length; i++) {
      if (t <= ks[i][0]) {
        const e = ks[i][2] || F.E.io;
        return F.mixPose(ks[i - 1][1], ks[i][1], e(F.prog(t, ks[i - 1][0], ks[i][0])));
      }
    }
    return Object.assign({}, REST, ks[ks.length - 1][1]);
  };
  // Blinks at the given times, each about a fifth of a second. Returns 0..1.
  F.blinks = (t, times) => {
    let v = 0;
    for (const bt of times) {
      const d = t - bt;
      if (d > 0 && d < 0.2) v = Math.max(v, d < 0.07 ? d / 0.07 : d < 0.1 ? 1 : 1 - (d - 0.1) / 0.1);
    }
    return v;
  };
})();
