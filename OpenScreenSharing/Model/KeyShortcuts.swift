import Perception
import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

enum ShortcutGroup: CaseIterable, Identifiable {
    case file, edit, view, sound, control, debug

    var id: Self { self }

    var title: String {
        switch self {
        case .file: String(localized: "File")
        case .edit: String(localized: "Edit Menu", defaultValue: "Edit")
        case .view: String(localized: "View")
        case .sound: String(localized: "Sound")
        case .control: String(localized: "Control")
        case .debug: String(localized: "Debug")
        }
    }

    var actions: [ShortcutAction] {
        ShortcutAction.allCases.filter { $0.group == self }
    }
}

enum ShortcutAction: String, CaseIterable, Identifiable {
    case newConnection, disconnect
    case syncClipboard, receiveClipboard, sendClipboard
    #if os(macOS)
    case viewAsIcons, viewAsList
    #endif
    case zoomMode, actualSize
    case display1, display2, display3, display4, display5, display6, display7, display8, allDisplays
    case adaptiveQuality, fullQuality, hdr
    #if os(iOS)
    case toolbar
    #endif
    case customizeToolbar
    case receiveAudio, sendAudio
    case observe, pointerLock
    #if os(iOS)
    case keyboard
    #endif
    case home, appSwitcher, lock
    case typeUserPassword
    case missionControl, appWindows, showDesktop, launchpad
    case debugPanel

    var id: Self { self }

    static let displays: [ShortcutAction] = [.display1, .display2, .display3, .display4, .display5, .display6, .display7, .display8]

    static func display(at index: Int) -> ShortcutAction? {
        displays.indices.contains(index) ? displays[index] : nil
    }

    static func quality(_ quality: SessionQuality) -> ShortcutAction {
        switch quality {
        case .adaptive: .adaptiveQuality
        case .full: .fullQuality
        }
    }

    static func system(_ command: SystemCommand) -> ShortcutAction {
        switch command {
        case .missionControl: .missionControl
        case .appWindows: .appWindows
        case .showDesktop: .showDesktop
        case .launchpad: .launchpad
        }
    }

    var group: ShortcutGroup {
        switch self {
        case .newConnection, .disconnect: .file
        case .syncClipboard, .receiveClipboard, .sendClipboard: .edit
        case .receiveAudio, .sendAudio: .sound
        case .observe, .pointerLock, .home, .appSwitcher, .lock, .typeUserPassword, .missionControl, .appWindows, .showDesktop,
            .launchpad:
            .control
        #if os(iOS)
        case .keyboard: .control
        #endif
        case .debugPanel: .debug
        default: .view
        }
    }

    var title: String {
        switch self {
        case .newConnection: return String(localized: "New Connection")
        case .disconnect: return String(localized: "Disconnect")
        case .syncClipboard: return String(localized: "Sync Clipboard")
        case .receiveClipboard: return String(localized: "Receive Clipboard")
        case .sendClipboard: return String(localized: "Send Clipboard")
        #if os(macOS)
        case .viewAsIcons: return String(localized: "View as Icons")
        case .viewAsList: return String(localized: "View as List")
        #endif
        case .zoomMode: return String(localized: "Zoom Mode")
        case .actualSize: return String(localized: "Zoom to Fit / Actual Size")
        case .display1, .display2, .display3, .display4, .display5, .display6, .display7, .display8:
            return String(localized: "Display \((Self.displays.firstIndex(of: self) ?? 0) + 1)")
        case .allDisplays: return String(localized: "All Displays")
        case .adaptiveQuality: return String(localized: SessionQuality.adaptive.title)
        case .fullQuality: return String(localized: SessionQuality.full.title)
        case .hdr: return String(localized: "High Dynamic Range")
        #if os(iOS)
        case .toolbar: return String(localized: "Show/Hide Toolbar")
        #endif
        case .customizeToolbar: return String(localized: "Customize Toolbar")
        #if os(iOS)
        case .keyboard: return String(localized: "Show/Hide Keyboard")
        #endif
        case .receiveAudio: return String(localized: "Receive Audio")
        case .sendAudio: return String(localized: "Send Audio")
        case .observe: return String(localized: "Observe Mode")
        case .pointerLock: return String(localized: "Pointer Lock")
        case .home: return String(localized: SessionCommand.home.title)
        case .appSwitcher: return String(localized: SessionCommand.appSwitcher.title)
        case .lock: return String(localized: SessionCommand.lock.title)
        case .typeUserPassword: return String(localized: SessionCommand.typeUserPassword.title)
        case .missionControl: return String(localized: SystemCommand.missionControl.title)
        case .appWindows: return String(localized: SystemCommand.appWindows.title)
        case .showDesktop: return String(localized: SystemCommand.showDesktop.title)
        case .launchpad: return String(localized: SystemCommand.launchpad.title)
        case .debugPanel: return String(localized: "Debug Panel")
        }
    }
}

