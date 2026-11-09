import Foundation

enum MacKeyboardLayoutID: String, Codable, CaseIterable, Identifiable {
    case english
    case turkish

    var id: Self { self }

    static var preferred: Self {
        Locale.preferredLanguages.first?.hasPrefix("tr") == true ? .turkish : .english
    }

    var title: String.LocalizationValue {
        switch self {
        case .english: "English (US)"
        case .turkish: "Turkish Q"
        }
    }

    var shortName: String {
        switch self {
        case .english: "EN"
        case .turkish: "TR"
        }
    }

    var next: Self {
        let all = Self.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }

    var locale: Locale {
        switch self {
        case .english: Locale(identifier: "en_US")
        case .turkish: Locale(identifier: "tr_TR")
        }
    }

    func layout(compact: Bool) -> MacKeyboardLayout {
        switch (self, compact) {
        case (.english, false): .english
        case (.english, true): .compactEnglish
        case (.turkish, false): .turkish
        case (.turkish, true): .compactTurkish
        }
    }
}

/// A key on the on-screen Mac keyboard. Widths are in units of one letter key.
struct MacKeyboardKey: Hashable {
    enum Kind: Hashable {
        /// Types `base`, or `shifted` while Shift is on. Letters also follow Caps Lock.
        case character(base: String, shifted: String, isLetter: Bool)
        case key(MacKey, label: Label)
        case modifier(ModifierKey)
        case capsLock
        case fn
        /// Switches the letter rows to the symbols that don't fit on a compact keyboard.
        case symbols
        case arrows
    }

    enum Label: Hashable {
        case text(String)
        case symbol(String)
    }

    let kind: Kind
    var width: CGFloat = 1
    /// The function key this key turns into while Fn is on.
    var fnFunction: Int?
}

/// The keys of a Mac keyboard, without the function row: Fn turns the number row into F1 and on.
///
/// The compact layouts for narrow windows leave out the keys between the letters and the edges, and type their
/// symbols from a second set of letter rows instead, so each key gets wider.
struct MacKeyboardLayout {
    let numberRow: [MacKeyboardKey]
    let topRow: [MacKeyboardKey]
    let homeRow: [MacKeyboardKey]
    let bottomRow: [MacKeyboardKey]
    let spaceRow: [MacKeyboardKey]
    /// The letter rows while the symbols are on; nil when every symbol has a key of its own.
    let symbolRows: [[MacKeyboardKey]]?

    func rows(showsSymbols: Bool) -> [[MacKeyboardKey]] {
        if showsSymbols, let symbolRows {
            return [numberRow] + symbolRows + [spaceRow]
        }
        return [numberRow, topRow, homeRow, bottomRow, spaceRow]
    }

    private static func characters(_ pairs: [(String, String)]) -> [MacKeyboardKey] {
        pairs.map { base, shifted in MacKeyboardKey(kind: .character(base: base, shifted: shifted, isLetter: false)) }
    }

    /// Keys that type the same symbol with or without Shift.
    private static func symbols(_ symbols: String) -> [MacKeyboardKey] {
        symbols.map { MacKeyboardKey(kind: .character(base: String($0), shifted: String($0), isLetter: false)) }
    }

    private static func letters(_ letters: String, locale: Locale) -> [MacKeyboardKey] {
        letters.map { letter in
            let base = String(letter)
            return MacKeyboardKey(kind: .character(base: base, shifted: base.uppercased(with: locale), isLetter: true))
        }
    }

    /// Number keys, numbered for Fn from the first digit.
    private static func numberKeys(_ pairs: [(String, String)]) -> [MacKeyboardKey] {
        var function = 0
        return pairs.map { pair in
            let isDigit = pair.0.count == 1 && pair.0.first!.isNumber
            if isDigit || function > 0 { function += 1 }
            return MacKeyboardKey(
                kind: .character(base: pair.0, shifted: pair.1, isLetter: false),
                fnFunction: (1...12).contains(function) ? function : nil)
        }
    }

    private static let escape = MacKeyboardKey(kind: .key(.escape, label: .text("esc")))
    private static func backspace(_ width: CGFloat) -> MacKeyboardKey {
        MacKeyboardKey(kind: .key(.backspace, label: .symbol("delete.left")), width: width)
    }
    private static func tab(_ width: CGFloat) -> MacKeyboardKey {
        MacKeyboardKey(kind: .key(.tab, label: .symbol("arrow.right.to.line")), width: width)
    }
    private static func returnKey(_ width: CGFloat) -> MacKeyboardKey {
        MacKeyboardKey(kind: .key(.returnKey, label: .symbol("return")), width: width)
    }
    private static func capsLock(_ width: CGFloat) -> MacKeyboardKey { MacKeyboardKey(kind: .capsLock, width: width) }
    private static func shift(_ width: CGFloat) -> MacKeyboardKey { MacKeyboardKey(kind: .modifier(.shift), width: width) }
    private static func space(_ width: CGFloat) -> MacKeyboardKey {
        MacKeyboardKey(kind: .key(.space, label: .text(" ")), width: width)
    }

