"use strict";

/*
 * Pages of paper, for the word processor formats Gander reads itself: OpenDocument
 * text, Rich Text and Word 97-2003. Each has a reader of its own (prose-odt.js,
 * prose-rtf.js and prose-doc.js) that knows its file format and nothing about how a
 * document should look. This is the half they share, which knows how a document should
 * look and nothing about files.
 *
 * A reader hands over formatting as a plain object, a "bag": { bold: true, size: 11 }
 * for characters, { align: "center", before: 6 } for a paragraph. Lengths are numbers,
 * in points, because points are what all three formats reduce to: Rich Text and Word
 * count in twentieths of one, OpenDocument in centimetres and inches.
 *
 * Nothing from a document is ever parsed as markup or as a stylesheet. Text goes in as
 * text nodes and formatting goes in through the style object, which takes one property
 * at a time and drops a value it does not recognise, so there is no string a file can
 * carry that ends a declaration and starts another. That is why these viewers need no
 * sanitiser behind them, where md.html, which has to build HTML from a string, does.
 */

var VW_PROSE_LETTER = { width: 612, height: 792, top: 72, right: 72, bottom: 72, left: 72 };

/*
 * Starts a document: the ground the sheets lie on, and the stylesheet their formatting
 * is written into. No sheet yet, because a reader learns the paper size from the file.
 */
function vwProseOpen(container) {
  var wrap = document.createElement("div");
  wrap.className = "vw-paper";
  container.appendChild(wrap);

  var style = document.createElement("style");
  document.head.appendChild(style);

  return {
    wrap: wrap,
    css: style.sheet,
    classes: {},      // bag, as JSON -> class name
    count: 0,
    base: "",         // the class every sheet carries: the document's default font
    layout: null,
    sheet: null,
    body: null,       // where the reader appends paragraphs and tables
    notes: null,
    urls: []
  };
}

/*
 * A new sheet of paper, which is how a page break is drawn. layout is { width, height,
 * top, right, bottom, left } in points, and may carry header and footer functions that
 * are handed an element to fill; leave it out for another sheet like the last.
 *
 * The height is a least height. These readers do not paginate: deciding where a page
 * ends needs every line measured in the document's real fonts, which a phone does not
 * have. So a sheet ends where the file says a page must end, at a page break, and
 * otherwise runs as long as its text, as docx.html's does for a file with no record of
 * where Word ended its pages.
 *
 * Sizes are held to what paper could be. They come from a file, and a width of nothing,
 * or of a mile, would otherwise become the width of the screen's whole layout.
 */
function vwProseSheet(prose, layout) {
  layout = layout || prose.layout || VW_PROSE_LETTER;
  prose.layout = layout;

  function held(v, low, high, otherwise) {
    return (typeof v === "number" && v >= low && v <= high) ? v : otherwise;
  }
  var width = held(layout.width, 144, 3600, VW_PROSE_LETTER.width);
  var height = held(layout.height, 144, 3600, VW_PROSE_LETTER.height);
  var side = width / 3;

  var sheet = document.createElement("section");
  if (prose.base) sheet.className = prose.base;
  sheet.style.width = width + "pt";
  sheet.style.minHeight = height + "pt";
  sheet.style.paddingTop = held(layout.top, 0, height / 3, 72) + "pt";
  sheet.style.paddingBottom = held(layout.bottom, 0, height / 3, 72) + "pt";
  sheet.style.paddingLeft = held(layout.left, 0, side, 72) + "pt";
  sheet.style.paddingRight = held(layout.right, 0, side, 72) + "pt";

  function part(name, fill) {
    var el = document.createElement("div");
    el.className = name;
    sheet.appendChild(el);
    if (fill) fill(el);
    return el;
  }
  if (layout.header) part("vw-header", layout.header);
  var body = part("vw-body");
  if (layout.footer) part("vw-footer", layout.footer);

  prose.wrap.appendChild(sheet);
  prose.sheet = sheet;
  prose.body = body;
  prose.notes = null;
  return body;
}

