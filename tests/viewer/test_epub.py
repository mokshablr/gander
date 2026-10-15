"""
epub.html: a book, read a chapter at a time by a reader of Gander's own (epub.js).

The chapter is drawn in the shadow tree of #book. Playwright's selectors go through an
open shadow root, so "#book h1" below is the chapter's heading.
"""

import hashlib
import re
import struct
from pathlib import Path

import pytest

from helpers import highlight_count, status_text

BOOK = "harbour.epub"

# Pages per chapter of harbour.epub at 2048 bytes a page: 1, 1, 6 and 1
TOTAL = 9


def wait_for_book(page, timeout=20000):
    """The strip above a chapter appears once the first one is dressed."""
    page.wait_for_function(
        "() => !document.querySelector('#epub-bar').hidden", timeout=timeout
    )


def wait_for_heading(page, words, timeout=10000):
    page.wait_for_function(
        """(words) => {
          const h = document.querySelector('#book').shadowRoot.querySelector('h1');
          const page = document.querySelector('#book').shadowRoot.querySelector('.vw-page');
          return h && h.textContent.includes(words) && page.style.opacity !== '0';
        }""",
        arg=words,
        timeout=timeout,
    )


def open_book(viewer, page, fixture=BOOK, **params):
    viewer("epub.html", fixture, **params)
    wait_for_book(page)


def turn_to(page, heading):
    """Next, until the chapter under [heading] is the one on the page."""
    for _ in range(6):
        if heading in (page.text_content("#book h1") or ""):
            break
        page.click("#epub-next")
        page.wait_for_timeout(150)
    wait_for_heading(page, heading)


def top_of(page, selector):
    """How far down the screen an element in the chapter is."""
    return page.evaluate(
        "(s) => document.querySelector('#book').shadowRoot.querySelector(s)"
        ".getBoundingClientRect().top",
        selector,
    )


def wait_until_on_screen(page, selector):
    """
    Scrolled to, as far as the chapter allows: a place near a short chapter's foot cannot
    reach the top of the screen, so on screen with the page moved is what is asked. One that
    does reach the top may sit a fraction of a pixel above it, since the page scrolls by
    whole pixels and a line of text is not a whole number of them tall.
    """
    page.wait_for_function(
        """(s) => {
          const top = document.querySelector('#book').shadowRoot.querySelector(s)
            .getBoundingClientRect().top;
          return window.pageYOffset > 0 && top > -1 && top < window.innerHeight;
        }""",
        arg=selector,
    )


def style_of(page, selector, name):
    return page.evaluate(
        "([s, name]) => getComputedStyle(document.querySelector('#book').shadowRoot"
        ".querySelector(s))[name]",
        [selector, name],
    )


def go_to(page, index):
    """Chapter [index], by its place in the book, for a chapter with no heading to wait for."""
    page.evaluate("(i) => { vwEpub.go(i, null); }", index)
    page.wait_for_function(
        """(i) => vwFindParts.shown() === i && document.querySelector('#book').shadowRoot
          .querySelector('.vw-page').style.opacity !== '0'""",
        arg=index,
    )


def box_of(page, selector):
    """Where an element of the chapter is on the screen, and how much of it there is to scroll."""
    return page.evaluate(
        """(s) => {
          const el = document.querySelector('#book').shadowRoot.querySelector(s);
          const r = el.getBoundingClientRect();
          return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width,
                   height: r.height, inside: el.clientWidth, across: el.scrollWidth };
        }""",
        selector,
    )


def little_book(made, name, chapters, metadata="", contents="", extra=None,
                identifier="urn:uuid:00000000-0000-4000-8000-000000000054", listed="", order=None,
                spine="", html="", package="", title_xml="<dc:title>A Little Book</dc:title>"):
    """
    A book written for one test, for what no committed one has: [chapters] is a list of
    (what its place in the reading order says of it, what goes in its head, its body), and
    [extra] any other files by name. [listed] is what the package lists beside the chapters,
    and [order] the reading order by id where it is not simply the chapters, c0 onward.
    """
    import io
    import zipfile

    def chapter(head, body):
        return (
            '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n'
            f'<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" {html}>'
            f'<head><title>A chapter</title>{head}</head>{body}</html>\n'
        )

    items = "".join(
        f'<item id="c{n}" href="c{n}.xhtml" media-type="application/xhtml+xml"/>'
        for n in range(len(chapters))
    )
    refs = "".join(f'<itemref idref="c{n}" {says}/>' for n, (says, _, _) in enumerate(chapters))
    if order is not None:
        refs = "".join(f'<itemref idref="{i}"/>' for i in order)
    package = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid" {package}>'
        '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
        '<dc:identifier>not the one the package calls its own</dc:identifier>'
        f'<dc:identifier id="uid">{identifier}</dc:identifier>'
        f'{title_xml}<dc:language>en</dc:language>{metadata}</metadata>'
        '<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
        f'{items}{listed}</manifest><spine {spine}>{refs}</spine></package>\n'
    )
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip")
        z.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0"?><container version="1.0" '
            'xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>'
            '<rootfile full-path="package.opf" media-type="application/oebps-package+xml"/>'
            '</rootfiles></container>',
        )
        z.writestr("package.opf", package)
        z.writestr("nav.xhtml", chapter("", f'<body><nav epub:type="toc"><ol>{contents}</ol></nav></body>'))
        for n, (_, head, body) in enumerate(chapters):
            z.writestr(f"c{n}.xhtml", chapter(head, body))
        for path, data in (extra or {}).items():
            z.writestr(path, data)
    return made(name, buf.getvalue())


# ---------------------------------------------------------------------------
# Opening, and what a chapter takes from the zip
# ---------------------------------------------------------------------------

def test_a_book_opens_at_its_first_chapter(viewer, page):
    open_book(viewer, page)
    assert "The Harbour at Low Tide" in page.text_content("#book h1")
    assert page.text_content("#epub-where") == "Title page"
    assert page.is_disabled("#epub-prev")
    assert not page.is_disabled("#epub-next")


def test_a_picture_comes_out_of_the_zip(viewer, page):
    open_book(viewer, page)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#mark').naturalWidth === 48"
    )
    src = page.get_attribute("#book #mark", "src")
    assert src.startswith("blob:")


def test_the_books_stylesheet_applies_with_the_one_it_imports(viewer, page):
    open_book(viewer, page)
    # book.css, and the centring it gives the body it names by class
    assert style_of(page, "h1", "color") == "rgb(120, 40, 20)"
    assert style_of(page, "body", "textAlign") == "center"
    # extra.css, imported from book.css, with its picture found from its own folder
    assert style_of(page, ".byline", "backgroundImage").startswith('url("blob:')
    turn_to(page, "The Lamp Room")
    assert style_of(page, ".note", "fontStyle") == "italic"


def test_a_stylesheet_in_the_chapter_itself_applies(viewer, page):
    open_book(viewer, page)
    turn_to(page, "The Tide Table")
    assert style_of(page, "h2", "letterSpacing") == "1px"


def test_the_books_stylesheet_does_not_reach_the_page_around_it(viewer, page):
    """The contents list has an h1 of its own, which the book's h1 rule must not colour."""
    open_book(viewer, page)
    colour = page.evaluate(
        "() => getComputedStyle(document.querySelector('#epub-contents-title')).color"
    )
    assert colour != "rgb(120, 40, 20)"


def test_a_picture_named_in_an_inline_style_and_in_a_drawing(viewer, page):
    open_book(viewer, page)
    turn_to(page, "The Lamp Room")
    assert style_of(page, "#crest", "backgroundImage").startswith('url("blob:')
    turn_to(page, "The Tide Table")
    href = page.evaluate(
        "() => document.querySelector('#book').shadowRoot.querySelector('#drawn')"
        ".getAttributeNS('http://www.w3.org/1999/xlink', 'href')"
    )
    assert href.startswith("blob:")


def test_a_scrambled_font_is_left_out_and_the_book_still_opens(viewer, page):
    """
    The book's font is scrambled, which is not DRM, and what comes of unscrambling it is
    not a font: the file is noise. The text reads without it. Its @font-face rule goes too,
    since a rule with no font behind it is one the engine complains about, and the heading
    falls back to the face named after it.
    """
    said = []
    page.on("console", lambda m: said.append(m.text))
    open_book(viewer, page)
    faces = page.evaluate(
        "() => [...document.querySelectorAll('style')].map(s => s.textContent).join('')"
    )
    assert "Keeper Hand" not in faces
    assert "Keeper Hand" in style_of(page, "h1", "fontFamily")
    assert not [line for line in said if "font" in line.lower()]


def head_at(font, where):
    """
    The same font with its tables in another order, so that the one named head begins at
    byte [where]. That table holds a number every font must carry, twelve bytes in, and the
    engine takes no font without it. So with head at 1024 the number sits in the sixteen
    bytes that one way of scrambling covers and the other does not, and unscrambling too
    little of the file, or too much, leaves no font.
    """
    count = struct.unpack(">H", font[4:6])[0]
    tables = {}
    for n in range(count):
        tag, checksum, offset, length = struct.unpack(">4sIII", font[12 + 16 * n:28 + 16 * n])
        tables[tag] = (checksum, font[offset:offset + length])
    at = 12 + 16 * count
    placed, body, after = {}, b"", []
    for tag in sorted((t for t in tables if t != b"head"), key=lambda t: len(tables[t][1])):
        size = (len(tables[tag][1]) + 3) & ~3
        if at + size > where:
            after.append(tag)
            continue
        placed[tag] = at
        body += tables[tag][1].ljust(size, b"\0")
        at += size
    body += b"\0" * (where - at)
    at = where
    for tag in [b"head"] + after:
        size = (len(tables[tag][1]) + 3) & ~3
        placed[tag] = at
        body += tables[tag][1].ljust(size, b"\0")
        at += size
    directory = b"".join(
        struct.pack(">4sIII", tag, tables[tag][0], placed[tag], len(tables[tag][1]))
        for tag in sorted(tables)
    )
    made = font[:12] + directory + body
    assert made[where + 12:where + 16] == b"\x5f\x0f\x3c\xf5"
    return made


def scramble(font, algorithm, identifier):
    """A font as a book scrambles it, worked here from the two specifications' own words."""
    if algorithm.endswith("embedding"):
        key = hashlib.sha1(re.sub(r"[ \t\r\n]", "", identifier).encode("utf-8")).digest()
        span = 1040
    else:
        key = bytes.fromhex(re.search(r"[0-9a-f-]{36}", identifier).group().replace("-", ""))
        span = 1024
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(font[:span])) + font[span:]


@pytest.mark.parametrize("algorithm,identifier", [
    # The identifier has spaces and a line end in it, which come out before it is hashed
    ("http://www.idpf.org/2008/embedding", " heronsgate press : a little book \n"),
    ("http://ns.adobe.com/pdf/enc#RC", "urn:uuid:1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"),
])
def test_a_scrambled_font_is_put_right(viewer, page, made, algorithm, identifier):
    """
    A publisher's book scrambles its fonts, one of two published ways, with a key made from
    the book's own identifier. Put right, the font loads, which a font still scrambled, or
    unscrambled with the wrong key, does not: the engine takes nothing that is not sound.
    """
    from reportlab.pdfbase import pdfmetrics
    vera = head_at((Path(pdfmetrics.__file__).parent.parent / "fonts" / "Vera.ttf").read_bytes(), 1024)
    encryption = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" '
        'xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData>'
        f'<enc:EncryptionMethod Algorithm="{algorithm}"/>'
        '<enc:CipherData><enc:CipherReference URI="face.ttf"/></enc:CipherData>'
        '</enc:EncryptedData></encryption>'
    )
    book = little_book(
        made, "typeset.epub",
        [("", '<link rel="stylesheet" href="book.css"/>', "<body><h1>Set in the book's own face</h1></body>")],
        identifier=identifier,
        extra={
            "META-INF/encryption.xml": encryption,
            "face.ttf": scramble(vera, algorithm, identifier),
            "book.css": '@font-face { font-family: "Book Face"; src: url("face.ttf"); }\n'
                        'h1 { font-family: "Book Face", monospace; }\n',
        },
    )
    said = []
    page.on("console", lambda m: said.append(m.text))
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => [...document.fonts].some(f => f.family.includes('Book Face') && f.status === 'loaded')",
        timeout=10000,
    )
    assert not [line for line in said if "font" in line.lower()]


