package com.arjun.gander

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.forEach
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.MaterialShapeDrawable

/**
 * The viewer's own parts in the app's night colours while a PDF or Word document is in night
 * mode, whatever the phone is set to.
 *
 * Night mode, issue #19, turns the page over inside the WebView, and pdf.html darkens its own
 * cards for the reader night mode is for: one whose phone is set to light. Everything around the
 * page still followed the phone, so that reader had a black page under a paper toolbar, with a
 * paper strip behind the clock and, from Android 15, another behind the gesture bar, and a paper
 * search bar, menu and dialogs to open over it. Each of them gets the colours a phone set to dark
 * gives it, but for the ones that frame the page: the title bar, the search bar and the strips
 * behind the phone's bars are black, as the page is. In the night theme's warm near-black they
 * left a black page inside a lighter frame on an OLED screen, issue #41. So on a phone set to dark
 * those are all that change. The menu and the dialogs keep the night theme's surfaces, since they
 * stand over the page, and on black they would have no edge.
 *
 * Painted in place rather than by recreating the activity in the night theme. Recreating reloads
 * the document: it comes back at its page, but an encrypted PDF asks for its password again, and
 * the switch is otherwise instant. The price is that every part is named below, and a part added
 * to the viewer later has to be added here as well.
 */
internal class NightChrome(private val activity: AppCompatActivity) {

    /**
     * The app's theme under a night configuration, which is what a phone set to dark resolves
     * every colour role against. Dialogs are built on it whole, by a [DialogBuilder]; the parts
     * already on screen take their colours from it one at a time.
     */
    val nightContext: Context = ContextThemeWrapper(activity, R.style.Theme_Gander).apply {
        applyOverrideConfiguration(Configuration().apply {
            uiMode = (activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        })
    }

    // The menu's overlay goes on the toolbar's theme, which Material keeps as its own copy of the
    // activity's, so neither palette ever reads it
    private val asPhone = Palette(activity)
    private val atNight = Palette(nightContext)

    private val root: View = activity.findViewById(R.id.root)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.toolbar)
    private val searchBar: View = activity.findViewById(R.id.searchBar)
    private val searchInput: EditText = activity.findViewById(R.id.searchInput)
    private val searchCount: TextView = activity.findViewById(R.id.searchCount)
    private val searchButtons: List<ImageView> =
        listOf(R.id.searchPrev, R.id.searchNext, R.id.searchClose).map { activity.findViewById(it) }
    private val saveProgress: LinearProgressIndicator = activity.findViewById(R.id.saveProgress)

    private val rootAsPhone: Drawable? = root.background

    // On a phone set to dark every part is already in these colours, and the menu's overlay,
    // which borrows the light theme's inverse text, would turn its words dark
    private val phoneIsDark = (activity.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // MaterialToolbar turns its background colour into a shape tinted for its elevation. The
    // night one is a shape too, black and untinted, since a tint would lift it off the page.
    private val toolbarAsPhone: Drawable? = toolbar.background
    private val toolbarAtNight: Drawable by lazy {
        MaterialShapeDrawable().apply { fillColor = ColorStateList.valueOf(Color.BLACK) }
    }

    var on = false
        private set

    /** What a dialog opened over the document is built on, by a [DialogBuilder]. */
    val dialogs: Context
        get() = if (on) nightContext else activity

    fun show(night: Boolean) {
        if (night == on) return
        on = night
        val p = if (night) atNight else asPhone
        // What the page is framed in: black at night, like the page
        val frame = if (night) Color.BLACK else asPhone.surface

        toolbar.background = if (night) toolbarAtNight else toolbarAsPhone
        toolbar.setTitleTextColor(p.text)
        toolbar.setNavigationIconTint(p.onSurface)
        // Named rather than cleared on the way back: a cleared tint is no tint, and these
        // icons are black paths that the tint in their own XML colours
        val icons = ColorStateList.valueOf(p.onSurface)
        toolbar.menu.forEach { if (it.icon != null) it.iconTintList = icons }
        toolbar.overflowIcon?.let { toolbar.overflowIcon = it.mutate().apply { setTint(p.controlNormal) } }
        // The menu is built again every time it opens, from the toolbar's theme as it is then.
        // The app's own theme laid back over it undoes the overlay whole, text colour included.
        if (!phoneIsDark) {
            toolbar.context.theme.applyStyle(
                if (night) R.style.ThemeOverlay_Gander_Menu_Night else R.style.Theme_Gander,
                true
            )
        }

        // Behind the status and navigation bars. From Android 15 the app draws under both and
        // the root's padding keeps its content clear, so what shows there is the root's own
        // background; before that the window colours the status bar itself.
        root.background = if (night) frame.toDrawable() else rootAsPhone
        val window = activity.window
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            p.lightStatusBar
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = frame
        }

        searchBar.setBackgroundColor(frame)
        searchInput.setTextColor(p.text)
        searchInput.setHintTextColor(p.hint)
        ViewCompat.setBackgroundTintList(searchInput, p.underline)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            searchInput.textCursorDrawable?.let {
                searchInput.textCursorDrawable = it.mutate().apply { setTint(p.controlActivated) }
            }
        }
        searchCount.setTextColor(p.onSurfaceVariant)
        val buttons = ColorStateList.valueOf(p.onSurface)
        searchButtons.forEach { it.imageTintList = buttons }

