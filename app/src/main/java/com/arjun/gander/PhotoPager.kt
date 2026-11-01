package com.arjun.gander

import android.content.ContentResolver
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * A photo from a folder added in Gander, with the folder's other photos a swipe away, in the order
 * the home screen sorts the folder in. Issue #75. A photo another app opens in Gander comes alone,
 * since Android shares only that one file.
 *
 * Only the photo on screen may play, if it moves, since that takes the viewer's one WebView.
 */
internal class PhotoPager(
    context: Context,
    private val opened: Uri,
    first: ChildDoc,
    private val host: Host,
) {

    /** What the viewer does for the pages. */
    interface Host {
        /** Hands [then] whether the picture at [uri] moves and its turn, asked off the main thread. */
        fun look(uri: Uri, ext: String, then: (Look) -> Unit)

        /** The tiling view for a still photo, which calls [failed] when it can't draw it. */
        fun still(name: String, rotation: Int, failed: () -> Unit): SubsamplingScaleImageView

        /** Puts the page that plays a moving picture into [frame]. */
        fun play(frame: FrameLayout, uri: Uri, name: String, ext: String)

        /** Takes that page away again. */
        fun stopPlaying()

        /** The photo on screen is now [photo], number [at] from 0 of [of]. */
        fun shown(photo: ChildDoc, uri: Uri, at: Int, of: Int)
    }

    /** Whether a picture moves, and the turn its EXIF asks for. See [pictureLook]. */
    data class Look(val moves: Boolean, val rotation: Int)

    private var photos = listOf(first)
    private val pages = Pages()

    /** The page playing a moving picture, if one is. */
    private var playing: Page? = null

    val view = ViewPager2(context).apply {
        setBackgroundColor(Color.BLACK)
        // The photos either side are drawn before they are reached, so one slides in whole
        offscreenPageLimit = 1
        adapter = pages
        (getChildAt(0) as RecyclerView).apply {
            // A page further off is drawn again on the way back, rather than kept with its bitmap
            setItemViewCacheSize(0)
            itemAnimator = null
        }
        registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                announce(position)
                // A jump straight to a page reports no change of scroll state, so no rest
                // either. Settled after the layout that brings the page in.
                if (scrollState == ViewPager2.SCROLL_STATE_IDLE) post(::settle)
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) settle()
            }
        })
    }

    val count: Int get() = photos.size

    /** The photos' names, in order. Read by tests. */
    val names: List<String> get() = photos.map { it.name }

    /** The photo on screen. */
    val shown: Uri get() = uriOf(photos[view.currentItem.coerceIn(photos.indices)])

    /** Puts the folder's other photos either side of the one opened, which stays on screen. */
    fun fill(all: List<ChildDoc>, at: Int) {
        if (photos.size != 1 || all.size < 2 || all.getOrNull(at)?.docId != photos[0].docId) return
        photos = all
        pages.notifyItemRangeInserted(0, at)
        pages.notifyItemRangeInserted(at + 1, all.size - at - 1)
        view.setCurrentItem(at, false)
        // Said again for the first photo, which no page change announces
        announce(at)
    }

    private fun uriOf(photo: ChildDoc): Uri = DocumentsContract.buildDocumentUriUsingTree(opened, photo.docId)

    private fun announce(position: Int) {
        val photo = photos.getOrNull(position) ?: return
        host.shown(photo, uriOf(photo), position, photos.size)
    }

    private fun onScreen(photo: ChildDoc) = photos.getOrNull(view.currentItem)?.docId == photo.docId

    /** Once the pages have come to rest, the one on screen plays if it moves, and only that one. */
    private fun settle() {
        if (view.scrollState != ViewPager2.SCROLL_STATE_IDLE) return
        val here = (view.getChildAt(0) as RecyclerView)
            .findViewHolderForAdapterPosition(view.currentItem) as? Page
        if (playing != null && playing !== here) stop()
        val photo = here?.photo ?: return
        if (here.moves && playing == null && onScreen(photo)) {
            playing = here
            host.play(here.frame, uriOf(photo), photo.name, extOf(photo))
        }
    }

    private fun stop() {
        val page = playing ?: return
        playing = null
        host.stopPlaying()
        // And the card that says a page's renderer has gone, if that is what it shows
        page.frame.removeAllViews()
    }

    private inner class Page(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        var photo: ChildDoc? = null

        /** Whether its picture needs the page that plays it, rather than the tiling view. */
        var moves = false

        fun bind(next: ChildDoc) {
            clear()
            photo = next
            val uri = uriOf(next)
            val ext = extOf(next)
            if (FileKind.detect(ext, next.mime.ifEmpty { null }) != FileKind.IMAGE) return needsPlaying()
            host.look(uri, ext) { look ->
                // Swiped past, and drawing another photo by now
                if (photo !== next) return@look
                if (look.moves) return@look needsPlaying()
                val still = host.still(next.name, look.rotation) {
                    // Some formats decode in the WebView where the region decoder gives up
                    if (photo === next) {
                        clearStill()
                        needsPlaying()
                    }
                }
                frame.addView(still, MATCH)
                still.setImage(ImageSource.uri(uri))
            }
        }

        private fun needsPlaying() {
            moves = true
            // After the layout this may be part of, which a page put in mid-way would upset
            view.post(::settle)
        }

        fun clear() {
            if (playing === this) stop()
            clearStill()
            moves = false
            photo = null
        }

        private fun clearStill() {
            for (i in 0 until frame.childCount) (frame.getChildAt(i) as? SubsamplingScaleImageView)?.recycle()
            frame.removeAllViews()
        }
    }

    private inner class Pages : RecyclerView.Adapter<Page>() {
        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            // A page has to fill the pager
            Page(FrameLayout(parent.context).apply { layoutParams = MATCH })

        override fun onBindViewHolder(holder: Page, position: Int) = holder.bind(photos[position])

        override fun onViewRecycled(holder: Page) = holder.clear()
    }

    private companion object {
        val MATCH get() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        fun extOf(photo: ChildDoc) = photo.name.substringAfterLast('.', "").lowercase()
    }
}

