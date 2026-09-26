"""Small readers over the pdf.html DOM, so the tests read as prose."""

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

# A canvas that has never been drawn is 300x150, because that is the HTML
# default for the element and pdf.html leaves it alone until draw() sizes it.
# blank() zeroes it on release. So the three states are told apart by size:
# 300 wide means untouched, 0 means released, and anything else is a real
# bitmap at PAGE_WIDTH * DPR across.
DEFAULT_CANVAS_WIDTH = 300

DRAWN = (
    "() => [...document.querySelectorAll('#pages .pg canvas')]"
    f".filter(c => c.width > {DEFAULT_CANVAS_WIDTH}).length"
)
RELEASED = (
    "() => [...document.querySelectorAll('#pages .pg canvas')]"
    ".filter(c => c.width === 0).length"
)
SLOTS = "() => document.querySelectorAll('#pages .pg').length"

# A canvas wider than the default says only that a render has begun: pdf.mjs sizes
# it before it asks pdf.js to draw into it. When the render ended is on the slot
# pdf.mjs hangs on each .pg as _slot. Its state goes to "drawn" after the bitmap,
# the night recolour and the text layer have all landed, and its mode is the night
# setting that bitmap was drawn in, which draw() compares against the current one
# and redraws when they differ. So "drawn, in the mode the page is in now" is the
# one condition under which a page is finished, and the waits below are on that
# rather than on a duration.
SETTLED = (
    "(pg) => { const s = pg && pg._slot; return !!s && s.state === 'drawn'"
    " && s.mode === document.documentElement.classList.contains('vw-night'); }"
)
PAGES = "[...document.querySelectorAll('#pages .pg')]"


def wait_for_pdf(page, pages=None, timeout=30000):
    """Waits until one page is finished and the spinner has gone."""
    page.wait_for_function(f"() => {PAGES}.some({SETTLED})", timeout=timeout)
    page.wait_for_function(
        "() => { const e = document.getElementById('vw-status');"
        "return !e || getComputedStyle(e).display === 'none'"
        " || e.className.indexOf('vw-error') >= 0; }",
        timeout=timeout,
    )
    if pages is not None:
        assert page.evaluate(SLOTS) == pages, (
            f"expected {pages} page slots, saw {page.evaluate(SLOTS)}"
        )
    return page


def wait_for_page(page, index=0, timeout=20000):
    """Waits until page [index] is finished: drawn, turned over if night mode is on, words laid."""
    page.wait_for_function(
        f"(i) => ({SETTLED})(document.querySelectorAll('#pages .pg')[i])",
        arg=index, timeout=timeout,
    )
    return page


def scroll_to_page(page, index, timeout=20000):
    """Scrolls page [index] to the top of the screen and waits for it to be finished there."""
    page.evaluate("(i) => document.querySelectorAll('#pages .pg')[i].scrollIntoView()", index)
    return wait_for_page(page, index, timeout)


def slot_states(page):
    """Each page's state as pdf.mjs has it, for saying what a wait was left looking at."""
    return page.evaluate(
        f"() => {PAGES}.map(pg => pg._slot"
        " ? pg._slot.state + (pg._slot.mode ? ' night' : '') : '?')"
    )


def wait_for_band(page, timeout=20000):
    """
    Waits until nothing is queued or drawing: every page is either untouched or
    finished in the current mode, and at least one is finished.

    After a toggle this is the far side of the redraw. setNight() gives up every page
    drawn in the other mode and asks for it again, and a page that lands in a mode the
    reader has since left is given up once more by draw(), so the pages are settled
    only when the last of those has come back.
    """
    try:
        page.wait_for_function(
            f"() => {{ const pgs = {PAGES}; const settled = {SETTLED};"
            " return pgs.length > 0 && pgs.some(settled) && pgs.every(pg =>"
            " pg._slot && (pg._slot.state === 'blank' || settled(pg))); }",
            timeout=timeout,
        )
    except PlaywrightTimeoutError:
        raise AssertionError(f"the pages never settled: {slot_states(page)}") from None
    return page


