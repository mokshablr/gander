package com.arjun.gander

import android.animation.ValueAnimator
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer

/**
 * A double tap on a document's pages or a picture, against a stand-in for the WebView.
 *
 * The stand-in zooms the way WebView.zoomBy does, about the middle of the view and no further
 * than its limits, and throws at what the real one throws at. The real thing is tested on a
 * device, in ViewerPdfTest.
 */
@RunWith(AndroidJUnit4::class)
class DoubleTapZoomTest {

    /** A phone's screen over a document [length] px long when fitted, drawn at [zoom] times its fitted width. */
    private class Page(private val most: Float = 10f, private val length: Int = LENGTH) : ZoomSurface {
        var zoom = 1f
        var x = 0
        var y = 0
        var zooms = 0

        override fun viewWidth() = WIDTH
        override fun viewHeight() = HEIGHT
        override fun scrolledX() = x
        override fun scrolledY() = y
        override fun rangeX() = (WIDTH * zoom).roundToInt()
        override fun rangeY() = (length * zoom).roundToInt()

        override fun zoomBy(factor: Float) {
            // WebView's own check, in doubles as it makes it: 0.01f is under 0.01 and is thrown out
            require(factor >= 0.01 && factor <= 100.0) { "zoomBy($factor)" }
            zooms++
            val to = (zoom * factor).coerceIn(1f, most)
            val midX = (x + WIDTH / 2f) / zoom
            val midY = (y + HEIGHT / 2f) / zoom
            zoom = to
            scrollTo((midX * to - WIDTH / 2f).roundToInt(), (midY * to - HEIGHT / 2f).roundToInt())
        }

        override fun scrollTo(x: Int, y: Int) {
            this.x = x.coerceIn(0, rangeX() - WIDTH)
            this.y = y.coerceIn(0, rangeY() - HEIGHT)
        }

        /** How far along and down the fitted document the place under a finger at ([atX], [atY]) is. */
        fun under(atX: Float, atY: Float) = (x + atX) / zoom to (y + atY) / zoom
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2000

        /** Nine screens. */
        const val LENGTH = 18000
    }

