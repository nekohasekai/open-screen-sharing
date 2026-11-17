package io.nekohasekai.screensharing.session

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.nekohasekai.screensharing.app.MessageError
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

class VideoImage internal constructor(
    private val context: VideoContext,
    internal val texture: Int,
    val width: Int,
    val height: Int,
    val transfer: VideoTransfer,
    val primaries: VideoPrimaries,
    internal val fence: Long,
) {
    private val references = AtomicInteger(1)

    val isHDR: Boolean get() = transfer != VideoTransfer.Srgb

    fun retain(): VideoImage {
        references.incrementAndGet()
        return this
    }

    fun release() {
        if (references.decrementAndGet() == 0) context.recycle(this)
    }
}

class VideoContext {
    private class Spare(val texture: Int, val width: Int, val height: Int, val tenBit: Boolean)

    private val thread = HandlerThread("video", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    internal val handler = Handler(thread.looper)
    private val ready = CountDownLatch(1)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE

    @Volatile
    internal var failure: Throwable? = null
        private set
    private var outputName = 0
    private var outputTexture: SurfaceTexture? = null
    internal var surface: Surface? = null
        private set
    internal var frameAvailable: (() -> Unit)? = null
    private val transform = FloatArray(16)
    private var copyProgram = 0
    private var copyTransform = 0
    private var copyPosition = 0
    private var picture: PictureProgram? = null
    private var framebufferObject = 0
    private var vertexArray = 0
    private val spares = ArrayList<Spare>()
    private var closed = false
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    init {
        handler.post(::setUp)
    }

    private fun setUp() {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw Gl.eglFailure("eglGetDisplay")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw Gl.eglFailure("eglInitialize")
            val config = Gl.chooseConfig(display, 8, 8, 8, 8, EGL14.EGL_PBUFFER_BIT)
                ?: throw MessageError("this device has no EGL configuration for OpenGL ES 3")
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) throw Gl.eglFailure("eglCreateContext")
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (pbuffer == EGL14.EGL_NO_SURFACE) throw Gl.eglFailure("eglCreatePbufferSurface")
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) throw Gl.eglFailure("eglMakeCurrent")
            val names = IntArray(1)
            GLES30.glGenTextures(1, names, 0)
            outputName = names[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, outputName)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val texture = SurfaceTexture(outputName)
            texture.setOnFrameAvailableListener({ frameAvailable?.invoke() }, handler)
            outputTexture = texture
            surface = Surface(texture)
            copyProgram = Gl.program(Shaders.EXTERNAL_COPY_VERTEX, Shaders.EXTERNAL_COPY_FRAGMENT)
            copyTransform = GLES30.glGetUniformLocation(copyProgram, "transform")
            copyPosition = GLES30.glGetAttribLocation(copyProgram, "position")
            GLES30.glGenFramebuffers(1, names, 0)
            framebufferObject = names[0]
        } catch (e: Throwable) {
            failure = e
        }
        ready.countDown()
    }

    fun shareContext(): EGLContext {
        ready.await()
        return context
    }

    internal fun latch(): Long? {
        val texture = outputTexture ?: return null
        return try {
            texture.updateTexImage()
            texture.getTransformMatrix(transform)
            texture.timestamp
        } catch (e: RuntimeException) {
            null
        }
    }

    internal fun latchedDataSpace(): Int = if (android.os.Build.VERSION.SDK_INT >= 33) outputTexture?.dataSpace ?: 0 else 0

    internal fun copy(width: Int, height: Int, transfer: VideoTransfer, primaries: VideoPrimaries): VideoImage? {
        if (closed || context == EGL14.EGL_NO_CONTEXT || width <= 0 || height <= 0) return null
        val tenBit = transfer != VideoTransfer.Srgb
        val index = spares.indexOfFirst { it.width == width && it.height == height && it.tenBit == tenBit }
        val texture = if (index >= 0) {
            spares.removeAt(index).texture
        } else {
            Gl.texture(width, height, if (tenBit) GLES30.GL_RGB10_A2 else GLES30.GL_RGBA8)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferObject)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(copyProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, outputName)
        GLES30.glUniformMatrix4fv(copyTransform, 1, false, transform, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glVertexAttribPointer(copyPosition, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glEnableVertexAttribArray(copyPosition)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(copyPosition)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            if (fence != 0L) GLES30.glDeleteSync(fence)
            Gl.deleteTexture(texture)
            return null
        }
        return VideoImage(this, texture, width, height, transfer, primaries, fence)
    }

    internal fun recycle(image: VideoImage) {
        handler.post {
            if (closed) return@post
            if (image.fence != 0L) GLES30.glDeleteSync(image.fence)
            spares.add(Spare(image.texture, image.width, image.height, image.isHDR))
            while (spares.size > MAX_SPARES) {
                Gl.deleteTexture(spares.removeAt(0).texture)
            }
        }
    }

    internal suspend fun compose(pixelBuffer: VideoImage?, strips: List<VideoImage?>, stripHeight: Int, canvasHeight: Int): Bitmap? = suspendCancellableCoroutine { continuation ->
        val posted = handler.post {
            val composed = try {
                composed(pixelBuffer, strips, stripHeight, canvasHeight)
            } catch (e: Exception) {
                null
            }
            continuation.resume(composed)
        }
        if (!posted) continuation.resume(null)
    }

    private fun composed(pixelBuffer: VideoImage?, strips: List<VideoImage?>, stripHeight: Int, canvasHeight: Int): Bitmap? {
        if (closed || context == EGL14.EGL_NO_CONTEXT) return null
        val first = pixelBuffer ?: strips.firstNotNullOfOrNull { it } ?: return null
        val width = first.width
        val height = if (pixelBuffer != null) pixelBuffer.height else canvasHeight
        if (width <= 0 || height <= 0 || (pixelBuffer == null && stripHeight <= 0)) return null
        val program = picture ?: PictureProgram().also { picture = it }
        if (vertexArray == 0) {
            val names = IntArray(1)
            GLES30.glGenVertexArrays(1, names, 0)
            vertexArray = names[0]
        }
        val scale = PixelBufferImage.thumbnailScale(width)
        val thumbnailWidth = max(1, (width * scale).roundToInt())
        val thumbnailHeight = max(1, (height * scale).roundToInt())
        val canvas = Gl.texture(width, height, GLES30.GL_RGBA8, levels = 32 - Integer.numberOfLeadingZeros(max(width, height)))
        val thumbnail = Gl.texture(thumbnailWidth, thumbnailHeight, GLES30.GL_RGBA8)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferObject)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, canvas, 0)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glBindVertexArray(vertexArray)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            val size = Size(width.toFloat(), height.toFloat())
            program.setConversion(first.transfer, first.primaries, VideoTransfer.Srgb, VideoPrimaries.Srgb)
            fun draw(image: VideoImage, rect: Rect, textureRect: FloatArray) {
                if (image.fence != 0L) GLES30.glWaitSync(image.fence, 0, GLES30.GL_TIMEOUT_IGNORED)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, image.texture)
                program.draw(Gl.ndc(rect, size, flipped = true), textureRect)
            }
            if (pixelBuffer != null) {
                draw(pixelBuffer, Rect(0f, 0f, width.toFloat(), height.toFloat()), QuadProgram.unitRect)
            } else {
                for ((band, strip) in strips.withIndex()) {
                    strip ?: continue
                    val top = band * stripHeight
                    val visible = minOf(stripHeight, canvasHeight - top)
                    if (visible <= 0) continue
                    draw(strip, Rect(0f, top.toFloat(), width.toFloat(), (top + visible).toFloat()), floatArrayOf(0f, 0f, 1f, visible.toFloat() / stripHeight))
                }
            }
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, thumbnail, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, canvas)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glViewport(0, 0, thumbnailWidth, thumbnailHeight)
            program.setConversion(VideoTransfer.Srgb, VideoPrimaries.Srgb, VideoTransfer.Srgb, VideoPrimaries.Srgb)
            program.draw(floatArrayOf(-1f, -1f, 1f, 1f))
            val pixels = ByteBuffer.allocateDirect(thumbnailWidth * thumbnailHeight * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, thumbnailWidth, thumbnailHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glBindVertexArray(0)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return null
            pixels.rewind()
            return Bitmap.createBitmap(thumbnailWidth, thumbnailHeight, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) }
        } finally {
            Gl.deleteTexture(canvas)
            Gl.deleteTexture(thumbnail)
        }
    }

    fun close() {
        handler.post {
            closed = true
            frameAvailable = null
            surface?.release()
            surface = null
            outputTexture?.release()
            outputTexture = null
            if (context != EGL14.EGL_NO_CONTEXT) {
                for (spare in spares) Gl.deleteTexture(spare.texture)
                spares.clear()
                picture?.delete()
                if (copyProgram != 0) GLES30.glDeleteProgram(copyProgram)
                if (framebufferObject != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferObject), 0)
                if (vertexArray != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
                Gl.deleteTexture(outputName)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                context = EGL14.EGL_NO_CONTEXT
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val MAX_SPARES = 8
    }
}
