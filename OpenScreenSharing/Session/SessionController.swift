import Atomics
import CoreGraphics
import Foundation
import ImageIO
import Perception
import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif
import UniformTypeIdentifiers

enum ScaleMode: Equatable {
    case fit
    case actualSize
    case custom
}

struct Letterbox: Equatable {
    var left: CGRect
    var top: CGRect
    var topBetweenCorners: CGRect?
}

struct CredentialPrompt: Identifiable {
    enum Form: Equatable {
        case account(allowsRequest: Bool)
        case password
    }

    let id = UUID()
    var form: Form
    var username: String
    var remembers: Bool
    var failed: Bool
    var errorMessage = ""
    var server: ProbedServer
    var computerID: UUID?
    var allowsUnverifiedLogin: Bool
}

struct FileTransfer {
    enum Direction: Equatable {
        case upload
        case download
    }

    enum State: Equatable {
        case running
        case finished
        case failed(FileTransferError)
        case cancelled
    }

    let id: Int64
    let direction: Direction
    var name: String
    var transferred: Int64 = 0
    var total: Int64 = 0
    var state: State = .running
    var scopedURLs: [URL] = []
    var temporaryURLs: [URL] = []

    var isActive: Bool { state == .running }
}

struct RemoteDragOffer {
    let items: [RemoteDragItem]
    let image: CGImage?
}

nonisolated extension FileTransferError: LocalizedError {
    var summary: String {
        switch kind {
        case .cancelled: String(localized: "Canceled")
        case .notAllowed: String(localized: "The Mac doesn’t allow file transfers")
        case .notFound: String(localized: "The item isn’t available on the Mac")
        case .notWritable: String(localized: "The Mac can’t write to the folder")
        case .diskFull: String(localized: "The Mac’s disk is full")
        case .permission: String(localized: "The Mac doesn’t have permission to read the item")
        case .local: String(localized: "This device can’t read or write the file")
        case .connection: String(localized: "Connection interrupted")
        case .observeOnly: String(localized: "Not available while observing")
        case .unsupported: String(localized: "The Mac doesn’t support file transfers")
        case .failed: String(localized: "File transfer failed")
        }
    }

    var errorDescription: String? { message }
}

nonisolated struct SessionMemory: Equatable, Sendable {
    var quality = SessionQuality.adaptive
    var observes = false
    var sharesClipboard = true
    var resolutionScale = VirtualDisplayMode.defaultResolutionScale
    var display: DisplaySelection?
    var usesHDR = false
    var receivesAudio = true
    var sendsAudio = false
    var windowSize: CGSize?
    var locksPointer = false
    var usesNativeTouch: Bool

    @MainActor static var defaultUsesNativeTouch: Bool {
        #if os(iOS)
        UIDevice.current.userInterfaceIdiom == .pad
        #else
        false
        #endif
    }
}

private struct KeyboardSourceRequest {
    enum Step {
        case toggling
        case selecting
        case leaving
        case returning
    }

    let id: String
    var step: Step
}

@Perceptible
final class SessionController: Identifiable {
    let route: SessionRoute
    let title: String
    private(set) var phase: SessionPhase = .idle {
        didSet { SessionActivity.shared.set(self, active: phase.isActive) }
    }
    private(set) var mode: SessionMode = .control
    private(set) var displays: [RemoteDisplay] = []
    private(set) var displaySelection: DisplaySelection = .display(1)
    private(set) var capabilities: SessionCapabilities = []
    private(set) var usesHighPerformance: Bool
    private(set) var quality: SessionQuality = .adaptive
    private var networkConfiguration = NetworkConfiguration()
    private(set) var clipboardSharing = true
    fileprivate(set) var info: SessionInfo
    @PerceptionIgnored private var keyboardSources: [MacInputSource] = []
    @PerceptionIgnored private var keyboardSourceID: String?
    @PerceptionIgnored private var secureInput = false
    private(set) var imageSize: CGSize = .zero
    private(set) var picture = ScreenPicture.none
    private(set) var isVideo = false
    private(set) var resolutionScale = VirtualDisplayMode.defaultResolutionScale
    private(set) var hdrEnabled = false
    var displaySupportsHDR = false
    private(set) var receivesAudio = true
    private(set) var sendsAudio = false
    var scaleMode: ScaleMode = .fit
    var isFullScreen = false
    var isToolbarVisible = false
    var isToolbarDocked = false
    var letterbox: Letterbox?
    var debugInfoInset: CGFloat?
    private(set) var isPointerLockOn = false
    private(set) var isNativeTouchOn = SessionMemory.defaultUsesNativeTouch
    private(set) var isZoomModeOn = false
    var isPointerLocked = false {
        didSet {
            if isPointerLocked { pointerLockHasLocked = true }
        }
    }
    var isSystemPointerOverPicture = false
    private(set) var isKeyboardVisible = false
    private(set) var usesHardwareKeyboard = false
    var stickyModifiers: Set<ModifierKey> = []
    private(set) var lockedModifiers: Set<ModifierKey> = []
    private(set) var pressedModifiers: Set<ModifierKey> = []
    var credentialPrompt: CredentialPrompt?
    @PerceptionIgnored private(set) var computerID: UUID?
    var alert: AlertState?
    private(set) var isClosing = false
    private(set) var dropTarget: DropTarget?
    var isShowingResolution = false
    var isCustomizingToolbar = false
    var isEditingQuickInputs = false
    let debugInfo = DebugInfoMonitor()
    @PerceptionIgnored private var transfers: [FileTransfer] = [] {
        didSet { updateTransferTask() }
    }
    @PerceptionIgnored private var remotePointerVisible = true
    @PerceptionIgnored private var lastRemoteClipboard: ClipboardContent?
    @PerceptionIgnored private var syncedClipboardChangeCount = ClipboardAccess.changeCount
    @PerceptionIgnored private var reportedClipboardNotRestored = false
    @PerceptionIgnored private var readingClipboard = false
    @PerceptionIgnored private var decidedClipboardSharing = false

    let pipeline = DisplayPipeline()
    #if os(iOS)
    @PerceptionIgnored weak var screen: RemoteScreenUIView?
    #else
    @PerceptionIgnored weak var screen: RemoteScreenNSView?
    #endif
    var toolbarHandleFrame: CGRect?
    var toolbarBarFrame: CGRect?
    var lastSystemPointerLocation: CGPoint?
    private var pointerLockHasLocked = false
    private let store = ComputerStore.shared
    @PerceptionIgnored var onClose: () -> Void = {}

    @PerceptionIgnored private var session: LibsharingRemoteSession?
    @PerceptionIgnored private var sink: SessionEventSink?
    @PerceptionIgnored private var dragID: Int64?
    @PerceptionIgnored private var drops: [Int64: DropDelivery] = [:]
    @PerceptionIgnored private(set) var remoteDragOffer: RemoteDragOffer?
    @PerceptionIgnored private var promisedFiles: [Int64: PromisedFile] = [:]
    @PerceptionIgnored private var holdsPointer = false
    @PerceptionIgnored private var remoteDragActive = false
    private var credentials: SessionCredentials?
    @PerceptionIgnored private var pressedKeys: [Int: MacKey] = [:]
    @PerceptionIgnored private var sentModifiers: [ModifierKey: MacKey] = [:]
    @PerceptionIgnored private var commandTapPending = false
    @PerceptionIgnored private var repeatTask: Task<Void, Never>?
    @PerceptionIgnored private var capsLockEngaged = false
    @PerceptionIgnored private var capsLockPressedAt: TimeInterval?
    @PerceptionIgnored private var capsLockActsOnPress = false
    @PerceptionIgnored private var keyboardSourceRequest: KeyboardSourceRequest?
    #if os(macOS)
    @PerceptionIgnored private var inferredModifierKeys = Set<Int>()
    #endif
    @PerceptionIgnored private var previousKeyboardSource: String?
    @PerceptionIgnored private var queuedKeyboardSource: MacInputSource?
    @PerceptionIgnored private var keyboardSourceTimeout: Task<Void, Never>?
    @PerceptionIgnored private var keyboardType = MacKeyboardType.ansi
    @PerceptionIgnored private(set) var pointerPosition = CGPoint.zero
    @PerceptionIgnored private(set) var pointerButtons: PointerButtons = []
    @PerceptionIgnored private var clickCount = 0
    @PerceptionIgnored private var lastPress: (buttons: PointerButtons, position: CGPoint, time: TimeInterval)?
    @PerceptionIgnored private var pointerModifiers: Set<ModifierKey> = []
    @PerceptionIgnored private var nativeTouchIDs: Set<Int64> = []
    @PerceptionIgnored private var lastPointerMoveTime: TimeInterval = 0
    @PerceptionIgnored private var displayArea = CGSize.zero
    @PerceptionIgnored private(set) var windowSize: CGSize?
    @PerceptionIgnored private var displayScale: CGFloat = 2
    @PerceptionIgnored private var screenSize = CGSize.zero
    @PerceptionIgnored private var screenRefreshRate = 60
    private let virtualDisplayRequests = VirtualDisplayRequests()
    @PerceptionIgnored private(set) var sceneInBackground = false
    @PerceptionIgnored private var keepsAlive = false
    @PerceptionIgnored private var screenVisible = true
    @PerceptionIgnored private var localScreensAsleep = false
    @PerceptionIgnored nonisolated(unsafe) private var screenSleepObservers: [any NSObjectProtocol] = []
    @PerceptionIgnored private var activeWhenBackgrounded = false
    #if os(iOS)
    @PerceptionIgnored private var backgroundTask = UIBackgroundTaskIdentifier.invalid
    @PerceptionIgnored private var transferTask: ContinuedTask?
    @PerceptionIgnored private var transferTaskIDs: Set<Int64> = []
    #endif
    @PerceptionIgnored private var reconnectsOnReturn = false
    @PerceptionIgnored private var restartsHighPerformanceOnReturn = false
    @PerceptionIgnored private var hasConnected = false
    @PerceptionIgnored private var cursorAssets: MacCursorAssets?
    @PerceptionIgnored private var cursorAssetsAttempted = false
    @PerceptionIgnored private var microphoneFormat: AudioFormat?
    @PerceptionIgnored private var microphone: MicrophoneSender?
    @PerceptionIgnored private var reconnectAttempts = 0
    @PerceptionIgnored private var reconnectTask: Task<Void, Never>?
    @PerceptionIgnored private var chosenDisplay: DisplaySelection?
    @PerceptionIgnored private var restoresDisplay = false
    @PerceptionIgnored private var assistCursorShown = false
    @PerceptionIgnored private var highPerformanceStartPending = false
    @PerceptionIgnored private var awaitsVirtualDisplay = false {
        didSet { pipeline.setHoldsVideo(awaitsVirtualDisplay) }
    }
    @PerceptionIgnored private var awaitsResolutionChange = false {
        didSet {
            if awaitsResolutionChange != oldValue { pipeline.setWaitsForDisplay(awaitsResolutionChange) }
        }
    }
    private(set) var hasPicture = false
    @PerceptionIgnored private var isScrollGliding = false

    @PerceptionIgnored private var server: ServerAuthentication?
    @PerceptionIgnored private var probeTask: Task<Void, Never>?
    @PerceptionIgnored private var trustedFingerprint = ""
    @PerceptionIgnored private var allowsUnverifiedLogin = false
    @PerceptionIgnored private var passwordUpdate = PasswordUpdate.keep
    @PerceptionIgnored private var usedSavedPassword = false
    @PerceptionIgnored private var device: DeviceInfo?

