// This film's own props: the numerals. They are cut out of paper like everything else in the
// brand film (one flat colour, an edge that wobbles, a flat shadow), and they are shapes
// rather than type so that a zero is a ring with a real hole in it.
(function () {
  const F = window.F;

  // One piece of cut paper. A shadow or a second layer of the same piece is this again with
  // the same path and the same wobble, and the two then move as one.
  F.paper = (parent, d, o = {}) =>
    F.wob(F.el("path", {
      d, fill: o.fill || "none", "fill-rule": o.rule || "evenodd", stroke: o.stroke, "stroke-width": o.sw,
      "stroke-linejoin": "round", "stroke-linecap": "round",
    }, parent), { amp: o.amp ?? 1.5, wl: o.wl ?? 85, step: o.step ?? 4 });

  // A zero, centred on the origin: an ellipse with an ellipse cut out of it.
  F.zeroD = (rx, ry, s) => {
    const ix = rx - s, iy = ry - s;
    return `M${rx},0 A${rx},${ry} 0 1 1 ${-rx},0 A${rx},${ry} 0 1 1 ${rx},0 Z ` +
      `M${ix},0 A${ix},${iy} 0 1 0 ${-ix},0 A${ix},${iy} 0 1 0 ${ix},0 Z`;
  };

  // A comma, with the origin at the centre of its dot and the tail going down to the left.
  F.commaD = (r) => {
    const p = (x, y) => `${(x * r).toFixed(2)},${(y * r).toFixed(2)}`;
    return `M${p(0, -1)} C${p(0.55, -1)} ${p(1, -0.55)} ${p(1, 0)} C${p(1, 1.25)} ${p(0.35, 2.35)} ${p(-0.95, 2.9)} ` +
      `C${p(-0.25, 2.2)} ${p(0, 1.5)} ${p(-0.1, 0.98)} C${p(-0.6, 0.95)} ${p(-1, 0.5)} ${p(-1, 0)} C${p(-1, -0.55)} ${p(-0.55, -1)} ${p(0, -1)} Z`;
  };

  // A plus, centred on the origin, as one outline so that it can be edged like the zeros.
  F.plusD = (len, thick) => {
    const a = len / 2, b = thick / 2;
    return `M${-b},${-a} H${b} V${-b} H${a} V${b} H${b} V${a} H${-b} V${b} H${-a} V${-b} H${-b} Z`;
  };
})();
