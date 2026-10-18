"""
text.html: anything can be asked for as text, including a multi-gigabyte
binary, so it is read a page at a time.
"""

import json

import pytest

from helpers import wait_until_done

VW_TEXT_PAGE = 5 * 1024 * 1024
VW_JSON_MAX = 1024 * 1024


def content(page):
    return page.text_content("#content") or ""


def wait_for_text(page, timeout=20000):
    page.wait_for_function(
        "() => document.querySelector('#content').textContent.length > 0", timeout=timeout
    )


def test_a_text_file_is_shown(viewer, page):
    viewer("text.html", "plain.txt")
    wait_for_text(page)
    assert "Plain text, opened by the text viewer." in content(page)


@pytest.mark.parametrize("ext", ["txt", "log", "nfo", ""])
@pytest.mark.parametrize("text,direction", [
    ("(2026) العربية من اليمين إلى اليسار.\nEnglish second.", "rtl"),
    ("English first.\nالعربية من اليمين إلى اليسار.", "ltr"),
])
def test_text_uses_the_first_strong_character_for_file_direction(viewer, page, made, port, text, direction, ext):
    viewer("text.html", made("mixed" + ("." + ext if ext else ""), text))
    wait_for_text(page)
    assert content(page) == text
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == direction
    assert page.locator("#content > *").count() == 0
    channel = port()
    channel.query("العربية")
    channel.wait_for_count(1, 1)


@pytest.mark.parametrize("ext,text", [
    ("json", '"العربية"'),
    ("js", '"العربية"'),
    ("yaml", "العربية: نص"),
    ("srt", "1\n00:00:01,000 --> 00:00:03,000\nالعربية\n"),
    ("m3u", "العربية.mp3\n"),
])
def test_structured_formats_keep_ltr_even_when_the_first_word_is_arabic(viewer, page, made, ext, text):
    viewer("text.html", made("source." + ext, text))
    wait_for_text(page)
    assert content(page) == text
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == "ltr"


def test_newlines_survive(viewer, page):
    viewer("text.html", "plain.txt")
    wait_for_text(page)
    assert content(page).count("\n") >= 2


def test_an_accented_character_decodes(viewer, page):
    viewer("text.html", "plain.txt")
    wait_for_text(page)
    assert "café" in content(page)


# ---------------------------------------------------------------------------
# Text under other names, which FileKind sends here by extension
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "fixture", ["captions.srt", "captions.vtt", "playlist.m3u", "release.nfo"]
)
def test_subtitles_a_playlist_and_an_nfo_are_shown_as_written(
    viewer, page, fixture_path, fixture
):
    """
    Nothing is made of what they are: the timings, the playlist's tags and the
    notes' layout all come through exactly as the file has them.
    """
    viewer("text.html", fixture)
    wait_for_text(page)
    wait_until_done(page)
    assert content(page) == fixture_path(fixture).read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# Byte order marks
# ---------------------------------------------------------------------------

def test_utf16_little_endian_is_decoded_by_its_mark(viewer, page):
    viewer("text.html", "utf16le.txt")
    wait_for_text(page)
    assert "Byte order marked text" in content(page)


def test_utf16_big_endian_is_decoded_by_its_mark(viewer, page):
    viewer("text.html", "utf16be.txt")
    wait_for_text(page)
    assert "Byte order marked text" in content(page)


def test_the_mark_itself_is_not_shown(viewer, page):
    """The decoder strips it; a stray U+FEFF at the top would be visible."""
    viewer("text.html", "utf16le.txt")
    wait_for_text(page)
    assert "﻿" not in content(page)


def test_a_binary_read_as_text_does_not_break_the_page(viewer, page):
    """The unsupported page offers this, and warns that it may be gibberish."""
    viewer("text.html", "legacy.doc", ext="doc")
    page.wait_for_timeout(1500)
    assert page.query_selector("#content") is not None


# ---------------------------------------------------------------------------
# Paging
# ---------------------------------------------------------------------------

