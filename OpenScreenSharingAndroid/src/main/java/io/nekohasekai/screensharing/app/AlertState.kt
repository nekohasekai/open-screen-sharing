package io.nekohasekai.screensharing.app

import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R

class MessageError(message: String) : Exception(message)

data class AlertState(
    val title: String,
    val message: String = "",
    val actions: List<Action> = listOf(Action(App.instance.getString(R.string.ok), Role.Cancel) {}),
) {
    enum class Role {
        Cancel,
        Destructive,
    }

    data class Action(
        val label: String,
        val role: Role? = null,
        val isDefault: Boolean = false,
        val perform: () -> Unit,
    )

    companion object {
        fun failure(failure: String, error: Throwable?): AlertState = failure(failure, error?.localizedMessage.orEmpty())

        fun failure(
            failure: String,
            errorMessage: String,
            closeLabel: String = App.instance.getString(R.string.ok),
            onClose: () -> Unit = {},
        ): AlertState {
            val message = errorMessage.trim()
            val actions = mutableListOf(Action(closeLabel, Role.Cancel, isDefault = true, perform = onClose))
            if (message.isNotEmpty()) {
                actions.add(
                    0,
                    Action(App.instance.getString(R.string.copy)) {
                        AppClipboard.write("$failure\n$message")
                        onClose()
                    },
                )
            }
            return AlertState(failure, message, actions)
        }
    }
}
