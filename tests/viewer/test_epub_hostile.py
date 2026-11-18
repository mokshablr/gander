"""
epub.html against a book written to do harm.

A book is the one kind of file whose own markup and stylesheets Gander draws nearly as they
are written, so each test here is a book that tries something: to run script, to ask a host
for something, to take the reader off the page, to draw over Gander's own parts, or to keep
the page busy until the reader gives up. conftest fails any test where the page's policy
had to refuse something, so each also checks that the reader itself never tried.
"""

from pathlib import Path

import pytest

from helpers import status_text
from test_epub import go_to, little_book, open_book, style_of, wait_for_book, wait_for_heading

XHTML = 'xmlns="http://www.w3.org/1999/xhtml"'
SVG = 'xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"'


def in_book(page, script, arg=None):
    """Runs [script] with the chapter's shadow tree as `root`."""
    return page.evaluate(
        "(arg) => { const root = document.querySelector('#book').shadowRoot; " + script + " }", arg
    )


def tap_all(page, selector):
    """
    A real tap on everything in the chapter that [selector] finds and that has a box, and how
    many taps were made: a tap can take away the next thing to tap.
    """
    taps = 0
    spots = in_book(
        page,
        """return [...root.querySelectorAll(arg)].map(el => {
             el.scrollIntoView({ block: 'center' });
             const r = el.getBoundingClientRect();
             return r.width && r.height ? [r.left + r.width / 2, r.top + r.height / 2] : null;
           }).filter(Boolean).length;""",
        selector,
    )
    for n in range(spots):
        at = in_book(
            page,
            """const el = [...root.querySelectorAll(arg[0])].filter(el => {
                 const r = el.getBoundingClientRect();
                 return r.width && r.height;
               })[arg[1]];
               if (!el) return null;
               el.scrollIntoView({ block: 'center' });
               const r = el.getBoundingClientRect();
               return [r.left + Math.min(r.width / 2, 4), r.top + Math.min(r.height / 2, 4)];""",
            [selector, n],
        )
        if at:
            page.mouse.click(at[0], at[1])
            page.wait_for_timeout(60)
            taps += 1
    return taps


def asked_of(page):
    """Every address the page asks for from here on, but for bytes it already holds."""
    asked = []
    page.on("request", lambda r: asked.append(r.url) if not r.url.startswith(("blob:", "data:")) else None)
    return asked


# ---------------------------------------------------------------------------
# Script
# ---------------------------------------------------------------------------

SOUND_SCRIPTS = f"""<body onload="window.__xss='body onload'">
<h1>Sound and scripted</h1>
<script>window.__xss = 'script in the body'</script>
<script src="evil.js"></script>
<p><img src="nowhere.png" onerror="window.__xss='onerror'" alt=""/>
<img src="pix.png" onload="window.__xss='onload'" alt=""/>
<span class="tap" onclick="window.__xss='onclick'" onmouseover="window.__xss='over'">tap me</span>
<video autoplay="autoplay" onloadstart="window.__xss='loadstart'" src="nowhere.mp4"></video></p>
<details open="open" ontoggle="window.__xss='toggle'"><summary class="tap">more</summary>less</details>
<p><a class="tap" href="javascript:window.__xss='a'">one</a>
<a class="tap" href="JaVaScRiPt:window.__xss='a2'">two</a>
<a class="tap" href="&#106;avascript:window.__xss='a3'">three</a>
<a class="tap" href=" &#9;java&#10;script:window.__xss='a4'">four</a>
<a class="tap" href="vbscript:window.__xss='a5'">five</a>
<a class="tap" href="data:text/html,&lt;script&gt;parent.__xss='a6'&lt;/script&gt;">six</a></p>
<iframe srcdoc="&lt;script&gt;parent.__xss='srcdoc'&lt;/script&gt;"></iframe>
<iframe src="javascript:parent.__xss='frame'"></iframe>
<object data="evil.html"></object><embed src="evil.html"/>
<form action="javascript:window.__xss='form'"><button class="tap" formaction="javascript:window.__xss='button'">go</button>
<input type="image" src="pix.png" formaction="javascript:window.__xss='input'"/></form>
<meta http-equiv="refresh" content="0;url=javascript:window.__xss='meta'"/>
<svg {SVG} width="300" height="40" onload="window.__xss='svg onload'">
  <script>window.__xss = 'script in a drawing'</script>
  <script xlink:href="evil.js"></script>
  <a class="tap" xlink:href="javascript:window.__xss='svg a'"><text x="0" y="20">drawn link</text></a>
  <a class="tap"><animate attributeName="href" values="javascript:window.__xss='animate'" dur="1s" repeatCount="indefinite"/>
    <set attributeName="xlink:href" to="javascript:window.__xss='set'"/><text x="120" y="20">moving link</text></a>
  <foreignObject width="10" height="10"><body {XHTML} onload="window.__xss='foreign'"><script>window.__xss = 'foreign script'</script></body></foreignObject>
  <use xlink:href="data:image/svg+xml,&lt;svg xmlns='http://www.w3.org/2000/svg' id='x' onload='parent.__xss=1'/&gt;#x"/>
</svg>
<math xmlns="http://www.w3.org/1998/Math/MathML"><maction actiontype="statusline#javascript:window.__xss='maction'" xlink:href="javascript:window.__xss='math'" {SVG.split(' ')[1]}><mi class="tap">x</mi></maction></math>
<p class="after">Text beside the scripts.</p>
</body>"""

# Not sound XML, for the &nbsp; in it, so this one is read as HTML: the ways markup has been
# got past a cleaner by being read one way and written back another
UNSOUND_SCRIPTS = """<body><h1>Unsound&nbsp;and scripted</h1>
<p class="after">Text beside the scripts. <a class=tap href=javascript:window.__xss='a7'>seven</a></p>
<img src=x onerror="window.__xss='plain'">
<script>window.__xss = 'script, as html'</script>
<noscript><p title="</noscript><img src=x onerror=window.__xss='noscript'>"></noscript>
<svg><style><img src=x onerror="window.__xss='svg style'"></style></svg>
<math><mtext><table><mglyph><style><!--</style><img title="--&gt;&lt;/mglyph&gt;&lt;img src=1 onerror=window.__xss='mglyph'&gt;"></table></mtext></math>
<form><math><mtext></form><form><mglyph><style></math><img src onerror="window.__xss='form'"></form>
<svg></p><style><a id="</style><img src=1 onerror=window.__xss='svg p'>"></svg>
<svg onload="window.__xss='svg onload, as html'"></svg>
<iframe src="javascript:parent.__xss='frame, as html'"></iframe>
<xmp><img src=x onerror="window.__xss='xmp'"></xmp>
</body>"""

