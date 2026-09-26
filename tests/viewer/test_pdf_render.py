"""
pdf.html rendering: the pages, the text layer, and the ways a document fails.

This is the file that carries the open bugs. Every test here would have to be
run by hand on a phone otherwise, which is what CONTRIBUTING asked for before
this existed.
"""

import pytest

from helpers import (
    after_frames, canvas_widths, drawn_count, page_colours, released_count,
    status_text, status_visible, text_layer, wait_for_band, wait_for_page,
    wait_for_pdf, wait_for_text_layer,
)


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def test_a_document_renders_every_page_it_has(viewer, page):
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page, pages=6)
    assert not status_visible(page), status_text(page)


def test_the_first_page_carries_its_words(viewer, page):
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    wait_for_text_layer(page)
    assert "Alder Court, page 1" in text_layer(page)


def test_a_single_page_document_opens(viewer, page):
    viewer("pdf.html", "embedded-font.pdf")
    wait_for_pdf(page, pages=1)


# ---------------------------------------------------------------------------
# The text layer, which fails silently when its CSS contract is broken
# ---------------------------------------------------------------------------

def _first_span(page):
    return page.evaluate(
        """() => {
          const s = document.querySelector('#pages .pg .textLayer span');
          if (!s) return null;
          const cs = getComputedStyle(s);
          const box = s.getBoundingClientRect();
          const pageBox = s.closest('.pg').getBoundingClientRect();
          return {
            text: s.textContent,
            fontSize: cs.fontSize,
            transform: cs.transform,
            fontFamily: cs.fontFamily,
            inside: box.top >= pageBox.top - 2 && box.bottom <= pageBox.bottom + 2
                 && box.left >= pageBox.left - 2 && box.right <= pageBox.right + 2,
            width: box.width,
          };
        }"""
    )


def test_the_text_layer_is_sized_and_placed_by_the_stylesheet(viewer, page):
    """
    pdf.js writes only custom properties onto each span. The stylesheet has to
    derive font-size and transform from them, and if it does not, every word
    lands at the body default in the top left corner: invisible, because the
    layer is transparent, and wrong for selection and search alike.
    """
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    wait_for_text_layer(page)
    span = _first_span(page)

    assert span is not None, "the text layer built no spans at all"
    assert span["fontSize"] != "16px", "font-size fell back to the body default"
    assert span["transform"] not in ("none", ""), "the scale transform was not applied"
    assert span["width"] > 0


def test_every_word_lands_inside_the_page_it_belongs_to(viewer, page):
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    wait_for_text_layer(page)
    assert _first_span(page)["inside"], "a text run was drawn outside its own page"


def test_an_embedded_face_is_named_ahead_of_the_generic(viewer, page):
    """
    pdf.js measures each run in whatever font it was set in to work out the
    horizontal scale it needs. Left in a generic, the measurement is of the
    wrong face and every highlight sits slightly off the word it marks.
    """
    viewer("pdf.html", "embedded-font.pdf")
    wait_for_pdf(page)
    wait_for_text_layer(page)
    family = _first_span(page)["fontFamily"]
    assert "," in family, f"no fallback behind the embedded face: {family!r}"
    assert family.split(",")[0].strip().strip('"\'') not in (
        "sans-serif", "serif", "monospace",
    ), f"the embedded face was not named first: {family!r}"


# ---------------------------------------------------------------------------
# Issue #21: CJK text disappears without Adobe's CMap tables
# ---------------------------------------------------------------------------

def test_chinese_text_renders_rather_than_vanishing(viewer, page):
    """
    The fixture names a CID font and does not embed it, so pdf.js can only draw
    it by loading UniGB-UCS2-H out of lib/cmaps. Without those tables the text
    is dropped from the canvas and the text layer both, and nothing throws:
    the page renders looking complete with whole paragraphs simply absent.
    """
    viewer("pdf.html", "cjk.pdf")
    wait_for_pdf(page, pages=1)
    page.wait_for_function(
        "() => document.querySelector('#pages .pg .textLayer span')", timeout=15000
    )
    assert "你好世界" in text_layer(page), (
        "the Chinese line is missing; check lib/cmaps ships and vwWithAssets is applied"
    )


