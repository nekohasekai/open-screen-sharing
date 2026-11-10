#if os(iOS)
import SwiftUI
import UIKit

struct TrackpadView: UIViewRepresentable {
    let controller: SessionController

    func makeUIView(context: Context) -> TrackpadUIView {
        TrackpadUIView(controller: controller)
    }

    func updateUIView(_ view: TrackpadUIView, context: Context) {
        view.controller = controller
    }
}

/// A trackpad surface that drives the computer the way a Mac trackpad does:
///
/// - One finger moves the pointer, a tap clicks, and holding before moving, or tapping and then moving, drags.
/// - Two fingers scroll, pinch to zoom and rotate; a two-finger tap is a secondary click.
/// - Three fingers swipe up for Mission Control, down for App Exposé, and sideways between spaces; a three-finger
///   tap looks up the word under the pointer.
/// - Four fingers pinch in for Launchpad and spread out to show the desktop; they swipe like three.
final class TrackpadUIView: UIView {
    private static let tapSlop: CGFloat = 8
    private static let tapDuration: TimeInterval = 0.3
    private static let holdDelay: TimeInterval = 0.4
    private static let tapDragWindow: TimeInterval = 0.35
    private static let swipeDistance: CGFloat = 36
    private static let pinchThreshold: CGFloat = 0.12
    private static let rotationThreshold: CGFloat = 0.2
    private static let pageThreshold: CGFloat = 0.3

    var controller: SessionController

    private enum Mode {
        case idle
        /// Fingers are down but haven't said what they're doing yet.
        case pending
        case pointing
        case dragging
        case scrolling
        case magnifying
        case rotating
        /// The gesture has done its one thing; the rest is ignored until every finger lifts.
        case done
    }

    private var mode = Mode.idle
    private var fingers: [UITouch] = []
    private var fingerCount = 0
    private var gestureStart: TimeInterval = 0
    private var startCentroid = CGPoint.zero
    private var lastCentroid = CGPoint.zero
    private var startSpread: CGFloat = 1
    private var startAngle: CGFloat = 0
    private var lastScale: CGFloat = 1
    private var lastAngle: CGFloat = 0
    private var travel: CGFloat = 0
    private var samples: [(point: CGPoint, time: TimeInterval)] = []
    private var lastTap: (time: TimeInterval, location: CGPoint)?
    private var tapDragArmed = false
    /// Fingers of a gesture that hasn't started are coming off; what's left of them mustn't start one.
    private var isLifting = false
    private var holdTask: Task<Void, Never>?
    private let feedback = UIImpactFeedbackGenerator(style: .light)

    init(controller: SessionController) {
        self.controller = controller
        super.init(frame: .zero)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
        accessibilityLabel = String(localized: "Trackpad")
        accessibilityTraits = .allowsDirectInteraction
    }

    required init?(coder: NSCoder) {
        fatalError()
    }

