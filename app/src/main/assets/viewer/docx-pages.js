"use strict";

/*
 * Word's own pages, issue #47, and measured ones, issue #55.
 *
 * docx-preview starts a page only at a page or section break, so a document written as one
 * flow of text came out as a single sheet. Word records where each page began when it last
 * laid the file out, as w:lastRenderedPageBreak, and where a file carries that record its
 * pages are split there and numbered here. WPS, LibreOffice and Google Docs write no such
 * record, so for their files the pages are worked out from the first drawing instead: where
 * each page's paper runs out, records are written into the document as Word would have
 * written them, and it is drawn again (vwMeasurePages).
 */
var vwPages = {
  /* Whether this document's pages are Word's */
  split: false,
  /* Whether the document records no page at all, so its pages are measured; decided on its
     first drawing, since the second carries the records vwAddTurns wrote */
  measure: null,
  /* Where the measured pages turn, for the second drawing; see vwSheetTurns */
  turns: null,
  /* Whether the drawing on screen is the last, which the page counter and night mode wait for:
     a second drawing replaces the first's pages and everything marked on them */
  settled: false,
  /* Word's own count of its pages when it last saved the file, from docProps/app.xml */
  wordPages: 0,
  /* The places a second drawing starts a page Word left no record of; see vwFindMissingPages */
  turnAt: null,
  /* The section properties each page was made with, in order */
  props: [],
  /* Fields being read, innermost last; see trackField */
  fields: [],
  nextField: 0,
  /* This drawing's tabs and its default tab stop, held for vwLineUpTabs */
  tabs: null,
  /* This drawing's footnotes, numbered as their references are drawn; see vwNoteBook */
  notes: vwNoteBook(),
  /* The room for the body on a page measured from a sheet; see vwSplitAsWordDid */
  pageRoom: null
};

/* The notes drawn so far: how many are numbered, each one's number by its id, and the ids
   already listed under a page */
function vwNoteBook() {
  return { count: 0, of: Object.create(null), listed: Object.create(null) };
}

function vwIsPageMark(node) {
  return node.type === "break" && node.break === "lastRenderedPageBreak";
}

function vwHasPageMark(node) {
  var kids = node.children || [];
  for (var i = 0; i < kids.length; i++) {
    if (vwIsPageMark(kids[i]) || vwHasPageMark(kids[i])) return true;
  }
  return false;
}

/* Whether anything would show: a paragraph that only holds a bookmark or a break is empty. */
var VW_BLANK = { "break": 1, bookmarkStart: 1, bookmarkEnd: 1 };
var VW_HOLDERS = { run: 1, hyperlink: 1, smartTag: 1, inserted: 1, paragraph: 1 };

function vwHasInk(node) {
  var kids = node.children || [];
  for (var i = 0; i < kids.length; i++) {
    var c = kids[i];
    if (VW_BLANK[c.type]) continue;
    if (VW_HOLDERS[c.type] ? vwHasInk(c) : true) return true;
  }
  return false;
}

function vwColumns(props) {
  return (props && props.columns && props.columns.numberOfColumns) || 1;
}

/*
 * Word records where each column began as well as each page, and the two look the same. So a
 * section set in columns loses every record, [all], and a section running on from one loses
 * the record at its top, which marks where those columns ended.
 */
function vwColumnMarksOut(elements, all) {
  var inked = false;
  function out(node) {
    if (!node.children) return node;
    var kept = [], changed = false;
    node.children.forEach(function (c) {
      if (vwIsPageMark(c) && (all || !inked)) { changed = true; return; }
      if (!c.children && !VW_BLANK[c.type]) inked = true;
      var d = out(c);
      if (d !== c) changed = true;
      kept.push(d);
    });
    return changed ? Object.assign({}, node, { children: kept }) : node;
  }
  return elements.map(out);
}

/*
 * A section with no header or footer of a kind of its own shows the one before it, as Word
 * does. docx-preview drew none there, which on a document of several sections left the page
 * numbers off every page after the first section.
 */
function vwInheritRefs(prev, props, key) {
  var own = props[key] || [];
  var inherited = (prev && prev[key] || []).filter(function (ref) {
    return !own.some(function (mine) { return mine.type === ref.type; });
  });
  if (inherited.length) props[key] = own.concat(inherited);
}

/*
 * Installed from the h hook in docx.js, before the first page is made. Replaces how
 * docx-preview groups the body into pages, with Word's records where the file has them.
 */
