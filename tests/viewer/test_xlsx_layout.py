"""
xlsx.html as Excel draws a sheet: its grid, headings, sizes, alignment, borders, frozen panes and
tabs, a tapped cell shown whole in the bar above the tabs, and the page's own zoom.

Most of it is checked on layout.xlsx, which tests/fixtures/README.md describes cell by cell.
"""

import pytest

from helpers import big_sheet, wait_until_done
from test_xlsx import LOOK, NONE, rgb, contrast, wait_for_sheet

# A cell's box and computed style, by its address
CELL = """(address) => {
  const td = vwCellAt(address);
  if (!td) return null;
  const cs = getComputedStyle(td), box = td.getBoundingClientRect();
  return {
    left: box.left, right: box.right, top: box.top, width: box.width, height: box.height,
    text: td.textContent, align: cs.textAlign, valign: cs.verticalAlign, white: cs.whiteSpace,
    family: cs.fontFamily, size: parseFloat(cs.fontSize), padLeft: parseFloat(cs.paddingLeft),
    right_: [cs.borderRightWidth, cs.borderRightStyle, cs.borderRightColor],
    bottom: [cs.borderBottomWidth, cs.borderBottomStyle, cs.borderBottomColor],
    colspan: td.colSpan, rowspan: td.rowSpan, classes: td.className, html: td.innerHTML,
    rowHeight: td.parentNode.getBoundingClientRect().height
  };
}"""

TABS = "() => [...document.querySelectorAll('#tabs button')].map(b => [b.textContent, b.getAttribute('aria-selected')])"

BAR = "() => { const b = document.getElementById('cellbar'); return b.hidden ? null : b.innerText; }"


def opened(viewer, page, name="layout.xlsx", **params):
    viewer("xlsx.html", name, **params)
    wait_for_sheet(page)
    wait_until_done(page)
    return lambda address: page.evaluate(CELL, address)


def tab(page, name):
    page.locator("#tabs button", has_text=name).click()
    page.wait_for_function(
        "(n) => [...document.querySelectorAll('#tabs button')].some(b => b.textContent === n && b.classList.contains('active'))",
        arg=name)


# ---------------------------------------------------------------------------
# The workbook: which sheet opens, which are hidden
# ---------------------------------------------------------------------------

def test_a_workbook_opens_on_the_sheet_it_was_saved_showing(viewer, page):
    """Gander always opened the first sheet, which a reader may never have been meant to look at"""
    opened(viewer, page)
    assert [name for name, selected in page.evaluate(TABS) if selected == "true"] == ["Layout"]


def test_a_hidden_sheet_has_no_tab_and_is_not_drawn(viewer, page):
    opened(viewer, page)
    assert [name for name, _ in page.evaluate(TABS)] == ["Cover", "Layout", "No grid", "No headings", "Right to left",
                                                         "Rules", "Table", "Art", "Frozen tall"]
    assert "Kept out of sight" not in page.text_content("#sheet")


def test_the_tabs_are_at_the_foot_of_the_screen_and_say_which_is_chosen(viewer, page):
    opened(viewer, page)
    box = page.evaluate("() => document.getElementById('tabs').getBoundingClientRect().toJSON()")
    assert box["bottom"] == pytest.approx(page.evaluate("innerHeight"), abs=1)
    assert box["height"] >= 48
    roles = page.evaluate("() => [document.getElementById('tabs').getAttribute('role'),"
                          " ...[...document.querySelectorAll('#tabs button')].map(b => b.getAttribute('role'))]")
    assert set(roles) == {"tablist", "tab"} and roles[0] == "tablist"


def test_a_tab_the_workbook_colours_shows_its_colour(viewer, page):
    opened(viewer, page)
    colours = page.evaluate(
        "() => [...document.querySelectorAll('#tabs button')].map(b => getComputedStyle(b, '::after').backgroundColor)")
    assert colours[1] == "rgb(192, 0, 0)"
    assert colours[0] in ("rgba(0, 0, 0, 0)", "")


# ---------------------------------------------------------------------------
# The grid: headings, sizes, what is hidden
# ---------------------------------------------------------------------------

def test_the_columns_have_their_letters_and_the_rows_their_numbers(viewer, page):
    opened(viewer, page)
    letters = page.evaluate("() => [...document.querySelectorAll('#grid .vw-head th.vw-ch')].map(t => t.dataset.n)")
    assert letters[:4] == ["A", "C", "D", "E"]
    numbers = page.evaluate("() => [...document.querySelectorAll('#grid th.vw-rh')].map(t => t.dataset.n)")
    assert numbers[:5] == ["1", "2", "3", "5", "6"]


def test_the_letters_and_numbers_are_no_text_a_search_or_a_copy_could_take(viewer, page):
    """Drawn from an attribute by CSS, so a search for 12 finds the 12 in a cell and never a row's number"""
    opened(viewer, page)
    assert page.evaluate("() => [...document.querySelectorAll('#grid th')].every(t => t.textContent === '')")
    assert page.evaluate(
        "() => getComputedStyle(document.querySelector('#grid th.vw-ch'), '::before').content") == '"A"'


