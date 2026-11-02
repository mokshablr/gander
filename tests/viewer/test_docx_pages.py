"""
Issue #47: a Word file in pages where Word recorded them, numbered, and counted for the app.

word-pages.docx is six pages in Word and word-unrecorded.docx four, two of them begun where
Word could save no record; make_fixtures.py says where each one ends and why.
"""

import re
import zipfile

import pytest

from helpers import wait_until_done
from port import _HANDSHAKE

FIXTURE = "word-pages.docx"
UNRECORDED = "word-unrecorded.docx"

PAGES = """() => [...document.querySelectorAll('.docx-wrapper > section.docx')].map(s => ({
  articles: [...s.querySelectorAll(':scope > article')]
    .map(a => a.innerText.replace(/\\s+/g, ' ').trim()),
  footer: [...s.querySelectorAll(':scope > footer')]
    .map(f => f.innerText.replace(/\\s+/g, ' ').trim()).join(' | '),
  notes: [...s.querySelectorAll(':scope > ol')].map(o => o.innerText.trim()).join(' | ')
}))"""


def open_word(viewer, page, fixture=FIXTURE, **params):
    viewer("docx.html", fixture, **params)
    wait_until_done(page, timeout=25000)
    return page


def pages(page):
    return page.evaluate(PAGES)


def body(seen):
    return " / ".join(seen["articles"])


def remade(made, fixture_path, fixture, changes):
    """A copy of [fixture] with each part named in [changes] passed through its function."""
    with zipfile.ZipFile(fixture_path(fixture)) as z:
        items = {i.filename: z.read(i.filename) for i in z.infolist()}
    for name, change in changes.items():
        items[name] = change(items[name])
    target = made("remade-" + fixture, b"")
    with zipfile.ZipFile(target, "w") as z:
        for name, data in items.items():
            z.writestr(name, data)
    return target


def swap(old, new):
    def change(data):
        assert data.count(old) == 1
        return data.replace(old, new)
    return change


def counted(pages):
    """docProps/app.xml saying Word counted [pages] pages."""
    return lambda data: re.sub(rb"<Pages>\d+</Pages>", b"<Pages>%d</Pages>" % pages, data)


# ---------------------------------------------------------------------------
# Where the pages end
# ---------------------------------------------------------------------------

def test_a_page_ends_where_word_recorded_it(viewer, page):
    seen = pages(open_word(viewer, page))
    assert len(seen) == 6
    assert body(seen[0]).endswith("Second numbered item,")
    assert body(seen[1]).startswith("carried on to page two.")
    assert body(seen[4]).endswith("It runs on")
    assert body(seen[5]) == "to a second roman page."


def test_the_rest_of_a_cut_list_item_takes_no_number_of_its_own(viewer, page):
    open_word(viewer, page)
    said = page.evaluate("""() => {
      const ps = [...document.querySelectorAll('.docx-wrapper p')];
      const rest = ps.find(p => p.textContent.trim() === 'carried on to page two.');
      const third = ps.find(p => p.textContent.trim() === 'Third numbered item.');
      const look = p => ({
        marker: getComputedStyle(p, '::before').content,
        counts: getComputedStyle(p, '::before').counterIncrement,
        indent: getComputedStyle(p).textIndent
      });
      return { rest: look(rest), third: look(third) };
    }""")
    assert said["rest"]["marker"] == "none"
    assert said["rest"]["counts"] == "none"
    assert said["rest"]["indent"] == "0px"
    # The next item still has its number, which the rest not counting keeps at 3
    assert said["third"]["marker"] != "none"
    assert said["third"]["counts"] != "none"


def test_a_table_a_page_cuts_shows_its_rows_on_both_pages(viewer, page):
    seen = pages(open_word(viewer, page))
    assert body(seen[1]).endswith("Well Reading")
    assert body(seen[2]).startswith("North 4.2 South 3.8")
    assert "Well" not in body(seen[2])


def test_the_record_word_writes_after_a_page_break_adds_no_empty_page(viewer, page):
    seen = pages(open_word(viewer, page))
    assert body(seen[2]).endswith("Before the explicit break.")
    assert body(seen[3]).startswith("After the explicit break.")


def test_a_continuous_section_shares_its_page(viewer, page):
    seen = pages(open_word(viewer, page))
    assert seen[3]["articles"] == [
        "After the explicit break. Section one ends here.",
        "Section two shares page four.",
    ]


def test_a_record_at_the_top_of_a_column_turns_no_page(viewer, page):
    """Word records the top of each column as it does each page; this file is three pages in Word."""
    seen = pages(open_word(viewer, page, "word-columns.docx"))
    assert [p["articles"] for p in seen] == [
        [
            "Willowmere columns Page one begins here.",
            "First column. Second column. Third column.",
            "Back to one column. A long paragraph Word ended page one in,",
        ],
        ["carried on to page two."],
        ["Section four begins page three."],
    ]
    columns = page.evaluate(
        "() => getComputedStyle(document.querySelectorAll('.docx-wrapper article')[1]).columnCount"
    )
    assert columns == "3"


def test_a_footnote_sits_on_the_page_that_cites_it(viewer, page):
    seen = pages(open_word(viewer, page))
    assert [p["notes"] for p in seen] == ["", "Counted on the page that cites it.", "", "", "", ""]


