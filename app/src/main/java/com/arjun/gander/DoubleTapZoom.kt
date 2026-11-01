package com.arjun.gander

import android.animation.ValueAnimator
import android.content.Context
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How far in a double tap goes, in times the page fitted to the screen's width.
 *
 * Twice, as Jetpack's PDF viewer goes. It brings the body text of an A4 page to about the size
 * a phone sets its own text in, and one column of a two-column page to the width of the screen.
 */
internal const val DOUBLE_TAP_ZOOM = 2f

/** How long a double tap's zoom takes, which is what Chromium gives its own. */
internal const val DOUBLE_TAP_MS = 250

/**
 * Where a double tap takes a document drawn at [zoom] times its fitted width: in from the fitted
 * page or near it, and back to the fitted page from further in.
 *
 * The line between them is half way by ratio, so every double tap changes the size by at least
 * as much as that, wherever a pinch left it.
 */
internal fun doubleTapTarget(zoom: Float): Float =
    if (zoom < sqrt(DOUBLE_TAP_ZOOM)) DOUBLE_TAP_ZOOM else 1f

/**
 * The scroll offset that keeps a place in the document under the finger at [at] px along the
 * view. [place] is how far along the document it was when the zoom began, [grown] how many times
 * that size the document is drawn at now, and [range] and [extent] are the document's and the
 * view's lengths now.
 */
internal fun anchoredScroll(place: Float, grown: Float, at: Float, range: Int, extent: Int): Int =
    (place * grown - at).roundToInt().coerceIn(0, maxOf(0, range - extent))

/** The WebView as a double tap needs it, so the zoom can be tested against a stand-in. */
internal interface ZoomSurface {
    fun viewWidth(): Int
    fun viewHeight(): Int
    fun scrolledX(): Int
    fun scrolledY(): Int

    /** The document's width and height in px as it is zoomed now. */
    fun rangeX(): Int
    fun rangeY(): Int

    /** WebView.zoomBy, which zooms about the middle of the view, stops at its limits and throws outside 0.01 to 100. */
    fun zoomBy(factor: Float)
    fun scrollTo(x: Int, y: Int)
}

/**
 * A double tap on a document's pages or a picture zooms in about the place tapped, and the next
 * one zooms back out.
 *
 * The WebView's own double tap fits the block under the finger to the screen, which suits a web
 * page's columns. A PDF page is one block as wide as the screen, so it went in by a fifth. The
 * pages [FileKind.zoomedOnDoubleTap] names turn that one off with touch-action, and this is what
 * a double tap does instead.
 */
internal class DoubleTapZoom(context: Context, private val surface: ZoomSurface) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var running: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var still = false

    /**
     * Whether the page zooms a double tap itself, as a PDF does while its pages are pinched
     * smaller than the screen: the WebView is at its floor then, as at the fitted width, so only
     * the page can tell the two apart, and it says so over the channel. See [PortMessage.Shrunk].
     */
    var leftToPage = false

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        // The second tap, start to finish. A finger that goes down and drags is the WebView's
        // one-finger zoom, and a mouse's double click selects a word.
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    still = !e.isFromSource(InputDevice.SOURCE_MOUSE)
                }
                MotionEvent.ACTION_MOVE ->
                    if (hypot(e.x - downX, e.y - downY) > slop) still = false
                MotionEvent.ACTION_UP -> if (still) zoomAbout(e.x, e.y)
            }
            return false
        }
    })

    /** A touch on the document. Every one comes through here, and none is consumed. */
    fun touched(event: MotionEvent) {
        // A finger put down takes the document over from a zoom still on its way
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancel()
        taps.onTouchEvent(event)
    }

    fun cancel() {
        running?.cancel()
        running = null
    }

    /** How many times its fitted width the document is drawn at, or null before it is laid out. */
    private fun zoom(): Float? {
        val width = surface.viewWidth()
        val range = surface.rangeX()
        return if (width > 0 && range > 0) range.toFloat() / width else null
    }

    private fun zoomAbout(x: Float, y: Float) {
        if (leftToPage) return
        val from = zoom() ?: return
        val to = doubleTapTarget(from)
        val began = ZoomStart(surface)
        cancel()
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DOUBLE_TAP_MS.toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                // By ratio, so the page grows at an even rate rather than fastest at the start
                step(from * (to / from).pow(it.animatedFraction), began, x, y)
            }
            start()
        }
    }

    /** One frame: the document at [to] times its fitted width, with the place tapped under the finger. */
    private fun step(to: Float, began: ZoomStart, x: Float, y: Float) {
        val now = zoom() ?: return
        // The fitted page is the WebView's floor, and asking for a little less lands on it exactly
        val by = if (to <= 1f && now > 1f) UNDER / now else to / now
        // WebView.zoomBy throws outside 0.01 to 100
        if (by != 1f) surface.zoomBy(by.coerceIn(0.02f, 50f))
        // Read back, since the WebView stops at its own limits
        val grown = began.grown() ?: return
        surface.scrollTo(
            anchoredScroll(began.x + x, grown, x, surface.rangeX(), surface.viewWidth()),
            anchoredScroll(began.y + y, grown, y, surface.rangeY(), surface.viewHeight()),
        )
    }

    private companion object {
        /** A little under the fitted page, as a zoom. */
        const val UNDER = 0.9f
    }
}

/** Where a double tap's zoom began, which each of its frames measures from. */
private class ZoomStart(private val surface: ZoomSurface) {
    val x = surface.scrolledX().toFloat()
    val y = surface.scrolledY().toFloat()
    private val wide = surface.rangeX()
    private val long = surface.rangeY()

    /**
     * How many times its size at the start the document is drawn at now, or null once it is not
     * laid out. Read off its length while that runs past the view: a length is whole px, so the
     * longer one measures finer, and deep in a long document the scroll multiplies whatever the
     * measure misses.
     */
    fun grown(): Float? {
        val view = surface.viewHeight()
        val longNow = surface.rangeY()
        val wideNow = surface.rangeX()
        return when {
            long > view && longNow > view -> longNow.toFloat() / long
            wideNow > 0 -> wideNow.toFloat() / wide
            else -> null
        }
    }
}
