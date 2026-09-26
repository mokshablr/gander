"""
Issue #19: night mode, the toggle that turns a PDF's pages over.

The page bitmap gets one filtered pass once pdf.js has finished drawing it, so
what is asserted here is pixels: which colour each thing on the page came out,
not merely that it got darker. colours.pdf is painted in flat known values for
exactly that, and the values below are the arithmetic the filter is meant to be
doing, worked out from the matrix rather than read off a screenshot.

Two of these tests are the ones worth keeping if the rest ever go. Grey has to
stay grey, because the near-miss implementation of this feature is
`invert(1) hue-rotate(180deg)` written as CSS, and a hue-rotate matrix that is
even slightly off leaves body text tinted rather than white -- on a page of
prose that is most of the pixels. And a hue has to survive, because plain
inversion flips it: the cheap version of this feature would bring the heading
below back orange.
"""

from helpers import (
    body_ground, page_colours, pan, paper_ground, region_colours,
    region_fingerprint, scroll_to_page, set_page_scale,
    text_layer_geometry, tile_colours, tiles, wait_for_band, wait_for_page,
    wait_for_pdf, wait_for_tile, wait_for_tile_away_from_the_top,
)

# What colours.pdf is painted in, and what each one must become.
PAPER = "255,255,255"
PAPER_OVER = "0,0,0"

INK = "20,20,20"                 # body text
INK_OVER = "235,235,235"         # lighter by as much as it was dark, and still grey

HEADING = "0,119,199"            # a saturated blue
HEADING_OVER = "56,175,255"      # same hue, 204 degrees; lightness 0.39 -> 0.61
HEADING_PLAIN_INVERT = "255,136,56"   # what a plain invert would give: orange

VECTOR = "30,150,60"             # a drawn block, not an image, so it turns over
VECTOR_OVER = "49,169,79"

PHOTO = "200,30,30"              # an image on page 1: a picture, and stays one
PHOTO_OVER = "255,153,153"       # what it would become if the clip ever missed it
CORNER_PHOTO = "255,0,255"       # a second one, up where the first zoom tile lands

# Page 3 carries the three images that decide the rule. See make_fixtures.py.
CHART_PAPER = "255,255,255"      # the chart's opaque white background, which must go

# Where the three images on page 3 sit, as fractions of the page. The page is 400x600
# and the generator places them at (30, H-220) 260x170, (30, H-380) 160x120 and
# (220, H-380) 160x120; these are those rectangles, inset a little.
CHART_AT = (0.10, 0.10, 0.70, 0.35)
MONO_PHOTO_AT = (0.10, 0.45, 0.45, 0.62)
COLOUR_PHOTO_AT = (0.58, 0.45, 0.93, 0.62)
# (30, H-520) 150x110 and (210, H-520) 170x110 on a 400x600 page, inset a little.
PRODUCT_AT = (0.10, 0.685, 0.43, 0.855)
RIGHT_FIGURE_AT = (0.55, 0.685, 0.93, 0.855)

# Page 4: two photographs at (30, H-260) and (130, H-320), each 180x130 on a 400x600
# page. In canvas fractions that puts the lower one at x 0.075..0.525, y 0.217..0.433
# and the upper at x 0.325..0.775, y 0.317..0.533, so they cross at x 0.325..0.525,
# y 0.317..0.433. These three boxes are inset inside those, and the middle one has to
# be the crossing itself: a box that only looks near it passes whatever the code does,
# which is how the first version of this test passed against the bug it exists for.
UNDER_ONLY_AT = (0.10, 0.24, 0.30, 0.40)
OVER_ONLY_AT = (0.58, 0.36, 0.75, 0.51)
OVERLAP_AT = (0.361, 0.338, 0.489, 0.412)
UNDER_COLOUR = "206,44,30"
OVER_COLOUR = "28,82,196"


def night(viewer, page, on=True, fixture="colours.pdf"):
    """
    Opens colours.pdf the way ViewerActivity would, with the mode already set, and
    waits for its first page to be finished in that mode.
    """
    viewer("pdf.html", fixture, night="1" if on else "0")
    wait_for_pdf(page)
    wait_for_page(page, 0)
    return page


