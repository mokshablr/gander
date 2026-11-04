"""
Issue #20: drawing the page again at the zoom the reader is actually at.

A page bitmap is drawn once and pinch zoom is the compositor magnifying it, so
past the point where it runs out of pixels the reader is looking at an upscale.
pdf.html now draws the part of the page on screen a second time, at the live
zoom, as a tile laid over the page.

These drive real page scale through CDP, which is what the WebView's pinch is:
a step applied after rendering that moves visualViewport and leaves the layout
alone. Playwright's own zoom would not do, because it changes layout instead.

One thing to know when reading the numbers: devicePixelRatio is 1 here where a
phone is 2 or more, so the constants come out smaller than on a device. Every
assertion below is therefore about a relationship rather than an absolute, and
holds at any density.
"""

import pytest

from helpers import (
    device_pixels_per_css_pixel, pan, set_page_scale, tile_count, tiles,
    visible_rect, wait_for_pdf, wait_for_tile,
)

# Chromium clamps page scale to the page's maximum, which is 3 for this layout.
ZOOMED = 4


def zoom(page, factor=ZOOMED):
    scale = set_page_scale(page, factor)
    assert scale > 1, f"page scale did not move: {scale}"
    return scale


# ---------------------------------------------------------------------------
# When a tile is and is not drawn
# ---------------------------------------------------------------------------

def test_no_tile_is_drawn_at_fit_to_width(viewer, page):
    """The page bitmap already has the pixels, so a tile would cost memory for
    nothing. This is also what keeps the common case free."""
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    page.wait_for_timeout(600)          # longer than the 150 ms settle
    assert tile_count(page) == 0


def test_zooming_in_draws_a_sharp_patch(viewer, page):
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    zoom(page)
    wait_for_tile(page)
    assert tile_count(page) >= 1


def test_zooming_back_out_releases_every_patch(viewer, page):
    """Tiles are the largest thing this file allocates that is not a page, so
    they have to go the moment they stop earning their place."""
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    zoom(page)
    wait_for_tile(page)
    set_page_scale(page, 1)
    page.wait_for_function("() => "
        "document.querySelectorAll('#pages .pg canvas.tile').length === 0",
        timeout=10000)


# ---------------------------------------------------------------------------
# Where the tile is, and how much of it there is
# ---------------------------------------------------------------------------

def test_the_patch_covers_what_the_reader_can_see(viewer, page):
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    zoom(page)
    wait_for_tile(page)

    seen = visible_rect(page)
    t = tiles(page)[0]
    # half a pixel of slack, against the rounding the tile snaps itself to
    assert t["x"] <= seen["x"] + 0.5, f"tile starts right of the view: {t} {seen}"
    assert t["y"] <= seen["y"] + 0.5, f"tile starts below the view: {t} {seen}"
    assert t["x"] + t["w"] >= seen["x"] + seen["w"] - 0.5, f"tile ends early: {t} {seen}"
    assert t["y"] + t["h"] >= seen["y"] + seen["h"] - 0.5, f"tile ends early: {t} {seen}"


def test_the_patch_is_drawn_at_the_zoom_the_reader_is_at(viewer, page):
    """The whole point: its bitmap is sized for the device pixels it will occupy,
    not for the page's fitted size."""
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    zoom(page)
    wait_for_tile(page)

    dppx = device_pixels_per_css_pixel(page)
    t = tiles(page)[0]
    assert t["px"] == pytest.approx(t["w"] * dppx, rel=0.02), (
        f"tile bitmap {t['px']}x{t['py']} does not match {t['w']}x{t['h']} CSS px "
        f"at {dppx} device px per CSS px"
    )
    assert t["py"] == pytest.approx(t["h"] * dppx, rel=0.02)


def test_the_patch_is_bounded_by_the_screen_not_by_the_page(viewer, page):
    """
    This is why redrawing at ten times zoom is affordable at all. The page at
    this scale is far larger than the tile, and the tile is near enough what is
    visible: bound it to the page instead and the memory grows with the square
    of the zoom.
    """
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    zoom(page)
    wait_for_tile(page)

    t = tiles(page)[0]
    seen = visible_rect(page)
    dppx = device_pixels_per_css_pixel(page)
    whole_page = t["boxW"] * dppx * t["boxH"] * dppx
    visible = seen["w"] * dppx * seen["h"] * dppx

    assert t["px"] * t["py"] < whole_page / 2, "the tile is page-sized, not screen-sized"
    # a fifth of margin each way is 1.4 squared, so about twice what is visible
    assert t["px"] * t["py"] <= visible * 2.6, "the tile carries more margin than it should"


