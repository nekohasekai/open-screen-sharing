package io.nekohasekai.screensharing.sessionui

import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.session.MediaFailure
import io.nekohasekai.screensharing.session.SessionFailure

fun SessionFailure.title(computerName: String, remoteProtocol: RemoteProtocol): String {
    val resources = App.instance.resources
    return when (val kind = kind) {
        SessionFailure.Kind.AuthenticationFailed -> resources.getString(R.string.home_failure_sign_in, computerName)
        SessionFailure.Kind.PermissionDenied -> resources.getString(R.string.home_failure_declined, computerName)
        SessionFailure.Kind.HostNotFound -> resources.getString(R.string.home_failure_find, computerName)
        SessionFailure.Kind.Unreachable, SessionFailure.Kind.Other -> resources.getString(R.string.home_failure_connect, computerName)
        SessionFailure.Kind.ConnectionLost -> resources.getString(R.string.home_failure_lost, computerName)
        SessionFailure.Kind.Ended, SessionFailure.Kind.DrawingFailed -> resources.getString(R.string.home_failure_disconnected, computerName)
        SessionFailure.Kind.ClosedByServer -> resources.getString(
            if (remoteProtocol == RemoteProtocol.Rdp) R.string.home_failure_ended_session else R.string.home_failure_ended_screen_sharing,
            computerName,
        )
        SessionFailure.Kind.Unsupported -> resources.getString(R.string.home_failure_unsupported, computerName)
        is SessionFailure.Kind.CertificateChanged -> resources.getString(
            if (remoteProtocol == RemoteProtocol.Rdp) R.string.home_failure_new_certificate else R.string.home_failure_verify,
            computerName,
        )
        SessionFailure.Kind.UnverifiedServer -> resources.getString(R.string.home_failure_verify, computerName)
        is SessionFailure.Kind.HighPerformance -> when (kind.kind) {
            MediaFailure.Kind.Busy -> resources.getString(R.string.home_failure_high_performance_busy, computerName)
            MediaFailure.Kind.Unsupported -> resources.getString(R.string.home_failure_high_performance_unsupported, computerName)
            MediaFailure.Kind.Undecodable -> resources.getString(R.string.home_failure_high_performance_undecodable)
            MediaFailure.Kind.Refused -> resources.getString(R.string.home_failure_high_performance_refused, computerName)
            else -> resources.getString(R.string.home_failure_high_performance_stopped)
        }
    }
}