def test_a_chapter_that_is_not_sound_xml_is_read_as_html(viewer, page):
    open_book(viewer, page)
    turn_to(page, "The Long")
    assert page.text_content("#book h1") == "Two: The Long Watch"
    assert len(page.query_selector_all("#book p")) == 60
    # An empty anchor written <a id="halfway"/> must not swallow what follows it
    assert page.evaluate(
        "() => document.querySelector('#book').shadowRoot.querySelector('#halfway').textContent"
    ) == ""


# ---------------------------------------------------------------------------
# A chapter is somebody else's markup
# ---------------------------------------------------------------------------

def test_script_in_a_chapter_does_not_run(viewer, page):
    open_book(viewer, page)
    turn_to(page, "The Lamp Room")
    page.click("#book #tapped")
    page.click("#book #bad-link")
    page.wait_for_timeout(400)

    assert page.evaluate("() => window.__xss") is None
    assert page.query_selector("#book script") is None
    assert page.get_attribute("#book #tapped", "onclick") is None
    assert page.get_attribute("#book #missing", "onerror") is None
    assert page.get_attribute("#book #bad-link", "href") is None
    assert "Text after the injected markup" in page.text_content("#book .note")


def test_nothing_outside_the_book_is_asked_for(viewer, page):
    """
    A picture on another host, a picture the book does not hold and a web link that is
    tapped: none of them may become a request. The policy would refuse the first, and
    conftest fails a test where anything was refused, so this also checks that the page
    never tried.
    """
    asked = []
    page.on("request", lambda r: asked.append(r.url))
    open_book(viewer, page)
    turn_to(page, "The Lamp Room")
    page.click("#book #web-link")
    page.wait_for_timeout(400)

    assert page.get_attribute("#book #remote", "src") is None
    assert page.get_attribute("#book #missing", "src") is None
    assert not [u for u in asked if "example.invalid" in u or "nowhere.png" in u]
    assert "The Lamp Room" in page.text_content("#book h1")


# ---------------------------------------------------------------------------
# Getting about: the two buttons, the contents and links
# ---------------------------------------------------------------------------

def test_next_and_previous_turn_the_chapter(viewer, page):
    open_book(viewer, page)
    page.click("#epub-next")
    wait_for_heading(page, "The Lamp Room")
    assert page.text_content("#epub-where") == "One: The Lamp Room"
    page.click("#epub-prev")
    wait_for_heading(page, "The Harbour at Low Tide")

    turn_to(page, "The Tide Table")
    assert page.is_disabled("#epub-next")


def test_the_contents_list_is_the_books_own(viewer, page):
    open_book(viewer, page)
    page.click("#epub-contents-open")
    assert page.is_visible("#epub-contents")
    assert page.text_content("#epub-title") == "The Harbour at Low Tide"
    labels = page.eval_on_selector_all(
        "#epub-contents-list li", "els => els.map(e => e.textContent)"
    )
    assert labels == [
        "Title page", "One: The Lamp Room", "The ledger", "Two: The Long Watch",
        "Three: The Tide Table", "Notes, in the printed edition only",
    ]
    # The entry that goes nowhere is words and not a button
    assert len(page.query_selector_all("#epub-contents-list button")) == 5
    assert page.get_attribute("#epub-contents-list button[aria-current]", "data-n") == "0"

    page.click("#epub-contents-list button >> text=Three: The Tide Table")
    wait_for_heading(page, "The Tide Table")
    assert not page.is_visible("#epub-contents")


def test_a_contents_entry_goes_to_its_place_in_a_chapter(viewer, page):
    open_book(viewer, page)
    page.set_viewport_size({"width": 390, "height": 300})
    page.click("#epub-contents-open")
    page.click("#epub-contents-list button >> text=The ledger")
    wait_for_heading(page, "The Lamp Room")
    wait_until_on_screen(page, "#ledger")


def test_opening_and_closing_the_contents_does_not_move_the_page(viewer, page):
    """
    Clicked from script, because Playwright scrolls a button into view before it clicks it
    and the button is at the top of the chapter. What is checked is that the list coming and
    going, and the focus it hands back, leave the chapter where it was.
    """
    open_book(viewer, page)
    turn_to(page, "The Long")
    page.evaluate("() => window.scrollTo(0, 900)")
    page.evaluate("() => document.querySelector('#epub-contents-open').click()")
    assert page.is_visible("#epub-contents")
    page.evaluate("() => document.querySelector('#epub-contents-close').click()")
    assert not page.is_visible("#epub-contents")
    assert page.evaluate("() => window.pageYOffset") == 900
    assert "The Long" in page.text_content("#book h1")


def test_the_apps_menu_opens_the_contents_and_back_closes_them(viewer, page, port):
    """
    The strip with the Contents button is at the top of a chapter and a reader is seldom
    there, so the app's menu asks for the list too, and Back is the page's to answer while
    the list is up: it tells the app when that is.
    """
    open_book(viewer, page)
    channel = port()
    channel.wait_for(rf"page 1 {TOTAL}")
    turn_to(page, "The Long")
    page.evaluate("() => window.scrollTo(0, 900)")

    channel.contents()
    page.wait_for_selector("#epub-contents", state="visible")
    channel.wait_for(r"cover 1")
    assert page.get_attribute("#epub-contents-list button[aria-current]", "data-n") == "3"

    channel.clear_inbox()
    channel.close_over()
    page.wait_for_selector("#epub-contents", state="hidden")
    channel.wait_for(r"cover 0")
    assert page.evaluate("() => window.pageYOffset") == 900

    # Closed by its own button, the app hears of it the same
    channel.contents()
    channel.wait_for(r"cover 1")
    channel.clear_inbox()
    page.click("#epub-contents-close")
    channel.wait_for(r"cover 0")


def test_the_menu_asks_for_no_contents_before_there_is_a_book(viewer, page, port, made):
    viewer("epub.html", made("notes.epub", b"Plain words in a file named like a book."))
    page.wait_for_selector(".vw-error", timeout=15000)
    channel = port()
    channel.contents()
    page.wait_for_timeout(200)
    assert not page.is_visible("#epub-contents")


def test_a_web_link_shows_its_address_and_goes_nowhere(viewer, page, port):
    """
    Gander opens no web address, so the link says where it goes and offers to copy that.
    Nothing is asked of the network and the chapter stays where it is.
    """
    asked = []
    page.on("request", lambda r: asked.append(r.url))
    page.context.grant_permissions(["clipboard-read", "clipboard-write"])
    open_book(viewer, page)
    channel = port()
    turn_to(page, "The Lamp Room")
    here = page.url

    page.click("#book #web-link")
    page.wait_for_selector("#epub-link", state="visible")
    channel.wait_for(r"cover 1")
    assert page.text_content("#epub-link-address") == "https://example.invalid/"

    page.click("#epub-link-copy")
    page.wait_for_function("() => document.querySelector('#epub-link-copy').textContent === 'Copied'")
    assert page.evaluate("() => navigator.clipboard.readText()") == "https://example.invalid/"

    channel.clear_inbox()
    channel.close_over()
    page.wait_for_selector("#epub-link", state="hidden")
    channel.wait_for(r"cover 0")
    assert page.url == here
    assert not [u for u in asked if "example.invalid" in u]
    assert "The Lamp Room" in page.text_content("#book h1")


# Copying as WebView 66 allows it: the older way only while the tap is still being handled,
# and the newer way refused a moment after it was asked. This browser allows both at any
# time, so each is put in the page's way where the page would find it.
OLDER_COPY_ONLY_IN_THE_TAP = """
(function () {
  var real = Document.prototype.execCommand, inTap = false;
  document.addEventListener('click', function () {
    inTap = true;
    setTimeout(function () { inTap = false; }, 0);
  }, true);
  Document.prototype.execCommand = function () {
    window.__olderCopy = (window.__olderCopy || []).concat(inTap);
    return inTap && real.apply(this, arguments);
  };
})();
"""
OLDER_COPY_REFUSED = """
Document.prototype.execCommand = function () {
  window.__olderCopy = (window.__olderCopy || []).concat(false);
  return false;
};
"""
NEWER_COPY_REFUSED = """
Object.defineProperty(Clipboard.prototype, 'writeText', { value: function () {
  window.__refusedCopy = (window.__refusedCopy || 0) + 1;
  return new Promise(function (yes, no) { setTimeout(function () { no(new Error('not allowed')); }, 0); });
} });
"""


def copy_the_lamp_rooms_link(viewer, page):
    open_book(viewer, page)
    turn_to(page, "The Lamp Room")
    page.click("#book #web-link")
    page.click("#epub-link-copy")


def test_an_address_is_copied_while_the_tap_is_still_being_handled(viewer, page):
    """
    On WebView 66 the newer way is refused, and by the time it has said so the older way is
    refused as well, so Copy address did nothing there. The older way is asked first.
    """
    page.context.grant_permissions(["clipboard-read"])
    page.add_init_script(OLDER_COPY_ONLY_IN_THE_TAP + NEWER_COPY_REFUSED)
    copy_the_lamp_rooms_link(viewer, page)
    page.wait_for_function("() => document.querySelector('#epub-link-copy').textContent === 'Copied'")
    assert page.evaluate("() => window.__olderCopy") == [True]
    assert page.evaluate("() => navigator.clipboard.readText()") == "https://example.invalid/"
    # Nothing is left selected on the page once it is copied
    assert page.evaluate("() => String(window.getSelection())") == ""


def test_an_address_is_copied_the_newer_way_where_the_older_is_refused(viewer, page):
    page.context.grant_permissions(["clipboard-read", "clipboard-write"])
    page.add_init_script(OLDER_COPY_REFUSED)
    copy_the_lamp_rooms_link(viewer, page)
    page.wait_for_function("() => document.querySelector('#epub-link-copy').textContent === 'Copied'")
    assert page.evaluate("() => window.__olderCopy") == [False]
    assert page.evaluate("() => navigator.clipboard.readText()") == "https://example.invalid/"
    assert page.evaluate("() => String(window.getSelection())") == ""


def test_an_address_no_way_will_copy_is_left_selected(viewer, page):
    """The reader's own Copy, from a long press on what is selected, still has it."""
    page.add_init_script(OLDER_COPY_REFUSED + NEWER_COPY_REFUSED)
    copy_the_lamp_rooms_link(viewer, page)
    page.wait_for_function("() => window.__refusedCopy === 1")
    page.wait_for_timeout(100)
    assert page.text_content("#epub-link-copy") == "Copy address"
    assert page.evaluate("() => String(window.getSelection())") == "https://example.invalid/"


@pytest.mark.parametrize("href,shown", [
    ("https://example.invalid/a?b=c#d", "https://example.invalid/a?b=c#d"),
    ("mailto:keeper@example.invalid", "mailto:keeper@example.invalid"),
    ("tel:+15550100", "tel:+15550100"),
    ("//example.invalid/page", "https://example.invalid/page"),
    ("ftp://example.invalid/file", None),
    ("intent://example.invalid#Intent;end", None),
    ("missing.xhtml", None),
], ids=["web", "mail", "telephone", "no scheme", "ftp", "intent", "a file the book has not got"])
def test_which_links_out_of_the_book_show_an_address(viewer, page, made, href, shown):
    book = little_book(made, "links.epub", [(
        "", "", f'<body><h1>Links</h1><p><a id="out" href="{href}">A link</a></p></body>',
    )])
    open_book(viewer, page, fixture=book)
    page.click("#book #out")
    page.wait_for_timeout(200)
    if shown is None:
        assert not page.is_visible("#epub-link")
    else:
        assert page.is_visible("#epub-link")
        assert page.text_content("#epub-link-address") == shown
        # A tap on the book behind the box puts it away
        page.mouse.click(20, 20)
        assert not page.is_visible("#epub-link")
    assert "Links" in page.text_content("#book h1")


def test_a_link_goes_to_its_place_in_another_chapter(viewer, page):
    open_book(viewer, page)
    page.set_viewport_size({"width": 390, "height": 160})
    turn_to(page, "The Lamp Room")
    page.click("#book #to-tide")
    wait_for_heading(page, "The Tide Table")
    wait_until_on_screen(page, "#tide-table")