function vwSplitAsWordDid(renderer) {
  var createPageElement = renderer.createPageElement;
  var renderHeaderFooter = renderer.renderHeaderFooter;
  var renderRun = renderer.renderRun;
  var renderParagraph = renderer.renderParagraph;
  var renderTableRow = renderer.renderTableRow;
  var renderText = renderer.renderText;

  renderer.splitBySection = function (elements, bodyProps) {
    /* A second drawing counts its pages, fields and footnotes afresh */
    vwPages.props = [];
    vwPages.fields = [];
    vwPages.notes = vwNoteBook();
    var app = this.document.extendedPropsPart;
    vwPages.wordPages = (app && app.props && app.props.pages) || 0;
    vwPages.split = elements.some(vwHasPageMark);
    if (vwPages.measure === null) vwPages.measure = !vwPages.split;
    return splitPieces(this, elements, bodyProps);
  };

  renderer.groupByPageBreaks = function (pieces) {
    var pages = [], page = [];
    pieces.forEach(function (piece) {
      page.push(piece);
      if (piece.pageBreak) { pages.push(page); page = []; }
    });
    if (page.length) pages.push(page);
    return pages;
  };

  renderer.createPageElement = function (className, props, docStyle) {
    vwPages.props.push(props);
    return createPageElement.call(this, className, props, docStyle);
  };

  /* A header or footer is read on its own, so a field left open in one cannot reach another */
  renderer.renderHeaderFooter = function () {
    var outer = vwPages.fields;
    vwPages.fields = [];
    try {
      return renderHeaderFooter.apply(this, arguments);
    } finally {
      vwPages.fields = outer;
    }
  };

  renderer.renderRun = function (run) {
    if (run.fieldRun) trackField(run);
    var span = renderRun.call(this, run);
    var field = vwPages.fields[vwPages.fields.length - 1];
    if (span && field && field.showing && field.kind) {
      span.setAttribute("data-vw-field", field.id + " " + field.kind + " " + field.format);
    }
    return span;
  };

  /*
   * The places splitPieces numbered, marked for vwFindMissingPages to measure. In a document
   * whose pages are measured, every paragraph, row and piece of text is also named by its
   * place in the document (vwNumberModel), for vwSheetTurns to say where a page turns.
   */
  renderer.renderParagraph = function (para) {
    var el = vwMarkPlace(renderParagraph.call(this, para), para);
    if (el && vwPages.measure && para.vwP !== undefined) {
      el.setAttribute("data-vw-p", para.vwP);
      var keep = vwKeeps(this, para);
      if (keep) el.setAttribute("data-vw-keep", keep);
    }
    return el;
  };
  renderer.renderTableRow = function (row) {
    var el = vwMarkPlace(renderTableRow.call(this, row), row);
    if (el && vwPages.measure && row.vwR !== undefined) {
      el.setAttribute("data-vw-r", row.vwR);
      if (vwIsHeaderRow(row)) el.setAttribute("data-vw-head", "");
    }
    return el;
  };
  renderer.renderText = function (text) {
    var node = renderText.call(this, text);
    if (node && vwPages.measure && text.vwT !== undefined) node.vwT = text.vwT;
    return node;
  };

  /*
   * docx-preview lines up tabs half a second after it draws, on a timer. Here its tabs are held
   * for vwLineUpTabs to line up once the drawing is on the page and its fonts are in, so a page
   * is measured with its tabs as they are drawn, and before fitPageWidth zooms the pages: tabs
   * are placed by getBoundingClientRect, which takes the zoom in from about Chromium 128.
   */
  renderer.refreshTabStops = function () {
    if (this.options.experimental) vwPages.tabs = { tabs: this.currentTabs, size: this.defaultTabSize };
  };

  /*
   * docx-preview numbers a footnote by its place in its own page's list, so a document cut
   * into pages started again at 1 on each. Word numbers them through the document, and so does
   * this. A reference drawn again, as in a table's header row repeated on each page, keeps its
   * note's number, and the note is listed once, under the first page that cites it. References
   * are also marked, for vwNotesOn to find the notes each page measured from a sheet holds.
   */
  var renderFootnoteReference = renderer.renderFootnoteReference;
  renderer.renderFootnoteReference = function (ref) {
    var sup = renderFootnoteReference.call(this, ref), notes = vwPages.notes;
    var n = ref.id != null && notes.of[ref.id];
    if (!n) {
      n = ++notes.count;
      if (ref.id != null) notes.of[ref.id] = n;
    }
    if (sup && sup.setAttribute) {
      sup.textContent = String(n);
      sup.setAttribute("data-vw-note", "");
    }
    return sup;
  };
  var renderNotes = renderer.renderNotes;
  renderer.renderNotes = function (ids, map) {
    if (map !== this.footnoteMap) return renderNotes.apply(this, arguments);
    var notes = vwPages.notes;
    var fresh = ids.filter(function (id) {
      if (id == null) return true;
      if (notes.listed[id]) return false;
      notes.listed[id] = true;
      return true;
    });
    var list = renderNotes.call(this, fresh, map);
    if (list && list.setAttribute) {
      /* docx-preview lists the notes it has, in the order cited */
      var shown = fresh.filter(function (id) { return map[id]; });
      for (var i = 0; i < list.children.length && i < shown.length; i++) {
        if (notes.of[shown[i]]) list.children[i].value = notes.of[shown[i]];
      }
      list.setAttribute("data-vw-notes", "");
    }
    return list;
  };

  /* Each part of a page keeps its section's properties, for vwSheetTurns to read the margins of a page that starts in it */
  var createSectionContent = renderer.createSectionContent;
  renderer.createSectionContent = function (props) {
    var article = createSectionContent.call(this, props);
    if (article) article.vwProps = props;
    return article;
  };

  /*
   * The room for the body on the page at [index] made with [props], for a page vwSheetTurns
   * cuts from a sheet: its own margins, which a section running on from the sheet's first can
   * change, and its header and footer, which can be an even page's or not the first page's.
   * The page is drawn once, out of sight, and let go.
   */
  vwPages.pageRoom = function (props, index) {
    var cache = props.vwRoom || (props.vwRoom = {}), key = index % 2;
    if (cache[key] !== undefined) return cache[key];
    var wrap = document.querySelector(".docx-wrapper");
    if (!wrap) return 0;
    var page = createPageElement.call(renderer, renderer.className, props, {});
    page.style.position = "absolute";
    page.style.visibility = "hidden";
    wrap.appendChild(page);
    try {
      renderer.renderHeaderFooter(props.headerRefs, props, index, false, page);
      renderer.renderHeaderFooter(props.footerRefs, props, index, false, page);
      var style = getComputedStyle(page);
      cache[key] = (parseFloat(style.minHeight) || 0) - (parseFloat(style.paddingTop) || 0) -
        (parseFloat(style.paddingBottom) || 0) - vwOverMargin(page.querySelector(":scope > header")) -
        vwOverMargin(page.querySelector(":scope > footer"));
    } finally {
      wrap.removeChild(page);
    }
    return cache[key];
  };
}

/*
 * Lines up the tabs of the drawing on the page, which docx-preview left for later. Its own pass
 * reads where a tab sits and then moves it, tab by tab, so the page was laid out again for each
 * tab: with the CPU slowed four times, 3,600 tabs on one long sheet took 3.6 s and 9,000 took
 * 25 s. This is the same pass (vwTabPlan) with the reads for one tab of every paragraph made
 * before any of them moves, so the page is laid out once for each tab a paragraph holds. A tab
 * is placed from its own paragraph's left edge and moves only the text after it in that
 * paragraph, so the tabs of one paragraph move another's only beside a float: a line beside an
 * anchored picture, a drop cap or a floating table starts after it, and a paragraph that grows
 * or shrinks as its tabs are set can move the paragraphs below it from beside the float to
 * below it, or back. So where the page holds a float, each tab is read with where the floats
 * beside its paragraph sit (vwTabKey), the page is read once more after the rounds, and a
 * paragraph with a tab that no longer has the same floats beside it is put back as drawn and
 * lined up on its own, a tab at a time, as upstream's pass reads it. A document without floats
 * is lined up in the rounds alone. A table laid out to its content moves its cells' edges as
 * their tabs are set too, in either order, and is left to the rounds.
 */
function vwLineUpTabs() {
  var held = vwPages.tabs;
  vwPages.tabs = null;
  if (!held) return;
  try {
    var paras = [], of = new Map();
    held.tabs.forEach(function (tab) {
      /* docx-preview's pass threw on a tab outside any paragraph; a drawing already gone has nothing to line up */
      var p = tab.span.isConnected && tab.span.closest("p");
      if (!p) return;
      var group = of.get(p);
      if (!group) {
        group = { p: p, tabs: [] };
        of.set(p, group);
        paras.push(group);
      }
      group.tabs.push(tab);
    });
    var pass = { size: held.size, ratio: vwPointsPerPixel(), floats: vwFloats() };
    vwLineUpInRounds(paras, pass);
    for (var n = 0; pass.floats.length && n < VW_TAB_PASSES; n++) {
      var moved = vwTabsMoved(paras, pass);
      if (!moved.length) break;
      moved.forEach(function (group) {
        group.tabs.forEach(vwUnsetTab);
        vwLineUpInRounds([group], pass);
      });
    }
  } catch (e) {
    /* The tabs not yet lined up stay as drawn, as when docx-preview's own pass threw */
  }
}

/* How often the page is read again after the rounds: a paragraph lined up again can move the
   paragraphs below it past a float's edge in turn, so a pass can leave the next one more to do */
var VW_TAB_PASSES = 4;

/* The rounds: the tab at [k] of every paragraph in [paras] read, then every one of them set */
function vwLineUpInRounds(paras, pass) {
  for (var k = 0; paras.length; k++) {
    var floats = pass.floats.length ? vwFloatBoxes(pass.floats) : null;
    var plans = paras.map(function (group) {
      var tab = group.tabs[k], para = group.p.getBoundingClientRect();
      if (floats) {
        tab.vwKey = vwTabKey(group.p, para, floats, null);
        if (!tab.vwDrawn) tab.vwDrawn = { text: tab.span.textContent, style: tab.span.getAttribute("style") };
      }
      return vwTabPlan(tab, group.p, pass.size, pass.ratio, para);
    });
    plans.forEach(function (plan, i) { if (plan) vwSetTab(paras[i].tabs[k].span, plan); });
    paras = paras.filter(function (group) { return group.tabs.length > k + 1; });
  }
}

/* The paragraphs with a tab that no longer has beside it the floats it was read beside */
function vwTabsMoved(paras, pass) {
  var floats = vwFloatBoxes(pass.floats);
  return paras.filter(function (group) {
    var para = group.p.getBoundingClientRect();
    return group.tabs.some(function (tab) {
      return tab.vwKey && !vwSameKey(tab.vwKey.edges, vwTabKey(group.p, para, floats, tab.vwKey.height).edges);
    });
  });
}