struct KeyShortcut: Codable, Hashable {
    var key: String
    var modifiers: Int

    init(key: String, modifiers: EventModifiers) {
        self.key = key
        self.modifiers = modifiers.rawValue
    }

    struct NamedKey: Identifiable {
        let name: String
        let symbol: String
        let title: String
        let pcLabel: String
        let output: MacKey?
        let targets: Set<MappingTarget>
        let equivalent: KeyEquivalent?
        let standalone: Bool
        let command: SystemCommand?

        var id: String { name }

        func label(for target: MappingTarget) -> String {
            target == .pc ? pcLabel : title
        }
    }

    struct KeySection: Identifiable {
        let title: String?
        let keys: [NamedKey]

        var id: String { title ?? "" }
    }

    enum KeyGroup: CaseIterable, Identifiable {
        case editing, navigation, functionKeys, keypad, system, control

        var id: Self { self }

        var title: String {
            switch self {
            case .editing: String(localized: "Editing")
            case .navigation: String(localized: "Navigation")
            case .functionKeys: String(localized: "Function Keys")
            case .keypad: String(localized: "Keypad")
            case .system: String(localized: "System")
            case .control: String(localized: "Control")
            }
        }

        private var sectionNames: [(title: String?, names: [String])] {
            switch self {
            case .editing: [(nil, ["return", "tab", "space", "delete", "deleteForward", "escape", "insert"])]
            case .navigation: [(nil, ["home", "end", "pageUp", "pageDown", "upArrow", "downArrow", "leftArrow", "rightArrow"])]
            case .functionKeys: [(nil, (1...24).map { "f\($0)" })]
            case .keypad:
                [
                    (
                        nil,
                        ["numLock"] + (0...9).map { "keypad\($0)" }
                            + ["keypadDecimal", "keypadPlus", "keypadMinus", "keypadMultiply", "keypadDivide", "keypadEqual", "keypadEnter"]
                    )
                ]
            case .system: [(nil, ["printScreen", "scrollLock", "pause", "menu", "capsLock"])]
            case .control:
                [("Mac", ["missionControl", "appWindows", "showDesktop", "launchpad"]), ("iPhone / iPad", ["homeScreen", "appSwitcher", "lock"])]
            }
        }

        func sections(for target: MappingTarget) -> [KeySection] {
            sectionNames.map { section in
                KeySection(title: section.title, keys: section.names.compactMap { KeyShortcut.namedKeys[$0] }.filter { $0.targets.contains(target) })
            }
            .filter { !$0.keys.isEmpty }
        }

        func keys(for target: MappingTarget) -> [NamedKey] {
            sections(for: target).flatMap(\.keys)
        }
    }