# ---------------------------------------------------------------------------
# How far through the book the reader is, and the place it reopens at
# ---------------------------------------------------------------------------

def test_the_page_is_reported_and_counts_the_whole_book(viewer, page, port):
    """The app shows this as a percentage: see ViewerActivity.setPageIndicatorText."""
    open_book(viewer, page)
    channel = port()
    channel.wait_for(rf"page 1 {TOTAL}")

    # Chapter two starts on page 3 and runs to page 8
    turn_to(page, "The Long")
    channel.wait_for(rf"page 3 {TOTAL}")
    page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    channel.wait_for(rf"page 8 {TOTAL}")


def first_line(page, top=0):
    """
    The first line of the chapter wholly below [top], found the slow way, by asking every
    letter where it is: how far down the screen it sits, the words it starts with, and how
    many letters of the chapter's text come before it. The book says the same things over
    in many places, so it is that count which says which line this is.
    """
    return page.evaluate(
        r"""(top) => {
          const body = document.querySelector('#book').shadowRoot.querySelector('body');
          const walk = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
          let before = 0;
          for (let n = walk.nextNode(); n; n = walk.nextNode()) {
            for (let k = 0; k < n.data.length; k++) {
              if (/\s/.test(n.data[k])) continue;
              const range = document.createRange();
              range.setStart(n, k);
              range.setEnd(n, k + 1);
              const box = range.getBoundingClientRect();
              if (box.height > 0 && box.top >= top - 1) {
                return { top: box.top, words: n.data.slice(k, k + 24), letter: before + k };
              }
            }
            before += n.data.length;
          }
          return null;
        }""",
        top,
    )


def top_of_letter(page, letter):
    """How far down the screen the line is that has the chapter's letter number [letter]."""
    return page.evaluate(
        """(letter) => {
          const body = document.querySelector('#book').shadowRoot.querySelector('body');
          const walk = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
          let before = 0;
          for (let n = walk.nextNode(); n; n = walk.nextNode()) {
            if (letter < before + n.data.length) {
              const range = document.createRange();
              range.setStart(n, letter - before);
              range.setEnd(n, letter - before + 1);
              return range.getBoundingClientRect().top;
            }
            before += n.data.length;
          }
          return null;
        }""",
        letter,
    )


def rest_on_a_line(page, channel, down, top=0):
    """
    Scrolls [down] the chapter and then on to where the second line of a paragraph starts
    exactly at [top], so the place is inside a text and not at the start of one. Answers
    that line with the place the page told the app once it had come to rest.
    """
    page.evaluate("(y) => window.scrollTo(0, y)", down)
    by = page.evaluate(
        """(top) => {
          const body = document.querySelector('#book').shadowRoot.querySelector('body');
          const walk = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
          for (let n = walk.nextNode(); n; n = walk.nextNode()) {
            const range = document.createRange();
            range.selectNodeContents(n);
            const lines = range.getClientRects();
            if (lines.length < 2 || lines[1].top < top) continue;
            if (lines[1].top > lines[0].top + 1) return lines[1].top - top;
          }
          return null;
        }""",
        top,
    )
    assert by is not None, "no paragraph of two lines below the top of the screen"
    channel.clear_inbox()
    page.evaluate("(by) => window.scrollBy(0, by)", by)
    told = channel.wait_for(r"at \d+ \d+")
    return first_line(page, top), told.split(" ")[1] + "." + told.split(" ")[2]


def test_the_place_is_told_once_the_page_comes_to_rest(viewer, page, port):
    open_book(viewer, page)
    channel = port()
    # At the top of the first chapter nothing is above the screen
    channel.wait_for(r"at 0 0")

    turn_to(page, "The Long")
    channel.wait_for(r"at 2 0")
    line, at = rest_on_a_line(page, channel, 1200)
    chapter, past = at.split(".")
    assert chapter == "2"
    # Some of the chapter's eleven thousand letters are above the screen, and not all of them
    assert 500 < int(past) < 10000

    # Further down the chapter is further into it
    _, later = rest_on_a_line(page, channel, 2400)
    assert int(later.split(".")[1]) > int(past)


@pytest.mark.parametrize("width", [980, 412], ids=["as it was left", "on a narrower screen"])
def test_a_book_reopens_at_the_line_it_was_left_on(viewer, page, port, width):
    """
    The same line at the top of the screen, and not the start of the stretch of the book it
    was in, which was up to a screenful above it. On a screen of another width the chapter
    is laid out afresh and the line is somewhere else down it, and still the one on top.
    """
    open_book(viewer, page)
    channel = port()
    turn_to(page, "The Long")
    channel.wait_for(r"at 2 0")
    line, at = rest_on_a_line(page, channel, 1234)
    assert page.evaluate("() => window.pageYOffset") > 1000

    page.set_viewport_size({"width": width, "height": 1600})
    open_book(viewer, page, at=at)
    wait_for_heading(page, "The Long")
    page.wait_for_function("() => window.pageYOffset > 0")
    # The letter that began the line is on the top one, wherever in it that now falls
    assert abs(top_of_letter(page, line["letter"])) <= 1
    assert abs(first_line(page)["top"]) <= 1


def test_a_chapter_turned_to_is_the_place_before_its_page_is_told(viewer, page, port):
    """
    The app keeps the last page and the last place it heard of when the reader leaves. Told
    the new chapter's page first, it would file that with the old chapter's place for as long
    as the new one takes to dress, and a book left then would reopen in the chapter before.
    """
    open_book(viewer, page)
    channel = port()
    channel.wait_for(r"at 0 0")
    turn_to(page, "The Long")
    channel.wait_for(r"at 2 0")
    page.evaluate("() => window.scrollTo(0, 1500)")
    channel.wait_for(r"at 2 [1-9]\d*")

    channel.clear_inbox()
    page.click("#epub-next")
    wait_for_heading(page, "The Tide Table")
    channel.wait_for(rf"page {TOTAL} {TOTAL}")
    said = channel.messages()
    assert said.index("at 3 0") < said.index(f"page {TOTAL} {TOTAL}")


def test_a_line_more_than_half_gone_is_not_the_place(viewer, page, port):
    """What reopens on top is a line the reader could still read when they left."""
    open_book(viewer, page)
    channel = port()
    turn_to(page, "The Long")
    channel.wait_for(r"at 2 0")
    line, at = rest_on_a_line(page, channel, 1234)
    tall = page.evaluate(
        """() => {
          const body = document.querySelector('#book').shadowRoot.querySelector('body');
          return parseFloat(getComputedStyle(body.querySelector('p')).lineHeight);
        }"""
    )

    # A third of it gone, and it is still the place
    channel.clear_inbox()
    page.evaluate("(by) => window.scrollBy(0, by)", tall * 0.3)
    page.wait_for_timeout(400)
    assert channel.messages() == []

    # Two thirds gone, and the place is the line under it
    page.evaluate("(by) => window.scrollBy(0, by)", tall * 0.4)
    told = channel.wait_for(r"at 2 \d+")
    assert int(told.split(" ")[2]) > int(at.split(".")[1])
    under = first_line(page)
    assert under["letter"] > line["letter"]

    open_book(viewer, page, at="2." + told.split(" ")[2])
    wait_for_heading(page, "The Long")
    page.wait_for_function("() => window.pageYOffset > 0")
    assert first_line(page)["letter"] == under["letter"]


def test_a_picture_is_a_place_as_a_letter_is(viewer, page, port, made):
    """
    A chapter of plates with no words between them, as a picture book's is. Counted in
    letters alone every plate would be the same place, and the book would reopen at the first.
    """
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (600, 500), (140, 60, 30)).save(buf, format="PNG")
    plates = "".join(
        f'<img id="plate{n}" src="plate.png" alt="" style="display: block; margin: 8px 0"/>'
        for n in range(12)
    )
    book = little_book(made, "plates.epub", [(
        "", "", f"<body><h1>Plates</h1>{plates}<p>The end.</p></body>",
    )], extra={"plate.png": buf.getvalue()})
    open_book(viewer, page, fixture=book)
    channel = port()
    channel.wait_for(r"at 0 0")
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#plate7').complete"
    )

    channel.clear_inbox()
    page.evaluate("(by) => window.scrollBy(0, by)", top_of(page, "#plate7"))
    told = channel.wait_for(r"at 0 [1-9]\d*")

    open_book(viewer, page, fixture=book, at="0." + told.split(" ")[2])
    page.wait_for_function("() => window.pageYOffset > 0")
    page.wait_for_function(
        """() => Math.abs(document.querySelector('#book').shadowRoot.querySelector('#plate7')
          .getBoundingClientRect().top) <= 1"""
    )


def test_the_line_reopens_under_the_title_bar(viewer, page, port):
    """The bar floats over the top of the page, so the top of the screen is its lower edge."""
    open_book(viewer, page, top=64)
    channel = port()
    turn_to(page, "The Long")
    channel.wait_for(r"at 2 0")
    edge = page.evaluate("() => vwTopPx")
    assert edge > 0
    line, at = rest_on_a_line(page, channel, 1234, top=edge)

    open_book(viewer, page, at=at, top=64)
    wait_for_heading(page, "The Long")
    page.wait_for_function("() => window.pageYOffset > 0")
    again = first_line(page, top=edge)
    assert again["letter"] == line["letter"]
    assert abs(again["top"] - edge) <= 1


@pytest.mark.parametrize("at,heading", [
    ("99.5", "The Harbour at Low Tide"),
    ("2.99999999", "The Long"),
    ("nowhere", "The Harbour at Low Tide"),
], ids=["no such chapter", "past the chapter's end", "not a place"])
def test_a_place_the_book_does_not_have_opens_it_at_a_top(viewer, page, at, heading):
    open_book(viewer, page, at=at)
    wait_for_heading(page, heading)
    assert page.evaluate("() => window.pageYOffset") == 0


def set_type(page, percent):
    """
    What the app's text size does to the page, as near as a browser comes to it. The app
    asks the WebView for larger type and the engine lays the page out again; here the
    chapter's own size is set, and the letter the page watches for that is set with it.
    """
    page.evaluate(
        """(by) => {
          document.querySelector('#book').shadowRoot.querySelector('.vw-page')
            .style.fontSize = (17 * by) + 'px';
          document.querySelector('#epub-gauge').style.fontSize = (100 * by) + 'px';
        }""",
        percent / 100,
    )


def test_the_line_stays_on_top_when_the_type_changes_size(viewer, page, port):
    """Larger type moves every line below the first further down the chapter."""
    page.set_viewport_size({"width": 412, "height": 915})
    open_book(viewer, page)
    channel = port()
    turn_to(page, "The Long")
    channel.wait_for(r"at 2 0")
    line, _ = rest_on_a_line(page, channel, 1500)
    before = page.evaluate("() => window.pageYOffset")

    for percent in (150, 80, 100):
        set_type(page, percent)
        page.wait_for_function(
            """(words) => {
              const body = document.querySelector('#book').shadowRoot.querySelector('body');
              return Math.abs(parseFloat(getComputedStyle(body).fontSize) - words) < 0.1;
            }""",
            arg=17 * percent / 100,
        )
        page.wait_for_timeout(250)
        # On the top line, wherever in it the letter now falls
        assert abs(top_of_letter(page, line["letter"])) <= 1, percent
        assert abs(first_line(page)["top"]) <= 1, percent
    # Larger type had put the line further down the chapter than it was
    assert abs(page.evaluate("() => window.pageYOffset") - before) <= 1


@pytest.mark.parametrize("asked", [False, True], ids=["left alone", "asked where things are"])
def test_the_line_stays_on_top_through_two_sizes_one_close_on_the_other(viewer, page, port, asked):
    """
    Two taps on the larger button close together, as a reader who knows what they want makes
    them. The engine moves the screen itself when the type changes, and the page hears of
    that move as it hears of the reader scrolling. Asked where something is before it has
    been told of the new size, which is what the phone's own tests do to it, it hears of the
    move first. It must not take either for the reader's doing and leave the line there.
    """
    page.set_viewport_size({"width": 412, "height": 915})
    open_book(viewer, page)
    channel = port()
    turn_to(page, "The Long")
    channel.wait_for(r"at 2 0")
    line, _ = rest_on_a_line(page, channel, 1500)

    for first, second in ((115, 130), (150, 100), (250, 80)):
        set_type(page, first)
        if asked:
            page.evaluate("() => document.documentElement.scrollHeight")
        page.wait_for_timeout(60)
        set_type(page, second)
        if asked:
            page.evaluate("() => document.documentElement.scrollHeight")
        page.wait_for_timeout(500)
        assert abs(top_of_letter(page, line["letter"])) <= 1, (first, second)