/* A tab as docx-preview drew it, for a paragraph to be lined up again from the start */
function vwUnsetTab(tab) {
  if (!tab.vwDrawn) return;
  tab.span.textContent = tab.vwDrawn.text;
  if (tab.vwDrawn.style === null) tab.span.removeAttribute("style");
  else tab.span.setAttribute("style", tab.vwDrawn.style);
}

/*
 * Where each float beside paragraph [p], whose box is [para], sits against it. A float is
 * beside it where its margin box reaches into the paragraph's, read [height] tall or as it is,
 * so that the paragraph's own growth as its tabs are set pulls no float below it in. A float
 * drawn after the paragraph sits below its lines, and one anchored inside it or one that holds
 * it moves with it, so none of them counts.
 */
function vwTabKey(p, para, floats, height) {
  var edges = [];
  var bottom = para.top + (height === null ? para.height : height);
  floats.forEach(function (f) {
    // The overlap test goes first: it rules out nearly every float, and the tree walks cost seconds on long files
    if (f.bottom <= para.top || f.top >= bottom) return;
    if (f.el.contains(p) || (p.compareDocumentPosition(f.el) & Node.DOCUMENT_POSITION_FOLLOWING)) return;
    edges.push(f.left - para.left, f.right - para.left, f.top - para.top, f.bottom - para.top);
  });
  return { edges: edges, height: para.height };
}

function vwSameKey(a, b) {
  if (a.length !== b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (Math.abs(a[i] - b[i]) > VW_SLACK) return false;
  }
  return true;
}

/* What docx-preview floats, which it does by inline style: anchored drawings, drop caps and floating tables, with the margins a line keeps clear of too */
function vwFloats() {
  var floats = [], styled = document.querySelectorAll('.docx-wrapper [style*="float"]');
  for (var i = 0; i < styled.length; i++) {
    var style = getComputedStyle(styled[i]);
    if (style.cssFloat === "none") continue;
    floats.push({ el: styled[i], margins: [style.marginTop, style.marginRight, style.marginBottom, style.marginLeft].map(function (m) {
      return parseFloat(m) || 0;
    }) });
  }
  return floats;
}

function vwFloatBoxes(floats) {
  return floats.map(function (f) {
    var box = f.el.getBoundingClientRect(), m = f.margins;
    return { el: f.el, top: box.top - m[0], right: box.right + m[1], bottom: box.bottom + m[2], left: box.left - m[3] };
  });
}

/* Points to a pixel, as docx-preview reads it: 100pt over the whole pixels a div that wide takes */
function vwPointsPerPixel() {
  var probe = document.createElement("div");
  probe.style.width = "100pt";
  document.body.appendChild(probe);
  var ratio = 100 / probe.offsetWidth;
  document.body.removeChild(probe);
  return ratio;
}

var VW_TAB_LEFT = { pos: 0, leader: "none", style: "left" };

/*
 * docx-preview's updateTabStop up to where it writes: the stop a tab reaches and the width that
 * takes it there, with the paragraph's own stops, then default stops to its width, at most 50.
 * Null for a tab past the last stop, which stays as drawn. Reads only, and takes the paragraph's
 * box as [para] where the caller has read it. It differs from upstream's only for a right or
 * centred tab with nothing drawn after it.
 */
function vwTabPlan(tab, p, size, ratio, para) {
  var span = tab.span, box = span.getBoundingClientRect();
  para = para || p.getBoundingClientRect();
  var margin = parseFloat(getComputedStyle(p).marginLeft);
  var stops = tab.stops && tab.stops.length > 0 ? tab.stops.map(function (s) {
    return { pos: parseFloat(s.position), leader: s.leader, style: s.style };
  }).sort(function (a, b) { return a.pos - b.pos; }) : [VW_TAB_LEFT];
  var width = para.width * ratio, step = parseFloat(size);
  for (var pos = stops[stops.length - 1].pos + step; pos < width && stops.length < 50; pos += step) {
    stops.push({ pos: pos, leader: "none", style: "left" });
  }
  var at = (box.left - (para.left + margin)) * ratio, stop = null;
  for (var i = 0; i < stops.length && !stop; i++) {
    if (stops[i].style !== "clear" && stops[i].pos > at) stop = stops[i];
  }
  if (!stop) return null;
  if (stop.style !== "right" && stop.style !== "center") return { stop: stop, spacing: stop.pos - at };
  /* The text from the tab to the next one, or to the paragraph's end, ends at the stop or centres on it */
  var tabs = [].slice.call(p.querySelectorAll("." + span.className)), next = tabs[tabs.indexOf(span) + 1];
  var range = document.createRange();
  range.setStart(span, 1);
  if (next) range.setEndBefore(next); else range.setEndAfter(p);
  var text = range.getBoundingClientRect(), share = stop.style === "center" ? 0.5 : 1;
  /* With nothing drawn after it, as when it ends its paragraph, the tab sets empty text at its
     own end, and never pulls its line back where that end is past the stop. Upstream read the
     empty range as a box at the page's left edge, so a contents line whose dotted tab ends it
     had the dots drawn past its line, on a line of their own. */
  if (!text.width && !text.height) {
    return { stop: stop, spacing: Math.max(0, stop.pos - (box.right - (para.left - margin)) * ratio) };
  }
  return { stop: stop, spacing: stop.pos - (text.left + share * text.width - (para.left - margin)) * ratio };
}

/* The rest of updateTabStop: the tab widened to its stop, with the stop's leader drawn under it */
function vwSetTab(span, plan) {
  span.innerHTML = "&nbsp;";
  span.style.textDecoration = "inherit";
  span.style.wordSpacing = plan.spacing.toFixed(0) + "pt";
  switch (plan.stop.leader) {
    case "dot":
    case "middleDot":
      span.style.textDecoration = "underline";
      span.style.textDecorationStyle = "dotted";
      break;
    case "hyphen":
    case "heavy":
    case "underscore":
      span.style.textDecoration = "underline";
  }
}

/* Word's keep with next ("n") and keep lines together ("l"), set on the paragraph or its styles */
function vwKeeps(renderer, para) {
  var next = para.keepNext, lines = para.keepLines;
  var style = renderer.findStyle(para.styleName);
  for (var depth = 0; style && depth < 20; depth++) {
    var props = style.paragraphProps || {};
    if (next === undefined) next = props.keepNext;
    if (lines === undefined) lines = props.keepLines;
    style = style.basedOn ? renderer.findStyle(style.basedOn) : null;
  }
  return (next ? "n" : "") + (lines ? "l" : "");
}

function vwMarkPlace(el, node) {
  var spot = node.vwPlace;
  if (el && spot && spot.canTurn) el.setAttribute("data-vw-place", spot.n + " " + spot.stretch);
  return el;
}

/* A paragraph with no run: Word records where a page began in its first run, so here it cannot */
function vwRunless(node) {
  return !(node.children || []).some(function (c) {
    return c.type === "run" || !vwRunless(c);
  });
}

/* docx-preview reads Word's <w:tblHeader/>, which carries no value, as null, and a row
   without one has no isHeader */
function vwIsHeaderRow(row) {
  return "isHeader" in row && row.isHeader !== false;
}

function vwRowStartsRunless(row) {
  var cell = (row.children || [])[0];
  var first = cell && (cell.children || [])[0];
  return !!first && first.type === "paragraph" && vwRunless(first);
}

