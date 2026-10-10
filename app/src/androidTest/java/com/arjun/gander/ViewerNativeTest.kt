package com.arjun.gander

import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two surfaces that are not a WebView: the tiling photo view and the
 * player.
 *
 * Neither can be tested off-device at all. The photo view needs Android's
 * region decoder, and the player needs a real codec.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ViewerNativeTest {

    @get:Rule
    val retry = RetryRule()

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    private fun open(fixture: String) =
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent(fixture))

    private fun children(scenario: ActivityScenario<ViewerActivity>): List<View> {
        var found: List<View> = emptyList()
        scenario.onActivity { activity ->
            val container = activity.findViewById<FrameLayout>(R.id.container)
            found = (0 until container.childCount).map { container.getChildAt(it) }
        }
        return found
    }

    /**
     * A photo goes to the tiling view rather than a WebView, because that is
     * what gives deep zoom on an image far larger than memory.
     */
    @Test
    fun aPhotoOpensInTheTilingView() {
        open("exif-1.jpg").use { scenario ->
            Thread.sleep(1500)
            val views = children(scenario)
            assertThat(views.filterIsInstance<SubsamplingScaleImageView>()).hasSize(1)
        }
    }

    /**
     * The rotation is read from the file's EXIF, because a content URI carries
     * no orientation of its own and the photo would otherwise open sideways.
     */
    @Test
    fun aRotatedPhotoIsTurnedTheRightWayUp() {
        open("exif-6.jpg").use { scenario ->
            Thread.sleep(1500)
            var orientation = -1
            scenario.onActivity { activity ->
                val container = activity.findViewById<FrameLayout>(R.id.container)
                val image = (0 until container.childCount)
                    .map { container.getChildAt(it) }
                    .filterIsInstance<SubsamplingScaleImageView>()
                    .firstOrNull()
                orientation = image?.orientation ?: -1
            }
            assertThat(orientation).isEqualTo(90)
        }
    }

    /**
     * Audio gets a screen of its own rather than a black video surface: the
     * cover art slot in view_audio_player.xml is what tells the two apart.
     */
    @Test
    fun anAudioFileOpensTheAudioScreenRatherThanAVideoSurface() {
        open("tone.wav").use { scenario ->
            Thread.sleep(2000)
            var hasCover = false
            scenario.onActivity { activity ->
                hasCover = activity.findViewById<View?>(R.id.audioCover) != null
            }
            assertThat(hasCover).isTrue()
        }
    }

    /**
     * What drew [fixture], once something had, within ten seconds: "tiling" and the size the
     * tiling view decoded, or "web" and the size the page decoded its picture at, when
     * Android's region decoder gave up and the viewer fell back to the page GIFs get.
     */
    private fun drawnBy(fixture: String): String = open(fixture).use { scenario ->
        val deadline = SystemClock.uptimeMillis() + 10_000
        var drawn = ""
        while (drawn.isEmpty() && SystemClock.uptimeMillis() < deadline) {
            val views = children(scenario)
            val tiles = views.filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
            if (tiles != null) {
                scenario.onActivity {
                    if (tiles.isReady) drawn = "tiling ${tiles.sWidth}x${tiles.sHeight}"
                }
            } else if (views.any { it is WebView }) {
                drawn = WebViewProbe.text(scenario, PICTURE_SIZE)
            }
            if (drawn.isEmpty()) Thread.sleep(100)
        }
        drawn
    }

    /** A lossy WebP, as websites serve photos. */
    @Test
    fun aWebpPhotoIsDecodedByTheTilingView() {
        assertThat(drawnBy("photo.webp")).isEqualTo("tiling 160x96")
    }

    /** What an iPhone takes photos in unless told otherwise. */
    @Test
    fun aHeicPhotoIsDecodedByTheTilingView() {
        assertThat(drawnBy("photo.heic")).isEqualTo("tiling 160x96")
    }

    /**
     * Android's region decoder, which the tiling view needs, takes JPEG, PNG, WebP and HEIF
     * but not BMP, so a BMP falls back to the page and is shown whole, without deep zoom.
     */
    @Test
    fun aBmpPhotoIsShownByThePageInstead() {
        assertThat(drawnBy("photo.bmp")).isEqualTo("web 160x96")
    }

    /**
     * A video plays: the player knows the picture's size and has rendered frames of it to
     * the screen, which no test off the device can show, since only a device has decoders.
     * Each file is made by the device's own encoder: H.264 in an MP4, as a phone records,
     * and VP8 in WebM, as the web serves.
     */
    @Test
    fun anMp4VideoPlaysItsPictures() =
        assertPlays("clip.mp4", MediaFormat.MIMETYPE_VIDEO_AVC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    @Test
    fun aWebmVideoPlaysItsPictures() =
        assertPlays("clip.webm", MediaFormat.MIMETYPE_VIDEO_VP8, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun assertPlays(name: String, mime: String, container: Int) {
        MadeVideo.write(DeviceFixtures.made(name), mime, container)
        open(name).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 15_000
            var size = ""
            var rendered = 0
            var audioScreen = false
            while (rendered < 5 && SystemClock.uptimeMillis() < deadline) {
                val view = children(scenario).filterIsInstance<PlayerView>().firstOrNull()
                scenario.onActivity { activity ->
                    audioScreen = activity.findViewById<View?>(R.id.audioCover) != null
                    val player = view?.player as? ExoPlayer
                    size = when {
                        player == null -> "no player"
                        player.playerError != null -> "failed: ${player.playerError!!.errorCodeName}"
                        else -> "${player.videoSize.width}x${player.videoSize.height}"
                    }
                    // The most seen, since a renderer's counts go when it is disabled
                    rendered = maxOf(rendered, player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0)
                }
                if (rendered < 5) Thread.sleep(100)
            }
            assertThat(audioScreen).isFalse()
            assertThat(size).isEqualTo("${MadeVideo.WIDTH}x${MadeVideo.HEIGHT}")
            assertThat(rendered).isAtLeast(5)
        }
    }

    private companion object {
        /** The picture's decoded size in the page that GIFs get, or nothing until it has one. */
        const val PICTURE_SIZE = """(function () {
            var i = document.getElementById('img');
            return i && i.complete && i.naturalWidth ? 'web ' + i.naturalWidth + 'x' + i.naturalHeight : '';
        })()"""
    }
}
