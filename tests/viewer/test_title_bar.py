"""
The room a document leaves at its top for the title bar that floats over it, issue #40.

ViewerActivity puts the bar's height in dp on the URL as top=, and sends it again over the
channel as "t<dp>" when the search bar under it changes it. "b<dp>" says how much of the bar has
gone up out of sight as it follows the reader's scrolling. In this harness a CSS px is a dp, since
the page is not zoomed; the phone's zoom is tested on a page shaped like a phone.
"""

import pytest

from helpers import big_sheet, set_page_scale, wait_for_pdf, wait_until_done

BAR = 56

# Where each page's document begins
FIRST = {
    "pdf.html": "#pages .pg",
    "docx.html": ".docx-wrapper > section.docx",
    "pptx.html": "#result .slide",
    "xlsx.html": "#sheet",
    "md.html": "#content",
    "text.html": "#content",
    "prose.html": ".vw-paper > section",
}


@pytest.fixture(scope="session")
def one_page_pdf(tmp_path_factory):
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas

    target = tmp_path_factory.mktemp("short") / "one-page.pdf"
    c = canvas.Canvas(str(target), pagesize=A4, invariant=1)
    c.drawString(72, 760, "The only page")
    c.showPage()
    c.save()
    return target


@pytest.fixture
def opened(viewer, page):
    """A page with its document drawn, given room for a bar of [top] dp or none."""
    def open_doc(html, fixture, top=None):
        viewer(html, fixture, top=top)
        if html == "pdf.html":
            wait_for_pdf(page)
        else:
            wait_until_done(page)
        return page
    return open_doc


def first_top(page, html):
    """Where the document begins, from the top of the page rather than of the screen."""
    return page.evaluate(
        "(s) => document.querySelector(s).getBoundingClientRect().top + scrollY", FIRST[html]
    )


def room(page):
    return page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--vw-top')")


def fits(page):
    return page.evaluate("() => document.documentElement.scrollHeight <= innerHeight")


@pytest.mark.parametrize("html,fixture", [
    ("pdf.html", "six-pages.pdf"),
    ("docx.html", "word-pages.docx"),
    ("pptx.html", "deck.pptx"),
    ("prose.html", "letter.odt"),
    ("text.html", "big.txt"),
    ("xlsx.html", "big.csv"),
])
def test_a_long_document_begins_the_bars_height_further_down(opened, made, html, fixture):
    if fixture == "big.txt":
        fixture = made("big.txt", "\n".join(f"Line {n}" for n in range(1, 400)))
    elif fixture == "big.csv":
        fixture = big_sheet(made, 300)
    page = opened(html, fixture)
    assert not fits(page), "the document has to be longer than the screen for this to say anything"
    before = first_top(page, html)

    opened(html, fixture, top=BAR)
    assert first_top(page, html) == pytest.approx(before + BAR, abs=1)


@pytest.mark.parametrize("html,fixture", [
    ("pdf.html", "one-page.pdf"),
    ("docx.html", "report.docx"),
    ("pptx.html", "lines.pptx"),
    ("prose.html", "short.rtf"),
    ("xlsx.html", "budget.xlsx"),
    ("md.html", "notes.md"),
    ("text.html", "plain.txt"),
])
def test_a_short_document_clears_the_bar_and_still_does_not_scroll(opened, made, one_page_pdf, html, fixture):
    if fixture == "one-page.pdf":
        fixture = one_page_pdf
    elif fixture == "short.rtf":
        fixture = made("short.rtf", r"{\rtf1\ansi A letter of one line.\par}")
    page = opened(html, fixture, top=BAR)
    assert first_top(page, html) >= BAR
    assert fits(page)


def test_a_page_given_no_room_leaves_none(opened):
    page = opened("pdf.html", "six-pages.pdf")
    assert room(page) == ""
    assert page.evaluate("() => getComputedStyle(document.body).paddingTop") == "0px"


@pytest.fixture
def widescreen_deck(made, fixture_path):
    """deck.pptx with 16:9 slides, which PPTXjs draws 1280 CSS px wide."""
    import zipfile

    target = made("widescreen.pptx", b"")
    with zipfile.ZipFile(fixture_path("deck.pptx")) as source, zipfile.ZipFile(target, "w") as out:
        for item in source.infolist():
            data = source.read(item.filename)
            if item.filename == "ppt/presentation.xml":
                data = data.replace(b'cx="9144000" cy="6858000"', b'cx="12192000" cy="6858000"')
                assert b'cx="12192000"' in data
            out.writestr(item, data)
    return target


