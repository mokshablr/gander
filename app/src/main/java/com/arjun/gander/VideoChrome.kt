package com.arjun.gander

import android.content.pm.ActivityInfo
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView

/**
 * The title bar and the phone's bars over a video, floating over the picture as [OverChrome] has
 * them, come and go with the player's own controls. Issue #33. One tap brings everything back, and
 * it all goes again with the controls, unless the title bar or a menu or box opened from it is in
 * use.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoChrome(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    night: NightChrome,
    screenReader: () -> Boolean,
    /** How every other viewer keeps clear of the phone's bars, put back by [land]. */
    keepClear: (View) -> Unit,
) {
    private val controls: PlayerControlView? =
        playerView.findViewById(androidx.media3.ui.R.id.exo_controller)

    private val over = OverChrome(
        activity, night, screenReader, keepClear,
        // A finger on the title bar is using it, so the countdown starts again, as it does for a
        // finger on the player's own controls
        touched = { playerView.showController() },
        // The controls keep clear only of what takes a tap. Three buttons at the bottom do; the
        // handle of gesture navigation takes a swipe, and taps under it still reach the player, so
        // kept clear of it the controls stopped a handle's height off the bottom.
        keepClearOf = { _, tappable ->
            controls?.setPadding(tappable.left, tappable.top, tappable.right, tappable.bottom)
        },
    )

    /** How long the controls stay up untouched, as the viewer set it. */
    private val timeout = playerView.controllerShowTimeoutMs

    /** How the screen is held for full screen, or null while the phone turns it. */
    var held: Int? = null
        private set

    fun float() {
        over.float()
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> follow(visibility == View.VISIBLE) }
        )
    }

    // Offered once the video is known to be wider than it is tall
    private var offered = false

    /**
     * The full screen button, on a video wider than it is tall, which it turns to landscape. An
     * upright one, as a phone films, already fills an upright screen and landscape would only
     * shrink it, so it gets no button that seems to do nothing. None until the size is known.
     */
    fun sized(size: VideoSize) {
        val wide = widerThanTall(size)
        if (wide == offered) return
        offered = wide
        if (wide) {
            playerView.setFullscreenButtonClickListener { on ->
                hold(if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else null)
            }
        } else {
            // Deprecated, but the player view hands the controls a listener of its own whatever it
            // is given, so only the controls' own setter can take the button away again
            @Suppress("DEPRECATION")
            controls?.setOnFullScreenModeChangedListener(null)
            if (held != null) hold(null)
        }
    }

    /**
     * Holds the screen at [orientation] for full screen, or gives it back to the phone for null.
     * Called with what [held] was when the viewer is made again, so a video in full screen stays so.
     */
    fun hold(orientation: Int?) {
        held = orientation
        playerView.setFullscreenButtonState(orientation != null)
        activity.requestedOrientation = orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /**
     * Everything stays up while a menu or a box opened from the title bar is open over the video.
     * Either takes the window's focus, which the player's countdown knows nothing of, so the title
     * bar faded out from under the menu opened from it. The countdown starts again when it closes.
     */
    fun focusChanged(hasFocus: Boolean) {
        // Applied at once while the controls are fully up, and to their next showing otherwise
        playerView.controllerShowTimeoutMs = if (hasFocus) timeout else 0
    }

    private var controlsWereGone = false

    /**
     * Along with the player's controls, which go in two steps: the buttons after the timeout, and
     * two seconds later the line of progress left at the bottom. They say they are hidden only at
     * the second, but they report every step on the way and are fully up only before the first.
     * So everything goes as soon as they stop being fully up, and comes back the moment they
     * reappear from nothing. From the line of progress, a tap brings the title bar back once the
     * controls are fully up, since on the way in and on the way out look alike from here.
     */
    private fun follow(controlsVisible: Boolean) {
        val fullyUp = playerView.isControllerFullyVisible
        if (fullyUp) {
            // The player leaves its controls up for good if it was told to play while they were
            // still coming in: prepared first, they came up to stay until playback began, and it
            // counts the time to hide them only if they are fully up when it begins. Asked again
            // now that they are, it counts from here, or keeps them up for a paused or finished
            // video, whichever the player is by now.
            playerView.showController()
        }
        over.show(
            when {
                !controlsVisible -> false
                fullyUp -> true
                else -> controlsWereGone
            }
        )
        controlsWereGone = !controlsVisible
    }

    /** The viewer as every other file has it, for the card that stands in for a video that will not play. */
    fun land() {
        over.land()
        held = null
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

/** Whether a video of this size shows wider than it is tall, its pixels' own shape included. */
internal fun widerThanTall(size: VideoSize): Boolean =
    size.width * size.pixelWidthHeightRatio > size.height