def test_hidden_rows_and_columns_are_left_out_with_a_mark_where_they_were(viewer, page):
    cell = opened(viewer, page)
    assert cell("B3") is None and cell("A4") is None
    text = page.text_content("#sheet")
    assert "In a hidden column" not in text and "In a hidden row" not in text
    assert page.evaluate("() => document.querySelector('#grid th.vw-ch[data-n=\"C\"]').classList.contains('vw-gap')")
    assert page.evaluate("() => document.querySelector('#grid th.vw-rh[data-n=\"5\"]').classList.contains('vw-gap')")


def test_columns_are_as_wide_and_rows_as_tall_as_the_file_makes_them(viewer, page):
    """
    20 characters is 140 px, 6 is 42 and Excel's default 64, by ECMA-376's sum with Calibri's 7 px
    digit; 30 points is 40 px and the default 15 points 20. Gander sized every column to its widest
    text, and every row to its text, so an empty row was a sliver.
    """
    cell = opened(viewer, page)
    assert cell("A3")["width"] == pytest.approx(140, abs=0.5)
    assert cell("C3")["width"] == pytest.approx(42, abs=0.5)
    assert cell("D3")["width"] == pytest.approx(64, abs=0.5)
    assert cell("A2")["rowHeight"] == pytest.approx(40, abs=0.5)
    assert cell("A1")["rowHeight"] == pytest.approx(20, abs=0.5)
    assert cell("A7")["rowHeight"] == pytest.approx(20, abs=0.5)


def test_the_grid_runs_on_past_the_last_cell_to_fill_the_screen(viewer, page):
    """As Excel's does, rather than ending in a table at the top of an empty page"""
    opened(viewer, page, "budget.xlsx")
    rows = page.evaluate("() => document.querySelectorAll('#grid tr[data-r]').length")
    assert rows > 30
    width = page.evaluate("() => document.getElementById('grid').getBoundingClientRect().width")
    assert width == pytest.approx(page.evaluate("innerWidth"), abs=64)


# ---------------------------------------------------------------------------
# Cells: alignment, fonts, borders, text running on
# ---------------------------------------------------------------------------

def test_numbers_go_to_the_right_text_to_the_left_and_true_to_the_middle(viewer, page):
    """Excel's General alignment, where Gander set everything to the left"""
    cell = opened(viewer, page)
    assert cell("F3")["align"] == "right"
    assert cell("A3")["align"] in ("left", "start")
    assert cell("G3")["align"] == "center"


def test_a_cells_alignment_indent_and_wrapping_are_the_files(viewer, page):
    cell = opened(viewer, page)
    assert cell("D3")["align"] == "right"
    assert cell("D5")["align"] == "center" and cell("D5")["valign"] == "middle"
    assert cell("A3")["valign"] == "bottom"
    assert cell("D7")["padLeft"] > 15
    assert cell("E3")["white"] == "pre-wrap"
    assert cell("E3")["rowHeight"] > 50


def test_a_row_set_short_by_hand_keeps_its_height_and_cuts_its_wrapped_text_off(viewer, page):
    cell = opened(viewer, page)
    assert cell("E6")["rowHeight"] == pytest.approx(16, abs=1)
    assert "vw-clip" in cell("E6")["html"]


def test_turned_text_is_turned(viewer, page):
    cell = opened(viewer, page)
    assert 'class="vw-up"' in cell("A8")["html"]
    assert 'class="vw-down"' in cell("E8")["html"]
    assert 'class="vw-stack"' in cell("F8")["html"]


def test_a_cells_font_and_size_are_the_files(viewer, page):
    """
    20 points is 26.67 px. Calibri, the workbook's own, is drawn in Roboto Condensed on a phone,
    whose digits are as wide as Calibri's, so text that fits a column in Excel fits it here.
    """
    cell = opened(viewer, page)
    big = cell("A9")
    assert big["size"] == pytest.approx(26.67, abs=0.05)
    assert big["family"].startswith('"Times New Roman"') and big["family"].endswith("serif")
    assert cell("A10")["family"].endswith("monospace")
    normal = cell("A11")
    assert normal["size"] == pytest.approx(14.67, abs=0.05)
    assert normal["family"].startswith("Calibri") and "sans-serif-condensed" in normal["family"]


def test_a_cells_borders_are_drawn_the_heavier_where_two_cells_give_one_edge(viewer, page):
    """E10's right side is thin, F10's left medium dashed red: the edge they share is the dashed one"""
    cell = opened(viewer, page)
    boxed = cell("E10")
    assert boxed["right_"] == ["2px", "dashed", "rgb(255, 0, 0)"]
    assert boxed["bottom"] == ["1px", "solid", "rgb(0, 0, 0)"]
    # Its top is the bottom of the cell above, which draws that edge
    assert cell("E9")["bottom"] == ["1px", "solid", "rgb(0, 0, 0)"]
    assert cell("E11")["bottom"] == ["2px", "solid", "rgb(0, 0, 255)"]
    assert cell("E12")["bottom"][:2] == ["3px", "double"]