def test_each_page_has_its_own_number(viewer, page):
    """
    The footer's fields saved 1 and 6. The last two sections have no footer of their own
    and show the first section's, as Word does, and the last counts in roman from i.
    """
    seen = pages(open_word(viewer, page))
    assert [p["footer"] for p in seen] == [
        "Page 1 of 6", "Page 2 of 6", "Page 3 of 6", "Page 4 of 6", "Page i of 6", "Page ii of 6",
    ]


def test_a_file_without_words_record_keeps_its_breaks_and_counts_its_pages(viewer, page, made, fixture_path):
    """
    The same document as WPS or LibreOffice would save it, with no record of where its pages
    began (#55). None of its text runs past its paper, so its pages are its breaks' and its
    sections', the continuous section sharing its page as in Word, and the footer counts them.
    """
    target = remade(made, fixture_path, FIXTURE, {
        "word/document.xml": lambda xml: xml.replace(b"<w:lastRenderedPageBreak/>", b""),
    })
    seen = pages(open_word(viewer, page, target))
    assert len(seen) == 3
    assert seen[1]["articles"] == [
        "After the explicit break. Section one ends here.",
        "Section two shares page four.",
    ]
    assert [p["footer"] for p in seen] == ["Page 1 of 3", "Page 2 of 3", "Page i of 3"]
    assert page.evaluate("vwPages.measure") is True
    assert page.evaluate("vwPages.turns") is None


# ---------------------------------------------------------------------------
# Pages measured where the file records none, issue #55
# ---------------------------------------------------------------------------

MEASURED = "word-measured.docx"

MEASURED_PAGES = """() => {
  document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1');
  return [...document.querySelectorAll('.docx-wrapper > section.docx')].map(s => ({
    lines: [...s.querySelectorAll(':scope > article')].map(a => a.innerText).join('\\n')
      .split('\\n').map(l => l.trim()).filter(Boolean),
    footer: [...s.querySelectorAll(':scope > footer')].map(f => f.innerText.trim()).join(' | '),
    over: s.getBoundingClientRect().height / parseFloat(getComputedStyle(s).minHeight)
  }));
}"""


def measured(viewer, page):
    open_word(viewer, page, MEASURED)
    return page.evaluate(MEASURED_PAGES)


def test_a_file_without_words_record_turns_where_its_paper_runs_out(viewer, page):
    seen = measured(viewer, page)
    assert len(seen) == 6
    assert page.evaluate("vwPages.measure") is True
    for p in seen:
        assert p["over"] < 1.005


def test_a_measured_page_takes_a_heading_kept_with_its_next_paragraph_along(viewer, page):
    seen = measured(viewer, page)
    assert seen[0]["lines"][-1] == "Filler A31"
    assert seen[1]["lines"][:2] == ["Kept heading", "Kept body line 1"]


def test_a_paragraph_a_measured_page_cuts_goes_on_at_its_next_line(viewer, page):
    seen = measured(viewer, page)
    assert seen[1]["lines"][-1] == "Cut line 3"
    assert seen[2]["lines"][0] == "Cut line 4"
    rest = page.evaluate("""() => [...document.querySelectorAll('.docx-wrapper p')]
      .find(p => p.innerText.trim().startsWith('Cut line 4')).className""")
    assert "vw-cont" in rest.split()


def test_a_paragraph_that_would_leave_a_line_alone_goes_to_the_next_page_whole(viewer, page):
    seen = measured(viewer, page)
    assert seen[2]["lines"][-1] == "Filler C23"
    assert seen[3]["lines"][:3] == ["Widow line 1", "Widow line 2", "Widow line 3"]


def test_a_measured_table_draws_its_header_row_again_on_the_next_page(viewer, page):
    seen = measured(viewer, page)
    assert seen[3]["lines"][-2:] == ["Row 06", "Value 06"]
    assert seen[4]["lines"][:4] == ["Entry", "Reading", "Row 07", "Value 07"]


def test_measured_pages_lose_and_repeat_nothing_but_the_header_row(viewer, page):
    seen = measured(viewer, page)
    rows = [x for n in range(1, 13) for x in (f"Row {n:02d}", f"Value {n:02d}")]
    expected = (
        [f"Filler A{n:02d}" for n in range(1, 32)]
        + ["Kept heading"] + [f"Kept body line {n}" for n in range(1, 6)]
        + [f"Filler B{n:02d}" for n in range(1, 24)]
        + [f"Cut line {n}" for n in range(1, 11)]
        + [f"Filler C{n:02d}" for n in range(1, 24)]
        + [f"Widow line {n}" for n in range(1, 4)]
        + [f"Filler D{n:02d}" for n in range(1, 22)]
        + ["Entry", "Reading"] + rows[:12] + ["Entry", "Reading"] + rows[12:]
        + ["Last line"]
    )
    assert [line for p in seen for line in p["lines"]] == expected


def test_measured_pages_are_numbered_in_the_footer(viewer, page):
    seen = measured(viewer, page)
    assert [p["footer"] for p in seen] == [f"Page {n} of 6" for n in range(1, 7)]


def test_blank_lines_a_measured_page_has_no_room_for_go_to_the_next(viewer, page):
    open_word(viewer, page, MEASURED)
    blank = page.evaluate("""() => [...document.querySelectorAll('.docx-wrapper > section.docx')]
      .map(s => [...s.querySelectorAll(':scope > article > p')].filter(p => !p.innerText.trim()).length)""")
    assert blank[4:] == [24, 6]


