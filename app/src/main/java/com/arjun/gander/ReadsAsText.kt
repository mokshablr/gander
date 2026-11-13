package com.arjun.gander

import java.io.InputStream

/** How much of a file is read to tell whether it is text, which is what git reads to tell. */
private const val TEXT_HEAD_BYTES = 8000

private const val ZERO: Byte = 0
private const val MARK_FF = 0xFF.toByte()
private const val MARK_FE = 0xFE.toByte()

/** Space, tab, line feed and carriage return: the blank space prose.js steps over. */
private val BLANKS = setOf(0x20, 0x09, 0x0A, 0x0D)

/**
 * Whether a file of no known format reads as text, from its first bytes. Issue #51.
 *
 * Text has no zero byte in it and nearly everything else has one early, which is how git and
 * most editors tell the two apart. UTF-16 is half zeros and still text, so a file that opens
 * with its byte order mark counts, since the text page reads those.
 */
internal fun readsAsText(stream: InputStream): Boolean = isText(head(stream))

/**
 * Whether a .dot opens as text rather than in the prose page. Issue #58.
 *
 * Word 97-2003 saves its templates as .dot and Graphviz saves its graphs as .dot, and the prose
 * page reads only the templates. A Word file has zero bytes in its first few, so a .dot that
 * reads as text is not one. Rich Text and an OpenDocument saved as one XML file are text too,
 * and the prose page reads those, so they stay with it.
 */
internal fun dotOpensAsText(stream: InputStream): Boolean = dotReadsAsText(head(stream))

private fun dotReadsAsText(head: ByteArray): Boolean = isText(head) && !startsAsProseText(head)

/**
 * Which page a file bound for the prose page opens in, from its first bytes.
 *
 * Word can also save a document as one XML file holding every part of a .docx, and some systems
 * hand those out named .doc. The prose page would take it for a flat OpenDocument and fail, so
 * it goes to the Word page, which zips it back up. A .dot that reads as text is a Graphviz graph,
 * as [dotOpensAsText] says, and everything else is a Word 97-2003 file for the prose page.
 */
internal fun wordFileOpensAs(stream: InputStream, ext: String): FileKind {
    val head = head(stream)
    return when {
        startsAsWordXmlPackage(head) -> FileKind.DOCX
        ext == "dot" && dotReadsAsText(head) -> FileKind.TEXT
        else -> FileKind.PROSE
    }
}

/** The namespace a single-file Office package's root is in, which nothing else uses. */
private const val XML_PACKAGE_NS = "http://schemas.microsoft.com/office/2006/xmlPackage"

private fun startsAsWordXmlPackage(head: ByteArray): Boolean =
    startsAsProseText(head) && String(head, Charsets.UTF_8).contains(XML_PACKAGE_NS)

private fun head(stream: InputStream): ByteArray {
    val head = ByteArray(TEXT_HEAD_BYTES)
    var filled = 0
    while (filled < head.size) {
        val n = stream.read(head, filled, head.size - filled)
        if (n < 0) break
        filled += n
    }
    return head.copyOf(filled)
}

private fun isText(head: ByteArray): Boolean {
    val utf16 = head.size >= 2 &&
        ((head[0] == MARK_FF && head[1] == MARK_FE) || (head[0] == MARK_FE && head[1] == MARK_FF))
    return utf16 || head.none { it == ZERO }
}

/**
 * Whether a file starts as Rich Text or a flat OpenDocument does, looked at as vwProseSniff in
 * prose.js looks: past a UTF-8 byte order mark and up to 64 bytes of blank space. The two must
 * agree, or a file this sends to the prose page would get its error instead.
 */
private fun startsAsProseText(head: ByteArray): Boolean {
    val utf8Mark = head.size >= 3 &&
        head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
    var at = if (utf8Mark) 3 else 0
    while (at < 64 && at < head.size && head[at].toInt() in BLANKS) at++
    fun startsWith(text: String) =
        at + text.length <= head.size && text.indices.all { head[at + it] == text[it].code.toByte() }
    return startsWith("{\\rtf") || startsWith("<?xml") || startsWith("<office:document")
}
