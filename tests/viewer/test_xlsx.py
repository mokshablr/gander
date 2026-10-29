"""xlsx.html: SheetJS into a table, one sheet at a time."""

import base64

import pytest

from helpers import ROW_DRAWN, big_sheet, bring_row, wait_until_done


def wait_for_sheet(page, timeout=25000):
    page.wait_for_function(
        "() => document.querySelector('#sheet') && "
        "document.querySelector('#sheet').textContent.trim().length > 0",
        timeout=timeout,
    )


def test_a_workbook_shows_its_first_sheet(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    assert "Surveying" in page.text_content("#sheet")


def test_the_cells_arrive_as_a_table(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    assert page.query_selector("#sheet table") is not None
    assert len(page.query_selector_all("#sheet tr")) >= 4


def test_every_sheet_gets_a_tab(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    page.wait_for_selector("#tabs button", timeout=10000)
    assert len(page.query_selector_all("#tabs button")) == 3


def test_choosing_a_tab_swaps_the_sheet(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    page.wait_for_selector("#tabs button", timeout=10000)

    page.query_selector_all("#tabs button")[1].click()
    page.wait_for_function(
        "() => document.querySelector('#sheet').textContent.indexOf('detail-sheet') >= 0",
        timeout=10000,
    )
    assert "Surveying" not in page.text_content("#sheet")


def test_a_csv_opens_as_a_single_sheet(viewer, page):
    """A CSV goes to the spreadsheet viewer rather than the text one."""
    viewer("xlsx.html", "budget.csv")
    wait_for_sheet(page)
    assert "Surveying" in page.text_content("#sheet")
    assert page.query_selector("#tabs").is_hidden() or \
        len(page.query_selector_all("#tabs button")) <= 1


def test_a_utf8_csv_with_no_byte_order_mark_reads_as_utf8(viewer, page):
    """Issue #37: handed bytes, SheetJS read this as Latin-1, and Флаг came out as Ð¤Ð»Ð°Ð³."""
    viewer("xlsx.html", "utf8.csv")
    wait_for_sheet(page)
    text = page.text_content("#sheet")
    for word in ("Флаг", "Straße", "東京", "\U0001F1EA\U0001F1FA"):
        assert word in text


def test_a_latin1_csv_still_reads_as_latin1(viewer, page):
    """Not UTF-8, so it goes to SheetJS as bytes, as every CSV did before #37."""
    viewer("xlsx.html", "latin1.csv")
    wait_for_sheet(page)
    text = page.text_content("#sheet")
    assert "Café" in text
    assert "Grüße" in text


def test_a_template_opens_as_a_workbook_does(viewer, page, main_part):
    """
    budget.xlsx with its main part declared as a template's, which is all that
    tells an .xltx apart. SheetJS knows that type for a workbook, and would try
    xl/workbook.xml by name if it did not.
    """
    assert main_part("budget.xltx") == [
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template.main+xml"
    ]
    viewer("xlsx.html", "budget.xltx")
    wait_for_sheet(page)
    wait_until_done(page)
    assert "Surveying" in page.text_content("#sheet")
    assert len(page.query_selector_all("#tabs button")) == 3


def test_a_macro_enabled_workbook_opens_as_a_workbook_does(viewer, page, main_part):
    """budget.xlsx declared as an .xlsm, which carries no macros: no viewer here runs them."""
    assert main_part("budget.xlsm") == ["application/vnd.ms-excel.sheet.macroEnabled.main+xml"]
    viewer("xlsx.html", "budget.xlsm")
    wait_for_sheet(page)
    wait_until_done(page)
    assert "Surveying" in page.text_content("#sheet")
    assert len(page.query_selector_all("#tabs button")) == 3


@pytest.mark.parametrize("name", ["budget.xls", "budget.xlsb", "budget.ods"])
def test_a_workbook_in_another_format_opens_with_every_sheet(viewer, page, name):
    """
    The same three sheets in Excel 97-2003's format, Excel's binary format and
    OpenDocument, each read by a different SheetJS parser. The last sheet's second line
    needs UTF-16, which BIFF8 switches to for that string alone.
    """
    viewer("xlsx.html", name)
    wait_for_sheet(page)
    wait_until_done(page)
    first = page.text_content("#sheet")
    assert "Surveying" in first and "4200" in first
    tabs = page.query_selector_all("#tabs button")
    assert [t.text_content() for t in tabs] == ["Summary", "Detail", "Notes"]

    tabs[2].click()
    page.wait_for_function(
        "() => document.querySelector('#sheet').textContent.indexOf('Third sheet marker') >= 0",
        timeout=10000,
    )
    assert "Grüße aus Zürich, 東京" in page.text_content("#sheet")


# ---------------------------------------------------------------------------
# A large sheet, drawn a piece at a time
# ---------------------------------------------------------------------------

# Rows enough for many pieces: xlsx.js cuts a sheet this narrow every 500 rows
BIG = 20000

# The left edge of each cell in the row whose first cell reads n
CELL_LEFTS = """(n) => {
  const tr = [...document.querySelectorAll('#sheet tr')]
    .find(r => r.cells.length && r.cells[0].textContent === String(n));
  return [...tr.cells].map(td => Math.round(td.getBoundingClientRect().left * 100) / 100);
}"""


def test_a_large_sheet_draws_its_first_rows_and_leaves_room_for_the_rest(viewer, page, made):
    """Drawn whole, 20,000 rows took seconds to appear and froze the page while they did."""
    viewer("xlsx.html", big_sheet(made, BIG))
    wait_for_sheet(page)
    wait_until_done(page)
    assert page.evaluate(ROW_DRAWN, 1)
    assert not page.evaluate(ROW_DRAWN, BIG)
    assert 0 < page.evaluate("() => document.querySelectorAll('#sheet tr').length") < 1000
    # The page is already as long as the whole sheet, so the scroll bar tells the truth
    row = page.evaluate("() => document.querySelectorAll('#sheet tr')[1].getBoundingClientRect().height")
    assert page.evaluate("() => document.documentElement.scrollHeight") > row * BIG * 0.9


def test_scrolling_to_the_end_draws_the_last_rows_and_not_every_row_between(viewer, page, made):
    viewer("xlsx.html", big_sheet(made, BIG))
    wait_for_sheet(page)
    page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    page.wait_for_function(ROW_DRAWN, arg=BIG, timeout=20000)
    assert page.evaluate("() => document.querySelectorAll('#sheet tr').length") < 3000


def test_a_piece_drawn_later_lines_its_columns_up_with_the_first(viewer, page, made):
    """Row 15,000 has a name far longer than any other, in a piece drawn long after the first."""
    long_name = "A name a great deal longer than any other in this column"
    viewer("xlsx.html", big_sheet(made, BIG, {15000: long_name}))
    wait_for_sheet(page)
    bring_row(page, 15000, BIG)
    assert page.evaluate(CELL_LEFTS, 15000) == page.evaluate(CELL_LEFTS, 1)
    assert page.evaluate(CELL_LEFTS, 15000) == page.evaluate(CELL_LEFTS, 14999)


def test_no_row_is_drawn_as_a_heading_the_file_does_not_make_one(viewer, page, made):
    """
    The page used to shade a sheet's first row and set it bold, which no file asked for and Excel
    does not do. A sheet's own looks are all there is now; its first row is under the column letters.
    """
    viewer("xlsx.html", big_sheet(made, BIG))
    wait_for_sheet(page)
    bring_row(page, 2000, BIG)
    weights = page.evaluate(
        "() => [...document.querySelectorAll('#grid > .vw-p .vw-tr')]"
        ".map(t => [...t.rows].find(r => r.cells[0] && r.cells[0].tagName === 'TD'))"
        ".map(r => getComputedStyle(r.cells[0]).fontWeight)"
    )
    assert len(weights) > 1
    assert set(weights) == {"400"}
    assert page.evaluate(LOOK, "A1")["fill"] == NONE


def test_rows_keep_their_spacing_from_one_piece_to_the_next(viewer, page, made):
    """
    Each piece is a table with borders of its own. Laid end to end, two tables put two lines
    between the pieces and push the second down; one table's rows share a line.
    """
    viewer("xlsx.html", big_sheet(made, BIG))
    wait_for_sheet(page)
    bring_row(page, 600, BIG)
    inside, across, after = page.evaluate("""() => {
      const [a, b] = document.querySelectorAll('#grid > .vw-p .vw-tr');
      const top = (row) => row.getBoundingClientRect().top;
      const n = a.rows.length;
      return [top(a.rows[n - 1]) - top(a.rows[n - 2]), top(b.rows[0]) - top(a.rows[n - 1]),
              top(b.rows[1]) - top(b.rows[0])];
    }""")
    assert abs(across - inside) < 0.25
    assert abs(after - inside) < 0.25


def test_a_merged_range_is_kept_whole_where_a_large_sheet_is_cut(viewer, page, made):
    """
    Rows 495 to 505 of the first column are merged, across where a sheet 20 columns wide is cut
    into pieces, every 250 rows. A piece ending at 499 would leave the merged cell six rows short.
    """
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    workbook = page.evaluate("""() => {
      const rows = [['Group', 'Row'].concat(Array.from({ length: 18 }, (_, k) => 'V' + k))];
      for (let r = 1; r < 1200; r++) {
        rows.push([r === 495 ? 'Merged across the cut' : '', 'R' + r].concat(Array.from({ length: 18 }, (_, k) => r + k)));
      }
      const ws = XLSX.utils.aoa_to_sheet(rows);
      ws['!merges'] = [{ s: { r: 495, c: 0 }, e: { r: 505, c: 0 } }];
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, 'Tall');
      return XLSX.write(wb, { type: 'base64', bookType: 'xlsx' });
    }""")
    viewer("xlsx.html", made("tall-merge.xlsx", base64.b64decode(workbook)))
    wait_for_sheet(page)
    wait_until_done(page)
    page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight * 496 / 1200)")
    page.wait_for_function("() => !!vwCellAt('A496')", timeout=10000)
    merged, same_table, cut_at = page.evaluate("""() => {
      const merged = vwCellAt('A496');
      const last = vwCellAt('B506');
      const firsts = [...document.querySelectorAll('#grid > .vw-p .vw-tr')].map(t => +t.rows[0].dataset.r);
      return [merged.rowSpan, !!last && last.closest('table') === merged.closest('table'), firsts];
    }""")
    assert merged == 11
    assert same_table
    # The piece before it ends where 250 rows would, and the one with the merge runs past 499
    assert 250 in cut_at and 500 not in cut_at


def test_an_engine_too_old_to_search_the_page_itself_gets_every_row_on_it(viewer, page, made):
    """
    Below Chromium 105 the app searches with Chromium's own find, which reads only what is on
    the page, and such an engine has no Highlight API. There the whole sheet goes onto the page,
    a piece at a time, with no scrolling to bring it.
    """
    page.add_init_script("delete CSS.highlights")
    viewer("xlsx.html", big_sheet(made, BIG))
    wait_for_sheet(page)
    assert page.evaluate("() => !(window.CSS && CSS.highlights)")
    page.wait_for_function(ROW_DRAWN, arg=BIG, timeout=30000)
    assert page.evaluate("() => window.scrollY") == 0


# ---------------------------------------------------------------------------
# Cell colours, read by xlsx-styles.js
# ---------------------------------------------------------------------------

# The drawn look of the cell at an address, such as A1, found through the page's own map of its tables
LOOK = """(address) => {
  const td = vwCellAt(address);
  if (!td) return null;
  const cs = getComputedStyle(td);
  return {fill: cs.backgroundColor, ink: cs.color, weight: cs.fontWeight};
}"""

NONE = "rgba(0, 0, 0, 0)"


def rgb(css):
    return tuple(int(v) for v in css[css.index("(") + 1:css.index(")")].split(",")[:3])


def contrast(a, b):
    def luminance(c):
        c = [v / 255 for v in c]
        c = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in c]
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2] + 0.05
    x, y = luminance(a), luminance(b)
    return max(x, y) / min(x, y)


def open_colours(viewer, page, name="colours.xlsx"):
    viewer("xlsx.html", name)
    wait_for_sheet(page)
    wait_until_done(page)
    return lambda address: page.evaluate(LOOK, address)


def test_a_filled_cell_shows_its_fill(viewer, page):
    """Drawn from SheetJS's reading alone, every cell of every sheet came out plain."""
    look = open_colours(viewer, page)
    assert look("A3")["fill"] == "rgb(255, 255, 0)"
    assert look("A2")["fill"] == NONE
    assert look("B3")["fill"] == NONE


def test_fills_named_by_a_theme_colour_or_a_palette_number_show(viewer, page):
    """Theme colour 0, the white Excel's greys are shades of, and number 43, FFFF99"""
    look = open_colours(viewer, page)
    assert look("A4")["fill"] == "rgb(217, 217, 217)"
    assert look("A5")["fill"] == "rgb(255, 255, 153)"


def test_a_pattern_shows_as_its_two_colours_mixed(viewer, page):
    """A quarter red over white"""
    look = open_colours(viewer, page)
    assert look("A8")["fill"] == "rgb(255, 191, 191)"


def test_a_gradient_shows_as_the_mean_of_its_colours(viewer, page):
    look = open_colours(viewer, page)
    assert look("C4")["fill"] == "rgb(128, 0, 128)"


def test_a_filled_row_fills_the_cells_not_written_in_it(viewer, page):
    look = open_colours(viewer, page)
    for address in ("A9", "B9", "C9"):
        assert look(address)["fill"] == "rgb(146, 208, 80)", address


def test_a_filled_column_fills_the_cells_not_written_in_it(viewer, page):
    look = open_colours(viewer, page)
    for address in ("D2", "D5", "D8", "D10"):
        assert look(address)["fill"] == "rgb(255, 192, 0)", address


def test_a_cell_written_where_a_filled_row_and_column_cross_keeps_its_own_style(viewer, page):
    """D9 is written with no style, so it is plain, under neither the row's green nor the column's orange"""
    look = open_colours(viewer, page)
    assert look("D9")["fill"] == NONE


def test_text_keeps_its_colour_and_weight(viewer, page):
    look = open_colours(viewer, page)
    assert look("A6")["ink"] == "rgb(255, 0, 0)"
    assert look("A7")["weight"] == "700"
    assert look("A2")["ink"] == "rgb(0, 0, 0)"


def test_a_first_row_keeps_the_workbooks_fill_and_a_plain_one_stays_plain(viewer, page):
    """The page shaded a sheet's first row as a heading, which Excel does not; the file's own fill stays"""
    look = open_colours(viewer, page)
    assert look("A1") == {"fill": "rgb(31, 78, 121)", "ink": "rgb(255, 255, 255)", "weight": "700"}
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    assert page.evaluate(LOOK, "A1")["fill"] == NONE


def test_at_night_fills_turn_over_keeping_their_colour_and_text_stays_readable(viewer, page):
    """
    Yellow goes dark but stays yellow and clear of the ground, the dark blue heading goes light
    with dark text on it, and an unfilled cell is the night's own ground and ink.
    """
    page.emulate_media(color_scheme="dark")
    look = open_colours(viewer, page)
    ground = rgb(page.evaluate("() => getComputedStyle(document.body).backgroundColor"))

    yellow = look("A3")
    r, g, b = rgb(yellow["fill"])
    assert r > b + 40 and g > b + 40 and abs(r - g) < 20
    assert max(rgb(yellow["fill"])) > max(ground) + 30
    assert contrast(rgb(yellow["ink"]), rgb(yellow["fill"])) >= 4.5

    heading = look("A1")
    assert contrast(rgb(heading["fill"]), ground) > 3
    assert contrast(rgb(heading["ink"]), rgb(heading["fill"])) >= 4.5

    # Turned over, red is pale, and the black text on it the light night ink
    alert = look("C3")
    assert contrast(rgb(alert["fill"]), ground) > 3
    assert contrast(rgb(alert["ink"]), rgb(alert["fill"])) >= 4.5

    assert look("A2")["fill"] == NONE
    assert rgb(look("A2")["ink"]) == (0xE7, 0xE2, 0xD9)


def test_the_night_ground_and_ink_are_app_css_s(viewer, page):
    """xlsx-styles.js turns colours over between them, so its copies must follow app.css"""
    page.emulate_media(color_scheme="dark")
    open_colours(viewer, page)
    said = page.evaluate("""() => {
      const root = getComputedStyle(document.documentElement);
      return [root.getPropertyValue('--vw-ground').trim(), root.getPropertyValue('--vw-ink').trim(),
              VW_SHEET_NIGHT_GROUND, VW_SHEET_NIGHT_INK];
    }""")
    assert said[0].upper() == "#" + said[2]
    assert said[1].upper() == "#" + said[3]


def test_a_fill_far_down_a_long_sheet_shows_in_the_piece_drawn_for_it(viewer, page):
    open_colours(viewer, page)
    page.query_selector_all("#tabs button")[1].click()
    bring_row(page, 1100, 1200)
    assert page.evaluate(LOOK, "B1101")["fill"] == "rgb(255, 255, 0)"
    assert page.evaluate(LOOK, "B1100")["fill"] == NONE


def test_an_xls_shows_its_fills_and_its_texts_colour_and_weight(viewer, page):
    """Read from its own records: SheetJS reads an .xls's fills but no font, and no row or column"""
    look = open_colours(viewer, page, "colours.xls")
    # A first row the file does not fill is as plain as any other
    assert look("A1")["fill"] == NONE
    assert look("A2")["fill"] == "rgb(255, 255, 0)"
    # Blank cells, which SheetJS does not keep, written as one record for the two
    assert look("B2")["fill"] == "rgb(255, 255, 0)"
    assert look("C2")["fill"] == "rgb(255, 255, 0)"
    # Navy with text given no colour, which takes white
    assert look("A3")["fill"] == "rgb(0, 0, 128)"
    assert look("A3")["ink"] == "rgb(255, 255, 255)"
    assert look("A4")["ink"] == "rgb(255, 0, 0)"
    assert look("A4")["weight"] == "700"


def test_an_xls_fills_the_cells_not_written_in_a_filled_row_or_column(viewer, page):
    look = open_colours(viewer, page, "colours.xls")
    for address in ("B5", "C5"):
        assert look(address)["fill"] == "rgb(153, 204, 0)", address
    for address in ("D2", "D3", "D4"):
        assert look(address)["fill"] == "rgb(255, 153, 0)", address
    # Written with no style of its own where the row and the column cross
    assert look("D5")["fill"] == NONE


def test_a_workbook_whose_styles_cannot_be_read_is_drawn_plain_and_not_refused(viewer, page):
    """The readers in xlsx-styles.js are newer than the sheet's drawing, and must not stop it"""
    page.add_init_script(
        "document.addEventListener('DOMContentLoaded', () => {"
        " vwXlsxStyles = () => { throw new Error('unreadable styles'); }; })"
    )
    look = open_colours(viewer, page)
    assert "Yellow" in page.text_content("#sheet")
    assert look("A3")["fill"] == NONE
