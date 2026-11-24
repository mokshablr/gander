package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.MessageDigest
import org.junit.Test

/**
 * The vendored viewer libraries, and the three places that describe them.
 *
 * Gander ships every renderer inside the APK and fetches nothing, so the
 * licence notice it displays and the provenance file in the repo are the whole
 * of its answer to "what is in this binary". Those two and the fetch script
 * are maintained by hand and have already drifted once, which is what this
 * catches.
 *
 * Unit tests run with the module directory as their working directory, so the
 * repository root is one level up.
 */
class VendoredLibsTest {

    private companion object {
        val REPO = File("..")
        val LIB = File(REPO, "app/src/main/assets/viewer/lib")
        val CMAPS = File(LIB, "cmaps")

        val FETCH_SCRIPT = File(REPO, "scripts/fetch-viewer-libs.sh").readText()
        val VENDORED_MD = File(REPO, "docs/VENDORED.md").readText()
        val LICENCES_MD = File(REPO, "app/src/main/assets/licences.md").readText()

        /**
         * VENDORED.md's table as each file it lists, relative to the lib
         * directory, to the project named beside it.
         */
        val PROJECTS: Map<String, String> = VENDORED_MD.lines()
            .filter { it.startsWith("| `") }
            .flatMap { row ->
                val cells = row.split("|").map { it.trim() }
                Regex("`([^`]+)`").findAll(cells[1]).map { it.groupValues[1] to cells[2] }.toList()
            }
            .toMap()

        /** The pdf.js release the script pins, read out of the script itself. */
        val PDFJS_VER: String =
            Regex("""PDFJS_VER="([^"]+)"""").find(FETCH_SCRIPT)!!.groupValues[1]

        /**
         * The files the Word and Markdown floors in WebViewFloor.kt were measured
         * on, as `shasum -a 256 <file>` prints each from the lib directory. Neither
         * docx-preview nor marked publishes a minimum, so those floors are
         * measurements, and a measurement is of one file.
         */
        val FLOORS_MEASURED_ON = mapOf(
            "docx-preview.min.js" to "c4a133c65a112799e35b143c572dfff9e923a3bf39b5fd3b07a06aa0f8f530c2",
            "jszip3.min.js" to "acc7e41455a80765b5fd9c7ee1b8078a6d160bbbca455aeae854de65c947d59e",
            "marked.umd.js" to "b82b9334406d8bdd16b6236c08de1cb65b2a859873ca9503d9cc0b292ca3c12a",
            "purify.min.js" to "cd37898f9782adf54820c413191b5691803f0f137c799d6edcfd38523b4c2c31",
            "katex/katex.min.js" to "fa140498b8cfc8302cbe5e7cb1bdbc1f952bc38b3fa258ede09e73edbf9a74bc",
        )
    }

    @Test
    fun theFetchScriptPinsAPdfJsVersion() {
        assertThat(PDFJS_VER).matches("""\d+\.\d+\.\d+""")
    }

    /**
     * The three records of the pdf.js version have to agree. The floor in
     * WebViewFloor.kt is derived from this release's own minimum, so a bump
     * that updates one and not the others leaves the app refusing PDFs on
     * engines that would have run them, or accepting ones that will not.
     */
    @Test
    fun everyRecordOfThePdfJsVersionAgrees() {
        assertThat(VENDORED_MD).contains(PDFJS_VER)
        assertThat(LICENCES_MD).contains(PDFJS_VER)
    }

    @Test
    fun theVendoredLibrariesAreAllPresent() {
        val expected = listOf(
            "pdf.min.mjs", "pdf.worker.min.mjs", "jszip3.min.js",
            "docx-preview.min.js", "xlsx.full.min.js", "marked.umd.js",
            "purify.min.js", "katex/katex.min.js", "katex/katex.min.css",
            "pptx/pptxjs.js", "pptx/divs2slides.js", "pptx/filereader.js",
            "pptx/jquery.min.js", "pptx/jszip2.min.js", "pptx/d3.min.js",
            "pptx/nv.d3.min.js", "pptx/pptxjs.css", "pptx/nv.d3.min.css",
        )
        expected.forEach { name ->
            val f = File(LIB, name)
            assertThat("$name exists=${f.exists()} bytes=${f.length() > 0}")
                .isEqualTo("$name exists=true bytes=true")
        }
    }

    /**
     * Every shipped library is named in both the provenance file and the
     * licence notice the app displays. Shipping a library the notice does not
     * mention is the licence problem; naming one that is not there is the
     * smaller half of the same drift.
     *
     * The notice names projects, not files, so each file is looked up in
     * VENDORED.md's table for the project it belongs to, and that project has
     * to be in a row of the notice's own table. The two documents word some
     * names differently, "pdf.js worker (legacy build)" against "pdf.js (legacy
     * build, with worker)", so a project counts as named when every word of it,
     * less VENDORED.md's notes in brackets or after a comma, is in one row.
     * Rows only, because the licence texts below them use words like "marked".
     */
    @Test
    fun everyShippedLibraryIsNamedInBothRecords() {
        val shipped = LIB.walkTopDown()
            .filter { it.isFile && it.extension in setOf("js", "mjs", "wasm") }
            .map { it.relativeTo(LIB).invariantSeparatorsPath }
            .toList()
        assertThat(shipped).isNotEmpty()
        shipped.forEach { path ->
            val name = path.substringAfterLast('/')
            assertThat("$name in VENDORED.md: ${VENDORED_MD.contains(name)}")
                .isEqualTo("$name in VENDORED.md: true")
        }

        val rows = LICENCES_MD.lines().filter { it.startsWith("|") }
        val unnamed = shipped.mapNotNull { path ->
            val project = PROJECTS[path] ?: return@mapNotNull "$path: no row in VENDORED.md"
            val words = project.substringBefore(" (").substringBefore(",").split(" ")
            val named = rows.any { row -> words.all { standalone(it).containsMatchIn(row) } }
            if (named) null else "$path: $project"
        }
        assertWithMessage("shipped but not named in licences.md").that(unnamed).isEmpty()
    }

    // ---------------------------------------------------------------
    // The files the Word and Markdown floors were measured on
    // ---------------------------------------------------------------

    /**
     * docx.html loads docx-preview and JSZip, md.html marked, DOMPurify and KaTeX,
     * and the floors in WebViewFloor.kt were measured on exactly these five files.
     * The fetch script takes the newest marked 18 and DOMPurify 3, so a refetch
     * can hand over a library that needs a newer engine than its floor says, and
     * a reader on that engine is back to an error naming a missing part instead
     * of the card that says why: issue #31.
     */
    @Test
    fun theWordAndMarkdownFloorsWereMeasuredOnTheseFiles() {
        FLOORS_MEASURED_ON.forEach { (name, measured) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(File(LIB, name).readBytes())
            val fingerprint = digest.joinToString("") { "%02x".format(it) }
            assertWithMessage(
                "$name is not the file the floors in WebViewFloor.kt were measured on. Measure it " +
                    "as docs/VENDORED.md describes, set the floor from what that finds, then put " +
                    "the new fingerprint in FLOORS_MEASURED_ON",
            ).that(fingerprint).isEqualTo(measured)
        }
    }

    // ---------------------------------------------------------------
    // The CMap tables, which fail silently when they are missing
    // ---------------------------------------------------------------

    /**
     * A PDF naming a CJK encoding without embedding the font renders blank
     * paragraphs without throwing when these are absent: no exception, no
     * fallback boxes, just missing text. They were left untracked once
     * already. tests/viewer/test_pdf_render.py proves they work; this proves
     * they ship.
     */
    @Test
    fun theCjkCmapTablesShip() {
        assertThat(CMAPS.isDirectory).isTrue()
        val bcmaps = CMAPS.listFiles { f -> f.extension == "bcmap" }.orEmpty()
        assertThat(bcmaps.size).isAtLeast(150)
        assertThat(bcmaps.all { it.length() > 0 }).isTrue()
    }

    /** The encodings the four CJK scripts actually use. */
    @Test
    fun theTablesForEachCjkScriptArePresent() {
        listOf(
            "UniGB-UCS2-H", // Simplified Chinese
            "UniCNS-UCS2-H", // Traditional Chinese
            "UniJIS-UCS2-H", // Japanese
            "UniKS-UCS2-H", // Korean
        ).forEach { name ->
            val f = File(CMAPS, "$name.bcmap")
            assertThat("$name.bcmap exists=${f.exists()}").isEqualTo("$name.bcmap exists=true")
        }
    }

    /** Adobe's tables carry their own licence, and it travels with them. */
    @Test
    fun theCmapLicenceTravelsWithTheTables() {
        assertThat(File(CMAPS, "LICENSE").exists()).isTrue()
        assertThat(VENDORED_MD).contains("cmaps/")
        assertThat(LICENCES_MD).contains("CMap")
    }

    // ---------------------------------------------------------------

    /**
     * Nothing in the shipped viewer may reach the network. The app has no
     * INTERNET permission, so a stray CDN reference fails silently rather than
     * loudly, and the page renders wrong with nothing to say why.
     *
     * The lib directory is upstream code and is not searched: a minified
     * bundle mentions its own homepage in a banner comment. This covers the
     * hand-written pages, which are the ones a change could add a tag to.
     */
    @Test
    fun noHandWrittenViewerPageReferencesARemoteResource() {
        val viewer = File(REPO, "app/src/main/assets/viewer")
        val ours = viewer.listFiles { f -> f.isFile }.orEmpty()
        assertThat(ours).isNotEmpty()
        val remote = Regex("""(src|href)\s*=\s*["']https?://""", RegexOption.IGNORE_CASE)
        ours.forEach { page ->
            val hit = remote.find(page.readText())
            assertThat("${page.name}: ${hit?.value ?: "no remote reference"}")
                .isEqualTo("${page.name}: no remote reference")
        }
    }

    /** [word] where it is not part of a longer run of letters and digits, so D3 is not in NVD3. */
    private fun standalone(word: String) =
        Regex("""(?<![0-9A-Za-z])${Regex.escape(word)}(?![0-9A-Za-z])""")
}
