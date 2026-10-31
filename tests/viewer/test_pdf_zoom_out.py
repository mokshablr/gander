"""
Issue #52: zooming out past a page's width.

The WebView will not zoom out below its content's width, so at the fitted width a pinch in
went nowhere. pdf.html now shrinks its pages itself, into one centred column down to a
quarter of the fitted width, and draws each page at the size it is shown. The WebView's own
pinch still takes over past the fitted width, as before.

Headless Chromium on Linux drops a synthesised touch gesture without an error (see pan() in
helpers.py), so the pinches and taps here are dispatched touch events: the same events the
page's listeners read on a phone, constructed by hand. What they cannot show is the WebView
pinching at the same time, which only a phone can; the handover is argued in pdf.mjs and
checked on a device by DoubleTapDeviceTest.
"""

import pytest

from helpers import (
    PAGES, SETTLED, highlight_count, set_page_scale, tile_count, wait_for_band,
    wait_for_page, wait_for_pdf, wait_for_text_layer,
)

# The layout width pdf.html is laid out at, which the harness fixes in conftest.py.
PAGE_WIDTH = 980
# SHRINK_LEAST in pdf.mjs.
FLOOR = 0.25
# DRAW_PAGES and KEEP_PAGES in pdf.mjs.
DRAW_PAGES = 3
KEEP_PAGES = 5

TOUCH_ACTION = "() => getComputedStyle(document.documentElement).touchAction"
SHRINK = "() => vwPdfShrink()"
PAGE_WIDTHS = f"() => {PAGES}.map(pg => pg.getBoundingClientRect().width)"
PAGE_LEFTS = f"() => {PAGES}.map(pg => pg.getBoundingClientRect().left)"
FIRST_TOP = "() => document.querySelector('#pages .pg').getBoundingClientRect().top"

# Every bitmap held, as the sum of width times height over the page canvases of the slots
# that hold one. The tiles are counted too, since they are canvases under #pages as well.
BITMAP_PIXELS = f"""() => {PAGES}.reduce((sum, pg) => {{
  const s = pg._slot;
  if (!s || (s.state !== 'drawn' && s.state !== 'drawing')) return sum;
  return sum + [...pg.querySelectorAll('canvas')].reduce((t, c) => t + c.width * c.height, 0);
}}, 0)"""

# The slots holding a bitmap or about to: everything the bands have asked for.
ASKED_FOR = f"() => {PAGES}.filter(pg => pg._slot && pg._slot.state !== 'blank').length"

# The pages in the band that are finished and the fraction they were drawn for.
DRAWN_AT = f"() => {PAGES}.filter({SETTLED}).map(pg => pg._slot.shrink)"


def pinch(page, x, y, from_apart, to_apart, steps=8):
    """
    Two fingers [from_apart] px apart about ([x], [y]), moving to [to_apart] apart and
    lifting. Dispatched, not synthesised: see the note at the top of this file.
    """
    page.evaluate(
        """([x, y, fromApart, toApart, steps]) => {
          const target = document.elementFromPoint(x, y) || document.body;
          const make = (type, apart) => {
            const touches = [-1, 1].map((side, i) => new Touch({
              identifier: i + 1, target,
              clientX: x + side * apart / 2, clientY: y,
              pageX: x + side * apart / 2 + scrollX, pageY: y + scrollY,
            }));
            const held = type === 'touchend' ? [] : touches;
            return new TouchEvent(type, {
              touches: held, targetTouches: held, changedTouches: touches,
              bubbles: true, cancelable: true,
            });
          };
          target.dispatchEvent(make('touchstart', fromApart));
          for (let i = 1; i <= steps; i++) {
            target.dispatchEvent(make('touchmove', fromApart + (toApart - fromApart) * i / steps));
          }
          target.dispatchEvent(make('touchend', toApart));
        }""",
        [x, y, from_apart, to_apart, steps],
    )
    return page


def tap(page, x, y, times=1):
    """One finger down and up at ([x], [y]), [times] over, as fast as a finger could."""
    page.evaluate(
        """([x, y, times]) => {
          const target = document.elementFromPoint(x, y) || document.body;
          const touch = new Touch({ identifier: 1, target, clientX: x, clientY: y,
                                    pageX: x + scrollX, pageY: y + scrollY });
          for (let i = 0; i < times; i++) {
            target.dispatchEvent(new TouchEvent('touchstart', {
              touches: [touch], targetTouches: [touch], changedTouches: [touch],
              bubbles: true, cancelable: true }));
            target.dispatchEvent(new TouchEvent('touchend', {
              touches: [], targetTouches: [], changedTouches: [touch],
              bubbles: true, cancelable: true }));
          }
        }""",
        [x, y, times],
    )
    return page


