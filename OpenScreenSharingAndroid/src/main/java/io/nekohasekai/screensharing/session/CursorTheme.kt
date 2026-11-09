package io.nekohasekai.screensharing.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.nekohasekai.libsharing.Libsharing
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.model.AppDirectory
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.CursorStyle
import io.nekohasekai.screensharing.session.StandardCursor.ResizeDirections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

class CursorTheme(val directory: File) {
    @Serializable
    private class Manifest(val name: String, val cursors: Map<String, List<Frame>>)

    @Serializable
    private class Frame(val file: String, val scale: Double, val hotspotX: Double, val hotspotY: Double)

    class Drawn(val image: Bitmap, val hotspot: Offset, val scale: Float)

    class Preview(val image: Bitmap, val scale: Float)

    val id: String = directory.name
    val name: String
    private val cursors: Map<String, List<Frame>>
    private val images = HashMap<String, Bitmap>()
    private var previews: Pair<Float, List<Preview>>? = null

    init {
        val manifest = json.decodeFromString<Manifest>(File(directory, "theme.json").readText())
        name = manifest.name
        cursors = manifest.cursors.mapValues { (_, frames) -> frames.sortedBy { it.scale } }
    }

    fun image(cursor: StandardCursor, scale: Float): Drawn? {
        val roles = when (cursor) {
            StandardCursor.Arrow -> emptyList()
            StandardCursor.ContextualMenu -> listOf("context-menu")
            StandardCursor.PointingHand -> listOf("pointer")
            StandardCursor.OpenHand -> listOf("grab", "pointer")
            StandardCursor.ClosedHand -> listOf("grabbing", "grab", "pointer")
            StandardCursor.DragLink -> listOf("alias")
            StandardCursor.DragCopy -> listOf("copy")
            StandardCursor.OperationNotAllowed -> listOf("not-allowed")
            StandardCursor.IBeam -> listOf("text")
            StandardCursor.VerticalIBeam -> listOf("vertical-text", "text")
            StandardCursor.Crosshair -> listOf("crosshair")
            StandardCursor.ZoomIn -> listOf("zoom-in")
            StandardCursor.ZoomOut -> listOf("zoom-out")
            is StandardCursor.Resize -> when (cursor.directions) {
                ResizeDirections.Left + ResizeDirections.Right -> listOf("ew-resize", "col-resize")
                ResizeDirections.Up + ResizeDirections.Down -> listOf("ns-resize", "row-resize")
                ResizeDirections.UpLeft + ResizeDirections.DownRight -> listOf("nwse-resize")
                ResizeDirections.UpRight + ResizeDirections.DownLeft -> listOf("nesw-resize")
                ResizeDirections.Left -> listOf("w-resize", "ew-resize", "col-resize")
                ResizeDirections.Right -> listOf("e-resize", "ew-resize", "col-resize")
                ResizeDirections.Up -> listOf("n-resize", "ns-resize", "row-resize")
                ResizeDirections.Down -> listOf("s-resize", "ns-resize", "row-resize")
                ResizeDirections.UpLeft -> listOf("nw-resize", "nwse-resize")
                ResizeDirections.DownRight -> listOf("se-resize", "nwse-resize")
                ResizeDirections.UpRight -> listOf("ne-resize", "nesw-resize")
                ResizeDirections.DownLeft -> listOf("sw-resize", "nesw-resize")
                else -> listOf("move", "all-scroll")
            }
        }
        val role = (roles + "default").firstOrNull { it in cursors } ?: return null
        val frames = cursors.getValue(role)
        val frame = frames.firstOrNull { it.scale >= scale } ?: frames.lastOrNull() ?: return null
        val image = image(frame.file) ?: return null
        return Drawn(image, Offset((frame.hotspotX / frame.scale).toFloat(), (frame.hotspotY / frame.scale).toFloat()), frame.scale.toFloat())
    }

    fun previewImages(scale: Float): List<Preview> {
        previews?.let { (cachedScale, images) -> if (cachedScale == scale) return images }
        val images = previewRoles.asSequence().mapNotNull { role ->
            val frames = cursors[role] ?: return@mapNotNull null
            val frame = frames.firstOrNull { it.scale >= scale } ?: frames.lastOrNull() ?: return@mapNotNull null
            val image = image(frame.file) ?: return@mapNotNull null
            Preview(croppedToContent(image), frame.scale.toFloat())
        }.take(PREVIEW_COUNT).toList()
        previews = scale to images
        return images
    }

