package io.nekohasekai.screensharing.sessionui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.SectionHeader
import io.nekohasekai.screensharing.home.ComputerRow
import io.nekohasekai.screensharing.model.Computer
import io.nekohasekai.screensharing.model.ComputerStore
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.session.SessionCredentials
import java.util.UUID

@Composable
fun OtherConnectionSection(computerID: UUID?, signIn: (SessionCredentials) -> Unit, enabled: Boolean = true) {
    val saved = ComputerStore.computers.filter { it.authenticates && it.username.isNotEmpty() && it.password.isNotEmpty() }
    val seen = saved.filter { it.id == computerID }.mapTo(mutableSetOf()) { it.savedAccount }
    val computers = saved.filter { seen.add(it.savedAccount) }
    if (computers.isEmpty()) return
    SectionHeader(stringResource(R.string.home_other_connection))
    ListGroup {
        for (computer in computers) {
            ComputerRow(
                name = computer.displayName,
                detail = computer.username,
                device = computer.device,
                modifier = Modifier.clickable(enabled = enabled) { signIn(computer.savedAccount) },
                isPC = computer.remoteProtocol == RemoteProtocol.Rdp,
            )
        }
    }
}

private val Computer.savedAccount: SessionCredentials
    get() = SessionCredentials.Account(username, password)
