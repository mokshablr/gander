// This film's frame. The brand film is drawn wide and re-seated for a phone; this one is drawn
// upright from the start, for a 4:5 frame of 1080x1350, and every scene works in that frame's
// coordinates. With ?tall the same picture is set down whole on 1080x1920, with more ground
// above and below it, so nothing is drawn twice and the two cuts share one timeline.
(function () {
  const F = window.F;
  if (!F.TALL) { F.W = 1080; F.H = 1350; }
  // How far down the tall frame the 4:5 picture starts. A phone's video apps put their own
  // furniture over the top and the foot of a tall frame, so the picture sits between them.
  F.DROP = F.TALL ? 190 : 0;
  F.stage = (node) => {
    if (F.DROP) node.setAttribute("transform", `translate(0 ${F.DROP})`);
    return node;
  };
})();
