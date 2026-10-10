"""prose.html: the OpenDocument text and Rich Text readers, one behaviour at a time.

test_prose.py proves the page on three fixtures that carry one document. Each test here
is the smallest file that shows one thing a reader must do, written for that test:
Rich Text is text, so it is written out in the test, and an OpenDocument file is a zip,
so it is built in the test with zipfile. The expected results are LibreOffice's reading
of the same files, except where a docstring says otherwise.
"""

import io
import zipfile

import pytest

from helpers import status_text


def drawn(page):
    """Waits for the reader to finish, with a document or with the card."""
    page.wait_for_function(
        "() => { const s = document.getElementById('vw-status');"
        "return !!s && (s.style.display === 'none' || s.classList.contains('vw-error')); }",
        timeout=15000,
    )


def open_rtf(viewer, page, made, text, name="t.rtf"):
    viewer("prose.html", made(name, text.encode("latin-1")))
    drawn(page)
    assert not page.query_selector(".vw-error"), status_text(page)


def paper_text(page):
    return page.evaluate("() => document.querySelector('#container').innerText")


def note_text(page):
    return page.evaluate("() => document.querySelector('.vw-notes').innerText")


# ------------------------------------------------------------------------------------
# Rich Text: footnotes
# ------------------------------------------------------------------------------------

def test_a_footnote_decodes_its_escaped_bytes(viewer, page, made):
    """
    A byte written as \\'hh was decoded only in the body, so in a footnote it was dropped:
    "caf\\'e9" read "caf". Being dropped, it also never used up the fallback a \\u
    character leaves behind it, so the character after the fallback went instead.
    """
    open_rtf(viewer, page, made,
             r"{\rtf1\ansi\ansicpg1252\deff0{\fonttbl{\f0\froman Times New Roman;}}"
             r"\pard Body{\super\chftn}{\footnote\pard{\super\chftn} Note caf\'e9 na\'efve,"
             r" \u233\'e9 x\par}\par}")
    notes = note_text(page)
    assert "Note café naïve" in notes
    assert "é x" in notes


def test_a_backslash_before_a_line_end_ends_a_paragraph_in_a_footnote(viewer, page, made):
    """A backslash and a line end is \\par, in a footnote as in the body."""
    open_rtf(viewer, page, made,
             "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0\\froman Times New Roman;}}"
             "\\pard Body\\chftn{\\footnote\\pard\\chftn First line\\\nSecond line\\par}\\par}")
    paragraphs = page.evaluate(
        "() => [...document.querySelectorAll('.vw-notes p')].map(p => p.textContent.trim())"
    )
    assert paragraphs == ["1 First line", "Second line"]


# ------------------------------------------------------------------------------------
# Rich Text: sections
# ------------------------------------------------------------------------------------

RTF_HEAD = r"{\rtf1\ansi\deff0{\fonttbl{\f0\froman Times New Roman;}}"


def sheet_words(page):
    """The words on each sheet, sheet by sheet."""
    return page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper > section')]"
        ".map(s => s.querySelector('.vw-body').innerText.split(/\\s+/).filter(Boolean))"
    )


@pytest.mark.parametrize("sections, sheets", [
    (r"\sectd\sbknone\pard One\par\sect\sectd\pard Two\par", [["One"], ["Two"]]),
    (r"\sectd\pard One\par\sect\sectd\sbknone\pard Two\par", [["One", "Two"]]),
], ids=["first-runs-on", "second-runs-on"])
def test_a_section_break_is_the_kind_the_new_section_asks_for(viewer, page, made, sections, sheets):
    """
    Word and LibreOffice write a section's \\sbk after the \\sect that ends the one before
    it, so the break between two sections is the second one's. The first one's was used:
    a continuous first section ran the second on, and a continuous second one did not.
    """
    open_rtf(viewer, page, made, RTF_HEAD + sections + "}")
    assert sheet_words(page) == sheets


def test_a_header_set_after_a_section_break_heads_the_sheet_that_section_starts(viewer, page, made):
    """The sheet was made at \\sect, before the new section's header had been read."""
    open_rtf(viewer, page, made,
             RTF_HEAD + r"\sectd{\header\pard First head\par}\pard One\par"
             r"\sect\sectd{\header\pard Second head\par}\pard Two\par}")
    headers = page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper > section .vw-header')]"
        ".map(h => h.textContent.trim())"
    )
    assert headers == ["First head", "Second head"]