    private static let fullSpaceRow: [MacKeyboardKey] = [
        MacKeyboardKey(kind: .fn),
        MacKeyboardKey(kind: .modifier(.control)),
        MacKeyboardKey(kind: .modifier(.option)),
        MacKeyboardKey(kind: .modifier(.command), width: 1.25),
        space(5),
        MacKeyboardKey(kind: .modifier(.command), width: 1.25),
        MacKeyboardKey(kind: .modifier(.option)),
        MacKeyboardKey(kind: .arrows, width: 3),
    ]

    private static func compactSpaceRow(space width: CGFloat) -> [MacKeyboardKey] {
        [
            MacKeyboardKey(kind: .fn),
            MacKeyboardKey(kind: .symbols, width: 1.25),
            MacKeyboardKey(kind: .modifier(.control)),
            MacKeyboardKey(kind: .modifier(.option)),
            MacKeyboardKey(kind: .modifier(.command), width: 1.25),
            space(width),
            MacKeyboardKey(kind: .arrows, width: 3),
        ]
    }

    // 14.5 units a row, like a Mac's.
    static let english: MacKeyboardLayout = {
        let locale = MacKeyboardLayoutID.english.locale
        return MacKeyboardLayout(
            numberRow: numberKeys([
                ("`", "~"), ("1", "!"), ("2", "@"), ("3", "#"), ("4", "$"), ("5", "%"), ("6", "^"), ("7", "&"),
                ("8", "*"), ("9", "("), ("0", ")"), ("-", "_"), ("=", "+"),
            ]) + [backspace(1.5)],
            topRow: [tab(1.5)] + letters("qwertyuiop", locale: locale) + characters([("[", "{"), ("]", "}"), ("\\", "|")]),
            homeRow: [capsLock(1.75)] + letters("asdfghjkl", locale: locale) + characters([(";", ":"), ("'", "\"")])
                + [returnKey(1.75)],
            bottomRow: [shift(2.25)] + letters("zxcvbnm", locale: locale) + characters([(",", "<"), (".", ">"), ("/", "?")])
                + [shift(2.25)],
            spaceRow: fullSpaceRow,
            symbolRows: nil
        )
    }()

    static let turkish: MacKeyboardLayout = {
        let locale = MacKeyboardLayoutID.turkish.locale
        return MacKeyboardLayout(
            numberRow: numberKeys([
                ("\"", "é"), ("1", "!"), ("2", "'"), ("3", "^"), ("4", "+"), ("5", "%"), ("6", "&"), ("7", "/"),
                ("8", "("), ("9", ")"), ("0", "="), ("*", "?"), ("-", "_"),
            ]) + [backspace(1.5)],
            topRow: [tab(1.5)] + letters("qwertyuıopğü", locale: locale) + characters([(",", ";")]),
            homeRow: [capsLock(1.75)] + letters("asdfghjklşi", locale: locale) + [returnKey(1.75)],
            bottomRow: [shift(1.75)] + characters([("<", ">")]) + letters("zxcvbnmöç", locale: locale)
                + characters([(".", ":")]) + [shift(1.75)],
            spaceRow: fullSpaceRow,
            symbolRows: nil
        )
    }()

    // 12 units a row.
    static let compactEnglish: MacKeyboardLayout = {
        let locale = MacKeyboardLayoutID.english.locale
        return MacKeyboardLayout(
            numberRow: [escape] + numberKeys([
                ("1", "!"), ("2", "@"), ("3", "#"), ("4", "$"), ("5", "%"), ("6", "^"), ("7", "&"), ("8", "*"),
                ("9", "("), ("0", ")"),
            ]) + [backspace(1)],
            topRow: [tab(2)] + letters("qwertyuiop", locale: locale),
            homeRow: [capsLock(1.25)] + letters("asdfghjkl", locale: locale) + [returnKey(1.75)],
            bottomRow: [shift(2)] + letters("zxcvbnm", locale: locale) + characters([(",", "<"), (".", ">"), ("/", "?")]),
            spaceRow: compactSpaceRow(space: 3.5),
            symbolRows: [
                [tab(2)] + symbols("[]{}<>\\|~`"),
                [capsLock(1.25)] + symbols("-_=+;:'\"?") + [returnKey(1.75)],
                [shift(2)] + symbols("@#$%^&*") + characters([(",", "<"), (".", ">"), ("/", "?")]),
            ]
        )
    }()

    // 13 units a row: Turkish has two more letters.
    static let compactTurkish: MacKeyboardLayout = {
        let locale = MacKeyboardLayoutID.turkish.locale
        return MacKeyboardLayout(
            numberRow: [escape] + numberKeys([
                ("1", "!"), ("2", "'"), ("3", "^"), ("4", "+"), ("5", "%"), ("6", "&"), ("7", "/"), ("8", "("),
                ("9", ")"), ("0", "="),
            ]) + [backspace(2)],
            topRow: [tab(1)] + letters("qwertyuıopğü", locale: locale),
            homeRow: [capsLock(1)] + letters("asdfghjklşi", locale: locale) + [returnKey(1)],
            bottomRow: [shift(2)] + letters("zxcvbnmöç", locale: locale) + characters([(",", ";"), (".", ":")]),
            spaceRow: compactSpaceRow(space: 4.5),
            symbolRows: [
                [tab(1)] + symbols("[]{}<>\\|~`\"é"),
                [capsLock(1)] + symbols("-_*?;:#$€@£") + [returnKey(1)],
                [shift(2)] + symbols("'^+/()=%&") + characters([(",", ";"), (".", ":")]),
            ]
        )
    }()
}
