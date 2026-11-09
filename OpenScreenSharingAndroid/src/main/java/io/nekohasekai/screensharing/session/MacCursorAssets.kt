@file:OptIn(ExperimentalPathApi::class)

package io.nekohasekai.screensharing.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Xml
import io.nekohasekai.screensharing.app.MessageError
import io.nekohasekai.screensharing.model.AppDirectory
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.math.ceil

class MacCursorAssets(downloadFiles: (remotePaths: List<String>, localFolder: File) -> Unit) {
    private val directory: File = prepare(downloadFiles)
    private val images = HashMap<String, Bitmap>()

    fun image(fingerprint: Long): Bitmap? {
        val name = sources[fingerprint] ?: return null
        synchronized(images) {
            images[name]?.let { return it }
            val image = BitmapFactory.decodeFile(File(directory, "$name.png").path) ?: return null
            images[name] = image
            return image
        }
    }

    companion object {
        const val SCALE = 5f

        private const val SYSTEM_VERSION = "/System/Library/CoreServices/SystemVersion.plist"
        private const val CURSOR_FOLDER =
            "/System/Library/Frameworks/ApplicationServices.framework/Versions/A/Frameworks/HIServices.framework/Versions/A/Resources/cursors"
        private const val CURSOR_ASSET = "/System/Library/PrivateFrameworks/SkyLight.framework/Versions/A/Resources/CursorAsset"
        private const val ARROW = "arrow"
        private val directory = File(AppDirectory.caches, "RemoteCursors")
        private val downloads: File by lazy {
            File(directory, "Downloads").also { runCatching { it.toPath().deleteRecursively() } }
        }

        // Cursor images of macOS 27.0.1 26A434: the arrow is com.apple.coregraphics.ArrowS (id 100) in SkyLight's
        // CursorAsset, the I-beam is its IBeamS, which HIServices cursors/ibeamhorizontal draws pixel for pixel, and the
        // rest are drawn from HIServices cursors/<name>, or cursors/macos27/<name> for closedhand, openhand and
        // pointinghand while the SwiftUI/Calistoga feature flag is on (HIServices 834, UUID
        // 185A502D-7E89-344D-9718-11FFB39205FF, _setCursorFromBundle 0x187e894d0..0x187e89534,
        // ____CalistogaEnabled_block_invoke 0x187eb0bb8..0x187eb0bd0).
        private val sources: Map<Long, String> = mapOf(
            0x550cf3e12f82e290uL.toLong() to ARROW,
            0x892a7ece621ac8c5uL.toLong() to "ibeamhorizontal",
            0x9ae3c16e5e50bde8uL.toLong() to "ibeamvertical",
            0xa1d04e5d7b3638c1uL.toLong() to "contextualmenu",
            0x794a89c5f925811duL.toLong() to "pointinghand",
            0x50644df703aa9698uL.toLong() to "openhand",
            0xbee3f9deb4290ac6uL.toLong() to "closedhand",
            0xc45e03a98e56c597uL.toLong() to "makealias",
            0x404d0f11c2436172uL.toLong() to "copy",
            0x95ad88c4cc9e3d42uL.toLong() to "notallowed",
            0xbb6e1a08616ba322uL.toLong() to "cross",
            0x099f7efbca83ccbauL.toLong() to "zoomin",
            0x1343377190e903aauL.toLong() to "zoomout",
            0xd39b94f838be4e58uL.toLong() to "resizeleft",
            0x42d8bbc7a95a09cauL.toLong() to "resizeright",
            0x95e871169704c06duL.toLong() to "resizeleftright",
            0xc4629ef7e72b4692uL.toLong() to "resizeup",
            0xddefb65d0e8d7aa3uL.toLong() to "resizedown",
            0x99faf011b1d1672auL.toLong() to "resizeupdown",
            0xc272dbe8fb06c3b0uL.toLong() to "resizenorthsouth",
            0x8d1cc66ab524f521uL.toLong() to "resizenorth",
            0x21683648e46facfcuL.toLong() to "resizesouth",
            0xbf87b07eefdd81bfuL.toLong() to "resizeeastwest",
            0x96f9141fdf6a48f0uL.toLong() to "resizewest",
            0x248f97736835ac0duL.toLong() to "resizeeast",
            0x36a33a215de72d90uL.toLong() to "resizenorthwestsoutheast",
            0xe533934d212ce831uL.toLong() to "resizenorthwest",
            0x1427a75fc2437d9auL.toLong() to "resizesoutheast",
            0x8c24bd91dffa9649uL.toLong() to "resizenortheastsouthwest",
            0x576440b0b8e85e5buL.toLong() to "resizenortheast",
            0x16531b30a60cab6buL.toLong() to "resizesouthwest",
        )

        private fun prepare(downloadFiles: (List<String>, File) -> Unit): File {
            val download = File(downloads, UUID.randomUUID().toString())
            Files.createDirectories(download.toPath())
            try {
                downloadFiles(listOf(SYSTEM_VERSION), download)
                val version = PropertyList.read(File(download, "SystemVersion.plist")) as? Map<*, *>
                val build = (version?.get("ProductBuildVersion") as? String)
                    ?.takeIf { build -> build.isNotEmpty() && build.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                    ?: throw MessageError("SystemVersion.plist has no build version")
                val assets = File(directory, "$build@${SCALE.toInt()}x")
                if (assets.exists()) return assets
                downloadFiles(listOf(CURSOR_FOLDER, CURSOR_ASSET), download)
                val rendered = File(download, "rendered")
                Files.createDirectories(rendered.toPath())
                val cursors = File(download, "cursors")
                val asset = try {
                    RandomAccessFile(File(download, "CursorAsset"), "r").use { file ->
                        file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()).order(ByteOrder.LITTLE_ENDIAN)
                    }
                } catch (error: IOException) {
                    null
                }
                for (name in sources.values.toSet()) {
                    val image = (
                        if (name == ARROW) {
                            asset?.let(::arrowImage)
                        } else {
                            val modern = File(File(cursors, "macos27"), name)
                            renderCursor(if (modern.exists()) modern else File(cursors, name))
                        }
                        ) ?: continue
                    File(rendered, "$name.png").outputStream().use { stream ->
                        if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw MessageError("Bitmap.compress failed")
                    }
                }
                try {
                    Files.move(rendered.toPath(), assets.toPath())
                } catch (error: IOException) {
                    if (!assets.exists()) throw error
                }
                return assets
            } finally {
                runCatching { download.toPath().deleteRecursively() }
            }
        }

        // HIServices _setCursorFromBundle defaults shadowcolor to black at 1/3 alpha, and _createScaledCursor draws
        // cursor.pdf into a DeviceRGB bitmap of the size times the scale rounded up, keeping the shadow offset and blur in
        // device pixels at every scale (HIServices 834, macOS 27.0.1 26A434, UUID 185A502D-7E89-344D-9718-11FFB39205FF,
        // _setCursorFromBundle 0x187e89638..0x187e89644, _createScaledCursor 0x187e89d0c..0x187e89e1c).
        private fun renderCursor(folder: File): Bitmap? {
            val info = (
                try {
                    PropertyList.read(File(folder, "info.plist")) as? Map<*, *>
                } catch (error: Exception) {
                    null
                }
                ) ?: return null
            if (((info["frames"] as? Number)?.toInt() ?: 1) != 1) return null
            fun number(key: String): Double = (info[key] as? Number)?.toDouble() ?: 0.0
            val shadowColor = doubleArrayOf(0.0, 0.0, 0.0, 1.0 / 3)
            (info["shadowcolor"] as? List<*>)?.take(4)?.forEachIndexed { index, component ->
                (component as? Number)?.let { shadowColor[index] = it.toDouble() }
            }
            val content = try {
                val descriptor = ParcelFileDescriptor.open(File(folder, "cursor.pdf"), ParcelFileDescriptor.MODE_READ_ONLY)
                val document = try {
                    PdfRenderer(descriptor)
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
                document.use { renderer ->
                    if (renderer.pageCount < 1) return null
                    renderer.openPage(0).use { page ->
                        val width = ceil(page.width * SCALE).toInt()
                        val height = ceil(page.height * SCALE).toInt()
                        if (width <= 0 || height <= 0) return null
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            } catch (error: Exception) {
                return null
            }
            val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            // CGContextSetShadow's blur is twice the standard deviation of its Gaussian, as WebKit relies on when it
            // hands CSS shadow blur radii to CoreGraphics unchanged.
            val sigma = number("blur") / 2
            val blur = Paint()
            if (sigma > 0.5) {
                blur.maskFilter = BlurMaskFilter(((sigma - 0.5) / 0.57735).toFloat(), BlurMaskFilter.Blur.NORMAL)
            }
            val offset = IntArray(2)
            val shadow = content.extractAlpha(blur, offset)
            val channels = shadowColor.map { (it.coerceIn(0.0, 1.0).toFloat() * 255f + 0.5f).toInt() }
            val tint = Paint().apply { color = Color.argb(channels[3], channels[0], channels[1], channels[2]) }
            canvas.drawBitmap(shadow, offset[0] + number("shadowoffsetx").toFloat(), offset[1] - number("shadowoffsety").toFloat(), tint)
            canvas.drawBitmap(content, 0f, 0f, null)
            return image
        }

        // CursorAsset is a fat Mach-O whose only export, _cursors, starts __DATA_CONST,__const: {u32 id, descriptor
        // pointer} pairs up to a null pointer. Descriptor: f64 width, height, hotX, hotY (points), u64 frames,
        // f32 duration, u64 image count, image pointers. Image: u64 pixel width, frame pixel height, f64 hotX, hotY
        // (pixels), then premultiplied BGRA rows with frames stacked vertically. Pointers are
        // DYLD_CHAINED_PTR_ARM64E_USERLAND24 rebases whose low 32 bits are the address (SkyLight 953.5.5, macOS 27.0.1
        // 26A434, UUID 8A3B348E-4637-3685-92D0-6CBC2F36A234, ___cursor_asset_data_block_invoke 0x1870b5d90..0x1870b5dc4,
        // _CGXUpdateHardwareColorCursors 0x1874f53d4..0x1874f54b0).
        private fun arrowImage(bytes: ByteBuffer): Bitmap? {
            val size = bytes.capacity().toLong()
            fun u32(offset: Long): Long? = if (offset >= 0 && offset + 4 <= size) bytes.getInt(offset.toInt()).toLong() and 0xffffffffL else null
            fun u64(offset: Long): Long? = if (offset >= 0 && offset + 8 <= size) bytes.getLong(offset.toInt()) else null
            fun be32(offset: Long): Long? = u32(offset)?.let { Integer.reverseBytes(it.toInt()).toLong() and 0xffffffffL }
            fun string(offset: Long): String {
                val raw = ByteArray(16) { bytes.get((offset + it).toInt()) }
                val length = raw.indexOf(0).takeIf { it >= 0 } ?: raw.size
                return String(raw, 0, length, Charsets.UTF_8)
            }
            var slice = 0L
            val count = be32(4)
            if (be32(0) == 0xcafebabeL && count != null) {
                val entry = (0 until count).firstOrNull { be32(8 + it * 20) == 0x0100000cL } ?: return null
                slice = be32(8 + entry * 20 + 8) ?: return null
            }
            if (u32(slice) != 0xfeedfacfL) return null
            val commandCount = u32(slice + 16) ?: return null
            val segments = mutableListOf<LongArray>()
            var table: Long? = null
            var command = slice + 32
            for (index in 0 until commandCount) {
                val kind = u32(command) ?: return null
                val commandSize = u32(command + 4) ?: return null
                val address = u64(command + 24)
                val length = u64(command + 48)
                val offset = u64(command + 40)
                val sectionCount = u32(command + 64)
                if (kind == 0x19L && address != null && length != null && offset != null && sectionCount != null) {
                    segments.add(longArrayOf(address, length, offset))
                    for (sectionIndex in 0 until sectionCount) {
                        val section = command + 72 + sectionIndex * 80
                        if (section + 40 > size) return null
                        if (string(section + 16) == "__DATA_CONST" && string(section) == "__const") {
                            table = u64(section + 32)
                        }
                    }
                }
                command += commandSize
            }
            fun fileOffset(address: Long): Long? = segments.firstOrNull { address >= it[0] && address < it[0] + it[1] }?.let { slice + it[2] + address - it[0] }
            fun pointer(address: Long): Long? = fileOffset(address)?.let(::u64)?.and(0xffffffffL)
            var entry = table ?: return null
            while (true) {
                val id = fileOffset(entry)?.let(::u32) ?: return null
                val descriptor = pointer(entry + 8) ?: return null
                if (descriptor == 0L) return null
                entry += 16
                if (id != 100L) continue
                val base = fileOffset(descriptor) ?: return null
                val width = u64(base)?.let { Double.fromBits(it) } ?: return null
                val frames = u64(base + 0x20)?.toULong() ?: return null
                val imageCount = u64(base + 0x30)?.toULong() ?: return null
                if (frames > 1uL || imageCount >= 16uL) return null
                for (index in 0 until imageCount.toInt()) {
                    val image = pointer(descriptor + 0x38 + index * 8)?.let(::fileOffset) ?: return null
                    val pixelWidth = u64(image) ?: return null
                    val pixelHeight = u64(image + 8) ?: return null
                    if (pixelWidth != Math.round(width * SCALE) || pixelWidth <= 0 || pixelHeight <= 0 || pixelHeight >= 4096 ||
                        image + 0x20 + pixelWidth * pixelHeight * 4 > size
                    ) {
                        continue
                    }
                    val pixels = ByteArray((pixelWidth * pixelHeight * 4).toInt())
                    bytes.duplicate().apply { position((image + 0x20).toInt()) }.get(pixels)
                    for (pixel in pixels.indices step 4) {
                        val blue = pixels[pixel]
                        pixels[pixel] = pixels[pixel + 2]
                        pixels[pixel + 2] = blue
                    }
                    return Bitmap.createBitmap(pixelWidth.toInt(), pixelHeight.toInt(), Bitmap.Config.ARGB_8888).apply {
                        copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                    }
                }
                return null
            }
        }
    }
}

private object PropertyList {
    fun read(file: File): Any? = file.inputStream().use { stream ->
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        while (parser.next() != XmlPullParser.START_TAG) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) throw MessageError("${file.name} is not a property list")
        }
        if (parser.name != "plist") throw MessageError("${file.name} is not a property list")
        parser.nextTag()
        value(parser)
    }

    private fun value(parser: XmlPullParser): Any? = when (parser.name) {
        "dict" -> buildMap {
            while (parser.nextTag() == XmlPullParser.START_TAG) {
                val key = parser.nextText()
                parser.nextTag()
                put(key, value(parser))
            }
        }
        "array" -> buildList {
            while (parser.nextTag() == XmlPullParser.START_TAG) {
                add(value(parser))
            }
        }
        "string", "date" -> parser.nextText()
        "integer" -> parser.nextText().trim().toLong()
        "real" -> parser.nextText().trim().toDouble()
        "true" -> true.also { parser.nextTag() }
        "false" -> false.also { parser.nextTag() }
        "data" -> Base64.decode(parser.nextText(), Base64.DEFAULT)
        else -> null.also { skip(parser) }
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth += 1
                XmlPullParser.END_TAG -> depth -= 1
            }
        }
    }
}
