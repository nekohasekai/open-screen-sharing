package io.nekohasekai.screensharing.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertHost
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.FormButton
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.SectionHeader
import io.nekohasekai.screensharing.app.SwitchRow
import io.nekohasekai.screensharing.app.groupItemColors
import io.nekohasekai.screensharing.app.slideTransition
import io.nekohasekai.screensharing.discovery.NearbyBrowser
import io.nekohasekai.screensharing.discovery.NearbyComputer
import io.nekohasekai.screensharing.model.Computer
import io.nekohasekai.screensharing.model.ComputerStore
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.session.ServerAuthentication
import io.nekohasekai.screensharing.session.SessionCredentials
import io.nekohasekai.screensharing.session.SessionRoute

class ComputerEditorSetup(
    val credentials: SessionCredentials,
    val server: ServerAuthentication?,
    val connect: (SessionRoute) -> Unit,
    val back: () -> Unit,
)

private enum class ServerChoice {
    Vnc,
    AppleVnc,
    Rdp,
    ;

    val remoteProtocol: RemoteProtocol get() = if (this == Rdp) RemoteProtocol.Rdp else RemoteProtocol.Vnc

    val usesHighPerformance: Boolean get() = this == AppleVnc

    companion object {
        fun of(computer: Computer): ServerChoice = when {
            computer.remoteProtocol == RemoteProtocol.Rdp -> Rdp
            computer.usesHighPerformance -> AppleVnc
            else -> Vnc
        }
    }
}

private enum class EditorProblem {
    EmptyName,
}

