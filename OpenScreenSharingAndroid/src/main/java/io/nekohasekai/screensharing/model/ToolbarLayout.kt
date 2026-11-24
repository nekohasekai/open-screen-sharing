package io.nekohasekai.screensharing.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.session.RemotePlatform
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.sessionui.ScreenObstructions
import io.nekohasekai.screensharing.sessionui.SessionCommand
import io.nekohasekai.screensharing.sessionui.SessionCommandCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ToolbarLayout private constructor(
    private val platform: RemotePlatform,
    private val key: String,
    defaultBar: List<SessionCommand>,
) {
    val defaultBar: List<SessionCommand> = defaultBar.filter { it.applies(platform) }

    var bar: List<SessionCommand> by mutableStateOf(this.defaultBar)
        private set
    var isCustomized: Boolean by mutableStateOf(false)
        private set

    init {
        val stored = PreferenceStore.value<List<String>>(key)
        if (stored != null) {
            bar = stored.mapNotNull { SessionCommand.of(it) }
            isCustomized = true
        }
    }

    fun otherCommands(category: SessionCommandCategory): List<SessionCommand> = category.commands.filter { it !in bar && it.applies(platform) }

    val isDefault: Boolean get() = bar == defaultBar

    fun move(
        fromOffsets: Collection<Int>,
        toOffset: Int,
    ) {
        val moving = fromOffsets.sorted().map { bar[it] }
        val remaining = bar.filterIndexed { index, _ -> index !in fromOffsets }.toMutableList()
        remaining.addAll(toOffset - fromOffsets.count { it < toOffset }, moving)
        bar = remaining
        save()
    }

    fun move(
        command: SessionCommand,
        offset: Int,
    ) {
        val index = bar.indexOf(command)
        if (index < 0) return
        val target = index + offset
        if (target !in bar.indices) return
        bar =
            bar.toMutableList().apply {
                this[index] = bar[target]
                this[target] = command
            }
        save()
    }

    fun remove(command: SessionCommand) {
        bar = bar.filter { it != command }
        save()
    }

    fun add(command: SessionCommand) {
        if (command in bar) return
        bar = bar + command
        save()
    }

    fun reset() {
        bar = defaultBar
        isCustomized = false
        PreferenceStore.remove(key)
    }

    fun plan(
        controller: SessionController,
        slots: Int?,
    ): ToolbarPlan {
        val offered = bar.filter { it.isOffered(controller) && it.isEnabled(controller) }
        val count = min(max(slots ?: offered.size, 0), offered.size)
        return ToolbarPlan(inBar = offered.take(count), overflow = offered.drop(count))
    }

    private fun save() {
        isCustomized = true
        PreferenceStore.set(key, bar.map { it.rawValue })
    }

    companion object {
        val mac =
            ToolbarLayout(
                RemotePlatform.Mac,
                "SessionToolbar",
                listOf(SessionCommand.Displays, SessionCommand.Audio, SessionCommand.PointerLock, SessionCommand.Keyboard, SessionCommand.Clipboard),
            )
        val pc =
            ToolbarLayout(
                RemotePlatform.Pc,
                "SessionToolbar.PC",
                listOf(SessionCommand.Displays, SessionCommand.Audio, SessionCommand.PointerLock, SessionCommand.Keyboard, SessionCommand.Clipboard),
            )

        fun layout(controller: SessionController): ToolbarLayout = when (controller.info.platform) {
            RemotePlatform.Mac -> mac
            RemotePlatform.Pc -> pc
        }

        const val maximumSlots = 8

        fun capsuleSlots(
            width: Dp,
            itemWidth: Dp,
        ): Int {
            val fixed = itemWidth * 2 + capsuleGroupSpacing * 2 + capsuleGroupPadding * 2 + capsuleMargin * 2
            return slots(width - fixed, itemWidth)
        }

        fun floatingSlots(
            length: Dp,
            itemWidth: Dp,
            closes: Boolean,
        ): Int {
            val fixed = capsuleGroupPadding * 2 + itemWidth + (if (closes) itemWidth + capsuleGroupSpacing else 0.dp) + capsuleGroupSpacing
            return slots(length - fixed, itemWidth)
        }

        private fun slots(
            width: Dp,
            itemWidth: Dp,
        ): Int {
            if (width <= 0.dp || itemWidth <= 0.dp) return 0
            return min(maximumSlots, floor(width / itemWidth).toInt())
        }

        val itemSize = 48.dp
        val capsuleGroupSpacing = 8.dp
        val capsuleGroupPadding = 4.dp
        val capsuleMargin = 16.dp
        val floatingMargin = 8.dp
        val dockClearance = 4.dp
        val dockMargin = 16.dp
    }
}

