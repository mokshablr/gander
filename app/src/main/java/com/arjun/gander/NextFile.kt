package com.arjun.gander

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The files either side of the one open, in its folder, for the viewer's Next file and Previous
 * file. Issue #87, after SumatraPDF's.
 *
 * Only for a file opened from a folder added in Gander, which holds a grant on the whole folder.
 * A file another app opens in Gander comes alone, since Android shares only that one file.
 */
internal data class Neighbours(val previous: ChildDoc?, val next: ChildDoc?) {
    /** Whether there is anywhere to go, which decides whether the viewer offers either. */
    val any: Boolean get() = previous != null || next != null
}

/**
 * Whether the viewer steps to [this] on the way through a folder: a file Gander has a viewer for.
 * Not a photo, since a folder's photos are swiped through on their own and the photo viewer has
 * no Next file to step on from, nor a .zip, whose list of what is inside has its own way back.
 * Nor a file of no format Gander knows, which may or may not read as text.
 */
internal fun ChildDoc.isSteppedTo(): Boolean {
    if (mime == MIME_DIR) return false
    val kind = FileKind.detect(name.substringAfterLast('.', "").lowercase(), mime.ifEmpty { null })
    return kind != FileKind.UNSUPPORTED && kind != FileKind.IMAGE && kind != FileKind.IMAGE_WEB &&
        kind != FileKind.ARCHIVE
}

/**
 * The nearest file stepped to before and after the one whose document id is [docId], in the order
 * the home screen lists [children] in. The file itself need not be one stepped to, so a file in
 * a format Gander doesn't know can still be stepped on from. Null when it isn't among them.
 */
internal fun neighboursIn(children: List<ChildDoc>, docId: String, sortOrder: Settings.SortOrder): Neighbours? {
    val files = orderChildren(children, sortOrder).second
    val at = files.indexOfFirst { it.docId == docId }
    if (at < 0) return null
    return Neighbours(
        previous = files.subList(0, at).lastOrNull { it.isSteppedTo() },
        next = files.subList(at + 1, files.size).firstOrNull { it.isSteppedTo() },
    )
}

/**
 * [neighboursIn] for the document at [file], read from its folder through the grant on the tree
 * it belongs to. Null when it wasn't opened from a folder added in Gander, or the folder can't be
 * read. Asked off the main thread, since a folder is listed by another app's provider.
 */
internal fun neighboursOf(resolver: ContentResolver, file: Uri, sortOrder: Settings.SortOrder): Neighbours? {
    if (!DocumentsContract.isTreeUri(file)) return null
    val folder = runCatching { DocumentsContract.findDocumentPath(resolver, file)?.path }
        .getOrNull()?.dropLast(1)?.lastOrNull() ?: return null
    val children = childrenOf(resolver, file, folder) ?: return null
    return neighboursIn(children, DocumentsContract.getDocumentId(file), sortOrder)
}
