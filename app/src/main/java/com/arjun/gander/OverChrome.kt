package com.arjun.gander

import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.appbar.MaterialToolbar

/**
 * The title bar and the phone's bars over a picture that fills the screen: a video's (issue #33),
 * a photo's and a track's (issue #71).
 *
 * Everywhere else the title bar sits above the document and the viewer keeps clear of the phone's
 * bars, which suits a page and not a picture. In landscape the three of them took about a third of
 * the screen's height, and hiding any of them where they stood would have resized the picture
 * every time they came and went. So here the picture fills the screen, the title bar is lifted
 * over its top edge with its own colour carried up behind the clock, and nothing moves when the
 * bars do.
 *
 * A picture sits on dark whatever the phone is set to, so the parts over it take the colours a
 * night-mode PDF gives them, a dark bar with the words and icons a phone set to dark has: a paper
 * bar over a picture is the one bright thing in the room.
 *
 * Under a screen reader nothing goes. A view that has faded out cannot be reached by swiping, and
 * the title bar holds Back and the menu; the page readout stays up for the same reason.
 */
internal class OverChrome(
    private val activity: AppCompatActivity,
    private val night: NightChrome,
    private val screenReader: () -> Boolean,
    /** How every other viewer keeps clear of the phone's bars, put back by [land]. */
    private val keepClear: (View) -> Unit,
    /** The colour of the strip the title bar sits on, or null for the night bar's black. */
    @ColorInt private val ground: Int? = null,
    /** A finger put down on the title bar. */
    private val touched: () -> Unit = {},
    /**
     * What else over the picture keeps clear of the phone's bars: [clear] is where any of them can
     * be, [tappable] where one takes a tap, both whether they are showing or not.
     */
    private val keepClearOf: (clear: Insets, tappable: Insets) -> Unit = { _, _ -> },
) {
    private val root: ViewGroup = activity.findViewById(R.id.root)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.toolbar)
    private val saveProgress: View = activity.findViewById(R.id.saveProgress)

    // The frame the picture is in, with the things that float over a document
    private val stage = activity.findViewById<View>(R.id.container).parent as FrameLayout

    /** The title bar and the save bar under it, over the top of the picture. */
    private val top = object : LinearLayout(activity) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touched()
            return super.dispatchTouchEvent(event)
        }
    }.apply { orientation = LinearLayout.VERTICAL }

    private val window = activity.window
    private val bars = WindowCompat.getInsetsController(window, window.decorView)

    // As float() found them, for land(). The night colours put the rest back themselves.
    private val toolbarAt = root.indexOfChild(toolbar)
    private val saveProgressAt = root.indexOfChild(saveProgress)
    private val toolbarElevation = toolbar.elevation
    private val darkNavigationIcons = bars.isAppearanceLightNavigationBars
    private val barsBehaviour = bars.systemBarsBehavior

    // Read only below Android 15, where land() puts it back. Play lists a read with no version
    // check as a deprecated edge-to-edge call.
    @Suppress("DEPRECATION")
    private val navigationBarColour =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) window.navigationBarColor
        else Color.TRANSPARENT

    // Between float() and land(), the only time there are bars over a picture to show or hide
    private var floating = false

    fun float() {
        floating = true
        night.show(true)
        root.removeView(toolbar)
        root.removeView(saveProgress)
        // The toolbar's surface, night's now, moves out to the strip holding it, so it reaches up
        // behind the clock, and the shadow goes with it to that strip's lower edge. Let go of
        // first, since a view letting go of a drawable clears whatever it has been given to since.
        val surface = toolbar.background
        toolbar.background = null
        toolbar.elevation = 0f
        top.background = ground?.toDrawable() ?: surface
        top.elevation = toolbarElevation
        top.addView(toolbar)
        top.addView(saveProgress)
        stage.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        // Below Android 15 the window keeps clear of the bars itself unless told not to, and
        // paints them colours of its own over whatever is behind
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        }
        // Pale over a picture, as the night colours have them at the top
        bars.isAppearanceLightNavigationBars = false
        // Hidden, they come back for a swipe in from the edge and go again by themselves, and a
        // tap still reaches the picture
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // Where the bars can be rather than where they are, so their coming and going moves
            // nothing
            val clear = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val tappable = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.tappableElement() or WindowInsetsCompat.Type.displayCutout()
            )
            root.setPadding(0, 0, 0, 0)
            top.setPadding(clear.left, clear.top, clear.right, 0)
            keepClearOf(clear, tappable)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    // Up when the picture opens
    private var shown = true

    /** The title bar and the phone's bars faded in for [visible], or out, unless a screen reader is on. */
    fun show(visible: Boolean) {
        if (!floating) return
        val up = visible || screenReader()
        // Asked at every step a video's controls take, and a fade begun again would never finish
        if (up == shown) return
        shown = up
        top.animate().cancel()
        if (up) {
            top.visibility = View.VISIBLE
            top.animate().alpha(1f).setDuration(FADE_MS).start()
            bars.show(WindowInsetsCompat.Type.systemBars())
        } else {
            top.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction { top.visibility = View.INVISIBLE }.start()
            bars.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Gone if they are up, and back if they are gone. */
    fun toggle() = show(!shown)

    /**
     * A tap on [view], a photo or a track's screen, hides the bars, and the next brings them back.
     * A screen reader isn't told the view can be clicked, since under one the bars never go.
     */
    fun takeTapsOn(view: View) {
        view.setOnClickListener { toggle() }
        ViewCompat.setAccessibilityDelegate(view, TapForTheEye)
    }

    /** The viewer as every other file has it, for the card that stands in for a picture that will not show. */
    fun land() {
        floating = false
        top.animate().cancel()
        stage.removeView(top)
        top.removeAllViews()
        top.background = null
        toolbar.elevation = toolbarElevation
        root.addView(toolbar, toolbarAt)
        root.addView(saveProgress, saveProgressAt)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            @Suppress("DEPRECATION")
            window.navigationBarColor = navigationBarColour
        }
        bars.show(WindowInsetsCompat.Type.systemBars())
        bars.isAppearanceLightNavigationBars = darkNavigationIcons
        bars.systemBarsBehavior = barsBehaviour
        // The card is the app's, so the parts around it go back to the phone's colours, the
        // toolbar's own surface and the status bar's with them
        night.show(false)
        keepClear(root)
        ViewCompat.requestApplyInsets(root)
    }

    private companion object {
        /** A video player's own, for its controls. */
        const val FADE_MS = 250L
    }
}

/** What a screen reader hears of a view whose click only shows or hides the bars over it: no click. */
private object TapForTheEye : AccessibilityDelegateCompat() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.isClickable = false
        info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
    }
}
