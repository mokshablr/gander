package com.arjun.gander

import java.io.InputStream
import java.util.Locale

/**
 * What a text file is written in, when it does not say. Issue #78.
 *
 * The text page decodes UTF-8, or UTF-16 by its byte order mark, and a file a Chinese Windows
 * saved as GBK came out as replacement characters. A file has nothing in it to say which code
 * page it is in, so one that is not UTF-8 is scored the way a zip's names are, see
 * [ZipNames.textCodePage], from its first [HEAD_BYTES].
 */
internal object TextEncoding {

    /** How much of a file the guess reads: a few pages of text, and quick to score. */
    const val HEAD_BYTES = 64 * 1024

    /**
     * The label of the code page [head], a file's first bytes, is written in, for a TextDecoder,
     * or null to decode it as UTF-8 or by its byte order mark, as the page did before. A UTF-8
     * file with a stray bad byte stays UTF-8.
     */
    fun guess(head: ByteArray, locale: Locale = Locale.getDefault()): String? {
        if (hasByteOrderMark(head)) return null
        // Bytes below 0x80 read the same in every candidate, so a file of only those is UTF-8
        if (head.all { it >= 0 }) return null
        // A head that ends inside a character has one stray byte, which the guess allows for
        return ZipNames.textCodePage(head, locale)
    }

    /** Reads up to [HEAD_BYTES] from [stream], fewer only when it ends sooner. */
    fun readHead(stream: InputStream): ByteArray {
        val buf = ByteArray(HEAD_BYTES)
        var n = 0
        while (n < buf.size) {
            val got = stream.read(buf, n, buf.size - n)
            if (got < 0) break
            n += got
        }
        return buf.copyOf(n)
    }

    private fun hasByteOrderMark(b: ByteArray): Boolean {
        fun at(i: Int) = b.getOrNull(i)?.toInt()?.and(0xFF)
        return (at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) ||
            (at(0) == 0xFF && at(1) == 0xFE) ||
            (at(0) == 0xFE && at(1) == 0xFF)
    }
}