def after_frames(page, n=2):
    """
    Waits for [n] animation frames, for work the page does on the frame after an event
    rather than in the event: the observers that draw and release pages report after
    the frame a scroll landed in, and only then does the band move.
    """
    page.evaluate(
        "(n) => new Promise(done => { const step = () =>"
        " n-- > 0 ? requestAnimationFrame(step) : done(); step(); })",
        n,
    )
    return page


def wait_for_text_layer(page, timeout=20000):
    """The text layer is built after the bitmap, so it is waited for separately."""
    page.wait_for_function(
        "() => document.querySelector('#pages .pg .textLayer span')", timeout=timeout
    )
    return page


def drawn_count(page):
    return page.evaluate(DRAWN)


def released_count(page):
    return page.evaluate(RELEASED)


def slot_count(page):
    return page.evaluate(SLOTS)


def canvas_widths(page):
    return page.evaluate(
        "() => [...document.querySelectorAll('#pages .pg canvas')].map(c => c.width)"
    )


def status_text(page):
    el = page.query_selector("#vw-status")
    return el.text_content().strip() if el else ""


def status_visible(page):
    return page.evaluate(
        "() => { const e = document.getElementById('vw-status');"
        "return !!e && getComputedStyle(e).display !== 'none'; }"
    )


def wait_until_done(page, timeout=20000):
    """
    Waits for the status card to go, which the Office and text pages take down
    only once the document is drawn. A failure leaves it up with the error in
    it instead, so text on the page alone does not prove the page got to the end.
    """
    page.wait_for_function(
        "() => { const e = document.getElementById('vw-status');"
        "return !e || getComputedStyle(e).display === 'none'; }",
        timeout=timeout,
    )
    return page


def text_layer(page):
    return page.evaluate(
        "() => [...document.querySelectorAll('#pages .pg .textLayer')]"
        ".map(l => l.textContent).join(' ')"
    )


def highlight_count(page, name="vw-find"):
    return page.evaluate(
        "(n) => (window.CSS && CSS.highlights && CSS.highlights.get(n))"
        " ? CSS.highlights.get(n).size : 0",
        name,
    )


# ---------------------------------------------------------------------------
# Tiles: the sharp patch drawn over the part of a page the reader is looking at
# ---------------------------------------------------------------------------

TILE_COUNT = "document.querySelectorAll('#pages .pg canvas.tile').length"
TILES = f"() => {TILE_COUNT}"


def set_page_scale(page, factor):
    """
    Pinch, the way the WebView's compositor does it.

    Chromium clamps this to a maximum that differs by platform, so the scale that
    actually took effect is read back rather than assumed: asking for 4 gives 3 on
    macOS and 4 on Linux.
    """
    page.context.new_cdp_session(page).send(
        "Emulation.setPageScaleFactor", {"pageScaleFactor": factor}
    )
    return page.evaluate("() => visualViewport.scale")


def pan(page, dx, dy):
    """
    Drag the visual viewport, which is what moves the view while pinched in.

    window.scrollTo moves the layout viewport and cannot go sideways here,
    because the document is exactly as wide as that viewport.

    A mouse-sourced gesture, not a touch one. Headless Chromium on Linux, which is
    where CI runs, drops a synthesised touch gesture without any error and the view
    never moves; a mouse-sourced one pans the pinched viewport by the same CSS px on
    Linux and macOS alike. Unlike a touch drag it does not lock to one axis, so
    pan(200, 250) moves both ways at once. The page cannot tell the difference:
    it follows visualViewport scroll events, whatever produced them.
    """
    page.context.new_cdp_session(page).send("Input.synthesizeScrollGesture", {
        "x": 120, "y": 200, "xDistance": -dx, "yDistance": -dy,
        "gestureSourceType": "mouse",
    })
    page.wait_for_timeout(500)


def tile_count(page):
    return page.evaluate(TILES)