/**
 * The photos beside [photo] in the folder it was opened from, in the order the home screen lists
 * that folder in, and where [photo] is among them. Null when it wasn't opened from a folder added
 * in Gander, or the folder can't be read.
 */
internal fun photosAround(
    resolver: ContentResolver,
    photo: Uri,
    sortOrder: Settings.SortOrder,
): Pair<List<ChildDoc>, Int>? {
    if (!DocumentsContract.isTreeUri(photo)) return null
    val folder = runCatching { DocumentsContract.findDocumentPath(resolver, photo)?.path }
        .getOrNull()?.dropLast(1)?.lastOrNull() ?: return null
    val photos = photosIn(childrenOf(resolver, photo, folder) ?: return null, sortOrder)
    val docId = DocumentsContract.getDocumentId(photo)
    val at = photos.indexOfFirst { it.docId == docId }
    return if (at < 0) null else photos to at
}

/** The pictures in a folder's listing, in the order the home screen shows them. */
internal fun photosIn(children: List<ChildDoc>, sortOrder: Settings.SortOrder): List<ChildDoc> =
    orderChildren(children, sortOrder).second.filter {
        val kind = FileKind.detect(it.name.substringAfterLast('.', "").lowercase(), it.mime.ifEmpty { null })
        kind == FileKind.IMAGE || kind == FileKind.IMAGE_WEB
    }

/**
 * Whether the picture at [uri] moves, which of the formats the tiling view draws only a WebP or a
 * PNG can, and if not, the turn its EXIF asks for.
 */
internal fun pictureLook(resolver: ContentResolver, uri: Uri, ext: String): PhotoPager.Look {
    val moves = (ext == "webp" || ext == "png") &&
        runCatching { resolver.openInputStream(uri)?.use(::isAnimated) }.getOrNull() == true
    return PhotoPager.Look(moves, if (moves) 0 else Thumbs.exifRotation(resolver, uri))
}

/** Whether [a] and [b] are documents under the same tree grant. */
internal fun inSameTree(a: Uri, b: Uri): Boolean = runCatching {
    DocumentsContract.isTreeUri(a) && DocumentsContract.isTreeUri(b) && a.authority == b.authority &&
        DocumentsContract.getTreeDocumentId(a) == DocumentsContract.getTreeDocumentId(b)
}.getOrDefault(false)
