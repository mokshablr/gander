import * as pdfjsLib from "./lib/pdf.min.mjs";

/* The worker keeps parsing off the UI thread; without it pdf.js falls back to
   doing everything inline and large documents visibly stall. */
pdfjsLib.GlobalWorkerOptions.workerSrc = "./lib/pdf.worker.min.mjs";

var pages = document.getElementById("pages");

/*
 * The width every page is laid out at, read once.
 *
 * There is no viewport meta, so this is Chromium's fixed 980 CSS px default and
 * cannot change, not on rotation either. Reading clientWidth flushes style and
 * layout, and doing that once per page drawn, between two writes that dirty it, is a
 * relayout of a several-hundred-child block flow to re-learn a constant.
 */
var PAGE_WIDTH = pages.clientWidth || window.innerWidth;

/*
 * What this file used to call DPR, kept only as the answer for when the platform will
 * not give a straight one.
 *
 * It was 2 because 2 bought pinch headroom: a page drawn at twice the layout width had
 * spare pixels for the compositor to magnify, which is what kept it sharp to about
 * 1.8x and is why one A4 page cost about 21 MB rather than 6. Tiles buy that headroom
 * now, and buy all of it rather than 1.8x of it. It is the last-resort scale, and the
 * ceiling nothing below is allowed to exceed, so that a measurement that comes out
 * wrong is this file's old behaviour rather than a new failure. A page bitmap on a
 * phone 980 px across or wider reaches it.
 */
var DPR_FALLBACK = Math.min(window.devicePixelRatio || 1, 2);

/*
 * Twice the screen's own resolution for a page bitmap, for the compositor to scale down.
 *
 * .pg is fractionally wide and sits at a fraction of a pixel, so a bitmap near 1:1 is
 * resampled by a fraction of a pixel, which is a blur on every edge. Measured on a phone
 * at 720 and 1080 px across, a fitted page drawn a tenth over the screen came out a fifth
 * softer than Android's own PDF engine draws it, and one drawn at twice level with it.
 */
var BASE_HEADROOM = 2;

/*
 * The most bitmap one canvas may hold, per side and in total.
 *
 * Less a memory budget than a floor under the failure. A page bitmap is sized from its
 * width, so a page far taller than it is wide costs whatever that comes to: on a phone
 * 1080 px across, where A4 comes to five and a half megapixels, a receipt or a long
 * ticket at aspect 0.2 would come to nineteen. Past Blink's limit of 32767 px the canvas
 * is simply not allocated, and a canvas that failed to allocate is a blank page with
 * nothing said about it.
 *
 * 8192 is well inside that limit and inside the GPU texture limits Android devices
 * report, which are commonly between 4096 and 16384. On a phone 980 px across or wider the
 * pixel cap first bites around aspect 0.5, a page twice as tall as it is wide, so A4,
 * Letter, Legal and both of the maps in issue #20 are clear of it.
 */
var MAX_SIDE = 8192;
var BASE_MAX_PIXELS = 8e6;

/*
 * Canvas pixels per CSS pixel for a page bitmap: twice the resolution the screen has with
 * the page fitted to its width, and no more than DPR_FALLBACK.
 *
 * Deliberately not a function of the current zoom, so a page that happens to be drawn
 * while the reader is pinched in is the same size as one drawn while they are not, and
 * so a document costs the same whatever anybody is doing to it.
 */
function baseScale() {
  var w = vwPhysicalWidth();
  return w ? Math.min((w / PAGE_WIDTH) * BASE_HEADROOM, DPR_FALLBACK) : DPR_FALLBACK;
}

/*
 * What the tile gate is measured against: the screen's own resolution and a tenth, not
 * the page bitmap. A tile then comes in at about 1.4 times the fitted width, short of a
 * double tap's 2x, where a bitmap of twice the screen's pixels would be magnified with
 * no tile over it.
 */
function gateScale() {
  var w = vwPhysicalWidth();
  return w ? Math.min((w / PAGE_WIDTH) * 1.1, DPR_FALLBACK) : DPR_FALLBACK;
}

/*
 * Canvas pixels per CSS pixel needed to draw sharp at the pinch the reader is at now.
 *
 * The fallback needs nothing measured. PAGE_WIDTH over the visible width is how much
 * more magnified than fit-to-width the reader is, one at fit by construction, so
 * multiplying the old constant by it reproduces the old sharpness at every zoom.
 */
function liveScale() {
  var vv = window.visualViewport;
  if (!vv || !(vv.width > 0)) return DPR_FALLBACK;
  var w = vwPhysicalWidth();
  return w ? w / vv.width : DPR_FALLBACK * (PAGE_WIDTH / vv.width);
}

/*
 * How far a bitmap of this size has to be scaled down to be allowed, and 1 when it
 * already is. One answer to what this file will ask a canvas for, shared by the page
 * bitmaps and by the tiles laid over them.
 */
function within(w, h, maxPixels) {
  var f = 1;
  if (w > MAX_SIDE) f = Math.min(f, MAX_SIDE / w);
  if (h > MAX_SIDE) f = Math.min(f, MAX_SIDE / h);
  if (w * h > maxPixels) f = Math.min(f, Math.sqrt(maxPixels / (w * h)));
  return f;
}

/*
 * How many pages may be rasterising at once.
 *
 * There is little to win by raising it. pdf.js rasterises on the main thread, so a
 * fourth page in flight does not arrive any sooner; it holds a fourth page of bitmap
 * while it waits its turn. What the cap buys is a ceiling that does not move with how
 * fast somebody scrolls.
 *
 * Measured on a Nothing Phone 2 against a 41 MB, 357-page document carrying an image
 * on every page. Uncapped, a fling took the WebView renderer from 207 MB to 836 MB in
 * about five seconds, and Android's low memory killer ended it at oom_score_adj 1,
 * which is to say while it was the foreground process. Twice in one sitting, taking
 * unrelated applications' renderers with it on the way past.
 */
var MAX_IN_FLIGHT = 3;

/*
 * How far either side of the screen pages are drawn, and how much further they are
 * kept, both counted in pages.
 *
 * These were percentages of the viewport, and a percentage is the wrong unit here.
 * rootMargin resolves against the viewport, but a page's height is fixed by the
 * 980 CSS px layout and does not move, so the two units drift apart the moment the
 * phone is turned. Measured on a Nothing Phone 2: 200% was about seven pages in
 * portrait and about one and a third in landscape, where the viewport is a fifth as
 * tall and the page is exactly as tall as it ever was. At one and a third pages the
 * renderer cannot stay ahead of a scroll, and landscape brought back the blank pages
 * this whole change exists to remove. Verified before the fix: nine swipes in
 * landscape and the screen was a blank page.
 *
 * Counted in pages the two numbers mean the same thing on any screen and in either
 * orientation, which also means a reader who rotates mid-document keeps whatever
 * they had. DRAW is the depth the renderer has to stay ahead of; the gap up to KEEP
 * is the hysteresis that stops a small change of direction costing a redraw.
 */
var DRAW_PAGES = 3;
var KEEP_PAGES = 5;

/* One page box including its margin, in CSS px. Set from page 1, which is the same
   place the placeholders take their shape from. */
var PAGE_PITCH = 0;

/* Assigned once the document opens. Everything below needs it, and the observer
   callbacks outlive the promise chain that produced it. */
var doc = null;

/*
 * One slot per page: the canvas standing for it, what state that canvas is in, and
 * the render in progress if there is one. Index 0 is page 1.
 *
 * Four states. "blank" holds no bitmap. "queued" is wanted but waiting for a turn
 * under MAX_IN_FLIGHT. "drawing" holds a live pdf.js RenderTask. "drawn" holds a
 * painted bitmap. The rest of this file is about moving pages between those four as
 * they come into and go out of view.
 *
 * All four are about that one canvas. A drawn page may be carrying a tile besides: a
 * second and sharper picture of the part of it on screen, and another on its way to
 * replacing it. Those hang off the slot without being states of it, because a page is
 * no more or less drawn for having one. See the note further down beside TILE_GATE.
 */
var slots = [];
var queue = [];
var inFlight = 0;

/* Tiles waiting for a turn, at most one per page on screen. Separate from the queue
   above rather than mixed into it because the two are picked between on different
   grounds: a page is chosen by how near the middle of the screen it is, and a tile is
   chosen ahead of every page there is. Both spend the same MAX_IN_FLIGHT. */
var tileQueue = [];

/*
 * Everything the search knows.
 *
 * "pages" is the text of the whole document, one normalised string per page, built
 * once on the first search and kept. It is the only part of a page that outlives
 * being released, and it is why a match on page 300 can be found while pages 1 to 11
 * are the only ones drawn. Text is small: the 41 MB, 357-page document this file was
 * tuned against holds about 1.4 MB of it, against 21 MB for a single canvas.
 *
 * "matches" is flat and in document order, so the number the reader is shown is a
 * position in one list rather than a page and an offset within it.
 */
var finding = {
  query: "",
  pages: null,
  matches: [],
  active: -1,
  building: false,
  built: 0
};

/*
 * Give a placeholder the shape of the page it stands for.
 *
 * This is the whole of the bug this file used to have. A canvas with no width or
 * height attribute is 300x150, the HTML default, so every undrawn page was a small
 * white box. That was visible directly, as a column of little boxes whenever a reader
 * outran the renderer, and it caused everything else besides: at 158 px per page
 * including its margin, the look-ahead band below held about fifty placeholders where
 * it should hold three or four, and every one of them began rendering at once.
 *
 * The size goes on the style rather than on the canvas attributes deliberately.
 * Writing width and height allocates the backing store, which is the one thing worth
 * not doing until the page is actually wanted. An aspect-ratio against a width of
 * 100% lays the box out at full size while the bitmap stays at the default, or at
 * nothing at all once it has been released. Chromium has had aspect-ratio since 88
 * and pdf.js already requires 125, so there is no floor to check here.
 */
function shape(box, unscaled) {
  box.style.aspectRatio = String(unscaled.width / unscaled.height);
}

/* Distance from the middle of the viewport. During a fling the queue fills mostly
   with pages the reader has already gone past, and without this the renderer works
   through them in page order while the page being looked at waits its turn behind
   them. */
function distance(slot) {
  var r = slot.canvas.getBoundingClientRect();
  return Math.abs(r.top + r.height / 2 - window.innerHeight / 2);
}

/*
 * Ask for a page. Idempotent: a page already queued, drawing or drawn is left alone,
 * which is what makes it safe to call from an observer that reports the same target
 * more than once.
 *
 * It queues and does not draw. Draining here instead meant the first MAX_IN_FLIGHT
 * pages of an arriving batch were dispatched before there was a queue to choose
 * from, so they went in the order the observer reported them, which is page order,
 * which during a fling is the pages furthest behind the reader. The caller pumps
 * once the whole batch is in.
 */
function want(slot) {
  if (slot.state !== "blank") return;
  slot.state = "queued";
  queue.push(slot);
}

/*
 * Give up on a page that has left the band before it was finished.
 *
 * This matters as much as the cap does. A fling drags the band across the whole
 * document, so without cancelling, every page it passed over is still queued and
 * still going to be drawn, and fifty-at-once comes back as three-at-once repeated
 * three hundred times. pdf.js rejects a cancelled render with
 * RenderingCancelledException, which the failure path below puts back to blank.
 */
function unwant(slot) {
  if (slot.state === "queued") {
    var i = queue.indexOf(slot);
    if (i >= 0) queue.splice(i, 1);
    /* A page keeping its old picture while it waited for a new one has left the band,
       so nobody is looking at it any more and the picture goes with it. */
    if (slot.held) blank(slot); else slot.state = "blank";
  } else if (slot.state === "drawing" && slot.task) {
    slot.task.cancel();
  }
}

/*
 * Drop a drawn page's bitmap while keeping its box.
 *
 * Chromium hibernates idle offscreen 2D canvases on Android and reclaims a good deal
 * unprompted, which is why reading slowly holds flat at about 195 MB with none of
 * this. What it cannot do is keep pace with a fling, because a canvas being painted
 * right now is neither idle nor offscreen. This is the half hibernation cannot cover.
 *
 * The box survives because its size lives in CSS, so releasing a page moves nothing
 * on screen and costs the reader a redraw rather than a reflow.
 */
function release(slot) {
  if (slot.state !== "drawn") return;
  blank(slot);
}

/*
 * Put a slot back to holding nothing: no bitmap, no decoded artwork, and free to be
 * asked for again. Every path that gives a page up ends here, so the two statements
 * that actually reclaim memory live in one place.
 */
function blank(slot) {
  slot.canvas.width = 0;
  slot.canvas.height = 0;
  /* And the replacement on its way, if the render was cancelled part way: it never
     reached the document and still holds its backing store until this zeroes it. */
  if (slot.fresh) {
    slot.fresh.width = 0;
    slot.fresh.height = 0;
    slot.fresh = null;
  }
  slot.held = false;
  slot.state = "blank";
  slot.unscaled = null;
  /* The mode goes with the bitmap it describes: a blank slot has not been drawn in
     either one, and draw() compares against this to know a page needs asking for again.
     The verdicts beside it are only tidiness, and deliberately so - a slot belongs to one
     page for the life of the document and the coordinates behind them are fractions, so
     they would still be true if they were kept. Dropped anyway, with the array they
     describe, rather than leaving a slot holding something about a page it is no longer
     showing. */
  slot.mode = null;
  slot.shrink = null;
  slot.pictures = null;

  /*
   * The sharp patch goes too, and it goes before the cleanup() at the end of this
   * function rather than after it. cleanup() declines while any render on the page is
   * still outstanding, and a tile is a render on the page.
   *
   * Not fragile if that order ever slips. cleanup() leaves its flag set when it
   * declines and pdf.js runs the reclaim again as the last render finishes, so the
   * memory still comes back; the order is here to keep it from taking a detour.
   */
  dropTile(slot);

  /*
   * The words go with the picture.
   *
   * A text layer is one absolutely positioned span per run of text, so a dense page
   * is several hundred elements and a document is however many of those the band is
   * holding. Leaving them behind would rebuild the memory problem this whole file is
   * arranged against, in DOM instead of bitmaps, and it would do it quietly: nothing
   * on screen changes, because the words are transparent and the picture under them
   * has already gone.
   *
   * cancel() first. TextLayer streams its content in, so a layer still arriving when
   * the page is released would otherwise finish and repopulate the div just emptied.
   */
  if (slot.layer) {
    slot.layer.cancel();
    slot.layer = null;
  }
  slot.text.replaceChildren();

  /*
   * The canvas is only half of what a drawn page costs, and on an illustrated
   * document it is the smaller half. pdf.js keeps every image it decoded for a page
   * on the page object itself, and holds that object in its own cache for as long as
   * the document is open, so dropping the bitmap alone leaves the decoded artwork
   * behind. A 960x1200 JPEG costs about 4.6 MB decoded whatever it weighed on disk.
   *
   * Measured on a Nothing Phone 2 with the canvas released but this line missing: a
   * fling settled at 550 MB and stayed there with nothing left on screen to account
   * for it. With it, the same fling settles back to roughly where it opened.
   *
   * cleanup() declines while a render is outstanding, which is why this runs only
   * for a page in the drawn state, and it is safe to call again later: the page is
   * rebuilt from the file if the reader comes back to it.
   */
  if (slot.page) {
    slot.page.cleanup();
    slot.page = null;
  }
}

