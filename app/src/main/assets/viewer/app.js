"use strict";

var vwParams = new URLSearchParams(location.search);
var vwName = vwParams.get("name") || "file";
var vwExt = (vwParams.get("ext") || "").toLowerCase();

function vwStatus(msg) {
  var el = document.getElementById("vw-status");
  if (!el) {
    el = document.createElement("div");
    el.id = "vw-status";
    el.className = "vw-status";
    document.body.insertBefore(el, document.body.firstChild);
  }
  el.className = "vw-status";
  el.textContent = msg;
  el.style.display = "";
}

function vwStatusDone() {
  var el = document.getElementById("vw-status");
  if (el) el.style.display = "none";
}

function vwError(title, detail) {
  var el = document.getElementById("vw-status");
  if (!el) {
    el = document.createElement("div");
    el.id = "vw-status";
    document.body.insertBefore(el, document.body.firstChild);
  }
  el.className = "vw-status vw-error";
  el.style.display = "";
  el.innerHTML = "";
  var t = document.createElement("div");
  t.className = "vw-error-title";
  t.textContent = title;
  var d = document.createElement("div");
  d.className = "vw-error-detail";
  d.textContent = detail || "";
  el.appendChild(t);
  el.appendChild(d);
}

/*
 * The card a viewer shows instead of its document when the WebView is too old for the
 * library that draws it, and whether it showed it. Called before the viewer reads a
 * byte, and a true answer means stop: the card is the whole page.
 *
 * Kotlin decides, and says so on the URL. webview=<major>&needs=<floor> arrive only when
 * the engine about to render is older than the library supports; PDFs, Word documents
 * and Markdown each have a floor, and WebViewFloor.kt says where each number comes from.
 * That is a version read from the system rather than a timeout or a feature sniff, so
 * the same phone gets the same answer every time.
 *
 * locked=1 arrives when the reader has no way to update the WebView, which is the case on
 * a phone whose manufacturer supplies it and allows no replacement. Telling those readers
 * to go and update it is the one thing the card must not do. On such a phone the version
 * sometimes cannot be read at all, and then locked=1 arrives without a major beside it,
 * which is why this gates on either parameter rather than on the version.
 *
 * Once the card is up nothing may replace it, which is why window.onerror goes. pdf.html
 * fetches its renderer after this file whatever happens, an engine that old often cannot
 * parse it, and that failure would land in the handler at the foot of this file and swap
 * the card for the parser's complaint. That is what issue #31 was, on WebView 64. The
 * Word and Markdown libraries load ahead of this file, so their failures never reach it.
 */
function vwWebViewTooOld(what) {
  var have = vwParams.get("webview");
  var locked = vwParams.get("locked");
  if (!have && !locked) return false;
  vwNeedsNewerWebView(what, have, vwParams.get("needs"), locked);
  window.onerror = null;
  return true;
}

/*
 * what is the format as the sentences need it, plural and capitalised: "PDFs", "Word
 * documents", "Markdown files". No floor is written here. The number arrives in the query
 * string so that raising one stays a one-constant edit in Kotlin, which means every
 * sentence has to work without it too.
 *
 * No card says what else still opens. On the oldest engines that see one, other formats
 * fail as well: on the WebView 64 of issue #31, PDFs, Word documents and Markdown all did.
 */
function vwNeedsNewerWebView(what, have, needs, locked) {
  if (locked) {
    vwError(
      what + " cannot be shown on this phone",
      (needs
        ? what + " need the browser engine built into your phone to be version " +
          needs + " or newer, and this one is " + (have || "older") + ". "
        : what + " need a newer browser engine than the one built into your phone. ") +
      "On this phone that engine comes from the manufacturer and cannot be " +
      "updated or replaced, so installing Android System WebView will not help."
    );
    return;
  }
  vwError(
    "Android System WebView is too old to show " + what,
    "Gander opens " + what + " using Android System WebView, the browser engine built " +
    "into your phone. " +
    (have && needs
      ? what + " need version " + needs + " or newer, and this one is " + have + ". "
      : "") +
    "Updating Android System WebView and reopening the file will fix it."
  );
}

