import CoreGraphics
import CoreVideo
import Foundation
import QuartzCore

nonisolated enum ScreenPicture: Equatable {
    case none
    case previous(PictureChange)
    case current
}

nonisolated enum PictureChange: Equatable {
    case reconnection
    case resize
    case videoStart
}

nonisolated final class DisplayPipeline: @unchecked Sendable {
    struct Snapshot {
        var pixelBuffer: CVPixelBuffer?
        var framebuffer: Framebuffer?
        var strips: [CVPixelBuffer?] = []
        var stripHeight: Int = 0
        var canvasHeight: Int = 0
        var width: Int
        var height: Int
        var cursor: CursorShape?
        var cursorPosition: CGPoint
        var cursorVisible: Bool
        var pictureGeneration: Int?
        var presentationEpoch: Int
    }

    private struct Held {
        var pixelBuffer: CVPixelBuffer?
        var framebuffer: Framebuffer?
        var strips: [CVPixelBuffer?]
        var canvasHeight: Int
        var width: Int
        var height: Int
        var change: PictureChange
    }

    private struct Scheduled {
        var frame: DecodedVideoFrame
        var due: CFTimeInterval
        var ready: @Sendable (_ picture: Int) -> Void
    }

    private struct Copy {
        var framebuffer: Framebuffer
        var stale: CGRect
    }

    private struct Shown: Equatable {
        var picture = ScreenPicture.none
        var width = 0
        var height = 0
    }

    private static let presentationWindow: CFTimeInterval = 2
    private static let presentationQuantile = 0.9
    private static let presentationAllowance: TimeInterval = 0.001
    private static let maxPresentationSkew: TimeInterval = 0.5
    private static let maxScheduled = 4

    private let lock = NSLock()
    private let copyLock = NSLock()
    private var copies: [Copy] = []
    private var framebuffer: Framebuffer?
    private var presented: Framebuffer?
    private var framebufferUpdated = false
    private var framebufferHasPicture = false
    private var expectsVideo = false
    private var holdsVideo = false
    private var waitsForDisplay = false
    private var held: Held?
    private var videoFrame: CVPixelBuffer?
    private var strips: [CVPixelBuffer?] = []
    private var canvasHeight = 0
    private var isVideo = false
    private var width = 0
    private var height = 0
    private var cursor: CursorShape?
    private var cursorPosition = CGPoint.zero
    private var cursorVisible = true
    private var pictureGeneration = 0
    private var presentationEpoch = 0
    private var dirty = false
    private var wakeHandler: (@Sendable () -> Void)?
    private var shown = Shown()
    private var shownHandler: (@Sendable () -> Void)?
    private var schedulesPresentation = false
    private var scheduled: [Scheduled] = []
    private var consumedThrough: CFTimeInterval = 0
    private var presentationLatency: TimeInterval?
    private var latencySamples: [TimeInterval] = []
    private var latencyWindowSince: CFTimeInterval = 0
    private var latencyWarmingUp = false

    func setWakeHandler(_ handler: (@Sendable () -> Void)?) {
        lock.lock()
        wakeHandler = handler
        lock.unlock()
    }

    func setShownHandler(_ handler: (@Sendable () -> Void)?) {
        lock.lock()
        shownHandler = handler
        lock.unlock()
    }

    var screenSize: (width: Int, height: Int) {
        lock.lock()
        defer { lock.unlock() }
        return (width, height)
    }

    var currentPicture: (picture: ScreenPicture, width: Int, height: Int) {
        lock.lock()
        defer { lock.unlock() }
        return (shown.picture, shown.width, shown.height)
    }

    func isCurrentPresentation(epoch: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return epoch == presentationEpoch
    }

    func holdPicture() {
        update {
            presentationEpoch &+= 1
            resetPresentation()
            hold(.reconnection)
            framebufferUpdated = false
            framebufferHasPicture = false
            videoFrame = nil
            strips = []
        }
    }

    func attachFramebuffer(_ framebuffer: Framebuffer) {
        copyLock.lock()
        defer { copyLock.unlock() }
        copies.removeAll()
        update {
            presentationEpoch &+= 1
            resetPresentation()
            hold(.resize)
            self.framebuffer = framebuffer
            presented = nil
            framebufferUpdated = false
            framebufferHasPicture = false
            isVideo = false
            videoFrame = nil
            strips = []
            width = framebuffer.width
            height = framebuffer.height
        }
    }

    func beginVideo() {
        update {
            presentationEpoch &+= 1
            resetPresentation()
            hold(.videoStart)
            isVideo = true
            videoFrame = nil
            strips = []
        }
    }

    func endVideo() {
        update {
            presentationEpoch &+= 1
            resetPresentation()
            hold(.reconnection)
            isVideo = false
            videoFrame = nil
            strips = []
            framebufferUpdated = false
            framebufferHasPicture = false
            if let framebuffer {
                width = framebuffer.width
                height = framebuffer.height
            }
        }
    }

    var isSchedulingPresentation: Bool {
        lock.lock()
        defer { lock.unlock() }
        return isVideo && schedulesPresentation
    }

    func setSchedulesPresentation(_ schedules: Bool) {
        lock.lock()
        schedulesPresentation = schedules
        resetPresentation()
        lock.unlock()
    }

    func setVideoFrame(_ frame: DecodedVideoFrame, ready: @escaping @Sendable (_ picture: Int) -> Void) {
        let now = CACurrentMediaTime()
        lock.lock()
        guard isVideo else {
            lock.unlock()
            return
        }
        let due = schedulesPresentation ? presentationDue(capture: frame.timing.capture, at: now) : now
        scheduled.append(Scheduled(frame: frame, due: due, ready: ready))
        let handler = wakeHandler
        let horizon = max(now, consumedThrough)
        lock.unlock()
        if !promote(upTo: horizon) {
            handler?()
        }
    }

    private func presentationDue(capture: CFTimeInterval, at now: CFTimeInterval) -> CFTimeInterval {
        let offset = now - capture
        if let latency = presentationLatency, abs(offset - latency) <= Self.maxPresentationSkew {
            latencySamples.append(offset)
            let windowEnded = now - latencyWindowSince >= Self.presentationWindow
            if windowEnded || latencyWarmingUp {
                latencySamples.sort()
                presentationLatency =
                    latencySamples[Int(Double(latencySamples.count - 1) * Self.presentationQuantile)] + Self.presentationAllowance
            }
            if windowEnded {
                latencySamples.removeAll(keepingCapacity: true)
                latencyWindowSince = now
                latencyWarmingUp = false
            }
        } else {
            presentationLatency = offset
            latencySamples.removeAll(keepingCapacity: true)
            latencyWindowSince = now
            latencyWarmingUp = true
        }
        return capture + (presentationLatency ?? offset)
    }

    private func resetPresentation() {
        scheduled.removeAll()
        presentationLatency = nil
        latencySamples.removeAll()
    }

    private func dueCount(upTo time: CFTimeInterval) -> Int {
        max(scheduled.prefix { $0.due <= time }.count, scheduled.count - Self.maxScheduled)
    }

    @discardableResult
    private func promote(upTo time: CFTimeInterval) -> Bool {
        lock.lock()
        let pending = dueCount(upTo: time)
        lock.unlock()
        guard pending > 0 else { return false }
        var promoted: [(Scheduled, Int)] = []
        update {
            let count = dueCount(upTo: time)
            guard count > 0 else { return }
            for item in scheduled.prefix(count) {
                pictureGeneration &+= 1
                promoted.append((item, pictureGeneration))
            }
            let frame = scheduled[count - 1].frame
            scheduled.removeFirst(count)
            videoFrame = frame.pixelBuffer
            strips = frame.strips.map { Optional($0) }
            canvasHeight = frame.canvasHeight
        }
        for (item, picture) in promoted {
            item.ready(picture)
        }
        return !promoted.isEmpty
    }

    func updateFramebuffer(_ dirty: CGRect) throws -> Int? {
        copyLock.lock()
        defer { copyLock.unlock() }
        for index in copies.indices {
            copies[index].stale = copies[index].stale.union(dirty)
        }
        lock.lock()
        let framebuffer = isVideo ? nil : framebuffer
        lock.unlock()
        guard let framebuffer else { return nil }
        let target: Int
        if let free = copies.indices.first(where: { isKnownUniquelyReferenced(&copies[$0].framebuffer) }) {
            target = free
        } else {
            copies.append(Copy(framebuffer: try Framebuffer(width: framebuffer.width, height: framebuffer.height), stale: framebuffer.bounds))
            target = copies.endIndex - 1
        }
        let picture = copies[target].framebuffer
        picture.copy(copies[target].stale, from: framebuffer)
        copies[target].stale = .null
        var taken: Int?
        update {
            presented = picture
            guard !isVideo else { return }
            framebufferUpdated = true
            guard framebufferHasPicture || !expectsVideo else { return }
            pictureGeneration &+= 1
            taken = pictureGeneration
            framebufferHasPicture = true
        }
        var spares = 0
        for index in copies.indices.reversed() where isKnownUniquelyReferenced(&copies[index].framebuffer) {
            spares += 1
            if spares > 1 {
                copies.remove(at: index)
            }
        }
        return taken
    }

    func setExpectsVideo(_ expects: Bool) {
        update {
            expectsVideo = expects
            guard !expects, !isVideo, framebufferUpdated, !framebufferHasPicture else { return }
            pictureGeneration &+= 1
            framebufferHasPicture = true
        }
    }

    func setHoldsVideo(_ holds: Bool) {
        update { holdsVideo = holds }
    }

    func setWaitsForDisplay(_ waits: Bool) {
        update {
            if waits {
                hold(.resize)
            }
            waitsForDisplay = waits
        }
    }

    func setCursor(_ cursor: CursorShape?) {
        update { self.cursor = cursor }
    }

    func setCursorPosition(_ position: CGPoint, visible: Bool) {
        update {
            cursorPosition = position
            cursorVisible = visible
        }
    }

    func consume(through time: CFTimeInterval) -> Snapshot? {
        promote(upTo: time)
        lock.lock()
        defer { lock.unlock() }
        consumedThrough = max(consumedThrough, time)
        guard dirty else { return nil }
        dirty = false
        return snapshotLocked()
    }

    func snapshot() -> Snapshot {
        lock.lock()
        defer { lock.unlock() }
        return snapshotLocked()
    }

    func makeThumbnail() -> Data? {
        lock.lock()
        let snapshot = shownLocked().picture == .none ? nil : snapshotLocked()
        lock.unlock()
        guard let snapshot else { return nil }
        return withExtendedLifetime(snapshot.framebuffer) {
            if let pixelBuffer = snapshot.pixelBuffer {
                return PixelBufferImage.makeThumbnail(pixelBuffer)
            }
            return PixelBufferImage.makeThumbnail(
                strips: snapshot.strips, stripHeight: snapshot.stripHeight, canvasHeight: snapshot.canvasHeight)
        }
    }

    private var hasPicture: Bool {
        guard !waitsForDisplay else { return false }
        return isVideo
            ? !holdsVideo && (videoFrame != nil || !strips.isEmpty && strips.allSatisfy { $0 != nil }) : framebufferHasPicture
    }

    private func hold(_ change: PictureChange) {
        guard hasPicture else { return }
        held = Held(
            pixelBuffer: isVideo ? videoFrame : presented?.pixelBuffer, framebuffer: isVideo ? nil : presented, strips: isVideo ? strips : [],
            canvasHeight: canvasHeight, width: width, height: height, change: change)
    }

    private func shownLocked() -> Shown {
        if hasPicture {
            Shown(picture: .current, width: width, height: height)
        } else if let held {
            Shown(picture: .previous(held.change), width: held.width, height: held.height)
        } else {
            Shown(picture: .none, width: width, height: height)
        }
    }

    private static func stripHeight(_ strips: [CVPixelBuffer?]) -> Int {
        strips.compactMap { $0 }.first.map { CVPixelBufferGetHeight($0) } ?? 0
    }

    private func snapshotLocked() -> Snapshot {
        if !hasPicture, let held {
            return Snapshot(
                pixelBuffer: held.pixelBuffer,
                framebuffer: held.framebuffer,
                strips: held.strips,
                stripHeight: Self.stripHeight(held.strips),
                canvasHeight: held.canvasHeight,
                width: held.width, height: held.height, cursor: cursor, cursorPosition: cursorPosition,
                cursorVisible: cursorVisible, pictureGeneration: nil, presentationEpoch: presentationEpoch
            )
        }
        guard hasPicture else {
            return Snapshot(
                width: width, height: height, cursor: cursor, cursorPosition: cursorPosition, cursorVisible: cursorVisible,
                pictureGeneration: nil, presentationEpoch: presentationEpoch
            )
        }
        let stripHeight = Self.stripHeight(strips)
        return Snapshot(
            pixelBuffer: isVideo ? videoFrame : presented?.pixelBuffer,
            framebuffer: isVideo ? nil : presented,
            strips: isVideo ? strips : [],
            stripHeight: stripHeight,
            canvasHeight: canvasHeight,
            width: width, height: height, cursor: cursor, cursorPosition: cursorPosition,
            cursorVisible: cursorVisible, pictureGeneration: pictureGeneration,
            presentationEpoch: presentationEpoch
        )
    }

    private func update(_ body: () -> Void) {
        lock.lock()
        body()
        if hasPicture {
            held = nil
        }
        let wasDirty = dirty
        dirty = true
        let handler = wakeHandler
        let newShown = shownLocked()
        let shownChanged = newShown != shown
        shown = newShown
        let shownHandler = self.shownHandler
        lock.unlock()
        if !wasDirty {
            handler?()
        }
        if shownChanged {
            shownHandler?()
        }
    }
}
