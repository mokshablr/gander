package com.arjun.gander

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * The home screen: recents, granted folders, and the first-run explainer.
 *
 * The folder read normally happens on a background thread, which would make
 * every assertion here a race. [directExecutor] runs it inline instead, so a
 * test asserts on a screen that has finished drawing.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule val closing = ClosesActivities()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
        context.getSharedPreferences("recents", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.contentResolver.persistedUriPermissions.forEach {
            context.contentResolver.releasePersistableUriPermission(
                it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /**
     * Lets any Undo bar run out. Material queues bars in one object for the whole process, and a
     * bar left up at the end of a test would hold the next test's bar back until it had gone.
     */
    @After
    fun tearDown() {
        shadowOf(context.mainLooper).idleFor(Duration.ofSeconds(10))
    }

    /** Runs everything submitted to it on the calling thread, or as [direct] says. */
    private fun directExecutor(direct: Executor = Executor { it.run() }): ExecutorService {
        return object : ExecutorService, Executor by direct {
            override fun shutdown() = Unit
            override fun shutdownNow() = emptyList<Runnable>()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
            override fun <T : Any?> submit(task: java.util.concurrent.Callable<T>) =
                throw UnsupportedOperationException()
            override fun <T : Any?> submit(task: Runnable, result: T) =
                throw UnsupportedOperationException()
            override fun submit(task: Runnable) = throw UnsupportedOperationException()
            override fun <T : Any?> invokeAll(
                tasks: MutableCollection<out java.util.concurrent.Callable<T>>
            ) = throw UnsupportedOperationException()
            override fun <T : Any?> invokeAll(
                tasks: MutableCollection<out java.util.concurrent.Callable<T>>,
                timeout: Long,
                unit: TimeUnit
            ) = throw UnsupportedOperationException()
            override fun <T : Any?> invokeAny(
                tasks: MutableCollection<out java.util.concurrent.Callable<T>>
            ) = throw UnsupportedOperationException()
            override fun <T : Any?> invokeAny(
                tasks: MutableCollection<out java.util.concurrent.Callable<T>>,
                timeout: Long,
                unit: TimeUnit
            ) = throw UnsupportedOperationException()
        }
    }

    private fun home(state: Bundle? = null): ActivityController<MainActivity> {
        val controller = closing.build(MainActivity::class.java)
        controller.get().loader = directExecutor()
        return controller.create(state).start().resume().visible().also {
            shadowOf(context.mainLooper).idle()
        }
    }

    private fun ActivityController<MainActivity>.list(): RecyclerView =
        get().findViewById(R.id.list)

    private fun ActivityController<MainActivity>.welcome(): View =
        get().findViewById(R.id.welcome)

    /** Every row's title, read off the adapter through a bound holder. */
    private fun ActivityController<MainActivity>.rowTitles(): List<String> {
        val rv = list()
        val adapter = rv.adapter!!
        return (0 until adapter.itemCount).mapNotNull { position ->
            val type = adapter.getItemViewType(position)
            val holder = adapter.createViewHolder(rv, type)
            adapter.bindViewHolder(holder, position)
            holder.itemView.findViewById<TextView>(R.id.title)?.text?.toString()
                ?: holder.itemView.findViewById<TextView>(R.id.headerText)?.text?.toString()
        }
    }

    private fun granted(fixture: String, name: String) {
        val uri = FixtureProvider.uriFor(fixture)
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        Recents.add(context, uri, name)
    }

    // ---------------------------------------------------------------
    // First run
    // ---------------------------------------------------------------

    /**
     * Nothing opened and no folder granted, so there is nothing to list. The
     * explainer takes the whole screen rather than an empty list taking it.
     */
    @Test
    fun aFirstRunShowsTheExplainerInsteadOfAnEmptyList() {
        val controller = home()
        assertThat(controller.welcome().visibility).isEqualTo(View.VISIBLE)
        assertThat(controller.list().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun theExplainerShowsEveryKindGanderOpens() {
        val grid = home().get().findViewById<ViewGroup>(R.id.formatGrid)
        assertThat(grid.childCount).isEqualTo(WELCOME_BADGES.size)
        val labels = (0 until grid.childCount)
            .map { (grid.getChildAt(it) as TextView).text.toString() }
        assertThat(labels).containsExactlyElementsIn(WELCOME_BADGES.map { it.first }).inOrder()
    }

    /**
     * TalkBack reads one sentence for the grid rather than walking nine tiles
     * that mean nothing one at a time.
     */
    @Test
    fun theExplainerGridIsOneStopForAScreenReader() {
        val grid = home().get().findViewById<ViewGroup>(R.id.formatGrid)
        assertThat(grid.contentDescription.toString())
            .isEqualTo(context.getString(R.string.what_it_opens))
    }

    /**
     * Neither button opens a file from another app, so a first run is told that route too,
     * not only About.
     */
    @Test
    fun theExplainerSaysHowToOpenAFileFromAFileManager() {
        val tip = home().welcome().findViewById<TextView>(R.id.openWithHint)
        assertThat(tip.text.toString()).isEqualTo(context.getString(R.string.open_with_hint))
        assertThat(tip.isShown).isTrue()
    }

    @Test
    fun theExplainerGoesOnceSomethingHasBeenOpened() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val controller = home()
        assertThat(controller.welcome().visibility).isEqualTo(View.GONE)
        assertThat(controller.list().visibility).isEqualTo(View.VISIBLE)
    }

    /** The tip goes with the explainer, so the list ends with it instead. */
    @Test
    fun theListEndsWithTheOpenWithTipOnceTheExplainerHasGone() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val rv = home().list()
        val adapter = rv.adapter!!
        val last = adapter.itemCount - 1
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(last))
        adapter.bindViewHolder(holder, last)

        assertThat(holder.itemView.findViewById<TextView>(R.id.hintText)?.text?.toString())
            .isEqualTo(context.getString(R.string.open_with_hint))
    }

    // ---------------------------------------------------------------
    // Recents
    // ---------------------------------------------------------------

    @Test
    fun recentsAreListedNewestFirstUnderTheirOwnHeading() {
        granted("six-pages.pdf", "Alder Court.pdf")
        granted("budget.xlsx", "Q3 Budget.xlsx")

        val titles = home().rowTitles()

        assertThat(titles).contains(context.getString(R.string.recent_files))
        assertThat(titles.filter { it.contains('.') })
            .containsExactly("Q3 Budget.xlsx", "Alder Court.pdf").inOrder()
    }

    @Test
    fun aRecentWhoseGrantIsGoneDropsOffTheScreen() {
        granted("six-pages.pdf", "Alder Court.pdf")
        granted("budget.xlsx", "Q3 Budget.xlsx")
        context.contentResolver.releasePersistableUriPermission(
            FixtureProvider.uriFor("budget.xlsx"), Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val titles = home().rowTitles()

        assertThat(titles).contains("Alder Court.pdf")
        assertThat(titles).doesNotContain("Q3 Budget.xlsx")
    }

    /** Tapping a recent opens it in the viewer, with a read grant attached. */
    @Test
    fun tappingARecentOpensItInTheViewer() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val controller = home()
        val rv = controller.list()
        val adapter = rv.adapter!!

        val position = (0 until adapter.itemCount).first { position ->
            val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView.findViewById<TextView>(R.id.title)?.text?.toString() ==
                "Alder Court.pdf"
        }
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        holder.itemView.performClick()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertThat(started.component!!.className).isEqualTo(ViewerActivity.INTERNAL_VIEWER)
        assertThat(started.data.toString()).contains("six-pages.pdf")
        assertThat(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    /**
     * A back swipe shows this screen as it was last drawn on its way out, so a tap still
     * fading when a document opened came back lit. The press has to be gone by the pause;
     * clearing it on the way back in was tried on a phone and changed nothing.
     */
    @Test
    fun aTapHighlightIsGoneBeforeTheScreenLeaves() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val controller = home()
        val list = controller.list()
        val rows = (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isClickable }
        assertThat(rows).isNotEmpty()
        rows.forEach { it.isPressed = true }

        controller.pause()

        assertThat(rows.filter { it.isPressed }).isEmpty()
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    @Test
    fun aPhoneListsInOneColumn() {
        val manager = home().list().layoutManager as GridLayoutManager
        assertThat(manager.spanCount).isEqualTo(1)
    }

    /**
     * A tablet gets two columns of files, which is the whole of the tablet
     * layout: one integer overridden in values-sw600dp.
     */
    @Test
    @Config(qualifiers = "sw600dp")
    fun aTabletListsInTwoColumns() {
        val manager = home().list().layoutManager as GridLayoutManager
        assertThat(manager.spanCount).isEqualTo(2)
    }

    /**
     * Headings span the full width whatever the column count, and so does an
     * out-of-range position: the layout manager asks about positions mid-update
     * and a cell-shaped guess would throw where a heading-shaped one reflows.
     */
    @Test
    @Config(qualifiers = "sw600dp")
    fun headingsAndOutOfRangePositionsSpanTheWholeWidth() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val manager = home().list().layoutManager as GridLayoutManager
        // position 0 is the Recents heading
        assertThat(manager.spanSizeLookup.getSpanSize(0)).isEqualTo(2)
        assertThat(manager.spanSizeLookup.getSpanSize(9999)).isEqualTo(2)
    }

    // ---------------------------------------------------------------
    // State across a rotation
    // ---------------------------------------------------------------

    /** Grants a folder holding one subfolder and two files, and answers its tree URI. */
    private fun grantedFolder(write: Boolean = false): android.net.Uri {
        FakeDocumentsProvider.install()
            .folder(
                "root", "Documents",
                ChildDoc("sub", "Leases", MIME_DIR, 0, 0),
                ChildDoc("f1", "zeta.pdf", "application/pdf", 2048, 0),
                ChildDoc("f2", "alpha.pdf", "application/pdf", 1024, 0),
                ChildDoc("f3", ".hidden.pdf", "application/pdf", 512, 0),
            )
        val tree = FakeDocumentsProvider.treeUri()
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        )
        return tree
    }

    /** Clicks the first row whose title is [title]. */
    private fun ActivityController<MainActivity>.clickRow(title: String) {
        val rv = list()
        val adapter = rv.adapter!!
        val position = (0 until adapter.itemCount).first { position ->
            val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView.findViewById<TextView>(R.id.title)?.text?.toString() == title
        }
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        holder.itemView.performClick()
        shadowOf(context.mainLooper).idle()
    }

    @Test
    fun aGrantedFolderIsListedUnderItsProviderGivenName() {
        grantedFolder()
        val titles = home().rowTitles()
        assertThat(titles).contains(context.getString(R.string.folders))
        assertThat(titles).contains("Documents")
    }

    /** Directories first, then files by name, and dotfiles nowhere. */
    @Test
    fun openingAFolderListsItInOrderWithDotfilesHidden() {
        grantedFolder()
        val controller = home()
        controller.clickRow("Documents")

        val titles = controller.rowTitles()

        assertThat(titles).containsAtLeast("Leases", "alpha.pdf", "zeta.pdf").inOrder()
        assertThat(titles).doesNotContain(".hidden.pdf")
    }

    /** Inside a folder the toolbar names it, and the wordmark stands down. */
    @Test
    fun theToolbarNamesTheFolderYouAreIn() {
        grantedFolder()
        val controller = home()
        val toolbar = controller.get()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        assertThat(toolbar.title.isNullOrEmpty()).isTrue()

        controller.clickRow("Documents")

        assertThat(toolbar.title.toString()).isEqualTo("Documents")
        assertThat(controller.get().findViewById<View>(R.id.lockup).visibility)
            .isEqualTo(View.GONE)
    }

    @Test
    fun backComesOutOfAFolder() {
        grantedFolder()
        val controller = home()
        controller.clickRow("Documents")

        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()

        assertThat(controller.rowTitles()).contains(context.getString(R.string.folders))
    }

    /**
     * A tablet is rotated constantly, and losing your place three folders deep
     * on every turn is where this was found.
     */
    @Test
    fun theFolderYouAreInSurvivesARotation() {
        grantedFolder()
        val first = home()
        first.clickRow("Documents")

        val state = Bundle()
        first.saveInstanceState(state)
        assertThat(state.getStringArrayList("stack.treeUris")).hasSize(1)
        assertThat(state.getStringArrayList("stack.labels")).containsExactly("Documents")

        val second = home(state)

        val toolbar = second.get()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        assertThat(toolbar.title.toString()).isEqualTo("Documents")
        assertThat(second.rowTitles()).contains("alpha.pdf")
    }

    /**
     * A truncated bundle would index out of bounds while restoring three
     * parallel lists. Landing at the root is where a failure would land anyway.
     */
    @Test
    fun aTruncatedBundleLandsAtTheRootRatherThanCrashing() {
        val damaged = Bundle().apply {
            putStringArrayList("stack.treeUris", arrayListOf("content://a/tree/1", "content://a/tree/2"))
            putStringArrayList("stack.docIds", arrayListOf("1"))
            putStringArrayList("stack.labels", arrayListOf("One", "Two"))
        }
        val controller = home(damaged)
        assertThat(controller.welcome().visibility).isEqualTo(View.VISIBLE)
    }

    // ---------------------------------------------------------------
    // Removal
    // ---------------------------------------------------------------

    /** The row titled [title] as the list has laid it out on screen. */
    private fun ActivityController<MainActivity>.shownRow(title: String): View {
        val rv = list()
        return (0 until rv.childCount).map { rv.getChildAt(it) }.first {
            it.findViewById<TextView>(R.id.title)?.text?.toString() == title
        }
    }

    /**
     * Drags the row titled [title] towards the end by [fraction] of its width and lets go, as a
     * finger would, through the ItemTouchHelper the list really has. [stepMs] apart, the moves
     * are fast enough to count as a fling at 16 and too slow for one at 150. Answers how far the
     * row went while it was held.
     *
     * The list is drawn after every step, because the helper moves a row as it draws it, and
     * nothing draws here unless asked.
     */
    private fun ActivityController<MainActivity>.swipeRow(
        title: String,
        fraction: Float = 0.8f,
        stepMs: Long = 16,
    ): Float {
        val rv = list()
        val row = shownRow(title)
        val canvas = Canvas(Bitmap.createBitmap(rv.width, rv.height, Bitmap.Config.ARGB_8888))
        val y = row.top + row.height / 2f
        val from = row.left + row.width * 0.1f
        val to = from + row.width * fraction
        val down = SystemClock.uptimeMillis()
        var time = down
        var furthest = 0f
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
            rv.dispatchTouchEvent(event)
            event.recycle()
            shadowOf(context.mainLooper).idleFor(Duration.ofMillis(stepMs))
            rv.draw(canvas)
            furthest = maxOf(furthest, abs(row.translationX))
            time += stepMs
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (step in 1..10) send(MotionEvent.ACTION_MOVE, from + (to - from) * step / 10)
        send(MotionEvent.ACTION_UP, to)
        // The row flies off, or back, and a swipe is reported once it has gone
        shadowOf(context.mainLooper).idleFor(Duration.ofSeconds(1))
        rv.draw(canvas)
        return furthest
    }

    private fun latestDialog(): AlertDialog? =
        ShadowDialog.getLatestDialog() as? AlertDialog

    private fun AlertDialog.title(): String =
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    private fun persistedUris(): List<String> =
        context.contentResolver.persistedUriPermissions.map { it.uri.toString() }

    private fun ActivityController<MainActivity>.undo(): View? =
        get().findViewById(com.google.android.material.R.id.snackbar_action)

    private fun answer(which: Int) {
        latestDialog()!!.getButton(which).performClick()
        shadowOf(context.mainLooper).idle()
    }

    /** Long enough for the Undo bar to have come and gone. */
    private fun waitOutTheUndoBar() {
        shadowOf(context.mainLooper).idleFor(Duration.ofSeconds(6))
    }

    /** Issue #39: holding a recent file removed it on the spot, and was easy to do by accident. */
    @Test
    fun holdingARowRemovesNothing() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val controller = home()

        controller.shownRow("Alder Court.pdf").performLongClick()
        shadowOf(context.mainLooper).idle()

        assertThat(latestDialog()).isNull()
        assertThat(controller.rowTitles()).contains("Alder Court.pdf")
    }

    /** Swiped past halfway, the row is asked about, and nothing has gone yet. */
    @Test
    fun swipingARecentAwayAsksFirst() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val controller = home()

        assertThat(controller.swipeRow("Alder Court.pdf")).isGreaterThan(0f)

        val dialog = latestDialog()
        assertThat(dialog?.isShowing).isTrue()
        assertThat(dialog!!.title()).isEqualTo(context.getString(R.string.remove_recent_title))
        assertThat(persistedUris()).contains(FixtureProvider.uriFor("six-pages.pdf").toString())
    }

    /** Let go short of halfway, and slowly, the row springs back and nothing is asked. */
    @Test
    fun aShortSwipeSpringsBack() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val controller = home()

        val moved = controller.swipeRow("Alder Court.pdf", fraction = 0.3f, stepMs = 150)

        assertThat(moved).isGreaterThan(0f)
        assertThat(latestDialog()).isNull()
        assertThat(controller.shownRow("Alder Court.pdf").translationX).isEqualTo(0f)
    }

    /**
     * Headings, hints and Add a folder stay put under a swipe. Removable rows offer Remove to a
     * screen reader, which cannot swipe a row, and the rest offer nothing.
     */
    @Test
    fun onlyRowsThatCanBeRemovedMove() {
        grantedFolder()
        val controller = home()

        assertThat(controller.swipeRow(context.getString(R.string.add_folder))).isEqualTo(0f)
        assertThat(latestDialog()).isNull()

        val dismiss = AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id
        val folder = controller.shownRow("Documents").createAccessibilityNodeInfo()!!
            .actionList.firstOrNull { it.id == dismiss }
        assertThat(folder?.label.toString()).isEqualTo(context.getString(R.string.remove))
        val add = controller.shownRow(context.getString(R.string.add_folder))
            .createAccessibilityNodeInfo()!!.actionList.firstOrNull { it.id == dismiss }
        assertThat(add).isNull()
    }

    /** Remove, from a screen reader's list of actions, asks as the swipe does. */
    @Test
    fun aScreenReaderCanRemoveARowToo() {
        grantedFolder()
        val controller = home()

        controller.shownRow("Documents").performAccessibilityAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id, null
        )
        shadowOf(context.mainLooper).idle()

        assertThat(latestDialog()!!.title()).isEqualTo(context.getString(R.string.remove_folder_title))
    }

    /**
     * Removed, the row goes at once, but the file's grant stays until the Undo bar has gone, since
     * Android cannot give one back.
     */
    @Test
    fun aRemovedRecentKeepsItsGrantUntilTheUndoBarGoes() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val file = FixtureProvider.uriFor("six-pages.pdf").toString()
        val controller = home()

        controller.swipeRow("Alder Court.pdf")
        answer(AlertDialog.BUTTON_POSITIVE)

        assertThat(controller.rowTitles()).doesNotContain("Alder Court.pdf")
        assertThat(controller.undo()?.isShown).isTrue()
        assertThat(persistedUris()).contains(file)

        waitOutTheUndoBar()

        assertThat(persistedUris()).doesNotContain(file)
        assertThat(Recents.all(context).map { it.name }).doesNotContain("Alder Court.pdf")
    }

    @Test
    fun undoPutsTheRowBackWithItsGrant() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val file = FixtureProvider.uriFor("six-pages.pdf").toString()
        val controller = home()
        controller.swipeRow("Alder Court.pdf")
        answer(AlertDialog.BUTTON_POSITIVE)

        controller.undo()!!.performClick()
        shadowOf(context.mainLooper).idle()
        waitOutTheUndoBar()

        assertThat(controller.rowTitles()).contains("Alder Court.pdf")
        assertThat(persistedUris()).contains(file)
    }

    /** A bar nobody is looking at can no longer be answered, so the removal goes through. */
    @Test
    fun leavingTheScreenCarriesARemovalOut() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val file = FixtureProvider.uriFor("six-pages.pdf").toString()
        val controller = home()
        controller.swipeRow("Alder Court.pdf")
        answer(AlertDialog.BUTTON_POSITIVE)

        controller.pause().stop()

        assertThat(persistedUris()).doesNotContain(file)
    }

    /** The file or folder stays where it is, and the grant with it, and so does the row. */
    @Test
    fun cancellingKeepsTheRowAndItsGrant() {
        val tree = grantedFolder()
        val controller = home()
        controller.swipeRow("Documents")

        answer(AlertDialog.BUTTON_NEGATIVE)
        shadowOf(context.mainLooper).idleFor(Duration.ofSeconds(1))
        val rv = controller.list()
        rv.draw(Canvas(Bitmap.createBitmap(rv.width, rv.height, Bitmap.Config.ARGB_8888)))

        assertThat(persistedUris()).contains(tree.toString())
        assertThat(controller.rowTitles()).contains("Documents")
        // Back in its place, not left off the side of the screen
        assertThat(controller.shownRow("Documents").translationX).isEqualTo(0f)
    }

    /**
     * A folder added on a build that could rename files was granted write access as well as read.
     * Removing it gave back the read half only, and the write half stayed, out of sight: the list
     * shows read grants alone, so nothing on screen could reach it again.
     */
    @Test
    fun removingAFolderGivesBackWriteAccessToo() {
        val tree = grantedFolder(write = true)
        val controller = home()
        controller.swipeRow("Documents")

        answer(AlertDialog.BUTTON_POSITIVE)
        waitOutTheUndoBar()

        assertThat(persistedUris()).doesNotContain(tree.toString())
        assertThat(controller.rowTitles()).doesNotContain("Documents")
    }

    /** A file opened from the folder, as it sits in Recents (#82). */
    private fun recentInFolder(tree: android.net.Uri): android.net.Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, "f1").also { Recents.add(context, it, "zeta.pdf") }

    /** The folder's files leave Recents with it, and adding the folder again does not bring them back. */
    @Test
    fun removingAFolderTakesItsFilesOutOfRecents() {
        val tree = grantedFolder()
        recentInFolder(tree)
        val controller = home()
        assertThat(controller.rowTitles()).contains("zeta.pdf")

        controller.swipeRow("Documents")
        answer(AlertDialog.BUTTON_POSITIVE)

        // Gone with the folder's row while its Undo bar is up
        assertThat(controller.rowTitles()).containsNoneOf("zeta.pdf", "Documents")
        waitOutTheUndoBar()
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertThat(Recents.all(context)).isEmpty()
    }

    @Test
    fun undoingAFoldersRemovalBringsItsFilesBackToRecents() {
        recentInFolder(grantedFolder())
        val controller = home()
        controller.swipeRow("Documents")
        answer(AlertDialog.BUTTON_POSITIVE)

        controller.undo()!!.performClick()
        shadowOf(context.mainLooper).idle()
        waitOutTheUndoBar()

        assertThat(controller.rowTitles()).containsAtLeast("zeta.pdf", "Documents")
        assertThat(Recents.all(context).map { it.name }).containsExactly("zeta.pdf")
    }

    /** A folder's file removed from Recents is still in its folder, so the question says so and the folder stays. */
    @Test
    fun removingAFolderFileFromRecentsKeepsTheFolder() {
        val tree = grantedFolder()
        val file = recentInFolder(tree)
        // Its small picture, which the folder's list still shows
        val thumb = File(checkNotNull(Thumbs.folderDir(context, tree)), md5(file.toString()) + ".png")
            .apply { parentFile!!.mkdirs(); writeBytes(Fixtures.bytes("tiny.png")) }
        Recents.add(context, DocumentsContract.buildDocumentUriUsingTree(tree, "f2"), "alpha.pdf")
        val controller = home()

        controller.swipeRow("zeta.pdf")
        assertThat(latestDialog()!!.findViewById<TextView>(android.R.id.message)!!.text.toString())
            .isEqualTo(context.getString(R.string.remove_recent_in_folder_message, "zeta.pdf"))
        answer(AlertDialog.BUTTON_POSITIVE)
        // Only that one waits on the Undo bar, not the folder's other files
        assertThat(controller.rowTitles()).contains("alpha.pdf")
        assertThat(controller.rowTitles()).doesNotContain("zeta.pdf")
        waitOutTheUndoBar()

        assertThat(Recents.all(context).map { it.name }).containsExactly("alpha.pdf")
        assertThat(persistedUris()).contains(tree.toString())
        assertThat(controller.rowTitles()).contains("Documents")
        assertThat(thumb.exists()).isTrue()
    }

    /** And the thumbnails of the files in it, each a copy of a picture in it, only smaller. */
    @Test
    fun removingAFolderTakesItsThumbnailsWithIt() {
        val tree = grantedFolder()
        val thumbs = checkNotNull(Thumbs.folderDir(context, tree))
        val thumb = File(thumbs, "f1.png").apply { parentFile!!.mkdirs(); writeBytes(Fixtures.bytes("tiny.png")) }
        val controller = home()
        controller.swipeRow("Documents")

        answer(AlertDialog.BUTTON_POSITIVE)
        waitOutTheUndoBar()

        assertThat(thumb.exists()).isFalse()
    }

    /**
     * Gander's own primary is a burnt red, so tinting only the destructive button left
     * the two within dE 4.6 of each other on the light palette: near enough to the 2.3 a
     * person can notice that the colour marked nothing and made Cancel look dangerous
     * too. Both roles are asserted, because the bug was the pair being alike rather than
     * either one being wrong.
     */
    @Test
    fun theDestructiveButtonDoesNotLookLikeTheDismissiveOne() {
        grantedFolder()
        val controller = home()
        controller.swipeRow("Documents")
        val dialog = latestDialog()!!

        val remove = dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE).currentTextColor
        assertThat(remove).isEqualTo(ContextCompat.getColor(context, R.color.gander_error))
        assertThat(cancel)
            .isEqualTo(ContextCompat.getColor(context, R.color.gander_on_surface_variant))
        assertThat(remove).isNotEqualTo(cancel)
    }

    // ---------------------------------------------------------------
    // About
    // ---------------------------------------------------------------

    /**
     * The About screen asks Android what the app requests and prints the
     * answer, rather than printing a claim. This is the assertion that the
     * answer is still "none".
     */
    @Test
    fun aboutReportsNoPermissionsBecauseThereAreNone() {
        val controller = home()
        controller.get().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.performIdentifierAction(R.id.action_about, 0)
        shadowOf(context.mainLooper).idle()

        val dialog = shadowOf(org.robolectric.shadows.ShadowDialog.getLatestDialog()).let {
            org.robolectric.shadows.ShadowDialog.getLatestDialog()
        }
        assertThat(dialog).isNotNull()
        val text = dialog.findViewById<TextView>(R.id.aboutPermissions).text.toString()
        assertThat(text).isEqualTo(context.getString(R.string.about_permissions_none))
    }

    /** About's File types row closes About and opens the page as it ships, in Gander's viewer. */
    @Test
    fun theFileTypesRowOpensThePageInTheViewer() {
        val controller = home()
        controller.get().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.performIdentifierAction(R.id.action_about, 0)
        shadowOf(context.mainLooper).idle()

        val dialog = ShadowDialog.getLatestDialog()
        dialog.findViewById<View>(R.id.aboutFileTypes).performClick()
        shadowOf(context.mainLooper).idle()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertThat(started.component!!.className).isEqualTo(ViewerActivity.INTERNAL_VIEWER)
        val page = File(started.getStringExtra(ViewerActivity.EXTRA_PATH)!!)
        assertThat(page.name).isEqualTo(context.getString(R.string.file_types_file_name))
        val shipped = context.assets.open("file_types.md").use { it.readBytes().decodeToString() }
        assertThat(page.readText()).isEqualTo(shipped)
        assertThat(dialog.isShowing).isFalse()
    }

    // ---------------------------------------------------------------
    // Google Play
    // ---------------------------------------------------------------

    private fun ActivityController<MainActivity>.playItem(): android.view.MenuItem =
        get().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.findItem(R.id.action_play)

    /**
     * A copy installed from an APK is offered the switch to Play, whose listing updates it
     * in place, and is never asked to rate, since Play takes ratings only from its own copies.
     */
    @Test
    fun aCopyFromGitHubIsOfferedTheSwitchToPlay() {
        shadowOf(context.packageManager).setInstallSourceInfo(
            context.packageName, "com.android.chrome", "com.google.android.packageinstaller"
        )
        val item = home().playItem()

        assertThat(item.isVisible).isTrue()
        assertThat(item.title.toString()).isEqualTo(context.getString(R.string.switch_to_play))
    }

    /** Google Play's own copy keeps Rate in the same place. */
    @Test
    fun playsOwnCopyIsAskedToRate() {
        shadowOf(context.packageManager).setInstallSourceInfo(
            context.packageName, "com.android.vending", "com.android.vending"
        )
        val item = home().playItem()

        assertThat(item.isVisible).isTrue()
        assertThat(item.title.toString()).isEqualTo(context.getString(R.string.rate_app))
    }

    /** The switch opens Gander's listing in the Play Store app, where Update does the move. */
    @Test
    fun theSwitchOpensGandersListingInThePlayStore() {
        val controller = home()
        controller.get().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.performIdentifierAction(R.id.action_play, 0)

        val started = shadowOf(controller.get()).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(started.data.toString()).isEqualTo("market://details?id=${context.packageName}")
        assertThat(started.`package`).isEqualTo("com.android.vending")
    }

    // ---------------------------------------------------------------
    // Folder Sort and Filter
    // ---------------------------------------------------------------

    @Test
    fun menuShowsHomeOptionsAtHomeAndFolderOptionsInFolder() {
        grantedFolder()
        val controller = home()
        val toolbar = controller.get().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)

        // At home: share and about visible, sort and filter hidden
        assertThat(toolbar.menu.findItem(R.id.action_share_app).isVisible).isTrue()
        assertThat(toolbar.menu.findItem(R.id.action_about).isVisible).isTrue()
        assertThat(toolbar.menu.findItem(R.id.action_sort).isVisible).isFalse()
        assertThat(toolbar.menu.findItem(R.id.action_filter).isVisible).isFalse()

        // Inside folder: sort and filter visible, share and about hidden
        controller.clickRow("Documents")
        shadowOf(context.mainLooper).idle()

        assertThat(toolbar.menu.findItem(R.id.action_sort).isVisible).isTrue()
        assertThat(toolbar.menu.findItem(R.id.action_filter).isVisible).isTrue()
        assertThat(toolbar.menu.findItem(R.id.action_share_app).isVisible).isFalse()
        assertThat(toolbar.menu.findItem(R.id.action_about).isVisible).isFalse()
        assertThat(toolbar.menu.findItem(R.id.action_play).isVisible).isFalse()

        // Back to home: home options restored
        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()

        assertThat(toolbar.menu.findItem(R.id.action_share_app).isVisible).isTrue()
        assertThat(toolbar.menu.findItem(R.id.action_about).isVisible).isTrue()
        assertThat(toolbar.menu.findItem(R.id.action_sort).isVisible).isFalse()
        assertThat(toolbar.menu.findItem(R.id.action_filter).isVisible).isFalse()
    }

    @Test
    fun sortDialogChangesSortAndPersists() {
        grantedFolder()
        val controller = home()
        controller.clickRow("Documents")
        shadowOf(context.mainLooper).idle()

        val toolbar = controller.get().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.menu.performIdentifierAction(R.id.action_sort, 0)
        shadowOf(context.mainLooper).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(dialog).isNotNull()

        // Select LARGEST (index 4)
        val which = Settings.SortOrder.LARGEST.ordinal
        dialog.listView.performItemClick(null, which, which.toLong())
        shadowOf(context.mainLooper).idle()

        // Check persistence
        assertThat(Settings.sortOrder(context)).isEqualTo(Settings.SortOrder.LARGEST)

        // Zeta (2048) comes before alpha (1024) when sorted by size descending
        val titles = controller.rowTitles()
        assertThat(titles).containsAtLeast("zeta.pdf", "alpha.pdf").inOrder()
    }

    @Test
    fun filterDialogFiltersFilesShowsSubtitleAndClearsOnHome() {
        FakeDocumentsProvider.install()
            .folder(
                "root", "Documents",
                ChildDoc("f1", "doc1.pdf", "application/pdf", 1024, 0),
                ChildDoc("f2", "notes.txt", "text/plain", 512, 0),
                ChildDoc("f3", "sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 2048, 0),
            )
        val tree = FakeDocumentsProvider.treeUri()
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val controller = home()
        controller.clickRow("Documents")
        shadowOf(context.mainLooper).idle()

        assertThat(controller.rowTitles()).containsAtLeast("doc1.pdf", "notes.txt", "sheet.xlsx")

        val toolbar = controller.get().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.menu.performIdentifierAction(R.id.action_filter, 0)
        shadowOf(context.mainLooper).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val container = dialog.findViewById<ViewGroup>(R.id.filterItemsContainer)!!

        // Find the PDF row and check it
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            val badge = row.findViewById<TextView>(R.id.filterBadge)
            if (badge.text == "PDF") {
                row.performClick()
            }
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(context.mainLooper).idle()

        // Only PDF file should be shown and subtitle indicates active filter
        assertThat(controller.rowTitles()).contains("doc1.pdf")
        assertThat(controller.rowTitles()).doesNotContain("notes.txt")
        assertThat(controller.rowTitles()).doesNotContain("sheet.xlsx")
        assertThat(toolbar.subtitle?.toString()).isEqualTo("Filtered: PDF")

        // Going back to home screen clears the filter and subtitle
        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()

        assertThat(toolbar.subtitle).isNull()

        // Re-entering folder has filter cleared
        controller.clickRow("Documents")
        shadowOf(context.mainLooper).idle()
        assertThat(toolbar.subtitle).isNull()
        assertThat(controller.rowTitles()).containsAtLeast("doc1.pdf", "notes.txt", "sheet.xlsx")

        // Set filter again and test saving instance state across rotation
        toolbar.menu.performIdentifierAction(R.id.action_filter, 0)
        shadowOf(context.mainLooper).idle()
        val dialog2 = ShadowDialog.getLatestDialog() as AlertDialog
        val container2 = dialog2.findViewById<ViewGroup>(R.id.filterItemsContainer)!!
        container2.getChildAt(0).performClick()
        dialog2.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(context.mainLooper).idle()

        val state = Bundle()
        controller.saveInstanceState(state)
        assertThat(state.getStringArrayList("active_filters")).containsExactly("PDF")

        val second = home(state)
        val secondToolbar = second.get().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        assertThat(secondToolbar.subtitle?.toString()).isEqualTo("Filtered: PDF")
        assertThat(second.rowTitles()).contains("doc1.pdf")
        assertThat(second.rowTitles()).doesNotContain("notes.txt")
        assertThat(second.rowTitles()).doesNotContain("sheet.xlsx")
    }

    // ---------------------------------------------------------------
    // Searching a folder by name (#80)
    // ---------------------------------------------------------------

    private fun ActivityController<MainActivity>.searchBar(): View = get().findViewById(R.id.folderSearchBar)

    private fun ActivityController<MainActivity>.searchBox(): android.widget.EditText =
        get().findViewById(R.id.folderSearchInput)

    private fun ActivityController<MainActivity>.search(typed: String) {
        bar().menu.performIdentifierAction(R.id.action_search, 0)
        searchBox().setText(typed)
        shadowOf(context.mainLooper).idle()
    }

    @Test
    fun searchNarrowsAFoldersRowsByNameAndReadsTheFolderOnce() {
        val books = FakeDocumentsProvider.install()
            .folder(
                "root", "Books",
                ChildDoc("d1", "Tolstoy", MIME_DIR, 0, 0),
                ChildDoc("d2", "Pushkin", MIME_DIR, 0, 0),
                ChildDoc("f1", "Tolstoy - War and Peace (1869).fb2", "application/octet-stream", 10, 0),
                ChildDoc("f2", "Chekhov - Stories.epub", "application/epub+zip", 10, 0),
                ChildDoc("f3", "Résumé.pdf", "application/pdf", 10, 0),
            )
        context.contentResolver.takePersistableUriPermission(
            FakeDocumentsProvider.treeUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val controller = home()
        assertThat(controller.bar().menu.findItem(R.id.action_search).isVisible).isFalse()
        controller.clickRow("Books")
        assertThat(controller.bar().menu.findItem(R.id.action_search).isVisible).isTrue()
        assertThat(controller.searchBar().visibility).isEqualTo(View.GONE)
        val reads = books.childReads

        controller.search("tols")

        // Folders and files alike, and the button gives way to the bar it opened
        assertThat(controller.rowTitles()).containsExactly("Tolstoy", "Tolstoy - War and Peace (1869).fb2").inOrder()
        assertThat(controller.searchBar().visibility).isEqualTo(View.VISIBLE)
        assertThat(controller.bar().menu.findItem(R.id.action_search).isVisible).isFalse()
        controller.searchBox().setText("resume")
        shadowOf(context.mainLooper).idle()
        assertThat(controller.rowTitles()).containsExactly("Résumé.pdf")
        // Each letter narrows what was read, rather than asking the provider again
        assertThat(books.childReads).isEqualTo(reads)

        controller.searchBox().setText("dostoevsky")
        shadowOf(context.mainLooper).idle()
        val rv = controller.list()
        val adapter = rv.adapter!!
        assertThat(adapter.itemCount).isEqualTo(1)
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        assertThat(holder.itemView.findViewById<TextView>(R.id.hintText)?.text?.toString())
            .isEqualTo(context.getString(R.string.search_empty, "dostoevsky"))
    }

    /** A fresh read overtaken by a letter typed is done by the letter's render, not dropped. */
    @Test
    fun aLetterTypedBeforeAFreshReadDrawsReadsTheFolderAgain() {
        val books = FakeDocumentsProvider.install()
            .folder("root", "Books", ChildDoc("f1", "old notes.txt", "text/plain", 10, 0))
        context.contentResolver.takePersistableUriPermission(
            FakeDocumentsProvider.treeUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val held = ArrayDeque<Runnable>()
        var holding = false
        val controller = home()
        controller.get().loader = directExecutor { if (holding) held += it else it.run() }
        controller.clickRow("Books")
        // A file arrives while a document is open, and the reader types as soon as they are back
        books.folder(
            "root", "Books",
            ChildDoc("f1", "old notes.txt", "text/plain", 10, 0),
            ChildDoc("f2", "new notes.txt", "text/plain", 10, 0),
        )
        holding = true
        controller.pause().resume()
        controller.search("notes")
        holding = false
        while (held.isNotEmpty()) held.removeFirst().run()
        shadowOf(context.mainLooper).idle()

        assertThat(controller.rowTitles()).containsExactly("new notes.txt", "old notes.txt").inOrder()
    }

    @Test
    fun backClosesTheSearchBeforeLeavingTheFolder() {
        grantedFolder()
        val controller = home()
        controller.clickRow("Documents")
        controller.search("zeta")
        assertThat(controller.rowTitles()).containsExactly("zeta.pdf")

        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()

        assertThat(controller.barTitle()).isEqualTo("Documents")
        assertThat(controller.searchBar().visibility).isEqualTo(View.GONE)
        assertThat(controller.searchBox().text.toString()).isEmpty()
        assertThat(controller.rowTitles()).containsExactly("Leases", "alpha.pdf", "zeta.pdf").inOrder()
        assertThat(controller.bar().menu.findItem(R.id.action_search).isVisible).isTrue()

        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()
        assertThat(controller.list().visibility).isEqualTo(View.VISIBLE)
        assertThat(controller.bar().menu.findItem(R.id.action_search).isVisible).isFalse()
    }

    @Test
    fun goingIntoAFolderLeavesTheSearchBehind() {
        grantedFolder()
        val controller = home()
        controller.clickRow("Documents")
        controller.search("lea")
        controller.clickRow("Leases")

        assertThat(controller.barTitle()).isEqualTo("Leases")
        assertThat(controller.searchBar().visibility).isEqualTo(View.GONE)
        assertThat(controller.searchBox().text.toString()).isEmpty()
    }

    @Test
    fun aSearchSurvivesARotation() {
        grantedFolder()
        val controller = home()
        controller.clickRow("Documents")
        controller.search("alp")

        val state = Bundle()
        controller.saveInstanceState(state)
        val second = home(state)

        assertThat(second.searchBar().visibility).isEqualTo(View.VISIBLE)
        assertThat(second.searchBox().text.toString()).isEqualTo("alp")
        assertThat(second.rowTitles()).containsExactly("alpha.pdf")
    }

    /** A name changed while photos are picked could hide one that is picked, so the box holds still. */
    @Test
    fun theSearchHoldsStillWhilePhotosArePicked() {
        grantedPhotos()
        val controller = home()
        controller.clickRow("DCIM")
        controller.search(".jpg")
        assertThat(controller.rowTitles()).containsExactly("a.jpg", "b.jpg").inOrder()

        assertThat(controller.holdRow("a.jpg")).isTrue()
        assertThat(controller.searchBar().visibility).isEqualTo(View.VISIBLE)
        assertThat(controller.searchBox().isEnabled).isFalse()
        assertThat(controller.get().findViewById<View>(R.id.folderSearchClose).visibility).isEqualTo(View.INVISIBLE)

        // Back ends the picking first, and the search is still there
        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()
        assertThat(controller.barTitle()).isEqualTo("DCIM")
        assertThat(controller.searchBox().isEnabled).isTrue()
        assertThat(controller.rowTitles()).containsExactly("a.jpg", "b.jpg").inOrder()
    }

    // ---------------------------------------------------------------
    // Picking several of a folder's photos to delete together (#75)
    // ---------------------------------------------------------------

    private lateinit var docs: FakeDocumentsProvider
    private lateinit var media: FakeMediaStore

    private fun photoUri(tree: Uri, name: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, "primary:DCIM/$name")

    /**
     * Grants a folder on the phone's own storage, which Android's trash reaches: a folder, a PDF
     * and three photos its media store lists as 11, 12 and 13.
     */
    private fun grantedPhotos(): Uri {
        docs = FakeDocumentsProvider.install(EXTERNAL_STORAGE_AUTHORITY)
            .folder(
                "primary:DCIM", "DCIM",
                ChildDoc("primary:DCIM/Trip", "Trip", MIME_DIR, 0, 0),
                ChildDoc("primary:DCIM/a.jpg", "a.jpg", "image/jpeg", 10, 1_000),
                ChildDoc("primary:DCIM/b.jpg", "b.jpg", "image/jpeg", 20, 2_000),
                ChildDoc("primary:DCIM/c.png", "c.png", "image/png", 30, 3_000),
                ChildDoc("primary:DCIM/notes.pdf", "notes.pdf", "application/pdf", 40, 4_000),
            )
        val tree = FakeDocumentsProvider.treeUri("primary:DCIM", EXTERNAL_STORAGE_AUTHORITY)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        media = FakeMediaStore.install()
        listOf("a.jpg", "b.jpg", "c.png").forEachIndexed { i, name -> media.entry(photoUri(tree, name), 11L + i) }
        return tree
    }

    private fun ActivityController<MainActivity>.bar(): MaterialToolbar = get().findViewById(R.id.toolbar)

    private fun ActivityController<MainActivity>.barTitle(): String = bar().title.toString()

    /** The row titled [title], bound as the list would draw it now. */
    private fun ActivityController<MainActivity>.boundRow(title: String): View {
        val rv = list()
        val adapter = rv.adapter!!
        return (0 until adapter.itemCount).firstNotNullOf { position ->
            val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView.takeIf { it.findViewById<TextView>(R.id.title)?.text?.toString() == title }
        }
    }

    /**
     * Holds the row titled [title] down, and answers whether the row took it. A row that takes
     * no hold isn't asked: unattached as it is here, it would look for a parent to offer a menu.
     */
    private fun ActivityController<MainActivity>.holdRow(title: String): Boolean {
        val row = boundRow(title)
        return (row.isLongClickable && row.performLongClick()).also { shadowOf(context.mainLooper).idle() }
    }

    /** What a screen reader is told of the row titled [title] while photos are picked, or null. */
    private fun ActivityController<MainActivity>.said(title: String): String? =
        ViewCompat.getStateDescription(boundRow(title))?.toString()

    private fun ActivityController<MainActivity>.tapBin(): ShadowActivity.IntentSenderRequest? {
        bar().menu.performIdentifierAction(R.id.action_delete, 0)
        shadowOf(context.mainLooper).idle()
        return shadowOf(get()).lastIntentSenderRequest
    }

    private fun ActivityController<MainActivity>.answer(prompt: ShadowActivity.IntentSenderRequest, result: Int) {
        assertThat(get().activityResultRegistry.dispatchResult(prompt.requestCode, result, null)).isTrue()
        shadowOf(context.mainLooper).idle()
    }

    private fun picture(id: Long): Uri = MediaStore.Images.Media.getContentUri(FakeMediaStore.VOLUME, id)

    private val selected get() = context.getString(R.string.selected)
    private val notSelected get() = context.getString(R.string.not_selected)

    @Test
    fun holdingAPhotoStartsPickingAndTapsPickMoreOrLetGo() {
        grantedPhotos()
        val controller = home()
        controller.clickRow("DCIM")
        assertThat(controller.said("a.jpg")).isNull()
        assertThat(controller.boundRow("a.jpg").findViewById<View>(R.id.pick).visibility).isEqualTo(View.GONE)

        assertThat(controller.holdRow("a.jpg")).isTrue()

        assertThat(controller.barTitle()).isEqualTo("1 selected")
        assertThat(controller.said("a.jpg")).isEqualTo(selected)
        assertThat(controller.said("b.jpg")).isEqualTo(notSelected)
        assertThat(controller.boundRow("b.jpg").findViewById<View>(R.id.pick).visibility).isEqualTo(View.VISIBLE)
        assertThat(controller.bar().navigationContentDescription).isEqualTo(context.getString(R.string.stop_selecting))
        assertThat(controller.bar().menu.findItem(R.id.action_delete).isVisible).isTrue()
        assertThat(controller.bar().menu.findItem(R.id.action_sort).isVisible).isFalse()
        assertThat(controller.bar().menu.findItem(R.id.action_filter).isVisible).isFalse()
        assertThat(controller.bar().menu.findItem(R.id.action_select).isVisible).isFalse()
        // Open a file would leave the photos picked behind
        assertThat(controller.get().findViewById<View>(R.id.openFab).visibility).isNotEqualTo(View.VISIBLE)

        controller.clickRow("c.png")
        assertThat(controller.barTitle()).isEqualTo("2 selected")
        assertThat(controller.said("c.png")).isEqualTo(selected)
        controller.clickRow("a.jpg")
        assertThat(controller.barTitle()).isEqualTo("1 selected")
        assertThat(controller.said("a.jpg")).isEqualTo(notSelected)
        // Held down once picking has started, a photo is picked or let go as by a tap
        assertThat(controller.holdRow("b.jpg")).isTrue()
        assertThat(controller.barTitle()).isEqualTo("2 selected")
        assertThat(controller.said("b.jpg")).isEqualTo(selected)
        controller.holdRow("b.jpg")
        assertThat(controller.barTitle()).isEqualTo("1 selected")
        // And no viewer opened for any of the taps
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
    }

    /** What can't be picked stands back while photos are: it shows no mark, and a tap on it goes nowhere. */
    @Test
    fun whatIsNotAPhotoStandsBackWhilePhotosArePicked() {
        grantedPhotos()
        val controller = home()
        controller.clickRow("DCIM")
        // Holding one down starts nothing
        assertThat(controller.holdRow("notes.pdf")).isFalse()
        assertThat(controller.holdRow("Trip")).isFalse()
        assertThat(controller.barTitle()).isEqualTo("DCIM")
        assertThat(controller.boundRow("notes.pdf").alpha).isEqualTo(1f)
        controller.holdRow("b.jpg")

        for (row in listOf("notes.pdf", "Trip")) {
            assertThat(controller.boundRow(row).alpha).isLessThan(0.5f)
            assertThat(controller.boundRow(row).findViewById<View>(R.id.pick).visibility).isEqualTo(View.GONE)
            assertThat(controller.said(row)).isNull()
            controller.clickRow(row)
        }
        assertThat(controller.boundRow("a.jpg").alpha).isEqualTo(1f)

        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
        assertThat(controller.barTitle()).isEqualTo("1 selected")
        assertThat(controller.rowTitles()).contains("notes.pdf")
    }

    @Test
    fun thePhotosPickedGoToTheBinWithOnePromptAndLeaveTheList() {
        val tree = grantedPhotos()
        // Their small pictures, as the list keeps them, and one of a photo that stays
        val thumbs = checkNotNull(Thumbs.folderDir(context, tree)).apply { mkdirs() }
        fun thumb(name: String) = File(thumbs, md5(photoUri(tree, name).toString()) + ".png")
        listOf("a.jpg", "b.jpg", "c.png").forEach { thumb(it).writeBytes(Fixtures.bytes("tiny.png")) }
        // Two of them opened before, which put them in Recents
        listOf("a.jpg", "b.jpg").forEach { Recents.add(context, photoUri(tree, it), it) }
        val controller = home()
        controller.clickRow("DCIM")
        controller.holdRow("a.jpg")
        controller.clickRow("c.png")

        val prompt = controller.tapBin()!!
        docs.remove("primary:DCIM/a.jpg")
        docs.remove("primary:DCIM/c.png")
        controller.answer(prompt, Activity.RESULT_OK)

        assertThat(media.trashRequests.single().first).containsExactly(picture(11), picture(13)).inOrder()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("2 photos moved to the bin")
        assertThat(controller.rowTitles()).containsExactly("Trip", "b.jpg", "notes.pdf").inOrder()
        // The picking is over, and the bar is the folder's again
        assertThat(controller.barTitle()).isEqualTo("DCIM")
        assertThat(controller.said("b.jpg")).isNull()
        assertThat(controller.bar().menu.findItem(R.id.action_delete).isVisible).isFalse()
        assertThat(controller.bar().menu.findItem(R.id.action_sort).isVisible).isTrue()
        // A photo that has gone leaves no small copy of itself behind
        assertThat(thumb("a.jpg").exists()).isFalse()
        assertThat(thumb("c.png").exists()).isFalse()
        assertThat(thumb("b.jpg").exists()).isTrue()
        // Nor a row in Recents
        assertThat(Recents.all(context).map { it.name }).containsExactly("b.jpg")
    }

    /** A no in the prompt moves nothing, and a yes that moved nothing says so. Either way the photos stay picked. */
    @Test
    fun photosThatStayAreStillPicked() {
        grantedPhotos()
        val controller = home()
        controller.clickRow("DCIM")
        controller.holdRow("a.jpg")
        controller.clickRow("b.jpg")

        controller.answer(controller.tapBin()!!, Activity.RESULT_CANCELED)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
        assertThat(controller.barTitle()).isEqualTo("2 selected")

        controller.answer(controller.tapBin()!!, Activity.RESULT_OK)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("2 photos could not be moved to the bin")
        assertThat(controller.barTitle()).isEqualTo("2 selected")
        assertThat(controller.rowTitles()).containsAtLeast("a.jpg", "b.jpg")
    }

    @Test
    fun backEndsThePickingAndStaysInTheFolder() {
        grantedPhotos()
        val controller = home()
        controller.clickRow("DCIM")
        controller.holdRow("a.jpg")

        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()

        assertThat(controller.barTitle()).isEqualTo("DCIM")
        assertThat(controller.said("a.jpg")).isNull()
        assertThat(controller.bar().navigationContentDescription).isEqualTo(context.getString(R.string.back))
        assertThat(controller.get().findViewById<View>(R.id.openFab).visibility).isEqualTo(View.VISIBLE)
        // A tap opens a photo again
        controller.clickRow("a.jpg")
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNotNull()

        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()
        assertThat(controller.rowTitles()).contains(context.getString(R.string.folders))
    }

    /**
     * Videos and audio are picked as photos are and go to the bin in the same prompt, each asked
     * for under its own collection. A document can't be: Android's prompt takes only media.
     */
    @Test
    fun videosAndAudioArePickedWithPhotosAndGoToTheBinInTheSamePrompt() {
        docs = FakeDocumentsProvider.install(EXTERNAL_STORAGE_AUTHORITY)
            .folder(
                "primary:Media", "Media",
                ChildDoc("primary:Media/a.jpg", "a.jpg", "image/jpeg", 10, 1_000),
                ChildDoc("primary:Media/clip.mp4", "clip.mp4", "video/mp4", 20, 2_000),
                ChildDoc("primary:Media/notes.pdf", "notes.pdf", "application/pdf", 30, 3_000),
                ChildDoc("primary:Media/song.mp3", "song.mp3", "audio/mpeg", 40, 4_000),
            )
        val tree = FakeDocumentsProvider.treeUri("primary:Media", EXTERNAL_STORAGE_AUTHORITY)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        fun inMedia(tree: Uri, name: String) = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Media/$name")
        media = FakeMediaStore.install()
            .entry(inMedia(tree, "a.jpg"), 11)
            .entry(inMedia(tree, "clip.mp4"), 12, MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            .entry(inMedia(tree, "notes.pdf"), 13, MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT)
            .entry(inMedia(tree, "song.mp3"), 14, MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)
        val controller = home()
        controller.clickRow("Media")
        assertThat(controller.bar().menu.findItem(R.id.action_select).title).isEqualTo(context.getString(R.string.select_files))
        assertThat(controller.holdRow("notes.pdf")).isFalse()

        assertThat(controller.holdRow("clip.mp4")).isTrue()
        controller.clickRow("song.mp3")
        controller.clickRow("notes.pdf")
        controller.clickRow("a.jpg")
        assertThat(controller.barTitle()).isEqualTo("3 selected")

        val prompt = controller.tapBin()!!
        listOf("a.jpg", "clip.mp4", "song.mp3").forEach { docs.remove("primary:Media/$it") }
        controller.answer(prompt, Activity.RESULT_OK)

        assertThat(media.trashRequests.single().first).containsExactly(
            MediaStore.Video.Media.getContentUri(FakeMediaStore.VOLUME, 12),
            MediaStore.Audio.Media.getContentUri(FakeMediaStore.VOLUME, 14),
            picture(11),
        ).inOrder()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("3 files moved to the bin")
        assertThat(controller.rowTitles()).containsExactly("notes.pdf")
    }

    /**
     * Delete for good sits in the menu of a folder with things to delete, checked while it is on,
     * and is kept. With it on, the bin asks Android to delete the picked photos outright.
     */
    @Test
    fun deleteForGoodInTheMenuIsKeptAndDeletesThePickedPhotosOutright() {
        grantedPhotos()
        val controller = home()
        val toggle = { controller.bar().menu.findItem(R.id.action_delete_for_good) }
        assertThat(toggle().isVisible).isFalse()
        controller.clickRow("DCIM")
        assertThat(toggle().isVisible).isTrue()
        assertThat(toggle().isChecked).isFalse()

        controller.bar().menu.performIdentifierAction(R.id.action_delete_for_good, 0)
        shadowOf(context.mainLooper).idle()
        assertThat(toggle().isChecked).isTrue()
        assertThat(Settings.deleteForGood(context)).isTrue()

        // Still there while picking, to be seen just before the bin
        controller.holdRow("a.jpg")
        controller.clickRow("c.png")
        assertThat(toggle().isVisible).isTrue()
        val prompt = controller.tapBin()!!
        docs.remove("primary:DCIM/a.jpg")
        docs.remove("primary:DCIM/c.png")
        controller.answer(prompt, Activity.RESULT_OK)

        assertThat(media.trashRequests).isEmpty()
        assertThat(media.deleteRequests.single()).containsExactly(picture(11), picture(13)).inOrder()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("2 photos deleted")
        assertThat(controller.rowTitles()).containsExactly("Trip", "b.jpg", "notes.pdf").inOrder()

        // And a tap turns it off again
        controller.bar().menu.performIdentifierAction(R.id.action_delete_for_good, 0)
        shadowOf(context.mainLooper).idle()
        assertThat(toggle().isChecked).isFalse()
        assertThat(Settings.deleteForGood(context)).isFalse()
    }

    /** From the menu too, for whoever doesn't hold rows down: with nothing picked yet, and a bin that asks nothing. */
    @Test
    fun selectPhotosInTheMenuStartsPickingWithNothingPicked() {
        grantedPhotos()
        val controller = home()
        assertThat(controller.bar().menu.findItem(R.id.action_select).isVisible).isFalse()
        controller.clickRow("DCIM")
        assertThat(controller.bar().menu.findItem(R.id.action_select).isVisible).isTrue()

        controller.bar().menu.performIdentifierAction(R.id.action_select, 0)
        shadowOf(context.mainLooper).idle()

        assertThat(controller.barTitle()).isEqualTo(context.getString(R.string.select_photos))
        assertThat(controller.said("a.jpg")).isEqualTo(notSelected)
        assertThat(controller.bar().menu.findItem(R.id.action_delete).isEnabled).isFalse()
        assertThat(controller.tapBin()).isNull()
        assertThat(media.trashRequests).isEmpty()
        controller.clickRow("b.jpg")
        assertThat(controller.barTitle()).isEqualTo("1 selected")
        assertThat(controller.bar().menu.findItem(R.id.action_delete).isEnabled).isTrue()
    }

    /** Only where there are photos, and Android's trash reaches them. */
    @Test
    fun photosCanBePickedOnlyWhereAndroidCanMoveThemToItsBin() {
        // No photos in it
        grantedFolder()
        val documents = home()
        documents.clickRow("Documents")
        assertThat(documents.bar().menu.findItem(R.id.action_select).isVisible).isFalse()
        assertThat(documents.holdRow("alpha.pdf")).isFalse()

        // Photos, in a folder that isn't on the phone's own storage
        FakeDocumentsProvider.install().folder("root", "Cloud", ChildDoc("p1", "a.jpg", "image/jpeg", 1, 1))
        val cloud = home()
        cloud.clickRow("Cloud")
        assertThat(cloud.rowTitles()).contains("a.jpg")
        assertThat(cloud.bar().menu.findItem(R.id.action_select).isVisible).isFalse()
        assertThat(cloud.holdRow("a.jpg")).isFalse()
        assertThat(cloud.barTitle()).isEqualTo("Cloud")
    }

    /** Android's trash came with Android 11, and before it there is nothing to pick photos for. */
    @Test
    @Config(sdk = [29])
    fun beforeAndroid11NoPhotoCanBePicked() {
        grantedPhotos()
        val controller = home()
        controller.clickRow("DCIM")

        assertThat(controller.bar().menu.findItem(R.id.action_select).isVisible).isFalse()
        assertThat(controller.holdRow("a.jpg")).isFalse()
        assertThat(controller.barTitle()).isEqualTo("DCIM")
    }

    @Test
    fun whatIsPickedSurvivesARotation() {
        grantedPhotos()
        val first = home()
        first.clickRow("DCIM")
        first.holdRow("a.jpg")
        first.clickRow("c.png")
        val state = Bundle().also { first.saveInstanceState(it) }

        val second = home(state)

        assertThat(second.barTitle()).isEqualTo("2 selected")
        assertThat(second.said("a.jpg")).isEqualTo(selected)
        assertThat(second.said("b.jpg")).isEqualTo(notSelected)
        assertThat(second.said("c.png")).isEqualTo(selected)
        assertThat(second.get().findViewById<View>(R.id.openFab).visibility).isNotEqualTo(View.VISIBLE)
    }

    /** A photo deleted from somewhere else while picked is picked no longer once the folder is read again. */
    @Test
    fun aPickedPhotoThatHasGoneFromTheFolderIsPickedNoLonger() {
        grantedPhotos()
        val controller = home()
        controller.clickRow("DCIM")
        controller.holdRow("a.jpg")
        controller.clickRow("b.jpg")

        docs.remove("primary:DCIM/a.jpg")
        controller.pause().resume()
        shadowOf(context.mainLooper).idle()

        assertThat(controller.barTitle()).isEqualTo("1 selected")
        val prompt = controller.tapBin()!!
        assertThat(media.trashRequests.single().first).containsExactly(picture(12))
        controller.answer(prompt, Activity.RESULT_CANCELED)
    }

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
