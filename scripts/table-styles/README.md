# Excel's table styles

A workbook names the style of a range formatted as a table, such as `TableStyleMedium2`, and never
holds it: Excel knows its 60 built-in styles by name. `make.py` writes them into
`app/src/main/assets/viewer/xlsx-tables.js` for the sheet viewer, from `presetTableStyles.xml`, the
list ECMA-376 gives, in Apache POI's copy:

```sh
curl -sSfLO https://raw.githubusercontent.com/apache/poi/trunk/poi-ooxml/src/main/resources/org/apache/poi/xssf/usermodel/presetTableStyles.xml
python3 scripts/table-styles/make.py presetTableStyles.xml
```

Each family of seven is written once, with its accent left for the page to fill in, which brings
the 519 KB list to 8 KB; the script stops if a family's styles are not one shape in seven colours.
