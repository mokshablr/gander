package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.common.truth.Truth.assertThat
import kotlin.math.pow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The viewer around a PDF in night mode, on a phone set to light and on one set to dark.
 *
 * The WebView is Robolectric's shadow and cannot open a message channel, so the night mode item
 * stays hidden here and the switch is driven through [NightChrome] itself. Opening dark is tested
 * through the viewer, from the stored setting, the way a real open reads it.
 */
@RunWith(AndroidJUnit4::class)
class NightChromeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
        Settings.setNight(context, false)
    }

    @After
    fun tearDown() {
        Settings.setNight(context, false)
    }

    private fun open(fixture: String): ViewerActivity {
        val uri = FixtureProvider.uriFor(fixture)
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
    }

    /** The app's colours as a phone set to dark has them. */
    private val night: Context by lazy {
        val conf = Configuration(context.resources.configuration)
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_YES
        context.createConfigurationContext(conf)
    }

    private fun Context.color(id: Int) = ContextCompat.getColor(this, id)

    /**
     * Text as a phone set to dark draws it. Not gander_on_surface: Material's night theme names
     * its own grey for android:textColorPrimary rather than pointing it at the role, so that is
     * what the toolbar's title and the search box already show on a dark phone.
     */
    private val nightText: Int by lazy { textColour(ContextThemeWrapper(night, R.style.Theme_Gander)) }

    private fun textColour(themed: Context): Int {
        val values = themed.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        try {
            return values.getColorStateList(0)!!.defaultColor
        } finally {
            values.recycle()
        }
    }

    /** Each part NightChrome paints, as the colour a reader sees it in. */
    private data class Parts(
        val toolbar: Int?,
        val title: Int,
        val behindTheBars: Int?,
        val darkStatusIcons: Boolean,
        val searchBar: Int,
        val searchText: Int,
        val searchCount: Int,
        val menu: Int,
        val menuText: Int,
    )

    private fun ViewerActivity.parts(): Parts {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val title = toolbar.children.filterIsInstance<TextView>().first { it.text == toolbar.title }
        // What shows around the root's padding: its own background, or the window's through it
        val root = findViewById<View>(R.id.root)
        val behind = (root.background as? ColorDrawable)?.color
            ?: (window.decorView.background as? ColorDrawable)?.color
        return Parts(
            toolbar = (toolbar.background as? MaterialShapeDrawable)?.fillColor?.defaultColor,
            title = title.currentTextColor,
            behindTheBars = behind,
            darkStatusIcons = WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars,
            searchBar = (findViewById<View>(R.id.searchBar).background as ColorDrawable).color,
            searchText = findViewById<TextView>(R.id.searchInput).currentTextColor,
            searchCount = findViewById<TextView>(R.id.searchCount).currentTextColor,
            menu = MaterialColors.getColor(
                toolbar.context, com.google.android.material.R.attr.colorSurfaceContainer, "menu"
            ),
            menuText = textColour(toolbar.context),
        )
    }

    @Test
    fun aPdfOpenedInNightModeOpensWithTheViewerDark() {
        Settings.setNight(context, true)
        val parts = open("six-pages.pdf").parts()

        // The frame is black, as the page is: issue #41
        assertThat(parts.toolbar).isEqualTo(Color.BLACK)
        assertThat(parts.title).isEqualTo(nightText)
        assertThat(parts.behindTheBars).isEqualTo(Color.BLACK)
        assertThat(parts.darkStatusIcons).isFalse()
        assertThat(parts.searchBar).isEqualTo(Color.BLACK)
        assertThat(parts.searchText).isEqualTo(nightText)
        assertThat(parts.searchCount).isEqualTo(night.color(R.color.gander_on_surface_variant))
        // The menu's roles come from the five named night colours, so this also holds them
        // to the night palette they were copied out of
        assertThat(parts.menu).isEqualTo(night.color(R.color.gander_surface_container))
        assertThat(parts.menuText).isEqualTo(nightText)
    }

    @Test
    fun withNightModeOffAPdfOpensAsThePhoneHasIt() {
        val parts = open("six-pages.pdf").parts()

        assertThat(parts.toolbar).isEqualTo(context.color(R.color.gander_surface))
        assertThat(parts.behindTheBars).isEqualTo(context.color(R.color.gander_surface))
        assertThat(parts.darkStatusIcons).isTrue()
        assertThat(parts.searchBar).isEqualTo(context.color(R.color.gander_surface))
        assertThat(parts.menu).isEqualTo(context.color(R.color.gander_surface_container))
    }

    /** Word turns over as well since issue #47, so the frame goes dark with it. */
    @Test
    fun aWordDocumentOpenedInNightModeOpensWithTheViewerDark() {
        Settings.setNight(context, true)
        val parts = open("report.docx").parts()

        assertThat(parts.toolbar).isEqualTo(Color.BLACK)
        assertThat(parts.behindTheBars).isEqualTo(Color.BLACK)
        assertThat(parts.searchBar).isEqualTo(Color.BLACK)
        assertThat(parts.menu).isEqualTo(night.color(R.color.gander_surface_container))
    }

    @Test
    fun nightModeLeavesTheOtherFormatsAsThePhoneHasThem() {
        Settings.setNight(context, true)
        for (fixture in listOf("plain.txt", "letter.odt", "notes.md", "tiny.png")) {
            val parts = open(fixture).parts()
            assertThat(parts.behindTheBars).isEqualTo(context.color(R.color.gander_surface))
            assertThat(parts.searchBar).isEqualTo(context.color(R.color.gander_surface))
            assertThat(parts.menu).isEqualTo(context.color(R.color.gander_surface_container))
        }
    }

    @Test
    fun turningItOffPutsEveryPartBackAsItWas() {
        val viewer = open("six-pages.pdf")
        val asPhone = viewer.parts()
        val chrome = NightChrome(viewer)

        chrome.show(true)
        val atNight = viewer.parts()
        chrome.show(false)

        assertThat(atNight.searchBar).isNotEqualTo(asPhone.searchBar)
        assertThat(viewer.parts()).isEqualTo(asPhone)
    }

    /** The night theme's warm near-black left a black page inside a lighter frame, issue #41. */
    @Test
    @Config(qualifiers = "night")
    fun onAPhoneSetToDarkNightModeTurnsOnlyTheFrameBlack() {
        val viewer = open("six-pages.pdf")
        val asPhone = viewer.parts()
        val chrome = NightChrome(viewer)

        chrome.show(true)
        val atNight = viewer.parts()

        assertThat(asPhone.toolbar).isNotEqualTo(Color.BLACK)
        assertThat(atNight).isEqualTo(
            asPhone.copy(toolbar = Color.BLACK, behindTheBars = Color.BLACK, searchBar = Color.BLACK)
        )
        chrome.show(false)
        assertThat(viewer.parts()).isEqualTo(asPhone)
    }

    /** WCAG 2.1 contrast between two opaque colours. */
    private fun contrast(a: Int, b: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        fun luminance(c: Int) =
            0.2126 * channel(Color.red(c)) + 0.7152 * channel(Color.green(c)) + 0.0722 * channel(Color.blue(c))
        val (lighter, darker) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Selected text against its highlight, laid over what is behind the box. */
    private fun EditText.selectedTextContrast(behind: Int) =
        contrast(currentTextColor, ColorUtils.compositeColors(highlightColor, behind))

    // Left to Material, the highlight is the on-primary-container role at 60%, which in Gander's
    // light palette is a near-black brown, and the text it covered measured 3.5:1
    @Test
    fun selectedTextInTheSearchBoxStaysReadable() {
        val box = open("six-pages.pdf").findViewById<EditText>(R.id.searchInput)
        assertThat(box.selectedTextContrast(context.color(R.color.gander_surface))).isAtLeast(4.5)
    }

    // Material's own highlight for a dark theme left it at 3.1:1
    @Test
    @Config(qualifiers = "night")
    fun onAPhoneSetToDarkSelectedTextInTheSearchBoxStaysReadable() {
        val box = open("six-pages.pdf").findViewById<EditText>(R.id.searchInput)
        assertThat(box.selectedTextContrast(context.color(R.color.gander_surface))).isAtLeast(4.5)
    }

    @Test
    fun dialogsOverAPageInNightModeAreBuiltOnTheNightTheme() {
        val viewer = open("six-pages.pdf")
        val chrome = NightChrome(viewer)
        assertThat(chrome.dialogs).isSameInstanceAs(viewer)

        chrome.show(true)

        val surface = MaterialColors.getColor(
            chrome.dialogs, com.google.android.material.R.attr.colorSurface, "dialog"
        )
        assertThat(surface).isEqualTo(night.color(R.color.gander_surface))
    }

    // ---------------------------------------------------------------
    // Dialogs, opened one after another as a reader opens them
    // ---------------------------------------------------------------

    /** A dialog as a reader sees it: the box, its title, and the words in its field if it has one. */
    private data class Seen(val box: Int?, val title: Int, val field: Int?)

    /** A dialog as a phone set to dark draws it. Read before any dialog is opened. */
    private fun asDarkPhone() = Seen(
        box = night.color(R.color.gander_surface_container_high),
        title = night.color(R.color.gander_on_surface),
        field = nightText,
    )

    /** Go to page, the box a PDF has. Only a real WebView says how many pages there are. */
    private fun ViewerActivity.openGoToPage(): AlertDialog {
        pageTotal = 6
        findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_go_to_page, 0)
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun AlertDialog.field(): EditText? {
        fun find(v: View): EditText? = when (v) {
            is EditText -> v
            is ViewGroup -> v.children.firstNotNullOfOrNull { find(it) }
            else -> null
        }
        return find(window!!.decorView)
    }

    private fun AlertDialog.seen(): Seen {
        // Material draws the box as a shape inside an inset, and sets that as the window's background
        val box = (window!!.decorView.background as? InsetDrawable)?.drawable as? MaterialShapeDrawable
        return Seen(
            box = box?.fillColor?.defaultColor,
            title = findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.currentTextColor,
            field = field()?.currentTextColor,
        )
    }

    private fun AlertDialog.close() {
        cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** What the tests below hold night mode to. */
    @Test
    @Config(qualifiers = "night")
    fun onAPhoneSetToDarkGoToPageIsDark() {
        val dark = asDarkPhone()
        assertThat(open("six-pages.pdf").openGoToPage().seen()).isEqualTo(dark)
    }

    @Test
    fun withNightModeOffGoToPageIsAsThePhoneHasIt() {
        val light = Seen(
            box = context.color(R.color.gander_surface_container_high),
            title = context.color(R.color.gander_on_surface),
            field = textColour(ContextThemeWrapper(context, R.style.Theme_Gander)),
        )
        assertThat(open("six-pages.pdf").openGoToPage().seen()).isEqualTo(light)
    }

    /**
     * The first came out dark under a dim title and every one after it light, the field light on
     * light, because making a dialog put the night theme's resources back to day.
     */
    @Test
    fun everyDialogOverAPageInNightModeIsDarkNotOnlyTheFirst() {
        Settings.setNight(context, true)
        val dark = asDarkPhone()
        val viewer = open("six-pages.pdf")

        repeat(3) {
            val box = viewer.openGoToPage()
            assertThat(box.seen()).isEqualTo(dark)
            box.close()
        }
        // And none of it reached the viewer's own resources, which stay the phone's
        assertThat(viewer.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            .isEqualTo(Configuration.UI_MODE_NIGHT_NO)
    }

    /** A box made and shown in one call, as a question asked over another would be, is dark as well. */
    @Test
    fun aBoxShownInOneCallIsDarkToo() {
        Settings.setNight(context, true)
        val dark = asDarkPhone()
        val chrome = NightChrome(open("six-pages.pdf"))
        chrome.show(true)

        val box = DialogBuilder(chrome.dialogs).setTitle("Title").setMessage("Message").show()

        assertThat(box.seen()).isEqualTo(dark.copy(field = null))
    }

    /** The night theme's resources are shared across the process, so a viewer opened later read them too. */
    @Test
    fun aDialogAtNightLeavesTheNextViewerDarkToo() {
        Settings.setNight(context, true)
        val before = open("six-pages.pdf").parts()
        val first = open("six-pages.pdf")
        first.openGoToPage().close()

        assertThat(open("six-pages.pdf").parts()).isEqualTo(before)
        // Still open, as it was on the phone, holding the resources it shares with the one above
        assertThat(first.isDestroyed).isFalse()
    }
}
