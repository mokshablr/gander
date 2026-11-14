"""
The simple view, pdfsimple.html: a PDF on a phone whose WebView pdf.js can't run on, each page a
picture drawn by Gander's isolated renderer and served under /render/. Here the stand-in in
server.py draws the pictures with Pillow, so what is tested is the page: which pictures it asks
for and when, where it lays them out, and what it says over the channel. SimplePdf.kt and the
renderer are tested in ViewerActivityTest and on a device.
"""

import threading
import time

import pytest

A4 = (595, 842)
LANDSCAPE = (842, 595)

# The column of pages runs from the spacer above them to the one below, the first and last of
# #pages but for the note a document too long for it ends with
LAYOUT = """() => {
  const pages = document.getElementById('pages');
  const column = [...pages.children].filter(k => !k.classList.contains('vw-cut'));
  const boxes = [...pages.querySelectorAll('.pg')];
  return {
    width: pages.clientWidth,
    inner: column[column.length - 1].getBoundingClientRect().bottom
      - column[0].getBoundingClientRect().top,
    boxes: boxes.length,
    heights: boxes.map(b => b.getBoundingClientRect().height),
    drawn: boxes.filter(b => b.querySelector('img')).length,
  };
}"""


def opened(page, server, sizes, wait=True, pages=0, error=None, broken=(), delay=0.0, **params):
    """The simple view of a document whose pages are [sizes], with its first picture in place."""
    simple = server.show_simple(sizes=sizes, pages=pages, error=error, broken=broken, delay=delay)
    page.goto(server.url("pdfsimple.html", name="report.pdf", ext="pdf", **params))
    if wait:
        page.wait_for_function("() => document.querySelector('#pages .pg img')", timeout=15000)
    return simple


def layout(page):
    return page.evaluate(LAYOUT)


def drawn(page, n, night=None, timeout=15000):
    """Waits for page n, one based, to be showing its picture, of [night] if that is given."""
    want = "" if night is None else f"&night={1 if night else 0}"
    page.wait_for_function(
        """([n, want]) => { const img = document.querySelector(`img[alt="Page ${n}"]`);
           return !!img && img.getAttribute('src').endsWith(want) && img.complete; }""",
        arg=[n, want], timeout=timeout)


def top_of(page, n):
    """Where page n's picture is, from the top of the screen."""
    return page.evaluate(
        "(n) => document.querySelector(`img[alt=\"Page ${n}\"]`).getBoundingClientRect().top", n)


def frames(page, count=2):
    """Waits for [count] frames, by which a scroll has been heard and what it sets off has run."""
    page.evaluate("""(count) => new Promise((done) => {
      const next = (left) => left ? requestAnimationFrame(() => next(left - 1)) : done();
      next(count);
    })""", count)


def first_asked(server):
    """
    The first two pictures asked for, in page order. The page asks for two at once, on two
    connections, so either can reach the server first, and a test that read only the first one
    saw page 2 where the page had asked for 1.
    """
    until(lambda: len(server.pages_asked()) >= 2, "two pictures asked for")
    return sorted(server.pages_asked()[:2])


def until(check, what, timeout=15.0):
    """Waits for [check], asked of the server's side, to come true."""
    deadline = time.time() + timeout
    while not check():
        assert time.time() < deadline, f"timed out waiting for {what}"
        time.sleep(0.02)


# ---------------------------------------------------------------
# Laying out
# ---------------------------------------------------------------

def test_a_long_document_stands_at_its_full_height_with_only_the_pages_near_the_screen_laid_out(
        page, server):
    opened(page, server, [A4] * 300)
    lay = layout(page)
    pitch = lay["width"] * 842 / 595 + 8
    assert lay["inner"] == pytest.approx(300 * pitch, abs=2)
    # A page and a bit on a 1600 px screen, and five more below it kept laid out
    assert lay["boxes"] == 7
    assert all(h == pytest.approx(lay["width"] * 842 / 595, abs=1) for h in lay["heights"])


