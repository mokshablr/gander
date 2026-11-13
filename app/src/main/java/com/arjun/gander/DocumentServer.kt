package com.arjun.gander

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream

/** The host the viewer pages and the open document are served from, by Gander itself. */
internal const val ASSET_HOST = "appassets.androidplatform.net"

/** The query the text page asks for the document with, `?encoding=guess`: see [TextEncoding]. */
internal const val ENCODING_QUERY = "encoding"

/** The response header the guessed code page comes back under; app.js reads it by this name. */
internal const val ENCODING_HEADER = "X-Gander-Encoding"

/**
 * Answers every request a viewer page makes for the document [uri], a [kind] of [mime]: the
 * document at /doc/, a PDF's simple view pages at /render/, a Markdown file's pictures at
 * /beside, Gander's own pages and libraries from [assets] with the viewer's policy, and an empty
 * 404 for anything else on the web. It holds no views, since the WebView asks on a thread of its
 * own.
 */
internal class DocumentServer(
    private val resolver: ContentResolver,
    private val assets: WebViewAssetLoader,
    private val uri: Uri,
    private val kind: FileKind,
    private val mime: String,
    private val simplePdf: () -> SimplePdf?,
) {

    /** The document's length as its provider gave it, or -1; set before the page loads. */
    @Volatile
    var total = -1L

    private val picturesBeside = if (kind == FileKind.MD) PicturesBeside(resolver, uri) else null

    fun respond(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        // The document is served here rather than through WebViewAssetLoader
        // because a PathHandler is only given the path, and answering range
        // requests needs the Range header off the request itself. Never to the
        // simple view's page: its WebView is one too old for pdf.js, and nothing on
        // that page needs the file, which only the isolated renderer reads.
        if (kind != FileKind.PDF_SIMPLE &&
            url.scheme == "https" && url.host == ASSET_HOST &&
            url.path?.startsWith("/doc/") == true
        ) {
            return docResponse(
                request.requestHeaders["Range"], total,
                guessEncoding = url.getQueryParameter(ENCODING_QUERY) == "guess",
            )
        }
        // The simple view's pages, drawn by its isolated renderer. See SimplePdf.
        if (kind == FileKind.PDF_SIMPLE &&
            url.scheme == "https" && url.host == ASSET_HOST &&
            url.path?.startsWith("/render/") == true
        ) {
            return simplePdf()?.respond(url) ?: notFound()
        }
        // The pictures a Markdown file names beside it. See PicturesBeside.
        if (picturesBeside != null &&
            url.scheme == "https" && url.host == ASSET_HOST &&
            url.path == PicturesBeside.PATH
        ) {
            return picturesBeside.respond(url.getQueryParameter("path")) ?: notFound()
        }
        return assets.shouldInterceptRequest(url)?.withViewerPolicy() ?: when (url.scheme) {
            // Anything else a page asks the network for is answered here, with
            // nothing, instead of being handed on to the network stack. The missing
            // INTERNET permission would stop it there, but that would make one
            // control the whole of the guarantee; this is a second one, and it
            // holds even for a request from script that should never have run.
            "http", "https" -> notFound()
            // data: is bytes the page already holds. file: and content: are
            // refused by allowFileAccess and allowContentAccess in ViewerActivity.
            else -> null
        }
    }

    /**
     * Serves the open document at /doc/<anything>, answering range requests.
     *
     * Told that ranges are available, pdf.js pulls a large file in pieces as it
     * needs them instead of buffering all of it before drawing anything. On a
     * 53 MB scan that read was about 400 ms of a one second open, and it kept the
     * whole document in memory for as long as it was on screen.
     *
     * A fresh stream per request: the page asks for many, and out of order.
     *
     * Asked with [guessEncoding], by the text page, a whole file comes with the code page
     * [TextEncoding] finds it in, under [ENCODING_HEADER], when it is not UTF-8.
     */
    private fun docResponse(range: String?, total: Long, guessEncoding: Boolean = false): WebResourceResponse {
        return try {
            // Ranges are only offered for documents big enough to be worth the extra
            // round trips. Measured on a Nothing Phone 2: a 53 MB scan opened 177 ms
            // faster ranged, while a 251 KB document opened 67 ms slower. Below the
            // threshold one bulk read wins; above it, reading the lot dominates.
            val rangeable = useRanges(total)
            val span = if (rangeable) range?.let { parseRange(it, total) } else null
            // The document is on the pages' own host, which ViewerPolicy trusts for
            // scripts and styles. nosniff means the browser will only run it as either
            // if its type says it is one, so markup that a renderer let slip cannot load
            // a crafted file as the script that script-src 'self' would otherwise allow.
            val nosniff = "X-Content-Type-Options" to "nosniff"
            if (span == null) {
                val headers = mutableMapOf(nosniff)
                if (rangeable) headers["Accept-Ranges"] = "bytes"
                if (total >= 0) headers["Content-Length"] = total.toString()
                var body = resolver.openInputStream(uri)
                if (guessEncoding && body != null) {
                    // The head is read once and handed back in front of the rest. A guess that
                    // fails leaves the page reading UTF-8, as it did before there was one.
                    val head = TextEncoding.readHead(body)
                    runCatching { TextEncoding.guess(head) }.getOrNull()
                        ?.let { headers[ENCODING_HEADER] = it }
                    body = SequenceInputStream(ByteArrayInputStream(head), body)
                }
                WebResourceResponse(mime, null, 200, "OK", headers, body)
            } else {
                val (start, end) = span
                WebResourceResponse(
                    mime, null, 206, "Partial Content",
                    mapOf(
                        nosniff,
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes $start-$end/$total",
                        "Content-Length" to (end - start + 1).toString()
                    ),
                    slice(start, end)
                )
            }
        } catch (e: Exception) {
            notFound()
        }
    }

    /**
     * Exactly [start, end], seeking to the offset rather than reading up to it.
     *
     * Asked for as an asset descriptor, which is allowed to be a window onto a larger file,
     * rather than as a plain one, which is not: ContentResolver refuses openFileDescriptor
     * outright for a window. That is how a file stored inside a zip arrives, so the range is
     * counted from where the window starts, which is zero for every other file.
     */
    private fun slice(start: Long, end: Long): InputStream {
        val afd = resolver.openAssetFileDescriptor(uri, "r")
            ?: throw java.io.IOException("cannot open $uri")
        val stream = java.io.FileInputStream(afd.fileDescriptor)
        val from = afd.startOffset + start
        // Seekable for anything file backed; a pipe has to be read through instead
        runCatching { stream.channel.position(from) }
            .onFailure { runCatching { stream.skip(from) } }
        return LimitedInputStream(stream, end - start + 1, afd)
    }
}

