package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The recents list.
 *
 * Two stores make it up and they are not the same store: the list itself is
 * JSON in SharedPreferences, and whether an entry may be shown is decided by
 * Android's own record of which URIs the app still holds a read grant for. An
 * entry whose grant is gone stays in preferences and disappears from the list,
 * which is the behaviour most of these pin.
 */
@RunWith(AndroidJUnit4::class)
class RecentsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        context.getSharedPreferences("recents", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** A URI the picker returned, which is the only kind that gets remembered. */
    private fun granted(name: String): Uri {
        val uri = FixtureProvider.uriFor(name)
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        return uri
    }

    private fun names() = Recents.all(context).map { it.name }

    // ---------------------------------------------------------------

    @Test
    fun aFreshInstallHasNoRecents() {
        assertThat(Recents.all(context)).isEmpty()
    }

    @Test
    fun theNewestOpenedFileComesFirst() {
        Recents.add(context, granted("a.pdf"), "First")
        Recents.add(context, granted("b.pdf"), "Second")
        Recents.add(context, granted("c.pdf"), "Third")
        assertThat(names()).containsExactly("Third", "Second", "First").inOrder()
    }

    /**
     * Opening something already in the list moves it to the top rather than
     * adding it twice, so a file opened often does not fill the screen.
     */
    @Test
    fun reopeningAFileMovesItUpWithoutDuplicating() {
        val first = granted("a.pdf")
        Recents.add(context, first, "First")
        Recents.add(context, granted("b.pdf"), "Second")
        Recents.add(context, first, "First")

        assertThat(names()).containsExactly("First", "Second").inOrder()
    }

    /** The name is refreshed on the way, in case the provider renamed it. */
    @Test
    fun reopeningPicksUpANewDisplayName() {
        val uri = granted("a.pdf")
        Recents.add(context, uri, "Old name")
        Recents.add(context, uri, "New name")
        assertThat(names()).containsExactly("New name")
    }

    @Test
    fun theListStopsAtTwentyFive() {
        (1..30).forEach { Recents.add(context, granted("f$it.pdf"), "File $it") }
        val all = Recents.all(context)
        assertThat(all).hasSize(25)
        // The newest are the ones kept
        assertThat(all.first().name).isEqualTo("File 30")
        assertThat(all.last().name).isEqualTo("File 6")
    }

    @Test
    fun removingAnEntryTakesItOut() {
        val uri = granted("a.pdf")
        Recents.add(context, uri, "First")
        Recents.add(context, granted("b.pdf"), "Second")

        Recents.remove(context, uri.toString())

        assertThat(names()).containsExactly("Second")
    }

    @Test
    fun removingSomethingThatIsNotThereChangesNothing() {
        Recents.add(context, granted("a.pdf"), "First")
        Recents.remove(context, "content://elsewhere/1")
        assertThat(names()).containsExactly("First")
    }

    private fun kept(uri: Uri) =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri }

    /** Nothing else opens a file by its grant, so an entry that goes gives it back. */

    /** A file picked on a build that could rename it was granted write access too. */
    @Test
    fun removingAnEntryGivesBackWriteAccessToo() {
        val uri = FixtureProvider.uriFor("write.pdf")
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        Recents.add(context, uri, "Written")
        Recents.remove(context, uri.toString())
        assertThat(context.contentResolver.persistedUriPermissions.map { it.uri }).doesNotContain(uri)
    }

    @Test
    fun removingAnEntryGivesBackItsAccess() {
        val uri = granted("a.pdf")
        Recents.add(context, uri, "First")
        val other = granted("b.pdf")
        Recents.add(context, other, "Second")

        Recents.remove(context, uri.toString())

        assertThat(kept(uri)).isFalse()
        assertThat(kept(other)).isTrue()
    }

    @Test
    fun anEntryPushedOffTheEndGivesBackItsAccess() {
        val uris = (1..26).map { granted("f$it.pdf").also { uri -> Recents.add(context, uri, "File") } }
        assertThat(kept(uris.first())).isFalse()
        assertThat(uris.drop(1).all(::kept)).isTrue()
    }

    // ---------------------------------------------------------------
    // The grant filter
    // ---------------------------------------------------------------

    /**
     * The list only ever shows files it can still open. A grant is released
     * when the reader removes a folder, and it can also be dropped by
     * Android when the provider's app is uninstalled or its data cleared.
     */
    @Test
    fun anEntryWhoseGrantIsGoneIsNotShown() {
        val kept = granted("a.pdf")
        val lost = granted("b.pdf")
        Recents.add(context, kept, "Kept")
        Recents.add(context, lost, "Lost")

        context.contentResolver.releasePersistableUriPermission(
            lost, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        assertThat(names()).containsExactly("Kept")
    }

    /**
     * And it is hidden rather than deleted, so a grant that comes back brings
     * the entry back with it.
     */
    @Test
    fun aHiddenEntryReturnsIfItsGrantDoes() {
        val uri = granted("a.pdf")
        Recents.add(context, uri, "Comes and goes")
        context.contentResolver.releasePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        assertThat(names()).isEmpty()

        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        assertThat(names()).containsExactly("Comes and goes")
    }

    /**
     * A file opened by "Open with" carries a one-off grant that cannot be
     * persisted, so it is never added at all. Nothing here to remember.
     */
    @Test
    fun aFileWithNoPersistedGrantIsNeverListed() {
        Recents.add(context, FixtureProvider.uriFor("a.pdf"), "Opened once")
        assertThat(Recents.all(context)).isEmpty()
    }

    // ---------------------------------------------------------------
    // Files in a folder the reader added (#82)
    // ---------------------------------------------------------------

    private fun addedFolder(id: String = "primary:Docs"): Uri =
        DocumentsContract.buildTreeDocumentUri("test.documents", id).also {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** A file as the folder's list opens it, by a URI under the folder's grant. */
    private fun inFolder(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    /** The folder's grant keeps its files open, so a file there is listed with no grant of its own. */
    @Test
    fun aFileInAnAddedFolderIsListedUnderTheFoldersGrant() {
        val tree = addedFolder()
        val file = inFolder(tree, "primary:Docs/Leases/lease.pdf")
        assertThat(Recents.inAddedFolder(context, file)).isTrue()

        Recents.add(context, file, "lease.pdf")

        assertThat(names()).containsExactly("lease.pdf")
        assertThat(kept(file)).isFalse()
        assertThat(Recents.folderOf(file)).isEqualTo(tree.toString())
    }

    @Test
    fun aFileInAFolderThatIsNotAddedIsNotInOne() {
        addedFolder("primary:Docs")
        val elsewhere = inFolder(DocumentsContract.buildTreeDocumentUri("test.documents", "primary:Music"), "primary:Music/a.mp3")
        assertThat(Recents.inAddedFolder(context, elsewhere)).isFalse()
        assertThat(Recents.inAddedFolder(context, granted("a.pdf"))).isFalse()
        assertThat(Recents.folderOf(FixtureProvider.uriFor("a.pdf"))).isNull()

        Recents.add(context, elsewhere, "a.mp3")
        assertThat(names()).isEmpty()
    }

    @Test
    fun aFolderFileGoesFromTheListWhenItsFolderDoes() {
        val tree = addedFolder()
        Recents.add(context, inFolder(tree, "primary:Docs/lease.pdf"), "lease.pdf")
        Recents.add(context, granted("a.pdf"), "Picked")

        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        assertThat(names()).containsExactly("Picked")
    }

    /** A grant and a file can spell the folder differently, here its colon bare or escaped; it is still one folder. */
    @Test
    fun aFolderIsMatchedHoweverItsGrantSpellsIt() {
        val tree = Uri.parse("content://test.documents/tree/primary:Docs")
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val file = inFolder(tree, "primary:Docs/lease.pdf")
        Recents.add(context, file, "lease.pdf")

        assertThat(names()).containsExactly("lease.pdf")
        Recents.forgetFolder(context, tree)
        assertThat(names()).isEmpty()
    }

    /** Removing the folder forgets its files, so adding it again does not bring old rows back. */
    @Test
    fun forgettingAFolderDropsOnlyItsOwnFiles() {
        val tree = addedFolder("primary:Docs")
        val other = addedFolder("primary:Music")
        Recents.add(context, inFolder(tree, "primary:Docs/lease.pdf"), "lease.pdf")
        Recents.add(context, inFolder(other, "primary:Music/a.mp3"), "a.mp3")
        Recents.add(context, granted("a.pdf"), "Picked")

        Recents.forgetFolder(context, tree)

        assertThat(names()).containsExactly("Picked", "a.mp3").inOrder()
    }

    /** A folder file's entry has no grant of its own, and giving one back must not take the folder's. */
    @Test
    fun removingAFolderFileLeavesTheFoldersGrant() {
        val tree = addedFolder()
        val file = inFolder(tree, "primary:Docs/lease.pdf")
        Recents.add(context, file, "lease.pdf")

        Recents.remove(context, file.toString())

        assertThat(names()).isEmpty()
        assertThat(kept(tree)).isTrue()
    }

    @Test
    fun aFolderFilePushedOffTheEndLeavesTheFoldersGrant() {
        val tree = addedFolder()
        (1..26).forEach { Recents.add(context, inFolder(tree, "primary:Docs/f$it.pdf"), "File $it") }
        assertThat(Recents.all(context)).hasSize(25)
        assertThat(kept(tree)).isTrue()
    }

    // ---------------------------------------------------------------

    /**
     * Preferences are a file on disk and files get corrupted. Losing the list
     * is survivable; crashing on the home screen every launch, with no way
     * back, is not.
     */
    @Test
    fun aCorruptStoreReadsAsEmptyRatherThanThrowing() {
        listOf("not json at all", "{}", "[{\"uri\":1}]", "[", "[{}]").forEach { junk ->
            context.getSharedPreferences("recents", Context.MODE_PRIVATE)
                .edit().putString("items", junk).commit()
            assertThat(Recents.all(context)).isEmpty()
        }
    }

    /** And the next write puts it right. */
    @Test
    fun aCorruptStoreIsRepairedByTheNextOpen() {
        context.getSharedPreferences("recents", Context.MODE_PRIVATE)
            .edit().putString("items", "not json").commit()

        Recents.add(context, granted("a.pdf"), "After the damage")

        assertThat(names()).containsExactly("After the damage")
    }

    @Test
    fun theEntryCarriesTheUriAndATimestamp() {
        val before = System.currentTimeMillis()
        val uri = granted("a.pdf")
        Recents.add(context, uri, "First")

        val entry = Recents.all(context).single()
        assertThat(entry.uri).isEqualTo(uri.toString())
        assertThat(entry.name).isEqualTo("First")
        assertThat(entry.time).isAtLeast(before)
    }
}