/*
 * Publish the height the reader can actually see as --vw-fit, for the viewers that
 * centre a document short enough to fit on the screen.
 *
 * 100vh is not reliably that height. These pages carry no viewport meta, so the WebView
 * lays out at 980 CSS px and scales that to the screen, and the initial containing block
 * is fixed at the 980-wide geometry. A page whose content is wider than 980 overflows,
 * loadWithOverviewMode zooms further out to fit it, and the reader can then see more CSS
 * px of height than 100vh reports. Measured on the phone: an ordinary widescreen deck is
 * 1279 px wide and shows 2467 px of height against a 100vh that stays at 1886.
 *
 * window.innerHeight is the number that is right, and it is the one to use rather than
 * visualViewport.height, which shrinks as the reader pinches in. Centring against that
 * would move the document under the gesture; innerHeight holds still through a pinch and
 * changes only when the screen does.
 *
 * Call this once the document is in the DOM, because it is the content overflowing that
 * changes the answer and no resize is fired when that happens. The listener bound here is
 * for rotation, which does fire one, and is the only word the page gets: ViewerActivity
 * declares configChanges and never rebuilds. It binds on first call rather than on load
 * so that the viewers with nothing to centre do not carry it.
 */
var vwFitBound = false;

function vwFitHeight() {
  if (!vwFitBound) {
    vwFitBound = true;
    addEventListener("resize", vwFitHeight);
  }
  document.documentElement.style.setProperty("--vw-fit", window.innerHeight + "px");
  // A document wider than the layout zooms the page out, which changes what a dp is here
  vwPlaceTop();
}

/*
 * The room a document leaves at its top for the title bar Gander floats over it, which goes up
 * out of sight as the reader scrolls down. Issue #40.
 *
 * top= on the URL, and "t" over the channel when it changes, give the bar's height in dp, and
 * --vw-top is that in this page's CSS px at its widest zoom, kept clear by app.css. vv.width *
 * vv.scale is the WebView's width in dp at any pinch and innerWidth what the page fits across it
 * at the widest, so the two say what a dp is here. --vw-bar-gone is how much of the bar is out
 * of sight, in the same px, for whatever on the page is held under it.
 */
var vwTopDp = Number(vwParams.get("top")) || 0;
var vwTopPx = 0;
var vwTopBound = false;
var vwBarGoneDp = 0;
var vwBarGonePx = 0;

function vwPlaceTop() {
  if (!vwTopDp && !vwTopPx) return;
  if (!vwTopBound) {
    vwTopBound = true;
    // Turning the phone changes the width a dp is measured against
    addEventListener("resize", vwPlaceTop);
  }
  var vv = window.visualViewport;
  var across = vv && vv.scale > 0 ? vv.width * vv.scale : 0;
  var perDp = across > 0 && window.innerWidth > 0 ? window.innerWidth / across : 1;
  var px = vwTopDp * perDp;
  var gone = vwBarGoneDp * perDp;
  var root = document.documentElement.style;
  if (px !== vwTopPx) {
    vwTopPx = px;
    root.setProperty("--vw-top", px + "px");
  }
  if (gone !== vwBarGonePx) {
    vwBarGonePx = gone;
    root.setProperty("--vw-bar-gone", gone + "px");
  }
}

vwPlaceTop();

/*
 * The app on the bar, over the channel, from whichever script holds it: "t<dp>" its height, which
 * the search bar under it adds to, and "b<dp>" how much of it has gone up out of sight, sent as it
 * follows the reader's scrolling. Answers whether the message was one of those.
 */
function vwBarSaid(msg) {
  var verb = msg.charAt(0);
  if (verb === "t") {
    vwTopDp = Number(msg.slice(1)) || 0;
    vwPlaceTop();
    return true;
  }
  if (verb === "b") {
    vwBarGoneDp = Number(msg.slice(1)) || 0;
    vwPlaceTop();
    return true;
  }
  return false;
}