/** See [ViewerPolicy]: the header is what carries the policy into the pdf.js worker. */
private fun WebResourceResponse.withViewerPolicy(): WebResourceResponse = apply {
    responseHeaders = responseHeaders.orEmpty() + ("Content-Security-Policy" to ViewerPolicy.CSP)
}

private fun notFound(): WebResourceResponse = WebResourceResponse(
    "text/plain", "utf-8", 404, "Not Found",
    null, ByteArrayInputStream(ByteArray(0))
)

/**
 * Gander's own pages, which is everything a viewer may navigate to: unsupported.html
 * hands a file on to text.html, and a link inside a document may jump to a heading in
 * the page it is on. Nothing off this host is opened, in the viewer or anywhere else,
 * so a link cannot hand the browser a URL either. Nor the document at /doc/, the one
 * thing on the host that is not Gander's: opened as a page, a file would be rendered
 * as whatever its type says rather than by the viewer that makes it safe to look at.
 */
internal fun isViewerPage(url: Uri): Boolean {
    val path = url.path ?: return false
    return url.scheme == "https" && url.host == ASSET_HOST &&
        path.startsWith("/assets/viewer/") && path.endsWith(".html") && ".." !in path
}

/** Length in bytes, or -1 when the provider declines to say. */
internal fun documentLength(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
}.getOrNull()?.takeIf { it >= 0 } ?: -1L