def test_a_file_too_big_to_draw_twice_keeps_its_long_sheet(viewer, page, made, fixture_path):
    """
    Over 20,000 drawn elements, a second drawing would add more than about two seconds on a
    phone, so the pages are not measured: 10,500 one-line paragraphs, a paragraph and a run each.
    """
    lines = b"<w:p><w:r><w:t>Many lines</w:t></w:r></w:p>" * 10500
    target = remade(made, fixture_path, MEASURED, {
        "word/document.xml": lambda xml: re.sub(rb"(<w:body>).*?(<w:sectPr)", lambda m: m.group(1) + lines + m.group(2),
                                                xml, flags=re.S),
    })
    open_word(viewer, page, target)
    assert page.evaluate("document.querySelectorAll('.docx-wrapper > section.docx').length") == 1
    assert page.evaluate("vwPages.measure") is True
    assert page.evaluate("vwPages.turns") is None


def test_a_file_that_records_its_pages_is_not_measured(viewer, page):
    open_word(viewer, page)
    assert page.evaluate("vwPages.measure") is False
    assert page.evaluate("document.querySelectorAll('[data-vw-p], [data-vw-r]').length") == 0


# ---------------------------------------------------------------------------
# Pages Word began where it could save no record, from its own count of them
# ---------------------------------------------------------------------------

def test_a_page_word_began_among_blank_lines_is_found(viewer, page):
    seen = pages(open_word(viewer, page, UNRECORDED))
    assert len(seen) == 4
    assert body(seen[0]).endswith("Ledger line 8 of page one.")
    assert body(seen[1]).startswith("Page two follows the blank lines.")
    assert body(seen[1]).endswith("Ledger line 8 of page two.")
    assert body(seen[2]).startswith("Page three begins where Word recorded it.")
    # Hidden only while the pages were being found
    shown = page.evaluate("getComputedStyle(document.getElementById('container')).visibility")
    assert shown == "visible"


def test_a_page_word_began_on_a_table_row_has_the_header_row_again(viewer, page):
    seen = pages(open_word(viewer, page, UNRECORDED))
    assert body(seen[2]).startswith(
        "Page three begins where Word recorded it. Entry Reading Reading 1 ")
    # The row depends on the font; the table runs on from wherever it is, under its header
    first = int(re.match(r"Entry Reading Reading (\d+) ", body(seen[3])).group(1))
    assert body(seen[2]).endswith(f"Reading {first - 1} Checked {first - 1}")
    assert body(seen[3]).endswith("Reading 18 Checked 18 After the table.")
    # At the row nearest halfway down what the sheet held, so the two pages come out alike
    tall = page.evaluate("""() => [...document.querySelectorAll('.docx-wrapper > section.docx')]
      .slice(2).map(s => s.querySelector(':scope > article').getBoundingClientRect().height)""")
    assert abs(tall[0] - tall[1]) < 0.2 * max(tall)


def test_the_footers_count_the_pages_found(viewer, page):
    seen = pages(open_word(viewer, page, UNRECORDED))
    assert [p["footer"] for p in seen] == ["Page 1 of 4", "Page 2 of 4", "Page 3 of 4", "Page 4 of 4"]


def test_a_count_no_higher_than_the_pages_drawn_changes_nothing(viewer, page, made, fixture_path):
    target = remade(made, fixture_path, UNRECORDED, {"docProps/app.xml": counted(2)})
    seen = pages(open_word(viewer, page, target))
    assert len(seen) == 2
    assert body(seen[0]).endswith("Ledger line 8 of page two.")
    assert page.evaluate("vwPages.turnAt") is None


def test_a_count_past_what_overflows_its_paper_adds_no_more(viewer, page, made, fixture_path):
    """A count can be stale, and a page that fits its paper is no place to look for another."""
    target = remade(made, fixture_path, UNRECORDED, {"docProps/app.xml": counted(9)})
    assert len(pages(open_word(viewer, page, target))) == 4

    target = remade(made, fixture_path, FIXTURE, {"docProps/app.xml": counted(9)})
    seen = pages(open_word(viewer, page, target))
    assert [p["footer"] for p in seen] == [
        "Page 1 of 6", "Page 2 of 6", "Page 3 of 6", "Page 4 of 6", "Page i of 6", "Page ii of 6",
    ]


def test_a_run_of_blank_lines_takes_one_turn_however_long(viewer, page, made, fixture_path):
    """Two turns among the blank lines would make a page with nothing on it."""
    target = remade(made, fixture_path, UNRECORDED, {
        "word/document.xml": swap(b"<w:p/>" * 24, b"<w:p/>" * 120),
        "docProps/app.xml": counted(5),
    })
    seen = pages(open_word(viewer, page, target))
    assert all(body(p) for p in seen)
    assert len(seen) == 4
    assert body(seen[1]).startswith("Page two follows the blank lines.")


PAGE_BREAK = b'<w:p><w:r><w:br w:type="page"/></w:r></w:p>'