def test_a_filled_cell_covers_the_gridlines_round_it(viewer, page):
    cell = opened(viewer, page)
    assert cell("G18")["right_"][2] == NONE
    assert cell("G18")["bottom"][2] == NONE
    assert cell("F18")["right_"][2] == NONE
    assert cell("E18")["right_"][2] != NONE


def test_the_colour_a_number_format_gives_a_value_shows(viewer, page):
    """#,##0;[Red]-#,##0: SheetJS writes the number and drops the colour"""
    opened(viewer, page)
    assert page.evaluate(LOOK, "E16")["ink"] == "rgb(255, 0, 0)"
    assert page.evaluate(LOOK, "F16")["ink"] == "rgb(0, 0, 0)"


def test_text_runs_on_over_the_empty_cells_beside_it(viewer, page):
    """
    A1's title runs on out of frozen column A into the columns that scroll, carried on in C1, the
    first of them, from where it begins; G19's, set right, runs left over F19 and stops at E19.
    """
    cell = opened(viewer, page)
    title = cell("A1")
    assert "vw-sp" in title["html"]
    assert page.evaluate("() => vwCellAt('A1').querySelector('.vw-sp').getBoundingClientRect().width") > 200
    carried = cell("C1")
    assert "on over the empty cells" in carried["text"]
    assert page.evaluate("() => vwCellAt('C1').querySelector('.vw-sp').getBoundingClientRect().left") == \
        pytest.approx(page.evaluate("() => vwCellAt('A1').querySelector('.vw-sp').getBoundingClientRect().left")
                      + 3, abs=1.5)
    runs = page.evaluate("() => vwCellAt('G19').querySelector('.vw-sp').getBoundingClientRect().toJSON()")
    assert runs["left"] >= cell("E19")["right"] - 0.5
    # The gridline between F19 and G19 goes under the text, the one it stops at stays
    assert cell("F19")["right_"][2] == NONE
    assert cell("E19")["right_"][2] != NONE


def test_a_number_too_wide_for_its_column_shows_as_excel_shows_it(viewer, page):
    """
    Fewer places for a General number, and ### for a date: cut off at its edge, as Gander drew it,
    1,234,567.891 read as 567.891, and that is a different number.
    """
    cell = opened(viewer, page)
    general = cell("C3")["text"]
    assert general == "#" * len(general) or general.replace(".", "").replace("E+", "").isdigit()
    assert set(cell("C5")["text"]) == {"#"}


def test_a_merged_range_spans_its_cells_with_its_text_in_the_first(viewer, page):
    cell = opened(viewer, page)
    merged = cell("E14")
    assert (merged["colspan"], merged["rowspan"]) == (3, 2)
    assert merged["text"] == "Merged" and merged["align"] == "center"


def test_a_note_is_marked_on_its_cell(viewer, page):
    cell = opened(viewer, page)
    assert "vw-cm" in cell("A12")["classes"]
    assert "vw-cm" not in cell("A11")["classes"]


# ---------------------------------------------------------------------------
# Sheets that turn things off, or run the other way
# ---------------------------------------------------------------------------

def test_a_sheet_with_its_gridlines_off_draws_only_its_borders(viewer, page):
    cell = opened(viewer, page)
    tab(page, "No grid")
    assert cell("A1")["right_"][2] == NONE and cell("C5")["bottom"][2] == NONE
    assert cell("B2")["bottom"] == ["1px", "solid", "rgb(0, 0, 0)"]


def test_a_sheet_with_its_headings_off_has_no_letters_or_numbers(viewer, page):
    opened(viewer, page)
    tab(page, "No headings")
    assert page.evaluate("() => document.querySelectorAll('#grid th').length") == 0
    assert "No letters or numbers" in page.text_content("#sheet")


def test_a_sheet_that_runs_right_to_left_has_column_a_at_the_right(viewer, page):
    cell = opened(viewer, page)
    tab(page, "Right to left")
    assert page.evaluate("() => document.documentElement.dir") == "rtl"
    assert cell("A1")["left"] > cell("B1")["left"]
    tab(page, "Layout")
    assert page.evaluate("() => document.documentElement.dir") == ""


# ---------------------------------------------------------------------------
# Conditional formatting, which Excel works out as a sheet opens
# ---------------------------------------------------------------------------

def rules(viewer, page):
    opened(viewer, page)
    tab(page, "Rules")
    return lambda address: page.evaluate(LOOK, address)


def test_a_value_over_a_limit_is_highlighted_as_its_rule_says(viewer, page):
    """Over 100: a light red fill and dark red text, Excel's own pair"""
    look = rules(viewer, page)
    assert look("B3")["fill"] == "rgb(255, 199, 206)" and look("B3")["ink"] == "rgb(156, 0, 6)"
    assert look("B2")["fill"] == NONE


def test_a_rule_given_as_a_formula_is_worked_out_for_each_cell(viewer, page):
    """=$A1="Done", relative to the range's first row, so each row tests its own"""
    look = rules(viewer, page)
    assert look("A1")["fill"] == "rgb(198, 239, 206)" and look("A3")["fill"] == "rgb(198, 239, 206)"
    assert look("A2")["fill"] == NONE


