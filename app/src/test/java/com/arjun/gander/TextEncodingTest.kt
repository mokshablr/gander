package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Locale
import org.junit.Test

/**
 * What a text file that does not say is read in. Issue #78.
 *
 * The bytes are built here by the JVM's codecs, which is fair for what is under test: not the
 * codecs, but the choice of which one the page decodes with. The guess was scored on 889
 * samples of real text in 14 languages before these were written; these hold the cases that
 * taught it something.
 */
class TextEncodingTest {

    private val english = Locale.US
    private val chinese = Locale.SIMPLIFIED_CHINESE
    private val taiwan = Locale.TRADITIONAL_CHINESE
    private val japanese = Locale.JAPAN
    private val korean = Locale.KOREA
    private val russian = Locale.forLanguageTag("ru-RU")
    private val french = Locale.FRANCE
    private val greek = Locale.forLanguageTag("el-GR")

    private val simplified =
        "这是一个用记事本保存的文本文件。我们在会议上讨论了下一季度的计划，" +
            "并决定在北京和上海各开一家新的办公室。请在星期五之前把报告发给我。\r\n"
    private val traditional =
        "這是一個用記事本儲存的文字檔案。我們在會議上討論了下一季的計畫，" +
            "並決定在台北和高雄各開一家新的辦公室。請在星期五之前把報告寄給我。\r\n"
    private val japaneseText =
        "これはメモ帳で保存したテキストファイルです。会議では来期の計画について話し合い、" +
            "東京と大阪に新しい事務所を開くことに決めました。金曜日までに報告書を送ってください。\r\n"
    private val koreanText =
        "이것은 메모장으로 저장한 텍스트 파일입니다. 회의에서 다음 분기 계획을 논의했고 " +
            "서울과 부산에 새 사무실을 열기로 했습니다. 금요일까지 보고서를 보내 주세요.\r\n"
    private val russianText =
        "Это текстовый файл, сохранённый в Блокноте. На совещании мы обсудили планы " +
            "на следующий квартал и решили открыть новые офисы в Москве и Казани.\r\n"

    private fun guess(text: String, charset: String, locale: Locale): String? =
        TextEncoding.guess(text.toByteArray(Charset.forName(charset)), locale)

    // ---------------------------------------------------------------
    // Saved by Windows in its own code page
    // ---------------------------------------------------------------

    @Test
    fun chineseSavedAsGbkReadsAsGb18030WhateverThePhoneIsSetTo() {
        listOf(chinese, english, russian, japanese).forEach { locale ->
            assertWithMessage("$locale").that(guess(simplified, "GBK", locale)).isEqualTo("gb18030")
        }
    }

    /** GBK has no character outside the Basic Multilingual Plane; GB18030 writes one in four bytes. */
    @Test
    fun chineseWithGb18030sFourByteCharactersReadsAsGb18030() {
        val text = simplified + "生僻字：𠀀𠀁，还有表情😀。\r\n"
        assertThat(guess(text, "GB18030", english)).isEqualTo("gb18030")
    }

    @Test
    fun traditionalChineseReadsAsBig5() {
        listOf(taiwan, english).forEach { locale ->
            assertWithMessage("$locale").that(guess(traditional, "Big5", locale)).isEqualTo("big5")
        }
    }

    @Test
    fun japaneseReadsAsShiftJis() {
        listOf(japanese, english, chinese).forEach { locale ->
            assertWithMessage("$locale").that(guess(japaneseText, "windows-31j", locale)).isEqualTo("shift_jis")
        }
    }

    @Test
    fun koreanReadsAsEucKr() {
        listOf(korean, english, chinese).forEach { locale ->
            assertWithMessage("$locale").that(guess(koreanText, "x-windows-949", locale)).isEqualTo("euc-kr")
        }
    }

    /**
     * Hanja, and syllables only Windows' Korean code page has, which score less than everyday
     * Hangul. Read as Windows Cyrillic every byte is a letter, of either case at random, and
     * that won until a capital straight after a small letter stopped counting.
     */
    @Test
    fun koreanWithHanjaAndRareSyllablesIsNotTakenForCyrillic() {
        val chat = "오늘 仁川 女中 앞에서 家族 모임!! 九月 因緣 亞洲 食堂 갔다 ㅋㅋ 똠 펲 훀 즼 앜 ☆\r\n"
        listOf(english, korean).forEach { locale ->
            assertWithMessage("$locale").that(guess(chat, "x-windows-949", locale)).isEqualTo("euc-kr")
        }
    }

    @Test
    fun russianReadsAsWindowsCyrillic() {
        listOf(russian, english).forEach { locale ->
            assertWithMessage("$locale").that(guess(russianText, "windows-1251", locale)).isEqualTo("windows-1251")
        }
    }

    /** Under windows-1252 Russian is accented Latin letters, which no Western word runs three of. */
    @Test
    fun shortRussianIsNotTakenForWestern() {
        assertThat(guess("Ремонт и уход за домом\r\n", "windows-1251", english)).isEqualTo("windows-1251")
    }

