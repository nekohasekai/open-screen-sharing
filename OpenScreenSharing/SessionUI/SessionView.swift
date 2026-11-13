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
        controller.isToolbarVisible || controller.isZoomModeOn || !controller.isControlling || controller.isKeyboardVisible
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
    var body: some View {
        WithPerceptionTracking {
            let dock = dock
            let plan = ToolbarLayout.layout(for: controller).plan(for: controller, slots: toolbarSlots)
            screen
                .ignoresSafeArea()
                .background {
                    Color.black.ignoresSafeArea()
                    FullScreenReader(isFullScreen: $controller.isFullScreen)
                        .ignoresSafeArea()
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
