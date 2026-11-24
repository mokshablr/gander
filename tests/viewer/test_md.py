"""md.html: Markdown through marked, then DOMPurify, then into the DOM."""

import base64
from pathlib import Path

import pytest

from helpers import status_text


def wait_for_render(page, timeout=20000):
    page.wait_for_function(
        "() => document.querySelector('#content').children.length > 0", timeout=timeout
    )


def test_markdown_becomes_html(viewer, page):
    viewer("md.html", "notes.md")
    wait_for_render(page)
    assert page.query_selector("#content h1") is not None
    assert "Willowmere site notes" in page.text_content("#content h1")


def test_a_crafted_file_cannot_stall_the_page(viewer, page, made):
    """
    marked 15 backtracked over an open link followed by no-break spaces, taking minutes over
    16 KB, where marked 18.1 reads it at once.
    """
    viewer("md.html", made("stall.md", "[](" + "\u00a0" * 8000))
    wait_for_render(page, timeout=5000)


def test_lists_and_links_render(viewer, page):
    viewer("md.html", "notes.md")
    wait_for_render(page)
    assert len(page.query_selector_all("#content li")) >= 2
    assert page.query_selector("#content a") is not None


@pytest.mark.parametrize("heading", [
    "ملاحظات القراءة",
    # The first letter is Latin, but most of the prose under it is Arabic.
    "Reading notes",
])
def test_prose_sets_direction_without_turning_code(viewer, page, made, port, heading):
    direction = "rtl"
    text = f'''```js
const count = 1;
```

# {heading}

(2026) العربية من اليمين إلى اليسار.

- العنصر الأول
- العنصر الثاني

> اقتباس عربي.

نص مع `value += 1`.
'''
    viewer("md.html", made("direction.md", text))
    wait_for_render(page)
    assert page.locator("#content h1, #content p, #content li").evaluate_all(
        "(els, direction) => els.every(el => getComputedStyle(el).direction === direction)", direction
    )
    assert page.locator("#content pre, #content code").evaluate_all(
        "els => els.every(el => getComputedStyle(el).direction === 'ltr')"
    )
    assert page.locator("#content > p code").evaluate("el => getComputedStyle(el).unicodeBidi") == "isolate"
    assert page.text_content("#content pre code") == "const count = 1;\n"
    borders = page.locator("#content blockquote").evaluate(
        "el => [getComputedStyle(el).borderLeftWidth, getComputedStyle(el).borderRightWidth]"
    )
    assert borders == ["0px", "4px"]
    channel = port()
    channel.query("العربية")
    channel.wait_for_count(1, 1)


ARABIC_PROSE = "العربية من اليمين إلى اليسار، وهذا سطر آخر بالعربية.\n\nوهذه فقرة ثانية بالعربية أيضا.\n"


@pytest.mark.parametrize("opening", [
    "English | [العربية](README.ar.md) | [فارسی](README.fa.md)",
    "# NewPipe",
    "---\ntitle: Notes\nlayout: post\n---",
])
def test_arabic_prose_reads_right_to_left_below_an_opening_in_english(viewer, page, made, opening):
    """Real Arabic files often open with a language menu, the project's English name or front matter."""
    viewer("md.html", made("opening.md", f"{opening}\n\n{ARABIC_PROSE}"))
    wait_for_render(page)
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == "rtl"


def test_english_prose_reads_left_to_right_below_a_link_to_its_arabic_version(viewer, page, made):
    text = "[العربية](README.ar.md)\n\n# Quick start\n\nInstall the app, then open any file from the share sheet.\n"
    viewer("md.html", made("english.md", text))
    wait_for_render(page)
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == "ltr"


def test_a_part_the_author_gave_a_direction_does_not_decide_the_rest(viewer, page, made):
    text = f'''Install the app, then open any file from the share sheet.

<div dir="rtl">

{ARABIC_PROSE * 4}
</div>
'''
    viewer("md.html", made("authored-part.md", text))
    wait_for_render(page)
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == "ltr"
    assert page.locator("#content div").evaluate("el => getComputedStyle(el).direction") == "rtl"


def test_only_the_opening_of_a_long_document_is_counted(viewer, page, made):
    """Counting megabytes at once holds hundreds of megabytes of matches."""
    english = "Install the app, then open any file from the share sheet. " * 1500  # about 88K characters
    viewer("md.html", made("long.md", f"{english}\n\n{ARABIC_PROSE * 3000}"))
    wait_for_render(page)
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == "ltr"


