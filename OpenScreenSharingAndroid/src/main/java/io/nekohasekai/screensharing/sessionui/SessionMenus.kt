package io.nekohasekai.screensharing.sessionui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.model.ActionShortcut
import io.nekohasekai.screensharing.model.KeyShortcut
import io.nekohasekai.screensharing.model.QuickInputs
import io.nekohasekai.screensharing.model.SessionQuality
import io.nekohasekai.screensharing.model.ShortcutAction
import io.nekohasekai.screensharing.session.DisplaySelection
import io.nekohasekai.screensharing.session.ScaleMode
import io.nekohasekai.screensharing.session.SessionCapabilities
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.session.SystemCommand
import io.nekohasekai.screensharing.session.VirtualDisplayMode
import java.text.NumberFormat

sealed interface SessionMenuPage {
    data object More : SessionMenuPage

    data class Command(val command: SessionCommand) : SessionMenuPage

    data object FunctionKeys : SessionMenuPage
}

class SessionMenuState(
    val root: SessionMenuPage,
) {
    var expanded by mutableStateOf(false)
        private set
    var expandedCategory by mutableStateOf<SessionCommandCategory?>(null)
        private set
    var expandedCommand by mutableStateOf<SessionCommand?>(null)
        private set

    fun open() {
        expandedCategory = null
        expandedCommand = null
        expanded = true
    }

    fun toggle(category: SessionCommandCategory) {
        expandedCategory = if (expandedCategory == category) null else category
        expandedCommand = null
    }

    fun toggle(command: SessionCommand) {
        expandedCommand = if (expandedCommand == command) null else command
    }

    fun dismiss() {
        expanded = false
    }
}

@Composable
fun rememberSessionMenuState(root: SessionMenuPage): SessionMenuState = remember(root) { SessionMenuState(root) }

private val LocalMenuIndent = compositionLocalOf { 0.dp }

private val menuItemPadding: PaddingValues
    @Composable get() = PaddingValues(start = 12.dp + LocalMenuIndent.current, end = 12.dp)

@Composable
fun SessionMenu(
    controller: SessionController,
    state: SessionMenuState,
    properties: PopupProperties = PopupProperties(focusable = true),
) {
    DropdownMenu(expanded = state.expanded, onDismissRequest = state::dismiss, properties = properties) {
        when (val root = state.root) {
            SessionMenuPage.More -> {
                SessionMoreMenuContent(controller, state)
            }
            is SessionMenuPage.Command -> {
                val enabled = root.command.isEnabled(controller)
                when (root.command) {
                    SessionCommand.Displays -> DisplayPicker(controller, inOwnMenu = true, enabled, state)
                    SessionCommand.Quality -> QualityPicker(controller, inOwnMenu = true, enabled, state)
                    SessionCommand.Clipboard -> ClipboardMenuItems(controller, enabled, state)
                    SessionCommand.QuickInput -> QuickInputItems(controller, state)
                    else -> Unit
                }
            }
            SessionMenuPage.FunctionKeys -> {
                FunctionKeyItems(controller, state)
            }
        }
    }
}

@Composable
fun SessionMenuItem(
    text: String,
    icon: ImageVector?,
    enabled: Boolean = true,
    shortcut: ShortcutAction? = null,
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        modifier =
        if (checked == null) {
            Modifier
        } else {
            Modifier.semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(checked)
            }
        },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionShortcut(shortcut)
                if (checked != null) Checkbox(checked, onCheckedChange = null, enabled = enabled)
            }
        },
        enabled = enabled,
        contentPadding = menuItemPadding,
    )
}

@Composable
fun ExpandableMenuItem(
    text: String,
    icon: ImageVector,
    expanded: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val indent = LocalMenuIndent.current
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onToggle,
        modifier =
        Modifier.semantics {
            val action = {
                onToggle()
                true
            }
            if (expanded) collapse(action = action) else expand(action = action)
        },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null) },
        enabled = enabled,
        contentPadding = menuItemPadding,
    )
    if (expanded) {
        CompositionLocalProvider(LocalMenuIndent provides indent + 24.dp, content = content)
    }
}

