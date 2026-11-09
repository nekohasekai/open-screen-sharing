@file:OptIn(ExperimentalPathApi::class)

package io.nekohasekai.screensharing.sessionui

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.model.AppDirectory
import io.nekohasekai.screensharing.session.RemoteDragItem
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.session.TransferProgress
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.deleteRecursively
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

class RemoteFileProvider : ContentProvider() {
    class Offered(val uri: Uri, val name: String, val mimeType: String)

    private class Entry(
        val name: String,
        val mimeType: String,
        val remotePath: String,
        val isFolder: Boolean,
        val folder: File,
        val controller: WeakReference<SessionController>,
    ) {
        var file: File? = null
        var failure: Throwable? = null
        var loading: CountDownLatch? = null
        var progress: TransferProgress? = null
    }

    override fun onCreate(): Boolean {
        val drags = File(context?.cacheDir ?: return false, DRAGS)
        thread(name = "RemoteFileProvider") { runCatching { drags.toPath().deleteRecursively() } }
        return true
    }

    override fun getType(uri: Uri): String? = entry(uri)?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val entry = entry(uri) ?: return null
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        val size = synchronized(entry) { entry.file?.length() }
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) entry.name else size })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openFile(uri, mode, null)

    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("$uri is read-only")
        val entry = entry(uri) ?: throw FileNotFoundException(uri.toString())
        if (Looper.myLooper() == Looper.getMainLooper()) throw FileNotFoundException("$uri can’t be loaded on the main thread")
        return ParcelFileDescriptor.open(load(entry, signal), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun entry(uri: Uri): Entry? = uri.pathSegments.firstOrNull()?.let { entries[it] }

    private fun load(entry: Entry, signal: CancellationSignal?): File {
        val latch = synchronized(entry) {
            entry.file?.let { return it }
            entry.loading ?: CountDownLatch(1).also { latch ->
                entry.loading = latch
                entry.failure = null
                mainHandler.post { download(entry, latch) }
            }
        }
        signal?.setOnCancelListener {
            mainHandler.post { synchronized(entry) { entry.progress }?.cancel() }
        }
        latch.await()
        synchronized(entry) {
            entry.file?.let { return it }
            throw FileNotFoundException(entry.failure?.message ?: entry.name)
        }
    }

    companion object {
        private const val DRAGS = "Drags"
        private const val FOLDER_TYPE = "application/zip"
        private const val DEFAULT_TYPE = "application/octet-stream"
        private val entries = ConcurrentHashMap<String, Entry>()
        private val mainHandler = Handler(Looper.getMainLooper())

        val dragsFolder: File get() = File(AppDirectory.caches, DRAGS)

        fun offer(
            controller: SessionController,
            items: List<RemoteDragItem>,
            folder: File,
        ): List<Offered> {
            val authority = "${App.instance.packageName}.remotefiles"
            return items.map { item ->
                val base = item.path.trimEnd('/').substringAfterLast('/').ifEmpty { App.instance.getString(R.string.session_item) }
                val name = if (item.isFolder) "$base.zip" else base
                val type = if (item.isFolder) {
                    FOLDER_TYPE
                } else {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(base.substringAfterLast('.', "").lowercase()) ?: DEFAULT_TYPE
                }
                val token = UUID.randomUUID().toString()
                entries[token] = Entry(name, type, item.path, item.isFolder, folder, WeakReference(controller))
                val uri = Uri.Builder()
                    .scheme(ContentResolver.SCHEME_CONTENT)
                    .authority(authority)
                    .appendPath(token)
                    .appendPath(name)
                    .build()
                Offered(uri, name, type)
            }
        }

        fun discard(folder: File) {
            entries.values.removeAll { it.folder == folder }
            thread(name = "RemoteFileProvider") { runCatching { folder.toPath().deleteRecursively() } }
        }

        private fun download(entry: Entry, latch: CountDownLatch) {
            val controller = entry.controller.get()
            if (controller == null) {
                finish(entry, latch, Result.failure(FileNotFoundException("the session has ended")))
                return
            }
            val destination = File(entry.folder, UUID.randomUUID().toString())
            if (!destination.mkdirs()) {
                finish(entry, latch, Result.failure(IOException("create ${destination.path}")))
                return
            }
            val progress = TransferProgress()
            synchronized(entry) { entry.progress = progress }
            controller.downloadFile(entry.remotePath, destination, progress) { result ->
                val downloaded = result.getOrNull()
                when {
                    downloaded == null -> finish(entry, latch, result)
                    entry.isFolder -> thread(name = "RemoteFileProvider") { finish(entry, latch, runCatching { zip(downloaded) }) }
                    downloaded.toPath().isRegularFile(LinkOption.NOFOLLOW_LINKS) -> finish(entry, latch, result)
                    else -> finish(entry, latch, Result.failure(FileNotFoundException("${entry.name} is not a regular file")))
                }
            }
        }

        private fun finish(entry: Entry, latch: CountDownLatch, result: Result<File>) {
            synchronized(entry) {
                entry.file = result.getOrNull()
                entry.failure = result.exceptionOrNull()
                entry.loading = null
                entry.progress = null
            }
            latch.countDown()
        }

        private fun zip(folder: File): File {
            val root = folder.parentFile ?: throw IOException("zip ${folder.path}")
            val archive = File(root, "${folder.name}.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { output ->
                for (file in folder.toPath().walk(PathWalkOption.INCLUDE_DIRECTORIES)) {
                    val path = file.relativeTo(root.toPath()).invariantSeparatorsPathString
                    if (file.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
                        output.putNextEntry(ZipEntry("$path/"))
                        output.closeEntry()
                        continue
                    }
                    if (!file.isRegularFile(LinkOption.NOFOLLOW_LINKS)) continue
                    output.putNextEntry(ZipEntry(path).apply { time = file.getLastModifiedTime().toMillis() })
                    file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            return archive
        }
    }
}
