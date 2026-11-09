package io.nekohasekai.screensharing.sessionui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.nekohasekai.screensharing.app.MessageError
import io.nekohasekai.screensharing.session.CursorShape
import io.nekohasekai.screensharing.session.DisplayPipeline
import io.nekohasekai.screensharing.session.Framebuffer
import io.nekohasekai.screensharing.session.Gl
import io.nekohasekai.screensharing.session.PictureProgram
import io.nekohasekai.screensharing.session.QuadProgram
import io.nekohasekai.screensharing.session.ScaleMode
import io.nekohasekai.screensharing.session.SessionInfo
import io.nekohasekai.screensharing.session.SessionPhase
import io.nekohasekai.screensharing.session.Shaders
import io.nekohasekai.screensharing.session.VideoImage
import io.nekohasekai.screensharing.session.VideoPrimaries
import io.nekohasekai.screensharing.session.VideoTransfer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LoupeSpot(val source: Rect, val frame: Rect)

class DrawnCursor(val shape: CursorShape, val shownBySystem: Boolean)

interface RemoteScreen {
    val viewSize: Size
    val imageRect: Rect
    val displayScale: Float
    val isOccluded: Boolean

    fun drawnCursor(shape: CursorShape?, visible: Boolean, scale: Float): DrawnCursor?

    fun cursorDidRender(rect: Rect?)

    fun loupe(icon: Rect?): LoupeSpot?
}

interface RemoteScreenController {
    val pipeline: DisplayPipeline
    val imageSize: Size
    val pictureMatchesWindow: Boolean
    val resolutionScale: Double
    val info: SessionInfo
    val cursorPixelsPerPoint: Double
    val phase: SessionPhase
    val sceneInBackground: Boolean
    var scaleMode: ScaleMode
    val pointerPosition: Offset
    var displaySupportsHDR: Boolean
    var screen: RemoteScreenSurface?
    val presentationRun: Int?

    fun picturePresented(picture: Int, time: Long, run: Int)

    fun screenRefreshRateDidChange(rate: Int)

    fun drawingFailed(error: Throwable)
}