def test_pages_come_and_go_as_the_reader_scrolls_and_nothing_on_screen_moves(page, server):
    opened(page, server, [A4] * 300)
    pitch = layout(page)["width"] * 842 / 595 + 8
    page.evaluate("() => window.scrollTo(0, 100 * 1395)")
    drawn(page, 101)
    before = top_of(page, 101)
    lay = layout(page)
    assert lay["boxes"] <= 2 * 5 + 2
    # Page 1 is long gone, and its column is the same height without it
    assert page.evaluate("() => document.querySelector('img[alt=\"Page 1\"]')") is None
    assert lay["inner"] == pytest.approx(300 * pitch, abs=2)

    # Over two pages further, which takes the pages at the top of those laid out away and lays out
    # more below. The spacers stand in for them, so neither the page on screen nor where the reader
    # is moves. Both are checked, since scroll anchoring would hide a wrong spacer by moving the
    # reader to keep the page still.
    above = "() => document.getElementById('pages').firstElementChild.getBoundingClientRect().height"
    spacer, y = page.evaluate(above), page.evaluate("() => scrollY")
    page.evaluate("() => window.scrollBy(0, 3000)")
    frames(page)
    assert page.evaluate(above) > spacer + pitch, "no page went from the top of those laid out"
    assert page.evaluate("() => scrollY") == pytest.approx(y + 3000, abs=1)
    assert top_of(page, 101) == pytest.approx(before - 3000, abs=1)
    assert layout(page)["inner"] == pytest.approx(300 * pitch, abs=2)


def test_a_page_of_another_shape_takes_its_own_once_its_picture_comes(page, server):
    opened(page, server, [A4, LANDSCAPE, A4])
    drawn(page, 2)
    lay = layout(page)
    assert lay["heights"][1] == pytest.approx(lay["width"] * 595 / 842, abs=1)
    assert lay["heights"][0] == pytest.approx(lay["width"] * 842 / 595, abs=1)


def test_a_jump_gives_pages_laid_out_already_their_own_shapes(page, server, port):
    """
    A jump learns the shapes of the page it goes to and the one before it. Pages laid out before
    then, at page 1's shape, take theirs at once. Their boxes stay where they are, and a picture
    that comes in exactly the shape already known changes nothing, as a 4:3 slide drawn 980 px
    across does: in a deck after a portrait cover, such a slide stood portrait, its picture
    stretched.
    """
    slide = (720, 540)
    opened(page, server, [A4] + [slide] * 39)
    page.wait_for_function("() => document.querySelectorAll('#pages .pg img').length === 5",
                           timeout=15000)
    channel = port()
    channel.wait_for("page 1 40")
    lay = layout(page)
    portrait, wide = lay["width"] * 842 / 595, lay["width"] * 540 / 720
    # Pages 6 and 7 laid out, as tall as page 1 since neither is drawn
    assert lay["boxes"] == 7
    assert lay["heights"][5:] == [pytest.approx(portrait, abs=1)] * 2

    channel.go_to_page(7)
    channel.wait_for("page 7 40")
    for n in (6, 7):
        drawn(page, n)
        shown = page.evaluate("""(n) => {
          const img = document.querySelector(`img[alt="Page ${n}"]`);
          const box = img.parentNode.getBoundingClientRect(), at = img.getBoundingClientRect();
          return { box: box.height, ratio: at.height / at.width, own: img.naturalHeight / img.naturalWidth };
        }""", n)
        assert shown["own"] == 0.75, f"page {n}'s picture should come in its known shape"
        assert shown["box"] == pytest.approx(wide, abs=1), f"page {n}"
        assert shown["ratio"] == pytest.approx(0.75, abs=0.01), f"page {n}"


def test_a_document_of_a_million_pages_lays_out_a_few_and_says_where_it_stops(page, server, port):
    opened(page, server, [A4], pages=1_000_000)
    lay = layout(page)
    assert lay["boxes"] <= 7
    note = page.text_content(".vw-cut")
    shown = int(note.split("first ")[1].split(" ")[0])
    assert note == f"The simple view shows the first {shown} of this PDF's 1000000 pages."
    assert lay["inner"] == pytest.approx(shown * (lay["width"] * 842 / 595 + 8), abs=2)
    assert lay["inner"] < 25e6
    channel = port()
    channel.wait_for(rf"page 1 {shown}")


