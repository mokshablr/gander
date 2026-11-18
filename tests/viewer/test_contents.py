"""
A document's contents list, sent to the app to list, issue #79: a PDF's outline, and a Word
document's headings.

The app reads the message in PortMessage.kt and lists it natively, so what is checked here is the
message: one line per entry, each one entry's depth, page and title, and nothing at all for a
document with no outline or headings to show.
"""

import pytest

from helpers import wait_for_pdf


def _pdf(target, pages, outline):
    """
    [outline] is (title, level, page) entries. Each has a destination of its own, since reportlab
    keeps one outline entry for each and a second on the same one takes the first one's place.
    """
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas

    c = canvas.Canvas(str(target), pagesize=A4, invariant=1)
    for n in range(1, pages + 1):
        c.drawString(72, 760, f"Page {n}")
        for i, (_, _, page) in enumerate(outline):
            if page == n:
                c.bookmarkPage(f"e{i}")
        c.showPage()
    for i, (title, level, _) in enumerate(outline):
        c.addOutlineEntry(title, f"e{i}", level=level)
    c.save()
    return target


@pytest.fixture(scope="session")
def outlined_pdf(tmp_path_factory):
    return _pdf(tmp_path_factory.mktemp("outlined") / "outlined.pdf", 6, [
        ("Front matter", 0, 1),
        ("Chapter 1", 0, 2),
        ("Section 1.1", 1, 3),
        ("Section 1.1.1", 2, 4),
        # A tab and a line break, which would split the entry in two, come out as spaces
        ("Chapter\t2\nand after", 0, 5),
        ("فصل سوم", 0, 6),
        ("x" * 1000, 0, 6),
    ])


def contents_of(port, timeout=15.0):
    """Each entry's depth, place and title. A PDF's and a Word document's titles say no way or language."""
    message = port.wait_for(r"contents\n(?s:.*)", timeout)
    entries = []
    for line in message.split("\n")[1:]:
        depth, place, way, lang, title = line.split(" ", 4)
        assert (way, lang) == ("auto", "-"), line
        entries.append([depth, place, title])
    return entries


def test_the_outline_comes_as_one_line_an_entry_with_its_depth_and_page(viewer, page, port, outlined_pdf):
    viewer("pdf.html", outlined_pdf)
    wait_for_pdf(page)
    entries = contents_of(port())
    assert entries[:6] == [
        ["0", "1", "Front matter"],
        ["0", "2", "Chapter 1"],
        ["1", "3", "Section 1.1"],
        ["2", "4", "Section 1.1.1"],
        ["0", "5", "Chapter 2 and after"],
        ["0", "6", "فصل سوم"],
    ]
    assert entries[6] == ["0", "6", "x" * 300], "a long title is not cut to what a list can show"
    assert len(entries) == 7


def test_a_port_that_opens_before_the_document_has_read_still_gets_the_list(
        viewer, page, port, outlined_pdf):
    """
    The app hands the page its port when the page has finished loading, which can be either side
    of the outline being read. The test above has the port come after; this has it come first.
    """
    viewer("pdf.html", outlined_pdf)
    p = port()
    wait_for_pdf(page)
    assert len(contents_of(p)) == 7
    page.wait_for_timeout(300)
    assert len([m for m in p.messages() if m.startswith("contents")]) == 1, "the list was sent twice"


def test_a_document_with_no_outline_sends_no_list(viewer, page, port, tmp_path):
    plain = _pdf(tmp_path / "plain.pdf", 3, [])
    viewer("pdf.html", plain)
    wait_for_pdf(page)
    p = port()
    p.wait_for(r"page 1 3")
    page.wait_for_timeout(500)
    assert not [m for m in p.messages() if m.startswith("contents")]


def test_one_page_sends_no_list_since_it_has_nowhere_to_go(viewer, page, port, tmp_path):
    single = _pdf(tmp_path / "single.pdf", 1, [("Only", 0, 1)])
    viewer("pdf.html", single)
    wait_for_pdf(page)
    p = port()
    page.wait_for_timeout(800)
    assert not [m for m in p.messages() if m.startswith("contents")]


# ---------------------------------------------------------------------------
# A Word document's headings, sent the same way
# ---------------------------------------------------------------------------

_W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'

# Heading 1 to 3 as Word writes them, a style of the document's own based on Heading 2 that sets
# nothing of its own and so is a heading too, one based on Heading 2 that sets body text's level
# and so is not, and two plain styles, one with paragraph settings and one without
_STYLES = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles {_W}>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
  <w:pPr><w:keepNext/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/>
  <w:pPr><w:keepNext/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/>
  <w:pPr><w:keepNext/><w:outlineLvl w:val="2"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="ChapterTwo"><w:name w:val="Chapter Two"/><w:basedOn w:val="Heading2"/></w:style>