data class ToolbarPlan(
    val inBar: List<SessionCommand> = emptyList(),
    val overflow: List<SessionCommand> = emptyList(),
)

@Serializable
enum class FloatingToolbarEdge {
    @SerialName("left")
    Left,

    @SerialName("right")
    Right,

    @SerialName("top")
    Top,

    @SerialName("bottom")
    Bottom,
}

@Serializable
data class FloatingToolbarAnchor(
    val edge: FloatingToolbarEdge = FloatingToolbarEdge.Right,
    val location: Double = 0.2,
) {
    private class EdgeGeometry(
        val cross: Float,
        val start: Float,
        val end: Float,
        val segments: List<Pair<Float, Float>>,
    )

    fun point(
        width: Float,
        height: Float,
        ballSize: Float,
        margin: Float,
        clearance: Float,
        obstructions: ScreenObstructions,
    ): Offset {
        val geometry = edgeGeometry(edge, width, height, ballSize, margin, clearance, obstructions)
        val t = clamp(location.toFloat(), 0f, 1f)
        val raw = geometry.start + t * (geometry.end - geometry.start)
        val along = clampToSegments(raw, geometry.segments, (geometry.start + geometry.end) / 2f)
        return when (edge) {
            FloatingToolbarEdge.Left, FloatingToolbarEdge.Right -> Offset(geometry.cross, along)
            FloatingToolbarEdge.Top, FloatingToolbarEdge.Bottom -> Offset(along, geometry.cross)
        }
    }

    companion object {
        fun clamp(
            value: Float,
            low: Float,
            high: Float,
        ): Float = if (low > high) (low + high) / 2f else value.coerceIn(low, high)

        fun cornerSpanInset(
            radius: Float,
            crossOffset: Float,
            ballRadius: Float,
            margin: Float,
            clearance: Float,
        ): Float {
            val base = margin + ballRadius
            if (radius <= crossOffset) return base
            val dx = radius - crossOffset
            val limit = radius - ballRadius - clearance
            if (limit <= dx) return max(base, radius)
            return max(base, radius - sqrt(limit * limit - dx * dx))
        }

        private fun subtractInterval(
            cutLow: Float,
            cutHigh: Float,
            segments: List<Pair<Float, Float>>,
        ): List<Pair<Float, Float>> {
            if (cutLow >= cutHigh) return segments
            val result = ArrayList<Pair<Float, Float>>(segments.size + 1)
            for ((low, high) in segments) {
                if (cutHigh <= low || cutLow >= high) {
                    result.add(low to high)
                } else {
                    if (cutLow > low) result.add(low to cutLow)
                    if (cutHigh < high) result.add(cutHigh to high)
                }
            }
            return result
        }

        private fun clampToSegments(
            value: Float,
            segments: List<Pair<Float, Float>>,
            fallback: Float,
        ): Float {
            if (segments.isEmpty()) return fallback
            for ((low, high) in segments) {
                if (value in low..high) return value
            }
            var best = segments[0].first
            var bestDistance = abs(value - best)
            for ((low, high) in segments) {
                for (candidate in listOf(low, high)) {
                    val distance = abs(value - candidate)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = candidate
                    }
                }
            }
            return best
        }

        private fun edgeGeometry(
            edge: FloatingToolbarEdge,
            width: Float,
            height: Float,
            ballSize: Float,
            margin: Float,
            clearance: Float,
            obstructions: ScreenObstructions,
        ): EdgeGeometry {
            val half = ballSize / 2f
            val cross: Float
            val rawStart: Float
            val rawEnd: Float
            when (edge) {
                FloatingToolbarEdge.Left -> {
                    cross = min(margin + half, width / 2f)
                    rawStart = cornerSpanInset(obstructions.topLeft, cross, half, margin, clearance)
                    rawEnd = height - cornerSpanInset(obstructions.bottomLeft, cross, half, margin, clearance)
                }
                FloatingToolbarEdge.Right -> {
                    cross = max(width - margin - half, width / 2f)
                    val offset = width - cross
                    rawStart = cornerSpanInset(obstructions.topRight, offset, half, margin, clearance)
                    rawEnd = height - cornerSpanInset(obstructions.bottomRight, offset, half, margin, clearance)
                }
                FloatingToolbarEdge.Top -> {
                    cross = min(margin + half, height / 2f)
                    rawStart = cornerSpanInset(obstructions.topLeft, cross, half, margin, clearance)
                    rawEnd = width - cornerSpanInset(obstructions.topRight, cross, half, margin, clearance)
                }
                FloatingToolbarEdge.Bottom -> {
                    cross = max(height - margin - half, height / 2f)
                    val offset = height - cross
                    rawStart = cornerSpanInset(obstructions.bottomLeft, offset, half, margin, clearance)
                    rawEnd = width - cornerSpanInset(obstructions.bottomRight, offset, half, margin, clearance)
                }
            }
            val start = if (rawStart <= rawEnd) rawStart else (rawStart + rawEnd) / 2f
            val end = if (rawStart <= rawEnd) rawEnd else start
            var segments = listOf(start to end)
            for (occlusion in obstructions.occlusions) {
                when (edge) {
                    FloatingToolbarEdge.Left, FloatingToolbarEdge.Right -> {
                        if (occlusion.right + clearance - 0.01f > cross - half && occlusion.left - clearance + 0.01f < cross + half) {
                            segments = subtractInterval(occlusion.top - clearance - half, occlusion.bottom + clearance + half, segments)
                        }
                    }
                    FloatingToolbarEdge.Top, FloatingToolbarEdge.Bottom -> {
                        if (occlusion.bottom + clearance - 0.01f > cross - half && occlusion.top - clearance + 0.01f < cross + half) {
                            segments = subtractInterval(occlusion.left - clearance - half, occlusion.right + clearance + half, segments)
                        }
                    }
                }
            }
            return EdgeGeometry(cross, start, end, segments)
        }

        fun clampBall(
            point: Offset,
            width: Float,
            height: Float,
            ballSize: Float,
            margin: Float,
            clearance: Float,
            obstructions: ScreenObstructions,
        ): Offset {
            val half = ballSize / 2f
            val minX = min(margin + half, width / 2f)
            val maxX = max(width - margin - half, width / 2f)
            val minY = min(margin + half, height / 2f)
            val maxY = max(height - margin - half, height / 2f)
            var x = clamp(point.x, minX, maxX)
            var y = clamp(point.y, minY, maxY)
            if (obstructions.topLeft > 0f && x < obstructions.topLeft && y < obstructions.topLeft) {
                if (obstructions.topLeft - x >= obstructions.topLeft - y) {
                    y = max(y, min(maxY, cornerSpanInset(obstructions.topLeft, x, half, margin, clearance)))
                } else {
                    x = max(x, min(maxX, cornerSpanInset(obstructions.topLeft, y, half, margin, clearance)))
                }
            }
            if (obstructions.topRight > 0f && width - x < obstructions.topRight && y < obstructions.topRight) {
                val rx = width - x
                if (obstructions.topRight - rx >= obstructions.topRight - y) {
                    y = max(y, min(maxY, cornerSpanInset(obstructions.topRight, rx, half, margin, clearance)))
                } else {
                    x = min(x, max(minX, width - cornerSpanInset(obstructions.topRight, y, half, margin, clearance)))
                }
            }
            if (obstructions.bottomLeft > 0f && x < obstructions.bottomLeft && height - y < obstructions.bottomLeft) {
                val by = height - y
                if (obstructions.bottomLeft - x >= obstructions.bottomLeft - by) {
                    y = min(y, max(minY, height - cornerSpanInset(obstructions.bottomLeft, x, half, margin, clearance)))
                } else {
                    x = max(x, min(maxX, cornerSpanInset(obstructions.bottomLeft, by, half, margin, clearance)))
                }
            }
            if (obstructions.bottomRight > 0f && width - x < obstructions.bottomRight && height - y < obstructions.bottomRight) {
                val rx = width - x
                val by = height - y
                if (obstructions.bottomRight - rx >= obstructions.bottomRight - by) {
                    y = min(y, max(minY, height - cornerSpanInset(obstructions.bottomRight, rx, half, margin, clearance)))
                } else {
                    x = min(x, max(minX, width - cornerSpanInset(obstructions.bottomRight, by, half, margin, clearance)))
                }
            }
            for (occlusion in obstructions.occlusions) {
                val left = occlusion.left - clearance - half
                val right = occlusion.right + clearance + half
                val top = occlusion.top - clearance - half
                val bottom = occlusion.bottom + clearance + half
                if (x > left && x < right && y > top && y < bottom) {
                    val escapes = buildList {
                        if (left >= minX) add(Offset(left, y) to (x - left))
                        if (right <= maxX) add(Offset(right, y) to (right - x))
                        if (top >= minY) add(Offset(x, top) to (y - top))
                        if (bottom <= maxY) add(Offset(x, bottom) to (bottom - y))
                    }
                    escapes.minByOrNull { it.second }?.let { (escaped, _) ->
                        x = escaped.x
                        y = escaped.y
                    }
                }
            }
            return Offset(x, y)
        }

        fun snap(
            point: Offset,
            width: Float,
            height: Float,
            ballSize: Float,
            margin: Float,
            clearance: Float,
            obstructions: ScreenObstructions,
        ): FloatingToolbarAnchor {
            val clamped = clampBall(point, width, height, ballSize, margin, clearance, obstructions)
            var bestAnchor = FloatingToolbarAnchor()
            var bestDistance = Float.MAX_VALUE
            for (edge in FloatingToolbarEdge.entries) {
                val geometry = edgeGeometry(edge, width, height, ballSize, margin, clearance, obstructions)
                val inputAlong = if (edge == FloatingToolbarEdge.Left || edge == FloatingToolbarEdge.Right) clamped.y else clamped.x
                val snappedAlong = clampToSegments(inputAlong, geometry.segments, (geometry.start + geometry.end) / 2f)
                val candidate =
                    if (edge == FloatingToolbarEdge.Left || edge == FloatingToolbarEdge.Right) {
                        Offset(geometry.cross, snappedAlong)
                    } else {
                        Offset(snappedAlong, geometry.cross)
                    }
                val distance = hypot(clamped.x - candidate.x, clamped.y - candidate.y)
                if (distance < bestDistance) {
                    bestDistance = distance
                    val span = geometry.end - geometry.start
                    val location = if (span > 0f) clamp((snappedAlong - geometry.start) / span, 0f, 1f).toDouble() else 0.5
                    bestAnchor = FloatingToolbarAnchor(edge, location)
                }
            }
            return bestAnchor
        }

        fun localObstructions(
            obstructions: ScreenObstructions,
            density: Float,
            insetLeftPx: Int,
            insetTopPx: Int,
            insetRightPx: Int,
            insetBottomPx: Int,
        ): ScreenObstructions {
            if (density <= 0f) return ScreenObstructions()
            val dx = -insetLeftPx.toFloat()
            val dy = -insetTopPx.toFloat()
            return ScreenObstructions(
                occlusions =
                obstructions.occlusions.map {
                    Rect(
                        (it.left + dx) / density,
                        (it.top + dy) / density,
                        (it.right + dx) / density,
                        (it.bottom + dy) / density,
                    )
                },
                topLeft = if (insetLeftPx == 0 && insetTopPx == 0) obstructions.topLeft / density else 0f,
                topRight = if (insetRightPx == 0 && insetTopPx == 0) obstructions.topRight / density else 0f,
                bottomLeft = if (insetLeftPx == 0 && insetBottomPx == 0) obstructions.bottomLeft / density else 0f,
                bottomRight = if (insetRightPx == 0 && insetBottomPx == 0) obstructions.bottomRight / density else 0f,
            )
        }
    }
}

