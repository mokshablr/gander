"""
Print, issue #45: the page side.

A Word or prose page is printed by the WebView, so what it prints is decided here, by the
print rules in the page. A PDF is printed from the file instead, and all its page has to say
is whether it asked for a password, which Android's printing can't open.
"""

import re
import zipfile

import pytest

from helpers import wait_until_done

ZERO = {"top": "0", "bottom": "0", "left": "0", "right": "0"}


def sheets(page, paper):
    """How many sheets the page prints on, on [paper]."""
    data = page.pdf(format=paper, margin=ZERO)
    return len(re.findall(rb"/Type\s*/Page\b", data))


def remade(made, fixture_path, name, part, change):
    """word-measured.docx, a Letter file with a footer, with [part] passed through [change]."""
    with zipfile.ZipFile(fixture_path("word-measured.docx")) as z:
        items = {i.filename: z.read(i.filename) for i in z.infolist()}
    items[part] = change(items[part])
    target = made(name, b"")
    with zipfile.ZipFile(target, "w") as z:
        for each, data in items.items():
            z.writestr(each, data)
    return target


def spaced(made, fixture_path):
    """6pt after every paragraph, so its full pages end on space after."""
    def change(xml):
        xml, changed = re.subn(
            rb'(<w:style [^>]*w:styleId="Normal"(?:(?!</w:style>).)*?)w:after="0"', rb'\1w:after="120"',
            xml, count=1, flags=re.S)
        assert changed == 1
        return xml
    return remade(made, fixture_path, "spaced.docx", "word/styles.xml", change)


def one_long_page(made, fixture_path):
    """10,500 lines, too many to measure, so they stay one page that runs on to many sheets."""
    lines = b"<w:p><w:r><w:t>Many lines</w:t></w:r></w:p>" * 10500
    return remade(made, fixture_path, "long.docx", "word/document.xml", lambda xml: re.sub(
        rb"(<w:body>).*?(<w:sectPr)", lambda m: m.group(1) + lines + m.group(2), xml, flags=re.S))


@pytest.mark.parametrize("paper", ["A4", "Letter"])
def test_an_a4_document_prints_a_sheet_per_page_on_either_paper(viewer, page, paper):
    """
    Two A4 pages. Printed on Letter, which is shorter, each page used to run on to a sheet
    of its own; each now has its own paper size, which the print fits to the paper chosen.
    """
    viewer("prose.html", "letter.odt")
    wait_until_done(page)
    assert sheets(page, paper) == 2


@pytest.mark.parametrize("paper", ["A4", "Letter"])
def test_a_letter_word_document_prints_on_one_sheet(viewer, page, paper):
    viewer("docx.html", "report.docx")
    wait_until_done(page)
    assert sheets(page, paper) == 1


@pytest.mark.parametrize("paper", ["A4", "Letter"])
def test_a_word_document_with_a_footer_prints_a_sheet_per_page(viewer, page, paper):
    """
    Six pages, each with a footer in its bottom margin. Print keeps each sheet's whole margin
    free, so every footer used to print on a sheet of its own.
    """
    viewer("docx.html", "word-pages.docx")
    wait_until_done(page)
    assert sheets(page, paper) == 6


@pytest.mark.parametrize("paper", ["A4", "Letter"])
def test_a_full_word_page_prints_on_one_sheet(viewer, page, made, fixture_path, paper):
    """
    Pages measured full (#55), each ending on a paragraph with space after it. The space held
    the page past its paper, and print gave that page's last line a sheet of its own.
    """
    viewer("docx.html", spaced(made, fixture_path))
    wait_until_done(page)
    drawn = page.evaluate("document.querySelectorAll('.docx-wrapper > section.docx').length")
    assert drawn == 7
    assert sheets(page, paper) == drawn


@pytest.mark.parametrize("paper", ["A4", "Letter"])
@pytest.mark.parametrize("fixture", ["word-measured-fit.docx", "word-measured-notes.docx", "word-measured-tails.docx"])
def test_measured_pages_print_a_sheet_each(viewer, page, fixture, paper):
    """
    Pages measured with nothing to spare (#55). A page that ran past its paper, by its lines'
    spacing, its footnotes or its tabs, printed on a sheet more, most of them blank.
    """
    viewer("docx.html", fixture)
    wait_until_done(page)
    drawn = page.evaluate("document.querySelectorAll('.docx-wrapper > section.docx').length")
    assert drawn > 3
    assert sheets(page, paper) == drawn


