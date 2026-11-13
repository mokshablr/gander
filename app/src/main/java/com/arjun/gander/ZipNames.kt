package com.arjun.gander

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.CRC32

/** One name as the central directory holds it, before anything has decided what it says. */
internal class RawName(
    val bytes: ByteArray,
    /** General purpose bit 11: whoever wrote the zip says this name is UTF-8. */
    val utf8: Boolean,
    /** The data of an Info-ZIP Unicode Path field (0x7075), when the entry carries one. */
    val unicodePath: ByteArray? = null,
)

/** Every name in one archive, decoded, and what the ones that did not say were read as. */
internal class DecodedNames(
    /** Same order and count as the names given. */
    val names: List<String>,
    /**
     * The code page the names that did not say what they were written in were read in, or
     * null when every name did say: flagged UTF-8, a Unicode Path field, or plain ASCII. Null
     * is also when there is nothing for a reader to correct by hand.
     */
    val codePage: Charset?,
)

/**
 * The names inside a .zip, decoded (#30). A zip's name is bytes and one flag that only ever
 * means UTF-8, and without it the bytes are in whatever made the zip used: the machine's DOS
 * code page on Windows, UTF-8 on a Mac. Nothing says which, and java.util.zip reads them all as
 * UTF-8, turning the rest into question marks. So each candidate code page is scored on whether
 * the names come out as text people write, over every name in the archive, and the phone's
 * language breaks ties. Korean against Chinese and Western European against the double-byte
 * pages score alike that way, and take a check each: [COMMON_BONUS] and [westernLetterPairedUp].
 * A guess can still be wrong, so the reader can also choose the code page: [CHOICES].
 */
internal object ZipNames {

    /** What a candidate's output is judged against. [doubleByte] is the ones written two bytes to a character. */
    private enum class Script(val doubleByte: Boolean = false) {
        GB(true), BIG5(true), SJIS(true), KOREAN(true), CYRILLIC, GREEK, LATIN
    }

    /** One code page, under each name a runtime might know it by, preferred first. */
    private class Candidate(val script: Script, vararg names: String) {
        val charset: Charset? = charset(*names)
    }

    // The Windows code pages for each, where Java knows them separately. They are supersets
    // of the plain standards, and what Explorer actually writes.
    private val GBK = Candidate(Script.GB, "GBK")
    private val BIG5 = Candidate(Script.BIG5, "x-windows-950", "Big5")
    private val SJIS = Candidate(Script.SJIS, "windows-31j", "Shift_JIS")
    private val UHC = Candidate(Script.KOREAN, "x-windows-949", "EUC-KR")
    private val DOS_CYRILLIC = Candidate(Script.CYRILLIC, "IBM866")
    private val WIN_CYRILLIC = Candidate(Script.CYRILLIC, "windows-1251")
    private val DOS_US = Candidate(Script.LATIN, "IBM437")
    private val DOS_WESTERN = Candidate(Script.LATIN, "IBM850")
    private val DOS_CENTRAL = Candidate(Script.LATIN, "IBM852")
    private val DOS_TURKISH = Candidate(Script.LATIN, "IBM857")
    private val DOS_GREEK = Candidate(Script.GREEK, "x-IBM737", "IBM737")

    /**
     * Tried on every archive, in this order when nothing else decides. The last three above
     * join only on a phone set to a language that uses them: each reads most bytes as some
     * Latin or Greek letter, and elsewhere they would only be a way to be wrong.
     */
    private val EVERYWHERE = listOf(
        GBK, SJIS, BIG5, UHC, DOS_CYRILLIC, WIN_CYRILLIC, DOS_US, DOS_WESTERN
    )

    /** How much of the names the scoring reads. Plenty to decide on, and bounded. */
    private const val SAMPLE_BYTES = 64 * 1024

    /**
     * What a character scores on top of its 1 when it is one of the commonest in its language,
     * from [CommonCharacters]. Added rather than taken from the rest, because the 1 is what
     * every other candidate is measured against: GBK read as Windows Cyrillic is a run of
     * Cyrillic letters and scores 1 too, and lowering the uncommon hanzi below it handed one
     * name in six from a Simplified Chinese zip to Cyrillic.
     *
     * Chosen on half of the text CommonCharacters is counted from, held out from the counting,
     * and a list of words people name files with. For a zip of one name, a Korean one now comes
     * out Korean 96% of the time on a phone set to any other language, where it came out
     * Chinese every time; a Chinese one read as Korean on a Korean phone one time in six and
     * now one in thirty. From three names up, neither was wrong once in 800 tries.
     */
    private const val COMMON_BONUS = 0.5

