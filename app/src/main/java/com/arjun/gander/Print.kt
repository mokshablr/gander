package com.arjun.gander

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * How a file of this kind prints, or null if it doesn't. Issue #45.
 *
 * A PDF, in either view, goes to the printer as the file itself. The paper documents print as
 * the WebView draws them, with the print rules in their pages, and so do slides, a slide to a
 * sheet, and Markdown and text files, black on white whatever the phone's theme.
 */
internal enum class PrintRoute { FILE, PAGE }

internal fun printRoute(kind: FileKind): PrintRoute? = when (kind) {
    FileKind.PDF, FileKind.PDF_SIMPLE -> PrintRoute.FILE
    FileKind.DOCX, FileKind.PROSE, FileKind.PPTX, FileKind.MD, FileKind.TEXT -> PrintRoute.PAGE
    // A book has one chapter on its page at a time, so printing the page would print that
    // chapter and call it the book. A sheet on screen has no pages: what fits on paper is set
    // by print areas, page breaks and scaling the sheet keeps and the screen does not show.
    FileKind.IMAGE, FileKind.IMAGE_WEB, FileKind.PLAYER, FileKind.XLSX,
    FileKind.MODEL, FileKind.EPUB, FileKind.ARCHIVE, FileKind.UNSUPPORTED -> null
}

/**
 * The name Android's print screen gives the job, and "Save as PDF" the file. Without the
 * extension, or a Word file would be saved as "letter.docx.pdf".
 */
internal fun printJobName(fileName: String): String =
    fileName.substringBeforeLast('.').ifBlank { fileName.ifBlank { "Document" } }

/**
 * Copies [from] into [to] until it ends or [cancelled] says to stop, and says whether it
 * got to the end.
 */
internal fun copyForPrint(from: InputStream, to: OutputStream, cancelled: () -> Boolean): Boolean {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        if (cancelled()) return false
        val read = from.read(buffer)
        if (read < 0) return true
        to.write(buffer, 0, read)
    }
}

/**
 * Hands Android's printing the PDF file itself, so it prints exactly as it is. The page
 * can't be printed instead: pdf.js draws only the pages near the screen.
 */
internal class PdfPrintAdapter(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val jobName: String,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        val cancelled = { cancellationSignal?.isCanceled == true }
        val main = Handler(Looper.getMainLooper())
        // Off the main thread, since a scan can run to hundreds of megabytes
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val copied = runCatching {
                resolver.openInputStream(uri).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        copyForPrint(checkNotNull(input), output, cancelled)
                    }
                }
            }.getOrDefault(false)
            main.post {
                when {
                    cancelled() -> callback.onWriteCancelled()
                    // Every page, whichever were asked for: the print screen picks out its range
                    copied -> callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    else -> callback.onWriteFailed(null)
                }
            }
        }
        worker.shutdown()
    }
}
