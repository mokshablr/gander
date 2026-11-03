package com.arjun.gander

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The title bar and the phone's bars over a photo and over a track, which a tap hides and the next
 * brings back. Issue #71. Over a video they come and go with its controls: see VideoChromeTest.
 */
@RunWith(AndroidJUnit4::class)
class OverChromeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
    }

    /** The viewer for [intent], with what it reads off the main thread read inline, so the picture is up. */
    private fun start(intent: Intent): ActivityController<ViewerActivity> {
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent)
        controller.get().documentLoader = Executor { it.run() }
        return controller.setup().also { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun open(uri: Uri): ViewerActivity = start(
        Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
    ).get()

    private fun photo() = open(FixtureProvider.uriFor("exif-1.jpg"))

    private fun track() = open(FixtureProvider.uriFor("tone.wav"))

    /** A folder added in Gander holding two photos, the first of them opened. */
    private fun folderPhoto(): ViewerActivity {
        FakeDocumentsProvider.install()
            .folder(
                "pics", "pics",
                ChildDoc("pics/a.jpg", "a.jpg", "image/jpeg", 30, 1_000),
                ChildDoc("pics/b.png", "b.png", "image/png", 10, 2_000),
            )
            .contents("pics/a.jpg", Fixtures.bytes("exif-1.jpg"))
            .contents("pics/b.png", Fixtures.bytes("tiny.png"))
        val tree = FakeDocumentsProvider.treeUri("pics")
        return start(
            Intent()
                .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
                .setData(DocumentsContract.buildDocumentUriUsingTree(tree, "pics/a.jpg"))
        ).get()
    }

    private val ViewerActivity.root get() = findViewById<ViewGroup>(R.id.root)
    private val ViewerActivity.toolbar get() = findViewById<MaterialToolbar>(R.id.toolbar)
    private val ViewerActivity.container get() = findViewById<FrameLayout>(R.id.container)
    private val ViewerActivity.stage get() = container.parent as FrameLayout
    private val ViewerActivity.readout get() = findViewById<TextView>(R.id.pageIndicator)

    /** What holds the title bar over a picture. */
    private val ViewerActivity.top get() = toolbar.parent as View

    /** The tiling view drawing the photo on screen. */
    private val ViewerActivity.still: StillPhotoView
        get() = photoPager?.stillShown ?: container.children.filterIsInstance<StillPhotoView>().single()

    private val ViewerActivity.player
        get() = container.children.filterIsInstance<PlayerView>().single()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private val statusBar = Insets.of(0, 63, 0, 0)
    private val navigationBar = Insets.of(0, 0, 0, 126)

    /** The phone's bars, shown, with three buttons at the bottom: they take taps, as the status bar does. */
    private fun bars(): WindowInsetsCompat {
        val tappable = Insets.add(statusBar, navigationBar)
        return WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), statusBar)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), navigationBar)
            .setInsets(WindowInsetsCompat.Type.tappableElement(), tappable)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), statusBar)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigationBar)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.tappableElement(), tappable)
            .setVisible(WindowInsetsCompat.Type.systemBars(), true)
            .build()
    }

    private fun View.padding() = listOf(paddingLeft, paddingTop, paddingRight, paddingBottom)

    /** The app's colours as a phone set to dark has them. */
    private val night: Context by lazy {
        val conf = Configuration(context.resources.configuration)
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_YES
        context.createConfigurationContext(conf)
    }

    private fun nightText(): Int {
        val values = ContextThemeWrapper(night, R.style.Theme_Gander)
            .obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        try {
            return values.getColorStateList(0)!!.defaultColor
        } finally {
            values.recycle()
        }
    }

    private fun MaterialToolbar.titleColour(): Int =
        children.filterIsInstance<TextView>().first { it.text == title }.currentTextColor

    private fun ViewerActivity.barsAreUp(): Boolean {
        val up = top.visibility == View.VISIBLE && top.alpha == 1f
        val gone = top.visibility == View.INVISIBLE && top.alpha == 0f
        check(up || gone) { "the title bar stopped part way" }
        return up
    }

    // ---------------------------------------------------------------
    // A photo
    // ---------------------------------------------------------------

    /** A photo sits on black whatever the phone is set to, and on a phone set to light the bar went paper over it. */
    @Test
    fun aPhotosTitleBarFloatsOverItInTheNightColours() {
        val viewer = photo()

        assertThat(viewer.top.parent).isSameInstanceAs(viewer.stage)
        assertThat((viewer.top.background as MaterialShapeDrawable).fillColor?.defaultColor).isEqualTo(Color.BLACK)
        assertThat(viewer.toolbar.titleColour()).isEqualTo(nightText())
        assertThat(WindowCompat.getInsetsController(viewer.window, viewer.window.decorView)
            .isAppearanceLightStatusBars).isFalse()

        // The photo fills the screen, and the bar's strip reaches up behind the clock
        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars())
        assertThat(viewer.root.padding()).isEqualTo(listOf(0, 0, 0, 0))
        assertThat(viewer.top.padding()).isEqualTo(listOf(0, 63, 0, 0))
    }

    @Test
    fun aTapOnAPhotoHidesTheBarsAndTheNextBringsThemBack() {
        val viewer = photo()
        assertThat(viewer.barsAreUp()).isTrue()

        viewer.still.performClick()
        settle()
        assertThat(viewer.barsAreUp()).isFalse()

        viewer.still.performClick()
        settle()
        assertThat(viewer.barsAreUp()).isTrue()
    }

    @Test
    fun theBarsStayGoneAsAFoldersPhotosAreSwipedThrough() {
        val viewer = folderPhoto()
        viewer.still.performClick()
        settle()

        viewer.photoPager!!.view.setCurrentItem(1, false)
        settle()
        assertThat(viewer.toolbar.title.toString()).isEqualTo("b.png")
        assertThat(viewer.barsAreUp()).isFalse()

        // A tap on the photo swiped to brings them back
        viewer.still.performClick()
        settle()
        assertThat(viewer.barsAreUp()).isTrue()
    }

    /** The photo runs under the phone's bars now, and the count would sit under the buttons. */
    @Test
    fun aFoldersPhotoCountKeepsClearOfTheBarsAtTheBottom() {
        // As the layout has it, read on the card for a file Gander can't open, where nothing floats
        val card = open(FixtureProvider.uriNamed("opaque.xyz", "slides.ppt"))
        val lift = (card.readout.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val viewer = folderPhoto()

        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars())

        assertThat((viewer.readout.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
            .isEqualTo(lift + navigationBar.bottom)
    }

    private fun tap(view: View, at: Long) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val touch = MotionEvent.obtain(at, at, action, view.width / 2f, view.height / 2f, 0)
            view.dispatchTouchEvent(touch)
            touch.recycle()
        }
    }

    /** A picture that moves is drawn by its page, which takes a tap the same way, once no second tap follows. */
    @Test
    fun aTapOnAPictureThatMovesHidesTheBarsButADoubleTapDoesNot() {
        val viewer = open(FixtureProvider.uriFor("moving.webp"))
        val web = viewer.container.children.filterIsInstance<WebView>().single()

        tap(web, SystemClock.uptimeMillis())
        tap(web, SystemClock.uptimeMillis() + 100)
        settle()
        assertThat(viewer.barsAreUp()).isTrue()

        tap(web, SystemClock.uptimeMillis())
        settle()
        assertThat(viewer.barsAreUp()).isFalse()
    }

    @Test
    fun underAScreenReaderATapLeavesTheBarsUp() {
        val viewer = photo()
        shadowOf(viewer.getSystemService(AccessibilityManager::class.java))
            .setTouchExplorationEnabled(true)

        viewer.still.performClick()
        settle()

        assertThat(viewer.barsAreUp()).isTrue()
    }

    /** Under a screen reader the bars stay, so a photo isn't offered as something to click. */
    @Test
    fun aScreenReaderIsNotOfferedTheTapThatHidesTheBars() {
        val info = photo().still.createAccessibilityNodeInfo()
        assertThat(info.isClickable).isFalse()
        assertThat(info.actionList).doesNotContain(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
    }

    // ---------------------------------------------------------------
    // A track
    // ---------------------------------------------------------------

    @Test
    fun aTracksTitleBarFloatsOverItOnItsOwnDark() {
        val viewer = track()

        assertThat(viewer.top.parent).isSameInstanceAs(viewer.stage)
        assertThat((viewer.top.background as ColorDrawable).color)
            .isEqualTo(ContextCompat.getColor(context, R.color.audio_backdrop))
        assertThat(viewer.toolbar.titleColour()).isEqualTo(nightText())

        // Its controls keep clear of what takes a tap, as a video's do
        ViewCompat.dispatchApplyWindowInsets(viewer.root, bars())
        assertThat(viewer.root.padding()).isEqualTo(listOf(0, 0, 0, 0))
        assertThat(viewer.player.findViewById<View>(androidx.media3.ui.R.id.exo_controller).padding())
            .isEqualTo(listOf(0, 63, 0, 126))
    }

    /** Nothing else is on a track's screen, so its controls stay when the bars go. */
    @Test
    fun aTapOnATracksScreenHidesTheBarsAndLeavesItsControls() {
        val viewer = track()

        viewer.player.performClick()
        settle()
        assertThat(viewer.barsAreUp()).isFalse()
        assertThat(viewer.player.isControllerFullyVisible).isTrue()

        viewer.player.performClick()
        settle()
        assertThat(viewer.barsAreUp()).isTrue()
    }

    /** Text under a track's name, which the player cannot read and gives up on. */
    @Test
    fun aTrackThatWillNotPlayGetsItsTitleBarBackAboveTheCard() {
        val viewer = open(FixtureProvider.uriNamed("plain.txt", "broken.mp3"))
        // The player reads on a thread of its own and reports back on the main one
        repeat(100) {
            if (viewer.container.children.any { it is WebView }) {
                assertThat(viewer.toolbar.parent).isSameInstanceAs(viewer.root)
                assertThat(viewer.playerScreen!!.trackChrome).isNull()
                return
            }
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        error("the player never gave up on broken.mp3")
    }
}
