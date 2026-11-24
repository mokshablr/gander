/*
 * Formulas in Markdown, written as GitHub, Obsidian and ChatGPT write them: $$...$$ and \[...\]
 * for a formula on its own line, $...$ and \(...\) within one, and a ```math block. marked only
 * marks each formula with its TeX as plain text, and KaTeX draws them after DOMPurify has run,
 * loaded only for a file that has one.
 */
var vwMath = (function () {
  /* A formula on its own lines, which no blank line can be inside of */
  var BLOCK = /^ {0,3}(?:\$\$((?:[^\n]|\n(?![ \t]*\n))+?)\$\$|\\\[((?:[^\n]|\n(?![ \t]*\n))+?)\\\])[ \t]*(?:\n+|$)/;
  var DISPLAY = /^\$\$((?:\\[\s\S]|[^\\$])+?)\$\$/;
  var PAREN = /^\\\(((?:\\[\s\S]|[^\\\n])+?)\\\)/;
  /*
   * Pandoc's rule, so that prices stay prices: no space just inside either $, and no digit just
   * after the closing one. "$5 and $10" is text.
   */
  var DOLLAR = /^\$(?![\s$])((?:\\[\s\S]|[^\\$\n])+?)\$(?!\d)/;
  /*
   * How far ahead a closing mark is looked for. Without a limit, a file of openers that never
   * close takes time that grows with the square of its length.
   */
  var LONGEST = 8000;

  /* The start of src, if a closing mark is in it */
  function closed(src, opener, closer) {
    var head = src.slice(0, LONGEST);
    return head.indexOf(closer, opener) < 0 ? null : head;
  }

  function escapeHtml(s) {
    return s.replace(/[&<>"]/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
    });
  }

  function mark(tex, display, block) {
    var tag = block ? "div" : "span";
    return "<" + tag + ' class="vw-math' + (display ? " vw-math-display" : "") + '">' +
      escapeHtml(tex.trim()) + "</" + tag + ">" + (block ? "\n" : "");
  }

  var extension = {
    extensions: [{
      name: "vwMathBlock",
      level: "block",
      tokenizer: function (src) {
        var open = /^ {0,3}(\$\$|\\\[)/.exec(src);
        var head = open && closed(src, open[0].length, open[1] === "$$" ? "$$" : "\\]");
        var m = head && BLOCK.exec(head);
        if (m) return { type: "vwMathBlock", raw: m[0], tex: m[1] || m[2] };
      },
      renderer: function (token) { return mark(token.tex, true, true); }
    }, {
      name: "vwMathInline",
      level: "inline",
      // Past LONGEST it answers a place where nothing starts, which only splits the text there
      start: function (src) {
        var i = src.slice(0, LONGEST).search(/\$|\\\(/);
        return i >= 0 ? i : src.length > LONGEST ? LONGEST : undefined;
      },
      tokenizer: function (src) {
        var head = src.slice(0, 2) === "$$" ? closed(src, 2, "$$") : null;
        var m = head && DISPLAY.exec(head);
        if (m) return { type: "vwMathInline", raw: m[0], tex: m[1], display: true };
        head = src.slice(0, 2) === "\\(" ? closed(src, 2, "\\)") : null;
        m = head && PAREN.exec(head);
        if (m) return { type: "vwMathInline", raw: m[0], tex: m[1], display: false };
        m = DOLLAR.exec(src);
        if (m && !/\s$/.test(m[1])) return { type: "vwMathInline", raw: m[0], tex: m[1], display: false };
      },
      renderer: function (token) { return mark(token.tex, token.display, false); }
    }],
    renderer: {
      code: function (token) {
        return (token.lang || "").trim() === "math" ? mark(token.text, true, true) : false;
      }
    }
  };

  function load() {
    return new Promise(function (resolve, reject) {
      var left = 2;
      function one() { if (--left === 0) resolve(); }
      var css = document.createElement("link");
      css.rel = "stylesheet";
      css.href = "lib/katex/katex.min.css";
      var js = document.createElement("script");
      js.src = "lib/katex/katex.min.js";
      css.onload = js.onload = one;
      css.onerror = js.onerror = reject;
      document.head.appendChild(css);
      document.head.appendChild(js);
    });
  }

  /*
   * Draws every formula under root. One KaTeX cannot read shows its TeX in red, and if KaTeX
   * itself fails to load, the TeX stays as text, as it read before formulas were drawn.
   */
  function draw(root) {
    var found = root.querySelectorAll(".vw-math");
    // Formulas read left to right in a document of any language, and stay out of the sample
    // that decides its direction.
    found.forEach(function (el) { el.dir = "ltr"; });
    if (!found.length) return Promise.resolve();
    return load().then(function () {
      found.forEach(function (el) {
        katex.render(el.textContent, el, {
          displayMode: el.classList.contains("vw-math-display"),
          throwOnError: false,
          strict: "ignore",
          // A file could otherwise ask for a rule or space thousands of screens wide
          maxSize: 20,
          maxExpand: 1000
        });
      });
    }, function () {});
  }

  return { extension: extension, draw: draw };
})();