def test_a_section_break_at_the_very_end_leaves_no_empty_sheet(viewer, page, made):
    open_rtf(viewer, page, made, RTF_HEAD + r"\sectd\pard One\par\sect}")
    assert sheet_words(page) == [["One"]]


# ------------------------------------------------------------------------------------
# OpenDocument
# ------------------------------------------------------------------------------------

ODT_NS = (
    'xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
    'xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" '
    'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" '
    'xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" '
    'xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" '
    'xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" '
    'xmlns:xlink="http://www.w3.org/1999/xlink" '
    'xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" '
    'office:version="1.3"'
)


def odt(body, automatic="", styles="", layouts="", masters='<style:master-page style:name="Standard"/>',
        mimetype="application/vnd.oasis.opendocument.text"):
    """
    An .odt whose text is body. automatic is content.xml's automatic styles; styles,
    layouts and masters are styles.xml's named styles, page layouts and master pages.
    """
    content = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<office:document-content {ODT_NS}>'
        f'<office:automatic-styles>{automatic}</office:automatic-styles>'
        f'<office:body><office:text>{body}</office:text></office:body>'
        '</office:document-content>'
    )
    named = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<office:document-styles {ODT_NS}><office:styles>'
        '<style:default-style style:family="paragraph">'
        '<style:text-properties fo:font-size="12pt"/></style:default-style>'
        f'<style:style style:name="Standard" style:family="paragraph"/>{styles}'
        f'</office:styles><office:automatic-styles>{layouts}</office:automatic-styles>'
        f'<office:master-styles>{masters}</office:master-styles></office:document-styles>'
    )
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), mimetype)
        z.writestr("content.xml", content, zipfile.ZIP_DEFLATED)
        z.writestr("styles.xml", named, zipfile.ZIP_DEFLATED)
    return out.getvalue()


def open_odt(viewer, page, made, body, name="t.odt", **parts):
    viewer("prose.html", made(name, odt(body, **parts)))
    drawn(page)
    assert not page.query_selector(".vw-error"), status_text(page)


def test_the_title_of_a_table_of_contents_is_drawn(viewer, page, made):
    """
    An index keeps its title inside its body, in an index-title of its own, and only the
    entries under the title were read. The template in the index's source is not drawn:
    it is what a word processor would title the index if it were made again.
    """
    open_odt(viewer, page, made,
             '<text:table-of-content text:name="Contents1">'
             '<text:table-of-content-source text:outline-level="10">'
             '<text:index-title-template>Template title</text:index-title-template>'
             '</text:table-of-content-source><text:index-body>'
             '<text:index-title text:name="Contents1_Head"><text:p>Contents of the survey</text:p>'
             '</text:index-title><text:p>Intro<text:tab/>1</text:p>'
             '</text:index-body></text:table-of-content>'
             '<text:h text:outline-level="1">Intro</text:h>')
    lines = [line for line in paper_text(page).split("\n") if line.strip()]
    assert lines == ["Contents of the survey", "Intro\t1", "Intro"]


# ------------------------------------------------------------------------------------
# Raised and lowered text
# ------------------------------------------------------------------------------------

def font_size(page, text):
    """The drawn size, in CSS pixels, of the innermost element whose text is text."""
    return page.evaluate(
        "(text) => { const all = [...document.querySelectorAll('.vw-paper span, .vw-paper p')];"
        "const el = all.reverse().find(e => e.textContent === text);"
        "return parseFloat(getComputedStyle(el).fontSize); }",
        text,
    )


def test_an_opendocument_superscript_with_a_size_of_its_own_is_drawn_at_its_share_of_it(viewer, page, made):
    """
    LibreOffice writes a superscript as a position and a share of the size, super 58%,
    and when the text came from Word it writes the full size beside them. The share was
    only taken when there was no size, so that superscript was drawn full size.
    """
    open_odt(viewer, page, made,
             '<text:p>Area in m<text:span text:style-name="T1">2</text:span></text:p>',
             automatic='<style:style style:name="T1" style:family="text"><style:text-properties'
                       ' style:text-position="super 58%" fo:font-size="12pt"/></style:style>')
    share = font_size(page, "2") / font_size(page, "Area in m2")
    assert abs(share - 0.58) < 0.01


