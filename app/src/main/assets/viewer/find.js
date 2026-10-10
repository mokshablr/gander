"use strict";

/*
 * Find in page for the Word, prose, Markdown, text, spreadsheet and slide viewers, over the
 * message channel pdf.html already searches through, and in its words: "q<query>", "n", "p"
 * and "c" come in, and "<at> <total> <done>" goes back, <at> counting from one and nought when
 * there is nothing to be at. See PortMessage.kt, which reads it.
 *
 * These pages were searched with Chromium's own find until now, which sees only what is in the
 * DOM, and two of them keep part of their document out of it: a workbook draws one sheet at a
 * time, and a large text file its first five megabytes until asked for more. A match on the
 * second sheet was never found, and the count said so as if it had looked. Here each page is
 * searched in its own text, all of it, highlighted in the colours a PDF's matches have.
 *
 * A page marks the element its document is drawn into with data-vw-find. One whose document is
 * not all in that element at once also defines window.vwFindParts, see parts().
 */
var vwFind = (function () {
  /*
   * More than this many matches are not counted. A single letter in a large text file matches
   * hundreds of thousands of times, every one of them an object, and past a few thousand the
   * number stops being information anyway.
   */
  var MAX_MATCHES = 10000;

  /* Highlighted at once, around the current one: each is a Range the browser has to track. */
  var MAX_PAINTED = 2000;

  /* Elements that begin a line, so that no match runs from the end of one into the next. */
  var BLOCK = /^(ADDRESS|ARTICLE|ASIDE|BLOCKQUOTE|BR|CAPTION|DD|DIV|DL|DT|FIGCAPTION|FIGURE|FOOTER|H[1-6]|HEADER|HR|LI|MAIN|NAV|OL|P|PRE|SECTION|TABLE|TBODY|TD|TFOOT|TH|THEAD|TR|UL)$/;

  var port = null;
  var query = "";
  /* Each is { part, at, len }, at and len in that part's flattened text. */
  var matches = [];
  var active = -1;
  var capped = false;
  /* Flattened text per part, until the part changes. */
  var cache = [];
  var rerunTimer = 0;
  /* Set while this file itself is changing the page, so it does not answer its own change. */
  var moving = false;

  /*
   * The document as parts: one, the element marked data-vw-find, unless the page says otherwise
   * by defining window.vwFindParts with
   *   count()  how many parts there are
   *   root(i)  an element holding part i's text, which need not be on the page
   *   show(i)  puts part i on the page, and answers the element that holds it there
   *   shown()  which part is on the page now
   * and, for a page with several parts on it at once, as a large sheet has its pieces,
   *   drawn(i) whether part i is on the page, which then counts for highlighting, not shown()
   */
  function parts() {
    if (window.vwFindParts) return window.vwFindParts;
    var root = document.querySelector("[data-vw-find]") || document.body;
    return {
      count: function () { return 1; },
      root: function () { return root; },
      show: function () { return root; },
      shown: function () { return 0; }
    };
  }

  /* Lower case, one UTF-16 unit for one, so an offset in one is an offset in the other. */
  function fold(s) {
    var low = s.toLowerCase();
    if (low.length === s.length) return low;
    var out = "";
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i).toLowerCase();
      out += c.length === 1 ? c : s.charAt(i);
    }
    return out;
  }

  /*
   * The text under [root] as it reads, with where each text node's part of it starts.
   *
   * Runs of whitespace are one space, as the page lays them out, a non-breaking space among
   * them, so a query with a space in it finds words a line break or two spaces apart in the
   * source. Not inside a pre, which shows its text as it was written, the whole of a text file
   * included. Elements that begin a line put a line break between their text and the next,
   * which no query can contain, so a match never runs from one paragraph or cell into the
   * next. Nothing hidden is read: script, style and template, and what says so with hidden.
   */
  function flatten(root) {
    var pieces = [];
    var length = 0;
    var nodes = [];
    var starts = [];
    var spaced = [];
    /* Whether a node's text went in unchanged in length, so an offset in one is one in the other */
    var exact = [];
    var inPre = 0;
    var lastWasSpace = true;

    function add(s) {
      pieces.push(s);
      length += s.length;
    }

    function separate() {
      if (!lastWasSpace || (pieces.length && pieces[pieces.length - 1] !== "\n")) add("\n");
      lastWasSpace = true;
    }

    (function walk(node) {
      for (var child = node.firstChild; child; child = child.nextSibling) {
        if (child.nodeType === 3) {
          var raw = child.data;
          if (!raw) continue;
          nodes.push(child);
          starts.push(length);
          spaced.push(lastWasSpace);
          var text = inPre ? raw : raw.replace(/\s+/g, " ");
          var dropped = !inPre && lastWasSpace && text.charAt(0) === " ";
          if (dropped) text = text.slice(1);
          exact.push(!dropped && text.length === raw.length);
          if (text) {
            add(text);
            lastWasSpace = /\s/.test(text.charAt(text.length - 1));
          }
        } else if (child.nodeType === 1) {
          var tag = child.tagName;
          if (tag === "SCRIPT" || tag === "STYLE" || tag === "NOSCRIPT" || tag === "TEMPLATE" ||
              child.hidden) continue;
          var block = BLOCK.test(tag);
          if (block) separate();
          if (tag === "PRE") inPre++;
          walk(child);
          if (tag === "PRE") inPre--;
          if (block) separate();
        }
      }
    })(root);

    return {
      root: root, text: fold(pieces.join("")), nodes: nodes, starts: starts, spaced: spaced, exact: exact
    };
  }

  function flattened(i) {
    var root = parts().root(i);
    var hit = cache[i];
    if (hit && hit.root === root) return hit;
    return (cache[i] = flatten(root));
  }

  /*
   * Where in [node] the flattened offset [k] falls, counted from the node's own start in the
   * flattened text. The reverse of the collapsing flatten() did, which is only ever needed for
   * the few matches that are drawn, so it is worked out again rather than kept for every node.
   */
  function rawOffset(flat, i, k) {
    var node = flat.nodes[i];
    if (flat.exact[i]) return Math.min(k, node.data.length);
    var raw = node.data;
    var spacedBefore = flat.spaced[i];
    var emitted = 0;
    var space = spacedBefore;
    for (var r = 0; r < raw.length; r++) {
      if (emitted >= k) return r;
      if (/\s/.test(raw.charAt(r))) {
        if (!space) { emitted++; space = true; }
      } else {
        emitted++;
        space = false;
      }
    }
    return raw.length;
  }

  /* The last node whose text starts at or before [at]. */
  function nodeAt(flat, at) {
    var lo = 0, hi = flat.starts.length - 1, found = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (flat.starts[mid] <= at) { found = mid; lo = mid + 1; } else { hi = mid - 1; }
    }
    return found;
  }

  /* A Range over a match in the part on the page, or null where the page has moved on. */
  function rangeFor(flat, match) {
    var first = nodeAt(flat, match.at);
    var last = nodeAt(flat, match.at + match.len - 1);
    if (first < 0 || last < 0) return null;
    var range = document.createRange();
    try {
      var a = flat.nodes[first], b = flat.nodes[last];
      range.setStart(a, rawOffset(flat, first, match.at - flat.starts[first]));
      range.setEnd(b, rawOffset(flat, last, match.at + match.len - flat.starts[last]));
    } catch (e) {
      return null;
    }
    return range;
  }

  function search() {
    matches = [];
    capped = false;
    var q = fold(query.replace(/\s+/g, " "));
    if (!q) return;
    var p = parts();
    for (var i = 0; i < p.count() && !capped; i++) {
      var text = flattened(i).text;
      var at = text.indexOf(q);
      while (at !== -1) {
        if (matches.length >= MAX_MATCHES) { capped = true; break; }
        matches.push({ part: i, at: at, len: q.length });
        at = text.indexOf(q, at + q.length);
      }
    }
  }

  /* Whether the page is still drawing its document, in which case a count is a not-yet. */
  function loading() {
    var card = document.getElementById("vw-status");
    return !!card && card.style.display !== "none" && card.className.indexOf("vw-error") < 0;
  }

  function report() {
    if (!port) return;
    var total = matches.length;
    var at = total && active >= 0 ? active + 1 : 0;
    port.postMessage(at + " " + total + " " + (loading() ? 0 : 1));
  }

  /* Highlights over the parts on the page, the current match on its own. */
  function paint() {
    if (!window.CSS || !CSS.highlights) return;
    var p = parts();
    var shown = p.shown();
    var hits = [];
    var current = null;
    var from = Math.max(0, active - MAX_PAINTED / 2);
    for (var i = from; i < matches.length && hits.length < MAX_PAINTED; i++) {
      var part = matches[i].part;
      if (p.drawn ? !p.drawn(part) : part !== shown) continue;
      var r = rangeFor(flattened(part), matches[i]);
      if (!r) continue;
      if (i === active) current = r; else hits.push(r);
    }
    if (hits.length) CSS.highlights.set("vw-find", new Highlight(...hits));
    else CSS.highlights.delete("vw-find");
    if (current) CSS.highlights.set("vw-find-active", new Highlight(current));
    else CSS.highlights.delete("vw-find-active");
  }

  /*
   * Bring the current match on screen, showing its part first if it is another. The element
   * holding it is scrolled to the middle, which reaches through a sheet's own scrolling box as
   * well as the page's; then, for an element taller than the screen, the words themselves.
   * Without the Highlight API the words are selected instead, which every engine draws.
   */
  function reveal() {
    var match = matches[active];
    if (!match) return;
    var p = parts();
    if (p.shown() !== match.part) {
      moving = true;
      p.show(match.part);
      cache[match.part] = null;
      setTimeout(function () { moving = false; }, 0);
    }
    var range = rangeFor(flattened(match.part), match);
    if (!range) return;
    var holder = range.startContainer.parentElement;
    if (holder) holder.scrollIntoView({ block: "center", inline: "nearest" });
    var box = range.getBoundingClientRect();
    if (box.height && (box.top < 0 || box.bottom > window.innerHeight)) {
      window.scrollBy(0, box.top - window.innerHeight / 2);
    }
    if (!window.CSS || !CSS.highlights) {
      var selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    }
  }

  function stepTo(i) {
    var n = matches.length;
    active = n ? ((i % n) + n) % n : -1;
    reveal();
    paint();
    report();
  }

  function clear() {
    query = "";
    matches = [];
    active = -1;
    if (window.CSS && CSS.highlights) {
      CSS.highlights.delete("vw-find");
      CSS.highlights.delete("vw-find-active");
    }
    report();
  }

  /*
   * The document changed under a search: a sheet was chosen, the rest of a text file was asked
   * for, a deck is still putting its slides in. Searched again, staying on the match that was
   * current where it is still there, and on the same number otherwise.
   *
   * Only a part whose own text a change reached is read again: [records] are the observer's,
   * and without them every part is. A large sheet puts its pieces on the page as the reader
   * scrolls, and the parts a chosen sheet takes off it go on holding the text they had, so
   * reading every part again for each would read the whole sheet as often as a piece is drawn.
   */
  function changed(records) {
    if (moving) return;
    forget(records);
    if (!query || rerunTimer) return;
    rerunTimer = setTimeout(function () {
      rerunTimer = 0;
      var was = matches[active];
      search();
      var keep = -1;
      if (was) {
        for (var i = 0; i < matches.length; i++) {
          if (matches[i].part === was.part && matches[i].at === was.at) { keep = i; break; }
        }
      }
      active = keep >= 0 ? keep : Math.min(Math.max(active, 0), matches.length - 1);
      paint();
      report();
    }, 250);
  }

  /* Drops what was read of each part a change in [records] reached, or of every part */
  function forget(records) {
    if (!records) { cache = []; return; }
    for (var i = 0; i < cache.length; i++) {
      var hit = cache[i];
      if (!hit) continue;
      for (var r = 0; r < records.length; r++) {
        var target = records[r].target;
        if (hit.root === target || hit.root.contains(target)) { cache[i] = null; break; }
      }
    }
  }

  function onCommand(msg) {
    /* The title bar's height and whereabouts, which app.js keeps for every page */
    if (vwBarSaid(msg)) return;
    var verb = msg.charAt(0);
    if (verb === "q") {
      var q = msg.slice(1);
      if (q === query) return;
      query = q;
      if (!q) { clear(); return; }
      search();
      stepTo(0);
    } else if (verb === "n") {
      if (matches.length) stepTo(active + 1);
    } else if (verb === "p") {
      if (matches.length) stepTo(active - 1);
    } else if (verb === "c") {
      clear();
    } else if (window.vwPageCommand) {
      /* A page to go to and night mode belong to the page, where it has them, as Word's does */
      window.vwPageCommand(msg);
    }
  }

  window.addEventListener("message", function (e) {
    /* The only message these pages take is the one handing over the port. */
    if (!e.ports || !e.ports.length || port) return;
    port = e.ports[0];
    port.onmessage = function (m) {
      /* Nothing thrown in here may reach the port: it does not come back from that */
      try { onCommand(String(m.data || "")); } catch (err) { report(); }
    };
    port.start();
    try { if (window.vwPortReady) window.vwPortReady(); } catch (err) { /* the port still works */ }
  });

  document.addEventListener("DOMContentLoaded", function () {
    if (!window.MutationObserver) return;
    new MutationObserver(changed).observe(document.body, {
      childList: true, subtree: true, characterData: true
    });
  });

  /* The count is a not-yet while the page is drawing; say so again once it has finished. */
  if (typeof vwStatusDone === "function") {
    var done = vwStatusDone;
    vwStatusDone = function () {
      done.apply(this, arguments);
      if (query) changed(); else report();
    };
  }

  /* Says something to the app over the same port, answering whether there was one to say it on */
  function post(msg) {
    if (!port) return false;
    port.postMessage(msg);
    return true;
  }

  return { changed: changed, post: post };
})();
