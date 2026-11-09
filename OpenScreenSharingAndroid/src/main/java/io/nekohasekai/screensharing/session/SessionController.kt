package io.nekohasekai.screensharing.session

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.icu.text.BreakIterator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.ContinuedTask
import io.nekohasekai.screensharing.app.MessageError
import io.nekohasekai.screensharing.app.RuntimePermission
import io.nekohasekai.screensharing.model.AppDirectory
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.ComputerStore
import io.nekohasekai.screensharing.model.ConnectionRecord
import io.nekohasekai.screensharing.model.CursorStyle
import io.nekohasekai.screensharing.model.DeviceModel
import io.nekohasekai.screensharing.model.DeviceType
import io.nekohasekai.screensharing.model.KeyMapping
import io.nekohasekai.screensharing.model.KeyShortcut
import io.nekohasekai.screensharing.model.KeyShortcuts
import io.nekohasekai.screensharing.model.KeyTranslations
import io.nekohasekai.screensharing.model.MappingTarget
import io.nekohasekai.screensharing.model.NetworkConfiguration
import io.nekohasekai.screensharing.model.PasswordUpdate
import io.nekohasekai.screensharing.model.QuickInput
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.model.SessionQuality
import io.nekohasekai.screensharing.sessionui.RemoteScreenController
import io.nekohasekai.screensharing.sessionui.RemoteScreenSurface
import io.nekohasekai.screensharing.sessionui.ScreenObstructions
import io.nekohasekai.screensharing.sessionui.SessionActivity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

data class Letterbox(
    val left: Rect,
    val top: Rect,
    val topBetweenCorners: Rect?,
)

data class CredentialPrompt(
    val form: Form,
    val username: String,
    val remembers: Boolean,
    val failed: Boolean,
    val errorMessage: String = "",
    val server: ProbedServer,
    val computerID: UUID?,
    val allowsUnverifiedLogin: Boolean,
    val id: UUID = UUID.randomUUID(),
) {
    sealed interface Form {
        data class Account(
            val allowsRequest: Boolean,
        ) : Form

        data object Password : Form
    }
}

data class FileTransfer(
    val id: Long,
    val direction: Direction,
    val name: String,
    val transferred: Long = 0,
    val total: Long = 0,
    val state: State = State.Running,
    val temporaryFiles: List<File> = emptyList(),
) {
    enum class Direction {
        Upload,
        Download,
    }

    sealed interface State {
        data object Running : State

        data object Finished : State

        data class Failed(
            val error: FileTransferError,
        ) : State

        data object Cancelled : State
    }

    val isActive: Boolean get() = state == State.Running
}

class RemoteDragOffer(
    val items: List<RemoteDragItem>,
    val image: Bitmap?,
)

class TransferProgress {
    var totalUnitCount by mutableLongStateOf(0L)
        internal set
    var completedUnitCount by mutableLongStateOf(0L)
        internal set
    var isCancelled = false
        private set
    internal var cancellationHandler: (() -> Unit)? = null
        set(value) {
            field = value
            if (isCancelled) value?.invoke()
        }

    fun cancel() {
        if (isCancelled) return
        isCancelled = true
        cancellationHandler?.invoke()
    }
}

val FileTransferError.summary: String
    get() =
        App.instance.getString(
            when (kind) {
                FileTransferError.Kind.Cancelled -> R.string.session_transfer_canceled
                FileTransferError.Kind.NotAllowed -> R.string.session_transfer_not_allowed
                FileTransferError.Kind.NotFound -> R.string.session_transfer_not_found
                FileTransferError.Kind.NotWritable -> R.string.session_transfer_not_writable
                FileTransferError.Kind.DiskFull -> R.string.session_transfer_disk_full
                FileTransferError.Kind.Permission -> R.string.session_transfer_permission
                FileTransferError.Kind.Local -> R.string.session_transfer_local
                FileTransferError.Kind.Connection -> R.string.session_transfer_connection
                FileTransferError.Kind.ObserveOnly -> R.string.session_transfer_observe_only
                FileTransferError.Kind.Unsupported -> R.string.session_transfer_unsupported
                FileTransferError.Kind.Failed -> R.string.session_transfer_failed
            },
        )

private data class KeyboardSourceRequest(
    val id: String,
    val step: Step,
) {
    enum class Step {
        Toggling,
        Selecting,
        Leaving,
        Returning,
    }
}

private val mainHandler = Handler(Looper.getMainLooper())