# ------------------------------------------------------------------------------------
# The rest of the family: a template, and a whole document in one XML file
# ------------------------------------------------------------------------------------

def test_a_template_opens_as_the_document_it_would_make(viewer, page, made):
    """
    An .ott is an .odt whose mimetype entry says template. The reader goes by what is in
    the zip, so a check of that entry, to turn away a spreadsheet sent as an .odt, must
    leave a template through.
    """
    viewer("prose.html", made("t.ott", odt(
        "<text:p>Minutes of the meeting</text:p>",
        mimetype="application/vnd.oasis.opendocument.text-template",
    )))
    drawn(page)
    assert not page.query_selector(".vw-error"), status_text(page)
    assert paper_text(page).strip() == "Minutes of the meeting"


FLAT_ODT = (
    '<?xml version="1.0" encoding="UTF-8"?>'
    f'<office:document {ODT_NS} office:mimetype="application/vnd.oasis.opendocument.text">'
    '<office:styles>'
    '<style:default-style style:family="paragraph"><style:text-properties fo:font-size="12pt"/>'
    '</style:default-style>'
    '<style:style style:name="Standard" style:family="paragraph"/>'
    '<style:style style:name="Loud" style:family="paragraph" style:parent-style-name="Standard">'
    '<style:text-properties fo:font-size="18pt" fo:font-weight="bold"/></style:style>'
    '</office:styles>'
    '<office:automatic-styles>'
    '<style:style style:name="P1" style:family="paragraph" style:parent-style-name="Standard">'
    '<style:text-properties fo:font-style="italic"/></style:style>'
    '</office:automatic-styles>'
    '<office:master-styles><style:master-page style:name="Standard"/></office:master-styles>'
    '<office:body><office:text>'
    '<text:p text:style-name="Loud">Flat heading</text:p>'
    '<text:p text:style-name="P1">Flat aside</text:p>'
    '<text:p text:style-name="Standard">Flat body</text:p>'
    '</office:text></office:body></office:document>'
)


def test_a_flat_document_takes_its_styles_from_the_one_file(viewer, page, made):
    """
    A .fodt is an .odt's parts in a single XML file, office:document, rather than a zip:
    the named styles, the automatic ones and the text side by side.
    """
    viewer("prose.html", made("t.fodt", FLAT_ODT.encode()))
    drawn(page)
    assert not page.query_selector(".vw-error"), status_text(page)
    lines = [line for line in paper_text(page).split("\n") if line.strip()]
    assert lines == ["Flat heading", "Flat aside", "Flat body"]
    assert abs(font_size(page, "Flat heading") / font_size(page, "Flat body") - 1.5) < 0.01
    looks = page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper p')].map(p => {"
        "const s = getComputedStyle(p); return [p.textContent, s.fontWeight, s.fontStyle]; })"
    )
    assert ["Flat heading", "700", "normal"] in looks
    assert ["Flat aside", "400", "italic"] in looks


def test_a_rich_text_superscript_is_smaller_whatever_size_its_text_is(viewer, page, made):
    """
    \\super was drawn smaller only when the text was the default 12 points, since any
    other size was written into the run and the superscript kept it.
    """
    open_rtf(viewer, page, made, RTF_HEAD + r"\pard\plain\fs22 Area in m{\super 2}\par}")
    share = font_size(page, "2") / font_size(page, "Area in m")
    assert abs(share - 0.58) < 0.01


# ------------------------------------------------------------------------------------
# Rich Text: tables
# ------------------------------------------------------------------------------------