    private static let namedKeys: [String: NamedKey] = {
        var keys: [NamedKey] = []
        func add(
            _ name: String, _ symbol: String, title: String? = nil, pc: String? = nil, _ output: MacKey?,
            for targets: Set<MappingTarget> = [.apple, .pc], equivalent: KeyEquivalent? = nil, standalone: Bool = false,
            command: SystemCommand? = nil
        ) {
            keys.append(
                NamedKey(
                    name: name, symbol: symbol, title: title ?? symbol, pcLabel: pc ?? title ?? symbol, output: output, targets: targets,
                    equivalent: equivalent, standalone: standalone, command: command))
        }
        add("return", "↩", title: String(localized: "Return"), pc: "Enter", .returnKey, equivalent: .return)
        add("tab", "⇥", title: String(localized: "Tab"), pc: "Tab", .tab, equivalent: .tab)
        add("space", String(localized: "Space"), pc: "Space", .space, equivalent: .space)
        add("delete", "⌫", title: String(localized: "Delete"), pc: "Backspace", .backspace, equivalent: .delete)
        add("deleteForward", "⌦", title: String(localized: "Forward Delete"), pc: "Delete", .forwardDelete, equivalent: .deleteForward)
        add("escape", "⎋", title: String(localized: "Escape"), pc: "Esc", .escape, equivalent: .escape)
        add("insert", "Insert", .insert, for: [.pc], standalone: true)
        add("help", "Help", nil, for: [], standalone: true)
        add("home", "↖", title: String(localized: "Home"), pc: "Home", .home, equivalent: .home)
        add("end", "↘", title: String(localized: "End"), pc: "End", .end, equivalent: .end)
        add("pageUp", "⇞", title: String(localized: "Page Up"), pc: "Page Up", .pageUp, equivalent: .pageUp)
        add("pageDown", "⇟", title: String(localized: "Page Down"), pc: "Page Down", .pageDown, equivalent: .pageDown)
        add("upArrow", "↑", title: String(localized: "Up Arrow"), pc: "↑", .up, equivalent: .upArrow)
        add("downArrow", "↓", title: String(localized: "Down Arrow"), pc: "↓", .down, equivalent: .downArrow)
        add("leftArrow", "←", title: String(localized: "Left Arrow"), pc: "←", .left, equivalent: .leftArrow)
        add("rightArrow", "→", title: String(localized: "Right Arrow"), pc: "→", .right, equivalent: .rightArrow)
        for number in 1...24 {
            #if os(iOS)
            let equivalent = number <= 12
            #else
            let equivalent = number <= 20
            #endif
            add(
                "f\(number)", "F\(number)", .function(number), for: number <= 20 ? [.apple, .pc] : [.pc],
                equivalent: equivalent ? KeyEquivalent(Character(Unicode.Scalar(0xF703 + UInt32(number))!)) : nil, standalone: true)
        }
        add("numLock", "⌧", title: String(localized: "Clear"), pc: "Num Lock", MacKey(keyCode: 71, keysym: Keysym.numLock))
        for (digit, keyCode) in [82, 83, 84, 85, 86, 87, 88, 89, 91, 92].enumerated() {
            let title = String(localized: "Keypad \(String(digit))")
            add("keypad\(digit)", title, pc: "Numpad \(digit)", MacKey(keyCode: UInt16(keyCode), keysym: Keysym.keypad(digit)))
        }
        let operators: [(name: String, legend: String, keyCode: UInt16, keysym: UInt32)] = [
            ("keypadDecimal", ".", 65, Keysym.keypadDecimal), ("keypadPlus", "+", 69, Keysym.keypadPlus),
            ("keypadMinus", "-", 78, Keysym.keypadMinus), ("keypadMultiply", "*", 67, Keysym.keypadMultiply),
            ("keypadDivide", "/", 75, Keysym.keypadDivide), ("keypadEqual", "=", 81, Keysym.keypadEqual),
        ]
        for key in operators {
            add(
                key.name, String(localized: "Keypad \(key.legend)"), pc: "Numpad \(key.legend)", MacKey(keyCode: key.keyCode, keysym: key.keysym),
                for: key.name == "keypadEqual" ? [.apple] : [.apple, .pc])
        }
        add("keypadEnter", "⌤", title: String(localized: "Keypad Enter"), pc: "Numpad Enter", MacKey(keyCode: 76, keysym: Keysym.keypadEnter))
        add("printScreen", "Print Screen", .printScreen, for: [.pc], standalone: true)
        add("scrollLock", "Scroll Lock", MacKey(keyCode: nil, keysym: Keysym.scrollLock), for: [.pc], standalone: true)
        add("pause", "Pause", MacKey(keyCode: nil, keysym: Keysym.pause), for: [.pc], standalone: true)
        add("menu", "Menu", .menu, for: [.pc], standalone: true)
        add("capsLock", "Caps Lock", MacKey(keyCode: nil, keysym: Keysym.capsLock), for: [.pc])
        add("missionControl", String(localized: SystemCommand.missionControl.title), nil, for: [.apple], command: .missionControl)
        add("appWindows", String(localized: SystemCommand.appWindows.title), nil, for: [.apple], command: .appWindows)
        add("showDesktop", String(localized: SystemCommand.showDesktop.title), nil, for: [.apple], command: .showDesktop)
        add("launchpad", String(localized: SystemCommand.launchpad.title), nil, for: [.apple], command: .launchpad)
        add("homeScreen", String(localized: SessionCommand.home.title), nil, for: [.apple], command: .showDesktop)
        add("appSwitcher", String(localized: SessionCommand.appSwitcher.title), nil, for: [.apple], command: .missionControl)
        add("lock", String(localized: SessionCommand.lock.title), KeyChord.sideButton.key, for: [.apple])
        return Dictionary(uniqueKeysWithValues: keys.map { ($0.name, $0) })
    }()