def test_a_colour_scale_runs_from_its_lowest_colour_to_its_highest(viewer, page):
    look = rules(viewer, page)
    low, high = rgb(look("C1")["fill"]), rgb(look("C6")["fill"])
    assert low == (0xF8, 0x69, 0x6B) and high == (0x63, 0xBE, 0x7B)
    middle = rgb(look("C4")["fill"])
    assert middle[0] > 200 and middle[1] > 200


def test_a_data_bar_is_as_long_as_its_number(viewer, page):
    rules(viewer, page)
    bars = page.evaluate("""() => ['D1', 'D6'].map(a => [vwCellAt(a).style.getPropertyValue('--vw-bar'),
      getComputedStyle(vwCellAt(a)).backgroundImage])""")
    assert [b[0] for b in bars] == ["10%", "90%"]
    assert all("linear-gradient" in b[1] for b in bars)


def test_the_top_value_and_text_containing_a_word_take_their_rules(viewer, page):
    look = rules(viewer, page)
    assert look("B3")["weight"] == "700" and look("B6")["weight"] == "400"
    assert look("A4")["ink"] == "rgb(255, 0, 0)"


def test_duplicate_values_are_marked(viewer, page):
    look = rules(viewer, page)
    for address in ("E1", "E3", "E2", "E6"):
        assert look(address)["fill"] == "rgb(255, 235, 156)", address
    assert look("E4")["fill"] == NONE


def test_a_rule_whose_formula_gander_does_not_work_out_draws_nothing(viewer, page):
    """A COUNTIF over a range: guessed, it would colour the cells black"""
    look = rules(viewer, page)
    assert look("F1")["fill"] == NONE


# ---------------------------------------------------------------------------
# A table, in a style Excel holds and the workbook only names
# ---------------------------------------------------------------------------

def test_a_table_is_drawn_in_its_style_header_bands_and_lines(viewer, page):
    """TableStyleMedium2, Excel's default: the accent under white bold text, light bands, tinted lines"""
    opened(viewer, page)
    tab(page, "Table")
    look = lambda a: page.evaluate(LOOK, a)
    cell = lambda a: page.evaluate(CELL, a)
    header = look("A1")
    accent = rgb(header["fill"])
    assert accent[2] > accent[0] + 60, "the header takes the theme's first accent, a blue"
    assert header["ink"] == "rgb(255, 255, 255)" and header["weight"] == "700"
    band, plain = rgb(look("A2")["fill"]), look("A3")["fill"]
    assert band[2] > band[0] and min(band) > 180, "the first band is the accent made pale"
    assert look("B2")["fill"] == look("A2")["fill"], "a band runs the width of the table"
    assert plain == NONE
    assert cell("A2")["bottom"][:2] == ["1px", "solid"]
    assert look("A2")["weight"] == "400"


def test_a_cells_own_fill_wins_over_its_tables(viewer, page):
    """C2 is in a band the style fills, and has a yellow fill of its own"""
    opened(viewer, page)
    tab(page, "Table")
    assert page.evaluate(LOOK, "C2")["fill"] == "rgb(255, 255, 0)"


# ---------------------------------------------------------------------------
# Pictures and charts over the cells
# ---------------------------------------------------------------------------

def test_a_picture_is_drawn_where_the_workbook_anchors_it(viewer, page):
    """At B2, 80 by 40 px. Gander drew no picture a sheet had."""
    opened(viewer, page)
    tab(page, "Art")
    page.wait_for_function("() => { const i = document.querySelector('#grid .vw-art img'); return i && i.complete && i.naturalWidth > 0; }")
    picture = page.evaluate("() => document.querySelector('#grid .vw-art img').getBoundingClientRect().toJSON()")
    anchor = page.evaluate(CELL, "B2")
    assert picture["left"] == pytest.approx(anchor["left"], abs=1)
    assert picture["top"] == pytest.approx(anchor["top"], abs=1)
    assert (picture["width"], picture["height"]) == (pytest.approx(80, abs=1), pytest.approx(40, abs=1))


def test_a_chart_gander_cannot_draw_is_a_box_with_its_title_where_it_would_be(viewer, page):
    """So that nobody reads a sheet without knowing a chart was on it"""
    opened(viewer, page)
    tab(page, "Art")
    box = page.evaluate("""() => { const b = document.querySelector('#grid .vw-art-missing');
      return b && Object.assign(b.getBoundingClientRect().toJSON(), { text: b.textContent }); }""")
    assert box and "Spend by city" in box["text"] and "chart" in box["text"].lower()
    anchor = page.evaluate(CELL, "E2")
    assert box["left"] == pytest.approx(anchor["left"], abs=1)
    assert box["top"] == pytest.approx(anchor["top"], abs=1)
    assert box["width"] > 100 and box["height"] > 100
    # Its title at its top left, which a phone shows even when the rest runs off the screen
    title = page.evaluate("() => document.querySelector('#grid .vw-art-missing b').getBoundingClientRect().toJSON()")
    assert title["left"] - box["left"] < 16 and title["top"] - box["top"] < 16


# ---------------------------------------------------------------------------
# Frozen panes
# ---------------------------------------------------------------------------

