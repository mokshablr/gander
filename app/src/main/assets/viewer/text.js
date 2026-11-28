var content = document.getElementById("content");
/* Prose reads the way most of its letters do, decided once, from the first page that has any,
   so showing more never turns the file round. Code, data, subtitles and playlists stay left to
   right, which their layout depends on. */
var findsDirection = ["", "txt", "log", "nfo"].indexOf(vwExt) >= 0;
// An engine below Chromium 64 can't tell scripts apart, so there the first letter decides.
if (findsDirection && vwMostlyRightToLeft("a") === null) {
  content.dir = "auto";
  findsDirection = false;
}
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

    if (findsDirection) {
      // A sample tells as well as the whole page, which can run to megabytes
      var rtl = vwMostlyRightToLeft(text.slice(0, VW_DIRECTION_SAMPLE));
      if (rtl !== null) {
        content.dir = rtl ? "rtl" : "ltr";
        findsDirection = false;
      }
    }

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
  showPage().catch(function (e) { vwError("Could not read the rest of this file", String(e), true); });
});

vwOpenText()
  .then(function (d) {
    doc = d;
    return showPage();
  })
  .then(vwStatusDone)
  .catch(function (e) { vwError("Could not read this file", String(e)); });
