"""
The card a viewer shows while a document opens.

It waits before it appears, so a document that opens quickly never shows it; how long, and
why that long, is beside .vw-wait in app.css. Anything a script puts in the card afterwards
is never held back: an error, the password prompt, and the line shown once a password has
been entered.
"""

import pytest

from helpers import wait_for_pdf

# Every viewer whose markup opens with the card.
OPENING = [
    ("pdf.html", "six-pages.pdf"),
    ("docx.html", "report.docx"),
    ("pptx.html", "deck.pptx"),
    ("xlsx.html", "budget.xlsx"),
    ("md.html", "notes.md"),
    ("text.html", "plain.txt"),
    ("model.html", "bracket.stl"),
    ("epub.html", "harbour.epub"),
]

CARD = (
    "() => { const e = document.getElementById('vw-status');"
    "if (!e) return null; const s = getComputedStyle(e);"
    "return { display: s.display, visibility: s.visibility }; }"
)
SHOWING = (
    "() => { const e = document.getElementById('vw-status');"
    "if (!e) return false; const s = getComputedStyle(e);"
    "return s.display !== 'none' && s.visibility === 'visible'; }"
)


def showing(page):
    return page.evaluate(SHOWING)


def stretch_the_wait(page):
    """
    Holds the card back for longer than any test runs, so "not shown yet" is a fact about
    the page rather than a race against the machine. Appended to app.css as it is served,
    where it overrides the duration by coming later.
    """
    def serve(route):
        response = route.fetch()
        route.fulfill(
            response=response,
            body=response.text() + "\n.vw-wait { animation-duration: 600s; }\n",
        )
    page.route("**/assets/viewer/app.css", serve)


def hold_the_document(page):
    """Leaves every request for the document unanswered until release() lets it go."""
    held = []
    page.route("**/doc/**", lambda route: held.append(route))
    return held


def release(page, held):
    for _ in range(200):
        if held:
            break
        page.wait_for_timeout(50)
    assert held, "the page never asked for its document"
    for route in held:
        route.continue_()


@pytest.mark.parametrize("html, fixture", OPENING)
def test_the_card_a_viewer_opens_with_waits_before_it_appears(viewer, page, html, fixture):
    stretch_the_wait(page)
    hold_the_document(page)
    viewer(html, fixture)
    card = page.evaluate(CARD)
    assert card is not None, "the page no longer opens with a card"
    assert card["display"] != "none"
    assert card["visibility"] == "hidden"


def test_a_slow_document_shows_the_card_until_it_arrives(viewer, page):
    held = hold_the_document(page)
    viewer("pdf.html", "six-pages.pdf")
    page.wait_for_function(SHOWING, timeout=10000)
    release(page, held)
    wait_for_pdf(page, pages=6)


def test_an_error_does_not_wait(viewer, page):
    stretch_the_wait(page)
    viewer("pdf.html", "not-a-pdf.pdf")
    page.wait_for_selector(".vw-error", state="attached", timeout=20000)
    assert showing(page)


def test_the_password_prompt_does_not_wait(viewer, page):
    stretch_the_wait(page)
    viewer("pdf.html", "encrypted.pdf")
    page.wait_for_selector("#vw-pw", state="attached", timeout=20000)
    assert showing(page)


def test_a_line_put_up_after_a_tap_does_not_wait(viewer, page):
    """vwStatus is what puts "Opening document…" up once a password has been entered."""
    stretch_the_wait(page)
    hold_the_document(page)
    viewer("text.html", "plain.txt")
    page.wait_for_function("() => typeof vwStatus === 'function'", timeout=15000)
    assert not showing(page)
    page.evaluate("() => vwStatus('Opening document…')")
    assert showing(page)


# ---------------------------------------------------------------------------
# A document large enough to take a while to read, arriving inside the hold
# ---------------------------------------------------------------------------

def padded(made, fixture_path, name, source):
    """A copy of a zipped fixture carrying 1.5 MB its reader never looks at."""
    import io
    import random
    import zipfile
    out = io.BytesIO()
    with zipfile.ZipFile(fixture_path(source)) as src, zipfile.ZipFile(out, "w") as dst:
        for item in src.infolist():
            dst.writestr(item, src.read(item.filename))
        dst.writestr(zipfile.ZipInfo("padding.bin"), random.Random(1).randbytes(1_500_000))
    return made(name, out.getvalue())


