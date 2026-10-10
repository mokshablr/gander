package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * The fixture documents, on the device.
 *
 * They travel in the test APK's assets and are copied into the app's own cache
 * directory, where res/xml/file_paths.xml already exposes them through the
 * FileProvider the app ships. So a test hands the viewer the same kind of
 * content:// URI a file manager would, with a real read grant behind it, and
 * nothing in the app knows it came from a test.
 */
object DeviceFixtures {

    private val target: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testAssets
        get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun dir(): File = File(target.cacheDir, "fixtures").apply { mkdirs() }

    /** Copies [name] out of the test APK, once, and answers a URI for it. */
    fun uriFor(name: String): Uri {
        val file = File(dir(), name)
        if (!file.exists()) {
            testAssets.open(name).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return FileProvider.getUriForFile(
            target, "${target.packageName}.fileprovider", file
        )
    }

    /**
     * Where a file made on the device for one test goes, so [uriFor] and [viewIntent] serve
     * it as they serve the APK's own, which they never copy over a file already there.
     */
    fun made(name: String): File = File(dir(), name)

    /**
     * The intent the home screen sends when the reader taps a document. By the viewer's
     * internal name, because these URIs are on Gander's own FileProvider, which the exported
     * name refuses: see ViewerActivity.INTERNAL_VIEWER.
     */
    fun viewIntent(name: String): Intent =
        Intent()
            .setClassName(target, ViewerActivity.INTERNAL_VIEWER)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(name), target.contentResolver.getType(uriFor(name)))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun clear() {
        dir().deleteRecursively()
    }
}