    #if os(iOS)
    private static let usageNames: [UIKeyboardHIDUsage: String] = {
        var names: [UIKeyboardHIDUsage: String] = [
            .keyboardReturnOrEnter: "return", .keyboardReturn: "return", .keyboardTab: "tab", .keyboardSpacebar: "space",
            .keyboardDeleteOrBackspace: "delete", .keyboardDeleteForward: "deleteForward", .keyboardEscape: "escape",
            .keyboardInsert: "insert", .keyboardHelp: "help", .keyboardHome: "home", .keyboardEnd: "end", .keyboardPageUp: "pageUp",
            .keyboardPageDown: "pageDown", .keyboardUpArrow: "upArrow", .keyboardDownArrow: "downArrow", .keyboardLeftArrow: "leftArrow",
            .keyboardRightArrow: "rightArrow", .keypadNumLock: "numLock", .keypadEnter: "keypadEnter", .keyboardPrintScreen: "printScreen",
            .keyboardScrollLock: "scrollLock", .keyboardPause: "pause", .keyboardApplication: "menu", .keyboardMenu: "menu",
        ]
        for number in 1...12 {
            names[UIKeyboardHIDUsage(rawValue: UIKeyboardHIDUsage.keyboardF1.rawValue + number - 1)!] = "f\(number)"
        }
        for number in 13...24 {
            names[UIKeyboardHIDUsage(rawValue: UIKeyboardHIDUsage.keyboardF13.rawValue + number - 13)!] = "f\(number)"
        }
        return names
    }()
    #else
    private static let keyCodeNames: [UInt16: String] = {
        var names: [UInt16: String] = [
            36: "return", 48: "tab", 49: "space", 51: "delete", 117: "deleteForward", 53: "escape", 114: "help", 115: "home", 119: "end",
            116: "pageUp", 121: "pageDown", 126: "upArrow", 125: "downArrow", 123: "leftArrow", 124: "rightArrow", 71: "numLock",
            76: "keypadEnter", 110: "menu",
        ]
        for number in 1...20 {
            if let keyCode = MacKey.function(number).keyCode {
                names[keyCode] = "f\(number)"
            }
        }
        return names
    }()
    #endif

    static let unset = KeyShortcut(key: "", modifiers: [])

    var isUnset: Bool { key.isEmpty }

    var isNamed: Bool { Self.namedKeys[key] != nil }