def test_a_table_inside_a_table_is_drawn_inside_its_cell(viewer, page, made):
    """
    Word writes the cells of a table inside a table with \\nestcell, and each of its rows'
    definitions after the row, inside \\nesttableprops. \\nestcell was read as \\cell, so
    the inner table's cells became more cells of the outer table's row.
    """
    nested_row = (r"\pard\intbl\itap2 N1\nestcell N2\nestcell"
                  r"{\*\nesttableprops\trowd\cellx2000\cellx3500\nestrow}{\nonesttables\par}")
    open_rtf(viewer, page, made,
             RTF_HEAD + r"\trowd\cellx4000\cellx8000\pard\intbl Outer A1\par"
             + nested_row + nested_row.replace("N1", "N3").replace("N2", "N4")
             + r"\pard\intbl\itap1 after nested\cell Outer B1\cell"
             r"\pard\intbl{\trowd\cellx4000\cellx8000\row}\pard After table\par}")
    outer = page.evaluate(
        "() => { const row = document.querySelector('.vw-body > table > tbody > tr');"
        "const cells = [...row.children];"
        "return { first: [...cells[0].children].map(e => e.tagName),"
        "  inner: [...cells[0].querySelectorAll('tr')].map(tr =>"
        "    [...tr.children].map(td => td.textContent.trim())),"
        "  text: cells.map(td => td.innerText.trim().split(/\\s+/).join(' ')) }; }"
    )
    assert outer["text"] == ["Outer A1 N1 N2 N3 N4 after nested", "Outer B1"]
    assert outer["first"] == ["P", "TABLE", "P"]
    assert outer["inner"] == [["N1", "N2"], ["N3", "N4"]]
    assert paper_text(page).rstrip().endswith("After table")


def test_a_page_break_straight_after_a_table_leaves_the_table_where_it_was(viewer, page, made):
    """
    A table is only put on the page when something outside it comes along, and \\page
    did not count: the table waited, and went onto the sheet the break had just made.
    """
    open_rtf(viewer, page, made,
             RTF_HEAD + r"\pard Before\par\trowd\cellx2000\pard\intbl In the table\cell\row"
             r"\pard\page After\par}")
    assert sheet_words(page) == [["Before", "In", "the", "table"], ["After"]]


def test_a_page_break_inside_a_table_row_is_passed_over(viewer, page, made):
    """A sheet cannot break a table in two, so the table stays whole where it began."""
    open_rtf(viewer, page, made,
             RTF_HEAD + r"\pard Before\par\trowd\cellx2000\cellx4000\pard\intbl A\page\cell B\cell\row"
             r"\pard After\par}")
    assert sheet_words(page) == [["Before", "A", "B", "After"]]


# ------------------------------------------------------------------------------------
# OpenDocument: pages
# ------------------------------------------------------------------------------------

PAGES = {
    "layouts": (
        '<style:page-layout style:name="pm1"><style:page-layout-properties fo:page-width="21cm"'
        ' fo:page-height="29.7cm" fo:margin="2cm"/></style:page-layout>'
        '<style:page-layout style:name="pm2"><style:page-layout-properties fo:page-width="29.7cm"'
        ' fo:page-height="21cm" style:print-orientation="landscape" fo:margin="2cm"/></style:page-layout>'
    ),
    "masters": (
        '<style:master-page style:name="Standard" style:page-layout-name="pm1"/>'
        '<style:master-page style:name="Landscape" style:page-layout-name="pm2"/>'
    ),
}

TABLE = ('<table:table table:name="T" table:style-name="T"><table:table-column/><table:table-row>'
         '<table:table-cell><text:p>In the table</text:p></table:table-cell></table:table-row></table:table>')


def sheet_shapes(page):
    return page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper > section')]"
        ".map(s => parseFloat(s.style.width) > parseFloat(s.style.minHeight) ? 'wide' : 'tall')"
    )


@pytest.mark.parametrize("table_style, shapes, words", [
    ('style:master-page-name="Landscape"><style:table-properties style:width="20cm"/>',
     ["tall", "wide", "tall"], [["Before"], ["In", "the", "table"], ["After"]]),
    ('><style:table-properties style:width="10cm" fo:break-before="page"/>',
     ["tall", "tall"], [["Before"], ["In", "the", "table", "After"]]),
    ('><style:table-properties style:width="10cm" fo:break-after="page"/>',
     ["tall", "tall"], [["Before", "In", "the", "table"], ["After"]]),
], ids=["page-style", "break-before", "break-after"])
def test_a_table_can_start_a_page_as_a_paragraph_can(viewer, page, made, table_style, shapes, words):
    """
    A table's style can name a page style, or break the page before or after it, the way
    a paragraph's can; LibreOffice puts a wide table on a landscape page that way. Only
    a paragraph's were read. The paragraph after goes back to the portrait page by
    naming it.
    """
    open_odt(viewer, page, made,
             '<text:p>Before</text:p>' + TABLE + '<text:p text:style-name="Back">After</text:p>',
             automatic='<style:style style:name="T" style:family="table" ' + table_style + '</style:style>'
                       '<style:style style:name="Back" style:family="paragraph"'
                       + (' style:master-page-name="Standard"' if "Landscape" in table_style else '')
                       + '/>',
             **PAGES)
    assert sheet_shapes(page) == shapes
    assert sheet_words(page) == words


