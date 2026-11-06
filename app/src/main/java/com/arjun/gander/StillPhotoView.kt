package com.arjun.gander

import android.content.Context
import android.view.View
import androidx.core.view.doOnNextLayout
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * The tiling view for a still photo, which the reader can turn a quarter turn at a time with
 * Rotate. Issue #71. Only the view turns: the file stays as it is.
 *
 * On its side the view lays itself out as tall as its space is wide, centred on that space, and
 * turns about its middle, so a sideways photo fits the screen as an upright one does, and a pinch
 * or a drag moves it the way it faces. Turning the view rather than the picture it draws keeps the
 * tiles already decoded: the library's own orientation lets every tile go and decodes them again,
 * drawing nothing until the first are back.
 */
internal class StillPhotoView(context: Context) : SubsamplingScaleImageView(context) {

    /** How far it is turned, clockwise: 0, 90, 180 or 270. */
    var turn = 0
        private set

    /** On by a quarter turn clockwise, fitted to the screen again. */
    fun turnOn() {
        turnTo(turn + QUARTER)
        // Once laid out the new way round, since the library keeps the scale it had for the old
        // one. A photo not yet drawn is fitted to the new way round as it is drawn.
        if (isReady) doOnNextLayout { resetScaleAndCenter() }
    }

    /** Turned to [degrees] clockwise, for a photo shown again at the turn it was given. */
    fun turnTo(degrees: Int) {
        val to = Math.floorMod(degrees, FULL)
        if (to == turn) return
        val flips = (to - turn) % HALF != 0
        turn = to
        rotation = to.toFloat()
        if (flips) requestLayout()
    }

    private val sideways get() = turn % HALF != 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (sideways) super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Centred on the space it was given, which it is laid out across rather than along on its side
        val space = parent as? View ?: return
        val middleX = space.paddingLeft + (space.width - space.paddingLeft - space.paddingRight) / 2f
        val middleY = space.paddingTop + (space.height - space.paddingTop - space.paddingBottom) / 2f
        translationX = middleX - (left + right) / 2f
        translationY = middleY - (top + bottom) / 2f
    }

    private companion object {
        const val QUARTER = 90
        const val HALF = 180
        const val FULL = 360
    }
}