def shrink(page):
    return page.evaluate(SHRINK)


def shrink_to(page, s, y=None):
    """Lays the pages out at [s] of the fitted width at once, as a pinch that settled there."""
    page.evaluate("([s, y]) => vwPdfShrink(s, y === null ? undefined : y)", [s, y])
    return page


def wait_for_shrink(page, s, timeout=5000):
    page.wait_for_function("(s) => Math.abs(vwPdfShrink() - s) < 0.001", arg=s, timeout=timeout)
    return page


def page_widths(page):
    return page.evaluate(PAGE_WIDTHS)


def under(page, y):
    """How far down the column, in the fitted page's px, the screen's [y] falls."""
    return page.evaluate("(y) => (y - document.querySelector('#pages .pg')"
                         ".getBoundingClientRect().top) / vwPdfShrink()", y)


# ---------------------------------------------------------------------------
# The pinch, and whose it is
# ---------------------------------------------------------------------------

def test_a_pinch_in_at_the_fitted_width_shrinks_the_pages_into_one_centred_column(viewer, page):
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    assert page_widths(page)[0] == pytest.approx(PAGE_WIDTH)

    pinch(page, 490, 800, from_apart=400, to_apart=200)

    assert shrink(page) == pytest.approx(0.5)
    widths = page_widths(page)
    assert widths[0] == pytest.approx(PAGE_WIDTH / 2, abs=0.5)
    assert len(set(round(w) for w in widths)) == 1, f"the pages are not all one width: {widths}"
    lefts = page.evaluate(PAGE_LEFTS)
    assert len(set(round(x) for x in lefts)) == 1, f"the pages are not in one column: {lefts}"
    assert lefts[0] == pytest.approx((PAGE_WIDTH - widths[0]) / 2, abs=0.5), "the column is off centre"


def test_the_pages_stop_at_a_quarter_of_the_fitted_width(viewer, page):
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)

    pinch(page, 490, 800, from_apart=400, to_apart=10)

    assert shrink(page) == pytest.approx(FLOOR)
    assert page_widths(page)[0] == pytest.approx(PAGE_WIDTH * FLOOR, abs=0.5)


def test_a_pinch_out_grows_the_pages_back_to_the_fitted_width_and_no_further(viewer, page):
    """Past the fitted width the WebView zooms, on the next pinch: see the note in pdf.mjs."""
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    shrink_to(page, 0.5)

    pinch(page, 490, 800, from_apart=200, to_apart=900)

    assert shrink(page) == 1
    assert page_widths(page)[0] == pytest.approx(PAGE_WIDTH)


def test_the_pinch_is_the_pages_only_while_the_webview_is_at_its_floor(viewer, page):
    """
    Zoomed in, the fingers are magnifying the screen and the WebView has the pinch; and at
    the fitted width a pinch out is its too. The page reads its own pinch off the same
    touches, so it has to know when to leave them alone.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)

    pinch(page, 490, 800, from_apart=200, to_apart=600)
    assert shrink(page) == 1, "a pinch out at the fitted width is the WebView's"

    assert set_page_scale(page, 2) > 1.5
    pinch(page, 300, 500, from_apart=400, to_apart=100)
    assert shrink(page) == 1, "a pinch in while zoomed in is the WebView's"
    set_page_scale(page, 1)

    pinch(page, 490, 800, from_apart=400, to_apart=100)
    assert shrink(page) == FLOOR, "back at the floor, a pinch in is the page's again"


def test_the_webviews_pinch_is_turned_off_only_while_the_pages_are_shrunk(viewer, page):
    """
    touch-action: manipulation leaves the WebView its pinch and takes Chromium's double tap
    away, which the app's double tap needs; pan-x pan-y takes the pinch away as well, so a
    pinch out from a shrunk column grows the pages and does not zoom the WebView over them.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    assert page.evaluate(TOUCH_ACTION) == "manipulation"

    shrink_to(page, 0.5)
    assert page.evaluate(TOUCH_ACTION) == "pan-x pan-y"

    shrink_to(page, 1)
    assert page.evaluate(TOUCH_ACTION) == "manipulation"