DRAWN_SCRIPTS = f"""<?xml version="1.0" encoding="UTF-8"?>
<svg {SVG} viewBox="0 0 200 100" onload="window.__xss='drawing onload'">
  <script>window.__xss = 'script in a drawing of its own'</script>
  <a class="tap" xlink:href="javascript:window.__xss='drawing link'"><text x="10" y="40">A drawn page</text></a>
  <text class="after" x="10" y="80">Text beside the scripts.</text>
  <foreignObject width="100" height="40" y="50"><div {XHTML}><img src="x" onerror="window.__xss='drawing foreign'" alt=""/></div></foreignObject>
</svg>"""

DANGEROUS = "script, iframe, frame, object, embed, form, button, input, meta, base, link, style"


@pytest.mark.parametrize("chapter", [0, 1, 2])
def test_no_script_in_a_book_runs_however_it_is_written(viewer, page, made, fixture_path, chapter):
    """
    A chapter that is sound XHTML, one that is read as HTML, and a drawing in the reading
    order, each with script in every place markup has for it. None of it may run, none of
    it may reach the page, and a tap on any of it must leave the reader where they were.
    """
    book = little_book(
        made, "scripted.epub",
        [("", "<script>window.__xss = 'script in the head'</script>", SOUND_SCRIPTS),
         ("", "", UNSOUND_SCRIPTS)],
        listed='<item id="pix" href="pix.png" media-type="image/png"/>'
               '<item id="js" href="evil.js" media-type="text/javascript"/>'
               '<item id="page" href="evil.html" media-type="text/html"/>'
               '<item id="draw" href="draw.svg" media-type="image/svg+xml"/>',
        order=["c0", "c1", "draw"],
        extra={
            "pix.png": fixture_path("tiny.png").read_bytes(),
            "evil.js": "window.__xss = 'a script file in the book'",
            "evil.html": "<script>parent.__xss = 'a page in the book'</script>",
            "draw.svg": DRAWN_SCRIPTS,
        },
    )
    open_book(viewer, page, book)
    if chapter:
        go_to(page, chapter)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('.after')"
    )
    here = page.url
    assert tap_all(page, ".tap, a, summary, text") > 0
    page.wait_for_timeout(400)

    assert page.evaluate("() => window.__xss") is None
    assert page.url == here
    assert len(page.frames) == 1
    assert in_book(page, "return root.querySelector('.vw-page').querySelectorAll(arg).length", DANGEROUS) == 0
    assert in_book(
        page,
        """return [...root.querySelector('.vw-page').querySelectorAll('*')].flatMap(el =>
             [...el.attributes].filter(a => /^on/i.test(a.name) ||
               /^[\\s\\u0000-\\u0020]*(javascript|vbscript|data:text)/i.test(a.value.replace(/[\\t\\n\\r]/g, '')))
               .map(a => el.localName + ' ' + a.name));""",
    ) == []
    assert "Text beside the scripts." in in_book(page, "return root.querySelector('.after').textContent")


# ---------------------------------------------------------------------------
# A tap that would take the reader somewhere else
# ---------------------------------------------------------------------------

def leaving(made, fixture_path, target):
    """A book whose one chapter offers [target] in each way a tap can be given somewhere to go."""
    body = f"""<body><h1>Ways out</h1>
<p><img id="mapped" src="pix.png" usemap="#m" width="200" height="100" alt=""/></p>
<map name="m"><area shape="rect" coords="0,0,200,100" href="{target}" alt="a part of the picture"/></map>
<p><a id="plain" href="{target}" target="_blank" rel="opener">a link that asks for a window of its own</a></p>
<svg {SVG} width="300" height="60">
  <a id="drawn" xlink:href="{target}"><text x="0" y="50">a drawn link</text></a>
</svg>
</body>"""
    return little_book(
        made, "ways-out.epub", [("", "", body)],
        listed='<item id="pix" href="pix.png" media-type="image/png"/>',
        extra={"pix.png": fixture_path("tiny.png").read_bytes()},
    )


@pytest.mark.parametrize("target,shown", [
    ("https://example.invalid/away", "https://example.invalid/away"),
    ("//example.invalid/away", "https://example.invalid/away"),
    # Gander's own pages, which the app lets a viewer go to: see isViewerPage()
    ("text.html?name=spoof", None),
    ("/assets/viewer/unsupported.html?name=spoof&amp;ext=apk", None),
    ("../../doc/book", None),
])
@pytest.mark.parametrize("way", ["#mapped", "#plain", "#drawn text"])
def test_no_tap_in_a_book_leaves_the_page(viewer, page, made, fixture_path, target, shown, way):
    """
    A link, a part of a picture that is one, and a link in a drawing. None may take the
    page anywhere: an address on the web is shown for the reader to copy, as test_epub.py
    has it for a plain link, and any other does nothing.
    """
    asked = asked_of(page)
    book = leaving(made, fixture_path, target)
    open_book(viewer, page, book)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#mapped').naturalWidth > 0"
    )
    here = page.url
    del asked[:]
    assert tap_all(page, way) == 1
    page.wait_for_timeout(500)

    assert page.url == here
    assert len(page.context.pages) == 1
    assert asked == []
    assert "Ways out" in page.text_content("#book h1")
    if shown:
        assert page.is_visible("#epub-link")
        assert page.text_content("#epub-link-address") == shown
    else:
        assert not page.is_visible("#epub-link")


# ---------------------------------------------------------------------------
# Asking a host for something
# ---------------------------------------------------------------------------

