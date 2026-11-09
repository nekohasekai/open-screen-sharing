package io.nekohasekai.screensharing.sessionui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertHost
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.session.PictureChange
import io.nekohasekai.screensharing.session.ScreenPicture
import io.nekohasekai.screensharing.session.SessionChoice
import io.nekohasekai.screensharing.session.SessionChoicePrompt
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.session.SessionFailure
import io.nekohasekai.screensharing.session.SessionPhase

@Composable
fun SessionStatusOverlay(
    controller: SessionController,
    onClose: () -> Unit,
) {
    val phase = controller.phase
    val picture = controller.picture
    val isConnecting =
        when (phase) {
            SessionPhase.Idle, SessionPhase.Connecting, SessionPhase.Authenticating -> true
            SessionPhase.Connected -> picture == ScreenPicture.None
            else -> false
        }
    val isReconnecting = phase is SessionPhase.Reconnecting || (phase == SessionPhase.Connected && picture is ScreenPicture.Previous && !controller.hasPicture)
    val progressTitle =
        when {
            phase == SessionPhase.PreparingCursors -> {
                stringResource(R.string.chrome_preparing_cursors)
            }
            isConnecting -> {
                stringResource(R.string.home_connecting)
            }
            isReconnecting -> {
                stringResource(R.string.bridge_reconnecting)
            }
            phase == SessionPhase.WaitingForPermission -> {
                stringResource(R.string.chrome_waiting_for_permission)
            }
            phase == SessionPhase.Connected && picture is ScreenPicture.Previous -> {
                stringResource(
                    when (picture.change) {
                        PictureChange.Reconnection -> R.string.bridge_reconnecting
                        PictureChange.Resize -> R.string.chrome_changing_resolution
                        PictureChange.VideoStart -> R.string.chrome_starting_high_performance
                    },
                )
            }
            else -> {
                null
            }
        }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(progressTitle != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
            )
        }
        AnimatedContent(
            targetState = progressTitle,
            modifier = Modifier.align(Alignment.Center),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "progress",
        ) { title ->
            if (title != null) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            title,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (picture == ScreenPicture.None || phase == SessionPhase.WaitingForPermission) {
                        FilledTonalButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }

    val failure = (phase as? SessionPhase.Failed)?.failure
    AlertHost(remember(failure) { failure?.let { failureAlert(controller, it, onClose) } }) {}

    (phase as? SessionPhase.ChoosingSession)?.prompt?.let { prompt ->
        SessionChoiceDialog(controller, prompt, onClose)
    }

    if (phase == SessionPhase.Disconnected && !controller.isClosing) {
        val close = stringResource(R.string.close)
        AlertHost(
            AlertState(
                title = stringResource(R.string.chrome_disconnected),
                actions = listOf(AlertState.Action(close, AlertState.Role.Cancel, perform = onClose)),
            ),
        ) {}
    }
}

private fun sharesOwnDisplay(
    controller: SessionController,
    prompt: SessionChoicePrompt,
): Boolean {
    val name = controller.loginName ?: return false
    if (prompt.consoleUser.isEmpty()) return false
    return prompt.consoleUser.equals(name, ignoreCase = true)
}

@Composable
private fun SessionChoiceDialog(
    controller: SessionController,
    prompt: SessionChoicePrompt,
    onClose: () -> Unit,
) {
    val sharesOwnDisplay = sharesOwnDisplay(controller, prompt)
    val cancel = {
        controller.answerSessionChoice(SessionChoice.Cancel)
        onClose()
    }
    val message =
        when {
            sharesOwnDisplay -> stringResource(R.string.chrome_account_logged_in)
            prompt.consoleUser.isNotEmpty() -> stringResource(R.string.chrome_user_using_it, prompt.consoleUser)
            else -> null
        }
    val choices =
        buildList {
            if (prompt.allowsOwnSession) {
                add(Triple(Icons.Outlined.AccountCircle, R.string.chrome_log_in_own_session, SessionChoice.OwnSession))
            }
            if (prompt.allowsShareConsole) {
                add(Triple(Icons.AutoMirrored.Outlined.ScreenShare, if (sharesOwnDisplay) R.string.chrome_share_the_screen else R.string.chrome_ask_to_share, SessionChoice.ShareConsole))
            }
        }
    AlertDialog(
        onDismissRequest = cancel,
        confirmButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.chrome_in_use, controller.title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (message != null) {
                    SelectionContainer { Text(message, Modifier.padding(bottom = 8.dp)) }
                }
                for ((icon, label, choice) in choices) {
                    ListItem(
                        headlineContent = { Text(stringResource(label)) },
                        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { controller.answerSessionChoice(choice) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        },
    )
}

private fun failureAlert(
    controller: SessionController,
    failure: SessionFailure,
    onClose: () -> Unit,
): AlertState {
    val resources = App.instance.resources
    val title = failure.title(controller.title, controller.route.remoteProtocol)
    val kind = failure.kind
    val message: String
    val connectAnyway: () -> Unit
    when {
        kind is SessionFailure.Kind.CertificateChanged && controller.route.remoteProtocol == RemoteProtocol.Rdp -> {
            message = resources.getString(R.string.chrome_certificate_renewed)
            connectAnyway = { controller.trustCertificate(kind.fingerprint) }
        }
        kind is SessionFailure.Kind.CertificateChanged -> {
            message = resources.getString(R.string.chrome_mac_reinstalled)
            connectAnyway = { controller.trustCertificate(kind.fingerprint) }
        }
        kind == SessionFailure.Kind.UnverifiedServer -> {
            message = resources.getString(R.string.chrome_unverified_server)
            connectAnyway = { controller.allowUnverifiedLogin() }
        }
        else -> {
            return AlertState.failure(title, failure.message, resources.getString(R.string.close), onClose)
        }
    }
    return AlertState(
        title = title,
        message = message,
        actions =
        listOf(
            AlertState.Action(resources.getString(R.string.chrome_connect_anyway), perform = connectAnyway),
            AlertState.Action(resources.getString(R.string.close), AlertState.Role.Cancel, isDefault = true, perform = onClose),
        ),
    )
}