class SessionController(
    val route: SessionRoute,
) : RemoteScreenController {
    enum class DropTarget {
        Desktop,
        Pointer,
    }

    private class DropDelivery(
        var loading: Int,
    ) {
        var destination: String? = null
        var discards = false
        val waiting = mutableListOf<Pair<File, Boolean>>()

        val isSettled: Boolean get() = destination != null || discards
    }

    private class PromisedFile(
        val folder: File,
        val progress: TransferProgress,
        val completion: (Result<File>) -> Unit,
    )

    private class Press(
        val buttons: PointerButtons,
        val position: Offset,
        val time: Long,
    )

    val title: String = route.name
    private var phaseState: SessionPhase by mutableStateOf(SessionPhase.Idle)
    final override var phase: SessionPhase
        get() = phaseState
        private set(value) {
            phaseState = value
            SessionActivityRegistry.set(this, value.isActive)
        }
    var mode: SessionMode by mutableStateOf(SessionMode.Control)
        private set
    var displays: List<RemoteDisplay> by mutableStateOf(emptyList())
        private set
    var displaySelection: DisplaySelection by mutableStateOf(DisplaySelection.Display(1))
        private set
    var capabilities: SessionCapabilities by mutableStateOf(SessionCapabilities.None)
        private set
    var usesHighPerformance: Boolean by mutableStateOf(route.remoteProtocol == RemoteProtocol.Vnc && route.usesHighPerformance)
        private set
    var quality: SessionQuality by mutableStateOf(SessionQuality.Adaptive)
        private set
    private var networkConfiguration: NetworkConfiguration by mutableStateOf(NetworkConfiguration())
    var clipboardSharing: Boolean by mutableStateOf(true)
        private set
    final override var info: SessionInfo by mutableStateOf(SessionInfo.of(route.remoteProtocol, route.knownPlatform))
        internal set
    private var keyboardSources: List<MacInputSource> = emptyList()
    private var keyboardSourceID: String? = null
    private var secureInput = false
    final override var imageSize: Size by mutableStateOf(Size.Zero)
        private set
    var picture: ScreenPicture by mutableStateOf(ScreenPicture.None)
        private set
    var isVideo: Boolean by mutableStateOf(false)
        private set
    final override var resolutionScale: Double by mutableStateOf(VirtualDisplayMode.DEFAULT_RESOLUTION_SCALE)
        private set
    var hdrEnabled: Boolean by mutableStateOf(false)
        private set
    override var displaySupportsHDR: Boolean by mutableStateOf(false)
    var receivesAudio: Boolean by mutableStateOf(true)
        private set
    var sendsAudio: Boolean by mutableStateOf(false)
        private set
    override var scaleMode: ScaleMode by mutableStateOf(ScaleMode.Fit)
    var isFullScreen: Boolean by mutableStateOf(false)
    var isToolbarVisible: Boolean by mutableStateOf(false)
    var isToolbarDocked: Boolean by mutableStateOf(false)
    var letterbox: Letterbox? by mutableStateOf(null)
    var debugInfoObstructions: ScreenObstructions? by mutableStateOf(null)
    var isPointerLockOn: Boolean by mutableStateOf(false)
        private set
    var isNativeTouchOn: Boolean by mutableStateOf(SessionMemory.defaultUsesNativeTouch)
        private set
    var isZoomModeOn: Boolean by mutableStateOf(false)
        private set
    private var isPointerLockedState: Boolean by mutableStateOf(false)
    var isPointerLocked: Boolean
        get() = isPointerLockedState
        set(value) {
            isPointerLockedState = value
            if (value) pointerLockHasLocked = true
        }
    var isSystemPointerOverPicture: Boolean by mutableStateOf(false)
    var isKeyboardVisible: Boolean by mutableStateOf(false)
        private set
    var usesHardwareKeyboard: Boolean by mutableStateOf(false)
        private set
    var stickyModifiers: Set<ModifierKey> by mutableStateOf(emptySet())
    var lockedModifiers: Set<ModifierKey> by mutableStateOf(emptySet())
        private set
    var pressedModifiers: Set<ModifierKey> by mutableStateOf(emptySet())
        private set
    var credentialPrompt: CredentialPrompt? by mutableStateOf(null)
    var computerID: UUID? = route.computerID
        private set
    var alert: AlertState? by mutableStateOf(null)
    var notice: SessionNotice? by mutableStateOf(null)
    var microphonePermission: RuntimePermission.Requester? = null
    var isClosing: Boolean by mutableStateOf(false)
        private set
    var dropTarget: DropTarget? by mutableStateOf(null)
        private set
    var isShowingResolution: Boolean by mutableStateOf(false)
    var isCustomizingToolbar: Boolean by mutableStateOf(false)
    var isEditingQuickInputs: Boolean by mutableStateOf(false)
    val debugInfo = DebugInfoMonitor()
    private var transfers: List<FileTransfer> = emptyList()
        set(value) {
            field = value
            updateTransferTask()
        }
    private var remotePointerVisible = true
    private var lastRemoteClipboard: ClipboardContent? = null
    private var syncedClipboardChangeCount: Long? = null
    private var reportedClipboardNotRestored = false
    private var readingClipboard = false

    final override val pipeline = DisplayPipeline()
    override var screen: RemoteScreenSurface? = null
    var toolbarHandleFrame: Rect? by mutableStateOf(null)
    var toolbarBarFrame: Rect? by mutableStateOf(null)
    var lastSystemPointerLocation: Offset? by mutableStateOf(null)
    private var pointerLockHasLocked: Boolean by mutableStateOf(false)
    var onClose: () -> Unit = {}

    private var session: LibsharingRemoteSession? = null
    private var sink: SessionEventSink? = null
    private var dragID: Long? = null
    private val drops = HashMap<Long, DropDelivery>()
    var remoteDragOffer: RemoteDragOffer? = null
        private set
    private val promisedFiles = HashMap<Long, PromisedFile>()
    private var holdsPointer = false
    private var remoteDragActive = false
    private var credentials: SessionCredentials? = null
    private val pressedKeys = LinkedHashMap<Int, MacKey>()
    private val sentModifiers = LinkedHashMap<ModifierKey, MacKey>()
    private var commandTapPending = false
    private var capsLockEngaged = false
    private var capsLockPressedAt: Long? = null
    private var capsLockActsOnPress = false
    private var keyboardSourceRequest: KeyboardSourceRequest? = null
    private var previousKeyboardSource: String? = null
    private var queuedKeyboardSource: MacInputSource? = null
    private var keyboardSourceTimeout: Job? = null
    private var keyboardType = MacKeyboardType.Ansi
    final override var pointerPosition: Offset = Offset.Zero
        private set
    private var pictureSize = Size.Zero
    private var pictureTurns = PictureTurns.None
    var pointerButtons: PointerButtons = PointerButtons.None
        private set
    private var clickCount = 0
    private var lastPress: Press? = null
    private var pointerModifiers: Set<ModifierKey> = emptySet()
    private val nativeTouchIDs = HashSet<Long>()
    private var lastPointerMoveTime = 0L
    private var pointerMovedSinceTouch = false
    private var displayArea = Size.Zero
    var windowSize: Size? =
        route.computerID
            ?.let { ComputerStore.stored(it) }
            ?.memory
            ?.windowSize
        private set
    private var displayScale = 2f
    private var screenSize = Size.Zero
    private var screenRefreshRate = 60
    private val virtualDisplayRequests = VirtualDisplayRequests()
    final override var sceneInBackground = false
        private set
    private var keepsAlive = false
    private var screenVisible = true
    var hasWindowFocus = false
    private var activeWhenBackgrounded = false
    private var backgroundTask: Job? = null
    private var transferTask: ContinuedTask? = null
    private var transferTaskIDs: Set<Long> = emptySet()
    private var reconnectsOnReturn = false
    private var restartsHighPerformanceOnReturn = false
    private var hasConnected = false
    private var cursorAssets: MacCursorAssets? = null
    private var cursorAssetsAttempted = false
    private var microphoneFormat: AudioFormat? = null
    private var microphone: MicrophoneSender? = null
    private var reconnectAttempts = 0
    private var reconnectTask: Job? = null
    private var chosenDisplay: DisplaySelection? = null
    private var restoresDisplay = false
    private var assistCursorShown = false
    private var highPerformanceStartPending = false
    internal var awaitsDisplayArea by mutableStateOf(false)
    private var awaitsVirtualDisplay = false
        set(value) {
            field = value
            pipeline.setHoldsVideo(value)
        }
    private var awaitsResolutionChange = false
        set(value) {
            val changed = value != field
            field = value
            if (changed) pipeline.setWaitsForDisplay(value)
        }
    var hasPicture: Boolean by mutableStateOf(false)
        private set
    private var isScrollGliding = false

    private var server: ServerAuthentication? = route.server
    private var probeTask: Job? = null
    private var trustedFingerprint = ""
    private var allowsUnverifiedLogin = false
    private var passwordUpdate: PasswordUpdate = PasswordUpdate.Keep
    private var usedSavedPassword = false
    private var device: DeviceInfo? = route.model?.let { DeviceInfo(it, "") }
    private var deviceTypeState: DeviceType? by mutableStateOf(route.deviceType ?: route.model?.let { DeviceType.of(it) })
    private var deviceType: DeviceType?
        get() = deviceTypeState
        set(value) {
            deviceTypeState = value
            if (isIOS) {
                virtualDisplayRequests.stopWaiting()
            }
        }

    private val isIOS: Boolean get() = deviceType?.isIOS ?: false
    private val isPhone: Boolean get() = deviceType == DeviceType.IPhone

    val platform: RemotePlatform
        get() = if (isIOS) RemotePlatform.IOS else info.platform

    init {
        virtualDisplayRequests.onIdle = { virtualDisplayDidSettle() }
        pipeline.setShownHandler {
            mainHandler.post {
                val shown = pipeline.currentPicture
                handle(picture = shown.picture, width = shown.width, height = shown.height, turns = shown.turns)
            }
        }
    }

    fun start() {
        if (session != null || probeTask != null) return
        val current = phase
        if (current is SessionPhase.Failed && current.failure.kind == SessionFailure.Kind.DrawingFailed) return
        probeTask =
            App.scope.launch(Dispatchers.Main) {
                withContext(Dispatchers.IO) { DecoderCapabilities.current }
                probeTask = null
                val stored = computerID?.let { ComputerStore.stored(it) }
                if (stored != null) {
                    restore(stored.memory)
                    device = device ?: stored.device
                    usesHighPerformance = stored.usesHighPerformance
                    deviceType = stored.deviceType
                    keyboardSources = stored.keyboardSources.map { MacInputSource(it) }
                    trustedFingerprint = stored.trustedFingerprint
                }
                networkConfiguration = stored?.networkConfiguration ?: NetworkConfiguration()
                val username = route.username ?: stored?.username ?: ""
                val savedPassword = stored?.takeIf { it.username == username }?.password
                passwordUpdate = PasswordUpdate.Keep
                usedSavedPassword = false
                val credentials = route.credentials
                if (credentials != null) {
                    connect(credentials)
                } else if (savedPassword != null) {
                    usedSavedPassword = true
                    connect(SessionCredentials.Account(username, savedPassword))
                } else {
                    askForCredentials(username)
                }
            }
    }

    private fun askForCredentials(
        username: String,
        failure: SessionFailure? = null,
    ) {
        phase = SessionPhase.Connecting
        server?.let {
            presentCredentialPrompt(it, username, failure)
            return
        }
        val route = route
        probeTask =
            App.scope.launch(Dispatchers.Main) {
                val result = ServerProber.probe(route.host, route.port, route.remoteProtocol)
                if (!isActive) return@launch
                probeTask = null
                if (phase != SessionPhase.Connecting) return@launch
                when (result) {
                    is ProbeResult.Success -> {
                        server = result.value.authentication
                        presentCredentialPrompt(result.value.authentication, username, failure)
                    }

                    is ProbeResult.Failure -> {
                        phase = SessionPhase.Failed(result.failure)
                    }
                }
            }
    }

    private fun presentCredentialPrompt(
        server: ServerAuthentication,
        username: String,
        failure: SessionFailure?,
    ) {
        val openCredentials = server.openCredentials
        val form: CredentialPrompt.Form
        if (openCredentials != null && failure == null) {
            connect(openCredentials)
            return
        } else if (server.account || server.requestPermission) {
            form = CredentialPrompt.Form.Account(allowsRequest = server.requestPermission)
        } else if (server.vncPassword) {
            form = CredentialPrompt.Form.Password
        } else {
            phase =
                SessionPhase.Failed(
                    SessionFailure(SessionFailure.Kind.Unsupported, "the server offers no sign-in method this app supports"),
                )
            return
        }
        credentialPrompt =
            CredentialPrompt(
                form = form,
                username = username,
                remembers = usedSavedPassword,
                failed = failure != null,
                errorMessage = failure?.message ?: "",
                server = ProbedServer(route.address, route.remoteProtocol, server, trustedFingerprint),
                computerID = computerID,
                allowsUnverifiedLogin = allowsUnverifiedLogin,
            )
    }

    fun submitCredentials(
        credentials: SessionCredentials,
        remember: Boolean,
    ) {
        credentialPrompt = null
        passwordUpdate =
            when (credentials) {
                is SessionCredentials.Account -> if (remember) PasswordUpdate.Save(credentials.password) else PasswordUpdate.Delete
                is SessionCredentials.VncPassword, is SessionCredentials.AskForPermission, SessionCredentials.NoAuthentication -> PasswordUpdate.Keep
            }
        usedSavedPassword = false
        connect(credentials)
    }

    fun cancelCredentials() {
        credentialPrompt = null
        close()
    }

    private fun restore(memory: SessionMemory) {
        quality = memory.quality
        mode = if (memory.observes) SessionMode.Observe else SessionMode.Control
        clipboardSharing = memory.sharesClipboard
        resolutionScale = memory.resolutionScale
        chosenDisplay = memory.display
        hdrEnabled = memory.usesHDR && DecoderCapabilities.current.supportsHDR
        receivesAudio = memory.receivesAudio
        sendsAudio = memory.sendsAudio
        windowSize = memory.windowSize
        isPointerLockOn = memory.locksPointer
        isNativeTouchOn = memory.usesNativeTouch
    }

    private val memory: SessionMemory
        get() =
            SessionMemory(
                quality = quality,
                observes = mode == SessionMode.Observe,
                sharesClipboard = clipboardSharing,
                resolutionScale = resolutionScale,
                display = chosenDisplay,
                usesHDR = hdrEnabled,
                receivesAudio = receivesAudio,
                sendsAudio = sendsAudio,
                windowSize = windowSize,
                locksPointer = isPointerLockOn,
                usesNativeTouch = isNativeTouchOn,
            )

    private fun rememberState() {
        val computerID = computerID ?: return
        save { ComputerStore.remember(memory, computerID) }
    }

    private fun captureThumbnail() {
        val computerID = computerID
        if (phase != SessionPhase.Connected || computerID == null) return
        App.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val thumbnail = pipeline.makeThumbnail() ?: return@launch
            save { ComputerStore.setThumbnail(thumbnail, computerID) }
        }
    }

    private fun recordConnection() {
        val credentials = credentials ?: return
        save {
            computerID =
                ComputerStore.record(
                    ConnectionRecord(
                        computerID = computerID,
                        name = route.name,
                        address = route.address,
                        username = credentials.account?.username ?: "",
                        password = passwordUpdate,
                        remoteProtocol = route.remoteProtocol,
                        usesHighPerformance = usesHighPerformance,
                        memory = memory,
                        device = device,
                    ),
                )
            passwordUpdate = PasswordUpdate.Keep
        }
        saveKeyboardSources()
    }

    private fun save(body: () -> Unit) {
        try {
            body()
        } catch (error: Exception) {
            alert = AlertState.failure(App.instance.getString(R.string.session_couldnt_save, title), error)
        }
    }

    internal fun handle(device: DeviceInfo) {
        this.device = device
        DeviceType.of(device.model)?.let { deviceType = it }
        val computerID = computerID ?: return
        save { ComputerStore.setDevice(device, computerID) }
    }

    val touchScreenSize: Size?
        get() {
            val density = touchScreenDensity ?: return null
            if (info.framebufferWidth <= 0 || info.framebufferHeight <= 0) return null
            return Size((info.framebufferWidth / density).toFloat(), (info.framebufferHeight / density).toFloat())
        }

    private val touchScreenDensity: Double?
        get() {
            if (!isIOS) return null
            return if (isPhone && min(info.framebufferWidth, info.framebufferHeight) >= 1000) 3.0 else 2.0
        }

    override val cursorPixelsPerPoint: Double
        get() {
            val density = touchScreenDensity ?: return info.cursorPixelsPerPoint
            return density / info.framebufferPixelsPerCursorPixel
        }

    private fun connect(credentials: SessionCredentials) {
        this.credentials = credentials
        awaitsDisplayArea = false
        var keepsAlive = false
        var disablesUDP = false
        computerID?.let { ComputerStore.computer(it) }?.let { computer ->
            networkConfiguration = computer.networkConfiguration
            keepsAlive = computer.keepsAlive
            disablesUDP = computer.disablesUDP
        }
        hdrEnabled = hdrEnabled && networkSupportsHDR
        highPerformanceStartPending = usesHighPerformance
        hasPicture = false
        pipeline.setExpectsVideo(usesHighPerformance)
        pipeline.setSchedulesPresentation(networkConfiguration.schedulesPresentation)
        val configuration =
            SessionConfiguration(
                address = route.address,
                remoteProtocol = route.remoteProtocol,
                platform = route.knownPlatform,
                trustedFingerprint = trustedFingerprint,
                allowsUnverifiedLogin = allowsUnverifiedLogin,
                keyboardLayout = WindowsKeyboardLayout.current,
                clientName =
                if (route.remoteProtocol == RemoteProtocol.Rdp ||
                    credentials.username.isEmpty()
                ) {
                    DeviceModel.name
                } else {
                    credentials.username
                },
                credentials = credentials,
                quality = quality,
                startsInObserveMode = mode == SessionMode.Observe,
                sharesClipboard = clipboardSharing,
                receivesAudio = receivesAudio,
                sendsAudio = sendsAudio,
                updateRate = screenRefreshRate,
                awaitsVirtualDisplay = wantsVirtualDisplay,
                networkConfiguration = networkConfiguration,
                keepsAlive = keepsAlive,
                disablesUDP = disablesUDP,
            )
        val sink = SessionEventSink(this, pipeline)
        val session = LibsharingRemoteSession(configuration, sink)
        sink.session = session
        if (sceneInBackground) {
            sink.setSceneInBackground(true)
        }
        this.keepsAlive = keepsAlive
        this.session = session
        this.sink = sink
        updateRemoteWakefulness()
        virtualDisplayRequests.reset(session.displayWidthMultiple, route.remoteProtocol == RemoteProtocol.Rdp) { mode ->
            session.setVirtualDisplay(mode, VirtualDisplayMode.largestSide(screenSize, displayScale))
        }
        restoresDisplay = chosenDisplay != null
        awaitsResolutionChange = false
        pipeline.holdPicture()
        phase = SessionPhase.Connecting
        // ScreenSharing configures virtual displays before SetEncodings (ScreenSharing 776.27, macOS 27.0.1 26A434,
        // UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50, -[SSSession stConfigureSession] 0x1cc060514..0x1cc060590).
        if (hdrEnabled) {
            session.setHDR(true)
        }
        awaitsVirtualDisplay = wantsVirtualDisplay
        virtualDisplayRequests.open()
        requestVirtualDisplay()
        if (usesHighPerformance) {
            session.startHighPerformanceWithConnection()
        }
        session.connect()
    }

    fun abort() {
        releaseAllKeys()
        isClosing = true
        teardown()
        phase = SessionPhase.Disconnected
        endBackgroundTask()
        pipeline.close()
    }

    fun close() {
        releaseAllKeys()
        session?.stop()
        session = null
        abort()
        onClose()
    }

    fun trustCertificate(fingerprint: String) {
        trustedFingerprint = fingerprint
        computerID?.let { computerID -> save { ComputerStore.setTrustedFingerprint(fingerprint, computerID) } }
        retry()
    }

    fun allowUnverifiedLogin() {
        allowsUnverifiedLogin = true
        retry()
    }

    fun retry() {
        teardown()
        reconnectAttempts = 0
        val current = phase
        if (current is SessionPhase.Failed && current.failure.kind == SessionFailure.Kind.AuthenticationFailed) {
            askForCredentials(credentials?.username ?: "", current.failure)
            return
        }
        val credentials = credentials
        if (credentials != null) {
            connect(credentials)
        } else {
            start()
        }
    }

    private fun teardown() {
        captureThumbnail()
        assistCursorShown = false
        isScrollGliding = false
        reconnectTask?.cancel()
        reconnectTask = null
        probeTask?.cancel()
        probeTask = null
        restartsHighPerformanceOnReturn = false
        microphoneFormat = null
        updateMicrophone()
        sink?.detach()
        awaitsResolutionChange = false
        pipeline.holdPicture()
        session?.abort()
        session = null
        sink = null
        virtualDisplayRequests.reset(sender = null)
        awaitsVirtualDisplay = false
        holdsPointer = false
        remoteDragActive = false
        pointerButtons = PointerButtons.None
        pointerModifiers = emptySet()
        nativeTouchIDs.clear()
        dragID = null
        dropTarget = null
        remoteDragOffer = null
        discardPendingDrops()
        abandonTransfers()
    }

    fun sceneDidEnterBackground() {
        if (sceneInBackground) return
        sceneInBackground = true
        activeWhenBackgrounded = phase.isActive
        captureThumbnail()
        releaseAllKeys()
        stopScrollMomentum()
        updateMicrophone()
        sink?.setSceneInBackground(true)
        updateRemoteWakefulness()
        screen?.backgroundStateDidChange()
        beginBackgroundTask()
        closeIfSuspending()
    }

    fun sceneDidBecomeActive() {
        if (!sceneInBackground) {
            if (hasWindowFocus) sendChangedClipboard()
            return
        }
        sceneInBackground = false
        endBackgroundTask()
        sink?.setSceneInBackground(false)
        updateRemoteWakefulness()
        updateMicrophone()
        screen?.backgroundStateDidChange()
        val wasActive = activeWhenBackgrounded
        activeWhenBackgrounded = false
        val restartsHighPerformance = restartsHighPerformanceOnReturn
        restartsHighPerformanceOnReturn = false
        if (phase == SessionPhase.Connected) {
            if (isVideo) {
                session?.requestKeyframe()
            } else {
                session?.requestRefresh()
            }
            if (hasWindowFocus) sendChangedClipboard()
            if (restartsHighPerformance) {
                restartHighPerformance()
            }
        } else if (reconnectsOnReturn || (wasActive && brokeInBackground)) {
            reconnectNow()
        }
    }

    fun setScreenVisible(visible: Boolean) {
        if (screenVisible == visible) return
        screenVisible = visible
        updateRemoteWakefulness()
    }

    private fun updateRemoteWakefulness() {
        session?.setKeepsAwake(keepsAlive || (!sceneInBackground && screenVisible))
    }

    private fun beginBackgroundTask() {
        if (backgroundTask?.isActive == true) return
        backgroundTask =
            App.scope.launch(Dispatchers.Main) {
                snapshotFlow { SessionActivityRegistry.keepsAlive }.first { !it }
                closeIfSuspending()
            }
    }

    private fun endBackgroundTask() {
        backgroundTask?.cancel()
        backgroundTask = null
    }

    fun closeIfSuspending() {
        if (!sceneInBackground || !phase.isActive || reconnectsOnReturn || SessionActivityRegistry.keepsAlive ||
            transferTask?.isRunning == true || sink?.keepsAppRunning == true
        ) {
            return
        }
        reconnectsOnReturn = true
        teardown()
        phase = SessionPhase.Reconnecting(1)
    }

    private val brokeInBackground: Boolean
        get() {
            if (!hasConnected || credentials == null) return false
            return when (val current = phase) {
                is SessionPhase.Reconnecting -> {
                    reconnectAttempts > 0
                }

                is SessionPhase.Failed -> {
                    current.failure.kind.let {
                        it == SessionFailure.Kind.ConnectionLost || it == SessionFailure.Kind.Unreachable ||
                            it == SessionFailure.Kind.HostNotFound
                    }
                }

                else -> {
                    false
                }
            }
        }

    private fun reconnectNow() {
        val credentials = credentials ?: return
        reconnectsOnReturn = false
        releaseAllKeys()
        teardown()
        reconnectAttempts = 0
        connect(credentials)
        phase = SessionPhase.Reconnecting(1)
    }

    private fun restartHighPerformance() {
        if (!usesHighPerformance || phase != SessionPhase.Connected || isVideo) return
        highPerformanceStartPending = true
        startHighPerformanceIfDisplayReady()
    }

    internal fun handle(phase: SessionPhase) {
        var newPhase = phase
        val reconnecting = this.phase is SessionPhase.Reconnecting
        if (reconnecting && (newPhase == SessionPhase.Connecting || newPhase == SessionPhase.Authenticating)) return
        if (newPhase is SessionPhase.Failed) {
            val failure = newPhase.failure
            val unreachable = failure.kind == SessionFailure.Kind.Unreachable || failure.kind == SessionFailure.Kind.HostNotFound
            val retryable = failure.kind == SessionFailure.Kind.ConnectionLost || (reconnecting && unreachable)
            val credentials = credentials
            if (retryable && hasConnected && reconnectAttempts < RECONNECT_LIMIT && credentials != null) {
                reconnectAttempts += 1
                val attempt = reconnectAttempts
                releaseAllKeys()
                teardown()
                this.phase = SessionPhase.Reconnecting(attempt)
                reconnectTask?.cancel()
                val seconds = min(1 shl (attempt - 1), 8)
                reconnectTask =
                    App.scope.launch(Dispatchers.Main) {
                        delay(seconds * 1000L)
                        if (this@SessionController.phase !is SessionPhase.Reconnecting) return@launch
                        connect(credentials)
                        this@SessionController.phase = SessionPhase.Reconnecting(attempt)
                    }
                return
            }
            if (reconnecting && unreachable) {
                newPhase = SessionPhase.Failed(SessionFailure(SessionFailure.Kind.ConnectionLost, failure.message))
            }
        }
        if (newPhase == SessionPhase.Connected && prepareCursorAssets()) return
        this.phase = newPhase
        when (newPhase) {
            SessionPhase.Connected -> {
                reconnectAttempts = 0
                if (info.dialect != ServerDialect.Apple) {
                    deviceType = null
                } else if (deviceType == null) {
                    deviceType = DeviceType.Mac
                }
                if (!hasConnected || passwordUpdate.isPending) {
                    hasConnected = true
                    recordConnection()
                }
                val fingerprint = info.fingerprint
                if (fingerprint.isNotEmpty() && fingerprint != trustedFingerprint) {
                    computerID?.let { computerID -> save { ComputerStore.setTrustedFingerprint(fingerprint, computerID) } }
                    trustedFingerprint = fingerprint
                }
                pipeline.setExpectsVideo(usesHighPerformance)
                val sendsClipboard =
                    hasWindowFocus &&
                        sendChangedClipboard { sent ->
                            if (!sent) session?.setClipboardSharing(clipboardSharing)
                        }
                if (!sendsClipboard) {
                    session?.setClipboardSharing(clipboardSharing)
                }
                if (hdrEnabled) {
                    session?.setHDR(true)
                }
                val offersVirtualDisplay = SessionCapabilities.VirtualDisplay in capabilities
                virtualDisplayRequests.connectionDidOpen(offersVirtualDisplay)
                if (!offersVirtualDisplay) {
                    awaitsVirtualDisplay = false
                }
                requestVirtualDisplay()
                startHighPerformanceIfDisplayReady()
                closeIfSuspending()
            }

            is SessionPhase.Failed -> {
                releaseAllKeys()
                if (newPhase.failure.kind == SessionFailure.Kind.AuthenticationFailed) {
                    when (credentials) {
                        is SessionCredentials.Account, is SessionCredentials.VncPassword -> {
                            retry()
                        }

                        else -> {}
                    }
                }
            }

            SessionPhase.Disconnected -> {
                releaseAllKeys()
            }

            else -> {}
        }
    }

    private fun prepareCursorAssets(): Boolean {
        val session = session
        if (AppSettings.cursorStyle != CursorStyle.Remote || info.dialect != ServerDialect.Apple || session == null) return false
        cursorAssets?.let {
            session.setCursorAssets(it)
            return false
        }
        if (cursorAssetsAttempted || SessionCapabilities.FileTransfer !in capabilities || !mode.controls) return false
        cursorAssetsAttempted = true
        phase = SessionPhase.PreparingCursors
        App.scope.launch(Dispatchers.Main) {
            val assets =
                withContext(Dispatchers.IO) {
                    try {
                        MacCursorAssets(session::downloadFiles)
                    } catch (error: Exception) {
                        null
                    }
                }
            cursorAssetsPrepared(assets)
        }
        return true
    }

    private fun cursorAssetsPrepared(assets: MacCursorAssets?) {
        cursorAssets = assets
        if (phase == SessionPhase.PreparingCursors) {
            handle(phase = SessionPhase.Connected)
        } else if (phase == SessionPhase.Connected) {
            prepareCursorAssets()
        }
    }

    private fun startHighPerformanceIfDisplayReady() {
        if (!highPerformanceStartPending || !usesHighPerformance || phase != SessionPhase.Connected ||
            !virtualDisplayRequests.isIdle
        ) {
            return
        }
        highPerformanceStartPending = false
        session?.startHighPerformance()
    }

    private fun virtualDisplayDidSettle() {
        awaitsVirtualDisplay = false
        awaitsResolutionChange = false
        startHighPerformanceIfDisplayReady()
    }

    internal fun handle(
        screenWidth: Int,
        height: Int,
        video: Boolean,
        resized: Boolean,
    ) {
        virtualDisplayRequests.framebufferDidChange(screenWidth, height, resized)
        if (isVideo != video) {
            isVideo = video
        }
    }

    private fun handle(
        picture: ScreenPicture,
        width: Int,
        height: Int,
        turns: PictureTurns,
    ) {
        this.picture = picture
        if (picture == ScreenPicture.Current) {
            hasPicture = true
        }
        val newPictureSize = Size(width.toFloat(), height.toFloat())
        if (newPictureSize == pictureSize && turns == pictureTurns) return
        val serverPointer = if (newPictureSize == pictureSize) pictureTurns.picturePoint(pointerPosition, pictureSize) else null
        pictureSize = newPictureSize
        pictureTurns = turns
        val size = turns.shownSize(newPictureSize)
        imageSize = size
        if (serverPointer != null) {
            pointerPosition = pictureTurns.shownPoint(serverPointer, pictureSize)
            pipeline.setCursorPosition(pointerPosition, remotePointerVisible)
        } else if (pointerPosition == Offset.Zero || pointerPosition.x >= size.width || pointerPosition.y >= size.height) {
            pointerPosition = Offset(size.width / 2, size.height / 2)
            pipeline.setCursorPosition(pointerPosition, remotePointerVisible)
        }
        screen?.imageSizeDidChange()
    }

    internal fun handle(
        displays: List<RemoteDisplay>,
        selection: DisplaySelection,
    ) {
        this.displays = displays
        displaySelection = selection
        val screen = pipeline.screenSize
        virtualDisplayRequests.displaysDidChange(displays, screen.width, screen.height)
        val chosen = chosenDisplay
        if (!restoresDisplay || chosen == null) return
        restoresDisplay = false
        val available =
            when (chosen) {
                DisplaySelection.All -> displays.size > 1
                is DisplaySelection.Display -> displays.any { it.id == chosen.id && !it.isVirtual }
            }
        if (available && chosen != selection) {
            displaySelection = chosen
            session?.selectDisplay(chosen)
        }
    }

    internal fun handle(mode: SessionMode) {
        this.mode = mode
        if (mode == SessionMode.Observe) {
            releaseAllKeys()
        }
        updateAssistCursor()
    }

    internal fun handle(capabilities: SessionCapabilities) {
        val gained = capabilities - this.capabilities
        this.capabilities = capabilities
        if (mode == SessionMode.Observe && SessionCapabilities.Observe !in capabilities) {
            mode = SessionMode.Control
        }
        if (SessionCapabilities.VirtualDisplay in gained && phase == SessionPhase.Connected) {
            requestVirtualDisplay()
        }
    }

    internal fun handleVideoFailure(failure: MediaFailure) {
        if (usesHighPerformance && (failure.kind == MediaFailure.Kind.Silent || failure.kind == MediaFailure.Kind.Network) &&
            sceneInBackground
        ) {
            restartsHighPerformanceOnReturn = true
            return
        }
        if (!usesHighPerformance) return
        failHighPerformance(failure)
    }

    private fun failHighPerformance(failure: MediaFailure) {
        releaseAllKeys()
        teardown()
        phase = SessionPhase.Failed(SessionFailure(SessionFailure.Kind.HighPerformance(failure.kind), failure.message))
    }

    override fun drawingFailed(error: Throwable) {
        credentialPrompt = null
        releaseAllKeys()
        teardown()
        phase = SessionPhase.Failed(SessionFailure(SessionFailure.Kind.DrawingFailed, error.localizedMessage ?: error.toString()))
    }

    internal fun handle(clipboard: ClipboardContent) {
        if (sameClipboard(clipboard, lastRemoteClipboard)) return
        lastRemoteClipboard = clipboard
        if (clipboard.isEmpty()) return
        ClipboardAccess.write(clipboard)?.let { syncedClipboardChangeCount = it }
    }

    internal fun handle(cursorMovedTo: Offset) {
        if (isControlling && System.nanoTime() - lastPointerMoveTime < POINTER_ECHO_INTERVAL) return
        pointerPosition = pictureTurns.shownPoint(cursorMovedTo, pictureSize)
        pipeline.setCursorPosition(pointerPosition, remotePointerVisible)
    }

    internal fun handle(
        keyboardSource: String?,
        secureInput: Boolean,
    ) {
        val current = keyboardSourceID
        if (current != null && keyboardSource != null && keyboardSource != current) {
            previousKeyboardSource = current
        }
        keyboardSourceID = keyboardSource
        this.secureInput = secureInput
        rememberKeyboardSource(keyboardSource)
        val request = keyboardSourceRequest
        if (keyboardSource == null || request == null) return
        val step = request.step
        when {
            step == KeyboardSourceRequest.Step.Toggling && keyboardSource == request.id -> {
                advanceKeyboardSource(request.id, null)
            }

            step == KeyboardSourceRequest.Step.Toggling && keyboardSource != current -> {
                advanceKeyboardSource(request.id, KeyboardSourceRequest.Step.Selecting)
            }

            step == KeyboardSourceRequest.Step.Selecting && keyboardSource == request.id -> {
                advanceKeyboardSource(request.id, KeyboardSourceRequest.Step.Leaving)
            }

            step == KeyboardSourceRequest.Step.Leaving && keyboardSource != request.id -> {
                advanceKeyboardSource(request.id, KeyboardSourceRequest.Step.Returning)
            }

            step == KeyboardSourceRequest.Step.Returning && keyboardSource == request.id -> {
                advanceKeyboardSource(request.id, null)
            }
        }
    }

    internal fun handle(event: SessionEvent) {
        when (event) {
            SessionEvent.Bell -> {
                screen?.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.R
                    ) {
                        HapticFeedbackConstants.REJECT
                    } else {
                        HapticFeedbackConstants.LONG_PRESS
                    },
                )
            }

            is SessionEvent.PointerVisible -> {
                remotePointerVisible = event.visible
                pipeline.setCursorPosition(pointerPosition, event.visible)
            }

            is SessionEvent.ClipboardUnreadable -> {
                alert = AlertState.failure(App.instance.getString(R.string.session_couldnt_receive_clipboard), MessageError(event.message))
            }

            is SessionEvent.ClipboardNotRestored -> {
                if (reportedClipboardNotRestored) return
                reportedClipboardNotRestored = true
                alert = AlertState.failure(App.instance.getString(R.string.session_couldnt_receive_clipboard), MessageError(event.message))
            }
        }
    }

    private fun uploadFile(
        file: File,
        remoteFolder: String,
        temporary: Boolean,
    ) {
        try {
            val session = session ?: throw MessageError("not connected")
            val id = session.uploadFile(file, remoteFolder)
            transfers =
                transfers +
                FileTransfer(
                    id,
                    FileTransfer.Direction.Upload,
                    file.name,
                    temporaryFiles = if (temporary) listOf(file) else emptyList(),
                )
        } catch (error: Exception) {
            if (temporary) removeTemporary(file)
            alert = AlertState.failure(App.instance.getString(R.string.session_couldnt_send, file.name), error)
        }
    }

    fun dropEntered(names: List<String>) {
        if (!canTransferFiles) return
        val id =
            if (SessionCapabilities.DragToRemote in capabilities && isControlling) {
                try {
                    session?.startDrop(names)
                } catch (error: Exception) {
                    null
                }
            } else {
                null
            }
        if (id != null) {
            dragID = id
            dropTarget = DropTarget.Pointer
        } else {
            dropTarget = DropTarget.Desktop
        }
    }

    fun dropMoved(point: Offset) {
        if (dragID == null) return
        moveTouch(point)
        if (PointerButtons.Primary !in pointerButtons) {
            press(PointerButtons.Primary)
        }
    }

    fun dropExited() {
        dropTarget = null
        val dragID = dragID ?: return
        this.dragID = null
        session?.cancelDrag(dragID)
        release(PointerButtons.Primary)
    }

    private fun endDrop(loading: Int): Long? {
        dropTarget = null
        val id = dragID ?: return null
        dragID = null
        release(PointerButtons.Primary)
        // The Mac sends no destination when the window under the pointer refuses the drop, as
        // Finder's search results such as Recents do.
        discardPendingDrops()
        drops[id] = DropDelivery(loading)
        return id
    }

    fun drop(
        files: List<File>,
        loading: Int,
    ): (File?, String, Throwable?) -> Unit {
        val id = endDrop(files.size + loading)
        for (file in files) {
            droppedFileLoaded(file, false, file.name, null, id)
        }
        return { file, name, error -> droppedFileLoaded(file, true, name, error, id) }
    }

    fun drop(uris: List<Uri>) {
        copyToCaches(uris, drop(emptyList(), uris.size))
    }

    private fun copyToCaches(
        uris: List<Uri>,
        loaded: (File?, String, Throwable?) -> Unit,
    ) {
        for (uri in uris) {
            App.scope.launch(Dispatchers.IO) {
                val name = droppedName(uri)
                val folder = File(File(AppDirectory.caches, "Drops"), UUID.randomUUID().toString())
                val destination = File(folder, name)
                val error =
                    try {
                        folder.mkdirs()
                        val input = App.instance.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
                        input.use { source -> destination.outputStream().use { source.copyTo(it) } }
                        null
                    } catch (error: Exception) {
                        removeTemporary(destination)
                        error
                    }
                withContext(Dispatchers.Main) {
                    loaded(if (error == null) destination else null, name, error)
                }
            }
        }
    }

    private fun droppedFileLoaded(
        file: File?,
        temporary: Boolean,
        name: String,
        error: Throwable?,
        dragID: Long?,
    ) {
        val delivery = dragID?.let { drops[it] }
        if (dragID == null || delivery == null) {
            if (file != null) {
                uploadFile(file, "~/Desktop", temporary)
            } else {
                alert = AlertState.failure(App.instance.getString(R.string.session_couldnt_send, name), error)
            }
            return
        }
        delivery.loading -= 1
        if (file != null) {
            val destination = delivery.destination
            if (delivery.discards) {
                if (temporary) removeTemporary(file)
            } else if (destination != null) {
                uploadFile(file, destination, temporary)
            } else {
                delivery.waiting.add(file to temporary)
            }
        } else if (!delivery.discards) {
            alert = AlertState.failure(App.instance.getString(R.string.session_couldnt_send, name), error)
        }
        if (delivery.loading == 0 && delivery.isSettled) {
            drops.remove(dragID)
        }
    }

    fun handleDropDestination(
        id: Long,
        path: String,
    ) {
        val delivery = drops[id] ?: return
        if (delivery.isSettled) return
        delivery.destination = path
        val waiting = delivery.waiting.toList()
        delivery.waiting.clear()
        for ((file, temporary) in waiting) {
            uploadFile(file, path, temporary)
        }
        if (delivery.loading == 0) {
            drops.remove(id)
        }
    }

    private fun discardPendingDrops() {
        val iterator = drops.values.iterator()
        while (iterator.hasNext()) {
            val delivery = iterator.next()
            if (delivery.isSettled) continue
            delivery.discards = true
            for ((file, temporary) in delivery.waiting) {
                if (temporary) removeTemporary(file)
            }
            delivery.waiting.clear()
            if (delivery.loading == 0) {
                iterator.remove()
            }
        }
    }

    fun downloadFile(
        remotePath: String,
        folder: File,
        progress: TransferProgress,
        completion: (Result<File>) -> Unit,
    ) {
        val name = remotePath.trimEnd('/').substringAfterLast('/')
        val id =
            try {
                val session = session ?: throw MessageError("not connected")
                session.downloadFile(remotePath, folder)
            } catch (error: Exception) {
                alert = AlertState.failure(App.instance.getString(R.string.session_couldnt_get, name), error)
                completion(Result.failure(IOException(error)))
                return
            }
        transfers = transfers + FileTransfer(id, FileTransfer.Direction.Download, name)
        progress.cancellationHandler = {
            mainHandler.post { session?.cancelTransfer(id) }
        }
        promisedFiles[id] = PromisedFile(folder, progress, completion)
    }

    internal fun handleRemoteDrag(
        items: List<RemoteDragItem>,
        image: ByteArray?,
    ) {
        remoteDragActive = items.isNotEmpty()
        // ScreensharingAgent ends a canceled remote drag with an empty 0x20 event, and sends nothing else for
        // the cancel request (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434,
        // UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3, sub_100012544 0x100012644..0x10001266c,
        // sub_100022378 0x100022434..0x100022448).
        if (!remoteDragActive && holdsPointer) {
            holdsPointer = false
            if (isControlling) sendPointer(pointerButtons)
        }
        if (!remoteDragActive || SessionCapabilities.DragFromRemote !in capabilities || !isControlling ||
            PointerButtons.Primary !in pointerButtons
        ) {
            remoteDragOffer = null
            return
        }
        val icon = image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        remoteDragOffer = RemoteDragOffer(items, icon)
    }

    fun takeRemoteDrag(): RemoteDragOffer? {
        val offer = remoteDragOffer
        if (offer == null || !isControlling) return null
        remoteDragOffer = null
        return offer
    }

    // ScreensharingAgent cancels the Mac's drag by activating Finder and then posting Escape
    // (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434, UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3,
    // sub_100022890 0x100022924..0x100022b94); a button released before that drops the items where the pointer is.
    fun cancelRemoteDrag() {
        val session = session
        if (!remoteDragActive || session == null) {
            release(PointerButtons.Primary)
            return
        }
        holdsPointer = true
        try {
            session.cancelRemoteDrag()
        } catch (error: Exception) {
            holdsPointer = false
            alert = AlertState.failure(App.instance.getString(R.string.session_couldnt_cancel_drag), error)
        }
        release(PointerButtons.Primary)
    }

    private fun abandonTransfers() {
        val interruption = FileTransferError(FileTransferError.Kind.Connection).let { it.copy(message = it.summary) }
        val abandoned = mutableListOf<FileTransfer>()
        val updated =
            transfers.map { transfer ->
                if (!transfer.isActive) return@map transfer
                transfer.temporaryFiles.forEach(::removeTemporary)
                transfer.copy(state = FileTransfer.State.Failed(interruption), temporaryFiles = emptyList()).also { abandoned.add(it) }
            }
        if (abandoned.isNotEmpty()) {
            transfers = updated
        }
        for (promised in promisedFiles.values.toList()) {
            promised.completion(Result.failure(interruption))
        }
        promisedFiles.clear()
        if (isClosing) return
        val transfer = abandoned.firstOrNull() ?: return
        alert =
            if (abandoned.size == 1) {
                val failure =
                    if (transfer.direction ==
                        FileTransfer.Direction.Upload
                    ) {
                        R.string.session_couldnt_send
                    } else {
                        R.string.session_couldnt_get
                    }
                AlertState.failure(App.instance.getString(failure, transfer.name), interruption)
            } else {
                AlertState.failure(
                    App.instance.getString(R.string.session_couldnt_finish_transferring_files),
                    MessageError(abandoned.joinToString("\n") { it.name }),
                )
            }
    }

    private fun updateTransferTask() {
        val running = transfers.filter { it.isActive }
        val current = running.firstOrNull()
        if (current == null) {
            val task = transferTask ?: return
            task.finish(transfers.all { it.id !in transferTaskIDs || it.state == FileTransfer.State.Finished })
            transferTask = null
            transferTaskIDs = emptySet()
            closeIfSuspending()
            return
        }
        val activity =
            App.instance.getString(
                if (current.direction == FileTransfer.Direction.Upload) R.string.session_sending else R.string.session_receiving,
                current.name,
            )
        val task =
            transferTask ?: ContinuedTask("transfer").also { task ->
                task.onExpire = {
                    for (transfer in transfers) {
                        if (transfer.isActive) session?.cancelTransfer(transfer.id)
                    }
                }
                task.contentIntent = SessionActivity.intent(App.instance, route)
                task.submit(activity, title)
                transferTask = task
            }
        transferTaskIDs = transferTaskIDs + running.map { it.id }
        val batch = transfers.filter { it.id in transferTaskIDs }
        task.update(activity, title, batch.sumOf { it.transferred }, batch.sumOf { it.total })
    }

    private fun update(
        id: Long,
        body: (FileTransfer) -> FileTransfer,
    ) {
        val index = transfers.indexOfFirst { it.id == id }
        if (index < 0) return
        transfers = transfers.toMutableList().also { it[index] = body(it[index]) }
    }

    internal fun handleTransferProgress(
        id: Long,
        transferred: Long,
        total: Long,
    ) {
        promisedFiles[id]?.progress?.let { progress ->
            progress.totalUnitCount = total
            progress.completedUnitCount = transferred
        }
        update(id) { it.copy(transferred = transferred, total = total) }
    }

    internal fun handleTransferFinished(
        id: Long,
        name: String?,
        error: FileTransferError?,
    ) {
        update(id) { transfer ->
            transfer.temporaryFiles.forEach(::removeTemporary)
            transfer.copy(
                name = if (name.isNullOrEmpty()) transfer.name else name,
                state =
                when {
                    error == null -> FileTransfer.State.Finished
                    error.kind == FileTransferError.Kind.Cancelled -> FileTransfer.State.Cancelled
                    else -> FileTransfer.State.Failed(error)
                },
                transferred = if (error == null) maxOf(transfer.transferred, transfer.total) else transfer.transferred,
                temporaryFiles = emptyList(),
            )
        }
        promisedFiles.remove(id)?.let { promised ->
            when {
                error != null -> promised.completion(Result.failure(error))
                !name.isNullOrEmpty() -> promised.completion(Result.success(File(promised.folder, name)))
                else -> promised.completion(Result.failure(FileNotFoundException()))
            }
        }
        val transfer = transfers.firstOrNull { it.id == id } ?: return
        val state = transfer.state
        if (state is FileTransfer.State.Failed) {
            val failure =
                if (transfer.direction ==
                    FileTransfer.Direction.Upload
                ) {
                    R.string.session_couldnt_send
                } else {
                    R.string.session_couldnt_get
                }
            alert = AlertState.failure(App.instance.getString(failure, transfer.name), state.error)
        }
    }

    fun answerSessionChoice(choice: SessionChoice) {
        val prompt = (phase as? SessionPhase.ChoosingSession)?.prompt ?: return
        phase = if (choice == SessionChoice.Cancel) SessionPhase.Disconnected else SessionPhase.Authenticating
        session?.answerSessionChoice(prompt, choice)
    }

    val isControlling: Boolean get() = mode.controls && phase == SessionPhase.Connected
    val canTransferFiles: Boolean get() = SessionCapabilities.FileTransfer in capabilities && phase == SessionPhase.Connected

    val loginName: String?
        get() = credentials?.username?.takeIf { it.isNotEmpty() }

    @JvmName("changeMode")
    fun setMode(newMode: SessionMode) {
        if (newMode == mode) return
        if (newMode == SessionMode.Observe) {
            releaseAllKeys()
        }
        mode = newMode
        session?.setMode(newMode)
        updateAssistCursor()
        rememberState()
    }

    fun selectDisplay(selection: DisplaySelection) {
        displaySelection = selection
        chosenDisplay = selection
        session?.selectDisplay(selection)
        rememberState()
    }

    @JvmName("changeQuality")
    fun setQuality(newQuality: SessionQuality) {
        if (newQuality == quality) return
        quality = newQuality
        rememberState()
        session?.setQuality(newQuality)
    }

    val networkSupportsHDR: Boolean
        get() = networkConfiguration.chroma.pixelFormats(DecoderCapabilities.current.pixelFormats) and (1 shl 5) != 0

    val canUseHDR: Boolean
        get() = usesHighPerformance && SessionCapabilities.HighPerformance in capabilities && displaySupportsHDR && networkSupportsHDR

    fun setHDR(enabled: Boolean) {
        if (enabled && !canUseHDR) return
        hdrEnabled = enabled
        session?.setHDR(enabled)
        rememberState()
    }

    internal fun videoDecodingFailed(
        error: Throwable,
        hdr: Boolean,
    ) {
        if (!hdr || !hdrEnabled) {
            failHighPerformance(MediaFailure(MediaFailure.Kind.Undecodable, error.localizedMessage ?: error.toString()))
            return
        }
        hdrEnabled = false
        session?.setHDR(false)
        rememberState()
        alert = AlertState.failure(App.instance.getString(R.string.session_device_cant_show_hdr), error)
    }

    @JvmName("changeReceivesAudio")
    fun setReceivesAudio(receivesAudio: Boolean) {
        val changed = receivesAudio != this.receivesAudio
        this.receivesAudio = receivesAudio
        rememberState()
        if (changed && SessionCapabilities.Audio in capabilities && phase == SessionPhase.Connected) {
            reconnectNow()
            return
        }
        session?.setReceivesAudio(receivesAudio)
    }

    @JvmName("changeSendsAudio")
    fun setSendsAudio(sendsAudio: Boolean) {
        App.scope.launch(Dispatchers.Main) {
            if (sendsAudio && !(microphonePermission?.request() ?: MicrophoneSender.permission.isGranted)) {
                alertMicrophoneDenied()
                return@launch
            }
            if (sendsAudio == this@SessionController.sendsAudio) return@launch
            this@SessionController.sendsAudio = sendsAudio
            rememberState()
            updateMicrophone()
            if (phase == SessionPhase.Connected) {
                reconnectNow()
            }
        }
    }

    internal fun handle(microphone: AudioFormat?) {
        microphoneFormat = microphone
        updateMicrophone()
    }

    private fun updateMicrophone() {
        val format = microphoneFormat
        if (format == null || !sendsAudio || sceneInBackground) {
            microphone?.stop()
            microphone = null
            return
        }
        if (microphone?.format == format) return
        microphone?.stop()
        microphone = null
        App.scope.launch(Dispatchers.Main) {
            val granted = microphonePermission?.request() ?: MicrophoneSender.permission.isGranted
            if (microphone != null || microphoneFormat != format || !sendsAudio || sceneInBackground) return@launch
            if (!granted) {
                sendsAudio = false
                rememberState()
                alertMicrophoneDenied()
                return@launch
            }
            val session = session
            try {
                val sender =
                    MicrophoneSender(
                        format,
                        send = { data -> session?.sendMicrophoneAudio(data) },
                        failed = { sender, error ->
                            mainHandler.post {
                                if (microphone !== sender) return@post
                                sender.stop()
                                microphone = null
                                alert = AlertState.failure(App.instance.getString(R.string.session_cant_use_microphone), error)
                            }
                        },
                    )
                sender.start()
                microphone = sender
            } catch (error: Exception) {
                alert = AlertState.failure(App.instance.getString(R.string.session_cant_use_microphone), error)
            }
        }
    }

    private fun alertMicrophoneDenied() {
        alert =
            AlertState(
                title = App.instance.getString(R.string.session_cant_use_microphone),
                message = App.instance.getString(R.string.session_microphone_access_off),
                actions =
                listOf(
                    AlertState.Action(App.instance.getString(R.string.open_settings)) {
                        val intent =
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", App.instance.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        App.instance.startActivity(intent)
                    },
                    AlertState.Action(App.instance.getString(R.string.ok), AlertState.Role.Cancel) {},
                ),
            )
    }

    @JvmName("changeClipboardSharing")
    fun setClipboardSharing(enabled: Boolean) {
        clipboardSharing = enabled
        rememberState()
        if (enabled && sendChangedClipboard { session?.setClipboardSharing(clipboardSharing) }) return
        session?.setClipboardSharing(enabled)
    }

    @JvmName("changeResolutionScale")
    fun setResolutionScale(scale: Double) {
        if (scale == resolutionScale) return
        resolutionScale = scale
        rememberState()
        requestVirtualDisplay(holdingPicture = true)
    }

    val wantsVirtualDisplay: Boolean
        get() = !isIOS && (usesHighPerformance || route.remoteProtocol == RemoteProtocol.Rdp)

    override val pictureMatchesWindow: Boolean
        get() = wantsVirtualDisplay && SessionCapabilities.VirtualDisplay in capabilities

    val preferredOrientation: Int
        get() {
            val established = when (phase) {
                SessionPhase.Connected, SessionPhase.PreparingCursors, is SessionPhase.Reconnecting -> true
                SessionPhase.Idle, SessionPhase.Disconnected, is SessionPhase.Failed -> false
                else -> awaitsDisplayArea
            }
            if (!established || credentialPrompt != null) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val followsWindow = wantsVirtualDisplay && (capabilities.isEmpty || SessionCapabilities.VirtualDisplay in capabilities)
            val size = imageSize
            if (!followsWindow && size.width > 0 && size.height > 0) {
                return if (size.width <
                    size.height
                ) {
                    ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                }
            }
            return if (platform ==
                RemotePlatform.IOS
            ) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            }
        }

    fun displayAreaDidChange(
        area: Size,
        scale: Float,
        screen: Size,
    ) {
        if (area.width <= 0 || area.height <= 0) return
        displayArea = area
        displayScale = scale
        screenSize = screen
        requestVirtualDisplay(holdingPicture = true)
    }

    fun windowSizeDidChange(size: Size) {
        if (size == windowSize) return
        windowSize = size
        rememberState()
    }

    override fun screenRefreshRateDidChange(rate: Int) {
        if (rate <= 0 || rate == screenRefreshRate) return
        screenRefreshRate = rate
        session?.setUpdateRate(rate)
    }

    private fun requestVirtualDisplay(holdingPicture: Boolean = false) {
        if (!(virtualDisplayRequests.isOpening || (phase == SessionPhase.Connected && SessionCapabilities.VirtualDisplay in capabilities)) ||
            !wantsVirtualDisplay
        ) {
            return
        }
        val mode = VirtualDisplayMode.matching(displayArea, displayScale, resolutionScale.toFloat()) ?: return
        virtualDisplayRequests.request(mode)
        if (holdingPicture && phase == SessionPhase.Connected && !virtualDisplayRequests.isIdle) {
            awaitsResolutionChange = true
        }
    }

    fun sendClipboardToMac() {
        val changeCount = ClipboardAccess.changeCount
        App.scope.launch {
            val (content, explanation) = ClipboardAccess.read()
            if (explanation != null) {
                alert = explanation
            }
            if (content.isNotEmpty()) sendClipboard(content, changeCount)
        }
    }

    fun sendChangedClipboard(completion: (Boolean) -> Unit = {}): Boolean {
        val changeCount = ClipboardAccess.changeCount
        if (syncedClipboardChangeCount == null) {
            syncedClipboardChangeCount = changeCount
            return false
        }
        if (!clipboardSharing || phase != SessionPhase.Connected || SessionCapabilities.Clipboard !in capabilities || readingClipboard ||
            changeCount == syncedClipboardChangeCount
        ) {
            return false
        }
        syncedClipboardChangeCount = changeCount
        readingClipboard = true
        App.scope.launch(Dispatchers.Main) {
            val (content, explanation) = ClipboardAccess.read()
            readingClipboard = false
            if (explanation != null) {
                alert = explanation
            }
            completion(content.isNotEmpty() && phase == SessionPhase.Connected && sendClipboard(content, changeCount))
        }
        return true
    }

    fun pasteFromDevice() {
        if (!isControlling) return
        commandTapPending = false
        val changeCount = ClipboardAccess.changeCount
        App.scope.launch {
            val (content, explanation) = ClipboardAccess.read()
            if (explanation != null) {
                alert = explanation
            }
            if (content.isEmpty() || !isControlling || session == null || !sendClipboard(content, changeCount, pasting = true)) return@launch
            screen?.forgetTypedText()
        }
    }

    private fun sendClipboard(
        content: ClipboardContent,
        changeCount: Long,
        pasting: Boolean = false,
    ): Boolean {
        try {
            val session = session ?: throw MessageError("not connected")
            session.sendClipboard(content, pasting)
            syncedClipboardChangeCount = changeCount
            lastRemoteClipboard = content
            return true
        } catch (error: Exception) {
            alert = AlertState.failure(App.instance.getString(R.string.session_couldnt_send_clipboard), error)
            return false
        }
    }

    fun getClipboardFromMac() {
        lastRemoteClipboard = null
        try {
            val session = session ?: throw MessageError("not connected")
            session.requestClipboard()
        } catch (error: Exception) {
            alert = AlertState.failure(App.instance.getString(R.string.session_couldnt_receive_clipboard), error)
        }
    }

    internal fun audioFailed(error: Throwable) {
        val failure = if (platform != RemotePlatform.Pc) R.string.session_cant_play_mac_sound else R.string.session_cant_play_computer_sound
        alert = AlertState.failure(App.instance.getString(failure), error)
    }

    fun sendChord(chord: KeyChord) {
        if (!isControlling) return
        screen?.forgetTypedText()
        tap(chord.key, chord.modifiers.toSet())
    }

    fun setScale(scale: ScaleMode) {
        screen?.applyScale(scale)
    }

    @JvmName("changeKeyboardVisible")
    fun setKeyboardVisible(visible: Boolean) {
        screen?.setKeyboardVisible(visible)
    }

    fun toggleKeyboard() {
        setKeyboardVisible(!isKeyboardVisible)
    }

    fun keyboardVisibilityDidChange(visible: Boolean) {
        isKeyboardVisible = visible
    }

    @JvmName("changeUsesHardwareKeyboard")
    fun setUsesHardwareKeyboard(uses: Boolean) {
        usesHardwareKeyboard = uses
    }

    fun toggleToolbar() {
        isToolbarVisible = !isToolbarVisible
    }

    val canLockPointer: Boolean get() = mode.controls

    fun setPointerLock(on: Boolean) {
        if (on == isPointerLockOn) return
        isPointerLockOn = on
        pointerLockHasLocked = false
        rememberState()
        if (on && isToolbarVisible) toggleToolbar()
    }

    fun pointerLockAvailabilityDidChange(available: Boolean) {
        if (!available) pointerLockHasLocked = false
    }

    fun setNativeTouch(on: Boolean) {
        if (on == isNativeTouchOn) return
        isNativeTouchOn = on
        rememberState()
    }

    fun setZoomMode(on: Boolean) {
        if (on == isZoomModeOn) return
        isZoomModeOn = on
        if (on && isToolbarVisible) toggleToolbar()
    }

    val pointerLockCanResume: Boolean
        get() {
            if (!pointerLockHasLocked || isToolbarDocked) return true
            val location = lastSystemPointerLocation ?: return false
            val margin = TOOLBAR_RESUME_MARGIN * App.instance.resources.displayMetrics.density
            return listOf(toolbarHandleFrame, toolbarBarFrame).any { it?.inflate(margin)?.contains(location) == true }
        }

    fun movePointer(point: Offset) {
        pointerMovedSinceTouch = true
        val clamped = clampToImage(point)
        pointerPosition = clamped
        lastPointerMoveTime = System.nanoTime()
        pipeline.setCursorPosition(clamped, true)
        if (!isControlling) {
            updateAssistCursor()
            return
        }
        sendPointer(pointerButtons)
    }

    val touchesDirectly: Boolean get() = isIOS && isControlling

    val touchesNatively: Boolean get() = isNativeTouchOn && SessionCapabilities.Touch in capabilities && isControlling

    fun sendTouches(touches: List<RemoteTouch>) {
        if (touches.isEmpty()) return
        for (touch in touches) {
            if (touch.phase != TouchPhase.Began) continue
            beginPointerInput()
            nativeTouchIDs.add(touch.id)
        }
        session?.sendTouches(touches.map { it.copy(location = pictureTurns.picturePoint(clampToImage(it.location), pictureSize)) })
        for (touch in touches) {
            if (touch.phase == TouchPhase.Ended || touch.phase == TouchPhase.Cancelled) nativeTouchIDs.remove(touch.id)
        }
        endPointerInput()
    }

    val hidesPointerForTouch: Boolean get() = isIOS && !pointerMovedSinceTouch

    fun moveTouch(point: Offset) {
        pointerMovedSinceTouch = false
        val clamped = clampToImage(point)
        pointerPosition = clamped
        lastPointerMoveTime = System.nanoTime()
        pipeline.setCursorPosition(clamped, true)
        if (!isControlling || pointerButtons.isEmpty) return
        sendPointer(pointerButtons)
    }

    fun pointerLeftScreen() {
        if (!assistCursorShown) return
        assistCursorShown = false
        session?.hideAssistCursor()
    }

    private fun updateAssistCursor() {
        val wanted = SessionCapabilities.AssistCursor in capabilities && !mode.controls && phase == SessionPhase.Connected
        if (wanted) {
            assistCursorShown = true
            val point = pictureTurns.picturePoint(pointerPosition, pictureSize)
            session?.setAssistCursor(point.x.toInt(), point.y.toInt(), !pointerButtons.isEmpty)
        } else {
            pointerLeftScreen()
        }
    }

    fun setButtons(buttons: PointerButtons) {
        if (PointerButtons.Primary !in buttons) remoteDragOffer = null
        pointerButtons = buttons
        if (!isControlling) {
            updateAssistCursor()
            return
        }
        sendPointer(buttons)
    }

    private fun sendPointer(buttons: PointerButtons) {
        if (holdsPointer) return
        val point = pictureTurns.picturePoint(pointerPosition, pictureSize)
        session?.sendPointer(floor(point.x).toInt(), floor(point.y).toInt(), buttons, clickCount)
    }

    fun press(
        button: PointerButtons,
        clickCount: Int? = null,
    ) {
        val now = System.nanoTime()
        val last = lastPress
        if (clickCount != null) {
            this.clickCount = clickCount
        } else if (last != null && last.buttons == button && now - last.time <= MULTI_CLICK_INTERVAL &&
            hypot(pointerPosition.x - last.position.x, pointerPosition.y - last.position.y) <= MULTI_CLICK_DISTANCE
        ) {
            this.clickCount += 1
        } else {
            this.clickCount = 1
        }
        lastPress = Press(button, pointerPosition, now)
        beginPointerInput()
        setButtons(pointerButtons + button)
    }

    fun release(button: PointerButtons) {
        setButtons(pointerButtons - button)
        endPointerInput()
    }

    private fun beginPointerInput() {
        if (!isControlling) return
        screen?.forgetTypedText()
        if (!pointerButtons.isEmpty || nativeTouchIDs.isNotEmpty()) return
        if (platform == RemotePlatform.Pc && pressedKeys.values.any { ModifierKey.of(it.keysym) == ModifierKey.Command }) {
            commandTapPending = false
            val modifiers = pressedKeys.values.mapNotNull { ModifierKey.of(it.keysym) }.toMutableSet()
            modifiers.remove(ModifierKey.Command)
            modifiers.add(ModifierKey.Control)
            setSentModifiers(modifiers)
        }
        pointerModifiers = stickyModifiers - lockedModifiers - pressedModifiers
        stickyModifiers = emptySet()
        for (modifier in ModifierKey.entries) {
            if (modifier in pointerModifiers) send(modifier.macKey, true)
        }
    }

    private fun endPointerInput() {
        if (!pointerButtons.isEmpty || nativeTouchIDs.isNotEmpty() || pointerModifiers.isEmpty()) return
        for (modifier in ModifierKey.entries.asReversed()) {
            if (modifier in pointerModifiers) send(modifier.macKey, false)
        }
        pointerModifiers = emptySet()
    }

    fun setPressedButtons(buttons: PointerButtons) {
        val released = pointerButtons - buttons
        val pressed = buttons - pointerButtons
        if (!released.isEmpty) release(released)
        if (!pressed.isEmpty) press(pressed)
    }

    fun click(
        button: PointerButtons,
        count: Int = 1,
    ) {
        repeat(count) {
            press(button)
            release(button)
        }
    }

    val sendsGesturesToMac: Boolean get() = SessionCapabilities.Gestures in capabilities && isControlling

    fun magnify(
        point: Offset,
        magnification: Double,
        phase: GesturePhase,
    ) {
        if (!sendsGesturesToMac) return
        val server = pictureTurns.picturePoint(point, pictureSize)
        session?.sendMagnify(server.x.toInt(), server.y.toInt(), magnification, phase)
    }

    fun rotate(
        point: Offset,
        degrees: Double,
        phase: GesturePhase,
    ) {
        if (!sendsGesturesToMac) return
        val server = pictureTurns.picturePoint(point, pictureSize)
        session?.sendRotate(server.x.toInt(), server.y.toInt(), degrees, phase)
    }

    fun scroll(
        delta: Offset,
        phase: GesturePhase,
    ) {
        if (!sendsGesturesToMac) return
        isScrollGliding = false
        val point = pictureTurns.picturePoint(pointerPosition, pictureSize)
        val vector = pictureTurns.pictureVector(delta)
        session?.sendScrollPhase(point.x.toInt(), point.y.toInt(), vector.x.toDouble(), vector.y.toDouble(), phase)
    }

    fun startScrollMomentum(velocity: Offset) {
        if (!sendsGesturesToMac || hypot(velocity.x, velocity.y) <= 50) return
        isScrollGliding = true
        val point = pictureTurns.picturePoint(pointerPosition, pictureSize)
        val vector = pictureTurns.pictureVector(velocity)
        session?.startScrollMomentum(point.x.toInt(), point.y.toInt(), vector.x.toDouble(), vector.y.toDouble())
    }

    fun stopScrollMomentum() {
        if (!isScrollGliding) return
        isScrollGliding = false
        session?.stopScrollMomentum()
    }

    fun canPerform(command: SystemCommand): Boolean = command.capability in capabilities || (platform != RemotePlatform.Pc && command.fallbackChord != null)

    fun perform(command: SystemCommand) {
        if (!isControlling) return
        screen?.forgetTypedText()
        val chord = command.fallbackChord
        if (command.capability in capabilities) {
            session?.sendSystemCommand(command)
        } else if (platform != RemotePlatform.Pc && chord != null) {
            sendChord(chord)
        }
    }

    fun scroll(delta: Offset) {
        if (!isControlling) return
        val point = pictureTurns.picturePoint(pointerPosition, pictureSize)
        val vector = pictureTurns.pictureVector(delta)
        session?.sendScroll(point.x.toInt(), point.y.toInt(), vector.x.toDouble(), vector.y.toDouble())
    }

    private fun clampToImage(point: Offset): Offset {
        val size = imageSize
        if (size.width <= 0 || size.height <= 0) return point
        return Offset(point.x.coerceIn(0f, size.width - 1), point.y.coerceIn(0f, size.height - 1))
    }

    fun handlePress(event: KeyEvent): Boolean {
        val down =
            when (event.action) {
                KeyEvent.ACTION_DOWN -> true
                KeyEvent.ACTION_UP -> false
                else -> return false
            }
        if (down && event.device?.isVirtual == false) setUsesHardwareKeyboard(true)
        if (event.keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
            if (!isControlling || SessionCapabilities.KeyCodes !in capabilities) return false
            if (event.repeatCount == 0) {
                if (down) capsLockPressed() else capsLockReleased()
            }
            return true
        }
        val id = if (event.scanCode != 0) event.scanCode else KEY_CODE_ID or event.keyCode
        if (down && event.repeatCount > 0) {
            val pressed = pressedKeys[id] ?: return isControlling
            if (!pressed.isModifier && !KeyEvent.isModifierKey(event.keyCode)) send(pressed, true)
            return true
        }
        val held = ModifierKey.held(event.metaState)
        var shortcut: KeyShortcut? = null
        if (down && isControlling) {
            shortcut = KeyShortcut.of(event)
            if (shortcut != null && KeyShortcuts.action(shortcut) != null) {
                commandTapPending = false
                return false
            }
            keyboardType = keyboardType.learning(event)
            val mapping = shortcut?.let { KeyShortcuts.mapping(it, mappingTarget) }
            if (mapping != null) {
                performMapping(mapping.steps, held)
                return true
            }
        }
        return handleKey(id, down, held, shortcut) { MacKey.of(event, keyboardType, platform, capsLockEngaged) }
    }

    private fun handleKey(
        id: Int,
        down: Boolean,
        held: Set<ModifierKey>,
        shortcut: KeyShortcut?,
        mapping: () -> MacKey?,
    ): Boolean {
        if (!down) {
            val pressed = pressedKeys.remove(id) ?: return isControlling && mapping() != null
            release(pressed)
            return true
        }
        if (!isControlling) return false
        val mapped = mapping() ?: return false
        val modifier = ModifierKey.of(mapped.keysym)
        if (modifier != null && modifier in lockedModifiers && mapped.keysym == modifier.keysym) {
            return true
        }
        if (!mapped.isModifier) screen?.forgetTypedText()
        if (secureInput && !Keysym.typesInSecureInput(mapped.keysym)) {
            notice = SessionNotice(App.instance.getString(R.string.session_password_fields_english_only))
            return true
        }
        pressedKeys.remove(id)?.let { release(it) }
        if (mapped.isModifier) {
            pressModifier(mapped, id, tappable = true)
            return true
        }
        var key = mapped
        if (platform == RemotePlatform.Pc) {
            commandTapPending = false
            var modifiers = held
            val output = shortcut?.let { KeyTranslations.output(it) }
            val chord = output?.chord(MappingTarget.Pc)
            if (shortcut != null && output != null && chord != null) {
                modifiers = chord.modifiers.toSet()
                if (output.key != shortcut.key) key = chord.key
            }
            setSentModifiers(modifiers)
        }
        val sticky = stickyModifiers - held
        if (sticky.isNotEmpty()) {
            stickyModifiers = emptySet()
            tap(key, sticky)
            return true
        }
        pressedKeys[id] = key
        send(key, true)
        return true
    }

    val mappingTarget: MappingTarget
        get() = if (platform == RemotePlatform.Pc) MappingTarget.Pc else MappingTarget.Apple

    private fun pressModifier(
        key: MacKey,
        id: Int,
        tappable: Boolean,
    ) {
        pressedKeys[id] = key
        val modifier = ModifierKey.of(key.keysym)
        if (platform != RemotePlatform.Pc || modifier == null) {
            send(key, true)
            return
        }
        commandTapPending = tappable && modifier == ModifierKey.Command
        if (modifier == ModifierKey.Command || sentModifiers[modifier] != null) return
        sentModifiers[modifier] = key
        send(key, true)
    }

    private fun release(key: MacKey) {
        val modifier = ModifierKey.of(key.keysym)
        if (platform != RemotePlatform.Pc || modifier == null) {
            send(key, false)
            return
        }
        if (modifier == ModifierKey.Command && commandTapPending) {
            send(key, true)
            send(key, false)
        }
        commandTapPending = false
        val held = pressedKeys.values.mapNotNull { ModifierKey.of(it.keysym) }.toSet()
        for ((kind, sent) in sentModifiers.entries.toList()) {
            if (held.isEmpty() || (kind == modifier && kind !in held)) {
                sentModifiers.remove(kind)
                send(sent, false)
            }
        }
    }

    private fun setSentModifiers(modifiers: Set<ModifierKey>) {
        for (modifier in ModifierKey.entries) {
            if (modifier !in modifiers || sentModifiers[modifier] != null) continue
            val key = pressedKeys.values.firstOrNull { ModifierKey.of(it.keysym) == modifier } ?: modifier.macKey
            sentModifiers[modifier] = key
            send(key, true)
        }
        for ((modifier, key) in sentModifiers.entries.toList()) {
            if (modifier in modifiers) continue
            sentModifiers.remove(modifier)
            send(key, false)
        }
    }

    fun replaceText(
        deleting: Int,
        text: String,
    ): Boolean {
        if (!isControlling) return false
        val asText = (lockedModifiers + pressedModifiers + stickyModifiers - ModifierKey.Shift).isEmpty()
        repeat(deleting) { tapKey(MacKey.backspace) }
        val typed = insertText(text)
        return asText && typed
    }

    private fun insertText(text: String): Boolean {
        var typed = true
        var remaining = graphemes(text)
        if ((lockedModifiers + pressedModifiers - ModifierKey.Shift).isNotEmpty()) {
            for (character in remaining) {
                typed = typeCharacter(character) && typed
            }
            return typed
        }
        if (stickyModifiers.isNotEmpty() && remaining.isNotEmpty()) {
            typed = typeCharacter(remaining.first())
            remaining = remaining.drop(1)
        }
        return typeUnmodified(remaining.joinToString("")) && typed
    }

    private fun typeUnmodified(text: String): Boolean {
        var typed = true
        val run = StringBuilder()
        for (character in graphemes(text)) {
            val key =
                when (character) {
                    "\n", "\r", "\r\n" -> MacKey.returnKey
                    "\t" -> MacKey.tab
                    else -> null
                }
            if (key == null) {
                run.append(character)
                continue
            }
            typed = typeText(run.toString()) && typed
            run.clear()
            tap(key, emptySet())
        }
        return typeText(run.toString()) && typed
    }

    private fun typeText(text: String): Boolean {
        if (text.isEmpty()) return true
        if (secureInput) {
            val typed = text.filter { it.code < 0x80 }
            if (typed.isNotEmpty()) runCatching { session?.sendText(typed) }
            if (typed.length != text.length) {
                notice = SessionNotice(App.instance.getString(R.string.session_password_fields_english_only))
                return false
            }
            return true
        }
        // ScreensharingAgent's KeyMapDictionary_ConvertKeysym recognizes Unicode keysyms only with high word 0x0100,
        // and its PostKeyEvent posts one UTF-16 unit (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434,
        // UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3, sub_100035ba0 0x100035c7c..0x100035c8c,
        // sub_100027410 0x100027890..0x100027898).
        val inBasicPlane = text.none { it.isSurrogate() }
        if (info.dialect != ServerDialect.Apple) {
            val typable = SessionCapabilities.UnicodeText in capabilities || inBasicPlane
            val typed = if (typable) text else text.filterNot { it.isSurrogate() }
            try {
                if (typed.isNotEmpty()) session?.sendText(typed)
            } catch (error: Exception) {
                alert = AlertState.failure(App.instance.getString(R.string.session_some_characters_cant_be_typed, title), error)
                return false
            }
            if (!typable) {
                notice = SessionNotice(App.instance.getString(R.string.session_some_characters_cant_be_typed, title))
            }
            return typable
        }
        val macComposes = keyboardSource?.isInputMethod ?: (SessionCapabilities.KeyboardSource in capabilities)
        if (inBasicPlane && !macComposes) {
            runCatching { session?.sendText(text) }
        } else {
            try {
                session?.pasteText(text)
            } catch (error: Exception) {
                alert = AlertState.failure(App.instance.getString(R.string.session_some_characters_cant_be_typed, title), error)
                return false
            }
        }
        return true
    }

    private fun typeCharacter(character: String): Boolean {
        val held = lockedModifiers + pressedModifiers
        val sticky = stickyModifiers
        val modifiers = held + sticky
        val key: MacKey
        var shift = false
        when (character) {
            "\n", "\r", "\r\n" -> {
                key = MacKey.returnKey
            }

            "\t" -> {
                key = MacKey.tab
            }

            else -> {
                val keystroke = if (ModifierKey.Option in modifiers && character.length == 1) MacKey.usKeystroke(character[0]) else null
                if (keystroke != null) {
                    key = keystroke.first
                    shift = keystroke.second
                } else {
                    var text = character
                    if (ModifierKey.Command in modifiers || ModifierKey.Control in modifiers) {
                        text = if (ModifierKey.Shift in modifiers) text.uppercase() else text.lowercase()
                    }
                    if (text.isEmpty()) return false
                    key = MacKey(Keysym.character(text.codePointAt(0)))
                }
            }
        }
        stickyModifiers = emptySet()
        if (secureInput && !Keysym.typesInSecureInput(key.keysym)) {
            notice = SessionNotice(App.instance.getString(R.string.session_password_fields_english_only))
            return false
        }
        var pressing = sticky
        if (shift) pressing = pressing + ModifierKey.Shift
        tap(key, pressing - held)
        return true
    }

    fun typeKey(key: MacKey) {
        if (!isControlling) return
        screen?.forgetTypedText()
        tapKey(key)
    }

    fun perform(input: QuickInput) {
        if (!isControlling) return
        performMapping(input.steps(mappingTarget), emptySet())
    }

    private fun tapKey(key: MacKey) {
        val sticky = stickyModifiers
        stickyModifiers = emptySet()
        tap(key, sticky)
    }

    private fun performMapping(
        steps: List<KeyMapping.Step>,
        held: Set<ModifierKey>,
    ) {
        screen?.forgetTypedText()
        val latched = (lockedModifiers + pressedModifiers).toMutableSet()
        val down = mutableSetOf<MacKey>()
        if (platform == RemotePlatform.Pc) {
            commandTapPending = false
            setSentModifiers(emptySet())
        } else {
            latched.addAll(held)
            down.addAll(pressedKeys.values.filter { ModifierKey.of(it.keysym) != null })
        }
        for (modifier in lockedModifiers + pressedModifiers) {
            down.add(modifier.macKey)
        }
        val released = mutableListOf<MacKey>()
        for (step in steps) {
            val chords =
                when (step) {
                    is KeyMapping.Step.Key -> step.combo.shortcuts.mapNotNull { it.chord(mappingTarget) }
                    is KeyMapping.Step.Text -> emptyList()
                }
            val wanted =
                chords
                    .firstOrNull()
                    ?.modifiers
                    .orEmpty()
                    .toSet()
            for (key in down.toList()) {
                val modifier = ModifierKey.of(key.keysym) ?: continue
                if (modifier in wanted) continue
                send(key, false)
                down.remove(key)
                latched.remove(modifier)
                released.add(key)
            }
            if (chords.isNotEmpty()) {
                val keys = ModifierKey.entries.filter { it in wanted && it !in latched }.map { it.macKey } + chords.map { it.key }
                for (key in keys) send(key, true)
                for (key in keys.asReversed()) send(key, false)
            } else if (step is KeyMapping.Step.Text) {
                typeUnmodified(step.text)
            } else if (step is KeyMapping.Step.Key) {
                step.combo.command?.let { perform(it) }
            }
        }
        for (key in released) {
            send(key, true)
        }
    }

    private fun tap(
        key: MacKey,
        modifiers: Set<ModifierKey>,
    ) {
        val held = ModifierKey.entries.filter { it in modifiers }.map { it.macKey }
        for (modifier in held) send(modifier, true)
        send(key, true)
        send(key, false)
        for (modifier in held.asReversed()) send(modifier, false)
    }

    private fun setCapsLock(engaged: Boolean) {
        if (engaged == capsLockEngaged || SessionCapabilities.KeyCodes !in capabilities) return
        capsLockEngaged = engaged
        send(MacKey.capsLock, engaged)
    }

    private fun capsLockPressed() {
        if (capsLockPressedAt != null) {
            capsLockActsOnPress = true
        }
        capsLockPressedAt = System.nanoTime()
        if (capsLockActsOnPress) {
            capsLockTapped()
        }
    }

    private fun capsLockReleased() {
        val pressedAt = capsLockPressedAt ?: return
        capsLockPressedAt = null
        if (capsLockActsOnPress) return
        if (System.nanoTime() - pressedAt >= CAPS_LOCK_HOLD_DURATION) {
            setCapsLock(!capsLockEngaged)
        } else {
            capsLockTapped()
        }
    }

    private fun capsLockTapped() {
        if (capsLockEngaged) {
            setCapsLock(false)
        } else if (!secureInput && macHasOtherKeyboardSource) {
            switchKeyboardSource()
        } else {
            setCapsLock(true)
        }
    }

    val keyboardSource: MacInputSource?
        get() = keyboardSourceID?.let { MacInputSource(it) }

    private val macHasOtherKeyboardSource: Boolean
        get() {
            val current = keyboardSource ?: return false
            return current.isInputMethod || keyboardSources.any { it.id != current.id }
        }

    fun switchKeyboardSource() {
        if (!isControlling) return
        sendChord(KeyChord.previousInputSource)
    }

    fun selectKeyboardSource(source: MacInputSource) {
        if (!isControlling || secureInput) return
        if (keyboardSourceRequest != null) {
            queuedKeyboardSource = source
            return
        }
        if (source.id == keyboardSourceID) return
        val previous = previousKeyboardSource
        advanceKeyboardSource(
            source.id,
            if (previous == null || previous == source.id) KeyboardSourceRequest.Step.Toggling else KeyboardSourceRequest.Step.Selecting,
        )
    }

    private fun advanceKeyboardSource(
        id: String,
        step: KeyboardSourceRequest.Step?,
    ) {
        keyboardSourceTimeout?.cancel()
        if (step == null) {
            keyboardSourceRequest = null
            queuedKeyboardSource?.let { queued ->
                queuedKeyboardSource = null
                selectKeyboardSource(queued)
            }
            return
        }
        if (step == KeyboardSourceRequest.Step.Selecting) {
            session?.selectKeyboardSource(id)
        } else {
            sendChord(KeyChord.previousInputSource)
        }
        keyboardSourceRequest = KeyboardSourceRequest(id, step)
        keyboardSourceTimeout =
            App.scope.launch(Dispatchers.Main) {
                delay(KEYBOARD_SOURCE_TIMEOUT)
                when (step) {
                    KeyboardSourceRequest.Step.Toggling -> {
                        advanceKeyboardSource(id, KeyboardSourceRequest.Step.Selecting)
                    }

                    KeyboardSourceRequest.Step.Selecting -> {
                        advanceKeyboardSource(id, KeyboardSourceRequest.Step.Leaving)
                    }

                    KeyboardSourceRequest.Step.Leaving -> {
                        advanceKeyboardSource(id, null)
                    }

                    KeyboardSourceRequest.Step.Returning -> {
                        sendChord(KeyChord.previousInputSource)
                        advanceKeyboardSource(id, null)
                    }
                }
            }
    }

    private fun rememberKeyboardSource(id: String?) {
        if (id == null || keyboardSources.any { it.id == id }) return
        keyboardSources = (keyboardSources + MacInputSource(id)).takeLast(8)
        saveKeyboardSources()
    }

    private fun saveKeyboardSources() {
        val computerID = computerID
        if (computerID == null || keyboardSources.isEmpty()) return
        val sources = keyboardSources.map { it.id }
        save { ComputerStore.setKeyboardSources(sources, computerID) }
    }

    fun toggleSticky(modifier: ModifierKey) {
        if (modifier in lockedModifiers) {
            setModifierLocked(modifier, false)
        } else if (modifier in stickyModifiers) {
            stickyModifiers = stickyModifiers - modifier
        } else if (isControlling) {
            stickyModifiers = stickyModifiers + modifier
        }
    }

    fun setModifierLocked(
        modifier: ModifierKey,
        locked: Boolean,
    ) {
        if (locked == modifier in lockedModifiers || (locked && !isControlling)) return
        stickyModifiers = stickyModifiers - modifier
        lockedModifiers = if (locked) lockedModifiers + modifier else lockedModifiers - modifier
        if (modifier !in pressedModifiers) {
            send(modifier.macKey, locked)
        }
    }

    fun setModifierPressed(
        modifier: ModifierKey,
        pressed: Boolean,
    ) {
        if (pressed == modifier in pressedModifiers || (pressed && !isControlling)) return
        stickyModifiers = stickyModifiers - modifier
        pressedModifiers = if (pressed) pressedModifiers + modifier else pressedModifiers - modifier
        if (modifier !in lockedModifiers) {
            send(modifier.macKey, pressed)
        }
    }

    fun releaseHeldKeys() {
        val keys = pressedKeys.values.sortedBy { it.isModifier }
        pressedKeys.clear()
        for (key in keys) {
            if (platform != RemotePlatform.Pc || ModifierKey.of(key.keysym) == null) send(key, false)
        }
        for (key in sentModifiers.values) {
            send(key, false)
        }
        sentModifiers.clear()
        commandTapPending = false
        capsLockPressedAt = null
        if (!pointerButtons.isEmpty) {
            release(pointerButtons)
        }
    }

    fun releaseAllKeys() {
        releaseHeldKeys()
        if (capsLockEngaged) {
            capsLockEngaged = false
            send(MacKey.capsLock, false)
        }
        for (modifier in lockedModifiers + pressedModifiers) {
            send(modifier.macKey, false)
        }
        lockedModifiers = emptySet()
        pressedModifiers = emptySet()
    }

    private fun send(
        key: MacKey,
        down: Boolean,
    ) {
        session?.sendKey(key, keyboardType, down)
    }

    fun sampleDebugInfo() {
        debugInfo.sample(if (phase == SessionPhase.Connected) session?.statistics() else null)
    }

    override val presentationRun: Int? get() = debugInfo.presentationRun

    override fun picturePresented(
        picture: Int,
        time: Long,
        run: Int,
    ) {
        debugInfo.picturePresented(picture, time, run)
    }

    private companion object {
        const val RECONNECT_LIMIT = 6
        const val MULTI_CLICK_INTERVAL = 500_000_000L
        const val MULTI_CLICK_DISTANCE = 4f
        const val POINTER_ECHO_INTERVAL = 300_000_000L
        const val CAPS_LOCK_HOLD_DURATION = 500_000_000L
        const val KEYBOARD_SOURCE_TIMEOUT = 500L
        const val TOOLBAR_RESUME_MARGIN = 16f
        const val KEY_CODE_ID = 0x10000
    }
}

