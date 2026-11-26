package com.arjun.gander

/**
 * The geometry of the drag-to-scroll thumb.
 *
 * Four numbers describe it, and all four come from the WebView: how tall the
 * document is, how much of it fits on screen, how far down it is scrolled, and
 * how tall the track beside it is. None of that needs a WebView to reason
 * about, so it is arithmetic here and drawing there.
 */

/**
 * Whether the thumb belongs on screen, given where it was.
 *
 * Two thresholds rather than one. pptx.html reports itself finished as soon as
 * the first slide exists and keeps appending for seconds after, so a single
 * line here has the thumb appear, vanish and appear again while a deck loads.
 * Between the two the previous answer stands, which is what makes a growing
 * document settle instead of flickering.
 */
internal fun thumbShown(range: Int, extent: Int, wasShown: Boolean): Boolean = when {
    range > extent * 2 -> true
    range < extent * 3 / 2 -> false
    else -> wasShown
}

/**
 * Where the top of the thumb sits, as a pixel offset down the track, for a
 * document scrolled to [offset]. Zero when there is nothing to scroll.
 */
internal fun thumbOffset(
    trackHeight: Int,
    thumbHeight: Int,
    offset: Int,
    range: Int,
    extent: Int
): Float {
    val travel = (trackHeight - thumbHeight).toFloat()
    val scrollable = (range - extent).toFloat()
    if (travel <= 0f || scrollable <= 0f) return 0f
    return travel * (offset / scrollable)
}

/**
 * The reverse: how far down the document a thumb dragged to [at] points.
 *
 * [at] is already clamped to the track by the caller, which needs the clamped
 * value to draw with anyway. Null when there is nothing to scroll, so the
 * caller can skip the hop into the renderer rather than asking for zero.
 */
internal fun dragTarget(at: Float, travel: Float, range: Int, extent: Int): Int? {
    if (travel <= 0f) return null
    val scrollable = range - extent
    if (scrollable <= 0) return null
    return ((at / travel) * scrollable).toInt()
}
