"""
Find in page for the viewers that search through find.js: Word, the three prose readers,
Markdown, text, spreadsheets and slides.

Over the message channel ViewerActivity really opens, in the words PortMessage.kt parses, as
test_pdf_search.py does for PDF. These pages were searched with Chromium's own find before,
which sees only what is in the DOM: a workbook draws one sheet at a time and a long text file
its first pages. What is checked here above all is that the search reaches the rest.
"""

import re

import pytest

from helpers import big_sheet, bring_row, highlight_count

VW_TEXT_PAGE = 5 * 1024 * 1024

# The element each page draws its document into, and a word the fixture has in it
VIEWERS = [
    ("docx.html", "report.docx", "#container", "Willowmere"),
    ("prose.html", "letter.odt", "#container", "Willowmere"),
    ("prose.html", "memo.rtf", "#container", "Willowmere"),
    ("prose.html", "legacy.doc", "#container", "Willowmere"),
    ("md.html", "notes.md", "#content", "Willowmere"),
    ("text.html", "plain.txt", "#content", "line"),
    ("xlsx.html", "budget.xlsx", "#sheet", "Surveying"),
    ("pptx.html", "deck.pptx", "#result", "Willowmere"),
]

DRAWN = (
    "(sel) => { const e = document.querySelector(sel);"
    " const card = document.getElementById('vw-status');"
    " return !!e && e.textContent.trim().length > 0 &&"
    " (!card || getComputedStyle(card).display === 'none'); }"
)


def opened(viewer, page, port, html, fixture, root):
    viewer(html, fixture)
    page.wait_for_function(DRAWN, arg=root, timeout=40000)
    return port()


def occurrences(page, root, word):
    """How often [word] is in the drawn document, reading it the way a person would."""
    text = page.evaluate("(sel) => document.querySelector(sel).textContent", root)
    text = re.sub(r"\s+", " ", text.replace("\u00a0", " ")).lower()
    return text.count(word.lower())


@pytest.mark.parametrize("html, fixture, root, word", VIEWERS)
def test_every_viewer_counts_what_it_finds(viewer, page, port, html, fixture, root, word):
    p = opened(viewer, page, port, html, fixture, root)
    expected = occurrences(page, root, word)
    assert expected > 0
    p.query(word)
    assert p.wait_for_count(1, expected, done=True)
    assert highlight_count(page, "vw-find-active") == 1
    assert highlight_count(page, "vw-find") == expected - 1


def test_matching_ignores_case(viewer, page, port):
    p = opened(viewer, page, port, "docx.html", "report.docx", "#container")
    p.query("WILLOWMERE")
    assert p.wait_for_count(1, occurrences(page, "#container", "willowmere"), done=True)


def test_next_and_previous_walk_the_matches_and_wrap(viewer, page, port):
    """plain.txt has "line" twice: in "line" and in "newline"."""
    p = opened(viewer, page, port, "text.html", "plain.txt", "#content")
    p.query("line")
    p.wait_for_count(1, 2)
    p.next()
    assert p.wait_for_count(2, 2)
    p.clear_inbox()
    p.next()
    assert p.wait_for_count(1, 2)
    p.clear_inbox()
    p.prev()
    assert p.wait_for_count(2, 2)


def test_a_query_that_matches_nothing_reports_nothing(viewer, page, port):
    p = opened(viewer, page, port, "md.html", "notes.md", "#content")
    p.query("wombat")
    assert p.wait_for_count(0, 0, done=True)
    assert highlight_count(page, "vw-find") == 0


def test_clearing_the_search_takes_the_highlights_with_it(viewer, page, port):
    p = opened(viewer, page, port, "md.html", "notes.md", "#content")
    p.query("willowmere")
    p.wait_for_count(1, occurrences(page, "#content", "willowmere"))
    p.clear_inbox()
    p.clear()
    assert p.wait_for_count(0, 0)
    assert highlight_count(page, "vw-find-active") == 0


def test_words_a_slide_spaces_with_non_breaking_spaces_are_found_as_words(viewer, page, port):
    """PPTXjs lays every run out with a non-breaking space between the words."""
    p = opened(viewer, page, port, "pptx.html", "deck.pptx", "#result")
    p.query("Willowmere Kickoff")
    assert re.fullmatch(r"1 [1-9]\d* 1", p.wait_for(r"1 \d+ 1"))


def test_a_match_does_not_run_from_one_paragraph_into_the_next(viewer, page, port):
    """The Word fixture's heading ends "Willowmere" and its next paragraph begins "A short"."""
    p = opened(viewer, page, port, "docx.html", "report.docx", "#container")
    p.query("Willowmere A short")
    assert p.wait_for_count(0, 0, done=True)


# ---------------------------------------------------------------------------
# What Chromium's own find never saw
# ---------------------------------------------------------------------------

