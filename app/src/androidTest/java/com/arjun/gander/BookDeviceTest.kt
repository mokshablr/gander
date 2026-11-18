package com.arjun.gander

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A book on the device's own WebView, for the two things no browser test stands in for: the
 * text zoom that sizes its type, which is the WebView's and not the page's, and Back, which
 * is Android's. What the page does with each is in tests/viewer/test_epub.py.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BookDeviceTest {

    @get:Rule
    val retry = RetryRule()

    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The size the phone's owner reads at, which these tests change and must give back. */
    private var theirs = 100

    @Before
    fun setUp() {
        DeviceFixtures.clear()
        theirs = Settings.textSize(target)
        Settings.setTextSize(target, 100)
    }

    /**
     * Given back, and written through before this returns. Settings writes in the background,
     * and the process is killed as the last test ends, which left the size a test had set
     * as the owner's own.
     */
    @After
    fun tearDown() {
        Settings.setTextSize(target, theirs)
        target.getSharedPreferences("viewer", Context.MODE_PRIVATE).edit().commit()
    }

    private fun open(fixture: String) =
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent(fixture))

    private val opened = "!document.getElementById('epub-bar').hidden"

    /** The height of the letter epub.js watches, which grows and shrinks with the text zoom alone. */
    private val gauge = "document.getElementById('epub-gauge').getBoundingClientRect().height"

    private fun number(scenario: ActivityScenario<ViewerActivity>, js: String): Double =
        WebViewProbe.eval(scenario, js)!!.toDouble()

    /** By the item and not by a tap on the menu, since the title bar may have gone up out of sight. */
    private fun chooseFromMenu(scenario: ActivityScenario<ViewerActivity>, item: Int) {
        scenario.onActivity {
            it.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(item, 0)
        }
    }

    /**
     * A button of the text size box, clicked as the view and not by a tap aimed at it. Of two
     * of Espresso's taps on these, one after the other on a phone with its animations on, one
     * was seen not to land, and what is under test here is the size and not the tap.
     */
    private fun press(button: Int) {
        onView(withId(button)).inRoot(isDialog()).perform(object : ViewAction {
            override fun getConstraints() = isEnabled()
            override fun getDescription() = "click the view itself"
            override fun perform(uiController: UiController, view: View) {
                view.performClick()
                uiController.loopMainThreadUntilIdle()
            }
        })
    }

    /** Chapter [index] on the page and dressed, as epub.js leaves it once its pictures are in. */
    private fun goTo(scenario: ActivityScenario<ViewerActivity>, index: Int) {
        WebViewProbe.eval(scenario, "vwEpub.go($index, null), 1")
        WebViewProbe.await(
            scenario,
            "vwFindParts.shown() === $index && document.getElementById('book').shadowRoot" +
                ".querySelector('.vw-page').style.opacity !== '0'",
            "chapter $index to be on the page"
        )
    }

    /**
     * How far below the top of the screen, under whatever of the title bar is down, the line
     * is that holds letter [letter] of the chapter's text.
     */
    private fun lineBelowTop(letter: Int) = """
        (function () {
          var body = document.getElementById('book').shadowRoot.querySelector('body');
          var walk = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
          var before = 0;
          for (var n = walk.nextNode(); n; n = walk.nextNode()) {
            if ($letter < before + n.data.length) {
              var range = document.createRange();
              range.setStart(n, $letter - before);
              range.setEnd(n, $letter - before + 1);
              return range.getBoundingClientRect().top - Math.max(0, vwTopPx - vwBarGonePx);
            }
            before += n.data.length;
          }
          return -9999;
        })()
    """.trimIndent()

    @Test
    fun theSizeChosenFromTheMenuSizesTheTypeAndKeepsTheLineOnTop() {
        open("harbour.epub").use { scenario ->
            WebViewProbe.await(scenario, opened, "the book to open")
            goTo(scenario, 2)
            // To the second line of a paragraph well down the chapter, which then is the place
            val letter = number(
                scenario,
                """
                (function () {
                  var top = Math.max(0, vwTopPx - vwBarGonePx);
                  var body = document.getElementById('book').shadowRoot.querySelector('body');
                  var walk = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
                  var before = 0;
                  for (var n = walk.nextNode(); n; n = walk.nextNode()) {
                    var range = document.createRange();
                    range.selectNodeContents(n);
                    var lines = range.getClientRects();
                    if (before > 3000 && lines.length > 2 && lines[1].top > lines[0].top + 1) {
                      for (var k = 1; k < n.data.length; k++) {
                        range.setStart(n, k);
                        range.setEnd(n, k + 1);
                        var box = range.getBoundingClientRect();
                        if (box.height > 0 && box.top > lines[0].top + 1) {
                          window.scrollBy(0, box.top - top);
                          return before + k;
                        }
                      }
                    }
                    before += n.data.length;
                  }
                  return -1;
                })()
                """.trimIndent()
            ).toInt()
            assertThat(letter).isGreaterThan(3000)
            WebViewProbe.await(scenario, "Math.abs(${lineBelowTop(letter)}) < 2", "the line to be on top")
            // Long enough for the page to have worked out where it came to rest
            Thread.sleep(600)
            val before = number(scenario, gauge)

            chooseFromMenu(scenario, R.id.action_text_size)
            press(R.id.textLarger)
            press(R.id.textLarger)
            WebViewProbe.await(
                scenario, "Math.abs($gauge / $before - 1.3) < 0.03", "the type to be 130% of what it was"
            )
            WebViewProbe.await(
                scenario, "Math.abs(${lineBelowTop(letter)}) < 2", "the line to be on top at the larger size"
            )

            press(R.id.textSmaller)
            press(R.id.textSmaller)
            press(R.id.textSmaller)
            WebViewProbe.await(
                scenario, "Math.abs($gauge / $before - 0.9) < 0.03", "the type to be 90% of what it was"
            )
            WebViewProbe.await(
                scenario, "Math.abs(${lineBelowTop(letter)}) < 2", "the line to be on top at the smaller size"
            )
            assertThat(Settings.textSize(target)).isEqualTo(90)
        }
    }

    /** A page drawn at a fixed size keeps its type as the book set it, whatever the reader chose. */
    @Test
    fun aPageOfAFixedSizeIsNotSized() {
        Settings.setTextSize(target, 150)
        open("gazetteer.epub").use { scenario ->
            WebViewProbe.await(scenario, opened, "the book to open")
            val reflowing = number(scenario, gauge)
            goTo(scenario, 2)
            WebViewProbe.await(scenario, "$gauge < $reflowing / 1.2", "the type to go back to the book's own")
            goTo(scenario, 0)
            WebViewProbe.await(
                scenario, "Math.abs($gauge / $reflowing - 1) < 0.03", "the reader's size to come back"
            )
        }
    }

    /** The book's list is the app's, full screen, and Back puts it away and stays in the book. */
    @Test
    fun backClosesTheContentsAndStaysInTheBook() {
        open("harbour.epub").use { scenario ->
            WebViewProbe.await(scenario, opened, "the book to open")
            chooseFromMenu(scenario, R.id.action_contents)
            onView(withId(R.id.contents_list)).inRoot(isDialog()).check(matches(isDisplayed()))

            pressBack()
            onView(withId(R.id.contents_list)).check(doesNotExist())
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
    }
}
