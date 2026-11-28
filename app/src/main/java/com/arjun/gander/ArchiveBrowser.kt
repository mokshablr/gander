package com.arjun.gander

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.nio.charset.Charset
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A zip's flat list of paths, as folders.
 *
 * A zip need not list its folders at all, only the files in them, so every folder on the way
 * to a file exists here whether the archive has an entry for it or not. macOS puts a
 * __MACOSX folder of resource forks into every zip it makes, and that is left out, the way
 * orderChildren already leaves out dotfiles: neither is anything a person put there.
 */
internal class ArchiveTree(private val entries: List<ArchiveEntry>) {

    /**
     * One folder: the folders in it by name, and its files as indexes into [entries]. Each
     * folder holds its own name once, in the folder above, and never its whole path. Keyed by
     * path, a name tens of thousands of folders deep kept every path on the way down to it,
     * which is the square of its length: a zip of one 60 KB name asked for two gigabytes.
     */
    private class Folder {
        val folders = LinkedHashMap<String, Folder>()
        val files = ArrayList<Int>()
    }

    private val top = Folder()
    private var count = 0

    init {
        // Most files share a folder with the one before, so that one is kept to hand rather
        // than walked down to again: walking to every file's folder was a third of the time
        // a 100,000 entry archive took to list.
        var lastPath = ""
        var last = top
        entries.forEachIndexed { i, entry ->
            val path = entry.path
            if (path == MACOS_FORKS || path.startsWith("$MACOS_FORKS/")) return@forEachIndexed
            if (entry.isDirectory) {
                walk(path, make = true)
            } else {
                val parent = path.substringBeforeLast('/', "")
                if (parent != lastPath) {
                    last = walk(parent, make = true)!!
                    lastPath = parent
                }
                last.files += i
            }
        }
    }

    /**
     * The folder at [path], "" for the top, a name at a time, making each one that is not
     * there yet if [make] says so. A loop and not recursion, since a name can be tens of
     * thousands of folders deep.
     *
     * Every folder made is counted against [MAX_FOLDERS]. An index holds up to 32 MB of
     * names, and names that each start a chain of short folders of their own made millions of
     * them, well past what a phone's heap holds; past the limit the archive is too large to
     * list, as it is past ZipReader's own on entries.
     */
    private fun walk(path: String, make: Boolean): Folder? {
        var at = top
        if (path.isEmpty()) return at
        var start = 0
        while (true) {
            val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
            val name = path.substring(start, end)
            at = at.folders[name] ?: if (!make) {
                return null
            } else {
                if (++count > MAX_FOLDERS) throw ZipReader.TooLarge()
                Folder().also { at.folders[name] = it }
            }
            if (end == path.length) return at
            start = end + 1
        }
    }

    fun has(folder: String) = walk(folder, make = false) != null

    /**
     * The folders and files directly inside [folder], ordered and filtered exactly as the
     * folder browser orders and filters a folder on the phone, by the same function.
     */
    fun list(folder: String): Pair<List<String>, List<ArchiveEntry>> {
        val at = walk(folder, make = false) ?: return emptyList<String>() to emptyList()
        val children = at.folders.keys.map { ChildDoc(it, it, MIME_DIR, 0, 0) } +
            at.files.map { i ->
                val entry = entries[i]
                ChildDoc(i.toString(), entry.name, "", entry.size, entry.modified)
            }
        val (dirs, docs) = orderChildren(children)
        return dirs.map { it.name } to docs.map { entries[it.docId.toInt()] }
    }

    private companion object {
        const val MACOS_FORKS = "__MACOSX"

        /** As many folders as ZipReader lists entries, which no real archive comes near. */
        const val MAX_FOLDERS = 200_000
    }
}

/**
 * What is inside a .zip, as a list. Issue #30.
 *
 * Drawn with the home screen's own rows, so a folder inside a zip looks like a folder on the
 * phone: folders first, then files, by name. Back goes up a level and then leaves, and the
 * arrow in the toolbar does the same, as it does on the home screen.
 *
 * Part of the viewer rather than a screen of its own, because a zip arrives the way every
 * other file does, from Open with, the share sheet, Recents or a folder, and a list is what
 * opening one shows. A file tapped here opens in a viewer of its own, served by
 * [ArchiveProvider] straight out of the archive.
 */
