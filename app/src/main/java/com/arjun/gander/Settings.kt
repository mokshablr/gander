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

    enum class SortOrder(val labelRes: Int) {
        NAME_AZ(R.string.sort_name_az),
        NAME_ZA(R.string.sort_name_za),
        NEWEST(R.string.sort_newest),
        OLDEST(R.string.sort_oldest),
        LARGEST(R.string.sort_largest),
        SMALLEST(R.string.sort_smallest);
    }

    /** Whether PDF pages are drawn turned over. See issue #19 and `pdf.html`. */
    fun night(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NIGHT, false)

    fun setNight(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_NIGHT, on) }
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
