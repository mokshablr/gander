package com.arjun.gander

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Which photos a folder's swipe goes through, and in what order. The swiping is in ViewerActivityTest. */
@RunWith(AndroidJUnit4::class)
class PhotoPagerTest {

    private val folder = listOf(
        ChildDoc("b", "b.jpg", "image/jpeg", 300, 3),
        ChildDoc("a", "A.png", "image/png", 500, 1),
        ChildDoc("t", "notes.txt", "text/plain", 50, 9),
        ChildDoc("h", ".hidden.jpg", "image/jpeg", 10, 10),
        ChildDoc("s", "shots", MIME_DIR, 0, 8),
        ChildDoc("g", "c.gif", "image/gif", 200, 2),
        ChildDoc("v", "clip.mp4", "video/mp4", 900, 7),
        ChildDoc("x", "scan.xyz", "image/x-scan", 400, 4),
    )

    private fun names(order: Settings.SortOrder) = photosIn(folder, order).map { it.name }

    /** Every picture Gander opens as one, a GIF and one known only by its type too, and nothing else. */
    @Test
    fun aFoldersPicturesComeInTheOrderTheHomeScreenListsThemIn() {
        assertThat(names(Settings.SortOrder.NAME_AZ)).containsExactly("A.png", "b.jpg", "c.gif", "scan.xyz").inOrder()
        assertThat(names(Settings.SortOrder.NAME_ZA)).containsExactly("scan.xyz", "c.gif", "b.jpg", "A.png").inOrder()
        assertThat(names(Settings.SortOrder.NEWEST)).containsExactly("scan.xyz", "b.jpg", "c.gif", "A.png").inOrder()
        assertThat(names(Settings.SortOrder.OLDEST)).containsExactly("A.png", "c.gif", "b.jpg", "scan.xyz").inOrder()
        assertThat(names(Settings.SortOrder.LARGEST)).containsExactly("A.png", "scan.xyz", "b.jpg", "c.gif").inOrder()
        assertThat(names(Settings.SortOrder.SMALLEST)).containsExactly("c.gif", "b.jpg", "scan.xyz", "A.png").inOrder()
    }

    @Test
    fun twoDocumentsShareAGrantOnlyWhenTheyComeFromOneTree() {
        val pictures = DocumentsContract.buildTreeDocumentUri("a.documents", "primary:Pictures")
        val one = DocumentsContract.buildDocumentUriUsingTree(pictures, "primary:Pictures/x.jpg")
        val two = DocumentsContract.buildDocumentUriUsingTree(pictures, "primary:Pictures/y.jpg")
        val dcim = DocumentsContract.buildTreeDocumentUri("a.documents", "primary:DCIM")
        val otherFolder = DocumentsContract.buildDocumentUriUsingTree(dcim, "primary:DCIM/y.jpg")
        val otherApp = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri("b.documents", "primary:Pictures"), "primary:Pictures/y.jpg"
        )
        val picked = DocumentsContract.buildDocumentUri("a.documents", "primary:Pictures/y.jpg")

        assertThat(inSameTree(one, two)).isTrue()
        assertThat(inSameTree(one, otherFolder)).isFalse()
        assertThat(inSameTree(one, otherApp)).isFalse()
        assertThat(inSameTree(one, picked)).isFalse()
    }
}