@pytest.mark.parametrize("direction", ["rtl", "ltr", "auto"])
def test_code_reads_left_to_right_inside_an_authored_direction_unless_it_has_its_own(viewer, page, made, direction):
    """A wrapper's direction would put a leading `.` or `--` on the wrong end of the code in it."""
    text = f'''<div dir="{direction}">
<pre><code>مقطع عربي</code></pre>
<p><code>مقطع عربي</code></p>
</div>
<code dir="{direction}">مقطع عربي</code>
'''
    viewer("md.html", made("authored-dir.md", text))
    wait_for_render(page)
    assert page.get_attribute("#content div", "dir") == direction
    assert page.get_attribute("#content > code", "dir") == direction
    inside = page.locator("#content div pre, #content div code")
    assert inside.count() == 3
    assert inside.evaluate_all("els => els.every(el => getComputedStyle(el).direction === 'ltr')")
    assert page.locator("#content > code").evaluate("el => getComputedStyle(el).direction") == (
        "ltr" if direction == "ltr" else "rtl"
    )


# ---------------------------------------------------------------------------
# Pictures beside the file, which DocumentServer finds when the file was opened
# from a folder added in Gander (#62). The server stands in with the file's own
# folder.
# ---------------------------------------------------------------------------

@pytest.mark.refusals_expected
def test_pictures_beside_the_file_show_and_the_rest_are_left_as_they_were(
    viewer, page, server, tmp_path, fixture_path
):
    (tmp_path / "shots").mkdir()
    (tmp_path / "preview.png").write_bytes(fixture_path("tiny.png").read_bytes())
    (tmp_path / "shots" / "home 1.jpg").write_bytes(fixture_path("exif-1.jpg").read_bytes())
    embedded = "data:image/png;base64," + base64.b64encode(fixture_path("tiny.png").read_bytes()).decode()
    md = tmp_path / "README.md"
    md.write_text(
        "![beside](preview.png)\n\n"
        '<img alt="html" src="./shots/home%201.jpg">\n\n'
        "![web](https://example.com/web.png)\n\n"
        f"![embedded]({embedded})\n\n"
        "![missing](gone.png)\n",
        encoding="utf-8",
    )
    viewer("md.html", md)
    wait_for_render(page)
    page.wait_for_function("() => [...document.images].every(i => i.complete)")
    shown = page.evaluate("() => Object.fromEntries([...document.images].map(i => [i.alt, i.naturalWidth > 0]))")
    assert shown == {"beside": True, "html": True, "web": False, "embedded": True, "missing": False}
    # A picture from the web is still the page's policy to refuse, and never asked of the folder
    assert page.evaluate("window.__vwRefused") == ["img-src https://example.com/web.png"]
    asked = sorted(r["query"].get("path") for r in server.state.requests if r["path"] == "/beside")
    assert asked == ["./shots/home%201.jpg", "gone.png", "preview.png"]
    # Nor was a picture asked for where its path points from the viewer's own page first
    assert [r["path"] for r in server.state.requests if r["path"].endswith((".png", ".jpg"))] == []


def test_a_picture_for_reading_at_night_is_found_beside_the_file_too(viewer, page, tmp_path, fixture_path):
    """How a README on GitHub gives itself a dark logo: a source chosen by the theme."""
    page.emulate_media(color_scheme="dark")
    (tmp_path / "logo-light.png").write_bytes(fixture_path("tiny.png").read_bytes())
    (tmp_path / "logo-dark.jpg").write_bytes(fixture_path("exif-1.jpg").read_bytes())
    embedded = "data:image/png;base64," + base64.b64encode(fixture_path("tiny.png").read_bytes()).decode()
    md = tmp_path / "README.md"
    md.write_text(
        "<picture>\n"
        '  <source media="(prefers-color-scheme: dark)" srcset="logo-dark.jpg">\n'
        '  <img alt="logo" src="logo-light.png">\n'
        "</picture>\n\n"
        f'<img alt="sizes" src="logo-light.png" srcset="logo-light.png 1x, {embedded} 2x">\n',
        encoding="utf-8",
    )
    viewer("md.html", md)
    wait_for_render(page)
    page.wait_for_function("() => [...document.images].every(i => i.complete)")
    logo = page.evaluate("() => [document.images[0].currentSrc, document.images[0].naturalWidth]")
    assert logo[0].endswith("/beside?path=logo-dark.jpg")
    assert logo[1] > 0
    # Each address in a list is pointed beside the file, and one that is a URL, comma and all, is not
    assert page.get_attribute("img[alt=sizes]", "srcset") == f"/beside?path=logo-light.png 1x, {embedded} 2x"


