"use strict";

/*
 * Symbol, Wingdings and its kin are not on a phone, and are not Unicode: a document names the
 * font, so on a phone their characters come out as boxes or as the letters they are stored as.
 * Each table gives the Unicode character a phone can draw in their place, the first two for
 * the prose reader's text and all of them for the slides' bullets.
 */

/*
 * The characters that turn up in real documents, which is bullets, arrows, ticks and the Greek
 * and mathematics of Symbol. Anything not here in a Wingdings face becomes a plain bullet,
 * since that is what it nearly always was; in Symbol it is left as it came.
 */
var VW_WINGDINGS = {
  0x21: "\u270F", 0x22: "\u2702", 0x23: "\u2701", 0x28: "\u260E", 0x2A: "\u2709",
  0x36: "\u231B", 0x3F: "\u270D", 0x41: "\u270C", 0x43: "\uD83D\uDC4D", 0x44: "\uD83D\uDC4E",
  0x45: "\u261C", 0x46: "\u261E", 0x47: "\u261D", 0x48: "\u261F", 0x4A: "\u263A",
  0x4C: "\u2639", 0x4E: "\u2620", 0x51: "\u2708", 0x52: "\u263C", 0x54: "\u2744",
  0x58: "\u2720", 0x59: "\u2721", 0x5A: "\u262A", 0x5B: "\u262F", 0x5E: "\u2648",
  0x6C: "\u25CF", 0x6D: "\u274D", 0x6E: "\u25A0", 0x6F: "\u25A1", 0x70: "\u2751",
  0x71: "\u2751", 0x72: "\u2752", 0x73: "\u2B27", 0x74: "\u29EB", 0x75: "\u25C6",
  0x76: "\u2756", 0x77: "\u2B25", 0x78: "\u2327", 0x79: "\u2353", 0x7A: "\u2318",
  0x7B: "\u2740", 0x7C: "\u273F", 0x7D: "\u275D", 0x7E: "\u275E",
  0x9E: "\u00B7", 0x9F: "\u2022", 0xA0: "\u25AA", 0xA1: "\u25CB", 0xA2: "\u2B55",
  0xA4: "\u25C9", 0xA5: "\u25CE", 0xA7: "\u25AA", 0xA8: "\u25FB", 0xAA: "\u2726",
  0xAB: "\u2605", 0xAC: "\u2736", 0xAD: "\u2734", 0xAE: "\u2739", 0xAF: "\u2735",
  0xB1: "\u2316", 0xB2: "\u27E1", 0xB3: "\u2311", 0xB5: "\u272A", 0xB6: "\u2730",
  0xD5: "\u232B", 0xD6: "\u2326", 0xD8: "\u27A2", 0xDC: "\u27B2",
  0xDF: "\u2190", 0xE0: "\u2192", 0xE1: "\u2191", 0xE2: "\u2193", 0xE3: "\u2196",
  0xE4: "\u2197", 0xE5: "\u2199", 0xE6: "\u2198", 0xE7: "\u2190", 0xE8: "\u2794",
  0xE9: "\u2191", 0xEA: "\u2193", 0xEF: "\u21E6", 0xF0: "\u21E8", 0xF1: "\u21E7",
  0xF2: "\u21E9", 0xF3: "\u2B04", 0xF4: "\u21F3", 0xF8: "\u25AD", 0xFB: "\u2718",
  0xFC: "\u2714", 0xFD: "\u2612", 0xFE: "\u2611"
};

