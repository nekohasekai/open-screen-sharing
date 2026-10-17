import AVFoundation
import Atomics
import Combine
import CoreGraphics
import Foundation
import ImageIO
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
    var usesDynamicResolution = true
    var resolutionScale = VirtualDisplayMode.defaultResolutionScale
    var display: DisplaySelection?
    var usesHDR = false
    var remoteAudio = RemoteAudio.fromMac
    var windowSize: CGSize?
    var locksPointer = false
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

final class SessionController: ObservableObject, Identifiable {
    let route: SessionRoute
    @Published private(set) var title: String
    @Published private(set) var phase: SessionPhase = .idle {
        didSet { SessionActivity.shared.set(self, active: phase.isActive) }
    }
    @Published private(set) var mode: SessionMode = .control
    @Published private(set) var displays: [RemoteDisplay] = []
    @Published private(set) var displaySelection: DisplaySelection = .display(1)
    @Published private(set) var capabilities: SessionCapabilities = []
    @Published private(set) var highPerformance: Bool?
    @Published private(set) var quality: SessionQuality = .adaptive
    @Published private var networkConfiguration = NetworkConfiguration()
    @Published private(set) var clipboardSharing = true
    @Published private(set) var info: SessionInfo
    @Published private(set) var keyboardSources: [MacInputSource] = []
    @Published private(set) var imageSize: CGSize = .zero
    @Published private(set) var picture = ScreenPicture.none
    @Published private(set) var isVideo = false
    @Published private(set) var usesDynamicResolution = true
    @Published private(set) var resolutionScale = VirtualDisplayMode.defaultResolutionScale
    @Published private(set) var hdrEnabled = false
    @Published var displaySupportsHDR = false
    @Published private(set) var remoteAudio: RemoteAudio = .fromMac
    @Published var scaleMode: ScaleMode = .fit
    @Published var isFullScreen = false
    @Published var isToolbarVisible = false
    @Published var isToolbarDocked = false
    @Published var letterbox: Letterbox?
    @Published private(set) var isPointerLockOn = false
    @Published private(set) var isZoomModeOn = false
    @Published var isPointerLocked = false {
        didSet {
            if isPointerLocked { pointerLockHasLocked = true }
        }
    }
    @Published var isSystemPointerOverPicture = false
    @Published private(set) var isKeyboardVisible = false
    @Published private(set) var usesHardwareKeyboard = false
    @Published var stickyModifiers: Set<ModifierKey> = []
    @Published private(set) var lockedModifiers: Set<ModifierKey> = []
    @Published private(set) var pressedModifiers: Set<ModifierKey> = []
    @Published var credentialPrompt: CredentialPrompt?
    @Published private(set) var computerID: UUID?
    @Published var alert: AlertState?
    @Published private(set) var isClosing = false
    @Published private(set) var dropTarget: DropTarget?
    @Published var isShowingResolution = false
    @Published var isCustomizingToolbar = false
    let debugInfo = DebugInfoMonitor()
    private var transfers: [FileTransfer] = [] {
        didSet { updateTransferTask() }
    }
    private var remotePointerVisible = true
    private var lastRemoteClipboard: ClipboardContent?
    private var syncedClipboardChangeCount = ClipboardAccess.changeCount
    private var reportedClipboardNotRestored = false
    private var readingClipboard = false
    private var decidedClipboardSharing = false

    let pipeline = DisplayPipeline()
    #if os(iOS)
    weak var screen: RemoteScreenUIView?
    #else
    weak var screen: RemoteScreenNSView?
    #endif
    var toolbarHandleFrame: CGRect?
    var toolbarBarFrame: CGRect?
    var lastSystemPointerLocation: CGPoint?
    private var pointerLockHasLocked = false
    private let store = ComputerStore.shared
    #if os(iOS)
    private var miceChanges: AnyCancellable?
    #endif
    var onClose: () -> Void = {}

