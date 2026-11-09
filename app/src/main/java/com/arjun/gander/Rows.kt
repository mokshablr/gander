package com.arjun.gander

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import androidx.recyclerview.widget.RecyclerView

/**
 * The rows of a file list: the home screen's recents and folders, and what is inside a .zip.
 *
 * One adapter for both, so a folder inside an archive looks and behaves exactly like a folder
 * on the phone, down to what TalkBack says about each row.
 */
internal sealed interface Row {
    data class Header(val title: String) : Row
    data class Hint(val text: String) : Row
    data class Item(
        val badge: String,
        val color: Int,
        val title: String,
        val subtitle: String?,
        val onClick: () -> Unit,
        /**
         * Asks whether to remove the row, and calls back if the answer is no, so a row swiped
         * away can come back. Null for a row that cannot be removed.
         */
        val onRemove: ((kept: () -> Unit) -> Unit)? = null,
        val thumbUri: Uri? = null,
        val thumbExt: String = "",
        /**
         * What the row goes by while rows are picked to delete together, issue #75: a photo's
         * address. Null for a row that can't be picked.
         */
        val pickKey: String? = null
    ) : Row
}

internal class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val rows = mutableListOf<Row>()

    /**
     * The rows picked, by [Row.Item.pickKey], while rows are being picked to delete together,
     * and null while a tap on a row opens it. Issue #75.
     */
    private var picked: Set<String>? = null

    /**
     * A row that can be picked was tapped while rows are being picked, or [held] down, which is
     * how picking starts, and picks or lets go like a tap once it has.
     */
    var onPick: (key: String, held: Boolean) -> Unit = { _, _ -> }

    /** Draws the rows for [now] being picked, or for none being, touching only the rows it changes. */
    fun pick(now: Set<String>?) {
        val before = picked
        picked = now?.toSet()
        if ((before == null) != (now == null)) {
            // Every row changes: the ones that can be picked gain or lose their mark, the rest dim or clear
            notifyItemRangeChanged(0, rows.size, PICK)
            return
        }
        if (before == null || now == null) return
        rows.forEachIndexed { at, row ->
            val key = (row as? Row.Item)?.pickKey ?: return@forEachIndexed
            if ((key in before) != (key in now)) notifyItemChanged(at, PICK)
        }
    }

    /** What can be picked among the rows on screen. */
    val pickable: Set<String> get() = rows.mapNotNullTo(LinkedHashSet()) { (it as? Row.Item)?.pickKey }

    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    /**
     * Whether the row at [position] wants the whole width rather than one cell.
     *
     * Out-of-range answers full span on purpose: the layout manager can ask about a
     * position mid-update, and a header-shaped guess reflows harmlessly where a
     * cell-shaped one would throw.
     */
    fun isFullSpan(position: Int): Boolean =
        position !in rows.indices || rows[position] !is Row.Item

    /** Whether the row at [position] can be swiped away. See [SwipeToRemove]. */
    fun removable(position: Int): Boolean = (rows.getOrNull(position) as? Row.Item)?.onRemove != null

    /** The row at [position] was swiped away. It is asked about, and put back if it stays. */
    fun swiped(position: Int) {
        val putBack = { if (position in rows.indices) notifyItemChanged(position) }
        val remove = (rows.getOrNull(position) as? Row.Item)?.onRemove
        if (remove == null) putBack() else remove(putBack)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> 0
        is Row.Hint -> 1
        is Row.Item -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            0 -> R.layout.row_header
            1 -> R.layout.row_hint
            else -> R.layout.row_item
        }
        return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
    }

    override fun getItemCount(): Int = rows.size

    /** A change to what is picked redraws a row's mark and leaves its thumbnail alone. */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = rows[position]
        if (payloads.isNotEmpty() && row is Row.Item) bindPick(holder.itemView, row) else onBindViewHolder(holder, position)
    }

    /**
     * How a row looks and answers a tap for what is being picked. While rows are picked, a tap
     * on one that can be picks it or lets it go, and the rest stand back, dimmed, and open
     * nothing. Otherwise a tap opens the row. Holding one that can be picked starts picking, and
     * does what a tap does once picking has started.
     */
    private fun bindPick(view: View, row: Row.Item) {
        val picking = picked
        val key = row.pickKey
        val mark = view.findViewById<ImageView>(R.id.pick)
        mark.visibility = if (picking != null && key != null) View.VISIBLE else View.GONE
        if (picking != null && key != null) {
            mark.setImageResource(if (key in picking) R.drawable.ic_pick_on else R.drawable.ic_pick_off)
        }
        view.alpha = if (picking != null && key == null) DIMMED else 1f
        ViewCompat.setStateDescription(
            view,
            if (picking == null || key == null) null
            else view.context.getString(if (key in picking) R.string.selected else R.string.not_selected)
        )
        view.setOnClickListener {
            when {
                picked == null -> row.onClick()
                key != null -> onPick(key, false)
            }
        }
        if (key == null) {
            view.setOnLongClickListener(null)
            view.isLongClickable = false
        } else {
            view.setOnLongClickListener {
                onPick(key, true)
                true
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header ->
                holder.itemView.findViewById<TextView>(R.id.headerText).text = row.title
            is Row.Hint ->
                holder.itemView.findViewById<TextView>(R.id.hintText).text = row.text
            is Row.Item -> {
                val badge = holder.itemView.findViewById<TextView>(R.id.badge)
                val thumb = holder.itemView.findViewById<ImageView>(R.id.thumb)
                badge.text = row.badge
                badge.background.mutate().setTint(row.color)
                badge.visibility = View.VISIBLE
                thumb.visibility = View.GONE
                thumb.setImageDrawable(null)
                thumb.tag = null
                if (row.thumbUri != null) {
                    Thumbs.load(
                        holder.itemView.context, row.thumbUri, row.thumbExt, thumb, badge
                    )
                }
                holder.itemView.findViewById<TextView>(R.id.title).text = row.title
                val sub = holder.itemView.findViewById<TextView>(R.id.subtitle)
                sub.text = row.subtitle
                sub.visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
                // The row children are not-important for accessibility, so this is
                // the whole announcement. Keeping the badge in it matters: the badge
                // is hidden once a thumbnail loads, and the file type would go with it
                holder.itemView.contentDescription =
                    listOfNotNull(row.title, row.badge, row.subtitle).joinToString(", ")
                bindPick(holder.itemView, row)
                // A swipe is how a row is removed, and nothing on screen says so, nor can a
                // screen reader make one. So TalkBack is offered Remove among the row's
                // actions, as dismiss, the action a view that can be swiped away has, and
                // rows that stay must not offer it.
                val remove = row.onRemove
                ViewCompat.replaceAccessibilityAction(
                    holder.itemView, AccessibilityActionCompat.ACTION_DISMISS,
                    remove?.let { holder.itemView.context.getString(R.string.remove) },
                    remove?.let { AccessibilityViewCommand { _, _ -> it {}; true } }
                )
            }
        }
    }

    private companion object {
        /** Tells a row only what is picked has changed. */
        val PICK = Any()

        /** A row that can't be picked while others are, as Material dims what can't be used. */
        const val DIMMED = 0.38f
    }
}