def test_the_place_under_the_fingers_stays_under_them(viewer, page):
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    page.evaluate("() => window.scrollTo(0, 5000)")
    before = under(page, 800)

    pinch(page, 490, 800, from_apart=400, to_apart=160)

    assert shrink(page) == pytest.approx(0.4)
    assert under(page, 800) == pytest.approx(before, abs=1)


# ---------------------------------------------------------------------------
# The double tap
# ---------------------------------------------------------------------------

def test_a_double_tap_while_shrunk_brings_the_fitted_width_back_about_the_place_tapped(viewer, page):
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    page.evaluate("() => window.scrollTo(0, 5000)")
    shrink_to(page, 0.4, y=800)
    before = under(page, 900)

    tap(page, 490, 900, times=2)

    wait_for_shrink(page, 1)
    assert page_widths(page)[0] == pytest.approx(PAGE_WIDTH)
    assert under(page, 900) == pytest.approx(before, abs=1)


def test_a_double_tap_at_the_fitted_width_is_left_to_the_app(viewer, page):
    """
    DoubleTapZoom.kt zooms that one, to twice the fitted width, so the page must neither
    shrink its pages, as a double tap that toggled would, nor move them. Checked with the
    WebView zoomed in as well, since the app's double tap from there is the one back out.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    page.evaluate("() => window.scrollTo(0, 5000)")
    scrolled = page.evaluate("() => scrollY")

    tap(page, 490, 900, times=2)
    page.wait_for_timeout(400)
    assert shrink(page) == 1
    assert page.evaluate("() => scrollY") == scrolled
    assert not page.evaluate("() => document.getElementById('pages').classList.contains('vw-moving')")

    assert set_page_scale(page, 2) > 1.5
    tap(page, 300, 500, times=2)
    page.wait_for_timeout(400)
    assert shrink(page) == 1
    assert page.evaluate("() => scrollY") == scrolled
    set_page_scale(page, 1)


def test_one_tap_and_a_tap_that_moves_zoom_nothing(viewer, page):
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    shrink_to(page, 0.4)

    tap(page, 490, 900)
    page.wait_for_timeout(400)
    assert shrink(page) == pytest.approx(0.4)

    tap(page, 490, 900)
    tap(page, 490, 300)
    page.wait_for_timeout(400)
    assert shrink(page) == pytest.approx(0.4), "two taps far apart are not a double tap"


def test_the_app_is_told_when_the_pages_shrink_and_when_they_come_back(viewer, page, port):
    """
    The strings PortMessage.kt reads as Shrunk: one word and one digit, sent only as the
    pages cross the fitted width, since the app wants to know whose the double tap is and
    nothing else.
    """
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    p = port()
    p.wait_for(r"page 1 6")
    assert [m for m in p.messages() if m.startswith("shrunk")] == []

    shrink_to(page, 0.5)
    assert p.wait_for(r"shrunk 1")
    shrink_to(page, 0.4)
    page.wait_for_timeout(200)
    assert [m for m in p.messages() if m.startswith("shrunk")] == ["shrunk 1"]

    shrink_to(page, 1)
    assert p.wait_for(r"shrunk 0")


# ---------------------------------------------------------------------------
# What a column of small pages costs
# ---------------------------------------------------------------------------

def test_a_page_is_drawn_at_the_size_it_is_shown(viewer, page):
    """
    The whole of the memory argument. A page bitmap is sized from the width its box is laid
    out at, so a quarter-width page holds a sixteenth of the pixels, and nothing drawn at
    the fitted width is kept behind a small page.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    dppx = page.evaluate("() => document.querySelector('#pages .pg canvas').width"
                         " / document.querySelector('#pages .pg').getBoundingClientRect().width")

    shrink_to(page, FLOOR)
    wait_for_band(page)

    drawn = page.evaluate(
        f"() => {PAGES}.filter({SETTLED}).map(pg => [pg.querySelector('canvas').width,"
        " pg.getBoundingClientRect().width])")
    assert drawn, "nothing drawn at the floor"
    for bitmap, box in drawn:
        assert bitmap == pytest.approx(box * dppx, rel=0.02), (
            f"a {box} px wide page holds a {bitmap} px wide bitmap")
    assert set(page.evaluate(DRAWN_AT)) == {FLOOR}