/*
 * A field is a code between its begin and separate marks, then the value it showed between
 * separate and end. docx-preview drops the code and draws that saved value, so the runs
 * showing a page number are marked here for vwNumberPages to write the right one into.
 */
var VW_FIELD = /^\s*(PAGE|NUMPAGES|SECTIONPAGES)\b(.*)$/i;
var VW_FIELD_FORMATS = {
  roman: "lowerRoman", ROMAN: "upperRoman", Roman: "upperRoman",
  alphabetic: "lowerLetter", ALPHABETIC: "upperLetter", Alphabetic: "upperLetter",
  arabic: "decimal", Arabic: "decimal", ARABIC: "decimal", ArabicDash: "numberInDash"
};

function trackField(run) {
  var fields = vwPages.fields;
  (run.children || []).forEach(function (part) {
    var top = fields[fields.length - 1];
    if (part.type === "instruction") {
      if (top && !top.showing) top.code += part.text || "";
    } else if (part.type === "complexField") {
      if (part.charType === "begin") {
        fields.push({ id: ++vwPages.nextField, code: "", showing: false });
      } else if (part.charType === "separate" && top) {
        top.showing = true;
        var said = VW_FIELD.exec(top.code);
        if (said) {
          var as = /\\\*\s*(\w+)/.exec(said[2]);
          top.kind = said[1].toUpperCase();
          top.format = (as && VW_FIELD_FORMATS[as[1]]) || "-";
        }
      } else if (part.charType === "end") {
        fields.pop();
      }
    }
  });
}

/*
 * The body as pages. A section break starts one unless the next section runs on, a page
 * break always does, and Word's record does once the page has something on it, so a record
 * that repeats a break already made adds no empty page.
 */
function splitPieces(renderer, elements, bodyProps) {
  var sections = [], held = [];
  elements.forEach(function (el) {
    held.push(el);
    if (el.type === "paragraph" && el.sectionProps) {
      sections.push({ props: el.sectionProps, elements: held });
      held = [];
    }
  });
  sections.push({ props: bodyProps, elements: held });

  var pieces = [], piece = null, inked = false;
  /* Places numbered, stretches between ink, and places with no ink after them yet */
  var places = 0, stretch = 0, open = [];
  function start(props) {
    piece = { sectProps: props, elements: [], pageBreak: false };
    pieces.push(piece);
  }
  /*
   * Numbers a place a page can begin without Word's record, the same on every drawing, and
   * says whether this drawing turns there. A turn makes a page only with ink above and below
   * it on the page, and places with no ink between them share a stretch, one turn to each.
   */
  function place(node, canTurn) {
    /* An object, so the copy of the paragraph that is drawn sees a verdict reached later */
    var spot = node.vwPlace = { n: places++, stretch: stretch, canTurn: canTurn };
    if (canTurn) open.push(spot);
    return !!(vwPages.turnAt && vwPages.turnAt[spot.n]);
  }
  function inkSeen() {
    stretch++;
    open = [];
  }
  function pageEnds() {
    open.forEach(function (spot) { spot.canTurn = false; });
    open = [];
  }
  function turn(hard) {
    if (!inked && !hard) return;
    pageEnds();
    piece.pageBreak = true;
    start(piece.sectProps);
    inked = false;
  }
  /* A table's ink is seen row by row as it is read, since its rows are added in slices */
  function add(el) {
    piece.elements.push(el);
    if (el.type === "paragraph" || el.type === "table" ? vwHasInk(el) : !VW_BLANK[el.type]) {
      inked = true;
      if (el.type !== "table") inkSeen();
    }
  }
  function rows(table, list) { return Object.assign({}, table, { children: list }); }

  sections.forEach(function (s, si) {
    var prev = si > 0 ? sections[si - 1].props : null;
    var runsOn = false;
    if (prev) {
      vwInheritRefs(prev, s.props, "headerRefs");
      vwInheritRefs(prev, s.props, "footerRefs");
      runsOn = (s.props.type === "continuous" || s.props.type === "nextColumn") &&
        !renderer.isPageBreakSection(prev, s.props);
      if (!runsOn) {
        pageEnds();
        piece.pageBreak = true;
        inked = false;
      }
    }
    start(s.props);

    var columned = vwColumns(s.props) > 1;
    var elements = columned || (runsOn && vwColumns(prev) > 1)
      ? vwColumnMarksOut(s.elements, columned) : s.elements;
    /* Columns fill side by side, so how far down a page a place sits says nothing in them */
    var placing = !columned;

    elements.forEach(function (el) {
      if (el.type === "table") {
        var all = el.children || [], heads = [], from = 0;
        while (heads.length < all.length && vwIsHeaderRow(all[heads.length])) heads.push(all[heads.length]);
        for (var r = 0; r < all.length; r++) {
          /* Whether rows of the body, or what came before the table, are above this row */
          var above = r > Math.max(from, heads.length) || (!from && inked);
          var turns = placing && !vwIsHeaderRow(all[r]) && vwRowStartsRunless(all[r]) &&
            place(all[r], above);
          if (r === from && (turns || vwHasPageMark(all[r]))) {
            turn();
          } else if (turns || vwHasPageMark(all[r])) {
            add(rows(el, (from >= heads.length && from ? heads : []).concat(all.slice(from, r))));
            turn();
            from = r;
          }
          if (vwHasInk(all[r])) inkSeen();
        }
        add(from ? rows(el, (from >= heads.length ? heads : []).concat(all.slice(from))) : el);
        return;
      }
      if (el.type !== "paragraph") { add(el); return; }
      if (placing && vwRunless(el) && place(el, inked)) turn();

      var style = renderer.findStyle(el.styleName);
      if (el.pageBreakBefore || (style && style.paragraphProps && style.paragraphProps.pageBreakBefore)) {
        turn();
      }

      var part = Object.assign({}, el, { children: [] });
      (el.children || []).forEach(function (run) {
        var rest = run;
        var at = run.type === "run" && run.children ? run.children.findIndex(isBreak) : -1;
        while (at >= 0) {
          if (at > 0) part.children.push(Object.assign({}, rest, { children: rest.children.slice(0, at) }));
          var hard = rest.children[at].break === "page";
          var before = vwHasInk(part);
          if (before || hard) {
            add(part);
            turn(hard);
            part = Object.assign({}, el, { children: [] });
            /* The rest of a paragraph a page cut in two: no second list number, no indent */
            if (before) part.className = [el.className, "vw-cont"].filter(Boolean).join(" ");
          } else {
            turn();
          }
          rest = Object.assign({}, rest, { children: rest.children.slice(at + 1) });
          at = rest.children.findIndex(isBreak);
        }
        if (rest === run || rest.children.length) part.children.push(rest);
      });
      add(part);
    });
  });

  pageEnds();

  /* An empty page left by a break at a section's end gives its break to the page before */
  var kept = [];
  pieces.forEach(function (p) {
    if (p.elements.length || !kept.length) kept.push(p);
    else if (p.pageBreak) kept[kept.length - 1].pageBreak = true;
  });
  return kept;
}

function isBreak(node) {
  return node.type === "break" && (node.break === "page" || node.break === "lastRenderedPageBreak");
}

/*
 * Word records no page that begins on a paragraph without a run, such as a blank line or an
 * empty first cell, so its own count of pages says how many went unrecorded. Resolves true
 * once vwPages.turnAt has places for them, for the document to be drawn again.
 */
