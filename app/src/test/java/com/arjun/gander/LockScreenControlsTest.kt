package com.arjun.gander

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * A track plays on with the screen off, with play and pause on the lock screen. Issue #38.
 *
 * Nothing here plays a sound, and Robolectric's media session is a stand-in with no token, so
 * what is checked is what the viewer decides: which stops pause, what goes on the lock screen
 * and when, and what its buttons do. That the lock screen draws the controls, with no permission,
 * and that a phone stays awake through a track, is for a device.
 */
@RunWith(AndroidJUnit4::class)
class LockScreenControlsTest {

    @get:Rule val closing = ClosesActivities()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
    }

    private val track: Uri get() = FixtureProvider.uriFor("tone.wav")

    /** The same sound under a video's name, which is what decides between the two. */
    private val video: Uri get() = FixtureProvider.uriNamed("tone.wav", "clip.mp4")

    private fun play(uri: Uri): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/x-wav")
        return closing.build(ViewerActivity::class.java, intent).setup()
            .also { idle() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val ActivityController<ViewerActivity>.player: Player
        get() {
            val container = get().findViewById<FrameLayout>(R.id.container)
            val view = (0 until container.childCount).map { container.getChildAt(it) }
                .filterIsInstance<PlayerView>().single()
            return view.player!!
        }

    private fun screen(on: Boolean) =
        shadowOf(context.getSystemService(PowerManager::class.java)).turnScreenOn(on)

    private fun locked(on: Boolean) =
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(on)

    private fun posted(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    /** Stopped as Android stops it when the screen goes off, or when it is left. */
    private fun ActivityController<ViewerActivity>.stopped() = apply {
        pause().stop()
        idle()
    }

    @Test
    fun aTrackPlaysOnWhenTheScreenGoesOff() {
        val viewer = play(track)
        screen(on = false)
        viewer.stopped()
        assertThat(viewer.player.playWhenReady).isTrue()
    }

    /**
     * A call wakes the screen, and when it ends the lock screen comes back with the screen still
     * on, which stops the viewer. The track plays on, with its controls, once the call is over.
     */
    @Test
    fun theLockScreenComingBackWithTheScreenOnIsNotLeaving() {
        val viewer = play(track)
        locked(on = true)
        viewer.stopped()
        assertThat(viewer.player.playWhenReady).isTrue()
        assertThat(posted()).hasSize(1)
    }

    @Test
    fun leavingATrackPausesIt() {
        val viewer = play(track)
        viewer.stopped()
        assertThat(viewer.player.playWhenReady).isFalse()
        assertThat(posted()).isEmpty()
    }

    @Test
    fun aVideoPausesWhenTheScreenGoesOff() {
        val viewer = play(video)
        assertThat(viewer.get().playerScreen!!.lockScreen).isNull()
        screen(on = false)
        viewer.stopped()
        assertThat(viewer.player.playWhenReady).isFalse()
        assertThat(posted()).isEmpty()
    }

    @Test
    fun theLockScreenGetsTheTracksControlsWhileItPlaysOn() {
        val viewer = play(track)
        assertThat(posted()).isEmpty()
        screen(on = false)
        viewer.stopped()
        val controls = posted().single()
        assertThat(controls.extras.getString(Notification.EXTRA_TEMPLATE))
            .isEqualTo(Notification.MediaStyle::class.java.name)
        assertThat(controls.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("tone.wav")
        assertThat(controls.visibility).isEqualTo(Notification.VISIBILITY_PUBLIC)
    }

    @Test
    fun comingBackTakesTheControlsOffTheLockScreen() {
        val viewer = play(track)
        screen(on = false)
        viewer.stopped()
        screen(on = true)
        viewer.start().resume()
        idle()
        assertThat(posted()).isEmpty()
        assertThat(viewer.player.playWhenReady).isTrue()
    }

    /** Put down with the screen on, a track stays down, and puts nothing on the lock screen. */
    @Test
    fun aPausedTrackStaysPausedWithNothingOnTheLockScreen() {
        val viewer = play(track)
        viewer.player.pause()
        screen(on = false)
        viewer.stopped()
        assertThat(viewer.player.playWhenReady).isFalse()
        assertThat(posted()).isEmpty()
    }

    @Test
    fun theLockScreensButtonsPauseAndPlayTheTrack() {
        val viewer = play(track)
        screen(on = false)
        viewer.stopped()
        val controls = viewer.get().playerScreen!!.lockScreen!!
        controls.callback.onPause()
        assertThat(viewer.player.playWhenReady).isFalse()
        controls.callback.onPlay()
        assertThat(viewer.player.playWhenReady).isTrue()
    }

    /**
     * Left, the track lets go of a headset's button, so pressing it cannot start the track again
     * behind whatever is in front. In front again, it takes the button back.
     */
    @Test
    fun onlyATrackInFrontOrPlayingOnAnswersAHeadset() {
        val viewer = play(track)
        assertThat(viewer.get().playerScreen!!.lockScreen!!.session?.isActive).isTrue()
        viewer.stopped()
        assertThat(viewer.get().playerScreen!!.lockScreen!!.session).isNull()
        viewer.start()
        assertThat(viewer.get().playerScreen!!.lockScreen!!.session?.isActive).isTrue()
        screen(on = false)
        viewer.stopped()
        assertThat(viewer.get().playerScreen!!.lockScreen!!.session?.isActive).isTrue()
    }
}
