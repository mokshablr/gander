package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The message channel is the only way into this app from a document, and
 * nothing on either side of it is evaluated. That makes this parser the
 * boundary, so it is tested as one: everything that is not exactly a message
 * has to come back null.
 *
 * The other half of this contract is asserted in tests/viewer/test_pdf_search.py,
 * which drives the real page over a real MessageChannel and checks the strings
 * it emits match the shapes read here.
 */
class PortMessageTest {

    // ---------------------------------------------------------------
    // Outbound
    // ---------------------------------------------------------------

    @Test
    fun everyCommandIsOneVerbThenPayload() {
        assertThat(PortCommand.query("tenancy")).isEqualTo("qtenancy")
        assertThat(PortCommand.next()).isEqualTo("n")
        assertThat(PortCommand.prev()).isEqualTo("p")
        assertThat(PortCommand.clear()).isEqualTo("c")
        assertThat(PortCommand.goToPage(25)).isEqualTo("g25")
        assertThat(PortCommand.nightMode(true)).isEqualTo("i1")
        assertThat(PortCommand.nightMode(false)).isEqualTo("i0")
        assertThat(PortCommand.barHeight("64")).isEqualTo("t64")
        assertThat(PortCommand.barGone("0")).isEqualTo("b0")
        assertThat(PortCommand.barGone("23.5")).isEqualTo("b23.5")
        assertThat(PortCommand.contentsEntry(12)).isEqualTo("e12")
        assertThat(PortCommand.closeOver()).isEqualTo("x")
    }

    /**
     * Every verb is one character, and no two of them are the same character.
     * The page reads msg.charAt(0) and branches on it, so a duplicate would not
     * fail here or there: one of the two commands would simply do the other's
     * job. Written as a set so that a verb added later has to come past it.
     */
    @Test
    fun noTwoCommandsShareAVerb() {
        val verbs = listOf(
            PortCommand.query("x"),
            PortCommand.next(),
            PortCommand.prev(),
            PortCommand.clear(),
            PortCommand.goToPage(1),
            PortCommand.nightMode(true),
            PortCommand.barHeight("64"),
            PortCommand.barGone("0"),
            PortCommand.contentsEntry(0),
            PortCommand.closeOver(),
        ).map { it.take(1) }
        assertThat(verbs).containsNoDuplicates()
        assertThat(verbs).containsExactly("q", "n", "p", "c", "g", "i", "t", "b", "e", "x")
    }

    /**
     * A query is arbitrary text and is never quoted, escaped or parsed. The
     * page takes everything after the verb, so a query that looks like another
     * command is still just a query.
     */
    @Test
    fun aQueryIsPassedThroughUntouched() {
        assertThat(PortCommand.query("")).isEqualTo("q")
        assertThat(PortCommand.query("n")).isEqualTo("qn")
        assertThat(PortCommand.query("page 1 2")).isEqualTo("qpage 1 2")
        assertThat(PortCommand.query("a\"b\\c")).isEqualTo("qa\"b\\c")
        assertThat(PortCommand.query("你好")).isEqualTo("q你好")
    }

    // ---------------------------------------------------------------
    // Inbound: the page indicator
    // ---------------------------------------------------------------

    @Test
    fun aPageMessageIsRead() {
        assertThat(parsePortMessage("page 3 10")).isEqualTo(PortMessage.Page(3, 10))
        assertThat(parsePortMessage("page 1 1")).isEqualTo(PortMessage.Page(1, 1))
    }

    /** Pages count from one, so page zero is not a page. */
    @Test
    fun aPageBeforeTheFirstIsDropped() {
        assertThat(parsePortMessage("page 0 10")).isNull()
        assertThat(parsePortMessage("page -1 10")).isNull()
        assertThat(parsePortMessage("page 1 0")).isNull()
    }

    @Test
    fun aPagePastTheEndIsDropped() {
        assertThat(parsePortMessage("page 11 10")).isNull()
    }

    @Test
    fun aPageMessageWithNonNumbersIsDropped() {
        assertThat(parsePortMessage("page x 10")).isNull()
        assertThat(parsePortMessage("page 3 y")).isNull()
        assertThat(parsePortMessage("page 3.5 10")).isNull()
    }

    @Test
    fun aTruncatedPageMessageIsDropped() {
        assertThat(parsePortMessage("page 3")).isNull()
        assertThat(parsePortMessage("page")).isNull()
        assertThat(parsePortMessage("page 1 2 3")).isNull()
    }

