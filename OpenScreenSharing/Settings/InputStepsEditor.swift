#if os(iOS)
import GameController
#endif
import Perception
import SwiftUI

struct InputStep: Identifiable {
    let id = UUID()
    let target: MappingTarget?
    var combo: KeyCombo
    var text: String

    init(text: String) {
        target = nil
        combo = .empty
        self.text = text
    }

    init(key combo: KeyCombo, target: MappingTarget) {
        self.target = target
        self.combo = combo
        text = ""
    }

    var isComplete: Bool {
        guard target != nil else { return !text.isEmpty }
        return !combo.keys.isEmpty && (combo.keys.count > 1 || combo.shortcuts.contains { $0.hasCommandModifier || $0.isNamed })
    }

    mutating func clearKey() {
        combo.keys = []
    }

    typealias Modifier = (flag: EventModifiers, label: String, name: String, title: String)

    static func modifiers(for target: MappingTarget) -> [Modifier] {
        target == .pc ? pcModifiers : appleModifiers
    }

    private static let appleModifiers: [Modifier] = [
        (.control, "⌃", "Control", String(localized: "Control Key")),
        (.option, "⌥", "Option", String(localized: "Option Key")),
        (.shift, "⇧", "Shift", String(localized: "Shift Key")),
        (.command, "⌘", "Command", String(localized: "Command Key")),
    ]

    private static let pcModifiers: [Modifier] = [
        (.control, "Ctrl", "Ctrl", String(localized: "Ctrl Key")),
        (.option, "Alt", "Alt", String(localized: "Alt Key")),
        (.shift, "Shift", "Shift", String(localized: "Shift Key")),
        (.command, "Win", "Windows", String(localized: "Windows Key")),
    ]

    mutating func toggle(_ modifier: EventModifiers, _ on: Bool) {
        var flags = combo.eventModifiers
        if on { flags.insert(modifier) } else { flags.remove(modifier) }
        combo = KeyCombo(keys: combo.keys, modifiers: flags)
    }

    mutating func toggle(_ key: KeyGroupChoice.Key) {
        if key.isCommand {
            combo = combo.keys == [key.name] ? .empty : KeyCombo(keys: [key.name], modifiers: [])
        } else if combo.command != nil {
            combo = KeyCombo(keys: [key.name], modifiers: [])
        } else if let index = combo.keys.firstIndex(of: key.name) {
            combo.keys.remove(at: index)
        } else {
            combo.keys.append(key.name)
        }
    }

    mutating func record(_ pressed: KeyShortcut) -> Bool {
        guard let target, pressed.chord(for: target) != nil else { return false }
        let modifiers = pressed.eventModifiers.isEmpty ? combo.eventModifiers : pressed.eventModifiers
        combo = KeyCombo(keys: [pressed.key], modifiers: modifiers)
        return true
    }
}

struct InputStepsEditor: View {
    @Binding var steps: [InputStep]
    let keyKinds: [(target: MappingTarget, title: LocalizedStringKey)]
    @Binding var recording: UUID?
    @Binding var selected: UUID?
    @FocusState private var focusedText: UUID?

    private func binding(for step: InputStep) -> Binding<InputStep> {
        Binding(
            get: { steps.first { $0.id == step.id } ?? step },
            set: { value in
                guard let index = steps.firstIndex(where: { $0.id == step.id }) else { return }
                steps[index] = value
            }
        )
    }

