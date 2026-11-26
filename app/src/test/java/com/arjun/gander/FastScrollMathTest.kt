package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The scroll thumb, as four numbers.
 *
 * A phone screen is taken as 2000 px tall through most of this, with a track
 * a little shorter, which is roughly what the real layout gives.
 */
class FastScrollMathTest {

    private companion object {
        const val EXTENT = 2000 // one screenful
        const val TRACK = 1800 // the track beside it
        const val THUMB = 144 // R.dimen.fast_scroll_thumb_height, at 3x
    }

    // ---------------------------------------------------------------
    // thumbShown: the hysteresis that stops a loading deck flickering
    // ---------------------------------------------------------------

    @Test
    fun aDocumentTwiceTheScreenGetsAThumb() {
        assertThat(thumbShown(EXTENT * 2 + 1, EXTENT, wasShown = false)).isTrue()
        assertThat(thumbShown(EXTENT * 10, EXTENT, wasShown = false)).isTrue()
    }

    @Test
    fun aDocumentBarelyLongerThanTheScreenGetsNone() {
        assertThat(thumbShown(EXTENT, EXTENT, wasShown = true)).isFalse()
        assertThat(thumbShown(EXTENT * 5 / 4, EXTENT, wasShown = true)).isFalse()
    }

    /**
     * Between one and a half screens and two, the previous answer stands.
     * pptx.html grows for seconds after it says it has finished, and a single
     * threshold has the thumb appear, vanish and appear again as it does.
     */
    @Test
    fun betweenTheThresholdsTheAnswerHoldsStill() {
        val inBand = EXTENT * 7 / 4
        assertThat(thumbShown(inBand, EXTENT, wasShown = true)).isTrue()
        assertThat(thumbShown(inBand, EXTENT, wasShown = false)).isFalse()
    }

    /** A deck that grows past the band never flickers on the way. */
    @Test
    fun aGrowingDocumentSettlesOnceAndStays() {
        var shown = false
        val seen = (1..12).map { screens ->
            shown = thumbShown(EXTENT * screens / 4, EXTENT, shown)
            shown
        }
        // Once true it stays true, so there is at most one transition
        assertThat(seen.zipWithNext().count { (a, b) -> a != b }).isAtMost(1)
        assertThat(seen.last()).isTrue()
    }

    // ---------------------------------------------------------------
    // thumbOffset
    // ---------------------------------------------------------------

    @Test
    fun theThumbSitsAtTheTopOfAnUnscrolledDocument() {
        assertThat(thumbOffset(TRACK, THUMB, 0, EXTENT * 4, EXTENT)).isEqualTo(0f)
    }

    @Test
    fun theThumbReachesTheFootOfTheTrackAtTheEnd() {
        val range = EXTENT * 4
        val atEnd = thumbOffset(TRACK, THUMB, range - EXTENT, range, EXTENT)
        assertThat(atEnd).isWithin(0.01f).of((TRACK - THUMB).toFloat())
    }

    @Test
    fun halfwayDownIsHalfwayAlong() {
        val range = EXTENT * 4
        val half = thumbOffset(TRACK, THUMB, (range - EXTENT) / 2, range, EXTENT)
        assertThat(half).isWithin(1f).of((TRACK - THUMB) / 2f)
    }

    @Test
    fun aDocumentThatFitsOnScreenPinsTheThumbAtTheTop() {
        assertThat(thumbOffset(TRACK, TRACK, 0, EXTENT, EXTENT)).isEqualTo(0f)
    }

    // ---------------------------------------------------------------
    // dragTarget
    // ---------------------------------------------------------------

    @Test
    fun draggingTheThumbMapsBackOntoTheDocument() {
        val range = EXTENT * 10
        val travel = (TRACK - THUMB).toFloat()
        assertThat(dragTarget(0f, travel, range, EXTENT)).isEqualTo(0)
        assertThat(dragTarget(travel, travel, range, EXTENT)).isEqualTo(range - EXTENT)
        assertThat(dragTarget(travel / 2, travel, range, EXTENT))
            .isEqualTo((range - EXTENT) / 2)
    }

    /**
     * Null rather than zero, so the caller skips the scroll entirely. Every
     * scrollTo is a hop into the renderer process and this one would move
     * nothing.
     */
    @Test
    fun thereIsNoTargetWhenThereIsNothingToScroll() {
        assertThat(dragTarget(10f, 100f, EXTENT, EXTENT)).isNull()
        assertThat(dragTarget(10f, 0f, EXTENT * 4, EXTENT)).isNull()
    }

    /** A drag and the position it produces agree, which is what stops the thumb jumping. */
    @Test
    fun draggingAndDrawingAreInverses() {
        val range = EXTENT * 6
        val travel = (TRACK - THUMB).toFloat()
        listOf(0f, travel * 0.25f, travel * 0.5f, travel).forEach { at ->
            val scrolled = dragTarget(at, travel, range, EXTENT)!!
            assertThat(thumbOffset(TRACK, THUMB, scrolled, range, EXTENT)).isWithin(1f).of(at)
        }
    }
}
