/* Nothing is read below marked's floor. The card is already up, and the library in the
   head either did not parse, below Chromium 84, or would fail on its first document,
   below 93: issue #31, where this said "marked is not defined" instead. See
   vwWebViewTooOld in app.js. */
if (!vwWebViewTooOld("Markdown files")) {
  vwFetchDoc("text")
    .then(function (txt) {
      marked.use(vwMath.extension);
      var html = marked.parse(txt, { gfm: true, breaks: false });
      var content = document.getElementById("content");
      content.innerHTML = DOMPurify.sanitize(html);
      pointPicturesBeside(content);
      // Code reads left to right in a document of any language, unless the code
      // itself says otherwise.
      content.querySelectorAll("pre, code").forEach(function (el) {
        if (!el.hasAttribute("dir") && !el.parentElement.closest("pre[dir], code[dir]")) el.dir = "ltr";
      });
      return vwMath.draw(content).then(function () {
        content.dir = vwMostlyRightToLeft(proseText(content)) ? "rtl" : "ltr";
        vwStatusDone();
      });
    })
    .catch(function (e) { vwError("Could not render this Markdown file", String(e)); });
}

/* A picture named by a path from the file's own folder is asked of DocumentServer, which finds it
   when the file was opened from a folder added in Gander. See PicturesBeside.kt. */
function pointPicturesBeside(root) {
  root.querySelectorAll("img[src]").forEach(function (img) {
    var src = img.getAttribute("src");
    // GitHub shows one of a light and a dark logo, by these endings; md.html hides the other
    var only = /#gh-(dark|light)-mode-only$/.exec(src);
    if (only) img.setAttribute("data-vw-only", only[1]);
    if (besideTheFile(src)) img.setAttribute("src", besideUrl(src));
  });
  // Such as the dark version of a logo. A srcset lists "address size" pairs, split at a comma
  // that ends an address.
  root.querySelectorAll("img[srcset], source[srcset]").forEach(function (el) {
    el.setAttribute("srcset", el.getAttribute("srcset").replace(/(^|,)(\s*)(\S*[^\s,])/g,
      function (all, comma, space, src) {
        return comma + space + (besideTheFile(src) ? besideUrl(src) : src);
      }));
  });
}

/* Not a URL, nor a path from the top of a website, nor a link within the page */
function besideTheFile(src) {
  return !/^\s*([a-z][a-z\d+.-]*:|[\/\\#]|$)/i.test(src);
}

function besideUrl(src) {
  return "/beside?path=" + encodeURIComponent(src);
}

/* The text that decides the document's direction, up to VW_DIRECTION_SAMPLE characters of it.
   Code is left out, since it is mostly Latin whatever language surrounds it, and so is anything
   the author gave its own dir, as that part keeps its direction anyway. */
function proseText(root) {
  var walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), node, parts = [], length = 0;
  while (length < VW_DIRECTION_SAMPLE && (node = walk.nextNode())) {
    if (!node.parentElement.closest("[dir]")) {
      parts.push(node.data);
      length += node.data.length;
    }
  }
  return parts.join("").slice(0, VW_DIRECTION_SAMPLE);
}