def test_the_cmap_tables_are_actually_fetched_and_found(viewer, page, server):
    """
    Asked for *and* answered. pdf.js requests the table either way, so a test
    that only checks the request still passes with the whole directory
    deleted, which is the exact regression it exists to catch.
    """
    viewer("pdf.html", "cjk.pdf")
    # A page cannot be finished before the table its font needed has been answered
    wait_for_pdf(page)

    asked = [r for r in server.state.requests if "/cmaps/" in r["path"]]
    assert asked, "no CMap table was requested; the CJK font was drawn some other way"

    found = server.served("/cmaps/")
    assert found, (
        "every CMap request was refused: "
        f"{[(r['path'], r['status']) for r in asked][:3]}"
    )
    assert all(r["bytes"] > 0 for r in found)


# ---------------------------------------------------------------------------
# Issue #24: images vanish without the wasm decoders
# ---------------------------------------------------------------------------

# Three encodings pdf.js decodes in WebAssembly rather than in the bundle, which
# it fetches through the wasmUrl option. Set none and every image in these
# formats is dropped with a console warning and nothing else: no error, no
# placeholder, and a page that looks like its own layout rather than a failure.
#
# Each of these counts colours on the page bitmap, because the text layer says
# nothing about an image and a request-only assertion passes with the binary
# deleted. Each fixture carries nothing but its image, so a dropped decode is
# not a faint page, it is an entirely white one.
#
# Counted with floor=0, which the other colour tests do not do. The default drops
# any colour with under twenty samples, and a photographic gradient is tens of
# thousands of colours with a handful of samples each; filtered that way, a fully
# drawn JPEG 2000 page reports as 86% white. The floor exists to ignore
# antialiased edges, and here it would ignore the picture.
#
# The bar is low on purpose. Ink covers 68% of the JBIG2 page, which is drawn
# inverted, and 5% of the CCITT one, which is black line art on white. Both are
# correct, so the only threshold that means the same thing for all three is one
# that separates "something decoded" from a blank sheet.

@pytest.mark.parametrize("fixture,name", [
    ("jpx.pdf", "JPEG 2000"),
    ("jbig2.pdf", "JBIG2"),
    ("ccitt.pdf", "CCITT fax"),
])
def test_an_image_needing_a_wasm_decoder_is_drawn(viewer, page, fixture, name):
    viewer("pdf.html", fixture)
    wait_for_pdf(page, pages=1)

    colours = page_colours(page, floor=0)
    assert colours, f"nothing was drawn for {fixture}"
    ink = sum(n for c, n in colours.items() if c != "255,255,255")
    drawn = ink / sum(colours.values())
    assert drawn > 0.02, (
        f"the {name} image was dropped: the page is {1 - drawn:.1%} white. "
        "Check lib/wasm ships and that vwWithAssets sets wasmUrl"
    )


def test_the_wasm_decoders_are_actually_fetched_and_found(viewer, page, server):
    """
    Asked for *and* answered, for the same reason the CMap test is written this
    way: pdf.js asks either way, so checking the request alone still passes with
    the binaries deleted, which is the regression this exists to catch.
    """
    viewer("pdf.html", "jpx.pdf")
    wait_for_pdf(page)

    asked = [r for r in server.state.requests if "/wasm/" in r["path"]]
    assert asked, "no decoder was requested; the image was drawn some other way"

    found = server.served("/wasm/")
    assert found, (
        "every decoder request was refused: "
        f"{[(r['path'], r['status']) for r in asked][:3]}"
    )
    assert all(r["bytes"] > 0 for r in found)


# ---------------------------------------------------------------------------
# Every page is laid out at one width, which is correct and is not issue #20
# ---------------------------------------------------------------------------

def test_pages_of_different_paper_sizes_render_to_one_width(viewer, page):
    """
    This was written expecting the fix for issue #20 to turn it red. It did not,
    and that is the interesting part.

    A3 and A4 laying out to the same CSS width was thought to be the blur,
    because an A3 page then carries half the document-space resolution. On
    screen it makes no difference: both boxes are the same width, so both are
    shown across the same device pixels and are equally sharp. Page size never
    entered it. The blur was that a page is rasterised once, which
    test_pdf_tiles.py now covers.

    So this stays as it is, proving the layout it always proved, with the wrong
    reason taken off it.
    """
    viewer("pdf.html", "mixed-width.pdf")
    wait_for_pdf(page, pages=2)
    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)")
    # A box takes its own page's shape as that page is drawn
    wait_for_page(page, 1)
    widths = page.evaluate(
        "() => [...document.querySelectorAll('#pages .pg')]"
        ".map(p => Math.round(p.getBoundingClientRect().width))"
    )
    assert widths[0] == widths[1], f"page widths diverged: {widths}"