/*
 * Gives each sheet its own paper size for print, which Chromium then fits to the paper
 * chosen. Without it an A4 page printed on Letter runs on to a second, nearly empty sheet.
 * A named page per size, since a document can turn some of its pages sideways.
 */
function vwPrintSizes(sheets) {
  var rules = "";
  var names = {};
  for (var i = 0; i < sheets.length; i++) {
    var style = sheets[i].style;
    if (!style.width || !style.minHeight) continue;
    var size = style.width + " " + style.minHeight;
    if (!names[size]) {
      names[size] = "vw-sheet-" + i;
      rules += "@page " + names[size] + " { size: " + size + "; } ";
    }
    style.page = names[size];
  }
  if (!rules) return;
  var css = document.createElement("style");
  css.textContent = "@media print { " + rules + "}";
  document.head.appendChild(css);
}

/*
 * Takes the address off every link in a rendered document that is not a web or mail
 * address, a phone number or a jump within the page.
 *
 * docx-preview copies a link's target out of the file into href without looking at it,
 * and PPTXjs and SheetJS write theirs into markup too, so a crafted document can carry
 * a javascript: link, or one to another of Gander's pages. The page's policy already
 * refuses to run the first and ViewerActivity to open the second; this makes both
 * plain text, which is also what they look like to anyone who taps them. A list of
 * what may stay rather than of what must go, because browsers read "java\nscript:" as
 * javascript: and a list of bad schemes would have to know every such spelling.
 */
function vwDisarmLinks(root) {
  var links = root.querySelectorAll("a[href], area[href]");
  for (var i = 0; i < links.length; i++) {
    var href = links[i].getAttribute("href").trim();
    if (href.charAt(0) === "#") continue;
    var scheme = /^([a-z][a-z0-9+.-]*):/i.exec(href);
    if (scheme && /^(https?|mailto|tel)$/i.test(scheme[1])) continue;
    links[i].removeAttribute("href");
  }
}

function vwDocUrl() {
  return "/doc/file" + (vwExt ? "." + vwExt : "");
}

/*
 * How large a document has to be before the card goes up for reading it.
 *
 * .vw-wait holds the card back for 700 ms by animating its visibility, and only the
 * page's main thread can end that. Every page but the PDF one reads its document in
 * one piece on that thread, and a document fetched well inside the hold gave it no
 * chance to: the hold ran out unseen, and a spreadsheet of thirty thousand rows left
 * the reader on a blank page for the seconds it took to read, where before the hold
 * they were shown the card. Past this size the card goes up at once, and is given a
 * frame to be drawn in, before the reading starts.
 *
 * A megabyte is a judgment rather than a measurement. Below it, reading is short
 * enough that the hold covers it in the files .vw-wait was measured on; above it, a
 * card that comes and goes quickly costs less than a blank page for a slow one.
 */
var VW_BUSY_BYTES = 1024 * 1024;

/* [doc], once the card is on screen if [size] says reading it will take a while. */
function vwShowBusy(doc, size) {
  var el = document.getElementById("vw-status");
  if (size < VW_BUSY_BYTES || !el || el.style.display === "none") return doc;
  el.classList.remove("vw-wait");
  return new Promise(function (resolve) {
    // The frame the card is drawn in comes after this callback, and the timeout after it
    requestAnimationFrame(function () { setTimeout(function () { resolve(doc); }, 0); });
  });
}

/* Fetch the document being viewed. kind: "buffer" | "text" */
function vwFetchDoc(kind) {
  return fetch(vwDocUrl()).then(function (r) {
    if (!r.ok) throw new Error("Could not read the file (HTTP " + r.status + ")");
    return kind === "text" ? r.text() : r.arrayBuffer();
  }).then(function (doc) {
    return vwShowBusy(doc, kind === "text" ? doc.length : doc.byteLength);
  });
}

/* Anything can be asked for as text, including a multi-gigabyte binary, so the
   text viewer reads a page at a time and lets the reader ask for the next one
   rather than committing the whole file to the DOM up front. */
var VW_TEXT_PAGE = 5 * 1024 * 1024;

