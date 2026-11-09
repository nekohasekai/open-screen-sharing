package io.nekohasekai.screensharing.settings

import android.icu.text.ListFormatter
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.FormButton
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.SectionHeader
import io.nekohasekai.screensharing.app.groupItemColors
import io.nekohasekai.screensharing.model.KeyMapping
import io.nekohasekai.screensharing.model.KeyShortcut
import io.nekohasekai.screensharing.model.KeyShortcuts
import io.nekohasekai.screensharing.model.MappingTarget
import io.nekohasekai.screensharing.model.ShortcutAction
import io.nekohasekai.screensharing.model.ShortcutGroup
import java.util.UUID

@Composable
internal fun KeyboardView(
    navigationIcon: @Composable () -> Unit,
    onOpenGroup: (ShortcutGroup) -> Unit,
    onEditMapping: (MappingTarget, UUID?) -> Unit,
) {
    val isConnected = ConnectedKeyboard.isConnected
    PageScaffold(
        title = stringResource(R.string.settings_keyboard),
        navigationIcon = navigationIcon,
        actions = {
            if (isConnected) {
                var isShowingMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { isShowingMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.settings_more))
                    }
                    DropdownMenu(expanded = isShowingMenu, onDismissRequest = { isShowingMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_restore_defaults)) },
                            leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                            enabled = KeyShortcuts.isCustomized,
                            onClick = {
                                isShowingMenu = false
                                KeyShortcuts.restoreDefaults()
                            },
                        )
                    }
                }
            }
        },
        scrollable = isConnected,
    ) {
        if (!isConnected) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Keyboard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    stringResource(R.string.settings_no_keyboard),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.settings_connect_keyboard),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            MappingList(onEdit = onEditMapping, onOpenGroup = onOpenGroup)
        }
    }
}

@Composable
private fun MappingList(onEdit: (MappingTarget, UUID?) -> Unit, onOpenGroup: (ShortcutGroup) -> Unit) {
    for (target in MappingTarget.entries) {
        SectionHeader(target.title)
        ListGroup {
            for (mapping in KeyShortcuts.mappings(target)) {
                key(target, mapping.id) {
                    EditableListItem(
                        deleteLabel = stringResource(R.string.settings_delete_mapping),
                        onEdit = { onEdit(target, mapping.id) },
                        onDelete = { KeyShortcuts.removeMapping(mapping.id, target) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mapping.trigger.localDisplayName)
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .size(16.dp),
                            )
                            Text(mapping.displayName(target), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_add_mapping), color = MaterialTheme.colorScheme.primary) },
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                colors = groupItemColors(),
                modifier = Modifier.clickable { onEdit(target, null) },
            )
        }
    }
    SectionHeader(stringResource(R.string.settings_shortcuts))
    ListGroup {
        for (group in ShortcutGroup.entries) {
            ListItem(
                headlineContent = { Text(group.title) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                colors = groupItemColors(),
                modifier = Modifier.clickable { onOpenGroup(group) },
            )
        }
    }
}