def test_a_small_file_offers_no_more_button(viewer, page):
    viewer("text.html", "plain.txt")
    wait_for_text(page)
    assert page.query_selector("#more").is_hidden()


def test_a_large_file_shows_the_first_page_and_offers_the_rest(viewer, page, made):
    big = made("big.txt", ("Line of text to pad the file out.\n" * 200_000))
    assert big.stat().st_size > VW_TEXT_PAGE

    viewer("text.html", big, ext="txt")
    wait_for_text(page)

    assert page.query_selector("#more").is_visible()
    note = page.text_content("#moreNote")
    assert "MB" in note or "KB" in note
    shown = len(content(page))
    assert shown < big.stat().st_size


def test_the_note_about_the_rest_is_announced(viewer, page, made):
    big = made("big2.txt", "x" * (VW_TEXT_PAGE + 1000))
    viewer("text.html", big, ext="txt")
    wait_for_text(page)
    assert page.get_attribute("#moreNote", "aria-live") == "polite"


def test_asking_for_more_appends_the_rest(viewer, page, made):
    big = made("big3.txt", "abcdefghij" * 600_000)
    viewer("text.html", big, ext="txt")
    wait_for_text(page)
    first = len(content(page))

    page.click("#moreBtn")
    page.wait_for_function(
        f"() => document.querySelector('#content').textContent.length > {first}",
        timeout=20000,
    )
    assert len(content(page)) > first


def test_direction_updates_when_the_first_strong_character_arrives_on_a_later_page(viewer, page, made):
    text = "." * (VW_TEXT_PAGE - 1) + "ب\nالعربية في النهاية"
    viewer("text.html", made("paged-rtl.txt", text))
    wait_for_text(page)
    page.click("#moreBtn")
    page.wait_for_function("() => document.querySelector('#content').textContent.endsWith('النهاية')")
    assert content(page) == text
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == "rtl"
    assert page.locator("#content > *").count() == 0


def test_a_file_ending_exactly_on_the_boundary_offers_no_empty_page(viewer, page, made):
    """
    The reader overshoots the page by a chunk rather than stopping level with
    it, so a file that ends on the boundary is known to be finished.
    """
    exact = made("exact.txt", b"a" * VW_TEXT_PAGE)
    viewer("text.html", exact, ext="txt")
    wait_for_text(page)
    page.wait_for_timeout(600)
    assert page.query_selector("#more").is_hidden()


def test_a_character_split_across_the_boundary_still_decodes(viewer, page, made):
    """
    One streaming decoder across every page, so a multi-byte character cut in
    half by a page boundary comes out whole rather than as two replacements.
    """
    # A three-byte character straddling the 5 MiB mark
    head = b"a" * (VW_TEXT_PAGE - 1)
    straddling = "€".encode()  # e2 82 ac
    made_file = made("split.txt", head + straddling + b"b" * 100)

    viewer("text.html", made_file, ext="txt")
    wait_for_text(page)
    page.click("#moreBtn")
    page.wait_for_function(
        "() => document.querySelector('#content').textContent.indexOf('b') >= 0",
        timeout=20000,
    )

    text = content(page)
    assert "€" in text, "the split character did not survive the page boundary"
    assert "�" not in text, "the split character decoded as a replacement"


# ---------------------------------------------------------------------------
# JSON, which is very often written with no spacing at all
# ---------------------------------------------------------------------------

def test_a_json_file_is_laid_out_when_it_opens(viewer, page):
    viewer("text.html", "snapshot.json")
    wait_for_text(page)
    shown = content(page)
    assert '"app": "com.example.reader"' in shown
    assert shown.count("\n") >= 15, "the file was shown as one line"


def test_laying_it_out_does_not_change_what_it_says(viewer, page, fixture_path):
    """
    Whitespace is the only thing rewritten, so the document still reads back
    as the one on disk.
    """
    viewer("text.html", "snapshot.json")
    wait_for_text(page)
    original = fixture_path("snapshot.json").read_text(encoding="utf-8")
    assert json.loads(content(page)) == json.loads(original)


