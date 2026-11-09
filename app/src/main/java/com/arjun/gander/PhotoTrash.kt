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
 * Deleting photos in a folder added in Gander, issue #75. Gander keeps read access to a folder
 * and no more, so Android does the deleting: it shows its own prompt, and on a yes moves the
 * photos to its trash, which keeps them for 30 days. One prompt covers as many photos as it is
 * asked about, which is what makes picking many of them worth it.
 */

/**
 * Whether Android can be asked to move [photo] to its trash: from Android 11, which brought the
 * trash, and for a file on the phone's own storage or a card, the only files its media store lists.
 */
internal fun mayTrash(photo: Uri, sdk: Int = Build.VERSION.SDK_INT): Boolean =
    sdk >= Build.VERSION_CODES.R && photo.authority == EXTERNAL_STORAGE_AUTHORITY

/**
 * The request for Android's one prompt to move [photos] to its trash, or null where it can be
 * made for none of them.
 *
 * The prompt takes a photo by its entry among the media store's pictures, while getMediaUri
 * answers with its entry among all files, which the prompt refuses, so the same number is put
 * under the pictures. From Android 12 Gander may read that entry, and a file the store doesn't
 * count as a picture, such as one in a folder holding a .nomedia file, is left out here, since
 * the prompt would take it and then move nothing. So is a file the store doesn't list. What was
 * left out is found afterwards still in its folder, as a photo the prompt failed to move is.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal fun trashRequest(context: Context, photos: List<Uri>, sdk: Int = Build.VERSION.SDK_INT): PendingIntent? {
    val pictures = photos.mapNotNull { photo ->
        runCatching {
            val file = MediaStore.getMediaUri(context, photo) ?: return@runCatching null
            if (sdk >= Build.VERSION_CODES.S && !isPicture(context.contentResolver, file)) return@runCatching null
            ContentUris.withAppendedId(
                MediaStore.Images.Media.getContentUri(MediaStore.getVolumeName(file)),
                ContentUris.parseId(file),
            )
        }.getOrNull()
    }
    if (pictures.isEmpty()) return null
    return runCatching { MediaStore.createTrashRequest(context.contentResolver, pictures, true) }.getOrNull()
}

private fun isPicture(resolver: ContentResolver, file: Uri): Boolean =
    resolver.query(file, arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE), null, null, null)?.use {
        it.moveToFirst() && it.getInt(0) == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
    } == true

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
 * [done] is told which photos left their folder and which are still there, after a yes in the
 * prompt, or when the prompt couldn't be opened at all. A no moves nothing and needs no word.
 */
internal class TrashPrompt(
    private val activity: ComponentActivity,
    private val worker: Worker,
    private val done: (gone: List<Uri>, stayed: List<Uri>) -> Unit,
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
            asked = emptyList()
            if (photos.isEmpty() || result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            // Android answers yes after a move that failed as well, which only looking catches
            working(true)
            worker.offMain({ stayed: List<Uri> ->
                working(false)
                done(photos - stayed.toSet(), stayed)
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
        preparing = true
        working(true)
        worker.offMain({ request: PendingIntent? ->
            preparing = false
            working(false)
            if (!stillWanted()) return@offMain
            if (request == null) return@offMain done(emptyList(), photos)
            asked = photos
            runCatching { prompt.launch(IntentSenderRequest.Builder(request).build()) }.onFailure {
                asked = emptyList()
                done(emptyList(), photos)
            }
        }) { app -> trashRequest(app, photos) }
    }

    fun save(state: Bundle) = state.putStringArrayList(STATE_ASKED, ArrayList(asked.map { it.toString() }))

    fun restore(state: Bundle?) {
        asked = state?.getStringArrayList(STATE_ASKED).orEmpty().map { it.toUri() }
    }

    private companion object {
        const val STATE_ASKED = "trash.asked"

        /** How long the work may take before the screen says anything about it, as a folder's reading may. */
        const val BUSY_AFTER_MS = 150L
    }
}

/**
 * What to say once Android's prompt has been answered with a yes: how many of the photos went to
 * the bin, and how many are still in their folder.
 */
internal fun trashWord(context: Context, gone: Int, stayed: Int): String {
    val went = context.resources.getQuantityString(R.plurals.photos_trashed, gone, gone)
    val left = context.resources.getQuantityString(R.plurals.photos_not_trashed, stayed, stayed)
    return when {
        gone + stayed == 1 -> context.getString(if (gone == 1) R.string.photo_trashed else R.string.photo_not_trashed)
        stayed == 0 -> went
        gone == 0 -> left
        else -> context.getString(R.string.photos_trashed_some, went, left)
    }
}