/*
 * Where footnotes and endnotes go: under a rule at the foot of the last sheet. On paper
 * a footnote sits at the foot of its own page, but these sheets are as long as their
 * text, so the foot of the page a note belongs to is not a place that exists.
 */
function vwProseNotes(prose) {
  if (!prose.notes) {
    prose.notes = document.createElement("div");
    prose.notes.className = "vw-notes";
    prose.body.appendChild(prose.notes);
  }
  return prose.notes;
}

/*
 * Scales the sheets so the widest fills the screen. docx.html's fitPageWidth, which says
 * why it measures at a zoom of 1, why it takes the widest sheet and not the first, and
 * why a rotation does not need it run again. Call it once the document is in the page.
 */
function vwProseFit(prose) {
  var wrap = prose.wrap;
  var sheets = wrap.querySelectorAll(".vw-paper > section");
  if (!sheets.length) return;

  wrap.style.setProperty("--vw-page-zoom", "1");
  var pad = getComputedStyle(wrap);
  var room = wrap.clientWidth -
    parseFloat(pad.paddingLeft || 0) - parseFloat(pad.paddingRight || 0);

  var widest = 0;
  for (var i = 0; i < sheets.length; i++) {
    widest = Math.max(widest, sheets[i].getBoundingClientRect().width);
  }
  if (room > 0 && widest > 0) wrap.style.setProperty("--vw-page-zoom", room / widest);
  vwFitHeight();
}

/* ------------------------------------------------------------------------------------
 * Formatting
 * ---------------------------------------------------------------------------------- */

function vwProsePt(n) {
  // Rounded to a hundredth so that 0.1 + 0.2 does not make two classes out of one
  return (Math.round(n * 100) / 100) + "pt";
}

function vwProseBorder(b) {
  if (!b || !b.width) return "none";
  // Half a point is the thinnest line a word processor draws and it vanishes on a
  // zoomed-out page, so nothing is drawn thinner than a CSS pixel
  return Math.max(0.75, Math.min(b.width, 12)) + "pt " + (b.style || "solid") + " " +
    (b.color || "#000000");
}

var VW_PROSE_SIDES = ["Top", "Right", "Bottom", "Left"];

/*
 * Writes a bag into a style object. One function for characters, paragraphs and cells,
 * since a paragraph style in all three formats carries character formatting too, and a
 * key that is not in the bag writes nothing.
 *
 * Line spacing is against 1.2 because "single" in a word processor means the font's own
 * line height and not the 1.0 CSS would make of it, and 1.2 is where the faces these
 * documents use sit. "At least" has no CSS of its own; max() says it on Chromium 79 and
 * later, and an engine that does not know max() keeps the plain value before it.
 */
