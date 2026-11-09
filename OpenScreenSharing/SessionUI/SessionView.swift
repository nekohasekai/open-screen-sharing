import Perception
import SwiftUI

struct SessionView: View {
    #if os(macOS)
    @SwiftUI.Bindable private var controller: SessionController
    #else
    @Perception.Bindable private var controller: SessionController
    #endif
    private let settings = AppSettings.shared
    private let hasOwnWindow: Bool
    private let onClose: () -> Void
    @ScaledMetric(relativeTo: .body) private var toolbarItemWidth = ToolbarLayout.itemSize

    private var dock: ToolbarDock? {
        controller.pictureMatchesWindow ? nil : ToolbarDock(letterbox: controller.letterbox, itemWidth: toolbarItemWidth)
    }
    #if os(iOS)
    @Environment(\.scenePhase) private var scenePhase
    @State private var toolbarWidth: CGFloat = 0

    private var toolbarSlots: Int {
        ToolbarLayout.capsuleSlots(width: dock?.length ?? toolbarWidth, itemWidth: toolbarItemWidth)
    }

    private var locksPointer: Bool {
        controller.isPointerLockOn && pointerLockAvailable && !pointerLockPaused && controller.pointerLockCanResume
            && (dock == nil || controller.isSystemPointerOverPicture)
    }
    #else
    @State private var showsPointerLockHint = false

    private var toolbarSlots: Int? {
        dock.map { ToolbarLayout.capsuleSlots(width: $0.length, itemWidth: toolbarItemWidth) }
    }

    private var locksPointer: Bool {
        controller.isPointerLockOn && pointerLockAvailable && !pointerLockPaused
    }

    private var minimumWindowSize: CGSize {
        controller.pictureMatchesWindow ? VirtualDisplayMode.minimumWindowPoints : CGSize(width: 320, height: 200)
    }
    #endif

    private var pointerLockAvailable: Bool {
        SessionCommand.pointerLock.isOffered(by: controller) && controller.canLockPointer
    }

    private var pointerLockPaused: Bool {
        controller.isToolbarVisible || controller.isZoomModeOn || !controller.isControlling || controller.usesOnScreenInput
            || controller.picture == .none
            || controller.isShowingResolution
            || controller.isCustomizingToolbar || controller.isEditingQuickInputs
            || controller.credentialPrompt != nil || controller.alert != nil
    }

    private var colorScheme: ColorScheme? {
        controller.picture == .none ? .dark : nil
    }

    init(controller: SessionController, hasOwnWindow: Bool = true, onClose: @escaping () -> Void) {
        self.controller = controller
        self.hasOwnWindow = hasOwnWindow
        self.onClose = onClose
    }

    #if os(iOS)
    private var deviceScreen: CGSize? {
        guard let screen = controller.touchScreenSize else { return nil }
        let picture = controller.imageSize
        guard picture.width > 0, picture.height > 0, (picture.width > picture.height) != (screen.width > screen.height)
        else { return screen }
        return CGSize(width: screen.height, height: screen.width)
    }

