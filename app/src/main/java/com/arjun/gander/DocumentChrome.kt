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

/**
 * The title bar over a document: it slides away as the reader scrolls down and comes back as
 * they scroll up or reach the top. Issue #40.
 *
 * It floats over the page rather than sitting above it, so the page is never resized and the text
 * never jumps as it comes and goes. The page keeps the bar's height clear at the top of the
 * document instead, told it on the URL and over the channel as it changes, which is why only a
 * document whose page has the channel gets this. The search bar and the save bar ride with it.
 *
 * The phone's status bar stays. Only the reader's own scrolling moves the bar: a jump to a page, a
 * search hit or the place a document reopens at leaves it where it is. It stays while the search
 * bar is open, while a menu or box opened from it is up, and under a screen reader, which cannot
 * reach a view that has slid away.
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

    var shown = true
        private set

    /** The height the page was last told, in px. */
    private var told = 0

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
        if (!shown) tellPage(PortCommand.barShown(false))
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

    /** Whether the reader's finger, or the fling it left, is what is moving the document. */
    private var steering = false
    private var fingers = 0
    private val stopSteering = Runnable { steering = false }

    /** How far the document has moved one way since it last moved the other, in px. */
    private var travel = 0

    /**
     * A touch on the document or on the scroll thumb. Every touch the document is given comes
     * through here, so that a scroll can be told from one the page made for itself.
     */
    fun touched(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(stopSteering)
                steering = true
                travel = 0
                fingers = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> fingers = event.pointerCount
            MotionEvent.ACTION_POINTER_UP -> fingers = event.pointerCount - 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingers = 0
                quietAfter()
            }
        }
    }

    // A fling goes on after the finger lifts, and is still the reader's
    private fun quietAfter() {
        main.removeCallbacks(stopSteering)
        main.postDelayed(stopSteering, QUIET_MS)
    }

    /** The document moved from [oldY] to [y]. */
    fun scrolled(y: Int, oldY: Int) {
        if (y <= 0) {
            show(true)
            return
        }
        // A pinch moves the document about as it zooms, which is not the reader going anywhere
        if (!steering || fingers > 1) return
        if (fingers == 0) quietAfter()
        val dy = y - oldY
        if (dy == 0) return
        travel = if ((dy > 0) == (travel > 0)) travel + dy else dy
        if (travel >= slop) show(false) else if (travel <= -slop) show(true)
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

    private fun show(visible: Boolean) {
        val up = visible || searching || !focused || screenReader()
        if (up == shown) return
        shown = up
        top.animate().cancel()
        if (up) {
            top.visibility = View.VISIBLE
            top.animate().translationY(0f).setDuration(SLIDE_MS).start()
        } else {
            // Out of sight as well as out of the way, so nothing in it can be reached by a key
            top.animate().translationY(-top.height.toFloat()).setDuration(SLIDE_MS)
                .withEndAction { top.visibility = View.INVISIBLE }.start()
        }
        tellPage(PortCommand.barShown(up))
    }

    private companion object {
        /** About what Material gives a bar that leaves on scroll. A sheet's tabs follow it in the same time. */
        const val SLIDE_MS = 200L

        /** How long the document has to sit still after the finger lifts for a fling to be over. */
        const val QUIET_MS = 150L
    }
}
