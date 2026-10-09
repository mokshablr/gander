/*
 * Two things PPTXjs takes for granted that the format does not promise, either of which
 * puts the error card up in place of every slide. Both are put right here, in the package
 * as PPTXjs opens it and before it reads a part, so the library keeps to upstream's code
 * but for its one change (docs/VENDORED.md). Upstream has not moved since 2022 and has
 * both open, as issues 42 and 32.
 *
 * docProps/app.xml is optional, and Google Slides leaves it out. PPTXjs reads it without
 * looking: "Cannot read properties of null (reading 'Properties')". A deck without one is
 * given an empty one. PPTXjs wants only the version of PowerPoint inside, to tell Office
 * 2007's files apart, and an empty one says not 2007.
 *
 * A custom shape can be drawn in several paths, and Google Slides and WPS both use that: a
 * logic gate is its outline and a path for each wire. PPTXjs reads only one ("Cannot read
 * properties of undefined (reading 'w')"), so the paths of a shape become subpaths of its
 * first, a path drawn on a grid of its own moved onto the first's. Each still gets the
 * shape's fill and line, the only ones PPTXjs gives any path. One thing does change: where
 * two of them overlap, wound in opposite directions, the overlap is no longer filled.
 *
 * PPTXjs also draws a path's straight segments only when it has more than one. A lone one
 * it reads field by field as though they were the list, and nothing is drawn: no error, and
 * no line. A rule under a heading is such a path, so a deck can lose one from every slide.
 * Its segment is written twice, which draws the same line.
 */
var DRAWINGML = "http://schemas.openxmlformats.org/drawingml/2006/main";
var PRESENTATIONML = "http://schemas.openxmlformats.org/presentationml/2006/main";

var vwOpenPackage = JSZip.prototype.load;
JSZip.prototype.load = function () {
  var zip = vwOpenPackage.apply(this, arguments);
  if (!zip.file("docProps/app.xml")) zip.file("docProps/app.xml", "<Properties/>");
  var presentation = zip.file("ppt/presentation.xml");
  var sized = presentation && sizeWhereNoneIsGiven(presentation.asText());
  if (sized && sized !== presentation.asText()) zip.file("ppt/presentation.xml", sized);
  zip.file(/^ppt\/(slides|slideLayouts|slideMasters)\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var drawable = bulletsAPhoneDraws(breaksPptxjsKeeps(pathsPptxjsDraws(xml)));
    if (drawable !== xml) zip.file(part.name, drawable);
  });
  // SmartArt is drawn from parts of its own, which carry bullets of their own
  zip.file(/^ppt\/diagrams\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var drawable = bulletsAPhoneDraws(xml);
    if (drawable !== xml) zip.file(part.name, drawable);
  });
  var designs = {};
  zip.file(/^ppt\/slides\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var styled = styleTheDesignGives(zip, part.name, xml, designs);
    if (styled !== xml) zip.file(part.name, styled);
  });
  zip.file(/^ppt\/(slides|slideLayouts|slideMasters)\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var marked = placeTheDesignGives(zip, part.name, linesLeftWhole(zip, part.name, xml, designs), designs);
    if (marked !== xml) zip.file(part.name, marked);
  });
  return zip;
};

/*
 * PowerPoint draws text whose size nothing in the deck gives at 18 point. PPTXjs leaves it the
 * size of its paragraph, 0 in a placeholder or shape, where it went undrawn. The deck's
 * defaults, where PPTXjs looks last, are given 18 point at each level that names no size.
 */