<w:style w:type="paragraph" w:styleId="Aside"><w:name w:val="Aside"/><w:basedOn w:val="Heading2"/>
  <w:pPr><w:outlineLvl w:val="9"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/>
  <w:pPr><w:ind w:left="720"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="ListBullet"><w:name w:val="List Bullet"/><w:basedOn w:val="Normal"/></w:style>
</w:styles>"""


def _word(target, body):
    """
    [body] is ("h", level, text), ("p", text), ("style", style id, text), ("level", style id or
    None, outline level, text) for a level set on the paragraph itself, ("cut", style id, text,
    text) for a paragraph a page break inside it cuts in two, and ("break",), in order.

    Written out by hand rather than with python-docx, which the viewer tests do not install.
    """
    import zipfile
    from xml.sax.saxutils import escape

    def para(style, text, level=None):
        inner = (f'<w:pStyle w:val="{style}"/>' if style else "") + \
            (f'<w:outlineLvl w:val="{level}"/>' if level is not None else "")
        props = f"<w:pPr>{inner}</w:pPr>" if inner else ""
        return f"<w:p>{props}<w:r><w:t xml:space=\"preserve\">{escape(text)}</w:t></w:r></w:p>"

    paras = []
    for item in body:
        if item[0] == "h":
            paras.append(para(f"Heading{item[1]}", item[2]))
        elif item[0] == "style":
            paras.append(para(item[1], item[2]))
        elif item[0] == "level":
            paras.append(para(item[1], item[3], item[2]))
        elif item[0] == "cut":
            paras.append(f'<w:p><w:pPr><w:pStyle w:val="{item[1]}"/></w:pPr><w:r><w:t>{escape(item[2])}</w:t>'
                         f'<w:br w:type="page"/><w:t>{escape(item[3])}</w:t></w:r></w:p>')
        elif item[0] == "p":
            paras.append(para(None, item[1]))
        else:
            paras.append('<w:p><w:r><w:br w:type="page"/></w:r></w:p>')
    document = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<w:document {_W}><w:body>'
                + "".join(paras)
                + '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
                '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>'
                "</w:body></w:document>")
    with zipfile.ZipFile(target, "w") as z:
        z.writestr("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>""")
        z.writestr("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""")
        z.writestr("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""")
        z.writestr("word/document.xml", document)
        z.writestr("word/styles.xml", _STYLES)
    return target


def open_word(viewer, page, path):
    from helpers import wait_until_done

    viewer("docx.html", path)
    wait_until_done(page, timeout=25000)


def test_a_word_documents_headings_are_its_contents(viewer, page, port, tmp_path):
    path = _word(tmp_path / "headed.docx", [
        ("p", "A line before any heading."),
        ("h", 2, "Introduction"),
        ("p", "Words."),
        # Styled, and not as a heading
        ("style", "Quote", "A quotation."),
        ("style", "ListBullet", "A point."),
        ("style", "Aside", "Set like a heading, and body text."),
        ("break",),
        ("h", 3, "Background"),
        ("h", 3, "   "),
        ("p", "More words."),
        ("break",),
        ("style", "ChapterTwo", "Results, in a style of its own"),
        # A level on the paragraph alone, as some writers set every heading, and a heading's
        # style set back to body text on one paragraph
        ("level", None, 2, "Set on the paragraph"),
        ("level", "Heading2", 9, "A heading style made body text"),
        ("p", "Last words."),
    ])
    open_word(viewer, page, path)
    # Heading 2 is the highest level used, so it is the top of the list
    assert contents_of(port()) == [
        ["0", "1", "Introduction"],
        ["1", "2", "Background"],
        ["0", "3", "Results, in a style of its own"],
        ["1", "3", "Set on the paragraph"],
    ]


def test_a_word_document_with_no_headings_sends_no_list(viewer, page, port, tmp_path):
    path = _word(tmp_path / "plain.docx", [("p", "One."), ("break",), ("p", "Two.")])
    open_word(viewer, page, path)
    p = port()
    p.wait_for(r"page 1 2")
    page.wait_for_timeout(500)
    assert not [m for m in p.messages() if m.startswith("contents")]


def test_a_heading_a_page_cuts_in_two_is_listed_once(viewer, page, port, tmp_path):
    path = _word(tmp_path / "cut.docx", [
        ("h", 1, "Opening"),
        ("p", "Words."),
        ("cut", "Heading1", "Before the break", "after it"),
        ("p", "More words."),
    ])
    open_word(viewer, page, path)
    assert contents_of(port()) == [["0", "1", "Opening"], ["0", "1", "Before the break"]]
