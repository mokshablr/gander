package com.arjun.gander

import com.arjun.gander.FileKind.ARCHIVE
import com.arjun.gander.FileKind.DOCX
import com.arjun.gander.FileKind.EPUB
import com.arjun.gander.FileKind.IMAGE
import com.arjun.gander.FileKind.IMAGE_WEB
import com.arjun.gander.FileKind.MD
import com.arjun.gander.FileKind.MODEL
import com.arjun.gander.FileKind.PDF
import com.arjun.gander.FileKind.PDF_SIMPLE
import com.arjun.gander.FileKind.PLAYER
import com.arjun.gander.FileKind.PPTX
import com.arjun.gander.FileKind.PROSE
import com.arjun.gander.FileKind.TEXT
import com.arjun.gander.FileKind.UNSUPPORTED
import com.arjun.gander.FileKind.XLSX
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The format registry, pinned extension by extension.
 *
 * [EXPECTED] is written out in full rather than derived from the sets in
 * [FileKind], because a test that reads the same data as the code under test
 * agrees with it whatever either one says. Adding a format means adding a line
 * here, and that is the point: the table is the reviewable record of what
 * Gander claims to open.
 */
class FileKindTest {

    private companion object {
        /** Every extension the app routes, and where it goes. */
        val EXPECTED: Map<String, FileKind> = mapOf(
            // Photos, drawn by the native tiling view
            "jpg" to IMAGE, "jpeg" to IMAGE, "png" to IMAGE, "webp" to IMAGE,
            "bmp" to IMAGE, "heic" to IMAGE, "heif" to IMAGE,

            // Images the WebView decodes better than the region decoder does
            "gif" to IMAGE_WEB, "svg" to IMAGE_WEB, "avif" to IMAGE_WEB,
            "ico" to IMAGE_WEB,

            "pdf" to PDF,

            // Video
            "mp4" to PLAYER, "m4v" to PLAYER, "mov" to PLAYER, "mkv" to PLAYER,
            "webm" to PLAYER, "3gp" to PLAYER, "3g2" to PLAYER, "m2ts" to PLAYER,
            "mts" to PLAYER, "avi" to PLAYER, "flv" to PLAYER,

            // Audio
            "mp3" to PLAYER, "m4a" to PLAYER, "aac" to PLAYER, "flac" to PLAYER,
            "wav" to PLAYER, "ogg" to PLAYER, "oga" to PLAYER, "opus" to PLAYER,
            "amr" to PLAYER,

            "docx" to DOCX,

            // Word's relatives: the same package with the main part declared otherwise
            "docm" to DOCX, "dotx" to DOCX,

            // Read by Gander's own readers, issues #4 and #13; the page asks the bytes
            "odt" to PROSE, "ott" to PROSE, "fodt" to PROSE, "rtf" to PROSE,
            "doc" to PROSE, "dot" to PROSE,

            // Spreadsheets. csv is here and not in the text list: see
            // csvIsASpreadsheetBecauseSheetsAreCheckedFirst below.
            "xlsx" to XLSX, "xls" to XLSX, "xlsm" to XLSX, "xlsb" to XLSX,
            "xltx" to XLSX, "csv" to XLSX, "ods" to XLSX,

            "pptx" to PPTX, "ppsx" to PPTX, "pptm" to PPTX, "potx" to PPTX,

            "md" to MD, "markdown" to MD,

            // Text and code
            "txt" to TEXT, "log" to TEXT, "json" to TEXT, "xml" to TEXT,
            "yaml" to TEXT, "yml" to TEXT, "kt" to TEXT, "java" to TEXT,
            "py" to TEXT, "js" to TEXT, "ts" to TEXT, "html" to TEXT,
            "htm" to TEXT, "css" to TEXT, "sh" to TEXT, "zsh" to TEXT,
            "bash" to TEXT, "c" to TEXT, "cpp" to TEXT, "h" to TEXT,
            "hpp" to TEXT, "rs" to TEXT, "go" to TEXT, "rb" to TEXT,
            "php" to TEXT, "sql" to TEXT, "swift" to TEXT, "dart" to TEXT,
            "gradle" to TEXT, "properties" to TEXT, "toml" to TEXT,
            "ini" to TEXT, "cfg" to TEXT, "conf" to TEXT, "tex" to TEXT,
            "r" to TEXT,

            // Text under other names: subtitles, a playlist, a download's notes
            "srt" to TEXT, "vtt" to TEXT, "m3u" to TEXT, "nfo" to TEXT,

            // Hardware description languages: Verilog, SystemVerilog, VHDL
            "v" to TEXT, "vh" to TEXT, "sv" to TEXT, "svh" to TEXT,
            "vhd" to TEXT, "vhdl" to TEXT,

            // A Graphviz graph, issue #58. A .dot goes to PROSE by its name and is told by its
            // bytes in the viewer: see ReadsAsTextTest.
            "gv" to TEXT,

            // Listed rather than drawn, issue #30
            "zip" to ARCHIVE,

            // A 3D model, the file a 3D printer's slicer takes
            "stl" to MODEL,

            // A book, issue #50
            "epub" to EPUB,
        )

        const val MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val MIME_ODS = "application/vnd.oasis.opendocument.spreadsheet"
        const val MIME_DOCM = "application/vnd.ms-word.document.macroEnabled.12"
        const val MIME_PPTM = "application/vnd.ms-powerpoint.presentation.macroEnabled.12"
    }