function pump() {
  while (inFlight < MAX_IN_FLIGHT) {
    /*
     * Tiles first, and out of the same allowance.
     *
     * A tile is the picture the reader is looking at; the queue behind it is pages they
     * might reach. Giving tiles a lane of their own would have been simpler and would
     * have quietly moved MAX_IN_FLIGHT from three to four, and that three was measured
     * against a fling that got the renderer killed. It is not a number to add to on the
     * side. The cost of sharing is that a tile can wait behind one page already being
     * drawn, a few hundred milliseconds on a heavy one.
     */
    if (tileQueue.length) { drawTile(tileQueue.shift()); continue; }
    if (!queue.length) return;
    var next = 0;
    var best = distance(queue[0]);
    for (var i = 1; i < queue.length; i++) {
      var d = distance(queue[i]);
      if (d < best) { next = i; best = d; }
    }
    draw(queue.splice(next, 1)[0]);
  }
}

/*
 * Night mode, issue #19.
 *
 * The whole of it is one filtered pass over the finished page bitmap. Not a CSS filter
 * on the canvas element: that promotes every page to a compositor layer of its own and
 * is re-evaluated through every scroll and every pinch, which is the trade this file
 * spent 1.12 undoing. It would also catch the text layer, the selection, the find
 * marks, the page shadow and the password card, none of which is the document.
 *
 * pdf.js does exactly this for its own high-contrast mode: endDrawing() sets ctx.filter
 * and draws the canvas onto itself once. Same mechanism, different matrix, ours applied
 * from out here because the matrix it builds is a luminance ramp and throws colour away.
 */
var inverted = !!window.vwNight;

/*
 * Where the images on a page are, in the pixels of the canvas just drawn.
 *
 * Which of them are pictures, and so must be left alone, is a separate question and is
 * answered by looksLikePaper() below. Two rules were tried here first and both are worth
 * knowing about, because each fails on a document people actually read.
 *
 * Sizing it does not work. An image covering most of a page was taken to be the page and
 * turned over, on the reasoning that a scan is one full-page image. The first real file
 * it met was a full-page holiday photograph, which it turned inside out; a rectangle
 * cannot tell a scan from a photograph.
 *
 * Excluding every image does not work either, and fails more loudly. A figure exported
 * as a PNG, a logo on a letterhead and a chapter ornament all carry an opaque white
 * background, so leaving them alone puts a glaring white rectangle in the middle of a
 * black page - worse than doing nothing, on the commonest documents there are. KOReader
 * shipped exactly this rule and had it reported two months later as their issue #4986.
 *
 * pdf.js fills page.imageCoordinates when a render is asked for with recordImages, and
 * the shape of it is undocumented: six numbers per image, each a fraction of the canvas
 * that recorded them, giving three corners of the parallelogram the image was painted
 * into. The first corner is the one between the other two, so the fourth is B + C - A,
 * and the area is the cross product of the two edges leaving A. It is a parallelogram
 * rather than a rectangle so that a rotated or sheared placement is still exact.
 *
 * Fractions rather than pixels is what makes one recording serve every scale, which is
 * why a tile can use the numbers the full-page render produced. sx/ox and sy/oy carry
 * whatever linear map gets from those fractions to this canvas.
 *
 * Read the note in docs/VENDORED.md before touching any of this. Nothing here throws if
 * it is wrong; photographs quietly start turning over, or the clip lands somewhere else
 * on the page.
 */
function imageQuads(page, sx, ox, sy, oy) {
  var raw = page && page.imageCoordinates;
  if (!raw || !raw.length) return [];
  var out = [];
  for (var i = 0; i + 5 < raw.length; i += 6) {
    var ax = raw[i], ay = raw[i + 1];
    var bx = raw[i + 2], by = raw[i + 3];
    var cx = raw[i + 4], cy = raw[i + 5];
    /* A degenerate rectangle is not a picture and would clip nothing. */
    if (!(Math.abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) > 0)) continue;

    /*
     * Into this canvas, and on to whole pixels.
     *
     * Rounding is not tidiness. A clip edge landing part-way across a pixel is
     * antialiased, so that pixel gets some of the filtered draw and some of what was
     * under it, and the photograph comes back wearing a grey hairline. Measured on a
     * 33,856-pixel image: 184 pixels of fringe before this, none after. An
     * axis-aligned image shares each coordinate between two corners, so rounding them
     * one at a time keeps it square; a rotated one antialiases along its diagonals
     * whatever we do here, which is correct.
     *
     * To nearest rather than outwards. These arrive as Float16 above Chromium 135 and
     * Float32 below it, so the worse of the two is about a pixel of slack on a
     * page-sized canvas, and rounding away from the image would spend that pixel on
     * paper: an un-inverted line of white against a black page, which is the visible
     * failure. Rounding to nearest spends it on the edge of the photograph instead,
     * where a pixel either way cannot be seen.
     */
    var pax = Math.round(ax * sx + ox), pay = Math.round(ay * sy + oy);
    var pbx = Math.round(bx * sx + ox), pby = Math.round(by * sy + oy);
    var pcx = Math.round(cx * sx + ox), pcy = Math.round(cy * sy + oy);
    out.push([
      pax, pay,
      pbx, pby,
      pbx + pcx - pax, pby + pcy - pay,
      pcx, pcy
    ]);
  }
  return out;
}

/*
 * Is this rectangle of the page paper, or is it a picture?
 *
 * Paper wants turning over with the rest of the page; a picture wants leaving exactly as
 * it was printed. Nothing in the PDF says which one an image is - a scanned page, a
 * chart, a logo and a photograph are all just images - so the only thing left to ask is
 * what it looks like.
 *
 * Two measurements, and it takes both. Saturation catches a colour photograph, which is
 * full of colour where a document is not. But a black and white photograph has no
 * saturation either, so on its own that rule turns every one of them inside out; what
 * separates those is that a document is mostly blank paper and a photograph is not. So
 * an image counts as paper only when it is both unsaturated and largely white, which a
 * scan, a chart and a letterhead all are, and which no photograph is.
 *
 * Where this is known to be wrong, so the next person does not have to find it twice: a
 * photograph that carries its own white background and is not strongly coloured - a pale
 * object rendered or shot on white - reads as a document on both counts and is turned over.
 * Measured on the NeRF paper, whose figures are exactly that: those images run about 0.06
 * saturation and 80% white, which is a scanned page's signature. A third statistic was tried
 * to separate them, the fraction of the image that is neither near-black nor near-white, on
 * the reasoning that a document is bimodal and a photograph is not; it made things worse.
 * Those images measure 0.17 there against 0.31 for real documents, because being mostly blank
 * is the very thing they share. It is a limit of judging an image by its colours rather than
 * a threshold that wants tuning. The visible result is one kept photograph sitting on a white
 * rectangle beside an inverted one, and the answer if it ever matters is the switch KOReader
 * ships, not a cleverer measurement.
 *
 * The page is read back once, small, and every image is measured out of that one buffer.
 * Reading each image separately is the obvious way to write this and was how it was
 * written first: it costs a GPU to CPU round trip per image, which is nothing on a
 * desktop and 11 ms each on a phone, so a page carrying sixty small figures spent 684 ms
 * deciding what they were. One read of the whole page costs 1.9 ms on the same phone
 * whatever the page holds. Measured on a Nothing Phone AIN065; the desktop number for
 * the same work was 0.04 ms, which is why this has to be measured on a device.
 */
var PROBE_WIDTH = 256;
/* Both thresholds measured on real files before being chosen. A page scanned from a book
   reads about 0.02 saturation and 54% white; a phone photograph of a document reads 0.05
   and 40%; a colour photograph reads 0.38 and 5%. They sit in the gap, which is an order
   of magnitude wide on saturation, rather than being tuned to any one file. */
var PROBE_SATURATION = 0.15;
var PROBE_PAPER = 0.25;
/* A guard rather than a behaviour: with no samples the two ratios below are NaN, and
   every comparison against NaN is false, so this only makes the answer say what it means.
   An image small enough to reach it is a few pixels across and invisible either way. */
var PROBE_MIN_SAMPLES = 4;
var probeCanvas = null;

/*
 * One small copy of the finished page, and the map back to it.
 *
 * willReadFrequently because reading it back is the entire point: without it Chromium
 * keeps the canvas on the GPU and pays to fetch it every time.
 */
function pageSample(ctx) {
  var w = Math.max(1, Math.min(PROBE_WIDTH, ctx.canvas.width));
  var h = Math.max(1, Math.round(w * ctx.canvas.height / ctx.canvas.width));
  if (!probeCanvas) probeCanvas = document.createElement("canvas");
  if (probeCanvas.width !== w || probeCanvas.height !== h) {
    probeCanvas.width = w;
    probeCanvas.height = h;
  }
  var p = probeCanvas.getContext("2d", { willReadFrequently: true });
  p.clearRect(0, 0, w, h);
  p.drawImage(ctx.canvas, 0, 0, w, h);
  /* sx and sy come out equal, because the copy keeps the page's shape. Both are kept so
     that the mapping below reads as a mapping rather than as a single magic ratio. */
  return {
    w: w, h: h,
    data: p.getImageData(0, 0, w, h).data,
    sx: w / ctx.canvas.width,
    sy: h / ctx.canvas.height
  };
}

function looksLikePaper(sample, q) {
  if (!sample) return false;
  var xs = [q[0], q[2], q[4], q[6]];
  var ys = [q[1], q[3], q[5], q[7]];
  var x0 = Math.max(0, Math.floor(Math.min.apply(null, xs) * sample.sx));
  var y0 = Math.max(0, Math.floor(Math.min.apply(null, ys) * sample.sy));
  var x1 = Math.min(sample.w, Math.ceil(Math.max.apply(null, xs) * sample.sx));
  var y1 = Math.min(sample.h, Math.ceil(Math.max.apply(null, ys) * sample.sy));

  var d = sample.data, pale = 0, sat = 0, n = 0;
  for (var y = y0; y < y1; y++) {
    for (var x = x0; x < x1; x++) {
      var o = (y * sample.w + x) * 4;
      var r = d[o], g = d[o + 1], b = d[o + 2];
      var mx = Math.max(r, g, b), mn = Math.min(r, g, b);
      if (mn > 204) pale++;
      if (mx > 0) sat += (mx - mn) / mx;
      n++;
    }
  }
  if (n < PROBE_MIN_SAMPLES) return false;
  return (sat / n) < PROBE_SATURATION && (pale / n) > PROBE_PAPER;
}

/*
 * Turn a finished bitmap over, all of it but the pictures.
 *
 * Plain source-over is right because the bitmap is opaque: pdf.js fills the canvas with
 * the background colour before the first operator whatever the page does afterwards, and
 * a page that draws with transparency is composed onto that fill rather than instead of
 * it. The transform is set rather than assumed; pdf.js restores the one it made, but
 * inheriting a stray one here would put the whole page back down somewhere else.
 */
function recolour(ctx, keep) {
  ctx.save();
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.globalAlpha = 1;
  ctx.globalCompositeOperation = "source-over";
  /*
   * Everything, less the pictures: one hole at a time, and that is the whole of why
   * this is a loop rather than a single path.
   *
   * Putting every picture into one even-odd path is the obvious way to write it and is
   * wrong where two of them overlap. Even-odd counts crossings, so the canvas plus two
   * holes is three, which is odd, which is inside: the overlap gets turned over while
   * both pictures around it are left alone. Two copies of one image at the same place -
   * which a PDF is free to emit - come to the same three, and the whole picture inverts.
   *
   * Clipping is an intersection, so a hole at a time gives everything-but-A intersected
   * with everything-but-B, which is everything but the two of them together, however
   * they lie. Nonzero winding does not fix it either: the overlap counts -1 there, which
   * is also inside.
   */
  for (var i = 0; i < keep.length; i++) {
    var q = keep[i];
    ctx.beginPath();
    ctx.rect(0, 0, ctx.canvas.width, ctx.canvas.height);
    ctx.moveTo(q[0], q[1]);
    ctx.lineTo(q[2], q[3]);
    ctx.lineTo(q[4], q[5]);
    ctx.lineTo(q[6], q[7]);
    ctx.closePath();
    ctx.clip("evenodd");
  }
  ctx.filter = "url(#vw-invert)";
  ctx.drawImage(ctx.canvas, 0, 0);
  ctx.restore();
}

/*
 * The reader turned night mode on or off.
 *
 * Every page holding a bitmap drawn in the other mode is asked for again, rather than
 * filtered a second time to put it back. The matrix is its own inverse on paper, but a
 * saturated colour clamps on the way out and does not come back: red at 200,30,30 wants
 * 1.265 of a red channel and gets 1.0, and a second pass returns something else. Draw it
 * again, through redraw(), so a page on screen keeps its picture until the new one lands.
 *
 * Pages part-way through a render are left where they are. draw() reconciles them when
 * they land, which is also what makes toggling twice in a second come out right: the
 * mode is read again at the end of every render, so the last one the reader chose wins
 * however many are in flight behind it.
 *
 * Tiles come back on their own. redraw() drops the one a page is holding, and draw()
 * asks for a new one as it finishes.
 */
function setNight(on) {
  if (on === inverted) return;
  inverted = on;
  document.documentElement.classList.toggle("vw-night", on);
  for (var i = 0; i < slots.length; i++) {
    var s = slots[i];
    if (s.state === "drawn" && s.mode !== inverted) redraw(s, true);
  }
  pump();
}

/*
 * Ask for a drawn page again, in the mode or at the width it is shown now, without the
 * reader watching it go blank.
 *
 * A page on screen keeps the picture it has, which the stylesheet stretches to its box's
 * new size, until draw() has the replacement; draw() paints that into a canvas of its own
 * and swaps it in whole. Measured on a phone before this, a pinch settling past the fitted
 * width took every page on screen to white in one frame and filled the column back in
 * over about 200 ms, and a double tap back did the same at its end. A page off screen is
 * released as before, since nobody is looking at it and the band asks for it again if it
 * comes back; [ask] asks here instead, for the callers whose band will not.
 *
 * What is held is bounded by the screen. The old picture goes the moment the new one
 * lands, and goes with the page if it leaves the band first, in unwant() and the failure
 * path of draw(), so a fling across a column of held pages holds nothing for long.
 */