def test_blank_lines_just_before_a_page_break_take_no_turn(viewer, page, made, fixture_path):
    """Nothing would follow the turn on its page, so it would make a page of nothing."""
    target = remade(made, fixture_path, UNRECORDED, {
        "word/document.xml": swap(b"<w:p/>" * 24, b"<w:p/>" * 40 + PAGE_BREAK),
    })
    seen = pages(open_word(viewer, page, target))
    assert all(body(p) for p in seen)
    assert len(seen) == 4
    assert body(seen[3]).startswith("Entry Reading Reading ")


def test_blank_lines_just_after_a_page_break_take_no_turn(viewer, page, made, fixture_path):
    """Nothing would be above the turn on its page, so it would make no page, and lose one."""
    target = remade(made, fixture_path, UNRECORDED, {
        "word/document.xml": swap(b"<w:p/>" * 24, PAGE_BREAK + b"<w:p/>" * 40),
    })
    seen = pages(open_word(viewer, page, target))
    assert len(seen) == 4
    assert body(seen[3]).startswith("Entry Reading Reading ")


def test_a_turn_leaves_at_least_half_a_page_above_it(viewer, page, made, fixture_path):
    """
    Word turns at a blank line only once the page above is full. Here the only blank lines
    sit just below page one's first line, and twenty lines of text fill out the sheet.
    """
    filler = b"".join(b"<w:p><w:r><w:t>Filler line %d.</w:t></w:r></w:p>" % n for n in range(1, 21))
    first = b"Ledger line 1 of page one.</w:t></w:r></w:p>"
    target = remade(made, fixture_path, UNRECORDED, {
        "word/document.xml": lambda xml: swap(first, first + b"<w:p/>" * 3)(
            swap(b"<w:p/>" * 24, filler)(xml)),
    })
    seen = pages(open_word(viewer, page, target))
    assert body(seen[0]).endswith("Ledger line 8 of page two.")
    assert len(seen) == 3


# ---------------------------------------------------------------------------
# Measured pages with nothing to spare; make_fixtures.py says what each file holds
# ---------------------------------------------------------------------------

FIT = "word-measured-fit.docx"
NOTES = "word-measured-notes.docx"
SECTIONS = "word-measured-sections.docx"
TAILS = "word-measured-tails.docx"
TABLES = "word-measured-tables.docx"

PAGES_PAST = """() => {
  document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1');
  return [...document.querySelectorAll('.docx-wrapper > section.docx')].map(s => ({
    lines: [...s.querySelectorAll(':scope > article')].map(a => a.innerText).join('\\n')
      .split('\\n').map(l => l.trim()).filter(Boolean),
    past: s.getBoundingClientRect().height - parseFloat(getComputedStyle(s).minHeight)
  }));
}"""


def fitted(viewer, page, fixture):
    open_word(viewer, page, fixture)
    assert page.evaluate("vwPages.measure") is True
    return page.evaluate(PAGES_PAST)


@pytest.mark.parametrize("fixture", [FIT, NOTES, TAILS])
def test_measured_pages_fit_their_paper(viewer, page, fixture):
    """
    Lines measured by their text alone left out half their spacing at each end of a page,
    a page's footnotes were left out of its room, and tabs were measured before docx-preview
    lined them up: each ran pages past their paper, and print gave each a sheet more.
    """
    seen = fitted(viewer, page, fixture)
    assert len(seen) > 3
    for n, p in enumerate(seen, 1):
        assert p["past"] <= 0.5, (n, p["past"], p["lines"][-1:])


def full_line(n):
    """The paragraph of FIT that reads "Full <n>", n as a pattern."""
    return rb"<w:p>(?:(?!</w:p>).)*?<w:t>Full " + n + rb"</w:t>(?:(?!</w:p>).)*?</w:p>"


def test_a_page_break_after_a_full_page_leaves_no_blank_page(viewer, page, made, fixture_path):
    """
    A page break opening the paragraph after a full page: the break's empty line had no room
    on that page, so it turned a page of its own. How many lines fill a page depends on the
    fonts here: the file's 27 fit on one page in CI's container and on Windows, and 26 on a Mac.
    So the file is first remade with 45, which no fonts fit, and then with as many as its page
    held.
    """
    def more(xml):
        last = re.search(full_line(b"27"), xml, flags=re.S)
        assert last
        copies = b"".join(last.group(0).replace(b"Full 27", b"Full %02d" % n) for n in range(28, 46))
        return xml[:last.end()] + copies + xml[last.end():]

    seen = fitted(viewer, page, remade(made, fixture_path, FIT, {"word/document.xml": more}))
    start = next(p for p in seen if p["lines"][0] == "Full 01")
    held = sum(1 for line in start["lines"] if line.startswith("Full "))
    assert 20 < held < 45

    def keep(xml):
        return re.sub(full_line(rb"(\d\d)"), lambda m: b"" if int(m.group(1)) > held else m.group(0),
                      more(xml), flags=re.S)

    seen = fitted(viewer, page, remade(made, fixture_path, FIT, {"word/document.xml": keep}))
    full = next(n for n, p in enumerate(seen) if p["lines"][0] == "Full 01")
    assert seen[full]["lines"][-1] == f"Full {held:02d}"
    assert seen[full + 1]["lines"] == ["After the break"]


