package io.nekohasekai.screensharing.session

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.model.NetworkConfiguration
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.model.SessionQuality

sealed interface SessionPhase {
    data object Idle : SessionPhase
    data object Connecting : SessionPhase
    data object Authenticating : SessionPhase
    data object WaitingForPermission : SessionPhase
    data class ChoosingSession(val prompt: SessionChoicePrompt) : SessionPhase
    data object PreparingCursors : SessionPhase
    data object Connected : SessionPhase
    data class Reconnecting(val attempt: Int) : SessionPhase
    data object Disconnected : SessionPhase
    data class Failed(val failure: SessionFailure) : SessionPhase

    val isActive: Boolean get() = this is Connected || this is Reconnecting
}

data class SessionFailure(val kind: Kind, val message: String = "") {
    sealed interface Kind {
        data object AuthenticationFailed : Kind
        data object PermissionDenied : Kind
        data object HostNotFound : Kind
        data object Unreachable : Kind
        data object ConnectionLost : Kind
        data object ClosedByServer : Kind
        data object Unsupported : Kind
        data class CertificateChanged(val fingerprint: String) : Kind
        data object UnverifiedServer : Kind
        data class HighPerformance(val kind: MediaFailure.Kind) : Kind
        data object DrawingFailed : Kind
        data object Ended : Kind
        data object Other : Kind
    }
}

data class SessionChoicePrompt(
    val id: Long,
    val consoleUser: String,
    val allowsShareConsole: Boolean,
    val allowsOwnSession: Boolean,
)

enum class SessionChoice {
    ShareConsole,
    OwnSession,
    Cancel,
}

enum class SessionMode {
    Control,
    Observe,
    ;

    val controls: Boolean get() = this == Control
}

data class RemoteDisplay(
    val id: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val isMain: Boolean,
    val isVirtual: Boolean = false,
)

sealed interface DisplaySelection {
    data class Display(val id: Long) : DisplaySelection
    data object All : DisplaySelection
}

data class MediaFailure(val kind: Kind, val message: String = "") {
    enum class Kind {
        Busy,
        Network,
        Refused,
        Silent,
        Unsupported,
        Undecodable,
        Other,
    }
}

sealed interface SessionEvent {
    data object Bell : SessionEvent
    data class PointerVisible(val visible: Boolean) : SessionEvent
    data class ClipboardUnreadable(val message: String) : SessionEvent
    data class ClipboardNotRestored(val message: String) : SessionEvent
}

typealias ClipboardContent = List<Map<String, ByteArray>>

data class FileTransferError(val kind: Kind, override val message: String = "") : Exception(message) {
    enum class Kind {
        Cancelled,
        NotAllowed,
        NotFound,
        NotWritable,
        DiskFull,
        Permission,
        Local,
        Connection,
        ObserveOnly,
        Unsupported,
        Failed,
    }
}

data class RemoteDragItem(val path: String, val isFolder: Boolean)

@JvmInline
value class PointerButtons(val rawValue: Int) {
    operator fun contains(other: PointerButtons): Boolean = rawValue and other.rawValue == other.rawValue
    operator fun plus(other: PointerButtons): PointerButtons = PointerButtons(rawValue or other.rawValue)
    operator fun minus(other: PointerButtons): PointerButtons = PointerButtons(rawValue and other.rawValue.inv())
    val isEmpty: Boolean get() = rawValue == 0

    companion object {
        val None = PointerButtons(0)
        val Primary = PointerButtons(1 shl 0)
        val Middle = PointerButtons(1 shl 1)
        val Secondary = PointerButtons(1 shl 2)
    }
}

class CursorShape(
    val image: Bitmap,
    val hotspot: Offset,
    val kind: StandardCursor? = null,
    val inversion: Bitmap? = null,
    val scale: Float = 1f,
) {
    fun copy(
        image: Bitmap = this.image,
        hotspot: Offset = this.hotspot,
        kind: StandardCursor? = this.kind,
        inversion: Bitmap? = this.inversion,
        scale: Float = this.scale,
    ) = CursorShape(image, hotspot, kind, inversion, scale)
}

data class DeviceInfo(val model: String, val enclosureColor: String)

sealed interface SessionCredentials {
    data class Account(override val username: String, val password: String) : SessionCredentials
    data class VncPassword(val password: String) : SessionCredentials
    data class AskForPermission(override val username: String) : SessionCredentials
    data object NoAuthentication : SessionCredentials

    val username: String get() = ""

    val account: Account? get() = this as? Account
}

data class SessionConfiguration(
    val address: ComputerAddress,
    val remoteProtocol: RemoteProtocol,
    val platform: RemotePlatform?,
    val trustedFingerprint: String,
    val allowsUnverifiedLogin: Boolean = false,
    val keyboardLayout: Int,
    val clientName: String,
    val credentials: SessionCredentials,
    val quality: SessionQuality,
    val startsInObserveMode: Boolean,
    val sharesClipboard: Boolean,
    val receivesAudio: Boolean,
    val sendsAudio: Boolean,
    val updateRate: Int,
    val awaitsVirtualDisplay: Boolean,
    val networkConfiguration: NetworkConfiguration = NetworkConfiguration(),
    val keepsAlive: Boolean = false,
    val disablesUDP: Boolean = false,
)