REACHING_CSS = """@import "https://example.invalid/i1.css";
@import/**/"https://example.invalid/i2.css";
@\\69mport "https://example.invalid/i3.css";
@import url(https://example.invalid/i4.css);
@import "leak-i5.css";
@IMPORT/**/'leak-i6.css';
@import url("\\68ttps://example.invalid/i7.css");
@font-face { font-family: "Far"; src: url("https://example.invalid/f1.woff2"), url(leak-f2.woff2); }
h1 { color: rgb(1, 2, 3); font-family: "Far", serif; }
.a { background-image: url(https://example.invalid/u1.png); }
.b { background-image: u\\72l(https://example.invalid/u2.png); }
.c { background-image: \\75 rl(https://example.invalid/u3.png); }
.d { background-image: image-set("https://example.invalid/s1.png" 1x); }
.e { background-image: -webkit-image-set(url("https://example.invalid/s2.png") 1x); }
.f { background-image: image-set("leak-s3.png" 1x, "leak-s3b.png" 2x); }
.g { --far: "https://example.invalid/s4.png"; background-image: image-set(var(--far) 1x); }
.h { background: URL(  'https://example.invalid/u5.png'  ); }
.i { list-style-image: url("leak-u6.png"); cursor: url(leak-u7.cur), auto; }
.i::before { content: url(leak-u8.png); }
.j { border-image: url(//example.invalid/u9.png) 1; -webkit-mask-image: url(https://example.invalid/u10.png); }
.k { background-image: image-\\73 et("https://example.invalid/s5.png" 1x); }
.l { background-image: IMAGE-SET(/**/"leak-s6.png"/**/1x); }
.m { background-image: \\55RL(leak-u11.png); }
.n { background-image: url(https\\3a //example.invalid/u12.png); }
p { margin-top: 3px; }
"""

REACHING = f"""<body background="leak-b1.png"><h1>Reaching out</h1>
<div class="a">a</div><div class="b">b</div><div class="c">c</div><div class="d">d</div>
<div class="e">e</div><div class="f">f</div><div class="g">g</div><div class="h">h</div>
<ul><li class="i">i</li></ul><div class="j">j</div><div class="k">k</div><div class="l">l</div>
<div class="m">m</div><div class="n">n</div>
<div style="background-image: url(https://example.invalid/n1.png)">n1</div>
<div style="background-image: u\\72l(https://example.invalid/n2.png)">n2</div>
<div style="background-image: image-set('https://example.invalid/n3.png' 1x)">n3</div>
<div style="background-image: image-set('leak-n4.png' 1x)">n4</div>
<div style="background-image:/**/url(leak-n5.png)">n5</div>
<div style="background-image: \\75 rl(leak-n6.png)">n6</div>
<svg {SVG} width="60" height="20">
  <rect width="10" height="10" mask="url(https://example.invalid/m1.svg#m)" filter="url(https://example.invalid/m2.svg#f)"
        fill="url(leak-m3.svg#p)" clip-path="url(leak-m4.svg#c)"/>
  <rect x="12" width="10" height="10" style="mask: url(leak-m8.svg#m)" marker-start="url(leak-m9.svg#k)"/>
  <image xlink:href="https://example.invalid/m5.png" width="5" height="5"/>
  <image href="leak-m5b.png" width="5" height="5"/>
  <use xlink:href="https://example.invalid/m6.svg#u"/><use href="leak-m7.svg#u"/>
  <filter id="f"><feImage xlink:href="https://example.invalid/m10.png"/></filter>
</svg>
<p><img src="//example.invalid/p1.png" srcset="https://example.invalid/p2.png 2x, leak-p2b.png 3x" alt=""/>
<picture><source srcset="https://example.invalid/p3.png"/><img src="leak-p4.png" alt=""/></picture>
<img src="leak-p4b.png" lowsrc="leak-p4c.png" longdesc="leak-p4d.html" alt=""/></p>
<video poster="https://example.invalid/p5.png" src="https://example.invalid/p6.mp4" preload="auto">
  <source src="leak-p7.mp4"/><track src="leak-p8.vtt" default="default"/></video>
<audio src="https://example.invalid/p9.mp3" preload="auto"></audio>
<p><a id="pinged" href="#top" ping="https://example.invalid/ping leak-ping">a link that tells on its reader</a></p>
<table background="https://example.invalid/t1.png"><tr><td background="leak-t2.png">t</td></tr></table>
<style>@import "https://example.invalid/h3.css"; .o {{ background: url(leak-h4.png); }}</style>
<div class="o">o</div>
</body>"""

REACHING_HEAD = """<link rel="stylesheet" href="reach.css"/>
<link rel="stylesheet" href="https://example.invalid/l1.css"/>
<link rel="prefetch" href="https://example.invalid/l2"/>
<link rel="preload" as="image" href="leak-l3.png"/>
<link rel="dns-prefetch" href="//example.invalid"/>
<link rel="preconnect" href="https://example.invalid"/>
<link rel="icon" href="leak-l4.png"/>
<link rel="manifest" href="leak-l5.json"/>
<style>@import "https://example.invalid/h1.css"; @import 'leak-h2.css';</style>
"""


def test_nothing_is_asked_of_any_host_however_a_book_names_it(viewer, page, made):
    """
    Every way a chapter or a stylesheet has of naming a file: on another host, which the
    policy would refuse, and beside the page itself, which it would not, so that a book
    could ask Gander's own host for whatever it liked. Neither may be asked for, and the
    rules written beside them must still hold.
    """
    asked = asked_of(page)
    book = little_book(
        made, "reaching.epub", [("", REACHING_HEAD, REACHING)],
        listed='<item id="css" href="reach.css" media-type="text/css"/>',
        extra={"reach.css": REACHING_CSS},
    )
    open_book(viewer, page, book)
    wait_for_heading(page, "Reaching out")
    page.click("#book #pinged")
    page.wait_for_timeout(700)

    assert [u.rsplit("/", 1)[-1] for u in asked if "example.invalid" in u or "leak-" in u] == []
    assert style_of(page, "h1", "color") == "rgb(1, 2, 3)"
    assert style_of(page, "p", "marginTop") == "3px"


# ---------------------------------------------------------------------------
# Gander's own parts of the page
# ---------------------------------------------------------------------------

