/*
 * A workbook, drawn as Excel draws one: a sheet at a time, on its grid, under its column letters
 * and beside its row numbers, its columns as wide and its rows as tall as the file makes them,
 * its frozen rows and columns kept on screen, with the sheets' tabs at the foot of the screen.
 * A tap on a cell shows all of it in a bar above the tabs, a long text, a formula or a note
 * included, which a narrow column cuts off on the grid. Pinches and double taps zoom the sheet
 * here rather than in the WebView: see the zoom below.
 */

/*
 * Issue #37. Given bytes, SheetJS reads a text file as Latin-1 unless it starts with a UTF-8
 * byte order mark, and a CSV saved as UTF-8 seldom has one, so Флаг came out as Ð¤Ð»Ð°Ð³. A file
 * that is valid UTF-8 goes in already decoded. Anything else goes in as bytes, as it always did:
 * every binary workbook, a UTF-16 file, which SheetJS knows by its mark, and a Latin-1 one.
 *
 * Strict, and both halves of that matter. Decoded leniently, a Latin-1 file reaches SheetJS with
 * its accents turned to U+FFFD, and an .xlsx reaches it as text, which it never finished reading.
 * SheetJS's own codepage: 65001 was no better: it read "Café,Zürich" as "Caf鬚𲩣h".
 *
 * dense has SheetJS keep a sheet's cells in an array for each row rather than in one object keyed
 * by address. Read in a fresh page in Chromium 151 on a Mac, a workbook of 100,000 rows by ten
 * columns took 1.6 to 1.9 s this way and 2.1 to 2.8 s the other; at 10,000 rows the two were level.
 *
 * SheetJS keeps the file's parts (bookFiles), for xlsx-styles.js to read the cells' looks from, their
 * number formats among them. SheetJS can keep each cell's own (cellNF), which cost 26 MB on a sheet of
 * a million cells; a format is the same for every cell of a look.
 */
function readWorkbook(bytes) {
  var text = null;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch (e) {
    // Not UTF-8
  }
  return text === null
    ? XLSX.read(bytes, { type: "array", cellDates: true, dense: true, bookFiles: true })
    : XLSX.read(text, { type: "string", cellDates: true, dense: true });
}

/*
 * A large sheet is drawn a piece at a time.
 *
 * Drawn whole, a sheet is one table, and the WebView lays out every cell of it before it shows
 * any. In Chromium 151 on a Mac a sheet of 100,000 rows by ten columns took 17 s to appear, and a
 * minute with the processor slowed four times, about a mid-range phone, with the page frozen all
 * the while. Nor can rows go into that one table a few at a time: each addition has the whole
 * table laid out again, which took seconds once it was 20,000 rows long.
 *
 * So a sheet's rows are cut into pieces of about PIECE_CELLS cells. The first is drawn at once.
 * Each of the others stands as an empty block of its height until the reader scrolls to within a
 * screen of it, so the page is as long as the sheet from the start. Every column is as wide as the
 * file makes it, so the pieces line up by themselves. A merged range is never cut.
 */
var PIECE_CELLS = 5000;

/* Rows in a piece, whatever the width of the sheet */
var PIECE_ROWS_MIN = 20;
var PIECE_ROWS_MAX = 500;

/* The column letters' row, in px, and the row numbers' column at its narrowest and per digit */
var HEAD_HEIGHT = 20;
var HEAD_WIDTH_MIN = 26;
var HEAD_DIGIT = 8;

/*
 * A CSV, which says nothing of its columns' widths, has them fitted to its text from this many of
 * its first rows, no narrower than Excel's default and no wider than FIT_WIDEST. Excel opens one at
 * its default widths, which cut off any text longer than a short word; fitted, a CSV reads as a
 * table straight away.
 */
var FIT_ROWS = 500;
var FIT_WIDEST = 400;

/* How much of the screen frozen rows or columns may take, past which they scroll like the rest */
var FROZEN_MOST = 0.5;

/*
 * The empty grid drawn past a sheet's last cell to fill the screen, at most this many rows, and no
 * more cells than a piece: a sheet 16,385 columns wide was given 40 empty rows of them, 688,128 cells.
 */
var SPARE_ROWS_MOST = 200;

/*
 * The most a cell holds and the tallest a row grows, as Excel has them: 32,767 characters, and 409
 * points. One of Apache POI's test files holds a million characters in a cell, which took the page
 * seconds to lay out, and Excel would not have opened it.
 */
var CELL_MOST_CHARS = 32767;
var ROW_MOST_PX = 545;

/* How far past a sheet's last cell a merged range draws the sheet */
var MERGE_REACH = 200;

/* The most cells a CSV's widths are fitted from, however wide it is */
var FIT_CELLS = 20000;

/*
 * The zoom, in times Excel's 100%, where a px of the sheet is a dp on the phone. A sheet opens at
 * that, times the phone's own text size, which is about Excel's on a phone; it used to open fitted
 * to 980 px, the WebView's default width, at about 0.42, where 11 point text came out under 6 dp
 * and could not be read without zooming.
 *
 * The page zooms itself rather than letting the WebView zoom it. A pinch in the WebView magnifies
 * the screen, and a row or column held on screen with position: sticky is held to the part of the
 * page the screen showed before the pinch, which moves out of sight as the reader scrolls. Zoomed
 * here, by CSS zoom, the sheet is laid out again at the new size, and the headings, frozen panes,
 * tabs and the cell's bar stay where they belong at every size. While the fingers move the sheet is
 * scaled as a picture, which is cheap, and laid out at the end; content-visibility keeps that to
 * the pieces on screen: measured on a Mac with the processor slowed four times, 0.2 s for a sheet
 * of 8,000 rows, against 5 to 6 s with every piece laid out.
 */
var ZOOM_LEAST = 0.25;
var ZOOM_MOST = 4;

/*
 * How far past its cell a number may run on over empty cells, as a share of the cell. Excel runs none
 * on, but its font is not the phone's, and a number that fitted there by a hair should still show
 * here rather than turn to ###.
 */
var NUMBER_RUN_ON = 0.25;

/* A double tap: twice the sheet's own size about the place tapped, and back, as a PDF's goes */
var DOUBLE_TAP_ZOOM = 2;
var DOUBLE_TAP_MS = 250;
var DOUBLE_TAP_GAP_MS = 300;
var DOUBLE_TAP_SLOP = 40;
var TAP_SLOP = 12;

/* A column's letters from its number, 0 being A */
function columnName(c) {
  var s = "";
  for (c += 1; c > 0; c = Math.floor((c - 1) / 26)) s = String.fromCharCode(65 + (c - 1) % 26) + s;
  return s;
}