@Composable
private fun MenuGroupLabel(text: String) {
    Text(
        text,
        Modifier
            .padding(start = 12.dp + LocalMenuIndent.current, end = 12.dp, top = 8.dp, bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PickerItem(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    shortcut: ShortcutAction?,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        modifier =
        Modifier.semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        leadingIcon = {
            Icon(
                if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            )
        },
        trailingIcon = { ActionShortcut(shortcut) },
        enabled = enabled,
        contentPadding = menuItemPadding,
    )
}

@Composable
fun ScaleButton(
    controller: SessionController,
    style: SessionCommandStyle,
    enabled: Boolean,
    menu: SessionMenuState?,
) {
    val fit = controller.scaleMode == ScaleMode.Fit
    val label = stringResource(if (fit) R.string.chrome_zoom_to_actual_size else R.string.chrome_zoom_to_fit)
    val icon = if (fit) Icons.Outlined.OpenInFull else Icons.Outlined.CloseFullscreen
    val scale = if (fit) ScaleMode.ActualSize else ScaleMode.Fit
    if (style.isBar) {
        BarButton(label, icon, enabled) { controller.setScale(scale) }
    } else {
        SessionMenuItem(label, icon, enabled, ShortcutAction.ActualSize) {
            menu?.dismiss()
            controller.setScale(scale)
        }
    }
}

@Composable
fun DisplayPicker(
    controller: SessionController,
    inOwnMenu: Boolean,
    enabled: Boolean,
    menu: SessionMenuState,
) {
    if (!inOwnMenu) MenuGroupLabel(stringResource(SessionCommand.Displays.title))
    controller.displays.forEachIndexed { index, display ->
        key(display.id) {
            val selection = DisplaySelection.Display(display.id)
            PickerItem(display.name, controller.displaySelection == selection, enabled, ShortcutAction.display(index)) {
                menu.dismiss()
                controller.selectDisplay(selection)
            }
        }
    }
    PickerItem(
        stringResource(if (inOwnMenu) R.string.chrome_all else R.string.model_all_displays),
        controller.displaySelection == DisplaySelection.All,
        enabled,
        ShortcutAction.AllDisplays,
    ) {
        menu.dismiss()
        controller.selectDisplay(DisplaySelection.All)
    }
}

@get:StringRes
val SessionQuality.title: Int
    get() =
        when (this) {
            SessionQuality.Adaptive -> R.string.model_adaptive_quality
            SessionQuality.Full -> R.string.model_full_quality
        }

@get:StringRes
val SessionQuality.shortTitle: Int
    get() =
        when (this) {
            SessionQuality.Adaptive -> R.string.chrome_adaptive
            SessionQuality.Full -> R.string.chrome_full
        }

@Composable
fun QualityPicker(
    controller: SessionController,
    inOwnMenu: Boolean,
    enabled: Boolean,
    menu: SessionMenuState,
) {
    if (!inOwnMenu) MenuGroupLabel(stringResource(SessionCommand.Quality.title))
    for (quality in SessionQuality.entries) {
        PickerItem(
            stringResource(if (inOwnMenu) quality.shortTitle else quality.title),
            controller.quality == quality,
            enabled,
            ShortcutAction.quality(quality),
        ) {
            menu.dismiss()
            controller.setQuality(quality)
        }
    }
}

@Composable
fun resolutionScaleTitle(scale: Double): String {
    if (scale == 1.0) return stringResource(R.string.chrome_native)
    val format =
        remember {
            NumberFormat.getNumberInstance().apply {
                minimumFractionDigits = 0
                maximumFractionDigits = 2
            }
        }
    return stringResource(R.string.chrome_scale, format.format(scale))
}

@Composable
fun ResolutionPanel(
    controller: SessionController,
    onDismiss: () -> Unit,
) {
    var draggedScale by remember { mutableStateOf<Double?>(null) }
    val scale = draggedScale ?: controller.resolutionScale
    val range = VirtualDisplayMode.resolutionScaleRange
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chrome_resolution), Modifier.weight(1f))
                Text(
                    resolutionScaleTitle(scale),
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = scale.toFloat(),
                    onValueChange = { draggedScale = it.toDouble() },
                    valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                    steps = ((range.endInclusive - range.start) / VirtualDisplayMode.RESOLUTION_SCALE_STEP).toInt() - 1,
                    onValueChangeFinished = {
                        draggedScale?.let { controller.setResolutionScale(it) }
                        draggedScale = null
                    },
                )
                Text(
                    stringResource(R.string.chrome_resolution_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
fun ClipboardMenuItems(
    controller: SessionController,
    enabled: Boolean,
    menu: SessionMenuState,
) {
    val sharing = controller.clipboardSharing
    SessionMenuItem(stringResource(R.string.model_sync_clipboard), Icons.Outlined.SwapVert, enabled, ShortcutAction.SyncClipboard, checked = sharing) {
        menu.dismiss()
        controller.setClipboardSharing(!sharing)
    }
    HorizontalDivider(Modifier.padding(start = LocalMenuIndent.current))
    SessionMenuItem(
        stringResource(R.string.model_receive_clipboard),
        Icons.Outlined.Download,
        enabled && !sharing && SessionCapabilities.ClipboardRequest in controller.capabilities,
        ShortcutAction.ReceiveClipboard,
    ) {
        menu.dismiss()
        controller.getClipboardFromMac()
    }
    SessionMenuItem(stringResource(R.string.model_send_clipboard), Icons.Outlined.Upload, enabled && !sharing, ShortcutAction.SendClipboard) {
        menu.dismiss()
        controller.sendClipboardToMac()
    }
}

@Composable
fun QuickInputItems(
    controller: SessionController,
    menu: SessionMenuState,
) {
    val target = controller.mappingTarget
    val inputs = QuickInputs.inputs.filter { it.steps(target).isNotEmpty() }
    for (input in inputs) {
        key(input.id) {
            DropdownMenuItem(
                text = { Text(input.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = {
                    menu.dismiss()
                    controller.perform(input)
                },
                contentPadding = menuItemPadding,
            )
        }
    }
    if (inputs.isNotEmpty()) HorizontalDivider(Modifier.padding(start = LocalMenuIndent.current))
    DropdownMenuItem(
        text = { Text(stringResource(R.string.edit)) },
        onClick = {
            menu.dismiss()
            controller.isEditingQuickInputs = true
        },
        contentPadding = menuItemPadding,
    )
}

@Composable
fun FunctionKeyItems(
    controller: SessionController,
    menu: SessionMenuState,
) {
    val target = controller.mappingTarget
    for (namedKey in KeyShortcut.KeyGroup.FunctionKeys.keys(target)) {
        val output = namedKey.output
        if (output != null) {
            DropdownMenuItem(
                text = { Text(namedKey.label(target)) },
                onClick = {
                    menu.dismiss()
                    controller.typeKey(output)
                },
            )
        }
    }
}

@get:StringRes
val SystemCommand.title: Int
    get() =
        when (this) {
            SystemCommand.MissionControl -> R.string.model_mission_control
            SystemCommand.AppWindows -> R.string.model_app_windows
            SystemCommand.ShowDesktop -> R.string.model_desktop
            SystemCommand.Launchpad -> R.string.model_apps
        }

val SystemCommand.systemImage: ImageVector
    get() =
        when (this) {
            SystemCommand.MissionControl -> Icons.Outlined.Dashboard
            SystemCommand.AppWindows -> Icons.Outlined.FilterNone
            SystemCommand.ShowDesktop -> Icons.Outlined.Wallpaper
            SystemCommand.Launchpad -> Icons.Outlined.Apps
        }

@Composable
fun SessionMoreMenuContent(
    controller: SessionController,
    menu: SessionMenuState,
) {
    for (category in SessionCommandCategory.entries) {
        if (category.commands.any { it.isOffered(controller) }) {
            key(category) {
                ExpandableMenuItem(
                    stringResource(category.title),
                    category.systemImage,
                    expanded = menu.expandedCategory == category,
                    onToggle = { menu.toggle(category) },
                ) {
                    CategoryMenuContent(category, controller, menu)
                }
            }
        }
    }
    HorizontalDivider()
    SessionMenuItem(stringResource(R.string.model_customize_toolbar), Icons.Outlined.Tune, shortcut = ShortcutAction.CustomizeToolbar) {
        menu.dismiss()
        controller.isCustomizingToolbar = true
    }
}

@Composable
private fun CategoryMenuContent(
    category: SessionCommandCategory,
    controller: SessionController,
    menu: SessionMenuState,
) {
    val commands = category.commands.filter { it.isOffered(controller) }
    val sections =
        when (category) {
            SessionCommandCategory.View -> {
                val local = listOf(SessionCommand.ZoomMode, SessionCommand.Scale)
                val pickers = listOf(SessionCommand.Displays, SessionCommand.Quality)
                listOf(commands.filter { it in local }) + pickers.map { picker -> commands.filter { it == picker } } +
                    listOf(commands.filter { it !in local && it !in pickers })
            }
            SessionCommandCategory.Control -> {
                val local = listOf(SessionCommand.Observe, SessionCommand.PointerLock, SessionCommand.NativeTouch, SessionCommand.Keyboard)
                listOf(commands.filter { it in local }, commands.filter { it == SessionCommand.QuickInput }, commands.filter { it !in local && it != SessionCommand.QuickInput })
            }
            else -> {
                listOf(commands)
            }
        }
    sections.filter { it.isNotEmpty() }.forEachIndexed { index, section ->
        if (index > 0) HorizontalDivider(Modifier.padding(start = LocalMenuIndent.current))
        for (command in section) {
            key(command) { SessionCommandView(command, controller, SessionCommandStyle.MenuItem, menu) }
        }
    }
}