        saveProgress.setIndicatorColor(p.primary)
        saveProgress.trackColor = p.secondaryContainer
    }

    /** The colours the parts above are drawn in, all read from one theme. */
    private class Palette(context: Context) {
        @ColorInt val surface = color(context, com.google.android.material.R.attr.colorSurface)

        @ColorInt val onSurface = color(context, com.google.android.material.R.attr.colorOnSurface)

        @ColorInt val onSurfaceVariant =
            color(context, com.google.android.material.R.attr.colorOnSurfaceVariant)

        @ColorInt val secondaryContainer =
            color(context, com.google.android.material.R.attr.colorSecondaryContainer)

        @ColorInt val primary = color(context, androidx.appcompat.R.attr.colorPrimary)

        @ColorInt val controlNormal = color(context, androidx.appcompat.R.attr.colorControlNormal)

        @ColorInt val controlActivated = color(context, androidx.appcompat.R.attr.colorControlActivated)
        val text = colorList(context, android.R.attr.textColorPrimary, onSurface)
        val hint = colorList(context, android.R.attr.textColorHint, onSurfaceVariant)

        // The line under the search box as AppCompat draws it: the control colour, and the
        // activated one while the box is pressed or has focus
        val underline = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_pressed, -android.R.attr.state_focused), intArrayOf()),
            intArrayOf(controlNormal, controlActivated)
        )
        val lightStatusBar = context.resources.getBoolean(R.bool.gander_light_status_bar)
    }
}

/**
 * Builds a dialog that keeps the night or day of the context it is built on.
 *
 * AppCompat sets each dialog it makes to the app's night mode, which for Gander is the phone's, by
 * rewriting the configuration of the Resources the dialog's context reads. The ones
 * [NightChrome.nightContext] reads are shared by the framework with every context set to night in
 * the process, so on a phone set to light the first dialog over a page in night mode turned all
 * of them to day: its own title came out dim on a dark box, every dialog after it light, and a
 * viewer opened later took light chrome from them, until the process ended. Told its context's
 * mode as soon as it exists, the dialog rewrites them back before anything in it is drawn. A
 * dialog made and shown in one call comes through [create] as well.
 *
 * Not a dialog built on the activity and told night instead: the Resources it rewrote then would
 * be the activity's own, and they would stay at night after the dialog had gone.
 */
internal class DialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {

    override fun create(): AlertDialog {
        // Read first, since making the dialog is what changes it
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return super.create().apply {
            delegate.localNightMode = if (night == Configuration.UI_MODE_NIGHT_YES) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        }
    }
}

@ColorInt
private fun color(context: Context, @AttrRes attr: Int): Int =
    MaterialColors.getColor(context, attr, NightChrome::class.java.simpleName)

private fun colorList(context: Context, @AttrRes attr: Int, @ColorInt fallback: Int): ColorStateList {
    val values = context.obtainStyledAttributes(intArrayOf(attr))
    try {
        return values.getColorStateList(0) ?: ColorStateList.valueOf(fallback)
    } finally {
        values.recycle()
    }
}