TAB_STOPS = """() => {
  document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1');
  return [...document.querySelectorAll('.docx-wrapper p')].filter(p => /^Stop \\d/.test(p.textContent)).map(p => {
    const box = p.getBoundingClientRect(), style = getComputedStyle(p);
    const left = box.left + parseFloat(style.paddingLeft) + parseFloat(style.borderLeftWidth);
    const edge = (text, side) => {
      const walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        const at = n.nodeValue.indexOf(text);
        if (at < 0) continue;
        const range = document.createRange();
        range.setStart(n, at);
        range.setEnd(n, at + text.length);
        return (range.getBoundingClientRect()[side] - left) * 0.75;
      }
      return null;
    };
    return [edge('second column', 'left'), edge('right', 'right')];
  });
}"""


def test_a_tab_takes_the_text_after_it_to_its_stop(viewer, page):
    """
    docx-preview lines tabs up from where getBoundingClientRect puts them, which takes the
    pages' zoom to the screen in from about Chromium 128. Lined up after that zoom, tabs fell
    short of their stops by a tenth. In points: stops at 2in, and right-aligned at 6in.
    """
    open_word(viewer, page, FIT)
    stops = page.evaluate(TAB_STOPS)
    assert len(stops) == 3
    for second, right in stops:
        assert abs(second - 144) <= 4.3, stops
        assert abs(right - 432) <= 13, stops


# The renderer of each drawing, so a test can hand its tabs to vwLineUpTabs again
KEEP_RENDERERS = """
window.__vwRenderers = [];
document.addEventListener('DOMContentLoaded', function () {
  var split = vwSplitAsWordDid;
  window.vwSplitAsWordDid = function (r) { window.__vwRenderers.push(r); return split(r); };
});
"""


def layouts(cdp):
    metrics = cdp.send("Performance.getMetrics")["metrics"]
    return next(m["value"] for m in metrics if m["name"] == "LayoutCount")


def test_tabs_are_lined_up_with_a_layout_for_each_tab_a_paragraph_holds(viewer, page):
    """
    docx-preview's pass read where a tab sat and then moved it, tab by tab, so the page was
    laid out again for every tab: on one long sheet, with the CPU slowed four times, 3,600 tabs
    took 3.6 s and 9,000 took 25 s. Here 67 tabs, in paragraphs of one or two, are lined up again.
    """
    page.add_init_script(KEEP_RENDERERS)
    open_word(viewer, page, FIT)
    tabs = page.evaluate("""() => {
      const r = window.__vwRenderers[window.__vwRenderers.length - 1];
      vwPages.tabs = { tabs: r.currentTabs, size: r.defaultTabSize };
      return r.currentTabs.filter(t => t.span.isConnected).length;
    }""")
    assert tabs == 67
    cdp = page.context.new_cdp_session(page)
    cdp.send("Performance.enable")
    before = layouts(cdp)
    page.evaluate("vwLineUpTabs()")
    # Three: one for the points to a pixel, then one for each of the two rounds. A frame drawn
    # before the count is read can add one. Tab by tab, it was 67.
    assert layouts(cdp) - before <= 6


UPSTREAM_TABS = """() => {
  document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1');
  const r = window.__vwRenderers[window.__vwRenderers.length - 1];
  const tabs = r.currentTabs.filter(t => t.span.isConnected);
  const look = () => tabs.map(t => [t.span.innerHTML, t.span.getAttribute('style')]);
  const drawn = String.fromCharCode(0x2003);
  const undo = () => tabs.forEach(t => { t.span.textContent = drawn; t.span.removeAttribute('style'); });
  undo();
  vwPages.tabs = { tabs: r.currentTabs, size: r.defaultTabSize };
  vwLineUpTabs();
  const ours = look();
  undo();
  let pass = null;
  const schedule = window.setTimeout;
  window.setTimeout = f => { pass = f; return 0; };
  try { Object.getPrototypeOf(r).refreshTabStops.call(r); } finally { window.setTimeout = schedule; }
  pass();
  return { count: tabs.length, ours: ours, theirs: look() };
}"""


def test_tabs_line_up_as_docx_previews_own_pass_lines_them_up(viewer, page):
    """
    vwTabPlan and vwSetTab are docx-preview's updateTabStop, copied so that every paragraph's
    tab can be read before any of them moves (docs/VENDORED.md). After an upgrade, this says
    whether upstream's own pass still lines a file's tabs up as the copy does. The copy parts
    from it only for the tab that ends the contents line, the last of the file's 67.
    """
    page.add_init_script(KEEP_RENDERERS)
    open_word(viewer, page, FIT)
    seen = page.evaluate(UPSTREAM_TABS)
    assert seen["count"] == 67
    assert seen["ours"][:66] == seen["theirs"][:66]
    assert seen["ours"][66] != seen["theirs"][66]
    assert sum(1 for _, style in seen["ours"] if "word-spacing" in (style or "")) == 67


