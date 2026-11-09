package io.nekohasekai.screensharing.session

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.nekohasekai.libsharing.AudioPacket
import io.nekohasekai.libsharing.CursorImage
import io.nekohasekai.libsharing.DisplayList
import io.nekohasekai.libsharing.FileTransfer
import io.nekohasekai.libsharing.FramebufferUpdate
import io.nekohasekai.libsharing.HighPerformanceOptions
import io.nekohasekai.libsharing.Libsharing
import io.nekohasekai.libsharing.Prompt
import io.nekohasekai.libsharing.RemoteDrag
import io.nekohasekai.libsharing.ServerInfo
import io.nekohasekai.libsharing.Session
import io.nekohasekai.libsharing.SessionHandler
import io.nekohasekai.libsharing.TouchFrame
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.MessageError
import io.nekohasekai.screensharing.model.AppDirectory
import io.nekohasekai.screensharing.model.ChromaSampling
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.model.SessionQuality
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max
import io.nekohasekai.libsharing.AudioFormat as LibsharingAudioFormat
import io.nekohasekai.libsharing.Clipboard as LibsharingClipboard
import io.nekohasekai.libsharing.VideoAccessUnit as LibsharingVideoAccessUnit
import io.nekohasekai.libsharing.VideoFormat as LibsharingVideoFormat

class LibsharingRemoteSession(private val configuration: SessionConfiguration, delegate: SessionEventSink) {
    private val lock = Any()
    private val session: Session?
    private val handler: LibsharingHandler
    private val transfers = HashMap<Long, FileTransfer>()
    private var receivesAudio: Boolean
    private var highPerformanceRunning = false
    private var restartingHighPerformance = false
    private var supportsHighPerformance = false
    private val delegate = WeakReference(delegate)

    init {
        LibsharingLog.installed
        receivesAudio = configuration.receivesAudio
        val options = Libsharing.newSessionOptions()
        options.protocol = if (configuration.remoteProtocol == RemoteProtocol.Rdp) Libsharing.ProtocolRDP else Libsharing.ProtocolRFB
        options.platform = when (configuration.platform) {
            RemotePlatform.Mac, RemotePlatform.IOS -> Libsharing.PlatformMac
            RemotePlatform.Pc -> Libsharing.PlatformPC
            null -> Libsharing.PlatformUnknown
        }
        options.trustedFingerprint = configuration.trustedFingerprint
        options.allowUnverifiedLogin = configuration.allowsUnverifiedLogin
        options.keyboardLayout = configuration.keyboardLayout
        options.audio = configuration.receivesAudio
        options.microphone = configuration.sendsAudio
        options.host = configuration.address.host
        options.port = configuration.address.port
        configuration.credentials.applyTo(options, observes = configuration.startsInObserveMode)
        options.quality = quality(configuration.quality)
        options.mode = if (configuration.startsInObserveMode) Libsharing.ModeObserve else Libsharing.ModeControl
        options.sharedClipboard = configuration.sharesClipboard
        options.clientName = configuration.clientName
        options.decoderMaxPixelRate = DecoderCapabilities.current.maximumPixelRate
        options.maximumFrameRate = configuration.networkConfiguration.maximumFrameRate.rawValue
        options.updateRate = configuration.updateRate
        options.awaitVirtualDisplay = configuration.awaitsVirtualDisplay
        options.keepAlive = configuration.keepsAlive
        options.disableUDP = configuration.disablesUDP
        options.cacheDirectory = AppDirectory.caches.path

        val handler = LibsharingHandler(delegate, configuration.remoteProtocol, configuration.platform)
        handler.forwardsClipboard = configuration.sharesClipboard
        session = try {
            Libsharing.newSession(options, handler)
        } catch (error: Exception) {
            delegate.sessionPhaseChanged(SessionPhase.Failed(SessionFailure(SessionFailure.Kind.Other, error.message ?: "libsharing returned no session")))
            null
        }
        this.handler = handler
        handler.session = WeakReference(session)
        val owner = WeakReference(this)
        handler.connected = { supportsHighPerformance -> owner.get()?.connected(supportsHighPerformance) }
        handler.transferFinished = { id -> owner.get()?.transferFinished(id) }
        handler.mediaStopped = {
            owner.get()?.let { self ->
                synchronized(self.lock) {
                    if (self.restartingHighPerformance) {
                        self.restartingHighPerformance = false
                    } else {
                        self.highPerformanceRunning = false
                    }
                }
            }
        }
    }

    fun connect() {
        try {
            session?.connect()
        } catch (error: Exception) {
            delegate.get()?.sessionPhaseChanged(SessionPhase.Failed(SessionFailure(SessionFailure.Kind.Unreachable, error.message.orEmpty())))
        }
    }

    fun stop() {
        session?.disconnect()
    }

    fun abort() {
        session?.abort()
    }

