package io.nekohasekai.screensharing.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.model.SessionQuality
import io.nekohasekai.screensharing.session.ClipboardContent
import io.nekohasekai.screensharing.session.CursorShape
import io.nekohasekai.screensharing.session.DisplaySelection
import io.nekohasekai.screensharing.session.Framebuffer
import io.nekohasekai.screensharing.session.GesturePhase
import io.nekohasekai.screensharing.session.MacCursorAssets
import io.nekohasekai.screensharing.session.MacKey
import io.nekohasekai.screensharing.session.MacKeyboardType
import io.nekohasekai.screensharing.session.PointerButtons
import io.nekohasekai.screensharing.session.RemoteDisplay
import io.nekohasekai.screensharing.session.RemotePlatform
import io.nekohasekai.screensharing.session.RemoteSession
import io.nekohasekai.screensharing.session.RemoteTouch
import io.nekohasekai.screensharing.session.ServerDialect
import io.nekohasekai.screensharing.session.SessionCapabilities
import io.nekohasekai.screensharing.session.SessionChoice
import io.nekohasekai.screensharing.session.SessionChoicePrompt
import io.nekohasekai.screensharing.session.SessionEventSink
import io.nekohasekai.screensharing.session.SessionFailure
import io.nekohasekai.screensharing.session.SessionInfo
import io.nekohasekai.screensharing.session.SessionMode
import io.nekohasekai.screensharing.session.SessionPhase
import io.nekohasekai.screensharing.session.SessionStatistics
import io.nekohasekai.screensharing.session.StandardCursor
import io.nekohasekai.screensharing.session.SystemCommand
import io.nekohasekai.screensharing.session.VirtualDisplayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteOrder

