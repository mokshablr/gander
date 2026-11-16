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

    /** Where videos or audio were among them, they are files. One that went is "Moved to the bin" either way. */
    @Test
    fun whatIsSaidAfterwardsCallsThemFilesWhereNotAllWerePhotos() {
        assertThat(trashWord(context, gone = 1, stayed = 0, photos = false)).isEqualTo(context.getString(R.string.photo_trashed))
        assertThat(trashWord(context, gone = 0, stayed = 1, photos = false)).isEqualTo("The file could not be moved to the bin")
        assertThat(trashWord(context, gone = 3, stayed = 0, photos = false)).isEqualTo("3 files moved to the bin")
        assertThat(trashWord(context, gone = 2, stayed = 1, photos = false))
            .isEqualTo("2 files moved to the bin. 1 file could not be moved to the bin")
    }

    private fun file(name: String) = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:DCIM/$name")

    /** With Delete for good on, the same entries are asked to be deleted outright, and nothing is asked to go to the bin. */
    @Test
    fun deletingForGoodAsksForTheSameEntriesToBeDeletedOutright() {
        val media = FakeMediaStore.install()
            .entry(photo, 42)
            .entry(file("clip.mp4"), 43, MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            .entry(file("notes.pdf"), 45, MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT)

        assertThat(trashRequest(context, listOf(photo, file("clip.mp4"), file("notes.pdf")), sdk = 31, forGood = true))
            .isNotNull()

        assertThat(media.trashRequests).isEmpty()
        assertThat(media.deleteRequests.single()).containsExactly(
            MediaStore.Images.Media.getContentUri(FakeMediaStore.VOLUME, 42),
            MediaStore.Video.Media.getContentUri(FakeMediaStore.VOLUME, 43),
        ).inOrder()
    }

    @Test
    fun whatIsSaidAfterADeleteForGoodSaysDeleted() {
        assertThat(trashWord(context, gone = 1, stayed = 0, forGood = true)).isEqualTo("Deleted")
        assertThat(trashWord(context, gone = 0, stayed = 1, forGood = true)).isEqualTo("The photo could not be deleted")
        assertThat(trashWord(context, gone = 0, stayed = 1, photos = false, forGood = true)).isEqualTo("The file could not be deleted")
        assertThat(trashWord(context, gone = 3, stayed = 0, forGood = true)).isEqualTo("3 photos deleted")
        assertThat(trashWord(context, gone = 2, stayed = 1, photos = false, forGood = true))
            .isEqualTo("2 files deleted. 1 file could not be deleted")
    }

    /**
     * A video or audio file is asked for under the store's videos or audio, which the prompt
     * takes as it takes pictures. A document is left out: Android's prompt refuses anything but
     * media, "All requested items must be Media items" (measured on Android 17, 16 Nov 2026).
     */
    @Test
    fun videosAndAudioAreAskedForUnderTheirOwnCollectionsAndDocumentsAreLeftOut() {
        val media = FakeMediaStore.install()
            .entry(photo, 42)
            .entry(file("clip.mp4"), 43, MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            .entry(file("song.mp3"), 44, MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)
            .entry(file("notes.pdf"), 45, MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT)

        assertThat(trashRequest(context, listOf(photo, file("clip.mp4"), file("notes.pdf"), file("song.mp3")), sdk = 31))
            .isNotNull()

        assertThat(media.trashRequests.single().first).containsExactly(
            MediaStore.Images.Media.getContentUri(FakeMediaStore.VOLUME, 42),
            MediaStore.Video.Media.getContentUri(FakeMediaStore.VOLUME, 43),
            MediaStore.Audio.Media.getContentUri(FakeMediaStore.VOLUME, 44),
        ).inOrder()
    }

    /** Android 11 lets Gander read nothing of the store's entry, so there the file's name says which collection. */
    @Test
    fun onAndroid11TheNameSaysWhichCollection() {
        val none = MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
        val media = FakeMediaStore.install()
            .entry(file("clip.mp4"), 43, none)
            .entry(file("song.m4a"), 44, none)
            .entry(file("notes.pdf"), 45, none)

        assertThat(trashRequest(context, listOf(file("clip.mp4"), file("song.m4a"), file("notes.pdf")), sdk = 30)).isNotNull()

        assertThat(media.trashRequests.single().first).containsExactly(
            MediaStore.Video.Media.getContentUri(FakeMediaStore.VOLUME, 43),
            MediaStore.Audio.Media.getContentUri(FakeMediaStore.VOLUME, 44),
        ).inOrder()
    }

    @Test
    fun theNameAndTypeSayWhatAndroidsBinWouldTake() {
        assertThat(binnedAs("a.JPG", null)).isEqualTo(Binned.PICTURE)
        assertThat(binnedAs("a.gif", null)).isEqualTo(Binned.PICTURE)
        assertThat(binnedAs("clip.mkv", null)).isEqualTo(Binned.VIDEO)
        assertThat(binnedAs("song.flac", null)).isEqualTo(Binned.AUDIO)
        // A .ts is TypeScript unless its folder says it is a video
        assertThat(binnedAs("main.ts", null)).isNull()
        assertThat(binnedAs("rec.ts", "video/mp2t")).isEqualTo(Binned.VIDEO)
        assertThat(binnedAs("notes.pdf", "application/pdf")).isNull()
        assertThat(binnedAs("readme.txt", null)).isNull()
        assertThat(allPictures(listOf(photo, file("b.png")))).isTrue()
        assertThat(allPictures(listOf(photo, file("clip.mp4")))).isFalse()
    }
}
