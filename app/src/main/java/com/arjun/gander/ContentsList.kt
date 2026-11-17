package com.arjun.gander

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors

/**
 * A document's contents list, issue #79, as a list in a dialog: a PDF's outline, or a Word
 * document's headings. Tapping an entry goes to its page, the way Go to page does.
 *
 * Native rather than drawn in the page as a book's is. pdf.html and docx.html lay out at 980 px
 * and are zoomed to fit, so a panel there would come up magnified with the pages whenever the
 * reader had pinched in. The titles are the document's words and are only ever set as text.
 */
internal object ContentsList {

    /** How far in a level is set, and how many levels are, past which entries line up. */
    private const val STEP_DP = 16
    private const val DEEPEST_SHOWN = 4

    /**
     * Shows [entries] over the viewer in [themed], which is dark over a page in night mode (see
     * [NightChrome.dialogs]), with the one [pageAt] is in marked and scrolled to. [go] is handed
     * the page of the entry tapped, which is one of the document's [pages].
     */
    fun show(themed: Context, entries: List<ContentsEntry>, pageAt: Int, pages: Int, go: (Int) -> Unit) {
        val here = current(entries, pageAt)
        val adapter = Rows(themed, entries, here, pages)
        val dialog = DialogBuilder(themed)
            .setTitle(R.string.contents)
            .setAdapter(adapter) { _, which -> go(entries[which].page) }
            .create()
        dialog.show()
        if (here >= 0) {
            val list = dialog.listView
            // A third of the way down, so the entries before it show as well. After layout,
            // which is when the list has a height to take a third of.
            list.post { list.setSelectionFromTop(here, list.height / 3) }
        }
    }

    /**
     * The entry the reader is in on [pageAt]: the one on the latest page not past it, and of
     * several on that page the last, which is the most particular. -1 before the first entry.
     * By page rather than by the list's order, which an outline need not keep.
     */
    fun current(entries: List<ContentsEntry>, pageAt: Int): Int {
        var best = -1
        var bestPage = 0
        entries.forEachIndexed { i, e ->
            if (e.page in 1..pageAt && e.page >= bestPage) {
                best = i
                bestPage = e.page
            }
        }
        return best
    }

    private class Rows(
        private val context: Context,
        private val entries: List<ContentsEntry>,
        private val here: Int,
        private val pages: Int,
    ) : BaseAdapter() {

        private val density = context.resources.displayMetrics.density
        private val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, ContentsList::class.java.simpleName)
        private val plain = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, ContentsList::class.java.simpleName)
        private val quiet = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, ContentsList::class.java.simpleName)

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
            val start = ((24 + STEP_DP * e.depth.coerceAtMost(DEEPEST_SHOWN)) * density).toInt()
            row.setPaddingRelative(start, row.paddingTop, row.paddingEnd, row.paddingBottom)

            title.text = e.title
            val isHere = position == here
            title.setTypeface(null, if (isHere) Typeface.BOLD else Typeface.NORMAL)
            title.setTextColor(if (isHere) accent else if (goesSomewhere(e)) plain else quiet)
            ViewCompat.setStateDescription(row, if (isHere) context.getString(R.string.contents_here) else null)

            if (goesSomewhere(e)) {
                page.text = context.getString(R.string.contents_page, e.page)
                page.contentDescription = context.getString(R.string.contents_page_spoken, e.page)
                page.visibility = View.VISIBLE
            } else {
                page.visibility = View.GONE
            }
            return row
        }

        // A page past the document's end is the outline's mistake, and listed as nowhere
        private fun goesSomewhere(e: ContentsEntry) = e.page in 1..pages
    }
}
