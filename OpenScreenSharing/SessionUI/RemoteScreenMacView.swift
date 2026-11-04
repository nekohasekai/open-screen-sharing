#if os(macOS)
import AppKit
import Carbon
import SwiftUI
import UniformTypeIdentifiers

struct RemoteScreenView: NSViewRepresentable {
    let controller: SessionController
    let mode: SessionMode
    let isControlling: Bool
    let isZoomModeOn: Bool

    init(controller: SessionController) {
        self.controller = controller
        mode = controller.mode
        isControlling = controller.isControlling
        isZoomModeOn = controller.isZoomModeOn
    }

    func makeNSView(context: Context) -> RemoteScreenNSView {
        RemoteScreenNSView(controller: controller)
    }

    func updateNSView(_ view: RemoteScreenNSView, context: Context) {
        view.setAccessibilityValue(mode == .control ? String(localized: "Controlling") : String(localized: "Observing"))
        view.refreshCursor()
        view.updateSymbolicHotKeys()
    }
}

final class RemoteScreenNSView: NSView, RemoteScreen {
    private static let minimumZoom: CGFloat = 0.25
    private static let pointerMargin: CGFloat = 40
    private static let pointsPerLine: CGFloat = 10
    private static let wheelPointsPerZoomDoubling: CGFloat = 100
    private static let defaultPictureSize = CGSize(width: 1280, height: 800)
    private static let views = NSHashTable<RemoteScreenNSView>.weakObjects()

    private let controller: SessionController
    private let metalLayer = CAMetalLayer()
    private var presenter: ScreenPresenter!
    private(set) var isOccluded = false

    private var zoom: CGFloat = 1
    private var viewportCenter: CGPoint?

    private var reportTask: Task<Void, Never>?
    private var reportedArea: CGSize?
    private var reportedScale: CGFloat?
    private var keyMonitor: Any?
    private var suspendsSymbolicHotKeys = false
    private var composedText = ""
    private var composedMarkedRange: NSRange?
    private var typedText = ""

    private var trackingArea: NSTrackingArea?
    private var systemPointerLocation: CGPoint?
    private var drawsCursorLocally = false
    private var remoteCursor: (image: CGImage, scale: CGFloat, backingScale: CGFloat, style: CursorStyle, cursor: NSCursor, drawn: CursorShape)?
    private var remoteCursorVisible = true
    private var clickedButtons: PointerButtons = []
    private var panStart: CGPoint?
    private var locksPointer = false
    private var isPointerLocked = false
    private var isPointerReleased = false
    private static let chordModifiers: NSEvent.ModifierFlags = [.control, .option, .shift, .command, .function]
    private var releaseChordFlags: NSEvent.ModifierFlags = []
    private var releaseChordSpoiled = false
    private var shapedImageSize: CGSize?
    private var windowToolbarHeight: CGFloat = 0
    private var hasSizedWindow = false
    private var keepsRememberedSize = false

    private var scrollStarted = false
    private var scrollUsesPhases = false
    private var scrollPansView = false
    private var scrollRemainder = CGPoint.zero
    private var scrollSamples: [(delta: CGPoint, time: TimeInterval)] = []
    private var magnifyGoesToMac = false
    private var rotateGoesToMac = false

    private static let hiddenCursor = NSCursor(image: NSImage(size: NSSize(width: 1, height: 1)), hotSpot: .zero)

    init(controller: SessionController) {
        self.controller = controller
        super.init(frame: .zero)
        wantsLayer = true
        layerContentsRedrawPolicy = .never
        metalLayer.backgroundColor = NSColor.black.cgColor
        presenter = ScreenPresenter(screen: self, controller: controller, layer: metalLayer)
        controller.screen = self
        Self.views.add(self)
        setAccessibilityElement(true)
        setAccessibilityRole(.image)
        setAccessibilityLabel(String(localized: "Screen of \(controller.title)"))
        setAccessibilityCustomActions([
            NSAccessibilityCustomAction(name: String(localized: "Click")) { [weak self] in
                self?.controller.click(.primary)
                return self?.controller.isControlling ?? false
            },
            NSAccessibilityCustomAction(name: String(localized: "Right-Click")) { [weak self] in
                self?.controller.click(.secondary)
                return self?.controller.isControlling ?? false
            },
        ])
        registerForDraggedTypes([.fileURL] + NSFilePromiseReceiver.readableDraggedTypes.map { NSPasteboard.PasteboardType($0) })
    }

    required init?(coder: NSCoder) {
        fatalError()
    }

    deinit {
        MainActor.assumeIsolated {
            if let keyMonitor {
                NSEvent.removeMonitor(keyMonitor)
            }
            if isPointerLocked {
                CGAssociateMouseAndMouseCursorPosition(1)
                NSCursor.unhide()
            }
            if suspendsSymbolicHotKeys {
                SymbolicHotKeys.resume()
            }
        }
    }

    override func makeBackingLayer() -> CALayer { metalLayer }

    override var wantsUpdateLayer: Bool { true }

    override func updateLayer() {}

    override var isFlipped: Bool { true }

    override var isOpaque: Bool { true }

    override var acceptsFirstResponder: Bool { true }

    private var backingScale: CGFloat {
        window?.backingScaleFactor ?? 2
    }


