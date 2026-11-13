package io.nekohasekai.screensharing.settings

import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.SectionHeader
import io.nekohasekai.screensharing.app.groupItemColors
import io.nekohasekai.screensharing.app.slideTransition
import io.nekohasekai.screensharing.model.EventModifiers
import io.nekohasekai.screensharing.model.KeyCombo
import io.nekohasekai.screensharing.model.KeyShortcut
import io.nekohasekai.screensharing.model.MappingTarget
import sh.calvin.reorderable.ReorderableColumn
import java.util.UUID

data class InputStep(val target: MappingTarget?, val combo: KeyCombo, val text: String, val id: UUID = UUID.randomUUID()) {
    constructor(text: String) : this(null, KeyCombo.empty, text)

    constructor(key: KeyCombo, target: MappingTarget) : this(target, key, "")

    data class Modifier(val flag: EventModifiers, val label: String, val name: String, val title: String)

    val isComplete: Boolean
        get() {
            if (target == null) return text.isNotEmpty()
            return combo.keys.isNotEmpty() && (combo.keys.size > 1 || combo.shortcuts.any { it.hasCommandModifier || it.isNamed })
        }

    fun clearKey(): InputStep = copy(combo = combo.copy(keys = emptyList()))

    fun toggle(modifier: EventModifiers, on: Boolean): InputStep {
        val flags = if (on) combo.eventModifiers + modifier else combo.eventModifiers - modifier
        return copy(combo = KeyCombo(combo.keys, flags))
    }

    fun toggle(key: KeyGroupChoice.Key): InputStep = copy(
        combo = when {
            key.isCommand -> if (combo.keys == listOf(key.name)) KeyCombo.empty else KeyCombo(listOf(key.name), EventModifiers.None)
            combo.command != null -> KeyCombo(listOf(key.name), EventModifiers.None)
            key.name in combo.keys -> combo.copy(keys = combo.keys - key.name)
            else -> combo.copy(keys = combo.keys + key.name)
        },
    )

    fun record(pressed: KeyShortcut): InputStep? {
        if (target == null || pressed.chord(target) == null) return null
        val modifiers = if (pressed.eventModifiers.isEmpty) combo.eventModifiers else pressed.eventModifiers
        return copy(combo = KeyCombo(listOf(pressed.key), modifiers))
    }

    companion object {
        fun modifiers(target: MappingTarget): List<Modifier> {
            val context = App.instance
            return if (target == MappingTarget.Pc) {
                listOf(
                    Modifier(EventModifiers.Control, "Ctrl", "Ctrl", context.getString(R.string.model_ctrl_key)),
                    Modifier(EventModifiers.Option, "Alt", "Alt", context.getString(R.string.model_alt_key)),
                    Modifier(EventModifiers.Shift, "Shift", "Shift", context.getString(R.string.model_shift_key)),
                    Modifier(EventModifiers.Command, "Win", "Windows", context.getString(R.string.model_windows_key)),
                )
            } else {
                listOf(
                    Modifier(EventModifiers.Control, "⌃", "Control", context.getString(R.string.model_control_key)),
                    Modifier(EventModifiers.Option, "⌥", "Option", context.getString(R.string.model_option_key)),
                    Modifier(EventModifiers.Shift, "⇧", "Shift", context.getString(R.string.model_shift_key)),
                    Modifier(EventModifiers.Command, "⌘", "Command", context.getString(R.string.model_command_key)),
                )
            }
        }
    }
}

data class KeyKind(val target: MappingTarget, val title: String)

class KeyGroupChoice(val title: String, val keys: List<Key>) {
    class Key(val name: String, val symbol: String?, val label: String, val isCommand: Boolean) {
        val id: String get() = name
    }

    val id: String get() = title

    val hasSymbols: Boolean get() = keys.any { it.symbol != null }

    companion object {
        fun all(target: MappingTarget): List<KeyGroupChoice> {
            val characters = listOf(
                R.string.settings_letters to "abcdefghijklmnopqrstuvwxyz",
                R.string.settings_numbers to "1234567890",
                R.string.settings_symbols to "`-=[]\\;',./",
            ).map { (title, keys) ->
                KeyGroupChoice(App.instance.getString(title), keys.map { Key(it.toString(), null, it.uppercase(), false) })
            }
            val named = KeyShortcut.KeyGroup.entries.mapNotNull { group ->
                val keys = group.keys(target)
                if (keys.isEmpty()) return@mapNotNull null
                KeyGroupChoice(
                    group.title,
                    keys.map { key ->
                        val label = key.label(target)
                        Key(key.name, if (target == MappingTarget.Apple && key.symbol != label) key.symbol else null, label, key.command != null)
                    },
                )
            }
            return characters + named
        }
    }
}

object ConnectedKeyboard {
    private val inputManager = App.instance.getSystemService(InputManager::class.java)

    var isConnected by mutableStateOf(false)
        private set

