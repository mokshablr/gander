package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently opened files, limited to those Gander can still open: a file picked, whose own read
 * grant was persisted, or a file in a folder the reader added, which the folder's grant keeps
 * open. Stored as JSON in SharedPreferences.
 *
 * A picked file's grant goes when its entry does, whether it is removed or pushed off the end,
 * since nothing else opens the file by it. A folder's file has no grant of its own to give back.
 */
object Recents {

    data class Entry(val uri: String, val name: String, val time: Long)

    private const val PREFS = "recents"
    private const val KEY = "items"
    private const val MAX = 25

    fun all(context: Context): List<Entry> {
        val granted = readGrants(context)
        return load(context).filter { it.uri in granted || folderOf(it.uri.toUri()) in granted }
    }

    /** Whether [uri] is a file in a folder the reader added, which may go in without a grant of its own. */
    fun inAddedFolder(context: Context, uri: Uri): Boolean =
        folderOf(uri)?.let { it in readGrants(context) } == true

    /**
     * The folder grant a file opened from an added folder is read under, as that grant's URI, or
     * null for a file picked on its own, whose URI names no folder.
     */
    fun folderOf(uri: Uri): String? = runCatching {
        if (!DocumentsContract.isTreeUri(uri)) return null
        DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri)).toString()
    }.getOrNull()

    /** Drops the entries of the folder [tree], which the reader has removed, so adding it again starts afresh. */
    fun forgetFolder(context: Context, tree: Uri) {
        val key = folderOf(tree) ?: return
        save(context, load(context).filter { folderOf(it.uri.toUri()) != key })
    }

    /** Each grant as Android spells it, and each folder's also as [folderOf] does, which the two are matched by. */
    private fun readGrants(context: Context): Set<String> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission }
        .flatMap { listOfNotNull(it.uri.toString(), folderOf(it.uri)) }
        .toSet()

    fun add(context: Context, uri: Uri, name: String) {
        val key = uri.toString()
        val items = load(context).filter { it.uri != key }.toMutableList()
        items.add(0, Entry(key, name, System.currentTimeMillis()))
        save(context, items.take(MAX))
        items.drop(MAX).forEach { release(context, it.uri) }
    }

    fun remove(context: Context, uri: String) {
        save(context, load(context).filter { it.uri != uri })
        release(context, uri)
    }

    /**
     * Gives back the grant on [uri], write as well as read: a build that could rename a picked
     * file took both, and giving back only the read half left the other behind, where the list,
     * which shows read grants only, could never reach it again. Android throws for a grant that
     * is already gone, and that is the same outcome. A folder's file is left alone: the grant it
     * is read under is the folder's, which goes only when the folder does.
     */
    private fun release(context: Context, uri: String) {
        if (folderOf(uri.toUri()) != null) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun load(context: Context): List<Entry> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]")!!
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("uri"), o.getString("name"), o.getLong("time"))
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject()
                    .put("uri", e.uri)
                    .put("name", e.name)
                    .put("time", e.time)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, arr.toString()) }
    }
}