@pytest.mark.parametrize("change", ["type", "width"], ids=["the type changes size", "the phone turns"])
def test_the_place_is_still_told_after_the_page_is_laid_out_afresh(viewer, page, port, change):
    """
    While the page is being laid out afresh a move of the screen is its own and not the
    reader's. Once it has been, the reader scrolling is the reader scrolling again, and where
    they come to rest is told as before.
    """
    page.set_viewport_size({"width": 412, "height": 915})
    open_book(viewer, page)
    channel = port()
    turn_to(page, "The Long")
    channel.wait_for(r"at 2 0")
    _, at = rest_on_a_line(page, channel, 1500)

    if change == "type":
        set_type(page, 130)
    else:
        page.set_viewport_size({"width": 700, "height": 915})
    page.wait_for_timeout(400)

    channel.clear_inbox()
    page.mouse.wheel(0, 600)
    told = channel.wait_for(r"at 2 \d+")
    assert int(told.split(" ")[2]) > int(at.split(".")[1])


def test_a_picture_set_alone_for_large_type_goes_back_beside_the_text(viewer, page, made):
    """
    The room a picture must leave the text is counted in letters, so larger type sets alone
    a picture that had room beside it. At the old size it has that room again.
    """
    page.set_viewport_size({"width": 700, "height": 900})
    book = beside_the_text(
        made, "detail.epub", '<style>img.detail { float: right; }</style>',
        '<img id="plate" class="detail" src="plate.png" alt="A detail"/>', (500, 200),
    )
    open_book(viewer, page, fixture=book)
    floats = ("(how) => getComputedStyle(document.querySelector('#book').shadowRoot"
              ".querySelector('#plate')).cssFloat === how")
    page.wait_for_timeout(300)
    assert style_of(page, "#plate", "cssFloat") == "right"

    set_type(page, 160)
    page.wait_for_function(floats, arg="none")
    set_type(page, 100)
    page.wait_for_function(floats, arg="right")
    # With the words beside it again, and not under it
    assert box_of(page, "#words")["top"] < box_of(page, "#plate")["bottom"] - 1


def test_a_fixed_page_does_not_take_the_readers_text_size(viewer, page, port):
    """The app is told which kind of chapter is up, and sets the type back for a fixed one."""
    open_book(viewer, page, fixture=GAZETTEER)
    channel = port()
    channel.wait_for(r"reflow 1")
    channel.clear_inbox()
    go_to(page, 2)
    channel.wait_for(r"reflow 0")
    channel.clear_inbox()
    go_to(page, 0)
    channel.wait_for(r"reflow 1")


# ---------------------------------------------------------------------------
# Find in page, which has to cross chapters
# ---------------------------------------------------------------------------

def test_a_search_counts_matches_in_chapters_not_on_the_page(viewer, page, port):
    """Once in chapter one and twice in chapter three, and the page is showing the title."""
    open_book(viewer, page)
    channel = port()
    channel.query("heronsgate")
    channel.wait_for(r"1 3 1")
    # Until every chapter had been read, the count was a not-yet and never a firm nought
    assert "0 0 1" not in channel.counts()
    wait_for_heading(page, "The Lamp Room")
    page.wait_for_function("() => CSS.highlights.has('vw-find-active')")

    channel.clear_inbox()
    channel.next()
    channel.wait_for(r"2 3 1")
    wait_for_heading(page, "The Tide Table")
    page.wait_for_function("() => CSS.highlights.has('vw-find')")
    assert highlight_count(page) == 1


def test_a_search_for_words_the_book_does_not_have_finds_nothing(viewer, page, port):
    open_book(viewer, page)
    channel = port()
    channel.query("zeppelin")
    page.wait_for_timeout(600)
    assert channel.counts()[-1] == "0 0 1"


# ---------------------------------------------------------------------------
# The older kind of book, and the ones that cannot be opened
# ---------------------------------------------------------------------------

def test_an_epub_2_reads_with_its_ncx_contents(viewer, page):
    open_book(viewer, page, fixture="almanac.epub")
    assert page.text_content("#book h1") == "First Light"
    assert style_of(page, "h1", "fontVariantCaps") == "small-caps"

    page.click("#epub-contents-open")
    labels = page.eval_on_selector_all(
        "#epub-contents-list li", "els => els.map(e => e.textContent)"
    )
    assert labels == ["First Light", "Second Watch", "The watch bell"]
    page.click("#epub-contents-list button >> text=The watch bell")
    wait_for_heading(page, "Second Watch")


def test_a_book_under_drm_says_so(viewer, page):
    viewer("epub.html", "locked.epub")
    page.wait_for_selector(".vw-error", timeout=15000)
    said = status_text(page)
    assert "This book is locked" in said
    assert "DRM" in said
    assert page.is_hidden("#epub-bar")


def test_a_file_that_is_not_a_book_says_so(viewer, page, made):
    viewer("epub.html", made("notes.epub", b"Plain words in a file named like a book."))
    page.wait_for_selector(".vw-error", timeout=15000)
    assert "It is not an EPUB, or the file is damaged." in status_text(page)


# ---------------------------------------------------------------------------
# What real books do: each of these is the shape of something in a book from Project
# Gutenberg, Standard Ebooks or the EPUB 3 samples
# ---------------------------------------------------------------------------

GAZETTEER = "gazetteer.epub"


def test_margins_set_for_a_desk_do_not_squeeze_the_words(viewer, page):
    """
    The book gives its body six ems on one side and sixteen on the other, which on a phone
    is fifteen pixels for the words. The body is the width of the page, less the page's own
    margins, and the paper colour the book gives it goes out to the edges.
    """
    open_book(viewer, page, fixture=GAZETTEER)
    column = box_of(page, ".vw-page")
    assert abs(box_of(page, "body")["width"] - column["width"]) < 1
    assert abs(box_of(page, "#first")["width"] - (column["width"] - 40)) < 1
    assert style_of(page, "body", "backgroundColor") == "rgb(255, 255, 240)"


def test_a_box_wider_than_the_screen_is_held_to_the_page(viewer, page):
    """A box 1400 pixels wide, as Project Gutenberg gives its plates, and the picture in it."""
    open_book(viewer, page, fixture=GAZETTEER)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#chart').naturalWidth === 1400"
    )
    edge = box_of(page, "#first")["right"]
    page.wait_for_function(
        """(edge) => document.querySelector('#book').shadowRoot.querySelector('#plate')
          .getBoundingClientRect().right <= edge + 1""",
        arg=edge,
    )
    assert box_of(page, "#chart")["right"] <= edge + 1
    assert box_of(page, "#chart")["width"] > 300


@pytest.mark.parametrize("selector,last", [
    ("#fares-table", "td:last-child"),
    ("#log", None),
    # Set in a line of words, which cannot scroll, so the paragraph the line is in does
    ("#sum", None),
    # A formula, no part of which can scroll either
    ("#eq", None),
])
def test_what_cannot_be_narrowed_scrolls_sideways_where_it_stands(viewer, page, selector, last):
    """
    A table of fourteen columns and a line of code the book says must not wrap, both in a
    section that is itself no wider than the page, then a run of code and a formula. The
    page never moves sideways, so each scrolls by itself, where it stands and not as part of
    something larger, and its far end can be brought into view.
    """
    open_book(viewer, page, fixture=GAZETTEER)
    edge = box_of(page, "#first")["right"]
    page.wait_for_function(
        """([s, edge]) => document.querySelector('#book').shadowRoot.querySelector(s)
          .getBoundingClientRect().right <= edge + 1""",
        arg=[selector, edge],
    )
    box = box_of(page, selector)
    assert box["across"] > box["inside"] + 100
    assert style_of(page, selector, "overflowX") == "auto"
    assert style_of(page, "#tables", "overflowX") == "visible"
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
    if last:
        page.evaluate(
            "(s) => { const el = document.querySelector('#book').shadowRoot.querySelector(s);"
            " el.scrollLeft = el.scrollWidth; }",
            selector,
        )
        assert box_of(page, f"{selector} {last}")["right"] <= edge + 1


def test_what_the_book_already_dealt_with_is_left_alone(viewer, page):
    """
    A table in a box the book made scroll sideways can be reached as it is, so nothing in
    the box is touched. Nor is a note the book put far off to the right, out of sight.
    """
    open_book(viewer, page, fixture=GAZETTEER)
    edge = box_of(page, "#first")["right"]
    page.wait_for_function(
        """(edge) => document.querySelector('#book').shadowRoot.querySelector('#fares-table')
          .getBoundingClientRect().right <= edge + 1""",
        arg=edge,
    )
    assert style_of(page, "#kept", "display") == "table"
    assert page.get_attribute("#book #kept", "style") is None
    assert page.get_attribute("#book #far", "style") == "position: absolute; left: 2000px; width: 300px"


def without_layout_containment(page):
    """
    Serves the reader as WebView 66 runs it, where layout containment keeps nothing that
    runs past a box from widening the page around it: the two places the reader asks for
    containment are taken out. Gives back a list that takes how many were.
    """
    taken = []

    def serve(route):
        response = route.fetch()
        body, n = re.subn(r"contain:\s*layout;?\s*", "", response.text())
        taken.append(n)
        route.fulfill(response=response, body=body)
    page.route("**/assets/viewer/epub.js", serve)
    return taken


def test_the_page_keeps_to_the_screen_on_an_engine_that_lets_a_chapter_widen_it(viewer, page):
    """
    On WebView 66 the note this book puts 2,000 pixels to the right made the page that wide:
    it moved sideways under the finger, and the contents list, which covers the page, opened
    out of sight. There the book's box cuts off what runs past it, and the note is still
    placed against the page.
    """
    taken = without_layout_containment(page)
    open_book(viewer, page, fixture=GAZETTEER)
    edge = box_of(page, "#first")["right"]
    page.wait_for_function(
        """(edge) => document.querySelector('#book').shadowRoot.querySelector('#fares-table')
          .getBoundingClientRect().right <= edge + 1""",
        arg=edge,
    )
    assert taken == [2]
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
    assert box_of(page, "#far")["left"] == box_of(page, ".vw-page")["left"] + 2000
    page.click("#epub-contents-open")
    assert page.evaluate("document.querySelector('#epub-contents').getBoundingClientRect().width") \
        == page.evaluate("window.innerWidth")


def test_the_books_box_cuts_nothing_off_where_the_engine_keeps_a_chapter_to_its_page(viewer, page):
    """A box that cuts off is what position: sticky in a book would stick to, so only where it must."""
    open_book(viewer, page, fixture=GAZETTEER)
    assert page.evaluate("getComputedStyle(document.querySelector('#book')).overflowX") == "visible"
    assert style_of(page, ".vw-page", "position") == "static"
    # What the engine was tried with is gone from the page again
    assert page.evaluate("document.querySelectorAll('body > div[style*=\"overflow\"]').length") == 0


def test_pictures_too_wide_together_once_they_have_loaded(viewer, page, made):
    """
    Two plates the book keeps on one line. Each is held to the width of the page, so the
    pair is twice too wide, which nobody knows until they have loaded and have a width at
    all. So what is too wide is looked for again then.
    """
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (1500, 30), (30, 90, 140)).save(buf, format="PNG")
    plate = '<img src="wide.png" alt="A wide plate"/>'
    book = little_book(made, "plates.epub", [(
        "", "",
        '<body><p id="p">' + "Words enough to fill a line of the page. " * 4 + '</p>'
        f'<p id="row" style="white-space: nowrap">{plate}{plate}</p></body>',
    )], extra={"wide.png": buf.getvalue()})
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        """() => { const row = document.querySelector('#book').shadowRoot.querySelector('#row');
          return row.scrollWidth > row.clientWidth + 100 && getComputedStyle(row).overflowX === 'auto'; }"""
    )
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")