    init {
        inputManager.registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = update()

                override fun onInputDeviceRemoved(deviceId: Int) = update()

                override fun onInputDeviceChanged(deviceId: Int) = update()
            },
            Handler(Looper.getMainLooper()),
        )
        update()
    }

    private fun update() {
        isConnected = inputManager.inputDeviceIds.any { id ->
            val device = inputManager.getInputDevice(id) ?: return@any false
            !device.isVirtual && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        }
    }
}

@Composable
fun InputStepsEditor(
    steps: List<InputStep>,
    updateSteps: ((List<InputStep>) -> List<InputStep>) -> Unit,
    keyKinds: List<KeyKind>,
    onRecordingChange: (UUID?) -> Unit,
    onSelect: (UUID) -> Unit,
) {
    var focusedText by remember { mutableStateOf<UUID?>(null) }
    val haptic = LocalHapticFeedback.current
    val moveUp = stringResource(R.string.settings_move_up)
    val moveDown = stringResource(R.string.settings_move_down)
    val defaultKind = stringResource(R.string.settings_key)

    fun move(id: UUID, offset: Int): Boolean {
        var moved = false
        updateSteps { list ->
            val index = list.indexOfFirst { it.id == id }
            val destination = index + offset
            if (index < 0 || destination !in list.indices) return@updateSteps list
            moved = true
            list.toMutableList().apply { add(destination, removeAt(index)) }
        }
        return moved
    }

    ReorderableColumn(
        list = steps,
        onSettle = { from, to -> updateSteps { list -> list.toMutableList().apply { add(to, removeAt(from)) } } },
        onMove = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
    ) { _, step, isDragging ->
        key(step.id) {
            ReorderableItem {
                val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "InputStepElevation")
                Surface(
                    shadowElevation = elevation,
                    color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(start = 16.dp, end = 4.dp)
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction(moveUp) { move(step.id, -1) },
                                    CustomAccessibilityAction(moveDown) { move(step.id, 1) },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val target = step.target
                        if (target != null) {
                            Row(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 56.dp)
                                    .clickable { onSelect(step.id) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(keyKinds.firstOrNull { it.target == target }?.title ?: defaultKind, Modifier.weight(1f))
                                Text(
                                    step.combo.displayName(target),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        } else {
                            val focusRequester = remember { FocusRequester() }
                            OutlinedTextField(
                                value = step.text,
                                onValueChange = { text -> updateSteps { list -> list.map { if (it.id == step.id) it.copy(text = text) else it } } },
                                placeholder = { Text(stringResource(R.string.settings_text)) },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                                maxLines = 6,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 4.dp)
                                    .focusRequester(focusRequester),
                            )
                            LaunchedEffect(focusedText) {
                                if (focusedText == step.id) {
                                    focusRequester.requestFocus()
                                    focusedText = null
                                }
                            }
                        }
                        IconButton(onClick = {
                            onRecordingChange(null)
                            updateSteps { list -> list.filter { it.id != step.id } }
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                        }
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .draggableHandle()
                                .minimumInteractiveComponentSize(),
                        )
                    }
                }
            }
        }
    }
    Box {
        var isAdding by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.add), color = MaterialTheme.colorScheme.primary) },
            leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
            colors = groupItemColors(),
            modifier = Modifier.clickable { isAdding = true },
        )
        DropdownMenu(expanded = isAdding, onDismissRequest = { isAdding = false }) {
            for (kind in keyKinds) {
                DropdownMenuItem(
                    text = { Text(kind.title) },
                    leadingIcon = { Icon(Icons.Outlined.Keyboard, contentDescription = null) },
                    onClick = {
                        isAdding = false
                        val step = InputStep(KeyCombo.empty, kind.target)
                        updateSteps { it + step }
                        onSelect(step.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_text)) },
                leadingIcon = { Icon(Icons.Outlined.TextFields, contentDescription = null) },
                onClick = {
                    isAdding = false
                    val step = InputStep("")
                    updateSteps { it + step }
                    focusedText = step.id
                },
            )
        }
    }
}

private data class StepRoute(val step: UUID?, val group: String?) {
    val depth: Int get() = if (step == null) {
        0
    } else if (group == null) {
        1
    } else {
        2
    }
}

