"""
Night mode for .doc, .odt and .rtf, which prose.html draws, by night.js as Word's page has it.

letter.odt and memo.rtf colour their heading #1f3864, and letter.odt shades a table cell
#d9e2f3; each must come out where the matrix pdf.html uses puts it. test_docx_night.py says why
the colours are read off the elements.
"""

import pytest

from test_prose import wait_for_document

HEADING, HEADING_OVER = "rgb(31, 56, 100)", "rgb(178, 203, 247)"
SHADED, SHADED_OVER = "rgb(217, 226, 243)", "rgb(21, 30, 47)"
PAPER, PAPER_OVER = "rgb(255, 255, 255)", "rgb(0, 0, 0)"
INK, INK_OVER = "rgb(0, 0, 0)", "rgb(255, 255, 255)"

LOOK = """() => {
  const find = (sel, t) => [...document.querySelectorAll(sel)]
    .filter(e => e.textContent.trim().startsWith(t)).pop();
  const cell = [...document.querySelectorAll('.vw-paper td')]
    .find(td => getComputedStyle(td).backgroundColor !== 'rgba(0, 0, 0, 0)');
  return {
    heading: getComputedStyle(find('.vw-paper h1, .vw-paper h1 span, .vw-paper p, .vw-paper p span',
      'Field Survey')).color,
    ink: getComputedStyle(find('.vw-paper p', 'A short report')).color,
    paper: getComputedStyle(document.querySelector('.vw-paper > section')).backgroundColor,
    ground: getComputedStyle(document.body).backgroundColor,
    cell: cell ? getComputedStyle(cell).backgroundColor : null,
    shown: getComputedStyle(document.getElementById('container')).visibility
  };
}"""


def open_prose(viewer, page, fixture, night=True):
    viewer("prose.html", fixture, night="1" if night else "0")
    wait_for_document(page)
    page.wait_for_function(
        "() => getComputedStyle(document.getElementById('vw-status')).display === 'none'"
    )
    return page.evaluate(LOOK)


@pytest.mark.parametrize("fixture", ["letter.odt", "memo.rtf", "legacy.doc"])
def test_the_paper_turns_black_and_the_ink_white(viewer, page, fixture):
    seen = open_prose(viewer, page, fixture)
    assert (seen["paper"], seen["ink"], seen["ground"]) == (PAPER_OVER, INK_OVER, PAPER_OVER)
    assert seen["shown"] == "visible"


@pytest.mark.parametrize("fixture", ["letter.odt", "memo.rtf"])
def test_a_coloured_heading_keeps_its_hue(viewer, page, fixture):
    assert open_prose(viewer, page, fixture)["heading"] == HEADING_OVER


def test_shading_turns_over(viewer, page):
    assert open_prose(viewer, page, "letter.odt")["cell"] == SHADED_OVER


@pytest.mark.parametrize("fixture", ["letter.odt", "memo.rtf"])
def test_nothing_turns_over_unless_night_mode_is_asked_for(viewer, page, fixture):
    seen = open_prose(viewer, page, fixture, night=False)
    assert (seen["heading"], seen["ink"], seen["paper"]) == (HEADING, INK, PAPER)


def test_a_printout_keeps_the_documents_colours(viewer, page):
    open_prose(viewer, page, "letter.odt")
    page.emulate_media(media="print")
    seen = page.evaluate(LOOK)
    assert (seen["heading"], seen["ink"], seen["paper"], seen["cell"]) == (HEADING, INK, PAPER, SHADED)


def test_the_port_turns_it_on_and_off(viewer, page, port):
    open_prose(viewer, page, "letter.odt", night=False)
    p = port()
    p.night_mode(True)
    page.wait_for_function("() => document.documentElement.classList.contains('vw-night')")
    seen = page.evaluate(LOOK)
    assert (seen["heading"], seen["paper"], seen["cell"]) == (HEADING_OVER, PAPER_OVER, SHADED_OVER)

    p.night_mode(False)
    page.wait_for_function("() => !document.documentElement.classList.contains('vw-night')")
    seen = page.evaluate(LOOK)
    assert (seen["heading"], seen["paper"], seen["cell"]) == (HEADING, PAPER, SHADED)