    // ---------------------------------------------------------------
    // Inbound: a PDF that asked for a password
    // ---------------------------------------------------------------

    @Test
    fun aLockedMessageIsRead() {
        assertThat(parsePortMessage("locked")).isEqualTo(PortMessage.Locked)
    }

    /** One exact word, so nothing a page sends by accident reads as it. */
    @Test
    fun onlyTheExactWordIsLocked() {
        assertThat(parsePortMessage("Locked")).isNull()
        assertThat(parsePortMessage("locked ")).isNull()
        assertThat(parsePortMessage(" locked")).isNull()
        assertThat(parsePortMessage("locked 1 0")).isNull()
    }

    // ---------------------------------------------------------------
    // Inbound: the card's button for the simple view
    // ---------------------------------------------------------------

    @Test
    fun theAskForTheSimpleViewIsRead() {
        assertThat(parsePortMessage("simple")).isEqualTo(PortMessage.SimpleView)
    }

    /** One exact word, as "locked" is. */
    @Test
    fun onlyTheExactWordAsksForTheSimpleView() {
        assertThat(parsePortMessage("Simple")).isNull()
        assertThat(parsePortMessage("simple ")).isNull()
        assertThat(parsePortMessage("simpler")).isNull()
        assertThat(parsePortMessage("simple 1 0")).isNull()
    }

    // ---------------------------------------------------------------
    // Inbound: the card saying the document could not be shown, and its button
    // ---------------------------------------------------------------

    @Test
    fun theFailedCardAndItsButtonAreRead() {
        assertThat(parsePortMessage("failed")).isEqualTo(PortMessage.Failed)
        assertThat(parsePortMessage("elsewhere")).isEqualTo(PortMessage.OpenElsewhere)
    }

    @Test
    fun onlyTheExactWordsAreRead() {
        for (near in listOf("Failed", "failed ", "failed 1 0", "elsewhere 1", "Elsewhere", "else")) {
            assertThat(parsePortMessage(near)).isNull()
        }
    }

    // ---------------------------------------------------------------
    // Inbound: a PDF whose pages are pinched smaller than the screen
    // ---------------------------------------------------------------

    @Test
    fun whetherThePagesAreShrunkIsRead() {
        assertThat(parsePortMessage("shrunk 1")).isEqualTo(PortMessage.Shrunk(true))
        assertThat(parsePortMessage("shrunk 0")).isEqualTo(PortMessage.Shrunk(false))
    }

    /** Two exact messages, as "locked" is one. */
    @Test
    fun onlyTheExactWordsSayThePagesAreShrunk() {
        assertThat(parsePortMessage("shrunk 2")).isNull()
        assertThat(parsePortMessage("shrunk")).isNull()
        assertThat(parsePortMessage("shrunk 1 ")).isNull()
        assertThat(parsePortMessage("Shrunk 1")).isNull()
        assertThat(parsePortMessage("shrunk 0.5")).isNull()
        assertThat(parsePortMessage("shrunk 1 0")).isNull()
    }

    // ---------------------------------------------------------------
    // Inbound: a book's place, what is open over it, and whether it reflows
    // ---------------------------------------------------------------

    @Test
    fun aPlaceIsRead() {
        assertThat(parsePortMessage("at 2 2968")).isEqualTo(PortMessage.Place(2, 2968))
        assertThat(parsePortMessage("at 0 0")).isEqualTo(PortMessage.Place(0, 0))
    }

    /** Chapters and what is past in them both count from nought, and neither is ever less. */
    @Test
    fun aPlaceThatCouldNotBeInABookIsDropped() {
        assertThat(parsePortMessage("at -1 5")).isNull()
        assertThat(parsePortMessage("at 2 -5")).isNull()
        assertThat(parsePortMessage("at 2 x")).isNull()
        assertThat(parsePortMessage("at 2.5 5")).isNull()
        assertThat(parsePortMessage("at 2")).isNull()
        assertThat(parsePortMessage("at 2 5 9")).isNull()
        assertThat(parsePortMessage("at 99999999999 5")).isNull()
    }