def test_frozen_rows_and_columns_stay_on_screen_as_the_rest_scrolls(viewer, page):
    """layout.xlsx is frozen at B3: rows 1 and 2 under the letters, column A beside the numbers"""
    cell = opened(viewer, page)
    page.set_viewport_size({"width": 300, "height": 400})
    before = cell("E9")
    page.evaluate("() => window.scrollTo(150, 300)")
    page.wait_for_timeout(100)
    sx, sy = page.evaluate("[scrollX, scrollY]")
    assert sx > 100 and sy > 100
    assert page.evaluate("() => document.querySelector('#grid .vw-head').getBoundingClientRect().top") == \
        pytest.approx(0, abs=0.5)
    letters_foot = page.evaluate("() => document.querySelector('#grid .vw-letters').getBoundingClientRect().bottom")
    assert cell("A1")["top"] == pytest.approx(letters_foot, abs=1)
    assert cell("A2")["top"] == pytest.approx(letters_foot + 20, abs=1)
    assert page.evaluate("() => document.querySelector('#grid th.vw-rh[data-n=\"9\"]').getBoundingClientRect().left") == \
        pytest.approx(0, abs=0.5)
    assert cell("A9")["left"] < 40
    # And the rest has scrolled under them
    assert cell("E9")["left"] == pytest.approx(before["left"] - sx, abs=0.5)
    assert cell("E9")["top"] == pytest.approx(before["top"] - sy, abs=0.5)


def test_frozen_panes_too_big_for_a_phone_scroll_like_the_rest(viewer, page):
    """Frozen rows taller than half the screen would leave a phone a sliver to scroll in"""
    opened(viewer, page)
    tab(page, "Frozen tall")
    assert page.evaluate("() => document.querySelectorAll('#grid .vw-head tr[data-r]').length") == 0
    assert "Row 1" in page.text_content("#sheet")


# ---------------------------------------------------------------------------
# A cell tapped
# ---------------------------------------------------------------------------

def tap(page, address):
    page.evaluate("(a) => vwCellAt(a).scrollIntoView({ block: 'center', inline: 'center' })", address)
    box = page.evaluate("(a) => vwCellAt(a).getBoundingClientRect().toJSON()", address)
    page.mouse.click(box["left"] + 5, box["top"] + box["height"] / 2)


def test_a_cell_tapped_is_outlined_its_letter_and_number_lit_and_shown_whole_in_the_bar(viewer, page):
    """A1's title is cut off where the columns end; the bar shows all of it"""
    opened(viewer, page)
    tap(page, "A1")
    page.wait_for_function(BAR)
    said = page.evaluate(BAR)
    assert said.startswith("A1")
    assert "A title long enough to run on over the empty cells beside it" in said
    assert page.evaluate("() => vwCellAt('A1').classList.contains('vw-sel')")
    lit = page.evaluate("() => [...document.querySelectorAll('#grid th.vw-lit')].map(t => t.dataset.n)")
    assert sorted(lit) == ["1", "A"]


def test_the_bar_shows_a_numbers_value_where_its_format_shows_less(viewer, page):
    """C3 is too wide for its column, and C5's date is written dd-mm-yyyy"""
    opened(viewer, page)
    tap(page, "C3")
    page.wait_for_function(BAR)
    assert "1234567.891" in page.evaluate(BAR)
    tap(page, "C5")
    page.wait_for_function("() => (document.getElementById('cellbar').innerText || '').indexOf('C5') === 0")
    said = page.evaluate(BAR)
    assert "21-10-2026" in said and "2026-10-21" in said


def test_the_bar_shows_a_cells_note_and_link(viewer, page):
    opened(viewer, page)
    tap(page, "A12")
    page.wait_for_function("() => (document.getElementById('cellbar').innerText || '').indexOf('A12') === 0")
    assert "Asha: A note about this cell" in page.evaluate(BAR)
    url = page.url
    tap(page, "A13")
    page.wait_for_function("() => (document.getElementById('cellbar').innerText || '').indexOf('A13') === 0")
    assert "https://example.com/report" in page.evaluate(BAR)
    # A link in a document is never followed
    assert page.url == url


def test_a_merged_cell_tapped_is_selected_whole(viewer, page):
    opened(viewer, page)
    tap(page, "E14")
    page.wait_for_function(BAR)
    assert page.evaluate(BAR).startswith("E14:G15")


def test_back_puts_the_selection_down_before_it_leaves(viewer, page, port):
    """The page says it has something for Back to do, and puts the bar away when Back comes"""
    opened(viewer, page)
    channel = port()
    tap(page, "A3")
    page.wait_for_function(BAR)
    channel.wait_for(r"cover 1")
    channel.clear_inbox()
    channel.close_over()
    page.wait_for_function("() => document.getElementById('cellbar').hidden")
    channel.wait_for(r"cover 0")
    assert page.evaluate("() => document.querySelectorAll('#grid .vw-sel, #grid .vw-lit').length") == 0


