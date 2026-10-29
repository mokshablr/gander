"""The page's half of a double tap's zoom. The app's half is DoubleTapZoom.kt."""

import pytest

TOUCH_ACTION = "() => getComputedStyle(document.documentElement).touchAction"


@pytest.mark.parametrize("html,fixture", [
    ("pdf.html", "six-pages.pdf"),
    ("docx.html", "report.docx"),
    ("prose.html", "letter.odt"),
    ("pptx.html", "deck.pptx"),
    ("imgweb.html", "icon.svg"),
])
def test_a_page_the_app_zooms_turns_chromiums_own_double_tap_zoom_off(viewer, page, html, fixture):
    """Without it Chromium zooms the same double tap as well."""
    viewer(html, fixture)
    assert page.evaluate(TOUCH_ACTION) == "manipulation"


def test_a_sheet_turns_off_the_webviews_pinch_and_double_tap_for_its_own(viewer, page):
    """
    A sheet zooms itself, so that its headings and frozen panes stay on screen: see xlsx.js. The
    WebView's pinch is off by the viewport, and its double tap with it; touch-action says the same,
    for an engine that lets a page be zoomed whatever its viewport says.
    """
    viewer("xlsx.html", "budget.xlsx")
    assert page.evaluate(TOUCH_ACTION) == "pan-x pan-y"
    assert "user-scalable=no" in page.evaluate(
        "() => document.querySelector('meta[name=viewport]').getAttribute('content')")
