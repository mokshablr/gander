"""
The cards a page puts up in place of a document, as one design: an error, the password prompt and
the unknown-format card are the same plain card, readable on a light or a dark phone, and an error
card tells the app it failed and offers the file to another app. The app's half is in
DocumentCardTest.kt.
"""

import pytest

from helpers import wait_until_done

# Text against whatever it is drawn on: the nearest of itself and its parents with a ground of its own
CONTRAST = """([selector, property]) => {
  const el = document.querySelector(selector);
  let card = el;
  while (card.parentElement && /rgba\\(0, 0, 0, 0\\)|transparent/.test(getComputedStyle(card).backgroundColor)) {
    card = card.parentElement;
  }
  const rgb = (c) => c.match(/\\d+(\\.\\d+)?/g).slice(0, 3).map(Number);
  const lum = (c) => { const [r, g, b] = rgb(c).map(v => { v /= 255;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
  const a = lum(getComputedStyle(el)[property || 'color']);
  const b = lum(getComputedStyle(card).backgroundColor);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}"""


def contrast(page, selector, prop="color"):
    return page.evaluate(CONTRAST, [selector, prop])


def failed_card(viewer, page, scheme):
    page.emulate_media(color_scheme=scheme)
    viewer("pdf.html", "not-a-pdf.pdf")
    page.wait_for_selector(".vw-error", state="attached", timeout=20000)


@pytest.mark.parametrize("scheme", ["light", "dark"])
def test_an_error_is_the_plain_card_with_its_title_in_the_error_colour(viewer, page, scheme):
    failed_card(viewer, page, scheme)
    style = page.evaluate("""() => {
        const card = document.querySelector('.vw-error');
        const probe = document.createElement('div');
        probe.className = 'vw-status';
        probe.style.display = 'none';
        document.body.appendChild(probe);
        return { card: getComputedStyle(card).backgroundColor, plain: getComputedStyle(probe).backgroundColor,
                 stripe: getComputedStyle(card).borderLeftStyle };
    }""")
    # Not a card filled red, which on a dark phone read as an alarm
    assert style["card"] == style["plain"]
    assert style["stripe"] == "solid"
    assert contrast(page, ".vw-error-title") >= 4.5
    assert contrast(page, ".vw-error-detail") >= 4.5


def test_an_error_tells_the_app_and_offers_the_file_to_another_app(viewer, page, port):
    failed_card(viewer, page, "light")
    channel = port()
    assert channel.wait_for(r"failed") == "failed"
    button = page.wait_for_selector(".vw-elsewhere-btn", timeout=5000)
    assert button.inner_text() == "Open in another app"
    assert contrast(page, ".vw-elsewhere-btn") >= 4.5

    button.click()
    assert channel.wait_for(r"elsewhere") == "elsewhere"


def test_a_page_given_no_channel_has_no_button(viewer, page):
    failed_card(viewer, page, "light")
    page.wait_for_timeout(300)
    assert page.query_selector(".vw-elsewhere-btn") is None


def test_an_error_over_a_document_still_shown_says_nothing(viewer, page, port):
    """text.js's for the rest of a long file: what was read is still on the page."""
    viewer("text.html", "plain.txt")
    wait_until_done(page)
    channel = port()
    page.evaluate("vwError('Could not read the rest of this file', 'gone', true)")
    page.wait_for_timeout(300)
    assert "failed" not in channel.messages()
    assert page.query_selector(".vw-elsewhere-btn") is None


@pytest.mark.parametrize("scheme", ["light", "dark"])
def test_the_password_prompt_is_readable_on_either_phone(viewer, page, scheme):
    """On a dark phone the retry line was the light theme's red on the dark card, 1.6 to 1, under a white field."""
    page.emulate_media(color_scheme=scheme)
    viewer("pdf.html", "encrypted.pdf")
    page.wait_for_selector("#vw-pw", timeout=15000)
    page.fill("#vw-pw", "wrong")
    page.click(".vw-ask-btn")
    page.wait_for_selector("#vw-pw-alert", timeout=15000)

    assert contrast(page, "#vw-pw-alert") >= 4.5
    assert contrast(page, ".vw-ask-btn") >= 4.5
    assert contrast(page, "#vw-pw") >= 4.5
    field = page.evaluate("getComputedStyle(document.getElementById('vw-pw')).backgroundColor")
    # The field goes dark with the card rather than staying a white box on it
    assert (field == "rgb(255, 255, 255)") == (scheme == "light")


def test_the_unknown_format_card_sits_where_every_other_card_does(viewer, page):
    viewer("unsupported.html", "unknown.xyz")
    box = page.evaluate("""() => {
        const r = document.querySelector('.card').getBoundingClientRect();
        return { middle: r.top + r.height / 2, height: innerHeight,
                 radius: getComputedStyle(document.querySelector('.card')).borderTopLeftRadius };
    }""")
    assert abs(box["middle"] - box["height"] / 2) < 2
    assert box["radius"] == "10px"