OVER_CSS = """:host, :host(#book), :host(main#book#book#book) { position: fixed !important; top: 0 !important;
  left: 0 !important; width: 100vw !important; height: 100vh !important; overflow: auto !important;
  z-index: 2147483647 !important; background: rgb(200, 0, 0) !important; }
html, body, #epub-bar, .epub-bar, #epub-turn, #epub-link, #vw-status { display: none !important; }
:host-context(body) h1 { color: rgb(1, 2, 3); }
"""


def test_a_book_cannot_draw_over_any_part_of_gander(viewer, page, made, port):
    """
    A stylesheet in the shadow tree can reach the element the tree hangs on, and nothing
    outranks what it says there with !important, so a book could lay itself over the whole
    screen and take the taps meant for the bar above it. A book's :host matches nothing, and
    the bar, Next and the address a link leaves for are each on top where they are and take a
    real tap.
    """
    book = little_book(
        made, "over.epub",
        [("", "<style>" + OVER_CSS + "</style>",
          '<body><h1>Over everything</h1><p><a id="out" href="https://example.invalid/">out</a></p></body>'),
         ("", "", "<body><h1>Second</h1></body>")],
    )
    open_book(viewer, page, book)
    wait_for_heading(page, "Over everything")
    on_top = """(sel) => {
      const part = document.querySelector(sel);
      part.scrollIntoView({ block: 'center' });
      const r = part.getBoundingClientRect();
      const el = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return !!(el && el.closest(sel));
    }"""
    for part in ("#epub-where", "#epub-next"):
        assert page.evaluate(on_top, part), part
    assert page.evaluate("() => getComputedStyle(document.querySelector('#book')).position") != "fixed"
    assert style_of(page, "h1", "color") != "rgb(1, 2, 3)"

    box = page.evaluate("""() => { const r = document.querySelector('#epub-next').getBoundingClientRect();
                             return [r.left + r.width / 2, r.top + r.height / 2]; }""")
    page.mouse.click(box[0], box[1])
    wait_for_heading(page, "Second")
    page.evaluate("() => document.querySelector('#epub-prev').click()")
    wait_for_heading(page, "Over everything")

    page.click("#book #out")
    page.wait_for_function("() => !document.querySelector('#epub-link').hidden")
    assert page.evaluate(on_top, "#epub-link")


FACES = ["Roboto", "sans-serif", "-apple-system", "Segoe UI", "system-ui", "serif", "monospace",
         "Arial", "Helvetica", "Times New Roman", "Courier New"]


def test_a_books_font_is_not_what_gander_writes_in(viewer, page, made):
    """
    A book's fonts are named in the page's own head, where a shadow tree's would do nothing,
    so a book that names one of its fonts after the font Gander writes in would have the
    address of a link, and everything else Gander says on the page, set in a font of the
    book's choosing: one whose letters need not be the letters they stand for.
    """
    from reportlab.pdfbase import pdfmetrics
    face = (Path(pdfmetrics.__file__).parent.parent / "fonts" / "VeraBd.ttf").read_bytes()
    css = "".join(
        f'@font-face {{ font-family: "{name}"; src: url("face.ttf"); }}\n'
        f'@font-face {{ font-family: {name}; src: url("face.ttf"); }}\n'
        for name in FACES if " " not in name
    ) + "".join(f'@font-face {{ font-family: "{name}"; src: url("face.ttf"); }}\n' for name in FACES if " " in name)
    book = little_book(
        made, "faces.epub",
        [("", '<link rel="stylesheet" href="book.css"/>',
          '<body><h1 style="font-family: \'Other Face\', cursive">Faces</h1>'
          '<p style="font-family: cursive"><a id="out" href="https://example.invalid/">out</a></p></body>')],
        listed='<item id="css" href="book.css" media-type="text/css"/><item id="face" href="face.ttf" media-type="font/ttf"/>',
        extra={"book.css": css, "face.ttf": face},
    )
    open_book(viewer, page, book)
    wait_for_heading(page, "Faces")
    page.click("#book #out")
    # The address stays up while the fonts are read: it is Gander's words on the page
    page.wait_for_function("() => !document.querySelector('#epub-link').hidden")
    page.evaluate("() => document.fonts.ready")
    page.wait_for_timeout(300)

    # The book did name them, or this test shows nothing
    assert page.evaluate("() => [...document.fonts].length") >= len(FACES)
    # A font is read only when something on the page is to be written in it, and nothing in
    # the chapter asks for one of these
    assert page.evaluate(
        "() => [...document.fonts].filter(f => f.status !== 'unloaded').map(f => f.family)"
    ) == []


# ---------------------------------------------------------------------------
# Keeping the page busy
# ---------------------------------------------------------------------------

def fanned_out():
    """Eight stylesheets, each bringing in the next ten times over: ten million readings."""
    sheets = {f"s{n}.css": "".join(f'@import "s{n + 1}.css";\n' for _ in range(10)) for n in range(7)}
    sheets["s7.css"] = "h1 { color: rgb(1, 2, 3); }\n"
    return sheets


def fanned_out_by_capitals():
    """Nine sheets, each a folder deeper, each bringing in the next by six spellings of its folder."""
    names = ["abcd", "Abcd", "aBcd", "abCd", "abcD", "ABcd"]
    sheets = {}
    for n in range(9):
        sheets["abcd/" * n + f"s{n}.css"] = (
            "".join(f'@import "{name}/s{n + 1}.css";\n' for name in names) if n < 8
            else "h1 { color: rgb(1, 2, 3); }\n")
    return sheets


def forged_markers():
    """Nine sheets, each importing the next, with what an import was once marked by in a string."""
    sheets = {f"m{n}.css": f'@import "m{n + 1}.css";\ni::after {{ content: "{"/*vw-import-0*/" * 6}"; }}\n'
              for n in range(8)}
    sheets["m8.css"] = "h1 { color: rgb(1, 2, 3); }\n" + "p { margin: 0; }\n" * 4
    return sheets


def sheet_of(css):
    return dict(head='<link rel="stylesheet" href="b.css"/>', extra={"b.css": "h1 { color: rgb(1, 2, 3); }\n" + css})