    // MARK: Touches

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        let now = touches.first?.timestamp ?? CACurrentMediaTime()
        let wasIdle = fingers.isEmpty
        fingers.append(contentsOf: touches.filter { !fingers.contains($0) })
        if wasIdle {
            controller.stopScrollMomentum()
            fingerCount = fingers.count
            gestureStart = now
            mode = .pending
            isLifting = false
            let location = centroid
            tapDragArmed = fingers.count == 1 && lastTap.map {
                now - $0.time <= Self.tapDragWindow && hypot(location.x - $0.location.x, location.y - $0.location.y) <= 40
            } == true
            rebaseline(at: now)
            if fingerCount == 1 { scheduleHold() }
            return
        }
        switch mode {
        case .pending:
            // Fingers that land a moment apart are one gesture.
            fingerCount = max(fingerCount, fingers.count)
            cancelHold()
            rebaseline(at: now)
        case .pointing:
            // Adding fingers while pointing starts a gesture with all of them, as on a Mac.
            fingerCount = fingers.count
            gestureStart = now
            mode = .pending
            rebaseline(at: now)
        default:
            // The centroid jumps when a finger joins; the pointer and scrolling mustn't.
            lastCentroid = centroid
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !fingers.isEmpty, !isLifting, let time = touches.first?.timestamp else { return }
        let location = centroid
        let delta = CGPoint(x: location.x - lastCentroid.x, y: location.y - lastCentroid.y)
        lastCentroid = location
        travel = max(travel, hypot(location.x - startCentroid.x, location.y - startCentroid.y))
        samples.append((location, time))
        samples.removeAll { time - $0.time > 0.1 }

        if mode == .pending { classify() }
        switch mode {
        case .pointing, .dragging:
            movePointer(by: delta)
        case .scrolling:
            scroll(by: delta, phase: .changed)
        case .magnifying:
            let scale = spread / max(startSpread, 1)
            controller.magnify(at: controller.pointerPosition, by: Double(scale / lastScale - 1), phase: .changed)
            lastScale = scale
        case .rotating:
            let angle = self.angle
            controller.rotate(at: controller.pointerPosition, degrees: -Double(Self.normalized(angle - lastAngle)) * 180 / .pi, phase: .changed)
            lastAngle = angle
        default:
            break
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        lift(touches, cancelled: false)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        lift(touches, cancelled: true)
    }

    private func lift(_ touches: Set<UITouch>, cancelled: Bool) {
        fingers.removeAll { touches.contains($0) }
        let time = touches.first?.timestamp ?? CACurrentMediaTime()
        guard fingers.isEmpty else {
            // Lifting some fingers ends what they were doing.
            switch mode {
            case .scrolling, .magnifying, .rotating:
                finish(cancelled: cancelled, at: time)
                mode = .done
            case .pending:
                isLifting = true
            default:
                lastCentroid = centroid
            }
            return
        }
        finish(cancelled: cancelled, at: time)
        mode = .idle
        fingerCount = 0
    }

    // MARK: Gestures

    private func classify() {
        switch fingerCount {
        case 1:
            guard travel > Self.tapSlop else { return }
            cancelHold()
            if tapDragArmed {
                beginDrag()
            } else {
                mode = .pointing
            }
        case 2:
            let scale = spread / max(startSpread, 1)
            let rotation = Self.normalized(angle - startAngle)
            if abs(scale - 1) > Self.pinchThreshold {
                mode = .magnifying
                lastScale = scale
                controller.magnify(at: controller.pointerPosition, by: Double(scale - 1), phase: .began)
            } else if abs(rotation) > Self.rotationThreshold {
                mode = .rotating
                lastAngle = angle
                controller.rotate(at: controller.pointerPosition, degrees: -Double(rotation) * 180 / .pi, phase: .began)
            } else if travel > Self.tapSlop {
                mode = .scrolling
                let delta = CGPoint(x: lastCentroid.x - startCentroid.x, y: lastCentroid.y - startCentroid.y)
                scroll(by: delta, phase: .began)
            }
        default:
            let scale = spread / max(startSpread, 1)
            if fingerCount >= 4, abs(scale - 1) > Self.pageThreshold {
                mode = .done
                perform(scale < 1 ? .launchpad : .showDesktop)
            } else if travel > Self.swipeDistance {
                mode = .done
                swipe(CGPoint(x: lastCentroid.x - startCentroid.x, y: lastCentroid.y - startCentroid.y))
            }
        }
    }

    private func finish(cancelled: Bool, at time: TimeInterval) {
        cancelHold()
        switch mode {
        case .pending:
            guard !cancelled, travel <= Self.tapSlop, time - gestureStart <= Self.tapDuration * (fingerCount > 1 ? 1.5 : 1)
            else { break }
            tap(fingers: fingerCount, at: time)
        case .dragging:
            controller.release(.primary)
        case .scrolling:
            scroll(by: .zero, phase: cancelled ? .cancelled : .ended)
            if !cancelled { startMomentum(at: time) }
        case .magnifying:
            controller.magnify(at: controller.pointerPosition, by: 0, phase: cancelled ? .cancelled : .ended)
        case .rotating:
            controller.rotate(at: controller.pointerPosition, degrees: 0, phase: cancelled ? .cancelled : .ended)
        case .idle, .pointing, .done:
            break
        }
    }

    private func tap(fingers count: Int, at time: TimeInterval) {
        guard controller.isControlling else { return }
        switch count {
        case 1:
            controller.click(.primary)
            lastTap = (time, startCentroid)
        case 2:
            controller.click(.secondary)
        case 3:
            controller.sendChord(KeyChord(modifiers: [.control, .command], keysym: Keysym.character("d")))
        default:
            return
        }
        feedback.impactOccurred()
    }

    private func scheduleHold() {
        holdTask = Task { @MainActor [weak self] in
            try? await Task.sleep(seconds: Self.holdDelay)
            guard !Task.isCancelled, let self, self.mode == .pending, self.fingerCount == 1, self.fingers.count == 1 else { return }
            self.beginDrag()
        }
    }

    private func cancelHold() {
        holdTask?.cancel()
        holdTask = nil
    }

    private func beginDrag() {
        guard controller.isControlling else {
            mode = .pointing
            return
        }
        cancelHold()
        mode = .dragging
        lastTap = nil
        controller.press(.primary, clickCount: 1)
        feedback.impactOccurred()
    }

    private func swipe(_ translation: CGPoint) {
        guard controller.isControlling else { return }
        if abs(translation.x) > abs(translation.y) {
            // Fingers moving left bring in the space on the right.
            controller.sendChord(KeyChord(modifiers: [.control], key: translation.x < 0 ? .right : .left))
        } else {
            perform(translation.y < 0 ? .missionControl : .appWindows)
        }
        feedback.impactOccurred()
    }

    private func perform(_ command: SystemCommand) {
        guard controller.isControlling, controller.canPerform(command) else { return }
        controller.perform(command)
        feedback.impactOccurred()
    }

    // MARK: Pointer and scrolling

    /// Points on the device for one framebuffer pixel, so the pointer and scrolled content follow a finger the way
    /// they would on the picture itself.
    private var pictureScale: CGFloat {
        let scale = controller.screen?.displayScale ?? 0
        return scale > 0 ? scale : 1
    }

    private func movePointer(by delta: CGPoint) {
        let velocity = self.velocity
        let speed = hypot(velocity.x, velocity.y)
        // The trackpad is smaller than the picture, so it's geared higher than a finger on the picture, and speeds up
        // with quick movement like a Mac's.
        let gain = 1.6 + min(speed / 700, 2.6)
        let scale = pictureScale
        controller.movePointer(
            to: CGPoint(
                x: controller.pointerPosition.x + delta.x * gain / scale,
                y: controller.pointerPosition.y + delta.y * gain / scale
            ))
        controller.screen?.keepPointerVisible()
    }

    private func scroll(by delta: CGPoint, phase: GesturePhase) {
        let scale = pictureScale
        let pixels = CGPoint(x: delta.x / scale, y: delta.y / scale)
        if controller.sendsGesturesToMac {
            controller.scroll(byPixels: pixels, phase: phase)
        } else if phase == .began || phase == .changed {
            controller.scroll(byPixels: pixels)
        }
    }

    private func startMomentum(at time: TimeInterval) {
        guard time - (samples.last?.time ?? 0) < 0.05 else { return }
        let velocity = self.velocity
        let scale = pictureScale
        controller.startScrollMomentum(pixelsPerSecond: CGPoint(x: velocity.x / scale, y: velocity.y / scale))
    }

    // MARK: Geometry

    private func rebaseline(at time: TimeInterval) {
        let location = centroid
        startCentroid = location
        lastCentroid = location
        startSpread = spread
        startAngle = angle
        travel = 0
        samples = [(location, time)]
    }

    private var velocity: CGPoint {
        guard let first = samples.first, let last = samples.last, last.time - first.time > 0.008 else { return .zero }
        let interval = last.time - first.time
        return CGPoint(x: (last.point.x - first.point.x) / interval, y: (last.point.y - first.point.y) / interval)
    }

    private var locations: [CGPoint] {
        fingers.map { $0.location(in: self) }
    }

    private var centroid: CGPoint {
        let points = locations
        guard !points.isEmpty else { return lastCentroid }
        let count = CGFloat(points.count)
        return CGPoint(x: points.reduce(0) { $0 + $1.x } / count, y: points.reduce(0) { $0 + $1.y } / count)
    }

    private var spread: CGFloat {
        let points = locations
        guard points.count > 1 else { return 1 }
        let center = centroid
        return points.reduce(0) { $0 + hypot($1.x - center.x, $1.y - center.y) } / CGFloat(points.count)
    }

    private var angle: CGFloat {
        let points = locations
        guard points.count > 1 else { return 0 }
        return atan2(points[1].y - points[0].y, points[1].x - points[0].x)
    }

    private static func normalized(_ angle: CGFloat) -> CGFloat {
        var angle = angle
        while angle > .pi { angle -= 2 * .pi }
        while angle < -.pi { angle += 2 * .pi }
        return angle
    }
}
#endif
