package io.nekohasekai.screensharing.sessionui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.PointerIcon
import io.nekohasekai.screensharing.session.CursorShape
import io.nekohasekai.screensharing.session.StandardCursor
import io.nekohasekai.screensharing.session.StandardCursor.ResizeDirections
import kotlin.math.max
import kotlin.math.roundToInt

object PointerShape {
    private class Cached(val image: Bitmap, val scale: Float, val icon: PointerIcon)

    private val resizeAxes = listOf(
        ResizeDirections.Left + ResizeDirections.Right to PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,
        ResizeDirections.Up + ResizeDirections.Down to PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW,
        ResizeDirections.UpLeft + ResizeDirections.DownRight to PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW,
        ResizeDirections.UpRight + ResizeDirections.DownLeft to PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW,
    )

    private var cached: Cached? = null

    fun type(cursor: StandardCursor): Int = when (cursor) {
        StandardCursor.Arrow -> PointerIcon.TYPE_ARROW
        StandardCursor.ContextualMenu -> PointerIcon.TYPE_CONTEXT_MENU
        StandardCursor.PointingHand -> PointerIcon.TYPE_HAND
        StandardCursor.OpenHand -> PointerIcon.TYPE_GRAB
        StandardCursor.ClosedHand -> PointerIcon.TYPE_GRABBING
        StandardCursor.DragLink -> PointerIcon.TYPE_ALIAS
        StandardCursor.DragCopy -> PointerIcon.TYPE_COPY
        StandardCursor.OperationNotAllowed -> PointerIcon.TYPE_NO_DROP
        StandardCursor.IBeam -> PointerIcon.TYPE_TEXT
        StandardCursor.VerticalIBeam -> PointerIcon.TYPE_VERTICAL_TEXT
        StandardCursor.Crosshair -> PointerIcon.TYPE_CROSSHAIR
        StandardCursor.ZoomIn -> PointerIcon.TYPE_ZOOM_IN
        StandardCursor.ZoomOut -> PointerIcon.TYPE_ZOOM_OUT
        is StandardCursor.Resize -> resizeAxes.filter { (axis, _) -> cursor.directions.rawValue and axis.rawValue != 0 }
            .singleOrNull()?.second ?: PointerIcon.TYPE_ALL_SCROLL
    }

    fun icon(context: Context, shape: CursorShape, scale: Float, standard: Boolean): PointerIcon? {
        val kind = shape.kind
        if (standard && kind != null) return PointerIcon.getSystemIcon(context, type(kind))
        if (shape.inversion != null || scale <= 0f || shape.scale <= 0f) return null
        cached?.let { if (it.image === shape.image && it.scale == scale) return it.icon }
        val image = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && shape.image.config == Bitmap.Config.HARDWARE) shape.image.copy(Bitmap.Config.ARGB_8888, false) ?: return null else shape.image
        val pixels = scale / shape.scale
        val width = max(1, (image.width * pixels).roundToInt())
        val height = max(1, (image.height * pixels).roundToInt())
        val bitmap = if (width == image.width && height == image.height) image else Bitmap.createScaledBitmap(image, width, height, true)
        val icon = PointerIcon.create(
            bitmap,
            (shape.hotspot.x * scale).coerceIn(0f, width - 1f),
            (shape.hotspot.y * scale).coerceIn(0f, height - 1f),
        )
        cached = Cached(shape.image, scale, icon)
        return icon
    }
}
