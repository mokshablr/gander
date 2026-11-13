package com.arjun.gander

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowWebView

/**
 * The viewer, driven by the intents that really reach it.
 *
 * The WebView is Robolectric's shadow, so nothing here renders a document.
 * What it can answer is everything decided before the renderer runs: which
 * surface a file gets, what URL the page is loaded from, and what the request
 * interceptor serves. The rendering itself is tested in tests/viewer and on a
 * device.
 */
@RunWith(AndroidJUnit4::class)
class ViewerActivityTest {

    @get:Rule val closing = ClosesActivities()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
        RendererSlots.resetForTests()
    }

    @After
    fun tearDown() {
        ArchivePasswords.forgetAll()
    }

    // ---------------------------------------------------------------
    // Launching
    // ---------------------------------------------------------------

    private fun view(uri: Uri, type: String? = null): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, type ?: context.contentResolver.getType(uri))
        return start(intent)
    }

    /**
     * The viewer for [intent], with what it asks of the provider before the page loads asked
     * inline, so the page has loaded by the time this returns.
     */
    private fun start(intent: Intent): ActivityController<ViewerActivity> {
        val controller = closing.build(ViewerActivity::class.java, intent)
        controller.get().documentLoader = Executor { it.run() }
        return controller.setup().also { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun open(fixture: String): ActivityController<ViewerActivity> =
        view(FixtureProvider.uriFor(fixture))

    /** The same, the way Gander's own screens open it: by the viewer's internal name. */
    private fun viewFromGander(uri: Uri, type: String? = null): ActivityController<ViewerActivity> {
        val intent = Intent()
            .setClassName(context, ViewerActivity.INTERNAL_VIEWER)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, type ?: context.contentResolver.getType(uri))
        return closing.build(ViewerActivity::class.java, intent).setup()
    }

    private fun ActivityController<ViewerActivity>.container(): FrameLayout =
        get().findViewById(R.id.container)

    private fun ActivityController<ViewerActivity>.webView(): WebView? =
        container().children().filterIsInstance<WebView>().firstOrNull()

    private fun FrameLayout.children(): List<android.view.View> =
        (0 until childCount).map { getChildAt(it) }

    private fun ActivityController<ViewerActivity>.loadedUrl(): String =
        shadowOf(webView()!!).lastLoadedUrl

    /** The viewer page a file was given, or that it was given none and went to a native view. */
    private fun ActivityController<ViewerActivity>.pageName(): String {
        val web = webView() ?: return "a native view"
        return shadowOf(web).lastLoadedUrl.substringAfter("viewer/").substringBefore('?')
    }

    // ---------------------------------------------------------------
    // Which surface a file gets
    // ---------------------------------------------------------------

    @Test
    fun aPdfIsLoadedIntoThePdfPage() {
        val url = open("six-pages.pdf").loadedUrl()
        assertThat(url).startsWith("https://appassets.androidplatform.net/assets/viewer/pdf.html")
        assertThat(url).contains("ext=pdf")
    }

    @Test
    fun eachWebFormatGetsItsOwnPage() {
        mapOf(
            "report.docx" to "docx.html",
            "budget.xlsx" to "xlsx.html",
            "budget.csv" to "xlsx.html",
            "deck.pptx" to "pptx.html",
            "notes.md" to "md.html",
            "plain.txt" to "text.html",
            "icon.svg" to "imgweb.html",
            "anim.gif" to "imgweb.html",
            "bracket.stl" to "model.html",
            "bracket-ascii.stl" to "model.html",
            "unknown.xyz" to "text.html",
            "opaque.xyz" to "unsupported.html",
        ).forEach { (fixture, page) ->
            val url = open(fixture).loadedUrl()
            assertThat("$fixture -> ${url.substringAfter("viewer/").substringBefore('?')}")
                .isEqualTo("$fixture -> $page")
        }
    }

    /**
     * Opened with the type Android gives each extension, as a file manager opens them. For
     * the playlist that type is audio, and it still gets the text viewer rather than the
     * player.
     */
    @Test
    fun theOfficeRelativesAndTextUnderOtherNamesGetTheirFormatsPage() {
        mapOf(
            "report.docm" to "docx.html",
            "report.dotx" to "docx.html",
            "budget.xltx" to "xlsx.html",
            "deck.ppsx" to "pptx.html",
            "deck.pptm" to "pptx.html",
            "deck.potx" to "pptx.html",
            "captions.srt" to "text.html",
            "captions.vtt" to "text.html",
            "playlist.m3u" to "text.html",
            "release.nfo" to "text.html",
            "pump.v" to "text.html",
        ).forEach { (fixture, page) ->
            assertThat("$fixture -> ${open(fixture).pageName()}").isEqualTo("$fixture -> $page")
        }
    }

    /**
     * A playlist whose name has lost its extension still arrives labelled audio, and is shown
     * as text rather than handed to a player that could follow none of its entries.
     */
    @Test
    fun aPlaylistWithNoExtensionIsShownAsText() {
        val uri = FixtureProvider.uriNamed("playlist.m3u", "playlist")
        assertThat(context.contentResolver.getType(uri)).isEqualTo("audio/x-mpegurl")
        assertThat(view(uri).pageName()).isEqualTo("text.html")
    }

    /**
     * An STL whose name has lost its extension arrives with the type Android gives a .stl,
     * which is a Windows certificate trust list's, and is drawn as a model all the same.
     */
    @Test
    fun aModelWithNoExtensionIsDrawnByTheTypeAndroidGivesIt() {
        val uri = FixtureProvider.uriNamed("bracket.stl", "bracket")
        assertThat(context.contentResolver.getType(uri)).isEqualTo("application/vnd.ms-pki.stl")
        assertThat(view(uri).pageName()).isEqualTo("model.html")
    }

    /**
     * A model is drawn on a canvas, and the only words on its page are its size, so there is
     * nothing to search for and no search button. A text file is the control: the same
     * button on the same toolbar, offered.
     */
    @Test
    fun aModelOffersNoSearch() {
        fun search(fixture: String) = open(fixture).get()
            .findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(R.id.action_search)
        assertThat(search("plain.txt").isVisible).isTrue()
        assertThat(search("bracket.stl").isVisible).isFalse()
    }

    /**
     * A Word document's night mode, page counter and Go to page go over the channel, so its page
     * gets one below Chromium 105 too, where it is too old to search itself. A Markdown file
     * there needs none, since Chromium's own find searches it.
     */
    @Test
    fun aWordDocumentGetsTheChannelOnAWebViewTooOldToSearchIt() {
        ShadowWebView.setCurrentWebViewPackage(PackageInfo().apply {
            packageName = "com.google.android.webview"
            versionName = "104.0.5112.97"
        })
        fun loaded(fixture: String) = open(fixture).also {
            val web = it.webView()!!
            web.webViewClient.onPageFinished(web, it.loadedUrl())
        }
        assertThat(shadowOf(loaded("notes.md").webView()!!).createdPorts).isEmpty()

        val word = loaded("report.docx")
        val channels = shadowOf(word.webView()!!).createdPorts
        assertThat(channels).hasSize(1)
        val menu = word.get().findViewById<MaterialToolbar>(R.id.toolbar).menu
        assertThat(menu.findItem(R.id.action_night_mode).isVisible).isTrue()
        menu.performIdentifierAction(R.id.action_night_mode, 0)
        // After the title bar's height, which the channel carries as it opens
        val bar = PortCommand.barHeight(word.get().documentChrome!!.heightDp())
        assertThat(channels[0][0].outgoingMessages).containsExactly(bar, "i1").inOrder()
    }

    // ---------------------------------------------------------------
    // A PDF where pdf.js can't run
    // ---------------------------------------------------------------

    /** The WebView a Huawei phone without Google's services is held to. Its version won't read. */
    private fun lockedWebView() = ShadowWebView.setCurrentWebViewPackage(PackageInfo().apply {
        packageName = "com.huawei.webview"
        versionName = "14.0.0.331"
    })

    /** An Android System WebView below pdf.js's floor, which Play could update past it. */
    private fun oldWebView() = ShadowWebView.setCurrentWebViewPackage(PackageInfo().apply {
        packageName = "com.google.android.webview"
        versionName = "114.0.5735.196"
    })

    /** [body], with the renderer's check of a descriptor answering [whole], which Robolectric can't. */
    private fun <T> handingOver(whole: Boolean, body: () -> T): T {
        mayHandOver = { whole }
        try {
            return body()
        } finally {
            mayHandOver = ::readOnlyFile
        }
    }

    private fun ActivityController<ViewerActivity>.toolbarItem(id: Int) =
        get().findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(id)

    /**
     * Nothing the reader does will raise a locked WebView, so a PDF goes straight to the simple
     * view. Its pages are pictures, with no words to search, and it prints as the file itself.
     */
    @Test
    fun aPdfOnALockedWebViewOpensInTheSimpleView() {
        lockedWebView()
        renderersStandIn()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_PRINTING, true)
        val viewer = handingOver(true) { open("six-pages.pdf") }
        assertThat(viewer.pageName()).isEqualTo("pdfsimple.html")
        assertThat(viewer.toolbarItem(R.id.action_search).isVisible).isFalse()
        assertThat(viewer.toolbarItem(R.id.action_print).isVisible).isTrue()
    }

    /** It opens at the page the PDF was left at, filed as pdf.js files it. */
    @Test
    fun aPdfInTheSimpleViewOpensAtThePageItWasLeftAt() {
        lockedWebView()
        renderersStandIn()
        val uri = FixtureProvider.uriFor("six-pages.pdf")
        val length = Fixtures.file("six-pages.pdf").length()
        Positions.save(context, Positions.keyFor(context.contentResolver, uri, length)!!, 4, 6)
        val viewer = handingOver(true) { view(uri) }
        assertThat(viewer.pageName()).isEqualTo("pdfsimple.html")
        assertThat(viewer.loadedUrl()).contains("&resume=4")
    }

    /** One a provider streams can't be handed to the renderer, so it keeps the card. */
    @Test
    fun aPdfTheRendererCantBeHandedKeepsTheCard() {
        lockedWebView()
        val viewer = handingOver(false) { open("six-pages.pdf") }
        assertThat(viewer.pageName()).isEqualTo("pdf.html")
        assertThat(viewer.loadedUrl()).contains("&locked=1")
    }

    /**
     * Nor can a file inside a zip, which is never written out as a file of its own. Android
     * refuses one stored as it is, a window onto the zip, as a descriptor of its own. One
     * compressed comes through a pipe, which Robolectric makes a file, so here only the viewer's
     * own check keeps it from the renderer.
     */
    @Test
    fun aPdfInsideAZipOnALockedWebViewKeepsTheCard() {
        lockedWebView()
        for (stored in listOf(true, false)) {
            val intent = Intent()
                .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
                .setData(pdfInAZip(stored))
            val viewer = handingOver(true) { start(intent) }
            assertThat("stored $stored: ${viewer.pageName()}").isEqualTo("stored $stored: pdf.html")
            assertThat(viewer.loadedUrl()).contains("&locked=1")
        }
    }

    /** Its page is never served the file, which only the isolated renderer reads. */
    @Test
    fun theSimpleViewsPageIsNeverServedTheFile() {
        lockedWebView()
        renderersStandIn()
        val viewer = handingOver(true) { open("six-pages.pdf") }
        assertThat(viewer.pageName()).isEqualTo("pdfsimple.html")
        assertThat(viewer.serve("/doc/file.pdf")?.statusCode).isEqualTo(404)
    }

    /** Something the page says up the channel, which it is handed once it has loaded. */
    private fun ActivityController<ViewerActivity>.pageSays(message: String) {
        val web = webView()!!
        if (shadowOf(web).createdPorts.isEmpty()) web.webViewClient.onPageFinished(web, loadedUrl())
        val mine = shadowOf(web).createdPorts.single()[0]
        mine.webMessageCallback.onMessage(mine, WebMessage(message))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The page a rebuilt viewer settles on, once it has asked the provider on a thread of its own. */
    private fun ActivityController<ViewerActivity>.pageOnceAsked(expected: String): String {
        val deadline = System.currentTimeMillis() + 10_000
        while (pageName() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return pageName()
    }

    /**
     * A WebView an update would raise keeps the card, which says to update it, and has a button
     * for the simple view. The tap there comes up the channel, the viewer is rebuilt to show it,
     * and the ask survives that.
     */
    @Test
    fun aPdfOnAWebViewAnUpdateWouldRaiseKeepsTheCardUntilTheReaderAsks() {
        oldWebView()
        renderersStandIn()
        handingOver(true) {
            val viewer = open("six-pages.pdf")
            assertThat(viewer.pageName()).isEqualTo("pdf.html")
            assertThat(viewer.loadedUrl()).contains("&webview=114&needs=125")
            assertThat(viewer.loadedUrl()).doesNotContain("locked")
            assertThat(viewer.loadedUrl()).contains("&simple=1")

            viewer.pageSays("simple")
            assertThat(viewer.pageOnceAsked("pdfsimple.html")).isEqualTo("pdfsimple.html")
        }
    }

    /** No button for a file the renderer can't be handed, and a page asking anyway isn't heard. */
    @Test
    fun theCardHasNoSimpleViewForAFileTheRendererCantBeHanded() {
        oldWebView()
        handingOver(false) {
            val viewer = open("six-pages.pdf")
            assertThat(viewer.loadedUrl()).contains("&webview=114&needs=125")
            assertThat(viewer.loadedUrl()).doesNotContain("&simple=")
            val before = viewer.get()
            viewer.pageSays("simple")
            assertThat(viewer.get()).isSameInstanceAs(before)
        }
    }

    /** Nor for a PDF inside a zip, which is never a file of its own. */
    @Test
    fun theCardHasNoSimpleViewForAPdfInsideAZip() {
        oldWebView()
        for (stored in listOf(true, false)) {
            val intent = Intent()
                .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
                .setData(pdfInAZip(stored))
            val viewer = handingOver(true) { start(intent) }
            assertThat(viewer.loadedUrl()).contains("&webview=114&needs=125")
            assertThat("stored $stored: ${viewer.loadedUrl()}").doesNotContain("&simple=")
        }
    }

    /** Where pdf.js runs there is no card, and a page asking for the simple view isn't heard. */
    @Test
    fun aPdfWherePdfJsRunsCantBeAskedIntoTheSimpleView() {
        renderersStandIn()
        handingOver(true) {
            val viewer = open("six-pages.pdf")
            assertThat(viewer.loadedUrl()).doesNotContain("&needs=")
            assertThat(viewer.loadedUrl()).doesNotContain("&simple=")
            val before = viewer.get()
            viewer.pageSays("simple")
            assertThat(viewer.get()).isSameInstanceAs(before)
        }
    }

    // ---------------------------------------------------------------
    // The simple view's pages
    // ---------------------------------------------------------------

    /** A stand-in for each of the viewer's renderers, bound in its place and nothing else's. */
    private fun renderersStandIn(): List<FakeRenderer> = RendererSlots.services.map { service ->
        FakeRenderer().also {
            val component = ComponentName(context, service)
            shadowOf(context as Application).setComponentNameAndServiceForBindServiceForIntent(
                Intent().setComponent(component), component, it
            )
        }
    }

    /**
     * [path] answered as the WebView asks for it, off the main thread, with the main thread run
     * meanwhile: the renderer is bound there, and the simple view's lane may need it.
     */
    private fun ActivityController<ViewerActivity>.serveOff(path: String): WebResourceResponse? {
        val web = webView()!!
        val client = web.webViewClient
        val request = Request(Uri.parse("https://appassets.androidplatform.net$path"), emptyMap())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val answer = pool.submit(Callable { client.shouldInterceptRequest(web, request) })
            val deadline = System.currentTimeMillis() + 10_000
            while (!answer.isDone && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            return answer.get(1, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun WebResourceResponse.json() = JSONObject(data.readBytes().decodeToString())

    /** A PNG's width and height, from its header. */
    private fun WebResourceResponse.pngSize(): List<Int> {
        val png = data.readBytes()
        return listOf(ByteBuffer.wrap(png, 16, 4).int, ByteBuffer.wrap(png, 20, 4).int)
    }

    /**
     * The page learns the document from its own renderer, and gets each page from it as a PNG,
     * never kept, drawn at the width asked for with the page's highlights and comments.
     */
    @Test
    fun theSimpleViewsPagesComeFromItsOwnRenderer() {
        lockedWebView()
        val renderer = renderersStandIn().first()
        handingOver(true) {
            val viewer = open("six-pages.pdf")
            val document = viewer.serveOff("/render/document")!!
            assertThat(document.statusCode).isEqualTo(200)
            assertThat(document.mimeType).isEqualTo("application/json")
            val said = document.json()
            assertThat(listOf(said.getInt("pages"), said.getInt("width"), said.getInt("height")))
                .containsExactly(3, 595, 842).inOrder()

            val page = viewer.serveOff("/render/page?n=2&w=600&night=0")!!
            assertThat(page.statusCode).isEqualTo(200)
            assertThat(page.mimeType).isEqualTo("image/png")
            assertThat(page.responseHeaders).containsEntry("Cache-Control", "no-store")
            assertThat(page.responseHeaders).containsEntry("X-Content-Type-Options", "nosniff")
            assertThat(page.pngSize()).containsExactly(600, 849).inOrder()
            assertThat(renderer.drawn).containsExactly("1 600x849 flags ${PdfRenderService.RENDER_NOTES}")

            val sizes = viewer.serveOff("/render/sizes?from=2&to=3")!!.json().getJSONArray("sizes")
            assertThat(sizes.length()).isEqualTo(2)
            assertThat(sizes.getJSONArray(1).getInt(1)).isEqualTo(842)
            viewer.destroy()
        }
    }

    /** What it is asked for that no page is, or past what it will draw at once, it doesn't draw. */
    @Test
    fun theSimpleViewDrawsOnlyPagesThereAre() {
        lockedWebView()
        val renderer = renderersStandIn().first().apply { pages = 20 }
        handingOver(true) {
            val viewer = open("six-pages.pdf")
            assertThat(viewer.serveOff("/render/sizes?from=5&to=20")?.statusCode).isEqualTo(200)
            for (asked in listOf(
                "/render/page?n=21&w=600", "/render/page?n=0&w=600", "/render/page?n=x&w=600",
                "/render/page?n=1", "/render/page?n=1&w=-5", "/render/sizes?from=1&to=21",
                "/render/sizes?from=2&to=1", "/render/sizes?from=4&to=20", "/render/elsewhere",
            )) {
                assertThat("$asked ${viewer.serveOff(asked)?.statusCode}").isEqualTo("$asked 404")
            }
            assertThat(renderer.drawn).isEmpty()
            // A width past the caps is drawn within them
            assertThat(viewer.serveOff("/render/page?n=1&w=999999999")!!.pngSize()[0])
                .isAtMost(PdfRenderService.MAX_SIDE)
            viewer.destroy()
        }
    }

    /** No other page is served the renderer, nor has one bound for it. */
    @Test
    fun noPageButTheSimpleViewsIsServedItsPages() {
        val renderer = renderersStandIn().first()
        val viewer = open("six-pages.pdf")
        assertThat(viewer.pageName()).isEqualTo("pdf.html")
        assertThat(viewer.serve("/render/document")?.statusCode).isEqualTo(404)
        assertThat(viewer.serve("/render/page?n=1&w=600")?.statusCode).isEqualTo(404)
        assertThat(renderer.opens.get()).isEqualTo(0)
    }

    /** Night mode turns the simple view's pages over, the whole of each page. */
    @Test
    fun theSimpleViewTurnsItsPagesOverAtNight() {
        lockedWebView()
        val renderer = renderersStandIn().first()
        renderer.pixels = { _, w, h -> ByteArray(4 * w * h) { -1 } }
        Settings.setNight(context, true)
        handingOver(true) {
            val viewer = open("six-pages.pdf")
            assertThat(viewer.toolbarItem(R.id.action_night_mode).isVisible).isTrue()
            assertThat(viewer.loadedUrl()).contains("&night=1")
            fun paper(night: Int): Int {
                val png = viewer.serveOff("/render/page?n=1&w=40&night=$night")!!.data.readBytes()
                return JdkPng(png).rgb(20, 20)
            }
            assertThat(paper(1)).isEqualTo(0x000000)
            assertThat(paper(0)).isEqualTo(0xffffff)
            viewer.destroy()
        }
    }

    /**
     * Android's renderer can't open a PDF that needs a password, nor can its printing: the page is
     * told why, and Print says so rather than send it there.
     */
    @Test
    fun aPdfThatNeedsAPasswordInTheSimpleViewIsNeitherShownNorPrinted() {
        lockedWebView()
        renderersStandIn().first().pages = PdfRenderService.OPEN_PASSWORD
        handingOver(true) {
            val viewer = open("six-pages.pdf")
            assertThat(viewer.serveOff("/render/document")!!.json().getString("error")).isEqualTo("password")
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(viewer.get().printLocked).isTrue()
            viewer.destroy()
        }
    }

    /**
     * A renderer that dies on a page takes the page with it, and the reader is told. The viewer
     * gives up its renderer all the same, so the next simple view opened has it.
     */
    @Test
    fun aRendererThatDiesOnAPageLeavesTheCard() {
        lockedWebView()
        val renderers = renderersStandIn()
        val renderer = renderers.first()
        handingOver(true) {
            val viewer = open("six-pages.pdf")
            assertThat(viewer.serveOff("/render/document")?.statusCode).isEqualTo(200)
            // Half a page, from a renderer that is then gone
            renderer.onRender = {
                renderer.dead = true
                renderer.bytes = { it / 2 }
            }
            assertThat(viewer.serveOff("/render/page?n=1&w=100&night=0")?.statusCode).isEqualTo(404)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(viewer.webView()).isNull()
            assertThat(viewer.get().findViewById<TextView>(R.id.renderGoneTitle).text.toString())
                .isEqualTo(context.getString(R.string.render_gone_crashed))
            viewer.destroy()

            // The next simple view gets the same renderer, free again. Had the viewer kept it, the
            // next would have gone to another.
            renderer.dead = false
            renderer.bytes = { it }
            renderer.onRender = {}
            val next = open("six-pages.pdf")
            assertThat(next.serveOff("/render/document")?.statusCode).isEqualTo(200)
            assertThat(renderers.map { it.opens.get() }).containsExactly(2, 0, 0, 0).inOrder()
            next.destroy()
        }
    }

    /**
     * A request still waiting its turn when the viewer closes is answered, with nothing. Left
     * waiting, the WebView thread that asked would wait for good, and hold the viewer with it.
     */
    @Test
    fun aRequestStillWaitingWhenTheSimpleViewClosesIsAnswered() {
        lockedWebView()
        val renderer = renderersStandIn().first()
        // The renderer takes its time over the document, so whatever is asked next waits behind it
        renderer.onOpen = {
            renderer.entered.countDown()
            renderer.release.await()
        }
        handingOver(true) {
            val viewer = open("six-pages.pdf")
            val web = viewer.webView()!!
            val asking = AtomicReference<Thread>()
            val pool = Executors.newSingleThreadExecutor { job -> Thread(job).also(asking::set) }
            try {
                val request = Request(Uri.parse("https://appassets.androidplatform.net/render/document"), emptyMap())
                val answer = pool.submit(Callable { web.webViewClient.shouldInterceptRequest(web, request) })
                // Queued, its thread waiting on it: asked too late to be queued, it would be refused
                // at once and this would prove nothing
                val deadline = System.currentTimeMillis() + 10_000
                while (!(renderer.entered.count == 0L && asking.get()?.waitsOnItsJob() == true) &&
                    System.currentTimeMillis() < deadline
                ) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(5)
                }
                assertWithMessage("the request waiting behind the document")
                    .that(renderer.entered.count == 0L && asking.get()?.waitsOnItsJob() == true).isTrue()

                viewer.destroy()
                assertThat(answer.get(5, TimeUnit.SECONDS)?.statusCode).isEqualTo(404)
            } finally {
                renderer.release.countDown()
                pool.shutdownNow()
            }
        }
    }

    /** Whether this thread is parked in SimplePdf.respond(), waiting for its job to be done. */
    private fun Thread.waitsOnItsJob(): Boolean {
        val frames = stackTrace
        return state == Thread.State.WAITING &&
            frames.any { it.className == SimplePdf::class.java.name && it.methodName == "respond" } &&
            frames.any { it.className == FutureTask::class.java.name && it.methodName == "get" }
    }

    /** What the simple view can't show is said under the file's name, as Android draws it. */
    @Test
    fun theSimpleViewSaysUnderTheNameWhatItCantShow() {
        lockedWebView()
        renderersStandIn()
        handingOver(true) {
            val viewer = open("six-pages.pdf")
            assertThat(viewer.underTheName()).isEqualTo(context.getString(R.string.simple_view_line_notes))
            viewer.destroy()
        }
    }

    /** Below 15, Android draws no highlights or comments either. */
    @Test
    @Config(sdk = [34])
    fun theSimpleViewSaysItHasNoNotesBelowAndroid15() {
        lockedWebView()
        renderersStandIn()
        handingOver(true) {
            val viewer = open("six-pages.pdf")
            assertThat(viewer.underTheName()).isEqualTo(context.getString(R.string.simple_view_line))
            viewer.destroy()
        }
    }

    /** A debuggable build opens a PDF in the simple view when told to, whatever the WebView. */
    @Test
    fun aDebuggableBuildOpensTheSimpleViewWhenAsked() {
        renderersStandIn()
        val asked = { Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(FixtureProvider.uriFor("six-pages.pdf"), "application/pdf")
            .putExtra(ViewerActivity.EXTRA_SIMPLE_VIEW, true) }
        handingOver(true) {
            context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
            start(asked()).apply {
                assertThat(pageName()).isEqualTo("pdfsimple.html")
                destroy()
            }
            // A release build is not
            context.applicationInfo.flags = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertThat(start(asked()).pageName()).isEqualTo("pdf.html")
        }
    }

    /**
     * Every viewer showing a PDF in the simple view has a renderer of its own, so that no two hold
     * documents in one process. A fifth takes the renderer of the one asked for a page longest
     * ago, which opens its document again in another when it is next asked for one.
     */
    @Test
    fun eachSimpleViewHasARendererOfItsOwn() {
        lockedWebView()
        val renderers = renderersStandIn()
        fun opens() = renderers.map { it.opens.get() }
        handingOver(true) {
            val viewers = (0 until 4).map {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                open("six-pages.pdf").also { viewer ->
                    assertThat(viewer.serveOff("/render/document")?.statusCode).isEqualTo(200)
                }
            }
            assertThat(opens()).containsExactly(1, 1, 1, 1)

            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            val fifth = open("six-pages.pdf")
            assertThat(fifth.serveOff("/render/document")?.statusCode).isEqualTo(200)
            assertThat(opens()).containsExactly(2, 1, 1, 1).inOrder()

            assertThat(viewers[0].serveOff("/render/page?n=1&w=10&night=0")?.statusCode).isEqualTo(200)
            assertThat(opens()).containsExactly(2, 2, 1, 1).inOrder()
            (viewers + fifth).forEach { it.destroy() }
        }
    }

    /** A photo gets the tiling view, not a WebView. */
    @Test
    fun aPhotoIsDrawnByTheTilingViewInstead() {
        val controller = open("exif-1.jpg")
        assertThat(controller.webView()).isNull()
        assertThat(controller.container().children().filterIsInstance<SubsamplingScaleImageView>())
            .hasSize(1)
    }

    /** The tiling view draws one frame, so a WebP or PNG that moves gets the page that plays GIFs. #49 */
    @Test
    fun aMovingWebpOrPngGetsThePageThatPlaysIt() {
        assertThat(open("moving.webp").pageName()).isEqualTo("imgweb.html")
        assertThat(open("moving.png").pageName()).isEqualTo("imgweb.html")

        for (still in listOf("still.webp", "tiny.png")) {
            val controller = open(still)
            assertThat(controller.webView()).isNull()
            assertThat(controller.container().children().filterIsInstance<SubsamplingScaleImageView>())
                .hasSize(1)
        }
    }

    @Test
    fun audioGetsThePlayerRatherThanAPage() {
        val controller = open("tone.wav")
        assertThat(controller.webView()).isNull()
        assertThat(controller.container().childCount).isAtLeast(1)
    }

    /** The file name reaches the page, so it can title itself. */
    @Test
    fun thePageIsToldTheFileName() {
        val uri = FixtureProvider.uriNamed("six-pages.pdf", "Alder Court.pdf")
        assertThat(view(uri).loadedUrl()).contains("name=Alder%20Court.pdf")
    }

    @Test
    fun theToolbarShowsTheDocumentName() {
        val uri = FixtureProvider.uriNamed("six-pages.pdf", "Alder Court.pdf")
        val toolbar = view(uri).get()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        assertThat(toolbar.title.toString()).isEqualTo("Alder Court.pdf")
    }

    // ---------------------------------------------------------------
    // Unknown formats, issue #51
    // ---------------------------------------------------------------

    /** The line under the file's name in the title bar, or null where there is none. */
    private fun ActivityController<ViewerActivity>.underTheName(): String? =
        get().findViewById<MaterialToolbar>(R.id.toolbar).subtitle?.toString()

    /** How many channels the page was handed once it had loaded: one for a text file's page. */
    private fun ActivityController<ViewerActivity>.channels(): Int {
        val web = webView()!!
        web.webViewClient.onPageFinished(web, loadedUrl())
        return shadowOf(web).createdPorts.size
    }

    /** One that reads as text opens as a text file does, channel and all, and says why under its name. */
    @Test
    fun anUnknownFormatThatReadsAsTextOpensAsText() {
        val controller = open("unknown.xyz")
        assertThat(controller.pageName()).isEqualTo("text.html")
        assertThat(controller.loadedUrl()).contains("ext=xyz")
        assertThat(controller.underTheName()).isEqualTo("Shown as text, unknown format")
        assertThat(controller.channels()).isEqualTo(open("plain.txt").channels())
    }

    /** One that does not keeps the card, with nothing under its name and no channel. */
    @Test
    fun anUnknownFormatThatIsNotTextKeepsItsCard() {
        val controller = open("opaque.xyz")
        assertThat(controller.pageName()).isEqualTo("unsupported.html")
        assertThat(controller.underTheName()).isNull()
        assertThat(controller.channels()).isEqualTo(0)
    }

    /** One that cannot be read is not text either, and gets the card it always had. */
    @Test
    fun anUnknownFormatThatCannotBeReadKeepsItsCard() {
        val gone = File(context.cacheDir, "never-written.xyz")
        val controller = view(FixtureProvider.install().add("gone.xyz", gone))
        assertThat(controller.pageName()).isEqualTo("unsupported.html")
        assertThat(controller.underTheName()).isNull()
    }

    /** The line is for a file Gander has no name for, so a format it knows goes without. */
    @Test
    fun aKnownFormatSaysNothingUnderItsName() {
        for (fixture in listOf("plain.txt", "six-pages.pdf", "exif-1.jpg", "archive.zip")) {
            assertWithMessage(fixture).that(open(fixture).underTheName()).isNull()
        }
    }

    /**
     * Graphviz saves its graphs as .dot, and one opens as text, with nothing under its name: Gander
     * knows what it is. Issue #58.
     */
    @Test
    fun aGraphvizDotOpensAsText() {
        val graph = File(context.cacheDir, "graph.dot").apply { writeText("digraph G {\n  a -> b;\n}\n") }
        val controller = view(FixtureProvider.install().add("graph.dot", graph))
        assertThat(controller.pageName()).isEqualTo("text.html")
        assertThat(controller.loadedUrl()).contains("ext=dot")
        assertThat(controller.underTheName()).isNull()
    }

    /** A Word template is a .dot too, and still goes to the prose page, as does Rich Text so named. */
    @Test
    fun aWordTemplateDotStillOpensInTheProsePage() {
        for ((fixture, name) in listOf("legacy.doc" to "template.dot", "memo.rtf" to "letter.dot")) {
            assertWithMessage(name).that(view(FixtureProvider.uriNamed(fixture, name)).pageName())
                .isEqualTo("prose.html")
        }
    }

    /** Word's single-file XML named .doc opens in the Word page, which zips it back up. */
    @Test
    fun aWordXmlPackageDocOpensInTheWordPage() {
        val controller = view(FixtureProvider.uriNamed("word-package.doc", "certificate.doc"))
        assertThat(controller.pageName()).isEqualTo("docx.html")
        assertThat(controller.underTheName()).isNull()
    }

    /** A mail app may hand over only the type, with no extension on the name, and it still goes there. */
    @Test
    fun aWordXmlPackageTypedAsWordOpensInTheWordPage() {
        val uri = FixtureProvider.uriNamed("word-package.doc", "certificate")
        assertThat(view(uri, "application/msword").pageName()).isEqualTo("docx.html")
    }

    /** The other files for the prose page still go to it, read the same way. */
    @Test
    fun theProsePageKeepsItsOwnFiles() {
        for ((fixture, name) in listOf("legacy.doc" to "old.doc", "letter.odt" to "letter.odt", "memo.rtf" to "memo.rtf")) {
            assertWithMessage(name).that(view(FixtureProvider.uriNamed(fixture, name)).pageName())
                .isEqualTo("prose.html")
        }
    }

    /** A .ppt keeps its card though these bytes read as text, since the card says how to get it opened. */
    @Test
    fun aLegacyPowerPointKeepsItsCard() {
        val controller = view(FixtureProvider.uriNamed("unknown.xyz", "slides.ppt"))
        assertThat(controller.pageName()).isEqualTo("unsupported.html")
        assertThat(controller.loadedUrl()).contains("ext=ppt")
        assertThat(controller.underTheName()).isNull()
    }

    /** The line says how the page was shown, so it goes when the renderer under the page does. */
    @Test
    fun theLineGoesWithARendererThatHasGone() {
        val controller = open("unknown.xyz")
        assertThat(controller.underTheName()).isNotNull()
        val web = controller.webView()!!
        web.webViewClient.onRenderProcessGone(web, rendererReclaimed())
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(controller.webView()).isNull()
        assertThat(controller.underTheName()).isNull()
    }

    /**
     * With no working WebView, as while Android System WebView updates, a document gets a card
     * saying so, where it was a crash, and nothing on the toolbar speaks to a page that isn't there.
     */
    @Test
    @Config(shadows = [ShadowMissingWebView::class])
    fun withNoWorkingWebViewADocumentGetsACardRatherThanACrash() {
        // So Print would be offered, as on a phone
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_PRINTING, true)
        val controller = open("six-pages.pdf")
        assertThat(controller.webView()).isNull()
        assertThat(controller.get().findViewById<TextView>(R.id.renderGoneTitle).text.toString())
            .isEqualTo(context.getString(R.string.no_webview))
        for (item in listOf(R.id.action_search, R.id.action_night_mode, R.id.action_print)) {
            assertWithMessage(context.resources.getResourceEntryName(item))
                .that(controller.toolbarItem(item).isVisible).isFalse()
        }
    }

    /**
     * Inside a zip the first bytes come through the archive's provider: a stored file as the
     * part of the zip it sits in, which begins well after the zip's own first bytes, and a
     * packed one down a pipe the check stops reading early. The packing runs at once here,
     * since Robolectric's pipe is a file, and one nothing has been written to yet reads as empty.
     */
    @Test
    fun anUnknownFormatInsideAZipIsToldByItsBytesToo() {
        val text = Fixtures.bytes("unknown.xyz")
        val file = File.createTempFile("unknown", ".zip").apply { deleteOnExit() }
        java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("stored.xyz").apply {
                method = java.util.zip.ZipEntry.STORED
                size = text.size.toLong()
                compressedSize = text.size.toLong()
                crc = java.util.zip.CRC32().apply { update(text) }.value
            })
            z.write(text)
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("packed.xyz"))
            z.write(text)
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("opaque.xyz"))
            z.write(Fixtures.bytes("opaque.xyz"))
            z.closeEntry()
        }
        val archive = FixtureProvider.install().add("unknown.zip", file)
        Robolectric.buildContentProvider(ArchiveProvider::class.java)
            .create(ArchiveProvider.authority(context))
            .get().writers = Executor { it.run() }
        val raf = java.io.RandomAccessFile(file, "r")
        val entries = ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, java.util.Locale.US)
        }
        fun opened(name: String) = start(
            Intent()
                .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
                .setData(ArchiveProvider.uriFor(context, archive, entries.single { it.path == name }))
        )

        for (name in listOf("stored.xyz", "packed.xyz")) {
            val controller = opened(name)
            assertWithMessage(name).that(controller.pageName()).isEqualTo("text.html")
            assertWithMessage(name).that(controller.underTheName()).isEqualTo("Shown as text, unknown format")
        }
        val card = opened("opaque.xyz")
        assertThat(card.pageName()).isEqualTo("unsupported.html")
        assertThat(card.underTheName()).isNull()
    }

    // ---------------------------------------------------------------
    // Ranged loading
    // ---------------------------------------------------------------

    /** Below the threshold, the page is told to read the file whole. */
    @Test
    fun aSmallDocumentIsNotRanged() {
        assertThat(open("six-pages.pdf").loadedUrl()).contains("ranged=0")
    }

    /**
     * Above it, both sides switch together: the page uses the ranged loader
     * and the interceptor starts offering ranges. They are decided from the
     * one number so they cannot disagree.
     */
    @Test
    fun aLargeDocumentIsRanged() {
        val big = Fixtures.sized("big.pdf", (RANGE_THRESHOLD_BYTES + 1).toInt())
        val uri = FixtureProvider.install().add("big.pdf", big)
        assertThat(view(uri, "application/pdf").loadedUrl()).contains("ranged=1")
    }

    // ---------------------------------------------------------------
    // Serving the document
    // ---------------------------------------------------------------

    private class Request(
        private val url: Uri,
        private val headers: Map<String, String>
    ) : WebResourceRequest {
        override fun getUrl() = url
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = headers
    }

    private fun ActivityController<ViewerActivity>.serve(
        path: String,
        range: String? = null
    ): WebResourceResponse? {
        val web = webView()!!
        val headers = range?.let { mapOf("Range" to it) } ?: emptyMap()
        return web.webViewClient.shouldInterceptRequest(
            web, Request(Uri.parse("https://appassets.androidplatform.net$path"), headers)
        )
    }

    @Test
    fun theDocumentIsServedWholeWhenNoRangeIsAskedFor() {
        val response = open("six-pages.pdf").serve("/doc/file.pdf")!!
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.mimeType).isEqualTo("application/pdf")
        assertThat(response.responseHeaders["Content-Length"])
            .isEqualTo(Fixtures.file("six-pages.pdf").length().toString())
        assertThat(response.data.readBytes()).isEqualTo(Fixtures.bytes("six-pages.pdf"))
    }

    /** A small document never offers ranges, so a Range header is ignored. */
    @Test
    fun aRangeAskedOfASmallDocumentIsAnsweredWithTheWholeThing() {
        val response = open("six-pages.pdf").serve("/doc/file.pdf", "bytes=0-99")!!
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.responseHeaders).doesNotContainKey("Accept-Ranges")
    }

    @Test
    fun aRangeableDocumentAnswersARangeWithExactlyThoseBytes() {
        val size = (RANGE_THRESHOLD_BYTES + 512).toInt()
        val big = Fixtures.sized("ranged.pdf", size)
        val uri = FixtureProvider.install().add("ranged.pdf", big)
        val controller = view(uri, "application/pdf")

        val response = controller.serve("/doc/file.pdf", "bytes=100-199")!!

        assertThat(response.statusCode).isEqualTo(206)
        assertThat(response.reasonPhrase).isEqualTo("Partial Content")
        assertThat(response.responseHeaders["Content-Range"])
            .isEqualTo("bytes 100-199/$size")
        assertThat(response.responseHeaders["Content-Length"]).isEqualTo("100")
        assertThat(response.data.readBytes()).hasLength(100)
    }

    @Test
    fun aRangeableDocumentAdvertisesThatItAcceptsRanges() {
        val big = Fixtures.sized("ranged2.pdf", (RANGE_THRESHOLD_BYTES + 1).toInt())
        val uri = FixtureProvider.install().add("ranged2.pdf", big)
        val response = view(uri, "application/pdf").serve("/doc/file.pdf")!!
        assertThat(response.responseHeaders["Accept-Ranges"]).isEqualTo("bytes")
    }

    /**
     * Issue #78: asked by the text page, a file in a Windows code page comes with the one it is
     * in, and whole, though its head was read off the stream for the guess. Longer than the
     * head, so the two halves are joined back up.
     */
    @Test
    fun aTextFileInAWindowsCodePageIsServedWholeWithItsCodePage() {
        val text = "这是一个用记事本保存的文本文件。我们在会议上讨论了下一季度的计划。\r\n"
        val bytes = text.repeat(TextEncoding.HEAD_BYTES / 40).toByteArray(charset("GBK"))
        assertThat(bytes.size).isGreaterThan(TextEncoding.HEAD_BYTES)
        val file = File.createTempFile("gbk", ".txt").apply { writeBytes(bytes); deleteOnExit() }
        val controller = view(FixtureProvider.install().add("gbk.txt", file), "text/plain")

        val guessed = controller.serve("/doc/file.txt?encoding=guess")!!
        assertThat(guessed.responseHeaders[ENCODING_HEADER]).isEqualTo("gb18030")
        assertThat(guessed.data.readBytes()).isEqualTo(bytes)

        // Only the text page asks, and every other page is served the file as it was
        val plain = controller.serve("/doc/file.txt")!!
        assertThat(plain.responseHeaders).doesNotContainKey(ENCODING_HEADER)
        assertThat(plain.data.readBytes()).isEqualTo(bytes)
    }

    @Test
    fun aUtf8TextFileComesWithNoCodePage() {
        val response = open("plain.txt").serve("/doc/file.txt?encoding=guess")!!
        assertThat(response.responseHeaders).doesNotContainKey(ENCODING_HEADER)
        assertThat(response.data.readBytes()).isEqualTo(Fixtures.bytes("plain.txt"))
    }

    @Test
    fun anUnsatisfiableRangeFallsBackToTheWholeDocument() {
        val big = Fixtures.sized("ranged3.pdf", (RANGE_THRESHOLD_BYTES + 1).toInt())
        val uri = FixtureProvider.install().add("ranged3.pdf", big)
        val response = view(uri, "application/pdf")
            .serve("/doc/file.pdf", "bytes=99999999999-")!!
        assertThat(response.statusCode).isEqualTo(200)
    }

    /**
     * A provider that dies mid-document gets a 404 rather than an exception
     * thrown inside the renderer, which pdf.js reports as a readable error.
     */
    @Test
    fun aProviderThatFailsIsAnsweredWithNotFound() {
        val controller = view(FixtureProvider.uriFor(FixtureProvider.BROKEN), "application/pdf")
        val response = controller.serve("/doc/file.pdf")!!
        assertThat(response.statusCode).isEqualTo(404)
    }

    // ---------------------------------------------------------------
    // Pictures beside a Markdown file (#62)
    // ---------------------------------------------------------------

    /**
     * A folder, notes, holding drafts/README.md with a picture and a text file beside it, and a
     * picture a folder up. Answers the folder the reader added, [added], as a tree.
     */
    private fun notesFolder(added: String = "notes"): Uri {
        val png = Fixtures.bytes("tiny.png")
        FakeDocumentsProvider.install()
            .folder(
                "notes", "notes",
                ChildDoc("notes/drafts", "drafts", MIME_DIR, 0, 0),
                ChildDoc("notes/cover.png", "cover.png", "image/png", 0, 0),
            )
            .folder(
                "notes/drafts", "drafts",
                ChildDoc("notes/drafts/README.md", "README.md", "text/markdown", 0, 0),
                ChildDoc("notes/drafts/preview.png", "preview.png", "image/png", 0, 0),
                ChildDoc("notes/drafts/todo.txt", "todo.txt", "text/plain", 0, 0),
            )
            .contents("notes/drafts/README.md", "![](preview.png)".toByteArray())
            .contents("notes/drafts/preview.png", png)
            .contents("notes/drafts/todo.txt", "milk".toByteArray())
            .contents("notes/cover.png", png)
        return FakeDocumentsProvider.treeUri(added)
    }

    /** [docId] opened from the folder list, which is how Gander opens a file in [tree]. */
    private fun openedFrom(tree: Uri, docId: String): ActivityController<ViewerActivity> = start(
        Intent()
            .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
            .setData(DocumentsContract.buildDocumentUriUsingTree(tree, docId))
    )

    private fun ActivityController<ViewerActivity>.picture(path: String): WebResourceResponse =
        serve(PicturesBeside.PATH + "?path=" + Uri.encode(path))!!

    @Test
    fun aMarkdownFileFromAnAddedFolderIsServedThePicturesItNamesBesideIt() {
        val viewer = openedFrom(notesFolder(), "notes/drafts/README.md")
        assertThat(viewer.pageName()).isEqualTo("md.html")

        val beside = viewer.picture("preview.png")
        assertThat(beside.statusCode).isEqualTo(200)
        assertThat(beside.mimeType).isEqualTo("image/png")
        assertThat(beside.responseHeaders["X-Content-Type-Options"]).isEqualTo("nosniff")
        assertThat(beside.data.readBytes()).isEqualTo(Fixtures.bytes("tiny.png"))
        assertThat(viewer.picture("../cover.png").statusCode).isEqualTo(200)
        // Only a picture, and only one that is there
        assertThat(viewer.picture("todo.txt").statusCode).isEqualTo(404)
        assertThat(viewer.picture("gone.png").statusCode).isEqualTo(404)
    }

    @Test
    fun nothingAboveTheAddedFolderIsServed() {
        val viewer = openedFrom(notesFolder(added = "notes/drafts"), "notes/drafts/README.md")
        assertThat(viewer.picture("preview.png").statusCode).isEqualTo(200)
        assertThat(viewer.picture("../cover.png").statusCode).isEqualTo(404)
    }

    /** Open with from another app grants the one file, and pages other than Markdown never ask. */
    @Test
    fun noPictureIsServedToAFileFromAnotherAppOrToAnotherPage() {
        val text = openedFrom(notesFolder(), "notes/drafts/todo.txt")
        assertThat(text.pageName()).isEqualTo("text.html")
        assertThat(text.picture("preview.png").statusCode).isEqualTo(404)

        // tiny.png is beside it in the fixtures, but this provider grants the one file
        val readme = FixtureProvider.install().add("README.md", Fixtures.file("notes.md"))
        val fromAnotherApp = view(readme, "text/markdown")
        assertThat(fromAnotherApp.pageName()).isEqualTo("md.html")
        assertThat(fromAnotherApp.picture("tiny.png").statusCode).isEqualTo(404)
    }

    /** Everything that is not the document comes from the bundled assets. */
    @Test
    fun assetRequestsAreServedFromTheApk() {
        val response = open("six-pages.pdf").serve("/assets/viewer/app.js")
        assertThat(response).isNotNull()
        assertThat(response!!.data.readBytes().decodeToString()).contains("vwDocUrl")
    }

    /**
     * The pages name the policy themselves, and the header says it again for the one thing
     * a meta tag cannot reach: the pdf.js worker, which takes its policy from its own
     * script's response rather than from the page that started it.
     */
    @Test
    fun everyAssetIsServedUnderTheViewerPolicy() {
        val controller = open("six-pages.pdf")
        listOf(
            "/assets/viewer/pdf.html",
            "/assets/viewer/pdf.mjs",
            "/assets/viewer/lib/pdf.worker.min.mjs",
        ).forEach { path ->
            val policy = controller.serve(path)!!.responseHeaders["Content-Security-Policy"]
            assertWithMessage(path).that(policy).isEqualTo(ViewerPolicy.CSP)
        }
    }

    /**
     * The document is on the pages' own host, which the policy trusts for scripts, so it is
     * marked as never to be read as anything but the type it is served as, whole or in part.
     */
    @Test
    fun theDocumentIsNeverTakenForAScript() {
        assertThat(open("six-pages.pdf").serve("/doc/file.pdf")!!.responseHeaders["X-Content-Type-Options"])
            .isEqualTo("nosniff")

        val big = Fixtures.sized("sniffed.pdf", (RANGE_THRESHOLD_BYTES + 512).toInt())
        val ranged = view(FixtureProvider.install().add("sniffed.pdf", big), "application/pdf")
            .serve("/doc/file.pdf", "bytes=0-99")!!
        assertThat(ranged.statusCode).isEqualTo(206)
        assertThat(ranged.responseHeaders["X-Content-Type-Options"]).isEqualTo("nosniff")
    }

    // ---------------------------------------------------------------
    // Swiping through a folder's photos (#75)
    // ---------------------------------------------------------------

    /** A folder, pics, holding photos, one that moves, a text file and a folder, each a different age. */
    private fun picsFolder(): Uri {
        FakeDocumentsProvider.install()
            .folder(
                "pics", "pics",
                ChildDoc("pics/b.jpg", "b.jpg", "image/jpeg", 30, 3_000),
                ChildDoc("pics/a.png", "a.png", "image/png", 10, 1_000),
                ChildDoc("pics/notes.txt", "notes.txt", "text/plain", 5, 5_000),
                ChildDoc("pics/old", "old", MIME_DIR, 0, 0),
                ChildDoc("pics/c.webp", "c.webp", "image/webp", 20, 4_000),
                ChildDoc("pics/d.webp", "d.webp", "image/webp", 40, 2_000),
            )
            .folder("pics/old", "old")
            .contents("pics/b.jpg", Fixtures.bytes("exif-1.jpg"))
            .contents("pics/a.png", Fixtures.bytes("tiny.png"))
            .contents("pics/c.webp", Fixtures.bytes("still.webp"))
            .contents("pics/d.webp", Fixtures.bytes("moving.webp"))
            .contents("pics/notes.txt", "milk".toByteArray())
        return FakeDocumentsProvider.treeUri("pics")
    }

    private fun ActivityController<ViewerActivity>.title(): String =
        get().findViewById<MaterialToolbar>(R.id.toolbar).title.toString()

    private fun ActivityController<ViewerActivity>.swipeTo(photo: Int) {
        get().photoPager!!.view.setCurrentItem(photo, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun android.view.View.andInside(): List<android.view.View> =
        listOf(this) + ((this as? android.view.ViewGroup)?.let { group ->
            (0 until group.childCount).flatMap { group.getChildAt(it).andInside() }
        } ?: emptyList())

    /** The WebViews anywhere in the viewer, a page of the folder's photos included. */
    private fun ActivityController<ViewerActivity>.webViews(): List<WebView> =
        container().andInside().filterIsInstance<WebView>()

    @Test
    fun aPhotoFromAnAddedFolderSwipesToTheFoldersOtherPhotos() {
        val viewer = openedFrom(picsFolder(), "pics/b.jpg")
        val pager = viewer.get().photoPager!!
        assertThat(pager.names).containsExactly("a.png", "b.jpg", "c.webp", "d.webp").inOrder()
        assertThat(pager.view.currentItem).isEqualTo(1)
        assertThat(viewer.title()).isEqualTo("b.jpg")

        viewer.swipeTo(2)

        assertThat(viewer.title()).isEqualTo("c.webp")
        val readout = viewer.get().findViewById<TextView>(R.id.pageIndicator)
        assertThat(readout.text.toString()).isEqualTo("3/4")
        assertThat(readout.contentDescription.toString()).isEqualTo("Photo 3 of 4")
        assertThat(readout.isClickable).isFalse()
    }

    @Test
    fun theFoldersPhotosComeInTheOrderTheHomeScreenIsSortedIn() {
        Settings.setSortOrder(context, Settings.SortOrder.NEWEST)
        val pager = openedFrom(picsFolder(), "pics/b.jpg").get().photoPager!!
        assertThat(pager.names).containsExactly("c.webp", "b.jpg", "d.webp", "a.png").inOrder()
        assertThat(pager.view.currentItem).isEqualTo(1)
    }

    @Test
    fun shareAndSaveACopyGoWithThePhotoOnScreen() {
        val tree = picsFolder()
        val viewer = openedFrom(tree, "pics/b.jpg")
        viewer.swipeTo(0)
        val menu = viewer.get().findViewById<MaterialToolbar>(R.id.toolbar).menu

        menu.performIdentifierAction(R.id.action_share, 0)
        val chooser = shadowOf(viewer.get()).nextStartedActivity
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
            .isEqualTo(DocumentsContract.buildDocumentUriUsingTree(tree, "pics/a.png"))
        assertThat(send.type).isEqualTo("image/png")

        menu.performIdentifierAction(R.id.action_save_copy, 0)
        val save = Bundle().also { viewer.saveInstanceState(it) }
        assertThat(save.getString("copy_source"))
            .isEqualTo(DocumentsContract.buildDocumentUriUsingTree(tree, "pics/a.png").toString())
    }

    /** Opened any other way, Android shares that one photo with Gander and no more. */
    @Test
    fun aPhotoFromAnywhereElseHasNothingBesideIt() {
        assertThat(open("exif-1.jpg").get().photoPager).isNull()
        assertThat(viewFromGander(FixtureProvider.uriFor("exif-1.jpg")).get().photoPager).isNull()
        // Nor one another app hands over from inside a folder it was given
        val handed = view(DocumentsContract.buildDocumentUriUsingTree(picsFolder(), "pics/b.jpg"), "image/jpeg")
        assertThat(handed.get().photoPager).isNull()
    }

    @Test
    fun thePhotoSwipedToIsTheOneShownWhenTheViewerIsMadeAgain() {
        val viewer = openedFrom(picsFolder(), "pics/b.jpg")
        viewer.swipeTo(2)
        val saved = Bundle().also { viewer.saveInstanceState(it) }

        val again = closing.build(ViewerActivity::class.java, viewer.intent)
        again.get().documentLoader = Executor { it.run() }
        again.setup(saved)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(again.title()).isEqualTo("c.webp")
        assertThat(again.get().photoPager!!.view.currentItem).isEqualTo(2)
    }

    /** Playing takes the viewer's one WebView, so only the picture on screen plays. */
    @Test
    fun aMovingPictureInTheFolderPlaysOnlyWhileItIsOnScreen() {
        val viewer = openedFrom(picsFolder(), "pics/b.jpg")
        assertThat(viewer.webViews()).isEmpty()

        viewer.swipeTo(3)
        val playing = viewer.webViews()
        assertThat(playing).hasSize(1)
        assertThat(shadowOf(playing[0]).lastLoadedUrl).contains("viewer/imgweb.html?name=d.webp")

        viewer.swipeTo(2)
        assertThat(viewer.webViews().map { shadowOf(it).lastLoadedUrl }).isEmpty()
        assertThat(shadowOf(playing[0]).wasDestroyCalled()).isTrue()
    }

    @Test
    fun aPhotoAloneInItsFolderShowsNoCount() {
        FakeDocumentsProvider.install()
            .folder("solo", "solo", ChildDoc("solo/x.jpg", "x.jpg", "image/jpeg", 1, 1))
            .contents("solo/x.jpg", Fixtures.bytes("exif-1.jpg"))
        val viewer = openedFrom(FakeDocumentsProvider.treeUri("solo"), "solo/x.jpg")
        assertThat(viewer.get().photoPager!!.count).isEqualTo(1)
        assertThat(viewer.get().findViewById<TextView>(R.id.pageIndicator).visibility)
            .isEqualTo(android.view.View.GONE)
        assertThat(viewer.container().andInside().filterIsInstance<SubsamplingScaleImageView>()).hasSize(1)
    }

    // ---------------------------------------------------------------
    // Turning a photo (#71)
    // ---------------------------------------------------------------

    private fun ActivityController<ViewerActivity>.rotateItem(): android.view.MenuItem =
        get().findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(R.id.action_rotate)

    private fun ActivityController<ViewerActivity>.rotate() {
        get().findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_rotate, 0)
        // Laid out the new way round
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    /** The tiling view drawing a photo opened alone. */
    private fun ActivityController<ViewerActivity>.still(): StillPhotoView =
        container().children().filterIsInstance<StillPhotoView>().single()

    @Test
    fun rotateTurnsAPhotoAQuarterTurnClockwiseAtATimeAndFitsItOnItsSide() {
        val viewer = open("exif-1.jpg")
        assertThat(viewer.rotateItem().isVisible).isTrue()
        val space = viewer.container()
        val photo = viewer.still()
        assertThat(space.width).isNotEqualTo(space.height)

        viewer.rotate()

        assertThat(photo.turn).isEqualTo(90)
        assertThat(photo.rotation).isEqualTo(90f)
        // Laid out across its space, and turned about the middle of it, so it covers that space
        assertThat(listOf(photo.width, photo.height)).isEqualTo(listOf(space.height, space.width))
        assertThat(photo.left + photo.translationX + photo.width / 2f).isEqualTo(space.width / 2f)
        assertThat(photo.top + photo.translationY + photo.height / 2f).isEqualTo(space.height / 2f)

        repeat(3) { viewer.rotate() }

        assertThat(photo.turn).isEqualTo(0)
        assertThat(photo.rotation).isEqualTo(0f)
        assertThat(listOf(photo.width, photo.height)).isEqualTo(listOf(space.width, space.height))
        assertThat(listOf(photo.translationX, photo.translationY)).isEqualTo(listOf(0f, 0f))
    }

    /** A picture that moves is drawn by its page, which Rotate can't turn, and a document has nothing to turn. */
    @Test
    fun onlyAPhotoTheTilingViewDrawsIsOfferedRotate() {
        assertThat(open("moving.webp").rotateItem().isVisible).isFalse()
        assertThat(open("plain.txt").rotateItem().isVisible).isFalse()
    }

    @Test
    fun eachOfAFoldersPhotosKeepsItsOwnTurn() {
        val viewer = openedFrom(picsFolder(), "pics/b.jpg")
        val pager = viewer.get().photoPager!!
        viewer.rotate()
        val turned = pager.stillShown!!
        assertThat(turned.turn).isEqualTo(90)

        viewer.swipeTo(0)
        assertThat(viewer.rotateItem().isVisible).isTrue()
        assertThat(pager.stillShown!!.turn).isEqualTo(0)

        // A picture that moves has no Rotate, and this far off the turned photo's page is let go
        viewer.swipeTo(3)
        assertThat(viewer.rotateItem().isVisible).isFalse()

        viewer.swipeTo(1)
        assertThat(viewer.title()).isEqualTo("b.jpg")
        assertThat(pager.stillShown).isNotSameInstanceAs(turned)
        assertThat(pager.stillShown!!.turn).isEqualTo(90)
        assertThat(viewer.rotateItem().isVisible).isTrue()
    }

    /** On a phone a photo is drawn after its folder is counted, so Rotate has to come with the photo. */
    @Test
    fun rotateComesOnceThePhotoIsDrawn() {
        val (viewer, held) = openedHeld(picsFolder(), "pics/b.jpg")
        assertThat(viewer.rotateItem().isVisible).isFalse()

        viewer.runHeld(held)

        assertThat(viewer.get().photoPager!!.stillShown).isNotNull()
        assertThat(viewer.rotateItem().isVisible).isTrue()
    }

    /** The bar's buttons, left to right: the visible items the bar has room to show as icons. */
    private fun ActivityController<ViewerActivity>.barButtons(): List<String> {
        shadowOf(Looper.getMainLooper()).idle()
        val menu = get().findViewById<MaterialToolbar>(R.id.toolbar).menu
        return (0 until menu.size()).map { menu.getItem(it) }
            .filter { it.isVisible && (it as androidx.appcompat.view.menu.MenuItemImpl).isActionButton }
            .map { it.title.toString() }
    }

    /**
     * A phone's bar has room for two buttons beside the menu. Photo viewers keep Share and Delete a
     * tap away, so among a folder's photos Rotate goes into the menu; a photo opened alone has no
     * Delete, and Rotate takes the second button.
     */
    @Test
    @Config(qualifiers = "w384dp-h832dp")
    fun onAPhonesBarShareAndDeleteKeepTheirButtonsAndRotateTakesWhatIsLeft() {
        val folder = openedFrom(dcim(), "primary:DCIM/b.jpg")
        assertThat(folder.rotateItem().isVisible).isTrue()
        assertThat(folder.barButtons()).containsExactly("Share", "Delete").inOrder()

        assertThat(open("exif-1.jpg").barButtons()).containsExactly("Share", "Rotate").inOrder()
    }

    @Test
    fun aTurnedPhotoIsStillTurnedWhenTheViewerIsMadeAgain() {
        val viewer = open("exif-1.jpg")
        viewer.rotate()
        val saved = Bundle().also { viewer.saveInstanceState(it) }

        val again = closing.build(ViewerActivity::class.java, viewer.intent)
        again.get().documentLoader = Executor { it.run() }
        again.setup(saved)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(again.still().turn).isEqualTo(90)
    }

    // ---------------------------------------------------------------
    // Deleting a photo in a folder's photos (#75)
    // ---------------------------------------------------------------

    private lateinit var docs: FakeDocumentsProvider
    private lateinit var media: FakeMediaStore

    /** A folder on the phone's own storage, which Android's trash reaches, of three photos its media store lists. */
    private fun dcim(): Uri {
        docs = FakeDocumentsProvider.install(EXTERNAL_STORAGE_AUTHORITY)
            .folder(
                "primary:DCIM", "DCIM",
                ChildDoc("primary:DCIM/a.jpg", "a.jpg", "image/jpeg", 10, 1_000),
                ChildDoc("primary:DCIM/b.jpg", "b.jpg", "image/jpeg", 20, 2_000),
                ChildDoc("primary:DCIM/c.png", "c.png", "image/png", 30, 3_000),
            )
            .contents("primary:DCIM/a.jpg", Fixtures.bytes("exif-1.jpg"))
            .contents("primary:DCIM/b.jpg", Fixtures.bytes("exif-1.jpg"))
            .contents("primary:DCIM/c.png", Fixtures.bytes("tiny.png"))
        val tree = FakeDocumentsProvider.treeUri("primary:DCIM", EXTERNAL_STORAGE_AUTHORITY)
        media = FakeMediaStore.install()
        listOf("a.jpg", "b.jpg", "c.png").forEachIndexed { i, name ->
            media.entry(DocumentsContract.buildDocumentUriUsingTree(tree, "primary:DCIM/$name"), 11L + i)
        }
        return tree
    }

    private fun ActivityController<ViewerActivity>.deleteItem() =
        get().findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(R.id.action_delete)

    /** Taps Delete, and gives back the prompt Android was asked to open, if any was. */
    private fun ActivityController<ViewerActivity>.tapDelete(): ShadowActivity.IntentSenderRequest? {
        get().findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_delete, 0)
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(get()).lastIntentSenderRequest
    }

    /** Hands back the prompt's answer, as Android does once a button in it is tapped. */
    private fun ActivityController<ViewerActivity>.answer(prompt: ShadowActivity.IntentSenderRequest, result: Int) {
        assertThat(get().activityResultRegistry.dispatchResult(prompt.requestCode, result, null)).isTrue()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Deletes [docId]: the prompt said yes to, and the photo moved out of its folder. */
    private fun ActivityController<ViewerActivity>.delete(docId: String) {
        val prompt = tapDelete()!!
        docs.remove(docId)
        answer(prompt, Activity.RESULT_OK)
    }

    @Test
    fun aDeletedPhotoGoesToAndroidsTrashAndTheNextOneShows() {
        val viewer = openedFrom(dcim(), "primary:DCIM/b.jpg")
        assertThat(viewer.deleteItem().isVisible).isTrue()

        viewer.delete("primary:DCIM/b.jpg")

        // Asked for by its entry among the pictures, under the number Android gave it among all files
        val (asked, values) = media.trashRequests.single()
        assertThat(asked).containsExactly(MediaStore.Images.Media.getContentUri(FakeMediaStore.VOLUME, 12))
        assertThat(values!!.getAsInteger(MediaStore.MediaColumns.IS_TRASHED)).isEqualTo(1)
        assertThat(viewer.get().photoPager!!.names).containsExactly("a.jpg", "c.png").inOrder()
        assertThat(viewer.title()).isEqualTo("c.png")
        assertThat(viewer.get().findViewById<TextView>(R.id.pageIndicator).text.toString()).isEqualTo("2/2")
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(context.getString(R.string.photo_trashed))
    }

    /** A no needs no word. Android answers yes after a move that failed too, which only looking catches. */
    @Test
    fun aPhotoStaysWhenThePromptIsTurnedDownOrNothingMoves() {
        val viewer = openedFrom(dcim(), "primary:DCIM/b.jpg")

        viewer.answer(viewer.tapDelete()!!, Activity.RESULT_CANCELED)
        assertThat(viewer.get().photoPager!!.names).containsExactly("a.jpg", "b.jpg", "c.png").inOrder()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)

        viewer.answer(viewer.tapDelete()!!, Activity.RESULT_OK)
        assertThat(viewer.get().photoPager!!.names).containsExactly("a.jpg", "b.jpg", "c.png").inOrder()
        assertThat(viewer.title()).isEqualTo("b.jpg")
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(context.getString(R.string.photo_not_trashed))
    }

    @Test
    fun deletingTheLastPhotoShowsTheOneBeforeAndDeletingTheOnlyOneClosesTheViewer() {
        val viewer = openedFrom(dcim(), "primary:DCIM/c.png")

        viewer.delete("primary:DCIM/c.png")
        assertThat(viewer.title()).isEqualTo("b.jpg")
        viewer.delete("primary:DCIM/b.jpg")
        assertThat(viewer.title()).isEqualTo("a.jpg")
        // One photo has no count to show
        assertThat(viewer.get().findViewById<TextView>(R.id.pageIndicator).visibility)
            .isEqualTo(android.view.View.GONE)
        // Each time for the photo on screen
        assertThat(media.trashRequests.map { it.first.single() }).containsExactly(
            MediaStore.Images.Media.getContentUri(FakeMediaStore.VOLUME, 13),
            MediaStore.Images.Media.getContentUri(FakeMediaStore.VOLUME, 12),
        ).inOrder()

        viewer.delete("primary:DCIM/a.jpg")
        assertThat(viewer.get().isFinishing).isTrue()
    }

    /** Only among a folder's photos, and only where Android's trash reaches. */
    @Test
    fun deleteIsOfferedOnlyAmongAFoldersPhotosOnThePhonesStorage() {
        assertThat(openedFrom(dcim(), "primary:DCIM/a.jpg").deleteItem().isVisible).isTrue()
        // A folder another app keeps, whose files Android's media store doesn't list
        assertThat(openedFrom(picsFolder(), "pics/b.jpg").deleteItem().isVisible).isFalse()
        // A photo opened alone, from anywhere, the phone's storage too
        assertThat(open("exif-1.jpg").deleteItem().isVisible).isFalse()
        val picked = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:DCIM/a.jpg")
        assertThat(viewFromGander(picked, "image/jpeg").deleteItem().isVisible).isFalse()
    }

    /** The folder's photos were in the page a moving one plays in, and Delete goes with that page. */
    @Test
    fun deleteGoesWithARendererThatHasGone() {
        FakeDocumentsProvider.install(EXTERNAL_STORAGE_AUTHORITY)
            .folder(
                "primary:Clips", "Clips",
                ChildDoc("primary:Clips/m.webp", "m.webp", "image/webp", 1, 1),
                ChildDoc("primary:Clips/s.jpg", "s.jpg", "image/jpeg", 1, 2),
            )
            .contents("primary:Clips/m.webp", Fixtures.bytes("moving.webp"))
            .contents("primary:Clips/s.jpg", Fixtures.bytes("exif-1.jpg"))
        val tree = FakeDocumentsProvider.treeUri("primary:Clips", EXTERNAL_STORAGE_AUTHORITY)
        val viewer = openedFrom(tree, "primary:Clips/m.webp")
        assertThat(viewer.deleteItem().isVisible).isTrue()

        val web = viewer.webViews().single()
        web.webViewClient.onRenderProcessGone(web, rendererReclaimed())
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(viewer.deleteItem().isVisible).isFalse()
    }

    /**
     * A file the media store doesn't count as a picture, such as one in a folder holding a .nomedia
     * file, gets a word and no prompt, since the prompt would take it and then move nothing.
     */
    @Test
    fun aPhotoAndroidCannotTrashSaysSoWithoutAPrompt() {
        val tree = dcim()
        media.entry(
            DocumentsContract.buildDocumentUriUsingTree(tree, "primary:DCIM/b.jpg"), 12,
            MediaStore.Files.FileColumns.MEDIA_TYPE_NONE,
        )
        val viewer = openedFrom(tree, "primary:DCIM/b.jpg")

        assertThat(viewer.tapDelete()).isNull()
        assertThat(media.trashRequests).isEmpty()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(context.getString(R.string.photo_not_trashed))
    }

    /** The answer can come back to a viewer made again while the prompt was up, which has to know what was asked. */
    @Test
    fun aDeleteAnsweredAfterTheViewerIsMadeAgainStillCounts() {
        val viewer = openedFrom(dcim(), "primary:DCIM/b.jpg")
        val prompt = viewer.tapDelete()!!
        val saved = Bundle().also { viewer.saveInstanceState(it) }
        docs.remove("primary:DCIM/b.jpg")

        val again = closing.build(ViewerActivity::class.java, viewer.intent)
        again.get().documentLoader = Executor { it.run() }
        again.setup(saved)
        shadowOf(Looper.getMainLooper()).idle()
        again.answer(prompt, Activity.RESULT_OK)

        // Made again around a photo that has gone, it has no folder around it to show
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(context.getString(R.string.photo_trashed))
        assertThat(again.get().isFinishing).isTrue()
    }

    /**
     * The viewer for [docId] in [tree], with what it asks off the main thread held back, as a
     * phone's storage is slower than the first layout. Run the held work with [runHeld].
     */
    private fun openedHeld(tree: Uri, docId: String): Pair<ActivityController<ViewerActivity>, MutableList<Runnable>> {
        val held = mutableListOf<Runnable>()
        val viewer = closing.build(
            ViewerActivity::class.java,
            Intent()
                .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
                .setData(DocumentsContract.buildDocumentUriUsingTree(tree, docId))
        )
        viewer.get().documentLoader = Executor { held += it }
        viewer.setup()
        shadowOf(Looper.getMainLooper()).idle()
        return viewer to held
    }

    private fun ActivityController<ViewerActivity>.runHeld(held: MutableList<Runnable>) {
        while (held.isNotEmpty()) held.removeAt(0).run()
        shadowOf(Looper.getMainLooper()).idle()
        get().documentLoader = Executor { it.run() }
    }

    /** Delete waits for the folder, since a photo deleted before it would close a viewer that knows no others. */
    @Test
    fun deleteWaitsForTheFolderToBeRead() {
        val (viewer, held) = openedHeld(dcim(), "primary:DCIM/b.jpg")
        assertThat(viewer.deleteItem().isVisible).isFalse()

        viewer.runHeld(held)

        assertThat(viewer.deleteItem().isVisible).isTrue()
    }

    /** No fill names a photo alone in its folder, so nothing but the folder's arrival brings its Delete in. */
    @Test
    fun aPhotoAloneInItsFolderCanBeDeletedAndTheViewerCloses() {
        docs = FakeDocumentsProvider.install(EXTERNAL_STORAGE_AUTHORITY)
            .folder("primary:One", "One", ChildDoc("primary:One/x.jpg", "x.jpg", "image/jpeg", 1, 1))
            .contents("primary:One/x.jpg", Fixtures.bytes("exif-1.jpg"))
        val tree = FakeDocumentsProvider.treeUri("primary:One", EXTERNAL_STORAGE_AUTHORITY)
        media = FakeMediaStore.install().entry(DocumentsContract.buildDocumentUriUsingTree(tree, "primary:One/x.jpg"), 31)
        val (viewer, held) = openedHeld(tree, "primary:One/x.jpg")
        assertThat(viewer.deleteItem().isVisible).isFalse()

        viewer.runHeld(held)
        assertThat(viewer.deleteItem().isVisible).isTrue()
        viewer.delete("primary:One/x.jpg")

        assertThat(viewer.get().isFinishing).isTrue()
    }

    /** Swiped on while the prompt was being got ready, it would ask about a photo no longer on screen. */
    @Test
    fun noPromptOpensForAPhotoSwipedPast() {
        val viewer = openedFrom(dcim(), "primary:DCIM/b.jpg")
        viewer.get().findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_delete, 0)
        // The request is ready, and not yet handed back to the main thread
        viewer.get().photoPager!!.view.setCurrentItem(2, false)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(shadowOf(viewer.get()).lastIntentSenderRequest).isNull()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
        // Delete is for the photo on screen now
        viewer.delete("primary:DCIM/c.png")
        assertThat(viewer.get().photoPager!!.names).containsExactly("a.jpg", "b.jpg").inOrder()
    }

    /** A second tap while the first is got ready, or while its prompt is up, opens no second prompt. */
    @Test
    fun aSecondTapOnDeleteOpensNoSecondPrompt() {
        val viewer = openedFrom(dcim(), "primary:DCIM/b.jpg")
        val menu = viewer.get().findViewById<MaterialToolbar>(R.id.toolbar).menu

        menu.performIdentifierAction(R.id.action_delete, 0)
        menu.performIdentifierAction(R.id.action_delete, 0)
        shadowOf(Looper.getMainLooper()).idle()
        menu.performIdentifierAction(R.id.action_delete, 0)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(media.trashRequests).hasSize(1)
    }

    /** The photo after takes the deleted one's number, so no page change comes to play it if it moves. */
    @Test
    fun aMovingPhotoThatTakesTheDeletedOnesPlacePlays() {
        docs = FakeDocumentsProvider.install(EXTERNAL_STORAGE_AUTHORITY)
            .folder(
                "primary:Clips", "Clips",
                ChildDoc("primary:Clips/a.jpg", "a.jpg", "image/jpeg", 1, 1),
                ChildDoc("primary:Clips/m.webp", "m.webp", "image/webp", 1, 2),
            )
            .contents("primary:Clips/a.jpg", Fixtures.bytes("exif-1.jpg"))
            .contents("primary:Clips/m.webp", Fixtures.bytes("moving.webp"))
        val tree = FakeDocumentsProvider.treeUri("primary:Clips", EXTERNAL_STORAGE_AUTHORITY)
        media = FakeMediaStore.install().entry(DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Clips/a.jpg"), 21)
        val viewer = openedFrom(tree, "primary:Clips/a.jpg")
        assertThat(viewer.webViews()).isEmpty()

        viewer.delete("primary:Clips/a.jpg")

        assertThat(viewer.title()).isEqualTo("m.webp")
        assertThat(shadowOf(viewer.webViews().single()).lastLoadedUrl).contains("viewer/imgweb.html?name=m.webp")
    }

    // ---------------------------------------------------------------
    // The boundary around the page
    // ---------------------------------------------------------------

    /**
     * A document is untrusted content, and this is the wall around it. The
     * page may load nothing but the assets host: no CDN, no tracking pixel,
     * no link a crafted PDF talks the renderer into following.
     */
    @Test
    fun thePageMayNotNavigateAnywhereButTheAssetHost() {
        val web = open("six-pages.pdf").webView()!!
        listOf(
            "https://example.com/",
            "http://appassets.androidplatform.net.example.com/",
            "file:///etc/hosts",
            "content://com.arjun.gander.debug.fileprovider/cache/x",
        ).forEach { url ->
            val blocked = web.webViewClient.shouldOverrideUrlLoading(
                web, Request(Uri.parse(url), emptyMap())
            )
            assertThat("$url blocked: $blocked").isEqualTo("$url blocked: true")
        }
    }

    @Test
    fun theAssetHostItselfIsAllowedThrough() {
        val web = open("six-pages.pdf").webView()!!
        val blocked = web.webViewClient.shouldOverrideUrlLoading(
            web, Request(Uri.parse("https://appassets.androidplatform.net/assets/viewer/text.html"), emptyMap())
        )
        assertThat(blocked).isFalse()
    }

    @Test
    fun theWebViewReachesNeitherTheFilesystemNorTheProviders() {
        val settings = open("six-pages.pdf").webView()!!.settings
        assertThat(settings.allowFileAccess).isFalse()
        assertThat(settings.allowContentAccess).isFalse()
    }

    private fun ActivityController<ViewerActivity>.request(url: String): WebResourceResponse? {
        val web = webView()!!
        return web.webViewClient.shouldInterceptRequest(web, Request(Uri.parse(url), emptyMap()))
    }

    /**
     * The missing INTERNET permission stops all of these at the socket. This is the
     * second wall: nothing a page asks for is handed to the network stack at all, so the
     * guarantee does not rest on the permission alone. A null here would mean "go and
     * fetch it".
     */
    @Test
    fun anythingThePageAsksForThatGanderDoesNotServeIsAnsweredWithNotFound() {
        val controller = open("six-pages.pdf")
        listOf(
            "https://example.com/pixel.png",
            "http://example.com/",
            "https://appassets.androidplatform.net.example.com/assets/viewer/app.js",
            "http://appassets.androidplatform.net/assets/viewer/app.js",
            "http://appassets.androidplatform.net/doc/file.pdf",
            "https://appassets.androidplatform.net/elsewhere",
        ).forEach { url ->
            val status = controller.request(url)?.statusCode
            assertThat("$url answered $status").isEqualTo("$url answered 404")
        }
    }

    /** Bytes the page already holds, which never leave it. */
    @Test
    fun dataUrlsAreLeftToThePage() {
        assertThat(open("six-pages.pdf").request("data:image/png;base64,iVBORw0KGgo=")).isNull()
    }

    /**
     * On the asset host, only Gander's own pages. The document itself is on that host
     * too, at /doc/, and opened as a page it would be rendered as whatever its type says
     * instead of by the viewer that makes it safe to look at.
     */
    @Test
    fun theOnlyPagesThePageMayNavigateToAreGandersOwn() {
        val web = open("six-pages.pdf").webView()!!
        listOf(
            "https://appassets.androidplatform.net/doc/file.pdf",
            "https://appassets.androidplatform.net/assets/licences.md",
            "https://appassets.androidplatform.net/assets/viewer/app.js",
            "https://appassets.androidplatform.net/assets/viewer/../../doc/file.html",
            "http://appassets.androidplatform.net/assets/viewer/text.html",
        ).forEach { url ->
            val blocked = web.webViewClient.shouldOverrideUrlLoading(
                web, Request(Uri.parse(url), emptyMap())
            )
            assertThat("$url blocked: $blocked").isEqualTo("$url blocked: true")
        }
    }

    /**
     * Neither is anything Gander uses, and on a page showing an untrusted document both
     * are only a place for a document's script to leave something for the next one.
     */
    @Test
    fun thePageKeepsNeitherDomStorageNorCookies() {
        val web = open("six-pages.pdf").webView()!!
        assertThat(web.settings.domStorageEnabled).isFalse()
        assertThat(CookieManager.getInstance().acceptCookie()).isFalse()
    }

    // ---------------------------------------------------------------
    // How a file arrives
    // ---------------------------------------------------------------

    @Test
    fun sharedPlainTextIsWrittenOutAndShownAsText() {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Something copied out of another app")
        val controller = start(intent)

        assertThat(controller.loadedUrl()).contains("viewer/text.html")
        assertThat(File(ViewerActivity.shareableDir(context), "shared-text.txt").readText())
            .isEqualTo("Something copied out of another app")
    }

    /**
     * FileProvider keeps the folders it covers in a static map, read from the first test's cache,
     * and Robolectric gives every test a cache of its own: forgotten, so this test's are read.
     */
    private fun forgetFileProviderRoots() {
        val roots = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (roots.get(null) as MutableMap<*, *>).clear()
    }

    /** It can be shared on, through the FileProvider, and goes once the viewer has closed. */
    @Test
    fun sharedTextCanBeSharedOnAndGoesWithTheViewer() {
        forgetFileProviderRoots()
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Something copied out of another app")
        val controller = start(intent)
        val file = File(ViewerActivity.shareableDir(context), "shared-text.txt")

        controller.get().findViewById<MaterialToolbar>(R.id.toolbar).menu
            .performIdentifierAction(R.id.action_share, 0)
        val chooser = shadowOf(controller.get()).nextStartedActivity
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        val shared = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)
        assertThat(shared.toString())
            .isEqualTo("content://${context.packageName}.fileprovider/shared/shared-text.txt")

        assertThat(file.exists()).isTrue()
        controller.get().finish()
        controller.pause().stop().destroy()
        assertThat(file.exists()).isFalse()
    }

    /**
     * Nothing else in the cache can be handed to another app through the FileProvider, a
     * thumbnail least of all, since it is a copy of someone's photo.
     */
    @Test
    fun theFileProviderReachesOnlyTheFilesTheViewerShares() {
        forgetFileProviderRoots()
        val authority = "${context.packageName}.fileprovider"
        val page = File(ViewerActivity.shareableDir(context), "Open source licences.md").apply { writeText("x") }
        assertThat(FileProvider.getUriForFile(context, authority, page)).isNotNull()
        val thumb = File(context.cacheDir, "thumbs/x.png").apply { parentFile!!.mkdirs(); writeText("x") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, authority, thumb)
        }
    }

    @Test
    fun aSharedFileArrivesThroughExtraStream() {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, FixtureProvider.uriFor("six-pages.pdf"))
        val controller = start(intent)
        assertThat(controller.loadedUrl()).contains("viewer/pdf.html")
    }

    /** The path extra, which only the bundled licence viewer uses, from inside Gander. */
    @Test
    fun aPlainPathFromGanderIsOpenedAsAFile() {
        val intent = Intent()
            .setClassName(context, ViewerActivity.INTERNAL_VIEWER)
            .putExtra(ViewerActivity.EXTRA_PATH, Fixtures.file("notes.md").absolutePath)
        val controller = start(intent)
        assertThat(controller.loadedUrl()).contains("viewer/md.html")
    }

    /**
     * A path is read with Gander's access, not the sender's, and without a storage permission
     * the only paths that reaches are Gander's own: the Recents file, say, or /dev/zero, which
     * never ends. So another app naming one, by the extra or as a file:// URI, gets nothing.
     */
    @Test
    fun anotherAppCannotHaveGanderOpenAPath() {
        val recents = File(context.dataDir, "shared_prefs/recents.xml")
        listOf(
            Intent(context, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_PATH, recents.absolutePath),
            Intent(context, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_PATH, "/dev/zero"),
            Intent(context, ViewerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(recents), "text/xml"),
            Intent(context, ViewerActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///dev/zero")),
        ).forEach { intent ->
            val activity = closing.build(ViewerActivity::class.java, intent).setup().get()
            assertWithMessage(intent.toUri(0)).that(activity.isFinishing).isTrue()
        }
    }

    /**
     * Nor one of Gander's own content URIs, which read with Gander's access too: the
     * FileProvider covers the cache, where the thumbnails of the reader's documents are. By
     * the authority Android finds the provider with, so the 0@ spelling of the same one is
     * refused as well, as for zips, and so is 0%40, whose host is not the provider's.
     */
    @Test
    fun anotherAppCannotHaveGanderOpenItsOwnProviders() {
        val own = "${context.packageName}.fileprovider"
        listOf(
            "content://$own/cache/thumbs/0.png",
            "content://0@$own/cache/thumbs/0.png",
            "content://0%40$own/cache/thumbs/0.png",
            "content://${own.uppercase()}/cache/thumbs/0.png",
            "content://${context.packageName}/anything",
        ).forEach { url ->
            val activity = view(Uri.parse(url), "image/png").get()
            assertWithMessage(url).that(activity.isFinishing).isTrue()
        }
    }

    /** Nothing to show is not a blank screen; it is not a screen at all. */
    @Test
    fun anIntentCarryingNothingClosesTheViewer() {
        val intent = Intent(context, ViewerActivity::class.java)
        val controller = start(intent)
        assertThat(controller.get().isFinishing).isTrue()
    }

    // ---------------------------------------------------------------
    // Recents, and state
    // ---------------------------------------------------------------

    @Test
    fun openingAPickedFileRemembersIt() {
        context.getSharedPreferences("recents", Context.MODE_PRIVATE).edit().clear().commit()
        val uri = FixtureProvider.uriNamed("six-pages.pdf", "Alder Court.pdf")
        viewFromGander(uri)
        assertThat(Recents.all(context).map { it.name }).containsExactly("Alder Court.pdf")
    }

    /**
     * Recents fills from Gander's own screens, which is where the picker is. Another app
     * offering a grant Gander could keep would otherwise put an entry there, under a name of
     * its own choosing, for the reader to tap later.
     */
    @Test
    fun aFileAnotherAppHandsOverIsNotRemembered() {
        context.getSharedPreferences("recents", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = view(FixtureProvider.uriNamed("six-pages.pdf", "Your statement.pdf"))
        assertThat(controller.loadedUrl()).contains("viewer/pdf.html")
        assertThat(Recents.all(context)).isEmpty()
    }

    /**
     * The save picker outlives the activity if Android reclaims the process
     * while it is open, and comes back with a destination but no source. The
     * source is kept in the bundle for exactly that.
     */
    @Test
    fun theSaveDestinationSurvivesTheProcessGoingAway() {
        val controller = open("six-pages.pdf")
        controller.get()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.performIdentifierAction(R.id.action_save_copy, 0)

        val state = android.os.Bundle()
        controller.saveInstanceState(state)

        assertThat(state.getString("copy_source")).contains("six-pages.pdf")
    }

    /** The copy is the whole file, and the bar goes once it has been written. */
    @Test
    fun aSavedCopyIsTheWholeFile() {
        val activity = open("six-pages.pdf").get()
        val dest = Uri.parse("content://test.saved/copy.pdf")
        val written = java.io.ByteArrayOutputStream()
        shadowOf(activity.contentResolver).registerOutputStream(dest, written)

        activity.copier.copy(FixtureProvider.uriFor("six-pages.pdf"), dest)
        val deadline = System.currentTimeMillis() + 10_000
        while (activity.copier.saving && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertThat(activity.copier.saving).isFalse()
        assertThat(written.toByteArray()).isEqualTo(Fixtures.bytes("six-pages.pdf"))
        assertThat(activity.findViewById<LinearProgressIndicator>(R.id.saveProgress).visibility)
            .isEqualTo(android.view.View.GONE)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(context.getString(R.string.save_copy_done))
    }

    @Test
    fun leavingTheViewerTakesTheWebViewWithIt() {
        val controller = open("six-pages.pdf")
        val web = controller.webView()!!
        controller.pause().stop().destroy()
        assertThat(shadowOf(web).wasDestroyCalled()).isTrue()
    }

    // ---------------------------------------------------------------
    // A zip, issue #30
    // ---------------------------------------------------------------

    /** A zip, with its index read inline so the list is there to assert on. */
    private fun zip(
        uri: Uri = FixtureProvider.uriFor("archive.zip"),
        state: Bundle? = null,
        loader: Executor = Executor { it.run() },
    ): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/zip")
        val controller = closing.build(ViewerActivity::class.java, intent)
        controller.get().archiveLoader = loader
        // setup(null) restores from a null bundle and throws; the no-argument form is the one
        // that means a fresh start
        val started = if (state == null) controller.setup() else controller.setup(state)
        return started.also { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun ActivityController<ViewerActivity>.list(): RecyclerView =
        container().children().filterIsInstance<RecyclerView>().single()

    /** Each row, bound through the adapter, as its title or its message. */
    private fun ActivityController<ViewerActivity>.rows(): List<android.view.View> {
        val rv = list()
        val adapter = rv.adapter!!
        return (0 until adapter.itemCount).map { position ->
            val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView
        }
    }

    private fun ActivityController<ViewerActivity>.titles(): List<String> = rows().map {
        (it.findViewById<TextView>(R.id.title) ?: it.findViewById(R.id.hintText)).text.toString()
    }

    private fun ActivityController<ViewerActivity>.tap(title: String) {
        rows().single { it.findViewById<TextView>(R.id.title)?.text == title }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ActivityController<ViewerActivity>.toolbarTitle(): String =
        get().findViewById<MaterialToolbar>(R.id.toolbar).title.toString()

    @Test
    fun aZipOpensAsAListOfWhatIsInIt() {
        val controller = zip()
        assertThat(controller.webView()).isNull()
        assertThat(controller.titles())
            .containsExactly("nested", "photos", "private", "reports", "plain.txt").inOrder()
        assertThat(controller.toolbarTitle()).isEqualTo("archive.zip")
    }

    /** Back walks up a level at a time, and only leaves from the top. */
    @Test
    fun aFolderOpensInPlaceAndBackWalksOutOfIt() {
        val controller = zip()
        controller.tap("reports")
        assertThat(controller.titles()).containsExactly("notes.md", "six-pages.pdf").inOrder()
        assertThat(controller.toolbarTitle()).isEqualTo("reports")

        controller.get().onBackPressedDispatcher.onBackPressed()
        assertThat(controller.titles()).contains("reports")
        assertThat(controller.toolbarTitle()).isEqualTo("archive.zip")
        assertThat(controller.get().isFinishing).isFalse()

        controller.get().onBackPressedDispatcher.onBackPressed()
        assertThat(controller.get().isFinishing).isTrue()
    }

    /** A file opens in a viewer of its own, under the name only Gander can start. */
    @Test
    fun aFileOpensInTheViewerUnderTheEntryAlias() {
        val controller = zip()
        controller.tap("plain.txt")
        val started = shadowOf(controller.get()).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(ViewerActivity.INTERNAL_VIEWER)
        assertThat(started.data?.authority).isEqualTo(ArchiveProvider.authority(context))
        assertThat(ArchiveProvider.parse(started.data!!)?.name).isEqualTo("plain.txt")
    }

    /**
     * The list keeps its span indexes, as the home screen's does. Without them a tablet's two
     * columns lay a long folder out in time that grows with the square of its length.
     */
    @Test
    fun aZipsListCachesItsSpanIndexes() {
        val list = zip().container().children().filterIsInstance<RecyclerView>().single()
        val spans = (list.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanSizeLookup
        assertThat(spans.isSpanIndexCacheEnabled).isTrue()
        assertThat(spans.isSpanGroupIndexCacheEnabled).isTrue()
    }

    @Test
    fun aFileThatCannotBeOpenedSaysWhyAndOpensNothing() {
        val controller = zip()
        controller.tap("private")
        controller.tap("table.dat")
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(context.getString(R.string.entry_unsupported_open))
    }

    /** It says so in the row as well, before anybody taps it, and so does a file under a password. */
    @Test
    fun aFileThatCannotBeOpenedIsMarkedInTheList() {
        val controller = zip()
        controller.tap("private")
        val subtitles = controller.rows().associate {
            it.findViewById<TextView>(R.id.title).text.toString() to
                it.findViewById<TextView>(R.id.subtitle).text.toString()
        }
        assertThat(subtitles["locked.txt"]).startsWith(context.getString(R.string.entry_locked))
        assertThat(subtitles["table.dat"]).isEqualTo(context.getString(R.string.entry_unsupported))
    }

    // ---------------------------------------------------------------
    // Under a password
    // ---------------------------------------------------------------

    private fun locked() = zip(FixtureProvider.uriFor("locked.zip"))

    /** The password box on screen, and the field in it. */
    private fun passwordBox(): Pair<androidx.appcompat.app.AlertDialog, android.widget.EditText> {
        val box = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        fun find(v: android.view.View): android.widget.EditText? = when (v) {
            is android.widget.EditText -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return box to find(box.window!!.decorView)!!
    }

    private fun androidx.appcompat.app.AlertDialog.type(password: String) {
        passwordBox().second.setText(password)
        getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aFileUnderAPasswordAsksForIt() {
        val controller = zip()
        controller.tap("private")
        controller.tap("locked.txt")
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
        val (box, field) = passwordBox()
        assertThat(box.isShowing).isTrue()
        assertThat(field.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD).isNotEqualTo(0)
        // What is typed shows as dots. The input type alone does not make it so: calling
        // setSingleLine after it swaps the dots back out, which is how this was found
        assertThat(field.transformationMethod)
            .isInstanceOf(android.text.method.PasswordTransformationMethod::class.java)
    }

    /** A wrong one says so where it was typed, and the box stays for another try. */
    @Test
    fun aWrongPasswordSaysSoAndOpensNothing() {
        val controller = locked()
        controller.tap("zipcrypto.txt")
        val (box, field) = passwordBox()
        box.type("goose")
        assertThat(box.isShowing).isTrue()
        assertThat(field.error?.toString()).isEqualTo(context.getString(R.string.password_wrong))
        assertThat(field.text.toString()).isEmpty()
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
    }

    /** The right one opens the file, and every other file under it without asking again. */
    @Test
    fun theRightPasswordOpensTheFileAndIsNotAskedForAgain() {
        val controller = locked()
        controller.tap("zipcrypto.txt")
        val (box, _) = passwordBox()
        box.type("gander")
        assertThat(box.isShowing).isFalse()
        val first = shadowOf(controller.get()).nextStartedActivity
        assertThat(first.component?.className).isEqualTo(ViewerActivity.INTERNAL_VIEWER)
        assertThat(ArchiveProvider.parse(first.data!!)?.name).isEqualTo("zipcrypto.txt")

        controller.tap("aes256-deflate64.md")
        val second = shadowOf(controller.get()).nextStartedActivity
        assertThat(ArchiveProvider.parse(second.data!!)?.name).isEqualTo("aes256-deflate64.md")
        assertThat(ShadowDialog.getLatestDialog()).isSameInstanceAs(box)
    }

    /** Kept while the list is open, so a rotation keeps it, and forgotten once it is left. */
    @Test
    fun thePasswordIsForgottenWhenTheListIsLeft() {
        val uri = FixtureProvider.uriFor("locked.zip")
        val controller = locked()
        controller.tap("zipcrypto.txt")
        passwordBox().first.type("gander")
        assertThat(ArchivePasswords.get(uri)).isEqualTo("gander")
        controller.get().finish()
        controller.pause().stop().destroy()
        assertThat(ArchivePasswords.get(uri)).isNull()
    }

    @Test
    fun aFileLockedInAWayGanderCannotReadSaysWhy() {
        val controller = locked()
        controller.tap("strong.bin")
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(context.getString(R.string.entry_locked_unsupported))
        assertThat(ShadowDialog.getLatestDialog()).isNull()
    }

    // ---------------------------------------------------------------
    // Pictures in the list
    // ---------------------------------------------------------------

    /** Whether the row for [title] asked for a thumbnail. Thumbs tags the view when it is asked. */
    private fun ActivityController<ViewerActivity>.asksForAPicture(title: String): Boolean =
        rows().single { it.findViewById<TextView>(R.id.title)?.text == title }
            .findViewById<android.widget.ImageView>(R.id.thumb).tag != null

    @Test
    fun aPhotoInAZipGetsAPictureAndAPdfKeepsItsBadge() {
        val controller = zip()
        controller.tap("photos")
        assertThat(controller.asksForAPicture("tiny.png")).isTrue()
        controller.get().onBackPressedDispatcher.onBackPressed()
        controller.tap("reports")
        assertThat(controller.asksForAPicture("six-pages.pdf")).isFalse()
    }

    /** A photo under a password gets its picture once the password is known, and not before. */
    @Test
    fun aPhotoUnderAPasswordGetsAPictureOnceItIsUnlocked() {
        val controller = locked()
        assertThat(controller.asksForAPicture("aes256.png")).isFalse()
        controller.tap("zipcrypto.txt")
        passwordBox().first.type("gander")
        assertThat(controller.asksForAPicture("aes256.png")).isTrue()
    }

    // ---------------------------------------------------------------
    // The code page names are read in

    /**
     * Cancel means the file stays shut, even when the password typed before it was right and was
     * still being tried: the answer came back after the box had gone and opened the file anyway,
     * and kept the password for the rest of the archive.
     */
    @Test
    fun cancellingThePasswordBoxWhileItIsTriedOpensNothing() {
        val held = ArrayList<Runnable>()
        var holding = false
        val controller = zip(loader = Executor { if (holding) held += it else it.run() })
        controller.tap("private")
        controller.tap("locked.txt")
        val (box, field) = passwordBox()
        holding = true
        field.setText("gander")
        box.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        box.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        assertThat(held).isNotEmpty()
        held.forEach { it.run() }
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
        assertThat(ArchivePasswords.get(FixtureProvider.uriFor("archive.zip"))).isNull()
    }

    /**
     * A reading of the names that fails, the file gone or its grant lapsed, leaves the list saying
     * so and nothing of the reading before it: Back went up into the old list, over the message.
     */
    @Test
    fun aReadingThatFailsLeavesNothingOfTheOldListBehind() {
        val copy = File.createTempFile("vanishing", ".zip").apply {
            writeBytes(Fixtures.bytes("names-gbk.zip"))
            deleteOnExit()
        }
        val uri = FixtureProvider.install().add("vanishing.zip", copy)
        val controller = zip(uri)
        controller.tap("季度报告")
        assertThat(copy.delete()).isTrue()

        controller.get().findViewById<MaterialToolbar>(R.id.toolbar).menu
            .performIdentifierAction(controller.encodingItem().itemId, 0)
        val box = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val choices = box.listView.adapter
        val big5 = (0 until choices.count).single { choices.getItem(it).toString() == context.getString(R.string.encoding_big5) }
        box.listView.performItemClick(null, big5, big5.toLong())
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(controller.titles()).containsExactly(context.getString(R.string.archive_unreadable))

        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(controller.titles()).containsExactly(context.getString(R.string.archive_unreadable))
        assertThat(controller.get().isFinishing).isTrue()
    }

    // ---------------------------------------------------------------

    private fun ActivityController<ViewerActivity>.encodingItem(): android.view.MenuItem =
        get().findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(R.id.action_name_encoding)

    /** Offered only where names needed a guess, since everywhere else it would change nothing. */
    @Test
    fun theNameEncodingIsOfferedOnlyWhereNamesNeededAGuess() {
        assertThat(zip().encodingItem().isVisible).isFalse()
        assertThat(zip(FixtureProvider.uriFor("names-gbk.zip")).encodingItem().isVisible).isTrue()
    }

    /** Choosing a code page by hand reads every name again in it, and says what the guess was. */
    @Test
    fun choosingACodePageReadsTheNamesAgain() {
        val controller = zip(FixtureProvider.uriFor("names-gbk.zip"))
        assertThat(controller.titles()).containsExactly("季度报告", "照片").inOrder()
        controller.encodingItem().let {
            controller.get().findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(it.itemId, 0)
        }
        val box = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val choices = box.listView.adapter
        assertThat(choices.getItem(0).toString()).isEqualTo(
            context.getString(R.string.name_encoding_automatic_as, context.getString(R.string.encoding_gbk))
        )
        val big5 = (0 until choices.count).single { choices.getItem(it).toString() == context.getString(R.string.encoding_big5) }
        box.listView.performItemClick(null, big5, big5.toLong())
        shadowOf(Looper.getMainLooper()).idle()

        val raw = "季度报告".toByteArray(charset("GBK"))
        assertThat(controller.titles()).contains(String(raw, charset("Big5")))
    }

    /**
     * The list waits on the bar Save a copy reports on, and leaves it alone while a save is
     * using it: reading the names again used to hide it partway through a save.
     */
    @Test
    fun aZipsListLeavesTheBarToASaveUnderWay() {
        val controller = zip(FixtureProvider.uriFor("names-gbk.zip"))
        val activity = controller.get()
        val bar = activity.findViewById<LinearProgressIndicator>(R.id.saveProgress)
        // As Save a copy leaves it partway through
        activity.copier.saving = true
        bar.isIndeterminate = false
        bar.visibility = android.view.View.VISIBLE
        activity.findViewById<MaterialToolbar>(R.id.toolbar).menu
            .performIdentifierAction(controller.encodingItem().itemId, 0)
        val box = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val choices = box.listView.adapter
        val big5 = (0 until choices.count).single { choices.getItem(it).toString() == context.getString(R.string.encoding_big5) }
        box.listView.performItemClick(null, big5, big5.toLong())
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(bar.visibility).isEqualTo(android.view.View.VISIBLE)
        assertThat(bar.isIndeterminate).isFalse()
    }

    /** And the choice survives a recreation, which reads the index again. */
    @Test
    fun aChosenCodePageSurvivesARecreation() {
        val first = zip(FixtureProvider.uriFor("names-gbk.zip"))
        first.encodingItem().let {
            first.get().findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(it.itemId, 0)
        }
        val box = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val big5 = (0 until box.listView.adapter.count)
            .single { box.listView.adapter.getItem(it).toString() == context.getString(R.string.encoding_big5) }
        box.listView.performItemClick(null, big5, big5.toLong())
        shadowOf(Looper.getMainLooper()).idle()
        val titles = first.titles()
        val state = Bundle()
        first.saveInstanceState(state)

        val second = zip(FixtureProvider.uriFor("names-gbk.zip"), state)
        assertThat(second.titles()).isEqualTo(titles)
    }

    /** A change of theme recreates the viewer, and the reader stays in the folder they were in. */
    @Test
    fun theFolderOnScreenSurvivesARecreation() {
        val first = zip()
        first.tap("reports")
        val state = Bundle()
        first.saveInstanceState(state)

        val second = zip(state = state)
        assertThat(second.titles()).containsExactly("notes.md", "six-pages.pdf").inOrder()
        assertThat(second.toolbarTitle()).isEqualTo("reports")
    }

    @Test
    fun whatIsNotReallyAZipSaysSo() {
        val provider = FixtureProvider.install()
        val uri = provider.add("damaged.zip", Fixtures.file("plain.txt"))
        assertThat(zip(uri).titles())
            .containsExactly(context.getString(R.string.archive_unreadable))
    }

    /** An archive too big to list says so, in place of a list or a crash. */
    @Test
    fun aZipTooLargeToListSaysSo() {
        val bytes = Fixtures.bytes("odd-names.zip")
        val end = (bytes.size - 22 downTo 0).first { at ->
            bytes[at] == 0x50.toByte() && bytes[at + 1] == 0x4B.toByte() &&
                bytes[at + 2] == 0x05.toByte() && bytes[at + 3] == 0x06.toByte()
        }
        // The size of the index, claimed at 40 MB
        val claimed = 40 * 1024 * 1024
        for (i in 0 until 4) bytes[end + 12 + i] = (claimed ushr (8 * i)).toByte()
        val file = File.createTempFile("huge-index", ".zip").apply { writeBytes(bytes); deleteOnExit() }
        val uri = FixtureProvider.install().add("huge-index.zip", file)
        assertThat(zip(uri).titles())
            .containsExactly(context.getString(R.string.archive_too_large))
    }

    /**
     * ArchiveProvider reads whatever archive its URI names, with Gander's access, so one of its
     * URIs arriving from another app is turned away rather than shown.
     */
    @Test
    fun aFileInsideAZipIsRefusedFromAnywhereButGandersOwnList() {
        val entry = ArchiveProvider.uriFor(
            context,
            FixtureProvider.uriFor("archive.zip"),
            ArchiveEntry("plain.txt", false, 0, EntryLocation(0, 8, 1, 1, 0)),
        )
        val controller = view(entry, "text/plain")
        assertThat(controller.get().isFinishing).isTrue()
    }

    /**
     * Android takes "0@" in front of an authority for the same provider, for the phone's own
     * user, so the gate goes by host. Checked by authority, this spelling got past it, and any
     * app could have Gander show a file from any zip Gander can read.
     */
    @Test
    fun aFileInsideAZipIsRefusedHoweverItsAuthorityIsSpelled() {
        val entry = ArchiveProvider.uriFor(
            context,
            FixtureProvider.uriFor("archive.zip"),
            ArchiveEntry("plain.txt", false, 0, EntryLocation(0, 8, 1, 1, 0)),
        )
        val spelled = entry.buildUpon().encodedAuthority("0@" + entry.encodedAuthority).build()
        assertThat(spelled.authority).isNotEqualTo(ArchiveProvider.authority(context))
        val controller = view(spelled, "text/plain")
        assertThat(controller.get().isFinishing).isTrue()
    }

    /**
     * The same with the user's number and its @ written as %40. Android reads the host from the
     * authority as written, where there is no @ to split at, and finds the provider from the
     * authority decoded, where there is: a gate that compared the host let the URI through to
     * the exported viewer, which then read the archive it named with Gander's own access.
     */
    @Test
    fun aFileInsideAZipIsRefusedWithItsAtSignEncodedToo() {
        val entry = ArchiveProvider.uriFor(
            context,
            FixtureProvider.uriFor("archive.zip"),
            ArchiveEntry("plain.txt", false, 0, EntryLocation(0, 8, 1, 1, 0)),
        )
        val spelled = entry.buildUpon().encodedAuthority("0%40" + entry.encodedAuthority).build()
        assertThat(spelled.host).isNotEqualTo(ArchiveProvider.authority(context))
        assertThat(spelled.authority).isEqualTo("0@" + ArchiveProvider.authority(context))
        val controller = view(spelled, "text/plain")
        assertThat(controller.get().isFinishing).isTrue()
    }

    /**
     * Share from a file in a zip leaves Gander out of the list, as the home screen's share does.
     * Picked, Gander would turn the file away at the gate above without a word.
     */
    @Test
    fun shareLeavesGanderOutOfTheList() {
        val entry = ArchiveProvider.uriFor(
            context,
            FixtureProvider.uriFor("archive.zip"),
            ArchiveEntry("plain.txt", false, 0, EntryLocation(0, 8, 1, 1, 0)),
        )
        val intent = Intent()
            .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
            .setData(entry)
        val activity = start(intent).get()
        activity.findViewById<MaterialToolbar>(R.id.toolbar).menu
            .performIdentifierAction(R.id.action_share, 0)
        val chooser = shadowOf(activity).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS).orEmpty()
        assertThat(excluded.toList()).contains(ComponentName(context, ViewerActivity::class.java))
    }

    /**
     * A stored file in a zip is served as a window onto the archive, and a window is what
     * openFileDescriptor refuses outright. A large PDF asks for ranges, so the range server
     * has to take the window's own offset into account, or every piece comes back not found
     * and pdf.js reports the document broken.
     */
    @Test
    fun aLargeStoredFileInAZipIsServedInRangesFromItsOwnBytes() {
        val size = (RANGE_THRESHOLD_BYTES + 1).toInt()
        val body = ByteArray(size) { (it % 251).toByte() }
        val file = File.createTempFile("ranged", ".zip").apply { deleteOnExit() }
        java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
            // Something ahead of it, so the file does not start where the archive does
            z.putNextEntry(java.util.zip.ZipEntry("first.txt"))
            z.write("first".toByteArray())
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("big.pdf").apply {
                method = java.util.zip.ZipEntry.STORED
                this.size = body.size.toLong()
                compressedSize = body.size.toLong()
                crc = java.util.zip.CRC32().apply { update(body) }.value
            })
            z.write(body)
            z.closeEntry()
        }
        val archive = FixtureProvider.install().add("ranged.zip", file)
        Robolectric.buildContentProvider(ArchiveProvider::class.java)
            .create(ArchiveProvider.authority(context))
        val raf = java.io.RandomAccessFile(file, "r")
        val entry = ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, java.util.Locale.US).single { e -> e.path == "big.pdf" }
        }
        val intent = Intent()
            .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
            .setData(ArchiveProvider.uriFor(context, archive, entry))
        val controller = start(intent)

        assertThat(controller.loadedUrl()).contains("ranged=1")
        val response = controller.serve("/doc/file.pdf", "bytes=100-199")!!
        assertThat(response.statusCode).isEqualTo(206)
        assertThat(response.responseHeaders["Content-Range"]).isEqualTo("bytes 100-199/$size")
        assertThat(response.data.readBytes()).isEqualTo(body.copyOfRange(100, 200))
    }

    /**
     * A PDF inside a zip keeps no reading position. Positions are filed on the phone under a
     * fingerprint of the file, and nothing in a zip is written to the phone, so the fingerprint
     * is never taken. Seeded with a page under exactly the one the viewer would take, which a
     * viewer that still looked would open on.
     */
    @Test
    fun aPdfInsideAZipKeepsNoReadingPosition() {
        val uri = pdfInAZip()
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { it.length }
        Positions.save(context, Positions.keyFor(context.contentResolver, uri, length)!!, 4, 6)

        val intent = Intent()
            .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
            .setData(uri)
        val controller = start(intent)
        assertThat(controller.loadedUrl()).contains("viewer/pdf.html")
        assertThat(controller.loadedUrl()).doesNotContain("resume=")
    }

    /**
     * six-pages.pdf in a zip of its own, [stored] as it is or compressed, as Gander's URI for it
     * inside the zip.
     */
    private fun pdfInAZip(stored: Boolean = true): Uri {
        val pdf = Fixtures.bytes("six-pages.pdf")
        val file = File.createTempFile("pdf", ".zip").apply { deleteOnExit() }
        java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("six-pages.pdf").apply {
                if (stored) {
                    method = java.util.zip.ZipEntry.STORED
                    this.size = pdf.size.toLong()
                    compressedSize = pdf.size.toLong()
                    crc = java.util.zip.CRC32().apply { update(pdf) }.value
                }
            })
            z.write(pdf)
            z.closeEntry()
        }
        val archive = FixtureProvider.install().add(if (stored) "stored.zip" else "deflated.zip", file)
        Robolectric.buildContentProvider(ArchiveProvider::class.java)
            .create(ArchiveProvider.authority(context))
        val raf = java.io.RandomAccessFile(file, "r")
        val entry = ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, java.util.Locale.US).single()
        }
        return ArchiveProvider.uriFor(context, archive, entry)
    }

    /**
     * The page is told the document's length on its URL whenever the provider says, and not
     * otherwise. It cannot go by the Content-Length it is served with: the WebView sends one
     * of its own, and for a file coming out of a zip through a pipe that one says 0. Seen on
     * the emulator, where a model inside a zip opened as an empty one. The same bracket,
     * stored and compressed, is one of each.
     */
    @Test
    fun thePageIsToldTheLengthOnlyWhenTheProviderKnowsIt() {
        val stl = Fixtures.bytes("bracket.stl")
        assertThat(open("bracket.stl").loadedUrl()).contains("&length=${stl.size}&")

        val file = File.createTempFile("models", ".zip").apply { deleteOnExit() }
        java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("stored.stl").apply {
                method = java.util.zip.ZipEntry.STORED
                size = stl.size.toLong()
                compressedSize = stl.size.toLong()
                crc = java.util.zip.CRC32().apply { update(stl) }.value
            })
            z.write(stl)
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("deflated.stl"))
            z.write(stl)
            z.closeEntry()
        }
        val archive = FixtureProvider.install().add("models.zip", file)
        // Robolectric's pipe is a file, and would give as its length whatever the inflating
        // thread had written by then. Held until the URL is read, the compressed one says
        // nothing, as a real pipe does.
        val held = mutableListOf<Runnable>()
        Robolectric.buildContentProvider(ArchiveProvider::class.java)
            .create(ArchiveProvider.authority(context))
            .get().writers = java.util.concurrent.Executor { held += it }
        val raf = java.io.RandomAccessFile(file, "r")
        val entries = ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, java.util.Locale.US)
        }
        fun urlOf(name: String): String = start(
            Intent()
                .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
                .setData(ArchiveProvider.uriFor(context, archive, entries.single { it.path == name }))
        ).loadedUrl()

        assertThat(urlOf("stored.stl")).contains("viewer/model.html")
        assertThat(urlOf("stored.stl")).contains("&length=${stl.size}&")
        assertThat(urlOf("deflated.stl")).contains("viewer/model.html")
        assertThat(urlOf("deflated.stl")).doesNotContain("length=")
        held.forEach { it.run() }
    }

    @Test
    fun aPdfOpensAtThePageItWasLeftAt() {
        val uri = FixtureProvider.uriFor("six-pages.pdf")
        val length = Fixtures.file("six-pages.pdf").length()
        Positions.save(context, Positions.keyFor(context.contentResolver, uri, length)!!, 4, 6)
        assertThat(view(uri).loadedUrl()).contains("&resume=4")
    }

    /**
     * A provider that has to fetch a file before it can hand it over, as a cloud one does,
     * answers when it has it. The reading position is looked up in the file's first bytes,
     * and looked up on the main thread it held the viewer still, and short of its first
     * frame, for as long as that took: a whole download, for a large PDF on a slow line.
     */
    @Test
    fun aProviderSlowToHandAPdfOverDoesNotHoldTheViewerUp() {
        val uri = FixtureProvider.uriFor(FixtureProvider.SLOW)
        val length = Fixtures.file("six-pages.pdf").length()
        Positions.save(context, Positions.keyFor(context.contentResolver, uri, length)!!, 3, 6)
        FixtureProvider.gate = java.util.concurrent.CountDownLatch(1)
        // Handed over in fifteen seconds whatever happens, so a viewer that waits for it on
        // the main thread comes back late rather than never
        Thread { Thread.sleep(15_000); FixtureProvider.gate.countDown() }.apply { isDaemon = true }.start()
        try {
            val intent = Intent(context, ViewerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
            val started = System.nanoTime()
            val controller = closing.build(ViewerActivity::class.java, intent).setup()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertWithMessage("the viewer waited for the provider on the main thread")
                .that(tookMs).isLessThan(10_000L)

            // And the page loads, at its page, once the provider answers
            FixtureProvider.gate.countDown()
            val deadline = System.currentTimeMillis() + 10_000
            while (shadowOf(controller.webView()!!).lastLoadedUrl == null &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertThat(controller.loadedUrl()).contains("&resume=3")
        } finally {
            FixtureProvider.gate.countDown()
        }
    }

    /** The same for a photo, whose rotation is read from the file's first bytes. */
    @Test
    fun aProviderSlowToHandAPhotoOverDoesNotHoldTheViewerUp() {
        val gate = java.util.concurrent.CountDownLatch(1)
        FixtureProvider.gate = gate
        Thread { Thread.sleep(15_000); gate.countDown() }.apply { isDaemon = true }.start()
        try {
            val intent = Intent(context, ViewerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(FixtureProvider.uriFor(FixtureProvider.SLOW_PHOTO), "image/jpeg")
            val started = System.nanoTime()
            val controller = closing.build(ViewerActivity::class.java, intent).setup()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertWithMessage("the viewer waited for the provider on the main thread")
                .that(tookMs).isLessThan(10_000L)

            // And the photo is turned the right way up once the provider answers
            gate.countDown()
            val deadline = System.currentTimeMillis() + 10_000
            var photo: SubsamplingScaleImageView? = null
            while (photo == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
                photo = controller.container().children().filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
            }
            assertThat(photo?.orientation).isEqualTo(90)
        } finally {
            gate.countDown()
        }
    }

    /**
     * A zip's list is drawn with the home screen's rows, and leaves for a viewer the same way, so
     * it clears a tap highlight on the way out as the home screen does: a back swipe shows the
     * frame drawn as the list left, and the file just opened came back lit.
     */
    @Test
    fun aZipsRowsAreNotLeftLitWhenAFileOpens() {
        val controller = zip()
        // The rows the list has laid out in the window, not ones bound for reading, which the
        // window never reaches
        val list = controller.list()
        list.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 1080, 1920)
        val rows = (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isClickable }
        assertThat(rows).isNotEmpty()
        rows.forEach { it.isPressed = true }

        controller.pause()

        assertThat(rows.filter { it.isPressed }).isEmpty()
    }

    @Test
    fun theEntryAliasIsLetThrough() {
        val entry = ArchiveProvider.uriFor(
            context,
            FixtureProvider.uriFor("archive.zip"),
            ArchiveEntry("plain.txt", false, 0, EntryLocation(0, 8, 1, 1, 0)),
        )
        val intent = Intent()
            .setComponent(ComponentName(context, ViewerActivity.INTERNAL_VIEWER))
            .setData(entry)
        val controller = start(intent)
        assertThat(controller.get().isFinishing).isFalse()
        assertThat(controller.loadedUrl()).contains("viewer/text.html")
    }
}