RUN_PAST = """past => {
  document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1');
  const sheets = document.querySelectorAll('.docx-wrapper > section.docx'), first = sheets[0];
  const paper = parseFloat(getComputedStyle(first).minHeight);
  const spacer = document.createElement('div');
  first.querySelector(':scope > article').appendChild(spacer);
  spacer.style.height = '5000px';
  const free = 5000 - (first.getBoundingClientRect().height - paper);
  spacer.style.height = (free + past) + 'px';
  for (const s of sheets) {
    s.classList.remove('vw-print-footer', 'vw-print-fit');
    s.style.removeProperty('--vw-print-pad');
  }
  vwPrintFooters(sheets);
  return first.getBoundingClientRect().height - paper;
}"""


@pytest.mark.parametrize("past,printed", [(10, 6), (32, 7)])
def test_a_page_a_little_past_its_paper_prints_on_one_sheet(viewer, page, past, printed):
    """
    Lines set in the phone's fonts can run one of Word's pages a little past its paper, and a
    page over by a line or less printed it on a sheet of its own. Here the first of six pages
    runs 10px past, about a hundredth of its paper, and then 32px, a thirtieth, which is too far.
    """
    viewer("docx.html", "word-pages.docx")
    wait_until_done(page)
    assert abs(page.evaluate(RUN_PAST, past) - past) < 1
    assert sheets(page, "Letter") == printed


def footer_at_the_edge(made, fixture_path):
    """word-pages.docx on A4 paper, with its footers at the paper's edge, filling the bottom margin."""
    with zipfile.ZipFile(fixture_path("word-pages.docx")) as z:
        items = {i.filename: z.read(i.filename) for i in z.infolist()}
    xml, sized = re.subn(rb'<w:pgSz w:w="12240" w:h="15840"/>', b'<w:pgSz w:w="11906" w:h="16838"/>',
                         items["word/document.xml"])
    xml, moved = re.subn(rb'w:footer="720"', b'w:footer="0"', xml)
    assert sized == moved == 3
    items["word/document.xml"] = xml
    target = made("footer-at-the-edge.docx", b"")
    with zipfile.ZipFile(target, "w") as z:
        for name, data in items.items():
            z.writestr(name, data)
    return target


@pytest.mark.parametrize("paper", ["A4", "Letter"])
def test_a_footer_that_fills_its_bottom_margin_prints_a_sheet_per_page(viewer, page, made, fixture_path, paper):
    """
    A4 paper is not a whole number of pixels tall, so a page that fits it reads a hair past it.
    With the footer in the whole bottom margin, that hair was more margin than the page had to
    give, so the page kept its margin in print and its footer took a sheet of its own.
    """
    viewer("docx.html", footer_at_the_edge(made, fixture_path))
    wait_until_done(page)
    assert page.evaluate("vwPages.split") is True
    assert page.evaluate("document.querySelectorAll('.docx-wrapper > section.docx').length") == 6
    assert sheets(page, paper) == 6


def test_a_printed_footer_stays_where_the_page_draws_it(viewer, page):
    viewer("docx.html", "word-pages.docx")
    wait_until_done(page)
    page.evaluate("document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1')")
    where = """() => [...document.querySelectorAll('.docx-wrapper > section.docx')].map(s => {
        const f = s.querySelector(':scope > footer').getBoundingClientRect(), top = s.getBoundingClientRect().top;
        return [f.top - top, f.bottom - top];
    })"""
    drawn = page.evaluate(where)
    page.emulate_media(media="print")
    assert page.evaluate(where) == drawn


def test_a_page_too_long_for_one_sheet_keeps_its_whole_margin(viewer, page, made, fixture_path):
    """
    It repeats its margins on every sheet it prints on, so giving its footer's part of the
    bottom margin to the page would let every sheet's text run half an inch lower.
    """
    viewer("docx.html", one_long_page(made, fixture_path))
    wait_until_done(page, timeout=25000)
    assert page.evaluate("document.querySelectorAll('.docx-wrapper > section.docx').length") == 1
    bottom = "getComputedStyle(document.querySelector('.docx-wrapper > section.docx')).paddingBottom"
    drawn = page.evaluate(bottom)
    page.emulate_media(media="print")
    assert page.evaluate(bottom) == drawn == "96px"


