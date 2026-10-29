"""
layout.xlsx edited to read as Excel writes a workbook where openpyxl writes it otherwise: the quotes
in a currency format as entities, Excel 2010's own reading of a data bar, two tables in different
styles, and a table style of the workbook's own. Then what real workbooks hold that the fixture does
not: a date past Excel's last day, a long integer in a narrow column, a formula's empty text beside a
title, a merge whose first column is hidden, and rows set short by hand under turned or large text.
"""

import io
import re
import zipfile

import pytest

from server import FIXTURES
from test_xlsx import LOOK, rgb
from test_xlsx_layout import BAR, CELL, opened, tab

R = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
X14 = 'xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main"'


def excel_wrote(made, edits):
    """layout.xlsx with each part in [edits] put through its function, a new part where it had none"""
    with zipfile.ZipFile(FIXTURES / "layout.xlsx") as z:
        parts = {n: z.read(n) for n in z.namelist()}
    for part, edit in edits.items():
        parts[part] = edit(parts[part].decode("utf-8") if part in parts else "").encode("utf-8")
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in parts.items():
            z.writestr(name, data)
    return made("excel.xlsx", out.getvalue())


def once(text, old, new):
    assert text.count(old) == 1, old
    return text.replace(old, new)


def test_a_currency_format_keeps_its_red_for_a_negative_where_excel_writes_its_quotes_as_entities(viewer, page, made):
    """Excel's "$"#,##0.00_);[Red]("$"#,##0.00) is &quot;$&quot;... in the file, and each ; in &quot; read
    raw cut the format into sections that are not there, so the negative one lost its red"""
    path = excel_wrote(made, {"xl/styles.xml": lambda x: once(x, 'formatCode="#,##0;[Red]-#,##0"',
                       'formatCode="&quot;$&quot;#,##0.00_);[Red]\\(&quot;$&quot;#,##0.00\\)"')})
    opened(viewer, page, path)
    assert page.evaluate(LOOK, "E16")["ink"] == "rgb(255, 0, 0)"
    assert page.evaluate(LOOK, "F16")["ink"] == "rgb(0, 0, 0)"


def test_a_data_bar_is_drawn_as_excel_2010_reads_it_from_nought_to_the_whole_cell(viewer, page, made):
    """Newer Excel keeps a bar's real ends and lengths in an extension; 10 to 60 run 17% to 100%"""
    guid = "{6E3E2B2C-1A9B-4C3E-9D5F-000000000001}"

    def rules(x):
        x = once(x, '<cfRule type="dataBar" priority="4"><dataBar><cfvo type="min"/><cfvo type="max"/><color rgb="FF638EC6"/></dataBar></cfRule>',
                 '<cfRule type="dataBar" priority="4"><dataBar><cfvo type="min"/><cfvo type="max"/><color rgb="FF638EC6"/></dataBar>'
                 f'<extLst><ext uri="{{B025F937-C7B1-47D3-B67F-A62EFF666E3E}}" {X14}><x14:id>{guid}</x14:id></ext></extLst></cfRule>')
        return once(x, "</worksheet>",
                    f'<extLst><ext uri="{{78C0D931-6437-407d-A8EE-F0AAD7539E65}}" {X14}><x14:conditionalFormattings>'
                    '<x14:conditionalFormatting xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main">'
                    f'<x14:cfRule type="dataBar" id="{guid}"><x14:dataBar minLength="0" maxLength="100" gradient="0">'
                    '<x14:cfvo type="autoMin"/><x14:cfvo type="autoMax"/><x14:negativeFillColor rgb="FFFF0000"/>'
                    '<x14:axisColor rgb="FF000000"/></x14:dataBar></x14:cfRule><xm:sqref>D1:D6</xm:sqref>'
                    "</x14:conditionalFormatting></x14:conditionalFormattings></ext></extLst></worksheet>")

    opened(viewer, page, excel_wrote(made, {"xl/worksheets/sheet7.xml": rules}))
    tab(page, "Rules")
    bars = page.evaluate("() => ['D1', 'D3', 'D6'].map(a => vwCellAt(a).style.getPropertyValue('--vw-bar'))")
    assert bars == ["17%", "50%", "100%"]