function vwFindMissingPages() {
  if (vwPages.measure) return vwMeasurePages();
  var sheets = vwSheets(), missing = vwPages.wordPages - sheets.length;
  if (!vwPages.split || !(missing > 0)) return Promise.resolve(false);
  /* A layout first, so a font the document embeds is loading by the time fonts.ready is read */
  document.body.getBoundingClientRect();
  return document.fonts.ready.then(function () {
    vwLineUpTabs();
    vwPages.turnAt = vwPlaceTurns([].map.call(sheets, vwMeasureSheet), missing);
    return !!vwPages.turnAt;
  });
}

/* Over its paper by more than 2%: lines as Word sets them (docx-lines.js) run that close to Word's */
var VW_OVERFLOW = 1.02;

/*
 * Each missing page goes to the sheet that runs furthest over its paper, while one does and
 * has a place to turn for it.
 */
function vwPlaceTurns(plan, missing) {
  var given = 0, turnAt = null;
  while (given < missing) {
    var best = null, most = VW_OVERFLOW;
    plan.forEach(function (s) {
      var over = s.height / ((s.turns.length + 1) * s.room);
      if (s.room > 0 && !s.full && over > most) { best = s; most = over; }
    });
    if (!best) break;
    var turns = vwTurnsOn(best, best.turns.length + 2);
    if (turns) {
      best.turns = turns;
      given++;
    } else {
      best.full = true;
    }
  }
  plan.forEach(function (s) {
    s.turns.forEach(function (spot) { (turnAt = turnAt || {})[spot.n] = true; });
  });
  return turnAt;
}

/*
 * Where a sheet turns to make [pages] pages: at the place nearest each even share of it, one
 * to a stretch. Word turns at a blank line or a row only once the page above is full, so a
 * turn leaving less than half a page above it is not one Word made, and null says so.
 */
function vwTurnsOn(sheet, pages) {
  var turns = [], next = 0, above = 0;
  for (var j = 1; j < pages; j++) {
    var want = j * sheet.height / pages, pick = null, stretch = 0;
    for (var r = next; r < sheet.places.length; r++) {
      for (var q = 0; q < sheet.places[r].length; q++) {
        var spot = sheet.places[r][q];
        if (spot.at - above < sheet.room / 2) continue;
        if (!pick || Math.abs(spot.at - want) < Math.abs(pick.at - want)) {
          pick = spot;
          stretch = r;
        }
      }
    }
    if (!pick) return null;
    turns.push(pick);
    above = pick.at;
    next = stretch + 1;
  }
  return turns;
}

/*
 * How far down a sheet its body runs, the room its paper has for one, and the places a page
 * could begin on it, a list to each stretch, read before fitPageWidth zooms the sheets.
 */
function vwMeasureSheet(sheet) {
  var style = getComputedStyle(sheet);
  var padTop = parseFloat(style.paddingTop) || 0;
  var room = (parseFloat(style.minHeight) || 0) - padTop - (parseFloat(style.paddingBottom) || 0);
  var top = sheet.getBoundingClientRect().top + padTop, bottom = top;
  /* To the last ink, since Word lets a page's last space after fall below its margin */
  var blocks = sheet.querySelectorAll(":scope > article > *");
  for (var a = 0; a < blocks.length; a++) {
    bottom = Math.max(bottom, blocks[a].getBoundingClientRect().bottom);
  }
  var places = [], last = null;
  var marked = sheet.querySelectorAll(":scope > article [data-vw-place]");
  for (var m = 0; m < marked.length; m++) {
    var said = marked[m].getAttribute("data-vw-place").split(" ");
    var spot = { n: Number(said[0]), at: marked[m].getBoundingClientRect().top - top };
    if (said[1] === last) places[places.length - 1].push(spot);
    else places.push([spot]);
    last = said[1];
  }
  return { room: room, height: bottom - top, places: places, turns: [], full: false };
}

/*
 * Measured pages, issue #55: a file that records no page at all, as WPS, LibreOffice and
 * Google Docs write them, turns a page wherever its paper runs out on the first drawing.
 *
 * Names every paragraph, table row and piece of text in the body by its place, the same on
 * every parse of the same file, so a place measured on the first drawing can be found again
 * in the parse the second drawing is made from. Returns them, for vwAddTurns.
 */
function vwNumberModel(doc) {
  var body = doc.documentPart && doc.documentPart.body;
  var model = { paras: [], rows: [], texts: {} };
  /* [via] is the child of the paragraph a piece of text sits in, the part splitPieces can cut */
  function walk(node, para, via) {
    (node.children || []).forEach(function (c) {
      if (c.type === "paragraph") {
        c.vwP = model.paras.length;
        var p = { node: c, top: node === body, texts: 0 };
        model.paras.push(p);
        walk(c, p, null);
        return;
      }
      if (c.type === "row") {
        c.vwR = model.rows.length;
        model.rows.push(c);
      }
      var through = para && node === para.node ? c : via;
      if (c.type === "text" && para) {
        c.vwT = para.node.vwP + "." + para.texts++;
        model.texts[c.vwT] = { node: c, holder: node, via: through, para: para };
      }
      walk(c, c.type === "table" ? null : para, through);
    });
  }
  if (body) walk(body, null, null);
  return model;
}

function vwPageMark() {
  return { type: "break", break: "lastRenderedPageBreak" };
}

/*
 * Writes a page record where each measured page turns, as Word writes one: before a paragraph,
 * in a table row, which splitPieces then turns before, or in a run where a line begins.
 */
function vwAddTurns(model, turns) {
  var cuts = [];
  turns.forEach(function (t) {
    if (t.p !== undefined) {
      var p = model.paras[t.p];
      if (p && p.top) (p.node.children = p.node.children || []).unshift({ type: "run", children: [vwPageMark()] });
    } else if (t.r !== undefined) {
      var cell = model.rows[t.r] && (model.rows[t.r].children || [])[0];
      if (cell) (cell.children = cell.children || []).unshift(vwPageMark());
    } else if (t.t !== undefined) {
      var at = model.texts[t.t];
      if (!at || !at.para.top) return;
      if (at.holder.type === "run" && at.holder === at.via) {
        var cut = cuts.filter(function (c) { return c.at === at; })[0];
        if (!cut) cuts.push(cut = { at: at, offsets: [] });
        cut.offsets.push(t.o);
      } else {
        /* Deeper than a run of the paragraph's own, such as in a link: the page turns before
           the part of the paragraph that holds it, a line or so early */
        var kids = at.para.node.children, i = kids.indexOf(at.via);
        if (i >= 0) kids.splice(i, 0, { type: "run", children: [vwPageMark()] });
      }
    }
  });
  cuts.forEach(function (cut) {
    var text = cut.at.node, value = text.text || "", parts = [], from = 0;
    cut.offsets.sort(function (a, b) { return a - b; }).forEach(function (o) {
      if (o <= from && parts.length) return;
      if (o > from) parts.push(Object.assign({}, text, { text: value.slice(from, o) }));
      parts.push(vwPageMark());
      from = o;
    });
    if (from < value.length) parts.push(Object.assign({}, text, { text: value.slice(from) }));
    var run = cut.at.holder.children, i = run.indexOf(text);
    if (i >= 0) run.splice.apply(run, [i, 1].concat(parts));
  });
}

/*
 * A second drawing costs about as much again as the first, which grows with how much the file
 * draws. With the CPU slowed four times, as a stand-in for a phone, a file of 6,247 elements (261
 * pages) took 1.6 s to open where one long sheet took 1.0 s, and one of 60,640 elements (498
 * pages) 10.5 s where it took 6.0 s. Past this many a file keeps its long sheets, as before
 * measuring, so the wait pages add stays near two seconds.
 */
var VW_MOST_ELEMENTS = 20000;

