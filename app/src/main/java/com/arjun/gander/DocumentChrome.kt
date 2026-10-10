package com.arjun.gander

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The title bar over a document: it goes up out of sight as the reader scrolls down and comes back
 * as they scroll up or reach the top. Issue #40.
 *
 * It follows the scrolling pixel for pixel, as Chrome's bar does, so a short scroll moves it a
 * little and at the top of a document it moves as part of the page. Let go of it part way and it
 * finishes going whichever way most of it has already gone.
 *
 * It floats over the page rather than sitting above it, so the page is never resized and the text
 * never jumps as it comes and goes. The page keeps the bar's height clear at the top of the
 * document instead, told it on the URL and over the channel as it changes, which is why only a
 * document whose page has the channel gets this. The search bar and the save bar ride with it.
 *
 * The phone's status bar stays. Only the reader's own scrolling moves the bar: a jump to a page, a
 * search hit, a link tapped or the place a document reopens at leaves it where it is. It stays
 * while the search bar is open, while a menu or box opened from it is up, and under a screen
 * reader, which cannot reach a view that has gone out of sight.
 */
internal class DocumentChrome(
    private val activity: AppCompatActivity,
    private val screenReader: () -> Boolean,
    /** Says something to the page, over the channel once there is one. */
    private val tellPage: (String) -> Unit,
    /** How much of the top of the document the bar covers, in px, for the scroll thumb's track. */
    private val covered: (Int) -> Unit,
) {
    private val root: ViewGroup = activity.findViewById(R.id.root)
    private val toolbar: View = activity.findViewById(R.id.toolbar)
    private val searchBar: View = activity.findViewById(R.id.searchBar)
    private val saveProgress: View = activity.findViewById(R.id.saveProgress)
    private val stage = activity.findViewById<View>(R.id.container).parent as FrameLayout
    private val density = activity.resources.displayMetrics.density

    /** The title bar, the search bar and the save bar, over the top of the document. */
    private val top = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        // The bars' own shadows fall on the document below this strip, as they did on the page
        // when it sat under them
        clipChildren = false
        clipToPadding = false
        // First in the frame, so a screen reader comes to it before the document, and drawn over
        // the document all the same by standing higher. Not by elevation, which Material adds to
        // the toolbar's own and tints its surface deeper for.
        translationZ = toolbar.elevation
    }

    // As float() found them, for land()
    private val parts = listOf(toolbar, searchBar, saveProgress)
    private val partsAt = parts.map { root.indexOfChild(it) }

    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private val main = Handler(Looper.getMainLooper())

    /** The height the page was last told, in px. */
    private var told = 0

    /** How much of the bar is out of sight, in dp, as the page was last told it. */
    private var toldGone = "0"

    fun float() {
        parts.forEach { root.removeView(it) }
        parts.forEach { top.addView(it) }
        stage.addView(top, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))
        top.addOnLayoutChangeListener(heightChanged)
    }

    /** Back above the document, for the card that stands in for a document whose renderer has gone. */
    fun land() {
        top.removeOnLayoutChangeListener(heightChanged)
        top.animate().cancel()
        main.removeCallbacks(stopSteering)
        main.removeCallbacks(cover)
        stage.removeView(top)
        top.removeAllViews()
        parts.zip(partsAt).forEach { (part, at) -> root.addView(part, at) }
        covered(0)
    }

    /** The bar's height in dp, for the URL the page is loaded from. */
    fun heightDp(): String = dp(if (top.height > 0) top.height else toolbar.layoutParams.height)

    /** What the page has to be told once the channel is open, since it was told only the URL. */
    fun replay() {
        tellPage(PortCommand.barHeight(heightDp()))
        if (toldGone != "0") tellPage(PortCommand.barGone(toldGone))
    }

    private val heightChanged = View.OnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
        val height = b - t
        if (height == told) return@OnLayoutChangeListener
        told = height
        tellPage(PortCommand.barHeight(dp(height)))
        // Outside the layout pass that reported it, which a new margin would otherwise re-enter
        main.removeCallbacks(cover)
        main.post(cover)
    }

    private val cover = Runnable { covered(told) }

    private fun dp(px: Int): String = String.format(Locale.ROOT, "%.1f", px / density).removeSuffix(".0")

    // ---------------------------------------------------------------
    // Following the reader's scrolling
    // ---------------------------------------------------------------

    /**
     * Whether the reader is what is moving the document: a finger dragging it, the fling it left,
     * the scroll thumb, a mouse wheel or a key.
     */
    private var steering = false
    private var fingers = 0
    private var downX = 0f
    private var downY = 0f
    private val stopSteering = Runnable {
        steering = false
        settle()
    }

    /**
     * A touch on the document. Every touch the document is given comes through here, so that a
     * scroll can be told from one the page made for itself. A finger steers once it has moved
     * further than a tap does, so a jump that a tap makes, to a heading from a contents link say,
     * leaves the bar where it is.
     */
    fun touched(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(stopSteering)
                steering = false
                fingers = 1
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_MOVE ->
                if (fingers == 1 && hypot(event.x - downX, event.y - downY) > slop) steering = true
            MotionEvent.ACTION_POINTER_DOWN -> fingers = event.pointerCount
            MotionEvent.ACTION_POINTER_UP -> fingers = event.pointerCount - 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingers = 0
                if (steering) quietAfter() else settle()
            }
        }
    }

    /** The scroll thumb taken hold of, which moves the document from the finger's first move. */
    fun grabbed() {
        main.removeCallbacks(stopSteering)
        steering = true
        fingers = 1
    }

    /** A mouse wheel, a touchpad or a key is moving the document, which is the reader too. */
    fun nudged() {
        if (fingers > 0) return
        steering = true
        quietAfter()
    }

    // A fling goes on after the finger lifts, and is still the reader's
    private fun quietAfter() {
        main.removeCallbacks(stopSteering)
        main.postDelayed(stopSteering, QUIET_MS)
    }

    /** The document moved from [oldY] to [y]. */
    fun scrolled(y: Int, oldY: Int) {
        // A pinch moves the document about as it zooms, which is not the reader going anywhere
        if (steering && fingers <= 1) {
            if (fingers == 0) quietAfter()
            follow(y - oldY)
        }
        // However the document got there, the whole bar is back at the top
        if (y <= 0) show(true)
    }

    /** Up by as much as the document went down, and down by as much as it came back. */
    private fun follow(dy: Int) {
        if (dy == 0 || held()) return
        val y = (top.translationY - dy).coerceIn(-top.height.toFloat(), 0f)
        if (y == top.translationY) return
        top.animate().cancel()
        place(y)
    }

    /** Let go of part way, the bar finishes going whichever way most of it has gone. */
    private fun settle() {
        val y = top.translationY
        val height = top.height.toFloat()
        if (y < 0f && y > -height) show(y > -height / 2)
    }

    // ---------------------------------------------------------------
    // Holding it up
    // ---------------------------------------------------------------

    private var searching = false
    private var focused = true

    /** The search bar opened or closed: the title bar stays for as long as it is open. */
    fun searching(open: Boolean) {
        searching = open
        if (open) show(true)
    }

    /** A menu or a box opened from the bar takes the window's focus; the bar stays under it. */
    fun focusChanged(hasFocus: Boolean) {
        focused = hasFocus
        if (!hasFocus) show(true)
    }

    private fun held() = searching || !focused || screenReader()

    /** All of the bar on screen, or none of it, in the time the rest of the way takes. */
    private fun show(visible: Boolean) {
        val height = top.height.toFloat()
        val y = if (visible || held()) 0f else -height
        top.animate().cancel()
        val distance = abs(y - top.translationY)
        if (distance == 0f || height <= 0f) {
            place(y)
            return
        }
        top.visibility = View.VISIBLE
        top.animate().translationY(y).setDuration((SLIDE_MS * distance / height).toLong())
            // The sheet tabs held under the bar follow it frame by frame here too
            .setUpdateListener { tellWhere() }
            .withEndAction { place(y) }
            .start()
    }

    /** The bar put [y] px above where all of it shows, so 0 or less. */
    private fun place(y: Float) {
        top.translationY = y
        // Out of sight as well as out of the way, so nothing in it can be reached by a key
        top.visibility = if (top.height > 0 && y <= -top.height) View.INVISIBLE else View.VISIBLE
        tellWhere()
    }

    private fun tellWhere() {
        val gone = dp(-top.translationY.roundToInt())
        if (gone == toldGone) return
        toldGone = gone
        tellPage(PortCommand.barGone(gone))
    }

    private companion object {
        /** What Material gives a bar that leaves on scroll, for the whole bar; less of it takes less. */
        const val SLIDE_MS = 200L

        /** How long the document has to sit still after the finger lifts for a fling to be over. */
        const val QUIET_MS = 150L
    }
}