def test_a_page_scrolled_out_of_view_loses_its_patch(viewer, page):
    """
    The other half of releasing them. Zooming out drops every tile at once;
    this is the path where the reader stays zoomed in and simply moves on, and
    a tile left behind on a page nobody is looking at is memory held for
    nothing.

    Note there is no test for MAX_TILES. It cannot bind: at the zoom the gate
    opens, at most about one page height is visible on any real screen, so the
    list it truncates never has three pages in it. See the note beside the
    constant in pdf.mjs.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page)
    zoom(page)
    wait_for_tile(page)

    tiled = page.evaluate(
        "() => [...document.querySelectorAll('#pages .pg')]"
        ".findIndex(p => p.querySelector('canvas.tile'))"
    )
    assert tiled >= 0

    # Far enough that the page is off screen, near enough that it is still
    # drawn. Scroll it out of the band entirely and blank() releases the whole
    # page, tile included, which would pass this test without the code under it
    # ever running.
    page.evaluate("() => window.scrollTo(0, 2200)")
    page.wait_for_function(
        "(i) => !document.querySelectorAll('#pages .pg')[i]"
        ".querySelector('canvas.tile')",
        arg=tiled, timeout=10000,
    )
    assert page.evaluate(
        "(i) => document.querySelectorAll('#pages .pg')[i]"
        ".querySelector('canvas:not(.tile)').width > 300", tiled
    ), "the page was released, so this proved nothing about dropping its tile"


# ---------------------------------------------------------------------------
# That the picture in the tile is the right picture
# ---------------------------------------------------------------------------

def test_the_patch_lands_exactly_on_the_page_beneath_it(viewer, page):
    """
    The one that would catch a wrong offset in the render transform.

    Both pictures are of the same region, so bringing the tile down to the page
    bitmap's resolution and sliding it over its neighbours must find the best
    agreement at no offset at all. A tile placed half a page out still looks
    like a plausible tile; only this notices.
    """
    # A page with detail everywhere. A fixture carrying one line of text at the
    # top correlates to nothing once you have zoomed past it, and two blank
    # regions agree with each other perfectly at every offset.
    viewer("pdf.html", "dense-map.pdf")
    wait_for_pdf(page)
    zoom(page)
    # Off the page's own corner, in both directions. A tile drawn from the
    # wrong place would otherwise be drawn from the right one by accident:
    # at the top of the document the visible region *is* the page's corner.
    #
    # Measured down from where the page is rather than from the top of the
    # document, because #pages centres a document that fits on the screen and
    # this one does: 693 px of page in 1600 px of viewport, which leaves it
    # starting 449 px down. A fixed distance lands in the surround above the
    # page, and the view never reaches the page at all.
    top = page.evaluate(
        "() => document.querySelector('#pages .pg').getBoundingClientRect().top")
    pan(page, 150, 0)
    pan(page, 0, top + 180)
    wait_for_tile(page)
    page.wait_for_timeout(600)
    seen = visible_rect(page)
    assert seen["x"] > 50 and seen["y"] > 50, f"the view did not move off the corner: {seen}"

    result = page.evaluate("""() => {
      const pg = document.querySelector('#pages .pg');
      const base = pg.querySelector('canvas:not(.tile)');
      const tile = pg.querySelector('canvas.tile');
      const box = pg.getBoundingClientRect();
      const r = tile.getBoundingClientRect();
      // where the tile claims to sit on the page, as a fraction of the page box
      const fx = (r.left - box.left) / box.width, fy = (r.top - box.top) / box.height;
      const fw = r.width / box.width,             fh = r.height / box.height;

      // both pictures show that same region, so each is drawn into one box and
      // they become directly comparable whatever resolution each was drawn at
      const w = 200, h = 200;
      const into = (src, sx, sy, sw, sh) => {
        const c = document.createElement('canvas');
        c.width = w; c.height = h;
        const g = c.getContext('2d');
        g.drawImage(src, sx, sy, sw, sh, 0, 0, w, h);
        return g.getImageData(0, 0, w, h).data;
      };
      const A = into(base, fx * base.width, fy * base.height,
                           fw * base.width, fh * base.height);
      const B = into(tile, 0, 0, tile.width, tile.height);

      const diff = (dx, dy) => {
        let sum = 0, n = 0;
        for (let y = 4; y < h - 4; y++) for (let x = 4; x < w - 4; x++) {
          const a = (y * w + x) * 4, b = ((y + dy) * w + (x + dx)) * 4;
          sum += Math.abs(A[a] - B[b]) + Math.abs(A[a+1] - B[b+1]) + Math.abs(A[a+2] - B[b+2]);
          n += 3;
        }
        return sum / n;
      };
      let best = null;
      const grid = [];
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) {
        const d = diff(dx, dy);
        grid.push({ dx, dy, d: Math.round(d * 100) / 100 });
        if (!best || d < best.d) best = { dx, dy, d };
      }
      return { best, grid };
    }""")
    best = result["best"]
    assert (best["dx"], best["dy"]) == (0, 0), (
        f"the tile agrees with the page best at offset {best['dx']},{best['dy']} "
        f"rather than at none, so it is drawn from the wrong part of the page. "
        f"grid: {result['grid']}"
    )


# ---------------------------------------------------------------------------
# What the tile must not disturb
# ---------------------------------------------------------------------------

def test_the_text_layer_scale_is_untouched_by_the_patch(viewer, page):
    """
    The contract that fails silently. The canvas wants device pixels and the
    text layer wants CSS pixels, and the tile introduced a third scale between
    them. Collapse any two and the words move off the picture, with nothing
    thrown and nothing visibly wrong until a selection covers the wrong text.
    """
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    read = ("() => document.querySelector('#pages .pg .textLayer')"
            ".style.getPropertyValue('--total-scale-factor')")
    page.wait_for_function(f"{read} !== ''")
    before = page.evaluate(read)

    zoom(page)
    wait_for_tile(page)
    assert page.evaluate(read) == before, (
        "drawing a tile moved the text layer's scale"
    )


# ---------------------------------------------------------------------------
# On a phone's screen, where a CSS pixel is not a device pixel
# ---------------------------------------------------------------------------

@pytest.fixture
def phone(browser, server):
    """
    A phone 720 px across at 2 px to the dp, as a Lava Z3 is: the page lays out at 980 CSS
    px and is zoomed to fit, as in the WebView. At the ratio of 1 the rest of this file runs
    at, the cap of two device pixels to the CSS pixel and the screen are the same, so how
    far over the screen a page bitmap is drawn cannot show.
    """
    context = browser.new_context(
        viewport={"width": 360, "height": 800}, device_scale_factor=2,
        is_mobile=True, has_touch=True,
    )
    page = context.new_page()
    server.show("six-pages.pdf")
    page.goto(server.url("pdf.html"))
    wait_for_pdf(page)
    yield page
    context.close()


def fitted(page):
    return page.evaluate("() => visualViewport.scale")


def test_a_fitted_page_is_drawn_at_twice_the_screens_pixels(phone):
    """Measured on a phone, a page drawn a tenth over the screen came out a fifth softer than
    Android's own PDF engine draws it, and one drawn at twice level with it."""
    across = phone.evaluate("() => visualViewport.width * devicePixelRatio * visualViewport.scale")
    assert across == pytest.approx(720, abs=1)
    drawn = phone.evaluate("() => document.querySelector('#pages .pg canvas:not(.tile)').width")
    assert drawn == pytest.approx(2 * 720, abs=2)
    assert tile_count(phone) == 0


def test_a_patch_still_comes_in_short_of_a_double_tap(phone):
    """
    Tiles are gated on the screen's own resolution, not on the page bitmap, so they come in
    at about 1.4 times the fitted width as before. Gated on a bitmap of twice the screen's
    pixels, a double tap's 2x would leave that bitmap magnified with nothing drawn over it.
    """
    fit = fitted(phone)
    set_page_scale(phone, fit * 1.3)
    phone.wait_for_timeout(600)         # longer than the 150 ms settle
    assert tile_count(phone) == 0
    set_page_scale(phone, fit * 1.6)
    wait_for_tile(phone)
    assert tile_count(phone) >= 1
