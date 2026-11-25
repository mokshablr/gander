package com.arjun.gander

import android.content.Context
import androidx.core.content.edit

/**
 * The reader's own preferences, as opposed to [Recents], which is a record of what
 * they opened.
 *
 * Gander has never had a settings screen and does not want one; settings here persist
 * across opens because reading preferences and view modes are not things you want to
 * re-select each time.
 *
 * Not backed up: `allowBackup="false"` in the manifest covers this file along with the
 * recents list, for the reason given there.
 */
object Settings {

    private const val PREFS = "viewer"
    private const val KEY_NIGHT = "night"
    private const val KEY_SORT_ORDER = "folder_sort_order"

    // Named for books, which had it first; renaming it would lose the size already chosen
    private const val KEY_TEXT_SIZE = "book_text_size"
    private const val KEY_DELETE_FOR_GOOD = "delete_for_good"

    /**
     * The sizes the text of a book, a text file or a Markdown file can be set to, as a
     * percentage of the size it opens at. Nine steps, each about a seventh larger than the one
     * before, from a little under the file's own size to two and a half times it. Past that a
     * phone holds three words to a line, and pinching is the better tool.
     */
    val TEXT_SIZES = intArrayOf(80, 90, 100, 115, 130, 150, 175, 200, 250)

    enum class SortOrder(val labelRes: Int) {
        NAME_AZ(R.string.sort_name_az),
        NAME_ZA(R.string.sort_name_za),

        /** By extension, then by name, so a folder's files of one format sit together. Issue #77. */
        TYPE(R.string.sort_type),
        NEWEST(R.string.sort_newest),
        OLDEST(R.string.sort_oldest),
        LARGEST(R.string.sort_largest),
        SMALLEST(R.string.sort_smallest)
    }

    /** Whether PDF pages are drawn turned over. See issue #19 and `pdf.html`. */
    fun night(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NIGHT, false)

    fun setNight(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_NIGHT, on) }
    }

    /**
     * The reader's text size, one of [TEXT_SIZES]. The same for every book, text file and
     * Markdown file, since it suits the reader's eyes and not the file.
     */
    fun textSize(context: Context): Int {
        val kept = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TEXT_SIZE, 100)
        return if (kept in TEXT_SIZES) kept else 100
    }

    fun setTextSize(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putInt(KEY_TEXT_SIZE, percent) }
    }

    /**
     * Whether Delete in an added folder asks Android to delete for good rather than move the
     * files to its bin, which keeps them 30 days and which only some gallery apps can empty.
     * Asked for on issue #75. Off unless turned on.
     */
    fun deleteForGood(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DELETE_FOR_GOOD, false)

    fun setDeleteForGood(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DELETE_FOR_GOOD, on) }
    }

    fun sortOrder(context: Context): SortOrder {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SORT_ORDER, SortOrder.NAME_AZ.name)
        return runCatching { SortOrder.valueOf(name ?: SortOrder.NAME_AZ.name) }
            .getOrDefault(SortOrder.NAME_AZ)
    }

    fun setSortOrder(context: Context, order: SortOrder) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_SORT_ORDER, order.name) }
    }
}