def test_coming_back_to_the_fitted_width_draws_the_pages_again_at_it(viewer, page):
    """A page drawn small is a blur at the fitted width, so it is given up and asked for again."""
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    shrink_to(page, FLOOR)
    wait_for_band(page)
    assert set(page.evaluate(DRAWN_AT)) == {FLOOR}

    shrink_to(page, 1)
    wait_for_band(page)

    assert set(page.evaluate(DRAWN_AT)) == {1}
    widths = page.evaluate(
        f"() => {PAGES}.filter({SETTLED}).map(pg => pg.querySelector('canvas').width)")
    assert widths and all(w == widths[0] for w in widths)


def test_a_small_pinch_keeps_the_pages_it_has(viewer, page):
    """
    Within a quarter either way a bitmap is near enough the size it is shown at, and a
    reader who takes the pages in by a tenth should not watch the column go white.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    wait_for_band(page)

    shrink_to(page, 0.9)
    wait_for_band(page)

    assert set(page.evaluate(DRAWN_AT)) == {1}


def test_the_bands_are_counted_in_shrunk_pages(viewer, page):
    """
    The observers' margins are px, fixed when they are made from a fitted page's height.
    Left as they were, a column of quarter-width pages would have four times as many pages
    in the band, every one of them drawn.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    shrink_to(page, FLOOR)
    wait_for_band(page)

    pitch = page.evaluate(
        f"() => {PAGES}[1].getBoundingClientRect().top - {PAGES}[0].getBoundingClientRect().top")
    on_screen = page.evaluate("() => innerHeight") / pitch + 1
    asked_for = page.evaluate(ASKED_FOR)
    assert asked_for <= on_screen + 2 * DRAW_PAGES + 1, (
        f"{asked_for} pages asked for, with about {on_screen:.1f} on screen")


def test_no_tile_is_drawn_while_the_pages_are_shrunk(viewer, page):
    """
    A shrunk page is drawn at the size it is shown, so there is nothing to sharpen; and a
    tile is the one thing that could put a screen's worth of pixels back on a small page.
    """
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    shrink_to(page, 0.5)
    wait_for_band(page)

    # The WebView cannot zoom in over a shrunk column, since touch-action takes its pinch;
    # the compositor can, and the page still draws no tile.
    assert set_page_scale(page, 3) > 2
    page.wait_for_timeout(600)
    assert tile_count(page) == 0
    set_page_scale(page, 1)


def walk(page, step=1200):
    """
    Scrolls the whole document a screen at a time, waiting for the band to settle at
    each stop, and answers the most bitmap held at any stop.
    """
    peak = 0
    y = 0
    end = page.evaluate("() => document.documentElement.scrollHeight")
    while y < end:
        page.evaluate("(y) => window.scrollTo(0, y)", y)
        wait_for_band(page)
        peak = max(peak, page.evaluate(BITMAP_PIXELS))
        y += step
    page.evaluate("() => window.scrollTo(0, 0)")
    wait_for_band(page)
    return peak


