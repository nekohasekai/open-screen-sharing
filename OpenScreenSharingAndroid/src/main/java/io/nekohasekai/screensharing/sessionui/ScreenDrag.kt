package io.nekohasekai.screensharing.sessionui

import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.os.SystemClock
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.core.content.ContextCompat
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.session.PointerButtons
import io.nekohasekai.screensharing.session.RemoteDragOffer
import io.nekohasekai.screensharing.session.SessionController
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class ScreenDrag(
    private val view: RemoteScreenView,
    private val controller: SessionController,
    private val input: ScreenInput,
) {
    data class TouchKey(val downTime: Long, val pointerId: Int)

    private var attemptedTouch: TouchKey? = null
    var takenTouch: TouchKey? = null
        private set
    private var session: Any? = null
    private var offer: RemoteDragOffer? = null
    private var folder: File? = null
    private var relative = false
    private var last: Pair<Offset, Long>? = null
    private var isOverMac = true
    private var droppedOnMac = false

    val isDragging: Boolean get() = session != null

    fun owns(localState: Any?): Boolean = localState != null && localState === session

    fun begin(
        touch: TouchKey,
        location: Offset,
        relative: Boolean,
    ) {
        if (controller.remoteDragOffer == null || session != null || touch == attemptedTouch) return
        attemptedTouch = touch
        this.relative = relative
        last = location to SystemClock.uptimeMillis()
        val offer = controller.takeRemoteDrag() ?: return
        if (offer.items.isEmpty()) return
        val folder = File(RemoteFileProvider.dragsFolder, UUID.randomUUID().toString())
        val offered = RemoteFileProvider.offer(controller, offer.items, folder)
        val description = ClipDescription(offered.first().name, offered.map { it.mimeType }.distinct().toTypedArray())
        val clip = ClipData(description, ClipData.Item(offered.first().uri))
        for (item in offered.drop(1)) clip.addItem(ClipData.Item(item.uri))
        val session = Any()
        this.session = session
        this.offer = offer
        this.folder = folder
        takenTouch = touch
        isOverMac = true
        droppedOnMac = false
        if (view.startDragAndDrop(clip, Preview(), session, View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ)) return
        RemoteFileProvider.discard(folder)
        this.session = null
        this.offer = null
        this.folder = null
        takenTouch = null
        last = null
    }

    fun moved(location: Offset) {
        val now = SystemClock.uptimeMillis()
        val surface = view.surface
        if (relative) {
            last?.let { (previous, time) ->
                val delta = location - previous
                input.movePointer(delta, delta.getDistance() / max((now - time) / 1000f, 0.001f))
            }
            setOverMac(true)
        } else if (surface.imageRect.intersect(Rect(0f, 0f, view.width.toFloat(), view.height.toFloat())).contains(location)) {
            controller.movePointer(surface.imagePoint(location))
            setOverMac(true)
        } else {
            setOverMac(false)
        }
        last = location to now
    }

    fun exited() {
        last = null
        setOverMac(false)
    }

    fun drop(location: Offset): Boolean {
        moved(location)
        if (!isOverMac) return false
        droppedOnMac = true
        controller.release(PointerButtons.Primary)
        return true
    }

    fun ended(result: Boolean) {
        if (!droppedOnMac) controller.cancelRemoteDrag()
        val folder = folder
        if (folder != null && (droppedOnMac || !result)) RemoteFileProvider.discard(folder)
        session = null
        offer = null
        this.folder = null
        takenTouch = null
        last = null
    }

    private fun setOverMac(over: Boolean) {
        if (over == isOverMac || session == null) return
        isOverMac = over
        view.updateDragShadow(Preview())
    }

    private inner class Preview : View.DragShadowBuilder() {
        private val image = if (isOverMac) null else offer?.image
        private val icon = if (isOverMac || image != null) {
            null
        } else {
            ContextCompat.getDrawable(
                this@ScreenDrag.view.context,
                if (offer?.items?.firstOrNull()?.isFolder == true) R.drawable.input_drag_folder else R.drawable.input_drag_file,
            )
        }
        private val width: Int
        private val height: Int

        init {
            val scale = this@ScreenDrag.view.surface.displayScale
            when {
                image != null -> {
                    width = max(1, (image.width * scale).roundToInt())
                    height = max(1, (image.height * scale).roundToInt())
                }
                icon != null -> {
                    width = (ICON_SIZE * this@ScreenDrag.view.resources.displayMetrics.density).roundToInt()
                    height = width
                }
                else -> {
                    width = 1
                    height = 1
                }
            }
        }

        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            outShadowSize.set(width, height)
            outShadowTouchPoint.set(width / 2, height / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            if (image != null) {
                canvas.drawBitmap(image, null, android.graphics.Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            } else if (icon != null) {
                icon.setBounds(0, 0, width, height)
                icon.draw(canvas)
            }
        }
    }

    private companion object {
        const val ICON_SIZE = 48f
    }
}
