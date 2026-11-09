package io.nekohasekai.screensharing.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.dropUnlessResumed
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.FormButton
import io.nekohasekai.screensharing.app.FormDialog
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.SectionFooter
import io.nekohasekai.screensharing.app.SectionHeader
import io.nekohasekai.screensharing.app.groupItemColors
import io.nekohasekai.screensharing.home.FieldModifier
import io.nekohasekai.screensharing.model.MappingTarget
import io.nekohasekai.screensharing.model.QuickInput
import io.nekohasekai.screensharing.model.QuickInputs
import java.util.UUID

@Composable
fun QuickInputView(onClose: () -> Unit) {
    var editing by remember { mutableStateOf<Pair<QuickInput, Boolean>?>(null) }
    QuickInputPage(navigationIcon = { CloseButton(onClose) }) { input ->
        editing = (input ?: QuickInput(UUID.randomUUID(), "", emptyList())) to (input == null)
    }
    editing?.let { (input, isNew) ->
        FormDialog(onDismissRequest = { editing = null }) {
            QuickInputEditor(input, isNew, navigationIcon = { CloseButton { editing = null } }) { editing = null }
        }
    }
}

@Composable
internal fun QuickInputPage(navigationIcon: @Composable () -> Unit, onEdit: (QuickInput?) -> Unit) {
    PageScaffold(title = stringResource(R.string.settings_quick_input), navigationIcon = navigationIcon) {
        ListGroup {
            for (input in QuickInputs.inputs) {
                key(input.id) {
                    EditableListItem(
                        deleteLabel = stringResource(R.string.settings_delete_quick_input),
                        onEdit = { onEdit(input) },
                        onDelete = { QuickInputs.remove(input.id) },
                    ) {
                        Text(input.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_add_quick_input), color = MaterialTheme.colorScheme.primary) },
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                colors = groupItemColors(),
                modifier = Modifier.clickable { onEdit(null) },
            )
        }
    }
}

@Composable
internal fun QuickInputEditor(input: QuickInput, isNew: Boolean, navigationIcon: @Composable () -> Unit, onDismiss: () -> Unit) {
    val id = input.id
    var name by remember { mutableStateOf(input.name) }
    var steps by remember {
        mutableStateOf(
            input.steps.map { step ->
                when (step) {
                    is QuickInput.Step.Text -> InputStep(step.text)
                    is QuickInput.Step.Key -> InputStep(step.combo, step.target)
                }
            },
        )
    }
    var recording by remember { mutableStateOf<UUID?>(null) }
    var selectedStep by remember { mutableStateOf<UUID?>(null) }
    val keyKinds = listOf(
        KeyKind(MappingTarget.Apple, stringResource(R.string.settings_apple_key)),
        KeyKind(MappingTarget.Pc, stringResource(R.string.settings_pc_key)),
    )
    val isComplete = name.isNotBlank() && steps.isNotEmpty() && steps.all { it.isComplete }
    val updateSteps: ((List<InputStep>) -> List<InputStep>) -> Unit = { transform -> steps = transform(steps) }

    fun record(stepID: UUID, press: ShortcutPress): Boolean {
        when (press) {
            ShortcutPress.Cancel -> recording = null
            ShortcutPress.Clear -> {
                recording = null
                steps = steps.map { if (it.id == stepID) it.clearKey() else it }
            }
            is ShortcutPress.Shortcut -> {
                val index = steps.indexOfFirst { it.id == stepID }
                val recorded = steps.getOrNull(index)?.record(press.shortcut) ?: return false
                steps = steps.toMutableList().also { it[index] = recorded }
                recording = null
            }
        }
        return true
    }

    fun save() {
        val updated = QuickInput(
            id,
            name.trim(),
            steps.map { step ->
                val target = step.target
                if (target != null) QuickInput.Step.Key(step.combo, target) else QuickInput.Step.Text(step.text)
            },
        )
        if (isNew) {
            QuickInputs.add(updated)
        } else {
            QuickInputs.update(updated)
        }
        onDismiss()
    }

    ShortcutRecorder(recording = recording, onRecordingChange = { recording = it }, onPress = { stepID, press -> record(stepID, press) }) {
        InputStepDestination(
            steps = steps,
            updateSteps = updateSteps,
            selected = selectedStep,
            onSelectedChange = { selectedStep = it },
            keyKinds = keyKinds,
            recording = recording,
            onRecordingChange = { recording = it },
        ) {
            PageScaffold(
                title = stringResource(if (isNew) R.string.settings_add_quick_input else R.string.settings_edit_quick_input),
                navigationIcon = navigationIcon,
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = FieldModifier,
                )
                SectionHeader(stringResource(R.string.settings_sends))
                ListGroup(clipContent = false) {
                    InputStepsEditor(
                        steps = steps,
                        updateSteps = updateSteps,
                        keyKinds = keyKinds,
                        onRecordingChange = { recording = it },
                        onSelect = { selectedStep = it },
                    )
                }
                SectionFooter(stringResource(R.string.settings_quick_input_footer))
                if (recording == null) FormButton(stringResource(R.string.save), enabled = isComplete, onClick = dropUnlessResumed { save() })
            }
        }
    }
}