BUSY = {
    "imports that fan out": dict(head='<link rel="stylesheet" href="s0.css"/>', extra=fanned_out()),
    "imports that fan out by every spelling of a folder": dict(
        head='<link rel="stylesheet" href="s0.css"/>', extra=fanned_out_by_capitals()),
    "imports with a marker forged in them": dict(head='<link rel="stylesheet" href="m0.css"/>', extra=forged_markers()),
    "an address that stays open": sheet_of("url(a" + " " * 400000),
    "an import of nothing": sheet_of("@import " + "a" * 400000),
    "imports with nothing between them": sheet_of("@import" * 57000),
    "imports with no end": dict(
        head='<link rel="stylesheet" href="b.css"/>',
        extra={"b.css": "h1 { color: rgb(1, 2, 3); }\n" + "@import x " * 60000}),
    "font rules with no end": dict(
        head='<link rel="stylesheet" href="b.css"/>',
        extra={"b.css": "h1 { color: rgb(1, 2, 3); }\n" + "@font-face { src: local(x) " * 60000}),
    "addresses with no end": dict(
        head='<link rel="stylesheet" href="b.css"/>',
        extra={"b.css": "h1 { color: rgb(1, 2, 3); }\n" + "url(a " * 60000 + "\n" + 'url("a ' * 60000}),
    "sets of pictures with no end": dict(
        head='<link rel="stylesheet" href="b.css"/>',
        extra={"b.css": "h1 { color: rgb(1, 2, 3); }\n" + "p { background: image-set( " * 60000}),
    # Read as HTML, for the &nbsp;, which is where a chapter's empty elements are spelled out
    "a tag with a long gap in it": dict(tail="&nbsp;<a" + " " * 500000 + "x<p>after</p>"),
}


@pytest.mark.parametrize("how", sorted(BUSY))
def test_a_book_written_to_keep_the_page_busy_still_opens(viewer, page, made, how):
    """
    Files a few hundred kilobytes long, which is nothing to read, shaped so that reading
    them the simple way takes minutes: the same stretch gone over once for every place in
    it that looks like the start of something. Each must open in the time any book does.
    """
    case = BUSY[how]
    contents = "".join(f'<li><a href="c0.xhtml">Part {n}</a></li>' for n in range(3))
    book = little_book(
        made, "busy.epub",
        [("", case.get("head", ""),
          f'<body><h1>Busy</h1>{case.get("tail", "")}</body>')],
        contents=contents, extra=case.get("extra"),
    )
    viewer("epub.html", book)
    wait_for_book(page, timeout=15000)
    assert "Busy" in page.text_content("#book h1")
    if "head" in case:
        assert style_of(page, "h1", "color") == "rgb(1, 2, 3)"


# ---------------------------------------------------------------------------
# What a book must still be drawn with, however its addresses are written
# ---------------------------------------------------------------------------

def test_a_drawing_keeps_what_it_draws_with_from_itself(viewer, page, made):
    """
    A shape is filled, clipped and filtered by things drawn for the purpose in the same
    chapter, written url(#g) with or without quotes and spaces. Those stay, and one that
    reaches outside the chapter goes, however it is spelled: in a picture offered at sizes,
    or after a comment the engine reads as part of the address before it.
    """
    asked = asked_of(page)
    body = f"""<body><h1>Drawn</h1><svg {SVG} width="200" height="40">
<defs><linearGradient id="g"><stop offset="0" stop-color="#f00"/><stop offset="1" stop-color="#00f"/></linearGradient>
<clipPath id="c"><rect width="10" height="10"/></clipPath><filter id="f"><feGaussianBlur stdDeviation="1"/></filter></defs>
<rect id="r1" width="30" height="30" fill="url(#g)"/>
<rect id="r2" x="35" width="30" height="30" fill="url('#g')"/>
<rect id="r3" x="70" width="30" height="30" fill='url("#g")'/>
<rect id="r4" x="105" width="30" height="30" fill="url( #g)"/>
<rect id="r5" x="140" width="30" height="30" clip-path="url('#c')" filter="url( '#f' )"/>
<rect id="r6" x="175" width="20" height="20" mask="image-set(url(leak-mask.png) 1x)" filter="url(#f/*) url(leak-filter.svg#f)"/>
</svg></body>"""
    book = little_book(made, "drawn.epub", [("", "", body)])
    open_book(viewer, page, book)
    wait_for_heading(page, "Drawn")
    page.wait_for_timeout(300)

    for n in range(1, 5):
        assert in_book(page, "return root.querySelector('#r' + arg).getAttribute('fill')", n), n
        assert in_book(page, "return getComputedStyle(root.querySelector('#r' + arg)).fill", n) == 'url("#g")', n
    assert in_book(page, "return root.querySelector('#r5').getAttribute('clip-path')") == "url('#c')"
    assert in_book(page, "return getComputedStyle(root.querySelector('#r5')).filter") == 'url("#f")'
    assert in_book(page, "const r = root.querySelector('#r6'); return [r.getAttribute('mask'), r.getAttribute('filter')]") == [None, None]
    assert [u for u in asked if "leak" in u] == []


def test_a_picture_offered_at_several_sizes_still_shows(viewer, page, made, fixture_path):
    """
    image-set() offers a picture at more than one size, by url() or by its address in quotes
    alone. Each is answered from the zip like any other address, so the picture shows, and
    a book that gave a plain picture first and the sizes after loses neither.
    """
    asked = asked_of(page)
    css = (".one { background-image: url(pix.png); background-image: -webkit-image-set(url(pix.png) 1x, url(pix.png) 2x); }\n"
           ".two { background: image-set(url(pix.png) 1x) no-repeat; }\n"
           '.three { background-image: image-set("pix.png" 1x, "pix.png" 2x); }\n')
    book = little_book(
        made, "sizes.epub",
        [("", '<link rel="stylesheet" href="b.css"/>',
          '<body><h1>Sizes</h1><div class="one">1</div><div class="two">2</div><div class="three">3</div></body>')],
        listed='<item id="pix" href="pix.png" media-type="image/png"/>',
        extra={"b.css": css, "pix.png": fixture_path("tiny.png").read_bytes()},
    )
    open_book(viewer, page, book)
    wait_for_heading(page, "Sizes")
    for name in ("one", "two", "three"):
        assert "blob:" in style_of(page, "." + name, "backgroundImage"), name
    assert [u for u in asked if "pix.png" in u] == []


