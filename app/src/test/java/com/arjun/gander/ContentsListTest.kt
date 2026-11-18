package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * A document's contents list, issue #79: which entry the reader is in, and the list as it is
 * shown, full screen, for a PDF, a Word document and a book alike. What the pages send is checked
 * in tests/viewer/test_contents.py and test_epub.py, and how it is read in PortMessageTest.
 */
@RunWith(AndroidJUnit4::class)
class ContentsListTest {

    @get:Rule val closing = ClosesActivities()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
    }

    private val outline = listOf(
        ContentsEntry(0, 1, "Front matter"),
        ContentsEntry(0, 2, "Chapter 1"),
        ContentsEntry(1, 2, "Section 1.1"),
        ContentsEntry(1, 4, "Section 1.2"),
        ContentsEntry(0, 0, "On the web"),
        ContentsEntry(0, 5, "Chapter 2"),
    )

    @Test
    fun theReaderIsInTheLastEntryOnTheLatestPageNotPastThem() {
        assertThat(ContentsList.current(outline, 1)).isEqualTo(0)
        // Of the two on page 2, the one under the other
        assertThat(ContentsList.current(outline, 2)).isEqualTo(2)
        assertThat(ContentsList.current(outline, 3)).isEqualTo(2)
        assertThat(ContentsList.current(outline, 4)).isEqualTo(3)
        assertThat(ContentsList.current(outline, 6)).isEqualTo(5)
    }

    /** A book says which chapter is on screen and not how far in, so of a chapter's entries the first. */
    @Test
    fun inABookTheReaderIsInTheFirstEntryOfTheirChapter() {
        assertThat(ContentsList.current(outline, 2, firstOfTies = true)).isEqualTo(1)
        assertThat(ContentsList.current(outline, 3, firstOfTies = true)).isEqualTo(1)
        assertThat(ContentsList.current(outline, 4, firstOfTies = true)).isEqualTo(3)
    }

    @Test
    fun beforeTheFirstEntryTheReaderIsInNone() {
        assertThat(ContentsList.current(listOf(ContentsEntry(0, 3, "Chapter 1")), 2)).isEqualTo(-1)
        assertThat(ContentsList.current(outline, 0)).isEqualTo(-1)
    }

    /** An outline need not be in page order, and the reader is placed by page all the same. */
    @Test
    fun anOutlineOutOfOrderIsReadByPage() {
        val shuffled = listOf(ContentsEntry(0, 9, "Index"), ContentsEntry(0, 3, "Chapter 1"))
        assertThat(ContentsList.current(shuffled, 5)).isEqualTo(1)
    }

    private fun openPdf(): ViewerActivity {
        val uri = FixtureProvider.uriFor("six-pages.pdf")
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
        return closing.build(ViewerActivity::class.java, intent).setup().get().apply {
            // Only a real WebView says these
            pageTotal = 6
            listedContents = outline
        }
    }

    private fun AppCompatDialog.list() = findViewById<ListView>(R.id.contents_list)!!

    private fun AppCompatDialog.bar() = findViewById<MaterialToolbar>(R.id.contents_bar)!!

    private fun AppCompatDialog.rows() =
        list().let { list -> (0 until list.adapter.count).map { list.adapter.getView(it, null, list) } }

    private fun View.title() = findViewById<TextView>(R.id.contents_title)

    private fun View.page() = findViewById<TextView>(R.id.contents_page)

    @Test
    fun theMenuShowsTheListFullScreen() {
        val viewer = openPdf()
        viewer.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_contents, 0)
        shadowOf(Looper.getMainLooper()).idle()
        val screen = ShadowDialog.getLatestDialog() as AppCompatDialog
        assertThat(screen.isShowing).isTrue()
        assertThat(screen.window!!.attributes.width).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
        assertThat(screen.window!!.attributes.height).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
        assertThat(screen.bar().title.toString()).isEqualTo(context.getString(R.string.contents))
        val rows = screen.rows()
        assertThat(rows.map { it.title().text.toString() })
            .containsExactly("Front matter", "Chapter 1", "Section 1.1", "Section 1.2", "On the web", "Chapter 2")
            .inOrder()
        // Before the document says where it is, the readout is at nought and nothing is marked
        assertThat(rows.none { it.title().typeface?.isBold == true }).isTrue()
        assertThat(rows[1].page().text.toString()).isEqualTo("2")
        assertThat(rows[2].paddingStart).isGreaterThan(rows[1].paddingStart)
    }

    @Test
    fun theBackArrowClosesTheList() {
        val viewer = openPdf()
        val screen = ContentsList.show(viewer, outline, 1, 6, byChapter = false) { }
        assertThat(screen.bar().navigationContentDescription.toString()).isEqualTo(context.getString(R.string.back))
        val arrow = (0 until screen.bar().childCount).map { screen.bar().getChildAt(it) }
            .single { it.contentDescription == context.getString(R.string.back) }
        arrow.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(screen.isShowing).isFalse()
    }

    @Test
    fun anEntryThatGoesNowhereIsListedAndCannotBeTapped() {
        val viewer = openPdf()
        var went = -1
        val screen = ContentsList.show(viewer, outline, 4, 6, byChapter = false) { went = it }
        val adapter = screen.list().adapter
        assertThat(adapter.isEnabled(4)).isFalse()
        assertThat(adapter.isEnabled(5)).isTrue()
        assertThat(screen.rows()[4].page().visibility).isEqualTo(View.GONE)
        // The one the reader is in, on page 4
        assertThat(screen.rows()[3].title().typeface?.style).isEqualTo(Typeface.BOLD)
        assertThat(went).isEqualTo(-1)
    }

    @Test
    fun tappingAnEntryHandsOnItsNumberInTheListAndCloses() {
        val viewer = openPdf()
        var went = -1
        val screen = ContentsList.show(viewer, outline, 1, 6, byChapter = false) { went = it }
        val list = screen.list()
        list.performItemClick(list.adapter.getView(5, null, list), 5, 5)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(went).isEqualTo(5)
        assertThat(screen.isShowing).isFalse()
    }

    /** A place past the end is the list's mistake, and goes nowhere. */
    @Test
    fun anEntryPastTheLastPageCannotBeTapped() {
        val viewer = openPdf()
        val screen = ContentsList.show(
            viewer,
            listOf(ContentsEntry(0, 2, "Real"), ContentsEntry(0, 40, "Gone")),
            1,
            6,
            byChapter = false,
        ) { }
        assertThat(screen.list().adapter.isEnabled(1)).isFalse()
    }

    /** A book's place is a chapter's number in reading order, which is no page and is not shown. */
    @Test
    fun aBooksEntriesShowNoPage() {
        val viewer = openPdf()
        val screen = ContentsList.show(viewer, outline, 2, Int.MAX_VALUE, byChapter = true) { }
        assertThat(screen.rows().map { it.page().visibility }.distinct()).containsExactly(View.GONE)
        assertThat(screen.rows()[1].title().typeface?.style).isEqualTo(Typeface.BOLD)
    }

    /** As the book's own list read them (#54): its way, the side it is set in from, and its language. */
    @Test
    fun anEntryReadsTheWayAndInTheLanguageItSays() {
        val viewer = openPdf()
        val arabic = "الأول"
        val screen = ContentsList.show(
            viewer,
            listOf(
                ContentsEntry(0, 1, arabic, "auto", "ar"),
                ContentsEntry(1, 1, "English first", "rtl", ""),
                ContentsEntry(1, 1, "English", "ltr", "ja"),
                ContentsEntry(0, 1, "Plain", "auto", ""),
            ),
            1,
            6,
            byChapter = true,
        ) { }
        val rows = screen.rows()
        assertThat(rows.map { it.layoutDirection }).containsExactly(
            View.LAYOUT_DIRECTION_RTL,
            View.LAYOUT_DIRECTION_RTL,
            View.LAYOUT_DIRECTION_LTR,
            View.LAYOUT_DIRECTION_LTR,
        ).inOrder()
        assertThat(rows.map { it.title().textDirection }).containsExactly(
            View.TEXT_DIRECTION_FIRST_STRONG,
            View.TEXT_DIRECTION_RTL,
            View.TEXT_DIRECTION_LTR,
            View.TEXT_DIRECTION_FIRST_STRONG,
        ).inOrder()
        assertThat(rows[0].title().textLocale).isEqualTo(Locale.forLanguageTag("ar"))
        assertThat(rows[2].title().textLocale).isEqualTo(Locale.forLanguageTag("ja"))
        assertThat(rows[3].title().textLocale).isEqualTo(Locale.getDefault())
    }
}