def test_a_tab_that_ends_its_line_reaches_its_stop(viewer, page):
    """
    A contents line that ends on a right-aligned dotted tab, with no page number after it.
    docx-preview read the nothing after the tab as a box at the page's left edge, and drew the
    dots past the line, on a line of their own. In points, they end at the 6in stop, short by as
    much as the right-aligned stops above: docx-preview draws a tab as an em space and lines it
    up as a no-break space, and a right-aligned tab is placed as though its width stayed.
    """
    open_word(viewer, page, FIT)
    seen = page.evaluate("""() => {
      document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1');
      const p = [...document.querySelectorAll('.docx-wrapper p')].find(p => p.textContent.startsWith('Contents line'));
      const box = p.getBoundingClientRect(), style = getComputedStyle(p);
      const left = box.left + parseFloat(style.paddingLeft) + parseFloat(style.borderLeftWidth);
      const tab = p.querySelector('.docx-tab-stop').getBoundingClientRect();
      const range = document.createRange();
      range.selectNodeContents(p.firstChild);
      return { end: (tab.right - left) * 0.75, rise: tab.top - range.getBoundingClientRect().top,
               dotted: getComputedStyle(p.querySelector('.docx-tab-stop')).textDecorationStyle };
    }""")
    assert abs(seen["end"] - 432) <= 13, seen
    assert abs(seen["rise"]) < 2 and seen["dotted"] == "dotted", seen


# A tab whose stops cannot be read, put first among the tabs to line up
BROKEN_TAB = """
document.addEventListener('DOMContentLoaded', function () {
  var line = vwLineUpTabs;
  window.vwLineUpTabs = function () {
    var held = vwPages.tabs;
    if (held && held.tabs.length) held.tabs.unshift({ span: held.tabs[0].span, stops: { length: 1 } });
    return line();
  };
});
"""


def test_a_tab_that_cannot_be_lined_up_leaves_the_document_open(viewer, page):
    """
    docx-preview's pass ran on a timer after the document was up, so when it threw, as it did
    on a tab outside any paragraph, the tabs after it stayed as drawn. Run before the page
    shows, a throw would have failed the whole file.
    """
    page.add_init_script(BROKEN_TAB)
    seen = pages(open_word(viewer, page, FIT))
    assert len(seen) > 3


# ---------------------------------------------------------------------------
# Tabs beside a floating picture; make_fixtures.py says what the file holds
# ---------------------------------------------------------------------------

FLOAT = "word-tabs-beside-float.docx"

# Where each "Label N" tab ends, in points from its paragraph's left edge, at the page's own size
LABEL_TABS = """() => {
  document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1');
  return [...document.querySelectorAll('.docx-wrapper p')].filter(p => /^Label \\d/.test(p.textContent)).map(p => {
    const box = p.getBoundingClientRect(), style = getComputedStyle(p);
    const left = box.left + parseFloat(style.paddingLeft) + parseFloat(style.borderLeftWidth);
    return (p.querySelector('.docx-tab-stop').getBoundingClientRect().right - left) * 0.75;
  });
}"""

# How long the paragraph beside the picture is, in repetitions of its two words; which labels
# move from beside the picture to below it as it grows depends on the fonts and on this
BESIDE_LENGTHS = [6, 8, 10, 12, 16]


def beside_float(made, fixture_path, repetitions):
    """FLOAT with the paragraph beside the picture saying "gauge reading" [repetitions] times."""
    words = " ".join(["gauge reading"] * 10).encode()
    if repetitions == 10:
        return FLOAT
    return remade(made, fixture_path, FLOAT, {
        "word/document.xml": swap(words, " ".join(["gauge reading"] * repetitions).encode()),
    })


@pytest.mark.parametrize("repetitions", BESIDE_LENGTHS)
def test_a_tab_beside_a_floating_picture_reaches_its_stop(viewer, page, made, fixture_path, repetitions):
    """
    A line beside a float starts after it, so a tab there sits further from its paragraph's
    left edge. Lined up in rounds, the first label's tab was read beside the picture, then the
    paragraph above it grew by a line as its own tab was set, and the label dropped below the
    picture with the plan made beside it, so its tab ended at 110pt where its stop is at 216pt.
    In points, each label's tab ends at its stop, plus the no-break space it is drawn as.
    """
    open_word(viewer, page, beside_float(made, fixture_path, repetitions))
    ends = page.evaluate(LABEL_TABS)
    assert len(ends) == 8
    for end in ends:
        assert abs(end - 216) <= 6, ends


@pytest.mark.parametrize("repetitions", BESIDE_LENGTHS)
def test_tabs_beside_a_float_line_up_as_docx_previews_own_pass_lines_them_up(
    viewer, page, made, fixture_path, repetitions
):
    """
    docx-preview's own pass reads each tab after every tab before it is set, so it reads a
    label's tab wherever the paragraph above has left it. The copy has to put every one of the
    file's nine tabs where that pass puts it, however long the paragraph beside the picture is.
    """
    page.add_init_script(KEEP_RENDERERS)
    open_word(viewer, page, beside_float(made, fixture_path, repetitions))
    seen = page.evaluate(UPSTREAM_TABS)
    assert seen["count"] == 9
    assert seen["ours"] == seen["theirs"]
    assert sum(1 for _, style in seen["ours"] if "word-spacing" in (style or "")) == 9


NOTES_SEEN = """() => [...document.querySelectorAll('.docx-wrapper > section.docx')].map(s => ({
  lines: [...s.querySelectorAll(':scope > article')].map(a => a.innerText).join('\\n')
    .split('\\n').map(l => l.trim()).filter(Boolean),
  refs: [...s.querySelectorAll(':scope > article sup')].filter(e => !e.querySelector('sup'))
    .map(e => e.textContent.trim()),
  notes: [...s.querySelectorAll(':scope > ol > li')].map(li => [li.value, li.textContent.trim()])
}))"""