function redraw(slot, ask) {
  if (slot.state !== "drawn") return;
  if (!onScreen(slot)) {
    blank(slot);
    if (ask) want(slot);
    return;
  }
  dropTile(slot);
  slot.held = true;
  slot.state = "queued";
  queue.push(slot);
}

/* Whether any of a page's box is inside the visual viewport. The box is in layout
   coordinates, and the visual viewport's offset and height are in the same space. */
function onScreen(slot) {
  var vv = window.visualViewport;
  var r = slot.box.getBoundingClientRect();
  var top = vv ? vv.offsetTop : 0;
  var bottom = vv ? top + vv.height : window.innerHeight;
  return r.bottom > top && r.top < bottom;
}

function draw(slot) {
  slot.state = "drawing";
  inFlight++;
  /* The mode this bitmap is being drawn in, which is not necessarily the mode by the
     time it lands. Read once here and compared once at the end. */
  var mode = inverted;
  /* And the width this bitmap is drawn for, the same way: read once here, and compared
     at the end against where a pinch has moved it to since. */
  var at = shrink;
  return doc.getPage(slot.n).then(function (page) {
    slot.page = page;

    var unscaled = page.getViewport({ scale: 1 });
    /* Correct the placeholder to this page's own shape. Page 1's shape was a
       stand-in for it until now, and in a document of uniform pages it was exact. */
    shape(slot.box, unscaled);
    /* Kept because a tile has to be able to build a viewport for this page later,
       without another round trip for a matrix that has already been built. Dropped in
       blank() with everything else the page was holding. */
    slot.unscaled = unscaled;

    /*
     * Three scales now, and no two of them are the same number.
     *
     * This canvas is a bitmap and wants device pixels, so it is drawn at baseScale()
     * times the layout width: twice the resolution the screen has with the page fitted to
     * its width. The tile drawn over it later wants device pixels too, but at whatever the
     * live pinch needs, which is larger the moment anybody zooms in. The text layer is
     * DOM and wants CSS pixels: it has to land on the box, which is PAGE_WIDTH wide
     * whatever the bitmaps behind it are. Hand the text layer a canvas viewport and
     * every word lands at a multiple of its offset, off the bottom of the page.
     *
     * Keep all three apart. This note used to say that the blur fix in #20 would change
     * the canvas scale and must not be allowed to collapse two of these into one. It
     * did change the canvas scale, and that is still the way to break this.
     */
    var cssScale = PAGE_WIDTH / unscaled.width;
    /* Times the shrink, so a page is drawn at the size it is shown and a column of small
       pages costs a fraction of what a column of fitted ones does. The text layer below
       is not: its viewport is the fitted width, and the stylesheet shrinks the type. */
    var scale = cssScale * at * baseScale();
    scale *= within(unscaled.width * scale, unscaled.height * scale, BASE_MAX_PIXELS);
    /* clone rather than a second getViewport: the same matrix, already built. */
    var viewport = unscaled.clone({ scale: scale });
    /*
     * Drawn into a canvas of its own, outside the document, and swapped for the page's
     * canvas once it is finished, the way a tile is. Sizing a canvas clears it, and
     * pdf.js paints it white before its first operator, so a page drawn again in place
     * showed as white filling in from the top for as long as the render took: at the
     * end of a double tap back to the fitted width, every page on screen at once. The
     * page keeps whatever it was showing until the swap, which is the old picture
     * stretched for a page redraw() held, and white paper for a page not drawn before.
     */
    var canvas = document.createElement("canvas");
    canvas.width = viewport.width;
    canvas.height = viewport.height;
    slot.fresh = canvas;
    /* Held rather than asked for twice: recolour() below wants the same context, and
       getContext is a lookup on every call for a handle that never changes. */
    var ctx = canvas.getContext("2d");
    slot.task = page.render({
      canvasContext: ctx,
      viewport: viewport,
      /* Asked for only when it is going to be used. The tracker is allocated per render
         and every image on the page pays a bounding box into it, so ordinary daylight
         reading does not carry the cost. pdf.js records once per page object and keeps
         the answer, which is why a page turned over twice measures itself once. */
      recordImages: mode
    });
    return slot.task.promise.then(function () {
      if (mode) {
        var quads = imageQuads(page, canvas.width, 0, canvas.height, 0);
        var sample = quads.length ? pageSample(ctx) : null;
        /*
         * Judged here, on the whole page, and remembered for the tiles.
         *
         * A tile holds only the part of an image that falls inside it, and a corner of a
         * photograph can measure very differently from all of it: the sky alone is pale
         * and unsaturated and would be read as paper. One verdict per image per page,
         * taken where the whole thing is visible, is also the only way the tile and the
         * page underneath it can agree about what to turn over.
         */
        slot.pictures = quads.map(function (q) { return !looksLikePaper(sample, q); });
        recolour(ctx, quads.filter(function (q, i) { return slot.pictures[i]; }));
      }
      slot.mode = mode;
      slot.shrink = at;
      /* The swap, in one step: the finished picture goes in and the old one's backing
         store is dropped, so the page shows exactly one picture on every frame. */
      var old = slot.canvas;
      slot.box.replaceChild(canvas, old);
      old.width = 0;
      old.height = 0;
      slot.canvas = canvas;
      slot.fresh = null;
      var held = slot.held;
      slot.held = false;
      /* A held page's words are already where they belong. The text layer is laid out for
         the fitted width and follows --vw-shrink, so only the picture needed drawing again,
         and leaving the layer alone keeps a selection or a search hit on it through the
         redraw. */
      if (held && slot.layer) return;
      return textLayerFor(slot, page, unscaled.clone({ scale: cssScale }));
    });
  }).then(function () {
    slot.state = "drawn";
    /*
     * Night mode moved while this was being drawn, so the picture that just arrived is
     * of the wrong one. Ask for it again, keeping this one up meanwhile; the mode is read
     * afresh at the top of draw(), so this converges however many times the reader taps.
     * A pinch that moved the pages well away from the width this was drawn for is the
     * same case.
     *
     * Here rather than in setNight() because a render in flight cannot be told to change
     * its mind, and cancelling it would put this page down the same failure path a fling
     * uses, which is the hottest one in the file and not somewhere to add a special case.
     */
    if (slot.mode !== inverted || !sizedForNow(slot)) {
      redraw(slot, true);
      return;
    }
    /* A page can come back after being released with a search still running, so
       the hits on it have to be put back: a Range does not survive its nodes. Whole
       registry rather than this page's part of it, because that is one code path
       over about eleven drawn pages rather than two that have to agree. */
    if (finding.query) { paint(); reveal(); }
    /* A page arriving while the reader is pinched in wants its sharp patch, and no
       event is going to say so: they are holding still, waiting for this. */
    scheduleRefine();
  }, function () {
    /* Cancelled or genuinely broken. Both end the same way: nothing held, the picture
       on its way thrown away with whatever the page was keeping up meanwhile, and free
       to be asked for again if the reader comes back to it. */
    blank(slot);
  }).then(function () {
    slot.task = null;
    inFlight--;
    pump();
  });
}

/*
 * Drawing the page again, at the zoom the reader is actually at.
 *
 * Everything above draws a page once. Pinch zoom is Chromium's compositor magnifying
 * that bitmap, so past the point where it runs out of pixels the reader is looking at an
 * upscale. That was issue #20: two A3 tube maps, which have to be taken to six or ten
 * times before a station name is legible at all, held up against MuPDF, which redraws.
 *
 * Redrawing a whole page at ten times is not on offer. Measured on a Nothing Phone 2,
 * eight times the fit zoom is 8.8 device pixels to the CSS pixel, which puts an A4 page
 * at 8,624 by 12,195 px, about 420 MB, in a file whose MAX_IN_FLIGHT exists because an
 * uncapped fling once took this renderer to 836 MB and had it killed twice in one
 * sitting. So only the part on screen is drawn again, and that is the whole of why this
 * is safe: a tile is bounded by the screen rather than by the page. Measured on the same
 * phone, a tile stayed at 4.4 megapixels at every zoom from 2.7 to 11.7 device pixels
 * per CSS pixel, where the page itself at the top of that range would be 186.
 *
 * None of this talks to the app. The zoom is plainly visible from inside the page
 * through visualViewport and is not visible to Kotlin at all, which has no
 * onScaleChanged and, deliberately, no JavascriptInterface. Keeping the whole of it on
 * one side of that line costs nothing here and is worth keeping.
 */

/*
 * How far past gateScale() the live zoom has to go before part of a page is worth
 * drawing again.
 *
 * A quarter short is about where a shortfall starts to read as blur. Acting on any
 * shortfall at all would spend a render on a five per cent gain every time somebody
 * nudged the zoom, and a render is not free: it is a page's worth of drawing on the main
 * thread, ahead of the pages the reader is scrolling towards.
 */
var TILE_GATE = 1.25;

/*
 * How far past the visible rectangle a tile reaches, as a fraction of what is visible.
 *
 * Entirely about how often a pan costs a redraw. A fifth either way absorbs the small
 * correction that follows a pinch without the tile costing half as much again, and a pan
 * that outruns it is a deliberate move to somewhere else on the page, which is going to
 * want its own tile whatever this number says.
 *
 * It is also what keeps one rectangle the right answer here rather than a grid of them.
 * A fifth either way is 1.4 squared, about twice the screen. Okular is the one viewer
 * that implements both a viewport rectangle and a tile grid and chooses between them at
 * runtime, and its thresholds are four times the screen area to turn tiling on and three
 * to turn it back off; at twice, this sits inside the band where it would keep tiling
 * off. Raise this far enough to pass about three times the screen area and that
 * calibration is what says to think about a grid instead.
 *
 * Measured cost of the single rectangle, on a Nothing Phone 2 at 4.4 device pixels to the
 * CSS pixel: a 700 px pan takes the sharp area from 26% of the screen down to 5%, and
 * back to all of it about 350 ms after the pan stops. It never reaches nothing, because
 * the tile already up is kept until its replacement has finished drawing.
 */
var TILE_MARGIN = 0.2;

/*
 * The most bitmap one tile may hold.
 *
 * A net rather than a budget. Tiles cover disjoint parts of one screen, so however many
 * there are they come to the screen plus their margins, which on a phone is four to six
 * megapixels between them; this should not be reached there. It is here for the screen
 * that is much larger than a phone's, where the honest answer is a slightly smaller
 * margin rather than a canvas the platform will refuse to allocate.
 */
var TILE_MAX_PIXELS = 6e6;

/*
 * A ceiling on how many pages may carry a tile at once.
 *
 * It does not bind, and that is worth writing down rather than discovering again. A page
 * box plus its margin is about 1395 CSS px, and by the time the gate opens the visible
 * height is around 1600 CSS px on a phone and less on a tablet, so the list this
 * truncates has one or two pages in it and never three. It is here as a bound on a loop
 * that reads geometry, not as a memory limit that is ever reached, and the tile tests
 * deliberately do not try to exercise it.
 */
var MAX_TILES = 2;

/*
 * How long after the last movement a tile is drawn.
 *
 * Debounced, where the page counter beside this is coalesced once a frame, and the
 * difference is not an oversight. The counter has to be right during the gesture. A tile
 * drawn during a pinch is thrown away by the next frame of that pinch, so this one waits
 * for the hand to stop.
 */
var TILE_SETTLE_MS = 150;

/*
 * Let go of a tile: the render if one is still running, the element if it reached the
 * page, and the bitmap either way.
 *
 * The order matters. A cancelled render has to be told to stop before the canvas it was
 * drawing into is worth anything to reclaim, and a canvas that never reached the document
 * still holds its backing store until something zeroes it.
 */
function shed(tile) {
  if (!tile) return;
  var task = tile.task;
  tile.task = null;
  if (task) task.cancel();
  if (tile.canvas.parentNode) tile.canvas.parentNode.removeChild(tile.canvas);
  tile.canvas.width = 0;
  tile.canvas.height = 0;
}

/* Both of a page's tiles: the one on screen and the one on its way. */
function dropTile(slot) {
  shed(slot.pending);
  shed(slot.tile);
  slot.pending = null;
  slot.tile = null;
}

/*
 * Draw one, into a canvas that is not in the document yet.
 *
 * That last part is the detail worth keeping. pdf.js paints a canvas white before it
 * draws anything into it, so a tile attached while it renders is a white block over the
 * page. Attached on resolve instead, the reader looks at the stretched page bitmap until
 * the moment there is something better, which is exactly what they would have been
 * looking at if none of this existed.
 *
 * The page bitmap underneath is left alone, and so is the tile already on screen if
 * there is one, until this one is ready to take its place. Both are what stops a pan
 * from flashing back to blur on its way to being sharp again.
 */