def test_the_bars_copy_puts_the_cells_text_on_the_clipboard(browser, server):
    context = browser.new_context(viewport={"width": 980, "height": 1600})
    context.grant_permissions(["clipboard-read", "clipboard-write"])
    try:
        own = context.new_page()
        server.show("layout.xlsx")
        own.goto(server.url("xlsx.html"))
        wait_until_done(own)
        tap(own, "D3")
        own.wait_for_function(BAR)
        own.click("#cellbar .vw-cb-copy")
        assert own.evaluate("() => navigator.clipboard.readText()") == "Right"
    finally:
        context.close()


def test_switching_sheets_puts_the_selection_down(viewer, page):
    opened(viewer, page)
    tap(page, "A3")
    page.wait_for_function(BAR)
    tab(page, "No grid")
    assert page.evaluate(BAR) is None


# ---------------------------------------------------------------------------
# Zoom
# ---------------------------------------------------------------------------

@pytest.fixture
def phone(browser, server):
    """A page shaped like a phone, with touch, as the WebView lays a sheet out at the screen's width"""
    context = browser.new_context(viewport={"width": 412, "height": 915}, device_scale_factor=2.625,
                                  is_mobile=True, has_touch=True)
    own = context.new_page()
    cdp = context.new_cdp_session(own)

    def open_sheet(name="budget.xlsx", **params):
        server.show(name)
        own.goto(server.url("xlsx.html", **params))
        wait_until_done(own)
        return own

    def touch(kind, points):
        cdp.send("Input.dispatchTouchEvent", {
            "type": kind, "touchPoints": [{"x": x, "y": y, "id": i} for i, (x, y) in enumerate(points)]})

    yield open_sheet, touch
    context.close()


def place(page, x, y):
    return page.evaluate("([x, y]) => vwSheetPlace(x, y)", [x, y])


def test_a_sheet_opens_at_excels_size_where_it_opened_too_small_to_read(phone):
    """Laid out at 980 px and zoomed to fit, 11 point text came out under 6 dp; now a px is a dp"""
    open_sheet, _ = phone
    page = open_sheet("layout.xlsx")
    assert page.evaluate("() => vwSheetZoom()") == 1
    assert page.evaluate("() => visualViewport.scale") == 1
    assert page.evaluate("() => parseFloat(getComputedStyle(vwCellAt('A11')).fontSize)") == pytest.approx(14.67, abs=0.05)


def test_a_sheet_opens_zoomed_to_the_phones_text_size(phone):
    open_sheet, _ = phone
    page = open_sheet("layout.xlsx", type=130)
    assert page.evaluate("() => vwSheetZoom()") == pytest.approx(1.3)


def test_a_pinch_zooms_the_sheet_about_the_fingers(phone):
    open_sheet, touch = phone
    page = open_sheet("budget.xlsx")
    before = place(page, 206, 300)
    touch("touchStart", [(156, 300), (256, 300)])
    for k in range(1, 11):
        touch("touchMove", [(156 - 5 * k, 300), (256 + 5 * k, 300)])
    touch("touchEnd", [])
    page.wait_for_function("() => Math.abs(vwSheetZoom() - 2) < 0.01")
    after = place(page, 206, 300)
    assert after[0] == pytest.approx(before[0], abs=2)
    assert after[1] == pytest.approx(before[1], abs=2)
    # The page itself was never zoomed: the WebView's own pinch is off
    assert page.evaluate("() => visualViewport.scale") == 1


def test_a_double_tap_zooms_in_twice_about_the_place_tapped_and_the_next_back(phone):
    open_sheet, touch = phone
    page = open_sheet("budget.xlsx")
    before = place(page, 120, 500)
    for expected in (2, 1):
        for _ in range(2):
            touch("touchStart", [(120, 500)])
            touch("touchEnd", [])
            page.wait_for_timeout(60)
        page.wait_for_function(f"() => Math.abs(vwSheetZoom() - {expected}) < 0.01", timeout=5000)
        after = place(page, 120, 500)
        assert after[0] == pytest.approx(before[0], abs=2)
        assert after[1] == pytest.approx(before[1], abs=2)


def double_tap(page, touch, x, y, zoom):
    for _ in range(2):
        touch("touchStart", [(x, y)])
        touch("touchEnd", [])
        page.wait_for_timeout(60)
    page.wait_for_function(f"() => Math.abs(vwSheetZoom() - {zoom}) < 0.01", timeout=5000)
    # Past the time a tap's click could still come in
    page.wait_for_timeout(400)


def test_a_double_tap_leaves_the_selection_as_it_was(phone):
    """Its first tap selects a cell at once, without waiting to see if a second follows, and the zoom puts it back"""
    open_sheet, touch = phone
    page = open_sheet("layout.xlsx")
    # One cell selected by a tap, then double taps on another
    box = page.evaluate("() => vwCellAt('D3').getBoundingClientRect().toJSON()")
    touch("touchStart", [(box["left"] + 5, box["top"] + 5)])
    touch("touchEnd", [])
    page.wait_for_function("() => (document.getElementById('cellbar').innerText || '').indexOf('D3') === 0")
    page.wait_for_timeout(400)
    box = page.evaluate("() => vwCellAt('A9').getBoundingClientRect().toJSON()")
    x, y = box["left"] + 5, box["top"] + 5
    for zoom in (2, 1):
        double_tap(page, touch, x, y, zoom)
        assert page.evaluate(BAR).startswith("D3")
        assert page.evaluate("() => [...document.querySelectorAll('#grid td.vw-sel')].map(t => vwCellOf(t))") == ["D3"]
    # Nothing selected, and nothing after
    page.evaluate("() => vwPageCommand('x')")
    double_tap(page, touch, x, y, 2)
    assert page.evaluate(BAR) is None
    assert page.evaluate("() => document.querySelectorAll('#grid .vw-sel').length") == 0