    var body: some View {
        WithPerceptionTracking {
            let dock = dock
            let plan = ToolbarLayout.layout(for: controller).plan(for: controller, slots: toolbarSlots)
            SessionInputLayout(controller: controller) {
                screen
            }
                .background {
                    Color.black.ignoresSafeArea()
                    FullScreenReader(isFullScreen: $controller.isFullScreen)
                        .ignoresSafeArea()
                    if #available(iOS 26, *), controller.platform == .iOS, hasOwnWindow {
                        DeviceWindowShaper(screen: deviceScreen, isFullScreen: controller.isFullScreen)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if settings.showsDebugInfo, controller.phase.isActive {
                        let inset = controller.debugInfoInset
                        DebugInfoView(controller: controller)
                            .padding(EdgeInsets(top: inset ?? 8, leading: 8, bottom: 8, trailing: inset ?? 8))
                            .ignoresSafeArea(edges: inset == nil ? [] : [.top, .trailing])
                            .allowsHitTesting(false)
                    }
                }
                .overlay(alignment: .top) {
                    FloatingToolbar(controller: controller, plan: plan, dock: dock, closes: controller.isFullScreen || !hasOwnWindow, onClose: controller.close)
                }
                .onGeometryChangeCompat(for: CGFloat.self) { proxy in
                    proxy.size.width - proxy.safeAreaInsets.leading - proxy.safeAreaInsets.trailing
                } action: { width in
                    toolbarWidth = width
                }
                .preferredColorScheme(colorScheme)
                .statusBarHidden(controller.isFullScreen)
                .persistentSystemOverlaysCompat(controller.isFullScreen ? .hidden : .automatic)
                .defersSystemGesturesCompat(on: controller.isControlling ? .all : [])
                .background { PointerLockRequest(locked: locksPointer) }
                .onChangeCompat(of: pointerLockAvailable) { available in
                    controller.pointerLockAvailabilityDidChange(available)
                }
                .onChangeCompat(of: pointerLockPaused) { paused in
                    if !paused, !controller.pointerLockCanResume { controller.setPointerLock(false) }
                }
                .focusedSceneValue(\.sessionController, controller)
                .sheet(isPresented: $controller.isCustomizingToolbar) {
                    WithPerceptionTracking {
                        CustomizeToolbarView(controller: controller, slots: toolbarSlots)
                    }
                }
                .sheet(isPresented: $controller.isEditingQuickInputs) {
                    NavigationStackCompat {
                        QuickInputView()
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    CloseButton { controller.isEditingQuickInputs = false }
                                }
                            }
                    }
                }
                .alert($controller.alert)
                .sheet(
                    item: Binding(
                        get: { controller.credentialPrompt },
                        set: { prompt in
                            if prompt == nil, controller.credentialPrompt != nil {
                                controller.cancelCredentials()
                            }
                        })
                ) { prompt in
                    WithPerceptionTracking {
                        CredentialsView(prompt: prompt, computerName: controller.title) { credentials, remember in
                            controller.submitCredentials(credentials, remember: remember)
                        } onCancel: {
                            controller.cancelCredentials()
                        }
                    }
                }
                .onAppear {
                    controller.onClose = onClose
                    controller.start()
                }
                .onDisappear {
                    controller.abort()
                }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.willTerminateNotification)) { _ in
                    controller.abort()
                }
                .onChangeCompat(of: controller.isFullScreen) {
                    controller.isToolbarVisible = false
                }
                .onChangeCompat(of: dock != nil, initial: true) { docked in
                    controller.isToolbarDocked = docked
                    if docked {
                        controller.isToolbarVisible = false
                    }
                }
                .onChangeCompat(of: scenePhase) { phase in
                    switch phase {
                    case .background: controller.sceneDidEnterBackground()
                    case .inactive: ClipboardAccess.shared.sceneDidBecomeInactive()
                    case .active: controller.sceneDidBecomeActive()
                    @unknown default: break
                    }
                }
        }
    }
    #else
    var body: some View {
        let dock = dock
        let plan = ToolbarLayout.layout(for: controller).plan(for: controller, slots: toolbarSlots)
        screen
            .background(Color.black)
            .overlay(alignment: .topTrailing) {
                if settings.showsDebugInfo, controller.phase.isActive {
                    DebugInfoView(controller: controller)
                        .padding(8)
                        .allowsHitTesting(false)
                }
            }
            .overlay {
                FloatingToolbar(controller: controller, plan: plan, dock: dock, closes: false, onClose: controller.close)
            }
            .overlay(alignment: .top) {
                if showsPointerLockHint {
                    Text("Press \(EventModifiers(KeyShortcuts.shared.pointerRelease).symbols) to release the pointer")
                        .font(.callout)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.regularMaterial, in: Capsule())
                        .padding(.top, 12)
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }
            }
            .frame(minWidth: minimumWindowSize.width, minHeight: minimumWindowSize.height)
            .navigationTitle(controller.title)
            .navigationSubtitle(controller.mode == .observe ? String(localized: "Observing") : "")
            .toolbar {
                if dock == nil {
                    SessionWindowToolbar(controller: controller, commands: plan.inBar)
                }
            }
            .windowToolbarFullScreenVisibilityCompat()
            .sheet(isPresented: $controller.isCustomizingToolbar) {
                CustomizeToolbarView(controller: controller, slots: toolbarSlots)
            }
            .preferredColorScheme(colorScheme)
            .onChange(of: locksPointer, initial: true) { _, locks in
                controller.screen?.setLocksPointer(locks)
            }
            .task(id: controller.isPointerLocked) {
                guard controller.isPointerLocked else {
                    showsPointerLockHint = false
                    return
                }
                withAnimation { showsPointerLockHint = true }
                do {
                    try await Task.sleep(seconds: 3)
                } catch { return }
                withAnimation { showsPointerLockHint = false }
            }
            .focusedSceneValue(\.sessionController, controller)
            .alert($controller.alert)
            .sheet(
                item: Binding(
                    get: { controller.credentialPrompt },
                    set: { prompt in
                        if prompt == nil, controller.credentialPrompt != nil {
                            controller.cancelCredentials()
                        }
                    })
            ) { prompt in
                CredentialsView(prompt: prompt, computerName: controller.title) { credentials, remember in
                    controller.submitCredentials(credentials, remember: remember)
                } onCancel: {
                    controller.cancelCredentials()
                }
            }
            .onAppear {
                controller.onClose = onClose
                controller.start()
            }
            .onDisappear {
                controller.abort()
            }
            .onReceive(NotificationCenter.default.publisher(for: NSApplication.willTerminateNotification)) { _ in
                controller.abort()
            }
    }
    #endif

    private var screen: some View {
        ZStack {
            RemoteScreenView(controller: controller)
            if controller.dropTarget == .desktop {
                RoundedRectangle(cornerRadius: 24)
                    .strokeBorder(Color.accentColor, lineWidth: 4)
                    .overlay {
                        Label("Send to Desktop", systemImage: "arrow.up.doc")
                            .font(.headline)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 14)
                            .glassEffectCompat(in: Capsule())
                    }
                    .allowsHitTesting(false)
            }
            SessionStatusOverlay(controller: controller, onClose: controller.close)
                .environment(\.colorScheme, .dark)
        }
    }
}

