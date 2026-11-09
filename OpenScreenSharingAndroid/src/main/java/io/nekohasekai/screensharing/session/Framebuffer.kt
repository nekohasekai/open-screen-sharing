package io.nekohasekai.screensharing.session

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.SharedMemory
import androidx.compose.ui.geometry.Rect
import io.nekohasekai.screensharing.app.MessageError
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Framebuffer(val width: Int, val height: Int) {
    init {
        if (width <= 0 || height <= 0 || width.toLong() * 4 * height > Int.MAX_VALUE) throw MessageError("framebuffer size ${width}x$height")
    }

    val bytesPerRow = width * 4
    val buffer: ByteBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        SharedMemory.create(null, bytesPerRow * height).use { it.mapReadWrite() }
    } else {
        ByteBuffer.allocateDirect(bytesPerRow * height)
    }
    val baseAddress: Long = NativeMemory.address(buffer)
    private val references = AtomicInteger(1)

    init {
        if (baseAddress == 0L) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) SharedMemory.unmap(buffer)
            throw MessageError("GetDirectBufferAddress returned no address")
        }
    }

    fun retain(): Framebuffer {
        references.incrementAndGet()
        return this
    }

    fun release() {
        if (references.decrementAndGet() == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) SharedMemory.unmap(buffer)
    }

    val bounds: Rect get() = Rect(0f, 0f, width.toFloat(), height.toFloat())

    fun copy(rect: Rect, source: Framebuffer) {
        val area = rect.intersect(bounds).intersect(source.bounds)
        if (area.isEmpty) return
        val offset = area.left.toInt() * 4
        val length = area.width.toInt() * 4
        val from = source.buffer.duplicate()
        val to = buffer.duplicate()
        for (row in area.top.toInt() until area.bottom.toInt()) {
            val start = row * source.bytesPerRow + offset
            from.limit(start + length)
            from.position(start)
            to.position(row * bytesPerRow + offset)
            to.put(from)
        }
    }

    internal fun bitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        copyPixelsFromBuffer(buffer.duplicate().also { it.clear() })
        setHasAlpha(false)
    }
}

object PixelBufferImage {
    private const val THUMBNAIL_WIDTH = 1280f
    private const val THUMBNAIL_QUALITY = 80

    private val swapsRedAndBlue = ColorMatrixColorFilter(
        floatArrayOf(
            0f, 0f, 1f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 255f,
        ),
    )

    fun thumbnailScale(width: Int, height: Int, turns: PictureTurns): Float = min(1f, THUMBNAIL_WIDTH / (if (turns.isSideways) height else width))

    fun makeThumbnail(image: Bitmap, turns: PictureTurns, bgra: Boolean = false): ByteArray? {
        if (image.width <= 0 || image.height <= 0) return null
        val scale = thumbnailScale(image.width, image.height, turns)
        val width = max(1, (image.width * scale).roundToInt())
        val height = max(1, (image.height * scale).roundToInt())
        var scaled = image
        var swaps = bgra
        while (true) {
            val halves = scaled.width / 2 >= width && scaled.height / 2 >= height
            val nextWidth = if (halves) scaled.width / 2 else width
            val nextHeight = if (halves) scaled.height / 2 else height
            if (!swaps && nextWidth == scaled.width && nextHeight == scaled.height) break
            val next = Bitmap.createBitmap(nextWidth, nextHeight, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            if (swaps) paint.colorFilter = swapsRedAndBlue
            Canvas(next).drawBitmap(scaled, null, android.graphics.Rect(0, 0, nextWidth, nextHeight), paint)
            if (scaled !== image) scaled.recycle()
            scaled = next
            swaps = false
            if (!halves) break
        }
        if (turns.count != 0) {
            val turned = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(turns.degrees.toFloat()) }, true)
            if (scaled !== image && turned !== scaled) scaled.recycle()
            scaled = turned
        }
        val stream = ByteArrayOutputStream()
        val encoded = scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, stream)
        if (scaled !== image) scaled.recycle()
        return if (encoded) stream.toByteArray() else null
    }
}