def test_what_fits_is_left_as_the_book_set_it(viewer, page):
    """Only what is too wide is touched: a table of two columns stays a table."""
    open_book(viewer, page)
    turn_to(page, "The Tide Table")
    page.wait_for_timeout(300)
    assert style_of(page, "table", "display") == "table"
    assert style_of(page, "table", "overflowX") == "visible"
    # Nor the chapter itself, which would then scroll sideways with nothing to scroll to
    assert style_of(page, "body", "overflowX") == "visible"


def beside_the_text(made, name, head, picture, size, para=""):
    """A chapter with one picture in a paragraph, set beside its words however [head] and [picture] say."""
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", size, (140, 60, 30)).save(buf, format="PNG")
    return little_book(made, name, [(
        "", head,
        f'<body><p id="p" {para}>{picture}<span id="words">To the youth</span>, the calm river seems to '
        'lead straight to the castle in the sky.</p></body>',
    )], extra={"plate.png": buf.getvalue()})


@pytest.mark.parametrize("head, picture, size", [
    ('<style>img.detail { float: right; margin: 0 0 1em 2em; }</style>',
     '<img id="plate" class="detail" src="plate.png" alt="A detail"/>', (600, 200)),
    ('<style>@media screen { img.detail { float: left; } }</style>',
     '<img id="plate" class="detail" src="plate.png" alt="A detail"/>', (600, 200)),
    ('', '<img id="plate" style="float: right" src="plate.png" alt="A detail"/>', (600, 200)),
    ('', '<img id="plate" align="right" src="plate.png" alt="A detail"/>', (600, 200)),
    ('<style>.side { float: right; width: 600px; }</style>',
     '<span id="plate" class="side"><img src="plate.png" alt="A detail"/></span>', (600, 200)),
    # Narrower, and it is the margin the book gives it that takes the room
    ('<style>img.detail { float: right; margin-left: 6em; }</style>',
     '<img id="plate" class="detail" src="plate.png" alt="A detail"/>', (500, 200)),
], ids=["rule", "media", "inline", "align", "box", "margin"])
def test_a_picture_that_leaves_the_text_no_room_beside_it_is_set_alone(viewer, page, made, head, picture, size):
    """
    A plate nearly as wide as the page, set beside the text as Cole's Voyage of Life sets its
    details: the column left down its side takes a letter to a line. It goes in the middle of
    the page instead, and the words start under it on one line.
    """
    book = beside_the_text(made, "detail.epub", head, picture, size)
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => getComputedStyle(document.querySelector('#book').shadowRoot.querySelector('#plate')).cssFloat === 'none'"
    )
    plate, words, para = box_of(page, "#plate"), box_of(page, "#words"), box_of(page, "#p")
    assert words["top"] >= plate["bottom"] - 1
    assert words["height"] < 40
    assert abs((plate["left"] - para["left"]) - (para["right"] - plate["right"])) <= 1


def test_a_picture_that_fills_a_narrow_box_is_set_alone_in_it(viewer, page, made):
    """The room is what the box it stands in has, which may be far less than the page."""
    book = beside_the_text(
        made, "boxed.epub", '<style>img.detail { float: left; }</style>',
        '<img id="plate" class="detail" src="plate.png" alt="A detail"/>', (200, 100), para='style="width: 240px"')
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => getComputedStyle(document.querySelector('#book').shadowRoot.querySelector('#plate')).cssFloat === 'none'"
    )
    assert box_of(page, "#words")["top"] >= box_of(page, "#plate")["bottom"] - 1


def test_a_heading_set_across_its_whole_box_stays_as_the_book_set_it(viewer, page, made):
    """
    Pro Git sets the heading of a note this way, so that it sits on the note's upper edge. It
    takes the whole width, so there is no text beside it to squeeze.
    """
    book = little_book(made, "note.epub", [(
        "", '<style>aside > h2 { float: left; width: 100%; margin-top: -1.25em; } aside { padding: 1em 2em; }</style>',
        '<body><aside><h2 id="plate">Note</h2><p id="p">Words enough to fill a line of the page.</p></aside></body>',
    )])
    open_book(viewer, page, fixture=book)
    page.wait_for_timeout(300)
    assert style_of(page, "#plate", "cssFloat") == "left"
    assert page.get_attribute("#book #plate", "style") is None


def test_a_picture_that_leaves_the_text_room_stays_beside_it(viewer, page, made):
    """A drop capital, as Project Gutenberg sets them: the words run down its side."""
    book = beside_the_text(
        made, "capital.epub", '<style>img.letra { float: left; margin-right: 4px; }</style>',
        '<img id="plate" class="letra" src="plate.png" alt="T"/>', (90, 90))
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#plate').naturalWidth > 0"
    )
    page.wait_for_timeout(300)
    assert style_of(page, "#plate", "cssFloat") == "left"
    assert page.get_attribute("#book #plate", "style") is None
    plate, words = box_of(page, "#plate"), box_of(page, "#words")
    assert words["left"] >= plate["right"]
    assert words["top"] < plate["bottom"]


def test_a_small_picture_in_a_narrow_box_stays_beside_its_text(viewer, page, made):
    """The box is what is narrow there, and setting the picture alone would not widen it."""
    book = beside_the_text(
        made, "narrow.epub", '<style>img.mark { float: left; margin-right: 4px; }</style>',
        '<img id="plate" class="mark" src="plate.png" alt="A mark"/>', (40, 40), para='style="width: 150px"')
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#plate').naturalWidth > 0"
    )
    page.wait_for_timeout(300)
    assert style_of(page, "#plate", "cssFloat") == "left"


def a_plate(size=(300, 200)):
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", size, (140, 60, 30)).save(buf, format="PNG")
    return buf.getvalue()


def chapter_text(page):
    return " ".join(page.evaluate(
        "document.querySelector('#book').shadowRoot.querySelector('.vw-page body').textContent"
    ).split())


def test_a_picture_in_the_reading_order_is_drawn(viewer, page, made, port):
    """
    A book of plates may list the plates themselves to be read, each with a page of words to
    show where a picture cannot be. Here it can, so the plate is what is shown, as one page
    however large its file is.
    """
    import os
    plate = a_plate() + os.urandom(9000)
    book = little_book(
        made, "plates-in-order.epub",
        [("", "", "<body><h1>Words for the plate</h1></body>"), ("", "", "<body><h1>After the plate</h1></body>")],
        listed='<item id="plate" href="plate.png" media-type="image/png" fallback="c0"/>',
        order=["plate", "c1"], extra={"plate.png": plate})
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        """() => { const im = document.querySelector('#book').shadowRoot.querySelector('img');
          return im && im.naturalWidth === 300; }"""
    )
    assert chapter_text(page) == ""
    port().wait_for(r"page 1 2")
    page.click("#epub-next")
    wait_for_heading(page, "After the plate")


@pytest.mark.parametrize("kind", ["application/json", "application/xml", "application/octet-stream"])
def test_a_file_that_is_no_page_gives_way_to_the_page_named_in_its_stead(viewer, page, made, kind):
    book = little_book(
        made, "stead.epub", [("", "", "<body><h1>The page in its stead</h1></body>")],
        listed=f'<item id="data" href="data.bin" media-type="{kind}" fallback="c0"/>',
        order=["data"], extra={"data.bin": "<novel><para>Not these words.</para></novel>"})
    open_book(viewer, page, fixture=book)
    assert chapter_text(page) == "The page in its stead"


def test_a_file_that_is_no_page_with_nothing_in_its_stead_is_not_read_as_one(viewer, page, made):
    """A disk image in the reading order, say: its bytes are not words, and the book goes on after it."""
    book = little_book(
        made, "bytes.epub", [("", "", "<body><h1>After the file</h1></body>")],
        listed='<item id="blob" href="blob.bin" media-type="application/octet-stream"/>',
        order=["blob", "c0"], extra={"blob.bin": b"\x00\x01EFI PART\xff\xfe words that are not words"})
    open_book(viewer, page, fixture=book)
    assert chapter_text(page) == "This part of the book could not be read."
    page.click("#epub-next")
    wait_for_heading(page, "After the file")


def test_a_chapter_the_package_gives_the_wrong_type_is_still_read(viewer, page, made):
    """Only a type that says a file is something else is believed: text of any kind is a chapter."""
    odd = ('<?xml version="1.0" encoding="UTF-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml">'
           '<head><title>Odd</title></head><body><h1>Called plain text</h1></body></html>')
    book = little_book(
        made, "mislabelled.epub", [("", "", "<body><h1>Unused</h1></body>")],
        listed='<item id="odd" href="odd.xhtml" media-type="text/html"/>',
        order=["odd"], extra={"odd.xhtml": odd})
    open_book(viewer, page, fixture=book)
    assert chapter_text(page) == "Called plain text"


def test_a_picture_the_engine_cannot_draw_gives_way_to_the_one_named_in_its_stead(viewer, page, made):
    book = little_book(
        made, "photoshop.epub",
        [("", "", '<body><p><img id="plate" src="plate.psd" alt="A plate"/></p></body>')],
        listed='<item id="psd" href="plate.psd" media-type="image/vnd.adobe.photoshop" fallback="png"/>'
               '<item id="png" href="plate.png" media-type="image/png"/>',
        extra={"plate.psd": b"8BPS not a picture a browser draws", "plate.png": a_plate()})
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#plate').naturalWidth === 300"
    )


def test_a_contents_entry_that_is_a_picture_goes_by_what_the_picture_is_called(viewer, page, made):
    book = little_book(
        made, "pictured-contents.epub", [("", "", "<body><h1>The Light</h1></body>")],
        contents='<li><a href="c0.xhtml">Start</a></li>'
                 '<li><a href="c0.xhtml"><img src="light.png" alt="The harbour light"/></a></li>',
        extra={"light.png": a_plate()})
    open_book(viewer, page, fixture=book)
    labels = page.eval_on_selector_all("#epub-contents-list li", "els => els.map(e => e.textContent)")
    assert labels == ["Start", "The harbour light"]


def test_properties_under_epubs_old_names_apply(viewer, page, made):
    """The first EPUB 3 books hyphenate by -epub-hyphens, which the engine knows as hyphens only."""
    book = little_book(made, "prefixed.epub", [(
        "", "<style>#p { -epub-hyphens: auto; -epub-text-align-last: center; }</style>",
        '<body><p id="p">Words enough to fill a line of the page.</p></body>',
    )])
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => getComputedStyle(document.querySelector('#book').shadowRoot.querySelector('#p')).hyphens === 'auto'"
    )
    assert style_of(page, "#p", "textAlignLast") == "center"


def test_an_id_the_document_has_a_property_by_is_kept(viewer, page):
    """
    "title" is the id of the heading, the name its stylesheet styles it by, and where the
    contents point. It is also a property of every document, for which DOMPurify drops it.
    """
    # A screen short enough that the heading has somewhere to be scrolled to
    page.set_viewport_size({"width": 412, "height": 500})
    open_book(viewer, page, fixture=GAZETTEER)
    assert style_of(page, "#title", "letterSpacing") == "3px"
    page.evaluate("document.querySelector('#epub-contents-open').click()")
    page.click("#epub-contents-list button >> text=Fares")
    wait_until_on_screen(page, "#title")


def test_a_switch_shows_its_default(viewer, page):
    open_book(viewer, page, fixture=GAZETTEER)
    said = page.text_content("#book body")
    assert "Salt and water, written out in words." in said
    assert "markup no reader here knows" not in said
    # The one written with a prefix, whose other branch would show beside its default
    assert "A second default, in words." in said
    assert "more of what no reader here knows" not in said
    # And the chapter goes on after it
    assert "The last line of the chapter." in said


def test_a_book_set_in_columns_is_laid_out_in_rows(viewer, page):
    """
    The chapter brings a stylesheet for columns that run down the page and offers one for
    rows as its alternative. The one for rows is used, the other is not, and what the
    chapter itself sets sideways is laid out in rows too.
    """
    open_book(viewer, page, fixture=GAZETTEER)
    go_to(page, 1)
    assert style_of(page, ".main", "writingMode") == "horizontal-tb"
    assert style_of(page, ".main", "letterSpacing") == "2px"
    assert style_of(page, ".main", "marginLeft") == "0px"
    assert style_of(page, ".aside", "writingMode") == "horizontal-tb"


