package com.arjun.gander

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/**
 * A stand-in for a granted folder.
 *
 * The home screen reads folders through DocumentsContract, which means a
 * provider on the other side. This is the smallest one that answers the two
 * queries MainActivity makes, the children of a folder and the display name
 * of a tree, and what the viewer asks of a file opened from one: its name,
 * its bytes, and where it sits in the folder that was added.
 */
internal class FakeDocumentsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "test.documents"

        /** Under [authority], which can be the phone's own storage's, where Android's trash reaches. */
        fun install(authority: String = AUTHORITY): FakeDocumentsProvider =
            Robolectric.buildContentProvider(FakeDocumentsProvider::class.java)
                .create(authority)
                .get()

        fun treeUri(docId: String = "root", authority: String = AUTHORITY): Uri =
            DocumentsContract.buildTreeDocumentUri(authority, docId)
    }

    /** Folder document id to the entries inside it. */
    private val folders = mutableMapOf<String, MutableList<ChildDoc>>()
    private val labels = mutableMapOf<String, String>()
    private val contents = mutableMapOf<String, ByteArray>()

    /** Entries taken away, which a query for no longer finds. */
    private val gone = mutableSetOf<String>()

    /** Takes [docId] out of its folder, as Android's trash does to a photo it moves. */
    fun remove(docId: String) = apply {
        folders.values.forEach { children -> children.removeAll { it.docId == docId } }
        contents.remove(docId)
        gone += docId
    }

    fun folder(docId: String, label: String, vararg children: ChildDoc) = apply {
        folders[docId] = children.toMutableList()
        labels[docId] = label
    }

    /** What reading the entry [docId] gives. */
    fun contents(docId: String, bytes: ByteArray) = apply { contents[docId] = bytes }

    private fun entry(docId: String): ChildDoc? = folders.values.flatten().firstOrNull { it.docId == docId }

    private fun parentOf(docId: String): String? =
        folders.entries.firstOrNull { (_, children) -> children.any { it.docId == docId } }?.key

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

        // A children query names the parent in the last path segment; a single
        // document query names the document itself.
        val isChildren = uri.pathSegments.contains("children")
        val docId = DocumentsContract.getDocumentId(uri)
        // As Android's storage answers for a file that has gone. Asked through a folder it was
        // given, it can no longer tell the file was in it, and throws, as FileSystemProvider's
        // isChildDocument does.
        if (!isChildren && docId in gone) {
            if (DocumentsContract.isTreeUri(uri)) throw IllegalArgumentException("Failed to determine if $docId is a child")
            return null
        }

        val rows = if (isChildren) {
            folders[docId].orEmpty()
        } else {
            listOf(labels[docId]?.let { ChildDoc(docId, it, MIME_DIR, 0, 0) } ?: entry(docId)
                ?: ChildDoc(docId, docId, MIME_DIR, 0, 0))
        }

        return MatrixCursor(columns).apply {
            rows.forEach { child ->
                addRow(
                    columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> child.docId
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> child.name
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> child.mime
                            DocumentsContract.Document.COLUMN_SIZE -> child.size
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> child.modified
                            else -> null
                        }
                    }.toTypedArray<Any?>()
                )
            }
        }
    }

    override fun getType(uri: Uri): String =
        runCatching { entry(DocumentsContract.getDocumentId(uri))?.mime }.getOrNull() ?: MIME_DIR

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val bytes = contents[DocumentsContract.getDocumentId(uri)] ?: throw FileNotFoundException(uri.toString())
        val file = File.createTempFile("fake-document", null).apply { deleteOnExit(); writeBytes(bytes) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * DocumentsContract.findDocumentPath, which asks under these hidden names: the folders from
     * the tree's top down to the document.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "android:findDocumentPath") return null
        @Suppress("DEPRECATION")
        val uri = extras?.getParcelable<Uri>("uri") ?: return null
        val top = DocumentsContract.getTreeDocumentId(uri)
        val path = generateSequence(DocumentsContract.getDocumentId(uri)) { if (it == top) null else parentOf(it) }
            .toList().asReversed()
        if (path.first() != top) throw FileNotFoundException("$uri is not inside $top")
        return Bundle().apply { putParcelable("result", DocumentsContract.Path(null, path)) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?
    ) = 0
}