def test_the_bitmap_held_at_the_floor_is_no_more_than_at_the_fitted_width(viewer, page):
    """
    Issue #16 is why this file is arranged as it is, and a column of small pages puts far
    more pages on screen and in the bands. Each at a sixteenth of the pixels, the whole
    column costs less than a screen of fitted pages.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    at_fit = walk(page)

    shrink_to(page, FLOOR)
    wait_for_band(page)
    at_floor = walk(page)

    print(f"canvas pixels held, peak over a walk: {at_fit} at the fitted width, {at_floor} at the floor")
    assert at_floor <= at_fit, f"{at_floor} px held at the floor against {at_fit} at the fitted width"


# ---------------------------------------------------------------------------
# What must keep working while the pages are small
# ---------------------------------------------------------------------------

WORDS_ON_THE_PAGE = """() => {
  const pg = document.querySelector('#pages .pg');
  const box = pg.getBoundingClientRect();
  return [...pg.querySelectorAll('.textLayer span')]
    .filter(s => s.firstChild && s.firstChild.nodeType === 3)
    .map(s => {
      const r = document.createRange(); r.selectNodeContents(s);
      const b = r.getBoundingClientRect();
      return [s.textContent, (b.left - box.left) / box.width, (b.top - box.top) / box.height,
              b.width / box.width, b.height / box.height];
    });
}"""


def test_the_words_stay_over_the_picture_while_shrunk(viewer, page):
    """
    The contract that fails silently: pdf.js positions each span in percent and the
    stylesheet sizes its type from --total-scale-factor, so the type is the one thing that
    has to be told the page shrank. Measured as fractions of the page box, so that the words
    at the floor are the words at the fitted width, only smaller.
    """
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    wait_for_text_layer(page)
    at_fit = page.evaluate(WORDS_ON_THE_PAGE)
    assert at_fit

    shrink_to(page, FLOOR)
    wait_for_band(page)
    wait_for_page(page, 0)
    at_floor = page.evaluate(WORDS_ON_THE_PAGE)

    assert [w[0] for w in at_floor] == [w[0] for w in at_fit]
    for a, b in zip(at_fit, at_floor):
        for k in range(1, 5):
            assert b[k] == pytest.approx(a[k], abs=0.003), f"{a[0]!r} moved: {a} against {b}"


# The vertical intervals of every span in the first page's layer, merged, and what is left
# between them, which is a band a finger can drag a selection through with no word under
# it. The same measure as test_pdf_selection.py's, in the shrunk page's px.
HOLES_DOWN_THE_PAGE = """() => {
  const layer = document.querySelector('#pages .pg .textLayer');
  const top = layer.getBoundingClientRect().top;
  const iv = [...layer.querySelectorAll('span')]
    .map(s => s.getBoundingClientRect()).filter(r => r.height > 0)
    .map(r => [r.top, r.bottom]).sort((a, b) => a[0] - b[0]);
  const merged = [iv[0].slice()];
  for (const [from, to] of iv.slice(1)) {
    const last = merged[merged.length - 1];
    if (from <= last[1]) last[1] = Math.max(last[1], to);
    else merged.push([from, to]);
  }
  const holes = [];
  for (let i = 1; i < merged.length; i++)
    holes.push({ at: +(merged[i - 1][1] - top).toFixed(1),
                 size: +(merged[i][0] - merged[i - 1][1]).toFixed(3) });
  return holes.sort((a, b) => b.size - a.size);
}"""


# How far the spans of the first page's layer reach past the page, at most. A padding too
# large for the width the page is shown at runs the last row off the foot of the page and
# the last span on a row off its right edge, which no hole can show.
OVERRUN = """() => {
  const layer = document.querySelector('#pages .pg .textLayer');
  const box = layer.getBoundingClientRect();
  let worst = 0;
  for (const s of layer.querySelectorAll('span')) {
    const r = s.getBoundingClientRect();
    if (!r.height) continue;
    worst = Math.max(worst, r.bottom - box.bottom, r.right - box.right, box.left - r.left, box.top - r.top);
  }
  return +worst.toFixed(3);
}"""


@pytest.mark.parametrize("fraction,drawn_at", [(FLOOR, FLOOR), (0.85, 1)])
def test_the_hit_boxes_still_cover_the_page_while_shrunk(viewer, page, fraction, drawn_at):
    """
    padRows() writes its paddings for the fitted page, times --vw-shrink, so a page drawn
    at one width is still covered at another. Two widths: the floor, which the page is drawn
    again at, and one near enough the fitted width that the page drawn there is kept, where
    a padding written in plain px would be the fitted page's on a smaller one.
    """
    viewer("pdf.html", "ragged-prose.pdf")
    wait_for_pdf(page)
    wait_for_text_layer(page)
    shrink_to(page, fraction)
    wait_for_band(page)
    wait_for_page(page, 0)
    assert page.evaluate(DRAWN_AT) == [drawn_at]

    holes = page.evaluate(HOLES_DOWN_THE_PAGE)
    worst = holes[0] if holes else {"size": 0}
    assert worst["size"] < 0.1, (
        f"{len(holes)} uncovered bands at {fraction}, worst {worst['size']}px at y={worst.get('at')}")
    # Half a pixel: a padding carries two decimals of the fitted page's px and is laid out on
    # a subpixel grid, and the failure this guards against is a whole gap's worth
    assert page.evaluate(OVERRUN) < 0.5, "a hit box reaches past the page"


def test_find_lands_on_the_right_words_while_shrunk(viewer, page, port):
    """
    The highlight is a Range over the shrunk layer's spans, and the hit is centred by
    reveal() rather than left wherever centring its page put it. At half width the
    document scrolls far enough for the second and third hits to tell the two apart.
    """
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    shrink_to(page, 0.5)
    wait_for_band(page)
    p = port()

    p.query("tenancy")
    assert p.wait_for_count(1, 3, done=True)
    assert highlight_count(page, "vw-find-active") == 1

    for expected in (2, 3):
        p.next()
        p.wait_for_count(expected, 3)
        hit = page.evaluate("""() => {
          const r = [...CSS.highlights.get('vw-find-active')][0];
          const b = r.getBoundingClientRect();
          const pg = r.startContainer.parentElement.closest('.pg').getBoundingClientRect();
          const most = document.documentElement.scrollHeight - innerHeight;
          return { text: r.toString(), inside: b.left >= pg.left - 0.5 && b.right <= pg.right + 0.5
                   && b.top >= pg.top - 0.5 && b.bottom <= pg.bottom + 0.5,
                   centred: Math.abs(b.top - innerHeight / 2) < 1
                     || (scrollY <= 0 && b.top < innerHeight / 2)
                     || (scrollY >= most - 1 && b.top > innerHeight / 2) };
        }""")
        assert hit["text"].lower() == "tenancy", hit
        assert hit["inside"], f"the highlight lies outside its page: {hit}"
        assert hit["centred"], f"the hit was not brought to the middle of the screen: {hit}"


def test_the_page_readout_and_go_to_page_follow_the_shrunk_column(viewer, page, port):
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    p = port()
    p.wait_for(r"page 1 40")
    shrink_to(page, FLOOR)
    p.clear_inbox()

    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)")
    assert p.wait_for(r"page 40 40")

    p.go_to_page(25)
    assert p.wait_for(r"page 25 40")
    wait_for_band(page)
    assert page.evaluate(f"({SETTLED})({PAGES}[24])"), "page 25 was not drawn"


def test_night_mode_keeps_the_shrink_and_turns_the_small_pages_over(viewer, page, port):
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    shrink_to(page, 0.5)
    wait_for_band(page)
    p = port()

    p.night_mode(True)
    page.wait_for_function("() => document.documentElement.classList.contains('vw-night')")
    wait_for_band(page)

    assert shrink(page) == pytest.approx(0.5)
    assert set(page.evaluate(DRAWN_AT)) == {0.5}
    assert page.evaluate(f"() => {PAGES}.filter({SETTLED}).every(pg => pg._slot.mode === true)")


def test_the_title_bars_room_is_unchanged_by_a_shrink(viewer, page, port):
    viewer("pdf.html", "forty-pages.pdf", top=56)
    wait_for_pdf(page)
    room = "() => getComputedStyle(document.documentElement).getPropertyValue('--vw-top')"
    before = page.evaluate(room)

    # Shrunk a little: a page still taller than half the screen is put at the top by Go to
    # page, where a shorter one is centred, and only the first says where the bar's room is
    shrink_to(page, 0.8)
    assert page.evaluate(room) == before

    port().go_to_page(4)
    page.wait_for_function(
        f"() => Math.abs({PAGES}[3].getBoundingClientRect().top - 56) < 1", timeout=10000)


@pytest.fixture(scope="session")
def three_page_pdf(tmp_path_factory):
    """Three A4 pages: taller than the screen fitted, shorter than it at the floor."""
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas

    target = tmp_path_factory.mktemp("three") / "three-pages.pdf"
    c = canvas.Canvas(str(target), pagesize=A4, invariant=1)
    for n in range(1, 4):
        c.drawString(72, 760, f"Page {n}")
        c.showPage()
    c.save()
    return target


def test_a_document_that_fits_the_screen_once_shrunk_sits_in_the_middle(viewer, page, three_page_pdf):
    """#pages centres a document shorter than the screen, and a short one gets shorter."""
    viewer("pdf.html", three_page_pdf)
    wait_for_pdf(page)
    assert page.evaluate("() => document.documentElement.scrollHeight > innerHeight")
    shrink_to(page, FLOOR)

    first = page.evaluate(FIRST_TOP)
    last_bottom = page.evaluate(f"() => {PAGES}[2].getBoundingClientRect().bottom")
    height = page.evaluate("() => innerHeight")
    assert last_bottom - first < height
    assert first == pytest.approx(height - last_bottom, abs=12), "the column is not centred"


def test_a_document_of_one_page_is_drawn_again_at_its_new_size(viewer, page):
    """
    A one-page document has no bands and is never observed, so nothing would ask for its
    page again once a pinch gave it up: the first run of this left it blank for good.
    """
    viewer("pdf.html", "embedded-font.pdf")
    wait_for_pdf(page)
    shrink_to(page, FLOOR)
    wait_for_band(page)
    assert page.evaluate(DRAWN_AT) == [FLOOR]

    shrink_to(page, 1)
    wait_for_band(page)
    assert page.evaluate(DRAWN_AT) == [1]