def test_on_a_phones_screen_the_column_holds_what_chromium_lays_out_there(
        browser_type, browser_type_launch_args, server):
    """
    Chromium lays out in device pixels on a phone's screen, so a column holds that many fewer CSS
    px there: cut at 25 million CSS px, a million pages stood under 13 million tall at a pixel
    ratio of 2.625, and the pages past that were never laid out. The browser is launched with
    the ratio, as a phone's screen has it: one a context emulates leaves Chromium laying out in
    CSS px, where 33.5 million fit at any ratio.
    """
    launch_args = dict(browser_type_launch_args)
    launch_args["args"] = [*launch_args.get("args", []), "--force-device-scale-factor=2.625"]
    browser = browser_type.launch(**launch_args)
    context = browser.new_context(
        viewport={"width": 411, "height": 891}, device_scale_factor=2.625,
        is_mobile=True, has_touch=True,
    )
    try:
        page = context.new_page()
        opened(page, server, [A4], pages=1_000_000)
        lay = layout(page)
        assert lay["width"] == 980, "laid out as a WebView lays out a page with no viewport meta"
        shown = int(page.text_content(".vw-cut").split("first ")[1].split(" ")[0])
        assert lay["inner"] == pytest.approx(shown * (lay["width"] * 842 / 595 + 8), abs=2)
        # The last of them there to be scrolled to and drawn
        page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
        drawn(page, shown)
    finally:
        context.close()
        browser.close()


def test_a_short_document_sits_in_the_middle_of_the_screen(page, server):
    opened(page, server, [LANDSCAPE])
    box = page.evaluate("() => document.querySelector('.pg').getBoundingClientRect().toJSON()")
    below = page.evaluate("() => innerHeight") - box["bottom"]
    assert box["top"] == pytest.approx(below - 8, abs=2)


# ---------------------------------------------------------------
# Asking for pictures
# ---------------------------------------------------------------

def test_pictures_are_asked_for_nearest_the_middle_first_and_two_at_a_time(page, server):
    simple = opened(page, server, [A4] * 300, delay=0.15)
    page.wait_for_function("() => document.querySelectorAll('#pages .pg img').length === 5",
                           timeout=15000)
    asked = [n for n, _ in server.pages_asked()]
    # Two go at once, so either of a pair may reach the server first
    assert sorted(asked[:2]) == [1, 2]
    assert sorted(asked[2:4]) == [3, 4]
    assert asked[4:] == [5]
    assert simple.most == 2


def test_a_picture_is_asked_for_at_the_screens_own_resolution(page, server):
    opened(page, server, [A4] * 3)
    asked = [r["query"] for r in server.state.requests if r["path"] == "/render/page"]
    dpr = page.evaluate("() => devicePixelRatio")
    width = page.evaluate("() => document.getElementById('pages').clientWidth")
    assert int(asked[0]["w"]) == round(min(width * dpr * 1.1, width * min(dpr, 2)))


def test_a_page_the_renderer_cannot_draw_says_so(page, server):
    opened(page, server, [A4] * 3, wait=False, broken={1})
    page.wait_for_selector(".pg .pg-note", timeout=15000)
    assert page.text_content(".pg .pg-note") == "This page could not be drawn"
    # Asked for once, not again and again
    page.wait_for_timeout(300)
    assert [n for n, _ in server.pages_asked()].count(1) == 1


# ---------------------------------------------------------------
# The channel
# ---------------------------------------------------------------

def test_the_readout_names_the_page_on_screen_and_the_last_at_the_end(page, server, port):
    """Pages shorter than half the screen, whose last is never under the middle of it."""
    opened(page, server, [LANDSCAPE] * 30)
    channel = port()
    channel.wait_for("page 1 30")
    page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    channel.wait_for("page 30 30")
    drawn(page, 30)


def test_go_to_page_puts_the_page_at_the_top_and_draws_it_first(page, server, port):
    opened(page, server, [A4] * 300)
    channel = port()
    channel.wait_for("page 1 300")
    page.wait_for_function("() => document.querySelectorAll('#pages .pg img').length === 5",
                           timeout=15000)
    asked = len(server.pages_asked())
    channel.go_to_page(120)
    channel.wait_for("page 120 300")
    drawn(page, 120)
    assert top_of(page, 120) == pytest.approx(0, abs=2)
    assert server.pages_asked()[asked][0] == 120


def test_go_to_a_short_page_puts_it_in_the_middle_so_the_readout_names_it(page, server, port):
    opened(page, server, [LANDSCAPE] * 40)
    channel = port()
    channel.wait_for("page 1 40")
    channel.go_to_page(20)
    channel.wait_for("page 20 40")
    drawn(page, 20)
    middle = page.evaluate("() => innerHeight / 2")
    box = page.evaluate(
        "() => document.querySelector('img[alt=\"Page 20\"]').getBoundingClientRect().toJSON()")
    assert box["top"] < middle < box["bottom"]


