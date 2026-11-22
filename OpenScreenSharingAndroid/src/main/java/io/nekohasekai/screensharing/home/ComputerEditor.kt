package io.nekohasekai.screensharing.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
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
import io.nekohasekai.screensharing.discovery.NearbyBrowser
import io.nekohasekai.screensharing.discovery.NearbyComputer
import io.nekohasekai.screensharing.model.Computer
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.model.ComputerStore
import io.nekohasekai.screensharing.model.ConnectionTarget
import io.nekohasekai.screensharing.model.NetworkConfiguration
import io.nekohasekai.screensharing.model.NetworkQuality
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.session.ServerAuthentication
import io.nekohasekai.screensharing.session.SessionCredentials
import io.nekohasekai.screensharing.session.SessionRoute
import kotlin.math.roundToInt

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
    InvalidAddress,
}

@Composable
fun ComputerEditor(computer: Computer, setup: ComputerEditorSetup?, dismiss: () -> Unit) {
    var name by remember(computer.id) { mutableStateOf(computer.displayName) }
    var address by remember(computer.id) { mutableStateOf(computer.addressDescription) }
    var authenticates by remember(computer.id) { mutableStateOf(computer.authenticates) }
    var username by remember(computer.id) { mutableStateOf(computer.username) }
    var password by remember(computer.id) { mutableStateOf(computer.password) }
    var choice by remember(computer.id) { mutableStateOf(ServerChoice.of(computer)) }
    var networkConfiguration by remember(computer.id) { mutableStateOf(computer.networkConfiguration) }
    var keepsAlive by remember(computer.id) { mutableStateOf(computer.keepsAlive) }
    var disablesUDP by remember(computer.id) { mutableStateOf(computer.disablesUDP) }
    var reversesScrolling by remember(computer.id) { mutableStateOf(computer.reversesScrolling) }
    var problem by remember { mutableStateOf<EditorProblem?>(null) }
    var alert by remember { mutableStateOf<AlertState?>(null) }
    val nameFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    val trimmedUsername = username.trim()
    val isComplete = !authenticates || trimmedUsername.isNotEmpty()

    fun save() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            problem = EditorProblem.EmptyName
            nameFocus.requestFocus()
            return
        }
        val endpoint = ConnectionTarget.parseEndpoint(address.trim())
        if (endpoint == null) {
            problem = EditorProblem.InvalidAddress
            addressFocus.requestFocus()
            return
        }
        val parsed = ComputerAddress(endpoint.first, endpoint.second ?: choice.remoteProtocol.defaultPort)
        if (!isComplete) {
            if (setup == null) usernameFocus.requestFocus()
            return
        }
        val saved = try {
            ComputerStore.save(computer) { stored ->
                stored.copy(
                    name = trimmedName,
                    trustedFingerprint = if (stored.remoteProtocol != choice.remoteProtocol || !stored.address.matches(parsed)) "" else stored.trustedFingerprint,
                    host = parsed.host,
                    port = parsed.port,
                    authenticates = authenticates,
                    username = if (authenticates) trimmedUsername else "",
                    password = if (authenticates) password else "",
                    remoteProtocol = choice.remoteProtocol,
                    usesHighPerformance = choice.usesHighPerformance,
                    keepsAlive = keepsAlive,
                    disablesUDP = disablesUDP,
                    reversesScrolling = choice != ServerChoice.AppleVnc && reversesScrolling,
                ).withNetworkConfiguration(networkConfiguration)
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
            onValueChange = { name = it },
            modifier = FieldModifier.focusRequester(nameFocus),
            label = { Text(stringResource(R.string.home_name)) },
            isError = problem == EditorProblem.EmptyName,
            supportingText = if (problem == EditorProblem.EmptyName) {
                { Text(stringResource(R.string.home_enter_name)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = if (setup == null) ImeAction.Next else ImeAction.Go),
            keyboardActions = KeyboardActions(onAny = { if (setup == null) addressFocus.requestFocus() else save() }),
            singleLine = true,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = FieldModifier.focusRequester(addressFocus),
            enabled = setup == null,
            label = { Text(stringResource(R.string.home_server_address)) },
            isError = problem == EditorProblem.InvalidAddress,
            supportingText = if (problem == EditorProblem.InvalidAddress) {
                { Text(stringResource(R.string.home_enter_valid_address)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = if (authenticates && setup == null) ImeAction.Next else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onNext = { usernameFocus.requestFocus() }),
            singleLine = true,
        )
        DropdownField(
            label = stringResource(R.string.home_server_type),
            selected = choice,
            options = buildList {
                if (setup == null || computer.remoteProtocol == RemoteProtocol.Vnc) {
                    add(ServerChoice.Vnc)
                    add(ServerChoice.AppleVnc)
                }
                if (setup == null || computer.remoteProtocol == RemoteProtocol.Rdp) {
                    add(ServerChoice.Rdp)
                }
            },
            optionLabel = {
                stringResource(
                    when (it) {
                        ServerChoice.Vnc -> R.string.home_vnc
                        ServerChoice.AppleVnc -> R.string.home_apple_vnc
                        ServerChoice.Rdp -> R.string.home_rdp
                    },
                )
            },
            onSelect = { choice = it },
            enabled = !(setup != null && computer.remoteProtocol == RemoteProtocol.Rdp),
        )
        if (choice == ServerChoice.AppleVnc) {
            DropdownField(
                label = stringResource(R.string.home_network_quality),
                selected = networkConfiguration.quality,
                options = NetworkQuality.entries,
                optionLabel = {
                    stringResource(
                        when (it) {
                            NetworkQuality.LocalNetwork -> R.string.home_local_network
                            NetworkQuality.Internet -> R.string.home_internet
                        },
                    )
                },
                onSelect = { networkConfiguration = networkConfiguration.copy(quality = it) },
            )
            if (networkConfiguration.quality == NetworkQuality.Internet) {
                Spacer(Modifier.height(8.dp))
                ListGroup {
                    SwitchRow(
                        stringResource(R.string.home_high_definition),
                        networkConfiguration.internetHighDefinition,
                        onCheckedChange = { networkConfiguration = networkConfiguration.copy(internetHighDefinition = it) },
                    )
                    if (networkConfiguration.internetHighDefinition) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.home_network_bandwidth),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(R.string.home_mbps, networkConfiguration.bandwidthMbps),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val range = NetworkConfiguration.bandwidthRangeMbps
                            Slider(
                                value = networkConfiguration.bandwidthMbps.toFloat(),
                                onValueChange = { networkConfiguration = networkConfiguration.copy(internetBandwidthMbps = it.roundToInt()) },
                                valueRange = range.first.toFloat()..range.last.toFloat(),
                                steps = range.last - range.first - 1,
                            )
                        }
                    }
                }
            }
        }
        if (setup == null) {
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
