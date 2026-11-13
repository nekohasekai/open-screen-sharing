#if os(iOS)
import SwiftUI
import UIKit

struct RemoteScreenView: UIViewRepresentable {
    let controller: SessionController
    let matchesWindow: Bool
    let preferredOrientation: UIInterfaceOrientationMask?
    let fillsScreen: Bool
    let mode: SessionMode

    init(controller: SessionController) {
        self.controller = controller
        matchesWindow = controller.wantsVirtualDisplay
        preferredOrientation = controller.preferredOrientation
        fillsScreen = AppSettings.shared.pictureFillsScreen
        mode = controller.mode
    }

    func makeUIView(context: Context) -> RemoteScreenUIView {
        RemoteScreenUIView(controller: controller)
    }

    func updateUIView(_ view: RemoteScreenUIView, context: Context) {
        view.windowTracker.setMatchesWindow(matchesWindow)
        view.windowTracker.setPreferredOrientation(preferredOrientation)
        view.windowTracker.setFillsScreen(fillsScreen)
        view.accessibilityValue = String(localized: mode == .control ? "Controlling" : "Observing")
    }
}

final class RemoteScreenUIView: UIView, RemoteScreen {
    override class var layerClass: AnyClass { CAMetalLayer.self }

    private static let minimumZoom: CGFloat = 0.25

    private unowned let controller: SessionController
    private var presenter: ScreenPresenter!
    private let keyInput = KeyInputProxy()
    let pressIndicator = PressIndicatorView()
    private let loupe = PointerLoupeView()
    private let systemInput = KeyInputProxy(systemInput: true)
    private var systemInputReturnsToKeyboard = false
    private var dictationPress: UIPress?
    private var input: ScreenInput!
    private(set) var windowTracker: ScreenWindowTracker!

    private(set) var zoom: CGFloat = 1
    private var viewportCenter: CGPoint?
    private var keyboardLift: CGFloat = 0
    private var keyboardTop: CGFloat?

    private var metalLayer: CAMetalLayer { layer as! CAMetalLayer }

    init(controller: SessionController) {
        self.controller = controller
        super.init(frame: .zero)
        backgroundColor = .black
        isMultipleTouchEnabled = true
        presenter = ScreenPresenter(screen: self, controller: controller, layer: metalLayer)
        controller.screen = self
        for proxy in [keyInput, systemInput] {
            proxy.controller = controller
            proxy.screen = self
            addSubview(proxy)
        }
        if #available(iOS 17, *) {
            keyboardLayoutGuide.usesBottomSafeArea = false
        }
        let keyboardTracker = UIView()
        keyboardTracker.isHidden = true
        keyboardTracker.translatesAutoresizingMaskIntoConstraints = false
        addSubview(keyboardTracker)
        NSLayoutConstraint.activate([
            keyboardTracker.leadingAnchor.constraint(equalTo: leadingAnchor),
            keyboardTracker.widthAnchor.constraint(equalToConstant: 1),
            keyboardTracker.heightAnchor.constraint(equalToConstant: 1),
            keyboardTracker.bottomAnchor.constraint(equalTo: keyboardLayoutGuide.topAnchor),
        ])
        addSubview(pressIndicator)
        addSubview(loupe)
        isAccessibilityElement = true
        accessibilityLabel = String(localized: "Screen of \(controller.title)")
        accessibilityTraits = .allowsDirectInteraction
        input = ScreenInput(view: self, controller: controller)
        windowTracker = ScreenWindowTracker(view: self) { [weak self] area, scale, screen in
            self?.controller.displayAreaDidChange(area, scale: scale, screen: screen)
        }
        accessibilityCustomActions = input.accessibilityActions()
        NotificationCenter.default.addObserver(self, selector: #selector(sceneWillDeactivate(_:)), name: UIScene.willDeactivateNotification, object: nil)
    }