    private var session: LibsharingRemoteSession?
    private var sink: SessionEventSink?
    private var dragID: Int64?
    private var drops: [Int64: DropDelivery] = [:]
    private(set) var remoteDragOffer: RemoteDragOffer?
    private var promisedFiles: [Int64: PromisedFile] = [:]
    private var holdsPointer = false
    private var credentials: SessionCredentials?
    private var pressedKeys: [Int: MacKey] = [:]
    private var sentModifiers: [ModifierKey: MacKey] = [:]
    private var commandTapPending = false
    private var repeatTask: Task<Void, Never>?
    private var capsLockEngaged = false
    private var capsLockPressedAt: TimeInterval?
    private var capsLockActsOnPress = false
    private var keyboardSourceRequest: KeyboardSourceRequest?
    #if os(macOS)
    private var inferredModifierKeys = Set<Int>()
    #endif
    private var previousKeyboardSource: String?
    private var queuedKeyboardSource: MacInputSource?
    private var keyboardSourceTimeout: Task<Void, Never>?
    private var keyboardType = MacKeyboardType.ansi
    private(set) var pointerPosition = CGPoint.zero
    private var pictureSize = CGSize.zero
    private var pictureTurns = PictureTurns.none
    private(set) var pointerButtons: PointerButtons = []
    private var pointerModifiers: Set<ModifierKey> = []
    private var lastPointerMoveTime: TimeInterval = 0
    private var pointerMovedSinceTouch = false
    private var displayArea = CGSize.zero
    private(set) var windowSize: CGSize?
    private var displayScale: CGFloat = 2
    private var screenSize = CGSize.zero
    private var screenRefreshRate = 60
    private let virtualDisplayRequests = VirtualDisplayRequests()
    private(set) var sceneInBackground = false
    private var activeWhenBackgrounded = false
    #if os(iOS)
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid
    private var transferTask: ContinuedTask?
    private var transferTaskIDs: Set<Int64> = []
    #endif
    private var returnedFromBackground: TimeInterval?
    private var resumeCheck: Task<Void, Never>?
    private var restartsHighPerformanceOnReturn = false
    private var hasConnected = false
    private var microphone: MicrophoneSender?
    private var reconnectAttempts = 0
    private var reconnectTask: Task<Void, Never>?
    private var chosenDisplay: DisplaySelection?
    private var restoresDisplay = false
    private var assistCursorShown = false
    private var hasStartedHighPerformance = false
    private var highPerformanceStartPending = false
    private var awaitsVirtualDisplay = false {
        didSet { pipeline.setHoldsVideo(awaitsVirtualDisplay) }
    }
    private var awaitsResolutionChange = false {
        didSet {
            if awaitsResolutionChange != oldValue { pipeline.setWaitsForDisplay(awaitsResolutionChange) }
        }
    }
    private var highPerformanceAwaitsDisplay = false
    @Published private(set) var hasPicture = false
    private var isScrollGliding = false

    private var server: ServerAuthentication?
    private var probeTask: Task<Void, Never>?
    private var trustedFingerprint = ""
    private var passwordUpdate = PasswordUpdate.keep
    private var usedSavedPassword = false
    private var device: DeviceInfo?
    @Published private var deviceType: DeviceType? {
        didSet {
            if isIOS {
                virtualDisplayRequests.stopWaiting()
            }
        }
    }

    private var isIOS: Bool { deviceType?.isIOS ?? false }
    private var isPhone: Bool { deviceType == .iPhone }

    var platform: RemotePlatform {
        isIOS ? .iOS : info.platform
    }

