package io.nekohasekai.screensharing.sessionui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.model.ToolbarLayout
import io.nekohasekai.screensharing.model.ToolbarPlan
import io.nekohasekai.screensharing.session.Letterbox
import io.nekohasekai.screensharing.session.SessionController
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

private val LocalBarVertical = staticCompositionLocalOf { false }

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
        Floating(controller, plan, closes, onClose)
    }
}

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
            modifier = if (left) Modifier.absolutePadding(right = margin) else Modifier.padding(bottom = margin),
        )
    }
}

@Composable
private fun Floating(
    controller: SessionController,
    plan: ToolbarPlan,
    closes: Boolean,
    onClose: () -> Unit,
) {
    val visible = controller.isToolbarVisible
    val interactionSource = remember { MutableInteractionSource() }
    DisposableEffect(controller) {
        onDispose { controller.toolbarHandleFrame = null }
    }
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(88.dp, 48.dp)
                    .onGloballyPositioned { controller.toolbarHandleFrame = it.boundsInWindow() }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = !controller.isPointerLocked,
                        onClickLabel = stringResource(if (visible) R.string.chrome_hide_toolbar else R.string.chrome_show_toolbar),
                        role = Role.Button,
                        onClick = controller::toggleToolbar,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    Modifier
                        .padding(top = 8.dp)
                        .size(40.dp, 16.dp)
                        .indication(interactionSource, ripple()),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 2.dp,
                ) {
                    Icon(
                        if (visible) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = stringResource(if (visible) R.string.chrome_hide_toolbar else R.string.chrome_show_toolbar),
                        modifier =
                        Modifier
                            .padding(horizontal = 12.dp)
                            .size(16.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                Bar(
                    controller,
                    plan,
                    closes,
                    onClose,
                    vertical = false,
                    modifier = Modifier.onGloballyPositioned { if (controller.isToolbarVisible) controller.toolbarBarFrame = it.boundsInWindow() },
                )
            }
        }
    }
}

@Composable
private fun Bar(
    controller: SessionController,
    plan: ToolbarPlan,
    closes: Boolean,
    onClose: () -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    val more = rememberSessionMenuState(SessionMenuPage.More)
    Surface(
        modifier,
        shape = RoundedCornerShape(12.dp + ToolbarLayout.capsuleGroupPadding),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
    ) {
        CompositionLocalProvider(LocalBarVertical provides vertical) {
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
        positionProvider =
        TooltipDefaults.rememberTooltipPositionProvider(
            if (LocalBarVertical.current) TooltipAnchorPosition.Right else TooltipAnchorPosition.Below,
        ),
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