@pytest.mark.parametrize("html,fixture,sheet", [
    ("docx.html", "report.docx", ".docx-wrapper > section.docx"),
    ("prose.html", "letter.odt", ".vw-paper > section"),
])
def test_printed_paper_drops_what_the_screen_draws_around_it(viewer, page, html, fixture, sheet):
    """No zoom to the screen's width, no shadow, no ground, and the margins on every sheet."""
    page.emulate_media(color_scheme="dark")
    viewer(html, fixture)
    wait_until_done(page)
    page.emulate_media(media="print")
    style = page.evaluate(
        """(sel) => {
            const s = document.querySelector(sel);
            const cs = getComputedStyle(s);
            return {
                zoom: cs.zoom,
                shadow: cs.boxShadow,
                ground: getComputedStyle(s.parentElement).backgroundColor,
                body: getComputedStyle(document.body).backgroundColor,
                decoration: cs.boxDecorationBreak || cs.webkitBoxDecorationBreak,
                page: s.style.page,
            };
        }""",
        sheet,
    )
    assert style["zoom"] == "1"
    assert style["shadow"] == "none"
    assert style["ground"] in ("rgba(0, 0, 0, 0)", "transparent")
    assert style["body"] in ("rgba(0, 0, 0, 0)", "transparent")
    assert style["decoration"] == "clone"
    assert style["page"].startswith("vw-sheet-")


def test_a_pdf_with_a_password_says_it_is_locked(viewer, page, port):
    viewer("pdf.html", "encrypted.pdf")
    page.wait_for_selector("#vw-pw", timeout=15000)
    channel = port()
    assert channel.wait_for(r"locked") == "locked"


def test_a_pdf_without_one_never_does(viewer, page, port):
    viewer("pdf.html", "six-pages.pdf")
    channel = port()
    channel.wait_for(r"page \d+ \d+")
    page.wait_for_timeout(300)
    assert "locked" not in channel.messages()


@pytest.mark.parametrize("paper", ["A4", "Letter"])
def test_a_deck_prints_a_slide_to_a_sheet(viewer, page, paper):
    """Each on a sheet of its own size, fitted to the paper, with no ground, gap or shadow around it."""
    viewer("pptx.html", "deck.pptx")
    wait_until_done(page)
    slides = page.evaluate("document.querySelectorAll('#result .slide').length")
    assert slides > 1
    page.emulate_media(media="print")
    style = page.evaluate("""() => {
        const s = getComputedStyle(document.querySelector('#result .slide'));
        return { shadow: s.boxShadow, margin: s.marginTop, page: document.querySelector('#result .slide').style.page };
    }""")
    assert style == {"shadow": "none", "margin": "0px", "page": style["page"]}
    assert style["page"].startswith("vw-sheet-")
    assert sheets(page, paper) == slides


@pytest.mark.parametrize("html,fixture,ink", [
    ("md.html", "notes.md", "#content"),
    ("text.html", "plain.txt", "#content"),
])
def test_markdown_and_text_print_black_on_white_from_a_dark_phone(viewer, page, html, fixture, ink):
    page.emulate_media(color_scheme="dark")
    viewer(html, fixture)
    wait_until_done(page)
    page.emulate_media(media="print", color_scheme="dark")
    colours = page.evaluate("""(sel) => ({
        ink: getComputedStyle(document.querySelector(sel)).color,
        body: getComputedStyle(document.body).backgroundColor,
    })""", ink)
    # Paper, not the dark ground the screen gives them
    assert colours["body"] in ("rgba(0, 0, 0, 0)", "transparent", "rgb(255, 255, 255)")
    # Dark ink: every channel well under the middle
    assert all(int(c) < 80 for c in colours["ink"][4:-1].split(", ")[:3])
    assert sheets(page, "A4") >= 1


def test_a_text_files_show_more_button_stays_off_paper(viewer, page, made):
    viewer("text.html", made("long.txt", b"line\n" * 1_200_000))
    page.wait_for_selector("#moreBtn", state="visible", timeout=20000)
    page.emulate_media(media="print")
    assert page.evaluate("getComputedStyle(document.getElementById('more')).display") == "none"