    override func viewWillMove(toWindow newWindow: NSWindow?) {
        super.viewWillMove(toWindow: newWindow)
        guard let window, newWindow !== window else { return }
        setPointerLocked(false)
        NotificationCenter.default.removeObserver(self, name: nil, object: window)
        DistributedNotificationCenter.default().removeObserver(self)
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        presenter.stop()
        if let keyMonitor {
            NSEvent.removeMonitor(keyMonitor)
            self.keyMonitor = nil
        }
        reportTask?.cancel()
        reportTask = nil
        reportedArea = nil
        reportedScale = nil
        guard let window else {
            controller.releaseAllKeys()
            updateSymbolicHotKeys()
            return
        }
        if !hasSizedWindow, !window.isVisible {
            window.alphaValue = 0
        }
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(windowDidBecomeKey), name: NSWindow.didBecomeKeyNotification, object: window)
        center.addObserver(self, selector: #selector(windowDidResignKey), name: NSWindow.didResignKeyNotification, object: window)
        center.addObserver(self, selector: #selector(windowOcclusionDidChange), name: NSWindow.didChangeOcclusionStateNotification, object: window)
        center.addObserver(self, selector: #selector(windowScreenDidChange), name: NSWindow.didChangeScreenNotification, object: window)
        center.addObserver(self, selector: #selector(windowFullScreenDidChange), name: NSWindow.didEnterFullScreenNotification, object: window)
        center.addObserver(self, selector: #selector(windowFullScreenDidChange), name: NSWindow.didExitFullScreenNotification, object: window)
        DistributedNotificationCenter.default().addObserver(
            self, selector: #selector(keyboardSourceDidChange), name: NSNotification.Name(kTISNotifySelectedKeyboardInputSourceChanged as String),
            object: nil, suspensionBehavior: .deliverImmediately
        )
        presenter.start(displayLink(target:selector:))
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp, .flagsChanged]) { [weak self] event in
            guard let self else { return event }
            if self.detectsReleaseChord(event), self.isPointerLocked {
                self.isPointerReleased = true
                self.updatePointerLock()
            }
            guard self.capturesKey(event) else {
                guard event.type == .keyDown else { return event }
                self.controller.keyDownHandledLocally()
                return event.window === self.window && KeyShortcuts.shared.performAssigned(event) ? nil : event
            }
            return self.controller.handleKeyEvent(event) ? nil : event
        }
        isOccluded = !window.occlusionState.contains(.visible)
        controller.setScreenVisible(!isOccluded)
        updateDrawableSize()
        geometryDidChange()
        DispatchQueue.main.async { [weak self] in
            guard let self, let window = self.window else { return }
            self.sizeWindow()
            window.alphaValue = 1
            self.windowScreenDidChange()
            self.windowFullScreenDidChange()
            window.makeFirstResponder(self)
            if window.isKeyWindow { self.windowDidBecomeKey() }
        }
    }

    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        updateDrawableSize()
        geometryDidChange()
        if controller.scaleMode == .actualSize {
            applyScale(.actualSize)
        }
        clampCenter()
        setNeedsRender()
    }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        updateDrawableSize()
        geometryDidChange()
        setNeedsRender()
    }

    override func viewDidEndLiveResize() {
        super.viewDidEndLiveResize()
        reportTask?.cancel()
        reportTask = nil
        reportArea()
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let trackingArea {
            removeTrackingArea(trackingArea)
        }
        let area = NSTrackingArea(
            rect: .zero, options: [.mouseMoved, .mouseEnteredAndExited, .cursorUpdate, .activeInKeyWindow, .inVisibleRect], owner: self)
        addTrackingArea(area)
        trackingArea = area
    }

    private func updateDrawableSize() {
        let scale = backingScale
        metalLayer.contentsScale = scale
        metalLayer.drawableSize = CGSize(width: bounds.width * scale, height: bounds.height * scale)
    }

    private func geometryDidChange() {
        guard window != nil, bounds.width > 0, bounds.height > 0 else { return }
        guard !inLiveResize else {
            reportTask?.cancel()
            reportTask = nil
            return
        }
        guard reportTask == nil else { return }
        reportTask = Task { [weak self] in
            guard let self, !Task.isCancelled else { return }
            self.reportTask = nil
            guard !self.inLiveResize else { return }
            self.reportArea()
        }
    }

    private func reportArea() {
        guard let window, bounds.width > 0, bounds.height > 0 else { return }
        guard bounds.size != reportedArea || window.backingScaleFactor != reportedScale else { return }
        reportedArea = bounds.size
        reportedScale = window.backingScaleFactor
        controller.displayAreaDidChange(bounds.size, scale: window.backingScaleFactor, screen: window.screen?.frame.size ?? bounds.size)
        if hasSizedWindow, !window.styleMask.contains(.fullScreen) {
            controller.windowSizeDidChange(bounds.size)
        }
    }

    private func sizeWindow() {
        guard !hasSizedWindow, let window, !window.styleMask.contains(.fullScreen), window.contentLayoutRect.width > 0,
            window.contentLayoutRect.height > 0
        else { return }
        hasSizedWindow = true
        keepsRememberedSize = controller.windowSize != nil
        resizeWindow(toPicture: controller.windowSize ?? Self.defaultPictureSize, animate: false)
    }

    private func resizeWindow(toPicture size: CGSize, animate: Bool) {
        guard let window, let screen = window.screen, size.width > 0, size.height > 0 else { return }
        let content = window.contentLayoutRect.size
        let toolbar = window.toolbar == nil ? windowToolbarHeight : 0
        let chrome = CGSize(width: window.frame.width - content.width, height: window.frame.height - content.height + toolbar)
        let available = CGSize(width: screen.visibleFrame.width - chrome.width, height: screen.visibleFrame.height - chrome.height)
        let fit = min(1, available.width / size.width, available.height / size.height)
        let target = CGSize(width: (size.width * fit).rounded(), height: (size.height * fit).rounded())
        guard abs(target.width - content.width) > 1 || abs(target.height - content.height) > 1 else { return }
        var frame = window.frame
        frame.size = CGSize(width: target.width + chrome.width, height: target.height + chrome.height)
        frame.origin.y = window.frame.maxY - frame.height
        window.setFrame(window.constrainFrameRect(frame, to: screen), display: true, animate: animate)
    }

    static func isSessionWindow(_ window: NSWindow?) -> Bool {
        guard let window else { return false }
        return views.allObjects.contains { $0.window === window }
    }

    static func focusWindow(showing route: SessionRoute) -> Bool {
        let shown = views.allObjects.first { view in
            if let id = route.computerID, let computerID = view.controller.computerID {
                return id == computerID
            }
            return view.controller.route.address.matches(route.address)
        }
        guard let shown, let window = shown.window else { return false }
        switch shown.controller.phase {
        case .disconnected, .failed:
            window.close()
            return false
        default:
            if window.isMiniaturized { window.deminiaturize(nil) }
            window.makeKeyAndOrderFront(nil)
            return true
        }
    }

    @objc private func windowDidBecomeKey() {
        guard window?.isKeyWindow == true else { return }
        controller.sceneDidBecomeActive()
        controller.syncCapsLock(NSEvent.modifierFlags.contains(.capsLock))
        updatePointerLock()
        updateSymbolicHotKeys()
    }

