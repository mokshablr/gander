package com.arjun.gander

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** A long file name in a list row: two lines, then its middle cut out (#77). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TwoLineNameTest {

    /** A stand-in for a text layout: [perLine] characters to a line. */
    private fun lines(perLine: Int) = { s: String -> (s.length + perLine - 1) / perLine }

    @Test
    fun aNameThatFitsTwoLinesIsLeftWhole() {
        val name = "Ivanov Ivan Ivanovich - Book.fb2" // 32
        assertThat(middleCut(name, 2, lines(16))).isEqualTo(name)
        assertThat(middleCut(name, 2, lines(20))).isEqualTo(name)
    }

    @Test
    fun aLongerNameKeepsItsStartAndItsEnd() {
        val name = "Ivanov Ivan Ivanovich - The Long Title of One Book (1987).fb2"
        val shown = middleCut(name, 2, lines(20))
        assertThat(shown.length).isEqualTo(40)
        assertThat(shown).startsWith("Ivanov Ivan Ivano")
        assertThat(shown).contains("…")
        assertThat(shown).endsWith("(1987).fb2")
    }

    @Test
    fun theCutKeepsAsMuchAsFitsAndNoMore() {
        val name = "a".repeat(30) + "b".repeat(30)
        val shown = middleCut(name, 2, lines(25))
        assertThat(lines(25)(shown)).isAtMost(2)
        // One more character of the name would not fit
        assertThat(lines(25)(cutTo(name, shown.length))).isGreaterThan(2)
    }

    @Test
    fun theCutNeverSplitsACharacterOutsideTheBmp() {
        val face = String(Character.toChars(0x1F600))
        val name = face.repeat(20)
        for (keep in 0..name.length) {
            val shown = cutTo(name, keep)
            assertThat(shown.codePoints().anyMatch { Character.isSurrogate(it.toChar()) }).isFalse()
        }
    }

    @Test
    fun aRowWrapsALongNameToTwoLinesAndCutsTheRest() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_Gander
        )
        val parent = FrameLayout(context)
        val row = LayoutInflater.from(context).inflate(R.layout.row_item, parent, false)
        val title = row.findViewById<TextView>(R.id.title)
        val width = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        val short = "notes.md"
        title.text = short
        row.measure(width, height)
        assertThat(title.text.toString()).isEqualTo(short)
        assertThat(title.lineCount).isEqualTo(1)

        val two = "Ivanov Ivan Ivanovich - The Long Title of One Book (1987).fb2"
        title.text = two
        row.measure(width, height)
        assertThat(title.text.toString()).isEqualTo(two)
        assertThat(title.lineCount).isEqualTo(2)

        val long = "Ivanov Ivan Ivanovich - " + "A Very Long Title ".repeat(8) + "(1987).fb2"
        title.text = long
        row.measure(width, height)
        val shown = title.text.toString()
        assertThat(title.lineCount).isEqualTo(2)
        assertThat(shown).startsWith("Ivanov Ivan Ivanovich - A")
        assertThat(shown).contains("…")
        assertThat(shown).endsWith("(1987).fb2")
        // The view's own end cut, the backstop, never had to step in
        assertThat(title.layout.getEllipsisCount(1)).isEqualTo(0)

        // The list refreshing gives a row the same name again, which must be cut again
        title.text = long
        row.measure(width, height)
        assertThat(title.text.toString()).isEqualTo(shown)
        row.measure(width, height)
        assertThat(title.text.toString()).isEqualTo(shown)

        // A row recycled for a short name shows that name, not the old cut
        title.text = short
        row.measure(width, height)
        assertThat(title.text.toString()).isEqualTo(short)
    }
}