class ScreenRenderer(
    private val pipeline: DisplayPipeline,
    private val failure: (Throwable) -> Unit,
    private val hdrChanged: (Boolean) -> Unit,
) {
    class Frame(
        val snapshot: DisplayPipeline.Snapshot,
        val imageRect: Rect,
        val cursor: CursorShape?,
        val cursorRect: Rect?,
        val cursorShownBySystem: Boolean,
        val viewSize: Size,
        val pixelsPerImagePixel: Float,
        val pixelsPerCursorPixel: Float,
        val loupe: LoupeSpot?,
        val vsync: Long,
        val frameInterval: Long,
    )

    private class Request(val frame: Frame, val presented: (Long) -> Unit, val failed: () -> Unit)

    private data class OutputSpace(val transfer: VideoTransfer, val primaries: VideoPrimaries)

    private class Drawn(val texture: Int, val rect: Rect, val textureRect: FloatArray)

    private class CursorTexture(val image: Bitmap, val texture: Int, val mipmapped: Boolean)

    private class Resources {
        val picture = PictureProgram()
        val black = QuadProgram(Shaders.BLACK_FRAGMENT)
        val cursor = QuadProgram(Shaders.CURSOR_FRAGMENT)
        val maskedCursor = QuadProgram(Shaders.MASKED_CURSOR_FRAGMENT)
        val cursorInversion = QuadProgram(Shaders.CURSOR_INVERSION_FRAGMENT)
        val linearSampler = sampler(GLES30.GL_LINEAR, GLES30.GL_LINEAR)
        val nearestSampler = sampler(GLES30.GL_LINEAR, GLES30.GL_NEAREST)
        val mipmappedSampler = sampler(GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR)
        val vertexArray = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        var framebufferTexture = 0
        var textureWidth = 0
        var textureHeight = 0
        val cursorTextures = ArrayList<CursorTexture>()

        fun delete() {
            for (program in listOf(picture, black, cursor, maskedCursor, cursorInversion)) program.delete()
            GLES30.glDeleteSamplers(3, intArrayOf(linearSampler, nearestSampler, mipmappedSampler), 0)
            GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
            Gl.deleteTexture(framebufferTexture)
            for (cached in cursorTextures) Gl.deleteTexture(cached.texture)
            cursorTextures.clear()
        }

        private fun sampler(minification: Int, magnification: Int): Int {
            val names = IntArray(1)
            GLES30.glGenSamplers(1, names, 0)
            GLES30.glSamplerParameteri(names[0], GLES30.GL_TEXTURE_MIN_FILTER, minification)
            GLES30.glSamplerParameteri(names[0], GLES30.GL_TEXTURE_MAG_FILTER, magnification)
            GLES30.glSamplerParameteri(names[0], GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glSamplerParameteri(names[0], GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return names[0]
        }
    }

    private val thread = HandlerThread("screen-renderer", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queueLock = Any()
    private var queued: Request? = null
    private var released = false

    private var display = EGL14.EGL_NO_DISPLAY
    private var shareContext = EGL14.EGL_NO_CONTEXT
    private var setUpFailed = false
    private var sdrConfig: EGLConfig? = null
    private var hdrConfig: EGLConfig? = null
    private var supportsDisplayP3 = false
    private var supportsHdrMetadata = false
    private var config: EGLConfig? = null
    private var context = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var surfaceSpace: OutputSpace? = null
    private var surfaceWindow: Surface? = null
    private var window: Surface? = null
    private var windowWidth = 0
    private var windowHeight = 0
    private var resources: Resources? = null
    private val pending = ArrayDeque<Pair<Long, DisplayPipeline.Snapshot>>()
    private var showsHDR = false

    init {
        handler.post(::setUp)
    }

    private fun setUp() {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw Gl.eglFailure("eglGetDisplay")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw Gl.eglFailure("eglInitialize")
            val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty().split(' ').toSet()
            sdrConfig = Gl.chooseConfig(display, 8, 8, 8, 8, EGL14.EGL_WINDOW_BIT)
                ?: throw MessageError("this device has no EGL window configuration for OpenGL ES 3")
            val colorSpaces = "EGL_KHR_gl_colorspace" in extensions
            if (colorSpaces && "EGL_EXT_gl_colorspace_bt2020_pq" in extensions) {
                hdrConfig = Gl.chooseConfig(display, 10, 10, 10, 2, EGL14.EGL_WINDOW_BIT)
            }
            supportsDisplayP3 = colorSpaces && "EGL_EXT_gl_colorspace_display_p3_passthrough" in extensions
            supportsHdrMetadata = "EGL_EXT_surface_SMPTE2086_metadata" in extensions
            val videoContext = pipeline.videoContext
            shareContext = videoContext.shareContext()
            videoContext.failure?.let { throw it }
        } catch (e: Throwable) {
            setUpFailed = true
            mainHandler.post { failure(e) }
        }
    }

    fun setSurface(surface: Surface?, width: Int, height: Int) {
        if (surface != null) {
            handler.post {
                if (window !== surface) destroySurface()
                window = surface
                windowWidth = width
                windowHeight = height
            }
            return
        }
        val done = CountDownLatch(1)
        val posted = handler.post {
            destroySurface()
            window = null
            done.countDown()
        }
        if (posted) done.await(SURFACE_TIMEOUT, TimeUnit.MILLISECONDS)
    }

    fun render(frame: Frame, presented: (time: Long) -> Unit, failed: () -> Unit): Boolean {
        val previous: Request?
        synchronized(queueLock) {
            if (released) {
                frame.snapshot.release()
                return false
            }
            previous = queued
            queued = Request(frame, presented, failed)
        }
        if (previous != null) {
            previous.frame.snapshot.release()
        } else {
            handler.post(::drawQueued)
        }
        return true
    }

    fun release() {
        val request = synchronized(queueLock) {
            released = true
            queued.also { queued = null }
        }
        request?.frame?.snapshot?.release()
        handler.post {
            destroyContext()
            destroySurface()
            window = null
        }
        thread.quitSafely()
    }

    private fun drawQueued() {
        val request = synchronized(queueLock) { queued.also { queued = null } } ?: return
        val drawn = try {
            draw(request)
        } catch (e: Exception) {
            Log.w(TAG, "draw a frame", e)
            false
        }
        when (drawn) {
            true -> Unit
            false -> {
                request.frame.snapshot.release()
                mainHandler.post { request.failed() }
            }
            null -> request.frame.snapshot.release()
        }
    }

    private fun draw(request: Request): Boolean? {
        val frame = request.frame
        val snapshot = frame.snapshot
        val window = window ?: return null
        if (setUpFailed || frame.viewSize.width <= 0f || frame.viewSize.height <= 0f) return null
        val content = contentSpace(snapshot)
        val output = when {
            content.transfer != VideoTransfer.Srgb && hdrConfig != null -> OutputSpace(VideoTransfer.Pq, VideoPrimaries.Bt2020)
            content.primaries == VideoPrimaries.DisplayP3 && supportsDisplayP3 -> OutputSpace(VideoTransfer.Srgb, VideoPrimaries.DisplayP3)
            else -> OutputSpace(VideoTransfer.Srgb, VideoPrimaries.Srgb)
        }
        val config = (if (output.transfer == VideoTransfer.Pq) hdrConfig else sdrConfig) ?: return false
        if (!prepare(config, output, window)) return false
        val resources = resources ?: Resources().also { resources = it }
        releaseCompleted()

        val images = ArrayList<Drawn>()
        val framebuffer = snapshot.framebuffer
        val pixelBuffer = snapshot.pixelBuffer
        if (framebuffer != null) {
            images.add(Drawn(uploadFramebuffer(resources, framebuffer), frame.imageRect, QuadProgram.unitRect))
        } else if (pixelBuffer != null) {
            waitFor(pixelBuffer)
            images.add(Drawn(pixelBuffer.texture, frame.imageRect, QuadProgram.unitRect))
        } else if (snapshot.strips.isNotEmpty()) {
            if (snapshot.stripHeight <= 0 || snapshot.canvasHeight <= 0 || snapshot.canvasHeight > snapshot.stripHeight * snapshot.strips.size) return false
            val canvasHeight = snapshot.canvasHeight.toFloat()
            val stripHeight = snapshot.stripHeight.toFloat()
            val imageRect = frame.imageRect
            for ((band, strip) in snapshot.strips.withIndex()) {
                strip ?: return false
                val top = band * stripHeight
                val visible = min(stripHeight, canvasHeight - top)
                if (visible <= 0f) continue
                waitFor(strip)
                val unit = snapshot.turns.shownRect(Rect(0f, top / canvasHeight, 1f, (top + visible) / canvasHeight))
                val rect = Rect(
                    imageRect.left + imageRect.width * unit.left,
                    imageRect.top + imageRect.height * unit.top,
                    imageRect.left + imageRect.width * unit.right,
                    imageRect.top + imageRect.height * unit.bottom,
                )
                images.add(Drawn(strip.texture, rect, floatArrayOf(0f, 0f, 1f, visible / stripHeight)))
            }
        }

        val cursor = frame.cursor
        val cursorRect = frame.cursorRect
        var cursorTexture = 0
        var inversionTexture = 0
        var cursorSampler = 0
        if (cursor != null && cursorRect != null && (!frame.cursorShownBySystem || frame.loupe != null)) {
            val mipmapped = cursor.scale > 1
            cursorTexture = texture(resources, cursor.image, mipmapped) ?: return false
            cursor.inversion?.let { inversionTexture = texture(resources, it, false) ?: return false }
            cursorSampler = if (mipmapped) resources.mipmappedSampler else sampler(resources, frame.pixelsPerCursorPixel)
        }

        GLES30.glViewport(0, 0, windowWidth, windowHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindVertexArray(resources.vertexArray)
        resources.picture.setConversion(content.transfer, content.primaries, output.transfer, output.primaries)
        val pictureSampler = sampler(resources, frame.pixelsPerImagePixel)

        fun drawContent(dx: Float, dy: Float, drawsCursor: Boolean) {
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindSampler(0, pictureSampler)
            for (image in images) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, image.texture)
                resources.picture.draw(Gl.ndc(image.rect.translate(dx, dy), frame.viewSize), image.textureRect, snapshot.turns.count)
            }
            if (!drawsCursor || cursor == null || cursorRect == null || cursorTexture == 0) return
            val rect = Gl.ndc(cursorRect.translate(dx, dy), frame.viewSize)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cursorTexture)
            GLES30.glBindSampler(0, cursorSampler)
            if (inversionTexture != 0 && output.transfer == VideoTransfer.Srgb) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inversionTexture)
                GLES30.glBindSampler(1, cursorSampler)
                blendPremultiplied()
                resources.maskedCursor.draw(rect)
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFuncSeparate(GLES30.GL_ONE_MINUS_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_COLOR, GLES30.GL_ZERO, GLES30.GL_ONE)
                resources.cursorInversion.draw(rect)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            } else {
                blendPremultiplied()
                resources.cursor.use()
                GLES30.glUniform1i(resources.cursor.location("outputTransfer"), output.transfer.ordinal)
                GLES30.glUniformMatrix3fv(resources.cursor.location("primaries"), 1, false, VideoPrimaries.Srgb.conversion(output.primaries), 0)
                resources.cursor.draw(rect)
            }
        }

        drawContent(0f, 0f, drawsCursor = !frame.cursorShownBySystem)
        frame.loupe?.let { loupe ->
            val scale = windowWidth / frame.viewSize.width
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(
                (loupe.frame.left * scale).roundToInt(),
                windowHeight - (loupe.frame.bottom * scale).roundToInt(),
                (loupe.frame.width * scale).roundToInt(),
                (loupe.frame.height * scale).roundToInt(),
            )
            GLES30.glDisable(GLES30.GL_BLEND)
            resources.black.draw(Gl.ndc(loupe.frame, frame.viewSize))
            drawContent(loupe.frame.left - loupe.source.left, loupe.frame.top - loupe.source.top, drawsCursor = true)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        }
        GLES30.glBindVertexArray(0)

        if (!EGL14.eglSwapBuffers(display, eglSurface)) {
            when (EGL14.eglGetError()) {
                EGL14.EGL_BAD_SURFACE, EGL14.EGL_BAD_NATIVE_WINDOW -> destroySurface()
                EGL14.EGL_CONTEXT_LOST -> destroyContext()
            }
            return false
        }
        pending.addLast(GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0) to snapshot)
        if (images.isNotEmpty()) {
            request.presented(presentationTime(frame))
        }
        return true
    }

    private fun contentSpace(snapshot: DisplayPipeline.Snapshot): OutputSpace {
        if (snapshot.framebuffer != null) return OutputSpace(VideoTransfer.Srgb, VideoPrimaries.Srgb)
        val image = snapshot.pixelBuffer ?: snapshot.strips.firstNotNullOfOrNull { it } ?: return OutputSpace(VideoTransfer.Srgb, VideoPrimaries.Srgb)
        return OutputSpace(image.transfer, image.primaries)
    }

    private fun presentationTime(frame: Frame): Long {
        val now = System.nanoTime()
        val interval = frame.frameInterval
        if (interval <= 0 || frame.vsync <= 0) return now
        val latched = frame.vsync + max(0L, (now - frame.vsync + interval - 1) / interval) * interval
        return latched + interval
    }

    private fun prepare(config: EGLConfig, output: OutputSpace, window: Surface): Boolean {
        if (context == EGL14.EGL_NO_CONTEXT || this.config !== config) {
            destroyContext()
            destroySurface()
            context = EGL14.eglCreateContext(display, config, shareContext, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                Log.w(TAG, Gl.eglFailure("eglCreateContext").message.orEmpty())
                return false
            }
            this.config = config
        }
        if (eglSurface == EGL14.EGL_NO_SURFACE || surfaceSpace != output || surfaceWindow !== window) {
            destroySurface()
            val attributes = when {
                output.transfer == VideoTransfer.Pq -> intArrayOf(EGL15.EGL_GL_COLORSPACE, EGL_GL_COLORSPACE_BT2020_PQ, EGL14.EGL_NONE)
                output.primaries == VideoPrimaries.DisplayP3 -> intArrayOf(EGL15.EGL_GL_COLORSPACE, EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH, EGL14.EGL_NONE)
                else -> intArrayOf(EGL14.EGL_NONE)
            }
            eglSurface = EGL14.eglCreateWindowSurface(display, config, window, attributes, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, Gl.eglFailure("eglCreateWindowSurface").message.orEmpty())
                return false
            }
            if (output.transfer == VideoTransfer.Pq && supportsHdrMetadata) {
                EGL14.eglSurfaceAttrib(display, eglSurface, EGL_SMPTE2086_MAX_LUMINANCE, (HDR_MAX_LUMINANCE * EGL_METADATA_SCALING).toInt())
                EGL14.eglSurfaceAttrib(display, eglSurface, EGL_SMPTE2086_MIN_LUMINANCE, (HDR_MIN_LUMINANCE * EGL_METADATA_SCALING).toInt())
            }
            surfaceSpace = output
            surfaceWindow = window
            val hdr = output.transfer == VideoTransfer.Pq
            if (hdr != showsHDR) {
                showsHDR = hdr
                mainHandler.post { hdrChanged(hdr) }
            }
        }
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            Log.w(TAG, Gl.eglFailure("eglMakeCurrent").message.orEmpty())
            return false
        }
        return true
    }

    private fun destroySurface() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) return
        if (context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
        EGL14.eglDestroySurface(display, eglSurface)
        eglSurface = EGL14.EGL_NO_SURFACE
        surfaceSpace = null
        surfaceWindow = null
    }

    private fun destroyContext() {
        if (context == EGL14.EGL_NO_CONTEXT) {
            releaseCompleted(force = true, current = false)
            return
        }
        val current = EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        if (current) {
            resources?.delete()
            GLES30.glFinish()
        }
        resources = null
        releaseCompleted(force = true, current = current)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }

    private fun releaseCompleted(force: Boolean = false, current: Boolean = true) {
        while (pending.isNotEmpty()) {
            val (fence, snapshot) = pending.first()
            if (!force && fence != 0L) {
                val status = GLES30.glClientWaitSync(fence, 0, 0)
                if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) {
                    if (pending.size <= MAX_PENDING_FRAMES) return
                    GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, PENDING_WAIT)
                }
            }
            if (fence != 0L && current) GLES30.glDeleteSync(fence)
            snapshot.release()
            pending.removeFirst()
        }
    }

    private fun waitFor(image: VideoImage) {
        if (image.fence != 0L) GLES30.glWaitSync(image.fence, 0, GLES30.GL_TIMEOUT_IGNORED)
    }

    private fun sampler(resources: Resources, pixelsPerTexel: Float): Int = if (pixelsPerTexel >= 2 || abs(pixelsPerTexel - 1) < 0.001f) resources.nearestSampler else resources.linearSampler

    private fun blendPremultiplied() {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun uploadFramebuffer(resources: Resources, framebuffer: Framebuffer): Int {
        val current = resources.framebufferTexture != 0 && resources.textureWidth == framebuffer.width && resources.textureHeight == framebuffer.height
        if (!current) {
            Gl.deleteTexture(resources.framebufferTexture)
            resources.framebufferTexture = Gl.texture(framebuffer.width, framebuffer.height, GLES30.GL_RGBA8)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_SWIZZLE_R, GLES30.GL_BLUE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_SWIZZLE_B, GLES30.GL_RED)
            resources.textureWidth = framebuffer.width
            resources.textureHeight = framebuffer.height
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, resources.framebufferTexture)
        pipeline.uploadFramebuffer(framebuffer, current) { region ->
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, framebuffer.bytesPerRow / 4)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, region.left.toInt())
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, region.top.toInt())
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, region.left.toInt(), region.top.toInt(), region.width.toInt(), region.height.toInt(),
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, framebuffer.buffer.duplicate().also { it.clear() },
            )
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
        }
        return resources.framebufferTexture
    }

    private fun texture(resources: Resources, image: Bitmap, mipmapped: Boolean): Int? {
        resources.cursorTextures.firstOrNull { it.image === image && (!mipmapped || it.mipmapped) }?.let { return it.texture }
        val source = when {
            image.config == Bitmap.Config.ALPHA_8 -> image
            image.config == Bitmap.Config.ARGB_8888 && image.isPremultiplied -> image
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && image.config == Bitmap.Config.HARDWARE -> image.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            else -> Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(image, 0f, 0f, null) }
        }
        val names = IntArray(1)
        GLES30.glGenTextures(1, names, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, names[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, source, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        if (source.config == Bitmap.Config.ALPHA_8) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_SWIZZLE_R, GLES30.GL_ALPHA)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, if (mipmapped) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        if (mipmapped) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        if (source !== image) source.recycle()
        while (resources.cursorTextures.size >= MAX_CURSOR_TEXTURES) {
            Gl.deleteTexture(resources.cursorTextures.removeAt(0).texture)
        }
        resources.cursorTextures.add(CursorTexture(image, names[0], mipmapped))
        return names[0]
    }

    private companion object {
        const val TAG = "ScreenRenderer"
        const val SURFACE_TIMEOUT = 1_000L
        const val MAX_PENDING_FRAMES = 4
        const val PENDING_WAIT = 50_000_000L
        const val MAX_CURSOR_TEXTURES = 2
        const val EGL_GL_COLORSPACE_BT2020_PQ = 0x3340
        const val EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH = 0x3490
        const val EGL_SMPTE2086_MAX_LUMINANCE = 0x3349
        const val EGL_SMPTE2086_MIN_LUMINANCE = 0x334A
        const val EGL_METADATA_SCALING = 50000.0
        const val HDR_MAX_LUMINANCE = 1000.0
        const val HDR_MIN_LUMINANCE = 0.0005
    }
}