function drawTile(req) {
  var slot = req.slot;
  /* pump() drains this queue whenever a render finishes, which is exactly when the
     bands may have moved, so a page asked for a moment ago can have been let go since. */
  if (slot.state !== "drawn" || !slot.page || !slot.unscaled) return;

  shed(slot.pending);
  /* As in draw(): the mode this patch is being drawn in, checked again when it lands. */
  var mode = inverted;
  var canvas = document.createElement("canvas");
  canvas.className = "tile";
  canvas.setAttribute("aria-hidden", "true");
  canvas.width = req.cw;
  canvas.height = req.ch;

  var tile = {
    canvas: canvas,
    x: req.x, y: req.y, w: req.w, h: req.h, k: req.k, task: null
  };
  slot.pending = tile;

  var cssScale = PAGE_WIDTH / slot.unscaled.width;
  var ctx = canvas.getContext("2d");
  tile.task = slot.page.render({
    canvasContext: ctx,
    /* A no-op in practice: a tile only ever follows a full-page render of the same page,
       and pdf.js keeps the first answer. Asked for anyway so that a tile is never the
       thing that leaves a page with no image coordinates to clip by. */
    recordImages: mode,
    viewport: slot.unscaled.clone({ scale: cssScale * req.k }),
    /*
     * pdf.js applies this outside the viewport's own transform, in canvas pixels, so a
     * plain translation is the whole of what turns a full-page render into this corner
     * of one. Leaving the scale part at 1 is deliberate: the viewport above already
     * carries it, and pdf.js reads these two numbers only to size an annotation canvas
     * this viewer never asks for.
     *
     * The page's operator list is not parsed again for this. pdf.js caches it against
     * the rendering intent rather than the scale, and holds more than one render on a
     * page at a time quite happily, refusing only two onto the same canvas. So a tile
     * is the display list run a second time, not the page read a second time.
     *
     * It is still the whole display list though, operators outside this rectangle
     * included. 6.3.289 has what is needed to skip those, on the public API:
     * `recordOperations: true` fills PDFPageProxy.recordedBBoxes, and `operationsFilter`
     * is consulted per operator. Upstream's own detail view does exactly that, in the
     * web/ layer this file does not vendor. It is left alone because the recording has to
     * be paid for on every page drawn, tiled or not, to save time on renders that only
     * happen while somebody is zoomed in. Worth revisiting if a tile ever takes long
     * enough to notice; it took about 160 ms on a Nothing Phone 2 when this was written.
     */
    transform: [1, 0, 0, 1, -req.x * req.k, -req.y * req.k]
  });
  /* Counted only once there is something to count. A render that threw on the way out
     would otherwise hold a place in MAX_IN_FLIGHT that nothing is ever going to give
     back, and the queue behind it would stop for good. */
  inFlight++;

  tile.task.promise.then(function () {
    /* Cleared here rather than at the end of the chain, so that whatever sheds this tile
       next is not cancelling a render that has already finished. */
    tile.task = null;
    if (slot.pending !== tile) { shed(tile); return; }
    slot.pending = null;
    /* Night mode moved while this was being drawn. The page under it has already been
       asked for again in the new mode, and this is a patch of the old picture. */
    if (mode !== inverted) { shed(tile); return; }
    if (mode) {
      /* The image coordinates are fractions of the whole page, and this canvas is one
         rectangle of it blown up: boxW across becomes boxW * k pixels, and the corner
         the tile starts at moves back to zero. */
      var seen = slot.pictures;
      recolour(ctx, imageQuads(
        slot.page,
        req.boxW * req.k, -req.x * req.k,
        req.boxH * req.k, -req.y * req.k
      ).filter(function (q, i) {
        /* Same source array, same order, so the page's verdicts line up. Without them,
           leave the image alone: that is the safe half of being wrong. */
        return !seen || seen[i];
      }));
    }
    canvas.style.left = String((req.x / req.boxW) * 100) + "%";
    canvas.style.top = String((req.y / req.boxH) * 100) + "%";
    canvas.style.width = String((req.w / req.boxW) * 100) + "%";
    canvas.style.height = String((req.h / req.boxH) * 100) + "%";
    /* Before the text layer, so the words stay over the picture. */
    slot.box.insertBefore(canvas, slot.text);
    shed(slot.tile);
    slot.tile = tile;
  }, function () {
    /* Cancelled, or the page would not draw. Either way the picture underneath is still
       correct and the reader is no worse off than before this file could do any of it. */
    tile.task = null;
    if (slot.pending === tile) slot.pending = null;
    shed(tile);
  }).then(function () {
    inFlight--;
    pump();
  });
}

/*
 * Work out what, if anything, is worth drawing again, and ask for it.
 *
 * Reads the geometry rather than being told it: getBoundingClientRect is in layout
 * viewport coordinates, which pinching deliberately does not move, and visualViewport's
 * offset and size are in that same space and say which part of it the reader can see. So
 * the two intersect directly, with no coordinate space in between to get wrong.
 *
 * The binary search is the same one reportPage does, for the same reason. This runs off
 * a gesture on a document that can be several hundred boxes, and asking every one of
 * them where it is would be several hundred layout reads a fifth of a second apart.
 */
function refine() {
  if (!doc || !slots.length) return;
  var vv = window.visualViewport;
  if (!vv || !(vv.width > 0) || !(vv.height > 0)) return;

  /* Anything asked for before this pass is out of date by definition. */
  tileQueue.length = 0;

  var k = liveScale();
  var i;
  /* Shrunk pages are drawn at the size they are shown and the WebView is at its floor, so
     there is nothing a tile could sharpen; said outright rather than left to the gate, so
     that no tile is ever drawn over a column of small pages. */
  if (shrink < 1 || !(k > gateScale() * TILE_GATE)) {
    /* Back at a zoom the page bitmaps cover on their own. A walk of every slot rather
       than a register of which ones are tiled: it is a property read per page, against
       the layout read per page that this function exists to avoid. */
    for (i = 0; i < slots.length; i++) dropTile(slots[i]);
    return;
  }

  var top = vv.offsetTop;
  var bottom = top + vv.height;
  var left = vv.offsetLeft;
  var right = left + vv.width;
  var middle = top + vv.height / 2;

  var lo = 0, hi = slots.length - 1, from = slots.length;
  while (lo <= hi) {
    var mid = (lo + hi) >> 1;
    if (slots[mid].box.getBoundingClientRect().bottom > top) {
      from = mid; hi = mid - 1;
    } else {
      lo = mid + 1;
    }
  }

  var showing = [];
  for (i = from; i < slots.length; i++) {
    var r = slots[i].box.getBoundingClientRect();
    if (r.top >= bottom) break;
    showing.push({
      slot: slots[i], box: r,
      away: Math.abs(r.top + r.height / 2 - middle)
    });
  }
  /* Nearest the middle of the screen first, so that when only some of what is showing
     can be tiled it is the part being looked at rather than the part at the edge. */
  showing.sort(function (a, b) { return a.away - b.away; });
  var chosen = showing.slice(0, MAX_TILES);

  for (i = 0; i < slots.length; i++) {
    var keep = false;
    for (var c = 0; c < chosen.length; c++) if (chosen[c].slot === slots[i]) keep = true;
    if (!keep) dropTile(slots[i]);
  }

  for (i = 0; i < chosen.length; i++) {
    var slot = chosen[i].slot;
    var box = chosen[i].box;
    if (slot.state !== "drawn" || !slot.unscaled) { dropTile(slot); continue; }

    /* The visible part of this page, measured from its own top left. */
    var vx = Math.max(box.left, left) - box.left;
    var vy = Math.max(box.top, top) - box.top;
    var vw = Math.min(box.right, right) - box.left - vx;
    var vh = Math.min(box.bottom, bottom) - box.top - vy;
    if (!(vw > 0) || !(vh > 0)) { dropTile(slot); continue; }

    /* As much margin as the budget will carry, up to TILE_MARGIN. Shrinking the margin
       is the right way to come in under the cap; scaling the tile down is not, because
       the scale is the entire point of drawing it. */
    var room = Math.sqrt(TILE_MAX_PIXELS / (vw * k * vh * k));
    var m = Math.max(0, Math.min(TILE_MARGIN, (room - 1) / 2));
    var x = Math.max(0, vx - vw * m);
    var y = Math.max(0, vy - vh * m);
    var w = Math.min(box.width, vx + vw + vw * m) - x;
    var h = Math.min(box.height, vy + vh + vh * m) - y;

    /* Only if even the bare visible rectangle will not fit, which on a phone it always
       does. Then a slightly soft tile beats no tile. */
    var tk = k * within(w * k, h * k, TILE_MAX_PIXELS);

    /*
     * Snapped so the rectangle begins and ends on whole tile pixels, and the placement
     * derived back from the snapped figures rather than the other way round. Done the
     * other way the tile sits a fraction of a device pixel off the bitmap underneath it
     * and gets resampled, which is precisely the detail this is here to recover.
     */
    var px = Math.max(0, Math.floor(x * tk));
    var py = Math.max(0, Math.floor(y * tk));
    var cw = Math.min(Math.ceil((x + w) * tk), Math.floor(box.width * tk)) - px;
    var ch = Math.min(Math.ceil((y + h) * tk), Math.floor(box.height * tk)) - py;
    if (!(cw > 0) || !(ch > 0)) { dropTile(slot); continue; }
    x = px / tk; y = py / tk; w = cw / tk; h = ch / tk;

    /* What is already up, or already coming, may well cover this. Redrawing on every
       small change would have a slow pan rendering continuously and never catching up. */
    if (covers(slot.tile, vx, vy, vw, vh, tk) ||
        covers(slot.pending, vx, vy, vw, vh, tk)) continue;

    tileQueue.push({
      slot: slot, x: x, y: y, w: w, h: h, cw: cw, ch: ch, k: tk,
      boxW: box.width, boxH: box.height
    });
  }

  pump();
}

/* Whether a tile is close enough in scale and wide enough in reach to stand for what is
   visible now. The scale tolerance is what stops a pinch that drifts a few per cent from
   costing a redraw; the half pixel is against the rounding above. */
function covers(tile, vx, vy, vw, vh, k) {
  if (!tile) return false;
  if (Math.abs(tile.k - k) > tile.k * 0.15) return false;
  return tile.x <= vx + 0.5 && tile.y <= vy + 0.5 &&
    tile.x + tile.w >= vx + vw - 0.5 && tile.y + tile.h >= vy + vh - 0.5;
}

/* One pass once the reader stops, however many events they arrive with. */
var tileTimer = 0;
function scheduleRefine() {
  if (tileTimer) clearTimeout(tileTimer);
  tileTimer = setTimeout(function () { tileTimer = 0; refine(); }, TILE_SETTLE_MS);
}

/*
 * Zooming out past the page's width, issue #52.
 *
 * Everything above zooms in. The WebView draws this page, and it will not zoom out below
 * the width of its content; a page is as wide as the layout, so at the fitted width the
 * pinch stopped. To go smaller the page shrinks its pages itself: #pages is laid out at
 * --vw-shrink of the layout width and centred, and every page fills it, one column, the
 * way Chrome's own viewer and Jetpack's show a document pinched small. The gap, the type
 * in the text layer and the hit boxes padRows() writes all read the same property, so
 * the words stay over the picture.
 *
 * Who owns a pinch is decided by where the WebView is. At the fitted width it sits at its
 * floor, and a pinch in can move it no further, so this page takes that pinch and shrinks
 * the column; a pinch out from there is the WebView's, as before. While the pages are
 * shrunk html.vw-shrunk sets touch-action to pan-x pan-y, so the WebView pinches nothing
 * and a pinch out is this page's, up to the fitted width and no further. touch-action is
 * read when a gesture begins, so the pinch that brings the pages back to the fitted width
 * ends there, and it is the next one that zooms in. The sheet in xlsx.js owns its pinch
 * the same way; what is new here is the handover.
 *
 * None of this is remembered between openings. A document reopens at its fitted width, at
 * the page it was left at.
 */

/*
 * The least a page may be shrunk to, as a fraction of the fitted width.
 *
 * A quarter puts an A4 page at about 100 dp wide on a phone, five or six of them down the
 * screen, which is what the reporter's example from Chrome showed and what a column of
 * pages is for: finding a place in a document by the shape of its pages, since no type is
 * readable at this size. Smaller than that a page is a thumbnail with no shape left to
 * recognise, and a double tap has further to bring it back. The sheet's floor is the same
 * number.
 */
var SHRINK_LEAST = 0.25;

/* The fraction the pages are laid out at now. 1 at the fitted width. */
var shrink = 1;

/*
 * Whether a page's bitmap is near enough the size it is shown at to keep.
 *
 * A quarter short reads as blur, which is the argument beside TILE_GATE; a quarter over is
 * the same number of pixels held for nothing. Inside that band a pinch costs no redraw, so
 * a reader who takes the pages in by a tenth and back does not watch the column go white.
 */
function sizedForNow(slot) {
  var r = slot.shrink / shrink;
  return r < TILE_GATE && r > 1 / TILE_GATE;
}

/*
 * Whether the WebView is at its floor, showing the whole of the layout's width. It is
 * there at the fitted width and whenever the pages are shrunk, and anywhere else a pinch
 * is the WebView's to magnify. Measured rather than remembered, so a rotation that moves
 * the floor has nothing to update.
 */
function atFloor() {
  var vv = window.visualViewport;
  return !vv || !(vv.width > 0) || vv.width >= window.innerWidth - 0.5;
}

/* Where the column of pages begins on the screen, which a shrink scales everything from. */
function columnTop() {
  return (slots.length ? slots[0].box : pages).getBoundingClientRect().top;
}

/* How far down the column, in the fitted page's px, the screen's [y] falls. */
function under(y) {
  return (y - columnTop()) / shrink;
}

/* A dp in this page's px: the layout's width over what the screen shows of it, as app.js
   measures the title bar. The slops below are in dp, since a finger is. */
function perDp() {
  var vv = window.visualViewport;
  var across = vv && vv.scale > 0 ? vv.width * vv.scale : 0;
  return across > 0 && window.innerWidth > 0 ? window.innerWidth / across : 1;
}

function setShrink(s) {
  shrink = Math.min(1, Math.max(SHRINK_LEAST, s));
  document.documentElement.style.setProperty("--vw-shrink", String(shrink));
  document.documentElement.classList.toggle("vw-shrunk", shrink < 1);
}

/*
 * Lay the pages out at [s], with the column's [at] (from under()) still at the screen's
 * [y], and put everything that depends on the width right.
 *
 * The scroll is measured after the layout rather than worked out before it. Chromium's
 * scroll anchoring may move the view as the column changes height, and whatever it did
 * is in the measurement.
 */
function settle(s, at, y) {
  pages.style.transform = "";
  pages.style.transformOrigin = "";
  pages.classList.remove("vw-moving");
  var was = shrink;
  setShrink(s);
  window.scrollBy(0, columnTop() + at * shrink - y);
  if (shrink === was) return;

  /* The bands, in pages as they are drawn now. A document of one page has no bands and
     is never observed, so its page is asked for again here. */
  var watched = slots.length > 1;
  if (watched) observePages();
  /*
   * Pages drawn for a width well away from this one are asked for again at the new size.
   * Those on screen keep their picture until the new one lands, see redraw(); the rest
   * are released, and the observers just built ask again for those in the band: asking
   * here would draw pages the band no longer reaches, only to cancel them. A page
   * part-way through a render is reconciled when it lands, as night mode's are.
   */
  for (var i = 0; i < slots.length; i++) {
    if (slots[i].state === "drawn" && !sizedForNow(slots[i])) redraw(slots[i], !watched);
  }
  pump();
  /* The page under the middle of the screen is likely another one now, and the sharp
     patches, if any were up, have nothing left to sharpen. A scroll of nought says
     neither, so both are said here. */
  schedulePageReport();
  scheduleRefine();
  if ((was < 1) !== (shrink < 1)) reportShrunk();
}

/*
 * Tells the app whether the pages are shrunk, so a double tap is zoomed by whichever of
 * the two should. The app zooms a double tap at the fitted width, in DoubleTapZoom.kt,
 * and while the pages are shrunk this page does, back to the fitted width; the WebView
 * is at its floor either way, so from the app's side the two look the same. One word and
 * one digit, read as exactly that in PortMessage.kt, and sent as the pages cross the
 * fitted width. The app starts from the pages at that width, so a port arriving to find
 * them there is told nothing, and one arriving to find them shrunk is told so.
 */
function reportShrunk() {
  if (!searchPort) return;
  if (shrink < 1) searchPort.postMessage("shrunk 1");
  else if (reportedShrunk) searchPort.postMessage("shrunk 0");
  reportedShrunk = shrink < 1;
}

/* Whether the app was last told the pages are shrunk. */
var reportedShrunk = false;

var pinch = null;

function apart(e) {
  var a = e.touches[0], b = e.touches[1];
  return Math.max(1, Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY));
}

