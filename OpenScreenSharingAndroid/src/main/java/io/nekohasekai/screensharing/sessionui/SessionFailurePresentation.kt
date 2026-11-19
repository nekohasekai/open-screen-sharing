package io.nekohasekai.screensharing.sessionui

import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.session.MediaFailure
import io.nekohasekai.screensharing.session.SessionFailure

fun SessionFailure.presentation(computerName: String, remoteProtocol: RemoteProtocol): Pair<String, String?> {
    val resources = App.instance.resources
    val failed = resources.getString(R.string.home_failure_connection_failed)
    val closed = resources.getString(R.string.home_failure_connection_closed)
    val rdp = remoteProtocol == RemoteProtocol.Rdp
    fun known(reason: String) = reason.takeIf { message.isEmpty() }
    return when (val kind = kind) {
        SessionFailure.Kind.HostNotFound -> failed to known(resources.getString(R.string.home_failure_host_not_found, computerName))
        SessionFailure.Kind.PermissionDenied -> failed to known(
            resources.getString(if (rdp) R.string.home_failure_logon_denied else R.string.home_failure_declined, computerName),
        )
        SessionFailure.Kind.AuthenticationFailed -> failed to known(resources.getString(R.string.home_wrong_account))
        SessionFailure.Kind.Unsupported -> failed to known(resources.getString(R.string.home_no_sign_in_method, computerName))
        SessionFailure.Kind.Unreachable, SessionFailure.Kind.Other -> failed to null
        SessionFailure.Kind.ClosedByServer -> closed to known(
            resources.getString(if (rdp) R.string.home_failure_ended_session else R.string.home_failure_ended_screen_sharing, computerName),
        )
        SessionFailure.Kind.TakenOver -> closed to known(
            resources.getString(if (rdp) R.string.home_failure_taken_over_rdp else R.string.home_failure_taken_over),
        )
        SessionFailure.Kind.ConnectionLost, SessionFailure.Kind.Ended, SessionFailure.Kind.DrawingFailed -> closed to null
        is SessionFailure.Kind.CertificateChanged -> resources.getString(R.string.home_failure_cant_verify) to resources.getString(
            if (rdp) R.string.home_failure_certificate_changed_rdp else R.string.home_failure_certificate_changed,
            computerName,
        )
        SessionFailure.Kind.UnverifiedServer ->
            resources.getString(R.string.home_failure_cant_verify) to resources.getString(R.string.home_failure_unverified_server, computerName)
        is SessionFailure.Kind.HighPerformance -> when {
            kind.kind == MediaFailure.Kind.Busy ->
                resources.getString(R.string.home_failure_busy) to known(resources.getString(R.string.home_failure_busy_detail))
            kind.kind == MediaFailure.Kind.Unsupported && !kind.started ->
                failed to known(resources.getString(R.string.home_failure_high_performance_unsupported, computerName))
            else -> (if (kind.started) closed else failed) to null
        }
    }
}

fun SessionFailure.alert(
    computerName: String,
    remoteProtocol: RemoteProtocol,
    closeLabel: String = App.instance.getString(R.string.ok),
    onClose: () -> Unit = {},
): AlertState {
    val (title, reason) = presentation(computerName, remoteProtocol)
    if (reason == null) return AlertState.failure(title, message, closeLabel, onClose)
    return AlertState(title, reason, listOf(AlertState.Action(closeLabel, AlertState.Role.Cancel, isDefault = true, perform = onClose)))
}