enum class FloatingToolbarDirection {
    Left,
    Right,
    Up,
    Down,
    ;

    val vertical: Boolean get() = this == Up || this == Down
}

data class FloatingToolbarPlacement(
    val ballCenter: Offset,
    val direction: FloatingToolbarDirection,
    val slots: Int,
) {
    companion object {
        fun of(
            width: Dp,
            height: Dp,
            anchor: FloatingToolbarAnchor,
            itemWidth: Dp,
            closes: Boolean,
            obstructions: ScreenObstructions,
        ): FloatingToolbarPlacement {
            val ballMargin = ToolbarLayout.floatingMargin + ToolbarLayout.capsuleGroupPadding
            val clearance = ToolbarLayout.dockClearance
            val gap = ToolbarLayout.capsuleGroupSpacing
            val center = anchor.point(width.value, height.value, itemWidth.value, ballMargin.value, clearance.value, obstructions)
            val half = itemWidth.value / 2f
            val barHalf = half + ToolbarLayout.capsuleGroupPadding.value

            fun metrics(direction: FloatingToolbarDirection): Pair<Dp, Boolean> = when (direction) {
                FloatingToolbarDirection.Right -> {
                    val start = center.x + half + gap.value
                    var maxCenter = width.value - ballMargin.value - half
                    if (center.y < obstructions.topRight) {
                        maxCenter = min(
                            maxCenter,
                            width.value - FloatingToolbarAnchor.cornerSpanInset(obstructions.topRight, center.y, half, ballMargin.value, clearance.value),
                        )
                    }
                    if (height.value - center.y < obstructions.bottomRight) {
                        maxCenter = min(
                            maxCenter,
                            width.value - FloatingToolbarAnchor.cornerSpanInset(
                                obstructions.bottomRight,
                                height.value - center.y,
                                half,
                                ballMargin.value,
                                clearance.value,
                            ),
                        )
                    }
                    val unoccluded = maxCenter + barHalf
                    var limit = unoccluded
                    for (occ in obstructions.occlusions) {
                        if (occ.bottom + clearance.value - 0.01f > center.y - barHalf &&
                            occ.top - clearance.value + 0.01f < center.y + barHalf &&
                            occ.right > center.x
                        ) {
                            limit = min(limit, occ.left - clearance.value)
                        }
                    }
                    max(0f, limit - start).dp to (limit < unoccluded - 0.5f)
                }
                FloatingToolbarDirection.Left -> {
                    val start = center.x - half - gap.value
                    var minCenter = ballMargin.value + half
                    if (center.y < obstructions.topLeft) {
                        minCenter = max(
                            minCenter,
                            FloatingToolbarAnchor.cornerSpanInset(obstructions.topLeft, center.y, half, ballMargin.value, clearance.value),
                        )
                    }
                    if (height.value - center.y < obstructions.bottomLeft) {
                        minCenter = max(
                            minCenter,
                            FloatingToolbarAnchor.cornerSpanInset(
                                obstructions.bottomLeft,
                                height.value - center.y,
                                half,
                                ballMargin.value,
                                clearance.value,
                            ),
                        )
                    }
                    val unoccluded = minCenter - barHalf
                    var limit = unoccluded
                    for (occ in obstructions.occlusions) {
                        if (occ.bottom + clearance.value - 0.01f > center.y - barHalf &&
                            occ.top - clearance.value + 0.01f < center.y + barHalf &&
                            occ.left < center.x
                        ) {
                            limit = max(limit, occ.right + clearance.value)
                        }
                    }
                    max(0f, start - limit).dp to (limit > unoccluded + 0.5f)
                }
                FloatingToolbarDirection.Down -> {
                    val start = center.y + half + gap.value
                    var maxCenter = height.value - ballMargin.value - half
                    if (center.x < obstructions.bottomLeft) {
                        maxCenter = min(
                            maxCenter,
                            height.value - FloatingToolbarAnchor.cornerSpanInset(obstructions.bottomLeft, center.x, half, ballMargin.value, clearance.value),
                        )
                    }
                    if (width.value - center.x < obstructions.bottomRight) {
                        maxCenter = min(
                            maxCenter,
                            height.value - FloatingToolbarAnchor.cornerSpanInset(
                                obstructions.bottomRight,
                                width.value - center.x,
                                half,
                                ballMargin.value,
                                clearance.value,
                            ),
                        )
                    }
                    val unoccluded = maxCenter + barHalf
                    var limit = unoccluded
                    for (occ in obstructions.occlusions) {
                        if (occ.right + clearance.value - 0.01f > center.x - barHalf &&
                            occ.left - clearance.value + 0.01f < center.x + barHalf &&
                            occ.bottom > center.y
                        ) {
                            limit = min(limit, occ.top - clearance.value)
                        }
                    }
                    max(0f, limit - start).dp to (limit < unoccluded - 0.5f)
                }
                FloatingToolbarDirection.Up -> {
                    val start = center.y - half - gap.value
                    var minCenter = ballMargin.value + half
                    if (center.x < obstructions.topLeft) {
                        minCenter = max(
                            minCenter,
                            FloatingToolbarAnchor.cornerSpanInset(obstructions.topLeft, center.x, half, ballMargin.value, clearance.value),
                        )
                    }
                    if (width.value - center.x < obstructions.topRight) {
                        minCenter = max(
                            minCenter,
                            FloatingToolbarAnchor.cornerSpanInset(
                                obstructions.topRight,
                                width.value - center.x,
                                half,
                                ballMargin.value,
                                clearance.value,
                            ),
                        )
                    }
                    val unoccluded = minCenter - barHalf
                    var limit = unoccluded
                    for (occ in obstructions.occlusions) {
                        if (occ.right + clearance.value - 0.01f > center.x - barHalf &&
                            occ.left - clearance.value + 0.01f < center.x + barHalf &&
                            occ.top < center.y
                        ) {
                            limit = max(limit, occ.bottom + clearance.value)
                        }
                    }
                    max(0f, start - limit).dp to (limit > unoccluded + 0.5f)
                }
            }

            val primary: FloatingToolbarDirection
            val alongOpposite: FloatingToolbarDirection
            val inwardPerpendicular: FloatingToolbarDirection
            when (anchor.edge) {
                FloatingToolbarEdge.Left -> {
                    primary = if (center.y <= height.value / 2f) FloatingToolbarDirection.Down else FloatingToolbarDirection.Up
                    alongOpposite = if (primary == FloatingToolbarDirection.Down) FloatingToolbarDirection.Up else FloatingToolbarDirection.Down
                    inwardPerpendicular = FloatingToolbarDirection.Right
                }
                FloatingToolbarEdge.Right -> {
                    primary = if (center.y <= height.value / 2f) FloatingToolbarDirection.Down else FloatingToolbarDirection.Up
                    alongOpposite = if (primary == FloatingToolbarDirection.Down) FloatingToolbarDirection.Up else FloatingToolbarDirection.Down
                    inwardPerpendicular = FloatingToolbarDirection.Left
                }
                FloatingToolbarEdge.Top -> {
                    primary = if (center.x <= width.value / 2f) FloatingToolbarDirection.Right else FloatingToolbarDirection.Left
                    alongOpposite = if (primary == FloatingToolbarDirection.Right) FloatingToolbarDirection.Left else FloatingToolbarDirection.Right
                    inwardPerpendicular = FloatingToolbarDirection.Down
                }
                FloatingToolbarEdge.Bottom -> {
                    primary = if (center.x <= width.value / 2f) FloatingToolbarDirection.Right else FloatingToolbarDirection.Left
                    alongOpposite = if (primary == FloatingToolbarDirection.Right) FloatingToolbarDirection.Left else FloatingToolbarDirection.Right
                    inwardPerpendicular = FloatingToolbarDirection.Up
                }
            }

            val (primarySpace, primaryOccluded) = metrics(primary)
            val chosen =
                if (!primaryOccluded) {
                    primary
                } else {
                    val (oppositeSpace, _) = metrics(alongOpposite)
                    val (perpSpace, _) = metrics(inwardPerpendicular)
                    val bestAlong = if (oppositeSpace > primarySpace) alongOpposite else primary
                    val bestAlongSpace = maxOf(oppositeSpace, primarySpace)
                    if (ToolbarLayout.floatingSlots(bestAlongSpace, itemWidth, closes) > 0) {
                        bestAlong
                    } else {
                        if (perpSpace > bestAlongSpace) inwardPerpendicular else bestAlong
                    }
                }
            return FloatingToolbarPlacement(
                ballCenter = center,
                direction = chosen,
                slots = ToolbarLayout.floatingSlots(metrics(chosen).first, itemWidth, closes),
            )
        }
    }
}
