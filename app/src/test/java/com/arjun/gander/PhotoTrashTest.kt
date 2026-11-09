package com.arjun.gander

import android.content.Context
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** What Gander asks of Android to delete a photo, issue #75. The viewer's part is in ViewerActivityTest. */
@RunWith(AndroidJUnit4::class)
class PhotoTrashTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tree = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:DCIM")
    private val photo = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:DCIM/a.jpg")

    @Test
    fun androidIsAskedFromAndroid11AndOnlyForAFileOnThePhonesStorage() {
        assertThat(mayTrash(photo, sdk = 30)).isTrue()
        assertThat(mayTrash(photo, sdk = 29)).isFalse()
        val cloud = DocumentsContract.buildTreeDocumentUri("org.example.cloud.documents", "pics")
        assertThat(mayTrash(DocumentsContract.buildDocumentUriUsingTree(cloud, "pics/a.jpg"), sdk = 36)).isFalse()
    }

    /** getMediaUri names the photo among all files, which the prompt refuses, so it's named among the pictures. */
    @Test
    fun thePromptIsAskedForThePhotosEntryAmongThePictures() {
        val media = FakeMediaStore.install().entry(photo, 42)

        assertThat(trashRequest(context, listOf(photo))).isNotNull()

        val (asked, values) = media.trashRequests.single()
        assertThat(asked).containsExactly(MediaStore.Images.Media.getContentUri(FakeMediaStore.VOLUME, 42))
        assertThat(values!!.getAsInteger(MediaStore.MediaColumns.IS_TRASHED)).isEqualTo(1)
    }

    /**
     * From Android 12 Gander may read the entry, and turns away a file the store doesn't count as
     * a picture. Android 11 lets it read nothing, so the prompt is opened and the move checked after.
     */
    @Test
    fun aFileTheStoreDoesNotCountAsAPictureIsTurnedAwayWhereGanderCanTell() {
        val media = FakeMediaStore.install().entry(photo, 42, MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)

        assertThat(trashRequest(context, listOf(photo), sdk = 31)).isNull()
        assertThat(media.trashRequests).isEmpty()
        assertThat(trashRequest(context, listOf(photo), sdk = 30)).isNotNull()
        // Nor is one the store doesn't list at all
        assertThat(trashRequest(context, listOf(DocumentsContract.buildDocumentUriUsingTree(tree, "primary:DCIM/new.jpg"))))
            .isNull()
    }

    /** One prompt covers every photo asked about, which is what picking many of them is for. */
    @Test
    fun onePromptIsAskedForEveryPhotoAndWhatIsNotAPictureIsLeftOutOfIt() {
        val b = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:DCIM/b.jpg")
        val hidden = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:DCIM/hidden.jpg")
        val unlisted = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:DCIM/new.jpg")
        val media = FakeMediaStore.install().entry(photo, 42).entry(b, 43)
            .entry(hidden, 44, MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)

        assertThat(trashRequest(context, listOf(photo, hidden, b, unlisted))).isNotNull()

        assertThat(media.trashRequests.single().first).containsExactly(
            MediaStore.Images.Media.getContentUri(FakeMediaStore.VOLUME, 42),
            MediaStore.Images.Media.getContentUri(FakeMediaStore.VOLUME, 43),
        ).inOrder()
    }

    /** One photo keeps the words it had, and several are counted: those that went, and those that stayed. */
    @Test
    fun whatIsSaidAfterwardsCountsThePhotosThatWentAndThoseThatStayed() {
        assertThat(trashWord(context, gone = 1, stayed = 0)).isEqualTo(context.getString(R.string.photo_trashed))
        assertThat(trashWord(context, gone = 0, stayed = 1)).isEqualTo(context.getString(R.string.photo_not_trashed))
        assertThat(trashWord(context, gone = 3, stayed = 0)).isEqualTo("3 photos moved to the bin")
        assertThat(trashWord(context, gone = 0, stayed = 2)).isEqualTo("2 photos could not be moved to the bin")
        assertThat(trashWord(context, gone = 2, stayed = 1)).isEqualTo("2 photos moved to the bin. 1 photo could not be moved to the bin")
    }
}