@pytest.mark.parametrize("style", [
    "background-color: rgb(253, 246, 227); /* paper */",
    "background-color: rgb(253, 246, 227); font-family: 'A\\62 c', serif",
])
def test_a_chapters_style_for_its_own_body_holds(viewer, page, made, style):
    """A body's own style with a comment or an escape in it, neither of which names a file."""
    book = little_book(made, "own.epub", [("", "", f'<body style="{style}"><h1>Own style</h1><p>text</p></body>')])
    open_book(viewer, page, book)
    wait_for_heading(page, "Own style")
    assert style_of(page, "body", "backgroundColor") == "rgb(253, 246, 227)"
    if "font-family" in style:
        assert style_of(page, "body", "fontFamily").startswith('Abc')


def test_a_sheet_imported_from_two_places_stands_where_the_engine_puts_it(viewer, page, made):
    """
    b.css and c.css both import d.css, b with a rule after it. The engine reads d at both
    places, so d's rule, at the second, has the last word over b's.
    """
    extra = {
        "a.css": '@import "b.css";\n@import "c.css";\n',
        "b.css": '@import "d.css";\nh1 { color: rgb(200, 0, 0); }\n',
        "c.css": '@import "d.css";\n',
        "d.css": "h1 { color: rgb(1, 2, 3); }\n",
    }
    book = little_book(made, "diamond.epub", [("", '<link rel="stylesheet" href="a.css"/>', "<body><h1>Diamond</h1></body>")],
                       extra=extra)
    open_book(viewer, page, book)
    wait_for_heading(page, "Diamond")
    assert style_of(page, "h1", "color") == "rgb(1, 2, 3)"


@pytest.mark.parametrize("sheet", [
    '@import "my styles.css";\n',
    "@import url('my styles.css') screen;\n",
    '@import "my styles.css"',
])
def test_a_sheet_is_imported_however_the_import_is_written(viewer, page, made, sheet):
    """A name with a space in it, an import with what it is for after it, and one the sheet ends in."""
    asked = asked_of(page)
    book = little_book(made, "imported.epub", [("", '<link rel="stylesheet" href="a.css"/>', "<body><h1>Imported</h1></body>")],
                       extra={"a.css": sheet, "my styles.css": "h1 { color: rgb(1, 2, 3); }\n"})
    open_book(viewer, page, book)
    wait_for_heading(page, "Imported")
    assert style_of(page, "h1", "color") == "rgb(1, 2, 3)"
    assert [u for u in asked if "styles" in u] == []


@pytest.mark.parametrize("inner", [
    "h1 { color: rgb(1, 2, 3)",
    "h1 { color: rgb(1, 2, 3); } h2",
    "h1 { color: rgb(1, 2, 3); } /* a note",
    'h1 { color: rgb(1, 2, 3); content: "a quote',
    "h1 { color: rgb(1, 2, 3); } @namespace svg url(http://www.w3.org/2000/svg)",
    'h1 { color: rgb(1, 2, 3); } @namespace svg "http://www.w3.org/2000/svg"',
])
def test_a_sheet_imported_unfinished_leaves_the_rest_whole(viewer, page, made, inner):
    """
    The engine reads an imported sheet to its own end, where whatever it left open closes.
    Written into the sheet that imports it, a block, a rule, a comment, a quote or a
    namespace it leaves open must close there too, and not take in the rules after the import.
    """
    book = little_book(made, "unfinished.epub",
                       [("", '<link rel="stylesheet" href="a.css"/>', "<body><h1>Unfinished</h1><p>after</p></body>")],
                       extra={"a.css": '@import "inner.css";\np { margin-top: 3px; }\n', "inner.css": inner})
    open_book(viewer, page, book)
    wait_for_heading(page, "Unfinished")
    assert style_of(page, "h1", "color") == "rgb(1, 2, 3)"
    assert style_of(page, "p", "marginTop") == "3px"


def test_a_name_in_a_chapter_is_not_a_link(viewer, page, made):
    """
    An a with no address is a name for a place, and a tap on it is a tap on what it is in: a
    summary that opens its details, or a link around it. A summary inside a link is nearer
    than the link, and a tap on it opens its details and goes nowhere, as the engine has it.
    """
    book = little_book(made, "names.epub", [
        ("", "", '<body><h1>Names</h1><details id="d1"><summary><a id="s1">Answers</a></summary><p>42</p></details>'
                 '<a href="c1.xhtml"><details id="d2"><summary id="s2">More</summary><p>43</p></details></a>'
                 '<p><a href="c1.xhtml"><a id="p12">To the second</a></a></p></body>'),
        ("", "", "<body><h1>Second</h1></body>"),
    ])
    open_book(viewer, page, book)
    wait_for_heading(page, "Names")
    page.click("#book #s1")
    page.click("#book #s2")
    page.wait_for_timeout(300)
    assert in_book(page, "return [root.querySelector('#d1').open, root.querySelector('#d2').open]") == [True, True]
    assert "Names" in page.text_content("#book h1")
    page.click("#book #p12")
    wait_for_heading(page, "Second")


# ---------------------------------------------------------------------------
# Spellings of an address the engine reads and a pattern would not
# ---------------------------------------------------------------------------

LINKED = '<link rel="stylesheet" href="reach.css"/>'
PLAIN = '<body><h1>Spelled</h1><div class="a">a</div><i>i</i></body>'

