package com.arjun.gander

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import org.robolectric.Robolectric

/**
 * A stand-in for Android's media store, for what deleting a photo asks of it, none of which
 * Robolectric answers: getMediaUri, which turns a document into the store's entry among all files,
 * that entry's media type, and createTrashRequest, which hands back the prompt to open.
 */
internal class FakeMediaStore : ContentProvider() {

    companion object {
        /** The phone's own storage, as the store names it. */
        const val VOLUME = "external_primary"

        fun install(): FakeMediaStore =
            Robolectric.buildContentProvider(FakeMediaStore::class.java).create(MediaStore.AUTHORITY).get()
    }

    /** A document to its number in the store, and that entry's media type. */
    private val entries = mutableMapOf<Uri, Pair<Long, Int>>()

    /** Each trash request: the entries it was for, and what it would set on them. */
    val trashRequests = mutableListOf<Pair<List<Uri>, ContentValues?>>()

    fun entry(document: Uri, id: Long, mediaType: Int = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) =
        apply { entries[document] = id to mediaType }

    /** Under the names MediaStore calls the store by. */
    @Suppress("DEPRECATION")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        "get_media_uri" -> {
            val document = extras?.getParcelable<Uri>("uri")
            // As the store does for a file it doesn't list
            val (id, _) = entries[document] ?: throw IllegalArgumentException("Not listed: $document")
            Bundle().apply { putParcelable("uri", MediaStore.Files.getContentUri(VOLUME, id)) }
        }
        "create_trash_request" -> {
            val clip = extras!!.getParcelable<ClipData>("clip_data")!!
            trashRequests += (0 until clip.itemCount).map { clip.getItemAt(it).uri } to
                extras.getParcelable<ContentValues>("content_values")
            val prompt = PendingIntent.getActivity(
                context!!, 0, Intent("test.TRASH_PROMPT"), PendingIntent.FLAG_IMMUTABLE
            )
            Bundle().apply { putParcelable("result", prompt) }
        }
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val type = entries.values.firstOrNull { it.first == ContentUris.parseId(uri) }?.second ?: return null
        return MatrixCursor(arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE)).apply { addRow(arrayOf<Any>(type)) }
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ) = 0
}
