# Measuring the spreadsheet colours

`xlsx-styles.js` is Gander's own reader of the colours a workbook gives its cells: fills, text
colour, bold, italic, underline and strike-through, for an .xlsx from its parts and for an .xls
from its records. The viewer tests prove it on two fixtures written for it; what proves it is real
files, scored against LibreOffice's reading of the same files. This directory is that measurement.
Run it before trusting a change to the reader.

Needs `gh`, LibreOffice (`soffice` on the path) and the Python the viewer tests use (Playwright).

```sh
W=/tmp/sheet-corpus                             # anywhere outside the repo
scripts/sheet-corpus/fetch.sh "$W"              # Apache POI's test workbooks: ~760, 24 MB
scripts/sheet-corpus/refs.sh "$W"               # LibreOffice's HTML of each that has colours
python3 scripts/sheet-corpus/harness.py "$W"    # a line per format; each cell in $W/score.json
```

`--viewer` scores another tree's `app/src/main/assets/viewer`, such as a release's from
`git archive`.

Each cell of each sheet's first piece is compared: whether the file fills it and with what, and
its text's colour and weight. A fill is "missed" where LibreOffice draws one and Gander none,
"extra" the other way round. White counts as no fill, as the viewer draws it.

Where Gander and LibreOffice part, read the file before blaming the reader:

- LibreOffice applies conditional formatting and Gander does not, so a sheet with any is charged
  for every cell a rule colours or uncolours.
- LibreOffice leaves hidden sheets, rows and columns out of its export, and so does the viewer since
  2026-10-28. The harness lines the two up by text, and a chart sheet can still be lined up with
  the wrong table.
- In some .xls files LibreOffice draws no fill where a cell's own record gives one, and SheetJS
  reads that record as Gander does: `39634.xls`, `42464-ExpPtg-ok.xls` and `52447.xls`, where it
  gave three cells with three different records one style. In an .xls it also drew a cell written
  plain in a styled row with the row's style (D5 of the fixture `colours.xls`), not its own.

## 2026-10-27

When the reader was written, over the 352 of POI's workbooks that colour their cells and that
both LibreOffice and the viewer open:

| Format | Files | Files with a fill drawn otherwise | Filled cells | Fills alike | Text colour alike | Bold alike |
|---|---|---|---|---|---|---|
| .xlsx | 148 | 6 | 13,264 | 91.4% | 99.7% | 100.0% |
| .xlsm | 6 | 1 | 1,028 | 93.7% | 100.0% | 100.0% |
| .xls | 198 | 34 | 20,887 | 78.8% | 94.7% | 94.9% |

`pick.py` picked 365; 13 were passed over. LibreOffice exports nothing for four, encrypted or
broken; the viewer cannot open seven, as it could not before, five of them POI's fuzzing cases;
and two come out of LibreOffice at 418 MB and 2.1 GB of HTML, since it writes every row a sheet
formats, so mind the disk.

Every .xlsx and .xlsm disagreement was conditional formatting, or one of `57181.xlsm`'s chart
sheets lined up with the wrong table, by reading each; 1,029 of the cells are one sheet whose rules
paint its blank rows white. The .xls figures carry LibreOffice's own readings, above.

Before writing a reader, these were measured. SheetJS's community build, which Gander reads
workbooks with, gives a cell no fill where Excel names its colour by palette number or by the first
theme colour, moves every fill after a gradient onto other cells, and reads no font, no row and no
column. ExcelJS 4.4.0 reads an .xlsx's styles whole, but is 863 KB minified, would read every
workbook a second time, and reads no .xls.

## 2026-10-28

The viewer drew each sheet as one HTML table from SheetJS until now; it draws its own grid now, two
tables a piece, with its letters, numbers, frozen panes, widths, heights and borders, and the
harness reads each cell's address from the page (`vwCellOf`). On 50 of the picked files, 25 .xlsx
and 25 .xls, the colours came out as before: .xlsx fills 100.0% alike against LibreOffice both
before and after, text colour 98.6% against 98.5%; .xls fills 91.9% against 92.1%, the cells counted
differing by the hidden rows now left out. All 746 of POI's files opened as before or better: 710
against 709, none that opened before failing, in 56.2 s against 55.7 s.
