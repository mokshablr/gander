package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import org.junit.Test

/** Whether a WebP or PNG moves, read from its first bytes. Issue #49. */
class AnimatedPictureTest {

    private fun animated(bytes: ByteArray) = isAnimated(ByteArrayInputStream(bytes))

    private fun animated(fixture: String) = animated(Fixtures.bytes(fixture))

    @Test
    fun anAnimatedWebpAndAnAnimatedPngMove() {
        assertThat(animated("moving.webp")).isTrue()
        assertThat(animated("moving.png")).isTrue()
    }

    @Test
    fun aStillWebpAndAStillPngDoNot() {
        assertThat(animated("still.webp")).isFalse()
        assertThat(animated("tiny.png")).isFalse()
    }

    /** VP8X is also how a still WebP says it has transparency or a colour profile. */
    @Test
    fun anExtendedWebpMovesOnlyWithItsAnimationFlag() {
        fun webp(flags: Int) = "RIFF".toByteArray() + byteArrayOf(30, 0, 0, 0) + "WEBPVP8X".toByteArray() +
            byteArrayOf(10, 0, 0, 0, flags.toByte(), 0, 0, 0, 63, 0, 0, 63, 0, 0)
        // Alpha, EXIF and XMP, and nothing about moving
        assertThat(animated(webp(0x10 or 0x08 or 0x04))).isFalse()
        assertThat(animated(webp(0x02))).isTrue()
        assertThat(animated(webp(0x10 or 0x02))).isTrue()
    }

    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun png(vararg chunks: Pair<String, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write(signature)
            for ((type, length) in chunks) {
                writeInt(length)
                write(type.toByteArray())
                write(ByteArray(length))
                writeInt(0)
            }
        }
        return out.toByteArray()
    }

    /** Colour notes and text often come between the header and the picture, the APNG chunk among them. */
    @Test
    fun anAnimationChunkIsFoundPastTheOnesBeforeIt() {
        assertThat(animated(png("IHDR" to 13, "iCCP" to 3000, "tEXt" to 40, "acTL" to 8, "IDAT" to 20))).isTrue()
        assertThat(animated(png("IHDR" to 13, "iCCP" to 3000, "tEXt" to 40, "IDAT" to 20))).isFalse()
    }

    /** An APNG's control chunk has to come before the picture; one after it is ignored by every decoder. */
    @Test
    fun anAnimationChunkAfterThePictureDoesNotCount() {
        assertThat(animated(png("IHDR" to 13, "IDAT" to 20, "acTL" to 8))).isFalse()
    }

    @Test
    fun aFileThatEndsEarlyOrIsNotAPictureKeepsStill() {
        assertThat(animated(ByteArray(0))).isFalse()
        assertThat(animated("RIFF".toByteArray())).isFalse()
        assertThat(animated(Fixtures.bytes("moving.webp").copyOf(18))).isFalse()
        assertThat(animated(png("IHDR" to 13).copyOf(20))).isFalse()
        assertThat(animated(Fixtures.bytes("anim.gif"))).isFalse()
        assertThat(animated("%PDF-1.7 and the rest".toByteArray())).isFalse()
    }

    /** A file that goes on for ever, as a pipe out of a zip can seem to, counting what is read of it. */
    private class Endless(private val head: ByteArray) : InputStream() {
        var consumed = 0L
        override fun read(): Int =
            (if (consumed < head.size) head[consumed.toInt()].toInt() and 0xFF else 0).also { consumed++ }
        override fun skip(n: Long): Long = n.also { consumed += it }
    }

    /** A chunk claiming gigabytes is not read through to find out what follows it. */
    @Test
    fun aChunkTooLongToBeAHeaderEndsTheSearch() {
        val claims = png("IHDR" to 13) + ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(Int.MAX_VALUE); write("zTXt".toByteArray()) }
        }.toByteArray()
        val stream = Endless(claims)
        assertThat(isAnimated(stream)).isFalse()
        assertThat(stream.consumed).isLessThan(1024L * 1024)
    }
}