def two_taps(page, touch, box):
    x, y = box["left"] + box["width"] / 2, box["top"] + box["height"] / 2
    for _ in range(2):
        touch("touchStart", [(x, y)])
        touch("touchEnd", [])
        page.wait_for_timeout(60)
    page.wait_for_timeout(500)


def test_two_quick_taps_on_a_tab_or_on_the_bars_close_are_no_double_tap_on_the_sheet(phone):
    """The page's touches were the whole page's, so two taps on a tab zoomed the sheet about the tabs, and
    two on Close put back the cell it closed, opening the bar again"""
    open_sheet, touch = phone
    page = open_sheet("layout.xlsx")
    two_taps(page, touch, page.evaluate("() => document.querySelector('#tabs button').getBoundingClientRect().toJSON()"))
    assert page.evaluate("() => vwSheetZoom()") == 1
    page.evaluate("() => [...document.querySelectorAll('#tabs button')].find(b => b.textContent === 'Layout').click()")
    box = page.evaluate("() => vwCellAt('D3').getBoundingClientRect().toJSON()")
    touch("touchStart", [(box["left"] + 5, box["top"] + 5)])
    touch("touchEnd", [])
    page.wait_for_function(BAR)
    page.wait_for_timeout(400)
    two_taps(page, touch, page.evaluate("() => document.querySelector('.vw-cb-close').getBoundingClientRect().toJSON()"))
    # The second tap lands on the sheet the closed bar uncovers, and may select a cell there, as one tap does
    bar = page.evaluate(BAR)
    assert bar is None or not bar.startswith("D3"), "the cell Close closed is not put back"
    assert page.evaluate("() => vwSheetZoom()") == 1


def test_a_cell_brought_into_view_is_brought_clear_of_the_bar_once_it_opens(viewer, page):
    """The page's scroll padding at its foot was set as a sheet was shown, the tabs' height, and not as
    the bar opened above them, so the arrows brought each next cell to sit under the bar"""
    opened(viewer, page)
    page.evaluate("() => vwCellAt('A3').click()")
    page.wait_for_function(BAR)
    padding, under = page.evaluate("""() => [parseFloat(document.documentElement.style.scrollPaddingBottom),
      document.getElementById('cellbar').getBoundingClientRect().height + document.getElementById('tabs').getBoundingClientRect().height]""")
    assert padding == pytest.approx(under, abs=0.5)


def test_the_cell_bar_sits_on_the_tabs_with_no_sheet_showing_between(phone):
    """At a text size of 110% the tabs are 53.8 px tall, which offsetHeight rounds to 54. A phone gets
    such fractions at any size: its 1 px border is drawn as two device px, 0.76 px at a density of 2.625."""
    open_sheet, _ = phone
    page = open_sheet("layout.xlsx", type=110)
    page.evaluate("() => { const td = vwCellAt('A3'); td.click(); }")
    page.wait_for_function(BAR)
    bar, tabs, height = page.evaluate("""() => [document.getElementById('cellbar').getBoundingClientRect().bottom,
      document.getElementById('tabs').getBoundingClientRect().top, document.getElementById('tabs').getBoundingClientRect().height]""")
    assert height != round(height)
    assert bar == pytest.approx(tabs, abs=0.01)


def test_zoomed_in_the_letters_and_frozen_rows_stay_on_screen(phone, made):
    """What the WebView's own zoom could not do: a held row is held to the page it magnified"""
    open_sheet, _ = phone
    page = open_sheet(big_sheet(made, 300), top=56)
    page.evaluate("() => vwSheetZoom(2, 100, 400)")
    page.evaluate("() => window.scrollTo(200, 3000)")
    page.wait_for_timeout(100)
    assert page.evaluate("() => document.querySelector('#grid .vw-head').getBoundingClientRect().top") == \
        pytest.approx(56, abs=0.5)
    assert page.evaluate("() => document.querySelector('#grid .vw-p .vw-tl').getBoundingClientRect().left") == \
        pytest.approx(0, abs=0.5)


def test_a_zoom_lays_the_sheet_out_again_so_its_text_stays_sharp(phone):
    """Scaled as a picture while the fingers move, and laid out at the new size when they lift"""
    open_sheet, _ = phone
    page = open_sheet("layout.xlsx")
    page.evaluate("() => vwSheetZoom(2)")
    assert page.evaluate("() => document.getElementById('sheet').style.transform") == ""
    width = page.evaluate("() => vwCellAt('A3').getBoundingClientRect().width")
    assert width == pytest.approx(280, abs=1)


# ---------------------------------------------------------------------------
# Night, and a CSV
# ---------------------------------------------------------------------------

