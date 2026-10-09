"""pptx.html: PPTXjs, which reports nothing and is polled instead."""

import io
import re
import zipfile

import pytest
from PIL import Image

from helpers import status_text, wait_until_done


def test_a_deck_renders_every_slide(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    assert len(page.query_selector_all("#result .slide")) >= 3


def test_the_spinner_goes_once_the_slides_are_up(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    page.wait_for_function(
        "() => { const e = document.getElementById('vw-status');"
        "return !e || getComputedStyle(e).display === 'none'; }",
        timeout=20000,
    )


def test_the_slide_titles_are_there(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    # PPTXjs lays every run out with non-breaking spaces between the words
    said = page.text_content("#result").replace("\u00a0", " ")
    assert "Willowmere Kickoff" in said
    assert "What we found" in said
    assert "What happens next" in said


# ---------------------------------------------------------------------------
# PowerPoint's relatives, which FileKind sends here by extension
# ---------------------------------------------------------------------------

SLIDE_RELATIVES = {
    "deck.ppsx": "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
    "deck.pptm": "application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml",
    "deck.potx": "application/vnd.openxmlformats-officedocument.presentationml.template.main+xml",
}


@pytest.mark.parametrize("fixture", sorted(SLIDE_RELATIVES))
def test_a_slide_show_a_macro_enabled_deck_and_a_template_render_as_a_pptx_does(
    viewer, page, main_part, fixture
):
    """
    Each is deck.pptx with its main part declared as its own format's. PPTXjs
    opens ppt/presentation.xml by name and finds the slides by their own type,
    which the four formats share, so what the main part was declared as never
    reaches it.
    """
    assert main_part(fixture) == [SLIDE_RELATIVES[fixture]]
    viewer("pptx.html", fixture)
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    wait_until_done(page)
    said = page.text_content("#result").replace(" ", " ")
    assert "Willowmere Kickoff" in said
    assert "What we found" in said
    assert "What happens next" in said


# ---------------------------------------------------------------------------
# Decks PPTXjs could not open: see the foot of pptx.js
# ---------------------------------------------------------------------------

def wait_for_deck(page, slides):
    page.wait_for_function(
        f"() => document.querySelectorAll('#result .slide').length >= {slides}"
        " || document.querySelector('.vw-error')",
        timeout=40000,
    )
    assert page.query_selector(".vw-error") is None, status_text(page)
    wait_until_done(page)


def drawn_paths(page, name):
    """The path data PPTXjs wrote for the shape called [name]."""
    return page.evaluate(
        "(n) => [...document.querySelectorAll('#result svg')]"
        ".filter(s => s.getAttribute('_name') === n)"
        ".flatMap(s => [...s.querySelectorAll('path')].map(p => p.getAttribute('d')))",
        name,
    )


def test_a_deck_with_no_app_properties_renders(viewer, page, fixture_path):
    """Google Slides writes no docProps/app.xml, and PPTXjs read it without looking."""
    with zipfile.ZipFile(fixture_path("deck-no-app-xml.pptx")) as z:
        assert "docProps/app.xml" not in z.namelist()
    viewer("pptx.html", "deck-no-app-xml.pptx")
    wait_for_deck(page, 3)
    said = page.text_content("#result").replace(" ", " ")
    assert "Willowmere Kickoff" in said
    assert "What happens next" in said


def test_a_shape_drawn_in_several_paths_draws_all_of_them(viewer, page):
    """A gate's outline and its two wires, which PPTXjs read as one path and threw on."""
    viewer("pptx.html", "freeforms.pptx")
    wait_for_deck(page, 1)
    paths = drawn_paths(page, "Gate")
    assert len(paths) == 1
    assert paths[0].count("M") == 3, paths[0]
    assert paths[0].count("L") == 6, paths[0]


def test_a_path_on_a_grid_of_its_own_is_drawn_to_its_shape(viewer, page):
    """
    The frame's grid is 1000 square and the diagonal's 500, so the diagonal's far
    end, at 500 on its own grid, is the frame's far corner.
    """
    viewer("pptx.html", "freeforms.pptx")
    wait_for_deck(page, 1)
    paths = drawn_paths(page, "Grid")
    corners = [tuple(round(float(v)) for v in pt.split(","))
               for pt in re.findall(r"[ML]\s*(-?[\d.]+,-?[\d.]+)", paths[0])]
    frame, diagonal_end = corners[2], corners[-1]
    assert diagonal_end == frame, paths[0]


def test_a_path_of_one_straight_segment_is_drawn(viewer, page):
    """A rule under a heading, a path PPTXjs drew as nothing, with no error to say so."""
    viewer("pptx.html", "freeforms.pptx")
    wait_for_deck(page, 1)
    paths = drawn_paths(page, "Rule")
    points = [tuple(round(float(v)) for v in pt.split(","))
              for pt in re.findall(r"[ML]\s*(-?[\d.]+,-?[\d.]+)", paths[0])]
    assert (0, 0) in points and (480, 0) in points, paths[0]


def painted(page, name, rgb):
    """How many pixels in and just around the shape called [name] are near [rgb]."""
    slide = page.query_selector("#result .slide")
    shot = Image.open(io.BytesIO(slide.screenshot())).convert("RGB")
    x, y, w, h = page.evaluate(
        "(n) => { const s = [...document.querySelectorAll('#result svg')]"
        ".find(e => e.getAttribute('_name') === n);"
        "const r = s.getBoundingClientRect(), o = s.closest('.slide').getBoundingClientRect();"
        "return [r.x - o.x, r.y - o.y, r.width, r.height]; }",
        name,
    )
    near = shot.crop((round(x) - 6, round(y) - 6, round(x + w) + 6, round(y + h) + 6))
    want = tuple(int(rgb[i:i + 2], 16) for i in (0, 2, 4))
    px = near.load()
    return sum(
        1 for i in range(near.width) for j in range(near.height)
        if all(abs(a - b) < 48 for a, b in zip(px[i, j], want))
    )


@pytest.mark.parametrize("name, rgb", [("Level", "C02020"), ("Upright", "2060C0"), ("Rule", "208040")])
def test_a_line_that_lies_flat_or_stands_upright_is_drawn(viewer, page, name, rgb):
    """Each is a shape of no height or no width, whose SVG was not drawn at all."""
    viewer("pptx.html", "lines.pptx")
    wait_for_deck(page, 1)
    assert painted(page, name, rgb) > 200


# ---------------------------------------------------------------------------
# Text: see spacesThatBreak in pptx.js
# ---------------------------------------------------------------------------

def text_lines(page, name):
    """The lines the text box called [name] is drawn on, as text, by where each character sits."""
    return page.evaluate(
        """(n) => {
             const lines = [];
             let top = null;
             const shape = document.querySelector(`#result div[_name="${n}"]`);
             for (const block of shape.querySelectorAll('.text-block')) {
               const walk = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
               for (let node; (node = walk.nextNode()); ) {
                 for (let i = 0; i < node.data.length; i++) {
                   const r = document.createRange();
                   r.setStart(node, i); r.setEnd(node, i + 1);
                   const box = r.getClientRects()[0];
                   if (!box) continue;
                   if (top === null || box.top - top > box.height / 2) { lines.push(''); top = box.top; }
                   lines[lines.length - 1] += node.data[i];
                 }
               }
             }
             return lines;
           }""",
        name,
    )


def test_a_line_too_long_for_its_box_breaks_between_words(viewer, page):
    """
    PPTXjs writes every space as a no-break space, so a line had nowhere to break and
    was cut wherever it ran out of room: "Language" on one line and "s" on the next.
    """
    viewer("pptx.html", "wrapping.pptx")
    wait_for_deck(page, 1)
    lines = text_lines(page, "Wrapped")
    assert len(lines) > 2, lines
    words = "Every word of this line stays whole when it wraps inside a narrow box".split()
    assert [w for line in lines for w in line.split()] == words, lines


def test_a_run_of_spaces_keeps_its_width(viewer, page):
    """The spaces PPTXjs wrote as no-break spaces kept their width, and ordinary ones must too."""
    viewer("pptx.html", "wrapping.pptx")
    wait_for_deck(page, 1)
    gap, space = page.evaluate(
        """() => {
             const block = document.querySelector('#result div[_name="Spaced"] .text-block');
             const node = document.createTreeWalker(block, NodeFilter.SHOW_TEXT).nextNode();
             const r = document.createRange();
             const at = (i) => { r.setStart(node, i); r.setEnd(node, i + 1); return r.getBoundingClientRect(); };
             const left = node.data.indexOf('left'), right = node.data.indexOf('right');
             return [at(right).left - at(left + 3).right, at(left + 4).width];
           }"""
    )
    assert space > 0
    assert gap > 6 * space, (gap, space)


# ---------------------------------------------------------------------------
# Weight: see regularWeight in pptx.js
# ---------------------------------------------------------------------------

def test_slide_text_that_is_not_bold_has_normal_weight(viewer, page):
    """
    PPTXjs gave every paragraph of a body or a shape font-weight 100, and its plain runs
    took it, which Android draws in Roboto Thin. A bold run must stay bold.
    """
    viewer("pptx.html", "weights.pptx")
    wait_for_deck(page, 1)
    weights = page.evaluate(
        """() => Object.fromEntries([...document.querySelectorAll('#result .text-block')]
             .map((run) => [run.textContent.trim(), getComputedStyle(run).fontWeight]))"""
    )
    assert weights["Plain body text"] == "400", weights
    assert weights["plain"] == "400", weights
    assert weights["bold"] == "700", weights


# ---------------------------------------------------------------------------
# Bold and italic: see styleTheDesignGives in pptx.js
# ---------------------------------------------------------------------------

def test_text_is_bold_or_italic_where_its_design_says(viewer, page):
    """
    PPTXjs read bold and italic only from the run itself, so a title its master or layout
    makes bold drew regular. What the run says still wins, and the nearer design over the
    farther one, at the paragraph's own level.
    """
    viewer("pptx.html", "inherited-bold.pptx")
    wait_for_deck(page, 2)
    styles = page.evaluate(
        """() => Object.fromEntries([...document.querySelectorAll('#result .text-block')]
             .map((run) => [run.textContent.trim(),
                            getComputedStyle(run).fontWeight + ' ' + getComputedStyle(run).fontStyle]))"""
    )
    assert styles["Bold from the master"] == "700 normal", styles
    assert styles["but not this"] == "400 normal", styles
    assert styles["Bold from the layout"] == "700 normal", styles
    assert styles["Plain at the second level"] == "400 normal", styles
    assert styles["Regular by its layout"] == "400 normal", styles
    assert styles["Italic from the layout"] == "400 italic", styles


# ---------------------------------------------------------------------------
# Line breaks: see breaksPptxjsKeeps in pptx.js
# ---------------------------------------------------------------------------

def test_every_line_break_in_a_paragraph_breaks_it(viewer, page):
    """PPTXjs dropped the first line break of a paragraph that had more than one."""
    viewer("pptx.html", "line-breaks.pptx")
    wait_for_deck(page, 1)
    assert text_lines(page, "Broken") == ["The first line", "the second", "and the third"]


# ---------------------------------------------------------------------------
# No wrap: see linesLeftWhole in pptx.js
# ---------------------------------------------------------------------------

def text_span(page, name):
    """The left and right of the text box called [name], and of the text drawn in it."""
    return page.evaluate(
        """(n) => {
             const shape = document.querySelector(`#result div[_name="${n}"]`);
             const box = shape.getBoundingClientRect();
             const runs = [...shape.querySelectorAll('.text-block')].flatMap((b) => [...b.getClientRects()]);
             return [[box.left, box.right],
                     [Math.min(...runs.map((r) => r.left)), Math.max(...runs.map((r) => r.right))]];
           }""",
        name,
    )


def test_a_box_set_not_to_wrap_keeps_its_lines_whole(viewer, page):
    """
    PPTXjs never read wrap="none" and wrapped such a box at its width like any other. Its
    line runs on past the box instead: to the right of text aligned left, to the left of
    text aligned right, and both ways from centred text. A title takes the setting from its
    layout, and a box the layout draws behind the slide says for itself.
    """
    viewer("pptx.html", "unwrapped.pptx")
    wait_for_deck(page, 1)
    for name in ("Left", "Centred", "Right", "Inheriting", "Behind"):
        assert len(text_lines(page, name)) == 1, (name, text_lines(page, name))
    assert len(text_lines(page, "Wrapping")) > 1

    (box, text) = text_span(page, "Left")
    assert text[0] >= box[0] - 1 and text[1] > box[1] + 20, (box, text)
    (box, text) = text_span(page, "Right")
    assert text[1] <= box[1] + 1 and text[0] < box[0] - 20, (box, text)
    (box, text) = text_span(page, "Centred")
    assert text[0] < box[0] - 20 and text[1] > box[1] + 20, (box, text)
    assert abs((text[0] + text[1]) - (box[0] + box[1])) / 2 < 2, (box, text)


# ---------------------------------------------------------------------------
# Symbol bullets: see bulletsAPhoneDraws in pptx.js, and symbol-fonts.js
# ---------------------------------------------------------------------------

def bullets(page, name):
    """Each paragraph of the shape called [name], as its bullet and its text."""
    return page.evaluate(
        """(n) => [...document.querySelectorAll(`#result div.block[_name="${n}"] .slide-prgrph`)]
             .map(p => { const b = p.firstElementChild.textContent; return [b, p.textContent.slice(b.length)]; })""",
        name,
    )


def test_a_symbol_font_bullet_is_drawn_as_a_character_a_phone_has(viewer, page):
    """
    No phone has Wingdings, Symbol or their kin. PPTXjs drew the Circuit design's arrowhead
    from Wingdings 3 (#48) and PowerPoint's own arrow as characters Android has no glyph for,
    a bullet given in the private use area as an empty box, whatever its font, and a
    Webdings one as the digit it is stored as.
    """
    viewer("pptx.html", "symbol-bullets.pptx")
    wait_for_deck(page, 2)
    assert bullets(page, "Bullets") == [
        ["●", "A circle from Wingdings 2"],
        ["▶", "An arrowhead from Wingdings 3"],
        ["➢", "The arrow PowerPoint offers"],
        ["●", "A round bullet from Wingdings"],
        ["•", "A bullet from Symbol"],
        ["▶", "A triangle from Webdings"],
        ["•", "A printer from Wingdings 2"],
        ["•", "A private use bullet from StarSymbol"],
    ]


def test_a_bullet_the_design_sets_in_wingdings_is_not_drawn_as_a_letter(viewer, page):
    """PPTXjs looked for a bullet's font only in the paragraph, so the master's round one came out an l."""
    viewer("pptx.html", "symbol-bullets.pptx")
    wait_for_deck(page, 2)
    assert bullets(page, "Designed") == [["●", "A round bullet from the master"]]


def test_a_bullet_in_smartart_is_drawn_as_a_character_a_phone_has(viewer, page):
    """SmartArt is drawn from a part of its own, where bullets were not put right, so its round one came out an l."""
    viewer("pptx.html", "symbol-bullets.pptx")
    wait_for_deck(page, 3)
    assert bullets(page, "Round in SmartArt") == [["●", "A round bullet in SmartArt"]]
    assert bullets(page, "Arrowhead in SmartArt") == [["▶", "An arrowhead in SmartArt"]]


def test_a_bullet_in_a_part_domparser_cannot_read_is_still_one_a_phone_draws(viewer, page, made, fixture_path):
    """
    pptx.js puts bullets right only in a part DOMParser can read, and PPTXjs reads more than
    that, such as an attribute whose prefix nothing declares. There PPTXjs looks a Wingdings 2
    or 3 bullet up in a table of its own, and without one it threw and the shape was left out.
    pptx.js gives it that table, made of characters a phone has.
    """
    with zipfile.ZipFile(fixture_path("symbol-bullets.pptx")) as z:
        parts = {name: z.read(name) for name in z.namelist()}
    slide = parts["ppt/slides/slide1.xml"].decode()
    unread = slide.replace("<p:sld ", '<p:sld vw:unread="1" ', 1)
    assert unread != slide
    assert page.evaluate(
        "x => new DOMParser().parseFromString(x, 'application/xml').getElementsByTagName('parsererror').length",
        unread,
    )
    parts["ppt/slides/slide1.xml"] = unread.encode()
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in parts.items():
            z.writestr(name, data)
    viewer("pptx.html", made("unread.pptx", out.getvalue()))
    wait_for_deck(page, 3)
    assert bullets(page, "Bullets")[:2] == [
        ["●", "A circle from Wingdings 2"],
        ["▶", "An arrowhead from Wingdings 3"],
    ]


# What a bullet in Wingdings 2, Wingdings 3 or Webdings can become, every one found in a plain
# font of both Android 9 and Android 16, from their font files on 9 Oct 2026. A character
# joins this list only once a phone's fonts have it too.
ANDROID_DRAWS = (
    "&+•※‽⁂←↑→↓↔↕↖↗↘↙↨↯↰↱↲↳↵↶↹↺↻⇆⇇⇈⇉⇊"
    "⇞⇟⇠⇡⇢⇣⇤⇥⇦⇧⇨⇪⇱⇲⇵⊖⊗⊘⊙⋅⌃⌤⌥⍽⎋⏐⏭⏮␣①②③"
    "④⑤⑥⑦⑧⑨⑩Ⓟ⓪⓿■□▣▪▲△▴▵▶▷▸▹▼▽▾▿◀◁◂◃◆◇"
    "◈◊○●◒◓◖◗◢◣◤◥★☉☑☒☜☞☟☽☾⛷✂✄✎✓✔✕✖✗✚✝"
    "✦✯✱✳✶✷✹❖❧❶❷❸❹❺❻❼❽❾❿➡➤⤒⤓⤴⤵⤶⤷⦁⦸⦿⬅⬆"
    "⬇⬈⬉⬊⬋⬎⬏⬐⬑⬟⬢⬣⬤⬥⬧⬩⬪⭘⸿"
)


def test_every_wingdings_2_3_and_webdings_bullet_is_one_android_draws(viewer, page):
    viewer("pptx.html", "symbol-bullets.pptx")
    wait_for_deck(page, 2)
    drawn = page.evaluate(
        """() => { const out = new Set();
             for (const face of ["Wingdings 2", "Wingdings 3", "Webdings"])
               for (let code = 0x21; code <= 0xFF; code++) {
                 out.add(vwSymbolBullet(face, String.fromCharCode(code)));
                 out.add(vwSymbolBullet(face, String.fromCharCode(0xF000 + code)));
               }
             return [...out].join(""); }"""
    )
    assert len(drawn) > 150, drawn
    assert set(drawn) - set(ANDROID_DRAWS) == set()


# ---------------------------------------------------------------------------
# Placeholders: see placeTheDesignGives in pptx.js
# ---------------------------------------------------------------------------

def drawn_box(page, name):
    """Where the shape called [name] is drawn on its slide: left, top, width and height."""
    return page.evaluate(
        """(n) => { const s = document.querySelector(`#result div.block[_name="${n}"]`);
             const slide = s.closest('.slide'), r = s.getBoundingClientRect(), o = slide.getBoundingClientRect();
             return [r.x - o.x - slide.clientLeft, r.y - o.y - slide.clientTop, r.width, r.height]; }""",
        name,
    )


def test_a_placeholder_with_an_outline_but_no_place_sits_where_its_design_puts_it(viewer, page):
    """PPTXjs read the place of a shape with an outline from the shape alone, and threw (#48)."""
    viewer("pptx.html", "placed-by-design.pptx")
    wait_for_deck(page, 1)
    px = 96 / 914400
    masters_title = (457200, 274638, 8229600, 1143000)
    layouts_body = (4572000, 2286000, 3657600, 2743200)
    assert drawn_box(page, "From the master") == pytest.approx([v * px for v in masters_title], abs=1)
    assert drawn_box(page, "From the layout") == pytest.approx([v * px for v in layouts_body], abs=1)
    said = page.text_content("#result").replace(" ", " ")
    assert "Placed by the master" in said and "Placed by the layout" in said


# ---------------------------------------------------------------------------
# Size: see sizeWhereNoneIsGiven in pptx.js
# ---------------------------------------------------------------------------

def test_text_whose_size_nothing_gives_is_drawn_at_18_point(viewer, page):
    """
    PowerPoint's own size. PPTXjs left such text the size of its paragraph: 0 in a placeholder
    or shape, which drew nothing (#48), and the browser's 16 px in this text box.
    """
    viewer("pptx.html", "unsized.pptx")
    wait_for_deck(page, 1)
    sizes = page.evaluate(
        """() => Object.fromEntries([...document.querySelectorAll('#result .text-block')]
             .map((run) => [run.textContent.trim(), getComputedStyle(run).fontSize]))"""
    )
    assert sizes["Eighteen point"] != "0px", sizes
    assert sizes["No size anywhere"] == sizes["Eighteen point"], sizes


# ---------------------------------------------------------------------------
# What PPTXjs cannot draw: see the lines marked Gander in lib/pptx/pptxjs.js
# ---------------------------------------------------------------------------

def test_what_pptxjs_cannot_draw_is_left_out_and_every_slide_drawn(viewer, page):
    """
    A chart whose part is missing ("reading 'c:chartSpace'") and a picture with no image on a
    layout each put the error card up in place of every slide (#48). Only they are left out:
    the chart's slide keeps its other shapes, and the layout its text. Each is marked where
    it would be, as is a shaded box with text, which PPTXjs also throws on. One with no text
    is left out unmarked, as is the layout's empty placeholder, which no slide shows.
    """
    viewer("pptx.html", "unreadable.pptx")
    wait_for_deck(page, 3)
    assert len(page.query_selector_all("#result .slide")) == 3
    assert len(page.query_selector_all("#all_slides_warpper")) == 1
    said = page.text_content("#result").replace(" ", " ")
    for text in ("A chart Gander cannot read", "Beside the chart", "The slide after it",
                 "On a layout with a broken picture", "Drawn by the layout"):
        assert text in said, text
    assert page.query_selector("#result [id^='chart']") is None
    assert missing(page) == [
        (1, "A chart that cannot be shown here", [96, 192, 384, 288]),
        (2, "A shape that cannot be shown here", [480, 192, 288, 96]),
        (3, "A picture that cannot be shown here", [48, 48, 96, 96]),
    ]


# nv.d3's bar chart throws as PPTXjs draws it, once its svg is in, the way an odd chart could
# inside nv.d3. nv.d3 sets window.nv before it fills nv.models, so the models are wrapped as
# they are read, and the chart keeps its axes, which PPTXjs sets before it draws.
CHART_THAT_THROWS = """
(() => {
  let nv;
  Object.defineProperty(window, "nv", {
    configurable: true,
    get: () => nv,
    set: (real) => {
      nv = new Proxy(real, { get: (target, key) => key !== "models" ? target[key]
        : new Proxy(target.models, { get: (models, name) => name !== "multiBarChart" ? models[name]
          : () => Object.assign(() => { throw new Error("a chart nv.d3 cannot draw"); }, models[name]()) }) });
    },
  });
})();
"""


def missing(page):
    """Each box pptx.js put where something is missing: its slide, its sentence, and its place there."""
    return [tuple(box) for box in page.evaluate("""
        [...document.querySelectorAll('#result .vw-missing')].map(box => {
          const slide = box.closest('.slide'), at = box.getBoundingClientRect(), on = slide.getBoundingClientRect();
          return [[...document.querySelectorAll('#result .slide')].indexOf(slide) + 1, box.textContent,
                  [at.left - on.left - slide.clientLeft, at.top - on.top - slide.clientTop, at.width, at.height]
                    .map(Math.round)];
        })""")]


def test_a_chart_that_throws_as_it_is_drawn_is_left_out_and_the_deck_drawn(viewer, page):
    """
    PPTXjs draws its charts with nv.d3 once every slide is built, and a throw there put the
    error card up in place of every slide (#48). The chart is left out and the rest drawn.
    """
    page.add_init_script(CHART_THAT_THROWS)
    viewer("pptx.html", "charted.pptx")
    wait_for_deck(page, 2)
    said = page.text_content("#result")
    for text in ("A chart nv.d3 draws", "Beside the chart", "The slide after it"):
        assert text in said, text
    # What nv.d3 drew before it threw goes, and the chart is marked where it would be
    assert page.query_selector("#result [id^='chart'] svg") is None
    assert missing(page) == [(1, "A chart that cannot be shown here", [96, 192, 384, 288])]


def test_a_picture_or_chart_that_never_draws_is_marked_where_it_would_be(viewer, page):
    """
    A Windows metafile is a picture no browser draws, a PNG cut short draws nothing either, and
    PPTXjs draws no doughnut chart. None of them throws, and each was left blank. Each is
    marked where it would be now, and the whole PNG beside them is drawn.
    """
    viewer("pptx.html", "undrawn.pptx")
    wait_for_deck(page, 2)
    page.wait_for_function("() => document.querySelectorAll('#result .vw-missing').length >= 3",
                           timeout=10000)
    assert missing(page) == [
        (1, "A picture in a format that cannot be shown here", [96, 192, 288, 192]),
        (1, "A picture that cannot be shown here", [528, 432, 288, 192]),
        (2, "A chart that cannot be shown here", [96, 192, 384, 288]),
    ]
    assert page.evaluate("[...document.querySelectorAll('#result img')].map(i => i.naturalWidth)") == [60]
    assert "Beside the chart" in page.text_content("#result")


@pytest.mark.parametrize("name", ["deck.pptx", "charted.pptx"])
def test_a_deck_drawn_whole_has_no_box(viewer, page, name):
    """A box goes only where something is missing: none on a deck whose pictures and chart draw."""
    viewer("pptx.html", name)
    wait_for_deck(page, 2)
    page.wait_for_function("() => [...document.querySelectorAll('#result img')].every(i => i.complete)")
    if name == "charted.pptx":
        assert page.query_selector("#result [id^='chart'] svg") is not None
    assert missing(page) == []