    @objc private func windowDidResignKey() {
        if isPointerLocked { isPointerReleased = true }
        setPointerLocked(false)
        releaseChordFlags = []
        releaseChordSpoiled = false
        controller.releaseAllKeys()
        if !clickedButtons.isEmpty {
            clickedButtons = []
            controller.setPressedButtons([])
        }
        panStart = nil
        endScroll(velocity: .zero, cancelled: true)
        systemPointerLocation = nil
        controller.pointerLeftScreen()
        updateSymbolicHotKeys()
        setNeedsRender()
    }

    func updateSymbolicHotKeys() {
        let suspends = window?.isKeyWindow == true && controller.isControlling
        guard suspends != suspendsSymbolicHotKeys else { return }
        suspendsSymbolicHotKeys = suspends
        if suspends {
            SymbolicHotKeys.suspend()
        } else {
            SymbolicHotKeys.resume()
        }
    }

    @objc private func keyboardSourceDidChange() {
        guard let window, window.isKeyWindow, window.attachedSheet == nil, !(window.firstResponder is NSText),
            let source = TISCopyCurrentKeyboardInputSource()?.takeRetainedValue(),
            let id = TISGetInputSourceProperty(source, kTISPropertyInputSourceID)
        else { return }
        controller.followKeyboardSource(Unmanaged<CFString>.fromOpaque(id).takeUnretainedValue() as String)
    }

    @objc private func windowOcclusionDidChange() {
        guard let window else { return }
        isOccluded = !window.occlusionState.contains(.visible)
        controller.setScreenVisible(!isOccluded)
        if isOccluded {
            presenter.pause()
        } else {
            setNeedsRender()
        }
    }

    @objc private func windowScreenDidChange() {
        guard let screen = window?.screen else { return }
        controller.displaySupportsHDR = screen.maximumPotentialExtendedDynamicRangeColorComponentValue > 1
        presenter.setRefreshRate(screen.maximumFramesPerSecond)
    }

    @objc private func windowFullScreenDidChange() {
        let fullScreen = window?.styleMask.contains(.fullScreen) ?? false
        controller.isFullScreen = fullScreen
        updateLetterbox()
    }

    private func setNeedsRender() {
        updateLetterbox()
        presenter.setNeedsRender()
    }

    private func updateLetterbox() {
        let letterbox = measureLetterbox()
        controller.letterbox = letterbox
    }

    private func measureLetterbox() -> Letterbox? {
        guard let window, controller.imageSize.width > 0, controller.imageSize.height > 0 else { return nil }
        let fullScreen = window.styleMask.contains(.fullScreen)
        let titlebar = fullScreen ? 0 : NSWindow.frameRect(forContentRect: .zero, styleMask: window.styleMask.subtracting(.fullSizeContentView)).height
        let toolbar = max(0, window.frame.height - titlebar - bounds.height)
        if toolbar > 0, !fullScreen {
            windowToolbarHeight = toolbar
        }
        let area = CGRect(x: bounds.minX, y: bounds.minY - toolbar, width: bounds.width, height: bounds.height + toolbar)
        let picture = imageRect(in: area)
        return Letterbox(
            left: CGRect(x: area.minX, y: area.minY, width: max(0, picture.minX - area.minX), height: area.height),
            top: CGRect(x: area.minX, y: area.minY, width: area.width, height: max(0, picture.minY - area.minY))
        )
    }

    func imageSizeDidChange() {
        if controller.scaleMode == .actualSize {
            applyScale(.actualSize)
        } else if controller.scaleMode == .fit {
            zoom = 1
            viewportCenter = nil
        }
        clampCenter()
        setNeedsRender()
        shapeWindow()
    }

    private func shapeWindow() {
        let size = controller.imageSize
        guard let window, size.width > 0, size.height > 0, size != shapedImageSize,
            controller.scaleMode == .fit, !controller.pictureMatchesWindow, !window.styleMask.contains(.fullScreen), !inLiveResize,
            bounds.width > 0, bounds.height > 0
        else { return }
        shapedImageSize = size
        guard !keepsRememberedSize else {
            keepsRememberedSize = false
            return
        }
        resizeWindow(toPicture: CGSize(width: bounds.width, height: bounds.width * size.height / size.width), animate: true)
    }


    private var imageSize: CGSize {
        let size = controller.imageSize
        return size.width > 0 && size.height > 0 ? size : CGSize(width: 16, height: 10)
    }

    private func fitScale(in size: CGSize) -> CGFloat {
        let scale = min(size.width / imageSize.width, size.height / imageSize.height)
        let devicePixels = scale * backingScale
        return devicePixels >= 1 && devicePixels < 1.01 ? 1 / backingScale : scale
    }

    var displayScale: CGFloat {
        fitScale(in: bounds.size) * zoom
    }

    private var maximumZoom: CGFloat {
        max(4, (8 / backingScale) / max(fitScale(in: bounds.size), 0.0001))
    }

    private var actualSizeZoom: CGFloat {
        (1 / backingScale) / max(fitScale(in: bounds.size), 0.0001)
    }

    var imageRect: CGRect {
        imageRect(in: bounds)
    }

    private func imageRect(in area: CGRect) -> CGRect {
        let scale = fitScale(in: area.size) * zoom
        let size = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
        var origin = CGPoint(x: area.midX - size.width / 2, y: area.midY - size.height / 2)
        if let center = viewportCenter {
            origin = CGPoint(x: area.midX - center.x * scale, y: area.midY - center.y * scale)
        }
        origin.x = (origin.x * backingScale).rounded() / backingScale
        origin.y = (origin.y * backingScale).rounded() / backingScale
        return CGRect(origin: origin, size: size)
    }

    private func clampCenter() {
        guard let center = viewportCenter else { return }
        let scale = displayScale
        func clamp(_ center: CGFloat, image: CGFloat, bounds: ClosedRange<CGFloat>) -> CGFloat {
            let size = image * scale
            let middle = (bounds.lowerBound + bounds.upperBound) / 2
            let origins: ClosedRange<CGFloat>
            if size > bounds.upperBound - bounds.lowerBound {
                origins = (bounds.upperBound - size)...bounds.lowerBound
            } else {
                origins = bounds.lowerBound...(bounds.upperBound - size)
            }
            let origin = min(max(middle - center * scale, origins.lowerBound), origins.upperBound)
            return (middle - origin) / scale
        }
        viewportCenter = CGPoint(
            x: clamp(center.x, image: imageSize.width, bounds: bounds.minX...bounds.maxX),
            y: clamp(center.y, image: imageSize.height, bounds: bounds.minY...bounds.maxY)
        )
    }

