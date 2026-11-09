package io.nekohasekai.screensharing.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.FormButton
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.SectionHeader
import io.nekohasekai.screensharing.discovery.ComputerAvailability
import io.nekohasekai.screensharing.discovery.LocalNetworkAccess
import io.nekohasekai.screensharing.discovery.NearbyBrowser
import io.nekohasekai.screensharing.discovery.NearbyComputer
import io.nekohasekai.screensharing.model.ComputerStore
import io.nekohasekai.screensharing.model.ConnectionTarget
import io.nekohasekai.screensharing.model.DeviceAppearance
import io.nekohasekai.screensharing.model.RemoteLink
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.session.ProbeResult
import io.nekohasekai.screensharing.session.ProbedServer
import io.nekohasekai.screensharing.session.ServerProber
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun ConnectToView(
    link: RemoteLink?,
    close: () -> Unit,
    signIn: (NewComputer) -> Unit,
    signedIn: (SignedInComputer) -> Unit,
) {
    val nearby = NearbyBrowser.shared
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<SetupProblem?>(null) }
    var task by remember { mutableStateOf<Job?>(null) }
    var checkedID by remember { mutableStateOf<String?>(null) }
    var nearbyProblem by remember { mutableStateOf<SetupProblem?>(null) }
    var details by remember { mutableStateOf<NearbyComputer?>(null) }
    var opensLink by rememberSaveable { mutableStateOf(true) }
    val addressFocus = remember { FocusRequester() }
    var focusRequest by remember { mutableIntStateOf(0) }

    val isChecking = task != null
    val typed = text.trim()
    val found = nearby.computers.filter { found ->
        ComputerStore.computers.none { saved ->
            saved.remoteProtocol == found.remoteProtocol &&
                (found.address?.let { saved.address.matches(it) } == true || nearby.computer(saved.address, saved.remoteProtocol)?.id == found.id)
        }
    }

    suspend fun check(host: String, port: Int?, remoteProtocol: RemoteProtocol?, makeComputer: (ProbedServer) -> NewComputer): SetupProblem? {
        val server = when (val result = ServerProber.probe(host, port, remoteProtocol)) {
            is ProbeResult.Success -> result.value
            is ProbeResult.Failure -> return SetupProblem(result.failure, host, asksForUsername = true)
        }
        currentCoroutineContext().ensureActive()
        val computer = makeComputer(server)
        val credentials = server.authentication.openCredentials
        if (credentials == null) {
            if (!server.authentication.account && !server.authentication.vncPassword) {
                return SetupProblem(App.instance.getString(R.string.home_no_sign_in_method, computer.name))
            }
            signIn(computer)
            return null
        }
        return when (val result = ServerProber.checkLogin(server, credentials)) {
            is ProbeResult.Success -> {
                currentCoroutineContext().ensureActive()
                signedIn(computer.signedIn(credentials, result.value))
                null
            }
            is ProbeResult.Failure -> SetupProblem(result.failure, host, asksForUsername = false)
        }
    }

    fun choose(computer: NearbyComputer) {
        val address = computer.address
        if (task != null || address == null) return
        nearbyProblem = null
        checkedID = computer.id
        task = scope.launch {
            val problem = check(address.host, address.port, computer.remoteProtocol) { server ->
                NewComputer(name = computer.name, model = computer.model, server = server)
            }
            task = null
            checkedID = null
            nearbyProblem = problem
        }
    }

    fun connect(target: ConnectionTarget) {
        problem = null
        nearbyProblem = null
        task = scope.launch {
            val failure = check(target.host, target.port, target.remoteProtocol) { server ->
                val nearbyComputer = nearby.computer(server.address, server.remoteProtocol)
                NewComputer(
                    name = nearbyComputer?.name ?: server.address.bonjourServiceName ?: server.address.host,
                    model = nearbyComputer?.model,
                    username = target.username.orEmpty(),
                    password = target.password.orEmpty(),
                    server = server,
                )
            }
            task = null
            if (failure != null) {
                problem = failure
                focusRequest++
            }
        }
    }

    val localNetworkAccess = LocalNetworkAccess.permission.rememberRequester()

    fun begin(target: ConnectionTarget) {
        if (task != null) return
        problem = null
        nearbyProblem = null
        task = scope.launch {
            LocalNetworkAccess.request(target.host, localNetworkAccess)
            connect(target)
        }
    }

    fun start() {
        val typed = text.trim()
        if (task != null || typed.isEmpty()) return
        val target = ConnectionTarget.parse(typed)
        if (target == null) {
            problem = SetupProblem(App.instance.getString(R.string.home_invalid_address, typed))
            return
        }
        begin(target)
    }

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) addressFocus.requestFocus()
    }

    LaunchedEffect(Unit) {
        if (!opensLink || link == null) return@LaunchedEffect
        opensLink = false
        text = (link.username?.let { "$it@" } ?: "") + link.address.description(link.remoteProtocol.defaultPort)
        begin(ConnectionTarget(link.address.host, link.address.port, link.remoteProtocol, link.username, link.password))
    }

    PageScaffold(
        title = stringResource(R.string.home_new_connection),
        navigationIcon = {
            IconButton(onClick = {
                task?.cancel()
                close()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.home_back))
            }
        },
    ) {
        val shownProblem = problem
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                problem = null
            },
            modifier = FieldModifier.focusRequester(addressFocus),
            enabled = !isChecking,
            label = { Text(stringResource(R.string.home_server_address)) },
            placeholder = { Text(stringResource(R.string.home_hostname)) },
            isError = shownProblem != null,
            supportingText = when {
                isChecking && checkedID == null -> {
                    { InlineProgress(stringResource(R.string.home_connecting)) }
                }
                shownProblem != null -> {
                    { SetupProblemView(shownProblem) }
                }
                else -> null
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onAny = { start() }),
            singleLine = true,
        )
        FormButton(stringResource(R.string.home_next), enabled = typed.isNotEmpty() && !isChecking, onClick = ::start)
        SectionHeader(stringResource(R.string.home_network))
        ListGroup {
            if (found.isEmpty()) {
                NearbyPlaceholder(Modifier.padding(16.dp))
            }
            for (computer in found) {
                key(computer.id) {
                    NearbyRow(
                        computer,
                        isChecked = checkedID == computer.id,
                        enabled = computer.address != null && !isChecking,
                        choose = { choose(computer) },
                        showDetails = { details = computer },
                    )
                }
            }
        }
        nearbyProblem?.let { SetupProblemView(it, Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
    }
    details?.let { computer -> NearbyDetailsView(computer) { details = null } }
}