def test_two_tables_in_different_styles_each_draw_in_their_own(viewer, page, made):
    """The looks a table gives were kept for the workbook under keys that did not say which table"""
    def sheet(x):
        for r, cells in ((1, ("Item", "Cost")), (2, ("Tea", "Three")), (3, ("Bread", "Two"))):
            add = "".join(f'<c r="{col}{r}" t="inlineStr"><is><t>{v}</t></is></c>' for col, v in zip("EF", cells))
            x = re.sub(rf'(<row r="{r}"[^>]*>.*?)(</row>)', lambda m: m.group(1) + add + m.group(2), x, count=1, flags=re.S)
        x = once(x, '<dimension ref="A1:C5"/>', '<dimension ref="A1:F5"/>')
        return once(x, '<tableParts count="1">', f'<tableParts count="2"><tablePart {R} r:id="rId2"/>')

    path = excel_wrote(made, {
        "xl/worksheets/sheet8.xml": sheet,
        "xl/worksheets/_rels/sheet8.xml.rels": lambda x: once(x, "</Relationships>",
            '<Relationship Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" '
            'Target="/xl/tables/table2.xml" Id="rId2"/></Relationships>'),
        "xl/tables/table2.xml": lambda _: (
            '<table xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" id="2" name="Second" '
            'displayName="Second" ref="E1:F3" headerRowCount="1"><autoFilter ref="E1:F3"/><tableColumns count="2">'
            '<tableColumn id="1" name="Item"/><tableColumn id="2" name="Cost"/></tableColumns>'
            '<tableStyleInfo name="TableStyleMedium3" showFirstColumn="0" showLastColumn="0" showRowStripes="1" '
            'showColumnStripes="0"/></table>'),
        "[Content_Types].xml": lambda x: once(x, "</Types>",
            '<Override PartName="/xl/tables/table2.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml"/></Types>'),
    })
    opened(viewer, page, path)
    tab(page, "Table")
    first, second = rgb(page.evaluate(LOOK, "A1")["fill"]), rgb(page.evaluate(LOOK, "E1")["fill"])
    assert first[2] > first[0] + 60, "TableStyleMedium2's header, the first accent, blue"
    assert second[0] > second[2] + 60, "TableStyleMedium3's header, the second accent, red or orange"


def test_a_table_style_of_the_workbooks_own_draws_its_header_and_its_lines_in_their_colours(viewer, page, made):
    """Read from its dxfs, the lines between its rows in the colour it gives them, white here, not black"""
    own = ('<dxf><fill><patternFill patternType="solid"><bgColor rgb="FF203864"/></patternFill></fill></dxf>'
           '<dxf><fill><patternFill patternType="solid"><bgColor rgb="FFDDEBF7"/></patternFill></fill>'
           '<border><horizontal style="thin"><color rgb="FFFFFFFF"/></horizontal></border></dxf>')

    def styles(x):
        x = once(x, "</dxfs>", own + "</dxfs>")
        return once(x, '<tableStyles count="0" defaultTableStyle="TableStyleMedium9" defaultPivotStyle="PivotStyleLight16"/>',
                    '<tableStyles count="1" defaultTableStyle="TableStyleMedium9" defaultPivotStyle="PivotStyleLight16">'
                    '<tableStyle name="Ours" pivot="0" count="2"><tableStyleElement type="headerRow" dxfId="6"/>'
                    '<tableStyleElement type="wholeTable" dxfId="7"/></tableStyle></tableStyles>')

    path = excel_wrote(made, {"xl/styles.xml": styles,
                              "xl/tables/table1.xml": lambda x: once(x, 'name="TableStyleMedium2"', 'name="Ours"')})
    opened(viewer, page, path)
    tab(page, "Table")
    assert page.evaluate(LOOK, "A1")["fill"] == "rgb(32, 56, 100)"
    line = page.evaluate("() => { const cs = getComputedStyle(vwCellAt('A3')); return [cs.borderBottomStyle, cs.borderBottomColor]; }")
    assert line == ["solid", "rgb(255, 255, 255)"]