def test_a_jump_shapes_the_page_and_the_one_before_it_first(page, server, port):
    """
    Until it is drawn a page stands at page 1's shape, which in a deck after a portrait cover is
    not its own. Placed at that, a landscape page went to the top as if it were tall and the
    readout named the one after it: pdf.mjs's place() says so, and so does this page's.
    """
    opened(page, server, [A4] + [LANDSCAPE] * 39)
    channel = port()
    channel.wait_for("page 1 40")
    channel.go_to_page(20)
    channel.wait_for("page 20 40")
    drawn(page, 20)
    page.wait_for_timeout(300)
    middle = page.evaluate("() => innerHeight / 2")
    box = page.evaluate(
        "() => document.querySelector('img[alt=\"Page 20\"]').getBoundingClientRect().toJSON()")
    assert box["top"] < middle < box["bottom"]
    assert channel.pages()[-1] == "page 20 40"


# Counts the answers to /render/sizes the page has read, once it has done all it does with one:
# a timer set as the answer is read runs after every promise that answer settles
SIZES_READ = """
(() => {
  window.__vwSizesRead = 0;
  const json = Response.prototype.json;
  Response.prototype.json = function () {
    const sizes = this.url.indexOf('/render/sizes') >= 0;
    return json.call(this).then((said) => {
      if (sizes) setTimeout(() => { window.__vwSizesRead++; }, 0);
      return said;
    });
  };
})();
"""


def test_of_two_jumps_close_together_the_second_is_where_the_reader_stays(page, server, port):
    """
    A jump waits for the sizes of its page and the one before, unless both are known. The first
    of two could get its sizes after the second had landed, and take the reader back.
    """
    page.add_init_script(SIZES_READ)
    simple = opened(page, server, [A4] * 300)
    page.wait_for_function("() => document.querySelectorAll('#pages .pg img').length === 5",
                           timeout=15000)
    channel = port()
    channel.wait_for("page 1 300")
    simple.gate = threading.Event()
    channel.go_to_page(120)
    until(lambda: any(r["path"] == "/render/sizes" for r in server.state.requests),
          "page 120's sizes to be asked for")
    # Pages 2 and 3 are drawn, so their sizes are known and this one lands at once
    channel.go_to_page(3)
    channel.wait_for("page 3 300")
    y = page.evaluate("() => scrollY")
    simple.gate.set()
    page.wait_for_function("() => window.__vwSizesRead === 1", timeout=15000)
    assert page.evaluate("() => scrollY") == y
    assert top_of(page, 3) == pytest.approx(0, abs=2)


# Chromium below 69, which has no scroll-padding: app.css's scroll-padding-top goes, and
# CSS.supports says there is none, as it would there
NO_SCROLL_PADDING = """
(() => {
  const supports = CSS.supports.bind(CSS);
  CSS.supports = function (what) {
    return String(what).indexOf('scroll-padding') < 0 && supports.apply(null, arguments);
  };
  new MutationObserver((_, watch) => {
    if (!document.head) return;
    watch.disconnect();
    const style = document.createElement('style');
    style.textContent = 'html { scroll-padding-top: 0 !important; }';
    document.head.appendChild(style);
  }).observe(document, { childList: true, subtree: true });
})();
"""


@pytest.mark.parametrize("padding", [True, False], ids=["scroll-padding", "none"])
def test_a_jump_or_a_reopen_puts_the_page_below_the_title_bar(page, server, port, padding):
    """
    app.css's scroll-padding-top keeps a page that scrollIntoView brings up out from under the
    title bar, from Chromium 69. This page is for engines as old as 66, so without it
    pdfsimple.js puts the page in the same place itself.
    """
    if not padding:
        page.add_init_script(NO_SCROLL_PADDING)
    opened(page, server, [A4] * 30 + [LANDSCAPE] * 10, top=56, resume=12)
    assert page.evaluate("() => getComputedStyle(document.documentElement).scrollPaddingTop") \
        == ("56px" if padding else "0px")
    drawn(page, 12)
    assert top_of(page, 12) == pytest.approx(56, abs=2)

    channel = port()
    channel.go_to_page(25)
    channel.wait_for("page 25 40")
    drawn(page, 25)
    assert top_of(page, 25) == pytest.approx(56, abs=2)
    # Shorter than half the screen, a page goes in the middle of what the bar leaves of it
    channel.go_to_page(35)
    channel.wait_for("page 35 40")
    drawn(page, 35)
    box = page.evaluate(
        "() => document.querySelector('img[alt=\"Page 35\"]').getBoundingClientRect().toJSON()")
    assert (box["top"] + box["bottom"]) / 2 == pytest.approx(56 + (1600 - 56) / 2, abs=2)


