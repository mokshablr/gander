/*
 * docx-preview draws a Word file's embedded HTML part, an altChunk, which some tools that
 * turn web pages into Word files write, in an iframe whose srcdoc is that HTML, and gives
 * the frame no sandbox. Unsandboxed, a srcdoc frame shares this page's origin, so script
 * in the part would run as the page the moment the file opened. The page's policy carries
 * into the frame and forbids that already; the sandbox takes script, forms and navigation
 * away from the frame outright, whatever any policy says. It is set as the element is
 * made because a frame's sandbox is fixed when its document loads, and docx-preview fills
 * in srcdoc a moment later. The library stays as upstream ships it (docs/VENDORED.md), so
 * the change is made from here.
 */
var vwMakeElement = document.createElement;
document.createElement = function (name, options) {
  var el = vwMakeElement.call(document, name, options);
  if (String(name).toLowerCase() === "iframe") el.setAttribute("sandbox", "");
  return el;
};

/*
 * docx-preview draws a run set as subscript or superscript twice and keeps the second
 * drawing: renderRun, in 0.4.0 and still in 0.4.1 and on its main branch. Whatever the
 * first drawing did besides make elements stays done. A tab in it joins the tabs that are
 * lined up against their paragraph's stops half a second after the document is up, though
 * it sits in no paragraph, and that pass throws on it. That put "Cannot read properties of
 * null (reading 'getBoundingClientRect')" over a lab report that had drawn whole, and left
 * every tab after it unaligned. A footnote marker in such a run is counted twice the same
 * way, so the first note is numbered 2 and listed twice under the page.
 *
 * The renderer makes every element through the h function in its options, calling it as
 * its own method, and its first call is for the stylesheet, before any run is drawn. That
 * is the one way to the renderer from outside, so it is where this renderer is given a
 * renderRun that draws such a run once, as though it were level, and then moves what it
 * drew into the sub or sup the run asked for, the element the library would have kept.
 */
function makeElement(spec) {
  if (this && this.renderRun && !this.vwRunsOnce) {
    vwSplitAsWordDid(this);
    drawRunsOnce(this);
    drawLrTbAcross(this);
    vwWordLines(this);
  }
  var custom = vwWordElement(spec);
  var el = docx.defaultOptions.h(spec);
  for (var name in custom) el.style.setProperty(name, custom[name]);
  return el;
}

function drawRunsOnce(renderer) {
  var draw = renderer.renderRun;
  renderer.vwRunsOnce = true;
  renderer.renderRun = function (run) {
    if (!run.verticalAlign) return draw.call(this, run);
    var span = draw.call(this, Object.assign({}, run, { verticalAlign: null }));
    if (span) {
      var raised = this.h({ tagName: run.verticalAlign });
      while (span.firstChild) raised.appendChild(span.firstChild);
      span.appendChild(raised);
    }
    return span;
  };
}

/*
 * docx-preview draws a cell whose text direction is lrTb, Word's plain left-to-right text, as
 * vertical. lrTb is the only direction it makes vertical-lr, so that one is taken back off.
 */
function drawLrTbAcross(renderer) {
  var draw = renderer.renderTableCell;
  renderer.renderTableCell = function (cell) {
    if (cell.cssStyle && cell.cssStyle["writing-mode"] === "vertical-lr") {
      delete cell.cssStyle["writing-mode"];
      delete cell.cssStyle.transform;
    }
    return draw.call(this, cell);
  };
}

/* Word bullet lists use Symbol/Wingdings private-use characters (U+F000 range)
   that Android has no glyphs for. Swap them for Unicode equivalents. */
function fixSymbolChars(root) {
  var map = {
    0xF0B7: "•", 0xF0A7: "▪", 0xF0A8: "◦", 0xF076: "❖",
    0xF0D8: "➢", 0xF0FC: "✓", 0xF0B0: "°", 0xF0D0: "➔"
  };
  var pua = new RegExp("[\\uF000-\\uF0FF]", "g");
  var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  var n;
  while ((n = walker.nextNode())) {
    pua.lastIndex = 0;
    if (pua.test(n.nodeValue)) {
      pua.lastIndex = 0;
      n.nodeValue = n.nodeValue.replace(pua, function (c) {
        return map[c.charCodeAt(0)] || "•";
      });
    }
  }
}

