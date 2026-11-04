"use strict";

/*
 * The simple view: a PDF on a phone whose WebView pdf.js can't run on, each page a picture drawn
 * by Android's PdfRenderer in an isolated process and served by Gander under /render/. SimplePdf.kt
 * is that half, and FileKind.PDF_SIMPLE says when a PDF comes here.
 *
 * ES5, as app.js is, since this page is for old engines. Measure it with scripts/js-floor.mjs
 * after a change. All of it is inside one function, so that none of its names is a global: a
 * classic script's top() would be window.top, which can't be declared again.
 *
 * What it keeps from pdf.html: a page laid out at the 980 CSS px a WebView with no viewport meta
 * lays out at, the title bar's room at the top, the page readout and Go to page over the channel,
 * the page a document reopens at, and night mode. The double tap and the scroll thumb are the
 * app's. What it has not: words to search or select, and links.
 */

(function () {
  var pages = document.getElementById("pages");

  /* Read once, for pdf.mjs's reason: there is no viewport meta, so this is a constant. */
  var PAGE_WIDTH = pages.clientWidth || window.innerWidth;

  /* See vwPhysicalWidth() in app.js and BASE_HEADROOM in pdf.mjs. A tenth over here, where
     pdf.mjs draws at twice the screen: each picture is a PNG made in the renderer's own
     process, on the old phones this view is for, and twice would be four times the pixels. */
  var DPR_FALLBACK = Math.min(window.devicePixelRatio || 1, 2);
  var BASE_HEADROOM = 1.1;

  /*
   * How far either side of the screen pages are drawn, and laid out at all, counted in pages, as
   * pdf.mjs counts them. A page is laid out and its picture kept until it is KEEP_PAGES off the
   * screen, so a small change of direction costs nothing.
   */
  var DRAW_PAGES = 3;
  var KEEP_PAGES = 5;

  /*
   * How many pictures are asked for at once. Gander's renderer draws one page at a time, so a
   * second only saves the wait between them. More would only queue in Gander, where a page asked
   * for can't be taken back: one the reader has flung past would still be drawn before the page
   * they stopped at.
   */
  var MAX_IN_FLIGHT = 2;

  /*
   * The tallest a column of pages may be, in CSS px. Chromium lays nothing out past about 33.5
   * million px, counted in device pixels on a screen with several to a CSS px, as a phone's has,
   * so the ceiling in CSS px comes down with the pixel ratio. A document longer than this shows
   * its first pages and says so. A renderer can believe a PDF has a million pages, which is why
   * the pages are laid out lazily at all.
   */
  var MAX_COLUMN = 25e6 / Math.max(1, window.devicePixelRatio || 1);

  /*
   * Whether app.css's scroll-padding-top keeps a page brought into view out from under the title
   * bar. Chromium has it from 69, and this page is for engines as old as 66: see place().
   */
  var PADDED = !!(window.CSS && CSS.supports && CSS.supports("scroll-padding-top", "0px"));

  /* The shapes a page may take, height over width, for laying it out. */
  var MIN_RATIO = 0.02;
  var MAX_RATIO = 50;

  var GAP =
    parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--pg-gap")) || 8;

  /* Pages laid out, and pages the document has: the same unless the column ran out. */
  var count = 0;
  var total = 0;

  /*
   * Page 1's shape, height over width, which stands for every page whose own is not known, as it
   * does in pdf.mjs. Those that are known are kept in page order with how far each is from it, and
   * the sum of those up to each, so that where a page lies is a search rather than a walk.
   */
  var ratio0 = Math.SQRT2;
  var known = [];
  var deltas = [];
  var sums = [];
  /* Pages whose size has been asked for, whatever it was */
  var sized = {};

  /* The pages laid out as boxes, in page order, the first being page first + 1 */
  var win = [];
  var first = 0;
  var above = document.createElement("div");
  var below = document.createElement("div");

  var night = vwParams.get("night") === "1";
  if (night) document.documentElement.classList.add("vw-night");

  var inFlight = 0;
  var shown = false;

  /* The index in known of page i, or of where it would go, and whether it is there. */
  function seek(i) {
    var lo = 0, hi = known.length;
    while (lo < hi) {
      var mid = (lo + hi) >> 1;
      if (known[mid] < i) lo = mid + 1; else hi = mid;
    }
    return lo;
  }

  function ratioOf(i) {
    var at = seek(i);
    return at < known.length && known[at] === i ? ratio0 + deltas[at] : ratio0;
  }

  function heightOf(i) {
    return PAGE_WIDTH * ratioOf(i);
  }

  /* Where page i begins, in CSS px below the top of page 1, with the gaps above it. */
  function top(i) {
    var at = seek(i);
    return i * (PAGE_WIDTH * ratio0 + GAP) + (at ? PAGE_WIDTH * sums[at - 1] : 0);
  }

  /* The page at y px below the top of page 1, kept to the pages laid out. */
  function pageAt(y) {
    var lo = 0, hi = count - 1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (top(mid) <= y) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  /*
   * Gives page i its own shape, r, and its box too if it is laid out: lay() keeps the boxes it
   * has, so one laid out before a jump learnt its shape would otherwise keep the old one. True
   * if that moved anything.
   */
  function shape(i, r) {
    sized[i] = true;
    r = Math.min(MAX_RATIO, Math.max(MIN_RATIO, r));
    if (Math.abs(r - ratioOf(i)) < 1e-4) return false;
    var at = seek(i);
    if (at < known.length && known[at] === i) {
      deltas[at] = r - ratio0;
    } else {
      known.splice(at, 0, i);
      deltas.splice(at, 0, r - ratio0);
      sums.splice(at, 0, 0);
    }
    for (var k = at; k < known.length; k++) sums[k] = (k ? sums[k - 1] : 0) + deltas[k];
    var s = slotOf(i);
    if (s) s.box.style.height = heightOf(i) + "px";
    return true;
  }

  /* How many pixels across a page is asked for: the screen's own with the page fitted to it. */
  function drawWidth() {
    var w = vwPhysicalWidth();
    var most = PAGE_WIDTH * DPR_FALLBACK;
    return Math.round(w ? Math.min(w * BASE_HEADROOM, most) : most);
  }

  /* What the reader can see, in CSS px down the document, pinched in or not. */
  function view() {
    var vv = window.visualViewport;
    if (vv && vv.height > 0) return { top: vv.pageTop, height: vv.height };
    return { top: window.pageYOffset, height: window.innerHeight };
  }

  /* Where page 1 begins, in CSS px down the document, under the title bar's room. */
  function columnTop() {
    return above.getBoundingClientRect().top + window.pageYOffset;
  }

  /* The pages on screen, first and last, counting from 0. */
  function onScreen() {
    var v = view(), o = columnTop();
    return [pageAt(v.top - o), pageAt(v.top + v.height - o)];
  }

  /* A box for page i, at the shape it is known by. */
  function make(i) {
    var box = document.createElement("div");
    box.className = "pg";
    box.style.height = heightOf(i) + "px";
    if (i === count - 1) box.classList.add("vw-last");
    return { n: i, box: box, img: null, mode: null, asked: false, failed: false, dead: false };
  }

  /*
   * Lays out pages f to l as boxes, and only those, with the spacers standing for the rest. A box
   * that stays is left where it is, so the picture in it stays too. A spacer is as tall as the
   * pages it stands for, so nothing on screen moves as pages come and go off it.
   */
  function lay(f, l) {
    var kept = [];
    for (var k = 0; k < win.length; k++) {
      var s = win[k];
      if (s.n < f || s.n > l) {
        pages.removeChild(s.box);
        /* A picture still on its way finishes and is dropped: see request() */
        s.dead = true;
      } else {
        kept.push(s);
      }
    }
    var next = [];
    for (var i = f, j = 0; i <= l; i++) {
      if (j < kept.length && kept[j].n === i) next.push(kept[j++]);
      else next.push(make(i));
    }
    var anchor = below;
    for (var m = next.length - 1; m >= 0; m--) {
      var box = next[m].box;
      if (box.parentNode !== pages || box.nextSibling !== anchor) pages.insertBefore(box, anchor);
      anchor = box;
    }
    win = next;
    first = f;
    spacers();
  }

  function spacers() {
    var end = win.length ? win[win.length - 1].n + 1 : count;
    above.style.height = top(win.length ? win[0].n : count) + "px";
    below.style.height = (top(count) - top(end)) + "px";
  }

  /* The slot for page i, if it is laid out. */
  function slotOf(i) {
    var s = win[i - first];
    return s && s.n === i ? s : null;
  }

  /* The pages near the screen laid out, and their pictures asked for. */
  function update() {
    if (!count) return;
    var seen = onScreen();
    var f = Math.max(0, seen[0] - KEEP_PAGES);
    var l = Math.min(count - 1, seen[1] + KEEP_PAGES);
    if (!win.length || f !== first || l !== win[win.length - 1].n) lay(f, l);
    pump();
  }

  /* Whether a page wants a picture: none yet, or one of the wrong mode, and none on its way. */
  function needs(s) {
    return !s.asked && !s.failed && (!s.img || s.mode !== night);
  }

  /*
   * Asks for the next pictures, nearest the middle of the screen first: during a fling most of the
   * pages near the screen are ones the reader has passed.
   */
  function pump() {
    if (!count) return;
    var seen = onScreen();
    var a = seen[0] - DRAW_PAGES, b = seen[1] + DRAW_PAGES;
    var v = view();
    var middle = v.top - columnTop() + v.height / 2;
    while (inFlight < MAX_IN_FLIGHT) {
      var best = null, nearest = Infinity;
      for (var k = 0; k < win.length; k++) {
        var s = win[k];
        if (s.n < a || s.n > b || !needs(s)) continue;
        var d = Math.abs(top(s.n) + heightOf(s.n) / 2 - middle);
        if (d < nearest) { best = s; nearest = d; }
      }
      if (!best) return;
      request(best);
    }
  }

  /*
   * One page's picture. The old one stays until the new one has come, so turning night mode over
   * shows each page one way or the other and never blank. A picture that comes for a page no
   * longer laid out is dropped, but only then: Gander draws what it is asked for whatever happens
   * to the asking, so the count of those in flight comes down when the picture does.
   */
  function request(s) {
    s.asked = true;
    inFlight++;
    var mode = night;
    var img = document.createElement("img");
    img.alt = "Page " + (s.n + 1);
    var settled = false;
    function settle() {
      if (settled) return false;
      settled = true;
      inFlight--;
      s.asked = false;
      return !s.dead;
    }
    img.onload = function () {
      if (settle()) landed(s, img, mode);
      later();
    };
    img.onerror = function () {
      if (settle() && !s.img) failed(s);
      else if (!s.dead) s.failed = true;
      later();
    };
    img.src = "/render/page?n=" + (s.n + 1) + "&w=" + drawWidth() + "&night=" + (mode ? 1 : 0);
  }

  function landed(s, img, mode) {
    /* Drawn the other way round from what is wanted now: pump() asks again */
    if (mode !== night && s.img) return;
    if (img.naturalWidth > 0 && shape(s.n, img.naturalHeight / img.naturalWidth)) spacers();
    if (s.img) s.box.replaceChild(img, s.img); else s.box.appendChild(img);
    s.img = img;
    s.mode = mode;
    if (!shown) {
      shown = true;
      vwStatusDone();
    }
  }

  function failed(s) {
    s.failed = true;
    var note = document.createElement("div");
    note.className = "pg-note";
    note.textContent = "This page could not be drawn";
    s.box.appendChild(note);
    if (!shown) {
      shown = true;
      vwStatusDone();
    }
  }

  /* One look at the screen per frame, however many events arrive in it. */
  var frame = 0;
  function later() {
    if (frame) return;
    frame = requestAnimationFrame(function () {
      frame = 0;
      update();
      reportPage();
    });
  }

  window.addEventListener("scroll", later, { passive: true });
  if (window.visualViewport) {
    /* Both, as in pdf.mjs: a pinched-in WebView can move without a window scroll */
    window.visualViewport.addEventListener("scroll", later, { passive: true });
    window.visualViewport.addEventListener("resize", later, { passive: true });
  }

  /* The channel ViewerActivity hands the page, and pdf.mjs's page readout over it. */
  var port = null;
  var lastPageSent = 0;

  /* The page a jump or a reopen last put on screen and where that left the screen, or null once
     the reader has moved from there: see bringIntoView() in pdf.mjs. */
  var askedFor = null;

  function reportPage() {
    if (!port || count < 2) return;
    var v = view();
    if (!v.height) return;
    if (askedFor && Math.abs(v.top - askedFor.top) > 1) askedFor = null;
    var n = askedFor ? askedFor.n : pageOnScreen(v);
    if (n === lastPageSent) return;
    lastPageSent = n;
    port.postMessage("page " + n + " " + count);
  }

  /* pdf.mjs's: the page under the middle of the screen, except at the two ends. */
  function pageOnScreen(v) {
    if (v.top <= 1) return 1;
    if (v.top + v.height >= document.documentElement.scrollHeight - 1) return count;
    return pageAt(v.top + v.height / 2 - columnTop()) + 1;
  }

  /*
   * Jumps asked for, counted, so that only the last one lands: the sizes one waits for can come
   * after a later one's that needed none.
   */
  var jumps = 0;

  /*
   * The sizes of pages a to b not yet known, asked of Gander for a jump. Never fails: a page it
   * can't size keeps the shape it has. Sizes that come for a jump a later one has overtaken are
   * dropped: that jump lays nothing out, so nothing would bring the spacers into line with them.
   */
  function sizes(a, b, jump) {
    var from = a, to = b;
    while (from <= to && sized[from]) from++;
    while (to >= from && sized[to]) to--;
    if (from > to) return Promise.resolve();
    return fetch("/render/sizes?from=" + (from + 1) + "&to=" + (to + 1)).then(function (r) {
      return r.ok ? r.json() : null;
    }).then(function (said) {
      var all = said && said.sizes;
      if (!all || jump !== jumps) return;
      for (var k = 0; k < all.length && from + k <= to; k++) {
        var wh = all[k];
        if (wh && wh[0] > 0 && wh[1] > 0) shape(from + k, wh[1] / wh[0]);
      }
    }, function () {});
  }

  /*
   * Puts page i on screen where the readout will name it, with it and the page before at their own
   * shapes first: pdf.mjs's place() and bringIntoView(), which say why both. Where the engine has
   * no scroll-padding, it is put where scroll-padding-top would have scrollIntoView put it: at the
   * top below the title bar's room, or in the middle of the screen below it.
   */
  function place(i) {
    var jump = ++jumps;
    return sizes(Math.max(0, i - 1), i, jump).then(function () {
      if (jump !== jumps) return;
      lay(Math.max(0, i - KEEP_PAGES), Math.min(count - 1, i + KEEP_PAGES));
      var s = slotOf(i);
      var shortPage = heightOf(i) <= view().height / 2;
      if (PADDED) {
        s.box.scrollIntoView({ block: shortPage ? "center" : "start" });
      } else {
        var room = vwTopPx, y = s.box.getBoundingClientRect().top + window.pageYOffset;
        window.scrollTo(0, shortPage
          ? y + heightOf(i) / 2 - (room + (view().height - room) / 2)
          : y - room);
      }
      askedFor = { n: i + 1, top: view().top };
    });
  }

  window.addEventListener("message", function (e) {
    /* The only message this page gets is the one handing it the port. Anything else isn't ours. */
    if (!e.ports || !e.ports.length || port) return;
    port = e.ports[0];
    port.onmessage = function (m) { onCommand(String(m.data || "")); };
    port.start();
    reportPage();
  });

  /* pdf.mjs's commands, but for search, which has nothing to find here. */
  function onCommand(msg) {
    if (vwBarSaid(msg)) {
      later();
      return;
    }
    var verb = msg.charAt(0);
    if (verb === "i") {
      setNight(msg.charAt(1) === "1");
    } else if (verb === "g") {
      /* Checked again, as pdf.mjs does: a throw in here would end the channel */
      var n = Math.floor(Number(msg.slice(1)));
      if (!count || !(n >= 1 && n <= count)) return;
      place(n - 1).then(function () {
        update();
        reportPage();
      });
    }
  }

  /* Night mode on or off: each page is asked for again the other way round, nearest first. */
  function setNight(on) {
    if (on === night) return;
    night = on;
    document.documentElement.classList.toggle("vw-night", on);
    pump();
  }

  /* What to say when Gander could not open the document, by the word it gives. */
  function failure(word) {
    switch (word) {
      case "password":
        return [
          "This PDF needs a password",
          "The simple view, which this phone gets for PDFs, can't open a PDF that needs one."
        ];
      case "not-pdf":
        return [
          "This file is not a PDF that can be read",
          "It is either damaged or not a PDF at all, whatever it happens to be " +
          "named. Every other format Gander opens still works."
        ];
      case "damaged":
        return [
          "This PDF could not be read",
          "It may be damaged, or made in a way the simple view can't draw."
        ];
      case "unreadable":
        return [
          "This PDF could not be read",
          "Gander could not read the file it was given. Opening it again usually settles this."
        ];
      default:
        return ["Could not render this PDF", "Its renderer stopped."];
    }
  }

  function begin(doc) {
    if (doc.error) {
      var said = failure(doc.error);
      vwError(said[0], said[1]);
      return;
    }
    total = doc.pages;
    if (doc.width > 0 && doc.height > 0) {
      ratio0 = Math.min(MAX_RATIO, Math.max(MIN_RATIO, doc.height / doc.width));
    }
    sized[0] = true;
    count = Math.max(1, Math.min(total, Math.floor(MAX_COLUMN / (PAGE_WIDTH * ratio0 + GAP))));
    pages.appendChild(above);
    pages.appendChild(below);
    if (count < total) {
      var cut = document.createElement("div");
      cut.className = "vw-cut";
      cut.textContent =
        "The simple view shows the first " + count + " of this PDF's " + total + " pages.";
      pages.appendChild(cut);
    }
    /* A reopened document goes straight to its page, which is then the first one drawn. Checked
       against the document rather than trusted, as a "g" command is. */
    var resumeAt = Math.floor(Number(vwParams.get("resume")));
    var start = resumeAt >= 2 && resumeAt <= count ? place(resumeAt - 1) : Promise.resolve();
    return start.then(function () {
      update();
      reportPage();
    });
  }

  fetch("/render/document").then(function (r) {
    if (!r.ok) throw new Error("Could not read the file (HTTP " + r.status + ")");
    return r.json();
  }).then(begin).catch(function (e) {
    vwError("Could not render this PDF", String(e));
  });
})();