    private func currentCenter() -> CGPoint {
        let rect = imageRect
        let scale = displayScale
        return CGPoint(x: (bounds.midX - rect.minX) / scale, y: (bounds.midY - rect.minY) / scale)
    }

    func imagePoint(for viewPoint: CGPoint) -> CGPoint {
        let rect = imageRect
        let scale = displayScale
        return CGPoint(x: (viewPoint.x - rect.minX) / scale, y: (viewPoint.y - rect.minY) / scale)
    }

    func viewPoint(for imagePoint: CGPoint) -> CGPoint {
        let rect = imageRect
        let scale = displayScale
        return CGPoint(x: rect.minX + imagePoint.x * scale, y: rect.minY + imagePoint.y * scale)
    }

    func applyScale(_ scale: ScaleMode) {
        switch scale {
        case .fit:
            zoom = 1
            viewportCenter = nil
        case .actualSize:
            let anchor = controller.pointerPosition
            zoom = max(actualSizeZoom, 0.0001)
            viewportCenter = nil
            let centered = currentCenter()
            let scale = displayScale
            viewportCenter = CGPoint(
                x: imageSize.width * scale > bounds.width ? anchor.x : centered.x,
                y: imageSize.height * scale > bounds.height ? anchor.y : centered.y
            )
            clampCenter()
        case .custom:
            break
        }
        controller.scaleMode = scale
        setNeedsRender()
    }

    private func toggleZoom(at viewPoint: CGPoint) {
        if controller.scaleMode != .fit {
            applyScale(.fit)
        } else {
            setZoom(max(actualSizeZoom, 2), keeping: imagePoint(for: viewPoint), at: viewPoint)
        }
    }

    private func setZoom(_ newZoom: CGFloat, keeping imageAnchor: CGPoint, at viewAnchor: CGPoint) {
        zoom = min(max(newZoom, Self.minimumZoom), maximumZoom)
        let scale = displayScale
        viewportCenter = CGPoint(
            x: imageAnchor.x + (bounds.midX - viewAnchor.x) / scale,
            y: imageAnchor.y + (bounds.midY - viewAnchor.y) / scale
        )
        clampCenter()
        let mode: ScaleMode = abs(zoom - 1) < 0.01 ? .fit : (abs(zoom - actualSizeZoom) < 0.01 ? .actualSize : .custom)
        controller.scaleMode = mode
        setNeedsRender()
    }

    private func keepPointerVisible() {
        guard zoom > 1.01 else { return }
        let point = viewPoint(for: controller.pointerPosition)
        let inset = bounds.insetBy(dx: min(80, bounds.width / 4), dy: min(80, bounds.height / 4))
        var shift = CGPoint.zero
        if point.x < inset.minX { shift.x = point.x - inset.minX } else if point.x > inset.maxX { shift.x = point.x - inset.maxX }
        if point.y < inset.minY { shift.y = point.y - inset.minY } else if point.y > inset.maxY { shift.y = point.y - inset.maxY }
        guard shift != .zero else { return }
        let scale = displayScale
        let current = currentCenter()
        viewportCenter = CGPoint(x: current.x + shift.x / scale, y: current.y + shift.y / scale)
        clampCenter()
        setNeedsRender()
    }

    private func panViewport(by delta: CGPoint) {
        let scale = displayScale
        let current = currentCenter()
        viewportCenter = CGPoint(x: current.x - delta.x / scale, y: current.y - delta.y / scale)
        clampCenter()
        setNeedsRender()
    }


    private var showsRemoteCursorLocally: Bool {
        guard let systemPointerLocation, !isPointerLocked, !controller.isZoomModeOn, controller.isControlling,
            window?.isKeyWindow == true
        else { return false }
        return imageRect.contains(systemPointerLocation)
    }

    func drawnCursor(_ shape: CursorShape?, visible: Bool, scale: CGFloat) -> (shape: CursorShape, shownBySystem: Bool)? {
        updateRemoteCursor(shape, visible: visible, scale: scale)
        drawsCursorLocally = showsRemoteCursorLocally
        return remoteCursor.map { ($0.drawn, drawsCursorLocally) }
    }

    private func updateRemoteCursor(_ shape: CursorShape?, visible: Bool, scale: CGFloat) {
        var changed = visible != remoteCursorVisible
        remoteCursorVisible = visible
        if let shape {
            let style = AppSettings.shared.cursorStyle
            if shape.image !== remoteCursor?.image || scale != remoteCursor?.scale || backingScale != remoteCursor?.backingScale
                || style != remoteCursor?.style
            {
                var local: NSCursor?
                switch style {
                case .system:
                    local = shape.kind?.systemCursor
                case .remote:
                    break
                case .theme(let id):
                    if let kind = shape.kind, let drawn = CursorThemeStore.shared.theme(id: id)?.image(for: kind, scale: backingScale) {
                        let size = NSSize(width: CGFloat(drawn.image.width) / drawn.scale, height: CGFloat(drawn.image.height) / drawn.scale)
                        local = NSCursor(image: NSImage(cgImage: drawn.image, size: size), hotSpot: drawn.hotspot)
                    }
                }
                var rect = CGRect(origin: .zero, size: (local?.image.size ?? .zero).applying(CGAffineTransform(scaleX: backingScale, y: backingScale)))
                if let local, let image = local.image.cgImage(forProposedRect: &rect, context: nil, hints: nil) {
                    let drawn = CursorShape(
                        image: image, hotspot: CGPoint(x: local.hotSpot.x / scale, y: local.hotSpot.y / scale), kind: shape.kind,
                        scale: CGFloat(image.width) / local.image.size.width * scale)
                    remoteCursor = (shape.image, scale, backingScale, style, local, drawn)
                } else {
                    let size = NSSize(width: CGFloat(shape.image.width) / shape.scale * scale, height: CGFloat(shape.image.height) / shape.scale * scale)
                    let cursor = NSCursor(
                        image: NSImage(cgImage: shape.image, size: size),
                        hotSpot: NSPoint(x: shape.hotspot.x * scale, y: shape.hotspot.y * scale))
                    remoteCursor = (shape.image, scale, backingScale, style, cursor, shape)
                }
                changed = true
            }
        } else if remoteCursor != nil {
            remoteCursor = nil
            changed = true
        }
        if changed {
            refreshCursor()
        }
    }

