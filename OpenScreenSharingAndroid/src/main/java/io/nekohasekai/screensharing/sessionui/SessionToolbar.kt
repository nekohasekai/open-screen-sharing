package io.nekohasekai.screensharing.sessionui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.FloatingToolbarAnchor
import io.nekohasekai.screensharing.model.FloatingToolbarDirection
import io.nekohasekai.screensharing.model.FloatingToolbarEdge
import io.nekohasekai.screensharing.model.FloatingToolbarPlacement
import io.nekohasekai.screensharing.model.ToolbarLayout
import io.nekohasekai.screensharing.model.ToolbarPlan
import io.nekohasekai.screensharing.session.Letterbox
import io.nekohasekai.screensharing.session.SessionController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class ToolbarDock(
    val edge: Edge,
    val area: Rect,
) {
    enum class Edge {
        Left,
        Top,
    }

    val length: Float get() = if (edge == Edge.Left) area.height else area.width

    companion object {
        val barThickness: Dp = ToolbarLayout.itemSize + ToolbarLayout.capsuleGroupPadding * 2

        fun of(
            letterbox: Letterbox?,
            barThickness: Float,
            clearance: Float,
        ): ToolbarDock? {
            if (letterbox == null) return null
            val thickness = barThickness + 2 * clearance
            val top = letterbox.topBetweenCorners
            return when {
                letterbox.left.width >= thickness -> ToolbarDock(Edge.Left, letterbox.left)
                letterbox.top.height >= thickness -> ToolbarDock(Edge.Top, letterbox.top)
                top != null && top.height >= thickness -> ToolbarDock(Edge.Top, top)
                else -> null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private val LocalBarTooltipPosition = staticCompositionLocalOf { TooltipAnchorPosition.Below }

private val barItemShape = RoundedCornerShape(12.dp)

@Composable
fun BoxScope.FloatingToolbar(
    controller: SessionController,
    plan: ToolbarPlan,
    dock: ToolbarDock?,
    closes: Boolean,
    onClose: () -> Unit,
) {
    if (dock != null) {
        Docked(controller, plan, dock, closes, onClose)
    } else {
        Floating(controller, closes, onClose)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.Docked(
    controller: SessionController,
    plan: ToolbarPlan,
    dock: ToolbarDock,
    closes: Boolean,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val left = dock.edge == ToolbarDock.Edge.Left
    val area = dock.area
    val thickness = with(density) { (if (left) area.width else area.height).toDp() }
    val margin = minOf(ToolbarLayout.dockMargin, (thickness - ToolbarDock.barThickness) / 2)
    Box(
        Modifier
            .align(AbsoluteAlignment.TopLeft)
            .absoluteOffset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
            .size(with(density) { DpSize(area.width.toDp(), area.height.toDp()) }),
        contentAlignment = if (left) AbsoluteAlignment.CenterRight else Alignment.BottomCenter,
    ) {
        Bar(
            controller,
            plan,
            closes,
            onClose,
            vertical = left,
            tooltipPosition = if (left) TooltipAnchorPosition.Right else TooltipAnchorPosition.Below,
            modifier = if (left) Modifier.absolutePadding(right = margin) else Modifier.padding(bottom = margin),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Floating(
    controller: SessionController,
    closes: Boolean,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val visible = controller.isToolbarVisible
    val interactionSource = remember { MutableInteractionSource() }
    var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var ballCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragPoint by remember { mutableStateOf<Offset?>(null) }
    val snapAnimatable = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    val isSnapping = snapJob != null
    val anchor = AppSettings.floatingToolbarAnchor

    DisposableEffect(controller) {
        onDispose {
            controller.toolbarHandleFrame = null
            controller.isToolbarStashed = false
        }
    }
    val nearHandle = controller.isPointerNearToolbarHandle
    LaunchedEffect(visible, dragPoint != null, isSnapping, nearHandle, controller.isToolbarStashed, anchor) {
        if (visible || dragPoint != null || isSnapping || nearHandle) {
            if (controller.isToolbarStashed) controller.isToolbarStashed = false
            return@LaunchedEffect
        }
        if (controller.isToolbarStashed) return@LaunchedEffect
        delay(2200)
        controller.isToolbarStashed = true
    }

    val layoutDirection = LocalLayoutDirection.current
    val floatingInsets = WindowInsets.systemBars.union(WindowInsets.captionBar).union(WindowInsets.ime)
    val extraBottomPx = if (controller.isKeyboardVisible) with(density) { 64.dp.roundToPx() } else 0
    val insetLeftPx = floatingInsets.getLeft(density, layoutDirection)
    val insetTopPx = floatingInsets.getTop(density)
    val insetRightPx = floatingInsets.getRight(density, layoutDirection)
    val insetBottomPx = floatingInsets.getBottom(density) + extraBottomPx
    val touchesEdge =
        when (anchor.edge) {
            FloatingToolbarEdge.Left -> insetLeftPx == 0
            FloatingToolbarEdge.Right -> insetRightPx == 0
            FloatingToolbarEdge.Top -> insetTopPx == 0
            FloatingToolbarEdge.Bottom -> insetBottomPx == 0
        }
    val localDpObstructions =
        FloatingToolbarAnchor.localObstructions(
            obstructions = controller.screenObstructions,
            density = density.density,
            insetLeftPx = insetLeftPx,
            insetTopPx = insetTopPx,
            insetRightPx = insetRightPx,
            insetBottomPx = insetBottomPx,
        )
    val localPxObstructions =
        ScreenObstructions(
            occlusions =
            localDpObstructions.occlusions.map {
                Rect(
                    it.left * density.density,
                    it.top * density.density,
                    it.right * density.density,
                    it.bottom * density.density,
                )
            },
            topLeft = localDpObstructions.topLeft * density.density,
            topRight = localDpObstructions.topRight * density.density,
            bottomLeft = localDpObstructions.bottomLeft * density.density,
            bottomRight = localDpObstructions.bottomRight * density.density,
            density = density.density,
        )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(floatingInsets)
            .padding(bottom = if (controller.isKeyboardVisible) 64.dp else 0.dp),
    ) {
        val placement =
            FloatingToolbarPlacement.of(
                width = maxWidth,
                height = maxHeight,
                anchor = anchor,
                itemWidth = ToolbarLayout.itemSize,
                closes = closes,
                obstructions = localDpObstructions,
            )
        val floatingPlan = ToolbarLayout.layout(controller).plan(controller, placement.slots)
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val ballSizePx = with(density) { ToolbarLayout.itemSize.toPx() }
        val stashVisibleThicknessPx = with(density) { ToolbarLayout.stashVisibleThickness.toPx() }
        val stashCornerRadiusPx = with(density) { ToolbarLayout.stashCornerRadius.toPx() }
        val stashOverhangPx = stashCornerRadiusPx
        val stashHitThicknessPx = with(density) { ToolbarLayout.stashHitThickness.toPx() }
        val edgeMarginPx = with(density) { ToolbarLayout.floatingMargin.toPx() }
        val ballMarginPx = with(density) { (ToolbarLayout.floatingMargin + ToolbarLayout.capsuleGroupPadding).toPx() }
        val clearancePx = with(density) { ToolbarLayout.dockClearance.toPx() }
        val gapPx = with(density) { ToolbarLayout.capsuleGroupSpacing.toPx() }
        val snappedCenterPx =
            with(density) {
                Offset(placement.ballCenter.x.dp.toPx(), placement.ballCenter.y.dp.toPx())
            }
        val displayedCenter =
            when {
                dragPoint != null -> dragPoint!!
                isSnapping -> snapAnimatable.value
                else -> snappedCenterPx
            }
        val stashedAlongPx =
            if (touchesEdge) {
                anchor.stashedAlong(
                    snappedCenterPx,
                    widthPx,
                    heightPx,
                    ballSizePx,
                    stashVisibleThicknessPx,
                    edgeMarginPx,
                    clearancePx,
                    localPxObstructions,
                )
            } else {
                null
            }
        val stashed = controller.isToolbarStashed && !visible && dragPoint == null && !isSnapping
        val stashedAsTab = stashed && stashedAlongPx != null
        val dimmedAsBall = stashed && stashedAlongPx == null
        val stashProgress by animateFloatAsState(
            targetValue = if (stashedAsTab) 1f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        )
        val ballAlpha by animateFloatAsState(
            targetValue = if (dimmedAsBall) 0.45f else 1f,
            animationSpec = tween(durationMillis = 250),
        )

        val halfBallPx = ballSizePx / 2f
        val effectiveAlongPx =
            stashedAlongPx
                ?: if (anchor.edge == FloatingToolbarEdge.Left || anchor.edge == FloatingToolbarEdge.Right) {
                    displayedCenter.y
                } else {
                    displayedCenter.x
                }
        val stashTotalThicknessPx = stashVisibleThicknessPx + stashOverhangPx
        val hitLeft0 = displayedCenter.x - halfBallPx
        val hitTop0 = displayedCenter.y - halfBallPx
        val hitLeft1: Float
        val hitTop1: Float
        val hitW1: Float
        val hitH1: Float
        val glassW1: Float
        val glassH1: Float
        val glassOffsetX1: Float
        val glassOffsetY1: Float
        when (anchor.edge) {
            FloatingToolbarEdge.Left -> {
                hitLeft1 = 0f
                hitTop1 = effectiveAlongPx - halfBallPx
                hitW1 = stashHitThicknessPx
                hitH1 = ballSizePx
                glassW1 = stashTotalThicknessPx
                glassH1 = ballSizePx
                glassOffsetX1 = -stashOverhangPx
                glassOffsetY1 = 0f
            }
            FloatingToolbarEdge.Right -> {
                hitLeft1 = widthPx - stashHitThicknessPx
                hitTop1 = effectiveAlongPx - halfBallPx
                hitW1 = stashHitThicknessPx
                hitH1 = ballSizePx
                glassW1 = stashTotalThicknessPx
                glassH1 = ballSizePx
                glassOffsetX1 = stashHitThicknessPx - stashVisibleThicknessPx
                glassOffsetY1 = 0f
            }
            FloatingToolbarEdge.Top -> {
                hitLeft1 = effectiveAlongPx - halfBallPx
                hitTop1 = 0f
                hitW1 = ballSizePx
                hitH1 = stashHitThicknessPx
                glassW1 = ballSizePx
                glassH1 = stashTotalThicknessPx
                glassOffsetX1 = 0f
                glassOffsetY1 = -stashOverhangPx
            }
            FloatingToolbarEdge.Bottom -> {
                hitLeft1 = effectiveAlongPx - halfBallPx
                hitTop1 = heightPx - stashHitThicknessPx
                hitW1 = ballSizePx
                hitH1 = stashHitThicknessPx
                glassW1 = ballSizePx
                glassH1 = stashTotalThicknessPx
                glassOffsetX1 = 0f
                glassOffsetY1 = stashHitThicknessPx - stashVisibleThicknessPx
            }
        }
        val hitLeft = hitLeft0 + (hitLeft1 - hitLeft0) * stashProgress
        val hitTop = hitTop0 + (hitTop1 - hitTop0) * stashProgress
        val hitW = ballSizePx + (hitW1 - ballSizePx) * stashProgress
        val hitH = ballSizePx + (hitH1 - ballSizePx) * stashProgress
        val glassW = ballSizePx + (glassW1 - ballSizePx) * stashProgress
        val glassH = ballSizePx + (glassH1 - ballSizePx) * stashProgress
        val glassOffsetX = glassOffsetX1 * stashProgress
        val glassOffsetY = glassOffsetY1 * stashProgress
        val cornerRadiusPx = halfBallPx + (stashCornerRadiusPx - halfBallPx) * stashProgress
        val handleCenter = Offset(hitLeft + hitW / 2f, hitTop + hitH / 2f)

        val currentWidthPx by rememberUpdatedState(widthPx)
        val currentHeightPx by rememberUpdatedState(heightPx)
        val currentSnappedCenter by rememberUpdatedState(snappedCenterPx)
        val currentHandleCenter by rememberUpdatedState(handleCenter)
        val currentPxObstructions by rememberUpdatedState(localPxObstructions)

        val transformOrigin =
            when (placement.direction) {
                FloatingToolbarDirection.Right -> TransformOrigin(0f, 0.5f)
                FloatingToolbarDirection.Left -> TransformOrigin(1f, 0.5f)
                FloatingToolbarDirection.Down -> TransformOrigin(0.5f, 0f)
                FloatingToolbarDirection.Up -> TransformOrigin(0.5f, 1f)
            }
        val tooltipPosition =
            if (placement.direction.vertical) {
                if (snappedCenterPx.x > widthPx / 2f) TooltipAnchorPosition.Left else TooltipAnchorPosition.Right
            } else {
                if (snappedCenterPx.y > heightPx / 2f) TooltipAnchorPosition.Above else TooltipAnchorPosition.Below
            }

        val label = stringResource(if (visible) R.string.chrome_hide_toolbar else R.string.chrome_show_toolbar)
        val dragModifier =
            if (!controller.isPointerLocked) {
                Modifier.pointerInput(density) {
                    val velocityTracker = VelocityTracker()
                    var grabOffset = Offset.Zero

                    fun finish(projected: Offset) {
                        val nextAnchor =
                            FloatingToolbarAnchor.snap(
                                projected,
                                currentWidthPx,
                                currentHeightPx,
                                ballSizePx,
                                ballMarginPx,
                                clearancePx,
                                currentPxObstructions,
                            )
                        val target =
                            nextAnchor.point(
                                currentWidthPx,
                                currentHeightPx,
                                ballSizePx,
                                ballMarginPx,
                                clearancePx,
                                currentPxObstructions,
                            )
                        val start = dragPoint ?: currentHandleCenter
                        AppSettings.floatingToolbarAnchor = nextAnchor
                        dragPoint = null
                        snapJob?.cancel()
                        var job: Job? = null
                        job =
                            scope.launch {
                                try {
                                    snapAnimatable.snapTo(start)
                                    snapAnimatable.animateTo(
                                        target,
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                } finally {
                                    if (snapJob === job) {
                                        snapJob = null
                                    }
                                }
                            }
                        snapJob = job
                    }

                    detectDragGestures(
                        onDragStart = { startOffset ->
                            velocityTracker.resetTracking()
                            val rootFinger = ballCoords?.localToRoot(startOffset) ?: return@detectDragGestures
                            val rootContainer = containerCoords?.localToRoot(Offset.Zero) ?: Offset.Zero
                            val startCenter = currentHandleCenter
                            snapJob?.cancel()
                            snapJob = null
                            grabOffset = (rootFinger - rootContainer) - startCenter
                            controller.isToolbarStashed = false
                            dragPoint =
                                FloatingToolbarAnchor.clampBall(
                                    startCenter,
                                    currentWidthPx,
                                    currentHeightPx,
                                    ballSizePx,
                                    ballMarginPx,
                                    clearancePx,
                                    currentPxObstructions,
                                )
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val rootFinger = ballCoords?.localToRoot(change.position) ?: return@detectDragGestures
                            val rootContainer = containerCoords?.localToRoot(Offset.Zero) ?: Offset.Zero
                            velocityTracker.addPosition(change.uptimeMillis, rootFinger)
                            dragPoint =
                                FloatingToolbarAnchor.clampBall(
                                    (rootFinger - rootContainer) - grabOffset,
                                    currentWidthPx,
                                    currentHeightPx,
                                    ballSizePx,
                                    ballMarginPx,
                                    clearancePx,
                                    currentPxObstructions,
                                )
                        },
                        onDragEnd = {
                            val end = dragPoint ?: currentSnappedCenter
                            val velocity = velocityTracker.calculateVelocity()
                            finish(Offset(end.x + velocity.x * 0.08f, end.y + velocity.y * 0.08f))
                        },
                        onDragCancel = {
                            finish(dragPoint ?: currentSnappedCenter)
                        },
                    )
                }
            } else {
                Modifier
            }

        Layout(
            modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onGloballyPositioned { containerCoords = it },
            content = {
                Box(
                    modifier =
                    Modifier
                        .systemGestureExclusion()
                        .onGloballyPositioned {
                            ballCoords = it
                            controller.toolbarHandleFrame = it.boundsInWindow()
                        }
                        .then(dragModifier)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = !controller.isPointerLocked,
                            onClickLabel = label,
                            role = Role.Button,
                            onClick = controller::tapToolbarHandle,
                        ),
                    contentAlignment = AbsoluteAlignment.TopLeft,
                ) {
                    AssistiveBall(
                        label = label,
                        edge = anchor.edge,
                        stashProgress = stashProgress,
                        stashOverhangPx = stashOverhangPx,
                        ballSizePx = ballSizePx,
                        cornerRadiusPx = cornerRadiusPx,
                        interactionSource = interactionSource,
                        alpha = ballAlpha,
                        modifier =
                        Modifier.layout { measurable, _ ->
                            val w = glassW.roundToInt().coerceAtLeast(1)
                            val h = glassH.roundToInt().coerceAtLeast(1)
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(w, h) {
                                placeable.place(glassOffsetX.roundToInt(), glassOffsetY.roundToInt())
                            }
                        },
                    )
                }
                AnimatedVisibility(
                    visible = visible && dragPoint == null && !isSnapping,
                    enter = scaleIn(initialScale = 0.85f, transformOrigin = transformOrigin) + fadeIn(),
                    exit = scaleOut(targetScale = 0.85f, transformOrigin = transformOrigin) + fadeOut(),
                ) {
                    Bar(
                        controller,
                        floatingPlan,
                        closes,
                        onClose,
                        vertical = placement.direction.vertical,
                        tooltipPosition = tooltipPosition,
                        modifier = Modifier.onGloballyPositioned { if (controller.isToolbarVisible) controller.toolbarBarFrame = it.boundsInWindow() },
                    )
                }
            },
        ) { measurables, constraints ->
            val fixedHitW = hitW.roundToInt().coerceAtLeast(1)
            val fixedHitH = hitH.roundToInt().coerceAtLeast(1)
            val ballPlaceable = measurables[0].measure(Constraints.fixed(fixedHitW, fixedHitH))
            val barPlaceable =
                measurables.getOrNull(1)?.measure(
                    Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight),
                )
            val ballX = hitLeft.roundToInt()
            val ballY = hitTop.roundToInt()
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (barPlaceable != null && barPlaceable.width > 0 && barPlaceable.height > 0) {
                    val anchorCenter = snappedCenterPx
                    val clampedBarX =
                        FloatingToolbarAnchor.clamp(
                            anchorCenter.x - barPlaceable.width / 2f,
                            edgeMarginPx,
                            constraints.maxWidth - edgeMarginPx - barPlaceable.width,
                        ).roundToInt()
                    val clampedBarY =
                        FloatingToolbarAnchor.clamp(
                            anchorCenter.y - barPlaceable.height / 2f,
                            edgeMarginPx,
                            constraints.maxHeight - edgeMarginPx - barPlaceable.height,
                        ).roundToInt()
                    val (barX, barY) =
                        when (placement.direction) {
                            FloatingToolbarDirection.Right ->
                                (anchorCenter.x + ballSizePx / 2f + gapPx).roundToInt() to clampedBarY
                            FloatingToolbarDirection.Left ->
                                (anchorCenter.x - ballSizePx / 2f - gapPx - barPlaceable.width).roundToInt() to clampedBarY
                            FloatingToolbarDirection.Down ->
                                clampedBarX to (anchorCenter.y + ballSizePx / 2f + gapPx).roundToInt()
                            FloatingToolbarDirection.Up ->
                                clampedBarX to (anchorCenter.y - ballSizePx / 2f - gapPx - barPlaceable.height).roundToInt()
                        }
                    barPlaceable.place(barX, barY)
                }
                ballPlaceable.place(ballX, ballY)
            }
        }
    }
}

@Composable
private fun AssistiveBall(
    label: String,
    edge: FloatingToolbarEdge,
    stashProgress: Float,
    stashOverhangPx: Float,
    ballSizePx: Float,
    cornerRadiusPx: Float,
    interactionSource: MutableInteractionSource,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Surface(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .indication(interactionSource, ripple()),
        shape = RoundedCornerShape(with(density) { cornerRadiusPx.toDp() }),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
    ) {
        val contentColor = LocalContentColor.current
        Canvas(Modifier.fillMaxSize(), contentDescription = label) {
            val unit = ballSizePx / 44f
            val ballVisibility = 1f - stashProgress
            if (ballVisibility > 0f) {
                val scale = 0.6f + 0.4f * ballVisibility
                drawCircle(contentColor.copy(alpha = 0.20f * ballVisibility), radius = 14f * unit * scale)
                drawCircle(contentColor.copy(alpha = 0.38f * ballVisibility), radius = 10.5f * unit * scale)
                drawCircle(contentColor.copy(alpha = 0.85f * ballVisibility), radius = 7f * unit * scale)
            }
            if (stashProgress > 0f) {
                val shift = stashOverhangPx / 2f * stashProgress
                val cx =
                    when (edge) {
                        FloatingToolbarEdge.Left -> center.x + shift
                        FloatingToolbarEdge.Right -> center.x - shift
                        FloatingToolbarEdge.Top, FloatingToolbarEdge.Bottom -> center.x
                    }
                val cy =
                    when (edge) {
                        FloatingToolbarEdge.Top -> center.y + shift
                        FloatingToolbarEdge.Bottom -> center.y - shift
                        FloatingToolbarEdge.Left, FloatingToolbarEdge.Right -> center.y
                    }
                val halfSpan = 6.5f * unit
                val halfDepth = 2.5f * unit
                val path =
                    Path().apply {
                        when (edge) {
                            FloatingToolbarEdge.Left -> {
                                moveTo(cx - halfDepth, cy - halfSpan)
                                lineTo(cx + halfDepth, cy)
                                lineTo(cx - halfDepth, cy + halfSpan)
                            }
                            FloatingToolbarEdge.Right -> {
                                moveTo(cx + halfDepth, cy - halfSpan)
                                lineTo(cx - halfDepth, cy)
                                lineTo(cx + halfDepth, cy + halfSpan)
                            }
                            FloatingToolbarEdge.Top -> {
                                moveTo(cx - halfSpan, cy - halfDepth)
                                lineTo(cx, cy + halfDepth)
                                lineTo(cx + halfSpan, cy - halfDepth)
                            }
                            FloatingToolbarEdge.Bottom -> {
                                moveTo(cx - halfSpan, cy + halfDepth)
                                lineTo(cx, cy - halfDepth)
                                lineTo(cx + halfSpan, cy + halfDepth)
                            }
                        }
                    }
                drawPath(
                    path = path,
                    color = contentColor.copy(alpha = 0.85f * stashProgress),
                    style = Stroke(width = 2.4f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(
    controller: SessionController,
    plan: ToolbarPlan,
    closes: Boolean,
    onClose: () -> Unit,
    vertical: Boolean,
    tooltipPosition: TooltipAnchorPosition,
    modifier: Modifier = Modifier,
) {
    val more = rememberSessionMenuState(SessionMenuPage.More)
    Surface(
        modifier,
        shape = RoundedCornerShape(12.dp + ToolbarLayout.capsuleGroupPadding),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
    ) {
        CompositionLocalProvider(LocalBarTooltipPosition provides tooltipPosition) {
            AxisStack(vertical, Modifier.padding(ToolbarLayout.capsuleGroupPadding), ToolbarLayout.capsuleGroupSpacing) {
                if (closes) {
                    BarButton(stringResource(R.string.disconnect), Icons.Outlined.Close, onClick = onClose)
                }
                if (plan.inBar.isNotEmpty()) {
                    AxisStack(vertical, Modifier, 0.dp) {
                        for (command in plan.inBar) {
                            key(command) { SessionCommandView(command, controller, SessionCommandStyle.BarItem) }
                        }
                    }
                }
                Box {
                    BarButton(stringResource(R.string.settings_more), Icons.Outlined.MoreVert) { more.open() }
                    SessionMenu(controller, more)
                }
            }
        }
    }
}

@Composable
private fun AxisStack(
    vertical: Boolean,
    modifier: Modifier,
    spacing: Dp,
    content: @Composable () -> Unit,
) {
    if (vertical) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarTooltip(
    label: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(LocalBarTooltipPosition.current),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}

@Composable
fun BarButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    BarTooltip(label) {
        IconButton(
            onClick = onClick,
            modifier =
            Modifier
                .size(ToolbarLayout.itemSize)
                .semantics { if (selected) this.selected = true },
            enabled = enabled,
            shape = barItemShape,
            colors =
            if (selected) {
                IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                IconButtonDefaults.iconButtonColors()
            },
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
fun BarToggle(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    BarTooltip(label) {
        IconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(ToolbarLayout.itemSize),
            enabled = enabled,
            shape = barItemShape,
            colors =
            IconButtonDefaults.iconToggleButtonColors(
                containerColor = Color.Transparent,
                contentColor = LocalContentColor.current,
                checkedContainerColor = MaterialTheme.colorScheme.primary,
                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}
