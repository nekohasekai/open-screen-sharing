import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#else
import AppKit
#endif

final class ClipboardAccess {
    enum Setting: String, Codable {
        case allow
        case ask
    }

    static let shared = ClipboardAccess()

    #if os(iOS)
    private(set) var setting = PreferenceStore.shared.value(Setting.self, for: Key.setting)

    private var reading = false
    private var systemAsked = false
    #endif
    private let store = PreferenceStore.shared

    private enum Key {
        static let setting = "pasteFromOtherApps"
        static let ownChangeCount = "ownClipboardChangeCount"
        static let explained = "explainedPasteFromOtherApps"
    }

    private var explained: Bool { store.value(Bool.self, for: Key.explained) == true }

    static var changeCount: Int {
        #if os(iOS)
        UIPasteboard.general.changeCount
        #else
        NSPasteboard.general.changeCount
        #endif
    }

    func noteOwnWrite() {
        store.set(Self.changeCount, for: Key.ownChangeCount)
    }

    func write(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
        noteOwnWrite()
    }

    func write(_ content: ClipboardContent) {
        #if os(iOS)
        UIPasteboard.general.setItems(
            content.map { item in
                var converted: [String: Any] = [:]
                for (type, data) in item {
                    if type == UTType.utf8PlainText.identifier, let text = String(data: data, encoding: .utf8) {
                        converted[type] = text
                    } else {
                        converted[type] = data
                    }
                }
                return converted
            })
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.writeObjects(
            content.map { item in
                let pasteboardItem = NSPasteboardItem()
                for (type, data) in item {
                    pasteboardItem.setData(data, forType: NSPasteboard.PasteboardType(type))
                }
                return pasteboardItem
            })
        #endif
        noteOwnWrite()
    }

    #if os(iOS)
    var allowsReading: Bool { setting == .allow }

    func sceneDidBecomeInactive() {
        if reading {
            systemAsked = true
        }
    }

    func read() async -> (content: ClipboardContent, explanation: AlertState?) {
        let (content, found) = await readAndRecord()
        guard found == .ask, !explained else { return (content, nil) }
        store.set(true, for: Key.explained)
        return (content, explanation)
    }

    func check() async -> AlertState? {
        let pasteboard = UIPasteboard.general
        guard pasteboard.numberOfItems > 0, pasteboard.changeCount != store.value(Int.self, for: Key.ownChangeCount),
            setting != .ask || !explained
        else { return nil }
        return await read().explanation
    }

    private func readAndRecord() async -> (content: ClipboardContent, found: Setting?) {
        let own = UIPasteboard.general.changeCount == store.value(Int.self, for: Key.ownChangeCount)
        reading = true
        systemAsked = false
        let (content, readable) = await Task.detached { Self.readClipboard() }.value
        reading = false
        var found: Setting?
        if systemAsked {
            found = .ask
        } else if readable, !own {
            found = .allow
        }
        if let found {
            record(found)
        }
        return (content, found)
    }

    private func record(_ setting: Setting) {
        guard setting != self.setting else { return }
        self.setting = setting
        store.set(setting, for: Key.setting)
    }
    #else
    private var setting: Setting {
        guard #available(macOS 15.4, *) else { return .allow }
        switch NSPasteboard.general.accessBehavior {
        case .alwaysAllow:
            return .allow
        case .default:
            if #available(macOS 26, *) {
                return .ask
            }
            return .allow
        default:
            return .ask
        }
    }

    var allowsReading: Bool { setting == .allow }

    func read() async -> (content: ClipboardContent, explanation: AlertState?) {
        let content = await Task.detached { Self.readClipboard().content }.value
        return (content, await check())
    }

    func check() async -> AlertState? {
        guard setting == .ask, !explained else { return nil }
        store.set(true, for: Key.explained)
        return explanation
    }
    #endif

    private var explanation: AlertState {
        #if os(iOS)
        AlertState(
            title: String(localized: "Clipboard Sync Off by Default"),
            message: String(
                localized: "The system asks for confirmation every time this app reads it. To turn it on, set Paste from Other Apps to Allow in Settings."),
            actions: [
                AlertState.Action(label: String(localized: "Open Settings"), role: nil) {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                },
                AlertState.Action(label: String(localized: "OK"), role: .cancel) {},
            ]
        )
        #else
        AlertState(
            title: String(localized: "Clipboard Sync Off by Default"),
            message: String(
                localized:
                    "The system asks for confirmation every time this app reads it. To turn it on, turn on Screen Sharing in Privacy & Security > Paste from Other Apps in System Settings."
            ),
            actions: [
                AlertState.Action(label: String(localized: "Open System Settings"), role: nil) {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Pasteboard") {
                        NSWorkspace.shared.open(url)
                    }
                },
                AlertState.Action(label: String(localized: "OK"), role: .cancel) {},
            ]
        )
        #endif
    }

    nonisolated static func readClipboard() -> (content: ClipboardContent, readable: Bool) {
        #if os(iOS)
        let items = UIPasteboard.general.items
        let content: ClipboardContent = items.compactMap { item in
            var flavors: [String: Data] = [:]
            for (type, value) in item {
                switch value {
                case let data as Data: flavors[type] = data
                case let text as String: flavors[type] = Data(text.utf8)
                case let url as URL: flavors[type] = Data(url.absoluteString.utf8)
                case let image as UIImage: flavors[type] = image.pngData()
                default: break
                }
            }
            if flavors[UTType.utf8PlainText.identifier] == nil, let text = item[UTType.plainText.identifier] as? String ?? item[UTType.text.identifier] as? String {
                flavors[UTType.utf8PlainText.identifier] = Data(text.utf8)
            }
            return flavors.isEmpty ? nil : flavors
        }
        return (content, items.contains { !$0.isEmpty })
        #else
        let items = NSPasteboard.general.pasteboardItems ?? []
        let content: ClipboardContent = items.compactMap { item in
            var flavors: [String: Data] = [:]
            for type in item.types where !type.rawValue.hasPrefix("dyn.") {
                flavors[type.rawValue] = item.data(forType: type)
            }
            return flavors.isEmpty ? nil : flavors
        }
        return (content, !content.isEmpty)
        #endif
    }
}
