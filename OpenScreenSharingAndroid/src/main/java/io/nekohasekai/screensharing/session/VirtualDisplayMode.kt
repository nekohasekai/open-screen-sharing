package io.nekohasekai.screensharing.session

import androidx.compose.ui.geometry.Size
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class VirtualDisplayMode(val width: Int, val height: Int, val density: Int) {
    val pixelWidth: Int get() = width * density
    val pixelHeight: Int get() = height * density

    fun aligned(widthMultiple: Int): VirtualDisplayMode {
        var mode = this
        while (mode.pixelWidth % widthMultiple != 0) {
            mode = mode.copy(width = mode.width - 1)
        }
        return mode
    }

    companion object {
        const val MINIMUM_LONG_SIDE = 800f
        const val MINIMUM_SHORT_SIDE = 525f

        val minimumWindowPoints = Size(MINIMUM_LONG_SIDE * 0.75f, MINIMUM_SHORT_SIDE * 0.75f)

        val resolutionScaleRange = 1.0..2.0
        const val RESOLUTION_SCALE_STEP = 0.25
        val resolutionScales: List<Double> = generateSequence(resolutionScaleRange.start) { it + RESOLUTION_SCALE_STEP }
            .takeWhile { it <= resolutionScaleRange.endInclusive }
            .toList()
        const val DEFAULT_RESOLUTION_SCALE = 1.0

        fun matching(area: Size, screenScale: Float, resolutionScale: Float = 1f): VirtualDisplayMode? {
            if (area.width < 1 || area.height < 1) return null
            // ScreenSharing's -[SSSessionView setDynamicSize:] requests two pixels per point (ScreenSharing 776.27,
            // macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50, -[SSSessionView setDynamicSize:]
            // 0x1cc0a3788..0x1cc0a37f0).
            val density = if (screenScale >= 2) 2 else 1
            val points = Size(area.width * resolutionScale, area.height * resolutionScale)
            val grow = max(
                1f,
                max(MINIMUM_LONG_SIDE / max(points.width, points.height), MINIMUM_SHORT_SIDE / min(points.width, points.height)),
            )
            return VirtualDisplayMode(
                width = floor(points.width * grow + 0.001f).toInt(),
                height = floor(points.height * grow + 0.001f).toInt(),
                density = density,
            )
        }

        fun largestSide(screen: Size, screenScale: Float): Int {
            val mode = matching(screen, screenScale, resolutionScaleRange.endInclusive.toFloat()) ?: return 0
            return max(mode.pixelWidth, mode.pixelHeight)
        }
    }
}

class VirtualDisplayRequests {
    private var send: ((VirtualDisplayMode) -> Unit)? = null
    private var widthMultiple = 1
    private var sizedByConnection = false
    private var framebufferSize: Size? = null
    private var sent: VirtualDisplayMode? = null
    private var awaitsAnswer = false
    var isOpening = false
        private set
    var onIdle: (() -> Unit)? = null

    val isIdle: Boolean get() = !awaitsAnswer && !isOpening

    fun reset(widthMultiple: Int = 1, sizedByConnection: Boolean = false, sender: ((VirtualDisplayMode) -> Unit)?) {
        send = sender
        this.widthMultiple = widthMultiple
        this.sizedByConnection = sizedByConnection
        framebufferSize = null
        sent = null
        awaitsAnswer = false
        isOpening = false
    }

    fun open() {
        isOpening = true
    }

    fun connectionDidOpen(offersVirtualDisplay: Boolean) {
        if (!isOpening) return
        isOpening = false
        val sent = sent
        if (sizedByConnection && sent != null && framebufferSize == Size(sent.pixelWidth.toFloat(), sent.pixelHeight.toFloat())) {
            answered()
        } else if (!offersVirtualDisplay) {
            awaitsAnswer = false
            this.sent = null
            reportIdle()
        } else {
            reportIdle()
        }
    }

    fun request(requested: VirtualDisplayMode) {
        val mode = requested.aligned(widthMultiple)
        val send = send
        if (mode == sent || send == null) return
        sent = mode
        awaitsAnswer = true
        send(mode)
    }

    fun framebufferDidChange(width: Int, height: Int, resized: Boolean) {
        val size = Size(width.toFloat(), height.toFloat())
        val previous = framebufferSize
        if (resized) {
            framebufferSize = size
        }
        val sent = sent
        if (!awaitsAnswer || sent == null) return
        if ((sent.pixelWidth == width && sent.pixelHeight == height) || (resized && previous != null && previous != size)) {
            answered()
        }
    }

    fun displaysDidChange(displays: List<RemoteDisplay>, framebufferWidth: Int, framebufferHeight: Int) {
        val sent = sent
        if (!awaitsAnswer || sent == null || sent.pixelWidth != framebufferWidth || sent.pixelHeight != framebufferHeight) return
        if (displays.none { it.isVirtual && it.width == sent.width && it.height == sent.height }) return
        answered()
    }

    private fun answered() {
        awaitsAnswer = false
        reportIdle()
    }

    private fun reportIdle() {
        if (isIdle) onIdle?.invoke()
    }
}
