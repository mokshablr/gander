"""
Scores the spreadsheet viewer's cell colours against LibreOffice's reading of the same files.

  python3 scripts/sheet-corpus/harness.py <dir> [--viewer <assets/viewer of another tree>]

Each workbook in <dir>/picked.txt is opened in xlsx.html in Chromium, as the viewer tests open
one, and the first piece of every sheet is read back: each cell's address, whether the file gave
it a fill, and its drawn fill, text colour and weight. Those are lined up with LibreOffice's HTML
export of the file in <dir>/refs/<extension>, cell by cell, and counted. LibreOffice leaves out hidden
sheets, rows and columns, which the viewer shows, so they are taken out before lining up, and a
sheet is matched to LibreOffice's table whose texts line up with it best. The counts go to
<dir>/score.json, and a line for each format is printed.
"""
import argparse
import collections
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tests/viewer"))
import server as viewer_server  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

# Cells read from each sheet's first piece, enough for every sheet in the corpus but one 15,800
# columns wide, whose every cell's computed style would take minutes to read
CELLS_READ = 4000

# LibreOffice's rows kept from each table: a first piece is at most 500 rows (PIECE_ROWS_MAX in
# xlsx.js), and LibreOffice exports every formatted row of a sheet, 65,536 of them in some .xls
LO_ROWS_KEPT = 1000

# An export larger than this is passed over: POI's 51535.xls comes out at 2.1 GB
LO_LARGEST = 50 * 1024 * 1024