# ---------------------------------------------------------------------------
# The transform itself
# ---------------------------------------------------------------------------

def test_the_paper_turns_black_and_the_ink_turns_white(viewer, page):
    night(viewer, page)
    colours = page_colours(page)
    assert PAPER_OVER in colours, f"paper did not turn over; saw {list(colours)[:6]}"
    assert PAPER not in colours, "white paper survived night mode"
    assert INK_OVER in colours, f"body text did not turn over; saw {list(colours)[:6]}"


def test_grey_stays_grey(viewer, page):
    """
    The one that catches a wrong matrix.

    `invert(1) hue-rotate(180deg)` is the usual one-liner for this, and if the
    hue-rotate matrix's rows do not each sum to one it scales a channel on
    neutral colours: body text comes back faintly tinted instead of white. It is
    subtle enough to ship and obvious enough to be reported, and on a page of
    prose it is most of the pixels. So the assertion is not "light" but "equal".
    """
    night(viewer, page)
    over = [c for c in page_colours(page) if c not in (CORNER_PHOTO, PHOTO)]
    for colour in over:
        r, g, b = (int(v) for v in colour.split(","))
        if r == g == b:
            continue
        # Anything not neutral has to be one of the page's own coloured things
        assert colour in (HEADING_OVER, VECTOR_OVER), \
            f"{colour} is neither neutral nor one of the page's colours"
    assert INK_OVER in over
    r, g, b = (int(v) for v in INK_OVER.split(","))
    assert r == g == b


def test_a_colour_keeps_its_hue(viewer, page):
    """Plain inversion would bring this heading back orange. It has to stay blue."""
    night(viewer, page)
    colours = page_colours(page)
    assert HEADING_OVER in colours, f"heading came out wrong; saw {list(colours)[:6]}"
    assert HEADING_PLAIN_INVERT not in colours, "the heading was plainly inverted"
    assert VECTOR_OVER in colours


def test_nothing_turns_over_unless_night_mode_is_asked_for(viewer, page):
    """Default off. A document is paper and is white at midnight."""
    night(viewer, page, on=False)
    colours = page_colours(page)
    assert PAPER in colours
    assert PAPER_OVER not in colours
    assert HEADING in colours


# ---------------------------------------------------------------------------
# Photographs
# ---------------------------------------------------------------------------

def test_a_photograph_is_left_as_it_was_printed(viewer, page):
    """The wart in every reader that does this by inverting the whole page."""
    night(viewer, page)
    colours = page_colours(page)
    assert PHOTO in colours, "the photograph was turned over with the page"
    assert CORNER_PHOTO in colours


def test_a_scanned_page_turns_over_even_though_it_is_an_image(viewer, page):
    """
    Page 2 of the fixture is one image covering the whole page, which is what a
    scanned book is, and it has to turn over like the page it is a picture of.

    Two simpler rules got this wrong in opposite directions and both shipped
    briefly. Sizing it - an image covering most of a page is the page - turned a
    full-page photograph inside out. Excluding every image left this white.
    """
    night(viewer, page)
    scroll_to_page(page, 1)
    colours = page_colours(page, index=1)
    assert PAPER_OVER in colours, f"the scan did not turn over; saw {list(colours)[:6]}"
    assert INK_OVER in colours
    assert PAPER not in colours


def test_a_figure_with_a_white_background_does_not_stay_white(viewer, page):
    """
    The loudest way to get this wrong, and the one that is easiest to ship.

    A chart exported as a PNG, a logo on a letterhead and a chapter ornament are
    all images carrying an opaque white background. Leaving them alone because
    they are images puts a glaring white rectangle in the middle of a dark page,
    on the commonest documents there are - worse than night mode doing nothing.
    KOReader shipped exactly that and had it reported as their issue #4986.
    """
    night(viewer, page)
    scroll_to_page(page, 2)
    colours = region_colours(page, 2, *CHART_AT)
    assert CHART_PAPER not in colours, \
        f"the figure was left as a white rectangle; saw {list(colours)[:6]}"
    assert PAPER_OVER in colours


