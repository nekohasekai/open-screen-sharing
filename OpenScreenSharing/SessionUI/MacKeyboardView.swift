#if os(iOS)
import Perception
import SwiftUI
import UIKit

/// A Mac keyboard drawn on screen. Keys go to the computer as they're touched; modifier keys share their state with
/// the keyboard accessory bar.
struct MacKeyboardView: View {
    static let keySpacing: CGFloat = 5
    static let padding: CGFloat = 4

    let controller: SessionController
    let layout: MacKeyboardLayoutID
    let rowHeight: CGFloat
    let isCompact: Bool

    @State private var capsLock = false
    @State private var fnOn = false
    @State private var symbolsOn = false

    static let rows = 5

    static func height(rows: Int, rowHeight: CGFloat) -> CGFloat {
        CGFloat(rows) * rowHeight + CGFloat(rows - 1) * keySpacing + padding * 2
    }

    private var shiftOn: Bool {
        controller.stickyModifiers.contains(.shift) || controller.lockedModifiers.contains(.shift)
            || controller.pressedModifiers.contains(.shift)
    }

    var body: some View {
        WithPerceptionTracking {
            VStack(spacing: Self.keySpacing) {
                ForEach(Array(layout.layout(compact: isCompact).rows(showsSymbols: symbolsOn).enumerated()), id: \.offset) { _, row in
                    GeometryReader { proxy in
                        let units = row.reduce(0) { $0 + $1.width }
                        let unit = (proxy.size.width - Self.keySpacing * CGFloat(row.count - 1)) / max(units, 1)
                        HStack(spacing: Self.keySpacing) {
                            ForEach(Array(row.enumerated()), id: \.offset) { _, key in
                                keyView(key)
                                    .frame(width: max(unit * key.width, 0))
                            }
                        }
                    }
                    .frame(height: rowHeight)
                }
            }
            .padding(Self.padding)
            .font(.system(size: max(11, rowHeight * 0.42)))
            .foregroundStyle(Color.white)
            .opacity(controller.isControlling ? 1 : 0.5)
            .allowsHitTesting(controller.isControlling)
        }
    }

    @ViewBuilder
    private func keyView(_ key: MacKeyboardKey) -> some View {
        switch key.kind {
        case .character(let base, let shifted, let isLetter):
            if fnOn, let number = key.fnFunction {
                KeyCap(repeats: false) { press(.function(number)) } face: { functionLabel(number) }
            } else {
                let upper = isLetter ? shiftOn != capsLock : shiftOn
                KeyCap(repeats: true) {
                    controller.enterText(upper ? shifted : base)
                } face: {
                    if isLetter {
                        Text(verbatim: upper ? shifted : base)
                    } else {
                        VStack(spacing: 0) {
                            Text(verbatim: shifted).font(.system(size: max(9, rowHeight * 0.3)))
                                .opacity(shiftOn ? 1 : 0.6)
                            Text(verbatim: base).opacity(shiftOn ? 0.6 : 1)
                        }
                        // One element, read as the character the key types now.
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text(verbatim: shiftOn ? shifted : base))
                    }
                }
            }
        case .key(let macKey, let label):
            let target = fnOn && macKey == .backspace ? MacKey.forwardDelete : macKey
            KeyCap(isSpecial: true, repeats: macKey != .escape && macKey != .tab) {
                press(target)
            } face: {
                switch label {
                case .text(let text): Text(verbatim: text).font(.system(size: max(10, rowHeight * 0.32)))
                case .symbol(let name): Image(systemName: target == .forwardDelete ? "delete.right" : name)
                }
            }
            .accessibilityLabel(Text(verbatim: label.accessibilityText))
        case .modifier(let modifier):
            MacModifierKeyCap(controller: controller, modifier: modifier, rowHeight: rowHeight)
        case .capsLock:
            KeyCap(isSpecial: true, isOn: capsLock, repeats: false) {
                capsLock.toggle()
            } face: {
                Image(systemName: capsLock ? "capslock.fill" : "capslock")
            }
            .accessibilityLabel(Text("Caps Lock"))
        case .symbols:
            KeyCap(isSpecial: true, isOn: symbolsOn, repeats: false) {
                symbolsOn.toggle()
            } face: {
                Text(verbatim: symbolsOn ? "abc" : "#+=").font(.system(size: max(10, rowHeight * 0.3)))
            }
            .accessibilityLabel(Text("Symbols"))
        case .fn:
            KeyCap(isSpecial: true, isOn: fnOn, repeats: false) {
                fnOn.toggle()
            } face: {
                Text(verbatim: "fn").font(.system(size: max(10, rowHeight * 0.32)))
            }
        case .arrows:
            let left: MacKey = fnOn ? .home : .left
            let up: MacKey = fnOn ? .pageUp : .up
            let down: MacKey = fnOn ? .pageDown : .down
            let right: MacKey = fnOn ? .end : .right
            HStack(spacing: 2) {
                KeyCap(isSpecial: true, repeats: true) { press(left) } face: { Image(systemName: "arrowtriangle.left.fill") }
                VStack(spacing: 2) {
                    KeyCap(isSpecial: true, repeats: true) { press(up) } face: { Image(systemName: "arrowtriangle.up.fill") }
                    KeyCap(isSpecial: true, repeats: true) { press(down) } face: { Image(systemName: "arrowtriangle.down.fill") }
                }
                KeyCap(isSpecial: true, repeats: true) { press(right) } face: { Image(systemName: "arrowtriangle.right.fill") }
            }
            .imageScale(.small)
        }
    }

    private func functionLabel(_ number: Int) -> some View {
        Text(verbatim: "F\(number)").font(.system(size: max(9, rowHeight * 0.3)))
    }

    private func press(_ key: MacKey) {
        controller.typeKey(key)
        fnOn = false
    }
}