    @Test
    fun everyKnownExtensionRoutesToItsKind() {
        EXPECTED.forEach { (ext, kind) ->
            assertThat(FileKind.detect(ext, null)).isEqualTo(kind)
        }
    }

    /** A count, so a silently deleted table row is noticed. */
    @Test
    fun theTableCoversOneHundredAndFourExtensions() {
        assertThat(EXPECTED).hasSize(104)
    }

    /**
     * About's File types page lists every extension in the table and no other, so it can't leave
     * a format out or name one Gander turns away. Issue #61.
     */
    @Test
    fun theFileTypesPageListsEveryExtensionInTheTableAndNoOther() {
        val page = File("src/main/assets/file_types.md").readText()
        // A dot that starts a word, as in ".docx", and not the full stop in "text."
        val listed = Regex("""(?<![\w.])\.([a-z0-9]+)\b""").findAll(page).map { it.groupValues[1] }.toSet()
        assertThat(listed).containsExactlyElementsIn(EXPECTED.keys)
    }

    /**
     * Every kind is some extension's but two: UNSUPPORTED, which is what nothing else is, and a
     * PDF's simple view, which the viewer picks by the phone and the file, never by a name. See
     * pdfRoute.
     */
    @Test
    fun everyKindButTheTwoChosenOtherwiseIsReachableFromSomeExtension() {
        val reached = EXPECTED.values.toSet()
        val unreachable = FileKind.entries.toSet() - reached
        assertThat(unreachable).containsExactly(UNSUPPORTED, PDF_SIMPLE)
    }

    // ---------------------------------------------------------------
    // The ordering rules inside detect(), each of which is load bearing
    // ---------------------------------------------------------------

    /**
     * .ts is TypeScript far more often than it is a transport stream, so it
     * reads as text unless the provider positively says otherwise. That branch
     * is first in the when, ahead of every extension set.
     */
    @Test
    fun tsIsTypeScriptUnlessTheProviderCallsItVideo() {
        assertThat(FileKind.detect("ts", null)).isEqualTo(TEXT)
        assertThat(FileKind.detect("ts", "text/plain")).isEqualTo(TEXT)
        assertThat(FileKind.detect("ts", "video/mp2t")).isEqualTo(PLAYER)
    }

    /**
     * The extension is trusted over the MIME type throughout. A provider that
     * mislabels a text file as a PDF does not get to send it to the PDF
     * renderer, which would fail where the text viewer succeeds.
     */
    @Test
    fun theExtensionBeatsTheMimeType() {
        assertThat(FileKind.detect("txt", "application/pdf")).isEqualTo(TEXT)
        assertThat(FileKind.detect("pdf", "text/plain")).isEqualTo(PDF)
        assertThat(FileKind.detect("png", "application/octet-stream")).isEqualTo(IMAGE)
    }