def test_a_page_out_of_range_is_not_gone_to(page, server, port):
    opened(page, server, [A4] * 10)
    channel = port()
    channel.wait_for("page 1 10")
    before = layout(page)
    channel.go_to_page(11)
    channel.go_to_page(0)
    channel.send("gx")
    page.wait_for_timeout(300)
    assert channel.pages() == ["page 1 10"]
    # Nothing laid out moved, and a page that is there is still gone to
    assert layout(page)["inner"] == pytest.approx(before["inner"], abs=1)
    assert layout(page)["boxes"] == before["boxes"]
    channel.go_to_page(5)
    channel.wait_for("page 5 10")


def test_a_reopened_document_opens_at_its_page_and_draws_that_first(page, server, port):
    opened(page, server, [A4] * 300, resume=40)
    assert first_asked(server) == [(40, False), (41, False)]
    drawn(page, 40)
    assert top_of(page, 40) == pytest.approx(0, abs=2)
    channel = port()
    channel.wait_for("page 40 300")


def test_a_resume_past_the_end_opens_at_the_top(page, server):
    opened(page, server, [A4] * 10, resume=11)
    assert first_asked(server) == [(1, False), (2, False)]


def test_night_mode_asks_for_every_page_turned_over_and_back(page, server, port):
    opened(page, server, [A4] * 10, night=1)
    assert page.evaluate("() => document.documentElement.classList.contains('vw-night')")
    page.wait_for_function("() => document.querySelectorAll('#pages .pg img').length === 5",
                           timeout=15000)
    assert all(night for _, night in server.pages_asked())
    # Each page drawn shows one picture or the other while it is turned, never none: looked at
    # after every change to the pages, since the new pictures come one at a time
    watched = page.evaluate("""() => {
      const boxes = [...document.querySelectorAll('#pages .pg')].filter(b => b.querySelector('img'));
      window.__vwBlank = [];
      new MutationObserver(() => boxes.forEach((b, k) => {
        if (b.querySelectorAll('img').length !== 1) window.__vwBlank.push(k + 1);
      })).observe(document.getElementById('pages'), { childList: true, subtree: true });
      return boxes.length;
    }""")
    assert watched == 5
    channel = port()
    channel.night_mode(False)
    for n in range(1, watched + 1):
        drawn(page, n, night=False)
    assert not page.evaluate("() => document.documentElement.classList.contains('vw-night')")
    assert page.evaluate("() => window.__vwBlank") == []


def test_the_title_bar_has_room_at_the_top(page, server):
    opened(page, server, [A4] * 10, top=56)
    first = page.evaluate("() => document.querySelector('.pg').getBoundingClientRect().top + scrollY")
    assert first == pytest.approx(56 + 8, abs=1)


# ---------------------------------------------------------------
# What it says
# ---------------------------------------------------------------

@pytest.mark.parametrize("word, title", [
    ("password", "This PDF needs a password"),
    ("not-pdf", "This file is not a PDF that can be read"),
    ("damaged", "This PDF could not be read"),
    ("unreadable", "This PDF could not be read"),
    ("gone", "Could not render this PDF"),
])
def test_a_document_that_wont_open_is_said_why(page, server, word, title):
    opened(page, server, [], wait=False, error=word)
    page.wait_for_selector(".vw-error", timeout=15000)
    assert page.text_content(".vw-error-title") == title
    assert server.pages_asked() == []


def test_the_card_goes_once_the_first_page_is_drawn(page, server):
    opened(page, server, [A4] * 3)
    page.wait_for_function(
        "() => getComputedStyle(document.getElementById('vw-status')).display === 'none'",
        timeout=5000)


def test_its_ground_is_pdf_htmls_in_both_schemes_and_black_at_night(page, server):
    for scheme in ("light", "dark"):
        page.emulate_media(color_scheme=scheme)
        opened(page, server, [A4] * 3)
        assert page.evaluate("() => getComputedStyle(document.body).backgroundColor") == "rgb(72, 68, 61)"
    opened(page, server, [A4] * 3, night=1)
    assert page.evaluate("() => getComputedStyle(document.body).backgroundColor") == "rgb(0, 0, 0)"


def test_the_app_zooms_its_double_tap(page, server):
    opened(page, server, [A4] * 3)
    assert page.evaluate("() => getComputedStyle(document.documentElement).touchAction") == "manipulation"