    var body: some View {
        WithPerceptionTracking {
            ForEach(steps) { step in
                let binding = self.binding(for: step)
                HStack {
                    #if os(macOS)
                    Button {
                        recording = nil
                        steps.removeAll { $0.id == step.id }
                    } label: {
                        Image(systemName: "minus.circle.fill")
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .red)
                            .font(.title2)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Delete"))
                    #endif
                    if let target = step.target {
                        #if os(iOS)
                        Button {
                            selected = step.id
                        } label: {
                            HStack {
                                Text(keyKinds.first { $0.target == target }?.title ?? "Key")
                                Spacer()
                                Text(verbatim: step.combo.displayName(for: target))
                                    .foregroundStyle(.secondary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        #else
                        if step.combo.command == nil {
                            ForEach(InputStep.modifiers(for: target), id: \.label) { modifier in
                                Toggle(isOn: Binding(
                                    get: { step.combo.eventModifiers.contains(modifier.flag) },
                                    set: { binding.wrappedValue.toggle(modifier.flag, $0) }
                                )) {
                                    Text(verbatim: modifier.label)
                                }
                                .toggleStyle(.button)
                                .accessibilityLabel(Text(verbatim: modifier.title))
                            }
                        }
                        KeyControl(step: binding, target: target, recording: $recording)
                        Spacer(minLength: 0)
                        #endif
                    } else if #available(iOS 16, *) {
                        TextField("Text", text: binding.text, prompt: Text("Text"), axis: .vertical)
                            .labelsHidden()
                            .focused($focusedText, equals: step.id)
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                    } else {
                        TextField("Text", text: binding.text)
                            .focused($focusedText, equals: step.id)
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                    }
                    #if os(macOS)
                    Image(systemName: "line.3.horizontal")
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                    #endif
                }
                #if os(macOS)
                .contextMenu {
                    Button {
                        guard let index = steps.firstIndex(where: { $0.id == step.id }), index > 0 else { return }
                        steps.swapAt(index, index - 1)
                    } label: {
                        Label("Move Up", systemImage: "arrow.up")
                    }
                    .disabled(steps.first?.id == step.id)
                    Button {
                        guard let index = steps.firstIndex(where: { $0.id == step.id }), index < steps.count - 1 else { return }
                        steps.swapAt(index, index + 1)
                    } label: {
                        Label("Move Down", systemImage: "arrow.down")
                    }
                    .disabled(steps.last?.id == step.id)
                }
                #endif
            }
            .onMove { source, destination in steps.move(fromOffsets: source, toOffset: destination) }
            .onDelete { offsets in
                recording = nil
                steps.remove(atOffsets: offsets)
            }
            Menu {
                ForEach(keyKinds, id: \.target) { kind in
                    Button {
                        let step = InputStep(key: .empty, target: kind.target)
                        steps.append(step)
                        #if os(iOS)
                        selected = step.id
                        #endif
                    } label: {
                        Label(kind.title, systemImage: "keyboard")
                    }
                }
                Button {
                    let step = InputStep(text: "")
                    steps.append(step)
                    focusedText = step.id
                } label: {
                    Label("Text", systemImage: "character.cursor.ibeam")
                }
            } label: {
                HStack {
                    Image(systemName: "plus.circle.fill")
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, .green)
                        .font(.title2)
                        .accessibilityHidden(true)
                    Text("Add")
                        .foregroundStyle(.primary)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            #if os(macOS)
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            #endif
        }
    }
}

struct KeyGroupChoice: Identifiable {
    struct Key: Identifiable {
        let name: String
        let symbol: String?
        let label: String
        let isCommand: Bool

        var id: String { name }
    }

    let title: String
    let keys: [Key]

    var id: String { title }

    var hasSymbols: Bool { keys.contains { $0.symbol != nil } }

    static func all(for target: MappingTarget) -> [KeyGroupChoice] {
        let characters = [
            (String(localized: "Letters"), "abcdefghijklmnopqrstuvwxyz"),
            (String(localized: "Numbers"), "1234567890"),
            (String(localized: "Symbols"), "`-=[]\\;',./"),
        ]
        .map { title, characters in
            KeyGroupChoice(
                title: title,
                keys: characters.map { Key(name: String($0), symbol: nil, label: String($0).uppercased(), isCommand: false) })
        }
        let named = KeyShortcut.KeyGroup.allCases.compactMap { group -> KeyGroupChoice? in
            let keys = group.keys(for: target)
            guard !keys.isEmpty else { return nil }
            return KeyGroupChoice(
                title: group.title,
                keys: keys.map { key in
                    let label = key.label(for: target)
                    return Key(
                        name: key.name, symbol: target == .apple && key.symbol != label ? key.symbol : nil, label: label,
                        isCommand: key.command != nil)
                })
        }
        return characters + named
    }
}

#if os(macOS)
struct KeyControl: View {
    @Binding var step: InputStep
    let target: MappingTarget
    @Binding var recording: UUID?

    var body: some View {
        let keys = step.combo.keys
        let label = keys.isEmpty ? nil : KeyCombo(keys: keys, modifiers: []).displayName(for: target)
        let isRecording = recording == step.id
        Menu {
            Button {
                recording = step.id
            } label: {
                Label("Type Key", systemImage: "keyboard")
            }
            ForEach(KeyGroupChoice.all(for: target)) { group in
                Menu(group.title) {
                    ForEach(group.keys) { key in
                        Toggle(isOn: Binding(
                            get: { keys.contains(key.name) },
                            set: { _ in
                                recording = nil
                                step.toggle(key)
                            }
                        )) {
                            Text(verbatim: key.label)
                        }
                    }
                }
            }
        } label: {
            ShortcutButtonLabel(isRecording: isRecording, shortcut: label, prompt: "Type Key")
        }
        .buttonStyle(.bordered)
        .tint(isRecording ? .accentColor : nil)
        .id(isRecording)
        .accessibilityLabel(Text("Key"))
        .accessibilityValue(isRecording ? Text("Type Key") : label.map { Text(verbatim: $0) } ?? Text("None"))
    }
}
#endif

#if os(iOS)
private struct KeyStepView: View {
    @Binding var step: InputStep
    let target: MappingTarget
    let title: LocalizedStringKey
    @Binding var recording: UUID?

    private let keyboard = ConnectedKeyboard.shared

    var body: some View {
        WithPerceptionTracking {
            let isRecording = recording == step.id
            Form {
                if keyboard.isConnected {
                    Section {
                        HStack {
                            Text("Key")
                            Spacer()
                            ShortcutButton(
                                isRecording: isRecording, title: String(localized: "Key"),
                                shortcut: step.combo.keys.isEmpty ? nil : step.combo.displayName(for: target), prompt: "Type Key"
                            ) {
                                recording = isRecording ? nil : step.id
                            }
                        }
                    }
                }
                Section("Modifier Keys") {
                    ForEach(InputStep.modifiers(for: target), id: \.label) { modifier in
                        let isOn = step.combo.eventModifiers.contains(modifier.flag)
                        CheckmarkRow(symbol: target == .apple ? modifier.label : nil, showsSymbol: target == .apple, title: modifier.name, isOn: isOn) {
                            step.toggle(modifier.flag, !isOn)
                        }
                        .accessibilityLabel(Text(verbatim: modifier.title))
                    }
                }
                .disabled(step.combo.command != nil)
                Section("Key") {
                    ForEach(KeyGroupChoice.all(for: target)) { group in
                        let selected = group.keys.filter { step.combo.keys.contains($0.name) }
                        NavigationLink {
                            KeyListView(step: $step, group: group, recording: $recording)
                        } label: {
                            HStack {
                                Text(verbatim: group.title)
                                Spacer()
                                Text(verbatim: selected.map(\.label).joined(separator: ", "))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                    }
                }
            }
            .environment(\.editMode, .constant(.inactive))
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .onDisappear { recording = nil }
        }
    }
}

private struct KeyListView: View {
    @Binding var step: InputStep
    let group: KeyGroupChoice
    @Binding var recording: UUID?

    var body: some View {
        Form {
            ForEach(group.keys) { key in
                CheckmarkRow(symbol: key.symbol, showsSymbol: group.hasSymbols, title: key.label, isOn: step.combo.keys.contains(key.name)) {
                    recording = nil
                    step.toggle(key)
                }
            }
        }
        .navigationTitle(Text(verbatim: group.title))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct CheckmarkRow: View {
    let symbol: String?
    let showsSymbol: Bool
    let title: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                if showsSymbol {
                    Text(verbatim: symbol ?? "")
                        .foregroundStyle(.secondary)
                        .frame(width: 28, alignment: .leading)
                }
                Text(verbatim: title)
                Spacer()
                if isOn {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}
#endif

extension View {
    @ViewBuilder
    func inputStepDestination(
        steps: Binding<[InputStep]>, selected: Binding<UUID?>, keyKinds: [(target: MappingTarget, title: LocalizedStringKey)],
        recording: Binding<UUID?>
    ) -> some View {
        #if os(iOS)
        navigationDestinationCompat(
            isPresented: Binding(
                get: { selected.wrappedValue != nil },
                set: { presented in
                    guard !presented else { return }
                    steps.wrappedValue.removeAll { $0.id == selected.wrappedValue && $0.combo.keys.isEmpty }
                    selected.wrappedValue = nil
                }
            )
        ) {
            if let index = steps.wrappedValue.firstIndex(where: { $0.id == selected.wrappedValue }), let target = steps.wrappedValue[index].target {
                KeyStepView(
                    step: steps[index], target: target, title: keyKinds.first { $0.target == target }?.title ?? "Key", recording: recording)
            }
        }
        #else
        self
        #endif
    }
}

#if os(iOS)
@Perceptible
final class ConnectedKeyboard {
    static let shared = ConnectedKeyboard()

    private(set) var isConnected = GCKeyboard.coalesced != nil

    private init() {
        NotificationCenter.default.addObserver(forName: .GCKeyboardDidConnect, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { ConnectedKeyboard.shared.isConnected = true }
        }
        NotificationCenter.default.addObserver(forName: .GCKeyboardDidDisconnect, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { ConnectedKeyboard.shared.isConnected = false }
        }
    }
}
#endif
