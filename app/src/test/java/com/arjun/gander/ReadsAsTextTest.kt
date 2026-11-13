package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Test

/** Whether a file of no known format reads as text, which decides if it opens as text. Issue #51. */
class ReadsAsTextTest {

    private fun reads(bytes: ByteArray): Boolean = readsAsText(ByteArrayInputStream(bytes))

    @Test
    fun plainLettersAreText() {
        assertThat(reads("key = value\n# a comment\n".toByteArray())).isTrue()
    }

    /** No encoding of text a phone meets without a mark has a zero byte in it. */
    @Test
    fun lettersBeyondEnglishAreTextInUtf8AndInTheOldSingleByteEncodings() {
        assertThat(reads("Żółć, café, 日本語\n".toByteArray(Charsets.UTF_8))).isTrue()
        assertThat(reads("café au lait\n".toByteArray(Charsets.ISO_8859_1))).isTrue()
    }

    @Test
    fun aZeroByteIsNotText() {
        assertThat(reads(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0))).isFalse()
        assertThat(reads("words, then".toByteArray() + byteArrayOf(0) + "more words".toByteArray())).isFalse()
    }

    /** Half of UTF-16 is zeros, and the text page reads it where the file says which way round it is. */
    @Test
    fun utf16WithItsMarkIsText() {
        val words = "Ferry at nine\n"
        assertThat(reads(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + words.toByteArray(Charsets.UTF_16LE))).isTrue()
        assertThat(reads(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + words.toByteArray(Charsets.UTF_16BE))).isTrue()
    }

    /** Without the mark the text page would read it as noise, so the card is the honest answer. */
    @Test
    fun utf16WithoutItsMarkIsNot() {
        assertThat(reads("Ferry at nine\n".toByteArray(Charsets.UTF_16LE))).isFalse()
    }

    @Test
    fun anEmptyFileIsText() {
        assertThat(reads(ByteArray(0))).isTrue()
    }

    /** Only the start is read: a zero far into a long file does not send its reader to the card. */
    @Test
    fun aZeroPastTheStartIsNotLookedFor() {
        val long = ByteArray(20_000) { 'a'.code.toByte() }
        long[7_999] = 0
        assertThat(reads(long)).isFalse()
        long[7_999] = 'a'.code.toByte()
        long[8_000] = 0
        assertThat(reads(long)).isTrue()
    }

    /** A provider may hand over a few bytes at each read, and the start is still read through. */
    @Test
    fun aStreamThatComesInSmallPiecesIsReadAsFar() {
        val bytes = ByteArray(6_000) { 'a'.code.toByte() }
        bytes[5_000] = 0
        val trickle = object : InputStream() {
            private val all = ByteArrayInputStream(bytes)
            override fun read(): Int = all.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = all.read(b, off, minOf(len, 7))
        }
        assertThat(readsAsText(trickle)).isFalse()
    }

    private fun dotIsText(bytes: ByteArray): Boolean = dotOpensAsText(ByteArrayInputStream(bytes))

    /** Graphviz saves its graphs as .dot, and a graph is text, comment first or not. Issue #58. */
    @Test
    fun aGraphvizDotOpensAsText() {
        assertThat(dotIsText("digraph G {\n  a -> b;\n}\n".toByteArray())).isTrue()
        assertThat(dotIsText("/* made by a build tool */\nstrict graph {\n  a -- b\n}\n".toByteArray())).isTrue()
    }

    /** A Word 97-2003 template is a Word file, which the prose page reads. */
    @Test
    fun aWordTemplateDotDoesNot() {
        assertThat(dotIsText(Fixtures.bytes("legacy.doc"))).isFalse()
    }

    /**
     * Rich Text and an OpenDocument saved as one XML file are text the prose page reads, under a
     * .dot as under any name, past a byte order mark and blank lines as prose.js steps over them.
     */
    @Test
    fun richTextAndFlatOpenDocumentDotsStayWithTheProsePage() {
        val mark = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertThat(dotIsText(Fixtures.bytes("memo.rtf"))).isFalse()
        assertThat(dotIsText(mark + "\r\n\t {\\rtf1\\ansi Dear Sam}".toByteArray())).isFalse()
        assertThat(dotIsText("<?xml version=\"1.0\"?>\n<office:document/>".toByteArray())).isFalse()
        assertThat(dotIsText("<office:document office:version=\"1.3\"/>".toByteArray())).isFalse()
    }

    private fun opensAs(bytes: ByteArray, ext: String): FileKind =
        wordFileOpensAs(ByteArrayInputStream(bytes), ext)

    /**
     * Word's single-file XML under a .doc or .dot name goes to the Word page, which zips it back
     * up, since the prose page would take it for a flat OpenDocument and fail.
     */
    @Test
    fun aWordXmlPackageOpensInTheWordPage() {
        val pkg = Fixtures.bytes("word-package.doc")
        assertThat(opensAs(pkg, "doc")).isEqualTo(FileKind.DOCX)
        assertThat(opensAs(pkg, "dot")).isEqualTo(FileKind.DOCX)
    }

    /** Everything else keeps the page it had: Word 97 and flat OpenDocument the prose page, a graph text. */
    @Test
    fun otherDocAndDotFilesKeepTheirPages() {
        assertThat(opensAs(Fixtures.bytes("legacy.doc"), "doc")).isEqualTo(FileKind.PROSE)
        assertThat(opensAs(Fixtures.bytes("legacy.doc"), "dot")).isEqualTo(FileKind.PROSE)
        assertThat(opensAs(Fixtures.bytes("memo.rtf"), "doc")).isEqualTo(FileKind.PROSE)
        assertThat(opensAs("<?xml version=\"1.0\"?>\n<office:document/>".toByteArray(), "doc"))
            .isEqualTo(FileKind.PROSE)
        assertThat(opensAs("digraph G {\n  a -> b;\n}\n".toByteArray(), "dot")).isEqualTo(FileKind.TEXT)
        assertThat(opensAs("digraph G {\n  a -> b;\n}\n".toByteArray(), "doc")).isEqualTo(FileKind.PROSE)
    }
}