/* Encoding of a file from its byte order mark, if it has one. The decoder
   strips the mark itself. */
function vwEncodingOf(bytes) {
  if (bytes.length >= 2 && bytes[0] === 0xFF && bytes[1] === 0xFE) return "utf-16le";
  if (bytes.length >= 2 && bytes[0] === 0xFE && bytes[1] === 0xFF) return "utf-16be";
  return "utf-8";
}

/*
 * Opens the document for paged reading. Resolves to { next }, where next()
 * resolves to { text, bytes, done } for the next VW_TEXT_PAGE bytes.
 */
function vwOpenText() {
  return vwOpenTextPages().then(function (doc) {
    // A page is read into the DOM in one piece, like any other document: see VW_BUSY_BYTES
    var next = doc.next;
    doc.next = function () {
      return next().then(function (page) { return vwShowBusy(page, page.bytes); });
    };
    return doc;
  });
}

function vwOpenTextPages() {
  return fetch(vwDocUrl()).then(function (r) {
    if (!r.ok) throw new Error("Could not read the file (HTTP " + r.status + ")");

    // One streaming decoder across every page, so a multi-byte character split
    // by a page boundary still comes out whole. Decoding stays lenient, so an
    // unfamiliar encoding degrades to replacement characters rather than an error
    var decoder = null;
    function decode(bytes, last) {
      if (!decoder) decoder = new TextDecoder(vwEncodingOf(bytes));
      return { text: decoder.decode(bytes, { stream: !last }), bytes: bytes.length, done: last };
    }

    // A WebView too old for the Streams API has to buffer the whole body
    if (!r.body || !r.body.getReader) {
      return r.arrayBuffer().then(function (buf) {
        var all = new Uint8Array(buf);
        var off = 0;
        return {
          next: function () {
            var end = Math.min(off + VW_TEXT_PAGE, all.length);
            var page = all.subarray(off, end);
            off = end;
            return Promise.resolve(decode(page, off >= all.length));
          }
        };
      });
    }

    var reader = r.body.getReader();
    var pending = [];
    var held = 0;
    var ended = false;

    return {
      next: function () {
        // Overshoot the page by a chunk rather than stopping level with it, so
        // a file that ends exactly on the boundary is known to be finished and
        // does not offer an empty page
        function pump() {
          if (ended || held > VW_TEXT_PAGE) return Promise.resolve();
          return reader.read().then(function (res) {
            if (res.done) { ended = true; return; }
            pending.push(res.value);
            held += res.value.length;
            return pump();
          });
        }
        return pump().then(function () {
          var take = Math.min(held, VW_TEXT_PAGE);
          var page = new Uint8Array(take);
          var off = 0;
          while (off < take) {
            var chunk = pending[0];
            var n = Math.min(chunk.length, take - off);
            page.set(chunk.subarray(0, n), off);
            off += n;
            if (n === chunk.length) pending.shift();
            else pending[0] = chunk.subarray(n);
          }
          held -= take;
          return decode(page, ended && held === 0);
        });
      }
    };
  });
}

var VW_JSON_INDENT = "  ";

/*
 * The largest document worth laying out, in characters.
 *
 * Laying out costs little; drawing the result costs a great deal, because a
 * minified megabyte becomes about a hundred thousand lines. Measured at 390x844
 * with the processor slowed fourfold, which is roughly a mid-range phone: 1 MB
 * takes 1.0 s to lay out and draw against 0.4 s drawn as it came, 2 MB takes
 * 2.3 s against 0.7 s, and 5 MB takes 5.7 s against 1.8 s and needs 96 MB of
 * heap. Tripling the time a large file takes to open is not a trade its reader
 * asked for, and past a hundred thousand lines nobody is reading by scrolling
 * anyway: they are searching, which works either way.
 *
 * A first page shorter than this is also certain to be the whole file, since a
 * page that is not the last is always VW_TEXT_PAGE bytes, and that many bytes
 * cannot decode to fewer characters than this.
 */
var VW_JSON_MAX = 1024 * 1024;

