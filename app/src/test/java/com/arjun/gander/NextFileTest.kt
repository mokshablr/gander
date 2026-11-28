package com.arjun.gander

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** Next file and Previous file, issue #87: which files they go to, and how the viewer offers them. */
@RunWith(AndroidJUnit4::class)
class NextFileTest {

    @get:Rule val closing = ClosesActivities()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Settings.setSortOrder(context, Settings.SortOrder.NAME_AZ)
    }

    @After
    fun tearDown() = Settings.setSortOrder(context, Settings.SortOrder.NAME_AZ)

    private val folder = listOf(
        ChildDoc("d/f.txt", "f.txt", "text/plain", 60, 6),
        ChildDoc("d/b.md", "b.md", "text/markdown", 20, 2),
        ChildDoc("d/c.jpg", "c.jpg", "image/jpeg", 30, 3),
        ChildDoc("d/sub", "sub", MIME_DIR, 0, 9),
        ChildDoc("d/a.pdf", "a.pdf", "application/pdf", 10, 1),
        ChildDoc("d/d.zip", "d.zip", "application/zip", 40, 4),
        ChildDoc("d/.e.txt", ".e.txt", "text/plain", 45, 5),
        ChildDoc("d/e.xyz", "e.xyz", "application/octet-stream", 50, 5),
        ChildDoc("d/clip.mp4", "clip.mp4", "video/mp4", 35, 7),
    )

    private fun around(docId: String, order: Settings.SortOrder = Settings.SortOrder.NAME_AZ) =
        neighboursIn(folder, docId, order)!!.let { it.previous?.name to it.next?.name }

    /** Past a photo, a folder, a zip, a hidden file and one of no format Gander knows, in the home screen's order. */
    @Test
    fun theFilesEitherSideAreTheNearestOnesGanderOpens() {
        assertThat(around("d/a.pdf")).isEqualTo(null to "b.md")
        assertThat(around("d/b.md")).isEqualTo("a.pdf" to "clip.mp4")
        assertThat(around("d/clip.mp4")).isEqualTo("b.md" to "f.txt")
        assertThat(around("d/f.txt")).isEqualTo("clip.mp4" to null)
    }

    @Test
    fun theyFollowTheOrderTheFolderIsSortedIn() {
        assertThat(around("d/b.md", Settings.SortOrder.NAME_ZA)).isEqualTo("clip.mp4" to "a.pdf")
        assertThat(around("d/b.md", Settings.SortOrder.NEWEST)).isEqualTo("f.txt" to "a.pdf")
        assertThat(around("d/clip.mp4", Settings.SortOrder.LARGEST)).isEqualTo("f.txt" to "b.md")
    }

    /** A file Gander can only show a card for is still somewhere to step on from. */
    @Test
    fun aFileNotSteppedToStillHasFilesEitherSide() {
        assertThat(around("d/e.xyz")).isEqualTo("clip.mp4" to "f.txt")
        assertThat(neighboursIn(folder, "d/gone.txt", Settings.SortOrder.NAME_AZ)).isNull()
        assertThat(neighboursIn(listOf(folder[0]), "d/f.txt", Settings.SortOrder.NAME_AZ)!!.any).isFalse()
    }

    // ---------------------------------------------------------------
    // In the viewer
    // ---------------------------------------------------------------

    private fun docsFolder(vararg children: ChildDoc = folder.toTypedArray()): Uri {
        val docs = FakeDocumentsProvider.install().folder("d", "d", *children)
        children.forEach {
            val fixture = when (it.name.substringAfterLast('.')) {
                "mp4" -> "tone.wav"
                "jpg" -> "exif-1.jpg"
                else -> "plain.txt"
            }
            docs.contents(it.docId, Fixtures.bytes(fixture))
        }
        return FakeDocumentsProvider.treeUri("d")
    }

    private fun openedFrom(tree: Uri, docId: String): ActivityController<ViewerActivity> {
        val intent = Intent()
            .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
            .setData(DocumentsContract.buildDocumentUriUsingTree(tree, docId))
        val controller = closing.build(ViewerActivity::class.java, intent)
        controller.get().documentLoader = Executor { it.run() }
        return controller.setup().also { shadowOf(Looper.getMainLooper()).idle() }
    }

    private val ViewerActivity.menu get() = findViewById<MaterialToolbar>(R.id.toolbar).menu

    private fun ViewerActivity.offers(id: Int): Boolean? = menu.findItem(id).let { if (!it.isVisible) null else it.isEnabled }

    @Test
    fun aDocumentFromAnAddedFolderStepsToTheNextFileInAViewerOfItsOwn() {
        val tree = docsFolder()
        val viewer = openedFrom(tree, "d/b.md").get()
        assertThat(viewer.offers(R.id.action_next_file)).isTrue()
        assertThat(viewer.offers(R.id.action_previous_file)).isTrue()

        viewer.menu.performIdentifierAction(R.id.action_next_file, 0)

        val started = shadowOf(viewer).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(ViewerActivity.INTERNAL_VIEWER)
        assertThat(started.data).isEqualTo(DocumentsContract.buildDocumentUriUsingTree(tree, "d/clip.mp4"))
        assertThat(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        // In place of this one, so Back goes to the folder rather than through every file seen
        assertThat(viewer.isFinishing).isTrue()
    }

    @Test
    fun previousFileGoesBack() {
        val tree = docsFolder()
        val viewer = openedFrom(tree, "d/b.md").get()
        viewer.menu.performIdentifierAction(R.id.action_previous_file, 0)
        assertThat(shadowOf(viewer).nextStartedActivity.data)
            .isEqualTo(DocumentsContract.buildDocumentUriUsingTree(tree, "d/a.pdf"))
    }

    /** Greyed rather than gone, so the reader can see it is the end of the folder and not a missing feature. */
    @Test
    fun theLastFileHasNextFileGreyed() {
        val viewer = openedFrom(docsFolder(), "d/f.txt").get()
        assertThat(viewer.offers(R.id.action_next_file)).isFalse()
        assertThat(viewer.offers(R.id.action_previous_file)).isTrue()

        viewer.menu.performIdentifierAction(R.id.action_next_file, 0)
        assertThat(shadowOf(viewer).nextStartedActivity).isNull()
        assertThat(viewer.isFinishing).isFalse()
    }

    @Test
    fun nothingIsOfferedForAFileAloneInItsFolderOrAPhoto() {
        val alone = openedFrom(docsFolder(folder[0]), "d/f.txt").get()
        assertThat(alone.offers(R.id.action_next_file)).isNull()
        assertThat(alone.offers(R.id.action_previous_file)).isNull()

        // A folder's photos are swiped through instead
        val photo = openedFrom(docsFolder(), "d/c.jpg").get()
        assertThat(photo.offers(R.id.action_next_file)).isNull()
    }

    /** Opened any other way, Android shares that one file with Gander and no folder with it. */
    @Test
    fun aFileFromAnotherAppHasNoNextFile() {
        val uri = DocumentsContract.buildDocumentUriUsingTree(docsFolder(), "d/b.md")
        val intent = Intent(context, ViewerActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown")
        val controller = closing.build(ViewerActivity::class.java, intent)
        controller.get().documentLoader = Executor { it.run() }
        val viewer = controller.setup().also { shadowOf(Looper.getMainLooper()).idle() }.get()
        assertThat(viewer.offers(R.id.action_next_file)).isNull()
    }

    private val ViewerActivity.player: Player
        get() = findViewById<FrameLayout>(R.id.container).children.filterIsInstance<PlayerView>().single().player!!

    /** A video's or a track's own Previous and Next do it, rather than menu items beside them. */
    @Test
    fun aVideoStepsWithThePlayersOwnButtons() {
        val tree = docsFolder()
        val viewer = openedFrom(tree, "d/clip.mp4").get()
        assertThat(viewer.offers(R.id.action_next_file)).isNull()
        val player = viewer.player
        assertThat(player).isInstanceOf(SteppingPlayer::class.java)
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
        // Not within the file, as a player's Previous does by default
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)).isFalse()

        player.seekToNext()

        assertThat(shadowOf(viewer).nextStartedActivity.data)
            .isEqualTo(DocumentsContract.buildDocumentUriUsingTree(tree, "d/f.txt"))
        assertThat(viewer.isFinishing).isTrue()
    }

    @Test
    fun aVideoAloneKeepsThePlayerAsItIs() {
        val viewer = openedFrom(docsFolder(folder[8]), "d/clip.mp4").get()
        assertThat(viewer.player).isNotInstanceOf(SteppingPlayer::class.java)
    }

    @Test
    fun theSteppingPlayerOffersOnlyTheWayThereIsAFileIn() {
        val inner = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        try {
            var went = 0
            val atTheEnd = SteppingPlayer(inner, previous = { went++ }, next = null)
            assertThat(atTheEnd.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)).isTrue()
            assertThat(atTheEnd.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()
            assertThat(atTheEnd.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)).isFalse()
            atTheEnd.seekToNext()
            atTheEnd.seekToPrevious()
            assertThat(went).isEqualTo(1)
        } finally {
            inner.release()
        }
    }
}