    var command: SystemCommand? { Self.namedKeys[key]?.command }

    var isValidTrigger: Bool { hasCommandModifier || Self.namedKeys[key]?.standalone == true }

    var eventModifiers: EventModifiers { EventModifiers(rawValue: modifiers) }

    func chord(for target: MappingTarget) -> KeyChord? {
        let output: MacKey?
        if let named = Self.namedKeys[key] {
            output = named.targets.contains(target) ? named.output : nil
        } else if key.count == 1, let scalar = key.unicodeScalars.first {
            output = MacKey(keysym: Keysym.character(scalar))
        } else {
            output = nil
        }
        return output.map { KeyChord(modifiers: chordModifiers, key: $0) }
    }

    private var chordModifiers: [ModifierKey] {
        ModifierKey.allCases.filter { modifier in
            switch modifier {
            case .control: eventModifiers.contains(.control)
            case .option: eventModifiers.contains(.option)
            case .shift: eventModifiers.contains(.shift)
            case .command: eventModifiers.contains(.command)
            }
        }
    }

    var keyboardShortcut: KeyboardShortcut? {
        if let named = Self.namedKeys[key] {
            return named.equivalent.map { KeyboardShortcut($0, modifiers: eventModifiers) }
        }
        guard key.count == 1, let character = key.first else { return nil }
        return KeyboardShortcut(KeyEquivalent(character), modifiers: eventModifiers)
    }

    var hasCommandModifier: Bool {
        !eventModifiers.isDisjoint(with: [.command, .option, .control])
    }

    var displayName: String {
        eventModifiers.symbols + (Self.namedKeys[key]?.symbol ?? key.uppercased())
    }

    var pcDisplayName: String {
        var names: [String] = []
        if eventModifiers.contains(.control) { names.append("Ctrl") }
        if eventModifiers.contains(.option) { names.append("Alt") }
        if eventModifiers.contains(.shift) { names.append("Shift") }
        if eventModifiers.contains(.command) { names.append("Win") }
        names.append(Self.namedKeys[key]?.pcLabel ?? key.uppercased())
        return names.joined(separator: "+")
    }

    func displayName(for target: MappingTarget) -> String {
        target == .pc ? pcDisplayName : displayName
    }

    private init?(character: String?, modifiers: EventModifiers) {
        guard let character = character?.lowercased(), character.count == 1, let scalar = character.unicodeScalars.first,
            !CharacterSet.controlCharacters.contains(scalar), !(0xF700...0xF8FF).contains(scalar.value)
        else { return nil }
        self.init(key: character, modifiers: modifiers)
    }

    #if os(iOS)
    init?(key press: UIKey) {
        var modifiers: EventModifiers = []
        if press.modifierFlags.contains(.control) { modifiers.insert(.control) }
        if press.modifierFlags.contains(.alternate) { modifiers.insert(.option) }
        if press.modifierFlags.contains(.shift) { modifiers.insert(.shift) }
        if press.modifierFlags.contains(.command) { modifiers.insert(.command) }
        if let name = Self.usageNames[press.keyCode] {
            self.init(key: name, modifiers: modifiers)
        } else {
            self.init(character: press.charactersIgnoringModifiers, modifiers: modifiers)
        }
    }
    #else
    init?(event: NSEvent) {
        let modifiers = EventModifiers(event.modifierFlags)
        if let name = Self.keyCodeNames[event.keyCode] {
            self.init(key: name, modifiers: modifiers)
        } else {
            self.init(character: event.characters(byApplyingModifiers: []), modifiers: modifiers)
        }
    }
    #endif
}

extension EventModifiers {
    var symbols: String {
        var symbols = ""
        if contains(.control) { symbols += "⌃" }
        if contains(.option) { symbols += "⌥" }
        if contains(.shift) { symbols += "⇧" }
        if contains(.command) { symbols += "⌘" }
        return symbols
    }