var VW_SYMBOL = {
  0x22: "\u2200", 0x24: "\u2203", 0x27: "\u220D", 0x2A: "\u2217", 0x2D: "\u2212",
  0x40: "\u2245", 0x41: "\u0391", 0x42: "\u0392", 0x43: "\u03A7", 0x44: "\u0394",
  0x45: "\u0395", 0x46: "\u03A6", 0x47: "\u0393", 0x48: "\u0397", 0x49: "\u0399",
  0x4A: "\u03D1", 0x4B: "\u039A", 0x4C: "\u039B", 0x4D: "\u039C", 0x4E: "\u039D",
  0x4F: "\u039F", 0x50: "\u03A0", 0x51: "\u0398", 0x52: "\u03A1", 0x53: "\u03A3",
  0x54: "\u03A4", 0x55: "\u03A5", 0x56: "\u03C2", 0x57: "\u03A9", 0x58: "\u039E",
  0x59: "\u03A8", 0x5A: "\u0396", 0x5C: "\u2234", 0x5E: "\u22A5", 0x60: "\uF8E5",
  0x61: "\u03B1", 0x62: "\u03B2", 0x63: "\u03C7", 0x64: "\u03B4", 0x65: "\u03B5",
  0x66: "\u03C6", 0x67: "\u03B3", 0x68: "\u03B7", 0x69: "\u03B9", 0x6A: "\u03D5",
  0x6B: "\u03BA", 0x6C: "\u03BB", 0x6D: "\u03BC", 0x6E: "\u03BD", 0x6F: "\u03BF",
  0x70: "\u03C0", 0x71: "\u03B8", 0x72: "\u03C1", 0x73: "\u03C3", 0x74: "\u03C4",
  0x75: "\u03C5", 0x76: "\u03D6", 0x77: "\u03C9", 0x78: "\u03BE", 0x79: "\u03C8",
  0x7A: "\u03B6", 0x7E: "\u223C",
  0xA1: "\u03D2", 0xA2: "\u2032", 0xA3: "\u2264", 0xA4: "\u2044", 0xA5: "\u221E",
  0xA6: "\u0192", 0xA7: "\u2663", 0xA8: "\u2666", 0xA9: "\u2665", 0xAA: "\u2660",
  0xAB: "\u2194", 0xAC: "\u2190", 0xAD: "\u2191", 0xAE: "\u2192", 0xAF: "\u2193",
  0xB0: "\u00B0", 0xB1: "\u00B1", 0xB2: "\u2033", 0xB3: "\u2265", 0xB4: "\u00D7",
  0xB5: "\u221D", 0xB6: "\u2202", 0xB7: "\u2022", 0xB8: "\u00F7", 0xB9: "\u2260",
  0xBA: "\u2261", 0xBB: "\u2248", 0xBC: "\u2026", 0xBF: "\u21B5",
  0xC0: "\u2135", 0xC1: "\u2111", 0xC2: "\u211C", 0xC3: "\u2118", 0xC4: "\u2297",
  0xC5: "\u2295", 0xC6: "\u2205", 0xC7: "\u2229", 0xC8: "\u222A", 0xC9: "\u2283",
  0xCA: "\u2287", 0xCB: "\u2284", 0xCC: "\u2282", 0xCD: "\u2286", 0xCE: "\u2208",
  0xCF: "\u2209", 0xD0: "\u2220", 0xD1: "\u2207", 0xD2: "\u00AE", 0xD3: "\u00A9",
  0xD4: "\u2122", 0xD5: "\u220F", 0xD6: "\u221A", 0xD7: "\u22C5", 0xD8: "\u00AC",
  0xD9: "\u2227", 0xDA: "\u2228", 0xDB: "\u21D4", 0xDC: "\u21D0", 0xDD: "\u21D1",
  0xDE: "\u21D2", 0xDF: "\u21D3", 0xE0: "\u25CA", 0xE1: "\u2329", 0xE5: "\u2211",
  0xF1: "\u232A", 0xF2: "\u222B"
};

/*
 * One character for each code from 0x20, sixteen to a row. Each is PPTXjs's own
 * (lib/pptx/dingbat.js) where Android 9 and 16 both have it in a plain font, or else the
 * nearest shape or arrow they have, since they lack most of these fonts' newer Unicode. A
 * plain bullet stands where nothing comes close, which is most of Webdings' pictures.
 */
var VW_WINGDINGS_2 =
  " ✎✎✎✎✄✂•••••••••" + // 0x20
  "••••••••••••••☜☞" + // 0x30
  "☜☞☜☞☜☞•☟•☟•☟•☟•✗" + // 0x40
  "✓☒☑☒☒⊗⊗⊘⦸&&&&‽‽‽" + // 0x50
  "‽❧❧❧❧❧❧❧❧⓪①②③④⑤⑥" + // 0x60
  "⑦⑧⑨⑩⓿❶❷❸❹❺❻❼❽❾❿•" + // 0x70
  "☉○☽☾⸿✝✝•••••••••" + // 0x80
  "•••❖■⋅•⦁●○⭘⭘⭘⊙⦿▪" + // 0x90
  "▪■■□□□□▣▣▣▣⬩⬩⬩⬥◇" + // 0xA0
  "◈◈◈◈⬪⬪⬪⬧◊◊◖◗◓◒■◆" + // 0xB0
  "⬟⬟⬣⬢⬢⬢++✚✚✚✚✚✕✕✕" + // 0xC0
  "✖✖✖✖✱✱✱✱✱✱✱✱✱✱✱✱" + // 0xD0
  "✳✳✳✳✳▲▲✦✦★★✶✶✷✷✹" + // 0xE0
  "✹▲✦✯✶✹✦✦※⁂";        // 0xF0

