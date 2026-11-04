import CoreGraphics
import CoreMedia
import Foundation
import QuartzCore
import Libsharing
import os

nonisolated final class LibsharingRemoteSession: @unchecked Sendable {
    private let configuration: SessionConfiguration
    private let lock = NSLock()
    private let session: LibsharingSession?
    private let handler: LibsharingHandler
    private var transfers: [Int64: LibsharingFileTransfer] = [:]
    private var receivesAudio: Bool
    private var highPerformanceRunning = false
    private var restartingHighPerformance = false
    private var supportsHighPerformance = false
    private weak var delegate: SessionEventSink?

    init(configuration: SessionConfiguration, delegate: SessionEventSink) {
        LibsharingLog.installed
        self.configuration = configuration
        self.delegate = delegate
        receivesAudio = configuration.receivesAudio
        let options = LibsharingNewSessionOptions()!
        options.protocol = configuration.remoteProtocol == .rdp ? LibsharingProtocolRDP : LibsharingProtocolRFB
        options.platform =
            switch configuration.platform {
            case .mac, .iOS: LibsharingPlatformMac
            case .pc: LibsharingPlatformPC
            case nil: LibsharingPlatformUnknown
            }
        options.trustedFingerprint = configuration.trustedFingerprint
        options.allowUnverifiedLogin = configuration.allowsUnverifiedLogin
        options.keyboardLayout = Int32(configuration.keyboardLayout)
        options.audio = configuration.receivesAudio
        options.microphone = configuration.sendsAudio
        options.host = configuration.address.host
        options.port = Int32(configuration.address.port)
        configuration.credentials.apply(to: options, observes: configuration.startsInObserveMode)
        options.quality = Self.quality(configuration.quality)
        options.mode = configuration.startsInObserveMode ? LibsharingModeObserve : LibsharingModeControl
        options.sharedClipboard = configuration.sharesClipboard
        options.clientName = configuration.clientName
        options.decoderMaxPixelRate = DecoderCapabilities.current.maximumPixelRate
        options.maximumFrameRate = Int32(configuration.networkConfiguration.maximumFrameRate.rawValue)
        options.updateRate = Int32(configuration.updateRate)
        options.awaitVirtualDisplay = configuration.awaitsVirtualDisplay
        options.keepAlive = configuration.keepsAlive
        options.disableUDP = configuration.disablesUDP
        options.cacheDirectory = AppDirectory.caches.path

        let handler = LibsharingHandler(delegate: delegate, remoteProtocol: configuration.remoteProtocol, platform: configuration.platform)
        handler.forwardsClipboard = configuration.sharesClipboard
        var error: NSError?
        session = LibsharingNewSession(options, handler, &error)
        self.handler = handler
        if session == nil {
            delegate.sessionPhaseChanged(.failed(SessionFailure(.other, message: error?.localizedDescription ?? "libsharing returned no session")))
        }
        handler.session = session
        handler.onConnected = { [weak self] supportsHighPerformance in
            self?.connected(supportsHighPerformance: supportsHighPerformance)
        }
        handler.onTransferFinished = { [weak self] id in
            self?.transferFinished(id)
        }
        handler.onMediaStopped = { [weak self] in
            guard let self else { return }
            lock.lock()
            if restartingHighPerformance {
                restartingHighPerformance = false
            } else {
                highPerformanceRunning = false
            }
            lock.unlock()
        }
    }

    func connect() {
        do {
            try session?.connect()
        } catch {
            delegate?.sessionPhaseChanged(.failed(SessionFailure(.unreachable, message: error.localizedDescription)))
        }
    }

    func stop() {
        session?.disconnect()
    }

    func abort() {
        session?.abort()
    }

    func answerSessionChoice(_ prompt: SessionChoicePrompt, choice: SessionChoice) {
        let answer =
            switch choice {
            case .shareConsole: LibsharingSessionChoiceShareConsole
            case .ownSession: LibsharingSessionChoiceOwnSession
            case .cancel: LibsharingSessionChoiceCancel
            }
        try? session?.answerPrompt(prompt.id, answer: answer)
    }

    func sendPointer(x: Int, y: Int, buttons: PointerButtons, clickCount: Int) {
        var mask: Int32 = 0
        if buttons.contains(.primary) { mask |= LibsharingButtonLeft }
        if buttons.contains(.middle) { mask |= LibsharingButtonMiddle }
        if buttons.contains(.secondary) { mask |= LibsharingButtonRight }
        try? session?.sendPointer(Int32(x), y: Int32(y), buttons: mask, clickCount: Int32(clickCount))
    }

    func sendScroll(x: Int, y: Int, deltaX: Double, deltaY: Double) {
        let pointsPerPixel = handler.pointsPerPixel
        try? session?.sendScroll(Int32(x), y: Int32(y), deltaX: deltaX * pointsPerPixel, deltaY: deltaY * pointsPerPixel)
    }

    func sendTouches(_ touches: [RemoteTouch]) {
        let frame = LibsharingTouchFrame()
        for touch in touches {
            let phase =
                switch touch.phase {
                case .began: LibsharingTouchPhaseBegan
                case .moved: LibsharingTouchPhaseMoved
                case .ended: LibsharingTouchPhaseEnded
                case .cancelled: LibsharingTouchPhaseCancelled
                }
            frame.add(touch.id, phase: phase, x: Int32(touch.location.x.rounded(.down)), y: Int32(touch.location.y.rounded(.down)))
        }
        try? session?.sendTouches(frame)
    }

    func sendKey(_ key: MacKey, keyboardType: MacKeyboardType, down: Bool) {
        guard let keyCode = key.keyCode else {
            try? session?.sendKey(Int32(bitPattern: key.keysym), down: down)
            return
        }
        let type =
            switch keyboardType {
            case .ansi: LibsharingKeyboardANSI
            case .iso: LibsharingKeyboardISO
            case .jis: LibsharingKeyboardJIS
            }
        try? session?.sendKeyCode(Int32(keyCode), keyboardType: type, keysym: Int32(bitPattern: key.keysym), down: down)
    }

    func sendText(_ text: String) throws {
        try session?.sendText(text)
    }

    func pasteText(_ text: String) throws {
        try session?.pasteText(text)
    }

    func selectKeyboardSource(_ id: String) {
        try? session?.selectKeyboardSource(id)
    }

    private static func phaseValue(_ phase: GesturePhase) -> Int32 {
        switch phase {
        case .mayBegin: LibsharingGesturePhaseMayBegin
        case .began: LibsharingGesturePhaseBegan
        case .changed: LibsharingGesturePhaseChanged
        case .ended: LibsharingGesturePhaseEnded
        case .cancelled: LibsharingGesturePhaseCancelled
        }
    }

    func sendMagnify(x: Int, y: Int, magnification: Double, phase: GesturePhase) {
        try? session?.sendMagnify(Int32(x), y: Int32(y), magnification: magnification, phase: Self.phaseValue(phase))
    }

    func sendRotate(x: Int, y: Int, degrees: Double, phase: GesturePhase) {
        try? session?.sendRotate(Int32(x), y: Int32(y), degrees: degrees, phase: Self.phaseValue(phase))
    }

    func sendScrollPhase(x: Int, y: Int, deltaX: Double, deltaY: Double, phase: GesturePhase) {
        let pointsPerPixel = handler.pointsPerPixel
        try? session?.sendScrollPhase(
            Int32(x), y: Int32(y), deltaX: deltaX * pointsPerPixel, deltaY: deltaY * pointsPerPixel, phase: Self.phaseValue(phase))
    }

    func startScrollMomentum(x: Int, y: Int, velocityX: Double, velocityY: Double) {
        let pointsPerPixel = handler.pointsPerPixel
        try? session?.startScrollMomentum(
            Int32(x), y: Int32(y), velocityX: velocityX * pointsPerPixel, velocityY: velocityY * pointsPerPixel)
    }

    func stopScrollMomentum() {
        session?.stopScrollMomentum()
    }

    func sendSystemCommand(_ command: SystemCommand) {
        let value =
            switch command {
            case .missionControl: LibsharingSystemCommandMissionControl
            case .appWindows: LibsharingSystemCommandAppWindows
            case .showDesktop: LibsharingSystemCommandShowDesktop
            case .launchpad: LibsharingSystemCommandLaunchpad
            }
        try? session?.sendSystemCommand(value)
    }

    func setAssistCursor(x: Int, y: Int, pressed: Bool) {
        try? session?.setAssistCursor(Int32(x), y: Int32(y), pressed: pressed)
    }

    func hideAssistCursor() {
        try? session?.hideAssistCursor()
    }

    func setMode(_ mode: SessionMode) {
        let value =
            switch mode {
            case .control: LibsharingModeControl
            case .observe: LibsharingModeObserve
            }
        try? session?.setMode(value)
    }

    func selectDisplay(_ selection: DisplaySelection) {
        switch selection {
        case .all: try? session?.selectDisplay(LibsharingAllDisplays)
        case .display(let id): try? session?.selectDisplay(id)
        }
    }

    func setQuality(_ quality: SessionQuality) {
        try? session?.setQuality(Self.quality(quality))
    }

    func startHighPerformance() {
        lock.lock()
        let supported = supportsHighPerformance
        lock.unlock()
        if supported {
            startHighPerformanceWithConnection()
        } else {
            handler.reportMediaFailure(MediaFailure(.unsupported))
        }
    }

    private func connected(supportsHighPerformance: Bool) {
        lock.lock()
        self.supportsHighPerformance = supportsHighPerformance
        if !supportsHighPerformance {
            highPerformanceRunning = false
        }
        lock.unlock()
    }

    func startHighPerformanceWithConnection() {
        guard let session else { return }
        lock.lock()
        guard !highPerformanceRunning else {
            lock.unlock()
            return
        }
        highPerformanceRunning = true
        lock.unlock()
        let options: LibsharingHighPerformanceOptions
        do {
            options = try highPerformanceOptions()
        } catch {
            lock.lock()
            highPerformanceRunning = false
            lock.unlock()
            handler.reportMediaFailure(MediaFailure(.undecodable, message: error.localizedDescription))
            return
        }
        do {
            try session.startHighPerformance(options)
        } catch {
            lock.lock()
            highPerformanceRunning = false
            lock.unlock()
            handler.reportMediaFailure(MediaFailure(.other, message: error.localizedDescription))
        }
    }

    private func highPerformanceOptions() throws -> LibsharingHighPerformanceOptions {
        let options = LibsharingNewHighPerformanceOptions()!
        let capabilities = DecoderCapabilities.current
        guard capabilities.supportsHEVC || capabilities.supportsH264 else { throw MessageError("this device has no hardware decoder for HEVC or H.264") }
        options.supportedCodecs =
            (capabilities.supportsHEVC ? LibsharingDecoderCodecHEVC : 0)
            | (capabilities.supportsH264 ? LibsharingDecoderCodecH264 : 0)
        let network = configuration.networkConfiguration
        options.pixelFormats = network.chroma.pixelFormats(available: capabilities.pixelFormats)
        if network.chroma == .yuv444 && capabilities.supportsFullChroma {
            options.supportedCodecs = LibsharingDecoderCodecHEVC
        }
        lock.lock()
        options.audio = receivesAudio
        lock.unlock()
        options.videoMode = network.usesCoreDevice ? LibsharingVideoModeCoreDevice : LibsharingVideoModeRemoteDesktop
        options.bands = Int32(network.bands)
        options.immediatePlayout = network.schedulesPresentation
        return options
    }

    func uploadFile(_ localURL: URL, remoteFolder: String) throws -> Int64 {
        guard let session else { throw MessageError("not connected") }
        let transfer = try session.uploadFile(localURL.path, remoteFolder: remoteFolder)
        let id = transfer.transferID()
        lock.lock()
        transfers[id] = transfer
        lock.unlock()
        return id
    }

    func downloadFile(_ remotePath: String, localFolder: URL) throws -> Int64 {
        guard let session else { throw MessageError("not connected") }
        let transfer = try session.downloadFile(remotePath, localFolder: localFolder.path)
        let id = transfer.transferID()
        lock.lock()
        transfers[id] = transfer
        lock.unlock()
        return id
    }

    func downloadFiles(_ remotePaths: [String], localFolder: URL) throws {
        guard let session else { throw MessageError("not connected") }
        try session.downloadFiles(remotePaths.joined(separator: "\n"), localFolder: localFolder.path)
    }

    func setCursorAssets(_ assets: MacCursorAssets?) {
        handler.setCursorAssets(assets)
    }

    func startDrop(names: [String]) throws -> Int64 {
        guard let session else { throw MessageError("not connected") }
        var id: Int64 = 0
        try session.startDrop(names.joined(separator: "\n"), ret0_: &id)
        return id
    }

    func cancelDrag(_ id: Int64) {
        try? session?.cancelDrag(id)
    }

    func cancelRemoteDrag() throws {
        guard let session else { throw MessageError("not connected") }
        try session.cancelRemoteDrag()
    }

    func cancelTransfer(_ id: Int64) {
        lock.lock()
        let transfer = transfers[id]
        lock.unlock()
        try? transfer?.cancel()
    }

    fileprivate func transferFinished(_ id: Int64) {
        lock.lock()
        transfers[id] = nil
        lock.unlock()
    }

    func setReceivesAudio(_ receivesAudio: Bool) {
        lock.lock()
        let changed = receivesAudio != self.receivesAudio
        self.receivesAudio = receivesAudio
        let running = highPerformanceRunning
        lock.unlock()
        guard changed, running, let session, let options = try? highPerformanceOptions() else { return }
        lock.lock()
        restartingHighPerformance = true
        lock.unlock()
        do {
            try session.restartHighPerformance(options)
        } catch {
            lock.lock()
            restartingHighPerformance = false
            lock.unlock()
        }
    }

    func sendMicrophoneAudio(_ data: Data) {
        try? session?.sendMicrophoneAudio(data)
    }

    func requestRefresh() {
        try? session?.requestRefresh()
    }

    func requestKeyframe() {
        try? session?.requestKeyframe()
    }

    func statistics() -> SessionStatistics? {
        guard let statistics = session?.statistics() else { return nil }
        return SessionStatistics(
            roundTripMilliseconds: statistics.roundTripMilliseconds,
            receivedBytes: statistics.receivedBytes,
            encoding: statistics.encoding.isEmpty ? nil : statistics.encoding,
            packets: statistics.udp ? PacketStatistics(received: statistics.packets, expected: statistics.packetsExpected) : nil,
            video: statistics.highPerformance ? VideoStreamStatistics(losses: statistics.videoLosses) : nil
        )
    }

    var displayWidthMultiple: Int {
        session.map { Int($0.displayWidthMultiple()) } ?? 1
    }

    func setVirtualDisplay(_ mode: VirtualDisplayMode, largestSide: Int) {
        try? session?.setVirtualDisplay(
            Int32(mode.width), height: Int32(mode.height), density: Double(mode.density),
            maximumWidth: Int32(largestSide), maximumHeight: Int32(largestSide))
    }

    func setVisible(_ visible: Bool) {
        try? session?.setVisible(visible)
    }

    func setKeepsAwake(_ enabled: Bool) {
        try? session?.setKeepsAwake(enabled)
    }

    func setHDR(_ enabled: Bool) {
        try? session?.setHDR(enabled)
    }

    func setUpdateRate(_ rate: Int) {
        try? session?.setUpdateRate(Int32(rate))
    }

    func setClipboardSharing(_ enabled: Bool) {
        handler.forwardsClipboard = enabled
        try? session?.setSharedClipboard(enabled)
    }

    func sendClipboard(_ content: ClipboardContent, pasting: Bool) throws {
        guard let session else { throw MessageError("not connected") }
        let clipboard = LibsharingNewClipboard()!
        for item in content {
            let clipboardItem = LibsharingNewClipboardItem()!
            for (type, data) in item {
                clipboardItem.addFlavor(type, data: data)
            }
            clipboard.add(clipboardItem)
        }
        if pasting {
            try session.paste(clipboard)
        } else {
            try session.setClipboard(clipboard)
        }
    }

    func requestClipboard() throws {
        guard let session else { throw MessageError("not connected") }
        handler.forceNextClipboard = true
        do {
            try session.requestClipboard()
        } catch {
            handler.forceNextClipboard = false
            throw error
        }
    }

    private static func quality(_ quality: SessionQuality) -> Int32 {
        switch quality {
        case .adaptive: LibsharingQualityAdaptive
        case .full: LibsharingQualityFull
        }
    }
}

