package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowWebView

/**
 * The title bar over a document, which slides away as the reader scrolls down it. Issue #40.
 *
 * The WebView is Robolectric's, so nothing is drawn and nothing scrolls by itself: a scroll here
 * is the WebView moved to a place, with or without a finger on it first.
 */
@RunWith(AndroidJUnit4::class)
class DocumentChromeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
    }

    /** The viewer for [fixture], its page loaded, as ViewerActivityTest opens one. */
    private fun open(fixture: String): ActivityController<ViewerActivity> {
        val uri = FixtureProvider.uriFor(fixture)
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent)
        controller.get().documentLoader = Executor { it.run() }
        return controller.setup().also { settle() }
    }

    private fun pdf(): ViewerActivity = open("six-pages.pdf").get()

    private val ViewerActivity.root get() = findViewById<ViewGroup>(R.id.root)
    private val ViewerActivity.toolbar get() = findViewById<MaterialToolbar>(R.id.toolbar)
    private val ViewerActivity.searchBar get() = findViewById<View>(R.id.searchBar)
    private val ViewerActivity.saveProgress get() = findViewById<View>(R.id.saveProgress)
    private val ViewerActivity.stage get() = findViewById<View>(R.id.container).parent as FrameLayout
    private val ViewerActivity.web
        get() = findViewById<FrameLayout>(R.id.container).children.filterIsInstance<WebView>().single()

    /** What holds the title bar over a document. */
    private val ViewerActivity.top get() = toolbar.parent as View

    private val ViewerActivity.floats get() = toolbar.parent !== root

    private val ViewerActivity.barUp get() = top.visibility == View.VISIBLE && top.translationY == 0f
    // All of it out of the frame, however tall it has been since
    private val ViewerActivity.barAway
        get() = top.visibility == View.INVISIBLE && top.translationY <= -top.height.toFloat()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun ViewerActivity.touch(action: Int, pointers: Int = 1) {
        val now = SystemClock.uptimeMillis()
        val properties = Array(pointers) { i -> MotionEvent.PointerProperties().apply { id = i } }
        val coords = Array(pointers) { i -> MotionEvent.PointerCoords().apply { x = 100f + 50f * i; y = 400f } }
        val event = MotionEvent.obtain(now, now, action, pointers, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        web.dispatchTouchEvent(event)
        event.recycle()
    }

    private val secondFingerDown =
        MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** A finger put down, the document moved to [y] under it, and the finger lifted. */
    private fun ViewerActivity.swipeTo(y: Int) {
        touch(MotionEvent.ACTION_DOWN)
        web.scrollTo(0, y)
        touch(MotionEvent.ACTION_UP)
        settle()
    }

    // ---------------------------------------------------------------
    // Where things are
    // ---------------------------------------------------------------

    @Test
    fun aDocumentsTitleBarFloatsOverItWithTheSearchAndSaveBarsUnderIt() {
        val viewer = pdf()

        assertThat(viewer.top.parent).isSameInstanceAs(viewer.stage)
        assertThat(viewer.searchBar.parent).isSameInstanceAs(viewer.top)
        assertThat(viewer.saveProgress.parent).isSameInstanceAs(viewer.top)
        // First, so a screen reader comes to it before the document, and drawn over everything
        // else in the frame all the same
        assertThat(viewer.stage.indexOfChild(viewer.top)).isEqualTo(0)
        val others = (viewer.stage as ViewGroup).children.filter { it !== viewer.top }
        others.forEach { assertThat(viewer.top.z).isGreaterThan(it.z) }
        assertThat(viewer.barUp).isTrue()
    }

    /** Material tints a bar deeper for every elevation under it, and floating is not one. */
    @Test
    fun theBarKeepsTheColourItHasOverEveryOtherFile() {
        val photo = open("tiny.png").get()
        val viewer = pdf()
        fun MaterialToolbar.surface() = (background as MaterialShapeDrawable).resolvedTintColor
        assertThat(viewer.toolbar.surface()).isEqualTo(photo.toolbar.surface())
    }

    @Test
    fun everyDocumentFloatsItsBarAndNothingElseDoes() {
        val documents = listOf(
            "six-pages.pdf", "report.docx", "letter.odt", "budget.xlsx", "deck.pptx", "notes.md", "plain.txt",
        )
        documents.forEach { assertWithMessage(it).that(open(it).get().floats).isTrue() }

        // A photo, a picture drawn by a page, a model, a sound, a zip and a file Gander cannot read
        val others = listOf("tiny.png", "anim.gif", "bracket.stl", "tone.wav", "archive.zip", "unknown.xyz")
        others.forEach { assertWithMessage(it).that(open(it).get().floats).isFalse() }
    }

    @Test
    fun thePageIsToldOnItsUrlHowMuchRoomToLeaveForTheBar() {
        val viewer = pdf()
        val url = Uri.parse(shadowOf(viewer.web).lastLoadedUrl)
        val dp = viewer.toolbar.layoutParams.height / viewer.resources.displayMetrics.density
        assertThat(url.getQueryParameter("top")!!.toFloat()).isWithin(0.05f).of(dp)
    }

    /** The card saying the WebView is too old to draw a PDF has nothing to scroll and no room left. */
    @Test
    fun aDocumentOnAWebViewTooOldToDrawItKeepsItsBarAbove() {
        ShadowWebView.setCurrentWebViewPackage(PackageInfo().apply {
            packageName = "com.google.android.webview"
            versionName = "100.0.4896.127"
        })
        val viewer = pdf()
        assertThat(viewer.floats).isFalse()
        assertThat(Uri.parse(shadowOf(viewer.web).lastLoadedUrl).getQueryParameter("top")).isNull()
    }

    // ---------------------------------------------------------------
    // Following the reader
    // ---------------------------------------------------------------

    @Test
    fun scrollingDownSlidesTheBarAwayAndScrollingUpBringsItBack() {
        val viewer = pdf()
        assertThat(viewer.top.height).isGreaterThan(0)

        viewer.swipeTo(600)
        assertThat(viewer.barAway).isTrue()

        viewer.swipeTo(400)
        assertThat(viewer.barUp).isTrue()
    }

    /** A jump to a page, a search hit or the page a document reopens at is not the reader scrolling. */
    @Test
    fun aScrollThePageMakesItselfLeavesTheBarWhereItIs() {
        val viewer = pdf()
        viewer.web.scrollTo(0, 600)
        settle()
        assertThat(viewer.barUp).isTrue()

        viewer.swipeTo(900)
        viewer.web.scrollTo(0, 300)
        settle()
        assertThat(viewer.barAway).isTrue()
    }

    @Test
    fun aFlingGoesOnMovingTheBarUntilTheDocumentStops() {
        val viewer = pdf()
        viewer.touch(MotionEvent.ACTION_DOWN)
        viewer.touch(MotionEvent.ACTION_UP)
        // Still moving after the finger has lifted, for longer than the quiet that ends a fling
        // when nothing moves
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(100))
        viewer.web.scrollTo(0, 2)
        looper.idleFor(Duration.ofMillis(100))
        viewer.web.scrollTo(0, 600)
        settle()
        assertThat(viewer.barAway).isTrue()

        // Once it has stopped, what moves it next is the page
        viewer.web.scrollTo(0, 300)
        settle()
        assertThat(viewer.barAway).isTrue()
    }

    @Test
    fun reachingTheTopBringsTheBarBackWhoeverScrolled() {
        val viewer = pdf()
        viewer.swipeTo(600)
        assertThat(viewer.barAway).isTrue()

        viewer.web.scrollTo(0, 0)
        settle()
        assertThat(viewer.barUp).isTrue()
    }

    @Test
    fun aPinchLeavesTheBar() {
        val viewer = pdf()
        viewer.touch(MotionEvent.ACTION_DOWN)
        viewer.touch(secondFingerDown, pointers = 2)
        viewer.web.scrollTo(0, 600)
        settle()
        assertThat(viewer.barUp).isTrue()
    }

    @Test
    fun aTwitchUnderTheFingerLeavesTheBar() {
        val viewer = pdf()
        viewer.swipeTo(600)
        // Back up by less than a finger moves before Android calls it a scroll
        viewer.touch(MotionEvent.ACTION_DOWN)
        viewer.web.scrollTo(0, 598)
        settle()
        assertThat(viewer.barAway).isTrue()
    }

    // ---------------------------------------------------------------
    // Holding it up
    // ---------------------------------------------------------------

    private fun ViewerActivity.openSearch() {
        toolbar.menu.performIdentifierAction(R.id.action_search, 0)
        settle()
    }

    @Test
    fun theBarStaysWhileTheSearchBarIsOpen() {
        val viewer = pdf()
        viewer.openSearch()
        viewer.swipeTo(600)
        assertThat(viewer.barUp).isTrue()

        viewer.onBackPressedDispatcher.onBackPressed()
        assertThat(viewer.searchBar.visibility).isEqualTo(View.GONE)
        viewer.swipeTo(900)
        assertThat(viewer.barAway).isTrue()
    }

    /** The menu opened from the bar, or a box opened from that, takes the window's focus. */
    @Test
    fun theBarStaysUnderAMenuOrABoxOpenedFromIt() {
        val viewer = pdf()
        viewer.onWindowFocusChanged(false)
        viewer.swipeTo(600)
        assertThat(viewer.barUp).isTrue()

        viewer.onWindowFocusChanged(true)
        viewer.swipeTo(900)
        assertThat(viewer.barAway).isTrue()
    }

    @Test
    fun underAScreenReaderTheBarStays() {
        val viewer = pdf()
        shadowOf(viewer.getSystemService(AccessibilityManager::class.java))
            .setTouchExplorationEnabled(true)
        viewer.swipeTo(600)
        assertThat(viewer.barUp).isTrue()
    }

    // ---------------------------------------------------------------
    // What else follows it
    // ---------------------------------------------------------------

    /** The page's channel, opened as the page finishes loading. */
    private fun ViewerActivity.openChannel() =
        web.webViewClient.onPageFinished(web, shadowOf(web).lastLoadedUrl)

    /** Everything the app has said down the channel so far. */
    private fun ViewerActivity.said(): List<String> = shadowOf(web).createdPorts.single()[0].outgoingMessages

    private fun ViewerActivity.barHeight() = PortCommand.barHeight(documentChrome!!.heightDp())

    @Test
    fun thePageIsToldTheBarsHeightAndWhetherItIsOnScreen() {
        val viewer = pdf()
        viewer.openChannel()
        val height = viewer.barHeight()
        assertThat(viewer.said()).containsExactly(height)

        viewer.swipeTo(600)
        viewer.swipeTo(300)
        assertThat(viewer.said())
            .containsExactly(height, PortCommand.barShown(false), PortCommand.barShown(true)).inOrder()
    }

    /** The search bar is the bar's too, so the page leaves room for both while it is open. */
    @Test
    fun thePageLeavesRoomForTheSearchBarWhileItIsOpen() {
        val viewer = pdf()
        viewer.openChannel()
        val alone = viewer.top.height
        val aloneSaid = viewer.barHeight()

        viewer.openSearch()
        assertThat(viewer.top.height).isGreaterThan(alone)
        assertThat(viewer.said().last()).isEqualTo(viewer.barHeight())
        assertThat(viewer.said().last()).isNotEqualTo(aloneSaid)

        viewer.onBackPressedDispatcher.onBackPressed()
        settle()
        assertThat(viewer.top.height).isEqualTo(alone)
        assertThat(viewer.said().last()).isEqualTo(aloneSaid)
    }

    /** A channel that opens after the bar has moved is told where it is. */
    @Test
    fun aChannelThatOpensLateIsToldWhereTheBarIs() {
        val viewer = pdf()
        viewer.swipeTo(600)
        viewer.openChannel()
        assertThat(viewer.said()).containsExactly(viewer.barHeight(), PortCommand.barShown(false)).inOrder()
    }

    @Test
    fun theScrollThumbRunsBelowTheBar() {
        val viewer = pdf()
        val track = viewer.findViewById<View>(R.id.fastScrollTrack)
        val thumb = viewer.findViewById<View>(R.id.fastScrollThumb)
        fun View.marginTop() = (layoutParams as ViewGroup.MarginLayoutParams).topMargin

        assertThat(track.marginTop()).isEqualTo(viewer.top.height)
        assertThat(thumb.marginTop()).isEqualTo(viewer.top.height)
        viewer.openSearch()
        assertThat(track.marginTop()).isEqualTo(viewer.top.height)

        // Not with the bar as it slides: one place, wherever the bar is
        viewer.swipeTo(600)
        assertThat(track.marginTop()).isEqualTo(viewer.top.height)
    }

    // ---------------------------------------------------------------
    // A renderer that goes
    // ---------------------------------------------------------------

    @Test
    fun theCardForARendererThatHasGoneHasItsBarAboveIt() {
        val sound = open("tone.wav").get()
        val viewer = pdf()
        viewer.swipeTo(600)

        val web = viewer.web
        web.webViewClient.onRenderProcessGone(web, object : RenderProcessGoneDetail() {
            override fun didCrash() = false
            override fun rendererPriorityAtExit() = 0
        })
        settle()

        assertThat(viewer.documentChrome).isNull()
        val parts = listOf<(ViewerActivity) -> View>({ it.toolbar }, { it.searchBar }, { it.saveProgress })
        parts.forEach { part ->
            assertThat(part(viewer).parent).isSameInstanceAs(viewer.root)
            assertThat(viewer.root.indexOfChild(part(viewer))).isEqualTo(sound.root.indexOfChild(part(sound)))
        }
        assertThat(viewer.toolbar.translationY).isEqualTo(0f)
        assertThat(viewer.toolbar.visibility).isEqualTo(View.VISIBLE)
        val track = viewer.findViewById<View>(R.id.fastScrollTrack)
        assertThat((track.layoutParams as ViewGroup.MarginLayoutParams).topMargin).isEqualTo(0)
    }
}