@pytest.mark.parametrize("body", [
    TABLE + '<text:p>After</text:p>',
    '<text:h text:style-name="Wide" text:outline-level="1">In a heading</text:h><text:p>After</text:p>',
    '<draw:frame text:anchor-type="page" svg:width="5cm"><draw:text-box><text:p>Boxed</text:p>'
    '</draw:text-box></draw:frame><text:p text:style-name="Wide">After</text:p>',
], ids=["table", "heading", "frame-before-it"])
def test_the_first_sheet_is_the_page_the_first_block_names(viewer, page, made, body):
    """
    The first sheet took its page from the first text:p anywhere in the document, which
    passed over a heading or a table that came first and could be a paragraph inside
    that table. And the first block that named a page made a sheet of its own after a
    frame anchored to the page, as if it were not the first.
    """
    open_odt(viewer, page, made, body,
             automatic='<style:style style:name="T" style:family="table"'
                       ' style:master-page-name="Landscape"/>'
                       '<style:style style:name="Wide" style:family="paragraph"'
                       ' style:master-page-name="Landscape"/>',
             **PAGES)
    assert sheet_shapes(page) == ["wide"]


# ------------------------------------------------------------------------------------
# OpenDocument: what is drawn
# ------------------------------------------------------------------------------------

def test_a_hidden_section_is_not_drawn(viewer, page, made):
    """
    A section can be hidden, which LibreOffice writes as text:display="none". Being not
    drawn, it does not choose the first page either.
    """
    open_odt(viewer, page, made,
             '<text:section text:name="S1" text:display="none">'
             '<text:p text:style-name="Wide">Kept out of sight</text:p></text:section>'
             '<text:p>Shown</text:p><text:section text:name="S2" text:display="none">'
             '<text:p>Hidden too</text:p></text:section><text:p>and shown again</text:p>',
             automatic='<style:style style:name="Wide" style:family="paragraph"'
                       ' style:master-page-name="Landscape"/>',
             **PAGES)
    assert paper_text(page).split() == ["Shown", "and", "shown", "again"]
    assert sheet_shapes(page) == ["tall"]


# ------------------------------------------------------------------------------------
# OpenDocument: tables
# ------------------------------------------------------------------------------------

def table_rows(page):
    return page.evaluate(
        "() => [...document.querySelectorAll('.vw-body > table > tbody > tr')]"
        ".map(tr => [...tr.children].map(td => td.textContent))"
    )


def test_a_repeated_cell_or_row_is_drawn_as_often_as_it_says(viewer, page, made):
    """A cell or a row written once with a count of repeats was drawn once."""
    open_odt(viewer, page, made,
             '<table:table table:name="T"><table:table-column table:number-columns-repeated="3"/>'
             '<table:table-row><table:table-cell><text:p>a</text:p></table:table-cell>'
             '<table:table-cell table:number-columns-repeated="2"><text:p>b</text:p></table:table-cell>'
             '</table:table-row><table:table-row table:number-rows-repeated="2">'
             '<table:table-cell table:number-columns-repeated="3"><text:p>c</text:p></table:table-cell>'
             '</table:table-row></table:table>')
    assert table_rows(page) == [["a", "b", "b"], ["c", "c", "c"], ["c", "c", "c"]]


def test_a_row_repeated_a_million_times_is_not(viewer, page, made):
    """
    A few bytes can ask for a row a million times, which is a spreadsheet's way to reach
    the end of its sheet. The copies stop at a budget for the whole document, well short
    of taking the page down.
    """
    open_odt(viewer, page, made,
             '<table:table table:name="T"><table:table-column table:number-columns-repeated="2"/>'
             '<table:table-row table:number-rows-repeated="1000000">'
             '<table:table-cell table:number-columns-repeated="2"><text:p>x</text:p></table:table-cell>'
             '</table:table-row></table:table><text:p>After the table</text:p>')
    rows = table_rows(page)
    assert 1 < len(rows) <= 10000
    assert paper_text(page).rstrip().endswith("After the table")