def fixed_page_is_scaled(page):
    """The plate, 600 by 800, is as wide as the page and its caption where the book put it."""
    column = box_of(page, ".vw-page")
    by = column["width"] / 600
    sheet = box_of(page, "html")
    assert abs(sheet["width"] - column["width"]) < 1
    assert abs(sheet["height"] - 800 * by) < 1
    assert abs(column["height"] - 800 * by) <= 1
    caption = box_of(page, "#caption")
    assert abs(caption["left"] - (column["left"] + 300 * by)) < 1
    assert abs(caption["top"] - (column["top"] + 400 * by)) < 1
    # The buttons under the chapter are under it, and not beneath the page's picture
    turn = page.evaluate("document.querySelector('#epub-turn').getBoundingClientRect().top")
    assert turn >= column["bottom"] - 1


def test_a_page_of_a_fixed_size_is_scaled_to_the_screen(viewer, page):
    open_book(viewer, page, fixture=GAZETTEER)
    # The two chapters before it say they reflow, in a book whose pages are fixed
    assert "vw-flow" in page.get_attribute("#book .vw-page", "class")
    go_to(page, 2)
    assert "vw-fixed" in page.get_attribute("#book .vw-page", "class")
    fixed_page_is_scaled(page)
    assert style_of(page, "body", "backgroundColor") == "rgb(20, 40, 60)"

    # And again on a phone held upright, which the page learns of as a change of size
    page.set_viewport_size({"width": 412, "height": 915})
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('html')"
        ".getBoundingClientRect().width < 420"
    )
    fixed_page_is_scaled(page)

    # A chapter that reflows, after one that does not, has its own height back
    go_to(page, 0)
    assert "vw-flow" in page.get_attribute("#book .vw-page", "class")
    assert abs(box_of(page, ".vw-page")["height"] - box_of(page, "html")["height"]) < 1
    assert box_of(page, "#after")["bottom"] <= box_of(page, ".vw-page")["bottom"]


def test_a_fixed_page_keeps_its_colours_on_a_dark_phone(viewer, page):
    """It is a drawing more than it is text, and white words on the book's own dark blue."""
    page.emulate_media(color_scheme="dark")
    open_book(viewer, page, fixture=GAZETTEER)
    go_to(page, 2)
    assert style_of(page, "#caption", "color") == "rgb(255, 255, 255)"
    assert style_of(page, "body", "backgroundColor") == "rgb(20, 40, 60)"


def test_one_fixed_page_in_a_book_that_reflows(viewer, page, made):
    """
    The page says it is fixed in its own place in the reading order, as a painting between
    two chapters of text does. A size alone makes no chapter a fixed page: the second has
    one and the book does not call it fixed.
    """
    size = '<meta name="viewport" content="width=400,height=300"/>'
    bleed = '<div id="bleed" style="position: absolute; left: 300px; top: 100px; width: 300px; height: 60px"></div>'
    book = little_book(made, "painting.epub", [
        ('properties="rendition:layout-pre-paginated"', size, f'<body><p id="p">A painting.</p>{bleed}</body>'),
        ("", size, '<body><p id="p">Words about it.</p></body>'),
    ])
    open_book(viewer, page, fixture=book)
    assert "vw-fixed" in page.get_attribute("#book .vw-page", "class")
    column = box_of(page, ".vw-page")
    assert abs(box_of(page, "html")["height"] - 300 * column["width"] / 400) < 1

    # What the page draws past its own edge is cut off there, as at the edge of a sheet
    def under(x):
        return page.evaluate(
            "([x, y]) => { const el = document.querySelector('#book').shadowRoot.elementFromPoint(x, y);"
            " return el ? el.id : ''; }",
            [x, column["top"] + 130 * column["width"] / 400],
        )
    assert under(column["right"] - 10) == "bleed"
    assert under(column["right"] + 10) != "bleed"
    go_to(page, 1)
    assert "vw-flow" in page.get_attribute("#book .vw-page", "class")


def test_a_fixed_book_says_which_of_its_chapters_are_not(viewer, page, made):
    """
    In a book of fixed pages a chapter may say that it reflows, and does then, whatever size
    it gives. One that gives no size cannot be scaled to it and reflows too: a width alone
    is not a size, nor is one in words.
    """
    fixed = '<meta property="rendition:layout">pre-paginated</meta>'
    size = '<meta name="viewport" content="width=400,height=300"/>'
    book = little_book(made, "plates.epub", [
        ("", size, '<body><p>A plate.</p></body>'),
        ('properties="rendition:layout-reflowable"', size, '<body><p>The words between the plates.</p></body>'),
        ("", '<meta name="viewport" content="width=400"/>', '<body><p>A width and no height.</p></body>'),
        ("", '<meta name="viewport" content="width=device-width, height=device-height"/>',
         '<body><p>A size in words.</p></body>'),
    ], metadata=fixed)
    open_book(viewer, page, fixture=book)
    assert "vw-fixed" in page.get_attribute("#book .vw-page", "class")
    for chapter in (1, 2, 3):
        go_to(page, chapter)
        assert "vw-flow" in page.get_attribute("#book .vw-page", "class")


def test_a_fixed_page_with_no_colours_of_its_own_is_black_on_white(viewer, page, made):
    """
    Its sheet is white and its words black unless the book says otherwise, on a dark phone
    too: the dark ground of the page around it would leave black words unread.
    """
    page.emulate_media(color_scheme="dark")
    book = little_book(made, "plain-plate.epub", [(
        'properties="rendition:layout-pre-paginated"',
        '<meta name="viewport" content="width=400,height=300"/>',
        '<body><p id="p">Words on a plate that names no colour.</p></body>',
    )])
    open_book(viewer, page, fixture=book)
    assert style_of(page, "html", "backgroundColor") == "rgb(255, 255, 255)"
    assert style_of(page, "#p", "color") == "rgb(0, 0, 0)"


def test_a_long_contents_list_is_shown_whole(viewer, page, made):
    """An epic in 2,014 chapters lists every one, so a list of 2,100 is shown to its end."""
    entries = "".join(f'<li><a href="c0.xhtml">Canto {n}</a></li>' for n in range(1, 2101))
    book = little_book(made, "epic.epub", [("", "", "<body><p>Sing.</p></body>")], contents=entries)
    open_book(viewer, page, fixture=book)
    assert page.evaluate("document.querySelectorAll('#epub-contents-list button').length") == 2100


@pytest.mark.parametrize("direction,fixed", [
    ("rtl", False), ("ltr", False), ("default", False), (None, False), ("invalid", False), ("rtl", True),
])
def test_spine_progression_only_mirrors_the_turn_controls(viewer, page, made, direction, fixed):
    book = little_book(made, "progression.epub", [
        ('properties="rendition:layout-pre-paginated"' if fixed else "",
         '<meta name="viewport" content="width=600,height=800"/>',
         '<body><h1>الأول</h1><p dir="ltr">English 123</p></body>'),
        ("", "", "<body><h1>الثاني</h1></body>"),
    ], spine=f'page-progression-direction="{direction}"' if direction else "", html='dir="rtl" lang="ar"')
    open_book(viewer, page, fixture=book)
    prev, next = page.locator("#epub-prev"), page.locator("#epub-next")
    assert (next.bounding_box()["x"] < prev.bounding_box()["x"]) == (direction == "rtl")
    assert prev.is_disabled() and next.is_enabled()
    assert style_of(page, "body", "direction") == "rtl"
    assert style_of(page, "p", "direction") == "ltr"
    next.click()
    wait_for_heading(page, "الثاني")
    assert next.is_disabled() and prev.is_enabled()
    prev.click()
    wait_for_heading(page, "الأول")


@pytest.mark.parametrize("ncx", [False, True])
def test_navigation_labels_keep_direction_and_language(viewer, page, made, ncx):
    labels = '<li><a href="c0.xhtml">الأول (1)</a><ol><li><a href="c1.xhtml">الثاني (2)</a></li>' \
             '<li dir="ltr" lang="en"><a href="c1.xhtml">English (3)</a></li></ol></li>'
    points = '<navPoint><navLabel><text>الأول (1)</text></navLabel><content src="c0.xhtml"/>' \
             '<navPoint><navLabel><text>الثاني (2)</text></navLabel><content src="c1.xhtml"/></navPoint>' \
             '<navPoint><navLabel><text dir="ltr" xml:lang="en">English (3)</text></navLabel>' \
             '<content src="c1.xhtml"/></navPoint></navPoint>'
    book = little_book(made, "labels.epub", [("", "", "<body><p>Text</p></body>")] * 2,
        contents="" if ncx else labels, html='dir="rtl" xml:lang="ar"',
        package='dir="rtl" xml:lang="ar"',
        listed='<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>' if ncx else "",
        extra={"toc.ncx": '<ncx dir="rtl" xml:lang="ar"><navMap>' + points + '</navMap></ncx>'})
    open_book(viewer, page, fixture=book)
    for selector in ("#epub-title", "#epub-where"):
        assert page.get_attribute(selector, "dir") == "rtl"
        assert page.get_attribute(selector, "lang") == "ar"
    page.click("#epub-contents-open")
    entries = page.locator("#epub-contents-list button")
    assert entries.evaluate_all("els => els.map(e => e.dir)") == ["rtl", "rtl", "ltr"]
    assert entries.evaluate_all("els => els.map(e => e.lang)") == ["ar", "ar", "en"]
    assert entries.nth(1).evaluate("e => getComputedStyle(e).paddingRight") == "28px"
    assert entries.nth(1).evaluate("e => getComputedStyle(e).paddingLeft") == "12px"
    assert entries.nth(2).evaluate("e => getComputedStyle(e).paddingLeft") == "28px"
    entries.nth(1).click()
    page.wait_for_function("() => vwFindParts.shown() === 1")
    assert page.get_attribute("#epub-where", "dir") == "rtl"
    page.click("#epub-contents-open")
    assert entries.nth(1).get_attribute("aria-current") == "true"


def test_empty_book_title_does_not_use_the_toc_label_fallback(viewer, page, made):
    book = little_book(made, "empty-title.epub", [("", "", "<body><p>Text</p></body>")],
        title_xml='<dc:title title="Not the book title"/>',
        contents='<li><a href="c0.xhtml" title="TOC fallback"/></li>')
    open_book(viewer, page, fixture=book)
    page.click("#epub-contents-open")
    assert page.text_content("#epub-title") == ""
    assert page.text_content("#epub-contents-list button") == "TOC fallback"
    assert page.text_content("#epub-where") == "TOC fallback"


def test_navigation_without_direction_uses_automatic_bidi(viewer, page, made):
    book = little_book(made, "auto.epub", [("", "", "<body><p>Text</p></body>")],
        contents='<li><a href="c0.xhtml">الأول (1)</a></li><li><a href="c0.xhtml">English (2)</a></li>',
        spine='page-progression-direction="rtl"', html='dir="invalid"')
    open_book(viewer, page, fixture=book)
    page.click("#epub-contents-open")
    entries = page.locator("#epub-contents-list button")
    assert entries.evaluate_all("els => els.map(e => e.dir)") == ["auto", "auto"]
    assert entries.evaluate_all("els => els.map(e => getComputedStyle(e).direction)") == ["rtl", "ltr"]
    assert entries.evaluate_all("els => els.map(e => getComputedStyle(e).unicodeBidi)") == ["isolate"] * 2


def test_navigation_inherits_resolved_auto_and_can_clear_language(viewer, page, made):
    book = little_book(made, "inherited-auto.epub", [("", "", "<body><p>Text</p></body>")],
        contents='<li dir="auto" lang="ar"><span dir="ltr" lang="en">English first</span><ol>'
                 '<li><a lang="" href="c0.xhtml">العربية</a></li>'
                 '<li><a xml:lang="" href="c0.xhtml">English</a></li></ol></li>'
                 '<li dir="rtl"><span>العربية</span><ol><li dir="auto">'
                 '<a href="c0.xhtml">123</a></li></ol></li>')
    open_book(viewer, page, fixture=book)
    page.click("#epub-contents-open")
    entries = page.locator("#epub-contents-list button, #epub-contents-list span")
    assert entries.evaluate_all("els => els.map(e => e.dir)") == ["ltr", "rtl", "rtl", "rtl", "ltr"]
    assert entries.evaluate_all("els => els.map(e => e.lang)") == ["en", "", "", "", ""]


