package com.arjun.gander

/**
 * Whether the WebView about to render a document is new enough for the library
 * that draws it, and what to tell the page when it is not.
 *
 * Split out of ViewerActivity because deciding this is string parsing against
 * a table of vendor quirks, and the quirks are the part worth testing. The
 * activity keeps only the two calls that ask Android what it is running.
 */

/**
 * Chromium major version the vendored pdf.js needs. Mozilla puts the legacy
 * build's floor at Chrome 125, and `lib/pdf.min.mjs` is pdfjs-dist 6.5.438
 * legacy. Below it, a PDF goes to the card or the simple view instead: see [pdfRoute].
 *
 * Read this before raising it alongside a pdf.js upgrade. Chromium 138 is the
 * last WebView that Android 8.0, 8.1 and 9.0 will ever receive, because 139
 * requires Android 10 and minSdk here is 26. A pdf.js release needing more than
 * 138 therefore does not degrade on API 26 to 28, it ends pdf.js there for good.
 * [pdfRoute] and the card would then have to treat those phones as locked: Android
 * 9 straight to the simple view, and Android 8, which has none, to a card that
 * doesn't tell the reader to update. docs/VENDORED.md carries the same warning
 * next to the version fetched.
 */
internal const val PDFJS_MIN_CHROMIUM_MAJOR = 125

/**
 * Chromium major version docx-preview needs, which draws .docx files. Measured
 * rather than published, since upstream names no minimum: the bundle is full of
 * `??` and `?.`, so an older engine cannot even parse it, and Chromium 80 shipped
 * both. Nothing it calls at run time is newer.
 *
 * Measured with scripts/js-floor.mjs on the exact file VendoredLibsTest pins by
 * hash, as docs/VENDORED.md describes. Any other file fails that test until this
 * has been measured again.
 */
internal const val DOCX_PREVIEW_MIN_CHROMIUM_MAJOR = 80

/**
 * Chromium major version marked needs, which lays out .md files. Measured the same
 * way: it parses from Chromium 84, but its check for a reference link, which runs on
 * any text with a bracket in it, calls Object.hasOwn, and that arrived in 93. DOMPurify,
 * loaded beside it, needs nothing newer. The fetch script takes the newest marked 18,
 * so a refetch is the likeliest way for that test to fail.
 */
internal const val MARKED_MIN_CHROMIUM_MAJOR = 93

/**
 * Chromium major version the find in page of find.js needs, which marks what it found with the
 * CSS Custom Highlight API: that arrived in 105. Unlike the three above it is not a floor below
 * which a document cannot open. Below it those pages keep Chromium's own find, which marks
 * matches itself but sees only what is in the DOM.
 */
internal const val HIGHLIGHT_MIN_CHROMIUM_MAJOR = 105

/**
 * Whether a page of this [kind] searches its own text, through find.js and the channel pdf.html
 * searches through, on an engine of [major], null when it could not be read. A PDF always does,
 * and has no other way to. The rest only where the engine can mark what was found; an unreadable
 * version is taken as new enough, as the floors above take it.
 */
internal fun searchesInPage(kind: FileKind, major: Int?): Boolean = when (kind) {
    FileKind.PDF -> true
    FileKind.DOCX, FileKind.PROSE, FileKind.MD, FileKind.TEXT, FileKind.XLSX, FileKind.PPTX,
    FileKind.EPUB ->
        major == null || major >= HIGHLIGHT_MIN_CHROMIUM_MAJOR
    // The simple view shows pictures of a PDF's pages, with no text in them
    FileKind.PDF_SIMPLE, FileKind.IMAGE, FileKind.IMAGE_WEB, FileKind.PLAYER, FileKind.MODEL,
    FileKind.ARCHIVE, FileKind.UNSUPPORTED -> false
}

/**
 * The floor of whatever draws [kind], or null for a format with none. Every other
 * viewer's scripts parse as ES2015, and each of them opened its files on WebView 66.
 *
 * No else branch, so a format added later does not compile until someone has
 * decided whether the library drawing it has a floor.
 */