@Composable
fun InputStepDestination(
    steps: List<InputStep>,
    updateSteps: ((List<InputStep>) -> List<InputStep>) -> Unit,
    selected: UUID?,
    onSelectedChange: (UUID?) -> Unit,
    keyKinds: List<KeyKind>,
    recording: UUID?,
    onRecordingChange: (UUID?) -> Unit,
    content: @Composable () -> Unit,
) {
    var group by remember { mutableStateOf<String?>(null) }
    val stateHolder = rememberSaveableStateHolder()
    val defaultKind = stringResource(R.string.settings_key)

    fun close() {
        onRecordingChange(null)
        group = null
        updateSteps { list -> list.filterNot { it.id == selected && it.combo.keys.isEmpty() } }
        onSelectedChange(null)
    }

    BackHandler(enabled = selected != null) {
        if (group != null) {
            onRecordingChange(null)
            group = null
        } else {
            close()
        }
    }

    AnimatedContent(
        targetState = StepRoute(selected, group),
        transitionSpec = { slideTransition(forward = targetState.depth > initialState.depth) },
        label = "InputStepDestination",
    ) { route ->
        stateHolder.SaveableStateProvider(route.toString()) {
            val step = steps.firstOrNull { it.id == route.step }
            val target = step?.target
            when {
                route.step == null -> content()
                step == null || target == null -> Box(Modifier)
                route.group == null -> KeyStepView(
                    step = step,
                    target = target,
                    title = keyKinds.firstOrNull { it.target == target }?.title ?: defaultKind,
                    isRecording = recording == step.id,
                    onRecordingChange = onRecordingChange,
                    update = { transform -> updateSteps { list -> list.map { if (it.id == step.id) transform(it) else it } } },
                    onOpenGroup = {
                        onRecordingChange(null)
                        group = it.title
                    },
                    onBack = { close() },
                )
                else -> {
                    val choice = remember(target, route.group) { KeyGroupChoice.all(target).firstOrNull { it.title == route.group } }
                    if (choice != null) {
                        KeyListView(
                            step = step,
                            group = choice,
                            onRecordingChange = onRecordingChange,
                            update = { transform -> updateSteps { list -> list.map { if (it.id == step.id) transform(it) else it } } },
                            onBack = {
                                onRecordingChange(null)
                                group = null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyStepView(
    step: InputStep,
    target: MappingTarget,
    title: String,
    isRecording: Boolean,
    onRecordingChange: (UUID?) -> Unit,
    update: ((InputStep) -> InputStep) -> Unit,
    onOpenGroup: (KeyGroupChoice) -> Unit,
    onBack: () -> Unit,
) {
    PageScaffold(title, navigationIcon = { BackButton(onBack) }) {
        if (ConnectedKeyboard.isConnected) {
            val keyTitle = stringResource(R.string.settings_key)
            ListGroup {
                ListItem(
                    headlineContent = { Text(keyTitle) },
                    trailingContent = {
                        ShortcutButton(
                            isRecording = isRecording,
                            title = keyTitle,
                            shortcut = if (step.combo.keys.isEmpty()) null else step.combo.displayName(target),
                            prompt = stringResource(R.string.settings_type_key),
                        ) {
                            onRecordingChange(if (isRecording) null else step.id)
                        }
                    },
                    colors = groupItemColors(),
                )
            }
        }
        SectionHeader(stringResource(R.string.settings_modifier_keys))
        val enabled = step.combo.command == null
        ListGroup {
            for (modifier in InputStep.modifiers(target)) {
                CheckmarkRow(
                    symbol = if (target == MappingTarget.Apple) modifier.label else null,
                    showsSymbol = target == MappingTarget.Apple,
                    title = modifier.name,
                    isOn = modifier.flag in step.combo.eventModifiers,
                    enabled = enabled,
                    description = modifier.title,
                ) {
                    update { it.toggle(modifier.flag, modifier.flag !in it.combo.eventModifiers) }
                }
            }
        }
        SectionHeader(stringResource(R.string.settings_key))
        val groups = remember(target) { KeyGroupChoice.all(target) }
        ListGroup {
            for (group in groups) {
                val selected = group.keys.filter { it.name in step.combo.keys }.joinToString(", ") { it.label }
                ListItem(
                    headlineContent = { Text(group.title) },
                    supportingContent = if (selected.isEmpty()) null else ({ Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    colors = groupItemColors(),
                    modifier = Modifier.clickable { onOpenGroup(group) },
                )
            }
        }
    }
}

@Composable
private fun KeyListView(
    step: InputStep,
    group: KeyGroupChoice,
    onRecordingChange: (UUID?) -> Unit,
    update: ((InputStep) -> InputStep) -> Unit,
    onBack: () -> Unit,
) {
    PageScaffold(group.title, navigationIcon = { BackButton(onBack) }) {
        ListGroup {
            for (key in group.keys) {
                CheckmarkRow(symbol = key.symbol, showsSymbol = group.hasSymbols, title = key.label, isOn = key.name in step.combo.keys) {
                    onRecordingChange(null)
                    update { it.toggle(key) }
                }
            }
        }
    }
}

@Composable
private fun CheckmarkRow(
    symbol: String?,
    showsSymbol: Boolean,
    title: String,
    isOn: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    action: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = if (showsSymbol) {
            { Text(symbol.orEmpty(), modifier = Modifier.width(28.dp)) }
        } else {
            null
        },
        trailingContent = { Checkbox(checked = isOn, onCheckedChange = null, enabled = enabled) },
        colors = groupItemColors(enabled = enabled),
        modifier = Modifier
            .toggleable(value = isOn, enabled = enabled, role = Role.Checkbox) { action() }
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    )
}
