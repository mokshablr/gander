package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The title bar and the phone's bars over a video, issue #33.
 *
 * The videos here are the tone under a video's name. Which screen a file gets is decided from its
 * name, and a sound plays under Robolectric where a picture would need a decoder it has not got.
 */
@RunWith(AndroidJUnit4::class)
class VideoChromeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
    }

    private fun open(uri: Uri): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
    }

    private fun video(): ActivityController<ViewerActivity> =
        open(FixtureProvider.uriNamed("tone.wav", "clip.mp4"))

    private val ViewerActivity.root get() = findViewById<ViewGroup>(R.id.root)
    private val ViewerActivity.toolbar get() = findViewById<MaterialToolbar>(R.id.toolbar)
    private val ViewerActivity.saveProgress get() = findViewById<View>(R.id.saveProgress)
    private val ViewerActivity.stage get() = findViewById<View>(R.id.container).parent as FrameLayout
    private val ViewerActivity.player
        get() = findViewById<FrameLayout>(R.id.container).children.filterIsInstance<PlayerView>().single()
    private val ViewerActivity.fullScreenButton
        get() = player.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)

    /** What holds the title bar over a video. */
    private val ViewerActivity.top get() = toolbar.parent as View

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    // ---------------------------------------------------------------
    // Where things are
    // ---------------------------------------------------------------

    @Test
    fun aVideosTitleBarFloatsOverThePictureWithTheSaveBarUnderIt() {
        val viewer = video().get()

        assertThat(viewer.top.parent).isSameInstanceAs(viewer.stage)
        assertThat(viewer.saveProgress.parent).isSameInstanceAs(viewer.top)
        // Its surface carried up behind the clock, on the strip rather than the bar
        assertThat(viewer.top.background).isNotNull()
        assertThat(viewer.toolbar.background).isNull()
    }

    /** A track's transport is a layout of Gander's own, with no full screen button in it to show. */
    @Test
    fun aSoundHasNoFullScreenButton() {
        val viewer = open(FixtureProvider.uriFor("tone.wav")).get()
        val button: View? = viewer.player.findViewById(androidx.media3.ui.R.id.exo_fullscreen)
        assertThat(button?.visibility ?: View.GONE).isNotEqualTo(View.VISIBLE)
    }

    /** The card for a file Gander can't open, with its title bar above it: where a video that will not play ends up. */
    private fun card(): ViewerActivity = open(FixtureProvider.uriNamed("opaque.xyz", "slides.ppt")).get()

    private val statusBar = Insets.of(0, 63, 0, 0)
    private val navigationBar = Insets.of(0, 0, 0, 126)

    /**
     * The phone's bars, shown or hidden: where they can be stays the same either way. What takes a
     * tap is the status bar, and the navigation bar only when it has [buttons]: the handle of
     * gesture navigation takes swipes, and taps under it reach the app.
     */
    private fun bars(shown: Boolean, buttons: Boolean = false): WindowInsetsCompat {
        val tappable = if (buttons) Insets.add(statusBar, navigationBar) else statusBar
        return WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), if (shown) statusBar else Insets.NONE)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), if (shown) navigationBar else Insets.NONE)
            .setInsets(WindowInsetsCompat.Type.tappableElement(), if (shown) tappable else Insets.NONE)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), statusBar)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigationBar)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.tappableElement(), tappable)
            .setVisible(WindowInsetsCompat.Type.systemBars(), shown)
            .build()
    }

    private fun View.padding() = listOf(paddingLeft, paddingTop, paddingRight, paddingBottom)

    private fun ViewerActivity.paddings(): List<List<Int>> = listOf(
        root.padding(),
        top.padding(),
        player.findViewById<View>(androidx.media3.ui.R.id.exo_controller).padding(),
    )

    @Test
    fun thePictureFillsTheScreenAndNothingMovesWhenTheBarsGo() {
        val viewer = video().get()

        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars(shown = true))
        val shown = viewer.paddings()
        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars(shown = false))

        assertThat(shown).isEqualTo(listOf(
            listOf(0, 0, 0, 0),
            // The title bar's strip reaches behind the clock
            listOf(0, 63, 0, 0),
            // And the controls keep clear of it, and reach the bottom under a gesture handle
            listOf(0, 63, 0, 0),
        ))
        assertThat(viewer.paddings()).isEqualTo(shown)
    }

    /** Three buttons take taps, so the controls stay above them. */
    @Test
    fun withThreeButtonNavigationTheControlsStayAboveTheButtons() {
        val viewer = video().get()

        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars(shown = true, buttons = true))
        val shown = viewer.paddings()
        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars(shown = false, buttons = true))

        assertThat(shown.last()).isEqualTo(listOf(0, 63, 0, 126))
        assertThat(viewer.paddings()).isEqualTo(shown)
    }

    // ---------------------------------------------------------------
    // Its colours
    // ---------------------------------------------------------------

    /** The app's colours as a phone set to dark has them. */
    private val night: Context by lazy {
        val conf = Configuration(context.resources.configuration)
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_YES
        context.createConfigurationContext(conf)
    }

    private fun textColour(themed: Context): Int {
        val values = themed.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        try {
            return values.getColorStateList(0)!!.defaultColor
        } finally {
            values.recycle()
        }
    }

    private fun MaterialToolbar.titleColour(): Int =
        children.filterIsInstance<TextView>().first { it.text == title }.currentTextColor

    private fun View.surface(): Int? = (background as? MaterialShapeDrawable)?.fillColor?.defaultColor

    /** A film is dark whatever the phone is set to, and the bar over it is black, with pale icons. */
    @Test
    fun onAPhoneSetToLightTheBarOverAVideoTakesTheNightColours() {
        val nightText = textColour(ContextThemeWrapper(night, R.style.Theme_Gander))
        val viewer = video().get()

        assertThat(viewer.top.surface()).isEqualTo(Color.BLACK)
        assertThat(viewer.toolbar.titleColour()).isEqualTo(nightText)
        assertThat(WindowCompat.getInsetsController(viewer.window, viewer.window.decorView)
            .isAppearanceLightStatusBars).isFalse()
    }

    // ---------------------------------------------------------------
    // Coming and going
    // ---------------------------------------------------------------

    @Test
    fun theTitleBarGoesWithTheControlsAndComesBackWithThem() {
        val viewer = video().get()

        viewer.player.hideController()
        settle()
        assertThat(viewer.top.visibility).isEqualTo(View.INVISIBLE)
        assertThat(viewer.top.alpha).isEqualTo(0f)

        viewer.player.showController()
        // On its way in as they start in, rather than once they are all the way up
        assertThat(viewer.player.isControllerFullyVisible).isFalse()
        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)
        settle()
        assertThat(viewer.top.alpha).isEqualTo(1f)
    }

    /**
     * The player hides its buttons after the timeout and its line of progress two seconds later,
     * and says it is hidden only then. The title bar goes with the buttons.
     */
    @Test
    fun theTitleBarGoesWithThePlayersButtonsNotItsLineOfProgress() {
        val viewer = video().get()
        // Timed out whatever state the tone's playback has reached here: shown to stay up, the
        // controls would wait for an ended or paused player to be touched
        viewer.player.controllerAutoShow = false
        viewer.player.showController()
        settle()
        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500))
        // The line of progress is still there, and nothing else
        assertThat(viewer.findViewById<View>(androidx.media3.ui.R.id.exo_controller).visibility)
            .isEqualTo(View.VISIBLE)
        assertThat(viewer.player.isControllerFullyVisible).isFalse()
        assertThat(viewer.top.visibility).isEqualTo(View.INVISIBLE)
    }

    /**
     * The player's own race, which would keep the title bar up with its controls: shown to stay
     * while the video was being prepared, and told to play before they were fully in, the controls
     * never counted down to hide. Here they are made to stay that way directly.
     */
    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun controlsShownToStayWhileTheVideoPlaysStillGo() {
        val viewer = video().get()
        viewer.player.controllerAutoShow = false
        viewer.player.hideController()
        settle()

        val controls = viewer.player.findViewById<androidx.media3.ui.PlayerControlView>(
            androidx.media3.ui.R.id.exo_controller
        )
        controls.showTimeoutMs = 0
        controls.show()
        settle()
        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500))
        assertThat(viewer.player.isControllerFullyVisible).isFalse()
        assertThat(viewer.top.visibility).isEqualTo(View.INVISIBLE)
    }

    /** A finger on the title bar is using it, as one on the player's controls would be. */
    @Test
    fun aFingerOnTheTitleBarStartsTheCountdownAgain() {
        val viewer = video().get()
        viewer.player.controllerAutoShow = false
        viewer.player.showController()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))

        val toolbar = viewer.toolbar
        val x = toolbar.width / 2f
        val y = toolbar.top + toolbar.height / 2f
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val touch = MotionEvent.obtain(now, now, action, x, y, 0)
            viewer.top.dispatchTouchEvent(touch)
            touch.recycle()
        }

        // Past the first countdown's end, and within the second's
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertThat(viewer.top.visibility).isEqualTo(View.INVISIBLE)
    }

    /**
     * The menu from the title bar, or a box opened from it, takes the window's focus. The countdown
     * knew nothing of either, and the title bar faded out from under its own menu.
     */
    @Test
    fun everythingStaysUpWhileAMenuIsOpenOverTheVideo() {
        val viewer = video().get()
        viewer.player.controllerAutoShow = false
        viewer.player.showController()
        settle()

        viewer.onWindowFocusChanged(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4000))
        assertThat(viewer.player.isControllerFullyVisible).isTrue()
        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)

        // Closed, it counts down again from there
        viewer.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertThat(viewer.top.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun underAScreenReaderTheTitleBarStays() {
        val viewer = video().get()
        shadowOf(viewer.getSystemService(AccessibilityManager::class.java))
            .setTouchExplorationEnabled(true)

        viewer.player.hideController()
        settle()

        assertThat(viewer.top.visibility).isEqualTo(View.VISIBLE)
        assertThat(viewer.top.alpha).isEqualTo(1f)
    }

    // ---------------------------------------------------------------
    // Full screen
    // ---------------------------------------------------------------

    // The tone has no picture, so the size a real film's first frame reports is handed over here
    private val wide = VideoSize(1920, 1080)
    private val upright = VideoSize(1080, 1920)

    private val ViewerActivity.offersFullScreen get() = fullScreenButton.visibility == View.VISIBLE

    @Test
    fun aWideVideoIsOfferedFullScreenWhichTurnsTheScreenAndGivesItBack() {
        val viewer = video().get()
        // Nothing until the size is known
        assertThat(viewer.offersFullScreen).isFalse()
        viewer.playerScreen!!.videoChrome!!.sized(wide)
        assertThat(viewer.offersFullScreen).isTrue()

        viewer.fullScreenButton.performClick()
        assertThat(viewer.requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

        viewer.fullScreenButton.performClick()
        assertThat(viewer.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    /** Landscape would only shrink it, so there is no button that seems to do nothing. */
    @Test
    fun anUprightVideoIsNotOfferedFullScreen() {
        val viewer = video().get()
        viewer.playerScreen!!.videoChrome!!.sized(upright)
        assertThat(viewer.offersFullScreen).isFalse()

        // And one that turns upright while held gives the screen back
        viewer.playerScreen!!.videoChrome!!.sized(wide)
        viewer.fullScreenButton.performClick()
        viewer.playerScreen!!.videoChrome!!.sized(upright)
        assertThat(viewer.offersFullScreen).isFalse()
        assertThat(viewer.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    @Test
    fun aVideoInFullScreenStaysSoWhenTheViewerIsMadeAgain() {
        val controller = video()
        controller.get().playerScreen!!.videoChrome!!.sized(wide)
        controller.get().fullScreenButton.performClick()

        controller.recreate()

        assertThat(controller.get().requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        // And the button knows it once it is back, so the next press gives the screen back
        controller.get().playerScreen!!.videoChrome!!.sized(wide)
        controller.get().fullScreenButton.performClick()
        assertThat(controller.get().requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    @Test
    fun whetherAVideoShowsWiderThanTall() {
        assertThat(widerThanTall(wide)).isTrue()
        // A phone held upright
        assertThat(widerThanTall(upright)).isFalse()
        // Stored narrow in pixels drawn twice as wide, so wide on screen
        assertThat(widerThanTall(VideoSize(480, 640, 2f))).isTrue()
        assertThat(widerThanTall(VideoSize(640, 640))).isFalse()
        assertThat(widerThanTall(VideoSize.UNKNOWN)).isFalse()
    }

    // ---------------------------------------------------------------
    // A video that will not play
    // ---------------------------------------------------------------

    /** Text under a video's name, which the player cannot read and gives up on. */
    private fun unplayable(): ViewerActivity {
        val viewer = open(FixtureProvider.uriNamed("plain.txt", "broken.mp4")).get()
        // The player reads on a thread of its own and reports back on the main one
        repeat(100) {
            if (viewer.findViewById<FrameLayout>(R.id.container).children.any { it is WebView }) {
                return viewer
            }
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        error("the player never gave up on broken.mp4")
    }

    @Test
    fun aVideoThatWillNotPlayGetsTheViewerBackAsEveryOtherFileHasIt() {
        val card = card()
        val viewer = unplayable()

        assertThat(card.toolbar.parent).isSameInstanceAs(card.root)
        assertThat(viewer.toolbar.parent).isSameInstanceAs(viewer.root)
        assertThat(viewer.root.indexOfChild(viewer.toolbar)).isEqualTo(card.root.indexOfChild(card.toolbar))
        assertThat(viewer.root.indexOfChild(viewer.saveProgress))
            .isEqualTo(card.root.indexOfChild(card.saveProgress))
        assertThat(viewer.toolbar.surface()).isEqualTo(card.toolbar.surface())
        assertThat(viewer.toolbar.titleColour()).isEqualTo(card.toolbar.titleColour())
        assertThat(viewer.toolbar.elevation).isEqualTo(card.toolbar.elevation)
        assertThat(viewer.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        // And it keeps clear of the bars again
        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars(shown = true))
        assertThat(viewer.root.padding()).isEqualTo(listOf(0, 63, 0, 126))
    }

    /** Below Android 15 the window paints the bars itself, which over a video it must not. */
    @Test
    @Config(sdk = [34])
    @Suppress("DEPRECATION")
    fun belowAndroid15TheBarsAreSeeThroughOverAVideoAndPutBackAfter() {
        val card = card()
        assertThat(card.window.statusBarColor).isNotEqualTo(Color.TRANSPARENT)

        assertThat(video().get().window.statusBarColor).isEqualTo(Color.TRANSPARENT)
        assertThat(unplayable().window.statusBarColor).isEqualTo(card.window.statusBarColor)
    }
}