internal fun minChromiumMajor(kind: FileKind): Int? = when (kind) {
    FileKind.PDF -> PDFJS_MIN_CHROMIUM_MAJOR
    FileKind.DOCX -> DOCX_PREVIEW_MIN_CHROMIUM_MAJOR
    FileKind.MD -> MARKED_MIN_CHROMIUM_MAJOR
    // Gander's own readers: scripts/js-floor.mjs puts every prose-*.js at Chromium 51
    // or earlier, and they lean on nothing newer than JSZip does. model.js and
    // model-stl.js parse from 51 too, and the newest thing either calls is pointer
    // events, from 55, which is older than the WebView Android 8 shipped with.
    // epub.js is written the same way and parses from 51 as well. The newest thing it calls
    // is a shadow tree, from 53.
    FileKind.IMAGE, FileKind.IMAGE_WEB, FileKind.PLAYER, FileKind.XLSX, FileKind.PPTX,
    FileKind.PROSE, FileKind.TEXT, FileKind.MODEL, FileKind.EPUB, FileKind.ARCHIVE,
    FileKind.UNSUPPORTED -> null
    // The view for the engines pdf.js can't run on, so a floor of its own would defeat it
    FileKind.PDF_SIMPLE -> null
}

/**
 * Below this, a parsed major is treated as unreadable rather than as ancient.
 * WebView only became updatable at Chromium 33, so a provider reporting
 * something like "1.0" is telling us it does not use Chromium version numbers,
 * not that it predates them, and refusing its PDFs would break a device that
 * works today.
 *
 * This is why the user agent is asked first. Huawei numbers its WebView 12.1.x,
 * 14.0.x, 15.0.x, so a genuinely old engine parsed to 15 from the package alone,
 * landed under this floor, and was waved through as unreadable. Reading Chrome/
 * out of the user agent gives the engine version whatever the vendor calls the
 * package, and a provider that reports neither is caught by
 * [LOCKED_WEBVIEW_PACKAGES] instead of by guessing.
 */
internal const val PLAUSIBLE_CHROMIUM_MAJOR = 30

/** The Chromium major in a WebView user agent, as in "Chrome/138.0.7204.179". */
internal val CHROME_TOKEN = Regex("""Chrome/(\d+)""")

/**
 * WebView providers that cannot be swapped for another one.
 *
 * On a Huawei device without Google services the provider is pinned to this
 * package: Chrome and Android System WebView are both rejected, because they are
 * signed against a certificate chain the device does not carry. Two consequences.
 * The card must not tell these users to update Android System WebView, since they
 * cannot. And for a PDF, a version we failed to read is old rather than unknown,
 * because no Huawei build reaches the pdf.js floor, so this is the one case where
 * an unreadable version keeps a PDF from pdf.js, and sends it to the simple view,
 * instead of being waved through. Nothing says the same of the Word and Markdown
 * floors, which are far lower.
 */
internal val LOCKED_WEBVIEW_PACKAGES = setOf("com.huawei.webview")

/**
 * Chromium major version of the WebView that will render the page.
 *
 * The user agent is asked first, because its Chrome/ token is the engine version
 * whatever the provider calls its package, and a vendor scheme like Huawei's
 * "15.0.4.326" says nothing about the engine. [packageVersionName] gives the
 * provider package's own versionName, kept as a fallback for an engine whose
 * user agent carries no Chrome/ token at all. It is a function so the package is
 * only asked once the user agent has failed to answer: that lookup can throw, and
 * a throw must not cost a version the user agent already gave.
 *
 * Null when neither source answers, or when both are too low to be a Chromium
 * version. A null is treated as new enough unless the file is a PDF and the provider
 * is locked: refusing files on a WebView that works would be the worse mistake, and
 * pdf.html's nomodule fallback still covers the oldest engines a null could hide.
 */
