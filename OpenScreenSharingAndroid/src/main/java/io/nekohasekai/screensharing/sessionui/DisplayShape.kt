package io.nekohasekai.screensharing.sessionui

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.core.graphics.Insets
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

class DisplayShape(
    val cutouts: List<Rect>,
    val topLeft: Float,
    val topRight: Float,
    val bottomLeft: Float,
    val bottomRight: Float,
) {
    companion object {
        fun of(view: View): DisplayShape? {
            val insets = view.rootWindowInsets ?: return null
            val location = IntArray(2).also { view.getLocationInWindow(it) }
            val left = -location[0].toFloat()
            val top = -location[1].toFloat()
            val cutouts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.boundingRects.orEmpty()
                    .filterNot { it.isEmpty }
                    .map { Rect(it.left + left, it.top + top, it.right + left, it.bottom + top) }
            } else {
                emptyList()
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return DisplayShape(cutouts, 0f, 0f, 0f, 0f)
            }
            val window = view.rootView
            val atLeft = abs(left) < 1
            val atTop = abs(top) < 1
            val atRight = abs(left + window.width - view.width) < 1
            val atBottom = abs(top + window.height - view.height) < 1
            return DisplayShape(
                cutouts,
                topLeft = if (atLeft && atTop) radius(insets, RoundedCorner.POSITION_TOP_LEFT) else 0f,
                topRight = if (atRight && atTop) radius(insets, RoundedCorner.POSITION_TOP_RIGHT) else 0f,
                bottomLeft = if (atLeft && atBottom) radius(insets, RoundedCorner.POSITION_BOTTOM_LEFT) else 0f,
                bottomRight = if (atRight && atBottom) radius(insets, RoundedCorner.POSITION_BOTTOM_RIGHT) else 0f,
            )
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun radius(insets: WindowInsets, position: Int): Float = insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
    }
}

data class ScreenInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    fun union(other: ScreenInsets) = ScreenInsets(max(left, other.left), max(top, other.top), max(right, other.right), max(bottom, other.bottom))

    fun inset(rect: Rect) = Rect(rect.left + left, rect.top + top, rect.right - right, rect.bottom - bottom)

    companion object {
        fun of(insets: Insets) = ScreenInsets(insets.left.toFloat(), insets.top.toFloat(), insets.right.toFloat(), insets.bottom.toFloat())
    }
}

data class ScreenObstructions(
    val occlusions: List<Rect> = emptyList(),
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomLeft: Float = 0f,
    val bottomRight: Float = 0f,
    val density: Float = 1f,
) {
    private class Corner(val radius: Float, val left: Boolean, val top: Boolean)

    private val corners: List<Corner>
        get() = listOf(
            Corner(topLeft, left = true, top = true),
            Corner(topRight, left = false, top = true),
            Corner(bottomLeft, left = true, top = false),
            Corner(bottomRight, left = false, top = false),
        )

    fun clearingCorners(insets: ScreenInsets): ScreenInsets {
        var cleared = insets
        for (corner in corners) {
            val radius = corner.radius
            val horizontal = if (corner.left) cleared.left else cleared.right
            val vertical = if (corner.top) cleared.top else cleared.bottom
            if (!hides(radius, horizontal, vertical)) continue
            val outer = max(max(horizontal, vertical), cornerInset(radius))
            val inner = radius - sqrt(radius * radius - (radius - outer) * (radius - outer))
            val newHorizontal = if (horizontal >= vertical) outer else inner
            val newVertical = if (horizontal >= vertical) inner else outer
            cleared = if (corner.left) cleared.copy(left = newHorizontal) else cleared.copy(right = newHorizontal)
            cleared = if (corner.top) cleared.copy(top = newVertical) else cleared.copy(bottom = newVertical)
        }
        return cleared
    }

    fun topCornerInset(left: Boolean, area: Rect, size: Size): Float? {
        val radius = if (left) topLeft else topRight
        if (radius <= 0) return null
        val inset = cornerInset(radius)
        val frame = Rect(Offset(if (left) area.left + inset else area.right - inset - size.width, area.top + inset), size)
        return if (occlusions.any { it.overlaps(frame) }) null else inset
    }

    fun loupe(icon: Rect, area: Rect): LoupeSpot? {
        val offset = (OCCLUSION_GAP + LOUPE_BORDER_WIDTH) * density
        for (occlusion in occlusions) {
            val gaps = listOf(occlusion.left - area.left, area.right - occlusion.right, occlusion.top - area.top, area.bottom - occlusion.bottom)
            val source: Rect
            val frame: Rect
            when (gaps.indices.minBy { gaps[it] }) {
                0 -> {
                    source = Rect(area.left, occlusion.top, occlusion.right, occlusion.bottom)
                    frame = source.translate(source.width + offset, 0f)
                }
                1 -> {
                    source = Rect(occlusion.left, occlusion.top, occlusion.right + gaps[1], occlusion.bottom)
                    frame = source.translate(-source.width - offset, 0f)
                }
                2 -> {
                    source = Rect(occlusion.left, area.top, occlusion.right, occlusion.bottom)
                    frame = source.translate(0f, source.height + offset)
                }
                else -> {
                    source = Rect(occlusion.left, occlusion.top, occlusion.right, occlusion.bottom + gaps[3])
                    frame = source.translate(0f, -source.height - offset)
                }
            }
            if (source.overlaps(icon)) {
                return LoupeSpot(source, frame)
            }
        }
        for (corner in corners) {
            val radius = corner.radius
            val horizontal = if (corner.left) max(icon.left, area.left) - area.left else area.right - minOf(icon.right, area.right)
            val vertical = if (corner.top) max(icon.top, area.top) - area.top else area.bottom - minOf(icon.bottom, area.bottom)
            if (!hides(radius, horizontal, vertical)) continue
            val x = if (corner.left) area.left else area.right - radius
            val y = if (corner.top) area.top else area.bottom - radius
            val source = Rect(x, y, x + radius, y + radius)
            val inset = cornerInset(radius) + LOUPE_BORDER_WIDTH * density
            return LoupeSpot(source, source.translate(if (corner.left) inset else -inset, if (corner.top) inset else -inset))
        }
        return null
    }

    companion object {
        const val LOUPE_BORDER_WIDTH = 2f
        private const val OCCLUSION_GAP = 8f

        private fun hides(radius: Float, horizontal: Float, vertical: Float): Boolean = horizontal < radius && vertical < radius && hypot(radius - horizontal, radius - vertical) > radius

        private fun cornerInset(radius: Float): Float = radius * (1 - 1 / sqrt(2f))
    }
}
