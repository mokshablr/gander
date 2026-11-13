package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * What the viewer does when its player reports an error. A file that fails as it opens gets the
 * page saying Gander doesn't recognize the format. One that fails after it has been ready to play
 * for a while gets another go from where it stopped, as when a decoder gives out at the end of a
 * call (issue #38), unless it fails again straight after.
 *
 * The errors are raised on the player's own thread, as a decoder's are, so the viewer hears of
 * them as it would of a real one. Under Robolectric the tone has played to its end by the time
 * the viewer is up, so it is wound back and paused while the clock moves past
 * [PlayerScreen.RETRY_AFTER_MS].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerErrorTest {

    @get:Rule val closing = ClosesActivities()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
    }

    private fun play(): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(FixtureProvider.uriFor("tone.wav"), "audio/x-wav")
        return closing.build(ViewerActivity::class.java, intent).setup()
    }

    private val ActivityController<ViewerActivity>.shown: List<View>
        get() = get().findViewById<FrameLayout>(R.id.container).children.toList()

    private val ActivityController<ViewerActivity>.player: ExoPlayer
        get() = shown.filterIsInstance<PlayerView>().single().player as ExoPlayer

    /**
     * Runs the viewer's thread until [done], giving the player's own threads a moment of real
     * time at each turn. With [moving], the clock moves on 10 ms a turn, which the player needs
     * to get ready, since it schedules its work by that clock. Without, it stands still, so that
     * however slow the machine, waiting for an error never adds time toward a retry.
     */
    private fun until(moving: Boolean = true, done: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Never happened" }
            if (moving) looper.idleFor(Duration.ofMillis(10)) else looper.idle()
            Thread.sleep(1)
        }
    }

    /** Ready to play from the start, paused, and ready for long enough to be tried again. */
    private fun ExoPlayer.readyLongEnough() {
        pause()
        seekTo(0)
        until { playbackState == Player.STATE_READY }
        shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(PlayerScreen.RETRY_AFTER_MS))
    }

    private fun ExoPlayer.errors(): List<PlaybackException> {
        val heard = mutableListOf<PlaybackException>()
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                heard += error
            }
        })
        return heard
    }

    private fun ExoPlayer.fail() {
        createMessage { _, _ ->
            throw ExoPlaybackException.createForUnexpected(
                IllegalStateException("Decoder gave out"), PlaybackException.ERROR_CODE_DECODING_FAILED
            )
        }.send()
    }

    @Test
    fun aTrackThatFailsAsItOpensGetsThePageSayingSo() {
        val viewer = play()
        viewer.player.fail()
        until(moving = false) { viewer.shown.any { it is WebView } }
        assertThat(viewer.shown.filterIsInstance<PlayerView>()).isEmpty()
    }

    /**
     * Opening as text unasked is for files Gander has no viewer for. A track its player gave
     * up on keeps the card, which says the track would not play. Issue #51.
     */
    @Test
    fun aTrackThatFailsKeepsItsCard() {
        val viewer = play()
        viewer.player.fail()
        until(moving = false) {
            viewer.shown.filterIsInstance<WebView>().any { shadowOf(it).lastLoadedUrl != null }
        }
        val url = shadowOf(viewer.shown.filterIsInstance<WebView>().single()).lastLoadedUrl
        assertThat(url).contains("viewer/unsupported.html")
        assertThat(viewer.get().findViewById<MaterialToolbar>(R.id.toolbar).subtitle).isNull()
    }

    @Test
    fun aTrackThatFailsAfterAWhileGoesOnFromWhereItStopped() {
        val viewer = play()
        val player = viewer.player
        player.readyLongEnough()
        player.seekTo(500)
        player.play()
        val errors = player.errors()
        player.fail()
        until(moving = false) { errors.isNotEmpty() }

        assertThat(viewer.player).isSameInstanceAs(player)
        assertThat(viewer.shown.none { it is WebView }).isTrue()
        assertThat(player.playbackState).isNotEqualTo(Player.STATE_IDLE)
        assertThat(player.playWhenReady).isTrue()
        assertThat(player.currentPosition).isAtLeast(500)
    }

    /** As a file damaged partway does, when it is tried again from just before the damage. */
    @Test
    fun aTrackThatFailsAgainStraightAfterGetsThePage() {
        val viewer = play()
        val player = viewer.player
        player.readyLongEnough()
        val errors = player.errors()
        player.fail()
        until { errors.isNotEmpty() && player.playbackState == Player.STATE_READY }

        player.fail()
        until(moving = false) { viewer.shown.any { it is WebView } }
        assertThat(viewer.shown.filterIsInstance<PlayerView>()).isEmpty()
    }
}