/*
 * Scale the pages so the widest of them fills the screen, for the zoom in the stylesheet.
 *
 * The widest and not the first: a document can mix portrait and landscape sections, and
 * sizing off page one would push a landscape page past the layout width, widen the
 * viewport for the whole document, and shrink every portrait page to pay for it. Taking
 * the widest means nothing overflows and the pages keep their real proportions to each
 * other, which is the point of drawing them as paper at all.
 *
 * That is a trade, and only for a document that mixes the two. Measured on such a one:
 * its portrait pages go from 80.2% of the screen to 76.1%, while its landscape page goes
 * from 103.7%, which is to say wider than the screen and needing a sideways drag to read,
 * to 98.5% and whole. A document of one orientation, which is nearly all of them, only
 * gains: 83% to 98%.
 *
 * The ratio is reset to 1 before measuring rather than divided out of the last one,
 * because a zoomed element reports its zoomed width and two roundings of that would
 * drift. Writing the property forces the recalculation that the read below needs.
 *
 * That reset is doing a second job, and it is the reason not to optimise it away. Whether
 * getBoundingClientRect reports a zoomed element's box with the zoom in it changed when
 * zoom was standardised, around Chromium 128: WebView 152 includes it and WebView 103,
 * checked on an emulator, does not. Measuring at 1 means this never has to know which,
 * because at 1 the two agree. The rendering is right on both; it is only the reading back
 * that differs, which is worth knowing before believing a rect on an older engine.
 *
 * A page already wider than the screen, which a landscape one is, comes out under 1 and
 * is shrunk to fit. That is the same size it reaches today by overflowing and making the
 * WebView zoom out to fit it, and it costs the document nothing, but it keeps the layout
 * viewport at 980 where the overflow route moves it.
 *
 * Not re-run on rotation, where vwFitHeight is. The width this divides by is the WebView's
 * own layout width, which is 980 in both orientations because there is no viewport meta
 * for the turn to reinterpret, so the ratio a rotation would arrive at is the one already
 * set. Checked on the phone: 1.181 in portrait and in landscape.
 */
function fitPageWidth() {
  var wrap = document.querySelector(".docx-wrapper");
  var pages = wrap ? wrap.querySelectorAll("section.docx") : [];
  if (!pages.length) return;

  wrap.style.setProperty("--vw-page-zoom", "1");
  var pad = getComputedStyle(wrap);
  var room = wrap.clientWidth -
    parseFloat(pad.paddingLeft || 0) - parseFloat(pad.paddingRight || 0);

  var widest = 0, widestPage = null;
  for (var i = 0; i < pages.length; i++) {
    var width = pages[i].getBoundingClientRect().width;
    if (width > widest) { widest = width; widestPage = pages[i]; }
  }
  if (room > 0 && widest > 0) {
    wrap.style.setProperty("--vw-page-zoom", room / widest);
    /* 1 where a zoomed page's rect includes its zoom, 1 / zoom where it does not, so the
       page counter can read a rect either way. The widest page now fills the room. */
    vwPages.rectScale = widestPage.getBoundingClientRect().width / room;
  }
}

/* A page this much taller than its paper still prints on one sheet, as lines set in the phone's fonts can run */
var VW_PRINT_OVER = 0.02;

/*
 * docx-preview sets a footer in the bottom margin with a negative margin below it, and print
 * keeps each sheet's whole margin free, so the footer printed on a sheet of its own. In print
 * the part of the margin it reaches into is the page's instead (docx.html), which leaves the
 * footer where it was. A page that runs a little past its paper gives up the part of the margin
 * it runs into as well, and prints on one sheet with its foot that much lower, where it took a
 * second sheet for a line or less. Read before fitPageWidth zooms the pages.
 */
function vwPrintFooters(sheets) {
  for (var i = 0; i < sheets.length; i++) {
    var footer = sheets[i].querySelector(":scope > footer");
    var into = footer ? Math.max(0, -parseFloat(getComputedStyle(footer).marginBottom) || 0) : 0;
    var style = getComputedStyle(sheets[i]);
    var paper = parseFloat(style.minHeight) || 0;
    var over = Math.max(0, sheets[i].getBoundingClientRect().height - paper);
    var pad = parseFloat(style.paddingBottom) || 0;
    /* A page too long for one sheet keeps its whole margin, which it repeats on every sheet. A
       rounding's worth over is not too long: a page reads a hair past paper that is not a whole
       number of pixels tall, as A4 is not, and a footer can fill the whole margin. */
    if (!(into + over > 0.5) || over > paper * VW_PRINT_OVER || into + over > pad + VW_SLACK) continue;
    sheets[i].style.setProperty("--vw-print-pad", Math.max(0, pad - into - over) + "px");
    sheets[i].classList.add(into > 0 ? "vw-print-footer" : "vw-print-fit");
  }
}

/* What the app sends besides search: "g<n>" to go to a page, "i1" and "i0" for night mode */
window.vwPageCommand = function (msg) {
  var verb = msg.charAt(0);
  if (verb === "g") vwGoToPage(Math.floor(Number(msg.slice(1))));
  else if (verb === "i") vwSetNight(msg.charAt(1) === "1");
};

/* The document may be up before the port arrives, and would otherwise say nothing until scrolled */
window.vwPortReady = function () {
  vwPageSent = 0;
  vwReportPage();
};

var VW_DRAW = {
  inWrapper: true,
  breakPages: true,
  renderHeadersFooters: true,
  ignoreLastRenderedPageBreak: true,
  experimental: true,
  keepOrigin: true,
  h: makeElement
};

/*
 * renderAsync in its two halves, so docx-lines.js can read what docx-preview leaves out of the
 * styles from the XML it has just parsed (keepOrigin keeps it) instead of parsing it again, and
 * docx-pages.js can write in the page records it measured on the first drawing.
 */