@pytest.mark.parametrize("theme", ["light", "dark"])
def test_a_logo_github_shows_in_one_theme_shows_in_that_theme_only(viewer, page, tmp_path, fixture_path, theme):
    """A README's address ending #gh-dark-mode-only or #gh-light-mode-only, which GitHub reads."""
    page.emulate_media(color_scheme=theme)
    (tmp_path / "logo.png").write_bytes(fixture_path("tiny.png").read_bytes())
    md = tmp_path / "README.md"
    md.write_text(
        "![light](logo.png#gh-light-mode-only)\n![dark](logo.png#gh-dark-mode-only)\n![always](logo.png)\n",
        encoding="utf-8",
    )
    viewer("md.html", md)
    wait_for_render(page)
    page.wait_for_function("() => [...document.images].every(i => i.complete)")
    shown = page.evaluate(
        "() => Object.fromEntries([...document.images].map(i => [i.alt, getComputedStyle(i).display !== 'none']))"
    )
    assert shown == {"light": theme == "light", "dark": theme == "dark", "always": True}
    assert page.evaluate("[...document.images].every(i => i.naturalWidth > 0)")


def test_a_script_in_the_document_is_removed(viewer, page):
    """
    A Markdown file is untrusted input and marked will happily pass raw HTML
    through. DOMPurify is what stands between the two.
    """
    viewer("md.html", "notes.md")
    wait_for_render(page)
    page.wait_for_timeout(500)

    assert page.query_selector("#content script") is None
    assert page.evaluate("() => window.__xss") is None


def test_an_event_handler_attribute_is_removed(viewer, page):
    viewer("md.html", "notes.md")
    wait_for_render(page)
    page.wait_for_timeout(500)

    handlers = page.evaluate(
        "() => [...document.querySelectorAll('#content *')]"
        ".filter(e => e.getAttribute('onerror') || e.getAttribute('onload')).length"
    )
    assert handlers == 0
    assert page.evaluate("() => window.__xss") is None


def test_the_prose_after_the_injected_markup_survives(viewer, page):
    """Sanitising is not the same as discarding the document."""
    viewer("md.html", "notes.md")
    wait_for_render(page)
    assert "Text after the injected markup" in page.text_content("#content")


# ---------------------------------------------------------------------------
# The too-old-WebView card, which Kotlin decides and app.js words
# ---------------------------------------------------------------------------

def test_an_engine_too_old_for_marked_is_told_to_update_it(viewer, page, server):
    viewer("md.html", "notes.md", webview=64, needs=93)
    page.wait_for_selector(".vw-error", timeout=15000)
    page.wait_for_load_state("networkidle")
    said = status_text(page)
    assert "too old to show Markdown files" in said
    assert "64" in said and "93" in said
    assert "updating android system webview" in said.lower()
    # The card is the whole page: the document is never read
    assert server.full_requests() == []


def test_the_card_is_all_that_shows_when_marked_cannot_parse(viewer, page):
    """
    Below Chromium 84, as on issue #31's WebView 64, marked can't parse and
    the reader was told "marked is not defined". The browser here parses it, so the
    file is swapped for one that no engine can.
    """
    page.route(
        "**/assets/viewer/lib/marked.umd.js",
        lambda route: route.fulfill(content_type="text/javascript", body="var x = ;"),
    )
    with page.expect_event("pageerror"):
        viewer("md.html", "notes.md", webview=64, needs=93)
    page.wait_for_load_state("networkidle")
    said = status_text(page)
    assert "too old to show Markdown files" in said
    assert "not defined" not in said


def test_the_card_is_all_that_shows_where_marked_parses_but_cannot_run(viewer, page):
    """
    From Chromium 84 to 92 marked parses, and then fails on a document with a bracket
    in it, because its check for a reference link calls Object.hasOwn, which arrived
    in 93. So the floor is 93, not the 84 its syntax alone would suggest. Taking
    Object.hasOwn away here is that engine.
    """
    page.add_init_script("delete Object.hasOwn;")
    viewer("md.html", "notes.md", webview=92, needs=93)
    page.wait_for_selector(".vw-error", timeout=15000)
    page.wait_for_load_state("networkidle")
    said = status_text(page)
    assert "too old to show Markdown files" in said
    assert "is not a function" not in said


# ---------------------------------------------------------------
# Formulas, issue #83
# ---------------------------------------------------------------

def wait_until_drawn(page):
    page.wait_for_function("() => document.getElementById('vw-status').style.display === 'none'")


FORMULAS = r'''# Notes

$$p(x) = \frac{1}{\sqrt{2\pi}\,\sigma}\exp\!\left(-\frac{(x-\mu)^2}{2\sigma^2}\right)$$

$$
\mathbf{x} \sim \mathcal{N}(\boldsymbol{\mu}, \boldsymbol{\Sigma})
$$

\[
\boxed{P = \tfrac{3}{4}}
\]

Inline $E = mc^2$ and \(a^2 + b^2\) in a sentence, and $$\int_0^1 x\,dx$$ set apart.

```math
\sum_{i=1}^n i = \frac{n(n+1)}{2}
```
'''


