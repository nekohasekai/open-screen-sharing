package io.nekohasekai.screensharing.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.FormButton
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.SectionHeader
import io.nekohasekai.screensharing.app.SwitchRow
import io.nekohasekai.screensharing.app.groupItemColors
import io.nekohasekai.screensharing.app.slideTransition
import io.nekohasekai.screensharing.discovery.NearbyBrowser
import io.nekohasekai.screensharing.model.AddressKind
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.model.ComputerEndpoint
import io.nekohasekai.screensharing.model.ConnectionTarget
import io.nekohasekai.screensharing.model.NetworkConfiguration
import io.nekohasekai.screensharing.model.NetworkQuality
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.session.ProbeResult
import io.nekohasekai.screensharing.session.ServerProber
import io.nekohasekai.screensharing.session.SessionCredentials
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import kotlin.math.roundToInt

private sealed interface AddressesPage {
    data object List : AddressesPage
    data class Edit(val endpoint: ComputerEndpoint, val isNew: Boolean) : AddressesPage

    val depth: Int
        get() = when (this) {
            List -> 0
            is Edit -> 1
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AddressesView(
    endpoints: List<ComputerEndpoint>,
    onEndpointsChange: (List<ComputerEndpoint>) -> Unit,
    computerName: String,
    remoteProtocol: RemoteProtocol,
    usesHighPerformance: Boolean,
    trustedFingerprint: String,
    credentials: SessionCredentials?,
    back: () -> Unit,
    setupNext: (() -> Unit)? = null,
) {
    var page by remember { mutableStateOf<AddressesPage>(AddressesPage.List) }
    var suggested by remember { mutableStateOf<List<ComputerAddress>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isSearching = true
        val snapshot = endpoints
        val sshHost = (
            snapshot.firstOrNull { it.address.kind == AddressKind.PrivateNetwork }
                ?: snapshot.firstOrNull { it.address.kind != AddressKind.Tailscale }
            )?.address?.host
        val combined = coroutineScope {
            val tailscaleDeferred = async {
                ServerProber.suggestTailscaleAddresses(
                    name = computerName,
                    endpoints = snapshot,
                    remoteProtocol = remoteProtocol,
                    trustedFingerprint = trustedFingerprint,
                    sshHost = sshHost,
                    credentials = credentials,
                )
            }
            val nearbyDeferred = async {
                discoverNearbyAddresses(snapshot, remoteProtocol, trustedFingerprint)
            }
            tailscaleDeferred.await() + nearbyDeferred.await()
        }
        val unique = mutableListOf<ComputerAddress>()
        for (address in combined) {
            if (unique.none { it.matches(address) }) {
                unique += address
            }
        }
        suggested = unique
        isSearching = false
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = { slideTransition(forward = targetState.depth >= initialState.depth) },
        contentKey = { it.depth },
        label = "addresses",
    ) { shown ->
        when (shown) {
            AddressesPage.List -> {
                val availableSuggestions = suggested.filter { candidate ->
                    endpoints.none { it.address.matches(candidate) }
                }
                PageScaffold(
                    title = stringResource(R.string.home_addresses_and_networks),
                    navigationIcon = {
                        IconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.home_back))
                        }
                    },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.home_addresses),
                            Modifier
                                .weight(1f)
                                .semantics { heading() },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(
                            onClick = {
                                page = AddressesPage.Edit(
                                    ComputerEndpoint(
                                        address = ComputerAddress("", remoteProtocol.defaultPort),
                                        networkConfiguration = NetworkConfiguration.defaultConfiguration(AddressKind.PrivateNetwork),
                                    ),
                                    isNew = true,
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.add))
                        }
                    }
                    ListGroup {
                        for (endpoint in endpoints) {
                            key(endpoint.id) {
                                EndpointListRow(
                                    endpoint = endpoint,
                                    remoteProtocol = remoteProtocol,
                                    usesHighPerformance = usesHighPerformance,
                                    canDelete = endpoints.size > 1,
                                    onClick = { page = AddressesPage.Edit(endpoint, isNew = false) },
                                    onDelete = {
                                        if (endpoints.size > 1) {
                                            onEndpointsChange(endpoints.filterNot { it.id == endpoint.id })
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (setupNext != null) {
                        Spacer(Modifier.height(8.dp))
                        FormButton(
                            stringResource(R.string.home_next),
                            enabled = endpoints.isNotEmpty(),
                            onClick = setupNext,
                        )
                    }
                    SectionHeader(stringResource(R.string.home_suggested))
                    ListGroup {
                        for (candidate in availableSuggestions) {
                            val preview = ComputerEndpoint(
                                address = candidate,
                                networkConfiguration = NetworkConfiguration.defaultConfiguration(candidate.kind),
                            )
                            ListItem(
                                headlineContent = {
                                    Text(candidate.description(remoteProtocol.defaultPort), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(preview.networkSummary(usesHighPerformance), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                leadingContent = {
                                    EndpointIcon(
                                        kind = candidate.kind,
                                        quality = preview.networkConfiguration.quality,
                                        usesHighPerformance = usesHighPerformance,
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        Icons.Outlined.AddCircleOutline,
                                        contentDescription = stringResource(R.string.add),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                colors = groupItemColors(),
                                modifier = Modifier.clickable {
                                    if (endpoints.none { it.address.matches(candidate) }) {
                                        onEndpointsChange(endpoints + preview)
                                    }
                                },
                            )
                        }
                        if (isSearching) {
                            InlineProgress(stringResource(R.string.home_looking_for_addresses), Modifier.padding(16.dp))
                        } else if (availableSuggestions.isEmpty()) {
                            Text(
                                stringResource(R.string.home_no_addresses_found),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            is AddressesPage.Edit -> {
                BackHandler { page = AddressesPage.List }
                AddressEditorView(
                    initial = shown.endpoint,
                    remoteProtocol = remoteProtocol,
                    usesHighPerformance = usesHighPerformance,
                    isNew = shown.isNew,
                    canDelete = !shown.isNew && endpoints.size > 1,
                    onSave = { updated ->
                        if (shown.isNew) {
                            if (endpoints.none { it.address.matches(updated.address) }) {
                                onEndpointsChange(endpoints + updated)
                            }
                        } else {
                            onEndpointsChange(endpoints.map { if (it.id == updated.id) updated else it })
                        }
                        page = AddressesPage.List
                    },
                    onDelete = {
                        if (endpoints.size > 1) {
                            onEndpointsChange(endpoints.filterNot { it.id == shown.endpoint.id })
                        }
                        page = AddressesPage.List
                    },
                    back = { page = AddressesPage.List },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EndpointListRow(
    endpoint: ComputerEndpoint,
    remoteProtocol: RemoteProtocol,
    usesHighPerformance: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val deleteLabel = stringResource(R.string.delete)
    Box {
        ListItem(
            headlineContent = {
                Text(endpoint.address.description(remoteProtocol.defaultPort), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(endpoint.networkSummary(usesHighPerformance), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = {
                EndpointIcon(
                    kind = endpoint.address.kind,
                    quality = endpoint.networkConfiguration.quality,
                    usesHighPerformance = usesHighPerformance,
                )
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            colors = groupItemColors(),
            modifier = Modifier
                .onSizeChanged { anchor = it }
                .then(if (canDelete) Modifier.secondaryClick { menuAt = it } else Modifier)
                .combinedClickable(
                    onLongClick = if (canDelete) ({ menuAt = Offset.Unspecified }) else null,
                    onLongClickLabel = if (canDelete) deleteLabel else null,
                    onClick = onClick,
                ),
        )
        if (canDelete) {
            DropdownMenu(
                expanded = menuAt != null,
                onDismissRequest = { menuAt = null },
                offset = menuOffset(menuAt, anchor),
            ) {
                DropdownMenuItem(
                    text = { Text(deleteLabel) },
                    onClick = {
                        menuAt = null
                        onDelete()
                    },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                )
            }
        }
    }
}

@Composable
private fun EndpointIcon(
    kind: AddressKind,
    quality: NetworkQuality,
    usesHighPerformance: Boolean,
) {
    val tint = MaterialTheme.colorScheme.primary
    when {
        kind == AddressKind.Tailscale -> Icon(
            painterResource(R.drawable.ic_tailscale),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = tint,
        )
        if (usesHighPerformance) quality == NetworkQuality.LocalNetwork else kind == AddressKind.PrivateNetwork -> Icon(
            Icons.Outlined.Wifi,
            contentDescription = null,
            tint = tint,
        )
        else -> Icon(
            Icons.Outlined.Language,
            contentDescription = null,
            tint = tint,
        )
    }
}

@Composable
private fun AddressEditorView(
    initial: ComputerEndpoint,
    remoteProtocol: RemoteProtocol,
    usesHighPerformance: Boolean,
    isNew: Boolean,
    canDelete: Boolean,
    onSave: (ComputerEndpoint) -> Unit,
    onDelete: () -> Unit,
    back: () -> Unit,
) {
    var addressText by remember(initial.id) {
        mutableStateOf(if (isNew) "" else initial.address.description(remoteProtocol.defaultPort))
    }
    var networkConfiguration by remember(initial.id) { mutableStateOf(initial.networkConfiguration) }
    var customizedNetwork by remember(initial.id) { mutableStateOf(false) }
    var invalidAddress by remember { mutableStateOf(false) }
    val addressFocus = remember { FocusRequester() }

    LaunchedEffect(isNew) {
        if (isNew) {
            withFrameNanos {}
            addressFocus.requestFocus()
        }
    }

    fun save() {
        val parsed = ConnectionTarget.parseEndpoint(addressText.trim())
        if (parsed == null) {
            invalidAddress = true
            addressFocus.requestFocus()
            return
        }
        val updatedAddress = ComputerAddress(parsed.first, parsed.second ?: remoteProtocol.defaultPort)
        val finalConfiguration = if (isNew && !customizedNetwork) {
            NetworkConfiguration.defaultConfiguration(updatedAddress.kind)
        } else {
            networkConfiguration
        }
        onSave(initial.copy(address = updatedAddress, networkConfiguration = finalConfiguration))
    }

    PageScaffold(
        title = stringResource(if (isNew) R.string.home_new_address else R.string.home_edit_address),
        navigationIcon = {
            IconButton(onClick = back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.home_back))
            }
        },
    ) {
        OutlinedTextField(
            value = addressText,
            onValueChange = { newValue ->
                addressText = newValue
                invalidAddress = false
                if (isNew && !customizedNetwork) {
                    val parsed = ConnectionTarget.parseEndpoint(newValue.trim())
                    if (parsed != null) {
                        val kind = AddressKind.of(parsed.first)
                        networkConfiguration = NetworkConfiguration.defaultConfiguration(kind)
                    }
                }
            },
            modifier = FieldModifier.focusRequester(addressFocus),
            label = { Text(stringResource(R.string.home_server_address)) },
            placeholder = { Text(stringResource(R.string.home_hostname)) },
            isError = invalidAddress,
            supportingText = if (invalidAddress) {
                { Text(stringResource(R.string.home_enter_valid_address)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onAny = { save() }),
            singleLine = true,
        )
        if (usesHighPerformance) {
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
                onSelect = {
                    customizedNetwork = true
                    networkConfiguration = networkConfiguration.copy(quality = it)
                },
            )
            if (networkConfiguration.quality == NetworkQuality.Internet) {
                Spacer(Modifier.height(8.dp))
                ListGroup {
                    SwitchRow(
                        stringResource(R.string.home_high_definition),
                        networkConfiguration.internetHighDefinition,
                        onCheckedChange = {
                            customizedNetwork = true
                            networkConfiguration = networkConfiguration.copy(internetHighDefinition = it)
                        },
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
                                onValueChange = {
                                    customizedNetwork = true
                                    networkConfiguration = networkConfiguration.copy(internetBandwidthMbps = it.roundToInt())
                                },
                                valueRange = range.first.toFloat()..range.last.toFloat(),
                                steps = range.last - range.first - 1,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FormButton(
            stringResource(if (isNew) R.string.add else R.string.save),
            enabled = addressText.trim().isNotEmpty(),
            onClick = ::save,
        )
        if (canDelete) {
            Spacer(Modifier.height(8.dp))
            ListGroup {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_delete_address)) },
                    leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    colors = groupItemColors(destructive = true),
                    modifier = Modifier.clickable(onClick = onDelete),
                )
            }
        }
    }
}

@Composable
internal fun ComputerEndpoint.networkSummary(usesHighPerformance: Boolean): String {
    val kind = address.kind
    if (!usesHighPerformance) {
        return when (kind) {
            AddressKind.PrivateNetwork -> stringResource(R.string.home_local_network)
            AddressKind.Tailscale -> "Tailscale"
            AddressKind.PublicNetwork -> stringResource(R.string.home_internet)
        }
    }
    return when (networkConfiguration.quality) {
        NetworkQuality.LocalNetwork -> {
            val local = stringResource(R.string.home_local_network)
            if (kind == AddressKind.Tailscale) "Tailscale · $local" else local
        }
        NetworkQuality.Internet -> {
            if (networkConfiguration.internetHighDefinition) {
                val prefix = if (kind == AddressKind.Tailscale) "Tailscale" else stringResource(R.string.home_internet)
                val hd = stringResource(R.string.home_high_definition)
                val rate = stringResource(R.string.home_mbps, networkConfiguration.bandwidthMbps)
                "$prefix · $hd · $rate"
            } else {
                val internet = stringResource(R.string.home_internet)
                if (kind == AddressKind.Tailscale) "Tailscale · $internet" else internet
            }
        }
    }
}

private suspend fun discoverNearbyAddresses(
    existing: List<ComputerEndpoint>,
    remoteProtocol: RemoteProtocol,
    trustedFingerprint: String,
): List<ComputerAddress> {
    if (trustedFingerprint.isEmpty()) return emptyList()
    val candidates = NearbyBrowser.shared.computers
        .filter { it.remoteProtocol == remoteProtocol }
        .mapNotNull { it.address }
        .filter { candidate -> existing.none { it.address.matches(candidate) } }
    if (candidates.isEmpty()) return emptyList()
    return coroutineScope {
        candidates.map { candidate ->
            async {
                val result = ServerProber.probe(candidate.host, candidate.port, remoteProtocol)
                if (result is ProbeResult.Success &&
                    result.value.fingerprint.isNotEmpty() &&
                    result.value.fingerprint.equals(trustedFingerprint, ignoreCase = true)
                ) {
                    result.value.address
                } else {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }
}