    #if os(macOS)
    init(_ flags: NSEvent.ModifierFlags) {
        self = []
        if flags.contains(.control) { insert(.control) }
        if flags.contains(.option) { insert(.option) }
        if flags.contains(.shift) { insert(.shift) }
        if flags.contains(.command) { insert(.command) }
    }
    #endif
}

nonisolated enum MappingTarget: CaseIterable, Identifiable {
    case apple
    case pc

    var id: Self { self }

    var title: String {
        switch self {
        case .apple: String(localized: "Apple Key Mappings")
        case .pc: String(localized: "PC Key Mappings")
        }
    }
}

struct KeyMapping: Codable, Hashable, Identifiable {
    enum Step: Codable, Hashable {
        case key(KeyShortcut)
        case text(String)

        func displayName(for target: MappingTarget) -> String {
            switch self {
            case .key(let shortcut):
                shortcut.displayName(for: target)
            case .text(let text):
                "“"
                    + text.map { character in
                        switch character {
                        case "\n", "\r", "\r\n": "↩"
                        case "\t": "⇥"
                        default: String(character)
                        }
                    }.joined() + "”"
            }
        }
    }

    var id: UUID
    var trigger: KeyShortcut
    var steps: [Step]

    func displayName(for target: MappingTarget) -> String {
        steps.map { $0.displayName(for: target) }.joined(separator: " ")
    }
}

@Perceptible
final class KeyShortcuts {
    static let shared = KeyShortcuts()

    private static let key = "keyboardShortcuts"

    private static func mappingsKey(_ target: MappingTarget) -> String {
        switch target {
        case .apple: "keyboardMappings"
        case .pc: "pcKeyboardMappings"
        }
    }

    private var assigned: [String: KeyShortcut]
    private var mappings: [MappingTarget: [KeyMapping]]
    var isRecording = false

    #if os(macOS)
    private static let pointerReleaseKey = "pointerReleaseModifiers"
    static let defaultPointerRelease: NSEvent.ModifierFlags = [.control, .option]

    private(set) var pointerRelease: NSEvent.ModifierFlags
    #endif

    private let store = PreferenceStore.shared

    init() {
        assigned = store.value([String: KeyShortcut].self, for: Self.key) ?? [:]
        mappings = Dictionary(uniqueKeysWithValues: MappingTarget.allCases.map { [store] target in
            (target, store.value([KeyMapping].self, for: Self.mappingsKey(target)) ?? [])
        })
        #if os(macOS)
        pointerRelease = store.value(UInt.self, for: Self.pointerReleaseKey).map(NSEvent.ModifierFlags.init(rawValue:)) ?? Self.defaultPointerRelease
        #endif
    }

    var isCustomized: Bool {
        #if os(macOS)
        if pointerRelease != Self.defaultPointerRelease { return true }
        #endif
        return !assigned.isEmpty || mappings.values.contains { !$0.isEmpty }
    }

    func shortcut(for action: ShortcutAction) -> KeyShortcut? {
        assigned[action.rawValue]
    }

    func action(using shortcut: KeyShortcut) -> ShortcutAction? {
        let assigned = assigned
        return ShortcutAction.allCases.first { assigned[$0.rawValue] == shortcut }
    }

    func set(_ shortcut: KeyShortcut?, for action: ShortcutAction) {
        assigned[action.rawValue] = shortcut
        store.set(assigned, for: Self.key)
    }

    func mappings(for target: MappingTarget) -> [KeyMapping] {
        mappings[target] ?? []
    }

    func mapping(triggeredBy shortcut: KeyShortcut, for target: MappingTarget) -> KeyMapping? {
        mappings(for: target).first { $0.trigger == shortcut }
    }

    func add(_ mapping: KeyMapping, to target: MappingTarget) {
        var list = mappings(for: target)
        list.append(mapping)
        setMappings(list, for: target)
    }

    func update(_ mapping: KeyMapping, in target: MappingTarget) {
        var list = mappings(for: target)
        guard let index = list.firstIndex(where: { $0.id == mapping.id }) else { return }
        list[index] = mapping
        setMappings(list, for: target)
    }

