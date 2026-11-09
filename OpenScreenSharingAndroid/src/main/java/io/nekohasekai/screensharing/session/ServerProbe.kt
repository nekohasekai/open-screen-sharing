package io.nekohasekai.screensharing.session

import android.os.Parcelable
import io.nekohasekai.libsharing.Libsharing
import io.nekohasekai.libsharing.ProbeRequest
import io.nekohasekai.libsharing.SessionOptions
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.model.RemoteProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class ServerAuthentication(
    val account: Boolean,
    val requestPermission: Boolean,
    val vncPassword: Boolean,
    val noPassword: Boolean,
    val grantsRequests: Boolean,
) : Parcelable {
    val openCredentials: SessionCredentials?
        get() = when {
            noPassword -> SessionCredentials.NoAuthentication
            grantsRequests -> SessionCredentials.AskForPermission("")
            else -> null
        }
}

data class ProbedServer(
    val address: ComputerAddress,
    val remoteProtocol: RemoteProtocol,
    val authentication: ServerAuthentication,
    val fingerprint: String,
)

data class LoginCheck(val supportsHighPerformance: Boolean, val fingerprint: String)

sealed interface ProbeResult<out T> {
    data class Success<T>(val value: T) : ProbeResult<T>
    data class Failure(val failure: SessionFailure) : ProbeResult<Nothing>
}

object ServerProber {
    suspend fun probe(host: String, port: Int?, remoteProtocol: RemoteProtocol?): ProbeResult<ProbedServer> = Libsharing.newProbeRequest().perform {
        val probe = when (remoteProtocol) {
            RemoteProtocol.Vnc -> probeServer(host, port ?: RemoteProtocol.Vnc.defaultPort)
            RemoteProtocol.Rdp -> probeRDPServer(host, port ?: RemoteProtocol.Rdp.defaultPort)
            null -> probeProtocol(host, port ?: 0)
        }
        if (probe.errorCode != Libsharing.ErrorNone) {
            return@perform ProbeResult.Failure(failure(probe.errorCode, probe.errorMessage.orEmpty(), probe.fingerprint.orEmpty()))
        }
        ProbeResult.Success(
            ProbedServer(
                address = ComputerAddress(host, probe.port),
                remoteProtocol = if (probe.protocol == Libsharing.ProtocolRDP) RemoteProtocol.Rdp else RemoteProtocol.Vnc,
                authentication = ServerAuthentication(
                    account = probe.supportsAccount,
                    requestPermission = probe.supportsRequestControl || probe.supportsRequestObserve,
                    vncPassword = probe.supportsVNCPassword,
                    noPassword = probe.supportsNoPassword,
                    grantsRequests = probe.grantsRequests,
                ),
                fingerprint = probe.fingerprint.orEmpty(),
            ),
        )
    }

    suspend fun checkLogin(
        server: ProbedServer,
        credentials: SessionCredentials,
        allowsUnverifiedLogin: Boolean = false,
    ): ProbeResult<LoginCheck> = Libsharing.newProbeRequest().perform {
        val options = Libsharing.newSessionOptions()
        options.protocol = if (server.remoteProtocol == RemoteProtocol.Rdp) Libsharing.ProtocolRDP else Libsharing.ProtocolRFB
        options.host = server.address.host
        options.port = server.address.port
        options.trustedFingerprint = server.fingerprint
        options.allowUnverifiedLogin = allowsUnverifiedLogin
        credentials.applyTo(options, observes = false)
        val check = checkLogin(options)
        if (check.errorCode != Libsharing.ErrorNone) {
            return@perform ProbeResult.Failure(failure(check.errorCode, check.errorMessage.orEmpty(), check.fingerprint.orEmpty()))
        }
        ProbeResult.Success(LoginCheck(check.supportsHighPerformance, check.fingerprint.orEmpty()))
    }

    private suspend fun <T> ProbeRequest.perform(block: ProbeRequest.() -> T): T = coroutineScope {
        val result = async(Dispatchers.IO) {
            LibsharingLog.installed
            this@perform.block()
        }
        try {
            result.await()
        } catch (error: CancellationException) {
            this@perform.cancel()
            throw error
        }
    }

    private fun failure(code: Int, message: String, fingerprint: String): SessionFailure = when (code) {
        Libsharing.ErrorAuthentication -> SessionFailure(SessionFailure.Kind.AuthenticationFailed, message)
        Libsharing.ErrorPermissionDenied -> SessionFailure(SessionFailure.Kind.PermissionDenied, message)
        Libsharing.ErrorHostNotFound -> SessionFailure(SessionFailure.Kind.HostNotFound, message)
        Libsharing.ErrorNetwork -> SessionFailure(SessionFailure.Kind.Unreachable, message)
        Libsharing.ErrorUnsupportedServer -> SessionFailure(SessionFailure.Kind.Unsupported, message)
        Libsharing.ErrorCertificateChanged -> SessionFailure(SessionFailure.Kind.CertificateChanged(fingerprint), message)
        Libsharing.ErrorUnverifiedServer -> SessionFailure(SessionFailure.Kind.UnverifiedServer, message)
        else -> SessionFailure(SessionFailure.Kind.Other, message)
    }
}

fun SessionCredentials.applyTo(options: SessionOptions, observes: Boolean) {
    options.username = username
    when (this) {
        is SessionCredentials.Account -> {
            options.loginType = Libsharing.LoginAccount
            options.password = password
        }
        is SessionCredentials.VncPassword -> {
            options.loginType = Libsharing.LoginVNCPassword
            options.password = password
        }
        is SessionCredentials.AskForPermission -> options.loginType = if (observes) Libsharing.LoginRequestObserve else Libsharing.LoginRequestControl
        SessionCredentials.NoAuthentication -> options.loginType = Libsharing.LoginNone
    }
}
