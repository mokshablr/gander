"""
Find-in-document, over the message port ViewerActivity really uses.

Every assertion here is on the wire format PortMessage.kt parses, so the two
halves of the protocol are pinned against each other: PortMessageTest proves
Kotlin reads these strings, and this proves the page writes them.

six-pages.pdf contains "tenancy" exactly three times, in three different
cases, and nothing else in it matches.
"""

from helpers import highlight_count, wait_for_band, wait_for_page, wait_for_pdf


def open_and_attach(viewer, page, port, fixture="six-pages.pdf"):
    """
    The document open, its first page finished with its words in place, and the port
    attached. paint() puts a hit's highlight on before report() sends its count, so a
    count arriving means the highlights on the pages drawn by then are on as well.
    """
    viewer("pdf.html", fixture)
    wait_for_pdf(page)
    wait_for_page(page, 0)
    return port()


# ---------------------------------------------------------------------------
# Counting
# ---------------------------------------------------------------------------

def test_a_query_reports_how_many_it_found(viewer, page, port):
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    assert p.wait_for_count(1, 3, done=True)


def test_matching_ignores_case(viewer, page, port):
    """The three hits are "tenancy", "Tenancy" and "TENANCY"."""
    p = open_and_attach(viewer, page, port)
    p.query("TENANCY")
    assert p.wait_for_count(1, 3, done=True)


def test_a_query_that_matches_nothing_reports_nothing(viewer, page, port):
    """
    Zero of zero, never one of zero: the readout would otherwise say "1/0" and
    TalkBack would read "Match 1 of 0" aloud.
    """
    p = open_and_attach(viewer, page, port)
    p.query("wombat")
    assert p.wait_for_count(0, 0, done=True)


def test_an_empty_query_clears_the_count(viewer, page, port):
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    p.wait_for_count(1, 3)
    p.clear_inbox()

    p.query("")
    assert p.wait_for_count(0, 0)


def test_clearing_the_search_puts_the_count_back_to_nothing(viewer, page, port):
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    p.wait_for_count(1, 3)
    p.clear_inbox()

    p.clear()
    assert p.wait_for_count(0, 0)


# ---------------------------------------------------------------------------
# Stepping
# ---------------------------------------------------------------------------

def test_next_walks_forward_through_the_hits(viewer, page, port):
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    p.wait_for_count(1, 3)

    p.next()
    assert p.wait_for_count(2, 3)
    p.next()
    assert p.wait_for_count(3, 3)


def test_next_wraps_round_at_the_end(viewer, page, port):
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    p.wait_for_count(1, 3)
    p.next()
    p.wait_for_count(2, 3)
    p.next()
    p.wait_for_count(3, 3)

    p.next()
    assert p.wait_for_count(1, 3)


def test_previous_wraps_round_at_the_start(viewer, page, port):
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    p.wait_for_count(1, 3)

    p.prev()
    assert p.wait_for_count(3, 3)


# ---------------------------------------------------------------------------
# Highlighting
# ---------------------------------------------------------------------------

def test_the_hits_on_screen_are_highlighted(viewer, page, port):
    """
    Ranges through CSS.highlights rather than wrapping the text in elements,
    which would rebuild the text layer the selection depends on.

    A highlight is a Range over the text layer, and a text layer only exists
    for a page currently drawn. So the registry holds the hits on the pages in
    the band, never necessarily all of them: the count in the toolbar comes
    from the index, which does cover the whole document.
    """
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    p.wait_for_count(1, 3)

    shown = highlight_count(page, "vw-find")
    assert 1 <= shown <= 3, f"{shown} highlights for three hits over three pages"
    assert highlight_count(page, "vw-find-active") == 1


def test_clearing_takes_the_highlights_with_it(viewer, page, port):
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    p.wait_for_count(1, 3)
    assert highlight_count(page) >= 1

    p.clear()
    p.wait_for_count(0, 0)
    assert highlight_count(page, "vw-find") == 0
    assert highlight_count(page, "vw-find-active") == 0


