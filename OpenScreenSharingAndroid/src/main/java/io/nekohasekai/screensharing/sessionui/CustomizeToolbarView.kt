package io.nekohasekai.screensharing.sessionui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertHost
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.FormDialog
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.SectionHeader
import io.nekohasekai.screensharing.model.ToolbarLayout
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.settings.CloseButton
import sh.calvin.reorderable.ReorderableColumn

@Composable
fun CustomizeToolbarView(
    controller: SessionController,
    slots: Int?,
    onDismiss: () -> Unit,
) {
    val layout = ToolbarLayout.layout(controller)
    val plan = layout.plan(controller, slots)
    val categories =
        SessionCommandCategory.entries
            .map { category -> category to layout.otherCommands(category) }
            .filter { (_, commands) -> commands.isNotEmpty() }
    val haptic = LocalHapticFeedback.current
    var isConfirmingReset by remember { mutableStateOf(false) }
    val moveUp = stringResource(R.string.home_move_up)
    val moveDown = stringResource(R.string.home_move_down)
    val removeLabel = stringResource(R.string.chrome_remove_from_toolbar)
    val notAvailable = stringResource(R.string.home_not_available)

    FormDialog(onDismissRequest = onDismiss) {
        PageScaffold(
            title = stringResource(R.string.model_customize_toolbar),
            navigationIcon = { CloseButton(onDismiss) },
            actions = {
                var isShowingMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { isShowingMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.settings_more))
                    }
                    DropdownMenu(expanded = isShowingMenu, onDismissRequest = { isShowingMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chrome_reset_to_default)) },
                            leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                            enabled = layout.isCustomized,
                            onClick = {
                                isShowingMenu = false
                                if (layout.isDefault) layout.reset() else isConfirmingReset = true
                            },
                        )
                    }
                }
            },
        ) {
            ListGroup(clipContent = false) {
                ReorderableColumn(
                    list = layout.bar,
                    onSettle = { from, to -> layout.move(listOf(from), if (to > from) to + 1 else to) },
                    onMove = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                ) { _, command, isDragging ->
                    key(command) {
                        ReorderableItem {
                            val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "ToolbarItemElevation")
                            val offered = command.isOffered(controller)
                            Surface(
                                shadowElevation = elevation,
                                color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .padding(start = 4.dp, end = 16.dp)
                                        .semantics(mergeDescendants = true) {
                                            customActions =
                                                buildList {
                                                    if (command != layout.bar.firstOrNull()) {
                                                        add(
                                                            CustomAccessibilityAction(moveUp) {
                                                                layout.move(command, -1)
                                                                true
                                                            },
                                                        )
                                                    }
                                                    if (command != layout.bar.lastOrNull()) {
                                                        add(
                                                            CustomAccessibilityAction(moveDown) {
                                                                layout.move(command, 1)
                                                                true
                                                            },
                                                        )
                                                    }
                                                    add(
                                                        CustomAccessibilityAction(removeLabel) {
                                                            layout.remove(command)
                                                            true
                                                        },
                                                    )
                                                }
                                            if (!offered) stateDescription = notAvailable
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.DragHandle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier =
                                        Modifier
                                            .draggableHandle()
                                            .minimumInteractiveComponentSize(),
                                    )
                                    CommandRow(command, stringResource(command.title), offered, inMore = command in plan.overflow, modifier = Modifier.weight(1f))
                                    Switch(checked = true, onCheckedChange = { layout.remove(command) }, modifier = Modifier.padding(start = 16.dp))
                                }
                            }
                        }
                    }
                }
            }
            for ((category, commands) in categories) {
                key(category) {
                    SectionHeader(stringResource(category.title))
                    ListGroup {
                        for (command in commands) {
                            key(command) {
                                val title = stringResource(command.title)
                                val offered = command.isOffered(controller)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .toggleable(false, role = Role.Switch) { layout.add(command) }
                                        .semantics { if (!offered) contentDescription = "$title, $notAvailable" }
                                        .padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CommandRow(command, title, offered, inMore = false, modifier = Modifier.weight(1f))
                                    Switch(checked = false, onCheckedChange = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (isConfirmingReset) {
        AlertHost(
            AlertState(
                title = stringResource(R.string.chrome_reset_toolbar_confirm),
                actions =
                listOf(
                    AlertState.Action(stringResource(R.string.chrome_reset_toolbar), AlertState.Role.Destructive) { layout.reset() },
                    AlertState.Action(stringResource(R.string.cancel), AlertState.Role.Cancel) {},
                ),
            ),
        ) { isConfirmingReset = false }
    }
}

@Composable
private fun CommandRow(
    command: SessionCommand,
    title: String,
    offered: Boolean,
    inMore: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier.alpha(if (offered) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(command.systemImage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            title,
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (inMore) {
            Text(stringResource(R.string.chrome_in_more), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