internal fun chromiumMajor(userAgent: String?, packageVersionName: () -> String?): Int? {
    val fromUa = userAgent
        ?.let { CHROME_TOKEN.find(it) }
        ?.groupValues?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it >= PLAUSIBLE_CHROMIUM_MAJOR }
    return fromUa ?: packageVersionName()
        ?.substringBefore('.')
        ?.toIntOrNull()
        ?.takeIf { it >= PLAUSIBLE_CHROMIUM_MAJOR }
}

/**
 * The parameters telling a viewer page its library cannot run here, and empty
 * otherwise, which includes every format without a floor. Before issue #31 only
 * PDFs were checked, and on WebView 64 a .docx and a .md failed with an error
 * that said nothing about the WebView.
 *
 * "&webview=<major>&needs=<floor>" when the engine is older than the format's
 * library supports. Both numbers are passed so each floor lives only in Kotlin
 * rather than being repeated as a literal inside a user-facing sentence in the page.
 *
 * "&locked=1" is added when updating the WebView is not something the reader can
 * do, so the page can drop the advice to go and update it. On a locked provider
 * whose version would not parse, a PDF gets that flag on its own with no major
 * beside it, which is why the page gates on either parameter rather than on the
 * version. A Word or Markdown file there is let through: see
 * [LOCKED_WEBVIEW_PACKAGES].
 */
internal fun webViewFloorParams(kind: FileKind, major: Int?, locked: Boolean): String {
    val floor = minChromiumMajor(kind) ?: return ""
    if (major == null) {
        return if (locked && kind == FileKind.PDF) "&needs=$floor&locked=1" else ""
    }
    if (major >= floor) return ""
    return "&webview=$major&needs=$floor" + if (locked) "&locked=1" else ""
}

/** Where a PDF is shown: see [pdfRoute]. */
internal enum class PdfRoute {
    /** By pdf.js, in pdf.html. */
    PDFJS,

    /** Nowhere: pdf.html says the WebView is too old for pdf.js. */
    CARD,

    /** In the simple view, [FileKind.PDF_SIMPLE], once the viewer has found the file whole. */
    SIMPLE,
}

/**
 * Where a PDF is shown on a WebView of [major], null when it could not be read, from a provider
 * that is [locked] or not, on Android [sdk], and whether the reader has [asked] for the simple
 * view.
 *
 * By pdf.js wherever it runs, which is wherever [webViewFloorParams] leaves a PDF alone, so the
 * two can't disagree. Below its floor a locked provider goes straight to the simple view, since
 * nothing the reader does will bring pdf.js back. An engine an update would raise keeps the card,
 * and gets the simple view only when the reader asks for it there: the update brings back what
 * the simple view lacks, text to search, select and follow links in, and annotations such as
 * filled-in forms, and it patches the engine every other format is drawn by. Never the simple
 * view on Android 8, where the renderer isn't walled off from Gander: see
 * [PdfRenderClient.containedOn].
 */
internal fun pdfRoute(major: Int?, locked: Boolean, sdk: Int, asked: Boolean): PdfRoute = when {
    webViewFloorParams(FileKind.PDF, major, locked).isEmpty() -> PdfRoute.PDFJS
    !PdfRenderClient.containedOn(sdk) -> PdfRoute.CARD
    locked || asked -> PdfRoute.SIMPLE
    else -> PdfRoute.CARD
}

/**
 * Whether the card a PDF gets on a WebView of [major], from a provider that is [locked] or not, on
 * Android [sdk], offers the simple view. Read off [pdfRoute], so the button shows exactly where
 * asking would take the PDF there.
 */
internal fun pdfCardOffersSimple(major: Int?, locked: Boolean, sdk: Int): Boolean =
    pdfRoute(major, locked, sdk, asked = false) == PdfRoute.CARD &&
        pdfRoute(major, locked, sdk, asked = true) == PdfRoute.SIMPLE
