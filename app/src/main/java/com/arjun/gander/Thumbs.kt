package com.arjun.gander

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Small preview thumbnails for images, videos and PDFs.
 * Memory LRU + PNG disk cache under cacheDir/thumbs, a folder's files' in a subfolder of
 * their own, generated off the main thread. Kinds without a cheap visual preview simply keep their badge.
 * A PDF's is drawn in an isolated process: see PdfThumbs.
 *
 * A file inside a zip gets the memory cache and never the disk. Nothing in a zip is written
 * to the phone, which is what Gander says about opening one, and a thumbnail on disk is a
 * copy of a photo, only smaller.
 */
object Thumbs {

    private const val SIZE = 192

    private val mem = LruCache<String, Bitmap>(48)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    // Made by the first PDF to need a picture, on the main thread. The context it keeps is the
    // application's, which lives as long as the process, so holding it leaks nothing.
    @SuppressLint("StaticFieldLeak")
    private var pdfs: PdfThumbs? = null

    /** Whether a file of [kind] gets a thumbnail on Android [sdk]. */
    fun supported(kind: FileKind, ext: String, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        when (kind) {
            FileKind.IMAGE, FileKind.IMAGE_WEB -> true
            // From the isolated renderer, which on Android 8 could reach Gander at every launch
            // while the file is in view
            FileKind.PDF -> PdfRenderClient.containedOn(sdk)
            FileKind.PLAYER -> !FileKind.isAudioExt(ext)
            else -> false
        }

    /**
     * Which files inside a zip get one. A photo, however it is packed, since decoding one from
     * the start is what decoding a photo is. A video only when it is stored as it is and not
     * encrypted, which makes it a window onto the archive the retriever can seek in; through
     * the pipe, finding one frame would mean inflating the video up to it. Never a PDF, which
     * PdfRenderer will only take as a whole file, and a window onto a zip is not one.
     */
    internal fun supportedInArchive(kind: FileKind, ext: String, at: EntryLocation): Boolean = when (kind) {
        FileKind.IMAGE, FileKind.IMAGE_WEB -> true
        FileKind.PLAYER -> !FileKind.isAudioExt(ext) && at.method == ZipReader.METHOD_STORED && at.lock == Lock.NONE
        else -> false
    }

    /** Shows the thumbnail in [into] and hides [badge] once ready. */
    fun load(context: Context, uri: Uri, ext: String, into: ImageView, badge: View) {
        val key = md5(uri.toString())
        into.tag = key
        mem.get(key)?.let {
            into.setImageBitmap(it)
            into.visibility = View.VISIBLE
            badge.visibility = View.GONE
            return
        }
        val appCtx = context.applicationContext
        val kind = FileKind.detect(ext, null)
        val onDisk = !ArchiveProvider.isEntry(appCtx, uri)
        val pdf = if (kind == FileKind.PDF) pdfs ?: PdfThumbs(appCtx, SIZE).also { pdfs = it } else null
        executor.execute {
            val file = diskFile(appCtx, uri, key)
            val cached = if (onDisk) fromDisk(file) else null
            if (cached != null) return@execute show(key, cached, into, badge)
            val make = Runnable {
                val bmp = generate(appCtx, uri, kind, key, pdf) ?: return@Runnable
                if (onDisk) toDisk(file, bmp)
                show(key, bmp, into, badge)
            }
            // A PDF takes its turn for the renderer, which holds one document at a time. One
            // already on disk didn't need it, so it never waits behind the others.
            if (pdf != null) pdf.queue(make) else make.run()
        }
    }

    private fun show(key: String, bmp: Bitmap, into: ImageView, badge: View) {
        mem.put(key, bmp)
        main.post {
            if (into.tag == key) {
                into.setImageBitmap(bmp)
                into.visibility = View.VISIBLE
                badge.visibility = View.GONE
            }
        }
    }

    /**
     * Drops the in-memory cache and the PDF renderer.
     *
     * This object is process-wide and its LruCache outlives any one screen,
     * which is what makes it useful and also what makes it leak between unit
     * tests. Nothing in the app calls this.
     */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTests() {
        mem.evictAll()
        // Bound with the Application of an earlier test
        pdfs?.shutdown()
        pdfs = null
    }

    fun evict(context: Context, uriString: String) {
        val key = md5(uriString)
        mem.remove(key)
        diskFile(context, uriString.toUri(), key).delete()
    }

    /**
     * Drops the thumbnails of every file reached through the folder [tree], which the reader has
     * removed: each is a copy of a picture in it, only smaller.
     */
    fun forgetFolder(context: Context, tree: Uri) {
        folderDir(context, tree)?.deleteRecursively()
    }

    fun exifRotation(resolver: ContentResolver, uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180,
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    private fun generate(ctx: Context, uri: Uri, kind: FileKind, key: String, pdf: PdfThumbs?): Bitmap? =
        runCatching {
            when {
                pdf != null -> pdf.draw(uri, key)
                kind == FileKind.PLAYER -> videoThumb(ctx, uri)
                else -> imageThumb(ctx, uri)
            }
        }.getOrNull()

    private fun imageThumb(ctx: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val rotation = exifRotation(ctx.contentResolver, uri)
        if (rotation == 0) return bmp
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private fun videoThumb(ctx: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, uri)
            val frame = retriever.frameAtTime ?: return null
            val scale = SIZE.toFloat() / maxOf(frame.width, frame.height)
            if (scale >= 1f) frame
            else frame.scale((frame.width * scale).toInt(), (frame.height * scale).toInt())
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun diskDir(ctx: Context): File =
        File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    /** Where [uri]'s thumbnail is kept: with its folder's, for a file reached through one, so they go together. */
    private fun diskFile(ctx: Context, uri: Uri, key: String): File =
        File(folderDir(ctx, uri) ?: diskDir(ctx), "$key.png")

    /** Where the thumbnails of the folder [uri] was reached through are kept, or null for a file opened on its own. */
    internal fun folderDir(ctx: Context, uri: Uri): File? = folderKey(uri)?.let { File(diskDir(ctx), it) }

    /** The folder grant [uri] was reached through, as a key, or null for a file opened on its own. */
    private fun folderKey(uri: Uri): String? = Recents.folderOf(uri)?.let(::md5)

    private fun fromDisk(file: File): Bitmap? {
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun toDisk(file: File, bmp: Bitmap) {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
