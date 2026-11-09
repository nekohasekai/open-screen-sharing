package io.nekohasekai.screensharing.app

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.window.DialogProperties

@Composable
fun AlertHost(alert: AlertState?, onDismiss: () -> Unit) {
    if (alert == null) return
    val actions = remember(alert) {
        alert.actions.sortedBy {
            when {
                it.isDefault -> 2
                it.role == AlertState.Role.Cancel -> 0
                else -> 1
            }
        }
    }
    val cancel = alert.actions.firstOrNull { it.role == AlertState.Role.Cancel }
    AlertDialog(
        onDismissRequest = {
            onDismiss()
            cancel?.perform?.invoke()
        },
        confirmButton = {
            for (action in actions) {
                TextButton(
                    onClick = {
                        onDismiss()
                        action.perform()
                    },
                    colors = if (action.role == AlertState.Role.Destructive) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text(action.label)
                }
            }
        },
        title = { Text(alert.title) },
        text = if (alert.message.isEmpty()) {
            null
        } else {
            { SelectionContainer { Text(alert.message) } }
        },
        properties = DialogProperties(dismissOnBackPress = cancel != null, dismissOnClickOutside = cancel != null),
    )
}

@Composable
fun StorageNoticeHost() {
    AlertHost(StorageNotice.alert) { StorageNotice.dismiss() }
}