def test_each_way_of_writing_a_formula_is_drawn(viewer, page, made):
    viewer("md.html", made("formulas.md", FORMULAS))
    wait_until_drawn(page)
    drawn = page.locator("#content .vw-math").evaluate_all(
        "els => els.map(el => [!!el.querySelector('.katex'), !!el.querySelector('.katex-display')])"
    )
    assert drawn == [
        [True, True], [True, True], [True, True],
        [True, False], [True, False], [True, True],
        [True, True],
    ]
    assert page.locator("#content .katex-error").count() == 0
    assert "$" not in page.inner_text("#content")
    assert page.inner_text("#content h1") == "Notes"


def test_prices_code_and_escaped_dollars_stay_text(viewer, page, made):
    text = (
        "It costs $5 and $10, or $5-$10 on sale, and \\$x\\$ is escaped.\n\n"
        "Set `$PATH` and `$x$` in code.\n\n"
        "```sh\necho $HOME $x$\n```\n"
    )
    viewer("md.html", made("prices.md", text))
    wait_until_drawn(page)
    assert page.locator("#content .vw-math").count() == 0
    said = page.inner_text("#content")
    assert "It costs $5 and $10, or $5-$10 on sale, and $x$ is escaped." in said
    assert page.text_content("#content pre code") == "echo $HOME $x$\n"


def test_a_file_without_formulas_never_loads_katex(viewer, page):
    viewer("md.html", "notes.md")
    wait_until_drawn(page)
    assert page.evaluate("typeof katex") == "undefined"
    assert page.locator("script[src*=katex], link[href*=katex]").count() == 0


@pytest.mark.parametrize("name", ["licences.md", "file_types.md"])
def test_the_pages_gander_ships_in_markdown_have_no_formulas(viewer, page, made, name):
    """The About dialog opens these in this viewer, so a $ in them must stay a $."""
    text = (Path(__file__).parents[2] / "app/src/main/assets" / name).read_text()
    viewer("md.html", made(name, text))
    wait_until_drawn(page)
    assert page.locator("#content .vw-math").count() == 0


def test_a_formula_katex_cannot_read_shows_its_tex_and_the_rest_still_draws(viewer, page, made):
    viewer("md.html", made("broken.md", "$$\\frac{1}{$$\n\nAfter it, $x^2$.\n"))
    wait_until_drawn(page)
    assert page.locator("#content .katex-error").count() == 1
    assert "\\frac{1}{" in page.inner_text("#content .katex-error")
    assert page.locator("#content p .katex").count() == 1


def test_the_page_still_shows_when_katex_cannot_load(viewer, page, made):
    page.route("**/lib/katex/katex.min.js", lambda route: route.abort())
    viewer("md.html", made("offline.md", "# Title\n\n$$x^2$$\n"))
    wait_until_drawn(page)
    assert page.inner_text("#content h1") == "Title"
    assert page.inner_text("#content .vw-math") == "x^2"


def test_a_file_cannot_draw_a_formula_thousands_of_screens_wide(viewer, page, made):
    viewer("md.html", made("wide.md", "$$\\rule{100000em}{1em}\\hspace{100000em}$$\n"))
    wait_until_drawn(page)
    width = page.locator("#content .katex").evaluate("el => el.scrollWidth")
    assert width < 2000


def test_formulas_read_left_to_right_in_a_right_to_left_document(viewer, page, made):
    viewer("md.html", made("rtl-math.md", ARABIC_PROSE + "\nالمعادلة $x + y = z$ بالعربية.\n"))
    wait_until_drawn(page)
    assert page.locator("#content").get_attribute("dir") == "rtl"
    assert page.locator("#content .vw-math").evaluate("el => getComputedStyle(el).direction") == "ltr"


def test_find_counts_a_word_in_a_formula_once(viewer, page, made, port):
    """KaTeX keeps a MathML copy of each formula for screen readers, which find leaves out."""
    viewer("md.html", made("find-math.md", "$$P(\\text{solved}) = 1$$\n\nIt was solved.\n"))
    wait_until_drawn(page)
    channel = port()
    channel.query("solved")
    channel.wait_for_count(1, 2)


@pytest.mark.parametrize("opener", ["\\[a\n", "\\(a "])
def test_formula_openers_that_never_close_cannot_stall_the_page(viewer, page, made, opener):
    """Looked for to the end of the file each time, these took time growing with its square."""
    viewer("md.html", made("unclosed.md", opener * 40000))
    page.wait_for_function(
        "() => document.getElementById('vw-status').style.display === 'none'", timeout=5000
    )