# ---------------------------------------------------------------------------
# Ranged loading
# ---------------------------------------------------------------------------

def test_a_ranged_document_is_pulled_in_pieces(viewer, page, server, big_pdf):
    """
    disableStream and disableAutoFetch are not the same switch, and leaving
    either out makes pdf.js read the whole file anyway with the range requests
    on top as pure extra work.
    """
    viewer("pdf.html", big_pdf, ranged=True)
    wait_for_pdf(page)

    assert server.ranged_requests(), "nothing was fetched by range"


def test_a_ranged_document_reads_far_less_than_all_of_itself(viewer, page, server, big_pdf):
    """
    The measurement behind the switches: against a 20 MB file the discovery
    request delivered 100% of the bytes without disableStream, and under 5%
    with it. This asserts the shape of that, not the exact figure.
    """
    viewer("pdf.html", big_pdf, ranged=True)
    wait_for_pdf(page)

    ranged = len(server.ranged_requests())
    whole = len(server.full_requests())
    assert ranged > whole, (
        f"{whole} whole-document reads against {ranged} ranged ones; "
        "disableStream or disableAutoFetch has stopped taking effect"
    )


def test_a_ranged_document_still_shows_its_text(viewer, page, big_pdf):
    viewer("pdf.html", big_pdf, ranged=True)
    wait_for_pdf(page)
    wait_for_text_layer(page)
    assert "Section 1" in text_layer(page)


# ---------------------------------------------------------------------------
# Failing well
# ---------------------------------------------------------------------------

def test_a_file_that_is_not_a_pdf_says_so(viewer, page):
    viewer("pdf.html", "not-a-pdf.pdf")
    page.wait_for_function(
        "() => { const e = document.getElementById('vw-status');"
        "return e && e.className.indexOf('vw-error') >= 0; }",
        timeout=20000,
    )
    assert "not a PDF" in status_text(page)


def test_a_document_that_cannot_be_read_says_so(viewer, page):
    viewer("pdf.html", "six-pages.pdf", status=404)
    page.wait_for_function(
        "() => { const e = document.getElementById('vw-status');"
        "return e && e.className.indexOf('vw-error') >= 0; }",
        timeout=20000,
    )
    assert status_text(page)


# ---------------------------------------------------------------------------
# Password protected documents
# ---------------------------------------------------------------------------

def test_an_encrypted_document_asks_for_its_password(viewer, page):
    viewer("pdf.html", "encrypted.pdf")
    page.wait_for_selector("#vw-pw", timeout=20000)
    assert "password" in status_text(page).lower()


def test_the_wrong_password_says_so_out_loud(viewer, page):
    viewer("pdf.html", "encrypted.pdf")
    page.wait_for_selector("#vw-pw", timeout=20000)
    page.fill("#vw-pw", "not the password")
    page.click(".vw-ask-btn")

    page.wait_for_selector("#vw-pw-alert", timeout=20000)
    # role=alert, so a screen reader hears it without the focus moving
    assert page.get_attribute("#vw-pw-alert", "role") == "alert"
    assert page.get_attribute("#vw-pw", "aria-describedby") == "vw-pw-alert"


def test_the_right_password_opens_the_document(viewer, page):
    viewer("pdf.html", "encrypted.pdf")
    page.wait_for_selector("#vw-pw", timeout=20000)
    page.fill("#vw-pw", "gander")
    page.click(".vw-ask-btn")

    wait_for_pdf(page, timeout=30000)
    # And the password is not left lying in the DOM
    assert page.query_selector("#vw-pw") is None


def test_a_retry_after_a_wrong_password_still_opens(viewer, page):
    viewer("pdf.html", "encrypted.pdf")
    page.wait_for_selector("#vw-pw", timeout=20000)
    page.fill("#vw-pw", "wrong")
    page.click(".vw-ask-btn")
    page.wait_for_selector("#vw-pw-alert", timeout=20000)

    page.fill("#vw-pw", "gander")
    page.click(".vw-ask-btn")
    wait_for_pdf(page, timeout=30000)