    func refreshCursor() {
        guard systemPointerLocation != nil, !isPointerLocked else { return }
        if controller.isZoomModeOn {
            (panStart != nil ? NSCursor.closedHand : NSCursor.openHand).set()
        } else if showsRemoteCursorLocally {
            (remoteCursorVisible ? remoteCursor?.cursor ?? .arrow : Self.hiddenCursor).set()
        } else {
            NSCursor.arrow.set()
        }
        if showsRemoteCursorLocally != drawsCursorLocally {
            setNeedsRender()
        }
    }

    override func cursorUpdate(with event: NSEvent) {
        systemPointerLocation = convert(event.locationInWindow, from: nil)
        refreshCursor()
    }

    override func mouseEntered(with event: NSEvent) {
        systemPointerLocation = convert(event.locationInWindow, from: nil)
        refreshCursor()
    }

    override func mouseExited(with event: NSEvent) {
        guard !isPointerLocked else { return }
        systemPointerLocation = nil
        if clickedButtons.isEmpty {
            controller.pointerLeftScreen()
        }
        if drawsCursorLocally {
            setNeedsRender()
        }
    }

    override func mouseMoved(with event: NSEvent) {
        pointerMoved(event)
    }

    override func mouseDragged(with event: NSEvent) {
        pointerMoved(event)
    }

    override func rightMouseDragged(with event: NSEvent) {
        pointerMoved(event)
    }

    override func otherMouseDragged(with event: NSEvent) {
        pointerMoved(event)
    }

    private func pointerMoved(_ event: NSEvent) {
        if isPointerLocked {
            let scale = displayScale
            guard scale > 0 else { return }
            controller.movePointer(
                to: CGPoint(
                    x: controller.pointerPosition.x + event.deltaX / scale,
                    y: controller.pointerPosition.y + event.deltaY / scale
                ))
            keepPointerVisible()
            return
        }
        let location = convert(event.locationInWindow, from: nil)
        systemPointerLocation = location
        if clickedButtons == [.primary], !bounds.contains(location), let offer = controller.takeRemoteDrag() {
            controller.cancelRemoteDrag()
            clickedButtons = []
            dragOut(offer, event: event, at: location)
            return
        }
        if let panStart {
            panViewport(by: CGPoint(x: location.x - panStart.x, y: location.y - panStart.y))
            self.panStart = location
            return
        }
        refreshCursor()
        guard !controller.isZoomModeOn else { return }
        if !clickedButtons.isEmpty || imageRect.insetBy(dx: -Self.pointerMargin, dy: -Self.pointerMargin).contains(location) {
            controller.movePointer(to: imagePoint(for: location))
        } else {
            controller.pointerLeftScreen()
        }
    }

    override func mouseDown(with event: NSEvent) {
        buttonChanged(.primary, down: true, event: event)
    }

    override func mouseUp(with event: NSEvent) {
        buttonChanged(.primary, down: false, event: event)
    }

    override func rightMouseDown(with event: NSEvent) {
        buttonChanged(.secondary, down: true, event: event)
    }

    override func rightMouseUp(with event: NSEvent) {
        buttonChanged(.secondary, down: false, event: event)
    }

    override func otherMouseDown(with event: NSEvent) {
        guard event.buttonNumber == 2 else { return }
        buttonChanged(.middle, down: true, event: event)
    }

    override func otherMouseUp(with event: NSEvent) {
        guard event.buttonNumber == 2 else { return }
        buttonChanged(.middle, down: false, event: event)
    }

    private func buttonChanged(_ button: PointerButtons, down: Bool, event: NSEvent) {
        let location = convert(event.locationInWindow, from: nil)
        guard down else {
            if button == .primary, panStart != nil {
                panStart = nil
                refreshCursor()
            }
            guard clickedButtons.remove(button) != nil else { return }
            controller.release(button)
            return
        }
        controller.stopScrollMomentum()
        window?.makeFirstResponder(self)
        controller.syncModifiers(event.modifierFlags)
        if !event.modifierFlags.intersection(Self.chordModifiers).isEmpty { releaseChordSpoiled = true }
        if isPointerReleased, locksPointer, imageRect.contains(location) {
            isPointerReleased = false
            updatePointerLock()
        }
        if controller.isZoomModeOn, !isPointerLocked {
            guard button == .primary else { return }
            if event.clickCount == 2 {
                toggleZoom(at: location)
            } else {
                panStart = location
                refreshCursor()
            }
            return
        }
        if !isPointerLocked {
            guard !clickedButtons.isEmpty || imageRect.insetBy(dx: -Self.pointerMargin, dy: -Self.pointerMargin).contains(location)
            else { return }
            controller.movePointer(to: imagePoint(for: location))
        }
        clickedButtons.insert(button)
        controller.press(button, clickCount: event.clickCount)
    }


    override func scrollWheel(with event: NSEvent) {
        let location = convert(event.locationInWindow, from: nil)
        guard event.hasPreciseScrollingDeltas else {
            let delta = CGPoint(x: event.scrollingDeltaX * Self.pointsPerLine, y: event.scrollingDeltaY * Self.pointsPerLine)
            if controller.isZoomModeOn {
                let factor = pow(2, delta.y / Self.wheelPointsPerZoomDoubling)
                setZoom(zoom * factor, keeping: imagePoint(for: location), at: location)
            } else {
                scrollRemainder = .zero
                sendScroll(delta)
            }
            return
        }
        let delta = CGPoint(x: event.scrollingDeltaX, y: event.scrollingDeltaY)
        if !event.momentumPhase.isEmpty {
            if !scrollUsesPhases, !scrollPansView {
                sendScroll(delta)
            }
            return
        }
        switch event.phase {
        case .mayBegin:
            controller.stopScrollMomentum()
        case .began:
            controller.stopScrollMomentum()
            scrollStarted = false
            scrollUsesPhases = controller.sendsGesturesToMac
            scrollPansView = controller.isZoomModeOn
            scrollRemainder = .zero
            scrollSamples = []
            scrolled(by: delta, at: event.timestamp)
        case .changed:
            scrolled(by: delta, at: event.timestamp)
        case .ended:
            let recent = scrollSamples.filter { event.timestamp - $0.time < 0.1 }
            var velocity = CGPoint.zero
            if recent.count > 1, let first = recent.first, let last = recent.last {
                let interval = max(last.time - first.time, 0.008)
                let distance = recent.dropFirst().reduce(CGPoint.zero) { CGPoint(x: $0.x + $1.delta.x, y: $0.y + $1.delta.y) }
                velocity = CGPoint(x: distance.x / interval, y: distance.y / interval)
            }
            endScroll(velocity: velocity, cancelled: false)
        case .cancelled:
            endScroll(velocity: .zero, cancelled: true)
        default:
            scrollUsesPhases = false
            scrollPansView = false
            sendScroll(delta)
        }
    }

