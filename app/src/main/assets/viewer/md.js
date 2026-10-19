/* Nothing is read below marked's floor. The card is already up, and the library in the
   head either did not parse, below Chromium 80, or would fail on its first document,
   below 92: issue #31, where this said "marked is not defined" instead. See
   vwWebViewTooOld in app.js. */
if (!vwWebViewTooOld("Markdown files")) {
  vwFetchDoc("text")
    .then(function (txt) {
      var html = marked.parse(txt, { gfm: true, breaks: false });
      var content = document.getElementById("content");
      content.innerHTML = DOMPurify.sanitize(html);
      // Isolate code before auto direction scans the document.
      content.querySelectorAll("pre, code").forEach(function (el) {
        if (!el.closest("[dir]")) el.dir = "ltr";
      });
      content.dir = "auto";
      vwStatusDone();
    })
    .catch(function (e) { vwError("Could not render this Markdown file", String(e)); });
}
