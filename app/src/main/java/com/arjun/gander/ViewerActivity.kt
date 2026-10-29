package com.arjun.gander

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.arjun.gander.FileKind.Companion.detect
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.math.roundToInt

open class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"

        /**
         * The one part of the cache Gander's FileProvider covers: the files of its own the viewer
         * can share, which are text another app shared and About's pages.
         */
        fun shareableDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }
        private const val STATE_COPY_SOURCE = "copy_source"
        private const val STATE_ARCHIVE_FOLDER = "archive_folder"
        private const val STATE_ARCHIVE_CODE_PAGE = "archive_code_page"
        private const val STATE_SIMPLE_VIEW = "simple_view"

        /**
         * Opens a PDF in the simple view whatever the WebView, so that a phone pdf.js runs on can
         * show it. Read only by a debuggable build: see [pdfRouteHere].
         */
        const val EXTRA_SIMPLE_VIEW = "com.arjun.gander.SIMPLE_VIEW"

        /** The keys a WebView scrolls a document by. */
        private val SCROLL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
        )

        /**
         * This activity again, under the name Gander's own screens open it by.
         *
         * A subclass, InternalViewer, so that it can be declared not exported and nothing outside
         * Gander can start it, which lets the activity tell a file Gander chose from one another app
         * handed it. The exported name is the door for other apps, and through it only a
         * content URI from someone else's provider comes in, the grant that came with it
         * being the whole of what Gander may read. Gander's own URIs and paths are refused
         * there, because each reads with Gander's access rather than the sender's:
         * - a file:// path or the path extra, anything Gander's own storage holds, such as
         *   the Recents file, or /dev/zero, which never ends;
         * - its FileProvider, which covers the cache and the thumbnails of the reader's
         *   documents in it;
         * - ArchiveProvider, which reads whatever archive its URI names: a folder the reader
         *   granted, or a file in Recents.
         * And only a file opened from Gander's own screens goes into Recents, so another app
         * cannot plant an entry there under a name of its choosing.
         *
         * Nothing would leave the phone either way, since nothing can, but another app has
         * no business deciding what Gander shows.
         */
        const val INTERNAL_VIEWER = "com.arjun.gander.InternalViewer"

        /**
         * How long the page readout stays up after the last scroll, and how long it
         * takes to arrive and leave.
         *
         * Two seconds rather than the usual one and a half because the pill is a tap
         * target as well as a readout, and one and a half races the hand of somebody
         * who has just decided to reach for it.
         */
        private const val OVERLAY_IDLE_MS = 2000L
        private const val OVERLAY_FADE_IN_MS = 120L
        private const val OVERLAY_FADE_OUT_MS = 180L
    }

    private var webView: ScrollProbeWebView? = null

    /**
     * Whether the page on show searches its own text over the channel, see [searchesInPage],
     * rather than being searched by Chromium's own find. Decided once the WebView exists.
     */
    private var pageSearches = false

    /** A video or a track, while one is on show. See [PlayerScreen]. Read by tests. */
    internal var playerScreen: PlayerScreen? = null
        private set

    /** The list of what is in a zip, when this is one. */
    private var archiveBrowser: ArchiveBrowser? = null

    /** Where that list reads the archive; tests swap in one that runs inline. */
    @androidx.annotation.VisibleForTesting
    internal var archiveLoader: java.util.concurrent.Executor? = null

    /** The file the destination picker is currently open for. */
    private var copySource: Uri? = null

    /** Whether the PDF on show asked for a password. See [PortMessage.Locked]. Read by tests. */
    @androidx.annotation.VisibleForTesting
    internal var printLocked = false
        private set

    /** Whether the reader asked for a PDF's simple view, which the viewer is rebuilt to show. */
    private var simpleAsked = false

    /** Whether the card on show has a button for the simple view, which asks over the channel. */
    private var cardOffersSimple = false

    /** The simple view's renderer and the pages it serves, while a PDF is shown there. */
    @Volatile
    private var simplePdf: SimplePdf? = null

    /** The title bar over a document, which goes up out of sight as it is read. See [DocumentChrome]. Read by tests. */
    internal var documentChrome: DocumentChrome? = null
        private set

    /** The double tap of a document's pages or a picture in the WebView. See [DoubleTapZoom]. */
    private var doubleTapZoom: DoubleTapZoom? = null

    private val createDocument = CreateTypedDocument()

    /** Writes the open file wherever the reader points the picker. See [SaveCopy]. */
    @androidx.annotation.VisibleForTesting
    internal val copier by lazy { SaveCopy(this) }

    private val saveCopy = registerForActivityResult(createDocument) { dest ->
        val src = copySource
        copySource = null
        if (dest != null && src != null) copier.copy(src, dest)
    }

    /**
     * The picker outlives the activity if Android reclaims the process while it is
     * open, and the callback is handed the destination but never the source, so
     * without this the save comes back to a null source and silently does nothing.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_COPY_SOURCE, copySource?.toString())
        outState.putString(STATE_ARCHIVE_FOLDER, archiveBrowser?.folder)
        outState.putString(STATE_ARCHIVE_CODE_PAGE, archiveBrowser?.codePage)
        playerScreen?.save(outState)
        outState.putBoolean(STATE_SIMPLE_VIEW, simpleAsked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        applySystemBarInsets(findViewById(R.id.root))
        onBackPressedDispatcher.addCallback(this, searchBackCallback)
        // After the search bar's, so it is asked first: what it closes is over the whole page
        onBackPressedDispatcher.addCallback(this, coverBackCallback)
        pageIndicator.setOnClickListener { askForPage() }

        copySource = savedInstanceState?.getString(STATE_COPY_SOURCE)?.let(Uri::parse)
        simpleAsked = savedInstanceState?.getBoolean(STATE_SIMPLE_VIEW) == true

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        val container = findViewById<FrameLayout>(R.id.container)

        // Files arrive via VIEW (data), the share sheet (EXTRA_STREAM), a plain path extra
        // from Gander itself, or as shared text (EXTRA_TEXT). Who sent them decides which
        // of those may be opened: see INTERNAL_VIEWER.
        val fromGander = componentName.className == INTERNAL_VIEWER
        val handed = intent.data
            ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.getStringExtra(EXTRA_PATH)?.let { Uri.fromFile(File(it)) }
        if (handed != null && !fromGander && !mayOpenFromOutside(handed)) {
            finish()
            return
        }
        // The shared-text file is written here, from text the sender handed over, so it
        // is Gander's own URI but never one another app chose.
        val uri = handed ?: sharedTextUri()
        if (uri == null) {
            finish()
            return
        }

        val name = resolveDisplayName(uri)
        toolbar.title = name
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: intent.type

        // Picker selections carry a persistable grant; keep those in Recents.
        // Open-with and folder-browsed URIs throw here and are simply skipped. A file
        // inside a zip would too, but is kept out by name rather than by that: nothing
        // in a zip is written to the phone, and Recents is on the phone. Only from
        // Gander's own screens, which is where the picker is: another app offering a
        // persistable grant would otherwise get an entry, under a name of its choosing.
        if (fromGander && uri.scheme == "content" && !ArchiveProvider.isEntry(this, uri)) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Recents.add(this, uri, name)
            }
        }

        fun show(kind: FileKind) {
            when (kind) {
                FileKind.IMAGE -> showPicture(container, uri, name, ext)
                FileKind.PLAYER -> playerScreen = PlayerScreen(
                    this, nightChrome, ::touchExplorationOn, ::applySystemBarInsets, savedInstanceState,
                    failed = { showWeb(container, uri, FileKind.UNSUPPORTED, name, ext) },
                ).apply { show(container, uri, name, ext) }
                FileKind.ARCHIVE -> showArchive(container, uri, name, savedInstanceState)
                else -> showWeb(container, uri, kind, name, ext)
            }
            setUpSearch(toolbar, kind)
            setUpActions(toolbar, kind, uri, name, ext, mime)
            // A document that opens turned over opens with the parts around it dark as well
            if (loadedNight) nightChrome.show(true)
            // What the simple view can't show, under the file's name
            if (kind == FileKind.PDF_SIMPLE) {
                val notesDrawn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                toolbar.subtitle =
                    getString(if (notesDrawn) R.string.simple_view_line_notes else R.string.simple_view_line)
            }
        }

        val detected = detect(ext, mime)
        if (detected == FileKind.PDF) {
            // A PDF bound for the simple view goes there once the file is found to be one it can be
            // handed, and to the card saying pdf.js can't run here otherwise
            if (pdfRouteHere() == PdfRoute.SIMPLE) {
                return whetherWhole(uri) { whole -> show(if (whole) FileKind.PDF_SIMPLE else detected) }
            }
            // The card's button for it is offered on the same terms, and needs the channel to ask over
            if (canPortSearch() &&
                pdfCardOffersSimple(engineHere(), webViewProviderIsLocked(), Build.VERSION.SDK_INT)
            ) {
                return whetherWhole(uri) { whole ->
                    cardOffersSimple = whole
                    show(detected)
                }
            }
        }
        // Graphviz saves its graphs as .dot too, which the prose page cannot read: one that reads
        // as text opens as text, while a Word template still goes to the prose page. Issue #58.
        if (detected == FileKind.PROSE && ext == "dot") {
            return whetherText(uri, ::dotOpensAsText) { text -> show(if (text) FileKind.TEXT else detected) }
        }
        if (!FileKind.isUnknown(detected, ext)) return show(detected)
        // A file of no known format that reads as text opens as text, with no card to tap
        // through, and the line under its name says so. Issue #51.
        whetherText(uri) { text ->
            if (text) toolbar.subtitle = getString(R.string.unknown_shown_as_text)
            show(if (text) FileKind.TEXT else detected)
        }
    }

    /**
     * Where a PDF goes on this phone: see [pdfRoute]. Asked before the WebView is made, so the
     * engine is read from the user agent a WebView starts with, which is the one it keeps, since
     * Gander never sets its own.
     *
     * A debuggable build also takes [EXTRA_SIMPLE_VIEW], so that the simple view can be checked
     * on a phone pdf.js runs on, which is every phone a test is run on. Never on Android 8, where
     * the renderer isn't walled off from Gander.
     */
    private fun pdfRouteHere(): PdfRoute {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent.getBooleanExtra(EXTRA_SIMPLE_VIEW, false) &&
            PdfRenderClient.containedOn(Build.VERSION.SDK_INT)
        ) return PdfRoute.SIMPLE
        return pdfRoute(engineHere(), webViewProviderIsLocked(), Build.VERSION.SDK_INT, simpleAsked)
    }

    /** The engine's major version, as [pdfRouteHere] reads it before there is a WebView. */
    private fun engineHere(): Int? =
        webViewChromiumMajor(runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull())

    /**
     * Opens the PDF on show again in the simple view, when the reader asks for it on the card
     * saying pdf.js can't run here. Rebuilt rather than swapped in place, because the search,
     * night mode and print the viewer sets up around a page are decided by which view it is.
     */
    private fun askForSimpleView() {
        simpleAsked = true
        recreate()
    }

    /**
     * Tells [then] whether the file reads as text, which [test] decides from its first bytes.
     * Those are read off the main thread, as a picture's are, since a provider can be slow to
     * hand over even those. One that cannot be read is not text, and gets the page or card it
     * always had.
     */
    private fun whetherText(
        uri: Uri,
        test: (InputStream) -> Boolean = ::readsAsText,
        then: (Boolean) -> Unit,
    ) = offMain(then) { app ->
        runCatching { app.contentResolver.openInputStream(uri)?.use(test) }.getOrNull() == true
    }

    /**
     * Tells [then] whether the file can be handed to the simple view's renderer, which is given
     * a whole file opened for reading alone, the way [PdfRenderClient.open] checks it: not a file
     * inside a zip, nor one its provider streams through a pipe.
     */
    private fun whetherWhole(uri: Uri, then: (Boolean) -> Unit) = offMain(then) { app ->
        !ArchiveProvider.isEntry(app, uri) &&
            runCatching { app.contentResolver.openFileDescriptor(uri, "r")?.use(mayHandOver) }
                .getOrNull() == true
    }

    /**
     * Asks [ask] off the main thread and hands its answer to [then] on it, unless the viewer has
     * gone by then.
     */
    private fun <T> offMain(then: (T) -> Unit, ask: (Context) -> T) {
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        val worker = documentLoader ?: Executors.newSingleThreadExecutor()
        worker.execute {
            val answer = ask(app)
            main.post { if (!isDestroyed) then(answer) }
        }
        (worker as? java.util.concurrent.ExecutorService)?.shutdown()
    }

    /** Night mode, share, print and "show in file manager" toolbar actions. */
    private fun setUpActions(
        toolbar: MaterialToolbar,
        kind: FileKind,
        uri: Uri,
        name: String,
        ext: String,
        mime: String?
    ) {
        setUpNightMode(toolbar, kind)
        goToPageItem = toolbar.menu.findItem(R.id.action_go_to_page).apply {
            setOnMenuItemClickListener { askForPage(); true }
        }
        contentsItem = toolbar.menu.findItem(R.id.action_contents).apply {
            setOnMenuItemClickListener { tellPage(PortCommand.contents()); true }
        }
        textSizeItem = toolbar.menu.findItem(R.id.action_text_size).apply {
            setOnMenuItemClickListener { askForTextSize(); true }
        }
        toolbar.menu.findItem(R.id.action_share).setOnMenuItemClickListener {
            shareFile(uri, ext, mime)
            true
        }
        toolbar.menu.findItem(R.id.action_save_copy).setOnMenuItemClickListener {
            copySource = uri
            createDocument.type = mime
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "*/*"
            // No picker on the device is the only way this throws, and it leaves
            // the reader on the document rather than on a crash
            runCatching { saveCopy.launch(name) }.onFailure {
                copySource = null
                Toast.makeText(this, R.string.save_copy_failed, Toast.LENGTH_SHORT).show()
            }
            true
        }

        setUpPrint(toolbar, kind, uri, name)

        val folder = containingFolder(uri)
        toolbar.menu.findItem(R.id.action_open_folder).apply {
            isVisible = folder != null
            setOnMenuItemClickListener {
                openFolder(folder ?: return@setOnMenuItemClickListener true)
                true
            }
        }
    }

    /**
     * Turning a document's pages over for reading in the dark: a PDF's since issue #19,
     * a Word document's since #47.
     *
     * Only where the page is handed the message port, see [pageGetsChannel], since that is the
     * whole of how it is told. Without it a document still opens in the right mode from the
     * URL, but a control that could not change it would quietly do nothing.
     */
    private fun setUpNightMode(toolbar: MaterialToolbar, kind: FileKind) {
        val item = toolbar.menu.findItem(R.id.action_night_mode)
        // Not for a card in place of the document, saying the WebView is too old or isn't there:
        // turning a card dark is not a feature. webViewFloorParams is non-empty exactly for the first.
        val blocked = webView == null ||
            webViewFloorParamsFor(kind, webView?.settings?.userAgentString).isNotEmpty()
        if (!kind.turnsOver || !pageGetsChannel(kind) || blocked) {
            item.isVisible = false
            return
        }
        item.isVisible = true
        item.isChecked = Settings.night(this)
        item.setOnMenuItemClickListener {
            val on = !it.isChecked
            it.isChecked = on
            Settings.setNight(this, on)
            nightChrome.show(on)
            if (searchPort != null) loadedNight = on
            tellPage(PortCommand.nightMode(on))
            true
        }
    }

    /** Print, through Android's own print screen. Issue #45. */
    private fun setUpPrint(toolbar: MaterialToolbar, kind: FileKind, uri: Uri, name: String) {
        val item = toolbar.menu.findItem(R.id.action_print)
        val route = printRoute(kind)
        // A card saying the WebView is too old or isn't there has no document on it to print
        val blocked = webView == null ||
            webViewFloorParamsFor(kind, webView?.settings?.userAgentString).isNotEmpty()
        if (route == null || blocked || !packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING)) {
            item.isVisible = false
            return
        }
        item.isVisible = true
        item.setOnMenuItemClickListener {
            print(route, uri, name)
            true
        }
    }

    private fun print(route: PrintRoute, uri: Uri, name: String) {
        if (route == PrintRoute.FILE && printLocked) {
            Toast.makeText(this, R.string.print_locked, Toast.LENGTH_LONG).show()
            return
        }
        val job = printJobName(name)
        val adapter = when (route) {
            PrintRoute.FILE -> PdfPrintAdapter(contentResolver, uri, job)
            PrintRoute.PAGE -> webView?.createPrintDocumentAdapter(job)
        }
        val started = adapter != null && runCatching {
            checkNotNull(getSystemService(PrintManager::class.java)).print(job, adapter, null)
        }.isSuccess
        if (!started) Toast.makeText(this, R.string.print_failed, Toast.LENGTH_SHORT).show()
    }

    private fun shareFile(uri: Uri, ext: String, mime: String?) {
        // Received content:// URIs go out with a read grant passed along; our own
        // file:// URIs (the shared-text temp file) go through the FileProvider
        val shareUri =
            if (uri.scheme == "content") uri
            else runCatching {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", File(uri.path!!))
            }.getOrNull()
        if (shareUri == null) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, shareUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Not to Gander itself, as the home screen's share is not: a file from a zip or
        // the shared-text file would be turned away at the door (see INTERNAL_VIEWER),
        // which looks like a crash, and any other file is already open right here.
        val chooser = Intent.createChooser(send, getString(R.string.share_file))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, ViewerActivity::class.java)))
        // Some Android versions refuse to delegate a tree-derived grant and
        // throw here rather than at read time
        runCatching { startActivity(chooser) }
            .onFailure { Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show() }
    }

    /**
     * The folder this document is in, for the Files app, or null where its provider has no place
     * on the phone's storage, which hides the menu item. The MediaStore lookup is here because it
     * needs a resolver; the rest is provider id string work in StorageUris.kt.
     */
    private fun containingFolder(uri: Uri): Uri? = parentDocUri(
        uri,
        Environment.getExternalStorageDirectory().absolutePath
    ) { mediaUri ->
        contentResolver.query(mediaUri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun openFolder(folder: Uri) {
        // The system Files app (and most file managers) handle VIEW on a
        // directory document; no grant needed, they have provider access
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        runCatching { startActivity(view) }.onFailure {
            Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Where a search goes. Two of these, because native find cannot see a whole document.
     *
     * findAllAsync is Chromium's own find-in-page, which searches the DOM. pdf.html holds
     * only about eleven pages of a document in the DOM at once, so native find would
     * report matches from the part of it currently on screen and silently miss the rest,
     * which is worse than offering no search at all. PDF therefore searches in the page,
     * over text it extracts itself, and reports back over a message port. So do the Word,
     * prose, Markdown, text, spreadsheet and slide pages, through find.js, for the same
     * reason on a smaller scale: a workbook draws one sheet at a time and a long text file
     * its first pages. Native find is what those fall back to on an engine too old to mark
     * matches for them, see [searchesInPage], and what everything else is searched with.
     */
    private interface Finder {
        fun query(q: String)
        fun next()
        fun prev()
        fun clear()
    }

    private class NativeFinder(private val web: WebView) : Finder {
        override fun query(q: String) = web.findAllAsync(q)
        override fun next() { web.findNext(true) }
        override fun prev() { web.findNext(false) }
        override fun clear() = web.clearMatches()
    }

    /** Gander's own end of the channel to the page, for every page that is given one. */
    private var searchPort: WebMessagePortCompat? = null

    /**
     * The night mode the page was loaded with, so a tap made before the channel exists
     * is not lost. setUpNightMode runs in onCreate and the port only arrives on page
     * finished; in between the item is tappable and there is nowhere to send it. The
     * preference is the truth, so the port corrects the page on the way up, the same
     * way openSearchChannel replays a query typed too early.
     */
    private var loadedNight = false

    /** Set once the counter exists, called with (position, total, indexFinished). */
    private var onSearchCount: ((Int, Int, Boolean) -> Unit)? = null

    /**
     * Closes the search bar, whoever asked: the close button, Back, or a renderer that
     * died underneath it. Held as a field because showRendererGone has to reach it from
     * outside setUpSearch, and it stays a no-op until there is a bar to close.
     */
    private var closeSearchBar: () -> Unit = {}

    /**
     * Takes Back while the search bar is open, so it closes the bar instead of the
     * document, which would throw away the reading position and the query together.
     *
     * Nothing here calls finish(). The callback closes the bar and switches itself off,
     * and the next press falls through to the default, which is what keeps predictive
     * back working: an enabled callback suppresses the animation and owns the event.
     */
    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearchBar()
    }

    /**
     * Back while a book's page has something for it to do first: its contents list or a
     * link's address to close, or the place the reader was at before a link took them to a
     * note. And while a sheet has a cell selected, whose bar Back puts away before it leaves.
     * The page says when there is: see [PortMessage.Covered].
     *
     * Switched off as it fires rather than on the page's word that the thing has closed, so
     * a page that never answers cannot hold on to Back.
     */
    private val coverBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            isEnabled = false
            tellPage(PortCommand.closeOver())
        }
    }

    /**
     * Last page the document reported, and how many there are. Zero until it says, which under
     * Robolectric it never does, so tests set the count themselves.
     */
    private var pageAt = 0

    @androidx.annotation.VisibleForTesting
    internal var pageTotal = 0

    /** What a PDF's page or a book's place is saved under; see [Positions]. Null for any other format. */
    private var positionKey: String? = null

    /**
     * Whether the document is a book, whose readout is how far through it the reader is and
     * which has no page to go to.
     */
    private var book = false

    /** Where in a book the top of the screen is; see [Positions.Place]. Null until its page says. */
    private var bookPlace: Positions.Place? = null

    /** Whether the chapter on screen takes the reader's text size. See [applyTextSize]. */
    private var bookReflows = true

    /** The text zoom the WebView came with, which is the phone's own font size setting. */
    private var phoneTextZoom = 100

    /**
     * Where a document's length and a PDF's page are looked up before the page loads, which
     * means asking its provider and, for the page, reading the start of the file, and where a
     * WebP or PNG is read for whether it moves. Tests swap in one that runs inline.
     */
    @androidx.annotation.VisibleForTesting
    internal var documentLoader: java.util.concurrent.Executor? = null

    /**
     * True while the find box is up. The page readout stands down for it: two counters
     * on one screen, the lower of them behind the keyboard, is not information.
     */
    private var searchBarOpen = false

    /**
     * An overlay that shows itself while the reader scrolls and puts itself away once
     * they stop.
     *
     * The page readout and the scroll thumb want the same six transitions in the same
     * three timings, and the second was written by copying the first. What separates
     * them is data rather than logic: the thumb brings its track up and down with it
     * and settles to INVISIBLE, because the track's height is what the thumb is
     * measured from and a gone view is never measured, while the pill has nothing
     * beside it and settles to GONE.
     *
     * Whether it is up is tracked here rather than read off the view. A scroll delivers
     * an event per frame, and asking the view would restart the fade on every one of
     * them: a fresh animator each frame never reaches the end of itself, so the overlay
     * would sit part-faded for as long as somebody kept scrolling.
     */
    private class AutoHide(
        private val view: View,
        private val hidden: Int,
        private val companion: View? = null,
        private val canFade: () -> Boolean = { true },
        private val onSettled: () -> Unit = {},
    ) {
        private var up = false
        private val hide = Runnable { if (canFade()) fadeOut() }

        /** Up if it is not already. The idle countdown is the caller's to start. */
        fun show() {
            if (up) return
            up = true
            view.animate().cancel()
            view.alpha = 0f
            view.visibility = View.VISIBLE
            companion?.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(OVERLAY_FADE_IN_MS).start()
        }

        /** Up now, at full alpha, and staying: for as long as a finger is on it. */
        fun pin() {
            up = true
            view.animate().cancel()
            view.removeCallbacks(hide)
            view.visibility = View.VISIBLE
            companion?.visibility = View.VISIBLE
            view.alpha = 1f
        }

        fun restartIdle() {
            view.removeCallbacks(hide)
            view.postDelayed(hide, OVERLAY_IDLE_MS)
        }

        fun cancelIdle() {
            view.removeCallbacks(hide)
        }

        fun fadeOut() {
            up = false
            view.animate().cancel()
            view.animate().alpha(0f).setDuration(OVERLAY_FADE_OUT_MS).withEndAction {
                view.visibility = hidden
                companion?.visibility = hidden
                onSettled()
            }.start()
        }

        /** Straight off, with no fade: for a document that has stopped being one. */
        fun hideNow() {
            up = false
            view.removeCallbacks(hide)
            view.animate().cancel()
            view.visibility = hidden
            companion?.visibility = hidden
            onSettled()
        }
    }

    private val pageIndicator: TextView by lazy { findViewById(R.id.pageIndicator) }

    /** The viewer's own parts, dark with a document in night mode. See [NightChrome]. */
    private val nightChrome by lazy { NightChrome(this) }

    private val pageFader by lazy { AutoHide(pageIndicator, View.GONE) }

    /** Bound once the toolbar exists, shown once pdf.html has said how long the file is. */
    private var goToPageItem: MenuItem? = null

    /** A book's two, shown once epub.html has said the book is open. */
    private var contentsItem: MenuItem? = null
    private var textSizeItem: MenuItem? = null

    /**
     * Put the page readout on screen, or keep it there.
     *
     * Called from two places that know different things. The page says which page is on
     * screen, over the port search already uses, and only when that number changes. The
     * WebView says that something scrolled at all, which is what decides the pill is
     * worth showing: scrolling within one tall page changes no page number, and a
     * readout that only appeared on crossing a boundary would be absent exactly when
     * somebody goes looking for it.
     *
     * A no-op until a paged document, a PDF in either view, a Word document or a book, has said
     * it is more than one page long, since nothing else sets the total.
     */
    private fun showPageIndicator() {
        if (pageTotal < 2 || searchBarOpen) return
        // While the thumb has hold of it, where the pill sits and whether it is up at
        // all belong to the drag. The number is set where it changes, not here: this
        // runs once a frame for the length of a fling, and the text is the same on all
        // but the few frames that cross a page boundary.
        if (dragging) return
        pageFader.show()
        restartPageIndicatorIdle()
    }

    private fun setPageIndicatorText() {
        val pill = pageIndicator
        // Description before text, as the search counter does it: anything already
        // reading this node has to find the spoken form in place by the time the
        // visible one changes under it.
        if (book) {
            // What a book's page counts is a stretch of its files and not a page anybody
            // could turn to, so the reader is told how far through it they are
            val percent = bookPercent(pageAt, pageTotal)
            pill.contentDescription = getString(R.string.book_position_spoken, percent)
            pill.text = getString(R.string.scroll_position, percent)
            return
        }
        pill.contentDescription =
            getString(R.string.page_indicator_spoken, pageAt, pageTotal)
        pill.text = getString(R.string.page_indicator, pageAt, pageTotal)
    }

    private fun restartPageIndicatorIdle() {
        // A page readout does not fade under a screen reader: a view at zero alpha
        // cannot be reached by swipe navigation, and this is the only thing on screen
        // that knows where in the document the reader is. Same reason media controls
        // stay up. A percentage left over from a drag is not worth keeping, so that
        // still goes.
        if (!touchExplorationOn() || pageTotal < 2) pageFader.restartIdle()
        else pageFader.cancelIdle()
    }

    /**
     * Asks for a page number and goes there.
     *
     * A bare EditText rather than a TextInputLayout, which is used nowhere else here, so a
     * floating label would pull more of Material into the APK. setError is also what Android 16
     * names as the way to report a bad value, so the small answer is the accessible one too.
     */
    // The field starts with the page in the digits the number keyboard types, whatever the locale
    @SuppressLint("SetTextI18n")
    private fun askForPage() {
        val total = pageTotal
        if (total < 2 || book) return

        // Dark over a document in night mode, like the rest of the viewer. See NightChrome.
        val themed = nightChrome.dialogs
        val entry = EditText(themed).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = getString(R.string.page_number)
            // A plain field comes out at 43dp, under the 48 Android asks of anything tapped
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setText(pageAt.toString())
            setSelection(text.length)
            requestFocus()
        }
        val gutter = (24 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(themed).apply {
            setPadding(gutter, gutter / 3, gutter, 0)
            addView(
                entry,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // The password box's insets, for its reason: see field_box_inset. With Material's own the
        // box could also come up short under the keyboard, Go and Cancel cut to a sliver.
        val inset = resources.getDimensionPixelSize(R.dimen.field_box_inset)
        val dialog = DialogBuilder(themed)
            .setTitle(R.string.go_to_page)
            .setMessage(getString(R.string.page_range, total))
            .setView(holder)
            .setBackgroundInsetTop(inset)
            .setBackgroundInsetBottom(inset)
            .setPositiveButton(R.string.go, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // ALWAYS_VISIBLE rather than VISIBLE: the weaker one leaves the keyboard down
        // until the field is tapped, and the only thing anybody opens this to do is type
        // a number into it.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        dialog.show()

        // Bound after show() on purpose: the button a builder makes dismisses the dialog
        // before its listener runs, and a number outside the document has to be able to
        // say so without the box closing under the answer.
        val go = {
            val n = entry.text.toString().trim().toIntOrNull()
            if (n == null || n < 1 || n > total) {
                entry.error = getString(R.string.page_out_of_range, total)
            } else {
                // Straight down the channel search uses. See PortFinder for the shape.
                tellPage(PortCommand.goToPage(n))
                dialog.dismiss()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { go() }
        entry.setOnEditorActionListener { _, _, _ -> go(); true }
    }

    /**
     * Sets the size of a book's text by the WebView's own text zoom, which scales every letter
     * and its line whatever unit the book gave the size in. A rule laid over the book's
     * stylesheets would leave a book that sets its type in points where it was. The choice
     * multiplies the zoom the WebView came with, so the phone's font size is where it starts.
     *
     * A page of a fixed size gets neither: it was drawn around its type at the size its book
     * gave it, and larger letters run out of the boxes they were placed in.
     */
    private fun applyTextSize() {
        val web = webView ?: return
        web.settings.textZoom =
            bookTextZoom(phoneTextZoom, Settings.bookTextSize(this), bookReflows)
    }

    /**
     * A book's text size: a button either side of the size, each tap showing on the page at
     * once. At the foot of the screen and with nothing dimmed, so the text being sized can be
     * read above it. The same size for every book, since it is the reader's eyes that it
     * suits and not the book. See [Settings.BOOK_TEXT_SIZES].
     */
    private fun askForTextSize() {
        val themed = nightChrome.dialogs
        val row = LayoutInflater.from(themed).inflate(R.layout.dialog_text_size, null)
        val smaller = row.findViewById<View>(R.id.textSmaller)
        val larger = row.findViewById<View>(R.id.textLarger)
        val now = row.findViewById<TextView>(R.id.textSizeNow)
        val sizes = Settings.BOOK_TEXT_SIZES

        fun show() {
            val percent = Settings.bookTextSize(this)
            // Description before text, as the page readout does it
            now.contentDescription = getString(R.string.text_size_spoken, percent)
            now.text = getString(R.string.scroll_position, percent)
            smaller.isEnabled = percent > sizes.first()
            larger.isEnabled = percent < sizes.last()
        }
        fun step(by: Int) {
            val to = sizes.getOrNull(sizes.indexOf(Settings.bookTextSize(this)) + by) ?: return
            Settings.setBookTextSize(this, to)
            applyTextSize()
            show()
        }
        smaller.setOnClickListener { step(-1) }
        larger.setOnClickListener { step(1) }
        show()

        val dialog = DialogBuilder(themed)
            .setTitle(R.string.text_size)
            .setView(row)
            .setPositiveButton(R.string.done, null)
            .create()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
    }

    private val accessibility: AccessibilityManager? by lazy {
        getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }

    private fun touchExplorationOn(): Boolean =
        accessibility?.isTouchExplorationEnabled == true

    /**
     * A WebView that will say how far down it is.
     *
     * The three measurements are protected on View, and widening them is the only reason
     * this class exists. Reading the scroll here rather than asking the page is what lets
     * one thumb serve every viewer, including the six that have no channel back to the
     * app at all. It is also why the thumb is a native view and not drawn in the page:
     * pdf.html has no viewport meta and is zoomed to fit from a fixed 980 CSS px layout,
     * so a position: fixed scrubber would be pinned to a viewport the reader is not
     * looking at and would slide off screen the moment they pinched in.
     */
    private class ScrollProbeWebView(context: Context) : WebView(context), ZoomSurface {
        fun verticalOffset(): Int = computeVerticalScrollOffset()
        fun verticalRange(): Int = computeVerticalScrollRange()
        fun verticalExtent(): Int = computeVerticalScrollExtent()

        // What a double tap reads; zoomBy and scrollTo are the WebView's own. See DoubleTapZoom.
        override fun viewWidth(): Int = width
        override fun viewHeight(): Int = height
        override fun scrolledX(): Int = scrollX
        override fun scrolledY(): Int = scrollY
        override fun rangeX(): Int = computeHorizontalScrollRange()
        override fun rangeY(): Int = computeVerticalScrollRange()
    }

    private val fastScrollTrack: View by lazy { findViewById(R.id.fastScrollTrack) }
    private val fastScrollThumb: View by lazy { findViewById(R.id.fastScrollThumb) }

    /**
     * The track goes up and down with the thumb, so the strip stops standing between a
     * reader and the document the moment there is nothing to grab. The exclusion rect is
     * refreshed once it has settled, because where the thumb ends up is what the back
     * gesture has to be kept off.
     */
    private val thumbFader by lazy {
        AutoHide(
            view = fastScrollThumb,
            hidden = View.INVISIBLE,
            companion = fastScrollTrack,
            canFade = { !dragging },
            onSettled = { excludeThumbFromBackGesture() },
        )
    }

    /** Whether there is enough document to be worth scrubbing. Sticky; see syncFastScroll. */
    private var thumbShown = false

    /**
     * Whether this document gets a thumb at all.
     *
     * The scroll listener runs for every WebView, so the one format setUpFastScroll turns
     * down has to be turned down here too, or its scrolls would put a thumb up anyway.
     */
    private var fastScrollEnabled = false

    /** The rect last handed to the window manager; see excludeThumbFromBackGesture. */
    private var excludedRect: Rect? = null

    private var dragging = false
    private var grabOffset = 0f
    private var pendingScrollY = 0
    private var scrollQueued = false

    /**
     * Wires the thumb to a WebView, for every format but one.
     *
     * A photo is the document this is wrong for. Fitted to the screen it cannot scroll at
     * all, so the gate in syncFastScroll would keep the thumb away by itself; pinched to
     * three times it can, because the scroll range grows with the zoom while the screen
     * does not, and the gate then opens and puts a vertical scrubber over an image
     * somebody is panning in two directions.
     *
     * Nothing else is excluded by kind, and UNSUPPORTED in particular must not be: its
     * "View as text" button replaces the card with text.html inside this same WebView
     * while the kind here stays UNSUPPORTED, and the file it does that for is exactly the
     * multi-megabyte one worth scrubbing. Geometry already hides the thumb on the card,
     * which is a screenful, and brings it back on the text, which is not.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setUpFastScroll(web: ScrollProbeWebView, kind: FileKind) {
        fastScrollEnabled = false
        hideFastScrollNow()
        if (kind == FileKind.IMAGE_WEB) return
        fastScrollEnabled = true
        // Gander's thumb replaces the WebView's own bar rather than sitting beside it.
        // This is the View's scrollbar and not a web one, so no stylesheet reaches it;
        // it stays on for a photo, where the thumb deliberately does not appear and that
        // bar is then the only position feedback there is.
        web.isVerticalScrollBarEnabled = false
        val track = fastScrollTrack
        val thumb = fastScrollThumb

        // Rotation does not rebuild this activity, so there is no onCreate to measure
        // from again; a layout change is the only word we get that the screen turned.
        web.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncFastScroll() }
        // And the track's own, which starts lower when the title bar floats over the document
        track.addOnLayoutChangeListener { _, _, t, _, b, _, oldT, _, oldB ->
            if (b - t != oldB - oldT) syncFastScroll()
        }

        track.setOnTouchListener { _, event ->
            val probe = webView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val top = thumb.translationY
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    val onThumb = thumbShown &&
                        event.y >= top - slop && event.y <= top + thumb.height + slop
                    if (probe == null || !onThumb) {
                        // Handed back, so the parent carries on down its child list to
                        // the document. Without this the 48dp strip would swallow every
                        // scroll made with a right thumb.
                        false
                    } else {
                        dragging = true
                        // A drag down the thumb is the reader scrolling, and moves the title bar
                        documentChrome?.grabbed()
                        grabOffset = event.y - top
                        thumb.isPressed = true
                        track.parent.requestDisallowInterceptTouchEvent(true)
                        // Nothing else can stop a fling that is already running. This
                        // overlay is a sibling of the WebView, so the touch never reaches
                        // it and never aborts its scroller, and the fling would then
                        // fight every scrollTo below.
                        probe.flingScroll(0, 0)
                        thumbFader.cancelIdle()
                        dragTo(probe, event.y)
                        true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // The field, every time. onRenderProcessGone destroys the WebView and
                    // nulls it, and a drag under way would otherwise still be talking to
                    // one that has gone.
                    if (dragging && probe != null) { dragTo(probe, event.y); true } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) false else {
                        dragging = false
                        documentChrome?.touched(event)
                        thumb.isPressed = false
                        // Where the finger left it is where the back gesture has to be
                        // kept off; dragTo does not pay for this on every move.
                        excludeThumbFromBackGesture()
                        restartPageIndicatorIdle()
                        thumbFader.restartIdle()
                        true
                    }
                }
                else -> false
            }
        }
        syncFastScroll()
    }

    /** Where the thumb belongs, and whether it belongs on screen at all. */
    private fun syncFastScroll() {
        if (!fastScrollEnabled) return
        val web = webView ?: return
        val track = fastScrollTrack
        val thumb = fastScrollThumb
        val range = web.verticalRange()
        val extent = web.verticalExtent()
        if (extent <= 0) return

        thumbShown = thumbShown(range, extent, thumbShown)
        if (!thumbShown) {
            hideFastScrollNow()
            return
        }

        val trackHeight = track.height
        if (trackHeight <= 0) return
        val floor = resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_min)
        val height = thumbHeight(trackHeight, extent, range, floor)
        if (thumb.layoutParams.height != height) {
            thumb.layoutParams = thumb.layoutParams.also { it.height = height }
        }
        if (!dragging) {
            thumb.translationY =
                thumbOffset(trackHeight, height, web.verticalOffset(), range, extent)
        }
        showFastScroll()
        excludeThumbFromBackGesture()
    }

    private fun dragTo(web: ScrollProbeWebView, y: Float) {
        val track = fastScrollTrack
        val thumb = fastScrollThumb
        val travel = (track.height - thumb.height).toFloat()
        if (travel <= 0f) return
        val at = (y - grabOffset).coerceIn(0f, travel)
        thumb.translationY = at
        dragTarget(at, travel, web.verticalRange(), web.verticalExtent())?.let(::queueScroll)
        showDragReadout(at / travel)
    }

    /**
     * One scroll per frame however many touch events arrive in it. A drag can deliver
     * events faster than the screen refreshes, and every scrollTo is a hop into the
     * renderer process.
     *
     * The horizontal offset is carried through rather than zeroed. The pages with no viewport
     * meta lay out at 980 CSS px, and a spreadsheet or a slide is routinely wider than the screen
     * even unzoomed, so scrolling to x = 0 would snap the reader back to the left edge on every move.
     */
    private fun queueScroll(y: Int) {
        pendingScrollY = y
        if (scrollQueued) return
        scrollQueued = true
        fastScrollTrack.postOnAnimation {
            scrollQueued = false
            val web = webView ?: return@postOnAnimation
            web.scrollTo(web.scrollX, pendingScrollY)
        }
    }

    /** The readout during a drag, where it always is, at the foot of the screen, rather than beside the thumb. */
    private fun showDragReadout(fraction: Float) {
        if (searchBarOpen) return
        val pill = pageIndicator
        if (pageTotal < 2) {
            // Nothing else knows how far into a Word document or a five megabyte log the
            // reader is, so the thumb says it rather than dragging blind.
            val percent = (fraction * 100f).toInt().coerceIn(0, 100)
            pill.contentDescription = getString(R.string.scroll_position_spoken, percent)
            pill.text = getString(R.string.scroll_position, percent)
        }
        pageFader.pin()
    }

    private fun showFastScroll() {
        thumbFader.show()
        if (!dragging) thumbFader.restartIdle()
    }

    /** Off, and the drag off with it: for a document too short to be worth scrubbing. */
    private fun hideFastScrollNow() {
        dragging = false
        fastScrollThumb.isPressed = false
        thumbFader.hideNow()
    }

    /**
     * Keeps the back gesture off the thumb.
     *
     * On gesture navigation the edge the thumb sits on is the back swipe, and without
     * this the thumb is a control you cannot touch. A no-op below API 29, and one thumb
     * is nowhere near the 200dp per edge the system allows.
     */
    private fun excludeThumbFromBackGesture() {
        val track = fastScrollTrack
        val thumb = fastScrollThumb
        val want =
            if (thumb.visibility != View.VISIBLE) null
            else thumb.translationY.toInt().let { Rect(0, it, track.width, it + thumb.height) }
        // Only when it has actually moved. This is reached from the scroll listener, so
        // it runs once a frame for the length of every fling, and each call that gets
        // through allocates a Rect and a list and then reports the change to the window
        // manager across a binder. The thumb's travel truncates to the same integer for
        // several frames at a time on a long document, and submitting a rect identical
        // to the standing one buys nothing.
        if (want == excludedRect) return
        excludedRect = want
        ViewCompat.setSystemGestureExclusionRects(track, want?.let { listOf(it) } ?: emptyList())
    }

    /** In-document search: a message port where the page searches itself, findAllAsync otherwise. */
    private fun setUpSearch(toolbar: MaterialToolbar, kind: FileKind) {
        val bar = findViewById<LinearLayout>(R.id.searchBar)
        val input = findViewById<EditText>(R.id.searchInput)
        val count = findViewById<TextView>(R.id.searchCount)
        val searchItem = toolbar.menu.findItem(R.id.action_search)

        val web = webView
        val blocked = webViewFloorParamsFor(kind, web?.settings?.userAgentString).isNotEmpty()
        // A 3D model is a picture drawn on a canvas, and the only words on its page are its
        // size, so there is nothing in it to find. Nor in a PDF's simple view, which shows
        // pictures of its pages.
        val wordless = kind == FileKind.MODEL || kind == FileKind.PDF_SIMPLE
        if (web == null || (kind == FileKind.PDF && !canPortSearch()) || blocked || wordless) {
            // No WebView at all, or one too old to carry a message channel, which is close to
            // unreachable since channels came long before the Chromium 125 pdf.html needs. Or a
            // PDF, Word or Markdown file under the card saying the WebView is too old, which has
            // no document on it to search.
            searchItem.isVisible = false
            return
        }
        searchItem.isVisible = true

        // One place that renders a count, whichever transport produced it. The
        // ordering inside it is load-bearing and is explained where it happens.
        val show = { active: Int, total: Int, settled: Boolean ->
            val asked = input.text.isNotEmpty()
            // Set the spoken form before the text: the live region fires on the text
            // change, and by then the description has to be the one to read out
            count.contentDescription = when {
                total > 0 -> getString(R.string.match_count_spoken, active, total)
                // "No matches" only once there is nothing left to look through. While
                // the PDF index is still being read a zero is a not-yet, and saying so
                // out loud would be wrong twice over: wrong now, and again when the
                // count changes under the reader.
                asked && settled -> getString(R.string.no_matches)
                else -> null
            }
            count.text = when {
                total > 0 -> getString(R.string.match_count, active, total)
                asked && settled -> getString(R.string.match_count, 0, 0)
                else -> ""
            }
        }

        val finder: Finder
        if (pageSearches) {
            onSearchCount = { active, total, settled -> show(active, total, settled) }
            finder = PortFinder()
        } else {
            web.setFindListener { active, total, done ->
                // Native find counts from zero and only means it once done.
                if (done) show(active + 1, total, true)
            }
            finder = NativeFinder(web)
        }

        // Every call into the finder goes through here. NativeFinder holds the WebView
        // directly, and onRenderProcessGone destroys it and nulls the field before
        // showRendererGone runs, so a clear arriving after that would land on a dead
        // one. The close path reaches this twice over: once itself, and once through the
        // text watcher that emptying the box fires.
        fun find(action: (Finder) -> Unit) { if (webView != null) action(finder) }

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        searchItem.setOnMenuItemClickListener {
            bar.visibility = LinearLayout.VISIBLE
            searchBackCallback.isEnabled = true
            searchBarOpen = true
            documentChrome?.searching(true)
            pageFader.hideNow()
            input.requestFocus()
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            true
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                if (q.isEmpty()) {
                    find { it.clear() }
                    count.text = ""
                    count.contentDescription = null
                } else {
                    find { it.query(q) }
                }
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            find { it.next() }
            true
        }
        findViewById<ImageButton>(R.id.searchPrev).setOnClickListener { find { it.prev() } }
        findViewById<ImageButton>(R.id.searchNext).setOnClickListener { find { it.next() } }

        closeSearchBar = {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            input.text.clear()
            find { it.clear() }
            bar.visibility = LinearLayout.GONE
            searchBackCallback.isEnabled = false
            searchBarOpen = false
            documentChrome?.searching(false)
        }
        findViewById<ImageButton>(R.id.searchClose).setOnClickListener { closeSearchBar() }
    }

    /**
     * What the reader last asked for, kept so it can be asked again.
     *
     * The search button is on the toolbar from the moment the activity is built, but
     * the channel does not exist until the page has run. Somebody quick enough to open
     * the box and type in that window would otherwise have their query go nowhere,
     * with the counter sitting empty and no way back except retyping it.
     */
    private var pendingQuery: String = ""

    /** One letter of command, then the payload. Read by onCommand() in pdf.mjs and find.js. */
    private inner class PortFinder : Finder {
        override fun query(q: String) {
            pendingQuery = q
            tellPage(PortCommand.query(q))
        }
        override fun next() = tellPage(PortCommand.next())
        override fun prev() = tellPage(PortCommand.prev())
        override fun clear() {
            pendingQuery = ""
            tellPage(PortCommand.clear())
        }
    }

    /** Down the channel, once the page has one: a search, and the title bar over a document. */
    @SuppressLint("RequiresFeature") // A channel exists only where canPortSearch() found the feature
    private fun tellPage(command: String) {
        searchPort?.postMessage(WebMessageCompat(command))
    }

    private fun canPortSearch(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_CLOSE)

    /**
     * Whether the page is handed the channel: a page that searches itself, and a paged document
     * whatever searches it, since its page counter and night mode go over the channel too.
     */
    private fun pageGetsChannel(kind: FileKind): Boolean = pageSearches || (kind.paged && canPortSearch())

    /**
     * Hand the page one end of a message channel: pdf.html, or a page that finds with find.js.
     *
     * This is the only channel from this app into a page, and it is deliberately not
     * addJavascriptInterface. That call reflects a Java object into script running on
     * an untrusted document and lets it call methods on it; this passes strings, in
     * the same direction the query parameters on the URL already go. Nothing on
     * either side is evaluated, and what comes back is read as a few integers or one of a
     * few fixed words, and dropped if it is anything else: see [parsePortMessage]. See the note beside vwAskPassword in pdf.mjs
     * about why that boundary is most of what keeps a document away from the app.
     *
     * Posted on page finished rather than at load, because the listener that takes it
     * lives in the page and is not there until the page has run.
     */
    @SuppressLint("RequiresFeature") // Checked by canPortSearch() on the first line, which lint can't follow
    private fun openSearchChannel(web: WebView, kind: FileKind) {
        if (!canPortSearch() || searchPort != null) return
        val ends = WebViewCompat.createWebMessageChannel(web)
        val mine = ends[0]
        mine.setWebMessageCallback(
            Handler(Looper.getMainLooper()),
            object : WebMessagePortCompat.WebMessageCallbackCompat() {
                override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                    when (val said = parsePortMessage(message?.data)) {
                        // Only a PDF and a Word document have pages to report. Any other
                        // page saying so is its document talking, and is not given a
                        // readout to write in.
                        is PortMessage.Page -> if (kind.paged) {
                            if (pageTotal == 0) {
                                goToPageItem?.isVisible = !book
                                contentsItem?.isVisible = book
                                textSizeItem?.isVisible = book
                            }
                            pageAt = said.n
                            pageTotal = said.of
                            setPageIndicatorText()
                            showPageIndicator()
                        }
                        is PortMessage.SearchCount ->
                            onSearchCount?.invoke(said.at, said.total, said.done)
                        PortMessage.Locked -> if (kind == FileKind.PDF) printLocked = true
                        is PortMessage.Place -> if (book) {
                            bookPlace = Positions.Place(said.chapter, said.past)
                            // The page works its place out once it has stopped moving, and
                            // that may be after the viewer has left the screen and saved
                            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                savePosition()
                            }
                        }
                        // A book's contents or a link's address over it, or a sheet's selected cell
                        is PortMessage.Covered ->
                            if (book || kind == FileKind.XLSX) coverBackCallback.isEnabled = said.on
                        is PortMessage.Reflows -> if (book) {
                            bookReflows = said.on
                            applyTextSize()
                        }
                        // Only the card that has the button may ask
                        PortMessage.SimpleView -> if (kind == FileKind.PDF && cardOffersSimple) askForSimpleView()
                        // Anything else came from the document rather than from
                        // the renderer, and is dropped without a word.
                        null -> Unit
                    }
                }
            })
        searchPort = mine
        // Addressed to Gander's own origin rather than to "*", so the port is only ever
        // handed to a page served from it, whatever the WebView is showing by then.
        WebViewCompat.postWebMessage(web, WebMessageCompat("vw-search-port", arrayOf(ends[1])), "https://$ASSET_HOST".toUri())
        // Anything typed while there was nowhere to send it.
        if (pendingQuery.isNotEmpty()) mine.postMessage(WebMessageCompat(PortCommand.query(pendingQuery)))
        // And any night mode tapped in the same window. Compared against what the URL
        // carried rather than tracked as a flag, so any number of taps comes out right.
        if (Settings.night(this) != loadedNight) {
            loadedNight = Settings.night(this)
            mine.postMessage(WebMessageCompat(PortCommand.nightMode(loadedNight)))
        }
        // And the title bar as it is now, which may not be as the URL had it
        documentChrome?.replay()
    }

    @SuppressLint("RequiresFeature") // A channel exists only where canPortSearch() found the feature
    private fun closeSearchChannel() {
        runCatching { searchPort?.close() }
        searchPort = null
        onSearchCount = null
    }

    /**
     * Edge to edge is enforced from targetSdk 35 on, so push the layout out of the
     * status bar, display cutout and navigation bar areas.
     */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** The file shared text was written to, which goes when the viewer closes. */
    private var sharedText: File? = null

    /** Shared plain text becomes a temp file shown in the text viewer. */
    private fun sharedTextUri(): Uri? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return runCatching {
            val f = File(shareableDir(this), "shared-text.txt")
            f.writeText(text)
            sharedText = f
            Uri.fromFile(f)
        }.getOrNull()
    }

    /**
     * What the exported viewer takes from another app: a content URI on somebody else's
     * provider, read with the grant that came with it. Never a path, and never one of
     * Gander's own providers, all of which are named under its package and read with
     * Gander's access rather than the sender's. Read the way Android reads it to find the
     * provider: the authority decoded, and whatever is before its last @ left off.
     * content://0@... names the same provider, the 0 being the phone's own user, and so
     * does the same with the @ written as %40, whose host is not the provider's, since the
     * host is split out of the authority as written.
     */
    private fun mayOpenFromOutside(uri: Uri): Boolean {
        val authority = uri.authority?.substringAfterLast('@') ?: return false
        return uri.scheme == "content" &&
            !authority.equals(packageName, ignoreCase = true) &&
            !authority.startsWith("$packageName.", ignoreCase = true)
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /**
     * A photo, or a WebP or PNG that moves, which goes to the page that plays GIFs, since the
     * photo view draws one frame. Issue #49. Whether it moves and which way up it was taken are
     * read off the main thread, as a document's length is, since a provider can be slow to hand
     * over even the first bytes.
     */
    private fun showPicture(container: FrameLayout, uri: Uri, name: String, ext: String) {
        val mayMove = ext == "webp" || ext == "png"
        offMain({ (moves, rotation): Pair<Boolean, Int> ->
            if (moves) showWeb(container, uri, FileKind.IMAGE_WEB, name, ext)
            else showImage(container, uri, name, ext, rotation)
        }) { app ->
            val moves = mayMove &&
                runCatching { app.contentResolver.openInputStream(uri)?.use(::isAnimated) }.getOrNull() == true
            moves to if (moves) 0 else Thumbs.exifRotation(app.contentResolver, uri)
        }
    }

    private fun showImage(container: FrameLayout, uri: Uri, name: String, ext: String, rotation: Int) {
        val imageView = SubsamplingScaleImageView(this)
        imageView.contentDescription = name
        imageView.setBackgroundColor(Color.BLACK)
        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
        imageView.maxScale = 12f
        // The library's own double tap went to a picture pixel a dp, nine times a phone's photo
        // and a small picture not at all, so it goes to twice the fit as a page's does
        imageView.setDoubleTapZoomDuration(DOUBLE_TAP_MS)
        imageView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitDoubleTap(imageView) }
        imageView.orientation = rotation
        imageView.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onReady() = fitDoubleTap(imageView)

            override fun onImageLoadError(e: Exception) {
                // Some formats decode fine in the WebView even when the region decoder gives up
                container.removeAllViews()
                showWeb(container, uri, FileKind.IMAGE_WEB, name, ext)
            }
        })
        container.addView(imageView, matchParent())
        imageView.setImage(ImageSource.uri(uri))
    }

    /** Twice the picture as it fits the view now, which changes as the phone turns. */
    private fun fitDoubleTap(view: SubsamplingScaleImageView) {
        if (view.isReady) view.setDoubleTapZoomScale(DOUBLE_TAP_ZOOM * view.minScale)
    }

    /** A zip, listed rather than drawn. See [ArchiveBrowser]. */
    private fun showArchive(container: FrameLayout, uri: Uri, name: String, state: Bundle?) {
        val browser = ArchiveBrowser(
            activity = this,
            toolbar = findViewById(R.id.toolbar),
            // The bar under the toolbar that a save reports on, doing the same job for the
            // list that the home screen's does for a folder
            progress = findViewById(R.id.saveProgress),
            saving = { copier.saving },
            archive = uri,
            archiveName = name,
            restoredFolder = state?.getString(STATE_ARCHIVE_FOLDER),
            restoredCodePage = state?.getString(STATE_ARCHIVE_CODE_PAGE),
            loader = archiveLoader ?: java.util.concurrent.Executors.newSingleThreadExecutor(),
        )
        archiveBrowser = browser
        browser.attach(container)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showWeb(container: FrameLayout, uri: Uri, kind: FileKind, name: String, ext: String) {
        // Android throws here when it has no working WebView: while Android System WebView is
        // being updated, or where someone has turned it off
        val web = runCatching { ScrollProbeWebView(this) }.getOrElse {
            showCard(container, R.string.no_webview, R.string.no_webview_detail)
            return
        }
        webView = web
        pageSearches = canPortSearch() &&
            searchesInPage(kind, webViewChromiumMajor(web.settings.userAgentString))

        with(web.settings) {
            javaScriptEnabled = true
            // Off, which is also the default, and said here so it stays off. Nothing
            // Gander ships reads or writes it, and on a page that renders an untrusted
            // document it is only a place for script that should never have run to leave
            // something behind for the next document to find.
            domStorageEnabled = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            /*
             * WebView's own floor on rendered text, which is 8 and is not neutral here.
             *
             * It exists so that a web page cannot make body text unreadably small. A
             * document is not a web page: its type sizes are the author's, the reader
             * can pinch, and a floor silently rewrites the smallest of them.
             *
             * For the PDF viewer it is worse than cosmetic, because pdf.js measures it.
             * TextLayer renders a 1px probe and reads back its height, then, finding 8,
             * lays every text span out at eight times the size it wants and scales it
             * back down by 1/8 so the words still land on the picture. That worked, and
             * it left every span in the selection layer with a layout box eight times
             * the glyphs it covers. Chromium's touch selection then resolved a drag to
             * the layer rather than to the words in it, and a selection dragged with a
             * finger stopped tracking: 584 of 600 moves in a row landing in the same
             * place, which is issue #22.
             *
             * At 1 the probe reads 1, pdf.js skips the compensation, and a span's box
             * is the size of its own text again. Measured against pdf.js's own viewer
             * in Chrome, which reads 1 because Chrome has no such floor, and which
             * tracks a finger correctly.
             *
             * PDF only, deliberately. The argument that a document's type sizes are the
             * author's holds for the Word, slide and spreadsheet viewers too, and the
             * floor is silently rewriting them there as well. But nothing has been
             * measured going wrong in those, and the way to find out is to look rather
             * than to change five viewers on the strength of one.
             *
             * A sheet as well, for its smallest type. It measures text on a canvas, which no
             * floor reaches, to tell what fits a cell, and 5 point text drawn at the floor's 8 px
             * came out a fifth wider than measured, spilling where Excel's fits. Its zoom is CSS
             * zoom, which the floor leaves alone.
             */
            if (kind == FileKind.PDF || kind == FileKind.XLSX) minimumFontSize = 1
            /*
             * A book for another reason, and measured too. Its page writes its own words
             * smaller as the reader's text size goes up, so that the zoom brings them back
             * to the size the phone asks for: 15 px is written as 6 at 250%. The floor made
             * that 8, and the strip over a chapter came out at 20 px where 15 was meant.
             * Only this floor is lowered. The other still holds at 8 every size a book
             * gives in ems or percent, which is how books give them, and is set here to
             * what it starts at so that the two can be read side by side.
             */
            if (kind == FileKind.EPUB) {
                minimumFontSize = 1
                minimumLogicalFontSize = 8
            }
            phoneTextZoom = textZoom
            // A sheet takes the phone's text size as the zoom it opens at, cells and all, rather
            // than as larger letters in columns the file sized for smaller ones: see the URL below
            if (kind == FileKind.XLSX) textZoom = 100
        }
        book = kind == FileKind.EPUB
        if (book) {
            applyTextSize()
            // Its readout is no page number, so a tap on it has no page to ask for
            pageIndicator.isClickable = false
        }

        // Cookies go for the same reason as DOM storage. Nothing here ever sets one, since
        // every response comes from this app, so the jar could only hold what a document's
        // own script put there.
        CookieManager.getInstance().apply {
            setAcceptCookie(false)
            setAcceptThirdPartyCookies(web, false)
        }

        // Neither of these can change while the document is open, and a ranged load
        // asks for hundreds of pieces, so they are resolved once rather than per request:
        // the type here, and the length before the page loads, below, since that is a
        // round trip to the provider.
        val server = DocumentServer(
            contentResolver,
            WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
                .build(),
            uri, kind, documentMime(ext),
            simplePdf = { simplePdf },
        )

        web.webViewClient = object : WebViewClientCompat() {
            override fun onPageFinished(view: WebView, url: String) {
                // The viewers that search from inside the page need a way to answer, and a
                // paged document a way to report its pages and hear night mode
                if (pageGetsChannel(kind)) openSearchChannel(view, kind)
            }

            /**
             * The renderer is a process of its own, and Android is free to kill it
             * when memory runs short. That happens most easily while Gander is in
             * the background and something heavy is starting in front of it, which
             * is exactly what tapping Share and picking a large app looks like.
             *
             * Not overriding this is not the neutral choice. The default returns
             * false, and false tells WebView to kill the whole application rather
             * than leave it holding a WebView it can no longer draw. So a document
             * left open in the background could take Gander down with it, with no
             * crash of ours behind it and nothing on screen to explain it.
             *
             * Returning true keeps the process, and the price is that this WebView
             * is finished: nothing may call into it again, so it is detached and
             * destroyed here and the reader is offered the document back.
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                if (webView === view) dropPage(container, detail.didCrash())
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = server.respond(request)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = !isViewerPage(request.url)
        }

        // A document gets its title bar out of the way while it is read. Only a document's page
        // is given the channel, which is how it is told what room to leave the bar at its top, so
        // a picture, a model and a card keep the bar above them.
        val floor = webViewFloorParamsFor(kind, web.settings.userAgentString)
        if (pageGetsChannel(kind) && floor.isEmpty()) {
            documentChrome = DocumentChrome(this, ::touchExplorationOn, ::tellPage, ::keepThumbBelowBar)
                .apply { float() }
        }
        // Only where the page turns the WebView's own double tap off, or both would zoom
        doubleTapZoom = if (kind.zoomedOnDoubleTap) DoubleTapZoom(this, web) else null
        // Not consumed: the document still takes every touch
        web.setOnTouchListener { _, event ->
            documentChrome?.touched(event)
            doubleTapZoom?.touched(event)
            false
        }

        // Any scroll at all is what brings the readout up; the number in it comes from
        // the port. See showPageIndicator for why the two are separate.
        web.setOnScrollChangeListener { _, _, y, _, oldY ->
            showPageIndicator()
            syncFastScroll()
            documentChrome?.scrolled(y, oldY)
        }
        setUpFastScroll(web, kind)

        container.addView(web, matchParent())
        if (kind == FileKind.PDF_SIMPLE) {
            simplePdf = SimplePdf(
                this, uri,
                inFront = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
                locked = { printLocked = true },
                // The page is pictures the renderer draws, so it goes with the renderer
                gone = { crashed -> if (webView === web) dropPage(container, crashed) },
            ).apply { start() }
        }
        // Out on the URL rather than over the port, so a page opens already turned over
        // instead of drawing itself white and then again. It is also the only route that
        // survives process death and the recreate() in showRendererGone, neither of which
        // leaves a port to send anything down.
        val night = if (kind.turnsOver && Settings.night(this)) 1 else 0
        loadedNight = night == 1
        // The page a PDF or a book was left at goes the same way, for the same two reasons and one
        // more: a document that opened at the top and then jumped would spend its opening
        // drawing a page nobody asked to see. Issue #25. Never for a file inside a zip:
        // the position is filed on the phone under a fingerprint of the file, nothing in a
        // zip is written to the phone, and one under a password would leave a fingerprint
        // of what the password was keeping. A PDF's two views file it under the same
        // fingerprint, so the page left in one is the page the other opens at.
        val keepsPosition =
            (kind == FileKind.PDF || kind == FileKind.PDF_SIMPLE || kind == FileKind.EPUB) &&
                !ArchiveProvider.isEntry(this, uri)
        positionKey = null

        // Both looked up off the main thread, since both ask the provider, and the page's
        // fingerprint reads the start of the file itself. A provider that fetches a file
        // before handing it over, as a cloud one does, answers when it has it, and asked on
        // the main thread that held the whole viewer still for the length of a download. The
        // page waits for the answers, since it would be waiting on the same provider anyway.
        offMain({ (total, key, placeAt, resumeAt): Opening ->
            if (webView !== web) return@offMain
            server.total = total
            positionKey = key
            // The load strategy is decided here, not in the page, so the headers we serve
            // and the loader the page picks cannot disagree
            val ranged = if (useRanges(total)) 1 else 0
            // The length goes on the URL too, when the provider gave one, because the
            // Content-Length a page sees is not ours. The WebView adds one of its own,
            // made from what the stream has to hand when it starts, which for a file
            // coming out of a zip through a pipe is nothing: a 1 KB model inside a zip
            // arrived saying it was 0 bytes long. model.js reads it from here instead.
            // Never 0, which is as likely to be a provider that could not tell as a file
            // that is empty, and an empty one says so anyway when there is nothing to read.
            web.loadUrl(
                "https://$ASSET_HOST/assets/viewer/${kind.page}" +
                    "?name=${Uri.encode(name)}&ext=${Uri.encode(ext)}&ranged=$ranged" +
                    (if (total > 0) "&length=$total" else "") +
                    "&night=$night" +
                    (if (resumeAt > 1) "&resume=$resumeAt" else "") +
                    (placeAt?.let { "&at=${it.chapter}.${it.past}" } ?: "") +
                    // The zoom the phone itself asks for, which a book's page keeps its
                    // own words at whatever size the reader gives the book's, and a sheet
                    // opens zoomed to
                    (if (kind == FileKind.EPUB || kind == FileKind.XLSX) "&type=$phoneTextZoom" else "") +
                    // The room to leave at the top for the title bar floating over it
                    (documentChrome?.let { "&top=${it.heightDp()}" } ?: "") +
                    floor +
                    (if (cardOffersSimple) "&simple=1" else "")
            )
        }) { app ->
            val total = documentLength(app, uri)
            val key = if (keepsPosition) Positions.keyFor(app.contentResolver, uri, total) else null
            // A book reopens at its place, which is finer than the page it also files
            Opening(
                total, key,
                placeAt = if (kind == FileKind.EPUB) key?.let { Positions.place(app, it) } else null,
                resumeAt = if (kind == FileKind.EPUB) 0 else key?.let { Positions.page(app, it) } ?: 0,
            )
        }
    }

    /** What a document's page is told before it loads that has to be asked of its provider: see [showWeb]. */
    private data class Opening(val total: Long, val key: String?, val placeAt: Positions.Place?, val resumeAt: Int)

    /**
     * Takes the WebView away, which can no longer show its document, and puts the card saying so
     * in its place: its renderer has gone, or the simple view's has.
     */
    private fun dropPage(container: FrameLayout, didCrash: Boolean) {
        val view = webView ?: return
        // A zoom still on its way would go on calling a WebView that has gone
        doubleTapZoom?.cancel()
        doubleTapZoom = null
        webView = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        showRendererGone(container, didCrash)
    }

    /**
     * Puts a message and a way back where the document was, once its renderer has
     * gone. Reloading is a full restart of the activity rather than a fresh WebView
     * in place, because search holds the WebView it was wired to and a destroyed one
     * cannot answer findAllAsync; going through onCreate again rebuilds both together.
     */
    private fun showRendererGone(container: FrameLayout, didCrash: Boolean) {
        // The simple view's renderer goes with the page it drew for
        simplePdf?.close()
        simplePdf = null
        // Both search surfaces go until there is something to search again, and for
        // PDF so does the channel: its far end was in the renderer that just died, so
        // it can neither be asked anything nor answer. The activity restarts to get a
        // working one, which is where a new channel comes from.
        closeSearchBar()
        // Kept before it is forgotten, so Reload comes back to this page rather than the top.
        savePosition()
        pageAt = 0
        pageTotal = 0
        goToPageItem?.isVisible = false
        pageFader.hideNow()
        fastScrollEnabled = false
        hideFastScrollNow()
        // Night mode goes with the search item: the channel both of them speak over is
        // closed below, so leaving either on screen offers a control that does nothing, and
        // night mode would go on rewriting the stored preference at a port nothing hears.
        val goneBar = findViewById<MaterialToolbar>(R.id.toolbar)
        // The line under an unknown file's name says how the page was shown, and the page is
        // gone. Reload reads the file's first bytes again and writes it back. Issue #51.
        goneBar.subtitle = null
        val goneMenu = goneBar.menu
        goneMenu.findItem(R.id.action_search)?.isVisible = false
        goneMenu.findItem(R.id.action_night_mode)?.isVisible = false
        // And Print, which has no document to print until Reload brings it back
        goneMenu.findItem(R.id.action_print)?.isVisible = false
        // A book's two speak to the page that has gone as well: Contents would do nothing,
        // and Text size would change the stored size with nothing on screen to show for it.
        // Its page can no longer answer Back either.
        contentsItem?.isVisible = false
        textSizeItem?.isVisible = false
        coverBackCallback.isEnabled = false
        closeSearchChannel()
        // Back above the card, which has nothing to scroll that would bring back a bar gone out of sight
        documentChrome?.land()
        documentChrome = null

        // The card is the app's rather than the document's, so the parts around it go back to
        // the phone's colours along with the page that was dark
        nightChrome.show(false)
        container.removeAllViews()
        showCard(
            container,
            if (didCrash) R.string.render_gone_crashed else R.string.render_gone_reclaimed,
            R.string.render_gone_detail,
        )
    }

    /** A card where the document would be, saying why it isn't, with Reload, which starts the viewer again. */
    private fun showCard(container: FrameLayout, @StringRes title: Int, @StringRes detail: Int) {
        val card = layoutInflater.inflate(R.layout.view_render_gone, container, false)
        card.findViewById<TextView>(R.id.renderGoneTitle).setText(title)
        card.findViewById<TextView>(R.id.renderGoneDetail).setText(detail)
        card.findViewById<View>(R.id.renderGoneReload).setOnClickListener { recreate() }
        container.addView(card)
    }

    /**
     * What the two sources say about the engine, handed to [chromiumMajor] to
     * be read. Only the package lookup is wrapped, because getCurrentWebViewPackage
     * talks to the package manager and a provider in a bad state can throw, and it
     * is only made once the user agent has failed to answer.
     */
    private fun webViewChromiumMajor(userAgent: String?): Int? = chromiumMajor(userAgent) {
        runCatching { WebViewCompat.getCurrentWebViewPackage(this)?.versionName }.getOrNull()
    }

    /** Whether the WebView about to render cannot be swapped for a different one. */
    private fun webViewProviderIsLocked(): Boolean = runCatching {
        WebViewCompat.getCurrentWebViewPackage(this)?.packageName in LOCKED_WEBVIEW_PACKAGES
    }.getOrDefault(false)

    /**
     * The engine's own answers, resolved before [webViewFloorParams] reads them, and
     * only for a format with a floor: no other page needs them, so opening anything
     * else asks nothing.
     */
    private fun webViewFloorParamsFor(kind: FileKind, userAgent: String?): String {
        if (minChromiumMajor(kind) == null) return ""
        return webViewFloorParams(kind, webViewChromiumMajor(userAgent), webViewProviderIsLocked())
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    /** The scroll thumb runs below the [px] of title bar floating over the document, wherever the bar is. */
    private fun keepThumbBelowBar(px: Int) {
        for (view in listOf(fastScrollTrack, fastScrollThumb)) {
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            if (params.topMargin == px) continue
            params.topMargin = px
            view.layoutParams = params
        }
    }

    /**
     * The tap highlight goes as the viewer leaves, for the reason MainActivity.onPause gives: a
     * back swipe shows the frame drawn on the way out, and a zip's list, drawn with the home
     * screen's rows, came back with the file just opened still lit.
     */
    override fun onPause() {
        super.onPause()
        window.decorView.isPressed = false
        window.decorView.jumpDrawablesToCurrentState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        playerScreen?.videoChrome?.focusChanged(hasFocus)
        documentChrome?.focusChanged(hasFocus)
    }

    // A mouse wheel or a touchpad scrolling a document moves its title bar as a finger does. Seen
    // here, before the WebView, which takes them in its own way.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) documentChrome?.nudged()
        return super.dispatchGenericMotionEvent(event)
    }

    // And so do the keys that scroll it
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in SCROLL_KEYS) documentChrome?.nudged()
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        playerScreen?.started()
    }

    /** A track may play on with the screen off: see [PlayerScreen.stopped]. */
    override fun onStop() {
        playerScreen?.stopped()
        savePosition()
        super.onStop()
    }

    /**
     * Keeps the page a PDF is on, or the place a book is at, for the next time it is opened.
     * See [Positions].
     *
     * On stop, rather than every time the readout changes, since a fling across three
     * hundred pages would otherwise be three hundred writes of a number of which only the
     * last matters. Stop arrives for Back, Home, a switch to another app and the screen
     * going off alike, and Android does not reclaim the process for memory before it. A
     * book's page works its place out a moment after it stops moving, so one that arrives
     * after the stop is saved as it arrives.
     *
     * Nothing until the page has reported, so a document closed before it finished
     * opening keeps the page it had from last time.
     */
    private fun savePosition() {
        val key = positionKey ?: return
        if (pageAt < 1 || pageTotal < 2) return
        // A book that has said how far through it is but not yet where keeps the place it had
        if (book && bookPlace == null) return
        Positions.save(this, key, pageAt, pageTotal, bookPlace)
    }

    override fun onDestroy() {
        playerScreen?.release()
        doubleTapZoom?.cancel()
        webView?.destroy()
        webView = null
        simplePdf?.close()
        simplePdf = null
        // Written again from the intent whenever the viewer is made again, so only a closing one
        // takes it away
        if (isFinishing) sharedText?.delete()
        super.onDestroy()
    }
}

/**
 * The text zoom a book's WebView is given: [phone] is the zoom it came with, [chosen] the
 * reader's size as a percentage of that, and [reflows] whether the chapter on screen is one
 * that takes either. See [ViewerActivity.applyTextSize].
 */
internal fun bookTextZoom(phone: Int, chosen: Int, reflows: Boolean): Int =
    if (reflows) phone * chosen / 100 else 100

/**
 * How far through a book the reader is, from the stretch of it on screen and how many there
 * are: nothing at the first, all of it at the last, which is the foot of its last chapter.
 */
internal fun bookPercent(at: Int, total: Int): Int =
    if (total < 2) 100 else ((at - 1) * 100f / (total - 1)).roundToInt().coerceIn(0, 100)
