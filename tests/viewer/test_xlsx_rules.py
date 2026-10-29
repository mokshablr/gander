"""
xlsx-rules.js, conditional formatting as Excel works it out, called directly on a stand-in sheet:
errors and empty text as Excel takes them, Excel 2010's data bars, and what a rule over a long
sheet may cost. test_xlsx_layout.py checks rules drawn from a workbook.
"""

import pytest

from test_xlsx_layout import opened

# A sheet of [cells], rows of SheetJS cells, with [rules], each over A1 down the column
SHEET = """([cells, rules]) => {
  const sheet = {
    lastRow: cells.length - 1, lastCol: Math.max(...cells.map(r => r.length)) - 1,
    cell: (r, c) => (cells[r] || [])[c],
    style: { rules: rules.map((rule, k) => Object.assign({ ranges: [{ s: { r: 0, c: 0 }, e: { r: cells.length - 1, c: 0 } }],
      priority: k + 1, look: { fill: 'FFC7CE' } }, rule)) }
  };
  const rows = vwConditional(sheet);
  return cells.map((row, r) => { const s = rows && rows[r] && rows[r][0]; return s ? (s.bar ? s.bar.width : s.fill) : null; });
}"""

NA = {"t": "e", "v": 42, "w": "#N/A"}


@pytest.fixture
def ruled(viewer, page):
    opened(viewer, page)
    return lambda cells, *rules: page.evaluate(SHEET, [cells, list(rules)])


def expression(formula):
    return {"type": "expression", "formulas": [formula]}


def test_a_formula_with_a_function_gander_does_not_know_leaves_its_rule_off_even_under_iserror(ruled):
    """ISERROR took the failure to work VALUE out for an error, and lit every cell, numbers too"""
    cells = [[{"t": "n", "v": 5}], [{"t": "s", "v": "x"}]]
    assert ruled(cells, expression("ISERROR(VALUE($A1))")) == [None, None]


def test_iserror_iserr_and_isna_tell_the_errors_apart_as_excel_does(ruled):
    cells = [[NA], [{"t": "e", "v": 7, "w": "#DIV/0!"}], [{"t": "n", "v": 1}]]
    assert ruled(cells, expression("ISERROR($A1)")) == ["FFC7CE", "FFC7CE", None]
    assert ruled(cells, expression("ISERR($A1)")) == [None, "FFC7CE", None]
    assert ruled(cells, expression("ISNA($A1)")) == ["FFC7CE", None, None]
    # A text that is no number is #VALUE! to ISERROR, as it is to Excel
    assert ruled([[{"t": "s", "v": "abc"}]], expression("ISERROR($A1+1)")) == ["FFC7CE"]


@pytest.mark.parametrize("formula", ['NOT($A1="Paid")', "LEN($A1)>0", "IF($A1>100,FALSE,TRUE)", "OR(TRUE,$A1)"])
def test_an_error_a_cell_holds_is_the_answer_of_the_functions_over_it_so_the_rule_is_off(ruled, formula):
    assert ruled([[NA], [{"t": "s", "v": "Due"}]], expression(formula))[0] is None


def test_a_formulas_empty_text_is_text_where_a_blank_is_nought(ruled):
    """=IF(B2="","",B2*C2) leaves "" in a cell, which Excel ranks above every number, and is not blank"""
    empty = {"t": "s", "v": "", "f": 'IF(B1="","",B1*C1)'}
    cells = [[empty], [None], [{"t": "n", "v": 50}]]
    assert ruled(cells, {"type": "cellIs", "operator": "lessThan", "formulas": ["100"]}) == [None, "FFC7CE", "FFC7CE"]
    assert ruled(cells, expression("ISBLANK($A1)")) == [None, "FFC7CE", None]
    assert ruled(cells, expression('$A1=""')) == ["FFC7CE", "FFC7CE", None]


def test_a_data_bar_excel_2010_starts_at_nought_runs_as_long_as_it_says(ruled):
    cells = [[{"t": "n", "v": v}] for v in (50, 75, 100)]
    newer = {"type": "dataBar", "cfvos": [{"type": "autoMin", "val": ""}, {"type": "autoMax", "val": ""}],
             "colours": ["638EC6"], "lengths": [0, 100]}
    assert ruled(cells, newer) == [50, 75, 100]
    # Excel 2007's, from the lowest value, a tenth of the cell to nine tenths
    older = {"type": "dataBar", "cfvos": [{"type": "min", "val": ""}, {"type": "max", "val": ""}], "colours": ["638EC6"]}
    assert ruled(cells, older) == [10, 50, 90]


def test_a_formula_rule_over_more_cells_than_it_may_work_out_is_left_off_and_a_cheaper_one_drawn(page, viewer):
    opened(viewer, page)
    given = page.evaluate("""() => {
      const sheet = { lastRow: 999999, lastCol: 9, cell: () => ({ t: 'n', v: 1 }),
        style: { rules: [
          { type: 'expression', formulas: ['AND($A1>0,$A1<2,$A1<>3)'], priority: 1, look: { fill: 'FF0000' },
            ranges: [{ s: { r: 0, c: 0 }, e: { r: 999999, c: 9 } }] },
          { type: 'expression', formulas: ['$A1=1'], priority: 2, look: { ink: '0000FF' },
            ranges: [{ s: { r: 0, c: 0 }, e: { r: 9, c: 0 } }] }] } };
      const rows = vwConditional(sheet);
      return [rows[0][0].fill || null, rows[0][0].ink, rows[500] === undefined];
    }""")
    assert given == [None, "0000FF", True]


def test_cells_a_rule_gives_the_same_share_one_look_until_another_rule_adds_to_one(page, viewer):
    opened(viewer, page)
    shared, apart, kept = page.evaluate("""() => {
      const sheet = { lastRow: 2, lastCol: 0, cell: () => ({ t: 'n', v: 1 }),
        style: { rules: [
          { type: 'expression', formulas: ['TRUE'], priority: 1, look: { fill: 'FF0000' }, ranges: [{ s: { r: 0, c: 0 }, e: { r: 2, c: 0 } }] },
          { type: 'expression', formulas: ['TRUE'], priority: 2, look: { b: true }, ranges: [{ s: { r: 0, c: 0 }, e: { r: 0, c: 0 } }] }] } };
      const rows = vwConditional(sheet);
      return [rows[1][0] === rows[2][0], rows[0][0] !== rows[1][0], [rows[0][0].b, rows[1][0].b === undefined, rows[0][0].fill]];
    }""")
    assert shared and apart
    assert kept == [True, True, "FF0000"]