LAYOUT = "xl/worksheets/sheet3.xml"


def test_a_date_past_excels_last_day_can_be_tapped(viewer, page, made):
    """A phone number in a date's column is past 9999-12-31: SheetJS gives no date, which the bar threw on"""
    path = excel_wrote(made, {LAYOUT: lambda x: once(x, '<c r="C5" s="3" t="n"><v>46316</v></c>',
                                                     '<c r="C5" s="3" t="n"><v>5551234567</v></c>')})
    opened(viewer, page, path)
    page.evaluate("() => vwCellAt('C5').click()")
    page.wait_for_function(BAR)
    assert page.evaluate(BAR).startswith("C5")
    assert page.evaluate("() => { const e = document.getElementById('vw-status'); return !e || getComputedStyle(e).display === 'none'; }")


def test_a_long_integer_in_a_narrow_column_is_written_as_a_power_of_ten(viewer, page, made):
    """12345678901 in a column ten characters wide, too narrow for its digits in any font: Excel writes it
    as a power of ten with as many digits as fit, where Gander wrote ###"""
    path = excel_wrote(made, {LAYOUT: lambda x: once(x, '<c r="D5" s="4" t="inlineStr"><is><t>Middle</t></is></c></row>',
                                                     '<c r="D5" s="4" t="inlineStr"><is><t>Middle</t></is></c>'
                                                     '<c r="E5" t="n"><v>12345678901</v></c></row>')})
    text = opened(viewer, page, path)("E5")["text"]
    assert re.fullmatch(r"\d(\.\d+)?E\+10", text), text


def test_text_does_not_run_on_over_a_formula_that_gives_empty_text(viewer, page, made):
    """="" holds the cell in Excel, an old way to stop a title running on over it"""
    path = excel_wrote(made, {LAYOUT: lambda x: once(x, "beside it</t></is></c></row>",
                                                     'beside it</t></is></c><c r="D1" t="str"><f>""</f><v></v></c></row>')})
    opened(viewer, page, path)
    reach = page.evaluate("""() => [...vwCellAt('C1').querySelectorAll('.vw-sp')].map(b => b.getBoundingClientRect().right)""")
    assert reach and max(reach) <= page.evaluate("() => vwCellAt('D1').getBoundingClientRect().left") + 1


def test_a_merge_whose_first_column_is_hidden_shows_its_text_and_names_its_first_cell(viewer, page, made):
    path = excel_wrote(made, {LAYOUT: lambda x: once(x, '<col width="10" customWidth="1" min="5" max="5"/>',
                                                     '<col hidden="1" width="10" customWidth="1" min="5" max="5"/>')})
    opened(viewer, page, path)
    assert page.evaluate("() => vwCellAt('F14').textContent") == "Merged"
    page.evaluate("() => vwCellAt('F14').click()")
    page.wait_for_function(BAR)
    assert page.evaluate(BAR).startswith("E14:G15") and "Merged" in page.evaluate(BAR)


def test_rows_set_short_by_hand_keep_their_height_under_turned_and_large_text(viewer, page, made):
    """A row grows to fit its cells whatever a cell's overflow, so turned text made one 30 px row 182 px"""
    def rows(x):
        x = once(x, '<row r="8">', '<row r="8" ht="22.5" customHeight="1">')
        return once(x, '<row r="9">', '<row r="9" ht="12" customHeight="1">')
    cell = opened(viewer, page, excel_wrote(made, {LAYOUT: rows}))
    assert cell("A8")["rowHeight"] == pytest.approx(30, abs=1)
    assert cell("A9")["rowHeight"] == pytest.approx(16, abs=1)