@Composable
internal fun EditableListItem(deleteLabel: String, onEdit: () -> Unit, onDelete: () -> Unit, headlineContent: @Composable () -> Unit) {
    var isShowingMenu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = headlineContent,
            trailingContent = {
                IconButton(onClick = { isShowingMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.settings_more))
                }
            },
            colors = groupItemColors(),
            modifier = Modifier
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(deleteLabel) {
                            onDelete()
                            true
                        },
                    )
                }
                .contextMenuGestures(onClick = onEdit, onContextMenu = { isShowingMenu = true }),
        )
        DropdownMenu(expanded = isShowingMenu, onDismissRequest = { isShowingMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    isShowingMenu = false
                    onEdit()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                ),
                onClick = {
                    isShowingMenu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
internal fun ShortcutGroupView(group: ShortcutGroup, navigationIcon: @Composable () -> Unit) {
    val resources = LocalResources.current
    var recording by remember { mutableStateOf<ShortcutAction?>(null) }
    var alert by remember { mutableStateOf<AlertState?>(null) }

    fun assign(shortcut: KeyShortcut, action: ShortcutAction) {
        val other = KeyShortcuts.action(shortcut)
        if (other != null && other != action) {
            alert = AlertState(
                title = resources.getString(R.string.settings_already_in_use, shortcut.localDisplayName),
                message = resources.getString(R.string.settings_assigned_to, other.title),
                actions = listOf(
                    AlertState.Action(resources.getString(R.string.cancel), AlertState.Role.Cancel) {},
                    AlertState.Action(resources.getString(R.string.settings_replace), isDefault = true) {
                        KeyShortcuts.set(null, other)
                        KeyShortcuts.set(shortcut, action)
                    },
                ),
            )
            return
        }
        val conflicts = MappingTarget.entries.mapNotNull { target -> KeyShortcuts.mapping(shortcut, target)?.let { target to it } }
        if (conflicts.isNotEmpty()) {
            val displayNames = conflicts.map { (target, mapping) -> mapping.displayName(target) }
            val names = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ListFormatter.getInstance().format(displayNames) else displayNames.joinToString(", ")
            alert = AlertState(
                title = resources.getString(R.string.settings_already_in_use, shortcut.localDisplayName),
                message = resources.getString(
                    if (conflicts.size == 1) R.string.settings_mapped_to_mapping else R.string.settings_mapped_to_mappings,
                    names,
                ),
                actions = listOf(
                    AlertState.Action(resources.getString(R.string.cancel), AlertState.Role.Cancel) {},
                    AlertState.Action(resources.getString(R.string.settings_replace), isDefault = true) {
                        for ((target, mapping) in conflicts) {
                            KeyShortcuts.removeMapping(mapping.id, target)
                        }
                        KeyShortcuts.set(shortcut, action)
                    },
                ),
            )
            return
        }
        KeyShortcuts.set(shortcut, action)
    }

    fun record(action: ShortcutAction, press: ShortcutPress): Boolean {
        when (press) {
            ShortcutPress.Cancel -> recording = null
            ShortcutPress.Clear -> {
                recording = null
                KeyShortcuts.set(null, action)
            }
            is ShortcutPress.Shortcut -> {
                val shortcut = press.shortcut
                if (!shortcut.isValidTrigger || shortcut.shortcutInfo("") == null) return false
                recording = null
                assign(shortcut, action)
            }
        }
        return true
    }

    ShortcutRecorder(recording = recording, onRecordingChange = { recording = it }, onPress = { action, press -> record(action, press) }) {
        PageScaffold(title = group.title, navigationIcon = navigationIcon) {
            ListGroup {
                for (action in group.actions) {
                    val shortcut = KeyShortcuts.shortcut(action)
                    val isRecording = recording == action
                    ListItem(
                        headlineContent = { Text(action.title) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (shortcut != null && !isRecording) {
                                    IconButton(onClick = { KeyShortcuts.set(null, action) }) {
                                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.settings_clear_shortcut))
                                    }
                                }
                                ShortcutButton(isRecording = isRecording, title = action.title, shortcut = shortcut?.localDisplayName) {
                                    recording = if (isRecording) null else action
                                }
                            }
                        },
                        colors = groupItemColors(),
                    )
                }
            }
        }
    }
    AlertStateDialog(alert) { alert = null }
}

@Composable
fun ShortcutButton(
    isRecording: Boolean,
    title: String,
    shortcut: String?,
    prompt: String = stringResource(R.string.settings_type_shortcut),
    action: () -> Unit,
) {
    val value = if (isRecording) prompt else shortcut ?: stringResource(R.string.settings_none)
    val modifier = Modifier.semantics {
        contentDescription = title
        stateDescription = value
    }
    if (isRecording) {
        Button(onClick = action, modifier = modifier) {
            ShortcutButtonLabel(isRecording = true, shortcut = shortcut, prompt = prompt)
        }
    } else {
        OutlinedButton(onClick = action, modifier = modifier) {
            ShortcutButtonLabel(isRecording = false, shortcut = shortcut, prompt = prompt)
        }
    }
}