    fun answerSessionChoice(prompt: SessionChoicePrompt, choice: SessionChoice) {
        val answer = when (choice) {
            SessionChoice.ShareConsole -> Libsharing.SessionChoiceShareConsole
            SessionChoice.OwnSession -> Libsharing.SessionChoiceOwnSession
            SessionChoice.Cancel -> Libsharing.SessionChoiceCancel
        }
        runCatching { session?.answerPrompt(prompt.id, answer) }
    }

    fun sendPointer(x: Int, y: Int, buttons: PointerButtons, clickCount: Int) {
        var mask = 0
        if (PointerButtons.Primary in buttons) mask = mask or Libsharing.ButtonLeft
        if (PointerButtons.Middle in buttons) mask = mask or Libsharing.ButtonMiddle
        if (PointerButtons.Secondary in buttons) mask = mask or Libsharing.ButtonRight
        runCatching { session?.sendPointer(x, y, mask, clickCount) }
    }

    fun sendScroll(x: Int, y: Int, deltaX: Double, deltaY: Double) {
        val pointsPerPixel = handler.pointsPerPixel
        runCatching { session?.sendScroll(x, y, deltaX * pointsPerPixel, deltaY * pointsPerPixel) }
    }

    fun sendTouches(touches: List<RemoteTouch>) {
        val frame = TouchFrame()
        for (touch in touches) {
            val phase = when (touch.phase) {
                TouchPhase.Began -> Libsharing.TouchPhaseBegan
                TouchPhase.Moved -> Libsharing.TouchPhaseMoved
                TouchPhase.Ended -> Libsharing.TouchPhaseEnded
                TouchPhase.Cancelled -> Libsharing.TouchPhaseCancelled
            }
            frame.add(touch.id, phase, floor(touch.location.x).toInt(), floor(touch.location.y).toInt())
        }
        runCatching { session?.sendTouches(frame) }
    }

    fun sendKey(key: MacKey, keyboardType: MacKeyboardType, down: Boolean) {
        val keyCode = key.keyCode
        if (keyCode == null) {
            runCatching { session?.sendKey(key.keysym, down) }
            return
        }
        val type = when (keyboardType) {
            MacKeyboardType.Ansi -> Libsharing.KeyboardANSI
            MacKeyboardType.Iso -> Libsharing.KeyboardISO
            MacKeyboardType.Jis -> Libsharing.KeyboardJIS
        }
        runCatching { session?.sendKeyCode(keyCode, type, key.keysym, down) }
    }

    fun sendText(text: String) {
        session?.sendText(text)
    }

    fun pasteText(text: String) {
        session?.pasteText(text)
    }

    fun selectKeyboardSource(id: String) {
        runCatching { session?.selectKeyboardSource(id) }
    }

    fun sendMagnify(x: Int, y: Int, magnification: Double, phase: GesturePhase) {
        runCatching { session?.sendMagnify(x, y, magnification, phaseValue(phase)) }
    }

    fun sendRotate(x: Int, y: Int, degrees: Double, phase: GesturePhase) {
        runCatching { session?.sendRotate(x, y, degrees, phaseValue(phase)) }
    }

    fun sendScrollPhase(x: Int, y: Int, deltaX: Double, deltaY: Double, phase: GesturePhase) {
        val pointsPerPixel = handler.pointsPerPixel
        runCatching { session?.sendScrollPhase(x, y, deltaX * pointsPerPixel, deltaY * pointsPerPixel, phaseValue(phase)) }
    }

    fun startScrollMomentum(x: Int, y: Int, velocityX: Double, velocityY: Double) {
        val pointsPerPixel = handler.pointsPerPixel
        runCatching { session?.startScrollMomentum(x, y, velocityX * pointsPerPixel, velocityY * pointsPerPixel) }
    }

    fun stopScrollMomentum() {
        session?.stopScrollMomentum()
    }

    fun sendSystemCommand(command: SystemCommand) {
        val value = when (command) {
            SystemCommand.MissionControl -> Libsharing.SystemCommandMissionControl
            SystemCommand.AppWindows -> Libsharing.SystemCommandAppWindows
            SystemCommand.ShowDesktop -> Libsharing.SystemCommandShowDesktop
            SystemCommand.Launchpad -> Libsharing.SystemCommandLaunchpad
        }
        runCatching { session?.sendSystemCommand(value) }
    }

    fun setAssistCursor(x: Int, y: Int, pressed: Boolean) {
        runCatching { session?.setAssistCursor(x, y, pressed) }
    }

    fun hideAssistCursor() {
        runCatching { session?.hideAssistCursor() }
    }

    fun setMode(mode: SessionMode) {
        val value = when (mode) {
            SessionMode.Control -> Libsharing.ModeControl
            SessionMode.Observe -> Libsharing.ModeObserve
        }
        runCatching { session?.setMode(value) }
    }

    fun selectDisplay(selection: DisplaySelection) {
        val id = when (selection) {
            DisplaySelection.All -> Libsharing.AllDisplays
            is DisplaySelection.Display -> selection.id
        }
        runCatching { session?.selectDisplay(id) }
    }