    private lateinit var context: Context
    private lateinit var page: Page
    private lateinit var doubleTap: DoubleTapZoom

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        page = Page()
        doubleTap = DoubleTapZoom(context, page)
    }

    @After
    fun tearDown() = animations(1f)

    /** The phone's animation scale, which Settings sets to 0 for a reader who turns animations off. */
    private fun animations(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun touch(action: Int, x: Float, y: Float, source: Int = InputDevice.SOURCE_TOUCHSCREEN) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        val event = MotionEvent.obtain(now, now, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
        doubleTap.touched(event)
        event.recycle()
    }

    private fun tap(x: Float, y: Float, source: Int = InputDevice.SOURCE_TOUCHSCREEN) {
        touch(MotionEvent.ACTION_DOWN, x, y, source)
        pass(40)
        touch(MotionEvent.ACTION_UP, x, y, source)
    }

    /** Two taps as a finger makes them, and the zoom they start given time to finish. */
    private fun doubleTap(x: Float, y: Float) {
        tap(x, y)
        pass(80)
        tap(x, y)
        pass(1000)
    }

    // ---------------------------------------------------------------
    // How far
    // ---------------------------------------------------------------

    @Test
    fun aFittedPageGoesInAndAZoomedOneComesBack() {
        assertThat(doubleTapTarget(1f)).isEqualTo(DOUBLE_TAP_ZOOM)
        assertThat(doubleTapTarget(DOUBLE_TAP_ZOOM)).isEqualTo(1f)
        // Pinched in a little to lose the margins, and pinched in a long way
        assertThat(doubleTapTarget(1.3f)).isEqualTo(DOUBLE_TAP_ZOOM)
        assertThat(doubleTapTarget(6f)).isEqualTo(1f)
    }

    /** The complaint was a double tap that barely moved the page, so none may. */
    @Test
    fun noDoubleTapIsASmallStep() {
        val least = sqrt(DOUBLE_TAP_ZOOM) - 0.001f
        var zoom = 1f
        while (zoom < 10f) {
            val to = doubleTapTarget(zoom)
            assertThat(maxOf(to / zoom, zoom / to)).isAtLeast(least)
            zoom += 0.01f
        }
    }

    // ---------------------------------------------------------------
    // About where
    // ---------------------------------------------------------------

    @Test
    fun aDoubleTapZoomsInAboutThePlaceTapped() {
        page.scrollTo(0, 3000)
        val before = page.under(300f, 500f)

        doubleTap(300f, 500f)

        assertThat(page.zoom).isWithin(0.01f).of(DOUBLE_TAP_ZOOM)
        val after = page.under(300f, 500f)
        assertThat(after.first).isWithin(1f).of(before.first)
        assertThat(after.second).isWithin(1f).of(before.second)
    }

    @Test
    fun theNextDoubleTapPutsThePageBackWithThatPlaceStillUnderTheFinger() {
        page.scrollTo(0, 3000)
        doubleTap(300f, 500f)
        val zoomedIn = page.under(700f, 900f)

        doubleTap(700f, 900f)

        assertThat(page.zoom).isEqualTo(1f)
        assertThat(page.x).isEqualTo(0)
        assertThat(page.under(700f, 900f).second).isWithin(1f).of(zoomedIn.second)
    }

    /** There is no document above its top to bring under a finger, so it stops at the top. */
    @Test
    fun comingBackNearTheTopStopsAtTheTopOfTheDocument() {
        doubleTap(300f, 100f)
        assertThat(page.zoom).isWithin(0.01f).of(DOUBLE_TAP_ZOOM)

        doubleTap(300f, 1800f)

        assertThat(page.zoom).isEqualTo(1f)
        assertThat(page.y).isEqualTo(0)
    }

    @Test
    fun comingBackNearTheEndStopsAtTheEndOfTheDocument() {
        page.scrollTo(0, LENGTH - HEIGHT)
        doubleTap(300f, 1900f)

        doubleTap(300f, 100f)

        assertThat(page.zoom).isEqualTo(1f)
        assertThat(page.y).isEqualTo(LENGTH - HEIGHT)
    }

    /**
     * Deep in a long document the scroll is a big number, and it multiplies any error in how far
     * the zoom has got, so every frame is checked and not only where the zoom ends.
     */
    @Test
    fun theTappedPlaceStaysUnderTheFingerThroughEveryFrameDeepInALongDocument() {
        page = Page(length = 200_000)
        doubleTap = DoubleTapZoom(context, page)
        page.scrollTo(0, 150_000)

        assertThat(worstStrayThroughADoubleTap(300f, 1200f)).isAtMost(2f)
        assertThat(page.zoom).isWithin(0.01f).of(DOUBLE_TAP_ZOOM)

        assertThat(worstStrayThroughADoubleTap(300f, 1200f)).isAtMost(2f)
        assertThat(page.zoom).isEqualTo(1f)
    }

    /** A double tap run a frame at a time, and how far in px the place under the finger moved from it. */
    private fun worstStrayThroughADoubleTap(x: Float, y: Float): Float {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val held = page.under(x, y)
        tap(x, y)
        pass(80)
        tap(x, y)
        var worst = 0f
        repeat(30) {
            pass(16)
            val now = page.under(x, y)
            worst = maxOf(worst, abs(now.first - held.first) * page.zoom, abs(now.second - held.second) * page.zoom)
        }
        return worst
    }

    /** A WebView on a very wide screen cannot reach the full zoom, and must still come back. */
    @Test
    fun aWebViewThatStopsShortStillComesBackOnTheNextDoubleTap() {
        page = Page(most = 1.6f)
        doubleTap = DoubleTapZoom(context, page)

        doubleTap(300f, 500f)
        assertThat(page.zoom).isEqualTo(1.6f)

        doubleTap(300f, 500f)
        assertThat(page.zoom).isEqualTo(1f)
    }

    /** Android gives an animation one frame, its last, when animations are off. */
    @Test
    fun withAnimationsOffADoubleTapLandsInOneStep() {
        animations(0f)
        page.scrollTo(0, 3000)
        val before = page.under(300f, 500f)

        doubleTap(300f, 500f)

        assertThat(page.zooms).isEqualTo(1)
        assertThat(page.zoom).isWithin(0.01f).of(DOUBLE_TAP_ZOOM)
        assertThat(page.under(300f, 500f).second).isWithin(1f).of(before.second)

        doubleTap(300f, 500f)

        assertThat(page.zoom).isEqualTo(1f)
    }

    /** No WebView zooms this far in, but zoomBy throws at what it is asked and an exception here ends the app. */
    @Test
    fun aZoomNoWebViewReachesIsStillAskedForWithinWhatZoomByTakes() {
        animations(0f)
        page = Page(most = 500f).apply { zoom = 200f }
        doubleTap = DoubleTapZoom(context, page)

        doubleTap(300f, 500f)

        assertThat(page.zoom).isLessThan(200f)
    }

    /**
     * A PDF pinched smaller than the screen zooms its own double tap, back to the fitted width,
     * and the WebView is at its floor then as it is at the fitted width: zoomed here as well, the
     * page would come back to its width under a WebView gone in to twice it.
     */
    @Test
    fun whileThePageHasShrunkItsPagesADoubleTapIsLeftToIt() {
        doubleTap.leftToPage = true
        doubleTap(300f, 500f)

        assertThat(page.zooms).isEqualTo(0)
        assertThat(page.zoom).isEqualTo(1f)

        doubleTap.leftToPage = false
        doubleTap(300f, 500f)

        assertThat(page.zoom).isWithin(0.01f).of(DOUBLE_TAP_ZOOM)
    }

    // ---------------------------------------------------------------
    // What is not a double tap
    // ---------------------------------------------------------------

    @Test
    fun oneTapZoomsNothing() {
        tap(300f, 500f)
        pass(1000)

        assertThat(page.zooms).isEqualTo(0)
    }

    @Test
    fun twoTapsTooFarApartInTimeZoomNothing() {
        tap(300f, 500f)
        pass(600)
        tap(300f, 500f)
        pass(1000)

        assertThat(page.zooms).isEqualTo(0)
    }

    /** A second tap held and dragged is the WebView's own one-finger zoom. */
    @Test
    fun aSecondTapThatDragsIsLeftToTheWebView() {
        tap(300f, 500f)
        pass(80)
        touch(MotionEvent.ACTION_DOWN, 300f, 500f)
        pass(40)
        touch(MotionEvent.ACTION_MOVE, 300f, 700f)
        pass(40)
        touch(MotionEvent.ACTION_UP, 300f, 700f)
        pass(1000)

        assertThat(page.zooms).isEqualTo(0)
    }

    /** A double click selects a word, from a mouse or a touchpad. */
    @Test
    fun aMousesDoubleClickZoomsNothing() {
        tap(300f, 500f, InputDevice.SOURCE_MOUSE)
        pass(80)
        tap(300f, 500f, InputDevice.SOURCE_MOUSE)
        pass(1000)

        assertThat(page.zooms).isEqualTo(0)
    }

    /** A frame for each move of the clock here, since left to itself Robolectric runs a zoom to its end at once. */
    @Test
    fun aFingerPutDownStopsAZoomOnItsWay() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        fun frames(n: Int) = repeat(n) { pass(16) }
        tap(300f, 500f)
        pass(80)
        tap(300f, 500f)
        frames(5)
        val partWay = page.zoom

        touch(MotionEvent.ACTION_DOWN, 300f, 500f)
        frames(30)

        assertThat(partWay).isGreaterThan(1.1f)
        assertThat(partWay).isLessThan(DOUBLE_TAP_ZOOM - 0.1f)
        assertThat(page.zoom).isEqualTo(partWay)
    }
}