SPELLINGS = {
    "an escape for a letter written into another escape": (LINKED, PLAIN, {"reach.css": ".a { background: ur\\6\\63 (leak-fused.png) }"}),
    "an escape for a hyphen in image-set": (LINKED, PLAIN, {"reach.css": '.a { background-image: image\\2d set("leak-hyphen.png" 1x) }'}),
    "a quote inside a bare address": (LINKED, PLAIN, {"reach.css": "i { content: url(x'y) } .a { background: u\\72l(leak-badurl.png) }"}),
    "a line carried on in a string": (LINKED, PLAIN, {"reach.css": 'i { content: "a\\\r\n b"; } .a { background: u\\72l(leak-crlf.png) } /* " */'}),
    "a form feed ending a string": (LINKED, PLAIN, {"reach.css": "i { content: '\f } .a { background: u\\72l(leak-ff.png) }"}),
    "a carriage return ending a string": (LINKED, PLAIN, {"reach.css": "i { content: '\r } .a { background: u\\72l(leak-cr.png) }"}),
    "an import the sheet ends in": (LINKED, PLAIN, {"reach.css": '@import "leak-eof.css"'}),
    "an import with an @ after it": (LINKED, PLAIN, {"reach.css": '@import "leak-at.css" @x;\n.a { color: red }'}),
    "an import with a block after it": (LINKED, PLAIN, {"reach.css": '@import "leak-brace.css" ({});\n.a { color: red }'}),
    "an address the sheet ends in": (LINKED, PLAIN, {"reach.css": ".a { background: url(leak-unclosed.png"}),
    "an address a style ends in": ("", '<body><h1>Spelled</h1><div class="a" style="background:url(leak-attr.png">a</div></body>', {}),
    "an escaped quote in a quoted address": (LINKED, PLAIN, {"reach.css": '.a { background: url("leak-esc\\"x.png") }'}),
    "an escaped bracket in a bare address": (LINKED, PLAIN, {"reach.css": ".a { background: url(leak-paren\\(1.png) }"}),
    "an address with a modifier": (LINKED, PLAIN, {"reach.css": '.a { background: url("leak-mod.png" crossorigin(anonymous)) }'}),
    "an import cut out of the middle of url(": (LINKED, PLAIN, {"reach.css": '.a{background:u@import "nothere.css";rl(leak-splice.png)}'}),
    "a font rule cut out of the middle of url(": (LINKED, PLAIN, {"reach.css": ".a{background:u@font-face{}rl(leak-facesplice.png)}"}),
    "an import marker forged in a string": (LINKED, PLAIN, {
        "reach.css": "@import \"inner.css\";\ni{content:'/*vw-import-0*/'}",
        "inner.css": "x{content:\"'} .a { background: u\\72l(leak-forged.png) } q { content:'\"}",
    }),
    "image-set inside quotes": (LINKED, PLAIN, {"reach.css": 'i{content:"image-set("\n)} b{content:" } .a{background:u\\72l(leak-setquote.png)} d{content:"}'}),
    "attributes named in capitals": ("", '<body><h1>Spelled</h1><p><img SRC=" leak-upper.png" alt=""/></p>'
                                         '<p STYLE=" background:url(leak-upperstyle.png)">x</p></body>', {}),
    "image-set in a drawing's attribute": ("", f'<body><h1>Spelled</h1><svg {SVG} width="60" height="20">'
                                               '<rect width="20" height="20" mask="image-set(url(leak-mask.png) 1x)" '
                                               'cursor="image-set(\'leak-cursor.png\' 1x), auto"/></svg></body>', {}),
    "if() in image-set": (LINKED, PLAIN, {"reach.css": '.a { background-image: image-set(if(else: "leak-if.png") 1x) }'}),
    "if() with a media test in image-set": (LINKED, PLAIN, {
        "reach.css": '.a { background-image: image-set(if(media(width > 1px): "leak-ifmedia.png"; else: "x.png") 1x) }'}),
    "a book's own function in image-set": (LINKED, PLAIN, {
        "reach.css": '@function --f() { result: "leak-fn.png"; }\n.a { background-image: image-set(--f() 1x) }'}),
    "a book's own function handing on what it is given": (LINKED, PLAIN, {
        "reach.css": '@function --pick(--x) { result: var(--x); }\n.a { background-image: image-set(--pick("leak-arg.png") 1x) }'}),
    "a book's own function in a style element": (
        '<style>@function --g() { result: "leak-fnstyle.png"; } .a { background-image: image-set(--g() 1x) }</style>', PLAIN, {}),
    "if() in image-set in a style": ("", '<body><h1>Spelled</h1><div class="a" style="background-image: '
                                         'image-set(if(else: \'leak-ifattr.png\') 1x)">a</div></body>', {}),
    "if() in image-set in the body's style": ("", '<body style="background-image: image-set(if(else: \'leak-ifbody.png\') 1x)">'
                                                  '<h1>Spelled</h1></body>', {}),
}


@pytest.mark.parametrize("how", sorted(SPELLINGS))
def test_no_spelling_of_an_address_gets_past_the_reader(viewer, page, made, how):
    """
    Each a way of writing an address that the engine takes as one and a pattern does not,
    or of hiding one in plain text that a pattern read as an address and took out. Gander's
    own host would answer any of them, so none may be asked for.
    """
    head, body, extra = SPELLINGS[how]
    asked = asked_of(page)
    book = little_book(made, "spelled.epub", [("", head, body)], extra=extra)
    open_book(viewer, page, book)
    wait_for_heading(page, "Spelled")
    page.wait_for_timeout(500)
    assert [u.rsplit("/", 1)[-1] for u in asked if "leak" in u] == []


def test_a_link_named_like_what_every_object_has_goes_nowhere(viewer, page, made):
    """
    The chapters are kept by their names, and every object has a constructor and the like:
    a link to one must find no chapter, and a tap on it must leave the book where it is.
    """
    names = ["/constructor", "/toString", "/hasOwnProperty", "../../__proto__"]
    body = "<body><h1>Named</h1>" + "".join(f'<p><a id="n{i}" href="{n}">{n}</a></p>' for i, n in enumerate(names)) + "</body>"
    book = little_book(made, "named.epub", [("", "", body)])
    open_book(viewer, page, book)
    wait_for_heading(page, "Named")
    for i in range(len(names)):
        page.click(f"#book #n{i}")
        page.wait_for_timeout(150)
    assert page.locator("#vw-status.vw-error").count() == 0
    assert "Named" in page.text_content("#book h1")