function sizeWhereNoneIsGiven(xml) {
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var root = doc.documentElement;
  var defaults = childrenNamed(root, "defaultTextStyle")[0];
  if (!defaults) defaults = root.appendChild(doc.createElementNS(PRESENTATIONML, "p:defaultTextStyle"));
  var changed = false;
  for (var lvl = 1; lvl <= 9; lvl++) {
    var level = childrenNamed(defaults, "lvl" + lvl + "pPr")[0];
    if (!level) level = defaults.appendChild(doc.createElementNS(DRAWINGML, "a:lvl" + lvl + "pPr"));
    var props = childrenNamed(level, "defRPr")[0];
    if (!props) props = level.appendChild(doc.createElementNS(DRAWINGML, "a:defRPr"));
    if (!props.hasAttribute("sz")) {
      props.setAttribute("sz", "1800");
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

function pathsPptxjsDraws(xml) {
  // A path closed, or written empty, with another straight after it, or a lone segment
  if (!/(<\/a:path>|\/>)\s*<a:path\b/.test(xml) && !hasLoneSegment(xml)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var lists = doc.getElementsByTagNameNS(DRAWINGML, "pathLst");
  var changed = false;
  for (var i = 0; i < lists.length; i++) {
    var paths = childrenNamed(lists[i], "path");
    for (var j = 1; j < paths.length; j++) {
      onToGridOf(paths[0], paths[j]);
      while (paths[j].firstChild) paths[0].appendChild(paths[j].firstChild);
      lists[i].removeChild(paths[j]);
      changed = true;
    }
    var segments = paths.length ? childrenNamed(paths[0], "lnTo") : [];
    if (segments.length === 1) {
      paths[0].insertBefore(segments[0].cloneNode(true), segments[0].nextSibling);
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

function hasLoneSegment(xml) {
  var paths = xml.match(/<a:path\b[^>]*>[\s\S]*?<\/a:path>/g) || [];
  for (var i = 0; i < paths.length; i++) {
    if ((paths[i].match(/<a:lnTo\b/g) || []).length === 1) return true;
  }
  return false;
}

function childrenNamed(parent, name) {
  var found = [];
  for (var el = parent.firstElementChild; el; el = el.nextElementSibling) {
    if (el.localName === name) found.push(el);
  }
  return found;
}

/* A path's w and h are the size of the grid its points are on. */
function onToGridOf(first, path) {
  var sx = gridRatio(first, path, "w");
  var sy = gridRatio(first, path, "h");
  if (sx === 1 && sy === 1) return;
  var points = path.getElementsByTagNameNS(DRAWINGML, "pt");
  for (var i = 0; i < points.length; i++) {
    scaleAttr(points[i], "x", sx);
    scaleAttr(points[i], "y", sy);
  }
  var arcs = path.getElementsByTagNameNS(DRAWINGML, "arcTo");
  for (var k = 0; k < arcs.length; k++) {
    scaleAttr(arcs[k], "wR", sx);
    scaleAttr(arcs[k], "hR", sy);
  }
}

function gridRatio(first, path, side) {
  var to = parseFloat(first.getAttribute(side));
  var from = parseFloat(path.getAttribute(side));
  return to > 0 && from > 0 ? to / from : 1;
}

/* A point can also name a guide rather than give a number, and is left as it is. */
function scaleAttr(el, name, by) {
  var v = el.getAttribute(name);
  if (v !== null && /^-?\d+$/.test(v)) el.setAttribute(name, String(Math.round(Number(v) * by)));
}

/*
 * A paragraph with more than one line break in its text loses the first of them: PPTXjs
 * shifts it off the list before it draws the rest (genTextBody). So a title set on three
 * lines drew its first two as one, and two breaks in a row, which leave a blank line,
 * drew as one break. Across 86 decks, 22 paragraphs in 9 decks have more than one. Each
 * is given another in front of its first, for PPTXjs to drop instead.
 */
function breaksPptxjsKeeps(xml) {
  if ((xml.match(/<a:br\b/g) || []).length < 2) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var paragraphs = doc.getElementsByTagNameNS(DRAWINGML, "p");
  var changed = false;
  for (var i = 0; i < paragraphs.length; i++) {
    var breaks = childrenNamed(paragraphs[i], "br");
    if (breaks.length > 1 && childrenNamed(paragraphs[i], "r").length) {
      paragraphs[i].insertBefore(breaks[0].cloneNode(true), breaks[0]);
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

/*
 * A bullet set in Wingdings, Symbol or their kin, which no phone has, drew as an empty box or
 * as the letter it is stored as, such as an "l" for a round one: PPTXjs maps only a few, and
 * looks for the font only in the paragraph, where a design's master usually sets it. Each is
 * given the character it stands for, one a phone can draw (symbol-fonts.js), and loses the
 * font, which would send PPTXjs to its own table again.
 */
var SYMBOL_BULLETS = /<a:buFont\b[^>]*\stypeface="(wingdings|webdings|symbol)\b|<a:buChar\b[^>]*\schar="(&#|[\uE000-\uF8FF])/i;

function bulletsAPhoneDraws(xml) {
  if (!SYMBOL_BULLETS.test(xml)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var changed = false;
  var chars = doc.getElementsByTagNameNS(DRAWINGML, "buChar");
  for (var i = 0; i < chars.length; i++) {
    var font = childrenNamed(chars[i].parentNode, "buFont")[0];
    var was = chars[i].getAttribute("char");
    var now = vwSymbolBullet(font && font.getAttribute("typeface"), was);
    if (now !== was) {
      chars[i].setAttribute("char", now);
      changed = true;
    }
  }
  var fonts = [].slice.call(doc.getElementsByTagNameNS(DRAWINGML, "buFont"));
  for (var j = 0; j < fonts.length; j++) {
    if (vwIsSymbolFont(fonts[j].getAttribute("typeface"))) {
      fonts[j].parentNode.removeChild(fonts[j]);
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

/*
 * PPTXjs looks a Wingdings 2 or 3 bullet up in a table of its own, upstream's dingbat.js, and
 * throws without one. Only a part DOMParser cannot read still sends a bullet there, so the
 * table is made from symbol-fonts.js, under the name and in the form PPTXjs reads, and such a
 * bullet gets a character a phone has like any other.
 */
var dingbat_unicode = [];
[["Wingdings 2", VW_WINGDINGS_2], ["Wingdings 3", VW_WINGDINGS_3]].forEach(function (font) {
  for (var i = 0; i < font[1].length; i++) {
    dingbat_unicode.push({ f: font[0], code: 0x20 + i, unicode: font[1].charCodeAt(i) });
  }
});

/*
 * PPTXjs makes a run bold or italic only when the run itself says so (getFontBold,
 * getFontItalic). A deck's design usually says it instead: the layout's title is bold, or
 * the master's title style is, and the run says nothing. Across 86 decks that left 125 runs
 * regular that PowerPoint draws bold, 110 of them titles, on 108 slides. A run that says
 * nothing is given what it inherits, where PowerPoint looks for it: its shape's own list
 * style, the layout's placeholder, the master's, then the master's style for titles, body
 * text or the rest, the first at the paragraph's level that says either way. A slide whose
 * design nowhere sets either is left as it is.
 */
var SETS_BOLD_OR_ITALIC = /<a:defRPr\b[^>]*\s[bi]="(1|true)"/;

function styleTheDesignGives(zip, name, xml, designs) {
  var layout = designPart(zip, relatedPart(zip, name, "slideLayout"), designs);
  var master = layout && designPart(zip, relatedPart(zip, layout.name, "slideMaster"), designs);
  if (!SETS_BOLD_OR_ITALIC.test(xml) && !(layout && layout.sets) && !(master && master.sets)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var changed = false;
  var shapes = doc.getElementsByTagNameNS(PRESENTATIONML, "sp");
  for (var i = 0; i < shapes.length; i++) {
    var body = childrenNamed(shapes[i], "txBody")[0];
    if (!body) continue;
    var lists = [childrenNamed(body, "lstStyle")[0]];
    var behind = placeholdersBehind(shapes[i], layout, master);
    if (behind) {
      lists.push(inTextBody(behind.layout, "lstStyle"), inTextBody(behind.master, "lstStyle"),
        master && masterStyle(master.doc, behind.type));
    }
    var paragraphs = childrenNamed(body, "p");
    for (var j = 0; j < paragraphs.length; j++) {
      var pPr = childrenNamed(paragraphs[j], "pPr")[0];
      var level = "lvl" + ((parseInt(pPr && pPr.getAttribute("lvl"), 10) || 0) + 1) + "pPr";
      var runs = childrenNamed(paragraphs[j], "r").concat(childrenNamed(paragraphs[j], "fld"));
      for (var k = 0; k < runs.length; k++) {
        if (inherit(doc, runs[k], lists, level, "b")) changed = true;
        if (inherit(doc, runs[k], lists, level, "i")) changed = true;
      }
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

/* Gives a run that does not say bold, or italic, the value its design says, if that is on. */
function inherit(doc, run, lists, level, attr) {
  var rPr = childrenNamed(run, "rPr")[0];
  if (rPr && rPr.hasAttribute(attr)) return false;
  var said = null;
  for (var i = 0; i < lists.length && said === null; i++) {
    var lvl = lists[i] && childrenNamed(lists[i], level)[0];
    var defaults = lvl && childrenNamed(lvl, "defRPr")[0];
    if (defaults && defaults.hasAttribute(attr)) said = defaults.getAttribute(attr);
  }
  if (said !== "1" && said !== "true") return false;
  if (!rPr) {
    rPr = doc.createElementNS(DRAWINGML, (run.prefix ? run.prefix + ":" : "") + "rPr");
    run.insertBefore(rPr, run.firstChild);
  }
  rPr.setAttribute(attr, "1");
  return true;
}

/* A layout or a master, read once a deck, and whether it sets bold or italic, or no wrap, anywhere. */
function designPart(zip, name, designs) {
  if (!name) return null;
  if (!(name in designs)) {
    var file = zip.file(name);
    var xml = file && file.asText();
    var doc = xml && new DOMParser().parseFromString(xml, "application/xml");
    designs[name] = doc && !doc.getElementsByTagName("parsererror").length
      ? { name: name, doc: doc, sets: SETS_BOLD_OR_ITALIC.test(xml), unwraps: SETS_NO_WRAP.test(xml) }
      : null;
  }
  return designs[name];
}

/* The part a relationship of the given type points to, from the part's own .rels. */
function relatedPart(zip, name, type) {
  var slash = name.lastIndexOf("/");
  var rels = zip.file(name.slice(0, slash) + "/_rels/" + name.slice(slash + 1) + ".rels");
  var rel = rels && new RegExp('<Relationship\\b[^>]*Type="[^"]*/' + type + '"[^>]*>').exec(rels.asText());
  var target = rel && /Target="([^"]+)"/.exec(rel[0]);
  if (!target) return null;
  var path = target[1].charAt(0) === "/" ? [] : name.slice(0, slash).split("/");
  target[1].split("/").forEach(function (step) {
    if (step === "..") path.pop();
    else if (step && step !== ".") path.push(step);
  });
  return path.join("/");
}

function placeholderOf(sp) {
  var ph = sp.getElementsByTagNameNS(PRESENTATIONML, "ph")[0];
  return ph ? { type: ph.getAttribute("type") || "obj", idx: ph.getAttribute("idx") } : null;
}

/* The layout's placeholder and the master's that a slide's placeholder takes its design from, and the master's kind of it. */
function placeholdersBehind(sp, layout, master) {
  var ph = placeholderOf(sp);
  if (!ph) return null;
  var inLayout = layout && placeholderIn(layout.doc, ph.type, ph.idx);
  var type = masterType(inLayout ? placeholderOf(inLayout).type : ph.type);
  return { layout: inLayout, master: master && placeholderIn(master.doc, type, null), type: type };
}

/* The placeholder of the same idx, as PowerPoint pairs a slide's with its layout's, or else the first of the same type. */
function placeholderIn(doc, type, idx) {
  var shapes = doc.getElementsByTagNameNS(PRESENTATIONML, "sp");
  var sameType = null;
  for (var i = 0; i < shapes.length; i++) {
    var ph = placeholderOf(shapes[i]);
    if (!ph) continue;
    if (idx !== null && ph.idx === idx) return shapes[i];
    if (!sameType && ph.type === type) sameType = shapes[i];
  }
  return sameType;
}

/* A master has a title and a body placeholder for all the kinds a layout has, and its own few. */
function masterType(type) {
  return { title: "title", ctrTitle: "title", dt: "dt", ftr: "ftr", sldNum: "sldNum", hdr: "hdr" }[type] || "body";
}

function masterStyle(doc, type) {
  var styles = doc.getElementsByTagNameNS(PRESENTATIONML, "txStyles")[0];
  var name = type === "title" ? "titleStyle" : type === "body" ? "bodyStyle" : "otherStyle";
  return styles ? childrenNamed(styles, name)[0] : null;
}

/* A shape's list style or body properties, as the text body has them. */
function inTextBody(sp, name) {
  var body = sp && childrenNamed(sp, "txBody")[0];
  return body ? childrenNamed(body, name)[0] : null;
}

/*
 * A text box can be set not to wrap (wrap="none" on its body). Its lines then run on past
 * its sides, to the right of text aligned left, to the left of text aligned right, and both
 * ways from the middle of centred text, and break only where the deck breaks them. PPTXjs
 * never reads the setting and wraps them at the box's width like any other. Such a box is
 * usually sized to its text, so it wrapped where a font drew a line a little wider than
 * the deck's, and the line's last word went down a line. Across 86 decks in desktop
 * Chrome, 14 boxes in 3 decks wrapped, and the text of 210 more, centred and wider than
 * its box, ran out to the right alone.
 *
 * PPTXjs copies a shape's id into the page, as _id, and does nothing else with it. So the
 * setting goes there: such a shape's id becomes "nowrap-" and its own, and pptx.html keeps
 * the lines of those boxes whole. A slide's placeholder that says neither way takes the
 * setting from its layout's placeholder, or else its master's, as PowerPoint does. A
 * shape in a layout or a master, which PPTXjs draws behind each slide, says for itself.
 */
var SETS_NO_WRAP = /<a:bodyPr\b[^>]*\swrap="none"/;

function linesLeftWhole(zip, name, xml, designs) {
  var layout = /^ppt\/slides\//.test(name) ? designPart(zip, relatedPart(zip, name, "slideLayout"), designs) : null;
  var master = layout && designPart(zip, relatedPart(zip, layout.name, "slideMaster"), designs);
  if (!SETS_NO_WRAP.test(xml) && !(layout && layout.unwraps) && !(master && master.unwraps)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var changed = false;
  var shapes = doc.getElementsByTagNameNS(PRESENTATIONML, "sp");
  for (var i = 0; i < shapes.length; i++) {
    var behind = placeholdersBehind(shapes[i], layout, master);
    var chain = [shapes[i]].concat(behind ? [behind.layout, behind.master] : []);
    var wrap = null;
    for (var j = 0; j < chain.length && wrap === null; j++) {
      var bodyPr = inTextBody(chain[j], "bodyPr");
      if (bodyPr && bodyPr.hasAttribute("wrap")) wrap = bodyPr.getAttribute("wrap");
    }
    var nv = childrenNamed(shapes[i], "nvSpPr")[0];
    var props = nv && childrenNamed(nv, "cNvPr")[0];
    if (wrap === "none" && props) {
      props.setAttribute("id", "nowrap-" + props.getAttribute("id"));
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

/*
 * A placeholder that gives its shape an outline but no place sits where its layout's
 * placeholder does, or else its master's. PPTXjs reads that place from the shape alone and
 * threw ("reading 'x'"), so the place is written into the shape.
 */
var PLACED_BY_DESIGN = /<p:spPr\b[^>]*>\s*<a:(prstGeom|custGeom)\b/;

function placeTheDesignGives(zip, name, xml, designs) {
  if (!PLACED_BY_DESIGN.test(xml)) return xml;
  var layout = /^ppt\/slides\//.test(name) ? designPart(zip, relatedPart(zip, name, "slideLayout"), designs) : null;
  var master = designPart(zip, relatedPart(zip, layout ? layout.name : name, "slideMaster"), designs);
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var changed = false;
  var shapes = doc.getElementsByTagNameNS(PRESENTATIONML, "sp");
  for (var i = 0; i < shapes.length; i++) {
    var spPr = childrenNamed(shapes[i], "spPr")[0];
    var first = spPr && spPr.firstElementChild;
    if (!first || (first.localName !== "prstGeom" && first.localName !== "custGeom")) continue;
    var behind = placeholdersBehind(shapes[i], layout, master);
    var place = behind && (placeOf(behind.layout) || placeOf(behind.master));
    if (!place) continue;
    spPr.insertBefore(doc.importNode(place, true), first);
    changed = true;
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

function placeOf(sp) {
  var spPr = sp && childrenNamed(sp, "spPr")[0];
  return spPr ? childrenNamed(spPr, "xfrm")[0] : null;
}

/*
 * PPTXjs writes every space in a run of text as &nbsp; (genSpanElement), so a line of a
 * paragraph has nowhere to break, and the paragraph's overflow-wrap: break-word breaks it
 * wherever it runs out of room instead: "Language" at the end of one line and "s" at the
 * start of the next. Across 105 decks in desktop Chrome, that cut 763 words in 29 of the
 * 86 PPTXjs opens. Every word still cut after this is wider than its whole box, or sits in
 * a box PPTXjs draws with no width. The spaces go back to spaces here, and pptx.html gives the runs white-space: pre-wrap,
 * which keeps a run of them as wide as before and breaks lines at them, as PowerPoint
 * does. A no-break space a deck really has becomes an ordinary one too: those decks had
 * 15 in 9,031 runs of text.
 *
 * PPTXjs puts every slide in at once, in a single task, so an observer, which runs when
 * that task ends, finds them all and changes them before they are first drawn. The poll
 * below would leave the cut words on screen for up to half a second.
 */
function spacesThatBreak(root) {
  var blocks = root.querySelectorAll(".text-block");
  for (var i = 0; i < blocks.length; i++) {
    var walker = document.createTreeWalker(blocks[i], NodeFilter.SHOW_TEXT);
    for (var node = walker.nextNode(); node; node = walker.nextNode()) {
      if (node.data.indexOf("\u00a0") !== -1) node.data = node.data.replace(/\u00a0/g, " ");
    }
  }
}

/*
 * PPTXjs gives every paragraph of a body, an object or a plain shape font-weight: 100,
 * beside the font-size: 0 that closes the gaps between its runs, and a run that is not
 * bold inherits it. A deck has no such weight: its text is bold or it is not. On the Mac,
 * Arial and Calibri have no face that light, so the text drew regular there. Android
 * draws them and other sans-serif faces in Roboto, which has one, so the text came out in
 * Roboto Thin, with a third of the ink. The weight goes back to normal in the rules PPTXjs
 * wrote it in, which leaves a bold run's own weight alone. PPTXjs appends those rules after
 * the last slide, in the same task, so the observer below finds them too.
 */
function regularWeight(root) {
  var styles = root.querySelectorAll("style");
  for (var i = 0; i < styles.length; i++) {
    var rules = styles[i].sheet ? styles[i].sheet.cssRules : [];
    for (var j = 0; j < rules.length; j++) {
      if (rules[j].style && rules[j].style.fontWeight === "100") rules[j].style.fontWeight = "normal";
    }
  }
}

/*
 * What Gander cannot draw is marked where it would be, by a box that says what is missing,
 * as a picture in a .doc or .odt is (prose-draw.js). Left blank, a slide reads as though
 * the deck had nothing there. Three kinds, which between them mark 23 of the 97 that open of
 * the 108 decks this was tried on, Apache POI's 100 test decks among them:
 *
 * - a chart, picture, table or diagram PPTXjs threw on, or a shape with text, which the
 *   catch in lib/pptx/pptxjs.js leaves as an empty block in its place, marked with what it
 *   was, as it marks a chart that threw as nv.d3 drew it (7 decks);
 * - a chart of a type PPTXjs does not draw. It draws line, bar, pie, area and scatter
 *   charts, and leaves the box of any other, a doughnut or a radar, empty (4 decks);
 * - a picture in a format no browser draws, Windows' EMF and WMF or TIFF, which goes in
 *   as an image that fails to load (13 decks).
 */
var MISSING = {
  chart: "A chart that cannot be shown here",
  table: "A table that cannot be shown here",
  diagram: "A diagram that cannot be shown here",
  object: "An object that cannot be shown here",
  picture: "A picture that cannot be shown here",
  format: "A picture in a format that cannot be shown here",
  shape: "A shape that cannot be shown here"
};

function whatIsMissing(root) {
  var marked = root.querySelectorAll("[data-vw-missing]");
  for (var i = 0; i < marked.length; i++) markMissing(marked[i], marked[i].getAttribute("data-vw-missing"));
  var charts = root.querySelectorAll("div[id^='chart']");
  for (var j = 0; j < charts.length; j++) {
    if (!charts[j].querySelector("svg, .vw-missing")) markMissing(charts[j], "chart");
  }
}

/*
 * A picture's error does not bubble, so it is caught on its way down, from before the slides
 * go in. A picture bullet is an image too, inside the text, and is left alone.
 */
document.getElementById("result").addEventListener("error", function (e) {
  var img = e.target;
  if (img.tagName !== "IMG" || !img.parentNode || !img.parentNode.classList.contains("block")) return;
  // A browser draws these, so one that fails is damaged rather than in a format it cannot show
  var drawn = /^data:image\/(png|jpeg|gif|bmp|webp|svg\+xml);/.test((img.getAttribute("src") || "").slice(0, 30));
  markMissing(img.parentNode, drawn ? "picture" : "format");
}, true);

/*
 * The box fills the block. Its type is sized to the slide, as the WebView fits each deck to
 * the screen, so that it comes out the same size on the screen whatever the deck's width.
 * In a block too small for the sentence it is set smaller, and smaller still leaves the
 * outline alone.
 */
function markMissing(block, kind) {
  while (block.firstChild) block.removeChild(block.firstChild);
  var box = document.createElement("div");
  box.className = "vw-missing";
  box.textContent = MISSING[kind] || MISSING.shape;
  block.appendChild(box);
  var slide = block.closest(".slide");
  var width = slide ? slide.offsetWidth : 960;
  for (var size = width / 40; size > width / 100; size *= 0.8) {
    box.style.fontSize = size + "px";
    if (box.scrollHeight <= box.clientHeight && box.scrollWidth <= box.clientWidth) return;
  }
  box.textContent = "";
}

new MutationObserver(function (records, observer) {
  var result = document.getElementById("result");
  if (!result.querySelector(".slide")) return;
  observer.disconnect();
  spacesThatBreak(result);
  regularWeight(result);
  whatIsMissing(result);
}).observe(document.getElementById("result"), { childList: true });

try {
  $("#result").pptxToHtml({
    pptxFileUrl: "/doc/file.pptx",
    slideMode: false,
    keyBoardShortCut: false,
    mediaProcess: true
  });
} catch (e) {
  vwError("Could not render this presentation", String(e));
}
var vwChecks = 0;
var vwPoll = setInterval(function () {
  vwChecks++;
  var slides = document.querySelectorAll("#result .slide");
  if (slides.length > 0) {
    vwDisarmLinks(document.getElementById("result"));
    /* Once the slides exist, because it is they that widen the layout viewport. */
    vwFitHeight();
    vwStatusDone();
    clearInterval(vwPoll);
  } else if (vwChecks > 40) {
    clearInterval(vwPoll);
    vwError(
      "Could not render this presentation",
      "This .pptx may use features the built-in renderer does not understand yet."
    );
  }
}, 500);