def test_a_number_a_double_cannot_hold_is_not_rounded(viewer, page):
    """
    The fixture id is 2**53 + 1. Reformatting by parsing and restringifying
    would show 9007199254740992 instead, and the file would be saying
    something it does not say.
    """
    viewer("text.html", "snapshot.json")
    wait_for_text(page)
    assert '"id": 9007199254740993' in content(page)


def test_an_emoji_in_a_string_survives_being_laid_out(viewer, page):
    """A surrogate pair, which is two units of the string being copied."""
    viewer("text.html", "snapshot.json")
    wait_for_text(page)
    assert "caf\u00e9 \U0001f600" in content(page)


def test_a_json_file_that_is_not_json_is_shown_as_it_is(viewer, page):
    """Named .json, is prose. Nothing is laid out and nothing is lost."""
    viewer("text.html", "plain.txt", ext="json")
    wait_for_text(page)
    assert "Plain text, opened by the text viewer." in content(page)
    assert "caf\u00e9" in content(page)


def test_json_in_a_file_not_named_json_is_left_alone(viewer, page, fixture_path):
    """The extension is what asks for this, so that .txt stays literal."""
    viewer("text.html", "snapshot.json", ext="txt")
    wait_for_text(page)
    assert content(page) == fixture_path("snapshot.json").read_text(encoding="utf-8")


def test_a_json_file_past_one_page_is_shown_as_it_is(viewer, page, made):
    """
    A page on its own is a fragment, and a fragment is not JSON, so a file
    too large to arrive whole keeps the spacing it came with.
    """
    one = '{"id":0,"name":"android.widget.FrameLayout","depth":0}'
    count = VW_TEXT_PAGE // len(one) + 100
    big = made("big.json", '{"nodes":[' + ",".join([one] * count) + "]}")
    assert big.stat().st_size > VW_TEXT_PAGE

    viewer("text.html", big)
    wait_for_text(page)
    assert page.query_selector("#more").is_visible()
    assert '"id": 0' not in content(page), "a fragment was laid out"


def test_a_json_file_too_large_to_be_worth_drawing_is_shown_as_it_is(viewer, page, made):
    """
    Laying out is cheap; drawing the result is not, because a minified megabyte
    becomes about a hundred thousand lines. Past VW_JSON_MAX the file opens as
    it came rather than taking three times as long to appear.
    """
    one = '{"id":0,"name":"android.widget.FrameLayout","depth":0}'
    count = VW_JSON_MAX // len(one) + 1000
    big = made("wide.json", '{"nodes":[' + ",".join([one] * count) + "]}")
    assert VW_JSON_MAX < big.stat().st_size < VW_TEXT_PAGE, "must be over the cap, under a page"

    viewer("text.html", big)
    wait_for_text(page)
    assert page.query_selector("#more").is_hidden(), "it should still arrive in one page"
    assert '"id": 0' not in content(page), "a file past the cap was laid out"


def test_a_json_file_nested_too_deep_to_lay_out_is_shown_as_it_is(viewer, page, made):
    """Rather than an error in place of it, which is what laying it out came to."""
    deep = made("deep.json", "[" * 20000 + "]" * 20000)
    viewer("text.html", deep)
    wait_for_text(page)
    assert content(page) == "[" * 20000 + "]" * 20000


def test_a_later_page_that_parses_is_not_laid_out_either(viewer, page, made):
    """
    Whether to lay the file out is settled once, when it opens. A file that is
    not JSON but whose last page happens to be would otherwise arrive half raw
    and half laid out, one appended to the other.
    """
    tail = '{"x":1}'
    odd = made("tail.json", b"a" * VW_TEXT_PAGE + tail.encode())

    viewer("text.html", odd)
    wait_for_text(page)
    page.click("#moreBtn")
    page.wait_for_function(
        "() => document.querySelector('#content').textContent.indexOf('{') >= 0",
        timeout=20000,
    )
    shown = content(page)
    assert tail in shown, "the last page is missing"
    assert '"x": 1' not in shown, "a later page was laid out"