def tiles(page):
    """
    Every tile, with its rect measured from the top left of its own page box.

    The same space visible_rect() reports in, so the two can be compared without
    anything in between to get wrong.
    """
    return page.evaluate(
        "() => [...document.querySelectorAll('#pages .pg')].flatMap(pg => {"
        "  const box = pg.getBoundingClientRect();"
        "  return [...pg.querySelectorAll('canvas.tile')].map(t => {"
        "    const r = t.getBoundingClientRect();"
        "    return { px: t.width, py: t.height,"
        "             x: r.left - box.left, y: r.top - box.top,"
        "             w: r.width, h: r.height,"
        "             boxW: box.width, boxH: box.height }; }); })"
    )


def visible_rect(page, index=0):
    """
    The part of one page the reader can see, in that page's own CSS px.

    getBoundingClientRect is in the layout viewport, which pinching deliberately
    leaves alone, and visualViewport says which part of that is on screen. So the
    two intersect directly.
    """
    return page.evaluate(
        "(i) => { const pg = document.querySelectorAll('#pages .pg')[i];"
        "  const b = pg.getBoundingClientRect(), vv = visualViewport;"
        "  const x = Math.max(b.left, vv.offsetLeft) - b.left;"
        "  const y = Math.max(b.top, vv.offsetTop) - b.top;"
        "  return { x, y,"
        "    w: Math.min(b.right, vv.offsetLeft + vv.width) - b.left - x,"
        "    h: Math.min(b.bottom, vv.offsetTop + vv.height) - b.top - y }; }",
        index,
    )


def device_pixels_per_css_pixel(page):
    """What the page derives its scale from: the screen width over what is visible."""
    return page.evaluate(
        "() => (visualViewport.width * devicePixelRatio * visualViewport.scale)"
        " / visualViewport.width"
    )


# ---------------------------------------------------------------------------
# Night mode: what colour the pixels actually came out
# ---------------------------------------------------------------------------

# Every distinct colour on a canvas, with how many samples had it. The fixture
# it is used on is painted in flat colours, so this reads as a list of "what is
# on this page" rather than a histogram of a photograph, and a test can assert
# exact values without depending on where anything sits.
_COLOURS = """(canvas) => {
  if (!canvas || !canvas.width) return null;
  const d = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
  const seen = new Map();
  for (let y = 0; y < canvas.height; y += 3)
    for (let x = 0; x < canvas.width; x += 3) {
      const o = (y * canvas.width + x) * 4;
      const k = d[o] + ',' + d[o + 1] + ',' + d[o + 2];
      seen.set(k, (seen.get(k) || 0) + 1);
    }
  return [...seen.entries()].sort((a, b) => b[1] - a[1]);
}"""


def page_colours(page, index=0, floor=20):
    """
    The colours on one page's bitmap, commonest first.

    [floor] drops the handful of samples that land on an antialiased edge, which
    are a blend of two real colours and say nothing about what the filter did.
    """
    found = page.evaluate(
        "(i) => (" + _COLOURS + ")"
        "(document.querySelectorAll('#pages .pg')[i].querySelector('canvas'))",
        index,
    )
    return {k: n for k, n in (found or []) if n >= floor}


def tile_colours(page, floor=20):
    """The same, for the sharp patch drawn over a page while zoomed in."""
    found = page.evaluate(
        "() => (" + _COLOURS + ")(document.querySelector('#pages .pg canvas.tile'))"
    )
    return {k: n for k, n in (found or []) if n >= floor}


