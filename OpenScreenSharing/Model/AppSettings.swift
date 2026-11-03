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
        static let showsKeyboardForTextFields = "showsKeyboardForTextFields"
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

    var showsKeyboardForTextFields: Bool {
        didSet { store.set(showsKeyboardForTextFields, for: Key.showsKeyboardForTextFields) }
    }

    var pictureFillsScreen: Bool {
        didSet { store.set(pictureFillsScreen, for: Key.pictureFillsScreen) }
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
        showsKeyboardForTextFields = store.value(Bool.self, for: Key.showsKeyboardForTextFields) ?? true
        pictureFillsScreen = store.value(Bool.self, for: Key.pictureFillsScreen) ?? true
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
