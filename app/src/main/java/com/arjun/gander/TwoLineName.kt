package com.arjun.gander

import android.content.Context
import android.text.StaticLayout
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A file's name in a list row, on up to two lines, with the middle cut out of one that needs more.
 *
 * Android cuts the middle only out of a single line, and over two lines it cuts the end, which
 * would drop the year and the extension. Names in a folder of books share their start ("Author -
 * Title (Year)"), so the middle is the part to lose, and that cut is made here instead (#77).
 */
internal class TwoLineName(context: Context, attrs: AttributeSet?) : AppCompatTextView(context, attrs) {

    // Not initialised here: TextView's constructor sets the text before Kotlin's initialisers run
    private var full: CharSequence? = null
    private var cutting = false

    // The last name and width measured, so a row measured again skips the search
    private var fittedName: String? = null
    private var fittedWidth = 0

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!cutting) {
            full = text
            fittedName = null
        }
        super.setText(text, type)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
        val name = (full ?: text).toString()
        val fresh = name != fittedName || width != fittedWidth
        if (fresh && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && width > 0) {
            fittedName = name
            fittedWidth = width
            val shown = middleCut(name, LINES) { lineCount(it, width) }
            if (shown != text.toString()) {
                cutting = true
                try { setText(shown) } finally { cutting = false }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun lineCount(s: String, width: Int): Int =
        StaticLayout.Builder.obtain(s, 0, s.length, paint, width)
            .setIncludePad(includeFontPadding)
            .setBreakStrategy(breakStrategy)
            .setHyphenationFrequency(hyphenationFrequency)
            .build()
            .lineCount

    private companion object {
        const val LINES = 2
    }
}

/**
 * [name] as is if [lines] says it takes at most [max] lines, or else its start and end around an
 * ellipsis, keeping as much of both as fits.
 */
internal fun middleCut(name: String, max: Int, lines: (String) -> Int): String {
    if (lines(name) <= max) return name
    // The most characters of the name that still fit, found by halving
    var lo = 0
    var hi = name.length - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (lines(cutTo(name, mid)) <= max) lo = mid else hi = mid - 1
    }
    return cutTo(name, lo)
}

/** [name] with [keep] of its characters kept, half from each end, around an ellipsis. */
internal fun cutTo(name: String, keep: Int): String {
    var headEnd = keep - keep / 2
    var tailStart = name.length - keep / 2
    // A character outside the BMP is two chars, and half of one would draw as a box
    if (headEnd > 0 && Character.isHighSurrogate(name[headEnd - 1])) headEnd--
    if (tailStart < name.length && Character.isLowSurrogate(name[tailStart])) tailStart++
    return name.substring(0, headEnd) + "…" + name.substring(tailStart)
}
