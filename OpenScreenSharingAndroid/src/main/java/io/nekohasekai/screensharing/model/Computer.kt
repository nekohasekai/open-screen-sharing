package io.nekohasekai.screensharing.model

import androidx.compose.ui.geometry.Size
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.nekohasekai.screensharing.session.DeviceInfo
import io.nekohasekai.screensharing.session.DisplaySelection
import io.nekohasekai.screensharing.session.SessionMemory
import io.nekohasekai.screensharing.session.VirtualDisplayMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class RemoteProtocol(val rawValue: String, val defaultPort: Int) {
    @SerialName("vnc")
    Vnc("vnc", 5900),

    @SerialName("rdp")
    Rdp("rdp", 3389),
    ;

    companion object {
        fun of(rawValue: String): RemoteProtocol? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

@Serializable
enum class SessionQuality(val rawValue: String) {
    @SerialName("adaptive")
    Adaptive("adaptive"),

    @SerialName("full")
    Full("full"),
}

@Serializable
data class StoredEndpoint(
    @Serializable(with = UUIDSerializer::class) val id: UUID = UUID.randomUUID(),
    val host: String,
    val port: Int,
    val quality: NetworkQuality,
    val internetHighDefinition: Boolean,
    val internetBandwidthMbps: Int,
) {
    constructor(endpoint: ComputerEndpoint, primaryID: UUID) : this(
        id = if (endpoint.id == primaryID) UUID.randomUUID() else endpoint.id,
        host = endpoint.address.host,
        port = endpoint.address.port,
        quality = endpoint.networkConfiguration.quality,
        internetHighDefinition = endpoint.networkConfiguration.internetHighDefinition,
        internetBandwidthMbps = endpoint.networkConfiguration.internetBandwidthMbps,
    )

    val endpoint: ComputerEndpoint
        get() = ComputerEndpoint(
            id = id,
            address = ComputerAddress(host, port),
            networkConfiguration = NetworkConfiguration(quality, internetHighDefinition, internetBandwidthMbps),
        )
}

data class ComputerEndpoint(
    val id: UUID = UUID.randomUUID(),
    val address: ComputerAddress,
    val networkConfiguration: NetworkConfiguration,
)

@Entity(tableName = "computers")
data class Computer(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val name: String = "",
    val host: String = "",
    val port: Int = ComputerAddress.DEFAULT_PORT,
    val username: String = "",
    val password: String = "",
    val authenticates: Boolean = false,
    val model: String = "",
    val enclosureColor: String = "",
    val lastConnected: Long? = null,
    val thumbnail: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val usesHighPerformance: Boolean = false,
    val networkQuality: NetworkQuality = NetworkQuality.LocalNetwork,
    val internetHighDefinition: Boolean = false,
    val internetBandwidthMbps: Int = NetworkConfiguration.DEFAULT_BANDWIDTH_MBPS,
    val additionalEndpoints: List<StoredEndpoint> = emptyList(),
    val remoteProtocol: RemoteProtocol = RemoteProtocol.Vnc,
    val trustedFingerprint: String = "",
    val keepsAlive: Boolean = false,
    val disablesUDP: Boolean = false,
    val reversesScrolling: Boolean = false,
    val quality: SessionQuality = SessionQuality.Adaptive,
    val observes: Boolean = false,
    val sharesClipboard: Boolean = true,
    val resolutionScale: Double = VirtualDisplayMode.DEFAULT_RESOLUTION_SCALE,
    val displayID: Long = -1,
    val usesHDR: Boolean = false,
    val receivesAudio: Boolean = true,
    val sendsAudio: Boolean = false,
    val keyboardSources: List<String> = emptyList(),
    val windowWidth: Double = 0.0,
    val windowHeight: Double = 0.0,
    val locksPointer: Boolean = false,
    val usesNativeTouch: Boolean = true,
) {
    constructor(name: String, address: ComputerAddress, remoteProtocol: RemoteProtocol = RemoteProtocol.Vnc, username: String = "") : this(
        name = name,
        host = address.host,
        port = address.port,
        remoteProtocol = remoteProtocol,
        username = username,
    )

    val networkConfiguration: NetworkConfiguration
        get() = NetworkConfiguration(networkQuality, internetHighDefinition, internetBandwidthMbps)

    fun withNetworkConfiguration(configuration: NetworkConfiguration): Computer = copy(
        networkQuality = configuration.quality,
        internetHighDefinition = configuration.internetHighDefinition,
        internetBandwidthMbps = configuration.internetBandwidthMbps,
    )

    val displaySelection: DisplaySelection?
        get() = when {
            displayID == 0L -> DisplaySelection.All
            displayID >= 1 -> DisplaySelection.Display(displayID)
            else -> null
        }

    val address: ComputerAddress get() = ComputerAddress(host, port)

    fun withAddress(address: ComputerAddress): Computer = copy(host = address.host, port = address.port)

    val endpoints: List<ComputerEndpoint>
        get() = listOf(ComputerEndpoint(id = id, address = address, networkConfiguration = networkConfiguration)) +
            additionalEndpoints.map { it.endpoint }

    fun withEndpoints(endpoints: List<ComputerEndpoint>): Computer {
        val first = endpoints.firstOrNull() ?: return this
        return copy(
            host = first.address.host,
            port = first.address.port,
            additionalEndpoints = endpoints.drop(1).map { StoredEndpoint(it, id) },
        ).withNetworkConfiguration(first.networkConfiguration)
    }

    val addressDescription: String get() = address.description(remoteProtocol.defaultPort)

    val displayName: String get() = name.ifEmpty { addressDescription }

    val device: DeviceAppearance get() = DeviceAppearance(model, enclosureColor)

    val memory: SessionMemory
        get() = SessionMemory(
            quality = quality,
            observes = observes,
            sharesClipboard = sharesClipboard,
            resolutionScale = resolutionScale,
            display = displaySelection,
            usesHDR = usesHDR,
            receivesAudio = receivesAudio,
            sendsAudio = sendsAudio,
            windowSize = if (windowWidth > 0 && windowHeight > 0) Size(windowWidth.toFloat(), windowHeight.toFloat()) else null,
            locksPointer = locksPointer,
            usesNativeTouch = usesNativeTouch,
        )

    fun remember(memory: SessionMemory): Computer = copy(
        quality = memory.quality,
        observes = memory.observes,
        sharesClipboard = memory.sharesClipboard,
        resolutionScale = memory.resolutionScale,
        displayID = when (val display = memory.display) {
            DisplaySelection.All -> 0
            is DisplaySelection.Display -> display.id
            null -> -1
        },
        usesHDR = memory.usesHDR,
        receivesAudio = memory.receivesAudio,
        sendsAudio = memory.sendsAudio,
        windowWidth = memory.windowSize?.width?.toDouble() ?: 0.0,
        windowHeight = memory.windowSize?.height?.toDouble() ?: 0.0,
        locksPointer = memory.locksPointer,
        usesNativeTouch = memory.usesNativeTouch,
    )

    fun apply(device: DeviceInfo): Computer = copy(
        model = device.model,
        enclosureColor = device.enclosureColor.ifEmpty { enclosureColor },
    )

    override fun equals(other: Any?): Boolean = other is Computer &&
        copy(thumbnail = null).hashFields() == other.copy(thumbnail = null).hashFields() &&
        (thumbnail contentEquals other.thumbnail)

    override fun hashCode(): Int = hashFields().hashCode()

    private fun hashFields(): List<Any?> = listOf(
        id, name, host, port, username, password, authenticates, model, enclosureColor, lastConnected, createdAt,
        usesHighPerformance, networkQuality, internetHighDefinition, internetBandwidthMbps, additionalEndpoints, remoteProtocol, trustedFingerprint, keepsAlive,
        disablesUDP, reversesScrolling, quality, observes, sharesClipboard, resolutionScale, displayID, usesHDR, receivesAudio, sendsAudio,
        keyboardSources, windowWidth, windowHeight, locksPointer, usesNativeTouch,
    )
}