    @Test
    fun whatIsOpenOverTheBookAndWhetherItReflowsAreRead() {
        assertThat(parsePortMessage("cover 1")).isEqualTo(PortMessage.Covered(true))
        assertThat(parsePortMessage("cover 0")).isEqualTo(PortMessage.Covered(false))
        assertThat(parsePortMessage("reflow 1")).isEqualTo(PortMessage.Reflows(true))
        assertThat(parsePortMessage("reflow 0")).isEqualTo(PortMessage.Reflows(false))
    }

    /** Each of the four is one exact message, as "locked" is. */
    @Test
    fun onlyTheExactWordsAreThose() {
        assertThat(parsePortMessage("cover 2")).isNull()
        assertThat(parsePortMessage("cover")).isNull()
        assertThat(parsePortMessage("cover 1 ")).isNull()
        assertThat(parsePortMessage("Cover 1")).isNull()
        assertThat(parsePortMessage("reflow yes")).isNull()
        assertThat(parsePortMessage("reflow 1 0")).isNull()
    }

    // ---------------------------------------------------------------
    // Inbound: the search counter
    // ---------------------------------------------------------------

    @Test
    fun aSearchCountIsRead() {
        assertThat(parsePortMessage("2 5 1"))
            .isEqualTo(PortMessage.SearchCount(2, 5, done = true))
        assertThat(parsePortMessage("2 5 0"))
            .isEqualTo(PortMessage.SearchCount(2, 5, done = false))
    }

    /**
     * Nothing found, and nothing to be at. The page sends this rather than
     * "1 0", so the readout never reads "1/0" and TalkBack never says
     * "Match 1 of 0".
     */
    @Test
    fun nothingFoundIsAValidCount() {
        assertThat(parsePortMessage("0 0 1"))
            .isEqualTo(PortMessage.SearchCount(0, 0, done = true))
    }

    /** Still indexing: a count that is true so far, and will grow. */
    @Test
    fun anIncompleteIndexIsMarkedNotDone() {
        val said = parsePortMessage("1 4 0") as PortMessage.SearchCount
        assertThat(said.done).isFalse()
    }

    /** Anything other than exactly "1" is not done. */
    @Test
    fun onlyTheDigitOneMeansDone() {
        listOf("2 5 0", "2 5 x", "2 5 true", "2 5 11").forEach { raw ->
            assertThat((parsePortMessage(raw) as PortMessage.SearchCount).done).isFalse()
        }
    }

    @Test
    fun aNegativeCountIsDropped() {
        assertThat(parsePortMessage("-1 5 1")).isNull()
        assertThat(parsePortMessage("1 -5 1")).isNull()
    }

    @Test
    fun aMalformedCountIsDropped() {
        assertThat(parsePortMessage("x 5 1")).isNull()
        assertThat(parsePortMessage("1 y 1")).isNull()
        assertThat(parsePortMessage("1 2")).isNull()
        assertThat(parsePortMessage("1 2 3 4")).isNull()
    }

    // ---------------------------------------------------------------
    // Everything that is not a message at all
    // ---------------------------------------------------------------

    @Test
    fun anythingElseIsDropped() {
        listOf(
            null, "", " ", "hello", "page", "{\"page\":1}",
            "javascript:alert(1)", "1", "1 2 3 4 5",
        ).forEach { raw ->
            assertThat(parsePortMessage(raw)).isNull()
        }
    }

    /**
     * The tag is read before the numbers, so a document cannot send a page
     * message that is mistaken for a search count or the other way round.
     */
    @Test
    fun theTaggedAndUntaggedShapesCannotBeConfused() {
        assertThat(parsePortMessage("page 3 10")).isInstanceOf(PortMessage.Page::class.java)
        assertThat(parsePortMessage("3 10 1"))
            .isInstanceOf(PortMessage.SearchCount::class.java)
        // "page" where a number belongs is not a number, so this is neither
        assertThat(parsePortMessage("3 page 1")).isNull()
    }

    /**
     * Numbers far larger than any real document are still integers, and are
     * accepted rather than rejected: the activity clamps what it displays, and
     * a parser that threw here would take the channel down with it.
     */
    @Test
    fun anImplausiblyLargeButValidCountIsAccepted() {
        assertThat(parsePortMessage("1 999999 1"))
            .isEqualTo(PortMessage.SearchCount(1, 999999, done = true))
        // past Int.MAX_VALUE it stops being an integer, and is dropped
        assertThat(parsePortMessage("1 99999999999 1")).isNull()
    }