class LibreOfficeTables(HTMLParser):
    """LibreOffice's HTML export: a table for each sheet, each cell's fill, text colour and weight."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tables, self.cell, self.depth = [], None, 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "table":
            self.depth += 1
            if self.depth == 1:
                self.tables.append({"cells": {}, "taken": set(), "row": -1, "col": 0})
        if not self.tables or self.depth != 1:
            return
        t = self.tables[-1]
        if tag == "tr":
            t["row"] += 1
            t["col"] = 0
        elif tag == "td" and t["row"] < LO_ROWS_KEPT:
            r, c = t["row"], t["col"]
            while (r, c) in t["taken"]:
                c += 1
            rows, cols = int(a.get("rowspan", 1)), int(a.get("colspan", 1))
            t["taken"].update((r + i, c + j) for i in range(rows) for j in range(cols))
            self.cell = {"fill": (a.get("bgcolor") or "").upper() or None, "ink": None, "b": False, "text": ""}
            t["cells"][(r, c)] = self.cell
            t["col"] = c + cols
        elif self.cell is not None:
            if tag == "font" and a.get("color") and self.cell["ink"] is None:
                self.cell["ink"] = a["color"].upper()
            elif tag == "b":
                self.cell["b"] = True

    def handle_endtag(self, tag):
        if tag == "table":
            self.depth -= 1
        elif tag == "td":
            self.cell = None

    def handle_data(self, data):
        if self.cell is not None:
            self.cell["text"] += data


# Each cell's address as the page maps its tables, so the frozen panes' tables and the rest line up
READ_CELLS = """(most) => [...document.querySelectorAll('#grid td')].slice(0, most).map(td => {
  const cs = getComputedStyle(td);
  return [vwCellOf(td) || '', td.classList.contains('vw-f'), cs.backgroundColor, cs.color, cs.fontWeight,
          td.textContent];
})"""

# Which sheets, rows and columns are hidden, as SheetJS reads them
READ_HIDDEN = """async () => {
  const wb = XLSX.read(new Uint8Array(await (await fetch(vwDocUrl())).arrayBuffer()),
    {type: 'array', cellStyles: true, dense: true});
  const hidden = list => (list || []).map((x, i) => x && x.hidden ? i : -1).filter(i => i >= 0);
  return wb.SheetNames.map((n, i) => ({
    name: n,
    sheet: !!((wb.Workbook && wb.Workbook.Sheets && wb.Workbook.Sheets[i] || {}).Hidden),
    rows: hidden(wb.Sheets[n]['!rows']), cols: hidden(wb.Sheets[n]['!cols'])}));
}"""

COUNTS = ("fill_same", "fill_missed", "fill_extra", "fill_differs", "ink_same", "ink_differs",
          "bold_same", "bold_differs")


def hex_of(css):
    m = re.match(r"rgba?\((\d+), (\d+), (\d+)(?:, ([\d.]+))?\)", css)
    if not m or (m.group(4) is not None and float(m.group(4)) == 0):
        return None
    return "#%02X%02X%02X" % tuple(int(m.group(i)) for i in (1, 2, 3))


def near(a, b, within=3):
    if a is None or b is None:
        return a == b
    return all(abs(int(a[i:i + 2], 16) - int(b[i:i + 2], 16)) <= within for i in (1, 3, 5))


def column_of(letters):
    n = 0
    for ch in letters:
        n = n * 26 + ord(ch) - 64
    return n - 1


def plain_text(s):
    return re.sub(r"\s+", " ", s or "").strip()


def offsets(mine, theirs):
    """How many texts line up at each offset between the two sheets"""
    where = {}
    for (r, c), v in theirs.items():
        if plain_text(v["text"]):
            where.setdefault(plain_text(v["text"]), []).append((r, c))
    votes = collections.Counter()
    for (r, c), v in mine.items():
        for lr, lc in where.get(v["text"], [])[:3] if v["text"] else []:
            votes[(r - lr, c - lc)] += 1
    return votes


def score_sheet(mine, theirs):
    votes = offsets(mine, theirs)
    (dr, dc), _ = votes.most_common(1)[0]
    counts, disagreements = collections.Counter(), []
    for (r, c), v in mine.items():
        lo = theirs.get((r - dr, c - dc))
        if lo is None:
            continue
        # A white fill is the paper's own colour
        want = lo["fill"] if lo["fill"] not in (None, "#FFFFFF") else None
        if v["fill"] is None and want is None:
            pass
        elif v["fill"] is None:
            counts["fill_missed"] += 1
            disagreements.append(("missed", r, c, None, want))
        elif want is None:
            counts["fill_extra"] += 1
            disagreements.append(("extra", r, c, v["fill"], None))
        elif near(v["fill"], want):
            counts["fill_same"] += 1
        else:
            counts["fill_differs"] += 1
            disagreements.append(("differs", r, c, v["fill"], want))
        if v["text"]:
            kind = "ink_same" if near(v["ink"], lo["ink"] or "#000000") else "ink_differs"
            counts[kind] += 1
            counts["bold_same" if v["b"] == lo["b"] else "bold_differs"] += 1
    return counts, disagreements[:12]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dir")
    parser.add_argument("--viewer", help="the assets/viewer folder of another tree")
    args = parser.parse_args()
    work = Path(args.dir)
    if args.viewer:
        viewer_server.VIEWER = Path(args.viewer).resolve()
    names = [n.strip() for n in (work / "picked.txt").read_text().splitlines() if n.strip()]
    results = []
    server = viewer_server.ViewerServer()
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 980, "height": 900})
        for name in names:
            entry = {"file": name, "sheets": []}
            results.append(entry)
            ref = work / "refs" / Path(name).suffix.lstrip(".").lower() / (Path(name).stem + ".html")
            if not ref.exists():
                entry["skip"] = "no LibreOffice export"
                continue
            if ref.stat().st_size > LO_LARGEST:
                entry["skip"] = "LibreOffice's export is too large to read"
                continue
            lo = LibreOfficeTables()
            lo.feed(ref.read_text(encoding="utf-8", errors="replace"))
            server.show(work / "files" / name)
            try:
                page.goto(server.url("xlsx.html"))
                page.wait_for_function(
                    "() => { const e = document.getElementById('vw-status');"
                    " return e && (getComputedStyle(e).display === 'none' || e.classList.contains('vw-error')); }",
                    timeout=30000)
            except Exception as e:
                entry["skip"] = "timed out: " + str(e)[:60]
                continue
            if page.evaluate("() => document.getElementById('vw-status').classList.contains('vw-error')"):
                entry["skip"] = "could not open it"
                continue
            hidden = page.evaluate(READ_HIDDEN)
            # A hidden sheet has no tab, so each tab is found by its sheet's name
            tabs = page.query_selector_all("#tabs button")
            shown = [t.text_content() for t in tabs] or [None]
            for k, tab_name in enumerate(shown):
                if k:
                    tabs[k].click()
                index = next((i for i, h in enumerate(hidden) if h["name"] == tab_name), k)
                sheet = {"index": index}
                entry["sheets"].append(sheet)
                if hidden[index]["sheet"]:
                    sheet["skip"] = "hidden"
                    continue
                rows, cols = set(hidden[k]["rows"]), set(hidden[k]["cols"])
                mine = {}
                for address, filled, fill, ink, weight, text in page.evaluate(READ_CELLS, CELLS_READ):
                    m = re.match(r"([A-Z]+)(\d+)$", address)
                    if not m:
                        continue
                    r, c = int(m.group(2)) - 1, column_of(m.group(1))
                    if r in rows or c in cols:
                        continue
                    # Where LibreOffice puts it, with the hidden rows and columns taken out
                    at = (r - sum(h < r for h in rows), c - sum(h < c for h in cols))
                    # White is the paper's own colour, which LibreOffice's export gives as none
                    colour = hex_of(fill) if filled else None
                    mine[at] = {"fill": None if colour == "#FFFFFF" else colour, "ink": hex_of(ink),
                                "b": int(weight) >= 600, "text": plain_text(text)}
                # The table with the most texts lined up at any one offset; summed over every offset,
                # a table full of the same few numbers would win
                best = max(((max(offsets(mine, t["cells"]).values(), default=0), i) for i, t in enumerate(lo.tables)),
                           default=(0, -1))
                if best[0] == 0:
                    sheet["skip"] = "no table of LibreOffice's lines up with it"
                    continue
                counts, sheet["disagreements"] = score_sheet(mine, lo.tables[best[1]]["cells"])
                sheet.update(counts)
        browser.close()
    server.stop()
    (work / "score.json").write_text(json.dumps(results, indent=1))

    for fmt in (".xlsx", ".xlsm", ".xls"):
        total, files, disagreeing = collections.Counter(), 0, 0
        for entry in (e for e in results if e["file"].lower().endswith(fmt) and not e.get("skip")):
            files += 1
            sheets = [s for s in entry["sheets"] if not s.get("skip")]
            for s in sheets:
                total.update({k: s.get(k, 0) for k in COUNTS})
            disagreeing += any(s.get("fill_missed", 0) + s.get("fill_extra", 0) + s.get("fill_differs", 0)
                               for s in sheets)
        filled = sum(total[k] for k in COUNTS[:4])

        def alike(same, other):
            return "%.1f%%" % (100 * total[same] / max(1, total[same] + total[other]))
        print(f"{fmt}: {files} files, {disagreeing} with a fill LibreOffice draws otherwise; "
              f"filled cells {filled}, {100 * total['fill_same'] / max(1, filled):.1f}% alike "
              f"(missed {total['fill_missed']}, extra {total['fill_extra']}, other colour {total['fill_differs']}); "
              f"text colour {alike('ink_same', 'ink_differs')} alike, bold {alike('bold_same', 'bold_differs')}")
    passed = [e["file"] + " (" + e["skip"] + ")" for e in results if e.get("skip")]
    print(f"passed over: {', '.join(passed) or 'none'}")


if __name__ == "__main__":
    main()