    @Test
    fun russianFromDosReadsAsIbm866() {
        assertThat(guess(russianText, "IBM866", english)).isEqualTo("ibm866")
    }

    /**
     * A no-break space, which French sets before a colon, is a lone "а" under IBM866, and
     * Russian has that word. Scored 0, as in a name, the punctuation handed the file to it.
     */
    @Test
    fun frenchSetWithItsOwnPunctuationReadsAsWindowsWestern() {
        val text = "Prix : 12 € « à emporter » – voilà !\r\n"
        listOf(french, english).forEach { locale ->
            assertWithMessage("$locale").that(guess(text, "windows-1252", locale)).isEqualTo("windows-1252")
        }
    }

    /**
     * "à" on its own reads in either Cyrillic code page as "а", a Russian word, and the three
     * tie; on a phone not set to a Cyrillic language, Western goes first.
     */
    @Test
    fun aWesternTextWhoseOnlyAccentStandsAloneReadsAsWesternUnlessThePhoneIsRussian() {
        val text = "On va à la plage.\r\n"
        assertThat(guess(text, "windows-1252", english)).isEqualTo("windows-1252")
        assertThat(guess(text, "windows-1252", french)).isEqualTo("windows-1252")
        assertThat(guess(text, "windows-1252", russian)).isEqualTo("windows-1251")
    }

    /** A Greek phone's own code page; its accented small letters fall on Cyrillic capitals. */
    @Test
    fun greekReadsAsWindowsGreekOnAGreekPhone() {
        val text = "Αυτό είναι ένα αρχείο κειμένου. Στη συνάντηση συζητήσαμε τα σχέδια για " +
            "το επόμενο τρίμηνο και αποφασίσαμε να ανοίξουμε γραφεία στην Αθήνα.\r\n"
        assertThat(guess(text, "windows-1253", greek)).isEqualTo("windows-1253")
    }

    // ---------------------------------------------------------------
    // Left to UTF-8
    // ---------------------------------------------------------------

    @Test
    fun utf8IsLeftAlone() {
        listOf(simplified, japaneseText, russianText, "café crème\r\n").forEach { text ->
            assertWithMessage(text).that(guess(text, "UTF-8", chinese)).isNull()
        }
    }

    @Test
    fun plainAsciiIsLeftAlone() {
        assertThat(guess("Nothing but ASCII here.\r\n", "US-ASCII", chinese)).isNull()
    }

    /**
     * GB18030 reads almost any run of UTF-8's high bytes without a fault, so one bad byte in a
     * UTF-8 file must not hand it a page of wrong hanzi. Short files too, where it is one in 70.
     */
    @Test
    fun utf8WithAStrayByteStaysUtf8() {
        listOf(simplified, simplified.repeat(40), "Grüße aus Köln, schöne Tage.\r\n").forEach { text ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            val broken = bytes.copyOfRange(0, bytes.size / 2) + 0xFF.toByte() + bytes.copyOfRange(bytes.size / 2, bytes.size)
            assertWithMessage(text.take(10)).that(TextEncoding.guess(broken, chinese)).isNull()
        }
    }

    @Test
    fun aByteOrderMarkDecides() {
        val gbk = simplified.toByteArray(Charset.forName("GBK"))
        listOf(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            .forEach { mark -> assertThat(TextEncoding.guess(mark + gbk, chinese)).isNull() }
    }

    // ---------------------------------------------------------------
    // The head of a long file
    // ---------------------------------------------------------------

    /** A file in its code page can still hold a stray byte, cut short or pasted in. */
    @Test
    fun aGbkFileWithAStrayByteIsStillGbk() {
        val bytes = simplified.repeat(20).toByteArray(Charset.forName("GBK"))
        val broken = bytes.copyOfRange(0, 101) + 0xFF.toByte() + bytes.copyOfRange(101, bytes.size)
        assertThat(TextEncoding.guess(broken, english)).isEqualTo("gb18030")
    }

    /** The head ends where it ends, often inside a character, which is no fault of the file's. */
    @Test
    fun aHeadCutThroughACharacterIsStillGbk() {
        // No line breaks or spaces, so the head ends mid-character half the time; both are tried
        val line = simplified.trim()
        listOf(0, 1).forEach { shift ->
            val bytes = ("a".repeat(shift) + line.repeat(TextEncoding.HEAD_BYTES / line.length + 10))
                .toByteArray(Charset.forName("GBK"))
            val head = TextEncoding.readHead(ByteArrayInputStream(bytes))
            assertThat(head.size).isEqualTo(TextEncoding.HEAD_BYTES)
            assertWithMessage("shift $shift").that(TextEncoding.guess(head, english)).isEqualTo("gb18030")
        }
    }

    @Test
    fun aHeadIsTheWholeOfAShortFile() {
        val bytes = simplified.toByteArray(Charset.forName("GBK"))
        assertThat(TextEncoding.readHead(ByteArrayInputStream(bytes))).isEqualTo(bytes)
    }
}