function between(e) {
  return (e.touches[0].clientY + e.touches[1].clientY) / 2;
}

/* Two fingers down. Whose pinch this is cannot be known yet: it is the WebView's until it
   turns out to be moving in a direction the WebView can do nothing with. */
function startPinch(e) {
  finishAnimation();
  pinch = {
    apart: apart(e), from: shrink, to: shrink, taken: false,
    at: 0, y0: between(e), y: between(e), sy: window.scrollY
  };
}

function movePinch(e) {
  var g = pinch;
  var d = apart(e);
  var y = between(e);
  if (!g.taken) {
    /*
     * Still the WebView's: it is zoomed in and this pinch is magnifying the screen, or
     * the pages are at the fitted width and the fingers are spreading, which it zooms.
     * Measured from here, so that if it comes back to its floor with the fingers still
     * closing, the column takes over from where the fingers are rather than jumping to
     * where they began.
     */
    if (!atFloor() || (shrink >= 1 && d >= g.apart)) {
      g.apart = d; g.y0 = y; g.sy = window.scrollY;
      return;
    }
    g.taken = true;
    g.at = under(y);
    /* About the middle across, since a column laid out at its new width is centred, and
       about the fingers down the screen, which settle() then holds to. */
    pages.style.transformOrigin = "50% " + (y - pages.getBoundingClientRect().top) + "px";
    pages.classList.add("vw-moving");
  }
  g.y = y;
  g.to = Math.min(1, Math.max(SHRINK_LEAST, g.from * d / g.apart));
  /* The WebView may pan the page with the fingers as well, which the picture must not
     count twice. */
  var ty = y - g.y0 + window.scrollY - g.sy;
  /* #pages is as wide as the column, so this scale takes it to g.to of the layout's width
     at most: a picture wider than the layout has the WebView zooming out to fit it, see
     the rule for #pages in pdf.html. */
  pages.style.transform = "translate(0px," + ty + "px) scale(" + (g.to / g.from) + ")";
}

function endPinch() {
  var g = pinch;
  pinch = null;
  if (g.taken) settle(g.to, g.at, g.y);
}

/*
 * A double tap while the pages are shrunk, which this page zooms back to the fitted width
 * about the place tapped. At the fitted width a double tap is the app's: see reportShrunk.
 * The slops are in dp, as the app's gesture detector has them.
 */
var DOUBLE_TAP_MS = 250;
var DOUBLE_TAP_GAP_MS = 300;
var DOUBLE_TAP_SLOP_DP = 40;
var TAP_SLOP_DP = 12;
var tap = null;
var lastTap = null;

document.addEventListener("touchstart", function (e) {
  if (!doc || !slots.length) return;
  if (e.touches.length === 2) {
    tap = lastTap = null;
    startPinch(e);
  } else if (e.touches.length === 1 && !pinch && shrink < 1 && pages.contains(e.target)) {
    finishAnimation();
    tap = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() };
  } else {
    tap = lastTap = null;
  }
}, { passive: true });

document.addEventListener("touchmove", function (e) {
  if (pinch && e.touches.length === 2) {
    movePinch(e);
  } else if (tap && e.touches.length === 1 &&
      Math.abs(e.touches[0].clientX - tap.x) + Math.abs(e.touches[0].clientY - tap.y) >
      TAP_SLOP_DP * perDp()) {
    tap = null;
  }
}, { passive: true });

function touchEnded(e) {
  if (pinch && e.touches.length < 2) {
    endPinch();
    return;
  }
  if (!tap || e.touches.length) return;
  var now = Date.now();
  if (now - tap.t > DOUBLE_TAP_GAP_MS) { tap = null; return; }
  if (lastTap && now - lastTap.t < DOUBLE_TAP_GAP_MS &&
      Math.abs(tap.x - lastTap.x) + Math.abs(tap.y - lastTap.y) < DOUBLE_TAP_SLOP_DP * perDp()) {
    lastTap = null;
    zoomTo(1, tap.y, DOUBLE_TAP_MS);
  } else {
    lastTap = { x: tap.x, y: tap.y, t: now };
  }
  tap = null;
}
document.addEventListener("touchend", touchEnded, { passive: true });
document.addEventListener("touchcancel", function (e) {
  tap = lastTap = null;
  if (pinch && e.touches.length < 2) endPinch();
}, { passive: true });

var animation = null;

function finishAnimation() {
  if (!animation) return;
  var a = animation;
  animation = null;
  cancelAnimationFrame(a.frame);
  settle(a.to, a.at, a.y);
}

/* Shrinks the pages to [s] about the screen's [y], over [ms]: at once when that is nought. */
function zoomTo(s, y, ms) {
  finishAnimation();
  if (!slots.length) return;
  s = Math.min(1, Math.max(SHRINK_LEAST, s));
  var at = under(y);
  if (!ms) { settle(s, at, y); return; }
  pages.style.transformOrigin = "50% " + (y - pages.getBoundingClientRect().top) + "px";
  pages.classList.add("vw-moving");
  var from = shrink, start = null;
  animation = { to: s, at: at, y: y, frame: 0 };
  function step(t) {
    if (!animation) return;
    if (start === null) start = t;
    var f = Math.min(1, (t - start) / ms);
    /* Decelerating, and by ratio, so the pages grow at an even rate rather than fastest
       at the start, as DoubleTapZoom.kt has it */
    var eased = 1 - (1 - f) * (1 - f);
    pages.style.transform = "scale(" + Math.pow(s / from, eased) + ")";
    if (f >= 1) { finishAnimation(); return; }
    animation.frame = requestAnimationFrame(step);
  }
  animation.frame = requestAnimationFrame(step);
}

/* For the tests, and for a device test, which can only say what the page says. */
window.vwPdfShrink = function (s, y) {
  if (s === undefined) return shrink;
  zoomTo(s, y === undefined ? window.innerHeight / 2 : y, 0);
  return shrink;
};

/*
 * The WebView's text zoom, for the text layer to divide its type by (--vw-text-zoom in
 * pdf.html). Android sets it from the phone's font size, and it enlarges every font the page
 * sets. pdf.js measures each run on a canvas, which no zoom reaches, and stretches its span to
 * that width, so at a font size of 130% each run was drawn 30% wider than the words under it.
 * A search highlight then sat beside its word: in Persian, which runs right to left from the
 * span's far end, a whole word to the right. The page's own cards keep the phone's size.
 */
function readTextZoom() {
  var probe = document.createElement("div");
  probe.style.cssText = "position:absolute;visibility:hidden;font-size:100px";
  document.body.appendChild(probe);
  var zoom = parseFloat(getComputedStyle(probe).fontSize) / 100;
  probe.remove();
  if (zoom > 0 && isFinite(zoom) && zoom !== 1) {
    document.documentElement.style.setProperty("--vw-text-zoom", String(zoom));
  }
}
readTextZoom();

/*
 * Lay the words of a page over the picture of it.
 *
 * The viewport handed in here is at CSS scale, not the canvas's device scale; see
 * the note in draw() about why those are two numbers.
 *
 * A failure here is deliberately not a failure of the page. The picture is already
 * drawn and readable, and a document whose text cannot be extracted, which is most
 * scanned ones, should look exactly as it did before this feature existed rather
 * than showing an error over a page the reader can see perfectly well.
 */
function textLayerFor(slot, page, viewport) {
  /*
   * pdf.js reads this off the container to size the glyphs. It is --total-scale-factor
   * in 6.3.289; the older --scale-factor is not read by this build at all.
   *
   * Times userUnit, and that factor is easy to miss. PageViewport keeps .scale as the
   * number it was handed and multiplies by userUnit only inside its own matrix, so the
   * viewport's width and height carry the unit while its scale does not. pdf.js's own
   * stylesheet says the same thing the long way round, as
   * calc(var(--scale-factor) * var(--user-unit)). Left out, a document declaring a
   * UserUnit gets a text layer at the wrong size against a correctly sized picture.
   * It is rare, being for pages beyond 200 inches, but that is large-format drawings
   * and maps, which is exactly the kind of document this viewer gets handed.
   */
  slot.text.style.setProperty(
    "--total-scale-factor", String(viewport.scale * (viewport.userUnit || 1)));
  var layer = new pdfjsLib.TextLayer({
    textContentSource: embedFonts(page.streamTextContent()),
    container: slot.text,
    viewport: viewport
  });
  slot.layer = layer;
  return layer.render().then(function () {
    padRows(slot.text, layer.textDivs);
  }, function () {
    if (slot.layer === layer) slot.layer = null;
    slot.text.replaceChildren();
  });
}

/*
 * A note on what is deliberately not here.
 *
 * pdf.js's viewer bounds a stray selection with an .endOfContent block that it moves
 * next to the growing end of the range on every selectionchange, and this file carried
 * a port of it. It is gone, and the stylesheet rules for it with it, because padRows()
 * below leaves nothing for it to catch: measured across the two documents from issue
 * #22, hit boxes now cover 100% of a page and 96% of the worst illustrated one, where
 * that machinery existed only to answer the uncovered part.
 *
 * It was not free, either. It ran on every selectionchange, which a finger produces
 * about fifty times a second, and moved the block in every registered layer. When that
 * churn landed in a layer above the viewport, Chromium's scroll anchoring compensated
 * and jumped the view: traced at 3418 to 2899 in a single step. Guarding the churn made
 * it cheaper; removing it makes a drag touch the DOM not at all.
 *
 * If a document ever turns up that padRows() cannot cover, the symptom is a selection
 * that freezes at a constant length while the finger keeps moving, and the answer is to
 * find the hole rather than to put this back.
 */

/* Rows are sorted twice, so the comparators are shared rather than a fresh closure
   per row per page. */
function byTopThenLeft(a, b) { return a.top - b.top || a.left - b.left; }
function byLeft(a, b) { return a.left - b.left; }

/*
 * Pads the text layer's spans until they cover the whole page, so a finger dragging a
 * selection always has a word under it (#22). pdf.js sizes each span to its glyphs, which
 * left 36% of a page in no span, and a touch there resolves to one fixed position, so the
 * selection froze for seconds and then jumped. Padding with matching negative margins leaves
 * the glyphs where pdf.js put them, and the gap between two lines goes half to each, with no
 * cap, since a capped gap leaves its middle uncovered. The cost is that a selection highlight
 * fills the padded box, so a short line highlights full width.
 */
function padRows(container, divs) {
  var layer = container.getBoundingClientRect();
  /*
   * The three numbers this needs are already written down, so none of them is measured
   * back out of a computed style.
   *
   * pdf.js writes --scale-x and --rotate inline on each span and --min-font-size once
   * on the container; the stylesheet contract above says so. The transform it composes
   * is rotate() scaleX(--scale-x) scale(1 / --min-font-size), so a padding written here
   * is not rendered at the size it is written: sideways it is scaled by
   * --scale-x / --min-font-size, and not by --scale-x, and downwards by
   * 1 / --min-font-size. Measured on a phone, that is 1.3% for a min font size of
   * 1.013, and 8x on a WebView left at its default floor of 8. Every padding below is
   * therefore divided by the scale its own axis is drawn at.
   *
   * Getting the vertical half of that wrong is not cosmetic and is worth knowing the
   * shape of, because both symptoms look like something else. Padding that falls 1.3%
   * short leaves 1.3% of every gap uncovered, which is a hairline in a line gap and
   * 6.6px in the blank above a chapter opening, and an uncovered band is the freeze
   * this whole function exists to prevent. It also drags the words off the picture:
   * the margin below is in layout px and the padding it cancels is not, so the glyphs
   * come to rest 1.3% of the padding above where pdf.js put them, which is nothing on
   * an ordinary line and 4.7px on the one after a large gap.
   */
  var mfs = parseFloat(container.style.getPropertyValue("--min-font-size")) || 1;

  /*
   * Every distance below is measured on the page as it stands and written for the page
   * at its fitted width, times --vw-shrink, so the hit boxes follow a pinch that shrinks
   * the pages without this running again over every drawn page. The layer fills a box
   * laid out at that fraction of PAGE_WIDTH, so its own width says what the fraction is,
   * a pinch still in progress and scaling the column as a picture included.
   */
  var drawnAt = layer.width / PAGE_WIDTH || 1;
  function fitted(px) { return "calc(" + px.toFixed(2) + "px * var(--vw-shrink))"; }

  /* Read every rect before writing any padding. Interleaving the two makes the browser
     lay the page out again on each span, which on a dense page is hundreds of times. */
  var boxes = [];
  for (var i = 0; i < divs.length; i++) {
    var el = divs[i];
    /*
     * Rotated text is left alone, a span at a time rather than a page at a time.
     *
     * Everything below reads horizontal lines out of vertical positions: it groups
     * spans by their top and grows them down and sideways. On a rotated span that means
     * nothing and the padding would land across the words rather than around them. So
     * skip it, and let the rest of the page still be covered; a caption printed
     * sideways should not cost the page it sits on. A page rotated in its entirety ends
     * up padded nowhere, which is where this file was before any of it.
     */
    var rot = el.style.getPropertyValue("--rotate");
    if (rot && parseFloat(rot)) continue;
    var r = el.getBoundingClientRect();
    if (!r.height) continue;
    boxes.push({
      el: el, top: r.top, bottom: r.bottom, left: r.left, right: r.right,
      sx: (parseFloat(el.style.getPropertyValue("--scale-x")) || 1) / mfs
    });
  }
  if (boxes.length < 2) return;
  boxes.sort(byTopThenLeft);

  var rows = [];
  var row = null;
  for (var j = 0; j < boxes.length; j++) {
    var b = boxes[j];
    /* A new row when this span starts below the one before it. Two spans on the same
       line share a top to within rounding, so the tolerance is small and absolute, in
       the fitted page's px. */
    if (!row || b.top - row.top > 2 * drawnAt) {
      row = { top: b.top, bottom: b.bottom, items: [b] };
      rows.push(row);
    } else {
      row.bottom = Math.max(row.bottom, b.bottom);
      row.items.push(b);
    }
  }

  for (var v = 0; v < rows.length; v++) {
    var here = rows[v];
    /*
     * What this row owns, vertically. Half the gap to the row above and half the gap to
     * the row below, so a point in a gap joins whichever line it is nearer and no
     * single line grows a hit box the height of the space before a section heading.
     * The first row takes the page margin above it whole, having no row to share with,
     * and the last row takes the rest of the page.
     *
     * No cap: a cap leaves the middle of a large gap uncovered, which is the freeze
     * this exists to prevent, and it was measured failing at exactly that spot with a
     * cap of two line-heights.
     */
    var up = (v ? (here.top - rows[v - 1].bottom) / 2 : here.top - layer.top) / drawnAt;
    var floor = (v + 1 < rows.length)
      ? here.bottom + (rows[v + 1].top - here.bottom) / 2
      : layer.bottom;

    /* Left to right, so a gap belongs to the span before it, which is reading order and
       so the answer a reader expects when they land in one. Sorted in place: the rows
       were built off a top-then-left sort, so this is usually already true, but two
       spans a pixel apart vertically would come out of it the wrong way round. */
    var across = here.items;
    across.sort(byLeft);

    for (var x = 0; x < across.length; x++) {
      var it = across[x];
      var style = it.el.style;
      /* Down to its row's share of the gap, measured from this span's own bottom: a row
         can mix type sizes, a heading beside a caption, and padding each item the same
         amount from where it happens to end leaves a sliver under every short one. */
      var down = (floor - it.bottom) / drawnAt;
      if (down > 0.5) style.paddingBottom = fitted(down * mfs);
      if (up > 0.5) {
        style.paddingTop = fitted(up * mfs);
        /* Cancels the padding so the glyphs stay where pdf.js put them: the box top
           moves up by exactly what the padding pushes the text down by. Not scaled,
           where the padding above it is, because the two are in different spaces: a
           margin moves the box before the transform is applied, and the padding is
           measured inside a box the transform has already shrunk. */
        style.marginTop = fitted(-up);
      }
      /*
       * Sideways to the next span along the row, and to the page edge at its ends.
       *
       * Reaching only the two margins would leave every gap inside a row open: the
       * gutter between columns, the space between table cells, the white beside a
       * figure. And a heading reaches perhaps a sixth of the way across, so a finger
       * dragged down the middle of the column passes through the empty space beside it
       * and lands on nothing, which is why sections went on failing after paragraphs
       * were fixed.
       */
      var until = (x + 1 < across.length) ? across[x + 1].left : layer.right;
      var reach = (until - it.right) / drawnAt;
      if (reach > 1) style.paddingRight = fitted(reach / it.sx);
    }

    var first = across[0];
    var leftNeed = (first.left - layer.left) / drawnAt;
    if (leftNeed > 1) {
      first.el.style.paddingLeft = fitted(leftNeed / first.sx);
      first.el.style.marginLeft = fitted(-leftNeed);
    }
  }
}