def watch_the_read(page):
    """
    Notes whether the card was on screen at the moment the document is handed to the page,
    which reads it there and then in one piece on its main thread. Nothing drawn can reach the
    screen until that is done, so a card not on screen then is not seen for as long as it takes.

    On screen means a frame began with it showing, since that frame is the one that draws it.
    Showing, in its style, is not enough: a card made visible just before the reading starts is
    never drawn at all.
    """
    hook = """
;(function () {
  function showing() {
    var e = document.getElementById('vw-status'), s = e && getComputedStyle(e);
    return !!s && s.display !== 'none' && s.visibility === 'visible';
  }
  var framed = false;
  (function frame() {
    if (showing()) framed = true;
    if (!('cardWhileReading' in window)) requestAnimationFrame(frame);
  })();
  function note() {
    if ('cardWhileReading' in window) return;
    window.cardWhileReading = showing() && framed;
  }
  var fetchDoc = vwFetchDoc;
  vwFetchDoc = function (kind) {
    return fetchDoc(kind).then(function (doc) { note(); return doc; });
  };
  var openText = vwOpenText;
  vwOpenText = function () {
    return openText().then(function (doc) {
      var next = doc.next;
      doc.next = function () { return next().then(function (p) { note(); return p; }); };
      return doc;
    });
  };
  // PPTXjs fetches the deck itself and reads it as soon as FileReaderJS hands it over
  if (window.FileReaderJS) {
    var setupBlob = FileReaderJS.setupBlob;
    FileReaderJS.setupBlob = function (blob, opts) {
      var load = opts.on.load;
      opts.on.load = function () { note(); return load.apply(this, arguments); };
      return setupBlob.call(this, blob, opts);
    };
  }
})();
"""

    def serve(route):
        response = route.fetch()
        route.fulfill(response=response, body=response.text() + hook)
    page.route("**/assets/viewer/app.js", serve)


@pytest.mark.parametrize(
    "html", ["xlsx.html", "docx.html", "pptx.html", "md.html", "text.html", "prose.html"])
def test_a_document_that_takes_a_while_to_read_shows_the_card_while_it_is_read(
        viewer, page, made, fixture_path, html):
    """
    A document of a megabyte and more is fetched in well under the 700 ms hold and then read in
    one piece on the main thread, which on a phone takes seconds. The hold is a visibility
    animation, which only the main thread can end, so it ran out unseen and the reader looked
    at a blank page until the document appeared, where before the hold they saw the card.
    """
    document = {
        "xlsx.html": lambda: padded(made, fixture_path, "big.xlsx", "budget.xlsx"),
        "docx.html": lambda: padded(made, fixture_path, "big.docx", "report.docx"),
        "pptx.html": lambda: padded(made, fixture_path, "big.pptx", "deck.pptx"),
        "md.html": lambda: made("big.md", "# Notes\n\n" + "A line of notes.\n" * 100_000),
        "text.html": lambda: made("big.txt", "A line of text.\n" * 100_000),
        "prose.html": lambda: made("big.rtf", "{\\rtf1\\ansi " + "A line of text.\\par\n" * 80_000 + "}"),
    }[html]()
    watch_the_read(page)
    viewer(html, document)
    page.wait_for_function("() => 'cardWhileReading' in window", timeout=20000)
    assert page.evaluate("() => window.cardWhileReading") is True


@pytest.mark.parametrize("html, fixture", OPENING[1:])
def test_a_small_document_is_still_read_inside_the_hold(viewer, page, html, fixture):
    """And one that reads in a moment still never shows it."""
    if html == "model.html":
        pytest.skip("the model is streamed rather than fetched whole; test_model.py times its card")
    watch_the_read(page)
    viewer(html, fixture)
    page.wait_for_function("() => 'cardWhileReading' in window", timeout=20000)
    assert page.evaluate("() => window.cardWhileReading") is False


def test_a_deck_of_many_slides_shows_the_card_while_it_is_read(viewer, page, made, fixture_path):
    """
    Slides cost as much to read as bytes do, so a deck of many slides and little else gets the
    card too, though it is far under a megabyte. The extra slides are parts that pptx.js
    counts and PPTXjs never draws, since [Content_Types].xml does not name them.
    """
    import io
    import zipfile
    out = io.BytesIO()
    with zipfile.ZipFile(fixture_path("deck.pptx")) as src, zipfile.ZipFile(out, "w") as dst:
        for item in src.infolist():
            dst.writestr(item, src.read(item.filename))
        slide = src.read("ppt/slides/slide1.xml")
        for n in range(101, 121):
            dst.writestr(f"ppt/slides/slide{n}.xml", slide)
    deck = made("many.pptx", out.getvalue())
    assert deck.stat().st_size < 200_000
    watch_the_read(page)
    viewer("pptx.html", deck)
    page.wait_for_function("() => 'cardWhileReading' in window", timeout=20000)
    assert page.evaluate("() => window.cardWhileReading") is True


def test_pptxjs_own_loading_bar_is_not_shown(viewer, page):
    """It stood at 1% until the slides appeared (#85), as PPTXjs reads a deck in one piece."""
    hold_the_document(page)
    viewer("pptx.html", "deck.pptx")
    page.wait_for_selector(".slides-loadnig-msg", state="attached", timeout=15000)
    assert page.evaluate(
        "() => getComputedStyle(document.querySelector('.slides-loadnig-msg')).display") == "none"