private fun sameClipboard(
    a: ClipboardContent?,
    b: ClipboardContent?,
): Boolean {
    if (a == null || b == null) return a == null && b == null
    if (a.size != b.size) return false
    return a.indices.all { index ->
        val left = a[index]
        val right = b[index]
        left.keys == right.keys && left.all { (type, data) -> data contentEquals right[type] }
    }
}

private fun removeTemporary(file: File) {
    file.delete()
    file.parentFile?.delete()
}

private fun droppedName(uri: Uri): String {
    val resolver = App.instance.contentResolver
    val displayName =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (error: Exception) {
            null
        }
    var name =
        (displayName ?: uri.lastPathSegment?.substringAfterLast('/'))
            ?.replace('/', '_')
            ?.takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: App.instance.getString(R.string.session_item)
    if (name.substringAfterLast('.', "").isEmpty()) {
        val type =
            try {
                resolver.getType(uri)
            } catch (error: Exception) {
                null
            }
        type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { name = "$name.$it" }
    }
    return name
}

private fun graphemes(text: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result.add(text.substring(start, end))
        start = end
        end = iterator.next()
    }
    return result
}

class SessionEventSink(
    controller: SessionController,
    private val pipeline: DisplayPipeline,
) {
    private val lock = Any()
    private val controller = WeakReference(controller)
    private val debugInfo = controller.debugInfo
    private var detached = false
    private var audioPlayer: AudioPlayer? = null
    private var isVideo = false
    private var sceneInBackground = false

    @Volatile
    var session: LibsharingRemoteSession? = null

    private val decoder =
        VideoDecoder(
            pipeline.videoContext,
            output = { frame ->
                if (isDetached) {
                    frame.release()
                } else {
                    val timing = frame.timing
                    pipeline.setVideoFrame(frame) { picture -> pictureReady(picture, timing.arrival, timing.capture, timing.composed) }
                }
            },
            failure = { session?.requestKeyframe() },
            unsupported = { hdr, error -> onMain { it.videoDecodingFailed(error, hdr) } },
        )

    fun detach() {
        val player =
            synchronized(lock) {
                detached = true
                audioPlayer.also { audioPlayer = null }
            }
        player?.stop()
        decoder.reset()
    }

    private val isDetached: Boolean
        get() = synchronized(lock) { detached }

    fun setSceneInBackground(background: Boolean) {
        session?.setVisible(!background)
        decoder.setSuspended(background)
        synchronized(lock) {
            sceneInBackground = background
            audioPlayer?.setInBackground(background)
        }
    }

    val keepsAppRunning: Boolean
        get() = synchronized(lock) { audioPlayer }?.isPlaying ?: false

    private fun onMain(body: (SessionController) -> Unit) {
        mainHandler.post {
            val controller = controller.get()
            if (!isDetached && controller != null) body(controller)
        }
    }

    private fun pictureReady(
        picture: Int,
        arrival: Long,
        capture: Long?,
        composed: Long?,
    ) {
        if (debugInfo.presentationRun == null) return
        onMain { it.debugInfo.pictureReady(picture, arrival, capture, composed) }
    }

    fun sessionPhaseChanged(phase: SessionPhase) {
        onMain { it.handle(phase = phase) }
    }

    fun sessionAwaitsDisplay() {
        onMain { it.awaitsDisplayArea = true }
    }

    fun sessionNeedsFramebuffer(
        width: Int,
        height: Int,
    ): Framebuffer? {
        if (isDetached) return null
        val framebuffer = Framebuffer(width, height)
        pipeline.attachFramebuffer(framebuffer)
        val video = synchronized(lock) { isVideo }
        if (video) {
            pipeline.beginVideo()
        }
        onMain { it.handle(screenWidth = width, height = height, video = video, resized = true) }
        return framebuffer
    }

    fun sessionFramebufferUpdated(
        dirtyRect: Rect,
        arrival: Long,
    ) {
        if (isDetached) return
        try {
            pipeline.updateFramebuffer(dirtyRect)?.let { pictureReady(it, arrival, null, null) }
        } catch (error: Exception) {
            onMain { it.drawingFailed(error) }
        } catch (error: OutOfMemoryError) {
            onMain { it.drawingFailed(error) }
        }
    }

    fun sessionVideoFormatChanged(format: VideoFormat) {
        if (isDetached) return
        decoder.setFormat(format)
        val started =
            synchronized(lock) {
                val started = !isVideo
                isVideo = true
                started
            }
        if (!started) return
        pipeline.beginVideo()
        val size = pipeline.screenSize
        onMain { it.handle(screenWidth = size.width, height = size.height, video = true, resized = false) }
    }

    fun sessionVideoStopped(failure: MediaFailure?) {
        if (isDetached) return
        decoder.reset()
        val player =
            synchronized(lock) {
                isVideo = false
                audioPlayer.also { audioPlayer = null }
            }
        player?.stop()
        pipeline.endVideo()
        val size = pipeline.screenSize
        onMain { controller ->
            controller.handle(screenWidth = size.width, height = size.height, video = false, resized = false)
            if (failure != null) {
                controller.handleVideoFailure(failure)
            }
        }
    }

    fun sessionReceivedVideo(unit: VideoAccessUnit) {
        decoder.decode(unit)
    }

    fun sessionAudioFormatChanged(format: AudioFormat) {
        val player =
            try {
                AudioPlayer(
                    format,
                    failed = { error -> onMain { it.audioFailed(error) } },
                    stopped = { onMain { it.closeIfSuspending() } },
                )
            } catch (error: Exception) {
                onMain { it.audioFailed(error) }
                null
            }
        val previous =
            synchronized(lock) {
                if (detached) {
                    player
                } else {
                    val previous = audioPlayer
                    audioPlayer = player
                    player?.setInBackground(sceneInBackground)
                    previous
                }
            }
        previous?.stop()
    }

    fun sessionMicrophoneChanged(format: AudioFormat?) {
        onMain { it.handle(microphone = format) }
    }

    fun sessionReceivedAudio(unit: AudioAccessUnit) {
        synchronized(lock) { audioPlayer }?.enqueue(unit)
    }

    fun sessionDisplaysChanged(
        displays: List<RemoteDisplay>,
        selection: DisplaySelection,
    ) {
        onMain { it.handle(displays = displays, selection = selection) }
    }

    fun sessionCursorChanged(cursor: CursorShape?) {
        pipeline.setCursor(cursor)
    }

    fun sessionModeChanged(mode: SessionMode) {
        onMain { it.handle(mode = mode) }
    }

    fun sessionCapabilitiesChanged(capabilities: SessionCapabilities) {
        onMain { it.handle(capabilities = capabilities) }
    }

    fun sessionClipboardReceived(content: ClipboardContent) {
        onMain { it.handle(clipboard = content) }
    }

    fun sessionCursorMoved(
        x: Int,
        y: Int,
    ) {
        onMain { it.handle(cursorMovedTo = Offset(x.toFloat(), y.toFloat())) }
    }

    fun sessionKeyboardSourceChanged(
        source: String?,
        secureInput: Boolean,
    ) {
        onMain { it.handle(keyboardSource = source, secureInput = secureInput) }
    }

    fun sessionEvent(event: SessionEvent) {
        onMain { it.handle(event = event) }
    }

    fun sessionFileTransferProgress(
        id: Long,
        transferred: Long,
        total: Long,
    ) {
        onMain { it.handleTransferProgress(id, transferred, total) }
    }

    fun sessionFileTransferFinished(
        id: Long,
        name: String?,
        error: FileTransferError?,
    ) {
        onMain { it.handleTransferFinished(id, name, error) }
    }

    fun sessionDeviceInfo(device: DeviceInfo) {
        onMain { it.handle(device = device) }
    }

    fun sessionDropDestination(
        id: Long,
        path: String,
    ) {
        onMain { it.handleDropDestination(id, path) }
    }

    fun sessionRemoteDragChanged(
        items: List<RemoteDragItem>,
        image: ByteArray?,
    ) {
        onMain { it.handleRemoteDrag(items, image) }
    }

    fun sessionInfoChanged(info: SessionInfo) {
        onMain { it.info = info }
    }
}

class SessionNotice(val message: String)
