package com.arjun.gander

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A double tap zooms a document's pages or a picture to twice its fitted width, and the next
 * puts it back.
 *
 * In the WebView it has a half in the page, which turns Chromium's own off, and a half in the
 * app, which zooms the WebView. Only a device has both, and a WebView to zoom. A photo's is the
 * tiling view's own, told how far to go.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DoubleTapDeviceTest {

    @get:Rule
    val retry = RetryRule()

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    private fun open(fixture: String): ActivityScenario<ViewerActivity> =
        ActivityScenario.launch(DeviceFixtures.viewIntent(fixture))

    @Test
    fun aPdfPageZoomsInAboutThePlaceTappedAndBack() =
        zoomsInAboutThePlaceTappedAndBack("six-pages.pdf", PDF_DRAWN)

    @Test
    fun aWordPageZoomsInAboutThePlaceTappedAndBack() =
        zoomsInAboutThePlaceTappedAndBack("report.docx", STATUS_DONE)

    @Test
    fun anOpenDocumentPageZoomsInAboutThePlaceTappedAndBack() =
        zoomsInAboutThePlaceTappedAndBack("letter.odt", STATUS_DONE)

    @Test
    fun aSlideZoomsInAboutThePlaceTappedAndBack() =
        zoomsInAboutThePlaceTappedAndBack("deck.pptx", STATUS_DONE)

    @Test
    fun aPictureOnThePageZoomsInAboutThePlaceTappedAndBack() =
        zoomsInAboutThePlaceTappedAndBack("icon.svg", IMAGE_SHOWN)

    /**
     * A sheet's page zooms itself, so its column letters and frozen panes stay on screen, and says
     * how far: the WebView itself never zooms. What only a device shows is that a real double tap
     * reaches the page through the app's touch handling, and that the WebView does not zoom too.
     */
    @Test
    fun aSheetZoomsItselfInAboutThePlaceTappedAndBack() {
        open("budget.xlsx").use { scenario ->
            WebViewProbe.await(scenario, STATUS_DONE, "budget.xlsx to be drawn")
            val opened = WebViewProbe.text(scenario, "String(vwSheetZoom())").toDouble()
            val before = sheetPlace(scenario)

            onView(isAssignableFrom(WebView::class.java)).perform(doubleTapOffCentre)
            WebViewProbe.await(
                scenario, "Math.abs(vwSheetZoom() / $opened - $DOUBLE_TAP_ZOOM) < 0.02", "the sheet to zoom in", 10_000
            )
            val after = sheetPlace(scenario)
            assertThat(after[0]).isWithin(4.0).of(before[0])
            assertThat(after[1]).isWithin(4.0).of(before[1])
            assertThat(WebViewProbe.eval(scenario, ZOOM)).isEqualTo("100")

            onView(isAssignableFrom(WebView::class.java)).perform(doubleTapOffCentre)
            WebViewProbe.await(scenario, "Math.abs(vwSheetZoom() - $opened) < 0.02", "the sheet to come back", 10_000)
        }
    }

    /** Where in the sheet, in its own px, the place a quarter across the screen and a third down it is */
    private fun sheetPlace(scenario: ActivityScenario<ViewerActivity>): List<Double> =
        WebViewProbe.text(scenario, "vwSheetPlace(innerWidth / 4, innerHeight / 3).join(' ')")
            .split(" ").map { it.toDouble() }

    @Test
    fun aPhotoZoomsToTwiceItsFitAndBack() {
        openCameraPhoto().use { scenario -> photoZoomsAndComesBack(scenario) }
    }

    /** The viewer is turned without being made again, so the fit a double tap doubles moves under it. */
    @Test
    fun aPhotoTurnedOnItsSideZoomsToTwiceItsNewFit() {
        openCameraPhoto().use { scenario ->
            awaitPhotoAt(scenario, 1f, "the photo to fit")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var wide = false
            while (!wide && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val container = activity.findViewById<FrameLayout>(R.id.container)
                    wide = container.width > container.height
                }
                if (!wide) Thread.sleep(100)
            }
            assertThat(wide).isTrue()
            // The view keeps a photo's scale through a turn rather than fitting it again, so this
            // starts a little way in, and gives it a moment to draw at the new size
            Thread.sleep(500)
            onView(isAssignableFrom(SubsamplingScaleImageView::class.java)).perform(doubleTapOffCentre)
            awaitPhotoAt(scenario, DOUBLE_TAP_ZOOM, "the photo to zoom in")
            onView(isAssignableFrom(SubsamplingScaleImageView::class.java)).perform(doubleTapOffCentre)
            awaitPhotoAt(scenario, 1f, "the photo to come back")
        }
    }

    /**
     * A photo of a phone camera's proportions. The fixtures' own are thumbnails, which the
     * view's limit of 12 stops short of twice.
     */
    private fun openCameraPhoto(): ActivityScenario<ViewerActivity> {
        DeviceFixtures.made("camera.jpg").outputStream().use { out ->
            Bitmap.createBitmap(2000, 1500, Bitmap.Config.RGB_565)
                .apply { eraseColor(Color.GRAY) }
                .compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        return open("camera.jpg")
    }

    private fun photoZoomsAndComesBack(scenario: ActivityScenario<ViewerActivity>) {
        awaitPhotoAt(scenario, 1f, "the photo to fit")
        onView(isAssignableFrom(SubsamplingScaleImageView::class.java)).perform(doubleTapOffCentre)
        awaitPhotoAt(scenario, DOUBLE_TAP_ZOOM, "the photo to zoom in")
        onView(isAssignableFrom(SubsamplingScaleImageView::class.java)).perform(doubleTapOffCentre)
        awaitPhotoAt(scenario, 1f, "the photo to come back")
    }

    private fun zoomsInAboutThePlaceTappedAndBack(fixture: String, drawn: String) {
        open(fixture).use { scenario ->
            WebViewProbe.await(scenario, drawn, "$fixture to be drawn")
            assertThat(WebViewProbe.eval(scenario, ZOOM)).isEqualTo("100")
            val before = place(scenario)

            onView(isAssignableFrom(WebView::class.java)).perform(doubleTapOffCentre)
            val wanted = (DOUBLE_TAP_ZOOM * 100).toInt()
            WebViewProbe.await(scenario, "Math.abs($ZOOM - $wanted) <= 2", "$fixture to zoom in", 10_000)
            val after = place(scenario)
            assertThat(after[0]).isWithin(4).of(before[0])
            assertThat(after[1]).isWithin(4).of(before[1])

            onView(isAssignableFrom(WebView::class.java)).perform(doubleTapOffCentre)
            WebViewProbe.await(scenario, "$ZOOM === 100", "$fixture to come back", 10_000)
        }
    }

    private fun place(scenario: ActivityScenario<ViewerActivity>): List<Int> =
        WebViewProbe.text(scenario, UNDER_THE_FINGER).split(" ").map { it.toInt() }

    /** Waits for the photo to be drawn at [times] its fitted size, read on the main thread. */
    private fun awaitPhotoAt(scenario: ActivityScenario<ViewerActivity>, times: Float, what: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var last: Float? = null
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val container = activity.findViewById<FrameLayout>(R.id.container)
                val photo = (0 until container.childCount).map { container.getChildAt(it) }
                    .filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
                last = photo?.takeIf { it.isReady }?.let { it.scale / it.minScale }
            }
            if (last?.let { abs(it - times) < 0.02f } == true) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $what. Last zoom: $last")
    }

    private val doubleTapOffCentre = GeneralClickAction(
        Tap.DOUBLE,
        { view ->
            val at = IntArray(2).also { view.getLocationOnScreen(it) }
            floatArrayOf(at[0] + view.width / 4f, at[1] + view.height / 3f)
        },
        Press.FINGER,
        InputDevice.SOURCE_TOUCHSCREEN,
        MotionEvent.BUTTON_PRIMARY,
    )

    private companion object {
        /** A drawn canvas is the page width across; an untouched one is 300. */
        const val PDF_DRAWN =
            "[].slice.call(document.querySelectorAll('#pages .pg canvas'))" +
                ".filter(function (c) { return c.width > 300; }).length >= 1"

        /** A Word, OpenDocument or slide page takes its status card down once it has drawn and fitted. */
        const val STATUS_DONE = "document.getElementById('vw-status').style.display === 'none'"

        const val IMAGE_SHOWN =
            "document.getElementById('img').complete && document.getElementById('img').naturalWidth > 0"

        /** How many hundredths of its fitted width the page is drawn at. */
        const val ZOOM = "Math.round(100 * document.documentElement.clientWidth / visualViewport.width)"

        /** Where in the document the place a quarter across the screen and a third down it is, in CSS px. */
        const val UNDER_THE_FINGER =
            "Math.round(visualViewport.pageLeft + visualViewport.width / 4) + ' ' +" +
                " Math.round(visualViewport.pageTop + visualViewport.height / 3)"
    }
}
