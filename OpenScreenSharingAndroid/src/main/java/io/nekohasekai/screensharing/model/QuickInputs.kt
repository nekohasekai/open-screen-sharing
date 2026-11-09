package io.nekohasekai.screensharing.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class QuickInput(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    val name: String,
    val steps: List<Step>,
) {
    @Serializable
    sealed interface Step {
        @Serializable
        @SerialName("text")
        data class Text(val text: String) : Step

        @Serializable
        @SerialName("key")
        data class Key(val combo: KeyCombo, val target: MappingTarget) : Step
    }

    fun steps(target: MappingTarget): List<KeyMapping.Step> = steps.mapNotNull { step ->
        when (step) {
            is Step.Text -> KeyMapping.Step.Text(step.text)
            is Step.Key -> if (step.target == target) KeyMapping.Step.Key(step.combo) else null
        }
    }
}

object QuickInputs {
    private const val KEY = "quickInputs"

    var inputs: List<QuickInput> by mutableStateOf(PreferenceStore.value<List<QuickInput>>(KEY) ?: emptyList())
        private set

    fun add(input: QuickInput) {
        inputs = inputs + input
        PreferenceStore.set(KEY, inputs)
    }

    fun update(input: QuickInput) {
        val index = inputs.indexOfFirst { it.id == input.id }
        if (index < 0) return
        inputs = inputs.toMutableList().also { it[index] = input }
        PreferenceStore.set(KEY, inputs)
    }

    fun remove(id: UUID) {
        inputs = inputs.filter { it.id != id }
        PreferenceStore.set(KEY, inputs)
    }
}