    fun setQuality(quality: SessionQuality) {
        runCatching { session?.setQuality(quality(quality)) }
    }

    fun startHighPerformance() {
        val supported = synchronized(lock) { supportsHighPerformance }
        if (supported) {
            startHighPerformanceWithConnection()
        } else {
            handler.reportMediaFailure(MediaFailure(MediaFailure.Kind.Unsupported))
        }
    }

    private fun connected(supportsHighPerformance: Boolean) {
        synchronized(lock) {
            this.supportsHighPerformance = supportsHighPerformance
            if (!supportsHighPerformance) {
                highPerformanceRunning = false
            }
        }
    }

    fun startHighPerformanceWithConnection() {
        val session = session ?: return
        synchronized(lock) {
            if (highPerformanceRunning) return
            highPerformanceRunning = true
        }
        val options = try {
            highPerformanceOptions()
        } catch (error: Exception) {
            synchronized(lock) { highPerformanceRunning = false }
            handler.reportMediaFailure(MediaFailure(MediaFailure.Kind.Undecodable, error.message.orEmpty()))
            return
        }
        try {
            session.startHighPerformance(options)
        } catch (error: Exception) {
            synchronized(lock) { highPerformanceRunning = false }
            handler.reportMediaFailure(MediaFailure(MediaFailure.Kind.Other, error.message.orEmpty()))
        }
    }

    private fun highPerformanceOptions(): HighPerformanceOptions {
        val options = Libsharing.newHighPerformanceOptions()
        val capabilities = DecoderCapabilities.current
        if (!capabilities.supportsHEVC && !capabilities.supportsH264) throw MessageError("this device has no hardware decoder for HEVC or H.264")
        options.supportedCodecs = (if (capabilities.supportsHEVC) Libsharing.DecoderCodecHEVC else 0) or
            (if (capabilities.supportsH264) Libsharing.DecoderCodecH264 else 0)
        val network = configuration.networkConfiguration
        options.pixelFormats = network.chroma.pixelFormats(capabilities.pixelFormats)
        if (network.chroma == ChromaSampling.Yuv444 && capabilities.supportsFullChroma) {
            options.supportedCodecs = Libsharing.DecoderCodecHEVC
        }
        options.audio = synchronized(lock) { receivesAudio }
        options.videoMode = if (network.usesCoreDevice) Libsharing.VideoModeCoreDevice else Libsharing.VideoModeRemoteDesktop
        options.bands = network.bands
        options.immediatePlayout = network.schedulesPresentation
        return options
    }

    fun uploadFile(localFile: File, remoteFolder: String): Long {
        val session = session ?: throw MessageError("not connected")
        val transfer = session.uploadFile(localFile.path, remoteFolder)
        val id = transfer.transferID()
        synchronized(lock) { transfers[id] = transfer }
        return id
    }

    fun downloadFile(remotePath: String, localFolder: File): Long {
        val session = session ?: throw MessageError("not connected")
        val transfer = session.downloadFile(remotePath, localFolder.path)
        val id = transfer.transferID()
        synchronized(lock) { transfers[id] = transfer }
        return id
    }

    fun downloadFiles(remotePaths: List<String>, localFolder: File) {
        val session = session ?: throw MessageError("not connected")
        session.downloadFiles(remotePaths.joinToString("\n"), localFolder.path)
    }

    fun setCursorAssets(assets: MacCursorAssets?) {
        handler.setCursorAssets(assets)
    }

    fun startDrop(names: List<String>): Long {
        val session = session ?: throw MessageError("not connected")
        return session.startDrop(names.joinToString("\n"))
    }

    fun cancelDrag(id: Long) {
        runCatching { session?.cancelDrag(id) }
    }

    fun cancelRemoteDrag() {
        val session = session ?: throw MessageError("not connected")
        session.cancelRemoteDrag()
    }

    fun cancelTransfer(id: Long) {
        val transfer = synchronized(lock) { transfers[id] }
        runCatching { transfer?.cancel() }
    }

    private fun transferFinished(id: Long) {
        synchronized(lock) { transfers.remove(id) }
    }

    fun setReceivesAudio(receivesAudio: Boolean) {
        val changed: Boolean
        val running: Boolean
        synchronized(lock) {
            changed = receivesAudio != this.receivesAudio
            this.receivesAudio = receivesAudio
            running = highPerformanceRunning
        }
        val session = session
        if (!changed || !running || session == null) return
        val options = try {
            highPerformanceOptions()
        } catch (_: Exception) {
            return
        }
        synchronized(lock) { restartingHighPerformance = true }
        try {
            session.restartHighPerformance(options)
        } catch (_: Exception) {
            synchronized(lock) { restartingHighPerformance = false }
        }
    }

    fun sendMicrophoneAudio(data: ByteArray) {
        runCatching { session?.sendMicrophoneAudio(data) }
    }

    fun requestRefresh() {
        runCatching { session?.requestRefresh() }
    }