function vwProseDeclare(s, bag) {
  if (bag.bold != null) s.fontWeight = bag.bold ? "bold" : "normal";
  if (bag.italic != null) s.fontStyle = bag.italic ? "italic" : "normal";

  if (bag.underline != null || bag.strike != null) {
    var lines = [];
    if (bag.underline) lines.push("underline");
    if (bag.strike) lines.push("line-through");
    s.textDecorationLine = lines.length ? lines.join(" ") : "none";
    var kind = bag.underline === true || !bag.underline ? bag.strike : bag.underline;
    if (kind && kind !== true) s.textDecorationStyle = kind;
  }

  if (bag.color) s.color = bag.color;
  if (bag.background) s.backgroundColor = bag.background;
  if (bag.size) s.fontSize = vwProsePt(Math.max(1, Math.min(bag.size, 400)));
  else if (bag.sizePercent) s.fontSize = bag.sizePercent + "%";
  if (bag.font) s.fontFamily = bag.font;

  // A size that comes with a position is the size to draw the raised or lowered text
  // at, which its reader has already made smaller by as much as its format says; the
  // 0.7em is for raised text that comes with no size at all
  if (bag.position) {
    s.verticalAlign = bag.position === "sub" ? "sub" : "super";
    if (!bag.size && !bag.sizePercent) s.fontSize = "0.7em";
    s.lineHeight = "0";
  }
  if (bag.caps) s.textTransform = "uppercase";
  if (bag.smallCaps) s.fontVariant = "small-caps";
  if (bag.spacing) s.letterSpacing = vwProsePt(bag.spacing);
  if (bag.hidden) s.display = "none";

  if (bag.align) s.textAlign = bag.align;
  if (bag.rtl != null) s.direction = bag.rtl ? "rtl" : "ltr";
  if (bag.left != null) s.marginLeft = vwProsePt(bag.left);
  if (bag.right != null) s.marginRight = vwProsePt(bag.right);
  if (bag.first != null) s.textIndent = vwProsePt(bag.first);
  if (bag.before != null) s.marginTop = vwProsePt(bag.before);
  if (bag.after != null) s.marginBottom = vwProsePt(bag.after);

  if (bag.line) s.lineHeight = String(Math.round(bag.line * 120) / 100);
  if (bag.lineExact) s.lineHeight = vwProsePt(bag.lineExact);
  if (bag.lineAtLeast) {
    s.lineHeight = "1.2";
    s.lineHeight = "max(1.2em, " + vwProsePt(bag.lineAtLeast) + ")";
  }

  for (var i = 0; i < VW_PROSE_SIDES.length; i++) {
    var side = VW_PROSE_SIDES[i];
    if (bag["border" + side]) s["border" + side] = vwProseBorder(bag["border" + side]);
    if (bag["pad" + side] != null) s["padding" + side] = vwProsePt(bag["pad" + side]);
  }

  if (bag.valign) s.verticalAlign = bag.valign;
  if (bag.width != null) s.width = vwProsePt(bag.width);
  if (bag.minHeight != null) s.height = vwProsePt(bag.minHeight);
}

/*
 * The class that carries a bag, made the first time that bag is seen. A long document
 * is thousands of paragraphs in a few dozen formats, so writing each format once and
 * naming it keeps the page a fraction of the size inline styles would make it. An empty
 * bag has no class.
 *
 * The rule goes in empty and is then filled through its style object, which is what
 * keeps a file's strings out of stylesheet text: see the head of this file.
 */
function vwProseClass(prose, bag) {
  var key = JSON.stringify(bag);
  if (key === "{}") return "";
  var name = prose.classes[key];
  if (!name) {
    name = prose.classes[key] = "vw-f" + (prose.count++);
    var at = prose.css.insertRule(".vw-paper ." + name + " {}", prose.css.cssRules.length);
    vwProseDeclare(prose.css.cssRules[at].style, bag);
  }
  return name;
}

/*
 * A colour from a file, as CSS, or nothing if it is not one. Six hex digits with or
 * without the hash; the formats that count in red, green and blue build the string
 * themselves.
 */
function vwProseColor(text) {
  var m = /^#?([0-9a-fA-F]{6})$/.exec(text || "");
  return m ? "#" + m[1].toLowerCase() : null;
}

function vwProseRgb(r, g, b) {
  function hex(n) { return (256 + (n & 255)).toString(16).slice(1); }
  return "#" + hex(r) + hex(g) + hex(b);
}

/*
 * A font family for CSS, from the name a file gives and the kind of face the file says
 * it is, when it says: "roman", "swiss", "modern", "script" or "decor", which are the
 * words Rich Text uses and the other two formats have equivalents of.
 *
 * The kind is what matters on a phone. It has none of Times New Roman, Arial, Calibri
 * or Liberation Serif, so the name alone falls through to the default sans-serif and a
 * document set in Times arrives in Roboto. With the generic family after it, a serif
 * document stays serif and a listing in Courier keeps its columns.
 *
 * A name this recognises is believed over the file. LibreOffice 25.2, converting a Word
 * document on a machine without Calibri, wrote it down as "roman", and the headings set
 * in it came out in a serif. The file's word is for the names nobody here has heard of.
 */
