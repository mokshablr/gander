package com.arjun.gander

/**
 * The wire between ViewerActivity and the viewer pages.
 *
 * One HTML5 message channel carries every word spoken in either direction, and
 * it is deliberately not addJavascriptInterface: that call reflects a Java
 * object into script running on an untrusted document and lets it call methods
 * on it. This passes strings, in the same direction the query parameters on the
 * URL already go. Nothing on either side is evaluated.
 *
 * That makes the parsing the whole of the security boundary, which is why it
 * lives in its own file with its own tests. A document that can reach this can
 * say anything, so everything it says is read as a small fixed shape or
 * dropped. There is no error path on purpose: an unreadable message is not
 * worth reporting to a page that may have sent it deliberately.
 *
 * Outbound, in [PortCommand], is one letter of verb then arbitrary payload.
 * Inbound is space separated and read here.
 */

/** What ViewerActivity sends. One letter, then the rest is payload. */
internal object PortCommand {
    /** Set the query. Arbitrary text, never parsed as anything but text. */
    fun query(q: String) = "q$q"

    fun next() = "n"

    fun prev() = "p"

    fun clear() = "c"

    /** Go to a page, one based. Bounds are checked again inside the page. */
    fun goToPage(n: Int) = "g$n"

    /**
     * Turn the pages over, or back. The channel is named for search because search is
     * what it was opened for, not because that is all it carries.
     */
    fun nightMode(on: Boolean) = if (on) "i1" else "i0"

    /**
     * How tall the title bar over a document is, in dp, which the page keeps clear at its
     * top. See [DocumentChrome].
     */
    fun barHeight(dp: String) = "t$dp"

    /**
     * How much of the title bar over a document has gone up out of sight, in dp: 0 while all of
     * it is on screen.
     */
    fun barGone(dp: String) = "b$dp"

    /** Show a book's contents list, which its own button for is at the top of a chapter. */
    fun contents() = "o"

    /** Back, for a page that has said it has something to do first. See [PortMessage.Covered]. */
    fun closeOver() = "x"
}

/** What a page sends back, once it has been read and found sound. */
internal sealed interface PortMessage {

    /**
     * Which page is under the middle of the screen. Tagged, because the search
     * message is three bare numbers and the two would otherwise be the same
     * shape.
     */
    data class Page(val n: Int, val of: Int) : PortMessage

    /**
     * The search counter. [at] counts from one and is zero when there is
     * nothing to be at, so the readout never says "1 of 0". [done] is false
     * while the index is still being built.
     */
    data class SearchCount(val at: Int, val total: Int, val done: Boolean) : PortMessage

    /** The PDF asked for a password, which Android's printing has no way to give it. */
    data object Locked : PortMessage

    /**
     * Where in a book the top of the screen is: the chapter by its number in the reading
     * order, from nought, and how many of its letters and pictures are [past]. Finer than
     * [Page], and what the book reopens at. See [Positions.Place].
     */
    data class Place(val chapter: Int, val past: Int) : PortMessage

    /**
     * The page has something for Back to do before it leaves the viewer, or no longer has: a
     * book's contents list or the address of a link open over the document, or a place to go
     * back to after a link inside the book.
     */
    data class Covered(val on: Boolean) : PortMessage

    /**
     * Whether the chapter on screen is laid out afresh for the screen, and so takes the
     * reader's text size. A page of a fixed size does not: see [ViewerActivity.applyTextSize].
     */
    data class Reflows(val on: Boolean) : PortMessage

    /** The reader asked on the card for the PDF's simple view. See [ViewerActivity.askForSimpleView]. */
    data object SimpleView : PortMessage

    /**
     * Whether a PDF's pages are pinched smaller than the screen's width, which the page draws
     * itself since the WebView zooms out no further. While they are, a double tap is the page's,
     * back to the fitted width: see [DoubleTapZoom.leftToPage].
     */
    data class Shrunk(val on: Boolean) : PortMessage
}

/**
 * Reads one message off the port, or null for anything that is not one.
 *
 * Null covers a message of the wrong length, a field that is not an integer,
 * and a set of integers that could not describe a real document: a page before
 * the first or after the last, a place before a book's first chapter, or a
 * negative count. The caller drops those silently.
 */
internal fun parsePortMessage(data: String?): PortMessage? {
    // Whole messages, each one exact, so nothing a page says by accident reads as one
    when (data) {
        "locked" -> return PortMessage.Locked
        "cover 1" -> return PortMessage.Covered(true)
        "cover 0" -> return PortMessage.Covered(false)
        "reflow 1" -> return PortMessage.Reflows(true)
        "reflow 0" -> return PortMessage.Reflows(false)
        "simple" -> return PortMessage.SimpleView
        "shrunk 1" -> return PortMessage.Shrunk(true)
        "shrunk 0" -> return PortMessage.Shrunk(false)
    }
    val said = data?.split(" ") ?: return null
    if (said.size != 3) return null

    if (said[0] == "at") {
        val chapter = said[1].toIntOrNull() ?: return null
        val past = said[2].toIntOrNull() ?: return null
        if (chapter < 0 || past < 0) return null
        return PortMessage.Place(chapter, past)
    }

    // Read first, because "page" is not an integer and so was already being
    // dropped by the search branch before this one existed.
    if (said[0] == "page") {
        val n = said[1].toIntOrNull() ?: return null
        val of = said[2].toIntOrNull() ?: return null
        if (n < 1 || of < 1 || n > of) return null
        return PortMessage.Page(n, of)
    }

    val at = said[0].toIntOrNull() ?: return null
    val total = said[1].toIntOrNull() ?: return null
    if (at < 0 || total < 0) return null
    return PortMessage.SearchCount(at, total, said[2] == "1")
}