def test_footnotes_are_numbered_through_a_measured_document(viewer, page):
    """docx-preview numbered each page's notes from 1, as its own list."""
    open_word(viewer, page, NOTES)
    seen = page.evaluate(NOTES_SEEN)
    assert [r for p in seen for r in p["refs"]] == [str(n) for n in range(1, 21)]
    cited = [p for p in seen if p["refs"]]
    assert len(cited) > 2
    for p in cited:
        assert [str(n) for n, _ in p["notes"]] == p["refs"], cited
        assert all(text.startswith(f"Note {n}.") for n, text in p["notes"]), cited


def test_a_footnote_cited_in_a_repeated_header_row_keeps_its_number(viewer, page):
    """
    A table's header row is drawn again on each page it runs over, and a reference in it was
    counted again each time, so the notes after the table were numbered one higher a page.
    The note is listed once, under the first page that cites it.
    """
    open_word(viewer, page, TABLES)
    seen = page.evaluate(NOTES_SEEN)
    heads = [n for n, p in enumerate(seen) if any(line.startswith("Entry") for line in p["lines"])]
    assert len(heads) >= 3
    assert all(seen[n]["refs"][0] == "1" for n in heads), seen
    assert [r for p in seen for r in p["refs"]][-2:] == ["2", "3"]
    assert [[n for n, _ in p["notes"]] for p in seen if p["notes"]] == [[1], [2, 3]]
    assert seen[heads[0]]["notes"][0] == [1, "Readings are in metres."]


def test_header_rows_taller_than_a_page_are_not_drawn_again_on_every_page(viewer, page):
    """
    Header rows of 40 lines leave a page no room for a row under them, and the table was cut
    into a page for each of its 40 rows, each under the header rows drawn again.
    """
    open_word(viewer, page, TABLES)
    seen = page.evaluate(NOTES_SEEN)
    tall = [p for p in seen if "Head line 01" in p["lines"]]
    assert len(tall) == 1
    assert "Body 01" in tall[0]["lines"] and "Body 40" in tall[0]["lines"]


FOOT = """() => {
  document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1');
  return [...document.querySelectorAll('.docx-wrapper > section.docx')].map(s => ({
    past: s.getBoundingClientRect().height - parseFloat(getComputedStyle(s).minHeight),
    blocks: [...s.querySelectorAll(':scope > article > *')].map(b => ({
      text: b.textContent.trim(), tail: b.classList.contains('vw-tail'),
      height: b.getBoundingClientRect().height, after: parseFloat(getComputedStyle(b).marginBottom)
    }))
  }));
}"""


def test_blank_lines_take_no_room_at_the_foot_of_a_page_past_its_paper(viewer, page):
    """
    Forty blank lines end the page the sixty lines end on and run past its paper: they take
    no room, and nor does the space after the line above them, which is then the page's last.
    On a page with room for them, two blank lines stay as they are.
    """
    open_word(viewer, page, TAILS)
    seen = page.evaluate(FOOT)
    full = next(p for p in seen if any(b["text"] == "Fill 60" for b in p["blocks"]))
    at = [b["text"] for b in full["blocks"]].index("Fill 60")
    after = full["blocks"][at + 1:]
    assert len(after) == 40 and all(b["tail"] and b["height"] == 0 for b in after)
    assert full["blocks"][at - 1]["after"] > 0 and full["blocks"][at]["after"] == 0
    assert full["past"] <= 0.5
    short = next(p for p in seen if p["blocks"][0]["text"] == "Short page")
    assert len(short["blocks"]) == 3
    assert not any(b["tail"] for b in short["blocks"]) and all(b["height"] > 0 for b in short["blocks"])


KINDS = """() => {
  const ps = [...document.querySelectorAll('.docx-wrapper p')];
  const at = ps.findIndex(p => p.textContent.trim() === 'Signed for the client:');
  return ps.slice(at + 1, at + 11).map(p => vwBlank(p));
}"""


def test_a_line_is_blank_only_when_it_draws_nothing(viewer, page):
    """
    A line was taken for blank when it held no letters, so at a page's foot an underlined tab
    to sign on, underlined spaces, a dotted leader and an empty numbered item lost their line,
    leader or number. In order: a picture, a border, a ground, an empty paragraph, a bookmark
    alone, underlined spaces, an empty numbered item, an empty item that the browser numbers,
    a dotted leader and an underlined tab.
    """
    open_word(viewer, page, TAILS)
    assert page.evaluate(KINDS) == [False, False, False, True, True, False, False, False, False, False]


def test_a_line_that_shows_something_stays_at_the_foot_of_a_page(viewer, page):
    """The lines that only look blank end their page, above forty blank lines that go."""
    open_word(viewer, page, TAILS)
    seen = page.evaluate(FOOT)
    signed = next(p for p in seen if p["blocks"][0]["text"] == "Signed for the client:")
    blocks = signed["blocks"]
    assert len(blocks) == 51
    assert not any(b["tail"] for b in blocks[:11]) and all(b["height"] > 0 for b in blocks[6:11])
    assert all(b["tail"] and b["height"] == 0 for b in blocks[11:])
    assert signed["past"] <= 0.5


