package com.arjun.gander

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One document of every format, opened end to end on a device.
 *
 * Deliberately shallow: each of these renders a real file with the real
 * library inside the real WebView, and asserts that something recognisable
 * from the document came out. What each renderer does in detail is covered
 * far more cheaply in tests/viewer.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ViewerFormatsTest {

    @get:Rule
    val retry = RetryRule()

    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    private fun open(fixture: String) =
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent(fixture))

    private fun expect(fixture: String, selector: String, text: String) {
        open(fixture).use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, selector))
                .check(webMatches(getText(), containsString(text)))
        }
    }

    @Test
    fun aWordDocumentRenders() =
        expect("report.docx", "#container", "Field Survey")

    @Test
    fun aSpreadsheetRenders() =
        expect("budget.xlsx", "#sheet", "Surveying")

    @Test
    fun aCsvRendersAsASpreadsheetRatherThanText() =
        expect("budget.csv", "#sheet", "Surveying")

    @Test
    fun aSlideDeckRenders() =
        expect("deck.pptx", "#result", "Willowmere")

    @Test
    fun markdownRenders() =
        expect("notes.md", "#content", "Willowmere site notes")

    @Test
    fun plainTextRenders() =
        expect("plain.txt", "#content", "Plain text")

    /**
     * The space after the colon is only there if the formatter ran: the fixture
     * is written with no spacing at all. Worth a device test as well as the
     * browser ones for the same reason the sanitiser below is, that the WebView
     * is where it actually runs.
     */
    @Test
    fun aJsonFileIsLaidOutOnTheDevice() =
        expect("snapshot.json", "#content", "\"app\": \"com.example.reader\"")

    /**
     * Markdown is untrusted input and DOMPurify is what stands between it and
     * the DOM. Worth one device test as well as the browser one, because the
     * WebView is where it would actually matter.
     */
    @Test
    fun markdownIsSanitisedOnTheDevice() {
        open("notes.md").use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#content"))
                .check(webMatches(getText(), not(containsString("window.__xss"))))
        }
    }

    /** A file of no known format whose bytes are not text gets the card, and its offer. */
    @Test
    fun anUnknownFormatThatIsNotTextOffersToShowItAsText() {
        open("opaque.xyz").use {
            onWebView()
                .withElement(findElement(Locator.ID, "asText"))
                .check(webMatches(getText(), containsString("View as text")))
        }
    }

    /** One that reads as text opens as text with no card to tap through, and says so under its name. Issue #51. */
    @Test
    fun anUnknownFormatThatReadsAsTextOpensAsText() {
        open("unknown.xyz").use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#content"))
                .check(webMatches(getText(), containsString("0123456789")))
            onView(withText(R.string.unknown_shown_as_text)).check(matches(isDisplayed()))
        }
    }

    // The three formats read by Gander's own code rather than a library, on the
    // one page that asks the file's bytes which it is

    @Test
    fun aWord97DocumentRenders() =
        expect("legacy.doc", "#container", "Field Survey")

    // Word's single-file XML under a .doc name, which the app sends to docx.html instead
    @Test
    fun aWordXmlPackageDocRenders() =
        expect("word-package.doc", "#container", "Willowmere package marker")

    @Test
    fun anOpenDocumentTextRenders() =
        expect("letter.odt", "#container", "Field Survey")

    @Test
    fun aRichTextFileRenders() =
        expect("memo.rtf", "#container", "Field Survey")

    // A book's chapter is drawn in a shadow tree, which this cannot see into, so it reads
    // the strip above it: that names the chapter once the zip, the book's list of chapters
    // and its contents have all been read.
    @Test
    fun aBookOpensAtItsFirstChapter() {
        unread("harbour.epub")
        expect("harbour.epub", "#epub-where", "Title page")
    }

    /**
     * Forgets where a book was left, so it opens as one never read. BookDeviceTest leaves this
     * one at chapter 2, and Gander reopens a book where it was left, across installs too.
     */
    private fun unread(fixture: String) {
        val uri = DeviceFixtures.uriFor(fixture)
        val resolver = target.contentResolver
        // Measured as the viewer measures it, or the key would be another book's
        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val key = checkNotNull(Positions.keyFor(resolver, uri, length)) { "$fixture could not be read" }
        // The top of the first chapter is not kept, so leaving the book there drops its line
        Positions.save(target, key, 1, 2, Positions.Place(0, 0))
    }

    /**
     * WebGL on the device's own graphics driver, which no browser test stands in for. The
     * page says what it drew, and the pixels are read back off the graphics card in the same
     * task as a fresh draw, since nothing keeps a finished frame any longer than that.
     */
    @Test
    fun aThreeDModelIsDrawn() {
        open("bracket.stl").use { scenario ->
            WebViewProbe.await(
                scenario,
                "document.getElementById('model').getAttribute('data-state') === 'drawn'",
                "the model to be drawn"
            )
            assertThat(WebViewProbe.text(scenario, "document.getElementById('size').textContent"))
                .isEqualTo("40 × 20 × 30 mm")
            val share = WebViewProbe.eval(scenario, MODEL_SHARE)?.toDoubleOrNull()
            assertThat(share).isNotNull()
            // Some of the screen and not all of it: a blank canvas is 0, a flooded one near 1
            assertThat(share!!).isGreaterThan(0.02)
            assertThat(share).isLessThan(0.9)
        }
    }

    private companion object {
        /** The share of the canvas that is not the ground, from every fourth pixel. */
        const val MODEL_SHARE = """(function () {
            vwModelDraw();
            var c = document.getElementById('model');
            var gl = c.getContext('webgl');
            var px = new Uint8Array(c.width * c.height * 4);
            gl.readPixels(0, 0, c.width, c.height, gl.RGBA, gl.UNSIGNED_BYTE, px);
            var g = vwModelGround.map(function (v) { return Math.round(v * 255); });
            var model = 0, seen = 0;
            for (var i = 0; i < px.length; i += 16) {
              seen++;
              if (Math.abs(px[i] - g[0]) + Math.abs(px[i + 1] - g[1]) +
                  Math.abs(px[i + 2] - g[2]) > 30) model++;
            }
            return model / seen;
        })()"""
    }
}
