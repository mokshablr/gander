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


def test_only_the_first_row_of_a_large_sheet_is_drawn_as_its_heading(viewer, page, made):
    viewer("xlsx.html", big_sheet(made, BIG))
    wait_for_sheet(page)
    bring_row(page, 2000, BIG)
    weights = page.evaluate(
        "() => [...document.querySelectorAll('#sheet > table')]"
        ".map(t => getComputedStyle(t.rows[0].cells[0]).fontWeight)"
    )
    assert len(weights) > 1
    assert weights[0] == "600"
    assert set(weights[1:]) == {"400"}


def test_rows_keep_their_spacing_from_one_piece_to_the_next(viewer, page, made):
    """
    Each piece is a table with borders of its own. Laid end to end, two tables put two lines
    between the pieces and push the second down; one table's rows share a line.
    """
    viewer("xlsx.html", big_sheet(made, BIG))
    wait_for_sheet(page)
    bring_row(page, 600, BIG)
    inside, across, after = page.evaluate("""() => {
      const [a, b] = document.querySelectorAll('#sheet > table');
      const top = (row) => row.getBoundingClientRect().top;
      const n = a.rows.length;
      return [top(a.rows[n - 1]) - top(a.rows[n - 2]), top(b.rows[0]) - top(a.rows[n - 1]),
              top(b.rows[1]) - top(b.rows[0])];
    }""")
    assert abs(across - inside) < 0.25
    assert abs(after - inside) < 0.25


def test_a_merged_range_is_kept_whole_where_a_large_sheet_is_cut(viewer, page, made):
    """
    Rows 495 to 505 of the first column are merged, across where a sheet this narrow is cut
    into pieces. A piece ending at 499 would leave the merged cell six rows short.
    """
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    workbook = page.evaluate("""() => {
      const rows = [['Group', 'Row', 'Value']];
      for (let r = 1; r < 1200; r++) rows.push([r === 495 ? 'Merged across the cut' : '', 'R' + r, r]);
      const ws = XLSX.utils.aoa_to_sheet(rows);
      ws['!merges'] = [{ s: { r: 495, c: 0 }, e: { r: 505, c: 0 } }];
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, 'Tall');
      return XLSX.write(wb, { type: 'base64', bookType: 'xlsx' });
    }""")
    viewer("xlsx.html", made("tall-merge.xlsx", base64.b64decode(workbook)))
    wait_for_sheet(page)
    wait_until_done(page)
    merged, same_table = page.evaluate("""() => {
      const cells = [...document.querySelectorAll('#sheet td')];
      const merged = cells.find(td => td.textContent === 'Merged across the cut');
      const last = cells.find(td => td.textContent === 'R505');
      return [merged.rowSpan, !!last && last.closest('table') === merged.closest('table')];
    }""")
    assert merged == 11
    assert same_table


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
