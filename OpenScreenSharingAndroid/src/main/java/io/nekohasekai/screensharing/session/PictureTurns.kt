package io.nekohasekai.screensharing.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

@JvmInline
value class PictureTurns private constructor(val count: Int) {
    val isSideways: Boolean get() = count % 2 == 1

    fun shownSize(size: Size): Size = if (isSideways) Size(size.height, size.width) else size

    fun shownPoint(point: Offset, pictureSize: Size): Offset {
        val right = pictureSize.width - 1
        val bottom = pictureSize.height - 1
        return when (count) {
            1 -> Offset(bottom - point.y, point.x)
            2 -> Offset(right - point.x, bottom - point.y)
            3 -> Offset(point.y, right - point.x)
            else -> point
        }
    }

    fun picturePoint(point: Offset, pictureSize: Size): Offset {
        val right = pictureSize.width - 1
        val bottom = pictureSize.height - 1
        return when (count) {
            1 -> Offset(point.y, bottom - point.x)
            2 -> Offset(right - point.x, bottom - point.y)
            3 -> Offset(right - point.y, point.x)
            else -> point
        }
    }

    fun pictureVector(vector: Offset): Offset = when (count) {
        1 -> Offset(vector.y, -vector.x)
        2 -> Offset(-vector.x, -vector.y)
        3 -> Offset(-vector.y, vector.x)
        else -> vector
    }

    fun shownRect(unitRect: Rect): Rect = when (count) {
        1 -> Rect(1 - unitRect.bottom, unitRect.left, 1 - unitRect.top, unitRect.right)
        2 -> Rect(1 - unitRect.right, 1 - unitRect.bottom, 1 - unitRect.left, 1 - unitRect.top)
        3 -> Rect(unitRect.top, 1 - unitRect.right, unitRect.bottom, 1 - unitRect.left)
        else -> unitRect
    }

    val degrees: Int get() = count * 90

    companion object {
        val None = PictureTurns(0)

        operator fun invoke(count: Int): PictureTurns = PictureTurns(count and 3)
    }
}