var VW_PROSE_SERIF = /times|georgia|garamond|palatino|cambria|book|roman|serif|century|minion|baskerville|didot|caslon|mincho|song|sim ?sun|batang|charter|utopia|antiqua|constantia/i;
var VW_PROSE_MONO = /courier|consol|mono|typewriter|fixed|menlo|lucida console|andale|terminal|gothic che/i;
var VW_PROSE_SANS = /sans|arial|helvetica|calibri|verdana|tahoma|segoe|roboto|gothic|trebuchet/i;

function vwProseFont(name, kind) {
  name = String(name || "").replace(/["\\;{}<>]/g, "").replace(/\s+/g, " ").trim();
  var generic =
    VW_PROSE_MONO.test(name) ? "monospace" :
    VW_PROSE_SANS.test(name) ? "sans-serif" :
    VW_PROSE_SERIF.test(name) ? "serif" :
    kind === "roman" ? "serif" :
    kind === "swiss" ? "sans-serif" :
    kind === "modern" ? "monospace" :
    kind === "script" ? "cursive" : "sans-serif";
  return name ? '"' + name + '", ' + generic : generic;
}

/* ------------------------------------------------------------------------------------
 * Numbering
 * ---------------------------------------------------------------------------------- */

var VW_PROSE_ROMAN = [
  [1000, "m"], [900, "cm"], [500, "d"], [400, "cd"], [100, "c"], [90, "xc"],
  [50, "l"], [40, "xl"], [10, "x"], [9, "ix"], [5, "v"], [4, "iv"], [1, "i"]
];

/*
 * A list number as its document would print it. format is "decimal", "lowerLetter",
 * "upperLetter", "lowerRoman" or "upperRoman", and anything else counts in digits.
 * Letters run a to z and then aa, bb, cc, which is what Word and LibreOffice both do,
 * and not the aa, ab, ac of a spreadsheet's columns.
 */
function vwProseNumber(n, format) {
  if (format === "lowerRoman" || format === "upperRoman") {
    if (n < 1 || n > 3999) return String(n);
    var out = "";
    for (var i = 0; i < VW_PROSE_ROMAN.length; i++) {
      while (n >= VW_PROSE_ROMAN[i][0]) { out += VW_PROSE_ROMAN[i][1]; n -= VW_PROSE_ROMAN[i][0]; }
    }
    return format === "upperRoman" ? out.toUpperCase() : out;
  }
  if (format === "lowerLetter" || format === "upperLetter") {
    if (n < 1) return String(n);
    var letter = String.fromCharCode(97 + (n - 1) % 26);
    var times = Math.floor((n - 1) / 26) + 1;
    var word = new Array(Math.min(times, 20) + 1).join(letter);
    return format === "upperLetter" ? word.toUpperCase() : word;
  }
  return String(n);
}

/*
 * The label of a list item: a bullet or a number, in front of the paragraph it belongs
 * to and as wide as that paragraph's hanging indent. See .vw-label in prose.css for why
 * it is text. hang is in points; with none, the label is followed by a space unless
 * the reader has already put a tab or a space after it.
 */
function vwProseLabel(prose, para, text, bag, hang) {
  var label = document.createElement("span");
  label.className = ("vw-label " + vwProseClass(prose, bag || {})).trim();
  if (hang > 0) label.style.minWidth = vwProsePt(hang);
  label.textContent = hang > 0 || /[\t ]$/.test(text) ? text : text + " ";
  para.insertBefore(label, para.firstChild);
  return label;
}

/* ------------------------------------------------------------------------------------
 * Symbol fonts
 * ---------------------------------------------------------------------------------- */

/*
 * Word stores a symbol font's character in the private use range, U+F000 plus the font's
 * own code, and the older formats store the bare code. The tables are in symbol-fonts.js,
 * which the slides share.
 */

/*
 * Which of the fonts in symbol-fonts.js a font name is, by its name there, or null. Each has
 * a table of its own, and the same code is a different symbol in each.
 */
function vwProseSymbolFont(name) {
  var face = String(name || "").trim().toLowerCase();
  if (/webdings/.test(face)) return "webdings";
  var wingdings = /wingdings[\s-]*([23]\b)?/.exec(face);
  if (wingdings) return wingdings[1] ? "wingdings " + wingdings[1] : "wingdings";
  return face === "symbol" ? "symbol" : null;
}

/*
 * Text set in a symbol font, as the Unicode it stands for. which is what
 * vwProseSymbolFont answered. With no font to go by, a character in the private use
 * range is still certainly one of these, so it is read as Symbol, which is where Word's
 * own bullet lives.
 */
function vwProseSymbols(text, which) {
  var dense = VW_DINGBATS[which];
  var table = which === "wingdings" ? VW_WINGDINGS : VW_SYMBOL;
  var out = "";
  for (var i = 0; i < text.length; i++) {
    var c = text.charCodeAt(i);
    var code = (c >= 0xF020 && c <= 0xF0FF) ? c - 0xF000 : (which && c <= 0xFF ? c : -1);
    if (code < 0x20) out += text.charAt(i);
    else if (dense) out += dense.charAt(code - 0x20) || "\u2022";
    else if (table[code]) out += table[code];
    else if (which === "wingdings" && code > 0x20) out += "\u2022";
    else out += String.fromCharCode(code);
  }
  return out;
}

/* ------------------------------------------------------------------------------------
 * Pictures
 * ---------------------------------------------------------------------------------- */

/*
 * What kind of picture some bytes are, from how they begin, or null for one a browser
 * cannot draw. A file's own word for it is not trusted: Rich Text says \pngblip over
 * JPEG data often enough, and an OpenDocument picture's name can end in anything.
 */
function vwProsePictureType(b) {
  function at(i, s) {
    for (var k = 0; k < s.length; k++) if (b[i + k] !== s.charCodeAt(k)) return false;
    return true;
  }
  if (b.length < 12) return null;
  if (b[0] === 0x89 && at(1, "PNG")) return "image/png";
  if (b[0] === 0xFF && b[1] === 0xD8 && b[2] === 0xFF) return "image/jpeg";
  if (at(0, "GIF8")) return "image/gif";
  if (at(0, "BM")) return "image/bmp";
  if (at(0, "RIFF") && at(8, "WEBP")) return "image/webp";
  var head = "";
  for (var i = 0; i < Math.min(b.length, 256); i++) head += String.fromCharCode(b[i]);
  if (/<svg[\s>]/i.test(head) || (/^\s*<\?xml/.test(head) && /svg/i.test(head))) return "image/svg+xml";
  return null;
}

/*
 * An <img> for a picture held as bytes, sized in points if the file gave a size. When
 * the bytes are something no browser draws, which for these formats is a Windows
 * metafile, a box that keeps the picture's place and says so: see .vw-nopicture.
 *
 * The URL is a blob: one, which the viewer's content policy allows and which can name
 * nothing but bytes this page already holds. Inside an <img> even an SVG can run no
 * script and fetch nothing.
 */
function vwProsePicture(prose, bytes, width, height, alt) {
  var type = bytes && vwProsePictureType(bytes);
  if (!type) {
    var box = document.createElement("span");
    box.className = "vw-nopicture";
    box.textContent = "A picture in a format that cannot be shown here";
    if (width > 24) box.style.width = vwProsePt(width);
    return box;
  }
  var url = URL.createObjectURL(new Blob([bytes], { type: type }));
  prose.urls.push(url);
  var img = document.createElement("img");
  img.src = url;
  img.alt = alt || "";
  if (width > 0) img.style.width = vwProsePt(width);
  if (width > 0 && height > 0) {
    // The ratio and not the height, so that a picture wider than the page shrinks whole
    img.style.aspectRatio = width + " / " + height;
    img.setAttribute("width", Math.round(width * 4 / 3));
    img.setAttribute("height", Math.round(height * 4 / 3));
  }
  return img;
}