@Composable
fun ShortcutButtonLabel(isRecording: Boolean, shortcut: String?, prompt: String) {
    when {
        isRecording -> Text(prompt)
        shortcut != null -> Text(shortcut)
        else -> Text(stringResource(R.string.settings_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private sealed interface MappingField {
    data object Trigger : MappingField

    data class Key(val id: UUID) : MappingField
}

@Composable
internal fun KeyMappingEditor(mapping: KeyMapping, target: MappingTarget, isNew: Boolean, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val id = mapping.id
    var trigger by remember { mutableStateOf(mapping.trigger) }
    var steps by remember {
        mutableStateOf(
            mapping.steps.map { step ->
                when (step) {
                    is KeyMapping.Step.Key -> InputStep(step.combo, target)
                    is KeyMapping.Step.Text -> InputStep(step.text)
                }
            },
        )
    }
    var recording by remember { mutableStateOf<MappingField?>(null) }
    var selectedStep by remember { mutableStateOf<UUID?>(null) }
    var alert by remember { mutableStateOf<AlertState?>(null) }
    val keyKinds = listOf(KeyKind(target, stringResource(R.string.settings_key)))
    val isComplete = !trigger.isUnset && steps.isNotEmpty() && steps.all { it.isComplete }
    val updateSteps: ((List<InputStep>) -> List<InputStep>) -> Unit = { transform -> steps = transform(steps) }
    val stepRecording = (recording as? MappingField.Key)?.id
    val setStepRecording: (UUID?) -> Unit = { recording = it?.let { stepID -> MappingField.Key(stepID) } }

    fun commit() {
        val updated = KeyMapping(
            id,
            trigger,
            steps.map { if (it.target == null) KeyMapping.Step.Text(it.text) else KeyMapping.Step.Key(it.combo) },
        )
        if (isNew) {
            KeyShortcuts.add(updated, target)
        } else {
            KeyShortcuts.update(updated, target)
        }
        onDismiss()
    }

    fun save() {
        val shortcut = trigger
        val other = KeyShortcuts.action(shortcut)
        val otherMapping = KeyShortcuts.mapping(shortcut, target)
        when {
            other != null -> alert = AlertState(
                title = resources.getString(R.string.settings_already_in_use, shortcut.localDisplayName),
                message = resources.getString(R.string.settings_assigned_to, other.title),
                actions = listOf(
                    AlertState.Action(resources.getString(R.string.cancel), AlertState.Role.Cancel) {},
                    AlertState.Action(resources.getString(R.string.settings_replace), isDefault = true) {
                        KeyShortcuts.set(null, other)
                        commit()
                    },
                ),
            )
            otherMapping != null && otherMapping.id != id -> alert = AlertState(
                title = resources.getString(R.string.settings_already_in_use, shortcut.localDisplayName),
                message = resources.getString(R.string.settings_mapped_to_mapping, otherMapping.displayName(target)),
                actions = listOf(
                    AlertState.Action(resources.getString(R.string.cancel), AlertState.Role.Cancel) {},
                    AlertState.Action(resources.getString(R.string.settings_replace), isDefault = true) {
                        KeyShortcuts.removeMapping(otherMapping.id, target)
                        commit()
                    },
                ),
            )
            else -> commit()
        }
    }

    fun record(field: MappingField, press: ShortcutPress): Boolean {
        when (press) {
            ShortcutPress.Cancel -> recording = null
            ShortcutPress.Clear -> {
                recording = null
                when (field) {
                    MappingField.Trigger -> trigger = KeyShortcut.unset
                    is MappingField.Key -> steps = steps.map { if (it.id == field.id) it.clearKey() else it }
                }
            }
            is ShortcutPress.Shortcut -> when (field) {
                MappingField.Trigger -> {
                    if (!press.shortcut.isValidTrigger) return false
                    recording = null
                    trigger = press.shortcut
                }
                is MappingField.Key -> {
                    val index = steps.indexOfFirst { it.id == field.id }
                    val recorded = steps.getOrNull(index)?.record(press.shortcut) ?: return false
                    steps = steps.toMutableList().also { it[index] = recorded }
                    recording = null
                }
            }
        }
        return true
    }

    ShortcutRecorder(recording = recording, onRecordingChange = { recording = it }, onPress = { field, press -> record(field, press) }) {
        InputStepDestination(
            steps = steps,
            updateSteps = updateSteps,
            selected = selectedStep,
            onSelectedChange = { selectedStep = it },
            keyKinds = keyKinds,
            recording = stepRecording,
            onRecordingChange = setStepRecording,
        ) {
            PageScaffold(
                title = stringResource(if (isNew) R.string.settings_add_mapping else R.string.settings_edit_mapping),
                navigationIcon = { BackButton(onDismiss) },
            ) {
                val shortcutTitle = stringResource(R.string.settings_shortcut)
                ListGroup {
                    ListItem(
                        headlineContent = { Text(shortcutTitle) },
                        trailingContent = {
                            ShortcutButton(
                                isRecording = recording == MappingField.Trigger,
                                title = shortcutTitle,
                                shortcut = if (trigger.isUnset) null else trigger.localDisplayName,
                            ) {
                                recording = if (recording == MappingField.Trigger) null else MappingField.Trigger
                            }
                        },
                        colors = groupItemColors(),
                    )
                }
                SectionHeader(stringResource(R.string.settings_sends))
                ListGroup(clipContent = false) {
                    InputStepsEditor(
                        steps = steps,
                        updateSteps = updateSteps,
                        keyKinds = keyKinds,
                        onRecordingChange = setStepRecording,
                        onSelect = { selectedStep = it },
                    )
                }
                if (recording == null) {
                    Spacer(Modifier.height(8.dp))
                    FormButton(stringResource(R.string.save), enabled = isComplete, onClick = dropUnlessResumed { save() })
                }
            }
        }
    }
    AlertStateDialog(alert) { alert = null }
}
