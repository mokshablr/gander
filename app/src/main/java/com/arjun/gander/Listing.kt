package com.arjun.gander

/**
 * What the home screen shows for a file, and in what order.
 *
 * The badge palette lives here rather than inside MainActivity because three
 * things draw from it: a row, the welcome grid, and the accessibility label
 * that stands in for the grid. Two hardcoded copies of a palette drift the
 * first time one is edited.
 */

/**
 * The badge for each file kind, named once.
 *
 * Four of these moved when the app adopted the brand's terracotta primary. PPT sat
 * about four degrees of hue from the new accent, so a PowerPoint tile and the app's
 * own accent would have read as one signal; it moved to 16 degrees off. The other
 * three moved because they were already failing WCAG AA against their own white
 * label, which is what Play's pre-launch accessibility scan looks for: DIR was the
 * worst thing in the app at 1.97:1, FILE at 3.35 and PPT at 3.92, against the 4.5
 * that 12sp bold needs. They now measure 4.90, 4.65 and 5.20.
 *
 * PDF moved too, from 4.98 to 6.54. It was already passing, and it sits close to the
 * accent, but Thumbs draws a real first page over it whenever it can, so the tile is
 * mostly a placeholder. Mostly: a document that will not render, an encrypted one
 * above all, falls back to this badge and keeps it.
 *
 * The rest are untouched. Every one of them clears AA and sits at least 82 degrees
 * of hue away from the accent.
 */
internal val PDF_BADGE = "PDF" to 0xFFB3261E.toInt()
internal val DOC_BADGE = "DOC" to 0xFF1565C0.toInt()
internal val XLS_BADGE = "XLS" to 0xFF2E7D32.toInt()
internal val PPT_BADGE = "PPT" to 0xFFB25000.toInt()
internal val IMG_BADGE = "IMG" to 0xFF7B1FA2.toInt()
internal val VID_BADGE = "VID" to 0xFFAD1457.toInt()
internal val AUD_BADGE = "AUD" to 0xFF00838F.toInt()
internal val MD_BADGE = "MD" to 0xFF455A64.toInt()
internal val TXT_BADGE = "TXT" to 0xFF616161.toInt()

/**
 * A 3D model. Indigo, in the widest gap the palette leaves: 32 degrees of hue from DOC's
 * blue and 38 from IMG's purple, with the accent over a hundred away. 7.91:1 against its
 * white label.
 */
internal val MODEL_BADGE = "3D" to 0xFF4338CA.toInt()

/** What an unsupported file falls back to. */
internal val FILE_BADGE = "FILE" to 0xFF607884.toInt()

/**
 * The last tile of the welcome grid, standing for everything Gander opens that has no tile of
 * its own: text and code, zips, and 3D models. In the grey the TXT tile had in that place, so
 * the grid reads exactly as it did and only the word has changed.
 *
 * Three letters, as every other tile is. The tile is a fixed square and its label grows with
 * the phone's font size, and a fourth letter wrapped it at 150% and cut it off at 200%.
 */
internal val ETC_BADGE = "ETC" to TXT_BADGE.second

/**
 * Draws the nine tiles of the welcome grid, in reading order.
 *
 * Kinds rather than formats, which is what makes the grid hold still: FileKind maps
 * 78 extensions onto its kinds, so adding .odt or .rst or another codec changes nothing
 * here. There are more kinds than tiles, since text files and zips both open, and three
 * rows of three is what fits the narrowest phone, so the last tile says ETC rather than
 * naming one of them. A new kind needs no tile: ETC already covers it.
 *
 * FILE is deliberately absent. It is what an unsupported file falls back to, and
 * this grid is a list of what Gander opens.
 *
 * One thing here does not update itself: welcome_formats_spoken is the sentence a
 * screen reader hears in place of these tiles, and it is prose that names every kind,
 * including the ones behind ETC. Adding a kind means editing that string too, or the
 * grid and its description stop agreeing.
 */
internal val WELCOME_BADGES = listOf(
    PDF_BADGE, DOC_BADGE, XLS_BADGE,
    PPT_BADGE, IMG_BADGE, VID_BADGE,
    AUD_BADGE, MD_BADGE, ETC_BADGE,
)

internal val DIR_COLOR = 0xFF8A6D1F.toInt()

/**
 * A .zip, in the folder colour, because that is what it behaves as: tapped, it opens as a
 * list to go into rather than as a document. Borrowing the colour also borrows its
 * measured 4.90:1 against the white label. Declared after DIR_COLOR on purpose, since
 * top-level properties are initialised in the order they are written.
 */
internal val ZIP_BADGE = "ZIP" to DIR_COLOR