def test_a_match_on_a_sheet_that_is_not_drawn_is_found_and_shown(viewer, page, port):
    """
    budget.xlsx has "Second sheet marker" on its second sheet and "Third sheet marker" on its
    third, and neither on the first, which is the one drawn.
    """
    p = opened(viewer, page, port, "xlsx.html", "budget.xlsx", "#sheet")
    assert "sheet marker" not in page.text_content("#sheet").lower()
    p.query("sheet marker")
    assert p.wait_for_count(1, 2, done=True)
    page.wait_for_function(
        "() => document.querySelector('#sheet').textContent.indexOf('Second sheet marker') >= 0",
        timeout=10000,
    )
    assert highlight_count(page, "vw-find-active") == 1
    assert page.evaluate("() => document.querySelector('#tabs button.active').textContent") == "Detail"
    p.next()
    assert p.wait_for_count(2, 2)
    page.wait_for_function(
        "() => document.querySelector('#sheet').textContent.indexOf('Third sheet marker') >= 0",
        timeout=10000,
    )


def test_a_sheet_chosen_by_hand_keeps_the_count_and_draws_its_matches(viewer, page, port):
    p = opened(viewer, page, port, "xlsx.html", "budget.xlsx", "#sheet")
    p.query("sheet marker")
    p.wait_for_count(1, 2)
    page.query_selector_all("#tabs button")[2].click()
    page.wait_for_function(
        "() => document.querySelector('#sheet').textContent.indexOf('Third sheet marker') >= 0",
        timeout=10000,
    )
    page.wait_for_timeout(600)
    assert p.last_count() == "1 2 1"
    assert highlight_count(page, "vw-find") + highlight_count(page, "vw-find-active") == 1


def test_a_match_in_rows_a_large_sheet_has_not_drawn_yet_is_counted_and_shown(
        viewer, page, port, made):
    """A large sheet draws its rows as the reader nears them. Row 19,990 is far from the top."""
    sheet = big_sheet(made, 20000, {19990: "the needle"})
    p = opened(viewer, page, port, "xlsx.html", sheet, "#sheet")
    assert "needle" not in page.text_content("#sheet")
    p.query("needle")
    assert p.wait_for_count(1, 1, done=True)
    page.wait_for_function(
        "() => document.querySelector('#sheet').textContent.indexOf('the needle') >= 0",
        timeout=10000,
    )
    assert highlight_count(page, "vw-find-active") == 1
    assert page.evaluate("""() => {
      const td = [...document.querySelectorAll('#sheet td')].find(t => t.textContent === 'the needle');
      const box = td.getBoundingClientRect();
      return box.top >= 0 && box.bottom <= innerHeight;
    }""")


def test_matches_in_every_piece_of_a_large_sheet_on_the_page_are_highlighted(
        viewer, page, port, made):
    """Rows 10 and 700 are in different pieces, both of them on the page."""
    sheet = big_sheet(made, 20000, {10: "a marker", 700: "a marker"})
    p = opened(viewer, page, port, "xlsx.html", sheet, "#sheet")
    bring_row(page, 700, 20000)
    p.query("marker")
    assert p.wait_for_count(1, 2, done=True)
    page.wait_for_timeout(300)
    assert highlight_count(page, "vw-find-active") == 1
    assert highlight_count(page, "vw-find") == 1


def test_the_rest_of_a_long_text_file_is_searched_once_it_is_shown(viewer, page, port, made):
    """
    Past a page, a text file waits for Show more, and so does anything in it. Asked for, the
    rest is searched along with it, and the count grows without the query being typed again.
    """
    line = "A line of an ordinary log file, nothing to see here.\n"
    head = line * (VW_TEXT_PAGE // len(line) + 10)
    big = made("long.log", head + "the needle is here\n" + line * 100)
    viewer("text.html", big)
    page.wait_for_function(DRAWN, arg="#content", timeout=40000)
    p = port()
    p.query("needle")
    assert p.wait_for_count(0, 0, done=True)
    p.clear_inbox()
    page.click("#moreBtn")
    assert p.wait_for_count(1, 1, done=True, timeout=30)


def test_a_query_asked_before_the_document_arrives_is_answered_once_it_has(viewer, page, port):
    held = []
    page.route("**/doc/**", lambda route: held.append(route))
    viewer("md.html", "notes.md")
    page.wait_for_function("() => typeof vwFind === 'object'", timeout=15000)
    p = port()
    p.query("willowmere")
    # Nothing drawn yet: a count of nothing, and not a settled one
    assert p.wait_for_count(0, 0, done=False)
    for _ in range(200):
        if held:
            break
        page.wait_for_timeout(50)
    for route in held:
        route.continue_()
    page.wait_for_function(DRAWN, arg="#content", timeout=40000)
    assert p.wait_for_count(1, occurrences(page, "#content", "willowmere"), done=True)

