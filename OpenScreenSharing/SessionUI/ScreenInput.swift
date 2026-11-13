#if os(iOS)
import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

final class ScreenInput: NSObject, UIGestureRecognizerDelegate, UIPointerInteractionDelegate {
    private static let tapSlop: CGFloat = 10
    private static let tapDuration: TimeInterval = 0.35
    private static let multiClickInterval: TimeInterval = 0.5
    private static let pressHold: TimeInterval = 0.3
    private static let pressIndicatorDelay: TimeInterval = 0.15
    private static let mouseCountsPerPoint: CGFloat = 2
    private static let touchMargin: CGFloat = 24
    private static let doubleTapDistance: CGFloat = 40
    private static let wheelPointsPerZoomDoubling: CGFloat = 100
    // Height of the I-beam of macOS 27.0.1 26A434, SkyLight CursorAsset IBeam and IBeamS.
    private static let iBeamPoints: CGFloat = 18

    private unowned let view: RemoteScreenUIView
    private unowned let controller: SessionController

    private var pointerInteraction: UIPointerInteraction!
    private var drag: ScreenDrag!
    private var drop: ScreenDrop!
    private var secondaryTapGesture: UITapGestureRecognizer!
    private var twoFingerPanGesture: UIPanGestureRecognizer!
    private var pinchGesture: UIPinchGestureRecognizer!
    private var rotationGesture: UIRotationGestureRecognizer!

    private var fingers: Set<UITouch> = []
    private var finger: Finger? {
        didSet {
            if finger == nil { view.pressIndicator.dismiss() }
        }
    }
    private var holdTask: Task<Void, Never>?
    private var nativeTouches: [UITouch: Int64] = [:]
    private var nextNativeTouchID: Int64 = 0

    private var pointerTouches: Set<UITouch> = []
    private var lockedButtons: PointerButtons = []
    private var handleTouch: UITouch?
    private var toolbarTouches: Set<UITouch> = []
    private let systemPointerHover = UIHoverGestureRecognizer()
    private let windowTouches = TouchObserver(target: nil, action: nil)
    private var systemPointerLocation: CGPoint?
    private var systemPointerOverPicture = false {
        didSet {
            if systemPointerOverPicture != oldValue { view.setNeedsRender() }
        }
    }
    private var pointerCursor: StandardCursor?
    private var pointerBeamLength: CGFloat = 0
    private var localPointerImage: (cursor: StandardCursor, beamLength: CGFloat, displayScale: CGFloat, image: CGImage, hotspot: CGPoint)?
    private var lastLockedMoveTime: TimeInterval = 0
    private var lockedMouseSpeed: CGFloat = 0

    private var twoFingerScroll = ScrollStream()
    private var twoFingerPansView = false
    private var twoFingerTranslation = CGPoint.zero
    private var hardwareScroll = ScrollStream()
    private var hardwareScrollPansView = false
    private var hardwareScrollTranslation = CGPoint.zero
    private var wheelScroll = ScrollStream()
    private var wheelZoomsView = false
    private var wheelTranslation = CGPoint.zero

    private var zoomTouches: [UITouch: CGPoint] = [:]
    private var zoomTap: (start: CGPoint, time: TimeInterval, travel: CGFloat)?
    private var lastZoomTap: (location: CGPoint, time: TimeInterval)?

    private var pinchGoesToMac = false
    private var macPinchStarted = false
    private var previousPinchScale: CGFloat = 1
    private var pinchStartZoom: CGFloat = 1
    private var pinchLocation = CGPoint.zero
    private var pinchAnchor = CGPoint.zero
    private var macRotationStarted = false
    private var previousRotation: CGFloat = 0