    /**
     * csv appears in both the spreadsheet and the text sets, and the
     * spreadsheet branch is checked first, so it opens as a sheet. The text
     * membership is unreachable. Pinned because it looks like an accident and
     * is not: a CSV is more useful in a grid than in a monospace block.
     */
    @Test
    fun csvIsASpreadsheetBecauseSheetsAreCheckedFirst() {
        assertThat(FileKind.detect("csv", null)).isEqualTo(XLSX)
        assertThat(FileKind.detect("csv", "text/csv")).isEqualTo(XLSX)
    }

    // ---------------------------------------------------------------
    // The MIME fallbacks, which are all that is left when a share sheet
    // hands over a name with no extension on it
    // ---------------------------------------------------------------

    @Test
    fun mimeTypesRouteWhenThereIsNoExtension() {
        val byMime = mapOf(
            "application/pdf" to PDF,
            "video/mp4" to PLAYER,
            "audio/mpeg" to PLAYER,
            MIME_DOCX to DOCX,
            MIME_DOCM to DOCX,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template" to DOCX,
            "application/vnd.oasis.opendocument.text" to PROSE,
            "application/msword" to PROSE,
            "application/rtf" to PROSE,
            "text/rtf" to PROSE,
            MIME_XLSX to XLSX,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template" to XLSX,
            "application/vnd.ms-excel" to XLSX,
            "text/csv" to XLSX,
            MIME_ODS to XLSX,
            MIME_PPTX to PPTX,
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to PPTX,
            MIME_PPTM to PPTX,
            "application/vnd.openxmlformats-officedocument.presentationml.template" to PPTX,
            "image/png" to IMAGE_WEB,
            "text/plain" to TEXT,
            "application/json" to TEXT,
            "application/xml" to TEXT,
            "application/x-subrip" to TEXT,
            "text/vtt" to TEXT,
            "text/x-nfo" to TEXT,
            "application/zip" to ARCHIVE,
            "application/x-zip-compressed" to ARCHIVE,
            "model/stl" to MODEL,
            "model/x.stl-binary" to MODEL,
            "model/x.stl-ascii" to MODEL,
            "application/sla" to MODEL,
            "application/vnd.ms-pki.stl" to MODEL,
        )
        byMime.forEach { (mime, kind) ->
            assertThat(FileKind.detect("", mime)).isEqualTo(kind)
        }
    }

    /**
     * An STL is a model whatever it is labelled, and a text one is often labelled text. Only
     * the STL types are taken for one: model/ also covers formats nothing here draws.
     */
    @Test
    fun anStlIsAModelWhateverItIsLabelled() {
        assertThat(FileKind.detect("stl", "text/plain")).isEqualTo(MODEL)
        assertThat(FileKind.detect("stl", "application/octet-stream")).isEqualTo(MODEL)
        assertThat(FileKind.detect("", "Model/STL")).isEqualTo(MODEL)
        assertThat(FileKind.detect("", "model/obj")).isEqualTo(UNSUPPORTED)
        assertThat(FileKind.detect("obj", null)).isEqualTo(UNSUPPORTED)
        assertThat(FileKind.detect("3mf", null)).isEqualTo(UNSUPPORTED)
    }

    /**
     * .ods has been in the extension set and both intent filters since 1.7,
     * but not in the MIME fallback, so a spreadsheet shared with no filename
     * on it was offered a viewer and then refused one.
     */
    @Test
    fun openDocumentSpreadsheetsRouteByMimeAsWellAsByExtension() {
        assertThat(FileKind.detect("ods", null)).isEqualTo(XLSX)
        assertThat(FileKind.detect("", MIME_ODS)).isEqualTo(XLSX)
    }

    /**
     * Android 10 and later report every type in lower case, while the registered spelling
     * of the macro-enabled Office types has capitals in it, and an app can pass that on as
     * it was given. Either has to reach the same viewer, and so does any other type in a
     * case nobody expected.
     */
    @Test
    fun aTypeRoutesWhateverItsCase() {
        assertThat(FileKind.detect("", MIME_DOCM)).isEqualTo(DOCX)
        assertThat(FileKind.detect("", MIME_DOCM.lowercase())).isEqualTo(DOCX)
        assertThat(FileKind.detect("", MIME_PPTM)).isEqualTo(PPTX)
        assertThat(FileKind.detect("", MIME_PPTM.lowercase())).isEqualTo(PPTX)
        assertThat(FileKind.detect("", "Application/PDF")).isEqualTo(PDF)
    }