@Composable
private fun NearbyPlaceholder(modifier: Modifier) {
    val nearby = NearbyBrowser.shared
    when (val state = nearby.state) {
        is NearbyBrowser.State.Failed -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.home_cant_look_nearby))
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> if (nearby.hasSearched) {
            Text(stringResource(R.string.home_no_computers_found), modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            InlineProgress(stringResource(R.string.home_looking_for_computers), modifier)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NearbyRow(computer: NearbyComputer, isChecked: Boolean, enabled: Boolean, choose: () -> Unit, showDetails: () -> Unit) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val viewDetails = stringResource(R.string.home_view_details)
    Box {
        ComputerRow(
            name = computer.name,
            detail = computer.address?.description(computer.remoteProtocol.defaultPort),
            device = DeviceAppearance(computer.model.orEmpty()),
            modifier = Modifier
                .onSizeChanged { anchor = it }
                .secondaryClick { menuAt = it }
                .combinedClickable(
                    onLongClick = { menuAt = Offset.Unspecified },
                    onLongClickLabel = viewDetails,
                    onClick = { if (enabled) choose() },
                )
                .alpha(if (enabled) 1f else 0.38f),
            availability = ComputerAvailability.Available,
            isPC = computer.remoteProtocol == RemoteProtocol.Rdp,
            trailingContent = if (isChecked) {
                { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
        )
        DropdownMenu(
            expanded = menuAt != null,
            onDismissRequest = { menuAt = null },
            offset = menuOffset(menuAt, anchor),
        ) {
            DropdownMenuItem(
                text = { Text(viewDetails) },
                onClick = {
                    menuAt = null
                    showDetails()
                },
                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            )
        }
    }
}
