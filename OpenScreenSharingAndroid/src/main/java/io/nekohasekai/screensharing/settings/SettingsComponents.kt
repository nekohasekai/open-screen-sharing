package io.nekohasekai.screensharing.settings

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertState

@Composable
internal fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
    }
}

@Composable
internal fun CloseButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
    }
}

@Composable
internal fun AlertStateDialog(alert: AlertState?, onDismiss: () -> Unit) {
    if (alert == null) return
    val cancel = alert.actions.firstOrNull { it.role == AlertState.Role.Cancel }
    val confirm = alert.actions.firstOrNull { it.isDefault }
        ?: alert.actions.lastOrNull { it !== cancel }
        ?: alert.actions.last()
    val others = alert.actions.filter { it !== confirm }

    @Composable
    fun ActionButton(action: AlertState.Action) {
        TextButton(onClick = {
            onDismiss()
            action.perform()
        }) {
            Text(
                action.label,
                color = if (action.role == AlertState.Role.Destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        }
    }
    AlertDialog(
        onDismissRequest = {
            onDismiss()
            cancel?.perform?.invoke()
        },
        title = { Text(alert.title) },
        text = if (alert.message.isEmpty()) null else ({ Text(alert.message) }),
        confirmButton = { ActionButton(confirm) },
        dismissButton = if (others.isEmpty()) {
            null
        } else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    others.forEach { ActionButton(it) }
                }
            }
        },
    )
}

@Composable
internal fun Modifier.contextMenuGestures(onClick: () -> Unit, onContextMenu: () -> Unit): Modifier {
    val currentOnContextMenu by rememberUpdatedState(onContextMenu)
    return pointerInput(Unit) {
        awaitEachGesture {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                currentOnContextMenu()
            }
        }
    }.combinedClickable(onLongClick = onContextMenu, onClick = onClick)
}
