import Perception
import SwiftUI

@main
enum OpenScreenSharingMain {
    static func main() {
        #if os(iOS)
        if #available(iOS 17, *) {
            MultiWindowApp.main()
        } else {
            SingleWindowApp.main()
        }
        #else
        // AppKit opens no default window whenever saved state exists, even if SwiftUI restores no window from it,
        // unless the app is already active (AppKit 2775.10.103.1, macOS 27.0.1 26A434,
        // UUID 2688AFBC-5C39-3132-BCF5-8D00D32D7615,
        // -[NSApplication _reopenWindowsAsNecessaryIncludingRestorableState:withFullFidelity:completionHandler:]
        // 0x1852a5f00..0x1852a5ff4,
        // -[NSPersistentUIRestorer _finishedRestoringWindowsWith_ZOrder:options:completionHandler:] 0x185ca0144..0x185ca01d0,
        // ___58-[NSApplication(NSAppleEventHandling) _handleAEOpenEvent:]_block_invoke 0x185024488..0x1850244c0).
        UserDefaults.standard.register(defaults: ["ApplePersistenceIgnoreState": true])
        MacApp.main()
        #endif
    }
}

final class AppLaunch {
    let nearby: NearbyBrowser
    let availabilityMonitor: AvailabilityMonitor

    init() {
        Task.detached(priority: .userInitiated) { _ = DecoderCapabilities.current }
        _ = AppDatabase.shared
        nearby = Demo.current?.nearby ?? NearbyBrowser()
        availabilityMonitor = AvailabilityMonitor(nearby: nearby)
        #if os(macOS)
        UpdateManager.shared.checkOnLaunch()
        _ = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            guard !RemoteScreenNSView.isSessionWindow(event.window) else { return event }
            return KeyShortcuts.shared.performAssigned(event) ? nil : event
        }
        #endif
    }
}

#if os(iOS)
struct HomeScene: Scene {
    let launch: AppLaunch

    var body: some Scene {
        WindowGroup {
            HomeView()
                .providesSessionWindows()
                .environment(launch.nearby)
                .environment(launch.availabilityMonitor)
        }
        .commands {
            AppCommands()
        }
    }
}

struct SingleWindowApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    private let launch = AppLaunch()

    var body: some Scene {
        HomeScene(launch: launch)
    }
}

@available(iOS 17, *)
struct MultiWindowApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    private let launch = AppLaunch()

    var body: some Scene {
        HomeScene(launch: launch)

        WindowGroup(for: SessionRoute.self) { $route in
            SessionWindow(route: route)
        }
    }
}
#else
struct MacApp: App {
    private let launch = AppLaunch()

    var body: some Scene {
        Window(String(localized: "Connections"), id: "home") {
            HomeView()
                .providesSessionWindows()
                .environment(launch.nearby)
                .environment(launch.availabilityMonitor)
        }
        .defaultSize(width: 900, height: 560)
        .commands {
            AppCommands()
            SidebarCommands()
        }

        WindowGroup(for: SessionRoute.self) { $route in
            SessionWindow(route: route)
        }
        .defaultSize(width: 1280, height: 800)
    }
}
#endif

@available(iOS 17, *)
struct SessionWindow: View {
    let route: SessionRoute?
    @Environment(\.dismissWindow) private var dismissWindow
    @State private var controller: SessionController?

    var body: some View {
        if let controller {
            SessionView(controller: controller) {
                DispatchQueue.main.async { dismissWindow() }
            }
        } else if let route {
            Color.clear.onAppear { controller = SessionController(route: WindowCredentials.receive(route)) }
        } else {
            ContentUnavailableView("No Connection Open", systemImage: "desktopcomputer")
        }
    }
}

nonisolated private struct OpenSessionWindowKey: EnvironmentKey {
    static let defaultValue: (@MainActor (SessionRoute) -> Void)? = nil
}

nonisolated extension EnvironmentValues {
    var openSessionWindow: (@MainActor (SessionRoute) -> Void)? {
        get { self[OpenSessionWindowKey.self] }
        set { self[OpenSessionWindowKey.self] = newValue }
    }
}

@available(iOS 17, *)
private struct SessionWindowOpener: ViewModifier {
    @Environment(\.openWindow) private var openWindow
    @Environment(\.supportsMultipleWindows) private var supportsMultipleWindows

    func body(content: Content) -> some View {
        content.environment(\.openSessionWindow, supportsMultipleWindows ? open : nil)
    }

    private func open(_ route: SessionRoute) {
        #if os(macOS)
        if RemoteScreenNSView.focusWindow(showing: route) { return }
        #endif
        openWindow(value: WindowCredentials.send(route))
    }
}

extension View {
    @ViewBuilder
    func providesSessionWindows() -> some View {
        if #available(iOS 17, *) {
            modifier(SessionWindowOpener())
        } else {
            self
        }
    }
}
