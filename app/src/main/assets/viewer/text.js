var content = document.getElementById("content");
// shortcut: direction is file-wide; use format-aware rendering if timestamps or ASCII art must stay LTR.
if (["", "txt", "log", "nfo"].indexOf(vwExt) >= 0) content.dir = "auto";
var more = document.getElementById("more");
var moreNote = document.getElementById("moreNote");
var moreBtn = document.getElementById("moreBtn");
var doc = null;
var shown = 0;
var firstPage = true;

/* Append rather than reassign textContent: the whole document would otherwise
   be reserialised on every page. */
function showPage() {
  moreBtn.disabled = true;
  return doc.next().then(function (page) {
    var text = page.text;

    /* A .json file is more often than not written with no spacing at all, and
       arrives as one unbroken line. Lay it out, but only when it opens, and
       only up to the size worth drawing: a file that fits VW_JSON_MAX arrived
       whole, while a later page can be small enough to parse on its own and
       would leave the reader a document half laid out and half not. Anything
       that does not parse is shown as it is. */
    if (firstPage && text.length <= VW_JSON_MAX && vwExt === "json") {
      var laid = vwFormatJson(text);
      if (laid !== null) text = laid;
    }
    firstPage = false;

    content.appendChild(document.createTextNode(text));
    shown += page.bytes;
    if (page.done) {
      more.hidden = true;
    } else {
      moreNote.textContent = "Showing the first " + vwFormatSize(shown) + " of this file.";
      more.hidden = false;
      moreBtn.disabled = false;
    }
  });
}

moreBtn.addEventListener("click", function () {
  showPage().catch(function (e) { vwError("Could not read the rest of this file", String(e)); });
});

vwOpenText()
  .then(function (d) {
    doc = d;
    return showPage();
  })
  .then(vwStatusDone)
  .catch(function (e) { vwError("Could not read this file", String(e)); });