/*
 * The most a layout may come to, past which the file is shown as it came.
 *
 * VW_JSON_MAX bounds what goes in, and what comes out can be far more. Every line
 * is indented by its depth, so a file nested deep grows with the square of how
 * deep: forty kilobytes of brackets inside brackets, which JSON.parse accepts, came
 * to more characters than a string can hold, and the page opened on an error rather
 * than on the file. And bare numbers are two characters a line, so a megabyte of
 * them is five times the hundred thousand lines the cap was measured against.
 */
var VW_JSON_MAX_OUT = 4 * VW_JSON_MAX;
var VW_JSON_MAX_LINES = 150 * 1000;

/*
 * Lays a JSON document out over several lines, indented by depth. Answers null
 * if the text is not JSON, and the caller then shows the file as it is.
 *
 * Only the whitespace between tokens is rewritten; every value is copied
 * through exactly as it was written. That distinction is the whole point. The
 * obvious implementation, JSON.parse followed by JSON.stringify, is a rewrite
 * rather than a reformat: it rounds any number past 2^53 to a different number,
 * turns 1.0 into 1 and 1e5 into 100000, keeps only the last of a set of
 * duplicate keys, and moves keys that look like integers to the front of their
 * object. A viewer that quietly altered what a file said would be worse than
 * one that was hard to read.
 *
 * JSON.parse is still what decides whether the text is JSON. With the grammar
 * proved sound, the pass below only has to know whether it is inside a string.
 */
function vwFormatJson(text) {
  try {
    JSON.parse(text);
  } catch (e) {
    return null;
  }

  var pads = [""];
  function pad(depth) {
    while (pads.length <= depth) pads.push(pads[pads.length - 1] + VW_JSON_INDENT);
    return pads[depth];
  }

  var out = [];
  var n = text.length;
  var depth = 0;
  var i = 0;
  var size = n;
  var lines = 1;

  function newline() {
    var indent = pad(depth);
    out.push("\n", indent);
    size += 1 + indent.length;
    lines++;
  }

  // A container that has just opened and is still empty. Breaking the line is
  // put off until something turns up inside it, so that {} and [] stay whole.
  var fresh = false;
  function begin() {
    if (fresh) {
      newline();
      fresh = false;
    }
  }

  while (i < n) {
    if (size > VW_JSON_MAX_OUT || lines > VW_JSON_MAX_LINES) return null;
    var c = text.charAt(i);

    if (c === '"') {
      begin();
      var str = i;
      i++;
      while (i < n) {
        var s = text.charAt(i);
        i++;
        if (s === "\\") i++;      // whatever follows an escape is not the end
        else if (s === '"') break;
      }
      out.push(text.slice(str, i));
    } else if (c === " " || c === "\t" || c === "\n" || c === "\r") {
      i++;                        // the spacing the file came with is dropped
    } else if (c === "{" || c === "[") {
      begin();
      out.push(c);
      depth++;
      fresh = true;
      i++;
    } else if (c === "}" || c === "]") {
      depth--;
      if (fresh) fresh = false;
      else newline();
      out.push(c);
      i++;
    } else if (c === ",") {
      out.push(",");
      newline();
      i++;
    } else if (c === ":") {
      out.push(": ");
      size++;
      i++;
    } else {
      // A number, or true, false or null. In sound JSON it runs until the next
      // structural character, so it can be copied in one piece.
      begin();
      var lit = i;
      while (i < n && '{}[],: \t\n\r"'.indexOf(text.charAt(i)) < 0) i++;
      out.push(text.slice(lit, i));
    }
  }

  return out.join("");
}

/* Byte count as a short human size, for "showing the first N" notices. */
function vwFormatSize(bytes) {
  var mb = bytes / (1024 * 1024);
  if (mb < 1) return Math.max(1, Math.round(bytes / 1024)) + " KB";
  return (mb >= 10 ? Math.round(mb) : Math.round(mb * 10) / 10) + " MB";
}

window.onerror = function (message) {
  vwError("Something went wrong while rendering", String(message));
};
