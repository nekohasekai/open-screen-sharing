package io.nekohasekai.screensharing.sessionui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertHost
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.FormButton
import io.nekohasekai.screensharing.app.FormDialog
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.SectionFooter
import io.nekohasekai.screensharing.app.SwitchRow
import io.nekohasekai.screensharing.home.FieldModifier
import io.nekohasekai.screensharing.home.InlineProgress
import io.nekohasekai.screensharing.home.PasswordField
import io.nekohasekai.screensharing.session.CredentialPrompt
import io.nekohasekai.screensharing.session.ProbeResult
import io.nekohasekai.screensharing.session.ServerProber
import io.nekohasekai.screensharing.session.SessionCredentials
import io.nekohasekai.screensharing.session.SessionFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun CredentialsView(
    prompt: CredentialPrompt,
    computerName: String,
    onSubmit: (SessionCredentials, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var username by remember { mutableStateOf(prompt.username) }
    var password by remember { mutableStateOf("") }
    var remembers by remember { mutableStateOf(prompt.remembers) }
    var task by remember { mutableStateOf<Job?>(null) }
    var alert by remember { mutableStateOf<AlertState?>(null) }
    val scope = rememberCoroutineScope()
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    val asksForUsername = prompt.form is CredentialPrompt.Form.Account
    val allowsRequest = prompt.form == CredentialPrompt.Form.Account(allowsRequest = true)
    val trimmedUsername = username.trim()
    val isValid = if (asksForUsername) trimmedUsername.isNotEmpty() else password.isNotEmpty()

    fun signIn() {
        if (task != null || !isValid) return
        val credentials = if (asksForUsername) SessionCredentials.Account(trimmedUsername, password) else SessionCredentials.VncPassword(password)
        onSubmit(credentials, remembers)
    }

    fun signInWith(account: SessionCredentials) {
        if (task != null) return
        task =
            scope.launch {
                val result = ServerProber.checkLogin(prompt.server, account, prompt.allowsUnverifiedLogin)
                task = null
                if (result is ProbeResult.Failure) {
                    val failure = result.failure
                    when (failure.kind) {
                        is SessionFailure.Kind.CertificateChanged, SessionFailure.Kind.UnverifiedServer -> {
                            Unit
                        }
                        else -> {
                            alert = AlertState.failure(failure.title(computerName, prompt.server.remoteProtocol), failure.message)
                            return@launch
                        }
                    }
                }
                onSubmit(account, remembers)
            }
    }

    fun askForPermission() {
        onSubmit(SessionCredentials.AskForPermission(trimmedUsername), remembers)
    }

    LaunchedEffect(Unit) {
        withFrameNanos {}
        if (asksForUsername && username.isEmpty()) usernameFocus.requestFocus() else passwordFocus.requestFocus()
    }

    FormDialog(onDismissRequest = onCancel) {
        PageScaffold(
            title = computerName,
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                }
            },
        ) {
            if (asksForUsername) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = FieldModifier.focusRequester(usernameFocus),
                    enabled = task == null,
                    label = { Text(stringResource(R.string.home_user_name)) },
                    keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onAny = { passwordFocus.requestFocus() }),
                    singleLine = true,
                )
            }
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.home_password),
                focusRequester = passwordFocus,
                imeAction = ImeAction.Go,
                onImeAction = ::signIn,
                enabled = task == null,
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when {
                    task != null -> {
                        InlineProgress(stringResource(R.string.home_signing_in))
                    }
                    prompt.failed -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(if (asksForUsername) R.string.home_wrong_account else R.string.home_wrong_password),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            if (prompt.errorMessage.isNotEmpty()) {
                                SelectionContainer {
                                    Text(prompt.errorMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    else -> {
                        Text(
                            stringResource(if (asksForUsername) R.string.home_sign_in_account else R.string.home_sign_in_password, computerName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            FormButton(stringResource(R.string.chrome_sign_in), enabled = task == null && isValid, onClick = ::signIn)
            if (asksForUsername) {
                Spacer(Modifier.height(8.dp))
                ListGroup {
                    SwitchRow(stringResource(R.string.chrome_remember_password), remembers, onCheckedChange = { remembers = it })
                }
            }
            if (prompt.server.authentication.account) {
                OtherConnectionSection(computerID = prompt.computerID, signIn = ::signInWith, enabled = task == null)
            }
            if (allowsRequest) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = ::askForPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    enabled = task == null,
                ) {
                    Text(stringResource(R.string.chrome_ask_for_permission))
                }
                SectionFooter(stringResource(R.string.chrome_someone_needs_to_accept))
            }
        }
    }
    AlertHost(alert) { alert = null }
}