/*
 * Scrolls the page when a selection handle is dragged to the edge of the screen. Chromium on
 * Android, Chrome included, doesn't scroll for a handle held at the bottom edge, so a reader
 * could select only as far as the fold. A handle drag sends no touch events, only
 * selectionchange, so each change steps the scroll by how far past the edge band the
 * selection's end has landed. No timer, because from here a finger held still and a lifted one
 * look the same, and a timer would scroll the page out from under the selection. The band is in
 * dp, divided by the visual viewport's scale, so it stays the same size under a pinch.
 */
var SELECT_EDGE_DP = 50;
var SELECT_EDGE_MIN = 10;
var SELECT_GAP_MS = 700;
var selectPrevY = null;
var selectPrevT = 0;

/* The extent's own rect, not the selection's. A selection's box spans everything
   between its two ends, so by the time any of this matters its bottom is most of the
   document away; a collapsed range at the focus is the end the finger is holding. */
function extentRect(sel) {
  try {
    var r = document.createRange();
    r.setStart(sel.focusNode, sel.focusOffset);
    r.collapse(true);
    var rects = r.getClientRects();
    if (rects.length) return rects[0];
    /* An extent resolved to an element rather than into a text node has no rect of its
       own. padRows() above is what makes that rare rather than the usual case. */
    if (sel.focusNode.nodeType === 1) return sel.focusNode.getBoundingClientRect();
  } catch (e) {}
  return null;
}

function selectionEdgeScroll() {
  var sel = window.getSelection();
  if (!sel || !sel.rangeCount || sel.isCollapsed) { selectPrevY = null; return; }
  var rect = extentRect(sel);
  if (!rect) { selectPrevY = null; return; }
  var vv = window.visualViewport;
  if (!vv || !vv.height) return;

  /* In document coordinates, so that comparing against the previous event survives the
     scrolling this function itself does between the two. */
  var y = rect.bottom + window.scrollY;
  var now = performance.now();
  var dragging = selectPrevY !== null && now - selectPrevT < SELECT_GAP_MS;
  var travel = dragging ? y - selectPrevY : 0;
  selectPrevY = y;
  selectPrevT = now;
  /* A long press is one selectionchange standing on its own, and a reader who has just
     picked a word out near the bottom of the screen has not asked for the page to move.
     The couple of px of tolerance below are for a finger pushing at an edge without
     travelling: the direction it last moved is what says which edge it wants. */
  if (!dragging) return;

  var band = SELECT_EDGE_DP / vv.scale;
  var low = vv.offsetTop + vv.height - band;
  var high = vv.offsetTop + band;
  if (travel >= -2 && rect.bottom > low) {
    window.scrollBy(0, Math.min(Math.max(rect.bottom - low, SELECT_EDGE_MIN), band));
  } else if (travel <= 2 && rect.top < high) {
    window.scrollBy(0, -Math.min(Math.max(high - rect.top, SELECT_EDGE_MIN), band));
  }
}

document.addEventListener("selectionchange", selectionEdgeScroll);

/*
 * Points the text layer at the fonts the page was painted with, so a search highlight covers
 * the whole word. pdf.js lays text out in "serif" and stretches each run to the width the PDF
 * asks for, which is right for the run and wrong for the letters inside it: "markerpage"
 * highlighted as "markerpag". This edits each chunk's styles as it streams in, before its spans
 * are built, since changing a built span's font would leave its --scale-x measured against the
 * wrong one, and streams rather than reads the page whole so its spans are still built a hundred
 * at a time. A run with no embedded face, as in a scan, a Type3 font or a substituted Helvetica,
 * keeps pdf.js's default.
 */
function embedFonts(stream) {
  /* Degrading is structural rather than argued: with no TransformStream nothing is
     rewritten and every page keeps the font it has today. Chromium has had it since
     67 and pdf.js already requires 125, so this will not fire. */
  if (typeof TransformStream !== "function" || !stream.pipeThrough) return stream;

  /* One walk per page rather than one per chunk. Every font this page uses is
     registered by now: the canvas render this is downstream of could not have
     finished otherwise. Deferred, so a page carrying nothing but artwork never walks
     the set at all. */
  var families = null;

  return stream.pipeThrough(new TransformStream({
    transform: function (chunk, controller) {
      var styles = chunk && chunk.styles;
      for (var key in styles) {
        var style = styles[key];
        if (!style || !style.fontFamily) continue;
        if (!families) families = loadedFamilies();
        if (!families[key]) continue;
        /*
         * Quoted, and the generic left behind it. Both halves earn their place.
         *
         * Quoted because pdf.js splices this into a canvas font shorthand, and an
         * unparseable shorthand there is not an error, it is ignored. pdf.js caches
         * the font it believes it set on a measurement canvas shared by the whole
         * document, so one name needing an escape would quietly measure that page and
         * every page after it in whatever was set last. Its own names cannot trip
         * that; quoting means no name it invents later can either.
         *
         * The generic stays as a real second choice, because a subset font is missing
         * characters constantly and one it does not carry should land on the font
         * that measures it today rather than on the platform's last resort. pdf.js
         * reads the family back off the span to build its measurement, so the string
         * that measures and the string that paints are the same string, and --scale-x
         * stays honest whichever of the two ends up drawing a given character.
         */
        style.fontFamily = '"' + key + '", ' + style.fontFamily;
      }
      controller.enqueue(chunk);
    }
  }));
}

/*
 * Which font families the browser has actually been handed, by name.
 *
 * document.fonts.check() reads like the test for this and is not one. Chromium
 * answers true for a family nobody ever registered, because the question check()
 * answers is whether the text can be drawn, and once fallback is allowed the answer
 * is always yes. Looking at what is in the set is the only honest test.
 *
 * status is checked because a face still loading is a family the browser will
 * substitute for, which is this bug rather than a smaller version of it. It cannot be
 * one of this page's own, but MAX_IN_FLIGHT lets two other pages rasterise alongside
 * and their fonts pass through this set on the way in.
 */
