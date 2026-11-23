package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.webkit.WebMessage
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog

/**
 * The viewer's own side of a book, issue #50: its two menu items, Back, the readout, the place
 * it reopens at and the size of its text.
 *
 * The WebView is Robolectric's, so no book is read here. The page's half is in
 * tests/viewer/test_epub.py, which checks that epub.html says the things this class has the
 * page say, and does what the app asks of it here.
 */
@RunWith(AndroidJUnit4::class)
class BookViewerTest {

    @get:Rule val closing = ClosesActivities()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
        File(context.noBackupFilesDir, "positions").delete()
        Settings.setTextSize(context, 100)
    }

    /** The viewer for [fixture], its page loaded and handed the channel. */
    private fun open(fixture: String): ActivityController<ViewerActivity> {
        val uri = FixtureProvider.uriFor(fixture)
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
        val controller = closing.build(ViewerActivity::class.java, intent)
        controller.get().documentLoader = Executor { it.run() }
        controller.setup()
        settle()
        val web = controller.get().web
        web.webViewClient.onPageFinished(web, shadowOf(web).lastLoadedUrl)
        return controller
    }

    private fun book() = open("harbour.epub")

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private val ViewerActivity.web
        get() = findViewById<FrameLayout>(R.id.container).children.filterIsInstance<WebView>().single()
    private val ViewerActivity.menu get() = findViewById<MaterialToolbar>(R.id.toolbar).menu
    private val ViewerActivity.readout get() = findViewById<TextView>(R.id.pageIndicator)

    /** What harbour.epub's page sends of its list, in the shape of test_epub.py's. */
    private val bookList = "contents\n0 1 auto en Title page\n0 2 auto en One: The Lamp Room\n" +
        "0 3 auto en Two: The Long Watch\n0 0 auto en Notes, in the printed edition only"

    /** Something the page says up the channel, heard as the app hears it. */
    private fun ViewerActivity.pageSays(vararg messages: String) {
        val mine = shadowOf(web).createdPorts.single()[0]
        messages.forEach { mine.webMessageCallback.onMessage(mine, WebMessage(it)) }
        settle()
    }

    /** Everything the app has said down the channel so far. */
    private fun ViewerActivity.said(): List<String> = shadowOf(web).createdPorts.single()[0].outgoingMessages

    private fun keyOf(fixture: String): String =
        Positions.keyFor(
            context.contentResolver, FixtureProvider.uriFor(fixture), Fixtures.file(fixture).length()
        )!!

    // ---------------------------------------------------------------
    // The menu
    // ---------------------------------------------------------------

    /** Nothing to list and nothing to size until the page says the book has opened. */
    @Test
    fun aBooksMenuHasContentsAndTextSizeOnceItHasOpened() {
        val viewer = book().get()
        assertThat(viewer.menu.findItem(R.id.action_contents).isVisible).isFalse()
        assertThat(viewer.menu.findItem(R.id.action_text_size).isVisible).isFalse()

        viewer.pageSays("page 1 9", bookList)
        assertThat(viewer.menu.findItem(R.id.action_contents).isVisible).isTrue()
        assertThat(viewer.menu.findItem(R.id.action_text_size).isVisible).isTrue()
        // What a book counts is no page anybody could ask for
        assertThat(viewer.menu.findItem(R.id.action_go_to_page).isVisible).isFalse()
    }

    @Test
    fun aPdfsMenuHasNeither() {
        val viewer = open("six-pages.pdf").get()
        viewer.pageSays("page 1 6")
        assertThat(viewer.menu.findItem(R.id.action_go_to_page).isVisible).isTrue()
        assertThat(viewer.menu.findItem(R.id.action_contents).isVisible).isFalse()
        assertThat(viewer.menu.findItem(R.id.action_text_size).isVisible).isFalse()
    }

    /**
     * A PDF's or Word document's list can come before its first page count, and the list does
     * not open until that count says two pages or more. Contents waits for it, whichever of the
     * two the page sends first.
     */
    @Test
    fun aDocumentsContentsWaitsForASecondPage() {
        val outline = "contents\n0 1 auto en Chapter 1\n0 4 auto en Chapter 2"
        val alone = open("six-pages.pdf").get()
        alone.pageSays(outline)
        assertThat(alone.menu.findItem(R.id.action_contents).isVisible).isFalse()
        alone.pageSays("page 1 6")
        assertThat(alone.menu.findItem(R.id.action_contents).isVisible).isTrue()

        val paged = open("six-pages.pdf").get()
        paged.pageSays("page 1 6", outline)
        assertThat(paged.menu.findItem(R.id.action_contents).isVisible).isTrue()
    }

    /** The list the book sent, shown by the app, with no pages, the chapter on screen marked. */
    @Test
    fun contentsShowsTheBooksListAndAnEntryGoesBackToThePage() {
        val viewer = book().get()
        viewer.pageSays("page 1 9", bookList, "at 2 40")
        viewer.menu.performIdentifierAction(R.id.action_contents, 0)
        settle()
        val screen = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AppCompatDialog
        val list = screen.findViewById<android.widget.ListView>(R.id.contents_list)!!
        val rows = (0 until list.adapter.count).map { list.adapter.getView(it, null, list) }
        assertThat(rows.map { it.findViewById<TextView>(R.id.contents_page).visibility }.distinct())
            .containsExactly(android.view.View.GONE)
        // Chapter 2 counts from nought in the place, so it is the third in reading order
        assertThat(rows.map { it.findViewById<TextView>(R.id.contents_title).typeface?.style == Typeface.BOLD })
            .containsExactly(false, false, true, false).inOrder()

        list.performItemClick(rows[1], 1, 1)
        settle()
        assertThat(viewer.said().last()).isEqualTo(PortCommand.contentsEntry(1))
        assertThat(screen.isShowing).isFalse()
    }

    /** A book with no list of its own sends its chapters by number, so a book always has one. */
    @Test
    fun aBooksMenuShowsNoContentsUntilItsListComes() {
        val viewer = book().get()
        viewer.pageSays("page 1 9")
        assertThat(viewer.menu.findItem(R.id.action_contents).isVisible).isFalse()
        viewer.pageSays(bookList)
        assertThat(viewer.menu.findItem(R.id.action_contents).isVisible).isTrue()
    }

    // ---------------------------------------------------------------
    // Back
    // ---------------------------------------------------------------

    /** The first Back closes what is open over the book, and only the second leaves it. */
    @Test
    fun backClosesWhatThePageHasOpenOverTheBookBeforeItLeaves() {
        val controller = book()
        val viewer = controller.get()
        viewer.pageSays("page 1 9", "cover 1")

        viewer.onBackPressedDispatcher.onBackPressed()
        assertThat(viewer.said().last()).isEqualTo(PortCommand.closeOver())
        assertThat(viewer.isFinishing).isFalse()

        // A page that never says it has closed does not hold on to Back
        viewer.onBackPressedDispatcher.onBackPressed()
        assertThat(viewer.isFinishing).isTrue()
    }

    @Test
    fun backLeavesOnceThePageSaysNothingIsOpen() {
        val viewer = book().get()
        viewer.pageSays("page 1 9", "cover 1", "cover 0")
        val before = viewer.said().size
        viewer.onBackPressedDispatcher.onBackPressed()
        assertThat(viewer.said()).hasSize(before)
        assertThat(viewer.isFinishing).isTrue()
    }

    /**
     * Over the card for a renderer that has gone there is no page to list a book's contents,
     * to take a text size or to answer Back.
     */
    @Test
    fun aBookWhoseRendererHasGoneOffersNothingThatNeedsItsPage() {
        val viewer = book().get()
        viewer.pageSays("page 1 9", bookList, "cover 1")
        assertThat(viewer.menu.findItem(R.id.action_contents).isVisible).isTrue()

        val web = viewer.web
        web.webViewClient.onRenderProcessGone(web, rendererReclaimed())
        settle()

        assertThat(viewer.menu.findItem(R.id.action_contents).isVisible).isFalse()
        assertThat(viewer.menu.findItem(R.id.action_text_size).isVisible).isFalse()
        viewer.onBackPressedDispatcher.onBackPressed()
        assertThat(viewer.isFinishing).isTrue()
    }

    /** The message is a book's page's to send. A PDF saying it changes nothing about Back. */
    @Test
    fun aPdfCannotHoldBack() {
        val viewer = open("six-pages.pdf").get()
        viewer.pageSays("page 1 6", "cover 1")
        viewer.onBackPressedDispatcher.onBackPressed()
        assertThat(viewer.isFinishing).isTrue()
    }

    // ---------------------------------------------------------------
    // The readout
    // ---------------------------------------------------------------

    @Test
    fun aBooksReadoutIsHowFarThroughItTheReaderIs() {
        val viewer = book().get()
        viewer.pageSays("page 5 9")
        assertThat(viewer.readout.text.toString()).isEqualTo("50%")
        assertThat(viewer.readout.contentDescription.toString()).isEqualTo("50% through the book")
        // And a tap on it asks for no page
        assertThat(viewer.readout.isClickable).isFalse()
        viewer.readout.performClick()
        assertThat(ShadowDialog.getLatestDialog()).isNull()
    }

    @Test
    fun aPdfsReadoutIsStillItsPage() {
        val viewer = open("six-pages.pdf").get()
        viewer.pageSays("page 5 6")
        assertThat(viewer.readout.text.toString()).isEqualTo("5/6")
        assertThat(viewer.readout.isClickable).isTrue()
    }

    @Test
    fun aBookIsNoneOfTheWayThroughAtItsStartAndAllOfItAtItsEnd() {
        assertThat(bookPercent(1, 9)).isEqualTo(0)
        assertThat(bookPercent(9, 9)).isEqualTo(100)
        assertThat(bookPercent(5, 9)).isEqualTo(50)
        assertThat(bookPercent(2, 400)).isEqualTo(0)
        assertThat(bookPercent(399, 400)).isEqualTo(100)
        assertThat(bookPercent(4159, 12472)).isEqualTo(33)
        // One stretch is the whole of a book that short
        assertThat(bookPercent(1, 1)).isEqualTo(100)
    }

    // ---------------------------------------------------------------
    // The place a book reopens at
    // ---------------------------------------------------------------

    @Test
    fun aBookReopensAtThePlaceItWasLeftAt() {
        Positions.save(context, keyOf("harbour.epub"), 4, 9, Positions.Place(2, 2968))
        val url = shadowOf(book().get().web).lastLoadedUrl
        assertThat(url).contains("&at=2.2968")
        // Its page is filed too, and is not where it reopens
        assertThat(url).doesNotContain("resume=")
    }

    @Test
    fun aBookNeverLeftAnywhereOpensAtItsStart() {
        assertThat(shadowOf(book().get().web).lastLoadedUrl).doesNotContain("&at=")
    }

    @Test
    fun thePlaceThePageSaysIsKeptWhenTheViewerLeavesTheScreen() {
        val controller = book()
        controller.get().pageSays("page 4 9", "at 2 2968")
        controller.pause().stop()
        assertThat(Positions.place(context, keyOf("harbour.epub"))).isEqualTo(Positions.Place(2, 2968))
    }

    /**
     * The page works its place out once it has stopped moving, so a reader who lets go of a
     * fling and presses Home at once is gone before it has said where they stopped.
     */
    @Test
    fun aPlaceSaidAfterTheViewerHasLeftTheScreenIsKeptToo() {
        val controller = book()
        controller.get().pageSays("page 4 9", "at 2 100")
        controller.pause().stop()
        controller.get().pageSays("at 2 2968")
        assertThat(Positions.place(context, keyOf("harbour.epub"))).isEqualTo(Positions.Place(2, 2968))
    }

    /** Closed in the moment between the page saying how far through it is and where. */
    @Test
    fun aBookThatHasNotYetSaidWhereItIsKeepsThePlaceItHad() {
        val key = keyOf("harbour.epub")
        Positions.save(context, key, 4, 9, Positions.Place(2, 2968))
        val controller = book()
        controller.get().pageSays("page 4 9")
        controller.pause().stop()
        assertThat(Positions.place(context, key)).isEqualTo(Positions.Place(2, 2968))
    }

    /** A place is a book's. A PDF's page saying one files nothing more than its page. */
    @Test
    fun aPdfHasNoPlace() {
        val controller = open("six-pages.pdf")
        controller.get().pageSays("page 4 6", "at 2 2968")
        controller.pause().stop()
        val key = keyOf("six-pages.pdf")
        assertThat(Positions.page(context, key)).isEqualTo(4)
        assertThat(Positions.place(context, key)).isNull()
    }

    // ---------------------------------------------------------------
    // The size of its text
    // ---------------------------------------------------------------

    @Test
    fun aBookOpensAtTheTextSizeLastChosen() {
        assertThat(book().get().web.settings.textZoom).isEqualTo(100)
        Settings.setTextSize(context, 130)
        assertThat(book().get().web.settings.textZoom).isEqualTo(130)
        // A text or Markdown file takes the same size, and a page of a fixed size does not
        assertThat(open("notes.md").get().web.settings.textZoom).isEqualTo(130)
        assertThat(open("plain.txt").get().web.settings.textZoom).isEqualTo(130)
        assertThat(open("six-pages.pdf").get().web.settings.textZoom).isEqualTo(100)
        assertThat(open("letter.odt").get().web.settings.textZoom).isEqualTo(100)
    }

    /** Offered from the start where the page is laid out at the screen's width, and only there. */
    @Test
    fun textAndMarkdownOfferTextSizeAndTakeItAtOnce() {
        for (fixture in listOf("plain.txt", "notes.md", "captions.srt")) {
            assertWithMessage(fixture).that(open(fixture).get().menu.findItem(R.id.action_text_size).isVisible)
                .isTrue()
        }
        for (fixture in listOf("six-pages.pdf", "report.docx", "letter.odt", "budget.xlsx", "anim.gif")) {
            assertWithMessage(fixture).that(open(fixture).get().menu.findItem(R.id.action_text_size).isVisible)
                .isFalse()
        }
        val viewer = open("plain.txt").get()
        textSizeBox(viewer).findViewById<View>(R.id.textLarger)!!.performClick()
        assertThat(viewer.web.settings.textZoom).isEqualTo(115)
    }

    /** It was drawn around its type, and larger letters run out of the boxes they were set in. */
    @Test
    fun aPageOfAFixedSizeIsLeftAtTheSizeItsBookGaveIt() {
        Settings.setTextSize(context, 150)
        val viewer = book().get()
        viewer.pageSays("page 1 9", "reflow 0")
        assertThat(viewer.web.settings.textZoom).isEqualTo(100)
        viewer.pageSays("reflow 1")
        assertThat(viewer.web.settings.textZoom).isEqualTo(150)
    }

    private fun textSizeBox(viewer: ViewerActivity): AlertDialog {
        viewer.menu.performIdentifierAction(R.id.action_text_size, 0)
        settle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test
    fun theTwoButtonsStepTheSizeAndThePageTakesItAtOnce() {
        val viewer = book().get()
        viewer.pageSays("page 1 9")
        val box = textSizeBox(viewer)
        val now = box.findViewById<TextView>(R.id.textSizeNow)!!
        assertThat(now.text.toString()).isEqualTo("100%")

        box.findViewById<View>(R.id.textLarger)!!.performClick()
        assertThat(now.text.toString()).isEqualTo("115%")
        assertThat(now.contentDescription.toString()).isEqualTo("Text size 115%")
        assertThat(viewer.web.settings.textZoom).isEqualTo(115)
        assertThat(Settings.textSize(context)).isEqualTo(115)

        box.findViewById<View>(R.id.textSmaller)!!.performClick()
        box.findViewById<View>(R.id.textSmaller)!!.performClick()
        assertThat(now.text.toString()).isEqualTo("90%")
        assertThat(viewer.web.settings.textZoom).isEqualTo(90)
    }

    @Test
    fun aButtonStopsAtTheEndOfTheSizes() {
        Settings.setTextSize(context, Settings.TEXT_SIZES.last())
        val viewer = book().get()
        viewer.pageSays("page 1 9")
        val box = textSizeBox(viewer)
        val larger = box.findViewById<View>(R.id.textLarger)!!
        val smaller = box.findViewById<View>(R.id.textSmaller)!!
        assertThat(larger.isEnabled).isFalse()
        assertThat(smaller.isEnabled).isTrue()

        repeat(Settings.TEXT_SIZES.size - 1) { smaller.performClick() }
        assertThat(Settings.textSize(context)).isEqualTo(Settings.TEXT_SIZES.first())
        assertThat(smaller.isEnabled).isFalse()
        assertThat(larger.isEnabled).isTrue()
    }

    /** A size from a build with other steps is not one of these, and reads as the book's own. */
    @Test
    fun aSizeThatIsNotOneOfTheStepsIsTheBooksOwn() {
        Settings.setTextSize(context, 123)
        assertThat(Settings.textSize(context)).isEqualTo(100)
    }

    /**
     * The page keeps its own words at the phone's size, so it is told what that is, and may
     * write them smaller than the engine's floor of 8 px, which large type needs it to.
     */
    @Test
    fun aBooksPageIsToldTheSizeThePhoneAsksFor() {
        val viewer = book().get()
        assertThat(shadowOf(viewer.web).lastLoadedUrl).contains("&type=${viewer.web.settings.textZoom}")
        assertThat(viewer.web.settings.minimumFontSize).isEqualTo(1)
        assertThat(viewer.web.settings.minimumLogicalFontSize).isEqualTo(8)
        assertThat(shadowOf(open("six-pages.pdf").get().web).lastLoadedUrl).doesNotContain("&type=")
    }

    /** With the phone's font size turned up the WebView starts above 100, and the choice multiplies that. */
    @Test
    fun theChoiceMultipliesTheSizeThePhoneIsSetTo() {
        assertThat(readerTextZoom(phone = 130, chosen = 150, reflows = true)).isEqualTo(195)
        assertThat(readerTextZoom(phone = 100, chosen = 80, reflows = true)).isEqualTo(80)
        assertThat(readerTextZoom(phone = 130, chosen = 150, reflows = false)).isEqualTo(100)
    }
}
