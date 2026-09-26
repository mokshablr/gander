"""
Dark mode, on every page, without a reference screenshot.

Structural rather than visual: what matters is that a page asked to be dark is
dark and still readable. The exact shades are left to the four goldens in
test_visual.py.

The pages fall into two families, and the split is a design decision rather
than an oversight. A page of prose follows the system scheme, because it is
the reading surface itself. A page that shows a document *on* something -- a
PDF page, a slide, a photo -- keeps a fixed dark ground in both schemes, so
the white paper reads as the content and the surround recedes. Inverting that
surround in light mode would put a white document on a white desk.

Night mode, issue #19, is a third thing and is deliberately not in this file's
two families. It turns a PDF page itself over, it is asked for rather than
inherited from the system, and what it does is pinned in test_pdf_invert.py.
The one assertion about it that belongs here is the boundary: dark mode on the
phone must not turn a document over by itself.
"""

import pytest

# Pages that follow prefers-color-scheme.
THEMED = [
    ("text.html", "plain.txt"),
    ("md.html", "notes.md"),
    ("docx.html", "report.docx"),
    ("xlsx.html", "budget.xlsx"),
    ("unsupported.html", "unknown.xyz"),
]

# Pages whose ground is fixed, and dark, whatever the system says.
FIXED_GROUND = [
    ("pdf.html", "six-pages.pdf"),
    ("pptx.html", "deck.pptx"),
    ("imgweb.html", "anim.gif"),
    ("model.html", "bracket.stl"),
]

# Of the themed pages, these three are the ones whose words Gander itself
# styles. docx.html and xlsx.html render the document's own paper, black on
# white, inside the themed surround, so their ink is not Gander's to colour.
PROSE = [
    ("text.html", "plain.txt", "#content"),
    ("md.html", "notes.md", "#content"),
    ("unsupported.html", "unknown.xyz", ".card"),
]

ALL_PAGES = THEMED + FIXED_GROUND

LUMINANCE = """(selector) => {
  const el = selector ? document.querySelector(selector) : document.body;
  if (!el) return null;
  const rgb = getComputedStyle(el).backgroundColor
    .match(/\\d+(\\.\\d+)?/g).slice(0, 3).map(Number);
  const f = c => { const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
  return 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2]);
}"""


def ground(page, selector=None):
    return page.evaluate(LUMINANCE, selector)


def loaded(page):
    """
    A page's ground is a stylesheet rule, and nothing in any page's script touches
    it, so it is final once the page has loaded.
    """
    page.wait_for_load_state("load")
    return page


def readability(page, selector):
    """
    The contrast between the words in [selector] and whatever is actually
    behind them.

    The background is found by walking up from the element until something
    paints one, because a paragraph is almost always transparent and sitting
    on a card that is not.
    """
    return page.evaluate("""(sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const f = c => { const s = c / 255;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
      const lum = value => {
        const rgb = value.match(/\\d+(\\.\\d+)?/g).slice(0, 3).map(Number);
        return 0.2126 * f(rgb[0]) + 0.7152 * f(rgb[1]) + 0.0722 * f(rgb[2]);
      };
      const opaque = value =>
        value && value !== 'transparent' && !value.startsWith('rgba(0, 0, 0, 0)');

      let behind = el;
      while (behind && !opaque(getComputedStyle(behind).backgroundColor)) {
        behind = behind.parentElement;
      }
      const ink = lum(getComputedStyle(el).color);
      const ground = lum(getComputedStyle(behind || document.body).backgroundColor);
      const lighter = Math.max(ink, ground), darker = Math.min(ink, ground);
      return (lighter + 0.05) / (darker + 0.05);
    }""", selector)


def contrast(a, b):
    lighter, darker = max(a, b), min(a, b)
    return (lighter + 0.05) / (darker + 0.05)


# ---------------------------------------------------------------------------
# The pages that follow the system
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("html,fixture", THEMED, ids=[p[0] for p in THEMED])
def test_a_reading_page_goes_dark_when_asked(viewer, page, html, fixture):
    page.emulate_media(color_scheme="dark")
    viewer(html, fixture)
    loaded(page)
    assert ground(page) < 0.2, f"{html} stayed light under a dark colour scheme"


@pytest.mark.parametrize("html,fixture", THEMED, ids=[p[0] for p in THEMED])
def test_a_reading_page_is_light_by_default(viewer, page, html, fixture):
    page.emulate_media(color_scheme="light")
    viewer(html, fixture)
    loaded(page)
    assert ground(page) > 0.5, f"{html} was dark under a light colour scheme"