    private val commonKorean: Set<Char> by lazy { CommonCharacters.KOREAN.toSet() }
    private val commonSimplified: Set<Char> by lazy { CommonCharacters.SIMPLIFIED.toSet() }
    private val commonTraditional: Set<Char> by lazy { CommonCharacters.TRADITIONAL.toSet() }

    /** 1 for a character a language uses every day, and more when it is one of its commonest. */
    private fun everyday(cp: Int, common: Set<Char>): Double =
        if (cp <= 0xFFFF && cp.toChar() in common) 1.0 + COMMON_BONUS else 1.0

    /**
     * Every code page a reader can choose by hand, under the key the menu names it by, in the
     * order the menu lists them: UTF-8 for a zip whose names only look like it, then every one
     * ZipNames guesses among, then five it never guesses. Hebrew, Arabic, Thai, Baltic and
     * Vietnamese Windows machines write their names in these, and each reads as some other
     * language's letters with nothing in a name to say which. Any a phone lacks is left out.
     */
    val CHOICES: List<Pair<String, Charset>> by lazy {
        listOf(
            "utf8" to Charsets.UTF_8,
            "gbk" to GBK.charset,
            "big5" to BIG5.charset,
            "sjis" to SJIS.charset,
            "korean" to UHC.charset,
            "cp866" to DOS_CYRILLIC.charset,
            "cp1251" to WIN_CYRILLIC.charset,
            "cp850" to DOS_WESTERN.charset,
            "cp437" to DOS_US.charset,
            "cp852" to DOS_CENTRAL.charset,
            "cp737" to DOS_GREEK.charset,
            "cp857" to DOS_TURKISH.charset,
            "cp862" to charset("IBM862"),
            "cp720" to charset("x-IBM720", "IBM720"),
            "cp874" to charset("x-IBM874", "TIS-620"),
            "cp775" to charset("IBM775"),
            "cp1258" to charset("windows-1258"),
        ).mapNotNull { (key, charset) -> charset?.let { key to it } }
    }