function escapeHtml(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/* [v] as Excel's General format writes it to [places] significant digits: 1234.5, 0.0012, 1.2E+12,
   as a power of ten wherever [power] says */
function generalNumber(v, places, power) {
  if (v === 0) return "0";
  var big = power || Math.abs(v) >= 1e11 || Math.abs(v) < 1e-9;
  if (!big) {
    var fixed = Number(v.toPrecision(places));
    var text = String(fixed);
    if (!/e/i.test(text)) return text;
  }
  var e = v.toExponential(Math.max(0, places - 1)).split("e");
  var mantissa = e[0].indexOf(".") >= 0 ? e[0].replace(/0+$/, "").replace(/\.$/, "") : e[0];
  var exp = +e[1];
  return mantissa + "E" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
}

/*
 * A date's value as Excel's formula bar gives one, from SheetJS's date in UTC: the day, its time
 * where it has one, and a time alone for a value under a day, which Excel counts from 1899.
 */
function dateValue(date) {
  // A number past Excel's last day, 9999-12-31, under a date's format, which SheetJS gives as no date
  if (isNaN(date.getTime())) return "";
  var iso = date.toISOString();
  var day = iso.slice(0, 10), time = iso.slice(11, 19);
  if (day === "1899-12-30" || day === "1899-12-31") return time;
  return time === "00:00:00" ? day : day + " " + time;
}

/* The first index in sorted [list] whose value is at least [v] */
function firstAtLeast(list, v) {
  var lo = 0, hi = list.length;
  while (lo < hi) {
    var mid = (lo + hi) >> 1;
    if (list[mid] < v) lo = mid + 1; else hi = mid;
  }
  return lo;
}

/*
 * The widest a character of text is, in ems, for whether text surely fits its cell without measuring
 * it: wider than nearly any letter or digit, and than a full-width character. A cell is clipped only
 * where its text is measured too wide for it, so text judged to fit by this must fit.
 */
var WIDEST_CHAR = 1.25;

/* The width of text in a font, for whether it spills out of its cell, measured off the page */
var measure = (function () {
  var context = null;
  var font = "";
  return function (text, cssFont) {
    if (!context) context = document.createElement("canvas").getContext("2d");
    if (cssFont !== font) context.font = font = cssFont;
    return context.measureText(text).width;
  };
})();

/*
 * The colour a number format gives a value, as [Red] in "#,##0;[Red](#,##0)", or null. SheetJS
 * writes the number and drops the colour, which is how a sheet shows its losses.
 */
var FORMAT_COLOURS = {
  black: "000000", blue: "0000FF", cyan: "00FFFF", green: "00FF00", magenta: "FF00FF",
  red: "FF0000", white: "FFFFFF", yellow: "FFFF00"
};

function formatColour(z, v) {
  if (typeof z !== "string" || z.indexOf("[") < 0 || typeof v !== "number") return null;
  var sections = [], part = "", quoted = false;
  for (var i = 0; i < z.length; i++) {
    var ch = z.charAt(i);
    if (ch === '"') quoted = !quoted;
    if (ch === "\\" && !quoted) { part += ch + z.charAt(++i); continue; }
    if (ch === ";" && !quoted) { sections.push(part); part = ""; continue; }
    part += ch;
  }
  sections.push(part);
  var section = v < 0 && sections.length > 1 ? sections[1] : v === 0 && sections.length > 2 ? sections[2] : sections[0];
  var m = /\[(black|blue|cyan|green|magenta|red|white|yellow|colou?r\s*(\d+))\]/i.exec(section);
  if (!m) return null;
  if (m[2]) {
    var n = +m[2];
    return n >= 1 && n <= 56 ? VW_XL_PALETTE[7 + n] : null;
  }
  return FORMAT_COLOURS[m[1].toLowerCase()];
}

/*
 * One sheet, laid out for drawing once it is first shown or searched.
 *
 * rows and cols are its visible rows and columns in order, the hidden ones left out, as Excel
 * leaves them out. heads are the frozen rows, kept on screen under the column letters, and the
 * rest are cut into pieces. lefts are the frozen columns, which with the row numbers stay at the
 * left while the rest scroll under them, and rights the rest. Each piece is two tables side by
 * side, the left one held with position: sticky: one held block per piece, where a held cell per
 * row cost the page about five times the work on each frame of a fling.
 */
function Sheet(wb, index, styles) {
  this.index = index;
  this.name = wb.SheetNames[index];
  this.ws = wb.Sheets[this.name];
  this.data = this.ws["!data"] || [];
  this.style = styles.sheet(index);
  this.looks = styles.looks;
  this.normal = styles.normal;
  this.drawn = styles.art(index);
  this.laid = false;
}

Sheet.prototype.cell = function (r, c) {
  var row = this.data[r];
  return row ? row[c] : undefined;
};

/* Whether a cell holds nothing, which text from beside it can run over: a formula giving "" holds one */
Sheet.prototype.empty = function (r, c) {
  var cell = this.cell(r, c);
  return !cell || cell.t === "z" || cell.v === undefined || cell.v === null || (cell.v === "" && !cell.f);
};

/* What a cell shows, as text */
Sheet.prototype.text = function (cell) {
  if (!cell || cell.t === "z") return "";
  var text;
  if (cell.w !== undefined) {
    text = String(cell.w);
  } else {
    try {
      text = String(XLSX.utils.format_cell(cell));
    } catch (e) {
      text = cell.v === undefined || cell.v === null ? "" : String(cell.v);
    }
  }
  return text.length > CELL_MOST_CHARS ? text.slice(0, CELL_MOST_CHARS) : text;
};

/*
 * The rows, columns, merges and pieces, worked out once. [room] is the screen in the sheet's own px
 * at the zoom it opens at, which the empty grid past the last cell fills, and which frozen panes
 * may take half of.
 */
Sheet.prototype.lay = function (room) {
  if (this.laid) return;
  this.laid = true;
  var style = this.style, ws = this.ws;
  var ref = ws["!ref"] ? XLSX.utils.decode_range(ws["!ref"]) : null;
  var lastRow = ref ? ref.e.r : -1, lastCol = ref ? ref.e.c : -1;
  var merges = (ws["!merges"] || []).filter(function (m) {
    return m && m.s && m.e && m.e.r >= m.s.r && m.e.c >= m.s.c && (m.e.r > m.s.r || m.e.c > m.s.c);
  });
  // A merge past the last cell draws the sheet that far, but a merge of a whole column, which would
  // be a million rows, no further than MERGE_REACH past it
  var cellRow = lastRow, cellCol = lastCol;
  merges.forEach(function (m) {
    if (m.e.r > lastRow) lastRow = Math.min(m.e.r, Math.max(cellRow, m.s.r) + MERGE_REACH);
    if (m.e.c > lastCol) lastCol = Math.min(m.e.c, Math.max(cellCol, m.s.c) + MERGE_REACH);
  });
  this.merges = merges;

  // Columns: every one to the last cell, then empty ones to the edge of the screen
  var fitted = style.widths ? null : this.fit(lastCol);
  this.width = [];
  this.cols = [];
  var across = 0, c;
  for (c = 0; c <= lastCol + VW_SPARE_COLUMNS; c++) {
    var info = style.cols[c];
    if (info && info.hidden) continue;
    var w = fitted && fitted[c] ? fitted[c] : info && info.w > 0 ? info.w : style.width;
    // Past the last cell, only as many as fit, so a short sheet does not scroll for nothing
    if (c > lastCol && across + w > room.width) break;
    this.width[c] = w;
    this.cols.push(c);
    across += w;
  }

  // Rows the same way, to the bottom of the screen
  this.height = [];
  this.rows = [];
  var down = 0, r;
  var spare = Math.min(SPARE_ROWS_MOST, Math.floor(PIECE_CELLS / Math.max(1, this.cols.length)));
  for (r = 0; r <= lastRow + spare; r++) {
    var row = style.rows[r];
    if (row && row.hidden) continue;
    var h = row ? row.h : style.height;
    // A row's slack left, for text that wraps and grows its row once it is laid out
    if (r > lastRow && down + h > room.height - style.height) break;
    this.height[r] = h;
    this.rows.push(r);
    down += h;
  }
  this.lastRow = this.rows.length ? this.rows[this.rows.length - 1] : -1;
  this.lastCol = this.cols.length ? this.cols[this.cols.length - 1] : -1;
  this.after = [];
  for (var n = 0; n < this.cols.length; n++) this.after[this.cols[n]] = n + 1 < this.cols.length ? this.cols[n + 1] : -1;

  // Frozen panes, unless they would take more of a phone's screen than they leave
  var frozen = style.frozen, self = this;
  var frozenRows = this.rows.filter(function (r) { return r < frozen.rows; });
  var frozenCols = this.cols.filter(function (c) { return c < frozen.columns; });
  var tall = frozenRows.reduce(function (sum, r) { return sum + self.height[r]; }, 0);
  var wide = frozenCols.reduce(function (sum, c) { return sum + self.width[c]; }, 0);
  if (tall > room.height * FROZEN_MOST) frozenRows = [];
  if (wide > room.width * FROZEN_MOST) frozenCols = [];
  this.heads = frozenRows;
  this.body = this.rows.slice(frozenRows.length);
  this.lefts = frozenCols;
  this.rights = this.cols.slice(frozenCols.length);
  this.showHeads = style.heads;
  this.headWidth = style.heads
    ? Math.max(HEAD_WIDTH_MIN, String(this.lastRow + 1).length * HEAD_DIGIT + 10) : 0;

  // Each merge's first visible row and column, which hold its text, and the merges on each row
  this.rowMerges = {};
  for (var k = 0; k < merges.length; k++) {
    var m = merges[k];
    var i = firstAtLeast(this.rows, m.s.r), j = firstAtLeast(this.cols, m.s.c);
    m.top = this.rows[i] !== undefined && this.rows[i] <= m.e.r ? this.rows[i] : -1;
    m.left = this.cols[j] !== undefined && this.cols[j] <= m.e.c ? this.cols[j] : -1;
    if (m.top < 0 || m.left < 0) continue;
    for (r = m.s.r; r <= Math.min(m.e.r, this.lastRow); r++) (this.rowMerges[r] || (this.rowMerges[r] = [])).push(m);
  }

  this.pieces = this.cut();
  this.fullWidth = this.headWidth + this.cols.reduce(function (sum, c) { return sum + self.width[c]; }, 0);

  // Conditional formatting, worked out once for the cells drawn: see xlsx-rules.js
  this.cf = null;
  try {
    if (typeof vwConditional === "function") this.cf = vwConditional(this);
  } catch (e) {
    this.cf = null;
  }
};

/* What conditional formatting gives a cell, or undefined */
Sheet.prototype.ruled = function (r, c) {
  var row = this.cf && this.cf[r];
  return row ? row[c] : undefined;
};

/* Widths for a sheet whose file gives none, fitted to the text of its first rows */
Sheet.prototype.fit = function (lastCol) {
  var widths = [], font = this.normal.px + "px " + this.normal.stack;
  var rows = Math.min(this.data.length, FIT_ROWS, Math.ceil(FIT_CELLS / Math.max(1, lastCol + 1)));
  for (var r = 0; r < rows; r++) {
    var row = this.data[r];
    if (!row) continue;
    for (var c = 0; c <= lastCol && c < row.length; c++) {
      var text = this.text(row[c]);
      if (!text) continue;
      var w = Math.ceil(measure(text.replace(/\n.*/g, ""), font)) + 2 * VW_CELL_PAD + 3;
      if (!(widths[c] >= w)) widths[c] = w;
    }
  }
  for (var k = 0; k < widths.length; k++) {
    if (widths[k]) widths[k] = Math.min(FIT_WIDEST, Math.max(this.style.width, widths[k]));
  }
  return widths;
};

/* The body's rows in pieces of about PIECE_CELLS cells, none of them ending inside a merged range */
Sheet.prototype.cut = function () {
  var body = this.body, n = body.length;
  if (!n) return [];
  var step = Math.max(PIECE_ROWS_MIN, Math.min(PIECE_ROWS_MAX, Math.floor(PIECE_CELLS / Math.max(1, this.cols.length))));
  var pieces = [];
  for (var i = 0; i < n;) {
    var end = Math.min(i + step, n) - 1;
    // Stretched to the foot of every merge begun in it
    for (var grew = true; grew;) {
      grew = false;
      for (var k = i; k <= end; k++) {
        var list = this.rowMerges[body[k]];
        if (!list) continue;
        for (var q = 0; q < list.length; q++) {
          if (list[q].e.r > body[end] && end < n - 1) {
            end = Math.min(firstAtLeast(body, list[q].e.r + 1), n) - 1;
            grew = true;
          }
        }
      }
    }
    pieces.push({ from: i, to: end, rows: body.slice(i, end + 1) });
    i = end + 1;
  }
  return pieces;
};

/* The next visible column after [c], or -1 */
Sheet.prototype.nextCol = function (c) {
  if (this.after[c] !== undefined) return this.after[c];
  var i = firstAtLeast(this.cols, c + 1);
  return i < this.cols.length ? this.cols[i] : -1;
};

Sheet.prototype.nextRow = function (r) {
  var i = firstAtLeast(this.rows, r + 1);
  return i < this.rows.length ? this.rows[i] : -1;
};

/*
 * A cell's look as it is drawn: its own, over what its table gives it where its own gives nothing,
 * as Excel lays a table's style under a cell's formatting, and under its conditional formatting.
 */
Sheet.prototype.lookAt = function (r, c) {
  var look = this.style.at(r, c);
  var table = this.style.tables.length ? this.style.tableAt(r, c) : null;
  if (table) look = this.looks.under(look, table);
  var ruled = this.ruled(r, c);
  return ruled ? this.looks.mix(look, ruled) : look;
};

/* The look of a cell's own borders, as its look gives them */
Sheet.prototype.borders = function (r, c) {
  if (r < 0 || c < 0) return null;
  var look = this.looks.list[this.lookAt(r, c)];
  return look ? look.borders : null;
};

Sheet.prototype.filled = function (r, c) {
  if (r < 0 || c < 0) return false;
  var look = this.looks.list[this.lookAt(r, c)];
  return !!(look && look.fill);
};

/*
 * The cells of row [r] over the columns [cols] of one part, in a section whose rows are [rows]: one
 * for each td, as [column, columns spanned, rows spanned, merge or null]. A cell under a merge that
 * begins above or to its left in the same section and part has no td of its own. A merge cut by the
 * frozen panes is drawn in each pane it crosses, its text in the first.
 */
Sheet.prototype.cellsOf = function (r, cols, rows) {
  var out = [], list = this.rowMerges[r];
  for (var i = 0; i < cols.length; i++) {
    var c = cols[i], merge = null;
    if (list) {
      for (var q = 0; q < list.length; q++) {
        if (c >= list[q].s.c && c <= list[q].e.c) { merge = list[q]; break; }
      }
    }
    if (!merge) {
      out.push([c, 1, 1, null]);
      continue;
    }
    var top = rows[firstAtLeast(rows, merge.s.r)];
    if (top !== r) continue;
    if (i > 0 && cols[i - 1] >= merge.s.c) continue;
    var across = 0, down = 0;
    for (var j = i; j < cols.length && cols[j] <= merge.e.c; j++) across++;
    for (var k = firstAtLeast(rows, r); k < rows.length && rows[k] <= merge.e.r; k++) down++;
    out.push([c, across, down, merge]);
  }
  return out;
};

/*
 * A section's rows as rows of its two tables, the left's and the right's, written in one pass, so
 * that text in a frozen column can run on into the columns that scroll, as in Excel. [letters] puts
 * the column letters first.
 */
Sheet.prototype.sectionHtml = function (rows, letters) {
  var left = [], right = [], style = this.style;
  this.growing = false;
  if (letters) {
    left.push(this.lettersHtml(this.lefts, true));
    right.push(this.lettersHtml(this.rights, false));
  }
  for (var i = 0; i < rows.length; i++) {
    var r = rows[i], h = this.height[r];
    var info = style.rows[r];
    var open = '<tr data-r="' + r + '"' + (h !== style.height ? ' style="height:' + h + 'px"' : "") + ">";
    left.push(open);
    right.push(open);
    if (this.showHeads) {
      var before = this.rows[firstAtLeast(this.rows, r) - 1];
      left.push('<th class="vw-rh' + ((before === undefined ? r > 0 : before !== r - 1) ? " vw-gap" : "") +
        '" data-n="' + (r + 1) + '"></th>');
    }
    var self = this;
    var below = i + 1 < rows.length ? rows[i + 1] : this.nextRow(r);
    var lefts = this.cellsOf(r, this.lefts, rows).map(function (spec) { return self.describe(r, spec, info, below); });
    var rights = this.cellsOf(r, this.rights, rows).map(function (spec) { return self.describe(r, spec, info, below); });
    var all = lefts.concat(rights);
    if (!this.growing) {
      for (var g = 0; g < all.length; g++) if (this.grows(all[g])) { this.growing = true; break; }
    }
    this.spill(all, lefts.length);
    for (var j = 0; j < all.length; j++) (j < lefts.length ? left : right).push(this.cellHtml(all[j]));
    left.push("</tr>");
    right.push("</tr>");
  }
  this.looks.flush();
  return [left.join(""), right.join("")];
};

/*
 * Whether a cell's text can make its row taller than the file says, in which case the section's two
 * tables have their rows evened out once they are laid out: text that wraps or turns, rich text,
 * which can be larger than its cell's font, and a font too big for its row.
 */
Sheet.prototype.grows = function (d) {
  if (!d.text) return false;
  var lookOf = d.lookOf;
  if (lookOf.wrap || lookOf.rot) return true;
  if (d.cell && d.cell.h && /<span|<b>|<i>|<u>|<s>/.test(d.cell.h)) return true;
  return (lookOf.px || this.normal.px) * 1.2 + 1 > d.height + 0.5;
};

/* One cell's look, edges and text, before it is written */
Sheet.prototype.describe = function (r, spec, rowInfo, nextBelow) {
  var c = spec[0], merge = spec[3];
  var looks = this.looks, style = this.style;
  var look = this.lookAt(r, c), ruled = this.ruled(r, c);
  var lookOf = looks.list[look] || {};
  var lastC = c, lastR = r, k;
  if (merge) {
    lastC = merge.e.c;
    lastR = merge.e.r;
  }
  var holdsText = !merge || (r === merge.top && c === merge.left);
  // A merge's value is in its first cell, which may be in a hidden row or column
  var cell = holdsText ? (merge ? this.cell(merge.s.r, merge.s.c) : this.cell(r, c)) : undefined;
  var text = this.text(cell);
  var width = this.width[c], height = this.height[r];
  if (spec[1] > 1) {
    width = 0;
    for (k = 0; k < spec[1]; k++) width += this.width[this.cols[firstAtLeast(this.cols, c) + k]];
  }
  if (spec[2] > 1) {
    height = 0;
    for (k = 0; k < spec[2]; k++) height += this.height[this.rows[firstAtLeast(this.rows, r) + k]];
  }

  // The edges this cell draws: its right and its bottom, each the heavier of its own border and the
  // one the cell beyond gives that side, and its left and top where no visible cell lies that way
  var own = lookOf.borders, right = this.nextCol(lastC), below = merge ? this.nextRow(lastR) : nextBelow;
  var ownRight = merge ? this.borders(r, lastC) : own, ownBottom = merge ? this.borders(lastR, c) : own;
  var beyondRight = right >= 0 ? this.borders(r, right) : null;
  var beyondBelow = below >= 0 ? this.borders(below, c) : null;
  var edge = looks.edge(
    vwHeavier(ownRight && ownRight.r, beyondRight && beyondRight.l),
    vwHeavier(ownBottom && ownBottom.b, beyondBelow && beyondBelow.t),
    c === this.cols[0] && own ? own.l : null,
    r === this.rows[0] && own ? own.t : null
  );

  var type = cell ? cell.t : "z";
  var align = lookOf.h || (type === "n" || type === "d" ? "right" : type === "b" || type === "e" ? "center" : "left");
  return {
    r: r, c: c, across: spec[1], down: spec[2], merge: merge, cell: cell, text: text,
    look: look, lookOf: lookOf, edge: edge, type: type, align: align, width: width, height: height,
    custom: !!(rowInfo && rowInfo.custom),
    // A filled cell covers the gridlines round it, as in Excel, and so does one beside it
    noRight: !!lookOf.fill || (right >= 0 && this.filled(r, right)),
    noBottom: !!lookOf.fill || (below >= 0 && this.filled(below, c)),
    bar: ruled && ruled.bar,
    spill: null
  };
};

/* The font a cell's text is drawn in, as canvas measures it */
Sheet.prototype.fontOf = function (lookOf) {
  return (lookOf.i ? "italic " : "") + (lookOf.b ? "700 " : "") + (lookOf.px || this.normal.px) + "px " +
    (lookOf.font || this.normal.stack);
};

/*
 * Text too long for its cell runs on over the empty cells beside it, as in Excel: to the right of
 * text set to the left, to the left of text set to the right, both ways from centred text, and is
 * cut off at the first cell that holds something. Text that wraps, turns, fills or sits in a
 * merged range stays in its cell. A number does not run on, as in Excel, but by a hair: see
 * NUMBER_RUN_ON. Each cell whose text is too wide for it and runs on nowhere is clipped.
 */
Sheet.prototype.spill = function (cells, boundary) {
  for (var i = 0; i < cells.length; i++) {
    var d = cells[i];
    if (!d.text) continue;
    var lookOf = d.lookOf, px = lookOf.px || this.normal.px;
    // A font too tall for a row set by hand, which keeps its height, is cut off at the row's foot
    if (d.custom && px * 1.2 + 1 > d.height + 0.5) d.clip = true;
    if (lookOf.rot) {
      if (d.custom) d.clip = true;
      continue;
    }
    if (lookOf.wrap || d.align === "fill" || d.align === "justify") continue;
    var room = d.width - 2 * VW_CELL_PAD - (lookOf.indent || 0) * VW_INDENT_PX;
    var text = d.text.replace(/\s*\n\s*/g, " ");
    // Most text is short enough to fit by its length alone, without measuring it
    if (text.length * px * WIDEST_CHAR <= room) continue;
    var font = this.fontOf(lookOf);
    var wide = measure(text, font);
    var need = wide - room;
    if (need <= 0.5) continue;
    if (d.merge) {
      // A merged range keeps its text inside it
      d.clip = true;
      continue;
    }
    d.over = true;
    var number = d.type === "n" || d.type === "d";
    var toLeft = d.align === "right" ? need : d.align === "center" || d.align === "centerContinuous" ? need / 2 : 0;
    var toRight = d.align === "left" ? need : d.align === "right" ? 0 : need / 2;
    // Text runs on rightwards out of the frozen columns into the rest, but never back into them
    var leftmost = i >= boundary ? boundary : 0;
    var gotRight = 0, gotLeft = 0, j;
    for (j = i + 1; toRight > gotRight && j < cells.length && this.free(cells[j]); j++) gotRight += cells[j].width;
    for (j = i - 1; toLeft > gotLeft && j >= leftmost && this.free(cells[j]); j--) gotLeft += cells[j].width;
    if (number && (gotLeft < toLeft || gotRight < toRight || need > room * NUMBER_RUN_ON)) {
      // Excel never runs a number on: it shows a General one to fewer places, and any other as ###
      d.text = this.shorter(d.cell, d.lookOf, room, font) || "#".repeat(Math.max(1, Math.floor(room / measure("#", font))));
      d.cut = true;
      continue;
    }
    // The gridlines the text runs over go, as in Excel, and the one it is cut off at stays
    var still = toRight, across = d.width;
    for (j = i + 1; still > 0 && j < cells.length && this.free(cells[j]); j++) {
      cells[j - 1].noRight = true;
      if (j === boundary) {
        // Carried on past the freeze line, in the first column that scrolls: from where the text
        // begins, so that it lines up with the frozen part of it until the columns scroll
        var start = d.align === "left" ? VW_CELL_PAD + (d.lookOf.indent || 0) * VW_INDENT_PX : (d.width - wide) / 2;
        cells[j].carry = { html: this.contentHtml(d, false), before: across - start, after: Math.min(still, this.freeAfter(cells, j)) };
      }
      across += cells[j].width;
      still -= cells[j].width;
    }
    still = toLeft;
    for (j = i - 1; still > 0 && j >= leftmost && this.free(cells[j]); j--) {
      cells[j].noRight = true;
      still -= cells[j].width;
    }
    gotRight = Math.min(toRight, gotRight);
    gotLeft = Math.min(toLeft, gotLeft);
    if (d.align === "left" && !gotRight) {
      // Text set to the left with no room beside it is cut off at its own edge
      d.clip = true;
      continue;
    }
    if (d.align === "center" || d.align === "centerContinuous") {
      // Centred on its own cell, so it runs on as far each way, and is cut off where either side is
      d.spill = { left: gotLeft, right: gotRight, centre: need / 2 };
    } else {
      d.spill = { left: gotLeft, right: gotRight };
    }
  }
};

/* The width of the empty cells from cells[j] on, which text carried into them can run over */
Sheet.prototype.freeAfter = function (cells, j) {
  var room = 0;
  for (var k = j; k < cells.length && this.free(cells[k]); k++) room += cells[k].width;
  return room;
};

/*
 * A number in the General format to as few places as fit [room], as Excel shows one in a column too
 * narrow for all of it, or null for a number in any other format, or one that will not fit at all.
 */
Sheet.prototype.shorter = function (cell, lookOf, room, font) {
  if (!cell || cell.t !== "n" || typeof cell.v !== "number" || lookOf.nf) return null;
  for (var places = 10; places >= 1; places--) {
    var text = generalNumber(cell.v, places);
    if (measure(text, font) <= room + 0.5) return text;
  }
  // Digits that still do not fit are written as a power of ten, as Excel writes 123456789 in a
  // narrow column as 1.23E+08, with as many as fit
  for (places = 6; places >= 1; places--) {
    text = generalNumber(cell.v, places, true);
    if (measure(text, font) <= room + 0.5) return text;
  }
  return null;
};

/* Whether text from beside a cell can run over it */
Sheet.prototype.free = function (d) {
  return !d.merge && !d.text && this.empty(d.r, d.c);
};

Sheet.prototype.cellHtml = function (d) {
  var looks = this.looks, lookOf = d.lookOf;
  var classes = looks.classes[d.look] || "";
  if (!lookOf.h && d.align !== "left") classes += d.align === "right" ? " vw-n" : " vw-m";
  if (d.edge) classes += " " + d.edge;
  if (d.noRight) classes += " vw-nr";
  if (d.noBottom) classes += " vw-nb";
  if (d.cell && d.cell.c && d.cell.c.length) classes += " vw-cm";
  var colour = d.cell && d.cell.t === "n" ? formatColour(lookOf.nf, d.cell.v) : null;
  if (colour) classes += " " + looks.inkClass(colour);
  if (d.bar) classes += " " + looks.barClass(d.bar.colour);
  var wrap = !!lookOf.wrap;
  var content = this.contentHtml(d, wrap);
  if (d.clip) classes += " vw-cut";
  if (d.carry) {
    classes += " vw-sx";
    content = '<div class="vw-sp" style="width:' + (d.carry.before + d.carry.after) + "px;margin-left:" +
      -d.carry.before + 'px;justify-content:flex-start">' + d.carry.html + "</div>";
  } else if (d.spill || (d.over && !d.cut && d.align !== "left")) {
    classes += " vw-sx";
    content = this.spillHtml(d, content);
  } else if (lookOf.rot) {
    content = '<div class="' + (lookOf.rot === 90 ? "vw-up" : lookOf.rot === -90 ? "vw-down" : lookOf.rot === 255 ? "vw-stack" : "vw-turn") + '"' +
      (lookOf.rot !== 90 && lookOf.rot !== -90 && lookOf.rot !== 255 ? ' style="transform:rotate(' + (-lookOf.rot) + 'deg)"' : "") + ">" + content + "</div>";
  } else if (wrap && d.custom) {
    // A row given its height by hand keeps it, and cuts its text off there, as Excel does
    content = '<div class="vw-clip" style="max-height:' + Math.max(1, d.height - 1) + 'px">' + content + "</div>";
  } else if (wrap && d.text.length > 200) {
    content = '<div class="vw-clip" style="max-height:' + ROW_MOST_PX + 'px">' + content + "</div>";
  }
  // So too where its text is turned or too tall for it: a row grows to what its cells hold, and a
  // cell's own overflow cannot stop it. Cut only below, so that text running on still does.
  if (d.clip && d.custom && !(wrap && !lookOf.rot && !d.carry && !d.spill)) {
    content = '<div class="vw-cap" style="max-height:' + Math.max(1, d.height - 1) + 'px">' + content + "</div>";
  }
  var attrs = classes ? ' class="' + classes.replace(/^ /, "") + '"' : "";
  if (d.bar) attrs += ' style="--vw-bar:' + d.bar.width + '%"';
  if (d.across > 1) attrs += ' colspan="' + d.across + '"';
  if (d.down > 1) attrs += ' rowspan="' + d.down + '"';
  return "<td" + attrs + ">" + content + "</td>";
};

/* A cell's text as HTML: SheetJS's rich text where it has it, and a link as a link */
Sheet.prototype.contentHtml = function (d, wrap) {
  var cell = d.cell;
  if (!cell || !d.text) return "";
  var rich = !d.cut && cell.h && cell.h.length <= CELL_MOST_CHARS * 2 && /<span|<b>|<i>|<u>|<s>/.test(cell.h);
  var html = rich ? String(cell.h) : escapeHtml(d.text);
  html = wrap ? html.replace(/\n/g, "<br>") : html.replace(/<br\s*\/?>/gi, " ").replace(/\s*\n\s*/g, " ");
  var target = cell.l && cell.l.Target;
  if (target && String(target).charAt(0) !== "#") html = '<a href="' + escapeHtml(target) + '">' + html + "</a>";
  return html;
};

/* A cell's text in a box as wide as the cells it runs over, cut off at their edges */
Sheet.prototype.spillHtml = function (d, content) {
  var s = d.spill || { left: 0, right: 0 };
  var pad = VW_CELL_PAD + (d.lookOf.indent || 0) * VW_INDENT_PX;
  var style = "width:" + Math.max(0, d.width - 1 + s.left + s.right) + "px";
  if (s.left) style += ";margin-left:" + -s.left + "px";
  var justify = d.align === "right" ? "flex-end" : d.align === "left" ? "flex-start" : "center";
  style += ";justify-content:" + justify + (justify === "center" ? ";padding:0 " + pad + "px"
    : ";padding-" + (d.align === "right" ? "right" : "left") + ":" + pad + "px");
  if (s.centre !== undefined && s.left !== s.right) {
    // Cut off on the side it cannot run on to, still centred on its own cell
    var reach = s.centre;
    var inner = "width:" + (d.width - 1 + 2 * reach) + "px;margin-left:" + (s.left - reach) + "px";
    return '<div class="vw-sp" style="' + style + ';justify-content:flex-start;padding:0">' +
      '<div class="vw-sp" style="' + inner + ';justify-content:center">' + content + "</div></div>";
  }
  return '<div class="vw-sp" style="' + style + '">' + content + "</div>";
};

/* A colgroup for a part's columns, the row numbers' first in the left part */
Sheet.prototype.colgroup = function (cols, left) {
  var html = "<colgroup>", total = 0;
  if (left && this.showHeads) {
    html += '<col style="width:' + this.headWidth + 'px">';
    total += this.headWidth;
  }
  for (var i = 0; i < cols.length; i++) {
    html += '<col style="width:' + this.width[cols[i]] + 'px">';
    total += this.width[cols[i]];
  }
  return { html: html + "</colgroup>", width: total };
};

/* The row of column letters over a part's columns */
Sheet.prototype.lettersHtml = function (cols, left) {
  var html = '<tr class="vw-letters">';
  if (left && this.showHeads) html += '<th class="vw-corner"></th>';
  for (var i = 0; i < cols.length; i++) {
    var c = cols[i], before = i > 0 ? cols[i - 1] : left ? -1 : this.lefts.length ? this.lefts[this.lefts.length - 1] : -1;
    html += '<th class="vw-ch' + (c !== before + 1 ? " vw-gap" : "") + '" data-n="' + columnName(c) + '"></th>';
  }
  return html + "</tr>";
};

/* A table for one part of a section from its rows' HTML, or null for a part with no columns */
Sheet.prototype.table = function (cols, left, rowsHtml) {
  if (!cols.length && !(left && this.showHeads)) return null;
  var group = this.colgroup(cols, left);
  var t = document.createElement("table");
  t.className = "vw-t" + (left ? " vw-tl" + (this.lefts.length ? " vw-freeze" : "") : " vw-tr");
  t.style.width = group.width + "px";
  t.innerHTML = group.html + rowsHtml;
  vwDisarmLinks(t);
  return t;
};

/* A block of a section's two tables side by side */
Sheet.prototype.blockOf = function (rows, letters, className) {
  var html = this.sectionHtml(rows, letters);
  var block = document.createElement("div");
  block.className = className;
  var left = this.table(this.lefts, true, html[0]);
  var right = this.table(this.rights, false, html[1]);
  if (left) block.appendChild(left);
  if (right) block.appendChild(right);
  block.vwLeft = left;
  block.vwRight = right;
  block.vwGrows = this.growing;
  return block;
};

/* The frozen rows, under the column letters, held at the top of the screen; null where there are neither */
Sheet.prototype.headBlock = function () {
  if (this.head !== undefined) return this.head;
  if (!this.heads.length && !this.showHeads) return (this.head = null);
  return (this.head = this.blockOf(this.heads, this.showHeads, "vw-head" + (this.heads.length ? " vw-freeze" : "")));
};

/* Piece [k], made the first time it is asked for, on the page or not: the search reads pieces off the page */
Sheet.prototype.block = function (k) {
  var piece = this.pieces[k];
  if (piece.block) return piece.block;
  var block = this.blockOf(piece.rows, false, "vw-p");
  block.vwPiece = k;
  block.style.containIntrinsicSize = this.fullWidth + "px " + this.piecePx(piece) + "px";
  piece.block = block;
  return block;
};

Sheet.prototype.piecePx = function (piece) {
  var sum = 0;
  for (var i = 0; i < piece.rows.length; i++) sum += this.height[piece.rows[i]];
  return sum;
};

/*
 * Where a column's left edge and a row's top lie in the grid, in its own px: past the row numbers
 * and the column letters, hidden ones taking no room, and past the last drawn at the sheet's own
 * width and height.
 */
Sheet.prototype.xOf = function (c) {
  if (!this.colLefts) {
    this.colLefts = [0];
    for (var i = 0; i < this.cols.length; i++) this.colLefts.push(this.colLefts[i] + this.width[this.cols[i]]);
  }
  var k = firstAtLeast(this.cols, c);
  var past = c > this.lastCol ? (c - this.lastCol - 1) * this.style.width : 0;
  return this.headWidth + this.colLefts[k] + past;
};

Sheet.prototype.yOf = function (r) {
  if (!this.rowTops) {
    this.rowTops = [0];
    for (var i = 0; i < this.rows.length; i++) this.rowTops.push(this.rowTops[i] + this.height[this.rows[i]]);
  }
  var k = firstAtLeast(this.rows, r);
  var past = r > this.lastRow ? (r - this.lastRow - 1) * this.style.height : 0;
  return (this.showHeads ? HEAD_HEIGHT : 0) + this.rowTops[k] + past;
};

/*
 * The sheet's pictures, charts and text boxes, over its cells where the workbook anchors them, or
 * null for a sheet with none. A picture is drawn, and a text box's text; a chart, which Gander does
 * not draw, is a box where it would be, with its title, so that nobody reads a sheet without
 * knowing a chart was on it. Above the cells, and under the letters and frozen rows, which it
 * scrolls under as they do.
 */
Sheet.prototype.artBlock = function () {
  if (this.art !== undefined) return this.art;
  if (!this.drawn.length) return (this.art = null);
  var layer = document.createElement("div");
  layer.className = "vw-art";
  var self = this;
  this.drawn.forEach(function (item) {
    var left, top, width, height;
    if (item.at) {
      left = self.headWidth + item.at.x;
      top = (self.showHeads ? HEAD_HEIGHT : 0) + item.at.y;
    } else {
      left = self.xOf(item.from.c) + item.from.x;
      top = self.yOf(item.from.r) + item.from.y;
    }
    if (item.to) {
      width = self.xOf(item.to.c) + item.to.x - left;
      height = self.yOf(item.to.r) + item.to.y - top;
    } else {
      width = item.size.w;
      height = item.size.h;
    }
    if (!(width >= 2 && height >= 2)) return;
    var el;
    if (item.kind === "picture") {
      el = document.createElement("img");
      el.src = URL.createObjectURL(new Blob([item.bytes], { type: item.type }));
      el.alt = item.title || "";
    } else if (item.kind === "text") {
      el = document.createElement("div");
      el.className = "vw-art-text";
      el.textContent = item.title;
    } else {
      el = document.createElement("div");
      el.className = "vw-art-missing";
      var name = document.createElement("b");
      name.textContent = item.kind === "chart" ? (item.title || "Chart") : "Picture";
      var why = document.createElement("span");
      why.textContent = item.kind === "chart" ? "A chart Gander can't draw yet" : "A picture in a format Gander can't show";
      el.appendChild(name);
      el.appendChild(why);
    }
    el.style.insetInlineStart = left + "px";
    el.style.top = top + "px";
    el.style.width = width + "px";
    el.style.height = height + "px";
    layer.appendChild(el);
  });
  return (this.art = layer.childNodes.length ? layer : null);
};

/* The column of the td at [index] among row [r]'s cells in a part, as cellsOf numbers them */
Sheet.prototype.columnAt = function (r, cols, rows, index) {
  var cells = this.cellsOf(r, cols, rows);
  return cells[index] ? cells[index][0] : -1;
};

/* The merged range a cell is in, or null */
Sheet.prototype.mergeAt = function (r, c) {
  var list = this.rowMerges[r];
  if (!list) return null;
  for (var q = 0; q < list.length; q++) if (c >= list[q].s.c && c <= list[q].e.c) return list[q];
  return null;
};

/* What a table gives a cell under [base], its own look, which wins wherever it gives anything */
VwLooks.prototype.under = function (base, table) {
  var key = base + "|" + table.key;
  this.unders = this.unders || {};
  if (this.unders[key] !== undefined) return this.unders[key];
  var own = this.list[base] || {}, look = {};
  for (var field in own) if (own.hasOwnProperty(field)) look[field] = own[field];
  ["fill", "ink"].forEach(function (field) { if (!own[field] && table[field]) look[field] = table[field]; });
  ["b", "i"].forEach(function (field) { if (!own[field] && table[field]) look[field] = true; });
  var mine = own.borders || {}, theirs = table.borders;
  look.borders = { l: mine.l || theirs.l, r: mine.r || theirs.r, t: mine.t || theirs.t, b: mine.b || theirs.b };
  return (this.unders[key] = this.add(look));
};

/* [base] with what conditional formatting gives a cell over it, as a look of its own */
VwLooks.prototype.mix = function (base, ruled) {
  var key = base + "|" + [ruled.fill, ruled.ink, ruled.b, ruled.i, ruled.u, ruled.s].join(",");
  this.mixes = this.mixes || {};
  if (this.mixes[key] !== undefined) return this.mixes[key];
  var look = {}, own = this.list[base] || {};
  for (var field in own) if (own.hasOwnProperty(field)) look[field] = own[field];
  ["fill", "ink", "b", "i", "u", "s"].forEach(function (field) {
    if (ruled[field] !== undefined && ruled[field] !== null) look[field] = ruled[field];
  });
  return (this.mixes[key] = this.add(look));
};

/*
 * A data bar of a colour, as a class: the bar fading from it, as Excel's do, as long as the cell's
 * --vw-bar says, by day and turned over at night.
 */
VwLooks.prototype.barClass = function (hex) {
  this.bars = this.bars || {};
  if (!this.bars[hex]) {
    var name = this.bars[hex] = "vw-b" + hex;
    var rule = function (from) {
      return "background-image: linear-gradient(90deg, #" + from + ", #" + vwBlend(from, "FFFFFF", 0.8) + "); " +
        "background-size: var(--vw-bar) calc(100% - 4px); background-repeat: no-repeat; background-position: 1px center";
    };
    var night = vwSheetNight(hex);
    this.rules.push("#grid td." + name + " { " + rule(hex) + " }\n@media (prefers-color-scheme: dark) { #grid td." +
      name + " { background-image: linear-gradient(90deg, #" + night + ", #" + vwBlend(night, VW_SHEET_NIGHT_GROUND, 0.8) + ") } }");
  }
  return this.bars[hex];
};

/*
 * A colour a number format gives its value, as a class: the colour by day, and turned over at night.
 * Doubled, so that it wins over the colour of the cell's font, as it does in Excel.
 */
VwLooks.prototype.inkClass = function (hex) {
  this.inks = this.inks || {};
  if (!this.inks[hex]) {
    var name = this.inks[hex] = "vw-k" + hex;
    this.rules.push("#grid td." + name + "." + name + " { color: #" + hex + " }\n" +
      "@media (prefers-color-scheme: dark) { #grid td." + name + "." + name + " { color: #" + vwSheetNight(hex) + " } }");
  }
  return this.inks[hex];
};

vwFetchDoc("buffer")
  .then(function (buf) {
    var wb = readWorkbook(new Uint8Array(buf));
    if (!wb.SheetNames.length) throw new Error("The workbook has no sheets");
    var root = document.documentElement;
    var tabs = document.getElementById("tabs");
    var sheetDiv = document.getElementById("sheet");
    var grid = document.getElementById("grid");
    var bar = document.getElementById("cellbar");
    var styles = vwWorkbookStyles(wb);
    var probe = document.createElement("div");
    probe.className = "vw-probe";
    grid.appendChild(probe);
    var sheets = wb.SheetNames.map(function (sn, i) { return new Sheet(wb, i, styles); });
    var csv = !wb.Workbook || vwExt === "csv";

    // Sheets the workbook hides have no tab, as in Excel, unless it hides every one
    var hiddenSheets = (wb.Workbook && wb.Workbook.Sheets) || [];
    var shown = sheets.filter(function (s, i) { return !(hiddenSheets[i] && hiddenSheets[i].Hidden); });
    if (!shown.length) shown = sheets;
    var opening = shown[0];
    if (styles.active !== null && sheets[styles.active] && shown.indexOf(sheets[styles.active]) >= 0) {
      opening = sheets[styles.active];
    }

    grid.style.fontFamily = styles.normal.stack;
    grid.style.fontSize = styles.normal.px + "px";
    // The colour of the workbook's own text, which a cell set in no colour of its own draws in
    if (styles.normal.ink && styles.normal.ink !== "000000") {
      styles.looks.rules.push(".vw-t td { color: #" + styles.normal.ink + " }\n" +
        "@media (prefers-color-scheme: dark) { .vw-t td { color: #" + vwSheetNight(styles.normal.ink) + " } }");
      styles.looks.flush();
    }

    /* The phone's own text size, which a sheet opens zoomed by: see ZOOM_LEAST */
    var phone = Math.min(2, Math.max(0.85, (Number(vwParams.get("type")) || 100) / 100));
    root.style.setProperty("--vw-type", phone);
    var zoom = phone;

    var current = null;
    var selected = null;

    function setZoom(z) {
      zoom = Math.min(ZOOM_MOST, Math.max(ZOOM_LEAST, z));
      grid.style.zoom = zoom;
      root.style.setProperty("--vw-z", zoom);
      held();
    }

    /* The room the bars at the foot of the screen take, which the sheet scrolls clear of */
    function bottomRoom() {
      // Heights as drawn, not rounded as offsetHeight has them, or a hairline of sheet shows between the bars
      var under = tabs.hidden ? 0 : tabs.getBoundingClientRect().height;
      bar.style.bottom = under + "px";
      var room = under + (bar.hidden ? 0 : bar.getBoundingClientRect().height);
      document.body.style.paddingBottom = room + "px";
      // A cell the arrows or Find bring into view is brought clear of the bar as it opens and closes
      root.style.scrollPaddingBottom = room + "px";
      return room;
    }

    /*
     * What the held rows and columns cover, for a search to bring what it found out from under
     * them rather than leave it there: scroll-padding is what scrollIntoView keeps clear.
     */
    function held() {
      if (!current) return;
      var top = vwTopPx || 0, left = 0;
      if (current.head) top += seen(current.head).height;
      var piece = current.pieces.length && current.pieces[0].block;
      if (piece && piece.vwLeft) left = seen(piece.vwLeft).width;
      root.style.scrollPaddingTop = top + "px";
      // On the frozen columns' side, the right for a sheet right to left
      root.style.scrollPaddingInlineStart = left + "px";
      bottomRoom();
    }

    /*
     * An element of the sheet's size on the screen, in the page's px. Measured against a block of
     * known height, since what a size inside CSS zoom reads as changed in Chromium 128: before, the
     * zoom's own px; since, the page's.
     */
    function seen(el) {
      var unit = probe.getBoundingClientRect().height / 100 || 1;
      var box = el.getBoundingClientRect();
      return { width: box.width / unit * zoom, height: box.height / unit * zoom };
    }

    /*
     * The screen in the sheet's own px at the zoom a sheet opens at, whatever it is zoomed to now, so
     * that how a sheet is laid out never hangs on when it was first shown
     */
    function room() {
      var top = vwTopPx || 0;
      return {
        width: Math.max(1, innerWidth / phone),
        height: Math.max(1, (innerHeight - top - bottomRoom()) / phone - (HEAD_HEIGHT + 2))
      };
    }

    /*
     * The rows of a section's two tables kept the same heights. Each row is as tall as the file
     * makes it, but text that wraps grows one, and then only in the table it is in. A measure of a
     * known height first, since what a row's size reads as under CSS zoom has changed between
     * versions of Chromium.
     */
    function level(block) {
      var left = block.vwLeft, right = block.vwRight;
      if (!left || !right || !block.parentNode) return;
      var unit = probe.getBoundingClientRect().height / 100;
      if (!(unit > 0)) return;
      var lr = left.rows, rr = right.rows, n = Math.min(lr.length, rr.length);
      var heights = [];
      for (var i = 0; i < n; i++) {
        var a = lr[i].getBoundingClientRect().height, b = rr[i].getBoundingClientRect().height;
        heights.push(Math.abs(a - b) > 0.5 ? Math.max(a, b) / unit : 0);
      }
      for (var j = 0; j < n; j++) {
        if (!heights[j]) continue;
        lr[j].style.height = rr[j].style.height = heights[j] + "px";
      }
      if (block.vwPiece !== undefined) {
        block.vwPx = block.getBoundingClientRect().height / unit;
        block.style.containIntrinsicSize = current.fullWidth + "px " + block.vwPx + "px";
      }
    }

    /*
     * A table that changes size, as one does when it is zoomed and its text wraps anew, has its rows
     * evened out in the next frame. Done in the observer's own call, the heights it sets would change
     * the size again before the frame is drawn, a loop Chromium cuts short and reports.
     */
    var uneven = [], evening = false;
    var levelled = window.ResizeObserver ? new ResizeObserver(function (entries) {
      for (var i = 0; i < entries.length; i++) {
        var block = entries[i].target.parentNode;
        if (block && uneven.indexOf(block) < 0) uneven.push(block);
      }
      if (evening) return;
      evening = true;
      requestAnimationFrame(function () {
        evening = false;
        var blocks = uneven;
        uneven = [];
        for (var k = 0; k < blocks.length; k++) level(blocks[k]);
      });
    }) : null;

    /*
     * A block whose rows nothing can grow keeps them as the file sizes them, the same in both its
     * tables, and is not measured: measuring has the WebView lay a block out before it is drawn,
     * at a cost a block with a million cells showed, seconds.
     */
    function watchLevel(block) {
      if (!block.vwGrows) return;
      level(block);
      if (levelled && block.vwRight && block.vwLeft) levelled.observe(block.vwRight);
    }

    /* Piece [k] of the sheet on the page, put there in place of the block standing for it */
    function place(k) {
      var sheet = current;
      var piece = sheet.pieces[k];
      var block = sheet.block(k);
      if (block.parentNode === grid) return block;
      if (piece.stand && piece.stand.parentNode === grid) {
        if (watcher) watcher.unobserve(piece.stand);
        grid.replaceChild(block, piece.stand);
        watchLevel(block);
        if (selected && selected.sheet === sheet) mark(true);
      }
      return block;
    }

    /*
     * Below Chromium 105 the app searches with Chromium's own find, which reads only what is on
     * the page (searchesInPage in WebViewFloor.kt), and an engine that old has no Highlight API,
     * which is how the page knows. There every piece goes onto the page, one at a time once the
     * first is up, so that search reaches every row, as it did when a sheet was drawn whole.
     */
    var fillAll = !(window.CSS && CSS.highlights);

    /* How many times a sheet has been shown, so a fill for one shown before stops */
    var shows = 0;

    function fill(sheet, run) {
      var pieces = sheet.pieces;
      var k = 1;
      (function next() {
        if (run !== shows) return;
        while (k < pieces.length && pieces[k].block && pieces[k].block.parentNode === grid) k++;
        if (k >= pieces.length) return;
        place(k++);
        setTimeout(next, 0);
      })();
    }

    /* A screen's height either side of the screen: a piece is drawn before it scrolls into view */
    var watcher = window.IntersectionObserver
      ? new IntersectionObserver(function (entries) {
          for (var i = 0; i < entries.length; i++) {
            if (entries[i].isIntersecting) place(entries[i].target.vwPiece);
          }
        }, { rootMargin: "100% 0px" })
      : null;

    function show(sheet) {
      if (current === sheet) return;
      clearSelection();
      current = sheet;
      shows++;
      sheet.lay(room());
      if (watcher) watcher.disconnect();
      if (levelled) levelled.disconnect();
      while (grid.lastChild && grid.lastChild !== probe) grid.removeChild(grid.lastChild);
      grid.className = sheet.style.grid ? "" : "vw-nogrid";
      grid.style.setProperty("--vw-rh", sheet.style.height + "px");
      root.dir = sheet.style.rtl ? "rtl" : "";
      var head = sheet.headBlock();
      if (head) {
        grid.appendChild(head);
        watchLevel(head);
      }
      sheet.pieces.forEach(function (piece, k) {
        if (k === 0) {
          grid.appendChild(sheet.block(0));
          watchLevel(piece.block);
          return;
        }
        // A stand-in, though the piece was drawn when the sheet was shown before or built off the page
        // for Find: put back, each would be levelled at once, the whole sheet laid out in one go. As
        // tall as the piece was last drawn, so that the page is as long as it was.
        var stand = document.createElement("div");
        stand.className = "vw-later";
        stand.style.height = (piece.block && piece.block.vwPx || sheet.piecePx(piece)) + "px";
        stand.style.width = sheet.fullWidth + "px";
        stand.vwPiece = k;
        piece.stand = stand;
        grid.appendChild(stand);
        if (watcher) watcher.observe(stand);
        else place(k);
      });
      var art = sheet.artBlock();
      if (art) grid.appendChild(art);
      if (fillAll && sheet.pieces.length > 1) fill(sheet, shows);
      var buttons = tabs.querySelectorAll("button");
      for (var i = 0; i < buttons.length; i++) {
        var on = buttons[i].vwSheet === sheet;
        buttons[i].classList.toggle("active", on);
        buttons[i].setAttribute("aria-selected", on ? "true" : "false");
        if (on && buttons[i].scrollIntoView) buttons[i].scrollIntoView({ block: "nearest", inline: "nearest" });
      }
      // The start of the sheet, which for one that runs right to left is at the right
      window.scrollTo(0, 0);
      held();
    }

    shown.forEach(function (sheet) {
      var b = document.createElement("button");
      b.textContent = sheet.name;
      b.setAttribute("role", "tab");
      b.vwSheet = sheet;
      if (sheet.style.tab) {
        b.style.setProperty("--vw-tab", "#" + sheet.style.tab);
        b.classList.add("vw-coloured");
      }
      b.onclick = function () { show(sheet); };
      tabs.appendChild(b);
    });
    // A CSV has the one sheet, named after nothing in the file
    tabs.hidden = csv && shown.length < 2;
    setZoom(zoom);
    show(opening);

    /* ---------------------------------------------------------------------------------------
     * A cell tapped, and the bar that shows it
     * ------------------------------------------------------------------------------------- */

    /* The cell a tapped element is in, as the sheet's row and column, or null */
    function cellOf(target) {
      var td = target && target.closest ? target.closest("td") : null;
      if (!td || !grid.contains(td)) return null;
      var tr = td.parentNode, table = tr.parentNode.closest ? tr.parentNode.closest("table") : null;
      var block = table && table.parentNode;
      if (!block || !current) return null;
      var r = +tr.getAttribute("data-r");
      var left = table.classList.contains("vw-tl");
      var rows = block.vwPiece !== undefined ? current.pieces[block.vwPiece].rows : current.heads;
      var index = td.cellIndex - (left && current.showHeads ? 1 : 0);
      var c = current.columnAt(r, left ? current.lefts : current.rights, rows, index);
      if (c < 0) return null;
      var merge = current.mergeAt(r, c);
      return merge ? { r: merge.top, c: merge.left, merge: merge } : { r: r, c: c, merge: null };
    }
    window.vwCellOf = function (td) {
      var at = cellOf(td);
      return at ? columnName(at.c) + (at.r + 1) : null;
    };

    /* The block on the page holding row [r], the head or a piece, with that block's rows; or null */
    function blockOf(r) {
      if (current.head && current.heads.indexOf(r) >= 0) return [current.head, current.heads];
      var i = firstAtLeast(current.body, r);
      if (current.body[i] !== r) return null;
      for (var k = 0; k < current.pieces.length; k++) {
        var p = current.pieces[k];
        if (i >= p.from && i <= p.to) return p.block && p.block.parentNode === grid ? [p.block, p.rows] : null;
      }
      return null;
    }

    /* The td drawing a cell of the sheet on the page, or null where its piece is not drawn */
    function tdOf(r, c) {
      var found = blockOf(r);
      if (!found) return null;
      var left = current.lefts.indexOf(c) >= 0;
      var table = left ? found[0].vwLeft : found[0].vwRight;
      var tr = table && table.querySelector('tr[data-r="' + r + '"]');
      if (!tr) return null;
      var cells = current.cellsOf(r, left ? current.lefts : current.rights, found[1]);
      for (var k = 0; k < cells.length; k++) {
        if (cells[k][0] === c) return tr.cells[k + (left && current.showHeads ? 1 : 0)] || null;
      }
      return null;
    }
    window.vwCellAt = function (address) {
      var a = XLSX.utils.decode_cell(address);
      return tdOf(a.r, a.c);
    };

    /* The selected cell's outline, and its letters and number lit, on the page as it is now */
    var marked = [];

    function mark(on) {
      for (var i = 0; i < marked.length; i++) marked[i].classList.remove("vw-sel", "vw-lit");
      marked = [];
      if (!on || !selected) return;
      var td = tdOf(selected.r, selected.c);
      if (td) marked.push(td);
      var lastC = selected.merge ? selected.merge.e.c : selected.c;
      var lastR = selected.merge ? selected.merge.e.r : selected.r;
      if (current.head) {
        var letters = current.head.querySelectorAll(".vw-ch");
        for (var k = 0; k < letters.length; k++) {
          var c = XLSX.utils.decode_col(letters[k].getAttribute("data-n"));
          if (c >= selected.c && c <= lastC) marked.push(letters[k]);
        }
      }
      for (var r = selected.r; r <= lastR && r <= current.lastRow; r++) {
        var th = rowHeadOf(r);
        if (th) marked.push(th);
      }
      for (var j = 0; j < marked.length; j++) marked[j].classList.add(j === 0 && td ? "vw-sel" : "vw-lit");
    }

    /* The row number beside row [r], where it is drawn */
    function rowHeadOf(r) {
      var found = blockOf(r);
      var table = found && found[0].vwLeft;
      var tr = table && table.querySelector('tr[data-r="' + r + '"]');
      return tr && tr.cells[0] && tr.cells[0].classList.contains("vw-rh") ? tr.cells[0] : null;
    }

    function select(at) {
      if (selected) mark(false);
      selected = { sheet: current, r: at.r, c: at.c, merge: at.merge };
      mark(true);
      fillBar();
      vwFind.post("cover 1");
    }

    function clearSelection() {
      if (!selected) return;
      mark(false);
      selected = null;
      bar.hidden = true;
      bottomRoom();
      vwFind.post("cover 0");
    }

    /* Back while a cell is selected puts the selection down first, as the app asks: see PortMessage.Covered */
    window.vwPageCommand = function (msg) {
      if (msg === "x") clearSelection();
    };

    var addressEl = bar.querySelector(".vw-cb-address");
    var valueEl = bar.querySelector(".vw-cb-value");
    var moreEl = bar.querySelector(".vw-cb-more");

    function line(cls, label, text) {
      var div = document.createElement("div");
      div.className = cls;
      if (label) {
        var b = document.createElement("span");
        b.className = "vw-cb-label";
        b.textContent = label;
        div.appendChild(b);
      }
      div.appendChild(document.createTextNode(text));
      return div;
    }

    /* Where a selection's value is: a merge's first cell, which may be in a hidden row or column */
    function home(at) {
      return at.merge ? { r: at.merge.s.r, c: at.merge.s.c } : at;
    }

    function fillBar() {
      var sheet = current, at = selected, first = home(at);
      var cell = sheet.cell(first.r, first.c);
      var address = columnName(first.c) + (first.r + 1);
      if (at.merge) address += ":" + columnName(at.merge.e.c) + (at.merge.e.r + 1);
      addressEl.textContent = address;
      var text = sheet.text(cell);
      valueEl.textContent = text;
      valueEl.classList.toggle("vw-cb-none", !text);
      if (!text) valueEl.textContent = "Empty";
      moreEl.textContent = "";
      if (cell && cell.f) moreEl.appendChild(line("vw-cb-formula", "", "=" + cell.f));
      else if (cell && (cell.t === "n" || cell.t === "d") && cell.v !== undefined) {
        // What the number is, where its format shows less of it, as Excel's formula bar does
        var raw = cell.t === "d" && cell.v instanceof Date ? dateValue(cell.v) : String(cell.v);
        if (raw !== text) moreEl.appendChild(line("vw-cb-raw", "", raw));
      }
      if (cell && cell.l && cell.l.Target) moreEl.appendChild(line("vw-cb-link", "Link ", String(cell.l.Target)));
      if (cell && cell.c) {
        cell.c.forEach(function (note) {
          var who = note.a && note.a !== "sheetjsghost" ? note.a + ": " : "";
          moreEl.appendChild(line("vw-cb-note", note.T ? "Comment " : "Note ", who + (note.t || "")));
        });
      }
      bar.hidden = false;
      bottomRoom();
    }

    bar.querySelector(".vw-cb-close").onclick = clearSelection;
    bar.querySelector(".vw-cb-copy").onclick = function () {
      if (!selected) return;
      var text = current.text(current.cell(home(selected).r, home(selected).c));
      var area = document.createElement("textarea");
      area.value = text;
      area.setAttribute("readonly", "");
      area.className = "vw-copy";
      document.body.appendChild(area);
      area.select();
      var ok = false;
      try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
      document.body.removeChild(area);
      if (!ok && navigator.clipboard) navigator.clipboard.writeText(text).catch(function () {});
      var button = this;
      button.classList.add("vw-done");
      setTimeout(function () { button.classList.remove("vw-done"); }, 1200);
    };

    grid.addEventListener("click", function (e) {
      // A link is for its text to show in the bar: no link in a document is followed
      var link = e.target.closest && e.target.closest("a");
      if (link) e.preventDefault();
      // The second tap of a double tap, which zoomed: see putBack
      if (Date.now() - zoomedAt < DOUBLE_TAP_GAP_MS) return;
      var at = cellOf(e.target);
      if (!at) return;
      if (selected && selected.r === at.r && selected.c === at.c) return;
      select(at);
    });

    /* Keys, for a phone with a keyboard: the arrows move the selection, Escape puts it down */
    document.addEventListener("keydown", function (e) {
      if (!selected || e.altKey || e.ctrlKey || e.metaKey) return;
      if (e.key === "Escape") { clearSelection(); return; }
      var step = { ArrowLeft: [0, -1], ArrowRight: [0, 1], ArrowUp: [-1, 0], ArrowDown: [1, 0] }[e.key];
      if (!step) return;
      if (current.style.rtl) step[1] = -step[1];
      var r = selected.r, c = selected.c;
      if (step[0]) {
        var ri = firstAtLeast(current.rows, (step[0] > 0 && selected.merge ? selected.merge.e.r : r)) + step[0];
        if (ri < 0 || ri >= current.rows.length) return;
        r = current.rows[ri];
      } else {
        var ci = firstAtLeast(current.cols, (step[1] > 0 && selected.merge ? selected.merge.e.c : c)) + step[1];
        if (ci < 0 || ci >= current.cols.length) return;
        c = current.cols[ci];
      }
      e.preventDefault();
      var merge = current.mergeAt(r, c);
      var at = merge ? { r: merge.top, c: merge.left, merge: merge } : { r: r, c: c, merge: null };
      var body = current.body, i = firstAtLeast(body, at.r);
      for (var k = 0; k < current.pieces.length; k++) {
        if (i >= current.pieces[k].from && i <= current.pieces[k].to) place(k);
      }
      select(at);
      var td = tdOf(at.r, at.c);
      if (td) td.scrollIntoView({ block: "nearest", inline: "nearest" });
    });

    /* ---------------------------------------------------------------------------------------
     * Zoom: see ZOOM_LEAST
     * ------------------------------------------------------------------------------------- */

    /* The sheet's own px under a point on the screen, at the zoom it is drawn at */
    function under(x, y) {
      var box = sheetDiv.getBoundingClientRect(), left = gridLeft(box);
      return { x: (x - left) / zoom, y: (y - box.top) / zoom, box: box };
    }

    /* Where the grid starts across the screen: a sheet right to left is set at the page's right, so a
       grid narrower than the screen, zoomed out, starts past the sheet's left edge */
    function gridLeft(box) {
      return box.left + (current && current.style.rtl ? Math.max(0, box.width - current.fullWidth * zoom) : 0);
    }

    /* The sheet laid out at [z], with the place [at] (from under()) where the finger is, at [x, y] */
    function settle(z, at, x, y) {
      sheetDiv.style.transform = "";
      sheetDiv.style.transformOrigin = "";
      sheetDiv.classList.remove("vw-moving");
      setZoom(z);
      var box = sheetDiv.getBoundingClientRect();
      window.scrollBy(gridLeft(box) + at.x * zoom - x, box.top + at.y * zoom - y);
    }

    var gesture = null;

    function distance(a, b) {
      return Math.sqrt((a.clientX - b.clientX) * (a.clientX - b.clientX) + (a.clientY - b.clientY) * (a.clientY - b.clientY));
    }

    function startPinch(e) {
      finishAnimation();
      var a = e.touches[0], b = e.touches[1];
      var x = (a.clientX + b.clientX) / 2, y = (a.clientY + b.clientY) / 2;
      var at = under(x, y);
      gesture = {
        apart: Math.max(1, distance(a, b)), from: zoom, to: zoom, at: at, x0: x, y0: y, x: x, y: y,
        sx: window.scrollX, sy: window.scrollY
      };
      sheetDiv.style.transformOrigin = (x - at.box.left) + "px " + (y - at.box.top) + "px";
      sheetDiv.classList.add("vw-moving");
    }

    function movePinch(e) {
      var a = e.touches[0], b = e.touches[1], g = gesture;
      g.x = (a.clientX + b.clientX) / 2;
      g.y = (a.clientY + b.clientY) / 2;
      g.to = Math.min(ZOOM_MOST, Math.max(ZOOM_LEAST, g.from * distance(a, b) / g.apart));
      // The WebView may pan the page with the fingers as well, which the picture must not count twice
      var tx = g.x - g.x0 + window.scrollX - g.sx, ty = g.y - g.y0 + window.scrollY - g.sy;
      sheetDiv.style.transform = "translate(" + tx + "px," + ty + "px) scale(" + g.to / g.from + ")";
    }

    function endPinch() {
      var g = gesture;
      gesture = null;
      settle(g.to, g.at, g.x, g.y);
    }

    var tap = null, lastTap = null, zoomedAt = 0;

    /*
     * A double tap is for zooming, so the cell its first tap selected goes back to what was selected
     * before. The first tap selects at once rather than waiting to see whether a second follows,
     * which would make every tap slow.
     */
    function putBack(was) {
      if (!was || was.sheet !== current) clearSelection();
      else if (!selected || selected.r !== was.r || selected.c !== was.c) select(was);
    }

    document.addEventListener("touchstart", function (e) {
      if (e.touches.length === 2) {
        tap = lastTap = null;
        startPinch(e);
      } else if (e.touches.length === 1 && !gesture && sheetDiv.contains(e.target)) {
        finishAnimation();
        tap = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() };
      } else {
        // Only taps on the sheet make a double tap: two on a tab or on Copy are two taps there
        tap = lastTap = null;
      }
    }, { passive: true });

    document.addEventListener("touchmove", function (e) {
      if (gesture && e.touches.length === 2) movePinch(e);
      else if (tap && e.touches.length === 1 &&
        Math.abs(e.touches[0].clientX - tap.x) + Math.abs(e.touches[0].clientY - tap.y) > TAP_SLOP) tap = null;
    }, { passive: true });

    function touchEnded(e) {
      if (gesture && e.touches.length < 2) {
        endPinch();
        return;
      }
      if (!tap || e.touches.length) return;
      var now = Date.now();
      if (now - tap.t > DOUBLE_TAP_GAP_MS) { tap = null; return; }
      if (lastTap && now - lastTap.t < DOUBLE_TAP_GAP_MS &&
        Math.abs(tap.x - lastTap.x) + Math.abs(tap.y - lastTap.y) < DOUBLE_TAP_SLOP) {
        putBack(lastTap.was);
        lastTap = null;
        zoomedAt = now;
        doubleTap(tap.x, tap.y);
      } else {
        // What was selected before this tap's click, which comes after its touchend
        lastTap = { x: tap.x, y: tap.y, t: now, was: selected };
      }
      tap = null;
    }
    document.addEventListener("touchend", touchEnded, { passive: true });
    document.addEventListener("touchcancel", function (e) {
      tap = lastTap = null;
      if (gesture && e.touches.length < 2) endPinch();
    }, { passive: true });

    // Only a finger's double tap zooms: a mouse's double click selects a word, as anywhere else
    var animation = null;

    function finishAnimation() {
      if (!animation) return;
      var a = animation;
      animation = null;
      cancelAnimationFrame(a.frame);
      settle(a.to, a.at, a.x, a.y);
    }

    /* Zooms to [z] about the screen's point [x, y], over [ms] */
    function zoomTo(z, x, y, ms) {
      finishAnimation();
      z = Math.min(ZOOM_MOST, Math.max(ZOOM_LEAST, z));
      var at = under(x, y);
      if (!ms) { settle(z, at, x, y); return; }
      sheetDiv.style.transformOrigin = (x - at.box.left) + "px " + (y - at.box.top) + "px";
      sheetDiv.classList.add("vw-moving");
      var from = zoom, start = null;
      animation = { to: z, at: at, x: x, y: y, frame: 0 };
      function step(t) {
        if (!animation) return;
        if (start === null) start = t;
        var f = Math.min(1, (t - start) / ms);
        // Decelerating, and by ratio, so it grows at an even rate rather than fastest at the start
        var eased = 1 - (1 - f) * (1 - f);
        sheetDiv.style.transform = "scale(" + Math.pow(z / from, eased) + ")";
        if (f >= 1) { finishAnimation(); return; }
        animation.frame = requestAnimationFrame(step);
      }
      animation.frame = requestAnimationFrame(step);
    }

    /* In from the sheet's own size or near it, and back to it from further in, as a PDF's double tap goes */
    function doubleTap(x, y) {
      zoomTo(zoom < phone * Math.SQRT2 ? phone * DOUBLE_TAP_ZOOM : phone, x, y, DOUBLE_TAP_MS);
    }

    /* Ctrl and the wheel, or a trackpad's pinch, which Chromium sends as the same */
    document.addEventListener("wheel", function (e) {
      if (!e.ctrlKey) return;
      e.preventDefault();
      zoomTo(zoom * Math.exp(-e.deltaY / 300), e.clientX, e.clientY, 0);
    }, { passive: false });

    /* For the tests, and for a device test, which can only say what the page says */
    window.vwSheetZoom = function (z, x, y) {
      if (z === undefined) return zoom;
      zoomTo(z, x === undefined ? innerWidth / 2 : x, y === undefined ? innerHeight / 2 : y, 0);
      return zoom;
    };
    window.vwSheetPlace = function (x, y) {
      var at = under(x, y);
      return [at.x, at.y];
    };

    addEventListener("resize", held);

    /*
     * Find reaches every row of every shown sheet, not only those drawn: see find.js. A sheet's
     * frozen rows are a part of their own, and each of its pieces another, read off the page until
     * it is put there.
     */
    var parts = null;

    /* Every shown sheet laid out, the first time a search asks, which reads each one's styles */
    function partsOf() {
      if (parts) return parts;
      parts = [];
      shown.forEach(function (sheet) {
        sheet.lay(room());
        sheet.firstPart = parts.length;
        if (sheet.heads.length) parts.push({ sheet: sheet, piece: -1 });
        sheet.pieces.forEach(function (piece, k) { parts.push({ sheet: sheet, piece: k }); });
      });
      return parts;
    }
    window.vwFindParts = {
      count: function () { return partsOf().length; },
      root: function (i) {
        var p = partsOf()[i];
        return p.piece < 0 ? p.sheet.headBlock() : p.sheet.block(p.piece);
      },
      show: function (i) {
        var p = partsOf()[i];
        if (p.sheet !== current) show(p.sheet);
        return p.piece < 0 ? current.headBlock() : place(p.piece);
      },
      shown: function () { partsOf(); return current.firstPart; },
      drawn: function (i) {
        var p = partsOf()[i];
        if (p.sheet !== current) return false;
        var block = p.piece < 0 ? p.sheet.head : p.sheet.pieces[p.piece].block;
        return !!block && block.parentNode === grid;
      }
    };
    vwStatusDone();
    held();
  })
  .catch(function (e) { vwError("Could not open this spreadsheet", String(e)); });
