package com.arjun.gander

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDialog
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import java.util.Locale

/**
 * A document's contents list, issue #79, full screen over the viewer: a PDF's outline, a Word
 * document's headings and a book's own list, one screen for all three. Tapping an entry closes it
 * and hands its number in the list to the page, which goes there.
 *
 * Native rather than drawn in the page. pdf.html and docx.html lay out at 980 px and are zoomed to
 * fit, so a panel there would come up magnified with the pages whenever the reader had pinched in,
 * and a book's panel of its own made two different lists behind one menu item. The titles are the
 * document's words and are only ever set as text.
 */
internal object ContentsList {

    /** How far in a level is set, and how many levels are, past which entries line up. */
    private const val STEP_DP = 16
    private const val EDGE_DP = 16
    private const val DEEPEST_SHOWN = 4

    /**
     * Shows [entries] over the viewer, built on [themed], which is dark over a page in night mode
     * (see [NightChrome.dialogs]). [here] is the place the reader is at: a page, or for a book
     * ([byChapter]) a chapter in reading order from one. The entry it falls in is marked and
     * scrolled to. An entry whose place is past [last] goes nowhere. A page is shown beside each
     * entry unless [byChapter]. [go] is handed the entry tapped, by its index in [entries].
     */
    fun show(
        themed: Context,
        entries: List<ContentsEntry>,
        here: Int,
        last: Int,
        byChapter: Boolean,
        go: (Int) -> Unit,
    ): AppCompatDialog {
        val at = current(entries, here, firstOfTies = byChapter)
        val dialog = AppCompatDialog(themed, R.style.Theme_Gander)
        // As DialogBuilder does, and for its reason: a dialog left to the process's night mode
        // turns the app's colours to day under a page in night mode on a light phone
        val night = themed.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        dialog.delegate.localNightMode = if (night == Configuration.UI_MODE_NIGHT_YES) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        dialog.setContentView(R.layout.contents_screen)
        dialog.setTitle(R.string.contents)
        val window = dialog.window!!
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val root = dialog.findViewById<View>(R.id.contents_root)!!
        val list = dialog.findViewById<ListView>(R.id.contents_list)!!
        val bar = dialog.findViewById<MaterialToolbar>(R.id.contents_bar)!!
        root.setBackgroundColor(color(dialog.context, com.google.android.material.R.attr.colorSurface))

        // Drawn under the status and navigation bars on every Android, as the viewer is from 15,
        // with the title bar and the list's foot kept clear of them
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val listBottom = list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            list.updatePadding(bottom = listBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        bar.setNavigationOnClickListener { dialog.dismiss() }
        list.adapter = Rows(dialog.context, entries, at, last, byChapter)
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            go(position)
        }
        dialog.show()
        if (at >= 0) {
            // A third of the way down, so the entries before it show as well. After layout,
            // which is when the list has a height to take a third of.
            list.post { list.setSelectionFromTop(at, list.height / 3) }
        }
        return dialog
    }

    /**
     * The entry the reader is in at [here]: one on the latest place not past it. Of several on
     * that place, a page takes the last, the most particular, and a chapter the first, since a
     * book says only which chapter is on screen and not how far into it. -1 before the first
     * entry. By place rather than by the list's order, which a document need not keep.
     */
    fun current(entries: List<ContentsEntry>, here: Int, firstOfTies: Boolean = false): Int {
        var best = -1
        var bestPlace = 0
        entries.forEachIndexed { i, e ->
            val later = if (firstOfTies) e.place > bestPlace else e.place >= bestPlace
            if (e.place in 1..here && later) {
                best = i
                bestPlace = e.place
            }
        }
        return best
    }

    @ColorInt
    private fun color(context: Context, @AttrRes attr: Int) =
        MaterialColors.getColor(context, attr, ContentsList::class.java.simpleName)

    private class Rows(
        private val context: Context,
        private val entries: List<ContentsEntry>,
        private val here: Int,
        private val last: Int,
        private val byChapter: Boolean,
    ) : BaseAdapter() {

        private val density = context.resources.displayMetrics.density
        private val accent = color(context, androidx.appcompat.R.attr.colorPrimary)
        private val plain = color(context, com.google.android.material.R.attr.colorOnSurface)
        private val quiet = color(context, com.google.android.material.R.attr.colorOnSurfaceVariant)

        override fun getCount() = entries.size

        override fun getItem(position: Int) = entries[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun areAllItemsEnabled() = false

        override fun isEnabled(position: Int) = goesSomewhere(entries[position])

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.row_contents, parent, false)
            val e = entries[position]
            val title = row.findViewById<TextView>(R.id.contents_title)
            val page = row.findViewById<TextView>(R.id.contents_page)
            val start = ((EDGE_DP + STEP_DP * e.depth.coerceAtMost(DEEPEST_SHOWN)) * density).toInt()
            row.setPaddingRelative(start, row.paddingTop, row.paddingEnd, row.paddingBottom)

            title.text = e.title
            // As the book's own list read them (#54): its way, and from that the side it is set
            // in from and the side its page is on, and its language, which picks the letters
            // a script shared by several languages is drawn in
            val rtl = e.dir == "rtl" ||
                (e.dir == "auto" && TextDirectionHeuristicsCompat.FIRSTSTRONG_LTR.isRtl(e.title, 0, e.title.length))
            row.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            title.textDirection = when (e.dir) {
                "rtl" -> View.TEXT_DIRECTION_RTL
                "ltr" -> View.TEXT_DIRECTION_LTR
                else -> View.TEXT_DIRECTION_FIRST_STRONG
            }
            title.textLocale = if (e.lang.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(e.lang)
            val isHere = position == here
            title.setTypeface(null, if (isHere) Typeface.BOLD else Typeface.NORMAL)
            title.setTextColor(if (isHere) accent else if (goesSomewhere(e)) plain else quiet)
            ViewCompat.setStateDescription(row, if (isHere) context.getString(R.string.contents_here) else null)

            // A book's place is a chapter's number in its reading order, which nobody reads by
            if (goesSomewhere(e) && !byChapter) {
                page.text = context.getString(R.string.contents_page, e.place)
                page.contentDescription = context.getString(R.string.contents_page_spoken, e.place)
                page.visibility = View.VISIBLE
            } else {
                page.visibility = View.GONE
            }
            return row
        }

        // A place past the document's end is the list's mistake, and listed as nowhere
        private fun goesSomewhere(e: ContentsEntry) = e.place in 1..last
    }
}