def test_a_photograph_with_no_colour_in_it_is_still_a_photograph(viewer, page):
    """
    Why the rule takes two measurements rather than one.

    Saturation alone separates a colour photograph from a document, and would be
    the obvious single test. A black and white photograph has no saturation
    either, so on its own that rule turns every one of them inside out. What
    saves it is that a document is mostly blank paper and a photograph is not.
    """
    def photo(on):
        night(viewer, page, on=on)
        scroll_to_page(page, 2)
        return region_colours(page, 2, *MONO_PHOTO_AT)

    daylight = photo(False)
    assert daylight, "the monochrome photograph is not in the fixture"
    assert photo(True) == daylight, \
        "a photograph with no colour in it was turned over"


def test_a_colour_photograph_is_left_alone_beside_a_figure_that_is_not(viewer, page):
    """
    Both halves of the rule on one page, so neither can be satisfied by a change
    that gives up and treats every image the same way.
    """
    def look(on):
        night(viewer, page, on=on)
        scroll_to_page(page, 2)
        return (region_fingerprint(page, 2, *COLOUR_PHOTO_AT),
                region_fingerprint(page, 2, *CHART_AT))

    photo_day, chart_day = look(False)
    photo_night, chart_night = look(True)
    assert photo_night == photo_day, "the colour photograph was turned over"
    assert chart_night != chart_day, "the figure beside it was left alone"


def test_a_photograph_on_a_white_background_is_still_a_photograph(viewer, page):
    """
    Why the rule keeps its saturation half.

    A product shot - an object photographed against white - is mostly paper by area,
    so the "is it mostly white" half on its own calls it a document and turns it
    inside out. Catalogues, listings and press packs are full of them. What saves
    them is that they are strongly coloured where a document is not.
    """
    def shot(on):
        night(viewer, page, on=on)
        scroll_to_page(page, 2)
        return region_fingerprint(page, 2, *PRODUCT_AT)

    assert shot(True) == shot(False), \
        "a photograph on a white background was turned over"


def test_a_figure_far_from_the_page_corner_still_turns_over(viewer, page):
    """
    That the page sample is mapped back to the page, and not read at face value.

    Every image is measured out of one small copy of the page, so each rectangle has
    to be scaled into that copy's coordinates. Getting that wrong still works for
    anything near the top left, which is where the first figure sits, because the
    wrong region is also mostly paper. This one is far enough across that a wrong
    mapping measures nothing at all and leaves it white.
    """
    night(viewer, page)
    scroll_to_page(page, 2)
    colours = region_colours(page, 2, *RIGHT_FIGURE_AT)
    assert CHART_PAPER not in colours, \
        f"the figure on the right stayed white; saw {list(colours)[:6]}"
    assert PAPER_OVER in colours


def test_two_photographs_that_overlap_are_both_kept_whole(viewer, page):
    """
    The overlap is a picture too, and it is the piece an even-odd hole loses.

    Clipping every picture out of a single even-odd path is the natural way to write
    "everything but these", and it is wrong exactly here: even-odd counts crossings,
    so the canvas plus two holes comes to three, which is odd, which is inside. The
    overlap is turned over while both photographs around it are left alone, and two
    copies of one image at the same place invert the whole picture. Clipping one hole
    at a time intersects instead, and an intersection of complements is the complement
    of the union however they lie. Nonzero winding is not the fix either; the overlap
    counts -1 there, which is also inside.

    A collage, a photograph under a colour wash and a figure with an inset are all
    this shape.
    """
    night(viewer, page)
    scroll_to_page(page, 3)

    under = region_colours(page, 3, *UNDER_ONLY_AT)
    over = region_colours(page, 3, *OVER_ONLY_AT)
    overlap = region_colours(page, 3, *OVERLAP_AT)

    assert UNDER_COLOUR in under, f"the lower photograph was turned over; {list(under)[:4]}"
    assert OVER_COLOUR in over, f"the upper photograph was turned over; {list(over)[:4]}"
    assert OVER_COLOUR in overlap, \
        f"the overlap was turned over while both photographs were kept; {list(overlap)[:4]}"