def test_navigation_metadata_is_not_markup(viewer, page, made):
    attrs = 'dir="rtl&quot; onclick=&quot;alert(1)" lang="ar&quot; onclick=&quot;alert(1)"'
    book = little_book(made, "labels-safe.epub", [("", "", "<body><p>Text</p></body>")],
        html=attrs, package=attrs,
        contents='<li><a href="c0.xhtml">&lt;img src=x onerror=alert(1)&gt;</a></li>')
    open_book(viewer, page, fixture=book)
    page.click("#epub-contents-open")
    assert page.locator("#epub-contents-list button").inner_text() == "<img src=x onerror=alert(1)>"
    assert page.get_attribute("#epub-where", "dir") == "auto"
    assert page.locator("#epub-contents img, #epub-contents [onclick], #epub-where[onclick]").count() == 0


def test_rtl_progress_and_exact_resume(viewer, page, port, made):
    body = '<body><h1>الأول</h1>' + ('<p>' + 'كلمة ' * 100 + '</p>') * 50 + '</body>'
    book = little_book(made, "resume-rtl.epub", [("", "", body),
        ("", "", "<body><p>النهاية</p></body>")], html='dir="rtl" lang="ar"',
        spine='page-progression-direction="rtl"')
    open_book(viewer, page, fixture=book)
    channel = port()
    first = channel.wait_for(r"page 1 \d+")
    line, at = rest_on_a_line(page, channel, 1234)
    page.evaluate("window.scrollTo(0, document.documentElement.scrollHeight)")
    page.wait_for_timeout(400)
    assert int(channel.pages()[-1].split()[1]) > int(first.split()[1])
    page.set_viewport_size({"width": 412, "height": 1600})
    open_book(viewer, page, fixture=book, at=at)
    page.wait_for_function("() => window.pageYOffset > 0")
    assert abs(top_of_letter(page, line["letter"])) <= 1


def test_a_table_too_wide_in_a_book_read_right_to_left(viewer, page, made):
    """It runs over to the left there, and scrolls from the right."""
    cells = "".join(f"<td>{'م' * 20}</td>" for _ in range(30))
    book = little_book(made, "rtl.epub", [(
        "", "<style>td { white-space: nowrap; padding: 0 12px; }</style>",
        f'<body dir="rtl"><p id="p">{"كلمة " * 40}</p><table id="wide"><tr>{cells}</tr></table></body>',
    )])
    open_book(viewer, page, fixture=book)
    edge = box_of(page, "#p")["left"]
    page.wait_for_function(
        """(edge) => document.querySelector('#book').shadowRoot.querySelector('#wide')
          .getBoundingClientRect().left >= edge - 1""",
        arg=edge,
    )
    box = box_of(page, "#wide")
    assert box["across"] > box["inside"] + 100


# ---------------------------------------------------------------------------
# A dark phone
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("scheme,dark", [("light", False), ("dark", True)])
def test_the_books_own_colours_give_way_on_a_dark_phone(viewer, page, scheme, dark):
    """
    The book sets its headings in a dark red, which is unreadable on a dark ground. By day
    it is the book's; on a dark phone the ink is the page's, and the picture is untouched.
    """
    page.emulate_media(color_scheme=scheme)
    open_book(viewer, page)
    colour = style_of(page, "h1", "color")
    assert (colour == "rgb(231, 226, 217)") is dark
    assert (colour == "rgb(120, 40, 20)") is not dark
    assert page.get_attribute("#book #mark", "src").startswith("blob:")


def test_ink_a_book_insists_on_still_gives_way_on_a_dark_phone(viewer, page, made):
    """
    A converted book often marks its colours important, black on white. The page's own rules
    come after the book's and are no less particular, so on a dark phone they still win.
    """
    page.emulate_media(color_scheme="dark")
    book = little_book(made, "insistent.epub", [(
        "",
        "<style>p.calibre1 { color: rgb(0, 0, 0) !important; background-color: rgb(255, 255, 255) !important; }"
        "</style>",
        '<body><p class="calibre1">Black on white, and marked important.</p></body>',
    )])
    open_book(viewer, page, fixture=book)
    assert style_of(page, "p", "color") == "rgb(231, 226, 217)"
    assert style_of(page, "p", "backgroundColor") == "rgba(0, 0, 0, 0)"


# ---------------------------------------------------------------------------
# What a dark page would swallow: pictures in dark ink on a clear ground, and black rules
# ---------------------------------------------------------------------------

def png(width, height, pixel):
    """A PNG of [width] by [height], [pixel](x, y) answering each dot's (r, g, b, a)."""
    import zlib

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    rows = b"".join(
        b"\x00" + bytes(c for x in range(width) for c in pixel(x, y)) for y in range(height)
    )
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )


CLEAR = (0, 0, 0, 0)
BLACK = (0, 0, 0, 255)
OXBLOOD = (110, 20, 20, 255)
CHALK = (235, 235, 235, 255)
WHITE = (255, 255, 255, 255)
PAGE_INK = "rgb(231, 226, 217)"


def a_drawing(ink, ground=CLEAR):
    """A cross in [ink] on [ground], four fifths of it ground."""
    return png(40, 40, lambda x, y: ink if 18 <= x < 22 or 18 <= y < 22 else ground)


def picture_book(made, name, picture, head=""):
    return little_book(
        made, name, [("", head, '<body><p>Before.</p><p><img id="mark" src="mark.png" alt=""/></p></body>')],
        extra={"mark.png": picture}, listed='<item id="mark" href="mark.png" media-type="image/png"/>',
    )


def ground_of(page):
    """What the page has told the picture to be, once it has had the time to weigh it."""
    page.wait_for_function(
        """() => {
          const img = document.querySelector('#book').shadowRoot.querySelector('#mark');
          return img && img.complete && img.naturalWidth > 0;
        }"""
    )
    page.wait_for_timeout(400)
    return page.get_attribute("#book #mark", "data-vw-ground")


@pytest.mark.parametrize("ink,told", [(BLACK, "ink"), (OXBLOOD, "paper"), (CHALK, None)],
                         ids=["black lines", "dark colours", "light lines"])
def test_a_picture_in_dark_ink_on_a_clear_ground_is_not_lost_on_a_dark_phone(viewer, page, made, ink, told):
    """
    Black lines are drawn in the page's light ink, as the words are. Dark colours would come
    out as a negative that way, so they are given paper to stand on. Light lines show as
    they are.
    """
    page.emulate_media(color_scheme="dark")
    open_book(viewer, page, fixture=picture_book(made, "inked.epub", a_drawing(ink)))
    assert ground_of(page) == told
    assert style_of(page, "#mark", "filter") == ("invert(0.9)" if told == "ink" else "none")
    assert style_of(page, "#mark", "backgroundColor") == (PAGE_INK if told == "paper" else "rgba(0, 0, 0, 0)")


@pytest.mark.parametrize("picture,head,ground,filtered", [
    (a_drawing(BLACK, WHITE), "", "rgba(0, 0, 0, 0)", "none"),
    (a_drawing(WHITE, BLACK), "", "rgba(0, 0, 0, 0)", "none"),
    (a_drawing(BLACK), "<style>img { background-color: rgb(250, 250, 250); } p { background-color: rgb(250, 250, 250); }</style>",
     "rgb(250, 250, 250)", "none"),
    (a_drawing(BLACK), "<style>@media (prefers-color-scheme: dark) { img { filter: invert(100%); } }</style>",
     "rgba(0, 0, 0, 0)", "invert(1)"),
], ids=["drawn on white", "dark all over", "given a ground by its book", "turned over by its book"])
def test_a_picture_that_shows_on_a_dark_phone_is_left_as_it_is(viewer, page, made, picture, head, ground, filtered):
    """
    One with a ground of its own needs nothing, a photograph taken at night least of all.
    One its book has seen to for a dark page keeps what the book gave it: the ground used to
    be taken off with every other colour.
    """
    page.emulate_media(color_scheme="dark")
    open_book(viewer, page, fixture=picture_book(made, "seen-to.epub", picture, head))
    assert ground_of(page) is None
    assert style_of(page, "#mark", "backgroundColor") == ground
    assert style_of(page, "#mark", "filter") == filtered
    # The words beside it still stand on the page's own ground
    assert style_of(page, "p", "backgroundColor") == "rgba(0, 0, 0, 0)"


def test_no_picture_is_changed_on_a_light_phone(viewer, page, made):
    page.emulate_media(color_scheme="light")
    open_book(viewer, page, fixture=picture_book(made, "by-day.epub", a_drawing(BLACK)))
    assert ground_of(page) is None
    assert style_of(page, "#mark", "filter") == "none"


@pytest.mark.parametrize("scheme", ["light", "dark"])
def test_a_rule_drawn_in_black_takes_the_pages_ink_on_a_dark_phone(viewer, page, made, scheme):
    """
    A table's lines, the line under a heading and a rule an element gives itself. A grey one
    shows on a dark page and stays the book's, and so does one left clear to hold a space open.
    """
    page.emulate_media(color_scheme=scheme)
    book = little_book(made, "ruled.epub", [(
        "",
        "<style>td { border: 1px solid black; } .grey { border: 1px solid rgb(128, 128, 128); }"
        " .clear { border: 2px solid transparent; }"
        " @media screen { h2 { border-bottom: 1px solid #111; } }</style>",
        '<body><h2 id="head">Tides</h2><table><tr><td id="cell">High</td></tr></table>'
        '<p id="grey" class="grey">Grey</p><p id="clear" class="clear">Clear</p>'
        '<p id="own" style="border-left: 3px solid #000000">Its own</p></body>',
    )])
    open_book(viewer, page, fixture=book)
    dark = scheme == "dark"
    assert style_of(page, "#cell", "borderTopColor") == (PAGE_INK if dark else "rgb(0, 0, 0)")
    assert style_of(page, "#head", "borderBottomColor") == (PAGE_INK if dark else "rgb(17, 17, 17)")
    assert style_of(page, "#own", "borderLeftColor") == (PAGE_INK if dark else "rgb(0, 0, 0)")
    assert style_of(page, "#grey", "borderTopColor") == "rgb(128, 128, 128)"
    assert style_of(page, "#clear", "borderTopColor") == "rgba(0, 0, 0, 0)"


# ---------------------------------------------------------------------------
# The page's own parts: their size, the contents' heading, and the strip at a chapter's foot
# ---------------------------------------------------------------------------

OWN_WORDS = [
    ("#epub-contents-open", 15), ("#epub-where", 13), ("#epub-next", 15), ("#epub-contents-title", 17),
    ("#epub-contents-close", 15), ("#epub-title", 13), ("#epub-contents-list button", 15),
    ("#epub-link-title", 17), ("#epub-link-address", 15), (".epub-link-note", 13),
]


def own_sizes(page):
    return page.evaluate(
        "(all) => all.map(s => parseFloat(getComputedStyle(document.querySelector(s)).fontSize))",
        [s for s, _ in OWN_WORDS],
    )


@pytest.mark.parametrize("phone,chosen", [(100, 250), (130, 200), (100, 80), (115, 100)])
def test_the_pages_own_words_stay_the_size_the_phone_asks_for(viewer, page, phone, chosen):
    """
    The reader's text size is the WebView's zoom and reaches every letter on the page. The
    page divides its own sizes by the reader's share of that zoom, so the zoom brings them
    back to what the phone alone asks for: at 250% a size of 15 is written as 6.
    """
    open_book(viewer, page, type=phone)
    set_type(page, phone * chosen / 100)
    page.wait_for_function(
        """(own) => Math.abs(parseFloat(getComputedStyle(document.documentElement)
          .getPropertyValue('--epub-own')) - own) < 0.001""",
        arg=100 / chosen,
    )
    for (selector, px), size in zip(OWN_WORDS, own_sizes(page)):
        assert abs(size - px * 100 / chosen) < 0.05, selector