def test_at_night_the_gridlines_are_faint_and_the_selection_plain(viewer, page):
    page.emulate_media(color_scheme="dark")
    opened(viewer, page)
    ground = rgb(page.evaluate("() => getComputedStyle(document.body).backgroundColor"))
    line = rgb(page.evaluate("() => getComputedStyle(vwCellAt('D9')).borderRightColor"))
    assert 1.5 <= contrast(line, ground) <= 3
    tap(page, "D9")
    page.wait_for_function(BAR)
    outline = rgb(page.evaluate("() => getComputedStyle(vwCellAt('D9')).outlineColor"))
    assert contrast(outline, ground) >= 3


def test_a_csvs_columns_are_fitted_to_its_text(viewer, page, made):
    """
    A CSV says nothing of its widths, and at Excel's default 64 px a full row cuts off any text longer
    than a short word, as every cell of this one would be.
    """
    rows = ["Description,Supplier,Delivered to"] + [
        f"A description long enough to need a wide column {n},Northwind Traders Limited,Harbour Road warehouse {n}"
        for n in range(1, 6)]
    viewer("xlsx.html", made("wide-text.csv", "\n".join(rows) + "\n"))
    wait_for_sheet(page)
    wait_until_done(page)
    cells = page.evaluate("""() => [...document.querySelectorAll('#grid .vw-p .vw-tr tr')].slice(0, 6)
      .map(tr => [...tr.cells].slice(0, 3).map(td => [td.textContent,
        td.classList.contains('vw-cut') || !!td.querySelector('.vw-sp'), td.getBoundingClientRect().width]))""")
    assert cells[1][0][0].startswith("A description")
    for row in cells:
        for text, cut_or_ran_on, width in row:
            assert not cut_or_ran_on, text
            assert width > 64, text
    assert page.evaluate("() => document.getElementById('tabs').hidden")


# ---------------------------------------------------------------------------
# An .xls, read from its own records
# ---------------------------------------------------------------------------

def test_an_xls_opens_on_the_sheet_it_was_saved_showing_and_hides_its_hidden_ones(viewer, page):
    opened(viewer, page, "layout.xls")
    assert page.evaluate(TABS) == [["Cover", "false"], ["Layout", "true"], ["No grid", "false"]]
    assert "Kept out of sight" not in page.text_content("#sheet")


def test_an_xls_lays_its_sheet_out_as_its_records_say(viewer, page):
    """Widths from COLINFO, heights from ROW and DEFAULTROWHEIGHT, the frozen panes from WINDOW2 and PANE"""
    cell = opened(viewer, page, "layout.xls")
    assert cell("A1")["width"] == pytest.approx(140, abs=0.5)
    assert cell("C1")["width"] == pytest.approx(42, abs=0.5)
    assert cell("A2")["rowHeight"] == pytest.approx(40, abs=0.5)
    assert cell("A1")["rowHeight"] == pytest.approx(17, abs=0.5)
    assert cell("B3") is None and cell("A4") is None
    held = page.evaluate("() => [...document.querySelectorAll('#grid .vw-head tr[data-r]')].map(r => r.dataset.r)")
    assert sorted(set(held)) == ["0", "1"]
    assert page.evaluate("() => !!document.querySelector('#grid .vw-p .vw-tl.vw-freeze')")


def test_an_xls_row_nought_high_is_drawn_at_the_sheets_height_unless_set_so_by_hand(viewer, page):
    """MS-XLS asks two twips at least. POI's 45322.xls gives every row nought, and drew as an empty sheet;
    LibreOffice draws its rows. A row set by hand to nought is as good as hidden."""
    cell = opened(viewer, page, "layout.xls")
    assert cell("E10")["rowHeight"] == pytest.approx(17, abs=0.5)
    assert page.evaluate("() => document.querySelector('#grid tr[data-r=\"5\"]')") is None


def test_an_xls_sets_its_cells_as_its_records_say(viewer, page):
    """Alignment, wrapping and turning from each XF, its borders, and its font's size and name"""
    cell = opened(viewer, page, "layout.xls")
    assert cell("D3")["align"] == "right"
    assert cell("D5")["align"] == "center" and cell("D5")["valign"] == "middle"
    assert cell("E3")["white"] == "pre-wrap"
    assert 'class="vw-up"' in cell("A8")["html"]
    big = cell("A9")
    assert big["size"] == pytest.approx(26.67, abs=0.05) and big["family"].startswith('"Times New Roman"')
    assert page.evaluate(LOOK, "A9")["weight"] == "700"
    assert cell("E10")["bottom"] == ["1px", "solid", "rgb(0, 0, 0)"]
    # Arial, the workbook's own font, is as wide as Roboto, and takes no condensed face
    assert cell("A1")["family"].startswith("Arial") and "condensed" not in cell("A1")["family"]


def test_an_xls_sheet_with_its_gridlines_off_has_none(viewer, page):
    """C5 is empty in both, so only the gridlines say what its edges are"""
    cell = opened(viewer, page, "layout.xls")
    assert cell("C5")["right_"][2] != NONE
    tab(page, "No grid")
    assert cell("C5")["right_"][2] == NONE and cell("C5")["bottom"][2] == NONE
