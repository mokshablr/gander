"""imgweb.html: the formats the tiling view cannot decode, and the pictures that move."""

import pytest


def wait_for_image(page, timeout=20000):
    page.wait_for_function(
        "() => { const i = document.getElementById('img');"
        "return i && i.complete && i.naturalWidth > 0; }",
        timeout=timeout,
    )


def test_a_gif_is_shown(viewer, page):
    viewer("imgweb.html", "anim.gif")
    wait_for_image(page)
    assert page.evaluate("() => document.getElementById('img').naturalWidth") == 48


def test_an_svg_is_shown(viewer, page):
    viewer("imgweb.html", "icon.svg")
    wait_for_image(page)
    assert page.evaluate("() => document.getElementById('img').naturalWidth") > 0


def test_the_image_is_labelled_with_the_file_name(viewer, page):
    """The only thing a screen reader can truthfully say about a photo."""
    viewer("imgweb.html", "anim.gif")
    wait_for_image(page)
    assert page.get_attribute("#img", "alt") == "anim.gif"


def test_something_that_will_not_decode_says_so(viewer, page):
    viewer("imgweb.html", "not-a-pdf.pdf", ext="gif")
    page.wait_for_selector(".vw-error", timeout=20000)
    assert page.text_content("#vw-status").strip()


def colour_now(page):
    """The colour at the middle of the picture as it is on screen. Not through a canvas, which
    is given an animation's first frame whatever frame is showing."""
    from io import BytesIO
    from PIL import Image

    shot = Image.open(BytesIO(page.locator("#img").screenshot())).convert("RGB")
    return shot.getpixel((shot.width // 2, shot.height // 2))


@pytest.mark.parametrize("fixture", ["moving.webp", "moving.png"])
def test_a_webp_or_png_that_moves_plays(viewer, page, fixture):
    """#49: the photo view drew one frame of these, so they come here, where they move."""
    viewer("imgweb.html", fixture)
    wait_for_image(page)
    seen = {colour_now(page)}
    for _ in range(20):
        page.wait_for_timeout(100)
        seen.add(colour_now(page))
        if len(seen) > 1:
            break
    assert len(seen) > 1, f"{fixture} showed only {seen}"