def region_colours(page, index, fx0, fy0, fx1, fy1, floor=20):
    """
    The colours inside one rectangle of a page, given as fractions of it.

    Needed wherever a page carries several things that share a colour: the ink in a
    chart and the body text beside it are both near-black, so counting the whole
    page cannot say which of them moved.
    """
    found = page.evaluate(
        """([i, a, b, c, e]) => {
            const cv = document.querySelectorAll('#pages .pg')[i].querySelector('canvas');
            if (!cv || !cv.width) return null;
            const x = Math.round(a * cv.width), y = Math.round(b * cv.height);
            const w = Math.round((c - a) * cv.width), h = Math.round((e - b) * cv.height);
            const d = cv.getContext('2d').getImageData(x, y, w, h).data;
            const seen = new Map();
            for (let i2 = 0; i2 < d.length; i2 += 4) {
              const k = d[i2] + ',' + d[i2+1] + ',' + d[i2+2];
              seen.set(k, (seen.get(k) || 0) + 1);
            }
            return [...seen.entries()].sort((p, q) => q[1] - p[1]);
        }""",
        [index, fx0, fy0, fx1, fy1],
    )
    return {k: n for k, n in (found or []) if n >= floor}


def region_fingerprint(page, index, fx0, fy0, fx1, fy1):
    """
    A cheap checksum of one rectangle of a page, for asking "did this change at all".

    Counting colours cannot answer that for a photograph: its pixels are all but
    unique, so every count is one and any threshold hides the lot. Two fingerprints
    that match mean the pixels match.
    """
    return page.evaluate(
        """([i, a, b, c, e]) => {
            const cv = document.querySelectorAll('#pages .pg')[i].querySelector('canvas');
            if (!cv || !cv.width) return null;
            const x = Math.round(a * cv.width), y = Math.round(b * cv.height);
            const w = Math.round((c - a) * cv.width), h = Math.round((e - b) * cv.height);
            const d = cv.getContext('2d').getImageData(x, y, w, h).data;
            let s1 = 0, s2 = 0;
            for (let k = 0; k < d.length; k += 4) {
              s1 = (s1 + d[k] + 2 * d[k+1] + 3 * d[k+2]) % 4294967291;
              s2 = (s2 + s1) % 4294967291;
            }
            return w + 'x' + h + ':' + s1 + ':' + s2;
        }""",
        [index, fx0, fy0, fx1, fy1],
    )


def body_ground(page):
    return page.evaluate("() => getComputedStyle(document.body).backgroundColor")


def paper_ground(page):
    return page.evaluate(
        "() => getComputedStyle(document.querySelector('#pages .pg')).backgroundColor"
    )


def text_layer_geometry(page):
    """Every span's box, to the tenth of a pixel. Nothing night mode does may move one."""
    return page.evaluate(
        "() => [...document.querySelectorAll('#pages .pg .textLayer span')].map(s => {"
        "  const r = s.getBoundingClientRect();"
        "  return [s.textContent, Math.round(r.left * 10) / 10,"
        "          Math.round(r.top * 10) / 10, Math.round(r.width * 10) / 10,"
        "          Math.round(r.height * 10) / 10]; })"
    )


def wait_for_tile(page, timeout=15000):
    """
    A tile is asked for 150 ms after the reader stops and then has to render, so it is
    waited for. One on the page and none on its way: a tile goes into the document
    only once it is drawn and turned over, so a tile in the document is a finished one.
    """
    page.wait_for_function(
        f"() => {TILE_COUNT} >= 1 && {PAGES}.every(pg => !pg._slot || !pg._slot.pending)",
        timeout=timeout,
    )
    return page


def wait_for_tile_away_from_the_top(page, timeout=15000):
    """
    Waits for a tile that does not begin at its page's top edge.

    Panning asks for a new tile, and the old one stays on screen until the new one
    has rendered, so "a tile exists" is true throughout and says nothing. Waiting on
    the condition the test is about, rather than on a duration, is also what keeps
    this from failing only when the machine is busy.
    """
    page.wait_for_function(
        """() => [...document.querySelectorAll('#pages .pg')].some(pg => {
             const b = pg.getBoundingClientRect();
             return [...pg.querySelectorAll('canvas.tile')].some(t => {
               const r = t.getBoundingClientRect();
               return t.width > 0 && r.top - b.top > 1;
             });
           })""",
        timeout=timeout,
    )
    return page