class ScreenPresenter(
    private val screen: RemoteScreen,
    private val controller: RemoteScreenController,
    private val density: Float,
) {
    private val pipeline = controller.pipeline
    private val choreographer = Choreographer.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var renderer: ScreenRenderer? = null
    private var linked = false
    private var linkPaused = false
    private var callbackPosted = false
    private var vsync = 0L
    private var frameInterval = DEFAULT_FRAME_INTERVAL
    private var presentationTick = 0L
    private var needsRender = true
    private var renderedThisFrame = false
    private var idleFrames = 0
    private var lastSnapshot: DisplayPipeline.Snapshot? = null
    private var refreshRate = 0
    private val frameCallback = Choreographer.FrameCallback { tick(it) }
    private val presentationCallback = Runnable { presentScheduledFrames() }

    fun start(renderer: ScreenRenderer) {
        this.renderer = renderer
        linked = true
        linkPaused = false
        refreshRate = 0
        needsRender = true
        pipeline.setWakeHandler { mainHandler.post { contentChanged() } }
        postFrame()
    }

    fun stop() {
        linked = false
        linkPaused = false
        choreographer.removeFrameCallback(frameCallback)
        callbackPosted = false
        mainHandler.removeCallbacks(presentationCallback)
        pipeline.setWakeHandler(null)
        lastSnapshot?.release()
        lastSnapshot = null
        renderer = null
    }

    fun setRefreshRate(rate: Int) {
        if (rate <= 0 || rate == refreshRate) return
        refreshRate = rate
        controller.screenRefreshRateDidChange(rate)
    }

    fun setFrameInterval(interval: Long) {
        if (interval > 0) frameInterval = interval
    }

    fun pause() {
        linkPaused = true
    }

    fun setNeedsRender() {
        needsRender = true
        wake()
    }

    private fun postFrame() {
        if (callbackPosted || !linked) return
        callbackPosted = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun tick(frameTime: Long) {
        callbackPosted = false
        if (!linked || linkPaused) return
        vsync = frameTime
        postFrame()
        renderedThisFrame = false
        if (screen.isOccluded) {
            linkPaused = true
            return
        }
        if (pipeline.isSchedulingPresentation) {
            presentationTick = frameTime + frameInterval
            val midpoint = frameTime + frameInterval / 2
            val delay = max(midpoint - System.nanoTime(), 0L)
            mainHandler.removeCallbacks(presentationCallback)
            mainHandler.postAtTime(presentationCallback, SystemClock.uptimeMillis() + (delay + 999_999) / 1_000_000)
            return
        }
        val snapshot = pipeline.consume(frameTime)
        if (snapshot != null) replaceLastSnapshot(snapshot)
        if (snapshot == null && !needsRender) {
            idleFrames += 1
            if (idleFrames > 30) linkPaused = true
            return
        }
        idleFrames = 0
        renderedThisFrame = true
        needsRender = !render()
    }

    private fun presentScheduledFrames() {
        if (!linked || screen.isOccluded) return
        val snapshot = pipeline.consume(presentationTick)
        if (snapshot != null) replaceLastSnapshot(snapshot)
        if (snapshot == null && !needsRender) {
            idleFrames += 1
            if (idleFrames > 30) linkPaused = true
            return
        }
        idleFrames = 0
        renderedThisFrame = true
        needsRender = !render()
    }

    private fun contentChanged() {
        if (!linked) return
        wake()
        if (pipeline.isSchedulingPresentation || renderedThisFrame || screen.isOccluded) return
        val snapshot = pipeline.consume(System.nanoTime()) ?: return
        replaceLastSnapshot(snapshot)
        renderedThisFrame = true
        needsRender = !render()
    }

    private fun wake() {
        if (screen.isOccluded) return
        idleFrames = 0
        linkPaused = false
        postFrame()
    }

    private fun replaceLastSnapshot(snapshot: DisplayPipeline.Snapshot) {
        lastSnapshot?.release()
        lastSnapshot = snapshot
    }

    private fun render(): Boolean {
        val renderer = renderer ?: return false
        val viewSize = screen.viewSize
        if (viewSize.width <= 0f || viewSize.height <= 0f) return false
        val snapshot = lastSnapshot ?: pipeline.snapshot().also { lastSnapshot = it }
        val epoch = snapshot.presentationEpoch
        val generation = snapshot.pictureGeneration
        val run = controller.presentationRun
        if (snapshot.turns.shownSize(Size(snapshot.width.toFloat(), snapshot.height.toFloat())) != controller.imageSize ||
            !pipeline.isCurrentPresentation(epoch)
        ) {
            return false
        }
        val imageRect = screen.imageRect
        val scale = screen.displayScale
        val resolutionScale = if (controller.pictureMatchesWindow) controller.resolutionScale else 1.0
        val cursorScale = max(
            scale * controller.info.framebufferPixelsPerCursorPixel.toFloat(),
            (density / (controller.cursorPixelsPerPoint * resolutionScale)).toFloat(),
        )
        val cursor = screen.drawnCursor(snapshot.cursor, snapshot.cursorVisible, cursorScale)
        var cursorRect: Rect? = null
        if (cursor != null && snapshot.cursorVisible && controller.phase == SessionPhase.Connected) {
            val shape = cursor.shape
            val left = imageRect.left + snapshot.cursorPosition.x * scale - shape.hotspot.x * cursorScale
            val top = imageRect.top + snapshot.cursorPosition.y * scale - shape.hotspot.y * cursorScale
            cursorRect = Rect(left, top, left + shape.image.width / shape.scale * cursorScale, top + shape.image.height / shape.scale * cursorScale)
        }
        screen.cursorDidRender(if (cursor?.shownBySystem == false) cursorRect else null)
        val loupe = screen.loupe(cursorRect)
        return renderer.render(
            ScreenRenderer.Frame(
                snapshot = snapshot.retain(),
                imageRect = imageRect,
                cursor = cursor?.shape,
                cursorRect = cursorRect,
                cursorShownBySystem = cursor?.shownBySystem ?: false,
                viewSize = viewSize,
                pixelsPerImagePixel = scale,
                pixelsPerCursorPixel = cursorScale,
                loupe = loupe,
                vsync = vsync,
                frameInterval = frameInterval,
            ),
            presented = { time ->
                mainHandler.post {
                    if (generation != null && run != null && pipeline.isCurrentPresentation(epoch)) {
                        controller.picturePresented(generation, time, run)
                    }
                }
            },
            failed = {
                if (pipeline.isCurrentPresentation(epoch)) setNeedsRender()
            },
        )
    }

    private companion object {
        const val DEFAULT_FRAME_INTERVAL = 16_666_667L
    }
}
