import Foundation
import Perception
#if os(iOS)
import UIKit
#else
import AppKit
#endif

@Perceptible
final class AppSettings {
    static let shared = AppSettings()

    private enum Key {
        static let showsDebugInfo = "showsDebugInfo"
        static let cursorStyle = "cursorStyle"
        static let pinchZooms = "pinchZooms"
        static let pictureFillsScreen = "pictureFillsScreen"
        static let usesTabBar = "usesTabBar"
        static let computerLayout = "computerLayout"
        static let computerOrder = "computerOrder"
        static let seenTutorials = "seenTutorials"
    }

    var showsDebugInfo: Bool {
        didSet { store.set(showsDebugInfo, for: Key.showsDebugInfo) }
    }

    var cursorStyle: CursorStyle {
        didSet { store.set(cursorStyle, for: Key.cursorStyle) }
    }

    var pictureFillsScreen: Bool {
        didSet {
            store.set(pictureFillsScreen, for: Key.pictureFillsScreen)
            #if os(macOS)
            MainActor.assumeIsolated {
                RemoteScreenNSView.updateFillsScreen(pictureFillsScreen)
            }
            #endif
        }
    }

    #if os(iOS)
    var pinchZooms: Bool {
        didSet { store.set(pinchZooms, for: Key.pinchZooms) }
    }

    var usesTabBar: Bool {
        didSet { store.set(usesTabBar, for: Key.usesTabBar) }
    }

    static var offersFullScreen: Bool {
        switch UIDevice.current.userInterfaceIdiom {
        case .phone:
            return true
        case .pad:
            guard #available(iOS 26, *) else { return DisplayShape.describesDevice }
            return true
        default:
            return false
        }
    }
    #else
    private var hasCameraHousing: Bool

    static var offersFullScreen: Bool {
        shared.hasCameraHousing
    }
    #endif

    var computerLayout: ComputerLayout {
        didSet { store.set(computerLayout, for: Key.computerLayout) }
    }

    var computerOrder: [UUID] {
        didSet { store.set(computerOrder, for: Key.computerOrder) }
    }

    var seenTutorials: Set<SessionTutorial> {
        didSet { store.set(seenTutorials, for: Key.seenTutorials) }
    }

    private let store = PreferenceStore.shared

    init() {
        showsDebugInfo = store.value(Bool.self, for: Key.showsDebugInfo) ?? false
        cursorStyle = store.value(CursorStyle.self, for: Key.cursorStyle) ?? .system
        pictureFillsScreen = store.value(Bool.self, for: Key.pictureFillsScreen) ?? true
        #if os(iOS)
        pinchZooms = store.value(Bool.self, for: Key.pinchZooms) ?? (UIDevice.current.userInterfaceIdiom == .phone)
        usesTabBar = store.value(Bool.self, for: Key.usesTabBar) ?? false
        #else
        hasCameraHousing = FullScreenCameraHousing.isAvailable
        #endif
        computerLayout = store.value(ComputerLayout.self, for: Key.computerLayout) ?? .grid
        computerOrder = store.value([UUID].self, for: Key.computerOrder) ?? []
        seenTutorials = store.value(Set<SessionTutorial>.self, for: Key.seenTutorials) ?? []
        #if os(macOS)
        NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                let available = FullScreenCameraHousing.isAvailable
                if self?.hasCameraHousing != available {
                    self?.hasCameraHousing = available
                }
            }
        }
        #endif
    }
}

enum CursorStyle: Codable, Hashable {
    case system
    case remote
    case theme(String)
}

enum ComputerLayout: String, Codable {
    case grid
    case list
}
