package io.nekohasekai.screensharing.session

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.text.Html
import android.util.Log
import androidx.core.content.FileProvider
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.MessageError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.random.Random

object ClipboardAccess {
    private const val TAG = "ClipboardAccess"
    private const val UTF8_PLAIN_TEXT = "public.utf8-plain-text"
    private const val HTML = "public.html"
    private const val URL = "public.url"
    private const val PNG = "public.png"
    private const val JPEG = "public.jpeg"
    private const val TIFF = "public.tiff"
    private const val CONCEALED = "org.nspasteboard.ConcealedType"
    private const val OWN_WRITE = "io.nekohasekai.screensharing.clipboardWrite"
    private const val SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val images = listOf(PNG to "image/png", JPEG to "image/jpeg", TIFF to "image/tiff")

    private val manager: ClipboardManager get() = App.instance.getSystemService(ClipboardManager::class.java)
    private val folder: File get() = File(App.instance.cacheDir, "Clipboard")

    private var known = 0L

    @Volatile
    private var changes = 0L

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) manager.addPrimaryClipChangedListener { changes += 1 }
    }

    val changeCount: Long
        get() {
            val description = try {
                manager.primaryClipDescription
            } catch (error: Exception) {
                Log.w(TAG, "read the clipboard description", error)
                null
            }
            if (description != null) {
                known = description.extras?.getLong(OWN_WRITE)?.takeIf { it != 0L }
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) description.timestamp else changes
            }
            return known
        }

    fun write(content: ClipboardContent): Long? {
        val previous = folder.listFiles().orEmpty()
        val mimeTypes = LinkedHashSet<String>()
        val items = ArrayList<ClipData.Item>()
        for (flavors in content) {
            val html = flavors[HTML]?.decodeToString()
            val link = flavors[URL]?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }
            val text = flavors[UTF8_PLAIN_TEXT]?.decodeToString() ?: link ?: html?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString() }
            val image = images.firstNotNullOfOrNull { (type, mimeType) -> flavors[type]?.let { Triple(type, mimeType, it) } }
            val uri = if (image != null) {
                try {
                    imageUri(image.first, image.third).also { mimeTypes.add(image.second) }
                } catch (error: Exception) {
                    Log.e(TAG, "write a clipboard image", error)
                    null
                }
            } else {
                link?.let { Uri.parse(it) }?.also { mimeTypes.add(ClipDescription.MIMETYPE_TEXT_URILIST) }
            }
            if (text == null && html == null && uri == null) continue
            if (html != null) mimeTypes.add(ClipDescription.MIMETYPE_TEXT_HTML)
            if (text != null) mimeTypes.add(ClipDescription.MIMETYPE_TEXT_PLAIN)
            items.add(ClipData.Item(text, html, null, uri))
        }
        val first = items.firstOrNull() ?: return null
        val clip = ClipData(null, mimeTypes.toTypedArray(), first)
        items.drop(1).forEach { clip.addItem(it) }
        val id = Random.nextLong() or Long.MIN_VALUE
        clip.description.extras = PersistableBundle().apply {
            putLong(OWN_WRITE, id)
            if (content.any { CONCEALED in it }) putBoolean(SENSITIVE, true)
        }
        try {
            manager.setPrimaryClip(clip)
        } catch (error: Exception) {
            Log.e(TAG, "write the clipboard", error)
            return null
        }
        known = id
        previous.forEach { it.delete() }
        return id
    }

    private fun imageUri(type: String, data: ByteArray): Uri {
        val directory = folder
        if (!directory.isDirectory && !directory.mkdirs()) throw MessageError("create $directory")
        val extension = when (type) {
            PNG -> "png"
            JPEG -> "jpg"
            else -> "tiff"
        }
        val file = File(directory, "${UUID.randomUUID()}.$extension")
        file.writeBytes(data)
        return FileProvider.getUriForFile(App.instance, "${App.instance.packageName}.files", file)
    }

    suspend fun read(): Pair<ClipboardContent, AlertState?> {
        val clip = runCatching { manager.primaryClip }
        val (content, error) = clip.getOrNull()?.let { primary ->
            if ((0 until primary.itemCount).any { primary.getItemAt(it).uri?.scheme == "content" }) {
                withContext(Dispatchers.IO) { load(primary) }
            } else {
                load(primary)
            }
        } ?: (emptyList<Map<String, ByteArray>>() to clip.exceptionOrNull())
        if (content.isNotEmpty() || error == null) return content to null
        return content to AlertState.failure(App.instance.getString(R.string.session_couldnt_send_clipboard), error)
    }

    private fun load(clip: ClipData): Pair<ClipboardContent, Exception?> {
        val context = App.instance
        val sensitive = clip.description.extras?.getBoolean(SENSITIVE) == true
        var failure: Exception? = null
        val content = ArrayList<Map<String, ByteArray>>()
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            val flavors = LinkedHashMap<String, ByteArray>()
            item.text?.toString()?.let { flavors[UTF8_PLAIN_TEXT] = it.encodeToByteArray() }
            item.htmlText?.let { flavors[HTML] = it.encodeToByteArray() }
            val uri = item.uri
            if (uri != null && uri.scheme == "content") {
                try {
                    val mimeType = context.contentResolver.getType(uri)
                    if (mimeType != null && mimeType.startsWith("image/")) {
                        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw MessageError("open $uri")
                        val type = images.firstOrNull { it.second == mimeType }?.first
                        if (type != null) {
                            flavors[type] = data
                        } else {
                            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw MessageError("decode a $mimeType image")
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            bitmap.recycle()
                            flavors[PNG] = stream.toByteArray()
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "read a clipboard item", error)
                    failure = failure ?: error
                }
            } else if (uri != null) {
                val link = uri.toString()
                flavors[URL] = link.encodeToByteArray()
                if (UTF8_PLAIN_TEXT !in flavors) flavors[UTF8_PLAIN_TEXT] = link.encodeToByteArray()
            }
            if (flavors.isEmpty()) continue
            if (sensitive) flavors[CONCEALED] = ByteArray(0)
            content.add(flavors)
        }
        return content to failure
    }
}
