package com.arjun.gander

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private sealed interface Row {
        data class Header(val title: String) : Row
        data class Hint(val text: String) : Row
        data class Item(
            val badge: String,
            val color: Int,
            val title: String,
            val subtitle: String?,
            val onClick: () -> Unit,
            val onLongClick: (() -> Unit)? = null,
            val thumbUri: Uri? = null,
            val thumbExt: String = ""
        ) : Row
    }

    /**
     * What one pass over a location produced: the rows to draw, and whether this is a
     * first run and so wants the welcome block in place of the list.
     *
     * The flag is carried rather than inferred from an empty list. "This folder is empty"
     * and "nothing has ever been opened" both produce no rows and only the second one
     * replaces the screen.
     */
    private data class Screen(val rows: List<Row>, val welcome: Boolean = false)

    private data class Crumb(val treeUri: Uri, val docId: String, val label: String)

    private val stack = ArrayDeque<Crumb>()
    private val adapter = RowAdapter()
    private lateinit var toolbar: MaterialToolbar
    private lateinit var lockup: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var list: RecyclerView
    private lateinit var welcome: View
    private lateinit var fab: ExtendedFloatingActionButton

    /**
     * The last "Removed" toast, kept only so the next one can cancel it.
     *
     * The framework queues toasts rather than replacing them, and each one is shown for
     * its full duration. Clearing a dozen recents in a couple of seconds therefore left
     * a dozen badges to play out one after another, still appearing half a minute after
     * the last thing was removed. Cancelling the one in flight collapses a burst to a
     * single badge that goes away shortly after the reader stops.
     */
    private var removedToast: Toast? = null

    /**
     * Where the rows are built.
     *
     * Reading a granted folder is a query to another app's DocumentsProvider, and so is
     * asking a tree for its display name. Both were done inline in render(), which runs
     * on every resume and every tap, so a folder holding a few thousand files, or a
     * provider on an SD card, a USB stick or a cloud account, froze the home screen and
     * would eventually have shown up as an ANR. Play tracks ANR rate and a bad one
     * suppresses the listing, which makes this the one item on the pre-launch list that
     * could quietly cost reach.
     *
     * Single threaded on purpose: one folder is being looked at at a time, and it keeps
     * treeLabels below confined to one thread without a lock.
     */
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Bumped by every render. A load that finishes after another has been asked for is
     * dropped rather than drawn: tapping through three folders quickly used to be three
     * blocking reads in order, and now it is three racing ones, only the last of which
     * describes where the reader actually is.
     */
    private var renderToken = 0

    /**
     * Display names for granted trees, which cost a query each and never change while
     * the grant lasts. Read and written only on [loader], so it needs no synchronising.
     */
    private val treeLabels = mutableMapOf<String, String>()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            stack.removeLast()
            render()
        }
    }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openInViewer(uri)
        }

    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                render()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        toolbar = findViewById(R.id.toolbar)
        lockup = findViewById(R.id.lockup)
        toolbar.setNavigationOnClickListener { backCallback.handleOnBackPressed() }
        // render() rewrites the title and navigation icon on every resume and on
        // every folder change, but never touches the menu, so inflating once here
        // survives all of it.
        toolbar.inflateMenu(R.menu.main_menu)
        // Set once, like the inflate: the installer cannot change while this process
        // lives, because any reinstall or update kills the process first.
        toolbar.menu.findItem(R.id.action_rate).isVisible = installedFromPlay()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rate -> { openPlayListing(); true }
                R.id.action_share_app -> { shareGander(); true }
                R.id.action_about -> { showAbout(); true }
                else -> false
            }
        }
        progress = findViewById(R.id.loadProgress)
        list = findViewById(R.id.list)
        // One column on a phone, which is exactly what a LinearLayoutManager did, and two
        // on a tablet, where a single column of filenames left about nine tenths of the
        // screen empty. Headers and hints label the whole list rather than a cell, so they
        // take every span.
        val columns = resources.getInteger(R.integer.home_list_columns)
        list.layoutManager = GridLayoutManager(this, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (adapter.isFullSpan(position)) columns else 1
            }.apply {
                // Uncached, getSpanIndex walks getSpanSize from zero for every item, so
                // laying out a large folder is quadratic in its length. Free on a phone,
                // where one column returns immediately, and not on the tablet this grid
                // exists for. submit's notifyDataSetChanged already clears the cache.
                isSpanIndexCacheEnabled = true
                isSpanGroupIndexCacheEnabled = true
            }
        }
        list.adapter = adapter

        // Built once here rather than on every render: the nine kinds Gander opens do not
        // change while it is running, and the block itself is shown or hidden, not rebuilt.
        welcome = findViewById(R.id.welcome)
        fillFormatGrid(findViewById(R.id.formatGrid))

        // Three controls, two destinations. The FAB and the welcome block's filled button
        // are the same action seen in two states of the screen, and the outlined button is
        // what the "+ Add a folder" row does once there is a list to put it in.
        fab = findViewById(R.id.openFab)
        val openFile = View.OnClickListener { openDocument.launch(arrayOf("*/*")) }
        fab.setOnClickListener(openFile)
        findViewById<View>(R.id.openFileButton).setOnClickListener(openFile)
        findViewById<View>(R.id.addFolderButton).setOnClickListener { openTree.launch(null) }

        restoreStack(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    /**
     * Keeps the reader where they were browsing across a configuration change.
     *
     * This activity is recreated on a rotation, a font size change, a theme change and a
     * multi-window resize, and [stack] is an ordinary field, so all of them used to drop
     * whoever was three folders deep straight back to the root with no way to tell why.
     * A phone is rarely rotated mid-browse and a tablet is rotated constantly, which is
     * where this was found.
     *
     * Three parallel lists rather than a Parcelable Crumb: a crumb is a URI and two
     * strings, and this needs no new type, no @Parcelize plugin, and none of the
     * getParcelableArrayList deprecation dance.
     *
     * The rows are not saved with it. They come from a provider that may have changed
     * while the activity was gone, so onResume re-reads the folder rather than restoring
     * a stale listing of it.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_TREE_URIS, ArrayList(stack.map { it.treeUri.toString() }))
        outState.putStringArrayList(STATE_DOC_IDS, ArrayList(stack.map { it.docId }))
        outState.putStringArrayList(STATE_LABELS, ArrayList(stack.map { it.label }))
    }

    private fun restoreStack(state: Bundle?) {
        val uris = state?.getStringArrayList(STATE_TREE_URIS) ?: return
        val docIds = state.getStringArrayList(STATE_DOC_IDS) ?: return
        val labels = state.getStringArrayList(STATE_LABELS) ?: return
        // Defensive: a truncated Bundle would otherwise index out of bounds, and landing
        // at the root is the same place a failure here would land anyway.
        if (uris.size != docIds.size || uris.size != labels.size) return
        uris.indices.forEach { i ->
            stack.addLast(Crumb(Uri.parse(uris[i]), docIds[i], labels[i]))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun openInViewer(uri: Uri) {
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    /**
     * The app's only About surface. Carries the version, who made it, and the
     * permission list read back out of Android, plus the way in to the licence
     * text the bundled libraries require to travel with the binary.
     */
    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()
        view.findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version, version)

        val permissions = requestedPermissions()
        val field = view.findViewById<TextView>(R.id.aboutPermissions)
        when {
            // Only when the package manager refused to answer. Printing "none"
            // for a question we could not ask would be the one dishonest thing
            // this dialog could do, so it says nothing at all instead.
            permissions == null ->
                view.findViewById<View>(R.id.aboutPermissionsCard).visibility = View.GONE
            permissions.isEmpty() -> field.setText(R.string.about_permissions_none)
            // Never expected: assembleRelease fails before a build can get here.
            // Shown rather than swallowed, because a broken promise is the thing
            // a reader of this dialog most needs to know.
            else -> {
                field.text = permissions.joinToString("\n")
                field.setTextColor(
                    MaterialColors.getColor(field, com.google.android.material.R.attr.colorError)
                )
            }
        }

        view.findViewById<View>(R.id.aboutAuthor)
            .setOnClickListener { openUrl(getString(R.string.url_author)) }
        view.findViewById<View>(R.id.aboutSource)
            .setOnClickListener { openUrl(getString(R.string.url_source)) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_gander)
            .setView(view)
            .setPositiveButton(R.string.about_close, null)
            .show()

        view.findViewById<View>(R.id.aboutLicences).setOnClickListener {
            dialog.dismiss()
            openLicences()
        }
    }

    /**
     * What Android says this install asks for, or null if it would not say.
     *
     * androidx.core declares DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION under our
     * own package name so libraries can registerReceiver safely. It is signature
     * level, self-granted and never shown to a user, which is why the permission
     * check in build.gradle.kts allowlists it as well. Anything else carrying our
     * package prefix is ours on the same reasoning, so drop those and report what
     * is left, which is the list Android would actually confront someone with.
     */
    private fun requestedPermissions(): List<String>? = runCatching {
        packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .filterNot { it.startsWith("$packageName.") }
    }.getOrNull()

    /**
     * Gander shows its own licences. The asset is copied into the cache and
     * handed to the viewer as a plain path, so the bundled Markdown renderer
     * draws it and there is no second document surface to keep alive.
     *
     * Copied on every open rather than once: the cache outlives an app update,
     * and an update is exactly when the text changes. The viewer only records
     * content:// URIs in Recents, so this cannot turn up there.
     */
    private fun openLicences() {
        val file = File(cacheDir, getString(R.string.licences_file_name))
        val opened = runCatching {
            assets.open(LICENCES_ASSET).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
            )
        }.isSuccess
        if (!opened) Toast.makeText(this, R.string.licences_failed, Toast.LENGTH_SHORT).show()
    }

    /**
     * Hands a URL to whichever browser the user has. Gander never fetches
     * anything itself, and without the INTERNET permission it could not.
     */
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }

    /**
     * Whether Google Play is this install's installer of record, the only case where Rate
     * can go anywhere: Play takes ratings from nobody else, so on a copy from GitHub or
     * F-Droid it would open a listing that cannot be rated. Asking about our own package
     * needs no permission and no <queries> entry.
     */
    private fun installedFromPlay(): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
        installer == PLAY_STORE
    }.getOrDefault(false)

    /**
     * Gander's listing, sent to the Play Store app by name so another store that also
     * answers market:// links cannot catch it, and to the browser if Play will not take it.
     */
    private fun openPlayListing() {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
            .setPackage(PLAY_STORE)
        runCatching { startActivity(market) }
            .onFailure { openUrl(getString(R.string.url_play_listing, packageName)) }
    }

    /**
     * A line and a link through the system share sheet, as the viewer shares a file.
     * Gander leaves itself out of the sheet: it accepts shared text, and opening its own
     * link as a document helps nobody.
     */
    private fun shareGander() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_text, getString(R.string.url_site)))
        val chooser = Intent.createChooser(send, getString(R.string.share_app))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, ViewerActivity::class.java)))
        runCatching { startActivity(chooser) }
    }

    /**
     * Redraws the screen for wherever the reader is now.
     *
     * The toolbar changes at once and the list follows, because the title is known here
     * and the rows are not: they come from a provider that may be slow. Nothing is
     * cleared in the meantime, so returning from a document leaves the old list on
     * screen until the new one is ready rather than blinking through empty.
     */
    private fun render() {
        val here = stack.lastOrNull()
        backCallback.isEnabled = here != null
        // At the root the wordmark is the title, centred; inside a folder the title is the
        // folder's name, where Android puts it, beside the back arrow. A mark is an identity
        // and a folder name is a location, so these are two kinds of content sharing a slot
        // rather than one element that moves.
        toolbar.title = here?.label.orEmpty()
        lockup.visibility = if (here == null) View.VISIBLE else View.GONE
        toolbar.navigationIcon =
            if (here == null) null
            else androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_back)
        toolbar.navigationContentDescription = getString(R.string.back)

        val token = ++renderToken
        // Delayed rather than shown at once. Most folders come back in a few
        // milliseconds, and a bar that appears and vanishes inside one frame reads as a
        // flicker rather than as progress.
        val announce = Runnable {
            if (token == renderToken && !isDestroyed) progress.visibility = View.VISIBLE
        }
        main.postDelayed(announce, RENDER_PROGRESS_DELAY_MS)

        loader.execute {
            // Checked here as well as after, because loader is a single thread: without
            // this, tapping into a folder and straight back out makes the second read
            // wait for the whole of the first, which on the slow provider this exists
            // for is the wait it was meant to remove.
            if (token != renderToken) return@execute
            val screen = if (here == null) homeRows() else folderRows(here)
            main.post {
                main.removeCallbacks(announce)
                if (token != renderToken || isDestroyed) return@post
                progress.visibility = View.GONE
                // Inside the token guard, so a slow load that finishes after the reader has
                // moved on cannot put the welcome block back over a folder they are in.
                welcome.visibility = if (screen.welcome) View.VISIBLE else View.GONE
                list.visibility = if (screen.welcome) View.GONE else View.VISIBLE
                if (screen.welcome) fab.hide() else fab.show()
                adapter.submit(screen.rows)
            }
        }
    }

    /** Shows the removal badge, replacing any still on screen rather than queueing behind it. */
    private fun toastRemoved() {
        removedToast?.cancel()
        removedToast = Toast.makeText(this, R.string.removed, Toast.LENGTH_SHORT)
            .also { it.show() }
    }

    private fun homeRows(): Screen {
        val recents = Recents.all(this)
        // Labelled first, then sorted. sortedBy runs its selector on every comparison,
        // so naming the tree inside it cost a provider query per comparison rather than
        // one per folder.
        val roots = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && isTreeUri(it.uri) }
            .map { it to treeLabel(it.uri) }
            .sortedBy { (_, label) -> label.lowercase() }

        // Nothing opened and nothing granted is a first run, and a first run gets the
        // welcome block in place of the list rather than two empty headings above three
        // paragraphs about what this is. Once either has happened the reader knows, and
        // the ordinary list comes back for good.
        if (recents.isEmpty() && roots.isEmpty()) return Screen(emptyList(), welcome = true)

        val rows = mutableListOf<Row>()
        rows += Row.Header(getString(R.string.recent_files))
        if (recents.isEmpty()) {
            rows += Row.Hint(getString(R.string.no_recents_hint))
        } else {
            recents.forEach { r ->
                val (badge, color) = badgeFor(r.name, null)
                val ext = r.name.substringAfterLast('.', "").lowercase()
                val uri = Uri.parse(r.uri)
                rows += Row.Item(
                    badge, color, r.name,
                    DateUtils.getRelativeTimeSpanString(r.time).toString(),
                    onClick = { openInViewer(uri) },
                    onLongClick = {
                        Recents.remove(this, r.uri)
                        Thumbs.evict(this, r.uri)
                        toastRemoved()
                        render()
                    },
                    thumbUri = uri.takeIf { Thumbs.supported(FileKind.detect(ext, null), ext) },
                    thumbExt = ext
                )
            }
        }
        rows += Row.Header(getString(R.string.folders))
        if (roots.isEmpty()) rows += Row.Hint(getString(R.string.no_folders_hint))
        roots.forEach { (perm, label) ->
            rows += Row.Item(
                "DIR", DIR_COLOR, label, null,
                onClick = {
                    stack.addLast(
                        Crumb(perm.uri, DocumentsContract.getTreeDocumentId(perm.uri), label)
                    )
                    render()
                },
                // The only confirmation in the app, because this is the only thing on the
                // screen that cannot be undone. Android has no inverse for a released
                // permission: takePersistableUriPermission needs a live grant from an intent
                // result, so once this has run the way back is the system picker.
                onLongClick = {
                    val dialog = MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.remove_folder_title)
                        .setMessage(getString(R.string.remove_folder_message, label))
                        .setPositiveButton(R.string.remove) { _, _ ->
                            runCatching {
                                contentResolver.releasePersistableUriPermission(
                                    perm.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            toastRemoved()
                            render()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()
                    dialog.show()
                    // Tinted after show(): getButton returns null until the dialog is laid
                    // out. Both buttons are set rather than only the destructive one,
                    // because Gander's own primary is a burnt red: an error-coloured Remove
                    // beside an untouched Cancel measures dE 4.6 on the light palette, near
                    // enough to the 2.3 a person can notice that it marks nothing and only
                    // makes Cancel look dangerous too. Standing the dismissive button down
                    // to a neutral is what leaves the red meaning one thing.
                    listOf(
                        AlertDialog.BUTTON_POSITIVE to
                            com.google.android.material.R.attr.colorError,
                        AlertDialog.BUTTON_NEGATIVE to
                            com.google.android.material.R.attr.colorOnSurfaceVariant
                    ).forEach { (which, attr) ->
                        dialog.getButton(which).let {
                            it.setTextColor(MaterialColors.getColor(it, attr))
                        }
                    }
                }
            )
        }
        rows += Row.Item("+", ADD_COLOR, getString(R.string.add_folder), null,
            onClick = { openTree.launch(null) })
        return Screen(rows)
    }

    private fun folderRows(crumb: Crumb): Screen {
        data class Child(
            val docId: String, val name: String, val mime: String,
            val size: Long, val modified: Long
        )

        val children = mutableListOf<Child>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            crumb.treeUri, crumb.docId
        )
        runCatching {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    children += Child(
                        c.getString(0), c.getString(1) ?: "?", c.getString(2) ?: "",
                        c.getLong(3), c.getLong(4)
                    )
                }
            }
        }

        // sortedWith and not sortedBy, for the reason homeRows gives: the selector runs
        // on every comparison, so lowercase() there allocated some sixteen thousand
        // strings on a folder of fifteen hundred files rather than none.
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { c: Child -> c.name }
        val dirs = children
            .filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
            .filterNot { it.name.startsWith(".") }
            .sortedWith(byName)
        val files = children
            .filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
            .filterNot { it.name.startsWith(".") }
            .sortedWith(byName)

        val rows = mutableListOf<Row>()
        dirs.forEach { d ->
            rows += Row.Item("DIR", DIR_COLOR, d.name, null, onClick = {
                stack.addLast(Crumb(crumb.treeUri, d.docId, d.name))
                render()
            })
        }
        files.forEach { f ->
            val (badge, color) = badgeFor(f.name, f.mime)
            val ext = f.name.substringAfterLast('.', "").lowercase()
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(crumb.treeUri, f.docId)
            val subtitle = listOfNotNull(
                Formatter.formatShortFileSize(this, f.size).takeIf { f.size > 0 },
                DateUtils.getRelativeTimeSpanString(f.modified).toString()
                    .takeIf { f.modified > 0 }
            ).joinToString(" · ").ifEmpty { null }
            rows += Row.Item(
                badge, color, f.name, subtitle,
                onClick = { openInViewer(fileUri) },
                thumbUri = fileUri.takeIf {
                    Thumbs.supported(FileKind.detect(ext, f.mime), ext)
                },
                thumbExt = ext
            )
        }
        if (rows.isEmpty()) rows += Row.Hint(getString(R.string.empty_folder))
        return Screen(rows)
    }

    private fun isTreeUri(uri: Uri): Boolean =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.isSuccess &&
            uri.pathSegments.firstOrNull() == "tree"

    /** Cached: the name of a granted tree costs a query and does not change. */
    private fun treeLabel(uri: Uri): String =
        treeLabels.getOrPut(uri.toString()) { readTreeLabel(uri) }

    private fun readTreeLabel(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return "Folder"
        val name = runCatching {
            contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(uri, id),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return name ?: id.substringAfterLast(':').ifEmpty { id }
    }

    /**
     * The two or three letters, and the colour behind them, for a file.
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
    private fun badgeFor(name: String, mime: String?): Pair<String, Int> {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (FileKind.detect(ext, mime)) {
            FileKind.PDF -> PDF_BADGE
            FileKind.DOCX -> DOC_BADGE
            FileKind.XLSX -> XLS_BADGE
            FileKind.PPTX -> PPT_BADGE
            FileKind.IMAGE, FileKind.IMAGE_WEB -> IMG_BADGE
            FileKind.PLAYER -> if (FileKind.isAudioExt(ext)) AUD_BADGE else VID_BADGE
            FileKind.MD -> MD_BADGE
            FileKind.TEXT -> TXT_BADGE
            FileKind.UNSUPPORTED -> "FILE" to 0xFF607884.toInt()
        }
    }

    /**
     * Draws the nine tiles of the welcome grid, from the same pairs [badgeFor] returns.
     *
     * The grid is filled here rather than declared nine times in the layout so that a new
     * file kind is one line in [WELCOME_BADGES] and the first screen cannot end up naming
     * a different set of things from the rows underneath it. The tint is the same call the
     * adapter makes on a real row.
     */
    private fun fillFormatGrid(grid: ViewGroup) {
        WELCOME_BADGES.forEach { (label, color) ->
            val tile = layoutInflater.inflate(R.layout.view_welcome_tile, grid, false) as TextView
            tile.text = label
            tile.background.mutate().setTint(color)
            grid.addView(tile)
        }
        // One description for all nine, and screenReaderFocusable is what makes the grid a
        // single stop rather than a container TalkBack walks into.
        grid.contentDescription = getString(R.string.welcome_formats_spoken)
        ViewCompat.setScreenReaderFocusable(grid, true)
    }

    override fun onDestroy() {
        // Anything already queued still runs to completion and the thread ends with it,
        // rather than outliving the activity it was drawing.
        loader.shutdown()
        super.onDestroy()
    }

    private companion object {
        /**
         * The badge for each file kind, named once.
         *
         * These used to be nine hex literals inside badgeFor. They are pulled out because
         * the welcome grid draws the same nine, and two hardcoded copies of a palette drift
         * the first time one is edited. Plain vals rather than consts: 0xFFB3261E.toInt()
         * is not a compile-time constant expression, which is why DIR_COLOR below has
         * always been one too.
         */
        val PDF_BADGE = "PDF" to 0xFFB3261E.toInt()
        val DOC_BADGE = "DOC" to 0xFF1565C0.toInt()
        val XLS_BADGE = "XLS" to 0xFF2E7D32.toInt()
        val PPT_BADGE = "PPT" to 0xFFB25000.toInt()
        val IMG_BADGE = "IMG" to 0xFF7B1FA2.toInt()
        val VID_BADGE = "VID" to 0xFFAD1457.toInt()
        val AUD_BADGE = "AUD" to 0xFF00838F.toInt()
        val MD_BADGE = "MD" to 0xFF455A64.toInt()
        val TXT_BADGE = "TXT" to 0xFF616161.toInt()

        /**
         * What the welcome grid shows, in reading order.
         *
         * Kinds rather than formats, which is what makes the grid hold still: FileKind maps
         * 77 extensions onto these nine, so adding .odt or .rst or another codec changes
         * nothing here. A tenth tile means a tenth renderer, and the layout's columnCount is
         * the number to revisit when that happens.
         *
         * FILE is deliberately absent. It is what an unsupported file falls back to, and
         * this grid is a list of what Gander opens.
         *
         * One thing here does not update itself: welcome_formats_spoken is the sentence a
         * screen reader hears in place of these tiles, and it is prose. Adding a kind means
         * editing that string too, or the grid and its description stop agreeing.
         */
        val WELCOME_BADGES = listOf(
            PDF_BADGE, DOC_BADGE, XLS_BADGE,
            PPT_BADGE, IMG_BADGE, VID_BADGE,
            AUD_BADGE, MD_BADGE, TXT_BADGE,
        )

        val DIR_COLOR = 0xFF8A6D1F.toInt()

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
        val ADD_COLOR = 0xFFAF2D18.toInt()
        const val LICENCES_ASSET = "licences.md"

        /** Google Play's package: the installer Rate depends on, and the app it opens. */
        const val PLAY_STORE = "com.android.vending"

        /** How long a folder may take to read before the screen says anything about it. */
        const val RENDER_PROGRESS_DELAY_MS = 150L

        /** Where the reader had browsed to, kept across a configuration change. */
        const val STATE_TREE_URIS = "stack.treeUris"
        const val STATE_DOC_IDS = "stack.docIds"
        const val STATE_LABELS = "stack.labels"
    }

    private class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = mutableListOf<Row>()

        fun submit(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        /**
         * Whether the row at [position] wants the whole width rather than one cell.
         *
         * Out-of-range answers full span on purpose: the layout manager can ask about a
         * position mid-update, and a header-shaped guess reflows harmlessly where a
         * cell-shaped one would throw.
         */
        fun isFullSpan(position: Int): Boolean =
            position !in rows.indices || rows[position] !is Row.Item

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            is Row.Hint -> 1
            is Row.Item -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = when (viewType) {
                0 -> R.layout.row_header
                1 -> R.layout.row_hint
                else -> R.layout.row_item
            }
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header ->
                    holder.itemView.findViewById<TextView>(R.id.headerText).text = row.title
                is Row.Hint ->
                    holder.itemView.findViewById<TextView>(R.id.hintText).text = row.text
                is Row.Item -> {
                    val badge = holder.itemView.findViewById<TextView>(R.id.badge)
                    val thumb = holder.itemView.findViewById<ImageView>(R.id.thumb)
                    badge.text = row.badge
                    badge.background.mutate().setTint(row.color)
                    badge.visibility = View.VISIBLE
                    thumb.visibility = View.GONE
                    thumb.setImageDrawable(null)
                    thumb.tag = null
                    if (row.thumbUri != null) {
                        Thumbs.load(
                            holder.itemView.context, row.thumbUri, row.thumbExt, thumb, badge
                        )
                    }
                    holder.itemView.findViewById<TextView>(R.id.title).text = row.title
                    val sub = holder.itemView.findViewById<TextView>(R.id.subtitle)
                    sub.text = row.subtitle
                    sub.visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
                    // The row children are not-important for accessibility, so this is
                    // the whole announcement. Keeping the badge in it matters: the badge
                    // is hidden once a thumbnail loads, and the file type would go with it
                    holder.itemView.contentDescription =
                        listOfNotNull(row.title, row.badge, row.subtitle).joinToString(", ")
                    holder.itemView.setOnClickListener { row.onClick() }
                    // Long-press is how a row is removed, and nothing on screen says so.
                    // Naming it for TalkBack is the one place that gesture is announced, so
                    // the rows that do not have it must not claim it either: binding a
                    // listener at all sets isLongClickable, which used to leave headings and
                    // "Add a folder" advertising a press that did nothing.
                    val remover = row.onLongClick
                    if (remover == null) {
                        holder.itemView.setOnLongClickListener(null)
                        // Clearing the listener does not clear the flag it set
                        holder.itemView.isLongClickable = false
                        ViewCompat.replaceAccessibilityAction(
                            holder.itemView, AccessibilityActionCompat.ACTION_LONG_CLICK,
                            null, null
                        )
                    } else {
                        holder.itemView.setOnLongClickListener { remover(); true }
                        // Relabels the gesture and nothing else: a null command keeps the
                        // default behaviour, so this reads "double tap and hold to Remove"
                        ViewCompat.replaceAccessibilityAction(
                            holder.itemView, AccessibilityActionCompat.ACTION_LONG_CLICK,
                            holder.itemView.context.getString(R.string.remove), null
                        )
                    }
                }
            }
        }
    }
}