    init(route: SessionRoute) {
        self.route = route
        title = route.name
        info = SessionInfo(remoteProtocol: route.remoteProtocol, platform: route.knownPlatform)
        computerID = route.computerID
        server = route.server
        device = route.model.map { DeviceInfo(model: $0, enclosureColor: "") }
        usesHighPerformance = route.remoteProtocol == .vnc && route.usesHighPerformance
        windowSize = route.computerID.flatMap { store.stored($0) }?.memory.windowSize
        virtualDisplayRequests.onIdle = { [weak self] in self?.virtualDisplayDidSettle() }
        #if os(macOS)
        let workspace = NSWorkspace.shared.notificationCenter
        for (name, asleep) in [(NSWorkspace.screensDidSleepNotification, true), (NSWorkspace.screensDidWakeNotification, false)] {
            screenSleepObservers.append(
                workspace.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated {
                        self?.localScreensAsleep = asleep
                        self?.updateRemoteWakefulness()
                    }
                })
        }
        #endif
        pipeline.setShownHandler { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                let shown = self.pipeline.currentPicture
                self.handle(picture: shown.picture, width: shown.width, height: shown.height)
            }
        }
    }

    deinit {
        for observer in screenSleepObservers { NotificationCenter.default.removeObserver(observer) }
    }


    func start() {
        guard session == nil, probeTask == nil else { return }
        if case .failed(let failure) = phase, failure.kind == .drawingFailed { return }
        let stored = computerID.flatMap { store.stored($0) }
        if let stored {
            restore(stored.memory)
            device = device ?? stored.device
            usesHighPerformance = stored.usesHighPerformance
            keyboardSources = stored.keyboardSources.map(MacInputSource.init(id:))
            trustedFingerprint = stored.trustedFingerprint
        }
        if !decidedClipboardSharing {
            decidedClipboardSharing = true
            clipboardSharing = clipboardSharing && ClipboardAccess.shared.allowsReading
        }
        networkConfiguration = stored?.networkConfiguration ?? NetworkConfiguration()
        let username = route.username ?? stored?.username ?? ""
        let savedPassword = stored.flatMap { $0.username == username ? $0.password : nil }
        passwordUpdate = .keep
        usedSavedPassword = false
        if let credentials = route.credentials {
            connect(with: credentials)
        } else if let savedPassword {
            usedSavedPassword = true
            connect(with: .account(username: username, password: savedPassword))
        } else {
            askForCredentials(username: username)
        }
    }

    private func askForCredentials(username: String, failure: SessionFailure? = nil) {
        phase = .connecting
        if let server {
            presentCredentialPrompt(for: server, username: username, failure: failure)
            return
        }
        probeTask = Task { [weak self, route] in
            let result = await ServerProber.probe(host: route.host, port: route.port, remoteProtocol: route.remoteProtocol)
            guard let self, !Task.isCancelled else { return }
            probeTask = nil
            guard phase == .connecting else { return }
            switch result {
            case .success(let server):
                self.server = server.authentication
                presentCredentialPrompt(for: server.authentication, username: username, failure: failure)
            case .failure(let failure):
                phase = .failed(failure)
            }
        }
    }

    private func presentCredentialPrompt(for server: ServerAuthentication, username: String, failure: SessionFailure?) {
        let form: CredentialPrompt.Form
        if let credentials = server.openCredentials, failure == nil {
            connect(with: credentials)
            return
        } else if server.account || server.requestPermission {
            form = .account(allowsRequest: server.requestPermission)
        } else if server.vncPassword {
            form = .password
        } else {
            phase = .failed(SessionFailure(.unsupported, message: "the server offers no sign-in method this app supports"))
            return
        }
        credentialPrompt = CredentialPrompt(
            form: form, username: username, remembers: usedSavedPassword, failed: failure != nil, errorMessage: failure?.message ?? "",
            server: ProbedServer(
                address: route.address, remoteProtocol: route.remoteProtocol, authentication: server, fingerprint: trustedFingerprint),
            computerID: computerID, allowsUnverifiedLogin: allowsUnverifiedLogin)
    }

    func submitCredentials(_ credentials: SessionCredentials, remember: Bool) {
        credentialPrompt = nil
        switch credentials {
        case .account(_, let password): passwordUpdate = remember ? .save(password) : .delete
        case .vncPassword, .askForPermission, .noAuthentication: passwordUpdate = .keep
        }
        usedSavedPassword = false
        connect(with: credentials)
    }

    func cancelCredentials() {
        credentialPrompt = nil
        close()
    }

    private func restore(_ memory: SessionMemory) {
        quality = memory.quality
        mode = memory.observes ? .observe : .control
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

    private var memory: SessionMemory {
        SessionMemory(
            quality: quality,
            observes: mode == .observe,
            sharesClipboard: clipboardSharing,
            resolutionScale: resolutionScale,
            display: chosenDisplay,
            usesHDR: hdrEnabled,
            receivesAudio: receivesAudio,
            sendsAudio: sendsAudio,
            windowSize: windowSize,
            locksPointer: isPointerLockOn,
            usesNativeTouch: isNativeTouchOn
        )
    }

    private func rememberState() {
        guard let computerID else { return }
        save { try $0.remember(memory, for: computerID) }
    }

    private func captureThumbnail() {
        guard phase == .connected, let computerID, let thumbnail = pipeline.makeThumbnail() else { return }
        save { try $0.setThumbnail(thumbnail, for: computerID) }
    }

    private func recordConnection() {
        guard let credentials else { return }
        save { store in
            computerID = try store.record(
                ConnectionRecord(
                    computerID: computerID,
                    name: route.name,
                    address: route.address,
                    username: credentials.account?.username ?? "",
                    password: passwordUpdate,
                    remoteProtocol: route.remoteProtocol,
                    usesHighPerformance: usesHighPerformance,
                    memory: memory,
                    device: device
                ))
            passwordUpdate = .keep
        }
        saveKeyboardSources()
    }

    private func save(_ body: (ComputerStore) throws -> Void) {
        do {
            try body(store)
        } catch {
            alert = AlertState(String(localized: "Couldn’t Save “\(title)”"), error: error)
        }
    }

    fileprivate func handle(device: DeviceInfo) {
        self.device = device
        if let computerID {
            save { try $0.setDevice(device, for: computerID) }
        }
    }

    private func connect(with credentials: SessionCredentials) {
        self.credentials = credentials
        var keepsAlive = false
        var disablesUDP = false
        if let computerID, let computer = store.computer(computerID) {
            networkConfiguration = computer.networkConfiguration
            keepsAlive = computer.keepsAlive
            disablesUDP = computer.disablesUDP
        }
        hdrEnabled = hdrEnabled && networkSupportsHDR
        highPerformanceStartPending = usesHighPerformance
        hasPicture = false
        pipeline.setExpectsVideo(usesHighPerformance)
        pipeline.setSchedulesPresentation(networkConfiguration.schedulesPresentation)
        let configuration = SessionConfiguration(
            address: route.address,
            remoteProtocol: route.remoteProtocol,
            platform: route.knownPlatform,
            trustedFingerprint: trustedFingerprint,
            allowsUnverifiedLogin: allowsUnverifiedLogin,
            keyboardLayout: WindowsKeyboardLayout.current,
            clientName: route.remoteProtocol == .rdp || credentials.username.isEmpty ? Self.deviceName : credentials.username,
            credentials: credentials,
            quality: quality,
            startsInObserveMode: mode == .observe,
            sharesClipboard: clipboardSharing,
            receivesAudio: receivesAudio,
            sendsAudio: sendsAudio,
            updateRate: screenRefreshRate,
            awaitsVirtualDisplay: wantsVirtualDisplay,
            networkConfiguration: networkConfiguration,
            keepsAlive: keepsAlive,
            disablesUDP: disablesUDP
        )
        let sink = SessionEventSink(controller: self, pipeline: pipeline)
        let session = LibsharingRemoteSession(configuration: configuration, delegate: sink)
        sink.session = session
        if sceneInBackground {
            sink.setSceneInBackground(true)
        }
        self.keepsAlive = keepsAlive
        self.session = session
        self.sink = sink
        updateRemoteWakefulness()
        virtualDisplayRequests.reset(widthMultiple: session.displayWidthMultiple, sizedByConnection: route.remoteProtocol == .rdp) {
            [weak self, weak session] mode in
            guard let self else { return }
            session?.setVirtualDisplay(
                mode, largestSide: VirtualDisplayMode.largestSide(onScreen: screenSize, screenScale: displayScale))
        }
        restoresDisplay = chosenDisplay != nil
        awaitsResolutionChange = false
        pipeline.holdPicture()
        phase = .connecting
        // ScreenSharing configures virtual displays before SetEncodings (ScreenSharing 776.27, macOS 27.0.1 26A434,
        // UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50, -[SSSession stConfigureSession] 0x1cc060514..0x1cc060590).
        if hdrEnabled {
            session.setHDR(true)
        }
        awaitsVirtualDisplay = wantsVirtualDisplay
        virtualDisplayRequests.open()
        requestVirtualDisplay()
        if usesHighPerformance {
            session.startHighPerformanceWithConnection()
        }
        session.connect()
    }

    func abort() {
        releaseAllKeys()
        isClosing = true
        teardown()
        phase = .disconnected
        endBackgroundTask()
    }

    func close() {
        releaseAllKeys()
        session?.stop()
        session = nil
        abort()
        onClose()
    }

    func trustCertificate(_ fingerprint: String) {
        trustedFingerprint = fingerprint
        if let computerID {
            save { try $0.setTrustedFingerprint(fingerprint, for: computerID) }
        }
        retry()
    }

    func allowUnverifiedLogin() {
        allowsUnverifiedLogin = true
        retry()
    }

    func retry() {
        teardown()
        reconnectAttempts = 0
        if case .failed(let failure) = phase, failure.kind == .authenticationFailed {
            askForCredentials(username: credentials?.username ?? "", failure: failure)
            return
        }
        if let credentials {
            connect(with: credentials)
        } else {
            start()
        }
    }

    private func teardown() {
        captureThumbnail()
        assistCursorShown = false
        isScrollGliding = false
        reconnectTask?.cancel()
        reconnectTask = nil
        probeTask?.cancel()
        probeTask = nil
        restartsHighPerformanceOnReturn = false
        microphoneFormat = nil
        updateMicrophone()
        sink?.detach()
        awaitsResolutionChange = false
        pipeline.holdPicture()
        session?.abort()
        session = nil
        sink = nil
        virtualDisplayRequests.reset(sender: nil)
        awaitsVirtualDisplay = false
        holdsPointer = false
        remoteDragActive = false
        pointerButtons = []
        pointerModifiers = []
        nativeTouchIDs = []
        dragID = nil
        dropTarget = nil
        remoteDragOffer = nil
        discardPendingDrops()
        abandonTransfers()
    }


    func sceneDidEnterBackground() {
        guard !sceneInBackground else { return }
        sceneInBackground = true
        activeWhenBackgrounded = phase.isActive
        captureThumbnail()
        releaseAllKeys()
        stopScrollMomentum()
        updateMicrophone()
        sink?.setSceneInBackground(true)
        updateRemoteWakefulness()
        #if os(iOS)
        screen?.backgroundStateDidChange()
        #endif
        if phase == .connected {
            beginBackgroundTask()
        }
        closeIfSuspending()
    }

    func sceneDidBecomeActive() {
        guard sceneInBackground else {
            sendChangedClipboard()
            return
        }
        sceneInBackground = false
        endBackgroundTask()
        sink?.setSceneInBackground(false)
        updateRemoteWakefulness()
        updateMicrophone()
        #if os(iOS)
        screen?.backgroundStateDidChange()
        #endif
        let wasActive = activeWhenBackgrounded
        activeWhenBackgrounded = false
        let restartsHighPerformance = restartsHighPerformanceOnReturn
        restartsHighPerformanceOnReturn = false
        if phase == .connected {
            if isVideo {
                session?.requestKeyframe()
            } else {
                session?.requestRefresh()
            }
            sendChangedClipboard()
            if restartsHighPerformance {
                restartHighPerformance()
            }
        } else if reconnectsOnReturn || wasActive && brokeInBackground {
            reconnectNow()
        }
    }

    func setScreenVisible(_ visible: Bool) {
        guard screenVisible != visible else { return }
        screenVisible = visible
        updateRemoteWakefulness()
    }

    private func updateRemoteWakefulness() {
        session?.setKeepsAwake(keepsAlive || !sceneInBackground && screenVisible && !localScreensAsleep)
    }

    private func beginBackgroundTask() {
        #if os(iOS)
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Screen Sharing session") { [self] in
            endBackgroundTask()
            closeIfSuspending()
        }
        #endif
    }

    private func endBackgroundTask() {
        #if os(iOS)
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
        #endif
    }

    fileprivate func closeIfSuspending() {
        #if os(iOS)
        guard sceneInBackground, phase.isActive, !reconnectsOnReturn, backgroundTask == .invalid,
            transferTask?.isRunning != true, sink?.keepsAppRunning != true
        else { return }
        reconnectsOnReturn = true
        teardown()
        phase = .reconnecting(attempt: 1)
        #endif
    }

    private var brokeInBackground: Bool {
        guard hasConnected, credentials != nil else { return false }
        switch phase {
        case .reconnecting:
            return reconnectAttempts > 0
        case .failed(let failure):
            return failure.kind == .connectionLost || failure.kind == .unreachable || failure.kind == .hostNotFound
        default:
            return false
        }
    }

    private func reconnectNow() {
        guard let credentials else { return }
        reconnectsOnReturn = false
        releaseAllKeys()
        teardown()
        reconnectAttempts = 0
        connect(with: credentials)
        phase = .reconnecting(attempt: 1)
    }

    private func restartHighPerformance() {
        guard usesHighPerformance, phase == .connected, !isVideo else { return }
        highPerformanceStartPending = true
        startHighPerformanceIfDisplayReady()
    }


    private static let reconnectLimit = 6

    fileprivate func handle(phase newPhase: SessionPhase) {
        var newPhase = newPhase
        var reconnecting = false
        if case .reconnecting = phase { reconnecting = true }
        if reconnecting, newPhase == .connecting || newPhase == .authenticating { return }
        if case .failed(let failure) = newPhase {
            let unreachable = failure.kind == .unreachable || failure.kind == .hostNotFound
            let retryable = failure.kind == .connectionLost || (reconnecting && unreachable)
            if retryable, hasConnected, reconnectAttempts < Self.reconnectLimit, let credentials {
                reconnectAttempts += 1
                let attempt = reconnectAttempts
                releaseAllKeys()
                teardown()
                phase = .reconnecting(attempt: attempt)
                reconnectTask?.cancel()
                let delay = TimeInterval(min(1 << (attempt - 1), 8))
                reconnectTask = Task { [weak self] in
                    try? await Task.sleep(seconds: delay)
                    guard let self, !Task.isCancelled, case .reconnecting = self.phase else { return }
                    self.connect(with: credentials)
                    self.phase = .reconnecting(attempt: attempt)
                }
                return
            }
            if reconnecting, unreachable {
                newPhase = .failed(SessionFailure(.connectionLost, message: failure.message))
            }
        }
        if newPhase == .connected, prepareCursorAssets() { return }
        phase = newPhase
        switch newPhase {
        case .connected:
            reconnectAttempts = 0
            if !hasConnected || passwordUpdate.isPending {
                hasConnected = true
                recordConnection()
            }
            if !info.fingerprint.isEmpty, info.fingerprint != trustedFingerprint {
                if let computerID {
                    save { try $0.setTrustedFingerprint(info.fingerprint, for: computerID) }
                }
                trustedFingerprint = info.fingerprint
            }
            pipeline.setExpectsVideo(usesHighPerformance)
            let sendsClipboard = sendChangedClipboard { [weak self] sent in
                guard let self, !sent else { return }
                session?.setClipboardSharing(clipboardSharing)
            }
            if !sendsClipboard {
                session?.setClipboardSharing(clipboardSharing)
            }
            if hdrEnabled {
                session?.setHDR(true)
            }
            let offersVirtualDisplay = capabilities.contains(.virtualDisplay)
            virtualDisplayRequests.connectionDidOpen(offersVirtualDisplay: offersVirtualDisplay)
            if !offersVirtualDisplay {
                awaitsVirtualDisplay = false
            }
            requestVirtualDisplay()
            startHighPerformanceIfDisplayReady()
            closeIfSuspending()
        case .failed(let failure):
            releaseAllKeys()
            if failure.kind == .authenticationFailed {
                switch credentials {
                case .account?, .vncPassword?: retry()
                default: break
                }
            }
        case .disconnected:
            releaseAllKeys()
        default:
            break
        }
    }

    private func prepareCursorAssets() -> Bool {
        guard AppSettings.shared.cursorStyle == .remote, info.dialect == .apple, let session else { return false }
        if let cursorAssets {
            session.setCursorAssets(cursorAssets)
            return false
        }
        guard !cursorAssetsAttempted, capabilities.contains(.fileTransfer), mode.controls else { return false }
        cursorAssetsAttempted = true
        phase = .preparingCursors
        Task.detached(priority: .userInitiated) { [weak self] in
            let assets = try? MacCursorAssets(session: session)
            await self?.cursorAssetsPrepared(assets)
        }
        return true
    }

    private func cursorAssetsPrepared(_ assets: MacCursorAssets?) {
        cursorAssets = assets
        if phase == .preparingCursors {
            handle(phase: .connected)
        } else if phase == .connected {
            _ = prepareCursorAssets()
        }
    }

    private func startHighPerformanceIfDisplayReady() {
        guard highPerformanceStartPending, usesHighPerformance, phase == .connected, virtualDisplayRequests.isIdle
        else { return }
        highPerformanceStartPending = false
        session?.startHighPerformance()
    }

    private func virtualDisplayDidSettle() {
        awaitsVirtualDisplay = false
        awaitsResolutionChange = false
        startHighPerformanceIfDisplayReady()
    }

    fileprivate func handle(screenWidth: Int, height: Int, video: Bool, resized: Bool) {
        virtualDisplayRequests.framebufferDidChange(width: screenWidth, height: height, resized: resized)
        if isVideo != video {
            isVideo = video
        }
    }

    private func handle(picture: ScreenPicture, width: Int, height: Int) {
        self.picture = picture
        if picture == .current {
            hasPicture = true
        }
        let size = CGSize(width: width, height: height)
        guard size != imageSize else { return }
        imageSize = size
        if pointerPosition == .zero || pointerPosition.x >= size.width || pointerPosition.y >= size.height {
            pointerPosition = CGPoint(x: size.width / 2, y: size.height / 2)
            pipeline.setCursorPosition(pointerPosition, visible: remotePointerVisible)
        }
        screen?.imageSizeDidChange()
    }

    fileprivate func handle(displays: [RemoteDisplay], selection: DisplaySelection) {
        self.displays = displays
        displaySelection = selection
        let screen = pipeline.screenSize
        virtualDisplayRequests.displaysDidChange(displays, framebufferWidth: screen.width, framebufferHeight: screen.height)
        guard restoresDisplay, let chosen = chosenDisplay else { return }
        restoresDisplay = false
        let available =
            switch chosen {
            case .all: displays.count > 1
            case .display(let id): displays.contains { $0.id == id && !$0.isVirtual }
            }
        if available, chosen != selection {
            displaySelection = chosen
            session?.selectDisplay(chosen)
        }
    }

    fileprivate func handle(mode: SessionMode) {
        self.mode = mode
        if mode == .observe {
            releaseAllKeys()
        }
        updateAssistCursor()
    }

    fileprivate func handle(capabilities: SessionCapabilities) {
        let gained = capabilities.subtracting(self.capabilities)
        self.capabilities = capabilities
        if mode == .observe, !capabilities.contains(.observe) {
            mode = .control
        }
        if gained.contains(.virtualDisplay), phase == .connected {
            requestVirtualDisplay()
        }
    }

    fileprivate func handleVideoFailure(_ failure: MediaFailure) {
        if usesHighPerformance, failure.kind == .silent || failure.kind == .network, sceneInBackground {
            restartsHighPerformanceOnReturn = true
            return
        }
        guard usesHighPerformance else { return }
        failHighPerformance(failure)
    }

    private func failHighPerformance(_ failure: MediaFailure) {
        releaseAllKeys()
        teardown()
        phase = .failed(SessionFailure(.highPerformance(failure.kind), message: failure.message))
    }

    func drawingFailed(_ error: any Error) {
        credentialPrompt = nil
        releaseAllKeys()
        teardown()
        phase = .failed(SessionFailure(.drawingFailed, message: error.localizedDescription))
    }

    fileprivate func handle(clipboard content: ClipboardContent) {
        guard content != lastRemoteClipboard else { return }
        lastRemoteClipboard = content
        guard !content.isEmpty else { return }
        ClipboardAccess.shared.write(content)
        syncedClipboardChangeCount = ClipboardAccess.changeCount
    }

    fileprivate func handle(cursorMovedTo point: CGPoint) {
        if isControlling, CACurrentMediaTime() - lastPointerMoveTime < 0.3 { return }
        pointerPosition = point
        pipeline.setCursorPosition(pointerPosition, visible: remotePointerVisible)
    }

    fileprivate func handle(keyboardSource source: String?, secureInput: Bool) {
        let current = keyboardSourceID
        if let current, let source, source != current {
            previousKeyboardSource = current
        }
        keyboardSourceID = source
        self.secureInput = secureInput
        rememberKeyboardSource(source)
        guard let source, let request = keyboardSourceRequest else { return }
        switch request.step {
        case .toggling where source == request.id:
            advanceKeyboardSource(request.id, to: nil)
        case .toggling where source != current:
            advanceKeyboardSource(request.id, to: .selecting)
        case .selecting where source == request.id:
            advanceKeyboardSource(request.id, to: .leaving)
        case .leaving where source != request.id:
            advanceKeyboardSource(request.id, to: .returning)
        case .returning where source == request.id:
            advanceKeyboardSource(request.id, to: nil)
        default:
            break
        }
    }

    fileprivate func handle(event: SessionEvent) {
        switch event {
        case .bell:
            #if os(iOS)
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            #else
            NSSound.beep()
            #endif
        case .pointerVisible(let visible):
            remotePointerVisible = visible
            pipeline.setCursorPosition(pointerPosition, visible: visible)
        case .clipboardUnreadable(let message):
            alert = AlertState(String(localized: "Couldn’t Receive the Clipboard"), error: MessageError(message))
        case .clipboardNotRestored(let message):
            guard !reportedClipboardNotRestored else { return }
            reportedClipboardNotRestored = true
            alert = AlertState(String(localized: "Couldn’t Receive the Clipboard"), error: MessageError(message))
        }
    }


    private static var deviceName: String {
        #if os(iOS)
        UIDevice.current.name
        #else
        Host.current().localizedName ?? ""
        #endif
    }

    private func uploadFile(_ url: URL, to remoteFolder: String, temporary: Bool) {
        let scoped = !temporary && url.startAccessingSecurityScopedResource()
        do {
            guard let session else { throw MessageError("not connected") }
            let id = try session.uploadFile(url, remoteFolder: remoteFolder)
            transfers.append(
                FileTransfer(
                    id: id, direction: .upload, name: url.lastPathComponent,
                    scopedURLs: scoped ? [url] : [], temporaryURLs: temporary ? [url] : []
                ))
        } catch {
            if scoped { url.stopAccessingSecurityScopedResource() }
            if temporary { try? FileManager.default.removeItem(at: url) }
            reportSendFailure(url.lastPathComponent, error: error)
        }
    }

    enum DropTarget {
        case desktop
        case pointer
    }

    private struct DropDelivery {
        var destination: String?
        var discards = false
        var waiting: [(url: URL, temporary: Bool)] = []
        var loading: Int

        var isSettled: Bool { destination != nil || discards }
    }

    func dropEntered(names: [String]) {
        guard canTransferFiles else { return }
        if capabilities.contains(.dragToRemote), isControlling, let id = try? session?.startDrop(names: names) {
            dragID = id
            dropTarget = .pointer
        } else {
            dropTarget = .desktop
        }
    }

    func dropMoved(to point: CGPoint) {
        guard dragID != nil else { return }
        movePointer(to: point)
        if !pointerButtons.contains(.primary) {
            press(.primary)
        }
    }

    func dropExited() {
        dropTarget = nil
        guard let dragID else { return }
        self.dragID = nil
        session?.cancelDrag(dragID)
        release(.primary)
    }

    private func endDrop(loading: Int) -> Int64? {
        dropTarget = nil
        guard let id = dragID else { return nil }
        dragID = nil
        release(.primary)
        // The Mac sends no destination when the window under the pointer refuses the drop, as
        // Finder's search results such as Recents do.
        discardPendingDrops()
        drops[id] = DropDelivery(loading: loading)
        return id
    }

    func drop(files: [URL], loading count: Int) -> @MainActor (URL?, String, (any Error)?) -> Void {
        let id = endDrop(loading: files.count + count)
        for url in files {
            droppedFileLoaded(url, temporary: false, name: url.lastPathComponent, error: nil, dragID: id)
        }
        return { [weak self] url, name, error in
            self?.droppedFileLoaded(url, temporary: true, name: name, error: error, dragID: id)
        }
    }

    func drop(_ providers: [NSItemProvider]) {
        let loaded = drop(files: [], loading: providers.count)
        let folder = AppDirectory.caches.appendingPathComponent("Drops", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for provider in providers {
            let type = provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .item) == true } ?? UTType.item.identifier
            let suggestedName = provider.suggestedName
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                guard let url else {
                    Task { @MainActor in loaded(nil, suggestedName ?? String(localized: "Item"), error) }
                    return
                }
                let destination = folder.appendingPathComponent(
                    suggestedName.map { name in
                        url.pathExtension.isEmpty || name.hasSuffix(url.pathExtension) ? name : "\(name).\(url.pathExtension)"
                    } ?? url.lastPathComponent)
                var copyError: (any Error)?
                do {
                    try FileManager.default.copyItem(at: url, to: destination)
                } catch {
                    copyError = error
                }
                Task { @MainActor in
                    loaded(copyError == nil ? destination : nil, destination.lastPathComponent, copyError)
                }
            }
        }
    }

    private func droppedFileLoaded(_ url: URL?, temporary: Bool, name: String, error: (any Error)?, dragID: Int64?) {
        guard let dragID, var delivery = drops[dragID] else {
            if let url {
                uploadFile(url, to: "~/Desktop", temporary: temporary)
            } else {
                reportSendFailure(name, error: error)
            }
            return
        }
        delivery.loading -= 1
        if let url {
            if delivery.discards {
                if temporary { try? FileManager.default.removeItem(at: url) }
            } else if let destination = delivery.destination {
                uploadFile(url, to: destination, temporary: temporary)
            } else {
                delivery.waiting.append((url, temporary))
            }
        } else if !delivery.discards {
            reportSendFailure(name, error: error)
        }
        drops[dragID] = delivery.loading == 0 && delivery.isSettled ? nil : delivery
    }

    func handleDropDestination(id: Int64, path: String) {
        guard var delivery = drops[id], !delivery.isSettled else { return }
        delivery.destination = path
        for file in delivery.waiting {
            uploadFile(file.url, to: path, temporary: file.temporary)
        }
        delivery.waiting = []
        drops[id] = delivery.loading == 0 ? nil : delivery
    }

    private func discardPendingDrops() {
        for (id, var delivery) in drops where !delivery.isSettled {
            delivery.discards = true
            for file in delivery.waiting where file.temporary {
                try? FileManager.default.removeItem(at: file.url)
            }
            delivery.waiting = []
            drops[id] = delivery.loading == 0 ? nil : delivery
        }
    }

    private struct PromisedFile {
        let folder: URL
        let progress: Progress
        let completion: (Result<URL, any Error>) -> Void
    }

    func downloadFile(_ remotePath: String, into folder: URL, progress: Progress, completion: @escaping (Result<URL, any Error>) -> Void) {
        let name = (remotePath as NSString).lastPathComponent
        let id: Int64
        do {
            guard let session else { throw MessageError("not connected") }
            id = try session.downloadFile(remotePath, localFolder: folder)
        } catch {
            alert = AlertState(String(localized: "Couldn’t Get “\(name)”"), error: error)
            completion(.failure(CocoaError(.fileWriteUnknown)))
            return
        }
        transfers.append(FileTransfer(id: id, direction: .download, name: name))
        progress.cancellationHandler = { [weak self] in
            Task { @MainActor in self?.session?.cancelTransfer(id) }
        }
        promisedFiles[id] = PromisedFile(folder: folder, progress: progress, completion: completion)
    }

    fileprivate func handleRemoteDrag(_ items: [RemoteDragItem], image: Data?) {
        remoteDragActive = !items.isEmpty
        // ScreensharingAgent ends a canceled remote drag with an empty 0x20 event, and sends nothing else for
        // the cancel request (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434,
        // UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3, sub_100012544 0x100012644..0x10001266c,
        // sub_100022378 0x100022434..0x100022448).
        if !remoteDragActive, holdsPointer {
            holdsPointer = false
            if isControlling { sendPointer(buttons: pointerButtons) }
        }
        guard remoteDragActive, capabilities.contains(.dragFromRemote), isControlling, pointerButtons.contains(.primary) else {
            remoteDragOffer = nil
            return
        }
        let icon = image.flatMap { CGImageSourceCreateWithData($0 as CFData, nil) }.flatMap { CGImageSourceCreateImageAtIndex($0, 0, nil) }
        remoteDragOffer = RemoteDragOffer(items: items, image: icon)
    }

    func takeRemoteDrag() -> RemoteDragOffer? {
        guard let offer = remoteDragOffer, isControlling else { return nil }
        remoteDragOffer = nil
        return offer
    }

    // ScreensharingAgent cancels the Mac's drag by activating Finder and then posting Escape
    // (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434, UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3,
    // sub_100022890 0x100022924..0x100022b94); a button released before that drops the items where the pointer is.
    func cancelRemoteDrag() {
        guard remoteDragActive, let session else {
            release(.primary)
            return
        }
        holdsPointer = true
        do {
            try session.cancelRemoteDrag()
        } catch {
            holdsPointer = false
            alert = AlertState(String(localized: "Couldn’t Cancel Drag"), error: error)
        }
        release(.primary)
    }

    private func abandonTransfers() {
        var interruption = FileTransferError(.connection)
        interruption.message = interruption.summary
        var abandoned: [FileTransfer] = []
        for index in transfers.indices where transfers[index].isActive {
            transfers[index].state = .failed(interruption)
            transfers[index].scopedURLs.forEach { $0.stopAccessingSecurityScopedResource() }
            transfers[index].scopedURLs = []
            transfers[index].temporaryURLs.forEach { try? FileManager.default.removeItem(at: $0) }
            transfers[index].temporaryURLs = []
            abandoned.append(transfers[index])
        }
        for promised in promisedFiles.values {
            promised.completion(.failure(interruption))
        }
        promisedFiles = [:]
        guard !isClosing, let transfer = abandoned.first else { return }
        if abandoned.count == 1 {
            alert = AlertState(
                transfer.direction == .upload
                    ? String(localized: "Couldn’t Send “\(transfer.name)”") : String(localized: "Couldn’t Get “\(transfer.name)”"),
                error: interruption)
        } else {
            alert = AlertState(String(localized: "Couldn’t Finish Transferring Files"), error: MessageError(abandoned.map(\.name).joined(separator: "\n")))
        }
    }

    private func updateTransferTask() {
        #if os(iOS)
        let running = transfers.filter(\.isActive)
        guard let current = running.first else {
            guard let transferTask else { return }
            transferTask.finish(success: transfers.allSatisfy { !transferTaskIDs.contains($0.id) || $0.state == .finished })
            self.transferTask = nil
            transferTaskIDs = []
            closeIfSuspending()
            return
        }
        let activity =
            current.direction == .upload
            ? String(localized: "Sending “\(current.name)”") : String(localized: "Receiving “\(current.name)”")
        if let transferTask {
            transferTask.update(title: activity, subtitle: title)
        } else {
            let task = ContinuedTask(kind: "transfer")
            task.onExpire = { [weak self] in
                guard let self else { return }
                for transfer in self.transfers where transfer.isActive {
                    self.session?.cancelTransfer(transfer.id)
                }
            }
            task.submit(title: activity, subtitle: title)
            transferTask = task
        }
        transferTaskIDs.formUnion(running.map(\.id))
        let batch = transfers.filter { transferTaskIDs.contains($0.id) }
        transferTask?.setProgress(completed: batch.reduce(0) { $0 + $1.transferred }, total: batch.reduce(0) { $0 + $1.total })
        #endif
    }

    private func update(_ id: Int64, _ body: (inout FileTransfer) -> Void) {
        guard let index = transfers.firstIndex(where: { $0.id == id }) else { return }
        body(&transfers[index])
    }

    fileprivate func handleTransferProgress(id: Int64, transferred: Int64, total: Int64) {
        if let progress = promisedFiles[id]?.progress {
            progress.totalUnitCount = total
            progress.completedUnitCount = transferred
        }
        update(id) { transfer in
            transfer.transferred = transferred
            transfer.total = total
        }
    }

    fileprivate func handleTransferFinished(id: Int64, name: String?, error: FileTransferError?) {
        update(id) { transfer in
            if let name, !name.isEmpty { transfer.name = name }
            switch error {
            case nil:
                transfer.state = .finished
                transfer.transferred = max(transfer.transferred, transfer.total)
            case let error? where error.kind == .cancelled:
                transfer.state = .cancelled
            case let error?:
                transfer.state = .failed(error)
            }
            transfer.scopedURLs.forEach { $0.stopAccessingSecurityScopedResource() }
            transfer.scopedURLs = []
            transfer.temporaryURLs.forEach { try? FileManager.default.removeItem(at: $0) }
            transfer.temporaryURLs = []
        }
        if let promised = promisedFiles.removeValue(forKey: id) {
            if let error {
                promised.completion(.failure(error))
            } else if let name, !name.isEmpty {
                promised.completion(.success(promised.folder.appendingPathComponent(name)))
            } else {
                promised.completion(.failure(CocoaError(.fileNoSuchFile)))
            }
        }
        guard let transfer = transfers.first(where: { $0.id == id }) else { return }
        if case .failed(let error) = transfer.state {
            alert = AlertState(
                transfer.direction == .upload
                    ? String(localized: "Couldn’t Send “\(transfer.name)”") : String(localized: "Couldn’t Get “\(transfer.name)”"),
                error: error)
        }
    }

    func answerSessionChoice(_ choice: SessionChoice) {
        guard case .choosingSession(let prompt) = phase else { return }
        phase = choice == .cancel ? .disconnected : .authenticating
        session?.answerSessionChoice(prompt, choice: choice)
    }


    var isControlling: Bool { mode.controls && phase == .connected }
    var canTransferFiles: Bool { capabilities.contains(.fileTransfer) && phase == .connected }

    var loginName: String? {
        guard let username = credentials?.username, !username.isEmpty else { return nil }
        return username
    }

    func setMode(_ newMode: SessionMode) {
        guard newMode != mode else { return }
        if newMode == .observe {
            releaseAllKeys()
        }
        mode = newMode
        session?.setMode(newMode)
        updateAssistCursor()
        rememberState()
    }

    func selectDisplay(_ selection: DisplaySelection) {
        displaySelection = selection
        chosenDisplay = selection
        session?.selectDisplay(selection)
        rememberState()
    }

    func setQuality(_ newQuality: SessionQuality) {
        guard newQuality != quality else { return }
        quality = newQuality
        rememberState()
        session?.setQuality(newQuality)
    }

    var networkSupportsHDR: Bool {
        networkConfiguration.chroma.pixelFormats(available: DecoderCapabilities.current.pixelFormats) & (1 << 5) != 0
    }

    var canUseHDR: Bool {
        usesHighPerformance && capabilities.contains(.highPerformance) && displaySupportsHDR && networkSupportsHDR
    }

    func setHDR(_ enabled: Bool) {
        guard !enabled || canUseHDR else { return }
        hdrEnabled = enabled
        session?.setHDR(enabled)
        rememberState()
    }

    fileprivate func videoDecodingFailed(_ error: any Error, hdr: Bool) {
        guard hdr, hdrEnabled else {
            failHighPerformance(MediaFailure(.undecodable, message: error.localizedDescription))
            return
        }
        hdrEnabled = false
        session?.setHDR(false)
        rememberState()
        alert = AlertState(String(localized: "This Device Can’t Show HDR"), error: error)
    }

    func setReceivesAudio(_ receivesAudio: Bool) {
        let changed = receivesAudio != self.receivesAudio
        self.receivesAudio = receivesAudio
        rememberState()
        if changed, capabilities.contains(.audio), phase == .connected {
            reconnectNow()
            return
        }
        session?.setReceivesAudio(receivesAudio)
    }

    func setSendsAudio(_ sendsAudio: Bool) {
        Task {
            if sendsAudio, !(await MicrophoneSender.requestPermission()) {
                alertMicrophoneDenied()
                return
            }
            guard sendsAudio != self.sendsAudio else { return }
            self.sendsAudio = sendsAudio
            rememberState()
            updateMicrophone()
            if phase == .connected {
                reconnectNow()
            }
        }
    }

    fileprivate func handle(microphone format: AudioFormat?) {
        microphoneFormat = format
        updateMicrophone()
    }

    private func updateMicrophone() {
        guard let format = microphoneFormat, sendsAudio, !sceneInBackground else {
            microphone?.stop()
            microphone = nil
            return
        }
        guard microphone?.format != format else { return }
        microphone?.stop()
        microphone = nil
        Task { [self] in
            let granted = await MicrophoneSender.requestPermission()
            guard microphone == nil, microphoneFormat == format, sendsAudio, !sceneInBackground else { return }
            guard granted else {
                sendsAudio = false
                rememberState()
                alertMicrophoneDenied()
                return
            }
            let session = self.session
            do {
                let sender = try MicrophoneSender(
                    format: format,
                    send: { data in session?.sendMicrophoneAudio(data) },
                    failed: { [weak self] sender, error in
                        DispatchQueue.main.async {
                            guard let self, self.microphone === sender else { return }
                            sender.stop()
                            self.microphone = nil
                            self.alert = AlertState(String(localized: "Can’t Use the Microphone"), error: error)
                        }
                    })
                sender.start()
                microphone = sender
            } catch {
                alert = AlertState(String(localized: "Can’t Use the Microphone"), error: error)
            }
        }
    }

    private func alertMicrophoneDenied() {
        #if os(iOS)
        alert = AlertState(
            title: String(localized: "Can’t Use the Microphone"),
            message: String(localized: "Microphone access is turned off in Settings."),
            actions: [
                AlertState.Action(label: String(localized: "Open Settings"), role: nil) {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                },
                AlertState.Action(label: String(localized: "OK"), role: .cancel) {},
            ]
        )
        #else
        alert = AlertState(
            title: String(localized: "Can’t Use the Microphone"),
            message: String(localized: "Microphone access is turned off in System Settings."),
            actions: [
                AlertState.Action(label: String(localized: "Open System Settings"), role: nil) {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone") {
                        NSWorkspace.shared.open(url)
                    }
                },
                AlertState.Action(label: String(localized: "OK"), role: .cancel) {},
            ]
        )
        #endif
    }

    func setClipboardSharing(_ enabled: Bool) {
        clipboardSharing = enabled
        rememberState()
        guard enabled, capabilities.contains(.clipboard), !readingClipboard else {
            session?.setClipboardSharing(enabled)
            return
        }
        let sendsClipboard = sendChangedClipboard { [weak self] _ in
            guard let self else { return }
            session?.setClipboardSharing(clipboardSharing)
        }
        guard !sendsClipboard else { return }
        readingClipboard = true
        Task {
            let explanation = await ClipboardAccess.shared.check()
            readingClipboard = false
            if let explanation {
                alert = explanation
            }
            session?.setClipboardSharing(clipboardSharing)
        }
    }

    func setResolutionScale(_ scale: Double) {
        guard scale != resolutionScale else { return }
        resolutionScale = scale
        rememberState()
        requestVirtualDisplay(holdingPicture: true)
    }

    var wantsVirtualDisplay: Bool {
        usesHighPerformance || route.remoteProtocol == .rdp
    }

    var pictureMatchesWindow: Bool {
        wantsVirtualDisplay && capabilities.contains(.virtualDisplay)
    }

    #if os(iOS)
    var preferredOrientation: UIInterfaceOrientationMask? {
        guard credentials != nil, credentialPrompt == nil else { return nil }
        let followsWindow = wantsVirtualDisplay && (capabilities.isEmpty || capabilities.contains(.virtualDisplay))
        if !followsWindow, imageSize.width > 0, imageSize.height > 0 {
            return imageSize.width < imageSize.height ? .portrait : .landscape
        }
        return .landscape
    }
    #endif

    func displayAreaDidChange(_ area: CGSize, scale: CGFloat, screen: CGSize) {
        guard area.width > 0, area.height > 0 else { return }
        displayArea = area
        displayScale = scale
        screenSize = screen
        requestVirtualDisplay(holdingPicture: true)
    }

    func windowSizeDidChange(_ size: CGSize) {
        guard size != windowSize else { return }
        windowSize = size
        rememberState()
    }

    func screenRefreshRateDidChange(_ rate: Int) {
        guard rate > 0, rate != screenRefreshRate else { return }
        screenRefreshRate = rate
        session?.setUpdateRate(rate)
    }

    private func requestVirtualDisplay(holdingPicture: Bool = false) {
        guard virtualDisplayRequests.isOpening || phase == .connected && capabilities.contains(.virtualDisplay),
            wantsVirtualDisplay,
            let mode = VirtualDisplayMode(matching: displayArea, screenScale: displayScale, resolutionScale: resolutionScale)
        else { return }
        virtualDisplayRequests.request(mode)
        if holdingPicture, phase == .connected, !virtualDisplayRequests.isIdle {
            awaitsResolutionChange = true
        }
    }

    func sendClipboardToMac() {
        let content = ClipboardAccess.readClipboard().content
        guard !content.isEmpty else { return }
        sendClipboard(content)
    }

    @discardableResult
    func sendChangedClipboard(completion: @escaping (Bool) -> Void = { _ in }) -> Bool {
        let changeCount = ClipboardAccess.changeCount
        guard clipboardSharing, phase == .connected, capabilities.contains(.clipboard), !readingClipboard,
            changeCount != syncedClipboardChangeCount
        else { return false }
        syncedClipboardChangeCount = changeCount
        readingClipboard = true
        Task {
            let (content, explanation) = await ClipboardAccess.shared.read()
            readingClipboard = false
            if let explanation {
                alert = explanation
            }
            completion(!content.isEmpty && phase == .connected && sendClipboard(content, changeCount: changeCount))
        }
        return true
    }

    func pasteFromDevice() {
        guard isControlling else { return }
        commandTapPending = false
        let content = ClipboardAccess.readClipboard().content
        guard !content.isEmpty, sendClipboard(content, pasting: true) else { return }
        textCursorMayHaveMoved()
    }

    @discardableResult
    private func sendClipboard(_ content: ClipboardContent, changeCount: Int = ClipboardAccess.changeCount, pasting: Bool = false) -> Bool {
        do {
            guard let session else { throw MessageError("not connected") }
            try session.sendClipboard(content, pasting: pasting)
            syncedClipboardChangeCount = changeCount
            lastRemoteClipboard = content
            return true
        } catch {
            alert = AlertState(String(localized: "Couldn’t Send the Clipboard"), error: error)
            return false
        }
    }

    func getClipboardFromMac() {
        lastRemoteClipboard = nil
        do {
            guard let session else { throw MessageError("not connected") }
            try session.requestClipboard()
        } catch {
            alert = AlertState(String(localized: "Couldn’t Receive the Clipboard"), error: error)
        }
    }

    fileprivate func audioFailed(_ error: any Error) {
        alert = AlertState(info.platform != .pc ? String(localized: "Can’t Play the Mac’s Sound") : String(localized: "Can’t Play the Computer’s Sound"), error: error)
    }

    private func sendChord(_ chord: KeyChord) {
        guard isControlling else { return }
        textCursorMayHaveMoved()
        tap(chord.key, modifiers: Set(chord.modifiers))
    }

    func setScale(_ scale: ScaleMode) {
        screen?.applyScale(scale)
    }

    func setKeyboardVisible(_ visible: Bool) {
        #if os(iOS)
        screen?.setKeyboardVisible(visible)
        #endif
    }

    func toggleKeyboard() {
        setKeyboardVisible(!isKeyboardVisible)
    }

    func keyboardVisibilityDidChange(_ visible: Bool) {
        isKeyboardVisible = visible
    }

    func setUsesHardwareKeyboard(_ uses: Bool) {
        usesHardwareKeyboard = uses
    }

    func toggleToolbar() {
        withAnimation(.smoothCompat(duration: 0.25)) { isToolbarVisible.toggle() }
    }

    var canLockPointer: Bool {
        #if os(iOS)
        mode.controls && isFullScreen
        #else
        mode.controls
        #endif
    }

    func setPointerLock(_ on: Bool) {
        guard on != isPointerLockOn else { return }
        isPointerLockOn = on
        pointerLockHasLocked = false
        rememberState()
        if on, isToolbarVisible { toggleToolbar() }
    }

    func pointerLockAvailabilityDidChange(_ available: Bool) {
        if !available { pointerLockHasLocked = false }
    }

    func setNativeTouch(_ on: Bool) {
        guard on != isNativeTouchOn else { return }
        isNativeTouchOn = on
        rememberState()
    }

    func setZoomMode(_ on: Bool) {
        guard on != isZoomModeOn else { return }
        isZoomModeOn = on
        if on, isToolbarVisible { toggleToolbar() }
    }

    var pointerLockCanResume: Bool {
        guard pointerLockHasLocked, !isToolbarDocked else { return true }
        guard let location = lastSystemPointerLocation else { return false }
        return [toolbarHandleFrame, toolbarBarFrame].contains { $0?.insetBy(dx: -16, dy: -16).contains(location) == true }
    }

    private func reportSendFailure(_ name: String, error: (any Error)?) {
        alert = AlertState(String(localized: "Couldn’t Send “\(name)”"), error: error)
    }


    func movePointer(to point: CGPoint) {
        let clamped = clampToImage(point)
        pointerPosition = clamped
        lastPointerMoveTime = CACurrentMediaTime()
        pipeline.setCursorPosition(clamped, visible: true)
        guard isControlling else {
            updateAssistCursor()
            return
        }
        sendPointer(buttons: pointerButtons)
    }

    var touchesNatively: Bool {
        isNativeTouchOn && capabilities.contains(.touch) && isControlling
    }

    func sendTouches(_ touches: [RemoteTouch]) {
        guard !touches.isEmpty else { return }
        for touch in touches where touch.phase == .began {
            beginPointerInput()
            nativeTouchIDs.insert(touch.id)
        }
        session?.sendTouches(
            touches.map { touch in
                var touch = touch
                touch.location = clampToImage(touch.location)
                return touch
            })
        for touch in touches where touch.phase == .ended || touch.phase == .cancelled {
            nativeTouchIDs.remove(touch.id)
        }
        endPointerInput()
    }

    func pointerLeftScreen() {
        guard assistCursorShown else { return }
        assistCursorShown = false
        session?.hideAssistCursor()
    }

    private func updateAssistCursor() {
        let wanted = capabilities.contains(.assistCursor) && !mode.controls && phase == .connected
        if wanted {
            assistCursorShown = true
            session?.setAssistCursor(x: Int(pointerPosition.x), y: Int(pointerPosition.y), pressed: !pointerButtons.isEmpty)
        } else {
            pointerLeftScreen()
        }
    }

    func setButtons(_ buttons: PointerButtons) {
        if !buttons.contains(.primary) { remoteDragOffer = nil }
        pointerButtons = buttons
        guard isControlling else {
            updateAssistCursor()
            return
        }
        sendPointer(buttons: buttons)
    }

    private func sendPointer(buttons: PointerButtons) {
        guard !holdsPointer else { return }
        session?.sendPointer(
            x: Int(pointerPosition.x.rounded(.down)), y: Int(pointerPosition.y.rounded(.down)), buttons: buttons, clickCount: clickCount)
    }

    private static let multiClickInterval: TimeInterval = 0.5
    private static let multiClickDistance: CGFloat = 4

    func press(_ button: PointerButtons, clickCount count: Int? = nil) {
        let now = CACurrentMediaTime()
        if let count {
            clickCount = count
        } else if let lastPress, lastPress.buttons == button, now - lastPress.time <= Self.multiClickInterval,
            hypot(pointerPosition.x - lastPress.position.x, pointerPosition.y - lastPress.position.y) <= Self.multiClickDistance
        {
            clickCount += 1
        } else {
            clickCount = 1
        }
        lastPress = (button, pointerPosition, now)
        beginPointerInput()
        setButtons(pointerButtons.union(button))
    }

    func release(_ button: PointerButtons) {
        setButtons(pointerButtons.subtracting(button))
        endPointerInput()
    }

    private func beginPointerInput() {
        guard isControlling else { return }
        textCursorMayHaveMoved()
        guard pointerButtons.isEmpty, nativeTouchIDs.isEmpty else { return }
        if info.platform == .pc, pressedKeys.values.contains(where: { ModifierKey(keysym: $0.keysym) == .command }) {
            commandTapPending = false
            var modifiers = Set(pressedKeys.values.compactMap { ModifierKey(keysym: $0.keysym) })
            modifiers.remove(.command)
            modifiers.insert(.control)
            setSentModifiers(modifiers)
        }
        pointerModifiers = stickyModifiers.subtracting(lockedModifiers).subtracting(pressedModifiers)
        stickyModifiers = []
        for modifier in ModifierKey.allCases where pointerModifiers.contains(modifier) { send(modifier.macKey, down: true) }
    }

    private func endPointerInput() {
        guard pointerButtons.isEmpty, nativeTouchIDs.isEmpty, !pointerModifiers.isEmpty else { return }
        for modifier in ModifierKey.allCases.reversed() where pointerModifiers.contains(modifier) { send(modifier.macKey, down: false) }
        pointerModifiers = []
    }

    func setPressedButtons(_ buttons: PointerButtons) {
        let released = pointerButtons.subtracting(buttons)
        let pressed = buttons.subtracting(pointerButtons)
        if !released.isEmpty { release(released) }
        if !pressed.isEmpty { press(pressed) }
    }

    func click(_ button: PointerButtons, count: Int = 1) {
        for _ in 0..<count {
            press(button)
            release(button)
        }
    }

    var sendsGesturesToMac: Bool {
        capabilities.contains(.gestures) && isControlling
    }

    func magnify(by magnification: Double, phase: GesturePhase) {
        guard sendsGesturesToMac else { return }
        session?.sendMagnify(x: Int(pointerPosition.x), y: Int(pointerPosition.y), magnification: magnification, phase: phase)
    }

    func rotate(degrees: Double, phase: GesturePhase) {
        guard sendsGesturesToMac else { return }
        session?.sendRotate(x: Int(pointerPosition.x), y: Int(pointerPosition.y), degrees: degrees, phase: phase)
    }

    func scroll(byPixels delta: CGPoint, phase: GesturePhase) {
        guard sendsGesturesToMac else { return }
        isScrollGliding = false
        session?.sendScrollPhase(
            x: Int(pointerPosition.x), y: Int(pointerPosition.y), deltaX: Double(delta.x), deltaY: Double(delta.y), phase: phase)
    }

    func startScrollMomentum(pixelsPerSecond velocity: CGPoint) {
        guard sendsGesturesToMac, hypot(velocity.x, velocity.y) > 50 else { return }
        isScrollGliding = true
        session?.startScrollMomentum(
            x: Int(pointerPosition.x), y: Int(pointerPosition.y), velocityX: Double(velocity.x), velocityY: Double(velocity.y))
    }

    func stopScrollMomentum() {
        guard isScrollGliding else { return }
        isScrollGliding = false
        session?.stopScrollMomentum()
    }

    func canPerform(_ command: SystemCommand) -> Bool {
        capabilities.contains(command.capability) || info.platform != .pc && command.fallbackChord != nil
    }

    func perform(_ command: SystemCommand) {
        guard isControlling else { return }
        textCursorMayHaveMoved()
        if capabilities.contains(command.capability) {
            session?.sendSystemCommand(command)
        } else if info.platform != .pc, let chord = command.fallbackChord {
            sendChord(chord)
        }
    }

    func scroll(byPixels delta: CGPoint) {
        guard isControlling else { return }
        session?.sendScroll(x: Int(pointerPosition.x), y: Int(pointerPosition.y), deltaX: Double(delta.x), deltaY: Double(delta.y))
    }

    private func clampToImage(_ point: CGPoint) -> CGPoint {
        let size = imageSize
        guard size.width > 0, size.height > 0 else { return point }
        return CGPoint(x: min(max(point.x, 0), size.width - 1), y: min(max(point.y, 0), size.height - 1))
    }


    #if os(iOS)
    func handlePress(_ key: UIKey, down: Bool) -> Bool {
        if down { setUsesHardwareKeyboard(true) }
        let usage = key.keyCode
        if usage == .keyboardCapsLock {
            guard isControlling, capabilities.contains(.keyCodes) else { return false }
            if down { capsLockPressed() } else { capsLockReleased() }
            return true
        }
        var shortcut: KeyShortcut?
        if down, isControlling {
            shortcut = KeyShortcut(key: key)
            if let shortcut, KeyShortcuts.shared.action(using: shortcut) != nil {
                commandTapPending = false
                return false
            }
            keyboardType = keyboardType.learning(from: usage)
            if let shortcut, let mapping = KeyShortcuts.shared.mapping(triggeredBy: shortcut, for: mappingTarget) {
                performMapping(mapping.steps, held: heldModifiers(from: key.modifierFlags))
                return true
            }
        }
        return handleKey(id: usage.rawValue, down: down, held: heldModifiers(from: key.modifierFlags), shortcut: shortcut, repeats: true) {
            macKey(for: key)
        }
    }
    #else
    func handleKeyEvent(_ event: NSEvent) -> Bool {
        let id = Int(event.keyCode)
        switch event.type {
        case .flagsChanged:
            if event.keyCode == MacKey.capsLock.keyCode {
                if isControlling {
                    setCapsLock(event.modifierFlags.contains(.capsLock))
                }
                return false
            }
            guard let key = Self.modifierKeys[event.keyCode] else { return false }
            let flags = event.modifierFlags
            let down = flags.rawValue & Self.deviceModifierMask == 0 ? flags.contains(key.flag) : flags.rawValue & key.deviceMask != 0
            inferredModifierKeys.remove(id)
            let handled = handleKey(id: id, down: down, held: [], shortcut: nil, repeats: false) {
                MacKey(keyCode: event.keyCode, keysym: Keysym.special(forKeyCode: event.keyCode) ?? 0)
            }
            syncModifiers(flags)
            return handled
        case .keyDown where event.isARepeat:
            guard let pressed = pressedKeys[id] else { return isControlling }
            send(pressed, down: true)
            return true
        case .keyDown, .keyUp:
            if event.type == .keyDown, isControlling {
                keyboardType = .current
            }
            syncModifiers(event.modifierFlags)
            let shortcut = event.type == .keyDown && isControlling ? KeyShortcut(event: event) : nil
            if let shortcut, let mapping = KeyShortcuts.shared.mapping(triggeredBy: shortcut, for: mappingTarget) {
                performMapping(mapping.steps, held: heldModifiers(from: event.modifierFlags))
                return true
            }
            return handleKey(id: id, down: event.type == .keyDown, held: heldModifiers(from: event.modifierFlags), shortcut: shortcut, repeats: false) {
                MacKey(keyCode: event.keyCode, keysym: keysym(for: event) ?? 0)
            }
        default:
            return false
        }
    }

    func keyDownHandledLocally() {
        commandTapPending = false
    }

    func syncCapsLock(_ engaged: Bool) {
        guard isControlling else { return }
        setCapsLock(engaged)
    }

    func syncModifiers(_ flags: NSEvent.ModifierFlags) {
        inferredModifierKeys.formIntersection(pressedKeys.keys)
        guard isControlling else { return }
        setCapsLock(flags.contains(.capsLock))
        let held = heldModifiers(from: flags)
        let latched = lockedModifiers.union(pressedModifiers)
        for modifier in ModifierKey.allCases where !latched.contains(modifier) {
            let sent = pressedKeys.keys.filter { id in UInt16(exactly: id).flatMap { Self.modifierKeys[$0]?.modifier } == modifier }
            if !held.contains(modifier) {
                for id in sent where inferredModifierKeys.contains(id) {
                    inferredModifierKeys.remove(id)
                    if let key = pressedKeys.removeValue(forKey: id) {
                        release(key)
                    }
                }
            } else if sent.isEmpty, let keyCode = modifier.macKey.keyCode {
                inferredModifierKeys.insert(Int(keyCode))
                pressModifier(modifier.macKey, id: Int(keyCode), tappable: false)
            }
        }
    }

    private static let deviceModifierMask: UInt = 0x207f

    private static let modifierKeys: [UInt16: (modifier: ModifierKey?, flag: NSEvent.ModifierFlags, deviceMask: UInt)] = [
        59: (.control, .control, 0x01), 62: (.control, .control, 0x2000),
        56: (.shift, .shift, 0x02), 60: (.shift, .shift, 0x04),
        55: (.command, .command, 0x08), 54: (.command, .command, 0x10),
        58: (.option, .option, 0x20), 61: (.option, .option, 0x40),
        63: (nil, .function, NSEvent.ModifierFlags.function.rawValue),
    ]
    #endif

    private func handleKey(id: Int, down: Bool, held: Set<ModifierKey>, shortcut: KeyShortcut?, repeats: Bool, mapping: () -> MacKey?) -> Bool {
        if !down {
            guard let pressed = pressedKeys.removeValue(forKey: id) else {
                return isControlling && mapping() != nil
            }
            release(pressed)
            return true
        }
        guard isControlling, let mapped = mapping() else { return false }
        if let modifier = ModifierKey(keysym: mapped.keysym), lockedModifiers.contains(modifier), mapped.keysym == modifier.keysym {
            return true
        }
        if !mapped.isModifier { textCursorMayHaveMoved() }
        if secureInput, !Keysym.typesInSecureInput(mapped.keysym) {
            showSecureInputNotice()
            return true
        }
        if let previous = pressedKeys.removeValue(forKey: id) {
            release(previous)
        }
        if mapped.isModifier {
            pressModifier(mapped, id: id, tappable: true)
            return true
        }
        var key = mapped
        if info.platform == .pc {
            commandTapPending = false
            var modifiers = held
            if let shortcut, let output = KeyTranslations.output(for: shortcut), let chord = output.chord(for: .pc) {
                modifiers = Set(chord.modifiers)
                if output.key != shortcut.key { key = chord.key }
            }
            setSentModifiers(modifiers)
        }
        let sticky = stickyModifiers.subtracting(held)
        if !sticky.isEmpty {
            stickyModifiers = []
            tap(key, modifiers: sticky)
            return true
        }
        pressedKeys[id] = key
        send(key, down: true)
        if repeats {
            startRepeat(key, id: id)
        }
        return true
    }

    var mappingTarget: MappingTarget {
        info.platform == .pc ? .pc : .apple
    }

    private func pressModifier(_ key: MacKey, id: Int, tappable: Bool) {
        pressedKeys[id] = key
        guard info.platform == .pc, let modifier = ModifierKey(keysym: key.keysym) else {
            send(key, down: true)
            return
        }
        commandTapPending = tappable && modifier == .command
        guard modifier != .command, sentModifiers[modifier] == nil else { return }
        sentModifiers[modifier] = key
        send(key, down: true)
    }

    private func release(_ key: MacKey) {
        guard info.platform == .pc, let modifier = ModifierKey(keysym: key.keysym) else {
            send(key, down: false)
            return
        }
        if modifier == .command, commandTapPending {
            send(key, down: true)
            send(key, down: false)
        }
        commandTapPending = false
        let held = Set(pressedKeys.values.compactMap { ModifierKey(keysym: $0.keysym) })
        for (kind, sent) in sentModifiers where held.isEmpty || kind == modifier && !held.contains(kind) {
            sentModifiers[kind] = nil
            send(sent, down: false)
        }
    }

    private func setSentModifiers(_ modifiers: Set<ModifierKey>) {
        for modifier in ModifierKey.allCases where modifiers.contains(modifier) && sentModifiers[modifier] == nil {
            let key = pressedKeys.values.first { ModifierKey(keysym: $0.keysym) == modifier } ?? modifier.macKey
            sentModifiers[modifier] = key
            send(key, down: true)
        }
        for (modifier, key) in sentModifiers where !modifiers.contains(modifier) {
            sentModifiers[modifier] = nil
            send(key, down: false)
        }
    }

    func replaceText(deleting count: Int, with text: String) -> Bool {
        guard isControlling else { return false }
        let asText = lockedModifiers.union(pressedModifiers).union(stickyModifiers).isSubset(of: [.shift])
        for _ in 0..<count { tapKey(.backspace) }
        let typed = insertText(text)
        return asText && typed
    }

    private func insertText(_ text: String) -> Bool {
        var typed = true
        var remaining = Substring(text)
        if !lockedModifiers.union(pressedModifiers).isSubset(of: [.shift]) {
            for character in remaining { typed = typeCharacter(character) && typed }
            return typed
        }
        if !stickyModifiers.isEmpty, let first = remaining.popFirst() {
            typed = typeCharacter(first)
        }
        return typeUnmodified(remaining) && typed
    }

    private func typeUnmodified(_ text: Substring) -> Bool {
        var typed = true
        var run = ""
        for character in text {
            let key: MacKey? =
                switch character {
                case "\n", "\r", "\r\n": .returnKey
                case "\t": .tab
                default: nil
                }
            guard let key else {
                run.append(character)
                continue
            }
            typed = typeText(run) && typed
            run = ""
            tap(key, modifiers: [])
        }
        return typeText(run) && typed
    }

    private func typeText(_ text: String) -> Bool {
        guard !text.isEmpty else { return true }
        if secureInput {
            let typed = text.filter(\.isASCII)
            if !typed.isEmpty { try? session?.sendText(typed) }
            guard typed.count == text.count else {
                showSecureInputNotice()
                return false
            }
            return true
        }
        // ScreensharingAgent's KeyMapDictionary_ConvertKeysym recognizes Unicode keysyms only with high word 0x0100,
        // and its PostKeyEvent posts one UTF-16 unit (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434,
        // UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3, sub_100035ba0 0x100035c7c..0x100035c8c,
        // sub_100027410 0x100027890..0x100027898).
        let inBasicPlane = text.unicodeScalars.allSatisfy { $0.value <= 0xffff }
        guard info.dialect == .apple else {
            let typable = capabilities.contains(.unicodeText) || inBasicPlane
            let typed = typable ? text : String(String.UnicodeScalarView(text.unicodeScalars.filter { $0.value <= 0xffff }))
            do {
                if !typed.isEmpty { try session?.sendText(typed) }
            } catch {
                alert = AlertState(String(localized: "Some Characters Can’t Be Typed on “\(title)”"), error: error)
                return false
            }
            if !typable {
                alert = AlertState(title: String(localized: "Some Characters Can’t Be Typed on “\(title)”"))
            }
            return typable
        }
        let macComposes = keyboardSource?.isInputMethod ?? capabilities.contains(.keyboardSource)
        if inBasicPlane, !macComposes {
            try? session?.sendText(text)
        } else {
            do {
                try session?.pasteText(text)
            } catch {
                alert = AlertState(String(localized: "Some Characters Can’t Be Typed on “\(title)”"), error: error)
                return false
            }
        }
        return true
    }

    private func showSecureInputNotice() {
        alert = AlertState(title: String(localized: "Password Fields Take Only English Characters"))
    }

    private func typeCharacter(_ character: Character) -> Bool {
        let held = lockedModifiers.union(pressedModifiers)
        let sticky = stickyModifiers
        let modifiers = held.union(sticky)
        var key: MacKey
        var shift = false
        switch character {
        case "\n", "\r", "\r\n":
            key = .returnKey
        case "\t":
            key = .tab
        default:
            if modifiers.contains(.option), let keystroke = MacKey.usKeystroke(for: character) {
                (key, shift) = keystroke
            } else {
                var text = String(character)
                if modifiers.contains(.command) || modifiers.contains(.control) {
                    text = modifiers.contains(.shift) ? text.uppercased() : text.lowercased()
                }
                guard let scalar = text.unicodeScalars.first else { return false }
                key = MacKey(keysym: Keysym.character(scalar))
            }
        }
        stickyModifiers = []
        if secureInput, !Keysym.typesInSecureInput(key.keysym) {
            showSecureInputNotice()
            return false
        }
        var pressing = sticky
        if shift { pressing.insert(.shift) }
        tap(key, modifiers: pressing.subtracting(held))
        return true
    }

    func typeKey(_ key: MacKey) {
        guard isControlling else { return }
        textCursorMayHaveMoved()
        tapKey(key)
    }

    func perform(_ input: QuickInput) {
        guard isControlling else { return }
        performMapping(input.steps(for: mappingTarget), held: [])
    }

    private func tapKey(_ key: MacKey) {
        let sticky = stickyModifiers
        stickyModifiers = []
        tap(key, modifiers: sticky)
    }

    private func textCursorMayHaveMoved() {
        screen?.forgetTypedText()
    }

    private func performMapping(_ steps: [KeyMapping.Step], held: Set<ModifierKey>) {
        textCursorMayHaveMoved()
        var latched = lockedModifiers.union(pressedModifiers)
        var down = Set<MacKey>()
        if info.platform == .pc {
            commandTapPending = false
            setSentModifiers([])
        } else {
            latched.formUnion(held)
            down = Set(pressedKeys.values.filter { ModifierKey(keysym: $0.keysym) != nil })
        }
        for modifier in lockedModifiers.union(pressedModifiers) { down.insert(modifier.macKey) }
        var released: [MacKey] = []
        for step in steps {
            let chords: [KeyChord] =
                switch step {
                case .key(let combo): combo.shortcuts.compactMap { $0.chord(for: mappingTarget) }
                case .text: []
                }
            let wanted = Set(chords.first?.modifiers ?? [])
            for key in down {
                guard let modifier = ModifierKey(keysym: key.keysym), !wanted.contains(modifier) else { continue }
                send(key, down: false)
                down.remove(key)
                latched.remove(modifier)
                released.append(key)
            }
            if !chords.isEmpty {
                let held = ModifierKey.allCases.filter { wanted.subtracting(latched).contains($0) }.map(\.macKey)
                for key in held + chords.map(\.key) { send(key, down: true) }
                for key in (held + chords.map(\.key)).reversed() { send(key, down: false) }
            } else if case .text(let text) = step {
                _ = typeUnmodified(Substring(text))
            } else if case .key(let combo) = step, let command = combo.command {
                perform(command)
            }
        }
        for key in released { send(key, down: true) }
    }

    private func tap(_ key: MacKey, modifiers: Set<ModifierKey>) {
        let held = ModifierKey.allCases.filter { modifiers.contains($0) }.map(\.macKey)
        for modifier in held { send(modifier, down: true) }
        send(key, down: true)
        send(key, down: false)
        for modifier in held.reversed() { send(modifier, down: false) }
    }

    private func setCapsLock(_ engaged: Bool) {
        guard engaged != capsLockEngaged, capabilities.contains(.keyCodes) else { return }
        capsLockEngaged = engaged
        send(.capsLock, down: engaged)
    }

    private static let capsLockHoldDuration: TimeInterval = 0.5


    private func capsLockPressed() {
        if capsLockPressedAt != nil {
            capsLockActsOnPress = true
        }
        capsLockPressedAt = ProcessInfo.processInfo.systemUptime
        if capsLockActsOnPress {
            capsLockTapped()
        }
    }

    private func capsLockReleased() {
        guard let pressedAt = capsLockPressedAt else { return }
        capsLockPressedAt = nil
        if capsLockActsOnPress { return }
        if ProcessInfo.processInfo.systemUptime - pressedAt >= Self.capsLockHoldDuration {
            setCapsLock(!capsLockEngaged)
        } else {
            capsLockTapped()
        }
    }

    private func capsLockTapped() {
        if capsLockEngaged {
            setCapsLock(false)
        } else if !secureInput, macHasOtherKeyboardSource {
            switchKeyboardSource()
        } else {
            setCapsLock(true)
        }
    }

    var keyboardSource: MacInputSource? {
        keyboardSourceID.map(MacInputSource.init(id:))
    }

    private var macHasOtherKeyboardSource: Bool {
        guard let current = keyboardSource else { return false }
        return current.isInputMethod || keyboardSources.contains { $0.id != current.id }
    }


    func switchKeyboardSource() {
        guard isControlling else { return }
        sendChord(.previousInputSource)
    }

    func selectKeyboardSource(_ source: MacInputSource) {
        guard isControlling, !secureInput else { return }
        guard keyboardSourceRequest == nil else {
            queuedKeyboardSource = source
            return
        }
        guard source.id != keyboardSourceID else { return }
        advanceKeyboardSource(source.id, to: previousKeyboardSource == nil || previousKeyboardSource == source.id ? .toggling : .selecting)
    }

    private func advanceKeyboardSource(_ id: String, to step: KeyboardSourceRequest.Step?) {
        keyboardSourceTimeout?.cancel()
        guard let step else {
            keyboardSourceRequest = nil
            if let queued = queuedKeyboardSource {
                queuedKeyboardSource = nil
                selectKeyboardSource(queued)
            }
            return
        }
        if step == .selecting {
            session?.selectKeyboardSource(id)
        } else {
            sendChord(.previousInputSource)
        }
        keyboardSourceRequest = KeyboardSourceRequest(id: id, step: step)
        keyboardSourceTimeout = Task { [weak self] in
            try? await Task.sleep(seconds: 0.5)
            guard !Task.isCancelled, let self else { return }
            switch step {
            case .toggling:
                self.advanceKeyboardSource(id, to: .selecting)
            case .selecting:
                self.advanceKeyboardSource(id, to: .leaving)
            case .leaving:
                self.advanceKeyboardSource(id, to: nil)
            case .returning:
                self.sendChord(.previousInputSource)
                self.advanceKeyboardSource(id, to: nil)
            }
        }
    }

    #if os(macOS)
    func followKeyboardSource(_ id: String) {
        let local = MacInputSource(id: id)
        guard !secureInput, let current = keyboardSource, local != current else { return }
        if keyboardSources.contains(local) {
            selectKeyboardSource(local)
        } else if local.isInputMethod != current.isInputMethod {
            if let match = keyboardSources.last(where: { $0.isInputMethod == local.isInputMethod }) {
                selectKeyboardSource(match)
            } else {
                switchKeyboardSource()
            }
        }
    }
    #endif

    private func rememberKeyboardSource(_ id: String?) {
        guard let id, !keyboardSources.contains(where: { $0.id == id }) else { return }
        keyboardSources = Array((keyboardSources + [MacInputSource(id: id)]).suffix(8))
        saveKeyboardSources()
    }

    private func saveKeyboardSources() {
        guard let computerID, !keyboardSources.isEmpty else { return }
        save { try $0.setKeyboardSources(keyboardSources.map(\.id), for: computerID) }
    }

    func toggleSticky(_ modifier: ModifierKey) {
        if lockedModifiers.contains(modifier) {
            setModifier(modifier, locked: false)
        } else if stickyModifiers.contains(modifier) {
            stickyModifiers.remove(modifier)
        } else if isControlling {
            stickyModifiers.insert(modifier)
        }
    }

    func setModifier(_ modifier: ModifierKey, locked: Bool) {
        guard locked != lockedModifiers.contains(modifier), !locked || isControlling else { return }
        stickyModifiers.remove(modifier)
        if locked {
            lockedModifiers.insert(modifier)
        } else {
            lockedModifiers.remove(modifier)
        }
        if !pressedModifiers.contains(modifier) {
            send(modifier.macKey, down: locked)
        }
    }

    func setModifier(_ modifier: ModifierKey, pressed: Bool) {
        guard pressed != pressedModifiers.contains(modifier), !pressed || isControlling else { return }
        stickyModifiers.remove(modifier)
        if pressed {
            pressedModifiers.insert(modifier)
        } else {
            pressedModifiers.remove(modifier)
        }
        if !lockedModifiers.contains(modifier) {
            send(modifier.macKey, down: pressed)
        }
    }

    func releaseAllKeys() {
        stopRepeat()
        let keys = pressedKeys.values.sorted { lhs, rhs in !lhs.isModifier && rhs.isModifier }
        pressedKeys.removeAll()
        for key in keys where info.platform != .pc || ModifierKey(keysym: key.keysym) == nil {
            send(key, down: false)
        }
        for key in sentModifiers.values {
            send(key, down: false)
        }
        sentModifiers.removeAll()
        commandTapPending = false
        if capsLockEngaged {
            capsLockEngaged = false
            send(.capsLock, down: false)
        }
        capsLockPressedAt = nil
        if !pointerButtons.isEmpty {
            release(pointerButtons)
        }
        for modifier in lockedModifiers.union(pressedModifiers) {
            send(modifier.macKey, down: false)
        }
        lockedModifiers = []
        pressedModifiers = []
    }

    #if os(iOS)
    private func heldModifiers(from flags: UIKeyModifierFlags) -> Set<ModifierKey> {
        var result = Set<ModifierKey>()
        if flags.contains(.control) { result.insert(.control) }
        if flags.contains(.alternate) { result.insert(.option) }
        if flags.contains(.shift) { result.insert(.shift) }
        if flags.contains(.command) { result.insert(.command) }
        return result
    }
    #else
    private func heldModifiers(from flags: NSEvent.ModifierFlags) -> Set<ModifierKey> {
        var result = Set<ModifierKey>()
        if flags.contains(.control) { result.insert(.control) }
        if flags.contains(.option) { result.insert(.option) }
        if flags.contains(.shift) { result.insert(.shift) }
        if flags.contains(.command) { result.insert(.command) }
        return result
    }

    private func keysym(for event: NSEvent) -> UInt32? {
        if let special = Keysym.special(forKeyCode: event.keyCode) { return special }
        let flags = event.modifierFlags
        let base = event.charactersIgnoringModifiers ?? ""
        var text = event.characters ?? ""
        if flags.contains(.command) || flags.contains(.control) {
            text = flags.contains(.shift) ? base.uppercased() : base.lowercased()
        } else if let scalar = text.unicodeScalars.first, scalar.value < 0x20 || scalar.value == 0x7f {
            text = base
        }
        guard let scalar = text.unicodeScalars.first else { return nil }
        return Keysym.character(scalar)
    }
    #endif

    #if os(iOS)
    private func macKey(for key: UIKey) -> MacKey? {
        let typed = keysym(for: key)
        guard let keyCode = MacKey.keyCode(for: key.keyCode, type: keyboardType),
            info.platform != .pc || ![.keyboardPrintScreen, .keyboardScrollLock, .keyboardPause].contains(key.keyCode)
        else {
            return typed.map { MacKey(keyCode: nil, keysym: $0) }
        }
        return MacKey(keyCode: keyCode, keysym: typed ?? 0)
    }

    private func keysym(for key: UIKey) -> UInt32? {
        if let special = Keysym.special(for: key.keyCode) { return special }
        let flags = key.modifierFlags
        let base = key.charactersIgnoringModifiers
        var text: String
        if flags.contains(.command) || flags.contains(.control) {
            if flags.contains(.shift) {
                let typed = key.characters
                if let scalar = typed.unicodeScalars.first, scalar.value >= 0x20, scalar.value != 0x7f {
                    text = typed.uppercased()
                } else {
                    text = base.uppercased()
                }
            } else {
                text = base.lowercased()
            }
        } else {
            let typed = key.characters
            if let scalar = typed.unicodeScalars.first, scalar.value >= 0x20, scalar.value != 0x7f {
                text = typed
            } else {
                text = base
            }
            if text.count == 1, text.first?.isLetter == true {
                let cased = flags.contains(.shift) != capsLockEngaged ? text.uppercased() : text.lowercased()
                if cased.count == 1 { text = cased }
            }
        }
        guard let scalar = text.unicodeScalars.first else { return nil }
        return Keysym.character(scalar)
    }

    #endif

    private func startRepeat(_ key: MacKey, id: Int) {
        stopRepeat()
        repeatTask = Task { [weak self] in
            try? await Task.sleep(seconds: 0.45)
            while !Task.isCancelled {
                guard let self, self.pressedKeys[id] == key else { return }
                self.send(key, down: true)
                try? await Task.sleep(seconds: 0.04)
            }
        }
    }

    private func stopRepeat() {
        repeatTask?.cancel()
        repeatTask = nil
    }

    private func send(_ key: MacKey, down: Bool) {
        session?.sendKey(key, keyboardType: keyboardType, down: down)
    }


    func sampleDebugInfo() {
        debugInfo.sample(phase == .connected ? session?.statistics() : nil)
    }
}