def test_a_sheet_shown_after_a_search_draws_only_what_is_near_the_screen(viewer, page, made):
    """Find builds every piece of every sheet off the page, and showing a sheet then put all of them on
    it, a long sheet laid out at once, which the pieces exist to avoid"""
    def tall(x):
        rows = "".join(f'<row r="{n}"><c r="A{n}" t="n"><v>{n}</v></c></row>' for n in range(1, 5001))
        x = re.sub(r"<sheetData>.*</sheetData>", "<sheetData>" + rows + "</sheetData>", x, flags=re.S)
        return once(x, '<dimension ref="A1:A199"/>', '<dimension ref="A1:A5000"/>')
    opened(viewer, page, excel_wrote(made, {"xl/worksheets/sheet10.xml": tall}))
    built = page.evaluate("() => { for (let i = 0; i < vwFindParts.count(); i++) vwFindParts.root(i); return vwFindParts.count(); }")
    assert built > 10
    tab(page, "Frozen tall")
    on_page = page.evaluate("() => [document.querySelectorAll('#grid > .vw-later').length, document.querySelectorAll('#grid > .vw-p').length]")
    assert on_page[0] >= 7, on_page
    assert on_page[1] <= 3, on_page


def test_a_piece_drawn_before_stands_in_as_tall_as_it_was_drawn_when_its_sheet_is_shown_again(viewer, page, made):
    """Rows its wrapped text grew make it taller than the file says, and the page as long as it was"""
    words = " ".join(["Text that wraps onto the lines below it"] * 6)

    def tall(x):
        rows = "".join(f'<row r="{n}"><c r="A{n}" t="n"><v>{n}</v></c></row>' if n != 3000 else
                       f'<row r="{n}"><c r="A{n}" s="2" t="inlineStr"><is><t>{words}</t></is></c></row>' for n in range(1, 5001))
        x = re.sub(r"<sheetData>.*</sheetData>", "<sheetData>" + rows + "</sheetData>", x, flags=re.S)
        return once(x, '<dimension ref="A1:A199"/>', '<dimension ref="A1:A5000"/>')
    opened(viewer, page, excel_wrote(made, {"xl/worksheets/sheet10.xml": tall}))
    tab(page, "Frozen tall")
    # The piece that holds row 3000, put on the page as Find puts one and scrolled to, where it is drawn
    # and its rows evened out: off the screen the browser skips it and gives it the size it was given
    page.evaluate("""() => { for (let k = 0; k < 10 && !document.querySelector('#grid tr[data-r="2999"]'); k++)
      vwFindParts.show(vwFindParts.shown() + k);
      document.querySelector('#grid tr[data-r="2999"]').scrollIntoView(); }""")
    page.wait_for_timeout(600)
    piece, drawn, filed = page.evaluate("""() => { const b = document.querySelector('#grid tr[data-r="2999"]').closest('.vw-p');
      return [b.vwPiece, b.getBoundingClientRect().height / vwSheetZoom(), b.querySelector('tr[data-r="2999"]').getBoundingClientRect().height]; }""")
    assert filed > 60, "the wrapped row grew"
    tab(page, "Layout")
    tab(page, "Frozen tall")
    stand = page.evaluate("(k) => { const s = [...document.querySelectorAll('#grid > .vw-later')].find(s => s.vwPiece === k); "
                          "return s && parseFloat(s.style.height); }", piece)
    assert stand == pytest.approx(drawn, abs=1)


def test_text_set_in_no_colour_of_its_own_takes_the_workbooks_own(viewer, page, made):
    """Many of Microsoft's templates set the Normal font in Text 1 made a quarter lighter, a dark grey"""
    path = excel_wrote(made, {"xl/styles.xml": lambda x: once(
        x, '<font><name val="Calibri"/><family val="2"/><color theme="1"/>',
        '<font><name val="Calibri"/><family val="2"/><color theme="1" tint="0.249977111117893"/>')})
    opened(viewer, page, path)
    assert page.evaluate(LOOK, "A3")["ink"] == "rgb(64, 64, 64)"
    # A cell in a colour of its own keeps it
    assert page.evaluate(LOOK, "E16")["ink"] == "rgb(255, 0, 0)"


