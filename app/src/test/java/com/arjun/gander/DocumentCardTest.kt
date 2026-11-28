package com.arjun.gander

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.webkit.WebMessage
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.content.IntentCompat
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The viewer's side of the cards a page puts up in place of a document: the one saying it could not
 * be shown, which takes the document's actions away and offers the file to another app, and the
 * one for a format Gander doesn't know, which has nothing to search until View as text.
 *
 * The WebView is Robolectric's, so no page runs here. The page's half is in
 * tests/viewer/test_status_card.py and test_unsupported.py.
 */
@RunWith(AndroidJUnit4::class)
class DocumentCardTest {

    @get:Rule val closing = ClosesActivities()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
    }

    /** The viewer for [fixture], from another app, its page loaded and handed the channel unless not [finished]. */
    private fun open(fixture: String, finished: Boolean = true): ActivityController<ViewerActivity> {
        val uri = FixtureProvider.uriFor(fixture)
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
        val controller = closing.build(ViewerActivity::class.java, intent)
        controller.get().documentLoader = Executor { it.run() }
        controller.setup()
        settle()
        val web = controller.get().web
        if (finished) web.webViewClient.onPageFinished(web, shadowOf(web).lastLoadedUrl)
        return controller
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private val ViewerActivity.web
        get() = findViewById<FrameLayout>(R.id.container).children.filterIsInstance<WebView>().single()
    private val ViewerActivity.menu get() = findViewById<MaterialToolbar>(R.id.toolbar).menu

    private fun ViewerActivity.pageSays(vararg messages: String) {
        val mine = shadowOf(web).createdPorts.single()[0]
        messages.forEach { mine.webMessageCallback.onMessage(mine, WebMessage(it)) }
        settle()
    }

    private fun ViewerActivity.shows(id: Int) = menu.findItem(id).isVisible

    @Test
    fun aDocumentThatFailedLosesWhatWouldActOnIt() {
        val viewer = open("not-a-pdf.pdf").get()
        viewer.pageSays("page 1 3")
        assertThat(viewer.shows(R.id.action_search)).isTrue()
        assertThat(viewer.shows(R.id.action_night_mode)).isTrue()
        assertThat(viewer.shows(R.id.action_go_to_page)).isTrue()

        viewer.pageSays("failed")

        for (gone in listOf(
            R.id.action_search, R.id.action_night_mode, R.id.action_print,
            R.id.action_go_to_page, R.id.action_contents, R.id.action_text_size,
        )) {
            assertThat(viewer.shows(gone)).isFalse()
        }
        // The file is still there to hand on
        assertThat(viewer.shows(R.id.action_share)).isTrue()
        assertThat(viewer.shows(R.id.action_save_copy)).isTrue()
    }

    @Test
    fun theCardsButtonOffersTheFileToTheOtherAppsThatOpenIt() {
        val viewer = open("not-a-pdf.pdf").get()
        viewer.pageSays("failed", "elsewhere")

        val chooser = shadowOf(viewer).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val view = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(view.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(view.data).isEqualTo(FixtureProvider.uriFor("not-a-pdf.pdf"))
        assertThat(view.type).isEqualTo("application/pdf")
        assertThat(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        @Suppress("DEPRECATION")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.toList()
        assertThat(excluded).contains(ComponentName(context, ViewerActivity::class.java))
    }

    /** Only the card that has the button may ask, as only the card with the simple view's may ask for that. */
    @Test
    fun aDocumentStillShownCannotBeSentElsewhere() {
        val viewer = open("not-a-pdf.pdf").get()
        viewer.pageSays("elsewhere")
        assertThat(shadowOf(viewer).nextStartedActivity).isNull()
    }

    @Test
    fun theUnknownFormatCardHasNothingToSearchUntilViewAsText() {
        // Not even while the card's page is still loading
        assertThat(open("opaque.xyz", finished = false).get().shows(R.id.action_search)).isFalse()

        val viewer = open("opaque.xyz").get()
        assertThat(shadowOf(viewer.web).lastLoadedUrl).contains("/unsupported.html")
        assertThat(viewer.shows(R.id.action_search)).isFalse()

        val web = viewer.web
        web.webViewClient.onPageFinished(web, "https://$ASSET_HOST/assets/viewer/text.html?name=opaque.xyz")
        assertThat(viewer.shows(R.id.action_search)).isTrue()

        // Only Gander's own text page; a page from anywhere else is not the file turned into text
        web.webViewClient.onPageFinished(web, Uri.parse("https://example.com/assets/viewer/text.html").toString())
        assertThat(viewer.shows(R.id.action_search)).isFalse()
    }
}
