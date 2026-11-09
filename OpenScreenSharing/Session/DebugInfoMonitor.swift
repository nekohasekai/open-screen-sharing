import Atomics
import Foundation
import Perception
import QuartzCore

@Perceptible
final class DebugInfoMonitor {
    struct Figures: Equatable {
        var readyPerSecond: Double?
        var shownPerSecond: Double?
        var bitsPerSecond: Double?
        var encoding: String?
        var networkMilliseconds: Double?
        var remoteMilliseconds: Double?
        var deviceMilliseconds: Double?
        var lossy: Bool
        var loss: Double?
    }

    struct Span: Equatable {
        var lateness: TimeInterval?
        var stutters = 0
        var seconds = 0
    }

    private struct Ready {
        var picture: Int
        var arrival: CFTimeInterval
        var capture: CFTimeInterval?
        var composed: CFTimeInterval?
    }

    private struct Shown {
        var capture: CFTimeInterval
        var presentation: CFTimeInterval
    }

    static let stutter = 0.05
    static let spans = 60
    static let spanSeconds = 5
    private static let maxWaiting = 64

    private(set) var figures: Figures?
    private(set) var timeline: [Span] = []

    var stutters: Int { timeline.reduce(0) { $0 + $1.stutters } }

    nonisolated let isRunning = ManagedAtomic<Bool>(false)
    @PerceptionIgnored private var run = 0
    @PerceptionIgnored private var startedAt: CFTimeInterval = 0
    @PerceptionIgnored private var lastPicture: Int?
    @PerceptionIgnored private var lastPresentation: CFTimeInterval?
    @PerceptionIgnored private var lastShown: Shown?
    @PerceptionIgnored private var waiting: [Ready] = []
    @PerceptionIgnored private var ready = 0
    @PerceptionIgnored private var shown = 0
    @PerceptionIgnored private var delays: [TimeInterval] = []
    @PerceptionIgnored private var remoteDelays: [TimeInterval] = []
    @PerceptionIgnored private var lateness: TimeInterval?
    @PerceptionIgnored private var hitches = 0
    @PerceptionIgnored private var remoteMilliseconds: Double?
    @PerceptionIgnored private var deviceMilliseconds: Double?
    @PerceptionIgnored private var windowStart: CFTimeInterval = 0
    @PerceptionIgnored private var previous: SessionStatistics?

    var presentationRun: Int? { isRunning.load(ordering: .relaxed) ? run : nil }

    func start() {
        isRunning.store(true, ordering: .relaxed)
        run &+= 1
        startedAt = CACurrentMediaTime()
        windowStart = startedAt
        figures = nil
        timeline = []
        lastPicture = nil
        lastPresentation = nil
        lastShown = nil
        waiting = []
        ready = 0
        shown = 0
        delays = []
        remoteDelays = []
        lateness = nil
        hitches = 0
        remoteMilliseconds = nil
        deviceMilliseconds = nil
        previous = nil
    }

    func stop() {
        isRunning.store(false, ordering: .relaxed)
        run &+= 1
        waiting = []
    }

    func pictureReady(_ picture: Int, arrival: CFTimeInterval, capture: CFTimeInterval?, composed: CFTimeInterval?) {
        guard isRunning.load(ordering: .relaxed) else { return }
        if let lastPicture, picture <= lastPicture { return }
        ready += 1
        if waiting.count == Self.maxWaiting {
            waiting.removeFirst()
        }
        waiting.append(Ready(picture: picture, arrival: arrival, capture: capture, composed: composed))
    }

    func picturePresented(_ picture: Int, at time: CFTimeInterval, run: Int) {
        guard isRunning.load(ordering: .relaxed), run == self.run, time.isFinite, time > 0, time >= startedAt else { return }
        if let lastPicture, picture <= lastPicture { return }
        if let lastPresentation, time <= lastPresentation { return }
        lastPicture = picture
        lastPresentation = time
        shown += 1
        var kept: [Ready] = []
        var newest: Ready?
        var late: TimeInterval = 0
        for waited in waiting {
            guard waited.picture <= picture else {
                kept.append(waited)
                continue
            }
            let delay = time - waited.arrival
            delays.append(delay)
            if let composed = waited.composed {
                remoteDelays.append(waited.arrival - composed)
            }
            late = max(late, delay)
            newest = waited
        }
        waiting = kept
        guard let newest else { return }
        if let capture = newest.capture {
            let last = lastShown
            lastShown = Shown(capture: capture, presentation: time)
            guard let last, capture >= last.capture else { return }
            late = max(time - last.presentation - (capture - last.capture), 0)
        } else {
            lastShown = nil
        }
        lateness = max(lateness ?? 0, late)
        if late > Self.stutter {
            hitches += 1
        }
    }

    func sample(_ statistics: SessionStatistics?) {
        let now = CACurrentMediaTime()
        defer {
            previous = statistics
            windowStart = now
            ready = 0
            shown = 0
            delays.removeAll(keepingCapacity: true)
            remoteDelays.removeAll(keepingCapacity: true)
            lateness = nil
            hitches = 0
        }
        guard let statistics else {
            figures = nil
            return
        }
        let elapsed = now - windowStart
        let measured = previous != nil && elapsed > 0
        var bitsPerSecond: Double?
        if measured, let earlier = previous?.receivedBytes {
            let added = statistics.receivedBytes >= earlier ? statistics.receivedBytes - earlier : statistics.receivedBytes
            bitsPerSecond = Double(added) * 8 / elapsed
        }
        var loss: Double?
        if let packets = statistics.packets {
            var expected = packets.expected
            var received = packets.received
            if let earlier = previous?.packets, expected >= earlier.expected, received >= earlier.received {
                expected -= earlier.expected
                received -= earlier.received
            }
            loss = expected > 0 ? Double(max(expected - received, 0)) / Double(expected) : nil
        }
        var losses = 0
        if let stream = statistics.video, let earlier = previous?.video, stream.losses > earlier.losses {
            losses = Int(stream.losses - earlier.losses)
        }
        if !delays.isEmpty {
            deviceMilliseconds = delays.reduce(0, +) / Double(delays.count) * 1000
        }
        if !remoteDelays.isEmpty {
            remoteMilliseconds = remoteDelays.reduce(0, +) / Double(remoteDelays.count) * 1000
        }
        figures = Figures(
            readyPerSecond: measured ? Double(ready) / elapsed : nil,
            shownPerSecond: measured ? Double(shown) / elapsed : nil,
            bitsPerSecond: bitsPerSecond,
            encoding: statistics.encoding,
            networkMilliseconds: statistics.roundTripMilliseconds.map { $0 / 2 },
            remoteMilliseconds: remoteMilliseconds,
            deviceMilliseconds: deviceMilliseconds,
            lossy: statistics.packets != nil,
            loss: loss
        )
        guard measured else { return }
        var timeline = self.timeline
        if timeline.last.map({ $0.seconds >= Self.spanSeconds }) ?? true {
            if timeline.count == Self.spans {
                timeline.removeFirst()
            }
            timeline.append(Span())
        }
        let index = timeline.count - 1
        timeline[index].seconds += 1
        timeline[index].stutters += max(hitches, losses)
        if let lateness {
            timeline[index].lateness = max(timeline[index].lateness ?? 0, lateness)
        }
        self.timeline = timeline
    }
}