def test_a_page_not_told_the_phones_size_leaves_its_own_words_alone(viewer, page):
    open_book(viewer, page)
    set_type(page, 250)
    page.wait_for_timeout(250)
    assert own_sizes(page) == [px for _, px in OWN_WORDS]


def test_the_contents_heading_stays_in_sight_where_the_list_opens_deep_in_a_book(viewer, page, made, port):
    """The list opens at the chapter being read, and its heading and Close used to scroll off with the top of it."""
    entries = "".join(f'<li><a href="c{n}.xhtml">Chapter {n + 1}</a></li>' for n in range(60))
    chapters = [("", "", f"<body><h1>Chapter {n + 1}</h1><p>Words.</p></body>") for n in range(60)]
    page.set_viewport_size({"width": 390, "height": 600})
    open_book(viewer, page, fixture=little_book(made, "sixty.epub", chapters, contents=entries), top=56)
    go_to(page, 50)
    port().contents()
    page.wait_for_selector("#epub-contents", state="visible")
    seen = page.evaluate(
        """() => {
          const box = (s) => { const r = document.querySelector(s).getBoundingClientRect(); return [r.top, r.bottom]; };
          return { bar: vwTopPx, down: document.querySelector('#epub-contents').scrollTop,
                   close: box('#epub-contents-close'), now: box('#epub-contents-list button[aria-current]') };
        }"""
    )
    assert seen["bar"] > 0 and seen["down"] > 1000
    # Under the title bar, and the chapter being read below it and not behind it
    assert seen["bar"] - 1 <= seen["close"][0] and seen["close"][1] <= seen["bar"] + 60
    assert seen["close"][1] <= seen["now"][0] and seen["now"][1] <= 600


def test_next_and_previous_take_a_tap_anywhere_on_their_half_of_the_strip(viewer, page):
    open_book(viewer, page)

    def tap(across):
        page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
        strip = page.evaluate(
            "(() => { const r = document.querySelector('#epub-turn').getBoundingClientRect();"
            " return [r.left, r.width, r.top + 24]; })()"
        )
        page.mouse.click(strip[0] + strip[1] * across, strip[2])

    tap(0.55)
    page.wait_for_function("() => vwFindParts.shown() === 1")
    tap(0.45)
    page.wait_for_function("() => vwFindParts.shown() === 0")


# ---------------------------------------------------------------------------
# Back, after a link to another place in the book
# ---------------------------------------------------------------------------

FILLER = "<p>" + "Words enough to fill a line of the page and run on to the next one. " * 2 + "</p>"


def noted_book(made):
    """A chapter with a note far below the line that points to it, and the note's own link back."""
    return little_book(made, "noted.epub", [(
        "", "",
        "<body><h1>Noted</h1>" + FILLER * 12
        + '<p>The line with the note<a id="ref" href="#note">1</a>.</p>' + FILLER * 60
        + '<p id="note">The note. <a id="return" href="#ref">Back to the line</a></p>' + FILLER * 60
        + "</body>",
    ), ("", "", "<body><h1>After</h1><p>The next chapter.</p></body>")])


def follow_the_note(viewer, page, made, port):
    page.set_viewport_size({"width": 390, "height": 400})
    open_book(viewer, page, fixture=noted_book(made))
    channel = port()
    page.evaluate(
        "() => document.querySelector('#book').shadowRoot.querySelector('#ref').scrollIntoView({ block: 'center' })"
    )
    page.wait_for_timeout(300)
    was = page.evaluate("() => window.pageYOffset")
    page.click("#book #ref")
    wait_until_on_screen(page, "#note")
    channel.wait_for(r"cover 1")
    channel.clear_inbox()
    return channel, was


def test_back_returns_to_where_a_link_was_followed_from(viewer, page, made, port):
    """A note is read and come back from, and Back is how: the page keeps the place and tells the app it has one."""
    channel, was = follow_the_note(viewer, page, made, port)
    assert page.evaluate("() => window.pageYOffset") > was + 1000
    channel.close_over()
    # To the line that was at the top of the screen, which may have been part way off it
    page.wait_for_function("(was) => Math.abs(window.pageYOffset - was) < 30", arg=was)
    # Once, and then Back is for leaving: the app is told of nothing more to do here
    page.wait_for_timeout(300)
    assert "cover 1" not in channel.messages()
    channel.close_over()
    page.wait_for_timeout(200)
    assert abs(page.evaluate("() => window.pageYOffset") - was) < 30


def test_back_returns_across_chapters(viewer, page, port):
    page.set_viewport_size({"width": 390, "height": 160})
    open_book(viewer, page)
    channel = port()
    turn_to(page, "The Lamp Room")
    page.click("#book #to-tide")
    wait_for_heading(page, "The Tide Table")
    channel.wait_for(r"cover 1")
    channel.close_over()
    wait_for_heading(page, "The Lamp Room")
    # With the link it was followed from on the screen again
    link = top_of(page, "#to-tide")
    assert 0 <= link < 160


def test_a_notes_own_link_back_leaves_nothing_for_back_to_do(viewer, page, made, port):
    channel, was = follow_the_note(viewer, page, made, port)
    page.click("#book #return")
    wait_until_on_screen(page, "#ref")
    channel.wait_for(r"cover 0")
    at = page.evaluate("() => window.pageYOffset")
    channel.close_over()
    page.wait_for_timeout(300)
    assert page.evaluate("() => window.pageYOffset") == at


def test_a_reader_who_reads_on_from_a_link_leaves_by_back(viewer, page, made, port):
    """
    A link in a book's own list of chapters is followed to read on from, not to come back
    from. Two screens on from where it put them, Back is theirs to leave by.
    """
    channel, _ = follow_the_note(viewer, page, made, port)
    page.evaluate("() => { window.dispatchEvent(new Event('touchstart')); window.scrollBy(0, 600); }")
    page.wait_for_timeout(500)
    assert "cover 0" not in channel.messages()
    page.evaluate("() => { window.dispatchEvent(new Event('touchstart')); window.scrollBy(0, 400); }")
    channel.wait_for(r"cover 0")


def test_a_change_of_type_size_is_not_reading_on(viewer, page, made, port):
    """Larger type puts the note many screens further down the chapter, with the reader still at it."""
    channel, _ = follow_the_note(viewer, page, made, port)
    # Once the page has come to rest at the note, which is when it knows the line it is on
    page.wait_for_timeout(300)
    before = page.evaluate("() => window.pageYOffset")
    set_type(page, 200)
    page.wait_for_function("(before) => window.pageYOffset > before + 1000", arg=before)
    page.wait_for_timeout(300)
    page.evaluate("() => { window.dispatchEvent(new Event('touchstart')); window.scrollBy(0, 300); }")
    page.wait_for_timeout(500)
    assert "cover 0" not in channel.messages()


def test_a_picture_that_loads_late_does_not_count_as_reading_on(viewer, page, made, port):
    """
    A link's place moves down the chapter as the pictures above it take their sizes, and the
    page follows it there. Where the link left the reader is where that ends, not where it began.
    """
    page.set_viewport_size({"width": 390, "height": 400})
    book = little_book(made, "late.epub", [
        ("", "", '<body><h1>From</h1><p><a id="over" href="c1.xhtml#there">Over there</a></p></body>'),
        ("", "", '<body><h1>To</h1><p><img id="tall" src="tall.png" alt=""/></p><p id="there">There.</p>'
         + FILLER * 40 + "</body>"),
    ], extra={"tall.png": png(8, 2400, lambda x, y: WHITE)},
        listed='<item id="tall" href="tall.png" media-type="image/png"/>')
    open_book(viewer, page, fixture=book)
    channel = port()
    page.click("#book #over")
    wait_for_heading(page, "To")
    page.wait_for_function("() => window.pageYOffset > 2000")
    channel.wait_for(r"cover 1")
    page.wait_for_timeout(400)
    channel.clear_inbox()
    page.evaluate("() => { window.dispatchEvent(new Event('touchstart')); window.scrollBy(0, 200); }")
    page.wait_for_timeout(500)
    assert "cover 0" not in channel.messages()


@pytest.mark.parametrize("how", ["next", "contents"])
def test_going_on_with_the_book_leaves_back_for_leaving(viewer, page, made, port, how):
    channel, _ = follow_the_note(viewer, page, made, port)
    if how == "next":
        page.evaluate("() => document.querySelector('#epub-next').click()")
    else:
        page.evaluate("() => document.querySelector('#epub-contents-open').click()")
        page.evaluate("() => document.querySelector('#epub-contents-list button').click()")
    channel.wait_for(r"cover 0")


def test_back_closes_the_contents_before_it_goes_back(viewer, page, made, port):
    """The app stops listening for Back each time it passes one on, so it is told again while there is more to do."""
    channel, was = follow_the_note(viewer, page, made, port)
    channel.contents()
    page.wait_for_selector("#epub-contents", state="visible")
    channel.close_over()
    page.wait_for_selector("#epub-contents", state="hidden")
    channel.wait_for(r"cover 1")
    assert page.evaluate("() => window.pageYOffset") > was + 1000
    channel.close_over()
    page.wait_for_function("(was) => Math.abs(window.pageYOffset - was) < 30", arg=was)


# ---------------------------------------------------------------------------
# A search begun part way through a book
# ---------------------------------------------------------------------------

def searched_book(made):
    return little_book(made, "searched.epub", [
        ("", "", "<body><h1>One</h1><p>The first needle.</p></body>"),
        ("", "", "<body><h1>Two</h1><p>The second needle.</p>" + FILLER * 60
         + '<p id="late">The third needle.</p>' + FILLER * 60 + '<p id="foot">The foot.</p></body>'),
        ("", "", '<body><h1>Three</h1><p>The fourth needle. <a id="up" href="c0.xhtml">To the start</a></p>'
         + FILLER * 60 + '<p id="end">The end.</p></body>'),
    ])


@pytest.mark.parametrize("chapter,below,found", [(0, None, 1), (1, None, 2), (1, "#late", 3), (1, "#foot", 4), (2, "#end", 1)],
                         ids=["at the start", "a chapter in", "past a match", "past the last in the chapter", "past them all"])
def test_a_search_starts_from_where_the_reader_is(viewer, page, made, port, chapter, below, found):
    """
    The first match at or below the top of the screen, or in a chapter after this one, and
    the book's first only when there is none: a search used to take a reader deep in a book
    back to its first page. The chapters ahead are not all in memory when the search is
    asked for, and it waits for them before it chooses.
    """
    page.set_viewport_size({"width": 390, "height": 400})
    open_book(viewer, page, fixture=searched_book(made))
    channel = port()
    go_to(page, chapter)
    if below:
        page.evaluate(
            "(s) => document.querySelector('#book').shadowRoot.querySelector(s).scrollIntoView({ block: 'start' })",
            below,
        )
        page.wait_for_timeout(250)
    channel.query("needle")
    channel.wait_for(rf"{found} 4 1")
    # It went nowhere else on the way there
    assert {count.split()[0] for count in channel.counts()} <= {"0", str(found)}
    page.wait_for_function("(i) => vwFindParts.shown() === i", arg=[0, 1, 1, 2][found - 1])
    page.wait_for_function("() => CSS.highlights.has('vw-find-active')")


def test_a_later_search_starts_from_where_the_reader_is_too(viewer, page, made, port):
    """The first search of a book reads all of it, so a later one has nothing to wait for and chooses at once."""
    page.set_viewport_size({"width": 390, "height": 400})
    open_book(viewer, page, fixture=searched_book(made))
    channel = port()
    channel.query("needle")
    channel.wait_for(r"1 4 1")
    channel.clear()
    go_to(page, 2)
    channel.clear_inbox()
    channel.query("needle")
    channel.wait_for(r"4 4 1")
    assert {count.split()[0] for count in channel.counts()} == {"4"}


def test_a_search_that_turns_the_chapter_leaves_back_for_leaving(viewer, page, made, port):
    open_book(viewer, page, fixture=searched_book(made))
    channel = port()
    go_to(page, 2)
    page.click("#book #up")
    wait_for_heading(page, "One")
    channel.wait_for(r"cover 1")
    channel.clear_inbox()
    channel.query("third")
    wait_for_heading(page, "Two")
    channel.wait_for(r"cover 0")