private extension MacKeyboardKey.Label {
    var accessibilityText: String {
        switch self {
        case .text(let text): text
        case .symbol(let name): name
        }
    }
}

private enum KeyFeedback {
    @MainActor static let generator = UIImpactFeedbackGenerator(style: .light)

    @MainActor static func tap() {
        generator.impactOccurred(intensity: 0.6)
    }
}

private struct KeyCap<Face: View>: View {
    private static var repeatDelay: TimeInterval { 0.45 }
    private static var repeatInterval: TimeInterval { 0.06 }

    var isSpecial = false
    var isOn = false
    let repeats: Bool
    let action: () -> Void
    @ViewBuilder let face: Face

    @State private var isDown = false
    @State private var repeatTask: Task<Void, Never>?

    init(isSpecial: Bool = false, isOn: Bool = false, repeats: Bool, action: @escaping () -> Void, @ViewBuilder face: () -> Face) {
        self.isSpecial = isSpecial
        self.isOn = isOn
        self.repeats = repeats
        self.action = action
        self.face = face()
    }

    var body: some View {
        face
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 5, style: .continuous).fill(fill))
            .overlay(alignment: .topLeading) {
                if isOn {
                    Circle().fill(Color.green).frame(width: 4, height: 4).padding(4)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in if !isDown { down() } }
                    .onEnded { _ in up() }
            )
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { action() }
    }

    private var fill: Color {
        if isDown { return Color(white: 0.55) }
        return isSpecial ? Color(white: 0.2) : Color(white: 0.3)
    }

    private func down() {
        isDown = true
        KeyFeedback.tap()
        action()
        guard repeats else { return }
        repeatTask = Task { @MainActor in
            try? await Task.sleep(seconds: Self.repeatDelay)
            while !Task.isCancelled {
                action()
                try? await Task.sleep(seconds: Self.repeatInterval)
            }
        }
    }

    private func up() {
        isDown = false
        repeatTask?.cancel()
        repeatTask = nil
    }
}

/// A modifier key that acts like the ones on the keyboard accessory bar: a tap applies it to the next key or click,
/// a double tap locks it, and holding it keeps it down.
private struct MacModifierKeyCap: View {
    private static let holdDelay: TimeInterval = 0.3
    private static let doubleTapInterval: TimeInterval = 0.35

    let controller: SessionController
    let modifier: ModifierKey
    let rowHeight: CGFloat

    @State private var isDown = false
    @State private var isHolding = false
    @State private var holdTask: Task<Void, Never>?
    @State private var lastTap: TimeInterval?

    private var isLocked: Bool { controller.lockedModifiers.contains(modifier) }
    private var isActive: Bool {
        isLocked || controller.pressedModifiers.contains(modifier) || controller.stickyModifiers.contains(modifier)
    }

    var body: some View {
        WithPerceptionTracking {
            VStack(spacing: 0) {
                Text(verbatim: modifier.symbol)
                if rowHeight >= 34 {
                    Text(verbatim: modifier.keyName).font(.system(size: max(8, rowHeight * 0.22)))
                }
            }
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .foregroundStyle(isActive ? Color.white : Color.white.opacity(0.9))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(isDown ? Color(white: 0.55) : isActive ? Color.accentColor : Color(white: 0.2))
            )
            .overlay(alignment: .topLeading) {
                if isLocked {
                    Circle().fill(Color.white).frame(width: 4, height: 4).padding(4)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in if !isDown { touchBegan() } }
                    .onEnded { _ in touchEnded() }
            )
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(String(localized: modifier.title)))
            .accessibilityAddTraits(.isButton)
            .accessibilityAddTraits(isActive ? AccessibilityTraits.isSelected : [])
            .accessibilityAction { controller.toggleSticky(modifier) }
        }
    }

    private func touchBegan() {
        isDown = true
        KeyFeedback.tap()
        holdTask = Task { @MainActor in
            try? await Task.sleep(seconds: Self.holdDelay)
            guard !Task.isCancelled else { return }
            isHolding = true
            controller.setModifier(modifier, pressed: true)
        }
    }

    private func touchEnded() {
        isDown = false
        holdTask?.cancel()
        holdTask = nil
        if isHolding {
            isHolding = false
            controller.setModifier(modifier, pressed: false)
            return
        }
        let now = ProcessInfo.processInfo.systemUptime
        if isLocked {
            controller.setModifier(modifier, locked: false)
        } else if let lastTap, now - lastTap < Self.doubleTapInterval, controller.stickyModifiers.contains(modifier) {
            controller.setModifier(modifier, locked: true)
        } else {
            controller.toggleSticky(modifier)
        }
        lastTap = now
    }
}

private extension ModifierKey {
    var keyName: String {
        switch self {
        case .control: "control"
        case .option: "option"
        case .shift: "shift"
        case .command: "command"
        }
    }
}
#endif