    /**
     * An .m3u is labelled audio by Android, and by name it is text all the same, because
     * the extension is asked before the type, as everywhere else. The last case is a type
     * that is not a playlist's at all, so that nothing but the extension can decide it.
     */
    @Test
    fun anM3uIsTextWhateverItIsLabelled() {
        assertThat(FileKind.detect("m3u", "audio/x-mpegurl")).isEqualTo(TEXT)
        assertThat(FileKind.detect("m3u", "audio/mpegurl")).isEqualTo(TEXT)
        assertThat(FileKind.detect("m3u", "audio/mpeg")).isEqualTo(TEXT)
    }

    /**
     * With no name to go on, a playlist's type still says audio. The player can only fail
     * on one, since its tracks are paths and links Gander cannot follow, so the two playlist
     * types are taken out ahead of the audio branch, and nothing else that says audio is.
     */
    @Test
    fun aPlaylistWithNoExtensionIsTextAndOtherAudioIsNot() {
        assertThat(FileKind.detect("", "audio/x-mpegurl")).isEqualTo(TEXT)
        assertThat(FileKind.detect("", "audio/mpegurl")).isEqualTo(TEXT)
        assertThat(FileKind.detect("", "AUDIO/X-MPEGURL")).isEqualTo(TEXT)
        assertThat(FileKind.detect("", "audio/mpeg")).isEqualTo(PLAYER)
        assertThat(FileKind.detect("", "audio/x-wav")).isEqualTo(PLAYER)
    }

    /**
     * An image arriving by MIME alone goes to the WebView rather than the
     * tiling view, because the tiling view needs a region decoder and this
     * could be any of the formats only Chromium reads.
     */
    @Test
    fun imagesByMimeGoToTheWebViewer() {
        assertThat(FileKind.detect("", "image/jpeg")).isEqualTo(IMAGE_WEB)
        assertThat(FileKind.detect("", "image/svg+xml")).isEqualTo(IMAGE_WEB)
    }

    /**
     * Word, Excel and PowerPoint files are zips underneath, and so are .epub, .apk and .jar.
     * Only a file called .zip, or sent as one, is listed as an archive: the rest open as what
     * they are, a book included, or not at all.
     */
    @Test
    fun onlyAZipIsTakenForAnArchive() {
        assertThat(FileKind.detect("docx", "application/zip")).isEqualTo(DOCX)
        assertThat(FileKind.detect("xlsx", null)).isEqualTo(XLSX)
        assertThat(FileKind.detect("pptx", null)).isEqualTo(PPTX)
        assertThat(FileKind.detect("epub", null)).isEqualTo(EPUB)
        assertThat(FileKind.detect("", "application/epub+zip")).isEqualTo(EPUB)
        // A book sent as a plain zip but named as a book is still a book: the name comes first
        assertThat(FileKind.detect("epub", "application/zip")).isEqualTo(EPUB)
        assertThat(FileKind.detect("apk", null)).isEqualTo(UNSUPPORTED)
        assertThat(FileKind.detect("jar", null)).isEqualTo(UNSUPPORTED)
    }

    @Test
    fun anythingElseIsUnsupported() {
        assertThat(FileKind.detect("", null)).isEqualTo(UNSUPPORTED)
        assertThat(FileKind.detect("xyz", null)).isEqualTo(UNSUPPORTED)
        assertThat(FileKind.detect("ppt", null)).isEqualTo(UNSUPPORTED)
        assertThat(FileKind.detect("odp", null)).isEqualTo(UNSUPPORTED)
        assertThat(FileKind.detect("", "application/octet-stream")).isEqualTo(UNSUPPORTED)
    }

    /** What may open as text unasked: a file with no viewer and no advice on the card. Issue #51. */
    @Test
    fun anUnknownFileHasNoViewerAndNoAdvice() {
        assertThat(FileKind.isUnknown(UNSUPPORTED, "xyz")).isTrue()
        assertThat(FileKind.isUnknown(UNSUPPORTED, "")).isTrue()
        assertThat(FileKind.isUnknown(UNSUPPORTED, "ppt")).isFalse()
        assertThat(FileKind.isUnknown(TEXT, "xyz")).isFalse()
        assertThat(FileKind.isUnknown(PLAYER, "mp4")).isFalse()
    }