    private fun charset(vararg names: String): Charset? =
        names.firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }

    /**
     * Which of the [CHOICES] keys [charset] is, or null. By name, since a runtime can hand back
     * the same code page from two lookups as two objects, or under an alias: Android answers
     * x-windows-949 with its EUC-KR, which reads Windows' extra syllables all the same.
     */
    fun keyOf(charset: Charset?): String? =
        charset?.let { c -> CHOICES.firstOrNull { it.second.name() == c.name() }?.first }

    /** Every name in one archive, decoded. Same order, same count. */
    fun decode(names: List<RawName>, locale: Locale = Locale.getDefault()): List<String> =
        read(names, locale).names

    /**
     * Every name in one archive, decoded, and the code page it took. [chosen] is one the reader
     * picked, which is used for every name that does not say otherwise, in place of a guess.
     * Names that do say, by the flag or a Unicode Path field, are never read any other way:
     * the writer knew, and a choice made for the rest of them is not about those.
     */
    fun read(names: List<RawName>, locale: Locale = Locale.getDefault(), chosen: Charset? = null): DecodedNames {
        val unicode = names.map(::unicodePath)
        // The names nothing else speaks for, which are the ones a code page has to be found for
        val legacy = names.filterIndexed { i, n -> !n.utf8 && unicode[i] == null && !isAscii(n.bytes) }
        val charset = when {
            legacy.isEmpty() -> null
            chosen != null -> chosen
            // macOS: UTF-8 without the flag. Checked over every such name at once, since
            // one short legacy name can be valid UTF-8 by accident and a whole archive of
            // them is not.
            legacy.all { strict(Charsets.UTF_8, it.bytes) != null } -> Charsets.UTF_8
            else -> guess(legacy.map { it.bytes }, locale)
        }
        return DecodedNames(
            names.mapIndexed { i, n ->
                when {
                    n.utf8 -> String(n.bytes, Charsets.UTF_8)
                    unicode[i] != null -> unicode[i]!!
                    isAscii(n.bytes) -> String(n.bytes, Charsets.US_ASCII)
                    else -> String(n.bytes, charset ?: Charsets.UTF_8)
                }
            },
            charset,
        )
    }

    /**
     * The UTF-8 name Info-ZIP's Unicode Path field carries, if it has one and it still
     * describes this entry. The field records a checksum of the name it was written beside,
     * so a tool that renamed the entry without knowing about the field leaves one that no
     * longer matches, and the ordinary name wins.
     */
    private fun unicodePath(name: RawName): String? {
        val field = name.unicodePath ?: return null
        if (field.size < 5 || field[0].toInt() != 1) return null
        val recorded = (field[1].toLong() and 0xFF) or
            ((field[2].toLong() and 0xFF) shl 8) or
            ((field[3].toLong() and 0xFF) shl 16) or
            ((field[4].toLong() and 0xFF) shl 24)
        val actual = CRC32().apply { update(name.bytes) }.value
        if (recorded != actual) return null
        return strict(Charsets.UTF_8, field.copyOfRange(5, field.size))
    }

    private fun isAscii(bytes: ByteArray) = bytes.all { it >= 0 }

    /** [bytes] as [charset] says, or null if they are not valid in it. */
    private fun strict(charset: Charset, bytes: ByteArray): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * The code pages names are guessed among, the phone's language first: also the ones a
     * password under the older encryption may have been written in, see ZipEncryption.
     */
    fun codePages(locale: Locale): List<Charset> = ordered(locale).mapNotNull { it.charset }

    /** The phone's language first, then everything else. */
    private fun ordered(locale: Locale): List<Candidate> {
        val first = when (locale.language) {
            "zh" ->
                if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) {
                    listOf(BIG5, GBK)
                } else {
                    listOf(GBK, BIG5)
                }
            "ja" -> listOf(SJIS)
            "ko" -> listOf(UHC)
            "ru", "uk", "be", "bg", "kk", "ky", "tg", "mn", "mk" -> listOf(DOS_CYRILLIC, WIN_CYRILLIC)
            "el" -> listOf(DOS_GREEK)
            "tr", "az" -> listOf(DOS_TURKISH)
            "pl", "cs", "sk", "hu", "hr", "sl", "ro", "bs", "sq" -> listOf(DOS_CENTRAL)
            else -> emptyList()
        }
        return (first + EVERYWHERE).distinct().filter { it.charset != null }
    }

    /**
     * The code page these names were most likely written in: the best score, and on a tie
     * whichever comes first for this phone. If none decodes them cleanly, which is a damaged
     * archive rather than an unusual one, the first is used and the names come out partly
     * replaced rather than not at all.
     */
    private fun guess(names: List<ByteArray>, locale: Locale): Charset {
        val sample = java.io.ByteArrayOutputStream()
        for (n in names) {
            if (sample.size() + n.size > SAMPLE_BYTES && sample.size() > 0) break
            sample.write(n)
            // A separator that cannot fall inside a character in any of the candidates
            sample.write('\n'.code)
        }
        val bytes = sample.toByteArray()
        val candidates = ordered(locale).ifEmpty { return Charsets.UTF_8 }
        var best = candidates.first()
        var bestScore = -1.0
        for (c in candidates) {
            val s = score(c, bytes)
            if (s > bestScore) {
                best = c
                bestScore = s
            }
        }
        return best.charset!!
    }

    /** A code page a text file may be in, under the name a browser's TextDecoder knows it by. */
    private class Readable(val candidate: Candidate, val label: String)

    // Windows' own code page for each language, since that is what Notepad saved its text in
    // before UTF-8. GB18030 reads every GBK file and the four-byte characters GBK lacks. The
    // double-byte ones are the same Windows supersets as for names, which are what the WHATWG
    // decoders implement under these labels. DOS code pages 437 and 850 are left out: a
    // browser has no decoder for them.
    private val TEXT_GB = Readable(Candidate(Script.GB, "GB18030"), "gb18030")
    private val TEXT_BIG5 = Readable(BIG5, "big5")
    private val TEXT_SJIS = Readable(SJIS, "shift_jis")
    private val TEXT_KOREAN = Readable(UHC, "euc-kr")
    private val TEXT_WIN_CYRILLIC = Readable(WIN_CYRILLIC, "windows-1251")
    private val TEXT_DOS_CYRILLIC = Readable(DOS_CYRILLIC, "ibm866")
    private val TEXT_WESTERN = Readable(Candidate(Script.LATIN, "windows-1252"), "windows-1252")
    private val TEXT_CENTRAL = Readable(Candidate(Script.LATIN, "windows-1250"), "windows-1250")
    private val TEXT_GREEK = Readable(Candidate(Script.GREEK, "windows-1253"), "windows-1253")
    private val TEXT_TURKISH = Readable(Candidate(Script.LATIN, "windows-1254"), "windows-1254")

    /**
     * Every text file is tried in these, in this order when nothing else decides; see
     * [EVERYWHERE]. Western comes before both Cyrillic code pages, unlike for names: a short
     * Western text whose only accented letter stands alone, "à" between spaces, reads under
     * either as "а", which is a Russian word, and the three tie. Russian does not tie the other
     * way, see [proseScore].
     */
    private val TEXT_EVERYWHERE = listOf(
        TEXT_GB, TEXT_SJIS, TEXT_BIG5, TEXT_KOREAN, TEXT_WESTERN, TEXT_WIN_CYRILLIC, TEXT_DOS_CYRILLIC
    )

    /**
     * How many characters in a thousand a text file's guess may have to replace. A long text
     * read in the wrong code page breaks it at nearly every turn, while a file in the right one
     * can still hold a stray byte or two, cut short or pasted in from elsewhere, which turned
     * every candidate down when one bad byte was enough.
     */
    private const val TEXT_REPLACED_PER_MILLE = 5

    /**
     * How many characters outside ASCII a file read as UTF-8 needs to have read for each it
     * could not; see [textCodePage]. Text in a code page reads as UTF-8 only by accident: over
     * 889 samples in 14 languages and their Windows code pages, none read more than 0.41 for
     * each fault, Japanese and Chinese the most.
     */
    private const val UTF8_READ_PER_FAULT = 2

    /**
     * The code page a text file was most likely written in, from its first [bytes], as the
     * label a browser's TextDecoder takes, or null for UTF-8 or when none reads it. Scored the
     * way names are, over code pages a browser can decode. Issue #78.
     *
     * UTF-8 is tried first, and nothing is scored against it: GB18030 reads nearly any run of
     * UTF-8's high bytes without a fault, so a UTF-8 file with one bad byte in it would
     * otherwise come out as a page of wrong hanzi. Its allowance for stray bytes is
     * [UTF8_READ_PER_FAULT] characters read for each one, which holds for a short file too,
     * where one in a thousand is less than one.
     */
    fun textCodePage(bytes: ByteArray, locale: Locale = Locale.getDefault()): String? {
        val utf8 = lenient(Charsets.UTF_8, bytes)
        val faults = utf8.count { it == '\uFFFD' }
        val read = utf8.count { it.code >= 0x80 } - faults
        if (read >= faults * UTF8_READ_PER_FAULT) return null
        return textScoresFor(bytes, locale)
            .fold(null as Pair<String, Double>?) { best, s -> if (best == null || s.second > best.second) s else best }
            ?.first
    }

    /** Whether [text], as some code page read it, had to replace little enough; see [TEXT_REPLACED_PER_MILLE]. */
    private fun replacesLittle(text: String): Boolean {
        val outside = text.count { it.code >= 0x80 }
        val replaced = text.count { it == '\uFFFD' }
        return replaced * 1000 <= outside * TEXT_REPLACED_PER_MILLE
    }

    /** The phone's language first, then everything else, as [ordered] does for names. */
    private fun textOrdered(locale: Locale): List<Readable> {
        val first = when (locale.language) {
            "zh" ->
                if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) {
                    listOf(TEXT_BIG5, TEXT_GB)
                } else {
                    listOf(TEXT_GB, TEXT_BIG5)
                }
            "ja" -> listOf(TEXT_SJIS)
            "ko" -> listOf(TEXT_KOREAN)
            "ru", "uk", "be", "bg", "kk", "ky", "tg", "mn", "mk" -> listOf(TEXT_WIN_CYRILLIC)
            "el" -> listOf(TEXT_GREEK)
            "tr", "az" -> listOf(TEXT_TURKISH)
            "pl", "cs", "sk", "hu", "hr", "sl", "ro", "bs", "sq" -> listOf(TEXT_CENTRAL)
            else -> emptyList()
        }
        return (first + TEXT_EVERYWHERE).distinct()
    }

    /**
     * Each of [candidates] that reads [bytes] without replacing too much, by label, with its
     * score, in the order given.
     */
    private fun textScores(bytes: ByteArray, candidates: List<Readable>): List<Pair<String, Double>> =
        candidates.mapNotNull { r ->
            val charset = r.candidate.charset ?: return@mapNotNull null
            val text = lenient(charset, bytes)
            if (replacesLittle(text)) r.label to score(r.candidate, charset, text, prose = true) else null
        }

    /** For a test to see why: every candidate's score on [bytes], for a phone set to [locale]. */
    internal fun textScoresFor(bytes: ByteArray, locale: Locale): List<Pair<String, Double>> =
        textScores(bytes, textOrdered(locale))

    /** [bytes] as [charset] says, with a replacement character for anything not valid in it. */
    private fun lenient(charset: Charset, bytes: ByteArray): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /**
     * How much of what [c] makes of [bytes] is ordinary text in its language, from 0 to 1, or
     * -1 if the bytes are not valid in it at all. Only characters outside ASCII count, since
     * ASCII reads the same under every candidate and says nothing.
     */
    private fun score(c: Candidate, bytes: ByteArray): Double {
        val charset = c.charset ?: return -1.0
        val text = strict(charset, bytes) ?: return -1.0
        return score(c, charset, text)
    }

    /**
     * [score] for what [charset], [c]'s, already made of the bytes. A replacement character
     * counts 0. [prose] is a text file's body rather than names, see [proseScore].
     */
    private fun score(c: Candidate, charset: Charset, text: String, prose: Boolean = false): Double {
        val encoder = charset.newEncoder()
        var counted = 0
        var sum = 0.0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            if (cp >= 0x80) {
                counted++
                val own = if (c.script.doubleByte) encode(encoder, text, i, width) else null
                val proseOnly = if (prose) proseScore(c, cp, text, i) else null
                sum += when {
                    proseOnly != null -> proseOnly
                    own != null && westernLetterPairedUp(text, i, width, own) -> 0.0
                    else -> when (c.script) {
                        Script.GB -> gb(cp, own)
                        Script.BIG5 -> big5(cp, own)
                        Script.SJIS -> sjis(cp, own)
                        Script.KOREAN -> korean(cp, own)
                        Script.CYRILLIC -> letterOf(Character.UnicodeScript.CYRILLIC, cp, text, i, width)
                        Script.GREEK -> letterOf(Character.UnicodeScript.GREEK, cp, text, i, width)
                        Script.LATIN -> latin(cp)
                    }
                }
            }
            i += width
        }
        return if (counted == 0) 0.0 else sum / counted
    }

    /**
     * The punctuation a text in a European language is set with, which no name needs: the
     * no-break space French puts before a colon, curly quotes and apostrophes, guillemets,
     * dashes, the ellipsis, and the bullet, degree, copyright, registered and euro signs.
     */
    private const val PROSE_PUNCTUATION = "\u00A0«»…‘’“”–—•°©®€"

    /**
     * What a character of a text file scores where that differs from a name's, or null.
     *
     * Its punctuation is as much the language as its letters. Scored 0 as in a name, a French
     * text full of no-break spaces lost to IBM866, which reads that byte as "а", a letter on
     * its own, which Russian has. No word in Cyrillic or Greek turns a capital straight after a
     * small letter, which is how Korean reads under Windows Cyrillic, every byte a letter of
     * either case. And no Western word runs three accented letters together, which is how
     * Russian reads under windows-1252.
     */
    private fun proseScore(c: Candidate, cp: Int, text: String, at: Int): Double? {
        if (c.script.doubleByte) return null
        if (cp <= 0xFFFF && cp.toChar() in PROSE_PUNCTUATION) return 1.0
        if (c.script == Script.CYRILLIC || c.script == Script.GREEK) {
            val before = text.getOrNull(at - 1)
            if (before != null && before.isLetter() && before.isLowerCase() && Character.isUpperCase(cp)) return 0.0
        }
        if (c.script == Script.LATIN && Character.isLetter(cp)) {
            val accented = { ch: Char? -> ch != null && ch.code >= 0x80 && ch.isLetter() }
            if (accented(text.getOrNull(at - 1)) && accented(text.getOrNull(at + 1))) return 0.0
        }
        return null
    }

    /** IBM850 and IBM437, the code pages a Western name from Windows is in; see [westernLetterPairedUp]. */
    private val WESTERN_READINGS = listOfNotNull(DOS_WESTERN.charset, DOS_US.charset)

    /**
     * Whether the character at [at], which is [own] in its code page, is what a Western name's
     * accented letter becomes when the name is read two bytes to a character.
     *
     * The accented letter pairs up with the letter after it, and "Größe" comes out as "Gr批e",
     * "Año" as "A寸": characters as everyday as the letters, so the two readings score the same,
     * and the tie went to the one nobody wrote. It takes two things to give such a character
     * away. It stands alone against a word of ASCII letters, see [wedgedAgainstAnAsciiWord], and
     * its own bytes, read one to a character in IBM850 or IBM437, spell an accented letter and
     * what follows one in a word, see [spellsWestern]. The first alone was the whole test once,
     * and a great many real names put one character against an English word: "PDF版.pdf",
     * "Word檔.docx", "B안.docx" and "A社.pdf" all came out as whatever else their bytes spell,
     * even on phones set to their own language.
     */
    private fun westernLetterPairedUp(text: String, at: Int, width: Int, own: ByteArray): Boolean =
        wedgedAgainstAnAsciiWord(text, at, width) &&
            WESTERN_READINGS.any { spellsWestern(String(own, it), text, at) }

    /**
     * Whether [letters], a character's bytes read one to a character, could be an accented
     * letter and what came after it in a Western name, standing where [at] does in [text].
     *
     * The first has to be a letter, and the second a letter, or the underscore that can follow
     * a word's last letter: "Più_bello". Then the two have to sit in their word the way letters
     * do, in its case, which is where most real names that get that far give themselves away.
     * A capital can start a new word anywhere, as in camel case, so only what no word does is
     * ruled out.
     */
    private fun spellsWestern(letters: String, text: String, at: Int): Boolean {
        if (letters.isEmpty() || letters.length > 2) return false
        val first = letters[0]
        val second = letters.getOrNull(1)
        if (!casedLatin(first)) return false
        if (second != null && !casedLatin(second) && second != '_') return false
        // Italian and French put a grave on u and i only at the end of a word: "Webùp" is Web用
        if (first in "ùì" && second?.isLowerCase() == true) return false
        val before = text.getOrNull(at - 1)?.takeIf(::isAsciiLetter)
        val inCapitals = before?.isUpperCase() == true &&
            text.getOrNull(at - 2)?.let { isAsciiLetter(it) && it.isUpperCase() } == true
        return when {
            // A small letter straight after a word in capitals: "ITòö" is IT部
            first.isLowerCase() && inCapitals -> false
            // A capital then a small letter, the capital after another: "AÄð" is A社
            first.isUpperCase() && second?.isLowerCase() == true && before?.isUpperCase() == true -> false
            // A small letter then an accented capital: "WordãÃ" is Word판
            first.isLowerCase() && second != null && second.code >= 0x80 && second.isUpperCase() -> false
            else -> true
        }
    }

    /** A letter of the Latin alphabet with a capital and a small form, which leaves out ª and º. */
    private fun casedLatin(c: Char): Boolean =
        (c.category == CharCategory.UPPERCASE_LETTER || c.category == CharCategory.LOWERCASE_LETTER) &&
            Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    private fun isAsciiLetter(c: Char): Boolean = c.code < 0x80 && c.isLetter()

    /**
     * Whether the character at [at] stands alone against a word of ASCII letters.
     *
     * That is where a Western name's accented letter lands when the name is read two bytes to a
     * character, see [westernLetterPairedUp]. It is also where a real name in any of these
     * languages puts one character against an English word, so it is not enough on its own.
     */
    private fun wedgedAgainstAnAsciiWord(text: String, at: Int, width: Int): Boolean {
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + width)
        // Another character outside ASCII on either side is a run of them, which is how these
        // languages are written; the space and the dot of a name's own ending are not.
        if (before != null && before.code >= 0x80) return false
        if (after != null && after.code >= 0x80) return false
        val asciiLetter = { ch: Char? -> ch != null && ch.code < 0x80 && ch.isLetter() }
        return asciiLetter(before) || asciiLetter(after)
    }

    /** One character's bytes in the candidate's own code page. */
    private fun encode(encoder: CharsetEncoder, text: String, at: Int, width: Int): ByteArray? =
        try {
            val out = encoder.reset().encode(CharBuffer.wrap(text, at, at + width))
            ByteArray(out.remaining()).also { out.get(it) }
        } catch (_: CharacterCodingException) {
            null
        }

    private fun code(b: ByteArray): Int = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)

    /**
     * GB2312's first level is the 3,755 hanzi in everyday use, and real Chinese names are
     * almost entirely made of them. GBK's additions are rare characters, and they are where
     * the lead bytes of other double-byte code pages land: Shift_JIS read as GBK comes out
     * in them nearly every time.
     */
    private fun gb(cp: Int, b: ByteArray?): Double {
        if (b == null || b.size != 2) return 0.0
        val lead = b[0].toInt() and 0xFF
        val trail = b[1].toInt() and 0xFF
        if (trail !in 0xA1..0xFE) return 0.0
        return when (lead) {
            in 0xB0..0xD7 -> everyday(cp, commonSimplified)
            in 0xD8..0xF7 -> 0.5
            // Punctuation, and the full-width forms of ASCII
            0xA1, 0xA3 -> 0.5
            // Kana, Greek, Cyrillic and box drawing: in GB2312, never in a Chinese name
            else -> 0.0
        }
    }

    /** Big5 splits its hanzi the same way: 5,401 in common use, then 7,652 less so. */
    private fun big5(cp: Int, b: ByteArray?): Double {
        if (b == null || b.size != 2) return 0.0
        return when (code(b)) {
            in 0xA440..0xC67E -> everyday(cp, commonTraditional)
            in 0xC940..0xF9D5 -> 0.5
            in 0xA140..0xA3BF -> 0.5
            else -> 0.0
        }
    }

    /** Kana is Japanese and nothing else; kanji split like the hanzi above, at JIS level 1. */
    private fun sjis(cp: Int, b: ByteArray?): Double {
        if (cp in 0x3041..0x30FF) return 1.0
        if (b == null) return 0.0
        // Half-width katakana: genuine, and a sign of an old machine
        if (b.size == 1) return 0.25
        return when (code(b)) {
            in 0x889F..0x9872 -> 1.0
            in 0x989F..0x9FFC, in 0xE040..0xEAA4 -> 0.5
            in 0x8140..0x81FC, in 0x824F..0x829A -> 0.5
            else -> 0.0
        }
    }

    /** The 2,350 Hangul syllables of KS X 1001 are the ones Korean is written in. */
    private fun korean(cp: Int, b: ByteArray?): Double {
        if (b == null || b.size != 2) return 0.0
        val lead = b[0].toInt() and 0xFF
        val trail = b[1].toInt() and 0xFF
        val standard = trail in 0xA1..0xFE
        return when {
            cp in 0xAC00..0xD7A3 -> if (standard && lead in 0xB0..0xC8) everyday(cp, commonKorean) else 0.5
            standard && lead in 0xCA..0xFD -> 0.25
            standard && lead in 0xA1..0xA4 -> 0.5
            else -> 0.0
        }
    }

    /** An accented Latin letter, which sits inside a word of ASCII ones as often as not. */
    private fun latin(cp: Int): Double =
        if (Character.isLetter(cp) &&
            Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
        ) 1.0 else 0.0

    /**
     * A letter of [script] that is not inside a word of ASCII letters. The second test is
     * what tells a Western name from a Cyrillic one: café written in code page 437 reads
     * under CP866 as "cafВ", every character of it a letter.
     */
    private fun letterOf(
        script: Character.UnicodeScript,
        cp: Int,
        text: String,
        at: Int,
        width: Int,
    ): Double {
        if (!Character.isLetter(cp) || Character.UnicodeScript.of(cp) != script) return 0.0
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + width)
        val asciiLetter = { ch: Char? -> ch != null && ch.code < 0x80 && ch.isLetter() }
        return if (asciiLetter(before) || asciiLetter(after)) 0.0 else 1.0
    }
}