function loadedFamilies() {
  /* Null prototype: these keys come from another library, and a font called
     "constructor" should be a lookup rather than a surprise. */
  var families = Object.create(null);
  var set = document.fonts;
  if (!set || typeof set.forEach !== "function") return families;
  set.forEach(function (face) {
    if (face.status !== "loaded") return;
    /* FontFace.family is a CSS value that has been through the CSSOM, so a name the
       engine decided needed quoting comes back carrying the quotes. pdf.js's names
       never do, but comparing a quoted string against an unquoted key would read as a
       miss, and a miss here is the quiet kind of wrong: the page keeps today's font
       and nothing says why. */
    var name = String(face.family || "").replace(/^(["'])([\s\S]*)\1$/, "$2");
    if (name) families[name] = true;
  });
  return families;
}

/*
 * The text of a page, flattened into one string, with a way back to where each
 * piece of it came from.
 *
 * pdf.js hands out text in runs, and the runs carry the spacing of the typeset page
 * rather than of a sentence: a word can arrive split across two runs, and the gap
 * between two runs may be a space, a newline or nothing at all. Searching the raw
 * concatenation therefore misses anything that straddles a run.
 *
 * So the runs are joined with a single space between them and every stretch of
 * whitespace inside them is collapsed to one, which is what makes "Baker Street"
 * findable whichever way the typesetter broke it. "map" records, for each run, where
 * it landed in the joined string, which is what turns a match back into a Range.
 */
function flatten(runs) {
  var text = "";
  var map = [];
  for (var i = 0; i < runs.length; i++) {
    var run = String(runs[i] == null ? "" : runs[i]);
    if (!run) { map.push(null); continue; }
    if (text) text += " ";
    var cleaned = run.replace(/\s+/g, " ");
    map.push({ at: text.length, len: cleaned.length, run: i });
    text += cleaned;
  }
  return { text: text, map: map };
}

/*
 * Read the whole document's text, a page at a time, without holding onto it.
 *
 * This is the one place that touches every page, and it is why it does not run until
 * somebody actually searches: asking for all of them at open costs a round trip per
 * page on a ranged load, which is the whole reason page 1's shape stands in for the
 * rest.
 *
 * The cleanup() call is not tidiness. getPage() puts the page in pdf.js's own cache
 * and leaves it there for as long as the document is open, so walking 357 pages to
 * read their text would leave 357 page objects alive holding whatever they decoded.
 * That is the same leak blank() exists to close, arrived at from the other end, and
 * it would not show up on anything short enough to test by hand. Pages the band is
 * currently holding are left alone: blank() owns those, and cleaning one mid-render
 * would pull the artwork out from under a page being drawn.
 */
function buildIndex() {
  /* A reader can have the search box open before the document has finished
     opening, and on a slow file that is a real gap rather than a theoretical one.
     The query is kept either way; the open chain starts the index once doc exists. */
  if (!doc) return;
  if (finding.pages || finding.building) return;
  finding.building = true;
  finding.pages = new Array(doc.numPages);
  finding.built = 0;

  var next = 1;
  function step() {
    if (next > doc.numPages) {
      finding.building = false;
      report();
      return;
    }
    var n = next++;
    doc.getPage(n).then(function (page) {
      return page.getTextContent().then(function (content) {
        var runs = content.items.map(function (it) {
          return typeof it.str === "string" ? it.str : "";
        });
        finding.pages[n - 1] = flatten(runs).text;
        if (slots[n - 1].state === "blank") page.cleanup();
      });
    }).catch(function () {
      /* An unreadable page is an empty one for searching. Losing the rest of the
         document because one page will not parse would be the worse answer. */
      finding.pages[n - 1] = "";
    }).then(function () {
      finding.built = n;
      /* Results grow as the index does rather than the box waiting for the end of a
         long document, so the count climbs while it works. Only the page just read
         is scanned; see the note on rerun() about why not all of them. */
      if (finding.query) {
        var added = scanPage(n);
        if (finding.active < 0 && finding.matches.length) {
          /* First hit in the document: go to it, the way a search that had the whole
             index would have done immediately. */
          stepTo(0);
        } else {
          if (added && slots[n - 1].state === "drawn") paint();
          report();
        }
      }
      step();
    });
  }
  step();
}

/* Case-insensitive, like the native find the other viewers get. Chromium's find does
   its own Unicode folding beyond this, so counts on the same query can differ a
   little between a PDF and a DOCX. That is worth knowing and not worth matching. */
function foldCase(s) { return s.toLowerCase(); }

/* Every occurrence of the query on one page, appended. Pages are indexed in order,
   so appending keeps finding.matches in document order without a sort. */
function scanPage(n) {
  var text = finding.pages[n - 1];
  if (!text) return 0;
  var q = foldCase(finding.query);
  if (!q) return 0;
  var hay = foldCase(text);
  var found = 0;
  var at = hay.indexOf(q);
  while (at !== -1) {
    finding.matches.push({ page: n, at: at, len: q.length });
    found++;
    at = hay.indexOf(q, at + q.length);
  }
  return found;
}

/*
 * Start the current query again from nothing, over every page read so far.
 *
 * Only for a query that has changed. While the index is being read it is scanPage()
 * that runs, once per page as it arrives, because rescanning the whole document
 * after every page of it is quadratic: on the 357-page document that is 357 passes
 * over a corpus growing to 1.4 MB, which is half a gigabyte of scanning to learn
 * what one page added.
 */
function rerun() {
  finding.matches = [];
  if (finding.query && finding.pages) {
    for (var n = 1; n <= finding.pages.length; n++) scanPage(n);
  }
  if (finding.active >= finding.matches.length) {
    finding.active = finding.matches.length ? 0 : -1;
  }
  if (finding.active < 0 && finding.matches.length) finding.active = 0;
  paint();
  report();
}

/*
 * Put the highlights on the pages that are currently showing.
 *
 * Only drawn pages have anything to highlight, so this covers the band and draw()
 * calls markSlot again for a page that comes back later. The registry is rebuilt
 * whole each time rather than edited, because a Range whose nodes have been thrown
 * away is not something to keep.
 */
var hitRanges = [];
var activeRanges = [];

function paint() {
  if (!window.CSS || !CSS.highlights) return;
  hitRanges = [];
  activeRanges = [];
  for (var i = 0; i < slots.length; i++) {
    if (slots[i].state === "drawn") markSlot(slots[i]);
  }
  flush();
}

/* Registered in one go. CSS.highlights is a live registry, so setting a name
   replaces what was under it and deleting is only needed when nothing is left. */
function flush() {
  if (!window.CSS || !CSS.highlights) return;
  if (hitRanges.length) CSS.highlights.set("vw-find", new Highlight(...hitRanges));
  else CSS.highlights.delete("vw-find");
  if (activeRanges.length) {
    CSS.highlights.set("vw-find-active", new Highlight(activeRanges[0]));
  } else {
    CSS.highlights.delete("vw-find-active");
  }
}

/*
 * Turn this page's matches into Ranges over the spans pdf.js laid down.
 *
 * The offsets in finding.matches are into the text read from the file, and the spans
 * here are what the text layer actually built, so the two are re-derived from the
 * layer's own arrays rather than assumed to line up. Where they do not, the page
 * simply carries no highlight: the reader still gets scrolled to the right page,
 * which is most of the value, instead of an exception in the middle of a render.
 */
function markSlot(slot) {
  if (!finding.query || !slot.layer) return;
  var divs = slot.layer.textDivs;
  var strs = slot.layer.textContentItemsStr;
  if (!divs || !strs || !divs.length) return;

  var flat = flatten(strs);
  var hay = foldCase(flat.text);
  var q = foldCase(finding.query);
  if (!q) return;

  var activeMatch = finding.matches[finding.active];
  var nth = 0;
  var at = hay.indexOf(q);
  while (at !== -1) {
    var r = rangeFor(divs, flat.map, at, q.length);
    if (r) {
      /* The active one is identified by its position among this page's hits, which
         is the same order rerun() pushed them in. */
      if (activeMatch && activeMatch.page === slot.n &&
          nthOnPage(activeMatch) === nth) {
        activeRanges.push(r);
      } else {
        hitRanges.push(r);
      }
    }
    nth++;
    at = hay.indexOf(q, at + q.length);
  }
}

/* Which hit this is within its own page, counted the way rerun() ordered them. */
function nthOnPage(match) {
  var n = 0;
  for (var i = 0; i < finding.matches.length; i++) {
    var m = finding.matches[i];
    if (m.page !== match.page) continue;
    if (m === match) return n;
    n++;
  }
  return -1;
}

/* A Range covering [at, at+len) of the flattened text, across however many spans
   that stretch happens to span. */
function rangeFor(divs, map, at, len) {
  var end = at + len;
  var range = document.createRange();
  var started = false;
  for (var i = 0; i < map.length; i++) {
    var piece = map[i];
    if (!piece) continue;
    var pieceEnd = piece.at + piece.len;
    if (pieceEnd <= at || piece.at >= end) continue;
    var node = divs[piece.run] && divs[piece.run].firstChild;
    if (!node) continue;
    /* The span holds the run as pdf.js received it; the map is into the collapsed
       copy. They agree unless the run carried runs of whitespace, and clamping keeps
       a disagreement to a highlight of the wrong width rather than a thrown error. */
    var max = node.length;
    if (!started) {
      range.setStart(node, Math.min(Math.max(at - piece.at, 0), max));
      started = true;
    }
    range.setEnd(node, Math.min(Math.max(end - piece.at, 0), max));
  }
  return started ? range : null;
}

/*
 * Move to a match and bring it on screen.
 *
 * The page it is on may not be drawn: a search reaches the whole document while the
 * band holds about eleven pages of it. Asking for it goes through the same want()
 * and pump() every other page does, so a match on page 300 is fetched by the queue
 * that is already there rather than by a second path that can also draw pages.
 *
 * That makes arriving a two-step business. The page is scrolled to immediately,
 * because its box is already the right size and standing in the right place even
 * when empty, and the highlight lands when the render finishes and draw() paints
 * again.
 */
function stepTo(i) {
  var n = finding.matches.length;
  if (!n) { finding.active = -1; paint(); report(); return; }
  finding.active = ((i % n) + n) % n;
  var slot = slots[finding.matches[finding.active].page - 1];
  slot.box.scrollIntoView({ block: "center" });
  want(slot);
  pump();
  paint();
  reveal();
  report();
}

/* Centre the active hit, once there is one to centre. Scrolling to the page is done
   by the caller and is what happens when this cannot: a page whose text would not
   extract still gets looked at, rather than the reader being left where they were
   with the counter claiming to have moved. */
function reveal() {
  if (!activeRanges.length) return;
  var r = activeRanges[0].getBoundingClientRect();
  if (!r.height && !r.width) return;
  window.scrollBy(0, r.top - window.innerHeight / 2);
}

/*
 * The search bar lives in Kotlin, so the counter it shows has to be told.
 *
 * Three integers, space separated: which hit is current counting from one, how many
 * there are, and whether the index has finished being read. Nothing else crosses,
 * in either direction, and nothing on this side is ever evaluated. See the note
 * beside vwAskPassword about why this page has no JavascriptInterface; a port that
 * carries three numbers is a different thing from an object with methods on it.
 */
var searchPort = null;

function report() {
  if (!searchPort) return;
  var total = finding.matches.length;
  /* Position counts from one, and is zero when there is nothing to be at. Sending
     1 of 0 would have the counter read "1/0" and TalkBack say "Match 1 of 0". */
  var at = total && finding.active >= 0 ? finding.active + 1 : 0;
  var done = finding.pages && !finding.building ? 1 : 0;
  searchPort.postMessage(at + " " + total + " " + done);
}

/*
 * Which page sits under the middle of the screen, for the readout in the toolbar.
 *
 * "page <n> <total>", tagged, unlike report()'s three bare numbers. The two messages
 * would otherwise be the same shape, so the Kotlin side reads the tag first and the
 * search message it already understood is left exactly as it was.
 *
 * Measured against the visual viewport, and that is the whole reason this is a scroll
 * handler rather than a third IntersectionObserver beside the two below, which is what
 * the rest of this file would lead you to write. An observer's root rect is the layout
 * viewport by specification, and is deliberately unaffected by pinch zoom on the
 * grounds that zooming is a magnifying glass and must not change layout. This page
 * lays out at a fixed 980 CSS px that the WebView zooms to fit, see the note in the
 * head, so an observer would confidently name the page under the middle of a viewport
 * the reader cannot see. distance() lives with that same approximation because
 * ordering renders by it is close enough. A number on screen is not.
 */
var lastPageSent = 0;

/* Whether the document asked for a password. Android's printing can't open such a file,
   so the app says so rather than send it there. */
var locked = false;

function reportLocked() {
  if (searchPort && locked) searchPort.postMessage("locked");
}

function reportPage() {
  /* Not before PAGE_PITCH is set, which is when the boxes take the document's own shape
     and a reopened document is moved to its page. Before that the answer is page 1 of a
     column of placeholders, and a document closed in that moment would be saved there. */
  if (!searchPort || !doc || doc.numPages < 2 || !slots.length || !PAGE_PITCH) return;
  var vv = window.visualViewport;
  /* Zero height happens once, between the port arriving and the first layout. There is
     no support fallback below this: visualViewport has existed since Chromium 61 and
     this file refuses to run below 125. */
  if (!vv || !vv.height) return;
  /* A page bringIntoView() put on screen is named until the reader moves; see there. */
  if (askedFor && Math.abs(vv.pageTop - askedFor.top) > 1) askedFor = null;
  var n = askedFor ? askedFor.n : pageOnScreen(vv);
  if (n === lastPageSent) return;
  lastPageSent = n;
  searchPort.postMessage("page " + n + " " + doc.numPages);
}

/*
 * The page under the middle of the screen, counting from 1, except at the two ends.
 *
 * At an end the middle is the wrong thing to ask about. A page shorter than half the
 * screen is never under it while that page is the first or the last thing showing, so on
 * an upright phone a landscape document or a slide deck read 2 as it opened and could
 * never read its last page. Measured in Chromium at 980 by 1600 on twelve A4 landscape
 * pages: 2 at the top, 11 at the end. Scrolled to an end, the reader is on the page at
 * that end whatever its shape. A pixel of slack both ways, since pageTop is fractional
 * while pinched.
 */
function pageOnScreen(vv) {
  if (vv.pageTop <= 1) return 1;
  if (vv.pageTop + vv.height >= document.documentElement.scrollHeight - 1) {
    return slots.length;
  }
  var middle = vv.pageTop + vv.height / 2;

  /* Binary search rather than a walk: nine reads of an already laid out box on a
     357-page document instead of three hundred and fifty seven. The rect plus scrollY
     rather than offsetTop, because offsetTop is only document-absolute here for as long
     as #pages and body both stay static and unpositioned, and nothing would say so if
     one of them stopped. */
  var lo = 0, hi = slots.length - 1, at = 0;
  while (lo <= hi) {
    var mid = (lo + hi) >> 1;
    if (slots[mid].box.getBoundingClientRect().top + window.scrollY <= middle) {
      at = mid; lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return at + 1;
}

/* The page a jump or a reopen last put on screen and where that left the screen, or null
   once the reader has moved from there. */
var askedFor = null;

/*
 * Put a page on screen where the readout will name it.
 *
 * At the top of the screen, since a jump means "start here", unless the page is too short
 * for the middle of the screen to fall on it from there, and then in the middle. The
 * readout names the page under the middle, so a short page put at the top read as the one
 * after it: Go to page 5 on a slide deck said 6. For a reopened document that would be
 * worse than a wrong number, because what the readout says is what gets saved, and the
 * document would move one page further on at every open.
 *
 * Near either end there may be nowhere to scroll a page to that puts it under the middle.
 * Ask for the second-last slide and the last one is on screen beside it, at the bottom of
 * the document, which pageOnScreen() calls the last page. So the page asked for is kept
 * along with where it left the screen, and named until the reader moves.
 */
function bringIntoView(slot) {
  var vv = window.visualViewport;
  var shortPage = !!vv && slot.box.getBoundingClientRect().height <= vv.height / 2;
  slot.box.scrollIntoView({ block: shortPage ? "center" : "start" });
  askedFor = vv ? { n: slot.n, top: vv.pageTop } : null;
}

/*
 * bringIntoView(), with the page and the one before it at their own shapes first.
 *
 * Until a page is drawn its box has page 1's shape, which in a document of mixed pages is
 * some other page's, and bringIntoView decides top or middle from the box. A landscape
 * page after a portrait cover was put at the top as if it were tall, and the middle of the
 * screen was on the page after it: that is what the readout named, what Kotlin saved, and
 * where the document reopened, a page further on every time. The page before matters too.
 * It is the one showing above a page put in the middle, and when it was drawn and shrank to
 * its own shape, everything below it moved up with it.
 */
function place(slot) {
  var unshaped = [slot.n - 1, slot.n].filter(function (n) {
    return n >= 1 && !slots[n - 1].box.style.aspectRatio;
  });
  return Promise.all(unshaped.map(function (n) {
    return doc.getPage(n).then(function (page) {
      shape(slots[n - 1].box, page.getViewport({ scale: 1 }));
    }, function () {
      /* A page that will not load keeps the stand-in shape, as it does when drawn */
    });
  })).then(function () { bringIntoView(slot); });
}

/* One measurement per frame, however many events arrive in it. */
var pageRaf = 0;
function schedulePageReport() {
  if (pageRaf) return;
  pageRaf = requestAnimationFrame(function () { pageRaf = 0; reportPage(); });
}

/*
 * Two things want to hear that the reader has moved, and they want to hear it on
 * different terms, so one handler tells both rather than each registering its own.
 *
 * The counter has to be right during the gesture, so it is measured once a frame. A tile
 * drawn during a pinch is thrown away by the next frame of that pinch, so it waits for
 * the hand to stop.
 *
 * This is also the only place the tiles are driven from, and it is deliberately beside
 * reportPage() rather than inside it. That function gives up at once on a document of
 * one page, and both files reported in issue #20 are single-page maps: put the tile work
 * behind that guard and the fix does nothing whatever on the documents it was written
 * for.
 */
function moved() {
  schedulePageReport();
  scheduleRefine();
}

window.addEventListener("scroll", moved, { passive: true });
if (window.visualViewport) {
  /* Both of these and not just the window scroll: moving a WebView while the reader is
     pinched in scrolls the visual viewport, which need not fire a window scroll at all. */
  window.visualViewport.addEventListener("scroll", moved, { passive: true });
  window.visualViewport.addEventListener("resize", moved, { passive: true });
}

window.addEventListener("message", function (e) {
  /* The only message this page ever receives is the one handing it the port, sent
     by ViewerActivity once the document is up. Anything else is not ours. */
  if (!e.ports || !e.ports.length || searchPort) return;
  searchPort = e.ports[0];
  searchPort.onmessage = function (m) {
    /* An error would reach window.onerror, which puts the error card over a document that is fine */
    try { onCommand(String(m.data || "")); } catch (err) { report(); }
  };
  searchPort.start();
  /* The document may well have opened before the port arrived, in which case the first
     report went nowhere and no other would follow until the reader scrolled. The same
     reason openSearchChannel replays a query typed before there was anywhere to send it. */
  reportPage();
  reportLocked();
  reportShrunk();
});

/* One letter of command, then the rest is payload. A query is arbitrary text and
   deliberately never parsed as anything but text. */
function onCommand(msg) {
  /* The title bar's height and whereabouts, which app.js keeps for every page */
  if (vwBarSaid(msg)) return;
  var verb = msg.charAt(0);
  if (verb === "q") {
    var q = msg.slice(1);
    if (q === finding.query) return;
    finding.query = q;
    finding.active = q ? 0 : -1;
    if (!q) { finding.matches = []; paint(); report(); return; }
    if (!finding.pages) { buildIndex(); report(); return; }
    rerun();
    stepTo(0);
  } else if (verb === "n") {
    stepTo(finding.active + 1);
  } else if (verb === "p") {
    stepTo(finding.active - 1);
  } else if (verb === "i") {
    /* Night mode on or off. The app holds the state, so nothing is reported back. */
    setNight(msg.charAt(1) === "1");
  } else if (verb === "c") {
    finding.query = "";
    finding.matches = [];
    finding.active = -1;
    paint();
    report();
  } else if (verb === "g") {
    /*
     * Go to a page, put at the top wherever it is tall enough, since a jump means "start
     * here" where a search hit is centred. Instant, because a smooth scroll would ask for
     * every page on the way past to be drawn, which MAX_IN_FLIGHT and DRAW_PAGES are there
     * to stop. The bounds are checked again, since a number off the port is only text.
     */
    var n = Math.floor(Number(msg.slice(1)));
    if (!doc || !slots.length || !(n >= 1 && n <= slots.length)) return;
    var target = slots[n - 1];
    place(target).then(function () {
      want(target);
      pump();
      reportPage();
      /* A jump made while pinched in lands somewhere else on a page that is drawn but not
         yet sharp here, and moves nothing that would fire a scroll to say so. */
      scheduleRefine();
    });
  }
}

/*
 * Asks for the password of an encrypted document, then hands it back to pdf.js.
 *
 * pdf.js stops mid-parse to ask and carries on from where it stopped once
 * answered, so a password costs one round trip through this function and no
 * re-read of the file. A wrong answer comes straight back here with
 * INCORRECT_PASSWORD rather than failing the load, which is what makes retrying
 * free.
 *
 * The prompt is drawn in the page rather than as a native dialog because Gander
 * has no JavascriptInterface in either direction, and that absence is most of
 * what keeps an untrusted document from reaching the app. GrapheneOS's PdfViewer
 * answers the same question over a bridge; this is the cheaper half of that
 * trade, not a worse version of it.
 *
 * There is no cancel. Back leaves the viewer, which is what a reader without the
 * password would do anyway, and a cancel button would only trade that for an
 * error card saying the same thing.
 */
function vwAskPassword(updatePassword, reason) {
  locked = true;
  reportLocked();
  var el = document.getElementById("vw-status");
  if (!el) {
    el = document.createElement("div");
    el.id = "vw-status";
    document.body.insertBefore(el, document.body.firstChild);
  }
  el.className = "vw-status vw-ask";
  el.style.display = "";
  el.innerHTML = "";

  var title = document.createElement("div");
  title.className = "vw-ask-title";
  title.textContent = "This PDF is password protected";
  el.appendChild(title);

  /*
   * The retry line is a node of its own rather than a rewrite of the label, and
   * it carries role=alert.
   *
   * An alert is announced when it arrives, which is the only thing that works
   * here: this card is built from nothing on every ask, so a live region would
   * turn up already holding its text with no change for a screen reader to
   * notice. Compare the search counter in ViewerActivity, which is a live region
   * done the other way round, because that element stays put and only its text
   * moves.
   *
   * It also names the field through aria-describedby, so the message is read a
   * second time when focus lands below. Belt and braces on purpose: which of the
   * two a given reader hears depends on their screen reader, and hearing it twice
   * costs less than not hearing it.
   */
  var retryNote = null;
  if (reason === pdfjsLib.PasswordResponses.INCORRECT_PASSWORD) {
    retryNote = document.createElement("div");
    retryNote.id = "vw-pw-alert";
    retryNote.className = "vw-ask-alert";
    retryNote.setAttribute("role", "alert");
    retryNote.textContent = "That password did not work.";
    el.appendChild(retryNote);
  }

  var label = document.createElement("label");
  label.className = "vw-ask-label";
  label.htmlFor = "vw-pw";
  label.textContent = "Enter its password to open it.";
  el.appendChild(label);

  /* A form, not a bare button, so the on-screen keyboard's Go key submits too. */
  var row = document.createElement("form");
  row.className = "vw-ask-row";

  var input = document.createElement("input");
  input.type = "password";
  input.id = "vw-pw";
  input.className = "vw-ask-input";
  if (retryNote) input.setAttribute("aria-describedby", retryNote.id);
  // A document password is not a site login; nothing should offer to save it.
  input.autocomplete = "off";
  input.setAttribute("autocapitalize", "none");
  input.setAttribute("autocorrect", "off");
  input.spellcheck = false;

  var btn = document.createElement("button");
  btn.type = "submit";
  btn.className = "vw-ask-btn";
  btn.textContent = "Open";

  row.appendChild(input);
  row.appendChild(btn);
  el.appendChild(row);

  row.addEventListener("submit", function (e) {
    e.preventDefault();
    if (!input.value) {
      input.focus();
      return;
    }
    var pw = input.value;
    /* Drop the answer out of the DOM on the way past, and put the status line
       back so that decrypting a large document does not read as a dead tap. */
    input.value = "";
    vwStatus("Opening document…");
    updatePassword(pw);
  });

  /* Focus, and not a keyboard. WebView raises the IME only for a field focused
     by a gesture, and this runs from pdf.js's callback, so the reader still taps
     to type either way. What it does buy is a screen reader landing on the field
     and reading the label, and the alert with it, without being sent looking. */
  input.focus();
}

/*
 * getDocument hands back a loading task and the password callback lives on that
 * task, so chaining .promise straight off the call throws away the only object
 * that could have carried it. That was the whole of this bug: onPassword could
 * never fire, and an encrypted PDF fell through to the bottom .catch to show its
 * reader a raw PasswordException. Both ways in below go past here.
 */
function vwUnlockable(task) {
  task.onPassword = vwAskPassword;
  return task.promise;
}

/*
 * Absolute URLs for the CMap tables and the wasm image decoders, which both fail silently when
 * missing: CJK text vanishes (#21), and JPEG 2000 and JBIG2 images are left out (#24). Absolute
 * because pdf.js has its worker fetch them itself once standardFontDataUrl is set as well, since
 * Gander passes no useWorkerFetch, and the worker resolves a relative path against lib/:
 * measured that way, cjk.pdf drew none of its characters. document.baseURI suits both the app
 * and the test server.
 */
function vwAssetUrl(path) {
  return new URL(path, document.baseURI).href;
}

function vwWithAssets(opts) {
  opts.cMapUrl = vwAssetUrl("./lib/cmaps/");
  opts.cMapPacked = true;
  opts.wasmUrl = vwAssetUrl("./lib/wasm/");
  return opts;
}

/*
 * Two ways in. Small documents are read whole, which is a single request. Large
 * ones are range-requested so pdf.js pulls only what it is about to draw instead
 * of holding the entire file in memory for as long as it is open.
 *
 * Which one is used is decided on the Kotlin side and passed in, so the headers
 * being served and the loader chosen here cannot drift apart.
 */
function vwOpenDoc() {
  return vwParams.get("ranged") !== "1"
    ? vwFetchDoc("buffer").then(function (buf) {
        return vwUnlockable(pdfjsLib.getDocument(vwWithAssets({ data: buf })));
      })
    : vwUnlockable(pdfjsLib.getDocument(vwWithAssets({
        url: vwDocUrl(),
        rangeChunkSize: 65536,
        // Both of these are needed, and they are not the same switch.
        // disableAutoFetch stops pdf.js pulling chunks nothing has asked for.
        // disableStream stops the full-file request it opens first to find out
        // whether ranges are available at all. Leave that second one out and the
        // discovery request runs to completion, so the whole document is read
        // regardless and the range requests on top of it are pure extra work.
        // Measured against a 20 MB file: the full request delivered 100% of the
        // bytes without it, and under 5% before pdf.js hung up with it.
        disableAutoFetch: true,
        disableStream: true
      })));
}

/*
 * What to say when a document cannot be opened at all.
 *
 * Errors coming back from the worker are rebuilt into named classes on this side,
 * and anything pdf.js has no class for arrives as UnknownErrorException still
 * carrying the worker's own wording. So the name is worth reading and the message
 * is not: "unsupported encryption algorithm" is a true sentence about a file and
 * a useless one to hand somebody holding a phone.
 *
 * Encryption is the case this exists for. onPassword above covers the standard
 * security handler, which is very nearly every protected PDF in circulation, and
 * what it does not cover used to fall through to the raw exception text.
 *
 * The last line keeps that raw text deliberately. Once the name is none of these
 * there is nothing truthful left to say about the cause, and the string is the
 * only thing a bug report could be built out of.
 */
function vwFailure(e) {
  /* Read before the name, because every encryption failure is a plain FormatError
     in the worker and reaches here unrecognised, with only its wording to go on.
     There are three, and they are not all worded alike: "unknown encryption
     method" for a security handler that is not the standard one, "unsupported
     encryption algorithm" for a /V outside 1, 2, 4 and 5, and "Unknown crypto
     method" for a crypt filter pdf.js has no cipher for. That last one is why
     this matches crypto as well; it is also the only one that arrives after a
     password has already been accepted, since the key is checked before the
     cipher is built. Matching wording is brittle in general and tolerable against
     a pdf.js that is vendored and pinned; if it ever moves, the fallback below is
     already the right answer. */
  if (/encryption|crypto/i.test(String(e && e.message))) {
    return [
      "This PDF is protected in a way Gander cannot open",
      "It is encrypted, but not with the password scheme its renderer reads, so " +
      "there is nothing you could type here that would open it. Every other " +
      "format Gander opens still works."
    ];
  }
  switch (e && e.name) {
    case "PasswordException":
      return [
        "This PDF could not be unlocked",
        "The password was not accepted. If the file opens elsewhere, its " +
        "protection is of a kind Gander cannot read yet."
      ];
    case "InvalidPDFException":
      return [
        "This file is not a PDF that can be read",
        "It is either damaged or not a PDF at all, whatever it happens to be " +
        "named. Every other format Gander opens still works."
      ];
    case "ResponseException":
      return [
        "This PDF could not be read to the end",
        "Gander lost access to the file part way through loading it. Opening it " +
        "again usually settles this."
      ];
    default:
      return ["Could not render this PDF", String(e)];
  }
}

/*
 * Two bands rather than one.
 *
 * A page is drawn when it reaches the inner band and released only when it falls
 * outside the wider one. The gap between them is what stops a reader who scrolls
 * down a screen and back up again watching the page they just left get thrown away
 * and rebuilt. With a single band every small change of direction costs a redraw.
 *
 * Both bands are in pixels off PAGE_PITCH rather than percentages of the viewport,
 * for the reason set out beside DRAW_PAGES.
 *
 * Neither observer unobserves anything. The first version of this dropped each
 * target as it fired, which was how it guaranteed a page was drawn once, and it also
 * meant a page leaving the band was never heard about again. Being told when a page
 * goes away is the whole basis of cancelling and releasing, so the guarantee has
 * moved into the slot state instead: want() ignores anything not blank.
 *
 * Both are built again when a pinch settles the pages at another width. A margin is
 * fixed when an observer is made, and the bands are counted in pages as the pages are
 * drawn now: at a quarter of the width a page is a quarter as tall, and a band left in
 * fitted pages would hold four times as many of them. Every target is reported once as
 * it is observed, so the new pair wants what is in the new band and releases what is
 * outside it with nothing else having to walk the slots.
 */
var draws = null;
var keeps = null;

function observePages() {
  if (draws) draws.disconnect();
  if (keeps) keeps.disconnect();
  var pitch = PAGE_PITCH * shrink;

  draws = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (e.isIntersecting) want(e.target._slot); else unwant(e.target._slot);
    });
    pump();
  }, { rootMargin: Math.round(DRAW_PAGES * pitch) + "px 0px" });

  keeps = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (!e.isIntersecting) release(e.target._slot);
    });
  }, { rootMargin: Math.round(KEEP_PAGES * pitch) + "px 0px" });

  slots.forEach(function (slot) {
    draws.observe(slot.box);
    keeps.observe(slot.box);
  });
}

