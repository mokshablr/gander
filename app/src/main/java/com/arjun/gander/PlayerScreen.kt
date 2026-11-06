package com.arjun.gander

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Plays a video or a track. A track gets a screen of its own: controls that stay up, since
 * nothing else is on it, a display allowed to sleep, and its cover art or a note where a
 * picture would be. It plays on with the screen off, but not once the reader leaves: see [stopped].
 *
 * [state] is what [save] kept for a viewer made again around the player, and [failed] shows the
 * page saying the file can't be played.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlayerScreen(
    private val activity: AppCompatActivity,
    private val nightChrome: NightChrome,
    private val touchExplorationOn: () -> Boolean,
    private val applySystemBarInsets: (View) -> Unit,
    state: Bundle?,
    private val failed: () -> Unit,
) {

    companion object {
        private const val STATE_POSITION = "player_position"
        private const val STATE_FULL_SCREEN = "full_screen"

        /** Cover art target while the view has not been measured yet. */
        private const val ART_FALLBACK_PX = 512

        /**
         * How long a video or a track has to have been ready to play before an error is put
         * down to something giving out underneath it rather than to the file. See onPlayerError
         * in [show].
         *
         * Long enough that a file damaged partway, which fails again as soon as it is tried
         * again from just before the damage, is not tried for ever.
         */
        @androidx.annotation.VisibleForTesting
        internal const val RETRY_AFTER_MS = 5000L
    }

    private var player: ExoPlayer? = null

    /** Play and pause on the lock screen, for a track. A video has none: see [stopped]. */
    @androidx.annotation.VisibleForTesting
    internal var lockScreen: LockScreenControls? = null
        private set

    /** The title bar and the phone's bars over a video. See [VideoChrome]. */
    internal var videoChrome: VideoChrome? = null
        private set

    /** The title bar and the phone's bars over a track's screen, which a tap hides. See [OverChrome]. */
    internal var trackChrome: OverChrome? = null
        private set

    /** Where a video or a track picks up when the viewer is rebuilt around it, as a change of theme does. */
    private val startAt = state?.getLong(STATE_POSITION) ?: 0L

    /** How the screen was held for a video in full screen, before the viewer was made again. */
    private val heldBefore = state?.takeIf { it.containsKey(STATE_FULL_SCREEN) }?.getInt(STATE_FULL_SCREEN)

    /**
     * Album art at the size it will be drawn, not the size it was stored.
     *
     * A bounds pass reads the header only, then inSampleSize halves until one more halving
     * would go under the view. The target is the view's own width, with a fallback for the
     * first frame, where it has not been measured yet.
     */
    private fun sampledArt(bytes: ByteArray, target: Int): Bitmap? {
        val want = if (target > 0) target else ART_FALLBACK_PX
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= want) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun show(container: FrameLayout, uri: Uri, name: String, ext: String) {
        val audio = FileKind.isAudioExt(ext)
        // Audio comes from a layout because its transport does: controller_layout_id is
        // an XML attribute with no setter behind it.
        val playerView =
            if (audio) activity.layoutInflater.inflate(R.layout.view_audio_player, container, false) as PlayerView
            else PlayerView(activity)
        // The cover, and the note that stands in for one. It sits in the transport layout
        // rather than PlayerView's own artwork slot, because that slot centres what it is
        // given and centred is where the play button is: the picture came out underneath
        // the control covering it.
        val cover: ImageView?
        if (audio) {
            // Nothing is being looked at, so the screen may sleep, and the transport is
            // the only thing on the display, so it stays put. The track plays on when the
            // screen sleeps: see stopped.
            playerView.keepScreenOn = false
            playerView.controllerShowTimeoutMs = 0
            // Warm near-black rather than pure black. A track is a screen somebody sits
            // and looks at, so it may as well be the dark the rest of the app uses.
            container.setBackgroundColor(ContextCompat.getColor(activity, R.color.audio_backdrop))
            cover = playerView.findViewById<ImageView>(R.id.audioCover)?.apply {
                contentDescription = name
            }

            playerView.setBackgroundColor(Color.TRANSPARENT)
            playerView.setShutterBackgroundColor(Color.TRANSPARENT)
            playerView.artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_OFF
            playerView.controllerHideOnTouch = false
            // One file, so there is nothing to be previous or next to.
            playerView.setShowPreviousButton(false)
            playerView.setShowNextButton(false)
        } else {
            cover = null
            playerView.keepScreenOn = true
            playerView.controllerShowTimeoutMs = 2500
            playerView.setBackgroundColor(Color.BLACK)
        }
        container.addView(
            playerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        if (!audio) {
            videoChrome = VideoChrome(
                activity, playerView, nightChrome, touchExplorationOn, applySystemBarInsets
            ).apply {
                float()
                heldBefore?.let { hold(it) }
            }
        } else {
            // Over a track's screen as over a video, on its own dark. A tap around the controls
            // hides the title bar and the phone's bars, and the next brings them back; the
            // controls stay, since nothing else is on the screen. Issue #71.
            val controls = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
            trackChrome = OverChrome(
                activity, nightChrome, touchExplorationOn, applySystemBarInsets,
                ground = ContextCompat.getColor(activity, R.color.audio_backdrop),
                // Clear of what takes a tap, as a video's controls are
                keepClearOf = { _, tappable ->
                    controls?.setPadding(tappable.left, tappable.top, tappable.right, tappable.bottom)
                },
            ).apply {
                float()
                takeTapsOn(playerView)
            }
        }

        val exo = ExoPlayer.Builder(activity)
            // Asks for the speaker as a player is expected to, so a call or another app's
            // sound pauses this one, and pauses when headphones come out rather than carrying
            // on out loud. Both matter most with the screen off, with nobody watching.
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exo
        playerView.player = exo
        if (audio) lockScreen = LockScreenControls(activity, exo, name)
        exo.addListener(object : Player.Listener {
            /**
             * Cover art, once the extractor has read the tags. Taken from the player
             * rather than opened a second time with a MediaMetadataRetriever, because
             * ExoPlayer has already parsed the ID3 or Vorbis picture by the time this
             * fires and holds the bytes.
             *
             * Sampled down first, the same way Thumbs decodes a photo. Embedded art is
             * commonly 1500 square and not rarely 3000, and this callback arrives on the
             * main thread as playback starts: decoded whole, a 3000 square picture is
             * some 36 MB of ARGB for a view a couple of hundred dp wide, which is the
             * one moment on this screen that cannot afford it. The tags are also
             * re-delivered as they arrive, so the same bytes are decoded once.
             */
            private var artDecoded: ByteArray? = null

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val view = cover ?: return
                val bytes = mediaMetadata.artworkData ?: return
                if (bytes === artDecoded) return
                val bmp = runCatching { sampledArt(bytes, view.width) }.getOrNull() ?: return
                artDecoded = bytes
                view.setImageBitmap(bmp)
                lockScreen?.art = bmp
            }

            /** Which way round the picture is, which decides whether full screen is offered. */
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoChrome?.sized(videoSize)
            }

            /**
             * Since when the player has been ready to play, by elapsedRealtime, counted afresh
             * each time it is prepared. Null while it has not been. See onPlayerError.
             */
            private var readySince: Long? = null

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && readySince == null) {
                    readySince = SystemClock.elapsedRealtime()
                }
            }

            /**
             * A file that fails as it opens is one Gander cannot play, and gets the page that
             * says so. One that fails after it has been ready to play for a while is one it
             * can, and something gave out underneath it instead: on an emulator, Android's
             * decoder did as a call ended over a track (issue #38). That gets another go: a
             * player prepared again after an error carries on from where it stopped, playing
             * if it was playing.
             *
             * Failing again soon after means the file is damaged there, and gets the page.
             */
            override fun onPlayerError(error: PlaybackException) {
                val since = readySince
                readySince = null
                if (since != null && SystemClock.elapsedRealtime() - since >= RETRY_AFTER_MS) {
                    exo.prepare()
                    return
                }
                lockScreen?.release()
                lockScreen = null
                exo.release()
                player = null
                videoChrome?.land()
                videoChrome = null
                trackChrome?.land()
                trackChrome = null
                container.removeAllViews()
                failed()
            }
        })
        exo.setMediaItem(MediaItem.fromUri(uri), startAt)
        exo.prepare()
        exo.playWhenReady = true
    }

    /** What a viewer made again around this one needs to carry on where it was. */
    fun save(outState: Bundle) {
        player?.let { outState.putLong(STATE_POSITION, it.currentPosition) }
        videoChrome?.held?.let { outState.putInt(STATE_FULL_SCREEN, it) }
    }

    fun started() {
        lockScreen?.inFront()
    }

    /**
     * Pauses whatever is playing, unless this is a track and the screen has gone off with it
     * playing, or the lock screen has come back over it, neither of which is leaving it. Issue #38.
     *
     * Playing on after leaving would need a foreground service, and so a permission. Playing on
     * with the screen off needs none: the viewer is still the thing in front, only asleep, and
     * Android's audio service keeps the phone awake while a track plays. A video stops all the
     * same, since there is nothing left of it to watch.
     *
     * The lock screen counts because of calls. A call wakes the screen over the lock screen, and
     * when it ends the lock screen comes back with the screen still on, which stops the viewer.
     * Taken as leaving, that paused the track for good and took its controls away.
     */
    fun stopped() {
        val screenOn = activity.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val locked = activity.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        val controls = lockScreen
        if (controls != null && player?.playWhenReady == true && (!screenOn || locked)) {
            controls.playingOn()
        } else {
            player?.pause()
            controls?.leftPaused()
        }
    }

    fun release() {
        lockScreen?.release()
        lockScreen = null
        player?.release()
        player = null
    }
}