    /** The advice is written in the card's script, so its extensions and these are held together. */
    @Test
    fun theAdvisedExtensionsAreTheOnesTheCardHasWordsFor() {
        val script = File("src/main/assets/viewer/unsupported.js").readText()
        val named = Regex("""vwExt === "([a-z0-9]+)"""").findAll(script).map { it.groupValues[1] }.toSet()
        assertThat(named).isEqualTo(FileKind.advisedExt)
    }

    // ---------------------------------------------------------------

    @Test
    fun isAudioExtSeparatesAudioFromVideoWithinPlayer() {
        listOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr")
            .forEach { assertThat(FileKind.isAudioExt(it)).isTrue() }
        listOf("mp4", "mkv", "webm", "avi", "pdf", "")
            .forEach { assertThat(FileKind.isAudioExt(it)).isFalse() }
    }

    /**
     * detect() expects an already lowercased extension, and every caller
     * lowercases before it. Pinned as a precondition rather than fixed here,
     * because the fix belongs in one place and this test would hide it.
     */
    @Test
    fun detectExpectsTheExtensionAlreadyLowercased() {
        assertThat(FileKind.detect("PDF", null)).isEqualTo(UNSUPPORTED)
        assertThat(FileKind.detect("pdf", null)).isEqualTo(PDF)
    }

    /**
     * Every kind that names a page must name one that ships. A renamed or
     * deleted viewer page is otherwise a blank WebView at runtime and nothing
     * at build time.
     */
    @Test
    fun everyKindNamesAViewerPageThatExists() {
        FileKind.entries.filter { it.page.isNotEmpty() }.forEach { kind ->
            val page = File("src/main/assets/viewer/${kind.page}")
            assertThat("${kind.name} -> ${page.path}, exists=${page.exists()}")
                .isEqualTo("${kind.name} -> ${page.path}, exists=true")
        }
    }

    /** The kinds a native view draws carry no page at all. */
    @Test
    fun nativeKindsNameNoPage() {
        assertThat(IMAGE.page).isEmpty()
        assertThat(PLAYER.page).isEmpty()
        assertThat(ARCHIVE.page).isEmpty()
    }

    /** The three whose pages report themselves and take Go to page, a PDF in either view. */
    @Test
    fun onlyPdfWordAndBooksArePaged() {
        assertThat(FileKind.entries.filter { it.paged }).containsExactly(PDF, PDF_SIMPLE, DOCX, EPUB)
    }

    /** Night mode is for paper. A book's page follows the phone's theme and has none. */
    @Test
    fun onlyPdfAndWordTurnOverAtNight() {
        assertThat(FileKind.entries.filter { it.turnsOver }).containsExactly(PDF, PDF_SIMPLE, DOCX)
    }

    /**
     * Every page laid out wider than the phone and zoomed out to fit it: pages and pictures. Books,
     * text and Markdown are laid out at the phone's own width, and a sheet zooms itself.
     */
    @Test
    fun pagesAndPicturesInTheWebViewHaveTheAppsDoubleTap() {
        assertThat(FileKind.entries.filter { it.zoomedOnDoubleTap })
            .containsExactly(PDF, PDF_SIMPLE, DOCX, PROSE, PPTX, IMAGE_WEB)
    }

    /**
     * The two halves of that double tap. A page whose kind the app zooms must turn Chromium's
     * own off, or both zoom, and no other page may, or its double tap would do nothing.
     */
    @Test
    fun aPageTurnsChromiumsDoubleTapOffExactlyWhereTheAppZoomsItsOwn() {
        FileKind.entries.filter { it.page.isNotEmpty() }.forEach { kind ->
            val css = File("src/main/assets/viewer/${kind.page}").readText()
            val off = "html { touch-action: manipulation; }" in css
            assertThat("${kind.page} turns it off: $off")
                .isEqualTo("${kind.page} turns it off: ${kind.zoomedOnDoubleTap}")
        }
    }
}