    private fun image(file: String): Bitmap? {
        images[file]?.let { return it }
        val image = BitmapFactory.decodeFile(File(directory, file).path) ?: return null
        images[file] = image
        return image
    }

    companion object {
        private const val PREVIEW_COUNT = 8
        private val previewRoles = listOf("default", "pointer", "text", "grab", "not-allowed", "wait", "crosshair", "move", "ew-resize", "zoom-in")
        private val json = Json { ignoreUnknownKeys = true }

        private fun croppedToContent(image: Bitmap): Bitmap {
            val width = image.width
            val height = image.height
            val pixels = IntArray(width * height)
            image.getPixels(pixels, 0, width, 0, 0, width, height)
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (pixels[y * width + x] ushr 24 == 0) continue
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
            if (right < 0) return image
            return Bitmap.createBitmap(image, left, top, right - left + 1, bottom - top + 1)
        }
    }
}

object CursorThemeStore {
    var themes by mutableStateOf<List<CursorTheme>>(emptyList())
        private set
    var isImporting by mutableStateOf(false)
        private set

    val directory: Result<File> by lazy {
        runCatching {
            File(App.instance.filesDir, "CursorThemes").also { Files.createDirectories(it.toPath()) }
        }
    }

    private val collator: Collator = Collator.getInstance().also { (it as? RuleBasedCollator)?.numericCollation = true }

    init {
        reload()
    }

    fun theme(id: String): CursorTheme? = themes.firstOrNull { it.id == id }

    suspend fun importFile(uri: Uri) {
        val directory = directory.getOrThrow()
        isImporting = true
        try {
            withContext(Dispatchers.IO) {
                val staging = File(directory, ".import-${UUID.randomUUID()}")
                val picked = File(AppDirectory.caches, "CursorThemeImports/${UUID.randomUUID()}")
                try {
                    Libsharing.importCursorTheme(copyPickedFile(uri, picked).path, staging.path)
                    for (theme in staging.listFiles().orEmpty()) {
                        Files.move(theme.toPath(), File(directory, UUID.randomUUID().toString()).toPath())
                    }
                } finally {
                    staging.deleteRecursively()
                    picked.deleteRecursively()
                }
            }
        } finally {
            isImporting = false
            reload()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    fun delete(theme: CursorTheme) {
        theme.directory.toPath().deleteRecursively()
        reload()
    }

    fun displayName(uri: Uri): String {
        val document = if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        } else {
            uri
        }
        val name = try {
            App.instance.contentResolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (error: Exception) {
            null
        }
        return name?.takeIf { it.isNotEmpty() } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/').substringAfterLast(':')
    }

    private fun copyPickedFile(uri: Uri, folder: File): File {
        Files.createDirectories(folder.toPath())
        val resolver = App.instance.contentResolver
        val name = displayName(uri).replace('/', '_').trimStart('.').ifEmpty { "theme" }
        if (DocumentsContract.isTreeUri(uri)) {
            val file = File(folder, "$name.zip")
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                addTree(zip, uri, DocumentsContract.getTreeDocumentId(uri), "$name/")
            }
            return file
        }
        val file = File(folder, name)
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
        return file
    }

    private fun addTree(zip: ZipOutputStream, tree: Uri, documentId: String, path: String) {
        val resolver = App.instance.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val entries = resolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList<Triple<String, String, String>> {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        } ?: throw FileNotFoundException(children.toString())
        for ((id, name, type) in entries) {
            if (type == DocumentsContract.Document.MIME_TYPE_DIR) {
                addTree(zip, tree, id, "$path$name/")
                continue
            }
            val input = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id)) ?: throw FileNotFoundException(name)
            zip.putNextEntry(ZipEntry("$path$name"))
            input.use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun reload() {
        val entries = directory.getOrNull()?.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        val existing = themes.associateBy { it.id }
        themes = entries.mapNotNull { entry ->
            existing[entry.name] ?: try {
                CursorTheme(entry)
            } catch (error: Exception) {
                null
            }
        }.sortedWith { left, right -> collator.compare(left.name, right.name) }
        val style = AppSettings.cursorStyle
        if (style is CursorStyle.Theme && theme(style.id) == null) {
            AppSettings.cursorStyle = CursorStyle.System
        }
    }
}