function vwDrawWord(buf) {
  return docx.parseAsync(buf, VW_DRAW)
    .then(function (doc) {
      vwContextualSpacing(doc);
      var model = vwNumberModel(doc);
      if (vwPages.turns) vwAddTurns(model, vwPages.turns);
      return docx.renderDocument(doc, VW_DRAW);
    })
    .then(function (nodes) {
      var container = document.getElementById("container");
      container.innerHTML = "";
      nodes.forEach(function (node) { container.appendChild(node); });
    });
}

/*
 * A second drawing makes its pictures afresh, so the first one's are let go. The first drawing
 * leaves the page before the second is parsed: docx-preview lines up every tab half a second
 * after it draws, laying the page out once per tab, and on the first drawing's pages that pass
 * came in the middle of the second parse. On a 498-page file it took over 3 s; on pages already
 * gone it lays nothing out.
 */
function vwDrawWordAgain(buf) {
  var container = document.getElementById("container");
  var pictures = [].map.call(container.querySelectorAll('img[src^="blob:"]'),
    function (img) { return img.src; });
  container.innerHTML = "";
  return vwDrawWord(buf).then(function () {
    pictures.forEach(function (url) { URL.revokeObjectURL(url); });
  });
}

/*
 * Word can save a document as a single XML file, a pkg:package holding every part a .docx
 * zips together, XML parts as they are and binary ones in base64. Some systems hand these out
 * named .doc, and the app sends those here once their first bytes show what they are
 * (wordFileOpensAs). docx-preview reads only the zip, so the parts are zipped back up first.
 * Anything that is not such a package, a .docx included, goes on to docx-preview untouched.
 */
var VW_XML_PACKAGE = "http://schemas.microsoft.com/office/2006/xmlPackage";

function vwEscapeXml(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
}

function vwWordFromPackage(buf) {
  var head = new Uint8Array(buf, 0, Math.min(buf.byteLength, 2));
  if (head[0] === 0x50 && head[1] === 0x4B) return Promise.resolve(buf);
  var xml = new DOMParser().parseFromString(new TextDecoder().decode(buf), "application/xml");
  var root = xml.documentElement;
  if (!root || root.namespaceURI !== VW_XML_PACKAGE || root.localName !== "package") {
    return Promise.resolve(buf);
  }
  var zip = new JSZip();
  var serializer = new XMLSerializer();
  var types = "";
  var parts = root.getElementsByTagNameNS(VW_XML_PACKAGE, "part");
  for (var i = 0; i < parts.length; i++) {
    var part = parts[i];
    var name = String(part.getAttributeNS(VW_XML_PACKAGE, "name") || "").replace(/^\/+/, "");
    if (!name) continue;
    var xmlData = part.getElementsByTagNameNS(VW_XML_PACKAGE, "xmlData")[0];
    var binaryData = part.getElementsByTagNameNS(VW_XML_PACKAGE, "binaryData")[0];
    if (xmlData && xmlData.firstElementChild) {
      zip.file(name, '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
        serializer.serializeToString(xmlData.firstElementChild));
    } else if (binaryData) {
      zip.file(name, binaryData.textContent.replace(/\s+/g, ""), { base64: true });
    } else {
      continue;
    }
    types += '<Override PartName="/' + vwEscapeXml(name) + '" ContentType="' +
      vwEscapeXml(part.getAttributeNS(VW_XML_PACKAGE, "contentType") || "") + '"/>';
  }
  zip.file("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' + types + "</Types>");
  return zip.generateAsync({ type: "arraybuffer" });
}

/* Nothing is read below docx-preview's floor. The card is already up, and the library
   in the head may not even have parsed, which below Chromium 80 it cannot: issue #31,
   where this said "docx is not defined" instead. See vwWebViewTooOld in app.js. */
if (!vwWebViewTooOld("Word documents")) {
  vwFetchDoc("buffer")
    .then(vwWordFromPackage)
    .then(function (buf) {
      return vwDrawWord(buf)
        .then(function () {
          /* Out of sight until its pages are final, or a second drawing would move them on screen */
          document.getElementById("container").style.visibility = "hidden";
          return vwFindMissingPages();
        })
        .then(function (missing) { if (missing) return vwDrawWordAgain(buf); });
    })
    .then(function () {
      /* Tabs are lined up once the fonts are in, which docx-preview's own pass waited half a second for */
      document.body.getBoundingClientRect();
      return document.fonts.ready;
    })
    .then(function () {
      vwPages.settled = true;
      var container = document.getElementById("container");
      container.style.visibility = "";
      vwDisarmLinks(container);
      fixSymbolChars(container);
      vwNumberPages(vwSheets());
      vwLineUpTabs();
      if (vwNightOn) vwPrepareNight();
      vwHideTails(vwSheets());
      vwPrintFooters(vwSheets());
      /* Width first: it decides whether the document still overflows 980, which is the
         one thing that moves the height vwFitHeight is about to read. */
      fitPageWidth();
      vwFitHeight();
      vwPrintSizes(vwSheets());
      vwStatusDone();
      vwFollowPages();
    })
    .catch(function (e) { vwError("Could not render this Word document", String(e)); });
}