# ---------------------------------------------------------------------------
# The too-old-WebView card, which Kotlin decides and the page words
# ---------------------------------------------------------------------------

def test_an_old_engine_is_told_to_update_it(viewer, page):
    viewer("pdf.html", "six-pages.pdf", webview=110, needs=125)
    page.wait_for_selector(".vw-error", timeout=15000)
    said = status_text(page)
    assert "110" in said and "125" in said
    assert "updating android system webview" in said.lower()


def test_the_card_outlasts_the_renderer_failing_to_parse(viewer, page):
    """
    Issue #31, WebView 64. The module is fetched even with the card up, and an
    engine that old cannot parse it. The failure lands after the card is drawn,
    and it replaced the card with "Unexpected token .". The browser here parses
    pdf.js, so the file is swapped for one that no engine can.
    """
    page.route(
        "**/assets/viewer/lib/pdf.min.mjs",
        lambda route: route.fulfill(content_type="text/javascript", body="export const x = ;"),
    )
    with page.expect_event("pageerror"):
        viewer("pdf.html", "six-pages.pdf", webview=64, needs=125)
    said = status_text(page)
    assert "64" in said and "125" in said
    assert "updating android system webview" in said.lower()


def test_a_locked_engine_is_not_told_to_update_what_it_cannot(viewer, page):
    """
    On a Huawei device the provider cannot be replaced. Telling those readers
    to install Android System WebView is the one thing the card must not do.
    """
    viewer("pdf.html", "six-pages.pdf", webview=110, needs=125, locked=1)
    page.wait_for_selector(".vw-error", timeout=15000)
    said = status_text(page)
    assert "cannot be" in said.lower()
    assert "Updating Android System WebView" not in said


def test_a_locked_engine_with_no_readable_version_still_blocks(viewer, page):
    """The flag arrives on its own, which is why the page gates on either one."""
    viewer("pdf.html", "six-pages.pdf", needs=125, locked=1)
    page.wait_for_selector(".vw-error", timeout=15000)
    assert status_text(page)


def test_a_current_engine_is_left_alone(viewer, page):
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    assert not status_visible(page)


# ---------------------------------------------------------------------------
# Page virtualisation: the 1.12 memory rework
# ---------------------------------------------------------------------------

def test_only_a_band_of_pages_is_ever_drawn(viewer, page):
    """
    A 357-page rulebook took the renderer past 900 MB and was killed. Pages are
    drawn near the viewport and released outside a wider band, so the whole
    document is never on the heap at once.

    An undrawn canvas is the HTML default 300x150; a drawn one is the page
    width across. That is how the two are told apart here.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page, pages=40)
    wait_for_band(page)

    drawn = drawn_count(page)
    assert drawn >= 1
    assert drawn < 12, f"{drawn} of 40 pages drawn at once; the band is not holding"


def test_scrolling_away_releases_the_pages_left_behind(viewer, page):
    """
    Released rather than merely undrawn: the canvas backing store is dropped to
    zero and the decoded artwork with it, which is the half of the 1.12 rework
    that actually freed the memory.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page, pages=40)
    wait_for_band(page)
    assert released_count(page) == 0

    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)")
    page.wait_for_function(
        "() => document.querySelector('#pages .pg canvas').width === 0", timeout=25000
    )

    assert released_count(page) >= 1, "nothing was released on the way down"
    assert drawn_count(page) < 12, "the band grew while scrolling"
    # and the far end is now the drawn part
    assert canvas_widths(page)[-1] > 300


def test_the_band_keeps_a_page_you_scroll_back_over(viewer, page):
    """
    Two bands, not one. A page is drawn at the inner one and released only
    outside the wider one, so scrolling down a screen and back up does not
    throw away the page you just left and rebuild it.
    """
    viewer("pdf.html", "forty-pages.pdf")
    wait_for_pdf(page, pages=40)
    wait_for_band(page)

    # The observers that move the band report on the frame after a scroll lands, so
    # each move is given the frames it would take for a page to be let go.
    page.evaluate("() => window.scrollBy(0, window.innerHeight)")
    after_frames(page, 3)
    page.evaluate("() => window.scrollBy(0, -window.innerHeight)")
    after_frames(page, 3)

    assert canvas_widths(page)[0] > 300, "the first page was thrown away and rebuilt"