def test_exactly_one_hit_is_ever_the_active_one(viewer, page, port):
    """
    Two registries, and the active one holds a single range however far the
    reader steps. Two active highlights at once would read as two answers to
    "where am I".
    """
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    p.wait_for_count(1, 3)

    for expected in (2, 3, 1):
        p.next()
        p.wait_for_count(expected, 3)
        assert highlight_count(page, "vw-find-active") == 1


def test_stepping_to_a_hit_brings_its_page_into_view(viewer, page, port):
    """
    stepTo draws the target page before scrolling to it, so a hit on a page
    outside the band is not scrolled to and then found blank.
    """
    from helpers import canvas_widths
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    p.wait_for_count(1, 3)
    p.next()
    p.next()
    p.wait_for_count(3, 3)
    wait_for_band(page)

    # the third hit is on page 3, which must now carry a bitmap
    assert canvas_widths(page)[2] > 300


# ---------------------------------------------------------------------------
# The page indicator, the other message on the same channel
# ---------------------------------------------------------------------------

def test_the_page_number_is_reported_as_soon_as_the_port_arrives(viewer, page, port):
    """
    Unprompted, because somebody who opens a document and reads the first page
    without scrolling still has to be told how long it is, and go-to-page has
    no total to check against until this has been sent.
    """
    p = open_and_attach(viewer, page, port)
    assert p.wait_for(r"page 1 6")


def test_scrolling_to_the_end_reports_the_last_page(viewer, page, port):
    p = open_and_attach(viewer, page, port)
    p.wait_for(r"page 1 6")

    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)")
    assert p.wait_for(r"page 6 6")


def test_a_one_page_document_never_reports_a_page(viewer, page, port):
    """Nothing to indicate, so the pill and the go-to-page control stay away."""
    p = open_and_attach(viewer, page, port, fixture="embedded-font.pdf")
    # The page reports on the port the moment it arrives, ahead of any command, so a
    # report would be in the inbox before this count is.
    p.query("wombat")
    p.wait_for_count(0, 0)
    assert p.pages() == []


def test_the_two_messages_never_take_each_others_shape(viewer, page, port):
    """
    A page report is tagged and a search count is three bare numbers. Kotlin
    reads the tag first; this is the other side of that agreement.
    """
    p = open_and_attach(viewer, page, port)
    p.query("tenancy")
    p.wait_for_count(1, 3)
    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)")
    p.wait_for(r"page 6 6")

    for message in p.pages():
        assert message.startswith("page ")
    for message in p.counts():
        assert not message.startswith("page")


# ---------------------------------------------------------------------------
# Go to page
# ---------------------------------------------------------------------------

def test_going_to_a_page_scrolls_to_it_and_says_so(viewer, page, port):
    p = open_and_attach(viewer, page, port, fixture="forty-pages.pdf")
    p.wait_for(r"page 1 40")
    p.clear_inbox()

    p.go_to_page(25)

    assert p.wait_for(r"page 25 40")


def test_going_to_a_page_draws_it(viewer, page, port):
    from helpers import canvas_widths
    p = open_and_attach(viewer, page, port, fixture="forty-pages.pdf")
    p.go_to_page(25)
    wait_for_band(page)
    assert canvas_widths(page)[24] > 300


def test_a_page_number_that_does_not_exist_is_ignored(viewer, page, port):
    """
    Bounds are checked in the page as well as in Kotlin, because an exception
    thrown inside the port's onmessage takes the channel down for the life of
    the document.
    """
    p = open_and_attach(viewer, page, port, fixture="forty-pages.pdf")
    p.wait_for(r"page 1 40")
    p.clear_inbox()

    p.go_to_page(0)
    p.go_to_page(41)
    p.go_to_page(-1)

    # and the channel is still alive afterwards: commands are handled in order, so
    # these three have been by the time the next is answered
    p.go_to_page(10)
    assert p.wait_for(r"page 10 40")


def test_a_command_that_is_nonsense_does_not_kill_the_channel(viewer, page, port):
    p = open_and_attach(viewer, page, port)
    p.send("")
    p.send("z")
    p.send("gnot-a-number")

    p.query("tenancy")
    assert p.wait_for_count(1, 3)