nonisolated private final class LibsharingHandler: NSObject, LibsharingSessionHandlerProtocol, @unchecked Sendable {
    private let lock = NSLock()
    private weak var delegate: SessionEventSink?
    weak var session: LibsharingSession?
    private var framebuffer: Framebuffer?
    private var framebufferFailure: String?
    private var info: SessionInfo
    private var capabilities: SessionCapabilities = []
    private var clipboardForwarded = true
    private var clipboardForced = false
    private var screenPointsPerPixel = 0.5
    var onConnected: (@Sendable (Bool) -> Void)?
    var onMediaStopped: (@Sendable () -> Void)?
    var onTransferFinished: (@Sendable (Int64) -> Void)?
    private let cursorLock = NSRecursiveLock()
    private var cursorAssets: MacCursorAssets?
    private var lastCursor: LibsharingCursorImage?

    init(delegate: SessionEventSink, remoteProtocol: RemoteProtocol, platform: RemotePlatform?) {
        self.delegate = delegate
        info = SessionInfo(remoteProtocol: remoteProtocol, platform: platform)
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    var forwardsClipboard: Bool {
        get { locked { clipboardForwarded } }
        set { locked { clipboardForwarded = newValue } }
    }

    var forceNextClipboard: Bool {
        get { locked { clipboardForced } }
        set { locked { clipboardForced = newValue } }
    }

    var pointsPerPixel: Double {
        locked { screenPointsPerPixel }
    }

    private func updateInfo(_ body: (inout SessionInfo) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        body(&info)
        delegate?.sessionInfoChanged(info)
    }

    func onStateChanged(_ state: Int32, errorCode: Int32, message: String?) {
        guard let delegate else { return }
        switch state {
        case LibsharingStateConnecting:
            delegate.sessionPhaseChanged(.connecting)
        case LibsharingStateAuthenticating:
            delegate.sessionPhaseChanged(.authenticating)
        case LibsharingStateWaitingForApproval:
            delegate.sessionPhaseChanged(.waitingForPermission)
        case LibsharingStateChoosingSession:
            break
        case LibsharingStateConnected:
            onConnected?(locked { capabilities.contains(.highPerformance) })
            delegate.sessionPhaseChanged(.connected)
        case LibsharingStateDisconnected:
            let text = locked { framebufferFailure } ?? message ?? ""
            switch errorCode {
            case LibsharingErrorNone, LibsharingErrorCancelled:
                delegate.sessionPhaseChanged(.disconnected)
            case LibsharingErrorAuthentication:
                delegate.sessionPhaseChanged(.failed(SessionFailure(.authenticationFailed, message: text)))
            case LibsharingErrorPermissionDenied:
                delegate.sessionPhaseChanged(.failed(SessionFailure(.permissionDenied, message: text)))
            case LibsharingErrorHostNotFound:
                delegate.sessionPhaseChanged(.failed(SessionFailure(.hostNotFound, message: text)))
            case LibsharingErrorOwnScreen:
                delegate.sessionPhaseChanged(.failed(SessionFailure(.ownScreen, message: text)))
            case LibsharingErrorNetwork:
                let started = locked { info.framebufferWidth > 0 }
                delegate.sessionPhaseChanged(.failed(SessionFailure(started ? .connectionLost : .unreachable, message: text)))
            case LibsharingErrorSessionEnded:
                delegate.sessionPhaseChanged(.failed(SessionFailure(.closedByServer, message: text)))
            case LibsharingErrorUnsupportedServer:
                delegate.sessionPhaseChanged(.failed(SessionFailure(.unsupported, message: text)))
            case LibsharingErrorCertificateChanged:
                let fingerprint = locked { info.fingerprint }
                delegate.sessionPhaseChanged(.failed(SessionFailure(.certificateChanged(fingerprint: fingerprint), message: text)))
            case LibsharingErrorUnverifiedServer:
                delegate.sessionPhaseChanged(.failed(SessionFailure(.unverifiedServer, message: text)))
            default:
                let started = locked { info.framebufferWidth > 0 }
                delegate.sessionPhaseChanged(.failed(SessionFailure(started ? .ended : .other, message: text)))
            }
        default:
            break
        }
    }

    func onServerInfo(_ serverInfo: LibsharingServerInfo?) {
        guard let serverInfo else { return }
        var capabilities: SessionCapabilities = []
        if !serverInfo.observeOnly { capabilities.insert(.control) }
        if serverInfo.supportsDisplaySelection { capabilities.insert(.displaySelection) }
        if serverInfo.supportsClipboard { capabilities.insert(.clipboard) }
        if serverInfo.supportsVirtualDisplay { capabilities.insert(.virtualDisplay) }
        if serverInfo.supportsKeyboardSource { capabilities.insert(.keyboardSource) }
        if serverInfo.supportsKeyCodes { capabilities.insert(.keyCodes) }
        if serverInfo.supportsHighPerformance { capabilities.insert(.highPerformance) }
        if serverInfo.supportsFileTransfer { capabilities.insert(.fileTransfer) }
        if serverInfo.supportsDragToRemote { capabilities.insert(.dragToRemote) }
        if serverInfo.supportsDragFromRemote { capabilities.insert(.dragFromRemote) }
        if serverInfo.supportsGestures { capabilities.insert(.gestures) }
        if serverInfo.supportsAssistCursor { capabilities.insert(.assistCursor) }
        if serverInfo.supportsMissionControl { capabilities.insert(.missionControl) }
        if serverInfo.supportsAppWindows { capabilities.insert(.appWindows) }
        if serverInfo.supportsShowDesktop { capabilities.insert(.showDesktop) }
        if serverInfo.supportsLaunchpad { capabilities.insert(.launchpad) }
        if serverInfo.supportsQuality { capabilities.insert(.quality) }
        if serverInfo.supportsObserve { capabilities.insert(.observe) }
        if serverInfo.supportsAudio { capabilities.insert(.audio) }
        if serverInfo.supportsClipboardRequest { capabilities.insert(.clipboardRequest) }
        if serverInfo.supportsUnicodeText { capabilities.insert(.unicodeText) }
        if serverInfo.supportsTouch { capabilities.insert(.touch) }
        locked { self.capabilities = capabilities }
        delegate?.sessionCapabilitiesChanged(capabilities)
        updateInfo { info in
            info.dialect =
                switch serverInfo.dialect {
                case LibsharingDialectApple: .apple
                case LibsharingDialectRDP: .rdp
                default: .standard
                }
            info.platform = serverInfo.platform == LibsharingPlatformMac ? .mac : .pc
            info.cursorPixelsPerPoint = max(serverInfo.cursorPixelsPerPoint, 1)
            info.framebufferWidth = Int(serverInfo.width)
            info.framebufferHeight = Int(serverInfo.height)
        }
        if serverInfo.observeOnly {
            delegate?.sessionModeChanged(.observe)
        }
    }

    func onServerCertificate(_ fingerprint: String?) {
        updateInfo { info in
            info.fingerprint = fingerprint ?? ""
        }
    }

    func onFramebufferResize(_ width: Int32, height: Int32) {
        guard let session, let delegate else { return }
        let attached: Framebuffer?
        let failure: String?
        do {
            guard let framebuffer = try delegate.sessionNeedsFramebuffer(width: Int(width), height: Int(height)) else { return }
            try session.attachFramebuffer(
                Int64(Int(bitPattern: framebuffer.baseAddress)),
                width: width, height: height, bytesPerRow: Int32(framebuffer.bytesPerRow)
            )
            attached = framebuffer
            failure = nil
        } catch {
            let text = "attach a \(width)x\(height) framebuffer: \(error.localizedDescription)"
            LibsharingLog.logger.error("\(text, privacy: .public)")
            attached = nil
            failure = text
        }
        locked {
            self.framebuffer = attached
            framebufferFailure = failure
        }
        updateInfo { info in
            info.framebufferWidth = Int(width)
            info.framebufferHeight = Int(height)
        }
    }

    func onFramebufferUpdate(_ update: LibsharingFramebufferUpdate?) {
        guard let update else { return }
        delegate?.sessionFramebufferUpdated(
            CGRect(x: Int(update.x), y: Int(update.y), width: Int(update.width), height: Int(update.height)),
            arrival: CACurrentMediaTime() - Double(max(update.holdNanoseconds, 0)) / 1_000_000_000)
    }

    func onDisplays(_ displays: LibsharingDisplayList?) {
        guard let displays else { return }
        let selected = (0..<displays.len()).lazy.compactMap { displays.get($0) }.first { $0.displayID == displays.current }
        let pointsWide = selected?.logicalWidth ?? displays.desktopWidth
        if displays.framebufferWidth > 0, pointsWide > 0 {
            let ratio = Double(pointsWide) / Double(displays.framebufferWidth)
            locked { screenPointsPerPixel = ratio }
            updateInfo { info in
                info.framebufferPixelsPerCursorPixel = 1 / ratio
            }
        }
        var result: [RemoteDisplay] = []
        var physicalIndex = 0
        for index in 0..<displays.len() {
            guard let display = displays.get(index) else { continue }
            let name: String
            if display.isVirtual {
                name = String(localized: "Virtual Display")
            } else if display.isMain {
                name = String(localized: "Main Display")
            } else {
                physicalIndex += 1
                name = String(localized: "Display \(physicalIndex + 1)")
            }
            result.append(
                RemoteDisplay(
                    id: display.displayID,
                    name: name,
                    width: Int(display.logicalWidth),
                    height: Int(display.logicalHeight),
                    isMain: display.isMain,
                    isVirtual: display.isVirtual
                ))
        }
        result.sort { lhs, rhs in lhs.isMain && !rhs.isMain }
        let selection: DisplaySelection = displays.current == LibsharingAllDisplays ? .all : .display(displays.current)
        delegate?.sessionDisplaysChanged(result, selection: selection)
    }

    func setCursorAssets(_ assets: MacCursorAssets?) {
        cursorLock.lock()
        defer { cursorLock.unlock() }
        cursorAssets = assets
        if let lastCursor { onCursorImage(lastCursor) }
    }

    func onCursorImage(_ cursor: LibsharingCursorImage?) {
        cursorLock.lock()
        defer { cursorLock.unlock() }
        lastCursor = cursor
        guard let cursor, cursor.width > 0, cursor.height > 0, let pixels = cursor.pixels,
            pixels.count >= Int(cursor.width) * Int(cursor.height) * 4,
            let provider = CGDataProvider(data: pixels as CFData),
            let image = CGImage(
                width: Int(cursor.width), height: Int(cursor.height), bitsPerComponent: 8, bitsPerPixel: 32,
                bytesPerRow: Int(cursor.width) * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue),
                provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
            )
        else {
            delegate?.sessionCursorChanged(nil)
            return
        }
        let fingerprint = StandardCursor.fingerprint(
            width: Int(cursor.width), height: Int(cursor.height), hotspotX: Int(cursor.hotspotX), hotspotY: Int(cursor.hotspotY), pixels: pixels)
        var inversion: CGImage?
        if let mask = cursor.invert, mask.count == Int(cursor.width) * Int(cursor.height), let provider = CGDataProvider(data: mask as CFData) {
            inversion = CGImage(
                width: Int(cursor.width), height: Int(cursor.height), bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: Int(cursor.width),
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
            )
        }
        var shape = CursorShape(
            image: image, hotspot: CGPoint(x: Int(cursor.hotspotX), y: Int(cursor.hotspotY)),
            kind: fingerprint.flatMap(StandardCursor.init(fingerprint:)), inversion: inversion)
        if let fingerprint, let sharp = cursorAssets?.image(for: fingerprint) {
            shape.image = sharp
            shape.scale = MacCursorAssets.scale
        }
        delegate?.sessionCursorChanged(shape)
    }

    func onCursorPosition(_ x: Int32, y: Int32) {
        delegate?.sessionCursorMoved(x: Int(x), y: Int(y))
    }

    func onClipboard(_ clipboard: LibsharingClipboard?) {
        guard let clipboard else { return }
        let forwarded = locked {
            let forwarded = clipboardForwarded || clipboardForced
            clipboardForced = false
            return forwarded
        }
        guard forwarded else { return }
        var content: ClipboardContent = []
        for index in 0..<clipboard.len() {
            guard let item = clipboard.get(index) else { continue }
            var flavors: [String: Data] = [:]
            for flavorIndex in 0..<item.len() {
                guard let flavor = item.get(flavorIndex), !flavor.promise, let data = flavor.data else { continue }
                flavors[flavor.type] = data
            }
            if !flavors.isEmpty {
                content.append(flavors)
            }
        }
        delegate?.sessionClipboardReceived(content)
    }

    func onKeyboardSource(_ sourceID: String?, secureInput: Bool) {
        delegate?.sessionKeyboardSourceChanged(sourceID, secureInput: secureInput)
    }

    func onPrompt(_ prompt: LibsharingPrompt?) {
        guard let prompt else { return }
        delegate?.sessionPhaseChanged(
            .choosingSession(
                SessionChoicePrompt(
                    id: prompt.promptID,
                    consoleUser: prompt.consoleUser,
                    allowsShareConsole: prompt.allowsShareConsole,
                    allowsOwnSession: prompt.allowsOwnSession
                )))
    }

    func onStatus(_ status: Int32) {
        switch status {
        case LibsharingStatusControlAllowed:
            let capabilities = locked {
                self.capabilities.insert(.control)
                return self.capabilities
            }
            delegate?.sessionCapabilitiesChanged(capabilities)
        case LibsharingStatusObserveOnly:
            let capabilities = locked {
                self.capabilities.remove(.control)
                return self.capabilities
            }
            delegate?.sessionCapabilitiesChanged(capabilities)
            delegate?.sessionModeChanged(.observe)
        case LibsharingStatusPointerHidden: delegate?.sessionEvent(.pointerVisible(false))
        case LibsharingStatusPointerShown: delegate?.sessionEvent(.pointerVisible(true))
        default: break
        }
    }

    func onBell() {
        delegate?.sessionEvent(.bell)
    }

    func onClipboardProblem(_ kind: Int32, message: String?) {
        if kind == LibsharingClipboardProblemNotRestored {
            delegate?.sessionEvent(.clipboardNotRestored(message ?? ""))
        } else {
            delegate?.sessionEvent(.clipboardUnreadable(message ?? ""))
        }
    }

    func onVideoFormat(_ format: LibsharingVideoFormat?) {
        guard let format else { return }
        var parameterSets: [Data] = []
        for index in 0..<format.len() {
            if let data = format.get(index) { parameterSets.append(data) }
        }
        delegate?.sessionVideoFormatChanged(
            VideoFormat(
                codec: format.codec == LibsharingCodecH264 ? .h264 : .hevc,
                parameterSets: parameterSets,
                bands: Int(max(1, format.bands)),
                canvasHeight: Int(format.height),
                isHDR: format.hdr
            ))
        let codec = format.codec == LibsharingCodecH264 ? "H.264" : "HEVC"
        let chroma = format.chromaFormat == 3 ? "4:4:4" : "4:2:0"
        let hdr = format.hdr
        updateInfo { $0.videoCodec = hdr ? "\(codec) \(chroma) HDR" : "\(codec) \(chroma)" }
    }

    func onVideoAccessUnit(_ unit: LibsharingVideoAccessUnit?) {
        guard let unit, let data = unit.data else { return }
        let now = CACurrentMediaTime()
        delegate?.sessionReceivedVideo(
            VideoAccessUnit(
                data: data,
                isSync: unit.sync,
                presentationTime: CMTime(value: unit.timestamp, timescale: max(1, unit.clockRate)),
                band: Int(unit.band),
                bands: Int(unit.bands),
                frameID: unit.frameID,
                frameEnd: unit.frameEnd,
                changedBands: Int(unit.changedBands),
                discontinuity: unit.discontinuity,
                turns: PictureTurns(Int(unit.turns)),
                arrival: now - Double(max(unit.holdNanoseconds, 0)) / 1_000_000_000,
                composed: unit.composed ? now - Double(unit.composeNanoseconds) / 1_000_000_000 : nil
            ))
    }

    func onAudioFormat(_ format: LibsharingAudioFormat?) {
        guard let format else { return }
        delegate?.sessionAudioFormatChanged(AudioFormat(format))
    }

    func onAudioPacket(_ packet: LibsharingAudioPacket?) {
        guard let packet, let data = packet.data else { return }
        delegate?.sessionReceivedAudio(AudioAccessUnit(data: data, timestamp: packet.timestamp, lost: Int(packet.lost)))
    }

    func onMicrophone(_ format: LibsharingAudioFormat?) {
        delegate?.sessionMicrophoneChanged(format.map(AudioFormat.init))
    }

    func onMediaStopped(_ errorCode: Int32, message: String?) {
        onMediaStopped?()
        updateInfo { $0.videoCodec = nil }
        let kind: MediaFailure.Kind? =
            switch errorCode {
            case LibsharingMediaErrorNone: nil
            case LibsharingMediaErrorBusy: .busy
            case LibsharingMediaErrorNetwork: .network
            case LibsharingMediaErrorRefused: .refused
            case LibsharingMediaErrorSilent: .silent
            default: .other
            }
        delegate?.sessionVideoStopped(failure: kind.map { MediaFailure($0, message: message ?? "") })
    }

    func onDeviceInfo(_ model: String?, deviceColor: String?, enclosureColor: String?) {
        func known(_ value: String?) -> String {
            guard let value, value.lowercased() != "unknown" else { return "" }
            return value
        }
        let model = known(model)
        guard !model.isEmpty else { return }
        let color = known(enclosureColor).isEmpty ? known(deviceColor) : known(enclosureColor)
        delegate?.sessionDeviceInfo(DeviceInfo(model: model, enclosureColor: color))
    }

    func onFileTransferProgress(_ transferID: Int64, transferred: Int64, total: Int64) {
        delegate?.sessionFileTransferProgress(id: transferID, transferred: transferred, total: total)
    }

    func onFileTransferFinished(_ transferID: Int64, name: String?, errorCode: Int32, message: String?) {
        onTransferFinished?(transferID)
        let kind: FileTransferError.Kind? =
            switch errorCode {
            case LibsharingFileErrorNone: nil
            case LibsharingFileErrorCancelled: .cancelled
            case LibsharingFileErrorNotAllowed: .notAllowed
            case LibsharingFileErrorNotFound: .notFound
            case LibsharingFileErrorNotWritable: .notWritable
            case LibsharingFileErrorDiskFull: .diskFull
            case LibsharingFileErrorPermission: .permission
            case LibsharingFileErrorLocal: .local
            case LibsharingFileErrorConnection: .connection
            case LibsharingFileErrorObserveOnly: .observeOnly
            case LibsharingFileErrorUnsupported: .unsupported
            default: .failed
            }
        delegate?.sessionFileTransferFinished(id: transferID, name: name, error: kind.map { FileTransferError($0, message: message ?? "") })
    }

    func onDropDestination(_ dragID: Int64, path: String?) {
        guard let path else { return }
        delegate?.sessionDropDestination(id: dragID, path: path)
    }

    func onRemoteDrag(_ drag: LibsharingRemoteDrag?) {
        guard let drag else {
            delegate?.sessionRemoteDragChanged([], image: nil)
            return
        }
        delegate?.sessionRemoteDragChanged(
            (0..<drag.len()).map { RemoteDragItem(path: drag.path($0), isFolder: drag.isFolder($0)) }, image: drag.image)
    }

    func reportMediaFailure(_ failure: MediaFailure) {
        delegate?.sessionVideoStopped(failure: failure)
    }
}

nonisolated extension AudioFormat {
    init(_ format: LibsharingAudioFormat) {
        self.init(
            formatID: AudioFormatID(truncatingIfNeeded: format.formatID),
            sampleRate: Double(format.sampleRate),
            channels: UInt32(max(1, format.channels)),
            framesPerPacket: UInt32(max(1, format.framesPerPacket)),
            magicCookie: format.magicCookie ?? Data()
        )
    }
}

nonisolated extension SessionCredentials {
    func apply(to options: LibsharingSessionOptions, observes: Bool) {
        options.username = username
        switch self {
        case .account(_, let password):
            options.loginType = LibsharingLoginAccount
            options.password = password
        case .vncPassword(let password):
            options.loginType = LibsharingLoginVNCPassword
            options.password = password
        case .askForPermission:
            options.loginType = observes ? LibsharingLoginRequestObserve : LibsharingLoginRequestControl
        case .noAuthentication:
            options.loginType = LibsharingLoginNone
        }
    }
}