# A sheet right to left: its columns run from the right, and what is placed on them with them

def right_to_left(x):
    return once(x, '<sheetView workbookViewId="0">', '<sheetView rightToLeft="1" workbookViewId="0">')


def test_a_picture_on_a_sheet_right_to_left_is_drawn_at_its_cell_from_the_right(viewer, page, made):
    """Placed from the left, a chart anchored at A1 landed at the far side, over the last columns"""
    opened(viewer, page, excel_wrote(made, {"xl/worksheets/sheet9.xml": right_to_left}))
    tab(page, "Art")
    page.wait_for_function("() => { const i = document.querySelector('#grid .vw-art img'); return i && i.complete && i.naturalWidth > 0; }")
    picture = page.evaluate("() => document.querySelector('#grid .vw-art img').getBoundingClientRect().toJSON()")
    anchor = page.evaluate(CELL, "B2")
    assert picture["right"] == pytest.approx(anchor["right"], abs=1)
    assert picture["top"] == pytest.approx(anchor["top"], abs=1)


def test_a_sheet_right_to_left_keeps_its_frozen_columns_clear_on_their_side(viewer, page, made):
    """Find and the arrows bring a cell into view clear of the frozen columns, which are at the right"""
    opened(viewer, page, excel_wrote(made, {LAYOUT: lambda x: once(x, "<sheetView ", '<sheetView rightToLeft="1" ')}))
    right, left = page.evaluate("() => { const cs = getComputedStyle(document.documentElement); "
                                "return [parseFloat(cs.scrollPaddingRight) || 0, parseFloat(cs.scrollPaddingLeft) || 0]; }")
    assert right > 100 and left == 0


def test_a_zoom_on_a_sheet_right_to_left_keeps_the_cell_under_the_finger_when_zoomed_out(viewer, page, made):
    """Zoomed out narrower than the screen, its grid sits at the page's right, and the zoom measured the
    place under the finger from the sheet's left edge, two or three columns off"""
    def tall(x):
        rows = "".join(f'<row r="{n}"><c r="A{n}" t="n"><v>{n}</v></c></row>' for n in range(1, 3001))
        x = re.sub(r"<sheetData>.*</sheetData>", "<sheetData>" + rows + "</sheetData>", x, flags=re.S)
        return right_to_left(once(x, '<dimension ref="A1:A199"/>', '<dimension ref="A1:A3000"/>'))
    opened(viewer, page, excel_wrote(made, {"xl/worksheets/sheet10.xml": tall}))
    tab(page, "Frozen tall")
    page.evaluate("() => vwSheetZoom(0.5, 490, 300)")
    grid = page.evaluate("() => document.getElementById('grid').getBoundingClientRect().toJSON()")
    assert grid["left"] > 300, "zoomed out, the grid is narrower than the screen and set at its right"
    x, y = grid["left"] + grid["width"] * 0.4, 600
    page.evaluate("([x, y]) => { window.__under = document.elementFromPoint(x, y).closest('#grid td'); }", [x, y])
    page.evaluate("([x, y]) => vwSheetZoom(2, x, y)", [x, y])
    assert page.evaluate("([x, y]) => document.elementFromPoint(x, y).closest('#grid td') === window.__under", [x, y])


def test_a_crafted_drawing_of_anchors_never_closed_opens_at_once(viewer, page, made):
    """Each start looked for its end in the rest of the part: a hundred thousand took most of a minute"""
    crafted = ('<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing">'
               + "<xdr:twoCellAnchor><xdr:from/>" * 100000 + "</xdr:wsDr>")
    opened(viewer, page, excel_wrote(made, {"xl/drawings/drawing1.xml": lambda _: crafted}))
    tab(page, "Art")
    assert page.evaluate("() => document.querySelector('#grid td') !== null")