    fun requestKeyframe() {
        runCatching { session?.requestKeyframe() }
    }

    fun statistics(): SessionStatistics? {
        val statistics = session?.statistics() ?: return null
        return SessionStatistics(
            roundTripMilliseconds = statistics.roundTripMilliseconds.takeIf { statistics.roundTripKnown },
            receivedBytes = statistics.receivedBytes,
            encoding = statistics.encoding?.takeIf { it.isNotEmpty() },
            packets = if (statistics.getUDP()) PacketStatistics(statistics.packets, statistics.packetsExpected) else null,
            video = if (statistics.highPerformance) VideoStreamStatistics(statistics.videoLosses) else null,
        )
    }

    val displayWidthMultiple: Int
        get() = session?.displayWidthMultiple() ?: 1

    fun setVirtualDisplay(mode: VirtualDisplayMode, largestSide: Int) {
        runCatching { session?.setVirtualDisplay(mode.width, mode.height, mode.density.toDouble(), largestSide, largestSide) }
    }

    fun setVisible(visible: Boolean) {
        runCatching { session?.setVisible(visible) }
    }

    fun setKeepsAwake(enabled: Boolean) {
        runCatching { session?.setKeepsAwake(enabled) }
    }

    fun setHDR(enabled: Boolean) {
        runCatching { session?.setHDR(enabled) }
    }

    fun setUpdateRate(rate: Int) {
        runCatching { session?.setUpdateRate(rate) }
    }

    fun setClipboardSharing(enabled: Boolean) {
        handler.forwardsClipboard = enabled
        runCatching { session?.setSharedClipboard(enabled) }
    }

    fun sendClipboard(content: ClipboardContent, pasting: Boolean) {
        val session = session ?: throw MessageError("not connected")
        val clipboard = Libsharing.newClipboard()
        for (item in content) {
            val clipboardItem = Libsharing.newClipboardItem()
            for ((type, data) in item) {
                clipboardItem.addFlavor(type, data)
            }
            clipboard.addItem(clipboardItem)
        }
        if (pasting) {
            session.pasteClipboard(clipboard)
        } else {
            session.setClipboard(clipboard)
        }
    }

    fun requestClipboard() {
        val session = session ?: throw MessageError("not connected")
        handler.forceNextClipboard = true
        try {
            session.requestClipboard()
        } catch (error: Exception) {
            handler.forceNextClipboard = false
            throw error
        }
    }

    private companion object {
        fun phaseValue(phase: GesturePhase): Int = when (phase) {
            GesturePhase.MayBegin -> Libsharing.GesturePhaseMayBegin
            GesturePhase.Began -> Libsharing.GesturePhaseBegan
            GesturePhase.Changed -> Libsharing.GesturePhaseChanged
            GesturePhase.Ended -> Libsharing.GesturePhaseEnded
            GesturePhase.Cancelled -> Libsharing.GesturePhaseCancelled
        }

        fun quality(quality: SessionQuality): Int = when (quality) {
            SessionQuality.Adaptive -> Libsharing.QualityAdaptive
            SessionQuality.Full -> Libsharing.QualityFull
        }
    }
}