# ------------------------------------------------------------------------------------
# OpenDocument: lists and white space
# ------------------------------------------------------------------------------------

def labels(page):
    return page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper .vw-label')].map(l => l.textContent.trim())"
    )


def test_a_list_can_count_from_zero(viewer, page, made):
    """A list that starts at 0 read 1, 1, 2: a count of 0 was taken for no count at all."""
    items = "".join(f"<text:list-item><text:p>{word}</text:p></text:list-item>"
                    for word in ["zero", "one", "two"])
    open_odt(viewer, page, made,
             f'<text:list text:style-name="L1">{items}</text:list>',
             automatic='<text:list-style style:name="L1"><text:list-level-style-number text:level="1"'
                       ' style:num-format="1" style:num-suffix="." text:start-value="0"/></text:list-style>')
    assert labels(page) == ["0.", "1.", "2."]


def test_a_space_after_a_tab_a_line_break_or_a_run_of_spaces_is_kept(viewer, page, made):
    """
    A tab, a line break and a run of spaces are written as elements so that the text's
    own white space can be collapsed around them, and they are not white space for that
    collapsing: a space after one is a space. It was dropped as if it followed another.
    """
    open_odt(viewer, page, made,
             '<text:p>[tab<text:tab/> x]</text:p><text:p>[br<text:line-break/> x]</text:p>'
             '<text:p>[s<text:s/> x]</text:p><text:p>[span <text:span> x</text:span>]</text:p>')
    paragraphs = page.evaluate(
        "() => [...document.querySelectorAll('.vw-body p')].map(p => p.innerText)"
    )
    assert paragraphs == ["[tab\t x]", "[br\n x]", "[s  x]", "[span x]"]


# ------------------------------------------------------------------------------------
# Rich Text: bytes
# ------------------------------------------------------------------------------------

@pytest.mark.parametrize("word, text", [("pc", "café ¢ naïve"), ("pca", "café ø naïve")],
                         ids=["437", "850"])
def test_the_ibm_pc_code_pages_are_each_their_own(viewer, page, made, word, text):
    """
    \\pc is the IBM PC's code page 437 and \\pca is 850, which was the PC's in Western
    Europe. Both were read as 866, the Russian one, the only one of the three a browser
    knows, which made "café" read "cafВ".
    """
    open_rtf(viewer, page, made,
             r"{\rtf1\%s\deff0{\fonttbl{\f0\fmodern Courier;}}\f0 caf\'82 \'9b na\'8bve\par}" % word)
    assert paper_text(page).strip() == text


def test_a_byte_order_mark_ahead_of_rich_text_is_not_text(viewer, page, made):
    """
    prose.js lets a file that starts with a UTF-8 byte order mark through as Rich Text, as
    every other program does, and the reader then drew the mark as three letters.
    """
    mark = bytes([0xEF, 0xBB, 0xBF])
    viewer("prose.html", made("t.rtf", mark + (RTF_HEAD + r"\pard Hello\par}").encode("latin-1")))
    drawn(page)
    assert paper_text(page).strip() == "Hello"


def test_a_word_for_a_character_after_a_unicode_character_is_its_fallback(viewer, page, made):
    """
    The characters that stand in for a \\u character, for readers without Unicode, can be
    a word such as \\bullet, and it was drawn as well, which made every such character
    two. Any other word ends the fallback, since a writer that leaves it out altogether
    is more likely than a \\par meant to be passed over.
    """
    open_rtf(viewer, page, made,
             RTF_HEAD + r"\uc1\pard A\u8226\bullet B\u8212\emdash C\u8220\ldblquote D\par"
             r" caf\u233\par next\par}")
    paragraphs = page.evaluate(
        "() => [...document.querySelectorAll('.vw-body p')].map(p => p.textContent)"
    )
    assert paragraphs == ["A\u2022B\u2014C\u201cD", "caf\u00e9", "next"]
