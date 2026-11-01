package com.arjun.gander

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi

/*
 * Deleting a photo in a folder added in Gander, issue #75. Gander keeps read access to a folder
 * and no more, so Android does the deleting: it shows its own prompt, and on a yes moves the photo
 * to its trash, which keeps it for 30 days.
 */

/**
 * Whether Android can be asked to move [photo] to its trash: from Android 11, which brought the
 * trash, and for a file on the phone's own storage or a card, the only files its media store lists.
 */
internal fun mayTrash(photo: Uri, sdk: Int = Build.VERSION.SDK_INT): Boolean =
    sdk >= Build.VERSION_CODES.R && photo.authority == EXTERNAL_STORAGE_AUTHORITY

/**
 * The request for Android's prompt to move [photo] to its trash, or null where it can't be made.
 *
 * The prompt takes a photo by its entry among the media store's pictures, while getMediaUri
 * answers with its entry among all files, which the prompt refuses, so the same number is put
 * under the pictures. From Android 12 Gander may read that entry, and a file the store doesn't
 * count as a picture, such as one in a folder holding a .nomedia file, is turned away here, since
 * the prompt would take it and then move nothing.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal fun trashRequest(context: Context, photo: Uri, sdk: Int = Build.VERSION.SDK_INT): PendingIntent? =
    runCatching {
        val file = MediaStore.getMediaUri(context, photo) ?: return null
        if (sdk >= Build.VERSION_CODES.S && !isPicture(context.contentResolver, file)) return null
        val picture = ContentUris.withAppendedId(
            MediaStore.Images.Media.getContentUri(MediaStore.getVolumeName(file)),
            ContentUris.parseId(file),
        )
        MediaStore.createTrashRequest(context.contentResolver, listOf(picture), true)
    }.getOrNull()

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