@Composable
fun ComputerEditor(computer: Computer, setup: ComputerEditorSetup?, dismiss: () -> Unit) {
    var name by remember(computer.id) { mutableStateOf(computer.displayName) }
    var endpoints by remember(computer.id) { mutableStateOf(computer.endpoints) }
    var authenticates by remember(computer.id) { mutableStateOf(computer.authenticates) }
    var username by remember(computer.id) { mutableStateOf(computer.username) }
    var password by remember(computer.id) { mutableStateOf(computer.password) }
    var choice by remember(computer.id) { mutableStateOf(ServerChoice.of(computer)) }
    var keepsAlive by remember(computer.id) { mutableStateOf(computer.keepsAlive) }
    var disablesUDP by remember(computer.id) { mutableStateOf(computer.disablesUDP) }
    var reversesScrolling by remember(computer.id) { mutableStateOf(computer.reversesScrolling) }
    var showingAddresses by remember(computer.id) { mutableStateOf(false) }
    var problem by remember { mutableStateOf<EditorProblem?>(null) }
    var alert by remember { mutableStateOf<AlertState?>(null) }
    val nameFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    if (setup == null) {
        DisposableEffect(Unit) {
            NearbyBrowser.shared.start()
            onDispose { NearbyBrowser.shared.stop() }
        }
    }

    val trimmedUsername = username.trim()
    val activeEndpoints = if (setup != null) computer.endpoints else endpoints
    val isComplete = activeEndpoints.isNotEmpty() && (!authenticates || trimmedUsername.isNotEmpty())
    val editorCredentials: SessionCredentials? = when {
        setup != null -> setup.credentials
        authenticates && trimmedUsername.isNotEmpty() && password.isNotEmpty() -> SessionCredentials.Account(trimmedUsername, password)
        else -> null
    }
    val endpointsSummary = when {
        endpoints.isEmpty() -> ""
        endpoints.size == 1 -> endpoints[0].address.description(choice.remoteProtocol.defaultPort)
        else -> endpoints.size.toString()
    }

    fun save() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            problem = EditorProblem.EmptyName
            nameFocus.requestFocus()
            return
        }
        if (!isComplete) {
            if (setup == null) usernameFocus.requestFocus()
            return
        }
        val saved = try {
            ComputerStore.save(computer) { stored ->
                val previousEndpoints = stored.endpoints
                val sharesEndpoint = activeEndpoints.any { candidate ->
                    previousEndpoints.any { it.address.matches(candidate.address) }
                }
                stored.copy(
                    name = trimmedName,
                    trustedFingerprint = if (setup == null && (stored.remoteProtocol != choice.remoteProtocol || !sharesEndpoint)) {
                        ""
                    } else {
                        stored.trustedFingerprint
                    },
                    authenticates = authenticates,
                    username = if (authenticates) trimmedUsername else "",
                    password = if (authenticates) password else "",
                    remoteProtocol = choice.remoteProtocol,
                    usesHighPerformance = choice.usesHighPerformance,
                    keepsAlive = keepsAlive,
                    disablesUDP = disablesUDP,
                    reversesScrolling = choice != ServerChoice.AppleVnc && reversesScrolling,
                ).withEndpoints(activeEndpoints)
            }
        } catch (error: Exception) {
            alert = AlertState.failure(App.instance.getString(R.string.home_save_failed), error)
            return
        }
        if (setup != null) {
            setup.connect(SessionRoute(saved).copy(credentials = setup.credentials, server = setup.server))
        }
        dismiss()
    }

    AnimatedContent(
        targetState = showingAddresses && setup == null,
        transitionSpec = { slideTransition(forward = targetState) },
        label = "editorAddresses",
    ) { isAddresses ->
        if (isAddresses) {
            BackHandler { showingAddresses = false }
            AddressesView(
                endpoints = endpoints,
                onEndpointsChange = { endpoints = it },
                computerName = name.trim().ifEmpty { computer.displayName },
                remoteProtocol = choice.remoteProtocol,
                usesHighPerformance = choice.usesHighPerformance,
                trustedFingerprint = computer.trustedFingerprint,
                credentials = editorCredentials,
                back = { showingAddresses = false },
            )
        } else {
            PageScaffold(
                title = stringResource(if (setup == null) R.string.home_edit_connection else R.string.home_new_connection),
                navigationIcon = {
                    IconButton(onClick = setup?.back ?: dismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.home_back))
                    }
                },
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        problem = null
                    },
                    modifier = FieldModifier.focusRequester(nameFocus),
                    label = { Text(stringResource(R.string.home_name)) },
                    isError = problem == EditorProblem.EmptyName,
                    supportingText = if (problem == EditorProblem.EmptyName) {
                        { Text(stringResource(R.string.home_enter_name)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = when {
                            setup == null && authenticates -> ImeAction.Next
                            setup == null -> ImeAction.Done
                            else -> ImeAction.Go
                        },
                    ),
                    keyboardActions = KeyboardActions(onAny = {
                        if (setup == null && authenticates) usernameFocus.requestFocus() else save()
                    }),
                    singleLine = true,
                )
                if (setup == null) {
                    Spacer(Modifier.height(8.dp))
                    ListGroup {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.home_addresses_and_networks)) },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        endpointsSummary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                }
                            },
                            colors = groupItemColors(),
                            modifier = Modifier.clickable { showingAddresses = true },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    DropdownField(
                        label = stringResource(R.string.home_connection_type),
                        selected = choice,
                        options = ServerChoice.entries,
                        optionLabel = {
                            stringResource(
                                when (it) {
                                    ServerChoice.Vnc -> R.string.home_vnc
                                    ServerChoice.AppleVnc -> R.string.home_apple_vnc
                                    ServerChoice.Rdp -> R.string.home_rdp
                                },
                            )
                        },
                        onSelect = { newChoice ->
                            val oldDefaultPort = choice.remoteProtocol.defaultPort
                            val newDefaultPort = newChoice.remoteProtocol.defaultPort
                            if (oldDefaultPort != newDefaultPort) {
                                endpoints = endpoints.map { endpoint ->
                                    if (endpoint.address.port == oldDefaultPort) {
                                        endpoint.copy(address = endpoint.address.copy(port = newDefaultPort))
                                    } else {
                                        endpoint
                                    }
                                }
                            }
                            choice = newChoice
                        },
                    )
                    SectionHeader(stringResource(R.string.home_authentication))
                    ListGroup {
                        SwitchRow(stringResource(R.string.home_enabled), authenticates, onCheckedChange = { authenticates = it })
                    }
                    if (authenticates) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            modifier = FieldModifier.focusRequester(usernameFocus),
                            label = { Text(stringResource(R.string.home_user_name)) },
                            placeholder = { Text(stringResource(R.string.home_required)) },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(onAny = { passwordFocus.requestFocus() }),
                            singleLine = true,
                        )
                        PasswordField(
                            value = password,
                            onValueChange = { password = it },
                            label = stringResource(R.string.home_password),
                            focusRequester = passwordFocus,
                            imeAction = ImeAction.Done,
                            placeholder = stringResource(R.string.home_optional),
                        )
                    }
                }
                if (choice == ServerChoice.Rdp || choice == ServerChoice.AppleVnc) {
                    SectionHeader(stringResource(R.string.home_connection))
                    ListGroup {
                        if (choice == ServerChoice.Rdp) {
                            SwitchRow(stringResource(R.string.home_disable_udp), disablesUDP, onCheckedChange = { disablesUDP = it })
                        }
                        SwitchRow(stringResource(R.string.home_keep_alive), keepsAlive, onCheckedChange = { keepsAlive = it })
                    }
                }
                if (choice != ServerChoice.AppleVnc) {
                    SectionHeader(stringResource(R.string.home_control))
                    ListGroup {
                        SwitchRow(stringResource(R.string.home_reverse_scroll_direction), reversesScrolling, onCheckedChange = { reversesScrolling = it })
                    }
                }
                Spacer(Modifier.height(8.dp))
                FormButton(stringResource(if (setup == null) R.string.save else R.string.connect), enabled = setup != null || isComplete, onClick = dropUnlessResumed { save() })
            }
        }
    }
    AlertHost(alert) { alert = null }
}

@Composable
fun NearbyDetailsView(computer: NearbyComputer, onDismiss: () -> Unit) {
    val current = NearbyBrowser.shared.computers.firstOrNull { it.id == computer.id } ?: computer
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        title = { Text(current.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.home_server_address), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val host = current.hostName ?: current.host
                    if (host != null) {
                        Text(host, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    } else {
                        InlineProgress(stringResource(R.string.home_looking_up))
                    }
                }
                val port = current.port
                if (port != null) {
                    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.home_port), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(port.toString(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
    )
}