/* Skipped entirely below the pdf.js floor. The card is already on screen from the
   classic guard above, so reading the document would only end in vwStatusDone
   hiding the very element that card lives in. A function rather than a bare
   promise so nothing is fetched just by evaluating this. */
if (!vwPdfBlocked) {
  vwOpenDoc()
    .then(function (opened) {
      doc = opened;

      /* One placeholder per page up front so the scrollbar is honest, then fill
         them in as they come into view. Rendering all of them eagerly would
         block the first page behind every other one.

         Built into a fragment and attached once, so opening a 357-page document
         is one mutation of a live tree rather than 357. */
      var frag = document.createDocumentFragment();
      for (var i = 1; i <= doc.numPages; i++) {
        var box = document.createElement("div");
        box.className = "pg";
        var c = document.createElement("canvas");
        box.appendChild(c);
        /* The text layer is not built here. An empty div per page is cheap and a
           built one is not, so it is filled in draw() and emptied in blank(),
           alongside the bitmap it belongs to. */
        var tl = document.createElement("div");
        tl.className = "textLayer";
        box.appendChild(tl);
        /* Bridges the margin below this page so a selection can cross into the next
           one; see the .vw-gap rule. Built here rather than when the page is drawn
           because it depends on nothing about the content, and last in .pg so that in
           document order it falls after every word on this page. The tile canvas goes
           in with insertBefore(canvas, slot.text), so it stays last. */
        var gap = document.createElement("div");
        gap.className = "vw-gap";
        box.appendChild(gap);
        var slot = {
          n: i, box: box, canvas: c, text: tl,
          state: "blank", mode: null, shrink: null, pictures: null, task: null, page: null,
          layer: null, unscaled: null, tile: null, pending: null,
          /* The picture being drawn to replace this page's, until it does, and whether
             the page is keeping an old picture up while it waits: see redraw(). */
          fresh: null, held: false
        };
        /* The observers are handed elements and need the slot back. Hanging it on
           the element beats writing a data attribute and parsing it out again on
           every callback, which was the only thing that attribute was ever for.

           The underscore is not decoration. Element.slot is a real DOM property, a
           DOMString reflecting the shadow-DOM slot attribute, so assigning an object
           to box.slot stores "[object Object]" and reads it back as that string.
           Every page then failed the state check in want() and stayed blank, with
           only page 1 ever drawn, which looks like a memory improvement right up
           until you look at the screen. */
        box._slot = slot;
        slots.push(slot);
        frag.appendChild(box);
      }
      pages.appendChild(frag);

      return doc.getPage(1);
    })
    .then(function (page) {
      /*
       * Page 1's shape stands in for every page until each is drawn.
       *
       * Asking every page for its own size would mean pulling several hundred page
       * dictionaries before anything could be shown, which on a ranged load is
       * several hundred round trips to learn something that is usually the same
       * every time. pdf.js's own viewer takes this shortcut too, under the name
       * defaultViewport. A document that mixes page sizes gets the first page's
       * shape until each page is drawn and its own from then on, which is a
       * placeholder being briefly wrong rather than the layout being wrong.
       */
      var unscaled = page.getViewport({ scale: 1 });
      pages.style.setProperty(
        "--pg-ratio", String(unscaled.width / unscaled.height));
      /* Derived rather than measured: the same number the CSS above will lay out to,
         without a layout read to ask what it did. The gap is read from --pg-gap rather
         than written here a second time; see the token's note in the stylesheet. */
      PAGE_PITCH = PAGE_WIDTH * (unscaled.height / unscaled.width) +
        (parseFloat(getComputedStyle(document.documentElement)
          .getPropertyValue("--pg-gap")) || 8);

      /*
       * A reopened document goes straight to the page it was left at, issue #25, and
       * that page is the first one drawn. Opening at the top and then jumping would
       * spend the opening wait drawing page 1 for nobody.
       *
       * Kotlin sends a page only when it has one, and it is checked against the
       * document here rather than trusted, the same as a "g" command is.
       */
      var first = slots[0];
      var resumeAt = Math.floor(Number(vwParams.get("resume")));
      if (resumeAt >= 2 && resumeAt <= slots.length) {
        first = slots[resumeAt - 1];
        return place(first).then(function () { return draw(first); });
      }
      return draw(first);
    })
    .then(function () {
      vwStatusDone();
      if (doc.numPages > 1) observePages();
      /* Once, unprompted. Somebody who opens a document and reads the first page
         without scrolling still has to be told how long it is, and the jump-to-page
         control has no total to check against until this has been sent. */
      reportPage();
      /* Asked for before there was anything to ask. */
      if (finding.query) { buildIndex(); report(); }
    })
    .catch(function (e) {
      var said = vwFailure(e);
      vwError(said[0], said[1]);
    });
}