def test_the_room_is_the_bars_height_on_a_phones_screen(browser, server, widescreen_deck):
    """
    pdf.html lays out at 980 CSS px and the WebView zooms that to the phone's width, so a dp is
    over two CSS px there. A widescreen deck is wider than 980, and the page zooms further out
    once its slides are in. text.html declares the phone's width and is not zoomed.
    """
    context = browser.new_context(
        viewport={"width": 411, "height": 891}, device_scale_factor=2.625,
        is_mobile=True, has_touch=True,
    )
    try:
        page = context.new_page()
        cases = (("pdf.html", "six-pages.pdf"), ("pptx.html", widescreen_deck), ("text.html", "plain.txt"))
        for html, fixture in cases:
            server.show(fixture)
            page.goto(server.url(html, top=BAR))
            wait_for_pdf(page) if html == "pdf.html" else wait_until_done(page)
            if html == "pptx.html":
                assert page.evaluate("() => innerWidth") > 1200, "the deck should have zoomed the page out"
            assert on_screen(page) == pytest.approx(BAR, abs=0.5), html

        # Turned on its side, a dp is fewer of the page's CSS px
        server.show("six-pages.pdf")
        page.goto(server.url("pdf.html", top=BAR))
        wait_for_pdf(page)
        page.set_viewport_size({"width": 891, "height": 411})
        page.wait_for_function(f"() => Math.abs(({ON_SCREEN})() - {BAR}) < 0.5", timeout=5000)
    finally:
        context.close()


ON_SCREEN = (
    "() => parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--vw-top'))"
    " * visualViewport.scale"
)


def on_screen(page):
    """The room left for the bar, in dp."""
    return page.evaluate(ON_SCREEN)


def test_a_pinch_leaves_the_room_as_it_was(opened):
    page = opened("pdf.html", "six-pages.pdf", top=BAR)
    before = room(page)
    assert set_page_scale(page, 2) > 1.5
    page.wait_for_timeout(200)
    assert room(page) == before


def test_the_channel_changes_the_room_for_the_search_bar(opened, port):
    page = opened("pdf.html", "six-pages.pdf", top=BAR)
    before = first_top(page, "pdf.html")
    channel = port()

    channel.send("t112")
    page.wait_for_function("() => getComputedStyle(document.documentElement)"
                           ".getPropertyValue('--vw-top') === '112px'")
    assert first_top(page, "pdf.html") == pytest.approx(before + 112 - BAR, abs=1)


def test_go_to_page_puts_the_page_just_below_the_bar(opened, port):
    page = opened("pdf.html", "six-pages.pdf", top=BAR)
    channel = port()
    channel.go_to_page(4)
    page.wait_for_function(
        "() => Math.abs(document.querySelectorAll('#pages .pg')[3].getBoundingClientRect().top - 56) < 1",
        timeout=10000,
    )


def test_go_to_page_in_a_word_document_puts_it_just_below_the_bar(opened, port):
    page = opened("docx.html", "word-pages.docx", top=BAR)
    channel = port()
    channel.go_to_page(3)
    page.wait_for_function(
        "() => { const s = document.querySelectorAll('.docx-wrapper > section.docx')[2];"
        " return Math.abs(s.getBoundingClientRect().top / (vwPages.rectScale || 1) - 56) < 1; }",
        timeout=10000,
    )


def test_a_sheets_tabs_follow_the_bar_pixel_for_pixel(opened, port):
    page = opened("xlsx.html", "budget.xlsx", top=BAR)
    tabs_top = "() => getComputedStyle(document.getElementById('tabs')).top"
    assert page.evaluate(tabs_top) == f"{BAR}px"
    channel = port()

    for gone, held_at in (("20", "36px"), ("56", "0px"), ("12.5", "43.5px"), ("0", f"{BAR}px")):
        channel.send("b" + gone)
        page.wait_for_function(f"() => ({tabs_top})() === '{held_at}'", timeout=5000)


def test_a_zoomed_out_page_hears_how_much_of_the_bar_has_gone_in_its_own_px(browser, server):
    """pdf.html is wider than a phone and zoomed out to fit, so a dp is more than one of its px."""
    context = browser.new_context(
        viewport={"width": 411, "height": 891}, device_scale_factor=2.625,
        is_mobile=True, has_touch=True,
    )
    try:
        page = context.new_page()
        server.show("six-pages.pdf")
        page.goto(server.url("pdf.html", top=BAR))
        wait_for_pdf(page)
        page.evaluate("() => vwBarSaid('b20')")
        assert page.evaluate(
            "() => parseFloat(getComputedStyle(document.documentElement)"
            ".getPropertyValue('--vw-bar-gone')) * visualViewport.scale"
        ) == pytest.approx(20, abs=0.5)
    finally:
        context.close()


def test_the_bars_words_are_not_taken_for_a_search(opened, port):
    page = opened("text.html", "plain.txt", top=BAR)
    channel = port()
    channel.send("t80")
    channel.send("b12")
    page.wait_for_timeout(300)
    assert channel.counts() == []
    assert room(page) == "80px"


def test_a_printout_leaves_no_room_for_a_bar(opened):
    page = opened("docx.html", "report.docx", top=BAR)
    page.emulate_media(media="print")
    assert page.evaluate("() => getComputedStyle(document.body).paddingTop") == "0px"