private class LibsharingHandler(
    delegate: SessionEventSink,
    remoteProtocol: RemoteProtocol,
    platform: RemotePlatform?,
) : SessionHandler {
    private val lock = Any()
    private val delegate = WeakReference(delegate)

    @Volatile
    var session: WeakReference<Session?> = WeakReference(null)
    private var framebuffer: Framebuffer? = null
    private var framebufferFailure: String? = null
    private var info = SessionInfo.of(remoteProtocol, platform)
    private var capabilities = SessionCapabilities.None
    private var clipboardForwarded = true
    private var clipboardForced = false
    private var screenPointsPerPixel = 0.5

    @Volatile
    var connected: ((Boolean) -> Unit)? = null

    @Volatile
    var mediaStopped: (() -> Unit)? = null

    @Volatile
    var transferFinished: ((Long) -> Unit)? = null
    private val cursorLock = Any()
    private var cursorAssets: MacCursorAssets? = null
    private var lastCursor: CursorImage? = null

    private val sink: SessionEventSink? get() = delegate.get()

    var forwardsClipboard: Boolean
        get() = synchronized(lock) { clipboardForwarded }
        set(value) = synchronized(lock) { clipboardForwarded = value }

    var forceNextClipboard: Boolean
        get() = synchronized(lock) { clipboardForced }
        set(value) = synchronized(lock) { clipboardForced = value }

    val pointsPerPixel: Double
        get() = synchronized(lock) { screenPointsPerPixel }

    private fun updateInfo(body: (SessionInfo) -> SessionInfo) {
        synchronized(lock) {
            info = body(info)
            sink?.sessionInfoChanged(info)
        }
    }

    override fun onStateChanged(state: Int, errorCode: Int, message: String?) {
        val delegate = sink ?: return
        when (state) {
            Libsharing.StateConnecting -> delegate.sessionPhaseChanged(SessionPhase.Connecting)
            Libsharing.StateAuthenticating -> delegate.sessionPhaseChanged(SessionPhase.Authenticating)
            Libsharing.StateWaitingForApproval -> delegate.sessionPhaseChanged(SessionPhase.WaitingForPermission)
            Libsharing.StateChoosingSession -> Unit
            Libsharing.StateAwaitingDisplay -> delegate.sessionAwaitsDisplay()
            Libsharing.StateConnected -> {
                connected?.invoke(synchronized(lock) { SessionCapabilities.HighPerformance in capabilities })
                delegate.sessionPhaseChanged(SessionPhase.Connected)
            }
            Libsharing.StateDisconnected -> {
                val text = synchronized(lock) { framebufferFailure } ?: message.orEmpty()
                val kind = when (errorCode) {
                    Libsharing.ErrorNone, Libsharing.ErrorCancelled -> null
                    Libsharing.ErrorAuthentication -> SessionFailure.Kind.AuthenticationFailed
                    Libsharing.ErrorPermissionDenied -> SessionFailure.Kind.PermissionDenied
                    Libsharing.ErrorHostNotFound -> SessionFailure.Kind.HostNotFound
                    Libsharing.ErrorNetwork -> {
                        val started = synchronized(lock) { info.framebufferWidth > 0 }
                        if (started) SessionFailure.Kind.ConnectionLost else SessionFailure.Kind.Unreachable
                    }
                    Libsharing.ErrorSessionEnded -> SessionFailure.Kind.ClosedByServer
                    Libsharing.ErrorUnsupportedServer -> SessionFailure.Kind.Unsupported
                    Libsharing.ErrorCertificateChanged -> SessionFailure.Kind.CertificateChanged(synchronized(lock) { info.fingerprint })
                    Libsharing.ErrorUnverifiedServer -> SessionFailure.Kind.UnverifiedServer
                    else -> {
                        val started = synchronized(lock) { info.framebufferWidth > 0 }
                        if (started) SessionFailure.Kind.Ended else SessionFailure.Kind.Other
                    }
                }
                delegate.sessionPhaseChanged(if (kind == null) SessionPhase.Disconnected else SessionPhase.Failed(SessionFailure(kind, text)))
            }
        }
    }

    override fun onServerInfo(info: ServerInfo?) {
        val serverInfo = info ?: return
        var capabilities = SessionCapabilities.None
        if (!serverInfo.observeOnly) capabilities += SessionCapabilities.Control
        if (serverInfo.supportsDisplaySelection) capabilities += SessionCapabilities.DisplaySelection
        if (serverInfo.supportsClipboard) capabilities += SessionCapabilities.Clipboard
        if (serverInfo.supportsVirtualDisplay) capabilities += SessionCapabilities.VirtualDisplay
        if (serverInfo.supportsKeyboardSource) capabilities += SessionCapabilities.KeyboardSource
        if (serverInfo.supportsKeyCodes) capabilities += SessionCapabilities.KeyCodes
        if (serverInfo.supportsHighPerformance) capabilities += SessionCapabilities.HighPerformance
        if (serverInfo.supportsFileTransfer) capabilities += SessionCapabilities.FileTransfer
        if (serverInfo.supportsDragToRemote) capabilities += SessionCapabilities.DragToRemote
        if (serverInfo.supportsDragFromRemote) capabilities += SessionCapabilities.DragFromRemote
        if (serverInfo.supportsGestures) capabilities += SessionCapabilities.Gestures
        if (serverInfo.supportsAssistCursor) capabilities += SessionCapabilities.AssistCursor
        if (serverInfo.supportsMissionControl) capabilities += SessionCapabilities.MissionControl
        if (serverInfo.supportsAppWindows) capabilities += SessionCapabilities.AppWindows
        if (serverInfo.supportsShowDesktop) capabilities += SessionCapabilities.ShowDesktop
        if (serverInfo.supportsLaunchpad) capabilities += SessionCapabilities.Launchpad
        if (serverInfo.supportsQuality) capabilities += SessionCapabilities.Quality
        if (serverInfo.supportsObserve) capabilities += SessionCapabilities.Observe
        if (serverInfo.supportsAudio) capabilities += SessionCapabilities.Audio
        if (serverInfo.supportsClipboardRequest) capabilities += SessionCapabilities.ClipboardRequest
        if (serverInfo.supportsUnicodeText) capabilities += SessionCapabilities.UnicodeText
        if (serverInfo.supportsTouch) capabilities += SessionCapabilities.Touch
        synchronized(lock) { this.capabilities = capabilities }
        sink?.sessionCapabilitiesChanged(capabilities)
        updateInfo { info ->
            info.copy(
                dialect = when (serverInfo.dialect) {
                    Libsharing.DialectApple -> ServerDialect.Apple
                    Libsharing.DialectRDP -> ServerDialect.Rdp
                    else -> ServerDialect.Standard
                },
                platform = if (serverInfo.platform == Libsharing.PlatformMac) RemotePlatform.Mac else RemotePlatform.Pc,
                cursorPixelsPerPoint = max(serverInfo.cursorPixelsPerPoint, 1.0),
                framebufferWidth = serverInfo.width,
                framebufferHeight = serverInfo.height,
            )
        }
        if (serverInfo.observeOnly) {
            sink?.sessionModeChanged(SessionMode.Observe)
        }
    }

    override fun onServerCertificate(fingerprint: String?) {
        updateInfo { it.copy(fingerprint = fingerprint.orEmpty()) }
    }

    override fun onFramebufferResize(width: Int, height: Int) {
        val session = session.get() ?: return
        val delegate = sink ?: return
        var created: Framebuffer? = null
        val thrown = try {
            created = delegate.sessionNeedsFramebuffer(width, height) ?: return
            session.attachFramebuffer(created.baseAddress, width, height, created.bytesPerRow)
            null
        } catch (error: Exception) {
            error
        } catch (error: OutOfMemoryError) {
            error
        }
        val failure = thrown?.let { "attach a ${width}x$height framebuffer: ${it.message.orEmpty()}" }
        if (failure != null) Log.e(TAG, failure)
        val released = synchronized(lock) {
            framebufferFailure = failure
            if (failure == null) framebuffer.also { framebuffer = created } else created
        }
        released?.release()
        updateInfo { it.copy(framebufferWidth = width, framebufferHeight = height) }
    }

    override fun onFramebufferUpdate(update: FramebufferUpdate?) {
        update ?: return
        sink?.sessionFramebufferUpdated(
            Rect(update.x.toFloat(), update.y.toFloat(), (update.x + update.width).toFloat(), (update.y + update.height).toFloat()),
            System.nanoTime() - max(update.holdNanoseconds, 0L),
        )
    }

    override fun onDisplays(displays: DisplayList?) {
        displays ?: return
        val count = displays.len()
        val selected = (0 until count).asSequence().mapNotNull { displays.get(it) }.firstOrNull { it.displayID == displays.current }
        val pointsWide = selected?.logicalWidth ?: displays.desktopWidth
        if (displays.framebufferWidth > 0 && pointsWide > 0) {
            val ratio = pointsWide.toDouble() / displays.framebufferWidth.toDouble()
            synchronized(lock) { screenPointsPerPixel = ratio }
            updateInfo { it.copy(framebufferPixelsPerCursorPixel = 1 / ratio) }
        }
        val context = App.instance
        val result = ArrayList<RemoteDisplay>()
        var physicalIndex = 0
        for (index in 0 until count) {
            val display = displays.get(index) ?: continue
            val name = when {
                display.isVirtual -> context.getString(R.string.bridge_virtual_display)
                display.isMain -> context.getString(R.string.bridge_main_display)
                else -> {
                    physicalIndex += 1
                    context.getString(R.string.bridge_display_number, physicalIndex + 1)
                }
            }
            result.add(
                RemoteDisplay(
                    id = display.displayID,
                    name = name,
                    width = display.logicalWidth,
                    height = display.logicalHeight,
                    isMain = display.isMain,
                    isVirtual = display.isVirtual,
                ),
            )
        }
        result.sortByDescending { it.isMain }
        val selection = if (displays.current == Libsharing.AllDisplays) DisplaySelection.All else DisplaySelection.Display(displays.current)
        sink?.sessionDisplaysChanged(result, selection)
    }

    fun setCursorAssets(assets: MacCursorAssets?) {
        synchronized(cursorLock) {
            cursorAssets = assets
            lastCursor?.let { onCursorImage(it) }
        }
    }

    override fun onCursorImage(cursor: CursorImage?) {
        synchronized(cursorLock) {
            lastCursor = cursor
            val pixels = cursor?.pixels
            if (cursor == null || cursor.width <= 0 || cursor.height <= 0 || pixels == null || pixels.size < cursor.width * cursor.height * 4) {
                sink?.sessionCursorChanged(null)
                return
            }
            val width = cursor.width
            val height = cursor.height
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val rgba = ByteArray(width * height * 4)
            for (offset in rgba.indices step 4) {
                rgba[offset] = pixels[offset + 2]
                rgba[offset + 1] = pixels[offset + 1]
                rgba[offset + 2] = pixels[offset]
                rgba[offset + 3] = pixels[offset + 3]
            }
            image.copyPixelsFromBuffer(rowAligned(rgba, width * 4, height, image.rowBytes))
            val fingerprint = StandardCursor.fingerprint(width, height, cursor.hotspotX, cursor.hotspotY, pixels)
            var inversion: Bitmap? = null
            val mask = cursor.invert
            if (mask != null && mask.size == width * height) {
                inversion = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also {
                    it.copyPixelsFromBuffer(rowAligned(mask, width, height, it.rowBytes))
                }
            }
            var shape = CursorShape(
                image = image,
                hotspot = Offset(cursor.hotspotX.toFloat(), cursor.hotspotY.toFloat()),
                kind = fingerprint?.let { StandardCursor.of(it) },
                inversion = inversion,
            )
            if (fingerprint != null) {
                cursorAssets?.image(fingerprint)?.let { sharp ->
                    shape = shape.copy(image = sharp, scale = MacCursorAssets.SCALE)
                }
            }
            sink?.sessionCursorChanged(shape)
        }
    }

    override fun onCursorPosition(x: Int, y: Int) {
        sink?.sessionCursorMoved(x, y)
    }

    override fun onClipboard(clipboard: LibsharingClipboard?) {
        clipboard ?: return
        val forwarded = synchronized(lock) {
            val forwarded = clipboardForwarded || clipboardForced
            clipboardForced = false
            forwarded
        }
        if (!forwarded) return
        val content = ArrayList<Map<String, ByteArray>>()
        for (index in 0 until clipboard.len()) {
            val item = clipboard.get(index) ?: continue
            val flavors = LinkedHashMap<String, ByteArray>()
            for (flavorIndex in 0 until item.len()) {
                val flavor = item.get(flavorIndex) ?: continue
                val data = flavor.data
                if (flavor.promise || data == null) continue
                flavors[flavor.type] = data
            }
            if (flavors.isNotEmpty()) {
                content.add(flavors)
            }
        }
        sink?.sessionClipboardReceived(content)
    }

    override fun onKeyboardSource(sourceID: String?, secureInput: Boolean) {
        sink?.sessionKeyboardSourceChanged(sourceID, secureInput)
    }

    override fun onPrompt(prompt: Prompt?) {
        prompt ?: return
        sink?.sessionPhaseChanged(
            SessionPhase.ChoosingSession(
                SessionChoicePrompt(
                    id = prompt.promptID,
                    consoleUser = prompt.consoleUser.orEmpty(),
                    allowsShareConsole = prompt.allowsShareConsole,
                    allowsOwnSession = prompt.allowsOwnSession,
                ),
            ),
        )
    }

    override fun onStatus(status: Int) {
        when (status) {
            Libsharing.StatusControlAllowed -> {
                val capabilities = synchronized(lock) {
                    capabilities += SessionCapabilities.Control
                    capabilities
                }
                sink?.sessionCapabilitiesChanged(capabilities)
            }
            Libsharing.StatusObserveOnly -> {
                val capabilities = synchronized(lock) {
                    capabilities -= SessionCapabilities.Control
                    capabilities
                }
                sink?.sessionCapabilitiesChanged(capabilities)
                sink?.sessionModeChanged(SessionMode.Observe)
            }
            Libsharing.StatusPointerHidden -> sink?.sessionEvent(SessionEvent.PointerVisible(false))
            Libsharing.StatusPointerShown -> sink?.sessionEvent(SessionEvent.PointerVisible(true))
        }
    }

    override fun onBell() {
        sink?.sessionEvent(SessionEvent.Bell)
    }

    override fun onClipboardProblem(kind: Int, message: String?) {
        if (kind == Libsharing.ClipboardProblemNotRestored) {
            sink?.sessionEvent(SessionEvent.ClipboardNotRestored(message.orEmpty()))
        } else {
            sink?.sessionEvent(SessionEvent.ClipboardUnreadable(message.orEmpty()))
        }
    }

    override fun onVideoFormat(format: LibsharingVideoFormat?) {
        format ?: return
        val parameterSets = ArrayList<ByteArray>()
        for (index in 0 until format.len()) {
            format.get(index)?.let { parameterSets.add(it) }
        }
        val hdr = format.getHDR()
        sink?.sessionVideoFormatChanged(
            VideoFormat(
                codec = if (format.codec == Libsharing.CodecH264) VideoCodec.H264 else VideoCodec.Hevc,
                parameterSets = parameterSets,
                bands = max(1, format.bands),
                canvasHeight = format.height,
                isHDR = hdr,
            ),
        )
        val codec = if (format.codec == Libsharing.CodecH264) "H.264" else "HEVC"
        val chroma = if (format.chromaFormat == 3) "4:4:4" else "4:2:0"
        updateInfo { it.copy(videoCodec = if (hdr) "$codec $chroma HDR" else "$codec $chroma") }
    }

    override fun onVideoAccessUnit(unit: LibsharingVideoAccessUnit?) {
        unit ?: return
        val data = unit.data ?: return
        val now = System.nanoTime()
        val clockRate = max(1, unit.clockRate).toLong()
        val timestamp = unit.timestamp
        sink?.sessionReceivedVideo(
            VideoAccessUnit(
                data = data,
                isSync = unit.sync,
                presentationTimeNanos = timestamp / clockRate * 1_000_000_000L + timestamp % clockRate * 1_000_000_000L / clockRate,
                band = unit.band,
                bands = unit.bands,
                frameID = unit.frameID,
                frameEnd = unit.frameEnd,
                changedBands = unit.changedBands,
                discontinuity = unit.discontinuity,
                turns = PictureTurns(unit.turns),
                arrivalNanos = now - max(unit.holdNanoseconds, 0L),
                composedNanos = if (unit.composed) now - unit.composeNanoseconds else null,
            ),
        )
    }

    override fun onAudioFormat(format: LibsharingAudioFormat?) {
        format ?: return
        sink?.sessionAudioFormatChanged(audioFormat(format))
    }

    override fun onAudioPacket(packet: AudioPacket?) {
        packet ?: return
        val data = packet.data ?: return
        sink?.sessionReceivedAudio(AudioAccessUnit(data, packet.timestamp, packet.lost))
    }

    override fun onMicrophone(format: LibsharingAudioFormat?) {
        sink?.sessionMicrophoneChanged(format?.let(::audioFormat))
    }

    override fun onMediaStopped(errorCode: Int, message: String?) {
        mediaStopped?.invoke()
        updateInfo { it.copy(videoCodec = null) }
        val kind = when (errorCode) {
            Libsharing.MediaErrorNone -> null
            Libsharing.MediaErrorBusy -> MediaFailure.Kind.Busy
            Libsharing.MediaErrorNetwork -> MediaFailure.Kind.Network
            Libsharing.MediaErrorRefused -> MediaFailure.Kind.Refused
            Libsharing.MediaErrorSilent -> MediaFailure.Kind.Silent
            else -> MediaFailure.Kind.Other
        }
        sink?.sessionVideoStopped(kind?.let { MediaFailure(it, message.orEmpty()) })
    }

    override fun onDeviceInfo(model: String?, deviceColor: String?, enclosureColor: String?) {
        fun known(value: String?): String = if (value == null || value.lowercase() == "unknown") "" else value
        val knownModel = known(model)
        if (knownModel.isEmpty()) return
        val color = known(enclosureColor).ifEmpty { known(deviceColor) }
        sink?.sessionDeviceInfo(DeviceInfo(knownModel, color))
    }

    override fun onFileTransferProgress(transferID: Long, transferred: Long, total: Long) {
        sink?.sessionFileTransferProgress(transferID, transferred, total)
    }

    override fun onFileTransferFinished(transferID: Long, name: String?, errorCode: Int, message: String?) {
        transferFinished?.invoke(transferID)
        val kind = when (errorCode) {
            Libsharing.FileErrorNone -> null
            Libsharing.FileErrorCancelled -> FileTransferError.Kind.Cancelled
            Libsharing.FileErrorNotAllowed -> FileTransferError.Kind.NotAllowed
            Libsharing.FileErrorNotFound -> FileTransferError.Kind.NotFound
            Libsharing.FileErrorNotWritable -> FileTransferError.Kind.NotWritable
            Libsharing.FileErrorDiskFull -> FileTransferError.Kind.DiskFull
            Libsharing.FileErrorPermission -> FileTransferError.Kind.Permission
            Libsharing.FileErrorLocal -> FileTransferError.Kind.Local
            Libsharing.FileErrorConnection -> FileTransferError.Kind.Connection
            Libsharing.FileErrorObserveOnly -> FileTransferError.Kind.ObserveOnly
            Libsharing.FileErrorUnsupported -> FileTransferError.Kind.Unsupported
            else -> FileTransferError.Kind.Failed
        }
        sink?.sessionFileTransferFinished(transferID, name, kind?.let { FileTransferError(it, message.orEmpty()) })
    }

    override fun onDropDestination(dragID: Long, path: String?) {
        path ?: return
        sink?.sessionDropDestination(dragID, path)
    }

    override fun onRemoteDrag(drag: RemoteDrag?) {
        if (drag == null) {
            sink?.sessionRemoteDragChanged(emptyList(), null)
            return
        }
        sink?.sessionRemoteDragChanged((0 until drag.len()).map { RemoteDragItem(drag.path(it), drag.isFolder(it)) }, drag.image)
    }

    fun reportMediaFailure(failure: MediaFailure) {
        sink?.sessionVideoStopped(failure)
    }

    private companion object {
        const val TAG = "LibsharingSession"

        fun rowAligned(bytes: ByteArray, bytesPerRow: Int, height: Int, rowBytes: Int): ByteBuffer {
            if (rowBytes == bytesPerRow) return ByteBuffer.wrap(bytes, 0, bytesPerRow * height)
            val buffer = ByteBuffer.allocate(rowBytes * height)
            for (row in 0 until height) {
                buffer.position(row * rowBytes)
                buffer.put(bytes, row * bytesPerRow, bytesPerRow)
            }
            buffer.rewind()
            return buffer
        }
    }
}

private fun audioFormat(format: LibsharingAudioFormat): AudioFormat = AudioFormat(
    formatID = format.formatID and 0xFFFFFFFFL,
    sampleRate = format.sampleRate.toDouble(),
    channels = max(1, format.channels),
    framesPerPacket = max(1, format.framesPerPacket),
    magicCookie = format.magicCookie ?: ByteArray(0),
)
