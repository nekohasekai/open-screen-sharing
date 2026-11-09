package io.nekohasekai.screensharing.sessionui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.core.content.ContextCompat
import io.nekohasekai.screensharing.session.CursorShape
import io.nekohasekai.screensharing.session.ScaleMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

open class RemoteScreenSurface(context: Context, private val controller: RemoteScreenController) :
    SurfaceView(context),
    RemoteScreen,
    SurfaceHolder.Callback {
    interface Delegate {
        fun drawnCursor(shape: CursorShape?, visible: Boolean, scale: Float): DrawnCursor? = shape?.let { DrawnCursor(it, shownBySystem = false) }

        fun cursorDidRender(rect: Rect?) {}

        fun loupe(icon: Rect?): LoupeSpot? = null

        fun geometryDidChange() {}
    }

    var delegate: Delegate = object : Delegate {}

    private val density = resources.displayMetrics.density
    private val presenter = ScreenPresenter(this, controller, density)
    private var renderer: ScreenRenderer? = null
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != display?.displayId) return
            controller.displaySupportsHDR = displaySupportsHDR()
            updateRefreshRate()
        }
    }
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            highestRefreshRate = 0f
            updateRefreshRate()
        }
    }
    private var requestedRefreshRate = 0f
    private var highestRefreshRate = 0f

    var zoom = 1f
        private set
    private var viewportCenter: Offset? = null
    private var keyboardLift = 0f

    var screenArea: Rect? = null
        set(value) {
            if (value == field) return
            field = value
            clampCenter()
            setNeedsRender()
        }

    var keyboardTop: Float? = null
        set(value) {
            if (value == field) return
            field = value
            keyboardLift = 0f
            liftAboveKeyboard()
            setNeedsRender()
        }

    init {
        holder.addCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val renderer = ScreenRenderer(
            controller.pipeline,
            failure = { controller.drawingFailed(it) },
            hdrChanged = ::hdrChanged,
        )
        this.renderer = renderer
        controller.screen = this
        controller.displaySupportsHDR = displaySupportsHDR()
        presenter.start(renderer)
        highestRefreshRate = 0f
        updateRefreshRate()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        ContextCompat.registerReceiver(context, powerSaveReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDetachedFromWindow() {
        displayManager.unregisterDisplayListener(displayListener)
        context.unregisterReceiver(powerSaveReceiver)
        if (controller.screen === this) controller.screen = null
        presenter.stop()
        super.onDetachedFromWindow()
        renderer?.setSurface(null, 0, 0)
        renderer?.release()
        renderer = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer?.setSurface(holder.surface, width, height)
        applyFrameRate()
        setNeedsRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer?.setSurface(null, 0, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRefreshRate()
        if (controller.scaleMode == ScaleMode.ActualSize) {
            applyScale(ScaleMode.ActualSize)
        }
        clampCenter()
        liftAboveKeyboard()
        setNeedsRender()
    }

    private fun updateRefreshRate() {
        val display = display ?: return
        val current = display.refreshRate
        presenter.setFrameInterval((1_000_000_000 / current).toLong())
        val mode = display.mode
        val maximum = display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: current
        if (maximum != requestedRefreshRate) {
            requestedRefreshRate = maximum
            applyFrameRate()
        }
        highestRefreshRate = max(highestRefreshRate, current)
        presenter.setRefreshRate(highestRefreshRate.roundToInt())
    }

    private fun applyFrameRate() {
        val rate = requestedRefreshRate
        val surface = holder.surface
        if (rate <= 0f || surface == null || !surface.isValid || Build.VERSION.SDK_INT < 30) return
        if (Build.VERSION.SDK_INT >= 31) {
            surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
        } else {
            surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    private fun displaySupportsHDR(): Boolean {
        val display = display ?: return false
        val types = if (Build.VERSION.SDK_INT >= 34) {
            display.mode.supportedHdrTypes
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes
        }
        return types?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
    }

    private fun hdrChanged(hdr: Boolean) {
        if (Build.VERSION.SDK_INT >= 35) {
            setDesiredHdrHeadroom(if (hdr) HDR_HEADROOM else 0f)
        }
    }

    override val viewSize: Size get() = Size(width.toFloat(), height.toFloat())

    override val isOccluded: Boolean get() = controller.sceneInBackground

    override fun drawnCursor(shape: CursorShape?, visible: Boolean, scale: Float): DrawnCursor? = delegate.drawnCursor(shape, visible, scale)

    override fun cursorDidRender(rect: Rect?) = delegate.cursorDidRender(rect)

    override fun loupe(icon: Rect?): LoupeSpot? = delegate.loupe(icon)

    fun backgroundStateDidChange() {
        if (controller.sceneInBackground) {
            presenter.pause()
        } else {
            setNeedsRender()
        }
    }

    fun setNeedsRender() {
        delegate.geometryDidChange()
        presenter.setNeedsRender()
    }

    open fun setKeyboardVisible(visible: Boolean) {}

    open fun forgetTypedText() {}

    fun imageSizeDidChange() {
        if (controller.scaleMode == ScaleMode.ActualSize) {
            applyScale(ScaleMode.ActualSize)
        } else if (controller.scaleMode == ScaleMode.Fit) {
            zoom = 1f
            viewportCenter = null
        }
        clampCenter()
        liftAboveKeyboard()
        setNeedsRender()
    }

    private val bounds: Rect get() = Rect(0f, 0f, width.toFloat(), height.toFloat())

    private val imageSize: Size
        get() {
            val size = controller.imageSize
            return if (size.width > 0 && size.height > 0) size else Size(16f, 10f)
        }

    private val fitArea: Rect get() = screenArea ?: bounds

    private val fitScale: Float
        get() {
            val area = fitArea
            val scale = min(area.width / imageSize.width, area.height / imageSize.height)
            return if (scale >= 1 && scale < 1.01f) 1f else scale
        }

    override val displayScale: Float get() = fitScale * zoom

    private val maximumZoom: Float get() = max(4f, 8f / max(fitScale, 0.0001f))

    private val actualSizeZoom: Float get() = 1f / max(fitScale, 0.0001f)

    override val imageRect: Rect
        get() {
            val scale = displayScale
            val size = Size(imageSize.width * scale, imageSize.height * scale)
            val area = fitArea
            var x = area.center.x - size.width / 2
            var y = area.center.y - size.height / 2
            viewportCenter?.let { center ->
                x = bounds.center.x - center.x * scale
                y = bounds.center.y - center.y * scale
            }
            y -= keyboardLift
            return Rect(Offset(x.roundToInt().toFloat(), y.roundToInt().toFloat()), size)
        }

    private fun liftAboveKeyboard() {
        val keyboardTop = keyboardTop
        if (keyboardTop == null) {
            keyboardLift = 0f
            return
        }
        val margin = KEYBOARD_MARGIN * density
        val unlifted = imageRect.translate(0f, keyboardLift)
        val pointerY = unlifted.top + controller.pointerPosition.y * displayScale
        val highest = max(0f, unlifted.bottom - keyboardTop)
        var lift = keyboardLift
        if (pointerY - lift > keyboardTop - margin) {
            lift = pointerY - (keyboardTop - margin)
        } else if (pointerY - lift < fitArea.top + margin) {
            lift = pointerY - (fitArea.top + margin)
        }
        keyboardLift = min(max(lift, 0f), highest)
    }

    private fun clampCenter() {
        val center = viewportCenter ?: return
        val scale = displayScale
        val area = fitArea
        fun clamp(center: Float, image: Float, areaStart: Float, areaEnd: Float, boundsStart: Float, boundsEnd: Float): Float {
            val size = image * scale
            val middle = (boundsStart + boundsEnd) / 2
            val lowest: Float
            val highest: Float
            if (size > boundsEnd - boundsStart) {
                lowest = boundsEnd - size
                highest = boundsStart
            } else if (size > areaEnd - areaStart) {
                lowest = (areaStart + areaEnd - size) / 2
                highest = lowest
            } else {
                lowest = areaStart
                highest = areaEnd - size
            }
            val origin = min(max(middle - center * scale, lowest), highest)
            return (middle - origin) / scale
        }
        viewportCenter = Offset(
            clamp(center.x, imageSize.width, area.left, area.right, 0f, width.toFloat()),
            clamp(center.y, imageSize.height, area.top, area.bottom, 0f, height.toFloat()),
        )
    }

    private fun currentCenter(): Offset {
        val rect = imageRect.translate(0f, keyboardLift)
        val scale = displayScale
        return Offset((bounds.center.x - rect.left) / scale, (bounds.center.y - rect.top) / scale)
    }

    fun imagePoint(viewPoint: Offset): Offset {
        val rect = imageRect
        val scale = displayScale
        return Offset((viewPoint.x - rect.left) / scale, (viewPoint.y - rect.top) / scale)
    }

    fun viewPoint(imagePoint: Offset): Offset {
        val rect = imageRect
        val scale = displayScale
        return Offset(rect.left + imagePoint.x * scale, rect.top + imagePoint.y * scale)
    }

    fun applyScale(scale: ScaleMode) {
        when (scale) {
            ScaleMode.Fit -> {
                zoom = 1f
                viewportCenter = null
            }
            ScaleMode.ActualSize -> {
                val anchor = controller.pointerPosition
                zoom = max(actualSizeZoom, 0.0001f)
                viewportCenter = null
                val centered = currentCenter()
                val displayScale = displayScale
                viewportCenter = Offset(
                    if (imageSize.width * displayScale > width) anchor.x else centered.x,
                    if (imageSize.height * displayScale > height) anchor.y else centered.y,
                )
                clampCenter()
            }
            ScaleMode.Custom -> Unit
        }
        controller.scaleMode = scale
        liftAboveKeyboard()
        setNeedsRender()
    }

    fun toggleZoom(viewPoint: Offset) {
        if (controller.scaleMode != ScaleMode.Fit) {
            applyScale(ScaleMode.Fit)
        } else {
            setZoom(max(actualSizeZoom, 2f), imagePoint(viewPoint), viewPoint)
        }
    }

    fun setZoom(newZoom: Float, imageAnchor: Offset, viewAnchor: Offset) {
        zoom = min(max(newZoom, MINIMUM_ZOOM), maximumZoom)
        val scale = displayScale
        viewportCenter = Offset(
            imageAnchor.x + (bounds.center.x - viewAnchor.x) / scale,
            imageAnchor.y + (bounds.center.y - viewAnchor.y - keyboardLift) / scale,
        )
        clampCenter()
        updateScaleMode()
        liftAboveKeyboard()
        setNeedsRender()
    }

    private fun updateScaleMode() {
        controller.scaleMode = when {
            abs(zoom - 1) < 0.01f -> ScaleMode.Fit
            abs(zoom - actualSizeZoom) < 0.01f -> ScaleMode.ActualSize
            else -> ScaleMode.Custom
        }
    }

    fun keepPointerVisible() {
        try {
            if (zoom <= 1.01f) return
            val point = viewPoint(controller.pointerPosition)
            val insetX = min(POINTER_INSET * density, width / 4f)
            val insetY = min(POINTER_INSET * density, height / 4f)
            val inset = Rect(insetX, insetY, width - insetX, height - insetY)
            val shiftX = when {
                point.x < inset.left -> point.x - inset.left
                point.x > inset.right -> point.x - inset.right
                else -> 0f
            }
            val shiftY = when {
                point.y < inset.top -> point.y - inset.top
                point.y > inset.bottom -> point.y - inset.bottom
                else -> 0f
            }
            if (shiftX == 0f && shiftY == 0f) return
            val scale = displayScale
            val current = currentCenter()
            viewportCenter = Offset(current.x + shiftX / scale, current.y + shiftY / scale)
            clampCenter()
            setNeedsRender()
        } finally {
            if (keyboardTop != null) {
                val lift = keyboardLift
                liftAboveKeyboard()
                if (lift != keyboardLift) setNeedsRender()
            }
        }
    }

    fun panViewport(delta: Offset) {
        val scale = displayScale
        val current = currentCenter()
        viewportCenter = Offset(current.x - delta.x / scale, current.y - delta.y / scale)
        clampCenter()
        setNeedsRender()
    }

    private companion object {
        const val MINIMUM_ZOOM = 0.25f
        const val KEYBOARD_MARGIN = 48f
        const val POINTER_INSET = 80f
        const val HDR_HEADROOM = 1000f / 203f
    }
}
