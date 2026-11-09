package io.nekohasekai.screensharing.session

import android.os.Parcel
import android.os.Parcelable
import io.nekohasekai.screensharing.model.Computer
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.model.DeviceType
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.model.UUIDSerializer
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Serializable
@Parcelize
data class SessionRoute(
    @Serializable(with = UUIDSerializer::class) val id: UUID = UUID.randomUUID(),
    @Serializable(with = UUIDSerializer::class) val computerID: UUID? = null,
    val name: String,
    val host: String,
    val port: Int,
    val username: String? = null,
    @Transient val credentials: @WriteWith<WithoutCredentials> SessionCredentials? = null,
    val model: String? = null,
    val remoteProtocol: RemoteProtocol,
    val usesHighPerformance: Boolean = false,
    val deviceType: DeviceType? = null,
    val server: ServerAuthentication? = null,
) : Parcelable {
    constructor(computer: Computer) : this(
        computerID = computer.id,
        name = computer.displayName,
        host = computer.host,
        port = computer.port,
        username = computer.username.ifEmpty { null },
        model = computer.model.ifEmpty { null },
        remoteProtocol = computer.remoteProtocol,
        usesHighPerformance = computer.usesHighPerformance,
        deviceType = if (computer.usesHighPerformance) computer.deviceType else null,
    )

    val address: ComputerAddress get() = ComputerAddress(host, port)

    val knownPlatform: RemotePlatform?
        get() {
            if (remoteProtocol == RemoteProtocol.Rdp) return RemotePlatform.Pc
            val type = deviceType ?: model?.let { DeviceType.of(it) } ?: return null
            return if (type.isIOS) RemotePlatform.IOS else RemotePlatform.Mac
        }
}

internal object WithoutCredentials : Parceler<SessionCredentials?> {
    override fun create(parcel: Parcel): SessionCredentials? = null

    override fun SessionCredentials?.write(parcel: Parcel, flags: Int) = Unit
}

object WindowCredentials {
    private val credentials = mutableMapOf<UUID, SessionCredentials>()

    fun send(route: SessionRoute): SessionRoute {
        val sent = route.credentials
        if (sent == null) {
            credentials.remove(route.id)
        } else {
            credentials[route.id] = sent
        }
        return route.copy(credentials = null)
    }

    fun receive(route: SessionRoute): SessionRoute = route.copy(credentials = credentials.remove(route.id))
}