/* Resolves true once vwPages.turns says where the pages turn, for the document to be drawn again */
function vwMeasurePages() {
  if (document.querySelectorAll("#container *").length > VW_MOST_ELEMENTS) return Promise.resolve(false);
  /* A layout first, so a font the document embeds is loading by the time fonts.ready is read */
  document.body.getBoundingClientRect();
  return document.fonts.ready.then(function () {
    vwLineUpTabs();
    var turns = [], sheets = vwSheets();
    for (var i = 0; i < sheets.length; i++) vwSheetTurns(sheets[i], turns, i);
    vwPages.turns = turns.length ? turns : null;
    return !!vwPages.turns;
  });
}

/* A rounding's worth: a line that reaches past its paper by less still fits */
var VW_SLACK = 0.5;

/* How much of the body's room a header or footer takes: none while it fits in its margin */
function vwOverMargin(el) {
  if (!el) return 0;
  var style = getComputedStyle(el);
  return Math.max(0, el.getBoundingClientRect().height +
    (parseFloat(style.marginTop) || 0) + (parseFloat(style.marginBottom) || 0));
}

/* Where a block starts, its space before included, which comes with it to a new page */
function vwBlockTop(el) {
  return el.getBoundingClientRect().top - (parseFloat(getComputedStyle(el).marginTop) || 0);
}

/*
 * The turns one sheet needs: down its blocks, a page turns at the first that runs past the
 * paper, before it or, for a paragraph, at a line of it and, for a table, at a row. The sheet
 * is the [index]th of the first drawing, and [turns] holds those of the sheets before it. Read
 * before fitPageWidth zooms the sheets.
 */
function vwSheetTurns(sheet, turns, index) {
  var articles = sheet.querySelectorAll(":scope > article");
  if (!articles.length) return;
  for (var a = 0; a < articles.length; a++) {
    /* Columns fill side by side, so how far down a page a line sits says nothing in them */
    if ((parseInt(getComputedStyle(articles[a]).columnCount, 10) || 1) > 1) return;
  }
  var style = getComputedStyle(sheet);
  /* The sheet's own margins, header and footer are its first page's; a later page's come from vwPages.pageRoom */
  var room = (parseFloat(style.minHeight) || 0) - (parseFloat(style.paddingTop) || 0) -
    (parseFloat(style.paddingBottom) || 0) - vwOverMargin(sheet.querySelector(":scope > header")) -
    vwOverMargin(sheet.querySelector(":scope > footer"));
  if (!(room > 0)) return;
  var notes = vwNotesOn(sheet);

  var blocks = vwBlocks(sheet);
  /* Blank lines that end the sheet start no page: the break or the end after them does (vwHideTails) */
  var last = vwInked(blocks);

  var page = { top: articles[0].getBoundingClientRect().top, room: room, notes: notes };
  for (var b = 0; b < last; b++) {
    var memo = {};
    for (var guard = 0; guard < 1000; guard++) {
      if (vwFits(page, blocks[b].getBoundingClientRect().bottom)) break;
      var turn = blocks[b].tagName === "TABLE" ? vwTableTurn(blocks, b, page, memo)
        : blocks[b].hasAttribute("data-vw-p") ? vwParagraphTurn(blocks, b, page, memo) : null;
      /* A turn that would not move the page on, as for a blank line taller than the paper */
      if (!turn || turn.y <= page.top + VW_SLACK) break;
      turns.push(turn.at);
      var props = blocks[b].parentNode.vwProps || vwPages.props[index];
      if (props && vwPages.pageRoom) room = vwPages.pageRoom(props, index + turns.length);
      if (!(room > 0)) return;
      page = { top: turn.y, room: room, notes: notes };
    }
  }
}

/* Whether a page that ends at [bottom] fits its paper, with the footnotes of what is on it */
function vwFits(page, bottom) {
  return bottom - page.top + vwNotesHeight(page.notes, page.top, bottom) <= page.room + VW_SLACK;
}

/*
 * The footnotes of a sheet, which docx-preview lists below its body: where each one's reference
 * sits and the height the note takes in the list, and what the list adds around its notes. A
 * page cut from the sheet holds the notes of its own references (vwNotesHeight). Null for a
 * sheet without any, or whose references and notes do not pair up.
 */
function vwNotesOn(sheet) {
  var list = sheet.querySelector(":scope > ol[data-vw-notes]");
  var refs = sheet.querySelectorAll(":scope > article sup[data-vw-note]");
  if (!list || !refs.length || list.children.length !== refs.length) return null;
  var items = list.children, box = list.getBoundingClientRect(), style = getComputedStyle(list);
  var tops = [].map.call(items, function (li) { return li.getBoundingClientRect().top; });
  var end = box.bottom - (parseFloat(style.paddingBottom) || 0) - (parseFloat(style.borderBottomWidth) || 0);
  var at = [], height = [];
  for (var i = 0; i < items.length; i++) {
    at.push(refs[i].getBoundingClientRect().top);
    height.push((i + 1 < items.length ? tops[i + 1] : end) - tops[i]);
  }
  var whole = box.height + (parseFloat(style.marginTop) || 0) + (parseFloat(style.marginBottom) || 0);
  return { at: at, height: height, frame: whole - (end - tops[0]) };
}

/* The height of the list of the footnotes whose references sit between [top] and [bottom] */
function vwNotesHeight(notes, top, bottom) {
  if (!notes) return 0;
  var sum = 0, any = false;
  for (var i = 0; i < notes.at.length; i++) {
    if (notes.at[i] >= top - VW_SLACK && notes.at[i] < bottom) {
      sum += notes.height[i];
      any = true;
    }
  }
  return any ? sum + notes.frame : 0;
}

/* The blocks of a page's body, from the top, across the parts its sections make of it */
function vwBlocks(sheet) {
  var articles = sheet.querySelectorAll(":scope > article"), blocks = [];
  for (var a = 0; a < articles.length; a++) blocks.push.apply(blocks, articles[a].children);
  return blocks;
}

/* How many of a page's [blocks] come before the blank paragraphs that end it */
function vwInked(blocks) {
  var last = blocks.length;
  while (last > 0 && vwBlank(blocks[last - 1])) last--;
  return last;
}

/* What an empty paragraph can hold and still draw nothing: empty runs, links, bookmarks and breaks */
var VW_EMPTY = { SPAN: 1, A: 1, BR: 1, WBR: 1 };

/*
 * A paragraph with nothing drawn on it, which a page can lose at its foot. It holds no text at
 * all, since spaces or a tab can carry an underline or a leader, as a line to sign on does,
 * and nothing besides empty runs. It has no list number, border or ground.
 */
function vwBlank(el) {
  if (el.tagName !== "P" || el.textContent) return false;
  var inside = el.getElementsByTagName("*");
  for (var i = 0; i < inside.length; i++) {
    if (!VW_EMPTY[inside[i].tagName]) return false;
  }
  /* A list's number is drawn before the paragraph, or as its marker where the level has no text */
  var number = getComputedStyle(el, "::before").content;
  if (number && number !== "none" && number !== "normal" && number !== '""') return false;
  var style = getComputedStyle(el);
  if (style.display === "list-item" && (style.listStyleType !== "none" || style.listStyleImage !== "none")) return false;
  return !(parseFloat(style.borderTopWidth) || parseFloat(style.borderBottomWidth) ||
    parseFloat(style.borderLeftWidth) || parseFloat(style.borderRightWidth)) &&
    /^(transparent|rgba\(0, 0, 0, 0\))$/.test(style.backgroundColor);
}