    func removeMapping(_ id: UUID, from target: MappingTarget) {
        var list = mappings(for: target)
        list.removeAll { $0.id == id }
        setMappings(list, for: target)
    }

    private func setMappings(_ list: [KeyMapping], for target: MappingTarget) {
        mappings[target] = list
        store.set(list, for: Self.mappingsKey(target))
    }

    func restoreDefaults() {
        assigned = [:]
        store.remove(Self.key)
        for target in MappingTarget.allCases {
            mappings[target] = []
            store.remove(Self.mappingsKey(target))
        }
        #if os(macOS)
        pointerRelease = Self.defaultPointerRelease
        store.remove(Self.pointerReleaseKey)
        #endif
    }

    #if os(macOS)
    func setPointerRelease(_ modifiers: NSEvent.ModifierFlags) {
        pointerRelease = modifiers
        store.set(modifiers.rawValue, for: Self.pointerReleaseKey)
    }

    func isAssigned(_ event: NSEvent) -> Bool {
        KeyShortcut(event: event).map { action(using: $0) != nil } ?? false
    }

    // Key equivalents matched against the main menu go to the Apple menu (Log Out ⇧⌘Q, Lock Screen ⌃⌘Q) before the
    // app's own items; a submenu matches only its own items (AppKit 2775.10.103.1, macOS 27.0.1 26A434,
    // UUID 2688AFBC-5C39-3132-BCF5-8D00D32D7615,
    // +[NSCocoaMenuImpl(KeyEvents) _menuItemWithKeyEquivalentMatchingEvent:inMenu:includingDisabledItems:]
    // 0x18578a8e0..0x18578aa48).
    func performAssigned(_ event: NSEvent) -> Bool {
        guard !isRecording, isAssigned(event) else { return false }
        if NSApp.mainMenu?.items.contains(where: { $0.submenu?.performKeyEquivalent(with: event) == true }) != true {
            NSSound.beep()
        }
        return true
    }
    #else
    // The SwiftUI App lifecycle on iPadOS consumes these chords before the first responder's pressesBegan,
    // unless the responder chain declares a key command for them; one whose action nobody performs lets the press through.
    private static let swiftUIConsumed: [(input: String, flags: UIKeyModifierFlags)] = [
        ("b", .command), ("i", .command), ("u", .command), ("e", .command), ("f", .command), ("f", [.command, .alternate]),
        ("g", .command), ("g", [.command, .shift]), ("-", .command), ("=", [.command, .shift]), ("[", [.command, .shift]),
        ("]", [.command, .shift]), ("\\", [.command, .shift]), ("s", [.command, .control]),
    ]

    func passthroughKeyCommands(includingAssigned: Bool) -> [UIKeyCommand] {
        Self.swiftUIConsumed.compactMap { input, flags in
            var modifiers: EventModifiers = []
            if flags.contains(.control) { modifiers.insert(.control) }
            if flags.contains(.alternate) { modifiers.insert(.option) }
            if flags.contains(.shift) { modifiers.insert(.shift) }
            if flags.contains(.command) { modifiers.insert(.command) }
            guard includingAssigned || action(using: KeyShortcut(key: input, modifiers: modifiers)) == nil else { return nil }
            let command = UIKeyCommand(input: input, modifierFlags: flags, action: NSSelectorFromString("passKeyThrough:"))
            command.wantsPriorityOverSystemBehavior = true
            return command
        }
    }
    #endif
}

struct ActionShortcut: ViewModifier {
    private let shortcuts = KeyShortcuts.shared
    let action: ShortcutAction?

    func body(content: Content) -> some View {
        WithPerceptionTracking {
            if let action, !shortcuts.isRecording, let shortcut = shortcuts.shortcut(for: action)?.keyboardShortcut {
                content.keyboardShortcut(shortcut)
            } else {
                content
            }
        }
    }
}