var VW_WINGDINGS_3 =
  " ←→↑↓↖↗↙↘⇤⇥⤒⤓⇱⇲⇞" + // 0x20
  "⇟↔↕⇠⇢⇡⇣↯↲↳↰↱⬑⬏⬐⬎" + // 0x30
  "↵↳↲↳⇆⇵↹↨⇇⇉⇈⇊↶↺↺↺" + // 0x40
  "↻↺⎋⌤⌃⌥␣⍽⇪⇧⇦⇨⇦⇨⇦⇨" + // 0x50
  "⇦⇨⇦⇨⇦⇨←→↑↓↖↗↙↘↔↕" + // 0x60
  "▲▼△▽◀▶◁▷◣◢◤◥◀▶▲•" + // 0x70
  "▼▲▼◀▶◀➤▲▼←→↑↓←→↑" + // 0x80
  "↓⬅➡⬆⬇⬅➡⬆⬇←→↑↓←→↑" + // 0x90
  "↓⬅➡⬆⬇▪▪←→⬅➡⬅▪▪▪▪" + // 0xA0
  "➡⬅➡⬅➡⬅➡⬆⬇⬅➡⬆⬇⬅➡⬆" + // 0xB0
  "⬇⬅➡⬆⬇⬅➡⬆⬇⤶⤷↰↱⬑⤴⬐" + // 0xC0
  "⤵←→↑↓↖↗↙↘⬅➡⬆⬇⬉⬈⬋" + // 0xD0
  "⬊⬅➡⬆⬇⬉⬈⬋⬊◂▸▴▾◃▹▵" + // 0xE0
  "▿";                 // 0xF0

var VW_WEBDINGS =
  " •••••••••••••••" + // 0x20
  "•••◀▶▲▼◀▶⏮⏭•■●••" + // 0x30
  "••••••••••••••••" + // 0x40
  "••••••••••••••••" + // 0x50
  "•✔•□•••■••••✦•⬤•" + // 0x60
  "••✕•••••⦸⊖••⏐•••" + // 0x70
  "•••••••⛷••••••••" + // 0x80
  "••••••••✯•••••••" + // 0x90
  "••••••••••••••••" + // 0xA0
  "••••••••••••••••" + // 0xB0
  "••••••••••••••••" + // 0xC0
  "••••••••••••••••" + // 0xD0
  "••••••••Ⓟ•△•••••" + // 0xE0
  "••••••••••••••••";  // 0xF0

var VW_DINGBATS = { "wingdings 2": VW_WINGDINGS_2, "wingdings 3": VW_WINGDINGS_3, "webdings": VW_WEBDINGS };

/* Whether a font is one of these, which a phone does not have. */
function vwIsSymbolFont(face) {
  return /^(wingdings( [23])?|webdings|symbol)$/i.test(String(face || "").trim());
}

/*
 * The character a bullet set in [face] as [ch] stands for, which a phone can draw. The code can
 * come as the font's own, or in the private use area, where Word and others put it; a
 * character already in Unicode is left as it is.
 */
function vwSymbolBullet(face, ch) {
  var c = String(ch || "").codePointAt(0);
  if (c === undefined) return ch;
  var code = c >= 0xF020 && c <= 0xF0FF ? c - 0xF000 : c;
  var font = vwIsSymbolFont(face) ? String(face).trim().toLowerCase() : "";
  var out = String.fromCodePoint(c);
  if (code >= 0x20 && code <= 0xFF) {
    if (font === "wingdings") out = code === 0x20 ? " " : VW_WINGDINGS[code] || "\u2022";
    else if (font === "symbol") out = VW_SYMBOL[code] || String.fromCharCode(code);
    else if (font) out = VW_DINGBATS[font].charAt(code - 0x20) || "\u2022";
  }
  // A phone draws nothing for a control character or one in the private use area.
  return /[\0-\x1F\x7F-\x9F\uE000-\uF8FF]/.test(out) ? "\u2022" : out;
}