/**
 * The brand accent, and the one badge that is an action rather than a file type.
 *
 * Fixed rather than ?attr/colorPrimary, which is what it looks like it should be.
 * Material inverts primary for dark mode, to #FFB39E, and the label on every
 * badge is a hardcoded white in row_item.xml: a white "+" on that measured
 * 1.72:1, worse than the amber DIR badge this release exists partly to fix.
 * Pinned to the light tone it stays 6.54:1 in both themes, and against the night
 * surface it sits at 2.83 against the DOC badge's 3.22, so it reads as a shape
 * exactly like its neighbours.
 */
internal val ADD_COLOR = 0xFFAF2D18.toInt()

/** The two or three letters, and the colour behind them, for a file. */
internal fun badgeFor(name: String, mime: String?): Pair<String, Int> {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (FileKind.detect(ext, mime)) {
        FileKind.PDF -> PDF_BADGE
        FileKind.DOCX, FileKind.PROSE -> DOC_BADGE
        FileKind.XLSX -> XLS_BADGE
        FileKind.PPTX -> PPT_BADGE
        FileKind.IMAGE, FileKind.IMAGE_WEB -> IMG_BADGE
        FileKind.PLAYER -> if (FileKind.isAudioExt(ext)) AUD_BADGE else VID_BADGE
        FileKind.MD -> MD_BADGE
        FileKind.TEXT -> TXT_BADGE
        FileKind.MODEL -> MODEL_BADGE
        FileKind.ARCHIVE -> ZIP_BADGE
        FileKind.UNSUPPORTED -> FILE_BADGE
    }
}

/** One entry of a folder, as the provider describes it. */
internal data class ChildDoc(
    val docId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long
)

internal const val MIME_DIR = "vnd.android.document/directory"

/**
 * A folder listing split into directories and files, each sorted by name.
 *
 * Directories first, because that is where a reader looking for somewhere else
 * to go will look. Dotfiles are dropped from both: nothing Gander opens is
 * hidden by convention, and a folder full of them reads as noise.
 *
 * sortedWith and not sortedBy, for the reason homeRows gives: the selector runs
 * on every comparison, so lowercase() there allocated some sixteen thousand
 * strings on a folder of fifteen hundred files rather than none.
 */
internal fun orderChildren(
    children: List<ChildDoc>,
    sortType: Settings.SortType = Settings.SortType.NAME,
    ascending: Boolean = true
): Pair<List<ChildDoc>, List<ChildDoc>> {
    val visible = children.filterNot { it.name.startsWith(".") }
    val (dirs, files) = visible.partition { it.mime == MIME_DIR }

    val dirComparator = when (sortType) {
        Settings.SortType.DATE -> {
            if (ascending) {
                compareBy<ChildDoc> { it.modified }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            } else {
                compareByDescending<ChildDoc> { it.modified }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            }
        }
        else -> {
            val cmp = compareBy(String.CASE_INSENSITIVE_ORDER) { c: ChildDoc -> c.name }
            if (ascending) cmp else cmp.reversed()
        }
    }

    val fileComparator = when (sortType) {
        Settings.SortType.NAME -> {
            val cmp = compareBy(String.CASE_INSENSITIVE_ORDER) { c: ChildDoc -> c.name }
            if (ascending) cmp else cmp.reversed()
        }
        Settings.SortType.DATE -> {
            if (ascending) {
                compareBy<ChildDoc> { it.modified }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            } else {
                compareByDescending<ChildDoc> { it.modified }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            }
        }
        Settings.SortType.SIZE -> {
            if (ascending) {
                compareBy<ChildDoc> { it.size }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            } else {
                compareByDescending<ChildDoc> { it.size }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            }
        }
    }

    return dirs.sortedWith(dirComparator) to files.sortedWith(fileComparator)
}

internal data class SupportedFilterType(
    val badge: String,
    val color: Int,
    val labelRes: Int
)

internal val SUPPORTED_FILTER_TYPES = listOf(
    SupportedFilterType(PDF_BADGE.first, PDF_BADGE.second, R.string.filter_pdf),
    SupportedFilterType(DOC_BADGE.first, DOC_BADGE.second, R.string.filter_doc),
    SupportedFilterType(XLS_BADGE.first, XLS_BADGE.second, R.string.filter_xls),
    SupportedFilterType(PPT_BADGE.first, PPT_BADGE.second, R.string.filter_ppt),
    SupportedFilterType(IMG_BADGE.first, IMG_BADGE.second, R.string.filter_img),
    SupportedFilterType(VID_BADGE.first, VID_BADGE.second, R.string.filter_vid),
    SupportedFilterType(AUD_BADGE.first, AUD_BADGE.second, R.string.filter_aud),
    SupportedFilterType(MD_BADGE.first, MD_BADGE.second, R.string.filter_md),
    SupportedFilterType(TXT_BADGE.first, TXT_BADGE.second, R.string.filter_txt),
    SupportedFilterType(MODEL_BADGE.first, MODEL_BADGE.second, R.string.filter_model),
    SupportedFilterType(ZIP_BADGE.first, ZIP_BADGE.second, R.string.filter_archive),
)

