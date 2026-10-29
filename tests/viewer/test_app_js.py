"""
app.js, the runtime every viewer page shares.

Its functions are globals on every page, so they are called directly rather
than exercised through a document.
"""

import pytest


@pytest.fixture
def loaded(viewer, page):
    """Any page will do; text.html is the smallest that loads app.js."""
    viewer("text.html", "plain.txt")
    page.wait_for_function("() => typeof vwFormatSize === 'function'", timeout=15000)
    return page


# ---------------------------------------------------------------------------
# vwFormatSize: the "showing the first N of" notice
# ---------------------------------------------------------------------------

def test_sizes_under_a_megabyte_are_shown_in_kilobytes(loaded):
    assert loaded.evaluate("() => vwFormatSize(1023)") == "1 KB"
    assert loaded.evaluate("() => vwFormatSize(2048)") == "2 KB"
    assert loaded.evaluate("() => vwFormatSize(500 * 1024)") == "500 KB"


def test_nothing_is_ever_reported_as_zero_kilobytes(loaded):
    """A file with something in it should not be described as having nothing."""
    assert loaded.evaluate("() => vwFormatSize(1)") == "1 KB"
    assert loaded.evaluate("() => vwFormatSize(0)") == "1 KB"


def test_megabytes_keep_one_decimal_until_they_reach_ten(loaded):
    assert loaded.evaluate("() => vwFormatSize(2.56 * 1024 * 1024)") == "2.6 MB"
    assert loaded.evaluate("() => vwFormatSize(1.04 * 1024 * 1024)") == "1 MB"


def test_ten_megabytes_and_over_are_whole_numbers(loaded):
    """A tenth of a megabyte is not a distinction anybody reads."""
    assert loaded.evaluate("() => vwFormatSize(10.4 * 1024 * 1024)") == "10 MB"
    assert loaded.evaluate("() => vwFormatSize(53 * 1024 * 1024)") == "53 MB"


# ---------------------------------------------------------------------------
# vwEncodingOf: the only byte sniffing anywhere in the project
# ---------------------------------------------------------------------------

def encoding_of(page, *byte_values):
    return page.evaluate(
        "(b) => vwEncodingOf(new Uint8Array(b))", list(byte_values)
    )


def test_a_little_endian_mark_is_recognised(loaded):
    assert encoding_of(loaded, 0xFF, 0xFE, 0x41, 0x00) == "utf-16le"


def test_a_big_endian_mark_is_recognised(loaded):
    assert encoding_of(loaded, 0xFE, 0xFF, 0x00, 0x41) == "utf-16be"


def test_anything_unmarked_is_read_as_utf8(loaded):
    assert encoding_of(loaded, 0x48, 0x65, 0x6C, 0x6C, 0x6F) == "utf-8"
    assert encoding_of(loaded, 0xEF, 0xBB, 0xBF, 0x41) == "utf-8"


def test_a_file_too_short_to_carry_a_mark_is_read_as_utf8(loaded):
    assert encoding_of(loaded) == "utf-8"
    assert encoding_of(loaded, 0xFF) == "utf-8"


# ---------------------------------------------------------------------------
# vwDocUrl: where every page fetches its document from
# ---------------------------------------------------------------------------

def test_the_document_is_always_fetched_from_the_same_path(loaded):
    assert loaded.evaluate("() => vwDocUrl()") == "/doc/file.txt"


def test_a_file_with_no_extension_still_has_a_url(viewer, page):
    viewer("text.html", "plain.txt", ext="")
    page.wait_for_function("() => typeof vwDocUrl === 'function'", timeout=15000)
    assert page.evaluate("() => vwDocUrl()") == "/doc/file"


# ---------------------------------------------------------------------------
# The status card
# ---------------------------------------------------------------------------

def test_an_error_is_shown_as_a_title_and_a_detail(loaded):
    loaded.evaluate("() => vwError('Something went wrong', 'and here is why')")
    assert loaded.text_content(".vw-error-title") == "Something went wrong"
    assert loaded.text_content(".vw-error-detail") == "and here is why"


def test_error_text_is_set_as_text_rather_than_markup(loaded):
    """
    The detail is often an exception message carrying content from the
    document, which is untrusted. It is written with textContent.
    """
    loaded.evaluate("() => vwError('t', '<img src=x onerror=\"window.__xss=1\">')")
    loaded.wait_for_timeout(200)
    assert loaded.evaluate("() => window.__xss") is None
    assert loaded.query_selector(".vw-error-detail img") is None


def test_a_resize_observers_notice_of_sizes_left_for_the_next_frame_brings_no_card(loaded):
    """
    Chromium 102 sends the first as a sheet with wrapped text opens, over a sheet that drew;
    newer Chromium words it the second way. Any other error still brings the card.
    """
    for notice in ("ResizeObserver loop limit exceeded",
                   "ResizeObserver loop completed with undelivered notifications."):
        loaded.evaluate("(m) => window.dispatchEvent(new ErrorEvent('error', { message: m }))", notice)
    assert loaded.query_selector(".vw-error-title") is None
    loaded.evaluate("() => window.dispatchEvent(new ErrorEvent('error', { message: 'TypeError: x is undefined' }))")
    assert loaded.text_content(".vw-error-title") == "Something went wrong while rendering"
    assert loaded.text_content(".vw-error-detail") == "TypeError: x is undefined"