    private func scrolled(by delta: CGPoint, at time: TimeInterval) {
        scrollSamples.append((delta, time))
        if scrollSamples.count > 8 { scrollSamples.removeFirst() }
        if scrollPansView {
            panViewport(by: delta)
        } else if scrollUsesPhases {
            let scale = displayScale
            controller.scroll(byPixels: CGPoint(x: delta.x / scale, y: delta.y / scale), phase: scrollStarted ? .changed : .began)
            scrollStarted = true
        } else {
            sendScroll(delta)
        }
    }

    private func endScroll(velocity: CGPoint, cancelled: Bool) {
        scrollSamples = []
        guard scrollStarted else { return }
        scrollStarted = false
        controller.scroll(byPixels: .zero, phase: cancelled ? .cancelled : .ended)
        if !cancelled {
            let scale = displayScale
            controller.startScrollMomentum(pixelsPerSecond: CGPoint(x: velocity.x / scale, y: velocity.y / scale))
        }
    }

    private func sendScroll(_ delta: CGPoint) {
        scrollRemainder.x += delta.x
        scrollRemainder.y += delta.y
        let sendX = scrollRemainder.x.rounded(.towardZero)
        let sendY = scrollRemainder.y.rounded(.towardZero)
        guard sendX != 0 || sendY != 0 else { return }
        scrollRemainder.x -= sendX
        scrollRemainder.y -= sendY
        let scale = displayScale
        controller.scroll(byPixels: CGPoint(x: sendX / scale, y: sendY / scale))
    }

    override func magnify(with event: NSEvent) {
        if event.phase == .began {
            magnifyGoesToMac = controller.sendsGesturesToMac && !controller.isZoomModeOn
        }
        if magnifyGoesToMac {
            guard let phase = GesturePhase(event.phase) else { return }
            controller.magnify(at: controller.pointerPosition, by: event.magnification, phase: phase)
            return
        }
        let location = convert(event.locationInWindow, from: nil)
        setZoom(zoom * (1 + event.magnification), keeping: imagePoint(for: location), at: location)
    }

    override func rotate(with event: NSEvent) {
        if event.phase == .began {
            rotateGoesToMac = controller.sendsGesturesToMac && !controller.isZoomModeOn
        }
        guard rotateGoesToMac, let phase = GesturePhase(event.phase) else { return }
        controller.rotate(at: controller.pointerPosition, degrees: Double(event.rotation), phase: phase)
    }

    override func smartMagnify(with event: NSEvent) {
        guard controller.isZoomModeOn || !controller.sendsGesturesToMac else { return }
        toggleZoom(at: convert(event.locationInWindow, from: nil))
    }


    func setLocksPointer(_ locks: Bool) {
        if !controller.isPointerLockOn { isPointerReleased = false }
        guard locks != locksPointer else { return }
        locksPointer = locks
        updatePointerLock()
    }

    private func updatePointerLock() {
        setPointerLocked(locksPointer && !isPointerReleased && window?.isKeyWindow == true && NSApp.isActive)
    }

    private func detectsReleaseChord(_ event: NSEvent) -> Bool {
        guard event.window === window else { return false }
        let flags = event.modifierFlags.intersection(Self.chordModifiers)
        guard event.type == .flagsChanged else {
            if event.type == .keyDown, !flags.isEmpty { releaseChordSpoiled = true }
            return false
        }
        let chord = KeyShortcuts.shared.pointerRelease
        let detected = releaseChordFlags == chord && flags.isStrictSubset(of: chord) && !releaseChordSpoiled
        releaseChordFlags = flags
        if flags.isEmpty {
            releaseChordSpoiled = false
        } else if detected || !flags.isSubset(of: chord) {
            releaseChordSpoiled = true
        }
        return detected
    }

    private func setPointerLocked(_ locked: Bool) {
        guard locked != isPointerLocked, let window else { return }
        isPointerLocked = locked
        controller.isPointerLocked = locked
        if locked {
            let location = convert(window.mouseLocationOutsideOfEventStream, from: nil)
            if imageRect.contains(location) {
                controller.movePointer(to: imagePoint(for: location))
            }
            warpCursor(to: CGPoint(x: bounds.midX, y: bounds.midY))
            CGAssociateMouseAndMouseCursorPosition(0)
            NSCursor.hide()
            systemPointerLocation = nil
        } else {
            CGAssociateMouseAndMouseCursorPosition(1)
            let point = viewPoint(for: controller.pointerPosition)
            if bounds.contains(point) {
                warpCursor(to: point)
            }
            NSCursor.unhide()
            if !clickedButtons.isEmpty {
                clickedButtons = []
                controller.setPressedButtons([])
            }
        }
        setNeedsRender()
    }

    private func warpCursor(to viewPoint: CGPoint) {
        guard let window, let primary = NSScreen.screens.first else { return }
        let point = window.convertPoint(toScreen: convert(viewPoint, to: nil))
        CGWarpMouseCursorPosition(CGPoint(x: point.x, y: primary.frame.maxY - point.y))
    }


    private func capturesKey(_ event: NSEvent) -> Bool {
        guard let window, event.window === window, window.attachedSheet == nil, !(window.firstResponder is NSText) else { return false }
        return event.type != .keyDown || !KeyShortcuts.shared.isAssigned(event)
    }

    override func becomeFirstResponder() -> Bool {
        let became = super.becomeFirstResponder()
        if became {
            controller.syncCapsLock(NSEvent.modifierFlags.contains(.capsLock))
        }
        return became
    }