#if os(iOS)
@available(iOS 26, *)
private struct DeviceWindowShaper: UIViewRepresentable {
    let screen: CGSize?
    let isFullScreen: Bool

    func makeUIView(context: Context) -> ShaperView {
        ShaperView()
    }

    func updateUIView(_ view: ShaperView, context: Context) {
        view.screen = screen
        view.isFullScreen = isFullScreen
        view.shape()
    }

    final class ShaperView: UIView {
        var screen: CGSize?
        var isFullScreen = false
        private weak var scene: UIWindowScene?
        private var observation: NSKeyValueObservation?
        private var hasShaped = false
        private var requested: CGSize?
        private var minimumSize: CGSize?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) {
            fatalError()
        }

        deinit {
            MainActor.assumeIsolated { restoreMinimumSize() }
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            let newScene = window?.windowScene
            if newScene !== scene {
                observation = nil
                restoreMinimumSize()
                scene = newScene
                requested = nil
                hasShaped = false
                observation = newScene?.observe(\.effectiveGeometry) { [weak self] _, _ in
                    DispatchQueue.main.async { self?.shape() }
                }
            }
            shape()
        }

        func shape() {
            guard let scene, let restrictions = scene.sizeRestrictions else { return }
            guard let screen, screen.width > 0, screen.height > 0, !isFullScreen else {
                restoreMinimumSize()
                requested = nil
                return
            }
            if scene.effectiveGeometry.isInteractivelyResizing {
                restoreMinimumSize()
                requested = nil
                return
            }
            if let requested {
                let current = scene.effectiveGeometry.coordinateSpace.bounds.size
                if current.width >= requested.width - 2, current.height >= requested.height - 2 {
                    restoreMinimumSize()
                }
            }
            let target = target(for: scene, screen: screen, atLeastScreen: !hasShaped)
            hasShaped = true
            guard let target, !Self.isClose(target, requested) else { return }
            if minimumSize == nil || restrictions.minimumSize != requested {
                minimumSize = restrictions.minimumSize
            }
            requested = target
            restrictions.minimumSize = target
        }

        private func restoreMinimumSize() {
            if let minimum = minimumSize, let restrictions = scene?.sizeRestrictions,
                restrictions.minimumSize == requested
            {
                restrictions.minimumSize = minimum
            }
            minimumSize = nil
        }

        private func target(for scene: UIWindowScene, screen: CGSize, atLeastScreen: Bool) -> CGSize? {
            let current = scene.effectiveGeometry.coordinateSpace.bounds.size
            guard current.width > 0, current.height > 0 else { return nil }
            var target =
                current.width / current.height < screen.width / screen.height
                ? CGSize(width: current.height * screen.width / screen.height, height: current.height)
                : CGSize(width: current.width, height: current.width * screen.height / screen.width)
            if atLeastScreen, target.width < screen.width {
                target = screen
            }
            let available = scene.screen.bounds.size
            let fit = min(1, available.width / target.width, available.height / target.height)
            target = CGSize(width: target.width * fit, height: target.height * fit)
            return target.width > current.width + 2 || target.height > current.height + 2 ? target : nil
        }

        private static func isClose(_ size: CGSize, _ other: CGSize?) -> Bool {
            guard let other else { return false }
            return abs(size.width - other.width) <= 2 && abs(size.height - other.height) <= 2
        }
    }
}

private struct FullScreenReader: UIViewRepresentable {
    @Binding var isFullScreen: Bool

    func makeUIView(context: Context) -> ReaderView {
        ReaderView()
    }

    func updateUIView(_ view: ReaderView, context: Context) {
        view.onChange = { value in
            if isFullScreen != value {
                isFullScreen = value
            }
        }
        view.report()
    }

    final class ReaderView: UIView {
        var onChange: ((Bool) -> Void)?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) {
            fatalError()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            report()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            report()
        }

        func report() {
            guard let window, let screen = window.windowScene?.screen else { return }
            let windowSize = window.bounds.size
            let screenSize = screen.bounds.size
            func fills(_ width: CGFloat, _ height: CGFloat) -> Bool {
                windowSize.width >= width - 1 && windowSize.height >= height - 1
            }
            let value =
                traitCollection.userInterfaceIdiom == .phone || fills(screenSize.width, screenSize.height)
                || fills(screenSize.height, screenSize.width)
            DispatchQueue.main.async { [weak self] in
                self?.onChange?(value)
            }
        }
    }
}
#else
private struct SessionWindowToolbar: ToolbarContent {
    let controller: SessionController
    let commands: [SessionCommand]

    var body: some ToolbarContent {
        ForEach(commands) { command in
            ToolbarItem {
                SessionCommandView(command: command, controller: controller, style: .windowToolbarItem)
                    .help(String(localized: command.title))
            }
        }
        ToolbarItem {
            Button {
                controller.isCustomizingToolbar = true
            } label: {
                Label("Customize Toolbar…", systemImage: "slider.horizontal.3")
            }
            .help("Customize Toolbar")
        }
    }
}
#endif
