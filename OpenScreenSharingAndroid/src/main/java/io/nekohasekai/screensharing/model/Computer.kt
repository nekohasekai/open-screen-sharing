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
enum class DeviceType(val rawValue: String) {
    @SerialName("mac")
    Mac("mac"),

    @SerialName("iPhone")
    IPhone("iPhone"),

    @SerialName("iPad")
    IPad("iPad"),
    ;

    val isIOS: Boolean get() = this != Mac

    companion object {
        fun of(model: String): DeviceType? {
            val trimmed = model.trim()
            return when {
                trimmed.startsWith("iPhone") -> IPhone
                trimmed.startsWith("iPad") -> IPad
                trimmed.startsWith("Mac") || trimmed.startsWith("iMac") -> Mac
                else -> null
            }
        }
    }
}

@Serializable
enum class SessionQuality(val rawValue: String) {
    @SerialName("adaptive")
    Adaptive("adaptive"),

    @SerialName("full")
    Full("full"),
}

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
    val internetMaximumFrameRate: MaximumFrameRate = MaximumFrameRate.Sixty,
    val remoteProtocol: RemoteProtocol = RemoteProtocol.Vnc,
    val trustedFingerprint: String = "",
    val keepsAlive: Boolean = false,
    val disablesUDP: Boolean = false,
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

    val deviceType: DeviceType get() = DeviceType.of(model) ?: DeviceType.Mac

    val networkConfiguration: NetworkConfiguration get() = NetworkConfiguration(networkQuality, internetMaximumFrameRate)

    fun withNetworkConfiguration(configuration: NetworkConfiguration): Computer = copy(
        networkQuality = configuration.quality,
        internetMaximumFrameRate = configuration.internetMaximumFrameRate,
    )

    val displaySelection: DisplaySelection?
        get() = when {
            displayID == 0L -> DisplaySelection.All
            displayID >= 1 -> DisplaySelection.Display(displayID)
            else -> null
        }

    val address: ComputerAddress get() = ComputerAddress(host, port)

    fun withAddress(address: ComputerAddress): Computer = copy(host = address.host, port = address.port)

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
        usesHighPerformance, networkQuality, internetMaximumFrameRate, remoteProtocol, trustedFingerprint, keepsAlive,
        disablesUDP, quality, observes, sharesClipboard, resolutionScale, displayID, usesHDR, receivesAudio, sendsAudio,
        keyboardSources, windowWidth, windowHeight, locksPointer, usesNativeTouch,
    )
}