# ---------------------------------------------------------------------------
# vwFormatJson: laying a minified .json file out over lines
# ---------------------------------------------------------------------------

def laid_out(page, text):
    return page.evaluate("(t) => vwFormatJson(t)", text)


def test_a_minified_object_is_laid_out_over_lines(loaded):
    assert laid_out(loaded, '{"a":1,"b":2}') == '{\n  "a": 1,\n  "b": 2\n}'


def test_nesting_is_indented_by_depth(loaded):
    assert laid_out(loaded, '{"a":{"b":[1]}}') == (
        '{\n  "a": {\n    "b": [\n      1\n    ]\n  }\n}'
    )


def test_an_empty_container_stays_on_one_line(loaded):
    """Breaking {} across two lines says there is something inside it."""
    assert laid_out(loaded, '{"a":{},"b":[]}') == '{\n  "a": {},\n  "b": []\n}'


def test_laying_out_an_already_laid_out_file_changes_nothing(loaded):
    once = laid_out(loaded, '{"a":[1,2],"b":{}}')
    assert laid_out(loaded, once) == once


# The tokens themselves are copied through. Each of these is altered by the
# obvious implementation, JSON.parse followed by JSON.stringify.

def test_a_number_too_large_for_a_double_is_not_rounded(loaded):
    """
    The one that matters most. Snapshots and API dumps are full of 19-digit
    ids, and rounding one is the viewer saying the file holds a number it
    does not hold.
    """
    assert '"id": 12345678901234567890' in laid_out(
        loaded, '{"id":12345678901234567890}'
    )


def test_a_number_keeps_the_form_it_was_written_in(loaded):
    out = laid_out(loaded, '{"a":1.0,"b":1e5,"c":-0,"d":0.1000000000000000055}')
    for written in ("1.0", "1e5", "-0", "0.1000000000000000055"):
        assert written in out, written + " was rewritten"


def test_every_one_of_a_set_of_duplicate_keys_survives(loaded):
    assert laid_out(loaded, '{"a":1,"a":2}') == '{\n  "a": 1,\n  "a": 2\n}'


def test_keys_that_look_like_integers_keep_their_place(loaded):
    """An object with these keys is reordered by a parse and a restringify."""
    assert laid_out(loaded, '{"b":1,"2":2,"1":3}') == (
        '{\n  "b": 1,\n  "2": 2,\n  "1": 3\n}'
    )


def test_an_escape_is_not_expanded(loaded):
    assert laid_out(loaded, '{"s":"\\u00e9 \\/ x"}') == '{\n  "s": "\\u00e9 \\/ x"\n}'


def test_structure_inside_a_string_is_not_structure(loaded):
    assert laid_out(loaded, '{"a":"}{,:"}') == '{\n  "a": "}{,:"\n}'


def test_an_escaped_quote_does_not_end_a_string(loaded):
    assert laid_out(loaded, '{"a":"x\\"y","b":1}') == '{\n  "a": "x\\"y",\n  "b": 1\n}'



def test_nesting_too_deep_to_draw_is_declined_rather_than_thrown(loaded):
    """
    Every line is indented by its depth, so what laying out makes grows with the
    square of how deep a file goes. Forty kilobytes of brackets inside brackets,
    which JSON.parse accepts, came to more characters than a string can hold, and
    the page opened on an error rather than on the file.
    """
    deep = "[" * 20000 + "]" * 20000
    outcome = loaded.evaluate(
        "(t) => { try { return vwFormatJson(t); } catch (e) { return 'threw ' + e; } }", deep)
    assert outcome is None


def test_a_layout_many_times_the_size_of_the_file_is_declined(loaded):
    """3,000 deep lays out as eighteen million characters from six thousand."""
    assert laid_out(loaded, "[" * 3000 + "]" * 3000) is None


def test_a_layout_of_more_lines_than_are_worth_drawing_is_declined(loaded):
    """
    Bare numbers are two characters a line, five times the lines per megabyte of
    the records VW_JSON_MAX was measured on, which is the drawing it exists to bound.
    """
    assert laid_out(loaded, "[" + ",".join(["0"] * 200000) + "]") is None
    # And an ordinary nesting of a few levels is still laid out
    assert laid_out(loaded, '{"a":[[1,[2]]]}') == '{\n  "a": [\n    [\n      1,\n      [\n        2\n      ]\n    ]\n  ]\n}'

# Anything that is not JSON is declined, and the file is shown as it is.

def test_text_that_is_not_json_is_declined(loaded):
    for not_json in (
        "",
        "2026-09-20 10:43:01 INFO started",
        '{ /* a comment */ "a":1 }',      # what an editor config often holds
        '{"a":1,}',                        # trailing comma
        "{'a':1}",                         # single quotes
        '{"a":1',                          # truncated
        '{"a":"x}',                        # unterminated string
        '{"a":1}\n{"b":2}',                # JSON Lines
        '{"a":NaN}',
        '{"a":1} and then some',
    ):
        assert laid_out(loaded, not_json) is None, not_json + " was not declined"
