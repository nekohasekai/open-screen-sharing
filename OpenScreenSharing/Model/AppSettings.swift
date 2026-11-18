import Foundation
import Perception
#if os(iOS)
import UIKit
#endif

@Perceptible
final class AppSettings {
    static let shared = AppSettings()

    private enum Key {
        static let showsDebugInfo = "showsDebugInfo"
        static let cursorStyle = "cursorStyle"
        static let pinchZooms = "pinchZooms"
        static let usesMacKeyboard = "usesMacKeyboard"
        static let macKeyboardLayout = "macKeyboardLayout"
        static let inputPanel = "inputPanel"
        static let pictureFillsScreen = "pictureFillsScreen"
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

    #if os(iOS)
    var pinchZooms: Bool {
        didSet { store.set(pinchZooms, for: Key.pinchZooms) }
    }

    var pictureFillsScreen: Bool {
        didSet { store.set(pictureFillsScreen, for: Key.pictureFillsScreen) }
    }

    var usesMacKeyboard: Bool {
        didSet { store.set(usesMacKeyboard, for: Key.usesMacKeyboard) }
    }

    var macKeyboardLayout: MacKeyboardLayoutID {
        didSet { store.set(macKeyboardLayout, for: Key.macKeyboardLayout) }
    }

    var inputPanel: InputPanelPlacement {
        didSet { store.set(inputPanel, for: Key.inputPanel) }
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
        #if os(iOS)
        pinchZooms = store.value(Bool.self, for: Key.pinchZooms) ?? (UIDevice.current.userInterfaceIdiom == .phone)
        pictureFillsScreen = store.value(Bool.self, for: Key.pictureFillsScreen) ?? true
        usesMacKeyboard = store.value(Bool.self, for: Key.usesMacKeyboard) ?? false
        macKeyboardLayout = store.value(MacKeyboardLayoutID.self, for: Key.macKeyboardLayout) ?? .preferred
        inputPanel = store.value(InputPanelPlacement.self, for: Key.inputPanel) ?? InputPanelPlacement()
        #endif
        computerLayout = store.value(ComputerLayout.self, for: Key.computerLayout) ?? .grid
        computerOrder = store.value([UUID].self, for: Key.computerOrder) ?? []
        seenTutorials = store.value(Set<SessionTutorial>.self, for: Key.seenTutorials) ?? []
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