    init(route: SessionRoute) {
        self.route = route
        title = route.name
        info = SessionInfo(remoteProtocol: route.remoteProtocol, platform: route.knownPlatform)
        computerID = route.computerID
        server = route.server
        device = route.model.map { DeviceInfo(model: $0, enclosureColor: "") }
        highPerformance = route.remoteProtocol == .vnc ? route.usesHighPerformance : false
        deviceType = route.deviceType ?? route.model.flatMap(DeviceType.init(model:))
        windowSize = route.computerID.flatMap { store.stored($0) }?.memory.windowSize
        virtualDisplayRequests.onIdle = { [weak self] in self?.virtualDisplayDidSettle() }
        #if os(iOS)
        miceChanges = PointerLock.mice.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }
        #endif
        pipeline.setShownHandler { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                let shown = self.pipeline.currentPicture
                self.handle(picture: shown.picture, width: shown.width, height: shown.height, turns: shown.turns)
            }
        }
    }


    func start() {
        guard session == nil, probeTask == nil else { return }
        if case .failed(let failure) = phase, failure.kind == .drawingFailed { return }
        let stored = computerID.flatMap { store.stored($0) }
        if let stored {
            restore(stored.memory)
            device = device ?? stored.device
            highPerformance = stored.usesHighPerformance
            deviceType = stored.deviceType
            keyboardSources = stored.keyboardSources.map(MacInputSource.init(id:))
            trustedFingerprint = stored.trustedFingerprint
        }
        if !decidedClipboardSharing {
            decidedClipboardSharing = true
            clipboardSharing = clipboardSharing && ClipboardAccess.shared.allowsReading
        }
        networkConfiguration = stored?.networkConfiguration ?? NetworkConfiguration()
        let username = route.username ?? stored?.username ?? ""
        let savedPassword = stored.flatMap { $0.username == username ? $0.password : nil } ?? ""
        passwordUpdate = .keep
        usedSavedPassword = false
        if let password = route.password {
            // An explicitly provided password may be empty for accounts configured
            // without one.
            connect(with: SessionCredentials(loginType: .account, username: username, password: password))
        } else if stored?.authenticates == true && stored?.username == username {
            // A saved account may likewise have an empty password.
            usedSavedPassword = true
            connect(with: SessionCredentials(loginType: .account, username: username, password: savedPassword))
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
            form: form, username: username, remembers: usedSavedPassword, failed: failure != nil, errorMessage: failure?.message ?? "")
    }

    func submitCredentials(_ credentials: SessionCredentials, remember: Bool) {
        credentialPrompt = nil
        switch credentials.loginType {
        case .account: passwordUpdate = remember ? .save(credentials.password) : .delete
        case .vncPassword, .askForPermission, .none: passwordUpdate = .keep
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
        usesDynamicResolution = memory.usesDynamicResolution
        resolutionScale = memory.resolutionScale
        chosenDisplay = memory.display
        hdrEnabled = memory.usesHDR && DecoderCapabilities.current.supportsHDR
        remoteAudio = memory.remoteAudio
        windowSize = memory.windowSize
        isPointerLockOn = memory.locksPointer
    }

    private var memory: SessionMemory {
        SessionMemory(
            quality: quality,
            observes: mode == .observe,
            sharesClipboard: clipboardSharing,
            usesDynamicResolution: usesDynamicResolution,
            resolutionScale: resolutionScale,
            display: chosenDisplay,
            usesHDR: hdrEnabled,
            remoteAudio: remoteAudio,
            windowSize: windowSize,
            locksPointer: isPointerLockOn
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
                    username: credentials.loginType == .account ? credentials.username : "",
                    password: passwordUpdate,
                    supportsHighPerformance: capabilities.contains(.highPerformance),
                    remoteProtocol: route.remoteProtocol,
                    usesHighPerformance: highPerformance ?? false,
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

    private var canDecodeVideo: Bool {
        let decoder = DecoderCapabilities.current
        return decoder.supportsHEVC || decoder.supportsH264
    }

    var usesHighPerformance: Bool { highPerformance == true }

    private var mayUseHighPerformance: Bool { highPerformance ?? canDecodeVideo }

    private func settleHighPerformance() {
        guard highPerformance == nil else { return }
        highPerformance = capabilities.contains(.highPerformance) && canDecodeVideo
        if highPerformance == true {
            highPerformanceStartPending = true
        }
    }

    fileprivate func handle(device: DeviceInfo) {
        self.device = device
        if let type = DeviceType(model: device.model) {
            deviceType = type
        }
        if let computerID {
            save { try $0.setDevice(device, for: computerID) }
        }
    }

    var touchScreenSize: CGSize? {
        guard let density = touchScreenDensity, info.framebufferWidth > 0, info.framebufferHeight > 0 else { return nil }
        return CGSize(width: Double(info.framebufferWidth) / density, height: Double(info.framebufferHeight) / density)
    }

    private var touchScreenDensity: Double? {
        guard isIOS else { return nil }
        return isPhone && min(info.framebufferWidth, info.framebufferHeight) >= 1000 ? 3 : 2
    }

    var cursorPixelsPerPoint: Double {
        guard let density = touchScreenDensity else { return info.cursorPixelsPerPoint }
        return density / info.framebufferPixelsPerCursorPixel
    }

    private func connect(with credentials: SessionCredentials) {
        self.credentials = credentials
        if let computerID, let computer = store.computer(computerID) {
            networkConfiguration = computer.networkConfiguration
        }
        hdrEnabled = hdrEnabled && networkSupportsHDR
        hasStartedHighPerformance = false
        highPerformanceStartPending = usesHighPerformance
        hasPicture = false
        pipeline.setExpectsVideo(mayUseHighPerformance)
        pipeline.setSchedulesPresentation(networkConfiguration.schedulesPresentation)
        let configuration = SessionConfiguration(
            address: route.address,
            remoteProtocol: route.remoteProtocol,
            platform: route.knownPlatform,
            trustedFingerprint: trustedFingerprint,
            keyboardLayout: WindowsKeyboardLayout.current,
            clientName: route.remoteProtocol == .rdp || credentials.username.isEmpty ? Self.deviceName : credentials.username,
            credentials: credentials,
            quality: quality,
            startsInObserveMode: mode == .observe,
            sharesClipboard: clipboardSharing,
            remoteAudio: remoteAudio,
            updateRate: screenRefreshRate,
            networkConfiguration: networkConfiguration
        )
        let sink = SessionEventSink(controller: self, pipeline: pipeline)
        let session = LibsharingRemoteSession(configuration: configuration, delegate: sink)
        sink.session = session
        if sceneInBackground {
            sink.setSceneInBackground(true)
        }
        self.session = session
        self.sink = sink
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
        highPerformanceAwaitsDisplay = mayUseHighPerformance
        requestVirtualDisplay()
        if !wantsVirtualDisplay {
            startHighPerformanceWithConnection()
        }
        session.connect()
    }

    private func startHighPerformanceWithConnection() {
        guard highPerformanceAwaitsDisplay, virtualDisplayRequests.isOpening else { return }
        highPerformanceAwaitsDisplay = false
        session?.startHighPerformanceWithConnection()
    }

    func disconnect() {
        isClosing = true
        releaseAllKeys()
        teardown()
        phase = .disconnected
        endBackgroundTask()
    }

    func close() {
        disconnect()
        onClose()
    }

    func trustCertificate(_ fingerprint: String) {
        trustedFingerprint = fingerprint
        if let computerID {
            save { try $0.setTrustedFingerprint(fingerprint, for: computerID) }
        }
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
        resumeCheck?.cancel()
        resumeCheck = nil
        restartsHighPerformanceOnReturn = false
        microphone?.stop()
        microphone = nil
        sink?.detach()
        awaitsResolutionChange = false
        pipeline.holdPicture()
        session?.stop()
        session = nil
        sink = nil
        virtualDisplayRequests.reset(sender: nil)
        awaitsVirtualDisplay = false
        highPerformanceAwaitsDisplay = false
        holdsPointer = false
        pointerButtons = []
        pointerModifiers = []
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
        endResumeCheck()
        releaseAllKeys()
        stopScrollMomentum()
        updateMicrophone()
        sink?.setSceneInBackground(true)
        #if os(iOS)
        screen?.backgroundStateDidChange()
        #endif
        if phase == .connected {
            beginBackgroundTask()
        }
    }

    func sceneDidBecomeActive() {
        guard sceneInBackground else {
            sendChangedClipboard()
            return
        }
        sceneInBackground = false
        returnedFromBackground = ProcessInfo.processInfo.systemUptime
        endBackgroundTask()
        sink?.setSceneInBackground(false)
        #if os(iOS)
        screen?.backgroundStateDidChange()
        #endif
        updateMicrophone()
        let wasActive = activeWhenBackgrounded
        activeWhenBackgrounded = false
        let restartsHighPerformance = restartsHighPerformanceOnReturn
        restartsHighPerformanceOnReturn = false
        if phase == .connected {
            checkConnectionAfterBackground()
            if resumeCheck == nil {
                sendChangedClipboard()
            }
            if restartsHighPerformance {
                restartHighPerformance()
            }
        } else if wasActive, brokeInBackground {
            reconnectNow()
        }
    }

    private func beginBackgroundTask() {
        #if os(iOS)
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Screen Sharing session") { [self] in
            endBackgroundTask()
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

    private static let resumeWindow: TimeInterval = 5

    private var returnedMomentsAgo: Bool {
        guard !sceneInBackground, let returnedFromBackground else { return false }
        return ProcessInfo.processInfo.systemUptime - returnedFromBackground < Self.resumeWindow
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
        releaseAllKeys()
        teardown()
        reconnectAttempts = 0
        connect(with: credentials)
        phase = .reconnecting(attempt: 1)
    }

    private func checkConnectionAfterBackground() {
        guard let session, let sink else { return }
        if isVideo {
            session.requestKeyframe()
        } else {
            session.requestRefresh()
        }
        guard hasPicture else { return }
        sink.watchForLife()
        resumeCheck?.cancel()
        resumeCheck = Task { [weak self] in
            try? await Task.sleep(seconds: Self.resumeWindow)
            guard let self, !Task.isCancelled, self.phase == .connected else { return }
            self.resumeCheck = nil
            self.reconnectNow()
        }
    }

    fileprivate func endResumeCheck() {
        resumeCheck?.cancel()
        resumeCheck = nil
    }

    fileprivate func connectionResumed() {
        guard resumeCheck != nil else { return }
        endResumeCheck()
        sendChangedClipboard()
    }

    private func restartHighPerformance() {
        guard usesHighPerformance, phase == .connected, !isVideo else { return }
        if needsReconnectToRestartStream {
            reconnectNow()
        } else {
            highPerformanceStartPending = true
            startHighPerformanceIfDisplayReady()
        }
    }


    private static let reconnectLimit = 6

    fileprivate func handle(phase newPhase: SessionPhase) {
        endResumeCheck()
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
                let delay: TimeInterval = attempt == 1 && returnedMomentsAgo ? 0 : TimeInterval(min(1 << (attempt - 1), 8))
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
        phase = newPhase
        switch newPhase {
        case .connected:
            reconnectAttempts = 0
            settleHighPerformance()
            if info.dialect != .apple {
                deviceType = nil
            } else if deviceType == nil {
                deviceType = .mac
            }
            if !hasConnected || passwordUpdate.isPending {
                hasConnected = true
                recordConnection()
            }
            if info.fingerprint != trustedFingerprint, let computerID {
                save { try $0.setTrustedFingerprint(info.fingerprint, for: computerID) }
            }
            trustedFingerprint = info.fingerprint
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
        case .failed(let failure):
            releaseAllKeys()
            if failure.kind == .authenticationFailed, credentials?.loginType == .account || credentials?.loginType == .vncPassword {
                retry()
            }
        case .disconnected:
            releaseAllKeys()
        default:
            break
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

    fileprivate func handle(screenWidth: Int, height: Int, video: Bool) {
        virtualDisplayRequests.framebufferDidChange(width: screenWidth, height: height)
        if isVideo != video {
            isVideo = video
            updateMicrophone()
        }
        if video {
            hasStartedHighPerformance = true
        }
    }

    private func handle(picture: ScreenPicture, width: Int, height: Int, turns: PictureTurns) {
        if self.picture != picture {
            self.picture = picture
        }
        if picture == .current, !hasPicture {
            hasPicture = true
        }
        let newPictureSize = CGSize(width: width, height: height)
        guard newPictureSize != pictureSize || turns != pictureTurns else { return }
        let serverPointer = newPictureSize == pictureSize ? serverPoint(pointerPosition) : nil
        pictureSize = newPictureSize
        pictureTurns = turns
        let size = turns.shownSize(of: newPictureSize)
        imageSize = size
        if let serverPointer {
            pointerPosition = shownPoint(serverPointer)
            pipeline.setCursorPosition(pointerPosition, visible: remotePointerVisible)
        } else if pointerPosition == .zero || pointerPosition.x >= size.width || pointerPosition.y >= size.height {
            pointerPosition = CGPoint(x: size.width / 2, y: size.height / 2)
            pipeline.setCursorPosition(pointerPosition, visible: remotePointerVisible)
        }
        screen?.imageSizeDidChange()
    }

    private func serverPoint(_ point: CGPoint) -> CGPoint {
        pictureTurns.picturePoint(point, inPictureOf: pictureSize)
    }

    private func shownPoint(_ point: CGPoint) -> CGPoint {
        pictureTurns.shownPoint(point, inPictureOf: pictureSize)
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

    fileprivate func handle(info: SessionInfo) {
        var updated = info
        updated.keyboardSource = self.info.keyboardSource
        updated.secureInput = self.info.secureInput
        if self.info != updated {
            self.info = updated
        }
    }

    fileprivate func handleVideoFailure(_ failure: MediaFailure) {
        if usesHighPerformance, failure.kind == .silent || failure.kind == .network, sceneInBackground || returnedMomentsAgo {
            hasStartedHighPerformance = true
            if sceneInBackground {
                restartsHighPerformanceOnReturn = true
            } else {
                restartHighPerformance()
            }
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
        pointerPosition = shownPoint(point)
        pipeline.setCursorPosition(pointerPosition, visible: remotePointerVisible)
    }

    fileprivate func handle(keyboardSource source: String?, secureInput: Bool) {
        let current = info.keyboardSource
        if let current, let source, source != current {
            previousKeyboardSource = current
        }
        info.keyboardSource = source
        info.secureInput = secureInput
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
        moveTouch(to: point)
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
        guard !items.isEmpty, capabilities.contains(.dragFromRemote), isControlling, pointerButtons.contains(.primary) else {
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
        session?.cancelRemoteDrag()
        holdsPointer = true
        Task { [weak self] in
            try? await Task.sleep(seconds: 0.5)
            guard let self, self.holdsPointer else { return }
            self.holdsPointer = false
            if self.isControlling { self.sendPointer(buttons: self.pointerButtons) }
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

    private var needsReconnectToRestartStream: Bool {
        hasStartedHighPerformance && !usesDynamicResolution && phase == .connected
    }

    var networkSupportsHDR: Bool {
        networkConfiguration.chroma.pixelFormats(available: DecoderCapabilities.current.pixelFormats) & (1 << 5) != 0
    }

    var canUseHDR: Bool {
        usesHighPerformance && capabilities.contains(.highPerformance) && usesDynamicResolution
            && displaySupportsHDR && networkSupportsHDR
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

    func setRemoteAudio(_ audio: RemoteAudio) {
        let changed = audio != remoteAudio
        remoteAudio = audio
        rememberState()
        if changed, capabilities.contains(.audio) ? phase == .connected : isVideo && needsReconnectToRestartStream {
            reconnectNow()
            return
        }
        session?.setRemoteAudio(audio)
        updateMicrophone()
    }

    private func updateMicrophone() {
        let wanted = isVideo && remoteAudio == .toMac && phase == .connected && !sceneInBackground
        if !wanted {
            microphone?.stop()
            microphone = nil
            return
        }
        guard microphone == nil else { return }
        Task { [self] in
            let granted: Bool
            if #available(iOS 17, *) {
                granted = await AVAudioApplication.requestRecordPermission()
            } else {
                #if os(iOS)
                granted = await withCheckedContinuation { continuation in
                    AVAudioSession.sharedInstance().requestRecordPermission { @Sendable granted in
                        continuation.resume(returning: granted)
                    }
                }
                #else
                granted = false
                #endif
            }
            guard microphone == nil, isVideo, remoteAudio == .toMac, phase == .connected, !sceneInBackground else { return }
            guard granted else {
                #if os(iOS)
                alert = AlertState(String(localized: "Can’t Use the Microphone"), errorMessage: String(localized: "Microphone access is turned off in Settings."))
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
                return
            }
            let session = self.session
            do {
                let sender = try MicrophoneSender(
                    send: { data in session?.sendMicrophoneAudio(data) },
                    failed: { [weak self] error in
                        DispatchQueue.main.async {
                            guard let self, let microphone = self.microphone else { return }
                            microphone.stop()
                            self.microphone = nil
                            self.alert = AlertState(String(localized: "Can’t Use the Microphone"), error: error)
                        }
                    })
                try sender.start()
                microphone = sender
            } catch {
                alert = AlertState(String(localized: "Can’t Use the Microphone"), error: error)
            }
        }
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

    func setUsesDynamicResolution(_ uses: Bool) {
        guard uses != usesDynamicResolution else { return }
        usesDynamicResolution = uses
        rememberState()
        if !uses, phase == .connected {
            reconnectNow()
        } else {
            requestVirtualDisplay(holdingPicture: true)
        }
    }

    func setResolutionScale(_ scale: Double) {
        guard scale != resolutionScale else { return }
        resolutionScale = scale
        rememberState()
        requestVirtualDisplay(holdingPicture: true)
    }

    var wantsVirtualDisplay: Bool {
        !isIOS && usesDynamicResolution
    }

    var pictureMatchesWindow: Bool {
        wantsVirtualDisplay && capabilities.contains(.virtualDisplay)
    }

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
        startHighPerformanceWithConnection()
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
        alert = AlertState(platform != .pc ? String(localized: "Can’t Play the Mac’s Sound") : String(localized: "Can’t Play the Computer’s Sound"), error: error)
    }

    func sendChord(_ chord: KeyChord) {
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
        if uses != usesHardwareKeyboard { usesHardwareKeyboard = uses }
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
        pointerMovedSinceTouch = true
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

    var touchesDirectly: Bool {
        isIOS && isControlling
    }

    var hidesPointerForTouch: Bool {
        isIOS && !pointerMovedSinceTouch
    }

    func moveTouch(to point: CGPoint) {
        pointerMovedSinceTouch = false
        let clamped = clampToImage(point)
        pointerPosition = clamped
        lastPointerMoveTime = CACurrentMediaTime()
        pipeline.setCursorPosition(clamped, visible: true)
        guard isControlling, !pointerButtons.isEmpty else { return }
        sendPointer(buttons: pointerButtons)
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
            let point = serverPoint(pointerPosition)
            session?.setAssistCursor(x: Int(point.x), y: Int(point.y), pressed: !pointerButtons.isEmpty)
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
        let point = serverPoint(pointerPosition)
        session?.sendPointer(x: Int(point.x.rounded(.down)), y: Int(point.y.rounded(.down)), buttons: buttons)
    }

    func press(_ button: PointerButtons) {
        if isControlling { textCursorMayHaveMoved() }
        if pointerButtons.isEmpty, isControlling {
            if platform == .pc, pressedKeys.values.contains(where: { ModifierKey(keysym: $0.keysym) == .command }) {
                commandTapPending = false
                var modifiers = Set(pressedKeys.values.compactMap { ModifierKey(keysym: $0.keysym) })
                modifiers.remove(.command)
                modifiers.insert(.control)
                setSentModifiers(modifiers)
            }
            pointerModifiers = stickyModifiers.subtracting(lockedModifiers).subtracting(pressedModifiers)
            stickyModifiers.removeAll()
            for modifier in ModifierKey.allCases where pointerModifiers.contains(modifier) { send(modifier.macKey, down: true) }
        }
        setButtons(pointerButtons.union(button))
    }

    func release(_ button: PointerButtons) {
        setButtons(pointerButtons.subtracting(button))
        guard pointerButtons.isEmpty, !pointerModifiers.isEmpty else { return }
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

    func magnify(at point: CGPoint, by magnification: Double, phase: GesturePhase) {
        guard sendsGesturesToMac else { return }
        let point = serverPoint(point)
        session?.sendMagnify(x: Int(point.x), y: Int(point.y), magnification: magnification, phase: phase)
    }

    func rotate(at point: CGPoint, degrees: Double, phase: GesturePhase) {
        guard sendsGesturesToMac else { return }
        let point = serverPoint(point)
        session?.sendRotate(x: Int(point.x), y: Int(point.y), degrees: degrees, phase: phase)
    }

    func scroll(byPixels delta: CGPoint, phase: GesturePhase) {
        guard sendsGesturesToMac else { return }
        isScrollGliding = false
        let point = serverPoint(pointerPosition)
        let delta = pictureTurns.pictureVector(delta)
        session?.sendScrollPhase(x: Int(point.x), y: Int(point.y), deltaX: Double(delta.x), deltaY: Double(delta.y), phase: phase)
    }

    func startScrollMomentum(pixelsPerSecond velocity: CGPoint) {
        guard sendsGesturesToMac, hypot(velocity.x, velocity.y) > 50 else { return }
        isScrollGliding = true
        let point = serverPoint(pointerPosition)
        let velocity = pictureTurns.pictureVector(velocity)
        session?.startScrollMomentum(x: Int(point.x), y: Int(point.y), velocityX: Double(velocity.x), velocityY: Double(velocity.y))
    }

    func stopScrollMomentum() {
        guard isScrollGliding else { return }
        isScrollGliding = false
        session?.stopScrollMomentum()
    }

    func canPerform(_ command: SystemCommand) -> Bool {
        capabilities.contains(command.capability) || platform != .pc && command.fallbackChord != nil
    }

    func perform(_ command: SystemCommand) {
        guard isControlling else { return }
        textCursorMayHaveMoved()
        if capabilities.contains(command.capability) {
            session?.sendSystemCommand(command)
        } else if platform != .pc, let chord = command.fallbackChord {
            sendChord(chord)
        }
    }

    func scroll(byPixels delta: CGPoint) {
        guard isControlling else { return }
        let point = serverPoint(pointerPosition)
        let delta = pictureTurns.pictureVector(delta)
        session?.sendScroll(x: Int(point.x), y: Int(point.y), deltaX: Double(delta.x), deltaY: Double(delta.y))
    }

    private func clampToImage(_ point: CGPoint) -> CGPoint {
        guard imageSize.width > 0, imageSize.height > 0 else { return point }
        return CGPoint(x: min(max(point.x, 0), imageSize.width - 1), y: min(max(point.y, 0), imageSize.height - 1))
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
            if let shortcut, KeyShortcuts.shared.action(using: shortcut) != nil { return false }
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
        if info.secureInput, !Keysym.typesInSecureInput(mapped.keysym) {
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
        if platform == .pc {
            commandTapPending = false
            var modifiers = held
            if let shortcut, let output = KeyTranslations.output(for: shortcut), let chord = output.chord {
                modifiers = Set(chord.modifiers)
                if output.key != shortcut.key { key = chord.key }
            }
            setSentModifiers(modifiers)
        }
        let sticky = stickyModifiers.subtracting(held)
        if !sticky.isEmpty {
            stickyModifiers.removeAll()
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

    private var mappingTarget: MappingTarget {
        platform == .pc ? .pc : .apple
    }

    private func pressModifier(_ key: MacKey, id: Int, tappable: Bool) {
        pressedKeys[id] = key
        guard platform == .pc, let modifier = ModifierKey(keysym: key.keysym) else {
            send(key, down: true)
            return
        }
        commandTapPending = tappable && modifier == .command
        guard modifier != .command, sentModifiers[modifier] == nil else { return }
        sentModifiers[modifier] = key
        send(key, down: true)
    }

    private func release(_ key: MacKey) {
        guard platform == .pc, let modifier = ModifierKey(keysym: key.keysym) else {
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
        if info.secureInput {
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
        let modifiers = held.union(stickyModifiers)
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
        if info.secureInput, !Keysym.typesInSecureInput(key.keysym) {
            if !stickyModifiers.isEmpty { stickyModifiers.removeAll() }
            showSecureInputNotice()
            return false
        }
        var pressing = stickyModifiers
        if shift { pressing.insert(.shift) }
        if !stickyModifiers.isEmpty { stickyModifiers.removeAll() }
        tap(key, modifiers: pressing.subtracting(held))
        return true
    }

    func typeKey(_ key: MacKey) {
        guard isControlling else { return }
        textCursorMayHaveMoved()
        tapKey(key)
    }

    private func tapKey(_ key: MacKey) {
        let sticky = stickyModifiers
        if !sticky.isEmpty { stickyModifiers.removeAll() }
        tap(key, modifiers: sticky)
    }

    private func textCursorMayHaveMoved() {
        #if os(iOS)
        screen?.forgetTypedText()
        #endif
    }

    private func performMapping(_ steps: [KeyMapping.Step], held: Set<ModifierKey>) {
        textCursorMayHaveMoved()
        var latched = lockedModifiers.union(pressedModifiers)
        var down = Set<MacKey>()
        if platform == .pc {
            commandTapPending = false
            setSentModifiers([])
        } else {
            latched.formUnion(held)
            down = Set(pressedKeys.values.filter { ModifierKey(keysym: $0.keysym) != nil })
        }
        for modifier in lockedModifiers.union(pressedModifiers) { down.insert(modifier.macKey) }
        var released: [MacKey] = []
        for step in steps {
            let chord: KeyChord? =
                switch step {
                case .key(let shortcut): platform == .pc ? shortcut.pcChord : shortcut.chord
                case .text: nil
                }
            let wanted = Set(chord?.modifiers ?? [])
            for key in down {
                guard let modifier = ModifierKey(keysym: key.keysym), !wanted.contains(modifier) else { continue }
                send(key, down: false)
                down.remove(key)
                latched.remove(modifier)
                released.append(key)
            }
            if let chord {
                tap(chord.key, modifiers: wanted.subtracting(latched))
            } else if case .text(let text) = step {
                _ = typeUnmodified(Substring(text))
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
        } else if !info.secureInput, macHasOtherKeyboardSource {
            switchKeyboardSource()
        } else {
            setCapsLock(true)
        }
    }

    var keyboardSource: MacInputSource? {
        info.keyboardSource.map(MacInputSource.init(id:))
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
        guard isControlling, !info.secureInput else { return }
        guard keyboardSourceRequest == nil else {
            queuedKeyboardSource = source
            return
        }
        guard source.id != info.keyboardSource else { return }
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
        guard !info.secureInput, let current = keyboardSource, local != current else { return }
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
        for key in keys where platform != .pc || ModifierKey(keysym: key.keysym) == nil {
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
        lockedModifiers.removeAll()
        pressedModifiers.removeAll()
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
        guard let keyCode = MacKey.keyCode(for: key.keyCode, type: keyboardType) else {
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
    private var awaitingLife = false
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
                pipeline.setVideoFrame(frame) { [weak self] picture, held in
                    self?.pictureReady(picture, arrival: timing.arrival, hold: timing.libraryHold + held, decode: timing.decode)
                }
                self.showedLife()
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

    func watchForLife() {
        lock.lock()
        awaitingLife = true
        lock.unlock()
    }

    private func showedLife() {
        lock.lock()
        let awaited = awaitingLife
        awaitingLife = false
        lock.unlock()
        if awaited {
            onMain { $0.connectionResumed() }
        }
    }

    private func onMain(_ body: @escaping @MainActor (SessionController) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isDetached, let controller = self.controller else { return }
            body(controller)
        }
    }

    private func pictureReady(_ picture: Int, arrival: CFTimeInterval, hold: TimeInterval, decode: TimeInterval) {
        guard debugInfo.isRunning.load(ordering: .relaxed) else { return }
        onMain { $0.debugInfo.pictureReady(picture, arrival: arrival, hold: hold, decode: decode) }
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
        onMain { $0.handle(screenWidth: width, height: height, video: video) }
        return framebuffer
    }

    func sessionFramebufferUpdated(_ dirtyRect: CGRect) {
        guard !isDetached else { return }
        if let picture = pipeline.markDirty() {
            pictureReady(picture, arrival: CACurrentMediaTime(), hold: 0, decode: 0)
        }
        showedLife()
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
        onMain { $0.handle(screenWidth: size.width, height: size.height, video: true) }
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
            controller.handle(screenWidth: size.width, height: size.height, video: false)
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
            player = try AudioPlayer(format: format) { [weak self] error in self?.onMain { $0.audioFailed(error) } }
        } catch {
            player = nil
            onMain { $0.audioFailed(error) }
        }
        lock.lock()
        let previous = audioPlayer
        audioPlayer = player
        if !sceneInBackground {
            player?.start()
        }
        lock.unlock()
        previous?.stop()
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
        onMain { $0.handle(info: info) }
    }
}
