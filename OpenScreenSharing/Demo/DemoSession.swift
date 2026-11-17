#if DEBUG || DEMO
import CoreGraphics
import Foundation
import ImageIO
import QuartzCore

nonisolated struct DemoFrames: Sendable {
    let directory: URL

    func frame(_ mode: VirtualDisplayMode) async -> URL? {
        let name = "\(mode.width)x\(mode.height)@\(mode.density)"
        let url = directory.appendingPathComponent(name + ".png")
        let descriptor = open(directory.path, O_EVTONLY)
        guard descriptor >= 0 else { return nil }
        let watcher = DispatchSource.makeFileSystemObjectSource(fileDescriptor: descriptor, eventMask: .write)
        defer { watcher.cancel() }
        let changes = AsyncStream<Void> { continuation in
            watcher.setEventHandler { continuation.yield() }
        }
        watcher.setCancelHandler { close(descriptor) }
        watcher.resume()
        print("frame " + name)
        var iterator = changes.makeAsyncIterator()
        repeat {
            if FileManager.default.fileExists(atPath: url.path) { return url }
        } while await iterator.next() != nil
        return nil
    }
}

nonisolated final class DemoRemoteSession: RemoteSession, @unchecked Sendable {
    private static let virtualDisplayID: Int64 = 2
    private static let cursorPixelsPerPoint = 2

    private let sink: SessionEventSink
    private let frames: DemoFrames?
    private let queue = DispatchQueue(label: "DemoRemoteSession")
    private var info = SessionInfo(remoteProtocol: .vnc, platform: .mac)
    private var requested: VirtualDisplayMode?
    private var loading: Task<Void, Never>?
    private var framebuffer: Framebuffer?
    private var highPerformanceStarted = false
    private var videoStarted = false

    init(sink: SessionEventSink, frames: DemoFrames?) {
        self.sink = sink
        self.frames = frames
        info.cursorPixelsPerPoint = Double(Self.cursorPixelsPerPoint)
    }

    var displayWidthMultiple: Int { 1 }

    private static let capabilities: SessionCapabilities = [
        .control, .highPerformance, .clipboard, .clipboardRequest, .displaySelection, .virtualDisplay, .fileTransfer, .keyboardSource,
        .keyCodes, .gestures, .assistCursor, .missionControl, .appWindows, .showDesktop, .launchpad, .dragToRemote, .dragFromRemote,
        .quality, .observe, .touch,
    ]

    func connect() {
        queue.async { [self] in
            guard frames != nil else {
                sink.sessionPhaseChanged(.failed(SessionFailure(.other, message: "demo sessions show the frames of -DemoFrames")))
                return
            }
            sink.sessionCapabilitiesChanged(Self.capabilities)
            sink.sessionInfoChanged(info)
            sink.sessionCursorChanged(Self.arrow)
            sink.sessionPhaseChanged(.connected)
        }
    }

    func stop() {
        abort()
    }

    func abort() {
        queue.async { [self] in
            loading?.cancel()
            loading = nil
        }
    }

    func setVirtualDisplay(_ mode: VirtualDisplayMode, largestSide: Int) {
        queue.async { [self] in
            requested = mode
            loading?.cancel()
            guard let frames else { return }
            loading = Task { [weak self] in
                guard let url = await frames.frame(mode),
                    let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                    let image = CGImageSourceCreateImageAtIndex(source, 0, nil), let self
                else { return }
                queue.async { self.show(image, at: mode) }
            }
        }
    }

    func startHighPerformance() {
        startHighPerformanceWithConnection()
    }

    func startHighPerformanceWithConnection() {
        queue.async { [self] in
            highPerformanceStarted = true
            present()
        }
    }

    private func show(_ image: CGImage, at mode: VirtualDisplayMode) {
        guard mode == requested else { return }
        let framebuffer: Framebuffer
        do {
            guard let attached = try sink.sessionNeedsFramebuffer(width: mode.pixelWidth, height: mode.pixelHeight) else { return }
            framebuffer = attached
        } catch {
            sink.sessionPhaseChanged(.failed(SessionFailure(.drawingFailed, message: error.localizedDescription)))
            return
        }
        let context = CGContext(
            data: framebuffer.baseAddress, width: framebuffer.width, height: framebuffer.height, bitsPerComponent: 8,
            bytesPerRow: framebuffer.bytesPerRow, space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue)!
        context.draw(image, in: framebuffer.bounds)
        self.framebuffer = framebuffer
        info.framebufferWidth = framebuffer.width
        info.framebufferHeight = framebuffer.height
        info.framebufferPixelsPerCursorPixel = Double(mode.density)
        sink.sessionInfoChanged(info)
        sink.sessionDisplaysChanged(
            [
                RemoteDisplay(
                    id: Self.virtualDisplayID, name: String(localized: "Virtual Display"), width: mode.width, height: mode.height,
                    isMain: false, isVirtual: true)
            ],
            selection: .display(Self.virtualDisplayID))
        present()
    }

    private func present() {
        guard highPerformanceStarted, let framebuffer else { return }
        if !videoStarted {
            videoStarted = true
            info.videoCodec = "HEVC 4:2:0"
            sink.sessionInfoChanged(info)
            sink.sessionVideoStarted()
        }
        let now = CACurrentMediaTime()
        sink.sessionDecodedVideo(
            DecodedVideoFrame(
                pixelBuffer: framebuffer.pixelBuffer, strips: [], canvasHeight: framebuffer.height,
                timing: FrameTiming(arrival: now, capture: now, composed: nil)))
    }

    private static let arrow: CursorShape = {
        let scale = CGFloat(cursorPixelsPerPoint)
        let size = CGSize(width: 14, height: 22)
        let context = CGContext(
            data: nil, width: Int(size.width * scale), height: Int(size.height * scale), bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue)!
        context.translateBy(x: 0, y: size.height * scale)
        context.scaleBy(x: scale, y: -scale)
        context.addLines(between: [
            CGPoint(x: 1, y: 1), CGPoint(x: 1, y: 17), CGPoint(x: 5, y: 13), CGPoint(x: 8, y: 20), CGPoint(x: 10, y: 19),
            CGPoint(x: 7, y: 12), CGPoint(x: 12, y: 12),
        ])
        context.closePath()
        context.setFillColor(CGColor(gray: 0, alpha: 1))
        context.setStrokeColor(CGColor(gray: 1, alpha: 1))
        context.setLineWidth(1)
        context.setLineJoin(.round)
        context.drawPath(using: .fillStroke)
        return CursorShape(image: context.makeImage()!, hotspot: CGPoint(x: scale, y: scale), kind: .arrow)
    }()

    func answerSessionChoice(_ prompt: SessionChoicePrompt, choice: SessionChoice) {}
    func sendPointer(x: Int, y: Int, buttons: PointerButtons, clickCount: Int) {}
    func sendScroll(x: Int, y: Int, deltaX: Double, deltaY: Double) {}
    func sendTouches(_ touches: [RemoteTouch]) {}
    func sendKey(_ key: MacKey, keyboardType: MacKeyboardType, down: Bool) {}
    func sendText(_ text: String) throws {}
    func pasteText(_ text: String) throws {}
    func selectKeyboardSource(_ id: String) {}
    func sendMagnify(x: Int, y: Int, magnification: Double, phase: GesturePhase) {}
    func sendRotate(x: Int, y: Int, degrees: Double, phase: GesturePhase) {}
    func sendScrollPhase(x: Int, y: Int, deltaX: Double, deltaY: Double, phase: GesturePhase) {}
    func startScrollMomentum(x: Int, y: Int, velocityX: Double, velocityY: Double) {}
    func stopScrollMomentum() {}
    func sendSystemCommand(_ command: SystemCommand) {}
    func setAssistCursor(x: Int, y: Int, pressed: Bool) {}
    func hideAssistCursor() {}
    func setMode(_ mode: SessionMode) {}
    func selectDisplay(_ selection: DisplaySelection) {}
    func setQuality(_ quality: SessionQuality) {}
    func uploadFile(_ localURL: URL, remoteFolder: String) throws -> Int64 { throw FileTransferError(.unsupported) }
    func downloadFile(_ remotePath: String, localFolder: URL) throws -> Int64 { throw FileTransferError(.unsupported) }
    func downloadFiles(_ remotePaths: [String], localFolder: URL) throws { throw FileTransferError(.unsupported) }
    func setCursorAssets(_ assets: MacCursorAssets?) {}
    func startDrop(names: [String]) throws -> Int64 { throw FileTransferError(.unsupported) }
    func cancelDrag(_ id: Int64) {}
    func cancelRemoteDrag() throws {}
    func cancelTransfer(_ id: Int64) {}
    func setReceivesAudio(_ receivesAudio: Bool) {}
    func sendMicrophoneAudio(_ data: Data) {}
    func requestRefresh() {}
    func requestKeyframe() {}
    func statistics() -> SessionStatistics? { nil }
    func setVisible(_ visible: Bool) {}
    func setKeepsAwake(_ enabled: Bool) {}
    func setHDR(_ enabled: Bool) {}
    func setUpdateRate(_ rate: Int) {}
    func setClipboardSharing(_ enabled: Bool) {}
    func sendClipboard(_ content: ClipboardContent, pasting: Bool) throws {}
    func requestClipboard() throws {}
}
#endif
