#if os(iOS)
import Perception
import SwiftUI

struct KeyboardAccessoryBar: View {
    let controller: SessionController

    var body: some View {
        WithPerceptionTracking {
            ScrollView(.horizontal, showsIndicators: false) {
                GlassEffectContainerCompat(spacing: 10) {
                    HStack(spacing: 10) {
                        group {
                            key("Escape", text: "esc", key: .escape)
                            if !controller.usesHardwareKeyboard {
                                key("Tab", systemImage: "arrow.right.to.line") { controller.typeKey(.tab) }
                            }
                            ForEach(controller.platform == .pc ? ModifierKey.pcOrder : ModifierKey.allCases, id: \.self) { modifier in
                                ModifierKeyButton(controller: controller, modifier: modifier)
                            }
                        }
                        if !controller.usesHardwareKeyboard {
                            group {
                                key("Left Arrow", systemImage: "arrowtriangle.left.fill") { controller.typeKey(.left) }
                                key("Up Arrow", systemImage: "arrowtriangle.up.fill") { controller.typeKey(.up) }
                                key("Down Arrow", systemImage: "arrowtriangle.down.fill") { controller.typeKey(.down) }
                                key("Right Arrow", systemImage: "arrowtriangle.right.fill") { controller.typeKey(.right) }
                            }
                        }
                        group {
                            menu("Function Keys", systemImage: "fn") { FunctionKeyItems(controller: controller) }
                            menu("Quick Input", systemImage: "text.insert") { QuickInputItems(controller: controller) }
                        }
                        if UIDevice.current.userInterfaceIdiom == .phone {
                            group {
                                key("Hide Keyboard", systemImage: "keyboard.chevron.compact.down") {
                                    controller.setKeyboardVisible(false)
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                }
            }
            .labelStyle(.iconOnly)
            .buttonStyle(.plain)
            .font(.body.weight(.medium))
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            .frame(height: 64)
        }
    }

    private func group(@ViewBuilder content: () -> some View) -> some View {
        HStack(spacing: 2) {
            content()
        }
        .padding(.horizontal, 6)
        .glassEffectCompat(in: Capsule())
    }

    private func key(_ title: String.LocalizationValue, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(String(localized: title), systemImage: systemImage)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .hoverEffect()
        .accessibilityShowsLargeContentViewer()
    }

    private func key(_ title: String.LocalizationValue, text: String, key: MacKey) -> some View {
        Button {
            controller.typeKey(key)
        } label: {
            Text(verbatim: text)
                .font(.subheadline.weight(.semibold))
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(Text(String(localized: title)))
        .hoverEffect()
        .accessibilityShowsLargeContentViewer()
    }

    private func menu(_ title: String.LocalizationValue, systemImage: String, @ViewBuilder items: () -> some View) -> some View {
        Menu {
            items()
        } label: {
            Label(String(localized: title), systemImage: systemImage)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .hoverEffect()
        .accessibilityShowsLargeContentViewer()
    }
}

struct ModifierKeyButton: View {
    private static let holdDelay: TimeInterval = 0.3
    private static let doubleTapInterval: TimeInterval = 0.35

    let controller: SessionController
    let modifier: ModifierKey

    @GestureState private var isTouching = false
    @State private var isHolding = false
    @State private var moved = false
    @State private var holdTask: Task<Void, Never>?
    @State private var lastTap: TimeInterval?

    private var isLocked: Bool { controller.lockedModifiers.contains(modifier) }
    private var isActive: Bool {
        isLocked || controller.pressedModifiers.contains(modifier) || controller.stickyModifiers.contains(modifier)
    }

    private var pcLabel: (text: String, title: String.LocalizationValue)? {
        guard controller.platform == .pc else { return nil }
        return modifier.pcLabel
    }

    private var face: some View {
        Group {
            if let pcLabel {
                Text(verbatim: pcLabel.text)
                    .font(.subheadline.weight(.semibold))
            } else {
                Label(String(localized: modifier.title), systemImage: modifier.systemImage)
            }
        }
        .frame(width: 44, height: 44)
        .foregroundStyle(isActive ? Color.white : Color.primary)
        .background {
            if isActive {
                Circle().fill(Color.accentColor).frame(width: 38, height: 38)
            }
        }
        .overlay(alignment: .bottom) {
            if isLocked {
                Capsule().fill(Color.white).frame(width: 12, height: 3).padding(.bottom, 9)
            }
        }
        .contentShape(Circle())
        .opacity(controller.isControlling ? 1 : 0.4)
    }

    var body: some View {
        WithPerceptionTracking {
            face
                .simultaneousGesture(
                    DragGesture(minimumDistance: 0)
                        .updating($isTouching) { _, state, _ in state = true }
                        .onChanged { value in
                            if hypot(value.translation.width, value.translation.height) > 10 {
                                moved = true
                            }
                        }
                        .onEnded { _ in
                            guard !isHolding, !moved else { return }
                            tapped()
                        }
                )
                .onChangeCompat(of: isTouching) { touching in
                    touching ? touchBegan() : touchEnded()
                }
                .allowsHitTesting(controller.isControlling)
                .selectionFeedbackCompat(trigger: isActive)
                .hoverEffect()
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(String(localized: pcLabel?.title ?? modifier.title)))
                .accessibilityValue(Text(String(localized: accessibilityValue)))
                .accessibilityAddTraits(.isButton)
                .accessibilityAddTraits(isActive ? AccessibilityTraits.isSelected : [])
                .accessibilityHint(Text("Applies to the next key or click."))
                .accessibilityAction { controller.toggleSticky(modifier) }
                .accessibilityAction(named: Text(isLocked ? "Unlock" : "Lock")) { controller.setModifier(modifier, locked: !isLocked) }
                .accessibilityShowsLargeContentViewer {
                    Label(String(localized: modifier.title), systemImage: modifier.systemImage)
                }
                .disabled(!controller.isControlling)
        }
    }

    private var accessibilityValue: String.LocalizationValue {
        if isLocked { return "Locked" }
        if isActive { return "On" }
        return "Off"
    }

    private func touchBegan() {
        moved = false
        holdTask = Task {
            try? await Task.sleep(seconds: Self.holdDelay)
            guard !Task.isCancelled, !moved else { return }
            isHolding = true
            controller.setModifier(modifier, pressed: true)
        }
    }

    private func touchEnded() {
        holdTask?.cancel()
        holdTask = nil
        guard isHolding else { return }
        isHolding = false
        controller.setModifier(modifier, pressed: false)
    }

    private func tapped() {
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
#endif
