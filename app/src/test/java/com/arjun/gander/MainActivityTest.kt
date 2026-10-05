package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The home screen: recents, granted folders, and the first-run explainer.
 *
 * The folder read normally happens on a background thread, which would make
 * every assertion here a race. [directExecutor] runs it inline instead, so a
 * test asserts on a screen that has finished drawing.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

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

    /** Runs everything submitted to it on the calling thread. */
    private fun directExecutor(): ExecutorService {
        val direct = Executor { it.run() }
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
        val controller = Robolectric.buildActivity(MainActivity::class.java)
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
            .isEqualTo(context.getString(R.string.welcome_formats_spoken))
    }

    @Test
    fun theExplainerGoesOnceSomethingHasBeenOpened() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val controller = home()
        assertThat(controller.welcome().visibility).isEqualTo(View.GONE)
        assertThat(controller.list().visibility).isEqualTo(View.VISIBLE)
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
}