def test_a_middle_click_on_a_link_opens_nothing(viewer, page, made, fixture_path):
    """A middle click opens a link in a page of its own, which no link in a book may."""
    book = leaving(made, fixture_path, "text.html?name=spoof")
    open_book(viewer, page, book)
    at = in_book(page, "const r = root.querySelector('#plain').getBoundingClientRect(); return [r.left + 4, r.top + r.height / 2];")
    page.mouse.click(at[0], at[1], button="middle")
    page.wait_for_timeout(500)
    assert len(page.context.pages) == 1


def test_a_namespace_keeps_its_name(viewer, page, made):
    """
    A namespace's address is a name and nothing to fetch, so it stays as written. A sheet
    that styles a drawing's shapes by svg|rect still reaches them.
    """
    css = '@namespace svg url(http://www.w3.org/2000/svg);\nsvg|rect { fill: rgb(1, 2, 3); }\n'
    body = f'<body><h1>Named space</h1><svg {SVG} width="20" height="20"><rect id="r" width="20" height="20"/></svg></body>'
    book = little_book(made, "space.epub", [("", '<link rel="stylesheet" href="b.css"/>', body)], extra={"b.css": css})
    open_book(viewer, page, book)
    wait_for_heading(page, "Named space")
    assert in_book(page, "return getComputedStyle(root.querySelector('#r')).fill") == "rgb(1, 2, 3)"


# Each a link around a summary that takes no tap, and whether its details is open after one
WRAPPED = {
    "a summary in no details": ('<p><a href="text.html?name=spoof"><summary id="t">tap</summary></a></p>', None),
    "a details' second summary": ('<a href="text.html?name=spoof"><details id="d" open="open"><summary>one</summary>'
                                  '<summary id="t">two</summary></details></a>', True),
    "an output in a details' summary": ('<a href="text.html?name=spoof"><details id="d"><summary>'
                                        '<output id="t">tap</output></summary></details></a>', False),
    "a fieldset in a details' summary": ('<a href="text.html?name=spoof"><details id="d"><summary>'
                                         '<fieldset id="t">tap</fieldset></summary></details></a>', False),
}


@pytest.mark.parametrize("how", sorted(WRAPPED))
def test_a_link_around_a_summary_that_takes_no_tap_is_still_not_followed(viewer, page, made, how):
    """
    A summary takes a tap from the link around it only where it opens its details: as the
    first summary of one, and not through a control inside it. Anywhere else the tap is the
    link's, which the engine would follow, to Gander's own pages too, so the reader takes it.
    A middle click opens nothing either.
    """
    html, opened = WRAPPED[how]
    book = little_book(made, "wrapped.epub", [("", "", "<body><h1>Wrapped</h1>" + html + "</body>")])
    open_book(viewer, page, book)
    wait_for_heading(page, "Wrapped")
    here = page.url
    page.click("#book #t")
    page.wait_for_timeout(500)
    assert page.url == here
    assert "Wrapped" in page.text_content("#book h1")
    if opened is not None:
        assert in_book(page, "return root.querySelector('#d').open") == opened

    at = in_book(page, "const r = root.querySelector('#t').getBoundingClientRect(); return [r.left + 4, r.top + r.height / 2];")
    page.mouse.click(at[0], at[1], button="middle")
    page.wait_for_timeout(500)
    assert len(page.context.pages) == 1


@pytest.mark.parametrize("unblocked", [
    '@font-face src: url("data:font/ttf;base64,AAAA")',
    '@media screen { @font-face src: url("data:font/ttf;base64,AAAA") }',
])
def test_a_font_rule_with_no_block_takes_none_of_the_next(viewer, page, made, unblocked):
    """
    A font rule with no block of its own is none, and the engine drops it. Kept as it was
    written, it would take the next sheet's font rule for its block, and a font the book
    has would be lost with it.
    """
    from reportlab.pdfbase import pdfmetrics
    vera = (Path(pdfmetrics.__file__).parent.parent / "fonts" / "Vera.ttf").read_bytes()
    book = little_book(
        made, "unblocked.epub",
        [("", '<link rel="stylesheet" href="a.css"/><link rel="stylesheet" href="b.css"/>', "<body><h1>Unblocked</h1></body>")],
        extra={
            "a.css": unblocked,
            "b.css": '@font-face { font-family: "Book Face"; src: url("face.ttf"); }\nh1 { font-family: "Book Face", monospace; }\n',
            "face.ttf": vera,
        },
    )
    open_book(viewer, page, book)
    wait_for_heading(page, "Unblocked")
    page.wait_for_function(
        "() => [...document.fonts].some(f => f.family.includes('Book Face') && f.status === 'loaded')",
        timeout=10000,
    )


@pytest.mark.parametrize("algorithm", ["constructor", "__proto__"])
def test_a_lock_named_like_what_every_object_has_still_locks(viewer, page, made, algorithm):
    """
    Two scramblings are known by name, and a chapter under any other is locked. One named
    after what every object has is no scrambling, and the chapter must not be unscrambled
    into nonsense by it but said to be locked.
    """
    encryption = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" '
        'xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData>'
        f'<enc:EncryptionMethod Algorithm="{algorithm}"/>'
        '<enc:CipherData><enc:CipherReference URI="c0.xhtml"/></enc:CipherData>'
        '</enc:EncryptedData></encryption>'
    )
    book = little_book(made, "named-lock.epub", [("", "", "<body><h1>Locked away</h1></body>")],
                       extra={"META-INF/encryption.xml": encryption})
    viewer("epub.html", book)
    page.wait_for_selector(".vw-error", timeout=15000)
    assert "This book is locked" in status_text(page)


def test_a_file_named_like_what_every_object_has_is_no_known_kind(viewer, page, made, fixture_path):
    """
    A file the package gives no type is known by its ending, and one ending in "constructor"
    is no kind of file at all, so it is not handed to the engine as one.
    """
    book = little_book(made, "kinds.epub", [("", "", '<body><h1>Kinds</h1><p><img id="p" src="pic.constructor" alt=""/></p></body>')],
                       extra={"pic.constructor": fixture_path("tiny.png").read_bytes()})
    open_book(viewer, page, book)
    wait_for_heading(page, "Kinds")
    page.wait_for_timeout(300)
    assert not (in_book(page, "return root.querySelector('#p').getAttribute('src')") or "").startswith("blob:")