/*
 * Blank lines at the foot of a page that runs past its paper take no room there, and the
 * block above them no space after, as a page's last block has none (docx.html). A page
 * measured without them (see vwSheetTurns) would otherwise run past its paper, and a blank
 * line ending a full page before a page break became a page of its own. A page that fits
 * keeps them, where they show nothing anyway. Every page is read before any is changed, so
 * the pages are laid out once.
 */
function vwHideTails(sheets) {
  var ends = [];
  for (var i = 0; i < sheets.length; i++) {
    var blocks = vwBlocks(sheets[i]), last = vwInked(blocks);
    if (last === blocks.length) continue;
    var style = getComputedStyle(sheets[i]);
    if (sheets[i].getBoundingClientRect().height > (parseFloat(style.minHeight) || 0) + VW_SLACK) {
      ends.push({ blocks: blocks, last: last });
    }
  }
  ends.forEach(function (end) {
    for (var b = end.last; b < end.blocks.length; b++) end.blocks[b].classList.add("vw-tail");
    if (end.last > 0) end.blocks[end.last - 1].classList.add("vw-page-end");
  });
}

/*
 * A turn before block [b], or before the paragraphs kept with it, as Word keeps a heading on
 * the page its text starts. Word gives up on keeping once that would leave the page empty.
 */
function vwTurnBefore(blocks, b, page) {
  var from = b;
  while (from > 0) {
    var prev = blocks[from - 1];
    if ((prev.getAttribute("data-vw-keep") || "").indexOf("n") < 0) break;
    if (vwBlockTop(prev) <= page.top + VW_SLACK) { from = b; break; }
    from--;
  }
  var el = blocks[from];
  if (vwBlockTop(el) <= page.top + VW_SLACK) return null;
  var row = el.tagName === "TABLE" ? vwRows(el)[0] : null;
  if (el.hasAttribute("data-vw-p")) return { at: { p: Number(el.getAttribute("data-vw-p")) }, y: vwBlockTop(el) };
  if (row && row.hasAttribute("data-vw-r")) return { at: { r: Number(row.getAttribute("data-vw-r")) }, y: vwBlockTop(el) };
  return null;
}

function vwRows(table) {
  return table.querySelectorAll(":scope > tr, :scope > tbody > tr, :scope > thead > tr");
}

/*
 * A table turns before its first row that runs past the paper, and its header rows are drawn
 * again above that row on the new page. If not even its first row of the body fits, the
 * whole table goes, rather than leave its header rows alone at the foot of the page. A row
 * taller than the paper keeps a page to itself, and the table turns again after it. [memo]
 * keeps what was read of the table for its next turn, so a long one is read once.
 */
function vwTableTurn(blocks, b, page, memo) {
  if (!memo.rows) {
    var rows = vwRows(blocks[b]), heads = 0, headHeight = 0;
    while (heads < rows.length && rows[heads].hasAttribute("data-vw-head")) {
      headHeight += rows[heads].getBoundingClientRect().height;
      heads++;
    }
    memo.rows = rows;
    memo.boxes = [].map.call(rows, function (row) { return row.getBoundingClientRect(); });
    memo.heads = heads;
    memo.lift = heads ? headHeight : 0;
    memo.from = 0;
  }
  for (var r = memo.from; r < memo.rows.length; r++) {
    var box = memo.boxes[r];
    if (box.top < page.top - VW_SLACK || vwFits(page, box.bottom)) continue;
    var at = r;
    if (r <= memo.heads) {
      if (vwBlockTop(blocks[b]) > page.top + VW_SLACK) return vwTurnBefore(blocks, b, page);
      at = memo.heads + 1;
    } else if (box.top - memo.lift <= page.top + VW_SLACK) {
      /* Already at the head of the page and still too tall: the next row starts the next one */
      at = r + 1;
    }
    /* Header rows that fill a page by themselves would be drawn again on every page with one
       row under them, so the table goes on as one page */
    if (memo.lift >= page.room) return null;
    if (at >= memo.rows.length || !memo.rows[at].hasAttribute("data-vw-r")) return null;
    memo.from = at;
    return { at: { r: Number(memo.rows[at].getAttribute("data-vw-r")) }, y: memo.boxes[at].top - memo.lift };
  }
  return null;
}

/*
 * A paragraph turns at the first of its lines that runs past the paper, keeping two lines at
 * the foot of the page and two at the head of the next, as Word's widow and orphan control
 * does, or before it when fewer would stay or it is to be kept together. [memo] keeps its
 * lines for its next turn, so a long paragraph is read once, not once a page.
 */
function vwParagraphTurn(blocks, b, page, memo) {
  var el = blocks[b], lines = memo.lines || (memo.lines = vwLineBoxes(el));
  /* A blank line has nothing on it to measure, so it goes to the next page whole */
  if (!lines.length) return vwBlockTop(el) <= page.top + VW_SLACK ? null : vwTurnBefore(blocks, b, page);
  var first = memo.first || 0;
  while (first < lines.length && lines[first].top < page.top - VW_SLACK) first++;
  memo.first = first;
  var cut = first;
  while (cut < lines.length && vwFits(page, lines[cut].bottom)) cut++;
  if (cut >= lines.length) return null;
  var atTop = (first ? lines[first].top : vwBlockTop(el)) <= page.top + VW_SLACK;
  if ((el.getAttribute("data-vw-keep") || "").indexOf("l") >= 0) {
    cut = first;
  } else {
    if (lines.length - cut === 1 && cut - first >= 2) cut--;
    if (cut - first < 2) cut = first;
  }
  if (cut === first) {
    if (!first && !atTop) return vwTurnBefore(blocks, b, page);
    /* Already at the head of the page, so as many lines as fit stay, at least one */
    cut = first + 1;
    while (cut < lines.length && vwFits(page, lines[cut].bottom)) cut++;
  }
  for (var k = cut; k < lines.length; k++) {
    if (!lines[k].texts.length) continue;
    var piece = lines[k].texts[0];
    return { at: { t: piece.node.vwT, o: vwLineStart(piece) }, y: lines[k].top };
  }
  return null;
}

/*
 * A paragraph's lines in reading order, each with the pieces of named text drawn on it, and
 * where each line's part of the paragraph starts and ends. A piece starts a new line once it
 * sits more than half its height below the line's ink, which holds where tight spacing makes
 * neighbouring lines' text overlap.
 *
 * Text is drawn half its line's spacing inside the line, so a line reaches halfway to the
 * next, and the first and last to the paragraph's edges. Measured by its text alone, a
 * double-spaced page took one line more than its paper held.
 */
function vwLineBoxes(p) {
  var lines = [], line = null, range = document.createRange();
  function add(r, piece) {
    if (!(r.height > 0)) return;
    if (!line || r.top >= line.inkTop + Math.min(line.inkBottom - line.inkTop, r.height) / 2) {
      line = { inkTop: r.top, inkBottom: r.bottom, texts: [] };
      lines.push(line);
    } else {
      line.inkTop = Math.min(line.inkTop, r.top);
      line.inkBottom = Math.max(line.inkBottom, r.bottom);
    }
    if (piece) line.texts.push(piece);
  }
  var walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null);
  for (var node = walker.nextNode(); node; node = walker.nextNode()) {
    if (node.nodeType === 3) {
      if (!node.nodeValue) continue;
      range.selectNodeContents(node);
      var rects = range.getClientRects();
      for (var i = 0; i < rects.length; i++) {
        add(rects[i], node.vwT !== undefined ? { node: node, rect: rects[i] } : null);
      }
    } else if (/^(img|svg|canvas|br)$/i.test(node.tagName)) {
      add(node.getBoundingClientRect(), null);
    }
  }
  if (lines.length) {
    /* The rest of a paragraph a page cut in two keeps its padding and border, but no space before */
    var box = p.getBoundingClientRect(), style = getComputedStyle(p);
    var above = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.borderTopWidth) || 0);
    var below = (parseFloat(style.paddingBottom) || 0) + (parseFloat(style.borderBottomWidth) || 0);
    for (var k = 0; k < lines.length; k++) {
      lines[k].top = k ? (lines[k - 1].inkBottom + lines[k].inkTop) / 2 - above : box.top;
      lines[k].bottom = k + 1 < lines.length ? (lines[k].inkBottom + lines[k + 1].inkTop) / 2 + below : box.bottom;
    }
  }
  return lines;
}