    override func resignFirstResponder() -> Bool {
        let resigned = super.resignFirstResponder()
        if resigned {
            composedText = ""
            composedMarkedRange = nil
            typedText = ""
            controller.releaseAllKeys()
        }
        return resigned
    }

    @objc func paste(_ sender: Any?) {
        controller.pasteFromDevice()
    }
}

extension RemoteScreenNSView: NSTextInputClient {
    func insertText(_ string: Any, replacementRange: NSRange) {
        replaceComposedText(in: replacementRange, with: string, marked: false)
    }

    func setMarkedText(_ string: Any, selectedRange: NSRange, replacementRange: NSRange) {
        replaceComposedText(in: replacementRange, with: string, marked: true)
    }

    func unmarkText() {
        guard composedMarkedRange != nil else { return }
        composedMarkedRange = nil
        composedTextDidChange()
    }

    func selectedRange() -> NSRange {
        NSRange(location: composedMarkedRange.map(NSMaxRange) ?? (composedText as NSString).length, length: 0)
    }

    func markedRange() -> NSRange {
        composedMarkedRange ?? NSRange(location: NSNotFound, length: 0)
    }

    func hasMarkedText() -> Bool {
        composedMarkedRange != nil
    }

    func attributedSubstring(forProposedRange range: NSRange, actualRange: NSRangePointer?) -> NSAttributedString? {
        let range = clampedComposedRange(range)
        actualRange?.pointee = range
        return NSAttributedString(string: (composedText as NSString).substring(with: range))
    }

    func validAttributesForMarkedText() -> [NSAttributedString.Key] {
        []
    }

    func firstRect(forCharacterRange range: NSRange, actualRange: NSRangePointer?) -> NSRect {
        let point = viewPoint(for: controller.pointerPosition)
        let font = NSFont.preferredFont(forTextStyle: .body)
        let height = font.ascender - font.descender
        return window?.convertToScreen(convert(NSRect(x: point.x, y: point.y - height / 2, width: 0, height: height), to: nil)) ?? .zero
    }

    func characterIndex(for point: NSPoint) -> Int {
        NSNotFound
    }

    override func doCommand(by selector: Selector) {}

    func forgetTypedText() {
        typedText = ""
        guard let marked = composedMarkedRange else {
            composedText = ""
            return
        }
        composedText = (composedText as NSString).substring(with: marked)
        composedMarkedRange = NSRange(location: 0, length: marked.length)
    }

    private func replaceComposedText(in replacementRange: NSRange, with string: Any, marked: Bool) {
        let text = (string as? NSAttributedString)?.string ?? string as? String ?? ""
        let range = clampedComposedRange(replacementRange.location == NSNotFound ? composedMarkedRange ?? selectedRange() : replacementRange)
        let length = (text as NSString).length
        composedText = (composedText as NSString).replacingCharacters(in: range, with: text)
        composedMarkedRange = marked && length > 0 ? NSRange(location: range.location, length: length) : nil
        composedTextDidChange()
    }

    private func composedTextDidChange() {
        let text = composedMarkedRange.map { (composedText as NSString).replacingCharacters(in: $0, with: "") } ?? composedText
        guard text != typedText else { return }
        let kept = zip(typedText, text).prefix { $0 == $1 }.count
        let deleting = typedText.count - kept
        typedText = text
        if !controller.replaceText(deleting: deleting, with: String(text.dropFirst(kept))) {
            forgetTypedText()
        }
    }

    private func clampedComposedRange(_ range: NSRange) -> NSRange {
        let length = (composedText as NSString).length
        let location = min(max(range.location, 0), length)
        return NSRange(location: location, length: min(max(range.length, 0), length - location))
    }
}

extension RemoteScreenNSView {
    private static let promiseQueue = OperationQueue()

    private func draggedItems(_ info: any NSDraggingInfo) -> [Any] {
        info.draggingPasteboard.readObjects(forClasses: [NSURL.self, NSFilePromiseReceiver.self], options: [.urlReadingFileURLsOnly: true]) ?? []
    }