data class SessionInfo(
    val videoCodec: String? = null,
    val framebufferWidth: Int = 0,
    val framebufferHeight: Int = 0,
    val framebufferPixelsPerCursorPixel: Double = 1.0,
    val cursorPixelsPerPoint: Double = 1.0,
    val dialect: ServerDialect,
    val platform: RemotePlatform,
    val fingerprint: String = "",
) {
    companion object {
        fun of(remoteProtocol: RemoteProtocol, platform: RemotePlatform?) = SessionInfo(
            dialect = if (remoteProtocol == RemoteProtocol.Rdp) ServerDialect.Rdp else ServerDialect.Apple,
            platform = platform ?: if (remoteProtocol == RemoteProtocol.Rdp) RemotePlatform.Pc else RemotePlatform.Mac,
        )
    }
}

enum class ServerDialect {
    Apple,
    Standard,
    Rdp,
}

enum class RemotePlatform {
    Mac,
    Pc,
}

@JvmInline
value class SessionCapabilities(val rawValue: Int) {
    operator fun contains(other: SessionCapabilities): Boolean = rawValue and other.rawValue == other.rawValue
    operator fun plus(other: SessionCapabilities): SessionCapabilities = SessionCapabilities(rawValue or other.rawValue)
    operator fun minus(other: SessionCapabilities): SessionCapabilities = SessionCapabilities(rawValue and other.rawValue.inv())
    val isEmpty: Boolean get() = rawValue == 0

    companion object {
        val None = SessionCapabilities(0)
        val Control = SessionCapabilities(1 shl 0)
        val HighPerformance = SessionCapabilities(1 shl 2)
        val Clipboard = SessionCapabilities(1 shl 3)
        val DisplaySelection = SessionCapabilities(1 shl 4)
        val VirtualDisplay = SessionCapabilities(1 shl 5)
        val FileTransfer = SessionCapabilities(1 shl 6)
        val KeyboardSource = SessionCapabilities(1 shl 7)
        val Gestures = SessionCapabilities(1 shl 8)
        val AssistCursor = SessionCapabilities(1 shl 9)
        val MissionControl = SessionCapabilities(1 shl 10)
        val AppWindows = SessionCapabilities(1 shl 11)
        val ShowDesktop = SessionCapabilities(1 shl 12)
        val Launchpad = SessionCapabilities(1 shl 13)
        val KeyCodes = SessionCapabilities(1 shl 14)
        val DragToRemote = SessionCapabilities(1 shl 15)
        val DragFromRemote = SessionCapabilities(1 shl 16)
        val Quality = SessionCapabilities(1 shl 17)
        val Observe = SessionCapabilities(1 shl 18)
        val Audio = SessionCapabilities(1 shl 19)
        val ClipboardRequest = SessionCapabilities(1 shl 20)
        val UnicodeText = SessionCapabilities(1 shl 21)
        val Touch = SessionCapabilities(1 shl 22)
    }
}

enum class GesturePhase {
    MayBegin,
    Began,
    Changed,
    Ended,
    Cancelled,
}

enum class TouchPhase {
    Began,
    Moved,
    Ended,
    Cancelled,
}

data class RemoteTouch(val id: Long, val phase: TouchPhase, val location: Offset)

enum class SystemCommand(val capability: SessionCapabilities) {
    MissionControl(SessionCapabilities.MissionControl),
    AppWindows(SessionCapabilities.AppWindows),
    ShowDesktop(SessionCapabilities.ShowDesktop),
    Launchpad(SessionCapabilities.Launchpad),
}

enum class VideoCodec {
    H264,
    Hevc,
}

class VideoFormat(
    val codec: VideoCodec,
    val parameterSets: List<ByteArray>,
    val bands: Int,
    val canvasHeight: Int = 0,
    val isHDR: Boolean = false,
)

class VideoAccessUnit(
    val data: ByteArray,
    val isSync: Boolean,
    val presentationTimeNanos: Long,
    val band: Int,
    val bands: Int,
    val frameID: Long,
    val frameEnd: Boolean,
    val changedBands: Int,
    val discontinuity: Boolean,
    val arrivalNanos: Long,
    val composedNanos: Long?,
)

data class AudioFormat(
    val formatID: Long,
    val sampleRate: Double,
    val channels: Int,
    val framesPerPacket: Int,
    val magicCookie: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is AudioFormat &&
        formatID == other.formatID &&
        sampleRate == other.sampleRate &&
        channels == other.channels &&
        framesPerPacket == other.framesPerPacket &&
        magicCookie.contentEquals(other.magicCookie)

    override fun hashCode(): Int = listOf(formatID.hashCode(), sampleRate.hashCode(), channels, framesPerPacket, magicCookie.contentHashCode()).hashCode()
}

class AudioAccessUnit(val data: ByteArray, val timestamp: Long, val lost: Int)

data class SessionStatistics(
    val roundTripMilliseconds: Double?,
    val receivedBytes: Long,
    val encoding: String?,
    val packets: PacketStatistics?,
    val video: VideoStreamStatistics?,
)

data class PacketStatistics(val received: Long, val expected: Long)

data class VideoStreamStatistics(val losses: Long)