# ---------------------------------------------------------------------------
# What night mode must not touch
# ---------------------------------------------------------------------------

def test_the_text_layer_is_not_moved(viewer, page):
    """
    The filter goes on the bitmap, not on an ancestor, so the words over it are
    untouched. If that ever changes, selection and search go wrong silently:
    see the text layer contract in docs/VENDORED.md.
    """
    viewer("pdf.html", "colours.pdf", night="0")
    wait_for_pdf(page)
    wait_for_band(page)
    before = text_layer_geometry(page)
    assert before, "no text layer to compare"

    viewer("pdf.html", "colours.pdf", night="1")
    wait_for_pdf(page)
    wait_for_band(page)
    assert text_layer_geometry(page) == before


def test_the_grounds_move_with_the_toggle_and_not_with_the_scheme(viewer, page):
    """
    Night mode is asked for; it is not the phone's dark mode arriving. The
    surround and the unpainted paper both turn over with it, the second so that
    a page not yet drawn is not a white rectangle.
    """
    night(viewer, page, on=False)
    assert body_ground(page) == "rgb(72, 68, 61)"
    assert paper_ground(page) == "rgb(255, 255, 255)"

    night(viewer, page, on=True)
    assert body_ground(page) == "rgb(23, 19, 10)"
    assert paper_ground(page) == "rgb(0, 0, 0)"


# ---------------------------------------------------------------------------
# The toggle, over the real channel
# ---------------------------------------------------------------------------

def test_the_port_turns_it_on_and_off(viewer, page, port):
    """
    Driven the way ViewerActivity drives it, so the verb is checked here and in
    PortMessageTest.kt against the same two strings.
    """
    night(viewer, page, on=False)
    p = port()
    before = page_colours(page)

    p.night_mode(True)
    wait_for_band(page)
    assert PAPER_OVER in page_colours(page)

    p.night_mode(False)
    wait_for_band(page)
    assert page_colours(page) == before, \
        "turning it off did not put the page back exactly as it was"


def test_the_last_of_several_quick_toggles_wins(viewer, page, port):
    """
    A render already in flight cannot be told to change its mind, so pages are
    reconciled as they land rather than cancelled. Three taps inside a redraw is
    the case that arrangement exists for.
    """
    night(viewer, page, on=False)
    p = port()
    p.night_mode(True)
    p.night_mode(False)
    p.night_mode(True)
    wait_for_band(page)
    colours = page_colours(page)
    assert PAPER_OVER in colours and PAPER not in colours
    assert PHOTO in colours, "the photograph was lost somewhere in the toggling"


def test_a_toggle_during_a_redraw_is_not_left_on_screen(viewer, page, port):
    """
    The window the reconcile in draw() exists for.

    setNight() gives up every drawn page and asks for it again, and pump() starts
    those renders synchronously, each one remembering the mode it began in. A
    second tap before they land leaves bitmaps arriving in a mode the reader has
    already left. They cannot be told to change their mind and cancelling them
    would send them down the failure path a fling uses, so instead they are
    checked as they land and asked for again.

    Without that check this ends with turned-over pages sitting on a light
    surround, and nothing further happens to correct it.
    """
    night(viewer, page, on=False)
    p = port()
    p.night_mode(True)
    # No wait: draw() has already captured "on" for everything in flight by the
    # time the first message is handled, so the second lands inside the window.
    p.night_mode(False)
    wait_for_band(page)

    colours = page_colours(page)
    assert PAPER in colours, \
        f"a page turned over in a mode that was left; saw {list(colours)[:6]}"
    assert PAPER_OVER not in colours
    assert body_ground(page) == "rgb(72, 68, 61)"


# ---------------------------------------------------------------------------
# The sharp patch drawn while zoomed in
# ---------------------------------------------------------------------------

def test_a_zoom_tile_is_turned_over_too(viewer, page):
    """A tile is a second render of the page, so it needs the same pass."""
    night(viewer, page)
    set_page_scale(page, 4)
    wait_for_tile(page)
    colours = tile_colours(page)
    assert colours, "no tile to read"
    assert PAPER_OVER in colours, f"the tile was not turned over; saw {list(colours)[:6]}"
    assert PAPER not in colours