    override func draggingEntered(_ sender: any NSDraggingInfo) -> NSDragOperation {
        let items = draggedItems(sender)
        guard controller.canTransferFiles, sender.draggingSource == nil, !items.isEmpty else { return [] }
        controller.dropEntered(
            names: items.flatMap { item -> [String] in
                if let url = item as? URL {
                    return [(try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true ? url.lastPathComponent + "/" : url.lastPathComponent]
                }
                // -[NSFilePromiseReceiver initWithPasteboardPropertyList:ofType:] fills in only the file
                // types; the names arrive with the files (AppKit 2775.10.103.1, macOS 27.0.1 26A434,
                // UUID 2688AFBC-5C39-3132-BCF5-8D00D32D7615,
                // -[NSFilePromiseReceiver initWithPasteboardPropertyList:ofType:] 0x185b162c8..0x185b164d0,
                // -[NSFilePromiseReceiver receivePromisedFilesAtDestination:options:operationQueue:reader:] 0x185b169e4).
                return ((item as? NSFilePromiseReceiver)?.fileTypes ?? []).map { identifier in
                    let type = UTType(identifier)
                    let name = String(localized: "Item")
                    if type?.conforms(to: .directory) == true { return name + "/" }
                    return type?.preferredFilenameExtension.map { "\(name).\($0)" } ?? name
                }
            })
        return draggingUpdated(sender)
    }

    override func draggingUpdated(_ sender: any NSDraggingInfo) -> NSDragOperation {
        controller.dropMoved(to: imagePoint(for: convert(sender.draggingLocation, from: nil)))
        return controller.dropTarget == nil ? [] : .copy
    }

    override func draggingExited(_ sender: (any NSDraggingInfo)?) {
        controller.dropExited()
    }

    override func performDragOperation(_ sender: any NSDraggingInfo) -> Bool {
        let items = draggedItems(sender)
        guard controller.dropTarget != nil, !items.isEmpty else { return false }
        let receivers = items.compactMap { $0 as? NSFilePromiseReceiver }
        let loaded = controller.drop(files: items.compactMap { $0 as? URL }, loading: receivers.reduce(0) { $0 + $1.fileTypes.count })
        guard !receivers.isEmpty else { return true }
        let folder = AppDirectory.caches.appendingPathComponent("Drops", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for receiver in receivers {
            receiver.receivePromisedFiles(atDestination: folder, options: [:], operationQueue: Self.promiseQueue) { @Sendable url, error in
                Task { @MainActor in loaded(error == nil ? url : nil, url.lastPathComponent, error) }
            }
        }
        return true
    }

    override func draggingEnded(_ sender: any NSDraggingInfo) {
        controller.dropExited()
    }
}

extension RemoteScreenNSView: NSDraggingSource, NSFilePromiseProviderDelegate {
    private func dragOut(_ offer: RemoteDragOffer, event: NSEvent, at location: CGPoint) {
        let scale = displayScale
        let items = offer.items.enumerated().map { index, item in
            // -[NSFilePromiseProvider initWithFileType:delegate:] throws for a type conforming to neither
            // public.data nor public.directory (AppKit 2775.10.103.1, macOS 27.0.1 26A434,
            // UUID 2688AFBC-5C39-3132-BCF5-8D00D32D7615,
            // -[NSFilePromiseProvider initWithFileType:delegate:] 0x1859e5588..0x1859e566c).
            let extensionType = UTType(filenameExtension: (item.path as NSString).pathExtension).flatMap { type in
                type.isDeclared && (type.conforms(to: .data) || type.conforms(to: .directory)) ? type : nil
            }
            let type = item.isFolder ? .folder : extensionType ?? .data
            let provider = NSFilePromiseProvider(fileType: type.identifier, delegate: self)
            provider.userInfo = item.path
            let draggingItem = NSDraggingItem(pasteboardWriter: provider)
            if let image = offer.image {
                let size = CGSize(width: CGFloat(image.width) * scale, height: CGFloat(image.height) * scale)
                draggingItem.setDraggingFrame(
                    CGRect(origin: CGPoint(x: location.x - size.width / 2, y: location.y - size.height / 2), size: size),
                    contents: index == 0 ? NSImage(cgImage: image, size: size) : nil)
            } else {
                let offset = CGFloat(index) * 4
                draggingItem.setDraggingFrame(
                    CGRect(x: location.x - 24 + offset, y: location.y - 24 + offset, width: 48, height: 48),
                    contents: NSWorkspace.shared.icon(for: type))
            }
            return draggingItem
        }
        beginDraggingSession(with: items, event: event, source: self)
    }

    func draggingSession(_ session: NSDraggingSession, sourceOperationMaskFor context: NSDraggingContext) -> NSDragOperation {
        context == .outsideApplication ? .copy : []
    }

    nonisolated func filePromiseProvider(_ provider: NSFilePromiseProvider, fileNameForType fileType: String) -> String {
        ((provider.userInfo as? String ?? "") as NSString).lastPathComponent
    }

    func filePromiseProvider(
        _ provider: NSFilePromiseProvider, writePromiseTo url: URL, completionHandler: @escaping @Sendable ((any Error)?) -> Void
    ) {
        guard let path = provider.userInfo as? String else { return completionHandler(CocoaError(.fileNoSuchFile)) }
        let folder: URL
        do {
            folder = try FileManager.default.url(for: .itemReplacementDirectory, in: .userDomainMask, appropriateFor: url, create: true)
        } catch {
            return completionHandler(error)
        }
        let progress = Progress(totalUnitCount: 0)
        progress.kind = .file
        progress.fileOperationKind = .downloading
        progress.fileURL = url
        progress.publish()
        controller.downloadFile(path, into: folder, progress: progress) { result in
            var moveError: (any Error)?
            do {
                try FileManager.default.moveItem(at: result.get(), to: url)
            } catch {
                moveError = error
            }
            try? FileManager.default.removeItem(at: folder)
            progress.unpublish()
            completionHandler(moveError)
        }
    }
}

extension RemoteScreenNSView: NSMenuItemValidation {
    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        if menuItem.action == #selector(paste(_:)) {
            return controller.isControlling && NSPasteboard.general.types?.isEmpty == false
        }
        return true
    }
}

private extension GesturePhase {
    init?(_ phase: NSEvent.Phase) {
        switch phase {
        case .mayBegin: self = .mayBegin
        case .began: self = .began
        case .changed: self = .changed
        case .ended: self = .ended
        case .cancelled: self = .cancelled
        default: return nil
        }
    }
}

extension StandardCursor {
    var systemCursor: NSCursor? {
        switch self {
        case .arrow: return .arrow
        case .contextualMenu: return .contextualMenu
        case .pointingHand: return .pointingHand
        case .openHand: return .openHand
        case .closedHand: return .closedHand
        case .dragLink: return .dragLink
        case .dragCopy: return .dragCopy
        case .operationNotAllowed: return .operationNotAllowed
        case .iBeam: return .iBeam
        case .verticalIBeam: return .iBeamCursorForVerticalLayout
        case .crosshair: return .crosshair
        case .zoomIn:
            guard #available(macOS 15, *) else { return nil }
            return .zoomIn
        case .zoomOut:
            guard #available(macOS 15, *) else { return nil }
            return .zoomOut
        case .resize(let directions):
            if #available(macOS 15, *) {
                let positions: [(StandardCursor.ResizeDirections, NSCursor.FrameResizePosition)] = [
                    (.up, .top), (.down, .bottom), (.left, .left), (.right, .right),
                    (.upLeft, .topLeft), (.upRight, .topRight), (.downLeft, .bottomLeft), (.downRight, .bottomRight),
                ]
                if let position = positions.first(where: { $0.0 == directions })?.1 {
                    return .frameResize(position: position, directions: .outward)
                }
                switch directions {
                case [.up, .down]: return .frameResize(position: .top, directions: .all)
                case [.left, .right]: return .frameResize(position: .left, directions: .all)
                case [.upLeft, .downRight]: return .frameResize(position: .topLeft, directions: .all)
                case [.upRight, .downLeft]: return .frameResize(position: .topRight, directions: .all)
                default: return nil
                }
            }
            switch directions {
            case .up: return .resizeUp
            case .down: return .resizeDown
            case .left: return .resizeLeft
            case .right: return .resizeRight
            case [.up, .down]: return .resizeUpDown
            case [.left, .right]: return .resizeLeftRight
            default: return nil
            }
        }
    }
}
#endif