class DemoRemoteSession(
    private val sink: SessionEventSink,
    private val loadFrame: (VirtualDisplayMode) -> ByteArray?,
) : RemoteSession {
    private val thread = HandlerThread("DemoRemoteSession").apply { start() }
    private val handler = Handler(thread.looper)
    private var info = SessionInfo(
        dialect = ServerDialect.Apple,
        platform = RemotePlatform.Mac,
        cursorPixelsPerPoint = CURSOR_PIXELS_PER_POINT.toDouble(),
    )
    private var requested: VirtualDisplayMode? = null
    private var loading: Job? = null

    override val displayWidthMultiple: Int get() = 1

    override fun connect() {
        handler.post {
            sink.sessionCapabilitiesChanged(CAPABILITIES)
            sink.sessionInfoChanged(info)
            sink.sessionCursorChanged(arrowCursor)
            sink.sessionPhaseChanged(SessionPhase.Connected)
        }
    }

    override fun stop() = abort()

    override fun abort() {
        handler.post {
            loading?.cancel()
            loading = null
            thread.quitSafely()
        }
    }

    override fun setVirtualDisplay(mode: VirtualDisplayMode, largestSide: Int) {
        handler.post {
            requested = mode
            loading?.cancel()
            loading = App.scope.launch(Dispatchers.IO) {
                val bytes = loadFrame(mode) ?: return@launch
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) {
                    decoded
                } else {
                    decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
                }
                handler.post {
                    show(bitmap, mode)
                    bitmap.recycle()
                }
            }
        }
    }

    override fun startHighPerformance() {}

    override fun startHighPerformanceWithConnection() {}

    private fun show(bitmap: Bitmap, mode: VirtualDisplayMode) {
        if (mode != requested) return
        val width = mode.pixelWidth
        val height = mode.pixelHeight
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val framebuffer = try {
            sink.sessionNeedsFramebuffer(width, height) ?: return
        } catch (error: Exception) {
            sink.sessionPhaseChanged(SessionPhase.Failed(SessionFailure(SessionFailure.Kind.DrawingFailed, error.message.orEmpty())))
            return
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        // uploadFramebuffer swizzles R<->B, so buffer stores 0xAARRGGBB in little-endian BGRA order.
        val intBuf = framebuffer.buffer.duplicate().apply {
            clear()
            order(ByteOrder.LITTLE_ENDIAN)
        }.asIntBuffer()
        intBuf.put(pixels)
        info = info.copy(
            framebufferWidth = width,
            framebufferHeight = height,
            framebufferPixelsPerCursorPixel = mode.density.toDouble(),
        )
        sink.sessionInfoChanged(info)
        sink.sessionDisplaysChanged(
            listOf(
                RemoteDisplay(
                    id = VIRTUAL_DISPLAY_ID,
                    name = App.instance.getString(R.string.bridge_virtual_display),
                    width = mode.width,
                    height = mode.height,
                    isMain = false,
                    isVirtual = true,
                ),
            ),
            DisplaySelection.Display(VIRTUAL_DISPLAY_ID),
        )
        sink.sessionFramebufferUpdated(Rect(0f, 0f, width.toFloat(), height.toFloat()), System.nanoTime())
    }

    override fun answerSessionChoice(prompt: SessionChoicePrompt, choice: SessionChoice) {}
    override fun sendPointer(x: Int, y: Int, buttons: PointerButtons, clickCount: Int) {}
    override fun sendScroll(x: Int, y: Int, deltaX: Double, deltaY: Double) {}
    override fun sendTouches(touches: List<RemoteTouch>) {}
    override fun sendKey(key: MacKey, keyboardType: MacKeyboardType, down: Boolean) {}
    override fun sendText(text: String) {}
    override fun pasteText(text: String) {}
    override fun selectKeyboardSource(id: String) {}
    override fun sendMagnify(x: Int, y: Int, magnification: Double, phase: GesturePhase) {}
    override fun sendRotate(x: Int, y: Int, degrees: Double, phase: GesturePhase) {}
    override fun sendScrollPhase(x: Int, y: Int, deltaX: Double, deltaY: Double, phase: GesturePhase) {}
    override fun startScrollMomentum(x: Int, y: Int, velocityX: Double, velocityY: Double) {}
    override fun stopScrollMomentum() {}
    override fun sendSystemCommand(command: SystemCommand) {}
    override fun setAssistCursor(x: Int, y: Int, pressed: Boolean) {}
    override fun hideAssistCursor() {}
    override fun setMode(mode: SessionMode) {}
    override fun selectDisplay(selection: DisplaySelection) {}
    override fun setQuality(quality: SessionQuality) {}
    override fun uploadFile(localFile: File, remoteFolder: String): Long = throw UnsupportedOperationException()
    override fun downloadFile(remotePath: String, localFolder: File): Long = throw UnsupportedOperationException()
    override fun downloadFiles(remotePaths: List<String>, localFolder: File) {}
    override fun setCursorAssets(assets: MacCursorAssets?) {}
    override fun startDrop(names: List<String>): Long = throw UnsupportedOperationException()
    override fun cancelDrag(id: Long) {}
    override fun cancelRemoteDrag() {}
    override fun cancelTransfer(id: Long) {}
    override fun setReceivesAudio(receivesAudio: Boolean) {}
    override fun sendMicrophoneAudio(data: ByteArray) {}
    override fun requestRefresh() {}
    override fun requestKeyframe() {}
    override fun statistics(): SessionStatistics? = null
    override fun setVisible(visible: Boolean) {}
    override fun setKeepsAwake(enabled: Boolean) {}
    override fun setHDR(enabled: Boolean) {}
    override fun setUpdateRate(rate: Int) {}
    override fun setClipboardSharing(enabled: Boolean) {}
    override fun sendClipboard(content: ClipboardContent, pasting: Boolean) {}
    override fun requestClipboard() {}

    companion object {
        private const val VIRTUAL_DISPLAY_ID = 2L
        private const val CURSOR_PIXELS_PER_POINT = 2

        private val CAPABILITIES =
            SessionCapabilities.Control + SessionCapabilities.HighPerformance + SessionCapabilities.Clipboard +
                SessionCapabilities.ClipboardRequest + SessionCapabilities.DisplaySelection + SessionCapabilities.VirtualDisplay +
                SessionCapabilities.FileTransfer + SessionCapabilities.KeyboardSource + SessionCapabilities.KeyCodes +
                SessionCapabilities.Gestures + SessionCapabilities.AssistCursor + SessionCapabilities.MissionControl +
                SessionCapabilities.AppWindows + SessionCapabilities.ShowDesktop + SessionCapabilities.Launchpad +
                SessionCapabilities.DragToRemote + SessionCapabilities.DragFromRemote + SessionCapabilities.Quality +
                SessionCapabilities.Observe + SessionCapabilities.Touch

        private val arrowCursor: CursorShape by lazy {
            val scale = CURSOR_PIXELS_PER_POINT.toFloat()
            val width = (14 * scale).toInt()
            val height = (22 * scale).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val path = Path().apply {
                moveTo(1f * scale, 1f * scale)
                lineTo(1f * scale, 17f * scale)
                lineTo(5f * scale, 13f * scale)
                lineTo(8f * scale, 20f * scale)
                lineTo(10f * scale, 19f * scale)
                lineTo(7f * scale, 12f * scale)
                lineTo(12f * scale, 12f * scale)
                close()
            }
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.FILL
                },
            )
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 1f * scale
                    strokeJoin = Paint.Join.ROUND
                },
            )
            CursorShape(bitmap, Offset(scale, scale), StandardCursor.Arrow)
        }
    }
}
