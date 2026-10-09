package com.arjun.gander

/**
 * The wire between ViewerActivity and pdf.html.
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
     * Turn the pages over, or back. The second verb here that is not about
     * finding something, after [goToPage]; the channel is named for search
     * because search is what it was opened for, not because that is all it says.
     */
    fun nightMode(on: Boolean) = if (on) "i1" else "i0"

    /**
     * How tall the title bar over a document is, in dp, which the page keeps clear at its
     * top. See [DocumentChrome].
     */
    fun barHeight(dp: String) = "t$dp"

    /** Whether the title bar over a document is on screen or has slid away. */
    fun barShown(shown: Boolean) = if (shown) "b1" else "b0"
}

/** What pdf.html sends back, once it has been read and found sound. */
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
}

/**
 * Reads one message off the port, or null for anything that is not one.
 *
 * Null covers a message of the wrong length, a field that is not an integer,
 * and a set of integers that could not describe a real document: a page before
 * the first or after the last, or a negative count. The caller drops those
 * silently.
 */
internal fun parsePortMessage(data: String?): PortMessage? {
    if (data == "locked") return PortMessage.Locked
    val said = data?.split(" ") ?: return null
    if (said.size != 3) return null

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
