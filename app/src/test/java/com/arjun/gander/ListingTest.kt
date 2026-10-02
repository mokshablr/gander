package com.arjun.gander

import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import kotlin.math.pow
import org.junit.Test

/**
 * The home screen's badges and its folder ordering.
 *
 * The contrast assertions are the interesting half. Play's pre-launch report
 * runs an accessibility scan, and a badge that fails it is both a listing
 * problem and a real one for anybody reading in sunlight. The numbers in
 * Listing.kt's KDoc were measured; this recomputes them, so editing a colour
 * without checking is caught here rather than by Google.
 */
class ListingTest {

    private companion object {
        /** WCAG 2.1 relative luminance. */
        fun luminance(argb: Int): Double {
            fun channel(c: Int): Double {
                val s = c / 255.0
                return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel((argb shr 16) and 0xFF) +
                0.7152 * channel((argb shr 8) and 0xFF) +
                0.0722 * channel(argb and 0xFF)
        }

        /** Every badge label is white, so white is the only ratio that matters. */
        fun contrastWithWhite(argb: Int): Double = 1.05 / (luminance(argb) + 0.05)

        /** What WCAG AA asks of 12sp bold, which is what a badge label is. */
        const val AA = 4.5

        val ALL_BADGES: Map<String, Int> =
            (WELCOME_BADGES + TXT_BADGE + FILE_BADGE + ZIP_BADGE + MODEL_BADGE).toMap() +
                mapOf("DIR" to DIR_COLOR, "ADD" to ADD_COLOR)
    }

    // ---------------------------------------------------------------
    // Contrast
    // ---------------------------------------------------------------

    @Test
    fun everyBadgeClearsWcagAaAgainstItsWhiteLabel() {
        ALL_BADGES.forEach { (label, color) ->
            val ratio = contrastWithWhite(color)
            assertThat("$label ${"%.2f".format(ratio)} >= $AA")
                .isEqualTo("$label ${"%.2f".format(ratio)} ${if (ratio >= AA) ">=" else "<"} $AA")
        }
    }

    /**
     * The figures Listing.kt's KDoc quotes, recomputed. If one of these moves,
     * the comment beside the palette is now describing a colour that is not
     * there any more.
     */
    @Test
    fun theMeasuredRatiosAreStillWhatTheCommentSays() {
        val documented = mapOf(
            "PDF" to 6.54, "PPT" to 5.20, "FILE" to 4.65,
            "DIR" to 4.90, "ADD" to 6.54, "3D" to 7.91,
        )
        documented.forEach { (label, expected) ->
            assertThat(contrastWithWhite(ALL_BADGES.getValue(label)))
                .isWithin(0.01).of(expected)
        }
    }

    /**
     * AUD is the tightest in the set at 4.52, two hundredths above the line.
     * Pinned so that darkening or lightening it by a shade is a deliberate act
     * rather than something noticed in a Play report weeks later.
     */
    @Test
    fun audioIsTheTightestBadgeAndStillPasses() {
        val worst = ALL_BADGES.minBy { contrastWithWhite(it.value) }
        assertThat(worst.key).isEqualTo("AUD")
        assertThat(contrastWithWhite(worst.value)).isWithin(0.01).of(4.52)
        assertThat(contrastWithWhite(worst.value)).isAtLeast(AA)
    }

    // ---------------------------------------------------------------
    // badgeFor
    // ---------------------------------------------------------------

    @Test
    fun eachKindGetsItsOwnBadge() {
        val expected = mapOf(
            "lease.pdf" to PDF_BADGE,
            "report.docx" to DOC_BADGE,
            "budget.xlsx" to XLS_BADGE,
            "notes.csv" to XLS_BADGE,
            "deck.pptx" to PPT_BADGE,
            "holiday.jpg" to IMG_BADGE,
            "sketch.svg" to IMG_BADGE,
            "clip.mp4" to VID_BADGE,
            "song.mp3" to AUD_BADGE,
            "readme.md" to MD_BADGE,
            "main.kt" to TXT_BADGE,
            "photos.zip" to ZIP_BADGE,
            "bracket.stl" to MODEL_BADGE,
        )
        expected.forEach { (name, badge) ->
            assertThat(badgeFor(name, null)).isEqualTo(badge)
        }
    }

    /**
     * A model that arrives with no name to go on still says what it is, by the type Android
     * gives a .stl. 3D rather than STL, because a model is what the file is, and STL is only
     * one way of writing one down.
     */
    @Test
    fun aModelIsLabelled3dWhateverItIsCalled() {
        assertThat(MODEL_BADGE.first).isEqualTo("3D")
        assertThat(badgeFor("attachment", "application/vnd.ms-pki.stl")).isEqualTo(MODEL_BADGE)
        assertThat(MODEL_BADGE.second).isNotEqualTo(DOC_BADGE.second)
        assertThat(MODEL_BADGE.second).isNotEqualTo(IMG_BADGE.second)
    }

    /**
     * A zip wears the folder's colour, because it behaves as one: it opens as a list to go
     * into. Issue #30. Its label is its own, so it is never mistaken for a folder on the phone.
     */
    @Test
    fun aZipIsAFolderColouredZip() {
        assertThat(ZIP_BADGE.first).isEqualTo("ZIP")
        assertThat(ZIP_BADGE.second).isEqualTo(DIR_COLOR)
        // Declared after DIR_COLOR, so it is not the zero it would read as before it
        assertThat(ZIP_BADGE.second).isNotEqualTo(0)
        assertThat(badgeFor("attachment", "application/zip")).isEqualTo(ZIP_BADGE)
    }

    /**
     * Audio and video share a kind and are told apart by extension, because a
     * file with no picture in it should not promise one.
     */
    @Test
    fun audioAndVideoAreSeparatedWithinTheOnePlayerKind() {
        assertThat(badgeFor("song.flac", null)).isEqualTo(AUD_BADGE)
        assertThat(badgeFor("clip.mkv", null)).isEqualTo(VID_BADGE)
    }

    @Test
    fun anythingUnrecognisedFallsBackToFile() {
        assertThat(badgeFor("backup.rar", null)).isEqualTo(FILE_BADGE)
        assertThat(badgeFor("noextension", null)).isEqualTo(FILE_BADGE)
        assertThat(badgeFor("slides.ppt", null)).isEqualTo(FILE_BADGE)
    }

    @Test
    fun theThreeOlderWordProcessorFormatsShareTheWordBadge() {
        assertThat(badgeFor("legacy.doc", null)).isEqualTo(DOC_BADGE)
        assertThat(badgeFor("minutes.rtf", null)).isEqualTo(DOC_BADGE)
        assertThat(badgeFor("letter.odt", null)).isEqualTo(DOC_BADGE)
    }

    @Test
    fun theExtensionIsReadWhateverItsCase() {
        assertThat(badgeFor("LEASE.PDF", null)).isEqualTo(PDF_BADGE)
        assertThat(badgeFor("Holiday.JPG", null)).isEqualTo(IMG_BADGE)
    }

    /** The provider's MIME type is used when the name carries no extension. */
    @Test
    fun theMimeTypeAnswersWhenTheNameDoesNot() {
        assertThat(badgeFor("scan", "application/pdf")).isEqualTo(PDF_BADGE)
        assertThat(badgeFor("sheet", "text/csv")).isEqualTo(XLS_BADGE)
    }

    // ---------------------------------------------------------------
    // The welcome grid
    // ---------------------------------------------------------------

    @Test
    fun theWelcomeGridShowsNineDistinctKinds() {
        assertThat(WELCOME_BADGES).hasSize(9)
        assertThat(WELCOME_BADGES.map { it.first }).containsNoDuplicates()
        assertThat(WELCOME_BADGES.map { it.second }).containsNoDuplicates()
    }

    /** FILE is what an unsupported file falls back to, and this grid is a list of what Gander opens. */
    @Test
    fun theWelcomeGridDoesNotAdvertiseTheFallback() {
        assertThat(WELCOME_BADGES).doesNotContain(FILE_BADGE)
        assertThat(WELCOME_BADGES.map { it.first }).doesNotContain("FILE")
    }

    /**
     * Every tile but the last is a badge some real file would actually get, and the last,
     * ETC, stands for exactly the kinds left without a tile of their own.
     */
    @Test
    fun everyWelcomeTileIsReachableFromSomeFile() {
        val reachable = listOf(
            "a.pdf", "a.docx", "a.xlsx", "a.pptx", "a.jpg",
            "a.mp4", "a.mp3", "a.md", "a.txt", "a.zip",
        ).map { badgeFor(it, null) }
        assertThat(WELCOME_BADGES.last()).isEqualTo(ETC_BADGE)
        assertThat(reachable).containsAtLeastElementsIn(WELCOME_BADGES.dropLast(1))
        assertThat(reachable - WELCOME_BADGES.toSet()).containsExactly(TXT_BADGE, ZIP_BADGE)
    }

    // ---------------------------------------------------------------
    // orderChildren
    // ---------------------------------------------------------------

    private fun dir(name: String) = ChildDoc("id-$name", name, MIME_DIR, 0, 0)
    private fun file(name: String) = ChildDoc("id-$name", name, "application/pdf", 10, 0)

    @Test
    fun directoriesComeBeforeFiles() {
        val (dirs, files) = orderChildren(
            listOf(file("zeta.pdf"), dir("Alpha"), file("alpha.pdf"), dir("Zeta"))
        )
        assertThat(dirs.map { it.name }).containsExactly("Alpha", "Zeta").inOrder()
        assertThat(files.map { it.name }).containsExactly("alpha.pdf", "zeta.pdf").inOrder()
    }

    @Test
    fun bothListsSortCaseInsensitively() {
        val (_, files) = orderChildren(
            listOf(file("banana.pdf"), file("Apple.pdf"), file("cherry.pdf"))
        )
        assertThat(files.map { it.name })
            .containsExactly("Apple.pdf", "banana.pdf", "cherry.pdf").inOrder()
    }

    @Test
    fun dotfilesAreHiddenFromBothLists() {
        val (dirs, files) = orderChildren(
            listOf(dir(".git"), dir("src"), file(".gitignore"), file("readme.md"))
        )
        assertThat(dirs.map { it.name }).containsExactly("src")
        assertThat(files.map { it.name }).containsExactly("readme.md")
    }

    @Test
    fun anEmptyFolderProducesTwoEmptyLists() {
        val (dirs, files) = orderChildren(emptyList())
        assertThat(dirs).isEmpty()
        assertThat(files).isEmpty()
    }

    /**
     * The directory MIME type is spelled out in Listing.kt so the ordering can
     * be tested off-device. It has to stay the one the framework uses.
     */
    @Test
    fun theDirectoryMimeTypeMatchesTheFrameworkConstant() {
        assertThat(MIME_DIR).isEqualTo(DocumentsContract.Document.MIME_TYPE_DIR)
    }

    private fun fileWith(name: String, size: Long, modified: Long) =
        ChildDoc("id-$name", name, "application/pdf", size, modified)

    @Test
    fun sortByNameDescending() {
        val (_, files) = orderChildren(
            listOf(file("Apple.pdf"), file("cherry.pdf"), file("banana.pdf")),
            sortType = Settings.SortType.NAME,
            ascending = false
        )
        assertThat(files.map { it.name })
            .containsExactly("cherry.pdf", "banana.pdf", "Apple.pdf").inOrder()
    }

    @Test
    fun sortByDateAscendingAndDescending() {
        val f1 = fileWith("old.pdf", 100, 1000L)
        val f2 = fileWith("mid.pdf", 200, 2000L)
        val f3 = fileWith("new.pdf", 300, 3000L)

        val (_, asc) = orderChildren(
            listOf(f2, f3, f1),
            sortType = Settings.SortType.DATE,
            ascending = true
        )
        assertThat(asc.map { it.name }).containsExactly("old.pdf", "mid.pdf", "new.pdf").inOrder()

        val (_, desc) = orderChildren(
            listOf(f2, f3, f1),
            sortType = Settings.SortType.DATE,
            ascending = false
        )
        assertThat(desc.map { it.name }).containsExactly("new.pdf", "mid.pdf", "old.pdf").inOrder()
    }

    @Test
    fun sortBySizeAscendingAndDescending() {
        val f1 = fileWith("small.pdf", 100, 1000L)
        val f2 = fileWith("medium.pdf", 500, 1000L)
        val f3 = fileWith("large.pdf", 1000, 1000L)

        val (_, asc) = orderChildren(
            listOf(f2, f3, f1),
            sortType = Settings.SortType.SIZE,
            ascending = true
        )
        assertThat(asc.map { it.name }).containsExactly("small.pdf", "medium.pdf", "large.pdf").inOrder()

        val (_, desc) = orderChildren(
            listOf(f2, f3, f1),
            sortType = Settings.SortType.SIZE,
            ascending = false
        )
        assertThat(desc.map { it.name }).containsExactly("large.pdf", "medium.pdf", "small.pdf").inOrder()
    }

    @Test
    fun supportedFilterTypesCoversAllElevenFormats() {
        val expectedBadges = listOf("PDF", "DOC", "XLS", "PPT", "IMG", "VID", "AUD", "MD", "TXT", "3D", "ZIP")
        assertThat(SUPPORTED_FILTER_TYPES.map { it.badge }).containsExactlyElementsIn(expectedBadges).inOrder()
    }
}