def test_a_zoom_tile_leaves_a_photograph_alone(viewer, page):
    """
    The image coordinates are fractions of the whole page and the tile is one
    rectangle of it blown up, so this is the mapping between the two.

    Counted rather than looked for. A hole in the wrong place still overlaps the
    photograph if it is anywhere near it, so "some magenta survived" passes with
    the mapping broken; "exactly as much magenta as in daylight" does not.
    """
    def magenta_in_tile(on):
        night(viewer, page, on=on)
        set_page_scale(page, 4)
        wait_for_tile(page)
        colours = tile_colours(page)
        assert colours, "no tile to read"
        return colours.get(CORNER_PHOTO, 0)

    daylight = magenta_in_tile(False)
    assert daylight > 0, "the fixture's corner image is not in the first tile"
    assert magenta_in_tile(True) == daylight, \
        "the photograph in the tile was turned over, or the clip landed elsewhere"


def test_a_tile_away_from_the_page_corner_still_finds_the_photograph(viewer, page):
    """
    The tile's own origin, which the first tile of a page cannot test.

    A tile at the top left of a page begins at 0,0, so the term that shifts the
    image coordinates back by where the tile starts is multiplied by nothing and
    a mistake in it cannot show. This pans down to the illustration in the lower
    half of the page first, so there is an origin to get wrong.
    """
    def red_in_tile(on):
        night(viewer, page, on=on)
        set_page_scale(page, 4)
        wait_for_tile(page)
        pan(page, 0, 900)
        wait_for_tile_away_from_the_top(page)
        placed = [t for t in tiles(page) if t["px"] and t["y"] > 1]
        assert placed, "no tile away from the page top after panning"
        seen = tile_colours(page)
        return seen.get(PHOTO, 0), seen.get(PHOTO_OVER, 0)

    daylight, _ = red_in_tile(False)
    assert daylight > 0, "the fixture's lower illustration is not in the panned tile"
    night_count, turned = red_in_tile(True)

    # Neither is an equality. Each pass loads the page and pans afresh, so its tile can
    # be cut a pixel differently and a few hundred pixels of the illustration fall in or
    # out of it.
    #
    # And the colour is allowed one row of edge. imageQuads rounds the clip to the
    # nearest pixel on purpose, so the last row of a photograph can land on either side
    # of it, and whether the one-in-three sample grid falls on that row depends on the
    # zoom: at 3 it does not, at 4 it does. A clip landing anywhere but on the
    # illustration turns all of it over, 200,30,30 coming back as 255,153,153, which is
    # a hundred times more than this allows.
    assert turned < night_count * 0.01, \
        f"the illustration in the panned tile was turned over: {turned} of {night_count} samples"
    assert abs(night_count - daylight) < daylight * 0.05, \
        f"the clip covered a different part of the page: {night_count} vs {daylight}"


def test_a_zoom_tile_turns_a_figure_over_like_the_page_under_it(viewer, page):
    """
    A tile has to reach the same verdict as the page beneath it.

    It cannot reach it by measuring, because a tile holds only the part of an image
    inside it: a corner of a photograph can be pale and flat and read as paper. So
    the page decides once, where the whole image is visible, and the tile reuses
    that. Get it wrong and a figure is dark at one zoom and white at another, on the
    same screen, which is the kind of thing that looks like a rendering fault.
    """
    night(viewer, page)
    scroll_to_page(page, 2)
    set_page_scale(page, 4)
    wait_for_tile(page)
    colours = tile_colours(page)
    assert colours, "no tile to read"
    assert CHART_PAPER not in colours, \
        f"the figure stayed white in the sharp patch; saw {list(colours)[:6]}"


def test_a_zoom_tile_is_not_turned_over_in_daylight(viewer, page):
    night(viewer, page, on=False)
    set_page_scale(page, 4)
    wait_for_tile(page)
    colours = tile_colours(page)
    assert PAPER in colours and PAPER_OVER not in colours
