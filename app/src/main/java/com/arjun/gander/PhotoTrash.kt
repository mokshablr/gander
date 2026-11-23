package com.arjun.gander

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.net.toUri

/*
 * Deleting photos, videos and audio in a folder added in Gander, issue #75. Gander keeps read
 * access to a folder and no more, so Android does the deleting: it shows its own prompt, and on a
 * yes moves the files to its trash, which keeps them for 30 days. One prompt covers as many files
 * as it is asked about, which is what makes picking many of them worth it. Android's trash takes
 * media only, so other files can't be picked. With Delete for good on, Android is asked instead to
 * delete them outright, in a prompt of its own, since its bin keeps them on the phone for 30 days
 * and only some gallery apps can empty it.
 */

/** The media store's collections Android's trash takes a file from, by the store's media type. */
internal enum class Binned(private val mediaType: Int) {
    PICTURE(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
    VIDEO(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
    AUDIO(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO);

    fun entry(volume: String, id: Long): Uri = ContentUris.withAppendedId(
        when (this) {
            PICTURE -> MediaStore.Images.Media.getContentUri(volume)
            VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
        },
        id,
    )

    companion object {
        fun ofMediaType(type: Int): Binned? = entries.firstOrNull { it.mediaType == type }
    }
}

/**
 * Which collection a file named [name] would be in, going by its name and the type its folder
 * gave, or null for a file Android's trash doesn't take. It is what the folder's list knows, and
 * all Android 11 lets Gander know, since it may not read the store's entry there.
 */
internal fun binnedAs(name: String, mime: String?): Binned? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (FileKind.detect(ext, mime)) {
        FileKind.IMAGE, FileKind.IMAGE_WEB -> Binned.PICTURE
        FileKind.PLAYER ->
            if (FileKind.isAudioExt(ext) || mime?.startsWith("audio/") == true) Binned.AUDIO else Binned.VIDEO
        else -> null
    }
}

/** Whether every one of [files] is a picture, going by its name, for what is said about them afterwards. */
internal fun allPictures(files: List<Uri>): Boolean = files.all {
    binnedAs(runCatching { DocumentsContract.getDocumentId(it) }.getOrDefault("").substringAfterLast('/'), null) ==
        Binned.PICTURE
}

/**
 * Whether Android can be asked to move [photo] to its trash: from Android 11, which brought the
 * trash, and for a file on the phone's own storage or a card, the only files its media store lists.
 */
internal fun mayTrash(photo: Uri, sdk: Int = Build.VERSION.SDK_INT): Boolean =
    sdk >= Build.VERSION_CODES.R && photo.authority == EXTERNAL_STORAGE_AUTHORITY

/**
 * The request for Android's one prompt to move [files] to its trash, or to delete them outright
 * where [forGood], or null where it can be made for none of them.
 *
 * The prompt takes a file by its entry among the store's pictures, videos or audio, while
 * getMediaUri answers with its entry among all files, which the prompt refuses, so the same
 * number is put under the collection the file belongs to. From Android 12 Gander may read that
 * entry, and the store's own media type says which. A file the store counts as none of them, such
 * as one in a folder holding a .nomedia file, is left out, since the prompt would take it and
 * then move nothing. So is a file the store doesn't list. Android 11 lets Gander read nothing,
 * so there the file's name and type decide. What was left out is found afterwards still in its
 * folder, as a file the prompt failed to move is.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal fun trashRequest(
    context: Context,
    files: List<Uri>,
    sdk: Int = Build.VERSION.SDK_INT,
    forGood: Boolean = false,
): PendingIntent? {
    val resolver = context.contentResolver
    val entries = files.mapNotNull { document ->
        runCatching {
            val file = MediaStore.getMediaUri(context, document) ?: return@runCatching null
            val binned = if (sdk >= Build.VERSION_CODES.S) {
                mediaType(resolver, file)?.let(Binned::ofMediaType)
            } else {
                binnedAs(DocumentsContract.getDocumentId(document).substringAfterLast('/'), resolver.getType(document))
            }
            binned?.entry(MediaStore.getVolumeName(file), ContentUris.parseId(file))
        }.getOrNull()
    }
    if (entries.isEmpty()) return null
    return runCatching {
        if (forGood) MediaStore.createDeleteRequest(resolver, entries) else MediaStore.createTrashRequest(resolver, entries, true)
    }.getOrNull()
}

private fun mediaType(resolver: ContentResolver, file: Uri): Int? =
    resolver.query(file, arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE), null, null, null)?.use {
        if (it.moveToFirst()) it.getInt(0) else null
    }

/**
 * Whether [photo] can still be found in its folder, asked after a yes in Android's prompt, which
 * answers yes after a move that failed as well. Android's storage throws when asked for a file
 * that has gone from a folder, since it can no longer tell the file was in it, so a question that
 * fails counts as the photo having gone.
 */
internal fun stillThere(resolver: ContentResolver, photo: Uri): Boolean = runCatching {
    resolver.query(photo, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
        ?.use { it.moveToFirst() } == true
}.getOrDefault(false)

/**
 * Android's prompt to move photos to its trash, for one screen: the viewer's, or the home
 * screen's list of a folder. Made with the activity, since an answer can only be registered for
 * before the activity starts.
 *
 * [done] is told which photos left their folder and which are still there, and whether they were
 * to be deleted for good, after a yes in the prompt, or when the prompt couldn't be opened at all.
 * A no moves nothing and needs no word.
 */
internal class TrashPrompt(
    private val activity: ComponentActivity,
    private val worker: Worker,
    private val done: (gone: List<Uri>, stayed: List<Uri>, forGood: Boolean) -> Unit,
    /**
     * Whether the screen should show it is at work: while the prompt is got ready and while its
     * answer is looked into, each a question to Android for every photo, which hundreds of
     * photos take seconds over. Not said for work that is over before anyone could see it.
     */
    private val busy: (Boolean) -> Unit,
) {
    /** How the screen asks a provider something off the main thread, and hears the answer on it. */
    interface Worker {
        fun <T> offMain(then: (T) -> Unit, ask: (Context) -> T)
    }

    /**
     * The photos the prompt was opened for, kept across the activity being made again while the
     * prompt is up, since the answer comes back without them.
     */
    private var asked = emptyList<Uri>()

    /** Whether the prompt up asks to delete for good, kept with [asked] for the same reason. */
    private var askedForGood = false

    /** While the prompt is being got ready, when a second tap on Delete does nothing. */
    private var preparing = false

    private val main = Handler(Looper.getMainLooper())
    private val sayBusy = Runnable { busy(true) }

    private fun working(now: Boolean) {
        main.removeCallbacks(sayBusy)
        if (now) main.postDelayed(sayBusy, BUSY_AFTER_MS) else busy(false)
    }

    private val prompt =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val photos = asked
            // Read now, since another Delete may ask again before the looking below is done
            val forGood = askedForGood
            asked = emptyList()
            if (photos.isEmpty() || result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            // Android answers yes after a move that failed as well, which only looking catches
            working(true)
            worker.offMain({ stayed: List<Uri> ->
                working(false)
                val gone = photos - stayed.toSet()
                // A file opened from its folder is in Recents, where it would stay as a dead row
                gone.forEach { Recents.remove(activity, it.toString()) }
                done(gone, stayed, forGood)
            }) { app ->
                val stayed = photos.filter { stillThere(app.contentResolver, it) }
                // A photo that has gone leaves no small copy of itself behind in Gander
                (photos - stayed.toSet()).forEach { Thumbs.evict(app, it.toString()) }
                stayed
            }
        }

    /**
     * Asks Android to move [photos] to its trash, which it does once the person says yes in its
     * own prompt. [stillWanted] is asked once the prompt is ready, since the screen can have
     * moved on by then, and a prompt would ask about photos it no longer shows.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun ask(photos: List<Uri>, stillWanted: () -> Boolean) {
        if (photos.isEmpty() || preparing || asked.isNotEmpty()) return
        val forGood = Settings.deleteForGood(activity)
        preparing = true
        working(true)
        worker.offMain({ request: PendingIntent? ->
            preparing = false
            working(false)
            if (!stillWanted()) return@offMain
            if (request == null) return@offMain done(emptyList(), photos, forGood)
            asked = photos
            askedForGood = forGood
            runCatching { prompt.launch(IntentSenderRequest.Builder(request).build()) }.onFailure {
                asked = emptyList()
                done(emptyList(), photos, forGood)
            }
        }) { app -> trashRequest(app, photos, forGood = forGood) }
    }

    fun save(state: Bundle) {
        state.putStringArrayList(STATE_ASKED, ArrayList(asked.map { it.toString() }))
        state.putBoolean(STATE_FOR_GOOD, askedForGood)
    }

    fun restore(state: Bundle?) {
        asked = state?.getStringArrayList(STATE_ASKED).orEmpty().map { it.toUri() }
        askedForGood = state?.getBoolean(STATE_FOR_GOOD) == true
    }

    private companion object {
        const val STATE_ASKED = "trash.asked"
        const val STATE_FOR_GOOD = "trash.forGood"

        /** How long the work may take before the screen says anything about it, as a folder's reading may. */
        const val BUSY_AFTER_MS = 150L
    }
}

/**
 * What to say once Android's prompt has been answered with a yes: how many of the files went to
 * the bin, or were deleted where [forGood], and how many are still in their folder. They are
 * called photos where all of them are.
 */
internal fun trashWord(context: Context, gone: Int, stayed: Int, photos: Boolean = true, forGood: Boolean = false): String {
    val res = context.resources
    val wentRes = when {
        forGood -> if (photos) R.plurals.photos_deleted else R.plurals.files_deleted
        else -> if (photos) R.plurals.photos_trashed else R.plurals.files_trashed
    }
    val leftRes = when {
        forGood -> if (photos) R.plurals.photos_not_deleted else R.plurals.files_not_deleted
        else -> if (photos) R.plurals.photos_not_trashed else R.plurals.files_not_trashed
    }
    val went = res.getQuantityString(wentRes, gone, gone)
    val left = res.getQuantityString(leftRes, stayed, stayed)
    val one = if (forGood) R.string.photo_deleted else R.string.photo_trashed
    val notOne = when {
        forGood -> if (photos) R.string.photo_not_deleted else R.string.file_not_deleted
        else -> if (photos) R.string.photo_not_trashed else R.string.file_not_trashed
    }
    return when {
        gone + stayed == 1 -> context.getString(if (gone == 1) one else notOne)
        stayed == 0 -> went
        gone == 0 -> left
        else -> context.getString(R.string.photos_trashed_some, went, left)
    }
}
