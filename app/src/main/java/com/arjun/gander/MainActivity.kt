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
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

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
    private val activeFilters = mutableSetOf<String>()
    private val adapter = RowAdapter()
    private lateinit var toolbar: MaterialToolbar
    private lateinit var lockup: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var list: RecyclerView
    private lateinit var welcome: View
    private lateinit var fab: ExtendedFloatingActionButton

    /**
     * A row taken off the screen whose removal waits for its Undo bar to go, issue #39.
     *
     * Nothing is given back until then, because a released grant has no inverse: Android
     * takes a grant only from the picker, so an Undo made after it would have nothing to
     * restore. The row is only hidden meanwhile. The removal goes through when the bar
     * does, when another removal takes its place, or when this screen stops, since a bar
     * that has gone can no longer be answered.
     */
    private var pending: Pending? = null
    private var undoBar: Snackbar? = null

    /** The row's URI, which is what hides it, and what removing it does. */
    private class Pending(val uri: String, val commit: () -> Unit)

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
    @androidx.annotation.VisibleForTesting
    internal var loader: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor()
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
            if (stack.isEmpty()) {
                activeFilters.clear()
            }
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
        // lives, because any reinstall or update kills the process first. The item reads
        // Switch to Google Play unless Play installed this copy, so only Play's own copy
        // is asked to rate.
        if (installedFromPlay()) toolbar.menu.findItem(R.id.action_play).setTitle(R.string.rate_app)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_filter -> { showFilterDialog(); true }
                R.id.action_play -> { openPlayListing(); true }
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
        ItemTouchHelper(SwipeToRemove(this, adapter)).attachToRecyclerView(list)

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
        restoreFilters(savedInstanceState)
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
        outState.putStringArrayList(STATE_ACTIVE_FILTERS, ArrayList(activeFilters))
    }

    private fun restoreFilters(state: Bundle?) {
        val saved = state?.getStringArrayList(STATE_ACTIVE_FILTERS) ?: return
        activeFilters.clear()
        activeFilters.addAll(saved)
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

    /**
     * Takes any tap highlight off before this screen leaves.
     *
     * A tap that opens a document starts a ripple, and the viewer covers this screen while it
     * is still fading. A back swipe then shows this screen as it was last drawn on the way out,
     * so the row that was tapped came back lit for a moment. The back arrow never showed it,
     * because it waits for this screen to draw again first.
     *
     * Here rather than on the way back in: clearing it in onStart was tried on a phone and
     * changed nothing, because the swipe shows frames drawn before onStart runs. Both lines are
     * needed. Unpressing starts the ripple's fade, and the fade is what would be drawn; the jump
     * ends it on the spot.
     *
     * The cost is that a tap which leaves the screen shows its highlight for a frame or two
     * rather than through the transition. The next screen sliding in is feedback enough.
     */
    override fun onPause() {
        super.onPause()
        window.decorView.isPressed = false
        window.decorView.jumpDrawablesToCurrentState()
    }

    /** A removal waiting on its Undo bar goes through, since nobody is here to answer it. */
    override fun onStop() {
        super.onStop()
        finishRemoving()
        undoBar?.dismiss()
    }

    private fun openInViewer(uri: Uri) {
        // By the viewer's internal name, which is what lets it trust the URI and file it
        // in Recents. See ViewerActivity.INTERNAL_VIEWER.
        startActivity(
            Intent()
                .setClassName(this, ViewerActivity.INTERNAL_VIEWER)
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
                Intent()
                    .setClassName(this, ViewerActivity.INTERNAL_VIEWER)
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
     * can go anywhere: Play takes ratings from nobody else, so a copy from GitHub or
     * F-Droid is offered Switch to Google Play in that place instead. Asking about our own
     * package needs no permission and no <queries> entry.
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

        val inFolder = here != null
        toolbar.menu.findItem(R.id.action_sort)?.isVisible = inFolder
        toolbar.menu.findItem(R.id.action_filter)?.isVisible = inFolder
        toolbar.menu.findItem(R.id.action_play)?.isVisible = !inFolder
        toolbar.menu.findItem(R.id.action_share_app)?.isVisible = !inFolder
        toolbar.menu.findItem(R.id.action_about)?.isVisible = !inFolder

        val filterSummary = if (inFolder && activeFilters.isNotEmpty()) {
            SUPPORTED_FILTER_TYPES
                .map { it.badge }
                .filter { it in activeFilters }
                .joinToString(", ")
        } else {
            null
        }
        toolbar.subtitle = if (filterSummary != null) getString(R.string.filter_subtitle, filterSummary) else null

        val token = ++renderToken
        // Delayed rather than shown at once. Most folders come back in a few
        // milliseconds, and a bar that appears and vanishes inside one frame reads as a
        // flicker rather than as progress.
        val announce = Runnable {
            if (token == renderToken && !isDestroyed) progress.visibility = View.VISIBLE
        }
        main.postDelayed(announce, RENDER_PROGRESS_DELAY_MS)
        // Read here, on the main thread, which is the only one that changes it
        val hidden = pending?.uri
        val filters = activeFilters.toSet()

        loader.execute {
            // Checked here as well as after, because loader is a single thread: without
            // this, tapping into a folder and straight back out makes the second read
            // wait for the whole of the first, which on the slow provider this exists
            // for is the wait it was meant to remove.
            if (token != renderToken) return@execute
            val screen = if (here == null) homeRows(hidden) else folderRows(here, filters)
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

    /**
     * Asks before a row goes, and calls [kept] if the answer is no. Removing a recent file or a
     * folder gives back Gander's access to it, and Android has no inverse for that, so once the
     * Undo bar has gone the way back is the system picker. The row is swiped away over a bin,
     * so the question says the file or folder itself stays where it is.
     */
    private fun askToRemove(title: Int, message: String, kept: () -> Unit, remove: () -> Unit) {
        var removing = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.remove) { _, _ ->
                removing = true
                remove()
            }
            .setNegativeButton(android.R.string.cancel, null)
            // Cancel, Back and a tap outside alike
            .setOnDismissListener { if (!removing) kept() }
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

    /**
     * Hides the row for [uri] and offers Undo, doing [commit] only once the offer has gone.
     * See [pending].
     */
    private fun removeLater(uri: String, commit: () -> Unit) {
        // One at a time: the bar for this one takes the last one's place
        finishRemoving()
        val removal = Pending(uri, commit)
        pending = removal
        render()
        undoBar = Snackbar.make(list, R.string.removed, Snackbar.LENGTH_LONG)
            // Above the Open a file button rather than over it. The button is up while the bar
            // is, since a row being removed still counts in homeRows' first run test.
            .setAnchorView(fab)
            .setAction(R.string.undo) {
                if (pending === removal) {
                    pending = null
                    render()
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar, event: Int) {
                    if (event != DISMISS_EVENT_ACTION && pending === removal) {
                        finishRemoving()
                        render()
                    }
                }
            })
            .also { it.show() }
    }

    /** Carries out the removal waiting on an Undo bar, if one is. */
    private fun finishRemoving() {
        val removal = pending ?: return
        pending = null
        removal.commit()
    }

    /** The home screen, without the row for [hidden], whose removal waits on its Undo bar. */
    private fun homeRows(hidden: String?): Screen {
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
        // the ordinary list comes back for good. A row waiting on its Undo bar still
        // counts, so undoing the last one never has to bring the list back.
        if (recents.isEmpty() && roots.isEmpty()) return Screen(emptyList(), welcome = true)
        val shownRecents = recents.filter { it.uri != hidden }
        val shownRoots = roots.filter { (perm, _) -> perm.uri.toString() != hidden }

        val rows = mutableListOf<Row>()
        rows += Row.Header(getString(R.string.recent_files))
        if (shownRecents.isEmpty()) {
            rows += Row.Hint(getString(R.string.no_recents_hint))
        } else {
            shownRecents.forEach { r ->
                val (badge, color) = badgeFor(r.name, null)
                val ext = r.name.substringAfterLast('.', "").lowercase()
                val uri = Uri.parse(r.uri)
                rows += Row.Item(
                    badge, color, r.name,
                    DateUtils.getRelativeTimeSpanString(r.time).toString(),
                    onClick = { openInViewer(uri) },
                    onRemove = { kept ->
                        askToRemove(
                            R.string.remove_recent_title,
                            getString(R.string.remove_recent_message, r.name),
                            kept
                        ) {
                            removeLater(r.uri) {
                                Recents.remove(this, r.uri)
                                Thumbs.evict(this, r.uri)
                            }
                        }
                    },
                    thumbUri = uri.takeIf { Thumbs.supported(FileKind.detect(ext, null), ext) },
                    thumbExt = ext
                )
            }
        }
        rows += Row.Header(getString(R.string.folders))
        if (shownRoots.isEmpty()) rows += Row.Hint(getString(R.string.no_folders_hint))
        shownRoots.forEach { (perm, label) ->
            rows += Row.Item(
                "DIR", DIR_COLOR, label, null,
                onClick = {
                    stack.addLast(
                        Crumb(perm.uri, DocumentsContract.getTreeDocumentId(perm.uri), label)
                    )
                    render()
                },
                onRemove = { kept ->
                    askToRemove(
                        R.string.remove_folder_title,
                        getString(R.string.remove_folder_message, label),
                        kept
                    ) {
                        removeLater(perm.uri.toString()) {
                            // Write as well as read: a build that could rename files took both
                            // for a folder, and giving back only the read half left the write
                            // half behind, out of sight, since the list shows read grants only.
                            // Releasing a half that is not held changes nothing.
                            runCatching {
                                contentResolver.releasePersistableUriPermission(
                                    perm.uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                            }
                        }
                    }
                }
            )
        }
        rows += Row.Item("+", ADD_COLOR, getString(R.string.add_folder), null,
            onClick = { openTree.launch(null) })
        return Screen(rows)
    }

    private fun folderRows(crumb: Crumb, filters: Set<String>): Screen {
        val children = mutableListOf<ChildDoc>()
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
                    children += ChildDoc(
                        c.getString(0), c.getString(1) ?: "?", c.getString(2) ?: "",
                        c.getLong(3), c.getLong(4)
                    )
                }
            }
        }

        val sortOrder = Settings.sortOrder(this)
        val (dirs, files) = orderChildren(children, sortOrder)

        val filteredFiles = if (filters.isEmpty()) {
            files
        } else {
            files.filter { f ->
                val (badge, _) = badgeFor(f.name, f.mime)
                badge in filters
            }
        }

        val rows = mutableListOf<Row>()
        dirs.forEach { d ->
            rows += Row.Item("DIR", DIR_COLOR, d.name, null, onClick = {
                stack.addLast(Crumb(crumb.treeUri, d.docId, d.name))
                render()
            })
        }
        filteredFiles.forEach { f ->
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
        if (rows.isEmpty()) {
            val emptyMsg = if (filters.isNotEmpty()) {
                getString(R.string.filter_empty)
            } else {
                getString(R.string.empty_folder)
            }
            rows += Row.Hint(emptyMsg)
        }
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
     * Draws the nine tiles of the welcome grid: eight of the pairs [badgeFor] returns, and
     * ETC for the kinds that have no tile.
     *
     * The grid is filled here rather than declared nine times in the layout so that the
     * first screen cannot end up naming a different set of things from the rows underneath
     * it. The tint is the same call the adapter makes on a real row.
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

    private fun showSortDialog() {
        val orders = Settings.SortOrder.values()
        val current = Settings.sortOrder(this)
        val items = orders.map { getString(it.labelRes) }.toTypedArray()
        val checkedItem = orders.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(items, checkedItem) { dialog, which ->
                Settings.setSortOrder(this, orders[which])
                render()
                dialog.dismiss()
            }
            .show()
    }

    private fun showFilterDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_filter, null)
        val container = view.findViewById<ViewGroup>(R.id.filterItemsContainer)
        val resetBtn = view.findViewById<View>(R.id.resetFilterButton)

        val tempSelected = activeFilters.toMutableSet()
        val checkBoxes = mutableListOf<MaterialCheckBox>()

        SUPPORTED_FILTER_TYPES.forEach { item ->
            val row = layoutInflater.inflate(R.layout.dialog_filter_item, container, false)
            val badge = row.findViewById<TextView>(R.id.filterBadge)
            val title = row.findViewById<TextView>(R.id.filterTitle)
            val checkbox = row.findViewById<MaterialCheckBox>(R.id.filterCheckbox)

            badge.text = item.badge
            badge.background.mutate().setTint(item.color)
            title.setText(item.labelRes)
            checkbox.isChecked = item.badge in tempSelected
            checkBoxes += checkbox

            row.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
                if (checkbox.isChecked) {
                    tempSelected += item.badge
                } else {
                    tempSelected -= item.badge
                }
            }

            container.addView(row)
        }

        resetBtn.setOnClickListener {
            tempSelected.clear()
            checkBoxes.forEach { it.isChecked = false }
        }

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                activeFilters.clear()
                activeFilters.addAll(tempSelected)
                render()
            }
            .show()
    }

    override fun onDestroy() {
        // Anything already queued still runs to completion and the thread ends with it,
        // rather than outliving the activity it was drawing.
        loader.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val LICENCES_ASSET = "licences.md"

        /** Google Play's package: the installer Rate depends on, and the app it opens. */
        const val PLAY_STORE = "com.android.vending"

        /** How long a folder may take to read before the screen says anything about it. */
        const val RENDER_PROGRESS_DELAY_MS = 150L

        /** Where the reader had browsed to, kept across a configuration change. */
        const val STATE_TREE_URIS = "stack.treeUris"
        const val STATE_DOC_IDS = "stack.docIds"
        const val STATE_LABELS = "stack.labels"
        const val STATE_ACTIVE_FILTERS = "active_filters"
    }
}
