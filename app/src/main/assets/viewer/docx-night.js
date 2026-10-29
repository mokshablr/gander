"use strict";

/*
 * Night mode for Word, issue #47.
 *
 * The page's own colours are turned over with the matrix pdf.html uses for a PDF, so ink goes
 * light and a blue heading stays blue. Not with a CSS filter on the page: that would dull every
 * photograph on it, since a picture inside a filtered page cannot be left out of the filter.
 * The night colours go in a stylesheet for the screen alone, so printing keeps the document's
 * own, and switching off is taking a class away.
 */
var vwNightOn = vwParams.get("night") === "1";
var vwNightReady = false;
if (vwNightOn) document.documentElement.classList.add("vw-night");

var vwColourProbe = null;

/* A colour as night has it, or null for anything that is not a colour of its own */
function vwNightColour(value) {
  if (!value || /var\(|currentcolor|inherit|initial|unset|revert/i.test(value)) return null;
  if (!vwColourProbe) vwColourProbe = document.createElement("canvas").getContext("2d");
  /* A canvas reads any CSS colour and says it back as #rrggbb or rgba(); two sentinels tell
     a colour it took from one it refused */
  vwColourProbe.fillStyle = "#000001";
  vwColourProbe.fillStyle = value;
  var said = vwColourProbe.fillStyle;
  vwColourProbe.fillStyle = "#000002";
  vwColourProbe.fillStyle = value;
  if (said !== vwColourProbe.fillStyle) return null;

  var rgba;
  if (said.charAt(0) === "#") {
    rgba = [parseInt(said.slice(1, 3), 16), parseInt(said.slice(3, 5), 16), parseInt(said.slice(5, 7), 16), 1];
  } else {
    rgba = said.replace(/[^\d.,]/g, "").split(",").map(Number);
  }
  var out = VW_NIGHT_MATRIX.map(function (row) {
    var v = row[0] * rgba[0] / 255 + row[1] * rgba[1] / 255 + row[2] * rgba[2] / 255 + row[3];
    return Math.round(Math.min(1, Math.max(0, v)) * 255);
  });
  return "rgba(" + out.join(", ") + ", " + (rgba.length > 3 ? rgba[3] : 1) + ")";
}

/* Each colour docx-preview can set, and the short name its inline night value goes under */
var VW_NIGHT_PROPS = {
  "color": "c",
  "background-color": "b",
  "border-top-color": "bt",
  "border-right-color": "br",
  "border-bottom-color": "bb",
  "border-left-color": "bl",
  "text-decoration-color": "d",
  "column-rule-color": "r",
  "fill": "f",
  "stroke": "s"
};
var VW_NIGHT_STYLED = /color|border|column-rule|fill|stroke|decoration|background/i;

/* Top-level commas only, so a selector with a comma inside :not() stays whole */
function vwSplitSelectors(text) {
  var out = [], depth = 0, from = 0;
  for (var i = 0; i < text.length; i++) {
    var c = text.charAt(i);
    if (c === "(" || c === "[") depth++;
    else if (c === ")" || c === "]") depth--;
    else if (c === "," && !depth) { out.push(text.slice(from, i)); from = i + 1; }
  }
  out.push(text.slice(from));
  return out.map(function (s) { return s.trim(); }).filter(Boolean);
}

/*
 * docx-preview's stylesheets, as rules that win under html.vw-night. Its theme colours are
 * custom properties on .docx, turned over here too, so a colour given only by the theme
 * follows along.
 */
function vwNightRules(container) {
  var out = [];
  container.querySelectorAll("style").forEach(function (el) {
    var rules;
    try { rules = el.sheet && el.sheet.cssRules; } catch (e) { return; }
    for (var i = 0; rules && i < rules.length; i++) {
      var rule = rules[i];
      if (!rule.style || !rule.selectorText) continue;
      var decls = [];
      for (var k = 0; k < rule.style.length; k++) {
        var name = rule.style[k];
        var custom = /^--docx-.*-color$/.test(name);
        if (!custom && !VW_NIGHT_PROPS[name]) continue;
        var night = vwNightColour(rule.style.getPropertyValue(name).trim());
        if (night) decls.push(name + ": " + night + (custom ? "" : " !important"));
      }
      if (!decls.length) continue;
      var selectors = vwSplitSelectors(rule.selectorText).map(function (s) { return "html.vw-night " + s; });
      out.push(selectors.join(", ") + " { " + decls.join("; ") + " }");
    }
  });
  return out;
}

/*
 * A colour written on an element goes in a custom property beside it, which a rule in
 * docx.html uses at night. Custom properties do nothing on their own, so by day and on paper
 * the element is as it was.
 */
function vwNightInline(container) {
  container.querySelectorAll("[style]").forEach(function (el) {
    if (!VW_NIGHT_STYLED.test(el.getAttribute("style"))) return;
    var marks = [];
    for (var name in VW_NIGHT_PROPS) {
      var value = el.style.getPropertyValue(name).trim();
      /* A theme colour is already turned over at night through its variable; it is marked
         anyway, so it still wins over a style's colour as it does by day */
      var night = /^var\(--docx-[\w-]+-color\)$/.test(value) ? value : vwNightColour(value);
      if (!night) continue;
      el.style.setProperty("--vw-n-" + VW_NIGHT_PROPS[name], night);
      marks.push(VW_NIGHT_PROPS[name]);
    }
    if (marks.length) el.setAttribute("data-vw-n", marks.join(" "));
  });
  /* Shapes docx-preview draws as SVG carry their colours as attributes */
  container.querySelectorAll("svg [fill], svg [stroke]").forEach(function (el) {
    var marks = (el.getAttribute("data-vw-n") || "").split(" ").filter(Boolean);
    ["fill", "stroke"].forEach(function (name) {
      var night = vwNightColour(el.getAttribute(name));
      if (!night) return;
      el.style.setProperty("--vw-n-" + VW_NIGHT_PROPS[name], night);
      marks.push(VW_NIGHT_PROPS[name]);
    });
    if (marks.length) el.setAttribute("data-vw-n", marks.join(" "));
  });
}

/*
 * Whether a picture is paper, which turns over with the page, or a photograph, which stays as
 * it was: pdf.mjs's looksLikePaper, with its thresholds, measured over white because that is
 * what shows through a picture's transparent parts.
 */
var VW_PROBE_WIDTH = 64;
var VW_PROBE_SATURATION = 0.15;
var VW_PROBE_PAPER = 0.25;

function vwLooksLikePaper(img) {
  var w = Math.max(1, Math.min(VW_PROBE_WIDTH, img.naturalWidth));
  var h = Math.max(1, Math.round(w * img.naturalHeight / img.naturalWidth));
  var canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  var ctx = canvas.getContext("2d", { willReadFrequently: true });
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, w, h);
  ctx.drawImage(img, 0, 0, w, h);
  var d = ctx.getImageData(0, 0, w, h).data, pale = 0, sat = 0, n = d.length / 4;
  for (var o = 0; o < d.length; o += 4) {
    var mx = Math.max(d[o], d[o + 1], d[o + 2]), mn = Math.min(d[o], d[o + 1], d[o + 2]);
    if (mn > 204) pale++;
    if (mx > 0) sat += (mx - mn) / mx;
  }
  return sat / n < VW_PROBE_SATURATION && pale / n > VW_PROBE_PAPER;
}

function vwNightPictures(container) {
  container.querySelectorAll("img").forEach(function (img) {
    function judge() {
      if (!img.naturalWidth) return;
      try {
        if (vwLooksLikePaper(img)) img.setAttribute("data-vw-paper", "");
      } catch (e) {
        /* A picture that cannot be read back is left as it is */
      }
    }
    if (img.complete) judge(); else img.addEventListener("load", judge, { once: true });
  });
}

/* Once, the first time night is wanted after the document's last drawing */
function vwPrepareNight() {
  var container = document.getElementById("container");
  if (vwNightReady || !vwPages.settled || !container) return;
  vwNightReady = true;
  var style = document.createElement("style");
  style.id = "vw-night-rules";
  style.textContent = "@media screen { " + vwNightRules(container).join("\n") + " }";
  document.head.appendChild(style);
  vwNightInline(container);
  vwNightPictures(container);
  document.documentElement.classList.add("vw-night-ready");
}

function vwSetNight(on) {
  vwNightOn = on;
  if (on) vwPrepareNight();
  document.documentElement.classList.toggle("vw-night", on);
}