    required init?(coder: NSCoder) {
        fatalError()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if let window {
            window.windowScene?.title = controller.title
            if #available(iOS 16, *) {
                controller.displaySupportsHDR = (window.windowScene?.screen.potentialEDRHeadroom ?? 1) > 1
            }
            presenter.start(CADisplayLink.init(target:selector:))
            presenter.setRefreshRate(window.windowScene?.screen.maximumFramesPerSecond ?? 0)
            DispatchQueue.main.async { [weak self] in
                self?.takeKeyFocus()
            }
        } else {
            presenter.stop()
            controller.releaseAllKeys()
        }
        windowTracker.windowDidChange()
        input.windowDidChange()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = window?.screen.scale ?? traitCollection.displayScale
        contentScaleFactor = scale
        metalLayer.contentsScale = scale
        metalLayer.drawableSize = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        presenter.setRefreshRate(window?.windowScene?.screen.maximumFramesPerSecond ?? 0)
        windowTracker.geometryDidChange()
        if controller.scaleMode == .actualSize {
            applyScale(.actualSize)
        }
        clampCenter()
        let top = coveredKeyboardTop
        if top != keyboardTop {
            keyboardTop = top
            keyboardLift = 0
            liftAboveKeyboard()
        }
        setNeedsRender()
    }

    override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        windowTracker.geometryDidChange()
        setNeedsRender()
    }


    var isOccluded: Bool {
        controller.sceneInBackground
    }

    func drawnCursor(_ shape: CursorShape?, visible: Bool, scale: CGFloat) -> (shape: CursorShape, shownBySystem: Bool)? {
        let shownBySystem = input.showsCursorAsPointer(shape, visible: visible, scale: scale)
        guard let cursor = input.localPointer(for: shape, scale: scale) ?? shape else { return nil }
        return (cursor, shownBySystem)
    }

    func cursorDidRender(in rect: CGRect?) {
        pressIndicator.center = rect.map { CGPoint(x: $0.midX, y: $0.midY) } ?? viewPoint(for: controller.pointerPosition)
    }

    func loupe(for icon: CGRect?) -> LoupeSpot? {
        guard windowTracker.fillsScreen, let icon, bounds.intersects(icon),
            let spot = windowTracker.obstructions.loupe(for: icon, in: bounds)
        else {
            loupe.isHidden = true
            return nil
        }
        let scale = contentScaleFactor
        func pixelAligned(_ rect: CGRect) -> CGRect {
            CGRect(
                x: (rect.minX * scale).rounded() / scale, y: (rect.minY * scale).rounded() / scale,
                width: (rect.width * scale).rounded() / scale, height: (rect.height * scale).rounded() / scale)
        }
        let aligned = LoupeSpot(source: pixelAligned(spot.source), frame: pixelAligned(spot.frame))
        loupe.show(content: aligned.frame)
        return aligned
    }

    func backgroundStateDidChange() {
        if controller.sceneInBackground {
            presenter.pause()
        } else {
            setNeedsRender()
        }
    }

    func setNeedsRender() {
        updateLetterbox()
        controller.debugInfoInset =
            windowTracker.fillsScreen
            ? windowTracker.obstructions.topCornerInset(left: effectiveUserInterfaceLayoutDirection == .rightToLeft, in: bounds) : nil
        presenter.setNeedsRender()
    }

    private func updateLetterbox() {
        let letterbox = measureLetterbox()
        controller.letterbox = letterbox
    }

    private func measureLetterbox() -> Letterbox? {
        guard window != nil, controller.imageSize.width > 0, controller.imageSize.height > 0 else { return nil }
        let picture = imageRect
        let column: CGRect
        let row: UIEdgeInsets
        if #available(iOS 26, *) {
            column = bounds.inset(by: edgeInsets(for: .safeArea(cornerAdaptation: .vertical)))
            row = edgeInsets(for: .safeArea(cornerAdaptation: .horizontal))
        } else {
            column = bounds.inset(by: safeAreaInsets)
            row = safeAreaInsets
        }
        let side = max(column.minX - bounds.minX, bounds.maxX - column.maxX)
        let cornerSide = max(row.left, row.right)
        let bottom = min(column.maxY, keyboardTop ?? column.maxY)
        return Letterbox(
            left: CGRect(x: column.minX, y: column.minY, width: max(0, picture.minX - column.minX), height: max(0, bottom - column.minY)),
            top: CGRect(x: side, y: column.minY, width: max(0, bounds.width - 2 * side), height: max(0, picture.minY - column.minY)),
            topBetweenCorners: CGRect(
                x: cornerSide, y: row.top, width: max(0, bounds.width - 2 * cornerSide), height: max(0, picture.minY - row.top))
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
        liftAboveKeyboard()
        setNeedsRender()
    }


    private var imageSize: CGSize {
        let size = controller.imageSize
        return size.width > 0 && size.height > 0 ? size : CGSize(width: 16, height: 10)
    }

    private var fitArea: CGRect {
        windowTracker.screenArea ?? bounds
    }

    private var coveredKeyboardTop: CGFloat? {
        guard keyInput.isFirstResponder else { return nil }
        let top = keyboardLayoutGuide.layoutFrame.minY
        let area = fitArea
        return top > area.minY && top < area.maxY ? top : nil
    }

    private func liftAboveKeyboard() {
        guard let keyboardTop else {
            keyboardLift = 0
            return
        }
        let margin: CGFloat = 48
        let unlifted = imageRect.offsetBy(dx: 0, dy: keyboardLift)
        let pointerY = unlifted.minY + controller.pointerPosition.y * displayScale
        let highest = max(0, unlifted.maxY - keyboardTop)
        var lift = keyboardLift
        if pointerY - lift > keyboardTop - margin {
            lift = pointerY - (keyboardTop - margin)
        } else if pointerY - lift < fitArea.minY + margin {
            lift = pointerY - (fitArea.minY + margin)
        }
        keyboardLift = min(max(lift, 0), highest)
    }

    private var fitScale: CGFloat {
        let area = fitArea
        let scale = min(area.width / imageSize.width, area.height / imageSize.height)
        let devicePixels = scale * contentScaleFactor
        return devicePixels >= 1 && devicePixels < 1.01 ? 1 / contentScaleFactor : scale
    }

    var displayScale: CGFloat {
        fitScale * zoom
    }

    private var maximumZoom: CGFloat {
        max(4, (8 / contentScaleFactor) / max(fitScale, 0.0001))
    }

    private var actualSizeZoom: CGFloat {
        (1 / contentScaleFactor) / max(fitScale, 0.0001)
    }

    var imageRect: CGRect {
        let scale = displayScale
        let size = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
        let area = fitArea
        var origin = CGPoint(x: area.midX - size.width / 2, y: area.midY - size.height / 2)
        if let center = viewportCenter {
            origin = CGPoint(x: bounds.midX - center.x * scale, y: bounds.midY - center.y * scale)
        }
        origin.y -= keyboardLift
        origin.x = (origin.x * contentScaleFactor).rounded() / contentScaleFactor
        origin.y = (origin.y * contentScaleFactor).rounded() / contentScaleFactor
        return CGRect(origin: origin, size: size)
    }

    private func clampCenter() {
        guard let center = viewportCenter else { return }
        let scale = displayScale
        let area = fitArea
        func clamp(_ center: CGFloat, image: CGFloat, area: ClosedRange<CGFloat>, bounds: ClosedRange<CGFloat>) -> CGFloat {
            let size = image * scale
            let middle = (bounds.lowerBound + bounds.upperBound) / 2
            let origins: ClosedRange<CGFloat>
            if size > bounds.upperBound - bounds.lowerBound {
                origins = (bounds.upperBound - size)...bounds.lowerBound
            } else if size > area.upperBound - area.lowerBound {
                let centered = (area.lowerBound + area.upperBound - size) / 2
                origins = centered...centered
            } else {
                origins = area.lowerBound...(area.upperBound - size)
            }
            let origin = min(max(middle - center * scale, origins.lowerBound), origins.upperBound)
            return (middle - origin) / scale
        }
        viewportCenter = CGPoint(
            x: clamp(center.x, image: imageSize.width, area: area.minX...area.maxX, bounds: bounds.minX...bounds.maxX),
            y: clamp(center.y, image: imageSize.height, area: area.minY...area.maxY, bounds: bounds.minY...bounds.maxY)
        )
    }

    private func currentCenter() -> CGPoint {
        let rect = imageRect.offsetBy(dx: 0, dy: keyboardLift)
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
        liftAboveKeyboard()
        setNeedsRender()
    }

    func toggleZoom(at viewPoint: CGPoint) {
        if controller.scaleMode != .fit {
            applyScale(.fit)
        } else {
            setZoom(max(actualSizeZoom, 2), keeping: imagePoint(for: viewPoint), at: viewPoint)
        }
    }

    func setZoom(_ newZoom: CGFloat, keeping imageAnchor: CGPoint, at viewAnchor: CGPoint) {
        zoom = min(max(newZoom, Self.minimumZoom), maximumZoom)
        let scale = displayScale
        viewportCenter = CGPoint(
            x: imageAnchor.x + (bounds.midX - viewAnchor.x) / scale,
            y: imageAnchor.y + (bounds.midY - viewAnchor.y - keyboardLift) / scale
        )
        clampCenter()
        updateScaleMode()
        liftAboveKeyboard()
        setNeedsRender()
    }

    private func updateScaleMode() {
        let mode: ScaleMode = abs(zoom - 1) < 0.01 ? .fit : (abs(zoom - actualSizeZoom) < 0.01 ? .actualSize : .custom)
        controller.scaleMode = mode
    }

    func keepPointerVisible() {
        defer {
            if keyboardTop != nil {
                let lift = keyboardLift
                liftAboveKeyboard()
                if lift != keyboardLift { setNeedsRender() }
            }
        }
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

    func panViewport(by delta: CGPoint) {
        let scale = displayScale
        let current = currentCenter()
        viewportCenter = CGPoint(x: current.x - delta.x / scale, y: current.y - delta.y / scale)
        clampCenter()
        setNeedsRender()
    }


    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        input.touchesBegan(touches, with: event)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        input.touchesMoved(touches, with: event)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        input.touchesEnded(touches, with: event)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        input.touchesCancelled(touches, with: event)
    }

    override func accessibilityPerformMagicTap() -> Bool {
        controller.toggleKeyboard()
        return true
    }


    override var canBecomeFirstResponder: Bool { true }

    override var editingInteractionConfiguration: UIEditingInteractionConfiguration { .none }

    func takeKeyFocus() {
        guard window != nil, !isFirstResponder, !keyInput.isFirstResponder else { return }
        if systemInput.isFirstResponder {
            endSystemInput()
        } else {
            becomeFirstResponder()
        }
    }

    func setKeyboardVisible(_ visible: Bool) {
        guard window != nil else { return }
        if visible {
            guard !keyInput.isFirstResponder, !keyInput.becomeFirstResponder() else { return }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.window != nil, !self.keyInput.isFirstResponder else { return }
                self.keyInput.becomeFirstResponder()
            }
        } else if keyInput.isFirstResponder, !becomeFirstResponder() {
            keyInput.resignFirstResponder()
        }
    }

    func forgetTypedText() {
        keyInput.forgetTypedText()
        systemInput.forgetTypedText()
    }

    private func beginSystemInput() -> Bool {
        guard window != nil, !systemInput.isFirstResponder else { return false }
        systemInputReturnsToKeyboard = keyInput.isFirstResponder
        systemInput.frame = CGRect(origin: viewPoint(for: controller.pointerPosition), size: CGSize(width: 1, height: 1))
        return systemInput.beginSystemInput()
    }

    func endSystemInput() {
        guard systemInput.isFirstResponder else { return }
        let returned = systemInputReturnsToKeyboard ? keyInput.becomeFirstResponder() : becomeFirstResponder()
        if !returned {
            systemInput.resignFirstResponder()
        }
    }

    override func resignFirstResponder() -> Bool {
        let resigned = super.resignFirstResponder()
        if resigned, !keyInput.isFirstResponder, !systemInput.isFirstResponder {
            controller.releaseAllKeys()
        }
        return resigned
    }

    @objc private func sceneWillDeactivate(_ notification: Notification) {
        guard let scene = notification.object as? UIScene, scene === window?.windowScene else { return }
        controller.releaseAllKeys()
    }

    // UIKit performs main menu key commands through this action, which then sends each command's own action.
    static let mainMenuShortcut = NSSelectorFromString("_performMainMenuShortcutKeyCommand:")

    override var keyCommands: [UIKeyCommand]? { KeyShortcuts.shared.passthroughKeyCommands(includingAssigned: false) }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let unhandled = handlePresses(presses, phase: .began)
        if !unhandled.isEmpty {
            super.pressesBegan(unhandled, with: event)
        }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let unhandled = handlePresses(presses, phase: .ended)
        if !unhandled.isEmpty {
            super.pressesEnded(unhandled, with: event)
        }
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let unhandled = handlePresses(presses, phase: .cancelled)
        if !unhandled.isEmpty {
            super.pressesCancelled(unhandled, with: event)
        }
    }

    func handlePresses(_ presses: Set<UIPress>, phase: UIPress.Phase) -> Set<UIPress> {
        let down = phase == .began
        return presses.filter { press in
            if let dictationPress, press == dictationPress {
                guard !down else { return false }
                self.dictationPress = nil
                if phase == .ended {
                    DispatchQueue.main.async { self.systemInput.startDictation() }
                }
                return false
            }
            let isGlobe = press.key.map { KeyInputProxy.globeKeyCodes.contains($0.keyCode.rawValue) } ?? false
            if down, controller.isControlling, !systemInput.isFirstResponder {
                if press.type.rawValue == KeyInputProxy.dictationPressType {
                    if beginSystemInput() { dictationPress = press }
                    return false
                }
                if isGlobe {
                    if beginSystemInput() { systemInput.awaitGlobe() }
                    return false
                }
            }
            guard let key = press.key else { return true }
            if down, systemInput.isFirstResponder, !isGlobe, ModifierKey(keysym: Keysym.special(for: key.keyCode) ?? 0) == nil,
                key.keyCode != .keyboardCapsLock
            {
                endSystemInput()
            }
            return !controller.handlePress(key, down: down)
        }
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(paste(_:)) {
            return controller.clipboardSharing && controller.isControlling && UIPasteboard.general.hasStrings
        }
        return false
    }

    override func paste(_ sender: Any?) {
        controller.pasteFromDevice()
    }

    override func target(forAction action: Selector, withSender sender: Any?) -> Any? {
        if action == #selector(paste(_:)) {
            return canPerformAction(action, withSender: sender) ? self : nil
        }
        return controller.isControlling && action != Self.mainMenuShortcut ? nil : super.target(forAction: action, withSender: sender)
    }
}
#endif