@MainActor
final class SessionActivity {
    static let shared = SessionActivity()
    private var active = Set<ObjectIdentifier>()
    #if os(macOS)
    private var activity: (any NSObjectProtocol)?
    #endif

    func set(_ controller: AnyObject, active isActive: Bool) {
        if isActive {
            active.insert(ObjectIdentifier(controller))
        } else {
            active.remove(ObjectIdentifier(controller))
        }
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = !active.isEmpty
        #else
        if active.isEmpty, let activity {
            ProcessInfo.processInfo.endActivity(activity)
            self.activity = nil
        } else if !active.isEmpty, activity == nil {
            activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .idleDisplaySleepDisabled], reason: "Screen Sharing session")
        }
        #endif
    }
}

nonisolated final class SessionEventSink: @unchecked Sendable {
    private let lock = NSLock()
    private weak var controller: SessionController?
    private let pipeline: DisplayPipeline
    private let debugInfo: DebugInfoMonitor
    private var detached = false
    private var decoder: VideoDecoder!
    private var audioPlayer: AudioPlayer?
    private var isVideo = false
    private var sceneInBackground = false
    weak var session: LibsharingRemoteSession?

    @MainActor
    init(controller: SessionController, pipeline: DisplayPipeline) {
        self.controller = controller
        self.pipeline = pipeline
        debugInfo = controller.debugInfo
        decoder = VideoDecoder(
            output: { [weak self, pipeline] frame in
                guard let self, !self.isDetached else { return }
                let timing = frame.timing
                pipeline.setVideoFrame(frame) { [weak self] picture in
                    self?.pictureReady(picture, arrival: timing.arrival, capture: timing.capture, composed: timing.composed)
                }
            },
            failure: { [weak self] in self?.session?.requestKeyframe() },
            unsupported: { [weak self] hdr, error in self?.onMain { $0.videoDecodingFailed(error, hdr: hdr) } }
        )
    }

    func detach() {
        lock.lock()
        detached = true
        let player = audioPlayer
        audioPlayer = nil
        lock.unlock()
        player?.stop()
        decoder.reset()
    }

    private var isDetached: Bool {
        lock.lock()
        defer { lock.unlock() }
        return detached
    }

    func setSceneInBackground(_ background: Bool) {
        session?.setVisible(!background)
        decoder.setSuspended(background)
        lock.lock()
        sceneInBackground = background
        audioPlayer?.setInBackground(background)
        lock.unlock()
    }

    var keepsAppRunning: Bool {
        lock.lock()
        let player = audioPlayer
        lock.unlock()
        return player?.isPlaying ?? false
    }

    private func onMain(_ body: @escaping @MainActor (SessionController) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isDetached, let controller = self.controller else { return }
            body(controller)
        }
    }

    private func pictureReady(_ picture: Int, arrival: CFTimeInterval, capture: CFTimeInterval?, composed: CFTimeInterval?) {
        guard debugInfo.isRunning.load(ordering: .relaxed) else { return }
        onMain { $0.debugInfo.pictureReady(picture, arrival: arrival, capture: capture, composed: composed) }
    }

    func sessionPhaseChanged(_ phase: SessionPhase) {
        onMain { $0.handle(phase: phase) }
    }

    func sessionNeedsFramebuffer(width: Int, height: Int) throws -> Framebuffer? {
        guard !isDetached else { return nil }
        let framebuffer = try Framebuffer(width: width, height: height)
        pipeline.attachFramebuffer(framebuffer)
        lock.lock()
        let video = isVideo
        lock.unlock()
        if video {
            pipeline.beginVideo()
        }
        onMain { $0.handle(screenWidth: width, height: height, video: video, resized: true) }
        return framebuffer
    }

    func sessionFramebufferUpdated(_ dirtyRect: CGRect, arrival: CFTimeInterval) {
        guard !isDetached else { return }
        do {
            if let picture = try pipeline.updateFramebuffer(dirtyRect) {
                pictureReady(picture, arrival: arrival, capture: nil, composed: nil)
            }
        } catch {
            onMain { $0.drawingFailed(error) }
        }
    }

    func sessionVideoFormatChanged(_ format: VideoFormat) {
        decoder.setFormat(format)
        lock.lock()
        let started = !isVideo
        isVideo = true
        lock.unlock()
        guard started else { return }
        pipeline.beginVideo()
        let size = pipeline.screenSize
        onMain { $0.handle(screenWidth: size.width, height: size.height, video: true, resized: false) }
    }

    func sessionVideoStopped(failure: MediaFailure?) {
        decoder.reset()
        lock.lock()
        isVideo = false
        let player = audioPlayer
        audioPlayer = nil
        lock.unlock()
        player?.stop()
        pipeline.endVideo()
        let size = pipeline.screenSize
        onMain { controller in
            controller.handle(screenWidth: size.width, height: size.height, video: false, resized: false)
            if let failure {
                controller.handleVideoFailure(failure)
            }
        }
    }

    func sessionReceivedVideo(_ unit: VideoAccessUnit) {
        decoder.decode(unit)
    }

    func sessionAudioFormatChanged(_ format: AudioFormat) {
        let player: AudioPlayer?
        do {
            player = try AudioPlayer(
                format: format,
                failed: { [weak self] error in self?.onMain { $0.audioFailed(error) } },
                stopped: { [weak self] in self?.onMain { $0.closeIfSuspending() } }
            )
        } catch {
            player = nil
            onMain { $0.audioFailed(error) }
        }
        lock.lock()
        let previous = audioPlayer
        audioPlayer = player
        player?.setInBackground(sceneInBackground)
        lock.unlock()
        previous?.stop()
    }

    func sessionMicrophoneChanged(_ format: AudioFormat?) {
        onMain { $0.handle(microphone: format) }
    }

    func sessionReceivedAudio(_ unit: AudioAccessUnit) {
        lock.lock()
        let player = audioPlayer
        lock.unlock()
        player?.enqueue(unit)
    }

    func sessionDisplaysChanged(_ displays: [RemoteDisplay], selection: DisplaySelection) {
        onMain { $0.handle(displays: displays, selection: selection) }
    }

    func sessionCursorChanged(_ cursor: CursorShape?) {
        pipeline.setCursor(cursor)
    }

    func sessionModeChanged(_ mode: SessionMode) {
        onMain { $0.handle(mode: mode) }
    }

    func sessionCapabilitiesChanged(_ capabilities: SessionCapabilities) {
        onMain { $0.handle(capabilities: capabilities) }
    }

    func sessionClipboardReceived(_ content: ClipboardContent) {
        onMain { $0.handle(clipboard: content) }
    }

    func sessionCursorMoved(x: Int, y: Int) {
        onMain { $0.handle(cursorMovedTo: CGPoint(x: x, y: y)) }
    }

    func sessionKeyboardSourceChanged(_ source: String?, secureInput: Bool) {
        onMain { $0.handle(keyboardSource: source, secureInput: secureInput) }
    }

    func sessionEvent(_ event: SessionEvent) {
        onMain { $0.handle(event: event) }
    }

    func sessionFileTransferProgress(id: Int64, transferred: Int64, total: Int64) {
        onMain { $0.handleTransferProgress(id: id, transferred: transferred, total: total) }
    }

    func sessionFileTransferFinished(id: Int64, name: String?, error: FileTransferError?) {
        onMain { $0.handleTransferFinished(id: id, name: name, error: error) }
    }

    func sessionDeviceInfo(_ device: DeviceInfo) {
        onMain { $0.handle(device: device) }
    }

    func sessionDropDestination(id: Int64, path: String) {
        onMain { $0.handleDropDestination(id: id, path: path) }
    }

    func sessionRemoteDragChanged(_ items: [RemoteDragItem], image: Data?) {
        onMain { $0.handleRemoteDrag(items, image: image) }
    }

    func sessionInfoChanged(_ info: SessionInfo) {
        onMain { $0.info = info }
    }
}
