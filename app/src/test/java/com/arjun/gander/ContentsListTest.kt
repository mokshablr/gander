package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * A PDF's or a Word document's contents list, issue #79: which entry the reader is in, and the
 * list as it is shown. What the pages send is checked in tests/viewer/test_contents.py, and how
 * it is read in PortMessageTest.
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

    private fun AlertDialog.rows() =
        (0 until listView.adapter.count).map { listView.adapter.getView(it, null, listView) }

    @Test
    fun theMenuShowsTheListWithTheEntryTheReaderIsInMarked() {
        val viewer = openPdf()
        viewer.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_contents, 0)
        shadowOf(Looper.getMainLooper()).idle()
        val box = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(box.isShowing).isTrue()
        val rows = box.rows()
        assertThat(rows.map { it.findViewById<TextView>(R.id.contents_title).text.toString() })
            .containsExactly("Front matter", "Chapter 1", "Section 1.1", "Section 1.2", "On the web", "Chapter 2")
            .inOrder()
        // Before the document says where it is, the readout is at nought and nothing is marked
        assertThat(rows.none { it.findViewById<TextView>(R.id.contents_title).typeface?.isBold == true }).isTrue()
        assertThat(rows[1].findViewById<TextView>(R.id.contents_page).text.toString()).isEqualTo("2")
        assertThat(rows[2].paddingStart).isGreaterThan(rows[1].paddingStart)
    }

    @Test
    fun anEntryWithNoPageIsListedAndCannotBeTapped() {
        val viewer = openPdf()
        var went = 0
        ContentsList.show(viewer, outline, 4, 6) { went = it }
        val box = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(box.listView.adapter.isEnabled(4)).isFalse()
        assertThat(box.listView.adapter.isEnabled(5)).isTrue()
        assertThat(box.rows()[4].findViewById<TextView>(R.id.contents_page).visibility)
            .isEqualTo(android.view.View.GONE)
        // The one the reader is in, on page 4
        assertThat(box.rows()[3].findViewById<TextView>(R.id.contents_title).typeface?.style)
            .isEqualTo(Typeface.BOLD)
        assertThat(went).isEqualTo(0)
    }

    @Test
    fun tappingAnEntryGoesToItsPageAndClosesTheList() {
        val viewer = openPdf()
        var went = 0
        ContentsList.show(viewer, outline, 1, 6) { went = it }
        val box = ShadowDialog.getLatestDialog() as AlertDialog
        val list = box.listView
        list.performItemClick(list.adapter.getView(5, null, list), 5, 5)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(went).isEqualTo(5)
        assertThat(box.isShowing).isFalse()
    }

    /** A page past the end is the outline's mistake, and goes nowhere. */
    @Test
    fun anEntryPastTheLastPageCannotBeTapped() {
        val viewer = openPdf()
        ContentsList.show(viewer, listOf(ContentsEntry(0, 2, "Real"), ContentsEntry(0, 40, "Gone")), 1, 6) { }
        val box = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(box.listView.adapter.isEnabled(1)).isFalse()
    }
}