/* Where in its text a piece of a line begins: the first character drawn below the middle of the line before */
function vwLineStart(piece) {
  var node = piece.node, text = node.nodeValue, range = document.createRange();
  var mid = piece.rect.top + piece.rect.height / 2;
  function onOrAfter(o) {
    for (var k = o; k < text.length; k++) {
      range.setStart(node, k);
      range.setEnd(node, k + 1);
      var r = range.getBoundingClientRect();
      if (r.height > 0) return r.bottom > mid;
    }
    return true;
  }
  var lo = 0, hi = text.length;
  while (lo < hi) {
    var m = (lo + hi) >> 1;
    if (onOrAfter(m)) hi = m; else lo = m + 1;
  }
  /* Never between the halves of a character outside the BMP */
  var code = text.charCodeAt(lo);
  return code >= 0xDC00 && code <= 0xDFFF && lo > 0 ? lo - 1 : lo;
}

/*
 * Writes each page's number into the fields marked while it was drawn, counting as Word
 * does: on from the page before, unless a section restarts it, in the section's format
 * unless the field asks for its own.
 */
function vwNumberPages(sheets) {
  if (!(vwPages.split || vwPages.measure) || sheets.length !== vwPages.props.length) return;
  var numbers = [], sectionOf = [], sectionPages = [];
  for (var i = 0; i < sheets.length; i++) {
    var props = vwPages.props[i];
    var first = i === 0 || props !== vwPages.props[i - 1];
    var start = props.pageNumber && props.pageNumber.start;
    numbers.push(first && isFinite(start) && start !== null ? start : (i ? numbers[i - 1] + 1 : 1));
    if (first) sectionPages.push(0);
    sectionOf.push(sectionPages.length - 1);
    sectionPages[sectionPages.length - 1]++;
  }

  for (var p = 0; p < sheets.length; p++) {
    var own = vwPages.props[p].pageNumber;
    var seen = {};
    var marked = sheets[p].querySelectorAll("[data-vw-field]");
    for (var m = 0; m < marked.length; m++) {
      var said = marked[m].getAttribute("data-vw-field").split(" ");
      var text = "";
      if (!seen[said[0]]) {
        seen[said[0]] = true;
        var value = said[1] === "PAGE" ? numbers[p]
          : said[1] === "NUMPAGES" ? sheets.length : sectionPages[sectionOf[p]];
        var format = said[2] !== "-" ? said[2] : said[1] === "PAGE" && own ? own.format : "";
        text = vwFormatPageNumber(value, format);
      }
      writeText(marked[m], text);
    }
  }
}

/* The first text in [el] says [text] and the rest says nothing, so the run keeps its look */
function writeText(el, text) {
  var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  var node, done = false;
  while ((node = walker.nextNode())) {
    node.nodeValue = done ? "" : text;
    done = true;
  }
}

function vwFormatPageNumber(n, format) {
  switch (format) {
    case "lowerRoman": return roman(n).toLowerCase();
    case "upperRoman": return roman(n);
    case "lowerLetter": return letters(n).toLowerCase();
    case "upperLetter": return letters(n);
    case "numberInDash": return "- " + n + " -";
    default: return String(n);
  }
}

function roman(n) {
  if (n < 1 || n > 3999) return String(n);
  var out = "", marks = [[1000, "M"], [900, "CM"], [500, "D"], [400, "CD"], [100, "C"],
    [90, "XC"], [50, "L"], [40, "XL"], [10, "X"], [9, "IX"], [5, "V"], [4, "IV"], [1, "I"]];
  marks.forEach(function (m) {
    while (n >= m[0]) { out += m[1]; n -= m[0]; }
  });
  return out;
}

/* A to Z, then AA to ZZ, as Word counts in letters */
function letters(n) {
  if (n < 1) return String(n);
  var c = String.fromCharCode(65 + (n - 1) % 26);
  return new Array(Math.floor((n - 1) / 26) + 2).join(c);
}

/*
 * The page under the middle of the screen, told to the app for its page counter and Go to
 * page, as pdf.html does. A zoomed page's rect leaves the zoom out below about Chromium
 * 128 (see fitPageWidth in docx.js), so rects are read through vwPages.rectScale.
 */
var vwPageSent = 0;
var vwPageAskedFor = null;
var vwPageFrame = 0;

function vwSheets() {
  return document.querySelectorAll(".docx-wrapper > section.docx");
}

function vwSheetTop(sheet) {
  return sheet.getBoundingClientRect().top / (vwPages.rectScale || 1);
}

function vwPageOnScreen(sheets, vv) {
  if (vv.pageTop <= 1) return 1;
  if (vv.pageTop + vv.height >= document.documentElement.scrollHeight - 1) return sheets.length;
  var middle = vv.pageTop + vv.height / 2;
  var lo = 0, hi = sheets.length - 1, at = 0;
  while (lo <= hi) {
    var mid = (lo + hi) >> 1;
    if (vwSheetTop(sheets[mid]) + window.scrollY <= middle) { at = mid; lo = mid + 1; } else { hi = mid - 1; }
  }
  return at + 1;
}

function vwReportPage() {
  var sheets = vwSheets();
  var vv = window.visualViewport;
  if (!vwPages.settled || sheets.length < 2 || !vv || !vv.height) return;
  /* A page Go to page put on screen is named until the reader moves */
  if (vwPageAskedFor && Math.abs(vv.pageTop - vwPageAskedFor.top) > 1) vwPageAskedFor = null;
  var n = vwPageAskedFor ? vwPageAskedFor.n : vwPageOnScreen(sheets, vv);
  if (n !== vwPageSent && vwFind.post("page " + n + " " + sheets.length)) vwPageSent = n;
}

function vwSchedulePageReport() {
  if (vwPageFrame) return;
  vwPageFrame = requestAnimationFrame(function () { vwPageFrame = 0; vwReportPage(); });
}

/* At the top of the screen, or in the middle for a page too short for the middle to fall on */
function vwGoToPage(n) {
  var sheets = vwSheets();
  if (!(n >= 1 && n <= sheets.length)) return;
  var sheet = sheets[n - 1];
  var vv = window.visualViewport;
  var tall = sheet.getBoundingClientRect().height / (vwPages.rectScale || 1);
  sheet.scrollIntoView({ block: vv && tall <= vv.height / 2 ? "center" : "start" });
  vwPageAskedFor = vv ? { n: n, top: vv.pageTop } : null;
  vwReportPage();
}

function vwFollowPages() {
  if (vwSheets().length < 2) return;
  window.addEventListener("scroll", vwSchedulePageReport, { passive: true });
  if (window.visualViewport) {
    window.visualViewport.addEventListener("scroll", vwSchedulePageReport, { passive: true });
    window.visualViewport.addEventListener("resize", vwSchedulePageReport, { passive: true });
  }
  vwReportPage();
}