    init(view: RemoteScreenUIView, controller: SessionController) {
        self.view = view
        self.controller = controller
        super.init()
        installGestures()
        drag = ScreenDrag(view: view, controller: controller, input: self)
        drop = ScreenDrop(view: view, controller: controller, drag: drag)
        pointerInteraction = UIPointerInteraction(delegate: self)
        view.addInteraction(pointerInteraction)
        PointerLock.addReceiver(self)
        NotificationCenter.default.addObserver(
            self, selector: #selector(pointerLockDidChange(_:)), name: UIPointerLockState.didChangeNotification, object: nil)
        systemPointerHover.addTarget(self, action: #selector(trackSystemPointer(_:)))
        systemPointerHover.delegate = self
        windowTouches.touchBegan = { [weak self] touch in self?.windowTouchBegan(touch) }
    }

    func windowDidChange() {
        for recognizer in [systemPointerHover, windowTouches] {
            recognizer.view?.removeGestureRecognizer(recognizer)
            view.window?.addGestureRecognizer(recognizer)
        }
    }


    private func installGestures() {
        secondaryTapGesture = UITapGestureRecognizer(target: self, action: #selector(handleSecondaryTap(_:)))
        secondaryTapGesture.numberOfTouchesRequired = 2
        secondaryTapGesture.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]

        twoFingerPanGesture = UIPanGestureRecognizer(target: self, action: #selector(handleTwoFingerPan(_:)))
        twoFingerPanGesture.minimumNumberOfTouches = 2
        twoFingerPanGesture.maximumNumberOfTouches = 2
        twoFingerPanGesture.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]

        pinchGesture = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        rotationGesture = UIRotationGestureRecognizer(target: self, action: #selector(handleRotation(_:)))

        let scrollGesture = UIPanGestureRecognizer(target: self, action: #selector(handleHardwareScroll(_:)))
        scrollGesture.allowedScrollTypesMask = .continuous
        scrollGesture.allowedTouchTypes = []

        let wheelGesture = UIPanGestureRecognizer(target: self, action: #selector(handleWheel(_:)))
        wheelGesture.allowedScrollTypesMask = .discrete
        wheelGesture.allowedTouchTypes = []

        let hoverGesture = UIHoverGestureRecognizer(target: self, action: #selector(handleHover(_:)))

        let recognizers: [UIGestureRecognizer] = [
            secondaryTapGesture, twoFingerPanGesture, pinchGesture, rotationGesture, scrollGesture, wheelGesture, hoverGesture,
        ]
        for recognizer in recognizers {
            recognizer.delegate = self
            recognizer.delaysTouchesEnded = false
            view.addGestureRecognizer(recognizer)
        }
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        if gestureRecognizer === systemPointerHover || other === systemPointerHover { return true }
        let transforms: Set<UIGestureRecognizer> = [pinchGesture, rotationGesture]
        return transforms.contains(gestureRecognizer) && transforms.contains(other)
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if controller.isToolbarVisible || drag.isDragging, !(gestureRecognizer is UIHoverGestureRecognizer) {
            return false
        }
        if controller.isZoomModeOn, gestureRecognizer.numberOfTouches > 0 || gestureRecognizer === rotationGesture {
            return false
        }
        if gestureRecognizer.numberOfTouches > 0, controller.touchesNatively || !nativeTouches.isEmpty {
            return false
        }
        if gestureRecognizer === pinchGesture || gestureRecognizer === rotationGesture, gestureRecognizer.numberOfTouches > 0,
            !AppSettings.shared.pinchZooms
        {
            return controller.sendsGesturesToMac
        }
        if gestureRecognizer === rotationGesture {
            return gestureRecognizer.numberOfTouches == 0 && controller.sendsGesturesToMac
        }
        if gestureRecognizer === secondaryTapGesture {
            return controller.isControlling
        }
        if gestureRecognizer === twoFingerPanGesture {
            return gestureRecognizer.numberOfTouches <= 2
        }
        return true
    }


    func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !drag.isDragging else { return }
        controller.stopScrollMomentum()
        view.takeKeyFocus()
        var began: [RemoteTouch] = []
        for touch in touches {
            switch touch.type {
            case .indirectPointer where isPointerLocked:
                break
            case _ where controller.isToolbarVisible:
                toolbarTouches.insert(touch)
            case _ where controller.isZoomModeOn:
                zoomTouchBegan(touch)
            case .direct, .pencil:
                if handleTouch == nil, isPointerLocked, controller.toolbarHandleFrame?.contains(touch.location(in: nil)) == true {
                    handleTouch = touch
                } else if controller.touchesNatively {
                    guard view.imageRect.insetBy(dx: -Self.touchMargin, dy: -Self.touchMargin).contains(touch.location(in: view)) else { break }
                    nextNativeTouchID += 1
                    nativeTouches[touch] = nextNativeTouchID
                    began.append(nativeTouch(touch, phase: .began))
                } else {
                    fingerBegan(touch)
                }
            case .indirectPointer: pointerBegan(touch, event: event)
            default: break
            }
        }
        controller.sendTouches(began)
    }

    func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        zoomTouchesMoved(touches)
        controller.sendTouches(touches.filter { nativeTouches[$0] != nil }.map { nativeTouch($0, phase: .moved) })
        for touch in touches where zoomTouches[touch] == nil && nativeTouches[touch] == nil {
            switch touch.type {
            case .direct, .pencil: fingerMoved(touch)
            case .indirectPointer: pointerMoved(touch, event: event)
            default: break
            }
        }
    }

    func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        let native = touches.filter { nativeTouches[$0] != nil }
        liftNativeTouches(native, phase: .ended)
        for touch in touches.subtracting(native) {
            if toolbarTouches.remove(touch) != nil {
                if controller.isToolbarVisible { controller.toggleToolbar() }
                continue
            }
            if zoomTouches[touch] != nil {
                zoomTouchEnded(touch, cancelled: false)
                continue
            }
            switch touch.type {
            case .direct, .pencil:
                if touch === handleTouch {
                    handleTouch = nil
                    if controller.toolbarHandleFrame?.contains(touch.location(in: nil)) == true { controller.toggleToolbar() }
                } else {
                    fingerEnded(touch, cancelled: false)
                }
            case .indirectPointer: pointerEnded(touch)
            default: break
            }
        }
    }

    func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        let native = touches.filter { nativeTouches[$0] != nil }
        liftNativeTouches(native, phase: .cancelled)
        for touch in touches.subtracting(native) {
            if toolbarTouches.remove(touch) != nil { continue }
            if touch === drag.takenTouch {
                fingers.remove(touch)
                pointerTouches.remove(touch)
                if touch === finger?.touch { finger = nil }
                continue
            }
            if zoomTouches[touch] != nil {
                zoomTouchEnded(touch, cancelled: true)
                continue
            }
            switch touch.type {
            case .direct, .pencil:
                if touch === handleTouch {
                    handleTouch = nil
                } else {
                    fingerEnded(touch, cancelled: true)
                }
            case .indirectPointer: pointerEnded(touch)
            default: break
            }
        }
    }


    private func nativeTouch(_ touch: UITouch, phase: TouchPhase) -> RemoteTouch {
        RemoteTouch(id: nativeTouches[touch] ?? 0, phase: phase, location: view.imagePoint(for: touch.location(in: view)))
    }

    private func liftNativeTouches(_ touches: Set<UITouch>, phase: TouchPhase) {
        controller.sendTouches(touches.map { nativeTouch($0, phase: phase) })
        for touch in touches { nativeTouches[touch] = nil }
    }


    private func zoomTouchBegan(_ touch: UITouch) {
        let location = touch.location(in: view)
        zoomTouches[touch] = location
        zoomTap = zoomTouches.count == 1 ? (location, touch.timestamp, 0) : nil
    }

    private func zoomTouchesMoved(_ touches: Set<UITouch>) {
        let previous = zoomTouches
        var current = previous
        for touch in touches where previous[touch] != nil {
            current[touch] = touch.location(in: view)
        }
        guard current != previous else { return }
        zoomTouches = current
        if let tap = zoomTap, let location = current.values.first {
            zoomTap?.travel = max(tap.travel, hypot(location.x - tap.start.x, location.y - tap.start.y))
        }
        let from = Self.centroid(of: previous.values)
        let to = Self.centroid(of: current.values)
        var factor: CGFloat = 1
        if current.count > 1 {
            let spreadBefore = Self.spread(of: previous.values, around: from)
            let spreadAfter = Self.spread(of: current.values, around: to)
            if spreadBefore > 0 { factor = spreadAfter / spreadBefore }
        }
        view.setZoom(view.zoom * factor, keeping: view.imagePoint(for: from), at: to)
    }

    private func zoomTouchEnded(_ touch: UITouch, cancelled: Bool) {
        zoomTouches[touch] = nil
        guard zoomTouches.isEmpty else { return }
        let tap = zoomTap
        zoomTap = nil
        guard !cancelled, let tap, tap.travel <= Self.tapSlop, touch.timestamp - tap.time <= Self.tapDuration else {
            lastZoomTap = nil
            return
        }
        let location = touch.location(in: view)
        if let last = lastZoomTap, touch.timestamp - last.time <= Self.multiClickInterval,
            hypot(location.x - last.location.x, location.y - last.location.y) <= Self.doubleTapDistance
        {
            lastZoomTap = nil
            view.toggleZoom(at: location)
        } else {
            lastZoomTap = (location, touch.timestamp)
        }
    }

    private static func centroid(of points: some Collection<CGPoint>) -> CGPoint {
        let count = CGFloat(points.count)
        return CGPoint(x: points.reduce(0) { $0 + $1.x } / count, y: points.reduce(0) { $0 + $1.y } / count)
    }

    private static func spread(of points: some Collection<CGPoint>, around center: CGPoint) -> CGFloat {
        points.reduce(0) { $0 + hypot($1.x - center.x, $1.y - center.y) } / CGFloat(points.count)
    }


    private final class Finger {
        enum Phase {
            case pending
            case pointing
            case dragging
        }

        let touch: UITouch
        let start: CGPoint
        let startTime: TimeInterval
        let startPointer: CGPoint
        var last: CGPoint
        var phase = Phase.pending
        var travel: CGFloat = 0
        private var samples: [(point: CGPoint, time: TimeInterval)]

        init(touch: UITouch, location: CGPoint, pointer: CGPoint) {
            self.touch = touch
            start = location
            startTime = touch.timestamp
            startPointer = pointer
            last = location
            samples = [(location, touch.timestamp)]
        }

        func record(_ location: CGPoint, at time: TimeInterval) {
            travel = max(travel, hypot(location.x - start.x, location.y - start.y))
            last = location
            samples.append((location, time))
            samples.removeAll { time - $0.time > 0.1 }
        }

        func velocity(at time: TimeInterval) -> CGPoint {
            guard let first = samples.first, let last = samples.last, time - last.time < 0.05, last.time - first.time > 0.008 else { return .zero }
            let interval = last.time - first.time
            return CGPoint(x: (last.point.x - first.point.x) / interval, y: (last.point.y - first.point.y) / interval)
        }

        func isTap(endedAt time: TimeInterval) -> Bool {
            travel <= ScreenInput.tapSlop && time - startTime <= ScreenInput.tapDuration
        }
    }

    private func fingerBegan(_ touch: UITouch) {
        fingers.insert(touch)
        guard fingers.count == 1 else {
            if let finger { end(finger, liftedAt: nil) }
            return
        }
        let next = Finger(touch: touch, location: touch.location(in: view), pointer: controller.pointerPosition)
        finger = next
        guard controller.isControlling else { return }
        view.pressIndicator.beginHold(delay: Self.pressIndicatorDelay, duration: Self.pressHold - Self.pressIndicatorDelay)
        holdTask = Task { [weak self] in
            try? await Task.sleep(seconds: Self.pressHold)
            guard !Task.isCancelled, let self, self.finger === next, next.phase == .pending else { return }
            self.beginDrag(next)
        }
    }

    private func fingerMoved(_ touch: UITouch) {
        guard let finger, finger.touch === touch else { return }
        let location = touch.location(in: view)
        let delta = CGPoint(x: location.x - finger.last.x, y: location.y - finger.last.y)
        finger.record(location, at: touch.timestamp)
        if finger.phase == .pending, finger.travel > Self.tapSlop {
            finger.phase = .pointing
            view.pressIndicator.dismiss()
        }
        let velocity = finger.velocity(at: touch.timestamp)
        movePointer(by: delta, speed: hypot(velocity.x, velocity.y))
        if finger.phase == .dragging { drag.begin(with: touch, relative: true) }
    }

    private func fingerEnded(_ touch: UITouch, cancelled: Bool) {
        fingers.remove(touch)
        guard let finger, finger.touch === touch else { return }
        end(finger, liftedAt: cancelled ? nil : touch.timestamp)
    }

    private func end(_ finger: Finger, liftedAt time: TimeInterval?) {
        self.finger = nil
        holdTask?.cancel()
        holdTask = nil
        switch finger.phase {
        case .pending:
            guard let time, finger.isTap(endedAt: time), controller.isControlling else { break }
            controller.movePointer(to: finger.startPointer)
            controller.click(.primary)
        case .pointing:
            break
        case .dragging:
            controller.release(.primary)
        }
    }

    private func beginDrag(_ finger: Finger) {
        holdTask?.cancel()
        holdTask = nil
        finger.phase = .dragging
        controller.press(.primary, clickCount: 1)
        view.pressIndicator.press()
        if #available(iOS 17.5, *) {
            UIImpactFeedbackGenerator(style: .light).impactOccurred(at: finger.last)
        } else {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }

    func movePointer(by delta: CGPoint, speed: CGFloat) {
        let gain = 1.2 + min(speed / 1200, 1.8)
        let scale = view.displayScale
        guard scale > 0 else { return }
        controller.movePointer(
            to: CGPoint(
                x: controller.pointerPosition.x + delta.x * gain / scale,
                y: controller.pointerPosition.y + delta.y * gain / scale
            ))
        view.keepPointerVisible()
    }


    private struct ScrollStream {
        var usesPhases = false
        var started = false
        var remainder = CGPoint.zero
    }

    private func scroll(_ stream: inout ScrollStream, by delta: CGPoint) {
        let scale = view.displayScale
        if stream.usesPhases {
            controller.scroll(byPixels: CGPoint(x: delta.x / scale, y: delta.y / scale), phase: stream.started ? .changed : .began)
            stream.started = true
            return
        }
        stream.remainder.x += delta.x
        stream.remainder.y += delta.y
        let sendX = stream.remainder.x.rounded(.towardZero)
        let sendY = stream.remainder.y.rounded(.towardZero)
        guard sendX != 0 || sendY != 0 else { return }
        stream.remainder.x -= sendX
        stream.remainder.y -= sendY
        controller.scroll(byPixels: CGPoint(x: sendX / scale, y: sendY / scale))
    }

    private func endScroll(_ stream: inout ScrollStream, velocity: CGPoint, cancelled: Bool) {
        guard stream.started else { return }
        stream.started = false
        controller.scroll(byPixels: .zero, phase: cancelled ? .cancelled : .ended)
        if !cancelled {
            let scale = view.displayScale
            controller.startScrollMomentum(pixelsPerSecond: CGPoint(x: velocity.x / scale, y: velocity.y / scale))
        }
    }


    @objc private func handleSecondaryTap(_ gesture: UITapGestureRecognizer) {
        controller.click(.secondary)
    }

    @objc private func handleTwoFingerPan(_ gesture: UIPanGestureRecognizer) {
        let translation = gesture.translation(in: view)
        switch gesture.state {
        case .began:
            twoFingerTranslation = .zero
            twoFingerPansView = !controller.isControlling
            twoFingerScroll = ScrollStream(usesPhases: controller.sendsGesturesToMac)
        case .changed:
            let delta = CGPoint(x: translation.x - twoFingerTranslation.x, y: translation.y - twoFingerTranslation.y)
            twoFingerTranslation = translation
            if twoFingerPansView {
                view.panViewport(by: delta)
            } else {
                scroll(&twoFingerScroll, by: delta)
            }
        case .ended:
            endScroll(&twoFingerScroll, velocity: gesture.velocity(in: view), cancelled: false)
        default:
            endScroll(&twoFingerScroll, velocity: .zero, cancelled: true)
        }
    }

    @objc private func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        if gesture.state == .began {
            pinchGoesToMac =
                controller.sendsGesturesToMac && !controller.isZoomModeOn
                && (gesture.numberOfTouches == 0 || !AppSettings.shared.pinchZooms)
        }
        if pinchGoesToMac {
            handleMacPinch(gesture)
            return
        }
        switch gesture.state {
        case .began:
            pinchStartZoom = view.zoom / gesture.scale
            pinchLocation = gesture.location(in: view)
            pinchAnchor = view.imagePoint(for: pinchLocation)
        case .changed:
            let zoom = pinchStartZoom * gesture.scale
            if zoom > 1 {
                view.setZoom(zoom, keeping: pinchAnchor, at: pinchLocation)
            } else if view.zoom != 1 {
                view.applyScale(.fit)
                pinchAnchor = view.imagePoint(for: pinchLocation)
            }
        default:
            break
        }
    }

    private func handleMacPinch(_ gesture: UIPinchGestureRecognizer) {
        switch gesture.state {
        case .began:
            macPinchStarted = false
            previousPinchScale = 1
        case .changed:
            if !macPinchStarted {
                macPinchStarted = true
                controller.magnify(by: gesture.scale - 1, phase: .began)
            } else {
                controller.magnify(by: gesture.scale / previousPinchScale - 1, phase: .changed)
            }
            previousPinchScale = gesture.scale
        default:
            if macPinchStarted {
                controller.magnify(by: 0, phase: gesture.state == .ended ? .ended : .cancelled)
            }
            macPinchStarted = false
        }
    }

    @objc private func handleRotation(_ gesture: UIRotationGestureRecognizer) {
        switch gesture.state {
        case .began:
            macRotationStarted = false
            previousRotation = 0
        case .changed:
            let phase: GesturePhase
            if !macRotationStarted {
                guard abs(gesture.rotation) > 0.08 else { return }
                macRotationStarted = true
                phase = .began
            } else {
                phase = .changed
            }
            controller.rotate(degrees: -(gesture.rotation - previousRotation) * 180 / .pi, phase: phase)
            previousRotation = gesture.rotation
        default:
            if macRotationStarted {
                controller.rotate(degrees: 0, phase: gesture.state == .ended ? .ended : .cancelled)
            }
            macRotationStarted = false
        }
    }


    @objc private func handleHardwareScroll(_ gesture: UIPanGestureRecognizer) {
        let translation = gesture.translation(in: view)
        switch gesture.state {
        case .began:
            hardwareScrollTranslation = .zero
            hardwareScrollPansView = controller.isZoomModeOn
            hardwareScroll = ScrollStream(usesPhases: controller.sendsGesturesToMac)
            fallthrough
        case .changed:
            let delta = CGPoint(x: translation.x - hardwareScrollTranslation.x, y: translation.y - hardwareScrollTranslation.y)
            hardwareScrollTranslation = translation
            if hardwareScrollPansView {
                view.panViewport(by: delta)
            } else {
                scroll(&hardwareScroll, by: delta)
            }
        case .ended:
            endScroll(&hardwareScroll, velocity: gesture.velocity(in: view), cancelled: false)
        default:
            endScroll(&hardwareScroll, velocity: .zero, cancelled: true)
        }
    }

    @objc private func handleWheel(_ gesture: UIPanGestureRecognizer) {
        let translation = gesture.translation(in: view)
        switch gesture.state {
        case .began:
            wheelTranslation = .zero
            wheelScroll = ScrollStream()
            wheelZoomsView = controller.isZoomModeOn
        case .changed:
            let delta = CGPoint(x: translation.x - wheelTranslation.x, y: translation.y - wheelTranslation.y)
            wheelTranslation = translation
            if wheelZoomsView {
                let location = gesture.location(in: view)
                let factor = pow(2, delta.y / Self.wheelPointsPerZoomDoubling)
                view.setZoom(view.zoom * factor, keeping: view.imagePoint(for: location), at: location)
            } else {
                scroll(&wheelScroll, by: delta)
            }
        default:
            break
        }
    }

    @objc private func handleHover(_ gesture: UIHoverGestureRecognizer) {
        guard !isPointerLocked, !controller.isZoomModeOn else { return }
        switch gesture.state {
        case .began, .changed:
            let location = gesture.location(in: view)
            if !controller.isToolbarVisible, view.imageRect.insetBy(dx: -40, dy: -40).contains(location) {
                controller.movePointer(to: view.imagePoint(for: location))
            }
        default:
            controller.pointerLeftScreen()
        }
    }


    private func pointerBegan(_ touch: UITouch, event: UIEvent?) {
        pointerTouches.insert(touch)
        controller.movePointer(to: view.imagePoint(for: touch.location(in: view)))
        controller.setPressedButtons(buttons(from: event))
    }

    private func pointerMoved(_ touch: UITouch, event: UIEvent?) {
        guard pointerTouches.contains(touch) else { return }
        controller.movePointer(to: view.imagePoint(for: touch.location(in: view)))
        let buttons = buttons(from: event)
        if buttons != controller.pointerButtons {
            controller.setPressedButtons(buttons)
        }
        drag.begin(with: touch, relative: false)
    }

    private func pointerEnded(_ touch: UITouch) {
        guard pointerTouches.remove(touch) != nil, pointerTouches.isEmpty else { return }
        controller.setPressedButtons([])
    }

    private func buttons(from event: UIEvent?) -> PointerButtons {
        guard let mask = event?.buttonMask else { return .primary }
        var buttons: PointerButtons = []
        if mask.contains(.primary) { buttons.insert(.primary) }
        if mask.contains(.secondary) { buttons.insert(.secondary) }
        if mask.contains(.button(3)) { buttons.insert(.middle) }
        return buttons.isEmpty ? .primary : buttons
    }


    private var isPointerLocked: Bool {
        PointerLock.isLocked(view.window?.windowScene)
    }

    func lockedMouseMoved(by counts: CGPoint) {
        guard isPointerLocked, counts.x.isFinite, counts.y.isFinite else { return }
        let delta = CGPoint(x: counts.x / Self.mouseCountsPerPoint, y: counts.y / Self.mouseCountsPerPoint)
        let now = CACurrentMediaTime()
        let interval = min(max(now - lastLockedMoveTime, 0.001), 0.1)
        lastLockedMoveTime = now
        lockedMouseSpeed += (hypot(delta.x, delta.y) / interval - lockedMouseSpeed) * min(1, interval / 0.05)
        movePointer(by: delta, speed: lockedMouseSpeed)
    }

    func lockedMouseButton(_ button: PointerButtons, pressed: Bool) {
        guard isPointerLocked else { return }
        if pressed {
            controller.stopScrollMomentum()
            view.takeKeyFocus()
            lockedButtons.insert(button)
            controller.press(button)
        } else if lockedButtons.remove(button) != nil {
            controller.release(button)
        }
    }

    @objc private func pointerLockDidChange(_ notification: Notification) {
        guard let scene = notification.userInfo?[UIPointerLockState.sceneUserInfoKey] as? UIScene,
            scene === view.window?.windowScene
        else { return }
        lockedMouseSpeed = 0
        controller.isPointerLocked = isPointerLocked
        let held = lockedButtons
        lockedButtons = []
        if !pointerTouches.isEmpty {
            pointerTouches.removeAll()
            controller.setPressedButtons([])
        } else if !held.isEmpty {
            controller.release(held)
        }
        if isPointerLocked, let systemPointerLocation {
            self.systemPointerLocation = nil
            controller.movePointer(to: view.imagePoint(for: view.convert(systemPointerLocation, from: nil)))
            view.keepPointerVisible()
        }
        pointerInteraction.invalidate()
    }

    @objc private func trackSystemPointer(_ gesture: UIHoverGestureRecognizer) {
        guard !isPointerLocked, gesture.state == .began || gesture.state == .changed else { return }
        systemPointerLocation = gesture.location(in: nil)
        controller.lastSystemPointerLocation = systemPointerLocation
        let overPicture = view.imageRect.contains(gesture.location(in: view))
        controller.isSystemPointerOverPicture = overPicture
        var fromPointer = true
        if #available(iOS 16.1, *) { fromPointer = gesture.zOffset == 0 }
        systemPointerOverPicture = overPicture && fromPointer
    }

    private func windowTouchBegan(_ touch: UITouch) {
        if touch.type != .indirectPointer {
            systemPointerLocation = nil
            systemPointerOverPicture = false
        } else if !isPointerLocked {
            systemPointerLocation = touch.location(in: nil)
            controller.lastSystemPointerLocation = systemPointerLocation
        }
    }

    func pointerInteraction(_ interaction: UIPointerInteraction, regionFor request: UIPointerRegionRequest, defaultRegion: UIPointerRegion) -> UIPointerRegion? {
        let inside = !controller.isToolbarVisible && view.imageRect.contains(request.location)
        let identifier = !inside ? "letterbox" : controller.isZoomModeOn ? "zoom" : "screen"
        return UIPointerRegion(rect: inside ? view.imageRect : view.bounds, identifier: identifier as NSString)
    }

    func pointerInteraction(_ interaction: UIPointerInteraction, styleFor region: UIPointerRegion) -> UIPointerStyle? {
        guard (region.identifier as? NSString) == "screen", controller.isControlling else { return nil }
        guard let pointerCursor else { return .hidden() }
        return PointerShape.style(for: pointerCursor, beamLength: pointerBeamLength)
    }

    func showsCursorAsPointer(_ shape: CursorShape?, visible: Bool, scale: CGFloat) -> Bool {
        let cursor = visible && AppSettings.shared.cursorStyle == .system ? shape?.kind : nil
        let beamLength = Self.iBeamPoints * scale * controller.info.cursorPixelsPerPoint
        if cursor != pointerCursor || (cursor == .iBeam || cursor == .verticalIBeam) && beamLength != pointerBeamLength {
            pointerCursor = cursor
            pointerBeamLength = beamLength
            pointerInteraction.invalidate()
        }
        return pointerCursor != nil && systemPointerOverPicture && !isPointerLocked && controller.isControlling
            && !controller.isToolbarVisible && !controller.isZoomModeOn
    }

    func localPointer(for shape: CursorShape?, scale: CGFloat) -> CursorShape? {
        guard let shape, scale > 0 else { return nil }
        let displayScale = view.traitCollection.displayScale
        if case .theme(let id) = AppSettings.shared.cursorStyle {
            guard let kind = shape.kind, let drawn = CursorThemeStore.shared.theme(id: id)?.image(for: kind, scale: displayScale) else {
                return nil
            }
            return CursorShape(
                image: drawn.image, hotspot: CGPoint(x: drawn.hotspot.x / scale, y: drawn.hotspot.y / scale), kind: kind,
                scale: drawn.scale * scale)
        }
        guard let pointerCursor else { return nil }
        if localPointerImage?.cursor != pointerCursor || localPointerImage?.beamLength != pointerBeamLength
            || localPointerImage?.displayScale != displayScale
        {
            guard let drawn = PointerShape.mask(for: pointerCursor, beamLength: pointerBeamLength, scale: displayScale) else { return nil }
            localPointerImage = (pointerCursor, pointerBeamLength, displayScale, drawn.image, drawn.hotspot)
        }
        guard let localPointerImage else { return nil }
        return CursorShape(
            image: localPointerImage.image, hotspot: CGPoint(x: localPointerImage.hotspot.x / scale, y: localPointerImage.hotspot.y / scale),
            kind: shape.kind, scale: displayScale * scale,
            material: CursorMaterial(
                isDarkInterface: view.traitCollection.userInterfaceStyle == .dark,
                increasesContrast: UIAccessibility.isDarkerSystemColorsEnabled))
    }


    func accessibilityActions() -> [UIAccessibilityCustomAction] {
        [
            UIAccessibilityCustomAction(name: String(localized: "Click")) { [weak self] _ in
                self?.controller.click(.primary)
                return self?.controller.isControlling ?? false
            },
            UIAccessibilityCustomAction(name: String(localized: "Right-Click")) { [weak self] _ in
                self?.controller.click(.secondary)
                return self?.controller.isControlling ?? false
            },
            UIAccessibilityCustomAction(name: String(localized: "Show or Hide Keyboard")) { [weak self] _ in
                self?.controller.toggleKeyboard()
                return true
            },
        ]
    }
}

private final class TouchObserver: UIGestureRecognizer {
    var touchBegan: (UITouch) -> Void = { _ in }

    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        cancelsTouchesInView = false
        delaysTouchesEnded = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        touches.forEach(touchBegan)
        state = .failed
    }
}
#endif
