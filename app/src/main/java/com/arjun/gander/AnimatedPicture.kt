package com.arjun.gander

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Whether a WebP or PNG moves, read from its first bytes. Issue #49.
 *
 * The photo viewer draws one frame of either, so one that moves goes to the page that plays
 * GIFs instead. An animated WebP sets the animation flag in its VP8X chunk, and an animated PNG
 * has an acTL chunk before its first IDAT. Anything unreadable is taken to keep still, which
 * is how it was shown before.
 */
internal fun isAnimated(stream: InputStream): Boolean = try {
    val input = DataInputStream(stream)
    val head = ByteArray(12).also { input.readFully(it) }
    when {
        head.says("RIFF", 0) && head.says("WEBP", 8) -> {
            // The chunk's name, its size, and the byte of flags after them
            val chunk = ByteArray(9).also { input.readFully(it) }
            chunk.says("VP8X", 0) && (chunk[8].toInt() and WEBP_ANIMATION) != 0
        }
        head.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) ->
            pngHasAnimation(input, ByteBuffer.wrap(head, PNG_SIGNATURE.size, 4).int)
        else -> false
    }
} catch (e: EOFException) {
    false
}

/** Walks the chunks after the signature, of which the first one's length has already been read. */
private fun pngHasAnimation(input: DataInputStream, firstLength: Int): Boolean {
    var length = firstLength
    var skipped = 0L
    repeat(PNG_MAX_CHUNKS) {
        val type = ByteArray(4).also { input.readFully(it) }
        if (type.says("acTL", 0)) return true
        if (type.says("IDAT", 0)) return false
        // The chunk's data and its checksum. A chunk that claims more than any file Gander shows
        // ahead of its picture is not worth reading through to find out.
        skipped += length + 4L
        if (length < 0 || skipped > PNG_MAX_SKIP) return false
        skipFully(input, length + 4L)
        length = input.readInt()
    }
    return false
}

private fun skipFully(input: InputStream, count: Long) {
    var left = count
    while (left > 0) {
        val n = input.skip(left)
        if (n > 0) left -= n else if (input.read() < 0) throw EOFException() else left--
    }
}

private fun ByteArray.says(word: String, at: Int): Boolean =
    word.indices.all { this[at + it] == word[it].code.toByte() }

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** The A bit of VP8X's flags, as the WebP container spec numbers them. */
private const val WEBP_ANIMATION = 0x02

/** A PNG puts a handful of chunks before its picture: a header, colour notes, text. */
private const val PNG_MAX_CHUNKS = 64
private const val PNG_MAX_SKIP = 4L * 1024 * 1024