internal class ArchiveBrowser(
    private val activity: AppCompatActivity,
    private val toolbar: MaterialToolbar,
    private val progress: LinearProgressIndicator,
    /** Whether Save a copy is reporting on [progress], which is then left to it. */
    private val saving: () -> Boolean,
    private val archive: Uri,
    private val archiveName: String,
    /** Where the reader was before a configuration change, or null for the top. */
    restoredFolder: String?,
    /** The code page the reader chose for the names before a configuration change, if they did. */
    restoredCodePage: String?,
    /**
     * Where the index is read and a password tried. Its own thread, ended with the screen;
     * tests run it inline.
     */
    private val loader: Executor = Executors.newSingleThreadExecutor(),
) {

    /** The folder on screen, "" for the top of the archive. Saved across a recreation. */
    var folder: String = restoredFolder.orEmpty()
        private set

    /**
     * The code page the reader chose for the names, as a key of [ZipNames.CHOICES], or null
     * for Gander's own guess. Saved across a recreation, and kept nowhere else: a zip opened
     * again starts from the guess.
     */
    var codePage: String? = restoredCodePage
        private set

    /** What Gander guessed for the names, which the menu offers as its automatic choice. */
    private var guessed: Charset? = null

    private var tree: ArchiveTree? = null
    private val adapter = RowAdapter()
    private val list = RecyclerView(activity)
    private val main = Handler(Looper.getMainLooper())

    /** Where each folder was scrolled to, so coming back up lands where the reader went in. */
    private val scrolled = HashMap<String, Int>()

    /** The dialog on screen, if one is, so it goes with the screen rather than leaking it. */
    private var dialog: AlertDialog? = null

    /**
     * Which try at a password is still wanted. A try runs off the main thread, and one the reader
     * has let go of since, by the box's Cancel or by going elsewhere in the list, is dropped when
     * it comes back rather than opening the file from under their no.
     */
    private var unlockWanted = 0

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = show(folder.substringBeforeLast('/', ""))
    }

    /** What reading the index came to: the archive's folders, or what to say instead. */
    private sealed interface Listing {
        class Ready(val tree: ArchiveTree, val codePage: Charset?) : Listing
        class Failed(val message: Int) : Listing
    }

    /** What trying a password on a file came to. */
    private enum class Unlock { OPENS, WRONG, FAILED }

    fun attach(container: FrameLayout) {
        // One column on a phone and two on a tablet, the same as the home screen, and for the
        // same reason; a message takes the whole width.
        val columns = activity.resources.getInteger(R.integer.home_list_columns)
        list.layoutManager = GridLayoutManager(activity, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (adapter.isFullSpan(position)) columns else 1
            }.apply {
                // Cached, as the home screen's are and for its reason: uncached, laying out a
                // folder on a tablet takes time in the square of its length, and a folder in
                // a zip can be a hundred thousand files long
                isSpanIndexCacheEnabled = true
                isSpanGroupIndexCacheEnabled = true
            }
        }
        list.adapter = adapter
        container.addView(
            list,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        activity.onBackPressedDispatcher.addCallback(activity, back)
        toolbar.setNavigationOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        toolbar.menu.findItem(R.id.action_name_encoding)?.setOnMenuItemClickListener {
            chooseCodePage()
            true
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                dialog?.dismiss()
                (loader as? ExecutorService)?.shutdown()
                // Not on a rotation or a change of theme, which brings the same list straight
                // back: only when the reader has left it
                if (activity.isFinishing) ArchivePasswords.forget(archive)
            }
        })
        load()
    }

    /**
     * Reads the index off the main thread. It is small and quick to read, but it comes from
     * another app's provider, which may be a slow card or a cloud account fetching the file
     * first, so the bar comes up if it is taking long enough to notice.
     */
    private fun load() {
        val quiet = announceAfterADelay()
        val chosen = ZipNames.CHOICES.firstOrNull { it.first == codePage }?.second
        loader.execute {
            val result = read(chosen)
            main.post {
                quiet()
                if (activity.isDestroyed) return@post
                when (result) {
                    is Listing.Ready -> {
                        tree = result.tree
                        if (chosen == null) guessed = result.codePage
                        // Offered only where the names needed a code page, since everywhere
                        // else it would change nothing: they said what they were
                        toolbar.menu.findItem(R.id.action_name_encoding)?.isVisible = result.codePage != null
                        show(folder.takeIf(result.tree::has).orEmpty())
                    }
                    is Listing.Failed -> {
                        // Nothing of an earlier reading is left to go back into: it was of a file
                        // that can no longer be read, in names that may read otherwise now
                        tree = null
                        folder = ""
                        back.isEnabled = false
                        toolbar.title = archiveName
                        adapter.submit(listOf(Row.Hint(activity.getString(result.message))))
                    }
                }
            }
        }
    }

    /**
     * The bar under the toolbar, if what is about to happen takes long enough to notice, and
     * what takes it down again once it has happened. The bar is Save a copy's first: while a
     * save is reporting on it, it is left alone, and it is only ever taken down by whoever put
     * it up, which is what kept a quick reread of the names from hiding a save partway through.
     */
    private fun announceAfterADelay(): () -> Unit {
        var shown = false
        // Indeterminate, set before it shows: the bar is the one a save reports on, and it is
        // left determinate at nought, which would draw as an empty track that never moves
        val announce = Runnable {
            if (saving()) return@Runnable
            progress.isIndeterminate = true
            progress.visibility = View.VISIBLE
            shown = true
        }
        main.postDelayed(announce, PROGRESS_DELAY_MS)
        return {
            main.removeCallbacks(announce)
            if (shown && !saving()) progress.visibility = View.GONE
        }
    }

    private fun read(chosen: Charset?): Listing = try {
        val afd = activity.contentResolver.openAssetFileDescriptor(archive, "r")
        val source = afd?.let(ZipSource::open)
        when {
            afd == null -> Listing.Failed(R.string.archive_unreadable)
            source == null -> {
                afd.close()
                Listing.Failed(R.string.archive_not_seekable)
            }
            else -> source.use {
                val index = ZipReader.index(it, codePage = chosen)
                Listing.Ready(ArchiveTree(index.entries), index.codePage)
            }
        }
    } catch (_: ZipReader.TooLarge) {
        Listing.Failed(R.string.archive_too_large)
    } catch (_: OutOfMemoryError) {
        // The count limit is generous, and a phone with a small heap can run out below it. This
        // is a background thread, where an error left to escape ends the app rather than the
        // list, and what failed was one large allocation that is garbage the moment it fails.
        Listing.Failed(R.string.archive_too_large)
    } catch (_: Exception) {
        // Damaged, not a zip at all, or no longer readable: a grant can lapse while the list
        // is on screen, and a change of theme reads the archive again
        Listing.Failed(R.string.archive_unreadable)
    }

    private fun show(to: String) {
        val tree = tree ?: return
        if (to != folder) unlockWanted++
        scrolled[folder] = (list.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
        folder = to
        back.isEnabled = to.isNotEmpty()
        toolbar.title = if (to.isEmpty()) archiveName else to.substringAfterLast('/')

        val (dirs, files) = tree.list(to)
        val rows = mutableListOf<Row>()
        dirs.forEach { name ->
            val path = if (to.isEmpty()) name else "$to/$name"
            rows += Row.Item(FOLDER_BADGE, DIR_COLOR, name, null, onClick = { show(path) })
        }
        files.forEach { rows += fileRow(it) }
        if (rows.isEmpty()) rows += Row.Hint(activity.getString(R.string.empty_folder))
        adapter.submit(rows)
        list.scrollToPosition(scrolled[to]?.coerceAtLeast(0) ?: 0)
    }

    private fun fileRow(entry: ArchiveEntry): Row.Item {
        val (badge, color) = badgeFor(entry.name, null)
        val details = listOfNotNull(
            Formatter.formatShortFileSize(activity, entry.size).takeIf { entry.size > 0 },
            DateUtils.getRelativeTimeSpanString(entry.modified).toString()
                .takeIf { entry.modified > 0 }
        )
        val subtitle = when {
            entry.encrypted -> (listOf(activity.getString(R.string.entry_locked)) + details).joinToString(" · ")
            !entry.readable -> activity.getString(R.string.entry_unsupported)
            else -> details.joinToString(" · ").ifEmpty { null }
        }
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        // A file under a password gets its picture once the password is known, and not before
        val previewable = entry.readable &&
            Thumbs.supportedInArchive(FileKind.detect(ext, null), ext, entry.location) &&
            (!entry.encrypted || ArchivePasswords.get(archive) != null)
        return Row.Item(
            badge, color, entry.name, subtitle,
            onClick = { open(entry) },
            thumbUri = if (previewable) ArchiveProvider.uriFor(activity, archive, entry) else null,
            thumbExt = ext,
        )
    }

    private fun open(entry: ArchiveEntry) {
        if (!entry.readable) {
            val why = if (entry.encrypted) R.string.entry_locked_unsupported else R.string.entry_unsupported_open
            Toast.makeText(activity, why, Toast.LENGTH_SHORT).show()
            return
        }
        if (!entry.encrypted) {
            view(entry)
            return
        }
        // The password that opened another file here nearly always opens this one
        val known = ArchivePasswords.get(archive)
        if (known == null) askForPassword(entry) else unlock(entry, known) { result ->
            when (result) {
                Unlock.OPENS -> view(entry)
                // Not an error: this one file has a password of its own
                Unlock.WRONG -> askForPassword(entry)
                Unlock.FAILED -> Unit
            }
        }
    }

    private fun view(entry: ArchiveEntry) {
        activity.startActivity(
            Intent()
                .setClassName(activity, ViewerActivity.INTERNAL_VIEWER)
                .setData(ArchiveProvider.uriFor(activity, archive, entry))
        )
    }

    /** Tries [password] on [entry] off the main thread, and says what came of it on it. */
    private fun unlock(entry: ArchiveEntry, password: String, then: (Unlock) -> Unit) {
        val ticket = ++unlockWanted
        val quiet = announceAfterADelay()
        loader.execute {
            val result = try {
                val afd = activity.contentResolver.openAssetFileDescriptor(archive, "r")
                val source = afd?.let(ZipSource::open)
                if (source == null) {
                    afd?.close()
                    Unlock.FAILED
                } else {
                    source.use { ZipReader.open(it, entry.location, password).close() }
                    Unlock.OPENS
                }
            } catch (_: ZipReader.WrongPassword) {
                Unlock.WRONG
            } catch (_: Exception) {
                Unlock.FAILED
            }
            main.post {
                quiet()
                if (activity.isDestroyed || ticket != unlockWanted) return@post
                if (result == Unlock.OPENS) ArchivePasswords.remember(archive, password)
                if (result == Unlock.FAILED) {
                    Toast.makeText(activity, R.string.archive_unreadable, Toast.LENGTH_SHORT).show()
                }
                then(result)
            }
        }
    }

    /**
     * Asks for the password [entry] is under, in the words the PDF viewer uses for the same
     * question. The box stays up while the password is tried, so a wrong one says so where it
     * was typed rather than closing and opening again.
     *
     * Not a password a keyboard or an autofill service should offer to keep, any more than a
     * PDF's: it opens one file, not an account. And never written anywhere, see ArchivePasswords.
     */
    private fun askForPassword(entry: ArchiveEntry) {
        val field = EditText(activity).apply {
            // Single line already, and masked. Setting isSingleLine as well, after this, puts
            // back the transformation that shows the text, and the password was on screen
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            hint = activity.getString(R.string.password)
            // A plain field comes out at 43dp, under the 48 Android asks of anything tapped
            minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            requestFocus()
        }
        val gutter = (24 * activity.resources.displayMetrics.density).toInt()
        val holder = FrameLayout(activity).apply {
            setPadding(gutter, gutter / 3, gutter, 0)
            addView(
                field,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        // Material keeps 80dp clear above and below a dialog, which with the keyboard up leaves a
        // phone of ordinary height no room for this one, and the field was squeezed to 43dp to
        // fit: under the 48 anything tapped needs. The same inset as its sides is room enough.
        val inset = activity.resources.getDimensionPixelSize(R.dimen.field_box_inset)
        val box = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.password_title)
            .setMessage(R.string.password_message)
            .setView(holder)
            .setBackgroundInsetTop(inset)
            .setBackgroundInsetBottom(inset)
            .setPositiveButton(R.string.password_open, null)
            // Said here and not only on dismissal: the dialog posts its dismissal, and then its
            // dismiss listener after that, behind an answer that may already be on its way
            .setNegativeButton(android.R.string.cancel) { _, _ -> unlockWanted++ }
            .create()
        box.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        // However else it goes, Back or a tap outside, a try still running is no longer wanted
        box.setOnDismissListener { unlockWanted++ }
        dialog = box
        box.show()

        val open = box.getButton(AlertDialog.BUTTON_POSITIVE)
        val submit = submit@{
            val password = field.text.toString()
            if (password.isEmpty() || !open.isEnabled) return@submit
            open.isEnabled = false
            unlock(entry, password) { result ->
                open.isEnabled = true
                when (result) {
                    Unlock.OPENS -> {
                        box.dismiss()
                        view(entry)
                        // The pictures of the other files under it can be drawn now
                        show(folder)
                    }
                    Unlock.WRONG -> {
                        field.text.clear()
                        field.error = activity.getString(R.string.password_wrong)
                    }
                    Unlock.FAILED -> box.dismiss()
                }
            }
        }
        open.setOnClickListener { submit() }
        field.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
    }

    /**
     * The code pages a reader can choose for the names, when Gander's guess is wrong: its
     * guess first, then every one there is. Choosing reads the index again, which is what a
     * zip of any size lists in, and goes back to the top, since the folder on screen was named
     * in the old reading and may have another name in the new one.
     */
    private fun chooseCodePage() {
        val choices = ZipNames.CHOICES
        val automatic = ZipNames.keyOf(guessed)?.let { LABELS[it] }
            ?.let { activity.getString(R.string.name_encoding_automatic_as, activity.getString(it)) }
            ?: activity.getString(R.string.name_encoding_automatic)
        val labels = (listOf(automatic) + choices.map { activity.getString(LABELS.getValue(it.first)) })
            .toTypedArray()
        val checked = choices.indexOfFirst { it.first == codePage } + 1
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.name_encoding)
            .setSingleChoiceItems(labels, checked) { box, which ->
                box.dismiss()
                val chosen = if (which == 0) null else choices[which - 1].first
                if (chosen == codePage) return@setSingleChoiceItems
                codePage = chosen
                folder = ""
                scrolled.clear()
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        /** The same wait the home screen gives a folder before saying anything about it. */
        const val PROGRESS_DELAY_MS = 150L

        /** What the menu calls each of [ZipNames.CHOICES]. */
        val LABELS = mapOf(
            "utf8" to R.string.encoding_utf8,
            "gbk" to R.string.encoding_gbk,
            "big5" to R.string.encoding_big5,
            "sjis" to R.string.encoding_sjis,
            "korean" to R.string.encoding_korean,
            "cp866" to R.string.encoding_cp866,
            "cp1251" to R.string.encoding_cp1251,
            "cp850" to R.string.encoding_cp850,
            "cp437" to R.string.encoding_cp437,
            "cp852" to R.string.encoding_cp852,
            "cp737" to R.string.encoding_cp737,
            "cp857" to R.string.encoding_cp857,
            "cp862" to R.string.encoding_cp862,
            "cp720" to R.string.encoding_cp720,
            "cp874" to R.string.encoding_cp874,
            "cp775" to R.string.encoding_cp775,
            "cp1258" to R.string.encoding_cp1258,
        )
    }
}