def test_a_page_has_the_room_its_own_header_and_margins_leave(viewer, page):
    """
    A later page's header, six lines where the first page's is one, and a section running on
    with wider margins each took room their pages were measured without. The page with the
    row taller than a page is the one allowed past its paper.
    """
    seen = fitted(viewer, page, SECTIONS)
    tall = next(n for n, p in enumerate(seen) if "Tall line 1" in p["lines"])
    assert any("Narrow 080" in p["lines"] for p in seen[:tall + 1])
    for n, p in enumerate(seen):
        if n != tall:
            assert p["past"] <= 0.5, (n + 1, p["past"], p["lines"][-1:])


def test_a_table_turns_again_after_a_row_taller_than_its_page(viewer, page):
    """The row's page could not end, and the table's 40 rows after it went on as one page."""
    seen = fitted(viewer, page, SECTIONS)
    tall = next(n for n, p in enumerate(seen) if "Tall line 1" in p["lines"])
    after = seen[tall + 1:]
    assert len(after) >= 2
    assert after[0]["lines"][:3] == ["Entry", "Reading", "Row 02"]
    assert after[-1]["lines"][-1] == "Last line"


# ---------------------------------------------------------------------------
# Headers and footers for odd and even pages
# ---------------------------------------------------------------------------

ODD_EVEN = "word-odd-even.docx"

HEADERS_FOOTERS = """() => [...document.querySelectorAll('.docx-wrapper > section.docx')].map(s =>
  [...s.querySelectorAll(':scope > header, :scope > footer')].map(e => e.innerText.trim()).join(' | '))"""


def test_a_file_set_for_odd_and_even_pages_gives_even_pages_their_own_header(viewer, page):
    open_word(viewer, page, ODD_EVEN)
    assert page.evaluate(HEADERS_FOOTERS) == [
        "Header | Footer", "Even header | Even footer", "Header | Footer", "Even header | Even footer",
    ]


def test_a_file_not_set_for_odd_and_even_pages_has_one_header_on_every_page(viewer, page, made, fixture_path):
    """
    The same file without the setting, its section still holding an even-page header and
    footer. Word draws those only in a file set for different odd and even pages.
    """
    target = remade(made, fixture_path, ODD_EVEN, {
        "word/settings.xml": swap(b"<w:evenAndOddHeaders/>", b""),
    })
    open_word(viewer, page, target)
    assert page.evaluate(HEADERS_FOOTERS) == ["Header | Footer"] * 4


# ---------------------------------------------------------------------------
# The page counter and Go to page, over the app's channel
# ---------------------------------------------------------------------------

def test_the_page_on_screen_is_told_to_the_app(viewer, page, port):
    open_word(viewer, page)
    p = port()
    p.wait_for(r"page 1 6")
    page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    p.wait_for(r"page 6 6")


def test_the_middle_of_the_screen_decides_the_page(viewer, page, port):
    open_word(viewer, page)
    p = port()
    p.wait_for(r"page 1 6")
    # Page 3's top a little above the middle of the screen
    page.evaluate("""() => {
      const third = document.querySelectorAll('.docx-wrapper > section.docx')[2];
      window.scrollBy(0, third.getBoundingClientRect().top - innerHeight / 2 + 40);
    }""")
    p.wait_for(r"page 3 6")


def test_go_to_page_puts_the_page_at_the_top(viewer, page, port):
    open_word(viewer, page)
    p = port()
    p.wait_for(r"page 1 6")
    p.go_to_page(4)
    p.wait_for(r"page 4 6")
    top = page.evaluate(
        "() => document.querySelectorAll('.docx-wrapper > section.docx')[3].getBoundingClientRect().top"
    )
    assert abs(top) <= 2


def test_go_to_page_past_the_end_is_ignored(viewer, page, port):
    open_word(viewer, page)
    p = port()
    p.wait_for(r"page 1 6")
    p.go_to_page(99)
    p.go_to_page("x")
    page.wait_for_timeout(300)
    assert page.evaluate("() => window.scrollY") == 0
    assert p.pages() == ["page 1 6"]
    # Nor does it throw, which the channel answers with a stray search count
    assert p.counts() == []


# The app's channel handed over once the first drawing is up, as it is when the page finishes
# loading then, and the missing pages drawn only after the page has heard it
CHANNEL_BETWEEN_DRAWINGS = """
document.addEventListener('DOMContentLoaded', () => {
  const find = window.vwFindMissingPages, ready = window.vwPortReady;
  let heard;
  const told = new Promise(r => { heard = r; });
  window.vwPortReady = function () { ready(); heard(); };
  window.vwFindMissingPages = function () {
    window.__vwFirstSheets = document.querySelectorAll('.docx-wrapper > section.docx').length;
    (%s)();
    return told.then(find);
  };
});
""" % _HANDSHAKE


def test_a_channel_that_comes_between_two_drawings_hears_the_last_ones_count(viewer, page):
    page.add_init_script(CHANNEL_BETWEEN_DRAWINGS)
    open_word(viewer, page, UNRECORDED)
    page.wait_for_function("() => window.__vwInbox.some(m => m.startsWith('page '))")
    page.wait_for_timeout(300)
    assert page.evaluate("window.__vwFirstSheets") == 2
    assert [m for m in page.evaluate("window.__vwInbox") if m.startswith("page ")] == ["page 1 4"]


def test_a_document_of_one_page_says_nothing_about_pages(viewer, page, port):
    open_word(viewer, page, "report.docx")
    p = port()
    page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    page.wait_for_timeout(400)
    assert p.pages() == []