@pytest.mark.parametrize("html,fixture", THEMED, ids=[p[0] for p in THEMED])
def test_a_reading_page_really_changes_between_the_two(viewer, page, html, fixture):
    page.emulate_media(color_scheme="light")
    viewer(html, fixture)
    loaded(page)
    light = ground(page)

    page.emulate_media(color_scheme="dark")
    page.reload()
    loaded(page)

    assert light - ground(page) > 0.4, f"{html} barely changed between the two schemes"


@pytest.mark.parametrize("scheme", ["light", "dark"])
@pytest.mark.parametrize("html,fixture,selector", PROSE, ids=[p[0] for p in PROSE])
def test_prose_stays_readable_in_both_schemes(
    viewer, page, html, fixture, selector, scheme
):
    """
    Dark is not the same as legible. A theme that darkens the ground and
    leaves the ink where it was is worse than no theme at all, and it is
    exactly the kind of thing nobody notices until a reader reports it.
    """
    page.emulate_media(color_scheme=scheme)
    viewer(html, fixture)
    loaded(page)
    ratio = readability(page, selector)
    assert ratio is not None, f"{selector} is not on {html}"
    assert ratio >= 4.5, \
        f"{html} text measures {ratio:.2f}:1 in {scheme}, below the 4.5 WCAG AA asks"


# ---------------------------------------------------------------------------
# The pages that hold a document on a fixed ground
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("html,fixture", FIXED_GROUND, ids=[p[0] for p in FIXED_GROUND])
def test_a_document_ground_is_dark_in_both_schemes(viewer, page, html, fixture):
    for scheme in ("light", "dark"):
        page.emulate_media(color_scheme=scheme)
        viewer(html, fixture)
        loaded(page)
        assert ground(page) < 0.25, \
            f"{html} ground went light under {scheme}; the paper would vanish into it"


@pytest.mark.parametrize("html,fixture", FIXED_GROUND, ids=[p[0] for p in FIXED_GROUND])
def test_a_document_ground_does_not_move_with_the_scheme(viewer, page, html, fixture):
    page.emulate_media(color_scheme="light")
    viewer(html, fixture)
    loaded(page)
    light = ground(page)

    page.emulate_media(color_scheme="dark")
    page.reload()
    loaded(page)

    assert abs(light - ground(page)) < 0.02, \
        f"{html} ground shifted with the scheme; it is meant to be fixed"


def test_the_system_scheme_does_not_turn_a_pdf_page_over(viewer, page):
    """
    The line between dark mode and night mode.

    Dark mode is the phone's, and it darkens Gander's own chrome and the pages
    that are reading surfaces. A document is paper and stays as it was printed
    until somebody asks for otherwise, which is what the Night mode item in the
    viewer's menu is for. Wiring one to the other would turn every diagram and
    every letterhead over for people who only ever wanted a dark app.
    """
    from helpers import page_colours, wait_for_pdf
    page.emulate_media(color_scheme="dark")
    viewer("pdf.html", "colours.pdf")
    wait_for_pdf(page)
    assert "255,255,255" in page_colours(page), \
        "the phone's dark mode turned a PDF page over on its own"


def test_a_pdf_page_stands_out_against_its_surround(viewer, page):
    """The whole reason the ground is fixed and warm rather than themed."""
    from helpers import wait_for_pdf
    page.emulate_media(color_scheme="dark")
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)

    surround = ground(page)
    paper = page.evaluate(
        "() => { const c = document.querySelector('#pages .pg canvas');"
        "const ctx = c.getContext('2d');"
        "const d = ctx.getImageData(Math.floor(c.width/2), 10, 1, 1).data;"
        "const f = x => { const s = x / 255;"
        "return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };"
        "return 0.2126*f(d[0]) + 0.7152*f(d[1]) + 0.0722*f(d[2]); }"
    )
    assert contrast(paper, surround) > 3.0, \
        "the page does not read as separate from the ground behind it"


# ---------------------------------------------------------------------------

@pytest.mark.parametrize("html,fixture", ALL_PAGES, ids=[p[0] for p in ALL_PAGES])
def test_every_page_paints_its_own_ground(viewer, page, html, fixture):
    """
    A transparent body borrows whatever is behind it, which in a WebView is
    white, so a dark page would flash white on every load.
    """
    page.emulate_media(color_scheme="dark")
    viewer(html, fixture)
    loaded(page)
    painted = page.evaluate("() => getComputedStyle(document.body).backgroundColor")
    assert painted not in ("rgba(0, 0, 0, 0)", "transparent"), \
        f"{html} left its background transparent"