    // ---------------------------------------------------------------
    // Inbound: a document's contents list, issue #79
    // ---------------------------------------------------------------

    @Test
    fun aContentsListIsReadEntryByEntry() {
        assertThat(parsePortMessage("contents\n0 1 auto - Front matter\n1 3 auto - Section 1.1 and its two  spaces\n0 0 rtl ar On the web"))
            .isEqualTo(
                PortMessage.Contents(
                    listOf(
                        ContentsEntry(0, 1, "Front matter"),
                        ContentsEntry(1, 3, "Section 1.1 and its two  spaces"),
                        ContentsEntry(0, 0, "On the web", "rtl", "ar"),
                    )
                )
            )
    }

    /** A title is the document's words, and nothing in it is read as anything else. */
    @Test
    fun aTitleIsTakenAsItIs() {
        val said = parsePortMessage("contents\n0 2 auto - page 2 6\n0 3 auto - \u0641\u0635\u0644 cover 1") as PortMessage.Contents
        assertThat(said.entries.map { it.title }).containsExactly("page 2 6", "\u0641\u0635\u0644 cover 1").inOrder()
    }

    /** One bad line and the whole list is dropped, as any other message that is not exact is. */
    @Test
    fun aContentsListThatIsNotExactIsDropped() {
        assertThat(parsePortMessage("contents")).isNull()
        assertThat(parsePortMessage("contents\n")).isNull()
        assertThat(parsePortMessage("contents\n0 1 auto -")).isNull()
        assertThat(parsePortMessage("contents\n0 1 auto - ")).isNull()
        assertThat(parsePortMessage("contents\n0 x auto - Title")).isNull()
        assertThat(parsePortMessage("contents\n-1 1 auto - Title")).isNull()
        assertThat(parsePortMessage("contents\n0 -1 auto - Title")).isNull()
        assertThat(parsePortMessage("contents\n0 1 auto - Title\n0 1 auto -")).isNull()
        assertThat(parsePortMessage("Contents\n0 1 auto - Title")).isNull()
        assertThat(parsePortMessage(" contents\n0 1 auto - Title")).isNull()
    }

    /** Which way a title reads and its language are each a word of a fixed shape, or the list is dropped. */
    @Test
    fun aTitlesWayAndLanguageAreReadStrictly() {
        assertThat(parsePortMessage("contents\n0 1 ltr en-GB Title"))
            .isEqualTo(PortMessage.Contents(listOf(ContentsEntry(0, 1, "Title", "ltr", "en-GB"))))
        assertThat(parsePortMessage("contents\n0 1 up en Title")).isNull()
        assertThat(parsePortMessage("contents\n0 1 RTL en Title")).isNull()
        assertThat(parsePortMessage("contents\n0 1 auto en_GB Title")).isNull()
        assertThat(parsePortMessage("contents\n0 1 auto ${"a".repeat(36)} Title")).isNull()
        assertThat(parsePortMessage("contents\n0 1 auto \" Title")).isNull()
        assertThat(parsePortMessage("contents\n0 1 auto - Title")).isEqualTo(
            PortMessage.Contents(listOf(ContentsEntry(0, 1, "Title", "auto", "")))
        )
    }

    @Test
    fun aListWithNowhereToGoIsNoList() {
        assertThat(parsePortMessage("contents\n0 0 auto - On the web\n0 0 auto - And another")).isNull()
    }

    @Test
    fun aListPastTheLimitsIsDropped() {
        val most = (1..CONTENTS_MOST).joinToString("\n") { "0 1 auto - Entry $it" }
        assertThat(parsePortMessage("contents\n$most")).isInstanceOf(PortMessage.Contents::class.java)
        assertThat(parsePortMessage("contents\n$most\n0 1 auto - One more")).isNull()

        val longest = "x".repeat(CONTENTS_TITLE_MOST)
        assertThat(parsePortMessage("contents\n0 1 auto - $longest")).isInstanceOf(PortMessage.Contents::class.java)
        assertThat(parsePortMessage("contents\n0 1 auto - ${longest}x")).isNull()
        // Counted in letters, as pdf.mjs cuts them, so a title of emoji is not cut at half its length
        val emoji = "\uD83D\uDCD6".repeat(CONTENTS_TITLE_MOST)
        assertThat(parsePortMessage("contents\n0 1 auto - $emoji")).isInstanceOf(PortMessage.Contents::class.java)
    }
}
